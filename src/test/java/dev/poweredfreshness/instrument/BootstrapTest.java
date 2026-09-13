package dev.poweredfreshness.instrument;

import dev.poweredfreshness.Main;
import dev.poweredfreshness.PoweredFreshness;
import dev.poweredfreshness.runtime.Hooks;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import me.zed_0xff.zombie_buddy.Exposer;
import se.krka.kahlua.converter.KahluaConverterManager;
import se.krka.kahlua.j2se.KahluaTableImpl;
import zombie.Lua.LuaManager;
import zombie.core.Core;

/** Isolated JVM bootstrap only; does not invoke Loader.loadMods or change approvals/policy. */
public final class BootstrapTest {
    public static void main(String[] args) throws Exception {
        Field testField = InstrumentationTest.class.getDeclaredField("inst");
        testField.setAccessible(true);
        Instrumentation instrumentation = (Instrumentation) testField.get(null);
        if (instrumentation == null) throw new AssertionError("Run with the existing test-agent.jar");
        String version = Core.getInstance().getVersionNumber();
        Field buildField = Core.class.getDeclaredField("buildVersion");
        buildField.setAccessible(true);
        int build = buildField.getInt(null);
        if (!"42.20".equals(version) || build != 4) throw new AssertionError("Actual Core version: " + version + " build " + build);
        System.out.println("Actual Core version PASS: " + version + " build " + build);

        Class<?> loader = Class.forName("me.zed_0xff.zombie_buddy.Loader");
        Field field = loader.getDeclaredField("g_instrumentation");
        field.setAccessible(true);
        Object previous = field.get(null);
        field.set(null, instrumentation);
        try {
            // Use the framework's actual optional-Main invocation without calling loadJar/loadMods.
            Class<?> phaseClass = Class.forName("me.zed_0xff.zombie_buddy.Loader$Phase");
            Object phase = null;
            for (Object value : phaseClass.getEnumConstants()) {
                if ("MAIN".equals(value.toString())) phase = value;
            }
            if (phase == null) throw new AssertionError("ZombieBuddy MAIN phase missing");
            Method invokeMain = loader.getDeclaredMethod("try_call_main", Class.class, phaseClass);
            invokeMain.setAccessible(true);
            invokeMain.invoke(null, Main.class, phase);
            if (!Hooks.isEnabled()) throw new AssertionError("Real Main did not enable checked hooks");
            System.out.println("Real Loader.try_call_main -> Main -> Hooks enabled PASS");

            LuaManager.env = new KahluaTableImpl(new java.util.HashMap<>());
            LuaManager.exposer = new LuaManager.Exposer(new KahluaConverterManager(), LuaManager.platform, LuaManager.env);
            Exposer.exposeAnnotatedClasses("dev.poweredfreshness");
            if (!Exposer.isClassExposed(PoweredFreshness.class)) throw new AssertionError("Lua annotation was not discovered");
            Exposer.LuaClass annotation = PoweredFreshness.class.getAnnotation(Exposer.LuaClass.class);
            if (annotation == null) throw new AssertionError("Missing Lua annotation");
            if (!(LuaManager.env.rawget("PoweredFreshness") instanceof se.krka.kahlua.vm.KahluaTable)) {
                throw new AssertionError("Real Lua environment is missing PoweredFreshness (same-name alias deletes the table in ZB 2.3.2)");
            }
            System.out.println("Real Exposer discovery and Lua global table PASS: PoweredFreshness");
            Main.main(new String[0]);
            if (!Hooks.isEnabled()) throw new AssertionError("Repeated Main changed enabled state");
            System.out.println("BootstrapTest PASS: isolated JVM only; no game/server/approval loading");
        } finally {
            field.set(null, previous);
        }
    }
}
