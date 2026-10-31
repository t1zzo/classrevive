package dev.classrevive.pipeline;

import dev.classrevive.cfr.CfrEngine;
import dev.classrevive.engine.DecompilerEngine;
import dev.classrevive.vineflower.VineflowerEngine;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class CacheAndResumeTest {
    @TempDir Path dir;

    private Path compile(String name, String code) throws Exception {
        var source = Files.createDirectories(dir.resolve("src-" + name));
        Files.writeString(source.resolve(name + ".java"), code);
        var classes = Files.createDirectories(dir.resolve("classes-" + name));
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "--release", "8", "-Xlint:-options", "-proc:none",
            "-d", classes.toString(), source.resolve(name + ".java").toString()));
        return classes.resolve(name + ".class");
    }

    @Test void reusesEngineOutputsForTheSameInputAndEngineOnly() throws Exception {
        var input = compile("Calc", "public class Calc { public int twice(int x) { return x * 2; } }");
        var cache = dir.resolve("engine-cache");
        var engines = List.<dev.classrevive.engine.DecompilerEngine>of(new CfrEngine(), new VineflowerEngine());
        var first = new DecompilationJob().withEngineCache(cache).runVerified(input, dir.resolve("first"), engines, Duration.ofSeconds(60));
        assertTrue(first.engines().stream().noneMatch(result -> result.message().contains("engine cache")), first.engines().toString());
        var second = new DecompilationJob().withEngineCache(cache).runVerified(input, dir.resolve("second"), engines, Duration.ofSeconds(60));
        assertTrue(second.engines().stream().allMatch(result -> result.message().startsWith("Reused from the engine cache")), second.engines().toString());
        for (String engine : List.of("cfr", "vineflower"))
            assertEquals(Files.readString(dir.resolve("first/" + engine + "/sources/Calc.java")), Files.readString(dir.resolve("second/" + engine + "/sources/Calc.java")));
        assertEquals(first.selection().verification().matchedSymbols(), second.selection().verification().matchedSymbols());
        assertEquals(Files.readString(dir.resolve("first/selection/sources/Calc.java")), Files.readString(dir.resolve("second/selection/sources/Calc.java")));
        // Other input bytes give other keys: nothing is reused.
        var other = compile("Calc2", "public class Calc2 { public int thrice(int x) { return x * 3; } }");
        var third = new DecompilationJob().withEngineCache(cache).runVerified(other, dir.resolve("third"), engines, Duration.ofSeconds(60));
        assertTrue(third.engines().stream().noneMatch(result -> result.message().contains("engine cache")), third.engines().toString());
        try (var entries = Files.list(cache)) { assertEquals(4, entries.filter(Files::isDirectory).count()); }
    }

    @Test void resumesAnInterruptedBenchmarkOnlyWhenNothingChanged() throws Exception {
        var corpus = Files.createDirectory(dir.resolve("corpus"));
        Files.copy(compile("Alpha", "public class Alpha { public int one() { return 1; } }"), corpus.resolve("Alpha.class"));
        Files.copy(compile("Beta", "public class Beta { public int two() { return 2; } }"), corpus.resolve("Beta.class"));
        var output = dir.resolve("results");
        var engines = List.<dev.classrevive.engine.DecompilerEngine>of(new CfrEngine());
        var complete = new BenchmarkRunner().run(corpus, output, engines, Duration.ofSeconds(60));
        String finished = Files.readString(output.resolve("benchmark.json"));
        assertTrue(Files.readString(output.resolve("run.properties")).startsWith("fingerprint="));
        // Simulate an interruption after the first row: the aggregate, the second row and its folder never existed.
        var progress = Files.readAllLines(output.resolve("progress.jsonl"), StandardCharsets.UTF_8);
        assertEquals(2, progress.size());
        Files.write(output.resolve("progress.jsonl"), List.of(progress.getFirst(), "{\"case\":\"case-002\",\"vari"), StandardCharsets.UTF_8);
        for (String name : List.of("benchmark.json", "benchmark.md", "failures/failures.json", "failures/failures.md")) Files.delete(output.resolve(name));
        assertThrows(java.io.IOException.class, () -> new BenchmarkRunner().run(corpus, output, engines, Duration.ofSeconds(61), true),
            "a different timeout must not resume");
        var resumed = new BenchmarkRunner().run(corpus, output, engines, Duration.ofSeconds(60), true);
        assertEquals(complete, resumed);
        String json = Files.readString(output.resolve("benchmark.json"));
        assertTrue(json.contains("\"resumed_rows\":1"), json);
        assertTrue(Files.readString(output.resolve("benchmark.md")).contains("Resumed: 1 of 2 rows were reused"));
        // The partial attempt of the second row was set aside, and the row was measured again.
        assertTrue(Files.isDirectory(output.resolve("interrupted/case-002-cfr-1")));
        assertTrue(Files.isDirectory(output.resolve("case-002-cfr")));
        assertEquals(2, Files.readAllLines(output.resolve("progress.jsonl"), StandardCharsets.UTF_8).size());
        assertEquals(count(finished, "\"status\":\"STRUCTURALLY_COMPLETE\""), count(json, "\"status\":\"STRUCTURALLY_COMPLETE\""));
        assertThrows(java.io.IOException.class, () -> new BenchmarkRunner().run(corpus, output, engines, Duration.ofSeconds(60), true),
            "a finished run has nothing to resume");
    }

    @Test void engineJarBytesArePartOfTheCacheKey() throws Exception {
        var jar = dir.resolve("engine.jar");
        Files.write(jar, new byte[]{1, 2, 3});
        var input = compile("Keyed", "public class Keyed { public int one() { return 1; } }");
        var output = dir.resolve("keyed-output");
        DecompilerEngine engine = new DecompilerEngine() {
            @Override public String id() { return "fake"; }
            @Override public String version() { return "1.0"; }
            @Override public List<String> command(Path in, Path out) {
                return List.of("java", "-jar", jar.toString(), in.toString(), out.toString());
            }
        };
        String first = EngineCache.key(engine, input, output);
        Files.write(jar, new byte[]{4, 5, 6});
        String second = EngineCache.key(engine, input, output);
        assertNotEquals(first, second, "replacing the engine JAR at the same path must change the cache key");
    }

    @Test void corruptEngineCacheEntryFallsBackToTheEngine() throws Exception {
        var input = compile("Cached", "public class Cached { public int one() { return 1; } }");
        var cache = dir.resolve("broken-cache");
        var engines = List.<DecompilerEngine>of(new CfrEngine());
        new DecompilationJob().withEngineCache(cache).runVerified(input, dir.resolve("broken-first"), engines, Duration.ofSeconds(60));
        Path entry;
        try (var entries = Files.list(cache)) { entry = entries.filter(Files::isDirectory).findFirst().orElseThrow(); }
        // An invalid unicode escape makes Properties.load throw, as a torn write would.
        Files.writeString(entry.resolve("result.properties"), "broken=\\uZZZZ\n");
        var second = new DecompilationJob().withEngineCache(cache).runVerified(input, dir.resolve("broken-second"), engines, Duration.ofSeconds(60));
        assertFalse(second.engines().getFirst().message().startsWith("Reused from the engine cache"), second.engines().toString());
        assertTrue(second.selection().verification().complete());
        assertEquals(Files.readString(dir.resolve("broken-first/cfr/sources/Cached.java")),
            Files.readString(dir.resolve("broken-second/cfr/sources/Cached.java")));
        try (var entries = Files.list(cache)) { assertEquals(1, entries.filter(Files::isDirectory).count(), "the corrupt entry is replaced"); }
    }

    @Test void resumesRowsOnlyWhenTheirEvidenceStillExists() throws Exception {
        var corpus = Files.createDirectory(dir.resolve("evidence-corpus"));
        Files.copy(compile("Gamma", "public class Gamma { public int three() { return 3; } }"), corpus.resolve("Gamma.class"));
        var output = dir.resolve("evidence-results");
        var engines = List.<DecompilerEngine>of(new CfrEngine());
        var complete = new BenchmarkRunner().run(corpus, output, engines, Duration.ofSeconds(60));
        var progress = Files.readAllLines(output.resolve("progress.jsonl"), StandardCharsets.UTF_8);
        assertEquals(1, progress.size());
        // Simulate an interruption whose only row lost its evidence before the resume.
        Files.write(output.resolve("progress.jsonl"), List.of(progress.getFirst()), StandardCharsets.UTF_8);
        for (String name : List.of("benchmark.json", "benchmark.md")) Files.delete(output.resolve(name));
        Files.delete(output.resolve("case-001-cfr/report.json"));
        var resumed = new BenchmarkRunner().run(corpus, output, engines, Duration.ofSeconds(60), true);
        assertEquals(complete, resumed);
        String json = Files.readString(output.resolve("benchmark.json"));
        assertTrue(json.contains("\"resumed_rows\":0"), json);
        assertTrue(Files.isRegularFile(output.resolve("case-001-cfr/report.json"), LinkOption.NOFOLLOW_LINKS));
        assertTrue(Files.isDirectory(output.resolve("interrupted/case-001-cfr-1")), "the row without evidence is set aside");
    }

    private static int count(String text, String part) { return text.split(java.util.regex.Pattern.quote(part), -1).length - 1; }
}
