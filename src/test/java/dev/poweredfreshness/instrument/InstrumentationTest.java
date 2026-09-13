package dev.poweredfreshness.instrument;

import java.lang.instrument.*;
import java.security.ProtectionDomain;
import java.util.*;

/** Starts a JVM, not a game: loads actual game classes without running their static initializers. */
public final class InstrumentationTest {
    private static Instrumentation inst;
    public static void premain(String args,Instrumentation instrumentation) { inst=instrumentation; }
    public static void main(String[] args) throws Exception {
        GameTransformerTest.require(inst != null,"test agent missing");
        GameTransformerTest.require(inst.isRetransformClassesSupported(),"retransform unsupported");
        GameTransformer transformer = new GameTransformer(t -> { throw new AssertionError(t); });
        transformer.preflight(InstrumentationTest.class.getClassLoader());
        Map<String,byte[]> observed = new HashMap<>();
        inst.addTransformer(transformer,true);
        inst.addTransformer(new ClassFileTransformer() {
            public byte[] transform(ClassLoader l,String name,Class<?> c,ProtectionDomain d,byte[] bytes) {
                if (GameTransformer.TARGETS.contains(name)) observed.put(name,bytes.clone());
                return null;
            }
        },true);
        List<Class<?>> loaded = new ArrayList<>();
        for (String name : GameTransformer.TARGETS) {
            Class<?> target = Class.forName(name.replace('/','.'),false,InstrumentationTest.class.getClassLoader());
            // Reflective method resolution forces JVM verification while leaving <clinit> unexecuted.
            target.getDeclaredMethods();
            target.getDeclaredConstructors();
            loaded.add(target);
            assertTransformed(observed,name);
        }
        GameTransformerTest.require(transformer.failure()==null,"load transformation failed");
        System.out.println("Fresh-load verification PASS: " + loaded.size() + " actual game classes");
        observed.clear();
        inst.retransformClasses(loaded.toArray(Class<?>[]::new));
        for (String name : GameTransformer.TARGETS) assertTransformed(observed,name);
        GameTransformerTest.require(transformer.failure()==null,"retransformation failed");
        System.out.println("Retransformation verification PASS: " + loaded.size() + " actual game classes");
    }
    private static void assertTransformed(Map<String,byte[]> observed,String name) {
        GameTransformerTest.require(observed.containsKey(name),"JVM did not transform " + name);
        Map<String,Integer> calls=GameTransformerTest.hookCalls(observed.get(name));
        GameTransformerTest.require(calls.equals(GameTransformerTest.expectedCalls(name)),"JVM hook mismatch: " + name + " " + calls);
        System.out.println("verified " + name + " " + calls);
    }
}
