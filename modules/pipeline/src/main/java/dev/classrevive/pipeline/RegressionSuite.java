package dev.classrevive.pipeline;

import dev.classrevive.bytecode.ClassReducer;
import dev.classrevive.engine.DecompilerEngine;
import dev.classrevive.verification.MethodRoundTrip;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;

/**
 * Permanent regression cases from the failure lab (Phase 2AT). A case directory holds the reduced {@code classes.jar}
 * and {@code expected.json}: per variant (one engine alone, or all engines combined with source repair, as in the
 * benchmark) the minimum round-trip level of every method body. {@link #check} decompiles each case again and fails a
 * method that drops to a lower level ({@link FailureCase#LEVELS}); a higher level is reported as an improvement so the
 * expectation can be raised. Target code is never executed; levels are recompilation evidence, not behavior.
 */
public final class RegressionSuite {
    public record Check(String caseName, String variant, String method, String expected, String actual) {
        public String verdict() {
            int before = FailureCase.level(expected), after = FailureCase.LEVELS.contains(actual) ? FailureCase.level(actual) : -1;
            return after < before ? "REGRESSED" : after > before ? "IMPROVED" : "SAME";
        }
    }
    public record Report(int cases, List<Check> checks, List<String> errors) {
        public Report { checks = List.copyOf(checks); errors = List.copyOf(errors); }
        public List<Check> regressions() { return checks.stream().filter(check -> check.verdict().equals("REGRESSED")).toList(); }
        public List<Check> improvements() { return checks.stream().filter(check -> check.verdict().equals("IMPROVED")).toList(); }
        public boolean passed() { return errors.isEmpty() && regressions().isEmpty(); }
    }

    /**
     * Turns a minimized case into a regression case: measures every variant twice, records the lower level of each
     * method as its minimum, then writes the classes (and compile-only context) with {@code expected.json}. Nothing is
     * written to {@code destination} when a measurement is unreliable (a worker that could not run).
     *
     * @param provenance where the reduced classes come from and under which license; only authored or license-compatible
     *                   inputs may be committed
     */
    public Map<String,Object> promote(Path minimized, Path destination, List<DecompilerEngine> engines, Duration timeout,
                                      String provenance) throws IOException, InterruptedException {
        minimized = minimized.toAbsolutePath().normalize();
        var failure = FailureCase.fromJson(Json.decode(Files.readString(minimized.resolve("case.json"), StandardCharsets.UTF_8)));
        if (engines.isEmpty()) throw new IllegalArgumentException("Select at least one engine");
        destination = destination.toAbsolutePath().normalize();
        if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) throw new FileAlreadyExistsException(destination.toString());
        Path classes = minimized.resolve("classes.jar");
        Path context = failure.context() == null ? null : minimized.resolve(failure.context()).normalize();
        if (context != null && !Files.isRegularFile(context, LinkOption.NOFOLLOW_LINKS)) context = null;
        // Measurement jobs stay next to the minimized case (lab output), never inside the regression directory. The
        // name is short on purpose: deep job paths can exceed the Windows limit for a process working directory.
        Path work = null;
        for (int index = 1; work == null; index++) {
            try { work = Files.createDirectory(minimized.getParent().resolve(index == 1 ? "promotion" : "promotion-" + index)); }
            catch (FileAlreadyExistsException taken) { if (index >= 1000) throw taken; }
        }
        var variants = new TreeMap<String,Object>(); var unstable = new TreeSet<String>(); var versions = new TreeMap<String,String>();
        engines.forEach(engine -> versions.put(engine.id(), engine.version()));
        for (var variant : variants(engines).entrySet()) {
            boolean repair = variant.getValue().size() > 1;
            Map<String,String> lowest = null;
            for (String run : List.of("a", "b")) {
                var levels = measure(classes, context, work.resolve(variant.getKey() + "-" + run), variant.getValue(), repair, timeout);
                if (lowest == null) { lowest = levels; continue; }
                for (var entry : levels.entrySet()) {
                    String other = lowest.getOrDefault(entry.getKey(), "NOT_RECOMPILED");
                    if (!other.equals(entry.getValue())) unstable.add(variant.getKey() + " " + entry.getKey());
                    if (FailureCase.level(entry.getValue()) < FailureCase.level(other)) lowest.put(entry.getKey(), entry.getValue());
                }
            }
            var data = new TreeMap<String,Object>();
            data.put("engines", variant.getValue().stream().map(DecompilerEngine::id).toList());
            data.put("source_repair", repair); data.put("minimum_levels", lowest);
            variants.put(variant.getKey(), data);
        }
        Files.createDirectory(destination);
        Files.copy(classes, destination.resolve("classes.jar"));
        if (context != null) Files.copy(context, destination.resolve("context.jar"));
        Files.copy(minimized.resolve("case.json"), destination.resolve("case.json"));
        var expected = new TreeMap<String,Object>();
        expected.put("schema_version", 1); expected.put("kind", "regression-case"); expected.put("name", destination.getFileName().toString());
        expected.put("provenance", provenance); expected.put("signature", failure.signature());
        expected.put("classes_sha256", FailureCollector.sha256(Files.readAllBytes(destination.resolve("classes.jar"))));
        expected.put("context", Files.exists(destination.resolve("context.jar")));
        expected.put("variants", variants); expected.put("unstable_at_promotion", List.copyOf(unstable));
        expected.put("measured_with", Map.of("java_version", System.getProperty("java.version"), "engines", versions));
        expected.put("levels", FailureCase.LEVELS);
        expected.put("note", "minimum_levels are the levels measured at promotion (lower of two runs). lab-check fails when a method "
            + "drops below them and reports IMPROVED when one rises; raise the expectation when a fix lands.");
        EvidenceReport.write(destination.resolve("expected.json"), expected);
        return expected;
    }

    /** Re-runs every case under {@code regressions} into a new {@code output} directory and writes the verdicts there. */
    public Report check(Path regressions, Path output, List<DecompilerEngine> available, Duration timeout) throws IOException, InterruptedException {
        regressions = regressions.toAbsolutePath().normalize();
        output = output.toAbsolutePath().normalize();
        if (output.startsWith(regressions)) throw new IOException("Output must be outside the regression directory");
        Files.createDirectory(output);
        var cases = new ArrayList<Path>();
        try (var list = Files.list(regressions)) {
            for (var path : list.sorted().toList())
                if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) && Files.isRegularFile(path.resolve("expected.json"), LinkOption.NOFOLLOW_LINKS)) cases.add(path);
        }
        if (cases.size() > 1000) throw new IOException("Too many regression cases");
        var checks = new ArrayList<Check>(); var errors = new ArrayList<String>();
        for (var directory : cases) {
            String name = directory.getFileName().toString();
            try {
                var expected = (Map<?,?>) Json.decode(Files.readString(directory.resolve("expected.json"), StandardCharsets.UTF_8));
                if (!"regression-case".equals(expected.get("kind")) || !Long.valueOf(1).equals(expected.get("schema_version")))
                    throw new IOException("not a regression case");
                byte[] jar = Files.readAllBytes(directory.resolve("classes.jar"));
                if (!FailureCollector.sha256(jar).equals(expected.get("classes_sha256"))) throw new IOException("classes.jar does not match classes_sha256");
                for (var variant : new TreeMap<>((Map<?,?>) expected.get("variants")).entrySet()) {
                    var data = (Map<?,?>) variant.getValue();
                    var engines = new ArrayList<DecompilerEngine>();
                    for (Object id : (List<?>) data.get("engines"))
                        engines.add(available.stream().filter(engine -> engine.id().equals(id)).findFirst()
                            .orElseThrow(() -> new IOException("engine " + id + " is not available")));
                    Path context = directory.resolve("context.jar");
                    var actual = measure(directory.resolve("classes.jar"), Files.isRegularFile(context, LinkOption.NOFOLLOW_LINKS) ? context : null,
                        output.resolve(name).resolve(String.valueOf(variant.getKey())), engines, Boolean.TRUE.equals(data.get("source_repair")), timeout);
                    for (var level : new TreeMap<>((Map<?,?>) data.get("minimum_levels")).entrySet()) {
                        FailureCase.level(String.valueOf(level.getValue()));
                        checks.add(new Check(name, String.valueOf(variant.getKey()), String.valueOf(level.getKey()), String.valueOf(level.getValue()),
                            actual.getOrDefault(String.valueOf(level.getKey()), "ABSENT")));
                    }
                }
            } catch (IOException | RuntimeException error) {
                errors.add(name + ": " + FailureCollector.redact(String.valueOf(error.getMessage()), regressions));
            }
        }
        var report = new Report(cases.size(), checks, errors);
        write(report, output);
        return report;
    }

    /** One variant per engine (engine baseline, no repair) plus {@code combined} with every engine, as in the benchmark. */
    static Map<String,List<DecompilerEngine>> variants(List<DecompilerEngine> engines) {
        var result = new LinkedHashMap<String,List<DecompilerEngine>>();
        for (var engine : engines) result.put(engine.id(), List.of(engine));
        if (engines.size() > 1) result.put("combined", List.copyOf(engines));
        return result;
    }

    /** Level of every original method body after one job; bodies the job did not recompile are NOT_RECOMPILED. */
    private static Map<String,String> measure(Path classes, Path context, Path work, List<DecompilerEngine> engines, boolean repair,
                                              Duration timeout) throws IOException, InterruptedException {
        Files.createDirectories(work.getParent());
        var dependencies = context == null ? List.<Path>of() : List.of(context);
        var job = new DecompilationJob().runVerified(classes, work, engines, timeout, dependencies, repair);
        String unreliable = unreliable(job);
        if (unreliable != null) throw new IOException("unreliable measurement in " + work.getFileName() + ": " + unreliable);
        return levels(job, MethodRoundTrip.readJar(classes));
    }

    /**
     * A worker that could not start, failed on the host side, ran out of time or was cancelled says nothing about the
     * engine; its NOT_RECOMPILED must never become an expectation or a verdict. An engine exiting with an error is a result.
     */
    static String unreliable(DecompilationJob.VerifiedJob job) {
        if (job.selection().verification().status() == dev.classrevive.verification.VerificationResult.Status.WORKER_FAILED)
            return "the compiler worker failed (" + job.selection().verification().detail() + ")";
        for (var engine : job.engines())
            switch (engine.diagnosticCode()) {
                case WORKER_SETUP_FAILED, WORKER_START_FAILED, WORKER_RUNTIME_FAILURE, DEADLINE_EXCEEDED, OUTPUT_BUDGET_EXCEEDED, CANCELLED -> {
                    return engine.engine() + " worker " + engine.diagnosticCode();
                }
                default -> { }
            }
        return null;
    }

    static Map<String,String> levels(DecompilationJob.VerifiedJob job, Map<String,byte[]> originals) {
        var result = new TreeMap<String,String>();
        for (var entry : originals.entrySet())
            for (var member : ClassReducer.members(entry.getValue()))
                if (member.method() && member.hasBody()) result.put(entry.getKey() + "." + member.key(), "NOT_RECOMPILED");
        for (var item : job.selection().verification().roundTrip())
            for (var method : item.methods()) result.put(item.className() + "." + method.name() + method.descriptor(), method.outcome().name());
        return result;
    }

    private static void write(Report report, Path output) throws IOException {
        var data = new TreeMap<String,Object>();
        data.put("schema_version", 1); data.put("kind", "regression-check"); data.put("cases", (long) report.cases());
        data.put("passed", report.passed()); data.put("errors", report.errors());
        data.put("checks", report.checks().stream().map(check -> Map.of("case", check.caseName(), "variant", check.variant(),
            "method", check.method(), "expected_minimum", check.expected(), "actual", check.actual(), "verdict", check.verdict())).toList());
        data.put("behavior", "NOT_VERIFIED");
        EvidenceReport.write(output.resolve("regressions.json"), data);
        var text = new StringBuilder("# ClassRevive regression check\n\n")
            .append(report.passed() ? "PASSED" : "FAILED").append(": ").append(report.cases()).append(" case(s), ")
            .append(report.checks().size()).append(" method check(s), ").append(report.regressions().size()).append(" regressed, ")
            .append(report.improvements().size()).append(" improved, ").append(report.errors().size()).append(" error(s).\n\n");
        if (!report.checks().isEmpty()) {
            text.append("| Case | Variant | Method | Expected minimum | Actual | Verdict |\n|---|---|---|---|---|---|\n");
            for (var check : report.checks())
                text.append("| ").append(check.caseName()).append(" | ").append(check.variant()).append(" | `").append(check.method())
                    .append("` | ").append(check.expected()).append(" | ").append(check.actual()).append(" | ").append(check.verdict()).append(" |\n");
        }
        for (var error : report.errors()) text.append("\nError: ").append(error).append('\n');
        Files.writeString(output.resolve("regressions.md"), text, StandardOpenOption.CREATE_NEW);
    }
}
