package dev.classrevive.transforms;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.*;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import static org.objectweb.asm.Opcodes.*;

/** Folds only adjacent literal integer comparisons and conditional branches. */
public final class IntegerBranchPass implements BytecodePass {
    private static final int MAX_CLASS_BYTES = 16 * 1024 * 1024;
    private static final int MAX_CHANGES = 1_000_000;

    @Override public String id() { return "integer-branches-v2"; }

    @Override public TransformResult apply(byte[] original, int maxChanges) {
        Objects.requireNonNull(original);
        if (original.length > MAX_CLASS_BYTES || maxChanges < 1 || maxChanges > MAX_CHANGES)
            throw new IllegalArgumentException("Require class <=16 MiB and change budget 1..1000000");
        var reader = new ClassReader(original);
        var type = new ClassNode();
        reader.accept(type, 0);
        if (IntegerConstantPass.hasOpaqueAttributes(type)) return new TransformResult(original, List.of(), false);

        // Version 50+ classes need a stack map frame after an unconditional jump, so dead fall-through code must go.
        boolean framesRequired = (type.version & 0xFFFF) >= V1_6;
        var changes = new ArrayList<TransformResult.Change>();
        outer: for (var method : type.methods) {
            var initial = method.instructions.toArray();
            var indices = new IdentityHashMap<AbstractInsnNode, Integer>();
            for (int i = 0; i < initial.length; i++) indices.put(initial[i], i);
            var removed = java.util.Collections.newSetFromMap(new IdentityHashMap<AbstractInsnNode, Boolean>());
            for (int i = 0; i < initial.length; i++) {
                AbstractInsnNode leftNode = initial[i];
                if (removed.contains(leftNode)) continue;
                Integer left = IntegerConstantPass.literal(leftNode);
                if (left == null) continue;

                AbstractInsnNode next = leftNode.getNext();
                Integer right = 0;
                JumpInsnNode branch;
                boolean unary = next instanceof JumpInsnNode jump && isUnary(jump.getOpcode());
                if (unary) {
                    branch = (JumpInsnNode) next;
                } else {
                    Integer rightLiteral = IntegerConstantPass.literal(next);
                    if (rightLiteral == null || !(next.getNext() instanceof JumpInsnNode jump) || !isBinary(jump.getOpcode())) continue;
                    right = rightLiteral;
                    branch = jump;
                }
                if (IntegerConstantPass.hasInstructionAnnotations(leftNode)
                    || !unary && IntegerConstantPass.hasInstructionAnnotations(next)
                    || IntegerConstantPass.hasInstructionAnnotations(branch)) continue;

                boolean taken = evaluate(branch.getOpcode(), left, right);
                List<AbstractInsnNode> dead = List.of();
                if (taken && framesRequired) {
                    dead = unreachableAfter(branch, method);
                    if (dead == null) continue;
                }
                changes.add(new TransformResult.Change(id(), type.name, method.name, method.desc, indices.get(leftNode),
                    branch.getOpcode(), left, right, taken ? 1 : 0));
                method.instructions.set(leftNode, new InsnNode(NOP));
                if (!unary) method.instructions.set(next, new InsnNode(NOP));
                method.instructions.set(branch, taken ? new JumpInsnNode(GOTO, branch.label) : new InsnNode(NOP));
                for (var node : dead) { method.instructions.remove(node); removed.add(node); }
                if (changes.size() == maxChanges) break outer;
            }
        }
        if (changes.isEmpty()) return new TransformResult(original, changes, false);
        var writer = new ClassWriter(reader, 0);
        type.accept(writer);
        byte[] transformed = writer.toByteArray();
        if (transformed.length > MAX_CLASS_BYTES) return new TransformResult(original, List.of(), false);
        return new TransformResult(transformed, changes, changes.size() == maxChanges);
    }

    /**
     * Instructions between a new GOTO and the next stack map frame. A verified version 50+ method has a frame at every
     * jump target and handler, so these are reachable only by the fall-through the GOTO removed. Labels and line numbers
     * stay. Returns null (refuse the fold) when the region touches an exception range, reaches a jump/switch target
     * (a malformed input without the required frame) or carries type annotations.
     */
    private static List<AbstractInsnNode> unreachableAfter(JumpInsnNode branch, MethodNode method) {
        var guarded = java.util.Collections.newSetFromMap(new IdentityHashMap<LabelNode, Boolean>());
        for (var block : method.tryCatchBlocks) { guarded.add(block.start); guarded.add(block.end); guarded.add(block.handler); }
        for (var node : method.instructions) {
            if (node instanceof JumpInsnNode jump) guarded.add(jump.label);
            else if (node instanceof TableSwitchInsnNode table) { guarded.add(table.dflt); guarded.addAll(table.labels); }
            else if (node instanceof LookupSwitchInsnNode lookup) { guarded.add(lookup.dflt); guarded.addAll(lookup.labels); }
        }
        var dead = new ArrayList<AbstractInsnNode>();
        for (var node = branch.getNext(); node != null && !(node instanceof FrameNode); node = node.getNext()) {
            if (node instanceof LabelNode label) {
                if (framed(label)) break;
                if (guarded.contains(label)) return null;
                continue;
            }
            if (node instanceof LineNumberNode) continue;
            if (IntegerConstantPass.hasInstructionAnnotations(node)) return null;
            dead.add(node);
        }
        return dead;
    }

    private static boolean framed(LabelNode label) {
        var node = label.getNext();
        while (node instanceof LabelNode || node instanceof LineNumberNode) node = node.getNext();
        return node instanceof FrameNode;
    }

    private static boolean isUnary(int opcode) {
        return opcode == IFEQ || opcode == IFNE || opcode == IFLT || opcode == IFGE || opcode == IFGT || opcode == IFLE;
    }

    private static boolean isBinary(int opcode) {
        return opcode == IF_ICMPEQ || opcode == IF_ICMPNE || opcode == IF_ICMPLT || opcode == IF_ICMPGE
            || opcode == IF_ICMPGT || opcode == IF_ICMPLE;
    }

    private static boolean evaluate(int opcode, int left, int right) {
        return switch (opcode) {
            case IFEQ, IF_ICMPEQ -> left == right;
            case IFNE, IF_ICMPNE -> left != right;
            case IFLT, IF_ICMPLT -> left < right;
            case IFGE, IF_ICMPGE -> left >= right;
            case IFGT, IF_ICMPGT -> left > right;
            case IFLE, IF_ICMPLE -> left <= right;
            default -> throw new IllegalArgumentException("Unsupported integer branch opcode: " + opcode);
        };
    }
}
