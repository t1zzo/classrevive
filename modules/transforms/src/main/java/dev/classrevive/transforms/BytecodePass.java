package dev.classrevive.transforms;

/** Static byte-array transformation; implementations must never define or execute target classes. */
public interface BytecodePass {
    String id();
    TransformResult apply(byte[] original, int maxChanges);
}
