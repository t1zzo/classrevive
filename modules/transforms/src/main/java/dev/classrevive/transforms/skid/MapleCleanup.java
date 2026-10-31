package dev.classrevive.transforms.skid;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.*;
import java.util.*;

/**
 * Phase 2CS: undoes the shapes Skidfuscator's MapleIR back end leaves in every method it touched (it lowers code to SSA
 * and back), so the bytecode reads like javac's again and the decompilers rebuild ordinary expressions:
 * <ul>
 *   <li><b>stack spills</b>: a local stored once and loaded once, later in the same straight-line code, with everything
 *   in between balanced on the stack, becomes the stack value it was ({@code istore 6; iload 6} disappears). Pure pushes
 *   in between (loads of other locals, constants) are hoisted in front of the stored expression first, which restores
 *   javac's evaluation order ({@code a.get(); istore 7; iload 2; iload 7; iadd} becomes {@code iload 2; a.get(); iadd});</li>
 *   <li><b>constructions</b>: {@code new T; astore y; aload y; <args>; invokespecial T.<init>} becomes
 *   {@code new T; dup; <args>; invokespecial T.<init>; astore y};</li>
 *   <li><b>dead stores</b>: a store to a local that is never loaded (and has no debug name) becomes {@code pop};</li>
 *   <li><b>comparisons with zero</b>: {@code iconst_0; if_icmpXX} becomes {@code ifXX} (javac's form).</li>
 * </ul>
 * Every rewrite keeps the order of side effects; only loads of locals and constants move.
 */
final class MapleCleanup implements Opcodes {
    int spills, constructions, deadStores, comparisons;

    boolean apply(String owner, MethodNode method) {
        boolean any = false;
        for (int round = 0; round < 32; round++) {
            boolean changed = comparisons(method) | constructions(method) | spills(owner, method) | deadStores(method);
            if (!changed) break;
            any = true;
        }
        return any;
    }

    // ---------------------------------------------------------------- comparisons with zero

    private boolean comparisons(MethodNode method) {
        boolean changed = false;
        for (var insn : method.instructions.toArray()) {
            if (!(insn instanceof JumpInsnNode jump) || jump.getOpcode() < IF_ICMPEQ || jump.getOpcode() > IF_ICMPLE) continue;
            var zero = previous(insn);
            if (zero == null || zero.getOpcode() != ICONST_0 || labelBetween(zero, insn)) continue;
            method.instructions.remove(zero);
            method.instructions.set(jump, new JumpInsnNode(jump.getOpcode() - IF_ICMPEQ + IFEQ, jump.label));
            comparisons++; changed = true;
        }
        return changed;
    }

    // ---------------------------------------------------------------- constructions

    private boolean constructions(MethodNode method) {
        boolean changed = false;
        for (var insn : method.instructions.toArray()) {
            if (!(insn instanceof TypeInsnNode alloc) || alloc.getOpcode() != NEW) continue;
            if (!(next(alloc) instanceof VarInsnNode store) || store.getOpcode() != ASTORE || labelBetween(alloc, store)) continue;
            if (!(next(store) instanceof VarInsnNode load) || load.getOpcode() != ALOAD || load.var != store.var || labelBetween(store, load)) continue;
            // The matching constructor call: the first invokespecial T.<init> after the load whose receiver is that load.
            MethodInsnNode init = null;
            int depth = 0; // values pushed above the receiver
            for (var at = next(load); at != null; at = next(at)) {
                if (labelBetween(previous(at), at)) break;
                if (at instanceof VarInsnNode v && v.var == store.var) break;
                if (at instanceof MethodInsnNode call && call.getOpcode() == INVOKESPECIAL && call.name.equals("<init>")
                    && call.owner.equals(alloc.desc) && argumentSlots(call.desc) == depth) { init = call; break; }
                int delta = stackDelta(at);
                if (delta == Integer.MIN_VALUE) break;
                depth += delta;
                if (depth < 0) break;
            }
            if (init == null) continue;
            method.instructions.set(store, new InsnNode(DUP));
            method.instructions.remove(load);
            method.instructions.insert(init, new VarInsnNode(ASTORE, store.var));
            constructions++; changed = true;
        }
        return changed;
    }

    // ---------------------------------------------------------------- stack spills

    private boolean spills(String owner, MethodNode method) {
        Frame<BasicValue>[] frames;
        try { frames = new Analyzer<>(new BasicInterpreter()).analyze(owner, method); }
        catch (AnalyzerException error) { return false; }
        var insns = method.instructions.toArray();
        var index = new IdentityHashMap<AbstractInsnNode,Integer>();
        for (int i = 0; i < insns.length; i++) index.put(insns[i], i);
        var uses = new HashMap<Integer,List<AbstractInsnNode>>();
        for (var insn : insns) {
            if (insn instanceof VarInsnNode v && v.getOpcode() != RET) uses.computeIfAbsent(v.var, k -> new ArrayList<>()).add(insn);
            if (insn instanceof IincInsnNode inc) uses.computeIfAbsent(inc.var, k -> new ArrayList<>()).add(insn);
        }
        var named = debugNamed(method);
        int parameters = parameterSlots(method);
        for (var entry : uses.entrySet()) {
            int var = entry.getKey();
            var list = entry.getValue();
            if (var < parameters || named.contains(var) || list.size() != 2) continue;
            if (!(list.get(0) instanceof VarInsnNode store) || store.getOpcode() < ISTORE || store.getOpcode() > ASTORE) continue;
            if (!(list.get(1) instanceof VarInsnNode load) || load.getOpcode() != store.getOpcode() - ISTORE + ILOAD) continue;
            int s = index.get(store), l = index.get(load);
            if (s >= l || frames[s] == null || frames[l] == null) continue;
            // Straight-line code between them (no label: nothing jumps in, no handler starts).
            boolean straight = true;
            for (int i = s + 1; i < l; i++) {
                if (insns[i] instanceof LabelNode || insns[i] instanceof JumpInsnNode || insns[i] instanceof TableSwitchInsnNode
                    || insns[i] instanceof LookupSwitchInsnNode || insns[i].getOpcode() == ATHROW || insns[i].getOpcode() >= IRETURN && insns[i].getOpcode() <= RETURN) { straight = false; break; }
            }
            if (!straight || coveredDifferently(method, insns, s, l)) continue;
            // A lambda or method reference must keep its variable when it is the receiver of a call: Java has no
            // (x -> ...).apply(...) expression.
            if (previous(store) instanceof InvokeDynamicInsnNode && lambdaReceiver(insns, frames, l)) continue;
            int base = frames[s].getStackSize() - 1; // stack entries below the stored value
            // Pure pushes right before the load, stacked above `base`: hoist them in front of the stored expression.
            var hoist = new ArrayList<AbstractInsnNode>();
            int k = l - 1;
            while (k > s && pushOnly(insns[k]) && !writtenBetween(insns, s, l, insns[k])) { hoist.addFirst(insns[k]); k--; }
            // Everything between the store and the hoisted pushes must be balanced and never consume an entry at or below
            // `base` (the stored value will sit there).
            boolean balanced = frames[k + 1] != null && frames[k + 1].getStackSize() == base;
            for (int i = s + 1; balanced && i <= k; i++) {
                if (insns[i].getOpcode() < 0) continue;
                int pops = pops(insns[i]);
                if (frames[i] == null || pops < 0 || frames[i].getStackSize() - pops < base) balanced = false;
            }
            if (!balanced) continue;
            if (!hoist.isEmpty()) {
                // Where the stored expression starts: the first instruction (going back, straight-line) with `base` entries.
                int start = s;
                while (start > 0 && frames[start] != null && frames[start].getStackSize() > base && !(insns[start - 1] instanceof LabelNode)
                    && !(insns[start - 1] instanceof JumpInsnNode)) start--;
                if (frames[start] == null || frames[start].getStackSize() != base) continue;
                boolean safe = true;
                for (var push : hoist) if (writtenBetween(insns, start, s + 1, push)) safe = false;
                if (!safe) continue;
                var anchor = insns[start];
                for (var push : hoist) { method.instructions.remove(push); method.instructions.insertBefore(anchor, push); }
            }
            method.instructions.remove(store);
            method.instructions.remove(load);
            spills++;
            return true; // frames are stale: one rewrite per analysis
        }
        return false;
    }

    /** True when the value the load at {@code l} pushes is consumed as the receiver of a call. */
    private static boolean lambdaReceiver(AbstractInsnNode[] insns, Frame<BasicValue>[] frames, int l) {
        if (frames[l] == null) return true;
        int position = frames[l].getStackSize(); // index of the loaded value
        for (int j = l + 1; j < insns.length; j++) {
            if (insns[j].getOpcode() < 0) continue;
            if (frames[j] == null) return true;
            int pops = pops(insns[j]);
            if (pops < 0) return true;
            if (frames[j].getStackSize() - pops <= position)
                return insns[j] instanceof MethodInsnNode call && call.getOpcode() != INVOKESTATIC && frames[j].getStackSize() - pops == position;
        }
        return true;
    }

    /** True when some try range covers part, but not all, of the instructions from s to l (moving values would cross it). */
    private static boolean coveredDifferently(MethodNode method, AbstractInsnNode[] insns, int s, int l) {
        for (var block : method.tryCatchBlocks) {
            int start = method.instructions.indexOf(block.start), end = method.instructions.indexOf(block.end);
            boolean a = s >= start && s < end, b = l >= start && l < end;
            if (a != b) return true;
        }
        return false;
    }

    private static boolean pushOnly(AbstractInsnNode insn) {
        int op = insn.getOpcode();
        return op >= ACONST_NULL && op <= ALOAD;
    }

    /** True when the local a push loads is stored somewhere in [from, to). */
    private static boolean writtenBetween(AbstractInsnNode[] insns, int from, int to, AbstractInsnNode push) {
        if (!(push instanceof VarInsnNode load)) return false;
        for (int i = from; i < to; i++) {
            if (insns[i] instanceof VarInsnNode v && v.var == load.var && v.getOpcode() >= ISTORE && v.getOpcode() <= ASTORE) return true;
            if (insns[i] instanceof IincInsnNode inc && inc.var == load.var) return true;
        }
        return false;
    }

    // ---------------------------------------------------------------- dead stores

    private boolean deadStores(MethodNode method) {
        var loaded = new HashSet<Integer>();
        for (var insn : method.instructions) {
            if (insn instanceof VarInsnNode v && v.getOpcode() >= ILOAD && v.getOpcode() <= ALOAD) loaded.add(v.var);
            if (insn instanceof IincInsnNode inc) loaded.add(inc.var);
            if (insn instanceof VarInsnNode v && v.getOpcode() == RET) loaded.add(v.var);
        }
        var named = debugNamed(method);
        int parameters = parameterSlots(method);
        boolean changed = false;
        for (var insn : method.instructions.toArray()) {
            if (!(insn instanceof VarInsnNode store) || store.getOpcode() < ISTORE || store.getOpcode() > ASTORE) continue;
            if (loaded.contains(store.var) || named.contains(store.var) || store.var < parameters) continue;
            // A handler's stored exception stays (javac always stores it, even unused).
            var before = previous(insn);
            if (store.getOpcode() == ASTORE && (before == null || isHandlerStart(method, insn))) continue;
            boolean wide = store.getOpcode() == LSTORE || store.getOpcode() == DSTORE;
            method.instructions.set(store, new InsnNode(wide ? POP2 : POP));
            deadStores++; changed = true;
        }
        return changed;
    }

    private static boolean isHandlerStart(MethodNode method, AbstractInsnNode insn) {
        for (var block : method.tryCatchBlocks) {
            AbstractInsnNode first = block.handler;
            while (first != null && first.getOpcode() < 0) first = first.getNext();
            if (first == insn) return true;
        }
        return false;
    }

    // ---------------------------------------------------------------- helpers

    private static Set<Integer> debugNamed(MethodNode method) {
        var named = new HashSet<Integer>();
        if (method.localVariables != null) for (var local : method.localVariables) named.add(local.index);
        return named;
    }

    private static int parameterSlots(MethodNode method) {
        int slots = (method.access & ACC_STATIC) == 0 ? 1 : 0;
        for (var type : Type.getArgumentTypes(method.desc)) slots += type.getSize();
        return slots;
    }

    private static int argumentSlots(String desc) { return Type.getArgumentTypes(desc).length; }

    /** Net stack entries an instruction pushes (pops negative); MIN_VALUE when it branches or is not modelled. */
    private static int stackDelta(AbstractInsnNode insn) {
        int op = insn.getOpcode();
        if (op >= ACONST_NULL && op <= ALOAD) return 1;
        if (op >= ISTORE && op <= ASTORE) return -1;
        if (insn instanceof MethodInsnNode call) {
            int pops = Type.getArgumentTypes(call.desc).length + (op == INVOKESTATIC ? 0 : 1);
            return (Type.getReturnType(call.desc).getSort() == Type.VOID ? 0 : 1) - pops;
        }
        if (insn instanceof InvokeDynamicInsnNode indy)
            return (Type.getReturnType(indy.desc).getSort() == Type.VOID ? 0 : 1) - Type.getArgumentTypes(indy.desc).length;
        if (insn instanceof FieldInsnNode) return switch (op) { case GETSTATIC -> 1; case PUTSTATIC -> -1; case GETFIELD -> 0; default -> -2; };
        if (insn instanceof TypeInsnNode) return op == NEW ? 1 : 0; // ANEWARRAY, CHECKCAST, INSTANCEOF: 1 in, 1 out
        if (op == DUP) return 1;
        if (op == POP) return -1;
        if (op >= IALOAD && op <= SALOAD) return -1;
        if (op >= IASTORE && op <= SASTORE) return -3;
        if (op >= IADD && op <= LXOR) return (op >= INEG && op <= DNEG) ? 0 : -1;
        if (op >= I2L && op <= I2S || op == ARRAYLENGTH || op == NEWARRAY) return 0;
        return Integer.MIN_VALUE;
    }

    /** Stack entries an instruction consumes (reads counts as consuming), or -1 when not modelled. */
    private static int pops(AbstractInsnNode insn) {
        int op = insn.getOpcode();
        if (op >= ACONST_NULL && op <= ALOAD || op == NEW || op == GETSTATIC) return 0;
        if (op >= ISTORE && op <= ASTORE || op == POP || op == DUP || op == ARRAYLENGTH || op == NEWARRAY || op == ANEWARRAY
            || op == GETFIELD || op == PUTSTATIC || op == CHECKCAST || op == INSTANCEOF || op == MONITORENTER || op == MONITOREXIT) return 1;
        if (op >= IALOAD && op <= SALOAD || op == PUTFIELD) return 2;
        if (op >= IASTORE && op <= SASTORE) return 3;
        if (op >= INEG && op <= DNEG || op >= I2L && op <= I2S) return 1;
        if (op >= IADD && op <= LXOR || op >= LCMP && op <= DCMPG) return 2;
        if (insn instanceof MethodInsnNode call) return Type.getArgumentTypes(call.desc).length + (op == INVOKESTATIC ? 0 : 1);
        if (insn instanceof InvokeDynamicInsnNode indy) return Type.getArgumentTypes(indy.desc).length;
        return -1;
    }

    private static boolean labelBetween(AbstractInsnNode a, AbstractInsnNode b) {
        for (var node = a.getNext(); node != null && node != b; node = node.getNext()) if (node instanceof LabelNode) return true;
        return false;
    }

    static AbstractInsnNode next(AbstractInsnNode insn) { return SeedInterpreter.next(insn); }
    static AbstractInsnNode previous(AbstractInsnNode insn) { return SeedInterpreter.previous(insn); }
}
