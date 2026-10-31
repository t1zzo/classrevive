package dev.classrevive.transforms.skid;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.Frame;
import java.util.*;
import java.util.function.IntPredicate;

/**
 * Phase 2CN: a forward data-flow analysis over one method with {@link SeedInterpreter} values, path-sensitive on int
 * branches: when a branch tests a value computed from a local by known operations, each edge carries the local's set
 * narrowed to the inputs that take that edge, and an edge no input takes is not followed. This is what makes the
 * obfuscator's per-block seed a single constant in every block, even after an exception handler (where the seed is one of
 * the protected blocks' seeds until the handler's switch on its hash sorts them).
 *
 * <p>Only the frames are computed; nothing is executed.
 */
final class SeedAnalyzer implements Opcodes {
    /** {@code assumed}: branches decided by the trap invariant, not by evaluation. */
    record Result(AbstractInsnNode[] insns, Frame<SV>[] frames, Map<AbstractInsnNode,Set<Integer>> feasible, Set<AbstractInsnNode> assumed) {
        int index(AbstractInsnNode insn) { for (int i = 0; i < insns.length; i++) if (insns[i] == insn) return i; return -1; }
    }

    static final int WIDEN_AFTER = 6;
    private final Set<AbstractInsnNode> assumed = Collections.newSetFromMap(new IdentityHashMap<>());
    private MethodNode current;
    private AbstractInsnNode[] currentInsns;
    private Map<AbstractInsnNode,Integer> currentIndex;
    private final SeedInterpreter interpreter;
    /** Why the last {@link #analyze} returned null. */
    String lastFailure = "";
    SeedAnalyzer(SeedInterpreter interpreter) { this.interpreter = interpreter; }

    /** Null when the method cannot be analyzed (JSR/RET, a budget overrun or a malformed body). */
    @SuppressWarnings("unchecked")
    Result analyze(String owner, MethodNode method, Map<Integer,int[]> knownParameters) { return analyze(owner, method, knownParameters, Set.of()); }

    /** {@code seedLocals}: locals known to hold the obfuscator's seed (from earlier rounds); what they store stays tainted. */
    @SuppressWarnings("unchecked")
    Result analyze(String owner, MethodNode method, Map<Integer,int[]> knownParameters, Set<Integer> seedLocals) {
        var insns = method.instructions.toArray();
        int n = insns.length;
        if (n == 0) { lastFailure = "no code"; return null; }
        var index = new IdentityHashMap<AbstractInsnNode,Integer>();
        for (int i = 0; i < n; i++) index.put(insns[i], i);
        var handlers = new ArrayList<List<TryCatchBlockNode>>();
        for (int i = 0; i < n; i++) handlers.add(new ArrayList<>());
        for (var block : method.tryCatchBlocks) {
            int start = index.get(block.start), end = index.get(block.end);
            for (int i = start; i < end; i++) handlers.get(i).add(block);
        }
        Frame<SV>[] frames = new Frame[n];
        current = method; currentInsns = insns; currentIndex = index;
        var initial = new Frame<SV>(method.maxLocals, method.maxStack);
        boolean isStatic = (method.access & ACC_STATIC) != 0;
        int local = 0;
        if (!isStatic) initial.setLocal(local++, method.name.equals("<init>") ? SV.ref(owner, null) : SV.ref(owner, null));
        for (var type : Type.getArgumentTypes(method.desc)) {
            SV value = interpreter.newValue(type);
            if (knownParameters.containsKey(local)) value = SV.ints(knownParameters.get(local), null, true);
            initial.setLocal(local++, value);
            if (type.getSize() == 2) initial.setLocal(local++, SV.UNINIT);
        }
        while (local < method.maxLocals) initial.setLocal(local++, SV.UNINIT);
        initial.setReturn(interpreter.newValue(Type.getReturnType(method.desc)));
        var queue = new ArrayDeque<Integer>();
        var queued = new boolean[n];
        var widenings = new int[n];
        frames[0] = initial; queue.add(0); queued[0] = true;
        long budget = 200L * n + 20_000;
        try {
            while (!queue.isEmpty()) {
                if (--budget < 0) { lastFailure = "budget"; return null; }
                int i = queue.poll(); queued[i] = false;
                var insn = insns[i];
                var frame = frames[i];
                int op = insn.getOpcode();
                if (op == JSR || op == RET) { lastFailure = "jsr"; return null; }
                // Only an instruction that can throw reaches a handler; the seed stores between them never do, so the handler
                // sees exactly the seeds of the protected blocks.
                if (MethodRewriter.canThrow(insn)) for (var block : handlers.get(i)) {
                    var handlerFrame = new Frame<SV>(frame);
                    handlerFrame.clearStack();
                    handlerFrame.push(SV.ref(block.type == null ? "java/lang/Throwable" : block.type, null));
                    merge(frames, index.get(block.handler), handlerFrame, queue, queued, i, null);
                }
                if (op < 0) { if (i + 1 < n) merge(frames, i + 1, new Frame<>(frame), queue, queued, i, widenings); continue; }
                var out = new Frame<SV>(frame);
                out.execute(insn, interpreter);
                if (insn instanceof VarInsnNode var && var.getOpcode() >= ISTORE && var.getOpcode() <= ASTORE) {
                    invalidate(out, var.var);
                    if (var.getOpcode() == ISTORE && seedLocals.contains(var.var)) out.setLocal(var.var, out.getLocal(var.var).taint());
                }
                if (insn instanceof IincInsnNode inc) invalidate(out, inc.var);
                for (var edge : edges(insn, frame, out, index, i)) {
                    if (edge.target >= n) continue;
                    merge(frames, edge.target, edge.frame, queue, queued, i, widenings);
                }
            }
        } catch (AnalyzerException | RuntimeException error) {
            lastFailure = error.toString();
            return null;
        }
        // Feasible successors of every reachable branch, from the fixed-point frames.
        var feasible = new IdentityHashMap<AbstractInsnNode,Set<Integer>>();
        assumed.clear();
        for (int i = 0; i < n; i++) {
            if (frames[i] == null) continue;
            var insn = insns[i];
            if (insn instanceof JumpInsnNode || insn instanceof TableSwitchInsnNode || insn instanceof LookupSwitchInsnNode) {
                var out = new Frame<SV>(frames[i]);
                try { out.execute(insn, interpreter); } catch (AnalyzerException error) { return null; }
                var targets = new TreeSet<Integer>();
                for (var edge : edges(insn, frames[i], out, index, i)) targets.add(edge.target);
                feasible.put(insn, targets);
            }
        }
        return new Result(insns, frames, feasible, Set.copyOf(assumed));
    }

    private record Edge(int target, Frame<SV> frame) {}

    /** The successors of {@code insn}, each with its frame narrowed by the branch it takes; infeasible edges are absent. */
    private List<Edge> edges(AbstractInsnNode insn, Frame<SV> before, Frame<SV> after, Map<AbstractInsnNode,Integer> index, int i) {
        int op = insn.getOpcode();
        var edges = new ArrayList<Edge>();
        if (insn instanceof JumpInsnNode jump) {
            int target = index.get(jump.label);
            if (op == GOTO) { edges.add(new Edge(target, after)); return edges; }
            if (op >= IFEQ && op <= IFLE || op >= IF_ICMPEQ && op <= IF_ICMPLE) {
                SV left, right;
                if (op <= IFLE) { left = before.getStack(before.getStackSize() - 1); right = SV.literal(0); }
                else { left = before.getStack(before.getStackSize() - 2); right = before.getStack(before.getStackSize() - 1); }
                int cmp = op <= IFLE ? op : op - IF_ICMPEQ + IFEQ;
                // Only the obfuscator's tests are decided; the program's own branches on constants stay as written.
                if (!left.tainted && !right.tainted) { edges.add(new Edge(target, after)); edges.add(new Edge(i + 1, after)); }
                else {
                    branch(edges, after, left, right, cmp, true, target);
                    branch(edges, after, left, right, cmp, false, i + 1);
                }
                // Skidfuscator's trap invariant: when only the obfuscator's values are compared and the analysis cannot decide,
                // the side that goes straight to a trap (an uncaught throw it planted) is the one never taken.
                // With known constants on both sides the outcome is evaluated (and must avoid the trap); otherwise it is assumed.
                if (edges.size() == 2 && obfuscatorOnly(left) && obfuscatorOnly(right)) {
                    boolean trapTaken = trap(target), trapFallthrough = trap(i + 1);
                    if (trapTaken != trapFallthrough) {
                        int dropped = trapTaken ? target : i + 1;
                        if (left.singleton() && right.singleton()) {
                            boolean taken = Interpret.compare(cmp, left.single(), right.single());
                            int kept = taken ? target : i + 1;
                            if (kept != dropped) edges.removeIf(edge -> edge.target == dropped);
                        } else {
                            edges.removeIf(edge -> edge.target == dropped);
                            assumed.add(insn);
                        }
                    }
                }
                return edges;
            }
            edges.add(new Edge(target, after)); edges.add(new Edge(i + 1, after)); // reference comparisons, null checks
            return edges;
        }
        if (insn instanceof TableSwitchInsnNode || insn instanceof LookupSwitchInsnNode) {
            SV key = before.getStack(before.getStackSize() - 1);
            if (!key.tainted) key = SV.INT_TOP;
            var cases = new LinkedHashMap<Integer,LabelNode>();
            LabelNode dflt;
            if (insn instanceof TableSwitchInsnNode table) {
                for (int k = 0; k < table.labels.size(); k++) cases.put(table.min + k, table.labels.get(k));
                dflt = table.dflt;
            } else {
                var lookup = (LookupSwitchInsnNode) insn;
                for (int k = 0; k < lookup.keys.size(); k++) cases.put(lookup.keys.get(k), lookup.labels.get(k));
                dflt = lookup.dflt;
            }
            for (var entry : cases.entrySet()) {
                int k = entry.getKey();
                var frame = narrow(after, key, value -> value == k);
                if (frame != null) edges.add(new Edge(index.get(entry.getValue()), frame));
            }
            var frame = narrow(after, key, value -> !cases.containsKey(value));
            if (frame != null) edges.add(new Edge(index.get(dflt), frame));
            return edges;
        }
        if (op >= IRETURN && op <= RETURN || op == ATHROW) return edges;
        edges.add(new Edge(i + 1, after));
        return edges;
    }

    private static boolean obfuscatorOnly(SV value) { return value.kind == SV.Kind.INT && (value.tainted || value.literal); }

    /**
     * A trap: straight-line code from {@code at} that throws {@code null} or a new exception built without an argument (or
     * with Skidfuscator's "Error in hash"), not caught by a handler of exactly that type (that would be a throw-goto).
     */
    private boolean trap(int at) {
        if (closedLoop(at)) return true;
        int steps = 0;
        for (int i = at; i < currentInsns.length && steps < 12; i++) {
            var insn = currentInsns[i];
            int op = insn.getOpcode();
            if (op < 0) continue;
            steps++;
            if (op == ACONST_NULL) { var next = nextReal(i + 1); return next != null && next.getOpcode() == ATHROW; }
            if (insn instanceof TypeInsnNode alloc && op == NEW) {
                var dup = nextReal(i + 1);
                if (dup == null || dup.getOpcode() != DUP) return false;
                var arg = nextReal(currentIndex.get(dup) + 1);
                var init = arg instanceof LdcInsnNode ldc && "Error in hash".equals(ldc.cst) ? nextReal(currentIndex.get(arg) + 1) : arg;
                if (!(init instanceof MethodInsnNode ctor) || !ctor.name.equals("<init>") || !ctor.owner.equals(alloc.desc)) return false;
                var athrow = nextReal(currentIndex.get(init) + 1);
                if (athrow == null || athrow.getOpcode() != ATHROW) return false;
                int t = currentIndex.get(athrow);
                for (var block : current.tryCatchBlocks)
                    if (t >= currentIndex.get(block.start) && t < currentIndex.get(block.end) && alloc.desc.equals(block.type)) return false;
                return true;
            }
            // A trap may first scramble a seed field: constants, field reads, XOR and field writes only.
            boolean scramble = op >= ICONST_M1 && op <= LDC || op == GETSTATIC || op == GETFIELD || op == PUTSTATIC || op == PUTFIELD
                || op == IXOR || op == ALOAD || op == ILOAD;
            if (!scramble) return false;
        }
        return false;
    }

    /**
     * A region reachable from {@code at} (at most 48 instructions) that has no way out and no side effect: constants, local
     * loads and stores, arithmetic, stack moves, static calls with primitive descriptors, jumps and switches inside the
     * region. Skidfuscator plants such endless loops as traps too.
     */
    private boolean closedLoop(int at) {
        var seen = new HashSet<Integer>();
        var pending = new ArrayDeque<Integer>(List.of(at));
        while (!pending.isEmpty()) {
            int i = pending.poll();
            if (i >= currentInsns.length || !seen.add(i)) continue;
            if (seen.size() > 48) return false;
            var insn = currentInsns[i];
            int op = insn.getOpcode();
            if (op < 0) { pending.add(i + 1); continue; }
            if (insn instanceof JumpInsnNode jump) {
                if (op == JSR) return false;
                pending.add(currentIndex.get(jump.label));
                if (op != GOTO) pending.add(i + 1);
                continue;
            }
            if (insn instanceof TableSwitchInsnNode table) {
                pending.add(currentIndex.get(table.dflt)); for (var l : table.labels) pending.add(currentIndex.get(l)); continue;
            }
            if (insn instanceof LookupSwitchInsnNode lookup) {
                pending.add(currentIndex.get(lookup.dflt)); for (var l : lookup.labels) pending.add(currentIndex.get(l)); continue;
            }
            boolean pure = op >= ACONST_NULL && op <= DLOAD || op >= ISTORE && op <= DSTORE || op >= POP && op <= DCMPG || op == IINC
                || insn instanceof MethodInsnNode call && op == INVOKESTATIC && PureEvaluator.primitiveDescriptor(call.desc);
            if (!pure) return false;
            pending.add(i + 1);
        }
        return !seen.isEmpty();
    }

    private AbstractInsnNode nextReal(int from) {
        for (int i = from; i < currentInsns.length; i++) if (currentInsns[i].getOpcode() >= 0) return currentInsns[i];
        return null;
    }

    private void branch(List<Edge> edges, Frame<SV> after, SV left, SV right, int cmp, boolean taken, int target) {
        Frame<SV> frame;
        if (right.singleton() && left.kind == SV.Kind.INT) {
            int c = right.single();
            frame = narrow(after, left, v -> Interpret.compare(cmp, v, c) == taken);
        } else if (left.singleton() && right.kind == SV.Kind.INT) {
            int c = left.single();
            frame = narrow(after, right, v -> Interpret.compare(cmp, c, v) == taken);
        } else frame = after;
        if (frame != null) edges.add(new Edge(target, frame));
    }

    /**
     * The frame on an edge where {@code tested} satisfies {@code keep}: null when no possible value does. When the tested
     * value comes from a local, that local's set is narrowed to the inputs that take the edge.
     */
    private static Frame<SV> narrow(Frame<SV> after, SV tested, IntPredicate keep) {
        if (tested.kind != SV.Kind.INT) return after;
        if (tested.ints != null) {
            boolean any = false;
            for (int v : tested.ints) if (keep.test(v)) { any = true; break; }
            if (!any) return null;
        }
        var prov = tested.prov;
        if (prov == null || prov.local() >= after.getLocals()) return after;
        SV local = after.getLocal(prov.local());
        if (local.kind != SV.Kind.INT || local.ints == null) return after;
        var kept = new ArrayList<Integer>();
        for (int s : local.ints) {
            Integer v = prov.fn().apply(s);
            if (v == null || keep.test(v)) kept.add(s);
        }
        if (kept.isEmpty()) return null;
        if (kept.size() == local.ints.length) return after;
        var frame = new Frame<SV>(after);
        frame.setLocal(prov.local(), SV.ints(SV.sorted(kept), null, local.tainted));
        return frame;
    }

    /** A store to {@code local} makes stack values computed from its old value lose that provenance. */
    private static void invalidate(Frame<SV> frame, int local) {
        for (int s = 0; s < frame.getStackSize(); s++) {
            SV value = frame.getStack(s);
            if (value.prov != null && value.prov.local() == local) frame.setStack(s, value.withoutProv());
        }
        for (int l = 0; l < frame.getLocals(); l++) {
            SV value = frame.getLocal(l);
            if (value.prov != null) frame.setLocal(l, value.withoutProv());
        }
    }

    /**
     * Merges into a successor's frame. Along a backward edge (a loop), after {@value #WIDEN_AFTER} changes, int values that
     * still grow become unknown (their taint kept): a loop counter initialized from a decrypted constant would otherwise
     * grow one value per iteration. Handler entries (merged from the protected instructions) are never widened, so the set
     * of seeds a handler can see stays exact.
     */
    private void merge(Frame<SV>[] frames, int target, Frame<SV> frame, Deque<Integer> queue, boolean[] queued, int source, int[] widenings)
            throws AnalyzerException {
        if (frames[target] == null) {
            frames[target] = new Frame<>(frame);
        } else {
            var before = new Frame<SV>(frames[target]);
            if (!frames[target].merge(frame, interpreter)) return;
            if (widenings != null && target <= source && ++widenings[target] > WIDEN_AFTER) {
                var merged = frames[target];
                for (int l = 0; l < merged.getLocals(); l++) {
                    SV value = merged.getLocal(l);
                    if (value.kind == SV.Kind.INT && value.ints != null && !value.equals(before.getLocal(l)))
                        merged.setLocal(l, SV.ints(null, null, value.tainted));
                }
                for (int k = 0; k < merged.getStackSize(); k++) {
                    SV value = merged.getStack(k);
                    if (value.kind == SV.Kind.INT && value.ints != null && !value.equals(before.getStack(k)))
                        merged.setStack(k, SV.ints(null, null, value.tainted));
                }
            }
        }
        if (!queued[target]) { queued[target] = true; queue.add(target); }
    }
}
