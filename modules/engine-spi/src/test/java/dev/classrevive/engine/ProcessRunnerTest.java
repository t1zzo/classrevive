package dev.classrevive.engine;

import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ProcessRunnerTest {
    @TempDir Path directory;
    DecompilerEngine engine(String mode) {
        return new DecompilerEngine() {
            public String id() { return "fixture"; }
            public String version() { return "1"; }
            public List<String> command(Path input, Path output) {
                String cp;
                try { cp = Path.of(Worker.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString(); }
                catch (Exception error) { throw new IllegalStateException(error); }
                return List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-Xmx64m", "-cp", cp, Worker.class.getName(), mode, output.toString());
            }
        };
    }
    @Test void distinguishesNonzeroExit() {
        var result = new ProcessRunner().run(engine("fail"), directory, directory.resolve("out"), Duration.ofSeconds(10));
        assertEquals(EngineResult.Status.FAILED, result.status());
        assertEquals(7, result.exitCode());
        assertEquals(EngineResult.DiagnosticCode.ENGINE_EXIT_NONZERO, result.diagnosticCode());
    }
    @Test void zeroExitWithoutSourcesIsNotRecovery() {
        var result = new ProcessRunner().run(engine("empty"), directory, directory.resolve("out"), Duration.ofSeconds(10));
        assertEquals(EngineResult.Status.NO_SOURCES, result.status());
        assertEquals(EngineResult.DiagnosticCode.NO_SOURCE_FILES, result.diagnosticCode());
    }
    @Test void timesOutWorker() {
        long start = System.nanoTime();
        var result = new ProcessRunner().run(engine("sleep"), directory, directory.resolve("out"), Duration.ofMillis(500));
        assertEquals(EngineResult.Status.TIMED_OUT, result.status());
        assertEquals(EngineResult.DiagnosticCode.DEADLINE_EXCEEDED, result.diagnosticCode());
        assertTrue(Duration.ofNanos(System.nanoTime() - start).toSeconds() < 10);
    }
    @Test void drainsLargeLogsWithoutDeadlockingOrRetainingEverything() throws Exception {
        var result = new ProcessRunner().run(engine("noisy"), directory, directory.resolve("out"), Duration.ofSeconds(10));
        assertEquals(EngineResult.Status.OUTPUT_PRODUCED, result.status());
        assertEquals(EngineResult.DiagnosticCode.NONE, result.diagnosticCode());
        assertEquals(1, result.sourceFiles());
        assertTrue(Files.size(directory.resolve("out/engine.log")) <= 1024 * 1024);
    }
    @Test void handlesMissingExecutable() {
        var bad = new DecompilerEngine() {
            public String id() { return "bad"; }
            public String version() { return "1"; }
            public List<String> command(Path input, Path output) { return List.of(directory.resolve("missing-executable").toString()); }
        };
        var result = new ProcessRunner().run(bad, directory, directory.resolve("out"), Duration.ofSeconds(1));
        assertEquals(EngineResult.Status.FAILED, result.status());
        assertEquals(EngineResult.DiagnosticCode.WORKER_START_FAILED, result.diagnosticCode());
    }
    @Test void classifiesWorkerDirectorySetupFailure() throws Exception {
        Path output = Files.createDirectory(directory.resolve("out"));
        var result = new ProcessRunner().run(engine("empty"), directory, output, Duration.ofSeconds(1));
        assertEquals(EngineResult.Status.FAILED, result.status());
        assertEquals(EngineResult.DiagnosticCode.WORKER_SETUP_FAILED, result.diagnosticCode());
    }
    @Test void stopsWorkerOverOutputBudget() {
        var result = new ProcessRunner().run(engine("oversize"), directory, directory.resolve("out"), Duration.ofSeconds(10));
        assertEquals(EngineResult.Status.OUTPUT_LIMIT, result.status());
        assertEquals(EngineResult.DiagnosticCode.OUTPUT_BUDGET_EXCEEDED, result.diagnosticCode());
    }
    @Test void emptyJavaFileIsNotSourceRecovery() {
        var result = new ProcessRunner().run(engine("blank"), directory, directory.resolve("out"), Duration.ofSeconds(10));
        assertEquals(EngineResult.Status.NO_SOURCES, result.status());
        assertEquals(EngineResult.DiagnosticCode.NO_SOURCE_FILES, result.diagnosticCode());
    }
    @Test void entriesDeletedWhileTheWorkerRunsAreNotAFailure() throws Exception {
        // Engines delete their own state while running and at exit (JADX 1.5.3 removes .jadx-state/tmp in close());
        // the in-flight budget walk must not turn a vanished entry into WORKER_RUNTIME_FAILURE.
        var result = new ProcessRunner().run(engine("churn"), directory, directory.resolve("out"), Duration.ofSeconds(30));
        assertEquals(EngineResult.Status.OUTPUT_PRODUCED, result.status(), result + Files.readString(directory.resolve("out/engine.log")));
        assertEquals(EngineResult.DiagnosticCode.NONE, result.diagnosticCode());
        assertEquals(1, result.sourceFiles());
        assertFalse(Files.exists(directory.resolve("out/sources/.state")));
    }
    @Test void cancellationWaitsForWorkerTermination() throws Exception {
        var result = new java.util.concurrent.atomic.AtomicReference<EngineResult>();
        Path out = directory.resolve("out");
        Thread thread = new Thread(() -> result.set(new ProcessRunner().run(engine("pid"), directory, out, Duration.ofSeconds(30))));
        thread.start();
        try {
            Path pidFile = out.resolve("sources/pid.txt");
            long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while ((!Files.exists(pidFile) || Files.size(pidFile) == 0) && System.nanoTime() < deadline) Thread.sleep(20);
            assertTrue(Files.exists(pidFile));
            long pid = Long.parseLong(Files.readString(pidFile));
            thread.interrupt(); thread.join(7000);
            assertFalse(thread.isAlive());
            assertEquals(EngineResult.Status.CANCELLED, result.get().status());
            assertEquals(EngineResult.DiagnosticCode.CANCELLED, result.get().diagnosticCode());
            assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));
        } finally { thread.interrupt(); thread.join(7000); }
    }
    public static class Worker {
        public static void main(String[] args) throws Exception {
            switch (args[0]) {
                case "fail" -> System.exit(7);
                case "sleep" -> Thread.sleep(60_000);
                case "oversize" -> {
                    try (var file = new java.io.RandomAccessFile(Path.of(args[1], "huge.txt").toFile(), "rw")) {
                        file.setLength(129L * 1024 * 1024);
                    }
                    Thread.sleep(60_000);
                }
                case "blank" -> Files.createFile(Path.of(args[1], "Blank.java"));
                case "pid" -> {
                    Files.writeString(Path.of(args[1], "pid.txt"), Long.toString(ProcessHandle.current().pid()));
                    Thread.sleep(60_000);
                }
                case "churn" -> {
                    Path state = Path.of(args[1], ".state");
                    long end = System.nanoTime() + Duration.ofSeconds(3).toNanos();
                    for (int round = 0; System.nanoTime() < end; round++) {
                        Path tmp = Files.createDirectories(state.resolve("tmp").resolve("instance-" + round));
                        for (int i = 0; i < 20; i++) Files.writeString(tmp.resolve("f" + i + ".java"), "x");
                        deleteTree(state);
                    }
                    Files.writeString(Path.of(args[1], "Sample.java"), "class Sample {}");
                }
                case "noisy" -> {
                    for (int i = 0; i < 5000; i++) System.out.println("x".repeat(1000));
                    Files.writeString(Path.of(args[1], "Sample.java"), "class Sample {}");
                }
                default -> { }
            }
        }
        static void deleteTree(Path root) throws java.io.IOException {
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }
}
