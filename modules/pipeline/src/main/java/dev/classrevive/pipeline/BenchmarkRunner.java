package dev.classrevive.pipeline;

import dev.classrevive.engine.DecompilerEngine;
import dev.classrevive.engine.SafePaths;
import dev.classrevive.intake.InventoryReader;
import dev.classrevive.model.Limits;
import dev.classrevive.verification.MethodRoundTrip;
import java.io.IOException;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;

/** Fixed-order cold-worker baseline. Inputs are snapshotted; no target behavior is executed. */
public final class BenchmarkRunner {
    public record Result(int attempted, int complete) {}
    /** Phase 2CH: opt-in engine output cache shared by every row; null runs every engine. */
    private Path engineCache;
    public BenchmarkRunner withEngineCache(Path cache) { this.engineCache = cache; return this; }
    /** Phase 2CT: inferred stubs for types no sample or dependency supplies; on unless turned off. */
    private boolean stubs = true;
    public BenchmarkRunner withStubs(boolean enabled) { this.stubs = enabled; return this; }
    public Result run(Path corpus, Path output, List<DecompilerEngine> engines, Duration timeout) throws IOException, InterruptedException {
        return run(corpus, output, engines, timeout, false);
    }
    /**
     * @param resume continue an interrupted run in {@code output} (Phase 2CH): allowed only when its {@code run.properties}
     *               fingerprint equals this run's (same samples and dependencies by hash, engines and versions, timeout and
     *               ClassRevive build); completed rows and their failure cases are reused, partial attempts set aside
     */
    public Result run(Path corpus, Path output, List<DecompilerEngine> engines, Duration timeout, boolean resume) throws IOException, InterruptedException {
        // Phase 2DI: links above the corpus and the output are followed once (SafePaths); the samples below are still walked.
        corpus = SafePaths.resolveParents(corpus); output = SafePaths.resolveParents(output);
        engines = List.copyOf(engines);
        if (engines.isEmpty() || engines.size() > 4) throw new IllegalArgumentException("Select one to four engines");
        var ids = new HashSet<String>();
        for (var engine : engines) if (!engine.id().matches("[a-z][a-z0-9-]{0,31}") || engine.id().equals("combined") || !ids.add(engine.id()))
            throw new IllegalArgumentException("Invalid or duplicate benchmark engine id");
        if (timeout.isZero() || timeout.isNegative() || timeout.compareTo(Duration.ofHours(1)) > 0)
            throw new IllegalArgumentException("Invalid timeout");
        if (!Files.isDirectory(corpus, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Corpus must be a regular directory");
        if (output.startsWith(corpus)) throw new IOException("Benchmark output must be outside the corpus");
        List<Path> inputs, sidecars;
        try (var stream = Files.list(corpus)) {
            // Bound directory enumeration as well as selected sample count.
            var entries = stream.limit(1001).toList();
            if (entries.size() > 1000) throw new IOException("Corpus directory exceeds 1000 entries");
            inputs = entries.stream().filter(BenchmarkRunner::supported).sorted(Comparator.comparing(p -> p.getFileName().toString())).toList();
            sidecars = entries.stream().filter(p -> p.getFileName().toString().endsWith(DEPENDENCIES)).toList();
        }
        if (inputs.isEmpty() || inputs.size() > 100) throw new IOException("Corpus must contain 1..100 top-level .class/.jar samples");
        var names = new HashSet<String>();
        for (var input : inputs) names.add(input.getFileName().toString());
        // Phase 2BK: <sample>.deps/ holds that sample's compile-time dependency jars. An orphan is refused, not ignored,
        // so a misnamed folder cannot silently turn a dependency-backed measurement into a dependency-free one.
        for (var sidecar : sidecars) {
            String name = sidecar.getFileName().toString();
            if (!names.contains(name.substring(0, name.length() - DEPENDENCIES.length())))
                throw new IOException("Dependency folder without a matching sample: " + name);
        }
        long total = 0;
        var dependencies = new HashMap<Path,List<Path>>();
        for (var input : inputs) {
            rejectLinks(input);
            if (!Files.isRegularFile(input, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Samples must be regular files, not links");
            long size = Files.size(input);
            if (size > Limits.defaults().maxInputBytes()) throw new IOException("Sample exceeds input limit");
            total += size;
            var jars = dependencyJars(input.resolveSibling(input.getFileName() + DEPENDENCIES));
            for (var jar : jars) total += Files.size(jar);
            dependencies.put(input, jars);
            if (total > 512L * 1024 * 1024) throw new IOException("Corpus exceeds 512 MiB");
        }
        // Stubs change what compiles, so a run with them never resumes one without them (Phase 2CT).
        String fingerprint = RunFingerprint.of(inputs, dependencies, engines, timeout) + (stubs ? "" : "-nostubs");
        var done = new HashMap<String,Map<String,Object>>();
        Path snapshots = output.resolve("corpus");
        FailureCollector failures;
        if (!resume) {
            Files.createDirectory(output);
            Files.createDirectory(snapshots);
            Files.writeString(output.resolve("run.properties"), "fingerprint=" + fingerprint + "\n", StandardOpenOption.CREATE_NEW);
            // Phase 2AT failure lab: each row's selected candidate is captured as cases grouped by stable signature.
            failures = new FailureCollector(output.resolve("failures"));
        } else {
            if (!Files.isDirectory(output, LinkOption.NOFOLLOW_LINKS) || !Files.isRegularFile(output.resolve("run.properties"), LinkOption.NOFOLLOW_LINKS))
                throw new IOException("Nothing to resume: the output has no run.properties");
            if (Files.exists(output.resolve("benchmark.json"))) throw new IOException("Nothing to resume: the run already finished");
            var recorded = new Properties();
            try (var in = Files.newInputStream(output.resolve("run.properties"))) { recorded.load(in); }
            if (!fingerprint.equals(recorded.getProperty("fingerprint")))
                throw new IOException("Cannot resume: the samples, dependencies, engines, timeout or ClassRevive build changed");
            var keep = new HashSet<String>();
            Path progress = output.resolve("progress.jsonl");
            if (Files.isRegularFile(progress, LinkOption.NOFOLLOW_LINKS)) {
                if (Files.size(progress) > 256L * 1024 * 1024) throw new IOException("progress.jsonl exceeds limit");
                for (String line : Files.readAllLines(progress, java.nio.charset.StandardCharsets.UTF_8)) {
                    if (line.isBlank()) continue;
                    Object decoded;
                    try { decoded = Json.decode(line); } catch (RuntimeException truncated) { continue; } // A line cut by the interruption.
                    if (!(decoded instanceof Map<?,?> map) || !(map.get("case") instanceof String id) || !(map.get("variant") instanceof String variant)) continue;
                    var row = new TreeMap<String,Object>();
                    map.forEach((key, value) -> row.put(String.valueOf(key), value));
                    done.put(id + "/" + variant, row);
                    if (row.get("failure_cases") instanceof List<?> cases) cases.forEach(item -> keep.add(String.valueOf(item)));
                }
            }
            failures = FailureCollector.resume(output.resolve("failures"), keep);
            // The rewritten progress file holds only complete rows, in order.
            Files.deleteIfExists(progress);
        }
        var variants = new LinkedHashMap<String,List<DecompilerEngine>>();
        for (var engine : engines) variants.put(engine.id(), List.of(engine));
        if (engines.size() > 1) variants.put("combined", engines);
        var rows = new ArrayList<Map<String,Object>>();
        var caseBodies = new HashMap<String,Integer>();
        int complete = 0, index = 0, resumed = 0;
        long copied = 0;
        for (Path input : inputs) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            String caseId = String.format(Locale.ROOT, "case-%03d", ++index);
            Path snapshot = snapshots.resolve(caseId + (input.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar") ? ".jar" : ".class"));
            String snapshotError = null;
            var dependencySnapshots = new ArrayList<Path>();
            var dependencyEvidence = new ArrayList<Map<String,Object>>();
            if (resume) {
                // A case with no complete row starts over; its partial snapshot is set aside, never reused.
                String prefix = caseId + "/";
                if (done.keySet().stream().noneMatch(key -> key.startsWith(prefix))) {
                    setAside(output, snapshot); setAside(output, snapshots.resolve(caseId + DEPENDENCIES));
                }
            }
            try {
                if (resume && Files.isRegularFile(snapshot, LinkOption.NOFOLLOW_LINKS)) copied += Files.size(snapshot);
                else copied += copy(input, snapshot, copied);
                // Every variant compiles against the same dependency bytes, snapshotted once per case.
                var jars = dependencies.get(input);
                Path folder = jars.isEmpty() ? null : snapshots.resolve(caseId + DEPENDENCIES);
                if (folder != null && !Files.isDirectory(folder, LinkOption.NOFOLLOW_LINKS)) Files.createDirectory(folder);
                for (int i = 0; i < jars.size(); i++) {
                    Path copy = folder.resolve(String.format(Locale.ROOT, "%02d.jar", i));
                    if (resume && Files.isRegularFile(copy, LinkOption.NOFOLLOW_LINKS)) copied += Files.size(copy);
                    else copied += copy(jars.get(i), copy, copied);
                    dependencySnapshots.add(copy);
                    dependencyEvidence.add(Map.of("name", jars.get(i).getFileName().toString(), "sha256", sha256(copy)));
                }
            } catch (IOException error) { snapshotError = error.getMessage(); }
            var inventory = new InventoryReader().inspect(snapshot, Limits.defaults());
            for (var variant : variants.entrySet()) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                Path destination = output.resolve(caseId + "-" + variant.getKey());
                var previous = done.get(caseId + "/" + variant.getKey());
                // A reused row must describe this very snapshot and still have its evidence; the fingerprint already pinned the input bytes.
                if (previous != null && rowEvidence(destination)
                    && Objects.equals(previous.get("input_sha256"), snapshotError == null ? inventory.sha256() : null)) {
                    rows.add(previous); resumed++;
                    if ("STRUCTURALLY_COMPLETE".equals(previous.get("status"))) complete++;
                    if (previous.get("original_method_bodies") instanceof Number bodies) caseBodies.putIfAbsent(caseId, bodies.intValue());
                    Files.writeString(output.resolve("progress.jsonl"), Json.encode(previous) + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                    continue;
                }
                if (resume) setAside(output, destination);
                var row = new TreeMap<String,Object>();
                row.put("case", caseId); row.put("input_name", input.getFileName().toString());
                row.put("input_sha256", snapshotError == null ? inventory.sha256() : null); row.put("variant", variant.getKey());
                row.put("dependencies", dependencyEvidence);
                long start = System.nanoTime();
                try {
                    if (snapshotError != null) throw new IOException("Snapshot failed: " + snapshotError);
                    // Single-engine rows stay engine baselines; only the combined row gets compiler-guided source repair.
                    var job = new DecompilationJob().withEngineCache(engineCache).withStubs(stubs)
                        .runVerified(snapshot, destination, variant.getValue(), timeout, dependencySnapshots, "combined".equals(variant.getKey()));
                    boolean success = job.selection().verification().complete();
                    if (success) complete++;
                    row.put("status", success ? "STRUCTURALLY_COMPLETE" : "PARTIAL");
                    row.put("verification", EvidenceReport.verification(job.selection().verification()));
                    // Phase 2DF: stub use on the row itself, not only in the inner report.json.
                    row.put("stub_backed", job.selection().stubBacked());
                    var stubJson = DecompilationJob.stubJson(job.stubs());
                    if (stubJson != null) row.put("missing_type_stubs", stubJson);
                    row.put("engines", job.engines().stream().map(EvidenceReport::engine).toList());
                    row.put("per_file_round_trip", EvidenceReport.perFile(job.perFile()));
                    row.put("environment_stubs", EvidenceReport.environmentStubs(job.selection().repairs()));
                    row.put("report", destination.getFileName() + "/report.json");
                    try { row.put("failure_cases", failures.capture(job, destination, variant.getKey(), caseId, false).stream().map(FailureCase::id).toList()); }
                    catch (IOException captureError) { row.put("failure_capture_error", String.valueOf(captureError.getMessage())); }
                } catch (IOException error) {
                    row.put("status", "FAILED"); row.put("error", error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage());
                }
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                row.put("total_elapsed_ms", (System.nanoTime() - start) / 1_000_000);
                // Corpus-wide round-trip denominator: every variant normalizes the same snapshot, so count it once per case.
                if (!caseBodies.containsKey(caseId) && Files.isRegularFile(destination.resolve("classes.jar"), LinkOption.NOFOLLOW_LINKS)) {
                    try { caseBodies.put(caseId, MethodRoundTrip.countBodies(MethodRoundTrip.readJar(destination.resolve("classes.jar")))); }
                    catch (IOException unreadable) { /* Left unknown and reported as excluded from the corpus denominator. */ }
                }
                row.put("original_method_bodies", caseBodies.get(caseId));
                rows.add(row);
                // Keep completed rows after interruption or process failure; this is not the final aggregate.
                Files.writeString(output.resolve("progress.jsonl"), Json.encode(row) + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            }
        }
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
        var summaries = new TreeMap<String,Object>();
        for (String variant : variants.keySet())
            summaries.put(variant, variantSummary(rows.stream().filter(row -> variant.equals(row.get("variant"))).toList(), caseBodies));
        var lab = failures.finish();
        var markdown = markdown(summaries) + samples(rows, variants.keySet())
            + (resumed == 0 ? "" : "\nResumed: " + resumed + " of " + rows.size() + " rows were reused from an interrupted run with the same "
                + "samples, dependencies, engines, timeout and ClassRevive build; their times are the first run's.\n")
            + "\n## Failure signatures (failure lab)\n\n" + FailureCollector.markdown(lab, 20)
            + "\nCases are in [failures/](failures/failures.md); each `failure-NNNN` directory holds its classes and evidence.\n";
        var report = new TreeMap<String,Object>();
        report.put("schema_version", 1); report.put("kind", "benchmark"); report.put("tool_version", "0.1.0");
        report.put("environment", EvidenceReport.environment()); report.put("behavior", "NOT_VERIFIED");
        report.put("method", "Fixed-order single trial; variants sequential; combined engines and candidate compilations 2 to 4 concurrent (half the processors); fresh worker JVMs; no warmup; compiler-guided source repair only in the combined variant");
        report.put("timeout_seconds_per_worker", timeout.toSeconds()); report.put("samples", inputs.size());
        report.put("attempted", rows.size()); report.put("structurally_complete", complete);
        report.put("resumed_rows", resumed); report.put("run_fingerprint", fingerprint);
        report.put("engine_cache", engineCache == null ? "off" : "on");
        report.put("variants", summaries); report.put("rows", rows);
        report.put("failures", Map.of("cases", lab.get("cases"), "signatures", lab.get("signatures"), "categories", lab.get("categories"),
            "summary", "failures/failures.json"));
        var unmeasured = new ArrayList<String>();
        for (String name : List.of("cfr", "vineflower", "procyon", "jadx")) if (!ids.contains(name)) unmeasured.add(name);
        unmeasured.addAll(List.of("behavioral_equivalence", "deobfuscation", "textual_similarity", "human_readability"));
        report.put("not_measured", unmeasured);
        Files.writeString(output.resolve("benchmark.md"), markdown, StandardOpenOption.CREATE_NEW);
        EvidenceReport.write(output.resolve("benchmark.json"), report);
        return new Result(rows.size(), complete);
    }
    /**
     * Per-variant metrics, each with its own numerator and denominator. Round trip sums every row's
     * {@code verification.round_trip}; selection uses it only to break ties among structurally equal candidates (Phase 2AU).
     */
    static Map<String,Object> variantSummary(List<Map<String,Object>> subset, Map<String,Integer> caseBodies) {
        int attempts = subset.size();
        long compiled = subset.stream().filter(row -> row.get("verification") instanceof Map<?,?> v && "COMPILED".equals(v.get("status"))).count();
        long covered = subset.stream().filter(row -> "STRUCTURALLY_COMPLETE".equals(row.get("status"))).count();
        long measured = 0, identical = 0, equivalent = 0, different = 0, corpus = 0, withoutCount = 0;
        for (var row : subset) {
            if (row.get("verification") instanceof Map<?,?> v && v.get("round_trip") instanceof Map<?,?> trip && Boolean.TRUE.equals(trip.get("measured"))) {
                measured++;
                identical += count(trip.get("identical")); equivalent += count(trip.get("equivalent_after_normalization"));
                different += count(trip.get("different"));
            }
            Integer bodies = caseBodies.get(String.valueOf(row.get("case")));
            if (bodies == null) withoutCount++; else corpus += bodies;
        }
        long compared = identical + equivalent + different, recovered = identical + equivalent;
        var compilable = new TreeMap<String,Object>();
        compilable.put("status", "MEASURED"); compilable.put("recompiled", compiled); compilable.put("denominator", attempts);
        compilable.put("denominator_definition", "all attempts of this variant, failures included");
        var structural = new TreeMap<String,Object>();
        structural.put("status", "MEASURED"); structural.put("structurally_complete", covered); structural.put("denominator", attempts);
        // Phase 2DF: how many of the complete attempts rest on inferred stubs (still counted in structurally_complete).
        structural.put("stub_backed", subset.stream().filter(row -> "STRUCTURALLY_COMPLETE".equals(row.get("status"))
            && Boolean.TRUE.equals(row.get("stub_backed"))).count());
        structural.put("denominator_definition", "all attempts of this variant, failures included");
        var trip = new TreeMap<String,Object>();
        trip.put("status", "MEASURED"); trip.put("measured_attempts", measured); trip.put("attempts", attempts);
        trip.put("identical", identical); trip.put("equivalent_after_normalization", equivalent); trip.put("different", different);
        trip.put("methods_compared", compared); trip.put("recovered_methods", recovered);
        trip.put("recovered_permille", permille(recovered, compared));
        trip.put("denominator_definition", "methods_compared: original method bodies in attempts whose candidate recompiled; "
            + "attempts that did not recompile contribute no methods");
        trip.put("corpus_method_bodies", corpus); trip.put("attempts_without_method_count", withoutCount);
        trip.put("recovered_of_corpus_permille", permille(recovered, corpus));
        trip.put("corpus_denominator_definition", "corpus_method_bodies: original method bodies of every attempt whose input was "
            + "normalized; attempts that did not recompile count as not recovered");
        trip.put("note", "recovered = identical + equivalent_after_normalization against the decompiled bytes; different includes "
            + "legitimate compiler variation and is not proof of wrong behavior; not an original-source recovery percentage");
        // Phase 2BP: whole-candidate recovery, plus per-file recovery for attempts whose candidate did not compile.
        long perFileRecovered = 0, perFileAttempts = 0;
        for (var row : subset) {
            if (row.get("verification") instanceof Map<?,?> v && "COMPILED".equals(v.get("status"))) {
                if (v.get("round_trip") instanceof Map<?,?> whole && Boolean.TRUE.equals(whole.get("measured")))
                    perFileRecovered += count(whole.get("identical")) + count(whole.get("equivalent_after_normalization"));
            } else if (row.get("per_file_round_trip") instanceof Map<?,?> partial) {
                perFileAttempts++;
                perFileRecovered += count(partial.get("identical")) + count(partial.get("equivalent_after_normalization"));
            }
        }
        var perFile = new TreeMap<String,Object>();
        perFile.put("status", "MEASURED"); perFile.put("recovered_methods", perFileRecovered); perFile.put("corpus_method_bodies", corpus);
        perFile.put("attempts_measured_per_file", perFileAttempts);
        perFile.put("recovered_of_corpus_permille", permille(perFileRecovered, corpus));
        perFile.put("definition", "recovered bodies of attempts that recompiled, plus, for the others, bodies of the files that "
            + "compiled against the original classes (Phase 2BP); not a whole-program result");
        var metrics = new TreeMap<String,Object>();
        metrics.put("compilable", compilable); metrics.put("structural", structural); metrics.put("method_round_trip", trip);
        metrics.put("per_file_round_trip", perFile);
        metrics.put("behavioral_equivalence", Map.of("status", "NOT_MEASURED", "reason", "target execution is disabled"));
        metrics.put("deobfuscation", Map.of("status", "NOT_MEASURED", "reason",
            "no deobfuscation ground truth is compared; round trip measures agreement with the bytes the engines saw"));
        var summary = new TreeMap<String,Object>();
        summary.put("attempted", attempts); summary.put("recompiled", compiled); summary.put("structurally_complete", covered);
        summary.put("stub_backed", structural.get("stub_backed"));
        summary.put("metrics", metrics);
        return summary;
    }
    static String markdown(Map<String,Object> summaries) {
        var out = new StringBuilder("# ClassRevive benchmark\n\nSingle trial per sample and variant. Behavior and human readability are not verified.\n\n"
            + "## Compilation and structure\n\nDenominator: every attempt of the variant, failures included.\n\n"
            + "| Variant | Attempted | Recompiled | Full structural checks |\n|---|---:|---:|---:|\n");
        summaries.forEach((variant, value) -> {
            var summary = (Map<?,?>) value;
            out.append("| ").append(variant).append(" | ").append(summary.get("attempted")).append(" | ").append(summary.get("recompiled"))
                .append(" | ").append(summary.get("structurally_complete"))
                .append(count(summary.get("stub_backed")) > 0 ? " (" + summary.get("stub_backed") + " stub-backed)" : "").append(" |\n");
        });
        out.append("\n## Per-method round trip (tie-break among structurally equal candidates)\n\n")
            .append("Each recompiled method body is compared with the decompiled bytes. Recovered = identical + equivalent after ")
            .append("normalization. \"Different\" includes legitimate compiler variation, not only wrong code. ")
            .append("\"Recovered / compared\" only covers attempts whose candidate recompiled; \"Recovered / all bodies\" counts ")
            .append("the methods of every other attempt as not recovered. Rates are rounded down to 0.1%.\n\n")
            .append("| Variant | Measured attempts | Identical | Equivalent after normalization | Different | Recovered / compared | Recovered / all bodies |\n")
            .append("|---|---:|---:|---:|---:|---:|---:|\n");
        summaries.forEach((variant, value) -> {
            var trip = (Map<?,?>) ((Map<?,?>) ((Map<?,?>) value).get("metrics")).get("method_round_trip");
            out.append("| ").append(variant).append(" | ").append(trip.get("measured_attempts")).append('/').append(trip.get("attempts"))
                .append(" | ").append(trip.get("identical")).append(" | ").append(trip.get("equivalent_after_normalization"))
                .append(" | ").append(trip.get("different")).append(" | ")
                .append(rate(trip.get("recovered_methods"), trip.get("methods_compared"), trip.get("recovered_permille"))).append(" | ")
                .append(rate(trip.get("recovered_methods"), trip.get("corpus_method_bodies"), trip.get("recovered_of_corpus_permille")));
            if (count(trip.get("attempts_without_method_count")) > 0)
                out.append(" (").append(trip.get("attempts_without_method_count")).append(" attempts without a method count excluded)");
            out.append(" |\n");
        });
        out.append("\n## Per-file round trip (partial credit)\n\n")
            .append("When a candidate does not compile as a whole, each of its files is compiled against the original classes, ")
            .append("leaving out the files with errors, and the kept files' bodies are compared. These rates add that partial ")
            .append("credit to the whole-candidate rate above; they describe files in the original program's context, not a ")
            .append("program that compiles.\n\n| Variant | Recovered / all bodies (whole candidate) | With per-file credit |\n|---|---:|---:|\n");
        summaries.forEach((variant, value) -> {
            var metrics = (Map<?,?>) ((Map<?,?>) value).get("metrics");
            var trip = (Map<?,?>) metrics.get("method_round_trip");
            var perFile = (Map<?,?>) metrics.get("per_file_round_trip");
            out.append("| ").append(variant).append(" | ")
                .append(rate(trip.get("recovered_methods"), trip.get("corpus_method_bodies"), trip.get("recovered_of_corpus_permille"))).append(" | ")
                .append(perFile == null ? "n/a" : rate(perFile.get("recovered_methods"), perFile.get("corpus_method_bodies"),
                    perFile.get("recovered_of_corpus_permille"))).append(" |\n");
        });
        return out.append("\n## Not measured\n\n")
            .append("- Behavioral equivalence: target execution is disabled.\n")
            .append("- Deobfuscation quality: no ground truth is compared; on obfuscated inputs round trip measures agreement with the obfuscated bytes.\n")
            .append("- Textual similarity to the original source and human readability.\n")
            .append("\nFailures remain in the denominator. These counts are not original-source recovery percentages.\n")
            .append("See [benchmark.json](benchmark.json) for versions, hashes, timings, per-case evidence and limitations.\n").toString();
    }
    private static long count(Object value) { return value instanceof Number number ? number.longValue() : 0; }
    private static Long permille(long numerator, long denominator) { return denominator == 0 ? null : numerator * 1000 / denominator; }
    private static String rate(Object numerator, Object denominator, Object permille) {
        if (!(permille instanceof Number value)) return "n/a (0 methods)";
        long tenths = value.longValue();
        return numerator + "/" + denominator + " (" + tenths / 10 + "." + tenths % 10 + "%)";
    }
    /** A resumed row is reused only when the evidence it points to is still on disk. */
    private static boolean rowEvidence(Path destination) {
        return Files.isRegularFile(destination.resolve("report.json"), LinkOption.NOFOLLOW_LINKS)
            && Files.isRegularFile(destination.resolve("classes.jar"), LinkOption.NOFOLLOW_LINKS);
    }
    /** Moves a partial result of an interrupted run under {@code interrupted/}, so a resumed run writes afresh. */
    private static void setAside(Path output, Path partial) throws IOException {
        if (!Files.exists(partial, LinkOption.NOFOLLOW_LINKS)) return;
        Path folder = Files.createDirectories(output.resolve("interrupted"));
        for (int n = 1; ; n++) {
            Path target = folder.resolve(partial.getFileName() + "-" + n);
            if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) { Files.move(partial, target); return; }
        }
    }
    /** Sidecar folder suffix: {@code guava-17.0.jar.deps/} holds the jars {@code guava-17.0.jar} compiles against. */
    static final String DEPENDENCIES = ".deps";
    /** The sidecar's jars in name order: regular files only, at most 16 (the verifier's dependency limit). */
    private static List<Path> dependencyJars(Path folder) throws IOException {
        if (!Files.exists(folder, LinkOption.NOFOLLOW_LINKS)) return List.of();
        rejectLinks(folder);
        if (!Files.isDirectory(folder, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Dependency folder must be a regular directory");
        List<Path> jars;
        try (var stream = Files.list(folder)) { jars = stream.limit(17).sorted(Comparator.comparing(p -> p.getFileName().toString())).toList(); }
        if (jars.size() > 16) throw new IOException("At most 16 dependency JARs per sample");
        for (var jar : jars) {
            rejectLinks(jar);
            if (!jar.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar") || !Files.isRegularFile(jar, LinkOption.NOFOLLOW_LINKS))
                throw new IOException("Dependency folders may hold only regular .jar files");
            if (Files.size(jar) > Limits.defaults().maxInputBytes()) throw new IOException("Dependency exceeds input limit");
        }
        return jars;
    }
    /** Copies within the per-file input limit and the 512 MiB corpus budget; returns the bytes copied. */
    private static long copy(Path from, Path to, long copied) throws IOException {
        try (var in = Files.newInputStream(from); var out = Files.newOutputStream(to, StandardOpenOption.CREATE_NEW)) {
            byte[] buffer = new byte[8192]; long bytes = 0; int read;
            while ((read = in.read(buffer)) != -1) {
                bytes += read;
                if (bytes > Limits.defaults().maxInputBytes() || copied + bytes > 512L * 1024 * 1024) throw new IOException("Snapshot byte limit exceeded");
                out.write(buffer, 0, read);
            }
            return bytes;
        }
    }
    private static String sha256(Path file) throws IOException {
        try (var in = Files.newInputStream(file)) {
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192]; int read;
            while ((read = in.read(buffer)) != -1) digest.update(buffer, 0, read);
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    /**
     * Phase 2BK scoreboard: one line per sample, one column per variant, so each engine is compared with the combined
     * selection on the same input. A cell is the round trip over all of the sample's bodies, or javac's error count.
     */
    static String samples(List<Map<String,Object>> rows, Collection<String> variants) {
        var out = new StringBuilder("\n## Per sample\n\nRecovered method bodies (identical + equivalent after normalization) over all ")
            .append("original bodies of the sample when its candidate recompiled; otherwise javac's error count. Dependencies: jars from ")
            .append("the sample's `.deps` folder, used only by javac and never executed.\n\n| Sample | Dependencies |");
        for (String variant : variants) out.append(' ').append(variant).append(" |");
        out.append("\n|---|---:|").append("---:|".repeat(variants.size())).append('\n');
        var byCase = new LinkedHashMap<String,Map<String,Map<String,Object>>>();
        for (var row : rows) byCase.computeIfAbsent(String.valueOf(row.get("case")), key -> new HashMap<>()).put(String.valueOf(row.get("variant")), row);
        byCase.forEach((caseId, cells) -> {
            var first = cells.values().iterator().next();
            out.append("| ").append(String.valueOf(first.get("input_name")).replace("|", "\\|")).append(" | ")
                .append(first.get("dependencies") instanceof List<?> list ? list.size() : 0).append(" |");
            for (String variant : variants) out.append(' ').append(cell(cells.get(variant))).append(" |");
            out.append('\n');
        });
        return out.toString();
    }
    private static String cell(Map<String,Object> row) {
        if (row == null) return "n/a";
        // Phase 2DE: a candidate that compiles only with methods the original classes lack says so in its cell.
        long stubs = count(row.get("environment_stubs"));
        return stubs == 0 ? measured(row) : measured(row) + " (" + stubs + " environment stub" + (stubs == 1 ? "" : "s") + ")";
    }
    private static String measured(Map<String,Object> row) {
        if (!(row.get("verification") instanceof Map<?,?> verification)) return "failed";
        if (!"COMPILED".equals(verification.get("status"))) {
            long errors = count(verification.get("errors"));
            String cell = errors + (errors == 1 ? " error" : " errors");
            if (row.get("per_file_round_trip") instanceof Map<?,?> partial && row.get("original_method_bodies") instanceof Number bodies) {
                long kept = count(partial.get("identical")) + count(partial.get("equivalent_after_normalization"));
                cell += "; per file " + rate(kept, bodies, permille(kept, bodies.longValue()));
            }
            return cell;
        }
        long recovered = 0;
        if (verification.get("round_trip") instanceof Map<?,?> trip && Boolean.TRUE.equals(trip.get("measured")))
            recovered = count(trip.get("identical")) + count(trip.get("equivalent_after_normalization"));
        // Phase 2DF: a cell that compiled against inferred stubs says so.
        String stubs = Boolean.TRUE.equals(row.get("stub_backed")) ? " (stub-backed)" : "";
        if (!(row.get("original_method_bodies") instanceof Number bodies)) return "compiles" + stubs;
        return rate(recovered, bodies, permille(recovered, bodies.longValue())) + stubs;
    }
    private static boolean supported(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".jar") || name.endsWith(".class");
    }
    private static void rejectLinks(Path path) throws IOException {
        for (Path parent = path; parent != null; parent = parent.getParent()) {
            try {
                var attributes = Files.readAttributes(parent, java.nio.file.attribute.BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                // Windows junctions are reparse points (isOther), not symbolic links.
                if (attributes.isSymbolicLink() || attributes.isOther()) throw new IOException("Linked or special paths are not supported");
            } catch (NoSuchFileException ignored) { /* A new output directory does not exist yet. */ }
        }
    }
}
