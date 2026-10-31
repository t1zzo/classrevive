package dev.classrevive.verification;

import dev.classrevive.engine.*;
import dev.classrevive.model.ClassInfo;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;

/**
 * Phase 2CL: asks {@link PlaceholderTypeWorker} (a separate JVM running only javac) to type the locals JADX declared as
 * {@code ??} and to fix its oversized integer literals. The typed files are an engine tree's own sources with those edits;
 * they are measured like any other candidate and are evidence of nothing until they compile.
 */
public final class PlaceholderTypes {
    /**
     * One edit: where it was, the text written, and the rule ({@code initializer} or {@code object} for a {@code ??} type,
     * {@code long-literal} or {@code double-bits} for an oversized literal).
     */
    public record Site(String file, long line, long column, String written, String rule) {}
    /** {@code files} maps each edited source path to its typed copy. */
    public record Result(EngineResult.Status status, List<Site> sites, Map<String,Path> files) {
        public Result {
            sites = List.copyOf(sites);
            files = Collections.unmodifiableMap(new TreeMap<>(files));
        }
    }

    /** Whether {@code text} has a JADX placeholder declaration or an oversized integer literal outside comments and literals. */
    public static boolean present(String text) {
        boolean placeholder = text.contains("??");
        if (!placeholder && !PlaceholderTypeWorker.LITERAL.matcher(text).find()) return false;
        String masked = SourceText.mask(text);
        if (placeholder && PlaceholderTypeWorker.SITE.matcher(masked).find()) return true;
        var literals = PlaceholderTypeWorker.LITERAL.matcher(masked);
        while (literals.find()) if (PlaceholderTypeWorker.oversized(literals.group())) return true;
        return false;
    }

    public Result type(Path sources, Path work, List<ClassInfo> originals, Duration timeout, List<Path> dependencies) throws IOException {
        sources = sources.toAbsolutePath().normalize(); work = work.toAbsolutePath().normalize();
        int release = CompilerVerifier.release(originals);
        if (release < 0) return new Result(EngineResult.Status.FAILED, List.of(), Map.of());
        String workerClasspath;
        try {
            workerClasspath = Path.of(PlaceholderTypeWorker.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        } catch (Exception error) { throw new IOException("Cannot locate placeholder type worker", error); }
        var names = new HashSet<String>(); originals.forEach(info -> names.add(info.name()));
        var classpath = DependencyClasspath.prepare(dependencies, work.resolveSibling(work.getFileName() + "-dependencies"), names);
        var engine = new DecompilerEngine() {
            public String id() { return "javac-placeholder-types"; }
            public String version() { return System.getProperty("java.version"); }
            public List<String> command(Path input, Path output) {
                var command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-Xmx512m", "-XX:MaxMetaspaceSize=128m", "-XX:ActiveProcessorCount=2", "-Dfile.encoding=UTF-8",
                    "-cp", workerClasspath, PlaceholderTypeWorker.class.getName(), input.toString(), output.toString(), Integer.toString(release)));
                classpath.forEach(path -> command.add(path.toString())); return command;
            }
        };
        var result = new ProcessRunner().runSourceRepair(engine, sources, work, timeout);
        Path table = work.resolve("typed.tsv");
        if (result.exitCode() != 0 || !Files.isRegularFile(table, LinkOption.NOFOLLOW_LINKS))
            return new Result(result.status(), List.of(), Map.of());
        if (Files.size(table) > 4L * 1024 * 1024) throw new IOException("Placeholder type log exceeds limit");
        var sites = new ArrayList<Site>(); var files = new TreeMap<String,Path>();
        Path output = work.resolve("repaired");
        for (String line : Files.readAllLines(table, StandardCharsets.UTF_8)) {
            if (line.isEmpty()) continue;
            String[] fields = line.split("\t", -1);
            if (fields.length != 5 || sites.size() >= PlaceholderTypeWorker.MAX_SITES || !CompilerVerifier.safeSourcePath(fields[0])
                || !fields[3].matches("[\\p{L}\\p{N}_$.<>,\\[\\] ?-]{1,512}") || !PlaceholderTypeWorker.RULES.contains(fields[4]))
                throw new IOException("Invalid placeholder type log");
            long row, column;
            try { row = Long.parseLong(fields[1]); column = Long.parseLong(fields[2]); }
            catch (NumberFormatException error) { throw new IOException("Invalid placeholder type position", error); }
            sites.add(new Site(fields[0], row, column, fields[3], fields[4]));
            Path original = sources.resolve(fields[0]).normalize(), typed = output.resolve(fields[0]).normalize();
            if (!original.startsWith(sources) || !typed.startsWith(output) || !Files.isRegularFile(original, LinkOption.NOFOLLOW_LINKS)
                || !Files.isRegularFile(typed, LinkOption.NOFOLLOW_LINKS) || Files.size(typed) > 16L * 1024 * 1024)
                throw new IOException("Invalid typed source");
            files.put(fields[0], typed);
        }
        if (Files.isDirectory(output)) try (var paths = Files.walk(output)) {
            for (var path : paths.toList()) {
                if (Files.isSymbolicLink(path)) throw new IOException("Unsafe typed source tree");
                if (Files.isRegularFile(path) && !files.containsKey(output.relativize(path).toString().replace('\\', '/')))
                    throw new IOException("Unlisted typed source");
            }
        }
        return new Result(result.status(), sites, files);
    }
}
