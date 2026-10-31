package dev.classrevive.pipeline;

import dev.classrevive.bytecode.ObfuscationProfile;
import dev.classrevive.bytecode.ObfuscatorClassifier;
import java.util.*;

/** Phase 2BZ: readable and JSON forms of a static obfuscation profile and its obfuscator hypotheses. */
public final class ObfuscationSummary {
    public static final String INTERPRETATION = "Static patterns only; nothing was executed or decrypted. The suggested obfuscator is a hypothesis "
        + "from documented rules (docs/obfuscation-detection.md), never proof: obfuscators share techniques and a jar can be processed by several tools.";

    private ObfuscationSummary() {}

    /** One line, e.g. {@code string encryption: 107 classes; hidden calls ...: 1514 sites in 103 classes; suggested obfuscator: ...}. */
    public static String line(ObfuscationProfile profile, ObfuscatorClassifier.Result result) {
        var parts = new ArrayList<String>();
        for (var entry : profile.techniques().entrySet()) parts.add(entry.getKey().label() + ": " + count(profile, entry.getKey(), entry.getValue()));
        if (parts.isEmpty()) parts.add("no obfuscation technique detected");
        parts.add("suggested obfuscator: " + result.summary());
        return String.join("; ", parts);
    }

    /** Several lines with the counts, the hypotheses and their reasons. */
    public static String text(ObfuscationProfile profile, ObfuscatorClassifier.Result result) {
        var out = new StringBuilder("Obfuscation (static, " + profile.classes() + " classes"
            + (profile.unparsable() > 0 ? ", " + profile.unparsable() + " unparsable" : "") + "):\n");
        if (profile.techniques().isEmpty()) out.append("  no obfuscation technique detected\n");
        for (var entry : profile.techniques().entrySet())
            out.append("  ").append(entry.getKey().label()).append(": ").append(count(profile, entry.getKey(), entry.getValue())).append('\n');
        out.append("Suggested obfuscator: ").append(result.summary()).append('\n');
        for (var hypothesis : result.hypotheses()) {
            out.append("  ").append(hypothesis.obfuscator()).append(" (").append(hypothesis.confidence()).append(", ")
                .append(hypothesis.score()).append("/100):\n");
            for (String reason : hypothesis.reasons()) out.append("    - ").append(reason).append('\n');
        }
        out.append("  (suggestion from static patterns, not proof)\n");
        return out.toString();
    }

    private static String count(ObfuscationProfile profile, ObfuscationProfile.Technique technique, ObfuscationProfile.Count count) {
        return switch (technique) {
            case HIDDEN_CALLS -> count.occurrences() + " sites in " + count.classes() + " classes";
            case STRING_ENCRYPTION -> count.classes() + " classes, " + count.occurrences() + " decryption sites (" + profile.metric("strings.indy_sites")
                + " invokedynamic, " + profile.metric("strings.decryptor_calls") + " direct calls, " + (profile.metric("strings.clinit_crypto")
                + profile.metric("strings.clinit_xor")) + " <clinit>)" + (profile.metric("strings.native_key_classes") > 0
                ? "; keys supplied by the native layer in " + profile.metric("strings.native_key_classes") + " classes" : "");
            case CONSTANT_ENCRYPTION -> count.occurrences() + " sites in " + count.classes() + " classes (" + profile.metric("constants.indy_int_sites")
                + " int, " + profile.metric("constants.indy_long_sites") + " long)";
            case CASE_COLLISIONS -> count.occurrences() + " groups, " + count.classes() + " classes";
            case NATIVE_LAYER -> count.occurrences() + " native methods/libraries in " + count.classes() + " classes";
            case EXCEPTION_OBFUSCATION -> count.occurrences() + " handlers in " + count.classes() + " classes";
            default -> count.classes() + " classes, " + count.occurrences() + " occurrences";
        };
    }

    public static Map<String,Object> json(ObfuscationProfile profile, ObfuscatorClassifier.Result result) {
        var data = new TreeMap<String,Object>();
        data.put("classes", profile.classes()); data.put("unparsable_classes", profile.unparsable());
        var techniques = new TreeMap<String,Object>();
        profile.techniques().forEach((technique, count) -> {
            var row = new TreeMap<String,Object>(); row.put("label", technique.label()); row.put("classes", count.classes());
            row.put("occurrences", count.occurrences()); row.put("samples", count.samples());
            techniques.put(technique.name(), row);
        });
        data.put("techniques", techniques);
        data.put("indicators", new TreeMap<String,Object>(profile.metrics()));
        data.put("verdict", result.verdict());
        data.put("suggested_obfuscator", result.summary());
        data.put("hypotheses", result.hypotheses().stream().map(h -> {
            var row = new TreeMap<String,Object>(); row.put("obfuscator", h.obfuscator()); row.put("score", h.score());
            row.put("confidence", h.confidence()); row.put("reasons", h.reasons()); return (Object) row; }).toList());
        data.put("summary", line(profile, result));
        data.put("interpretation", INTERPRETATION);
        return data;
    }
}
