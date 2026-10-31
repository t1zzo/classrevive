package dev.classrevive.sandbox;

import java.util.Objects;

/** Coordinator evidence, not target-supplied attestation. Never executes a program. */
public record BehaviorObservation(String caseId,String inputSha256,String imageId,String isolationEvidenceId,
        boolean isolationVerified,Outcome outcome,int exitCode,byte[] stdout,byte[] stderr,boolean truncated) {
    public enum Outcome { COMPLETED, TIMED_OUT, CANCELLED, INFRASTRUCTURE_FAILED }
    public BehaviorObservation {
        Objects.requireNonNull(caseId); Objects.requireNonNull(inputSha256); Objects.requireNonNull(imageId);
        Objects.requireNonNull(isolationEvidenceId); Objects.requireNonNull(outcome);
        if(!caseId.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,127}") || !inputSha256.matches("[a-f0-9]{64}") ||
            !imageId.matches("sha256:[a-f0-9]{64}") || isolationEvidenceId.length()>256 ||
            (isolationVerified && isolationEvidenceId.isBlank())) throw new IllegalArgumentException("Invalid observation identity or context");
        if(stdout.length>1024*1024 || stderr.length>1024*1024) throw new IllegalArgumentException("Observation output exceeds 1 MiB per stream");
        stdout=stdout.clone(); stderr=stderr.clone();
    }
    @Override public byte[] stdout() { return stdout.clone(); }
    @Override public byte[] stderr() { return stderr.clone(); }
    int outputBytes() { return stdout.length+stderr.length; }
}
