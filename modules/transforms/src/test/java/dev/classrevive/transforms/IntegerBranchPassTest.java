package dev.classrevive.transforms;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.*;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

class IntegerBranchPassTest {
    @Test void registersAsAnOptInPass() {
        assertEquals("integer-branches-v2", BuiltinPasses.select("integer-branches").getFirst().id());
        assertEquals("integer-constants", BuiltinPasses.DEFAULT);
    }

    @Test void foldsTrueTwoOperandComparisonAndKeepsStackValid() throws Exception {
        byte[] input = fixture(2, 3, IF_ICMPLT);
        var result = new IntegerBranchPass().apply(input, 10);
        assertEquals(1, result.changes().size());
        assertEquals(1, result.changes().getFirst().result());
        var type = read(result.bytes());
        var instructions = type.methods.getFirst().instructions.toArray();
        assertEquals(2, Arrays.stream(instructions).filter(node -> node.getOpcode() == NOP).count());
        assertTrue(Arrays.stream(instructions).anyMatch(node -> node.getOpcode() == GOTO));
        assertFalse(Arrays.stream(instructions).anyMatch(node -> node.getOpcode() == IF_ICMPLT));
        new Analyzer<>(new BasicVerifier()).analyze(type.name, type.methods.getFirst());
    }

    @Test void foldsFalseUnaryComparisonToFallthrough() throws Exception {
        byte[] input = fixture(5, 0, IFEQ);
        var result = new IntegerBranchPass().apply(input, 10);
        assertEquals(1, result.changes().size());
        assertEquals(0, result.changes().getFirst().result());
        var type = read(result.bytes());
        var instructions = type.methods.getFirst().instructions.toArray();
        assertEquals(2, Arrays.stream(instructions).filter(node -> node.getOpcode() == NOP).count());
        assertTrue(Arrays.stream(instructions).anyMatch(node -> node.getOpcode() == NOP));
        assertFalse(Arrays.stream(instructions).anyMatch(node -> node instanceof JumpInsnNode jump && jump.getOpcode() != GOTO));
        new Analyzer<>(new BasicVerifier()).analyze(type.name, type.methods.getFirst());
    }

    @Test void foldsEverySupportedIntegerComparisonOpcode() {
        int[][] cases = {
            {0, 0, IFEQ, 1}, {1, 0, IFNE, 1}, {-1, 0, IFLT, 1}, {0, 0, IFGE, 1},
            {1, 0, IFGT, 1}, {1, 0, IFLE, 0},
            {1, 1, IF_ICMPEQ, 1}, {1, 1, IF_ICMPNE, 0}, {1, 2, IF_ICMPLT, 1},
            {1, 2, IF_ICMPGE, 0}, {2, 1, IF_ICMPGT, 1}, {2, 1, IF_ICMPLE, 0}
        };
        var pass = new IntegerBranchPass();
        for (int[] item : cases) {
            var result = pass.apply(fixture(item[0], item[1], item[2]), 10);
            assertEquals(1, result.changes().size(), "opcode=" + item[2]);
            assertEquals(item[3], result.changes().getFirst().result(), "opcode=" + item[2]);
        }
    }

    @Test void everyFoldPassesJvmVerificationAndKeepsTheSelectedPath() throws Throwable {
        int[][] cases = {
            {0, 0, IFEQ}, {1, 0, IFEQ}, {-1, 0, IFLT}, {1, 0, IFLT}, {0, 0, IFGE}, {-1, 0, IFGE},
            {1, 0, IFGT}, {0, 0, IFGT}, {0, 0, IFLE}, {1, 0, IFLE}, {1, 0, IFNE}, {0, 0, IFNE},
            {1, 1, IF_ICMPEQ}, {1, 2, IF_ICMPEQ}, {1, 2, IF_ICMPNE}, {1, 1, IF_ICMPNE}, {1, 2, IF_ICMPLT}, {2, 1, IF_ICMPLT},
            {2, 2, IF_ICMPGE}, {1, 2, IF_ICMPGE}, {2, 1, IF_ICMPGT}, {1, 1, IF_ICMPGT}, {1, 1, IF_ICMPLE}, {2, 1, IF_ICMPLE},
            {Integer.MIN_VALUE, Integer.MAX_VALUE, IF_ICMPLT}
        };
        for (int version : new int[] {V1_5, V1_8, V17}) for (int[] item : cases) {
            byte[] input = fixture(version, item[0], item[1], item[2]);
            int expected = (int) linkAndCall(input);
            var result = new IntegerBranchPass().apply(input, 10);
            assertEquals(1, result.changes().size(), "opcode=" + item[2]);
            assertEquals(expected == 20 ? 1 : 0, result.changes().getFirst().result(), "opcode=" + item[2]);
            assertEquals(expected, linkAndCall(result.bytes()), "version=" + version + " opcode=" + item[2]);
        }
    }

    @Test void removesOnlyDeadFallThroughUpToTheNextFrame() {
        var result = new IntegerBranchPass().apply(fixture(V1_8, 2, 3, IF_ICMPLT), 10);
        var instructions = read(result.bytes()).methods.getFirst().instructions.toArray();
        assertFalse(Arrays.stream(instructions).anyMatch(node -> node instanceof IntInsnNode push && push.operand == 10));
        assertTrue(Arrays.stream(instructions).anyMatch(node -> node instanceof IntInsnNode push && push.operand == 20));
        var legacy = read(new IntegerBranchPass().apply(fixture(V1_5, 2, 3, IF_ICMPLT), 10).bytes()).methods.getFirst().instructions.toArray();
        assertTrue(Arrays.stream(legacy).anyMatch(node -> node instanceof IntInsnNode push && push.operand == 10));
    }

    @Test void refusesTakenFoldWhenDeadRegionReachesAnUnframedTarget() {
        var writer = new ClassWriter(0);
        writer.visit(V1_8, ACC_PUBLIC, "UnframedFixture", null, "java/lang/Object", null);
        var method = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "value", "()I", null, null);
        var target = new Label();
        method.visitCode(); method.visitInsn(ICONST_2); method.visitInsn(ICONST_3); method.visitJumpInsn(IF_ICMPLT, target);
        method.visitIntInsn(BIPUSH, 10); method.visitInsn(IRETURN);
        method.visitLabel(target); method.visitIntInsn(BIPUSH, 20); method.visitInsn(IRETURN);
        method.visitMaxs(2, 0); method.visitEnd(); writer.visitEnd();
        byte[] input = writer.toByteArray();
        assertArrayEquals(input, new IntegerBranchPass().apply(input, 10).bytes());
    }

    @Test void refusesTakenFoldWhenDeadRegionTouchesAnExceptionRange() {
        var writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(V1_8, ACC_PUBLIC, "TryFixture", null, "java/lang/Object", null);
        var method = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "value", "()I", null, null);
        var target = new Label(); var start = new Label(); var end = new Label(); var handler = new Label();
        method.visitCode(); method.visitTryCatchBlock(start, end, handler, "java/lang/RuntimeException");
        method.visitInsn(ICONST_2); method.visitInsn(ICONST_3); method.visitJumpInsn(IF_ICMPLT, target);
        method.visitLabel(start); method.visitIntInsn(BIPUSH, 10); method.visitInsn(IRETURN); method.visitLabel(end);
        method.visitLabel(handler); method.visitInsn(POP); method.visitIntInsn(BIPUSH, 30); method.visitInsn(IRETURN);
        method.visitLabel(target); method.visitIntInsn(BIPUSH, 20); method.visitInsn(IRETURN);
        method.visitMaxs(0, 0); method.visitEnd(); writer.visitEnd();
        byte[] input = writer.toByteArray();
        assertArrayEquals(input, new IntegerBranchPass().apply(input, 10).bytes());
    }

    @Test void keepsLabelsAsHardBoundariesAndRejectsReferenceBranches() {
        byte[] input = fixtureWithBoundary();
        assertArrayEquals(input, new IntegerBranchPass().apply(input, 10).bytes());
        input = referenceComparisonFixture();
        assertArrayEquals(input, new IntegerBranchPass().apply(input, 10).bytes());
    }

    @Test void preservesInstructionTypeAnnotations() {
        var type = read(fixture(1, 2, IF_ICMPNE));
        var jump = Arrays.stream(type.methods.getFirst().instructions.toArray())
            .filter(node -> node instanceof JumpInsnNode).findFirst().orElseThrow();
        jump.visibleTypeAnnotations = new java.util.ArrayList<>();
        jump.visibleTypeAnnotations.add(new TypeAnnotationNode(
            TypeReference.newTypeArgumentReference(TypeReference.CAST, 0).getValue(), null, "LExampleAnnotation;"));
        var writer = new ClassWriter(0); type.accept(writer); byte[] input = writer.toByteArray();
        assertArrayEquals(input, new IntegerBranchPass().apply(input, 10).bytes());
    }

    @Test void obeysChangeBudgetAndDoesNotMutateInput() {
        byte[] input = fixture(1, 2, IF_ICMPLT);
        byte[] before = input.clone();
        var result = new IntegerBranchPass().apply(input, 1);
        assertArrayEquals(before, input);
        assertEquals(1, result.changes().size());
        assertTrue(result.changeLimitReached());
    }

    private static ClassNode read(byte[] bytes) {
        var type = new ClassNode(); new ClassReader(bytes).accept(type, 0); return type;
    }

    /** Links (so the JVM verifier runs) and calls a fixture this test generated; never target code. */
    private static Object linkAndCall(byte[] bytes) throws Throwable {
        var type = new ClassLoader(null) { Class<?> define() { return defineClass(null, bytes, 0, bytes.length); } }.define();
        try { return type.getMethod("value").invoke(null); }
        catch (java.lang.reflect.InvocationTargetException error) { throw error.getCause(); }
    }

    private static byte[] fixture(int left, int right, int opcode) { return fixture(V1_8, left, right, opcode); }

    private static byte[] fixture(int version, int left, int right, int opcode) {
        var writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(version, ACC_PUBLIC, "BranchFixture", null, "java/lang/Object", null);
        var method = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "value", "()I", null, null);
        var target = new Label();
        method.visitCode();
        method.visitLdcInsn(left);
        if (opcode >= IF_ICMPEQ && opcode <= IF_ICMPLE) method.visitLdcInsn(right);
        method.visitJumpInsn(opcode, target);
        method.visitIntInsn(BIPUSH, 10); method.visitInsn(IRETURN);
        method.visitLabel(target); method.visitIntInsn(BIPUSH, 20); method.visitInsn(IRETURN);
        method.visitMaxs(0, 0); method.visitEnd(); writer.visitEnd(); return writer.toByteArray();
    }

    private static byte[] fixtureWithBoundary() {
        var writer = new ClassWriter(0);
        writer.visit(V1_8, ACC_PUBLIC, "BoundaryFixture", null, "java/lang/Object", null);
        var method = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "value", "()I", null, null);
        var boundary = new Label(); var target = new Label();
        method.visitCode(); method.visitLdcInsn(1); method.visitLabel(boundary); method.visitLineNumber(12, boundary); method.visitLdcInsn(2);
        method.visitJumpInsn(IF_ICMPEQ, target); method.visitInsn(ICONST_0); method.visitInsn(IRETURN);
        method.visitLabel(target); method.visitInsn(ICONST_1); method.visitInsn(IRETURN);
        method.visitMaxs(2, 0); method.visitEnd(); writer.visitEnd(); return writer.toByteArray();
    }

    private static byte[] referenceComparisonFixture() {
        var writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(V1_8, ACC_PUBLIC, "ReferenceFixture", null, "java/lang/Object", null);
        var method = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "value", "()I", null, null);
        var target = new Label(); method.visitCode(); method.visitInsn(ACONST_NULL); method.visitInsn(ACONST_NULL);
        method.visitJumpInsn(IF_ACMPEQ, target); method.visitInsn(ICONST_0); method.visitInsn(IRETURN);
        method.visitLabel(target); method.visitInsn(ICONST_1); method.visitInsn(IRETURN);
        method.visitMaxs(0, 0); method.visitEnd(); writer.visitEnd(); return writer.toByteArray();
    }
}
