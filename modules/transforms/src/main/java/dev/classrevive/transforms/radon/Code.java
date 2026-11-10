package dev.classrevive.transforms.radon;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;
import java.util.*;

/**
 * Phase 2DK: instruction navigation that tolerates Radon's block splitting. Radon moves the first half of a method behind
 * its end and joins the halves with {@code goto}s, after the constants and calls it injected were placed; a constant may
 * therefore reach its consumer through a {@code goto}. {@link #before} and {@link #after} follow such a jump only when
 * the label is reached from that one {@code goto} and from nowhere else (no fall-through, no other jump, no handler), so
 * the value on the stack is exactly the one pushed before the jump.
 */
final class Code implements Opcodes {
    private final Map<LabelNode,List<AbstractInsnNode>> jumps = new HashMap<>();
    private final Set<LabelNode> handlers = new HashSet<>();

    Code(MethodNode method) {
        for (var insn : method.instructions) {
            if (insn instanceof JumpInsnNode jump) jumps.computeIfAbsent(jump.label, k -> new ArrayList<>()).add(jump);
            else if (insn instanceof TableSwitchInsnNode table) {
                jumps.computeIfAbsent(table.dflt, k -> new ArrayList<>()).add(table);
                for (var label : table.labels) jumps.computeIfAbsent(label, k -> new ArrayList<>()).add(table);
            } else if (insn instanceof LookupSwitchInsnNode lookup) {
                jumps.computeIfAbsent(lookup.dflt, k -> new ArrayList<>()).add(lookup);
                for (var label : lookup.labels) jumps.computeIfAbsent(label, k -> new ArrayList<>()).add(lookup);
            }
        }
        if (method.tryCatchBlocks != null) for (var block : method.tryCatchBlocks) handlers.add(block.handler);
    }

    /** Jumps and switches that target the label (handlers not included). */
    List<AbstractInsnNode> referrers(LabelNode label) { return jumps.getOrDefault(label, List.of()); }
    boolean handler(LabelNode label) { return handlers.contains(label); }
    boolean referenced(LabelNode label) { return handler(label) || !referrers(label).isEmpty(); }

    /** The real instruction executed just before {@code insn} on every path, or null when paths merge there. */
    AbstractInsnNode before(AbstractInsnNode insn) {
        var node = insn.getPrevious();
        for (int guard = 0; node != null && guard < 100_000; guard++) {
            if (node instanceof LabelNode label) {
                if (!referenced(label)) { node = node.getPrevious(); continue; }
                var from = referrers(label);
                if (from.size() == 1 && !handler(label) && from.getFirst().getOpcode() == GOTO && !fallsInto(label)) {
                    node = from.getFirst().getPrevious(); continue;
                }
                return null;
            }
            if (node.getOpcode() < 0) { node = node.getPrevious(); continue; }
            return node;
        }
        return null;
    }

    /** The real instruction executed just after {@code insn}, following a {@code goto} to a label nothing else reaches. */
    AbstractInsnNode after(AbstractInsnNode insn) {
        var node = insn.getNext();
        for (int guard = 0; node != null && guard < 100_000; guard++) {
            if (node instanceof LabelNode label) {
                if (referenced(label)) return null;
                node = node.getNext(); continue;
            }
            if (node.getOpcode() < 0) { node = node.getNext(); continue; }
            if (node instanceof JumpInsnNode jump && jump.getOpcode() == GOTO) {
                var target = jump.label;
                if (referrers(target).size() == 1 && !handler(target) && !fallsInto(target)) { node = target.getNext(); continue; }
                return null;
            }
            return node;
        }
        return null;
    }

    /** The next real instruction if only unreferenced labels and debug nodes lie between; null otherwise. */
    AbstractInsnNode adjacentNext(AbstractInsnNode insn) {
        for (var node = insn.getNext(); node != null; node = node.getNext()) {
            if (node instanceof LabelNode label && referenced(label)) return null;
            if (node.getOpcode() >= 0) return node;
        }
        return null;
    }

    /** Whether control reaches the label by falling through from the code before it. */
    boolean fallsInto(LabelNode label) {
        for (var node = label.getPrevious(); node != null; node = node.getPrevious()) {
            if (node instanceof LabelNode other) { if (referenced(other)) return true; continue; }
            if (node.getOpcode() < 0) continue;
            return !unconditional(node.getOpcode());
        }
        return true;
    }

    static boolean unconditional(int op) {
        return op == GOTO || op == ATHROW || op == TABLESWITCH || op == LOOKUPSWITCH || op == RET || op >= IRETURN && op <= RETURN;
    }

    /** First real instruction at or after the node. */
    static AbstractInsnNode real(AbstractInsnNode node) {
        while (node != null && node.getOpcode() < 0) node = node.getNext();
        return node;
    }

    /** Number of local slots the parameters (and {@code this}) occupy. */
    static int parameterSlots(MethodNode method) {
        int slots = (method.access & ACC_STATIC) != 0 ? 0 : 1;
        for (var type : org.objectweb.asm.Type.getArgumentTypes(method.desc)) slots += type.getSize();
        return slots;
    }
}
