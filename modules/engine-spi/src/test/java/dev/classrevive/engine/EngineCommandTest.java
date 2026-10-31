package dev.classrevive.engine;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class EngineCommandTest {
    @TempDir Path dir;

    @Test void heapGrowsWithTheInputButStaysCapped() throws Exception {
        var command = List.of("java", "-Xmx256m", "-XX:MaxMetaspaceSize=128m", "-jar", "engine.jar");
        assertEquals("-Xmx256m", EngineCommand.sizedHeap(file("small.jar", 1024), command).get(1));
        assertEquals("-Xmx256m", EngineCommand.sizedHeap(file("edge.jar", EngineCommand.SMALL_INPUT), command).get(1));
        assertEquals("-Xmx512m", EngineCommand.sizedHeap(file("medium.jar", EngineCommand.SMALL_INPUT + 1), command).get(1));
        assertEquals("-Xmx1g", EngineCommand.sizedHeap(file("large.jar", EngineCommand.MEDIUM_INPUT + 1), command).get(1));
        assertEquals("-Xmx256m", EngineCommand.sizedHeap(dir.resolve("missing.jar"), command).get(1));
        // Every other argument is kept as is.
        assertEquals(List.of("java", "-Xmx1g", "-XX:MaxMetaspaceSize=128m", "-jar", "engine.jar"),
            EngineCommand.sizedHeap(file("huge.jar", 64L << 20), command));
    }

    private Path file(String name, long size) throws Exception {
        Path path = dir.resolve(name);
        try (var channel = java.nio.channels.FileChannel.open(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            if (size > 0) channel.write(java.nio.ByteBuffer.wrap(new byte[] {0}), size - 1);
        }
        return path;
    }
}
