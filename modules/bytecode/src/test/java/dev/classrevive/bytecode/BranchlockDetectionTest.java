package dev.classrevive.bytecode;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/** Branchlock 4 detection over class sets that carry its markers (written with ASM; no tool output is used). */
class BranchlockDetectionTest {
    private static byte[] write(ClassNode node) { var writer = new ClassWriter(ClassWriter.COMPUTE_MAXS); node.accept(writer); return writer.toByteArray(); }
    private static ClassNode type(String name) {
        var node = new ClassNode(); node.version = V1_8; node.access = ACC_PUBLIC | ACC_SUPER; node.name = name; node.superName = "java/lang/Object"; return node;
    }
    private static MethodNode method(ClassNode node, int access, String name, String desc) {
        var method = new MethodNode(access, name, desc, null, null); node.methods.add(method); return method;
    }
    private static Optional<ObfuscatorClassifier.Hypothesis> branchlock(ClassNode... nodes) {
        var builder = new ObfuscationProfile.Builder();
        for (var node : nodes) builder.addClass(write(node));
        return ObfuscatorClassifier.classify(builder.build()).hypotheses().stream().filter(h -> h.obfuscator().equals("Branchlock")).findFirst();
    }
    private static void returns(MethodNode method) { method.instructions.add(new InsnNode(ACONST_NULL)); method.instructions.add(new InsnNode(ARETURN)); }

    @Test void theWatermarkAloneNamesIt() {
        var mark = type("Branchlock_a");
        mark.fields.add(new FieldNode(ACC_PUBLIC | ACC_STATIC | ACC_FINAL, "b", "Ljava/lang/String;", null, "  obfuscated using branchlock.net "));
        var found = branchlock(mark).orElseThrow();
        assertEquals("HIGH", found.confidence(), found.toString());
    }

    @Test void theReferenceLookupAndStringTablesWithoutTheWatermark() {
        var lookup = type("x/L");
        returns(method(lookup, ACC_PUBLIC | ACC_STATIC, "a", "(JLjava/lang/Class;)Ljava/lang/reflect/Method;"));
        returns(method(lookup, ACC_PUBLIC | ACC_STATIC, "b", "(JLjava/lang/Class;)Ljava/lang/reflect/Field;"));
        var nodes = new ArrayList<ClassNode>(List.of(lookup));
        for (int k = 0; k < 2; k++) {
            var app = type("x/C" + k);
            app.fields.add(new FieldNode(ACC_PRIVATE | ACC_STATIC, "t", "[Ljava/lang/String;", null, null));
            var clinit = method(app, ACC_STATIC, "<clinit>", "()V");
            var list = clinit.instructions;
            list.add(new LdcInsnNode("data")); list.add(new MethodInsnNode(INVOKEVIRTUAL, "java/lang/String", "toCharArray", "()[C", false)); list.add(new InsnNode(POP));
            list.add(new TypeInsnNode(NEW, "java/lang/Throwable")); list.add(new InsnNode(DUP));
            list.add(new MethodInsnNode(INVOKESPECIAL, "java/lang/Throwable", "<init>", "()V", false));
            list.add(new MethodInsnNode(INVOKEVIRTUAL, "java/lang/Throwable", "getStackTrace", "()[Ljava/lang/StackTraceElement;", false));
            list.add(new InsnNode(ICONST_0)); list.add(new InsnNode(AALOAD));
            list.add(new MethodInsnNode(INVOKEVIRTUAL, "java/lang/StackTraceElement", "getMethodName", "()Ljava/lang/String;", false));
            list.add(new MethodInsnNode(INVOKEVIRTUAL, "java/lang/String", "hashCode", "()I", false)); list.add(new InsnNode(POP));
            list.add(new InsnNode(ICONST_1)); list.add(new TypeInsnNode(ANEWARRAY, "java/lang/String"));
            list.add(new FieldInsnNode(PUTSTATIC, app.name, "t", "[Ljava/lang/String;"));
            list.add(new InsnNode(RETURN));
            nodes.add(app);
        }
        var found = branchlock(nodes.toArray(ClassNode[]::new)).orElseThrow();
        assertEquals("HIGH", found.confidence(), found.toString());
        assertEquals(2, found.reasons().size(), found.toString());
    }

    @Test void theDemoFieldNamesIt() {
        var app = type("x/Main");
        app.fields.add(new FieldNode(ACC_PUBLIC, "BRANCHLOCK_DOT_NET_DEMO", "I", null, null));
        assertEquals("HIGH", branchlock(app).orElseThrow().confidence());
    }

    @Test void anOrdinaryClassReadingItsStackTraceIsNotBranchlock() {
        var app = type("x/Logger");
        app.fields.add(new FieldNode(ACC_PRIVATE | ACC_STATIC, "names", "[Ljava/lang/String;", null, null));
        var clinit = method(app, ACC_STATIC, "<clinit>", "()V");
        clinit.instructions.add(new InsnNode(ICONST_0)); clinit.instructions.add(new TypeInsnNode(ANEWARRAY, "java/lang/String"));
        clinit.instructions.add(new FieldInsnNode(PUTSTATIC, app.name, "names", "[Ljava/lang/String;"));
        clinit.instructions.add(new InsnNode(RETURN));
        returns(method(app, ACC_PUBLIC | ACC_STATIC, "find", "(JLjava/lang/Class;)Ljava/lang/reflect/Method;"));
        assertTrue(branchlock(app).isEmpty());
    }
}
