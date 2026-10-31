package dev.classrevive.workbench;

import dev.classrevive.pipeline.NativeAnalysis;
import java.io.IOException;
import java.nio.file.*;

/** Phase 2CC native triage of a finished job's {@code input.jar}, attached to that job's text and JSON evidence. */
public final class NativeTriage {
    private NativeTriage() {}

    /** Only a jar input bundles native libraries; a standalone class has none. */
    public static boolean applicable(Path output) { return Files.isRegularFile(output.resolve("input.jar"), LinkOption.NOFOLLOW_LINKS); }

    /** Extracts the bundled libraries as data into {@code output/native} and appends the summary to {@code text} and {@code json}. */
    public static NativeAnalysis.Result run(Path output, Path text, Path json, NativeAnalysis.Options options) throws IOException, InterruptedException {
        var result = new NativeAnalysis().run(output.resolve("input.jar"), output.resolve("native"), options);
        NativeAnalysis.attach(text, json, result);
        return result;
    }
}
