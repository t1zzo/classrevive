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
    private volatile List<Diagnostic> diagnostics;

    private ResultIndex(Path output, Summary summary, SortedMap<String,ClassEntry> classes, List<EngineEntry> engines,
                        Map<String,String> originalNames, boolean levelsAvailable, Compiler compiler) {
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
                Collections.unmodifiableMap(originals), levels, compiler);
        } catch (IllegalArgumentException | ClassCastException | NullPointerException malformed) {
            throw new IOException("Not a readable ClassRevive report: " + malformed.getMessage(), malformed);
        }
    }

    public Path output() { return output; }
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
