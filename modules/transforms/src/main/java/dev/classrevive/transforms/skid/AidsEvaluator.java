package dev.classrevive.transforms.skid;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;
import java.util.*;

/**
 * Phase 2CZ: a concrete, bounded interpreter for the code Aidsfuscator generates to decrypt numbers and strings: its
 * decryptor methods and the initializers at the start of {@code <clinit>} that fill their arrays. It runs the target's
 * bytecode on values of its own (ints, longs, arrays, strings), never defining, loading or initializing a target class.
 * The JDK is reached only through a closed allowlist: {@code String} operations, {@code Throwable.getStackTrace()} (which
 * returns the modelled frames: the decryptor and its caller, the class and method of the call site) with
 * {@code StackTraceElement}'s names, and the AES-CBC decryption of the AES initializer ({@code Cipher},
 * {@code SecretKeySpec}, {@code IvParameterSpec}, {@code Base64}), run on constants. Static fields are those of the
 * class being reversed, kept in a map. Anything else, an exception or a budget overrun refuses the evaluation.
 */
final class AidsEvaluator implements Opcodes {
    static final int MAX_STEPS = 2_000_000, MAX_ARRAY = 1 << 20, MAX_DEPTH = 4;
    private final ClassNode owner;
    /** {@code name:desc} of the owner's static fields to their current values. */
    final Map<String,Object> statics = new HashMap<>();
    /** After {@link #runInitializer}: the fields it wrote and the instructions it ran before the program's code. */
    final Set<String> written = new HashSet<>();
    final Set<AbstractInsnNode> executed = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<AbstractInsnNode> sinceStatement = Collections.newSetFromMap(new IdentityHashMap<>());
    private long steps;

    static final class Refusal extends RuntimeException {
        Refusal(String why) { super(why, null, false, false); }
    }
    /** An object between {@code new} and its constructor. */
    private record Uninitialized(String type, int id) {}
    /** A {@code Throwable} created by the interpreted code: its stack trace is the interpreter's own frames. */
    private record Trace(StackTraceElement[] frames) {}
    /** The value a {@code jsr} pushes: where its {@code ret} goes back to. */
    private record ReturnAddress(int pc) {}
    private int allocations;

    AidsEvaluator(ClassNode owner) { this(owner, null); }

    /**
     * Phase 2DM: calls the interpreter does not run itself, answered by the caller with a value of its own (Zelix
     * KlassMaster's reflection: {@code Class.forName} and its member finders answered by symbolic references, nothing
     * loaded). {@link #UNHANDLED} lets the call be refused as usual.
     */
    interface Hooks {
        Object call(MethodInsnNode call, Object receiver, Object[] args);
        /** Whether {@code value} (one of the hooks' own values) may be cast to {@code type}. */
        default boolean castable(Object value, String type) { return false; }
    }
    static final Object UNHANDLED = new Object();
    private final Hooks hooks;
    AidsEvaluator(ClassNode owner, Hooks hooks) { this.owner = owner; this.hooks = hooks; }

    /**
     * Where the program's code starts: the first instruction of the statement holding the first instruction the
     * interpreter would not run ({@code null} when it reached a return), and the stack depth there.
     */
    record Stop(AbstractInsnNode at, int stackDepth) {}

    /**
     * Runs a {@code <clinit>} from its first instruction until it reaches an instruction outside the obfuscator's
     * initializer: a field of the class not in {@code fields}, any other class's member, a call outside the allowlist
     * (a decryptor call of the program included), or a return. Returns where it stopped; throws {@link Refusal} when the
     * initializer itself fails.
     */
    Stop runInitializer(MethodNode clinit, Set<String> fields) {
        steps = 0;
        var frames = new ArrayDeque<StackTraceElement>();
        frames.push(new StackTraceElement(owner.name.replace('/', '.'), "<clinit>", null, -1));
        return (Stop) execute(clinit, new Object[Math.max(clinit.maxLocals, 1)], frames, fields, true, 0);
    }

    /**
     * Phase 2DM: a copy of the static fields (arrays copied deeply), so a later call can start from this state again.
     * Zelix KlassMaster's reflection tables decrypt their entries in place; a call with a wrong key must not corrupt them.
     */
    Map<String,Object> snapshot() {
        var copy = new HashMap<String,Object>();
        statics.forEach((key, value) -> copy.put(key, deepCopy(value)));
        return copy;
    }
    void restore(Map<String,Object> snapshot) {
        statics.clear();
        snapshot.forEach((key, value) -> statics.put(key, deepCopy(value)));
    }
    private static Object deepCopy(Object value) {
        if (value instanceof Object[] array) {
            var copy = array.clone();
            for (int i = 0; i < copy.length; i++) copy[i] = deepCopy(copy[i]);
            return copy;
        }
        return switch (value) {
            case int[] a -> a.clone(); case long[] a -> a.clone(); case char[] a -> a.clone(); case byte[] a -> a.clone(); case short[] a -> a.clone();
            case null, default -> value;
        };
    }

    /** Calls a static method of the owner on constant arguments, as if from {@code callerMethod} of the owner. */
    Object call(MethodNode method, Object[] args, String callerMethod) {
        steps = 0;
        var frames = new ArrayDeque<StackTraceElement>();
        frames.push(new StackTraceElement(owner.name.replace('/', '.'), callerMethod, null, -1));
        return invoke(method, args, frames, 0);
    }

    private Object invoke(MethodNode method, Object[] args, Deque<StackTraceElement> frames, int depth) {
        if (depth > MAX_DEPTH || (method.access & ACC_STATIC) == 0) throw new Refusal("call depth or instance method");
        var locals = new Object[Math.max(method.maxLocals, 1)];
        int slot = 0;
        var types = Type.getArgumentTypes(method.desc);
        for (int i = 0; i < types.length; i++) { locals[slot] = args[i]; slot += types[i].getSize(); }
        frames.push(new StackTraceElement(owner.name.replace('/', '.'), method.name, null, -1));
        try { return execute(method, locals, frames, null, false, depth); }
        finally { frames.pop(); }
    }

    private Object execute(MethodNode method, Object[] locals, Deque<StackTraceElement> frames, Set<String> fields, boolean initializer, int depth) {
        var insns = method.instructions.toArray();
        var index = new IdentityHashMap<AbstractInsnNode,Integer>();
        for (int i = 0; i < insns.length; i++) index.put(insns[i], i);
        var stack = new ArrayList<Object>();
        int pc = 0;
        // The program's first statement starts at the last instruction run with an empty stack before a foreign one.
        AbstractInsnNode statement = null;
        while (true) {
            if (++steps > MAX_STEPS) throw new Refusal("budget");
            if (pc >= insns.length) throw new Refusal("fell off the code");
            var insn = insns[pc];
            int op = insn.getOpcode();
            if (op < 0) { pc++; continue; }
            if (stack.isEmpty()) {
                statement = insn;
                if (initializer) { executed.addAll(sinceStatement); sinceStatement.clear(); }
            }
            if (initializer && foreign(insn, fields)) return new Stop(statement, 0);
            if (initializer) sinceStatement.add(insn);
            int next = pc + 1;
            switch (insn) {
                case InsnNode plain -> {
                    if (op >= IRETURN && op <= ARETURN) return stack.removeLast();
                    if (op == RETURN) {
                        if (!initializer) return null;
                        executed.addAll(sinceStatement);
                        return new Stop(null, 0);
                    }
                    simple(op, stack);
                }
                case IntInsnNode i -> {
                    if (op == NEWARRAY) stack.add(newArray(i.operand, intValue(stack.removeLast())));
                    else stack.add(i.operand);
                }
                case LdcInsnNode ldc -> {
                    if (ldc.cst instanceof Integer || ldc.cst instanceof String || ldc.cst instanceof Float) stack.add(ldc.cst);
                    else if (ldc.cst instanceof Long || ldc.cst instanceof Double) { stack.add(ldc.cst); stack.add(null); }
                    else throw new Refusal("ldc " + ldc.cst);
                }
                case VarInsnNode var -> {
                    if (op >= ILOAD && op <= ALOAD) { stack.add(locals[var.var]); if (op == LLOAD || op == DLOAD) stack.add(null); }
                    else if (op >= ISTORE && op <= ASTORE) {
                        if (op == LSTORE || op == DSTORE) stack.removeLast();
                        locals[var.var] = stack.removeLast();
                    } else if (locals[var.var] instanceof ReturnAddress address) next = address.pc();
                    else throw new Refusal("ret without a return address");
                }
                case IincInsnNode inc -> locals[inc.var] = intValue(locals[inc.var]) + inc.incr;
                case JumpInsnNode jump -> {
                    // Phase 2DM: Zelix KlassMaster writes its initializers with subroutines (class files before Java 6).
                    if (op == JSR) { stack.add(new ReturnAddress(pc + 1)); next = index.get(jump.label); }
                    else if (branch(op, stack)) next = index.get(jump.label);
                }
                case TableSwitchInsnNode table -> {
                    int key = intValue(stack.removeLast());
                    next = index.get(key >= table.min && key <= table.max ? table.labels.get(key - table.min) : table.dflt);
                }
                case LookupSwitchInsnNode lookup -> {
                    int at = lookup.keys.indexOf(intValue(stack.removeLast()));
                    next = index.get(at >= 0 ? lookup.labels.get(at) : lookup.dflt);
                }
                case FieldInsnNode field -> {
                    if (op == GETSTATIC && PRIMITIVES.containsKey(field.owner + "." + field.name)) { stack.add(PRIMITIVES.get(field.owner + "." + field.name)); break; }
                    if (!field.owner.equals(owner.name) || op == GETFIELD || op == PUTFIELD) throw new Refusal("field " + field.owner + "." + field.name);
                    String key = field.name + ":" + field.desc;
                    if (op == GETSTATIC) {
                        if (!statics.containsKey(key)) throw new Refusal("unset field " + key);
                        stack.add(statics.get(key)); if (Type.getType(field.desc).getSize() == 2) stack.add(null);
                    } else {
                        if (Type.getType(field.desc).getSize() == 2) stack.removeLast();
                        statics.put(key, stack.removeLast());
                        if (initializer) written.add(key);
                    }
                }
                case TypeInsnNode type -> {
                    switch (op) {
                        case NEW -> {
                            if (!ALLOCATABLE.contains(type.desc)) throw new Refusal("new " + type.desc);
                            stack.add(new Uninitialized(type.desc, allocations++));
                        }
                        case ANEWARRAY -> {
                            int length = intValue(stack.removeLast());
                            if (length < 0 || length > MAX_ARRAY) throw new Refusal("array size");
                            stack.add(switch (type.desc) {
                                case "java/lang/String" -> new String[length];
                                case "java/lang/StackTraceElement" -> new StackTraceElement[length];
                                case "java/lang/Integer" -> new Integer[length];
                                case "java/lang/Long" -> new Long[length];
                                default -> new Object[length];
                            });
                        }
                        case CHECKCAST -> {
                            Object value = stack.getLast();
                            if (value != null && !castable(value, type.desc) && (hooks == null || !hooks.castable(value, type.desc)))
                                throw new Refusal("checkcast " + type.desc);
                        }
                        case INSTANCEOF -> {
                            Object value = stack.removeLast();
                            stack.add(value != null && (castable(value, type.desc) || hooks != null && hooks.castable(value, type.desc)) ? 1 : 0);
                        }
                        default -> throw new Refusal("type op " + op);
                    }
                }
                case MethodInsnNode call -> invokeInsn(call, stack, frames, depth);
                default -> throw new Refusal("instruction " + op);
            }
            pc = next;
        }
    }

    /** Whether an initializer instruction is no longer the obfuscator's (the program's own code starts there). */
    /** Phase 2DM: the JDK's primitive class objects ({@code int.class} as {@code Integer.TYPE}), JDK constants, not target code. */
    static final Map<String,Class<?>> PRIMITIVES = Map.of("java/lang/Boolean.TYPE", boolean.class, "java/lang/Byte.TYPE", byte.class,
        "java/lang/Character.TYPE", char.class, "java/lang/Short.TYPE", short.class, "java/lang/Integer.TYPE", int.class,
        "java/lang/Long.TYPE", long.class, "java/lang/Float.TYPE", float.class, "java/lang/Double.TYPE", double.class, "java/lang/Void.TYPE", void.class);

    private boolean foreign(AbstractInsnNode insn, Set<String> fields) {
        if (insn instanceof FieldInsnNode field && field.getOpcode() == GETSTATIC && PRIMITIVES.containsKey(field.owner + "." + field.name)) return false;
        if (insn instanceof FieldInsnNode field) return !field.owner.equals(owner.name) || !fields.contains(field.name + ":" + field.desc);
        if (insn instanceof MethodInsnNode call && call.getOpcode() == INVOKESTATIC && call.owner.equals(owner.name)) return !evaluable(call, fields, new HashSet<>());
        if (insn instanceof MethodInsnNode call) return !allowed(call);
        if (insn instanceof TypeInsnNode type && type.getOpcode() == NEW) return !ALLOCATABLE.contains(type.desc);
        int op = insn.getOpcode();
        return op == ATHROW || op == MONITORENTER || op == MONITOREXIT || insn instanceof InvokeDynamicInsnNode || insn instanceof MultiANewArrayInsnNode;
    }

    /**
     * Phase 2DM: whether a static method of the owner, called from the initializer, only does what this interpreter runs:
     * allowlisted calls, its own class's fields of the initializer, the JDK's primitive classes, and other such methods
     * (Zelix KlassMaster's initializers fill their tables through helpers).
     */
    private boolean evaluable(MethodInsnNode call, Set<String> fields, Set<String> seen) {
        String key = call.name + call.desc;
        if (seen.contains(key)) return true; // recursion: judged by the first visit
        if (seen.size() >= 16) return false;
        seen.add(key);
        MethodNode target = null;
        for (var method : owner.methods) if (method.name.equals(call.name) && method.desc.equals(call.desc)) target = method;
        if (target == null || (target.access & ACC_STATIC) == 0 || target.instructions.size() == 0 || !target.tryCatchBlocks.isEmpty()) return false;
        for (var insn : target.instructions) {
            if (insn instanceof MethodInsnNode inner) {
                if (inner.getOpcode() == INVOKESTATIC && inner.owner.equals(owner.name)) { if (!evaluable(inner, fields, seen)) return false; }
                else if (!allowed(inner)) return false;
            } else if (insn instanceof FieldInsnNode field) {
                if (field.getOpcode() == GETSTATIC && PRIMITIVES.containsKey(field.owner + "." + field.name)) continue;
                if (!field.owner.equals(owner.name) || !fields.contains(field.name + ":" + field.desc)) return false;
            } else if (insn instanceof TypeInsnNode type && type.getOpcode() == NEW && !ALLOCATABLE.contains(type.desc)) return false;
            else if (insn instanceof InvokeDynamicInsnNode || insn instanceof MultiANewArrayInsnNode || insn.getOpcode() == ATHROW
                || insn.getOpcode() == MONITORENTER || insn.getOpcode() == MONITOREXIT) return false;
        }
        return true;
    }

    private static final Set<String> ALLOCATABLE = Set.of("java/lang/String", "java/lang/Throwable", "javax/crypto/spec/IvParameterSpec",
        "javax/crypto/spec/SecretKeySpec", "java/lang/StringBuilder");

    private static boolean allowed(MethodInsnNode call) {
        return switch (call.owner) {
            case "java/lang/String" -> Set.of("charAt(I)C", "getBytes(Ljava/lang/String;)[B", "hashCode()I", "intern()Ljava/lang/String;", "length()I",
                "substring(II)Ljava/lang/String;", "toCharArray()[C", "<init>([C)V", "<init>([BLjava/lang/String;)V", "<init>([B)V",
                "indexOf(I)I", "indexOf(II)I", "substring(I)Ljava/lang/String;", "equals(Ljava/lang/Object;)Z").contains(call.name + call.desc);
            case "java/lang/Throwable" -> Set.of("<init>()V", "getStackTrace()[Ljava/lang/StackTraceElement;").contains(call.name + call.desc);
            case "java/lang/StackTraceElement" -> Set.of("getClassName()Ljava/lang/String;", "getMethodName()Ljava/lang/String;").contains(call.name + call.desc);
            case "java/util/Base64" -> (call.name + call.desc).equals("getDecoder()Ljava/util/Base64$Decoder;");
            case "java/util/Base64$Decoder" -> (call.name + call.desc).equals("decode(Ljava/lang/String;)[B");
            case "javax/crypto/Cipher" -> Set.of("getInstance(Ljava/lang/String;)Ljavax/crypto/Cipher;", "doFinal([B)[B",
                "init(ILjava/security/Key;Ljava/security/spec/AlgorithmParameterSpec;)V").contains(call.name + call.desc);
            case "javax/crypto/spec/SecretKeySpec" -> (call.name + call.desc).equals("<init>([BLjava/lang/String;)V");
            case "javax/crypto/spec/IvParameterSpec" -> (call.name + call.desc).equals("<init>([B)V");
            // Phase 2DM: Zelix KlassMaster's number decryptors cache boxed values.
            case "java/lang/Integer" -> Set.of("valueOf(I)Ljava/lang/Integer;", "intValue()I").contains(call.name + call.desc);
            case "java/lang/Long" -> Set.of("valueOf(J)Ljava/lang/Long;", "longValue()J", "parseLong(Ljava/lang/String;I)J").contains(call.name + call.desc);
            default -> false;
        };
    }

    private void invokeInsn(MethodInsnNode call, List<Object> stack, Deque<StackTraceElement> frames, int depth) {
        var types = Type.getArgumentTypes(call.desc);
        var args = new Object[types.length];
        for (int k = types.length - 1; k >= 0; k--) { if (types[k].getSize() == 2) stack.removeLast(); args[k] = stack.removeLast(); }
        // A static helper of the owner (Aidsfuscator's decryptors call none, but its polymorphic masks may); a hook may
        // answer for one first (Zelix KlassMaster's member finders).
        if (call.getOpcode() == INVOKESTATIC && call.owner.equals(owner.name) && hooks != null) {
            Object answer = hooks.call(call, null, args);
            if (answer != UNHANDLED) { push(stack, answer, Type.getReturnType(call.desc)); return; }
        }
        if (call.getOpcode() == INVOKESTATIC && call.owner.equals(owner.name)) {
            MethodNode target = null;
            for (var method : owner.methods) if (method.name.equals(call.name) && method.desc.equals(call.desc)) target = method;
            if (target == null) throw new Refusal("missing " + call.name);
            Object result = invoke(target, args, frames, depth + 1);
            push(stack, result, Type.getReturnType(call.desc));
            return;
        }
        if (hooks != null && !allowed(call)) {
            Object receiver = call.getOpcode() == INVOKESTATIC || call.name.equals("<init>") ? null : stack.removeLast();
            Object answer = hooks.call(call, receiver, args);
            if (answer != UNHANDLED) { push(stack, answer, Type.getReturnType(call.desc)); return; }
            if (receiver != null) stack.add(receiver);
        }
        if (!allowed(call)) throw new Refusal("call " + call.owner + "." + call.name + call.desc);
        if (call.name.equals("<init>")) {
            var target = (Uninitialized) stack.removeLast();
            Object built = construct(call, args, frames);
            for (int k = 0; k < stack.size(); k++) if (target.equals(stack.get(k))) stack.set(k, built);
            return;
        }
        Object receiver = call.getOpcode() == INVOKESTATIC ? null : stack.removeLast();
        if (call.getOpcode() != INVOKESTATIC && receiver == null) throw new Refusal("null receiver");
        Object result;
        try {
            result = switch (call.owner + "." + call.name) {
                case "java/lang/String.charAt" -> (int) ((String) receiver).charAt(intValue(args[0]));
                case "java/lang/String.getBytes" -> ((String) receiver).getBytes((String) args[0]);
                case "java/lang/String.hashCode" -> receiver.hashCode();
                case "java/lang/String.intern" -> receiver;
                case "java/lang/String.length" -> ((String) receiver).length();
                case "java/lang/String.substring" -> args.length == 1 ? ((String) receiver).substring(intValue(args[0]))
                    : ((String) receiver).substring(intValue(args[0]), intValue(args[1]));
                case "java/lang/String.indexOf" -> args.length == 1 ? ((String) receiver).indexOf(intValue(args[0]))
                    : ((String) receiver).indexOf(intValue(args[0]), intValue(args[1]));
                case "java/lang/String.equals" -> receiver.equals(args[0]) ? 1 : 0;
                case "java/lang/Long.parseLong" -> Long.parseLong((String) args[0], intValue(args[1]));
                case "java/lang/String.toCharArray" -> ((String) receiver).toCharArray();
                case "java/lang/Throwable.getStackTrace" -> ((Trace) receiver).frames().clone();
                case "java/lang/StackTraceElement.getClassName" -> ((StackTraceElement) receiver).getClassName();
                case "java/lang/StackTraceElement.getMethodName" -> ((StackTraceElement) receiver).getMethodName();
                case "java/util/Base64.getDecoder" -> Base64.getDecoder();
                case "java/util/Base64$Decoder.decode" -> ((Base64.Decoder) receiver).decode((String) args[0]);
                case "javax/crypto/Cipher.getInstance" -> {
                    if (!"AES/CBC/PKCS5Padding".equals(args[0])) throw new Refusal("cipher " + args[0]);
                    yield javax.crypto.Cipher.getInstance("AES/CBC/PKCS5Padding");
                }
                case "javax/crypto/Cipher.init" -> {
                    ((javax.crypto.Cipher) receiver).init(intValue(args[0]), (java.security.Key) args[1], (java.security.spec.AlgorithmParameterSpec) args[2]);
                    yield null;
                }
                case "javax/crypto/Cipher.doFinal" -> ((javax.crypto.Cipher) receiver).doFinal((byte[]) args[0]);
                // Ints and longs are already boxed values here: boxing and unboxing keep them.
                case "java/lang/Integer.valueOf", "java/lang/Long.valueOf" -> args[0];
                case "java/lang/Integer.intValue" -> intValue(receiver);
                case "java/lang/Long.longValue" -> longValue(receiver);
                default -> throw new Refusal("call " + call.name);
            };
        } catch (Refusal refusal) {
            throw refusal;
        } catch (Exception | Error failure) {
            throw new Refusal("call failed: " + failure);
        }
        push(stack, result, Type.getReturnType(call.desc));
    }

    private Object construct(MethodInsnNode call, Object[] args, Deque<StackTraceElement> frames) {
        try {
            return switch (call.owner + call.desc) {
                case "java/lang/String([C)V" -> new String((char[]) args[0]);
                case "java/lang/String([B)V" -> new String((byte[]) args[0], java.nio.charset.StandardCharsets.UTF_8);
                case "java/lang/String([BLjava/lang/String;)V" -> new String((byte[]) args[0], (String) args[1]);
                case "java/lang/Throwable()V" -> new Trace(frames.toArray(StackTraceElement[]::new));
                case "javax/crypto/spec/SecretKeySpec([BLjava/lang/String;)V" -> {
                    if (!"AES".equals(args[1])) throw new Refusal("key " + args[1]);
                    yield new javax.crypto.spec.SecretKeySpec((byte[]) args[0], "AES");
                }
                case "javax/crypto/spec/IvParameterSpec([B)V" -> new javax.crypto.spec.IvParameterSpec((byte[]) args[0]);
                default -> throw new Refusal("constructor " + call.owner + call.desc);
            };
        } catch (Refusal refusal) {
            throw refusal;
        } catch (Exception failure) {
            throw new Refusal("constructor failed: " + failure);
        }
    }

    private static void push(List<Object> stack, Object value, Type type) {
        if (type.getSort() == Type.VOID) return;
        if (type.getSort() == Type.CHAR && value instanceof Character c) value = (int) c;
        stack.add(value);
        if (type.getSize() == 2) stack.add(null);
    }

    private static boolean castable(Object value, String type) {
        return switch (type) {
            case "[Ljava/lang/StackTraceElement;" -> value instanceof StackTraceElement[];
            case "java/lang/String" -> value instanceof String;
            case "[Ljava/lang/String;" -> value instanceof String[];
            case "[Ljava/lang/Object;" -> value instanceof Object[];
            case "java/lang/Integer" -> value instanceof Integer;
            case "java/lang/Long" -> value instanceof Long;
            case "[Ljava/lang/Integer;" -> value instanceof Integer[];
            case "[Ljava/lang/Long;" -> value instanceof Long[];
            case "[J" -> value instanceof long[];
            case "java/lang/Class" -> value instanceof Class<?>;
            case "[Ljava/lang/Class;" -> value instanceof Object[];
            case "[I" -> value instanceof int[];
            default -> false;
        };
    }

    private static Object newArray(int kind, int length) {
        if (length < 0 || length > MAX_ARRAY) throw new Refusal("array size");
        return switch (kind) {
            case T_INT -> new int[length]; case T_CHAR -> new char[length]; case T_BYTE, T_BOOLEAN -> new byte[length];
            case T_SHORT -> new short[length]; case T_LONG -> new long[length];
            default -> throw new Refusal("array kind " + kind);
        };
    }

    static int intValue(Object value) {
        if (value instanceof Integer i) return i;
        throw new Refusal("not an int: " + value);
    }
    private static long longValue(Object value) {
        if (value instanceof Long l) return l;
        throw new Refusal("not a long: " + value);
    }

    private static boolean branch(int op, List<Object> stack) {
        return switch (op) {
            case GOTO -> true;
            case IFEQ, IFNE, IFLT, IFGE, IFGT, IFLE -> {
                int v = intValue(stack.removeLast());
                yield switch (op) { case IFEQ -> v == 0; case IFNE -> v != 0; case IFLT -> v < 0; case IFGE -> v >= 0; case IFGT -> v > 0; default -> v <= 0; };
            }
            case IF_ICMPEQ, IF_ICMPNE, IF_ICMPLT, IF_ICMPGE, IF_ICMPGT, IF_ICMPLE -> {
                int b = intValue(stack.removeLast()), a = intValue(stack.removeLast());
                yield switch (op) { case IF_ICMPEQ -> a == b; case IF_ICMPNE -> a != b; case IF_ICMPLT -> a < b; case IF_ICMPGE -> a >= b; case IF_ICMPGT -> a > b; default -> a <= b; };
            }
            case IFNULL -> stack.removeLast() == null;
            case IFNONNULL -> stack.removeLast() != null;
            case IF_ACMPEQ, IF_ACMPNE -> { Object b = stack.removeLast(), a = stack.removeLast(); yield (a == b) == (op == IF_ACMPEQ); }
            default -> throw new Refusal("jump " + op);
        };
    }

    /** Zero-operand instructions: constants, arithmetic, conversions, arrays and the stack shuffles (wide values take two slots). */
    private static void simple(int op, List<Object> stack) {
        switch (op) {
            case NOP -> { }
            case ACONST_NULL -> stack.add(null);
            case ICONST_M1, ICONST_0, ICONST_1, ICONST_2, ICONST_3, ICONST_4, ICONST_5 -> stack.add(op - ICONST_0);
            case LCONST_0, LCONST_1 -> { stack.add((long) (op - LCONST_0)); stack.add(null); }
            case IADD, ISUB, IMUL, IDIV, IREM, ISHL, ISHR, IUSHR, IAND, IOR, IXOR -> {
                int b = intValue(stack.removeLast()), a = intValue(stack.removeLast());
                Integer r = Interpret.binaryInt(op, a, b);
                if (r == null) throw new Refusal("division by zero");
                stack.add(r);
            }
            case LADD, LSUB, LMUL, LAND, LOR, LXOR -> {
                stack.removeLast(); long b = longValue(stack.removeLast()); stack.removeLast(); long a = longValue(stack.removeLast());
                stack.add(switch (op) { case LADD -> a + b; case LSUB -> a - b; case LMUL -> a * b; case LAND -> a & b; case LOR -> a | b; default -> a ^ b; });
                stack.add(null);
            }
            case LSHL, LSHR, LUSHR -> {
                int s = intValue(stack.removeLast()); stack.removeLast(); long a = longValue(stack.removeLast());
                stack.add(switch (op) { case LSHL -> a << s; case LSHR -> a >> s; default -> a >>> s; }); stack.add(null);
            }
            case LCMP -> {
                stack.removeLast(); long b = longValue(stack.removeLast()); stack.removeLast(); long a = longValue(stack.removeLast());
                stack.add(Long.compare(a, b));
            }
            case LNEG -> { stack.removeLast(); long a = longValue(stack.removeLast()); stack.add(-a); stack.add(null); }
            case LALOAD -> {
                int i = intValue(stack.removeLast()); Object array = array(stack.removeLast());
                check(array, i);
                if (!(array instanceof long[] a)) throw new Refusal("array load");
                stack.add(a[i]); stack.add(null);
            }
            case LASTORE -> {
                stack.removeLast(); long value = longValue(stack.removeLast()); int i = intValue(stack.removeLast()); Object array = array(stack.removeLast());
                check(array, i);
                if (!(array instanceof long[] a)) throw new Refusal("array store");
                a[i] = value;
            }
            case INEG -> stack.add(-intValue(stack.removeLast()));
            case I2B -> stack.add((int) (byte) intValue(stack.removeLast()));
            case I2C -> stack.add((int) (char) intValue(stack.removeLast()));
            case I2S -> stack.add((int) (short) intValue(stack.removeLast()));
            case I2L -> { stack.add((long) intValue(stack.removeLast())); stack.add(null); }
            case L2I -> { stack.removeLast(); stack.add((int) longValue(stack.removeLast())); }
            case ARRAYLENGTH -> stack.add(java.lang.reflect.Array.getLength(array(stack.removeLast())));
            case IALOAD, BALOAD, CALOAD, SALOAD, AALOAD -> {
                int i = intValue(stack.removeLast()); Object array = array(stack.removeLast());
                check(array, i);
                stack.add(switch (array) {
                    case int[] a -> a[i]; case byte[] a -> (int) a[i]; case char[] a -> (int) a[i]; case short[] a -> (int) a[i];
                    case Object[] a -> a[i];
                    default -> throw new Refusal("array load");
                });
            }
            case IASTORE, BASTORE, CASTORE, SASTORE, AASTORE -> {
                Object value = stack.removeLast(); int i = intValue(stack.removeLast()); Object array = array(stack.removeLast());
                check(array, i);
                switch (array) {
                    case int[] a -> a[i] = intValue(value); case byte[] a -> a[i] = (byte) intValue(value);
                    case char[] a -> a[i] = (char) intValue(value); case short[] a -> a[i] = (short) intValue(value);
                    case Object[] a -> {
                        if (value != null && !a.getClass().getComponentType().isInstance(value)) throw new Refusal("array store type");
                        a[i] = value;
                    }
                    default -> throw new Refusal("array store");
                }
            }
            case POP -> stack.removeLast();
            case POP2 -> { stack.removeLast(); stack.removeLast(); }
            case DUP -> stack.add(stack.getLast());
            case DUP_X1 -> { Object a = stack.removeLast(), b = stack.removeLast(); stack.add(a); stack.add(b); stack.add(a); }
            case DUP_X2 -> { Object a = stack.removeLast(), b = stack.removeLast(), c = stack.removeLast(); stack.add(a); stack.add(c); stack.add(b); stack.add(a); }
            case DUP2 -> { Object a = stack.get(stack.size() - 1), b = stack.get(stack.size() - 2); stack.add(b); stack.add(a); }
            case DUP2_X1 -> { Object a = stack.removeLast(), b = stack.removeLast(), c = stack.removeLast(); stack.add(b); stack.add(a); stack.add(c); stack.add(b); stack.add(a); }
            case DUP2_X2 -> {
                Object a = stack.removeLast(), b = stack.removeLast(), c = stack.removeLast(), d = stack.removeLast();
                stack.add(b); stack.add(a); stack.add(d); stack.add(c); stack.add(b); stack.add(a);
            }
            case SWAP -> { Object a = stack.removeLast(), b = stack.removeLast(); stack.add(a); stack.add(b); }
            default -> throw new Refusal("opcode " + op);
        }
    }

    private static Object array(Object value) {
        if (value == null || !value.getClass().isArray()) throw new Refusal("not an array");
        return value;
    }
    private static void check(Object array, int i) {
        if (i < 0 || i >= java.lang.reflect.Array.getLength(array)) throw new Refusal("index out of bounds");
    }
}
