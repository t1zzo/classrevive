package dev.classrevive.transforms;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

class IntegerConstantPassTest {
    byte[] fixture(int a, int b, int opcode, boolean boundary) {
        var writer = new ClassWriter(0);
        writer.visit(V1_8, ACC_PUBLIC, "Example", null, "java/lang/Object", null);
        var method = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "value", "()I", null, null);
        method.visitCode(); method.visitLdcInsn(a);
        if (boundary) { var label = new Label(); method.visitLabel(label); method.visitLineNumber(42, label); }
        method.visitLdcInsn(b); method.visitInsn(opcode); method.visitInsn(IRETURN);
        method.visitMaxs(2, 0); method.visitEnd(); writer.visitEnd(); return writer.toByteArray();
    }
    @Test void foldsEverySupportedOperationWithJvmIntegerSemantics() throws Exception {
        int[][] cases = {{2,3,IADD,5},{2,3,ISUB,-1},{Integer.MAX_VALUE,2,IMUL,-2},
            {Integer.MIN_VALUE,-1,IDIV,Integer.MIN_VALUE},{-7,3,IREM,-1},{6,3,IAND,2},
            {6,3,IOR,7},{6,3,IXOR,5},{1,33,ISHL,2},{-4,1,ISHR,-2},{-1,1,IUSHR,Integer.MAX_VALUE}};
        for (int[] item : cases) {
            byte[] input = fixture(item[0],item[1],item[2],false), original = input.clone();
            var result = new IntegerConstantPass().apply(input, 100);
            assertEquals(1, result.changes().size());
            var node = new ClassNode(); new ClassReader(result.bytes()).accept(node, 0);
            assertEquals(item[3], ((LdcInsnNode)node.methods.getFirst().instructions.getFirst()).cst);
            assertEquals(2,node.methods.getFirst().instructions.size());
            assertArrayEquals(original,input);
            JvmVerification.assertVerifiesIfChanged(input,result.bytes());
            new org.objectweb.asm.tree.analysis.Analyzer<>(new org.objectweb.asm.tree.analysis.BasicVerifier()).analyze(node.name,node.methods.getFirst());
        }
    }
    @Test void preservesThrowingOperationsAndInstructionBoundaries() {
        for (byte[] input : List.of(fixture(5,0,IDIV,false), fixture(5,0,IREM,false), fixture(1,2,IADD,true))) {
            var result = new IntegerConstantPass().apply(input,100);
            assertTrue(result.changes().isEmpty()); assertArrayEquals(input,result.bytes());
        }
    }
    @Test void hasDefensiveByteCopiesAndRejectsInvalidBudget() {
        byte[] input = fixture(1,2,IADD,false);
        var result = new IntegerConstantPass().apply(input,1);
        byte[] first = result.bytes(); first[0]=0;
        assertNotEquals(0,result.bytes()[0]);
        assertThrows(IllegalArgumentException.class, () -> new IntegerConstantPass().apply(input,0));
    }
    @Test void boundsChainedFoldingAndKeepsOriginalInstructionIndex() {
        var node = new ClassNode(); new ClassReader(fixture(1,2,IADD,false)).accept(node,0);
        var method = node.methods.getFirst();
        var tail = new InsnList(); tail.add(new LdcInsnNode(3)); tail.add(new InsnNode(IADD));
        method.instructions.insertBefore(method.instructions.getLast(),tail);
        var writer = new ClassWriter(0); node.accept(writer);
        var once = new IntegerConstantPass().apply(writer.toByteArray(),1);
        JvmVerification.assertVerifiesIfChanged(writer.toByteArray(),once.bytes());
        assertEquals(1,once.changes().size()); assertTrue(once.changeLimitReached());
        var twice = new IntegerConstantPass().apply(writer.toByteArray(),2);
        JvmVerification.assertVerifiesIfChanged(writer.toByteArray(),twice.bytes());
        assertEquals(2,twice.changes().size());
        assertEquals(6,twice.changes().getLast().result());
        assertEquals(0,twice.changes().getLast().instructionIndex());
    }
    @Test void leavesUnknownAttributesUntouched() {
        var node = new ClassNode(); new ClassReader(fixture(1,2,IADD,false)).accept(node,0);
        node.attrs = new ArrayList<>();
        node.attrs.add(new Attribute("UnknownOffsets") {
            @Override protected ByteVector write(ClassWriter writer, byte[] code, int length, int stack, int locals) {
                return new ByteVector().putInt(1234);
            }
        });
        var writer = new ClassWriter(0); node.accept(writer); byte[] input = writer.toByteArray();
        var result = new IntegerConstantPass().apply(input,100);
        assertTrue(result.changes().isEmpty()); assertArrayEquals(input,result.bytes());
    }
    @Test void preservesAlternateEntryFramesWhileFoldingEarlierExpression() {
        var writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        writer.visit(V1_8,ACC_PUBLIC,"Branches",null,"java/lang/Object",null);
        var method = writer.visitMethod(ACC_PUBLIC | ACC_STATIC,"value","(Z)I",null,null);
        method.visitCode(); method.visitInsn(ICONST_4); method.visitInsn(ICONST_5); method.visitInsn(IADD); method.visitInsn(POP);
        var other = new Label(); var join = new Label();
        method.visitVarInsn(ILOAD,0); method.visitJumpInsn(IFEQ,other);
        method.visitInsn(ICONST_1); method.visitJumpInsn(GOTO,join);
        method.visitLabel(other); method.visitInsn(ICONST_2);
        method.visitLabel(join); method.visitInsn(ICONST_3); method.visitInsn(IADD); method.visitInsn(IRETURN);
        method.visitMaxs(0,0); method.visitEnd(); writer.visitEnd();
        byte[] input=writer.toByteArray(); var result=new IntegerConstantPass().apply(input,100);
        JvmVerification.assertVerifiesIfChanged(input,result.bytes());
        assertEquals(1,result.changes().size()); assertSerializedMetadata(input,result.bytes(),0);
        var node=new ClassNode(); new ClassReader(result.bytes()).accept(node,0);
        assertEquals(1, Arrays.stream(node.methods.getFirst().instructions.toArray()).filter(i -> i.getOpcode()==IADD).count());
    }
    @Test void preservesExceptionHandlerAndThrowingDivisionAfterFolding() {
        var writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        writer.visit(V1_8,ACC_PUBLIC,"Handlers",null,"java/lang/Object",null);
        var method=writer.visitMethod(ACC_PUBLIC | ACC_STATIC,"value","()I",null,null);
        var start=new Label(); var end=new Label(); var handler=new Label();
        method.visitTryCatchBlock(start,end,handler,"java/lang/ArithmeticException"); method.visitCode();
        method.visitInsn(ICONST_4); method.visitInsn(ICONST_5); method.visitInsn(IADD); method.visitInsn(POP);
        method.visitLabel(start); method.visitInsn(ICONST_1); method.visitInsn(ICONST_0); method.visitInsn(IDIV);
        method.visitLabel(end); method.visitInsn(IRETURN); method.visitLabel(handler);
        method.visitInsn(POP); method.visitInsn(ICONST_M1); method.visitInsn(IRETURN);
        method.visitMaxs(0,0); method.visitEnd(); writer.visitEnd();
        byte[] input=writer.toByteArray(); var result=new IntegerConstantPass().apply(input,100);
        JvmVerification.assertVerifiesIfChanged(input,result.bytes());
        assertEquals(1,result.changes().size()); assertSerializedMetadata(input,result.bytes(),1);
    }
    private void assertSerializedMetadata(byte[] input, byte[] output, int handlers) {
        var before=new ClassNode(); var after=new ClassNode();
        new ClassReader(input).accept(before,ClassReader.EXPAND_FRAMES);
        new ClassReader(output).accept(after,ClassReader.EXPAND_FRAMES);
        assertEquals(frames(before),frames(after));
        assertEquals(handlers,after.methods.getFirst().tryCatchBlocks.size());
        var errors=new java.io.StringWriter();
        org.objectweb.asm.util.CheckClassAdapter.verify(new ClassReader(output),false,new java.io.PrintWriter(errors));
        assertEquals("",errors.toString());
    }
    private List<String> frames(ClassNode type) {
        return Arrays.stream(type.methods.getFirst().instructions.toArray()).filter(i -> i instanceof FrameNode)
            .map(i -> (FrameNode)i).map(f -> f.type + ":" + f.local + ":" + f.stack).toList();
    }
}
