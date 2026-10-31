package dev.classrevive.pipeline;

import dev.classrevive.cfr.CfrEngine;
import dev.classrevive.engine.DecompilerEngine;
import dev.classrevive.vineflower.VineflowerEngine;
import java.io.IOException;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.zip.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.ClassNode;
import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/**
 * Phase 2BW: a native-stub shape seen in obfuscated jars, built with ASM because javac cannot write it. {@code p/Stub} declares
 * {@code private static native void 0a2b4acb()}, which its static initializer calls after {@code p/Loader.register(Class)};
 * a private method {@code 1sum()} reads a private field {@code 2count}, and the public {@code total()} calls it. Nothing runs.
 */
class MemberNameJobTest {
    @TempDir Path directory;
    List<DecompilerEngine> engines() { return List.of(new CfrEngine(), new VineflowerEngine()); }

    static byte[] loader() {
        var writer = new ClassWriter(0); writer.visit(V1_8, ACC_PUBLIC | ACC_SUPER, "p/Loader", null, "java/lang/Object", null);
        CaseCollisionJobTest.constructor(writer);
        var register = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "register", "(Ljava/lang/Class;)V", null, null);
        register.visitCode(); register.visitInsn(RETURN); register.visitMaxs(0, 1); register.visitEnd();
        writer.visitEnd(); return writer.toByteArray();
    }
    static byte[] stub() {
        var writer = new ClassWriter(0); writer.visit(V1_8, ACC_PUBLIC | ACC_SUPER, "p/Stub", null, "java/lang/Object", null);
        writer.visitField(ACC_PRIVATE | ACC_STATIC, "2count", "I", null, null).visitEnd();
        CaseCollisionJobTest.constructor(writer);
        writer.visitMethod(ACC_PRIVATE | ACC_STATIC | ACC_NATIVE, "0a2b4acb", "()V", null, null).visitEnd();
        var sum = writer.visitMethod(ACC_PRIVATE | ACC_STATIC, "1sum", "(I)I", null, null);
        sum.visitCode(); sum.visitFieldInsn(GETSTATIC, "p/Stub", "2count", "I"); sum.visitVarInsn(ILOAD, 0); sum.visitInsn(IADD);
        sum.visitInsn(IRETURN); sum.visitMaxs(2, 1); sum.visitEnd();
        var total = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "total", "()I", null, null);
        total.visitCode(); total.visitInsn(ICONST_3); total.visitMethodInsn(INVOKESTATIC, "p/Stub", "1sum", "(I)I", false);
        total.visitInsn(IRETURN); total.visitMaxs(1, 0); total.visitEnd();
        var init = writer.visitMethod(ACC_STATIC, "<clinit>", "()V", null, null);
        init.visitCode(); init.visitLdcInsn(org.objectweb.asm.Type.getObjectType("p/Stub"));
        init.visitMethodInsn(INVOKESTATIC, "p/Loader", "register", "(Ljava/lang/Class;)V", false);
        init.visitMethodInsn(INVOKESTATIC, "p/Stub", "0a2b4acb", "()V", false);
        init.visitInsn(RETURN); init.visitMaxs(1, 0); init.visitEnd();
        writer.visitEnd(); return writer.toByteArray();
    }
    Path jar() throws IOException {
        Path jar = directory.resolve("stubs.jar");
        try (var zip = new ZipOutputStream(Files.newOutputStream(jar))) {
            for (var entry : List.of(Map.entry("p/Loader.class", loader()), Map.entry("p/Stub.class", stub()))) {
                zip.putNextEntry(new ZipEntry(entry.getKey())); zip.write(entry.getValue()); zip.closeEntry();
            }
        }
        return jar;
    }

    @Test void renamesNonIdentifierPrivateMembersAndRecompilesThem() throws Exception {
        Path out = directory.resolve("out");
        var job = new DecompilationJob().runVerified(jar(), out, engines(), Duration.ofSeconds(60));
        assertEquals(Map.of(), job.caseRenames());
        assertEquals(Map.of("p/Stub#0a2b4acb()V", "_0a2b4acb", "p/Stub#1sum(I)I", "_1sum", "p/Stub#2count:I", "_2count"), job.memberRenames());
        assertTrue(job.selection().verification().complete(), job.selection().toString());
        try (var zip = new ZipFile(out.resolve("classes.jar").toFile())) {
            var node = new ClassNode(); new ClassReader(zip.getInputStream(zip.getEntry("p/Stub.class")).readAllBytes()).accept(node, 0);
            assertEquals(List.of("<init>", "_0a2b4acb", "_1sum", "total", "<clinit>"), node.methods.stream().map(m -> m.name).toList());
            assertEquals("_2count", node.fields.getFirst().name);
        }
        assertTrue(Files.readAllLines(out.resolve("portable-names/mapping.txt")).contains("method p/Stub#0a2b4acb()V = _0a2b4acb"));
        for (String engine : List.of("cfr", "vineflower")) {
            String source = Files.readString(out.resolve(engine).resolve("sources/p/Stub.java"));
            assertTrue(source.contains("_0a2b4acb();"), source);
            assertFalse(source.contains(" 0a2b4acb"), source);
        }
        String report = Files.readString(out.resolve("report.txt"));
        assertTrue(report.contains("Portable member names: 3 private member(s) renamed"), report);
        assertTrue(report.contains("  p/Stub#0a2b4acb()V -> _0a2b4acb\n"), report);
        assertFalse(report.contains("Portable class names"), report);
        assertTrue(report.contains("Result: COMPILED_WITH_SIGNATURE_COVERAGE"), report);
        assertTrue(Files.readString(out.resolve("selection/selection.txt")).contains("  p/Stub#2count:I -> _2count\n"));
        String json = Files.readString(out.resolve("report.json"));
        assertTrue(json.contains("\"renamed_members\""), json);
        assertTrue(json.contains("\"p/Stub#1sum(I)I\":\"_1sum\""), json);
        assertTrue(json.contains("\"mapping_file\":\"portable-names/mapping.txt\""), json);
    }

    @Test void leavesNonPrivateMembersUnchangedAndReportsThem() throws Exception {
        var writer = new ClassWriter(0); writer.visit(V1_8, ACC_PUBLIC | ACC_SUPER, "p/Open", null, "java/lang/Object", null);
        writer.visitField(ACC_PUBLIC | ACC_STATIC, "3open", "I", null, null).visitEnd(); writer.visitEnd();
        Path jar = directory.resolve("open.jar");
        try (var zip = new ZipOutputStream(Files.newOutputStream(jar))) {
            zip.putNextEntry(new ZipEntry("p/Open.class")); zip.write(writer.toByteArray()); zip.closeEntry();
        }
        Path out = directory.resolve("open");
        var job = new DecompilationJob().runVerified(jar, out, List.of(new CfrEngine()), Duration.ofSeconds(60));
        assertEquals(Map.of(), job.memberRenames());
        assertFalse(Files.exists(out.resolve("portable-names/mapping.txt")));
        String report = Files.readString(out.resolve("report.txt"));
        assertTrue(report.contains("1 member name(s) that are no Java identifiers left unchanged"), report);
        assertTrue(report.contains("p/Open#3open:I (not private"), report);
        assertTrue(Files.readString(out.resolve("report.json")).contains("\"members_left_unchanged\""));
    }
}
