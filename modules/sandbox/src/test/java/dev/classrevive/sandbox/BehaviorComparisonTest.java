package dev.classrevive.sandbox;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class BehaviorComparisonTest {
    String hash="a".repeat(64), image="sha256:"+"b".repeat(64);
    BehaviorObservation observation(String id,byte[] out) {
        return new BehaviorObservation(id,hash,image,"probe-v1",true,BehaviorObservation.Outcome.COMPLETED,0,out,new byte[0],false);
    }
    @Test void matchesOnlyObservedCasesAndPreservesRawBytes() {
        var a=observation("case1",new byte[]{65,10});
        var result=BehaviorComparison.compare(List.of(a),List.of(a));
        assertEquals(BehaviorComparison.Status.MATCHED_CASES,result.status()); assertEquals(1,result.matched());
        assertEquals(BehaviorComparison.Status.MISMATCH,BehaviorComparison.compare(List.of(a),List.of(observation("case1",new byte[]{65,13,10}))).status());
    }
    @Test void missingAndEmptyCasesCannotPass() {
        var a=observation("one",new byte[0]);
        assertEquals(BehaviorComparison.Status.INCONCLUSIVE,BehaviorComparison.compare(List.of(),List.of()).status());
        assertEquals(BehaviorComparison.Status.INCONCLUSIVE,BehaviorComparison.compare(List.of(a),List.of()).status());
        assertThrows(IllegalArgumentException.class,()->BehaviorComparison.compare(List.of(a,a),List.of(a)));
    }
    @Test void rejectsIncompleteExecutionAndContextMismatch() {
        var good=observation("one",new byte[0]);
        var bad=List.of(
            new BehaviorObservation("one",hash,image,"probe-v1",false,BehaviorObservation.Outcome.COMPLETED,0,new byte[0],new byte[0],false),
            new BehaviorObservation("one",hash,image,"probe-v1",true,BehaviorObservation.Outcome.TIMED_OUT,-1,new byte[0],new byte[0],false),
            new BehaviorObservation("one",hash,image,"probe-v1",true,BehaviorObservation.Outcome.COMPLETED,1,new byte[0],new byte[0],false),
            new BehaviorObservation("one",hash,image,"probe-v1",true,BehaviorObservation.Outcome.COMPLETED,0,new byte[0],new byte[0],true),
            new BehaviorObservation("one","c".repeat(64),image,"probe-v1",true,BehaviorObservation.Outcome.COMPLETED,0,new byte[0],new byte[0],false));
        for(var item:bad) assertEquals(BehaviorComparison.Status.INCONCLUSIVE,BehaviorComparison.compare(List.of(good),List.of(item)).status());
    }
    @Test void observationDefensivelyCopiesOutputs() {
        byte[] bytes={1}; var a=observation("one",bytes); bytes[0]=2;
        byte[] copy=a.stdout(); copy[0]=3; assertEquals(1,a.stdout()[0]);
        assertThrows(IllegalArgumentException.class,()->observation("one",new byte[1024*1024+1]));
    }
    @Test void contextAndInfrastructureDifferencesRemainInconclusive() {
        var good=observation("one",new byte[0]);
        for(var item:List.of(
            new BehaviorObservation("one",hash,"sha256:"+"c".repeat(64),"probe-v1",true,BehaviorObservation.Outcome.COMPLETED,0,new byte[0],new byte[0],false),
            new BehaviorObservation("one",hash,image,"probe-v2",true,BehaviorObservation.Outcome.COMPLETED,0,new byte[0],new byte[0],false),
            new BehaviorObservation("one",hash,image,"probe-v1",true,BehaviorObservation.Outcome.CANCELLED,-1,new byte[0],new byte[0],false),
            new BehaviorObservation("one",hash,image,"probe-v1",true,BehaviorObservation.Outcome.INFRASTRUCTURE_FAILED,-1,new byte[0],new byte[0],false)))
            assertEquals(BehaviorComparison.Status.INCONCLUSIVE,BehaviorComparison.compare(List.of(good),List.of(item)).status());
        var stderr=new BehaviorObservation("one",hash,image,"probe-v1",true,BehaviorObservation.Outcome.COMPLETED,0,new byte[0],new byte[]{1},false);
        var result=BehaviorComparison.compare(List.of(good),List.of(stderr));
        assertEquals(BehaviorComparison.Status.MISMATCH,result.status());
        assertThrows(UnsupportedOperationException.class,()->result.cases().clear());
    }
    @Test void enforcesCaseAndAggregateCaptureBudgets() {
        var many=new ArrayList<BehaviorObservation>();
        for(int i=0;i<101;i++) many.add(observation("case"+i,new byte[0]));
        assertThrows(IllegalArgumentException.class,()->BehaviorComparison.compare(many,List.of()));
        var large=new ArrayList<BehaviorObservation>();
        for(int i=0;i<17;i++) large.add(observation("case"+i,new byte[1024*1024]));
        assertThrows(IllegalArgumentException.class,()->BehaviorComparison.compare(large,large));
    }
}
