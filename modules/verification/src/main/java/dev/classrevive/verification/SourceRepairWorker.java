package dev.classrevive.verification;

import com.sun.source.tree.*;
import com.sun.source.util.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;
import javax.lang.model.element.*;
import javax.lang.model.type.*;
import javax.lang.model.util.ElementFilter;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.*;

/**
 * Proposes minimal source edits for javac diagnostics that decompilers commonly cause with generics. Runs only the JDK
 * compiler's parser and attribution; never generates classes, loads target classes or runs annotation processors.
 * Each edit is tied to one reported diagnostic (code, file, line, column) and is limited to method bodies and
 * initializers, so declarations and their structural signatures are never edited. The one exception is
 * {@code environment-stub} (Phase 2DE), which adds a marked method a JDK type requires only since after the original
 * class's target release, and which the reports list apart.
 *
 * <p>Arguments: sources, output folder for repaired files, release, then classpath entries. Writes the changed files
 * under the output folder, and {@code repairs.tsv} and {@code unrepaired.tsv} next to it. The system property
 * {@value #ORIGINAL_CLASSES} may name the normalized class JAR the engines decompiled; it is only read as bytes.
 */
public final class SourceRepairWorker {
    static final int MAX_DIAGNOSTICS = 1000, MAX_EDITS = 1000;
    static final String ORIGINAL_CLASSES = "classrevive.originalClasses";
    private static final Pattern DIAMOND = Pattern.compile("\\s*<\\s*>");
    private static final Pattern RENAMED_LOCAL_SUFFIX = Pattern.compile("x+");

    record Edit(int start, int end, String replacement, String rule, Diagnostic<? extends JavaFileObject> diagnostic) {}

    public static void main(String[] args) throws Exception {
        Path sources = Path.of(args[0]), output = Path.of(args[1]);
        var compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) throw new IllegalStateException("A JDK is required for source repair");
        var files = CompilerWorker.sourceFiles(sources);
        Path empty = Files.createDirectory(output.getParent().resolve("empty-classpath"));
        var dependencies = new ArrayList<Path>();
        for (int i = 3; i < args.length; i++) dependencies.add(Path.of(args[i]));
        var diagnostics = new ArrayList<Diagnostic<? extends JavaFileObject>>();
        boolean[] entered = {false}, syntax = {false};
        var repairs = new StringBuilder(); var unrepaired = new StringBuilder();
        try (var manager = compiler.getStandardFileManager(null, Locale.ROOT, StandardCharsets.UTF_8)) {
            JdkRuntimeClasses.configure(manager, args[2], dependencies, empty);
            if (!files.isEmpty()) {
                var task = (JavacTask) compiler.getTask(null, manager, diagnostic -> {
                    if (diagnostic.getKind() != Diagnostic.Kind.ERROR) return;
                    if (!entered[0]) syntax[0] = true;
                    if (diagnostics.size() < MAX_DIAGNOSTICS) diagnostics.add(diagnostic);
                }, List.of("--release", args[2], "-proc:none", "-implicit:none", "-encoding", "UTF-8",
                    "-sourcepath", empty.toString(), "-Xmaxerrs", Integer.toString(MAX_DIAGNOSTICS)),
                    null, manager.getJavaFileObjectsFromFiles(files));
                task.addTaskListener(new TaskListener() {
                    @Override public void started(TaskEvent event) { if (event.getKind() == TaskEvent.Kind.ENTER) entered[0] = true; }
                });
                var units = new ArrayList<CompilationUnitTree>();
                task.parse().forEach(units::add);
                // A file that does not parse hides attribution errors; edits are only proposed for attributed code.
                if (!syntax[0]) {
                    task.analyze();
                    var worker = new SourceRepairWorker(task, sources);
                    String originalClasses = System.getProperty(ORIGINAL_CLASSES);
                    if (originalClasses != null) worker.originals = MethodRoundTrip.readJar(Path.of(originalClasses));
                    worker.propose(units, diagnostics, output, repairs, unrepaired);
                }
            }
        }
        Files.writeString(output.getParent().resolve("repairs.tsv"), repairs, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        Files.writeString(output.getParent().resolve("unrepaired.tsv"), unrepaired, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
    }

    private final Trees trees; private final Types types; private final Elements elements; private final SourcePositions positions; private final Path sources;
    private CompilationUnitTree unit; private String text; private String currentFile = "";
    /** Names generated per file in this proposal, so two edits never introduce the same local name (Phase 2BB). */
    private final Map<String,Set<String>> reservedNames = new HashMap<>();
    /** Final locals already repaired in this proposal; a second diagnostic for the same local adds nothing. */
    private final Set<Element> repairedFinals = new HashSet<>();
    /** The original classes by internal name, for rules that read the original bytecode (Phase 2BM); null when absent. */
    private Map<String,byte[]> originals;
    private final Map<String,List<LambdaSites.Site>> indys = new HashMap<>();

    private SourceRepairWorker(JavacTask task, Path sources) {
        trees = Trees.instance(task); types = task.getTypes(); elements = task.getElements(); positions = trees.getSourcePositions(); this.sources = sources;
    }

    private void propose(List<CompilationUnitTree> units, List<Diagnostic<? extends JavaFileObject>> diagnostics, Path output,
                         StringBuilder repairs, StringBuilder unrepaired) throws Exception {
        var byFile = new LinkedHashMap<String,CompilationUnitTree>();
        for (var tree : units) {
            String name = relative(tree.getSourceFile());
            if (name != null) byFile.put(name, tree);
        }
        var editsByFile = new TreeMap<String,List<Edit>>();
        int total = 0;
        for (var diagnostic : diagnostics) {
            String file = diagnostic.getSource() == null ? null : relative(diagnostic.getSource());
            if (file == null || !byFile.containsKey(file)) continue;
            unit = byFile.get(file); text = unit.getSourceFile().getCharContent(true).toString(); currentFile = file;
            var found = new ArrayList<Edit>();
            String reason;
            try { reason = repair(diagnostic, found); }
            catch (RuntimeException error) { reason = "analysis-failed:" + error.getClass().getSimpleName(); found.clear(); }
            if (reason == null && total + found.size() > MAX_EDITS) reason = "edit-budget-exceeded";
            if (reason != null) {
                unrepaired.append(row(file, diagnostic.getLineNumber(), diagnostic.getColumnNumber(), diagnostic.getCode(), reason)).append('\n');
                continue;
            }
            editsByFile.computeIfAbsent(file, ignored -> new ArrayList<>()).addAll(found); total += found.size();
        }
        for (var entry : editsByFile.entrySet()) {
            unit = byFile.get(entry.getKey()); text = unit.getSourceFile().getCharContent(true).toString();
            var overlapping = new ArrayList<Edit>();
            var accepted = nonOverlapping(entry.getValue(), overlapping);
            for (var edit : overlapping) {
                var d = edit.diagnostic;
                unrepaired.append(row(entry.getKey(), d.getLineNumber(), d.getColumnNumber(), d.getCode(), "overlapping-edit")).append('\n');
            }
            var result = new StringBuilder(text);
            for (int i = accepted.size() - 1; i >= 0; i--) { var edit = accepted.get(i); result.replace(edit.start, edit.end, edit.replacement); }
            Path target = output.resolve(entry.getKey()).normalize();
            if (!target.startsWith(output)) throw new IllegalArgumentException("Repaired path escapes output");
            Files.createDirectories(target.getParent());
            Files.writeString(target, result, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
            for (var edit : accepted) {
                var d = edit.diagnostic;
                repairs.append(row(entry.getKey(), d.getLineNumber(), d.getColumnNumber(), d.getCode(), edit.rule))
                    .append('\t').append(escape(text.substring(edit.start, edit.end))).append('\t').append(escape(edit.replacement)).append('\n');
            }
        }
    }

    /** Identical edits proposed by several diagnostics are applied once; overlapping edits keep the first (the others go to {@code overlapping}). */
    private static List<Edit> nonOverlapping(List<Edit> edits, List<Edit> overlapping) {
        var accepted = new ArrayList<Edit>(); var keys = new HashSet<String>();
        var ordered = new ArrayList<>(edits);
        ordered.sort(Comparator.comparingInt(Edit::start).thenComparingInt(Edit::end));
        int last = 0;
        for (var edit : ordered) {
            if (!keys.add(edit.start + ":" + edit.end + ":" + edit.replacement)) continue;
            if (edit.start < last) { overlapping.add(edit); continue; }
            accepted.add(edit); last = edit.end;
        }
        return accepted;
    }

    /**
     * Phase 2BG: the edits these rules propose for {@code diagnostics} of one compilation unit already attributed by
     * {@code task}, in source order and without overlaps. Diagnostics no rule repairs are skipped. Method composition uses
     * it on a body copied from another engine that fails only in place.
     */
    static List<Edit> repairs(JavacTask task, CompilationUnitTree unit, List<Diagnostic<? extends JavaFileObject>> diagnostics,
                              Map<String,byte[]> originals) {
        var worker = new SourceRepairWorker(task, null);
        worker.unit = unit; worker.originals = originals;
        try { worker.text = unit.getSourceFile().getCharContent(true).toString(); } catch (java.io.IOException error) { return List.of(); }
        var found = new ArrayList<Edit>();
        for (var diagnostic : diagnostics) {
            var edits = new ArrayList<Edit>();
            try { if (worker.repair(diagnostic, edits) == null) found.addAll(edits); } catch (RuntimeException unrepairable) { }
        }
        return nonOverlapping(found, new ArrayList<>());
    }

    /** javac's {@code -Xlint:cast} warning for a cast whose operand already has the cast's type. */
    static final String REDUNDANT_CAST = "compiler.warn.redundant.cast";

    /**
     * Edits that take away the {@code checkcast} javac emits at one site of a method body (Phase 2BI); {@code types} are
     * the classes it casts to, as the instruction names them (internal names, or descriptors for arrays).
     */
    record CastSite(Set<String> types, List<Edit> edits) {}

    /**
     * Phase 2BI: the sites in an attributed method body where javac 21 emits a {@code checkcast} that a source edit can
     * take away. Every edit inserts or deletes text, so the expressions around a site keep their offsets.
     * <ul>
     * <li>{@code drop-redundant-cast}: a cast javac reports as redundant ({@link #REDUNDANT_CAST}, among {@code warnings}
     *     when the unit was compiled with {@code -Xlint:cast}), e.g. {@code (T) list.get(0)} for a {@code List<T>}; only
     *     its {@code (T)} goes.</li>
     * <li>{@code drop-upcast}: the same for a cast up to a supertype, which javac does not call redundant (see
     *     {@link #upcasts}).</li>
     * <li>{@code raw-receiver}, {@code raw-creation}, {@code erased-argument}: an argument whose value has the erasure of a
     *     type variable (a generic method's result, a generic field) passed to a parameter declared as a type variable.
     *     javac 9 and later (JDK-8058199) cast it to the parameter's instantiated type ({@code set.add(list.get(0))} on a
     *     {@code Set<Block3>} gets {@code checkcast Block3}); older javac cast it to the declared parameter's erasure, so
     *     the classes it built have none. A raw receiver ({@code ((java.util.Set) set).add(...)}, where the call's value is
     *     unused or keeps its erasure), a raw creation ({@code new Box(...)} for {@code new Box<>(...)}), or for a generic
     *     method's own type variable an upcast argument and a raw result ({@code ((Pair) Pair.make(a, (Object) x))}) make
     *     javac 21 emit the older bytecode.</li>
     * </ul>
     * The edits only change static types, but typing may then differ (overloads, inference), so the method composition
     * worker keeps a body only when its names still resolve as before and the round trip proves it.
     */
    static List<CastSite> castSites(JavacTask task, List<Diagnostic<? extends JavaFileObject>> warnings, TreePath body) {
        var worker = new SourceRepairWorker(task, null);
        worker.unit = body.getCompilationUnit();
        try { worker.text = worker.unit.getSourceFile().getCharContent(true).toString(); } catch (java.io.IOException error) { return List.of(); }
        int from = worker.start(body.getLeaf()), to = worker.end(body.getLeaf());
        var sites = new ArrayList<CastSite>(); var redundant = new HashSet<Tree>();
        for (var warning : warnings) {
            if (!REDUNDANT_CAST.equals(warning.getCode()) || warning.getStartPosition() < from || warning.getStartPosition() >= to) continue;
            try { worker.dropRedundantCast(warning, body, sites, redundant); } catch (RuntimeException unrepairable) { }
        }
        worker.upcasts(body, redundant, sites);
        worker.genericArguments(body, sites);
        return sites.stream().filter(site -> site.edits().stream().allMatch(edit -> edit.start() >= from && edit.end() <= to)).toList();
    }

    /**
     * The edits of the sites that cast to one of {@code types}, in source order; a site that would overlap an edit
     * already taken is left out whole. Insertions at one offset keep the order they were found in (outer expression first).
     */
    static List<Edit> castEdits(List<CastSite> sites, Set<String> types) {
        var taken = new ArrayList<Edit>();
        for (var site : sites) {
            if (site.types().stream().noneMatch(types::contains)) continue;
            if (site.edits().stream().anyMatch(edit -> taken.stream().anyMatch(other -> overlaps(edit, other)))) continue;
            taken.addAll(site.edits());
        }
        taken.sort(Comparator.comparingInt(Edit::start).thenComparingInt(Edit::end));
        return List.copyOf(taken);
    }

    /** Two edits replace the same characters, or one inserts inside the other's replaced span. */
    private static boolean overlaps(Edit a, Edit b) {
        return a.start() < b.end() && b.start() < a.end() || a.start() == a.end() && b.start() < a.start() && a.start() < b.end()
            || b.start() == b.end() && a.start() < b.start() && b.start() < a.end();
    }

    /** The site removing the {@code (T)} of the redundant cast javac warned about, when it is found at the warning. */
    private void dropRedundantCast(Diagnostic<? extends JavaFileObject> warning, TreePath body, List<CastSite> sites, Set<Tree> redundant) {
        long at = warning.getPosition() >= 0 ? warning.getPosition() : warning.getStartPosition();
        TreePath[] found = {null};
        new TreePathScanner<Void,Void>() {
            @Override public Void visitTypeCast(TypeCastTree tree, Void unused) {
                // The innermost cast whose span holds the warning and ends where it ends.
                if (start(tree) <= at && at < end(tree) && end(tree) == warning.getEndPosition()) found[0] = getCurrentPath();
                return super.visitTypeCast(tree, null);
            }
        }.scan(body, null);
        if (found[0] == null) return;
        redundant.add(found[0].getLeaf());
        dropCast(found[0], "drop-redundant-cast", warning, sites);
    }

    /**
     * {@code drop-upcast}: a reference cast whose operand's type is already a subtype of the cast's, which javac does not
     * call redundant but may still check: an array upcast ({@code (Object[]) types}, which Gson's decompiled
     * {@code GsonTypes.equals} had) or a generic value whose erasure is wider ({@code (Foo) list.get(0)} for a
     * {@code List<? extends Foo>}).
     */
    private void upcasts(TreePath body, Set<Tree> redundant, List<CastSite> sites) {
        new TreePathScanner<Void,Void>() {
            @Override public Void visitTypeCast(TypeCastTree tree, Void unused) {
                try {
                    TypeMirror type = trees.getTypeMirror(getCurrentPath()), operand = trees.getTypeMirror(new TreePath(getCurrentPath(), tree.getExpression()));
                    if (!redundant.contains(tree) && type != null && operand != null && (type.getKind() == TypeKind.DECLARED || type.getKind() == TypeKind.ARRAY)
                        && (operand.getKind() == TypeKind.DECLARED || operand.getKind() == TypeKind.ARRAY || operand.getKind() == TypeKind.TYPEVAR)
                        && types.isSubtype(operand, type)) dropCast(getCurrentPath(), "drop-upcast", null, sites);
                } catch (RuntimeException unrepairable) { }
                return super.visitTypeCast(tree, null);
            }
        }.scan(body, null);
    }

    /** The site removing a cast's {@code (T)}; an operand that is not a primary keeps parentheses. */
    private void dropCast(TreePath path, String rule, Diagnostic<? extends JavaFileObject> warning, List<CastSite> sites) {
        var cast = (TypeCastTree) path.getLeaf();
        String type = internalName(trees.getTypeMirror(path));
        ExpressionTree operand = cast.getExpression();
        int start = start(cast), inner = start(operand), end = end(operand);
        if (type == null || start < 0 || inner <= start || end <= inner || text.charAt(start) != '(') return;
        if (primary(operand)) {
            // `return(T)x` must not become `returnx`.
            boolean joined = start > 0 && Character.isJavaIdentifierPart(text.charAt(start - 1)) && Character.isJavaIdentifierPart(text.charAt(inner));
            sites.add(new CastSite(Set.of(type), List.of(new Edit(start, inner, joined ? " " : "", rule, warning))));
        } else {
            sites.add(new CastSite(Set.of(type), List.of(new Edit(start, inner, "(", rule, warning), new Edit(end, end, ")", rule, warning))));
        }
    }

    /** The {@code raw-receiver}, {@code raw-creation} and {@code erased-argument} sites of the calls in a body. */
    private void genericArguments(TreePath body, List<CastSite> sites) {
        new TreePathScanner<Void,Void>() {
            @Override public Void visitMethodInvocation(MethodInvocationTree tree, Void unused) {
                try { invocation(getCurrentPath(), tree, sites); } catch (RuntimeException unrepairable) { }
                return super.visitMethodInvocation(tree, null);
            }
            @Override public Void visitNewClass(NewClassTree tree, Void unused) {
                try { creation(getCurrentPath(), tree, sites); } catch (RuntimeException unrepairable) { }
                return super.visitNewClass(tree, null);
            }
        }.scan(body, null);
    }

    private void invocation(TreePath path, MethodInvocationTree tree, List<CastSite> sites) {
        if (!(trees.getElement(path) instanceof ExecutableElement method)
            || !(trees.getTypeMirror(new TreePath(path, tree.getMethodSelect())) instanceof ExecutableType instantiated)) return;
        var casted = casted(path, method, instantiated.getParameterTypes(), tree.getArguments());
        if (casted.isEmpty()) return;
        long own = casted.keySet().stream().filter(i -> variableOf(method.getParameters().get(i).asType(), method)).count();
        boolean statement = path.getParentPath().getLeaf() instanceof ExpressionStatementTree;
        var edits = new ArrayList<Edit>();
        if (own == 0) {
            // A class's type variable: the members of a raw receiver are erased, as the older javac saw every parameter.
            if (method.getModifiers().contains(Modifier.STATIC) || !(tree.getMethodSelect() instanceof MemberSelectTree select)) return;
            ExpressionTree receiver = select.getExpression();
            if (receiver instanceof IdentifierTree name && name.getName().contentEquals("super")) return;
            TypeMirror type = trees.getTypeMirror(new TreePath(new TreePath(path, select), receiver));
            if (type == null || type.getKind() != TypeKind.DECLARED || types.isSameType(type, types.erasure(type))) return;
            // The raw receiver erases the call's value too: only where it is unused or keeps its erasure.
            if (!statement && !types.isSameType(types.erasure(method.getReturnType()), types.erasure(instantiated.getReturnType()))) return;
            if (!wrap(receiver, types.erasure(type), "raw-receiver", edits)) return;
        } else if (own == casted.size()) {
            // The method's own type variable: an upcast argument has it inferred as its erasure; a raw cast of a generic
            // result keeps whatever type the context expects.
            for (int site : casted.keySet())
                if (!wrap(tree.getArguments().get(site), types.erasure(method.getParameters().get(site).asType()), "erased-argument", edits)) return;
            TypeMirror result = method.getReturnType();
            if (!statement && result.getKind() == TypeKind.TYPEVAR) return;
            if (!statement && !types.isSameType(result, types.erasure(result)) && !wrap(tree, types.erasure(result), "erased-argument", edits)) return;
        } else return;
        sites.add(new CastSite(new TreeSet<>(casted.values()), List.copyOf(edits)));
    }

    private void creation(TreePath path, NewClassTree tree, List<CastSite> sites) {
        if (tree.getClassBody() != null || tree.getEnclosingExpression() != null || !(tree.getIdentifier() instanceof ParameterizedTypeTree generic)) return;
        if (!(trees.getElement(path) instanceof ExecutableElement constructor) || !constructor.getTypeParameters().isEmpty()) return;
        if (!(trees.getTypeMirror(path) instanceof DeclaredType created) || !(types.asMemberOf(created, constructor) instanceof ExecutableType instantiated)) return;
        var casted = casted(path, constructor, instantiated.getParameterTypes(), tree.getArguments());
        int from = end(generic.getType()), to = end(generic);
        if (casted.isEmpty() || from < 0 || to <= from) return;
        // Without type arguments the class is raw and its constructor's parameters erased, as the older javac saw them.
        sites.add(new CastSite(new TreeSet<>(casted.values()), List.of(new Edit(from, to, "", "raw-creation", null))));
    }

    /**
     * The arguments javac 21 casts where older javac did not, by index, with the class each is cast to: the declared
     * parameter's erasure differs from the instantiated one's, and the argument's value (see {@link #erasedValue}) fits
     * the first but not the second. A variable-arity call's trailing arguments are left alone.
     */
    private Map<Integer,String> casted(TreePath call, ExecutableElement method, List<? extends TypeMirror> instantiated, List<? extends ExpressionTree> arguments) {
        var result = new TreeMap<Integer,String>();
        var declared = method.getParameters();
        if (instantiated.size() != declared.size()) return result;
        int fixed = Math.min(method.isVarArgs() ? declared.size() - 1 : declared.size(), arguments.size());
        for (int i = 0; i < fixed; i++) {
            TypeMirror before = types.erasure(declared.get(i).asType()), after = types.erasure(instantiated.get(i));
            if (after.getKind() != TypeKind.DECLARED && after.getKind() != TypeKind.ARRAY || types.isSameType(before, after)) continue;
            TypeMirror value = erasedValue(new TreePath(call, arguments.get(i)));
            if (value == null || types.isSubtype(value, after) || !types.isSubtype(value, before)) continue;
            String name = internalName(after);
            if (name != null) result.put(i, name);
        }
        return result;
    }

    /**
     * The erased type javac gives an argument's value before fitting it to the parameter: a call's declared result or a
     * field's declared type (a type variable's erasure when generic). Null for anything else, whose static type javac keeps.
     */
    private TypeMirror erasedValue(TreePath path) {
        while (path.getLeaf() instanceof ParenthesizedTree parenthesized) path = new TreePath(path, parenthesized.getExpression());
        Element element = trees.getElement(path);
        if (path.getLeaf() instanceof MethodInvocationTree && element instanceof ExecutableElement method) return types.erasure(method.getReturnType());
        if ((path.getLeaf() instanceof MemberSelectTree || path.getLeaf() instanceof IdentifierTree) && element != null && element.getKind() == ElementKind.FIELD)
            return types.erasure(element.asType());
        return null;
    }

    /** The declared type is (an array of) a type variable of {@code method} itself. */
    private static boolean variableOf(TypeMirror type, ExecutableElement method) {
        while (type instanceof ArrayType array) type = array.getComponentType();
        return type instanceof TypeVariable variable && variable.asElement() instanceof TypeParameterElement parameter
            && parameter.getGenericElement().equals(method);
    }

    /** Inserts {@code ((T) } before the expression and {@code )} after it (with its own parentheses when it is not a primary). */
    private boolean wrap(ExpressionTree expression, TypeMirror type, String rule, List<Edit> edits) {
        String name = sourceName(type);
        int start = start(expression), end = end(expression);
        if (name == null || start < 0 || end <= start) return false;
        boolean parenthesized = !primary(expression);
        edits.add(new Edit(start, start, "((" + name + ") " + (parenthesized ? "(" : ""), rule, null));
        edits.add(new Edit(end, end, parenthesized ? "))" : ")", rule, null));
        return true;
    }

    /** A canonical name for an erased class or array type, or null (local and anonymous classes have none). */
    private static String sourceName(TypeMirror type) {
        if (type instanceof ArrayType array) { String component = sourceName(array.getComponentType()); return component == null ? null : component + "[]"; }
        if (type.getKind().isPrimitive()) return type.toString();
        return qualifiedName(type);
    }

    /** The class a {@code checkcast} to this type names: its internal name, or its descriptor for an array. */
    private String internalName(TypeMirror type) {
        if (type == null) return null;
        TypeMirror erased = types.erasure(type);
        if (erased instanceof DeclaredType declared && declared.asElement() instanceof TypeElement element)
            return elements.getBinaryName(element).toString().replace('.', '/');
        return erased.getKind() == TypeKind.ARRAY ? descriptor(erased) : null;
    }

    private String descriptor(TypeMirror type) {
        return switch (type.getKind()) {
            case BOOLEAN -> "Z"; case BYTE -> "B"; case CHAR -> "C"; case SHORT -> "S"; case INT -> "I"; case LONG -> "J";
            case FLOAT -> "F"; case DOUBLE -> "D";
            case ARRAY -> { String component = descriptor(((ArrayType) type).getComponentType()); yield component == null ? null : "[" + component; }
            case DECLARED -> { String name = internalName(type); yield name == null ? null : "L" + name + ";"; }
            default -> null;
        };
    }

    /** Adds the edits for one diagnostic and returns null, or returns why it was left alone. */
    private String repair(Diagnostic<? extends JavaFileObject> diagnostic, List<Edit> edits) {
        if (diagnostic.getCode().equals("compiler.err.does.not.override.abstract")) return environmentStub(diagnostic, edits);
        TreePath path = locate(diagnostic.getStartPosition(), diagnostic.getEndPosition());
        if (path == null) return "unlocated";
        if (diagnostic.getCode().equals("compiler.err.initializer.must.be.able.to.complete.normally"))
            return guardInitializer(path, diagnostic, edits);
        if (!insideBody(path)) return "outside-method-body";
        return switch (diagnostic.getCode()) {
            case "compiler.err.prob.found.req" -> {
                // Phase 2BO: an invalid method reference, or a lambda whose implicit parameters lost their types.
                if (path.getLeaf() instanceof MemberReferenceTree)
                    yield receiverWitness(path, diagnostic, edits) == null ? null : functionalCast(path, diagnostic, edits);
                if (shadowedParameter(path, diagnostic, edits) == null) yield null;
                String reason = rawCastToTarget(path, diagnostic, edits);
                yield reason == null ? null : lambdaCast(path, diagnostic, edits) == null ? null : reason;
            }
            case "compiler.err.cant.apply.diamond", "compiler.err.cant.apply.diamond.1" -> rawDiamond(path, diagnostic, edits);
            case "compiler.err.non-static.cant.be.ref" -> rawTypeArgument(path, diagnostic, edits);
            case "compiler.err.cant.apply.symbol", "compiler.err.cant.apply.symbols" ->
                creationTypeArguments(path, diagnostic, edits) == null ? null : rawArguments(path, diagnostic, edits);
            case "compiler.err.cant.assign.val.to.var" -> dropFinalLocal(path, diagnostic, edits);
            case "compiler.err.cant.ref.non.effectively.final.var" -> captureCopy(path, diagnostic, edits);
            case "compiler.err.cant.resolve", "compiler.err.cant.resolve.location" -> {
                String reason = renamedLocal(path, diagnostic, edits);
                // Phase 2BN: a type variable out of scope in a cast's type arguments, e.g. Guava's MapMaker casting to its
                // superclass's field type `(RemovalListener<K0, V0>)`; the raw cast emits the same checkcast.
                if (reason != null && path.getParentPath() != null && path.getParentPath().getLeaf() instanceof ParameterizedTypeTree
                    && rawCastType(path.getParentPath(), diagnostic, edits)) yield null;
                if (!"no-renamed-local".equals(reason)) yield reason;
                String hoist = hoistLocalClass(path, diagnostic, edits);
                yield "no-later-local-class".equals(hoist) ? reason : hoist;
            }
            case "compiler.err.incomparable.types" -> {
                String reason = booleanLiteralAsInt(path, diagnostic, edits);
                yield "no-boolean-literal".equals(reason) ? typeWitness(path, diagnostic, edits) : reason;
            }
            case "compiler.err.unreachable.stmt" -> dropUnreachableJump(path, diagnostic, edits);
            case "compiler.err.unexpected.mref", "compiler.err.unexpected.lambda" -> {
                String reason = iterableCast(path, diagnostic, edits);
                yield "not-iterated-by-enhanced-for".equals(reason) ? functionalCast(path, diagnostic, edits) : reason;
            }
            default -> "no-rule";
        };
    }

    /**
     * A lambda or method reference iterated by an enhanced {@code for}, e.g. Ant's
     * {@code for (String s : (Iterable<String>) stream::iterator)} printed without its cast. An enhanced {@code for} over a
     * functional expression only compiles with a cast to {@code Iterable} of the loop variable's type, which is the
     * interface the original invokedynamic created; a cast that gives a lambda its target type emits no instruction.
     * Primitive loop variables (which would need unboxing) are refused.
     */
    private String iterableCast(TreePath path, Diagnostic<? extends JavaFileObject> diagnostic, List<Edit> edits) {
        Tree leaf = path.getLeaf();
        if (!(leaf instanceof MemberReferenceTree) && !(leaf instanceof LambdaExpressionTree)) return "not-a-functional-expression";
        TreePath at = path;
        while (at.getParentPath() != null && at.getParentPath().getLeaf() instanceof ParenthesizedTree) at = at.getParentPath();
        if (!(at.getParentPath() != null && at.getParentPath().getLeaf() instanceof EnhancedForLoopTree loop) || loop.getExpression() != at.getLeaf())
            return "not-iterated-by-enhanced-for";
        var variable = loop.getVariable();
        if (variable.getType() == null || start(variable.getType()) < 0 || variable.getType() instanceof PrimitiveTypeTree) return "loop-variable-type-not-a-class";
        String expression = source(at.getLeaf());
        edits.add(new Edit(start(at.getLeaf()), end(at.getLeaf()), "(java.lang.Iterable<" + source(variable.getType()) + ">) "
            + (at.getLeaf() instanceof ParenthesizedTree ? expression : "(" + expression + ")"), "iterable-cast", diagnostic));
        return null;
    }

    /**
     * Phase 2BM: a method reference printed where nothing gives it a target type, e.g. Ant's
     * {@code ((Predicate<String>) String::isEmpty).negate()} printed as {@code (String::isEmpty).negate()}. The source does
     * not say which interface the reference implements, but the original {@code invokedynamic} does: its descriptor
     * returns the interface, and its instantiated method type gives the type arguments through the interface's abstract
     * method. The reference is matched to the call sites of its top-level class and nested classes by implementation
     * name and, for {@code Type::method}, the owner's simple name; every match must agree. The cast gives the reference
     * its target type and emits no instruction. Lambdas are refused: their implementation is a synthetic method whose
     * name does not identify the source lambda.
     */
    private String functionalCast(TreePath path, Diagnostic<? extends JavaFileObject> diagnostic, List<Edit> edits) {
        Object match = site(path);
        if (!(match instanceof LambdaSites.Site chosen)) return (String) match;
        String cast = functionalType(chosen);
        if (cast == null) return "type-arguments-not-determined";
        TreePath at = path;
        while (at.getParentPath() != null && at.getParentPath().getLeaf() instanceof ParenthesizedTree) at = at.getParentPath();
        String expression = source(at.getLeaf());
        // Outer parentheses: a cast binds less tightly than the member selection that usually follows (`.negate()`).
        edits.add(new Edit(start(at.getLeaf()), end(at.getLeaf()), "((" + cast + ") "
            + (at.getLeaf() instanceof ParenthesizedTree ? expression : "(" + expression + ")") + ")", "functional-cast", diagnostic));
        return null;
    }
    /**
     * Phase 2BS: a method reference passed to a method of a generic call's result, e.g. Ant's
     * {@code Predicate.<File>isEqual(known).or(File::exists)} printed without the witness: {@code isEqual(Object)} then
     * infers {@code Predicate<Object>}, and {@code File::exists} does not fit {@code or}. The witness comes from the
     * reference's original {@code invokedynamic}: when the inner call's result has the reference's functional interface,
     * each of the call's type variables that is a type argument of that result takes the argument the instantiated type
     * gives. Type arguments are erased, so the instructions are unchanged.
     */
    private String receiverWitness(TreePath path, Diagnostic<? extends JavaFileObject> diagnostic, List<Edit> edits) {
        TreePath argument = path;
        while (argument.getParentPath() != null && argument.getParentPath().getLeaf() instanceof ParenthesizedTree) argument = argument.getParentPath();
        if (!(argument.getParentPath().getLeaf() instanceof MethodInvocationTree call) || !call.getArguments().contains(argument.getLeaf())
            || !(call.getMethodSelect() instanceof MemberSelectTree outerSelect)) return "not-an-argument-of-a-member-call";
        ExpressionTree receiver = outerSelect.getExpression();
        while (receiver instanceof ParenthesizedTree parenthesized) receiver = parenthesized.getExpression();
        if (!(receiver instanceof MethodInvocationTree inner) || !inner.getTypeArguments().isEmpty()
            || !(inner.getMethodSelect() instanceof MemberSelectTree select)) return "receiver-not-a-generic-call";
        var selected = TreePath.getPath(unit, select);
        if (selected == null || !(trees.getElement(selected) instanceof ExecutableElement method) || method.getTypeParameters().isEmpty()
            || !(method.getReturnType() instanceof DeclaredType result)) return "receiver-not-a-generic-call";
        Object match = site(path);
        if (!(match instanceof LambdaSites.Site site)) return (String) match;
        var arguments = functionalArguments(site);
        if (arguments == null) return "type-arguments-not-determined";
        if (!((TypeElement) result.asElement()).getQualifiedName().contentEquals(site.iface().replace('/', '.').replace('$', '.'))
            || result.getTypeArguments().size() != arguments.size()) return "receiver-of-another-interface";
        var bound = new HashMap<Element,String>();
        for (int i = 0; i < arguments.size(); i++)
            if (result.getTypeArguments().get(i) instanceof TypeVariable variable && method.getTypeParameters().contains(variable.asElement())) {
                String previous = bound.putIfAbsent(variable.asElement(), arguments.get(i));
                if (previous != null && !previous.equals(arguments.get(i))) return "type-arguments-conflict";
            }
        var witnesses = new StringJoiner(", ", "<", ">");
        for (var parameter : method.getTypeParameters()) {
            String written = bound.get(parameter);
            if (written == null) return "type-arguments-not-determined";
            witnesses.add(written);
        }
        String name = select.getIdentifier().toString();
        int identifier = end(select) - name.length();
        if (identifier <= start(select) || !text.startsWith(name, identifier)) return "unlocated";
        edits.add(new Edit(identifier, identifier, witnesses.toString(), "receiver-witness", diagnostic));
        return null;
    }
    /** The original call site of a method reference, or why there is none (a String). */
    private Object site(TreePath path) {
        if (!(path.getLeaf() instanceof MemberReferenceTree reference)) return "not-a-method-reference";
        if (originals == null) return "no-original-classes";
        ClassTree outer = null;
        for (TreePath at = path; at.getParentPath() != null; at = at.getParentPath())
            if (at.getLeaf() instanceof ClassTree type && at.getParentPath().getLeaf() instanceof CompilationUnitTree) outer = type;
        if (outer == null) return "no-enclosing-class";
        String top = (unit.getPackageName() == null ? "" : unit.getPackageName().toString().replace('.', '/') + "/") + outer.getSimpleName();
        String implName = reference.getMode() == MemberReferenceTree.ReferenceMode.NEW ? "<init>" : reference.getName().toString();
        Tree qualifier = reference.getQualifierExpression();
        Element qualified = trees.getElement(new TreePath(path, qualifier));
        String owner = qualifier instanceof MemberSelectTree select ? select.getIdentifier().toString()
            : qualifier instanceof IdentifierTree identifier ? identifier.getName().toString() : null;
        // Only a type qualifier names the owner; a bound receiver (`p::m`, `this::m`) is a value.
        boolean typeQualifier = owner != null && (qualified != null ? qualified.getKind().isClass() || qualified.getKind().isInterface()
            : Character.isUpperCase(owner.charAt(0)));
        LambdaSites.Site chosen = null;
        for (var indy : indys.computeIfAbsent(top, name -> LambdaSites.read(originals, name))) {
            if (!indy.implName().equals(implName)) continue;
            if (typeQualifier && !simpleName(indy.implOwner()).equals(owner)) continue;
            if (chosen != null && !(chosen.iface().equals(indy.iface()) && chosen.method().equals(indy.method())
                && chosen.instantiated().equals(indy.instantiated()))) return "ambiguous-invokedynamic";
            chosen = indy;
        }
        return chosen == null ? "no-matching-invokedynamic" : chosen;
    }
    /**
     * Phase 2BO: a lambda whose implicitly typed parameters javac types as {@code Object} because the call it is passed
     * to has a raw receiver, e.g. Ant's {@code raw.forEach((arg, value) -> project.setUserProperty(arg, ...))} on a
     * {@code Map} declared raw, where the original had {@code Map<String, Object>}. The error sits on a use of a lambda
     * parameter. The lambda is cast to the functional interface of its original {@code invokedynamic}, with type arguments
     * from the instantiated method type, as {@link #functionalCast} does. javac names a lambda's implementation
     * {@code lambda$method$N}, and the number does not identify a source lambda, so the cast is made only when the
     * enclosing method holds exactly one lambda and the original has exactly one implementation for that method.
     */
    private String lambdaCast(TreePath path, Diagnostic<? extends JavaFileObject> diagnostic, List<Edit> edits) {
        if (originals == null) return "no-original-classes";
        if (!(path.getLeaf() instanceof IdentifierTree) || !(trees.getElement(path) instanceof VariableElement used)
            || used.getKind() != ElementKind.PARAMETER) return "not-a-lambda-parameter";
        TreePath lambda = null, method = null;
        ClassTree outer = null;
        for (TreePath at = path; at.getParentPath() != null; at = at.getParentPath()) {
            if (lambda == null && at.getLeaf() instanceof LambdaExpressionTree && declares(at, used)) lambda = at;
            if (method == null && at.getLeaf() instanceof MethodTree) method = at;
            if (at.getLeaf() instanceof ClassTree type && at.getParentPath().getLeaf() instanceof CompilationUnitTree) outer = type;
        }
        if (lambda == null) return "not-a-lambda-parameter";
        var expression = (LambdaExpressionTree) lambda.getLeaf();
        if (expression.getParameters().stream().anyMatch(parameter -> parameter.getType() != null && start(parameter.getType()) >= 0))
            return "explicitly-typed-lambda";
        if (method == null || outer == null) return "no-enclosing-method";
        int[] lambdas = {0};
        new TreeScanner<Void,Void>() {
            @Override public Void visitLambdaExpression(LambdaExpressionTree tree, Void unused) { lambdas[0]++; return super.visitLambdaExpression(tree, unused); }
            @Override public Void visitClass(ClassTree tree, Void unused) { return null; }
        }.scan(((MethodTree) method.getLeaf()).getBody(), null);
        if (lambdas[0] != 1) return "several-lambdas-in-method";
        String name = ((MethodTree) method.getLeaf()).getName().toString();
        String prefix = "lambda$" + (name.equals("<init>") ? "new" : name) + "$";
        String top = (unit.getPackageName() == null ? "" : unit.getPackageName().toString().replace('.', '/') + "/") + outer.getSimpleName();
        LambdaSites.Site chosen = null;
        for (var site : indys.computeIfAbsent(top, key -> LambdaSites.read(originals, key))) {
            if (!site.implName().startsWith(prefix)) continue;
            if (chosen != null) return "ambiguous-invokedynamic";
            chosen = site;
        }
        if (chosen == null) return "no-matching-invokedynamic";
        String cast = functionalType(chosen);
        if (cast == null) return "type-arguments-not-determined";
        if (LambdaSites.parameters(chosen.instantiated()).size() != expression.getParameters().size()) return "arity-differs";
        String source = source(expression);
        edits.add(new Edit(start(expression), end(expression), "((" + cast + ") (" + source + "))", "lambda-cast", diagnostic));
        return null;
    }
    private boolean declares(TreePath lambda, Element parameter) {
        for (var declared : ((LambdaExpressionTree) lambda.getLeaf()).getParameters())
            if (parameter.equals(trees.getElement(new TreePath(lambda, declared)))) return true;
        return false;
    }
    /**
     * Phase 2BR: a use of a captured variable where the decompiler meant the method's own parameter, which it renamed
     * to avoid the clash, e.g. Vineflower on Guava's {@code Futures.lazyTransform}: {@code applyTransformation(I inputx)}
     * returning {@code function.apply(input)}, where {@code input} is the enclosing method's {@code Future}. The
     * original passes the parameter (CFR, Procyon and JADX print it so). The use is replaced by the parameter only when
     * the variable is declared outside the enclosing method, and the method has exactly one parameter named like it plus
     * a renaming suffix ({@code inputx}, {@code inputxx}). A cast wrapped around the use by an earlier repair round goes
     * with it. The selection keeps the edit only if the errors drop.
     */
    private String shadowedParameter(TreePath path, Diagnostic<? extends JavaFileObject> diagnostic, List<Edit> edits) {
        TreePath use = path;
        if (use.getLeaf() instanceof TypeCastTree cast && cast.getExpression() instanceof IdentifierTree)
            use = new TreePath(use, cast.getExpression());
        if (!(use.getLeaf() instanceof IdentifierTree identifier) || !(trees.getElement(use) instanceof VariableElement used))
            return "not-a-variable";
        TreePath method = null;
        for (TreePath at = use; at != null && method == null; at = at.getParentPath()) if (at.getLeaf() instanceof MethodTree) method = at;
        if (method == null) return "no-enclosing-method";
        var declaration = trees.getPath(used);
        if (declaration != null && declaration.getCompilationUnit() == unit) {
            long from = positions.getStartPosition(unit, method.getLeaf()), to = positions.getEndPosition(unit, method.getLeaf());
            long at = positions.getStartPosition(unit, declaration.getLeaf());
            if (at >= from && at <= to) return "declared-in-method";
        }
        String name = identifier.getName().toString(), renamed = null;
        for (var parameter : ((MethodTree) method.getLeaf()).getParameters()) {
            String candidate = parameter.getName().toString();
            if (candidate.startsWith(name) && candidate.length() > name.length()
                && RENAMED_LOCAL_SUFFIX.matcher(candidate.substring(name.length())).matches()) {
                if (renamed != null) return "ambiguous-renamed-parameter";
                renamed = candidate;
            }
        }
        if (renamed == null) return "no-renamed-parameter";
        edits.add(new Edit(start(path.getLeaf()), end(path.getLeaf()), renamed, "shadowed-parameter", diagnostic));
        return null;
    }
    /**
     * Phase 2BU: a raw creation of a generic class at the head of an argument, e.g. picocli's
     * {@code parseWithHandlers(new RunLast().useOut(out), new DefaultExceptionHandler<List<Object>>().useErr(err), args)}
     * printed with a raw {@code new DefaultExceptionHandler()}: the raw type erases the self-typed results of
     * {@code useErr} and {@code useAnsi}, so no overload applies. For a generic method of that name and arity, its type
     * variables are bound from the other arguments' types (through their supertypes), the raw argument's parameter type
     * is instantiated with them, and the created class's type arguments are the ones that make it that type. Exactly one
     * binding must result over all candidate methods. Type arguments are erased, so the instructions are unchanged.
     */
    private String creationTypeArguments(TreePath path, Diagnostic<? extends JavaFileObject> diagnostic, List<Edit> edits) {
        TreePath callPath = path;
        for (int up = 0; callPath != null && up < 3 && !(callPath.getLeaf() instanceof MethodInvocationTree); up++) callPath = callPath.getParentPath();
        if (callPath == null || !(callPath.getLeaf() instanceof MethodInvocationTree call)) return "not-a-call";
        String name; TypeMirror receiver;
        if (call.getMethodSelect() instanceof MemberSelectTree select) {
            name = select.getIdentifier().toString();
            receiver = trees.getTypeMirror(new TreePath(new TreePath(callPath, select), select.getExpression()));
        } else if (call.getMethodSelect() instanceof IdentifierTree identifier) {
            name = identifier.getName().toString();
            var scope = trees.getScope(callPath);
            receiver = scope == null || scope.getEnclosingClass() == null ? null : scope.getEnclosingClass().asType();
        } else return "not-a-call";
        if (!(receiver instanceof DeclaredType declaredReceiver) || !(declaredReceiver.asElement() instanceof TypeElement receiverType)) return "receiver-unknown";
        var arguments = call.getArguments();
        String found = null; NewClassTree target = null;
        for (var method : ElementFilter.methodsIn(elements.getAllMembers(receiverType))) {
            if (!method.getSimpleName().contentEquals(name) || method.getTypeParameters().isEmpty()) continue;
            int formals = method.getParameters().size();
            if (method.isVarArgs() ? arguments.size() < formals - 1 : arguments.size() != formals) continue;
            var bound = new HashMap<Element,TypeMirror>();
            int raw = -1; NewClassTree creation = null; boolean usable = true;
            for (int i = 0; i < arguments.size() && usable; i++) {
                TypeMirror formal = formal(method, i);
                var head = rawCreationHead(arguments.get(i));
                if (head != null) {
                    if (raw >= 0) { usable = false; break; }
                    raw = i; creation = head; continue;
                }
                TypeMirror actual = trees.getTypeMirror(new TreePath(callPath, arguments.get(i)));
                if (actual == null || actual.getKind() == TypeKind.ERROR) continue;
                usable = bindTypeVariables(formal, actual, method, bound);
            }
            if (!usable || raw < 0) continue;
            String written = createdArguments(creation, callPath, formal(method, raw), method, bound);
            if (written == null) continue;
            if (found != null && !found.equals(written)) return "ambiguous-overloads";
            found = written; target = creation;
        }
        if (found == null) return "no-binding";
        int at = end(target.getIdentifier());
        if (at <= start(target.getIdentifier())) return "unlocated";
        edits.add(new Edit(at, at, found, "creation-type-argument", diagnostic));
        return null;
    }
    private static TypeMirror formal(ExecutableElement method, int index) {
        var parameters = method.getParameters();
        if (method.isVarArgs() && index >= parameters.size() - 1) {
            TypeMirror last = parameters.get(parameters.size() - 1).asType();
            return last instanceof ArrayType array ? array.getComponentType() : last;
        }
        return parameters.get(index).asType();
    }
    /** The raw {@code new G(...)} an argument starts with ({@code new G().a().b()}), when G is generic; null otherwise. */
    private NewClassTree rawCreationHead(ExpressionTree argument) {
        ExpressionTree at = argument;
        while (true) {
            while (at instanceof ParenthesizedTree parenthesized) at = parenthesized.getExpression();
            if (at instanceof MethodInvocationTree invocation && invocation.getMethodSelect() instanceof MemberSelectTree select) at = select.getExpression();
            else break;
        }
        if (!(at instanceof NewClassTree created) || created.getClassBody() != null || created.getIdentifier() instanceof ParameterizedTypeTree) return null;
        var path = TreePath.getPath(unit, created.getIdentifier());
        return path != null && trees.getElement(path) instanceof TypeElement type && !type.getTypeParameters().isEmpty() ? created : null;
    }
    /** Binds the method's type variables in {@code formal} to the matching parts of {@code actual}; false on a conflict. */
    private boolean bindTypeVariables(TypeMirror formal, TypeMirror actual, ExecutableElement method, Map<Element,TypeMirror> bound) {
        if (formal instanceof WildcardType wildcard) {
            TypeMirror inner = wildcard.getExtendsBound() != null ? wildcard.getExtendsBound() : wildcard.getSuperBound();
            return inner == null || bindTypeVariables(inner, actual, method, bound);
        }
        if (formal instanceof TypeVariable variable && method.getTypeParameters().contains(variable.asElement())) {
            if (actual.getKind() == TypeKind.WILDCARD || actual.getKind().isPrimitive()) return true;
            var previous = bound.putIfAbsent(variable.asElement(), actual);
            return previous == null || types.isSameType(previous, actual);
        }
        if (!(formal instanceof DeclaredType declared) || declared.getTypeArguments().isEmpty()) return true;
        var view = supertypeLike(actual, declared);
        if (view == null) return true;
        for (int k = 0; k < declared.getTypeArguments().size(); k++)
            if (!bindTypeVariables(declared.getTypeArguments().get(k), view.getTypeArguments().get(k), method, bound)) return false;
        return true;
    }
    /** The supertype of {@code type} (itself included) with the erasure of {@code like}, when it has type arguments. */
    private DeclaredType supertypeLike(TypeMirror type, DeclaredType like) {
        var queue = new ArrayDeque<TypeMirror>(List.of(type)); var seen = new HashSet<String>();
        while (!queue.isEmpty()) {
            TypeMirror next = queue.poll();
            if (!seen.add(next.toString())) continue;
            if (next instanceof DeclaredType declared && types.isSameType(types.erasure(declared), types.erasure(like))
                && declared.getTypeArguments().size() == like.getTypeArguments().size()) return declared;
            queue.addAll(types.directSupertypes(next));
        }
        return null;
    }
    /** {@code <A, B>} for the created class so that it is {@code formal} with the bound variables, or null. */
    private String createdArguments(NewClassTree creation, TreePath callPath, TypeMirror formal, ExecutableElement method, Map<Element,TypeMirror> bound) {
        if (formal instanceof WildcardType wildcard) formal = wildcard.getExtendsBound() != null ? wildcard.getExtendsBound() : wildcard.getSuperBound();
        if (!(formal instanceof DeclaredType wanted)) return null;
        var path = TreePath.getPath(unit, creation.getIdentifier());
        if (path == null || !(trees.getElement(path) instanceof TypeElement created)) return null;
        var view = supertypeLike(created.asType(), wanted);
        if (view == null) return null;
        var values = new HashMap<Element,String>();
        for (int k = 0; k < wanted.getTypeArguments().size(); k++) {
            TypeMirror want = wanted.getTypeArguments().get(k), have = view.getTypeArguments().get(k);
            if (want instanceof WildcardType wildcard) want = wildcard.getExtendsBound() != null ? wildcard.getExtendsBound() : wildcard.getSuperBound();
            if (want instanceof TypeVariable variable && method.getTypeParameters().contains(variable.asElement())) want = bound.get(variable.asElement());
            if (want == null || !(have instanceof TypeVariable own) || !created.getTypeParameters().contains(own.asElement())) continue;
            String written = witness(want);
            if (written == null) return null;
            var previous = values.putIfAbsent(own.asElement(), written);
            if (previous != null && !previous.equals(written)) return null;
        }
        var joined = new StringJoiner(", ", "<", ">");
        for (var parameter : created.getTypeParameters()) {
            String written = values.get(parameter);
            if (written == null) return null;
            joined.add(written);
        }
        return joined.toString();
    }
    private static String simpleName(String internalName) {
        return internalName.substring(Math.max(internalName.lastIndexOf('/'), internalName.lastIndexOf('$')) + 1);
    }
    /**
     * The interface with its type arguments, e.g. {@code java.util.function.Predicate<java.lang.String>}: each type
     * variable of the interface bound by the abstract method's parameters and result, as seen from the interface, to the
     * instantiated types (erased, so a generic argument is written raw). Null when a variable stays unbound, is bound
     * twice differently, or would be a primitive.
     */
    private String functionalType(LambdaSites.Site indy) {
        var arguments = functionalArguments(indy);
        if (arguments == null) return null;
        TypeElement type = elements.getTypeElement(indy.iface().replace('/', '.').replace('$', '.'));
        return type.getQualifiedName() + (arguments.isEmpty() ? "" : "<" + String.join(", ", arguments) + ">");
    }
    /** The interface's type arguments in declaration order, bound as {@link #functionalType} describes; null if any is not. */
    private List<String> functionalArguments(LambdaSites.Site indy) {
        TypeElement type = elements.getTypeElement(indy.iface().replace('/', '.').replace('$', '.'));
        if (type == null || type.getKind() != ElementKind.INTERFACE) return null;
        ExecutableElement method = null;
        for (var member : ElementFilter.methodsIn(elements.getAllMembers(type)))
            if (member.getModifiers().contains(Modifier.ABSTRACT) && member.getSimpleName().contentEquals(indy.method())
                && member.getEnclosingElement().getKind() == ElementKind.INTERFACE) {
                if (method != null) return null;
                method = member;
            }
        if (method == null) return null;
        var seen = (ExecutableType) types.asMemberOf((DeclaredType) type.asType(), method);
        var arguments = LambdaSites.parameters(indy.instantiated());
        if (seen.getParameterTypes().size() != arguments.size()) return null;
        var bound = new HashMap<String,String>();
        for (int i = 0; i < arguments.size(); i++) if (!bind(seen.getParameterTypes().get(i), arguments.get(i), bound)) return null;
        if (!bind(seen.getReturnType(), LambdaSites.result(indy.instantiated()), bound)) return null;
        var written = new ArrayList<String>();
        for (var parameter : type.getTypeParameters()) {
            String argument = bound.get(parameter.getSimpleName().toString());
            if (argument == null) return null;
            written.add(argument);
        }
        return written;
    }
    /** {@code actual} is a source type name, or null for a primitive, which no type variable can stand for. */
    private static boolean bind(TypeMirror declared, String name, Map<String,String> bound) {
        if (!(declared instanceof TypeVariable variable)) return true;
        if (name == null) return false;
        String previous = bound.putIfAbsent(variable.asElement().getSimpleName().toString(), name);
        return previous == null || previous.equals(name);
    }

    /**
     * An unreachable {@code break} or {@code continue}, e.g. the {@code break;} a decompiler prints after an inner switch
     * whose every case jumps or throws. A jump statement has no other effect, it was never executed, and javac emits no
     * code for it; since the statement before it cannot complete normally, removing it cannot create a fall-through.
     * Any other unreachable statement is left alone.
     */
    private String dropUnreachableJump(TreePath path, Diagnostic<? extends JavaFileObject> diagnostic, List<Edit> edits) {
        Tree leaf = path.getLeaf();
        if (!(leaf instanceof BreakTree) && !(leaf instanceof ContinueTree)) return "not-a-jump-statement";
        if (!(path.getParentPath() != null && path.getParentPath().getLeaf() instanceof BlockTree
            || path.getParentPath() != null && path.getParentPath().getLeaf() instanceof CaseTree)) return "jump-not-in-block";
        int start = start(leaf), end = end(leaf);
        if (start < 0 || end <= start) return "unlocated";
        edits.add(new Edit(start, end, "", "drop-unreachable-jump", diagnostic));
        return null;
    }

    /**
     * {@code i != true} where {@code i} is an int-like local: the bytecode compared {@code i} with the int constant 1
     * ({@code iconst_1; if_icmpne}), which a decompiler printed as a boolean literal. Writing {@code 1} (or {@code 0} for
     * {@code false}) gives javac exactly that comparison, so the edit only restores the constant's int spelling.
     */
    private String booleanLiteralAsInt(TreePath path, Diagnostic<? extends JavaFileObject> diagnostic, List<Edit> edits) {
        TreePath at = path;
        for (int up = 0; at != null && up < 3 && !(at.getLeaf() instanceof BinaryTree); up++) at = at.getParentPath();
        if (at == null || !(at.getLeaf() instanceof BinaryTree binary)
            || binary.getKind() != Tree.Kind.EQUAL_TO && binary.getKind() != Tree.Kind.NOT_EQUAL_TO) return "not-an-equality";
        ExpressionTree literal, other;
        if (binary.getRightOperand() instanceof LiteralTree right && right.getKind() == Tree.Kind.BOOLEAN_LITERAL) {
            literal = right; other = binary.getLeftOperand();
        } else if (binary.getLeftOperand() instanceof LiteralTree left && left.getKind() == Tree.Kind.BOOLEAN_LITERAL) {
            literal = left; other = binary.getRightOperand();
        } else return "no-boolean-literal";
        TypeMirror type = trees.getTypeMirror(new TreePath(at, other));
        if (type == null || !EnumSet.of(TypeKind.INT, TypeKind.SHORT, TypeKind.BYTE, TypeKind.CHAR).contains(type.getKind()))
            return "operand-not-int";
        boolean value = Boolean.TRUE.equals(((LiteralTree) literal).getValue());
        edits.add(new Edit(start(literal), end(literal), value ? "1" : "0", "boolean-literal-as-int", diagnostic));
        return null;
    }

    /**
     * Phase 2BN: a generic method compared with {@code ==} or {@code !=} without the type witness the original had, e.g.
     * Guava's {@code lowerBound == Cut.<C>belowAll()} printed as {@code lowerBound == Cut.belowAll()}. An operand of
     * {@code ==} is not a poly expression, so javac infers the method's type variables from their bounds alone
     * ({@code Cut<Comparable>}), which is incomparable with {@code Cut<C>}. Each type variable of the method that is a type
     * argument of its result is given the matching type argument of the other operand, written as a witness
     * ({@code Cut.<C>belowAll()}). Type arguments are erased, so the call and the comparison compile to the same
     * instructions. Only a qualified call ({@code Type.m()} or {@code expr.m()}) can take a witness; every variable of
     * the method must be bound, and wildcard or captured arguments are refused.
     */
    private String typeWitness(TreePath path, Diagnostic<? extends JavaFileObject> diagnostic, List<Edit> edits) {
        TreePath at = path;
        for (int up = 0; at != null && up < 3 && !(at.getLeaf() instanceof BinaryTree); up++) at = at.getParentPath();
        if (at == null || !(at.getLeaf() instanceof BinaryTree binary)) return "not-an-equality";
        for (int side = 0; side < 2; side++) {
            ExpressionTree call = side == 0 ? binary.getRightOperand() : binary.getLeftOperand();
            ExpressionTree other = side == 0 ? binary.getLeftOperand() : binary.getRightOperand();
            while (call instanceof ParenthesizedTree parenthesized) call = parenthesized.getExpression();
            if (!(call instanceof MethodInvocationTree invocation) || !invocation.getTypeArguments().isEmpty()
                || !(invocation.getMethodSelect() instanceof MemberSelectTree select)) continue;
            var selected = TreePath.getPath(unit, select);
            if (selected == null || !(trees.getElement(selected) instanceof ExecutableElement method) || method.getTypeParameters().isEmpty()) continue;
            var otherPath = TreePath.getPath(unit, other);
            TypeMirror otherType = otherPath == null ? null : trees.getTypeMirror(otherPath);
            if (!(method.getReturnType() instanceof DeclaredType declared) || !(otherType instanceof DeclaredType actual)
                || !types.isSameType(types.erasure(declared), types.erasure(actual))
                || declared.getTypeArguments().size() != actual.getTypeArguments().size()) continue;
            var bound = new HashMap<Element,String>(); boolean consistent = true;
            for (int i = 0; i < declared.getTypeArguments().size() && consistent; i++) {
                if (!(declared.getTypeArguments().get(i) instanceof TypeVariable variable)
                    || !method.getTypeParameters().contains(variable.asElement())) continue;
                String written = witness(actual.getTypeArguments().get(i));
                String previous = written == null ? null : bound.putIfAbsent(variable.asElement(), written);
                consistent = written != null && (previous == null || previous.equals(written));
            }
            var witnesses = new StringJoiner(", ", "<", ">");
            for (var parameter : method.getTypeParameters()) {
                String written = bound.get(parameter);
                if (written == null) { consistent = false; break; }
                witnesses.add(written);
            }
            if (!consistent) continue;
            String name = select.getIdentifier().toString();
            int identifier = end(select) - name.length();
            if (identifier <= start(select) || !text.startsWith(name, identifier)) continue;
            edits.add(new Edit(identifier, identifier, witnesses.toString(), "type-witness", diagnostic));
            return null;
        }
        return "no-generic-call-to-witness";
    }
    /** Source text for a type usable as a witness: a type variable or a type without wildcards; null otherwise. */
    private static String witness(TypeMirror type) {
        String written = type.toString();
        boolean usable = (type.getKind() == TypeKind.TYPEVAR || type.getKind() == TypeKind.DECLARED || type.getKind() == TypeKind.ARRAY)
            && !written.contains("?") && !written.contains("capture");
        return usable ? written : null;
    }

    /**
     * A class initializer block that cannot complete normally, e.g. {@code static { throw new X(); }} as decompilers
     * print javac's {@code static { if (true) throw ...; }}. Its statements are wrapped in {@code if (true) { ... }}: an
     * if-then statement may always complete normally (JLS 14.22), the condition is constant, and javac emits no branch
     * for it, so behavior and the generated body are unchanged. Only a block that is a direct member of a class is edited.
     */
    private String guardInitializer(TreePath path, Diagnostic<? extends JavaFileObject> diagnostic, List<Edit> edits) {
        if (!(path.getLeaf() instanceof BlockTree block) || path.getParentPath() == null
            || !(path.getParentPath().getLeaf() instanceof ClassTree)) return "not-an-initializer";
        int start = start(block), end = end(block);
        if (start < 0 || end <= start || end > text.length() || text.charAt(end - 1) != '}') return "unlocated";
        int open = text.indexOf('{', start);
        if (open < 0 || open >= end - 1) return "unlocated";
        String inner = text.substring(open + 1, end - 1);
        edits.add(new Edit(open, end, "{ if (true) {" + inner + "} }", "guard-initializer", diagnostic));
        return null;
    }

    /** The oldest class file version javac 21 can still build: Java 8 (major version 52); {@code --release 7} is gone. */
    static final int LOWEST_RELEASE_MAJOR = 52;

    /**
     * Phase 2DE, {@code environment-stub}: a class lacks an abstract method that a JDK type gained after the release the
     * class was built for. Guava 17 targets Java 6, and its {@code Types.TypeVariableImpl implements TypeVariable<D>} has
     * no {@code getAnnotatedBounds()}, which Java 8 added; javac 21 builds release 8 or later only, so no source that
     * keeps the original declarations compiles here. That is a limit of this environment, not a decompiler error. This
     * is the one rule that edits a declaration: before the class's closing brace it adds a method that throws
     * {@link UnsupportedOperationException}, under a comment saying it is not in the original. Taken only when the
     * original class file proves the gap: it is older than Java 8, neither abstract nor an interface, and declares no
     * method of that name and arity. Each missing method must be declared by a {@code java.} type, have no type
     * parameters, and have no type variable in its signature as a member of the class.
     */
    private String environmentStub(Diagnostic<? extends JavaFileObject> diagnostic, List<Edit> edits) {
        if (originals == null) return "no-original-classes";
        TreePath path = locateClass(diagnostic.getPosition());
        if (path == null || !(trees.getElement(path) instanceof TypeElement type) || !(type.asType() instanceof DeclaredType declared)) return "unlocated";
        var tree = (ClassTree) path.getLeaf();
        byte[] original = originals.get(elements.getBinaryName(type).toString().replace('.', '/'));
        if (original == null || original.length < 8) return "no-original-class";
        int major = (original[6] & 0xff) << 8 | original[7] & 0xff;
        if (major >= LOWEST_RELEASE_MAJOR) return "target-release-buildable";
        if (OriginalClass.isAbstract(original)) return "original-abstract";
        var declaredInOriginal = OriginalClass.methods(original);
        var members = ElementFilter.methodsIn(elements.getAllMembers(type));
        var missing = new ArrayList<ExecutableElement>();
        for (var method : members) {
            if (!method.getModifiers().contains(Modifier.ABSTRACT)) continue;
            if (members.stream().noneMatch(other -> other != method && !other.getModifiers().contains(Modifier.ABSTRACT)
                && other.getSimpleName().equals(method.getSimpleName()) && elements.overrides(other, method, type))) missing.add(method);
        }
        if (missing.isEmpty()) return "no-missing-method";
        int close = end(tree) - 1;
        if (close < 0 || text.charAt(close) != '}') return "unlocated";
        int closeLine = text.lastIndexOf('\n', close - 1) + 1;
        String closeIndent = text.substring(closeLine, close), newline = closeLine >= 2 && text.charAt(closeLine - 2) == '\r' ? "\r\n" : "\n";
        boolean ownLine = closeIndent.isBlank();
        String indent = memberIndent(tree, ownLine ? closeIndent : ""), step = indent.startsWith(closeIndent) && indent.length() > closeIndent.length()
            ? indent.substring(closeIndent.length()) : "    ";
        var stubs = new StringBuilder(ownLine ? "" : newline);
        for (var method : missing) {
            if (!(method.getEnclosingElement() instanceof TypeElement owner) || !owner.getQualifiedName().toString().startsWith("java.")) return "not-a-jdk-method";
            String name = method.getSimpleName().toString();
            if (declaredInOriginal.contains(name + "/" + method.getParameters().size())) return "original-declares-method";
            if (!(types.asMemberOf(declared, method) instanceof ExecutableType member)) return "unlocated";
            // AnnotatedElement's `<T extends Annotation> T getAnnotation(Class<T>)` keeps its own type variable; the class's
            // type variables are in scope in its body. Any other type variable cannot be spelled there.
            var scope = new HashSet<Element>(); scope.add(type); scope.add(method);
            for (var variable : member.getTypeVariables()) scope.add(variable.asElement());
            var bounds = new StringJoiner(", ", "<", "> ").setEmptyValue("");
            for (var variable : member.getTypeVariables()) {
                TypeMirror bound = variable.getUpperBound();
                String spelled = types.isSameType(bound, elements.getTypeElement("java.lang.Object").asType()) ? "" : spell(bound, scope);
                if (spelled == null) return "unspellable-signature";
                bounds.add(variable.asElement().getSimpleName() + (spelled.isEmpty() ? "" : " extends " + spelled));
            }
            var signature = new ArrayList<String>();
            for (TypeMirror part : concat(member.getReturnType(), member.getParameterTypes())) {
                String spelled = spell(part, scope);
                if (spelled == null) return "unspellable-signature";
                signature.add(spelled);
            }
            String access = method.getModifiers().contains(Modifier.PUBLIC) ? "public " : method.getModifiers().contains(Modifier.PROTECTED) ? "protected " : "";
            var parameters = new StringJoiner(", ");
            for (int i = 1; i < signature.size(); i++) parameters.add(signature.get(i) + " p" + (i - 1));
            stubs.append(indent).append("// ClassRevive environment stub, not in the original class (class file version ").append(major)
                .append(", older than javac's lowest release): the JDK now requires ").append(owner.getQualifiedName()).append('.').append(name).append('.').append(newline)
                .append(indent).append(access).append(bounds).append(signature.get(0)).append(' ').append(name).append('(').append(parameters).append(") {").append(newline)
                .append(indent).append(step).append("throw new UnsupportedOperationException(\"").append(name).append(": not in the original class\");").append(newline)
                .append(indent).append('}').append(newline);
        }
        edits.add(new Edit(ownLine ? closeLine : close, ownLine ? closeLine : close, stubs + (ownLine ? "" : closeIndent), "environment-stub", diagnostic));
        return null;
    }

    /** Source text for a type whose type variables are all in {@code scope}, or null. */
    private String spell(TypeMirror type, Set<Element> scope) {
        switch (type.getKind()) {
            case VOID: return "void";
            case ARRAY: { String component = spell(((ArrayType) type).getComponentType(), scope); return component == null ? null : component + "[]"; }
            case TYPEVAR: {
                Element variable = ((TypeVariable) type).asElement();
                return scope.contains(variable) || variable instanceof TypeParameterElement parameter && scope.contains(parameter.getGenericElement())
                    ? variable.getSimpleName().toString() : null;
            }
            case WILDCARD: {
                var wildcard = (WildcardType) type;
                TypeMirror bound = wildcard.getExtendsBound() != null ? wildcard.getExtendsBound() : wildcard.getSuperBound();
                if (bound == null) return "?";
                String spelled = spell(bound, scope);
                return spelled == null ? null : (wildcard.getExtendsBound() != null ? "? extends " : "? super ") + spelled;
            }
            case DECLARED: {
                String name = qualifiedName(type);
                if (name == null) return null;
                var arguments = new StringJoiner(", ", "<", ">").setEmptyValue("");
                for (TypeMirror argument : ((DeclaredType) type).getTypeArguments()) {
                    String spelled = spell(argument, scope);
                    if (spelled == null) return null;
                    arguments.add(spelled);
                }
                return name + arguments;
            }
            default: return type.getKind().isPrimitive() ? type.toString() : null;
        }
    }

    /**
     * Reads an original class file with ASM. A class of its own: CompilerWorker runs this class's {@link #escape} without
     * ASM on its classpath, and an ASM visitor here would make the JVM's verifier load ASM.
     */
    private static final class OriginalClass {
        static boolean isAbstract(byte[] bytes) {
            return (new org.objectweb.asm.ClassReader(bytes).getAccess() & (org.objectweb.asm.Opcodes.ACC_ABSTRACT | org.objectweb.asm.Opcodes.ACC_INTERFACE)) != 0;
        }

        /** Each declared method as {@code name/arity}. */
        static Set<String> methods(byte[] bytes) {
            var methods = new HashSet<String>();
            new org.objectweb.asm.ClassReader(bytes).accept(new org.objectweb.asm.ClassVisitor(org.objectweb.asm.Opcodes.ASM9) {
                @Override public org.objectweb.asm.MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                    methods.add(name + "/" + org.objectweb.asm.Type.getArgumentTypes(descriptor).length);
                    return null;
                }
            }, org.objectweb.asm.ClassReader.SKIP_CODE);
            return methods;
        }
    }

    private static List<TypeMirror> concat(TypeMirror first, List<? extends TypeMirror> rest) {
        var all = new ArrayList<TypeMirror>(); all.add(first); all.addAll(rest); return all;
    }

    /** The indentation of the class's first member that starts its own line, or one step more than {@code closeIndent}. */
    private String memberIndent(ClassTree tree, String closeIndent) {
        for (Tree member : tree.getMembers()) {
            int at = start(member);
            if (at < 0 || at <= start(tree)) continue;
            int line = text.lastIndexOf('\n', at - 1) + 1;
            if (text.substring(line, at).isBlank() && !text.substring(line, at).isEmpty()) return text.substring(line, at);
        }
        return closeIndent + "    ";
    }

    /** The innermost class whose header, not one of its members, holds {@code position}. */
    private TreePath locateClass(long position) {
        if (position < 0) return null;
        TreePath[] found = {null};
        new TreePathScanner<Void,Void>() {
            @Override public Void visitClass(ClassTree tree, Void unused) {
                if (start(tree) <= position && position < end(tree)
                    && tree.getMembers().stream().noneMatch(member -> start(member) > start(tree) && start(member) <= position && position < end(member)))
                    found[0] = getCurrentPath();
                return super.visitClass(tree, null);
            }
        }.scan(unit, null);
        return found[0];
    }

    /** {@code expr} whose type cannot be assigned to its target: an upcast to the target's raw type, e.g. {@code (TypeAdapter)expr}. */
    private String rawCastToTarget(TreePath path, Diagnostic<? extends JavaFileObject> diagnostic, List<Edit> edits) {
        // javac reports a failed diamond at the created type; the expression is the creation around it.
        if (path.getParentPath().getLeaf() instanceof NewClassTree created && created.getIdentifier() == path.getLeaf()) path = path.getParentPath();
        if (!(path.getLeaf() instanceof ExpressionTree expression) || expression.getKind() == Tree.Kind.NULL_LITERAL) return "not-an-expression";
        if (dropObjectCast(path, expression, diagnostic, edits)) return null;
        if (rawCastType(path, diagnostic, edits)) return null;
        TypeMirror target = targetType(path);
        if (target == null) target = argumentTypeVariable(path);
        if (target instanceof TypeVariable variable) return typeVariableCast(path, expression, variable, diagnostic, edits);
        if (target != null && (!(target instanceof DeclaredType declared) || declared.getTypeArguments().isEmpty())) return "target-not-parameterized";
        TypeMirror found = expression instanceof NewClassTree created ? classType(path, created.getIdentifier()) : trees.getTypeMirror(path);
        // A branch of a conditional that failed attribution may carry an error type; a variable still has its declared one.
        if ((found == null || found.getKind() != TypeKind.DECLARED) && trees.getElement(path) instanceof VariableElement variable) found = variable.asType();
        // A call whose generic result javac could not fit the target (Guava's `return multimap.asMap();` where the original
        // had a type-argument-only cast) carries an error type; its declared return type still gives the erasure (Phase 2BC).
        if ((found == null || found.getKind() != TypeKind.DECLARED) && expression instanceof MethodInvocationTree
            && trees.getElement(path) instanceof ExecutableElement method && method.getReturnType().getKind() == TypeKind.DECLARED)
            found = method.getReturnType();
        if (found == null || found.getKind() != TypeKind.DECLARED) return "source-type-unknown";
        if (target == null) {
            // Compact diagnostics put "no suitable method" on the mismatching argument: cast it to its own raw type.
            Tree parent = path.getParentPath().getLeaf();
            boolean argument = parent instanceof MethodInvocationTree call && call.getArguments().contains(expression)
                || parent instanceof NewClassTree creation && creation.getArguments().contains(expression);
            if (!argument) return "no-target-type";
            if (((DeclaredType) found).getTypeArguments().isEmpty()) return "argument-not-parameterized";
            return rawCast(expression, types.erasure(found), "raw-argument", diagnostic, edits);
        }
        TypeMirror raw = types.erasure(target);
        // Only upcasts: the cast cannot fail at run time and javac emits no checkcast for it.
        if (!types.isSubtype(types.erasure(found), raw)) return "not-an-upcast";
        return rawCast(expression, raw, "raw-cast-to-target", diagnostic, edits);
    }

    /**
     * Phase 2BN: a value passed or assigned where a type variable is expected, e.g. Guava's
     * {@code ConstrainedMultimap.this.get((K) key)} for an {@code Object key}, printed without the unchecked cast. The cast
     * {@code (K) expr} is added only when the variable is declared by a class or method enclosing the expression, so the
     * name is in scope, and the expression's erasure is a subtype of the variable's erasure: then javac emits no
     * {@code checkcast} and the instructions are unchanged.
     */
    private String typeVariableCast(TreePath path, ExpressionTree expression, TypeVariable variable,
                                    Diagnostic<? extends JavaFileObject> diagnostic, List<Edit> edits) {
        TypeMirror found = trees.getTypeMirror(path);
        if (found == null || found.getKind() != TypeKind.DECLARED && found.getKind() != TypeKind.TYPEVAR) return "source-type-unknown";
        if (!types.isSubtype(types.erasure(found), types.erasure(variable))) return "not-an-upcast";
        if (!(variable.asElement() instanceof TypeParameterElement parameter)) return "not-a-declared-type-variable";
        boolean inScope = false;
        for (TreePath at = path; at != null && !inScope; at = at.getParentPath())
            if (at.getLeaf() instanceof ClassTree || at.getLeaf() instanceof MethodTree) inScope = parameter.getGenericElement().equals(trees.getElement(at));
        if (!inScope) return "type-variable-not-in-scope";
        String source = source(expression);
        edits.add(new Edit(start(expression), end(expression), "(" + parameter.getSimpleName() + ")" + (primary(expression) ? source : "(" + source + ")"),
            "type-variable-cast", diagnostic));
        return null;
    }

    /**
     * {@code (Object) expr} where {@code expr} has a reference type, e.g. CFR's {@code entries.add((Object)new Entry(e))}
     * for a {@code List<Entry>}. A cast up to {@code Object} emits no {@code checkcast}, so dropping it restores the
     * original call. Primitive operands are refused: {@code (Object) 5} boxes, and dropping it would change the call.
     */
    private boolean dropObjectCast(TreePath path, ExpressionTree expression, Diagnostic<? extends JavaFileObject> diagnostic, List<Edit> edits) {
        ExpressionTree inner = expression;
        while (inner instanceof ParenthesizedTree parenthesized) inner = parenthesized.getExpression();
        if (!(inner instanceof TypeCastTree cast)) return false;
        TypeMirror castType = trees.getTypeMirror(new TreePath(path, cast.getType()));
        if (castType == null || castType.getKind() != TypeKind.DECLARED
            || !((TypeElement) ((DeclaredType) castType).asElement()).getQualifiedName().contentEquals("java.lang.Object")) return false;
        TypeMirror operand = trees.getTypeMirror(new TreePath(new TreePath(path, cast), cast.getExpression()));
        if (operand == null || !EnumSet.of(TypeKind.DECLARED, TypeKind.ARRAY, TypeKind.TYPEVAR).contains(operand.getKind())) return false;
        String source = source(cast.getExpression());
        edits.add(new Edit(start(expression), end(expression), primary(cast.getExpression()) ? source : "(" + source + ")", "drop-object-cast", diagnostic));
        return true;
    }

    /**
     * A cast to a parameterized type that javac finds inconvertible, e.g. Guava's
     * {@code (ImmutableMap<K, Collection<V>>)this.map} for an {@code ImmutableMap<K, ? extends ImmutableCollection<V>>}
     * field. Its type arguments are dropped ({@code (ImmutableMap)this.map}); a checkcast always uses the erased type,
     * so the generated instruction is unchanged (Phase 2BC). The diagnostic may sit on the cast or on its operand.
     */
    private boolean rawCastType(TreePath path, Diagnostic<? extends JavaFileObject> diagnostic, List<Edit> edits) {
        TreePath at = path.getLeaf() instanceof TypeCastTree ? path
            : path.getParentPath() != null && path.getParentPath().getLeaf() instanceof TypeCastTree ? path.getParentPath() : null;
        if (at == null || !(((TypeCastTree) at.getLeaf()).getType() instanceof ParameterizedTypeTree generic)) return false;
        int start = start(generic), end = end(generic);
        if (start < 0 || end <= start) return false;
        edits.add(new Edit(start, end, source(generic.getType()), "raw-cast-type", diagnostic));
        return true;
    }

    private String rawCast(ExpressionTree expression, TypeMirror raw, String rule, Diagnostic<? extends JavaFileObject> diagnostic, List<Edit> edits) {
        String name = qualifiedName(raw);
        if (name == null) return "type-not-nameable";
        String source = source(expression);
        edits.add(new Edit(start(expression), end(expression), "(" + name + ")" + (primary(expression) ? source : "(" + source + ")"), rule, diagnostic));
        return null;
    }

    /** {@code new X<>(...)} whose arguments cannot be inferred: the raw {@code new X(...)}. */
    private String rawDiamond(TreePath path, Diagnostic<? extends JavaFileObject> diagnostic, List<Edit> edits) {
        NewClassTree created = null;
        for (TreePath at = path; at != null && created == null && at.getLeaf() instanceof ExpressionTree; at = at.getParentPath())
            if (at.getLeaf() instanceof NewClassTree value) created = value;
        if (created == null || !(created.getIdentifier() instanceof ParameterizedTypeTree generic) || !generic.getTypeArguments().isEmpty())
            return "not-a-diamond";
        String base = source(generic.getType());
        if (!DIAMOND.matcher(text.substring(end(generic.getType()), end(generic))).matches()) return "not-a-diamond";
        edits.add(new Edit(start(generic), end(generic), base, "raw-diamond", diagnostic));
        return null;
    }

    /** A class type variable used in a static context inside a cast or creation: the raw type, e.g. {@code (TypeAdapter)x}. */
    private String rawTypeArgument(TreePath path, Diagnostic<? extends JavaFileObject> diagnostic, List<Edit> edits) {
        if (!(path.getLeaf() instanceof IdentifierTree)) return "not-a-type-variable";
        TreePath outer = null;
        for (TreePath at = path.getParentPath(); at != null && at.getLeaf() instanceof ParameterizedTypeTree; at = at.getParentPath()) outer = at;
        if (outer == null) return "not-a-type-argument";
        Tree parent = outer.getParentPath().getLeaf();
        boolean cast = parent instanceof TypeCastTree value && value.getType() == outer.getLeaf();
        boolean creation = parent instanceof NewClassTree value && value.getIdentifier() == outer.getLeaf();
        if (!cast && !creation) return "not-in-cast-or-creation";
        var generic = (ParameterizedTypeTree) outer.getLeaf();
        edits.add(new Edit(start(generic), end(generic), source(generic.getType()), "raw-type-argument", diagnostic));
        return null;
    }

    /** A call no overload accepts because of generic arguments: raw casts on the parameterized arguments only. */
    private String rawArguments(TreePath path, Diagnostic<? extends JavaFileObject> diagnostic, List<Edit> edits) {
        TreePath call = path;
        for (int i = 0; i < 3 && call != null && !(call.getLeaf() instanceof MethodInvocationTree) && !(call.getLeaf() instanceof NewClassTree); i++)
            call = call.getParentPath();
        if (call == null) return "not-a-call";
        List<? extends ExpressionTree> arguments = call.getLeaf() instanceof MethodInvocationTree invocation ? invocation.getArguments()
            : call.getLeaf() instanceof NewClassTree created ? created.getArguments() : null;
        if (arguments == null) return "not-a-call";
        int before = edits.size();
        for (var argument : arguments) {
            var argumentPath = new TreePath(call, argument);
            if (argument instanceof TypeCastTree cast && cast.getType() instanceof ParameterizedTypeTree generic) {
                // An existing generic cast keeps its erasure (the same checkcast); only its type arguments go.
                edits.add(new Edit(start(generic), end(generic), source(generic.getType()), "raw-argument", diagnostic));
                continue;
            }
            TypeMirror type = trees.getTypeMirror(argumentPath);
            if (!(type instanceof DeclaredType declared) || declared.getTypeArguments().isEmpty()) continue;
            rawCast(argument, types.erasure(type), "raw-argument", diagnostic, edits);
        }
        return edits.size() > before ? null : "no-parameterized-argument";
    }

    /** A final local assigned more than once: drop {@code final}; captured uses get a final copy (see {@link #captureCopies}). */
    private String dropFinalLocal(TreePath path, Diagnostic<? extends JavaFileObject> diagnostic, List<Edit> edits) {
        Element element = trees.getElement(path);
        if (element == null || element.getKind() != ElementKind.LOCAL_VARIABLE) return "not-a-local-variable";
        if (repairedFinals.contains(element)) return null; // already repaired for an earlier diagnostic in this proposal
        // A constant initializer does not refuse the edit (Phase 2BB): javac reports this diagnostic because the local is
        // assigned again, so the original program never treated it as a constant (e.g. Vineflower's
        // `for (final int attempt = 0; attempt < 2; attempt++)`). If a constant use such as a case label needed it, the
        // file would fail to compile and the edit would be reverted.
        if (!element.getModifiers().contains(Modifier.FINAL)) return "not-final";
        TreePath declaration = declaration(path, element);
        if (declaration == null) return "declaration-not-found";
        var variable = (VariableTree) declaration.getLeaf();
        int[] keyword = finalKeyword(variable);
        if (keyword == null) return "final-keyword-not-found";
        var found = new ArrayList<Edit>();
        found.add(new Edit(keyword[0], keyword[1], "", "drop-final-local", diagnostic));
        String reason = captureCopies(declaration, element, diagnostic, found);
        if (reason != null) return reason;
        repairedFinals.add(element);
        edits.addAll(found);
        return null;
    }

    /** A non-final local captured by an inner class or lambda: capture a final copy made just before that statement. */
    private String captureCopy(TreePath path, Diagnostic<? extends JavaFileObject> diagnostic, List<Edit> edits) {
        Element element = trees.getElement(path);
        if (element == null || element.getKind() != ElementKind.LOCAL_VARIABLE) return "not-a-local-variable";
        if (repairedFinals.contains(element)) return null; // already repaired for an earlier diagnostic in this proposal
        TreePath declaration = declaration(path, element);
        if (declaration == null) return "declaration-not-found";
        var found = new ArrayList<Edit>();
        String reason = captureCopies(declaration, element, diagnostic, found);
        if (reason != null) return reason;
        if (found.isEmpty()) return "no-capture-found";
        repairedFinals.add(element);
        edits.addAll(found);
        return null;
    }

    /**
     * For each inner class or lambda in the variable's scope that reads it, inserts {@code final T nameCaptured = name;}
     * before the enclosing block statement and renames the captured reads. Refused when that statement also assigns
     * the variable, because the copy would then see an older value.
     */
    private String captureCopies(TreePath declaration, Element element, Diagnostic<? extends JavaFileObject> diagnostic, List<Edit> edits) {
        var variable = (VariableTree) declaration.getLeaf();
        Tree scope = declaration.getParentPath().getLeaf();
        var uses = new LinkedHashMap<Tree,List<TreePath>>(); var roots = new HashMap<Tree,TreePath>();
        new TreePathScanner<Void,Void>() {
            final Deque<TreePath> captures = new ArrayDeque<>();
            @Override public Void visitClass(ClassTree tree, Void unused) {
                captures.push(getCurrentPath()); try { return super.visitClass(tree, null); } finally { captures.pop(); }
            }
            @Override public Void visitLambdaExpression(LambdaExpressionTree tree, Void unused) {
                captures.push(getCurrentPath()); try { return super.visitLambdaExpression(tree, null); } finally { captures.pop(); }
            }
            @Override public Void visitIdentifier(IdentifierTree tree, Void unused) {
                if (!captures.isEmpty() && element.equals(trees.getElement(getCurrentPath()))) {
                    TreePath root = captures.getLast();
                    roots.put(root.getLeaf(), root);
                    uses.computeIfAbsent(root.getLeaf(), ignored -> new ArrayList<>()).add(getCurrentPath());
                }
                return null;
            }
        }.scan(new TreePath(declaration.getParentPath(), scope), null);
        if (uses.isEmpty()) return null;
        String type = positions.getStartPosition(unit, variable.getType()) < 0 ? null : source(variable.getType());
        if (type == null) return "local-type-not-written";
        String name = variable.getName().toString();
        var statements = new HashMap<Tree,TreePath>();
        for (var root : uses.keySet()) {
            TreePath statement = roots.get(root);
            while (statement.getParentPath() != null && !(statement.getParentPath().getLeaf() instanceof BlockTree)) statement = statement.getParentPath();
            if (statement.getParentPath() == null) return "capture-outside-block";
            if (assigns(statement, element)) return "capture-statement-assigns-variable";
            statements.put(root, statement);
        }
        // One copy per capturing statement, each with its own name: two statements in one block cannot both declare
        // `nameCaptured` (CFR 0.152's LiteralRewriter initializer captured one local from four statements).
        var copies = new IdentityHashMap<Tree,String>();
        for (var capture : uses.entrySet()) {
            TreePath statement = statements.get(capture.getKey());
            String copy = copies.get(statement.getLeaf());
            if (copy == null) {
                copy = unusedName(name + "Captured");
                copies.put(statement.getLeaf(), copy);
                int at = start(statement.getLeaf());
                int lineStart = text.lastIndexOf('\n', at - 1) + 1;
                String indent = text.substring(lineStart, at).isBlank() ? text.substring(lineStart, at) : "";
                edits.add(new Edit(at, at, "final " + type + " " + copy + " = " + name + ";\n" + indent, "capture-copy", diagnostic));
            }
            for (var use : capture.getValue()) edits.add(new Edit(start(use.getLeaf()), end(use.getLeaf()), copy, "capture-copy", diagnostic));
        }
        return null;
    }

    /**
     * An unresolved name {@code n} next to exactly one visible local named {@code n} plus trailing {@code x}s: decompilers
     * (Vineflower among them) rename a clashing local that way and can miss a use inside an inner class.
     */
    private String renamedLocal(TreePath path, Diagnostic<? extends JavaFileObject> diagnostic, List<Edit> edits) {
        if (!(path.getLeaf() instanceof IdentifierTree identifier)) return "not-an-identifier";
        if (path.getParentPath().getLeaf() instanceof MethodInvocationTree call && call.getMethodSelect() == identifier) return "method-name";
        String name = identifier.getName().toString();
        var matches = new TreeSet<String>();
        for (Scope scope = trees.getScope(path); scope != null; scope = scope.getEnclosingScope()) {
            for (Element local : scope.getLocalElements()) {
                String candidate = local.getSimpleName().toString();
                boolean variable = switch (local.getKind()) {
                    case LOCAL_VARIABLE, PARAMETER, EXCEPTION_PARAMETER, RESOURCE_VARIABLE -> true;
                    default -> false;
                };
                if (variable && candidate.startsWith(name) && candidate.length() > name.length()
                    && RENAMED_LOCAL_SUFFIX.matcher(candidate.substring(name.length())).matches()) matches.add(candidate);
            }
            if (scope.getEnclosingClass() == null && scope.getEnclosingMethod() == null) break;
        }
        if (matches.size() != 1) return matches.isEmpty() ? "no-renamed-local" : "ambiguous-renamed-local";
        edits.add(new Edit(start(identifier), end(identifier), matches.first(), "renamed-local", diagnostic));
        return null;
    }

    /**
     * Phase 2DE, {@code hoist-local-class}: an unresolved name {@code N} used before the declaration of a local class
     * {@code N} later in the same body. Guava 17's {@code Types.ClassOwnership.detectJvmBehavior} declares
     * {@code class LocalClass<T> {}} first and then creates {@code new LocalClass<String>() {}}; Vineflower prints the
     * declaration inside the loop that names {@code LocalClass.class}, after the creation. A local class declaration
     * emits no instruction in its method, so it moves to just before the statement of the innermost block holding both
     * that contains the first use. Moving it earlier only takes locals out of its scope, so it is refused when the class
     * reads a local of the method or names another local class; and when the member declares another class {@code N},
     * since javac numbers local classes of one name in declaration order.
     */
    private String hoistLocalClass(TreePath path, Diagnostic<? extends JavaFileObject> diagnostic, List<Edit> edits) {
        if (!(path.getLeaf() instanceof IdentifierTree identifier)) return "not-an-identifier";
        String name = identifier.getName().toString();
        TreePath member = path;
        while (member.getParentPath() != null && !(member.getParentPath().getLeaf() instanceof ClassTree)) member = member.getParentPath();
        if (member.getParentPath() == null) return "no-later-local-class";
        var declarations = new ArrayList<TreePath>(); var uses = new ArrayList<TreePath>();
        new TreePathScanner<Void,Void>() {
            @Override public Void visitClass(ClassTree tree, Void unused) {
                if (tree.getSimpleName().contentEquals(name)) declarations.add(getCurrentPath());
                return super.visitClass(tree, null);
            }
            @Override public Void visitIdentifier(IdentifierTree tree, Void unused) {
                if (tree.getName().contentEquals(name)) uses.add(getCurrentPath());
                return null;
            }
        }.scan(member, null);
        if (declarations.isEmpty()) return "no-later-local-class";
        if (declarations.size() > 1) return "ambiguous-local-class";
        TreePath declaration = declarations.get(0);
        var local = (ClassTree) declaration.getLeaf();
        if (!(declaration.getParentPath().getLeaf() instanceof BlockTree) || start(local) <= start(identifier)) return "no-later-local-class";
        TreePath first = null;
        for (var use : uses) if (!within(use, local) && (first == null || start(use.getLeaf()) < start(first.getLeaf()))) first = use;
        // The innermost block holding the first use and the declaration, and its statement that holds the use.
        var around = new HashSet<Tree>();
        for (TreePath at = declaration.getParentPath(); at != null; at = at.getParentPath()) around.add(at.getLeaf());
        TreePath statement = first;
        while (statement.getParentPath() != null && !(statement.getParentPath().getLeaf() instanceof BlockTree block && around.contains(block)))
            statement = statement.getParentPath();
        if (statement.getParentPath() == null) return "no-common-block";
        if (within(declaration, statement.getLeaf())) return "declared-in-using-statement";
        String unusable = capturedLocal(declaration);
        if (unusable != null) return unusable;
        int from = start(local), to = end(local), at = start(statement.getLeaf());
        int lineStart = text.lastIndexOf('\n', from - 1) + 1, targetLine = text.lastIndexOf('\n', at - 1) + 1;
        String indent = text.substring(lineStart, from), targetIndent = text.substring(targetLine, at);
        if (!indent.isBlank() || !targetIndent.isBlank()) return "not-on-its-own-line";
        String newline = text.startsWith("\r\n", to) ? "\r\n" : "\n";
        int lineEnd = text.startsWith(newline, to) ? to + newline.length() : to;
        String moved = text.substring(from, to).replace("\n" + indent, "\n" + targetIndent);
        edits.add(new Edit(at, at, moved + newline + targetIndent, "hoist-local-class", diagnostic));
        edits.add(new Edit(lineStart, lineEnd, "", "hoist-local-class", diagnostic));
        return null;
    }

    /** Why a local class cannot leave its place: it reads a local declared outside it, or names another local class. */
    private String capturedLocal(TreePath declaration) {
        Tree local = declaration.getLeaf();
        String[] reason = {null};
        new TreePathScanner<Void,Void>() {
            @Override public Void visitIdentifier(IdentifierTree tree, Void unused) {
                Element element = trees.getElement(getCurrentPath());
                if (element == null || reason[0] != null) return null;
                Tree declared = trees.getTree(element);
                boolean inside = declared != null && within(trees.getPath(element), local);
                switch (element.getKind()) {
                    case LOCAL_VARIABLE, PARAMETER, EXCEPTION_PARAMETER, RESOURCE_VARIABLE, BINDING_VARIABLE -> {
                        if (!inside) reason[0] = "captures-local";
                    }
                    case CLASS, INTERFACE, ENUM, RECORD -> {
                        if (element instanceof TypeElement type && type.getNestingKind() == NestingKind.LOCAL && !inside) reason[0] = "uses-other-local-class";
                    }
                    default -> { }
                }
                return null;
            }
        }.scan(declaration, null);
        return reason[0];
    }

    /** The path passes through {@code tree}. */
    private static boolean within(TreePath path, Tree tree) {
        for (TreePath at = path; at != null; at = at.getParentPath()) if (at.getLeaf() == tree) return true;
        return false;
    }

    private TypeMirror targetType(TreePath path) {
        TreePath at = path;
        while (true) {
            Tree parent = at.getParentPath().getLeaf();
            if (parent instanceof ParenthesizedTree) { at = at.getParentPath(); continue; }
            if (parent instanceof ConditionalExpressionTree conditional && conditional.getCondition() != at.getLeaf()) { at = at.getParentPath(); continue; }
            break;
        }
        Tree parent = at.getParentPath().getLeaf();
        if (parent instanceof ReturnTree) {
            for (TreePath owner = at.getParentPath(); owner != null; owner = owner.getParentPath()) {
                if (owner.getLeaf() instanceof LambdaExpressionTree || owner.getLeaf() instanceof ClassTree) return null;
                if (owner.getLeaf() instanceof MethodTree)
                    return trees.getElement(owner) instanceof ExecutableElement method ? method.getReturnType() : null;
            }
            return null;
        }
        if (parent instanceof VariableTree variable && variable.getInitializer() == at.getLeaf()) {
            Element element = trees.getElement(at.getParentPath());
            return element == null ? null : element.asType();
        }
        if (parent instanceof AssignmentTree assignment && assignment.getExpression() == at.getLeaf())
            return trees.getTypeMirror(new TreePath(at.getParentPath(), assignment.getVariable()));
        return null;
    }

    /**
     * The parameter type an argument of a qualified call is passed to, as a member of the receiver's type, when it is a
     * type variable (e.g. {@code K} for {@code ConstrainedMultimap.this.get(key)}); null otherwise, or for variable arity.
     */
    private TypeMirror argumentTypeVariable(TreePath path) {
        TreePath callPath = path.getParentPath();
        if (!(callPath.getLeaf() instanceof MethodInvocationTree call) || !(call.getMethodSelect() instanceof MemberSelectTree select)) return null;
        int index = call.getArguments().indexOf(path.getLeaf());
        if (index < 0 || !(trees.getTypeMirror(new TreePath(callPath, select.getExpression())) instanceof DeclaredType receiver)) return null;
        // A call whose only candidate did not apply may carry no method symbol: the receiver's one method of that name and arity.
        ExecutableElement method = trees.getElement(new TreePath(callPath, select)) instanceof ExecutableElement selected ? selected : null;
        if (method == null) {
            for (var member : ElementFilter.methodsIn(elements.getAllMembers((TypeElement) receiver.asElement())))
                if (member.getSimpleName().contentEquals(select.getIdentifier()) && member.getParameters().size() == call.getArguments().size()) {
                    if (method != null) return null;
                    method = member;
                }
        }
        if (method == null || method.isVarArgs() || index >= method.getParameters().size()) return null;
        TypeMirror parameter;
        try { parameter = ((ExecutableType) types.asMemberOf(receiver, method)).getParameterTypes().get(index); }
        catch (IllegalArgumentException notAMember) { return null; }
        return parameter.getKind() == TypeKind.TYPEVAR ? parameter : null;
    }

    private TypeMirror classType(TreePath creation, ExpressionTree identifier) {
        var at = new TreePath(creation, identifier);
        Element element = trees.getElement(identifier instanceof ParameterizedTypeTree generic ? new TreePath(at, generic.getType()) : at);
        return element instanceof TypeElement typeElement ? typeElement.asType() : null;
    }

    private static String qualifiedName(TypeMirror raw) {
        if (!(raw instanceof DeclaredType declared) || !(declared.asElement() instanceof TypeElement element)) return null;
        String name = element.getQualifiedName().toString();
        return name.isEmpty() ? null : name;
    }

    private TreePath declaration(TreePath use, Element element) {
        for (TreePath at = use; at != null; at = at.getParentPath()) {
            if (!(at.getLeaf() instanceof MethodTree) && !(at.getLeaf() instanceof LambdaExpressionTree)
                && !(at.getLeaf() instanceof BlockTree block && at.getParentPath().getLeaf() instanceof ClassTree)) continue;
            TreePath[] found = {null};
            new TreePathScanner<Void,Void>() {
                @Override public Void visitVariable(VariableTree tree, Void unused) {
                    if (found[0] == null && element.equals(trees.getElement(getCurrentPath()))) found[0] = getCurrentPath();
                    return super.visitVariable(tree, null);
                }
            }.scan(at, null);
            if (found[0] != null) return found[0];
        }
        return null;
    }

    /** Offsets of the {@code final} keyword (and the spaces after it) among the declaration's modifiers, skipping annotations. */
    private int[] finalKeyword(VariableTree variable) {
        var modifiers = variable.getModifiers();
        int from = start(modifiers), to = start(variable.getType());
        if (from < 0 || to < 0 || to < from) return null;
        var masked = new StringBuilder(text.substring(from, to));
        for (var annotation : modifiers.getAnnotations())
            for (int i = start(annotation); i < end(annotation); i++) if (i >= from && i < to) masked.setCharAt(i - from, ' ');
        var matcher = Pattern.compile("\\bfinal\\b\\s*").matcher(masked);
        if (!matcher.find()) return null;
        int[] result = {from + matcher.start(), from + matcher.end()};
        return matcher.find() ? null : result;
    }

    private boolean assigns(TreePath statement, Element element) {
        boolean[] found = {false};
        new TreePathScanner<Void,Void>() {
            @Override public Void visitAssignment(AssignmentTree tree, Void unused) { check(tree.getVariable()); return super.visitAssignment(tree, null); }
            @Override public Void visitCompoundAssignment(CompoundAssignmentTree tree, Void unused) {
                check(tree.getVariable()); return super.visitCompoundAssignment(tree, null);
            }
            @Override public Void visitUnary(UnaryTree tree, Void unused) { check(tree.getExpression()); return super.visitUnary(tree, null); }
            void check(Tree target) {
                if (target instanceof IdentifierTree && element.equals(trees.getElement(new TreePath(getCurrentPath(), target)))) found[0] = true;
            }
        }.scan(statement, null);
        return found[0];
    }

    private String unusedName(String base) {
        var reserved = reservedNames.computeIfAbsent(currentFile, ignored -> new HashSet<>());
        String name = base;
        for (int i = 2; reserved.contains(name) || Pattern.compile("\\b" + Pattern.quote(name) + "\\b").matcher(text).find(); i++) name = base + i;
        reserved.add(name);
        return name;
    }

    /** The deepest tree spanning exactly the diagnostic's start and end offsets. */
    private TreePath locate(long start, long end) {
        // javac reports "invalid method reference" without an end position: only a functional expression starting
        // exactly there is taken (the outermost one, so `Props::register` rather than its qualifier `Props`).
        if (start >= 0 && end < 0) return locateFunctional(start);
        if (start < 0 || end < start) return null;
        TreePath[] found = {null};
        new TreePathScanner<Void,Void>() {
            @Override public Void scan(Tree tree, Void unused) {
                if (tree == null) return null;
                long from = positions.getStartPosition(unit, tree), to = positions.getEndPosition(unit, tree);
                if (from > end || (to >= 0 && to < start)) return null;
                if (from == start && to == end) found[0] = new TreePath(getCurrentPath(), tree);
                return super.scan(tree, null);
            }
        }.scan(unit, null);
        return found[0];
    }

    private TreePath locateFunctional(long start) {
        TreePath[] found = {null};
        new TreePathScanner<Void,Void>() {
            @Override public Void scan(Tree tree, Void unused) {
                if (tree == null || found[0] != null) return null;
                long from = positions.getStartPosition(unit, tree), to = positions.getEndPosition(unit, tree);
                if (from > start || (to >= 0 && to <= start)) return null;
                if (from == start && (tree instanceof MemberReferenceTree || tree instanceof LambdaExpressionTree)) {
                    found[0] = new TreePath(getCurrentPath(), tree);
                    return null;
                }
                return super.scan(tree, null);
            }
        }.scan(unit, null);
        return found[0];
    }

    /** True inside a method, lambda or initializer body, or a field initializer: never in a declaration's own types. */
    private static boolean insideBody(TreePath path) {
        Tree child = path.getLeaf();
        for (TreePath at = path.getParentPath(); at != null; at = at.getParentPath()) {
            Tree node = at.getLeaf();
            if (node instanceof BlockTree || node instanceof LambdaExpressionTree lambda && lambda.getBody() == child) return true;
            if (node instanceof VariableTree variable && variable.getInitializer() == child) return true;
            if (node instanceof ClassTree || node instanceof MethodTree || node instanceof CompilationUnitTree) return false;
            child = node;
        }
        return false;
    }

    private static boolean primary(ExpressionTree tree) {
        return switch (tree.getKind()) {
            case IDENTIFIER, MEMBER_SELECT, METHOD_INVOCATION, NEW_CLASS, PARENTHESIZED, ARRAY_ACCESS, TYPE_CAST, NEW_ARRAY -> true;
            default -> tree instanceof LiteralTree;
        };
    }

    private int start(Tree tree) { return (int) positions.getStartPosition(unit, tree); }
    private int end(Tree tree) { return (int) positions.getEndPosition(unit, tree); }
    private String source(Tree tree) { return text.substring(start(tree), end(tree)); }

    private String relative(JavaFileObject file) {
        var uri = file.toUri();
        if (!"file".equals(uri.getScheme())) return null;
        Path path = Path.of(uri).toAbsolutePath().normalize(), root = sources.toAbsolutePath().normalize();
        return path.startsWith(root) ? root.relativize(path).toString().replace('\\', '/') : null;
    }

    private static String row(String file, long line, long column, String code, String detail) {
        return escape(file) + '\t' + line + '\t' + column + '\t' + escape(code) + '\t' + escape(detail);
    }

    /** Keeps one record per line: backslash, tab, carriage return and newline are escaped. */
    static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\t", "\\t").replace("\r", "\\r").replace("\n", "\\n");
    }
}
