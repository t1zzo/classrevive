package dev.classrevive.transforms;

import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import java.util.*;
import static org.objectweb.asm.Opcodes.*;

/** Evaluates only exact JDK String operations on adjacent literals, never target methods. */
public final class StringConstantPass implements BytecodePass {
    public enum Operation { LENGTH, CHAR_AT }
    private final Operation operation;
    public StringConstantPass(Operation operation) { this.operation=Objects.requireNonNull(operation); }
    public String id() { return operation==Operation.LENGTH ? "string-length-v1" : "string-char-at-v1"; }
    public TransformResult apply(byte[] original,int maxChanges) {
        Objects.requireNonNull(original);
        if(original.length>16*1024*1024 || maxChanges<1 || maxChanges>1_000_000)
            throw new IllegalArgumentException("Require class <=16 MiB and change budget 1..1000000");
        var reader=new ClassReader(original); var type=new ClassNode(); reader.accept(type,0);
        if(IntegerConstantPass.hasOpaqueAttributes(type)) return new TransformResult(original,List.of(),false);
        var changes=new ArrayList<TransformResult.Change>();
        outer: for(var method:type.methods) {
            var initial=method.instructions.toArray();
            for(int index=0;index<initial.length;index++) {
                var cursor=initial[index];
                if(!(cursor instanceof LdcInsnNode literal) || !(literal.cst instanceof String text)) continue;
                var argument=operation==Operation.CHAR_AT ? cursor.getNext() : null;
                var next=argument==null ? cursor.getNext() : argument.getNext();
                if(!(next instanceof MethodInsnNode call) || call.getOpcode()!=INVOKEVIRTUAL || call.itf ||
                    !call.owner.equals("java/lang/String") ||
                    !call.name.equals(operation==Operation.LENGTH?"length":"charAt") ||
                    !call.desc.equals(operation==Operation.LENGTH?"()I":"(I)C")) continue;
                if(IntegerConstantPass.hasInstructionAnnotations(cursor) || IntegerConstantPass.hasInstructionAnnotations(argument) ||
                    IntegerConstantPass.hasInstructionAnnotations(call)) continue;
                Integer position=operation==Operation.CHAR_AT ? IntegerConstantPass.literal(argument) : Integer.valueOf(0);
                if(position==null || (operation==Operation.CHAR_AT && (position<0 || position>=text.length()))) continue;
                int result=operation==Operation.LENGTH ? text.length() : text.charAt(position);
                changes.add(new TransformResult.Change(id(),type.name,method.name,method.desc,index,INVOKEVIRTUAL,text.length(),position,result));
                method.instructions.set(cursor,new LdcInsnNode(result));
                if(argument!=null) method.instructions.remove(argument);
                method.instructions.remove(call);
                if(changes.size()==maxChanges) break outer;
            }
        }
        if(changes.isEmpty()) return new TransformResult(original,changes,false);
        var writer=new ClassWriter(reader,0); type.accept(writer);
        return new TransformResult(writer.toByteArray(),changes,changes.size()==maxChanges);
    }
}
