package dev.poweredfreshness;

import dev.poweredfreshness.instrument.GameTransformer;
import dev.poweredfreshness.runtime.Hooks;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.util.ArrayList;
import zombie.core.Core;

/** ZombieBuddy entry point; no game file is replaced. */
public final class Main {
    private static boolean installed;
    private Main() { }

    public static synchronized void main(String[] args) {
        if (installed) return;
        try {
            String version = Core.getInstance().getVersionNumber();
            Field buildField = Core.class.getDeclaredField("buildVersion"); buildField.setAccessible(true);
            int build = buildField.getInt(null);
            if (!"42.20".equals(version) || build != 4) {
                throw new IllegalStateException("Expected game 42.20.4, found " + version + "." + build);
            }
            ClassLoader loader = Main.class.getClassLoader();
            Class<?> zbLoader = Class.forName("me.zed_0xff.zombie_buddy.Loader", false, loader);
            Field field = zbLoader.getDeclaredField("g_instrumentation"); field.setAccessible(true);
            Object value = field.get(null);
            if (!(value instanceof Instrumentation instrumentation) || !instrumentation.isRetransformClassesSupported()) {
                throw new IllegalStateException("ZombieBuddy instrumentation is unavailable on this client/server");
            }
            GameTransformer transformer = new GameTransformer(Hooks::disable);
            transformer.preflight(loader);
            ArrayList<Class<?>> targets = new ArrayList<>();
            for (String target : GameTransformer.TARGETS) {
                Class<?> type = Class.forName(target.replace('/', '.'), false, loader);
                if (!instrumentation.isModifiableClass(type)) throw new IllegalStateException("Unmodifiable game class " + target);
                targets.add(type);
            }
            instrumentation.addTransformer(transformer, true);
            instrumentation.retransformClasses(targets.toArray(Class<?>[]::new));
            if (transformer.failure() != null) throw new IllegalStateException("Game patch validation failed", transformer.failure());
            installed = true;
            Hooks.enable();
            System.out.println("[PoweredFreshness] 0.1.0 installed: checked " + targets.size() + " game classes");
        } catch (Throwable error) {
            Hooks.disable(error);
            error.printStackTrace();
        }
    }
}
