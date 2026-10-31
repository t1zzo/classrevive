package dev.classrevive.jadx;

import dev.classrevive.engine.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.zip.*;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class JadxEngineTest {
    @TempDir Path directory;
    @Test void emitsJavaWithoutExecutingInitializer() throws Exception {
        Path source = directory.resolve("Example.java");
        Files.writeString(source, "public class Example { static { if (System.nanoTime() != 0) throw new Error(); } public int value() { return 42; } }");
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null,null,null,"--release","8","-proc:none",source.toString()));
        Path jar = directory.resolve("input.jar");
        try (var zip = new ZipOutputStream(Files.newOutputStream(jar))) {
            zip.putNextEntry(new ZipEntry("Example.class")); zip.write(Files.readAllBytes(directory.resolve("Example.class"))); zip.closeEntry();
        }
        var result = new ProcessRunner().run(new JadxEngine(), jar, directory.resolve("out"), Duration.ofSeconds(30));
        assertEquals(EngineResult.Status.OUTPUT_PRODUCED, result.status(), Files.readString(directory.resolve("out/engine.log")));
        var generated = directory.resolve("out/sources/Example.java");
        assertTrue(Files.readString(generated).contains("int value("));
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null,null,null,"-proc:none",generated.toString()));
    }
}
