package dev.classrevive.transforms.skid;

import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;
import java.util.*;

/**
 * Zelix KlassMaster 27 for Java 7 and later targets: a concrete, bounded interpreter for the code it adds to a jar. Its
 * class keys come from a key generator of its own (a few jar classes holding bit permutation tables, objects linked in
 * lists and maps keyed by their own {@code hashCode} and {@code equals}); its strings and numbers are decrypted with DES by
 * a static method of each class, reached through {@code invokedynamic}.
 *
 * <p>The interpreter runs the jar's bytecode on values of its own, never defining, loading or initializing a jar class:
 * ints, longs, arrays, strings, objects of jar classes ({@link Instance}, a type and a map of fields) and models of the
 * JDK classes the obfuscator's code uses ({@code ArrayList}, {@code Vector}, the hash maps, {@code StringBuilder}). The
 * JDK is otherwise reached through a closed allowlist: string operations, boxing, {@code System.arraycopy}, array
 * {@code clone()}, the DES and AES decryption of constants ({@code Cipher}, {@code SecretKeyFactory}, {@code DESKeySpec},
 * {@code SecretKeySpec}, {@code IvParameterSpec}), and {@code MethodHandles.lookup().lookupClass()} answered by a
 * symbolic class. {@code Thread.currentThread().getId()} (a cache key) is a constant.
 *
 * <p>Jar classes other than the one being reversed run only when they are <i>runtime classes</i>: classes whose every
 * instruction stays within this allowlist and other runtime classes (computed as a greatest fixed point). Their static
 * initializers run whole, in the JVM's order, a class whose initialization is in progress being used as it is.
 * Anything else, an exception thrown, an identity hash code or a budget overrun refuses the evaluation.
 */
final class ZkmRuntime implements Opcodes {
    static final long MAX_STEPS = 20_000_000L, INIT_STEPS = 2_000_000_000L;
    /** Stack for {@link #onLargeStack}: the key generator's sort recurses a few hundred interpreted calls deep. */
    static final long STACK_BYTES = 512L << 20;
    static final int MAX_DEPTH = 400, MAX_ARRAY = 1 << 20, MAX_OBJECTS = 1 << 18;

    static final class Refusal extends RuntimeException {
        Refusal(String why) { super(why, null, false, false); }
    }

    /** A class constant ({@code ldc}, {@code lookupClass()}, {@code Integer.TYPE}): an internal name or a primitive descriptor. */
    record ClassRef(String name) {}
    /** {@code MethodHandles.lookup()} in a class. */
    record LookupRef(String owner) {}
    /** An object of a jar class. Identity matters (maps and lists hold them), so it is a class, not a record. */
    static final class Instance {
        final String type;
        final Map<String,Object> fields = new HashMap<>();
        Instance(String type) { this.type = type; }
        @Override public String toString() { return "Instance(" + type + ")"; }
    }
    /** A JDK object between {@code new} and its constructor. */
    private static final class Uninitialized {
        final String type;
        Uninitialized(String type) { this.type = type; }
    }
    /** {@code Thread.currentThread()}: only its id is read, as a cache key. */
    private static final Object THREAD = new Object() { @Override public String toString() { return "Thread"; } };

    /** A {@code HashMap}, {@code ConcurrentHashMap} or {@code Hashtable}: keys compared as the JVM would, jar objects by their interpreted {@code hashCode} and {@code equals}. */
    final class ModelMap {
        final List<Object[]> entries = new ArrayList<>(); // {hash, key, value}
        int find(Object key, int hash, int depth) {
            for (int k = 0; k < entries.size(); k++) {
                Object[] entry = entries.get(k);
                if ((int) entry[0] == hash && (entry[1] == key || equal(key, entry[1], depth))) return k;
            }
            return -1;
        }
    }

    /** Where a partial initializer stopped: the first instruction of the statement holding the first foreign instruction (null: it returned). */
    record Stop(AbstractInsnNode at) {}

    private final Map<String,ClassNode> nodes;
    /** {@code owner -> name:desc -> value}. */
    final Map<String,Map<String,Object>> statics = new HashMap<>();
    /** Static fields each partial initializer wrote ({@code name:desc}). */
    final Map<String,Set<String>> written = new HashMap<>();
    private final Map<String,Integer> initState = new HashMap<>(); // 1 running, 2 done
    private final Set<String> runtimeClasses;
    private final Map<MethodNode,Code> code = new IdentityHashMap<>();
    private long steps, limit = MAX_STEPS;
    private int objects;

    private record Code(AbstractInsnNode[] insns, Map<AbstractInsnNode,Integer> index) {}

    ZkmRuntime(Map<String,ClassNode> nodes) {
        this.nodes = nodes;
        this.runtimeClasses = runtimeClasses(nodes);
    }

    Set<String> runtimeClasses() { return runtimeClasses; }

    /**
     * Runs {@code work} on a thread of its own with a large stack (the caller waits), so the interpreter's recursion
     * never depends on the calling thread's stack size; a stack overflow there becomes a {@link Refusal}.
     */
    static void onLargeStack(Runnable work) {
        var failure = new Throwable[1];
        var thread = new Thread(null, () -> {
            try { work.run(); }
            catch (StackOverflowError overflow) { failure[0] = new Refusal("stack overflow"); }
            catch (RuntimeException | Error error) { failure[0] = error; }
        }, "zkm-runtime", STACK_BYTES);
        thread.setDaemon(true);
        thread.start();
        try { thread.join(); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new Refusal("interrupted"); }
        if (failure[0] instanceof RuntimeException runtime) throw runtime;
        if (failure[0] instanceof Error error) throw error;
    }

    // ------------------------------------------------------------------ entry points

    /**
     * Runs {@code owner}'s {@code <clinit>} from its first instruction until the program's own code starts: an instruction
     * reaching a class that is neither the owner, a runtime class nor allowlisted JDK code, or an {@code invokedynamic}, a
     * throw or a monitor. The owner's statics are then those its obfuscator initializer set.
     */
    Stop initialize(ClassNode owner) {
        if (stops.containsKey(owner.name)) {
            Stop stop = stops.get(owner.name);
            if (stop == null) throw new Refusal(refusals.get(owner.name));
            return stop;
        }
        // The JVM initializes the super class first, and Zelix KlassMaster chains the class keys along that order: a
        // super class's key generator call leaves the entry its subclass's call looks up.
        String parent = owner.superName;
        if (parent != null && nodes.containsKey(parent) && !initState.containsKey(parent)) {
            if (runtimeClasses.contains(parent)) {
                try { ensureInitialized(parent, 0); } catch (Refusal ignored) { /* reported where it is used */ }
            } else {
                try { initialize(nodes.get(parent)); } catch (Refusal ignored) { /* reported for the super class itself */ }
            }
        }
        try {
            Stop stop = initializeOwn(owner);
            stops.put(owner.name, stop);
            return stop;
        } catch (Refusal refusal) {
            stops.put(owner.name, null);
            refusals.put(owner.name, refusal.getMessage());
            throw refusal;
        }
    }

    /** Where the partial initializer of {@code owner} stopped; null when it was not run or was refused. */
    Stop stop(String owner) { return stops.get(owner); }

    private final Map<String,Stop> stops = new HashMap<>();
    private final Map<String,String> refusals = new HashMap<>();

    private Stop initializeOwn(ClassNode owner) {
        steps = 0;
        MethodNode clinit = method(owner, "<clinit>", "()V");
        defaults(owner);
        initState.put(owner.name, 2); // its own code may read its fields; the JVM sees it as being initialized
        written.put(owner.name, new HashSet<>());
        if (clinit == null || clinit.instructions.size() == 0) return new Stop(null);
        return (Stop) execute(owner.name, clinit, new Object[Math.max(clinit.maxLocals, 1)], 0, true);
    }

    /** Calls a static method of {@code owner} on constant arguments. */
    Object call(String owner, MethodNode method, Object[] args) {
        steps = 0;
        if ((method.access & ACC_STATIC) == 0) throw new Refusal("instance method");
        return invoke(owner, method, null, args, 0);
    }

    // ------------------------------------------------------------------ runtime classes

    private static final Set<String> MODELLED = Set.of("java/lang/Object", "java/lang/String", "java/lang/StringBuilder",
        "java/lang/Integer", "java/lang/Long", "java/lang/Short", "java/lang/Byte", "java/lang/Character", "java/lang/Boolean",
        "java/lang/System", "java/lang/Math", "java/lang/Thread", "java/util/ArrayList", "java/util/Vector", "java/util/List",
        "java/util/Collection", "java/util/AbstractList", "java/util/HashMap", "java/util/Map", "java/util/Hashtable",
        "java/util/concurrent/ConcurrentHashMap", "java/util/AbstractMap", "javax/crypto/Cipher", "javax/crypto/SecretKeyFactory",
        "javax/crypto/SecretKey", "javax/crypto/spec/DESKeySpec", "javax/crypto/spec/SecretKeySpec", "javax/crypto/spec/IvParameterSpec",
        "java/security/Key", "java/security/spec/KeySpec", "java/security/spec/AlgorithmParameterSpec",
        "java/lang/invoke/MethodHandles", "java/lang/invoke/MethodHandles$Lookup", "java/lang/Class");

    /**
     * JDK members a runtime class may name though the interpreter does not run them: {@code Object}'s methods (answered
     * by the class's own overrides or refused), and the identity hash a key generator's comparator holds for objects it
     * never compares. Reaching one that has no answer refuses the evaluation.
     */
    private static final Set<String> TOLERATED = Set.of("java/lang/Object.hashCode()I", "java/lang/Object.equals(Ljava/lang/Object;)Z",
        "java/lang/Object.clone()Ljava/lang/Object;", "java/lang/System.identityHashCode(Ljava/lang/Object;)I");

    /**
     * The jar classes whose code only uses what this interpreter runs: no native method, no {@code invokedynamic}, and
     * every class it names is modelled JDK code or another runtime class. A greatest fixed point over the jar.
     */
    static Set<String> runtimeClasses(Map<String,ClassNode> nodes) {
        var candidates = new HashSet<>(nodes.keySet());
        boolean changed = true;
        while (changed) {
            changed = false;
            for (var it = candidates.iterator(); it.hasNext(); ) {
                var node = nodes.get(it.next());
                if (!runtimeClass(node, candidates)) { it.remove(); changed = true; }
            }
        }
        return Set.copyOf(candidates);
    }

    private static boolean runtimeClass(ClassNode node, Set<String> candidates) {
        if (node.superName != null && !node.superName.equals("java/lang/Object") && !candidates.contains(node.superName)) return false;
        for (String type : node.interfaces) if (!candidates.contains(type)) return false;
        for (var method : node.methods) {
            if ((method.access & ACC_NATIVE) != 0) return false;
            for (var insn : method.instructions) {
                // A JDK member must be one the interpreter runs, not only of a class it models.
                if (insn instanceof MethodInsnNode call && !candidates.contains(call.owner) && !allowed(call) && !TOLERATED.contains(call.owner + "." + call.name + call.desc))
                    return false;
                if (insn instanceof FieldInsnNode field && !candidates.contains(field.owner) && !PRIMITIVES.containsKey(field.owner + "." + field.name)) return false;
                String type = switch (insn) {
                    case MethodInsnNode call -> call.owner;
                    case FieldInsnNode field -> PRIMITIVES.containsKey(field.owner + "." + field.name) ? null : field.owner;
                    case TypeInsnNode t -> t.desc;
                    case InvokeDynamicInsnNode d -> "invokedynamic";
                    case MultiANewArrayInsnNode m -> "multianewarray";
                    case LdcInsnNode ldc when ldc.cst instanceof Type t && t.getSort() == Type.OBJECT -> t.getInternalName();
                    case LdcInsnNode ldc when ldc.cst instanceof Handle || ldc.cst instanceof Type -> "handle";
                    default -> null;
                };
                if (type == null) continue;
                while (type.startsWith("[")) type = type.substring(1);
                if (type.length() == 1) continue; // primitive array
                if (type.startsWith("L") && type.endsWith(";")) type = type.substring(1, type.length() - 1);
                if (!candidates.contains(type) && !MODELLED.contains(type)) return false;
            }
        }
        return true;
    }

    // ------------------------------------------------------------------ classes and methods

    private MethodNode method(ClassNode node, String name, String desc) {
        for (var m : node.methods) if (m.name.equals(name) && m.desc.equals(desc)) return m;
        return null;
    }

    private Code code(MethodNode method) {
        return code.computeIfAbsent(method, m -> {
            var insns = m.instructions.toArray();
            var index = new IdentityHashMap<AbstractInsnNode,Integer>();
            for (int i = 0; i < insns.length; i++) index.put(insns[i], i);
            return new Code(insns, index);
        });
    }

    private void defaults(ClassNode node) {
        var map = statics.computeIfAbsent(node.name, k -> new HashMap<>());
        for (var field : node.fields)
            if ((field.access & ACC_STATIC) != 0)
                map.putIfAbsent(field.name + ":" + field.desc, field.value != null ? field.value : zero(field.desc));
    }

    private static Object zero(String desc) {
        return switch (desc.charAt(0)) {
            case 'J' -> 0L; case 'F' -> 0f; case 'D' -> 0d; case 'L', '[' -> null; default -> 0;
        };
    }

    /** Initializes a runtime class the way the JVM would (its super class first); a class already being initialized is used as it is. */
    private void ensureInitialized(String type, int depth) {
        Integer state = initState.get(type);
        if (state != null) {
            if (state < 0) throw new Refusal("initializer of " + type + " refused earlier");
            return;
        }
        var node = nodes.get(type);
        if (node == null) return;
        if (!runtimeClasses.contains(type)) throw new Refusal("class " + type + " is not the obfuscator's");
        initState.put(type, 1);
        // A key generator sorts and links thousands of its objects once per jar: its own, larger budget.
        long saved = steps, savedLimit = limit;
        boolean outer = limit != INIT_STEPS;
        if (outer) { steps = 0; limit = INIT_STEPS; }
        try {
            if (node.superName != null && nodes.containsKey(node.superName)) ensureInitialized(node.superName, depth);
            defaults(node);
            MethodNode clinit = method(node, "<clinit>", "()V");
            if (clinit != null && clinit.instructions.size() > 0) invoke(type, clinit, null, new Object[0], depth + 1);
            initState.put(type, 2);
        } catch (Refusal refusal) {
            initState.put(type, -1);
            throw refusal;
        } finally {
            if (outer) { steps = saved; limit = savedLimit; }
        }
    }

    private boolean isJar(String type) { return nodes.containsKey(type); }

    /** Whether a jar class (or interface) is {@code target} or below it. */
    private boolean subtype(String type, String target) {
        var pending = new ArrayDeque<String>(List.of(type));
        var seen = new HashSet<String>();
        while (!pending.isEmpty()) {
            String t = pending.pop();
            if (t.equals(target)) return true;
            if (!seen.add(t)) continue;
            var node = nodes.get(t);
            if (node == null) continue;
            if (node.superName != null) pending.push(node.superName);
            pending.addAll(node.interfaces);
        }
        return target.equals("java/lang/Object");
    }

    /** The method a virtual call on an object of {@code type} reaches: its class or the nearest super class declaring it with code. */
    private Map.Entry<String,MethodNode> virtual(String type, String name, String desc) {
        for (String t = type; t != null; ) {
            var node = nodes.get(t);
            if (node == null) return null;
            var m = method(node, name, desc);
            if (m != null && (m.access & (ACC_ABSTRACT | ACC_STATIC)) == 0) return Map.entry(t, m);
            t = node.superName;
        }
        return null;
    }

    // ------------------------------------------------------------------ execution

    private Object invoke(String owner, MethodNode method, Object receiver, Object[] args, int depth) {
        if (depth > MAX_DEPTH) throw new Refusal("call depth");
        if ((method.access & ACC_NATIVE) != 0) throw new Refusal("native method " + owner + "." + method.name);
        if (method.instructions.size() == 0) throw new Refusal("no code: " + owner + "." + method.name);
        var locals = new Object[Math.max(method.maxLocals, 1) + 2];
        int slot = 0;
        if ((method.access & ACC_STATIC) == 0) locals[slot++] = receiver;
        var types = Type.getArgumentTypes(method.desc);
        for (int i = 0; i < types.length; i++) { locals[slot] = args[i]; slot += types[i].getSize(); }
        try {
            return execute(owner, method, locals, depth, false);
        } catch (Refusal refusal) {
            // Where it happened, innermost method first (bounded, for the report).
            String why = refusal.getMessage();
            if (why.length() < 300) why += " < " + owner + "." + method.name;
            throw new Refusal(why);
        }
    }

    private Object execute(String owner, MethodNode method, Object[] locals, int depth, boolean initializer) {
        var c = code(method);
        var insns = c.insns();
        var stack = new ArrayList<Object>();
        int pc = 0;
        AbstractInsnNode statement = null;
        while (true) {
            if (++steps > limit) throw new Refusal("budget");
            if (pc >= insns.length) throw new Refusal("fell off the code");
            var insn = insns[pc];
            int op = insn.getOpcode();
            if (op < 0) { pc++; continue; }
            if (initializer) {
                if (stack.isEmpty()) {
                    statement = insn;
                }
                if (foreign(owner, insn, new HashSet<>())) return new Stop(statement);
            }
            int next = pc + 1;
            switch (insn) {
                case InsnNode plain -> {
                    if (op >= IRETURN && op <= ARETURN) {
                        Object value = stack.removeLast();
                        if (op == LRETURN || op == DRETURN) value = stack.removeLast();
                        return value;
                    }
                    if (op == RETURN) {
                        if (!initializer) return null;
                        return new Stop(null);
                    }
                    if (op == ATHROW) throw new Refusal("throw");
                    if (op == MONITORENTER || op == MONITOREXIT) { stack.removeLast(); break; }
                    simple(op, stack);
                }
                case IntInsnNode i -> {
                    if (op == NEWARRAY) stack.add(newArray(i.operand, intValue(stack.removeLast())));
                    else stack.add(i.operand);
                }
                case LdcInsnNode ldc -> {
                    Object cst = ldc.cst;
                    if (cst instanceof Integer || cst instanceof String || cst instanceof Float) stack.add(cst);
                    else if (cst instanceof Long || cst instanceof Double) { stack.add(cst); stack.add(null); }
                    else if (cst instanceof Type t && (t.getSort() == Type.OBJECT || t.getSort() == Type.ARRAY)) stack.add(new ClassRef(t.getInternalName()));
                    else throw new Refusal("ldc " + cst);
                }
                case VarInsnNode var -> {
                    if (op >= ILOAD && op <= ALOAD) { stack.add(locals[var.var]); if (op == LLOAD || op == DLOAD) stack.add(null); }
                    else if (op >= ISTORE && op <= ASTORE) {
                        if (op == LSTORE || op == DSTORE) stack.removeLast();
                        locals[var.var] = stack.removeLast();
                    } else throw new Refusal("ret");
                }
                case IincInsnNode inc -> locals[inc.var] = intValue(locals[inc.var]) + inc.incr;
                case JumpInsnNode jump -> {
                    if (op == JSR) throw new Refusal("jsr");
                    if (branch(op, stack, depth)) next = c.index().get(jump.label);
                }
                case TableSwitchInsnNode table -> {
                    int key = intValue(stack.removeLast());
                    next = c.index().get(key >= table.min && key <= table.max ? table.labels.get(key - table.min) : table.dflt);
                }
                case LookupSwitchInsnNode lookup -> {
                    int at = lookup.keys.indexOf(intValue(stack.removeLast()));
                    next = c.index().get(at >= 0 ? lookup.labels.get(at) : lookup.dflt);
                }
                case FieldInsnNode field -> field(owner, field, stack, initializer, depth);
                case TypeInsnNode type -> type(type, stack);
                case MethodInsnNode call -> invokeInsn(owner, call, stack, locals, depth);
                default -> throw new Refusal("instruction " + op);
            }
            pc = next;
        }
    }

    /** Whether an initializer instruction is the program's own (it ends the obfuscator's part of {@code <clinit>}). */
    private boolean foreign(String owner, AbstractInsnNode insn, Set<String> seen) {
        switch (insn) {
            case FieldInsnNode field -> {
                if (PRIMITIVES.containsKey(field.owner + "." + field.name)) return false;
                return !field.owner.equals(owner) && !runtimeClasses.contains(field.owner);
            }
            case MethodInsnNode call -> {
                if (call.owner.equals(owner)) {
                    if (call.getOpcode() != INVOKESTATIC) return true;
                    return !helper(owner, call, seen);
                }
                if (runtimeClasses.contains(call.owner)) return false;
                return !allowed(call);
            }
            case TypeInsnNode type -> {
                String desc = type.desc;
                while (desc.startsWith("[")) desc = desc.substring(1);
                if (desc.startsWith("L") && desc.endsWith(";")) desc = desc.substring(1, desc.length() - 1);
                if (desc.length() == 1) return false;
                if (type.getOpcode() == NEW) return !ALLOCATABLE.contains(desc) && !runtimeClasses.contains(desc);
                return isJar(desc) && !desc.equals(owner) && !runtimeClasses.contains(desc) || !isJar(desc) && !MODELLED.contains(desc);
            }
            case LdcInsnNode ldc -> {
                return ldc.cst instanceof Handle || ldc.cst instanceof Type t && (t.getSort() == Type.METHOD
                    || t.getSort() == Type.OBJECT && !t.getInternalName().equals(owner) && !runtimeClasses.contains(t.getInternalName()));
            }
            default -> {
                int op = insn.getOpcode();
                return op == ATHROW || op == MONITORENTER || op == MONITOREXIT || op == JSR || insn instanceof InvokeDynamicInsnNode
                    || insn instanceof MultiANewArrayInsnNode;
            }
        }
    }

    /** A static method of the owner an initializer calls (a string table decoder): its code is the obfuscator's too. */
    private boolean helper(String owner, MethodInsnNode call, Set<String> seen) {
        String key = call.name + call.desc;
        if (!seen.add(key)) return true;
        if (seen.size() > 16) return false;
        var target = method(nodes.get(owner), call.name, call.desc);
        if (target == null || (target.access & (ACC_STATIC | ACC_NATIVE)) != ACC_STATIC || target.instructions.size() == 0) return false;
        for (var insn : target.instructions) if (insn.getOpcode() >= 0 && foreign(owner, insn, seen)) return false;
        return true;
    }

    private void field(String owner, FieldInsnNode field, List<Object> stack, boolean initializer, int depth) {
        int op = field.getOpcode();
        int size = Type.getType(field.desc).getSize();
        String key = field.name + ":" + field.desc;
        switch (op) {
            case GETSTATIC, PUTSTATIC -> {
                if (op == GETSTATIC && PRIMITIVES.containsKey(field.owner + "." + field.name)) { stack.add(PRIMITIVES.get(field.owner + "." + field.name)); return; }
                String declaring = staticOwner(field.owner, key);
                if (declaring == null) throw new Refusal("field " + field.owner + "." + field.name);
                if (!declaring.equals(owner) || !initState.containsKey(declaring)) ensureInitialized(declaring, depth);
                var map = statics.computeIfAbsent(declaring, k -> new HashMap<>());
                if (op == GETSTATIC) {
                    if (!map.containsKey(key)) throw new Refusal("unset field " + key);
                    stack.add(map.get(key)); if (size == 2) stack.add(null);
                } else {
                    if (size == 2) stack.removeLast();
                    map.put(key, stack.removeLast());
                    if (initializer && declaring.equals(owner)) written.get(owner).add(key);
                }
            }
            case GETFIELD -> {
                if (!(stack.removeLast() instanceof Instance object)) throw new Refusal("getfield on no jar object");
                stack.add(object.fields.containsKey(key) ? object.fields.get(key) : zero(field.desc));
                if (size == 2) stack.add(null);
            }
            default -> { // PUTFIELD
                if (size == 2) stack.removeLast();
                Object value = stack.removeLast();
                if (!(stack.removeLast() instanceof Instance object)) throw new Refusal("putfield on no jar object");
                object.fields.put(key, value);
            }
        }
    }

    /** The jar class declaring a static field named through {@code owner} (the field may be inherited). */
    private String staticOwner(String owner, String key) {
        for (String t = owner; t != null; ) {
            var node = nodes.get(t);
            if (node == null) return null;
            for (var f : node.fields) if ((f.access & ACC_STATIC) != 0 && (f.name + ":" + f.desc).equals(key)) return t;
            for (String i : node.interfaces) { String found = staticOwner(i, key); if (found != null) return found; }
            t = node.superName;
        }
        return null;
    }

    private static final Set<String> ALLOCATABLE = Set.of("java/lang/String", "java/lang/StringBuilder", "java/lang/Object",
        "java/util/ArrayList", "java/util/Vector", "java/util/HashMap", "java/util/Hashtable", "java/util/concurrent/ConcurrentHashMap",
        "javax/crypto/spec/DESKeySpec", "javax/crypto/spec/SecretKeySpec", "javax/crypto/spec/IvParameterSpec",
        "java/lang/Integer", "java/lang/Long");

    private void type(TypeInsnNode type, List<Object> stack) {
        switch (type.getOpcode()) {
            case NEW -> {
                if (++objects > MAX_OBJECTS) throw new Refusal("too many objects");
                if (isJar(type.desc)) {
                    if (!runtimeClasses.contains(type.desc)) throw new Refusal("new " + type.desc);
                    ensureInitialized(type.desc, 0);
                    stack.add(new Instance(type.desc));
                } else if (ALLOCATABLE.contains(type.desc)) stack.add(new Uninitialized(type.desc));
                else throw new Refusal("new " + type.desc);
            }
            case ANEWARRAY -> {
                int length = intValue(stack.removeLast());
                if (length < 0 || length > MAX_ARRAY) throw new Refusal("array size");
                stack.add(type.desc.equals("java/lang/String") ? new String[length] : new Object[length]);
            }
            case CHECKCAST -> {
                Object value = stack.getLast();
                if (value != null && !instance(value, type.desc)) throw new Refusal("checkcast " + type.desc + " on " + value.getClass().getSimpleName());
            }
            case INSTANCEOF -> {
                Object value = stack.removeLast();
                stack.add(value != null && instance(value, type.desc) ? 1 : 0);
            }
            default -> throw new Refusal("type op " + type.getOpcode());
        }
    }

    private boolean instance(Object value, String type) {
        if (value instanceof Instance object) return subtype(object.type, type);
        if (value instanceof Uninitialized) return false;
        return switch (type) {
            case "java/lang/Object" -> true;
            case "java/lang/String", "java/lang/CharSequence" -> value instanceof String;
            case "java/lang/Integer" -> value instanceof Integer;
            case "java/lang/Long" -> value instanceof Long;
            case "java/lang/Number" -> value instanceof Integer || value instanceof Long;
            case "java/lang/Class" -> value instanceof ClassRef;
            case "java/util/ArrayList", "java/util/List", "java/util/Collection", "java/util/AbstractList", "java/util/RandomAccess" ->
                value instanceof ArrayList<?> || value instanceof Vector<?> && !type.equals("java/util/ArrayList");
            case "java/util/Vector" -> value instanceof Vector<?>;
            case "java/util/Map", "java/util/HashMap", "java/util/Hashtable", "java/util/concurrent/ConcurrentHashMap", "java/util/AbstractMap" -> value instanceof ModelMap;
            case "javax/crypto/Cipher" -> value instanceof javax.crypto.Cipher;
            case "javax/crypto/SecretKeyFactory" -> value instanceof javax.crypto.SecretKeyFactory;
            case "javax/crypto/SecretKey", "java/security/Key" -> value instanceof javax.crypto.SecretKey;
            case "javax/crypto/spec/IvParameterSpec", "java/security/spec/AlgorithmParameterSpec" -> value instanceof javax.crypto.spec.IvParameterSpec;
            case "[I" -> value instanceof int[]; case "[J" -> value instanceof long[]; case "[B", "[Z" -> value instanceof byte[];
            case "[C" -> value instanceof char[]; case "[S" -> value instanceof short[];
            default -> type.startsWith("[") && !type.startsWith("[[") && value instanceof Object[] array && arrayHolds(array, type.substring(1));
        };
    }

    private boolean arrayHolds(Object[] array, String element) {
        if (element.equals("Ljava/lang/String;")) return array instanceof String[] || Arrays.stream(array).allMatch(v -> v == null || v instanceof String);
        if (!element.startsWith("L")) return false;
        String type = element.substring(1, element.length() - 1);
        for (Object v : array) if (v != null && !instance(v, type)) return false;
        return true;
    }

    // ------------------------------------------------------------------ calls

    private static boolean allowed(MethodInsnNode call) {
        String member = call.name + call.desc;
        return switch (call.owner) {
            case "java/lang/Object" -> member.equals("<init>()V");
            case "java/lang/String" -> Set.of("charAt(I)C", "getBytes(Ljava/lang/String;)[B", "hashCode()I", "intern()Ljava/lang/String;",
                "length()I", "substring(II)Ljava/lang/String;", "substring(I)Ljava/lang/String;", "toCharArray()[C", "<init>([C)V",
                "<init>([CII)V", "<init>([BLjava/lang/String;)V", "<init>([B)V", "indexOf(I)I", "indexOf(II)I", "equals(Ljava/lang/Object;)Z",
                "valueOf([C)Ljava/lang/String;", "valueOf(I)Ljava/lang/String;").contains(member);
            case "java/lang/StringBuilder" -> Set.of("<init>()V", "<init>(Ljava/lang/String;)V", "append(Ljava/lang/String;)Ljava/lang/StringBuilder;",
                "append(C)Ljava/lang/StringBuilder;", "append(I)Ljava/lang/StringBuilder;", "toString()Ljava/lang/String;", "length()I").contains(member);
            case "java/lang/Integer" -> Set.of("valueOf(I)Ljava/lang/Integer;", "intValue()I", "<init>(I)V").contains(member);
            case "java/lang/Long" -> Set.of("valueOf(J)Ljava/lang/Long;", "longValue()J", "<init>(J)V", "parseLong(Ljava/lang/String;I)J").contains(member);
            case "java/lang/System" -> member.equals("arraycopy(Ljava/lang/Object;ILjava/lang/Object;II)V");
            case "java/lang/Thread" -> Set.of("currentThread()Ljava/lang/Thread;", "getId()J").contains(member);
            case "java/util/ArrayList", "java/util/Vector", "java/util/List", "java/util/Collection", "java/util/AbstractList" ->
                Set.of("<init>()V", "<init>(I)V", "<init>(Ljava/util/Collection;)V", "add(Ljava/lang/Object;)Z", "get(I)Ljava/lang/Object;",
                    "set(ILjava/lang/Object;)Ljava/lang/Object;", "size()I", "isEmpty()Z", "clear()V", "addElement(Ljava/lang/Object;)V",
                    "elementAt(I)Ljava/lang/Object;").contains(member);
            case "java/util/HashMap", "java/util/Hashtable", "java/util/concurrent/ConcurrentHashMap", "java/util/Map", "java/util/AbstractMap" ->
                Set.of("<init>()V", "<init>(I)V", "get(Ljava/lang/Object;)Ljava/lang/Object;", "put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;",
                    "containsKey(Ljava/lang/Object;)Z", "size()I", "isEmpty()Z", "clear()V").contains(member);
            case "javax/crypto/Cipher" -> Set.of("getInstance(Ljava/lang/String;)Ljavax/crypto/Cipher;", "doFinal([B)[B",
                "init(ILjava/security/Key;Ljava/security/spec/AlgorithmParameterSpec;)V").contains(member);
            case "javax/crypto/SecretKeyFactory" -> Set.of("getInstance(Ljava/lang/String;)Ljavax/crypto/SecretKeyFactory;",
                "generateSecret(Ljava/security/spec/KeySpec;)Ljavax/crypto/SecretKey;").contains(member);
            case "javax/crypto/spec/DESKeySpec", "javax/crypto/spec/IvParameterSpec" -> member.equals("<init>([B)V");
            case "javax/crypto/spec/SecretKeySpec" -> member.equals("<init>([BLjava/lang/String;)V");
            case "java/lang/invoke/MethodHandles" -> member.equals("lookup()Ljava/lang/invoke/MethodHandles$Lookup;");
            case "java/lang/invoke/MethodHandles$Lookup" -> member.equals("lookupClass()Ljava/lang/Class;");
            default -> call.owner.startsWith("[") && member.equals("clone()Ljava/lang/Object;");
        };
    }

    private void invokeInsn(String owner, MethodInsnNode call, List<Object> stack, Object[] locals, int depth) {
        var types = Type.getArgumentTypes(call.desc);
        var args = new Object[types.length];
        for (int k = types.length - 1; k >= 0; k--) { if (types[k].getSize() == 2) stack.removeLast(); args[k] = stack.removeLast(); }
        Type result = Type.getReturnType(call.desc);
        int op = call.getOpcode();
        Object receiver = op == INVOKESTATIC ? null : stack.removeLast();

        // The jar's own code: the class being reversed or a runtime class.
        if (isJar(call.owner)) {
            if (!call.owner.equals(owner) && !runtimeClasses.contains(call.owner)) throw new Refusal("call " + call.owner + "." + call.name);
            String target = call.owner;
            MethodNode method;
            if (op == INVOKESTATIC) {
                method = method(nodes.get(target), call.name, call.desc);
                if (method == null) throw new Refusal("missing " + call.owner + "." + call.name);
                if (!target.equals(owner) || !initState.containsKey(target)) ensureInitialized(target, depth);
            } else if (op == INVOKESPECIAL) {
                if (!(receiver instanceof Instance)) throw new Refusal("invokespecial on no jar object");
                method = method(nodes.get(target), call.name, call.desc);
                if (method == null) {
                    var found = virtual(target, call.name, call.desc);
                    if (found == null) {
                        if (call.name.equals("<init>")) throw new Refusal("missing constructor " + target);
                        throw new Refusal("missing " + call.owner + "." + call.name);
                    }
                    target = found.getKey(); method = found.getValue();
                }
            } else {
                if (!(receiver instanceof Instance object)) throw new Refusal("virtual call on " + receiver);
                var found = virtual(object.type, call.name, call.desc);
                if (found == null) { push(stack, objectMethod(object, call, args, depth), result); return; }
                target = found.getKey(); method = found.getValue();
            }
            push(stack, invoke(target, method, receiver, args, depth + 1), result);
            return;
        }
        if (call.owner.equals("java/lang/Object") && call.name.equals("clone") && call.desc.equals("()Ljava/lang/Object;")
            && receiver != null && receiver.getClass().isArray()) { push(stack, clone(receiver), result); return; }
        if (receiver instanceof Instance object) { // a JDK method on a jar object: only Object's
            if (call.name.equals("<init>") && call.owner.equals("java/lang/Object")) return;
            var found = virtual(object.type, call.name, call.desc);
            if (found != null && op != INVOKESPECIAL) { push(stack, invoke(found.getKey(), found.getValue(), receiver, args, depth + 1), result); return; }
            push(stack, objectMethod(object, call, args, depth), result);
            return;
        }
        if (!allowed(call)) throw new Refusal("call " + call.owner + "." + call.name + call.desc);
        if (call.name.equals("<init>")) {
            if (!(receiver instanceof Uninitialized target)) throw new Refusal("constructor on " + receiver);
            Object built = construct(call, target, args);
            for (int k = 0; k < stack.size(); k++) if (stack.get(k) == target) stack.set(k, built);
            for (int k = 0; k < locals.length; k++) if (locals[k] == target) locals[k] = built;
            return;
        }
        if (op != INVOKESTATIC && receiver == null) throw new Refusal("null receiver");
        Object value;
        try {
            value = jdk(owner, call, receiver, args, depth);
        } catch (Refusal refusal) {
            throw refusal;
        } catch (Exception | Error failure) {
            throw new Refusal("call failed: " + failure);
        }
        push(stack, value, result);
    }

    /** {@code hashCode}, {@code equals} or {@code toString} a jar class inherits from {@code Object}. */
    private Object objectMethod(Instance object, MethodInsnNode call, Object[] args, int depth) {
        return switch (call.name + call.desc) {
            case "equals(Ljava/lang/Object;)Z" -> object == args[0] ? 1 : 0;
            case "hashCode()I" -> throw new Refusal("identity hash code");
            default -> throw new Refusal("call " + call.owner + "." + call.name + " on " + object.type);
        };
    }

    private Object construct(MethodInsnNode call, Uninitialized target, Object[] args) {
        try {
            return switch (call.owner + call.desc) {
                case "java/lang/String([C)V" -> new String((char[]) args[0]);
                case "java/lang/String([CII)V" -> new String((char[]) args[0], intValue(args[1]), intValue(args[2]));
                case "java/lang/String([B)V" -> new String((byte[]) args[0], java.nio.charset.StandardCharsets.UTF_8);
                case "java/lang/String([BLjava/lang/String;)V" -> new String((byte[]) args[0], charset((String) args[1]));
                case "java/lang/StringBuilder()V" -> new StringBuilder();
                case "java/lang/StringBuilder(Ljava/lang/String;)V" -> new StringBuilder((String) args[0]);
                case "java/lang/Object()V" -> new Object();
                case "java/lang/Integer(I)V" -> intValue(args[0]);
                case "java/lang/Long(J)V" -> longValue(args[0]);
                case "java/util/ArrayList()V", "java/util/ArrayList(I)V" -> new ArrayList<Object>();
                case "java/util/ArrayList(Ljava/util/Collection;)V" -> new ArrayList<Object>(list(args[0]));
                case "java/util/Vector()V", "java/util/Vector(I)V" -> new Vector<Object>();
                case "java/util/Vector(Ljava/util/Collection;)V" -> new Vector<Object>(list(args[0]));
                case "java/util/HashMap()V", "java/util/HashMap(I)V", "java/util/Hashtable()V", "java/util/Hashtable(I)V",
                     "java/util/concurrent/ConcurrentHashMap()V", "java/util/concurrent/ConcurrentHashMap(I)V" -> new ModelMap();
                case "javax/crypto/spec/DESKeySpec([B)V" -> new javax.crypto.spec.DESKeySpec((byte[]) args[0]);
                case "javax/crypto/spec/IvParameterSpec([B)V" -> new javax.crypto.spec.IvParameterSpec((byte[]) args[0]);
                case "javax/crypto/spec/SecretKeySpec([BLjava/lang/String;)V" -> {
                    String algorithm = (String) args[1];
                    if (!algorithm.equals("AES") && !algorithm.equals("DES")) throw new Refusal("key " + algorithm);
                    yield new javax.crypto.spec.SecretKeySpec((byte[]) args[0], algorithm);
                }
                default -> throw new Refusal("constructor " + call.owner + call.desc);
            };
        } catch (Refusal refusal) {
            throw refusal;
        } catch (Exception failure) {
            throw new Refusal("constructor failed: " + failure);
        }
    }

    private static java.nio.charset.Charset charset(String name) {
        return switch (name) {
            case "ISO-8859-1" -> java.nio.charset.StandardCharsets.ISO_8859_1;
            case "UTF-8", "UTF8" -> java.nio.charset.StandardCharsets.UTF_8;
            case "US-ASCII" -> java.nio.charset.StandardCharsets.US_ASCII;
            case "UTF-16", "UTF-16BE" -> java.nio.charset.StandardCharsets.UTF_16BE;
            case "UTF-16LE" -> java.nio.charset.StandardCharsets.UTF_16LE;
            default -> throw new Refusal("charset " + name);
        };
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object value) {
        if (value instanceof List<?> list) return (List<Object>) list;
        throw new Refusal("not a list");
    }

    private Object jdk(String owner, MethodInsnNode call, Object receiver, Object[] args, int depth) throws Exception {
        if (call.owner.startsWith("[")) return clone(receiver);
        String member = call.name;
        switch (call.owner) {
            case "java/lang/String" -> {
                String s = (String) receiver;
                return switch (member) {
                    case "charAt" -> (int) s.charAt(intValue(args[0]));
                    case "getBytes" -> s.getBytes(charset((String) args[0]));
                    case "hashCode" -> s.hashCode();
                    case "intern" -> s;
                    case "length" -> s.length();
                    case "substring" -> args.length == 1 ? s.substring(intValue(args[0])) : s.substring(intValue(args[0]), intValue(args[1]));
                    case "toCharArray" -> s.toCharArray();
                    case "indexOf" -> args.length == 1 ? s.indexOf(intValue(args[0])) : s.indexOf(intValue(args[0]), intValue(args[1]));
                    case "equals" -> s.equals(args[0]) ? 1 : 0;
                    case "valueOf" -> args[0] instanceof char[] chars ? String.valueOf(chars) : String.valueOf(intValue(args[0]));
                    default -> throw new Refusal("call String." + member);
                };
            }
            case "java/lang/StringBuilder" -> {
                var builder = (StringBuilder) receiver;
                return switch (call.name + call.desc) {
                    case "append(Ljava/lang/String;)Ljava/lang/StringBuilder;" -> builder.append((String) args[0]);
                    case "append(C)Ljava/lang/StringBuilder;" -> builder.append((char) intValue(args[0]));
                    case "append(I)Ljava/lang/StringBuilder;" -> builder.append(intValue(args[0]));
                    case "toString()Ljava/lang/String;" -> builder.toString();
                    case "length()I" -> builder.length();
                    default -> throw new Refusal("call StringBuilder." + member);
                };
            }
            case "java/lang/Integer", "java/lang/Long" -> {
                return switch (member) {
                    case "valueOf" -> args[0];
                    case "intValue" -> intValue(receiver);
                    case "longValue" -> longValue(receiver);
                    case "parseLong" -> Long.parseLong((String) args[0], intValue(args[1]));
                    default -> throw new Refusal("call " + call.owner + "." + member);
                };
            }
            case "java/lang/System" -> {
                Object from = array(args[0]), to = array(args[2]);
                int start = intValue(args[1]), at = intValue(args[3]), length = intValue(args[4]);
                System.arraycopy(from, start, to, at, length);
                return null;
            }
            case "java/lang/Thread" -> { return member.equals("currentThread") ? THREAD : (Object) 1L; }
            case "java/lang/invoke/MethodHandles" -> { return new LookupRef(owner); }
            case "java/lang/invoke/MethodHandles$Lookup" -> {
                if (!(receiver instanceof LookupRef lookup)) throw new Refusal("lookup");
                return new ClassRef(lookup.owner());
            }
            case "javax/crypto/Cipher" -> {
                return switch (member) {
                    case "getInstance" -> {
                        String transformation = (String) args[0];
                        if (!Set.of("DES/CBC/PKCS5Padding", "DES/CBC/NoPadding", "DES/ECB/PKCS5Padding", "DES/ECB/NoPadding", "DES",
                            "AES/CBC/PKCS5Padding").contains(transformation)) throw new Refusal("cipher " + transformation);
                        yield javax.crypto.Cipher.getInstance(transformation);
                    }
                    case "init" -> {
                        ((javax.crypto.Cipher) receiver).init(intValue(args[0]), (java.security.Key) args[1], (java.security.spec.AlgorithmParameterSpec) args[2]);
                        yield null;
                    }
                    default -> ((javax.crypto.Cipher) receiver).doFinal((byte[]) args[0]);
                };
            }
            case "javax/crypto/SecretKeyFactory" -> {
                if (member.equals("getInstance")) {
                    if (!"DES".equals(args[0])) throw new Refusal("key factory " + args[0]);
                    return javax.crypto.SecretKeyFactory.getInstance("DES");
                }
                if (!(args[0] instanceof javax.crypto.spec.DESKeySpec spec)) throw new Refusal("key spec");
                return ((javax.crypto.SecretKeyFactory) receiver).generateSecret(spec);
            }
            default -> { }
        }
        if (receiver instanceof ModelMap map) return mapCall(map, member, args, depth);
        if (receiver instanceof List<?>) {
            var list = list(receiver);
            return switch (member) {
                case "add" -> { list.add(args[0]); yield 1; }
                case "addElement" -> { list.add(args[0]); yield null; }
                case "get", "elementAt" -> list.get(intValue(args[0]));
                case "set" -> list.set(intValue(args[0]), args[1]);
                case "size" -> list.size();
                case "isEmpty" -> list.isEmpty() ? 1 : 0;
                case "clear" -> { list.clear(); yield null; }
                default -> throw new Refusal("call list." + member);
            };
        }
        throw new Refusal("call " + call.owner + "." + member + " on " + (receiver == null ? "null" : receiver.getClass().getSimpleName()));
    }

    private Object mapCall(ModelMap map, String member, Object[] args, int depth) {
        switch (member) {
            case "size" -> { return map.entries.size(); }
            case "isEmpty" -> { return map.entries.isEmpty() ? 1 : 0; }
            case "clear" -> { map.entries.clear(); return null; }
            default -> { }
        }
        Object key = args[0];
        if (key == null) throw new Refusal("null map key");
        int hash = hash(key, depth);
        int at = map.find(key, hash, depth);
        return switch (member) {
            case "get" -> at < 0 ? null : map.entries.get(at)[2];
            case "containsKey" -> at >= 0 ? 1 : 0;
            default -> { // put
                if (args[1] == null) throw new Refusal("null map value");
                if (at >= 0) { Object previous = map.entries.get(at)[2]; map.entries.get(at)[2] = args[1]; yield previous; }
                map.entries.add(new Object[] {hash, key, args[1]});
                yield null;
            }
        };
    }

    /** The hash a {@code HashMap} reads: a jar object's own {@code hashCode}, a JDK value's. */
    private int hash(Object key, int depth) {
        if (key instanceof Instance object) {
            var found = virtual(object.type, "hashCode", "()I");
            if (found == null) throw new Refusal("identity hash code");
            return intValue(invoke(found.getKey(), found.getValue(), object, new Object[0], depth + 1));
        }
        if (key instanceof Integer || key instanceof Long || key instanceof String || key instanceof ClassRef) return key.hashCode();
        throw new Refusal("map key " + key.getClass().getSimpleName());
    }

    private boolean equal(Object a, Object b, int depth) {
        if (a instanceof Instance object) {
            var found = virtual(object.type, "equals", "(Ljava/lang/Object;)Z");
            if (found == null) return a == b;
            return intValue(invoke(found.getKey(), found.getValue(), object, new Object[] {b}, depth + 1)) != 0;
        }
        return Objects.equals(a, b);
    }

    private static Object clone(Object value) {
        return switch (value) {
            case int[] a -> a.clone(); case long[] a -> a.clone(); case byte[] a -> a.clone(); case char[] a -> a.clone();
            case short[] a -> a.clone(); case Object[] a -> a.clone();
            case null, default -> throw new Refusal("clone");
        };
    }

    // ------------------------------------------------------------------ values

    static final Map<String,ClassRef> PRIMITIVES = Map.of("java/lang/Boolean.TYPE", new ClassRef("Z"), "java/lang/Byte.TYPE", new ClassRef("B"),
        "java/lang/Character.TYPE", new ClassRef("C"), "java/lang/Short.TYPE", new ClassRef("S"), "java/lang/Integer.TYPE", new ClassRef("I"),
        "java/lang/Long.TYPE", new ClassRef("J"), "java/lang/Float.TYPE", new ClassRef("F"), "java/lang/Double.TYPE", new ClassRef("D"),
        "java/lang/Void.TYPE", new ClassRef("V"));

    private static void push(List<Object> stack, Object value, Type type) {
        if (type.getSort() == Type.VOID) return;
        if (type.getSort() == Type.CHAR && value instanceof Character c) value = (int) c;
        if (type.getSort() == Type.BOOLEAN && value instanceof Boolean b) value = b ? 1 : 0;
        stack.add(value);
        if (type.getSize() == 2) stack.add(null);
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
    static long longValue(Object value) {
        if (value instanceof Long l) return l;
        throw new Refusal("not a long: " + value);
    }

    private boolean branch(int op, List<Object> stack, int depth) {
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
            case IF_ACMPEQ, IF_ACMPNE -> {
                Object b = stack.removeLast(), a = stack.removeLast();
                if (a instanceof Integer || a instanceof Long || b instanceof Integer || b instanceof Long) throw new Refusal("reference comparison of boxed values");
                yield (a == b) == (op == IF_ACMPEQ);
            }
            default -> throw new Refusal("jump " + op);
        };
    }

    /** Zero-operand instructions: constants, int and long arithmetic, conversions, arrays and the stack shuffles (wide values take two slots). */
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
            case LADD, LSUB, LMUL, LDIV, LREM, LAND, LOR, LXOR -> {
                stack.removeLast(); long b = longValue(stack.removeLast()); stack.removeLast(); long a = longValue(stack.removeLast());
                if ((op == LDIV || op == LREM) && b == 0) throw new Refusal("division by zero");
                stack.add(switch (op) { case LADD -> a + b; case LSUB -> a - b; case LMUL -> a * b; case LDIV -> a / b; case LREM -> a % b;
                    case LAND -> a & b; case LOR -> a | b; default -> a ^ b; });
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
            case INEG -> stack.add(-intValue(stack.removeLast()));
            case I2B -> stack.add((int) (byte) intValue(stack.removeLast()));
            case I2C -> stack.add((int) (char) intValue(stack.removeLast()));
            case I2S -> stack.add((int) (short) intValue(stack.removeLast()));
            case I2L -> { stack.add((long) intValue(stack.removeLast())); stack.add(null); }
            case L2I -> { stack.removeLast(); stack.add((int) longValue(stack.removeLast())); }
            case ARRAYLENGTH -> stack.add(java.lang.reflect.Array.getLength(array(stack.removeLast())));
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
