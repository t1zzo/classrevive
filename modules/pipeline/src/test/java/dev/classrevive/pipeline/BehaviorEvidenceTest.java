package dev.classrevive.pipeline;

import dev.classrevive.sandbox.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class BehaviorEvidenceTest {
    @TempDir Path directory;
    @Test void recordsContextAndHashesWithoutRawOutputAndRefusesOverwrite() throws Exception {
        var observation=new BehaviorObservation("case1","a".repeat(64),"sha256:"+"b".repeat(64),"isolation-v1",true,
            BehaviorObservation.Outcome.COMPLETED,0,"private output".getBytes(),new byte[0],false);
        Path file=directory.resolve("behavior.json");
        BehaviorEvidence.write(file,"c".repeat(64),"d".repeat(64),List.of(observation),List.of(observation));
        String text=Files.readString(file);
        assertTrue(text.contains("MATCHED_CASES")); assertTrue(text.contains("isolation-v1"));
        assertTrue(text.contains("FINITE_TEST_OBSERVATIONS_ONLY")); assertFalse(text.contains("private output"));
        assertThrows(java.io.IOException.class,()->BehaviorEvidence.write(file,"c".repeat(64),"d".repeat(64),List.of(observation),List.of(observation)));
    }
}
