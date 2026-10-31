package dev.classrevive.pipeline;

import dev.classrevive.cfr.CfrEngine;
import dev.classrevive.vineflower.VineflowerEngine;
import dev.classrevive.engine.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.zip.*;
import java.io.*;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class DecompilationJobTest {
    @TempDir Path directory;
    List<DecompilerEngine> engines() { return List.of(new CfrEngine(), new VineflowerEngine()); }
    @Test void allFourEnginesProduceEvidenceAndCombinedSelection() throws Exception {
        var engines = List.<DecompilerEngine>of(new CfrEngine(), new VineflowerEngine(),
            new dev.classrevive.procyon.ProcyonEngine(), new dev.classrevive.jadx.JadxEngine());
        var out = directory.resolve("four");
        var result = new DecompilationJob().runVerified(fixture(true), out, engines, Duration.ofSeconds(30));
        assertEquals(4, result.engines().size());
        for (var engine : result.engines()) assertTrue(engine.sourceFiles() > 0, engine.toString());
        assertTrue(result.selection().verification().complete());
        String report = Files.readString(out.resolve("report.json"));
        assertTrue(report.contains("procyon"));
        // Phase 2BZ: the static obfuscation profile of a javac-compiled input finds nothing.
        assertTrue(report.contains("\"obfuscation\":{"), report);
        assertTrue(report.contains("\"suggested_obfuscator\":\"none (no obfuscation technique detected)\""), report);
        assertTrue(Files.readString(out.resolve("report.txt")).contains("Suggested obfuscator: none"));
        assertTrue(report.contains("\"measured\":true"), report);
        // The four engines already agree on a compiling set, so no method body is composed (additive field, Phase 2BD).
        assertTrue(report.contains("\"method_composition\":{\"count\":0,\"evidence\":\"METHOD_BODIES_FROM_OTHER_ENGINES_ROUND_TRIP_IN_PLACE_BEHAVIOR_NOT_VERIFIED\",\"files\":0,\"methods\":[]}"), report);
        assertTrue(result.selection().methods().isEmpty());
        var verification = result.selection().verification();
        assertTrue(verification.roundTripMeasured());
        assertEquals(verification.roundTrip().stream().mapToInt(item -> item.methods().size()).sum(),
            verification.roundTripIdentical() + verification.roundTripEquivalent() + verification.roundTripDifferent());
        assertTrue(Files.readString(out.resolve("selection/selection.txt")).contains("Method round trip (tie-break among structurally equal candidates)"));
    }
    @Test void standaloneInnerClassRetainsProcyonPartialSource() throws Exception {
        var source = directory.resolve("Outer.java");
        Files.writeString(source, "public class Outer { public static class Inner { public int value() { return 7; } } }");
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null,null,null,"--release","8","-proc:none",source.toString()));
        var output = directory.resolve("orphan");
        new DecompilationJob().runVerified(directory.resolve("Outer$Inner.class"), output,
            List.of(new dev.classrevive.procyon.ProcyonEngine()), Duration.ofSeconds(30));
        assertTrue(Files.readString(output.resolve("procyon/sources/Outer$Inner.java")).contains("value("));
        assertTrue(Files.exists(output.resolve("report.json")));
    }
    Path fixture(boolean jar) throws Exception {
        var source = directory.resolve("Sample.java");
        Files.writeString(source, """
            package demo;
            public class Sample {
                static { try { java.nio.file.Files.write(java.nio.file.Paths.get("TARGET_EXECUTED"), new byte[]{1}); } catch (Exception e) { throw new RuntimeException(e); } }
                public int add(int a, int b) { return a + b; }
                public String choose(int i) { switch(i) { case 1: return "one"; default: return "other"; } }
            }
            """);
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null,
            "--release", "8", "-proc:none", "-d", directory.toString(), source.toString()));
        var clazz = directory.resolve("demo/Sample.class");
        if (!jar) return clazz;
        var archive = directory.resolve("sample.jar");
        try (var zip = new ZipOutputStream(Files.newOutputStream(archive))) {
            zip.putNextEntry(new ZipEntry("demo/Sample.class")); zip.write(Files.readAllBytes(clazz)); zip.closeEntry();
            zip.putNextEntry(new ZipEntry("private-resource.txt")); zip.write("do not copy".getBytes()); zip.closeEntry();
        }
        return archive;
    }
    @Test void bothRealEnginesProduceRecompilableJavaWithoutExecutingTarget() throws Exception {
        var input = fixture(true); var before = Files.readAllBytes(input);
        var out = directory.resolve("result");
        var results = new DecompilationJob().run(input, out, engines(), Duration.ofSeconds(30));
        assertEquals(2, results.size());
        for (var result : results) {
            assertEquals(EngineResult.Status.OUTPUT_PRODUCED, result.status(), result.toString());
            assertFalse(Files.exists(out.resolve(result.engine() + "/TARGET_EXECUTED")));
            var java = out.resolve(result.engine() + "/sources/demo/Sample.java");
            assertTrue(Files.exists(java), result.toString());
            assertTrue(Files.readString(java).contains("int add("));
            assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null,
                "--release", "8", "-proc:none", "-d", directory.resolve("compiled-" + result.engine()).toString(), java.toString()));
            assertFalse(Files.exists(out.resolve(result.engine() + "/sources/private-resource.txt")));
        }
        assertArrayEquals(before, Files.readAllBytes(input));
        assertTrue(Files.readString(out.resolve("report.txt")).contains("not verified"));
    }
    @Test void supportsStandaloneClass() throws Exception {
        var result = new DecompilationJob().run(fixture(false), directory.resolve("out"), List.of(new CfrEngine()), Duration.ofSeconds(30));
        assertEquals(EngineResult.Status.OUTPUT_PRODUCED, result.getFirst().status());
    }
    @Test void verifiedJobSelectsCompilingSources() throws Exception {
        var out = directory.resolve("verified");
        var result = new DecompilationJob().runVerified(fixture(true), out, engines(), Duration.ofSeconds(30));
        assertTrue(result.selection().verification().complete());
        assertNull(result.fallback());
        assertTrue(Files.exists(out.resolve("selection/sources/demo/Sample.java")));
    }
    @Test void missingDependencyProducesPartialSourcesAndBytecode() throws Exception {
        var src = directory.resolve("MissingExample.java");
        Files.writeString(src, "public class MissingExample extends Base {} class Base {}");
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null,null,null,"--release","8","-proc:none",src.toString()));
        var out = directory.resolve("partial");
        // Without inferred stubs (Phase 2CT, covered by StubJobTest) a missing type leaves partial sources and the bytecode fallback.
        var result = new DecompilationJob().withStubs(false).runVerified(directory.resolve("MissingExample.class"), out, engines(), Duration.ofSeconds(30));
        assertFalse(result.selection().verification().complete());
        assertNotNull(result.fallback());
        assertEquals(EngineResult.Status.OUTPUT_PRODUCED, result.fallback().status());
        assertTrue(Files.readString(out.resolve("fallback/bytecode/00001.txt")).contains("MissingExample"));
    }
    @Test void refusesExistingOutputWithoutChangingIt() throws Exception {
        var out = Files.createDirectory(directory.resolve("out"));
        Files.writeString(out.resolve("keep.txt"), "original");
        assertThrows(IOException.class, () -> new DecompilationJob().run(fixture(true), out, engines(), Duration.ofSeconds(1)));
        assertEquals("original", Files.readString(out.resolve("keep.txt")));
        try (var files = Files.list(out)) { assertEquals(1, files.count()); }
    }
    @Test void rejectsUnsafeClassIdentityBeforeStartingEngines() throws Exception {
        byte[] bytes = Files.readAllBytes(fixture(false));
        byte[] from = "demo/Sample".getBytes(); byte[] to = "../x/Sample".getBytes();
        for (int i = 0; i <= bytes.length - from.length; i++) {
            if (Arrays.equals(Arrays.copyOfRange(bytes, i, i + from.length), from)) {
                System.arraycopy(to, 0, bytes, i, to.length); break;
            }
        }
        var path = directory.resolve("unsafe.class"); Files.write(path, bytes);
        var out = directory.resolve("out");
        assertThrows(IOException.class, () -> new DecompilationJob().run(path, out, engines(), Duration.ofSeconds(1)));
        assertFalse(Files.exists(out.resolve("cfr")));
    }
    @Test void rejectsMalformedInputBeforeStartingEngines() throws Exception {
        var input = directory.resolve("bad.jar"); Files.write(input, new byte[10]);
        var out = directory.resolve("out");
        assertThrows(IOException.class, () -> new DecompilationJob().run(input, out, engines(), Duration.ofSeconds(1)));
        assertFalse(Files.exists(out.resolve("vineflower")));
    }
    @Test void refusesFilesystemRootWithoutUncheckedFailure() throws Exception {
        assertThrows(IOException.class, () -> new DecompilationJob().run(fixture(false), directory.getRoot(), engines(), Duration.ofSeconds(1)));
    }
    @Test void keepsSuccessfulEngineWhenOtherFailsToStart() throws Exception {
        var bad = new DecompilerEngine() {
            public String id() { return "missing"; }
            public String version() { return "1"; }
            public List<String> command(Path input, Path output) { return List.of(directory.resolve("missing.exe").toString()); }
        };
        var results = new DecompilationJob().run(fixture(false), directory.resolve("out"), List.of(bad, new CfrEngine()), Duration.ofSeconds(30));
        assertEquals(EngineResult.Status.FAILED, results.get(0).status());
        assertEquals(EngineResult.Status.OUTPUT_PRODUCED, results.get(1).status());
    }
    @Test void partialEngineTreesStayCandidatesButCancelledOnesDoNot() throws Exception {
        var out = Files.createDirectory(directory.resolve("candidates"));
        for (String engine : List.of("cfr", "vineflower", "procyon", "jadx")) Files.createDirectories(out.resolve(engine).resolve("sources"));
        Files.createDirectories(out.resolve("other"));
        var results = List.of(
            new EngineResult("cfr", "0.152", EngineResult.Status.OUTPUT_PRODUCED, 0, 1, 1, "ok"),
            new EngineResult("vineflower", "1.11.1", EngineResult.Status.TIMED_OUT, -1, 1, 1, "timeout"),
            // An obfuscated jar: Procyon ends FAILED with 621 usable files; its tree must stay a candidate (Phase 2CK builds on it).
            new EngineResult("procyon", "0.6.0", EngineResult.Status.FAILED, 1, 621, 1, "failed on some classes"),
            new EngineResult("jadx", "1.5.3", EngineResult.Status.CANCELLED, -1, 1, 1, "cancelled"),
            new EngineResult("other", "1", EngineResult.Status.OUTPUT_PRODUCED, 0, 0, 1, "no sources directory"));
        assertEquals(Set.of("cfr", "vineflower", "procyon"), DecompilationJob.candidateSources(results, out).keySet());
    }
}
