package dev.classrevive.pipeline;

import dev.classrevive.bytecode.LinkCheck;
import dev.classrevive.cfr.CfrEngine;
import dev.classrevive.engine.*;
import dev.classrevive.selection.SelectionResult;
import dev.classrevive.verification.MethodRoundTrip;
import dev.classrevive.verification.VerificationResult;
import dev.classrevive.vineflower.VineflowerEngine;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.zip.*;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 2AT failure lab on authored fixtures. CFR 0.152 decompiles a Java 17 pattern {@code instanceof} as an assignment
 * inside the condition, so javac emits {@code dup; astore} where the original had {@code astore; aload}: the method body
 * differs, while Vineflower 1.11.1 recovers it byte for byte. Nothing here executes fixture code.
 */
class FailureLabTest {
    @TempDir Path dir;
    static final Duration TIMEOUT = Duration.ofSeconds(60);
    // The first divergence after normalization; since Phase 2BB the pass-through store is skipped, so it starts at the constant.
    static final String SIGNATURE = "METHOD_DIFFERENT:cfr:body-differs:const-string/dup";
    static final String PROBE = """
        package demo;
        public class Probe {
            private final String name = "n";
            public int one() { return 1; }
            public String two(int k) { return k > 0 ? "a" : "b"; }
            public boolean named(Object other) { return other instanceof String text && text.equals(name); }
        }
        """;

    List<DecompilerEngine> engines() { return List.of(new CfrEngine(), new VineflowerEngine()); }

    Path compile(String name, String source, String release) throws Exception {
        Path file = dir.resolve("src").resolve(name + ".java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, source);
        Path classes = dir.resolve("classes-" + name.replace('/', '-'));
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "--release", release, "-proc:none", "-Xlint:-options",
            "-d", classes.toString(), file.toString()));
        Path jar = dir.resolve(name.replace('/', '-') + ".jar");
        try (var zip = new ZipOutputStream(Files.newOutputStream(jar)); var paths = Files.walk(classes)) {
            for (var path : paths.filter(p -> p.toString().endsWith(".class")).sorted().toList()) {
                zip.putNextEntry(new ZipEntry(classes.relativize(path).toString().replace('\\', '/'))); zip.write(Files.readAllBytes(path)); zip.closeEntry();
            }
        }
        return jar;
    }

    /** Runs the probe through CFR and Vineflower and captures every engine candidate, as {@code classrevive lab} does. */
    FailureCollector capture() throws Exception {
        var job = new DecompilationJob().runVerified(compile("demo/Probe", PROBE, "17"), dir.resolve("job"), engines(), TIMEOUT);
        var collector = new FailureCollector(dir.resolve("failures"));
        collector.capture(job, dir.resolve("job"), "combined", null, true);
        collector.finish();
        return collector;
    }

    static void assertNoLocalPath(String text, Path root) {
        String absolute = root.toAbsolutePath().normalize().toString();
        assertFalse(text.contains(absolute) || text.contains(absolute.replace('\\', '/')), text);
        assertFalse(text.contains(System.getProperty("user.home")), text);
    }

    @Test void capturesEngineCandidatesWithStableSignaturesAndKeepsTargetDetailsInTheCase() throws Exception {
        var collector = capture();
        assertEquals(1, collector.cases().size(), collector.cases().toString());
        var failure = collector.cases().getFirst();
        assertEquals(SIGNATURE, failure.signature());
        // Not selected since Phase 2AU+: the combined selection now takes a candidate that recovers this method.
        assertEquals("failure-0001", failure.id()); assertEquals("cfr", failure.candidate()); assertFalse(failure.selected());
        assertEquals("0.152", failure.engineVersion()); assertEquals("demo/Probe", failure.className());
        assertEquals("named(Ljava/lang/Object;)Z", failure.method()); assertEquals("demo/Probe.java", failure.sourceFile());
        assertEquals(Map.of("vineflower", "IDENTICAL"), failure.alternatives()); assertEquals(List.of("vineflower"), failure.recoverableBy());
        assertNull(failure.context(), "a one-class job has no other classes to put on the classpath");
        Path caseDirectory = collector.directory().resolve("failure-0001");
        var classes = MethodRoundTrip.readJar(caseDirectory.resolve("classes.jar"));
        assertEquals(Set.of("demo/Probe"), classes.keySet());
        assertEquals(FailureCollector.sha256(classes.get("demo/Probe")), failure.classSha256());
        assertTrue(Files.readString(caseDirectory.resolve("candidate.java")).contains("instanceof String"));
        assertTrue(Files.isRegularFile(caseDirectory.resolve("recompiled.class")));
        String json = Files.readString(caseDirectory.resolve("case.json"));
        assertEquals(failure, FailureCase.fromJson(Json.decode(json)));
        assertTrue(failure.excerpt().contains("first difference at normalized instruction"), failure.excerpt());
        assertNoLocalPath(json, dir);
        String summary = Files.readString(collector.directory().resolve("failures.json"));
        assertTrue(summary.contains(SIGNATURE), summary);
        for (String target : List.of("Probe", "named", "demo/")) assertFalse(summary.contains(target), "summary names the target: " + target);
        assertNoLocalPath(summary, dir);
        String markdown = Files.readString(collector.directory().resolve("failures.md"));
        assertTrue(markdown.contains("| `" + SIGNATURE + "` | 1 | 0 | 1 | combined 1 |"), markdown);
    }

    @Test void capturesCompileFailuresAndWorkerFailuresWithoutTargetNamesInTheSignature() throws Exception {
        var input = compile("fixtures/ThrowingInitializer", """
            package fixtures;
            public class ThrowingInitializer {
                static { if (true) throw new IllegalStateException("fixture must never execute"); }
                public int value() { return 42; }
            }
            """, "8");
        var broken = new DecompilerEngine() {
            public String id() { return "broken"; }
            public String version() { return "0"; }
            public List<String> command(Path in, Path out) {
                return List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-cp", dir.resolve("nothing").toString(), "NoSuchWorker");
            }
        };
        var job = new DecompilationJob().runVerified(input, dir.resolve("job"), List.of(broken, new CfrEngine()), TIMEOUT);
        var collector = new FailureCollector(dir.resolve("failures"));
        var cases = collector.capture(job, dir.resolve("job"), "combined", null, true);
        var signatures = cases.stream().map(FailureCase::signature).toList();
        assertTrue(signatures.contains("COMPILE_FAILED:cfr:compiler.err.initializer.must.be.able.to.complete.normally"), signatures.toString());
        assertTrue(signatures.contains("WORKER_FAILED:broken:ENGINE_EXIT_NONZERO:java.lang.ClassNotFoundException"), signatures.toString());
        var compile = cases.stream().filter(item -> item.category().equals(FailureCase.COMPILE_FAILED)).findFirst().orElseThrow();
        assertEquals("fixtures/ThrowingInitializer", compile.className());
        assertTrue(compile.excerpt().contains("compiler.err.initializer.must.be.able.to.complete.normally"), compile.excerpt());
        var worker = cases.stream().filter(item -> item.category().equals(FailureCase.WORKER_FAILED)).findFirst().orElseThrow();
        assertNull(worker.className());
        assertFalse(Files.exists(collector.directory().resolve(worker.id()).resolve("classes.jar")));
        assertFalse(Files.exists(dir.resolve("job/TARGET_EXECUTED")));
    }

    @Test void groupsBySignatureMostFrequentFirst() {
        var cases = List.of(item("failure-0001", "cfr", "body-differs:astore/dup", true, Map.of("vineflower", "IDENTICAL")),
            item("failure-0002", "jadx", "body-differs:goto/ireturn", true, Map.of()),
            item("failure-0003", "cfr", "body-differs:astore/dup", false, Map.of("vineflower", "DIFFERENT")),
            item("failure-0004", "cfr", "body-differs:astore/dup", true, Map.of("procyon", "EQUIVALENT_AFTER_NORMALIZATION")));
        var summary = FailureCollector.summary(cases);
        assertEquals(4L, summary.get("cases")); assertEquals(2L, summary.get("signatures"));
        var groups = (List<?>) summary.get("groups");
        var first = (Map<?,?>) groups.getFirst();
        assertEquals("METHOD_DIFFERENT:cfr:body-differs:astore/dup", first.get("signature"));
        assertEquals(3L, first.get("cases")); assertEquals(2L, first.get("selected")); assertEquals(2L, first.get("recoverable_by_other_candidate"));
        assertEquals(List.of("failure-0001", "failure-0003", "failure-0004"), first.get("case_ids"));
        assertEquals(Map.of("METHOD_DIFFERENT", 4L), summary.get("categories"));
        assertTrue(FailureCollector.markdown(summary, 1).contains("... 1 more in failures.json"));
        assertThrows(IllegalArgumentException.class, () -> new FailureCase(null, "OTHER", "cfr", "x", "", "v", null, "cfr", true, false,
            null, null, null, null, "", "", Map.of(), null, null));
    }

    static FailureCase item(String id, String engine, String detail, boolean selected, Map<String,String> alternatives) {
        return new FailureCase(id, FailureCase.METHOD_DIFFERENT, engine, detail, "1", "combined", "case-001", engine, selected, false,
            "a/B", "00", "m()V", "a/B.java", "body differs after normalization", "", alternatives, "ff", null);
    }

    @Test void redactsAbsolutePathsAndBoundsExcerpts() {
        String text = "bad class file C:\\Users\\someone\\lib.jar and /home/someone/lib.jar, /tmp/x and " + dir.resolve("job/a.java")
            + " but keeps java/lang/String and (Ljava/lang/Object;)Z";
        String redacted = FailureCollector.redact(text, dir.resolve("job"));
        for (String gone : List.of("someone", "/tmp/x", dir.toString())) assertFalse(redacted.contains(gone), redacted);
        assertTrue(redacted.contains("java/lang/String") && redacted.contains("(Ljava/lang/Object;)Z"), redacted);
        assertTrue(FailureCollector.redact("x".repeat(5000), dir).endsWith("..."));
        assertEquals("compiler-err-x", FailureCollector.slug("compiler.err.X"));
        assertEquals("demo/Outer", FailureCollector.topLevel("demo/Outer$Inner$Deep"));
    }

    @Test void minimizesARealCfrFailureAndKeepsTheEngineThatRecoveredIt() throws Exception {
        var collector = capture();
        Path caseDirectory = collector.directory().resolve("failure-0001");
        var result = new FailureMinimizer().minimize(caseDirectory, dir.resolve("minimized"), engines(),
            new FailureMinimizer.Budget(60, Duration.ofMinutes(15), TIMEOUT));
        String log = Files.readString(dir.resolve("minimized/minimization.json"));
        assertEquals(FailureMinimizer.MINIMIZED, result.status(), log);
        assertEquals(SIGNATURE, result.signature());
        assertTrue(result.accepted() >= 1, log);
        assertTrue(result.minimized().members() < result.original().members(), log);
        assertTrue(result.minimized().bytes() < result.original().bytes(), log);
        var report = (Map<?,?>) Json.decode(log);
        // The contrast engine must still recover the method; after reduction the round trip may call it equivalent.
        var contrast = (Map<?,?>) report.get("contrast_levels");
        assertEquals(Set.of("vineflower"), contrast.keySet());
        assertTrue(Set.of("IDENTICAL", "EQUIVALENT_AFTER_NORMALIZATION").contains(contrast.get("vineflower")), contrast.toString());
        var outcomes = new TreeSet<String>();
        for (var step : (List<?>) report.get("steps")) outcomes.add(String.valueOf(((Map<?,?>) step).get("outcome")));
        assertTrue(Set.of("ACCEPTED", "REJECTED_LINK", "REJECTED_SIGNATURE", "REJECTED_NOT_REPEATED", "REJECTED_CONTRAST", "REJECTED_ERROR")
            .containsAll(outcomes), outcomes.toString());
        assertEquals("ACCEPTED", ((Map<?,?>) ((List<?>) report.get("steps")).getFirst()).get("outcome"));
        Path jar = result.directory().resolve("classes.jar");
        assertTrue(new LinkCheck().check(jar, dir.resolve("link"), List.of(), TIMEOUT).linked());
        var minimized = FailureCase.fromJson(Json.decode(Files.readString(result.directory().resolve("case.json"))));
        assertEquals(SIGNATURE, minimized.signature());
        assertEquals(FailureCollector.sha256(MethodRoundTrip.readJar(jar).get("demo/Probe")), minimized.classSha256());
        // An independent run on the reduced class still shows the same failure on the same method.
        var again = new DecompilationJob().runVerified(jar, dir.resolve("again"), List.of(new CfrEngine()), TIMEOUT);
        assertTrue(FailureCollector.find(again, dir.resolve("again"), "lab", null, true).stream()
            .anyMatch(found -> found.data().signature().equals(SIGNATURE) && "named(Ljava/lang/Object;)Z".equals(found.data().method())));

        // The same case with an altered detail does not reproduce: one evaluation, nothing written as minimized.
        Path altered = Files.createDirectory(dir.resolve("altered"));
        Files.copy(caseDirectory.resolve("classes.jar"), altered.resolve("classes.jar"));
        Files.writeString(altered.resolve("case.json"), Files.readString(caseDirectory.resolve("case.json")).replace("const-string/dup", "nop/nop"));
        var refused = new FailureMinimizer().minimize(altered, dir.resolve("altered-out"), engines(), new FailureMinimizer.Budget(10, Duration.ofMinutes(5), TIMEOUT));
        assertEquals(FailureMinimizer.NOT_REPRODUCIBLE, refused.status());
        assertEquals(1, refused.evaluations()); assertNull(refused.directory());
        var unavailable = new FailureMinimizer().minimize(caseDirectory, dir.resolve("no-engine"), List.of(new VineflowerEngine()),
            new FailureMinimizer.Budget(10, Duration.ofMinutes(5), TIMEOUT));
        assertEquals(FailureMinimizer.UNSUPPORTED, unavailable.status()); assertEquals(0, unavailable.evaluations());
        var short_ = new FailureMinimizer().minimize(caseDirectory, dir.resolve("short"), engines(), new FailureMinimizer.Budget(2, Duration.ofMinutes(5), TIMEOUT));
        assertEquals(FailureMinimizer.BUDGET_EXHAUSTED, short_.status());
        assertEquals(2, short_.evaluations());
        assertTrue(Files.isRegularFile(short_.directory().resolve("classes.jar")), "the last accepted state is kept");
    }

    @Test void promotedCasesFailWhenAMethodDropsAndReportImprovements() throws Exception {
        var collector = capture();
        Path regressions = Files.createDirectory(dir.resolve("regressions"));
        var expected = new RegressionSuite().promote(collector.directory().resolve("failure-0001"), regressions.resolve("probe"), engines(), TIMEOUT,
            "authored test fixture");
        var variants = (Map<?,?>) expected.get("variants");
        assertEquals(Set.of("cfr", "vineflower", "combined"), variants.keySet());
        String named = "demo/Probe.named(Ljava/lang/Object;)Z";
        assertEquals("DIFFERENT", levels(variants, "cfr").get(named));
        assertEquals("IDENTICAL", levels(variants, "vineflower").get(named));
        // Written in Phase 2AT, when combined still selected CFR here; the round-trip tie-break (2AU) and per-method
        // composition (2BD+) now recover it, so the promoted expectation records the gain and guards it.
        assertEquals("IDENTICAL", levels(variants, "combined").get(named), "combined recovers the body CFR alone gets wrong");
        assertEquals(Boolean.TRUE, ((Map<?,?>) variants.get("combined")).get("source_repair"));
        assertEquals(List.of(), expected.get("unstable_at_promotion"));
        assertFalse(Files.exists(regressions.resolve("probe/promotion")), "measurement jobs never land in the regression directory");

        var engines = engines();
        var clean = new RegressionSuite().check(regressions, dir.resolve("check-1"), engines, TIMEOUT);
        assertTrue(clean.passed(), clean.toString());
        assertEquals(1, clean.cases()); assertEquals(12, clean.checks().size(), clean.checks().toString());

        Path file = regressions.resolve("probe/expected.json");
        String original = Files.readString(file, StandardCharsets.UTF_8);
        var data = (Map<String,Object>) Json.decode(original);
        @SuppressWarnings("unchecked") var cfr = (Map<String,Object>) ((Map<String,Object>) ((Map<String,Object>) data.get("variants")).get("cfr")).get("minimum_levels");
        @SuppressWarnings("unchecked") var vineflower = (Map<String,Object>) ((Map<String,Object>) ((Map<String,Object>) data.get("variants")).get("vineflower")).get("minimum_levels");
        cfr.put(named, "IDENTICAL"); vineflower.put(named, "DIFFERENT");
        Files.writeString(file, Json.encode(data) + "\n");
        var changed = new RegressionSuite().check(regressions, dir.resolve("check-2"), engines, TIMEOUT);
        assertFalse(changed.passed());
        assertEquals(List.of(new RegressionSuite.Check("probe", "cfr", named, "IDENTICAL", "DIFFERENT")), changed.regressions());
        assertEquals(List.of(new RegressionSuite.Check("probe", "vineflower", named, "DIFFERENT", "IDENTICAL")), changed.improvements());
        assertTrue(Files.readString(dir.resolve("check-2/regressions.md")).contains("| probe | cfr | `" + named + "` | IDENTICAL | DIFFERENT | REGRESSED |"));

        Files.writeString(file, original);
        Files.write(regressions.resolve("probe/classes.jar"), new byte[] {1, 2, 3}, StandardOpenOption.APPEND);
        var tampered = new RegressionSuite().check(regressions, dir.resolve("check-3"), engines, TIMEOUT);
        assertFalse(tampered.passed());
        assertTrue(tampered.errors().getFirst().contains("classes_sha256"), tampered.errors().toString());
        assertThrows(java.io.IOException.class, () -> new RegressionSuite().check(regressions, regressions.resolve("inside"), engines, TIMEOUT));
    }

    @SuppressWarnings("unchecked")
    static Map<String,String> levels(Map<?,?> variants, String variant) {
        return (Map<String,String>) ((Map<?,?>) variants.get(variant)).get("minimum_levels");
    }

    @Test void workersThatCouldNotRunNeverBecomeExpectations() {
        var compiled = new VerificationResult(VerificationResult.Status.COMPILED, 0, 1, 1, 0, "");
        var failed = new VerificationResult(VerificationResult.Status.WORKER_FAILED, 1000, 0, 1, 0, "TIMED_OUT");
        var exited = new EngineResult("cfr", "1", EngineResult.Status.FAILED, 1, 0, 5, "x", EngineResult.DiagnosticCode.ENGINE_EXIT_NONZERO);
        var unstarted = new EngineResult("cfr", "1", EngineResult.Status.FAILED, -1, 0, 5, "x", EngineResult.DiagnosticCode.WORKER_START_FAILED);
        assertNull(RegressionSuite.unreliable(new DecompilationJob.VerifiedJob(List.of(exited), new SelectionResult("cfr", compiled, Map.of(), 1), null)));
        assertTrue(RegressionSuite.unreliable(new DecompilationJob.VerifiedJob(List.of(unstarted), new SelectionResult("cfr", compiled, Map.of(), 1), null))
            .contains("WORKER_START_FAILED"));
        assertTrue(RegressionSuite.unreliable(new DecompilationJob.VerifiedJob(List.of(exited), new SelectionResult("cfr", failed, Map.of(), 1), null))
            .contains("compiler worker failed"));
        assertEquals("SAME", new RegressionSuite.Check("c", "v", "m", "DIFFERENT", "DIFFERENT").verdict());
        assertEquals("REGRESSED", new RegressionSuite.Check("c", "v", "m", "NOT_RECOMPILED", "ABSENT").verdict());
    }
}
