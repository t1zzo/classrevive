package dev.classrevive.bytecode;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.tree.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/** Phase 2DK: Radon 2 and yGuard rules over class sets written in their shapes (no tool output is used). */
class RadonYguardDetectionTest {
    private static byte[] write(ClassNode node) { var writer = new ClassWriter(ClassWriter.COMPUTE_MAXS); node.accept(writer); return writer.toByteArray(); }
    private static ClassNode type(String name, String superName) {
        var node = new ClassNode(); node.version = V1_8; node.access = ACC_PUBLIC | ACC_SUPER; node.name = name; node.superName = superName; return node;
    }
    private static MethodNode method(ClassNode node, int access, String name, String desc) {
        var method = new MethodNode(access, name, desc, null, null); node.methods.add(method); return method;
    }
    private static ObfuscationProfile profile(List<ClassNode> nodes) {
        var builder = new ObfuscationProfile.Builder();
        for (var node : nodes) builder.addClass(write(node));
        return builder.build();
    }
    private static Optional<ObfuscatorClassifier.Hypothesis> hypothesis(ObfuscationProfile profile, String name) {
        return ObfuscatorClassifier.classify(profile).hypotheses().stream().filter(h -> h.obfuscator().equals(name)).findFirst();
    }

    private static List<ClassNode> radonShapes() {
        var decryptor = type("rs/Str", "java/lang/Object");
        decryptor.fields.add(new FieldNode(ACC_PRIVATE | ACC_STATIC | ACC_FINAL, "m", "Ljava/util/Map;", null, null));
        decryptor.fields.add(new FieldNode(ACC_PRIVATE | ACC_STATIC | ACC_FINAL, "k", "[J", null, null));
        var s = method(decryptor, ACC_PUBLIC | ACC_STATIC, "s", "(Ljava/lang/Object;I)Ljava/lang/String;");
        s.instructions.add(new MethodInsnNode(INVOKESTATIC, "java/lang/Thread", "currentThread", "()Ljava/lang/Thread;", false));
        s.instructions.add(new MethodInsnNode(INVOKEVIRTUAL, "java/lang/Thread", "getStackTrace", "()[Ljava/lang/StackTraceElement;", false));
        s.instructions.add(new InsnNode(POP));
        for (int i = 0; i < 4; i++) {
            s.instructions.add(new InsnNode(ICONST_0)); s.instructions.add(new VarInsnNode(ILOAD, 1)); s.instructions.add(new InsnNode(IXOR));
            s.instructions.add(new VarInsnNode(ISTORE, 2 + i));
        }
        s.instructions.add(new VarInsnNode(ALOAD, 0)); s.instructions.add(new TypeInsnNode(CHECKCAST, "java/lang/String")); s.instructions.add(new InsnNode(ARETURN));
        var app = type("app/App", "java/lang/Object");
        app.fields.add(new FieldNode(ACC_PUBLIC | ACC_STATIC | ACC_FINAL, "p", "Z", null, null));
        var heavy = new Handle(H_INVOKESTATIC, "rb/Boot", "b", "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", false);
        for (int i = 0; i < 3; i++) {
            var m = method(app, ACC_PUBLIC | ACC_STATIC, "m" + i, "()Ljava/lang/String;");
            m.visibleAnnotations = new ArrayList<>(List.of(new AnnotationNode("@")));
            m.instructions.add(new FieldInsnNode(GETSTATIC, "app/App", "p", "Z")); m.instructions.add(new VarInsnNode(ISTORE, 0));
            m.instructions.add(new LdcInsnNode("ሴ噸")); m.instructions.add(new LdcInsnNode(0x12345));
            m.instructions.add(new MethodInsnNode(INVOKESTATIC, "rs/Str", "s", "(Ljava/lang/Object;I)Ljava/lang/String;", false));
            m.instructions.add(new LdcInsnNode("java.io.PrintStream")); m.instructions.add(new LdcInsnNode(42L));
            m.instructions.add(new InvokeDynamicInsnNode("a", "(Ljava/lang/Object;Ljava/lang/String;J)Ljava/lang/String;", heavy));
            m.instructions.add(new InsnNode(ARETURN));
        }
        var boot = type("rb/Boot", "java/lang/Object");
        var b = method(boot, ACC_PUBLIC | ACC_STATIC, "b", heavy.getDesc());
        b.instructions.add(new InsnNode(ACONST_NULL)); b.instructions.add(new InsnNode(ARETURN));
        return List.of(decryptor, app, boot);
    }

    @Test void radonShapesSuggestRadonAndNotStringerOrZkm() {
        var profile = profile(radonShapes());
        assertEquals(3, profile.metric("radon.string_sites"));
        assertEquals(3, profile.metric("radon.indy_heavy_sites"));
        assertEquals(1, profile.metric("radon.predicate_fields"));
        assertEquals(3, profile.metric("radon.bad_annotations"));
        var radon = hypothesis(profile, "Radon").orElseThrow();
        assertEquals("HIGH", radon.confidence(), radon.reasons().toString());
        assertEquals("Radon", ObfuscatorClassifier.classify(profile).hypotheses().getFirst().obfuscator());
        assertTrue(hypothesis(profile, "Stringer").isEmpty(), ObfuscatorClassifier.classify(profile).summary());
        assertTrue(hypothesis(profile, "Zelix KlassMaster").isEmpty(), ObfuscatorClassifier.classify(profile).summary());
    }

    @Test void writtenStaticFinalsAndJavacCodeAreNoRadonEvidence() {
        var app = type("app/Clean", "java/lang/Object");
        app.fields.add(new FieldNode(ACC_STATIC | ACC_FINAL, "DEBUG", "Z", null, null));
        var clinit = method(app, ACC_STATIC, "<clinit>", "()V");
        clinit.instructions.add(new InsnNode(ICONST_1)); clinit.instructions.add(new FieldInsnNode(PUTSTATIC, "app/Clean", "DEBUG", "Z"));
        clinit.instructions.add(new InsnNode(RETURN));
        var m = method(app, ACC_STATIC, "m", "()Z");
        m.instructions.add(new FieldInsnNode(GETSTATIC, "app/Clean", "DEBUG", "Z")); m.instructions.add(new VarInsnNode(ISTORE, 0));
        m.instructions.add(new VarInsnNode(ILOAD, 0)); m.instructions.add(new InsnNode(IRETURN));
        var profile = profile(List.of(app));
        assertEquals(0, profile.metric("radon.predicate_fields"));
        assertTrue(hypothesis(profile, "Radon").isEmpty());
    }

    @Test void shortNonAsciiNamesSuggestYguardAndNotProguard() {
        var nodes = new ArrayList<ClassNode>();
        String[] classes = {"Ä", "ß", "Ω"}; String[] members = {"ą", "ć", "ę", "ł"};
        for (String name : classes) {
            var node = type("a/" + name, "java/lang/Object");
            for (String member : members) node.fields.add(new FieldNode(ACC_PRIVATE, member, "I", null, null));
            nodes.add(node);
        }
        var profile = profile(nodes);
        assertEquals(12, profile.metric("names.non_ascii_short_members"));
        var yguard = hypothesis(profile, "yGuard (renaming)").orElseThrow();
        assertEquals("HIGH", yguard.confidence(), yguard.reasons().toString());
        assertTrue(hypothesis(profile, "ProGuard / R8 (renaming only)").map(h -> !h.confidence().equals("HIGH")).orElse(true));
    }

    @Test void portugueseIdentifiersAreNoYguardEvidence() {
        var node = type("demo/Inventario", "java/lang/Object");
        for (String name : List.of("preço", "ação", "quantidade", "é1")) node.fields.add(new FieldNode(ACC_PRIVATE, name, "I", null, null));
        var profile = profile(List.of(node));
        assertTrue(hypothesis(profile, "yGuard (renaming)").isEmpty());
    }
}
