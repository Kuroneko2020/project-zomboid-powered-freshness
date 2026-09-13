package dev.poweredfreshness.runtime;

import dev.poweredfreshness.core.AgeMath;
import dev.poweredfreshness.net.FreshnessNetwork;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.WeakHashMap;
import se.krka.kahlua.vm.KahluaTable;
import zombie.GameTime;
import zombie.SandboxOptions;
import zombie.Lua.LuaEventManager;
import zombie.Lua.LuaManager;
import zombie.inventory.InventoryItem;
import zombie.inventory.ItemContainer;
import zombie.inventory.types.Food;
import zombie.inventory.types.InventoryContainer;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoObject;
import zombie.iso.objects.IsoGenerator;
import zombie.network.GameClient;

/** All callbacks execute on the game's owning thread. Disabled callbacks preserve vanilla writes. */
public final class Hooks {
    private static final String JOURNAL = "PoweredFreshness.Pending.v1";
    private static final Field CONTAINER = field(InventoryItem.class, "container");
    private static final Set<IsoObject> WORLD = Collections.newSetFromMap(new IdentityHashMap<>());
    private static final Map<Food, Boolean> PENDING = new WeakHashMap<>();
    private static final Map<Food, Boolean> AUTHORITATIVE = new WeakHashMap<>();
    private static boolean enabled;
    private static boolean ready;
    private static boolean transferring;
    private static boolean settling;
    private static double lastTick = Double.NaN;
    private static int failures;
    private static long reverseUpdates;

    private Hooks() { }

    public static void enable() { enabled = true; }

    public static void disable(Throwable error) {
        enabled = false;
        System.err.println("[PoweredFreshness] DISABLED: " + error);
    }

    public static boolean isEnabled() { return enabled; }
    public static long reverseUpdates() { return reverseUpdates; }

    public static void start() {
        if (!enabled) return;
        ready = true;
        lastTick = Double.NaN;
        failures = 0;
        // Deserialization may have queued journals before OnGameStart. stop() clears the old world.
        AUTHORITATIVE.clear();
        PowerTracker.start(); FreshnessNetwork.reset();
        if (!GameClient.client) {
            for (IsoObject object : new ArrayList<>(WORLD)) PowerTracker.register(object);
        }
        System.out.println("[PoweredFreshness] Active; authority=" + !GameClient.client
                + "; rule=powered room-temperature reverse; version=0.1.0");
        tick();
    }

    public static void stop() {
        ready = false; WORLD.clear(); PENDING.clear(); AUTHORITATIVE.clear();
        PowerTracker.reset(); FreshnessNetwork.reset(); lastTick = Double.NaN;
    }

    public static void tick() {
        if (!enabled || !ready) return;
        try {
            if (!GameClient.client) {
                double now = now();
                if (!Double.isFinite(lastTick) || now < lastTick || now - lastTick >= 1.0 / 60.0) {
                    lastTick = now;
                    settleAll();
                }
            }
            FreshnessNetwork.tick();
        } catch (RuntimeException error) { report(error); }
    }

    public static void save() {
        if (!enabled || !ready || GameClient.client) return;
        settleAll(); PowerTracker.save();
    }

    private static void settleAll() {
        if (settling) return;
        settling = true;
        try {
            PowerTracker.tickSources();
            for (Food food : new ArrayList<>(PENDING.keySet())) flushJournal(food);
            for (ItemContainer container : PowerTracker.containers()) {
                visitContainer(container, Hooks::updateFood, Collections.newSetFromMap(new IdentityHashMap<>()), 0);
            }
        } finally { settling = false; }
    }

    /** Replaces exactly the vanilla age PUTFIELD, before vanilla lastAged and UI notifications. */
    public static void writeAge(Food food, float vanillaAge) {
        if (!enabled || !ready) { food.setAge(vanillaAge); return; }
        if (GameClient.client) {
            if (!AUTHORITATIVE.containsKey(food) || !cold(food.getOutermostContainer())) food.setAge(vanillaAge);
            return;
        }
        try {
            double oldAge = food.getAge(), from = food.getLastAged(), to = now();
            if (!valid(oldAge) || !valid(vanillaAge) || !valid(from) || !(to > from)) {
                food.setAge(vanillaAge); return;
            }
            ItemContainer outer = food.getOutermostContainer();
            AgeJournal journal = loadJournal(food);
            String key = cold(outer) ? PowerTracker.key(outer) : "";
            if (key == null) key = "";
            if (key.isEmpty() && journal == null) { food.setAge(vanillaAge); return; }
            double reverse = AgeMath.rotRate(SandboxOptions.instance.foodRotSpeed.getValue()) / 24.0;
            // Native catch-up can average a refrigerated segment together with a warm segment.
            // Applying that average to only the unpowered segment would refrigerate it twice.
            double ordinary = cold(outer) ? (food.isFrozen() ? 0 : reverse)
                    : Math.max(0, (vanillaAge - oldAge) / (to - from));
            if (journal == null && !PowerTracker.isAccounting()) {
                PowerTracker.WindowResult result = PowerTracker.windows(key, from, to);
                if (result.resolved()) {
                    if (result.windows().isEmpty()) { food.setAge(vanillaAge); return; }
                    float age = (float) AgeMath.integrate(oldAge, from, to, ordinary, reverse, result.windows());
                    food.setAge(age);
                    if (age < oldAge) reverseUpdates++;
                    FreshnessNetwork.broadcast(food);
                    return;
                }
            }
            if (journal == null) journal = new AgeJournal(oldAge);
            journal.append(new AgeJournal.Segment(from, to, ordinary, reverse, key));
            OptionalDouble result = resolve(journal);
            if (result.isPresent()) {
                food.setAge((float) result.getAsDouble());
                clearJournal(food);
            } else {
                // The native generator must finish its own fuel/condition catch-up first.
                food.setAge((float) journal.baseAge());
                storeJournal(food, journal);
                PENDING.put(food, Boolean.TRUE);
            }
            FreshnessNetwork.broadcast(food);
        } catch (RuntimeException error) {
            food.setAge(vanillaAge);
            report(error);
        }
    }

    private static OptionalDouble resolve(AgeJournal journal) {
        if (PowerTracker.isAccounting()) return OptionalDouble.empty();
        return journal.resolve((key, from, to) -> {
            PowerTracker.WindowResult result = PowerTracker.windows(key, from, to);
            return new AgeJournal.Resolution(result.resolved(), result.windows());
        });
    }

    private static void flushJournal(Food food) {
        if (food == null) return;
        AgeJournal journal = loadJournal(food);
        if (journal == null) { PENDING.remove(food); return; }
        OptionalDouble result = resolve(journal);
        if (result.isPresent()) {
            food.setAge((float) result.getAsDouble());
            clearJournal(food);
            FreshnessNetwork.broadcast(food);
            LuaEventManager.triggerEvent("OnContainerUpdate", food);
        }
    }

    /** Replaces writes to InventoryItem.container, including Java paths bypassing its setter. */
    public static void assignContainer(InventoryItem item, ItemContainer target) {
        if (item == null) throw new NullPointerException("item");
        boolean boundary = enabled && ready && !GameClient.client && !transferring && item.getContainer() != target;
        if (boundary) {
            transferring = true;
            try { visitItem(item, Hooks::updateFood, Collections.newSetFromMap(new IdentityHashMap<>()), 0); }
            catch (RuntimeException error) { report(error); }
            finally { transferring = false; }
        }
        finishAssignment(item, target, boundary);
    }

    /** ItemContainer.load restores ownership; it is not a gameplay transfer from the floor. */
    public static void restoreContainer(InventoryItem item, ItemContainer target) {
        finishAssignment(item, target, false);
    }

    private static void finishAssignment(InventoryItem item, ItemContainer target, boolean broadcast) {
        if (item == null) throw new NullPointerException("item");
        try { CONTAINER.set(item, target); }
        catch (IllegalAccessException error) { throw new IllegalStateException("Cannot preserve container assignment", error); }
        if (enabled && !GameClient.client) {
            visitItem(item, food -> {
                if (food.getModData().rawget(JOURNAL) != null) PENDING.put(food, Boolean.TRUE);
                if (broadcast) FreshnessNetwork.broadcast(food);
            }, Collections.newSetFromMap(new IdentityHashMap<>()), 0);
        }
    }

    private static void updateFood(Food food) {
        if (food == null || food.getOffAgeMax() >= 1_000_000_000) return;
        flushJournal(food);
        food.updateAge(false);
        FreshnessNetwork.broadcast(food);
    }

    /** Protect a recoverable object while its powered-age interval is being settled. */
    public static boolean protectRotting(Food food) {
        if (!enabled || !ready || GameClient.client) return false;
        ItemContainer container = food.getOutermostContainer();
        if (food.getModData().rawget(JOURNAL) != null) {
            PENDING.put(food, Boolean.TRUE);
            flushJournal(food);
            if (food.getModData().rawget(JOURNAL) != null) return true;
        }
        if (!cold(container) || !container.isPowered()) return false;
        updateFood(food);
        return true;
    }

    public static void worldAdded(IsoObject object) {
        if (!enabled || object == null || GameClient.client) return;
        if (!(object instanceof IsoGenerator) && !hasColdContainer(object)) return;
        boolean newlyLoaded = WORLD.add(object);
        if (ready) {
            PowerTracker.register(object);
            // Anchor newly discovered food immediately; pre-Mod time must remain vanilla.
            if (newlyLoaded) for (int i = 0; i < object.getContainerCount(); i++) {
                ItemContainer container = object.getContainerByIndex(i);
                if (cold(container)) visitContainer(container, Hooks::updateFood,
                        Collections.newSetFromMap(new IdentityHashMap<>()), 0);
            }
        }
    }

    public static void worldRemoving(IsoObject object) {
        if (!enabled || object == null || GameClient.client) return;
        if (ready && WORLD.contains(object)) {
            for (int i = 0; i < object.getContainerCount(); i++) {
                visitContainer(object.getContainerByIndex(i), Hooks::updateFood,
                        Collections.newSetFromMap(new IdentityHashMap<>()), 0);
            }
            PowerTracker.remove(object);
        }
        WORLD.remove(object);
    }

    /** The engine emits this event for physical removal, but not for chunk unloading. */
    public static void worldPermanentlyRemoving(IsoObject object) {
        if (enabled && ready && !GameClient.client) PowerTracker.permanentRemove(object);
    }

    public static void squareLoaded(IsoGridSquare square) {
        if (!enabled || square == null) return;
        for (int i = 0; i < square.getObjects().size(); i++) worldAdded(square.getObjects().get(i));
    }

    public static void generatorBefore(IsoGenerator generator) {
        if (enabled && ready && !GameClient.client) PowerTracker.beforeGenerator(generator);
    }
    public static void generatorAfter(IsoGenerator generator) {
        if (enabled && ready && !GameClient.client) PowerTracker.afterGenerator(generator);
    }
    public static void generatorAccounting(IsoGenerator generator, int index, int pending, int loss, float used) {
        if (enabled && ready && !GameClient.client) PowerTracker.accounting(generator, index, pending, loss, used);
    }
    public static void generatorSwitch(IsoGenerator generator, boolean active) {
        if (enabled && ready && !GameClient.client) PowerTracker.switchActive(generator, active);
    }
    public static void generatorConnected(IsoGenerator generator, boolean connected) {
        if (enabled && ready && !GameClient.client) PowerTracker.connect(generator, connected);
    }

    public static void applyAuthoritative(Food food, float age, float lastAged) {
        if (food == null || !valid(age) || !valid(lastAged)) return;
        food.setAge(age); food.setLastAged(lastAged);
        if (cold(food.getOutermostContainer())) AUTHORITATIVE.put(food, Boolean.TRUE);
        else AUTHORITATIVE.remove(food);
    }

    private static AgeJournal loadJournal(Food food) {
        Object raw = food.getModData().rawget(JOURNAL);
        if (raw == null) return null;
        if (!(raw instanceof KahluaTable data)) throw new IllegalArgumentException("Invalid saved age journal table");
        Object value = data.rawget("base");
        if (!(value instanceof Number base) || !valid(base.doubleValue())) throw new IllegalArgumentException("Invalid saved age journal base");
        Object entries = data.rawget("segments");
        if (!(entries instanceof KahluaTable segments)) throw new IllegalArgumentException("Invalid saved age journal segments");
        AgeJournal journal = new AgeJournal(base.doubleValue());
        for (int i = 1; i <= segments.len(); i++) {
            Object entry = segments.rawget((double) i);
            if (!(entry instanceof KahluaTable row)) throw new IllegalArgumentException("Invalid saved age journal");
            journal.append(new AgeJournal.Segment(number(row, "from"), number(row, "to"),
                    number(row, "ordinary"), number(row, "reverse"), String.valueOf(row.rawget("key"))));
        }
        return journal;
    }

    private static void storeJournal(Food food, AgeJournal journal) {
        KahluaTable data = LuaManager.platform.newTable(), rows = LuaManager.platform.newTable();
        data.rawset("base", journal.baseAge()); data.rawset("segments", rows);
        int i = 0;
        for (AgeJournal.Segment segment : journal.segments()) {
            KahluaTable row = LuaManager.platform.newTable();
            row.rawset("from", segment.from()); row.rawset("to", segment.to());
            row.rawset("ordinary", segment.ordinaryRatePerHour()); row.rawset("reverse", segment.reverseRatePerHour());
            row.rawset("key", segment.fridgeKey()); rows.rawset((double) ++i, row);
        }
        food.getModData().rawset(JOURNAL, data);
    }

    private static void clearJournal(Food food) { food.getModData().rawset(JOURNAL, null); PENDING.remove(food); }
    private static double number(KahluaTable row, String key) {
        Object value = row.rawget(key);
        if (!(value instanceof Number number) || !Double.isFinite(number.doubleValue())) throw new IllegalArgumentException("Invalid journal " + key);
        return number.doubleValue();
    }
    private static boolean valid(double value) { return Double.isFinite(value) && value >= 0 && value <= Float.MAX_VALUE; }
    private static double now() { return (double) (float) GameTime.getInstance().getWorldAgeHours(); }
    private static boolean cold(ItemContainer container) { return container != null && (container.isFridge() || container.isFreezer()); }
    private static boolean hasColdContainer(IsoObject object) {
        for (int i = 0; i < object.getContainerCount(); i++) if (cold(object.getContainerByIndex(i))) return true;
        return false;
    }
    private static Field field(Class<?> type, String name) {
        try { Field value = type.getDeclaredField(name); value.setAccessible(true); return value; }
        catch (ReflectiveOperationException error) { throw new ExceptionInInitializerError(error); }
    }
    private static void report(RuntimeException error) {
        failures++;
        if (failures <= 5) { System.err.println("[PoweredFreshness] Runtime error: " + error); error.printStackTrace(); }
        if (failures >= 10) disable(error);
    }
    private static void visitItem(InventoryItem item, java.util.function.Consumer<Food> action,
                                  Set<InventoryItem> seen, int depth) {
        if (item == null || depth > 32 || !seen.add(item)) return;
        if (item instanceof Food food) action.accept(food);
        if (item instanceof InventoryContainer bag) visitContainer(bag.getInventory(), action, seen, depth + 1);
    }
    private static void visitContainer(ItemContainer container, java.util.function.Consumer<Food> action,
                                       Set<InventoryItem> seen, int depth) {
        if (container == null || depth > 32) return;
        for (InventoryItem item : new ArrayList<>(container.getItems())) visitItem(item, action, seen, depth);
    }
}
