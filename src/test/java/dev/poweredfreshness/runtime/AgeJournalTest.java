package dev.poweredfreshness.runtime;

import dev.poweredfreshness.core.TimeWindow;
import java.util.List;

public final class AgeJournalTest {
    public static void main(String[] args) {
        AgeJournal journal=new AgeJournal(.25);
        journal.append(new AgeJournal.Segment(0,24,1.0/24,1.0/24,"fridge"));
        journal.append(new AgeJournal.Segment(24,48,1.0/24,1.0/24,""));
        check(journal.resolve((key,from,to)->new AgeJournal.Resolution(false,List.of())).isEmpty(),"unresolved");
        near(.25,journal.baseAge()); check(journal.segments().size()==2,"must retain whole log");
        near(1,journal.resolve((key,from,to)->new AgeJournal.Resolution(true,List.of(new TimeWindow(0,24)))).orElseThrow());
        near(1,journal.baseAge()); check(journal.segments().isEmpty(),"committed segments clear");
        near(1,journal.resolve((key,from,to)->{throw new AssertionError("empty must not resolve");}).orElseThrow());
        rejects(()->journal.append(new AgeJournal.Segment(47,49,1,1,"")));

        AgeJournal merged=new AgeJournal(10);
        for(int i=0;i<1440;i++) merged.append(new AgeJournal.Segment(i/60.0,(i+1)/60.0,1.0/24,1.0/24,"same"));
        check(merged.segments().size()==1,"contiguous equal segments merge");
        near(24,merged.segments().get(0).to());
        near(9,merged.resolve((key,from,to)->new AgeJournal.Resolution(true,List.of(new TimeWindow(0,24)))).orElseThrow());

        AgeJournal atomic=new AgeJournal(2);
        atomic.append(new AgeJournal.Segment(0,24,1.0/24,1.0/24,"a"));
        atomic.append(new AgeJournal.Segment(24,48,1.0/24,1.0/24,"b"));
        var saved=atomic.segments();
        check(atomic.resolve((key,from,to)->new AgeJournal.Resolution(key.equals("a"),List.of(new TimeWindow(from,to)))).isEmpty(),"later pending");
        near(2,atomic.baseAge()); check(atomic.segments().equals(saved),"resolved prefix must not commit");
        near(0,atomic.resolve((key,from,to)->new AgeJournal.Resolution(true,List.of(new TimeWindow(from,to)))).orElseThrow());

        AgeJournal gaps=new AgeJournal(1);
        gaps.append(new AgeJournal.Segment(0,1,1,1,""));
        gaps.append(new AgeJournal.Segment(3,4,1,1,""));
        check(gaps.segments().size()==2,"do not merge gap");
        near(3,gaps.resolve((key,from,to)->{throw new AssertionError("ordinary calls resolver");}).orElseThrow());

        AgeJournal outage=new AgeJournal(.1);
        outage.append(new AgeJournal.Segment(0,24,1.0/24,1.0/24,"cold"));
        near(.5,outage.resolve((key,from,to)->new AgeJournal.Resolution(true,List.of(new TimeWindow(0,12)))).orElseThrow());
        AgeJournal rates=new AgeJournal(1);
        rates.append(new AgeJournal.Segment(0,1,1,1,"x"));
        rates.append(new AgeJournal.Segment(1,2,2,1,"x"));
        rates.append(new AgeJournal.Segment(2,3,2,1,"y"));
        check(rates.segments().size()==3,"rate/key boundary must remain");
        rejects(()->rates.append(new AgeJournal.Segment(2,4,1,1,"")));
        check(rates.segments().size()==3,"rejected append has no side effects");
        boolean immutable=false;
        try { rates.segments().clear(); } catch(UnsupportedOperationException expected) { immutable=true; }
        check(immutable,"segments snapshot immutable");

        rejects(()->new AgeJournal(Double.NaN)); rejects(()->new AgeJournal(-1));
        rejects(()->new AgeJournal(Double.POSITIVE_INFINITY));
        rejects(()->new AgeJournal.Segment(-1,1,1,1,""));
        rejects(()->new AgeJournal.Segment(2,1,1,1,""));
        rejects(()->new AgeJournal.Segment(0,Double.NaN,1,1,""));
        rejects(()->new AgeJournal.Segment(0,1,-1,1,""));
        rejects(()->new AgeJournal.Segment(0,1,1,Double.POSITIVE_INFINITY,""));
        rejects(()->new AgeJournal.Segment(0,1,1,1,null));
        AgeJournal overflow=new AgeJournal(Double.MAX_VALUE);
        overflow.append(new AgeJournal.Segment(0,2,Double.MAX_VALUE,1,""));
        rejects(()->overflow.resolve((key,from,to)->{throw new AssertionError();}));
        near(Double.MAX_VALUE,overflow.baseAge()); check(overflow.segments().size()==1,"overflow retains log");
        System.out.println("AgeJournalTest PASS");
    }
    static void check(boolean ok,String message) {if(!ok)throw new AssertionError(message);}
    static void near(double expected,double actual){if(expected!=actual&&Math.abs(expected-actual)>1e-10)throw new AssertionError(expected+" != "+actual);}
    static void rejects(Runnable run){boolean rejected=false;try{run.run();}catch(IllegalArgumentException expected){rejected=true;}check(rejected,"must reject invalid input");}
}
