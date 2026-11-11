package dev.classrevive.transforms.skid;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.*;
import java.util.*;

/**
 * Phase 2DM: Zelix KlassMaster 27's keys. Every method it protects holds a {@code long} key: a constant stored in a
 * local ({@code ldc2_w K; lstore}), a class key field its initializer sets, a parameter its callers compute from their own
 * key, or, for a method whose parameters it packed into one {@code Object[]}, one element of that array as a
 * {@code Long} (callers box it with {@code Long.valueOf} or {@code new Long}). Keys are split into ints and passed on to
 * decryptors and to the reflection helper. This is a constant propagation of ints and longs over one method: constants,
 * int and long arithmetic, conversions, locals, and the key facts known for the method's parameters, its packed array
 * and the class keys. Nothing is executed.
 */
final class ZkmKeys implements Opcodes {
    /** A value: an Integer or Long constant, a packed-array marker below, or unknown ({@code null}). */
    record KV(int size, Object value) implements Value {
        @Override public int getSize() { return size; }
    }
    /** The method's packed {@code Object[]} parameter, and one of its elements. */
    static final Object ARGS = new Object();
    record Element(int index) {}
    /** An {@code Object[]} created by one {@code anewarray}, a key boxed by {@code valueOf}, a {@code new Long} (or other box) before its constructor. */
    record Packed(AbstractInsnNode creation) {}
    record Boxed(Object value) {}
    record NewLong(AbstractInsnNode creation) {}
    /** The boxes a key travels in (whole in a {@code Long}, split in the others), with their unboxing method and primitive. */
    private static final Map<String,String> BOXES = Map.of("java/lang/Long", "longValue:J", "java/lang/Integer", "intValue:I",
        "java/lang/Character", "charValue:C", "java/lang/Short", "shortValue:S", "java/lang/Byte", "byteValue:B");
    /** The frames, and the keys stored in each array a caller packs: its {@code anewarray} to index and key. */
    record Result(Frame<KV>[] frames, Map<AbstractInsnNode,Map<Integer,Object>> packedKeys) {}
    /** Why the last {@link #analyze} on this thread returned null. */
    static final ThreadLocal<String> FAILURE = new ThreadLocal<>();

    private static KV unknown(Type type) { return type == null ? new KV(1, null) : new KV(type.getSize(), null); }
    private static final KV INT = new KV(1, null), LONG = new KV(2, null), REF = new KV(1, null);

    /**
     * Frames of {@code method}: {@code parameters} maps a local slot to its known constant; {@code packed} the keys known
     * at indices of the packed {@code Object[]} parameter; {@code statics} ({@code owner.name:desc}) the class keys.
     */
    static Result analyze(String owner, MethodNode method, Map<Integer,Object> parameters, Map<Integer,Object> packed, Map<String,Object> statics) {
        var packedKeys = new IdentityHashMap<AbstractInsnNode,Map<Integer,Object>>();
        var conflicting = new IdentityHashMap<AbstractInsnNode,Set<Integer>>();
        var newLongs = new IdentityHashMap<AbstractInsnNode,Object>();
        var interpreter = new Interpreter<KV>(ASM9) {
            @Override public KV newValue(Type type) {
                if (type == null) return new KV(1, null);
                if (type.getSort() == Type.VOID) return null;
                return unknown(type);
            }
            @Override public KV newParameterValue(boolean isInstanceMethod, int local, Type type) {
                if (parameters.containsKey(local)) return new KV(type.getSize(), parameters.get(local));
                if (type.getDescriptor().equals("[Ljava/lang/Object;") && !packed.isEmpty()) return new KV(1, ARGS);
                return unknown(type);
            }
            @Override public KV newOperation(AbstractInsnNode insn) {
                int op = insn.getOpcode();
                if (op >= ICONST_M1 && op <= ICONST_5) return new KV(1, op - ICONST_0);
                return switch (op) {
                    case LCONST_0, LCONST_1 -> new KV(2, (long) (op - LCONST_0));
                    case FCONST_0, FCONST_1, FCONST_2 -> INT;
                    case DCONST_0, DCONST_1 -> LONG;
                    case BIPUSH, SIPUSH -> new KV(1, ((IntInsnNode) insn).operand);
                    case LDC -> {
                        Object cst = ((LdcInsnNode) insn).cst;
                        if (cst instanceof Integer i) yield new KV(1, i);
                        if (cst instanceof Long l) yield new KV(2, l);
                        if (cst instanceof Double) yield LONG;
                        yield REF;
                    }
                    case GETSTATIC -> {
                        var field = (FieldInsnNode) insn;
                        Object known = statics.get(field.owner + "." + field.name + ":" + field.desc);
                        yield known != null ? new KV(Type.getType(field.desc).getSize(), known) : unknown(Type.getType(field.desc));
                    }
                    case NEW -> BOXES.containsKey(((TypeInsnNode) insn).desc) ? new KV(1, new NewLong(insn)) : REF;
                    default -> REF; // ACONST_NULL, JSR
                };
            }
            @Override public KV copyOperation(AbstractInsnNode insn, KV value) { return value; }
            @Override public KV unaryOperation(AbstractInsnNode insn, KV value) {
                Object v = value.value();
                return switch (insn.getOpcode()) {
                    case INEG -> v instanceof Integer i ? new KV(1, -i) : INT;
                    case IINC -> v instanceof Integer i ? new KV(1, i + ((IincInsnNode) insn).incr) : INT;
                    case I2B -> v instanceof Integer i ? new KV(1, (int) (byte) (int) i) : INT;
                    case I2C -> v instanceof Integer i ? new KV(1, (int) (char) (int) i) : INT;
                    case I2S -> v instanceof Integer i ? new KV(1, (int) (short) (int) i) : INT;
                    case I2L -> v instanceof Integer i ? new KV(2, (long) i) : LONG;
                    case L2I -> v instanceof Long l ? new KV(1, (int) (long) l) : INT;
                    case LNEG -> v instanceof Long l ? new KV(2, -l) : LONG;
                    case F2I, D2I, L2F, D2F, I2F, FNEG, ARRAYLENGTH, INSTANCEOF -> INT;
                    case I2D, L2D, F2D, F2L, D2L, DNEG -> LONG;
                    case GETFIELD -> unknown(Type.getType(((FieldInsnNode) insn).desc));
                    case CHECKCAST -> value;
                    case ANEWARRAY -> ((TypeInsnNode) insn).desc.equals("java/lang/Object") ? new KV(1, new Packed(insn)) : REF;
                    default -> REF; // NEWARRAY; branches, switches, returns and stores return no value
                };
            }
            @Override public KV binaryOperation(AbstractInsnNode insn, KV a, KV b) {
                int op = insn.getOpcode();
                Object x = a.value(), y = b.value();
                if (op >= IADD && op <= DREM || op >= ISHL && op <= LXOR) {
                    Type type = switch (op) {
                        case IADD, ISUB, IMUL, IDIV, IREM, ISHL, ISHR, IUSHR, IAND, IOR, IXOR -> Type.INT_TYPE;
                        case LADD, LSUB, LMUL, LDIV, LREM, LSHL, LSHR, LUSHR, LAND, LOR, LXOR -> Type.LONG_TYPE;
                        case FADD, FSUB, FMUL, FDIV, FREM -> Type.FLOAT_TYPE;
                        default -> Type.DOUBLE_TYPE;
                    };
                    if (type == Type.INT_TYPE && x instanceof Integer i && y instanceof Integer j) {
                        Integer r = Interpret.binaryInt(op, i, j);
                        return r == null ? INT : new KV(1, r);
                    }
                    if (type == Type.LONG_TYPE && x instanceof Long i) {
                        if ((op == LSHL || op == LSHR || op == LUSHR) && y instanceof Integer s)
                            return new KV(2, op == LSHL ? i << s : op == LSHR ? i >> s : i >>> s);
                        if (y instanceof Long j) {
                            Long r = switch (op) {
                                case LADD -> i + j; case LSUB -> i - j; case LMUL -> i * j; case LAND -> i & j; case LOR -> i | j; case LXOR -> i ^ j;
                                case LDIV -> j == 0 ? null : i / j; case LREM -> j == 0 ? null : i % j; default -> null;
                            };
                            return r == null ? LONG : new KV(2, r);
                        }
                    }
                    return unknown(type);
                }
                if (op == LCMP) return x instanceof Long i && y instanceof Long j ? new KV(1, Long.compare(i, j)) : INT;
                if (op == FCMPL || op == FCMPG || op == DCMPL || op == DCMPG) return INT;
                if (op == LALOAD || op == DALOAD) return LONG;
                if (op == AALOAD) return x == ARGS && y instanceof Integer index ? new KV(1, new Element(index)) : REF;
                if (op >= IALOAD && op <= SALOAD) return INT;
                return null; // IF_ICMPxx, IF_ACMPxx, PUTFIELD
            }
            @Override public KV ternaryOperation(AbstractInsnNode insn, KV a, KV b, KV c) {
                // array[k] = Long.valueOf(key) or new Long(key): a key a packed call passes on.
                if (insn.getOpcode() == AASTORE && a.value() instanceof Packed array && b.value() instanceof Integer index) {
                    Object key = c.value() instanceof Boxed boxed ? boxed.value()
                        : c.value() instanceof NewLong created ? newLongs.get(created.creation()) : null;
                    var keys = packedKeys.computeIfAbsent(array.creation(), k -> new HashMap<>());
                    if (key == null) conflicting.computeIfAbsent(array.creation(), k -> new HashSet<>()).add(index);
                    else {
                        Object previous = keys.putIfAbsent(index, key);
                        if (previous != null && !previous.equals(key)) conflicting.computeIfAbsent(array.creation(), k -> new HashSet<>()).add(index);
                    }
                }
                return null;
            }
            @Override public KV naryOperation(AbstractInsnNode insn, List<? extends KV> values) {
                if (insn instanceof MethodInsnNode call) {
                    if (BOXES.containsKey(call.owner)) {
                        String[] unbox = BOXES.get(call.owner).split(":");
                        // The packed key: ((Long) args[k]).longValue(), or a part of it in another box.
                        if (call.name.equals(unbox[0]) && call.desc.equals("()" + unbox[1]) && values.getFirst().value() instanceof Element element && packed.containsKey(element.index()))
                            return new KV(unbox[1].equals("J") ? 2 : 1, packed.get(element.index()));
                        if (call.name.equals("valueOf") && call.desc.equals("(" + unbox[1] + ")L" + call.owner + ";") && (values.getFirst().value() instanceof Long || values.getFirst().value() instanceof Integer))
                            return new KV(1, new Boxed(values.getFirst().value()));
                        if (call.name.equals("<init>") && call.desc.equals("(" + unbox[1] + ")V") && values.getFirst().value() instanceof NewLong created) {
                            Object key = values.get(1).value();
                            if (key instanceof Long || key instanceof Integer) newLongs.put(created.creation(), key);
                            else newLongs.remove(created.creation());
                        }
                    }
                    return newValue(Type.getReturnType(call.desc));
                }
                if (insn instanceof InvokeDynamicInsnNode indy) return newValue(Type.getReturnType(indy.desc));
                return REF; // MULTIANEWARRAY
            }
            @Override public void returnOperation(AbstractInsnNode insn, KV value, KV expected) { }
            @Override public KV merge(KV a, KV b) {
                if (Objects.equals(a.value(), b.value())) return a;
                return new KV(Math.min(a.size(), b.size()), null);
            }
        };
        try {
            var frames = new Analyzer<>(interpreter).analyze(owner, method);
            conflicting.forEach((creation, indices) -> { var keys = packedKeys.get(creation); if (keys != null) indices.forEach(keys::remove); });
            return new Result(frames, packedKeys);
        } catch (AnalyzerException malformed) {
            FAILURE.set(String.valueOf(malformed.getMessage()));
            return null;
        }
    }

    /** The constant arguments of a call from the frame before it, in order; null when any is unknown. */
    static Object[] arguments(Frame<KV> frame, String desc) {
        var types = Type.getArgumentTypes(desc);
        var args = new Object[types.length];
        if (frame == null || frame.getStackSize() < types.length) return null;
        for (int k = 0; k < types.length; k++) {
            Object value = frame.getStack(frame.getStackSize() - types.length + k).value();
            if (!(value instanceof Integer) && !(value instanceof Long)) return null;
            int sort = types[k].getSort();
            if (sort == Type.LONG && !(value instanceof Long) || sort != Type.LONG && sort <= Type.INT && !(value instanceof Integer)) return null;
            if (sort > Type.LONG) return null;
            args[k] = value;
        }
        return args;
    }
}
