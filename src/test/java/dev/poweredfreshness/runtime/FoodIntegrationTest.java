package dev.poweredfreshness.runtime;

import dev.poweredfreshness.instrument.GameTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import net.bytebuddy.jar.asm.*;
import zombie.GameTime;
import zombie.SandboxOptions;
import zombie.Lua.LuaManager;
import zombie.inventory.ItemContainer;
import zombie.inventory.types.Food;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoObject;
import zombie.iso.IsoWorld;
import zombie.iso.areas.IsoRoom;
import zombie.network.GameClient;
import zombie.network.GameServer;

/** Isolated fixture test: calls real Food.updateAge, never loads a world/save. */
public final class FoodIntegrationTest {
    public static void premain(String args,Instrumentation inst) {
        inst.addTransformer(new ClassFileTransformer() {
            public byte[] transform(ClassLoader l,String name,Class<?> c,ProtectionDomain p,byte[] bytes) {
                if (!"zombie/Lua/LuaManager".equals(name)) return null;
                ClassReader reader=new ClassReader(bytes); ClassWriter writer=new ClassWriter(reader,0);
                reader.accept(new ClassVisitor(Opcodes.ASM9,writer) {
                    public MethodVisitor visitMethod(int a,String n,String d,String s,String[] e) {
                        MethodVisitor mv=super.visitMethod(a,n,d,s,e);
                        if(n.equals("RunLua")&&d.equals("(Ljava/lang/String;)Ljava/lang/Object;")) {
                            mv.visitCode();mv.visitInsn(Opcodes.ACONST_NULL);mv.visitInsn(Opcodes.ARETURN);mv.visitMaxs(1,1);mv.visitEnd();return null;
                        }
                        return mv;
                    }
                },0);return writer.toByteArray();
            }
        });
        if (!"vanilla".equals(args)) {
            inst.addTransformer(new GameTransformer(),true);
            try {
                for(Class<?> loaded:inst.getAllLoadedClasses()) if(GameTransformer.TARGETS.contains(loaded.getName().replace('.','/'))) inst.retransformClasses(loaded);
            } catch(Exception e) {throw new IllegalStateException(e);}
        }
    }
    public static void main(String[] args) throws Exception {
        zombie.core.random.RandStandard.INSTANCE.init();
        zombie.core.random.RandLua.INSTANCE.init();
        GameTime.setInstance(new GameTime());
        for (int option=1;option<=5;option++) {
            Food normal=fixture("fridge",3,false,true);
            SandboxOptions.instance.foodRotSpeed.setValue(option);
            at(24);normal.updateAge(false);
            double factor=new double[]{1.7,1.4,1,.7,.4}[option-1];
            near(3-factor,normal.getAge(),"fridge rate option "+option);
            near(24,normal.getLastAged(),"vanilla lastAged retained");
            Food frozen=fixture("freezer",3,true,true);
            SandboxOptions.instance.foodRotSpeed.setValue(option);
            at(24);frozen.updateAge(false);
            near(3-factor,frozen.getAge(),"frozen reverse rate option "+option);
            check(frozen.isFrozen(),"vanilla freezing maintained");
        }
        Food zero=fixture("fridge",.1f,false,true);
        at(24);zero.updateAge(false);near(0,zero.getAge(),"age floor");
        Food off=fixture("fridge",2,false,false);
        at(24);off.updateAge(false);near(3,off.getAge(),"power-off ordinary aging");
        Food moving=fixture("fridge",2,false,true);
        ItemContainer fridge=moving.getContainer();
        at(12);moving.setContainer(null);
        near(1.5,moving.getAge(),"move out settles powered interval");
        at(24);moving.updateAge(false);near(2,moving.getAge(),"outside resumes ordinary aging");
        at(36);moving.setContainer(fridge);near(2.5,moving.getAge(),"move in settles ordinary interval");
        at(48);moving.updateAge(false);near(2,moving.getAge(),"new powered interval starts at transfer");
        Food client=fixture("fridge",2,false,true);
        GameClient.client=true;
        Hooks.applyAuthoritative(client,1.5f,0);
        at(24);client.updateAge(false);near(1.5,client.getAge(),"client does not integrate confirmed age twice");
        GameClient.client=false;
        Food server=fixture("fridge",2,false,true);
        GameServer.server=true;GameClient.client=false;
        try {
            at(24);server.updateAge(false);
            near(1,server.getAge(),"server authority reverses age");
            Field outbox=dev.poweredfreshness.net.FreshnessNetwork.class.getDeclaredField("OUTBOX");
            outbox.setAccessible(true);
            Object queue=outbox.get(null);
            var poll=queue.getClass().getDeclaredMethod("poll");poll.setAccessible(true);
            Object queued=poll.invoke(queue);
            check(queued==server,"server update queues exact food for network broadcast");
        } finally {GameServer.server=false;GameClient.client=false;}
        Food stages=fixture("fridge",11,false,true);
        check(stages.isRotten(),"initially rotten");
        at(48);stages.updateAge(false);
        near(9,stages.getAge(),"rotten to stale age");
        check(!stages.isRotten()&&!stages.isFresh(),"stale threshold");
        at(168);stages.updateAge(false);
        near(4,stages.getAge(),"stale to fresh age");
        check(stages.isFresh(),"fresh threshold");
        Food crossing=fixture("fridge",2,false,true);
        SandboxOptions.instance.elecShutModifier.setValue(1);
        at(48);crossing.updateAge(false);
        near(2,crossing.getAge(),"one day powered then one day off in a single native catch-up");
        Food discovered=fixture("fridge",2,false,true);
        ItemContainer discoveredFridge=discovered.getContainer();
        IsoObject discoveredObject=discoveredFridge.getParent();
        Hooks.stop();zombie.world.moddata.GlobalModData.instance.reset();
        discovered.setContainer(null);
        at(48);Hooks.enable();Hooks.start();
        Hooks.restoreContainer(discovered,discoveredFridge);
        near(2,discovered.getAge(),"restore preserves saved age before world discovery");
        near(0,discovered.getLastAged(),"restore preserves saved timestamp");
        Hooks.worldAdded(discoveredObject);
        near(2.4,discovered.getAge(),"first discovery settles pre-mod history using vanilla refrigeration");
        near(48,discovered.getLastAged(),"first discovery anchors current game time");
        at(72);discovered.updateAge(false);
        near(1.4,discovered.getAge(),"only post-discovery powered time reverses");
        Hooks.stop();
        System.out.println("FoodIntegrationTest PASS: real updateAge, 5 rates x fridge/freezer, floor, unpowered, transfers, client/server authority, stale/fresh thresholds, mixed outage, save restoration and first discovery");
    }
    private static Food fixture(String type,float age,boolean frozen,boolean powered) throws Exception {
        GameClient.client=false;GameServer.server=false;
        Hooks.stop();zombie.world.moddata.GlobalModData.instance.reset();at(0);
        SandboxOptions.instance.foodRotSpeed.setValue(3);
        SandboxOptions.instance.elecShutModifier.setValue(powered?1000:-1);
        SandboxOptions.instance.timeSinceApo.setValue(1);
        IsoGridSquare square=new IsoGridSquare(null,null,100,100,0);
        square.room=allocate(IsoRoom.class);
        square.room.roomDef="kitchen";
        square.room.def=new zombie.iso.RoomDef(0,"kitchen");
        square.roomId=0;
        IsoObject object=new IsoObject();object.setSquare(square);square.getObjects().add(object);
        ItemContainer fridge=new ItemContainer(type,square,object);object.setContainer(fridge);
        IsoWorld.instance.setHydroPowerOn(powered);
        check(fridge.isPowered()==powered,"fixture power state");
        Food food=allocate(Food.class);
        food.setAge(age);food.setLastAged(0); food.setOffAge(5);food.setOffAgeMax(10);
        food.setCanBeFrozen(true);food.setFreezingTime(frozen?100:0);food.setFrozen(frozen);
        food.setContainer(fridge);fridge.getItems().add(food);
        Hooks.enable();Hooks.worldAdded(object);Hooks.start();
        return food;
    }
    static void at(double hours) {GameTime.getInstance().setNightsSurvived((int)(hours/24));GameTime.getInstance().timeOfDay=(float)(7+hours%24);GameTime.getInstance().serverTimeOfDay=(float)(7+hours%24);}
    static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    static void near(double expected,double actual,String message){if(Math.abs(expected-actual)>1e-5)throw new AssertionError(message+": expected="+expected+" actual="+actual);}
    static <T> T allocate(Class<T> type) throws Exception {
        Class<?> unsafeType=Class.forName("sun.misc.Unsafe");
        Field singleton=unsafeType.getDeclaredField("theUnsafe"); singleton.setAccessible(true);
        return type.cast(unsafeType.getMethod("allocateInstance",Class.class).invoke(singleton.get(null),type));
    }
}
