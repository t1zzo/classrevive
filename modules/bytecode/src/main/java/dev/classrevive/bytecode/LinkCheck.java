package dev.classrevive.bytecode;

import dev.classrevive.engine.*;
import java.io.IOException;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;

/**
 * Asks {@link LinkWorker}, in a fresh JVM with bytecode verification on, whether every class of a JAR links. It is the
 * validity gate of failure minimization (Phase 2AT): a reduced class is kept only when the real JVM verifier accepts
 * it. No target code is initialized or executed.
 */
public final class LinkCheck {
    /** {@code errorType} is the JVM error class (or {@code WORKER_<status>}); {@code message} may name target classes. */
    public record Result(boolean linked, int classes, String failedClass, String errorType, String message) {}

    public Result check(Path classesJar, Path work, List<Path> dependencies, Duration timeout) throws IOException {
        Path workerLocation;
        try { workerLocation = Path.of(LinkWorker.class.getProtectionDomain().getCodeSource().getLocation().toURI()); }
        catch (Exception error) { throw new IOException("Cannot locate link worker", error); }
        var jars = List.copyOf(dependencies);
        var engine = new DecompilerEngine() {
            public String id() { return "jvm-link"; }
            public String version() { return System.getProperty("java.version"); }
            public List<String> command(Path input, Path output) {
                var command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-Xmx256m", "-XX:MaxMetaspaceSize=128m", "-XX:ActiveProcessorCount=2",
                    "-Dfile.encoding=UTF-8", "-cp", workerLocation.toString(), LinkWorker.class.getName(), input.toString(), output.toString()));
                jars.forEach(jar -> command.add(jar.toAbsolutePath().toString()));
                return command;
            }
        };
        var result = new ProcessRunner().runLinkCheck(engine, classesJar.toAbsolutePath(), work, timeout);
        Path properties = work.resolve("linked").resolve("link.properties");
        if (result.exitCode() != 0 || !Files.isRegularFile(properties, LinkOption.NOFOLLOW_LINKS) || Files.size(properties) > 64 * 1024)
            return new Result(false, 0, null, "WORKER_" + result.status(), result.message());
        var values = new Properties();
        try (var input = Files.newInputStream(properties)) { values.load(input); }
        int classes;
        try { classes = Integer.parseInt(values.getProperty("classes", "-1")); } catch (NumberFormatException error) { classes = -1; }
        if (classes < 0) throw new IOException("Invalid link check result");
        return new Result(Boolean.parseBoolean(values.getProperty("linked")), classes, values.getProperty("failed-class"),
            values.getProperty("error-type"), values.getProperty("message"));
    }
}
