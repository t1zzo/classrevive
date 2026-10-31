package dev.classrevive.dex;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.ClassNode;

/** Phase 2DA: anonymous classes R8 creates outside their method stop being anonymous; the others stay as they are. */
class DexNormalizerTest {
    @Test void anonymousClassesCreatedInAnotherMethodBecomeTopLevel() {
        var classes = new TreeMap<String, byte[]>();
        // Outer$1 belongs to a(), but R8 inlined a() into b() and c.Other.d(): three creation sites.
        classes.put("p/Outer", outer("p/Outer", List.of("p/Outer$1", "p/Outer$2", "p/Outer$3"),
            Map.of("a", "p/Outer$1", "b", "p/Outer$1", "e", "p/Outer$2", "f", "p/Outer$3")));
        classes.put("p/Outer$1", anonymous("p/Outer$1", "p/Outer", "a"));
        classes.put("p/Outer$2", anonymous("p/Outer$2", "p/Outer", "e"));
        classes.put("c/Other", outer("c/Other", List.of("p/Outer$1"), Map.of("d", "p/Outer$1")));
        // Outer$2$1 lives inside Outer$2: its this$0 names Outer$2, as javac's nested anonymous classes do.
        classes.put("p/Outer$2$1", anonymous("p/Outer$2$1", "p/Outer$2", "run"));
        // Outer$3 is created only in f(), but an R8 lambda class keeps it in a field: Java cannot name it there.
        classes.put("p/Outer$3", anonymous("p/Outer$3", "p/Outer", "f"));
        classes.put("p/Outer$$ExternalSyntheticLambda0", holder("p/Outer$$ExternalSyntheticLambda0", "p/Outer$3"));

        var demoted = DexNormalizer.createdElsewhere(classes);
        assertEquals(Set.of("p/Outer$1", "p/Outer$3"), demoted, "Outer$2 is created only in e() and named only by its own nested class");

        int[] counts = new int[3];
        var one = read(DexNormalizer.normalize(classes.get("p/Outer$1"), counts, demoted));
        assertNull(one.outerClass);
        assertNull(one.outerMethod);
        assertTrue(one.innerClasses.isEmpty());
        assertEquals(1, counts[2]);
        // Not inner any more: this$0 stays a plain field, so the constructor that sets it still compiles.
        assertEquals(0, one.fields.getFirst().access & Opcodes.ACC_SYNTHETIC);
        var outer = read(DexNormalizer.normalize(classes.get("p/Outer"), counts, demoted));
        assertEquals(List.of("p/Outer$2"), outer.innerClasses.stream().map(inner -> inner.name).toList());
        var other = read(DexNormalizer.normalize(classes.get("c/Other"), counts, demoted));
        assertTrue(other.innerClasses.isEmpty());

        var two = read(DexNormalizer.normalize(classes.get("p/Outer$2"), new int[3], demoted));
        assertEquals("e", two.outerMethod);
        assertNotEquals(0, two.fields.getFirst().access & Opcodes.ACC_SYNTHETIC, "still anonymous: this$0 is synthetic again");
    }

    /** R8's lambda class calls Outer's synthetic $r8$lambda$ method: it is shown; an access$ method used by Outer$Inner stays hidden. */
    @Test void syntheticMethodsCalledFromAnotherNestAreShown() {
        var classes = new TreeMap<String, byte[]>();
        classes.put("p/Outer", withSynthetic("p/Outer", List.of("$r8$lambda$abc", "access$000"), "p/Outer$Inner"));
        classes.put("p/Outer$Inner", caller("p/Outer$Inner", "p/Outer", "access$000", true));
        classes.put("p/Outer$$ExternalSyntheticLambda0", caller("p/Outer$$ExternalSyntheticLambda0", "p/Outer", "$r8$lambda$abc", false));
        var across = DexNormalizer.calledAcrossNests(classes);
        assertEquals(Set.of("p/Outer.$r8$lambda$abc()V"), across);
        int[] counts = new int[4];
        var outer = read(DexNormalizer.normalize(classes.get("p/Outer"), counts, Set.of(), across));
        var access = new TreeMap<String, Integer>();
        outer.methods.forEach(m -> access.put(m.name, m.access & Opcodes.ACC_SYNTHETIC));
        assertEquals(0, access.get("$r8$lambda$abc"));
        assertNotEquals(0, access.get("access$000"));
        assertEquals(1, counts[3]);
    }

    private static byte[] withSynthetic(String name, List<String> methods, String inner) {
        var writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V1_6, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, name, null, "java/lang/Object", null);
        writer.visitInnerClass(inner, name, inner.substring(inner.lastIndexOf('$') + 1), Opcodes.ACC_STATIC);
        for (String method : methods) {
            var m = writer.visitMethod(Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC, method, "()V", null, null);
            m.visitCode(); m.visitInsn(Opcodes.RETURN); m.visitMaxs(0, 0); m.visitEnd();
        }
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] caller(String name, String owner, String method, boolean nested) {
        var writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V1_6, Opcodes.ACC_FINAL | Opcodes.ACC_SUPER, name, null, "java/lang/Object", null);
        if (nested) writer.visitInnerClass(name, owner, name.substring(name.lastIndexOf('$') + 1), Opcodes.ACC_STATIC);
        var m = writer.visitMethod(Opcodes.ACC_PUBLIC, "run", "()V", null, null);
        m.visitCode();
        m.visitMethodInsn(Opcodes.INVOKESTATIC, owner, method, "()V", false);
        m.visitInsn(Opcodes.RETURN); m.visitMaxs(0, 0); m.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    /** A top-level class with a field of the given type, as R8's lambda classes have. */
    private static byte[] holder(String name, String type) {
        var writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V1_6, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SYNTHETIC | Opcodes.ACC_SUPER, name, null, "java/lang/Object", null);
        writer.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL, "f$0", "L" + type + ";", null, null).visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static ClassNode read(byte[] bytes) {
        var node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }

    /** A class whose methods each create one anonymous class, listing them in InnerClasses as javac does. */
    private static byte[] outer(String name, List<String> inners, Map<String, String> creates) {
        var writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V1_6, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, name, null, "java/lang/Object", null);
        for (String inner : inners) writer.visitInnerClass(inner, null, null, 0);
        for (var entry : new TreeMap<>(creates).entrySet()) {
            var method = writer.visitMethod(Opcodes.ACC_PUBLIC, entry.getKey(), "()Ljava/lang/Object;", null, null);
            method.visitCode();
            method.visitTypeInsn(Opcodes.NEW, entry.getValue());
            method.visitInsn(Opcodes.DUP);
            method.visitInsn(Opcodes.ACONST_NULL);
            method.visitMethodInsn(Opcodes.INVOKESPECIAL, entry.getValue(), "<init>", "(Lp/Outer;)V", false);
            method.visitInsn(Opcodes.ARETURN);
            method.visitMaxs(0, 0);
            method.visitEnd();
        }
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] anonymous(String name, String owner, String method) {
        var writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V1_6, Opcodes.ACC_SUPER, name, null, "java/lang/Object", null);
        writer.visitOuterClass(owner, method, "()Ljava/lang/Object;");
        writer.visitInnerClass(name, null, null, 0);
        writer.visitField(Opcodes.ACC_FINAL, "this$0", "L" + owner + ";", null, null).visitEnd();
        var init = writer.visitMethod(0, "<init>", "(L" + owner + ";)V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitVarInsn(Opcodes.ALOAD, 1);
        init.visitFieldInsn(Opcodes.PUTFIELD, name, "this$0", "L" + owner + ";");
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }
}
