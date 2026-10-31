package dev.classrevive.pipeline;

import dev.classrevive.engine.*;
import dev.classrevive.vineflower.VineflowerEngine;
import java.nio.file.*;
import java.time.Duration;
import java.util.zip.*;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Phase 2DG: a class carrying {@code @kotlin.Metadata} must come out of Vineflower as Java, not as a .kt file. */
class KotlinInputTest {
    @TempDir Path directory;
    @Test void vineflowerWritesJavaForKotlinClasses() throws Exception {
        // A stand-in for kotlinc's annotation; Vineflower's Kotlin plugin chooses classes by its name alone.
        var kotlin = Files.createDirectories(directory.resolve("src/kotlin")).resolve("Metadata.java");
        Files.writeString(kotlin, """
            package kotlin;
            @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
            public @interface Metadata { int k() default 1; int[] mv() default {}; String[] d1() default {}; String[] d2() default {}; }
            """);
        var main = Files.createDirectories(directory.resolve("src/k")).resolve("Main.java");
        Files.writeString(main, """
            package k;
            @kotlin.Metadata(k = 1, mv = {2, 0, 0})
            public final class Main { public int add(int a, int b) { return a + b; } }
            """);
        var classes = directory.resolve("classes");
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "--release", "8", "-proc:none", "-d", classes.toString(),
            kotlin.toString(), main.toString()));
        var jar = directory.resolve("input.jar");
        try (var out = new ZipOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new ZipEntry("k/Main.class")); out.write(Files.readAllBytes(classes.resolve("k/Main.class"))); out.closeEntry();
        }
        var result = new ProcessRunner().run(new VineflowerEngine(), jar, directory.resolve("vineflower"), Duration.ofSeconds(60));
        assertEquals(EngineResult.Status.OUTPUT_PRODUCED, result.status(), result.toString());
        var sources = directory.resolve("vineflower/sources");
        assertTrue(Files.readString(sources.resolve("k/Main.java")).contains("public int add("));
        assertFalse(Files.exists(sources.resolve("k/Main.kt")));
    }
    @Test void enginePreferenceNamesTheSourceFilesOfKotlinClasses() throws Exception {
        var jar = directory.resolve("classes.jar");
        try (var out = new ZipOutputStream(Files.newOutputStream(jar))) {
            for (var name : new String[] {"k/Main", "k/Main$Companion", "k/Other", "j/Util"}) {
                var writer = new org.objectweb.asm.ClassWriter(0);
                writer.visit(org.objectweb.asm.Opcodes.V1_8, org.objectweb.asm.Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
                if (name.startsWith("k/")) writer.visitAnnotation("Lkotlin/Metadata;", true).visitEnd();
                writer.visitEnd();
                out.putNextEntry(new ZipEntry(name + ".class")); out.write(writer.toByteArray()); out.closeEntry();
            }
        }
        try (var out = new ZipOutputStream(Files.newOutputStream(directory.resolve("none.jar")))) {
            var writer = new org.objectweb.asm.ClassWriter(0);
            writer.visit(org.objectweb.asm.Opcodes.V1_8, org.objectweb.asm.Opcodes.ACC_PUBLIC, "j/Util", null, "java/lang/Object", null);
            writer.visitEnd();
            out.putNextEntry(new ZipEntry("j/Util.class")); out.write(writer.toByteArray()); out.closeEntry();
        }
        var preference = DecompilationJob.enginePreference(jar);
        assertEquals(java.util.Set.of("k/Main.java", "k/Other.java"), preference.kotlinFiles());
        assertTrue(preference.reason().contains("jadx is tried first"), preference.reason());
        assertEquals(dev.classrevive.selection.EnginePreference.NONE, DecompilationJob.enginePreference(directory.resolve("none.jar")));
    }
}
