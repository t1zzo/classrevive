package dev.classrevive.pipeline;

import java.util.*;

/**
 * One captured failure (Phase 2AT failure lab). {@link #signature()} is the stable grouping key: category, producer and
 * a detail that names no target constant, member, type or path (a round-trip reason with the first diverging opcodes,
 * a javac diagnostic code, an engine stub marker, or a worker outcome code with a JDK/engine exception type). The engine
 * version is recorded but kept out of the key, so the same defect groups across engine upgrades. Everything that does
 * name the target (class, method, javac message, source excerpt) stays inside the case's own directory.
 *
 * @param engine      producer of the failing source: an engine id, an engine id with {@code +repair}, or {@code javac}
 * @param candidate   selection attempt the failure was seen in ({@code cfr}, {@code hybrid-2}, {@code repair-1}, ...)
 * @param className   focus class (internal name); null when the failure is not tied to one class
 * @param method      focus method as {@code name + descriptor}; null unless the category is METHOD_DIFFERENT
 * @param alternatives level of the focus method in the other engine candidates of the same job (METHOD_DIFFERENT only)
 * @param context     path, relative to the case directory, of the job's class JAR used as compile-only context
 */
public record FailureCase(String id, String category, String engine, String detail, String engineVersion, String variant,
                          String sample, String candidate, boolean selected, boolean sourceRepair, String className,
                          String classSha256, String method, String sourceFile, String reason, String excerpt,
                          Map<String,String> alternatives, String inputSha256, String context) {
    public static final String METHOD_DIFFERENT = "METHOD_DIFFERENT", COMPILE_FAILED = "COMPILE_FAILED",
        PLACEHOLDER = "PLACEHOLDER", WORKER_FAILED = "WORKER_FAILED";
    /** Per-method levels, worst first; a regression case fails when a method drops to an earlier level. */
    public static final List<String> LEVELS = List.of("NOT_RECOMPILED", "DIFFERENT", "EQUIVALENT_AFTER_NORMALIZATION", "IDENTICAL");

    public FailureCase {
        if (!Set.of(METHOD_DIFFERENT, COMPILE_FAILED, PLACEHOLDER, WORKER_FAILED).contains(category)) throw new IllegalArgumentException("Unknown failure category");
        alternatives = Collections.unmodifiableMap(new TreeMap<>(alternatives));
    }

    public String signature() { return category + ":" + engine + ":" + detail; }

    /** Engine candidates of the same job that reached a recovered level (identical or equivalent) for the focus method. */
    public List<String> recoverableBy() {
        var result = new ArrayList<String>();
        alternatives.forEach((id, level) -> { if (level(level) >= level("EQUIVALENT_AFTER_NORMALIZATION")) result.add(id); });
        return List.copyOf(result);
    }

    public static int level(String name) {
        int index = LEVELS.indexOf(name);
        if (index < 0) throw new IllegalArgumentException("Unknown level: " + name);
        return index;
    }

    FailureCase withId(String value) {
        return new FailureCase(value, category, engine, detail, engineVersion, variant, sample, candidate, selected, sourceRepair,
            className, classSha256, method, sourceFile, reason, excerpt, alternatives, inputSha256, context);
    }

    FailureCase withContext(String value) {
        return new FailureCase(id, category, engine, detail, engineVersion, variant, sample, candidate, selected, sourceRepair,
            className, classSha256, method, sourceFile, reason, excerpt, alternatives, inputSha256, value);
    }

    /** Evidence of a later reproduction: its excerpt, engine version and the levels other engines reached there. */
    FailureCase withEvidence(String newExcerpt, String version, Map<String,String> newAlternatives) {
        return new FailureCase(id, category, engine, detail, version, variant, sample, candidate, selected, sourceRepair,
            className, classSha256, method, sourceFile, reason, newExcerpt, newAlternatives, inputSha256, context);
    }

    FailureCase withClassHash(String value) {
        return new FailureCase(id, category, engine, detail, engineVersion, variant, sample, candidate, selected, sourceRepair,
            className, value, method, sourceFile, reason, excerpt, alternatives, inputSha256, context);
    }

    Map<String,Object> toJson() {
        var data = new TreeMap<String,Object>();
        data.put("schema_version", 1); data.put("kind", "failure-case"); data.put("id", id);
        data.put("signature", signature()); data.put("category", category); data.put("engine", engine); data.put("detail", detail);
        data.put("engine_version", engineVersion); data.put("variant", variant); data.put("sample", sample);
        data.put("candidate", candidate); data.put("selected", selected); data.put("source_repair", sourceRepair);
        data.put("class_name", className); data.put("class_sha256", classSha256); data.put("method", method);
        data.put("source_file", sourceFile); data.put("reason", reason); data.put("excerpt", excerpt);
        data.put("alternatives", alternatives); data.put("recoverable_by", recoverableBy());
        data.put("input_sha256", inputSha256); data.put("context", context);
        data.put("behavior", "NOT_VERIFIED");
        return data;
    }

    static FailureCase fromJson(Object value) {
        if (!(value instanceof Map<?,?> data) || !"failure-case".equals(data.get("kind")) || !Long.valueOf(1).equals(data.get("schema_version")))
            throw new IllegalArgumentException("Not a failure case");
        var alternatives = new TreeMap<String,String>();
        if (data.get("alternatives") instanceof Map<?,?> map) map.forEach((key, level) -> alternatives.put(String.valueOf(key), String.valueOf(level)));
        return new FailureCase(text(data, "id"), text(data, "category"), text(data, "engine"), text(data, "detail"),
            text(data, "engine_version"), text(data, "variant"), text(data, "sample"), text(data, "candidate"),
            Boolean.TRUE.equals(data.get("selected")), Boolean.TRUE.equals(data.get("source_repair")), text(data, "class_name"),
            text(data, "class_sha256"), text(data, "method"), text(data, "source_file"), text(data, "reason"), text(data, "excerpt"),
            alternatives, text(data, "input_sha256"), text(data, "context"));
    }

    private static String text(Map<?,?> data, String key) {
        Object value = data.get(key);
        if (value != null && !(value instanceof String)) throw new IllegalArgumentException("Field " + key + " must be a string");
        return (String) value;
    }
}
