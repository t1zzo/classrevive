package dev.classrevive.workbench;

import java.nio.file.*;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Phase G5: the obfuscator classification and the Skidfuscator reversal on the committed fixtures (nothing is run). */
class ObfuscationCheckTest {
    static final Path FIXTURES = Path.of("../../benchmarks/obfuscated");
    @TempDir Path directory;

    @Test void recognizesSkidfuscatorAndNotTheCleanOrProguardJars() throws Exception {
        var skid = ObfuscationCheck.analyze(FIXTURES.resolve("skidfuscator/skid-fixture-obf.jar"));
        assertEquals("SUGGESTED", skid.verdict());
        assertEquals(ObfuscationCheck.SKIDFUSCATOR, skid.top().obfuscator(), skid.hypotheses().toString());
        assertTrue(skid.skidfuscator(), skid.top().toString());
        assertFalse(skid.top().reasons().isEmpty());
        assertFalse(skid.techniques().isEmpty());
        assertTrue(skid.classes() > 0);

        var clean = ObfuscationCheck.analyze(FIXTURES.resolve("skidfuscator/skid-fixture.jar"));
        assertFalse(clean.skidfuscator(), clean.summary());
        var proguard = ObfuscationCheck.analyze(FIXTURES.resolve("proguard8/obf8.jar"));
        assertFalse(proguard.skidfuscator(), proguard.summary());
    }

    @Test void reversesTheSkidfuscatorFixtureIntoClassesTheVerifierAccepts() throws Exception {
        var result = SkidReversal.run(FIXTURES.resolve("skidfuscator/skid-fixture-obf.jar"), directory.resolve("reversal"), Duration.ofSeconds(300));
        assertTrue(result.usable(), result.toString());
        assertTrue(Files.isRegularFile(result.jar()));
        assertTrue(result.linkedClasses() > 0);
        // The reversed jar no longer looks like Skidfuscator to the classifier.
        assertFalse(ObfuscationCheck.analyze(result.jar()).skidfuscator());
    }
}
