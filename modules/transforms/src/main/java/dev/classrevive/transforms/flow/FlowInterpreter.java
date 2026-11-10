package dev.classrevive.transforms.flow;

import dev.classrevive.transforms.skid.Interpret;
import dev.classrevive.transforms.skid.PureEvaluator;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.Interpreter;
import java.util.*;

/**
 * Phase 2DL: transfer functions over {@link FV}. Int arithmetic is evaluated on intervals and parities (exactly on
 * constants, with {@link Interpret}'s Java semantics), static fields {@link JarFacts} proves constant read as their value,
 * and static calls with constant primitive arguments are evaluated by {@link PureEvaluator} when their body is pure
 * (the JDK's allowlisted classes, or bytecode that only computes). Nothing is loaded, initialized or run.
 */
final class FlowInterpreter extends Interpreter<FV> implements Opcodes {
    private final JarFacts facts;
    private final PureEvaluator pure;
    private final Map<List<Object>,Optional<Object>> calls = new HashMap<>();

    FlowInterpreter(JarFacts facts, PureEvaluator pure) { super(ASM9); this.facts = facts; this.pure = pure; }

    @Override public FV newValue(Type type) {
        if (type == null) return FV.UNINIT;
        return switch (type.getSort()) {
            case Type.VOID -> null;
            case Type.BOOLEAN, Type.CHAR, Type.BYTE, Type.SHORT, Type.INT -> FV.INT_TOP;
            case Type.FLOAT -> FV.FLOAT;
            case Type.LONG -> FV.LONG_TOP;
            case Type.DOUBLE -> FV.DOUBLE;
            default -> FV.REF_TOP;
        };
    }
    /** A fresh value of {@code type} produced by {@code producer}. */
    FV fresh(Type type, Object producer) {
        return switch (type.getSort()) {
            case Type.BOOLEAN, Type.CHAR, Type.BYTE, Type.SHORT, Type.INT -> FV.unknownInt(producer, Integer.MIN_VALUE, Integer.MAX_VALUE);
            case Type.OBJECT, Type.ARRAY -> FV.ref(FV.UNKNOWN, producer, null);
            default -> newValue(type);
        };
    }

    private FV newOperation0(AbstractInsnNode insn) {
        int op = insn.getOpcode();
        switch (op) {
            case ACONST_NULL: return FV.NULL_REF;
            case ICONST_M1, ICONST_0, ICONST_1, ICONST_2, ICONST_3, ICONST_4, ICONST_5: return FV.constant(op - ICONST_0);
            case LCONST_0, LCONST_1: return FV.longConstant(op - LCONST_0);
            case FCONST_0, FCONST_1, FCONST_2: return FV.FLOAT;
            case DCONST_0, DCONST_1: return FV.DOUBLE;
            case BIPUSH, SIPUSH: return FV.constant(((IntInsnNode) insn).operand);
            case LDC: {
                Object cst = ((LdcInsnNode) insn).cst;
                if (cst instanceof Integer value) return FV.constant(value);
                if (cst instanceof Long value) return FV.longConstant(value);
                if (cst instanceof Float) return FV.FLOAT;
                if (cst instanceof Double) return FV.DOUBLE;
                if (cst instanceof String text) return FV.ref(FV.NONNULL, insn, text);
                if (cst instanceof org.objectweb.asm.ConstantDynamic dynamic) return fresh(Type.getType(dynamic.getDescriptor()), insn);
                return FV.ref(FV.NONNULL, insn, null);
            }
            case JSR: return FV.RET;
            case GETSTATIC: {
                var field = (FieldInsnNode) insn;
                Object known = facts.constant(field.owner, field.name, field.desc);
                if (known instanceof Integer value) return FV.constant(value);
                if (known instanceof Long value) return FV.longConstant(value);
                return fresh(Type.getType(field.desc), insn);
            }
            case NEW: return FV.ref(FV.NONNULL, insn, null);
            default: throw new IllegalStateException("newOperation " + op);
        }
    }



    private FV unaryOperation0(AbstractInsnNode insn, FV value) {
        int op = insn.getOpcode();
        switch (op) {
            case INEG: {
                if (value.kind != FV.Kind.INT) return FV.INT_TOP;
                if (value.isConstant()) return FV.constant(-value.value());
                long lo = value.lo == Integer.MIN_VALUE ? Integer.MIN_VALUE : -(long) value.hi, hi = value.lo == Integer.MIN_VALUE ? Integer.MAX_VALUE : -(long) value.lo;
                return FV.range(lo, hi, value.parity(), insn);
            }
            case I2B: return narrow(insn, value, Byte.MIN_VALUE, Byte.MAX_VALUE, v -> (int) (byte) v);
            case I2C: return narrow(insn, value, Character.MIN_VALUE, Character.MAX_VALUE, v -> (int) (char) v);
            case I2S: return narrow(insn, value, Short.MIN_VALUE, Short.MAX_VALUE, v -> (int) (short) v);
            case IINC: {
                int incr = ((IincInsnNode) insn).incr;
                if (value.kind != FV.Kind.INT) return FV.INT_TOP;
                return add(insn, value, FV.constant(incr), false);
            }
            case L2I: return value.lconst != null ? FV.constant((int) (long) value.lconst) : FV.unknownInt(insn, Integer.MIN_VALUE, Integer.MAX_VALUE);
            case F2I, D2I: return FV.unknownInt(insn, Integer.MIN_VALUE, Integer.MAX_VALUE);
            case I2L: return value.isConstant() ? FV.longConstant(value.value()) : FV.LONG_TOP;
            case F2L, D2L: return FV.LONG_TOP;
            case LNEG: return value.lconst != null ? FV.longConstant(-value.lconst) : FV.LONG_TOP;
            case I2F, L2F, D2F, FNEG: return FV.FLOAT;
            case I2D, L2D, F2D, DNEG: return FV.DOUBLE;
            case GETFIELD: return fresh(Type.getType(((FieldInsnNode) insn).desc), insn);
            case NEWARRAY, ANEWARRAY: return FV.ref(FV.NONNULL, insn, null);
            case ARRAYLENGTH: return FV.unknownInt(insn, 0, Integer.MAX_VALUE);
            case INSTANCEOF: return value.nullness == FV.NULL ? FV.constant(0) : FV.unknownInt(insn, 0, 1);
            case CHECKCAST: return value;
            default: return null; // branches, switches, returns, PUTSTATIC, ATHROW, monitors
        }
    }

    private static FV narrow(AbstractInsnNode insn, FV value, int min, int max, java.util.function.IntUnaryOperator cast) {
        if (value.kind != FV.Kind.INT) return FV.unknownInt(insn, min, max);
        if (value.isConstant()) return FV.constant(cast.applyAsInt(value.value()));
        if (value.lo >= min && value.hi <= max) return value;
        return FV.range(min, max, value.parity(), insn); // the low bit survives the cast
    }

    private FV binaryOperation0(AbstractInsnNode insn, FV a, FV b) {
        int op = insn.getOpcode();
        switch (op) {
            case IADD: return add(insn, a, b, false);
            case ISUB: return add(insn, a, b, true);
            case IMUL, IDIV, IREM, ISHL, ISHR, IUSHR, IAND, IOR, IXOR: return arithmetic(insn, op, a, b);
            case LADD, LSUB, LMUL, LDIV, LREM, LSHL, LSHR, LUSHR, LAND, LOR, LXOR: {
                if (a.lconst == null || b.kind == FV.Kind.LONG && b.lconst == null || b.kind == FV.Kind.INT && !b.isConstant()) return FV.LONG_TOP;
                long x = a.lconst, y = b.kind == FV.Kind.LONG ? b.lconst : b.value();
                Long r = switch (op) {
                    case LADD -> x + y; case LSUB -> x - y; case LMUL -> x * y;
                    case LDIV -> y == 0 ? null : x / y; case LREM -> y == 0 ? null : x % y;
                    case LSHL -> x << y; case LSHR -> x >> y; case LUSHR -> x >>> y;
                    case LAND -> x & y; case LOR -> x | y; default -> x ^ y;
                };
                return r == null ? FV.LONG_TOP : FV.longConstant(r);
            }
            case LCMP: {
                if (a.lconst != null && b.lconst != null) return FV.constant(Long.compare(a.lconst, b.lconst));
                return FV.unknownInt(insn, -1, 1);
            }
            case FCMPL, FCMPG, DCMPL, DCMPG: return FV.unknownInt(insn, -1, 1);
            case FADD, FSUB, FMUL, FDIV, FREM: return FV.FLOAT;
            case DADD, DSUB, DMUL, DDIV, DREM: return FV.DOUBLE;
            case IALOAD: return FV.unknownInt(insn, Integer.MIN_VALUE, Integer.MAX_VALUE);
            case BALOAD: return FV.unknownInt(insn, Byte.MIN_VALUE, Byte.MAX_VALUE);
            case CALOAD: return FV.unknownInt(insn, Character.MIN_VALUE, Character.MAX_VALUE);
            case SALOAD: return FV.unknownInt(insn, Short.MIN_VALUE, Short.MAX_VALUE);
            case LALOAD: return FV.LONG_TOP;
            case FALOAD: return FV.FLOAT;
            case DALOAD: return FV.DOUBLE;
            case AALOAD: return FV.ref(FV.UNKNOWN, insn, null);
            default: return null; // IF_ICMPxx, IF_ACMPxx, PUTFIELD
        }
    }

    /** {@code a + b} or {@code a - b}. */
    private static FV add(AbstractInsnNode insn, FV a, FV b, boolean subtract) {
        if (a.kind != FV.Kind.INT || b.kind != FV.Kind.INT) return FV.unknownInt(insn, Integer.MIN_VALUE, Integer.MAX_VALUE);
        if (a.isConstant() && b.isConstant()) return FV.constant(subtract ? a.value() - b.value() : a.value() + b.value());
        if (subtract && a.id != null && a.id == b.id) return FV.constant(0);
        long lo = subtract ? (long) a.lo - b.hi : (long) a.lo + b.lo, hi = subtract ? (long) a.hi - b.lo : (long) a.hi + b.hi;
        var parity = FV.Parity.add(a.parity(), b.parity());
        if (lo < Integer.MIN_VALUE || hi > Integer.MAX_VALUE) { lo = Integer.MIN_VALUE; hi = Integer.MAX_VALUE; } // wraps: parity still holds
        return FV.range(lo, hi, parity, insn);
    }

    private static FV arithmetic(AbstractInsnNode insn, int op, FV a, FV b) {
        if (a.kind != FV.Kind.INT || b.kind != FV.Kind.INT) return FV.unknownInt(insn, Integer.MIN_VALUE, Integer.MAX_VALUE);
        if (a.isConstant() && b.isConstant()) {
            Integer r = Interpret.binaryInt(op, a.value(), b.value());
            return r == null ? FV.unknownInt(insn, Integer.MIN_VALUE, Integer.MAX_VALUE) : FV.constant(r);
        }
        boolean same = a.id != null && a.id == b.id;
        long min = Integer.MIN_VALUE, max = Integer.MAX_VALUE;
        switch (op) {
            case IMUL: {
                long p1 = (long) a.lo * b.lo, p2 = (long) a.lo * b.hi, p3 = (long) a.hi * b.lo, p4 = (long) a.hi * b.hi;
                long lo = Math.min(Math.min(p1, p2), Math.min(p3, p4)), hi = Math.max(Math.max(p1, p2), Math.max(p3, p4));
                var parity = FV.Parity.mul(a.parity(), b.parity());
                if (lo < min || hi > max) { lo = min; hi = max; }
                return FV.range(lo, hi, parity, insn);
            }
            case IAND: {
                if (same) return a;
                FV.Parity parity = b.isConstant() ? ((b.value() & 1) == 0 ? FV.Parity.bit(0) : a.parity())
                    : a.isConstant() ? ((a.value() & 1) == 0 ? FV.Parity.bit(0) : b.parity()) : FV.Parity.TOP;
                long lo = min, hi = max;
                if (a.lo >= 0 && b.lo >= 0) { lo = 0; hi = Math.min(a.hi, b.hi); }
                else if (a.lo >= 0) { lo = 0; hi = a.hi; }
                else if (b.lo >= 0) { lo = 0; hi = b.hi; }
                return FV.range(lo, hi, parity, insn);
            }
            case IOR, IXOR: {
                if (same) return op == IOR ? a : FV.constant(0);
                FV.Parity parity;
                if (op == IXOR) parity = FV.Parity.add(a.parity(), b.parity());
                else parity = b.isConstant() ? ((b.value() & 1) == 1 ? FV.Parity.bit(1) : a.parity())
                    : a.isConstant() ? ((a.value() & 1) == 1 ? FV.Parity.bit(1) : b.parity()) : FV.Parity.TOP;
                long lo = min, hi = max;
                if (a.lo >= 0 && b.lo >= 0) { lo = 0; long top = Math.max(a.hi, b.hi); hi = top == 0 ? 0 : (Long.highestOneBit(top) << 1) - 1; }
                return FV.range(lo, Math.min(hi, max), parity, insn);
            }
            case ISHL: {
                if (!b.isConstant()) return FV.unknownInt(insn, min, max);
                int k = b.value() & 31;
                if (k == 0) return a;
                long lo = (long) a.lo << k, hi = (long) a.hi << k;
                if (lo < min || hi > max) { lo = min; hi = max; }
                return FV.range(lo, hi, FV.Parity.bit(0), insn);
            }
            case ISHR, IUSHR: {
                if (!b.isConstant()) return FV.unknownInt(insn, min, max);
                int k = b.value() & 31;
                if (k == 0) return a;
                if (op == ISHR || a.lo >= 0) return FV.range(op == ISHR ? a.lo >> k : a.lo >>> k, op == ISHR ? a.hi >> k : a.hi >>> k, FV.Parity.TOP, insn);
                return FV.range(0, (-1) >>> k, FV.Parity.TOP, insn);
            }
            case IDIV: {
                if (!b.isConstant() || b.value() == 0) return FV.unknownInt(insn, min, max);
                long c = b.value(), x = (long) a.lo / c, y = (long) a.hi / c;
                return FV.range(Math.min(x, y), Math.max(x, y), FV.Parity.TOP, insn);
            }
            case IREM: {
                if (!b.excludesZero()) return FV.unknownInt(insn, min, max);
                long m = Math.max(Math.abs((long) b.lo), Math.abs((long) b.hi)) - 1;
                long lo = a.lo >= 0 ? 0 : -m, hi = a.hi <= 0 ? 0 : m;
                if (a.lo >= 0) hi = Math.min(hi, a.hi);
                if (a.hi <= 0) lo = Math.max(lo, a.lo);
                // x = q * c + r: with an even divisor the remainder keeps the dividend's parity.
                var parity = b.isConstant() && (b.value() & 1) == 0 ? a.parity() : FV.Parity.TOP;
                return FV.range(lo, hi, parity, insn);
            }
            default: return FV.unknownInt(insn, min, max);
        }
    }


    // ------------------------------------------------------------------------------------------------- evidence flags

    @Override public FV newOperation(AbstractInsnNode insn) {
        FV value = newOperation0(insn);
        int op = insn.getOpcode();
        if (op == GETSTATIC) return value.isConstant() || value.kind == FV.Kind.LONG && value.lconst != null ? value.flags(false, true) : value;
        boolean literal = op >= ACONST_NULL && op <= SIPUSH || insn instanceof LdcInsnNode ldc && (ldc.cst instanceof Number || ldc.cst instanceof String);
        return literal ? value.flags(true, false) : value;
    }

    @Override public FV copyOperation(AbstractInsnNode insn, FV value) {
        int op = insn.getOpcode();
        return op >= ILOAD && op <= ALOAD || op >= ISTORE && op <= ASTORE ? value.flags(false, value.opaque) : value;
    }

    @Override public FV unaryOperation(AbstractInsnNode insn, FV value) {
        FV result = unaryOperation0(insn, value);
        int op = insn.getOpcode();
        boolean computes = op == INEG || op == LNEG || op == I2B || op == I2C || op == I2S || op == L2I || op == I2L;
        return result != null && computes && value.folded() ? result.flags(false, true) : result;
    }

    @Override public FV binaryOperation(AbstractInsnNode insn, FV a, FV b) {
        FV result = binaryOperation0(insn, a, b);
        int op = insn.getOpcode();
        if (result == null || !(op >= IADD && op <= LXOR || op == LCMP)) return result;
        // javac folds an operation on two constants, and never computes x - x or x ^ x.
        boolean identity = (op == ISUB || op == IXOR) && a.id != null && a.id == b.id;
        return a.folded() && b.folded() || identity ? result.flags(false, true) : result;
    }

    @Override public FV naryOperation(AbstractInsnNode insn, List<? extends FV> values) {
        FV result = naryOperation0(insn, values);
        if (result == null || !(result.isConstant() || result.kind == FV.Kind.LONG && result.lconst != null)) return result;
        boolean folded = true;
        for (var value : values) folded &= value.folded();
        return folded ? result.flags(false, true) : result;
    }

    @Override public FV ternaryOperation(AbstractInsnNode insn, FV a, FV b, FV c) { return null; }

    private FV naryOperation0(AbstractInsnNode insn, List<? extends FV> values) {
        if (insn instanceof MethodInsnNode call) {
            Type result = Type.getReturnType(call.desc);
            if (call.getOpcode() == INVOKESTATIC && PureEvaluator.primitiveDescriptor(call.desc) && result.getSort() <= Type.LONG) {
                Object[] args = new Object[values.size()];
                boolean constant = true;
                for (int i = 0; i < args.length && constant; i++) {
                    var value = values.get(i);
                    if (value.isConstant()) args[i] = value.value();
                    else if (value.kind == FV.Kind.LONG && value.lconst != null) args[i] = value.lconst;
                    else constant = false;
                }
                if (constant && pure.pureBody(call.owner, call.name, call.desc)) {
                    var key = new ArrayList<Object>(List.of(call.owner, call.name, call.desc)); key.addAll(Arrays.asList(args));
                    var evaluated = calls.computeIfAbsent(key, k -> pure.evaluate(call.owner, call.name, call.desc, args));
                    if (evaluated.isPresent()) {
                        if (evaluated.get() instanceof Integer value && result.getSort() <= Type.INT) return FV.constant(value);
                        if (evaluated.get() instanceof Long value && result.getSort() == Type.LONG) return FV.longConstant(value);
                    }
                }
            }
            // java.lang.String is final: its length, charAt and hashCode on a constant are fixed.
            if (call.getOpcode() == INVOKEVIRTUAL && call.owner.equals("java/lang/String") && values.getFirst().string != null) {
                String text = values.getFirst().string;
                if (call.name.equals("length") && call.desc.equals("()I")) return FV.constant(text.length());
                if (call.name.equals("hashCode") && call.desc.equals("()I")) return FV.constant(text.hashCode());
                if (call.name.equals("charAt") && call.desc.equals("(I)C") && values.get(1).isConstant()
                    && values.get(1).value() >= 0 && values.get(1).value() < text.length()) return FV.constant(text.charAt(values.get(1).value()));
            }
            if (call.getOpcode() == INVOKEVIRTUAL && call.owner.equals("java/lang/String") && call.name.equals("length") && call.desc.equals("()I"))
                return FV.unknownInt(insn, 0, Integer.MAX_VALUE);
            return result.getSort() == Type.VOID ? null : fresh(result, insn);
        }
        if (insn instanceof InvokeDynamicInsnNode indy) {
            Type result = Type.getReturnType(indy.desc);
            return result.getSort() == Type.VOID ? null : fresh(result, insn);
        }
        if (insn instanceof MultiANewArrayInsnNode) return FV.ref(FV.NONNULL, insn, null);
        return FV.REF_TOP;
    }

    @Override public void returnOperation(AbstractInsnNode insn, FV value, FV expected) { }

    @Override public FV merge(FV a, FV b) { return a.merge(b); }

    // ------------------------------------------------------------------------------------------------- decisions

    /** The outcome of an int comparison ({@code IFEQ..IFLE} codes), or null when both are possible. */
    static Boolean compare(int cmp, FV a, FV b) { return compare(cmp, a, b, false); }
    /** With {@code intervalsOnly}, value numbers and parities are not used (a decision they alone make is javac-proof evidence). */
    static Boolean compare(int cmp, FV a, FV b, boolean intervalsOnly) {
        if (a.kind != FV.Kind.INT || b.kind != FV.Kind.INT) return null;
        if (a.isConstant() && b.isConstant()) return Interpret.compare(cmp, a.value(), b.value());
        if (!intervalsOnly && a.id != null && a.id == b.id) return cmp == IFEQ || cmp == IFGE || cmp == IFLE;
        // Parity of a - b: odd means a != b.
        var difference = FV.Parity.add(a.parity(), b.parity());
        boolean differ = a.hi < b.lo || b.hi < a.lo || !intervalsOnly && difference.constant() && difference.bit() == 1;
        return switch (cmp) {
            case IFEQ -> differ ? Boolean.FALSE : null;
            case IFNE -> differ ? Boolean.TRUE : null;
            case IFLT -> a.hi < b.lo ? Boolean.TRUE : a.lo >= b.hi ? Boolean.FALSE : null;
            case IFGE -> a.lo >= b.hi ? Boolean.TRUE : a.hi < b.lo ? Boolean.FALSE : null;
            case IFGT -> a.lo > b.hi ? Boolean.TRUE : a.hi <= b.lo ? Boolean.FALSE : null;
            case IFLE -> a.hi <= b.lo ? Boolean.TRUE : a.lo > b.hi ? Boolean.FALSE : null;
            default -> null;
        };
    }

    /** {@code a}'s interval on the edge where {@code a cmp b} is {@code taken}; null when no value of {@code a} takes it. */
    static long[] narrowed(int cmp, FV a, FV b, boolean taken) {
        long lo = a.lo, hi = a.hi;
        if (!taken) cmp = negate(cmp);
        switch (cmp) {
            case IFEQ -> { lo = Math.max(lo, b.lo); hi = Math.min(hi, b.hi); }
            case IFNE -> { if (b.isConstant()) { if (lo == b.value()) lo++; if (hi == b.value()) hi--; } }
            case IFLT -> hi = Math.min(hi, (long) b.hi - 1);
            case IFGE -> lo = Math.max(lo, b.lo);
            case IFGT -> lo = Math.max(lo, (long) b.lo + 1);
            case IFLE -> hi = Math.min(hi, b.hi);
            default -> { }
        }
        return lo > hi ? null : new long[] {lo, hi};
    }
    static int negate(int cmp) {
        return switch (cmp) { case IFEQ -> IFNE; case IFNE -> IFEQ; case IFLT -> IFGE; case IFGE -> IFLT; case IFGT -> IFLE; default -> IFGT; };
    }
    /** The comparison seen from the other operand ({@code a < b} is {@code b > a}). */
    static int swap(int cmp) {
        return switch (cmp) { case IFLT -> IFGT; case IFGT -> IFLT; case IFLE -> IFGE; case IFGE -> IFLE; default -> cmp; };
    }
}
