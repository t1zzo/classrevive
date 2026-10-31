package dev.classrevive.pipeline;

import dev.classrevive.sandbox.*;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

/** Exports coordinator observations; this is not an authenticated execution attestation. */
public final class BehaviorEvidence {
    private BehaviorEvidence() {}
    public static BehaviorComparison.Result write(Path output,String originalArtifactSha256,String recoveredArtifactSha256,
            List<BehaviorObservation> original,List<BehaviorObservation> recovered) throws IOException {
        if(!originalArtifactSha256.matches("[a-f0-9]{64}") || !recoveredArtifactSha256.matches("[a-f0-9]{64}"))
            throw new IllegalArgumentException("Require artifact SHA-256 digests");
        var result=BehaviorComparison.compare(original,recovered);
        var report=new TreeMap<String,Object>();
        report.put("schema_version",1); report.put("kind","behavioral_comparison");
        report.put("scope","FINITE_TEST_OBSERVATIONS_ONLY"); report.put("status",result.status().name());
        report.put("original_artifact_sha256",originalArtifactSha256); report.put("recovered_artifact_sha256",recoveredArtifactSha256);
        report.put("matched",result.matched()); report.put("mismatched",result.mismatched()); report.put("inconclusive",result.inconclusive());
        var cases=new ArrayList<Map<String,Object>>();
        for(var item:result.cases()) {
            var row=new TreeMap<String,Object>(); row.put("case_id",item.id()); row.put("status",item.status().name()); row.put("reason",item.reason());
            row.put("original",evidence(item.original())); row.put("recovered",evidence(item.recovered())); cases.add(row);
        }
        report.put("cases",cases);
        report.put("limitations","Caller-supplied coordinator evidence, not cryptographic attestation. Exact stdout/stderr observations only; no universal equivalence claim.");
        EvidenceReport.write(output,report); return result;
    }
    private static Object evidence(BehaviorComparison.OutputEvidence item) {
        if(item==null) return null;
        var result=new TreeMap<String,Object>();
        result.put("stdout_sha256",item.stdoutSha256()); result.put("stdout_bytes",item.stdoutBytes());
        result.put("stderr_sha256",item.stderrSha256()); result.put("stderr_bytes",item.stderrBytes());
        result.put("test_input_sha256",item.inputSha256()); result.put("image_id",item.imageId());
        result.put("isolation_evidence_id",item.isolationEvidenceId()); result.put("isolation_verified",item.isolationVerified());
        result.put("outcome",item.outcome().name()); result.put("exit_code",item.exitCode()); result.put("truncated",item.truncated());
        return result;
    }
}
