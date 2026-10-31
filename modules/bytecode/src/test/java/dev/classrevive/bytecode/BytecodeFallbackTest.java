package dev.classrevive.bytecode;

import dev.classrevive.engine.EngineResult;
import java.nio.file.*;
import java.time.Duration;
import java.util.zip.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class BytecodeFallbackTest {
    @TempDir Path directory;
    @Test void exportsActualInstructionsWithoutExecutingClass() throws Exception {
        var src = directory.resolve("Code.java");
        Files.writeString(src, "public class Code { public int add(int a,int b) { return a+b; } }");
        assertEquals(0, javax.tools.ToolProvider.getSystemJavaCompiler().run(null,null,null,"--release","8","-proc:none",src.toString()));
        var jar = directory.resolve("input.jar");
        try(var zip = new ZipOutputStream(Files.newOutputStream(jar))) {
            zip.putNextEntry(new ZipEntry("Code.class")); zip.write(Files.readAllBytes(directory.resolve("Code.class"))); zip.closeEntry();
        }
        var result = new BytecodeFallback().dump(jar, directory.resolve("fallback"), Duration.ofSeconds(20));
        assertEquals(EngineResult.Status.OUTPUT_PRODUCED,result.status());
        String text = Files.readString(directory.resolve("fallback/bytecode/00001.txt"));
        assertTrue(text.contains("IADD")); assertTrue(text.contains("add(II)I"));
    }
}
