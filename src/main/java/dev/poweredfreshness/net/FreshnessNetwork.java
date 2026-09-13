package dev.poweredfreshness.net;

import dev.poweredfreshness.runtime.Hooks;
import se.krka.kahlua.vm.KahluaTable;
import zombie.Lua.LuaEventManager;
import zombie.Lua.LuaManager;
import zombie.characters.IsoPlayer;
import zombie.inventory.InventoryItem;
import zombie.inventory.ItemContainer;
import zombie.inventory.types.Food;
import zombie.inventory.types.InventoryContainer;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoObject;
import zombie.iso.IsoWorld;
import zombie.network.GameClient;
import zombie.network.GameServer;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

/** Age-only, server-authoritative protocol. Called exclusively on the game thread. */
public final class FreshnessNetwork {
    private static final String MODULE = "PoweredFreshness";
    private static final Protocol ORDER = new Protocol();
    private static final FairOutbox<Food> OUTBOX = new FairOutbox<>(4096);
    private static final Map<Integer, Pending> PENDING = new LinkedHashMap<>();
    private static final Map<IsoPlayer, Long> CLIENTS = new WeakHashMap<>();
    private static String epoch = UUID.randomUUID().toString();
    private static String nonce;
    private static long sequence;
    private static long nextTick;
    private static long nextHello;
    private static long nextRefresh;

    private FreshnessNetwork() { }

    public static void reset() {
        epoch = UUID.randomUUID().toString();
        nonce = UUID.randomUUID().toString();
        sequence = nextTick = nextHello = nextRefresh = 0;
        OUTBOX.clear(); PENDING.clear(); CLIENTS.clear(); ORDER.begin(nonce);
    }

    public static void broadcast(Food food) {
        if (!GameServer.server || food == null) return;
        OUTBOX.offer(food.getID(), food);
    }

    /** Bounded coalescing prevents an age update every frame from sending every frame. */
    public static void tick() {
        long now = System.currentTimeMillis();
        if (now < nextTick) return;
        nextTick = now + 1000;
        if (GameServer.server) {
            int budget = 128;
            Food food;
            while (budget-- > 0 && (food = OUTBOX.poll()) != null) {
                KahluaTable packet = snapshot(food);
                if (packet == null) continue;
                for (IsoPlayer player : new ArrayList<>(GameServer.getPlayers())) {
                    if (CLIENTS.containsKey(player) && visibleTo(player, food)) {
                        LuaManager.GlobalObject.sendServerCommand(player, MODULE, "age", packet);
                    }
                }
            }
        } else if (GameClient.client) {
            if (nonce == null) { nonce = UUID.randomUUID().toString(); ORDER.begin(nonce); }
            if (!ORDER.ready() && now >= nextHello && LuaManager.GlobalObject.getPlayer() != null) {
                nextHello = now + 3000;
                KahluaTable args = table(); put(args, "nonce", nonce);
                LuaManager.GlobalObject.sendClientCommand(MODULE, "hello", args);
            } else if (ORDER.ready() && now >= nextRefresh) {
                nextRefresh = now + 15000;
                LuaManager.GlobalObject.sendClientCommand(MODULE, "refresh", table());
            }
            Iterator<Pending> iterator = PENDING.values().iterator();
            while (iterator.hasNext()) {
                Pending pending = iterator.next();
                if (now >= pending.expires || apply(pending.data)) iterator.remove();
            }
        }
    }

    public static void onServerCommand(String command, KahluaTable args) {
        if (!GameClient.client || GameServer.server || args == null || !version(args)) return;
        if ("hello".equals(command)) {
            ORDER.handshake(text(args, "nonce"), text(args, "epoch"));
            return;
        }
        if (!"age".equals(command)) return;
        Snapshot data = parse(args);
        if (data == null || !ORDER.accept(data.epoch, data.id, data.sequence)) return;
        PENDING.remove(data.id);
        if (!apply(data)) {
            if (PENDING.size() >= 512) PENDING.remove(PENDING.keySet().iterator().next());
            PENDING.put(data.id, new Pending(data, System.currentTimeMillis() + 10000));
        }
    }

    public static void onClientCommand(IsoPlayer player, String command, KahluaTable args) {
        if (!GameServer.server || player == null || args == null || !version(args)) return;
        if (!"hello".equals(command) && !"refresh".equals(command)) return;
        long now = System.currentTimeMillis();
        Long previous = CLIENTS.get(player);
        if (previous != null && now - previous < 2000) return;
        if ("hello".equals(command)) {
            String value = text(args, "nonce");
            if (value == null || value.length() < 1 || value.length() > 64) return;
            CLIENTS.put(player, now);
            KahluaTable reply = table(); put(reply, "nonce", value); put(reply, "epoch", epoch);
            LuaManager.GlobalObject.sendServerCommand(player, MODULE, "hello", reply);
        } else {
            if (previous == null) return;
            CLIENTS.put(player, now);
        }
        // Never accept user-supplied coordinates: only this player's inventory and vicinity.
        int[] budget = {4096};
        enqueue(player.getInventory(), budget, 0);
        if (IsoWorld.instance == null || IsoWorld.instance.getCell() == null) return;
        int px = (int) Math.floor(player.getX()), py = (int) Math.floor(player.getY());
        int pz = (int) Math.floor(player.getZ());
        for (int z = pz - 1; z <= pz + 1; z++) {
            for (int x = px - 12; x <= px + 12 && budget[0] > 0; x++) {
                for (int y = py - 12; y <= py + 12 && budget[0] > 0; y++) {
                    IsoGridSquare square = IsoWorld.instance.getCell().getGridSquare(x, y, z);
                    if (square == null) continue;
                    for (int i = 0; i < square.getObjects().size(); i++) {
                        IsoObject object = square.getObjects().get(i);
                        for (int c = 0; c < object.getContainerCount(); c++) {
                            ItemContainer container = object.getContainerByIndex(c);
                            if (container != null && (container.isFridge() || container.isFreezer())) enqueue(container, budget, 0);
                        }
                    }
                }
            }
        }
    }

    private static void enqueue(ItemContainer container, int[] budget, int depth) {
        if (container == null || depth > 16 || budget[0] <= 0) return;
        for (InventoryItem item : container.getItems()) {
            if (--budget[0] < 0) return;
            if (item instanceof Food food) broadcast(food);
            else if (item instanceof InventoryContainer bag) enqueue(bag.getInventory(), budget, depth + 1);
        }
    }

    private static boolean visibleTo(IsoPlayer player, Food food) {
        ItemContainer root = food.getOutermostContainer();
        if (root == null) return false;
        IsoObject parent = root.getParent();
        if (parent instanceof IsoPlayer owner) return owner == player;
        IsoGridSquare square = parent == null ? null : parent.getSquare();
        if (square == null) return false;
        return Math.abs(player.getX() - square.getX()) <= 64
                && Math.abs(player.getY() - square.getY()) <= 64
                && Math.abs(player.getZ() - square.getZ()) <= 3;
    }

    private static KahluaTable snapshot(Food food) {
        if (!Protocol.age(food.getAge()) || !Protocol.age(food.getLastAged())) return null;
        ItemContainer root = food.getOutermostContainer();
        if (root == null || root.getParent() == null) return null;
        IsoObject object = root.getParent();
        KahluaTable args = table();
        put(args, "epoch", epoch); put(args, "seq", ++sequence);
        put(args, "id", food.getID()); put(args, "type", food.getFullType());
        put(args, "age", food.getAge()); put(args, "lastAged", food.getLastAged());
        if (object instanceof IsoPlayer owner) {
            put(args, "root", "player"); put(args, "owner", owner.getOnlineID());
        } else {
            IsoGridSquare square = object.getSquare();
            if (square == null || object.getObjectIndex() < 0) return null;
            put(args, "root", "world");
            put(args, "x", square.getX()); put(args, "y", square.getY()); put(args, "z", square.getZ());
            put(args, "object", object.getObjectIndex()); put(args, "container", object.getContainerIndex(root));
        }
        return args;
    }

    private static Snapshot parse(KahluaTable args) {
        if (!Protocol.integer(args.rawget("id"), Integer.MIN_VALUE, Integer.MAX_VALUE)
                || !Protocol.integer(args.rawget("seq"), 1, 9007199254740991L)
                || !Protocol.age(args.rawget("age")) || !Protocol.age(args.rawget("lastAged"))) return null;
        String type = text(args, "type"), root = text(args, "root"), session = text(args, "epoch");
        if (type == null || type.length() > 256 || type.isEmpty() || session == null || session.length() > 64) return null;
        int owner = 0, x = 0, y = 0, z = 0, object = 0, container = 0;
        if ("player".equals(root)) {
            if (!Protocol.integer(args.rawget("owner"), Short.MIN_VALUE, Short.MAX_VALUE)) return null;
            owner = number(args, "owner").intValue();
        } else if ("world".equals(root)) {
            if (!Protocol.integer(args.rawget("x"), -1000000, 1000000)
                    || !Protocol.integer(args.rawget("y"), -1000000, 1000000)
                    || !Protocol.integer(args.rawget("z"), -32, 255)
                    || !Protocol.integer(args.rawget("object"), 0, 65535)
                    || !Protocol.integer(args.rawget("container"), 0, 255)) return null;
            x = number(args, "x").intValue(); y = number(args, "y").intValue(); z = number(args, "z").intValue();
            object = number(args, "object").intValue(); container = number(args, "container").intValue();
        } else return null;
        return new Snapshot(session, number(args, "seq").longValue(), number(args, "id").intValue(), type,
                number(args, "age").floatValue(), number(args, "lastAged").floatValue(), root, owner, x, y, z, object, container);
    }

    private static boolean apply(Snapshot data) {
        Food food = resolve(data);
        if (food == null) return false;
        Hooks.applyAuthoritative(food, data.age, data.lastAged);
        LuaEventManager.triggerEvent("OnContainerUpdate", food);
        return true;
    }

    private static Food resolve(Snapshot data) {
        if ("player".equals(data.root)) {
            IsoPlayer owner = GameClient.IDToPlayerMap.get((short) data.owner);
            return owner == null ? null : find(owner.getInventory(), data, new int[]{4096}, 0);
        }
        if (IsoWorld.instance == null || IsoWorld.instance.getCell() == null) return null;
        IsoGridSquare square = IsoWorld.instance.getCell().getGridSquare(data.x, data.y, data.z);
        if (square == null) return null;
        if (data.object < square.getObjects().size()) {
            IsoObject object = square.getObjects().get(data.object);
            if (data.container < object.getContainerCount()) {
                Food food = find(object.getContainerByIndex(data.container), data, new int[]{4096}, 0);
                if (food != null) return food;
            }
        }
        // Object indices can move after construction/destruction. Search only the named square.
        int[] budget = {4096};
        for (int i = 0; i < square.getObjects().size() && budget[0] > 0; i++) {
            IsoObject object = square.getObjects().get(i);
            for (int c = 0; c < object.getContainerCount() && budget[0] > 0; c++) {
                Food food = find(object.getContainerByIndex(c), data, budget, 0);
                if (food != null) return food;
            }
        }
        return null;
    }

    private static Food find(ItemContainer container, Snapshot data, int[] budget, int depth) {
        if (container == null || depth > 16) return null;
        for (InventoryItem item : container.getItems()) {
            if (--budget[0] < 0) return null;
            if (item.getID() == data.id && item instanceof Food food && data.type.equals(item.getFullType())) return food;
            if (item instanceof InventoryContainer bag) {
                Food found = find(bag.getInventory(), data, budget, depth + 1);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static KahluaTable table() { KahluaTable args = LuaManager.platform.newTable(); put(args, "v", 1); return args; }
    private static void put(KahluaTable args, String key, Object value) { args.rawset(key, value instanceof Number n ? n.doubleValue() : value); }
    private static boolean version(KahluaTable args) { return Protocol.integer(args.rawget("v"), 1, 1); }
    private static String text(KahluaTable args, String key) { return args.rawget(key) instanceof String s ? s : null; }
    private static Number number(KahluaTable args, String key) { return (Number) args.rawget(key); }
    private record Pending(Snapshot data, long expires) { }
    private record Snapshot(String epoch, long sequence, int id, String type, float age, float lastAged,
                            String root, int owner, int x, int y, int z, int object, int container) { }
}
