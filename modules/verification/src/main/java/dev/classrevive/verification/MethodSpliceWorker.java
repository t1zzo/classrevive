package dev.classrevive.verification;

import com.sun.source.tree.*;
import com.sun.source.util.*;
import java.io.*;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.function.Function;
import java.util.regex.Pattern;
import javax.lang.model.element.*;
import javax.lang.model.type.*;
import javax.lang.model.util.*;
import javax.tools.*;

/**
 * Per-method composition (Phase 2BD). For each file that still fails in the selected mix, the bodies of the methods
 * javac reports errors in are taken from another engine's version of the same file, one method at a time. A body is
 * kept only when the file recompiles in place against the original classes, every name in the copied body resolves to
 * the same declaration as in its own engine's file, and the per-method round trip proves the method's bytecode
 * identical or equivalent to the original while no other method of the file gets worse.
 *
 * <p>Runs only the JDK compiler, and ASM's class reader on the bytes javac produced and on the original class JAR;
 * never loads or runs target classes or annotation processors. Methods are matched by owner class, name and erased
 * descriptor. Declarations, imports, fields and initializers are never copied: only a method body (and, when the
 * engines named them differently, its parameter names) changes. A type name the file's imports do not provide is
 * written with its canonical name inside the copied body instead of adding an import.
 *
 * <p>Phase 2BF adds a second mode for a mix that already compiles: the targets are the methods whose round trip is
 * {@code DIFFERENT}, each file is recompiled alone as it is (no stubs) to measure the floor, and another engine's body
 * is kept only when the same proof turns that method {@code IDENTICAL} or equivalent with no other method worse.
 * Phase 2BG maps a {@code DIFFERENT} lambda or anonymous-class method to the written method that holds it, and repairs a
 * copied body that fails in place only by errors the {@link SourceRepairWorker} rules fix. Phase 2BI then tries the
 * method's own body and every refused body that compiled without the {@code checkcast}s the original does not have
 * (javac 9's casts of generic arguments, redundant casts and upcasts), under the same proof.
 *
 * <p>Arguments: mix sources, output folder, release, original class JAR, time budget in milliseconds, {@code -} (failing
 * methods) or a file listing the {@code DIFFERENT} methods as {@code class<TAB>name<TAB>descriptor} lines, the number of
 * alternatives, an engine id and sources root per alternative, then dependency classpath entries. Writes the spliced
 * files under {@code output/sources}, and {@code splices.tsv} and {@code unspliced.tsv} next to the output folder.
 */
public final class MethodSpliceWorker {
    static final int MAX_FILES = 64, MAX_METHODS = 32, MAX_COMPILES = 400, MAX_DIAGNOSTICS = 1000;
    /** Bounds of the DIFFERENT-body mode (Phase 2BF), where every file of a large library may hold targets. */
    static final int MAX_DIFFERENT_FILES = 2000, MAX_DIFFERENT_METHODS = 128, MAX_DIFFERENT_COMPILES = 6000, MAX_TARGETS = 100_000;
    /** Rows of {@code unspliced.tsv} in both modes; {@code splices.tsv} is bounded by the file and method limits. */
    static final int MAX_ROWS = 20_000;
    private static final String STUB = "{ throw null; }";
    /** javac names anonymous and local classes {@code Outer$1} and {@code Outer$1Local}; member names never start with a digit. */
    private static final Pattern ANONYMOUS_OR_LOCAL = Pattern.compile("\\$\\d");

    /** Syntactic facts that decide javac's numbering of anonymous classes and lambda methods in the enclosing class. */
    record Facts(List<String> classes, int lambdas, int references) {}
    /** A method with a body, declared directly in a top-level or member class; {@code key} is owner.name(erased descriptor). */
    record Method(String owner, String name, String key, String jvmDescriptor, int bodyStart, int bodyEnd, List<String> parameters,
                  List<int[]> parameterSpans, Facts facts, String body, String stub, long firstLine, long lastLine) {}
    /** The declaration each name in a body resolves to, in source order, with offsets relative to the body. */
    record Symbols(List<String> keys, List<Integer> offsets) {}
    /** A simple type name in a body (offsets relative to the body) and the canonical name it resolved to. */
    record TypeName(int start, int end, String simple, String canonical) {}
    /** Another engine's version of a method, with its errors and the declarations its names resolved to in its own file. */
    record Option(String engine, Method method, int errors, Symbols symbols, List<TypeName> typeNames, String dollarName) {}
    /**
     * The body actually tried for a method: the option's, possibly with some type names written canonically and, since
     * Phase 2BG, with source repair edits. {@code origin[i]} is the offset in the option's body that character {@code i}
     * comes from, or -1 when a repair inserted it; {@code removed} holds the option's offsets a repair took out.
     */
    private record Choice(Option option, String body, List<String> qualified, int[] origin, Set<Integer> removed, List<String> repairs) {
        static Choice of(Option option) {
            return new Choice(option, option.method().body(), List.of(), java.util.stream.IntStream.range(0, option.method().body().length()).toArray(),
                Set.of(), List.of());
        }
        /** The option's offset of the body character at {@code at}: -1 when a repair inserted it, -2 outside the body. */
        int originOf(int at) { return at >= 0 && at < origin.length ? origin[at] : -2; }
    }
    /**
     * A file and the methods whose bodies may be replaced. {@code baseline} is null for failing files, whose baseline is
     * the file with those bodies stubbed; for a file of a compiling mix it is the file compiled alone as it is, and
     * {@code groups} maps each method (owner.name+JVM descriptor) to the DIFFERENT methods its body holds in place: its own
     * and those of its lambdas and anonymous or local classes (Phase 2BG).
     */
    private record BaseFile(String file, String text, List<Method> errors, List<String> topLevel, Compiled baseline, Map<String,Set<String>> groups,
                            Map<String,Own> own) {
        BaseFile(String file, String text, List<Method> errors, List<String> topLevel) { this(file, text, errors, topLevel, null, Map.of(), Map.of()); }
        boolean stubbed() { return baseline == null; }
    }
    /**
     * A DIFFERENT method's own body in a compiling file (Phase 2BI), as an option of no engine yet, and the edits (file
     * offsets) that take away the casts its recompiled bytecode has more of than the original.
     */
    private record Own(Option option, List<SourceRepairWorker.Edit> casts) {}
    private record Compiled(boolean parsed, List<Diagnostic<? extends JavaFileObject>> errors, Map<String,byte[]> classes, Object data) {}
    private record Change(int start, int end, String text, Method body) {}
    private record Built(String text, Map<Method,Integer> bodyStarts) {}
    /** An engine's version of a file analyzed alone; {@code sameFile} when it is the base file itself, which is not analyzed again. */
    private record Alternative(Map<String,Option> options, Set<String> ambiguous, boolean sameFile) {}
    /** What the attributed body of a choice that compiled in place gives: its names, and the sites of casts an edit can take away. */
    private record Probed(Symbols symbols, List<SourceRepairWorker.CastSite> casts) {}
    /**
     * Why a choice was refused (null when proved), the simple type names that did not resolve as in its engine's file,
     * the choice with source repair edits when its only errors lie in the copied body and a repair rule applies, and
     * (Phase 2BI) when it compiled but the round trip refused it, the choice with the cast edits of
     * {@link SourceRepairWorker#castSites} for the classes its DIFFERENT methods check-cast more often than the original.
     */
    private record Verdict(String refusal, Set<String> unresolvedTypes, Compiled compiled, Choice repaired, Choice uncast) {
        Verdict(String refusal, Set<String> unresolvedTypes, Compiled compiled) { this(refusal, unresolvedTypes, compiled, null, null); }
        Verdict(String refusal, Set<String> unresolvedTypes, Compiled compiled, Choice repaired) { this(refusal, unresolvedTypes, compiled, repaired, null); }
    }
    /** The last choice tried for an option (after canonical names and repairs), its verdict and the refusal text. */
    private record Tried(Choice choice, Verdict verdict, String refusal) {}
    /** Repair rounds on one copied body (Phase 2BG): javac reports flow errors only once attribution errors are gone. */
    static final int MAX_BODY_REPAIR_ROUNDS = 2;

    public static void main(String[] args) throws Exception {
        Path mix = Path.of(args[0]).toAbsolutePath().normalize(), output = Path.of(args[1]).toAbsolutePath().normalize();
        long deadline = System.nanoTime() + Long.parseLong(args[4]) * 1_000_000L;
        Path targets = args[5].equals("-") ? null : Path.of(args[5]);
        int count = Integer.parseInt(args[6]);
        var alternatives = new TreeMap<String,Path>();
        for (int i = 0; i < count; i++) alternatives.put(args[7 + 2 * i], Path.of(args[8 + 2 * i]).toAbsolutePath().normalize());
        var dependencies = new ArrayList<Path>();
        for (int i = 7 + 2 * count; i < args.length; i++) dependencies.add(Path.of(args[i]));
        var compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) throw new IllegalStateException("A JDK is required for method composition");
        Path empty = Files.createDirectory(output.getParent().resolve("empty-sourcepath"));
        var splices = new StringBuilder(); var unspliced = new StringBuilder();
        try (var mixManager = compiler.getStandardFileManager(null, Locale.ROOT, StandardCharsets.UTF_8);
             var isolated = compiler.getStandardFileManager(null, Locale.ROOT, StandardCharsets.UTF_8)) {
            JdkRuntimeClasses.configure(mixManager, args[2], dependencies, empty);
            mixManager.setLocation(StandardLocation.SOURCE_PATH, List.of(empty.toFile()));
            // Each file is recompiled alone against the original classes, so other failing files cannot hide its result.
            var classpath = new ArrayList<Path>(); classpath.add(Path.of(args[3])); classpath.addAll(dependencies);
            JdkRuntimeClasses.configure(isolated, args[2], classpath, empty);
            isolated.setLocation(StandardLocation.SOURCE_PATH, List.of(empty.toFile()));
            var worker = new MethodSpliceWorker(compiler, isolated, args[2], deadline, MethodRoundTrip.readJar(Path.of(args[3])), splices, unspliced,
                targets == null ? MAX_COMPILES : MAX_DIFFERENT_COMPILES);
            if (targets == null) {
                for (var base : worker.failingMethods(mix, mixManager)) worker.composeSafely(base, alternatives, output);
            } else {
                // A mix that compiles: each file holding DIFFERENT methods is measured alone first, then composed.
                int files = 0;
                for (var file : worker.differentFiles(mix, readTargets(targets)).entrySet()) {
                    if (files++ == MAX_DIFFERENT_FILES) { worker.refuse(file.getKey(), null, "file-budget-exceeded"); continue; }
                    if (worker.expired()) { worker.refuse(file.getKey(), null, "budget-exceeded"); continue; }
                    BaseFile base;
                    try { base = worker.differentMethods(mix, file.getKey(), file.getValue()); }
                    catch (RuntimeException error) { worker.failed(file.getKey(), error); continue; }
                    if (base != null) worker.composeSafely(base, alternatives, output);
                }
            }
        }
        Files.writeString(output.getParent().resolve("splices.tsv"), splices, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        Files.writeString(output.getParent().resolve("unspliced.tsv"), unspliced, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
    }

    private final JavaCompiler compiler; private final StandardJavaFileManager isolated; private final List<String> options;
    private final long deadline; private final Map<String,byte[]> originals; private final StringBuilder splices, unspliced;
    private final int maxCompiles;
    /** Original classes read without code, for EnclosingMethod and method lists (null when absent or unreadable). */
    private final Map<String,org.objectweb.asm.tree.ClassNode> nodes = new HashMap<>();
    private int compiles, refusals;

    private MethodSpliceWorker(JavaCompiler compiler, StandardJavaFileManager isolated, String release, long deadline,
                               Map<String,byte[]> originals, StringBuilder splices, StringBuilder unspliced, int maxCompiles) {
        this.compiler = compiler; this.isolated = isolated; this.deadline = deadline; this.originals = originals;
        this.splices = splices; this.unspliced = unspliced; this.maxCompiles = maxCompiles;
        // -Xlint:cast only adds warnings (Phase 2BI reads its redundant casts); it changes no error and no generated class.
        options = List.of("--release", release, "-proc:none", "-implicit:none", "-encoding", "UTF-8", "-Xlint:-options", "-Xlint:cast",
            "-Xmaxerrs", Integer.toString(MAX_DIAGNOSTICS), "-Xmaxwarns", Integer.toString(MAX_DIAGNOSTICS));
    }

    private boolean expired() { return System.nanoTime() > deadline || compiles >= maxCompiles; }

    private void composeSafely(BaseFile base, Map<String,Path> alternatives, Path output) throws IOException {
        if (expired()) { refuse(base.file(), null, "budget-exceeded"); return; }
        try { compose(base, alternatives, output.resolve("sources")); }
        catch (RuntimeException error) { failed(base.file(), error); }
    }

    /** The file is left as it was; the cause goes to the worker's own log. */
    private void failed(String file, RuntimeException error) {
        System.err.println("analysis failed for " + file + ": " + error + Arrays.stream(error.getStackTrace())
            .filter(frame -> frame.getClassName().startsWith("dev.")).limit(4).map(frame -> "\n  at " + frame).reduce("", String::concat));
        refuse(file, null, "analysis-failed:" + error.getClass().getSimpleName());
    }

    /** {@code class.name+descriptor} keys of the DIFFERENT methods the host listed; malformed lines are refused. */
    private static Set<String> readTargets(Path path) throws IOException {
        var keys = new TreeSet<String>();
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            if (line.isEmpty()) continue;
            String[] fields = line.split("\t", -1);
            if (fields.length != 3 || fields[0].isEmpty() || fields[0].contains(".") || fields[0].contains("\\") || fields[1].isEmpty()
                || !fields[2].startsWith("(") || keys.size() >= MAX_TARGETS) throw new IllegalArgumentException("Invalid target list");
            keys.add(fields[0] + "." + fields[1] + fields[2]);
        }
        return keys;
    }

    /**
     * Groups target keys by the method written in a top-level or member class whose body holds them, and those methods by
     * the mix file that declares their class: the file named after the class, or after an enclosing class for a member
     * class ({@code a/B$C} in {@code a/B.java}). Since Phase 2BG a lambda method or a method of an anonymous or local class
     * belongs to its enclosing method (see {@link #unitsOf}). Keys with no such method or file are dropped.
     */
    private TreeMap<String,Map<String,Set<String>>> differentFiles(Path mix, Set<String> keys) {
        var byFile = new TreeMap<String,Map<String,Set<String>>>();
        for (var key : keys) {
            for (var unit : unitsOf(key, 0)) {
                String name = unit.substring(0, unit.indexOf('.'));
                while (true) {
                    Path path = mix.resolve(name + ".java").normalize();
                    if (path.startsWith(mix) && Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                        byFile.computeIfAbsent(name + ".java", ignored -> new TreeMap<>()).computeIfAbsent(unit, ignored -> new TreeSet<>()).add(key);
                        break;
                    }
                    int dollar = name.lastIndexOf('$');
                    if (dollar <= name.lastIndexOf('/') + 1) break;
                    name = name.substring(0, dollar);
                }
            }
        }
        return byFile;
    }

    /**
     * The methods written in a top-level or member class whose bodies hold {@code key}'s bytecode, read from the original
     * classes (Phase 2BG): the method itself; for {@code lambda$m$N}, every method {@code m} of its class ({@code new}: the
     * constructors); for a method of an anonymous or local class, the method its {@code EnclosingMethod} attribute names.
     * Class initializers, {@code access$} methods, lambdas javac 8 named {@code lambda$null$N} or {@code lambda$static$N},
     * and classes declared in an initializer have none.
     */
    private Set<String> unitsOf(String key, int depth) {
        int dot = key.indexOf('.'), paren = key.indexOf('(', dot);
        if (depth > 8 || dot <= 0 || paren < 0) return Set.of();
        String owner = key.substring(0, dot), name = key.substring(dot + 1, paren);
        var node = node(owner);
        if (node == null || name.equals("<clinit>") || name.startsWith("access$")) return Set.of();
        if (name.startsWith("lambda$")) {
            String[] parts = name.split("\\$", -1);
            if (parts.length != 3 || parts[1].isEmpty() || parts[1].equals("static") || parts[1].equals("null")) return Set.of();
            String enclosing = parts[1].equals("new") ? "<init>" : parts[1];
            var result = new TreeSet<String>();
            for (var method : node.methods) if (method.name.equals(enclosing)) result.addAll(unitsOf(owner + "." + method.name + method.desc, depth + 1));
            return result;
        }
        if (node.outerMethod != null) return unitsOf(node.outerClass + "." + node.outerMethod + node.outerMethodDesc, depth + 1);
        // An anonymous or local class without an enclosing method is declared in an initializer.
        if (node.outerClass != null || ANONYMOUS_OR_LOCAL.matcher(owner.substring(owner.lastIndexOf('/') + 1)).find()) return Set.of();
        return Set.of(key);
    }

    /** The original class's declarations (no code), or null when it is not an original class or cannot be read. */
    private org.objectweb.asm.tree.ClassNode node(String name) {
        if (nodes.containsKey(name)) return nodes.get(name);
        org.objectweb.asm.tree.ClassNode node = null;
        byte[] bytes = originals.get(name);
        if (bytes != null) {
            try {
                node = new org.objectweb.asm.tree.ClassNode();
                new org.objectweb.asm.ClassReader(bytes).accept(node, org.objectweb.asm.ClassReader.SKIP_CODE
                    | org.objectweb.asm.ClassReader.SKIP_DEBUG | org.objectweb.asm.ClassReader.SKIP_FRAMES);
            } catch (RuntimeException malformed) { node = null; }
        }
        nodes.put(name, node);
        return node;
    }

    /**
     * Compiles one file of a compiling mix alone against the original classes, as it is, and returns the methods whose
     * bodies hold DIFFERENT methods in place (their own, or since Phase 2BG their lambdas' or anonymous classes'), with
     * that compilation as the baseline. Null when there is nothing to do.
     */
    private BaseFile differentMethods(Path mix, String file, Map<String,Set<String>> units) throws IOException {
        Path path = mix.resolve(file).normalize();
        if (!path.startsWith(mix) || Files.size(path) > 16L * 1024 * 1024) { refuse(file, null, "unreadable"); return null; }
        String text;
        try { text = Files.readString(path, StandardCharsets.UTF_8); } catch (IOException unreadable) { refuse(file, null, "unreadable"); return null; }
        // Phase 2BI: a listed method's own body with its cast sites, read while the file is attributed (code generation
        // rewrites the trees); which sites to edit is known once the classes are compared below.
        var own = new HashMap<Method,Map.Entry<Option,List<SourceRepairWorker.CastSite>>>();
        var compiled = compile(file, text, true, attributed -> {
            var methods = new ArrayList<Method>();
            for (var methodPath : attributed.methods()) {
                var method = attributed.describe(methodPath);
                if (method == null) continue;
                methods.add(method);
                if (!units.containsKey(method.owner() + "." + method.name() + method.jvmDescriptor()) || !attributed.errors.isEmpty()) continue;
                var body = new TreePath(methodPath, ((MethodTree) methodPath.getLeaf()).getBody());
                var sites = SourceRepairWorker.castSites(attributed.task, attributed.warnings, body);
                if (sites.isEmpty()) continue;
                own.put(method, Map.entry(new Option("", method, 0, attributed.symbols(body, method.bodyStart()),
                    attributed.typeNames(body, method.bodyStart()), attributed.dollarName(body)), sites));
            }
            return Map.entry(List.copyOf(methods), attributed.topLevel());
        });
        if (compiled == null) { refuse(file, null, "budget-exceeded"); return null; }
        if (!compiled.parsed() || !compiled.errors().isEmpty()) {
            // It compiled with the other decompiled files; alone against the original classes it may not (a signature differs).
            if (!compiled.errors().isEmpty()) System.err.println(file + " alone: line " + compiled.errors().get(0).getLineNumber() + ": "
                + compiled.errors().get(0).getMessage(Locale.ROOT).lines().findFirst().orElse(""));
            refuse(file, null, "file-does-not-compile-alone:" + (compiled.errors().isEmpty() ? "syntax" : compiled.errors().get(0).getCode()));
            return null;
        }
        @SuppressWarnings("unchecked") var data = (Map.Entry<List<Method>,List<String>>) compiled.data();
        var outcomes = outcomes(data.getValue(), compiled.classes());
        var targets = new ArrayList<Method>(); var groups = new TreeMap<String,Set<String>>();
        for (var method : data.getKey()) {
            String key = method.owner() + "." + method.name() + method.jvmDescriptor();
            if (!units.containsKey(key)) continue;
            // Compiled alone against the original classes, a body can come out identical where the whole mix did not.
            var different = new TreeSet<String>();
            for (var held : units.get(key)) if (outcomes.getOrDefault(held, -1) == 0) different.add(held);
            if (different.isEmpty()) refuse(file, method, "not-different-in-place");
            else if (targets.size() == MAX_DIFFERENT_METHODS) refuse(file, method, "method-budget-exceeded");
            else { targets.add(method); groups.put(key, Collections.unmodifiableSet(different)); }
        }
        var owned = new TreeMap<String,Own>();
        for (var method : targets) {
            var found = own.get(method);
            if (found == null) continue;
            var edits = SourceRepairWorker.castEdits(found.getValue(), extraCasts(groups.get(method.owner() + "." + method.name() + method.jvmDescriptor()), compiled.classes()));
            if (!edits.isEmpty()) owned.put(method.key(), new Own(found.getKey(), edits));
        }
        return targets.isEmpty() ? null : new BaseFile(file, text, List.copyOf(targets), data.getValue(), compiled, Collections.unmodifiableMap(groups),
            Collections.unmodifiableMap(owned));
    }

    /** Analyzes the whole mix as the compiler worker sees it and maps each failing file's errors to the methods holding them. */
    private List<BaseFile> failingMethods(Path mix, StandardJavaFileManager manager) throws IOException {
        var files = CompilerWorker.sourceFiles(mix);
        if (files.isEmpty()) return List.of();
        var diagnostics = new ArrayList<Diagnostic<? extends JavaFileObject>>();
        boolean[] entered = {false}, syntax = {false};
        var task = (JavacTask) compiler.getTask(null, manager, diagnostic -> {
            if (diagnostic.getKind() != Diagnostic.Kind.ERROR) return;
            if (!entered[0]) syntax[0] = true;
            if (diagnostics.size() < MAX_DIAGNOSTICS) diagnostics.add(diagnostic);
        }, options, null, manager.getJavaFileObjectsFromFiles(files));
        task.addTaskListener(new TaskListener() {
            @Override public void started(TaskEvent event) { if (event.getKind() == TaskEvent.Kind.ENTER) entered[0] = true; }
        });
        var units = new TreeMap<String,CompilationUnitTree>();
        for (var unit : task.parse()) { String name = relative(unit.getSourceFile(), mix); if (name != null) units.put(name, unit); }
        // A syntax error stops javac before attribution, so the other files' errors are unknown.
        if (syntax[0]) return List.of();
        task.analyze();
        var byFile = new TreeMap<String,List<Diagnostic<? extends JavaFileObject>>>();
        for (var diagnostic : diagnostics) {
            String name = diagnostic.getSource() == null ? null : relative(diagnostic.getSource(), mix);
            if (name != null && units.containsKey(name)) byFile.computeIfAbsent(name, ignored -> new ArrayList<>()).add(diagnostic);
        }
        var result = new ArrayList<BaseFile>();
        for (var entry : byFile.entrySet()) {
            if (result.size() == MAX_FILES) { refuse(entry.getKey(), null, "file-budget-exceeded"); continue; }
            var attributed = new Attributed(task, units.get(entry.getKey()), List.of(), List.of());
            var paths = attributed.methods();
            var failing = new TreeSet<Integer>(); boolean outside = false;
            for (var diagnostic : entry.getValue()) {
                long at = diagnostic.getStartPosition() >= 0 ? diagnostic.getStartPosition() : diagnostic.getPosition();
                int holder = -1;
                for (int i = 0; i < paths.size() && holder < 0; i++) {
                    var body = ((MethodTree) paths.get(i).getLeaf()).getBody();
                    if (at >= attributed.start(body) && at < attributed.end(body)) holder = i;
                }
                if (holder < 0) outside = true; else failing.add(holder);
            }
            // An error in a declaration, field, initializer or import stays whatever the bodies are, and keeps the file
            // from compiling alone, so no body taken from another engine could be proved by the round trip.
            if (outside) { refuse(entry.getKey(), null, "errors-outside-method-bodies"); continue; }
            if (failing.size() > MAX_METHODS) { refuse(entry.getKey(), null, "too-many-failing-methods"); continue; }
            var methods = new ArrayList<Method>(); boolean described = true;
            for (int index : failing) {
                var method = attributed.describe(paths.get(index));
                if (method == null) { refuse(entry.getKey(), null, "failing-method-not-attributed"); described = false; break; }
                methods.add(method);
            }
            if (described) result.add(new BaseFile(entry.getKey(), attributed.text, List.copyOf(methods), attributed.topLevel()));
        }
        return result;
    }

    /** Replaces failing (or DIFFERENT) bodies of one file, greedily and in source order, keeping each replacement only when proved. */
    private void compose(BaseFile base, Map<String,Path> alternatives, Path output) throws IOException {
        var options = new TreeMap<String,Map<String,Option>>(); var ambiguous = new TreeMap<String,Set<String>>();
        String sameFile = null;
        for (var alternative : alternatives.entrySet()) {
            var found = alternative(alternative.getKey(), alternative.getValue(), base);
            if (found == null) continue;
            if (found.sameFile()) { if (sameFile == null) sameFile = alternative.getKey(); continue; }
            options.put(alternative.getKey(), found.options()); ambiguous.put(alternative.getKey(), found.ambiguous());
        }
        if (base.own().isEmpty() && base.errors().stream().noneMatch(method -> options.values().stream().anyMatch(found -> found.containsKey(method.key())))) {
            for (var method : base.errors()) refuse(base.file(), method, "no-matching-method");
            return;
        }
        // Baseline: every failing body stubbed, or the compiling file as it is. It must compile alone, and gives the
        // outcomes no replacement may lower.
        var chosen = new LinkedHashMap<Method,Choice>();
        var baseline = base.stubbed() ? compile(base.file(), build(base, chosen, true).text(), true, null) : base.baseline();
        if (baseline == null) { refuse(base.file(), null, "budget-exceeded"); return; }
        if (!baseline.parsed() || !baseline.errors().isEmpty()) {
            if (!baseline.errors().isEmpty()) System.err.println(base.file() + " with failing bodies stubbed: line " + baseline.errors().get(0).getLineNumber()
                + ": " + baseline.errors().get(0).getMessage(Locale.ROOT).lines().findFirst().orElse(""));
            refuse(base.file(), null, "stubbed-file-does-not-compile:" + (baseline.errors().isEmpty() ? "syntax" : baseline.errors().get(0).getCode()));
            return;
        }
        var outcomes = outcomes(base.topLevel(), baseline.classes()); var previous = outcomes;
        var classes = baseline.classes().keySet();
        for (var method : base.errors()) {
            if (expired()) { refuse(base.file(), method, "budget-exceeded"); continue; }
            var candidates = new ArrayList<Option>(); var reasons = new ArrayList<String>();
            for (var engine : options.keySet()) {
                if (ambiguous.get(engine).contains(method.key())) reasons.add(engine + ":ambiguous-declaration");
                else if (options.get(engine).containsKey(method.key())) candidates.add(options.get(engine).get(method.key()));
            }
            candidates.sort(Comparator.comparingInt(Option::errors).thenComparing(Option::engine));
            Tried accepted = null; var uncast = new ArrayList<Choice>();
            for (var option : candidates) {
                String refusal = compatible(method, option);
                if (refusal == null) {
                    var choice = Choice.of(option);
                    var verdict = attempt(base, chosen, method, choice, outcomes, classes);
                    refusal = verdict.refusal();
                    // Engines import differently (Map.Entry against an imported Entry): the type names that did not resolve
                    // as in the engine's own file are written canonically in the copied body, and the attempt is repeated.
                    if (refusal != null && !verdict.unresolvedTypes().isEmpty()) {
                        choice = qualified(option, verdict.unresolvedTypes());
                        verdict = attempt(base, chosen, method, choice, outcomes, classes);
                        if (verdict.refusal() != null) refusal += " (canonical names: " + verdict.refusal() + ")";
                    }
                    var tried = repairRounds(base, chosen, method, new Tried(choice, verdict, refusal), outcomes, classes);
                    if (tried.verdict().refusal() == null) { accepted = tried; break; }
                    if (tried.verdict().uncast() != null) uncast.add(tried.verdict().uncast());
                    refusal = tried.refusal();
                }
                reasons.add(option.engine() + ":" + refusal);
            }
            // Phase 2BI: once no body is proved as written, the bodies that compiled but whose round trip refused them are
            // tried without the checkcasts the original does not have (SourceRepairWorker.castSites): first the method's
            // own body in a compiling file (named after the engine whose file or body it is), then the other engines' in
            // the order above. Trying them last leaves every method an earlier phase proved with the body it took.
            if (accepted == null) {
                var retries = new ArrayList<Choice>();
                var own = base.own().get(method.key());
                String engine = own == null ? null : sameFile != null ? sameFile : options.entrySet().stream()
                    .filter(entry -> entry.getValue().containsKey(method.key()) && entry.getValue().get(method.key()).method().body().equals(method.body()))
                    .map(Map.Entry::getKey).findFirst().orElse(null);
                if (own != null && engine == null) reasons.add("own-body:engine-unknown");
                if (engine != null) {
                    var option = own.option();
                    retries.add(repaired(Choice.of(new Option(engine, option.method(), 0, option.symbols(), option.typeNames(), option.dollarName())),
                        own.casts(), method.bodyStart()));
                }
                retries.addAll(uncast);
                for (var choice : retries) {
                    if (expired()) { reasons.add("budget-exceeded"); break; }
                    var verdict = attempt(base, chosen, method, choice, outcomes, classes);
                    var tried = repairRounds(base, chosen, method, new Tried(choice, verdict, verdict.refusal()), outcomes, classes);
                    if (tried.verdict().refusal() == null) { accepted = tried; break; }
                    reasons.add(choice.option().engine() + (choice.option().method() == method ? ":own-body" : "") + ":without-extra-casts:" + tried.refusal());
                }
            }
            if (accepted == null) { refuse(base.file(), method, reasons.isEmpty() ? "no-matching-method" : String.join("; ", reasons)); continue; }
            var choice = accepted.choice(); var compiled = accepted.verdict().compiled();
            chosen.put(method, choice);
            previous = outcomes;
            outcomes = outcomes(base.topLevel(), compiled.classes()); classes = compiled.classes().keySet();
            String key = method.owner() + "." + method.name() + method.jvmDescriptor();
            var result = MethodRoundTrip.Outcome.values()[2 - outcomes.get(key)];
            // The method's own outcome before (a stub's means nothing), and the other DIFFERENT methods its body holds that
            // the replacement recovered (its lambdas and anonymous classes, Phase 2BG).
            String before = base.stubbed() || !previous.containsKey(key) ? "-" : MethodRoundTrip.Outcome.values()[2 - previous.get(key)].name();
            var recovered = new ArrayList<String>();
            for (var held : base.groups().getOrDefault(key, Set.of()))
                if (!held.equals(key) && previous.getOrDefault(held, 0) == 0 && outcomes.getOrDefault(held, 0) > 0) recovered.add(held);
            splices.append(SourceRepairWorker.escape(base.file())).append('\t').append(method.owner()).append('\t').append(SourceRepairWorker.escape(method.name()))
                .append('\t').append(method.jvmDescriptor()).append('\t').append(choice.option().engine()).append('\t').append(result.name())
                .append('\t').append(method.firstLine()).append('\t').append(method.lastLine())
                .append('\t').append(SourceRepairWorker.escape(String.join(",", choice.qualified())))
                .append('\t').append(before).append('\t').append(SourceRepairWorker.escape(String.join(",", recovered)))
                .append('\t').append(String.join(",", choice.repairs())).append('\n');
        }
        if (chosen.isEmpty()) return;
        // Methods left unproved keep their own (failing) bodies; only proved replacements are written.
        Path target = output.resolve(base.file()).normalize();
        if (!target.startsWith(output)) throw new IllegalArgumentException("Spliced path escapes output");
        Files.createDirectories(target.getParent());
        Files.writeString(target, build(base, chosen, false).text(), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
    }

    /**
     * Phase 2BG: a copied body whose only errors are its own and that the source repair rules fix (a raw cast, an
     * unreachable break, a final local assigned twice...) is repaired in place and tried again, under the same checks; the
     * rules preserve bytecode, and the round trip still decides.
     */
    private Tried repairRounds(BaseFile base, Map<Method,Choice> chosen, Method method, Tried tried, Map<String,Integer> outcomes,
                               Set<String> classes) throws IOException {
        var choice = tried.choice(); var verdict = tried.verdict(); String refusal = tried.refusal();
        for (int round = 1; verdict.refusal() != null && verdict.repaired() != null && round <= MAX_BODY_REPAIR_ROUNDS; round++) {
            choice = verdict.repaired();
            verdict = attempt(base, chosen, method, choice, outcomes, classes);
            if (verdict.refusal() != null) refusal += " (repaired: " + verdict.refusal() + ")";
        }
        return new Tried(choice, verdict, refusal);
    }

    /**
     * Compiles the file with {@code choice} for {@code method}, the proved choices so far and the other failing bodies
     * stubbed (or, in a compiling file, the other bodies as they are).
     */
    private Verdict attempt(BaseFile base, Map<Method,Choice> chosen, Method method, Choice choice, Map<String,Integer> before,
                            Set<String> classes) throws IOException {
        var trial = new LinkedHashMap<>(chosen); trial.put(method, choice);
        var built = build(base, trial, base.stubbed());
        int bodyStart = built.bodyStarts().get(method), bodyEnd = bodyStart + choice.body().length();
        var compiled = compile(base.file(), built.text(), true, attributed -> {
            if (!attributed.errors.isEmpty()) {
                // Only errors of the copied body itself are repaired, and only by edits inside it.
                if (attributed.errors.stream().anyMatch(error -> error.getStartPosition() < bodyStart || error.getStartPosition() >= bodyEnd)) return null;
                var edits = SourceRepairWorker.repairs(attributed.task, attributed.unit, attributed.errors, originals);
                return edits.isEmpty() || edits.stream().anyMatch(edit -> edit.start() < bodyStart || edit.end() > bodyEnd - 1) ? null : edits;
            }
            TreePath body = attributed.body(bodyStart);
            return body == null ? null : new Probed(attributed.symbols(body, bodyStart),
                SourceRepairWorker.castSites(attributed.task, attributed.warnings, body));
        });
        if (compiled == null) return new Verdict("budget-exceeded", Set.of(), null);
        if (!compiled.parsed()) return new Verdict("does-not-parse-in-place", Set.of(), null);
        var option = choice.option(); boolean asWritten = choice.qualified().isEmpty() && choice.repairs().isEmpty();
        if (!compiled.errors().isEmpty()) {
            // A type name the file's imports do not provide (or provide differently) fails to resolve at its own position.
            var unresolved = new TreeSet<String>(); String missing = null;
            for (var error : compiled.errors()) {
                long at = error.getStartPosition() - bodyStart;
                for (var name : asWritten ? option.typeNames() : List.<TypeName>of())
                    if (name.start() == at) { unresolved.add(name.simple()); if (missing == null) missing = name.canonical(); }
            }
            var first = compiled.errors().get(0);
            // The worker's log keeps the first error of each refused attempt (javac's message, first line only).
            System.err.println(base.file() + " " + method.name() + method.jvmDescriptor() + " from " + option.engine()
                + (choice.qualified().isEmpty() ? "" : " (canonical names)") + (choice.repairs().isEmpty() ? "" : " (repaired: " + String.join(",", choice.repairs()) + ")")
                + ": line " + first.getLineNumber() + ": " + first.getCode() + ": " + first.getMessage(Locale.ROOT).lines().findFirst().orElse(""));
            @SuppressWarnings("unchecked") var edits = compiled.data() instanceof List<?> list ? (List<SourceRepairWorker.Edit>) list : null;
            return new Verdict(missing != null ? "import-missing:" + missing : "does-not-compile-in-place:" + first.getCode(), unresolved, null,
                edits == null ? null : repaired(choice, edits, bodyStart));
        }
        // Imports and fields: every name must mean what it meant in the engine's own file. Names a repair inserted (a raw
        // cast's type, a captured copy) and names it took out are left out on each side; everything else is compared.
        if (!(compiled.data() instanceof Probed probed)) return new Verdict("body-not-found-in-place", Set.of(), null);
        var symbols = probed.symbols();
        var actual = new ArrayList<String>();
        for (int i = 0; i < symbols.keys().size(); i++) if (choice.originOf(symbols.offsets().get(i)) != -1) actual.add(symbols.keys().get(i));
        var expectedKeys = new ArrayList<String>(); var expectedOffsets = new ArrayList<Integer>();
        for (int i = 0; i < option.symbols().keys().size(); i++)
            if (!choice.removed().contains(option.symbols().offsets().get(i))) {
                expectedKeys.add(option.symbols().keys().get(i)); expectedOffsets.add(option.symbols().offsets().get(i));
            }
        if (actual.size() != expectedKeys.size() || !sameNames(actual, expectedKeys)) {
            int i = 0;
            while (i < actual.size() && i < expectedKeys.size() && sameName(actual.get(i), expectedKeys.get(i))) i++;
            String expected = i < expectedKeys.size() ? expectedKeys.get(i) : "";
            var unresolved = new TreeSet<String>();
            if (asWritten && expected.startsWith("type "))
                for (var name : option.typeNames()) if (name.start() == expectedOffsets.get(i)) unresolved.add(name.simple());
            return new Verdict((expected.startsWith("type ") ? "import-incompatible:" : expected.startsWith("field ") ? "field-incompatible:"
                : "names-resolve-differently:") + expected.substring(expected.indexOf(' ') + 1), unresolved, null);
        }
        if (!compiled.classes().keySet().containsAll(classes)) return new Verdict("class-lost", Set.of(), null);
        var after = outcomes(base.topLevel(), compiled.classes());
        String key = method.owner() + "." + method.name() + method.jvmDescriptor();
        if (!after.containsKey(key)) return new Verdict("method-not-in-original", Set.of(), null);
        // A failing body must itself be proved. In a compiling file, some DIFFERENT method the body holds (its own, a
        // lambda's or an anonymous class's) must be recovered, and no method, its own included, may get worse.
        boolean improved = base.stubbed() ? after.get(key) > 0 : base.groups().getOrDefault(key, Set.of(key)).stream()
            .anyMatch(held -> before.getOrDefault(held, 0) == 0 && after.getOrDefault(held, 0) > 0);
        // Phase 2BI: the round trip refused a body that compiled. Where its DIFFERENT methods check-cast a class more often
        // than the original, the edits that take those casts away give one more choice.
        var held = base.stubbed() ? Set.of(key) : base.groups().getOrDefault(key, Set.of(key));
        var edits = probed.casts().isEmpty() ? List.<SourceRepairWorker.Edit>of() : SourceRepairWorker.castEdits(probed.casts(),
            extraCasts(held.stream().filter(other -> after.getOrDefault(other, 0) == 0).toList(), compiled.classes()));
        var uncast = edits.isEmpty() ? null : repaired(choice, edits, bodyStart);
        if (!improved) return new Verdict("round-trip-different", Set.of(), null, null, uncast);
        for (var entry : before.entrySet())
            if ((!base.stubbed() || !entry.getKey().equals(key)) && after.getOrDefault(entry.getKey(), 0) < entry.getValue())
                return new Verdict((entry.getKey().equals(key) ? "method-worse:" : "other-method-changed:")
                    + entry.getKey().substring(entry.getKey().lastIndexOf('/') + 1), Set.of(), null, null, uncast);
        return new Verdict(null, Set.of(), compiled);
    }

    /**
     * The choice with source repair edits (file offsets, all inside the copied body that starts at {@code bodyStart})
     * applied. Characters a repair wrote get origin -1, except where its text holds the replaced text unchanged (a raw cast
     * around an expression) or is part of it (a cast or type arguments dropped): those keep their origin. The option's
     * characters a repair took out are added to {@code removed}.
     */
    private static Choice repaired(Choice choice, List<SourceRepairWorker.Edit> edits, int bodyStart) {
        String body = choice.body(); var text = new StringBuilder(); var origin = new ArrayList<Integer>();
        var removed = new TreeSet<>(choice.removed()); var rules = new ArrayList<>(choice.repairs());
        int last = 0;
        for (var edit : edits) {
            int start = edit.start() - bodyStart, end = edit.end() - bodyStart;
            for (int i = last; i < start; i++) { text.append(body.charAt(i)); origin.add(choice.origin()[i]); }
            String replaced = body.substring(start, end), replacement = edit.replacement();
            int inner = embedded(replacement, replaced), outer = inner >= 0 ? -1 : embedded(replaced, replacement);
            for (int i = 0; i < replacement.length(); i++) {
                text.append(replacement.charAt(i));
                origin.add(inner >= 0 && i >= inner && i < inner + replaced.length() ? choice.origin()[start + i - inner]
                    : outer >= 0 ? choice.origin()[start + outer + i] : -1);
            }
            for (int i = start; i < end; i++)
                if ((inner < 0 && (outer < 0 || i < start + outer || i >= start + outer + replacement.length())) && choice.origin()[i] >= 0)
                    removed.add(choice.origin()[i]);
            if (!rules.contains(edit.rule())) rules.add(edit.rule());
            last = end;
        }
        for (int i = last; i < body.length(); i++) { text.append(body.charAt(i)); origin.add(choice.origin()[i]); }
        return new Choice(choice.option(), text.toString(), choice.qualified(), origin.stream().mapToInt(Integer::intValue).toArray(),
            Collections.unmodifiableSet(removed), List.copyOf(rules));
    }

    private static boolean sameNames(List<String> actual, List<String> expected) {
        for (int i = 0; i < actual.size(); i++) if (!sameName(actual.get(i), expected.get(i))) return false;
        return true;
    }

    /**
     * A name means in place what it meant in the engine's own file. A name that did not resolve there ({@code ?}, next to
     * an error of that file, which a body repair may fix in place) had no meaning to compare, except a type name: the
     * imports of the two files decide it, and a type in a declaration may not show in the bytecode (Phase 2BG).
     */
    private static boolean sameName(String actual, String expected) {
        return actual.equals(expected) || expected.equals("?") && !actual.startsWith("type ");
    }

    /** Where {@code part} occurs once in {@code whole} as whole tokens (not inside a longer identifier), or -1. */
    private static int embedded(String whole, String part) {
        if (part.isEmpty()) return -1;
        int at = whole.indexOf(part);
        if (at < 0 || whole.indexOf(part, at + 1) >= 0) return -1;
        boolean before = at > 0 && Character.isJavaIdentifierPart(whole.charAt(at - 1)) && Character.isJavaIdentifierPart(part.charAt(0));
        int next = at + part.length();
        boolean after = next < whole.length() && Character.isJavaIdentifierPart(whole.charAt(next)) && Character.isJavaIdentifierPart(part.charAt(part.length() - 1));
        return before || after ? -1 : at;
    }

    /**
     * The option's body with every occurrence of the given simple type names written as their canonical names; each
     * canonical name's characters take the offset of the simple name it replaces as their origin.
     */
    private static Choice qualified(Option option, Set<String> simple) {
        String source = option.method().body(); var body = new StringBuilder(); var origin = new ArrayList<Integer>(); var names = new TreeSet<String>();
        var typeNames = new ArrayList<>(option.typeNames());
        typeNames.sort(Comparator.comparingInt(TypeName::start));
        int last = 0;
        for (var name : typeNames) {
            if (!simple.contains(name.simple()) || name.start() < last) continue;
            for (int i = last; i < name.start(); i++) { body.append(source.charAt(i)); origin.add(i); }
            for (int i = 0; i < name.canonical().length(); i++) { body.append(name.canonical().charAt(i)); origin.add(name.start()); }
            names.add(name.canonical()); last = name.end();
        }
        for (int i = last; i < source.length(); i++) { body.append(source.charAt(i)); origin.add(i); }
        return new Choice(option, body.toString(), List.copyOf(names), origin.stream().mapToInt(Integer::intValue).toArray(), Set.of(), List.of());
    }

    /** Refusals that need no compilation: equal bodies, javac-numbered constructs that differ, or synthetic names. */
    private static String compatible(Method method, Option option) {
        if (option.method().body().equals(method.body())) return "same-body";
        if (option.dollarName() != null) return "synthetic-name:" + option.dollarName();
        // javac numbers anonymous and local classes and lambda methods per class in order; a body with a different
        // count or order would shift the names used by every later method of the class.
        if (!option.method().facts().classes().equals(method.facts().classes())) return "anonymous-or-local-classes-differ";
        if (option.method().facts().lambdas() != method.facts().lambdas() || option.method().facts().references() != method.facts().references())
            return "lambdas-or-method-references-differ";
        for (int i = 0; i < method.parameters().size(); i++)
            if (!option.method().parameters().get(i).equals(method.parameters().get(i)) && method.parameterSpans().get(i) == null)
                return "parameter-name-unlocated";
        return null;
    }

    /**
     * The classes the compiled methods {@code keys} ({@code owner.name+descriptor}) check-cast more often than the
     * original methods do (Phase 2BI), as the instructions name them.
     */
    private Set<String> extraCasts(Collection<String> keys, Map<String,byte[]> compiled) {
        var result = new TreeSet<String>();
        for (var key : keys) {
            int dot = key.indexOf('.'), paren = key.indexOf('(', dot);
            if (dot <= 0 || paren < 0) continue;
            String owner = key.substring(0, dot), name = key.substring(dot + 1, paren), descriptor = key.substring(paren);
            var before = checkcasts(originals.get(owner), name, descriptor);
            checkcasts(compiled.get(owner), name, descriptor).forEach((type, count) -> { if (count > before.getOrDefault(type, 0)) result.add(type); });
        }
        return result;
    }

    /** How many {@code checkcast} instructions per class a method of the class file holds; empty when absent or unreadable. */
    private static Map<String,Integer> checkcasts(byte[] bytes, String name, String descriptor) {
        var counts = new HashMap<String,Integer>();
        if (bytes == null) return counts;
        var node = new org.objectweb.asm.tree.ClassNode();
        try { new org.objectweb.asm.ClassReader(bytes).accept(node, org.objectweb.asm.ClassReader.SKIP_DEBUG | org.objectweb.asm.ClassReader.SKIP_FRAMES); }
        catch (RuntimeException malformed) { return counts; }
        for (var method : node.methods)
            if (method.name.equals(name) && method.desc.equals(descriptor))
                for (var insn : method.instructions)
                    if (insn.getOpcode() == org.objectweb.asm.Opcodes.CHECKCAST) counts.merge(((org.objectweb.asm.tree.TypeInsnNode) insn).desc, 1, Integer::sum);
        return counts;
    }

    /** Per-method outcomes (2 identical, 1 equivalent, 0 different) for the original classes declared in the file. */
    private Map<String,Integer> outcomes(List<String> topLevel, Map<String,byte[]> compiled) {
        var result = new TreeMap<String,Integer>();
        // Each side's context from the file's classes (Phase 2BH): a member class calls its outer class's accessors.
        var own = new ArrayList<byte[]>();
        for (var entry : originals.entrySet())
            if (topLevel.stream().anyMatch(top -> entry.getKey().equals(top) || entry.getKey().startsWith(top + "$"))) own.add(entry.getValue());
        var contexts = MethodRoundTrip.contexts(own, compiled.values());
        for (var entry : originals.entrySet()) {
            String name = entry.getKey();
            if (topLevel.stream().noneMatch(top -> name.equals(top) || name.startsWith(top + "$"))) continue;
            var methods = MethodRoundTrip.compareClass(entry.getValue(), compiled.get(name), contexts[0], contexts[1]);
            if (methods != null) for (var method : methods) result.put(name + "." + method.name() + method.descriptor(), 2 - method.outcome().ordinal());
        }
        return result;
    }

    /**
     * The engine's version of the file analyzed alone: its methods by key, and the keys declared more than once; or, when
     * it is the base file itself, only that fact (Phase 2BI names the file's own edited bodies after that engine).
     */
    private Alternative alternative(String engine, Path root, BaseFile base) throws IOException {
        Path path = root.resolve(base.file()).normalize();
        if (!path.startsWith(root) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > 16L * 1024 * 1024) return null;
        String text;
        try { text = Files.readString(path, StandardCharsets.UTF_8); } catch (IOException unreadable) { return null; }
        if (text.equals(base.text())) return new Alternative(Map.of(), Set.of(), true);
        if (expired()) return null;
        var compiled = compile(base.file(), text, false, attributed -> {
            var found = new HashMap<String,Option>(); var duplicates = new HashSet<String>();
            for (var methodPath : attributed.methods()) {
                var method = attributed.describe(methodPath);
                if (method == null) continue;
                int errors = (int) attributed.errors.stream().filter(error -> error.getStartPosition() >= method.bodyStart()
                    && error.getStartPosition() < method.bodyEnd()).count();
                var body = new TreePath(methodPath, ((MethodTree) methodPath.getLeaf()).getBody());
                var option = new Option(engine, method, errors, attributed.symbols(body, method.bodyStart()),
                    attributed.typeNames(body, method.bodyStart()), attributed.dollarName(body));
                if (found.put(method.key(), option) != null) duplicates.add(method.key());
            }
            duplicates.forEach(found::remove);
            return Map.entry(found, duplicates);
        });
        if (compiled == null || !compiled.parsed()) return null;
        @SuppressWarnings("unchecked") var result = (Map.Entry<Map<String,Option>,Set<String>>) compiled.data();
        return new Alternative(result.getKey(), result.getValue(), false);
    }

    /** The file with chosen bodies (and parameter names) from other engines, and the other failing bodies stubbed or kept. */
    private static Built build(BaseFile base, Map<Method,Choice> chosen, boolean stubOthers) {
        var changes = new ArrayList<Change>();
        for (var method : base.errors()) {
            var choice = chosen.get(method);
            if (choice != null) {
                changes.add(new Change(method.bodyStart(), method.bodyEnd(), choice.body(), method));
                for (int i = 0; i < method.parameters().size(); i++) {
                    String name = choice.option().method().parameters().get(i);
                    int[] span = method.parameterSpans().get(i);
                    if (!name.equals(method.parameters().get(i))) changes.add(new Change(span[0], span[1], name, null));
                }
            } else if (stubOthers) changes.add(new Change(method.bodyStart(), method.bodyEnd(), method.stub(), method));
        }
        changes.sort(Comparator.comparingInt(Change::start));
        var text = new StringBuilder(); var starts = new IdentityHashMap<Method,Integer>(); int last = 0;
        for (var change : changes) {
            text.append(base.text(), last, change.start());
            if (change.body() != null) starts.put(change.body(), text.length());
            text.append(change.text()); last = change.end();
        }
        text.append(base.text(), last, base.text().length());
        return new Built(text.toString(), starts);
    }

    /** Compiles one source alone against the original classes; classes stay in memory. Null when the budget is spent. */
    private Compiled compile(String file, String text, boolean generate, Function<Attributed,Object> probe) throws IOException {
        if (expired()) return null;
        compiles++;
        var errors = new ArrayList<Diagnostic<? extends JavaFileObject>>(); var casts = new ArrayList<Diagnostic<? extends JavaFileObject>>();
        boolean[] entered = {false}, syntax = {false};
        var classes = new TreeMap<String,byte[]>();
        var memory = new ForwardingJavaFileManager<StandardJavaFileManager>(isolated) {
            @Override public JavaFileObject getJavaFileForOutput(Location location, String name, JavaFileObject.Kind kind, FileObject sibling) {
                return new SimpleJavaFileObject(URI.create("memory:///" + name.replace('.', '/') + kind.extension), kind) {
                    @Override public OutputStream openOutputStream() {
                        return new ByteArrayOutputStream() {
                            @Override public void close() throws IOException {
                                super.close();
                                if (kind == JavaFileObject.Kind.CLASS) classes.put(name.replace('.', '/'), toByteArray());
                            }
                        };
                    }
                };
            }
        };
        URI uri;
        try { uri = new URI("string", null, "/" + file, null); } catch (URISyntaxException error) { throw new IOException("Invalid source path", error); }
        var source = new SimpleJavaFileObject(uri, JavaFileObject.Kind.SOURCE) {
            @Override public CharSequence getCharContent(boolean ignoreEncodingErrors) { return text; }
        };
        var task = (JavacTask) compiler.getTask(null, memory, diagnostic -> {
            if (diagnostic.getKind() == Diagnostic.Kind.WARNING && SourceRepairWorker.REDUNDANT_CAST.equals(diagnostic.getCode())) {
                if (casts.size() < MAX_DIAGNOSTICS) casts.add(diagnostic);
                return;
            }
            if (diagnostic.getKind() != Diagnostic.Kind.ERROR) return;
            if (!entered[0]) syntax[0] = true;
            if (errors.size() < MAX_DIAGNOSTICS) errors.add(diagnostic);
        }, options, null, List.of(source));
        task.addTaskListener(new TaskListener() {
            @Override public void started(TaskEvent event) { if (event.getKind() == TaskEvent.Kind.ENTER) entered[0] = true; }
        });
        var units = new ArrayList<CompilationUnitTree>();
        task.parse().forEach(units::add);
        if (syntax[0] || units.size() != 1) return new Compiled(false, List.copyOf(errors), Map.of(), null);
        task.analyze();
        Object data = probe == null ? null : probe.apply(new Attributed(task, units.get(0), errors, casts));
        if (generate && errors.isEmpty()) task.generate();
        return new Compiled(true, List.copyOf(errors), classes, data);
    }

    private void refuse(String file, Method method, String reason) {
        if (refusals++ >= MAX_ROWS) return;
        String clipped = reason.length() <= 400 ? reason : reason.substring(0, 400) + "...";
        unspliced.append(SourceRepairWorker.escape(file)).append('\t').append(method == null ? "-" : method.owner()).append('\t')
            .append(method == null ? "-" : SourceRepairWorker.escape(method.name() + method.jvmDescriptor())).append('\t')
            .append(SourceRepairWorker.escape(clipped)).append('\n');
    }

    private static String relative(JavaFileObject file, Path sources) {
        var uri = file.toUri();
        if (!"file".equals(uri.getScheme())) return null;
        Path path = Path.of(uri).toAbsolutePath().normalize(), root = sources.toAbsolutePath().normalize();
        return path.startsWith(root) ? root.relativize(path).toString().replace('\\', '/') : null;
    }

    /** One compilation unit after attribution, with the queries composition needs. */
    private static final class Attributed {
        final JavacTask task; final Trees trees; final Elements elements; final Types types; final SourcePositions positions;
        final CompilationUnitTree unit; final String text; final List<Diagnostic<? extends JavaFileObject>> errors;
        /** javac's redundant-cast warnings for this unit (Phase 2BI); empty where the analysis did not ask for them. */
        final List<Diagnostic<? extends JavaFileObject>> warnings;

        Attributed(JavacTask task, CompilationUnitTree unit, List<Diagnostic<? extends JavaFileObject>> errors,
                   List<Diagnostic<? extends JavaFileObject>> warnings) {
            this.task = task; trees = Trees.instance(task); elements = task.getElements(); types = task.getTypes(); positions = trees.getSourcePositions();
            this.unit = unit; this.errors = errors; this.warnings = warnings;
            try { text = unit.getSourceFile().getCharContent(true).toString(); } catch (IOException error) { throw new UncheckedIOException(error); }
        }

        int start(Tree tree) { return (int) positions.getStartPosition(unit, tree); }
        int end(Tree tree) { return (int) positions.getEndPosition(unit, tree); }

        /** Methods with a written body declared directly in a top-level or member class (not in local or anonymous ones). */
        List<TreePath> methods() {
            var result = new ArrayList<TreePath>();
            var root = new TreePath(unit);
            for (var type : unit.getTypeDecls()) if (type instanceof ClassTree) collect(new TreePath(root, type), result);
            return result;
        }

        private void collect(TreePath type, List<TreePath> result) {
            for (var member : ((ClassTree) type.getLeaf()).getMembers()) {
                var path = new TreePath(type, member);
                // A constructor javac generated has no end position; it was not written by the engine.
                if (member instanceof MethodTree method && method.getBody() != null && start(method) >= 0 && end(method.getBody()) > start(method.getBody()))
                    result.add(path);
                else if (member instanceof ClassTree) collect(path, result);
            }
        }

        List<String> topLevel() {
            var result = new ArrayList<String>();
            for (var type : unit.getTypeDecls())
                if (type instanceof ClassTree && trees.getElement(new TreePath(new TreePath(unit), type)) instanceof TypeElement element) result.add(binary(element));
            return result;
        }

        Method describe(TreePath path) {
            var tree = (MethodTree) path.getLeaf();
            if (!(trees.getElement(path) instanceof ExecutableElement method) || !(method.getEnclosingElement() instanceof TypeElement owner)) return null;
            String descriptor = descriptor(method), owned = binary(owner), jvm = descriptor;
            if (method.getKind() == ElementKind.CONSTRUCTOR) {
                // javac adds the enum name and ordinal, or the enclosing instance of an inner class, to the constructor descriptor.
                if (owner.getKind() == ElementKind.ENUM) jvm = "(Ljava/lang/String;I" + descriptor.substring(1);
                else if (owner.getKind() == ElementKind.CLASS && owner.getNestingKind() == NestingKind.MEMBER && !owner.getModifiers().contains(Modifier.STATIC)
                    && owner.getEnclosingElement() instanceof TypeElement outer && outer.getKind().isClass())
                    jvm = "(L" + binary(outer) + ";" + descriptor.substring(1);
            }
            var body = tree.getBody();
            var names = new ArrayList<String>(); var spans = new ArrayList<int[]>();
            for (var parameter : tree.getParameters()) {
                String name = parameter.getName().toString();
                names.add(name);
                // The name is the last occurrence of the identifier in its declaration (C-style brackets may follow it).
                int from = start(parameter), to = end(parameter), at = -1;
                if (from >= 0 && to > from) {
                    var matcher = Pattern.compile("(?<![\\p{javaJavaIdentifierPart}])" + Pattern.quote(name) + "(?![\\p{javaJavaIdentifierPart}])")
                        .matcher(text).region(from, to);
                    while (matcher.find()) at = matcher.start();
                }
                spans.add(at < 0 ? null : new int[] {at, at + name.length()});
            }
            var lines = unit.getLineMap();
            return new Method(owned, method.getSimpleName().toString(), owned + "." + method.getSimpleName() + descriptor, jvm, start(body), end(body),
                List.copyOf(names), Collections.unmodifiableList(spans), facts(body), source(body), stub(path, method),
                lines.getLineNumber(start(tree)), lines.getLineNumber(Math.max(start(tree), end(tree) - 1)));
        }

        /**
         * {@code { throw null; }}, which compiles for any method. A constructor keeps its written this(...) or super(...)
         * call, with neutral typed arguments for the constructor it resolved to, since those arguments may hold the error.
         */
        private String stub(TreePath path, ExecutableElement method) {
            var body = ((MethodTree) path.getLeaf()).getBody();
            if (method.getKind() != ElementKind.CONSTRUCTOR || body.getStatements().isEmpty()
                || !(body.getStatements().get(0) instanceof ExpressionStatementTree statement)
                || !(statement.getExpression() instanceof MethodInvocationTree call) || !explicitConstructorCall(call)
                || start(call) < 0 || end(call) <= start(call)) return STUB; // the super() javac adds has no position
            Element resolved = trees.getElement(new TreePath(new TreePath(new TreePath(path, body), statement), call));
            if (!(resolved instanceof ExecutableElement) && method.getEnclosingElement() instanceof TypeElement owner) {
                // javac leaves no symbol on a call whose arguments failed; the only constructor of that arity is the target.
                TypeElement called = call.getMethodSelect() instanceof IdentifierTree name && name.getName().contentEquals("this") ? owner
                    : owner.getSuperclass() instanceof DeclaredType parent && parent.asElement() instanceof TypeElement type ? type : null;
                var matching = called == null ? List.<ExecutableElement>of() : ElementFilter.constructorsIn(called.getEnclosedElements()).stream()
                    .filter(constructor -> !constructor.equals(method) && constructor.getParameters().size() == call.getArguments().size()).toList();
                if (matching.size() == 1) resolved = matching.get(0);
            }
            if (resolved instanceof ExecutableElement target) {
                var arguments = new StringJoiner(", ");
                for (var parameter : target.getParameters()) {
                    String value = neutral(parameter.asType());
                    if (value == null) { arguments = null; break; }
                    arguments.add(value);
                }
                if (arguments != null) return "{ " + source(call.getMethodSelect()) + "(" + arguments + "); throw null; }";
            }
            return "{ " + source(call) + "; throw null; }";
        }

        /** A constant of the erased type, cast so that overload resolution picks the same constructor. */
        private String neutral(TypeMirror type) {
            TypeMirror erased = types.erasure(type);
            return switch (erased.getKind()) {
                case BOOLEAN -> "false"; case BYTE -> "(byte) 0"; case SHORT -> "(short) 0"; case CHAR -> "(char) 0"; case INT -> "0";
                case LONG -> "0L"; case FLOAT -> "0F"; case DOUBLE -> "0D";
                case ARRAY, DECLARED -> { String name = sourceName(erased); yield name == null ? null : "(" + name + ") null"; }
                default -> null;
            };
        }

        private String sourceName(TypeMirror type) {
            if (type instanceof ArrayType array) { String component = sourceName(array.getComponentType()); return component == null ? null : component + "[]"; }
            if (type.getKind().isPrimitive()) return type.toString();
            if (!(type instanceof DeclaredType declared) || !(declared.asElement() instanceof TypeElement element)
                || element.getNestingKind() == NestingKind.LOCAL || element.getNestingKind() == NestingKind.ANONYMOUS) return null;
            return element.getQualifiedName().toString();
        }

        private static boolean explicitConstructorCall(MethodInvocationTree call) {
            return call.getMethodSelect() instanceof IdentifierTree name && (name.getName().contentEquals("this") || name.getName().contentEquals("super"))
                || call.getMethodSelect() instanceof MemberSelectTree select && select.getIdentifier().contentEquals("super");
        }

        /** The body block that starts at {@code offset}, or null. */
        TreePath body(int offset) {
            for (var path : methods()) {
                var body = ((MethodTree) path.getLeaf()).getBody();
                if (start(body) == offset) return new TreePath(path, body);
            }
            return null;
        }

        static Facts facts(BlockTree body) {
            var classes = new ArrayList<String>(); int[] counts = {0, 0};
            new TreeScanner<Void,Void>() {
                @Override public Void visitNewClass(NewClassTree tree, Void unused) {
                    if (tree.getClassBody() != null) classes.add("new " + simpleName(tree.getIdentifier()));
                    return super.visitNewClass(tree, null);
                }
                @Override public Void visitClass(ClassTree tree, Void unused) {
                    if (!tree.getSimpleName().isEmpty()) classes.add(tree.getKind() + " " + tree.getSimpleName());
                    return super.visitClass(tree, null);
                }
                @Override public Void visitLambdaExpression(LambdaExpressionTree tree, Void unused) { counts[0]++; return super.visitLambdaExpression(tree, null); }
                @Override public Void visitMemberReference(MemberReferenceTree tree, Void unused) { counts[1]++; return super.visitMemberReference(tree, null); }
            }.scan(body, null);
            return new Facts(List.copyOf(classes), counts[0], counts[1]);
        }

        private static String simpleName(Tree type) {
            while (true) {
                if (type instanceof ParameterizedTypeTree generic) type = generic.getType();
                else if (type instanceof AnnotatedTypeTree annotated) type = annotated.getUnderlyingType();
                else if (type instanceof MemberSelectTree select) return select.getIdentifier().toString();
                else return type.toString();
            }
        }

        /** The first name containing '$' in the body: generated names (access$000, this$0, Outer$1, lambda$m$0) never move. */
        String dollarName(TreePath body) {
            String[] found = {null};
            new TreeScanner<Void,Void>() {
                @Override public Void visitIdentifier(IdentifierTree tree, Void unused) { check(tree.getName()); return null; }
                @Override public Void visitMemberSelect(MemberSelectTree tree, Void unused) { check(tree.getIdentifier()); return super.visitMemberSelect(tree, null); }
                @Override public Void visitVariable(VariableTree tree, Void unused) { check(tree.getName()); return super.visitVariable(tree, null); }
                @Override public Void visitMethod(MethodTree tree, Void unused) { check(tree.getName()); return super.visitMethod(tree, null); }
                void check(Name name) { if (found[0] == null && name.toString().indexOf('$') >= 0) found[0] = name.toString(); }
            }.scan(body.getLeaf(), null);
            return found[0];
        }

        /**
         * The declaration each name in the body resolves to, in source order. Packages are left out: a canonical type name
         * ({@code java.util.Map}) and an imported simple one ({@code Map}) then give the same sequence.
         */
        Symbols symbols(TreePath body, int bodyStart) {
            var keys = new ArrayList<String>(); var offsets = new ArrayList<Integer>();
            new TreePathScanner<Void,Void>() {
                @Override public Void visitIdentifier(IdentifierTree tree, Void unused) { add(tree); return null; }
                @Override public Void visitMemberSelect(MemberSelectTree tree, Void unused) { add(tree); return super.visitMemberSelect(tree, null); }
                @Override public Void visitNewClass(NewClassTree tree, Void unused) { add(tree); return super.visitNewClass(tree, null); }
                @Override public Void visitMemberReference(MemberReferenceTree tree, Void unused) { add(tree); return super.visitMemberReference(tree, null); }
                void add(Tree tree) {
                    String key = key(getCurrentPath());
                    if (key.startsWith("package ")) return;
                    // A member select's name sits after its qualifier, which a repair may wrap (a raw receiver, Phase 2BI).
                    int at = tree instanceof MemberSelectTree select && end(select) >= 0 ? end(select) - select.getIdentifier().length() : start(tree);
                    keys.add(key); offsets.add(at - bodyStart);
                }
            }.scan(body, null);
            return new Symbols(List.copyOf(keys), List.copyOf(offsets));
        }

        /** Simple names of top-level and member types in the body, which may be written canonically in another file. */
        List<TypeName> typeNames(TreePath body, int bodyStart) {
            var result = new ArrayList<TypeName>(); var seen = new HashSet<Integer>();
            new TreePathScanner<Void,Void>() {
                @Override public Void visitIdentifier(IdentifierTree tree, Void unused) {
                    // javac shares the type tree of `new T() {...}` with the anonymous class's supertype; it is visited twice.
                    if (!seen.add(start(tree))) return null;
                    Element element;
                    try { element = trees.getElement(getCurrentPath()); } catch (RuntimeException unresolved) { return null; }
                    int from = start(tree), to = end(tree);
                    // `outer.new Inner()` must keep the simple name of the inner class.
                    boolean qualifiedCreation = getCurrentPath().getParentPath().getLeaf() instanceof NewClassTree created && created.getEnclosingExpression() != null
                        || getCurrentPath().getParentPath().getLeaf() instanceof ParameterizedTypeTree
                        && getCurrentPath().getParentPath().getParentPath().getLeaf() instanceof NewClassTree outer && outer.getEnclosingExpression() != null;
                    if (element instanceof TypeElement type && (type.getNestingKind() == NestingKind.TOP_LEVEL || type.getNestingKind() == NestingKind.MEMBER)
                        && !qualifiedCreation && from >= 0 && to > from && text.substring(from, to).contentEquals(tree.getName()))
                        result.add(new TypeName(from - bodyStart, to - bodyStart, tree.getName().toString(), type.getQualifiedName().toString()));
                    return null;
                }
            }.scan(body, null);
            return List.copyOf(result);
        }

        private String key(TreePath path) {
            try { return key(trees.getElement(path)); } catch (RuntimeException unresolved) { return "?"; }
        }

        private String key(Element element) {
            // javac's error recovery makes up symbols of an erroneous type (a call that failed reads as a class named after
            // the method): those names did not resolve.
            if (element == null || element.asType() != null && element.asType().getKind() == TypeKind.ERROR) return "?";
            return switch (element.getKind()) {
                case PACKAGE -> "package " + ((PackageElement) element).getQualifiedName();
                case CLASS, INTERFACE, ENUM, ANNOTATION_TYPE, RECORD -> "type " + typeKey((TypeElement) element);
                case TYPE_PARAMETER -> "type-variable " + element.getSimpleName() + " of " + key(((TypeParameterElement) element).getGenericElement());
                case FIELD, ENUM_CONSTANT -> "field " + owner(element) + "." + element.getSimpleName();
                case METHOD, CONSTRUCTOR -> "method " + owner(element) + "." + element.getSimpleName() + descriptor((ExecutableElement) element);
                case LOCAL_VARIABLE, PARAMETER, EXCEPTION_PARAMETER, RESOURCE_VARIABLE, BINDING_VARIABLE -> "local " + element.getSimpleName();
                default -> element.getKind() + " " + element.getSimpleName();
            };
        }

        private String owner(Element element) { return element.getEnclosingElement() instanceof TypeElement type ? typeKey(type) : "?"; }

        /** Anonymous and local classes are named by javac in order, so they are compared by kind and simple name only. */
        private String typeKey(TypeElement type) {
            return switch (type.getNestingKind()) {
                case ANONYMOUS -> "anonymous";
                case LOCAL -> "local " + type.getSimpleName();
                default -> binary(type);
            };
        }

        String binary(TypeElement type) { return elements.getBinaryName(type).toString().replace('.', '/'); }

        /** The erased descriptor of the declared parameter and return types (no synthetic parameters). */
        String descriptor(ExecutableElement method) {
            var out = new StringBuilder("(");
            for (var parameter : method.getParameters()) out.append(descriptor(parameter.asType()));
            return out.append(')').append(descriptor(method.getReturnType())).toString();
        }

        private String descriptor(TypeMirror type) {
            TypeMirror erased = types.erasure(type);
            return switch (erased.getKind()) {
                case BOOLEAN -> "Z"; case BYTE -> "B"; case CHAR -> "C"; case SHORT -> "S"; case INT -> "I"; case LONG -> "J";
                case FLOAT -> "F"; case DOUBLE -> "D"; case VOID -> "V";
                case ARRAY -> "[" + descriptor(((ArrayType) erased).getComponentType());
                case DECLARED -> "L" + binary((TypeElement) ((DeclaredType) erased).asElement()) + ";";
                default -> "!" + erased.getKind();
            };
        }

        String source(Tree tree) { return text.substring(start(tree), end(tree)); }
    }
}
