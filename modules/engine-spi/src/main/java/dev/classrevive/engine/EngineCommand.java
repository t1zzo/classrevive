package dev.classrevive.engine;

import java.net.JarURLConnection;
import java.nio.file.Path;
import java.util.*;

public final class EngineCommand {
    private EngineCommand() {}
    public static List<String> javaWorker(Class<?> adapter, String worker, List<String> resources, String... arguments) {
        try {
            var paths = new LinkedHashSet<String>();
            paths.add(Path.of(adapter.getProtectionDomain().getCodeSource().getLocation().toURI()).toString());
            for (String resource : resources) {
                var location = adapter.getClassLoader().getResource(resource);
                if (location == null || !(location.openConnection() instanceof JarURLConnection connection))
                    throw new IllegalStateException("Worker dependency missing: " + resource);
                paths.add(Path.of(connection.getJarFileURL().toURI()).toString());
            }
            var command = new ArrayList<String>();
            command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
            command.addAll(List.of("-Xmx256m", "-XX:MaxMetaspaceSize=128m", "-XX:ActiveProcessorCount=2",
                "-Dfile.encoding=UTF-8", "-cp", String.join(java.io.File.pathSeparator, paths), worker));
            command.addAll(Arrays.asList(arguments));
            return List.copyOf(command);
        } catch (Exception error) { throw new IllegalStateException("Cannot locate packaged worker dependencies", error); }
    }
    /** Worker heap cap by size of the class JAR given to a decompiler (Phase 2BB); the cap stays bounded at 1 GiB. */
    static final long SMALL_INPUT = 1L << 20, MEDIUM_INPUT = 4L << 20;

    /**
     * Replaces the default {@code -Xmx256m} of a decompiler command with a heap sized for its input: 256 MiB up to 1 MiB,
     * 512 MiB up to 4 MiB, and 1 GiB above. Vineflower ran out of 256 MiB on CFR 0.152 (2 MiB jar, 1302 classes) and
     * wrote stubs for different classes on each run, so results depended on chance. An unreadable size keeps 256 MiB.
     */
    public static List<String> sizedHeap(Path input, List<String> command) {
        long size;
        try { size = java.nio.file.Files.size(input); } catch (java.io.IOException | RuntimeException error) { size = 0; }
        String heap = size <= SMALL_INPUT ? "-Xmx256m" : size <= MEDIUM_INPUT ? "-Xmx512m" : "-Xmx1g";
        return command.stream().map(argument -> argument.equals("-Xmx256m") ? heap : argument).toList();
    }

    public static List<String> javaJar(Class<?> adapter, String resource, String... arguments) {
        try {
            var location = adapter.getClassLoader().getResource(resource);
            if (location == null || !(location.openConnection() instanceof JarURLConnection connection))
                throw new IllegalStateException("Engine JAR missing: " + resource);
            Path jar = Path.of(connection.getJarFileURL().toURI());
            var command = new ArrayList<String>();
            command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
            command.addAll(List.of("-Xmx256m", "-XX:MaxMetaspaceSize=128m", "-XX:ActiveProcessorCount=2",
                "-Dfile.encoding=UTF-8", "-jar", jar.toString()));
            command.addAll(Arrays.asList(arguments));
            return List.copyOf(command);
        } catch (Exception error) {
            throw new IllegalStateException("Cannot locate packaged engine", error);
        }
    }
}
