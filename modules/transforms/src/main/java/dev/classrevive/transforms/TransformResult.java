package dev.classrevive.transforms;

import java.util.List;

public record TransformResult(byte[] bytes, List<Change> changes, boolean changeLimitReached) {
    public TransformResult { bytes = bytes.clone(); changes = List.copyOf(changes); }
    @Override public byte[] bytes() { return bytes.clone(); }
    public record Change(String pass, String className, String method, String descriptor,
                         int instructionIndex, int opcode, int left, int right, int result) {}
}
