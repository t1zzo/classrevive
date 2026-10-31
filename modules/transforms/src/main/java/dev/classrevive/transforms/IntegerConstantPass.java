package dev.classrevive.transforms;

import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import java.util.*;
import static org.objectweb.asm.Opcodes.*;

/** Folds only adjacent literal operands; labels, frames and debug nodes are hard boundaries. */
public final class IntegerConstantPass implements BytecodePass {
    public String id() { return "integer-constants-v1"; }
    public TransformResult apply(byte[] original, int maxChanges) {
        Objects.requireNonNull(original);
        if (original.length > 16 * 1024 * 1024) throw new IllegalArgumentException("Class exceeds 16 MiB");
        if (maxChanges < 1 || maxChanges > 1_000_000) throw new IllegalArgumentException("Change budget must be 1..1000000");
        var reader = new ClassReader(original);
        var type = new ClassNode(); reader.accept(type,0);
        // Opaque attributes may contain code offsets or constant-pool indexes we cannot rewrite.
        if (hasOpaqueAttributes(type)) return new TransformResult(original,List.of(),false);
        var changes = new ArrayList<TransformResult.Change>();
        outer: for (var method : type.methods) {
            var indices = new IdentityHashMap<AbstractInsnNode,Integer>();
            var initial = method.instructions.toArray();
            for (int i=0;i<initial.length;i++) indices.put(initial[i],i);
            var cursor = method.instructions.getFirst();
            while (cursor != null) {
                var second = cursor.getNext();
                var operation = second == null ? null : second.getNext();
                Integer left = literal(cursor), right = literal(second);
                Integer value = left == null || right == null || operation == null ? null : evaluate(left,right,operation.getOpcode());
                if (value == null || hasInstructionAnnotations(cursor) || hasInstructionAnnotations(second) || hasInstructionAnnotations(operation)) { cursor = cursor.getNext(); continue; }
                int index = indices.get(cursor);
                changes.add(new TransformResult.Change(id(),type.name,method.name,method.desc,index,operation.getOpcode(),left,right,value));
                var replacement = new LdcInsnNode(value);
                method.instructions.set(cursor,replacement);
                method.instructions.remove(second); method.instructions.remove(operation);
                indices.put(replacement,index); cursor = replacement;
                if (changes.size() == maxChanges) break outer;
            }
        }
        if (changes.isEmpty()) return new TransformResult(original,changes,false);
        // Stack at every retained boundary is unchanged; existing frames/maxima remain valid.
        var writer = new ClassWriter(reader,0); type.accept(writer);
        return new TransformResult(writer.toByteArray(),changes,changes.size() == maxChanges);
    }
    static boolean hasOpaqueAttributes(ClassNode type) {
        if (type.attrs != null && !type.attrs.isEmpty()) return true;
        if (type.fields.stream().anyMatch(field -> field.attrs != null && !field.attrs.isEmpty())) return true;
        if (type.methods.stream().anyMatch(method -> method.attrs != null && !method.attrs.isEmpty())) return true;
        return type.recordComponents != null && type.recordComponents.stream().anyMatch(part -> part.attrs != null && !part.attrs.isEmpty());
    }
    static boolean hasInstructionAnnotations(AbstractInsnNode node) {
        return node != null && ((node.visibleTypeAnnotations != null && !node.visibleTypeAnnotations.isEmpty()) ||
            (node.invisibleTypeAnnotations != null && !node.invisibleTypeAnnotations.isEmpty()));
    }
    static Integer literal(AbstractInsnNode node) {
        if (node == null) return null;
        int opcode = node.getOpcode();
        if (opcode >= ICONST_M1 && opcode <= ICONST_5) return opcode - ICONST_0;
        if (node instanceof IntInsnNode number && (opcode == BIPUSH || opcode == SIPUSH)) return number.operand;
        if (node instanceof LdcInsnNode number && number.cst instanceof Integer value) return value;
        return null;
    }
    private static Integer evaluate(int left, int right, int opcode) {
        return switch (opcode) {
            case IADD -> left + right; case ISUB -> left - right; case IMUL -> left * right;
            case IDIV -> right == 0 ? null : Integer.valueOf(left / right);
            case IREM -> right == 0 ? null : Integer.valueOf(left % right);
            case IAND -> left & right; case IOR -> left | right; case IXOR -> left ^ right;
            case ISHL -> left << right; case ISHR -> left >> right; case IUSHR -> left >>> right;
            default -> null;
        };
    }
}
