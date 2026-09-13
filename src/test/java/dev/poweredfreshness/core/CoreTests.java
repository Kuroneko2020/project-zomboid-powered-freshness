package dev.poweredfreshness.core;

import java.util.ArrayList;
import java.util.List;

/** Standalone behavioral tests; all expected ages are calculated by hand. */
public final class CoreTests {
    private static int passed;
    private static int failed;

    public static void main(String[] args) {
        test("one powered day removes one age day", () -> near(2, AgeMath.reverse(3, 24, 3)));
        test("all sandbox speeds", () -> {
            double[] expected = {3.3, 3.6, 4, 4.3, 4.6};
            for (int option = 1; option <= 5; option++) near(expected[option - 1], AgeMath.reverse(5, 24, option));
        });
        test("reverse floors at zero", () -> near(0, AgeMath.reverse(.1, 12, 3)));
        test("unpowered time after zero still ages", () -> near(.5,
                AgeMath.integrate(.1, 0, 24, 1.0 / 24, 1.0 / 24, List.of(new TimeWindow(0, 12)))));
        test("ordinary time before power can be recovered", () -> near(.1,
                AgeMath.integrate(.1, 0, 24, 1.0 / 24, 1.0 / 24, List.of(new TimeWindow(12, 24)))));
        test("overlapping sources clipped sorted and not double counted", () -> {
            List<TimeWindow> input = new ArrayList<>(List.of(new TimeWindow(18, 40), new TimeWindow(0, 15), new TimeWindow(12, 20)));
            near(1, AgeMath.integrate(2, 6, 30, 1.0 / 24, 1.0 / 24, input));
            check(input.get(0).start() == 18, "caller list was reordered");
        });
        test("two outages retain chronological zero floors", () -> near(.25,
                AgeMath.integrate(0, 0, 24, 1.0 / 24, 1.0 / 24,
                        List.of(new TimeWindow(0, 6), new TimeWindow(12, 18)))));
        test("frozen ordinary rate zero does not stop reversal", () -> near(1,
                AgeMath.integrate(2, 0, 24, 0, 1.0 / 24, List.of(new TimeWindow(0, 24)))));
        test("no sources uses supplied ordinary rate", () -> near(2.2,
                AgeMath.integrate(2, 0, 24, .2 / 24, 1.0 / 24, List.of())));
        test("outside and empty intervals contribute nothing", () -> near(2.5,
                AgeMath.integrate(2, 12, 24, 1.0 / 24, 1.0 / 24,
                        List.of(new TimeWindow(0, 12), new TimeWindow(18, 18), new TimeWindow(24, 30)))));
        test("zero duration leaves age unchanged", () -> near(2,
                AgeMath.integrate(2, 12, 12, 1, 1, List.of(new TimeWindow(0, 24)))));
        test("invalid numbers do not contaminate save ages", () -> {
            rejects(() -> AgeMath.reverse(Double.NaN, 1, 3));
            rejects(() -> AgeMath.reverse(-1, 1, 3));
            rejects(() -> AgeMath.reverse(1, -1, 3));
            rejects(() -> AgeMath.reverse(1, Double.POSITIVE_INFINITY, 3));
            rejects(() -> AgeMath.rotRate(0));
            rejects(() -> AgeMath.rotRate(6));
            rejects(() -> AgeMath.integrate(1, 2, 1, 1, 1, List.of()));
            rejects(() -> AgeMath.integrate(1, 0, 1, -1, 1, List.of()));
            rejects(() -> AgeMath.integrate(1, 0, 1, 1, Double.NaN, List.of()));
            rejects(() -> AgeMath.integrate(1, -1, 1, 1, 1, List.of()));
            rejects(() -> AgeMath.integrate(1, 0, 1, 1, 1, null));
            rejects(() -> AgeMath.integrate(1, 0, Double.MAX_VALUE, Double.MAX_VALUE, 1, List.of()));
            rejects(() -> new TimeWindow(2, 1));
            rejects(() -> new TimeWindow(-1, 1));
            rejects(() -> new TimeWindow(0, Double.NaN));
        });
        test("new sessions require explicit reset", () -> {
            SnapshotOrder order = new SnapshotOrder(8);
            check(!order.accept("A", 1, 1), "uninitialized session accepted");
            order.reset("A");
            check(order.accept("A", 1, 1), "first snapshot rejected");
            check(!order.accept("B", 100, 1), "foreign session accepted");
            order.reset("B");
            check(order.accept("B", 0, 1), "new session sequence zero rejected");
            check(!order.accept("A", 200, 1), "previous session replay accepted");
        });
        test("per item ordering and signed identifiers", () -> {
            SnapshotOrder order = new SnapshotOrder(8);
            order.reset("A");
            check(order.accept("A", 5, Integer.MIN_VALUE), "negative ID rejected");
            check(!order.accept("A", 5, Integer.MIN_VALUE), "duplicate accepted");
            check(!order.accept("A", 4, Integer.MIN_VALUE), "stale accepted");
            check(order.accept("A", 1, Integer.MAX_VALUE), "independent item rejected");
            check(order.accept("A", 6, Integer.MIN_VALUE), "new snapshot rejected");
            order.reset("A");
            check(!order.accept("A", 6, Integer.MIN_VALUE), "same-session reset reopened replay");
        });
        test("bounded eviction does not reopen old snapshots", () -> {
            SnapshotOrder order = new SnapshotOrder(2);
            order.reset("A");
            check(order.accept("A", 10, 1), "first rejected");
            check(order.accept("A", 20, 2), "second rejected");
            check(order.accept("A", 30, 3), "third rejected");
            check(!order.accept("A", 10, 1), "evicted replay accepted");
            check(!order.accept("A", 5, 99), "below-watermark packet accepted");
            check(order.accept("A", 31, 1), "new version of evicted item rejected");
            check(!order.accept("A", 20, 2), "second evicted replay accepted");
        });
        test("malformed snapshot rejected without poisoning order", () -> {
            rejects(() -> new SnapshotOrder(0));
            SnapshotOrder order = new SnapshotOrder(2);
            rejects(() -> order.reset(" "));
            order.reset("A");
            check(!order.accept(null, 1, 1), "null session accepted");
            check(!order.accept("A", -1, 1), "negative sequence accepted");
            check(order.accept("A", 0, 1), "invalid packet advanced sequence");
            check(order.accept("A", Long.MAX_VALUE, 1), "maximum sequence rejected");
            check(!order.accept("A", 0, 1), "sequence wrap replay accepted");
        });
        System.out.println("CoreTests: " + passed + " passed, " + failed + " failed");
        if (failed != 0) throw new AssertionError("core behavioral tests failed");
    }

    private static void test(String name, Runnable action) {
        try { action.run(); passed++; }
        catch (Throwable failure) { failed++; System.out.println("FAIL " + name + ": " + failure); }
    }
    private static void near(double expected, double actual) {
        check(Double.isFinite(actual) && Math.abs(expected - actual) < 1e-10, "expected " + expected + ", actual " + actual);
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    private static void rejects(Runnable action) {
        try { action.run(); } catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("expected IllegalArgumentException");
    }
}
