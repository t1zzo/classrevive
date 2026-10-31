package dev.classrevive.transforms;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

class PassPipelineTest {
    static byte[] fixture(int value, int opcode) {
        var writer = new ClassWriter(0);
        writer.visit(V1_8, ACC_PUBLIC, "Unary", null, "java/lang/Object", null);
        var method = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "value", "()I", null, null);
        method.visitCode(); method.visitLdcInsn(value); method.visitInsn(opcode);
        method.visitInsn(IRETURN); method.visitMaxs(1, 0); method.visitEnd(); writer.visitEnd();
        return writer.toByteArray();
    }
    @Test void foldsUnaryWithJvmSemantics() throws Exception {
        int[][] cases = {{Integer.MIN_VALUE, INEG, Integer.MIN_VALUE}, {255,I2B,-1}, {-1,I2C,65535}, {65535,I2S,-1}};
        for (var item : cases) {
            var result = new IntegerUnaryPass().apply(fixture(item[0], item[1]), 10);
            JvmVerification.assertVerifiesIfChanged(fixture(item[0], item[1]), result.bytes());
            assertEquals(1, result.changes().size()); assertEquals(item[2], result.changes().getFirst().result());
            var node = new org.objectweb.asm.tree.ClassNode(); new ClassReader(result.bytes()).accept(node,0);
            new org.objectweb.asm.tree.analysis.Analyzer<>(new org.objectweb.asm.tree.analysis.BasicVerifier()).analyze(node.name,node.methods.getFirst());
        }
    }
    @Test void preservesBoundariesAndUnrecognizedOperations() {
        byte[] input = fixture(1,I2L);
        assertArrayEquals(input,new IntegerUnaryPass().apply(input,10).bytes());
        var node = new org.objectweb.asm.tree.ClassNode(); new ClassReader(fixture(1,INEG)).accept(node,0);
        var label = new org.objectweb.asm.tree.LabelNode();
        var instructions = node.methods.getFirst().instructions;
        instructions.insert(instructions.getFirst(),label);
        instructions.insert(label,new org.objectweb.asm.tree.LineNumberNode(12,label));
        var writer = new ClassWriter(0); node.accept(writer); input = writer.toByteArray();
        assertArrayEquals(input,new IntegerUnaryPass().apply(input,10).bytes());
    }
    @Test void preservesInstructionTypeAnnotations() {
        var node = new org.objectweb.asm.tree.ClassNode(); new ClassReader(fixture(255,I2B)).accept(node,0);
        var operation = node.methods.getFirst().instructions.getFirst().getNext();
        operation.visibleTypeAnnotations = new java.util.ArrayList<>();
        operation.visibleTypeAnnotations.add(new org.objectweb.asm.tree.TypeAnnotationNode(
            TypeReference.newTypeArgumentReference(TypeReference.CAST,0).getValue(),null,"LExampleAnnotation;"));
        var writer = new ClassWriter(0); node.accept(writer); byte[] input = writer.toByteArray();
        var result = new IntegerUnaryPass().apply(input,10);
        assertArrayEquals(input,result.bytes()); assertTrue(result.changes().isEmpty());
    }
    @Test void followsExplicitPassOrderInOneSweep() {
        var node = new org.objectweb.asm.tree.ClassNode(); new ClassReader(fixture(2,INEG)).accept(node,0);
        var tail = new org.objectweb.asm.tree.InsnList(); tail.add(new org.objectweb.asm.tree.LdcInsnNode(3));
        tail.add(new org.objectweb.asm.tree.InsnNode(IADD));
        node.methods.getFirst().instructions.insertBefore(node.methods.getFirst().instructions.getLast(),tail);
        node.methods.getFirst().maxStack=2;
        var writer = new ClassWriter(0); node.accept(writer);
        var forward = new PassPipeline(BuiltinPasses.select("integer-unary,integer-constants")).apply(writer.toByteArray(),10);
        var reverse = new PassPipeline(BuiltinPasses.select("integer-constants,integer-unary")).apply(writer.toByteArray(),10);
        JvmVerification.assertVerifiesIfChanged(writer.toByteArray(),forward.bytes()); JvmVerification.assertVerifiesIfChanged(writer.toByteArray(),reverse.bytes());
        assertEquals(2,forward.changes().size()); assertEquals(1,forward.changes().getLast().result());
        assertEquals(1,reverse.changes().size());
    }
    @Test void registryRejectsUnknownEmptyAndDuplicateSelections() {
        for (String selection : new String[]{"", "unknown", "integer-unary,integer-unary", "integer-unary,"})
            assertThrows(IllegalArgumentException.class, () -> BuiltinPasses.select(selection));
    }
    @Test void chainPreservesInputAndSharesBudget() {
        byte[] input = fixture(255,I2B), before = input.clone();
        var pipeline = new PassPipeline(BuiltinPasses.select("integer-constants,integer-unary"));
        var result = pipeline.apply(input, 1);
        JvmVerification.assertVerifiesIfChanged(input,result.bytes());
        assertEquals(1,result.changes().size()); assertTrue(result.changeLimitReached()); assertArrayEquals(before,input);
        assertThrows(IllegalArgumentException.class, () -> new PassPipeline(List.of(new IntegerConstantPass(),new IntegerConstantPass())));
    }
    @Test void rejectsPassThatExceedsBudget() {
        BytecodePass broken = new BytecodePass() {
            public String id() { return "broken"; }
            public TransformResult apply(byte[] bytes,int limit) {
                var change = new TransformResult.Change(id(),"X","v","()I",0,IADD,1,1,2);
                return new TransformResult(bytes,List.of(change,change),false);
            }
        };
        assertThrows(IllegalStateException.class, () -> new PassPipeline(List.of(broken)).apply(fixture(1,INEG),1));
    }
}
