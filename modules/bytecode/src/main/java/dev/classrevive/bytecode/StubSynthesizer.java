package dev.classrevive.bytecode;

import java.io.IOException;
import java.net.URI;
import java.nio.file.*;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import org.objectweb.asm.*;
import org.objectweb.asm.signature.SignatureReader;
import org.objectweb.asm.signature.SignatureVisitor;

/**
 * Phase 2CT: stand-in classes for types the input uses but nobody supplied (not in the input, the dependencies or
 * the JDK), inferred from how the input's own bytecode uses them, so the decompiled sources can be compiled and
 * measured instead of failing on hundreds of {@code cannot find symbol} errors. The stubs exist only on the compiler's
 * class path: they are never loaded, run or exported as code, and every report says the result compiled against
 * inferred stubs, not the real library.
 *
 * <p>What is inferred, per missing type:
 * <ul>
 * <li>its kind: an annotation (used as one), an interface ({@code invokeinterface}, an interface method reference, or
 * implemented by an input class), a throwable (caught, or in a {@code throws} clause), or a class;</li>
 * <li>constructors, methods and fields with their exact descriptors and static-ness, from every instruction that names
 * the type as owner, and from instructions that name an input subclass for a member the subclass does not declare
 * (an inherited member, added to the nearest missing superclass);</li>
 * <li>for a missing superclass, the input subclasses' instance methods (possible overrides, unless they would clash);</li>
 * <li>the number of type parameters, from generic signatures that mention the type with arguments (local variable
 * signatures included); with one parameter, a method or field of type {@code Object} whose result the input casts at
 * once is declared with that parameter, as javac's cast shows;</li>
 * <li>checked JDK exceptions caught around a range whose only call is a stub method's;</li>
 * <li>annotation elements (with defaults) and enum constants, from annotation values and enum-shaped uses;</li>
 * <li>Phase 2DJ: supertypes between missing types (and JDK supertypes), from values the bytecode passes where another type
 * is declared ({@link StubTypeFlow}); the element type of raw collection members, from javac's casts after
 * {@code next()} and {@code get(int)}; {@code Iterable} for a stub iterated through its own {@code iterator()}; and the
 * {@code throws} clause of an input override, copied to the missing superclass's method.</li>
 * </ul>
 * Stub methods are concrete (interface methods are {@code default}) and their bodies only throw, so no subclass is
 * forced to implement anything. Supertypes without evidence are {@code Object} ({@code RuntimeException} for throwables).
 */
public final class StubSynthesizer {
    private StubSynthesizer() {}
    public static final int MAX_STUBS = 20_000;

    /**
     * {@code classes}: internal names of the stubs written; {@code packages}: package to stub count; {@code supertypes}:
     * superclass and interface links between stubs or to JDK types, inferred from the bytecode (Phase 2DJ), of which
     * {@code guessedOrder} only order two required supertypes that the evidence did not order.
     */
    public record Result(SortedSet<String> classes, SortedMap<String,Integer> packages, int methods, int fields, boolean truncated,
                         int supertypes, int guessedOrder) {
        public Result(SortedSet<String> classes, SortedMap<String,Integer> packages, int methods, int fields, boolean truncated) {
            this(classes, packages, methods, fields, truncated, 0, 0);
        }
        public boolean isEmpty() { return classes.isEmpty(); }
    }

    private static final class Stub {
        final String name;
        boolean isInterface, annotation, throwable, extended;
        int typeParameters;
        final Map<String,Integer> methods = new TreeMap<>();   // name+desc -> access (ACC_STATIC, ACC_PROTECTED or 0 for public)
        final Map<String,Set<String>> throwsBy = new TreeMap<>();
        final Map<String,String> fields = new TreeMap<>();      // name -> desc (first seen)
        final Set<String> staticFields = new HashSet<>();
        final Set<String> constructors = new TreeSet<>();
        boolean enumType;
        final Set<String> genericReturns = new HashSet<>(), genericFields = new HashSet<>();
        final Map<String,String> elements = new TreeMap<>();      // annotation element -> descriptor
        final Set<String> constants = new TreeSet<>();            // enum constants
        // Phase 2DJ: inferred supertypes (a stub superclass, else a JDK one) and element types of collection members and of Iterable.
        String parent, jdkSuper, iterableElement;
        boolean iterable, implemented;
        final Set<String> lambdaMethods = new TreeSet<>();        // name+desc of the functional method lambdas implement
        final Set<String> interfaces = new TreeSet<>(), jdkSupers = new TreeSet<>();
        final Map<String,String> elementTypes = new HashMap<>();  // "F name" or method name+desc -> element type signature
        Stub(String name) { this.name = name; }
    }

    public static Result synthesize(Map<String,byte[]> originals, List<Path> dependencyJars, Path outputJar) throws IOException {
        return synthesize(originals, dependencyJars, outputJar, MAX_STUBS);
    }
    /** @param maxStubs the stub budget; {@code truncated} is set only when a missing type was refused because of it */
    static Result synthesize(Map<String,byte[]> originals, List<Path> dependencyJars, Path outputJar, int maxStubs) throws IOException {
        var nodes = new TreeMap<String,ClassModel>();
        var flow = new StubTypeFlow.Evidence();
        for (var entry : originals.entrySet()) {
            try { nodes.put(entry.getKey(), ClassModel.read(entry.getValue())); } catch (RuntimeException unreadable) { continue; /* no evidence */ }
            StubTypeFlow.analyze(entry.getValue(), flow);
        }
        var supplied = new HashSet<String>(originals.keySet());
        for (var jar : dependencyJars) {
            try (var zip = new ZipFile(jar.toFile())) {
                var entries = zip.entries();
                while (entries.hasMoreElements()) {
                    String name = entries.nextElement().getName();
                    if (name.endsWith(".class")) supplied.add(name.substring(0, name.length() - 6));
                }
            }
        }
        var jdk = new JdkTypes();
        var stubs = new TreeMap<String,Stub>();
        boolean[] truncated = {false};
        java.util.function.Function<String,Stub> missing = name -> {
            if (name == null || name.startsWith("[") || supplied.contains(name) || name.equals("module-info") || name.endsWith("/package-info")) return null;
            if (!stubs.containsKey(name)) {
                if (jdk.has(name)) return null;
                // Phase 2DF: truncated means a missing type was refused, not merely that the count reached the limit.
                if (stubs.size() >= maxStubs) { truncated[0] = true; return null; }
            }
            return stubs.computeIfAbsent(name, Stub::new);
        };
        // Pass 1: kinds, owners of members, supertypes and generic arity.
        for (var node : nodes.values()) {
            var superStub = missing.apply(node.superName);
            if (superStub != null) superStub.extended = true;
            for (String type : node.interfaces) { var stub = missing.apply(type); if (stub != null) { stub.isInterface = true; stub.implemented = true; } }
            for (var target : node.lambdaTargets) {
                var stub = missing.apply(target.owner());
                if (stub != null) { stub.isInterface = true; stub.lambdaMethods.add(target.descriptor()); stub.methods.putIfAbsent(target.descriptor(), 0); }
            }
            for (String type : node.annotations) { var stub = missing.apply(type); if (stub != null) { stub.annotation = true; stub.isInterface = true; } }
            for (String type : node.throwables) { var stub = missing.apply(type); if (stub != null) stub.throwable = true; }
            for (var arity : node.genericArity.entrySet()) {
                var stub = missing.apply(arity.getKey());
                if (stub != null) stub.typeParameters = Math.max(stub.typeParameters, arity.getValue());
            }
            for (String type : node.mentioned) missing.apply(type);
            for (var used : node.annotationElements.entrySet()) {
                var stub = stubs.get(used.getKey());
                if (stub != null) used.getValue().forEach(stub.elements::putIfAbsent);
            }
            for (var used : node.enumConstants.entrySet()) {
                var stub = missing.apply(used.getKey());
                if (stub != null) { stub.enumType = true; stub.constants.addAll(used.getValue()); }
            }
        }
        // Pass 2: members, through the owner named by each instruction (or the nearest missing superclass of an input owner).
        for (var node : nodes.values()) {
            for (var use : node.uses) {
                Stub target = ownerStub(use.owner(), use.name(), use.descriptor(), use.field(), nodes, missing);
                if (target == null) continue;
                if (use.interfaceOwner() && stubs.get(use.owner()) == target) target.isInterface = true;
                if (stubs.get(use.owner()) == target && !use.field() && (use.name().equals("values") && use.isStatic()
                    && use.descriptor().equals("()[L" + target.name + ";") || use.name().equals("ordinal") && use.descriptor().equals("()I"))) target.enumType = true;
                if (use.castResult() && stubs.get(use.owner()) == target) {
                    if (use.field()) target.genericFields.add(use.name()); else target.genericReturns.add(use.name() + use.descriptor());
                }
                if (use.field()) {
                    target.fields.putIfAbsent(use.name(), use.descriptor());
                    if (use.isStatic()) target.staticFields.add(use.name());
                } else if (use.name().equals("<init>")) {
                    if (stubs.get(use.owner()) == target) target.constructors.add(use.descriptor());
                } else {
                    target.methods.merge(use.name() + use.descriptor(), use.isStatic() ? Opcodes.ACC_STATIC : 0, (a, b) -> a);
                    for (String caught : use.caught()) target.throwsBy.computeIfAbsent(use.name() + use.descriptor(), key -> new TreeSet<>()).add(caught);
                }
            }
            // Possible overrides of a missing superclass's methods, so decompiled @Override annotations still compile.
            var superStub = node.superName == null ? null : stubs.get(node.superName);
            if (superStub != null && !superStub.isInterface)
                for (var method : node.instanceMethods) {
                    superStub.methods.putIfAbsent(method, 0);
                    // Phase 2CW: an override declared protected (Activity.onCreate) needs a protected stub: a public one
                    // would make the override "attempting to assign weaker access privileges".
                    if (node.protectedMethods.contains(method) && superStub.methods.get(method) == 0) superStub.methods.put(method, Opcodes.ACC_PROTECTED);
                    // Phase 2DJ: an override may throw only what the overridden method declares, so the stub declares at least that.
                    var thrown = node.methodExceptions.get(method);
                    if (thrown != null) superStub.throwsBy.computeIfAbsent(method, key -> new TreeSet<>()).addAll(thrown);
                }
        }
        // Constructors that input subclasses call through super(...).
        for (var node : nodes.values())
            for (var call : node.superConstructorCalls) { var stub = stubs.get(call.owner()); if (stub != null) stub.constructors.add(call.descriptor()); }
        // A nested stub needs every enclosing class to be a stub too (or supplied), so javac resolves Outer.Middle.Inner;
        // Phase 2DF walks each name up to its top-level class instead of stopping at the immediate outer one.
        for (String name : new ArrayList<>(stubs.keySet())) {
            int start = name.lastIndexOf('/') + 1;
            for (int dollar = name.lastIndexOf('$'); dollar > start; dollar = name.lastIndexOf('$', dollar - 1))
                missing.apply(name.substring(0, dollar));
        }
        int[] links = inferSupertypes(stubs, nodes, flow);
        inferElements(stubs, flow);
        int methods = 0, fields = 0;
        var packages = new TreeMap<String,Integer>();
        Files.createDirectories(outputJar.getParent());
        try (var zip = new ZipOutputStream(Files.newOutputStream(outputJar, StandardOpenOption.CREATE_NEW))) {
            for (var stub : stubs.values()) {
                byte[] bytes = write(stub, stubs);
                var entry = new ZipEntry(stub.name + ".class"); entry.setTime(0);
                zip.putNextEntry(entry); zip.write(bytes); zip.closeEntry();
                methods += stub.methods.size(); fields += stub.fields.size();
                int slash = stub.name.lastIndexOf('/');
                packages.merge(slash < 0 ? "" : stub.name.substring(0, slash).replace('/', '.'), 1, Integer::sum);
            }
        }
        return new Result(Collections.unmodifiableSortedSet(new TreeSet<>(stubs.keySet())), Collections.unmodifiableSortedMap(packages),
            methods, fields, truncated[0], links[0], links[1]);
    }

    /** Phase 2DJ: the kinds {@link #write} declares. */
    private static boolean enumKind(Stub stub) { return !stub.annotation && stub.enumType && !stub.extended; }
    private static boolean interfaceKind(Stub stub) { return !stub.annotation && !enumKind(stub) && stub.isInterface && !stub.extended && !stub.throwable; }
    private static boolean classKind(Stub stub) { return !stub.annotation && !enumKind(stub) && !interfaceKind(stub); }

    /**
     * Phase 2DJ: supertypes from the assignments {@link StubTypeFlow} found. A value of type A used where B is declared
     * makes B a supertype of A when A is a stub, or of A's nearest missing superclass when A is an input class whose own
     * hierarchy does not reach B. B is used only when it is a stub or a JDK type. An interface stub can only extend
     * interfaces, so a class-kind stub it needs becomes an interface when nothing marks it as a class. A class with
     * several required superclasses gets the one that reaches the others; when the evidence does not order two of
     * them, the one with fewer known subtypes is put below (counted in {@code guessedOrder}). Cycles are refused.
     *
     * @return {links, guessedOrder}
     */
    private static int[] inferSupertypes(Map<String,Stub> stubs, Map<String,ClassModel> nodes, StubTypeFlow.Evidence flow) {
        var required = new TreeMap<String,Set<String>>();
        for (var pair : flow.assignable()) {
            String from = pair.get(0), to = pair.get(1);
            if (!stubs.containsKey(to) && jdkClass(to) == null) continue;
            Stub target = stubs.containsKey(from) ? stubs.get(from) : missingAncestor(from, to, nodes, stubs);
            if (target == null || target.name.equals(to) || target.annotation) continue;
            required.computeIfAbsent(target.name, key -> new TreeSet<>()).add(to);
        }
        // A narrowing cast between two class-kind stubs (or to a stub from a JDK class): the target is the subclass.
        // Casts that involve an interface prove nothing, since any non-final class may be cast to or from one.
        for (var pair : flow.casts()) {
            var target = stubs.get(pair.get(0));
            var from = stubs.get(pair.get(1));
            Class<?> jdk = from == null ? jdkClass(pair.get(1)) : null;
            if (target == null || !classKind(target) || target.isInterface) continue;
            if (from != null ? !classKind(from) || from.isInterface : jdk == null || jdk.isInterface()) continue;
            required.computeIfAbsent(target.name, key -> new TreeSet<>()).add(pair.get(1));
        }
        for (boolean changed = true; changed; ) {
            changed = false;
            for (var entry : required.entrySet()) {
                if (!interfaceKind(stubs.get(entry.getKey()))) continue;
                for (String to : entry.getValue()) {
                    var other = stubs.get(to);
                    if (other != null && classKind(other) && promotable(other)) { other.isInterface = true; changed = true; }
                }
            }
        }
        int links = 0, guessed = 0;
        var edges = new TreeMap<String,Set<String>>();   // class stub -> stub superclasses it needs
        for (var entry : required.entrySet()) {
            var stub = stubs.get(entry.getKey());
            for (String to : entry.getValue()) {
                var other = stubs.get(to);
                if (other != null && other.annotation) continue;
                Class<?> jdk = other == null ? jdkClass(to) : null;
                if (other != null ? interfaceKind(other) : jdk.isInterface()) {
                    if (!reaches(to, stub.name, stubs, edges)) { stub.interfaces.add(to); links++; }
                } else if (classKind(stub)) {
                    if (other == null) stub.jdkSupers.add(to);
                    else if (!reaches(to, stub.name, stubs, edges)) edges.computeIfAbsent(stub.name, key -> new TreeSet<>()).add(to);
                }
            }
        }
        // One superclass each: keep the candidate that reaches the others, ordering the rest below it when needed.
        var subtypes = new HashMap<String,Integer>();
        for (var targets : edges.values()) for (String to : targets) subtypes.merge(to, 1, Integer::sum);
        for (int round = 0; round < 100_000; round++) {
            var crowded = edges.entrySet().stream().filter(entry -> entry.getValue().size() > 1).findFirst().orElse(null);
            if (crowded == null) break;
            var candidates = new ArrayList<>(crowded.getValue());
            var reach = new HashMap<String,Integer>();
            for (String candidate : candidates)
                reach.put(candidate, (int) candidates.stream().filter(other -> !other.equals(candidate) && reaches(candidate, other, stubs, edges)).count());
            candidates.sort(Comparator.<String>comparingInt(reach::get).reversed().thenComparingInt(name -> subtypes.getOrDefault(name, 0)).thenComparing(name -> name));
            String best = candidates.getFirst();
            for (String other : candidates)
                if (!other.equals(best) && !reaches(best, other, stubs, edges) && !reaches(other, best, stubs, edges)) {
                    edges.computeIfAbsent(best, key -> new TreeSet<>()).add(other); guessed++;
                }
            crowded.setValue(new TreeSet<>(List.of(best)));
        }
        for (var entry : edges.entrySet()) { stubs.get(entry.getKey()).parent = entry.getValue().iterator().next(); links++; }
        // JDK superclasses go to the top of each chain; a throwable's ancestors are throwables.
        for (var stub : stubs.values()) {
            if (stub.throwable) for (var up = stub; up.parent != null; ) { up = stubs.get(up.parent); up.throwable = true; }
            if (stub.jdkSupers.isEmpty()) continue;
            var top = stub;
            for (int depth = 0; top.parent != null && depth < 256; depth++) top = stubs.get(top.parent);
            for (String jdk : stub.jdkSupers) {
                Class<?> type = jdkClass(jdk);
                if (!extendable(type)) continue;
                if (top.jdkSuper == null) { top.jdkSuper = jdk; links++; }
                else if (jdkClass(top.jdkSuper).isAssignableFrom(type) && !jdk.equals(top.jdkSuper)) top.jdkSuper = jdk;
            }
        }
        for (var stub : stubs.values())
            if (stub.jdkSuper != null && stub.throwable && !Throwable.class.isAssignableFrom(jdkClass(stub.jdkSuper))) { stub.jdkSuper = null; links--; }
        return new int[]{links, guessed};
    }

    /** The nearest missing superclass of input class {@code from}, unless its known hierarchy already reaches {@code to}. */
    private static Stub missingAncestor(String from, String to, Map<String,ClassModel> nodes, Map<String,Stub> stubs) {
        var node = nodes.get(from);
        for (int depth = 0; node != null && depth < 64; depth++) {
            if (to.equals(node.superName) || node.interfaces.contains(to)) return null;
            var parent = node.superName == null ? null : stubs.get(node.superName);
            if (parent != null) return parent;
            node = nodes.get(node.superName);
        }
        return null;
    }

    /** Whether {@code from} reaches {@code to} through the stub parents, superclass candidates and interfaces known so far. */
    private static boolean reaches(String from, String to, Map<String,Stub> stubs, Map<String,Set<String>> edges) {
        var seen = new HashSet<String>();
        var work = new ArrayDeque<String>(List.of(from));
        while (!work.isEmpty()) {
            String current = work.pop();
            if (current.equals(to)) return true;
            if (!seen.add(current)) continue;
            var stub = stubs.get(current);
            if (stub == null) continue;
            work.addAll(stub.interfaces);
            if (stub.parent != null) work.add(stub.parent);
            work.addAll(edges.getOrDefault(current, Set.of()));
        }
        return false;
    }

    /** A class-kind stub can become an interface only when nothing needs it to be a class. */
    private static boolean promotable(Stub stub) {
        return !stub.extended && !stub.throwable && !stub.enumType && !stub.annotation && stub.constructors.isEmpty()
            && stub.staticFields.containsAll(stub.fields.keySet()) && !stub.methods.containsValue(Opcodes.ACC_PROTECTED);
    }

    /** A JDK class a stub may extend: public, not final, with an accessible no-argument constructor for the stub's own. */
    private static boolean extendable(Class<?> type) {
        if (type == null || type.isInterface() || type.isEnum() || type.isRecord() || type.isArray()) return false;
        int modifiers = type.getModifiers();
        if (!java.lang.reflect.Modifier.isPublic(modifiers) || java.lang.reflect.Modifier.isFinal(modifiers)) return false;
        try {
            int constructor = type.getDeclaredConstructor().getModifiers();
            return java.lang.reflect.Modifier.isPublic(constructor) || java.lang.reflect.Modifier.isProtected(constructor);
        } catch (NoSuchMethodException | SecurityException | LinkageError none) { return false; }
    }

    /** A JDK type by internal name, looked up without initializing it; {@code null} for anything else. */
    private static Class<?> jdkClass(String internalName) {
        if (internalName == null || internalName.startsWith("[")) return null;
        try { return Class.forName(internalName.replace('/', '.'), false, ClassLoader.getPlatformClassLoader()); }
        catch (ClassNotFoundException | LinkageError notJdk) { return null; }
    }

    /**
     * Phase 2DJ: element types of collection members, when every cast after {@code next()} or {@code get(int)} has one
     * erasure, or fit together through the inferred supertypes ({@link #element}), and {@code Iterable} for a stub iterated
     * through its own {@code iterator()}.
     */
    private static void inferElements(Map<String,Stub> stubs, StubTypeFlow.Evidence flow) {
        for (var entry : flow.elements().entrySet()) {
            String element = element(entry.getValue(), stubs);
            String[] origin = entry.getKey().split(" ");
            var stub = origin.length < 2 ? null : stubs.get(origin[1]);
            if (element == null || stub == null) continue;
            switch (origin[0]) {
                case "F" -> stub.elementTypes.put("F " + origin[2], element);
                case "M" -> stub.elementTypes.put(origin[2] + origin[3], element);
                default -> stub.iterableElement = element;
            }
        }
        for (String name : flow.iterated()) {
            var stub = stubs.get(name);
            if (stub != null && !stub.annotation && stub.methods.getOrDefault("iterator()Ljava/util/Iterator;", Opcodes.ACC_STATIC) != Opcodes.ACC_STATIC)
                stub.iterable = true;
        }
    }
    /**
     * The element type: a candidate X (one of the cast types) that is the type of, or a subtype of, every loop variable
     * ({@code "n "}) and the type of, or a supertype of, every {@code get(int)} cast ({@code "g "}); with several, the most
     * general. Its generic signature is used when exactly one was seen.
     */
    private static String element(Set<String> seen, Map<String,Stub> stubs) {
        var loops = new TreeSet<String>(); var gets = new TreeSet<String>();
        for (String entry : seen) (entry.startsWith("g ") ? gets : loops).add(StubTypeFlow.erasure(entry.substring(2)));
        var all = new TreeSet<String>(loops); all.addAll(gets);
        var fitting = new ArrayList<String>();
        for (String candidate : all)
            if (loops.stream().allMatch(loop -> below(candidate, loop, stubs)) && gets.stream().allMatch(got -> below(got, candidate, stubs))) fitting.add(candidate);
        String chosen = fitting.stream().filter(candidate -> fitting.stream().allMatch(other -> below(other, candidate, stubs))).findFirst().orElse(null);
        if (chosen == null) return null;
        var generic = seen.stream().map(entry -> entry.substring(2)).filter(signature -> signature.contains("<") && StubTypeFlow.erasure(signature).equals(chosen))
            .distinct().toList();
        return generic.size() == 1 ? generic.getFirst() : chosen;
    }
    /** Whether descriptor {@code sub} is {@code sup} or reaches it through the inferred stub supertypes. */
    private static boolean below(String sub, String sup, Map<String,Stub> stubs) {
        return sub.equals(sup) || sub.startsWith("L") && sup.startsWith("L")
            && reaches(sub.substring(1, sub.length() - 1), sup.substring(1, sup.length() - 1), stubs, Map.of());
    }
    /** A collection type's descriptor with one type argument, or {@code null} when it is not a tracked collection. */
    private static String withElement(String descriptor, String element) {
        if (element == null || !descriptor.startsWith("L") || !StubTypeFlow.COLLECTIONS.contains(descriptor.substring(1, descriptor.length() - 1))) return null;
        return descriptor.substring(0, descriptor.length() - 1) + "<" + element + ">;";
    }

    /** The stub that should declare a member: the owner itself when missing, else the nearest missing superclass of an input owner. */
    private static Stub ownerStub(String owner, String name, String descriptor, boolean field, Map<String,ClassModel> nodes,
                                  java.util.function.Function<String,Stub> missing) {
        var direct = missing.apply(owner);
        if (direct != null) return direct;
        var node = nodes.get(owner);
        for (int depth = 0; node != null && depth < 64; depth++) {
            if (field ? node.fieldNames.contains(name) : node.methodKeys.contains(name + descriptor)) return null;
            if (name.equals("<init>")) return null;
            var parent = missing.apply(node.superName);
            if (parent != null) return parent;
            node = nodes.get(node.superName);
        }
        return null;
    }

    private static byte[] write(Stub stub, Map<String,Stub> stubs) {
        var writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        int access = Opcodes.ACC_PUBLIC | Opcodes.ACC_SYNTHETIC;
        String superName = "java/lang/Object";
        access |= kindAccess(stub);
        if (enumKind(stub)) superName = "java/lang/Enum";
        else if (classKind(stub) && stub.parent != null) superName = stub.parent;
        else if (classKind(stub) && stub.jdkSuper != null) superName = stub.jdkSuper;
        else if (classKind(stub) && stub.throwable) superName = "java/lang/RuntimeException";
        boolean isEnum = (access & Opcodes.ACC_ENUM) != 0;
        boolean isInterface = (access & Opcodes.ACC_INTERFACE) != 0;
        var interfaces = new ArrayList<String>();
        if (stub.annotation) interfaces.add("java/lang/annotation/Annotation");
        else {
            interfaces.addAll(stub.interfaces);
            if (stub.iterable && !interfaces.contains("java/lang/Iterable")) interfaces.add("java/lang/Iterable");
        }
        boolean genericIterable = stub.iterable && stub.iterableElement != null;
        String signature = null;
        // Phase 2DJ: with a signature, javac takes the supertypes from it, so it lists the interfaces too.
        if (isEnum || stub.typeParameters > 0 && !stub.annotation || genericIterable) {
            var generic = new StringBuilder();
            if (stub.typeParameters > 0 && !isEnum && !stub.annotation) {
                generic.append('<');
                for (int i = 1; i <= stub.typeParameters; i++) generic.append('T').append(i).append(":Ljava/lang/Object;");
                generic.append('>');
            }
            generic.append(isEnum ? "Ljava/lang/Enum<L" + stub.name + ";>;" : "L" + superName + ";");
            for (String type : interfaces)
                generic.append(type.equals("java/lang/Iterable") && genericIterable ? "Ljava/lang/Iterable<" + stub.iterableElement + ">;" : "L" + type + ";");
            signature = generic.toString();
        }
        int dollar = stub.name.lastIndexOf('$');
        boolean nested = dollar > stub.name.lastIndexOf('/') + 1 && stubs.containsKey(stub.name.substring(0, dollar));
        if (nested) access |= Opcodes.ACC_STATIC;
        // Every annotation type implements java.lang.annotation.Annotation, or javac refuses it in an annotation use.
        writer.visit(Opcodes.V1_8, access & ~Opcodes.ACC_STATIC, stub.name, signature, superName,
            interfaces.isEmpty() ? null : interfaces.toArray(String[]::new));
        writer.visitSource("ClassRevive inferred stub", null);
        // Phase 2DJ: javac takes a nested type's kind from these flags, in the outer class too: an annotation listed
        // there as a plain class was "not an annotation interface".
        if (nested) writer.visitInnerClass(stub.name, stub.name.substring(0, dollar), stub.name.substring(dollar + 1),
            Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | kindAccess(stub));
        for (var inner : stubs.values()) {
            int innerDollar = inner.name.lastIndexOf('$');
            if (innerDollar > inner.name.lastIndexOf('/') + 1 && inner.name.substring(0, innerDollar).equals(stub.name))
                writer.visitInnerClass(inner.name, stub.name, inner.name.substring(innerDollar + 1), Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | kindAccess(inner));
        }
        if (isEnum) {
            String self = "L" + stub.name + ";";
            for (String constant : stub.constants)
                writer.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL | Opcodes.ACC_ENUM, constant, self, null, null).visitEnd();
            for (var field : stub.fields.entrySet())
                if (field.getValue().equals(self) && stub.staticFields.contains(field.getKey()) && !stub.constants.contains(field.getKey()))
                    writer.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL | Opcodes.ACC_ENUM, field.getKey(), self, null, null).visitEnd();
            stub.fields.entrySet().removeIf(field -> field.getValue().equals(self) && stub.staticFields.contains(field.getKey()));
            stub.constants.forEach(stub.fields::remove);
            var constructor = writer.visitMethod(Opcodes.ACC_PRIVATE, "<init>", "(Ljava/lang/String;I)V", null, null);
            constructor.visitCode(); constructor.visitVarInsn(Opcodes.ALOAD, 0); constructor.visitVarInsn(Opcodes.ALOAD, 1); constructor.visitVarInsn(Opcodes.ILOAD, 2);
            constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Enum", "<init>", "(Ljava/lang/String;I)V", false);
            constructor.visitInsn(Opcodes.RETURN); constructor.visitMaxs(0, 0); constructor.visitEnd();
            stub.methods.putIfAbsent("values()[" + self, Opcodes.ACC_STATIC);
            stub.methods.putIfAbsent("valueOf(Ljava/lang/String;)" + self, Opcodes.ACC_STATIC);
            stub.methods.remove("ordinal()I"); stub.methods.remove("name()Ljava/lang/String;");
        }
        if (stub.annotation) {
            for (var element : stub.elements.entrySet()) {
                var method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, element.getKey(), "()" + element.getValue(), null, null);
                if (hasDefault(element.getValue(), stubs)) {
                    var defaults = method.visitAnnotationDefault();
                    writeDefault(defaults, element.getValue(), stubs);
                    defaults.visitEnd();
                }
                method.visitEnd();
            }
            stub.methods.clear(); stub.fields.clear();
        }
        for (var field : stub.fields.entrySet()) {
            int fieldAccess = Opcodes.ACC_PUBLIC | (stub.staticFields.contains(field.getKey()) || isInterface ? Opcodes.ACC_STATIC : 0);
            if (isInterface) fieldAccess |= Opcodes.ACC_FINAL;
            String fieldSignature = stub.typeParameters == 1 && !isInterface && (fieldAccess & Opcodes.ACC_STATIC) == 0 && field.getValue().equals("Ljava/lang/Object;")
                && stub.genericFields.contains(field.getKey()) ? "TT1;" : withElement(field.getValue(), stub.elementTypes.get("F " + field.getKey()));
            writer.visitField(fieldAccess, field.getKey(), field.getValue(), fieldSignature, null).visitEnd();
        }
        if (!isInterface && !isEnum) {
            var constructors = new TreeSet<>(stub.constructors); constructors.add("()V");
            for (String descriptor : constructors) {
                var method = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", descriptor, null, null);
                method.visitCode();
                method.visitVarInsn(Opcodes.ALOAD, 0);
                method.visitMethodInsn(Opcodes.INVOKESPECIAL, superName, "<init>", "()V", false);
                method.visitInsn(Opcodes.RETURN);
                method.visitMaxs(0, 0); method.visitEnd();
            }
        }
        // Methods that differ only by return type would make overload resolution ambiguous: the first one (by descriptor) is kept.
        var seenParameters = new HashSet<String>();
        for (var entry : stub.methods.entrySet()) {
            String key = entry.getKey();
            int paren = key.indexOf('(');
            String name = key.substring(0, paren), descriptor = key.substring(paren);
            if (!seenParameters.add(name + descriptor.substring(0, descriptor.indexOf(')') + 1))) continue;
            boolean isStatic = entry.getValue() == Opcodes.ACC_STATIC;
            int methodAccess = (entry.getValue() == Opcodes.ACC_PROTECTED ? Opcodes.ACC_PROTECTED : Opcodes.ACC_PUBLIC) | (isStatic ? Opcodes.ACC_STATIC : 0);
            // Phase 2DJ: a lambda's target needs exactly one abstract method, or javac says "not a functional interface";
            // only when no input class implements the interface, which an abstract method would force to implement it.
            boolean functional = isInterface && !stub.implemented && !isStatic && !stub.lambdaMethods.isEmpty() && key.equals(stub.lambdaMethods.iterator().next());
            if (stub.annotation && !isStatic || functional) {
                // With one type parameter, the functional method's Object parameters are that parameter (Predicate<T>.apply(T)).
                String generic = functional && stub.typeParameters == 1 && descriptor.contains("Ljava/lang/Object;")
                    ? descriptor.substring(0, descriptor.indexOf(')')).replace("Ljava/lang/Object;", "TT1;") + descriptor.substring(descriptor.indexOf(')')) : null;
                writer.visitMethod(methodAccess | Opcodes.ACC_ABSTRACT, name, descriptor, generic, null).visitEnd();
                continue;
            }
            var exceptions = stub.throwsBy.getOrDefault(key, Set.of()).toArray(String[]::new);
            String methodSignature = stub.typeParameters == 1 && !isStatic && descriptor.endsWith(")Ljava/lang/Object;") && stub.genericReturns.contains(key)
                ? descriptor.substring(0, descriptor.length() - "Ljava/lang/Object;".length()) + "TT1;" : null;
            if (methodSignature == null) {
                int close = descriptor.indexOf(')');
                String element = genericIterable && !isStatic && key.equals("iterator()Ljava/util/Iterator;") ? stub.iterableElement : stub.elementTypes.get(key);
                String returned = withElement(descriptor.substring(close + 1), element);
                if (returned != null) methodSignature = descriptor.substring(0, close + 1) + returned;
            }
            var method = writer.visitMethod(methodAccess, name, descriptor, methodSignature, exceptions.length == 0 ? null : exceptions);
            method.visitCode();
            method.visitInsn(Opcodes.ACONST_NULL);
            method.visitInsn(Opcodes.ATHROW);
            method.visitMaxs(0, 0); method.visitEnd();
        }
        writer.visitEnd();
        return writer.toByteArray();
    }

    /** Phase 2DJ: the kind flags of a stub, for its class and for every InnerClasses entry naming it. */
    private static int kindAccess(Stub stub) {
        if (stub.annotation) return Opcodes.ACC_ANNOTATION | Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT;
        if (enumKind(stub)) return Opcodes.ACC_ENUM | Opcodes.ACC_FINAL;
        if (interfaceKind(stub)) return Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT;
        return 0;
    }

    /** Whether a neutral default exists: primitives, String, Class, arrays, and stub enums with a known constant. */
    private static boolean hasDefault(String descriptor, Map<String,Stub> stubs) {
        if (descriptor.startsWith("[") || descriptor.length() == 1 || descriptor.equals("Ljava/lang/String;") || descriptor.equals("Ljava/lang/Class;")) return true;
        var stub = descriptor.startsWith("L") ? stubs.get(descriptor.substring(1, descriptor.length() - 1)) : null;
        return stub != null && stub.enumType && !stub.constants.isEmpty();
    }
    /** A neutral default so an annotation use that omits the element still compiles (the first constant for an enum). */
    private static void writeDefault(AnnotationVisitor defaults, String descriptor, Map<String,Stub> stubs) {
        if (descriptor.startsWith("L") && !descriptor.equals("Ljava/lang/String;") && !descriptor.equals("Ljava/lang/Class;")) {
            defaults.visitEnum(null, descriptor, stubs.get(descriptor.substring(1, descriptor.length() - 1)).constants.iterator().next());
            return;
        }
        switch (descriptor) {
            case "Ljava/lang/String;" -> defaults.visit(null, "");
            case "Ljava/lang/Class;" -> defaults.visit(null, Type.getType("Ljava/lang/Object;"));
            case "I" -> defaults.visit(null, 0); case "J" -> defaults.visit(null, 0L); case "Z" -> defaults.visit(null, false);
            case "B" -> defaults.visit(null, (byte) 0); case "C" -> defaults.visit(null, (char) 0); case "S" -> defaults.visit(null, (short) 0);
            case "F" -> defaults.visit(null, 0f); case "D" -> defaults.visit(null, 0d);
            default -> { if (descriptor.startsWith("[")) defaults.visitArray(null).visitEnd(); }
        }
    }

    /** JDK class lookup through the runtime image, cached per package. */
    private static final class JdkTypes {
        private final List<Path> modules = new ArrayList<>();
        private final Map<String,List<Path>> byPackage = new HashMap<>();
        JdkTypes() {
            try (var stream = Files.list(FileSystems.getFileSystem(URI.create("jrt:/")).getPath("/modules"))) { stream.sorted().forEach(modules::add); }
            catch (IOException | RuntimeException unavailable) { /* no runtime image: nothing counts as a JDK type */ }
        }
        boolean has(String internalName) {
            int slash = internalName.lastIndexOf('/');
            String pkg = slash < 0 ? "" : internalName.substring(0, slash);
            var candidates = byPackage.computeIfAbsent(pkg, key -> modules.stream().filter(module -> Files.isDirectory(module.resolve(key))).toList());
            for (var module : candidates) if (Files.isRegularFile(module.resolve(internalName + ".class"))) return true;
            return false;
        }
    }

    /** What one input class tells about the types it uses. */
    private static final class ClassModel extends ClassVisitor {
        record Use(String owner, String name, String descriptor, boolean field, boolean isStatic, boolean interfaceOwner, Set<String> caught,
                   boolean castResult) {
            Use(String owner, String name, String descriptor, boolean field, boolean isStatic, boolean interfaceOwner, Set<String> caught) {
                this(owner, name, descriptor, field, isStatic, interfaceOwner, caught, false);
            }
        }
        record Call(String owner, String descriptor) {}
        String name, superName;
        final List<String> interfaces = new ArrayList<>();
        final Set<String> annotations = new HashSet<>(), throwables = new HashSet<>(), mentioned = new HashSet<>();
        final Map<String,Integer> genericArity = new HashMap<>();
        final List<Use> uses = new ArrayList<>();
        final List<Call> superConstructorCalls = new ArrayList<>();
        final Set<String> methodKeys = new HashSet<>(), fieldNames = new HashSet<>(), instanceMethods = new TreeSet<>(), protectedMethods = new HashSet<>();
        final Map<String,Map<String,String>> annotationElements = new HashMap<>();
        final Map<String,Set<String>> enumConstants = new HashMap<>();
        final Map<String,List<String>> methodExceptions = new HashMap<>();
        final List<Call> lambdaTargets = new ArrayList<>();   // functional interface, method name+erased descriptor
        ClassModel() { super(Opcodes.ASM9); }

        /** Records the elements of one annotation use; nested annotations and enum values are followed. */
        private AnnotationVisitor elements(String annotationType) {
            var found = annotationElements.computeIfAbsent(annotationType, key -> new TreeMap<>());
            return new AnnotationVisitor(Opcodes.ASM9) {
                @Override public void visit(String element, Object value) {
                    if (element != null) found.putIfAbsent(element, valueDescriptor(value));
                    if (value instanceof Type type) type(type); // @Mixin(Target.class): the class named in the value is used too
                }
                @Override public void visitEnum(String element, String descriptor, String value) {
                    if (element != null) found.putIfAbsent(element, descriptor);
                    enumValue(descriptor, value);
                }
                @Override public AnnotationVisitor visitAnnotation(String element, String descriptor) {
                    if (element != null) found.putIfAbsent(element, descriptor);
                    annotation(descriptor);
                    return elements(Type.getType(descriptor).getInternalName());
                }
                @Override public AnnotationVisitor visitArray(String element) {
                    String[] inner = {null};
                    return new AnnotationVisitor(Opcodes.ASM9) {
                        @Override public void visit(String unused, Object value) {
                            inner[0] = inner[0] == null ? valueDescriptor(value) : inner[0];
                            if (value instanceof Type type) type(type);
                        }
                        @Override public void visitEnum(String unused, String descriptor, String value) { inner[0] = descriptor; enumValue(descriptor, value); }
                        @Override public AnnotationVisitor visitAnnotation(String unused, String descriptor) {
                            inner[0] = descriptor; annotation(descriptor); return elements(Type.getType(descriptor).getInternalName());
                        }
                        @Override public void visitEnd() { if (element != null) found.putIfAbsent(element, "[" + (inner[0] == null ? "Ljava/lang/String;" : inner[0])); }
                    };
                }
            };
        }
        private void enumValue(String descriptor, String value) {
            Type type = Type.getType(descriptor);
            if (type.getSort() == Type.OBJECT) { mentioned.add(type.getInternalName()); enumConstants.computeIfAbsent(type.getInternalName(), key -> new TreeSet<>()).add(value); }
        }
        private static String valueDescriptor(Object value) {
            if (value instanceof Type type) return "Ljava/lang/Class;";
            if (value instanceof String) return "Ljava/lang/String;";
            if (value instanceof Integer) return "I"; if (value instanceof Long) return "J"; if (value instanceof Boolean) return "Z";
            if (value instanceof Byte) return "B"; if (value instanceof Character) return "C"; if (value instanceof Short) return "S";
            if (value instanceof Float) return "F"; if (value instanceof Double) return "D";
            if (value instanceof int[]) return "[I"; if (value instanceof long[]) return "[J"; if (value instanceof boolean[]) return "[Z";
            if (value instanceof byte[]) return "[B"; if (value instanceof char[]) return "[C"; if (value instanceof short[]) return "[S";
            if (value instanceof float[]) return "[F"; if (value instanceof double[]) return "[D";
            return "Ljava/lang/String;";
        }

        static ClassModel read(byte[] bytes) {
            var model = new ClassModel();
            // Debug attributes are read on purpose: local variable signatures are often the only trace of a type's generic arity.
            new ClassReader(bytes).accept(model, ClassReader.SKIP_FRAMES);
            return model;
        }
        private void type(Type type) {
            while (type.getSort() == Type.ARRAY) type = type.getElementType();
            if (type.getSort() == Type.OBJECT) mentioned.add(type.getInternalName());
        }
        private void descriptor(String descriptor) {
            Type type = Type.getType(descriptor);
            if (type.getSort() == Type.METHOD) { type(type.getReturnType()); for (var argument : type.getArgumentTypes()) type(argument); }
            else type(type);
        }
        private void signature(String signature) {
            if (signature == null) return;
            try {
                new SignatureReader(signature).accept(new SignatureVisitor(Opcodes.ASM9) {
                    final Deque<String> classes = new ArrayDeque<>(); final Deque<Integer> counts = new ArrayDeque<>();
                    @Override public void visitClassType(String type) { classes.push(type); counts.push(0); }
                    String last;
                    @Override public void visitInnerClassType(String inner) { finish(); classes.push((last == null ? "" : last + "$") + inner); counts.push(0); }
                    @Override public SignatureVisitor visitTypeArgument(char wildcard) { if (!counts.isEmpty()) counts.push(counts.pop() + 1); return this; }
                    @Override public void visitTypeArgument() { if (!counts.isEmpty()) counts.push(counts.pop() + 1); }
                    @Override public void visitEnd() { finish(); }
                    private void finish() {
                        if (classes.isEmpty()) return;
                        String type = classes.pop(); int count = counts.pop();
                        last = type;
                        mentioned.add(type);
                        if (count > 0) genericArity.merge(type, count, Math::max);
                    }
                });
            } catch (RuntimeException malformed) { /* a broken signature adds nothing */ }
        }
        @Override public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
            this.name = name; this.superName = superName;
            if (interfaces != null) this.interfaces.addAll(Arrays.asList(interfaces));
            if (superName != null) mentioned.add(superName);
            signature(signature);
        }
        @Override public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) { annotation(descriptor); return elements(Type.getType(descriptor).getInternalName()); }
        private void annotation(String descriptor) { Type type = Type.getType(descriptor); if (type.getSort() == Type.OBJECT) annotations.add(type.getInternalName()); }
        @Override public FieldVisitor visitField(int access, String field, String descriptor, String signature, Object value) {
            fieldNames.add(field); descriptor(descriptor); signature(signature);
            return new FieldVisitor(Opcodes.ASM9) {
                @Override public AnnotationVisitor visitAnnotation(String annotation, boolean visible) { annotation(annotation); return elements(Type.getType(annotation).getInternalName()); }
            };
        }
        @Override public MethodVisitor visitMethod(int access, String method, String descriptor, String signature, String[] exceptions) {
            methodKeys.add(method + descriptor); descriptor(descriptor); signature(signature);
            if ((access & (Opcodes.ACC_STATIC | Opcodes.ACC_PRIVATE | Opcodes.ACC_SYNTHETIC)) == 0 && !method.startsWith("<")) {
                instanceMethods.add(method + descriptor);
                if ((access & Opcodes.ACC_PROTECTED) != 0) protectedMethods.add(method + descriptor);
                if (exceptions != null) methodExceptions.put(method + descriptor, List.of(exceptions));
            }
            if (exceptions != null) for (String type : exceptions) { throwables.add(type); mentioned.add(type); }
            boolean constructor = method.equals("<init>");
            return new MethodVisitor(Opcodes.ASM9) {
                final List<Label[]> tries = new ArrayList<>(); final List<String> handlers = new ArrayList<>();
                final Map<Label,Integer> positions = new HashMap<>(); int position;
                final List<int[]> pendingUses = new ArrayList<>(); final List<Use> raw = new ArrayList<>();
                // An Object-typed result cast right away: a member whose declared type is a type variable (javac casts it).
                final Set<Integer> castResults = new HashSet<>(); int pendingCast = -1;
                @Override public AnnotationVisitor visitAnnotation(String annotation, boolean visible) { annotation(annotation); return elements(Type.getType(annotation).getInternalName()); }
                @Override public AnnotationVisitor visitParameterAnnotation(int parameter, String annotation, boolean visible) { annotation(annotation); return elements(Type.getType(annotation).getInternalName()); }
                @Override public void visitLabel(Label label) { positions.put(label, position); }
                @Override public void visitLocalVariable(String local, String desc, String generic, Label start, Label end, int index) { descriptor(desc); signature(generic); }
                @Override public void visitTryCatchBlock(Label start, Label end, Label handler, String type) {
                    if (type != null) { tries.add(new Label[]{start, end}); handlers.add(type); throwables.add(type); mentioned.add(type); }
                }
                @Override public void visitTypeInsn(int opcode, String type) {
                    position++;
                    if (opcode == Opcodes.CHECKCAST && pendingCast >= 0) castResults.add(pendingCast);
                    pendingCast = -1;
                    if (!type.startsWith("[")) mentioned.add(type); else descriptor(type);
                }
                @Override public void visitLdcInsn(Object value) { position++; if (value instanceof Type type && type.getSort() != Type.METHOD) type(type); }
                @Override public void visitFieldInsn(int opcode, String owner, String field, String desc) {
                    position++; mentioned.add(owner); descriptor(desc);
                    raw.add(new Use(owner, field, desc, true, opcode == Opcodes.GETSTATIC || opcode == Opcodes.PUTSTATIC, false, Set.of()));
                    pendingUses.add(new int[]{position, raw.size() - 1});
                    pendingCast = opcode == Opcodes.GETFIELD && desc.equals("Ljava/lang/Object;") ? raw.size() - 1 : -1;
                }
                @Override public void visitMethodInsn(int opcode, String owner, String called, String desc, boolean isInterface) {
                    position++;
                    if (owner.startsWith("[")) return;
                    mentioned.add(owner); descriptor(desc);
                    if (constructor && opcode == Opcodes.INVOKESPECIAL && called.equals("<init>") && owner.equals(superName))
                        superConstructorCalls.add(new Call(owner, desc));
                    raw.add(new Use(owner, called, desc, false, opcode == Opcodes.INVOKESTATIC, isInterface || opcode == Opcodes.INVOKEINTERFACE, Set.of()));
                    pendingUses.add(new int[]{position, raw.size() - 1});
                    pendingCast = opcode != Opcodes.INVOKESTATIC && desc.endsWith(")Ljava/lang/Object;") ? raw.size() - 1 : -1;
                }
                @Override public void visitInvokeDynamicInsn(String called, String desc, Handle bootstrap, Object... arguments) {
                    position++; descriptor(desc);
                    if (bootstrap.getOwner().equals("java/lang/invoke/LambdaMetafactory") && arguments.length > 0 && arguments[0] instanceof Type sam
                        && Type.getReturnType(desc).getSort() == Type.OBJECT)
                        lambdaTargets.add(new Call(Type.getReturnType(desc).getInternalName(), called + sam.getDescriptor()));
                    for (var argument : arguments) if (argument instanceof Handle handle && !handle.getOwner().startsWith("[")) {
                        mentioned.add(handle.getOwner());
                        boolean field = handle.getTag() <= Opcodes.H_PUTSTATIC;
                        boolean isStatic = handle.getTag() == Opcodes.H_INVOKESTATIC || handle.getTag() == Opcodes.H_GETSTATIC || handle.getTag() == Opcodes.H_PUTSTATIC;
                        raw.add(new Use(handle.getOwner(), handle.getName(), handle.getDesc(), field, isStatic, handle.isInterface(), Set.of()));
                    }
                }
                @Override public void visitMultiANewArrayInsn(String desc, int dimensions) { position++; descriptor(desc); }
                @Override public void visitInsn(int opcode) { position++; pendingCast = -1; }
                @Override public void visitIntInsn(int opcode, int operand) { position++; pendingCast = -1; }
                @Override public void visitVarInsn(int opcode, int var) { position++; pendingCast = -1; }
                @Override public void visitJumpInsn(int opcode, Label label) { position++; pendingCast = -1; }
                @Override public void visitIincInsn(int var, int increment) { position++; pendingCast = -1; }
                @Override public void visitTableSwitchInsn(int min, int max, Label dflt, Label... labels) { position++; }
                @Override public void visitLookupSwitchInsn(Label dflt, int[] keys, Label[] labels) { position++; }
                @Override public void visitEnd() {
                    // A checked exception caught around a range whose only calls are these: those calls may throw it.
                    var caughtBy = new HashMap<Integer,Set<String>>();
                    for (int t = 0; t < tries.size(); t++) {
                        Integer start = positions.get(tries.get(t)[0]), end = positions.get(tries.get(t)[1]);
                        if (start == null || end == null || !checked(handlers.get(t))) continue;
                        var inRange = new ArrayList<Integer>();
                        for (int[] pending : pendingUses) if (pending[0] > start && pending[0] <= end && !raw.get(pending[1]).field()) inRange.add(pending[1]);
                        if (inRange.size() == 1) caughtBy.computeIfAbsent(inRange.getFirst(), key -> new TreeSet<>()).add(handlers.get(t));
                    }
                    for (int i = 0; i < raw.size(); i++) {
                        var use = raw.get(i);
                        var caught = new TreeSet<String>();
                        caught.addAll(caughtBy.getOrDefault(i, Set.of()));
                        uses.add(new Use(use.owner(), use.name(), use.descriptor(), use.field(), use.isStatic(), use.interfaceOwner(), caught, castResults.contains(i)));
                    }
                }
            };
        }
        /** A JDK checked exception type (Exception, not RuntimeException); stub and unknown types are not counted. */
        private static boolean checked(String type) {
            try {
                Class<?> found = Class.forName(type.replace('/', '.'), false, ClassLoader.getPlatformClassLoader());
                return Exception.class.isAssignableFrom(found) && !RuntimeException.class.isAssignableFrom(found);
            } catch (ClassNotFoundException | LinkageError notJdk) { return false; }
        }
    }
}
