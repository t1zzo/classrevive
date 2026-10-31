package dev.classrevive.sandbox;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SandboxPolicyTest {
    @TempDir Path directory;
    String image="sha256:"+"a".repeat(64);
    @Test void constructsRestrictedLocalContainerCommand() {
        var command=SandboxPolicy.command("docker","unix:///var/run/docker.sock",image,"classrevive-test",directory,"example.Main",List.of("hello world"));
        for(String value:List.of("--network=none","--read-only","--cap-drop=ALL","--security-opt=no-new-privileges","--user=65534:65534","--pull=never","--memory=512m","--pids-limit=64")) assertTrue(command.contains(value));
        assertEquals("hello world",command.getLast());
        assertTrue(command.stream().anyMatch(s->s.contains("destination=/input,readonly")));
        assertFalse(command.contains("--privileged")); assertFalse(command.contains("--pid=host"));
    }
    @Test void rejectsRemoteMutableAndInjectedInputs() {
        assertThrows(IllegalArgumentException.class,()->SandboxPolicy.command("docker","tcp://server:2375",image,"classrevive-test",directory,"Main",List.of()));
        assertThrows(IllegalArgumentException.class,()->SandboxPolicy.command("docker","unix:///var/run/docker.sock","java:latest","classrevive-test",directory,"Main",List.of()));
        assertThrows(IllegalArgumentException.class,()->SandboxPolicy.command("docker","unix:///var/run/docker.sock",image,"classrevive-test",directory,"-jar",List.of()));
        assertThrows(IllegalArgumentException.class,()->SandboxPolicy.command("docker","unix:///var/run/docker.sock",image,"classrevive-test",directory.resolve("a,b"),"Main",List.of()));
    }
}
