package dev.classrevive.pipeline;

import dev.classrevive.cfr.CfrEngine;
import dev.classrevive.engine.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.zip.*;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Phase 2DP: an engine marked never-worse is kept only when the result with it is at least as good on every measure. */
class NeverWorseTest {
    @TempDir Path directory;

    private static NeverWorse.Score score(boolean compiled, int signatures, int classes, int bodies) {
        return new NeverWorse.Score(false, compiled, signatures, compiled ? Integer.MAX_VALUE : classes, bodies);
    }

    @Test void keepsOnlyAResultThatIsNoWorseAnywhereAndBetterSomewhere() {
        var base = score(false, 4730, 754, 8304);
        assertFalse(NeverWorse.keeps(base, base), "a tie keeps the result without the engine");
        assertTrue(NeverWorse.keeps(score(false, 4730, 754, 8305), base));
        assertTrue(NeverWorse.keeps(score(false, 4731, 760, 8400), base));
        // jackson-databind in 2DO: more signatures, fewer compiling classes and bodies. Not kept.
        assertFalse(NeverWorse.keeps(score(false, 6707, 733, 8085), base));
        assertFalse(NeverWorse.keeps(score(false, 4730, 755, 8303), base));
        // Compiling as a whole beats a partial result only when it recovers no fewer bodies.
        assertTrue(NeverWorse.keeps(score(true, 4730, 0, 8304), base));
        assertFalse(NeverWorse.keeps(score(true, 4730, 0, 8000), base));
        assertFalse(NeverWorse.keeps(base, score(true, 4730, 0, 8304)));
        var decision = NeverWorse.decide(List.of("jd-core"), score(false, 6707, 733, 8085), base);
        assertFalse(decision.keptWith());
        assertTrue(decision.text().contains("the result with jd-core was left out"), decision.text());
        assertTrue(decision.text().contains("without jd-core: not compiled, signatures 4730, classes compiling on their own 754, bodies identical or equivalent 8304"), decision.text());
    }

    @Test void keepsTheEngineWhenItMakesTheResultBetter() throws Exception {
        var out = directory.resolve("kept");
        // The base engine writes a class with no members: it compiles but matches none of the methods' signatures.
        var job = new DecompilationJob().runVerified(fixture(), out, List.of(writer("hollow"), guarded(new CfrEngine())), Duration.ofSeconds(60));
        String guard = Files.readString(out.resolve("guard/guard.txt"));
        assertTrue(guard.contains("the result with shadow-cfr was kept"), guard);
        assertTrue(job.selection().verification().complete(), guard);
        assertTrue(Files.isDirectory(out.resolve("guard/without/selection")));
        assertFalse(Files.exists(out.resolve("guard/with/selection")), "the kept run is the job's selection");
        assertTrue(Files.readString(out.resolve("selection/selection.txt")).contains("Never-worse guard (Phase 2DP)"));
        assertTrue(Files.readString(out.resolve("report.txt")).contains("Never-worse guard (Phase 2DP)"));
        assertFalse(Files.exists(out.resolve("TARGET_EXECUTED")));
    }

    @Test void leavesTheEngineOutWhenItAddsNothing() throws Exception {
        var out = directory.resolve("left-out");
        var job = new DecompilationJob().runVerified(fixture(), out, List.of(new CfrEngine(), guarded(writer("hollow"))), Duration.ofSeconds(60));
        String guard = Files.readString(out.resolve("guard/guard.txt"));
        assertTrue(guard.contains("the result with shadow-hollow was left out"), guard);
        assertTrue(job.selection().verification().complete());
        assertFalse(job.selection().provenance().containsValue("shadow-hollow"));
        assertTrue(Files.isDirectory(out.resolve("guard/with/selection")));
    }

    @Test void aGuardedEngineAloneIsNotWeighed() throws Exception {
        var out = directory.resolve("alone");
        new DecompilationJob().runVerified(fixture(), out, List.of(guarded(new CfrEngine())), Duration.ofSeconds(60));
        assertFalse(Files.exists(out.resolve("guard")));
        assertTrue(Files.isDirectory(out.resolve("selection")));
    }

    private static DecompilerEngine guarded(DecompilerEngine engine) {
        return new DecompilerEngine() {
            public String id() { return "shadow-" + engine.id(); }
            public String version() { return engine.version(); }
            public List<String> command(Path input, Path output) { return engine.command(input, output); }
            @Override public boolean neverWorse() { return true; }
        };
    }

    /** An engine that writes demo/Sample.java with no members, through a single-file Java program (no target class is run). */
    private DecompilerEngine writer(String id) throws Exception {
        Path program = directory.resolve("Hollow.java");
        Files.writeString(program, """
            public class Hollow {
                public static void main(String[] args) throws Exception {
                    var file = java.nio.file.Path.of(args[0], "demo", "Sample.java");
                    java.nio.file.Files.createDirectories(file.getParent());
                    java.nio.file.Files.writeString(file, "package demo;\\npublic class Sample {\\n}\\n");
                }
            }
            """);
        return new DecompilerEngine() {
            public String id() { return id; }
            public String version() { return "1"; }
            public List<String> command(Path input, Path output) {
                return List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(), program.toString(), output.toString());
            }
        };
    }

    private Path fixture() throws Exception {
        var source = directory.resolve("src/demo/Sample.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
            package demo;
            public class Sample {
                static { try { java.nio.file.Files.write(java.nio.file.Paths.get("TARGET_EXECUTED"), new byte[]{1}); } catch (Exception e) { throw new RuntimeException(e); } }
                public int add(int a, int b) { return a + b; }
                public String choose(int i) { return i == 1 ? "one" : "other"; }
            }
            """);
        Path classes = Files.createDirectories(directory.resolve("classes"));
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null,
            "--release", "8", "-proc:none", "-d", classes.toString(), source.toString()));
        var archive = directory.resolve("sample.jar");
        if (!Files.exists(archive))
            try (var zip = new ZipOutputStream(Files.newOutputStream(archive))) {
                zip.putNextEntry(new ZipEntry("demo/Sample.class")); zip.write(Files.readAllBytes(classes.resolve("demo/Sample.class"))); zip.closeEntry();
            }
        return archive;
    }
}
