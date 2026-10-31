package dev.classrevive.workbench;

import dev.classrevive.bytecode.ObfuscationProfile;
import dev.classrevive.bytecode.ObfuscatorClassifier;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.zip.ZipFile;

/**
 * Phase G5: which obfuscator a jar or class file points to, with the Phase 2BZ classifier that {@code classrevive diagnose}
 * and every decompile report already use. ASM reads the class bytes; nothing is loaded or run. A suggestion, never proof.
 */
public final class ObfuscationCheck {
    /** One hypothesis: the obfuscator, its score 0..100, {@code LOW}/{@code MEDIUM}/{@code HIGH}, and the evidence behind it. */
    public record Hypothesis(String obfuscator, int score, String confidence, List<String> reasons) {
        public Hypothesis { reasons = List.copyOf(reasons); }
    }

    /** One technique found: its label and how many classes show it. */
    public record Technique(String label, int classes) {}

    /**
     * {@code verdict}: {@code SUGGESTED}, {@code NONE} (no technique) or {@code UNKNOWN} (techniques, but no rule matched);
     * {@code summary}: the one-line text of the reports.
     */
    public record Detection(String verdict, String summary, List<Hypothesis> hypotheses, List<Technique> techniques, int classes) {
        public Detection { hypotheses = List.copyOf(hypotheses); techniques = List.copyOf(techniques); }

        /** The strongest hypothesis, or null. */
        public Hypothesis top() { return hypotheses.isEmpty() ? null : hypotheses.getFirst(); }

        /** Skidfuscator is the strongest hypothesis with at least medium confidence: the reversal is worth offering. */
        public boolean skidfuscator() {
            var top = top();
            return top != null && top.obfuscator().equals(SKIDFUSCATOR) && !top.confidence().equals("LOW");
        }
    }

    public static final String SKIDFUSCATOR = "Skidfuscator";
    static final int MAX_ENTRIES = 100_000, MAX_CLASS_BYTES = 16 * 1024 * 1024;

    private ObfuscationCheck() {}

    /** Classifies a {@code .jar} (classes and resource names) or a single {@code .class}. */
    public static Detection analyze(Path file) throws IOException {
        var builder = new ObfuscationProfile.Builder();
        if (file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".class")) {
            byte[] bytes = Files.readAllBytes(file);
            if (bytes.length <= MAX_CLASS_BYTES) builder.addClass(bytes);
        } else try (var zip = new ZipFile(file.toFile())) {
            var entries = zip.entries();
            for (int visited = 0; entries.hasMoreElements() && visited < MAX_ENTRIES; visited++) {
                var entry = entries.nextElement();
                if (entry.isDirectory()) continue;
                if (!entry.getName().toLowerCase(Locale.ROOT).endsWith(".class")) { builder.addResource(entry.getName(), entry.getSize()); continue; }
                if (entry.getSize() > MAX_CLASS_BYTES) continue;
                try (var in = zip.getInputStream(entry)) {
                    byte[] bytes = in.readNBytes(MAX_CLASS_BYTES + 1);
                    if (bytes.length <= MAX_CLASS_BYTES) builder.addClass(bytes);
                }
            }
        }
        var profile = builder.build();
        var result = ObfuscatorClassifier.classify(profile);
        var techniques = new ArrayList<Technique>();
        profile.techniques().forEach((technique, count) -> techniques.add(new Technique(technique.label(), count.classes())));
        techniques.sort(Comparator.comparingInt(Technique::classes).reversed().thenComparing(Technique::label));
        var hypotheses = result.hypotheses().stream().map(h -> new Hypothesis(h.obfuscator(), h.score(), h.confidence(), h.reasons())).toList();
        return new Detection(result.verdict(), result.summary(), hypotheses, techniques, profile.classes());
    }
}
