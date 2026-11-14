package dev.classrevive.bytecode;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.*;
import java.util.*;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/** Ambien detection over archives and classes that carry its markers (written with ASM; no tool output is used). */
class AmbienDetectionTest {
    private static byte[] write(ClassNode node) { var writer = new ClassWriter(ClassWriter.COMPUTE_MAXS); node.accept(writer); return writer.toByteArray(); }
    private static ClassNode type(String name, String superName) {
        var node = new ClassNode(); node.version = V1_8; node.access = ACC_PUBLIC | ACC_SUPER; node.name = name; node.superName = superName; return node;
    }
    private static Optional<ObfuscatorClassifier.Hypothesis> ambien(Consumer<ObfuscationProfile.Builder> input) {
        var builder = new ObfuscationProfile.Builder();
        input.accept(builder);
        return ObfuscatorClassifier.classify(builder.build()).hypotheses().stream().filter(h -> h.obfuscator().equals("Ambien")).findFirst();
    }
    /** A static method whose body makes the given calls, then returns null. */
    private static void method(ClassNode node, String name, String desc, MethodInsnNode... calls) {
        var m = new MethodNode(ACC_PUBLIC | ACC_STATIC, name, desc, null, null);
        for (var call : calls) {
            m.instructions.add(call);
            var ret = org.objectweb.asm.Type.getReturnType(call.desc);
            if (ret.getSize() == 1) m.instructions.add(new InsnNode(POP)); else if (ret.getSize() == 2) m.instructions.add(new InsnNode(POP2));
        }
        m.instructions.add(new InsnNode(ACONST_NULL)); m.instructions.add(new InsnNode(ARETURN));
        node.methods.add(m);
    }

    @Test void itsDefaultArchiveCommentNamesIt() {
        var found = ambien(b -> b.addArchiveComment("Obfuscated by Ambien\nSource code: https://example.invalid")).orElseThrow();
        assertEquals("HIGH", found.confidence(), found.toString());
        assertTrue(ambien(b -> b.addArchiveComment("Built with Gradle")).isEmpty());
    }

    @Test void itsStringDecryptorAndReferenceHider() {
        var app = type("app/A", "java/lang/Object");
        method(app, "d", "(Ljava/lang/String;II)Ljava/lang/String;",
            new MethodInsnNode(INVOKESPECIAL, "java/util/Random", "<init>", "(J)V", false),
            new MethodInsnNode(INVOKEVIRTUAL, "java/util/Base64$Decoder", "decode", "([B)[B", false));
        method(app, "h", "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/invoke/MutableCallSite;",
            new MethodInsnNode(INVOKESTATIC, "java/lang/Class", "forName", "(Ljava/lang/String;)Ljava/lang/Class;", false),
            new MethodInsnNode(INVOKEVIRTUAL, "java/lang/invoke/MethodHandles$Lookup", "findStatic",
                "(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/MethodHandle;", false));
        var found = ambien(b -> b.addClass(write(app))).orElseThrow();
        assertEquals("HIGH", found.confidence(), found.toString());
    }

    @Test void itsCrashersAddUp() {
        var self = type("qwertyuiopasdfg", "qwertyuiopasdfg");
        var html = type("<html><img src=\"https:abcde", "java/lang/Object");
        var app = type("app/B", "java/lang/Object");
        String lineFeeds = "\n".repeat(100);
        app.invisibleAnnotations = new ArrayList<>(List.of(new AnnotationNode(lineFeeds)));
        for (int i = 0; i < 3; i++) {
            var field = new FieldNode(ACC_PRIVATE, "f" + i, "I", null, null);
            field.invisibleAnnotations = new ArrayList<>(List.of(new AnnotationNode(lineFeeds)));
            app.fields.add(field);
        }
        var found = ambien(b -> { b.addClass(write(self)); b.addClass(write(html)); b.addClass(write(app)); }).orElseThrow();
        assertEquals("HIGH", found.confidence(), found.toString());
    }

    @Test void anOrdinaryThreeArgumentStringHelperIsNot() {
        var app = type("app/Text", "java/lang/Object");
        method(app, "slice", "(Ljava/lang/String;II)Ljava/lang/String;",
            new MethodInsnNode(INVOKEVIRTUAL, "java/lang/String", "substring", "(II)Ljava/lang/String;", false));
        assertTrue(ambien(b -> b.addClass(write(app))).isEmpty());
    }
}
