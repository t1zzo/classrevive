package dev.classrevive.verification;

import dev.classrevive.engine.*;
import dev.classrevive.model.ClassInfo;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;

/**
 * Asks {@link SourceRepairWorker} (a separate JVM running only javac) for minimal edits to the diagnostics it knows.
 * A proposal is not evidence of anything until the edited sources are recompiled; the caller decides what to keep.
 */
public final class SourceRepair {
    /**
     * Phase 2DE: the rule that adds a method the original class does not have, because a JDK type requires it only since
     * after the class's target release (javac 21 builds release 8 or later). Reports list these edits apart.
     */
    public static final String ENVIRONMENT_STUB = "environment-stub";
    /** Rules the worker may report; anything else in its output is rejected. */
    public static final Set<String> RULES = Set.of("raw-cast-to-target", "raw-argument", "raw-diamond", "raw-type-argument",
        "drop-final-local", "capture-copy", "renamed-local", "guard-initializer", "boolean-literal-as-int", "drop-unreachable-jump", "drop-object-cast", "raw-cast-type",
        "iterable-cast", "functional-cast", "type-witness", "type-variable-cast", "lambda-cast", "shadowed-parameter", "receiver-witness", "creation-type-argument",
        "hoist-local-class", ENVIRONMENT_STUB);

    /** One applied edit, tied to the javac diagnostic (code, file, line, column) that triggered it. */
    public record Edit(String file, long line, long column, String code, String rule, String before, String after) {}
    /** A diagnostic left alone, and why. */
    public record Unrepaired(String file, long line, long column, String code, String reason) {}
    /** {@code files} maps each edited source path to its repaired copy. */
    public record Proposal(EngineResult.Status status, List<Edit> edits, List<Unrepaired> unrepaired, Map<String,Path> files) {
        public Proposal {
            edits = List.copyOf(edits); unrepaired = List.copyOf(unrepaired);
            files = Collections.unmodifiableMap(new TreeMap<>(files));
        }
    }

    public Proposal propose(Path sources, Path work, List<ClassInfo> originals, Duration timeout, List<Path> dependencies) throws IOException {
        return propose(sources, work, originals, timeout, dependencies, null);
    }
    /** @param originalClasses optional normalized class JAR the engines decompiled; rules may read its bytecode (Phase 2BM) */
    public Proposal propose(Path sources, Path work, List<ClassInfo> originals, Duration timeout, List<Path> dependencies,
                            Path originalClasses) throws IOException {
        sources = sources.toAbsolutePath().normalize(); work = work.toAbsolutePath().normalize();
        int release = CompilerVerifier.release(originals);
        if (release < 0) return new Proposal(EngineResult.Status.FAILED, List.of(), List.of(), Map.of());
        var workerClasspath = new ArrayList<String>();
        try {
            // ASM reads the original invokedynamic call sites for functional-cast (Phase 2BM); the original JAR is read with
            // MethodRoundTrip.readJar, whose class also needs asm-tree and asm-util, as for method composition.
            for (var type : List.of(SourceRepairWorker.class, org.objectweb.asm.ClassReader.class, org.objectweb.asm.tree.ClassNode.class,
                    org.objectweb.asm.util.Printer.class))
                workerClasspath.add(Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString());
        } catch (Exception error) { throw new IOException("Cannot locate source repair worker", error); }
        String originalsOption = originalClasses == null ? null
            : "-D" + SourceRepairWorker.ORIGINAL_CLASSES + "=" + originalClasses.toAbsolutePath().normalize();
        var names = new HashSet<String>(); originals.forEach(info -> names.add(info.name()));
        var classpath = DependencyClasspath.prepare(dependencies, work.resolveSibling(work.getFileName() + "-dependencies"), names);
        var engine = new DecompilerEngine() {
            public String id() { return "javac-repair"; }
            public String version() { return System.getProperty("java.version"); }
            public List<String> command(Path input, Path output) {
                var command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-Xmx256m", "-XX:MaxMetaspaceSize=128m", "-XX:ActiveProcessorCount=2", "-Dfile.encoding=UTF-8"));
                if (originalsOption != null) command.add(originalsOption);
                command.addAll(List.of("-cp", String.join(java.io.File.pathSeparator, workerClasspath), SourceRepairWorker.class.getName(),
                    input.toString(), output.toString(), Integer.toString(release)));
                classpath.forEach(path -> command.add(path.toString())); return command;
            }
        };
        var result = new ProcessRunner().runSourceRepair(engine, sources, work, timeout);
        Path repairs = work.resolve("repairs.tsv"), unrepaired = work.resolve("unrepaired.tsv");
        if (result.exitCode() != 0 || !Files.isRegularFile(repairs, LinkOption.NOFOLLOW_LINKS) || !Files.isRegularFile(unrepaired, LinkOption.NOFOLLOW_LINKS))
            return new Proposal(result.status(), List.of(), List.of(), Map.of());
        var edits = new ArrayList<Edit>();
        for (var fields : rows(repairs, 7, SourceRepairWorker.MAX_EDITS)) {
            if (!RULES.contains(fields[4])) throw new IOException("Unknown source repair rule");
            edits.add(new Edit(fields[0], number(fields[1]), number(fields[2]), fields[3], fields[4], fields[5], fields[6]));
        }
        var skipped = new ArrayList<Unrepaired>();
        for (var fields : rows(unrepaired, 5, SourceRepairWorker.MAX_DIAGNOSTICS + SourceRepairWorker.MAX_EDITS))
            skipped.add(new Unrepaired(fields[0], number(fields[1]), number(fields[2]), fields[3], fields[4]));
        // Every edited path must name a candidate file and have exactly one repaired copy; nothing else may be written.
        Path output = work.resolve("repaired");
        var files = new TreeMap<String,Path>();
        for (var edit : edits) {
            Path original = sources.resolve(edit.file()).normalize(), repaired = output.resolve(edit.file()).normalize();
            if (!original.startsWith(sources) || !repaired.startsWith(output) || !Files.isRegularFile(original, LinkOption.NOFOLLOW_LINKS)
                || !Files.isRegularFile(repaired, LinkOption.NOFOLLOW_LINKS) || Files.size(repaired) > 16L * 1024 * 1024)
                throw new IOException("Invalid repaired source");
            files.put(edit.file(), repaired);
        }
        try (var paths = Files.walk(output)) {
            for (var path : paths.toList()) {
                if (Files.isSymbolicLink(path)) throw new IOException("Unsafe repaired source tree");
                if (Files.isRegularFile(path) && !files.containsKey(output.relativize(path).toString().replace('\\', '/')))
                    throw new IOException("Unlisted repaired source");
            }
        }
        return new Proposal(result.status(), edits, skipped, files);
    }

    static List<String[]> rows(Path path, int columns, int limit) throws IOException {
        if (Files.size(path) > 4L * 1024 * 1024) throw new IOException("Source repair log exceeds limit");
        var rows = new ArrayList<String[]>();
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            if (line.isEmpty()) continue;
            String[] fields = line.split("\t", -1);
            if (fields.length != columns || rows.size() >= limit) throw new IOException("Invalid source repair log");
            for (int i = 0; i < fields.length; i++) fields[i] = unescape(fields[i]);
            if (!CompilerVerifier.safeSourcePath(fields[0]) || !fields[3].startsWith("compiler.err.")) throw new IOException("Invalid source repair log");
            rows.add(fields);
        }
        return rows;
    }

    private static long number(String value) throws IOException {
        try { long number = Long.parseLong(value); if (number >= -1) return number; } catch (NumberFormatException ignored) { }
        throw new IOException("Invalid source repair position");
    }

    static String unescape(String value) throws IOException {
        var out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c != '\\') { out.append(c); continue; }
            if (++i == value.length()) throw new IOException("Invalid source repair escape");
            switch (value.charAt(i)) {
                case '\\' -> out.append('\\');
                case 't' -> out.append('\t');
                case 'r' -> out.append('\r');
                case 'n' -> out.append('\n');
                default -> throw new IOException("Invalid source repair escape");
            }
        }
        return out.toString();
    }
}
