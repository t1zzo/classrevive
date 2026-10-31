package dev.classrevive.pipeline;

import dev.classrevive.cfr.CfrEngine;
import dev.classrevive.vineflower.VineflowerEngine;
import java.nio.file.*;
import java.time.Duration;
import java.util.List;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class HtmlReportTest {
    @TempDir Path dir;

    @Test void writesASelfContainedConfidenceMapBesideTheJsonReport() throws Exception {
        var source = Files.createDirectories(dir.resolve("src/p"));
        Files.writeString(source.resolve("Calc.java"), "package p; public class Calc { public int twice(int x) { return x * 2; }"
            + " public static class Inner { public String name() { return \"inner\"; } } }");
        var classes = dir.resolve("classes");
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "--release", "8", "-Xlint:-options", "-proc:none",
            "-d", classes.toString(), source.resolve("Calc.java").toString()));
        var jar = dir.resolve("calc.jar");
        try (var zip = new java.util.zip.ZipOutputStream(Files.newOutputStream(jar))) {
            for (String name : List.of("p/Calc.class", "p/Calc$Inner.class")) {
                zip.putNextEntry(new java.util.zip.ZipEntry(name)); zip.write(Files.readAllBytes(classes.resolve(name))); zip.closeEntry();
            }
        }
        var output = dir.resolve("out");
        var job = new DecompilationJob().runVerified(jar, output, List.of(new CfrEngine(), new VineflowerEngine()), Duration.ofSeconds(60));
        String html = Files.readString(output.resolve("report.html"));
        assertTrue(html.startsWith("<!doctype html>"), html);
        assertTrue(html.contains("calc.jar") && html.contains("<code>p</code>"), html);
        assertTrue(html.contains("<code>Calc</code>") && html.contains("<code>Calc$Inner</code>"), html);
        // Both classes recompile with every signature and every body identical or equivalent.
        assertEquals(2, html.split("<span class=\"pill exact\">exact</span>", -1).length - 1, html);
        assertTrue(html.contains(job.selection().verification().matchedSymbols() + "/" + job.selection().verification().expectedSymbols()), html);
        // Self-contained: no script and no external resource.
        assertFalse(html.contains("<script") || html.contains("http://") || html.contains("https://") || html.contains("src="), html);
        assertTrue(html.contains("not proof of behavior"), html);
    }

    @Test void escapesEveryNameTakenFromTheInput() {
        assertEquals("a&lt;b&gt;&amp;&quot;c&quot;&#39;d&#7;", HtmlReport.escape("a<b>&\"c\"'d\u0007"));
    }
}
