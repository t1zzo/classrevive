package dev.classrevive.verification;

import java.util.*;

public record VerificationResult(Status status, int errors, int matchedSymbols, int expectedSymbols,
                                 int placeholderSignals, String detail, List<ClassCoverage> classCoverage,
                                 List<ClassRoundTrip> roundTrip, int syntaxErrors, Map<String,Integer> fileErrors,
                                 List<CompilerDiagnostic> diagnostics, boolean stubBacked) {
    public enum Status { COMPILED, COMPILE_FAILED, UNSUPPORTED_RELEASE, WORKER_FAILED }
    /**
     * One javac error in report order: source path relative to the candidate root, 1-based position (-1 when javac
     * gave none), diagnostic code and its message. The message can name target symbols; it is evidence for failure
     * cases and is not written to report.json.
     */
    public record CompilerDiagnostic(String file, long line, long column, String code, String message) {}
    public VerificationResult(Status status, int errors, int matchedSymbols, int expectedSymbols,
                              int placeholderSignals, String detail) {
        this(status, errors, matchedSymbols, expectedSymbols, placeholderSignals, detail, List.of());
    }
    public VerificationResult(Status status, int errors, int matchedSymbols, int expectedSymbols,
                              int placeholderSignals, String detail, List<ClassCoverage> classCoverage) {
        this(status, errors, matchedSymbols, expectedSymbols, placeholderSignals, detail, classCoverage, List.of());
    }
    public VerificationResult(Status status, int errors, int matchedSymbols, int expectedSymbols,
                              int placeholderSignals, String detail, List<ClassCoverage> classCoverage, List<ClassRoundTrip> roundTrip) {
        this(status, errors, matchedSymbols, expectedSymbols, placeholderSignals, detail, classCoverage, roundTrip, 0, Map.of());
    }
    public VerificationResult(Status status, int errors, int matchedSymbols, int expectedSymbols,
                              int placeholderSignals, String detail, List<ClassCoverage> classCoverage, List<ClassRoundTrip> roundTrip,
                              int syntaxErrors, Map<String,Integer> fileErrors) {
        this(status, errors, matchedSymbols, expectedSymbols, placeholderSignals, detail, classCoverage, roundTrip, syntaxErrors, fileErrors, List.of());
    }
    public VerificationResult(Status status, int errors, int matchedSymbols, int expectedSymbols,
                              int placeholderSignals, String detail, List<ClassCoverage> classCoverage, List<ClassRoundTrip> roundTrip,
                              int syntaxErrors, Map<String,Integer> fileErrors, List<CompilerDiagnostic> diagnostics) {
        this(status, errors, matchedSymbols, expectedSymbols, placeholderSignals, detail, classCoverage, roundTrip, syntaxErrors, fileErrors,
            diagnostics, false);
    }
    /**
     * @param syntaxErrors errors javac reported while parsing; when non-zero it stopped before attribution, so other
     *                     files' errors are unknown and no class was generated
     * @param fileErrors   javac errors per source path relative to the candidate root (errors without a file are omitted)
     * @param diagnostics  the first javac errors that have a source file, in report order (Phase 2AT failure cases)
     * @param stubBacked   Phase 2DF: the candidate compiled with inferred stubs (Phase 2CT) standing in for missing libraries,
     *                     so its compilation and signatures were checked against guesses, not the real types
     */
    public VerificationResult {
        classCoverage = List.copyOf(classCoverage);
        roundTrip = List.copyOf(roundTrip);
        fileErrors = Collections.unmodifiableMap(new TreeMap<>(fileErrors));
        diagnostics = List.copyOf(diagnostics);
    }
    /** Phase 2DF: the same result marked as compiled against inferred stubs (only a compiled result can be). */
    public VerificationResult withStubBacked(boolean value) {
        return new VerificationResult(status, errors, matchedSymbols, expectedSymbols, placeholderSignals, detail, classCoverage, roundTrip,
            syntaxErrors, fileErrors, diagnostics, value && status == Status.COMPILED);
    }
    /** Structural completeness; a stub-backed result stays complete (exit code and status unchanged) but carries {@link #stubBacked()}. */
    public boolean complete() { return status == Status.COMPILED && matchedSymbols == expectedSymbols && expectedSymbols > 0 && placeholderSignals == 0; }
    public int fullyCoveredClasses() {
        return (int) classCoverage.stream().filter(ClassCoverage::complete).count();
    }
    public int averageClassCoveragePermille() {
        return classCoverage.isEmpty() ? 0 : (int) (classCoverage.stream().mapToLong(ClassCoverage::coveragePermille).sum() / classCoverage.size());
    }
    /** True when original bytes were supplied and the candidate compiled, so per-method round trip ran. */
    public boolean roundTripMeasured() { return !roundTrip.isEmpty(); }
    public int roundTripIdentical() { return roundTrip.stream().mapToInt(ClassRoundTrip::identical).sum(); }
    public int roundTripEquivalent() { return roundTrip.stream().mapToInt(ClassRoundTrip::equivalent).sum(); }
    public int roundTripDifferent() { return roundTrip.stream().mapToInt(ClassRoundTrip::different).sum(); }
}
