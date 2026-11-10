package dev.classrevive.transforms.flow;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;
import java.util.*;

/**
 * Phase 2DL: control-flow helpers shared by the whole-jar reversers (Phase 2CN's {@code MethodRewriter}) and the generic
 * {@link ControlFlowPass}: which instructions can throw, jump chains, block starts and switch pruning. Pure tree
 * manipulation; nothing is loaded or run.
 */
public final class Cfg implements Opcodes {
    private Cfg() {}

    /**
     * Whether the instruction can complete abruptly with an exception the program could catch: loads, stores, constants,
     * stack moves, arithmetic except integer division, comparisons, branches and returns cannot ({@code VirtualMachineError}s
     * aside, which {@link FlowAnalyzer} handles separately).
     */
    public static boolean canThrow(AbstractInsnNode insn) {
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

    /** First real instruction at or after a node (labels, line numbers and frames skipped). */
    public static AbstractInsnNode start(AbstractInsnNode node) {
        while (node != null && node.getOpcode() < 0) node = node.getNext();
        return node;
    }

    /** The next real instruction after {@code insn}, or null. */
    public static AbstractInsnNode nextReal(AbstractInsnNode insn) {
        return insn == null ? null : start(insn.getNext());
    }

    /** The previous real instruction before {@code insn}, or null. */
    public static AbstractInsnNode previousReal(AbstractInsnNode insn) {
        if (insn == null) return null;
        for (var node = insn.getPrevious(); node != null; node = node.getPrevious()) if (node.getOpcode() >= 0) return node;
        return null;
    }

    /** Follows a chain of unconditional jumps from a label; a cycle leaves the label as it is. */
    public static LabelNode resolve(LabelNode label) {
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

    /** Every label a jump, a switch or an exception table entry refers to. */
    public static Set<LabelNode> jumpTargets(MethodNode method) {
        var targets = new HashSet<LabelNode>();
        for (var insn : method.instructions) {
            if (insn instanceof JumpInsnNode jump) targets.add(jump.label);
            else if (insn instanceof TableSwitchInsnNode table) { targets.add(table.dflt); targets.addAll(table.labels); }
            else if (insn instanceof LookupSwitchInsnNode lookup) { targets.add(lookup.dflt); targets.addAll(lookup.labels); }
        }
        for (var block : method.tryCatchBlocks) { targets.add(block.handler); targets.add(block.start); targets.add(block.end); }
        return targets;
    }

    /**
     * A switch without the cases whose label is not among the feasible {@code targets} (instruction indices); null when
     * every case stays. When the default is never taken the last kept case becomes the default.
     */
    public static AbstractInsnNode prune(AbstractInsnNode insn, Set<Integer> targets, Map<AbstractInsnNode,Integer> index) {
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
            dflt = keptLabels.removeLast(); keptKeys.removeLast();
        }
        if (keptKeys.size() == keys.size()) return null;
        int[] sortedKeys = keptKeys.stream().mapToInt(Integer::intValue).toArray();
        Integer[] order = new Integer[sortedKeys.length];
        for (int k = 0; k < order.length; k++) order[k] = k;
        Arrays.sort(order, Comparator.comparingInt(k -> sortedKeys[k]));
        int[] finalKeys = new int[order.length]; LabelNode[] finalLabels = new LabelNode[order.length];
        for (int k = 0; k < order.length; k++) { finalKeys[k] = sortedKeys[order[k]]; finalLabels[k] = keptLabels.get(order[k]); }
        return new LookupSwitchInsnNode(dflt, finalKeys, finalLabels);
    }
}
