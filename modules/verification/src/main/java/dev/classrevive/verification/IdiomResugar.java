package dev.classrevive.verification;

import dev.classrevive.engine.*;
import dev.classrevive.model.ClassInfo;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;

/**
 * Asks {@link IdiomResugarWorker} (a separate JVM running only javac and ASM's class reader) to undo compiler idioms in
 * a mix (Phase 2DH): enum switches printed on ordinals or switch maps, synthetic {@code access$NNN} calls and
 * {@code $assertionsDisabled} tests. Every rewrite it returns was read from the original bytecode and kept only when its
 * file, recompiled alone against the original classes, still compiled with no method's round trip worse; the caller
 * still recompiles the whole mix and decides what to keep.
 */
public final class IdiomResugar {
    /** One kept rewrite: the file, the line it starts on in the file before the rewrite, its rule, and short texts. */
    public record Rewrite(String file, long line, String rule, String before, String after) {}
    /** A site left as it was, and why ({@code line} 0 and {@code rule} "-" for a whole file). */
    public record Refusal(String file, long line, String rule, String reason) {}
    /** {@code files} maps each edited source path to its new copy. */
    public record Proposal(EngineResult.Status status, List<Rewrite> rewrites, List<Refusal> refusals, Map<String,Path> files) {
        public Proposal {
            rewrites = List.copyOf(rewrites); refusals = List.copyOf(refusals);
            files = Collections.unmodifiableMap(new TreeMap<>(files));
        }
    }

    /** @param originalClasses the normalized class JAR the engines decompiled; edited files are recompiled against it */
    public Proposal propose(Path sources, Path work, List<ClassInfo> originals, Duration timeout, List<Path> dependencies,
                            Path originalClasses) throws IOException {
        sources = sources.toAbsolutePath().normalize(); work = work.toAbsolutePath().normalize();
        int release = CompilerVerifier.release(originals);
        if (release < 0) return new Proposal(EngineResult.Status.FAILED, List.of(), List.of(), Map.of());
        var classpath = new ArrayList<String>();
        try {
            for (var type : List.of(IdiomResugarWorker.class, org.objectweb.asm.ClassReader.class, org.objectweb.asm.tree.ClassNode.class,
                    org.objectweb.asm.util.Printer.class))
                classpath.add(Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString());
        } catch (Exception error) { throw new IOException("Cannot locate idiom rewrite worker", error); }
        var names = new HashSet<String>(); originals.forEach(info -> names.add(info.name()));
        var dependencyPaths = DependencyClasspath.prepare(dependencies, work.resolveSibling(work.getFileName() + "-dependencies"), names);
        long budget = Math.max(1, timeout.toMillis() / 4 * 3);
        Path jar = originalClasses.toAbsolutePath().normalize();
        var engine = new DecompilerEngine() {
            public String id() { return "javac-idioms"; }
            public String version() { return System.getProperty("java.version"); }
            public List<String> command(Path input, Path output) {
                var command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-Xmx512m", "-XX:MaxMetaspaceSize=128m", "-XX:ActiveProcessorCount=2", "-Dfile.encoding=UTF-8",
                    "-cp", String.join(java.io.File.pathSeparator, classpath), IdiomResugarWorker.class.getName(),
                    input.toString(), output.toString(), Integer.toString(release), jar.toString(), Long.toString(budget)));
                dependencyPaths.forEach(path -> command.add(path.toString())); return command;
            }
        };
        var result = new ProcessRunner().runMethodSplice(engine, sources, work, timeout);
        Path keptLog = work.resolve("idioms.tsv"), refusedLog = work.resolve("unresugared.tsv");
        if (result.exitCode() != 0 || !Files.isRegularFile(keptLog, LinkOption.NOFOLLOW_LINKS) || !Files.isRegularFile(refusedLog, LinkOption.NOFOLLOW_LINKS))
            return new Proposal(result.status(), List.of(), List.of(), Map.of());
        var rewrites = new ArrayList<Rewrite>();
        for (var fields : rows(keptLog, 5)) {
            if (!IdiomResugarWorker.RULES.contains(fields[2])) throw new IOException("Invalid idiom rewrite log");
            rewrites.add(new Rewrite(fields[0], number(fields[1]), fields[2], fields[3], fields[4]));
        }
        var refusals = new ArrayList<Refusal>();
        for (var fields : rows(refusedLog, 4)) {
            if (!fields[2].equals("-") && !IdiomResugarWorker.RULES.contains(fields[2])) throw new IOException("Invalid idiom rewrite log");
            refusals.add(new Refusal(fields[0], number(fields[1]), fields[2], fields[3]));
        }
        // Every rewritten path must name a mix file and have exactly one new copy; nothing else may be written.
        Path output = work.resolve("spliced").resolve("sources");
        var files = new TreeMap<String,Path>();
        for (var rewrite : rewrites) {
            Path original = sources.resolve(rewrite.file()).normalize(), edited = output.resolve(rewrite.file()).normalize();
            if (!original.startsWith(sources) || !edited.startsWith(output) || !Files.isRegularFile(original, LinkOption.NOFOLLOW_LINKS)
                || !Files.isRegularFile(edited, LinkOption.NOFOLLOW_LINKS) || Files.size(edited) > 16L * 1024 * 1024)
                throw new IOException("Invalid rewritten source");
            files.put(rewrite.file(), edited);
        }
        if (Files.isDirectory(work.resolve("spliced"), LinkOption.NOFOLLOW_LINKS)) {
            try (var paths = Files.walk(work.resolve("spliced"))) {
                for (var path : paths.toList()) {
                    if (Files.isSymbolicLink(path)) throw new IOException("Unsafe rewritten source tree");
                    if (Files.isRegularFile(path) && (!path.startsWith(output) || !files.containsKey(output.relativize(path).toString().replace('\\', '/'))))
                        throw new IOException("Unlisted rewritten source");
                }
            }
        }
        return new Proposal(result.status(), rewrites, refusals, files);
    }

    private static List<String[]> rows(Path path, int columns) throws IOException {
        if (Files.size(path) > 32L * 1024 * 1024) throw new IOException("Idiom rewrite log exceeds limit");
        var rows = new ArrayList<String[]>();
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            if (line.isEmpty()) continue;
            String[] fields = line.split("\t", -1);
            if (fields.length != columns || rows.size() >= IdiomResugarWorker.MAX_ROWS) throw new IOException("Invalid idiom rewrite log");
            for (int i = 0; i < fields.length; i++) fields[i] = SourceRepair.unescape(fields[i]);
            if (!CompilerVerifier.safeSourcePath(fields[0])) throw new IOException("Invalid idiom rewrite log");
            rows.add(fields);
        }
        return rows;
    }

    private static long number(String value) throws IOException {
        try {
            long result = Long.parseLong(value);
            if (result < 0) throw new IOException("Invalid idiom rewrite position");
            return result;
        } catch (NumberFormatException error) { throw new IOException("Invalid idiom rewrite position"); }
    }
}
