package dev.classrevive.workbench;

import dev.classrevive.engine.*;
import dev.classrevive.pipeline.JobListener;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.*;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class DecompileServiceTest {
    @TempDir Path directory;

    /** Records every callback as text, from any thread. */
    static final class Recorder implements JobListener {
        final List<String> events = Collections.synchronizedList(new ArrayList<>());
        @Override public void stage(Stage stage) { events.add(stage.name()); }
        @Override public void engineStarted(String engine) { events.add("start:" + engine); }
        @Override public void engineFinished(EngineResult result) { events.add("finish:" + result.engine() + ":" + result.status()); }
    }

    @Test void reportsStagesAndEnginesInOrderAndWritesTheSameEvidence() throws Exception {
        var input = jar("demo/Sample.java", """
            package demo;
            public class Sample { public int add(int a, int b) { return a + b; } }
            """, null);
        var recorder = new Recorder();
        var request = new DecompileRequest(input, directory.resolve("out"), EngineRegistry.parse("cfr,vineflower"), Duration.ofSeconds(60),
            List.of(), null, true);
        var decompiled = new DecompileService().decompile(request, recorder);
        var events = List.copyOf(recorder.events);
        assertTrue(decompiled.job().selection().verification().complete(), events.toString());
        assertEquals(directory.resolve("out").toAbsolutePath().normalize(), decompiled.output());
        assertNull(decompiled.origin());
        assertTrue(Files.isRegularFile(decompiled.output().resolve("report.json")));
        // Stages in order; a complete selection needs neither the per-file measurement nor the fallback.
        assertEquals(List.of("INTAKE", "ENGINES", "STUBS", "SELECTION", "REPORTS"),
            events.stream().filter(e -> !e.contains(":")).toList());
        // Each engine starts and finishes between ENGINES and STUBS, in any order between engines.
        int engines = events.indexOf("ENGINES"), stubs = events.indexOf("STUBS");
        for (String id : List.of("cfr", "vineflower")) {
            int start = events.indexOf("start:" + id), finish = events.indexOf("finish:" + id + ":OUTPUT_PRODUCED");
            assertTrue(engines < start && start < finish && finish < stubs, events.toString());
        }
        assertEquals(9, events.size(), events.toString());
        assertNull(new DecompileService().triageNative(decompiledWithoutJar(), null));
    }

    @Test void reportsTheMeasurementAndFallbackOfAPartialResult() throws Exception {
        // lib/Missing is used but not supplied, and stubs are off: the candidate cannot compile.
        var input = jar("app/Use.java", """
            package app;
            public class Use { public int call() { return lib.Missing.value(); } }
            """, "package lib; public class Missing { public static int value() { return 1; } }");
        var recorder = new Recorder();
        var request = new DecompileRequest(input, directory.resolve("partial"), EngineRegistry.parse("cfr"), Duration.ofSeconds(60),
            List.of(), null, false);
        var decompiled = new DecompileService().decompile(request, recorder);
        assertFalse(decompiled.job().selection().verification().complete());
        assertEquals(List.of("INTAKE", "ENGINES", "start:cfr", "finish:cfr:OUTPUT_PRODUCED", "SELECTION", "PER_FILE", "FALLBACK", "REPORTS"),
            recorder.events);
    }

    /** Phase G4: per-method outcomes, the selected sources' javac errors and the compiler fingerprint reach the index. */
    @Test void resultsCarryMethodOutcomesJavacErrorsAndTheCompiler() throws Exception {
        var input = jar("app/Use.java", """
            package app;
            public class Use {
                public int call() { return lib.Missing.value(); }
                public int twice(int x) { return x * 2; }
            }
            """, "package lib; public class Missing { public static int value() { return 1; } }");
        var request = new DecompileRequest(input, directory.resolve("errors"), EngineRegistry.parse("cfr"), Duration.ofSeconds(60),
            List.of(), null, false);
        var output = new DecompileService().decompile(request, JobListener.NONE).output();
        assertTrue(Files.isRegularFile(output.resolve("selection/diagnostics.tsv")), "javac errors kept beside the selected sources");
        var index = ResultIndex.open(output);
        var errors = index.diagnostics("app/Use.java");
        assertFalse(errors.isEmpty());
        assertTrue(errors.stream().allMatch(error -> error.line() > 0 && error.code().startsWith("compiler.err.")), errors.toString());
        assertTrue(index.diagnostics("app/Other.java").isEmpty());
        assertEquals("Java", index.compiler().language());
        // Two tiny classes carry no javac-only trace (lambdas, enum switch maps), so the compiler stays undetermined.
        assertTrue(Set.of("javac", "undetermined").contains(index.compiler().javaCompiler()), index.compiler().toString());

        var good = jar("demo/Ok.java", "package demo; public class Ok { public int twice(int x) { return x * 2; } }", null);
        var compiled = ResultIndex.open(new DecompileService().decompile(new DecompileRequest(good, directory.resolve("ok"), EngineRegistry.parse("cfr"),
            Duration.ofSeconds(60), List.of(), null, true), JobListener.NONE).output());
        assertTrue(compiled.diagnostics().isEmpty(), "a compiling result has no diagnostics file");
        var methods = compiled.classes().get("demo/Ok").methods();
        assertEquals(ResultIndex.Outcome.IDENTICAL, methods.get("twice(I)I"), methods.toString());
        assertTrue(methods.containsKey("<init>()V"));
    }

    @Test void interruptingTheJobStopsItsWorkersWithoutOrphans() throws Exception {
        var input = jar("demo/Sample.java", "package demo; public class Sample {}", null);
        var sleeper = sleeper();
        var started = new CountDownLatch(1);
        var listener = new JobListener() { @Override public void engineStarted(String engine) { started.countDown(); } };
        var request = new DecompileRequest(input, directory.resolve("cancelled"), List.of(sleeper), Duration.ofSeconds(600), List.of(), null, true);
        var failure = new AtomicReference<Throwable>();
        var job = new Thread(() -> {
            try { new DecompileService().decompile(request, listener); failure.set(new AssertionError("job finished")); }
            catch (Throwable error) { failure.set(error); }
        });
        job.start();
        assertTrue(started.await(60, TimeUnit.SECONDS));
        // The worker writes its own pid, so the check below names that process and not another child of this JVM.
        Path pidFile = directory.resolve("sleeper.pid");
        assertTrue(waitFor(() -> Files.isRegularFile(pidFile) && pid(pidFile) > 0), "worker JVM never started");
        var worker = ProcessHandle.of(pid(pidFile)).orElseThrow();
        assertTrue(worker.isAlive());
        long before = System.nanoTime();
        job.interrupt();
        job.join(60_000);
        assertFalse(job.isAlive());
        assertInstanceOf(InterruptedException.class, failure.get());
        assertTrue(Duration.ofNanos(System.nanoTime() - before).toSeconds() < 30, "cancellation was not prompt");
        assertTrue(waitFor(() -> !worker.isAlive()), "orphan worker left running: " + describe(worker));
    }

    private static long pid(Path file) {
        try { return Long.parseLong(Files.readString(file).trim()); } catch (Exception notYet) { return -1; }
    }

    private static String describe(ProcessHandle process) {
        return process.pid() + " " + process.info().command().orElse("?") + " parent=" + process.parent().map(ProcessHandle::pid).orElse(-1L);
    }

    private DecompileService.Decompiled decompiledWithoutJar() throws Exception {
        var classOnly = Files.createDirectories(directory.resolve("class-only"));
        return new DecompileService.Decompiled(classOnly, null, null);
    }

    /** A worker that never ends, standing in for a slow decompiler. */
    private DecompilerEngine sleeper() throws Exception {
        var source = Files.createDirectories(directory.resolve("sleeper")).resolve("Sleeper.java");
        Files.writeString(source, """
            public class Sleeper {
                public static void main(String[] a) throws Exception {
                    java.nio.file.Files.writeString(java.nio.file.Path.of(a[0]), Long.toString(ProcessHandle.current().pid()));
                    Thread.sleep(600_000);
                }
            }
            """);
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "-proc:none", source.toString()));
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Path classes = source.getParent(), pidFile = directory.resolve("sleeper.pid");
        return new DecompilerEngine() {
            @Override public String id() { return "sleeper"; }
            @Override public String version() { return "test"; }
            @Override public List<String> command(Path in, Path out) { return List.of(java, "-cp", classes.toString(), "Sleeper", pidFile.toString()); }
        };
    }

    private Path jar(String path, String source, String library) throws Exception {
        var sources = Files.createDirectories(directory.resolve("src-" + path.hashCode()));
        var classes = Files.createDirectories(directory.resolve("classes-" + path.hashCode()));
        Path file = Files.createDirectories(sources.resolve(path).getParent()).resolve(Path.of(path).getFileName());
        Files.writeString(file, source);
        var args = new ArrayList<>(List.of("--release", "8", "-proc:none", "-d", classes.toString(), file.toString()));
        Path libraryClasses = null;
        if (library != null) {
            libraryClasses = Files.createDirectories(directory.resolve("lib-classes"));
            Path lib = Files.createDirectories(sources.resolve("lib")).resolve("Missing.java");
            Files.writeString(lib, library);
            assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "--release", "8", "-proc:none", "-d",
                libraryClasses.toString(), lib.toString()));
            args.addAll(0, List.of("-cp", libraryClasses.toString()));
        }
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, args.toArray(String[]::new)));
        String entry = path.replace(".java", ".class");
        var archive = directory.resolve("input-" + path.hashCode() + ".jar");
        try (var zip = new ZipOutputStream(Files.newOutputStream(archive))) {
            zip.putNextEntry(new ZipEntry(entry));
            zip.write(Files.readAllBytes(classes.resolve(entry)));
            zip.closeEntry();
        }
        return archive;
    }

    private static boolean waitFor(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) return true;
            Thread.sleep(50);
        }
        return condition.getAsBoolean();
    }
}
