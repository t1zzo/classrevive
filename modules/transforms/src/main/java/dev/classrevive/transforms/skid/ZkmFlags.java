package dev.classrevive.transforms.skid;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.*;
import java.util.*;

/**
 * Phase 2DM: Zelix KlassMaster 27's opaque flags. A {@code static boolean} or {@code int} of some class of the jar is copied to a local
 * and tested in front of real code; it is only written in branches those tests, or a test of the method's key, never
 * take. Proved by a fixed point: every such field with no initial value is assumed zero; with that and the known
 * {@code long} keys, the branches the constants decide are followed from each method's entry, and a flag some reachable
 * instruction writes with anything but false is dropped, until none is. By induction over the execution the rest are
 * always false: their reads become {@code 0} and the decided branches go. A branch the program decides by its own
 * constants stays as written; only tests that depend on a flag or a key are folded. Nothing is executed.
 */
final class ZkmFlags implements Opcodes {
    private final Map<String,ClassNode> nodes;
    private final Map<String,Map<Integer,Object>> parameters;
    private final Map<String,Map<Integer,Object>> packedKeys;
    private final Map<String,Object> classKeys;
    int flags, reads, branches;

    ZkmFlags(Map<String,ClassNode> nodes, Map<String,Map<Integer,Object>> parameters, Map<String,Map<Integer,Object>> packedKeys, Map<String,Object> classKeys) {
        this.nodes = nodes; this.parameters = parameters; this.packedKeys = packedKeys; this.classKeys = classKeys;
    }

    /** Folds the flags it proves; returns the classes changed. */
    Set<String> apply() {
        if (reflectiveWrite()) return Set.of();
        var candidates = new HashSet<String>();
        for (var node : nodes.values())
            for (var field : node.fields)
                if ((field.access & ACC_STATIC) != 0 && (field.desc.equals("Z") || field.desc.equals("I")) && (field.value == null || Integer.valueOf(0).equals(field.value)))
                    candidates.add(node.name + "." + field.name + ":" + field.desc);
        if (candidates.isEmpty()) return Set.of();
        while (true) {
            var dropped = new HashSet<String>();
            for (var node : nodes.values())
                for (var method : node.methods) {
                    if (method.instructions.size() == 0) continue;
                    var frames = frames(node, method, candidates);
                    var insns = method.instructions.toArray();
                    var reachable = frames == null ? null : reachable(method, insns, frames);
                    for (int i = 0; i < insns.length; i++) {
                        if (!(insns[i] instanceof FieldInsnNode put) || put.getOpcode() != PUTSTATIC) continue;
                        String name = put.owner + "." + put.name + ":" + put.desc;
                        if (!candidates.contains(name)) continue;
                        if (reachable == null || reachable[i] && !Integer.valueOf(0).equals(top(frames[i]))) {
                            dropped.add(name);
                        }
                    }
                }
            if (dropped.isEmpty()) break;
            candidates.removeAll(dropped);
            if (candidates.isEmpty()) return Set.of();
        }
        // Only flags something reads are the obfuscator's concern; the rest are left alone.
        var read = new HashSet<String>();
        for (var node : nodes.values())
            for (var method : node.methods)
                for (var insn : method.instructions)
                    if (insn instanceof FieldInsnNode get && get.getOpcode() == GETSTATIC) read.add(get.owner + "." + get.name + ":" + get.desc);
        candidates.retainAll(read);
        flags = candidates.size();
        var changed = new LinkedHashSet<String>();
        for (var node : nodes.values())
            for (var method : node.methods)
                if (method.instructions.size() > 0 && fold(node, method, candidates)) changed.add(node.name);
        return changed;
    }

    /**
     * Whether a reflective write that could set a static flag is left: {@code setLong} and the like throw on one,
     * and Zelix passes {@code null} as the receiver of a static field, so a method with no {@code null} writes instance fields.
     */
    private boolean reflectiveWrite() {
        for (var node : nodes.values())
            for (var method : node.methods) {
                boolean write = false, nullPushed = false;
                for (var insn : method.instructions) {
                    if (insn instanceof MethodInsnNode call && call.owner.equals("java/lang/reflect/Field") && (call.name.equals("set") || call.name.equals("setBoolean") || call.name.equals("setInt"))) write = true;
                    if (insn.getOpcode() == ACONST_NULL) nullPushed = true;
                }
                if (write && nullPushed) return true;
            }
        return false;
    }

    private Frame<ZkmKeys.KV>[] frames(ClassNode node, MethodNode method, Set<String> flagFields) {
        String key = SkidModel.key(node.name, method.name, method.desc);
        // Only long parameters: the keys. A program's int argument that happens to be constant decides nothing here.
        var known = new HashMap<Integer,Object>();
        parameters.getOrDefault(key, Map.of()).forEach((slot, value) -> { if (value instanceof Long) known.put(slot, value); });
        var statics = new HashMap<>(classKeys);
        for (String flag : flagFields) statics.put(flag, 0);
        var packed = new HashMap<Integer,Object>();
        packedKeys.getOrDefault(key, Map.of()).forEach((index, value) -> { if (value instanceof Long) packed.put(index, value); });
        var result = ZkmKeys.analyze(node.name, method, known, packed, statics);
        return result == null ? null : result.frames();
    }

    private static Object top(Frame<ZkmKeys.KV> frame) {
        return frame == null || frame.getStackSize() == 0 ? null : frame.getStack(frame.getStackSize() - 1).value();
    }

    /** Whether a conditional jump is taken, when its operands decide it; null when they do not. */
    private static Boolean decided(AbstractInsnNode insn, Frame<ZkmKeys.KV> frame) {
        int op = insn.getOpcode();
        if (frame == null) return null;
        if (op >= IFEQ && op <= IFLE) {
            if (!(top(frame) instanceof Integer v)) return null;
            return switch (op) { case IFEQ -> v == 0; case IFNE -> v != 0; case IFLT -> v < 0; case IFGE -> v >= 0; case IFGT -> v > 0; default -> v <= 0; };
        }
        if (op >= IF_ICMPEQ && op <= IF_ICMPLE) {
            if (frame.getStackSize() < 2 || !(top(frame) instanceof Integer b) || !(frame.getStack(frame.getStackSize() - 2).value() instanceof Integer a)) return null;
            return switch (op) { case IF_ICMPEQ -> a.equals(b); case IF_ICMPNE -> !a.equals(b); case IF_ICMPLT -> a < b; case IF_ICMPGE -> a >= b; case IF_ICMPGT -> a > b; default -> a <= b; };
        }
        return null;
    }

    /** The instructions reachable from the entry when decided branches go only their way; all of them for subroutines. */
    private static boolean[] reachable(MethodNode method, AbstractInsnNode[] insns, Frame<ZkmKeys.KV>[] frames) {
        var seen = new boolean[insns.length];
        for (var insn : insns)
            if (insn.getOpcode() == JSR || insn.getOpcode() == RET) { Arrays.fill(seen, true); return seen; }
        var list = method.instructions;
        var pending = new ArrayDeque<Integer>();
        pending.add(0);
        while (true) {
            while (!pending.isEmpty()) {
                int i = pending.pop();
                if (i >= insns.length || seen[i]) continue;
                seen[i] = true;
                var insn = insns[i];
                int op = insn.getOpcode();
                if (insn instanceof JumpInsnNode jump) {
                    Boolean taken = op == GOTO ? Boolean.TRUE : decided(insn, frames[i]);
                    if (taken == null || taken) pending.add(list.indexOf(jump.label));
                    if (taken == null || !taken) pending.add(i + 1);
                } else if (insn instanceof TableSwitchInsnNode table) {
                    pending.add(list.indexOf(table.dflt));
                    for (var label : table.labels) pending.add(list.indexOf(label));
                } else if (insn instanceof LookupSwitchInsnNode lookup) {
                    pending.add(list.indexOf(lookup.dflt));
                    for (var label : lookup.labels) pending.add(list.indexOf(label));
                } else if (!(op >= IRETURN && op <= RETURN) && op != ATHROW) pending.add(i + 1);
            }
            boolean grew = false;
            for (var block : method.tryCatchBlocks) {
                int handler = list.indexOf(block.handler);
                if (seen[handler]) continue;
                for (int i = list.indexOf(block.start); i < list.indexOf(block.end); i++)
                    if (seen[i]) { pending.add(handler); grew = true; break; }
            }
            if (!grew) return seen;
        }
    }

    private boolean fold(ClassNode node, MethodNode method, Set<String> proved) {
        var frames = frames(node, method, proved);
        if (frames == null) return false;
        Frame<ZkmKeys.KV>[] own;
        // What the program's own constants decide, with no flag and no key known.
        var plain = ZkmKeys.analyze(node.name, method, Map.of(), Map.of(), Map.of());
        own = plain == null ? null : plain.frames();
        Frame<SourceValue>[] sources;
        try { sources = new Analyzer<>(new SourceInterpreter()).analyze(node.name, method); }
        catch (AnalyzerException malformed) { return false; }
        var insns = method.instructions.toArray();
        boolean changed = false;
        for (int i = 0; i < insns.length; i++) {
            var insn = insns[i];
            if (insn instanceof JumpInsnNode jump && jump.getOpcode() != GOTO && jump.getOpcode() != JSR && frames[i] != null) {
                Boolean taken = decided(insn, frames[i]);
                if (taken == null || decided(insn, own == null ? null : own[i]) != null && !keyTest(insn, sources[i])) continue;
                var list = new InsnList();
                int op = jump.getOpcode();
                list.add(new InsnNode(POP));
                if (op >= IF_ICMPEQ && op <= IF_ICMPLE) list.add(new InsnNode(POP));
                if (taken) list.add(new JumpInsnNode(GOTO, jump.label));
                method.instructions.insertBefore(jump, list);
                method.instructions.remove(jump);
                branches++;
                changed = true;
            }
        }
        for (var insn : insns) {
            if (!(insn instanceof FieldInsnNode field) || !proved.contains(field.owner + "." + field.name + ":" + field.desc) || !method.instructions.contains(insn)) continue;
            if (field.getOpcode() == GETSTATIC) { method.instructions.set(insn, new InsnNode(ICONST_0)); reads++; changed = true; }
            else if (field.getOpcode() == PUTSTATIC) { method.instructions.set(insn, new InsnNode(POP)); changed = true; }
        }
        return changed;
    }

    /** A test of a {@code long} comparison: how Zelix tests a key, which a local constant may hold. */
    private static boolean keyTest(AbstractInsnNode insn, Frame<SourceValue> frame) {
        if (frame == null || frame.getStackSize() == 0 || insn.getOpcode() < IFEQ || insn.getOpcode() > IFLE) return false;
        var top = frame.getStack(frame.getStackSize() - 1);
        return top.insns.size() == 1 && top.insns.iterator().next().getOpcode() == LCMP;
    }
}
