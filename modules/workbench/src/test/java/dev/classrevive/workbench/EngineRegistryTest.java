package dev.classrevive.workbench;

import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class EngineRegistryTest {
    @Test void listsTheFourBundledEnginesInTheirFixedOrder() {
        assertEquals(List.of("cfr", "vineflower", "procyon", "jadx"), EngineRegistry.all().stream().map(e -> e.id()).toList());
        assertEquals(List.of("cfr", "vineflower", "procyon", "jadx"), EngineRegistry.parse("all").stream().map(e -> e.id()).toList());
        assertEquals(List.of("jadx", "cfr"), EngineRegistry.parse("jadx,cfr").stream().map(e -> e.id()).toList());
    }

    @Test void rejectsUnknownAndDuplicateEnginesWithTheCliMessages() {
        assertEquals("Duplicate engine: cfr", assertThrows(IllegalArgumentException.class, () -> EngineRegistry.parse("cfr,cfr")).getMessage());
        assertEquals("Unknown engine: fernflower. Use cfr,vineflower,procyon,jadx or all.",
            assertThrows(IllegalArgumentException.class, () -> EngineRegistry.parse("fernflower")).getMessage());
        assertThrows(IllegalArgumentException.class, () -> EngineRegistry.parse(""));
    }

    @Test void requestsKeepTheCliLimits() {
        Path in = Path.of("in.jar"), out = Path.of("out");
        var request = DecompileRequest.defaults(in, out);
        assertEquals(List.of("cfr", "vineflower"), request.engines().stream().map(e -> e.id()).toList());
        assertEquals(Duration.ofSeconds(120), request.timeout());
        assertTrue(request.stubs());
        assertNull(request.engineCache());
        assertEquals("Timeout must be 1..3600 seconds", assertThrows(IllegalArgumentException.class,
            () -> new DecompileRequest(in, out, EngineRegistry.all(), Duration.ofMillis(999), List.of(), null, true)).getMessage());
        assertThrows(IllegalArgumentException.class, () -> new DecompileRequest(in, out, EngineRegistry.all(), Duration.ofSeconds(3601), List.of(), null, true));
        assertEquals("Select one to four engines", assertThrows(IllegalArgumentException.class,
            () -> new DecompileRequest(in, out, List.of(), Duration.ofSeconds(1), List.of(), null, true)).getMessage());
        var seventeen = Collections.nCopies(17, Path.of("lib.jar"));
        assertEquals("At most 16 dependency JARs", assertThrows(IllegalArgumentException.class,
            () -> new DecompileRequest(in, out, EngineRegistry.all(), Duration.ofSeconds(60), seventeen, null, true)).getMessage());
        assertEquals(Duration.ofSeconds(3600), RequestLimits.timeoutSeconds(3600));
        assertThrows(IllegalArgumentException.class, () -> RequestLimits.timeoutSeconds(0));
        var dependencies = new ArrayList<>(List.of(Path.of("a.jar")));
        var copy = new DecompileRequest(in, out, EngineRegistry.all(), Duration.ofSeconds(60), dependencies, null, false);
        dependencies.add(Path.of("b.jar"));
        assertEquals(1, copy.dependencies().size());
    }
}
