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
 * <li>annotation elements (with defaults) and enum constants, from annotation values and enum-shaped uses.</li>
 * </ul>
 * Stub methods are concrete (interface methods are {@code default}) and their bodies only throw, so no subclass is
 * forced to implement anything. Unknown supertypes are {@code Object} ({@code RuntimeException} for throwables).
 */
public final class StubSynthesizer {
    private StubSynthesizer() {}
    public static final int MAX_STUBS = 20_000;

    /** {@code classes}: internal names of the stubs written; {@code packages}: package to stub count. */
    public record Result(SortedSet<String> classes, SortedMap<String,Integer> packages, int methods, int fields, boolean truncated) {
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
        Stub(String name) { this.name = name; }
    }

    public static Result synthesize(Map<String,byte[]> originals, List<Path> dependencyJars, Path outputJar) throws IOException {
        return synthesize(originals, dependencyJars, outputJar, MAX_STUBS);
    }
    /** @param maxStubs the stub budget; {@code truncated} is set only when a missing type was refused because of it */
    static Result synthesize(Map<String,byte[]> originals, List<Path> dependencyJars, Path outputJar, int maxStubs) throws IOException {
        var nodes = new TreeMap<String,ClassModel>();
        for (var entry : originals.entrySet()) {
            try { nodes.put(entry.getKey(), ClassModel.read(entry.getValue())); } catch (RuntimeException unreadable) { /* no evidence */ }
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
            for (String type : node.interfaces) { var stub = missing.apply(type); if (stub != null) stub.isInterface = true; }
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
            methods, fields, truncated[0]);
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
        if (stub.annotation) access |= Opcodes.ACC_ANNOTATION | Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT;
        else if (stub.enumType && !stub.extended) { access |= Opcodes.ACC_ENUM | Opcodes.ACC_FINAL; superName = "java/lang/Enum"; }
        else if (stub.isInterface && !stub.extended && !stub.throwable) access |= Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT;
        else if (stub.throwable) superName = "java/lang/RuntimeException";
        boolean isEnum = (access & Opcodes.ACC_ENUM) != 0;
        boolean isInterface = (access & Opcodes.ACC_INTERFACE) != 0;
        String signature = isEnum ? "Ljava/lang/Enum<L" + stub.name + ";>;" : null;
        if (stub.typeParameters > 0 && !isEnum && !stub.annotation) {
            var params = new StringBuilder("<");
            for (int i = 1; i <= stub.typeParameters; i++) params.append('T').append(i).append(":Ljava/lang/Object;");
            signature = params.append(">L").append(superName).append(";").toString();
        }
        int dollar = stub.name.lastIndexOf('$');
        boolean nested = dollar > stub.name.lastIndexOf('/') + 1 && stubs.containsKey(stub.name.substring(0, dollar));
        if (nested) access |= Opcodes.ACC_STATIC;
        // Every annotation type implements java.lang.annotation.Annotation, or javac refuses it in an annotation use.
        writer.visit(Opcodes.V1_8, access & ~Opcodes.ACC_STATIC, stub.name, signature, superName,
            stub.annotation ? new String[]{"java/lang/annotation/Annotation"} : null);
        writer.visitSource("ClassRevive inferred stub", null);
        if (nested) writer.visitInnerClass(stub.name, stub.name.substring(0, dollar), stub.name.substring(dollar + 1),
            Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | (access & (Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT | Opcodes.ACC_ANNOTATION)));
        for (var inner : stubs.values()) {
            int innerDollar = inner.name.lastIndexOf('$');
            if (innerDollar > inner.name.lastIndexOf('/') + 1 && inner.name.substring(0, innerDollar).equals(stub.name))
                writer.visitInnerClass(inner.name, stub.name, inner.name.substring(innerDollar + 1), Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC);
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
                && stub.genericFields.contains(field.getKey()) ? "TT1;" : null;
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
            if (stub.annotation && !isStatic) {
                writer.visitMethod(methodAccess | Opcodes.ACC_ABSTRACT, name, descriptor, null, null).visitEnd();
                continue;
            }
            var exceptions = stub.throwsBy.getOrDefault(key, Set.of()).toArray(String[]::new);
            String methodSignature = stub.typeParameters == 1 && !isStatic && descriptor.endsWith(")Ljava/lang/Object;") && stub.genericReturns.contains(key)
                ? descriptor.substring(0, descriptor.length() - "Ljava/lang/Object;".length()) + "TT1;" : null;
            var method = writer.visitMethod(methodAccess, name, descriptor, methodSignature, exceptions.length == 0 ? null : exceptions);
            method.visitCode();
            method.visitInsn(Opcodes.ACONST_NULL);
            method.visitInsn(Opcodes.ATHROW);
            method.visitMaxs(0, 0); method.visitEnd();
        }
        writer.visitEnd();
        return writer.toByteArray();
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
