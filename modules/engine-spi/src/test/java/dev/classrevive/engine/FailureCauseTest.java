package dev.classrevive.engine;

import java.io.UncheckedIOException;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class FailureCauseTest {
    @TempDir Path directory;
    @Test void recordsChainedCauseWithoutAbsoluteOrTargetDerivedPaths() {
        Path vanished = directory.resolve("run/sources/.jadx-state/tmp/jadx-instance-42/com/secret/Target.java");
        var error = new UncheckedIOException(new NoSuchFileException(vanished.toString()));
        String text = FailureCause.describe(error, directory.resolve("run"), directory.resolve("input.jar"));
        assertTrue(text.contains("host-side worker failure: java.io.UncheckedIOException"), text);
        assertTrue(text.contains("caused by: java.nio.file.NoSuchFileException"), text);
        assertTrue(text.contains("location=<run>/sources/.jadx-state/tmp/jadx-instance-42/<3 redacted segments>"), text);
        assertFalse(text.contains(directory.toString()), text);
        assertFalse(text.contains("secret") || text.contains("Target.java"), text);
    }
    @Test void keepsNoSegmentOfTargetOutputOutsideEngineState() {
        assertEquals("<run>/sources/<2 redacted segments>",
            FailureCause.location(directory.resolve("sources/demo/Sample.java").toString(), directory));
        assertEquals("<outside run directory>", FailureCause.location(directory.getParent().resolve("x").toString(), directory));
    }
    @Test void redactsPathsInsideMessages() {
        var error = new IllegalStateException("Cannot open " + directory.resolve("input.jar") + " for reading");
        String text = FailureCause.describe(error, directory.resolve("run"), directory.resolve("input.jar"));
        assertTrue(text.contains("Cannot open <input>/… for reading"), text);
        assertFalse(text.contains(directory.toString()), text);
    }
}
