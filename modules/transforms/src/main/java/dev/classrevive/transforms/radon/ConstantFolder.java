package dev.classrevive.transforms.radon;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;
import java.util.*;

/**
 * Phase 2DK: folds straight-line arithmetic on constants ({@code int}, {@code long}, {@code float}, {@code double}) into
 * one constant, as javac would have. Radon's number obfuscation replaces a constant by a chain such as
 * {@code c0 c1 op c2 op ... corr op}; its bitwise variant may replace each constant of such a chain again, so chains nest.
 * The folder evaluates them on a small constant stack: a run of constant pushes and operations whose operands are all
 * constants collapses to one push. Labels, line numbers and frames end a run, so a folded range never contains a jump
 * target. Integer division or remainder by zero is never folded (it throws at run time). Java arithmetic is the JVM's
 * (IEEE 754 for floating point, masked shift counts), so the folded value is the one the code computes.
 */
final class ConstantFolder implements Opcodes {
    private ConstantFolder() {}

    private record Entry(Object value, AbstractInsnNode first, int count) {}

    /** Folds every chain of the method; returns how many chains of two or more instructions became one constant. */
    static int fold(MethodNode method) {
        int folded = 0;
        var stack = new ArrayList<Entry>();
        for (var insn : method.instructions.toArray()) {
            int op = insn.getOpcode();
            if (op < 0) { folded += commit(method, stack); continue; }
            Object constant = constant(insn);
            if (constant != null) { stack.add(new Entry(constant, insn, 1)); continue; }
            if (stack.size() >= 2 && binary(op)) {
                var right = stack.get(stack.size() - 1); var left = stack.get(stack.size() - 2);
                Object value = binary(op, left.value, right.value);
                if (value != null) {
                    stack.remove(stack.size() - 1); stack.remove(stack.size() - 1);
                    stack.add(new Entry(value, left.first, left.count + right.count + 1));
                    continue;
                }
            }
            if (!stack.isEmpty() && unary(op)) {
                var top = stack.get(stack.size() - 1);
                Object value = unary(op, top.value);
                if (value != null) {
                    stack.set(stack.size() - 1, new Entry(value, top.first, top.count + 1));
                    continue;
                }
            }
            folded += commit(method, stack);
        }
        return folded + commit(method, stack);
    }

    /** Replaces each entry built from several instructions by one push of its value. */
    private static int commit(MethodNode method, List<Entry> stack) {
        int folded = 0;
        for (var entry : stack) {
            if (entry.count < 2) continue;
            var insns = method.instructions;
            var replacement = push(entry.value);
            insns.insertBefore(entry.first, replacement);
            AbstractInsnNode at = entry.first;
            for (int removed = 0; removed < entry.count; ) {
                var next = at.getNext();
                if (at.getOpcode() >= 0) { insns.remove(at); removed++; }
                at = next;
            }
            folded++;
        }
        stack.clear();
        return folded;
    }

    static Object constant(AbstractInsnNode insn) {
        int op = insn.getOpcode();
        if (op >= ICONST_M1 && op <= ICONST_5) return op - ICONST_0;
        if (op == LCONST_0 || op == LCONST_1) return (long) (op - LCONST_0);
        if (op >= FCONST_0 && op <= FCONST_2) return (float) (op - FCONST_0);
        if (op == DCONST_0 || op == DCONST_1) return (double) (op - DCONST_0);
        if ((op == BIPUSH || op == SIPUSH) && insn instanceof IntInsnNode number) return number.operand;
        if (insn instanceof LdcInsnNode ldc && (ldc.cst instanceof Integer || ldc.cst instanceof Long
            || ldc.cst instanceof Float || ldc.cst instanceof Double)) return ldc.cst;
        return null;
    }

    /** The shortest javac-style push of a constant. */
    static AbstractInsnNode push(Object value) {
        if (value instanceof Integer i) {
            if (i >= -1 && i <= 5) return new InsnNode(ICONST_0 + i);
            if (i >= Byte.MIN_VALUE && i <= Byte.MAX_VALUE) return new IntInsnNode(BIPUSH, i);
            if (i >= Short.MIN_VALUE && i <= Short.MAX_VALUE) return new IntInsnNode(SIPUSH, i);
            return new LdcInsnNode(i);
        }
        if (value instanceof Long l && (l == 0L || l == 1L)) return new InsnNode(LCONST_0 + l.intValue());
        if (value instanceof Float f && Float.floatToRawIntBits(f) == Float.floatToRawIntBits((float) Math.round(f)) && f >= 0 && f <= 2)
            return new InsnNode(FCONST_0 + (int) (float) f);
        if (value instanceof Double d && Double.doubleToRawLongBits(d) == Double.doubleToRawLongBits((double) Math.round(d)) && d >= 0 && d <= 1)
            return new InsnNode(DCONST_0 + (int) (double) d);
        return new LdcInsnNode(value);
    }

    private static boolean binary(int op) { return op >= IADD && op <= DREM || op >= ISHL && op <= LXOR; }
    private static boolean unary(int op) { return op >= INEG && op <= DNEG || op >= I2L && op <= I2S; }

    private static Object binary(int op, Object a, Object b) {
        switch (op) {
            case IADD: case ISUB: case IMUL: case IDIV: case IREM: case IAND: case IOR: case IXOR: case ISHL: case ISHR: case IUSHR: {
                if (!(a instanceof Integer x) || !(b instanceof Integer y)) return null;
                return switch (op) {
                    case IADD -> x + y; case ISUB -> x - y; case IMUL -> x * y;
                    case IDIV -> y == 0 ? null : (Object) (x / y); case IREM -> y == 0 ? null : (Object) (x % y);
                    case IAND -> x & y; case IOR -> x | y; case IXOR -> x ^ y;
                    case ISHL -> x << y; case ISHR -> x >> y; default -> x >>> y;
                };
            }
            case LSHL: case LSHR: case LUSHR: {
                if (!(a instanceof Long x) || !(b instanceof Integer y)) return null;
                return switch (op) { case LSHL -> x << y; case LSHR -> x >> y; default -> x >>> y; };
            }
            case LADD: case LSUB: case LMUL: case LDIV: case LREM: case LAND: case LOR: case LXOR: {
                if (!(a instanceof Long x) || !(b instanceof Long y)) return null;
                return switch (op) {
                    case LADD -> x + y; case LSUB -> x - y; case LMUL -> x * y;
                    case LDIV -> y == 0 ? null : (Object) (x / y); case LREM -> y == 0 ? null : (Object) (x % y);
                    case LAND -> x & y; case LOR -> x | y; default -> x ^ y;
                };
            }
            case FADD: case FSUB: case FMUL: case FDIV: case FREM: {
                if (!(a instanceof Float x) || !(b instanceof Float y)) return null;
                return switch (op) { case FADD -> x + y; case FSUB -> x - y; case FMUL -> x * y; case FDIV -> x / y; default -> x % y; };
            }
            case DADD: case DSUB: case DMUL: case DDIV: case DREM: {
                if (!(a instanceof Double x) || !(b instanceof Double y)) return null;
                return switch (op) { case DADD -> x + y; case DSUB -> x - y; case DMUL -> x * y; case DDIV -> x / y; default -> x % y; };
            }
            default: return null;
        }
    }

    private static Object unary(int op, Object a) {
        return switch (op) {
            case INEG -> a instanceof Integer x ? (Object) (-x) : null;
            case LNEG -> a instanceof Long x ? (Object) (-x) : null;
            case FNEG -> a instanceof Float x ? (Object) (-x) : null;
            case DNEG -> a instanceof Double x ? (Object) (-x) : null;
            case I2L -> a instanceof Integer x ? (Object) (long) x : null;
            case I2F -> a instanceof Integer x ? (Object) (float) x : null;
            case I2D -> a instanceof Integer x ? (Object) (double) x : null;
            case L2I -> a instanceof Long x ? (Object) (int) (long) x : null;
            case L2F -> a instanceof Long x ? (Object) (float) x : null;
            case L2D -> a instanceof Long x ? (Object) (double) x : null;
            case F2I -> a instanceof Float x ? (Object) (int) (float) x : null;
            case F2L -> a instanceof Float x ? (Object) (long) (float) x : null;
            case F2D -> a instanceof Float x ? (Object) (double) x : null;
            case D2I -> a instanceof Double x ? (Object) (int) (double) x : null;
            case D2L -> a instanceof Double x ? (Object) (long) (double) x : null;
            case D2F -> a instanceof Double x ? (Object) (float) (double) x : null;
            case I2B -> a instanceof Integer x ? (Object) (int) (byte) (int) x : null;
            case I2C -> a instanceof Integer x ? (Object) (int) (char) (int) x : null;
            case I2S -> a instanceof Integer x ? (Object) (int) (short) (int) x : null;
            default -> null;
        };
    }
}
