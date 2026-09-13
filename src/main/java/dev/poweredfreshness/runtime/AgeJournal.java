package dev.poweredfreshness.runtime;

import dev.poweredfreshness.core.AgeMath;
import dev.poweredfreshness.core.TimeWindow;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalDouble;

/** Ordered deferred age integration. Owned by the game's authoritative update thread. */
public final class AgeJournal {
    public record Segment(double from,double to,double ordinaryRatePerHour,
                          double reverseRatePerHour,String fridgeKey) {
        public Segment {
            finiteNonnegative(from,"from");
            finiteNonnegative(to,"to");
            finiteNonnegative(ordinaryRatePerHour,"ordinaryRatePerHour");
            finiteNonnegative(reverseRatePerHour,"reverseRatePerHour");
            if(to<from) throw new IllegalArgumentException("segment ends before it starts");
            if(fridgeKey==null) throw new IllegalArgumentException("fridgeKey must not be null");
        }
    }
    @FunctionalInterface public interface Resolver {
        Resolution resolve(String key,double from,double to);
    }
    public record Resolution(boolean resolved,List<TimeWindow> windows) {
        public Resolution {
            if(windows==null || windows.stream().anyMatch(w -> w==null))
                throw new IllegalArgumentException("windows must be non-null intervals");
            windows=List.copyOf(windows);
        }
    }
    private double baseAge;
    private double lastEnd=-1;
    private final List<Segment> pending=new ArrayList<>();

    public AgeJournal(double baseAge) {
        finiteNonnegative(baseAge,"baseAge");
        this.baseAge=baseAge;
    }
    public double baseAge() { return baseAge; }
    public List<Segment> segments() { return List.copyOf(pending); }

    /** Gaps carry no implied age change; backward or overlapping entries are rejected. */
    public void append(Segment segment) {
        if(segment==null) throw new IllegalArgumentException("segment must not be null");
        if(segment.from()<lastEnd) throw new IllegalArgumentException("segment overlaps prior recorded time");
        if(!pending.isEmpty()) {
            Segment last=pending.get(pending.size()-1);
            if(last.to()==segment.from() && last.fridgeKey().equals(segment.fridgeKey())
                && last.ordinaryRatePerHour()==segment.ordinaryRatePerHour()
                && last.reverseRatePerHour()==segment.reverseRatePerHour()) {
                pending.set(pending.size()-1,new Segment(last.from(),segment.to(),last.ordinaryRatePerHour(),last.reverseRatePerHour(),last.fridgeKey()));
                lastEnd=segment.to();
                return;
            }
        }
        pending.add(segment);
        lastEnd=segment.to();
    }

    /**
     * Commits and consumes the entire log only when every fridge segment is resolved.
     * Unknown power and arithmetic errors leave both the base and pending log intact.
     * Ordinary segments do not ask the resolver, but remain ordered behind pending ones.
     */
    public OptionalDouble resolve(Resolver resolver) {
        if(resolver==null) throw new IllegalArgumentException("resolver must not be null");
        double age=baseAge;
        for(Segment segment:pending) {
            List<TimeWindow> powered=List.of();
            if(!segment.fridgeKey().isEmpty()) {
                Resolution result=resolver.resolve(segment.fridgeKey(),segment.from(),segment.to());
                if(result==null) throw new IllegalArgumentException("resolver returned null");
                if(!result.resolved()) return OptionalDouble.empty();
                powered=result.windows();
            }
            age=AgeMath.integrate(age,segment.from(),segment.to(),segment.ordinaryRatePerHour(),segment.reverseRatePerHour(),powered);
        }
        baseAge=age;
        pending.clear();
        return OptionalDouble.of(age);
    }
    private static void finiteNonnegative(double value,String name) {
        if(!Double.isFinite(value)||value<0) throw new IllegalArgumentException(name+" must be finite and nonnegative");
    }
}
