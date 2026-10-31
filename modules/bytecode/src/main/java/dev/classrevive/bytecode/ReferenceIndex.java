package dev.classrevive.bytecode;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.zip.ZipFile;
import org.objectweb.asm.*;
import org.objectweb.asm.signature.SignatureReader;
import org.objectweb.asm.signature.SignatureVisitor;

/**
 * Phase G6: who uses each class, method and field of a set of class files, for "find usages" and the call hierarchy of
 * the desktop application. ASM reads the bytes; nothing is defined, loaded or run.
 * <p>
 * A site is one reference in one class: a call, a field read or write, {@code new}, a cast, {@code instanceof}, a class
 * literal, a catch type, a type in a member's descriptor or generic signature, a superclass or interface, an annotation,
 * or a method handle (lambda bodies and method references behind {@code invokedynamic}, {@code ldc} handles, custom
 * bootstrap methods). Each site keeps the member it sits in, its line from the {@code LineNumberTable} when there is one,
 * and its ordinal among the references of that member to the same target, which is how an editor finds it in text.
 * <p>
 * A member reference names the class written in the instruction. Queries resolve it the way the JVM does (superclass
 * chain, then interfaces) over the indexed classes, so a call {@code Sub.m()} counts for {@code Base.m()} when only
 * {@code Base} declares it, and a call through an overridden supertype method is reported as such.
 */
public final class ReferenceIndex {
    public enum Kind { EXTENDS, IMPLEMENTS, SIGNATURE, THROWS, ANNOTATION, NEW, NEW_ARRAY, CAST, INSTANCEOF, CLASS_LITERAL, CATCH,
        CALL, FIELD_READ, FIELD_WRITE, LAMBDA, METHOD_REFERENCE, METHOD_HANDLE }

    /** A class ({@code name} null), a field (descriptor without {@code (}) or a method. */
    public record Ref(String owner, String name, String descriptor) {
        public static Ref ofClass(String owner) { return new Ref(owner, null, null); }
        public boolean isClass() { return name == null; }
        public boolean isMethod() { return name != null && descriptor.startsWith("("); }
        public boolean isField() { return name != null && !descriptor.startsWith("("); }
        /** {@code name + descriptor} for a method, {@code name:descriptor} for a field. */
        public String key() { return isClass() ? owner : isMethod() ? name + descriptor : name + ":" + descriptor; }
        @Override public String toString() { return isClass() ? owner : owner + "." + key(); }
    }

    /**
     * One reference. {@code from} is the member that holds it (a class ref for the class header). {@code line} is the
     * source line from the {@code LineNumberTable}, or -1. {@code ordinal} counts from 0 among the references of
     * {@code from} to the same target with the same kind, in bytecode order.
     */
    public record Site(Ref from, Kind kind, Ref target, int line, int ordinal) {
        public String className() { return from.owner(); }
    }

    /** A site found by a query; {@code via} is the supertype member it names when the call reaches an override, else null. */
    public record Usage(Site site, Ref via) {}

    public record Member(String owner, String name, String descriptor, int access) {
        public Ref ref() { return new Ref(owner, name, descriptor); }
        public boolean isStatic() { return (access & Opcodes.ACC_STATIC) != 0; }
        public boolean isPrivate() { return (access & Opcodes.ACC_PRIVATE) != 0; }
    }

    public record ClassDecl(String name, String superName, List<String> interfaces, int access, List<Member> fields, List<Member> methods) {}

    /** Bounds: classes read, sites kept, and bytes per class file. */
    public record Limits(int maxClasses, int maxSites, int maxClassBytes) {
        public static Limits defaults() { return new Limits(200_000, 5_000_000, 16 * 1024 * 1024); }
    }

    /** What the build saw; {@code truncated} when a limit stopped it. */
    public record Stats(int classes, int unreadable, int sites, boolean truncated) {}

    private final Map<String,ClassDecl> classes;
    private final Map<Ref,List<Site>> byTarget;
    private final Map<String,List<Ref>> refsByOwner, refsByKey;
    private final Map<String,List<String>> subtypes;
    private final Stats stats;

    private ReferenceIndex(Map<String,ClassDecl> classes, Map<Ref,List<Site>> byTarget, Stats stats) {
        this.classes = classes;
        this.byTarget = byTarget;
        this.stats = stats;
        var byOwner = new HashMap<String,List<Ref>>();
        var byKey = new HashMap<String,List<Ref>>();
        for (Ref target : byTarget.keySet()) {
            if (target.isClass()) continue;
            byOwner.computeIfAbsent(target.owner(), k -> new ArrayList<>()).add(target);
            byKey.computeIfAbsent(target.key(), k -> new ArrayList<>()).add(target);
        }
        this.refsByOwner = byOwner;
        this.refsByKey = byKey;
        var subs = new HashMap<String,List<String>>();
        for (var decl : classes.values()) {
            if (decl.superName() != null) subs.computeIfAbsent(decl.superName(), k -> new ArrayList<>()).add(decl.name());
            for (String face : decl.interfaces()) subs.computeIfAbsent(face, k -> new ArrayList<>()).add(decl.name());
        }
        this.subtypes = subs;
    }

    /** Reads every {@code .class} entry of a jar (outside {@code META-INF/}); {@code cancelled} is polled between classes. */
    public static ReferenceIndex ofJar(Path jar, Limits limits, BooleanSupplier cancelled) throws IOException, InterruptedException {
        var builder = new Builder(limits);
        try (var zip = new ZipFile(jar.toFile())) {
            for (var entries = zip.entries(); entries.hasMoreElements(); ) {
                if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new InterruptedException("index cancelled");
                var entry = entries.nextElement();
                if (entry.isDirectory() || !entry.getName().endsWith(".class") || entry.getName().startsWith("META-INF/")) continue;
                if (entry.getSize() > limits.maxClassBytes()) { builder.unreadable++; continue; }
                byte[] bytes;
                try (var in = zip.getInputStream(entry)) { bytes = in.readNBytes(limits.maxClassBytes() + 1); }
                if (bytes.length > limits.maxClassBytes()) { builder.unreadable++; continue; }
                if (!builder.add(bytes)) break;
            }
        }
        return builder.build();
    }

    /** Adds classes one at a time; {@link #add} answers false once the class limit is reached. */
    public static final class Builder {
        private final Limits limits;
        private final Map<String,ClassDecl> classes = new HashMap<>();
        private final Map<Ref,List<Site>> byTarget = new HashMap<>();
        private final Map<Object,Object> pool = new HashMap<>();
        private int sites, unreadable;
        private boolean truncated;

        public Builder(Limits limits) { this.limits = limits; }

        public boolean add(byte[] classBytes) {
            if (classes.size() >= limits.maxClasses()) { truncated = true; return false; }
            if (classBytes.length > limits.maxClassBytes()) { unreadable++; return true; }
            try {
                var reader = new ClassReader(classBytes);
                if (classes.containsKey(reader.getClassName())) return true;
                reader.accept(new Indexer(), ClassReader.SKIP_FRAMES);
            } catch (RuntimeException malformed) {
                unreadable++;
            }
            return true;
        }

        public ReferenceIndex build() {
            byTarget.replaceAll((target, list) -> List.copyOf(list));
            return new ReferenceIndex(Map.copyOf(classes), Map.copyOf(byTarget), new Stats(classes.size(), unreadable, sites, truncated));
        }

        @SuppressWarnings("unchecked")
        private <T> T intern(T value) {
            if (value == null) return null;
            Object known = pool.putIfAbsent(value, value);
            return known == null ? value : (T) known;
        }

        private Ref ref(String owner, String name, String descriptor) { return intern(new Ref(intern(owner), intern(name), intern(descriptor))); }

        private final class Indexer extends ClassVisitor {
            private String name, superName;
            private List<String> interfaces;
            private int access;
            private final List<Member> fields = new ArrayList<>(), methods = new ArrayList<>();
            private Ref header;
            private final Map<String,Integer> ordinals = new HashMap<>();

            Indexer() { super(Opcodes.ASM9); }

            @Override public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
                this.name = intern(name);
                this.access = access;
                this.superName = intern(superName);
                this.interfaces = interfaces == null ? List.of() : Arrays.stream(interfaces).map(Builder.this::intern).toList();
                header = ref(name, null, null);
                if (superName != null) site(header, Kind.EXTENDS, ref(superName, null, null), -1, ordinals);
                boolean isInterface = (access & Opcodes.ACC_INTERFACE) != 0;
                for (String face : this.interfaces) site(header, isInterface ? Kind.EXTENDS : Kind.IMPLEMENTS, ref(face, null, null), -1, ordinals);
                var named = new LinkedHashSet<String>();
                if (superName != null) named.add(superName);
                named.addAll(this.interfaces);
                signatureTypes(signature, header, named, ordinals);
            }

            @Override public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                typeOf(descriptor, header, Kind.ANNOTATION, -1, ordinals);
                return null;
            }

            @Override public FieldVisitor visitField(int access, String fieldName, String descriptor, String signature, Object value) {
                var field = new Member(name, intern(fieldName), intern(descriptor), access);
                fields.add(field);
                Ref from = field.ref();
                var local = new HashMap<String,Integer>();
                var named = new LinkedHashSet<String>();
                descriptorTypes(Type.getType(descriptor), named);
                for (String type : named) site(from, Kind.SIGNATURE, ref(type, null, null), -1, local);
                signatureTypes(signature, from, named, local);
                return new FieldVisitor(Opcodes.ASM9) {
                    @Override public AnnotationVisitor visitAnnotation(String annotation, boolean visible) {
                        typeOf(annotation, from, Kind.ANNOTATION, -1, local);
                        return null;
                    }
                };
            }

            @Override public MethodVisitor visitMethod(int access, String methodName, String descriptor, String signature, String[] exceptions) {
                var method = new Member(name, intern(methodName), intern(descriptor), access);
                methods.add(method);
                Ref from = intern(method.ref());
                var local = new HashMap<String,Integer>();
                var named = new LinkedHashSet<String>();
                var type = Type.getMethodType(descriptor);
                for (Type argument : type.getArgumentTypes()) descriptorTypes(argument, named);
                descriptorTypes(type.getReturnType(), named);
                for (String typeName : named) site(from, Kind.SIGNATURE, ref(typeName, null, null), -1, local);
                if (exceptions != null) for (String exception : exceptions) if (named.add(exception)) site(from, Kind.THROWS, ref(exception, null, null), -1, local);
                signatureTypes(signature, from, named, local);
                return new Code(from, local);
            }

            @Override public void visitEnd() {
                classes.put(name, new ClassDecl(name, superName, interfaces, access, List.copyOf(fields), List.copyOf(methods)));
            }

            private void signatureTypes(String signature, Ref from, Set<String> named, Map<String,Integer> local) {
                if (signature == null) return;
                var found = new LinkedHashSet<String>();
                try {
                    new SignatureReader(signature).accept(new SignatureVisitor(Opcodes.ASM9) {
                        @Override public void visitClassType(String type) { found.add(type); }
                    });
                } catch (RuntimeException malformed) {
                    return;
                }
                for (String type : found) if (named.add(type)) site(from, Kind.SIGNATURE, ref(type, null, null), -1, local);
            }

            private void typeOf(String descriptor, Ref from, Kind kind, int line, Map<String,Integer> local) {
                try {
                    var named = new LinkedHashSet<String>();
                    descriptorTypes(Type.getType(descriptor), named);
                    for (String type : named) site(from, kind, ref(type, null, null), line, local);
                } catch (RuntimeException malformed) { /* not a type: nothing to index */ }
            }

            /** References of one method body, with the current line. */
            private final class Code extends MethodVisitor {
                private final Ref from;
                private final Map<String,Integer> local;
                private int line = -1;

                Code(Ref from, Map<String,Integer> local) { super(Opcodes.ASM9); this.from = from; this.local = local; }

                @Override public void visitLineNumber(int number, Label start) { line = number; }
                @Override public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) { typeOf(descriptor, from, Kind.ANNOTATION, -1, local); return null; }
                @Override public AnnotationVisitor visitParameterAnnotation(int parameter, String descriptor, boolean visible) { typeOf(descriptor, from, Kind.ANNOTATION, -1, local); return null; }

                @Override public void visitTryCatchBlock(Label start, Label end, Label handler, String type) {
                    if (type != null) site(from, Kind.CATCH, ref(type, null, null), -1, local);
                }

                @Override public void visitTypeInsn(int opcode, String type) {
                    Kind kind = switch (opcode) {
                        case Opcodes.NEW -> Kind.NEW;
                        case Opcodes.ANEWARRAY -> Kind.NEW_ARRAY;
                        case Opcodes.CHECKCAST -> Kind.CAST;
                        default -> Kind.INSTANCEOF;
                    };
                    String element = elementClass(type);
                    if (element != null) site(from, kind, ref(element, null, null), line, local);
                }

                @Override public void visitMultiANewArrayInsn(String descriptor, int dimensions) { typeOf(descriptor, from, Kind.NEW_ARRAY, line, local); }

                @Override public void visitFieldInsn(int opcode, String owner, String fieldName, String descriptor) {
                    boolean write = opcode == Opcodes.PUTFIELD || opcode == Opcodes.PUTSTATIC;
                    site(from, write ? Kind.FIELD_WRITE : Kind.FIELD_READ, ref(owner, fieldName, descriptor), line, local);
                }

                @Override public void visitMethodInsn(int opcode, String owner, String methodName, String descriptor, boolean isInterface) {
                    site(from, Kind.CALL, ref(owner, methodName, descriptor), line, local);
                }

                @Override public void visitInvokeDynamicInsn(String indyName, String descriptor, Handle bootstrap, Object... arguments) {
                    boolean lambdaFactory = "java/lang/invoke/LambdaMetafactory".equals(bootstrap.getOwner());
                    if (!lambdaFactory) handle(bootstrap, Kind.METHOD_HANDLE);
                    for (Object argument : arguments) constant(argument, lambdaFactory);
                }

                @Override public void visitLdcInsn(Object value) { constant(value, false); }

                private void constant(Object value, boolean lambdaFactory) {
                    switch (value) {
                        case Type type when type.getSort() == Type.OBJECT || type.getSort() == Type.ARRAY -> {
                            String element = elementClass(type.getInternalName());
                            if (element != null) site(from, Kind.CLASS_LITERAL, ref(element, null, null), line, local);
                        }
                        case Handle handle -> handle(handle, !lambdaFactory ? Kind.METHOD_HANDLE
                            : handle.getName().startsWith("lambda$") ? Kind.LAMBDA : Kind.METHOD_REFERENCE);
                        case ConstantDynamic dynamic -> {
                            handle(dynamic.getBootstrapMethod(), Kind.METHOD_HANDLE);
                            for (int i = 0; i < dynamic.getBootstrapMethodArgumentCount(); i++) constant(dynamic.getBootstrapMethodArgument(i), false);
                        }
                        default -> { }
                    }
                }

                private void handle(Handle handle, Kind kind) {
                    site(from, kind, ref(handle.getOwner(), handle.getName(), handle.getDesc()), line, local);
                }
            }
        }

        private void site(Ref from, Kind kind, Ref target, int line, Map<String,Integer> ordinals) {
            if (sites >= limits.maxSites()) { truncated = true; return; }
            String counter = kind.ordinal() + " " + target;
            int ordinal = ordinals.merge(counter, 1, Integer::sum) - 1;
            byTarget.computeIfAbsent(target, k -> new ArrayList<>(2)).add(new Site(from, kind, target, line, ordinal));
            sites++;
        }
    }

    /** The class an array or object type names, or null for primitive arrays. */
    private static String elementClass(String internalName) {
        if (!internalName.startsWith("[")) return internalName;
        try {
            var element = Type.getType(internalName).getElementType();
            return element.getSort() == Type.OBJECT ? element.getInternalName() : null;
        } catch (RuntimeException malformed) {
            return null;
        }
    }

    private static void descriptorTypes(Type type, Set<String> into) {
        if (type.getSort() == Type.ARRAY) type = type.getElementType();
        if (type.getSort() == Type.OBJECT) into.add(type.getInternalName());
    }

    // ----- queries -------------------------------------------------------------------------------------------------

    public Stats stats() { return stats; }
    public Map<String,ClassDecl> classes() { return classes; }
    public Optional<ClassDecl> declaration(String className) { return Optional.ofNullable(classes.get(className)); }

    /** The members a class declares under {@code name} (methods when {@code methods}, else fields), in declaration order. */
    public List<Member> declared(String className, String name, boolean methods) {
        var decl = classes.get(className);
        if (decl == null) return List.of();
        return (methods ? decl.methods() : decl.fields()).stream().filter(member -> member.name().equals(name)).toList();
    }

    /** Superclass first, then interfaces, breadth first; only indexed classes are walked, each once. */
    public List<String> supertypes(String className) {
        var order = new ArrayList<String>();
        var seen = new HashSet<String>(Set.of(className));
        var queue = new ArrayDeque<String>(List.of(className));
        while (!queue.isEmpty()) {
            var decl = classes.get(queue.poll());
            if (decl == null) continue;
            var next = new ArrayList<String>();
            if (decl.superName() != null) next.add(decl.superName());
            next.addAll(decl.interfaces());
            for (String type : next) if (seen.add(type)) { order.add(type); queue.add(type); }
        }
        return order;
    }

    /** Every indexed class that extends or implements {@code className}, directly or not. */
    public Set<String> subtypes(String className) {
        var found = new LinkedHashSet<String>();
        var queue = new ArrayDeque<String>(List.of(className));
        while (!queue.isEmpty()) for (String sub : subtypes.getOrDefault(queue.poll(), List.of())) if (found.add(sub)) queue.add(sub);
        return found;
    }

    /**
     * The declaration a member reference reaches, by JVM resolution: the named class, its superclasses, then its
     * interfaces. Empty when the chain leaves the indexed classes before a declaration is found.
     */
    public Optional<Member> resolve(Ref ref) {
        if (ref.isClass()) return Optional.empty();
        boolean method = ref.isMethod();
        // Constructors and class initializers belong to the named class only.
        if (method && ref.name().startsWith("<")) return own(ref.owner(), ref, true);
        for (String type = ref.owner(); type != null; ) {
            var found = own(type, ref, method);
            if (found.isPresent()) return found;
            var decl = classes.get(type);
            if (decl == null) return Optional.empty();
            type = decl.superName();
        }
        for (String type : supertypes(ref.owner())) {
            var found = own(type, ref, method);
            if (found.isPresent()) return found;
        }
        return Optional.empty();
    }

    private Optional<Member> own(String className, Ref ref, boolean method) {
        var decl = classes.get(className);
        if (decl == null) return Optional.empty();
        for (var member : method ? decl.methods() : decl.fields())
            if (member.name().equals(ref.name()) && member.descriptor().equals(ref.descriptor())) return Optional.of(member);
        return Optional.empty();
    }

    /**
     * The usages of a class, field or method, sorted by class, member and bytecode order. For a class: every type
     * reference to it and every member reference that names it. For a member: every reference that resolves to it, and,
     * for an instance method, every call that resolves to a supertype method it overrides ({@link Usage#via}).
     */
    public List<Usage> usages(Ref target) {
        var found = new ArrayList<Usage>();
        if (target.isClass()) {
            for (Site site : byTarget.getOrDefault(target, List.of())) found.add(new Usage(site, null));
            for (Ref member : refsByOwner.getOrDefault(target.owner(), List.of()))
                for (Site site : byTarget.get(member)) found.add(new Usage(site, null));
        } else {
            var declared = resolve(target);
            Ref declaration = declared.map(Member::ref).orElse(target);
            boolean virtual = target.isMethod() && declared.isPresent() && !declared.get().isStatic() && !declared.get().isPrivate()
                && !target.name().startsWith("<");
            var overridden = new HashSet<Ref>();
            if (virtual) for (String type : supertypes(declaration.owner()))
                own(type, target, true).filter(member -> !member.isStatic() && !member.isPrivate()).ifPresent(member -> overridden.add(member.ref()));
            for (Ref candidate : refsByKey.getOrDefault(declaration.key(), List.of())) {
                Ref reached = resolve(candidate).map(Member::ref).orElse(candidate);
                Ref via = reached.equals(declaration) ? null : overridden.contains(reached) ? reached : NOT_A_USAGE;
                if (via == NOT_A_USAGE) continue;
                for (Site site : byTarget.get(candidate)) found.add(new Usage(site, via));
            }
        }
        found.sort(ORDER);
        return found;
    }

    private static final Ref NOT_A_USAGE = new Ref("", "", "");
    private static final Comparator<Usage> ORDER = Comparator.<Usage,String>comparing(usage -> usage.site().className())
        .thenComparing(usage -> usage.site().from().isClass() ? "" : usage.site().from().key())
        .thenComparingInt(usage -> usage.site().line())
        .thenComparing(usage -> usage.site().kind())
        .thenComparingInt(usage -> usage.site().ordinal());

    /** The kinds that make {@code from} a caller of the target method: calls, lambda bodies, method references, handles. */
    public static boolean calls(Kind kind) {
        return kind == Kind.CALL || kind == Kind.LAMBDA || kind == Kind.METHOD_REFERENCE || kind == Kind.METHOD_HANDLE;
    }
}
