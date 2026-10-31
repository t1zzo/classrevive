package dev.classrevive.transforms;

import java.util.*;

/** One ordered sweep of trusted pass instances; indices are relative to each pass input. */
public final class PassPipeline {
    private final List<BytecodePass> passes;
    public PassPipeline(List<BytecodePass> passes) {
        this.passes = List.copyOf(passes);
        if (passes.isEmpty() || passes.size()>64) throw new IllegalArgumentException("Require 1..64 passes");
        var ids = new HashSet<String>();
        for (var pass : this.passes)
            if (pass.id()==null || !pass.id().matches("[a-z0-9][a-z0-9-]{0,63}") || !ids.add(pass.id()))
                throw new IllegalArgumentException("Invalid or duplicate pass ID");
    }
    public TransformResult apply(byte[] original, int maxChanges) {
        if (original.length>16*1024*1024 || maxChanges<1 || maxChanges>1_000_000)
            throw new IllegalArgumentException("Require class <=16 MiB and change budget 1..1000000");
        byte[] bytes = original.clone(); var changes = new ArrayList<TransformResult.Change>();
        for (var pass : passes) {
            int remaining = maxChanges-changes.size(); if (remaining==0) break;
            var result = Objects.requireNonNull(pass.apply(bytes.clone(),remaining));
            byte[] next = result.bytes();
            if (next.length>16*1024*1024 || result.changes().size()>remaining ||
                result.changes().stream().anyMatch(change -> !pass.id().equals(change.pass())) ||
                (result.changes().isEmpty() && !Arrays.equals(bytes,next)))
                throw new IllegalStateException("Pass violated output/audit contract: " + pass.id());
            bytes = next; changes.addAll(result.changes());
        }
        return new TransformResult(bytes,changes,changes.size()==maxChanges);
    }
}
