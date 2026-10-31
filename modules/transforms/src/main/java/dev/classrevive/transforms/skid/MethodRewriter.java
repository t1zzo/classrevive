package dev.classrevive.transforms.skid;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.Frame;
import java.util.*;

/**
 * Phase 2CN: rewrites one method from the {@link SeedAnalyzer} frames, round after round until nothing changes:
 * <ul>
 *   <li>an int value computed from the obfuscator's material that has one possible value becomes that constant;</li>
 *   <li>a branch or switch on such a value with one feasible successor becomes a jump (or falls through); switch cases
 *   no input reaches are dropped;</li>
 *   <li>{@code throw new X()} whose first covering handler catches exactly {@code X} and does not start by storing the
 *   exception (Skidfuscator's handlers first re-derive the seed) becomes a jump to the handler with a null exception;</li>
 *   <li>unreachable instructions and try ranges where nothing can throw are removed;</li>
 *   <li>a peephole pass drops pushes that are immediately popped, jumps to the next instruction and jump chains.</li>
 * </ul>
 */
final class MethodRewriter implements Opcodes {
    static final int MAX_ROUNDS = 24;
    /** Counts per change kind, for the audit. */
    static final class Stats {
        int folded, branches, switchesPruned, dead, throwGotos, handlers, storesDropped, peephole, rounds, unanalyzable, assumed, threaded;
        /** Up to 20 methods (and the reason) the analysis refused. */
        final List<String> refused = new ArrayList<>();
        void refuse(String what) { unanalyzable++; if (refused.size() < 20) refused.add(what); }
        void add(Stats other) {
            folded += other.folded; branches += other.branches; switchesPruned += other.switchesPruned; dead += other.dead;
            throwGotos += other.throwGotos; handlers += other.handlers; storesDropped += other.storesDropped; peephole += other.peephole;
            rounds += other.rounds; unanalyzable += other.unanalyzable; assumed += other.assumed; threaded += other.threaded;
            for (String item : other.refused) if (refused.size() < 20) refused.add(item);
        }
        int total() { return folded + branches + switchesPruned + dead + throwGotos + handlers + storesDropped + peephole; }
    }

    private final SeedInterpreter interpreter;
    private final SeedAnalyzer analyzer;
    private final PureCalls pureCalls;

    /** Which static calls with primitive arguments have no side effect (their result may be dropped with their call). */
    interface PureCalls { boolean pure(MethodInsnNode call, Object[] literalArguments); }

    /** The literal arguments right before a call (int literals only), or null when any argument is computed. */
    static Object[] literalArguments(MethodInsnNode call) {
        var types = Type.getArgumentTypes(call.desc);
        Object[] args = new Object[types.length];
        AbstractInsnNode at = call;
        for (int k = types.length - 1; k >= 0; k--) {
            at = previousReal(at);
            if (types[k].getSort() == Type.LONG && at instanceof LdcInsnNode ldc && ldc.cst instanceof Long wide) { args[k] = wide; continue; }
            if (types[k].getSort() == Type.LONG && (at != null && (at.getOpcode() == LCONST_0 || at.getOpcode() == LCONST_1))) { args[k] = (long) (at.getOpcode() - LCONST_0); continue; }
            Integer value = SkidModel.literal(at);
            if (value == null || types[k].getSort() > Type.INT) return null;
            args[k] = value;
        }
        return args;
    }

    MethodRewriter(SeedInterpreter interpreter, PureCalls pureCalls) {
        this.interpreter = interpreter; this.analyzer = new SeedAnalyzer(interpreter); this.pureCalls = pureCalls;
    }

    boolean rewrite(String owner, MethodNode method, Map<Integer,int[]> parameters, Stats stats) {
        return rewrite(owner, method, parameters, stats, Set.of());
    }

    /** {@code seeds}: locals known from their shape to hold the obfuscator's values (Phase 2CZ: Aidsfuscator's flow ints). */
    boolean rewrite(String owner, MethodNode method, Map<Integer,int[]> parameters, Stats stats, Set<Integer> seeds) {
        // Phase 2DC: abstract and native methods have nothing to analyze; that is no refusal.
        if (method.instructions.size() == 0) return false;
        boolean changed = false;
        var seedLocals = new HashSet<Integer>(seeds);
        var generated = Collections.newSetFromMap(new IdentityHashMap<AbstractInsnNode,Boolean>());
        interpreter.generated(generated);
        for (int round = 0; round < MAX_ROUNDS; round++) {
            boolean step;
            try { step = round(owner, method, parameters, stats, seedLocals, generated); }
            catch (AnalyzerException | RuntimeException error) { stats.refuse(owner + "." + method.name + method.desc + ": " + error); return changed; }
            step |= peephole(method, stats);
            if (!step) break;
            changed = true; stats.rounds++;
        }
        interpreter.generated(Set.of());
        return changed;
    }

    /** {@code seedLocals}: locals that held an obfuscator value in some round (their stores die once their loads are folded). */
    private boolean round(String owner, MethodNode method, Map<Integer,int[]> parameters, Stats stats, Set<Integer> seedLocals,
                          Set<AbstractInsnNode> generated) throws AnalyzerException {
        var result = analyzer.analyze(owner, method, parameters, seedLocals);
        if (result == null) { stats.refuse(owner + "." + method.name + method.desc + ": " + analyzer.lastFailure); return false; }
        var insns = result.insns();
        Frame<SV>[] frames = result.frames();
        var index = new IdentityHashMap<AbstractInsnNode,Integer>();
        for (int i = 0; i < insns.length; i++) index.put(insns[i], i);
        var replace = new IdentityHashMap<AbstractInsnNode,InsnList>();
        var loads = new HashSet<Integer>();
        for (int i = 0; i < insns.length; i++) {
            if (frames[i] == null) continue;
            if (insns[i] instanceof VarInsnNode store && store.getOpcode() == ISTORE && frames[i].getStack(frames[i].getStackSize() - 1).tainted)
                seedLocals.add(store.var);
            if (insns[i] instanceof VarInsnNode var && var.getOpcode() >= ILOAD && var.getOpcode() <= ALOAD) loads.add(var.var);
            if (insns[i] instanceof IincInsnNode inc) loads.add(inc.var);
        }
        for (int i = 0; i < insns.length; i++) {
            var insn = insns[i];
            var frame = frames[i];
            if (frame == null || insn.getOpcode() < 0 || replace.containsKey(insn)) continue;
            int op = insn.getOpcode();
            // Phase 2CZ: a goto into a dispatcher (iload k; switch) while k holds one obfuscator value goes straight to that
            // case (Aidsfuscator's flattening; once every goto is threaded the dispatcher is dead).
            if (insn instanceof JumpInsnNode jump && op == GOTO) {
                var target = dispatchTarget(jump, frame);
                if (target != null) {
                    var list = new InsnList(); list.add(new JumpInsnNode(GOTO, target));
                    replace.put(insn, list); stats.threaded++;
                }
                continue;
            }
            // Branches with one feasible successor.
            if (insn instanceof JumpInsnNode jump && op != GOTO && op != JSR) {
                var targets = result.feasible().get(insn);
                if (targets != null && targets.size() == 1) {
                    int target = targets.iterator().next();
                    var list = new InsnList();
                    int operands = op >= IFEQ && op <= IFLE || op == IFNULL || op == IFNONNULL ? 1 : 2;
                    for (int k = 0; k < operands; k++) list.add(new InsnNode(POP));
                    if (target != i + 1) list.add(new JumpInsnNode(GOTO, (LabelNode) insns[target]));
                    replace.put(insn, list); stats.branches++;
                    if (result.assumed().contains(insn)) stats.assumed++;
                }
                continue;
            }
            if (insn instanceof TableSwitchInsnNode || insn instanceof LookupSwitchInsnNode) {
                var targets = result.feasible().get(insn);
                if (targets == null || targets.isEmpty()) continue;
                if (targets.size() == 1) {
                    var list = new InsnList();
                    list.add(new InsnNode(POP));
                    list.add(new JumpInsnNode(GOTO, (LabelNode) insns[targets.iterator().next()]));
                    replace.put(insn, list); stats.branches++;
                } else {
                    var pruned = prune(insn, targets, index);
                    if (pruned != null) { var list = new InsnList(); list.add(pruned); replace.put(insn, list); stats.switchesPruned++; }
                }
                continue;
            }
            // Skidfuscator's goto in disguise: throw new X() into a handler of exactly X that re-derives the seed first.
            if (op == NEW && frame.getStackSize() == 0) {
                var list = throwGoto(method, (TypeInsnNode) insn, index);
                if (list != null) {
                    var dup = next(insn); var ctor = next(dup); var athrow = next(ctor);
                    var nullPush = new InsnList(); nullPush.add(new InsnNode(ACONST_NULL));
                    replace.put(insn, nullPush); replace.put(dup, new InsnList()); replace.put(ctor, new InsnList()); replace.put(athrow, list);
                    stats.throwGotos++;
                    continue;
                }
            }
            // A dead store of an obfuscator value.
            if (op == ISTORE && !loads.contains(((VarInsnNode) insn).var) && seedLocals.contains(((VarInsnNode) insn).var)) {
                var list = new InsnList(); list.add(new InsnNode(POP)); replace.put(insn, list); stats.storesDropped++;
                continue;
            }
            // Folding.
            if (!foldable(insn) || constantPush(insn)) continue;
            var out = new Frame<SV>(frame);
            out.execute(insn, interpreter);
            if (out.getStackSize() == 0) continue;
            SV top = out.getStack(out.getStackSize() - 1);
            // A pure call whose result is dropped and that the analysis evaluated without error on every possible input.
            if (insn instanceof MethodInsnNode call && call.getOpcode() == INVOKESTATIC && top.tainted && top.ints != null
                && next(insn) != null && next(insn).getOpcode() == POP && pureCalls.pure(call, null)) {
                var list = new InsnList();
                for (int size : operandSizes(insn)) list.add(new InsnNode(size == 2 ? POP2 : POP));
                replace.put(insn, list); replace.put(next(insn), new InsnList()); stats.folded++;
                continue;
            }
            if (!top.tainted || !top.singleton()) continue;
            var list = new InsnList();
            for (int size : operandSizes(insn)) list.add(new InsnNode(size == 2 ? POP2 : POP));
            var constant = push(top.single());
            generated.add(constant);
            list.add(constant);
            replace.put(insn, list); stats.folded++;
        }
        boolean changed = false;
        for (int i = 0; i < insns.length; i++) {
            var insn = insns[i];
            if (frames[i] == null && insn.getOpcode() >= 0) { method.instructions.remove(insn); stats.dead++; changed = true; continue; }
            var list = replace.get(insn);
            if (list != null) { method.instructions.insertBefore(insn, list); method.instructions.remove(insn); changed = true; }
        }
        changed |= removeIdleHandlers(method, stats);
        return changed;
    }

    /** The case label a goto reaches through {@code iload k; switch} when k is one obfuscator value; null otherwise. */
    private static LabelNode dispatchTarget(JumpInsnNode jump, Frame<SV> frame) {
        var load = start(jump.label);
        if (!(load instanceof VarInsnNode var) || var.getOpcode() != ILOAD || var.var >= frame.getLocals()) return null;
        var dispatch = next(load);
        SV key = frame.getLocal(var.var);
        if (!key.tainted || !key.singleton()) return null;
        // Nothing but labels, frames and line numbers between the load and the switch.
        for (var node = load.getNext(); node != dispatch; node = node.getNext()) if (node.getOpcode() >= 0) return null;
        int value = key.single();
        if (dispatch instanceof TableSwitchInsnNode table)
            return value >= table.min && value <= table.max ? table.labels.get(value - table.min) : table.dflt;
        if (dispatch instanceof LookupSwitchInsnNode lookup) {
            int at = lookup.keys.indexOf(value);
            return at >= 0 ? lookup.labels.get(at) : lookup.dflt;
        }
        return null;
    }

    /** A switch without the cases whose label no input reaches; null when every case stays. */
    private static AbstractInsnNode prune(AbstractInsnNode insn, Set<Integer> targets, Map<AbstractInsnNode,Integer> index) {
        var keys = new ArrayList<Integer>(); var labels = new ArrayList<LabelNode>(); LabelNode dflt;
        if (insn instanceof TableSwitchInsnNode table) {
            for (int k = 0; k < table.labels.size(); k++) { keys.add(table.min + k); labels.add(table.labels.get(k)); }
            dflt = table.dflt;
        } else {
            var lookup = (LookupSwitchInsnNode) insn;
            keys.addAll(lookup.keys); labels.addAll(lookup.labels); dflt = lookup.dflt;
        }
        var keptKeys = new ArrayList<Integer>(); var keptLabels = new ArrayList<LabelNode>();
        for (int k = 0; k < keys.size(); k++) {
            if (labels.get(k) == dflt || !targets.contains(index.get(labels.get(k)))) continue;
            keptKeys.add(keys.get(k)); keptLabels.add(labels.get(k));
        }
        if (!targets.contains(index.get(dflt)) && !keptLabels.isEmpty()) {
            // The default is never taken: the last kept case becomes the default.
            dflt = keptLabels.removeLast(); keptKeys.removeLast();
        }
        int before = keys.size();
        if (keptKeys.size() == before) return null;
        int[] sortedKeys = keptKeys.stream().mapToInt(Integer::intValue).toArray();
        Integer[] order = new Integer[sortedKeys.length];
        for (int k = 0; k < order.length; k++) order[k] = k;
        Arrays.sort(order, Comparator.comparingInt(k -> sortedKeys[k]));
        int[] finalKeys = new int[order.length]; LabelNode[] finalLabels = new LabelNode[order.length];
        for (int k = 0; k < order.length; k++) { finalKeys[k] = sortedKeys[order[k]]; finalLabels[k] = keptLabels.get(order[k]); }
        return new LookupSwitchInsnNode(dflt, finalKeys, finalLabels);
    }

    /** The jump replacing {@code new X; dup; invokespecial X.<init>()V; athrow}, or null when the pattern does not hold. */
    private static InsnList throwGoto(MethodNode method, TypeInsnNode alloc, Map<AbstractInsnNode,Integer> index) {
        var dup = next(alloc); var ctor = next(dup); var athrow = next(ctor);
        if (dup == null || dup.getOpcode() != DUP || !(ctor instanceof MethodInsnNode init) || !init.name.equals("<init>")
            || !init.desc.equals("()V") || !init.owner.equals(alloc.desc) || athrow == null || athrow.getOpcode() != ATHROW) return null;
        for (var node = alloc.getNext(); node != athrow; node = node.getNext()) if (node instanceof LabelNode) return null;
        int at = index.get(athrow);
        for (var block : method.tryCatchBlocks) {
            if (at < index.get(block.start) || at >= index.get(block.end)) continue;
            if (block.type == null || !block.type.equals(alloc.desc)) return null; // the first covering handler must catch exactly X
            var first = block.handler.getNext();
            while (first != null && first.getOpcode() < 0) first = first.getNext();
            if (first == null || first.getOpcode() == ASTORE) return null; // javac's handlers store the exception first
            var list = new InsnList();
            list.add(new JumpInsnNode(GOTO, block.handler));
            return list;
        }
        return null;
    }

    /** Removes try ranges in which no instruction can throw (their handlers become plain code or dead). */
    private static boolean removeIdleHandlers(MethodNode method, Stats stats) {
        boolean changed = false;
        for (var it = method.tryCatchBlocks.iterator(); it.hasNext(); ) {
            var block = it.next();
            boolean throwing = false;
            for (AbstractInsnNode insn = block.start; insn != null && insn != block.end; insn = insn.getNext())
                if (canThrow(insn)) { throwing = true; break; }
            if (!throwing) { it.remove(); stats.handlers++; changed = true; }
        }
        return changed;
    }

    static boolean canThrow(AbstractInsnNode insn) {
        int op = insn.getOpcode();
        if (op < 0) return false;
        if (op >= NOP && op <= ALOAD || op >= ISTORE && op <= ASTORE || op >= POP && op <= SWAP) return false;
        if (op >= IADD && op <= DREM) return op == IDIV || op == IREM || op == LDIV || op == LREM;
        if (op >= INEG && op <= DCMPG) return false;
        if (op >= IFEQ && op <= LOOKUPSWITCH) return false;
        if (op >= IRETURN && op <= RETURN) return false;
        if (op == IFNULL || op == IFNONNULL || op == IINC) return false;
        return true;
    }

    private static boolean foldable(AbstractInsnNode insn) {
        int op = insn.getOpcode();
        if (op == ILOAD || op == GETSTATIC || op == GETFIELD) return true;
        if (op >= IADD && op <= DREM || op >= ISHL && op <= LXOR || op == INEG || op == I2B || op == I2C || op == I2S) return true;
        if (insn instanceof MethodInsnNode call) {
            if (call.getOpcode() == INVOKESTATIC && Type.getReturnType(call.desc).getSort() <= Type.INT && Type.getReturnType(call.desc).getSort() >= Type.BOOLEAN
                && (PureEvaluator.primitiveDescriptor(call.desc) || call.owner.equals("java/lang/Integer") && call.name.equals("parseInt"))) return true;
            return false; // Random(long).nextInt() stays: its result feeds a store that dies with the seed field
        }
        return false;
    }

    private static boolean constantPush(AbstractInsnNode insn) {
        int op = insn.getOpcode();
        return op >= ICONST_M1 && op <= ICONST_5 || op == BIPUSH || op == SIPUSH || insn instanceof LdcInsnNode;
    }

    /** Operand sizes (1 or 2 slots) of a foldable instruction, top of stack last. */
    private static List<Integer> operandSizes(AbstractInsnNode insn) {
        int op = insn.getOpcode();
        if (op == ILOAD || op == GETSTATIC) return List.of();
        if (op == GETFIELD || op == INEG || op == I2B || op == I2C || op == I2S) return List.of(1);
        if (insn instanceof MethodInsnNode call) {
            var sizes = new ArrayList<Integer>();
            if (call.getOpcode() != INVOKESTATIC) sizes.add(1);
            for (var type : Type.getArgumentTypes(call.desc)) sizes.add(type.getSize());
            return sizes;
        }
        return List.of(1, 1); // int binary operations
    }

    static AbstractInsnNode push(int value) {
        if (value >= -1 && value <= 5) return new InsnNode(ICONST_0 + value);
        if (value >= Byte.MIN_VALUE && value <= Byte.MAX_VALUE) return new IntInsnNode(BIPUSH, value);
        if (value >= Short.MIN_VALUE && value <= Short.MAX_VALUE) return new IntInsnNode(SIPUSH, value);
        return new LdcInsnNode(value);
    }

    static AbstractInsnNode next(AbstractInsnNode insn) {
        for (var node = insn.getNext(); node != null; node = node.getNext()) if (node.getOpcode() >= 0) return node;
        return null;
    }

    // ---------------------------------------------------------------- peephole

    boolean peephole(MethodNode method, Stats stats) {
        boolean any = false, changed = true;
        int guard = 0;
        while (changed && guard++ < 64) {
            changed = false;
            var targets = jumpTargets(method);
            for (var insn = method.instructions.getFirst(); insn != null; ) {
                var following = insn.getNext();
                if (insn.getOpcode() == POP || insn.getOpcode() == POP2) {
                    var producer = previousReal(insn);
                    if (producer != null && !targets.contains(labelBetween(producer, insn)) && dropPopped(method, producer, insn)) {
                        changed = true; stats.peephole++; insn = method.instructions.getFirst(); continue;
                    }
                }
                if (insn instanceof JumpInsnNode jump) {
                    var destination = resolve(jump.label);
                    if (destination != jump.label && destination != null) { jump.label = destination; changed = true; stats.peephole++; }
                    if (jump.getOpcode() == GOTO && nextRealOrLabelTarget(jump) == jump.label) {
                        method.instructions.remove(jump); changed = true; stats.peephole++; insn = following; continue;
                    }
                }
                if (insn instanceof TableSwitchInsnNode || insn instanceof LookupSwitchInsnNode) {
                    if (threadSwitch(method, insn)) { changed = true; stats.peephole++; insn = method.instructions.getFirst(); continue; }
                }
                insn = following;
            }
            any |= changed;
        }
        return any;
    }

    /** Removes {@code producer; pop} when the producer has no side effect (its operands are popped instead). */
    private boolean dropPopped(MethodNode method, AbstractInsnNode producer, AbstractInsnNode pop) {
        int op = producer.getOpcode();
        boolean wide = pop.getOpcode() == POP2;
        boolean pushWide = op == LCONST_0 || op == LCONST_1 || op == DCONST_0 || op == DCONST_1 || op == LLOAD || op == DLOAD
            || producer instanceof LdcInsnNode ldc && (ldc.cst instanceof Long || ldc.cst instanceof Double);
        boolean pushNarrow = op >= ACONST_NULL && op <= ICONST_5 || op == FCONST_0 || op == FCONST_1 || op == FCONST_2 || op == BIPUSH
            || op == SIPUSH || op == ILOAD || op == FLOAD || op == ALOAD || op == DUP
            || producer instanceof LdcInsnNode ldc2 && !(ldc2.cst instanceof Long || ldc2.cst instanceof Double);
        if (wide ? pushWide : pushNarrow) { method.instructions.remove(producer); method.instructions.remove(pop); return true; }
        if (wide) return false;
        if (op == IADD || op == ISUB || op == IMUL || op == ISHL || op == ISHR || op == IUSHR || op == IAND || op == IOR || op == IXOR) {
            method.instructions.insertBefore(producer, new InsnNode(POP)); method.instructions.remove(producer); return true;
        }
        if (op == INEG || op == I2B || op == I2C || op == I2S) { method.instructions.remove(producer); return true; }
        if (producer instanceof MethodInsnNode call && call.getOpcode() == INVOKESTATIC && Type.getReturnType(call.desc).getSize() == 1) {
            boolean parse = call.owner.equals("java/lang/Integer") && call.name.equals("parseInt") && call.desc.equals("(Ljava/lang/String;)I")
                && previousReal(call) instanceof LdcInsnNode digits && digits.cst instanceof String text && text.matches("-?[0-9]{1,10}");
            Object[] literalArgs = literalArguments(call);
            // Also a JDK function Skidfuscator planted as a "pure" hash: literal arguments, one of them a folded 32-bit seed
            // hash (at least 2^20), result dropped. The program never called it; it goes with its test.
            boolean planted = literalArgs != null && (call.owner.startsWith("java/") || call.owner.startsWith("javax/"))
                && PureEvaluator.primitiveDescriptor(call.desc)
                && Arrays.stream(literalArgs).anyMatch(v -> v instanceof Integer i && Math.abs((long) i) >= 1 << 20);
            if (parse || PureEvaluator.primitiveDescriptor(call.desc) && literalArgs != null && (planted || pureCalls.pure(call, literalArgs))) {
                var list = new InsnList();
                var args = Type.getArgumentTypes(call.desc);
                for (int k = args.length - 1; k >= 0; k--) list.add(new InsnNode(args[k].getSize() == 2 ? POP2 : POP));
                method.instructions.insertBefore(producer, list);
                method.instructions.remove(producer); method.instructions.remove(pop);
                return true;
            }
        }
        // new Random(<long>).nextInt(); pop
        if (producer instanceof MethodInsnNode nextInt && nextInt.owner.equals("java/util/Random") && nextInt.name.equals("nextInt")
            && SeedInterpreter.randomSeed(nextInt) != null) {
            var init = previousReal(nextInt); var ldc = previousReal(init); var dup = previousReal(ldc); var alloc = previousReal(dup);
            for (var node : List.of(alloc, dup, ldc, init, nextInt, pop)) method.instructions.remove(node);
            return true;
        }
        return false;
    }

    /** A switch whose cases (after jump threading) all lead to one place becomes {@code pop; goto}. */
    private static boolean threadSwitch(MethodNode method, AbstractInsnNode insn) {
        List<LabelNode> labels; LabelNode dflt;
        if (insn instanceof TableSwitchInsnNode table) { labels = table.labels; dflt = table.dflt; }
        else { labels = ((LookupSwitchInsnNode) insn).labels; dflt = ((LookupSwitchInsnNode) insn).dflt; }
        boolean changed = false;
        for (int k = 0; k < labels.size(); k++) {
            var destination = resolve(labels.get(k));
            if (destination != null && destination != labels.get(k)) { labels.set(k, destination); changed = true; }
        }
        var resolvedDefault = resolve(dflt);
        if (resolvedDefault != null && resolvedDefault != dflt) {
            if (insn instanceof TableSwitchInsnNode table) table.dflt = resolvedDefault; else ((LookupSwitchInsnNode) insn).dflt = resolvedDefault;
            dflt = resolvedDefault; changed = true;
        }
        LabelNode only = dflt;
        for (var label : labels) if (label != only && start(label) != start(only) && !sameBlock(label, only)) { only = null; break; }
        if (only != null) {
            var list = new InsnList(); list.add(new InsnNode(POP)); list.add(new JumpInsnNode(GOTO, only));
            method.instructions.insertBefore(insn, list); method.instructions.remove(insn);
            return true;
        }
        return changed;
    }

    /**
     * True when the straight-line code at both labels is the same instructions up to an unconditional jump, return or
     * throw (Skidfuscator's handler dispatch ends, once folded, in identical {@code seed = C; goto handler} cases).
     */
    private static boolean sameBlock(LabelNode a, LabelNode b) {
        var x = start(a); var y = start(b);
        for (int steps = 0; steps < 24 && x != null && y != null; steps++) {
            if (x == y) return true; // the two paths meet (one may fall through where the other jumps)
            if (x instanceof JumpInsnNode j && j.getOpcode() == GOTO) { x = start(j.label); continue; }
            if (y instanceof JumpInsnNode j && j.getOpcode() == GOTO) { y = start(j.label); continue; }
            if (!sameInsn(x, y)) return false;
            int op = x.getOpcode();
            if (op >= IRETURN && op <= RETURN || op == ATHROW) return true;
            if (x instanceof JumpInsnNode || x instanceof TableSwitchInsnNode || x instanceof LookupSwitchInsnNode) return false;
            x = next(x); y = next(y);
        }
        return false;
    }

    private static boolean sameInsn(AbstractInsnNode x, AbstractInsnNode y) {
        if (x.getOpcode() != y.getOpcode() || x.getType() != y.getType()) return false;
        return switch (x) {
            case VarInsnNode v -> v.var == ((VarInsnNode) y).var;
            case IntInsnNode i -> i.operand == ((IntInsnNode) y).operand;
            case LdcInsnNode l -> l.cst.equals(((LdcInsnNode) y).cst);
            case JumpInsnNode j -> start(j.label) == start(((JumpInsnNode) y).label);
            case InsnNode plain -> true;
            default -> false;
        };
    }

    /** First real instruction at or after a label. */
    private static AbstractInsnNode start(LabelNode label) {
        AbstractInsnNode node = label;
        while (node != null && node.getOpcode() < 0) node = node.getNext();
        return node;
    }

    /** Follows a chain of unconditional jumps from a label; a cycle leaves the label as it is. */
    private static LabelNode resolve(LabelNode label) {
        var seen = new HashSet<LabelNode>();
        seen.add(label);
        LabelNode current = label;
        while (true) {
            var first = start(current);
            if (!(first instanceof JumpInsnNode jump) || jump.getOpcode() != GOTO) return current;
            if (!seen.add(jump.label)) return label;
            current = jump.label;
        }
    }

    private static LabelNode nextRealOrLabelTarget(JumpInsnNode jump) {
        for (var node = jump.getNext(); node != null; node = node.getNext()) {
            if (node instanceof LabelNode label && label == jump.label) return label;
            if (node.getOpcode() >= 0) return null;
        }
        return null;
    }

    private static AbstractInsnNode previousReal(AbstractInsnNode insn) {
        if (insn == null) return null;
        for (var node = insn.getPrevious(); node != null; node = node.getPrevious()) {
            if (node.getOpcode() >= 0) return node;
        }
        return null;
    }

    /** A label between two instructions that something jumps to (then the pair is not straight-line code). */
    private static LabelNode labelBetween(AbstractInsnNode first, AbstractInsnNode second) {
        for (var node = first.getNext(); node != null && node != second; node = node.getNext())
            if (node instanceof LabelNode label) return label;
        return null;
    }

    private static Set<LabelNode> jumpTargets(MethodNode method) {
        var targets = new HashSet<LabelNode>();
        for (var insn : method.instructions) {
            if (insn instanceof JumpInsnNode jump) targets.add(jump.label);
            else if (insn instanceof TableSwitchInsnNode table) { targets.add(table.dflt); targets.addAll(table.labels); }
            else if (insn instanceof LookupSwitchInsnNode lookup) { targets.add(lookup.dflt); targets.addAll(lookup.labels); }
        }
        for (var block : method.tryCatchBlocks) { targets.add(block.handler); targets.add(block.start); targets.add(block.end); }
        return targets;
    }
}
