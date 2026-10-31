package dev.classrevive.verification;

import dev.classrevive.model.ClassInfo;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.io.IOException;
import java.nio.file.Files;
import java.util.*;
import dev.classrevive.engine.*;
import dev.classrevive.bytecode.ClassInspector;

public final class CompilerVerifier {
    public VerificationResult verify(Path sources, Path work, List<ClassInfo> originals, Duration timeout) throws IOException {
        return verify(sources,work,originals,timeout,List.of());
    }
    public VerificationResult verify(Path sources, Path work, List<ClassInfo> originals, Duration timeout, List<Path> dependencies) throws IOException {
        return verify(sources,work,originals,timeout,dependencies,null);
    }
    /**
     * @param originalClasses optional normalized class JAR holding the bytes the engines decompiled; when present and
     *                        the candidate compiles, each method body is also compared by {@link MethodRoundTrip}.
     */
    public VerificationResult verify(Path sources, Path work, List<ClassInfo> originals, Duration timeout, List<Path> dependencies,
                                     Path originalClasses) throws IOException {
        // A module descriptor is not ordinary source: it is neither recompiled nor counted (CompilerWorker skips module-info.java).
        int descriptors = (int) originals.stream().filter(info -> info.name().equals("module-info")).count();
        var allOriginals = originals;
        originals = originals.stream().filter(info -> !info.name().equals("module-info")).toList();
        Map<String,Set<String>> expectedByClass = symbolsByClass(originals);
        int expectedCount = expectedByClass.values().stream().mapToInt(Set::size).sum();
        int release = release(originals);
        int newest = originals.stream().mapToInt(ClassInfo::javaVersion).max().orElse(release);
        String releaseNote = (newest < release ? "release=" + release + " (Java " + newest + " class files; javac 21 targets 8 at the lowest)"
            : "release=" + release) + (descriptors > 0 ? "; module descriptor not recompiled" : "");
        if (release < 0)
            return new VerificationResult(VerificationResult.Status.UNSUPPORTED_RELEASE, 0, 0, expectedCount, 0,
                "JDK 21 verifies class files up to Java 21 (Java 1.1..7 with --release 8); preview unsupported", coverage(expectedByClass, Map.of()));
        Path workerLocation;
        try { workerLocation = Path.of(CompilerWorker.class.getProtectionDomain().getCodeSource().getLocation().toURI()); }
        catch (Exception error) { throw new IOException("Cannot locate compiler worker", error); }
        var names=new HashSet<String>(); allOriginals.forEach(info->names.add(info.name()));
        var classpath=DependencyClasspath.prepare(dependencies,work.resolveSibling(work.getFileName()+"-dependencies"),names);
        var engine = new DecompilerEngine() {
            public String id() { return "javac"; }
            public String version() { return System.getProperty("java.version"); }
            public List<String> command(Path input, Path output) {
                var command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-Xmx256m", "-XX:MaxMetaspaceSize=128m", "-XX:ActiveProcessorCount=2", "-Dfile.encoding=UTF-8",
                    "-cp", workerLocation.toString(), CompilerWorker.class.getName(), input.toString(), output.toString(), Integer.toString(release)));
                classpath.forEach(path->command.add(path.toString())); return command;
            }
        };
        var result = new ProcessRunner().runCompiler(engine, sources, work, timeout);
        Path properties = work.resolve("compile.properties");
        if (result.exitCode() != 0 || !Files.isRegularFile(properties) || Files.size(properties) > 8192)
            return new VerificationResult(VerificationResult.Status.WORKER_FAILED, 1000, 0, expectedCount, 0,
                result.status().toString(), coverage(expectedByClass, Map.of()));
        var values = new Properties();
        try (var input = Files.newInputStream(properties)) { values.load(input); }
        int errors = Integer.parseInt(values.getProperty("errors", "1000"));
        var status = Boolean.parseBoolean(values.getProperty("compiled")) ? VerificationResult.Status.COMPILED : VerificationResult.Status.COMPILE_FAILED;
        var compiled = new ArrayList<ClassInfo>();
        var compiledBytes = new TreeMap<String,byte[]>();
        try (var paths = Files.walk(work.resolve("classes"))) {
            for (Path path : paths.filter(p -> p.toString().endsWith(".class")).toList()) {
                if (Files.isSymbolicLink(path) || Files.size(path) > 16L * 1024 * 1024) throw new IOException("Unsafe compiled output");
                byte[] bytes = Files.readAllBytes(path);
                var info = new ClassInspector().inspect(bytes);
                compiled.add(info); compiledBytes.put(info.name(), bytes);
            }
        }
        Map<String,Set<String>> actualByClass = symbolsByClass(compiled);
        var classCoverage = coverage(expectedByClass, actualByClass);
        int matchedCount = classCoverage.stream().mapToInt(ClassCoverage::matchedSymbols).sum();
        int signals = 0;
        try (var paths = Files.walk(sources)) {
            for (var path : paths.filter(p -> p.toString().endsWith(".java")).toList()) {
                if (Files.isSymbolicLink(path) || Files.size(path) > 16L * 1024 * 1024) throw new IOException("Unsafe source output");
                signals += placeholderSignals(Files.readString(path));
            }
        }
        List<ClassRoundTrip> roundTrip = originalClasses != null && status == VerificationResult.Status.COMPILED
            ? MethodRoundTrip.compare(MethodRoundTrip.readJar(originalClasses), compiledBytes) : List.of();
        int syntaxErrors = Integer.parseInt(values.getProperty("syntax-errors", Integer.toString(errors)));
        return new VerificationResult(status, errors, matchedCount, expectedCount, signals,
            releaseNote + missingPackages(values.getProperty("missing-packages", ""), allOriginals)
                + runtimeTypes(values.getProperty("jdk-runtime-types", ""))
                + "; structural signatures only; behavior not verified", classCoverage, roundTrip,
            syntaxErrors, fileErrors(work.resolve("file-errors.txt")), diagnostics(work.resolve("diagnostics.tsv")));
    }
    /** Reads the worker's located errors: bounded, with the path rules and escapes of the source repair logs. */
    static List<VerificationResult.CompilerDiagnostic> diagnostics(Path path) throws IOException {
        if (!Files.isRegularFile(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return List.of();
        if (Files.size(path) > 1024L * 1024) throw new IOException("Compiler diagnostics exceed limit");
        var result = new ArrayList<VerificationResult.CompilerDiagnostic>();
        for (String line : Files.readAllLines(path, java.nio.charset.StandardCharsets.UTF_8)) {
            if (line.isEmpty()) continue;
            String[] fields = line.split("\t", -1);
            if (fields.length != 5 || result.size() >= CompilerWorker.MAX_DIAGNOSTICS) throw new IOException("Invalid compiler diagnostics");
            for (int i = 0; i < fields.length; i++) fields[i] = SourceRepair.unescape(fields[i]);
            if (!safeSourcePath(fields[0]) || !fields[3].startsWith("compiler.err.")) throw new IOException("Invalid compiler diagnostics");
            long row, column;
            try { row = Long.parseLong(fields[1]); column = Long.parseLong(fields[2]); }
            catch (NumberFormatException error) { throw new IOException("Invalid compiler diagnostics"); }
            if (row < -1 || column < -1) throw new IOException("Invalid compiler diagnostics");
            result.add(new VerificationResult.CompilerDiagnostic(fields[0], row, column, fields[3], fields[4]));
        }
        return List.copyOf(result);
    }
    /**
     * The javac release for the originals, or -1 when JDK 21 cannot compile for it (newer than 21, or preview). Java 1.1..7
     * class files are verified with {@code --release 8}, the lowest release javac 21 accepts; their source is valid Java 8.
     */
    static int release(List<ClassInfo> originals) {
        int release = originals.stream().mapToInt(ClassInfo::javaVersion).max().orElse(21);
        if (release > 21 || originals.stream().anyMatch(c -> c.minorVersion() == 65535)) return -1;
        return Math.max(8, release);
    }
    /**
     * Phase 2BC: packages javac reported as nonexistent and that no input class belongs to, as
     * {@code "; missing packages (supply with --classpath): a.b (27), c.d (3)"}, largest first; empty when there are none.
     * Packages of the input are dropped: their absence means a decompiled file failed, not a missing dependency.
     */
    static String missingPackages(String property, List<ClassInfo> originals) {
        var own = new HashSet<String>();
        for (var info : originals) {
            int slash = info.name().lastIndexOf('/');
            if (slash > 0) own.add(info.name().substring(0, slash).replace('/', '.'));
        }
        var counts = new TreeMap<String,Integer>();
        for (String entry : property.split(",")) {
            int colon = entry.lastIndexOf(':');
            if (colon <= 0) continue;
            String name = entry.substring(0, colon);
            // An unresolved class used as a qualifier (`XZ.CHECK_NONE`) is also reported as a "package"; packages are lowercase.
            if (!name.matches("[\\p{L}_$][\\p{L}\\p{N}_$]*(\\.[\\p{L}_$][\\p{L}\\p{N}_$]*)*") || own.contains(name)
                || Character.isUpperCase(name.charAt(0))) continue;
            try { counts.merge(name, Integer.parseInt(entry.substring(colon + 1)), Integer::sum); } catch (NumberFormatException ignored) { }
        }
        if (counts.isEmpty()) return "";
        var joined = new StringJoiner(", ", "; missing packages (supply with --classpath): ", "");
        counts.entrySet().stream().sorted(Map.Entry.<String,Integer>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
            .limit(20).forEach(entry -> joined.add(entry.getKey() + " (" + entry.getValue() + ")"));
        return joined.toString();
    }
    /**
     * Phase 2BL: JDK types outside the Java 8 API that the compiled classes use, read by javac from the running JDK, as
     * {@code "; outside the Java 8 API, read from the running JDK: sun.misc.Unsafe"}; empty when there are none.
     */
    static String runtimeTypes(String property) {
        var names = Arrays.stream(property.split(",")).filter(name -> name.matches("[\\p{L}_$][\\p{L}\\p{N}_$]*(\\.[\\p{L}_$][\\p{L}\\p{N}_$]*)+"))
            .distinct().sorted().limit(20).toList();
        return names.isEmpty() ? "" : "; outside the Java 8 API, read from the running JDK: " + String.join(", ", names);
    }
    /** A relative path with "/" separators and no empty, "." or ".." part, ending in .java. */
    static boolean safeSourcePath(String name) {
        boolean safe = !name.isEmpty() && !name.startsWith("/") && !name.contains("\\") && name.endsWith(".java");
        for (String part : name.split("/", -1)) safe &= !part.isEmpty() && !part.equals(".") && !part.equals("..");
        return safe;
    }
    /** Reads the worker's per-file error counts; bounded, and only relative paths without "." or ".." parts. */
    static Map<String,Integer> fileErrors(Path path) throws IOException {
        if (!Files.isRegularFile(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return Map.of();
        if (Files.size(path) > 4L * 1024 * 1024) throw new IOException("Per-file compiler errors exceed limit");
        var result = new TreeMap<String,Integer>();
        for (String line : Files.readAllLines(path, java.nio.charset.StandardCharsets.UTF_8)) {
            if (line.isEmpty()) continue;
            int tab = line.indexOf('\t');
            if (tab <= 0 || result.size() >= 20_000) throw new IOException("Invalid per-file compiler errors");
            String name = line.substring(tab + 1);
            boolean safe = safeSourcePath(name);
            int count;
            try { count = Integer.parseInt(line.substring(0, tab)); } catch (NumberFormatException error) { count = -1; }
            if (!safe || count <= 0 || result.put(name, count) != null) throw new IOException("Invalid per-file compiler errors");
        }
        return result;
    }
    /**
     * Text markers of methods an engine replaced with a stub or flagged as possibly wrong. Engine-specific
     * entries were confirmed in the pinned CFR 0.152, Vineflower 1.11.1, Procyon 0.6.0 and JADX 1.5.3 jars.
     * Counts distinct markers per file; a generic throw also matches authored code, so this stays a heuristic.
     */
    public static final List<String> PLACEHOLDER_SIGNALS = List.of(
        "throw new UnsupportedOperationException", "could not be decompiled", "Couldn't be decompiled", "$FF: Couldn't be decompiled",
        "has failed to decompile", "Decompilation failed",
        "An error occurred while decompiling this method.",
        "Method dump skipped", "JADX ERROR:", "Code restructure failed");
    public static int placeholderSignals(String text) {
        int signals = 0;
        for (var signal : PLACEHOLDER_SIGNALS) if (text.contains(signal)) signals++;
        return signals;
    }
    private static Map<String,Set<String>> symbolsByClass(List<ClassInfo> classes) {
        var result = new TreeMap<String,Set<String>>();
        for (var info : classes) {
            Set<String> symbols = result.computeIfAbsent(info.name(), ignored -> new TreeSet<>());
            symbols.add(info.name() + ":class:" + (info.access() & 0x16611) + ":" + info.superName() + ":" + String.join(",", new TreeSet<>(info.interfaces())));
            for (var field : info.fields()) if ((field.access() & 0x1000) == 0)
                symbols.add(info.name() + ":field:" + (field.access() & 0xdf) + ":" + field.name() + field.descriptor());
            for (var method : info.methods()) if ((method.access() & (0x1000 | 0x40)) == 0 && !method.name().equals("<clinit>"))
                symbols.add(info.name() + ":method:" + (method.access() & 0xd3f) + ":" + method.name() + method.descriptor());
        }
        return result;
    }
    private static List<ClassCoverage> coverage(Map<String,Set<String>> expected, Map<String,Set<String>> actual) {
        var result = new ArrayList<ClassCoverage>(expected.size());
        for (var entry : expected.entrySet()) {
            Set<String> found = actual.getOrDefault(entry.getKey(), Set.of());
            long matched = entry.getValue().stream().filter(found::contains).count();
            result.add(new ClassCoverage(entry.getKey(), entry.getValue().size(), Math.toIntExact(matched)));
        }
        return List.copyOf(result);
    }
}
