package dev.classrevive.bytecode;

import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.zip.*;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.*;
import static org.junit.jupiter.api.Assertions.*;

class LinkCheckTest {
    @TempDir Path dir;

    Path jar(String name, Map<String,byte[]> classes) throws Exception {
        Path jar = dir.resolve(name);
        try (var zip = new ZipOutputStream(Files.newOutputStream(jar))) {
            for (var entry : classes.entrySet()) { zip.putNextEntry(new ZipEntry(entry.getKey() + ".class")); zip.write(entry.getValue()); zip.closeEntry(); }
        }
        return jar;
    }

    /** {@code static int m()} whose body is {@code aconst_null; ireturn}: ASM writes it, the JVM verifier must reject it. */
    static byte[] invalid(String name, String superName) {
        var writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, name, null, superName, null);
        var method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "m", "()I", null, null);
        method.visitCode(); method.visitInsn(Opcodes.ACONST_NULL); method.visitInsn(Opcodes.IRETURN); method.visitMaxs(0, 0); method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    static byte[] empty(String name, String superName) {
        var writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, name, null, superName, null);
        writer.visitEnd();
        return writer.toByteArray();
    }

    @Test void linksValidClassesWithoutRunningTheirInitializer() throws Exception {
        Path marker = dir.resolve("TARGET_EXECUTED");
        Path source = dir.resolve("demo/Loud.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "package demo; public class Loud { static { try { java.nio.file.Files.writeString(java.nio.file.Path.of("
            + "\"" + marker.toString().replace("\\", "\\\\") + "\"), \"ran\"); } catch (Exception e) { throw new RuntimeException(e); } }"
            + " public int value() { return 1; } }");
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "--release", "17", "-proc:none", "-d", dir.resolve("out").toString(), source.toString()));
        var classes = Map.of("demo/Loud", Files.readAllBytes(dir.resolve("out/demo/Loud.class")));
        var result = new LinkCheck().check(jar("loud.jar", classes), dir.resolve("link"), List.of(), Duration.ofSeconds(30));
        assertTrue(result.linked(), result.toString());
        assertEquals(1, result.classes());
        assertFalse(Files.exists(marker), "linking must not initialize the class");
    }

    @Test void rejectsBytecodeTheVerifierRefuses() throws Exception {
        var result = new LinkCheck().check(jar("bad.jar", Map.of("demo/Bad", invalid("demo/Bad", "java/lang/Object"))), dir.resolve("link"), List.of(), Duration.ofSeconds(30));
        assertFalse(result.linked());
        assertEquals("java.lang.VerifyError", result.errorType(), result.toString());
        assertEquals("demo/Bad", result.failedClass());
    }

    @Test void resolvesSupertypesFromTheCaseOrItsDependenciesOnly() throws Exception {
        var child = Map.of("demo/Child", empty("demo/Child", "demo/Base"));
        var alone = new LinkCheck().check(jar("child.jar", child), dir.resolve("alone"), List.of(), Duration.ofSeconds(30));
        assertFalse(alone.linked());
        assertEquals("java.lang.NoClassDefFoundError", alone.errorType(), alone.toString());
        Path base = jar("base.jar", Map.of("demo/Base", empty("demo/Base", "java/lang/Object")));
        var linked = new LinkCheck().check(dir.resolve("child.jar"), dir.resolve("with-base"), List.of(base), Duration.ofSeconds(30));
        assertTrue(linked.linked(), linked.toString());
        assertEquals(1, linked.classes());
        // The worker itself is not visible to the case: a class extending it cannot link.
        var worker = Map.of("demo/Sneaky", empty("demo/Sneaky", "dev/classrevive/bytecode/LinkWorker"));
        assertFalse(new LinkCheck().check(jar("sneaky.jar", worker), dir.resolve("sneaky"), List.of(), Duration.ofSeconds(30)).linked());
    }

    @Test void workerSelfTestSeesTheVerifierActive() throws Exception {
        assertTrue(LinkWorker.verifierActive());
    }
}
