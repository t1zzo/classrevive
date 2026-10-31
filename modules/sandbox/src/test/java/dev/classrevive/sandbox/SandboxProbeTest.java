package dev.classrevive.sandbox;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.Duration;
import static org.junit.jupiter.api.Assertions.*;

class SandboxProbeTest {
    @TempDir Path directory;
    @Test void missingDockerFailsClosedWithoutFallback() throws Exception {
        var result=new SandboxProbe().check(directory.resolve("no-such-docker-executable").toString(),Duration.ofSeconds(1));
        assertFalse(result.available()); assertEquals("UNAVAILABLE",result.status());
    }
    @Test void rejectsUnboundedProbeTimeouts() {
        for(var timeout:new Duration[]{Duration.ZERO,Duration.ofSeconds(-1),Duration.ofSeconds(31)})
            assertThrows(IllegalArgumentException.class,()->new SandboxProbe().check("docker",timeout));
    }
}
