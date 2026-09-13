package dev.poweredfreshness.runtime;

import dev.poweredfreshness.core.TimeWindow;
import se.krka.kahlua.vm.KahluaTable;
import se.krka.kahlua.vm.KahluaTableIterator;
import zombie.GameTime;
import zombie.Lua.LuaManager;
import zombie.SandboxOptions;
import zombie.characters.IsoGameCharacter;
import zombie.inventory.ItemContainer;
import zombie.iso.IsoChunk;
import zombie.iso.IsoDirections;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoObject;
import zombie.iso.objects.IsoGenerator;
import zombie.world.moddata.GlobalModData;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Main-thread power accounting; only authority instances should call this class. */
public final class PowerTracker {
    private static final String GLOBAL = "PoweredFreshness.Power.v1";
    private static final String FRIDGE = "PoweredFreshness.Fridge.v1";
    private static final String GENERATOR_ID = "PoweredFreshness.GeneratorId";
    private static final Field LAST_HOUR = field(IsoGenerator.class, "lastHour", int.class);
    private static final Field CHUNK_SOURCES = field(IsoChunk.class, "generatorsTouchingThisChunk", ArrayList.class);
    private static final Map<String, Source> sources = new HashMap<>();
    private static final Map<IsoGenerator, Source> loaded = new IdentityHashMap<>();
    private static final Map<IsoGenerator, Frame> accounting = new IdentityHashMap<>();
    private static final Map<ItemContainer, Fridge> fridges = new IdentityHashMap<>();
    private static final Map<String, KahluaTable> fridgeHistory = new HashMap<>();
    private static KahluaTable database;
    private static KahluaTable sourceTables;
    private static KahluaTable fridgeTables;
    private static double birth;

    public record WindowResult(boolean resolved, List<TimeWindow> windows) {
        public WindowResult { windows = List.copyOf(windows); }
    }

    private PowerTracker() { }

    public static void start() {
        if (database != null) return;
        database = GlobalModData.instance.getOrCreate(GLOBAL);
        birth = number(database, "birth", now());
        database.rawset("birth", birth);
        sourceTables = table(database, "sources");
        fridgeTables = table(database, "fridges");
        KahluaTableIterator fridgeIterator = fridgeTables.iterator();
        while (fridgeIterator.advance()) {
            if (fridgeIterator.getKey() instanceof String key && fridgeIterator.getValue() instanceof KahluaTable saved) {
                fridgeHistory.put(key, saved);
            }
        }
        KahluaTableIterator iterator = sourceTables.iterator();
        while (iterator.advance()) {
            if (iterator.getKey() instanceof String id && iterator.getValue() instanceof KahluaTable saved) {
                Source source = new Source(id, integer(saved, "x"), integer(saved, "y"), integer(saved, "z"), readLedger(saved));
                if (Boolean.TRUE.equals(saved.rawget("permanentlyRemoved"))) source.removePermanently(source.ledger.cursor());
                sources.put(id, source);
            }
        }
    }

    /** Clears process references only. GlobalModData is owned and saved by the game. */
    public static void reset() {
        sources.clear(); loaded.clear(); accounting.clear(); fridges.clear(); fridgeHistory.clear();
        database = sourceTables = fridgeTables = null;
    }

    public static void register(IsoObject object) {
        if (object == null || object.getSquare() == null) return;
        start();
        if (object instanceof IsoGenerator generator) registerSource(generator);
        for (int i = 0; i < object.getContainerCount(); i++) {
            ItemContainer container = object.getContainerByIndex(i);
            if (container == null || (!container.isFridge() && !container.isFreezer()) || fridges.containsKey(container)) continue;
            KahluaTable identity = table(object.getModData(), FRIDGE);
            Object uuid = identity.rawget("id");
            if (!(uuid instanceof String) || ((String) uuid).isBlank()) {
                uuid = UUID.randomUUID().toString(); identity.rawset("id", uuid);
            }
            String position = position(object.getSquare());
            String key = uuid + "@" + position + "#" + i;
            KahluaTable saved = table(fridgeTables, key);
            if (saved.rawget("birth") == null) {
                saved.rawset("position", position);
                saved.rawset("birth", now());
            }
            fridgeHistory.put(key, saved);
            // Retain a local pointer for save portability and human inspection.
            identity.rawset("container" + i, key);
            Fridge fridge = new Fridge(key, object, saved, number(saved, "birth", now()));
            fridges.put(container, fridge);
            observe(container, fridge);
        }
    }

    private static Source registerSource(IsoGenerator generator) {
        Source previous = loaded.get(generator);
        if (previous != null) return previous;
        IsoGridSquare square = generator.getSquare();
        if (square == null) throw new IllegalStateException("generator has no square");
        Object identity = generator.getModData().rawget(GENERATOR_ID);
        if (!(identity instanceof String) || ((String) identity).isBlank()) {
            identity = UUID.randomUUID().toString();
            generator.getModData().rawset(GENERATOR_ID, identity);
        }
        String id = identity + "@" + position(square);
        Source source = sources.get(id);
        if (source == null) {
            // A source discovered later may retain a native, still-unsettled interval.
            double start = Math.max(birth, Math.min(now(), Math.max(0, lastHour(generator))));
            source = new Source(id, square.getX(), square.getY(), square.getZ(), new PowerLedger(start, generator.isActivated()));
            sources.put(id, source);
        }
        loaded.put(generator, source);
        persist(source);
        return source;
    }

    /** Called before physical deletion, never for ordinary chunk unloading. */
    public static void permanentRemove(IsoObject object) {
        if (!(object instanceof IsoGenerator generator) || generator.getSquare() == null) return;
        start();
        Source source = registerSource(generator);
        if (source.permanentlyRemoved) return;
        if (!accounting.containsKey(generator)) updateNative(generator);
        Frame frame = accounting.get(generator);
        double time = frame != null && Double.isFinite(frame.cutoff) ? frame.cutoff : now();
        source.removePermanently(Math.max(source.ledger.birth(), time));
        persist(source);
    }

    public static void remove(IsoObject object) {
        if (object == null || database == null) return;
        if (object instanceof IsoGenerator generator) {
            if (!accounting.containsKey(generator) && generator.getSquare() != null) updateNative(generator);
            loaded.remove(generator); // Unloading is not a power-off event.
        }
        var iterator = fridges.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            if (entry.getValue().object == object) {
                Fridge fridge = entry.getValue();
                fridge.external.settle(now(), false);
                writeLedger(table(fridge.saved, "external"), fridge.external);
                iterator.remove();
            }
        }
    }

    public static List<ItemContainer> containers() { return List.copyOf(fridges.keySet()); }

    public static void tickSources() {
        start();
        for (IsoGenerator generator : List.copyOf(loaded.keySet())) {
            if (generator.getSquare() == null) { loaded.remove(generator); continue; }
            updateNative(generator);
        }
        for (var entry : List.copyOf(fridges.entrySet())) {
            if (entry.getValue().object.getSquare() != null) observe(entry.getKey(), entry.getValue());
        }
    }

    private static void updateNative(IsoGenerator generator) {
        if (accounting.containsKey(generator)) return;
        Source known = loaded.get(generator);
        if (known != null && known.permanentlyRemoved) return;
        if (generator.isActivated() && (int) now() > lastHour(generator)) {
            // Explicit pairing also protects direct calls if another hook triggers nested updates.
            beforeGenerator(generator);
            try { generator.update(); afterGenerator(generator); }
            catch (RuntimeException | Error failure) { accounting.remove(generator); throw failure; }
        } else {
            Source source = loaded.get(generator);
            if (source != null) { source.confirm(now(), generator.isActivated()); persist(source); }
        }
    }

    public static void beforeGenerator(IsoGenerator generator) {
        if (generator == null || generator.getSquare() == null || accounting.containsKey(generator)) return;
        start();
        Source source = registerSource(generator);
        accounting.put(generator, new Frame(source, lastHour(generator)));
    }

    public static void accounting(IsoGenerator generator, int loopIndex, int pendingHours, int conditionLoss, float fuelUsed) {
        Frame frame = accounting.get(generator);
        if (frame == null) throw new IllegalStateException("native fuel accounting without prefix");
        if (frame.accounted) return;
        frame.accounted = true;
        frame.cutoff = PowerLedger.nativeCutoff(frame.lastHour, pendingHours, loopIndex,
                generator.getCondition(), conditionLoss, generator.getFuel(), fuelUsed);
    }

    public static void afterGenerator(IsoGenerator generator) {
        Frame frame = accounting.remove(generator);
        if (frame == null) return;
        if (Double.isFinite(frame.cutoff)) frame.source.confirm(Math.max(frame.source.ledger.birth(), frame.cutoff), false);
        frame.source.confirm(now(), generator.isActivated());
        persist(frame.source);
    }

    /** Native setActivated invokes food updates before writing its new state. */
    public static void switchActive(IsoGenerator generator, boolean active) {
        if (generator == null || generator.getSquare() == null || generator.isActivated() == active) return;
        start();
        Source source = registerSource(generator);
        Frame frame = accounting.get(generator);
        if (frame == null && generator.isActivated()) updateNative(generator);
        frame = accounting.get(generator);
        double time = frame != null && !active && Double.isFinite(frame.cutoff) ? frame.cutoff : now();
        source.confirm(Math.max(source.ledger.birth(), time), active);
        persist(source);
    }

    /** Connected is not an independent power gate in the installed engine. */
    public static void connect(IsoGenerator generator, boolean connected) {
        if (generator == null || generator.getSquare() == null) return;
        start(); registerSource(generator);
        if (!accounting.containsKey(generator)) updateNative(generator);
    }

    public static boolean isAccounting() { return !accounting.isEmpty(); }

    public static void save() {
        if (database == null) return;
        for (Source source : sources.values()) persist(source);
        for (Fridge fridge : fridges.values()) writeLedger(table(fridge.saved, "external"), fridge.external);
    }

    public static WindowResult windows(ItemContainer container, double from, double to) {
        new TimeWindow(from, to); // Reject invalid game-clock intervals before touching persistence.
        if (container == null || (!container.isFridge() && !container.isFreezer())) return new WindowResult(true, List.of());
        if (isAccounting()) return new WindowResult(false, List.of());
        start();
        Fridge fridge = fridges.get(container);
        if (fridge == null) { register(container.getParent()); fridge = fridges.get(container); }
        if (fridge == null) return new WindowResult(false, List.of());
        observe(container, fridge);
        return windows(fridge.key, from, to);
    }

    /** Stable across unload/reload, but changes when the appliance is moved. */
    public static String key(ItemContainer container) {
        if (container == null || (!container.isFridge() && !container.isFreezer())) return null;
        start();
        if (!fridges.containsKey(container)) register(container.getParent());
        Fridge fridge = fridges.get(container);
        if (fridge != null && !isAccounting()) observe(container, fridge);
        return fridge == null ? null : fridge.key;
    }

    /** Historical lookups remain valid after the food or appliance leaves the loaded world. */
    public static WindowResult windows(String key, double from, double to) {
        new TimeWindow(from, to);
        if ("".equals(key)) return new WindowResult(true, List.of());
        if (isAccounting()) return new WindowResult(false, List.of());
        start();
        KahluaTable saved = fridgeHistory.get(key);
        if (saved == null) return new WindowResult(false, List.of());
        double start = Math.max(from, Math.max(birth, number(saved, "birth", to)));
        if (start >= to) return new WindowResult(true, List.of());
        List<TimeWindow> result = new ArrayList<>();
        double cutoff = PowerLedger.gridCutoff(SandboxOptions.instance.getElecShutModifier(), SandboxOptions.instance.getTimeSinceApo());
        if (cutoff > start) {
            result.addAll(eligibility(saved, "grid", start, Math.min(to, cutoff)));
        }
        // A complete grid interval resolves unknown generator overlap without guessing it.
        if (covers(result, start, to)) return new WindowResult(true, result);
        boolean resolved = true;
        List<TimeWindow> generatorEligibility = eligibility(saved, "generatorEligibility", start, to);
        for (String reference : generatorEligibility.isEmpty() ? Set.<String>of() : references(saved)) {
            Source source = resolve(reference);
            if (source == null) { resolved = false; continue; }
            result.addAll(intersect(source.ledger.windows(start, to), generatorEligibility));
            for (TimeWindow needed : generatorEligibility) if (!source.ledger.resolves(needed.end())) resolved = false;
        }
        result.addAll(readLedger(table(saved, "external")).windows(start, to));
        // Another confirmed source may fully cover the query even if one source is missing.
        if (!resolved && covers(result, start, to)) resolved = true;
        return new WindowResult(resolved, result);
    }

    private static void observe(ItemContainer container, Fridge fridge) {
        IsoGridSquare square = fridge.object.getSquare();
        if (square == null) return;
        boolean gridNow = ItemContainer.isObjectPowered(fridge.object, false);
        // Persist geometry separately: current grid state is false after global shutdown.
        boolean gridQualified = gridNow || gridGeometry(fridge.object);
        fridge.grid.settle(now(), gridQualified);
        writeLedger(table(fridge.saved, "grid"), fridge.grid);
        boolean generatorEligible = SandboxOptions.instance.allowExteriorGenerator.getValue() || !square.isOutside();
        fridge.generatorEligibility.settle(now(), generatorEligible);
        writeLedger(table(fridge.saved, "generatorEligibility"), fridge.generatorEligibility);
        KahluaTable refs = table(fridge.saved, "sources");
        for (Source source : sources.values()) {
            if (affects(source, square)) refs.rawset(source.id, Boolean.TRUE);
        }
        boolean nativePower = square.haveElectricity();
        for (IsoGameCharacter.Location location : cachedGenerators(square)) {
            if (IsoGenerator.isPoweringSquare(location.x, location.y, location.z, square.getX(), square.getY(), square.getZ())) {
                String key = "@" + position(location.x, location.y, location.z);
                Source source = resolve(key);
                refs.rawset(source == null ? key : source.id, Boolean.TRUE);
                if (source != null) refs.rawset(key, null);
            }
        }
        // Unknown non-native supplies receive only continuously observed loaded time.
        boolean external = container.isPowered() && !gridNow && !nativePower;
        fridge.external.settle(now(), external);
        writeLedger(table(fridge.saved, "external"), fridge.external);
    }

    private static boolean affects(Source source, IsoGridSquare square) {
        return IsoGenerator.isPoweringSquare(source.x, source.y, source.z, square.getX(), square.getY(), square.getZ());
    }

    private static List<TimeWindow> eligibility(KahluaTable saved, String name, double from, double to) {
        PowerLedger ledger = readLedger(table(saved, name));
        // Structural qualification does not drain while unloaded. Changes are recorded when observed.
        if (to > ledger.cursor()) ledger.settle(to, ledger.active());
        return ledger.windows(from, to);
    }

    private static List<TimeWindow> intersect(List<TimeWindow> left, List<TimeWindow> right) {
        List<TimeWindow> result = new ArrayList<>();
        for (TimeWindow first : left) for (TimeWindow second : right) {
            double start = Math.max(first.start(), second.start());
            double end = Math.min(first.end(), second.end());
            if (end > start) result.add(new TimeWindow(start, end));
        }
        return result;
    }

    /** Minimal structural branch of ItemContainer.isSquarePowered, without mutating the grid. */
    private static boolean gridGeometry(IsoObject object) {
        ArrayList<IsoObject> parts = new ArrayList<>();
        object.getSpriteGridObjects(parts);
        if (parts.isEmpty()) parts.add(object);
        for (IsoObject part : parts) {
            IsoGridSquare square = part.getSquare();
            if (square == null || square.isNoPower()) continue;
            if (part.getPipedFuelAmount() > 0 || square.getRoom() != null) return true;
            for (IsoDirections direction : List.of(IsoDirections.N, IsoDirections.S, IsoDirections.E, IsoDirections.W)) {
                IsoGridSquare adjacent = square.getAdjacentSquare(direction);
                if (adjacent != null && adjacent.getRoom() != null) return true;
            }
        }
        return false;
    }

    private static Source resolve(String reference) {
        Source source = sources.get(reference);
        if (source != null || !reference.startsWith("@")) return source;
        Source match = null;
        for (Source candidate : sources.values()) {
            if (reference.equals("@" + position(candidate.x, candidate.y, candidate.z))) {
                if (match != null) return null; // Replaced generators require their unique known IDs.
                match = candidate;
            }
        }
        return match;
    }

    private static Set<String> references(KahluaTable saved) {
        Set<String> result = new LinkedHashSet<>();
        KahluaTableIterator iterator = table(saved, "sources").iterator();
        while (iterator.advance()) if (iterator.getKey() instanceof String id) result.add(id);
        return result;
    }

    private static boolean covers(List<TimeWindow> windows, double from, double to) {
        ArrayList<TimeWindow> sorted = new ArrayList<>(windows);
        sorted.sort(java.util.Comparator.comparingDouble(TimeWindow::start));
        double cursor = from;
        for (TimeWindow window : sorted) {
            if (window.start() > cursor) return false;
            cursor = Math.max(cursor, window.end());
            if (cursor >= to) return true;
        }
        return cursor >= to;
    }

    private static void persist(Source source) {
        KahluaTable saved = table(sourceTables, source.id);
        saved.rawset("x", (double) source.x); saved.rawset("y", (double) source.y); saved.rawset("z", (double) source.z);
        saved.rawset("permanentlyRemoved", source.permanentlyRemoved);
        writeLedger(saved, source.ledger);
    }

    private static void writeLedger(KahluaTable saved, PowerLedger ledger) {
        saved.rawset("birth", ledger.birth()); saved.rawset("cursor", ledger.cursor()); saved.rawset("active", ledger.active());
        KahluaTable intervals = table(saved, "intervals");
        int oldLength = intervals.len();
        int index = 1;
        for (TimeWindow window : ledger.history()) {
            intervals.rawset(index++, Double.valueOf(window.start())); intervals.rawset(index++, Double.valueOf(window.end()));
        }
        while (index <= oldLength) intervals.rawset(index++, (Object) null);
    }

    private static PowerLedger readLedger(KahluaTable saved) {
        double sourceBirth = number(saved, "birth", now());
        double cursor = number(saved, "cursor", sourceBirth);
        List<TimeWindow> history = new ArrayList<>();
        if (saved.rawget("intervals") instanceof KahluaTable intervals) {
            if ((intervals.len() & 1) != 0) throw new IllegalArgumentException("odd persisted power interval count");
            for (int index = 1; index <= intervals.len(); index += 2) {
                history.add(new TimeWindow(requiredNumber(intervals.rawget(index)), requiredNumber(intervals.rawget(index + 1))));
            }
        }
        return PowerLedger.restore(sourceBirth, cursor, Boolean.TRUE.equals(saved.rawget("active")), history);
    }

    private static KahluaTable table(KahluaTable owner, String key) {
        Object value = owner.rawget(key);
        if (value instanceof KahluaTable existing) return existing;
        if (value != null) throw new IllegalArgumentException("invalid power table: " + key);
        KahluaTable result = LuaManager.platform.newTable(); owner.rawset(key, result); return result;
    }

    private static double number(KahluaTable table, String key, double fallback) {
        Object value = table.rawget(key); return value == null ? fallback : requiredNumber(value);
    }
    private static int integer(KahluaTable table, String key) {
        double value = requiredNumber(table.rawget(key));
        if (value != (int) value) throw new IllegalArgumentException("invalid coordinate");
        return (int) value;
    }
    private static double requiredNumber(Object value) {
        if (!(value instanceof Number number) || !Double.isFinite(number.doubleValue())) throw new IllegalArgumentException("invalid power number");
        return number.doubleValue();
    }
    private static double now() { return GameTime.getInstance().getWorldAgeHours(); }
    private static String position(IsoGridSquare square) { return position(square.getX(), square.getY(), square.getZ()); }
    private static String position(int x, int y, int z) { return x + "," + y + "," + z; }
    private static int lastHour(IsoGenerator generator) {
        try { return LAST_HOUR.getInt(generator); } catch (IllegalAccessException failure) { throw new IllegalStateException(failure); }
    }
    private static Field field(Class<?> type, String name, Class<?> expected) {
        try {
            Field field = type.getDeclaredField(name);
            if (field.getType() != expected || !field.trySetAccessible()) throw new IllegalStateException("incompatible field " + name);
            return field;
        } catch (ReflectiveOperationException failure) { throw new IllegalStateException("missing field " + name, failure); }
    }
    private static List<IsoGameCharacter.Location> cachedGenerators(IsoGridSquare square) {
        if (square.getChunk() == null) return List.of();
        try {
            Object values = CHUNK_SOURCES.get(square.getChunk());
            if (values == null) return List.of();
            List<IsoGameCharacter.Location> result = new ArrayList<>();
            for (Object value : (List<?>) values) {
                if (!(value instanceof IsoGameCharacter.Location location)) throw new IllegalStateException("invalid generator cache");
                result.add(location);
            }
            return result;
        } catch (IllegalAccessException failure) { throw new IllegalStateException(failure); }
    }

    static final class Source {
        final String id;
        final int x, y, z;
        final PowerLedger ledger;
        boolean permanentlyRemoved;
        Source(String id, int x, int y, int z, PowerLedger ledger) {
            this.id = id; this.x = x; this.y = y; this.z = z; this.ledger = ledger;
        }
        void confirm(double time, boolean active) { ledger.settle(time, active && !permanentlyRemoved); }
        void removePermanently(double time) {
            if (permanentlyRemoved) return;
            ledger.settle(Math.max(time, ledger.cursor()), false);
            permanentlyRemoved = true;
        }
    }
    private static final class Frame {
        final Source source;
        final int lastHour;
        double cutoff = Double.NaN;
        boolean accounted;
        Frame(Source source, int lastHour) { this.source = source; this.lastHour = lastHour; }
    }
    private static final class Fridge {
        final String key;
        final IsoObject object;
        final KahluaTable saved;
        final double birth;
        final PowerLedger external;
        final PowerLedger grid;
        final PowerLedger generatorEligibility;
        Fridge(String key, IsoObject object, KahluaTable saved, double birth) {
            this.key = key; this.object = object; this.saved = saved; this.birth = birth;
            PowerLedger old = readLedger(table(saved, "external"));
            // An unloaded third-party supply has no native historical evidence.
            external = PowerLedger.restore(old.birth(), Math.max(now(), old.cursor()), false, old.history());
            grid = readLedger(table(saved, "grid"));
            generatorEligibility = readLedger(table(saved, "generatorEligibility"));
        }
    }
}
