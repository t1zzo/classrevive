package dev.classrevive.engine;

import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.io.*;
import java.util.concurrent.*;
import static dev.classrevive.engine.EngineResult.Status.*;

public final class ProcessRunner {
    public EngineResult run(DecompilerEngine engine, Path input, Path directory, Duration timeout) {
        return run(engine, input, directory, timeout, "sources", ".java");
    }
    public EngineResult runCompiler(DecompilerEngine engine, Path input, Path directory, Duration timeout) {
        return run(engine, input, directory, timeout, "classes", ".class");
    }
    public EngineResult runDisassembler(DecompilerEngine engine, Path input, Path directory, Duration timeout) {
        return run(engine, input, directory, timeout, "bytecode", ".txt");
    }
    public EngineResult runTransform(DecompilerEngine engine, Path input, Path directory, Duration timeout) {
        return run(engine, input, directory, timeout, "transformed", ".jar");
    }
    public EngineResult runDiagnostics(DecompilerEngine engine, Path input, Path directory, Duration timeout) {
        return run(engine,input,directory,timeout,"diagnostics",".bin");
    }
    /** Source repair proposals: changed .java files under {@code repaired}. */
    public EngineResult runSourceRepair(DecompilerEngine engine, Path input, Path directory, Duration timeout) {
        return run(engine, input, directory, timeout, "repaired", ".java");
    }
    /** Method composition proposals (Phase 2BD): spliced .java files under {@code spliced/sources}. */
    public EngineResult runMethodSplice(DecompilerEngine engine, Path input, Path directory, Duration timeout) {
        return run(engine, input, directory, timeout, "spliced", ".java");
    }
    public EngineResult runRename(DecompilerEngine engine, Path input, Path directory, Duration timeout) {
        return run(engine, input, directory, timeout, "renamed", ".jar");
    }
    /** Native library triage (Phase 2CC): the external tool writes one JSON export under {@code native}. */
    public EngineResult runNativeAnalysis(DecompilerEngine engine, Path input, Path directory, Duration timeout) {
        return run(engine, input, directory, timeout, "native", ".json");
    }
    /** JVM link checks (define and verify, never initialize): one result file under {@code linked}. */
    public EngineResult runLinkCheck(DecompilerEngine engine, Path input, Path directory, Duration timeout) {
        return run(engine, input, directory, timeout, "linked", ".properties");
    }
    private EngineResult run(DecompilerEngine engine, Path input, Path directory, Duration timeout, String folder, String suffix) {
        if (timeout.isNegative() || timeout.isZero()) throw new IllegalArgumentException("Timeout must be positive");
        long start = System.nanoTime();
        Process process = null;
        Thread drain = null;
        var status = FAILED;
        var diagnosticCode = EngineResult.DiagnosticCode.WORKER_SETUP_FAILED;
        String message = "Worker did not complete";
        int exit = -1;
        long sources = 0;
        boolean restoreInterrupt = false;
        boolean startAttempted = false, directoryCreated = false;
        Throwable failure = null;
        long deadline = start + timeout.toNanos();
        try {
            Files.createDirectory(directory);
            directoryCreated = true;
            Path output = Files.createDirectory(directory.resolve(folder));
            var builder = new ProcessBuilder(engine.command(input.toAbsolutePath(), output.toAbsolutePath()));
            builder.directory(directory.toFile()).redirectErrorStream(true);
            // Do not inherit JVM injection options or target-related class paths.
            builder.environment().keySet().removeIf(key -> key.equalsIgnoreCase("JAVA_TOOL_OPTIONS")
                || key.equalsIgnoreCase("_JAVA_OPTIONS") || key.equalsIgnoreCase("JDK_JAVA_OPTIONS")
                || key.equalsIgnoreCase("CLASSPATH"));
            builder.environment().putAll(engine.environment());
            startAttempted = true;
            process = builder.start();
            diagnosticCode = EngineResult.DiagnosticCode.WORKER_RUNTIME_FAILURE;
            process.getOutputStream().close();
            Process worker = process;
            drain = Thread.ofVirtual().start(() -> drainLog(worker.getInputStream(), directory.resolve("engine.log")));
            while (true) {
                boolean finished = process.waitFor(100, TimeUnit.MILLISECONDS);
                checkDeadline(deadline);
                // While the worker runs it may delete its own state (JADX removes .jadx-state/tmp on close), so only
                // the walk after exit treats a vanished entry as a failure.
                long[] usage = usage(output, deadline, suffix, !finished);
                if (usage[0] > 128L * 1024 * 1024 || usage[1] > 20_000) {
                    status = OUTPUT_LIMIT; diagnosticCode = EngineResult.DiagnosticCode.OUTPUT_BUDGET_EXCEEDED;
                    message = "Worker exceeded output budget"; break;
                }
                if (finished) {
                    exit = process.exitValue(); sources = usage[2];
                    status = exit != 0 ? FAILED : sources == 0 ? NO_SOURCES : OUTPUT_PRODUCED;
                    diagnosticCode = exit != 0 ? EngineResult.DiagnosticCode.ENGINE_EXIT_NONZERO
                        : sources == 0 ? EngineResult.DiagnosticCode.NO_SOURCE_FILES : EngineResult.DiagnosticCode.NONE;
                    message = status == OUTPUT_PRODUCED ? "Sources emitted; correctness not verified" : "See engine.log";
                    break;
                }
            }
        } catch (TimeoutException error) {
            status = TIMED_OUT; diagnosticCode = EngineResult.DiagnosticCode.DEADLINE_EXCEEDED; message = "Worker exceeded deadline";
        } catch (InterruptedException error) {
            status = CANCELLED; diagnosticCode = EngineResult.DiagnosticCode.CANCELLED; message = "Worker interrupted";
            restoreInterrupt = true;
        } catch (IOException | RuntimeException error) {
            diagnosticCode = process != null ? EngineResult.DiagnosticCode.WORKER_RUNTIME_FAILURE
                : startAttempted ? EngineResult.DiagnosticCode.WORKER_START_FAILED : EngineResult.DiagnosticCode.WORKER_SETUP_FAILED;
            failure = error;
            Throwable root = error;
            while (root.getCause() != null && root.getCause() != root) root = root.getCause();
            message = "Worker failure: " + error.getClass().getSimpleName()
                + (root != error ? " (caused by " + root.getClass().getSimpleName() + ")" : "")
                + (directoryCreated ? "; see engine.log" : "");
        } finally {
            restoreInterrupt |= Thread.interrupted();
            if (process != null) {
                if (process.isAlive()) {
                    process.descendants().forEach(child -> child.destroyForcibly());
                    process.destroyForcibly();
                }
                try { process.waitFor(5, TimeUnit.SECONDS); }
                catch (InterruptedException error) { restoreInterrupt = true; }
                try { if (drain != null) drain.join(5000); }
                catch (InterruptedException error) { restoreInterrupt = true; }
                try { process.getInputStream().close(); } catch (IOException ignored) { }
            }
            // The exception names only its type in the result; its chained causes go to the run's own log.
            if (failure != null && directoryCreated) appendCause(failure, directory, input);
            if (restoreInterrupt) Thread.currentThread().interrupt();
        }
        return new EngineResult(engine.id(), engine.version(), status, exit, sources,
            Duration.ofNanos(System.nanoTime() - start).toMillis(), message, diagnosticCode);
    }

    private static void drainLog(InputStream input, Path log) {
        try (input; var output = Files.newOutputStream(log, StandardOpenOption.CREATE_NEW)) {
            byte[] buffer = new byte[8192]; int read; long retained = 0;
            while ((read = input.read(buffer)) != -1) {
                int keep = (int) Math.min(read, 1024 * 1024 - retained);
                if (keep > 0) { output.write(buffer, 0, keep); retained += keep; }
            }
        } catch (IOException ignored) {
            // A killed worker closes its pipe; no unbounded buffering is retained.
        }
    }

    private static void appendCause(Throwable failure, Path directory, Path input) {
        try {
            Files.writeString(directory.resolve("engine.log"), FailureCause.describe(failure, directory, input),
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException | RuntimeException ignored) {
            // The result still carries the failure type when the log cannot be written.
        }
    }

    private static long[] usage(Path output, long deadline, String suffix, boolean workerRunning)
        throws IOException, InterruptedException, TimeoutException {
        var meter = new OutputMeter(output, deadline, suffix, workerRunning);
        Files.walkFileTree(output, meter);
        if (meter.stop instanceof InterruptedException error) throw error;
        if (meter.stop instanceof TimeoutException error) throw error;
        return new long[] {meter.bytes, meter.files, meter.sources};
    }
    private static final class OutputMeter extends SimpleFileVisitor<Path> {
        private final Path output; private final long deadline; private final String suffix; private final boolean workerRunning;
        long bytes, files, sources; Exception stop;
        OutputMeter(Path output, long deadline, String suffix, boolean workerRunning) {
            this.output = output; this.deadline = deadline; this.suffix = suffix; this.workerRunning = workerRunning;
        }
        @Override public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) throws IOException {
            return visit(directory, attributes);
        }
        @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
            return visit(file, attributes);
        }
        @Override public FileVisitResult visitFileFailed(Path file, IOException error) throws IOException {
            return vanished(error);
        }
        @Override public FileVisitResult postVisitDirectory(Path directory, IOException error) throws IOException {
            return error == null ? FileVisitResult.CONTINUE : vanished(error);
        }
        private FileVisitResult visit(Path path, BasicFileAttributes attributes) throws IOException {
            try { checkDeadline(deadline); }
            catch (InterruptedException | TimeoutException error) { stop = error; return FileVisitResult.TERMINATE; }
            if (!path.equals(output)) files++;
            if (attributes.isSymbolicLink()) throw new IOException("Worker emitted symbolic link");
            if (attributes.isRegularFile()) {
                long size = attributes.size(); bytes += size;
                if (size > 0 && path.toString().endsWith(suffix)) sources++;
            }
            return bytes > 128L * 1024 * 1024 || files > 20_000 ? FileVisitResult.TERMINATE : FileVisitResult.CONTINUE;
        }
        /** An entry deleted between listing and visiting holds no budget; any other I/O error still fails the run. */
        private FileVisitResult vanished(IOException error) throws IOException {
            if (workerRunning && error instanceof NoSuchFileException) return FileVisitResult.CONTINUE;
            throw error;
        }
    }
    private static void checkDeadline(long deadline) throws InterruptedException, TimeoutException {
        if (Thread.interrupted()) throw new InterruptedException();
        if (System.nanoTime() >= deadline) throw new TimeoutException();
    }
}
