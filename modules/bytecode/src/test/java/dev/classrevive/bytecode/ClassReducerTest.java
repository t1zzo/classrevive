package dev.classrevive.bytecode;

import java.nio.file.*;
import java.util.*;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import static org.junit.jupiter.api.Assertions.*;

class ClassReducerTest {
    @TempDir Path dir;

    static final String SOURCE = """
        package demo;
        public class Shapes {
            private int count;
            private static final String LABEL = "x";
            public Shapes() { count = 1; }
            public int area(int w, int h) {
                if (w < 0) return -1;
                int total = 0;
                for (int i = 0; i < h; i++) total += w;
                try { return total / count; } catch (ArithmeticException e) { return 0; }
            }
            public String label() { return LABEL + count; }
            static long wide(long x) { return x * 3; }
            public int pick(int k) { switch (k) { case 1: return 10; case 2: return 20; default: return 0; } }
            static final class Inner { int v() { return 2; } }
        }
        """;

    Map<String,byte[]> compile() throws Exception {
        Path source = dir.resolve("demo/Shapes.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, SOURCE);
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "--release", "17", "-proc:none", "-d", dir.resolve("out").toString(), source.toString()));
        var result = new TreeMap<String,byte[]>();
        for (String name : List.of("demo/Shapes", "demo/Shapes$Inner")) result.put(name, Files.readAllBytes(dir.resolve("out/" + name + ".class")));
        return result;
    }

    /** Authored fixture bytes only: defined in a throwaway loader and linked, which runs the verifier and no fixture code. */
    static void verifies(byte[] bytes) {
        var type = new ClassLoader(null) { Class<?> define() { return defineClass(null, bytes, 0, bytes.length); } }.define();
        assertDoesNotThrow(type::getDeclaredMethods, "reduced class must pass JVM verification");
    }

    static MethodNode method(byte[] bytes, String key) {
        var node = new ClassNode(); new ClassReader(bytes).accept(node, 0);
        return node.methods.stream().filter(m -> (m.name + m.desc).equals(key)).findFirst().orElseThrow();
    }

    static int real(MethodNode method) {
        int count = 0; for (var insn : method.instructions) if (insn.getOpcode() >= 0) count++; return count;
    }

    @Test void listsMembersAndKeepsOnlyTheRequestedOnes() throws Exception {
        byte[] shapes = compile().get("demo/Shapes");
        var keys = ClassReducer.members(shapes).stream().map(ClassReducer.Member::key).toList();
        assertTrue(keys.containsAll(List.of("count:I", "LABEL:Ljava/lang/String;", "<init>()V", "area(II)I", "label()Ljava/lang/String;",
            "wide(J)J", "pick(I)I")), keys.toString());
        byte[] reduced = ClassReducer.keepMembers(shapes, Set.of("count:I", "<init>()V", "area(II)I"));
        assertEquals(List.of("count:I", "<init>()V", "area(II)I"), ClassReducer.members(reduced).stream().map(ClassReducer.Member::key).toList());
        verifies(reduced);
        assertEquals(real(method(shapes, "area(II)I")), real(method(reduced, "area(II)I")), "kept bodies are unchanged");
    }

    @Test void stubsBodiesWithoutFrames() throws Exception {
        byte[] shapes = compile().get("demo/Shapes");
        byte[] stubbed = ClassReducer.stubMethods(shapes, Set.of("<init>()V", "area(II)I", "wide(J)J"));
        verifies(stubbed);
        assertEquals(List.of(Opcodes.ACONST_NULL, Opcodes.ATHROW), opcodes(method(stubbed, "area(II)I")));
        assertEquals(List.of(Opcodes.ALOAD, Opcodes.INVOKESPECIAL, Opcodes.RETURN), opcodes(method(stubbed, "<init>()V")));
        assertTrue(method(stubbed, "area(II)I").tryCatchBlocks.isEmpty());
        assertEquals(real(method(shapes, "pick(I)I")), real(method(stubbed, "pick(I)I")));
        assertTrue(ClassReducer.instructions(stubbed) < ClassReducer.instructions(shapes));
    }

    static List<Integer> opcodes(MethodNode method) {
        var result = new ArrayList<Integer>(); for (var insn : method.instructions) if (insn.getOpcode() >= 0) result.add(insn.getOpcode()); return result;
    }

    @Test void everyInstructionEditIsRefusedOrVerifiesAndLeavesOtherMethodsAlone() throws Exception {
        var classes = compile();
        byte[] shapes = classes.get("demo/Shapes");
        for (String key : List.of("area(II)I", "pick(I)I")) {
            var edits = ClassReducer.edits(shapes, key);
            assertFalse(edits.isEmpty(), key);
            boolean smaller = false;
            for (int index = 0; index < edits.size(); index++) {
                byte[] edited = ClassReducer.applyEdit(shapes, key, index, classes);
                if (edited == null) continue;
                verifies(edited);
                assertEquals(real(method(shapes, "label()Ljava/lang/String;")), real(method(edited, "label()Ljava/lang/String;")), edits.get(index));
                smaller |= real(method(edited, key)) < real(method(shapes, key));
            }
            assertTrue(smaller, "some edit of " + key + " removes code: " + edits);
        }
        assertTrue(ClassReducer.edits(shapes, "area(II)I").contains("drop exception handler 1"));
        int toDefault = ClassReducer.edits(shapes, "pick(I)I").indexOf("switch 1 to its default target");
        byte[] picked = ClassReducer.applyEdit(shapes, "pick(I)I", toDefault, classes);
        verifies(picked);
        assertFalse(opcodes(method(picked, "pick(I)I")).contains(Opcodes.TABLESWITCH) || opcodes(method(picked, "pick(I)I")).contains(Opcodes.LOOKUPSWITCH));
        assertNull(ClassReducer.applyEdit(shapes, "area(II)I", 10_000, classes));
        assertEquals(List.of(), ClassReducer.edits(shapes, "missing()V"));
    }

    @Test void stripsDebugAttributesAndDanglingNestReferences() throws Exception {
        var classes = compile();
        byte[] stripped = ClassReducer.stripDebug(classes.get("demo/Shapes"));
        verifies(stripped);
        for (var insn : method(stripped, "area(II)I").instructions) assertFalse(insn instanceof LineNumberNode);
        byte[] alone = ClassReducer.dropReferences(classes.get("demo/Shapes"), Set.of("demo/Shapes$Inner"));
        var node = new ClassNode(); new ClassReader(alone).accept(node, 0);
        assertTrue(node.innerClasses.stream().noneMatch(inner -> inner.name.equals("demo/Shapes$Inner")));
        assertTrue(node.nestMembers == null || !node.nestMembers.contains("demo/Shapes$Inner"));
        verifies(alone);
    }

    @Test void commonSuperclassesComeFromTheCaseAndTheJdk() {
        var caseClasses = Map.of("demo/A", LinkCheckTest.empty("demo/A", "demo/B"), "demo/B", LinkCheckTest.empty("demo/B", "java/util/AbstractList"));
        assertEquals("demo/B", ClassReducer.commonSuperClass("demo/A", "demo/B", caseClasses));
        assertEquals("java/util/AbstractList", ClassReducer.commonSuperClass("demo/A", "java/util/ArrayList", caseClasses));
        assertEquals("java/lang/Number", ClassReducer.commonSuperClass("java/lang/Integer", "java/lang/Long", caseClasses));
        assertEquals("java/lang/Object", ClassReducer.commonSuperClass("demo/Unknown", "java/lang/Long", caseClasses));
    }
}
