package dev.classrevive.workbench;

import dev.classrevive.pipeline.Json;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * Phase G2: a finished decompile job read back from its output folder, for the desktop GUI. Everything comes from
 * {@code report.json} (levels from {@code class_confidence}, the rows of {@code report.html}) and the source folders the
 * job wrote; nothing is recomputed, nothing from the input is loaded or run. Folders written before Phase G2 have no
 * {@code class_confidence}: {@link #levelsAvailable()} is then false and every level is null.
 */
public final class ResultIndex {
    /** The confidence levels of {@code report.html}, strongest evidence first. */
    public enum Level { EXACT, CLOSE, PER_FILE, PARTIAL, UNVERIFIED }

    /** One class: its source file (relative, in every candidate), origin in the selected sources, and recompilation evidence. */
    public record ClassEntry(String className, String file, String origin, int matchedSymbols, int expectedSymbols, int identical,
                             int equivalent, int different, List<String> differentMethods, Level level, Map<String,Outcome> methods) {
        public ClassEntry {
            differentMethods = List.copyOf(differentMethods);
            methods = Collections.unmodifiableMap(new LinkedHashMap<>(methods));
        }
    }

    /** Phase G4: one method's round trip, keyed in {@link ClassEntry#methods()} by {@code name + descriptor}. */
    public enum Outcome { IDENTICAL, EQUIVALENT_AFTER_NORMALIZATION, DIFFERENT }

    /** Phase G4: a javac error of the selected sources; {@code file} is relative to their folder, positions 1-based or -1. */
    public record Diagnostic(String file, long line, long column, String code, String message) {}

    /** Phase 2CJ, as the report states it: the main language, javac or ecj, a javac version lower bound (0 when unknown). */
    public record Compiler(String language, String javaCompiler, int javacAtLeast, int classes) {}

    /** One engine run of the job. */
    public record EngineEntry(String id, String version, String status, long sourceFiles) {}

    /** Whole-job verification as {@code report.json} states it; behavior is always {@code NOT_VERIFIED}. */
    public record Summary(String inputSha256, String compilation, int errors, int matchedSymbols, int expectedSymbols,
                          boolean structurallyComplete, String selectedCandidate, String behavior) {}

    /** Phase G7: one obfuscation technique the static scan found, as {@code report.json} states it. */
    public record Technique(String id, String label, int classes, int occurrences) {}

    /** Phase G7: one obfuscator hypothesis with its 0-100 score and the reasons behind it. */
    public record Hypothesis(String obfuscator, int score, String confidence, List<String> reasons) {
        public Hypothesis { reasons = List.copyOf(reasons); }
    }

    /** Phase G7: the {@code obfuscation} section of the report: verdict, techniques and hypotheses (never proof). */
    public record ReportedObfuscation(String verdict, String suggested, int classes, List<Technique> techniques, List<Hypothesis> hypotheses) {
        public ReportedObfuscation { techniques = List.copyOf(techniques); hypotheses = List.copyOf(hypotheses); }
        /** The strongest hypothesis, or null. */
        public Hypothesis top() { return hypotheses.isEmpty() ? null : hypotheses.getFirst(); }
    }

    /** Phase G7: the JVM verifier check of the reversed classes; {@code errorType} and {@code failedClass} are null when they linked. */
    public record LinkCheck(boolean linked, int classes, String errorType, String failedClass) {}

    /** Phase G7: how much of the original's shape the reversal kept ({@code original_signatures}). */
    public record Signatures(int originalClasses, int reversedClasses, List<String> removedClasses, int originalMethods, int keptMethods,
                             int originalFields, int keptFields) {
        public Signatures { removedClasses = List.copyOf(removedClasses); }
    }

    /**
     * Phase G7: the {@code deobfuscation} section of the report: which pass ran, its outcome ({@code APPLIED}, {@code REFUSED},
     * {@code FAILED} or {@code OFF}), the labelled counts the pass recorded and, when applied, what the reversal kept.
     */
    public record Reversal(String obfuscator, int score, String pass, String status, String reason, String summary,
                           Map<String,Integer> counts, int methodsRewritten, LinkCheck link, Signatures signatures) {
        public Reversal { counts = Collections.unmodifiableMap(new LinkedHashMap<>(counts)); }
        public boolean applied() { return status.equals("APPLIED"); }
    }

    /** Phase G7: an intake notice ({@code inventory_notices}): a case collision or a resource left out. */
    public record Notice(String code, String entry, String message) {}

    /** The candidate holding the selected sources. */
    public static final String SELECTION = "selection";
    /** {@code report.json} larger than this is refused, matching the strict JSON reader's own limit. */
    static final long MAX_REPORT_BYTES = 16L * 1024 * 1024;

    private final Path output;
    private final Summary summary;
    private final SortedMap<String,ClassEntry> classes;
    private final List<EngineEntry> engines;
    private final Map<String,String> originalNames;
    private final boolean levelsAvailable;
    private final Compiler compiler;
    private final ReportedObfuscation obfuscation;
    private final Reversal reversal;
    private final List<Notice> notices;
    private volatile List<Diagnostic> diagnostics;
    private volatile Map<String,Integer> editedClasses;

    private ResultIndex(Path output, Summary summary, SortedMap<String,ClassEntry> classes, List<EngineEntry> engines,
                        Map<String,String> originalNames, boolean levelsAvailable, Compiler compiler,
                        ReportedObfuscation obfuscation, Reversal reversal, List<Notice> notices) {
        this.obfuscation = obfuscation; this.reversal = reversal; this.notices = notices;
        this.output = output; this.summary = summary; this.classes = classes; this.engines = engines;
        this.originalNames = originalNames; this.levelsAvailable = levelsAvailable; this.compiler = compiler;
    }

    /** Reads {@code output/report.json}; fails with an {@link IOException} when it is missing, too large or not a decompile report. */
    public static ResultIndex open(Path output) throws IOException {
        output = output.toAbsolutePath().normalize();
        Path report = output.resolve("report.json");
        if (!Files.isRegularFile(report, LinkOption.NOFOLLOW_LINKS)) throw new IOException("No report.json in " + output);
        if (Files.size(report) > MAX_REPORT_BYTES) throw new IOException("report.json exceeds " + (MAX_REPORT_BYTES >> 20) + " MiB");
        Map<String,Object> root;
        try {
            root = map(Json.decode(Files.readString(report, StandardCharsets.UTF_8)), "report");
            if (!"decompilation".equals(root.get("kind"))) throw new IllegalArgumentException("kind is not decompilation");
            var verification = map(root.get("verification"), "verification");
            var summary = new Summary(string(root, "input_sha256"), string(verification, "status"), integer(verification, "errors"),
                integer(verification, "matched_symbols"), integer(verification, "expected_symbols"),
                Boolean.TRUE.equals(verification.get("structurally_complete")), string(root, "selected_candidate"), string(root, "behavior"));
            var engines = new ArrayList<EngineEntry>();
            for (Object item : list(root.get("engines"), "engines")) {
                var engine = map(item, "engine");
                engines.add(new EngineEntry(string(engine, "id"), string(engine, "version"), string(engine, "status"), integer(engine, "source_files")));
            }
            var classes = new TreeMap<String,ClassEntry>();
            boolean levels = root.get("class_confidence") != null;
            if (levels) {
                for (Object item : list(root.get("class_confidence"), "class_confidence")) {
                    var row = map(item, "class confidence");
                    var methods = new ArrayList<String>();
                    for (Object method : list(row.get("different_methods"), "different_methods")) methods.add((String) method);
                    var outcomes = new LinkedHashMap<String,Outcome>();
                    if (row.get("methods") != null) for (Object measured : list(row.get("methods"), "methods")) {
                        var method = map(measured, "method");
                        outcomes.put(string(method, "method"), Outcome.valueOf(string(method, "outcome")));
                    }
                    var entry = new ClassEntry(string(row, "class_name"), string(row, "file"), string(row, "origin"),
                        integer(row, "matched_symbols"), integer(row, "expected_symbols"), integer(row, "identical"),
                        integer(row, "equivalent_after_normalization"), integer(row, "different"), methods, Level.valueOf(string(row, "level")), outcomes);
                    classes.put(entry.className(), entry);
                }
            } else {
                // Before Phase G2: structural coverage only; the selected file follows the top-level class name.
                var provenance = root.get("provenance") == null ? Map.<String,Object>of() : map(root.get("provenance"), "provenance");
                for (Object item : list(verification.get("class_coverage"), "class_coverage")) {
                    var row = map(item, "class coverage");
                    String name = string(row, "class_name"), file = topLevel(name) + ".java";
                    classes.put(name, new ClassEntry(name, file, String.valueOf(provenance.getOrDefault(file, "none")),
                        integer(row, "matched_symbols"), integer(row, "expected_symbols"), 0, 0, 0, List.of(), null, Map.of()));
                }
            }
            var originals = new TreeMap<String,String>();
            if (root.get("portable_names") != null) {
                var renamed = map(map(root.get("portable_names"), "portable_names").get("renamed_classes"), "renamed_classes");
                renamed.forEach((original, portable) -> originals.put(String.valueOf(portable), original));
            }
            Compiler compiler = null;
            if (root.get("compiler") instanceof Map<?,?>) {
                var found = map(root.get("compiler"), "compiler");
                compiler = new Compiler(string(found, "language"), string(found, "java_compiler"),
                    found.get("javac_at_least") == null ? 0 : integer(found, "javac_at_least"), integer(found, "classes"));
            }
            return new ResultIndex(output, summary, Collections.unmodifiableSortedMap(classes), List.copyOf(engines),
                Collections.unmodifiableMap(originals), levels, compiler,
                obfuscation(root.get("obfuscation")), reversal(root.get("deobfuscation")), notices(root.get("inventory_notices")));
        } catch (IllegalArgumentException | ClassCastException | NullPointerException malformed) {
            throw new IOException("Not a readable ClassRevive report: " + malformed.getMessage(), malformed);
        }
    }

    public Path output() { return output; }
    /** Phase G7: what the static scan found, or null when the report has no {@code obfuscation} section. */
    public ReportedObfuscation obfuscation() { return obfuscation; }
    /** Phase G7: the automatic reversal's outcome, or null when no reversal was considered. */
    public Reversal reversal() { return reversal; }
    /** Phase G7: intake notices of the job. */
    public List<Notice> notices() { return notices; }

    /**
     * Phase G7: classes the reversal edited, with the number of edits, from {@code deobfuscation/transform.json} (the pass
     * audit). Empty when there was no reversal, the file is missing or too large, or the pass records counts only.
     */
    public Map<String,Integer> editedClasses() {
        var cached = editedClasses;
        if (cached != null) return cached;
        var found = new TreeMap<String,Integer>();
        try {
            Path file = output.resolve("deobfuscation").resolve("transform.json");
            if (reversal != null && Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) && Files.size(file) <= MAX_REPORT_BYTES) {
                var root = map(Json.decode(Files.readString(file, StandardCharsets.UTF_8)), "transform");
                if (root.get("changes") instanceof List<?> changes)
                    for (Object change : changes) if (change instanceof Map<?,?> row && row.get("class") instanceof String name) found.merge(name, 1, Integer::sum);
            }
        } catch (IOException | RuntimeException unreadable) {
            found.clear();
        }
        cached = Collections.unmodifiableMap(found);
        editedClasses = cached;
        return cached;
    }
    /** The language and compiler found in the bytecode, or null when the report predates Phase 2CJ. */
    public Compiler compiler() { return compiler; }

    /**
     * Phase G4: the selected sources' javac errors ({@code selection/diagnostics.tsv}); empty when they compiled, for
     * folders written before G4, or when the file is not readable.
     */
    public List<Diagnostic> diagnostics() {
        var cached = diagnostics;
        if (cached != null) return cached;
        var list = new ArrayList<Diagnostic>();
        try {
            Path file = output.resolve(SELECTION).resolve(dev.classrevive.verification.SelectedDiagnostics.FILE);
            for (var diagnostic : dev.classrevive.verification.SelectedDiagnostics.read(file))
                list.add(new Diagnostic(diagnostic.file(), diagnostic.line(), diagnostic.column(), diagnostic.code(), diagnostic.message()));
        } catch (IOException | RuntimeException unreadable) {
            list.clear();
        }
        cached = List.copyOf(list);
        diagnostics = cached;
        return cached;
    }

    /** The javac errors of one selected source file ({@code a/b/C.java}). */
    public List<Diagnostic> diagnostics(String file) {
        return diagnostics().stream().filter(diagnostic -> diagnostic.file().equals(file)).toList();
    }
    public Summary summary() { return summary; }
    /** Every class of the job by internal name ({@code a/b/C$D}), sorted. */
    public SortedMap<String,ClassEntry> classes() { return classes; }
    public List<EngineEntry> engines() { return engines; }
    /** Whether the levels came from the report; false for folders written before Phase G2. */
    public boolean levelsAvailable() { return levelsAvailable; }

    /** The input's own name for a class renamed to a portable name (Phase 2BV), or the name itself. */
    public String originalName(String className) { return originalNames.getOrDefault(className, className); }

    /** {@link #SELECTION} when the job wrote selected sources, then each engine with a source folder, in report order. */
    public List<String> candidates() {
        var candidates = new ArrayList<String>();
        if (Files.isDirectory(sources(SELECTION), LinkOption.NOFOLLOW_LINKS)) candidates.add(SELECTION);
        for (var engine : engines) if (Files.isDirectory(sources(engine.id()), LinkOption.NOFOLLOW_LINKS)) candidates.add(engine.id());
        return List.copyOf(candidates);
    }

    /**
     * The file holding {@code className} in one candidate's sources. An engine may write a nested class as its own
     * {@code B$C.java} (when the input lost its InnerClasses attribute) or inside {@code B.java}; the first that exists wins.
     * Only regular files inside that candidate's folder are returned.
     */
    public Optional<Path> source(String className, String candidate) {
        if (!classes.containsKey(className)) return Optional.empty();
        Path root = sources(candidate).normalize();
        for (String file : List.of(className + ".java", topLevel(className) + ".java")) {
            Path path = root.resolve(file).normalize();
            if (path.startsWith(root) && Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) return Optional.of(path);
        }
        return Optional.empty();
    }

    /** Classes per level, every level present (zero when none). Empty when the levels are unavailable. */
    public Map<Level,Integer> levelCounts() {
        if (!levelsAvailable) return Map.of();
        var counts = new EnumMap<Level,Integer>(Level.class);
        for (var level : Level.values()) counts.put(level, 0);
        for (var entry : classes.values()) counts.merge(entry.level(), 1, Integer::sum);
        return Collections.unmodifiableMap(counts);
    }

    /**
     * Phase G7: the reversal that the desktop ran as its own step before decompiling ({@link SkidReversal}), read from that
     * folder's {@code transform.json}: the same fields the job's own {@code deobfuscation} section carries, except what it
     * compares with the original. Null when the file is missing, too large or not a transform report.
     */
    public static Reversal reversalOf(Path folder, String obfuscator, int score) {
        try {
            Path file = folder.resolve("transform.json");
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > MAX_REPORT_BYTES) return null;
            var root = map(Json.decode(Files.readString(file, StandardCharsets.UTF_8)), "transform");
            if (!"static_transform".equals(root.get("kind"))) return null;
            var counts = new LinkedHashMap<String,Integer>();
            if (root.get("jar_passes") instanceof String text) {
                for (String line : text.split("\\R")) {
                    int colon = line.lastIndexOf(": ");
                    if (!line.startsWith("  ") || colon < 0) continue;
                    try { counts.put(line.substring(2, colon), Integer.parseInt(line.substring(colon + 2).trim())); }
                    catch (NumberFormatException notACount) { /* a list line */ }
                }
            }
            LinkCheck link = null;
            if (root.get("link_check") instanceof Map<?,?>) {
                var found = map(root.get("link_check"), "link_check");
                link = new LinkCheck(Boolean.TRUE.equals(found.get("linked")), found.get("classes") instanceof Long n ? n.intValue() : 0,
                    found.get("error_type") instanceof String type ? type : null, null);
            }
            boolean applied = "OUTPUT_VALIDATED".equals(root.get("status"));
            int rewritten = counts.getOrDefault("methods changed", root.get("changes") instanceof List<?> changes ? changes.size() : 0);
            return new Reversal(obfuscator, score, "skidfuscator", applied ? "APPLIED" : "FAILED",
                applied ? null : String.valueOf(root.getOrDefault("error", root.get("status"))), "", counts, rewritten, link, null);
        } catch (IOException | RuntimeException unreadable) {
            return null;
        }
    }

    // The sections below are evidence for the reader: a malformed one is left out rather than failing the whole report.
    private static ReportedObfuscation obfuscation(Object value) {
        try {
            if (!(value instanceof Map<?,?>)) return null;
            var data = map(value, "obfuscation");
            var techniques = new ArrayList<Technique>();
            if (data.get("techniques") instanceof Map<?,?> found) {
                for (var entry : found.entrySet()) {
                    var row = map(entry.getValue(), "technique");
                    techniques.add(new Technique(String.valueOf(entry.getKey()), string(row, "label"), integer(row, "classes"), integer(row, "occurrences")));
                }
            }
            var hypotheses = new ArrayList<Hypothesis>();
            for (Object item : list(data.get("hypotheses"), "hypotheses")) {
                var row = map(item, "hypothesis");
                var reasons = new ArrayList<String>();
                for (Object reason : list(row.get("reasons"), "reasons")) reasons.add(String.valueOf(reason));
                hypotheses.add(new Hypothesis(string(row, "obfuscator"), integer(row, "score"), string(row, "confidence"), reasons));
            }
            return new ReportedObfuscation(string(data, "verdict"), string(data, "suggested_obfuscator"), integer(data, "classes"), techniques, hypotheses);
        } catch (IllegalArgumentException | ClassCastException | NullPointerException malformed) {
            return null;
        }
    }

    private static Reversal reversal(Object value) {
        try {
            if (!(value instanceof Map<?,?>)) return null;
            var data = map(value, "deobfuscation");
            var counts = new LinkedHashMap<String,Integer>();
            if (data.get("pass_counts") instanceof Map<?,?> found)
                for (var entry : found.entrySet())
                    if (entry.getValue() instanceof Long number && number >= 0 && number <= Integer.MAX_VALUE) counts.put(String.valueOf(entry.getKey()), number.intValue());
            LinkCheck link = null;
            if (data.get("link_check") instanceof Map<?,?>) {
                var found = map(data.get("link_check"), "link_check");
                link = new LinkCheck(Boolean.TRUE.equals(found.get("linked")), found.get("classes") instanceof Long n ? n.intValue() : 0,
                    found.get("error_type") instanceof String type ? type : null, found.get("failed_class") instanceof String name ? name : null);
            }
            Signatures signatures = null;
            if (data.get("original_signatures") instanceof Map<?,?>) {
                var found = map(data.get("original_signatures"), "original_signatures");
                var removed = new ArrayList<String>();
                if (found.get("removed_classes") instanceof List<?> names) for (Object name : names) removed.add(String.valueOf(name));
                signatures = new Signatures(integer(found, "original_classes"), integer(found, "reversed_classes"), removed, integer(found, "original_methods"),
                    integer(found, "methods_kept"), integer(found, "original_fields"), integer(found, "fields_kept"));
            }
            return new Reversal(string(data, "obfuscator"), integer(data, "score"), string(data, "pass"), string(data, "status"),
                data.get("reason") instanceof String reason ? reason : null, data.get("summary") instanceof String summary ? summary : "",
                counts, data.get("methods_rewritten") instanceof Long n ? n.intValue() : 0, link, signatures);
        } catch (IllegalArgumentException | ClassCastException | NullPointerException malformed) {
            return null;
        }
    }

    private static List<Notice> notices(Object value) {
        var found = new ArrayList<Notice>();
        if (value instanceof List<?> items)
            for (Object item : items)
                if (item instanceof Map<?,?> row && row.get("code") instanceof String code)
                    found.add(new Notice(code, row.get("entry") instanceof String entry ? entry : "", row.get("message") instanceof String message ? message : ""));
        return List.copyOf(found);
    }

    private Path sources(String candidate) {
        if (!candidate.equals(SELECTION) && engines.stream().noneMatch(engine -> engine.id().equals(candidate)))
            throw new IllegalArgumentException("Unknown candidate: " + candidate);
        return output.resolve(candidate).resolve("sources");
    }

    static String topLevel(String internalName) {
        int slash = internalName.lastIndexOf('/'), dollar = internalName.indexOf('$', slash + 1);
        return dollar > slash + 1 ? internalName.substring(0, dollar) : internalName;
    }

    @SuppressWarnings("unchecked")
    private static Map<String,Object> map(Object value, String what) {
        if (!(value instanceof Map<?,?>)) throw new IllegalArgumentException(what + " is not an object");
        return (Map<String,Object>) value;
    }
    private static List<Object> list(Object value, String what) {
        if (!(value instanceof List<?> items)) throw new IllegalArgumentException(what + " is not an array");
        return new ArrayList<>(items);
    }
    private static String string(Map<String,Object> map, String key) {
        if (!(map.get(key) instanceof String text)) throw new IllegalArgumentException(key + " is not a string");
        return text;
    }
    private static int integer(Map<String,Object> map, String key) {
        if (!(map.get(key) instanceof Long number) || number < Integer.MIN_VALUE || number > Integer.MAX_VALUE)
            throw new IllegalArgumentException(key + " is not an integer");
        return number.intValue();
    }
}
