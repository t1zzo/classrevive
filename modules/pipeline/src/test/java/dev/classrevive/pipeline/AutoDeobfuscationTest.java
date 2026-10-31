package dev.classrevive.pipeline;

import dev.classrevive.bytecode.ObfuscatorClassifier;
import dev.classrevive.engine.DecompilerEngine;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Phase 2DD: decompile reverses an obfuscator the diagnosis recognizes with HIGH confidence, and only then. */
class AutoDeobfuscationTest {
    static final Path FIXTURES = Path.of(System.getProperty("classrevive.skidfuscatorFixtures", "missing"));
    @TempDir Path directory;

    private static List<DecompilerEngine> cfr() { return List.of(new dev.classrevive.cfr.CfrEngine()); }

    @Test void reversesARecognizedSkidfuscatorJarBeforeTheEngines() throws Exception {
        Path input = Files.copy(FIXTURES.resolve("skid-fixture-obf.jar"), directory.resolve("app.jar"));
        Path output = directory.resolve("auto");
        var job = new DecompilationJob().runVerified(input, output, cfr(), Duration.ofSeconds(120));

        var outcome = job.deobfuscation();
        assertNotNull(outcome);
        assertEquals(AutoDeobfuscation.Status.APPLIED, outcome.status(), outcome.reason());
        assertEquals("Skidfuscator", outcome.obfuscator());
        assertEquals("skidfuscator", outcome.pass());
        assertTrue(outcome.counts().getOrDefault("strings decrypted", 0) > 0, outcome.counts().toString());
        assertTrue(outcome.comparison().keptMethods() <= outcome.comparison().originalMethods());

        // Original, reversed classes and the transform evidence are kept; the worker's input copy is not.
        assertTrue(Files.isRegularFile(output.resolve("deobfuscation/input.jar")));
        assertTrue(Files.isRegularFile(output.resolve("deobfuscation/worker/transformed/transformed.jar")));
        assertTrue(Files.isRegularFile(output.resolve("deobfuscation/transform.json")));
        assertFalse(Files.exists(output.resolve(AutoDeobfuscation.INPUT)));
        assertTrue(Files.isRegularFile(output.resolve("input.jar")), "the input snapshot stays the original");

        // The engines read the reversed classes: classes.jar is the reversal, not the input.
        assertEquals(dev.classrevive.verification.MethodRoundTrip.readJar(output.resolve("deobfuscation/worker/transformed/transformed.jar")).keySet(),
            dev.classrevive.verification.MethodRoundTrip.readJar(output.resolve("classes.jar")).keySet());

        String report = Files.readString(output.resolve("report.txt"));
        assertTrue(report.contains("Deobfuscation (Phase 2DD): Skidfuscator detected ("), report);
        assertTrue(report.contains("reversal applied, " + outcome.counts().get("strings decrypted") + " strings decrypted"), report);
        assertTrue(report.contains("measured against them, not against the original"), report);
        assertFalse(report.contains("compiled against the original classes"), report);
        String json = Files.readString(output.resolve("report.json"));
        assertTrue(json.contains("\"deobfuscation\""), json);
        assertTrue(json.contains("\"measured_against\":\"reversed classes\""));
        assertTrue(json.contains("\"status\":\"APPLIED\""));
        String html = Files.readString(output.resolve("report.html"));
        assertTrue(html.contains("<p class=\"reversal\">Skidfuscator detected ("), html.substring(0, Math.min(html.length(), 4000)));
    }

    @Test void offKeepsTheObfuscatedClassesAndSaysHowToReverse() throws Exception {
        Path input = Files.copy(FIXTURES.resolve("skid-fixture-obf.jar"), directory.resolve("app.jar"));
        Path output = directory.resolve("off");
        var job = new DecompilationJob().withDeobfuscation(false).runVerified(input, output, cfr(), Duration.ofSeconds(120));
        assertEquals(AutoDeobfuscation.Status.OFF, job.deobfuscation().status());
        assertFalse(Files.exists(output.resolve("deobfuscation")));
        assertEquals(dev.classrevive.verification.MethodRoundTrip.readJar(input).keySet(),
            dev.classrevive.verification.MethodRoundTrip.readJar(output.resolve("classes.jar")).keySet());
        String report = Files.readString(output.resolve("report.txt"));
        assertTrue(report.contains("automatic reversal turned off (--deobfuscate off); 'classrevive transform --passes skidfuscator' applies it"), report);
        assertTrue(Files.readString(output.resolve("report.json")).contains("\"status\":\"OFF\""));
    }

    @Test void aCleanJarIsDecompiledAsItIs() throws Exception {
        Path input = Files.copy(FIXTURES.resolve("skid-fixture.jar"), directory.resolve("clean.jar"));
        Path output = directory.resolve("clean");
        var job = new DecompilationJob().runVerified(input, output, cfr(), Duration.ofSeconds(120));
        assertNull(job.deobfuscation());
        assertFalse(Files.exists(output.resolve("deobfuscation")));
        assertFalse(Files.readString(output.resolve("report.txt")).contains("Deobfuscation (Phase 2DD)"));
        assertFalse(Files.readString(output.resolve("report.json")).contains("\"deobfuscation\""));
    }

    @Test void onlyAHighHypothesisWithAReversalPassQualifies() {
        var high = new ObfuscatorClassifier.Hypothesis("Skidfuscator", 100, "HIGH", List.of());
        var medium = new ObfuscatorClassifier.Hypothesis("Skidfuscator", 60, "MEDIUM", List.of());
        var zkm = new ObfuscatorClassifier.Hypothesis("Zelix KlassMaster", 90, "HIGH", List.of());
        var aids = new ObfuscatorClassifier.Hypothesis("Aidsfuscator", 85, "HIGH", List.of());
        assertEquals(Optional.of(high), AutoDeobfuscation.candidate(new ObfuscatorClassifier.Result("SUGGESTED", List.of(high))));
        assertEquals(Optional.empty(), AutoDeobfuscation.candidate(new ObfuscatorClassifier.Result("SUGGESTED", List.of(medium))));
        // The best hypothesis decides: a HIGH obfuscator without a pass is not overridden by a weaker one with a pass.
        assertEquals(Optional.empty(), AutoDeobfuscation.candidate(new ObfuscatorClassifier.Result("SUGGESTED", List.of(zkm, medium))));
        assertEquals("aidsfuscator", AutoDeobfuscation.PASSES.get(AutoDeobfuscation.candidate(
            new ObfuscatorClassifier.Result("SUGGESTED", List.of(aids))).orElseThrow().obfuscator()));
        assertEquals(Optional.empty(), AutoDeobfuscation.candidate(new ObfuscatorClassifier.Result("NONE", List.of())));
    }

    @Test void readsThePassCountsAndIgnoresListLines() throws Exception {
        Path report = directory.resolve("jar-passes.txt");
        Files.writeString(report, "Skidfuscator reversal: detected\n  strings decrypted: 12\n  strings not decrypted: 0\n"
            + "  methods the analysis refused: [a.b()V]\n");
        var counts = AutoDeobfuscation.counts(report);
        assertEquals(Map.of("strings decrypted", 12, "strings not decrypted", 0), counts);
        var outcome = new AutoDeobfuscation.Outcome("Skidfuscator", 100, "skidfuscator", AutoDeobfuscation.Status.APPLIED, null, report,
            counts, 3, null, new AutoDeobfuscation.Comparison(5, 4, List.of("sdk/SDK"), 20, 18, 6, 4));
        assertEquals("Skidfuscator detected (100/100): reversal applied, 12 strings decrypted, 3 method(s) rewritten",
            AutoDeobfuscation.headline(outcome));
        assertTrue(AutoDeobfuscation.text(outcome).contains("kept 18/20 method and 4/6 field signatures of the original; 1 of 5 classes removed (sdk/SDK)"));
    }
}
