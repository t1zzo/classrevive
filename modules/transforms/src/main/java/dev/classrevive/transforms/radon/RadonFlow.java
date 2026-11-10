package dev.classrevive.transforms.radon;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;
import java.util.*;

/**
 * Phase 2DK: Radon 2's flow obfuscation shapes, undone one exact shape at a time. Every predicate is a
 * {@code public static final boolean} or {@code int} field without a constant value that nothing in the jar writes, so it
 * holds {@code false}/{@code 0}; each method copies it into a local at entry. The shapes:
 * <ul>
 *   <li>goto replacement: {@code iload p; ifeq L; aconst_null; athrow} is a {@code goto L};</li>
 *   <li>bogus jumps: {@code iload p; ifne X} never jumps; {@code X} is an exit block at the method start
 *   ({@code goto E; X: [constant] return; E:}) that only those jumps reach;</li>
 *   <li>bogus switch: {@code iload p; ifeq D; iload p; tableswitch 0..n-1 -> T* default D; D:}, where every case label
 *   {@code T} starts a landing {@code T: constant; istore p; goto T'} into another case: a closed loop no other code
 *   reaches, so its stores never reach a read of {@code p} outside it;</li>
 *   <li>mutilated null checks: {@code ifnonnull L} / {@code ifnull L} written as a call on {@code Object} inside a
 *   {@code try} that catches {@code NullPointerException};</li>
 *   <li>fake catch blocks: a {@code try} around one instruction, caught by an empty class of the jar extending an
 *   exception, whose handler calls a method that class does not declare.</li>
 * </ul>
 * A local that still has reads after the shapes are gone gets {@code 0} instead of the field (the value it always held);
 * folding what remains is the generic control-flow pass's work (Phase 2DL). Block splitting (a method's halves swapped and
 * joined with {@code goto}s) is left to decompilers and to that pass.
 */
final class RadonFlow implements Opcodes {
    int predicateJumps, gotosRestored, exitBlocks, bogusSwitches, nullChecks, fakeCatches, predicateLocals, predicatesZeroed;

    /** {@code predicates}: {@code owner.name} of the proven-zero fields; {@code fakeHandlers}: the empty handler classes. */
    boolean apply(MethodNode method, Set<String> predicates, Set<String> fakeHandlers) {
        boolean changed = false;
        changed |= fakeCatches(method, fakeHandlers);
        changed |= nullChecks(method);
        changed |= predicates(method, predicates);
        return changed;
    }

    // ------------------------------------------------------------------------------------------------ predicates

    private boolean predicates(MethodNode method, Set<String> predicates) {
        var code = new Code(method);
        int parameters = Code.parameterSlots(method);
        // Locals stored from a predicate field.
        var sources = new HashMap<Integer,List<VarInsnNode>>();
        var stores = new HashMap<Integer,List<VarInsnNode>>();
        var iinc = new HashSet<Integer>();
        for (var insn : method.instructions) {
            if (insn instanceof IincInsnNode inc) iinc.add(inc.var);
            if (!(insn instanceof VarInsnNode store) || store.getOpcode() != ISTORE) continue;
            stores.computeIfAbsent(store.var, k -> new ArrayList<>()).add(store);
            if (code.before(store) instanceof FieldInsnNode field && field.getOpcode() == GETSTATIC && predicates.contains(field.owner + "." + field.name))
                sources.computeIfAbsent(store.var, k -> new ArrayList<>()).add(store);
        }
        boolean changed = false;
        for (var entry : sources.entrySet()) {
            int slot = entry.getKey();
            if (slot < parameters || iinc.contains(slot)) continue;
            var others = new ArrayList<>(stores.get(slot)); others.removeAll(entry.getValue());
            var landings = landings(method, code, slot);
            if (landings == null) continue;
            if (!landings.stores.containsAll(others)) continue;
            changed |= rewrite(method, slot, landings);
            code = new Code(method);
            boolean read = false;
            for (var insn : method.instructions) if (insn instanceof VarInsnNode load && load.getOpcode() == ILOAD && load.var == slot) read = true;
            var remaining = new ArrayList<VarInsnNode>();
            for (var insn : method.instructions) if (insn instanceof VarInsnNode store && store.getOpcode() == ISTORE && store.var == slot) remaining.add(store);
            for (var store : remaining) {
                var field = code.before(store);
                if (!(field instanceof FieldInsnNode get) || get.getOpcode() != GETSTATIC) continue;
                if (read) { method.instructions.set(get, new InsnNode(ICONST_0)); predicatesZeroed++; }
                else { method.instructions.remove(get); method.instructions.remove(store); }
                changed = true;
            }
            if (!read) predicateLocals++;
            code = new Code(method);
        }
        return changed;
    }

    /** The bogus switches on a local and their landings; null when a switch on it does not have Radon's exact shape. */
    private record Landings(List<TableSwitchInsnNode> switches, Set<LabelNode> labels, Set<VarInsnNode> stores, List<AbstractInsnNode> code) {}

    private static Landings landings(MethodNode method, Code code, int slot) {
        var switches = new ArrayList<TableSwitchInsnNode>(); var labels = new HashSet<LabelNode>();
        var stores = new HashSet<VarInsnNode>(); var body = new ArrayList<AbstractInsnNode>();
        for (var insn : method.instructions) {
            if (!(insn instanceof TableSwitchInsnNode table)) continue;
            var load = previousReal(table);
            if (!(load instanceof VarInsnNode var) || var.getOpcode() != ILOAD || var.var != slot) continue;
            if (table.min != 0 || table.max != table.labels.size() - 1) return null;
            switches.add(table); labels.addAll(table.labels);
        }
        for (var label : labels) {
            for (var from : code.referrers(label)) if (!switches.contains(from) && !(from instanceof JumpInsnNode)) return null;
            if (code.handler(label)) return null;
            var push = Code.real(label);
            var store = push == null ? null : Code.real(push.getNext());
            var jump = store == null ? null : Code.real(store.getNext());
            if (push == null || ConstantFolder.constant(push) == null || !(store instanceof VarInsnNode s) || s.getOpcode() != ISTORE || s.var != slot
                || !(jump instanceof JumpInsnNode j) || j.getOpcode() != GOTO || !labels.contains(j.label)) return null;
            stores.add(s); body.add(push); body.add(store); body.add(jump);
        }
        // Every jump into a landing comes from a landing.
        for (var label : labels) for (var from : code.referrers(label)) if (from instanceof JumpInsnNode && !body.contains(from)) return null;
        return new Landings(switches, labels, stores, body);
    }

    private boolean rewrite(MethodNode method, int slot, Landings landings) {
        boolean changed = false;
        var insns = method.instructions;
        // Bogus switch blocks: iload p; ifeq D; [B:] iload p; tableswitch ... D. All of them or none: the landings are shared.
        var blocks = new ArrayList<AbstractInsnNode>();
        for (var table : landings.switches) {
            var load = previousReal(table);
            var guard = previousReal(load);
            var guardLoad = guard == null ? null : previousReal(guard);
            if (!(guard instanceof JumpInsnNode ifeq) || ifeq.getOpcode() != IFEQ || ifeq.label != table.dflt || !endsAt(table, table.dflt)
                || !(guardLoad instanceof VarInsnNode gl) || gl.getOpcode() != ILOAD || gl.var != slot) { blocks = null; break; }
            blocks.addAll(List.of(guardLoad, guard, load, table));
        }
        if (blocks != null && !blocks.isEmpty()) {
            for (var insn : blocks) insns.remove(insn);
            for (var insn : landings.code) insns.remove(insn);
            bogusSwitches += landings.switches.size(); changed = true;
        }
        var code = new Code(method);
        var exits = new HashSet<LabelNode>();
        for (var insn : insns.toArray()) {
            if (!(insn instanceof VarInsnNode load) || load.getOpcode() != ILOAD || load.var != slot || insns.indexOf(insn) < 0) continue;
            var next = code.adjacentNext(load);
            if (next instanceof JumpInsnNode jump && jump.getOpcode() == IFEQ) {
                var nul = code.adjacentNext(jump); var thr = nul == null ? null : code.adjacentNext(nul);
                if (nul != null && nul.getOpcode() == ACONST_NULL && thr != null && thr.getOpcode() == ATHROW) {
                    insns.remove(load); insns.remove(nul); insns.remove(thr);
                    insns.set(jump, new JumpInsnNode(GOTO, jump.label));
                    gotosRestored++; changed = true;
                }
            } else if (next instanceof JumpInsnNode jump && jump.getOpcode() == IFNE) {
                insns.remove(load); insns.remove(jump); exits.add(jump.label);
                predicateJumps++; changed = true;
            }
        }
        code = new Code(method);
        for (var exit : exits) {
            if (code.referenced(exit)) continue;
            // goto E; X: [constant] return; E:
            var skip = previousReal(exit);
            if (!(skip instanceof JumpInsnNode go) || go.getOpcode() != GOTO) continue;
            var first = Code.real(exit);
            var ret = first != null && ConstantFolder.constant(first) != null || first != null && first.getOpcode() == ACONST_NULL ? Code.real(first.getNext()) : first;
            if (ret == null || ret.getOpcode() < IRETURN || ret.getOpcode() > RETURN) continue;
            var end = ret.getNext();
            while (end != null && !(end instanceof LabelNode)) { if (end.getOpcode() >= 0) { end = null; break; } end = end.getNext(); }
            if (end != go.label) continue;
            insns.remove(go);
            if (first != ret) insns.remove(first);
            insns.remove(ret);
            exitBlocks++; changed = true;
        }
        return changed;
    }

    private static AbstractInsnNode previousReal(AbstractInsnNode insn) {
        for (var node = insn.getPrevious(); node != null; node = node.getPrevious()) if (node.getOpcode() >= 0) return node;
        return null;
    }

    // ------------------------------------------------------------------------------------------------ null checks

    private boolean nullChecks(MethodNode method) {
        if (method.tryCatchBlocks == null || method.tryCatchBlocks.isEmpty()) return false;
        boolean changed = false;
        for (var block : new ArrayList<>(method.tryCatchBlocks)) {
            if (!"java/lang/NullPointerException".equals(block.type)) continue;
            var code = new Code(method);
            if (method.tryCatchBlocks.stream().filter(other -> other.handler == block.handler).count() != 1) continue;
            var insns = method.instructions;
            var call = code.adjacentNext(block.start);
            var removed = new ArrayList<AbstractInsnNode>();
            if (call != null && call.getOpcode() == ACONST_NULL) { removed.add(call); call = code.adjacentNext(call); }
            if (!(call instanceof MethodInsnNode m) || m.getOpcode() != INVOKEVIRTUAL || !m.owner.equals("java/lang/Object") || !objectProbe(m, !removed.isEmpty())) continue;
            removed.add(m);
            var pop = code.adjacentNext(m);
            if (pop == null || pop.getOpcode() != POP || !endsAt(pop, block.end)) continue;
            removed.add(pop);
            var jump = Code.real(block.end);
            if (!(jump instanceof JumpInsnNode go) || go.getOpcode() != GOTO) continue;
            var handlerPop = Code.real(block.handler);
            if (handlerPop == null || handlerPop.getOpcode() != POP || !endsAt(go, block.handler)) continue;
            int opcode; LabelNode target;
            var second = handlerPop.getNext() == null ? null : Code.real(handlerPop.getNext());
            if (second instanceof JumpInsnNode back && back.getOpcode() == GOTO && onlyDebugBetween(handlerPop, back) && endsAt(back, go.label)) {
                // ifnull L: goto CE; H: pop; goto L; CE:
                opcode = IFNULL; target = back.label;
                removed.add(go); removed.add(handlerPop); removed.add(back);
            } else {
                // ifnonnull L: goto L; H: pop; CE: (the code after the original check)
                opcode = IFNONNULL; target = go.label;
                removed.add(go); removed.add(handlerPop);
            }
            insns.insertBefore(block.end, new JumpInsnNode(opcode, target));
            for (var insn : removed) insns.remove(insn);
            method.tryCatchBlocks.remove(block);
            nullChecks++; changed = true;
        }
        return changed;
    }

    private static boolean objectProbe(MethodInsnNode call, boolean withNull) {
        if (withNull) return call.name.equals("equals") && call.desc.equals("(Ljava/lang/Object;)Z");
        return call.name.equals("getClass") && call.desc.equals("()Ljava/lang/Class;") || call.name.equals("hashCode") && call.desc.equals("()I")
            || call.name.equals("toString") && call.desc.equals("()Ljava/lang/String;");
    }

    /** Whether only labels and debug nodes lie between {@code insn} and {@code label}, which follows it. */
    private static boolean endsAt(AbstractInsnNode insn, LabelNode label) {
        for (var node = insn.getNext(); node != null; node = node.getNext()) {
            if (node == label) return true;
            if (node.getOpcode() >= 0) return false;
        }
        return false;
    }
    /** Whether no label and no real instruction lie between the two nodes. */
    private static boolean onlyDebugBetween(AbstractInsnNode from, AbstractInsnNode to) {
        for (var node = from.getNext(); node != null && node != to; node = node.getNext()) if (node instanceof LabelNode || node.getOpcode() >= 0) return false;
        return true;
    }

    // ------------------------------------------------------------------------------------------------ fake catches

    private boolean fakeCatches(MethodNode method, Set<String> fakeHandlers) {
        if (method.tryCatchBlocks == null || method.tryCatchBlocks.isEmpty() || fakeHandlers.isEmpty()) return false;
        boolean changed = false;
        for (var block : new ArrayList<>(method.tryCatchBlocks)) {
            if (block.type == null || !fakeHandlers.contains(block.type)) continue;
            if (method.tryCatchBlocks.stream().filter(other -> other.handler == block.handler).count() != 1) continue;
            var dup = Code.real(block.handler);
            var call = dup == null ? null : Code.real(dup.getNext());
            var thr = call == null ? null : Code.real(call.getNext());
            if (dup == null || dup.getOpcode() != DUP || !(call instanceof MethodInsnNode m) || m.getOpcode() != INVOKEVIRTUAL
                || !m.owner.equals(block.type) || !m.desc.equals("()V") || thr == null || thr.getOpcode() != ATHROW) continue;
            // try { insn } goto CE; H: dup; invokevirtual; athrow; CE:
            var go = Code.real(block.end);
            if (!(go instanceof JumpInsnNode jump) || jump.getOpcode() != GOTO || !endsAt(go, block.handler) || !endsAt(thr, jump.label)) continue;
            method.tryCatchBlocks.remove(block);
            method.instructions.remove(go); method.instructions.remove(dup); method.instructions.remove(call); method.instructions.remove(thr);
            fakeCatches++; changed = true;
        }
        return changed;
    }
}
