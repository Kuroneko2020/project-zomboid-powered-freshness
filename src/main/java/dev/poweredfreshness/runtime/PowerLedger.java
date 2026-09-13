package dev.poweredfreshness.runtime;
import dev.poweredfreshness.core.TimeWindow;
import java.util.ArrayList;
import java.util.List;

/** Confirmed powered history. An active source never implies future confirmation. */
final class PowerLedger {
    private final double birth;
    private double cursor;
    private boolean active;
    private final List<TimeWindow> history = new ArrayList<>();

    PowerLedger(double birth, boolean active) {
        validTime(birth);
        this.birth = this.cursor = birth;
        this.active = active;
    }

    void settle(double time, boolean nextActive) {
        validTime(time);
        if (time < cursor) return; // A repeated native callback cannot rewind confirmation.
        if (active && time > cursor) append(cursor, time);
        cursor = time;
        active = nextActive;
    }

    private void append(double from, double to) {
        if (!history.isEmpty()) {
            TimeWindow last = history.get(history.size() - 1);
            if (last.end() == from) {
                history.set(history.size() - 1, new TimeWindow(last.start(), to));
                return;
            }
        }
        history.add(new TimeWindow(from, to));
    }

    boolean resolves(double time) { validTime(time); return time <= cursor || !active; }
    double birth() { return birth; }
    double cursor() { return cursor; }
    boolean active() { return active; }
    List<TimeWindow> history() { return List.copyOf(history); }

    List<TimeWindow> windows(double from, double to) {
        validTime(from); validTime(to);
        if (to < from) throw new IllegalArgumentException("reversed query");
        List<TimeWindow> result = new ArrayList<>();
        for (TimeWindow window : history) {
            double start = Math.max(from, window.start());
            double end = Math.min(to, window.end());
            if (end > start) result.add(new TimeWindow(start, end));
        }
        return result;
    }

    static PowerLedger restore(double birth, double cursor, boolean active, List<TimeWindow> history) {
        validTime(cursor);
        if (cursor < birth) throw new IllegalArgumentException("cursor before birth");
        PowerLedger ledger = new PowerLedger(birth, active);
        double end = birth;
        for (TimeWindow window : history) {
            if (window.start() < end || window.end() > cursor) throw new IllegalArgumentException("invalid persisted power history");
            ledger.append(window.start(), window.end());
            end = window.end();
        }
        ledger.cursor = cursor;
        return ledger;
    }

    /** Called before native fuel mutation: index is the real loop index, not a prediction. */
    static double nativeCutoff(int start, int pending, int index, int condition, int conditionLoss, float fuel, float fuelUsed) {
        if (start < 0 || pending < 0 || index < 0 || conditionLoss < 0 || !Float.isFinite(fuel) || !Float.isFinite(fuelUsed)) {
            throw new IllegalArgumentException("invalid native generator accounting");
        }
        if (pending == 0 || (fuel - fuelUsed > 0 && condition - conditionLoss > 0)) return Double.NaN;
        return (double) start + Math.min((long) index + 1, pending);
    }

    static double gridCutoff(int shutdownDay, int timeSinceApo) {
        if (timeSinceApo < 1) throw new IllegalArgumentException("invalid TimeSinceApo");
        return 24.0 * (shutdownDay - (timeSinceApo - 1.0) * 30.0);
    }

    private static void validTime(double value) {
        if (!Double.isFinite(value) || value < 0) throw new IllegalArgumentException("invalid game time");
    }
}
