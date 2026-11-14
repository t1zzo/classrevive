package dev.classrevive.bytecode;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/** BlackObfuscator detection over methods written with ASM in the shape of its dispatcher; nothing is loaded or run. */
class BlackObfuscatorDetectionTest {
    private static byte[] write(ClassNode node) { var writer = new ClassWriter(ClassWriter.COMPUTE_MAXS); node.accept(writer); return writer.toByteArray(); }
    private static ClassNode type(String name) {
        var node = new ClassNode(); node.version = V1_8; node.access = ACC_PUBLIC | ACC_SUPER; node.name = name; node.superName = "java/lang/Object"; return node;
    }
    private static List<String> hypotheses(ClassNode node) {
        var builder = new ObfuscationProfile.Builder();
        builder.addClass(write(node));
        return ObfuscatorClassifier.classify(builder.build()).hypotheses().stream().map(ObfuscatorClassifier.Hypothesis::obfuscator).toList();
    }

    /** s = "k0"; loop: switch (s.hashCode() ^ K) { case: work; s = "k1"; goto loop; case: return } */
    private static MethodNode dispatcher(String name) {
        var m = new MethodNode(ACC_PUBLIC | ACC_STATIC, name, "()V", null, null);
        var list = m.instructions;
        var loop = new LabelNode(); var first = new LabelNode(); var second = new LabelNode(); var end = new LabelNode();
        int k = 0x5a5a;
        list.add(new LdcInsnNode("k0")); list.add(new VarInsnNode(ASTORE, 0));
        list.add(loop);
        list.add(new VarInsnNode(ALOAD, 0));
        list.add(new MethodInsnNode(INVOKEVIRTUAL, "java/lang/String", "hashCode", "()I", false));
        list.add(new LdcInsnNode(k)); list.add(new InsnNode(IXOR));
        list.add(new LookupSwitchInsnNode(end, new int[] {"k0".hashCode() ^ k, "k1".hashCode() ^ k}, new LabelNode[] {first, second}));
        list.add(first);
        list.add(new MethodInsnNode(INVOKESTATIC, "java/lang/Thread", "yield", "()V", false));
        list.add(new LdcInsnNode("k1")); list.add(new VarInsnNode(ASTORE, 0)); list.add(new JumpInsnNode(GOTO, loop));
        list.add(second);
        list.add(new LdcInsnNode("k0")); list.add(new VarInsnNode(ASTORE, 0)); list.add(new JumpInsnNode(GOTO, loop));
        list.add(end);
        list.add(new InsnNode(RETURN));
        return m;
    }

    @Test void flattenedMethodsAreRecognized() {
        var app = type("app/Flat");
        for (int i = 0; i < 3; i++) app.methods.add(dispatcher("m" + i));
        assertTrue(hypotheses(app).contains("BlackObfuscator"), hypotheses(app).toString());
    }

    @Test void anOrdinaryStringSwitchIsNot() {
        // switch (s) as javac writes it: s.hashCode() straight into the switch, then equals checks; no xor, no loop.
        var app = type("app/Plain");
        var m = new MethodNode(ACC_PUBLIC | ACC_STATIC, "pick", "(Ljava/lang/String;)I", null, null);
        var a = new LabelNode(); var other = new LabelNode();
        m.instructions.add(new VarInsnNode(ALOAD, 0));
        m.instructions.add(new MethodInsnNode(INVOKEVIRTUAL, "java/lang/String", "hashCode", "()I", false));
        m.instructions.add(new LookupSwitchInsnNode(other, new int[] {"a".hashCode()}, new LabelNode[] {a}));
        m.instructions.add(a); m.instructions.add(new InsnNode(ICONST_1)); m.instructions.add(new InsnNode(IRETURN));
        m.instructions.add(other); m.instructions.add(new InsnNode(ICONST_0)); m.instructions.add(new InsnNode(IRETURN));
        app.methods.add(m);
        assertFalse(hypotheses(app).contains("BlackObfuscator"));
    }
}
