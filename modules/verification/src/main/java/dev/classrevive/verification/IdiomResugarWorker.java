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
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

/**
 * Compiler idioms undone in the selected sources (Phase 2DH). Decompilers sometimes print what javac (or kotlinc) made
 * of a construct instead of the construct: a switch on an enum as a switch on {@code e.ordinal()} or on a synthetic
 * switch map, a private member reached through a synthetic {@code access$NNN} method, an {@code assert} as a test of
 * the synthetic {@code $assertionsDisabled} field. Each rewrite is read from the original bytecode, never guessed from
 * the text:
 * <ul>
 * <li>{@code enum-switch}: {@code switch (e.ordinal()) { case 0: }} becomes {@code switch (e) { case A: }}, with each
 *     ordinal's constant read from the enum's class initializer (the {@code new E("A", 0)} stored in field {@code A});
 *     {@code switch (H.$SwitchMap$p$E[e.ordinal()])} and kotlinc's {@code WhenMappings.$EnumSwitchMapping$N[...]}
 *     likewise, with each case value's constant read from the holder's class initializer
 *     ({@code $SwitchMap$p$E[E.A.ordinal()] = 1}).</li>
 * <li>{@code accessor-call}: {@code O.access$002(o, v)} becomes the member access the static synthetic method
 *     {@code access$002} performs, read from its instructions: it loads each parameter once, in order, and then only
 *     reads or writes one field of {@code O}, or calls one method of {@code O}, and returns.</li>
 * <li>{@code assert}: {@code if (!$assertionsDisabled && c) throw new AssertionError(m);} becomes
 *     {@code assert !c : m;}, when the field is the original class's synthetic static {@code $assertionsDisabled}; the
 *     field and its initialization go only when every use of the field became an {@code assert}.</li>
 * </ul>
 * Every file is compiled alone against the original classes before and after. A rule's edits in a file are kept only
 * when the file still compiles, every method of its original classes keeps a round trip at least as good as before
 * (identical stays identical, equivalent stays at least equivalent), and, for accessors, every rewritten member access
 * resolves to the member the accessor used. A file that does not compile alone (javac does not see synthetic members
 * of class files, so a printed {@code access$NNN} or {@code $SwitchMap$} reference fails, and a missing library fails
 * too) has no round trip to keep: its rewrites are kept only when they add no error, accessors still resolving to
 * their members. A rule whose edits fail together is retried one site at a time.
 *
 * <p>Runs only the JDK compiler and ASM's class reader; never loads or runs target classes or annotation processors.
 * Arguments: mix sources, output folder, release, original class JAR, time budget in milliseconds, then dependency
 * classpath entries. Writes the edited files under {@code output/sources}, and {@code idioms.tsv} (file, line, rule,
 * before, after) and {@code unresugared.tsv} (file, line, rule, reason) next to the output folder.
 */
public final class IdiomResugarWorker {
    static final int MAX_FILES = 20_000, MAX_COMPILES = 20_000, MAX_SITE_RETRIES = 16, MAX_ROWS = 100_000, MAX_DIAGNOSTICS = 100;
    static final String ENUM_SWITCH = "enum-switch", ACCESSOR_CALL = "accessor-call", ASSERT = "assert";
    /** The rules in the order they are tried; a later rule never edits text an earlier rule kept. */
    static final List<String> RULES = List.of(ENUM_SWITCH, ACCESSOR_CALL, ASSERT);
    private static final Pattern ACCESSOR = Pattern.compile("access\\$\\d+");
    private static final Pattern SWITCH_MAP = Pattern.compile("\\$SwitchMap\\$.*|\\$EnumSwitchMapping\\$\\d+");
    private static final Pattern IDENTIFIER = Pattern.compile("[\\p{L}_$][\\p{L}\\p{N}_$]*");

    public static void main(String[] args) throws Exception {
        Path mix = Path.of(args[0]).toAbsolutePath().normalize(), output = Path.of(args[1]).toAbsolutePath().normalize();
        long deadline = System.nanoTime() + Long.parseLong(args[4]) * 1_000_000L;
        var dependencies = new ArrayList<Path>();
        for (int i = 5; i < args.length; i++) dependencies.add(Path.of(args[i]));
        var compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) throw new IllegalStateException("A JDK is required for idiom rewrites");
        Path empty = Files.createDirectory(output.getParent().resolve("empty-sourcepath"));
        var kept = new StringBuilder(); var refused = new StringBuilder();
        try (var isolated = compiler.getStandardFileManager(null, Locale.ROOT, StandardCharsets.UTF_8)) {
            var classpath = new ArrayList<Path>(); classpath.add(Path.of(args[3])); classpath.addAll(dependencies);
            JdkRuntimeClasses.configure(isolated, args[2], classpath, empty);
            isolated.setLocation(StandardLocation.SOURCE_PATH, List.of(empty.toFile()));
            var worker = new IdiomResugarWorker(compiler, isolated, args[2], deadline, MethodRoundTrip.readJar(Path.of(args[3])), kept, refused);
            worker.mix = mix;
            var files = new ArrayList<Path>();
            try (var walk = Files.walk(mix)) {
                walk.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && path.toString().endsWith(".java")).sorted().forEach(files::add);
            }
            int count = 0;
            for (Path path : files) {
                String file = mix.relativize(path).toString().replace('\\', '/');
                if (!safeSourcePath(file) || Files.size(path) > 4L * 1024 * 1024) continue;
                String text = Files.readString(path, StandardCharsets.UTF_8);
                if (!candidate(text)) continue;
                if (count++ == MAX_FILES || worker.expired()) { worker.refuse(file, 0, "-", "budget-exceeded"); continue; }
                try {
                    String edited = worker.resugar(file, text);
                    if (edited != null) {
                        Path target = output.resolve("sources").resolve(file).normalize();
                        if (!target.startsWith(output)) continue;
                        Files.createDirectories(target.getParent());
                        Files.writeString(target, edited, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
                    }
                } catch (RuntimeException error) {
                    worker.refuse(file, 0, "-", "analysis-failed: " + error.getClass().getSimpleName());
                }
            }
        }
        Files.writeString(output.getParent().resolve("idioms.tsv"), kept, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        Files.writeString(output.getParent().resolve("unresugared.tsv"), refused, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
    }

    /** {@link CompilerVerifier#safeSourcePath}, which this worker cannot load (its class path has no engine classes). */
    static boolean safeSourcePath(String name) {
        boolean safe = !name.isEmpty() && !name.startsWith("/") && !name.contains("\\") && name.endsWith(".java");
        for (String part : name.split("/", -1)) safe &= !part.isEmpty() && !part.equals(".") && !part.equals("..");
        return safe;
    }

    /** A cheap text filter: only files that name one of the idioms are compiled. */
    static boolean candidate(String text) {
        return text.contains(".ordinal()") || text.contains("access$") || text.contains("$assertionsDisabled");
    }

    private final JavaCompiler compiler; private final StandardJavaFileManager isolated; private final List<String> options;
    private final long deadline; private final Map<String,byte[]> originals; private final StringBuilder kept, refused;
    private final Map<String,ClassNode> nodes = new HashMap<>();
    /** The mix's root and the file being rewritten, for {@link #declaredElsewhere}. */
    private Path mix; private String current;
    private int compiles, rows;

    private IdiomResugarWorker(JavaCompiler compiler, StandardJavaFileManager isolated, String release, long deadline,
                               Map<String,byte[]> originals, StringBuilder kept, StringBuilder refused) {
        this.compiler = compiler; this.isolated = isolated; this.deadline = deadline; this.originals = originals;
        this.kept = kept; this.refused = refused;
        options = List.of("--release", release, "-proc:none", "-implicit:none", "-encoding", "UTF-8", "-Xlint:-options",
            "-Xmaxerrs", Integer.toString(MAX_DIAGNOSTICS));
    }

    private boolean expired() { return System.nanoTime() > deadline || compiles >= MAX_COMPILES; }

    /** One text replacement in the file as it was before this phase. */
    record Edit(int start, int end, String text) {}
    /**
     * One rewrite: its edits, the line it starts on, short before and after texts for the report, and for accessors the
     * members ({@code owner.name} or {@code owner.name+descriptor}) the rewritten accesses must resolve to.
     */
    record Site(String rule, long line, List<Edit> edits, String before, String after, List<String> members, int memberShift) {
        Site(String rule, long line, List<Edit> edits, String before, String after) { this(rule, line, edits, before, after, List.of(), 0); }
    }
    private record Compiled(boolean ok, Map<String,byte[]> classes, Object data, String error, int errors) {
        Compiled(boolean ok, Map<String,byte[]> classes, Object data) { this(ok, classes, data, null, 0); }
    }

    /** The file with the kept rewrites, or null when none was kept. */
    private String resugar(String file, String text) throws IOException {
        current = file;
        var base = compile(file, text, attributed -> new Finder(attributed, this).find());
        if (base == null) { refuse(file, 0, "-", "budget-exceeded"); return null; }
        if (base.data() == null) { refuse(file, 0, "-", "file-does-not-parse"); return null; }
        @SuppressWarnings("unchecked") var found = (Found) base.data();
        found.refusals().forEach(row -> refuse(file, row.line(), row.rule(), row.reason()));
        if (found.sites().isEmpty()) return null;
        // A file that does not compile alone (it may name synthetic members javac cannot see, or miss a library) has no
        // round trip to keep: its rewrites are kept only when they add no compile error.
        if (!base.ok() && base.errors() >= MAX_DIAGNOSTICS) { refuse(file, 0, "-", "too-many-errors"); return null; }
        var before = base.ok() ? outcomes(found.topLevel(), base.classes()) : Map.<String,Integer>of();
        int errors = base.errors();
        var accepted = new ArrayList<Site>();
        for (String rule : RULES) {
            var sites = new ArrayList<Site>();
            for (var site : found.sites()) {
                if (!site.rule().equals(rule)) continue;
                if (overlaps(site, accepted) || overlaps(site, sites)) { refuse(file, site.line(), rule, "overlaps-another-rewrite"); continue; }
                sites.add(site);
            }
            if (sites.isEmpty()) continue;
            String verdict = check(file, text, accepted, sites, found.topLevel(), before, errors);
            if (verdict == null) { accepted.addAll(sites); continue; }
            if (sites.size() == 1) { refuse(file, sites.get(0).line(), rule, verdict); continue; }
            int retries = 0;
            for (var site : sites) {
                if (retries++ == MAX_SITE_RETRIES) { refuse(file, site.line(), rule, "site-budget-exceeded"); continue; }
                String alone = check(file, text, accepted, List.of(site), found.topLevel(), before, errors);
                if (alone == null) accepted.add(site); else refuse(file, site.line(), rule, alone);
            }
        }
        if (accepted.isEmpty()) return null;
        accepted.sort(Comparator.comparingInt(site -> site.edits().get(0).start()));
        for (var site : accepted)
            if (rows++ < MAX_ROWS)
                kept.append(SourceRepairWorker.escape(file)).append('\t').append(site.line()).append('\t').append(site.rule()).append('\t')
                    .append(SourceRepairWorker.escape(clip(site.before()))).append('\t').append(SourceRepairWorker.escape(clip(site.after()))).append('\n');
        return apply(text, accepted);
    }

    /**
     * Null when the file with {@code accepted} and {@code trying} compiles alone with no method worse, or, for a file
     * that had {@code errors} before, has no more errors than that; otherwise why not.
     */
    private String check(String file, String text, List<Site> accepted, List<Site> trying, List<String> topLevel, Map<String,Integer> before,
                         int errors) throws IOException {
        var all = new ArrayList<>(accepted); all.addAll(trying);
        String edited = apply(text, all);
        var members = new TreeMap<Integer,List<String>>();
        for (var site : trying) if (!site.members().isEmpty()) members.put(shifted(site.edits().get(0).start(), all) + site.memberShift(), site.members());
        var after = compile(file, edited, attributed -> Finder.resolves(attributed, members));
        if (after == null) return "budget-exceeded";
        if (after.data() == null) return "does-not-parse";
        if (!after.ok() && (errors == 0 || after.errors() > errors)) return "does-not-compile: " + after.error();
        if (!Boolean.TRUE.equals(after.data())) return "member-not-resolved";
        if (!after.ok()) return null;
        var outcomes = outcomes(topLevel, after.classes());
        for (var entry : before.entrySet()) {
            Integer now = outcomes.get(entry.getKey());
            if (now == null || now < entry.getValue()) return "round-trip-worse: " + entry.getKey();
        }
        return null;
    }

    /** Where the text at {@code offset} of the old file starts in the file with {@code sites} applied. */
    private static int shifted(int offset, List<Site> sites) {
        int delta = 0;
        for (var site : sites) for (var edit : site.edits()) if (edit.end() <= offset) delta += edit.text().length() - (edit.end() - edit.start());
        return offset + delta;
    }

    static String apply(String text, List<Site> sites) {
        var edits = new ArrayList<Edit>();
        sites.forEach(site -> edits.addAll(site.edits()));
        edits.sort(Comparator.comparingInt(Edit::start));
        var result = new StringBuilder(text.length()); int last = 0;
        for (var edit : edits) { result.append(text, last, edit.start()).append(edit.text()); last = edit.end(); }
        return result.append(text, last, text.length()).toString();
    }

    private static boolean overlaps(Site site, List<Site> others) {
        for (var other : others)
            for (var a : site.edits()) for (var b : other.edits())
                if (a.start() < b.end() && b.start() < a.end() || a.start() == a.end() && a.start() == b.start()) return true;
        return false;
    }

    /** Per-method outcomes (2 identical, 1 equivalent, 0 different) for the original classes declared in the file. */
    private Map<String,Integer> outcomes(List<String> topLevel, Map<String,byte[]> compiled) {
        var result = new TreeMap<String,Integer>();
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
     * Whether another file of the mix declares class {@code name} (a decompiler may print a synthetic holder or a member
     * class as a file of its own when InnerClasses is absent). The file then compiles in the mix but not alone, so its
     * round trip alone says nothing, and javac would generate a second class of that name for a rewritten switch.
     */
    boolean declaredElsewhere(String name) {
        if (mix == null) return false;
        int dollar = name.indexOf('$', name.lastIndexOf('/') + 1);
        for (String candidate : dollar < 0 ? List.of(name) : List.of(name, name.substring(0, dollar))) {
            String file = candidate + ".java";
            if (!file.equals(current) && Files.isRegularFile(mix.resolve(file), LinkOption.NOFOLLOW_LINKS)) return true;
        }
        return false;
    }

    /** The original class with its code, or null when it is not an original class or cannot be read. */
    ClassNode node(String name) {
        if (nodes.containsKey(name)) return nodes.get(name);
        ClassNode node = null;
        byte[] bytes = originals.get(name);
        if (bytes != null) {
            node = new ClassNode();
            try { new org.objectweb.asm.ClassReader(bytes).accept(node, org.objectweb.asm.ClassReader.SKIP_DEBUG | org.objectweb.asm.ClassReader.SKIP_FRAMES); }
            catch (RuntimeException malformed) { node = null; }
        }
        nodes.put(name, node);
        return node;
    }

    /**
     * The ordinal of each constant of enum {@code name}, from its class initializer: each {@code new E} (or a constant
     * body {@code new E$1}) takes its name from the first string constant and its ordinal from the next int constant,
     * and is stored in an enum field. Null unless every enum field gets one, the ordinals are 0 to n-1, and the
     * initializer has no jump between a creation and its store.
     */
    Map<Integer,String> ordinals(String name) {
        var node = node(name);
        if (node == null || (node.access & Opcodes.ACC_ENUM) == 0) return null;
        var enumFields = new HashSet<String>();
        for (var field : node.fields) if ((field.access & Opcodes.ACC_ENUM) != 0) enumFields.add(field.name);
        MethodNode clinit = null;
        for (var method : node.methods) if (method.name.equals("<clinit>")) clinit = method;
        if (clinit == null || enumFields.isEmpty()) return null;
        var result = new TreeMap<Integer,String>(); var seen = new HashSet<String>();
        boolean pending = false; String constant = null; int ordinal = -1;
        for (var insn : clinit.instructions) {
            if (insn instanceof TypeInsnNode type && insn.getOpcode() == Opcodes.NEW && (type.desc.equals(name) || type.desc.startsWith(name + "$"))) {
                pending = true; constant = null; ordinal = -1;
            } else if (pending && constant == null && insn instanceof LdcInsnNode ldc && ldc.cst instanceof String value) {
                constant = value;
            } else if (pending && constant != null && ordinal < 0 && intConstant(insn) != null) {
                ordinal = intConstant(insn);
            } else if (pending && insn instanceof JumpInsnNode) {
                return null;
            } else if (insn instanceof FieldInsnNode field && insn.getOpcode() == Opcodes.PUTSTATIC && field.owner.equals(name)
                       && enumFields.contains(field.name) && field.desc.equals("L" + name + ";")) {
                if (!pending || ordinal < 0 || !seen.add(field.name) || result.put(ordinal, field.name) != null) return null;
                pending = false;
            }
        }
        if (!seen.equals(enumFields)) return null;
        for (int i = 0; i < result.size(); i++) if (!result.containsKey(i)) return null;
        return result;
    }

    /**
     * The switch map {@code owner.field} as {@code case value -> enum constant}, with the enum's internal name under the
     * key {@code -1}, read from the holder's class initializer: javac stores {@code $SwitchMap$p$E[E.A.ordinal()] = 1}
     * through {@code getstatic}, kotlinc through a local it later stores in {@code $EnumSwitchMapping$N}. Null when the
     * field is not filled that way, a value is given twice, or two enums appear.
     */
    Map<Integer,String> switchMap(String owner, String field) {
        var node = node(owner);
        if (node == null) return null;
        MethodNode clinit = null;
        for (var method : node.methods) if (method.name.equals("<clinit>")) clinit = method;
        if (clinit == null) return null;
        var insns = new ArrayList<AbstractInsnNode>();
        for (var insn : clinit.instructions) if (insn.getOpcode() >= 0) insns.add(insn);
        var result = new TreeMap<Integer,String>(); String enumName = null;
        for (int i = 0; i + 4 < insns.size(); i++) {
            if (insns.get(i + 4).getOpcode() != Opcodes.IASTORE) continue;
            String array = arrayField(insns, i, owner);
            if (!(insns.get(i + 1) instanceof FieldInsnNode constant) || constant.getOpcode() != Opcodes.GETSTATIC
                || !(insns.get(i + 2) instanceof MethodInsnNode ordinal) || !ordinal.name.equals("ordinal") || !ordinal.desc.equals("()I")
                || !constant.desc.equals("L" + constant.owner + ";") || intConstant(insns.get(i + 3)) == null) continue;
            if (array == null) return null;
            if (!array.equals(field)) continue;
            if (enumName != null && !enumName.equals(constant.owner)) return null;
            enumName = constant.owner;
            if (result.put(intConstant(insns.get(i + 3)), constant.name) != null) return null;
        }
        if (enumName == null) return null;
        result.put(-1, enumName);
        return result;
    }

    /** The {@code int[]} field the array at {@code insns[i]} is: a {@code getstatic} of it, or a local later stored in it. */
    private static String arrayField(List<AbstractInsnNode> insns, int i, String owner) {
        var insn = insns.get(i);
        if (insn instanceof FieldInsnNode field && insn.getOpcode() == Opcodes.GETSTATIC && field.owner.equals(owner) && field.desc.equals("[I"))
            return field.name;
        if (insn instanceof VarInsnNode load && insn.getOpcode() == Opcodes.ALOAD) {
            for (int j = i + 5; j + 1 < insns.size(); j++)
                if (insns.get(j) instanceof VarInsnNode next && next.getOpcode() == Opcodes.ALOAD && next.var == load.var
                    && insns.get(j + 1) instanceof FieldInsnNode put && put.getOpcode() == Opcodes.PUTSTATIC && put.owner.equals(owner)
                    && put.desc.equals("[I")) return put.name;
        }
        return null;
    }

    /** What a static synthetic accessor does: a field read or write, or a call, with the member it uses. */
    record Accessor(int kind, String owner, String name, String descriptor, boolean isStatic) {
        static final int GET = 0, PUT = 1, CALL = 2;
        String member() { return owner + "." + name + (kind == CALL ? descriptor : ""); }
    }

    /**
     * The access {@code owner.name} performs, or null when it does anything else: its parameters are loaded once each,
     * in order, followed by one {@code getfield}/{@code getstatic} of a field of {@code owner}, or by the value's
     * {@code dup_x1}/{@code dup} and one {@code putfield}/{@code putstatic} (the assignment's value is returned; d8 loads
     * the value again after the store instead), or by
     * one call of a method of {@code owner} that is not a constructor and not a {@code super} call; then a return.
     */
    Accessor accessor(String owner, String name) {
        var node = node(owner);
        if (node == null) return null;
        MethodNode found = null;
        for (var method : node.methods) {
            if (!method.name.equals(name)) continue;
            if (found != null) return null;
            found = method;
        }
        int flags = Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC;
        if (found == null || (found.access & flags) != flags || !found.tryCatchBlocks.isEmpty()) return null;
        var insns = new ArrayList<AbstractInsnNode>();
        for (var insn : found.instructions) if (insn.getOpcode() >= 0) insns.add(insn);
        var arguments = Type.getArgumentTypes(found.desc);
        int slot = 0;
        for (int i = 0; i < arguments.length; i++) {
            if (i >= insns.size() || !(insns.get(i) instanceof VarInsnNode load) || load.var != slot
                || load.getOpcode() != arguments[i].getOpcode(Opcodes.ILOAD)) return null;
            slot += arguments[i].getSize();
        }
        var rest = insns.subList(arguments.length, insns.size());
        var returns = Type.getReturnType(found.desc);
        if (rest.isEmpty() || rest.get(rest.size() - 1).getOpcode() != returns.getOpcode(Opcodes.IRETURN)) return null;
        var core = rest.subList(0, rest.size() - 1);
        if (core.size() == 1 && core.get(0) instanceof FieldInsnNode field && field.owner.equals(owner)) {
            if (field.getOpcode() == Opcodes.GETFIELD && arguments.length == 1) return new Accessor(Accessor.GET, owner, field.name, field.desc, false);
            if (field.getOpcode() == Opcodes.GETSTATIC && arguments.length == 0) return new Accessor(Accessor.GET, owner, field.name, field.desc, true);
        }
        if (core.size() == 2 && core.get(1) instanceof FieldInsnNode field && field.owner.equals(owner) && returns.getSort() != Type.VOID) {
            int wide = Type.getType(field.desc).getSize() == 2 ? 1 : 0;
            int dup = core.get(0).getOpcode();
            if (field.getOpcode() == Opcodes.PUTFIELD && arguments.length == 2 && dup == (wide == 1 ? Opcodes.DUP2_X1 : Opcodes.DUP_X1))
                return new Accessor(Accessor.PUT, owner, field.name, field.desc, false);
            if (field.getOpcode() == Opcodes.PUTSTATIC && arguments.length == 1 && dup == (wide == 1 ? Opcodes.DUP2 : Opcodes.DUP))
                return new Accessor(Accessor.PUT, owner, field.name, field.desc, true);
        }
        // d8 (Android) returns the stored value by loading the parameter again: store, then load the value's own slot.
        if (core.size() == 2 && core.get(0) instanceof FieldInsnNode field && field.owner.equals(owner) && returns.getSort() != Type.VOID
            && core.get(1) instanceof VarInsnNode reload && reload.var == slot - arguments[arguments.length - 1].getSize()
            && reload.getOpcode() == arguments[arguments.length - 1].getOpcode(Opcodes.ILOAD)) {
            if (field.getOpcode() == Opcodes.PUTFIELD && arguments.length == 2) return new Accessor(Accessor.PUT, owner, field.name, field.desc, false);
            if (field.getOpcode() == Opcodes.PUTSTATIC && arguments.length == 1) return new Accessor(Accessor.PUT, owner, field.name, field.desc, true);
        }
        if (core.size() == 1 && core.get(0) instanceof MethodInsnNode call && call.owner.equals(owner) && !call.name.startsWith("<")
            && Type.getReturnType(call.desc).equals(returns)) {
            int receiver = call.getOpcode() == Opcodes.INVOKESTATIC ? 0 : 1;
            if (Type.getArgumentTypes(call.desc).length + receiver != arguments.length) return null;
            if (call.getOpcode() == Opcodes.INVOKESPECIAL) {
                // Only a private method of the owner itself: invokespecial of anything else is a super call.
                boolean own = false;
                for (var method : node.methods) own |= method.name.equals(call.name) && method.desc.equals(call.desc) && (method.access & Opcodes.ACC_PRIVATE) != 0;
                if (!own) return null;
            }
            return new Accessor(Accessor.CALL, owner, call.name, call.desc, receiver == 0);
        }
        return null;
    }

    private static Integer intConstant(AbstractInsnNode insn) {
        int opcode = insn.getOpcode();
        if (opcode >= Opcodes.ICONST_M1 && opcode <= Opcodes.ICONST_5) return opcode - Opcodes.ICONST_0;
        if (insn instanceof IntInsnNode push && (opcode == Opcodes.BIPUSH || opcode == Opcodes.SIPUSH)) return push.operand;
        if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof Integer value) return value;
        return null;
    }

    /** Compiles one source alone against the original classes; classes stay in memory. Null when the budget is spent. */
    private Compiled compile(String file, String text, Function<Attributed,Object> probe) throws IOException {
        if (expired()) return null;
        compiles++;
        var errors = new ArrayList<Diagnostic<? extends JavaFileObject>>();
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
            if (diagnostic.getKind() == Diagnostic.Kind.ERROR && errors.size() < MAX_DIAGNOSTICS) errors.add(diagnostic);
        }, options, null, List.of(source));
        var units = new ArrayList<CompilationUnitTree>();
        task.parse().forEach(units::add);
        if (!errors.isEmpty() || units.size() != 1) return new Compiled(false, Map.of(), null, code(errors), errors.size());
        task.analyze();
        // The probe also reads a unit with attribution errors: a file may fail only because it names synthetic members.
        Object data = probe.apply(new Attributed(task, units.get(0), text));
        if (!errors.isEmpty()) return new Compiled(false, Map.of(), data, code(errors), errors.size());
        task.generate();
        return new Compiled(errors.isEmpty(), classes, data, code(errors), errors.size());
    }

    /** The first error's diagnostic code (never its message, which can name target symbols). */
    private static String code(List<Diagnostic<? extends JavaFileObject>> errors) {
        return errors.isEmpty() ? "-" : String.valueOf(errors.get(0).getCode());
    }

    private void refuse(String file, long line, String rule, String reason) {
        if (rows++ >= MAX_ROWS) return;
        refused.append(SourceRepairWorker.escape(file)).append('\t').append(line).append('\t').append(rule).append('\t')
            .append(SourceRepairWorker.escape(clip(reason))).append('\n');
    }

    private static String clip(String text) {
        String line = text.replaceAll("\\s+", " ").trim();
        return line.length() <= 300 ? line : line.substring(0, 300) + "...";
    }

    /** One attributed compilation unit and its text. */
    record Attributed(JavacTask task, CompilationUnitTree unit, String text) {
        Trees trees() { return Trees.instance(task); }
        long start(Tree tree) { return trees().getSourcePositions().getStartPosition(unit, tree); }
        long end(Tree tree) { return trees().getSourcePositions().getEndPosition(unit, tree); }
        String source(Tree tree) { return text.substring((int) start(tree), (int) end(tree)); }
        long line(Tree tree) { return unit.getLineMap().getLineNumber(start(tree)); }
    }

    record Refusal(long line, String rule, String reason) {}
    record Found(List<Site> sites, List<Refusal> refusals, List<String> topLevel) {}

    /** Finds the rewrite sites of one attributed unit. */
    private static final class Finder extends TreePathScanner<Void,Void> {
        private final Attributed unit; private final IdiomResugarWorker worker; private final Trees trees;
        private final Elements elements; private final Types types;
        private final List<Site> sites = new ArrayList<>(); private final List<Refusal> refusals = new ArrayList<>();
        /** The assert sites of each {@code $assertionsDisabled} field, and every use of the field outside them. */
        private final Map<VariableElement,List<IfTree>> asserts = new LinkedHashMap<>();
        private final Map<VariableElement,List<Tree>> uses = new HashMap<>();

        Finder(Attributed unit, IdiomResugarWorker worker) {
            this.unit = unit; this.worker = worker; trees = unit.trees(); elements = unit.task().getElements(); types = unit.task().getTypes();
        }

        Found find() {
            scan(unit.unit(), null);
            for (var entry : asserts.entrySet()) assertSite(entry.getKey(), entry.getValue());
            var topLevel = new ArrayList<String>();
            for (var type : unit.unit().getTypeDecls())
                if (type instanceof ClassTree declared && trees.getElement(new TreePath(new TreePath(unit.unit()), declared)) instanceof TypeElement element)
                    topLevel.add(internalName(element));
            return new Found(sites, refusals, topLevel);
        }

        private String internalName(TypeElement type) { return elements.getBinaryName(type).toString().replace('.', '/'); }

        // ---- enum-switch -------------------------------------------------------------------------------------------

        @Override public Void visitSwitch(SwitchTree tree, Void ignored) {
            enumSwitch(tree.getExpression(), tree.getCases(), tree);
            return super.visitSwitch(tree, ignored);
        }

        @Override public Void visitSwitchExpression(SwitchExpressionTree tree, Void ignored) {
            enumSwitch(tree.getExpression(), tree.getCases(), tree);
            return super.visitSwitchExpression(tree, ignored);
        }

        private void enumSwitch(ExpressionTree selector, List<? extends CaseTree> cases, Tree statement) {
            var expression = strip(selector);
            ExpressionTree receiver; Map<Integer,String> values; String enumName;
            if (ordinalCall(expression) != null) {
                receiver = ordinalCall(expression);
                enumName = enumOf(receiver);
                if (enumName == null) return;
                values = worker.ordinals(enumName);
                if (values == null) { refuse(statement, ENUM_SWITCH, "enum-ordinals-unknown"); return; }
            } else if (expression instanceof ArrayAccessTree access && ordinalCall(strip(access.getIndex())) != null
                       && switchMapField(strip(access.getExpression())) != null) {
                var field = switchMapField(strip(access.getExpression()));
                receiver = ordinalCall(strip(access.getIndex()));
                enumName = enumOf(receiver);
                if (enumName == null) return;
                if (worker.declaredElsewhere(field.getKey())) { refuse(statement, ENUM_SWITCH, "holder-declared-in-another-file"); return; }
                var map = worker.switchMap(field.getKey(), field.getValue());
                if (map == null || !enumName.equals(map.get(-1))) { refuse(statement, ENUM_SWITCH, "switch-map-unknown"); return; }
                values = new TreeMap<>(map); values.remove(-1);
            } else return;
            var edits = new ArrayList<Edit>();
            // javac's selector is the parenthesized expression; only what is inside the parentheses is replaced.
            edits.add(new Edit((int) unit.start(expression), (int) unit.end(expression), unit.source(receiver)));
            var labels = new ArrayList<String>();
            for (var item : cases) {
                for (var label : item.getExpressions()) {
                    Object value = label instanceof LiteralTree literal ? literal.getValue() : null;
                    if (!(value instanceof Integer number) || !values.containsKey(number)) { refuse(statement, ENUM_SWITCH, "case-value-unmapped"); return; }
                    String constant = values.get(number);
                    if (!IDENTIFIER.matcher(constant).matches()) { refuse(statement, ENUM_SWITCH, "constant-name-not-an-identifier"); return; }
                    edits.add(new Edit((int) unit.start(label), (int) unit.end(label), constant));
                    labels.add(constant);
                }
            }
            if (labels.isEmpty()) return;
            sites.add(new Site(ENUM_SWITCH, unit.line(statement), edits, "switch (" + unit.source(expression) + ")",
                "switch (" + unit.source(receiver) + ") case " + String.join(", ", labels)));
        }

        /**
         * The holder's internal name and the field's name of a switch map ({@code H.$SwitchMap$p$E}), or null. javac
         * does not see a synthetic field of a class file, so when the field is unresolved its qualifier names the holder.
         */
        private Map.Entry<String,String> switchMapField(ExpressionTree expression) {
            if (element(expression) instanceof VariableElement field && SWITCH_MAP.matcher(field.getSimpleName().toString()).matches()
                && field.getEnclosingElement() instanceof TypeElement holder) return Map.entry(internalName(holder), field.getSimpleName().toString());
            if (!(expression instanceof MemberSelectTree select) || !SWITCH_MAP.matcher(select.getIdentifier().toString()).matches()) return null;
            String field = select.getIdentifier().toString();
            if (element(select.getExpression()) instanceof TypeElement holder && holder.asType().getKind() != TypeKind.ERROR)
                return Map.entry(internalName(holder), field);
            // kotlinc's Outer$WhenMappings, printed as WhenMappings where the source does not declare it: the member of
            // that simple name of an enclosing class, among the original classes.
            if (!(select.getExpression() instanceof IdentifierTree name)) return null;
            for (var path = getCurrentPath(); path != null; path = path.getParentPath()) {
                if (!(path.getLeaf() instanceof ClassTree) || !(trees.getElement(path) instanceof TypeElement type)) continue;
                String holder = internalName(type) + "$" + name.getName();
                if (worker.node(holder) != null) return Map.entry(holder, field);
            }
            return null;
        }

        /** The receiver of a no-argument {@code ordinal()} call of {@link Enum}, or null. */
        private ExpressionTree ordinalCall(ExpressionTree expression) {
            if (!(expression instanceof MethodInvocationTree call) || !call.getArguments().isEmpty()
                || !(call.getMethodSelect() instanceof MemberSelectTree select) || !select.getIdentifier().contentEquals("ordinal")) return null;
            if (!(element(call) instanceof ExecutableElement method) || !(method.getEnclosingElement() instanceof TypeElement owner)
                || !owner.getQualifiedName().contentEquals("java.lang.Enum")) return null;
            return select.getExpression();
        }

        /** The internal name of the enum an expression's static type is, or null. */
        private String enumOf(ExpressionTree expression) {
            var type = trees.getTypeMirror(path(expression));
            if (type == null || type.getKind() != TypeKind.DECLARED || !(types.asElement(type) instanceof TypeElement element)
                || element.getKind() != ElementKind.ENUM) return null;
            return internalName(element);
        }

        // ---- accessor-call ------------------------------------------------------------------------------------------

        @Override public Void visitMethodInvocation(MethodInvocationTree tree, Void ignored) {
            String name = tree.getMethodSelect() instanceof MemberSelectTree select ? select.getIdentifier().toString()
                : tree.getMethodSelect() instanceof IdentifierTree identifier ? identifier.getName().toString() : "";
            if (ACCESSOR.matcher(name).matches()) {
                var owner = accessorOwner(tree, name);
                if (owner != null) accessorSite(tree, owner, name);
            }
            return super.visitMethodInvocation(tree, ignored);
        }

        /**
         * The class declaring the accessor a call names. javac does not see a synthetic method of a class file, so when
         * the call is unresolved the owner is its qualifier, or else the innermost enclosing class whose original
         * declares a method of that name.
         */
        private TypeElement accessorOwner(MethodInvocationTree call, String name) {
            if (trees.getElement(getCurrentPath()) instanceof ExecutableElement method && method.getSimpleName().contentEquals(name)
                && method.getEnclosingElement() instanceof TypeElement owner) return owner;
            if (call.getMethodSelect() instanceof MemberSelectTree select)
                return element(select.getExpression()) instanceof TypeElement owner && owner.asType().getKind() != TypeKind.ERROR ? owner : null;
            for (var path = getCurrentPath(); path != null; path = path.getParentPath()) {
                if (!(path.getLeaf() instanceof ClassTree) || !(trees.getElement(path) instanceof TypeElement type)) continue;
                var node = worker.node(internalName(type));
                if (node != null && node.methods.stream().anyMatch(method -> method.name.equals(name))) return type;
            }
            return null;
        }

        private void accessorSite(MethodInvocationTree call, TypeElement ownerType, String name) {
            String owner = internalName(ownerType);
            if (worker.declaredElsewhere(owner)) { refuse(call, ACCESSOR_CALL, "owner-declared-in-another-file"); return; }
            var accessor = worker.accessor(owner, name);
            if (accessor == null) { refuse(call, ACCESSOR_CALL, "accessor-not-a-plain-access"); return; }
            if (!IDENTIFIER.matcher(accessor.name()).matches()) { refuse(call, ACCESSOR_CALL, "member-name-not-an-identifier"); return; }
            var arguments = call.getArguments();
            String target;
            int first = 0;
            if (accessor.isStatic()) {
                if (!(call.getMethodSelect() instanceof MemberSelectTree select) || !(element(select.getExpression()) instanceof TypeElement qualifier)
                    || !qualifier.equals(ownerType)) { refuse(call, ACCESSOR_CALL, "static-accessor-without-its-class"); return; }
                target = unit.source(select.getExpression());
            } else {
                var receiver = arguments.get(0);
                var type = trees.getTypeMirror(path(receiver));
                if (type == null || type.getKind() != TypeKind.DECLARED || !ownerType.equals(types.asElement(type))) {
                    refuse(call, ACCESSOR_CALL, "receiver-type-differs"); return;
                }
                target = primary(receiver);
                first = 1;
            }
            var rest = new ArrayList<String>();
            for (int i = first; i < arguments.size(); i++) rest.add(unit.source(arguments.get(i)));
            String member = target + "." + accessor.name();
            String text = switch (accessor.kind()) {
                case Accessor.GET -> member;
                case Accessor.PUT -> member + " = " + rest.get(0);
                default -> member + "(" + String.join(", ", rest) + ")";
            };
            // An assignment needs parentheses unless it is a statement of its own; the access then starts one later.
            var parent = getCurrentPath().getParentPath().getLeaf();
            int shift = 0;
            if (accessor.kind() == Accessor.PUT && !(parent instanceof ExpressionStatementTree) && !(parent instanceof ParenthesizedTree)) {
                text = "(" + text + ")"; shift = 1;
            }
            sites.add(new Site(ACCESSOR_CALL, unit.line(call), List.of(new Edit((int) unit.start(call), (int) unit.end(call), text)),
                unit.source(call), text, List.of(accessor.member()), shift));
        }

        /** The expression's text, in parentheses unless it is a name, a field, a call, an array element or a creation. */
        private String primary(ExpressionTree expression) {
            String text = unit.source(expression);
            return switch (expression.getKind()) {
                case IDENTIFIER, MEMBER_SELECT, METHOD_INVOCATION, ARRAY_ACCESS, PARENTHESIZED, NEW_CLASS -> text;
                default -> "(" + text + ")";
            };
        }

        /**
         * Whether every rewritten access of the edited unit resolves to the member the accessor used: the names after
         * each offset (field selections, calls and assignments) that carry the member's name.
         */
        static Boolean resolves(Attributed unit, Map<Integer,List<String>> members) {
            if (members.isEmpty()) return true;
            var found = new HashMap<Integer,Boolean>();
            var elements = unit.task().getElements();
            new TreePathScanner<Void,Void>() {
                @Override public Void visitMemberSelect(MemberSelectTree tree, Void ignored) {
                    int start = (int) unit.start(tree);
                    var expected = members.get(start);
                    if (expected != null) {
                        var element = unit.trees().getElement(getCurrentPath());
                        if (element != null && tree.getIdentifier().contentEquals(name(expected.get(0)))) {
                            String key = key(element, elements);
                            found.merge(start, expected.contains(key), Boolean::logicalAnd);
                        }
                    }
                    return super.visitMemberSelect(tree, ignored);
                }
            }.scan(unit.unit(), null);
            for (var offset : members.keySet()) if (!Boolean.TRUE.equals(found.get(offset))) return false;
            return true;
        }

        private static String name(String member) {
            int dot = member.lastIndexOf('.'), paren = member.indexOf('(', dot);
            return paren < 0 ? member.substring(dot + 1) : member.substring(dot + 1, paren);
        }

        /** {@code owner.name} for a field, {@code owner.name+descriptor} for a method; "" otherwise. */
        private static String key(Element element, Elements elements) {
            if (!(element.getEnclosingElement() instanceof TypeElement owner)) return "";
            String prefix = elements.getBinaryName(owner).toString().replace('.', '/') + "." + element.getSimpleName();
            if (element instanceof VariableElement) return prefix;
            if (element instanceof ExecutableElement method) {
                var descriptor = new StringBuilder("(");
                for (var parameter : method.getParameters()) descriptor.append(descriptor(parameter.asType(), elements));
                return prefix + descriptor.append(')').append(descriptor(method.getReturnType(), elements));
            }
            return "";
        }

        /** The erased JVM descriptor of a type as javac sees it in the edited unit. */
        private static String descriptor(TypeMirror type, Elements elements) {
            return switch (type.getKind()) {
                case BOOLEAN -> "Z"; case BYTE -> "B"; case CHAR -> "C"; case SHORT -> "S"; case INT -> "I";
                case LONG -> "J"; case FLOAT -> "F"; case DOUBLE -> "D"; case VOID -> "V";
                case ARRAY -> "[" + descriptor(((ArrayType) type).getComponentType(), elements);
                case DECLARED -> "L" + elements.getBinaryName((TypeElement) ((DeclaredType) type).asElement()).toString().replace('.', '/') + ";";
                case TYPEVAR -> descriptor(((TypeVariable) type).getUpperBound(), elements);
                case INTERSECTION -> descriptor(((IntersectionType) type).getBounds().get(0), elements);
                default -> "?";
            };
        }

        // ---- assert -------------------------------------------------------------------------------------------------

        @Override public Void visitIf(IfTree tree, Void ignored) {
            var field = assertionField(tree);
            if (field != null) {
                asserts.computeIfAbsent(field, key -> new ArrayList<>()).add(tree);
                // The condition's own use of the field is part of the site; its operand is still scanned.
                scan(((BinaryTree) strip(tree.getCondition())).getRightOperand(), null);
                scan(tree.getThenStatement(), null);
                return null;
            }
            return super.visitIf(tree, ignored);
        }

        @Override public Void visitIdentifier(IdentifierTree tree, Void ignored) {
            if (tree.getName().contentEquals("$assertionsDisabled")) use(tree);
            return super.visitIdentifier(tree, ignored);
        }

        @Override public Void visitMemberSelect(MemberSelectTree tree, Void ignored) {
            if (tree.getIdentifier().contentEquals("$assertionsDisabled")) use(tree);
            return super.visitMemberSelect(tree, ignored);
        }

        private void use(Tree tree) {
            if (trees.getElement(getCurrentPath()) instanceof VariableElement field)
                uses.computeIfAbsent(field, key -> new ArrayList<>()).add(tree);
        }

        /** The field of {@code if (!$assertionsDisabled && c) throw new AssertionError(m?);}, or null. */
        private VariableElement assertionField(IfTree tree) {
            if (tree.getElseStatement() != null || !(strip(tree.getCondition()) instanceof BinaryTree and)
                || and.getKind() != Tree.Kind.CONDITIONAL_AND || !(strip(and.getLeftOperand()) instanceof UnaryTree not)
                || not.getKind() != Tree.Kind.LOGICAL_COMPLEMENT || !(element(strip(not.getExpression())) instanceof VariableElement field)
                || !field.getSimpleName().contentEquals("$assertionsDisabled") || !field.getModifiers().contains(Modifier.STATIC)
                || assertionError(tree.getThenStatement()) == null) return null;
            return field;
        }

        /** The {@code new AssertionError(m?)} a statement (or a block of one statement) throws, or null. */
        private NewClassTree assertionError(StatementTree statement) {
            if (statement instanceof BlockTree block && block.getStatements().size() == 1) statement = block.getStatements().get(0);
            if (!(statement instanceof ThrowTree thrown) || !(strip(thrown.getExpression()) instanceof NewClassTree created)
                || created.getClassBody() != null || created.getEnclosingExpression() != null || created.getArguments().size() > 1
                || !(element(created.getIdentifier()) instanceof TypeElement type) || !type.getQualifiedName().contentEquals("java.lang.AssertionError"))
                return null;
            return created;
        }

        /**
         * One site per field: every {@code if} becomes an {@code assert}, and the field's declaration and its
         * {@code $assertionsDisabled = !C.class.desiredAssertionStatus();} initialization go (with a static block left
         * empty). Refused unless the original class has that synthetic static field and every other use of the field is
         * its declaration or that initialization.
         */
        private void assertSite(VariableElement field, List<IfTree> ifs) {
            var owner = (TypeElement) field.getEnclosingElement();
            var node = worker.node(internalName(owner));
            boolean synthetic = false;
            if (node != null)
                for (var declared : node.fields)
                    synthetic |= declared.name.equals("$assertionsDisabled") && declared.desc.equals("Z")
                        && (declared.access & (Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC)) == (Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC);
            if (!synthetic) { refuse(ifs.get(0), ASSERT, "field-not-synthetic-in-original"); return; }
            var edits = new ArrayList<Edit>();
            for (var tree : ifs) {
                var and = (BinaryTree) strip(tree.getCondition());
                var created = assertionError(tree.getThenStatement());
                String text = "assert " + negated(and.getRightOperand())
                    + (created.getArguments().isEmpty() ? "" : " : " + unit.source(created.getArguments().get(0))) + ";";
                edits.add(new Edit((int) unit.start(tree), (int) unit.end(tree), text));
            }
            ClassTree declaringClass = trees.getTree(owner);
            VariableTree declaration = null;
            var removedStatements = new HashSet<Tree>();
            for (var use : uses.getOrDefault(field, List.of())) {
                var path = path(use);
                var parent = path.getParentPath().getLeaf();
                if (parent instanceof AssignmentTree assignment && assignment.getVariable() == use && desiredStatus(assignment.getExpression())
                    && path.getParentPath().getParentPath().getLeaf() instanceof ExpressionStatementTree statement) {
                    removedStatements.add(statement);
                    continue;
                }
                refuse(ifs.get(0), ASSERT, "field-used-elsewhere"); return;
            }
            if (declaringClass == null) { refuse(ifs.get(0), ASSERT, "field-declaration-not-found"); return; }
            for (var member : declaringClass.getMembers())
                if (member instanceof VariableTree variable && trees.getElement(new TreePath(path(declaringClass), variable)) == field) declaration = variable;
            if (declaration == null || declaration.getInitializer() != null && !desiredStatus(declaration.getInitializer())) {
                refuse(ifs.get(0), ASSERT, "field-declaration-not-found"); return;
            }
            edits.add(removal(declaration));
            for (var member : declaringClass.getMembers()) {
                if (!(member instanceof BlockTree block)) continue;
                var statements = block.getStatements();
                if (!statements.isEmpty() && removedStatements.containsAll(statements) && block.isStatic()) {
                    edits.add(removal(block)); removedStatements.removeAll(statements);
                }
            }
            for (var statement : removedStatements) edits.add(removal(statement));
            edits.sort(Comparator.comparingInt(Edit::start));
            sites.add(new Site(ASSERT, unit.line(ifs.get(0)), edits, unit.source(ifs.get(0)), edits.stream()
                .filter(edit -> edit.text().startsWith("assert ")).findFirst().map(Edit::text).orElse("")));
        }

        /** {@code !C.class.desiredAssertionStatus()}. */
        private boolean desiredStatus(ExpressionTree expression) {
            return strip(expression) instanceof UnaryTree not && not.getKind() == Tree.Kind.LOGICAL_COMPLEMENT
                && strip(not.getExpression()) instanceof MethodInvocationTree call && call.getArguments().isEmpty()
                && call.getMethodSelect() instanceof MemberSelectTree select && select.getIdentifier().contentEquals("desiredAssertionStatus")
                && strip(select.getExpression()) instanceof MemberSelectTree literal && literal.getIdentifier().contentEquals("class");
        }

        /** Removes a member or statement and the blank it leaves on its line. */
        private Edit removal(Tree tree) {
            int start = (int) unit.start(tree), end = (int) unit.end(tree);
            String text = unit.text();
            // Annotations and modifiers come before the tree's start for a variable; the line is removed when it is only this.
            int lineStart = text.lastIndexOf('\n', start - 1) + 1, lineEnd = text.indexOf('\n', end);
            if (lineEnd < 0) lineEnd = text.length();
            if (text.substring(lineStart, start).isBlank() && text.substring(end, lineEnd).isBlank()) return new Edit(lineStart, Math.min(text.length(), lineEnd + 1), "");
            return new Edit(start, end, "");
        }

        /** The negation of an assert's failure condition: {@code ==} and {@code !=} swapped, {@code !x} unwrapped, else {@code !(c)}. */
        private String negated(ExpressionTree condition) {
            var bare = strip(condition);
            if (bare instanceof BinaryTree binary && (binary.getKind() == Tree.Kind.EQUAL_TO || binary.getKind() == Tree.Kind.NOT_EQUAL_TO))
                return unit.source(binary.getLeftOperand()) + (binary.getKind() == Tree.Kind.EQUAL_TO ? " != " : " == ") + unit.source(binary.getRightOperand());
            if (bare instanceof UnaryTree not && not.getKind() == Tree.Kind.LOGICAL_COMPLEMENT) return unit.source(not.getExpression());
            return "!(" + unit.source(bare) + ")";
        }

        // ---- helpers -------------------------------------------------------------------------------------------------

        private TreePath path(Tree tree) { return TreePath.getPath(unit.unit(), tree); }

        private Element element(Tree tree) {
            var path = path(tree);
            return path == null ? null : trees.getElement(path);
        }

        private static ExpressionTree strip(ExpressionTree expression) {
            while (expression instanceof ParenthesizedTree parenthesized) expression = parenthesized.getExpression();
            return expression;
        }

        private void refuse(Tree tree, String rule, String reason) { refusals.add(new Refusal(unit.line(tree), rule, reason)); }
    }
}
