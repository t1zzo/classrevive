package dev.classrevive.transforms.skid;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.tree.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;
import javax.tools.ToolProvider;
import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/**
 * Phase 2DM: the Zelix KlassMaster 27 reversal over a synthetic jar written for ClassRevive in its shapes (the obfuscator
 * is commercial, so none of its output is committed): a reflection class with {@code (long, long)} getters and member
 * finders, a keyed string decryptor with its table, a method with its parameters and key packed in an {@code Object[]},
 * a caller that builds the array by stack shuffling, and an opaque flag. Classes are linked by the JVM verifier in an
 * isolated loader; none is initialized or executed.
 */
class ZkmReverserTest {
    @TempDir Path dir;

    static final long KEY = 5, GET_KEY = 0x1234;
    static final String REFLECTION = """
        package z;
        import java.lang.reflect.*;
        public class R {
            private static final String[] NAMES = {"value", "compute"};
            public static Field c(long id, long key) throws Exception {
                return find(Class.forName("z.App"), NAMES[(int) (id ^ key)], Integer.TYPE);
            }
            public static Method d(long id, long key) throws Exception {
                return find(Class.forName("z.App"), NAMES[(int) (id ^ key)], Integer.TYPE, 0, new Class[0]);
            }
            private static Field find(Class<?> owner, String name, Class<?> type) { return null; }
            private static Method find(Class<?> owner, String name, Class<?> type, int modifiers, Class<?>[] parameters) { return null; }
            public static Object[] b() { return new Object[0]; }
        }
        """;

    private static void method(ClassWriter writer, int access, String name, String desc, Consumer<MethodVisitor> body) {
        var visitor = writer.visitMethod(access, name, desc, null, null);
        visitor.visitCode();
        body.accept(visitor);
        visitor.visitMaxs(0, 0);
        visitor.visitEnd();
    }

    private static void getter(MethodVisitor m, String name, long id, int keyLocal) {
        m.visitLdcInsn(id);
        m.visitVarInsn(LLOAD, keyLocal);
        m.visitMethodInsn(INVOKESTATIC, "z/R", name, name.equals("c") ? "(JJ)Ljava/lang/reflect/Field;" : "(JJ)Ljava/lang/reflect/Method;", false);
    }

    /** {@code long key = ((Long) args[index]).longValue();} into local {@code local}, with the array copied as Zelix does. */
    private static void readKey(MethodVisitor m, int index, int local) {
        m.visitVarInsn(ALOAD, 1);
        m.visitInsn(DUP);
        m.visitIntInsn(BIPUSH, index);
        m.visitInsn(AALOAD);
        m.visitTypeInsn(CHECKCAST, "java/lang/Long");
        m.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Long", "longValue", "()J", false);
        m.visitVarInsn(LSTORE, local);
        m.visitInsn(POP);
    }

    private static byte[] app(Consumer<ClassWriter> extra) {
        var w = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        w.visit(V1_8, ACC_PUBLIC, "z/App", null, "java/lang/Object", null);
        w.visitField(ACC_PUBLIC, "value", "I", null, null);
        w.visitField(ACC_PUBLIC | ACC_STATIC, "flag", "Z", null, null);
        w.visitField(ACC_PRIVATE | ACC_STATIC, "T", "[Ljava/lang/String;", null, null);
        w.visitField(ACC_PRIVATE | ACC_STATIC | ACC_FINAL, "NAME", "Ljava/lang/String;", null, null);
        method(w, ACC_STATIC, "<clinit>", "()V", m -> {
            m.visitInsn(ICONST_1); m.visitTypeInsn(ANEWARRAY, "java/lang/String");
            m.visitInsn(DUP); m.visitInsn(ICONST_0); m.visitLdcInsn("hello"); m.visitInsn(AASTORE);
            m.visitFieldInsn(PUTSTATIC, "z/App", "T", "[Ljava/lang/String;");
            m.visitLdcInsn("constant"); m.visitFieldInsn(PUTSTATIC, "z/App", "NAME", "Ljava/lang/String;");
            m.visitInsn(RETURN);
        });
        method(w, ACC_PUBLIC, "<init>", "()V", m -> {
            m.visitVarInsn(ALOAD, 0); m.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false); m.visitInsn(RETURN);
        });
        method(w, ACC_PRIVATE | ACC_STATIC, "a", "(III)Ljava/lang/String;", m -> {
            m.visitFieldInsn(GETSTATIC, "z/App", "T", "[Ljava/lang/String;");
            m.visitVarInsn(ILOAD, 0); m.visitVarInsn(ILOAD, 1); m.visitInsn(IXOR); m.visitVarInsn(ILOAD, 2); m.visitInsn(IXOR);
            m.visitInsn(AALOAD); m.visitInsn(ARETURN);
        });
        method(w, ACC_PUBLIC | ACC_STATIC, "text", "()Ljava/lang/String;", m -> {
            m.visitLdcInsn(KEY); m.visitVarInsn(LSTORE, 0);
            m.visitVarInsn(LLOAD, 0); m.visitInsn(L2I); m.visitInsn(ICONST_5); m.visitInsn(ICONST_0);
            m.visitMethodInsn(INVOKESTATIC, "z/App", "a", "(III)Ljava/lang/String;", false);
            m.visitInsn(ARETURN);
        });
        method(w, ACC_PUBLIC | ACC_STATIC, "constant", "()Ljava/lang/String;", m -> {
            m.visitFieldInsn(GETSTATIC, "z/App", "NAME", "Ljava/lang/String;"); m.visitInsn(ARETURN);
        });
        method(w, ACC_PUBLIC, "compute", "()I", m -> { m.visitIntInsn(BIPUSH, 42); m.visitInsn(IRETURN); });
        // get(Object[] {key, int offset}): this.value + offset, the field read through the reflection class.
        method(w, ACC_PUBLIC, "get", "([Ljava/lang/Object;)I", m -> {
            readKey(m, 0, 2);
            m.visitVarInsn(ALOAD, 0);
            getter(m, "c", GET_KEY, 2); // id ^ key == 0: "value"
            m.visitInsn(SWAP);
            m.visitMethodInsn(INVOKEVIRTUAL, "java/lang/reflect/Field", "getInt", "(Ljava/lang/Object;)I", false);
            m.visitVarInsn(ALOAD, 1); m.visitInsn(ICONST_1); m.visitInsn(AALOAD);
            m.visitTypeInsn(CHECKCAST, "java/lang/Integer");
            m.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Integer", "intValue", "()I", false);
            m.visitInsn(IADD); m.visitInsn(IRETURN);
        });
        // Values first, then the array moved under them and filled: Zelix's calling shape.
        method(w, ACC_PUBLIC, "run", "()I", m -> {
            m.visitLdcInsn(GET_KEY); m.visitVarInsn(LSTORE, 1);
            m.visitVarInsn(ALOAD, 0);
            m.visitIntInsn(BIPUSH, 9);
            m.visitVarInsn(LLOAD, 1);
            m.visitInsn(ICONST_2); m.visitTypeInsn(ANEWARRAY, "java/lang/Object");
            m.visitInsn(DUP_X2); m.visitInsn(DUP_X2); m.visitInsn(POP);
            m.visitMethodInsn(INVOKESTATIC, "java/lang/Long", "valueOf", "(J)Ljava/lang/Long;", false);
            m.visitInsn(ICONST_0); m.visitInsn(SWAP); m.visitInsn(AASTORE);
            m.visitInsn(DUP_X1); m.visitInsn(SWAP);
            m.visitMethodInsn(INVOKESTATIC, "java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;", false);
            m.visitInsn(ICONST_1); m.visitInsn(SWAP); m.visitInsn(AASTORE);
            m.visitMethodInsn(INVOKEVIRTUAL, "z/App", "get", "([Ljava/lang/Object;)I", false);
            m.visitInsn(IRETURN);
        });
        // this.compute() through Method.invoke, the result unboxed.
        method(w, ACC_PUBLIC, "viaInvoke", "()I", m -> {
            m.visitLdcInsn(KEY); m.visitVarInsn(LSTORE, 1);
            m.visitVarInsn(ALOAD, 0);
            m.visitMethodInsn(INVOKESTATIC, "z/R", "b", "()[Ljava/lang/Object;", false);
            getter(m, "d", KEY ^ 1, 1); // id ^ key == 1: "compute"
            m.visitInsn(DUP_X2); m.visitInsn(POP);
            m.visitMethodInsn(INVOKEVIRTUAL, "java/lang/reflect/Method", "invoke", "(Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;", false);
            m.visitTypeInsn(CHECKCAST, "java/lang/Integer");
            m.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Integer", "intValue", "()I", false);
            m.visitInsn(IRETURN);
        });
        // The opaque flag: only written where it is already true.
        method(w, ACC_PUBLIC | ACC_STATIC, "flagged", "()I", m -> {
            var end = new Label();
            m.visitFieldInsn(GETSTATIC, "z/App", "flag", "Z");
            m.visitJumpInsn(IFEQ, end);
            m.visitInsn(ICONST_1); m.visitFieldInsn(PUTSTATIC, "z/App", "flag", "Z");
            m.visitLabel(end);
            m.visitIntInsn(BIPUSH, 7); m.visitInsn(IRETURN);
        });
        extra.accept(w);
        w.visitEnd();
        return w.toByteArray();
    }

    private Map<String,byte[]> jar(Consumer<ClassWriter> extra) throws Exception {
        var source = Files.createDirectories(dir.resolve("src/z")).resolve("R.java");
        Files.writeString(source, REFLECTION);
        var classes = Files.createDirectories(dir.resolve("classes"));
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "--release", "8", "-Xlint:-options", "-proc:none", "-nowarn",
            "-d", classes.toString(), source.toString()));
        var entries = new LinkedHashMap<String,byte[]>();
        entries.put("z/R.class", Files.readAllBytes(classes.resolve("z/R.class")));
        entries.put("z/App.class", app(extra));
        return entries;
    }

    private static MethodNode method(ClassNode node, String name) {
        return node.methods.stream().filter(m -> m.name.equals(name)).findFirst().orElseThrow(() -> new AssertionError("no " + name));
    }
    private static List<Integer> opcodes(MethodNode method) {
        var result = new ArrayList<Integer>();
        for (var insn : method.instructions) if (insn.getOpcode() >= 0) result.add(insn.getOpcode());
        return result;
    }

    @Test void theSyntheticJarIsReversedLinkedAndCleaned() throws Exception {
        var result = new ZkmReverser().apply(jar(w -> {}), 10_000);
        var report = result.report();
        assertTrue(report.detected(), report.toString());
        assertEquals(List.of(), report.refused(), report.toString());
        assertEquals(2, report.count("reflective accesses written back"), report.toString());
        assertEquals(0, report.count("reflective accesses left"), report.toString());
        assertEquals(1, report.count("strings decrypted"), report.toString());
        assertEquals(1, report.count("string constants decrypted"), report.toString());
        assertEquals(1, report.count("packed methods unpacked"), report.toString());
        assertEquals(1, report.count("key parameters dropped"), report.toString());
        assertEquals(1, report.count("opaque flags proved"), report.toString());
        assertEquals(1, report.count("reflection classes removed"), report.toString());
        assertEquals(0, report.count("class write failures"), report.toString());
        SkidfuscatorReverserTest.link(result.entries());

        assertEquals(Set.of("z/App.class"), result.entries().keySet());
        var app = SkidfuscatorReverserTest.node(result.entries().get("z/App.class"));
        assertTrue(app.methods.stream().noneMatch(m -> m.name.equals("a") || m.name.equals("<clinit>")), "decryptor and initializer removed");
        assertTrue(app.fields.stream().noneMatch(f -> f.name.equals("T")), "table removed");
        // The parameters are back, the key is not.
        assertEquals("(I)I", method(app, "get").desc);
        assertEquals(List.of(ALOAD, GETFIELD, ILOAD, IADD, IRETURN), opcodes(method(app, "get")));
        var run = method(app, "run");
        assertTrue(Arrays.stream(run.instructions.toArray()).noneMatch(i -> i.getOpcode() == ANEWARRAY), "no array is built");
        assertTrue(Arrays.stream(run.instructions.toArray()).anyMatch(i -> i instanceof MethodInsnNode call && call.name.equals("get") && call.desc.equals("(I)I")));
        // The reflective call is a call, its boxing gone.
        assertEquals(List.of(ALOAD, INVOKEVIRTUAL, IRETURN), opcodes(method(app, "viaInvoke")));
        assertEquals(List.of(BIPUSH, IRETURN), opcodes(method(app, "flagged")));
        assertEquals(Set.of("hello", "constant"), SkidfuscatorReverserTest.literals(app));
    }

    @Test void whatHasNoKnownKeyStaysAndCompilesAsItRuns() throws Exception {
        var result = new ZkmReverser().apply(jar(w -> {
            // Nothing calls it: its key is unknown.
            method(w, ACC_PUBLIC, "orphan", "([Ljava/lang/Object;)I", m -> {
                readKey(m, 0, 2);
                m.visitVarInsn(ALOAD, 0);
                getter(m, "c", 77, 2);
                m.visitInsn(SWAP);
                m.visitMethodInsn(INVOKEVIRTUAL, "java/lang/reflect/Field", "getInt", "(Ljava/lang/Object;)I", false);
                m.visitInsn(IRETURN);
            });
            // Its array is passed on: not a parameter list.
            method(w, ACC_PUBLIC | ACC_STATIC, "escapes", "([Ljava/lang/Object;)Ljava/lang/Object;", m -> { m.visitVarInsn(ALOAD, 0); m.visitInsn(ARETURN); });
            method(w, ACC_PUBLIC | ACC_STATIC, "callsEscapes", "()Ljava/lang/Object;", m -> {
                m.visitInsn(ICONST_0); m.visitTypeInsn(ANEWARRAY, "java/lang/Object");
                m.visitMethodInsn(INVOKESTATIC, "z/App", "escapes", "([Ljava/lang/Object;)Ljava/lang/Object;", false);
                m.visitInsn(ARETURN);
            });
        }), 10_000);
        var report = result.report();
        assertEquals(1, report.count("reflective accesses left"), report.toString());
        assertEquals(0, report.count("reflection classes removed"), report.toString());
        assertEquals(1, report.count("methods declared to throw what their reflection throws"), report.toString());
        SkidfuscatorReverserTest.link(result.entries());
        assertTrue(result.entries().containsKey("z/R.class"), "the reflection class is still needed");
        var app = SkidfuscatorReverserTest.node(result.entries().get("z/App.class"));
        assertEquals("([Ljava/lang/Object;)I", method(app, "orphan").desc);
        assertEquals(List.of("java/lang/Exception"), method(app, "orphan").exceptions);
        assertEquals("([Ljava/lang/Object;)Ljava/lang/Object;", method(app, "escapes").desc);
    }

    @Test void aFlagTheProgramSetsIsNotFolded() throws Exception {
        var result = new ZkmReverser().apply(jar(w -> method(w, ACC_PUBLIC | ACC_STATIC, "enable", "()V", m -> {
            m.visitInsn(ICONST_1); m.visitFieldInsn(PUTSTATIC, "z/App", "flag", "Z"); m.visitInsn(RETURN);
        })), 10_000);
        assertEquals(0, result.report().count("opaque flags proved"), result.report().toString());
        var app = SkidfuscatorReverserTest.node(result.entries().get("z/App.class"));
        assertTrue(opcodes(method(app, "flagged")).contains(GETSTATIC));
    }

    @Test void aJarWithoutItIsLeftAsItIs() throws Exception {
        var w = new ClassWriter(0);
        w.visit(V1_8, ACC_PUBLIC, "p/Plain", null, "java/lang/Object", null);
        w.visitEnd();
        var entries = Map.of("p/Plain.class", w.toByteArray());
        var result = new ZkmReverser().apply(entries, 100);
        assertFalse(result.report().detected());
        assertSame(entries, result.entries());
    }

    @Test void keyStatementsMoveAfterTheConstructorCall() {
        var node = new ClassNode();
        node.name = "p/C"; node.superName = "java/lang/Object"; node.version = V1_8;
        var init = new MethodNode(ACC_PUBLIC, "<init>", "(J)V", null, null);
        init.maxLocals = 5; init.maxStack = 4;
        init.instructions.add(new VarInsnNode(LLOAD, 1));
        init.instructions.add(new LdcInsnNode(9L));
        init.instructions.add(new InsnNode(LXOR));
        init.instructions.add(new VarInsnNode(LSTORE, 3));
        init.instructions.add(new VarInsnNode(ALOAD, 0));
        init.instructions.add(new MethodInsnNode(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false));
        init.instructions.add(new InsnNode(RETURN));
        assertEquals(4, PureDeadCode.afterSuper(node, init, Set.of()));
        assertEquals(List.of(ALOAD, INVOKESPECIAL, LLOAD, LDC, LXOR, LSTORE, RETURN), opcodes(init));
    }

    @Test void pureLeftoversGoAndEffectsStay() {
        var method = new MethodNode(ACC_STATIC, "m", "(J)I", null, null);
        method.maxLocals = 2; method.maxStack = 6;
        var end = new LabelNode();
        // A key copied and dropped, a jump to the next instruction, a box undone: nothing remains of them.
        method.instructions.add(new VarInsnNode(LLOAD, 0));
        method.instructions.add(new InsnNode(DUP2));
        method.instructions.add(new InsnNode(POP2));
        method.instructions.add(new InsnNode(POP2));
        method.instructions.add(new JumpInsnNode(GOTO, end));
        method.instructions.add(end);
        method.instructions.add(new InsnNode(ICONST_3));
        method.instructions.add(new MethodInsnNode(INVOKESTATIC, "java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;", false));
        method.instructions.add(new TypeInsnNode(CHECKCAST, "java/lang/Integer"));
        method.instructions.add(new MethodInsnNode(INVOKEVIRTUAL, "java/lang/Integer", "intValue", "()I", false));
        // A call has an effect, even if its result is dropped.
        method.instructions.add(new MethodInsnNode(INVOKESTATIC, "p/C", "effect", "()I", false));
        method.instructions.add(new InsnNode(POP));
        method.instructions.add(new InsnNode(IRETURN));
        PureDeadCode.clean("p/C", method);
        assertEquals(List.of(ICONST_3, INVOKESTATIC, POP, IRETURN), opcodes(method));
    }

    @Test void aHandlerThatOnlyRethrowsGoes() {
        var method = new MethodNode(ACC_STATIC, "m", "()V", null, null);
        method.maxLocals = 0; method.maxStack = 1;
        var start = new LabelNode(); var finish = new LabelNode(); var handler = new LabelNode();
        method.instructions.add(start);
        method.instructions.add(new MethodInsnNode(INVOKESTATIC, "p/C", "effect", "()V", false));
        method.instructions.add(finish);
        method.instructions.add(new InsnNode(RETURN));
        method.instructions.add(handler);
        method.instructions.add(new InsnNode(ATHROW));
        method.tryCatchBlocks.add(new TryCatchBlockNode(start, finish, handler, "java/lang/SecurityException"));
        PureDeadCode.clean("p/C", method);
        assertEquals(List.of(), method.tryCatchBlocks);
        assertEquals(List.of(INVOKESTATIC, RETURN), opcodes(method));
    }
}
