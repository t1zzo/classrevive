package dev.classrevive.procyon;

import dev.classrevive.engine.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.zip.*;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ProcyonEngineTest {
    @TempDir Path directory;
    @Test void retainsOrphanNestedClassAsPartialSource() throws Exception {
        Path source = directory.resolve("Outer.java");
        Files.writeString(source, "public class Outer { public static class Inner { public int value() { return 7; } } }");
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null,null,null,"--release","8","-proc:none",source.toString()));
        Path jar = directory.resolve("orphan.jar");
        try (var zip = new ZipOutputStream(Files.newOutputStream(jar))) {
            zip.putNextEntry(new ZipEntry("Outer$Inner.class")); zip.write(Files.readAllBytes(directory.resolve("Outer$Inner.class"))); zip.closeEntry();
        }
        var result = new ProcessRunner().run(new ProcyonEngine(), jar, directory.resolve("out"), Duration.ofSeconds(30));
        assertTrue(result.sourceFiles() > 0, result.toString());
        assertTrue(Files.readString(directory.resolve("out/sources/Outer$Inner.java")).contains("value("));
    }
    @Test void decompilesNestedClassFamilyWithoutExecutingInitializer() throws Exception {
        Path source = directory.resolve("Example.java");
        Files.writeString(source, "public class Example { static { if (System.nanoTime() != 0) throw new Error(); } public static class Nested { public int value() { return 42; } } }");
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null,null,null,"--release","8","-proc:none",source.toString()));
        Path jar = directory.resolve("input.jar");
        try (var zip = new ZipOutputStream(Files.newOutputStream(jar))) {
            for (String name : new String[]{"Example", "Example$Nested"}) {
                zip.putNextEntry(new ZipEntry(name + ".class")); zip.write(Files.readAllBytes(directory.resolve(name + ".class"))); zip.closeEntry();
            }
        }
        var result = new ProcessRunner().run(new ProcyonEngine(), jar, directory.resolve("out"), Duration.ofSeconds(30));
        assertEquals(EngineResult.Status.OUTPUT_PRODUCED, result.status(), result.toString());
        Path generated = directory.resolve("out/sources/Example.java");
        assertTrue(Files.readString(generated).contains("class Nested"));
        assertFalse(Files.exists(directory.resolve("out/sources/Example$Nested.java")));
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null,null,null,"-proc:none",generated.toString()));
    }
}
