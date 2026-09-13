package dev.poweredfreshness.instrument;

import java.nio.file.Path;
import java.util.*;
import java.util.jar.*;
import net.bytebuddy.jar.asm.*;

/** Tests the actual installed game bytes; accepts its JAR path as an argument. */
public final class GameTransformerTest {
    static final String HOOKS = "dev/poweredfreshness/runtime/Hooks";
    public static void main(String[] args) throws Exception {
        try (JarFile game = new JarFile(Path.of(args[0]).toFile())) {
            if (args.length > 1 && args[1].equals("audit")) {
                for (JarEntry e : Collections.list(game.entries())) {
                    if (e.getName().endsWith(".class")) audit(e.getName(), game.getInputStream(e).readAllBytes());
                }
                return;
            }
            Class<?> type = Class.forName("dev.poweredfreshness.instrument.GameTransformer");
            Object transformer = type.getConstructor().newInstance();
            var method = type.getMethod("transformBytes", String.class, byte[].class);
            for (String name : List.of("zombie/inventory/types/Food", "zombie/inventory/ItemContainer", "zombie/inventory/InventoryItem", "zombie/iso/IsoObject", "zombie/iso/objects/IsoGenerator", "zombie/inventory/ItemUser", "zombie/inventory/types/DrainableComboItem")) {
                byte[] original = game.getInputStream(game.getJarEntry(name + ".class")).readAllBytes();
                byte[] changed = (byte[]) method.invoke(transformer, name, original);
                Map<String,Integer> calls = hookCalls(changed);
                require(calls.equals(expectedCalls(name)),name + " wrong hooks " + calls);
                System.out.println(name + " " + calls);
                if (name.endsWith("/Food")) {
                    require(calls.getOrDefault("writeAge", 0) == 1, "exactly one age hook");
                    require(calls.getOrDefault("protectRotting", 0) == 1, "exactly one rotting guard");
                }
                // Applying our transformation twice must fail, never silently stack callbacks.
                boolean rejected = false;
                try { method.invoke(transformer, name, changed); }
                catch (java.lang.reflect.InvocationTargetException expected) { rejected = expected.getCause() instanceof IllegalStateException; }
                require(rejected, "repeat transformation must reject " + name);
                byte[] missing = eraseFirstHookTarget(original, name);
                rejected = false;
                try { method.invoke(transformer,name,missing); }
                catch (java.lang.reflect.InvocationTargetException expected) { rejected = expected.getCause() instanceof IllegalStateException; }
                require(rejected,"missing target must reject " + name);
            }
            System.out.println("GameTransformerTest PASS");
        }
    }
    static Map<String,Integer> expectedCalls(String name) {
        if (name.endsWith("/Food")) return Map.of("protectRotting",1,"writeAge",1);
        if (name.endsWith("/ItemContainer")) return Map.of("assignContainer",11,"restoreContainer",1);
        if (name.endsWith("/InventoryItem") || name.endsWith("/ItemUser") || name.endsWith("/DrainableComboItem")) return Map.of("assignContainer",2);
        if (name.endsWith("/IsoObject")) return Map.of("worldAdded",2,"worldRemoving",1);
        if (name.endsWith("/IsoGenerator")) return Map.of("worldAdded",1,"worldRemoving",1,"generatorBefore",1,"generatorAfter",2,"generatorAccounting",1,"generatorSwitch",1,"generatorConnected",1);
        throw new AssertionError("unknown target " + name);
    }
    static byte[] eraseFirstHookTarget(byte[] original,String name) {
        ClassReader reader = new ClassReader(original);
        ClassWriter writer = new ClassWriter(reader,0);
        reader.accept(new ClassVisitor(Opcodes.ASM9,writer) {
            boolean removed;
            public MethodVisitor visitMethod(int a,String n,String d,String s,String[] e) {
                boolean target = name.endsWith("/Food") ? n.equals("updateAge") && d.equals("(Z)V")
                    : name.endsWith("/IsoObject") || name.endsWith("/IsoGenerator") ? n.equals("addToWorld") : false;
                if (target) return null;
                return new MethodVisitor(Opcodes.ASM9,super.visitMethod(a,n,d,s,e)) {
                    public void visitFieldInsn(int op,String owner,String field,String desc) {
                        if (!removed && op==Opcodes.PUTFIELD && field.equals("container") && desc.equals("Lzombie/inventory/ItemContainer;")) { removed=true; return; }
                        super.visitFieldInsn(op,owner,field,desc);
                    }
                };
            }
        },0);
        return writer.toByteArray();
    }
    static Map<String,Integer> hookCalls(byte[] bytes) {
        Map<String,Integer> counts = new TreeMap<>();
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            public MethodVisitor visitMethod(int a,String n,String d,String s,String[] e) {
                return new MethodVisitor(Opcodes.ASM9) {
                    public void visitMethodInsn(int op,String owner,String name,String desc,boolean itf) {
                        if (owner.equals(HOOKS)) counts.merge(name,1,Integer::sum);
                    }
                };
            }
        },0);
        return counts;
    }
    static void audit(String entry, byte[] bytes) {
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            public MethodVisitor visitMethod(int a,String n,String d,String s,String[] e) {
                return new MethodVisitor(Opcodes.ASM9) {
                    public void visitInsn(int op) {
                        if (op == Opcodes.RETURN && entry.endsWith("/IsoObject.class") && (n.equals("addToWorld") || n.equals("removeFromWorld")))
                            System.out.println(entry + " " + n + d + " RETURN");
                    }
                    public void visitFieldInsn(int op,String owner,String field,String desc) {
                        if (op == Opcodes.PUTFIELD && (field.equals("container") && desc.equals("Lzombie/inventory/ItemContainer;") || entry.endsWith("/Food.class") && field.equals("age") || entry.endsWith("/IsoGenerator.class") && field.equals("fuel")))
                            System.out.println(entry + " " + n + d + " -> " + owner + "." + field + desc);
                    }
                };
            }
        },0);
    }
    static void require(boolean b,String msg) { if (!b) throw new AssertionError(msg); }
}
