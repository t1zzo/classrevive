package dev.classrevive.workbench;

import dev.classrevive.pipeline.TransformJob;
import java.io.IOException;
import java.nio.file.*;
import java.time.Duration;

/**
 * Phase G5: the Skidfuscator 2.1.0 reversal (Phases 2CN–2CS) as {@code classrevive transform --passes skidfuscator} runs
 * it: a whole-jar pass in a worker JVM, then the JVM verifier links every rewritten class (never initializes it). The
 * desktop application decompiles the result only when it produced a jar and every class linked.
 */
public final class SkidReversal {
    /** {@code jar}: the reversed classes, or null when the reversal produced none; {@code report}: the pass's own summary. */
    public record Result(Path output, Path jar, boolean linked, int linkedClasses, String linkError, String report) {
        /** A jar exists and the verifier accepted every class: safe to decompile instead of the original. */
        public boolean usable() { return jar != null && linked; }
    }

    /** The pass name of {@code SkidfuscatorReverser} ({@code transform --passes skidfuscator}). */
    static final String PASS = "skidfuscator";

    private SkidReversal() {}

    /** Writes the reversal into the new folder {@code output} (its parent must exist). Interrupting the thread cancels it. */
    public static Result run(Path input, Path output, Duration timeout) throws IOException, InterruptedException {
        var result = new TransformJob().run(input, output, 10_000, RequestLimits.timeout(timeout), PASS);
        Path jar = output.resolve("worker/transformed/transformed.jar");
        if (!result.successful() || !Files.isRegularFile(jar, LinkOption.NOFOLLOW_LINKS)) return new Result(output, null, false, 0, "no output", "");
        var link = result.link();
        Path passes = output.resolve("worker/transformed/jar-passes.txt");
        String report = Files.isRegularFile(passes, LinkOption.NOFOLLOW_LINKS) && Files.size(passes) < 64 * 1024 ? Files.readString(passes) : "";
        return new Result(output, jar, link != null && link.linked(), link == null ? 0 : link.classes(),
            link == null || link.linked() ? null : link.errorType() + (link.failedClass() == null ? "" : " in " + link.failedClass()), report);
    }
}
