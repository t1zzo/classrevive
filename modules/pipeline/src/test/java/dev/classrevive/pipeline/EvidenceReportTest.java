package dev.classrevive.pipeline;

import dev.classrevive.engine.EngineResult;
import dev.classrevive.verification.ClassCoverage;
import dev.classrevive.verification.ClassRoundTrip;
import dev.classrevive.verification.MethodRoundTrip;
import dev.classrevive.verification.VerificationResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class EvidenceReportTest {
    @TempDir Path dir;
    @Test void writesReportsAtomicallyAndRefusesToOverwrite() throws Exception {
        var path = dir.resolve("report.json");
        EvidenceReport.write(path, Map.of("kind", "test"));
        String written = Files.readString(path);
        assertTrue(written.contains("\"kind\":\"test\""), written);
        assertTrue(written.endsWith("\n"), written);
        try (var files = Files.list(dir)) {
            assertTrue(files.noneMatch(file -> file.getFileName().toString().endsWith(".tmp")), "no temporary file remains");
        }
        assertThrows(FileAlreadyExistsException.class, () -> EvidenceReport.write(path, Map.of("kind", "other")));
        assertEquals(written, Files.readString(path));
    }
    @Test void serializesStableEngineDiagnosticCodeAlongsideMessage() {
        var result = new EngineResult("cfr", "0.152", EngineResult.Status.FAILED, 7, 0, 12,
            "See engine.log", EngineResult.DiagnosticCode.ENGINE_EXIT_NONZERO);
        String json = Json.encode(EvidenceReport.engine(result));
        assertTrue(json.contains("\"diagnostic_code\":\"ENGINE_EXIT_NONZERO\""));
        assertTrue(json.contains("\"message\":\"See engine.log\""));
    }
    @Test void serializesClassCoverageAsStructuredVerificationEvidence() {
        var result = new VerificationResult(VerificationResult.Status.COMPILED, 0, 1, 2, 0, "structural only",
            List.of(new ClassCoverage("demo/A", 2, 1)));
        String json = Json.encode(EvidenceReport.verification(result));
        assertTrue(json.contains("\"class_coverage\""));
        assertTrue(json.contains("\"class_name\":\"demo/A\""));
        assertTrue(json.contains("\"coverage_permille\":500"));
        assertTrue(json.contains("\"complete\":false"));
        assertTrue(json.contains("\"round_trip\":{\"classes\":[],\"different\":0,\"equivalent_after_normalization\":0,\"identical\":0,\"measured\":false}"), json);
    }
    @Test void serializesPerMethodRoundTripCountsPerClass() {
        var methods = List.of(new MethodRoundTrip.MethodResult("a", "()V", MethodRoundTrip.Outcome.IDENTICAL, ""),
            new MethodRoundTrip.MethodResult("b", "()V", MethodRoundTrip.Outcome.EQUIVALENT_AFTER_NORMALIZATION, ""),
            new MethodRoundTrip.MethodResult("c", "()V", MethodRoundTrip.Outcome.DIFFERENT, "body differs after normalization"));
        var result = new VerificationResult(VerificationResult.Status.COMPILED, 0, 1, 1, 0, "structural only",
            List.of(new ClassCoverage("demo/A", 1, 1)), List.of(new ClassRoundTrip("demo/A", methods)));
        String json = Json.encode(EvidenceReport.verification(result));
        assertTrue(json.contains("\"round_trip\":{\"classes\":[{\"class_name\":\"demo/A\",\"different\":1,"
            + "\"equivalent_after_normalization\":1,\"identical\":1}],\"different\":1,\"equivalent_after_normalization\":1,"
            + "\"identical\":1,\"measured\":true}"), json);
    }
    @Test void benchmarkSumsSerializedRoundTripCountsAcrossAttempts() {
        var first = new VerificationResult(VerificationResult.Status.COMPILED, 0, 2, 2, 0, "structural only",
            List.of(new ClassCoverage("demo/A", 2, 2)), List.of(
                new ClassRoundTrip("demo/A", List.of(method("a", MethodRoundTrip.Outcome.IDENTICAL), method("b", MethodRoundTrip.Outcome.IDENTICAL))),
                new ClassRoundTrip("demo/B", List.of(method("c", MethodRoundTrip.Outcome.DIFFERENT)))));
        var second = new VerificationResult(VerificationResult.Status.COMPILED, 0, 1, 1, 0, "structural only",
            List.of(new ClassCoverage("demo/A", 1, 1)), List.of(new ClassRoundTrip("demo/A",
                List.of(method("a", MethodRoundTrip.Outcome.EQUIVALENT_AFTER_NORMALIZATION)))));
        var notCompiled = new VerificationResult(VerificationResult.Status.COMPILE_FAILED, 3, 0, 1, 0, "javac failed",
            List.of(new ClassCoverage("demo/A", 1, 0)));
        var rows = List.<Map<String,Object>>of(Map.of("case", "case-001", "status", "PARTIAL", "verification", EvidenceReport.verification(first)),
            Map.of("case", "case-002", "status", "STRUCTURALLY_COMPLETE", "verification", EvidenceReport.verification(second)),
            Map.of("case", "case-003", "status", "PARTIAL", "verification", EvidenceReport.verification(notCompiled)));
        var trip = (Map<?,?>) ((Map<?,?>) BenchmarkRunner.variantSummary(rows, Map.of("case-001", 3, "case-002", 1, "case-003", 4))
            .get("metrics")).get("method_round_trip");
        assertEquals(2L, trip.get("measured_attempts"));
        assertEquals(2L, trip.get("identical")); assertEquals(1L, trip.get("equivalent_after_normalization")); assertEquals(1L, trip.get("different"));
        assertEquals(4L, trip.get("methods_compared")); assertEquals(750L, trip.get("recovered_permille"));
        assertEquals(8L, trip.get("corpus_method_bodies")); assertEquals(375L, trip.get("recovered_of_corpus_permille"));
    }
    private static MethodRoundTrip.MethodResult method(String name, MethodRoundTrip.Outcome outcome) {
        return new MethodRoundTrip.MethodResult(name, "()V", outcome, "");
    }
}
