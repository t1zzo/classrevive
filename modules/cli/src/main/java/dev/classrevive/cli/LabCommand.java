package dev.classrevive.cli;

import dev.classrevive.workbench.EngineRegistry;
import dev.classrevive.pipeline.*;
import java.io.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;

/** Failure lab commands (Phase 2AT): capture and group, minimize, promote to a regression case, check regressions. */
final class LabCommand {
    private LabCommand() {}

    static int run(String[] args, PrintStream out, PrintStream err) {
        try {
            return switch (args[0]) {
                case "lab" -> lab(args, out);
                case "lab-minimize" -> minimize(args, out);
                case "lab-promote" -> promote(args, out);
                default -> check(args, out);
            };
        } catch (IllegalArgumentException error) {
            err.println("Invalid arguments: " + Main.safe(String.valueOf(error.getMessage()))); return 2;
        } catch (FileAlreadyExistsException error) {
            err.println("Output already exists. Choose a new directory; existing results were not overwritten."); return 1;
        } catch (IOException error) {
            err.println("Failure lab failed: " + Main.safe(String.valueOf(error.getMessage()))); return 1;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt(); err.println("Failure lab interrupted; finished steps remain in the output."); return 130;
        }
    }

    private static int lab(String[] args, PrintStream out) throws IOException, InterruptedException {
        var options = options(args, Set.of("--output", "--engines", "--timeout-seconds", "--classpath", "--minimize", "--max-steps", "--max-seconds",
            "--keep-recoverable"));
        Path input = Path.of(args[1]), output = Path.of(required(options, "--output")).toAbsolutePath().normalize();
        var engines = EngineRegistry.parse(options.getOrDefault("--engines", "all"));
        Duration timeout = seconds(options, "--timeout-seconds", 120, 3600);
        String minimize = options.getOrDefault("--minimize", "none");
        if (!minimize.matches("none|first|all|failure-[0-9]{4}")) throw new IllegalArgumentException("--minimize must be none, first, all or failure-NNNN");
        var dependencies = new ArrayList<Path>();
        if (options.containsKey("--classpath")) {
            if (Files.isDirectory(input)) throw new IllegalArgumentException("--classpath is supported only for a single input");
            for (String path : options.get("--classpath").split(java.util.regex.Pattern.quote(File.pathSeparator), -1)) {
                if (path.isBlank()) throw new IllegalArgumentException("Empty classpath entry");
                dependencies.add(Path.of(path));
            }
        }
        var budget = budget(options);
        boolean keepRecoverable = keepRecoverable(options);
        Files.createDirectory(output);
        Path failures;
        if (Files.isDirectory(input, LinkOption.NOFOLLOW_LINKS)) {
            out.println("Corpus: running the benchmark with failure capture; target behavior is not executed.");
            var result = new BenchmarkRunner().run(input, output.resolve("benchmark"), engines, timeout);
            out.printf("Benchmark: %d/%d attempts structurally complete.%n", result.complete(), result.attempted());
            failures = output.resolve("benchmark").resolve("failures");
        } else {
            out.println("Decompiling with every selected engine; each engine candidate and the selected sources are captured.");
            var job = new DecompilationJob().runVerified(input, output.resolve("job"), engines, timeout, dependencies, true);
            var collector = new FailureCollector(output.resolve("failures"));
            collector.capture(job, output.resolve("job"), engines.size() > 1 ? "combined" : engines.getFirst().id(), null, true);
            collector.finish();
            failures = collector.directory();
        }
        var summary = (Map<?,?>) Json.decode(Files.readString(failures.resolve("failures.json")));
        var groups = (List<?>) summary.get("groups");
        out.printf("Failure cases: %s; signatures: %s (%s)%n", summary.get("cases"), summary.get("signatures"),
            Main.safe(failures.resolve("failures.md").toString()));
        int shown = 0;
        for (var value : groups) {
            if (shown++ == 10) { out.println("  ... " + (groups.size() - 10) + " more in failures.json"); break; }
            var group = (Map<?,?>) value;
            out.printf("  %4s  %s (selected %s, recoverable by another engine %s)%n", group.get("cases"), Main.safe(String.valueOf(group.get("signature"))),
                group.get("selected"), group.get("recoverable_by_other_candidate"));
        }
        var chosen = new ArrayList<String>();
        for (var value : groups) {
            var ids = (List<?>) ((Map<?,?>) value).get("case_ids");
            if (minimize.equals("first")) chosen.add(String.valueOf(ids.getFirst()));
            else if (minimize.equals("all")) ids.forEach(id -> chosen.add(String.valueOf(id)));
            else if (ids.contains(minimize)) chosen.add(minimize);
        }
        if (minimize.startsWith("failure-") && chosen.isEmpty()) throw new IllegalArgumentException("No captured case " + minimize);
        long deadline = System.nanoTime() + budget.maxTime().toNanos();
        for (String id : chosen) {
            long left = deadline - System.nanoTime();
            if (left < 1_000_000_000L) { out.println("Time budget used up; remaining cases were not minimized."); break; }
            var result = new FailureMinimizer().minimize(failures.resolve(id), output.resolve("minimized").resolve(id), EngineRegistry.all(),
                new FailureMinimizer.Budget(budget.maxSteps(), Duration.ofNanos(left), budget.timeout()), keepRecoverable);
            report(out, id, result);
        }
        out.println("Behavior not verified. Promote a reduced authored or license-compatible case with lab-promote.");
        return 0;
    }

    private static int minimize(String[] args, PrintStream out) throws IOException, InterruptedException {
        var options = options(args, Set.of("--output", "--max-steps", "--max-seconds", "--timeout-seconds", "--keep-recoverable"));
        Path output = Path.of(required(options, "--output"));
        out.println("Minimizing: each reduction must pass the JVM verifier (nothing is executed) and reproduce twice.");
        var result = new FailureMinimizer().minimize(Path.of(args[1]), output, EngineRegistry.all(), budget(options), keepRecoverable(options));
        report(out, Path.of(args[1]).toAbsolutePath().normalize().getFileName().toString(), result);
        return result.directory() == null ? 1 : 0;
    }

    private static int promote(String[] args, PrintStream out) throws IOException, InterruptedException {
        var options = options(args, Set.of("--output", "--engines", "--timeout-seconds", "--provenance"));
        Path output = Path.of(required(options, "--output"));
        String provenance = options.getOrDefault("--provenance", "unspecified: commit only cases derived from authored or license-compatible inputs");
        var expected = new RegressionSuite().promote(Path.of(args[1]), output, EngineRegistry.parse(options.getOrDefault("--engines", "all")),
            seconds(options, "--timeout-seconds", 120, 3600), provenance);
        out.println("Regression case: " + Main.safe(output.toAbsolutePath().normalize().toString()));
        var variants = (Map<?,?>) expected.get("variants");
        variants.forEach((variant, data) -> out.printf("  %s: %s%n", variant, Main.safe(String.valueOf(((Map<?,?>) data).get("minimum_levels")))));
        if (!((List<?>) expected.get("unstable_at_promotion")).isEmpty())
            out.println("Unstable between the two measurements (the lower level was kept): " + Main.safe(String.valueOf(expected.get("unstable_at_promotion"))));
        return 0;
    }

    private static int check(String[] args, PrintStream out) throws IOException, InterruptedException {
        var options = options(args, Set.of("--output", "--timeout-seconds"));
        Path output = Path.of(required(options, "--output"));
        var report = new RegressionSuite().check(Path.of(args[1]), output, EngineRegistry.all(), seconds(options, "--timeout-seconds", 120, 3600));
        out.printf("%s: %d case(s), %d method check(s), %d regressed, %d improved, %d error(s).%n", report.passed() ? "PASSED" : "FAILED",
            report.cases(), report.checks().size(), report.regressions().size(), report.improvements().size(), report.errors().size());
        for (var check : report.regressions())
            out.printf("  REGRESSED %s %s %s: expected at least %s, got %s%n", Main.safe(check.caseName()), check.variant(),
                Main.safe(check.method()), check.expected(), check.actual());
        for (var check : report.improvements())
            out.printf("  IMPROVED %s %s %s: %s -> %s (raise expected.json)%n", Main.safe(check.caseName()), check.variant(),
                Main.safe(check.method()), check.expected(), check.actual());
        report.errors().forEach(error -> out.println("  ERROR " + Main.safe(error)));
        out.println("Report: " + Main.safe(output.toAbsolutePath().normalize().resolve("regressions.md").toString()));
        return report.passed() ? 0 : 1;
    }

    private static void report(PrintStream out, String id, FailureMinimizer.Result result) {
        out.printf("%s %s: %s after %d evaluation(s), %d accepted", id, Main.safe(result.signature()), result.status(),
            result.evaluations(), result.accepted());
        if (result.original() != null && result.minimized() != null)
            out.printf("; %d -> %d bytes, %d -> %d members, %d -> %d instructions", result.original().bytes(), result.minimized().bytes(),
                result.original().members(), result.minimized().members(), result.original().instructions(), result.minimized().instructions());
        out.println();
        if (result.directory() != null) out.println("  Reduced case: " + Main.safe(result.directory().toString()));
    }

    private static Map<String,String> options(String[] args, Set<String> allowed) {
        if (args.length < 4 || args.length % 2 != 0) throw new IllegalArgumentException("Expected input and option/value pairs");
        var options = new HashMap<String,String>();
        for (int i = 2; i < args.length; i += 2) {
            if (!allowed.contains(args[i])) throw new IllegalArgumentException("Unknown option: " + args[i]);
            if (options.putIfAbsent(args[i], args[i + 1]) != null) throw new IllegalArgumentException("Repeated option: " + args[i]);
        }
        return options;
    }

    private static String required(Map<String,String> options, String name) {
        if (!options.containsKey(name)) throw new IllegalArgumentException("Missing " + name + " <new-directory>");
        return options.get(name);
    }

    private static Duration seconds(Map<String,String> options, String name, int fallback, int max) {
        int value = Integer.parseInt(options.getOrDefault(name, Integer.toString(fallback)));
        if (value < 1 || value > max) throw new IllegalArgumentException(name + " must be 1.." + max);
        return Duration.ofSeconds(value);
    }

    private static boolean keepRecoverable(Map<String,String> options) {
        String value = options.getOrDefault("--keep-recoverable", "true");
        if (!value.equals("true") && !value.equals("false")) throw new IllegalArgumentException("--keep-recoverable must be true or false");
        return value.equals("true");
    }

    private static FailureMinimizer.Budget budget(Map<String,String> options) {
        int steps = Integer.parseInt(options.getOrDefault("--max-steps", "80"));
        if (steps < 1 || steps > 10_000) throw new IllegalArgumentException("--max-steps must be 1..10000");
        return new FailureMinimizer.Budget(steps, seconds(options, "--max-seconds", 1800, 86_400), seconds(options, "--timeout-seconds", 60, 3600));
    }
}
