package dev.classrevive.pipeline;

import dev.classrevive.selection.SelectionResult;
import dev.classrevive.verification.PartialRoundTrip;
import dev.classrevive.verification.VerificationResult;
import java.util.List;

/**
 * Phase 2DP: the rule that keeps an optional engine's sources only when they make the result no worse. Phase 2DO measured
 * JD-Core as a fifth engine: it raised the recovered method bodies of some jars and lowered those of others (a different
 * hybrid path in the selection). The job therefore selects with and without such an engine, and keeps the result with it
 * only when that result is at least as good on every measure below and better on at least one. Nothing here is tuned to a
 * sample: it compares the job's own two results.
 *
 * <p>Measures, each compared as "at least as good": the result is complete (compiled, every signature matched); it
 * compiled as a whole; matched structural signatures; classes that compiled on their own (Phase 2BP; every class when the
 * result compiled as a whole); and method bodies identical or equivalent to the original (the whole result's round trip
 * when it compiled, the per-file round trip otherwise).
 */
final class NeverWorse {
    static final String DIRECTORY = "guard", WITH = "with", WITHOUT = "without", FILE = "guard.txt";

    private NeverWorse() {}

    record Score(boolean complete, boolean compiled, int signatures, int classes, int bodies) {
        int[] values() { return new int[] {complete ? 1 : 0, compiled ? 1 : 0, signatures, classes, bodies}; }
        String text() {
            return (complete ? "complete" : compiled ? "compiled" : "not compiled") + ", signatures " + signatures
                + ", classes compiling on their own " + (classes == Integer.MAX_VALUE ? "all" : Integer.toString(classes))
                + ", bodies identical or equivalent " + bodies;
        }
    }

    record Decision(boolean keptWith, String text) {}

    static Score score(SelectionResult selection, PartialRoundTrip.Result perFile) {
        var verification = selection.verification();
        boolean compiled = verification.status() == VerificationResult.Status.COMPILED;
        int bodies = compiled ? verification.roundTripIdentical() + verification.roundTripEquivalent()
            : perFile == null ? 0 : perFile.identical() + perFile.equivalent();
        int classes = compiled ? Integer.MAX_VALUE : perFile == null ? 0 : perFile.classes().size();
        return new Score(verification.complete(), compiled, verification.matchedSymbols(), classes, bodies);
    }

    /** True when {@code with} is at least as good as {@code without} on every measure and better on one. */
    static boolean keeps(Score with, Score without) {
        int[] a = with.values(), b = without.values();
        boolean better = false;
        for (int i = 0; i < a.length; i++) {
            if (a[i] < b[i]) return false;
            if (a[i] > b[i]) better = true;
        }
        return better;
    }

    static Decision decide(List<String> engines, Score with, Score without) {
        String names = String.join(", ", engines);
        boolean kept = keeps(with, without);
        String text = "Never-worse guard (Phase 2DP): the result with " + names + " was " + (kept ? "kept" : "left out")
            + (kept ? ": at least as good on every measure and better on one." : ": it is not at least as good on every measure and better on one.")
            + "\n  with " + names + ": " + with.text()
            + "\n  without " + names + ": " + without.text()
            + "\n  The other run stays in " + DIRECTORY + "/" + (kept ? WITHOUT : WITH) + "/.\n";
        return new Decision(kept, text);
    }
}
