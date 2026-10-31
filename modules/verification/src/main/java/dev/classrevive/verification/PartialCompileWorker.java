package dev.classrevive.verification;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import javax.tools.*;

/**
 * Phase 2BP: compiles as many candidate files as possible against the original classes. Each round compiles the files
 * still in play with the original class JAR (and any dependencies) on the class path; files with errors leave, and the
 * original versions of their classes stand in for them through the class path. The rounds stop when the rest compiles,
 * when an error names no file, or after {@link #MAX_ROUNDS}. Runs only javac: no target class is loaded or executed.
 *
 * <p>Arguments: sources, output folder for classes, release, the original class JAR, then dependency JARs. Writes
 * {@code partial.properties} and {@code excluded.tsv} (round and file) next to the output folder.
 */
public final class PartialCompileWorker {
    static final int MAX_ROUNDS = 20;

    public static void main(String[] args) throws Exception {
        Path sources = Path.of(args[0]), output = Path.of(args[1]);
        String release = args[2];
        var classpath = new ArrayList<Path>();
        for (int i = 3; i < args.length; i++) classpath.add(Path.of(args[i]));
        var compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) throw new IllegalStateException("A JDK is required for per-file measurement");
        var files = CompilerWorker.sourceFiles(sources);
        Path empty = Files.createDirectory(output.getParent().resolve("empty-sourcepath"));
        var relative = new HashMap<Path,String>();
        for (var file : files) relative.put(file.toPath().toAbsolutePath().normalize(), sources.relativize(file.toPath()).toString().replace('\\', '/'));
        var remaining = new ArrayList<>(files);
        var excluded = new StringBuilder();
        int rounds = 0;
        boolean compiled = false, unattributed = false;
        try (var manager = compiler.getStandardFileManager(null, Locale.ROOT, StandardCharsets.UTF_8)) {
            JdkRuntimeClasses.configure(manager, release, classpath, empty);
            while (!remaining.isEmpty() && rounds < MAX_ROUNDS) {
                rounds++;
                clear(output);
                var failing = new LinkedHashSet<String>();
                boolean[] other = {false};
                // javac stops analyzing other classes after the first error unless told to finish flow analysis; without
                // should-stop.ifError=FLOW each round would find only one file with a flow error (missing return, unreachable code).
                compiled = compiler.getTask(null, manager, diagnostic -> {
                    if (diagnostic.getKind() != Diagnostic.Kind.ERROR) return;
                    var uri = diagnostic.getSource() == null ? null : diagnostic.getSource().toUri();
                    String name = uri == null || !"file".equals(uri.getScheme()) ? null : relative.get(Path.of(uri).toAbsolutePath().normalize());
                    if (name == null) other[0] = true; else failing.add(name);
                }, List.of("--release", release, "-proc:none", "-implicit:none", "-encoding", "UTF-8", "-Xlint:-options", "-XDshould-stop.ifError=FLOW",
                    "-sourcepath", empty.toString(), "-d", output.toString(), "-Xmaxerrs", "100000"), null,
                    manager.getJavaFileObjectsFromFiles(remaining)).call();
                if (compiled) break;
                // An error outside the candidate files (a class path problem) cannot be fixed by leaving files out.
                if (failing.isEmpty() || other[0]) { unattributed = true; break; }
                remaining.removeIf(file -> failing.contains(relative.get(file.toPath().toAbsolutePath().normalize())));
                for (String name : failing) excluded.append(rounds).append('\t').append(SourceRepairWorker.escape(name)).append('\n');
            }
        }
        if (!compiled) clear(output);
        var result = new Properties();
        result.setProperty("files", Integer.toString(files.size()));
        result.setProperty("compiled-files", Integer.toString(compiled ? remaining.size() : 0));
        result.setProperty("rounds", Integer.toString(rounds));
        result.setProperty("compiled", Boolean.toString(compiled));
        result.setProperty("unattributed-errors", Boolean.toString(unattributed));
        try (var stream = Files.newOutputStream(output.getParent().resolve("partial.properties"), StandardOpenOption.CREATE_NEW)) {
            result.store(stream, "Per-file compilation against the original classes; no target execution");
        }
        Files.writeString(output.getParent().resolve("excluded.tsv"), excluded, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
    }

    /** Deletes the classes a previous round wrote (javac may write classes before it meets an error elsewhere). */
    private static void clear(Path output) throws java.io.IOException {
        try (var paths = Files.walk(output)) {
            for (var path : paths.sorted(Comparator.reverseOrder()).toList()) if (!path.equals(output)) Files.delete(path);
        }
    }
}
