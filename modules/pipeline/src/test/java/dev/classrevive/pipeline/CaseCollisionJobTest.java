package dev.classrevive.pipeline;

import dev.classrevive.cfr.CfrEngine;
import dev.classrevive.engine.DecompilerEngine;
import dev.classrevive.vineflower.VineflowerEngine;
import java.io.IOException;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.stream.Stream;
import java.util.zip.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/**
 * Phase 2BV: classes {@code p/A} and {@code p/a} (plus {@code p/a$Box}) that differ only in letter case and refer to each
 * other, built here with ASM because javac cannot write both on a case-insensitive file system. Nothing runs.
 */
class CaseCollisionJobTest {
    @TempDir Path directory;
    List<DecompilerEngine> engines() { return List.of(new CfrEngine(), new VineflowerEngine()); }

    static void constructor(ClassWriter writer) {
        var init = writer.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode(); init.visitVarInsn(ALOAD, 0); init.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(RETURN); init.visitMaxs(1, 1); init.visitEnd();
    }
    static MethodVisitor constant(ClassWriter writer, int value) {
        var method = writer.visitMethod(ACC_PUBLIC, "value", "()I", null, null);
        method.visitCode(); method.visitIntInsn(BIPUSH, value); method.visitInsn(IRETURN); method.visitMaxs(1, 1); method.visitEnd();
        return method;
    }
    /** {@code public class A { public a other; int value() { return 1; } int sum(a x) { return x.value() + value(); } }} */
    static byte[] upper() {
        var writer = new ClassWriter(0); writer.visit(V1_8, ACC_PUBLIC | ACC_SUPER, "p/A", null, "java/lang/Object", null);
        writer.visitField(ACC_PUBLIC, "other", "Lp/a;", null, null).visitEnd();
        constructor(writer); constant(writer, 1);
        var sum = writer.visitMethod(ACC_PUBLIC, "sum", "(Lp/a;)I", null, null);
        sum.visitCode(); sum.visitVarInsn(ALOAD, 1); sum.visitMethodInsn(INVOKEVIRTUAL, "p/a", "value", "()I", false);
        sum.visitVarInsn(ALOAD, 0); sum.visitMethodInsn(INVOKEVIRTUAL, "p/A", "value", "()I", false);
        sum.visitInsn(IADD); sum.visitInsn(IRETURN); sum.visitMaxs(2, 2); sum.visitEnd();
        writer.visitEnd(); return writer.toByteArray();
    }
    /** {@code public class a { int value() { return 2; } static A make() { return new A(); } public static class Box { public A held; } }} */
    static byte[] lower() {
        var writer = new ClassWriter(0); writer.visit(V1_8, ACC_PUBLIC | ACC_SUPER, "p/a", null, "java/lang/Object", null);
        writer.visitInnerClass("p/a$Box", "p/a", "Box", ACC_PUBLIC | ACC_STATIC);
        constructor(writer); constant(writer, 2);
        var make = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "make", "()Lp/A;", null, null);
        make.visitCode(); make.visitTypeInsn(NEW, "p/A"); make.visitInsn(DUP);
        make.visitMethodInsn(INVOKESPECIAL, "p/A", "<init>", "()V", false); make.visitInsn(ARETURN); make.visitMaxs(2, 0); make.visitEnd();
        writer.visitEnd(); return writer.toByteArray();
    }
    static byte[] box() {
        var writer = new ClassWriter(0); writer.visit(V1_8, ACC_PUBLIC | ACC_SUPER, "p/a$Box", null, "java/lang/Object", null);
        writer.visitInnerClass("p/a$Box", "p/a", "Box", ACC_PUBLIC | ACC_STATIC);
        writer.visitField(ACC_PUBLIC, "held", "Lp/A;", null, null).visitEnd();
        constructor(writer); writer.visitEnd(); return writer.toByteArray();
    }
    Path jar() throws IOException {
        Path jar = directory.resolve("colliding.jar");
        try (var zip = new ZipOutputStream(Files.newOutputStream(jar))) {
            for (var entry : List.of(Map.entry("p/A.class", upper()), Map.entry("p/a.class", lower()), Map.entry("p/a$Box.class", box()),
                    Map.entry("p/readme.txt", "first".getBytes()), Map.entry("p/README.txt", "second".getBytes()))) {
                zip.putNextEntry(new ZipEntry(entry.getKey())); zip.write(entry.getValue()); zip.closeEntry();
            }
        }
        return jar;
    }

    @Test void renamesClassesApartThatDifferOnlyInCaseAndRecompilesThem() throws Exception {
        Path out = directory.resolve("out");
        var job = new DecompilationJob().runVerified(jar(), out, engines(), Duration.ofSeconds(60));
        assertEquals(Map.of("p/a", "p/a_cr2", "p/a$Box", "p/a_cr2$Box"), job.caseRenames());
        var verification = job.selection().verification();
        assertTrue(verification.complete(), job.selection().toString());
        try (var zip = new ZipFile(out.resolve("classes.jar").toFile())) {
            assertEquals(List.of("p/A.class", "p/a_cr2$Box.class", "p/a_cr2.class"), zip.stream().map(ZipEntry::getName).sorted().toList());
        }
        assertEquals("class p/a = p/a_cr2", Files.readAllLines(out.resolve("portable-names/mapping.txt")).stream()
            .filter(line -> line.startsWith("class p/a ")).findFirst().orElseThrow());
        for (String engine : List.of("cfr", "vineflower")) {
            String upper = Files.readString(out.resolve(engine).resolve("sources/p/A.java"));
            assertTrue(upper.contains("a_cr2"), upper);
        }
        // Every selected source has its own path ignoring case, so the round trip compiled on this file system.
        try (Stream<Path> files = Files.walk(out.resolve("selection/sources"))) {
            var names = files.filter(Files::isRegularFile).map(path -> path.toString().toLowerCase(Locale.ROOT)).toList();
            assertEquals(names.size(), new HashSet<>(names).size(), names.toString());
            assertTrue(names.stream().anyMatch(name -> name.endsWith("a_cr2.java")), names.toString());
        }
        String report = Files.readString(out.resolve("report.txt"));
        assertTrue(report.contains("Portable class names: 2 class(es) renamed"), report);
        assertTrue(report.contains("  p/a -> p/a_cr2\n"), report);
        assertTrue(report.contains("1 resource path(s) colliding by case left out"), report);
        assertTrue(report.contains("Result: COMPILED_WITH_SIGNATURE_COVERAGE"), report);
        assertTrue(Files.readString(out.resolve("selection/selection.txt")).contains("  p/a$Box -> p/a_cr2$Box\n"));
        String json = Files.readString(out.resolve("report.json"));
        assertTrue(json.contains("\"portable_names\""), json);
        assertTrue(json.contains("\"p/a\":\"p/a_cr2\""), json);
        assertTrue(json.contains("\"inventory_notices\""), json);
        assertTrue(json.contains("\"code\":\"CASE_COLLISION_SKIPPED\",\"entry\":\"p/README.txt\""), json);
        assertTrue(json.contains("Class entries differ only in letter case: p/A.class, p/a.class"), json);
    }

    @Test void renameStillRefusesCaseCollisions() throws Exception {
        Path input = jar();
        var error = assertThrows(IOException.class, () -> new RenameJob().run(input, directory.resolve("renamed"), Duration.ofSeconds(30)));
        assertTrue(error.getMessage().contains("supported by decompile only"), error.getMessage());
    }
}
