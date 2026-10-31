package dev.classrevive.transforms.skid;

import org.objectweb.asm.Opcodes;
import java.util.Deque;

/** Phase 2CN: Java semantics of the primitive instructions, shared by the evaluator and the abstract interpreter. */
final class Interpret implements Opcodes {
    static final Object REFUSED = new Object();
    private Interpret() {}

    static boolean compare(int op, int a, int b) {
        return switch (op) {
            case IFEQ, IF_ICMPEQ -> a == b; case IFNE, IF_ICMPNE -> a != b;
            case IFLT, IF_ICMPLT -> a < b; case IFGE, IF_ICMPGE -> a >= b;
            case IFGT, IF_ICMPGT -> a > b; case IFLE, IF_ICMPLE -> a <= b;
            default -> throw new IllegalArgumentException("not an int comparison: " + op);
        };
    }

    /** Int binary arithmetic; null when the instruction would throw (division by zero). */
    static Integer binaryInt(int op, int a, int b) {
        return switch (op) {
            case IADD -> a + b; case ISUB -> a - b; case IMUL -> a * b;
            case IDIV -> b == 0 ? null : a / b; case IREM -> b == 0 ? null : a % b;
            case ISHL -> a << b; case ISHR -> a >> b; case IUSHR -> a >>> b;
            case IAND -> a & b; case IOR -> a | b; case IXOR -> a ^ b;
            default -> null;
        };
    }

    static Integer unaryInt(int op, int v) {
        return switch (op) {
            case INEG -> -v; case I2B -> (int) (byte) v; case I2C -> (int) (char) v; case I2S -> (int) (short) v;
            default -> null;
        };
    }

    private static boolean wide(Object value) { return value instanceof Long || value instanceof Double; }

    /** Executes one zero-operand instruction on a value stack (long/double are one entry); REFUSED when unsupported. */
    static Object plain(int op, Deque<Object> stack) {
        switch (op) {
            case NOP -> { return null; }
            case ICONST_M1, ICONST_0, ICONST_1, ICONST_2, ICONST_3, ICONST_4, ICONST_5 -> stack.push(op - ICONST_0);
            case LCONST_0, LCONST_1 -> stack.push((long) (op - LCONST_0));
            case FCONST_0, FCONST_1, FCONST_2 -> stack.push((float) (op - FCONST_0));
            case DCONST_0, DCONST_1 -> stack.push((double) (op - DCONST_0));
            case POP -> stack.pop();
            case POP2 -> { Object top = stack.pop(); if (!wide(top)) stack.pop(); }
            case DUP -> stack.push(stack.peek());
            case DUP_X1 -> { Object a = stack.pop(), b = stack.pop(); stack.push(a); stack.push(b); stack.push(a); }
            case DUP_X2 -> {
                Object a = stack.pop(), b = stack.pop();
                if (wide(b)) { stack.push(a); stack.push(b); stack.push(a); }
                else { Object c = stack.pop(); stack.push(a); stack.push(c); stack.push(b); stack.push(a); }
            }
            case DUP2 -> {
                Object a = stack.pop();
                if (wide(a)) { stack.push(a); stack.push(a); }
                else { Object b = stack.pop(); stack.push(b); stack.push(a); stack.push(b); stack.push(a); }
            }
            case SWAP -> { Object a = stack.pop(), b = stack.pop(); stack.push(a); stack.push(b); }
            case IADD, ISUB, IMUL, IDIV, IREM, ISHL, ISHR, IUSHR, IAND, IOR, IXOR -> {
                int b = (Integer) stack.pop(), a = (Integer) stack.pop();
                Integer r = binaryInt(op, a, b);
                if (r == null) throw new ArithmeticException();
                stack.push(r);
            }
            case LADD, LSUB, LMUL, LDIV, LREM, LAND, LOR, LXOR -> {
                long b = (Long) stack.pop(), a = (Long) stack.pop();
                stack.push(switch (op) {
                    case LADD -> a + b; case LSUB -> a - b; case LMUL -> a * b; case LDIV -> a / b; case LREM -> a % b;
                    case LAND -> a & b; case LOR -> a | b; default -> a ^ b; });
            }
            case LSHL, LSHR, LUSHR -> {
                int b = (Integer) stack.pop(); long a = (Long) stack.pop();
                stack.push(op == LSHL ? a << b : op == LSHR ? a >> b : a >>> b);
            }
            case FADD, FSUB, FMUL, FDIV, FREM -> {
                float b = (Float) stack.pop(), a = (Float) stack.pop();
                stack.push(switch (op) { case FADD -> a + b; case FSUB -> a - b; case FMUL -> a * b; case FDIV -> a / b; default -> a % b; });
            }
            case DADD, DSUB, DMUL, DDIV, DREM -> {
                double b = (Double) stack.pop(), a = (Double) stack.pop();
                stack.push(switch (op) { case DADD -> a + b; case DSUB -> a - b; case DMUL -> a * b; case DDIV -> a / b; default -> a % b; });
            }
            case INEG, I2B, I2C, I2S -> stack.push(unaryInt(op, (Integer) stack.pop()));
            case LNEG -> stack.push(-(Long) stack.pop());
            case FNEG -> stack.push(-(Float) stack.pop());
            case DNEG -> stack.push(-(Double) stack.pop());
            case I2L -> stack.push((long) (Integer) stack.pop());
            case I2F -> stack.push((float) (Integer) stack.pop());
            case I2D -> stack.push((double) (Integer) stack.pop());
            case L2I -> stack.push((int) (long) (Long) stack.pop());
            case L2F -> stack.push((float) (Long) stack.pop());
            case L2D -> stack.push((double) (Long) stack.pop());
            case F2I -> stack.push((int) (float) (Float) stack.pop());
            case F2L -> stack.push((long) (float) (Float) stack.pop());
            case F2D -> stack.push((double) (Float) stack.pop());
            case D2I -> stack.push((int) (double) (Double) stack.pop());
            case D2L -> stack.push((long) (double) (Double) stack.pop());
            case D2F -> stack.push((float) (double) (Double) stack.pop());
            case LCMP -> { long b = (Long) stack.pop(), a = (Long) stack.pop(); stack.push(Long.compare(a, b)); }
            case FCMPL, FCMPG -> {
                float b = (Float) stack.pop(), a = (Float) stack.pop();
                stack.push(Float.isNaN(a) || Float.isNaN(b) ? (op == FCMPG ? 1 : -1) : a > b ? 1 : a == b ? 0 : -1);
            }
            case DCMPL, DCMPG -> {
                double b = (Double) stack.pop(), a = (Double) stack.pop();
                stack.push(Double.isNaN(a) || Double.isNaN(b) ? (op == DCMPG ? 1 : -1) : a > b ? 1 : a == b ? 0 : -1);
            }
            default -> { return REFUSED; }
        }
        return null;
    }
}
