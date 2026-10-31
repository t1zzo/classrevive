package dev.classrevive.pipeline;

import dev.classrevive.engine.DecompilerEngine;
import dev.classrevive.verification.VerificationResult;
import dev.classrevive.vineflower.VineflowerEngine;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Gson 2.13.1 (Apache-2.0) is a real multi-release JAR ({@code META-INF/versions/9/module-info.class}) whose sources
 * need error_prone_annotations at compile time. Gradle resolves both from its cache with hash verification
 * (pipeline/build.gradle.kts); they are copied here and decompiled, never committed, loaded or executed.
 */
class RealMultiReleaseLibraryTest {
    static final String GSON_SHA256 = "94855942d4992f112946d3de1c334e709237b8126d8130bf07807c018a4a2120";
    static final String ANNOTATIONS_SHA256 = "6661d5335090a5fc61dd869d2095bc6c1e2156e3aa47a6e4ababdf64c99a7889";
    @TempDir Path directory;

    Path copy(String property, String sha256) throws Exception {
        String location = System.getProperty("classrevive.fixture." + property);
        assertNotNull(location, "Gradle passes the resolved fixture path");
        Path copy = directory.resolve(Path.of(location).getFileName().toString());
        Files.copy(Path.of(location), copy);
        assertEquals(sha256, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(copy))));
        return copy;
    }

    @Test void decompilesTheBaseVersionAndCompilesOnlyWithTheExplicitAnnotationDependency() throws Exception {
        Path gson = copy("gson", GSON_SHA256), annotations = copy("error_prone_annotations", ANNOTATIONS_SHA256);
        List<DecompilerEngine> engines = List.of(new VineflowerEngine());

        Path bare = directory.resolve("bare");
        // Stubs off (Phase 2CT): this checks what javac reports when the annotation JAR is really absent.
        var withoutDependency = new DecompilationJob().withStubs(false).runVerified(gson, bare, engines, Duration.ofSeconds(120));
        String report = Files.readString(bare.resolve("report.txt"));
        assertTrue(report.contains("Multi-release: base version decompiled; 1 META-INF/versions variant class(es) ignored (manifest Multi-Release: true)"), report);
        assertTrue(report.contains("META-INF/versions/9/module-info.class (Java 9, no base class)"), report);
        assertTrue(Files.readString(bare.resolve("report.json")).contains("\"multi_release\""));
        try (var zip = new ZipFile(bare.resolve("classes.jar").toFile())) {
            assertTrue(zip.stream().noneMatch(entry -> entry.getName().startsWith("META-INF/")));
        }
        var bareVerification = withoutDependency.selection().verification();
        assertEquals(0, bareVerification.syntaxErrors(), "Vineflower output parses; the failure is attribution");
        assertTrue(Files.readString(bare.resolve("selection/checks/attempt-0/engine.log"))
            .contains("package com.google.errorprone.annotations does not exist"));

        // Without source repair (as in a single-engine benchmark baseline) the remaining errors are decompiler output:
        // generic casts and a final local assigned twice. Classes compile only partially.
        Path resolved = directory.resolve("resolved");
        var withDependency = new DecompilationJob().runVerified(gson, resolved, engines, Duration.ofSeconds(120), List.of(annotations), false);
        var verification = withDependency.selection().verification();
        assertEquals("vineflower", withDependency.selection().candidate());
        assertEquals(0, verification.syntaxErrors());
        assertFalse(Files.readString(resolved.resolve("selection/checks/attempt-0/engine.log")).contains("com.google.errorprone"));
        assertTrue(Files.readString(resolved.resolve("selection/checks/attempt-0-dependencies/dependencies.txt"))
            .contains("multi-release-variants-ignored=1"));
        assertTrue(verification.matchedSymbols() > bareVerification.matchedSymbols(),
            verification.matchedSymbols() + " vs " + bareVerification.matchedSymbols());
        assertEquals(VerificationResult.Status.COMPILE_FAILED, verification.status());
        assertTrue(verification.errors() > 0);
        assertEquals(List.of(), withDependency.selection().repairs());
        assertTrue(Files.readString(resolved.resolve("report.json")).contains("\"source_repairs\":{\"count\":0,"));

        // Phase 2AS: the compiler-guided repair pass edits only the reported positions and recompiles.
        Path repaired = directory.resolve("repaired");
        var withRepair = new DecompilationJob().runVerified(gson, repaired, engines, Duration.ofSeconds(120), List.of(annotations));
        var repairedVerification = withRepair.selection().verification();
        String selection = Files.readString(repaired.resolve("selection/selection.txt"));
        assertTrue(withRepair.selection().candidate().startsWith("repair-"), selection);
        assertEquals(VerificationResult.Status.COMPILED, repairedVerification.status(), selection);
        assertEquals(0, repairedVerification.errors());
        assertEquals(repairedVerification.expectedSymbols(), repairedVerification.matchedSymbols(), selection);
        assertTrue(repairedVerification.roundTripMeasured());
        var rules = new TreeSet<String>(); var files = new TreeSet<String>();
        withRepair.selection().repairs().forEach(edit -> { rules.add(edit.rule()); files.add(edit.file()); });
        assertEquals(Set.of("raw-cast-to-target", "raw-argument", "raw-diamond", "raw-type-argument", "drop-final-local", "capture-copy",
            "renamed-local"), rules, selection);
        assertTrue(files.contains("com/google/gson/internal/bind/ReflectiveTypeAdapterFactory.java"), files.toString());
        // Every repaired file came from a file javac reported, and each edit names its diagnostic.
        assertTrue(verification.fileErrors().keySet().containsAll(files), files + " vs " + verification.fileErrors().keySet());
        assertTrue(withRepair.selection().repairs().stream().allMatch(edit -> edit.code().startsWith("compiler.err.") && edit.line() > 0));
        files.forEach(file -> assertTrue(withRepair.selection().provenance().get(file).endsWith("+repair"), file));
        assertTrue(Files.readString(repaired.resolve("report.txt")).contains("Source repairs: " + withRepair.selection().repairs().size()));
        String json = Files.readString(repaired.resolve("report.json"));
        assertTrue(json.contains("\"evidence\":\"COMPILER_DIAGNOSTIC_GUIDED_EDITS_BEHAVIOR_NOT_VERIFIED\""), json.substring(0, 200));
        assertTrue(json.contains("\"rule\":\"capture-copy\""));
        // Gson's own sources contain text that the placeholder heuristic counts, so "complete" stays false on that alone.
        assertTrue(repairedVerification.placeholderSignals() > 0 || repairedVerification.complete());
    }
}
