package dev.classrevive.sandbox;

import java.util.*;
import java.security.*;

/** Finite stdout/stderr observations cannot establish universal program equivalence. */
public final class BehaviorComparison {
    public enum Status { MATCHED_CASES, MISMATCH, INCONCLUSIVE }
    public record OutputEvidence(String stdoutSha256,int stdoutBytes,String stderrSha256,int stderrBytes,
        String inputSha256,String imageId,String isolationEvidenceId,boolean isolationVerified,
        BehaviorObservation.Outcome outcome,int exitCode,boolean truncated) {}
    public record CaseResult(String id,Status status,String reason,OutputEvidence original,OutputEvidence recovered) {}
    public record Result(Status status,int matched,int mismatched,int inconclusive,List<CaseResult> cases) {
        public Result { cases=List.copyOf(cases); }
    }
    private BehaviorComparison() {}
    public static Result compare(List<BehaviorObservation> original,List<BehaviorObservation> recovered) {
        var left=index(original); var right=index(recovered);
        long bytes=0; for(var item:original) bytes+=item.outputBytes(); for(var item:recovered) bytes+=item.outputBytes();
        if(bytes>32L*1024*1024) throw new IllegalArgumentException("Comparison output budget exceeds 32 MiB");
        var ids=new TreeSet<String>(left.keySet()); ids.addAll(right.keySet());
        var cases=new ArrayList<CaseResult>(); int matched=0,mismatched=0,inconclusive=0;
        for(String id:ids) {
            var a=left.get(id); var b=right.get(id); String reason=null;
            if(a==null || b==null) reason="MISSING_OBSERVATION";
            else if(!a.isolationVerified() || !b.isolationVerified()) reason="ISOLATION_NOT_VERIFIED";
            else if(!a.inputSha256().equals(b.inputSha256()) || !a.imageId().equals(b.imageId()) || !a.isolationEvidenceId().equals(b.isolationEvidenceId())) reason="CONTEXT_MISMATCH";
            else if(a.outcome()!=BehaviorObservation.Outcome.COMPLETED || b.outcome()!=BehaviorObservation.Outcome.COMPLETED) reason="INCOMPLETE_EXECUTION";
            else if(a.truncated() || b.truncated()) reason="TRUNCATED_OUTPUT";
            else if(a.exitCode()!=0 || b.exitCode()!=0) reason="UNSUCCESSFUL_EXECUTION";
            Status status;
            if(reason!=null) { status=Status.INCONCLUSIVE; inconclusive++; }
            else if(Arrays.equals(a.stdout(),b.stdout()) && Arrays.equals(a.stderr(),b.stderr())) { status=Status.MATCHED_CASES; reason="OBSERVED_BYTES_MATCH"; matched++; }
            else { status=Status.MISMATCH; reason="OBSERVED_BYTES_DIFFER"; mismatched++; }
            cases.add(new CaseResult(id,status,reason,evidence(a),evidence(b)));
        }
        Status status=mismatched>0?Status.MISMATCH:(inconclusive>0 || cases.isEmpty()?Status.INCONCLUSIVE:Status.MATCHED_CASES);
        return new Result(status,matched,mismatched,inconclusive,cases);
    }
    private static Map<String,BehaviorObservation> index(List<BehaviorObservation> observations) {
        if(observations.size()>100) throw new IllegalArgumentException("At most 100 cases per side");
        var map=new HashMap<String,BehaviorObservation>();
        for(var observation:observations) if(map.putIfAbsent(observation.caseId(),observation)!=null) throw new IllegalArgumentException("Duplicate case ID");
        return map;
    }
    private static OutputEvidence evidence(BehaviorObservation observation) {
        if(observation==null) return null;
        byte[] out=observation.stdout(),err=observation.stderr();
        return new OutputEvidence(hash(out),out.length,hash(err),err.length,observation.inputSha256(),observation.imageId(),
            observation.isolationEvidenceId(),observation.isolationVerified(),observation.outcome(),observation.exitCode(),observation.truncated());
    }
    private static String hash(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch(NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
