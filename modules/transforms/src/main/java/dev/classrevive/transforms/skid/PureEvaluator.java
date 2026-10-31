package dev.classrevive.transforms.skid;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;
import java.io.InputStream;
import java.util.*;

/**
 * Phase 2CN: evaluates static methods with primitive parameters and a primitive result by interpreting their bytecode,
 * with a step budget. Only primitive arithmetic, locals, branches and calls to other such methods are interpreted; any
 * field, object, array, exception handler or native method refuses the evaluation. Nothing is loaded or initialized:
 * jar methods come from the jar's bytes, JDK methods from the platform's class files read as resources.
 *
 * <p>A short allowlist of JDK methods whose bodies use lookup tables or intrinsics ({@code Character.toLowerCase(int)},
 * ...) is evaluated by calling the JDK's own method: that runs JDK code on constants, never target code.
 */
final class PureEvaluator implements Opcodes {
    static final int MAX_STEPS = 20_000;
    private static final int MAX_DEPTH = 8;
    private final Map<String,ClassNode> jar;
    private final Map<String,Optional<ClassNode>> jdk = new HashMap<>();
    private final Map<String,Boolean> refused = new HashMap<>();

    PureEvaluator(Map<String,ClassNode> jar) { this.jar = jar; }

    /** True when the descriptor has only primitive parameters (at least one) and a primitive, non-void result. */
    static boolean primitiveDescriptor(String desc) {
        var args = Type.getArgumentTypes(desc);
        if (args.length == 0) return false;
        for (var arg : args) if (arg.getSort() < Type.BOOLEAN || arg.getSort() > Type.DOUBLE) return false;
        int result = Type.getReturnType(desc).getSort();
        return result >= Type.BOOLEAN && result <= Type.DOUBLE;
    }

    /** Evaluates {@code owner.name desc} on boxed primitive arguments (int-like ones as Integer); empty when refused. */
    Optional<Object> evaluate(String owner, String name, String desc, Object[] args) {
        if (!primitiveDescriptor(desc)) return Optional.empty();
        String key = owner + "." + name + desc;
        if (refused.containsKey(key)) return Optional.ofNullable(Allowlist.call(owner, name, desc, args));
        try {
            var budget = new int[] {MAX_STEPS};
            Object result = invoke(owner, name, desc, args, budget, 0);
            if (result != null) return Optional.of(result);
        } catch (Refusal refusal) {
            if (refusal.permanent) refused.put(key, true);
        } catch (ArithmeticException | IndexOutOfBoundsException | ClassCastException error) {
            // A division by zero or a malformed body: no value.
        }
        Object reflected = Allowlist.call(owner, name, desc, args);
        return Optional.ofNullable(reflected);
    }

    private static final class Refusal extends RuntimeException {
        final boolean permanent;
        Refusal(boolean permanent) { super(null, null, false, false); this.permanent = permanent; }
    }

    private final Map<String,Boolean> pure = new HashMap<>();

    /**
     * True when calling the method has no effect besides its result or an {@code ArithmeticException} (a division by zero):
     * an allowlisted JDK class, or a static method whose body only does primitive arithmetic, branches and calls to such
     * methods. A call is dropped only when its arguments are literals it was evaluated on without error.
     */
    boolean pureBody(String owner, String name, String desc) {
        if (Allowlist.CLASSES.contains(owner)) return primitiveDescriptor(desc);
        String key = owner + "." + name + desc;
        Boolean known = pure.get(key);
        if (known != null) return known;
        pure.put(key, false); // recursion counts as impure
        boolean result = pureBodyOf(method(owner, name, desc));
        pure.put(key, result);
        return result;
    }

    private boolean pureBodyOf(MethodNode method) {
        if (method == null || (method.access & ACC_STATIC) == 0 || (method.access & (ACC_NATIVE | ACC_ABSTRACT)) != 0
            || !method.tryCatchBlocks.isEmpty() || !primitiveDescriptor(method.desc)) return false;
        for (var insn : method.instructions) {
            int op = insn.getOpcode();
            if (op < 0) continue;
            if (insn instanceof MethodInsnNode call) {
                if (op != INVOKESTATIC || !primitiveDescriptor(call.desc) || !pureBody(call.owner, call.name, call.desc)) return false;
                continue;
            }
            boolean allowed = op >= ICONST_M1 && op <= LDC && !(insn instanceof LdcInsnNode ldc && !(ldc.cst instanceof Number))
                || op >= ILOAD && op <= DLOAD || op >= ISTORE && op <= DSTORE || op >= POP && op <= DCMPG || op >= IFEQ && op <= IF_ICMPLE || op == GOTO || op == TABLESWITCH || op == LOOKUPSWITCH
                || op >= IRETURN && op <= DRETURN;
            if (op == ACONST_NULL || op == IINC) allowed = op == IINC;
            if (!allowed) return false;
        }
        return true;
    }

    private MethodNode method(String owner, String name, String desc) {
        ClassNode node = jar.get(owner);
        if (node == null) node = jdkClass(owner).orElse(null);
        if (node == null) return null;
        for (var method : node.methods) if (method.name.equals(name) && method.desc.equals(desc)) return method;
        return null;
    }

    private Optional<ClassNode> jdkClass(String owner) {
        return jdk.computeIfAbsent(owner, name -> {
            if (!(name.startsWith("java/") || name.startsWith("javax/"))) return Optional.empty();
            try (InputStream in = ClassLoader.getPlatformClassLoader().getResourceAsStream(name + ".class")) {
                if (in == null) return Optional.empty();
                byte[] bytes = in.readNBytes(8 * 1024 * 1024);
                var node = new ClassNode();
                new ClassReader(bytes).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                return Optional.of(node);
            } catch (Exception error) {
                return Optional.empty();
            }
        });
    }

    private Object invoke(String owner, String name, String desc, Object[] args, int[] budget, int depth) {
        if (depth > MAX_DEPTH) throw new Refusal(false);
        MethodNode method = method(owner, name, desc);
        if (method == null || (method.access & ACC_STATIC) == 0 || (method.access & (ACC_NATIVE | ACC_ABSTRACT)) != 0
            || !method.tryCatchBlocks.isEmpty()) throw new Refusal(true);
        var insns = method.instructions.toArray();
        var index = new IdentityHashMap<AbstractInsnNode,Integer>();
        for (int i = 0; i < insns.length; i++) index.put(insns[i], i);
        Object[] locals = new Object[Math.max(method.maxLocals, 1) + 2];
        int slot = 0;
        var types = Type.getArgumentTypes(desc);
        for (int i = 0; i < types.length; i++) { locals[slot] = args[i]; slot += types[i].getSize(); }
        var stack = new ArrayDeque<Object>();
        int pc = 0;
        while (pc < insns.length) {
            if (--budget[0] < 0) throw new Refusal(false);
            var insn = insns[pc];
            int op = insn.getOpcode();
            int next = pc + 1;
            if (op < 0) { pc = next; continue; }
            switch (insn) {
                case VarInsnNode var -> {
                    if (op >= ILOAD && op <= ALOAD) { if (op == ALOAD || locals[var.var] == null) throw new Refusal(true); stack.push(locals[var.var]); }
                    else if (op >= ISTORE && op <= DSTORE) locals[var.var] = stack.pop();
                    else throw new Refusal(true);
                }
                case IincInsnNode inc -> locals[inc.var] = (Integer) locals[inc.var] + inc.incr;
                case IntInsnNode push -> { if (op == NEWARRAY) throw new Refusal(true); stack.push(push.operand); }
                case LdcInsnNode ldc -> {
                    if (ldc.cst instanceof Integer || ldc.cst instanceof Long || ldc.cst instanceof Float || ldc.cst instanceof Double) stack.push(ldc.cst);
                    else throw new Refusal(true);
                }
                case JumpInsnNode jump -> {
                    boolean taken = switch (op) {
                        case GOTO -> true;
                        case IFEQ -> (Integer) stack.pop() == 0; case IFNE -> (Integer) stack.pop() != 0;
                        case IFLT -> (Integer) stack.pop() < 0; case IFGE -> (Integer) stack.pop() >= 0;
                        case IFGT -> (Integer) stack.pop() > 0; case IFLE -> (Integer) stack.pop() <= 0;
                        case IF_ICMPEQ, IF_ICMPNE, IF_ICMPLT, IF_ICMPGE, IF_ICMPGT, IF_ICMPLE -> {
                            int b = (Integer) stack.pop(), a = (Integer) stack.pop();
                            yield Interpret.compare(op, a, b);
                        }
                        default -> throw new Refusal(true);
                    };
                    if (taken) next = index.get(jump.label);
                }
                case TableSwitchInsnNode table -> {
                    int key = (Integer) stack.pop();
                    next = index.get(key >= table.min && key <= table.max ? table.labels.get(key - table.min) : table.dflt);
                }
                case LookupSwitchInsnNode lookup -> {
                    int key = (Integer) stack.pop(), at = lookup.keys.indexOf(key);
                    next = index.get(at >= 0 ? lookup.labels.get(at) : lookup.dflt);
                }
                case MethodInsnNode call -> {
                    if (op != INVOKESTATIC || !primitiveDescriptor(call.desc)) throw new Refusal(true);
                    var argTypes = Type.getArgumentTypes(call.desc);
                    Object[] callArgs = new Object[argTypes.length];
                    for (int i = argTypes.length - 1; i >= 0; i--) callArgs[i] = stack.pop();
                    Object result;
                    try { result = invoke(call.owner, call.name, call.desc, callArgs, budget, depth + 1); }
                    catch (Refusal refusal) {
                        result = Allowlist.call(call.owner, call.name, call.desc, callArgs);
                        if (result == null) throw refusal;
                    }
                    stack.push(result);
                }
                case InsnNode plain -> {
                    if (op >= IRETURN && op <= DRETURN) return normalize(Type.getReturnType(desc), stack.pop());
                    Object value = Interpret.plain(op, stack);
                    if (value == Interpret.REFUSED) throw new Refusal(true);
                }
                default -> throw new Refusal(true);
            }
            pc = next;
        }
        throw new Refusal(true);
    }

    private static Object normalize(Type type, Object value) {
        return switch (type.getSort()) {
            case Type.BOOLEAN, Type.BYTE, Type.CHAR, Type.SHORT, Type.INT -> (Integer) value;
            default -> value;
        };
    }

    /** JDK methods with table- or intrinsic-based bodies, evaluated by the JDK itself on constants. */
    static final class Allowlist {
        static final Set<String> CLASSES = Set.of("java/lang/Math", "java/lang/StrictMath", "java/lang/Integer", "java/lang/Long",
            "java/lang/Short", "java/lang/Byte", "java/lang/Character", "java/lang/Boolean", "java/lang/Float", "java/lang/Double",
            "java/util/Objects");
        static Object call(String owner, String name, String desc, Object[] args) {
            if (!CLASSES.contains(owner) || !primitiveDescriptor(desc)) return null;
            try {
                var types = Type.getArgumentTypes(desc);
                Class<?>[] parameters = new Class<?>[types.length];
                Object[] values = new Object[types.length];
                for (int i = 0; i < types.length; i++) {
                    parameters[i] = primitive(types[i]);
                    values[i] = convert(types[i], args[i]);
                }
                var method = Class.forName(owner.replace('/', '.'), false, ClassLoader.getPlatformClassLoader()).getMethod(name, parameters);
                if (!java.lang.reflect.Modifier.isStatic(method.getModifiers()) || !Type.getMethodDescriptor(method).equals(desc)) return null;
                Object result = method.invoke(null, values);
                return switch (Type.getReturnType(desc).getSort()) {
                    case Type.BOOLEAN -> ((Boolean) result) ? 1 : 0;
                    case Type.CHAR -> (int) (Character) result;
                    case Type.BYTE -> (int) (Byte) result;
                    case Type.SHORT -> (int) (Short) result;
                    default -> result;
                };
            } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
                return null;
            }
        }
        private static Class<?> primitive(Type type) {
            return switch (type.getSort()) {
                case Type.BOOLEAN -> boolean.class; case Type.BYTE -> byte.class; case Type.CHAR -> char.class; case Type.SHORT -> short.class;
                case Type.INT -> int.class; case Type.LONG -> long.class; case Type.FLOAT -> float.class; default -> double.class;
            };
        }
        private static Object convert(Type type, Object value) {
            return switch (type.getSort()) {
                case Type.BOOLEAN -> ((Integer) value) != 0; case Type.BYTE -> (byte) (int) (Integer) value;
                case Type.CHAR -> (char) (int) (Integer) value; case Type.SHORT -> (short) (int) (Integer) value;
                default -> value;
            };
        }
    }
}
