package dev.poweredfreshness.runtime;

import dev.poweredfreshness.core.AgeMath;
import java.util.List;

/** No game bootstrap required: exercises the exact power ledger consumed by PowerTracker. */
public final class PowerTrackerTests {
    private static int passed;
    public static void main(String[] args) {
        test("unloaded active source stays unresolved", () -> {
            PowerLedger ledger = new PowerLedger(10, true);
            ledger.settle(12, true);
            check(!ledger.resolves(20), "unobserved future was credited");
            near(2, hours(ledger, 0, 20));
        });
        test("physical removal closes source while unload preserves unknown future", () -> {
            PowerTracker.Source source = new PowerTracker.Source("generator", 1, 2, 0, new PowerLedger(10, true));
            source.confirm(12, true);
            check(!source.ledger.resolves(20), "unload falsely resolved future");
            source.removePermanently(15);
            check(source.ledger.resolves(20), "deleted source kept future unresolved");
            near(5, hours(source.ledger, 0, 20));
        });
        test("duplicate remove and stale native callback cannot reopen deleted source", () -> {
            PowerTracker.Source source = new PowerTracker.Source("generator", 1, 2, 0, new PowerLedger(10, true));
            source.confirm(13, false); // Native catch-up found fuel exhaustion before deletion.
            source.removePermanently(20);
            source.confirm(20, true); // The physical object's activated flag is still true.
            source.removePermanently(21);
            source.confirm(22, true);
            check(source.permanentlyRemoved && source.ledger.resolves(30), "callback reopened deleted source");
            near(3, hours(source.ledger, 0, 30));
        });
        test("permanent deletion closes even when callback time precedes confirmation", () -> {
            PowerTracker.Source source = new PowerTracker.Source("generator", 1, 2, 0, new PowerLedger(10, true));
            source.confirm(15, true);
            source.removePermanently(14);
            check(source.ledger.resolves(30), "old cutoff left removed source active");
            near(5, hours(source.ledger, 0, 30));
        });
        test("native fuel cutoff closes only actual powered hours", () -> {
            PowerLedger ledger = new PowerLedger(10.5, true);
            double cutoff = PowerLedger.nativeCutoff(10, 12, 2, 100, 1, 3, 3);
            near(13, cutoff);
            ledger.settle(cutoff, false);
            check(ledger.resolves(22), "stopped source unresolved");
            near(2.5, hours(ledger, 0, 22));
        });
        test("native random condition result can stop before fuel", () -> {
            near(102, PowerLedger.nativeCutoff(100, 10, 1, 2, 2, 10, .1f));
            check(Double.isNaN(PowerLedger.nativeCutoff(100, 10, 10, 100, 1, 10, 1)), "healthy generator falsely stopped");
        });
        test("replayed callback does not duplicate or reopen history", () -> {
            PowerLedger ledger = new PowerLedger(0, true);
            ledger.settle(4, false);
            ledger.settle(4, false);
            ledger.settle(2, true);
            near(4, hours(ledger, 0, 10));
            check(ledger.resolves(10), "stale callback reopened source");
        });
        test("save restore keeps pending history", () -> {
            PowerLedger ledger = new PowerLedger(10, true);
            ledger.settle(12, false);
            ledger.settle(15, true);
            PowerLedger restored = PowerLedger.restore(ledger.birth(), ledger.cursor(), ledger.active(), ledger.history());
            check(!restored.resolves(20), "restore invented future");
            restored.settle(18, false);
            near(5, hours(restored, 0, 30));
        });
        test("initial mod birth excludes old powered time", () -> {
            PowerLedger ledger = new PowerLedger(50, true);
            ledger.settle(60, false);
            near(10, hours(ledger, 0, 60));
        });
        test("市电 uses apocalypse offset", () -> {
            near(240, PowerLedger.gridCutoff(40, 2));
            near(-480, PowerLedger.gridCutoff(40, 3));
        });
        test("power interval reversal followed by ordinary aging", () -> {
            PowerLedger ledger = new PowerLedger(0, true);
            ledger.settle(12, false);
            near(.5, AgeMath.integrate(.1, 0, 24, 1.0 / 24, 1.0 / 24, ledger.history()));
        });
        if (args.length > 0 && "--game".equals(args[0])) {
            test("installed game private field contracts are accessible", () -> {
                try { Class.forName("dev.poweredfreshness.runtime.PowerTracker"); }
                catch (ClassNotFoundException failure) { throw new AssertionError(failure); }
            });
        }
        System.out.println("PowerTrackerTests: " + passed + " passed");
    }
    private static double hours(PowerLedger ledger, double from, double to) {
        return ledger.windows(from, to).stream().mapToDouble(window -> window.end() - window.start()).sum();
    }
    private static void test(String name, Runnable body) {
        try { body.run(); passed++; } catch (Throwable failure) { throw new AssertionError(name, failure); }
    }
    private static void check(boolean test, String message) { if (!test) throw new AssertionError(message); }
    private static void near(double expected, double actual) {
        check(Double.isFinite(actual) && Math.abs(expected - actual) < 1e-9, "expected " + expected + ", actual " + actual);
    }
}
