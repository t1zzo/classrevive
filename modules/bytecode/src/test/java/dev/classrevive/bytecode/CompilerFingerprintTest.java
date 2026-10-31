package dev.classrevive.bytecode;

import java.nio.file.*;
import java.util.*;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.*;
import static org.junit.jupiter.api.Assertions.*;

class CompilerFingerprintTest {
    @TempDir Path dir;

    private List<byte[]> javac(String release, boolean debug, String name, String code) throws Exception {
        var source = Files.createDirectories(dir.resolve("src-" + name)).resolve(name + ".java");
        Files.writeString(source, code);
        var classes = Files.createDirectories(dir.resolve("classes-" + name));
        var args = new ArrayList<>(List.of("--release", release, "-Xlint:-options", "-proc:none", "-d", classes.toString()));
        if (debug) args.add("-g");
        args.add(source.toString());
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, args.toArray(String[]::new)));
        var result = new ArrayList<byte[]>();
        try (var paths = Files.walk(classes)) { for (var path : paths.filter(p -> p.toString().endsWith(".class")).toList()) result.add(Files.readAllBytes(path)); }
        return result;
    }

    private static final String SHAPES = "public class Shapes { enum Color { RED, GREEN }"
        + " static int code(Color c) { switch (c) { case RED: return 1; default: return 2; } }"
        + " static Runnable task(String who) { return () -> System.out.println(\"hi \" + who); } }";

    @Test void recognizesJavacAndItsLowerBoundAndDebugInformation() throws Exception {
        var modern = CompilerFingerprint.analyze(javac("17", true, "Shapes", SHAPES));
        assertEquals("Java", modern.language()); assertEquals("javac", modern.javaCompiler());
        assertEquals(15, modern.javacAtLeast(), "the enum's synthetic $values() is javac 15+");
        assertEquals(0, modern.ecjSignals()); assertTrue(modern.javacSignals() >= 2, String.valueOf(modern.javacSignals()));
        assertEquals(Map.of(17, modern.classes()), modern.classVersions());
        assertEquals(modern.withCode(), modern.withLocalVariables(), "-g keeps local variable tables");
        // Release 8 without -g: the lower bound is about the compiler, not the target. javac 21 still writes $values()
        // (no indy concatenation for release 8), and without -g there are no local variable names.
        var old = CompilerFingerprint.analyze(javac("8", false, "Old", SHAPES.replace("Shapes", "Old")));
        assertEquals("javac", old.javaCompiler()); assertEquals(15, old.javacAtLeast());
        assertEquals(Map.of(8, old.classes()), old.classVersions());
        assertEquals(0, old.withLocalVariables()); assertEquals(old.withCode(), old.withLineNumbers(), "javac keeps line numbers by default");
        String text = CompilerFingerprint.text(old);
        assertTrue(text.startsWith("Compiler fingerprint: Java; Java classes by javac 15+ (javac signals in 2, ecj signals in 0 classes)"), text);
        assertTrue(text.contains("Class files: Java 8 (2)"), text);
        assertTrue(text.contains("decompilers invent them"), text);
    }

    @Test void recognizesEcjFromItsNamingOfLambdasSwitchTablesAndEnumValues() {
        var writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "p/Ecj", null, "java/lang/Object", null);
        writer.visitMethod(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC, "lambda$0", "()V", null, null).visitEnd();
        writer.visitMethod(Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC, "$SWITCH_TABLE$p$Color", "()[I", null, null).visitEnd();
        writer.visitEnd();
        var result = CompilerFingerprint.analyze(List.of(writer.toByteArray(), ecjEnum()));
        assertEquals("ecj", result.javaCompiler()); assertEquals(2, result.ecjSignals()); assertEquals(0, result.javacAtLeast());
        var mixed = CompilerFingerprint.analyze(List.of(writer.toByteArray(), plain("p/Javac", "lambda$run$0")));
        assertEquals("mixed", mixed.javaCompiler());
    }

    @Test void recognizesKotlinScalaAndGroovyClasses() {
        var kotlin = new ClassWriter(0);
        kotlin.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "k/Main", null, "java/lang/Object", null);
        kotlin.visitAnnotation("Lkotlin/Metadata;", true).visitEnd();
        kotlin.visitEnd();
        var scala = new ClassWriter(0);
        scala.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "s/Main", null, "java/lang/Object", null);
        scala.visitAttribute(new Attribute("ScalaSig") {
            @Override protected ByteVector write(ClassWriter classWriter, byte[] code, int codeLength, int maxStack, int maxLocals) {
                return new ByteVector().putByte(5).putByte(0);
            }
        });
        scala.visitEnd();
        var groovy = new ClassWriter(0);
        groovy.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "g/Script", null, "java/lang/Object", new String[]{"groovy/lang/GroovyObject"});
        groovy.visitEnd();
        var kotlinToo = new ClassWriter(0);
        kotlinToo.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "k/Other", null, "java/lang/Object", null);
        kotlinToo.visitAnnotation("Lkotlin/Metadata;", true).visitEnd();
        kotlinToo.visitEnd();
        var result = CompilerFingerprint.analyze(List.of(kotlin.toByteArray(), kotlinToo.toByteArray(), scala.toByteArray(), groovy.toByteArray()));
        assertEquals(2, result.kotlin()); assertEquals(1, result.scala()); assertEquals(1, result.groovy()); assertEquals(0, result.java());
        assertEquals("Kotlin", result.language()); assertEquals("undetermined", result.javaCompiler());
        assertTrue(CompilerFingerprint.text(result).contains("Kotlin classes decompile to Java"));
        assertEquals("Kotlin", CompilerFingerprint.json(result).get("language"));
    }

    private static byte[] plain(String name, String syntheticMethod) {
        var writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
        writer.visitMethod(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC, syntheticMethod, "()V", null, null).visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    /** An enum whose values() copies $VALUES with System.arraycopy, as ecj compiles it. */
    private static byte[] ecjEnum() {
        var writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_ENUM | Opcodes.ACC_FINAL, "p/Color", null, "java/lang/Enum", null);
        var values = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "values", "()[Lp/Color;", null, null);
        values.visitCode();
        values.visitInsn(Opcodes.ICONST_0); values.visitTypeInsn(Opcodes.ANEWARRAY, "p/Color");
        values.visitInsn(Opcodes.DUP); values.visitInsn(Opcodes.ICONST_0); values.visitInsn(Opcodes.ACONST_NULL); values.visitInsn(Opcodes.ICONST_0); values.visitInsn(Opcodes.ICONST_0);
        values.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/System", "arraycopy", "(Ljava/lang/Object;ILjava/lang/Object;II)V", false);
        values.visitInsn(Opcodes.ARETURN);
        values.visitMaxs(0, 0); values.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }
}
