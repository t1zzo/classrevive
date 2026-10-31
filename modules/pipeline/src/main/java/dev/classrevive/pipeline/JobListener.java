package dev.classrevive.pipeline;

import dev.classrevive.engine.EngineResult;

/**
 * Phase G1: progress of a {@link DecompilationJob} for callers that show it (the desktop GUI). Additive: a job without a
 * listener behaves exactly as before. Engine callbacks arrive on the engine orchestration threads and the others on the
 * job's thread, so implementations must be thread-safe, quick, and must not throw.
 */
public interface JobListener {
    /** Stages in the order a job reaches them; STUBS, PER_FILE and FALLBACK are reported only when they run. */
    enum Stage { INTAKE, ENGINES, STUBS, SELECTION, PER_FILE, FALLBACK, REPORTS }

    default void stage(Stage stage) {}
    default void engineStarted(String engine) {}
    default void engineFinished(EngineResult result) {}

    JobListener NONE = new JobListener() {};
}
