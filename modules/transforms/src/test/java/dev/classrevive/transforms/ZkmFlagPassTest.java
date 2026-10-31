package dev.classrevive.transforms;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.*;
import java.util.*;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/** Phase 2CM: ZKM-shaped flag holders built with ASM, as in a ZKM-obfuscated input. Classes are only verified, never run. */
class ZkmFlagPassTest {
    @Test void registersAsAnOptInPassThatNeedsTheWholeProgram() {
        assertEquals("zkm-flags-v1", BuiltinPasses.select("zkm-flags").getFirst().id());
        assertTrue(((ZkmFlagPass) BuiltinPasses.select("zkm-flags").getFirst()).flags().isEmpty());
    }

    @Test void aFlagTheInitializerSetsToAFreshArrayIsNeverNull() throws Exception {
        // g6: static { Q(new String[3]); }  t() = getstatic k  Q(a) = putstatic k
        var holder = holder("z/H", "[Ljava/lang/String;", clinit -> {
            clinit.visitInsn(ICONST_3); clinit.visitTypeInsn(ANEWARRAY, "java/lang/String");
            clinit.visitMethodInsn(INVOKESTATIC, "z/H", "Q", "([Ljava/lang/String;)V", false);
        });
        var user = user("z/U", "z/H", "t", "()[Ljava/lang/String;", IFNULL);
        var pass = ZkmFlagPass.plan(List.of(holder, user));
        assertEquals(1, pass.flags().size(), pass.rejected().toString());
        assertEquals(1, pass.flags().iterator().next().value());
        var result = pass.apply(user, 100);
        assertEquals(1, result.changes().size());
        assertEquals(0, result.changes().getFirst().result()); // ifnull never taken
        var method = method(result.bytes(), "m");
        assertFalse(has(method, IFNULL) || has(method, IFNONNULL));
        assertFalse(calls(method, "t"), "the read is dropped once nothing tests it");
        assertEquals(List.of(1), returnedConstants(method));
        JvmVerification.assertVerifiesIfChanged(user, result.bytes());
    }

    @Test void aFlagTheInitializerOnlyStoresBehindItsOwnTestStaysNull() throws Exception {
        // I: static { if (h() != null) b(new int[2]); }  -- never stores, so y stays null.
        var holder = holder("z/H", "[I", clinit -> {
            var skip = new Label();
            clinit.visitMethodInsn(INVOKESTATIC, "z/H", "t", "()[I", false);
            clinit.visitJumpInsn(IFNULL, skip);
            clinit.visitInsn(ICONST_2); clinit.visitIntInsn(NEWARRAY, T_INT);
            clinit.visitMethodInsn(INVOKESTATIC, "z/H", "Q", "([I)V", false);
            clinit.visitLabel(skip);
        });
        var user = user("z/U", "z/H", "t", "()[I", IFNONNULL);
        var pass = ZkmFlagPass.plan(List.of(holder, user));
        assertEquals(0, pass.flags().iterator().next().value(), pass.rejected().toString());
        var result = pass.apply(user, 100);
        assertEquals(0, result.changes().getFirst().result()); // ifnonnull never taken
        assertEquals(List.of(1), returnedConstants(method(result.bytes(), "m")));
        JvmVerification.assertVerifiesIfChanged(user, result.bytes());
    }

    @Test void aBooleanFlagSetThroughItsWrapperDuringInitializationIsProvedButTheWrapperIsNeverFolded() throws Exception {
        // G: static { if (s()) K(true); }  s() = !B()  B() = getstatic t  K(z) = putstatic t   => t is true afterwards
        // (here the accessor is t(), the setter Q and the field k)
        var holder = holder("z/H", "Z", clinit -> {
            var skip = new Label();
            clinit.visitMethodInsn(INVOKESTATIC, "z/H", "s", "()Z", false);
            clinit.visitJumpInsn(IFEQ, skip);
            clinit.visitInsn(ICONST_1);
            clinit.visitMethodInsn(INVOKESTATIC, "z/H", "Q", "(Z)V", false);
            clinit.visitLabel(skip);
        }, writer -> {
            var s = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "s", "()Z", null, null);
            s.visitCode(); var no = new Label();
            s.visitMethodInsn(INVOKESTATIC, "z/H", "t", "()Z", false);
            s.visitJumpInsn(IFNE, no); s.visitInsn(ICONST_1); s.visitInsn(IRETURN);
            s.visitLabel(no); s.visitInsn(ICONST_0); s.visitInsn(IRETURN);
            s.visitMaxs(0, 0); s.visitEnd();
        });
        var direct = user("z/U", "z/H", "t", "()Z", IFEQ);
        var wrapped = user("z/W", "z/H", "s", "()Z", IFEQ);
        var pass = ZkmFlagPass.plan(List.of(holder, direct, wrapped));
        var values = new TreeMap<String,Integer>();
        pass.flags().forEach(flag -> values.put(flag.key(), flag.value()));
        assertEquals(Map.of("z/H.k:Z", 1), values, pass.rejected().toString());
        // B() is true: `ifeq` falls through. s() computes a value from the flag: its callers are not folded.
        assertEquals(List.of(1), returnedConstants(method(pass.apply(direct, 100).bytes(), "m")));
        assertTrue(pass.apply(wrapped, 100).changes().isEmpty());
        // The initializer runs before the store and calls s(); s() is a wrapper: neither is folded.
        var folded = pass.apply(holder, 100);
        assertTrue(has(method(folded.bytes(), "<clinit>"), IFEQ));
        assertTrue(has(method(folded.bytes(), "s"), IFNE));
    }

    @Test void aMethodTheInitializerCallsBeforeTheStoreIsNotFoldedForThatFlag() throws Exception {
        // static { early(); Q(new String[1]); }  early() tests t() and sees null; later readers see the array.
        var holder = holder("z/H", "[Ljava/lang/String;", clinit -> {
            clinit.visitMethodInsn(INVOKESTATIC, "z/E", "m", "()I", false); clinit.visitInsn(POP);
            clinit.visitInsn(ICONST_1); clinit.visitTypeInsn(ANEWARRAY, "java/lang/String");
            clinit.visitMethodInsn(INVOKESTATIC, "z/H", "Q", "([Ljava/lang/String;)V", false);
        });
        var early = user("z/E", "z/H", "t", "()[Ljava/lang/String;", IFNULL);
        var late = user("z/U", "z/H", "t", "()[Ljava/lang/String;", IFNULL);
        var pass = ZkmFlagPass.plan(List.of(holder, early, late));
        assertEquals(1, pass.flags().iterator().next().value(), pass.rejected().toString());
        assertTrue(pass.apply(early, 100).changes().isEmpty());
        assertEquals(1, pass.apply(late, 100).changes().size());
    }

    @Test void aFlagWhoseInitializerCallsNativeCodeIsNotProved() {
        // ZKM with a native layer: static { Loader.registerNativesForClass(...); 0a2b...(); Q(new String[3]); } -- the native layer could set k.
        var holder = holder("z/H", "[Ljava/lang/String;", clinit -> {
            clinit.visitMethodInsn(INVOKESTATIC, "z/H", "n0a2b", "()V", false);
            clinit.visitInsn(ICONST_3); clinit.visitTypeInsn(ANEWARRAY, "java/lang/String");
            clinit.visitMethodInsn(INVOKESTATIC, "z/H", "Q", "([Ljava/lang/String;)V", false);
        }, writer -> writer.visitMethod(ACC_PRIVATE | ACC_STATIC | ACC_NATIVE, "n0a2b", "()V", null, null).visitEnd());
        var user = user("z/U", "z/H", "t", "()[Ljava/lang/String;", IFNULL);
        var pass = ZkmFlagPass.plan(List.of(holder, user));
        assertTrue(pass.flags().isEmpty());
        assertEquals("its <clinit> calls native code (n0a2b), which could set it", pass.rejected().get("z/H.k:[Ljava/lang/String;"));
        assertTrue(pass.apply(user, 100).changes().isEmpty());
    }

    @Test void aFlagAReachableStoreChangesIsNotProved() {
        // C.<init>-like: if (t() == null) Q(new String[2]);  -- the flag starts null and flips once that code runs.
        var holder = holder("z/H", "[Ljava/lang/String;", clinit -> { });
        var flipper = user("z/F", "z/H", "t", "()[Ljava/lang/String;", IFNONNULL, body -> {
            body.visitInsn(ICONST_2); body.visitTypeInsn(ANEWARRAY, "java/lang/String");
            body.visitMethodInsn(INVOKESTATIC, "z/H", "Q", "([Ljava/lang/String;)V", false);
        });
        var pass = ZkmFlagPass.plan(List.of(holder, flipper));
        assertTrue(pass.flags().isEmpty());
        assertTrue(pass.rejected().get("z/H.k:[Ljava/lang/String;").startsWith("a reachable store of another value in z/F.m()I"),
            pass.rejected().toString());
        assertTrue(pass.apply(flipper, 100).changes().isEmpty());
    }

    @Test void anAccessorWhoseValueIsUsedAsDataIsNoFlag() {
        // A singleton-like getter: its result is dereferenced (arraylength), so it is program state, not a flag.
        var holder = holder("z/H", "[Ljava/lang/String;", clinit -> {
            clinit.visitInsn(ICONST_3); clinit.visitTypeInsn(ANEWARRAY, "java/lang/String");
            clinit.visitMethodInsn(INVOKESTATIC, "z/H", "Q", "([Ljava/lang/String;)V", false);
        });
        var writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(V1_8, ACC_PUBLIC, "z/D", null, "java/lang/Object", null);
        var m = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "m", "()I", null, null);
        m.visitCode();
        m.visitMethodInsn(INVOKESTATIC, "z/H", "t", "()[Ljava/lang/String;", false);
        m.visitInsn(ARRAYLENGTH); m.visitInsn(IRETURN);
        m.visitMaxs(0, 0); m.visitEnd(); writer.visitEnd();
        var pass = ZkmFlagPass.plan(List.of(holder, writer.toByteArray()));
        assertTrue(pass.flags().isEmpty());
        assertEquals("used as data in z/D.m()I", pass.rejected().get("z/H.k:[Ljava/lang/String;"));
    }

    // ---------------------------------------------------------------------------------------------------------------

    /** A ZKM flag holder: static field k, accessor t() (getstatic; return), setter Q (load 0; putstatic; return). */
    @SafeVarargs
    private static byte[] holder(String name, String desc, Consumer<MethodVisitor> clinit, Consumer<ClassWriter>... extra) {
        var writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(V1_8, ACC_PUBLIC, name, null, "java/lang/Object", null);
        writer.visitField(ACC_PRIVATE | ACC_STATIC, "k", desc, null, null).visitEnd();
        String field = "k";
        var type = Type.getType(desc);
        var get = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "t", "()" + desc, null, null);
        get.visitCode(); get.visitFieldInsn(GETSTATIC, name, field, desc); get.visitInsn(type.getOpcode(IRETURN)); get.visitMaxs(0, 0); get.visitEnd();
        var set = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "Q", "(" + desc + ")V", null, null);
        set.visitCode(); set.visitVarInsn(type.getOpcode(ILOAD), 0); set.visitFieldInsn(PUTSTATIC, name, field, desc); set.visitInsn(RETURN);
        set.visitMaxs(0, 0); set.visitEnd();
        var init = writer.visitMethod(ACC_STATIC, "<clinit>", "()V", null, null);
        init.visitCode(); clinit.accept(init); init.visitInsn(RETURN); init.visitMaxs(0, 0); init.visitEnd();
        for (var more : extra) more.accept(writer);
        writer.visitEnd();
        return writer.toByteArray();
    }
    /** m()I: v = owner.accessor(); store and reload it, then `test` jumps to `return 2`, else `return 1`. */
    private static byte[] user(String name, String owner, String accessor, String desc, int test) { return user(name, owner, accessor, desc, test, body -> { }); }
    private static byte[] user(String name, String owner, String accessor, String desc, int test, Consumer<MethodVisitor> fallThrough) {
        var writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(V1_8, ACC_PUBLIC, name, null, "java/lang/Object", null);
        var m = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "m", "()I", null, null);
        m.visitCode(); var jump = new Label();
        var type = Type.getReturnType(desc);
        m.visitMethodInsn(INVOKESTATIC, owner, accessor, desc, false);
        m.visitVarInsn(type.getOpcode(ISTORE), 0); m.visitVarInsn(type.getOpcode(ILOAD), 0);
        m.visitJumpInsn(test, jump);
        fallThrough.accept(m);
        m.visitInsn(ICONST_1); m.visitInsn(IRETURN);
        m.visitLabel(jump); m.visitInsn(ICONST_2); m.visitInsn(IRETURN);
        m.visitMaxs(0, 0); m.visitEnd(); writer.visitEnd();
        return writer.toByteArray();
    }
    private static MethodNode method(byte[] bytes, String name) {
        var node = new ClassNode(); new ClassReader(bytes).accept(node, 0);
        return node.methods.stream().filter(method -> method.name.equals(name)).findFirst().orElseThrow();
    }
    private static boolean has(MethodNode method, int opcode) {
        for (var insn : method.instructions) if (insn.getOpcode() == opcode) return true;
        return false;
    }
    private static boolean calls(MethodNode method, String name) {
        for (var insn : method.instructions) if (insn instanceof MethodInsnNode call && call.name.equals(name)) return true;
        return false;
    }
    /** The constants pushed right before each return still in the method. */
    private static List<Integer> returnedConstants(MethodNode method) throws AnalyzerException {
        new Analyzer<>(new BasicVerifier()).analyze("x", method);
        var result = new ArrayList<Integer>();
        for (var insn : method.instructions) if (insn.getOpcode() == IRETURN) {
            var previous = insn.getPrevious();
            while (previous.getOpcode() < 0) previous = previous.getPrevious();
            result.add(previous.getOpcode() - ICONST_0);
        }
        return result;
    }
}
