package dev.classrevive.transforms.skid;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.tree.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.ZipFile;
import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/**
 * Phase 2CN/2CO: the reversal over a real Skidfuscator 2.1.0 output (benchmarks/obfuscated/skidfuscator, our own fixture
 * source obfuscated with the default configuration) and over synthetic classes. Classes are defined and linked in an
 * isolated loader (the JVM verifier runs); none is initialized or executed.
 */
class SkidfuscatorReverserTest {
    static final Path FIXTURES = Path.of(System.getProperty("classrevive.skidfuscatorFixtures", "missing"));

    static Map<String,byte[]> entries(Path jar) throws Exception {
        var result = new LinkedHashMap<String,byte[]>();
        try (var zip = new ZipFile(jar.toFile())) {
            for (var entry : Collections.list(zip.entries())) result.put(entry.getName(), zip.getInputStream(entry).readAllBytes());
        }
        return result;
    }
    static ClassNode node(byte[] bytes) { var node = new ClassNode(); new ClassReader(bytes).accept(node, ClassReader.SKIP_FRAMES); return node; }
    static Set<String> literals(ClassNode node) {
        var result = new TreeSet<String>();
        for (var method : node.methods) for (var insn : method.instructions) if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof String s) result.add(s);
        return result;
    }
    /** Defines every class in one isolated loader and links it (JVM verification); nothing is initialized. */
    static void link(Map<String,byte[]> entries) throws Exception {
        var bytes = new HashMap<String,byte[]>();
        entries.forEach((name, value) -> { if (name.endsWith(".class")) bytes.put(name.substring(0, name.length() - 6).replace('/', '.'), value); });
        var loader = new ClassLoader(null) {
            @Override protected Class<?> findClass(String name) throws ClassNotFoundException {
                byte[] value = bytes.get(name);
                if (value == null) return ClassLoader.getPlatformClassLoader().loadClass(name);
                return defineClass(name, value, 0, value.length);
            }
        };
        for (String name : bytes.keySet()) {
            var type = Class.forName(name, false, loader);
            assertDoesNotThrow(type::getDeclaredMethods, name);
        }
    }

    @Test void theFixtureIsRecoveredVerifiedAndCleaned() throws Exception {
        var obfuscated = entries(FIXTURES.resolve("skid-fixture-obf.jar"));
        var reference = entries(FIXTURES.resolve("skid-fixture.jar"));
        var result = new SkidfuscatorReverser().apply(obfuscated, 10_000);
        var report = result.report();
        assertTrue(report.detected());
        assertEquals(List.of(), report.refused());
        assertEquals(0, report.count("class write failures"));
        assertEquals(0, report.count("strings not decrypted"));
        assertTrue(report.count("strings decrypted") >= 30, report.toString());
        link(result.entries());
        // The hash factory is gone; only the SDK stays, for the one literal the bounded search cannot find ("Administrator").
        assertEquals(1, report.count("hash factories removed"));
        assertEquals(3, report.count("type checks recovered"));
        assertEquals(4, report.count("equals literals recovered"));
        assertEquals(4, report.count("hashed switches recovered"));
        assertEquals(12, report.count("injected parameters removed"));

        for (var entry : reference.entrySet()) {
            if (!entry.getKey().endsWith(".class")) continue;
            var expected = node(entry.getValue());
            var actual = node(result.entries().get(entry.getKey()));
            // Fields: the seed fields, key fields and the ASCII-art field are gone; the program's fields are untouched.
            assertEquals(expected.fields.stream().map(f -> f.name + f.desc).sorted().toList(),
                actual.fields.stream().map(f -> f.name + f.desc).sorted().toList(), expected.name);
            // Methods: the injected seed parameters are gone, so every signature is the reference's again.
            assertEquals(expected.methods.stream().map(m -> m.name + m.desc).sorted().toList(),
                actual.methods.stream().map(m -> m.name + m.desc).sorted().toList(), expected.name);
            // No decryptor, generator, XOR helper or seed switch is left.
            for (var method : actual.methods) {
                assertFalse(method.desc.endsWith("[B") && method.desc.startsWith("()"), expected.name + "." + method.name);
                assertFalse(StringRecovery.recognize(actual, method) != null, expected.name + "." + method.name);
                assertFalse(SkidfuscatorReverser.xorHelper(method), expected.name + "." + method.name);
                for (var insn : method.instructions) {
                    assertFalse(insn instanceof FieldInsnNode f && f.name.startsWith("nothing_to_see_here"));
                    assertFalse(insn instanceof MethodInsnNode call && call.owner.equals("java/lang/Integer") && call.name.equals("parseInt"),
                        expected.name + "." + method.name + " keeps a constructor seed");
                }
            }
            // Every literal of the reference is back, except those Skidfuscator replaced by a hash (equals against a constant).
            var missing = new TreeSet<>(literals(expected));
            missing.removeAll(literals(actual));
            missing.removeAll(Set.of("root", "Administrator", "start", "stop", "pause"));
            assertEquals(Set.of(), missing, expected.name);
        }
    }

    @Test void theProgramsOwnConstantsAndBranchesStay() throws Exception {
        // A seed local with Skidfuscator's updates, beside the program's own constant local and branch on it.
        var writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(V1_8, ACC_PUBLIC | ACC_SUPER, "t/Prog", null, "java/lang/Object", null);
        var m = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "f", "()I", null, null);
        m.visitCode();
        m.visitLdcInsn(1000); m.visitLdcInsn(7); m.visitInsn(IXOR); m.visitVarInsn(ISTORE, 1); // seed = 1000 ^ 7 (unfolded: obfuscator)
        for (int k = 0; k < 3; k++) { m.visitLdcInsn(11 + k); m.visitVarInsn(ILOAD, 1); m.visitInsn(IXOR); m.visitVarInsn(ISTORE, 1); }
        m.visitInsn(ICONST_5); m.visitVarInsn(ISTORE, 0); // int x = 5 (the program's)
        var other = new Label();
        m.visitVarInsn(ILOAD, 0); m.visitInsn(ICONST_5); m.visitJumpInsn(IF_ICMPNE, other); // if (x == 5)
        m.visitLdcInsn(1000 ^ 7 ^ 11 ^ 12 ^ 13 ^ 42); m.visitVarInsn(ILOAD, 1); m.visitInsn(IXOR); m.visitInsn(IRETURN); // return 42 (encrypted)
        m.visitLabel(other);
        m.visitVarInsn(ILOAD, 0); m.visitInsn(IRETURN); // return x (x is read twice: a real variable, not a spill)
        m.visitMaxs(0, 0); m.visitEnd();
        writer.visitEnd();
        var result = new SkidfuscatorReverser().apply(Map.of("t/Prog.class", writer.toByteArray()), 100);
        var method = node(result.entries().get("t/Prog.class")).methods.stream().filter(x -> x.name.equals("f")).findFirst().orElseThrow();
        var ops = new ArrayList<String>();
        for (var insn : method.instructions) {
            if (insn.getOpcode() < 0) continue;
            ops.add(insn instanceof IntInsnNode push ? "push " + push.operand : insn instanceof JumpInsnNode ? "jump " + insn.getOpcode()
                : insn instanceof VarInsnNode var ? insn.getOpcode() + "@" + var.var : String.valueOf(insn.getOpcode()));
        }
        // The program's x = 5 and its branch stay; the encrypted 42 is folded; the seed local is gone.
        assertEquals(List.of(String.valueOf(ICONST_5), ISTORE + "@0", ILOAD + "@0", String.valueOf(ICONST_5), "jump " + IF_ICMPNE,
            "push 42", String.valueOf(IRETURN), ILOAD + "@0", String.valueOf(IRETURN)), ops);
        link(result.entries());
    }

    @Test void aJarWithoutSkidfuscatorIsReturnedAsItIs() {
        var writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        writer.visit(V1_8, ACC_PUBLIC | ACC_SUPER, "t/Plain", null, "java/lang/Object", null);
        var m = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "f", "(I)I", null, null);
        m.visitCode(); m.visitVarInsn(ILOAD, 0); m.visitLdcInsn(77); m.visitInsn(IXOR); m.visitInsn(IRETURN); m.visitMaxs(0, 0); m.visitEnd();
        writer.visitEnd();
        byte[] bytes = writer.toByteArray();
        var result = new SkidfuscatorReverser().apply(Map.of("t/Plain.class", bytes), 100);
        assertFalse(result.report().detected());
        assertArrayEquals(bytes, result.entries().get("t/Plain.class"));
    }

    @Test void theBitwiseAndLegacyHashesEvaluateStatically() {
        var writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        writer.visit(V1_8, ACC_PUBLIC | ACC_SUPER, "h/Factory", null, "java/lang/Object", null);
        var legacy = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "legacy", "(I)I", null, null);
        legacy.visitCode();
        var zero = new Label();
        legacy.visitVarInsn(ILOAD, 0); legacy.visitJumpInsn(IFEQ, zero);
        legacy.visitVarInsn(ILOAD, 0); legacy.visitIntInsn(BIPUSH, 31); legacy.visitInsn(IMUL); legacy.visitInsn(ICONST_4); legacy.visitInsn(IUSHR);
        legacy.visitVarInsn(ILOAD, 0); legacy.visitInsn(IREM); legacy.visitVarInsn(ILOAD, 0); legacy.visitIntInsn(BIPUSH, 16); legacy.visitInsn(IUSHR);
        legacy.visitInsn(IXOR); legacy.visitInsn(IRETURN);
        legacy.visitLabel(zero); legacy.visitInsn(ICONST_0); legacy.visitInsn(IRETURN);
        legacy.visitMaxs(0, 0); legacy.visitEnd();
        writer.visitEnd();
        var nodes = Map.of("h/Factory", node(writer.toByteArray()));
        var pure = new PureEvaluator(nodes);
        for (int s : new int[] {1, -7, 123456789, Integer.MIN_VALUE, 0}) {
            int expected = s == 0 ? 0 : (((s * 31) >>> 4) % s) ^ (s >>> 16);
            assertEquals(expected, pure.evaluate("h/Factory", "legacy", "(I)I", new Object[] {s}).orElseThrow());
        }
        assertTrue(pure.pureBody("h/Factory", "legacy", "(I)I"));
        assertEquals(1197013600, pure.evaluate("java/lang/Math", "max", "(II)I", new Object[] {5, 1197013600}).orElseThrow());
        assertEquals((int) 'a', pure.evaluate("java/lang/Character", "toLowerCase", "(I)I", new Object[] {(int) 'A'}).orElseThrow());
    }

    /** Phase 2DC: a method with many locals and blocks whose frames take more than ASM's default 100 million operations. */
    static void wideMethod(ClassWriter writer, int locals, int blocks) {
        var m = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "f", "(I)I", null, null);
        m.visitCode();
        for (int i = 1; i <= locals; i++) { m.visitInsn(ICONST_0); m.visitVarInsn(ISTORE, i); }
        var labels = new Label[blocks];
        for (int i = 0; i < blocks; i++) labels[i] = new Label();
        for (int i = 0; i < blocks; i++) { m.visitLabel(labels[i]); m.visitVarInsn(ILOAD, 0); m.visitJumpInsn(IFEQ, labels[(i * 7 + 3) % blocks]); m.visitIincInsn(0, -1); }
        m.visitInsn(ICONST_0); m.visitInsn(IRETURN); m.visitMaxs(0, 0); m.visitEnd();
    }

    @Test void aLargeRebuiltMethodGetsItsFramesWithinTheRaisedBudget() {
        var plain = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        plain.visit(V1_8, ACC_PUBLIC | ACC_SUPER, "l/Wide", null, "java/lang/Object", null);
        assertThrows(org.objectweb.asm.LimitExceededException.class, () -> { wideMethod(plain, 400, 2500); plain.visitEnd(); plain.toByteArray(); });
        var writer = new Hierarchy(Map.of()).writer(ClassWriter.COMPUTE_FRAMES);
        writer.visit(V1_8, ACC_PUBLIC | ACC_SUPER, "l/Wide", null, "java/lang/Object", null);
        wideMethod(writer, 400, 2500); writer.visitEnd();
        assertTrue(writer.toByteArray().length > 0);
    }

    @Test void aClassThatCannotBeWrittenIsReportedWithItsReason() {
        assertEquals("a/B: frame computation over its budget (Too many operations)",
            Hierarchy.failure("a/B", new org.objectweb.asm.LimitExceededException("Too many operations")));
        assertEquals("a/B: a method grew past 64 KiB of code",
            Hierarchy.failure("a/B", new org.objectweb.asm.MethodTooLargeException("a/B", "f", "()V", 70000)));
        assertEquals("a/B: IllegalStateException: odd", Hierarchy.failure("a/B", new IllegalStateException("odd")));
        var report = new SkidfuscatorReverser.Report(true, Map.of("class write failures", 1), List.of(), List.of(), List.of("a/B: why"));
        assertTrue(report.toString().contains("classes kept as they were (write failed): [a/B: why]"), report.toString());
    }

    @Test void abstractAndNativeMethodsAreNoRefusals() {
        var model = new SkidModel(new HashMap<>());
        var rewriter = new MethodRewriter(model.interpreter(), (call, args) -> false);
        var stats = new MethodRewriter.Stats();
        for (int access : new int[] {ACC_PUBLIC | ACC_ABSTRACT, ACC_PUBLIC | ACC_NATIVE})
            assertFalse(rewriter.rewrite("t/Base", new MethodNode(access, "run", "(I)V", null, null), Map.of(), stats));
        assertEquals(0, stats.unanalyzable);
        assertEquals(List.of(), stats.refused);
    }
}
