package dev.classrevive.pipeline;

import dev.classrevive.cfr.CfrEngine;
import dev.classrevive.engine.DecompilerEngine;
import dev.classrevive.jadx.JadxEngine;
import dev.classrevive.procyon.ProcyonEngine;
import dev.classrevive.vineflower.VineflowerEngine;
import java.nio.file.*;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The committed failure-lab regression cases (benchmarks/regressions, Phase 2AT): every method of every case must stay
 * at or above the level recorded in its expected.json. Run alone with {@code ./gradlew checkRegressions}.
 */
class RegressionCasesTest {
    @TempDir Path dir;

    @Test void committedCasesKeepTheirMinimumLevels() throws Exception {
        String location = System.getProperty("classrevive.regressions");
        assertNotNull(location, "the Gradle build passes benchmarks/regressions");
        List<DecompilerEngine> engines = List.of(new CfrEngine(), new VineflowerEngine(), new ProcyonEngine(), new JadxEngine());
        var report = new RegressionSuite().check(Path.of(location), dir.resolve("check"), engines, Duration.ofSeconds(60));
        String details = Files.readString(dir.resolve("check/regressions.md"));
        assertTrue(report.cases() >= 1, details);
        assertEquals(List.of(), report.errors(), details);
        assertEquals(List.of(), report.regressions(), details);
        assertTrue(report.passed(), details);
    }
}
