package dev.classrevive.engine;

public record EngineResult(String engine, String version, Status status, int exitCode,
                           long sourceFiles, long elapsedMillis, String message, DiagnosticCode diagnosticCode) {
    public enum Status { OUTPUT_PRODUCED, NO_SOURCES, FAILED, TIMED_OUT, OUTPUT_LIMIT, CANCELLED }
    public enum DiagnosticCode {
        NONE, NO_SOURCE_FILES, ENGINE_EXIT_NONZERO, DEADLINE_EXCEEDED, OUTPUT_BUDGET_EXCEEDED,
        CANCELLED, WORKER_SETUP_FAILED, WORKER_START_FAILED, WORKER_RUNTIME_FAILURE
    }
    public EngineResult(String engine, String version, Status status, int exitCode,
                        long sourceFiles, long elapsedMillis, String message) {
        this(engine, version, status, exitCode, sourceFiles, elapsedMillis, message, defaultCode(status));
    }
    private static DiagnosticCode defaultCode(Status status) {
        return switch (status) {
            case OUTPUT_PRODUCED -> DiagnosticCode.NONE;
            case NO_SOURCES -> DiagnosticCode.NO_SOURCE_FILES;
            case FAILED -> DiagnosticCode.WORKER_RUNTIME_FAILURE;
            case TIMED_OUT -> DiagnosticCode.DEADLINE_EXCEEDED;
            case OUTPUT_LIMIT -> DiagnosticCode.OUTPUT_BUDGET_EXCEEDED;
            case CANCELLED -> DiagnosticCode.CANCELLED;
        };
    }
}
