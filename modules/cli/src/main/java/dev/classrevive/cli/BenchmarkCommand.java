package dev.classrevive.cli;

import dev.classrevive.pipeline.BenchmarkRunner;
import java.io.*;

final class BenchmarkCommand {
    private BenchmarkCommand() {}
    static int run(String[] args, PrintStream out, PrintStream err) {
        try {
            var options = JobOptions.parse(args, "all");
            out.println("Benchmarking selected engines and combined selection; target behavior is not executed.");
            var result = new BenchmarkRunner().withEngineCache(options.engineCache()).withStubs(options.stubs())
                .run(options.input(), options.output(), options.engines(), options.timeout(), options.resume());
            out.printf("Structurally complete: %d/%d attempts. Behavior not verified.%n", result.complete(), result.attempted());
            out.println("Report: " + Main.safe(options.output().toAbsolutePath().normalize().resolve("benchmark.json").toString()));
            return result.complete() == result.attempted() ? 0 : 1;
        } catch (IllegalArgumentException error) {
            err.println("Invalid arguments: " + Main.safe(String.valueOf(error.getMessage()))); return 2;
        } catch (IOException error) {
            err.println("Benchmark failed: " + Main.safe(String.valueOf(error.getMessage()))); return 1;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt(); err.println("Benchmark interrupted; completed rows remain in progress.jsonl."); return 130;
        }
    }
}
