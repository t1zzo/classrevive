package dev.classrevive.workbench;

import dev.classrevive.pipeline.*;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.*;
import java.util.*;

/**
 * Phase G1: one decompile job end to end, as {@code classrevive decompile} runs it, so no front end repeats the steps that
 * follow the pipeline job. Native triage stays a separate call: a front end may report between the two.
 */
public final class DecompileService {
    /** {@code output}: absolute result directory; {@code origin}: Phase 2CE note when the Java came from another file, else null. */
    public record Decompiled(Path output, DecompilationJob.VerifiedJob job, String origin) {}

    /** Runs the job into the new {@code request.output()} and records where its Java came from. Interrupting the thread cancels it. */
    public Decompiled decompile(DecompileRequest request, JobListener listener) throws IOException, InterruptedException {
        Path output = request.output().toAbsolutePath().normalize();
        String name = request.input().getFileName() == null ? "" : request.input().getFileName().toString().toLowerCase(Locale.ROOT);
        boolean named = name.endsWith(".class") || name.endsWith(".jar");
        if (request.entry() != null && named)
            throw new IllegalArgumentException("--entry applies only to a bundle of jars, not to a .class or .jar input");
        DecompilationJob.VerifiedJob job;
        if (!named && (request.entry() != null || InputIdentifier.identify(request.input()).verdict() == InputIdentifier.Verdict.JAVA_BUNDLE)) {
            job = bundle(request, listener, output);
        } else {
            job = job(request, listener).runVerified(request.input(), request.output(), request.engines(), request.timeout(), request.dependencies());
        }
        String origin = InputIdentifier.attach(output, output.resolve("report.txt"), output.resolve("report.json"));
        return new Decompiled(output, job, origin);
    }

    /**
     * Phase 2DB: a bundle of jars. The chosen nested jar is decompiled under its own name and the bundle's other jars with
     * classes join the --classpath JARs (most classes first, up to {@link RequestLimits#MAX_DEPENDENCIES} in all); they are
     * written to a temporary folder as data and removed afterwards. The origin is recorded even when the job fails.
     */
    private DecompilationJob.VerifiedJob bundle(DecompileRequest request, JobListener listener, Path output) throws IOException, InterruptedException {
        var resolved = InputIdentifier.resolve(request.input(), request.timeout(), request.entry());
        Path work = Files.createTempDirectory("classrevive-bundle");
        boolean existed = Files.exists(output, LinkOption.NOFOLLOW_LINKS);
        try {
            Path main = Files.createDirectory(work.resolve("main")).resolve(fileName(resolved.entry()));
            Files.write(main, resolved.bytes(), StandardOpenOption.CREATE_NEW);
            var dependencies = new ArrayList<>(request.dependencies());
            var used = new LinkedHashMap<String,byte[]>();
            int index = 0;
            for (var nested : resolved.classpath().entrySet()) {
                if (dependencies.size() >= RequestLimits.MAX_DEPENDENCIES) break;
                Path jar = Files.createDirectories(work.resolve("classpath").resolve(String.valueOf(index++))).resolve(fileName(nested.getKey()));
                Files.write(jar, nested.getValue(), StandardOpenOption.CREATE_NEW);
                dependencies.add(jar);
                used.put(nested.getKey(), nested.getValue());
            }
            resolved = resolved.withClasspath(used);
            return job(request, listener).runVerified(main, request.output(), request.engines(), request.timeout(), dependencies);
        } finally {
            if (!existed && Files.isDirectory(output, LinkOption.NOFOLLOW_LINKS) && !Files.exists(output.resolve("input-origin")))
                InputIdentifier.record(output, resolved);
            try (var walk = Files.walk(work)) {
                for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            } catch (IOException | UncheckedIOException ignored) {
                // A leftover temporary folder holds only copies of the bundle's jars.
            }
        }
    }

    private static DecompilationJob job(DecompileRequest request, JobListener listener) {
        return new DecompilationJob().withEngineCache(request.engineCache()).withStubs(request.stubs())
            .withDeobfuscation(request.deobfuscate()).withListener(listener);
    }

    /** A nested jar's base name as a portable file name ending in .jar. */
    static String fileName(String entry) {
        String base = entry.substring(Math.max(entry.lastIndexOf('/'), entry.lastIndexOf('\\')) + 1).replaceAll("[^A-Za-z0-9._-]", "_");
        if (base.isEmpty() || base.startsWith(".")) base = "nested" + base;
        if (base.length() > 100) base = base.substring(0, 96);
        return base.toLowerCase(Locale.ROOT).endsWith(".jar") ? base : base + ".jar";
    }

    /** Native triage of a finished job, attached to its report; null when its input is not a jar. */
    public NativeAnalysis.Result triageNative(Decompiled decompiled, NativeAnalysis.Options options) throws IOException, InterruptedException {
        Path output = decompiled.output();
        if (!NativeTriage.applicable(output)) return null;
        return NativeTriage.run(output, output.resolve("report.txt"), output.resolve("report.json"), options);
    }
}
