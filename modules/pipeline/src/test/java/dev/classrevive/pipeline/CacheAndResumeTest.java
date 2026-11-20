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

    /** A fake engine (a single-file Java program; no target class is run) that counts its runs in {@code runs.txt}. */
    private DecompilerEngine scripted(String id, String body) throws Exception {
        Path program = dir.resolve(id + "-engine/Engine.java");
        Files.createDirectories(program.getParent());
        Files.writeString(program, """
            import java.nio.file.*;
            public class Engine {
                public static void main(String[] args) throws Exception {
                    Path out = Path.of(args[1]);
                    Files.writeString(Path.of(args[2]), "run\\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                    %s
                }
            }
            """.formatted(body));
        Path runs = dir.resolve(id + "-runs.txt");
        return new DecompilerEngine() {
            @Override public String id() { return id; }
            @Override public String version() { return "1"; }
            @Override public List<String> command(Path in, Path out) {
                return List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(), program.toString(), in.toString(), out.toString(),
                    runs.toString());
            }
        };
    }

    @Test void reusesARunTheEngineEndedWithANonzeroExit() throws Exception {
        // Phase 2DX: JADX exits 1 on most real jars; such a run is the engine's own answer and is kept with its log.
        var input = compile("Calc", "public class Calc { public int twice(int x) { return x * 2; } }");
        var engine = scripted("partial", """
                    Files.writeString(out.resolve("Calc.java"), "public class Calc { public int twice(int x) { return x * 2; } }");
                    System.out.println("Failed class Other: IllegalArgumentException");
                    System.exit(3);""");
        var cache = dir.resolve("failure-cache");
        var first = new DecompilationJob().withEngineCache(cache).runVerified(input, dir.resolve("partial-first"), List.of(engine), Duration.ofSeconds(60));
        var ran = first.engines().getFirst();
        assertEquals(dev.classrevive.engine.EngineResult.Status.FAILED, ran.status(), ran.toString());
        assertEquals(3, ran.exitCode());
        var second = new DecompilationJob().withEngineCache(cache).runVerified(input, dir.resolve("partial-second"), List.of(engine), Duration.ofSeconds(60));
        var reused = second.engines().getFirst();
        assertTrue(reused.message().startsWith("Reused from the engine cache") && reused.message().contains("exited with code 3"), reused.toString());
        assertEquals(dev.classrevive.engine.EngineResult.Status.FAILED, reused.status());
        assertEquals(3, reused.exitCode());
        assertEquals(dev.classrevive.engine.EngineResult.DiagnosticCode.ENGINE_EXIT_NONZERO, reused.diagnosticCode());
        assertEquals(1, reused.sourceFiles());
        String log = Files.readString(dir.resolve("partial-second/partial/engine.log"));
        assertTrue(log.startsWith("Reused from the engine cache, key ") && log.contains("Failed class Other: IllegalArgumentException"), log);
        assertEquals(Files.readString(dir.resolve("partial-first/partial/sources/Calc.java")),
            Files.readString(dir.resolve("partial-second/partial/sources/Calc.java")));
        assertEquals(1, Files.readAllLines(dir.resolve("partial-runs.txt")).size(), "the engine ran once");
        assertEquals(first.selection().verification().status(), second.selection().verification().status());
    }

    @Test void timeoutsAndOtherMachineDependentEndingsAreNotCached() throws Exception {
        var input = compile("Slow", "public class Slow { public int one() { return 1; } }");
        var engine = scripted("slow", "Thread.sleep(20_000);");
        var cache = new EngineCache(dir.resolve("timeout-cache"));
        var first = cache.run(engine, input, dir.resolve("slow-first"), Duration.ofSeconds(2));
        assertEquals(dev.classrevive.engine.EngineResult.Status.TIMED_OUT, first.status());
        var second = cache.run(engine, input, dir.resolve("slow-second"), Duration.ofSeconds(2));
        assertEquals(dev.classrevive.engine.EngineResult.Status.TIMED_OUT, second.status());
        assertFalse(second.message().startsWith("Reused"), second.toString());
        try (var entries = Files.list(dir.resolve("timeout-cache"))) { assertEquals(0, entries.filter(Files::isDirectory).count()); }
        for (var status : List.of(dev.classrevive.engine.EngineResult.Status.TIMED_OUT, dev.classrevive.engine.EngineResult.Status.OUTPUT_LIMIT,
                dev.classrevive.engine.EngineResult.Status.CANCELLED, dev.classrevive.engine.EngineResult.Status.NO_SOURCES))
            assertFalse(EngineCache.cacheable(new dev.classrevive.engine.EngineResult("e", "1", status, 0, 0, 0, "")), status.name());
        assertFalse(EngineCache.cacheable(new dev.classrevive.engine.EngineResult("e", "1", dev.classrevive.engine.EngineResult.Status.FAILED, -1, 0, 0, "",
            dev.classrevive.engine.EngineResult.DiagnosticCode.WORKER_START_FAILED)), "a worker that could not start");
        assertTrue(EngineCache.cacheable(new dev.classrevive.engine.EngineResult("e", "1", dev.classrevive.engine.EngineResult.Status.FAILED, 1, 5, 0, "",
            dev.classrevive.engine.EngineResult.DiagnosticCode.ENGINE_EXIT_NONZERO)));
        assertTrue(EngineCache.cacheable(new dev.classrevive.engine.EngineResult("e", "1", dev.classrevive.engine.EngineResult.Status.OUTPUT_PRODUCED, 0, 5, 0, "")));
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
