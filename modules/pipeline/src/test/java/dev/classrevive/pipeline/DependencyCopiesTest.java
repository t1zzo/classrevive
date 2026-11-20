package dev.classrevive.pipeline;

import dev.classrevive.cfr.CfrEngine;
import dev.classrevive.vineflower.VineflowerEngine;
import dev.classrevive.verification.VerificationResult;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.zip.*;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Phase 2DW: the dependency jar copies go at the end of the job; each folder's dependencies.txt stays. */
class DependencyCopiesTest {
    @TempDir Path dir;

    @Test void removesOnlyTheCopiesAndKeepsTheEvidence() throws Exception {
        var output = dir.resolve("out");
        Path attempt = Files.createDirectories(output.resolve("selection/checks/attempt-0-dependencies"));
        Files.write(attempt.resolve("0-input.jar"), new byte[100]);
        Files.write(attempt.resolve("0-classes.jar"), new byte[50]);
        Files.writeString(attempt.resolve("dependencies.txt"), "0 sha256=abc classes=1\n");
        Path perFile = Files.createDirectories(output.resolve("per-file-dependencies"));
        Files.write(perFile.resolve("12-classes.jar"), new byte[10]);
        Files.write(perFile.resolve("notes.jar"), new byte[7]);                       // not a name the copies use
        Path stubs = Files.createDirectories(output.resolve("stubs"));
        Files.write(stubs.resolve("0-input.jar"), new byte[5]);                       // not in a *-dependencies folder
        var removed = DependencyCopies.remove(output);
        assertEquals(new DependencyCopies.Removed(2, 3, 160), removed);
        assertFalse(Files.exists(attempt.resolve("0-input.jar")) || Files.exists(attempt.resolve("0-classes.jar"))
            || Files.exists(perFile.resolve("12-classes.jar")));
        assertTrue(Files.exists(attempt.resolve("dependencies.txt")));
        assertTrue(Files.exists(perFile.resolve("notes.jar")) && Files.exists(stubs.resolve("0-input.jar")));
        assertEquals(Map.of("bytes", 160L, "files", 3, "folders", 2, "kept", "dependencies.txt in each folder: every jar's SHA-256 and class count"),
            DependencyCopies.json(removed));
        assertNull(DependencyCopies.json(new DependencyCopies.Removed(0, 0, 0)));
        assertEquals("", DependencyCopies.text(new DependencyCopies.Removed(0, 0, 0)));
    }

    @Test void aJobWithADependencyEndsWithoutTheCopies() throws Exception {
        // Target calls Library, which only the dependency jar supplies: every compile stage needs the jar, so every
        // stage made its copies; none is left once the job is done.
        var sources = Files.createDirectories(dir.resolve("src"));
        Files.writeString(sources.resolve("Library.java"), "public class Library { public static int value() { return 42; } }");
        Files.writeString(sources.resolve("Target.java"), "public class Target { public int value() { return Library.value(); } }");
        var classes = Files.createDirectories(dir.resolve("classes"));
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "--release", "8", "-Xlint:-options", "-proc:none",
            "-d", classes.toString(), sources.resolve("Library.java").toString(), sources.resolve("Target.java").toString()));
        var library = dir.resolve("lib.jar");
        try (var zip = new ZipOutputStream(Files.newOutputStream(library))) {
            zip.putNextEntry(new ZipEntry("Library.class")); zip.write(Files.readAllBytes(classes.resolve("Library.class"))); zip.closeEntry();
        }
        var app = dir.resolve("app.jar");
        try (var zip = new ZipOutputStream(Files.newOutputStream(app))) {
            zip.putNextEntry(new ZipEntry("Target.class")); zip.write(Files.readAllBytes(classes.resolve("Target.class"))); zip.closeEntry();
        }
        var output = dir.resolve("out");
        var job = new DecompilationJob().runVerified(app, output, List.of(new CfrEngine(), new VineflowerEngine()), Duration.ofSeconds(60),
            List.of(library));
        assertEquals(VerificationResult.Status.COMPILED, job.selection().verification().status());
        var copies = new ArrayList<Path>(); var evidence = new ArrayList<Path>();
        try (var paths = Files.walk(output)) {
            for (var path : paths.toList()) {
                String name = path.getFileName().toString();
                if (path.getParent() != null && path.getParent().getFileName().toString().endsWith("-dependencies")) {
                    if (name.matches("\\d+-(input|classes)\\.jar")) copies.add(path);
                    if (name.equals("dependencies.txt")) evidence.add(path);
                }
            }
        }
        assertEquals(List.of(), copies);
        assertFalse(evidence.isEmpty());
        assertTrue(Files.readString(evidence.getFirst()).contains("sha256="), Files.readString(evidence.getFirst()));
        String json = Files.readString(output.resolve("report.json"));
        assertTrue(json.contains("\"dependency_copies_removed\":{\"bytes\":"), json);
        assertTrue(Files.readString(output.resolve("report.txt")).contains("Dependency jar copies removed after the job (Phase 2DW): "));
        assertTrue(Files.exists(library), "the user's own jar is never touched");
        try (var lib = Files.list(output.resolve("project/lib"))) { assertTrue(lib.findAny().isPresent(), "the IDE project keeps its library"); }
    }

    @Test void aJobWithoutDependenciesRecordsNothing() throws Exception {
        var source = Files.createDirectories(dir.resolve("plain"));
        Files.writeString(source.resolve("Plain.java"), "public class Plain { public int one() { return 1; } }");
        var classes = Files.createDirectories(dir.resolve("plain-classes"));
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "--release", "8", "-Xlint:-options", "-proc:none",
            "-d", classes.toString(), source.resolve("Plain.java").toString()));
        var app = dir.resolve("plain.jar");
        try (var zip = new ZipOutputStream(Files.newOutputStream(app))) {
            zip.putNextEntry(new ZipEntry("Plain.class")); zip.write(Files.readAllBytes(classes.resolve("Plain.class"))); zip.closeEntry();
        }
        var output = dir.resolve("plain-out");
        new DecompilationJob().runVerified(app, output, List.of(new CfrEngine()), Duration.ofSeconds(60));
        assertFalse(Files.readString(output.resolve("report.json")).contains("dependency_copies_removed"));
        assertFalse(Files.readString(output.resolve("report.txt")).contains("Dependency jar copies removed"));
    }
}
