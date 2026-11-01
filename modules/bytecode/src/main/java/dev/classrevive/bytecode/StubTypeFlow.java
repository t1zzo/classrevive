package dev.classrevive.bytecode;

import java.util.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.*;

/**
 * Phase 2DJ: type evidence for inferred stubs from a static data-flow pass over the input's own bytecode (ASM's
 * {@link Analyzer}; nothing is loaded or run). Each value carries the type its producer declares (a descriptor, a
 * {@code new}, a {@code checkcast}); where the bytecode hands it to a consumer that declares another type (an argument,
 * a field store, a return, a local variable whose debug entry names its type), the producer's type must be assignable
 * to the consumer's, since the bytecode was verified and javac inserts no cast for a widening. Those pairs become
 * supertypes between missing types. Merged values of different types are unknown and give no evidence.
 *
 * <p>Values also remember which member produced a JDK collection, so the casts javac puts after {@code Iterator.next()}
 * or {@code List.get(int)} (and the generic signature of the local the element is stored in) give the element type of a
 * stub's raw {@code List}, {@code Collection} or {@code Set} member, and a stub iterated through its own
 * {@code iterator()} (a for-each) is {@code Iterable}.
 */
final class StubTypeFlow {
    private StubTypeFlow() {}
    /** Methods larger than this are skipped: the evidence is a bonus, not worth an unbounded analysis. */
    static final int MAX_INSTRUCTIONS = 30_000;
    /** JDK types with exactly one type parameter whose elements are tracked. */
    static final Set<String> COLLECTIONS = Set.of("java/lang/Iterable", "java/util/Collection", "java/util/List", "java/util/Set",
        "java/util/SortedSet", "java/util/NavigableSet", "java/util/Queue", "java/util/Deque", "java/util/ArrayList", "java/util/LinkedList",
        "java/util/HashSet", "java/util/LinkedHashSet", "java/util/TreeSet", "java/util/ArrayDeque", "java/util/Vector",
        "java/util/concurrent/CopyOnWriteArrayList", "java/util/Iterator");

    /**
     * {@code assignable}: [from, to] internal names, a value of type {@code from} used where {@code to} is declared;
     * {@code elements}: origin to element type signatures seen, prefixed {@code "n "} after {@code next()} (the loop
     * variable's type) or {@code "g "} after {@code get(int)}; an origin is {@code "F owner name"} (a field),
     * {@code "M owner name descriptor"} (a method's result) or {@code "I owner"} (iterated through the owner's own
     * {@code iterator()}, which is also put in {@code iterated} once a {@code next()} follows); {@code casts}: [to, from],
     * a {@code checkcast} or {@code instanceof} to {@code to} of a value declared {@code from} (javac casts only to
     * narrow, and an {@code instanceof} that cannot fail is pointless, so between two classes {@code to} is a subclass).
     */
    record Evidence(Set<List<String>> assignable, Map<String,Set<String>> elements, Set<String> iterated, Set<List<String>> casts) {
        Evidence() { this(pairs(), new TreeMap<>(), new TreeSet<>(), pairs()); }
        private static Set<List<String>> pairs() {
            return new TreeSet<>(Comparator.comparing((List<String> pair) -> pair.get(0)).thenComparing(pair -> pair.get(1)));
        }
    }

    /** Adds what one class's methods show; unreadable classes and methods the analyzer rejects add nothing. */
    static void analyze(byte[] bytes, Evidence into) {
        var node = new ClassNode();
        try { new ClassReader(bytes).accept(node, ClassReader.SKIP_FRAMES); } catch (RuntimeException unreadable) { return; }
        for (var method : node.methods) {
            if (method.instructions.size() == 0 || method.instructions.size() > MAX_INSTRUCTIONS) continue;
            try { new Analyzer<>(new Flow(method, into)).analyze(node.name, method); }
            catch (AnalyzerException | RuntimeException rejected) { /* no evidence from this method */ }
        }
    }

    /** A value: its declared type ({@code null} when unknown), and the collection member it came from, with a stage. */
    static final class V implements Value {
        // An element from next() is cast to the loop variable's type (a supertype of the element, or the element itself);
        // one from get(int) to the element's erasure, or straight to a subtype by an explicit cast: they are kept apart.
        static final int COLLECTION = 0, ITERATOR = 1, ELEMENT = 2, CAST_ELEMENT = 3, GOT = 4, CAST_GOT = 5;
        static final V UNINITIALIZED = new V(null, 1, false, null, 0), UNKNOWN = new V(null, 1, true, null, 0),
            NULL = new V(Type.getObjectType("null"), 1, true, null, 0), INT = new V(Type.INT_TYPE, 1, false, null, 0),
            FLOAT = new V(Type.FLOAT_TYPE, 1, false, null, 0), LONG = new V(Type.LONG_TYPE, 2, false, null, 0),
            DOUBLE = new V(Type.DOUBLE_TYPE, 2, false, null, 0);
        final Type type; final int size; final boolean reference; final String origin; final int stage;
        V(Type type, int size, boolean reference, String origin, int stage) {
            this.type = type; this.size = size; this.reference = reference; this.origin = origin; this.stage = stage;
        }
        static V of(Type type) { return new V(type, 1, true, null, 0); }
        static V of(Type type, String origin, int stage) { return new V(type, 1, true, origin, stage); }
        @Override public int getSize() { return size; }
        @Override public boolean equals(Object other) {
            return other instanceof V v && size == v.size && reference == v.reference && stage == v.stage
                && Objects.equals(type, v.type) && Objects.equals(origin, v.origin);
        }
        @Override public int hashCode() { return Objects.hash(type, size, reference, origin, stage); }
    }

    private static final class Flow extends Interpreter<V> {
        private final MethodNode method;
        private final Evidence evidence;
        Flow(MethodNode method, Evidence evidence) { super(Opcodes.ASM9); this.method = method; this.evidence = evidence; }

        @Override public V newValue(Type type) {
            if (type == null) return V.UNINITIALIZED;
            return switch (type.getSort()) {
                case Type.VOID -> null;
                case Type.BOOLEAN, Type.CHAR, Type.BYTE, Type.SHORT, Type.INT -> V.INT;
                case Type.FLOAT -> V.FLOAT;
                case Type.LONG -> V.LONG;
                case Type.DOUBLE -> V.DOUBLE;
                case Type.ARRAY, Type.OBJECT -> V.of(type);
                default -> V.UNKNOWN;
            };
        }

        @Override public V newOperation(AbstractInsnNode insn) {
            switch (insn.getOpcode()) {
                case Opcodes.ACONST_NULL: return V.NULL;
                case Opcodes.LCONST_0: case Opcodes.LCONST_1: return V.LONG;
                case Opcodes.FCONST_0: case Opcodes.FCONST_1: case Opcodes.FCONST_2: return V.FLOAT;
                case Opcodes.DCONST_0: case Opcodes.DCONST_1: return V.DOUBLE;
                case Opcodes.LDC: {
                    Object value = ((LdcInsnNode) insn).cst;
                    if (value instanceof Integer) return V.INT;
                    if (value instanceof Float) return V.FLOAT;
                    if (value instanceof Long) return V.LONG;
                    if (value instanceof Double) return V.DOUBLE;
                    if (value instanceof String) return V.of(Type.getObjectType("java/lang/String"));
                    if (value instanceof Type type) return V.of(Type.getObjectType(type.getSort() == Type.METHOD ? "java/lang/invoke/MethodType" : "java/lang/Class"));
                    if (value instanceof Handle) return V.of(Type.getObjectType("java/lang/invoke/MethodHandle"));
                    if (value instanceof ConstantDynamic constant) return newValue(Type.getType(constant.getDescriptor()));
                    return V.UNKNOWN;
                }
                case Opcodes.JSR: return V.UNINITIALIZED;
                case Opcodes.GETSTATIC: {
                    var field = (FieldInsnNode) insn;
                    return tracked(newValue(Type.getType(field.desc)), field.desc, "F " + field.owner + " " + field.name);
                }
                case Opcodes.NEW: return V.of(Type.getObjectType(((TypeInsnNode) insn).desc));
                default: return V.INT; // ICONST_*, BIPUSH, SIPUSH
            }
        }

        @Override public V copyOperation(AbstractInsnNode insn, V value) {
            if (insn.getOpcode() == Opcodes.ASTORE && value.reference) {
                // A local whose debug entry gives its type: the stored value must be assignable to it.
                var local = local(((VarInsnNode) insn).var, method.instructions.indexOf(insn));
                if (local != null) {
                    Type declared = Type.getType(local.desc);
                    assign(value, declared);
                    if ((value.stage == V.CAST_ELEMENT || value.stage == V.CAST_GOT) && value.origin != null && local.signature != null && concrete(local.signature))
                        evidence.elements.computeIfAbsent(value.origin, key -> new TreeSet<>()).add((value.stage == V.CAST_GOT ? "g " : "n ") + local.signature);
                    // From here on the local has the type javac saw: `Player p = localPlayer; Living l = p;` shows Player <: Living.
                    if (!declared.equals(value.type) && (declared.getSort() == Type.OBJECT || declared.getSort() == Type.ARRAY)) return V.of(declared);
                }
            }
            return value;
        }

        @Override public V unaryOperation(AbstractInsnNode insn, V value) {
            switch (insn.getOpcode()) {
                case Opcodes.INEG: case Opcodes.IINC: case Opcodes.L2I: case Opcodes.F2I: case Opcodes.D2I:
                case Opcodes.I2B: case Opcodes.I2C: case Opcodes.I2S: case Opcodes.ARRAYLENGTH: return V.INT;
                case Opcodes.INSTANCEOF: narrowed(value, Type.getObjectType(((TypeInsnNode) insn).desc)); return V.INT;
                case Opcodes.FNEG: case Opcodes.I2F: case Opcodes.L2F: case Opcodes.D2F: return V.FLOAT;
                case Opcodes.LNEG: case Opcodes.I2L: case Opcodes.F2L: case Opcodes.D2L: return V.LONG;
                case Opcodes.DNEG: case Opcodes.I2D: case Opcodes.L2D: case Opcodes.F2D: return V.DOUBLE;
                case Opcodes.PUTSTATIC: assign(value, Type.getType(((FieldInsnNode) insn).desc)); return null;
                case Opcodes.GETFIELD: {
                    var field = (FieldInsnNode) insn;
                    assign(value, Type.getObjectType(field.owner));
                    return tracked(newValue(Type.getType(field.desc)), field.desc, "F " + field.owner + " " + field.name);
                }
                case Opcodes.NEWARRAY: return V.of(Type.getType("[" + primitive(((IntInsnNode) insn).operand)));
                case Opcodes.ANEWARRAY: return V.of(Type.getType("[" + Type.getObjectType(((TypeInsnNode) insn).desc).getDescriptor()));
                case Opcodes.CHECKCAST: {
                    Type cast = Type.getObjectType(((TypeInsnNode) insn).desc);
                    narrowed(value, cast);
                    if ((value.stage == V.ELEMENT || value.stage == V.GOT) && value.origin != null) {
                        evidence.elements.computeIfAbsent(value.origin, key -> new TreeSet<>()).add((value.stage == V.GOT ? "g " : "n ") + cast.getDescriptor());
                        return V.of(cast, value.origin, value.stage == V.GOT ? V.CAST_GOT : V.CAST_ELEMENT);
                    }
                    return V.of(cast);
                }
                default: return null; // branches, switches, returns, athrow, monitors
            }
        }

        @Override public V binaryOperation(AbstractInsnNode insn, V first, V second) {
            switch (insn.getOpcode()) {
                case Opcodes.AALOAD:
                    return first.type != null && first.type.getSort() == Type.ARRAY ? newValue(Type.getType(first.type.getDescriptor().substring(1))) : V.UNKNOWN;
                case Opcodes.PUTFIELD: {
                    var field = (FieldInsnNode) insn;
                    assign(first, Type.getObjectType(field.owner));
                    assign(second, Type.getType(field.desc));
                    return null;
                }
                case Opcodes.FALOAD: case Opcodes.FADD: case Opcodes.FSUB: case Opcodes.FMUL: case Opcodes.FDIV: case Opcodes.FREM: return V.FLOAT;
                case Opcodes.LALOAD: case Opcodes.LADD: case Opcodes.LSUB: case Opcodes.LMUL: case Opcodes.LDIV: case Opcodes.LREM:
                case Opcodes.LSHL: case Opcodes.LSHR: case Opcodes.LUSHR: case Opcodes.LAND: case Opcodes.LOR: case Opcodes.LXOR: return V.LONG;
                case Opcodes.DALOAD: case Opcodes.DADD: case Opcodes.DSUB: case Opcodes.DMUL: case Opcodes.DDIV: case Opcodes.DREM: return V.DOUBLE;
                case Opcodes.IF_ICMPEQ: case Opcodes.IF_ICMPNE: case Opcodes.IF_ICMPLT: case Opcodes.IF_ICMPGE: case Opcodes.IF_ICMPGT:
                case Opcodes.IF_ICMPLE: case Opcodes.IF_ACMPEQ: case Opcodes.IF_ACMPNE: return null;
                default: return V.INT; // int loads and arithmetic, comparisons
            }
        }

        @Override public V ternaryOperation(AbstractInsnNode insn, V first, V second, V third) { return null; }

        @Override public V naryOperation(AbstractInsnNode insn, List<? extends V> values) {
            if (insn instanceof MultiANewArrayInsnNode array) return V.of(Type.getType(array.desc));
            if (insn instanceof InvokeDynamicInsnNode dynamic) return newValue(Type.getReturnType(dynamic.desc));
            var call = (MethodInsnNode) insn;
            Type[] parameters = Type.getArgumentTypes(call.desc);
            int offset = values.size() - parameters.length;
            for (int i = 0; i < parameters.length; i++) assign(values.get(offset + i), parameters[i]);
            V receiver = offset == 1 ? values.get(0) : null;
            if (receiver != null && !call.name.equals("<init>") && !call.owner.startsWith("[")) assign(receiver, Type.getObjectType(call.owner));
            Type result = Type.getReturnType(call.desc);
            if (receiver != null && call.name.equals("iterator") && call.desc.equals("()Ljava/util/Iterator;"))
                return receiver.stage == V.COLLECTION && receiver.origin != null ? V.of(result, receiver.origin, V.ITERATOR) : V.of(result, "I " + call.owner, V.ITERATOR);
            if (receiver != null && receiver.stage == V.ITERATOR && receiver.origin != null && call.name.equals("next") && call.desc.equals("()Ljava/lang/Object;")) {
                if (receiver.origin.startsWith("I ")) evidence.iterated.add(receiver.origin.substring(2));
                return V.of(result, receiver.origin, V.ELEMENT);
            }
            if (receiver != null && receiver.stage == V.COLLECTION && receiver.origin != null && call.name.equals("get") && call.desc.equals("(I)Ljava/lang/Object;"))
                return V.of(result, receiver.origin, V.GOT);
            return tracked(newValue(result), call.desc.substring(call.desc.indexOf(')') + 1), "M " + call.owner + " " + call.name + " " + call.desc);
        }

        @Override public void returnOperation(AbstractInsnNode insn, V value, V expected) { if (expected != null) assign(value, expected.type); }

        @Override public V merge(V first, V second) {
            if (first.equals(second)) return first;
            if (first.reference && second.reference) {
                if (first == V.NULL || V.NULL.equals(first)) return second;
                if (second == V.NULL || V.NULL.equals(second)) return first;
                if (first.type != null && first.type.equals(second.type)) return V.of(first.type);
                return V.UNKNOWN;
            }
            return V.UNINITIALIZED;
        }

        /** A {@code checkcast} or {@code instanceof} test of a value of known type: javac rejects both between unrelated classes. */
        private void narrowed(V value, Type to) {
            if (value.reference && value.type != null && !V.NULL.equals(value) && value.type.getSort() == Type.OBJECT && to.getSort() == Type.OBJECT
                && !value.type.equals(to) && !value.type.getInternalName().equals("java/lang/Object"))
                evidence.casts.add(List.of(to.getInternalName(), value.type.getInternalName()));
        }

        /** A collection-typed value remembers the member it came from. */
        private static V tracked(V value, String descriptor, String origin) {
            if (value == null || !value.reference || !descriptor.startsWith("L")) return value;
            String type = descriptor.substring(1, descriptor.length() - 1);
            if (!COLLECTIONS.contains(type)) return value;
            return V.of(value.type, origin, type.equals("java/util/Iterator") ? V.ITERATOR : V.COLLECTION);
        }

        /** Records that a value of a known reference type is used where {@code expected} is declared. */
        private void assign(V value, Type expected) {
            if (value == null || !value.reference || value.type == null || value == V.NULL || V.NULL.equals(value) || expected == null) return;
            Type from = value.type, to = expected;
            while (from.getSort() == Type.ARRAY && to.getSort() == Type.ARRAY) {
                from = Type.getType(from.getDescriptor().substring(1));
                to = Type.getType(to.getDescriptor().substring(1));
            }
            if (from.getSort() != Type.OBJECT || to.getSort() != Type.OBJECT) return;
            String a = from.getInternalName(), b = to.getInternalName();
            if (a.equals(b) || b.equals("java/lang/Object") || a.equals("null")) return;
            evidence.assignable.add(List.of(a, b));
        }

        /** The debug entry of a local slot just written at instruction {@code index}: one starting right after it, or one already in scope. */
        private LocalVariableNode local(int slot, int index) {
            if (method.localVariables == null) return null;
            var following = new HashSet<LabelNode>();
            for (var next = method.instructions.get(index).getNext(); next != null && next.getOpcode() < 0; next = next.getNext())
                if (next instanceof LabelNode label) following.add(label);
            LocalVariableNode inScope = null;
            for (var local : method.localVariables) {
                if (local.index != slot) continue;
                if (following.contains(local.start)) return local;
                int start = method.instructions.indexOf(local.start), end = method.instructions.indexOf(local.end);
                if (start <= index && index < end) inScope = local;
            }
            return inScope;
        }

        private static String primitive(int operand) {
            return switch (operand) {
                case Opcodes.T_BOOLEAN -> "Z"; case Opcodes.T_CHAR -> "C"; case Opcodes.T_FLOAT -> "F"; case Opcodes.T_DOUBLE -> "D";
                case Opcodes.T_BYTE -> "B"; case Opcodes.T_SHORT -> "S"; case Opcodes.T_INT -> "I"; default -> "J";
            };
        }
    }

    /** Whether a field type signature names no type variable (an element type usable outside the method that declared it). */
    static boolean concrete(String signature) {
        boolean[] variable = {false};
        try {
            new org.objectweb.asm.signature.SignatureReader(signature).acceptType(new org.objectweb.asm.signature.SignatureVisitor(Opcodes.ASM9) {
                @Override public void visitTypeVariable(String name) { variable[0] = true; }
            });
        } catch (RuntimeException malformed) { return false; }
        return !variable[0];
    }

    /** The erasure of a field type signature, as a descriptor: type arguments dropped, inner class dots back to {@code $}. */
    static String erasure(String signature) {
        var out = new StringBuilder();
        int depth = 0;
        for (char c : signature.toCharArray()) {
            if (c == '<') depth++;
            else if (c == '>') depth--;
            else if (depth == 0) out.append(c == '.' ? '$' : c);
        }
        return out.toString();
    }
}
