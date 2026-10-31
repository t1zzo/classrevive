package dev.classrevive.selection;

import dev.classrevive.verification.EnumBodyFolding;
import dev.classrevive.verification.IdiomResugar;
import dev.classrevive.verification.MethodSplice;
import dev.classrevive.verification.SourceRepair;
import dev.classrevive.verification.VerificationResult;
import java.util.List;
import java.util.Map;

/**
 * {@code repairs} lists the compiler-guided source edits present in the selected sources (empty for engine output);
 * {@code methods} lists the method bodies taken from another engine's version of their file (Phase 2BD);
 * {@code tried} holds every verified attempt in attempt order (engine candidates, hybrids, repairs, swaps,
 * compositions), so failure capture (Phase 2AT) can compare the selected candidate with the alternatives it beat;
 * {@code folds} lists the enum constant bodies folded into their enum in the selected sources (Phase 2BX).
 * {@link #stubBacked()} (Phase 2DF) says the selected candidate compiled only against inferred stubs for missing libraries.
 * {@code idioms} (Phase 2DH) lists the compiler idioms undone in the selected sources, read from the original bytecode.
 */
public record SelectionResult(String candidate, VerificationResult verification, Map<String,String> provenance, int attempts,
                              List<SourceRepair.Edit> repairs, List<MethodSplice.Splice> methods, List<Attempt> tried,
                              List<EnumBodyFolding.Fold> folds, List<IdiomResugar.Rewrite> idioms) {
    /** One verified attempt: candidate id, source file to producer, and its verification. */
    public record Attempt(String id, Map<String,String> origins, VerificationResult verification) {
        public Attempt { origins = Map.copyOf(origins); }
    }
    public SelectionResult {
        provenance = Map.copyOf(provenance); repairs = List.copyOf(repairs); methods = List.copyOf(methods); tried = List.copyOf(tried);
        folds = List.copyOf(folds); idioms = List.copyOf(idioms);
    }
    public SelectionResult(String candidate, VerificationResult verification, Map<String,String> provenance, int attempts,
                           List<SourceRepair.Edit> repairs, List<MethodSplice.Splice> methods, List<Attempt> tried,
                           List<EnumBodyFolding.Fold> folds) {
        this(candidate, verification, provenance, attempts, repairs, methods, tried, folds, List.of());
    }
    /** Phase 2DF: the selected candidate compiled against inferred stubs (Phase 2CT), not the real libraries. */
    public boolean stubBacked() { return verification.stubBacked(); }
    /** Phase 2DF: this selection with its verification marked as stub-backed or not. */
    public SelectionResult withStubBacked(boolean value) {
        return new SelectionResult(candidate, verification.withStubBacked(value), provenance, attempts, repairs, methods, tried, folds, idioms);
    }
    public SelectionResult(String candidate, VerificationResult verification, Map<String,String> provenance, int attempts,
                           List<SourceRepair.Edit> repairs, List<MethodSplice.Splice> methods, List<Attempt> tried) {
        this(candidate, verification, provenance, attempts, repairs, methods, tried, List.of());
    }
    public SelectionResult(String candidate, VerificationResult verification, Map<String,String> provenance, int attempts,
                           List<SourceRepair.Edit> repairs) {
        this(candidate, verification, provenance, attempts, repairs, List.of(), List.of());
    }
    public SelectionResult(String candidate, VerificationResult verification, Map<String,String> provenance, int attempts) {
        this(candidate, verification, provenance, attempts, List.of());
    }
}
