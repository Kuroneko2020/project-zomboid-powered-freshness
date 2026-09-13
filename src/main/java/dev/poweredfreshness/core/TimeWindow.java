package dev.poweredfreshness.core;

/** A possibly empty half-open interval measured in nonnegative game hours. */
public record TimeWindow(double start, double end) {
    public TimeWindow {
        AgeMath.nonnegativeFinite(start, "start");
        AgeMath.nonnegativeFinite(end, "end");
        if (end < start) throw new IllegalArgumentException("end must be >= start");
    }
}
