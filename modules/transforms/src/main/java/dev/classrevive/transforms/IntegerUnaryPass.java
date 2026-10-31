package dev.classrevive.transforms;

import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import java.util.*;
import static org.objectweb.asm.Opcodes.*;

/** Folds adjacent literal unary integer operations without crossing control-flow boundaries. */
public final class IntegerUnaryPass implements BytecodePass {
    public String id() { return "integer-unary-v1"; }
    public TransformResult apply(byte[] original, int maxChanges) {
        Objects.requireNonNull(original);
        if (original.length > 16*1024*1024 || maxChanges < 1 || maxChanges > 1_000_000)
            throw new IllegalArgumentException("Require class <=16 MiB and change budget 1..1000000");
        var reader = new ClassReader(original); var type = new ClassNode(); reader.accept(type,0);
        if (IntegerConstantPass.hasOpaqueAttributes(type)) return new TransformResult(original,List.of(),false);
        var changes = new ArrayList<TransformResult.Change>();
        outer: for (var method : type.methods) {
            var indices = new IdentityHashMap<AbstractInsnNode,Integer>();
            var initial = method.instructions.toArray();
            for (int i=0;i<initial.length;i++) indices.put(initial[i],i);
            var cursor = method.instructions.getFirst();
            while (cursor != null) {
                Integer literal = IntegerConstantPass.literal(cursor);
                var operation = cursor.getNext();
                Integer value = literal == null || operation == null ? null : switch(operation.getOpcode()) {
                    case INEG -> -literal; case I2B -> (int)(byte)(int)literal;
                    case I2C -> (int)(char)(int)literal; case I2S -> (int)(short)(int)literal;
                    default -> null;
                };
                if (value == null || IntegerConstantPass.hasInstructionAnnotations(cursor) || IntegerConstantPass.hasInstructionAnnotations(operation)) { cursor = cursor.getNext(); continue; }
                int index = indices.get(cursor);
                changes.add(new TransformResult.Change(id(),type.name,method.name,method.desc,index,operation.getOpcode(),literal,0,value));
                var replacement = new LdcInsnNode(value);
                method.instructions.set(cursor,replacement); method.instructions.remove(operation);
                indices.put(replacement,index); cursor = replacement;
                if (changes.size()==maxChanges) break outer;
            }
        }
        if (changes.isEmpty()) return new TransformResult(original,changes,false);
        var writer = new ClassWriter(reader,0); type.accept(writer);
        return new TransformResult(writer.toByteArray(),changes,changes.size()==maxChanges);
    }
}
