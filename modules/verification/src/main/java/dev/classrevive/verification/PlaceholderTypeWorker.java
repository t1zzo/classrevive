package dev.classrevive.verification;

import com.sun.source.tree.*;
import com.sun.source.util.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;
import javax.lang.model.element.Element;
import javax.lang.model.type.*;
import javax.lang.model.util.Types;
import javax.tools.*;

/**
 * Phase 2CL: JADX output that is no Java, fixed from javac's own attribution so that the file parses.
 *
 * <ul>
 * <li>A local declared with JADX's placeholder type {@code ??} ({@code ?? B = B();}, written when its type inference
 * fails) gets its initializer's type, as {@code var} would, unless a later assignment does not fit that type: JADX merges
 * a local slot the bytecode reuses with unrelated types (in ZKM output the flag slot later holds an {@code int[]} or a
 * {@code RuntimeException}), and then only {@code Object} accepts every assignment. A type that is missing, erroneous,
 * the null type or not denotable is written as its erasure or as {@code Object}. A {@code /* ?? *}{@code /} marker follows.</li>
 * <li>An integer literal too large for {@code int} and without suffix (JADX writes a {@code double} constant's raw bits,
 * {@code d = 4613937818241073152;}) becomes a {@code long} literal when its target is {@code long}, or the
 * {@code double} those bits encode ({@code 3.0}) when its target is {@code double}.</li>
 * </ul>
 *
 * Every {@code ??} becomes {@code var} and every such literal gets an {@code L}; javac parses and attributes the sources
 * at release 10 or later (so {@code var} is accepted) and goes on after errors elsewhere. Runs only javac's parser and
 * attribution; never generates classes, loads target classes or runs annotation processors.
 *
 * <p>Arguments: sources, output folder, release, then classpath entries. Writes the changed files under the output folder
 * and {@code typed.tsv} (file, line, column, written text, rule) next to it.
 */
public final class PlaceholderTypeWorker {
    static final int MAX_SITES = 20_000;
    /**
     * A local declaration whose type JADX could not infer: {@code ??}, a name, then {@code =} or {@code ;}, first on its
     * line; or the variable of an enhanced {@code for} ({@code for (?? r0 : size)}, Phase 2CM: ZKM output once its flags are
     * folded), which {@code var} also accepts.
     */
    static final Pattern SITE = Pattern.compile(
        "(?m)(?:^[ \\t]*|\\bfor[ \\t]*\\([ \\t]*)(\\?\\?)[ \\t]+[\\p{L}_$][\\p{L}\\p{N}_$]*[ \\t]*(?:[=;]|:(?!:))");
    /** A decimal literal of 10 to 19 digits without suffix; {@link #oversized} keeps those beyond {@code int}. */
    static final Pattern LITERAL = Pattern.compile("(?<![\\p{L}\\p{N}_$.])[1-9][0-9]{9,18}(?![\\p{L}\\p{N}_$.])");
    static final String MARKER = " /* ?? */";
    static final Set<String> RULES = Set.of("initializer", "object", "long-literal", "double-bits");

    /** An edit at {@code start} in the original text, replacing {@code length} characters (0 for an insertion). */
    record Site(int start, int length, boolean literal) {}

    public static void main(String[] args) throws Exception {
        Path sources = Path.of(args[0]).toAbsolutePath().normalize(), output = Path.of(args[1]);
        int release = Math.max(10, Integer.parseInt(args[2]));
        var compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) throw new IllegalStateException("A JDK is required for placeholder types");
        var dependencies = new ArrayList<Path>();
        for (int i = 3; i < args.length; i++) dependencies.add(Path.of(args[i]));
        Path empty = Files.createDirectory(output.getParent().resolve("empty-classpath"));
        var texts = new LinkedHashMap<Path,String>(); var sites = new LinkedHashMap<Path,List<Site>>();
        for (var file : CompilerWorker.sourceFiles(sources)) {
            Path path = file.toPath().toAbsolutePath().normalize();
            String text = Files.readString(path, StandardCharsets.UTF_8);
            var found = sites(text);
            if (!found.isEmpty()) { texts.put(path, text); sites.put(path, found); }
        }
        var table = new StringBuilder();
        if (!sites.isEmpty()) {
            try (var manager = compiler.getStandardFileManager(null, Locale.ROOT, StandardCharsets.UTF_8)) {
                JdkRuntimeClasses.configure(manager, Integer.toString(release), dependencies, empty);
                var units = new ArrayList<JavaFileObject>(); var substituted = new HashMap<URI,Path>();
                for (var file : CompilerWorker.sourceFiles(sources)) {
                    Path path = file.toPath().toAbsolutePath().normalize();
                    if (!sites.containsKey(path)) { manager.getJavaFileObjects(file).forEach(units::add); continue; }
                    String text = substitute(texts.get(path), sites.get(path));
                    units.add(new SimpleJavaFileObject(path.toUri(), JavaFileObject.Kind.SOURCE) {
                        @Override public CharSequence getCharContent(boolean ignoreEncodingErrors) { return text; }
                    });
                    substituted.put(path.toUri(), path);
                }
                // Attribution goes on past errors in other files (should-stop at FLOW), so one broken file hides no type.
                var task = (JavacTask) compiler.getTask(null, manager, diagnostic -> { },
                    List.of("--release", Integer.toString(release), "-proc:none", "-implicit:none", "-sourcepath", empty.toString(),
                        "-XDshould-stop.ifError=FLOW", "-XDshould-stop.ifNoError=FLOW", "-Xmaxerrs", "1"), null, units);
                var parsed = new ArrayList<CompilationUnitTree>();
                task.parse().forEach(parsed::add);
                task.analyze();
                var trees = Trees.instance(task); var types = task.getTypes();
                for (var unit : parsed) {
                    Path path = substituted.get(unit.getSourceFile().toUri());
                    if (path != null) write(unit, trees, types, path, sources, texts.get(path), sites.get(path), output, table);
                }
            }
        }
        Files.writeString(output.getParent().resolve("typed.tsv"), table, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
    }

    /** The {@code ??} of each placeholder declaration and each oversized literal, outside comments and literals, in order. */
    static List<Site> sites(String text) {
        var found = new ArrayList<Site>();
        String masked = SourceText.mask(text);
        if (text.contains("??")) {
            var matcher = SITE.matcher(masked);
            while (matcher.find()) found.add(new Site(matcher.start(1), 2, false));
        }
        var matcher = LITERAL.matcher(masked);
        while (matcher.find()) if (oversized(matcher.group())) found.add(new Site(matcher.end(), 0, true));
        found.sort(Comparator.comparingInt(Site::start));
        return found.size() > MAX_SITES ? found.subList(0, MAX_SITES) : found;
    }
    /** Too large for an {@code int} literal, even after a unary minus, yet a valid {@code long}. */
    static boolean oversized(String digits) {
        try { return Long.parseLong(digits) > 2147483648L; } catch (NumberFormatException error) { return false; }
    }
    /** The text javac attributes: {@code var} for each {@code ??}, an {@code L} after each oversized literal. */
    static String substitute(String text, List<Site> sites) {
        var out = new StringBuilder(text.length() + sites.size()); int at = 0;
        for (var site : sites) { out.append(text, at, site.start()).append(site.literal() ? "L" : "var"); at = site.start() + site.length(); }
        return out.append(text.substring(at)).toString();
    }

    private static void write(CompilationUnitTree unit, Trees trees, Types types, Path path, Path sources, String text,
                              List<Site> sites, Path output, StringBuilder table) throws Exception {
        // Each edit before a site moves it right by one character in the substituted text. A placeholder is found at the
        // start of its declaration, a literal at its end (where the L was inserted).
        var byStart = new HashMap<Long,Integer>(); var byEnd = new HashMap<Long,Integer>();
        for (int i = 0; i < sites.size(); i++) {
            var site = sites.get(i);
            if (site.literal()) byEnd.put((long) site.start() + i + 1, i); else byStart.put((long) site.start() + i, i);
        }
        var written = new String[sites.size()]; var rule = new String[sites.size()];
        var positions = trees.getSourcePositions();
        var variables = new HashMap<Element,Integer>(); var declared = new HashMap<Integer,TypeMirror>();
        var assigned = new HashMap<Element,List<TypeMirror>>();
        new TreePathScanner<Void,Void>() {
            @Override public Void visitVariable(VariableTree node, Void unused) {
                Integer site = byStart.get(positions.getStartPosition(unit, node.getType() == null ? node : node.getType()));
                if (site == null) site = byStart.get(positions.getStartPosition(unit, node));
                if (site != null && !declared.containsKey(site)) {
                    declared.put(site, trees.getTypeMirror(getCurrentPath()));
                    var element = trees.getElement(getCurrentPath());
                    if (element != null) variables.put(element, site);
                }
                return super.visitVariable(node, unused);
            }
            @Override public Void visitAssignment(AssignmentTree node, Void unused) {
                var element = trees.getElement(new TreePath(getCurrentPath(), node.getVariable()));
                if (element != null) assigned.computeIfAbsent(element, ignored -> new ArrayList<>())
                    .add(trees.getTypeMirror(new TreePath(getCurrentPath(), node.getExpression())));
                return super.visitAssignment(node, unused);
            }
            @Override public Void visitLiteral(LiteralTree node, Void unused) {
                Integer site = byEnd.get(positions.getEndPosition(unit, node));
                if (site != null && node.getValue() instanceof Long bits) {
                    var parent = getCurrentPath().getParentPath().getLeaf();
                    TypeMirror target = parent instanceof AssignmentTree assignment && assignment.getExpression() == node
                        ? trees.getTypeMirror(new TreePath(getCurrentPath().getParentPath(), assignment.getVariable()))
                        : parent instanceof VariableTree variable && variable.getInitializer() == node
                        ? trees.getTypeMirror(getCurrentPath().getParentPath()) : null;
                    TypeKind kind = target == null ? TypeKind.NONE : target.getKind();
                    double value = Double.longBitsToDouble(bits);
                    if (kind == TypeKind.LONG) { written[site] = "L"; rule[site] = "long-literal"; }
                    else if (kind == TypeKind.DOUBLE && Double.isFinite(value)) {
                        written[site] = Double.toString(value); rule[site] = "double-bits";
                    }
                }
                return super.visitLiteral(node, unused);
            }
        }.scan(unit, null);
        var misfits = new HashSet<Integer>();
        for (var item : variables.entrySet()) {
            TypeMirror type = declared.get(item.getValue());
            for (var value : assigned.getOrDefault(item.getKey(), List.of()))
                if (type == null || value == null || value.getKind() == TypeKind.ERROR || !types.isAssignable(value, type)) misfits.add(item.getValue());
        }
        for (var item : declared.entrySet()) {
            int site = item.getKey();
            written[site] = misfits.contains(site) ? null : denotable(item.getValue(), types);
            rule[site] = written[site] == null ? "object" : "initializer";
            if (written[site] == null) written[site] = "Object";
        }
        var out = new StringBuilder(text.length() + sites.size() * 24); int at = 0; boolean changed = false;
        String file = sources.relativize(path).toString().replace('\\', '/');
        for (int i = 0; i < sites.size(); i++) {
            var site = sites.get(i);
            if (!site.literal() && written[i] == null) { written[i] = "Object"; rule[i] = "object"; }
            if (written[i] == null) continue; // a literal whose target is neither long nor double stays as JADX wrote it
            int start = site.start();
            if (site.literal() && rule[i].equals("double-bits")) {
                int digits = start; while (digits > 0 && Character.isDigit(text.charAt(digits - 1))) digits--;
                out.append(text, at, digits).append(written[i]);
            } else if (site.literal()) out.append(text, at, start).append(written[i]);
            else out.append(text, at, start).append(written[i]).append(MARKER);
            at = start + site.length(); changed = true;
            long line = 1, column = 1;
            for (int c = 0; c < start; c++) if (text.charAt(c) == '\n') { line++; column = 1; } else column++;
            table.append(file).append('\t').append(line).append('\t').append(column).append('\t').append(written[i])
                .append('\t').append(rule[i]).append('\n');
        }
        if (!changed) return;
        out.append(text.substring(at));
        Path target = output.resolve(file).normalize();
        if (!target.startsWith(output)) throw new IllegalStateException("Source path escapes output");
        Files.createDirectories(target.getParent());
        Files.writeString(target, out, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
    }

    /** Source text for a variable of {@code type}, or null when only {@code Object} can be written. */
    static String denotable(TypeMirror type, Types types) {
        if (type == null) return null;
        switch (type.getKind()) {
            case BOOLEAN, BYTE, SHORT, INT, LONG, CHAR, FLOAT, DOUBLE -> { return type.toString(); }
            case ARRAY -> {
                String component = denotable(((ArrayType) type).getComponentType(), types);
                return component == null ? null : component + "[]";
            }
            case DECLARED -> {
                var declared = (DeclaredType) type;
                if (declared.asElement() instanceof javax.lang.model.element.TypeElement typeElement
                    && typeElement.getNestingKind() == javax.lang.model.element.NestingKind.ANONYMOUS)
                    return denotable(typeElement.getSuperclass(), types);
                if (declared.getTypeArguments().stream().allMatch(PlaceholderTypeWorker::plain)) return type.toString();
                return types.erasure(type).toString();
            }
            case TYPEVAR -> {
                // A captured wildcard has no name in source; its erasure is its bound.
                if (type.toString().contains("capture#")) return denotable(types.erasure(type), types);
                return ((TypeVariable) type).asElement().getSimpleName().toString();
            }
            default -> { return null; } // ERROR, NULL, NONE, INTERSECTION, UNION, WILDCARD, ...
        }
    }
    private static boolean plain(TypeMirror argument) {
        return switch (argument.getKind()) {
            case DECLARED -> ((DeclaredType) argument).getTypeArguments().stream().allMatch(PlaceholderTypeWorker::plain)
                && !argument.toString().contains("capture#");
            case ARRAY -> plain(((ArrayType) argument).getComponentType());
            case BOOLEAN, BYTE, SHORT, INT, LONG, CHAR, FLOAT, DOUBLE -> true;
            default -> false;
        };
    }
}
