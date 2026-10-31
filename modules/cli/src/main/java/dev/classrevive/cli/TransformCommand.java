package dev.classrevive.cli;

import dev.classrevive.pipeline.TransformJob;
import java.io.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.HashSet;

final class TransformCommand {
    private TransformCommand() {}
    static int run(String[] args, PrintStream out, PrintStream err) {
        try {
            if (args.length < 4 || args.length % 2 != 0) throw new IllegalArgumentException("Expected input and --output <new-directory>.");
            Path input = Path.of(args[1]), output = null;
            int budget = 1000, seconds = 60;
            String passes = "integer-constants";
            var seen = new HashSet<String>();
            for (int i = 2; i < args.length; i += 2) {
                if (!seen.add(args[i])) throw new IllegalArgumentException("Duplicate option: " + args[i]);
                switch (args[i]) {
                    case "--output" -> output = Path.of(args[i + 1]);
                    case "--max-changes" -> budget = Integer.parseInt(args[i + 1]);
                    case "--timeout-seconds" -> seconds = Integer.parseInt(args[i + 1]);
                    case "--passes" -> passes = args[i + 1];
                    default -> throw new IllegalArgumentException("Unknown option: " + args[i]);
                }
            }
            if (output == null || budget < 1 || budget > 10000 || seconds < 1 || seconds > 3600)
                throw new IllegalArgumentException("Require --output, max-changes 1..10000 and timeout-seconds 1..3600.");
            out.println("Applying selected static bytecode passes in a separate worker JVM. Original retained; target code is not executed.");
            var result = new TransformJob().run(input, output, budget, Duration.ofSeconds(seconds), passes);
            out.printf("Worker: %s; changes: %d; change limit reached: %s%n", result.worker().status(), result.changes(), result.limitReached());
            if (result.link() != null)
                out.println("JVM verifier (classes linked, none initialized): " + (result.link().linked() ? "all " + result.link().classes() + " classes link"
                    : "FAILED at " + Main.safe(String.valueOf(result.link().failedClass())) + " (" + Main.safe(String.valueOf(result.link().errorType())) + ")"));
            out.println("Evidence: " + Main.safe(output.toAbsolutePath().resolve("transform.json").toString()));
            out.println("Behavior not verified. This is a static simplification pass, not general deobfuscation.");
            return result.successful() ? 0 : 1;
        } catch (IllegalArgumentException error) {
            err.println("Invalid command arguments: " + Main.safe(String.valueOf(error.getMessage()))); return 2;
        } catch (FileAlreadyExistsException error) {
            err.println("Output already exists. Choose a new directory; existing results were not overwritten."); return 1;
        } catch (IOException error) {
            err.println("Transformation failed: " + Main.safe(String.valueOf(error.getMessage()))); return 1;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt(); err.println("Transformation interrupted."); return 130;
        }
    }
}
