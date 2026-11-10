package dev.classrevive.transforms.flow;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import java.io.InputStream;
import java.util.*;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/** Phase 2DL: synthetic classes written with ASM, one obfuscation pattern each; the output is checked by the JVM verifier. */
class ControlFlowPassTest {

    // ------------------------------------------------------------------------------------------------- helpers

    /** A class {@code T} (version {@code version}) with the members {@code body} writes. */
    private static byte[] type(int version, Consumer<ClassWriter> body) {
        var writer = new ClassWriter(version >= V1_6 ? ClassWriter.COMPUTE_FRAMES : ClassWriter.COMPUTE_MAXS);
        writer.visit(version, ACC_PUBLIC | ACC_SUPER, "T", null, "java/lang/Object", null);
        var init = writer.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode(); init.visitVarInsn(ALOAD, 0); init.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(RETURN); init.visitMaxs(0, 0); init.visitEnd();
        body.accept(writer);
        writer.visitEnd();
        return writer.toByteArray();
    }
    private static MethodVisitor method(ClassWriter writer, String name, String desc) {
        var method = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, name, desc, null, null);
        method.visitCode();
        return method;
    }
    private static void end(MethodVisitor method) { method.visitMaxs(0, 0); method.visitEnd(); }

    private static ControlFlowPass.Result run(byte[]... classes) {
        var entries = new LinkedHashMap<String,byte[]>();
        for (byte[] bytes : classes) entries.put(new ClassReader(bytes).getClassName() + ".class", bytes);
        return new ControlFlowPass().apply(entries, 10_000);
    }
    private static byte[] output(ControlFlowPass.Result result, String name) { return result.entries().get(name + ".class"); }
    private static MethodNode find(byte[] bytes, String name) {
        var node = new ClassNode(); new ClassReader(bytes).accept(node, 0);
        return node.methods.stream().filter(m -> m.name.equals(name)).findFirst().orElseThrow();
    }
    private static long count(MethodNode method, int... opcodes) {
        var set = new HashSet<Integer>(); for (int op : opcodes) set.add(op);
        long n = 0; for (var insn : method.instructions) if (set.contains(insn.getOpcode())) n++;
        return n;
    }
    private static long conditionals(MethodNode method) {
        long n = 0;
        for (var insn : method.instructions) if (insn instanceof JumpInsnNode && insn.getOpcode() != GOTO || insn instanceof TableSwitchInsnNode || insn instanceof LookupSwitchInsnNode) n++;
        return n;
    }
    /** The JVM verifier checks the class (linking it; no initializer and no method of it runs). */
    private static void verifies(byte[] bytes) {
        var type = new ClassLoader(null) { Class<?> define() { return defineClass(null, bytes, 0, bytes.length); } }.define();
        assertDoesNotThrow(type::getDeclaredMethods, "the rewritten class must pass the JVM verifier");
    }

    // ------------------------------------------------------------------------------------------------- opaque predicates

    @Test void foldsAPredicateOnAConstantValueFieldAndRemovesTheDeadArm() {
        byte[] input = type(V1_8, w -> {
            w.visitField(ACC_STATIC | ACC_FINAL | ACC_PRIVATE, "K", "I", null, 7).visitEnd();
            var m = method(w, "f", "(I)I");
            var fake = new Label();
            m.visitFieldInsn(GETSTATIC, "T", "K", "I"); m.visitInsn(ICONST_3); m.visitInsn(IMUL); m.visitIntInsn(BIPUSH, 20);
            m.visitJumpInsn(IF_ICMPLE, fake);
            m.visitVarInsn(ILOAD, 0); m.visitInsn(IRETURN);
            m.visitLabel(fake); m.visitInsn(ICONST_M1); m.visitInsn(IRETURN);
            end(m);
        });
        var result = run(input);
        var f = find(output(result, "T"), "f");
        assertEquals(0, conditionals(f));
        assertEquals(0, count(f, ICONST_M1), "the arm the predicate never takes is gone");
        assertEquals(0, count(f, GETSTATIC, IMUL), "the predicate's computation is gone");
        assertEquals(1, result.report().count("opaque branches decided"));
        assertEquals(1, result.changes().size());
        verifies(output(result, "T"));
    }

    @Test void provesAFinalFieldStoredInTheInitializerPrefix() {
        byte[] input = type(V1_8, w -> {
            w.visitField(ACC_STATIC | ACC_FINAL, "S", "I", null, null).visitEnd();
            var clinit = w.visitMethod(ACC_STATIC, "<clinit>", "()V", null, null);
            clinit.visitCode(); clinit.visitIntInsn(BIPUSH, 42); clinit.visitFieldInsn(PUTSTATIC, "T", "S", "I"); clinit.visitInsn(RETURN); end(clinit);
            var m = method(w, "f", "(I)I");
            var fake = new Label();
            m.visitFieldInsn(GETSTATIC, "T", "S", "I"); m.visitIntInsn(BIPUSH, 42); m.visitJumpInsn(IF_ICMPNE, fake);
            m.visitVarInsn(ILOAD, 0); m.visitInsn(IRETURN);
            m.visitLabel(fake); m.visitInsn(ACONST_NULL); m.visitInsn(ATHROW);
            end(m);
        });
        var result = run(input);
        var f = find(output(result, "T"), "f");
        assertEquals(0, conditionals(f));
        assertEquals(0, count(f, ATHROW));
        verifies(output(result, "T"));
    }

    @Test void leavesAFieldAlone_whenTheInitializerCallsOutBeforeTheStore() {
        byte[] input = type(V1_8, w -> {
            w.visitField(ACC_STATIC | ACC_FINAL, "S", "I", null, null).visitEnd();
            var clinit = w.visitMethod(ACC_STATIC, "<clinit>", "()V", null, null);
            clinit.visitCode(); clinit.visitMethodInsn(INVOKESTATIC, "T", "g", "()V", false);
            clinit.visitIntInsn(BIPUSH, 42); clinit.visitFieldInsn(PUTSTATIC, "T", "S", "I"); clinit.visitInsn(RETURN); end(clinit);
            var g = method(w, "g", "()V"); g.visitInsn(RETURN); end(g);
            var m = method(w, "f", "(I)I");
            var other = new Label();
            m.visitFieldInsn(GETSTATIC, "T", "S", "I"); m.visitIntInsn(BIPUSH, 42); m.visitJumpInsn(IF_ICMPNE, other);
            m.visitVarInsn(ILOAD, 0); m.visitInsn(IRETURN);
            m.visitLabel(other); m.visitInsn(ICONST_0); m.visitInsn(IRETURN);
            end(m);
        });
        var result = run(input);
        assertArrayEquals(input, output(result, "T"), "g() could read S before it is stored: not proved");
        assertEquals(0, result.report().count("static final constants proved"));
    }

    @Test void provesTheParityIdentityOfXTimesXPlusOne() {
        byte[] input = type(V1_8, w -> {
            var m = method(w, "f", "(I)I");
            var junk = new Label();
            m.visitVarInsn(ILOAD, 0); m.visitVarInsn(ILOAD, 0); m.visitInsn(ICONST_1); m.visitInsn(IADD); m.visitInsn(IMUL);
            m.visitInsn(ICONST_2); m.visitInsn(IREM); m.visitJumpInsn(IFNE, junk);
            m.visitVarInsn(ILOAD, 0); m.visitInsn(IRETURN);
            m.visitLabel(junk); m.visitIntInsn(BIPUSH, 99); m.visitInsn(IRETURN);
            end(m);
        });
        var result = run(input);
        var f = find(output(result, "T"), "f");
        assertEquals(0, conditionals(f));
        assertEquals(0, count(f, IMUL, IREM, BIPUSH), "x*(x+1) % 2 is always 0: the test and its computation go");
        verifies(output(result, "T"));
    }

    @Test void decidesAnArrayLengthAgainstZeroAndValueNumberedComparisons() {
        byte[] input = type(V1_8, w -> {
            var m = method(w, "f", "([II)I");
            var junk = new Label(); var junk2 = new Label();
            m.visitVarInsn(ALOAD, 0); m.visitInsn(ARRAYLENGTH); m.visitJumpInsn(IFLT, junk);
            m.visitVarInsn(ILOAD, 1); m.visitVarInsn(ILOAD, 1); m.visitInsn(IXOR); m.visitJumpInsn(IFNE, junk2);
            m.visitVarInsn(ILOAD, 1); m.visitInsn(IRETURN);
            m.visitLabel(junk); m.visitInsn(ICONST_M1); m.visitInsn(IRETURN);
            m.visitLabel(junk2); m.visitInsn(ICONST_M1); m.visitInsn(IRETURN);
            end(m);
        });
        var result = run(input);
        var f = find(output(result, "T"), "f");
        assertEquals(0, conditionals(f));
        assertEquals(0, count(f, ICONST_M1));
        assertEquals(1, count(f, ARRAYLENGTH), "arraylength may throw (null array): it stays");
        verifies(output(result, "T"));
    }

    @Test void narrowsAlongBranches_soANestedContradictionIsDead_inAMethodWithEvidence() {
        byte[] input = type(V1_8, w -> {
            w.visitField(ACC_STATIC | ACC_FINAL | ACC_PRIVATE, "K", "I", null, 1).visitEnd();
            var m = method(w, "f", "(I)I");
            var out = new Label(); var junk = new Label(); var fake = new Label();
            m.visitFieldInsn(GETSTATIC, "T", "K", "I"); m.visitJumpInsn(IFEQ, fake);
            m.visitVarInsn(ILOAD, 0); m.visitIntInsn(BIPUSH, 10); m.visitJumpInsn(IF_ICMPLE, out);
            m.visitVarInsn(ILOAD, 0); m.visitInsn(ICONST_5); m.visitJumpInsn(IF_ICMPLT, junk);
            m.visitVarInsn(ILOAD, 0); m.visitInsn(IRETURN);
            m.visitLabel(junk); m.visitInsn(ICONST_M1); m.visitInsn(IRETURN);
            m.visitLabel(out); m.visitInsn(ICONST_0); m.visitInsn(IRETURN);
            m.visitLabel(fake); m.visitInsn(ICONST_M1); m.visitInsn(IRETURN);
            end(m);
        });
        var result = run(input);
        var f = find(output(result, "T"), "f");
        assertEquals(1, conditionals(f), "x > 10 stays; x < 5 under it never holds");
        assertEquals(0, count(f, ICONST_M1));
        verifies(output(result, "T"));
    }

    @Test void evaluatesPureStaticCallsAndStringConstants() {
        byte[] input = type(V1_8, w -> {
            var m = method(w, "f", "(I)I");
            var junk = new Label(); var junk2 = new Label();
            m.visitIntInsn(BIPUSH, -9); m.visitMethodInsn(INVOKESTATIC, "java/lang/Math", "abs", "(I)I", false);
            m.visitIntInsn(BIPUSH, 9); m.visitJumpInsn(IF_ICMPNE, junk);
            m.visitLdcInsn("abc"); m.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "length", "()I", false);
            m.visitInsn(ICONST_3); m.visitJumpInsn(IF_ICMPNE, junk2);
            m.visitVarInsn(ILOAD, 0); m.visitInsn(IRETURN);
            m.visitLabel(junk); m.visitInsn(ICONST_M1); m.visitInsn(IRETURN);
            m.visitLabel(junk2); m.visitInsn(ICONST_M1); m.visitInsn(IRETURN);
            end(m);
        });
        var result = run(input);
        var f = find(output(result, "T"), "f");
        assertEquals(0, conditionals(f));
        verifies(output(result, "T"));
    }

    @Test void leavesANullTestOnAFreshObjectInCleanCode() {
        // javac's try-with-resources null-checks a resource it has just created: proved, but not evidence of obfuscation.
        byte[] input = type(V1_8, w -> {
            var m = method(w, "f", "(I)I");
            var skip = new Label();
            m.visitTypeInsn(NEW, "java/lang/Object"); m.visitInsn(DUP); m.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
            m.visitJumpInsn(IFNULL, skip);
            m.visitVarInsn(ILOAD, 0); m.visitInsn(IRETURN);
            m.visitLabel(skip); m.visitInsn(ICONST_M1); m.visitInsn(IRETURN);
            end(m);
        });
        assertArrayEquals(input, output(run(input), "T"));
    }

    @Test void foldsNullTestsOnFreshObjects_inAMethodWithEvidence() {
        byte[] input = type(V1_8, w -> {
            var m = method(w, "f", "(I)I");
            var junk = new Label();
            m.visitInsn(ACONST_NULL); m.visitJumpInsn(IFNONNULL, junk);
            m.visitTypeInsn(NEW, "java/lang/Object"); m.visitInsn(DUP); m.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
            m.visitJumpInsn(IFNULL, junk);
            m.visitVarInsn(ILOAD, 0); m.visitInsn(IRETURN);
            m.visitLabel(junk); m.visitInsn(ICONST_M1); m.visitInsn(IRETURN);
            end(m);
        });
        var result = run(input);
        var f = find(output(result, "T"), "f");
        assertEquals(0, conditionals(f));
        assertEquals(1, count(f, NEW), "the allocation (a constructor call) stays");
        verifies(output(result, "T"));
    }

    // ------------------------------------------------------------------------------------------------- dead code

    @Test void removesCodeNoPathReaches() {
        byte[] input = type(V1_5, w -> {
            var m = method(w, "f", "(I)I");
            var over = new Label();
            m.visitJumpInsn(GOTO, over);
            m.visitInsn(ICONST_1); m.visitInsn(POP); m.visitInsn(ICONST_2); m.visitInsn(IRETURN);
            m.visitLabel(over); m.visitVarInsn(ILOAD, 0); m.visitInsn(IRETURN);
            end(m);
        });
        var result = run(input);
        var f = find(output(result, "T"), "f");
        assertEquals(0, count(f, ICONST_1, ICONST_2, GOTO), "the junk and then the jump to the next instruction go");
        assertEquals(2, f.instructions.size() - count(f, -1) - countMarkers(f));
        verifies(output(result, "T"));
    }
    @Test void dropsTheLineNumberOfDeadCodeAtTheEnd() {
        // Gson 2.13.1 in an early run: a line number left past the last instruction is "Invalid pc in LineNumberTable".
        byte[] input = type(V1_8, w -> {
            w.visitField(ACC_STATIC | ACC_FINAL | ACC_PRIVATE, "K", "I", null, 0).visitEnd();
            var m = method(w, "f", "(I)I");
            var tail = new Label(); var line = new Label();
            m.visitFieldInsn(GETSTATIC, "T", "K", "I"); m.visitJumpInsn(IFNE, tail);
            m.visitVarInsn(ILOAD, 0); m.visitInsn(IRETURN);
            m.visitLabel(tail); m.visitLabel(line); m.visitLineNumber(99, line);
            m.visitTypeInsn(NEW, "java/lang/IllegalStateException"); m.visitInsn(DUP);
            m.visitMethodInsn(INVOKESPECIAL, "java/lang/IllegalStateException", "<init>", "()V", false); m.visitInsn(ATHROW);
            end(m);
        });
        var result = run(input);
        var f = find(output(result, "T"), "f");
        assertEquals(0, count(f, ATHROW));
        for (var insn : f.instructions) if (insn instanceof LineNumberNode number) assertNotNull(Cfg.start(number.start), "line " + number.line);
        verifies(output(result, "T"));
    }

    private static long countMarkers(MethodNode method) { long n = 0; for (var insn : method.instructions) if (insn.getOpcode() < 0) n++; return n; }

    // ------------------------------------------------------------------------------------------------- exceptions

    @Test void turnsAThrowCaughtAndDiscardedInTheSameMethodIntoAJump() {
        byte[] input = type(V1_8, w -> {
            var m = method(w, "f", "(I)I");
            var start = new Label(); var end = new Label(); var handler = new Label();
            m.visitTryCatchBlock(start, end, handler, "java/lang/IllegalStateException");
            m.visitLabel(start);
            m.visitTypeInsn(NEW, "java/lang/IllegalStateException"); m.visitInsn(DUP);
            m.visitMethodInsn(INVOKESPECIAL, "java/lang/IllegalStateException", "<init>", "()V", false); m.visitInsn(ATHROW);
            m.visitLabel(end);
            m.visitLabel(handler); m.visitVarInsn(ASTORE, 1);
            m.visitVarInsn(ILOAD, 0); m.visitInsn(IRETURN);
            end(m);
        });
        var result = run(input);
        var f = find(output(result, "T"), "f");
        assertEquals(0, count(f, ATHROW, NEW));
        assertTrue(f.tryCatchBlocks.isEmpty(), "the handler is plain code now");
        assertEquals(1, result.report().count("throw-gotos turned into jumps"));
        verifies(output(result, "T"));
    }

    @Test void keepsAThrowWhoseHandlerUsesTheException() {
        byte[] input = type(V1_8, w -> {
            var m = method(w, "f", "(I)Ljava/lang/Object;");
            var start = new Label(); var end = new Label(); var handler = new Label();
            m.visitTryCatchBlock(start, end, handler, "java/lang/RuntimeException");
            m.visitLabel(start);
            m.visitTypeInsn(NEW, "java/lang/IllegalStateException"); m.visitInsn(DUP);
            m.visitMethodInsn(INVOKESPECIAL, "java/lang/IllegalStateException", "<init>", "()V", false); m.visitInsn(ATHROW);
            m.visitLabel(end);
            m.visitLabel(handler); m.visitVarInsn(ASTORE, 1); m.visitVarInsn(ALOAD, 1); m.visitInsn(ARETURN);
            end(m);
        });
        var result = run(input);
        assertArrayEquals(input, output(result, "T"));
        assertTrue(result.changes().isEmpty());
    }

    @Test void removesARethrowOnlyHandler_inAMethodWithEvidence() {
        byte[] input = type(V1_8, w -> {
            w.visitField(ACC_STATIC | ACC_FINAL | ACC_PRIVATE, "K", "I", null, 1).visitEnd();
            var m = method(w, "f", "(I)I");
            var start = new Label(); var end = new Label(); var handler = new Label(); var junk = new Label();
            m.visitTryCatchBlock(start, end, handler, "java/lang/Throwable");
            m.visitLabel(start);
            m.visitFieldInsn(GETSTATIC, "T", "K", "I"); m.visitJumpInsn(IFEQ, junk);
            m.visitVarInsn(ILOAD, 0); m.visitMethodInsn(INVOKESTATIC, "java/lang/Integer", "toString", "(I)Ljava/lang/String;", false);
            m.visitInsn(POP);
            m.visitLabel(end);
            m.visitVarInsn(ILOAD, 0); m.visitInsn(IRETURN);
            m.visitLabel(junk); m.visitInsn(ICONST_M1); m.visitInsn(IRETURN);
            m.visitLabel(handler); m.visitVarInsn(ASTORE, 1); m.visitVarInsn(ALOAD, 1); m.visitInsn(ATHROW);
            end(m);
        });
        var result = run(input);
        var f = find(output(result, "T"), "f");
        assertTrue(f.tryCatchBlocks.isEmpty());
        assertEquals(0, count(f, ATHROW));
        assertEquals(1, result.report().count("rethrow-only handlers removed"));
        verifies(output(result, "T"));
    }

    @Test void leavesARethrowOnlyHandlerInCleanCode() {
        byte[] input = type(V1_8, w -> {
            var m = method(w, "f", "(I)I");
            var start = new Label(); var end = new Label(); var handler = new Label();
            m.visitTryCatchBlock(start, end, handler, "java/lang/RuntimeException");
            m.visitLabel(start);
            m.visitVarInsn(ILOAD, 0); m.visitMethodInsn(INVOKESTATIC, "java/lang/Integer", "toString", "(I)Ljava/lang/String;", false);
            m.visitInsn(POP);
            m.visitLabel(end);
            m.visitVarInsn(ILOAD, 0); m.visitInsn(IRETURN);
            m.visitLabel(handler); m.visitVarInsn(ASTORE, 1); m.visitVarInsn(ALOAD, 1); m.visitInsn(ATHROW);
            end(m);
        });
        var result = run(input);
        assertArrayEquals(input, output(result, "T"), "javac may write catch (E e) { throw e; }: no evidence, no change");
    }

    // ------------------------------------------------------------------------------------------------- flattening

    private static final int A = 0x5f3a91c2, B = 0x1d77e004, C = -0x3b02c1a9;

    /**
     * {@code s = A; loop: switch (s) { case A: x += 1; s = C; case C: x *= 3; s = B; case B: return x; default: return -1; }},
     * with random keys like an obfuscator's ({@code randomKeys}) or with the small states of a hand-written state machine.
     */
    private static byte[] flattened(boolean ambiguousStart, boolean randomKeys) {
        int a = randomKeys ? A : 0, b = randomKeys ? B : 1, c = randomKeys ? C : 2;
        return type(V1_8, w -> {
            var m = method(w, "f", "(I)I");
            var dispatch = new Label(); var c0 = new Label(); var c1 = new Label(); var c2 = new Label(); var dflt = new Label();
            var other = new Label(); var stored = new Label();
            if (ambiguousStart) {
                m.visitVarInsn(ILOAD, 0); m.visitJumpInsn(IFEQ, other); m.visitLdcInsn(a); m.visitJumpInsn(GOTO, stored);
                m.visitLabel(other); m.visitLdcInsn(b); m.visitLabel(stored);
            } else m.visitLdcInsn(a);
            m.visitVarInsn(ISTORE, 1);
            m.visitLabel(dispatch); m.visitVarInsn(ILOAD, 1);
            int[] keys = {a, b, c}; Label[] labels = {c0, c1, c2};
            Integer[] order = {0, 1, 2}; Arrays.sort(order, Comparator.comparingInt(k -> keys[k]));
            m.visitLookupSwitchInsn(dflt, new int[] {keys[order[0]], keys[order[1]], keys[order[2]]},
                new Label[] {labels[order[0]], labels[order[1]], labels[order[2]]});
            m.visitLabel(c0); m.visitIincInsn(0, 1); m.visitLdcInsn(c); m.visitVarInsn(ISTORE, 1); m.visitJumpInsn(GOTO, dispatch);
            m.visitLabel(c2); m.visitVarInsn(ILOAD, 0); m.visitInsn(ICONST_3); m.visitInsn(IMUL); m.visitVarInsn(ISTORE, 0);
            m.visitLdcInsn(b); m.visitVarInsn(ISTORE, 1); m.visitJumpInsn(GOTO, dispatch);
            m.visitLabel(c1); m.visitVarInsn(ILOAD, 0); m.visitInsn(IRETURN);
            m.visitLabel(dflt); m.visitInsn(ICONST_M1); m.visitInsn(IRETURN);
            end(m);
        });
    }
    private static long stores(MethodNode method, int var) {
        long n = 0; for (var insn : method.instructions) if (insn instanceof VarInsnNode v && v.getOpcode() == ISTORE && v.var == var) n++;
        return n;
    }

    @Test void unflattensADispatcherWhoseStateIsConstantInEveryBlock() {
        var result = run(flattened(false, true));
        var f = find(output(result, "T"), "f");
        assertEquals(0, conditionals(f), "every edge goes straight to its case; the dispatcher and the state are gone");
        assertEquals(0, stores(f, 1));
        assertEquals(0, count(f, ICONST_M1));
        assertTrue(result.report().count("dispatcher edges threaded") >= 3);
        verifies(output(result, "T"));
    }

    @Test void threadsOnlyTheEdgesWithAConstantState() {
        var result = run(flattened(true, true));
        var f = find(output(result, "T"), "f");
        assertEquals(1, count(f, LOOKUPSWITCH, TABLESWITCH), "the entry edge has a state of A or B: the dispatcher stays for it");
        assertTrue(result.report().count("dispatcher edges threaded") >= 2);
        assertTrue(result.report().count("dispatcher edges left (state not constant)") >= 1);
        verifies(output(result, "T"));
    }

    @Test void leavesAHandWrittenStateMachineAlone() {
        byte[] input = flattened(false, false);
        var result = run(input);
        assertArrayEquals(input, output(result, "T"), "small literal states are what javac writes for a state machine: no evidence");
    }

    // ------------------------------------------------------------------------------------------------- refusals and clean code

    @Test void refusesSubroutinesAndLeavesTheMethodAsItWas() {
        byte[] input = type(V1_5, w -> {
            var m = method(w, "f", "(I)I");
            var sub = new Label(); var junk = new Label();
            m.visitInsn(ICONST_0); m.visitJumpInsn(IFNE, junk);
            m.visitJumpInsn(JSR, sub);
            m.visitVarInsn(ILOAD, 0); m.visitInsn(IRETURN);
            m.visitLabel(sub); m.visitVarInsn(ASTORE, 1); m.visitVarInsn(RET, 1);
            m.visitLabel(junk); m.visitInsn(ICONST_M1); m.visitInsn(IRETURN);
            end(m);
        });
        var result = run(input);
        assertArrayEquals(input, output(result, "T"));
        assertEquals(1, result.report().count("methods refused"));
        assertTrue(result.report().refused().getFirst().contains("jsr"));
    }

    @Test void leavesJdkClassesUnchanged() throws Exception {
        var classes = new ArrayList<byte[]>();
        for (String name : List.of("java/util/ArrayList", "java/util/HashMap", "java/lang/Integer", "java/util/regex/Pattern",
                "java/util/concurrent/ConcurrentHashMap", "java/math/BigInteger", "java/lang/StringLatin1", "java/util/Formatter")) {
            try (InputStream in = ClassLoader.getPlatformClassLoader().getResourceAsStream(name + ".class")) {
                classes.add(Objects.requireNonNull(in, name).readAllBytes());
            }
        }
        var result = run(classes.toArray(byte[][]::new));
        assertEquals(List.of(), result.changes().stream().map(c -> c.className() + "." + c.method() + c.descriptor()).toList(),
            "javac output has no opaque predicate, flattening or dead code: " + result.report());
    }
}
