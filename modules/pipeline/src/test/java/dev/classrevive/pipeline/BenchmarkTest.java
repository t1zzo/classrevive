package dev.classrevive.pipeline;

import dev.classrevive.cfr.CfrEngine;
import dev.classrevive.vineflower.VineflowerEngine;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class BenchmarkTest {
    @TempDir Path directory;
    @Test void rejectsWindowsJunctionOutputIntoCorpus() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(System.getProperty("os.name").startsWith("Windows"));
        var corpus = Files.createDirectory(directory.resolve("corpus"));
        Files.write(corpus.resolve("bad.class"), new byte[]{0});
        var link = directory.resolve("alias");
        var process = new ProcessBuilder("cmd.exe", "/c", "mklink", "/J", link.toString(), corpus.toString()).redirectErrorStream(true).start();
        String message = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(0, process.waitFor(), message);
        try {
            assertThrows(java.io.IOException.class, () -> new BenchmarkRunner().run(corpus, link.resolve("output"), List.of(new CfrEngine()), Duration.ofSeconds(1)));
            assertFalse(Files.exists(corpus.resolve("output")));
        } finally { Files.delete(link); }
    }
    @Test void retainsInvalidCasesAndAllDenominators() throws Exception {
        var corpus = Files.createDirectory(directory.resolve("corpus"));
        Files.write(corpus.resolve("broken.class"), new byte[]{1,2,3});
        var output = directory.resolve("results");
        var result = new BenchmarkRunner().run(corpus, output, List.of(new CfrEngine(), new VineflowerEngine()), Duration.ofSeconds(30));
        assertEquals(3, result.attempted()); assertEquals(0, result.complete());
        String json = Files.readString(output.resolve("benchmark.json"));
        assertTrue(json.contains("\"status\":\"FAILED\""));
        assertTrue(json.contains("\"attempted\":3"));
        // A rejected input never reached an engine: the failure lab records engine failures, not intake refusals.
        assertTrue(json.contains("\"failures\":{\"cases\":0,"), json);
        String markdown = Files.readString(output.resolve("benchmark.md"));
        assertTrue(markdown.contains("| cfr | 1 | 0 | 0 |"));
        assertTrue(markdown.contains("not verified"));
        assertThrows(java.io.IOException.class, () -> new BenchmarkRunner().run(corpus, output, List.of(new CfrEngine()), Duration.ofSeconds(30)));
    }
    @Test void comparesStandaloneAndCombinedWithoutExecutingFixture() throws Exception {
        var corpus = Files.createDirectory(directory.resolve("corpus"));
        var source = directory.resolve("Sample.java");
        Files.writeString(source, "public class Sample { static { if (System.nanoTime() != 0) throw new Error(); } public int twice(int x) { return x * 2; } }");
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null,null,null,"--release","8","-proc:none","-d",corpus.toString(),source.toString()));
        var output = directory.resolve("results");
        var result = new BenchmarkRunner().run(corpus, output, List.of(new CfrEngine(), new VineflowerEngine()), Duration.ofSeconds(30));
        assertEquals(3, result.attempted()); assertEquals(3, result.complete(), Files.readString(output.resolve("benchmark.json")));
        String json = Files.readString(output.resolve("benchmark.json"));
        assertTrue(json.contains("NOT_VERIFIED"));
        // Sample has three bodies (<init>, <clinit>, twice); every variant recompiled, so both denominators are 3.
        assertTrue(json.contains("\"original_method_bodies\":3"), json);
        assertEquals(3, json.split("\"methods_compared\":3,", -1).length - 1, json);
        assertEquals(3, json.split("\"corpus_method_bodies\":3,\"denominator_definition\"", -1).length - 1, json);
        // Phase 2BP: every variant recompiled, so the per-file metric equals the whole-candidate one (3 of 3 bodies).
        assertEquals(3, json.split("\"corpus_method_bodies\":3,\"definition\":\"[^\"]*\",\"recovered_methods\":3,", -1).length - 1, json);
        assertTrue(json.contains("\"behavioral_equivalence\":{\"reason\":\"target execution is disabled\",\"status\":\"NOT_MEASURED\"}"), json);
        assertTrue(json.contains("\"deobfuscation\",\"textual_similarity\""), json);
        String markdown = Files.readString(output.resolve("benchmark.md"));
        assertTrue(markdown.contains("## Per-method round trip (tie-break among structurally equal candidates)"), markdown);
        // Phase 2AT: every row's selected candidate is captured; a DIFFERENT body there would become a case.
        assertTrue(markdown.contains("## Failure signatures (failure lab)"), markdown);
        String lab = Files.readString(output.resolve("failures/failures.json"));
        assertTrue(lab.contains("\"kind\":\"failure-lab\""), lab);
        assertTrue(json.contains("\"failures\":{\"cases\":"), json);
        assertFalse(lab.contains("Sample") || lab.contains("twice"), "the summary names no target member: " + lab);
        assertTrue(markdown.matches("(?s).*\\| combined \\| 1/1 \\| \\d+ \\| \\d+ \\| \\d+ \\| \\d/3 .*"), markdown);
    }
    @Test void compilesEachSampleAgainstItsOwnDependencyFolder() throws Exception {
        // Phase 2BK: Sample.class needs Dep at compile time; only its sidecar folder provides it.
        var classes = Files.createDirectory(directory.resolve("classes"));
        Files.writeString(directory.resolve("Dep.java"), "public class Dep { public static int one() { return 1; } }");
        Files.writeString(directory.resolve("Sample.java"), "public class Sample { public int value() { return Dep.one() + 1; } }");
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null,null,null,"--release","8","-proc:none","-d",classes.toString(),
            directory.resolve("Dep.java").toString(), directory.resolve("Sample.java").toString()));
        var corpus = Files.createDirectory(directory.resolve("corpus"));
        Files.copy(classes.resolve("Sample.class"), corpus.resolve("Sample.class"));
        Files.copy(classes.resolve("Sample.class"), corpus.resolve("Without.class"));
        var deps = Files.createDirectory(corpus.resolve("Sample.class.deps"));
        try (var jar = new java.util.jar.JarOutputStream(Files.newOutputStream(deps.resolve("dep.jar")))) {
            jar.putNextEntry(new java.util.jar.JarEntry("Dep.class")); jar.write(Files.readAllBytes(classes.resolve("Dep.class"))); jar.closeEntry();
        }
        var output = directory.resolve("results");
        // Stubs off (Phase 2CT): Without.class must show what a missing dependency costs; with stubs, Dep would be inferred.
        var result = new BenchmarkRunner().withStubs(false).run(corpus, output, List.of(new CfrEngine()), Duration.ofSeconds(30));
        String json = Files.readString(output.resolve("benchmark.json"));
        assertEquals(2, result.attempted()); assertEquals(1, result.complete(), json);
        assertTrue(json.contains("\"dependencies\":[{\"name\":\"dep.jar\",\"sha256\":\""), json);
        assertTrue(json.contains("\"dependencies\":[]"), json);
        String markdown = Files.readString(output.resolve("benchmark.md"));
        // Sample.class (with Dep) recompiles; Without.class lacks Dep, so javac reports errors.
        assertTrue(markdown.matches("(?s).*\\| Sample\\.class \\| 1 \\| \\d+/\\d+ \\(\\d+\\.\\d%\\) \\|.*"), markdown);
        assertTrue(markdown.matches("(?s).*\\| Without\\.class \\| 0 \\| [1-9]\\d* errors?(; per file [^|]*)? \\|.*"), markdown);
        assertTrue(Files.isRegularFile(output.resolve("corpus/case-001.deps/00.jar")), "dependencies are snapshotted per case");
        Files.move(deps, corpus.resolve("Missing.class.deps"));
        var orphan = assertThrows(java.io.IOException.class, () -> new BenchmarkRunner().run(corpus, directory.resolve("orphan"), List.of(new CfrEngine()), Duration.ofSeconds(30)));
        assertTrue(orphan.getMessage().contains("without a matching sample"), orphan.getMessage());
        assertFalse(Files.exists(directory.resolve("orphan")));
    }
    @Test void summarizesRoundTripCountsWithExplicitDenominators() {
        var rows = List.of(
            row("case-001", "COMPILED", "STRUCTURALLY_COMPLETE", trip(true, 7, 1, 2)),
            row("case-002", "COMPILED", "PARTIAL", trip(true, 3, 0, 1)),
            row("case-003", "COMPILE_FAILED", "PARTIAL", trip(false, 0, 0, 0)),
            failed("case-004"));
        var bodies = Map.of("case-001", 10, "case-002", 4, "case-003", 6);
        var summary = BenchmarkRunner.variantSummary(rows, bodies);
        assertEquals(4, summary.get("attempted")); assertEquals(2L, summary.get("recompiled")); assertEquals(1L, summary.get("structurally_complete"));
        var metrics = (Map<?,?>) summary.get("metrics");
        var trip = (Map<?,?>) metrics.get("method_round_trip");
        assertEquals(2L, trip.get("measured_attempts")); assertEquals(4, trip.get("attempts"));
        assertEquals(10L, trip.get("identical")); assertEquals(1L, trip.get("equivalent_after_normalization")); assertEquals(3L, trip.get("different"));
        assertEquals(14L, trip.get("methods_compared")); assertEquals(11L, trip.get("recovered_methods"));
        assertEquals(785L, trip.get("recovered_permille")); // 11/14 = 78.57%, rounded down
        assertEquals(20L, trip.get("corpus_method_bodies")); assertEquals(1L, trip.get("attempts_without_method_count"));
        assertEquals(550L, trip.get("recovered_of_corpus_permille")); // case-003 did not recompile: its 6 bodies are not recovered
        assertEquals("NOT_MEASURED", ((Map<?,?>) metrics.get("behavioral_equivalence")).get("status"));
        assertEquals("NOT_MEASURED", ((Map<?,?>) metrics.get("deobfuscation")).get("status"));
        assertEquals(2L, ((Map<?,?>) metrics.get("compilable")).get("recompiled"));
        assertEquals(4, ((Map<?,?>) metrics.get("structural")).get("denominator"));
        var all = new TreeMap<String,Object>(); all.put("cfr", summary);
        all.put("jadx", BenchmarkRunner.variantSummary(List.of(failed("case-001")), Map.of()));
        String markdown = BenchmarkRunner.markdown(all);
        assertTrue(markdown.contains("| cfr | 4 | 2 | 1 |"), markdown);
        assertTrue(markdown.contains("| cfr | 2/4 | 10 | 1 | 3 | 11/14 (78.5%) | 11/20 (55.0%) (1 attempts without a method count excluded) |"), markdown);
        assertTrue(markdown.contains("| jadx | 0/1 | 0 | 0 | 0 | n/a (0 methods) | n/a (0 methods) (1 attempts without a method count excluded) |"), markdown);
        assertTrue(markdown.contains("includes legitimate compiler variation"), markdown);
        assertTrue(markdown.contains("not original-source recovery percentages"), markdown);
    }
    @Test void rowsSayWhenTheyCompiledAgainstInferredStubs() throws Exception {
        // Phase 2DF: Sample.class needs Dep, which nobody supplies; with stubs (the default) it compiles, and the row says so.
        var classes = Files.createDirectory(directory.resolve("classes"));
        Files.writeString(directory.resolve("Dep.java"), "public class Dep { public static int one() { return 1; } }");
        Files.writeString(directory.resolve("Sample.java"), "public class Sample { public int value() { return Dep.one() + 1; } }");
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null,null,null,"--release","8","-proc:none","-d",classes.toString(),
            directory.resolve("Dep.java").toString(), directory.resolve("Sample.java").toString()));
        var corpus = Files.createDirectory(directory.resolve("corpus"));
        Files.copy(classes.resolve("Sample.class"), corpus.resolve("Sample.class"));
        var output = directory.resolve("results");
        var result = new BenchmarkRunner().run(corpus, output, List.of(new CfrEngine()), Duration.ofSeconds(30));
        String json = Files.readString(output.resolve("benchmark.json"));
        // Still counted complete (the decision keeps comparability), but marked on the row and in the summary.
        assertEquals(1, result.complete(), json);
        assertTrue(json.contains("\"stub_backed\":true"), json);
        assertTrue(json.contains("\"missing_type_stubs\":{\"classes\":1,"), json);
        String markdown = Files.readString(output.resolve("benchmark.md"));
        assertTrue(markdown.contains("| cfr | 1 | 1 | 1 (1 stub-backed) |"), markdown);
        assertTrue(markdown.matches("(?s).*\\| Sample\\.class \\| 0 \\| [^|]* \\(stub-backed\\) \\|.*"), markdown);
    }
    private static Map<String,Object> trip(boolean measured, int identical, int equivalent, int different) {
        return Map.of("measured", measured, "identical", identical, "equivalent_after_normalization", equivalent, "different", different, "classes", List.of());
    }
    private static Map<String,Object> row(String id, String compile, String status, Map<String,Object> trip) {
        return Map.of("case", id, "status", status, "verification", Map.of("status", compile, "round_trip", trip));
    }
    private static Map<String,Object> failed(String id) { return Map.of("case", id, "status", "FAILED", "error", "worker failed"); }
    @Test void rejectsEmptyAndOversizedCorpusBeforeCreatingOutput() throws Exception {
        var corpus = Files.createDirectory(directory.resolve("corpus"));
        var output = directory.resolve("results");
        assertThrows(java.io.IOException.class, () -> new BenchmarkRunner().run(corpus, output, List.of(new CfrEngine()), Duration.ofSeconds(1)));
        for (int i=0;i<101;i++) Files.write(corpus.resolve(i+".class"), new byte[]{0});
        assertThrows(java.io.IOException.class, () -> new BenchmarkRunner().run(corpus, output, List.of(new CfrEngine()), Duration.ofSeconds(1)));
        assertFalse(Files.exists(output));
    }
    @Test void cancellationDuringLastCompilerDoesNotProduceCompletedAggregate() throws Exception {
        var corpus = Files.createDirectory(directory.resolve("corpus"));
        var source = directory.resolve("Sample.java");
        Files.writeString(source, "public class Sample { public int value() { return 1; } }");
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null,null,null,"--release","8","-proc:none","-d",corpus.toString(),source.toString()));
        var output = directory.resolve("cancelled");
        var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        var worker = new Thread(() -> {
            try { new BenchmarkRunner().run(corpus, output, List.of(new CfrEngine()), Duration.ofSeconds(30)); }
            catch (Throwable error) { failure.set(error); }
        });
        worker.start();
        try {
            Path compiler = output.resolve("case-001-cfr/selection/checks/attempt-0/classes");
            long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
            while (!Files.isDirectory(compiler) && worker.isAlive() && System.nanoTime() < deadline) Thread.sleep(2);
            assertTrue(Files.isDirectory(compiler), "Compiler worker should start");
            worker.interrupt(); worker.join(10000);
            assertFalse(worker.isAlive());
            assertInstanceOf(InterruptedException.class, failure.get());
            assertFalse(Files.exists(output.resolve("benchmark.json")));
        } finally { worker.interrupt(); worker.join(10000); }
    }
}
