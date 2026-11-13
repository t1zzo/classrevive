package dev.classrevive.transforms.skid;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.*;
import java.util.*;

/**
 * Phase 2DM: removes computations whose result nothing uses and that cannot throw or have an effect: a {@code pop} of a
 * value pushed by a constant, a local load or int/long arithmetic (division excepted) loses its producer, whose own
 * operands are popped instead, round after round; a store to a local nothing loads becomes a {@code pop}. What a
 * replaced decryptor or reflective call left (its key expressions) disappears this way. Only the instructions' own
 * effects are judged; the analysis is the JDK's source analysis of one method.
 */
final class PureDeadCode implements Opcodes {
    private static final int MAX_ROUNDS = 4096;

    static int clean(String owner, MethodNode method) { return clean(owner, method, Set.of()); }

    /** {@code pureStatics} ({@code owner.name:desc}): static fields whose read is pure too (the obfuscator's class keys). */
    static int clean(String owner, MethodNode method, Set<String> pureStatics) {
        int removed = 0;
        for (int round = 0; round < MAX_ROUNDS; round++) {
            int step = rethrowers(method) + unreachable(owner, method) + jumpsToNext(method) + boxThenUnbox(method) + deadStores(method) + dupPops(method) + dupOfEarlierPush(owner, method)
                + droppedValues(owner, method, pureStatics);
            if (step == 0) break;
            removed += step;
        }
        return removed;
    }

    /**
     * Try blocks whose handler is only {@code athrow}: the exception goes on as if they were not there, provided no other
     * kind of handler covers the rethrow or comes later in the table over the same code. Zelix adds them around code it
     * protects; decompilers cannot structure the overlapping ranges.
     */
    private static int rethrowers(MethodNode method) {
        var insns = method.instructions;
        var rethrow = new ArrayList<TryCatchBlockNode>();
        for (var block : method.tryCatchBlocks) {
            var first = real(block.handler.getNext());
            if (first != null && first.getOpcode() == ATHROW) rethrow.add(block);
            // javac's shape of the same rethrow: catch (E e) { throw e; }
            else if (first instanceof VarInsnNode store && store.getOpcode() == ASTORE
                && real(store.getNext()) instanceof VarInsnNode load && load.getOpcode() == ALOAD && load.var == store.var
                && real(load.getNext()) != null && real(load.getNext()).getOpcode() == ATHROW) rethrow.add(block);
        }
        if (rethrow.isEmpty()) return 0;
        int count = 0;
        var blocks = method.tryCatchBlocks;
        for (var block : rethrow) {
            boolean safe = true;
            for (int k = 0; k < blocks.size() && safe; k++) {
                var other = blocks.get(k);
                if (rethrow.contains(other)) continue;
                int start = insns.indexOf(other.start), end = insns.indexOf(other.end), handler = insns.indexOf(block.handler);
                if (handler >= start && handler < end) safe = false;
                if (k > blocks.indexOf(block) && start < insns.indexOf(block.end) && insns.indexOf(block.start) < end) safe = false;
            }
            if (safe) { blocks.remove(block); count++; }
        }
        return count;
    }

    /** The first instruction from {@code insn} on that is not a label, line number or frame. */
    private static AbstractInsnNode real(AbstractInsnNode insn) {
        while (insn != null && insn.getOpcode() < 0) insn = insn.getNext();
        return insn;
    }

    /** A {@code goto} to the instruction right after it. */
    private static int jumpsToNext(MethodNode method) {
        int count = 0;
        for (var insn : method.instructions.toArray()) {
            if (!(insn instanceof JumpInsnNode jump) || jump.getOpcode() != GOTO) continue;
            for (var next = jump.getNext(); next != null && next.getOpcode() < 0; next = next.getNext())
                if (next == jump.label) { method.instructions.remove(jump); count++; break; }
        }
        return count;
    }

    private static final Map<String,String> UNBOXING = Map.of("java/lang/Integer", "intValue:I", "java/lang/Long", "longValue:J",
        "java/lang/Boolean", "booleanValue:Z", "java/lang/Character", "charValue:C", "java/lang/Short", "shortValue:S",
        "java/lang/Byte", "byteValue:B", "java/lang/Float", "floatValue:F", "java/lang/Double", "doubleValue:D");

    /** {@code Integer.valueOf(x)}, an optional cast to {@code Integer}, then {@code intValue()}: the value itself (what {@code Method.invoke} boxing left). */
    private static int boxThenUnbox(MethodNode method) {
        int count = 0;
        for (var insn : method.instructions.toArray()) {
            if (!(insn instanceof MethodInsnNode box) || box.getOpcode() != INVOKESTATIC || !box.name.equals("valueOf") || !UNBOXING.containsKey(box.owner)) continue;
            String[] unbox = UNBOXING.get(box.owner).split(":");
            if (!box.desc.equals("(" + unbox[1] + ")L" + box.owner + ";")) continue;
            var next = following(box);
            TypeInsnNode cast = next instanceof TypeInsnNode type && type.getOpcode() == CHECKCAST && type.desc.equals(box.owner) ? type : null;
            if (cast != null) next = following(cast);
            if (!(next instanceof MethodInsnNode value) || value.getOpcode() != INVOKEVIRTUAL || !value.owner.equals(box.owner)
                || !value.name.equals(unbox[0]) || !value.desc.equals("()" + unbox[1])) continue;
            method.instructions.remove(box);
            if (cast != null) method.instructions.remove(cast);
            method.instructions.remove(value);
            count++;
        }
        return count;
    }

    /** The next node that is not a line number or a frame (a label counts: something may jump there). */
    private static AbstractInsnNode following(AbstractInsnNode insn) {
        var next = insn.getNext();
        while (next instanceof LineNumberNode || next instanceof FrameNode) next = next.getNext();
        return next;
    }

    /**
     * {@code dup; pop} and {@code dup2; pop2} next to each other copy a value only to drop the copy: both go. A
     * {@code dup} (or {@code dup2} of a long or double) right after a constant or a local's load becomes that push again,
     * the same value, so each copy is judged on its own.
     */
    private static int dupPops(MethodNode method) {
        int count = 0;
        for (var insn : method.instructions.toArray()) {
            if (insn.getOpcode() != DUP && insn.getOpcode() != DUP2) continue;
            var push = insn.getPrevious();
            while (push instanceof LineNumberNode || push instanceof FrameNode) push = push.getPrevious();
            if (push == null || push.getOpcode() < 0) continue;
            int width = width(push);
            if (width == 0 || (insn.getOpcode() == DUP) != (width == 1)) continue;
            method.instructions.set(insn, push.clone(Map.of()));
            count++;
        }
        for (var insn : method.instructions.toArray()) {
            if (insn.getOpcode() != DUP && insn.getOpcode() != DUP2 || insn.getNext() == null) continue;
            var next = insn.getNext();
            while (next instanceof LineNumberNode || next instanceof FrameNode) next = next.getNext();
            if (next == null || next.getOpcode() != (insn.getOpcode() == DUP ? POP : POP2)) continue;
            method.instructions.remove(insn);
            method.instructions.remove(next);
            count++;
        }
        return count;
    }

    /**
     * Instructions no path reaches (an unwrapping handler whose try range went), and try ranges left with nothing that can
     * throw. Class files before Java 6 keep such code when written, so it is removed here.
     */
    private static int unreachable(String owner, MethodNode method) {
        Frame<SourceValue>[] frames;
        try { frames = new Analyzer<>(new SourceInterpreter()).analyze(owner, method); }
        catch (AnalyzerException malformed) { return 0; }
        var insns = method.instructions.toArray();
        int count = 0;
        for (int i = 0; i < insns.length; i++)
            if (frames[i] == null && insns[i].getOpcode() >= 0) { method.instructions.remove(insns[i]); count++; }
        for (var it = method.tryCatchBlocks.iterator(); it.hasNext(); ) {
            var block = it.next();
            boolean throwing = false;
            for (AbstractInsnNode insn = block.start; insn != null && insn != block.end; insn = insn.getNext())
                if (MethodRewriter.canThrow(insn)) { throwing = true; break; }
            if (!throwing) { it.remove(); count++; }
        }
        return count;
    }

    /** {@code xstore n} where nothing loads, increments or returns through local n becomes {@code pop} or {@code pop2}. */
    private static int deadStores(MethodNode method) {
        var read = new HashSet<Integer>();
        for (var insn : method.instructions) {
            if (insn instanceof VarInsnNode var && (var.getOpcode() >= ILOAD && var.getOpcode() <= ALOAD || var.getOpcode() == RET)) read.add(var.var);
            if (insn instanceof IincInsnNode inc) read.add(inc.var);
        }
        if (method.localVariables != null) for (var local : method.localVariables) read.add(local.index);
        int count = 0;
        for (var insn : method.instructions.toArray()) {
            if (!(insn instanceof VarInsnNode var) || var.getOpcode() < ISTORE || var.getOpcode() > ASTORE || read.contains(var.var)) continue;
            method.instructions.set(insn, new InsnNode(var.getOpcode() == LSTORE || var.getOpcode() == DSTORE ? POP2 : POP));
            count++;
        }
        return count;
    }

    /** A pop of a pure single-use value removes the value's producer (its operands popped instead). */
    private static int droppedValues(String owner, MethodNode method, Set<String> pureStatics) {
        Frame<SourceValue>[] frames;
        try { frames = new Analyzer<>(new SourceInterpreter()).analyze(owner, method); }
        catch (AnalyzerException malformed) { return 0; }
        var insns = method.instructions.toArray();
        var index = new IdentityHashMap<AbstractInsnNode,Integer>();
        for (int i = 0; i < insns.length; i++) index.put(insns[i], i);
        // How many instructions consume each producer's value.
        var uses = new IdentityHashMap<AbstractInsnNode,Integer>();
        for (int i = 0; i < insns.length; i++) {
            var frame = frames[i];
            if (frame == null) continue;
            int consumed = consumed(insns[i], frame);
            for (int k = 0; k < consumed && k < frame.getStackSize(); k++)
                for (var producer : frame.getStack(frame.getStackSize() - 1 - k).insns) uses.merge(producer, 1, Integer::sum);
        }
        for (int i = 0; i < insns.length; i++) {
            var pop = insns[i];
            if ((pop.getOpcode() != POP && pop.getOpcode() != POP2) || frames[i] == null || frames[i].getStackSize() == 0) continue;
            var top = frames[i].getStack(frames[i].getStackSize() - 1);
            if (pop.getOpcode() == POP2 && top.getSize() != 2) continue;
            if (top.insns.size() != 1) continue;
            var producer = top.insns.iterator().next();
            if (uses.getOrDefault(producer, 0) != 1 || !pure(producer, pureStatics)) continue;
            var producerFrame = frames[index.get(producer)];
            if (producerFrame == null) continue;
            // Labels between them that something jumps to would make the pair more than straight-line code: keep it.
            var replacement = new InsnList();
            int operands = consumed(producer, producerFrame);
            for (int k = 0; k < operands; k++)
                replacement.add(new InsnNode(producerFrame.getStack(producerFrame.getStackSize() - 1 - k).getSize() == 2 ? POP2 : POP));
            method.instructions.insertBefore(producer, replacement);
            method.instructions.remove(producer);
            method.instructions.remove(pop);
            return 1; // the frames are stale now
        }
        return 0;
    }

    /**
     * In a constructor, statements before the {@code super(...)} or {@code this(...)} call that only compute and store a
     * local (Zelix derives the keys there) move right after it: Java allows no statement before that call. Each moved
     * statement is pure arithmetic ending in a store, starts and ends with an empty stack, and the call's arguments read
     * none of the locals it stores. Returns the number of statements moved.
     */
    static int afterSuper(ClassNode node, MethodNode method, Set<String> pureStatics) {
        Frame<SourceValue>[] frames;
        try { frames = new Analyzer<>(new SourceInterpreter()).analyze(node.name, method); }
        catch (AnalyzerException malformed) { return 0; }
        var insns = method.instructions.toArray();
        // The constructor call on this: its receiver is this, loaded by the first instruction of its statement.
        int call = -1;
        for (int i = 0; i < insns.length && call < 0; i++) {
            if (!(insns[i] instanceof MethodInsnNode init) || init.getOpcode() != INVOKESPECIAL || !init.name.equals("<init>")) continue;
            if (!init.owner.equals(node.superName) && !init.owner.equals(node.name)) return 0;
            var frame = frames[i];
            if (frame == null) return 0;
            var receiver = frame.getStack(frame.getStackSize() - org.objectweb.asm.Type.getArgumentTypes(init.desc).length - 1);
            if (receiver.insns.size() != 1 || !(receiver.insns.iterator().next() instanceof VarInsnNode load) || load.getOpcode() != ALOAD || load.var != 0) return 0;
            call = i;
        }
        if (call < 0) return 0;
        // Where the call's statement starts: the last empty stack before it.
        int start = -1;
        for (int i = call; i >= 0; i--) if (frames[i] != null && frames[i].getStackSize() == 0 && insns[i].getOpcode() >= 0) { start = i; break; }
        if (start <= 0) return 0;
        var moved = new ArrayList<AbstractInsnNode>();
        var stored = new HashSet<Integer>();
        for (int i = 0; i < start; i++) {
            var insn = insns[i];
            if (insn.getOpcode() < 0) {
                if (insn instanceof LabelNode label && targeted(method, label)) return 0;
                continue;
            }
            if (insn instanceof VarInsnNode store && store.getOpcode() >= ISTORE && store.getOpcode() <= ASTORE) {
                if (store.var == 0) return 0;
                stored.add(store.var);
            } else if (!pure(insn, pureStatics)) return 0;
            moved.add(insn);
        }
        if (moved.isEmpty() || frames[start].getStackSize() != 0) return 0;
        for (int i = start; i <= call; i++)
            if (insns[i] instanceof VarInsnNode load && load.getOpcode() >= ILOAD && load.getOpcode() <= ALOAD && stored.contains(load.var)
                || insns[i] instanceof IincInsnNode inc && stored.contains(inc.var)) return 0;
        var after = insns[call];
        for (var insn : moved) {
            method.instructions.remove(insn);
            method.instructions.insert(after, insn);
            after = insn;
        }
        return moved.size();
    }

    private static boolean targeted(MethodNode method, LabelNode label) {
        for (var insn : method.instructions) {
            if (insn instanceof JumpInsnNode jump && jump.label == label) return true;
            if (insn instanceof TableSwitchInsnNode table && (table.dflt == label || table.labels.contains(label))) return true;
            if (insn instanceof LookupSwitchInsnNode lookup && (lookup.dflt == label || lookup.labels.contains(label))) return true;
        }
        for (var block : method.tryCatchBlocks) if (block.start == label || block.end == label || block.handler == label) return true;
        return false;
    }

    /**
     * A {@code dup} of a value a constant or local load pushed earlier in the same straight-line code, the local unchanged
     * since: that push again. Zelix keeps a key on the stack while deriving others from it.
     */
    private static int dupOfEarlierPush(String owner, MethodNode method) {
        Frame<SourceValue>[] frames;
        try { frames = new Analyzer<>(new SourceInterpreter()).analyze(owner, method); }
        catch (AnalyzerException malformed) { return 0; }
        var insns = method.instructions.toArray();
        next:
        for (int i = 0; i < insns.length; i++) {
            int op = insns[i].getOpcode();
            if (op != DUP && op != DUP2 || frames[i] == null || frames[i].getStackSize() == 0) continue;
            var top = frames[i].getStack(frames[i].getStackSize() - 1);
            if (top.insns.size() != 1) continue;
            var push = top.insns.iterator().next();
            if (width(push) != (op == DUP ? 1 : 2)) continue;
            for (var at = push.getNext(); at != insns[i]; at = at.getNext()) {
                if (at == null || at instanceof JumpInsnNode || at instanceof TableSwitchInsnNode || at instanceof LookupSwitchInsnNode) continue next;
                if (at instanceof LabelNode label && targeted(method, label)) continue next;
                if (push instanceof VarInsnNode load && (at instanceof VarInsnNode store && store.var == load.var && store.getOpcode() >= ISTORE
                    || at instanceof IincInsnNode inc && inc.var == load.var)) continue next;
            }
            method.instructions.set(insns[i], push.clone(Map.of()));
            return 1; // the frames are stale now
        }
        return 0;
    }

    /** The size of the value a constant or local load pushes; 0 for any other instruction. */
    private static int width(AbstractInsnNode insn) {
        int op = insn.getOpcode();
        if (insn instanceof LdcInsnNode ldc) {
            if (ldc.cst instanceof Long || ldc.cst instanceof Double) return 2;
            return ldc.cst instanceof org.objectweb.asm.Handle || ldc.cst instanceof org.objectweb.asm.ConstantDynamic ? 0 : 1;
        }
        if (op == LLOAD || op == DLOAD || op == LCONST_0 || op == LCONST_1 || op == DCONST_0 || op == DCONST_1) return 2;
        if (op == ILOAD || op == FLOAD || op == ALOAD || op >= ACONST_NULL && op <= ICONST_5 || op >= FCONST_0 && op <= FCONST_2
            || op == BIPUSH || op == SIPUSH) return 1;
        return 0;
    }

    static boolean pure(AbstractInsnNode insn, Set<String> pureStatics) {
        int op = insn.getOpcode();
        if (insn instanceof FieldInsnNode get && op == GETSTATIC) return pureStatics.contains(get.owner + "." + get.name + ":" + get.desc);
        if (op >= ACONST_NULL && op <= ALOAD) return !(insn instanceof LdcInsnNode ldc) || !(ldc.cst instanceof org.objectweb.asm.Handle)
            && !(ldc.cst instanceof org.objectweb.asm.ConstantDynamic);
        if (op >= IADD && op <= LXOR) return op != IDIV && op != IREM && op != LDIV && op != LREM;
        return op == I2L || op == L2I || op == I2B || op == I2C || op == I2S || op == I2F || op == I2D || op == L2F || op == L2D
            || op == F2D || op == D2F || op == LCMP;
    }

    /** The number of stack values an instruction takes. */
    static int consumed(AbstractInsnNode insn, Frame<SourceValue> frame) {
        int op = insn.getOpcode();
        if (op < 0) return 0;
        if (insn instanceof MethodInsnNode call) return org.objectweb.asm.Type.getArgumentTypes(call.desc).length + (op == INVOKESTATIC ? 0 : 1);
        if (insn instanceof InvokeDynamicInsnNode indy) return org.objectweb.asm.Type.getArgumentTypes(indy.desc).length;
        if (insn instanceof MultiANewArrayInsnNode multi) return multi.dims;
        if (insn instanceof FieldInsnNode) return switch (op) { case GETSTATIC -> 0; case PUTSTATIC, GETFIELD -> 1; default -> 2; };
        if (op >= ACONST_NULL && op <= ALOAD) return 0;
        if (op >= IALOAD && op <= SALOAD) return 2;
        if (op >= ISTORE && op <= ASTORE) return 1;
        if (op >= IASTORE && op <= SASTORE) return 3;
        if (op == POP || op == POP2) return 1;
        // Shuffles re-push what they take; counted as uses, so a value they touch is never judged unused.
        if (op == DUP) return 1;
        if (op == DUP_X1 || op == DUP2 || op == SWAP) return 2;
        if (op == DUP_X2 || op == DUP2_X1) return 3;
        if (op == DUP2_X2) return 4;
        if (op >= IADD && op <= DREM || op >= ISHL && op <= LXOR || op >= LCMP && op <= DCMPG) return 2;
        if (op >= INEG && op <= DNEG || op >= I2L && op <= I2S) return 1;
        if (op >= IFEQ && op <= IFLE || op == IFNULL || op == IFNONNULL || op == TABLESWITCH || op == LOOKUPSWITCH) return 1;
        if (op >= IF_ICMPEQ && op <= IF_ACMPNE) return 2;
        if (op >= IRETURN && op <= ARETURN || op == ATHROW || op == MONITORENTER || op == MONITOREXIT) return 1;
        if (op == NEWARRAY || op == ANEWARRAY || op == ARRAYLENGTH || op == CHECKCAST || op == INSTANCEOF) return 1;
        return 0;
    }
}
