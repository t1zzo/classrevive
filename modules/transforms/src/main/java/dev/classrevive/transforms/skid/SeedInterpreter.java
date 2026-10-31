package dev.classrevive.transforms.skid;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.Interpreter;
import java.util.*;
import java.util.function.IntFunction;

/**
 * Phase 2CN: the transfer functions of the seed analysis. Int constants, int arithmetic, the obfuscator's seed fields,
 * {@code Integer.parseInt("digits")}, {@code new Random(long).nextInt()} and calls to statically evaluable pure
 * methods produce finite sets; everything else is unknown.
 */
final class SeedInterpreter extends Interpreter<SV> implements Opcodes {
    /** Values the jar-wide model knows: the obfuscator's seed fields. */
    interface Facts {
        Integer staticInt(String owner, String name, String desc);
        Integer instanceInt(String owner, String name, String desc);
        /** The obfuscator's own hash and XOR helpers (a factory class method, a per-class XOR helper). */
        boolean hashFunction(String owner, String name, String desc);
    }
    static final int MAX_PRODUCT = 4096;
    private final Facts facts;
    private final PureEvaluator pure;
    private final Map<List<Object>,SV.Prov> provs = new HashMap<>();
    private final Map<List<Object>,Optional<Object>> calls = new HashMap<>();
    /** Constant pushes the reversal itself wrote in place of obfuscator values (they stay the obfuscator's in later rounds). */
    private Set<AbstractInsnNode> generated = Set.of();
    void generated(Set<AbstractInsnNode> pushes) { this.generated = pushes; }

    SeedInterpreter(Facts facts, PureEvaluator pure) { super(ASM9); this.facts = facts; this.pure = pure; }

    private SV.Prov prov(List<Object> key, int local, IntFunction<Integer> fn) {
        return provs.computeIfAbsent(key, k -> new SV.Prov(local, fn));
    }

    @Override public SV newValue(Type type) {
        if (type == null) return SV.UNINIT;
        return switch (type.getSort()) {
            case Type.VOID -> null;
            case Type.BOOLEAN, Type.CHAR, Type.BYTE, Type.SHORT, Type.INT -> SV.INT_TOP;
            case Type.FLOAT -> SV.FLOAT_TOP;
            case Type.LONG -> SV.LONG_TOP;
            case Type.DOUBLE -> SV.DOUBLE_TOP;
            case Type.ARRAY -> SV.ref(type.getDescriptor(), null);
            case Type.OBJECT -> SV.ref(type.getInternalName(), null);
            default -> SV.REF_TOP;
        };
    }

    @Override public SV newOperation(AbstractInsnNode insn) {
        SV value = newOperation0(insn);
        return generated.contains(insn) ? value.taint() : value;
    }

    private SV newOperation0(AbstractInsnNode insn) {
        int op = insn.getOpcode();
        switch (op) {
            case ACONST_NULL: return SV.ref(null, null);
            case ICONST_M1, ICONST_0, ICONST_1, ICONST_2, ICONST_3, ICONST_4, ICONST_5: return SV.literal(op - ICONST_0);
            case LCONST_0, LCONST_1: return SV.longConstant((long) (op - LCONST_0));
            case FCONST_0, FCONST_1, FCONST_2: return SV.FLOAT_TOP;
            case DCONST_0, DCONST_1: return SV.DOUBLE_TOP;
            case BIPUSH, SIPUSH: return SV.literal(((IntInsnNode) insn).operand);
            case LDC: {
                Object cst = ((LdcInsnNode) insn).cst;
                if (cst instanceof Integer value) return SV.literal(value);
                if (cst instanceof Long value) return SV.longConstant(value);
                if (cst instanceof Float) return SV.FLOAT_TOP;
                if (cst instanceof Double) return SV.DOUBLE_TOP;
                if (cst instanceof String text) return SV.ref("java/lang/String", text);
                if (cst instanceof Type type) return SV.ref(type.getSort() == Type.METHOD ? "java/lang/invoke/MethodType" : "java/lang/Class", null);
                return SV.REF_TOP;
            }
            case JSR: return SV.RET;
            case GETSTATIC: {
                var field = (FieldInsnNode) insn;
                Integer known = facts.staticInt(field.owner, field.name, field.desc);
                return known != null ? SV.of(known).taint() : newValue(Type.getType(field.desc));
            }
            case NEW: return SV.ref(((TypeInsnNode) insn).desc, null);
            default: throw new IllegalStateException("newOperation " + op);
        }
    }

    @Override public SV copyOperation(AbstractInsnNode insn, SV value) {
        if (insn.getOpcode() == ILOAD && value.kind == SV.Kind.INT) {
            int local = ((VarInsnNode) insn).var;
            return value.withProv(prov(List.of(insn), local, s -> s));
        }
        if (insn.getOpcode() >= ISTORE && insn.getOpcode() <= ASTORE) return value.withoutProv();
        return value;
    }

    @Override public SV unaryOperation(AbstractInsnNode insn, SV value) {
        int op = insn.getOpcode();
        switch (op) {
            case INEG, I2B, I2C, I2S: return mapInt(insn, value, v -> Interpret.unaryInt(op, v));
            case IINC: {
                int incr = ((IincInsnNode) insn).incr;
                SV mapped = mapInt(insn, value, v -> v + incr);
                return mapped.withoutProv();
            }
            case L2I, F2I, D2I: return SV.INT_TOP;
            case I2L, F2L, D2L: return SV.LONG_TOP;
            case I2F, L2F, D2F: return SV.FLOAT_TOP;
            case I2D, L2D, F2D: return SV.DOUBLE_TOP;
            case LNEG: return SV.LONG_TOP;
            case FNEG: return SV.FLOAT_TOP;
            case DNEG: return SV.DOUBLE_TOP;
            case GETFIELD: {
                var field = (FieldInsnNode) insn;
                Integer known = facts.instanceInt(field.owner, field.name, field.desc);
                return known != null ? SV.of(known).taint() : newValue(Type.getType(field.desc));
            }
            case NEWARRAY: {
                String desc = switch (((IntInsnNode) insn).operand) {
                    case T_BOOLEAN -> "[Z"; case T_CHAR -> "[C"; case T_BYTE -> "[B"; case T_SHORT -> "[S"; case T_INT -> "[I";
                    case T_FLOAT -> "[F"; case T_DOUBLE -> "[D"; default -> "[J"; };
                return SV.ref(desc, null);
            }
            case ANEWARRAY: return SV.ref("[" + Type.getObjectType(((TypeInsnNode) insn).desc).getDescriptor(), null);
            case ARRAYLENGTH, INSTANCEOF: return SV.INT_TOP;
            case CHECKCAST: return SV.ref(((TypeInsnNode) insn).desc, value.constant);
            default: return null; // branches, switches, returns, PUTSTATIC, ATHROW, monitors
        }
    }

    private SV mapInt(AbstractInsnNode insn, SV value, IntFunction<Integer> fn) {
        if (value.kind != SV.Kind.INT) return SV.INT_TOP;
        int[] result = null;
        if (value.ints != null) {
            var out = new ArrayList<Integer>(value.ints.length);
            for (int v : value.ints) { Integer r = fn.apply(v); if (r == null) { out = null; break; } out.add(r); }
            if (out != null) result = SV.sorted(out);
        }
        SV.Prov prov = null;
        if (value.prov != null) {
            var parent = value.prov;
            prov = prov(List.of(insn, parent), parent.local(), s -> { Integer p = parent.fn().apply(s); return p == null ? null : fn.apply(p); });
        }
        return SV.ints(result, prov, value.tainted);
    }

    @Override public SV binaryOperation(AbstractInsnNode insn, SV a, SV b) {
        int op = insn.getOpcode();
        switch (op) {
            case IADD, ISUB, IMUL, IDIV, IREM, ISHL, ISHR, IUSHR, IAND, IOR, IXOR: {
                int[] result = null;
                if (a.kind == SV.Kind.INT && b.kind == SV.Kind.INT && a.ints != null && b.ints != null && (long) a.ints.length * b.ints.length <= MAX_PRODUCT) {
                    var out = new TreeSet<Integer>();
                    boolean failed = false;
                    for (int x : a.ints) for (int y : b.ints) { Integer r = Interpret.binaryInt(op, x, y); if (r == null) failed = true; else out.add(r); }
                    if (!failed) result = SV.sorted(out);
                }
                SV.Prov prov = null;
                if (a.prov != null && b.singleton()) {
                    var parent = a.prov; int c = b.single();
                    prov = prov(List.of(insn, parent, "L", c), parent.local(), s -> { Integer p = parent.fn().apply(s); return p == null ? null : Interpret.binaryInt(op, p, c); });
                } else if (b.prov != null && a.singleton()) {
                    var parent = b.prov; int c = a.single();
                    prov = prov(List.of(insn, parent, "R", c), parent.local(), s -> { Integer p = parent.fn().apply(s); return p == null ? null : Interpret.binaryInt(op, c, p); });
                }
                // javac folds operations on two literals; an unfolded one is the obfuscator's. An operation with a value of
                // the program (neither literal nor obfuscator-derived) is the program's, even if its other operand was decrypted.
                boolean constantA = a.tainted || a.literal, constantB = b.tainted || b.literal;
                return SV.ints(result, prov, constantA && constantB);
            }
            case LADD, LSUB, LMUL, LDIV, LREM, LSHL, LSHR, LUSHR, LAND, LOR, LXOR: return SV.LONG_TOP;
            case FADD, FSUB, FMUL, FDIV, FREM: return SV.FLOAT_TOP;
            case DADD, DSUB, DMUL, DDIV, DREM: return SV.DOUBLE_TOP;
            case LCMP, FCMPL, FCMPG, DCMPL, DCMPG: return SV.INT_TOP;
            case IALOAD, BALOAD, CALOAD, SALOAD: return SV.INT_TOP;
            case LALOAD: return SV.LONG_TOP;
            case FALOAD: return SV.FLOAT_TOP;
            case DALOAD: return SV.DOUBLE_TOP;
            case AALOAD: {
                String type = a.type != null && a.type.startsWith("[") ? a.type.substring(1) : null;
                if (type != null && type.startsWith("L")) type = type.substring(1, type.length() - 1);
                return SV.ref(type, null);
            }
            default: return null; // IF_ICMPxx, IF_ACMPxx, PUTFIELD
        }
    }

    @Override public SV ternaryOperation(AbstractInsnNode insn, SV a, SV b, SV c) { return null; }

    @Override public SV naryOperation(AbstractInsnNode insn, List<? extends SV> values) {
        if (insn instanceof MethodInsnNode call) {
            Type result = Type.getReturnType(call.desc);
            if (call.getOpcode() == INVOKESTATIC) {
                if (call.owner.equals("java/lang/Integer") && call.name.equals("parseInt") && call.desc.equals("(Ljava/lang/String;)I")
                    && values.getFirst().constant instanceof String text) {
                    // Skidfuscator's constructor seed. It counts as a constant (like a literal): an operation joining it with the
                    // obfuscator's values is the obfuscator's; alone it stays as written.
                    try { return SV.literal(Integer.parseInt(text)); } catch (NumberFormatException error) { return SV.INT_TOP; }
                }
                // Only the obfuscator's functions, or a pure function applied to one of their results (Skidfuscator's
                // "pure function hashing"), are evaluated; the program's own calls on constants stay as they are.
                boolean own = facts.hashFunction(call.owner, call.name, call.desc);
                if (PureEvaluator.primitiveDescriptor(call.desc) && result.getSort() <= Type.INT
                    && (own || values.stream().anyMatch(v -> v.hashed))) {
                    SV evaluated = evaluate(call, values);
                    if (evaluated != null) return own && facts.hashFunction(call.owner, call.name, "hash") ? evaluated.hashedValue() : evaluated;
                    // A pure-function hash this analysis cannot evaluate (a java.awt method, say): its value is unknown but
                    // still the obfuscator's, so a test on it may be decided by Skidfuscator's trap invariant.
                    return SV.ints(null, null, true);
                }
                // A JDK pure function or factory hash on literals only (Skidfuscator sometimes emits the hash already
                // folded): a constant, but not the obfuscator's by itself; only a test guarding a trap is decided with it.
                if (PureEvaluator.primitiveDescriptor(call.desc) && result.getSort() <= Type.INT
                    && (own || PureEvaluator.Allowlist.CLASSES.contains(call.owner))
                    && values.stream().allMatch(v -> v.literal || v.kind == SV.Kind.LONG && v.constant != null)) {
                    SV evaluated = evaluate(call, values);
                    if (evaluated != null && evaluated.singleton()) return SV.literal(evaluated.single());
                }
                // A static function this analysis does not run (outside the allowlist) applied to literals of which one looks
                // like a folded seed hash (a 32-bit value, at least 2^20 in size): Skidfuscator's pure-function hash, opaque.
                if (PureEvaluator.primitiveDescriptor(call.desc) && result.getSort() <= Type.INT
                    && values.stream().allMatch(v -> v.literal || v.kind == SV.Kind.LONG && v.constant != null)
                    && values.stream().anyMatch(v -> v.singleton() && Math.abs((long) v.single()) >= 1 << 20)) {
                    return SV.ints(null, null, true);
                }
            }
            if (call.owner.equals("java/util/Random") && call.name.equals("nextInt") && call.desc.equals("()I")) {
                Long seed = randomSeed(call);
                if (seed != null) return SV.of(new Random(seed).nextInt()).taint();
            }
            return newValue(result);
        }
        if (insn instanceof InvokeDynamicInsnNode indy) return newValue(Type.getReturnType(indy.desc));
        if (insn instanceof MultiANewArrayInsnNode array) return SV.ref(array.desc, null);
        return SV.REF_TOP;
    }

    /** {@code new Random(<ldc long>).nextInt()} written as one expression (Skidfuscator's class seed). */
    static Long randomSeed(MethodInsnNode nextInt) {
        var init = previous(nextInt);
        if (!(init instanceof MethodInsnNode ctor) || !ctor.owner.equals("java/util/Random") || !ctor.name.equals("<init>") || !ctor.desc.equals("(J)V")) return null;
        var ldc = previous(init);
        var dup = previous(ldc);
        var alloc = previous(dup);
        if (ldc instanceof LdcInsnNode constant && constant.cst instanceof Long seed && dup != null && dup.getOpcode() == DUP
            && alloc instanceof TypeInsnNode type && type.getOpcode() == NEW && type.desc.equals("java/util/Random")) return seed;
        return null;
    }

    static AbstractInsnNode next(AbstractInsnNode insn) {
        if (insn == null) return null;
        for (var node = insn.getNext(); node != null; node = node.getNext()) if (node.getOpcode() >= 0) return node;
        return null;
    }

    static AbstractInsnNode previous(AbstractInsnNode insn) {
        if (insn == null) return null;
        for (var node = insn.getPrevious(); node != null; node = node.getPrevious()) if (node.getOpcode() >= 0) return node;
        return null;
    }

    private SV evaluate(MethodInsnNode call, List<? extends SV> values) {
        var types = Type.getArgumentTypes(call.desc);
        var choices = new ArrayList<Object[]>();
        int varying = -1; long product = 1;
        for (int i = 0; i < values.size(); i++) {
            SV value = values.get(i);
            Object[] options;
            if (value.kind == SV.Kind.INT && value.ints != null) {
                options = Arrays.stream(value.ints).boxed().toArray();
                if (options.length > 1) varying = varying == -1 ? i : -2;
            } else if (value.kind == SV.Kind.LONG && value.constant instanceof Long constant) {
                options = new Object[] {constant};
            } else return null;
            product *= options.length;
            if (product > MAX_PRODUCT) return null;
            choices.add(options);
        }
        var out = new TreeSet<Integer>();
        var args = new Object[values.size()];
        if (!enumerate(call, choices, 0, args, out)) return null;
        SV.Prov prov = null;
        // Provenance through the call: exactly one argument varies (or is the only one with provenance) and the others are fixed.
        int provArg = -1;
        for (int i = 0; i < values.size(); i++) if (values.get(i).prov != null) provArg = provArg == -1 ? i : -2;
        if (provArg >= 0 && (varying == -1 || varying == provArg)) {
            boolean fixed = true;
            for (int i = 0; i < values.size(); i++) if (i != provArg && choices.get(i).length != 1) fixed = false;
            if (fixed) {
                var parent = values.get(provArg).prov;
                Object[] base = new Object[values.size()];
                for (int i = 0; i < values.size(); i++) base[i] = choices.get(i)[0];
                int index = provArg;
                // Canonical per call, parent and fixed arguments (not the varying one), so a growing set keeps its provenance.
                var key = new ArrayList<Object>(Arrays.asList(base)); key.set(index, "varying");
                prov = prov(List.of(call, parent, key), parent.local(), s -> {
                    Integer p = parent.fn().apply(s);
                    if (p == null) return null;
                    Object[] actual = base.clone(); actual[index] = p;
                    Object r = call(call, actual);
                    return r instanceof Integer value ? value : null;
                });
            }
        }
        boolean tainted = values.stream().anyMatch(v -> v.tainted)
            && values.stream().allMatch(v -> v.tainted || v.literal || v.kind == SV.Kind.LONG && v.constant != null);
        return SV.ints(SV.sorted(out), prov, tainted);
    }

    private boolean enumerate(MethodInsnNode call, List<Object[]> choices, int i, Object[] args, Set<Integer> out) {
        if (i == choices.size()) {
            Object r = call(call, args.clone());
            if (!(r instanceof Integer value)) return false;
            out.add(value);
            return true;
        }
        for (Object option : choices.get(i)) { args[i] = option; if (!enumerate(call, choices, i + 1, args, out)) return false; }
        return true;
    }

    private Object call(MethodInsnNode call, Object[] args) {
        var key = new ArrayList<Object>(); key.add(call.owner); key.add(call.name); key.add(call.desc); key.addAll(Arrays.asList(args));
        return calls.computeIfAbsent(key, k -> pure.evaluate(call.owner, call.name, call.desc, args)).orElse(null);
    }

    @Override public void returnOperation(AbstractInsnNode insn, SV value, SV expected) { }

    @Override public SV merge(SV a, SV b) { return a.merge(b); }

    @Override public SV newExceptionValue(TryCatchBlockNode block, org.objectweb.asm.tree.analysis.Frame<SV> frame, Type type) {
        return SV.ref(type.getInternalName(), null);
    }
}
