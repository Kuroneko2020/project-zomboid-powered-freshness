package dev.poweredfreshness.instrument;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.*;
import java.util.function.Consumer;
import net.bytebuddy.jar.asm.*;

/** Checked instruction edits for the locally inspected 42.20.4 game contract. */
public final class GameTransformer implements ClassFileTransformer {
    public static final String FOOD = "zombie/inventory/types/Food";
    public static final String ITEM = "zombie/inventory/InventoryItem";
    public static final String CONTAINER = "zombie/inventory/ItemContainer";
    public static final String OBJECT = "zombie/iso/IsoObject";
    public static final String GENERATOR = "zombie/iso/objects/IsoGenerator";
    public static final String ITEM_USER = "zombie/inventory/ItemUser";
    public static final String DRAINABLE = "zombie/inventory/types/DrainableComboItem";
    private static final String HOOKS = "dev/poweredfreshness/runtime/Hooks";
    public static final Set<String> TARGETS = Collections.unmodifiableSet(new LinkedHashSet<>(
        List.of(FOOD, ITEM, CONTAINER, OBJECT, GENERATOR, ITEM_USER, DRAINABLE)));
    private final Consumer<Throwable> onFailure;
    private volatile Throwable failure;

    public GameTransformer() { this(t -> System.err.println("[PoweredFreshness] transformation rejected: " + t)); }
    public GameTransformer(Consumer<Throwable> onFailure) { this.onFailure = Objects.requireNonNull(onFailure); }
    public Throwable failure() { return failure; }

    @Override public byte[] transform(ClassLoader loader, String name, Class<?> redefining,
                                      ProtectionDomain domain, byte[] bytes) {
        if (!TARGETS.contains(name)) return null;
        try { return transformBytes(name, bytes); }
        catch (Throwable t) {
            failure = t;
            onFailure.accept(t);
            return null;
        }
    }

    /** Preflight ALL targets before installing: transformation failures must disable runtime callbacks. */
    public void preflight(ClassLoader loader) throws java.io.IOException {
        for (String name : TARGETS) {
            try (var stream = loader.getResourceAsStream(name + ".class")) {
                if (stream == null) throw new IllegalStateException("Missing game class " + name);
                transformBytes(name, stream.readAllBytes());
            }
        }
    }

    public byte[] transformBytes(String name, byte[] bytes) {
        if (!TARGETS.contains(name)) return null;
        ClassReader reader = new ClassReader(bytes);
        check(name.equals(reader.getClassName()), "Class identity mismatch: " + name);
        Map<String,Integer> actual = inspect(reader, name);
        Map<String,Integer> expected = expected(name);
        check(actual.equals(expected), "Unsupported game instructions for " + name + ": expected " + expected + ", got " + actual);
        ClassWriter writer = new ClassWriter(reader, 0);
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override public MethodVisitor visitMethod(int access,String method,String desc,String signature,String[] exceptions) {
                MethodVisitor base = super.visitMethod(access,method,desc,signature,exceptions);
                return new MethodVisitor(Opcodes.ASM9, base) {
                    int fuelWrites;
                    boolean loopIndexInitialized;
                    boolean age = name.equals(FOOD) && method.equals("updateAge") && desc.equals("(Z)V");
                    boolean rot = name.equals(FOOD) && method.equals("updateRotting") && desc.equals("(L"+CONTAINER+";)V");
                    boolean genUpdate = name.equals(GENERATOR) && method.equals("update") && desc.equals("()V");
                    boolean worldAdd = (name.equals(OBJECT)||name.equals(GENERATOR)) && method.equals("addToWorld") && desc.equals("()V");
                    boolean worldRemove = (name.equals(OBJECT)||name.equals(GENERATOR)) && method.equals("removeFromWorld") && desc.equals("()V");
                    @Override public void visitCode() {
                        super.visitCode();
                        if (rot) {
                            callThis("protectRotting", "(L"+FOOD+";)Z");
                            Label proceed = new Label();
                            super.visitJumpInsn(Opcodes.IFEQ, proceed);
                            super.visitInsn(Opcodes.RETURN);
                            super.visitLabel(proceed);
                            super.visitFrame(Opcodes.F_NEW,2,new Object[]{FOOD,CONTAINER},0,new Object[0]);
                        }
                        if (worldRemove) callThis("worldRemoving","(L"+OBJECT+";)V");
                        if (genUpdate) callThis("generatorBefore","(L"+GENERATOR+";)V");
                        if (name.equals(GENERATOR) && desc.equals("(Z)V") && (method.equals("setActivated") || method.equals("setConnected"))) {
                            super.visitVarInsn(Opcodes.ALOAD,0);
                            super.visitVarInsn(Opcodes.ILOAD,1);
                            hook(method.equals("setActivated") ? "generatorSwitch" : "generatorConnected", "(L"+GENERATOR+";Z)V");
                        }
                    }
                    @Override public void visitVarInsn(int op,int variable) {
                        if (genUpdate && op==Opcodes.ISTORE && variable==8) loopIndexInitialized=true;
                        super.visitVarInsn(op,variable);
                    }
                    @Override public void visitFrame(int type,int nLocal,Object[] local,int nStack,Object[] stack) {
                        if (genUpdate && fuelWrites==0 && loopIndexInitialized && nLocal==8) {
                            // javac drops the for-loop index at its exit label. All incoming edges
                            // have initialized slot 8, which the accounting callback now consumes.
                            Object[] kept=Arrays.copyOf(local,9);
                            kept[8]=Opcodes.INTEGER;
                            super.visitFrame(Opcodes.F_NEW,9,kept,nStack,stack);
                        } else super.visitFrame(type,nLocal,local,nStack,stack);
                    }
                    @Override public void visitInsn(int op) {
                        if (op == Opcodes.RETURN) {
                            if (worldAdd) callThis("worldAdded","(L"+OBJECT+";)V");
                            if (genUpdate) callThis("generatorAfter","(L"+GENERATOR+";)V");
                        }
                        super.visitInsn(op);
                    }
                    @Override public void visitFieldInsn(int op,String owner,String field,String descriptor) {
                        if (op == Opcodes.PUTFIELD && age && owner.equals(FOOD) && field.equals("age") && descriptor.equals("F")) {
                            hook("writeAge","(L"+FOOD+";F)V");
                            return;
                        }
                        if (isContainerWrite(name,op,owner,field,descriptor)) {
                            boolean restoring = name.equals(CONTAINER) && method.equals("load")
                                && desc.equals("(Ljava/nio/ByteBuffer;I)Ljava/util/ArrayList;");
                            hook(restoring ? "restoreContainer" : "assignContainer","(L"+ITEM+";L"+CONTAINER+";)V");
                            return;
                        }
                        if (genUpdate && op == Opcodes.PUTFIELD && owner.equals(GENERATOR) && field.equals("fuel") && descriptor.equals("F") && fuelWrites++ == 0) {
                            // Original [generator, newFuel] remains below callback arguments.
                            super.visitVarInsn(Opcodes.ALOAD,0);
                            super.visitVarInsn(Opcodes.ILOAD,8);
                            super.visitVarInsn(Opcodes.ILOAD,4);
                            super.visitVarInsn(Opcodes.ILOAD,6);
                            super.visitVarInsn(Opcodes.FLOAD,5);
                            hook("generatorAccounting","(L"+GENERATOR+";IIIF)V");
                        }
                        super.visitFieldInsn(op,owner,field,descriptor);
                    }
                    @Override public void visitMaxs(int stack,int locals) { super.visitMaxs(stack + (genUpdate ? 5 : 2),locals); }
                    void callThis(String hookName,String descriptor) { super.visitVarInsn(Opcodes.ALOAD,0); hook(hookName,descriptor); }
                    void hook(String hookName,String descriptor) { super.visitMethodInsn(Opcodes.INVOKESTATIC,HOOKS,hookName,descriptor,false); }
                };
            }
        },ClassReader.EXPAND_FRAMES);
        return writer.toByteArray();
    }

    private static Map<String,Integer> inspect(ClassReader reader,String name) {
        Map<String,Integer> counts = new TreeMap<>();
        reader.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override public MethodVisitor visitMethod(int a,String method,String desc,String s,String[] e) {
                String key = method + desc;
                if (isSpecialMethod(name,key)) counts.merge("method:"+key,1,Integer::sum);
                return new MethodVisitor(Opcodes.ASM9) {
                    int fuelWrites;
                    boolean loopIndexInitialized;
                    final Deque<String> recent = new ArrayDeque<>();
                    void remember(String instruction) { recent.addLast(instruction); if (recent.size()>5) recent.removeFirst(); }
                    @Override public void visitVarInsn(int op,int index) {
                        if (name.equals(GENERATOR) && key.equals("update()V") && op==Opcodes.ISTORE && index==8) loopIndexInitialized=true;
                        remember(op+":"+index);
                    }
                    @Override public void visitFrame(int type,int nLocal,Object[] local,int nStack,Object[] stack) {
                        if (name.equals(GENERATOR) && key.equals("update()V") && fuelWrites==0 && loopIndexInitialized && nLocal==8) {
                            check(nStack==0 && local[4].equals(Opcodes.INTEGER) && local[5].equals(Opcodes.FLOAT) && local[6].equals(Opcodes.INTEGER),"Generator loop exit locals changed");
                            counts.merge("frame:loopExit",1,Integer::sum);
                        }
                    }
                    @Override public void visitInsn(int op) {
                        if (op == Opcodes.RETURN && ((name.equals(OBJECT)||name.equals(GENERATOR)) && key.equals("addToWorld()V") || name.equals(GENERATOR) && key.equals("update()V"))) counts.merge("return:"+key,1,Integer::sum);
                        remember(Integer.toString(op));
                    }
                    @Override public void visitFieldInsn(int op,String owner,String field,String d) {
                        if (isContainerWrite(name,op,owner,field,d)) counts.merge("container:"+key,1,Integer::sum);
                        if (name.equals(FOOD) && key.equals("updateAge(Z)V") && op==Opcodes.PUTFIELD && field.equals("age") && d.equals("F")) {
                            check(owner.equals(FOOD),"Unexpected age field owner");
                            counts.merge("age:"+key,1,Integer::sum);
                        }
                        if (name.equals(GENERATOR) && key.equals("update()V") && op==Opcodes.PUTFIELD && field.equals("fuel") && d.equals("F")) {
                            check(owner.equals(GENERATOR),"Unexpected fuel owner");
                            if (fuelWrites++ == 0) check(recent.toString().equals("[25:0, 89, 180:"+GENERATOR+":fuel:F, 23:5, 102]"),"Generator fuel subtraction shape changed: " + recent);
                            counts.merge("fuel:"+key,1,Integer::sum);
                        }
                        remember(op+":"+owner+":"+field+":"+d);
                    }
                    @Override public void visitMethodInsn(int op,String owner,String n,String d,boolean itf) {
                        check(!owner.equals(HOOKS),"Already transformed: "+name);
                        recent.clear();
                    }
                };
            }
        },ClassReader.EXPAND_FRAMES);
        return counts;
    }
    private static boolean isContainerWrite(String name,int op,String owner,String field,String desc) {
        return Set.of(ITEM,CONTAINER,ITEM_USER,DRAINABLE).contains(name) && op==Opcodes.PUTFIELD && field.equals("container") && desc.equals("L"+CONTAINER+";") && (owner.equals(ITEM)||owner.equals(DRAINABLE));
    }
    private static boolean isSpecialMethod(String name,String key) {
        return name.equals(FOOD) && (key.equals("updateAge(Z)V")||key.equals("updateRotting(L"+CONTAINER+";)V"))
            || (name.equals(OBJECT)||name.equals(GENERATOR)) && (key.equals("addToWorld()V")||key.equals("removeFromWorld()V"))
            || name.equals(GENERATOR) && Set.of("update()V","setActivated(Z)V","setConnected(Z)V").contains(key);
    }
    private static Map<String,Integer> expected(String name) {
        Map<String,Integer> result = new TreeMap<>();
        if (name.equals(FOOD)) {
            result.put("method:updateAge(Z)V",1); result.put("age:updateAge(Z)V",1);
            result.put("method:updateRotting(L"+CONTAINER+";)V",1);
        }
        if (name.equals(ITEM)) for (String k : List.of("Use(ZZZ)V","setContainer(L"+CONTAINER+";)V")) result.put("container:"+k,1);
        if (name.equals(CONTAINER)) for (String k : List.of(
            "AddItem(L"+ITEM+";)L"+ITEM+";", "AddItem(Ljava/lang/String;)L"+ITEM+";",
            "AddItem(Ljava/lang/String;F)Z", "AddItem(Ljava/lang/String;FZ)Z", "Remove(L"+ITEM+";)V",
            "DoRemoveItem(L"+ITEM+";)V", "Remove(Ljava/lang/String;)V", "Remove(Lzombie/scripting/objects/ItemType;)L"+ITEM+";",
            "RemoveAll(Ljava/lang/String;I)Ljava/util/ArrayList;", "RemoveOneOf(Ljava/lang/String;Z)L"+ITEM+";",
            "load(Ljava/nio/ByteBuffer;I)Ljava/util/ArrayList;", "removeAllItems()V")) result.put("container:"+k,1);
        if (name.equals(ITEM_USER)) result.put("container:RemoveItem(L"+ITEM+";)V",2);
        if (name.equals(DRAINABLE)) { result.put("container:update()V",1); result.put("container:Use(ZZZ)V",1); }
        if (name.equals(OBJECT)||name.equals(GENERATOR)) {
            result.put("method:addToWorld()V",1); result.put("method:removeFromWorld()V",1);
            result.put("return:addToWorld()V",name.equals(OBJECT)?2:1);
        }
        if (name.equals(GENERATOR)) {
            for (String k : List.of("update()V","setActivated(Z)V","setConnected(Z)V")) result.put("method:"+k,1);
            result.put("return:update()V",2); result.put("fuel:update()V",2); result.put("frame:loopExit",1);
        }
        return result;
    }
    private static void check(boolean ok,String message) { if (!ok) throw new IllegalStateException(message); }
}
