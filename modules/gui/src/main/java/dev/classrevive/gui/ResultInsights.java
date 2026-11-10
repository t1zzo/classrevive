package dev.classrevive.gui;

import dev.classrevive.workbench.ResultIndex;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Phase G7: what the reports of a finished decompile say about deobfuscation, sorted for a reader: what was identified,
 * what was reverted, what was not recovered, and what deserves a warning. Pure reading of {@link ResultIndex}; no
 * deobfuscation logic lives here, and nothing is computed that the engine did not already record.
 */
final class ResultInsights {
    enum Severity { ERROR, WARNING, INFO }

    /** A message with what to do about it ({@code hint} may be empty). */
    record Notice(Severity severity, String text, String hint) {}

    /** A labelled count the reversal recorded. */
    record Count(String label, int value) {}

    private static final Pattern NOT_RECOVERED = Pattern.compile(
        "\\bnot (decrypted|resolved|recovered|matched)\\b|\\brefused\\b|\\bambiguous\\b|\\bwrite failures?\\b|\\(left alone\\)");
    /** JVM verifier errors that only say a library is missing from the input: the check is inconclusive, not a rejection. */
    private static final Set<String> MISSING_CLASS = Set.of("NoClassDefFoundError", "ClassNotFoundException");

    private final ResultIndex index;
    private final ResultIndex.Reversal reversal;
    private final ResultIndex.ReportedObfuscation found;

    /** What the job's own report says. */
    ResultInsights(ResultIndex index) { this(index, index.reversal(), index.obfuscation()); }

    /** With the reversal and detection taken from elsewhere (the reversal the window ran before decompiling). */
    ResultInsights(ResultIndex index, ResultIndex.Reversal reversal, ResultIndex.ReportedObfuscation found) {
        this.index = index; this.reversal = reversal; this.found = found;
    }

    /** The desktop's own detection (before decompiling) in the shape of the report; occurrences are not counted there (0). */
    static ResultIndex.ReportedObfuscation reported(dev.classrevive.workbench.ObfuscationCheck.Detection detection) {
        return new ResultIndex.ReportedObfuscation(detection.verdict(), detection.summary(), detection.classes(),
            detection.techniques().stream().map(t -> new ResultIndex.Technique(t.label(), t.label(), t.classes(), 0)).toList(),
            detection.hypotheses().stream().map(h -> new ResultIndex.Hypothesis(h.obfuscator(), h.score(), h.confidence(), h.reasons())).toList());
    }

    ResultIndex.Reversal reversal() { return reversal; }
    ResultIndex.ReportedObfuscation obfuscation() { return found; }

    /** True when there is something to show: techniques were found or a reversal was considered. */
    boolean hasDeobfuscation() { return reversal != null || (found != null && !found.techniques().isEmpty()); }

    /** Whether a recorded count says something was left undone ("strings not decrypted", "methods refused"…). */
    static boolean isNotRecovered(String label) { return NOT_RECOVERED.matcher(label.toLowerCase(Locale.ROOT)).find(); }

    /** What the pass did: every non-zero count that is not a shortfall, biggest first. */
    List<Count> reverted() {
        return reversal == null ? List.of() : counts(reversal, false);
    }

    /** What the pass left: every non-zero count that says "not decrypted", "refused", "ambiguous"… */
    List<Count> notRecovered() {
        return reversal == null ? List.of() : counts(reversal, true);
    }

    private static List<Count> counts(ResultIndex.Reversal reversal, boolean shortfalls) {
        var list = new ArrayList<Count>();
        reversal.counts().forEach((label, value) -> { if (value > 0 && isNotRecovered(label) == shortfalls) list.add(new Count(label, value)); });
        list.sort(Comparator.comparingInt(Count::value).reversed());
        return list;
    }

    /** Share of the original's methods whose signature survived the reversal, or -1 without the comparison. */
    double methodsKept() {
        var signatures = reversal == null ? null : reversal.signatures();
        return signatures == null || signatures.originalMethods() == 0 ? -1 : signatures.keptMethods() / (double) signatures.originalMethods();
    }

    /** Errors first, then warnings, then information; each says what to do when there is something to do. */
    List<Notice> notices() {
        var list = new ArrayList<Notice>();
        var summary = index.summary();
        if (!summary.compilation().equals("COMPILED"))
            list.add(new Notice(Severity.ERROR, Text.t("notice.compile", summary.errors()), Text.t("notice.compile.hint")));
        if (reversal != null) {
            switch (reversal.status()) {
                case "FAILED" -> list.add(new Notice(Severity.ERROR, Text.t("notice.reversal.failed", reason(reversal)), Text.t("notice.reversal.original")));
                case "REFUSED" -> list.add(new Notice(Severity.WARNING, Text.t("notice.reversal.refused", reason(reversal)), Text.t("notice.reversal.original")));
                case "OFF" -> list.add(new Notice(Severity.INFO, Text.t("notice.reversal.off", reversal.obfuscator()), Text.t("notice.reversal.offHint", reversal.pass())));
                default -> { }
            }
            var link = reversal.link();
            if (reversal.applied() && link != null && !link.linked()) {
                String where = link.failedClass() == null ? "—" : link.failedClass().replace('/', '.');
                if (link.errorType() != null && MISSING_CLASS.contains(link.errorType()))
                    list.add(new Notice(Severity.WARNING, Text.t("notice.link.inconclusive", where), Text.t("notice.link.inconclusive.hint")));
                else
                    list.add(new Notice(Severity.ERROR, Text.t("notice.link.rejected", String.valueOf(link.errorType()), where), Text.t("notice.link.rejected.hint")));
            }
            int shortfall = notRecovered().stream().mapToInt(Count::value).sum();
            if (reversal.applied() && shortfall > 0)
                list.add(new Notice(Severity.WARNING, Text.t("notice.unrecovered", shortfall), Text.t("notice.unrecovered.hint")));
        }
        for (var engine : index.engines())
            if (!engine.status().equals("OUTPUT_PRODUCED"))
                list.add(new Notice(Severity.WARNING, Text.t("notice.engine", engine.id(), Text.t("engine." + engine.status(), engine.status())), Text.t("notice.engine.hint")));
        if (!index.notices().isEmpty())
            list.add(new Notice(Severity.INFO, Text.t("notice.intake", index.notices().size()), Text.t("notice.intake.hint")));
        list.sort(Comparator.comparing(Notice::severity));
        return list;
    }

    private static String reason(ResultIndex.Reversal reversal) { return reversal.reason() == null ? "—" : reversal.reason(); }
}
