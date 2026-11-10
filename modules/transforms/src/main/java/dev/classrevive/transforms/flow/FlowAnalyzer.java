package dev.classrevive.transforms.flow;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.Frame;
import java.util.*;

/**
 * Phase 2DL: a forward data-flow analysis of one method over {@link FV} values that follows only feasible edges. A
 * conditional branch or switch whose outcome {@link FlowInterpreter#compare} decides has one successor; on each edge of an
 * undecided test the tested value (every slot with its value number) is narrowed to the inputs that take it, and an edge
 * no input takes is not followed. Loops converge by widening intervals that keep growing along a back edge.
 *
 * <p>Exception edges: an instruction that can throw ({@link Cfg#canThrow}) reaches the handlers covering it. A handler
 * that catches {@code VirtualMachineError} (any-type, {@code Throwable}, {@code Error}, ...) is reached from every
 * covered instruction, since the JVM may raise such an error anywhere. Nothing is executed.
 */
final class FlowAnalyzer implements Opcodes {
    static final int WIDEN_AFTER = 4;
    private static final Set<String> VM_ERROR_CATCHERS = Set.of("java/lang/Throwable", "java/lang/Error", "java/lang/VirtualMachineError",
        "java/lang/OutOfMemoryError", "java/lang/StackOverflowError", "java/lang/InternalError", "java/lang/UnknownError");

    record Result(AbstractInsnNode[] insns, Frame<FV>[] frames, Map<AbstractInsnNode,Integer> index,
                  Map<AbstractInsnNode,Set<Integer>> feasible) {}
    record Edge(int target, Frame<FV> frame) {}

    private final FlowInterpreter interpreter;
    String lastFailure = "";
    FlowAnalyzer(FlowInterpreter interpreter) { this.interpreter = interpreter; }

    static boolean catchesVmErrors(TryCatchBlockNode block) { return block.type == null || VM_ERROR_CATCHERS.contains(block.type); }

    /** Null when the method cannot be analyzed ({@link #lastFailure} says why). */
    @SuppressWarnings("unchecked")
    Result analyze(String owner, MethodNode method) {
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
        Frame<FV>[] frames = new Frame[n];
        var initial = new Frame<FV>(method.maxLocals, method.maxStack);
        int local = 0;
        if ((method.access & ACC_STATIC) == 0) initial.setLocal(local++, FV.ref(FV.NONNULL, "this", null));
        for (var type : Type.getArgumentTypes(method.desc)) {
            initial.setLocal(local, interpreter.fresh(type, new Object()));
            local += type.getSize();
            if (type.getSize() == 2) initial.setLocal(local - 1, FV.UNINIT);
        }
        while (local < method.maxLocals) initial.setLocal(local++, FV.UNINIT);
        initial.setReturn(interpreter.newValue(Type.getReturnType(method.desc)));
        var handlerValues = new IdentityHashMap<TryCatchBlockNode,FV>();
        for (var block : method.tryCatchBlocks) handlerValues.put(block, FV.ref(FV.NONNULL, block, null));
        var queue = new ArrayDeque<Integer>();
        var queued = new boolean[n];
        var widenings = new int[n];
        frames[0] = initial; queue.add(0); queued[0] = true;
        long budget = 100L * n + 20_000;
        try {
            while (!queue.isEmpty()) {
                if (--budget < 0) { lastFailure = "analysis budget exceeded"; return null; }
                int i = queue.poll(); queued[i] = false;
                var insn = insns[i];
                var frame = frames[i];
                int op = insn.getOpcode();
                if (op == JSR || op == RET) { lastFailure = "jsr/ret subroutines"; return null; }
                if (op >= 0) for (var block : handlers.get(i)) {
                    if (!Cfg.canThrow(insn) && !catchesVmErrors(block)) continue;
                    var handlerFrame = new Frame<FV>(frame);
                    handlerFrame.clearStack();
                    handlerFrame.push(handlerValues.get(block));
                    merge(frames, index.get(block.handler), handlerFrame, queue, queued, i, null);
                }
                if (op < 0) { if (i + 1 < n) merge(frames, i + 1, new Frame<>(frame), queue, queued, i, widenings); continue; }
                var out = execute(frame, insn);
                for (var edge : edges(insn, frame, out, index, i)) {
                    if (edge.target >= n) { lastFailure = "falls off the end of the code"; return null; }
                    merge(frames, edge.target, edge.frame, queue, queued, i, widenings);
                }
            }
        } catch (AnalyzerException | RuntimeException error) {
            lastFailure = error.toString();
            return null;
        }
        var feasible = new IdentityHashMap<AbstractInsnNode,Set<Integer>>();
        for (int i = 0; i < n; i++) {
            if (frames[i] == null) continue;
            var insn = insns[i];
            if (insn instanceof JumpInsnNode || insn instanceof TableSwitchInsnNode || insn instanceof LookupSwitchInsnNode) {
                try {
                    var targets = new TreeSet<Integer>();
                    for (var edge : edges(insn, frames[i], execute(frames[i], insn), index, i)) targets.add(edge.target);
                    feasible.put(insn, targets);
                } catch (AnalyzerException error) { lastFailure = error.toString(); return null; }
            }
        }
        return new Result(insns, frames, index, feasible);
    }

    /** The frame after {@code insn}: its result with every older value of the same producer forgotten. */
    Frame<FV> execute(Frame<FV> before, AbstractInsnNode insn) throws AnalyzerException {
        var out = new Frame<FV>(before);
        out.execute(insn, interpreter);
        // A value numbered by this instruction (or one whose parity refers to it) is now stale everywhere but on top.
        boolean produced = out.getStackSize() > 0 && out.getStack(out.getStackSize() - 1).mentions(insn)
            || insn instanceof IincInsnNode inc && out.getLocal(inc.var).mentions(insn);
        if (produced) {
            int top = insn instanceof IincInsnNode ? -1 : out.getStackSize() - 1;
            for (int s = 0; s < out.getStackSize(); s++) if (s != top) out.setStack(s, out.getStack(s).forget(insn));
            for (int l = 0; l < out.getLocals(); l++)
                if (!(insn instanceof IincInsnNode inc && inc.var == l)) out.setLocal(l, out.getLocal(l).forget(insn));
        }
        return out;
    }

    /** The successors of {@code insn}, each with its frame narrowed by the branch it takes; infeasible edges are absent. */
    List<Edge> edges(AbstractInsnNode insn, Frame<FV> before, Frame<FV> after, Map<AbstractInsnNode,Integer> index, int i) {
        int op = insn.getOpcode();
        var edges = new ArrayList<Edge>();
        if (insn instanceof JumpInsnNode jump) {
            int target = index.get(jump.label);
            if (op == GOTO) { edges.add(new Edge(target, after)); return edges; }
            if (op >= IFEQ && op <= IF_ICMPLE) {
                FV left, right;
                if (op <= IFLE) { left = before.getStack(before.getStackSize() - 1); right = FV.constant(0); }
                else { left = before.getStack(before.getStackSize() - 2); right = before.getStack(before.getStackSize() - 1); }
                int cmp = op <= IFLE ? op : op - IF_ICMPEQ + IFEQ;
                Boolean decided = FlowInterpreter.compare(cmp, left, right);
                if (decided == null || decided) { var f = narrowInt(after, cmp, left, right, true); if (f != null) edges.add(new Edge(target, f)); }
                if (decided == null || !decided) { var f = narrowInt(after, cmp, left, right, false); if (f != null) edges.add(new Edge(i + 1, f)); }
                return edges;
            }
            if (op == IFNULL || op == IFNONNULL) {
                FV value = before.getStack(before.getStackSize() - 1);
                boolean nullTaken = op == IFNULL;
                if (value.nullness != FV.NONNULL) edges.add(new Edge(nullTaken ? target : i + 1, narrowRef(after, value, FV.NULL)));
                if (value.nullness != FV.NULL) edges.add(new Edge(nullTaken ? i + 1 : target, narrowRef(after, value, FV.NONNULL)));
                return edges;
            }
            if (op == IF_ACMPEQ || op == IF_ACMPNE) {
                FV a = before.getStack(before.getStackSize() - 2), b = before.getStack(before.getStackSize() - 1);
                Boolean equal = a.id != null && a.id == b.id ? Boolean.TRUE
                    : a.nullness == FV.NULL && b.nullness == FV.NULL ? Boolean.TRUE
                    : a.nullness == FV.NULL && b.nullness == FV.NONNULL || a.nullness == FV.NONNULL && b.nullness == FV.NULL ? Boolean.FALSE : null;
                boolean takenWhenEqual = op == IF_ACMPEQ;
                if (equal == null || equal) edges.add(new Edge(takenWhenEqual ? target : i + 1, after));
                if (equal == null || !equal) edges.add(new Edge(takenWhenEqual ? i + 1 : target, after));
                return edges;
            }
            throw new IllegalStateException("jump " + op);
        }
        if (insn instanceof TableSwitchInsnNode || insn instanceof LookupSwitchInsnNode) {
            FV key = before.getStack(before.getStackSize() - 1);
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
                var value = FV.constant(entry.getKey());
                if (FlowInterpreter.compare(IFEQ, key, value) == Boolean.FALSE) continue;
                var f = narrowInt(after, IFEQ, key, value, true);
                if (f != null) edges.add(new Edge(index.get(entry.getValue()), f));
            }
            if (!coveredByCases(key, cases.keySet())) edges.add(new Edge(index.get(dflt), after));
            return edges;
        }
        if (op >= IRETURN && op <= RETURN || op == ATHROW) return edges;
        edges.add(new Edge(i + 1, after));
        return edges;
    }

    /** True when every possible key value is one of the cases (so the default is never taken). */
    private static boolean coveredByCases(FV key, Set<Integer> cases) {
        if (key.kind != FV.Kind.INT || (long) key.hi - key.lo >= cases.size()) return false;
        for (long v = key.lo; v <= key.hi; v++) {
            if (!key.ptop && key.pbase == null && (v & 1) != key.pbit) continue;
            if (!cases.contains((int) v)) return false;
        }
        return true;
    }

    /** The frame on an int-test edge: the operands' slots narrowed; null when no value takes the edge. */
    private static Frame<FV> narrowInt(Frame<FV> after, int cmp, FV left, FV right, boolean taken) {
        var frame = after;
        if (right.isConstant() || !left.isConstant()) {
            var narrowed = FlowInterpreter.narrowed(cmp, left, right, taken);
            if (narrowed == null) return null;
            frame = replace(frame, left, narrowed);
        }
        if (left.isConstant() || !right.isConstant()) {
            var narrowed = FlowInterpreter.narrowed(FlowInterpreter.swap(cmp), right, left, taken);
            if (narrowed == null) return null;
            frame = replace(frame, right, narrowed);
        }
        return frame;
    }

    /** Every slot holding {@code value} (same value number) gets the interval {@code range}. */
    private static Frame<FV> replace(Frame<FV> frame, FV value, long[] range) {
        if (value.id == null || value.kind != FV.Kind.INT || range[0] == value.lo && range[1] == value.hi) return frame;
        var copy = new Frame<FV>(frame);
        for (int l = 0; l < copy.getLocals(); l++) {
            var slot = copy.getLocal(l);
            if (slot.kind == FV.Kind.INT && slot.id == value.id) copy.setLocal(l, narrowTo(slot, range));
        }
        for (int s = 0; s < copy.getStackSize(); s++) {
            var slot = copy.getStack(s);
            if (slot.kind == FV.Kind.INT && slot.id == value.id) copy.setStack(s, narrowTo(slot, range));
        }
        return copy;
    }
    private static FV narrowTo(FV slot, long[] range) {
        var result = slot.withInterval(Math.max(slot.lo, range[0]), Math.min(slot.hi, range[1]));
        return result == null ? slot : result;
    }

    private static Frame<FV> narrowRef(Frame<FV> after, FV value, int nullness) {
        if (value.id == null || value.nullness == nullness) return after;
        var copy = new Frame<FV>(after);
        for (int l = 0; l < copy.getLocals(); l++) if (copy.getLocal(l).kind == FV.Kind.REF && copy.getLocal(l).id == value.id)
            copy.setLocal(l, copy.getLocal(l).withNullness(nullness));
        for (int s = 0; s < copy.getStackSize(); s++) if (copy.getStack(s).kind == FV.Kind.REF && copy.getStack(s).id == value.id)
            copy.setStack(s, copy.getStack(s).withNullness(nullness));
        return copy;
    }

    private void merge(Frame<FV>[] frames, int target, Frame<FV> frame, Deque<Integer> queue, boolean[] queued, int source, int[] widenings)
            throws AnalyzerException {
        if (frames[target] == null) {
            frames[target] = new Frame<>(frame);
        } else {
            var before = new Frame<FV>(frames[target]);
            if (!frames[target].merge(frame, interpreter)) return;
            if (widenings != null && target <= source && ++widenings[target] > WIDEN_AFTER) {
                var merged = frames[target];
                for (int l = 0; l < merged.getLocals(); l++)
                    if (!merged.getLocal(l).equals(before.getLocal(l))) merged.setLocal(l, merged.getLocal(l).widen());
                for (int s = 0; s < merged.getStackSize(); s++)
                    if (!merged.getStack(s).equals(before.getStack(s))) merged.setStack(s, merged.getStack(s).widen());
            }
        }
        if (!queued[target]) { queued[target] = true; queue.add(target); }
    }
}
