package dev.classrevive.pipeline;

import dev.classrevive.engine.DecompilerEngine;
import dev.classrevive.transforms.NameInference;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Phase 2DK: decompile --infer-names over a real ProGuard jar; off by default. */
class InferNamesJobTest {
    static final Path FIXTURES = Path.of(System.getProperty("classrevive.nameFixtures", "missing"));
    @TempDir Path directory;
    private static List<DecompilerEngine> cfr() { return List.of(new dev.classrevive.cfr.CfrEngine()); }

    @Test void offByDefaultLeavesNamesAlone() throws Exception {
        Path input = Files.copy(FIXTURES.resolve("names-default.jar"), directory.resolve("app.jar"));
        new DecompilationJob().runVerified(input, directory.resolve("off"), cfr(), Duration.ofSeconds(120));
        assertFalse(Files.exists(directory.resolve("off/inferred-names")));
        assertFalse(Files.readString(directory.resolve("off/report.json")).contains("inferred_names"));
    }

    @Test void inferredNamesAreAppliedAndReported() throws Exception {
        Path input = Files.copy(FIXTURES.resolve("names-default.jar"), directory.resolve("app.jar"));
        Path out = directory.resolve("on");
        var job = new DecompilationJob().withInferNames(NameInference.Confidence.LOW).runVerified(input, out, cfr(), Duration.ofSeconds(120));
        assertTrue(Files.isRegularFile(out.resolve("inferred-names/names.json")));
        assertTrue(Files.isRegularFile(out.resolve("inferred-names/mapping.txt")));
        assertTrue(Files.isRegularFile(out.resolve("inferred-names/names.tsv")));
        String json = Files.readString(out.resolve("report.json"));
        assertTrue(json.contains("\"inferred_names\"") && json.contains("\"applied\":true"), json.substring(0, Math.min(500, json.length())));
        assertTrue(Files.readString(out.resolve("report.txt")).contains("Inferred names (Phase 2DK"));
        var classes = dev.classrevive.verification.MethodRoundTrip.readJar(out.resolve("classes.jar")).keySet();
        assertTrue(classes.contains("app/Player"), classes.toString());
        assertTrue(job.selection().verification().complete(), "renamed classes still round-trip: " + job.selection().verification().status());
    }
}
