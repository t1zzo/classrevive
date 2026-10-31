package dev.classrevive.transforms;

import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.*;
import java.util.*;
import static org.objectweb.asm.Opcodes.*;

/**
 * Phase 2CM: folds Zelix KlassMaster's opaque-predicate flags, proved constant from the bytecode alone.
 *
 * <p>ZKM keeps a static flag per holder class, read through an accessor ({@code a.t()[Ljava/lang/String;} is
 * {@code getstatic k; areturn}), copied to a local at method entry and tested with {@code ifnull}/{@code ifnonnull} (or
 * {@code ifeq}/{@code ifne} for an {@code int}/{@code boolean} flag) in front of real code; one branch is never taken.
 * In a typical ZKM output one holder's {@code <clinit>} stores {@code new String[3]} through the setter
 * {@code Q([Ljava/lang/String;)V}, so every later read sees a non-null array; another holder's {@code <clinit>} stores
 * {@code null} through {@code C([I)V}, and other holders never
 * store at all. A flag is <em>proved</em> when:
 *
 * <ol>
 * <li>it is a static field of a class in the input with an accessor ({@code getstatic; return}); setters are
 * {@code load 0; putstatic; return};</li>
 * <li>its value is only ever copied, tested, popped or passed to a flag setter: an accessor result that is dereferenced,
 * compared, returned or passed elsewhere is data, not a flag;</li>
 * <li>its assumed value comes from the first store in its holder's {@code <clinit>}: {@code null} or a fresh object or
 * array for a reference flag, a constant for an {@code int} flag; with no such store, the default ({@code null}, 0). A
 * value other than the default must be stored on every path before the initializer can return. Before that store the
 * flag still holds its default: the initializer's own tests are decided with it ({@code G.<clinit>} runs
 * {@code if (s()) K(true)} with {@code s()} = {@code !B()}), and every method that may run before the store (the
 * initializer and what it reaches inside the input: class hierarchy analysis over calls, the class initializers they
 * trigger, every method of a bootstrap's class for an {@code invokedynamic}) is left unfolded for this flag;</li>
 * <li>its holder's {@code <clinit>} calls no native method of the input: a native layer
 * ({@code Loader.registerNativesForClass}, then a hex-named native method) could set the flag, and the premises below
 * do not cover native code;</li>
 * <li>a <em>wrapper</em> (a static no-argument method of the holder returning {@code int}/{@code boolean}, such as
 * {@code s()}) is evaluated only to decide the initializer's path; it computes a value from the flag, so neither its
 * calls nor its body are folded;</li>
 * <li>fixed point: with every proved flag folded, each store still reachable writes the assumed value again. A flag with
 * any other reachable store is dropped and the analysis repeats. By induction over the execution, the assumed values
 * never change, so every folded branch is one the program cannot take.</li>
 * </ol>
 *
 * <p>Flags that change at run time (one set non-null by the first constructor that runs
 * {@code if (b.F() != null) c.C(new int[2])}) fail the fixed point and are left alone.
 *
 * <p>Assumptions, stated in the report: code outside the input (the JDK, dependencies) does not call back into the input
 * during a flag holder's initialization, and nothing writes a flag through reflection. A folded test becomes
 * {@code pop} (not taken) or {@code pop; goto} (taken), code no longer reachable is removed, and a flag read whose value
 * is no longer used is dropped. A changed method must pass ASM's {@code BasicVerifier}, or its class is left unchanged.
 * Never defines, loads or runs a class.
 */
public final class ZkmFlagPass implements BytecodePass {
    public static final String NAME = "zkm-flags";
    private static final int MAX_CLASS_BYTES = 16 * 1024 * 1024;

    /** A proved flag: for a reference flag {@code value} is 0 (always {@code null}) or 1 (never {@code null}); for an {@code int} flag the constant. */
    public record Flag(String owner, String field, String descriptor, boolean reference, int value, Set<String> early, String base) {
        public Flag { early = Set.copyOf(early); }
        Flag(String owner, String field, String descriptor, boolean reference, int value) { this(owner, field, descriptor, reference, value, Set.of(), null); }
        public String key() { return owner + "." + field + ":" + descriptor; }
        /** For a wrapper: the method it is (owner.name+desc), and {@link #base} names the flag it reads. */
        public boolean wrapper() { return base != null; }
        Flag with(int value, Set<String> early) { return new Flag(owner, field, descriptor, reference, value, early, base); }
    }

    private final Map<String,Flag> flags;            // owner.field:desc
    private final Map<String,Flag> accessors;        // owner.name+desc of an accessor
    private final Map<String,Flag> setters;          // owner.name+desc of a setter
    private final Map<String,String> superclasses;   // for frame computation
    private final Set<String> interfaces;
    private final Map<String,String> rejected;        // candidate flag key -> why it was not proved

    private ZkmFlagPass(Map<String,Flag> flags, Map<String,Flag> accessors, Map<String,Flag> setters,
                        Map<String,String> superclasses, Set<String> interfaces, Map<String,String> rejected) {
        this.flags = Map.copyOf(flags); this.accessors = Map.copyOf(accessors); this.setters = Map.copyOf(setters);
        this.superclasses = Map.copyOf(superclasses); this.interfaces = Set.copyOf(interfaces);
        this.rejected = Collections.unmodifiableMap(new TreeMap<>(rejected));
    }

    @Override public String id() { return "zkm-flags-v1"; }
    /** The proved flags, by holder, field and descriptor. */
    public Collection<Flag> flags() { return new TreeMap<>(flags).values(); }
    /** Candidate flags (a static field with an accessor) that were not proved, with the first reason found. */
    public Map<String,String> rejected() { return rejected; }

    /** The flags proved over the whole input ({@code classes}: class bytes of every class in the input). */
    public static ZkmFlagPass plan(Collection<byte[]> classes) {
        var nodes = new TreeMap<String,ClassNode>();
        for (byte[] bytes : classes) {
            if (bytes.length > MAX_CLASS_BYTES) continue;
            try {
                var node = new ClassNode(); new ClassReader(bytes).accept(node, ClassReader.SKIP_FRAMES);
                nodes.put(node.name, node);
            } catch (RuntimeException unreadable) { /* not analyzed, never folded */ }
        }
        var superclasses = new HashMap<String,String>(); var interfaces = new HashSet<String>();
        for (var node : nodes.values()) {
            if (node.superName != null) superclasses.put(node.name, node.superName);
            if ((node.access & ACC_INTERFACE) != 0) interfaces.add(node.name);
        }
        // 1. Candidates: static fields with an accessor, and their setters.
        var candidates = new TreeMap<String,Flag>(); var accessors = new HashMap<String,Flag>(); var setters = new HashMap<String,Flag>();
        for (var node : nodes.values()) for (var method : node.methods) {
            if ((method.access & ACC_STATIC) == 0) continue;
            var code = real(method);
            if (code.size() == 2 && code.get(0) instanceof FieldInsnNode get && get.getOpcode() == GETSTATIC && get.owner.equals(node.name)
                && method.desc.equals("()" + get.desc) && isReturn(code.get(1).getOpcode()) && flagType(get.desc) != null
                && staticField(node, get.name, get.desc)) {
                var flag = candidates.computeIfAbsent(get.owner + "." + get.name + ":" + get.desc,
                    ignored -> new Flag(get.owner, get.name, get.desc, flagType(get.desc), 0));
                accessors.put(node.name + "." + method.name + method.desc, flag);
            }
        }
        for (var node : nodes.values()) for (var method : node.methods) {
            if ((method.access & ACC_STATIC) == 0) continue;
            var code = real(method);
            if (code.size() == 3 && code.get(0) instanceof VarInsnNode load && load.var == 0 && code.get(1) instanceof FieldInsnNode put
                && put.getOpcode() == PUTSTATIC && code.get(2).getOpcode() == RETURN) {
                var flag = candidates.get(put.owner + "." + put.name + ":" + put.desc);
                if (flag != null && method.desc.equals("(" + put.desc + ")V")) setters.put(node.name + "." + method.name + method.desc, flag);
            }
        }
        // 2. Initial values from the holder's class initializer, and the reentrancy check.
        var program = Program.of(nodes.values());
        var natives = new HashSet<String>();
        for (var node : nodes.values()) for (var method : node.methods)
            if ((method.access & ACC_NATIVE) != 0) natives.add(node.name + "." + method.name + method.desc);
        var active = new TreeMap<String,Flag>(); var rejected = new TreeMap<String,String>();
        for (var flag : candidates.values()) {
            var holder = nodes.get(flag.owner());
            var reason = new String[1];
            // Native code (for example Loader.registerNativesForClass, then a hex-named native method) may set the flag;
            // the premises of the proof (no callbacks, no reflection, one thread) do not cover it.
            String nativeCall = nativeCall(holder, natives);
            if (nativeCall != null) { rejected.put(flag.key(), "its <clinit> calls native code (" + nativeCall + "), which could set it"); continue; }
            var init = initialStore(program, holder, flag, accessors, setters, reason);
            if (init == null) { rejected.put(flag.key(), reason[0]); continue; }
            // A wrapper (G.s() = !B()) computes a value from the flag; it is never folded, neither its calls nor its body.
            var early = new HashSet<>(init.early());
            for (var wrapper : wrappers(holder, init, accessors)) early.add(holder.name + "." + wrapper.name + wrapper.desc);
            active.put(flag.key(), init.with(init.value(), early));
        }
        var activeAccessors = new HashMap<String,Flag>(); var activeSetters = new HashMap<String,Flag>();
        // 3. Fixed point over the whole input.
        while (true) {
            activeAccessors.clear(); activeSetters.clear();
            accessors.forEach((key, flag) -> { if (active.containsKey(flag.key())) activeAccessors.put(key, active.get(flag.key())); });
            setters.forEach((key, flag) -> { if (active.containsKey(flag.key())) activeSetters.put(key, active.get(flag.key())); });
            var probe = new ZkmFlagPass(active, activeAccessors, activeSetters, superclasses, interfaces, rejected);
            var violated = new TreeMap<String,String>();
            for (var node : nodes.values()) for (var method : node.methods) probe.check(node, method, violated);
            if (violated.isEmpty()) return probe;
            violated.forEach((key, why) -> { active.remove(key); rejected.putIfAbsent(key, why); });
            active.values().removeIf(flag -> flag.wrapper() && !active.containsKey(flag.base()));
        }
    }

    /** Nothing to fold: a pass with no proved flag. */
    public static ZkmFlagPass none() { return new ZkmFlagPass(Map.of(), Map.of(), Map.of(), Map.of(), Set.of(), Map.of()); }

    // ---------------------------------------------------------------------------------------------------------------
    // Values: the flag a value was read from, and a known value: an int constant, or 0 (null) / 1 (a fresh object) for a
    // reference.

    record V(BasicValue basic, String flag, Integer constant) implements Value {
        public int getSize() { return basic.getSize(); }
    }
    private static V plain(BasicValue basic) { return basic == null ? null : new V(basic, null, null); }
    private static V known(BasicValue basic, int constant) { return new V(basic, null, constant); }

    /** Tracks flag values; reports a flag used as data to {@code used}, and every store to a flag to {@code stores}. */
    private final class FlagInterpreter extends Interpreter<V> {
        private final BasicInterpreter basic = new BasicInterpreter();
        final List<Object[]> stores = new ArrayList<>(); // {insn, flag key, value}
        FlagInterpreter() { super(ASM9); }
        /** Candidate uses: an instruction, the flag, and the operand's depth below the top of the stack. */
        final List<Object[]> uses = new ArrayList<>();
        private void use(AbstractInsnNode insn, V value, int depth) {
            if (value != null && value.flag() != null) uses.add(new Object[] {insn, value.flag(), depth});
        }
        @Override public V newValue(Type type) { return plain(basic.newValue(type)); }
        @Override public V newOperation(AbstractInsnNode insn) throws AnalyzerException {
            var result = basic.newOperation(insn);
            switch (insn.getOpcode()) {
                case ICONST_M1, ICONST_0, ICONST_1, ICONST_2, ICONST_3, ICONST_4, ICONST_5 -> {
                    return known(result, insn.getOpcode() - ICONST_0);
                }
                case BIPUSH, SIPUSH -> { return known(result, ((IntInsnNode) insn).operand); }
                case ACONST_NULL -> { return known(result, 0); }
                case NEW -> { return known(result, 1); }
                case LDC -> {
                    Object constant = ((LdcInsnNode) insn).cst;
                    if (constant instanceof Integer integer) return known(result, integer);
                    if (constant instanceof String || constant instanceof Type) return known(result, 1);
                }
                case GETSTATIC -> {
                    var field = (FieldInsnNode) insn;
                    String key = field.owner + "." + field.name + ":" + field.desc;
                    if (flags.containsKey(key)) return new V(result, key, null);
                }
                default -> { }
            }
            return plain(result);
        }
        @Override public V copyOperation(AbstractInsnNode insn, V value) { return value; }
        @Override public V unaryOperation(AbstractInsnNode insn, V value) throws AnalyzerException {
            int op = insn.getOpcode();
            if (op == PUTSTATIC) {
                var field = (FieldInsnNode) insn; String key = field.owner + "." + field.name + ":" + field.desc;
                if (flags.containsKey(key)) stores.add(new Object[] {insn, key, value});
                else use(insn, value, 0);
                return null;
            }
            if (op != IFNULL && op != IFNONNULL && op != IFEQ && op != IFNE) use(insn, value, 0);
            var result = basic.unaryOperation(insn, value.basic());
            if (op == NEWARRAY || op == ANEWARRAY) return known(result, 1);
            return plain(result);
        }
        @Override public V binaryOperation(AbstractInsnNode insn, V a, V b) throws AnalyzerException {
            use(insn, a, 1); use(insn, b, 0); return plain(basic.binaryOperation(insn, a.basic(), b.basic()));
        }
        @Override public V ternaryOperation(AbstractInsnNode insn, V a, V b, V c) throws AnalyzerException {
            use(insn, a, 2); use(insn, b, 1); use(insn, c, 0); return plain(basic.ternaryOperation(insn, a.basic(), b.basic(), c.basic()));
        }
        @Override public V naryOperation(AbstractInsnNode insn, List<? extends V> values) throws AnalyzerException {
            var basics = new ArrayList<BasicValue>(); values.forEach(value -> basics.add(value.basic()));
            var result = basic.naryOperation(insn, basics);
            if (insn instanceof MethodInsnNode call && call.getOpcode() == INVOKESTATIC) {
                String key = call.owner + "." + call.name + call.desc;
                var setter = setters.get(key);
                if (setter != null) { stores.add(new Object[] {insn, setter.key(), values.getFirst()}); return plain(result); }
                var accessor = accessors.get(key);
                if (accessor != null) return new V(result, accessor.key(), null);
            }
            for (int i = 0; i < values.size(); i++) use(insn, values.get(i), values.size() - 1 - i);
            if (insn.getOpcode() == MULTIANEWARRAY) return known(result, 1);
            return plain(result);
        }
        @Override public void returnOperation(AbstractInsnNode insn, V value, V expected) { use(insn, value, 0); }
        /**
         * The flags used as data, judged on the converged frames: the analyzer meets an instruction before its frame has
         * merged every path, so a flag seen there may be a value that merges away (ZKM's key-guarded fake edges carry the
         * flag on the stack into unrelated code).
         */
        Set<String> used(MethodNode method, Frame<V>[] frames) {
            var result = new TreeSet<String>();
            for (var use : uses) {
                int index = method.instructions.indexOf((AbstractInsnNode) use[0]);
                var frame = frames[index];
                if (frame == null) continue;
                int depth = (Integer) use[2], slot = frame.getStackSize() - 1 - depth;
                if (slot >= 0 && use[1].equals(frame.getStack(slot).flag())) result.add((String) use[1]);
            }
            return result;
        }
        @Override public V merge(V a, V b) {
            var merged = basic.merge(a.basic(), b.basic());
            String flag = Objects.equals(a.flag(), b.flag()) ? a.flag() : null;
            Integer constant = Objects.equals(a.constant(), b.constant()) ? a.constant() : null;
            var result = new V(merged, flag, constant);
            return result.equals(a) ? a : result;
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Fixed-point check and folding.

    /**
     * One method: flags used as data, and stores that do not keep a flag's assumed value, in the method as folded under
     * the assumed values. Folding first makes the check follow paths: ZKM writes {@code K(!s())}, which stores the
     * flag's own value once {@code s()} is decided, while an unfolded analysis merges both constants.
     */
    private void check(ClassNode owner, MethodNode method, Map<String,String> violated) {
        String where = owner.name + "." + method.name + method.desc;
        if (method.instructions.size() == 0 || skipped(owner, method)) return;
        var copy = new MethodNode(ASM9, method.access, method.name, method.desc, method.signature,
            method.exceptions == null ? null : method.exceptions.toArray(String[]::new));
        method.accept(copy);
        var interpreter = new FlagInterpreter();
        Frame<V>[] frames;
        try {
            foldInPlace(owner.name, where, copy, null);
            frames = new Analyzer<>(interpreter).analyze(owner.name, copy);
        } catch (AnalyzerException unanalyzable) {
            // A method ASM cannot analyze may still read or write flags: each flag it touches is dropped.
            for (var insn : method.instructions) touched(insn).ifPresent(key -> violated.putIfAbsent(key, "touched in a method ASM cannot analyze: " + where));
            return;
        }
        for (String flag : interpreter.used(copy, frames)) violated.putIfAbsent(flag, "used as data in " + where);
        for (var store : interpreter.stores) {
            var flag = flags.get((String) store[1]); var value = (V) store[2];
            var from = value.flag() == null ? null : flags.get(value.flag());
            boolean keeps = value.constant() != null && value.constant() == flag.value()
                || from != null && from.reference() == flag.reference() && from.value() == flag.value();
            if (!keeps) violated.putIfAbsent(flag.key(), "a reachable store of another value in " + where);
        }
    }

    /**
     * Folds the decided tests of {@code method} in place, round after round (a fold can make a merged value constant),
     * removing code that is no longer reachable. Each fold is added to {@code changes} (when not null) with its
     * instruction index in the method as folded so far. Returns the number of folds.
     */
    private int foldInPlace(String owner, String where, MethodNode method, List<TransformResult.Change> changes) throws AnalyzerException {
        int folds = 0;
        for (int round = 0; round < 64; round++) {
            var frames = new Analyzer<>(new FlagInterpreter()).analyze(owner, method);
            var decisions = decisions(where, method, frames);
            var reachable = reachable(method, decisions);
            decisions.keySet().removeIf(jump -> !reachable.contains(jump));
            if (decisions.isEmpty()) break;
            var insns = method.instructions.toArray();
            for (int i = 0; i < insns.length; i++)
                if (insns[i] instanceof JumpInsnNode jump && decisions.containsKey(jump) && changes != null)
                    changes.add(new TransformResult.Change(id(), owner, method.name, method.desc, i, jump.getOpcode(), 0, 0,
                        decisions.get(jump) ? 1 : 0));
            folds += decisions.size();
            for (var insn : insns) if (!(insn instanceof LabelNode || insn instanceof LineNumberNode || insn instanceof FrameNode)
                && !reachable.contains(insn)) method.instructions.remove(insn);
            for (var decision : decisions.entrySet()) {
                var jump = decision.getKey();
                method.instructions.insertBefore(jump, new InsnNode(POP));
                if (decision.getValue()) method.instructions.set(jump, new JumpInsnNode(GOTO, jump.label));
                else method.instructions.remove(jump);
            }
            method.tryCatchBlocks.removeIf(block -> !covers(block));
        }
        if (folds > 0) dropDeadReads(method);
        return folds;
    }

    /** Folded tests of one method: the jump and whether it is taken. */
    private Map<JumpInsnNode,Boolean> decisions(String where, MethodNode method, Frame<V>[] frames) {
        var result = new IdentityHashMap<JumpInsnNode,Boolean>();
        var insns = method.instructions.toArray();
        for (int i = 0; i < insns.length; i++) {
            if (!(insns[i] instanceof JumpInsnNode jump) || frames[i] == null) continue;
            int op = jump.getOpcode();
            if (op != IFNULL && op != IFNONNULL && op != IFEQ && op != IFNE) continue;
            var top = frames[i].getStack(frames[i].getStackSize() - 1);
            var flag = top.flag() == null ? null : flags.get(top.flag());
            if (flag == null || flag.early().contains(where)) continue;
            if (flag.reference() && (op == IFNULL || op == IFNONNULL)) result.put(jump, (op == IFNULL) == (flag.value() == 0));
            else if (!flag.reference() && (op == IFEQ || op == IFNE)) result.put(jump, (op == IFEQ) == (flag.value() == 0));
        }
        return result;
    }

    @Override public TransformResult apply(byte[] original, int maxChanges) {
        Objects.requireNonNull(original);
        if (original.length > MAX_CLASS_BYTES || maxChanges < 1) throw new IllegalArgumentException("Require class <=16 MiB and a positive change budget");
        if (flags.isEmpty()) return new TransformResult(original, List.of(), false);
        var node = new ClassNode();
        try { new ClassReader(original).accept(node, ClassReader.SKIP_FRAMES); }
        catch (RuntimeException unreadable) { return new TransformResult(original, List.of(), false); }
        if (IntegerConstantPass.hasOpaqueAttributes(node)) return new TransformResult(original, List.of(), false);
        var changes = new ArrayList<TransformResult.Change>();
        for (var method : node.methods) {
            if (method.instructions.size() == 0 || skipped(node, method)) continue;
            var folded = new ArrayList<TransformResult.Change>();
            try { foldInPlace(node.name, node.name + "." + method.name + method.desc, method, folded); }
            catch (AnalyzerException unanalyzable) { return new TransformResult(original, List.of(), false); }
            if (changes.size() + folded.size() > maxChanges) return new TransformResult(original, List.of(), true);
            changes.addAll(folded);
        }
        if (changes.isEmpty()) return new TransformResult(original, List.of(), false);
        byte[] transformed;
        try {
            var writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES) {
                @Override protected String getCommonSuperClass(String a, String b) { return commonSuperClass(a, b); }
            };
            node.accept(writer);
            transformed = writer.toByteArray();
            var check = new ClassNode(); new ClassReader(transformed).accept(check, 0);
            for (var method : check.methods) {
                if (method.instructions.size() == 0) continue;
                new Analyzer<>(new BasicVerifier()).analyze(check.name, method);
            }
        } catch (RuntimeException | AnalyzerException invalid) {
            return new TransformResult(original, List.of(), false);
        }
        if (transformed.length > MAX_CLASS_BYTES) return new TransformResult(original, List.of(), false);
        return new TransformResult(transformed, changes, changes.size() == maxChanges);
    }

    /** Accessor and setter bodies are the flag itself, not a use of it. A wrapper is analyzed (and folded) like any method. */
    private boolean skipped(ClassNode owner, MethodNode method) {
        String key = owner.name + "." + method.name + method.desc;
        var accessor = accessors.get(key);
        return accessor != null && !accessor.wrapper() || setters.containsKey(key);
    }

    /** Instructions reachable from the entry, with each decided jump going only its decided way. */
    private static Set<AbstractInsnNode> reachable(MethodNode method, Map<JumpInsnNode,Boolean> decisions) {
        return reachable(method, decisions, null);
    }
    /** As {@link #reachable(MethodNode, Map)}, never going through {@code stop} (which is left out). */
    private static Set<AbstractInsnNode> reachable(MethodNode method, Map<JumpInsnNode,Boolean> decisions, AbstractInsnNode stop) {
        var seen = Collections.newSetFromMap(new IdentityHashMap<AbstractInsnNode,Boolean>());
        var work = new ArrayDeque<AbstractInsnNode>();
        if (method.instructions.getFirst() != null) work.add(method.instructions.getFirst());
        var handlers = new ArrayList<TryCatchBlockNode>(method.tryCatchBlocks);
        var handled = Collections.newSetFromMap(new IdentityHashMap<TryCatchBlockNode,Boolean>());
        while (!work.isEmpty()) {
            var insn = work.poll();
            if (insn == null || insn == stop || !seen.add(insn)) continue;
            int op = insn.getOpcode();
            if (op >= 0) for (var block : handlers) if (!handled.contains(block) && inside(method, block, insn)) { handled.add(block); work.add(block.handler); }
            if (insn instanceof JumpInsnNode jump) {
                Boolean decided = decisions.get(jump);
                if (decided == null || decided) work.add(jump.label);
                if (op != GOTO && op != JSR && (decided == null || !decided)) work.add(insn.getNext());
            } else if (insn instanceof TableSwitchInsnNode table) { work.add(table.dflt); work.addAll(table.labels); }
            else if (insn instanceof LookupSwitchInsnNode lookup) { work.add(lookup.dflt); work.addAll(lookup.labels); }
            else if (!(op >= IRETURN && op <= RETURN) && op != ATHROW && op != RET) work.add(insn.getNext());
        }
        return seen;
    }
    private static boolean inside(MethodNode method, TryCatchBlockNode block, AbstractInsnNode insn) {
        int at = method.instructions.indexOf(insn);
        return at >= method.instructions.indexOf(block.start) && at < method.instructions.indexOf(block.end);
    }
    private static boolean covers(TryCatchBlockNode block) {
        for (var insn = block.start.getNext(); insn != null && insn != block.end; insn = insn.getNext()) if (insn.getOpcode() >= 0) return true;
        return false;
    }

    /** {@code load n; pop} pairs, then flag reads stored to a local nothing loads any more. */
    private void dropDeadReads(MethodNode method) {
        boolean again = true;
        while (again) {
            again = false;
            for (var insn : method.instructions.toArray()) {
                var next = nextReal(insn);
                if (insn instanceof VarInsnNode load && load.getOpcode() >= ILOAD && load.getOpcode() <= ALOAD && next != null && next.getOpcode() == POP) {
                    method.instructions.remove(insn); method.instructions.remove(next); again = true;
                } else if (flagRead(insn) && next != null && next.getOpcode() == POP) {
                    method.instructions.remove(insn); method.instructions.remove(next); again = true;
                } else if (flagRead(insn) && next instanceof VarInsnNode store && store.getOpcode() >= ISTORE && store.getOpcode() <= ASTORE
                           && !loaded(method, store.var)) {
                    method.instructions.remove(insn); method.instructions.remove(next); again = true;
                }
            }
        }
    }
    /** A read through a field or a plain accessor; a wrapper call is kept, since its body may do more than read. */
    private boolean flagRead(AbstractInsnNode insn) {
        if (insn instanceof MethodInsnNode call && call.getOpcode() == INVOKESTATIC) {
            var accessor = accessors.get(call.owner + "." + call.name + call.desc);
            return accessor != null && !accessor.wrapper();
        }
        return insn instanceof FieldInsnNode field && field.getOpcode() == GETSTATIC && flags.containsKey(field.owner + "." + field.name + ":" + field.desc);
    }
    private static boolean loaded(MethodNode method, int var) {
        for (var insn : method.instructions) {
            if (insn instanceof VarInsnNode use && use.var == var && (use.getOpcode() <= ALOAD || use.getOpcode() == RET)) return true;
            if (insn instanceof IincInsnNode increment && increment.var == var) return true;
        }
        return false;
    }
    private static AbstractInsnNode nextReal(AbstractInsnNode insn) {
        var next = insn.getNext();
        while (next != null && next.getOpcode() < 0 && !(next instanceof LabelNode)) next = next.getNext();
        return next instanceof LabelNode ? null : next;
    }

    /** The flag an instruction reads or writes, if any. */
    private Optional<String> touched(AbstractInsnNode insn) {
        if (insn instanceof FieldInsnNode field) {
            String key = field.owner + "." + field.name + ":" + field.desc;
            if (flags.containsKey(key)) return Optional.of(key);
        }
        if (insn instanceof MethodInsnNode call) {
            String key = call.owner + "." + call.name + call.desc;
            if (accessors.containsKey(key)) return Optional.of(accessors.get(key).key());
            if (setters.containsKey(key)) return Optional.of(setters.get(key).key());
        }
        return Optional.empty();
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Initial values.

    /**
     * The flag with its assumed value and its early methods, or null (with {@code reason}) when it cannot be proved. The
     * first store in the holder's {@code <clinit>} gives the value; with none, the flag keeps its default ({@code null},
     * 0). A value other than the default must be stored before the initializer can return, on every path once the
     * initializer's tests on the flag and on its wrappers are decided with the default (the value the flag holds until
     * that store). The initializer and every method reachable from it before the store are the flag's early methods.
     */
    private static Flag initialStore(Program program, ClassNode holder, Flag flag, Map<String,Flag> accessors, Map<String,Flag> setters,
                                     String[] reason) {
        MethodNode clinit = null;
        for (var method : holder.methods) if (method.name.equals("<clinit>")) clinit = method;
        if (clinit == null) return flag;
        // The default value, with the wrappers it decides.
        var probeFlags = new HashMap<String,Flag>(); probeFlags.put(flag.key(), flag);
        var probeAccessors = filter(accessors, flag);
        for (var wrapper : wrappers(holder, flag, accessors)) {
            var value = evaluate(holder, wrapper, flag, accessors);
            if (value == null) continue;
            var derived = new Flag(holder.name, wrapper.name + wrapper.desc, Type.getReturnType(wrapper.desc).getDescriptor(), false,
                value, Set.of(), flag.key());
            probeFlags.put(derived.key(), derived); probeAccessors.put(holder.name + "." + wrapper.name + wrapper.desc, derived);
        }
        var probe = new ZkmFlagPass(probeFlags, probeAccessors, filter(setters, flag), Map.of(), Set.of(), Map.of());
        var interpreter = probe.new FlagInterpreter();
        Frame<V>[] frames;
        try { frames = new Analyzer<>(interpreter).analyze(holder.name, clinit); }
        catch (AnalyzerException unanalyzable) { reason[0] = "the holder's <clinit> cannot be analyzed"; return null; }
        // The first store the initializer can reach while the flag still holds its default (I.<clinit> runs
        // `if (h() != null) b(new int[2])`, which never stores).
        String clinitKey = holder.name + ".<clinit>()V";
        var decided = probe.decisions(clinitKey, clinit, frames);
        var reachableAtAll = reachable(clinit, decided);
        var store = probe.first(Arrays.stream(clinit.instructions.toArray()).filter(reachableAtAll::contains).toArray(AbstractInsnNode[]::new), flag);
        if (store == null) return flag;
        V value = null;
        for (var item : interpreter.stores) if (item[0] == store) value = (V) item[2];
        if (value == null || value.constant() == null) {
            reason[0] = "the first store in <clinit> is not null, a fresh object or a constant"; return null;
        }
        if (value.constant() == 0) return flag.with(0, Set.of());
        var before = reachable(clinit, decided, store);
        for (var insn : before) if (insn.getOpcode() >= IRETURN && insn.getOpcode() <= RETURN) {
            reason[0] = "<clinit> may return before its first store"; return null;
        }
        var prefix = new ArrayList<AbstractInsnNode>();
        for (var insn : before) if (insn.getOpcode() >= 0) prefix.add(insn);
        var early = new HashSet<>(program.reached(holder.name, prefix)); early.add(clinitKey);
        return flag.with(value.constant(), early);
    }
    /** The name of a native method of the input that the holder's {@code <clinit>} calls, or null. */
    private static String nativeCall(ClassNode holder, Set<String> natives) {
        for (var method : holder.methods) {
            if (!method.name.equals("<clinit>")) continue;
            for (var insn : method.instructions)
                if (insn instanceof MethodInsnNode call && natives.contains(call.owner + "." + call.name + call.desc)) return call.name;
        }
        return null;
    }
    /** Static no-argument methods of the holder returning {@code int}-like values that read {@code flag}: candidate wrappers. */
    private static List<MethodNode> wrappers(ClassNode holder, Flag flag, Map<String,Flag> accessors) {
        var result = new ArrayList<MethodNode>();
        for (var method : holder.methods) {
            if ((method.access & ACC_STATIC) == 0 || method.name.startsWith("<") || !method.desc.startsWith("()")
                || flagType(Type.getReturnType(method.desc).getDescriptor()) != Boolean.FALSE) continue;
            String key = holder.name + "." + method.name + method.desc;
            if (accessors.containsKey(key)) continue;
            for (var insn : method.instructions) {
                boolean reads = insn instanceof MethodInsnNode call && call.getOpcode() == INVOKESTATIC
                    && accessors.containsKey(call.owner + "." + call.name + call.desc) && accessors.get(call.owner + "." + call.name + call.desc).key().equals(flag.key())
                    || insn instanceof FieldInsnNode field && field.getOpcode() == GETSTATIC && (field.owner + "." + field.name + ":" + field.desc).equals(flag.key());
                if (reads) { result.add(method); break; }
            }
        }
        return result;
    }
    /** The one constant every reachable return of {@code wrapper} gives when {@code flag} holds its value, or null. */
    private static Integer evaluate(ClassNode holder, MethodNode wrapper, Flag flag, Map<String,Flag> accessors) {
        var probe = new ZkmFlagPass(Map.of(flag.key(), flag.with(flag.value(), Set.of())), filter(accessors, flag), Map.of(), Map.of(), Set.of(), Map.of());
        Frame<V>[] frames;
        try { frames = new Analyzer<>(probe.new FlagInterpreter()).analyze(holder.name, wrapper); }
        catch (AnalyzerException unanalyzable) { return null; }
        var reachable = reachable(wrapper, probe.decisions("", wrapper, frames));
        var insns = wrapper.instructions.toArray();
        Integer result = null;
        for (int i = 0; i < insns.length; i++) {
            if (!reachable.contains(insns[i]) || insns[i].getOpcode() != IRETURN) continue;
            if (frames[i] == null) return null;
            var returned = frames[i].getStack(frames[i].getStackSize() - 1).constant();
            if (returned == null || result != null && !result.equals(returned)) return null;
            result = returned;
        }
        return result;
    }
    private static Map<String,Flag> filter(Map<String,Flag> map, Flag flag) {
        var result = new HashMap<String,Flag>();
        map.forEach((key, value) -> { if (value.key().equals(flag.key())) result.put(key, value); });
        return result;
    }
    /** The first store to {@code flag} among {@code insns} (a putstatic or a call to one of its setters), or null. */
    private AbstractInsnNode first(AbstractInsnNode[] insns, Flag flag) {
        for (var insn : insns) {
            if (insn instanceof FieldInsnNode field && field.getOpcode() == PUTSTATIC && (field.owner + "." + field.name + ":" + field.desc).equals(flag.key()))
                return insn;
            if (insn instanceof MethodInsnNode call && call.getOpcode() == INVOKESTATIC) {
                var setter = setters.get(call.owner + "." + call.name + call.desc);
                if (setter != null && setter.key().equals(flag.key())) return insn;
            }
        }
        return null;
    }

    /** Every method of the input by owner.name+desc, and by name+desc for virtual calls. */
    private record Program(Map<String,MethodNode> methods, Map<String,List<String>> byName, Map<String,List<String>> supertypes,
                           Map<MethodNode,String> keys) {
        static Program of(Collection<ClassNode> nodes) {
            var methods = new HashMap<String,MethodNode>(); var byName = new HashMap<String,List<String>>();
            var supertypes = new HashMap<String,List<String>>(); var keys = new IdentityHashMap<MethodNode,String>();
            for (var node : nodes) {
                var direct = new ArrayList<String>();
                if (node.superName != null) direct.add(node.superName);
                direct.addAll(node.interfaces);
                supertypes.put(node.name, direct);
                for (var method : node.methods) {
                    methods.put(node.name + "." + method.name + method.desc, method);
                    keys.put(method, node.name + "." + method.name + method.desc);
                    byName.computeIfAbsent(method.name + method.desc, ignored -> new ArrayList<>()).add(node.name);
                }
            }
            return new Program(methods, byName, supertypes, keys);
        }
        /** Whether {@code type} is {@code ancestor} or extends or implements it, within the input. */
        boolean subtype(String type, String ancestor) {
            var seen = new HashSet<String>(); var work = new ArrayDeque<String>(List.of(type));
            while (!work.isEmpty()) {
                String next = work.poll();
                if (next.equals(ancestor)) return true;
                if (seen.add(next)) work.addAll(supertypes.getOrDefault(next, List.of()));
            }
            return false;
        }
        /** Class hierarchy analysis: the input's methods a virtual or interface call on {@code owner} may run. */
        List<MethodNode> dispatch(String owner, String name, String desc) {
            var result = new ArrayList<MethodNode>();
            for (String type : byName.getOrDefault(name + desc, List.of()))
                if (subtype(type, owner) || subtype(owner, type)) result.add(methods.get(type + "." + name + desc));
            return result;
        }

        /**
         * The methods of the input that may run from {@code prefix} (instructions of {@code holder}'s initializer): calls
         * by class hierarchy analysis, the class initializers they trigger, and every method of a bootstrap's class for an
         * {@code invokedynamic}, whose target its bootstrap picks at run time. Code outside the input is assumed not to
         * call back into it.
         */
        Set<String> reached(String holder, List<AbstractInsnNode> prefix) {
            var visited = Collections.newSetFromMap(new IdentityHashMap<MethodNode,Boolean>());
            var work = new ArrayDeque<AbstractInsnNode>(prefix);
            var classesSeen = new HashSet<String>(); classesSeen.add(holder);
            while (!work.isEmpty()) {
                var insn = work.poll();
                var targets = new ArrayList<MethodNode>();
                if (insn instanceof MethodInsnNode call) {
                    var exact = methods.get(call.owner + "." + call.name + call.desc);
                    if (call.getOpcode() == INVOKESTATIC || call.getOpcode() == INVOKESPECIAL) { if (exact != null) targets.add(exact); }
                    else targets.addAll(dispatch(call.owner, call.name, call.desc));
                    initializer(this, call.owner, classesSeen, targets);
                } else if (insn instanceof FieldInsnNode field) initializer(this, field.owner, classesSeen, targets);
                else if (insn instanceof TypeInsnNode type && type.getOpcode() == NEW) initializer(this, type.desc, classesSeen, targets);
                else if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof Type type && type.getSort() == Type.OBJECT)
                    initializer(this, type.getInternalName(), classesSeen, targets);
                else if (insn instanceof InvokeDynamicInsnNode indy)
                    for (var entry : methods.entrySet()) if (entry.getKey().startsWith(indy.bsm.getOwner() + ".")) targets.add(entry.getValue());
                for (var method : targets) if (method != null && visited.add(method))
                    for (var next : method.instructions) if (next.getOpcode() >= 0) work.add(next);
            }
            var result = new HashSet<String>();
            for (var method : visited) result.add(keys.get(method));
            return result;
        }
    }

    private static void initializer(Program program, String owner, Set<String> seen, List<MethodNode> targets) {
        if (!seen.add(owner)) return;
        var clinit = program.methods().get(owner + ".<clinit>()V");
        if (clinit != null) targets.add(clinit);
    }

    // ---------------------------------------------------------------------------------------------------------------

    private String commonSuperClass(String a, String b) {
        if (a.equals(b)) return a;
        if (interfaces.contains(a) || interfaces.contains(b)) return "java/lang/Object";
        var ancestors = new HashSet<String>();
        for (String type = a; type != null; type = superclasses.get(type)) ancestors.add(type);
        for (String type = b; type != null; type = superclasses.get(type)) if (ancestors.contains(type)) return type;
        return "java/lang/Object";
    }
    private static List<AbstractInsnNode> real(MethodNode method) {
        var result = new ArrayList<AbstractInsnNode>();
        for (var insn : method.instructions) if (insn.getOpcode() >= 0) result.add(insn);
        return result;
    }
    private static boolean isReturn(int op) { return op == ARETURN || op == IRETURN; }
    /** True for a reference flag, false for an int-like flag, null for any other type. */
    private static Boolean flagType(String desc) {
        if (desc.startsWith("[") || desc.startsWith("L")) return true;
        return switch (desc) { case "Z", "B", "C", "S", "I" -> false; default -> null; };
    }
    private static boolean staticField(ClassNode node, String name, String desc) {
        for (var field : node.fields) if (field.name.equals(name) && field.desc.equals(desc)) return (field.access & ACC_STATIC) != 0;
        return false;
    }
}
