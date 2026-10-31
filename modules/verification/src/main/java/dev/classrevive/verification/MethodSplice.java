package dev.classrevive.verification;

import dev.classrevive.engine.*;
import dev.classrevive.model.ClassInfo;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Asks {@link MethodSpliceWorker} (a separate JVM running only javac and ASM's class reader) to replace the failing
 * method bodies of a mix (Phase 2BD), or the {@code DIFFERENT} bodies of a mix that compiles (Phase 2BF), with other
 * engines' bodies of the same methods. Every replacement it returns was recompiled in place against the original
 * classes and its method's round trip was identical or equivalent; the caller still recompiles the whole mix and
 * decides what to keep.
 */
public final class MethodSplice {
    /** Round-trip outcomes a kept replacement may have. */
    public static final Set<String> OUTCOMES = Set.of("IDENTICAL", "EQUIVALENT_AFTER_NORMALIZATION");
    private static final Pattern CLASS_NAME = Pattern.compile("[\\p{L}\\p{N}_$]+(/[\\p{L}\\p{N}_$]+)*");
    private static final String QUALIFIED = "[\\p{L}_$][\\p{L}\\p{N}_$]*(\\.[\\p{L}_$][\\p{L}\\p{N}_$]*)*";
    private static final Pattern CANONICAL_NAMES = Pattern.compile(QUALIFIED + "(," + QUALIFIED + ")*");
    private static final Pattern DESCRIPTOR = Pattern.compile("\\([\\[\\p{L}\\p{N}_$/;]*\\)[\\[\\p{L}\\p{N}_$/;]+");
    private static final Pattern METHOD_NAME = Pattern.compile("<init>|<clinit>|[\\p{L}\\p{N}_$]+");
    private static final Pattern RULES = Pattern.compile("[a-z]+(-[a-z]+)*(,[a-z]+(-[a-z]+)*)*");

    /** The replaced body did not compile (Phase 2BD). */
    public static final String COMPILE_ERROR = "COMPILE_ERROR";
    /** The replaced body compiled but its round trip was {@code DIFFERENT} (Phase 2BF). */
    public static final String ROUND_TRIP_DIFFERENT = "ROUND_TRIP_DIFFERENT";

    /**
     * One method whose body came from {@code engine}; {@code firstLine..lastLine} is the method in the file before the
     * splice, {@code roundTrip} the outcome measured when the file was recompiled alone with that body,
     * {@code canonicalNames} the types the copied body names canonically because the file's imports did not provide them,
     * and {@code replaced} what was wrong with the body it replaced ({@link #COMPILE_ERROR} or {@link #ROUND_TRIP_DIFFERENT}).
     * Since Phase 2BG: {@code before} is the method's own outcome in place before ({@code -} for a body that did not
     * compile), {@code alsoRecovered} the DIFFERENT methods of its lambdas and anonymous or local classes that the new body
     * recovered ({@code class.name+descriptor}), and {@code bodyRepairs} the source repair rules applied to the copied body.
     */
    public record Splice(String file, String className, String method, String descriptor, String engine, String roundTrip,
                         long firstLine, long lastLine, List<String> canonicalNames, String replaced, String before,
                         List<String> alsoRecovered, List<String> bodyRepairs) {
        public Splice {
            canonicalNames = List.copyOf(canonicalNames); alsoRecovered = List.copyOf(alsoRecovered); bodyRepairs = List.copyOf(bodyRepairs);
        }
        /** A failing body replaced as written (Phase 2BD). */
        public Splice(String file, String className, String method, String descriptor, String engine, String roundTrip,
                      long firstLine, long lastLine, List<String> canonicalNames) {
            this(file, className, method, descriptor, engine, roundTrip, firstLine, lastLine, canonicalNames, COMPILE_ERROR, "-", List.of(), List.of());
        }
    }
    /** A failing method (or file, when {@code method} is "-") left as it was, and why. */
    public record Unspliced(String file, String className, String method, String reason) {}
    /** {@code files} maps each spliced source path to its new copy. */
    public record Proposal(EngineResult.Status status, List<Splice> splices, List<Unspliced> unspliced, Map<String,Path> files) {
        public Proposal {
            splices = List.copyOf(splices); unspliced = List.copyOf(unspliced);
            files = Collections.unmodifiableMap(new TreeMap<>(files));
        }
    }

    /**
     * @param originalClasses the normalized class JAR the engines decompiled; spliced files are recompiled against it
     * @param alternatives    engine id to that engine's sources root; a failing file's other versions are read from there
     */
    public Proposal propose(Path sources, Path work, List<ClassInfo> originals, Duration timeout, List<Path> dependencies,
                            Path originalClasses, Map<String,Path> alternatives) throws IOException {
        return propose(sources, work, originals, timeout, dependencies, originalClasses, alternatives, null);
    }

    /**
     * Phase 2BF: for a mix that compiles, replaces bodies whose round trip is {@code DIFFERENT}. Each kept replacement
     * turned its method {@code IDENTICAL} or equivalent when the file, otherwise as it is, was recompiled alone against
     * the original classes, and no other method of the file got worse.
     *
     * @param different the methods whose round trip was {@code DIFFERENT} in the compiled mix, as {@link ClassRoundTrip}s
     */
    public Proposal proposeForDifferent(Path sources, Path work, List<ClassInfo> originals, Duration timeout, List<Path> dependencies,
                                        Path originalClasses, Map<String,Path> alternatives, List<ClassRoundTrip> different) throws IOException {
        var targets = new StringBuilder(); int count = 0;
        for (var type : different) {
            for (var method : type.methods()) {
                if (method.outcome() != MethodRoundTrip.Outcome.DIFFERENT) continue;
                if (!CLASS_NAME.matcher(type.className()).matches() || !METHOD_NAME.matcher(method.name()).matches()
                    || !DESCRIPTOR.matcher(method.descriptor()).matches()) throw new IOException("Invalid round-trip method");
                if (++count > MethodSpliceWorker.MAX_TARGETS) throw new IOException("Too many round-trip targets");
                targets.append(type.className()).append('\t').append(method.name()).append('\t').append(method.descriptor()).append('\n');
            }
        }
        if (count == 0) return new Proposal(EngineResult.Status.OUTPUT_PRODUCED, List.of(), List.of(), Map.of());
        return propose(sources, work, originals, timeout, dependencies, originalClasses, alternatives, targets.toString());
    }

    private Proposal propose(Path sources, Path work, List<ClassInfo> originals, Duration timeout, List<Path> dependencies,
                             Path originalClasses, Map<String,Path> alternatives, String targets) throws IOException {
        sources = sources.toAbsolutePath().normalize(); work = work.toAbsolutePath().normalize();
        int release = CompilerVerifier.release(originals);
        if (release < 0 || alternatives.isEmpty()) return new Proposal(EngineResult.Status.FAILED, List.of(), List.of(), Map.of());
        for (var id : alternatives.keySet()) if (!id.matches("[a-z][a-z0-9-]{0,31}")) throw new IOException("Invalid alternative id");
        // The worker reads the DIFFERENT methods from a file next to its work folder, never from the mix.
        Path targetList = targets == null ? null : work.resolveSibling(work.getFileName() + "-targets.tsv");
        if (targetList != null) Files.writeString(targetList, targets, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        String replaced = targets == null ? COMPILE_ERROR : ROUND_TRIP_DIFFERENT;
        var classpath = new ArrayList<String>();
        try {
            // asm-util: MethodRoundTrip names the first divergence with Printer.OPCODES (Phase 2AT).
            for (var type : List.of(MethodSpliceWorker.class, org.objectweb.asm.ClassReader.class, org.objectweb.asm.tree.ClassNode.class,
                    org.objectweb.asm.util.Printer.class))
                classpath.add(Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString());
        } catch (Exception error) { throw new IOException("Cannot locate method composition worker", error); }
        var names = new HashSet<String>(); originals.forEach(info -> names.add(info.name()));
        var dependencyPaths = DependencyClasspath.prepare(dependencies, work.resolveSibling(work.getFileName() + "-dependencies"), names);
        // The worker stops starting new compilations after three quarters of the timeout, so it can still write its logs.
        long budget = Math.max(1, timeout.toMillis() / 4 * 3);
        Path jar = originalClasses.toAbsolutePath().normalize();
        var roots = new TreeMap<>(alternatives);
        var engine = new DecompilerEngine() {
            public String id() { return "javac-splice"; }
            public String version() { return System.getProperty("java.version"); }
            public List<String> command(Path input, Path output) {
                var command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-Xmx512m", "-XX:MaxMetaspaceSize=128m", "-XX:ActiveProcessorCount=2", "-Dfile.encoding=UTF-8",
                    "-cp", String.join(java.io.File.pathSeparator, classpath), MethodSpliceWorker.class.getName(),
                    input.toString(), output.toString(), Integer.toString(release), jar.toString(), Long.toString(budget),
                    targetList == null ? "-" : targetList.toString(), Integer.toString(roots.size())));
                roots.forEach((id, root) -> { command.add(id); command.add(root.toAbsolutePath().normalize().toString()); });
                dependencyPaths.forEach(path -> command.add(path.toString())); return command;
            }
        };
        var result = new ProcessRunner().runMethodSplice(engine, sources, work, timeout);
        Path splicesLog = work.resolve("splices.tsv"), unsplicedLog = work.resolve("unspliced.tsv");
        if (result.exitCode() != 0 || !Files.isRegularFile(splicesLog, LinkOption.NOFOLLOW_LINKS) || !Files.isRegularFile(unsplicedLog, LinkOption.NOFOLLOW_LINKS))
            return new Proposal(result.status(), List.of(), List.of(), Map.of());
        var splices = new ArrayList<Splice>();
        int maxSplices = targets == null ? MethodSpliceWorker.MAX_FILES * MethodSpliceWorker.MAX_METHODS
            : MethodSpliceWorker.MAX_DIFFERENT_FILES * MethodSpliceWorker.MAX_DIFFERENT_METHODS;
        // In the DIFFERENT-body mode, only listed methods may be replaced.
        var listed = targets == null ? null : new HashSet<>(Arrays.asList(targets.split("\n")));
        for (var fields : rows(splicesLog, 12, maxSplices)) {
            // A replacement in a compiling file may leave its own method DIFFERENT (never worse) only when it recovered a
            // lambda or anonymous-class method of its body, and every method it names recovered must have been listed.
            var recovered = fields[10].isEmpty() ? List.<String>of() : List.of(fields[10].split(","));
            boolean outcome = OUTCOMES.contains(fields[5]) || listed != null && fields[5].equals("DIFFERENT") && !recovered.isEmpty();
            boolean before = listed == null ? fields[9].equals("-") : OUTCOMES.contains(fields[9]) || fields[9].equals("DIFFERENT");
            boolean ownListed = listed == null || listed.contains(fields[1] + "\t" + fields[2] + "\t" + fields[3]);
            boolean recoveredListed = recovered.stream().allMatch(key -> listed != null && listed.contains(targetLine(key)));
            if (!CLASS_NAME.matcher(fields[1]).matches() || !DESCRIPTOR.matcher(fields[3]).matches() || !roots.containsKey(fields[4])
                || !outcome || !before || !fields[8].isEmpty() && !CANONICAL_NAMES.matcher(fields[8]).matches()
                || !(ownListed || !recovered.isEmpty()) || !recoveredListed || !fields[11].isEmpty() && !RULES.matcher(fields[11]).matches())
                throw new IOException("Invalid method composition log");
            long first = number(fields[6]), last = number(fields[7]);
            if (first < 1 || last < first) throw new IOException("Invalid method composition position");
            splices.add(new Splice(fields[0], fields[1], fields[2], fields[3], fields[4], fields[5], first, last,
                fields[8].isEmpty() ? List.of() : List.of(fields[8].split(",")), replaced, fields[9], recovered,
                fields[11].isEmpty() ? List.of() : List.of(fields[11].split(","))));
        }
        var unspliced = new ArrayList<Unspliced>();
        for (var fields : rows(unsplicedLog, 4, MethodSpliceWorker.MAX_ROWS))
            unspliced.add(new Unspliced(fields[0], fields[1], fields[2], fields[3]));
        // Every spliced path must name a mix file and have exactly one new copy; nothing else may be written.
        Path output = work.resolve("spliced").resolve("sources");
        var files = new TreeMap<String,Path>();
        for (var splice : splices) {
            Path original = sources.resolve(splice.file()).normalize(), spliced = output.resolve(splice.file()).normalize();
            if (!original.startsWith(sources) || !spliced.startsWith(output) || !Files.isRegularFile(original, LinkOption.NOFOLLOW_LINKS)
                || !Files.isRegularFile(spliced, LinkOption.NOFOLLOW_LINKS) || Files.size(spliced) > 16L * 1024 * 1024)
                throw new IOException("Invalid spliced source");
            files.put(splice.file(), spliced);
        }
        try (var paths = Files.walk(work.resolve("spliced"))) {
            for (var path : paths.toList()) {
                if (Files.isSymbolicLink(path)) throw new IOException("Unsafe spliced source tree");
                if (Files.isRegularFile(path) && (!path.startsWith(output) || !files.containsKey(output.relativize(path).toString().replace('\\', '/'))))
                    throw new IOException("Unlisted spliced source");
            }
        }
        return new Proposal(result.status(), splices, unspliced, files);
    }

    /** {@code class.name(desc)} as the target list's {@code class<TAB>name<TAB>desc} line; "" when malformed. */
    private static String targetLine(String key) {
        int dot = key.indexOf('.'), paren = key.indexOf('(', dot + 1);
        return dot <= 0 || paren < 0 ? "" : key.substring(0, dot) + "\t" + key.substring(dot + 1, paren) + "\t" + key.substring(paren);
    }

    private static List<String[]> rows(Path path, int columns, int limit) throws IOException {
        if (Files.size(path) > 4L * 1024 * 1024) throw new IOException("Method composition log exceeds limit");
        var rows = new ArrayList<String[]>();
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            if (line.isEmpty()) continue;
            String[] fields = line.split("\t", -1);
            if (fields.length != columns || rows.size() >= limit) throw new IOException("Invalid method composition log");
            for (int i = 0; i < fields.length; i++) fields[i] = SourceRepair.unescape(fields[i]);
            if (!CompilerVerifier.safeSourcePath(fields[0]) || !fields[1].equals("-") && !CLASS_NAME.matcher(fields[1]).matches())
                throw new IOException("Invalid method composition log");
            rows.add(fields);
        }
        return rows;
    }

    private static long number(String value) throws IOException {
        try { return Long.parseLong(value); } catch (NumberFormatException error) { throw new IOException("Invalid method composition position"); }
    }
}
