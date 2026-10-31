package dev.classrevive.verification;

import dev.classrevive.engine.*;
import dev.classrevive.model.ClassInfo;
import java.io.IOException;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;

/**
 * Phase 2BP: per-method round trip for a candidate that does not compile as a whole. {@link PartialCompileWorker}
 * compiles the files it can against the original classes, and each class those files produce is compared with its
 * original by {@link MethodRoundTrip}. Bodies of the files left out count as not recovered.
 *
 * <p>This measures each file in the context of the original program, not of the other decompiled files, so it is
 * reported apart from the whole-candidate round trip and never used to select or call a candidate compiled.
 */
public final class PartialRoundTrip {
    /** {@code classes}: round trip of every original class the compiled files produced. */
    public record Result(int files, int compiledFiles, int rounds, boolean compiled, List<ClassRoundTrip> classes) {
        public Result { classes = List.copyOf(classes); }
        public int identical() { return classes.stream().mapToInt(ClassRoundTrip::identical).sum(); }
        public int equivalent() { return classes.stream().mapToInt(ClassRoundTrip::equivalent).sum(); }
        public int different() { return classes.stream().mapToInt(ClassRoundTrip::different).sum(); }
    }

    /** Null when the originals' release cannot be compiled by this JDK. */
    public Result measure(Path sources, Path work, List<ClassInfo> originals, Duration timeout, List<Path> dependencies,
                          Path originalClasses) throws IOException {
        sources = sources.toAbsolutePath().normalize(); work = work.toAbsolutePath().normalize();
        var plain = originals.stream().filter(info -> !info.name().equals("module-info")).toList();
        int release = CompilerVerifier.release(plain);
        if (release < 0) return null;
        Path workerLocation;
        try { workerLocation = Path.of(PartialCompileWorker.class.getProtectionDomain().getCodeSource().getLocation().toURI()); }
        catch (Exception error) { throw new IOException("Cannot locate per-file compilation worker", error); }
        var names = new HashSet<String>(); originals.forEach(info -> names.add(info.name()));
        var classpath = new ArrayList<Path>(List.of(originalClasses.toAbsolutePath().normalize()));
        classpath.addAll(DependencyClasspath.prepare(dependencies, work.resolveSibling(work.getFileName() + "-dependencies"), names));
        var engine = new DecompilerEngine() {
            public String id() { return "javac-per-file"; }
            public String version() { return System.getProperty("java.version"); }
            public List<String> command(Path input, Path output) {
                var command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-Xmx512m", "-XX:MaxMetaspaceSize=128m", "-XX:ActiveProcessorCount=2", "-Dfile.encoding=UTF-8",
                    "-cp", workerLocation.toString(), PartialCompileWorker.class.getName(), input.toString(), output.toString(), Integer.toString(release)));
                classpath.forEach(path -> command.add(path.toString())); return command;
            }
        };
        var run = new ProcessRunner().runCompiler(engine, sources, work, timeout);
        Path properties = work.resolve("partial.properties");
        if (run.exitCode() != 0 || !Files.isRegularFile(properties, LinkOption.NOFOLLOW_LINKS) || Files.size(properties) > 8192)
            return new Result(0, 0, 0, false, List.of());
        var values = new Properties();
        try (var input = Files.newInputStream(properties)) { values.load(input); }
        boolean compiled = Boolean.parseBoolean(values.getProperty("compiled"));
        var recompiled = new TreeMap<String,byte[]>();
        if (compiled) {
            try (var paths = Files.walk(work.resolve("classes"))) {
                for (Path path : paths.filter(p -> p.toString().endsWith(".class")).toList()) {
                    if (Files.isSymbolicLink(path) || Files.size(path) > 16L * 1024 * 1024) throw new IOException("Unsafe compiled output");
                    byte[] bytes = Files.readAllBytes(path);
                    recompiled.put(new dev.classrevive.bytecode.ClassInspector().inspect(bytes).name(), bytes);
                }
            }
        }
        // Only the classes the kept files produced: the others were not recompiled and stay outside the comparison.
        var all = MethodRoundTrip.readJar(originalClasses);
        var compared = new TreeMap<String,byte[]>();
        for (var name : recompiled.keySet()) if (all.containsKey(name)) compared.put(name, all.get(name));
        var classes = compared.isEmpty() ? List.<ClassRoundTrip>of() : MethodRoundTrip.compare(compared, recompiled);
        return new Result(number(values, "files"), number(values, "compiled-files"), number(values, "rounds"), compiled, classes);
    }
    private static int number(Properties values, String key) {
        try { return Math.max(0, Integer.parseInt(values.getProperty(key, "0"))); } catch (NumberFormatException error) { return 0; }
    }
}
