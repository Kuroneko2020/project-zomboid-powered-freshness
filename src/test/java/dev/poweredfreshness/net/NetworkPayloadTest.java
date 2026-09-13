package dev.poweredfreshness.net;

import java.lang.reflect.Method;
import java.util.HashMap;
import se.krka.kahlua.j2se.KahluaTableImpl;
import se.krka.kahlua.vm.KahluaTable;

/** Exercises actual Kahlua payloads without opening a world or networking session. */
public final class NetworkPayloadTest {
    public static void main(String[] args) throws Exception {
        Method parse = FreshnessNetwork.class.getDeclaredMethod("parse", KahluaTable.class);
        parse.setAccessible(true);
        KahluaTable packet = valid();
        require(parse.invoke(null, packet) != null, "valid signed-ID world snapshot");
        for (Object value : new Object[]{Double.NaN, Double.POSITIVE_INFINITY, -1d, "2", true}) {
            packet = valid(); packet.rawset("age", value);
            require(parse.invoke(null, packet) == null, "invalid age " + value);
        }
        packet = valid(); packet.rawset("object", -1d);
        require(parse.invoke(null, packet) == null, "negative object index");
        packet = valid(); packet.rawset("id", 2147483648d);
        require(parse.invoke(null, packet) == null, "item ID overflow");
        packet = valid(); packet.rawset("seq", 9007199254740992d);
        require(parse.invoke(null, packet) == null, "sequence beyond double precision");
        packet = valid(); packet.rawset("root", "player"); packet.rawset("owner", -32768d);
        require(parse.invoke(null, packet) != null, "signed online ID");
        packet.rawset("owner", 32768d);
        require(parse.invoke(null, packet) == null, "online ID overflow");
        packet = valid(); packet.rawset("root", "arbitrary");
        require(parse.invoke(null, packet) == null, "unknown location type");
        System.out.println("NetworkPayloadTest: PASS (real Kahlua tables; no world/network startup)");
    }

    private static KahluaTable valid() {
        KahluaTable table = new KahluaTableImpl(new HashMap<>());
        table.rawset("epoch", "test"); table.rawset("seq", 1d); table.rawset("id", -2147483648d);
        table.rawset("type", "Base.Apple"); table.rawset("age", 0d); table.rawset("lastAged", 24d);
        table.rawset("root", "world"); table.rawset("x", 100d); table.rawset("y", 100d);
        table.rawset("z", 0d); table.rawset("object", 0d); table.rawset("container", 1d);
        return table;
    }

    private static void require(boolean value, String name) {
        if (!value) throw new AssertionError(name);
    }
}
