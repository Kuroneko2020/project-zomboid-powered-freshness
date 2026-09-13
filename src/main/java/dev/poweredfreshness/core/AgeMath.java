package dev.poweredfreshness.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Age in days, time in game hours. Invalid inputs and arithmetic overflow are rejected. */
public final class AgeMath {
    private AgeMath() { }

    public static double rotRate(int option) {
        return switch (option) {
            case 1 -> 1.7;
            case 2 -> 1.4;
            case 3 -> 1.0;
            case 4 -> .7;
            case 5 -> .4;
            default -> throw new IllegalArgumentException("FoodRotSpeed must be 1..5");
        };
    }

    public static double reverse(double age, double hours, int option) {
        nonnegativeFinite(age, "age");
        nonnegativeFinite(hours, "hours");
        return decrease(age, hours, rotRate(option) / 24);
    }

    /**
     * Integrates the union of powered intervals, clipping it to [from,to]. Each powered
     * segment floors at zero before later ordinary aging. Input lists are never changed.
     * The caller supplies the ordinary rate; reversal deliberately has no fridge factor.
     */
    public static double integrate(double age, double from, double to, double ordinaryRatePerHour,
            double reverseRatePerHour, List<TimeWindow> powered) {
        nonnegativeFinite(age, "age");
        nonnegativeFinite(from, "from");
        nonnegativeFinite(to, "to");
        nonnegativeFinite(ordinaryRatePerHour, "ordinaryRatePerHour");
        nonnegativeFinite(reverseRatePerHour, "reverseRatePerHour");
        if (to < from) throw new IllegalArgumentException("to must be >= from");
        if (powered == null) throw new IllegalArgumentException("powered must not be null");
        List<TimeWindow> sorted = new ArrayList<>(powered);
        for (TimeWindow window : sorted) {
            if (window == null) throw new IllegalArgumentException("powered contains a null interval");
        }
        sorted.sort(Comparator.comparingDouble(TimeWindow::start));
        double cursor = from;
        for (TimeWindow window : sorted) {
            double end = Math.min(to, window.end());
            double start = Math.max(from, window.start());
            if (end <= cursor || end <= start) continue;
            if (start > cursor) age = increase(age, start - cursor, ordinaryRatePerHour);
            // Already visited overlap is removed by cursor, so multiple sources form a union.
            age = decrease(age, end - Math.max(start, cursor), reverseRatePerHour);
            cursor = end;
        }
        return increase(age, to - cursor, ordinaryRatePerHour);
    }

    private static double decrease(double age, double hours, double rate) {
        double amount = hours * rate;
        nonnegativeFinite(amount, "age decrement");
        return Math.max(0, age - amount);
    }

    private static double increase(double age, double hours, double rate) {
        double result = age + hours * rate;
        nonnegativeFinite(result, "age result");
        return result;
    }

    static void nonnegativeFinite(double value, String name) {
        if (!Double.isFinite(value) || value < 0) {
            throw new IllegalArgumentException(name + " must be finite and nonnegative");
        }
    }
}
