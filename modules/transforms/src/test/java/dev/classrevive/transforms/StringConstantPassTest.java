package dev.classrevive.transforms;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

class StringConstantPassTest {
    static byte[] fixture(String text,Integer index) {
        var writer=new ClassWriter(0); writer.visit(V1_8,ACC_PUBLIC,"Strings",null,"java/lang/Object",null);
        var method=writer.visitMethod(ACC_PUBLIC|ACC_STATIC,"value","()I",null,null);
        method.visitCode(); method.visitLdcInsn(text); if(index!=null) method.visitLdcInsn(index);
        method.visitMethodInsn(INVOKEVIRTUAL,"java/lang/String",index==null?"length":"charAt",index==null?"()I":"(I)C",false);
        method.visitInsn(IRETURN); method.visitMaxs(2,0); method.visitEnd(); writer.visitEnd(); return writer.toByteArray();
    }
    @Test void usesUtf16LengthAndCodeUnits() throws Exception {
        String text="A\uD83D\uDE00";
        for(int index=-1;index<3;index++) {
            var pass=new StringConstantPass(index==-1?StringConstantPass.Operation.LENGTH:StringConstantPass.Operation.CHAR_AT);
            var result=pass.apply(fixture(text,index==-1?null:index),10);
            JvmVerification.assertVerifiesIfChanged(fixture(text,index==-1?null:index),result.bytes());
            assertEquals(1,result.changes().size()); assertEquals(index==-1?3:(int)text.charAt(index),result.changes().getFirst().result());
            var node=new ClassNode(); new ClassReader(result.bytes()).accept(node,0);
            new org.objectweb.asm.tree.analysis.Analyzer<>(new org.objectweb.asm.tree.analysis.BasicVerifier()).analyze(node.name,node.methods.getFirst());
        }
        assertEquals(0,new StringConstantPass(StringConstantPass.Operation.LENGTH).apply(fixture("",null),10).changes().getFirst().result());
    }
    @Test void preservesThrowingIndices() {
        for(int index:new int[]{-1,1,Integer.MAX_VALUE}) {
            byte[] input=fixture("a",index);
            assertArrayEquals(input,new StringConstantPass(StringConstantPass.Operation.CHAR_AT).apply(input,10).bytes());
        }
    }
    @Test void requiresExactCallAndAdjacentOperands() {
        for(int variant=0;variant<4;variant++) {
            var node=new ClassNode(); new ClassReader(fixture("hello",null)).accept(node,0);
            var instructions=node.methods.getFirst().instructions;
            var call=(MethodInsnNode)instructions.getFirst().getNext();
            if(variant==0) call.owner="Other";
            if(variant==1) call.setOpcode(INVOKESTATIC);
            if(variant==2) call.itf=true;
            if(variant==3) { var label=new LabelNode(); instructions.insertBefore(call,label); instructions.insert(label,new LineNumberNode(1,label)); }
            var writer=new ClassWriter(0); node.accept(writer); byte[] input=writer.toByteArray();
            assertArrayEquals(input,new StringConstantPass(StringConstantPass.Operation.LENGTH).apply(input,10).bytes());
        }
    }
    @Test void preservesAnnotationsAndSharesPipelineBudget() {
        var node=new ClassNode(); new ClassReader(fixture("secret",null)).accept(node,0);
        var call=node.methods.getFirst().instructions.getFirst().getNext();
        call.invisibleTypeAnnotations=new java.util.ArrayList<>();
        call.invisibleTypeAnnotations.add(new TypeAnnotationNode(TypeReference.newTypeArgumentReference(TypeReference.METHOD_INVOCATION_TYPE_ARGUMENT,0).getValue(),null,"LMarker;"));
        var writer=new ClassWriter(0); node.accept(writer); byte[] annotated=writer.toByteArray();
        assertArrayEquals(annotated,new StringConstantPass(StringConstantPass.Operation.LENGTH).apply(annotated,10).bytes());
        node=new ClassNode(); new ClassReader(fixture("hello",null)).accept(node,0);
        var extra=new InsnList(); extra.add(new LdcInsnNode(3)); extra.add(new InsnNode(IADD));
        node.methods.getFirst().instructions.insertBefore(node.methods.getFirst().instructions.getLast(),extra);
        writer=new ClassWriter(0); node.accept(writer);
        var pipeline=new PassPipeline(BuiltinPasses.select("string-length,integer-constants"));
        var limited=pipeline.apply(writer.toByteArray(),1);
        JvmVerification.assertVerifiesIfChanged(writer.toByteArray(),limited.bytes());
        assertEquals(1,limited.changes().size()); assertTrue(limited.changeLimitReached());
        var complete=pipeline.apply(writer.toByteArray(),2);
        JvmVerification.assertVerifiesIfChanged(writer.toByteArray(),complete.bytes());
        assertEquals(2,complete.changes().size()); assertEquals(8,complete.changes().getLast().result());
    }
}
