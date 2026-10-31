package dev.classrevive.pipeline;

import dev.classrevive.bytecode.ClassReducer;
import dev.classrevive.bytecode.LinkCheck;
import dev.classrevive.engine.DecompilerEngine;
import dev.classrevive.verification.MethodRoundTrip;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;

/**
 * Delta debugging for one captured failure (Phase 2AT). The case's classes are reduced with ASM in four phases: other
 * classes of the source file, then other members of the focus class (ddmin), then stub bodies of the methods that
 * must stay, then instruction edits inside the focus method (greedy, one edit at a time), and finally debug attributes.
 *
 * <p>A reduction is kept only when (1) every class passes the real JVM verifier in an isolated loader ({@link LinkCheck};
 * nothing is initialized or executed) and (2) a fresh single-engine job reproduces the failure with the same signature,
 * on the same class and method, in two consecutive runs, so an intermittent engine cannot shrink the case by accident.
 * When the case records engines that recovered the focus method in the original job ({@link FailureCase#recoverableBy()}),
 * each of them must still recover it (identical or equivalent) in the reduced case, so the case keeps proving that a
 * better choice existed. Budgets cap the evaluations and the wall time; on exhaustion the last accepted state is kept.
 */
public final class FailureMinimizer {
    /** @param maxSteps evaluations (link check plus runs) allowed; @param maxTime wall time; @param timeout per worker */
    public record Budget(int maxSteps, Duration maxTime, Duration timeout) {
        public Budget {
            if (maxSteps < 1 || maxSteps > 10_000) throw new IllegalArgumentException("Steps must be 1..10000");
            if (maxTime.isNegative() || maxTime.isZero() || maxTime.compareTo(Duration.ofHours(24)) > 0) throw new IllegalArgumentException("Time budget must be 1 s..24 h");
            if (timeout.isNegative() || timeout.isZero() || timeout.compareTo(Duration.ofHours(1)) > 0) throw new IllegalArgumentException("Timeout must be 1 s..1 h");
        }
    }
    public record Size(int classes, int bytes, int members, int instructions) {}
    public record Result(String status, String signature, int evaluations, int accepted, Size original, Size minimized, Path directory) {}

    public static final String MINIMIZED = "MINIMIZED", BUDGET_EXHAUSTED = "BUDGET_EXHAUSTED", NOT_REPRODUCIBLE = "NOT_REPRODUCIBLE",
        ORIGINAL_DOES_NOT_LINK = "ORIGINAL_DOES_NOT_LINK", UNSUPPORTED = "UNSUPPORTED";

    private static final class Exhausted extends Exception { Exhausted() { super(null, null, false, false); } }

    private Path output, steps; private FailureCase failure; private DecompilerEngine engine; private List<Path> context;
    private Budget budget; private long deadline; private int evaluations, accepted;
    private Map<String,byte[]> state;
    private final Set<String> evaluated = new HashSet<>();
    private final List<Map<String,Object>> log = new ArrayList<>();
    private String lastRejection = "";
    private FailureCase lastMatch;
    private List<DecompilerEngine> contrast = List.of();
    private Map<String,String> lastContrast = Map.of();

    public Result minimize(Path caseDirectory, Path output, List<DecompilerEngine> available, Budget budget) throws IOException, InterruptedException {
        return minimize(caseDirectory, output, available, budget, true);
    }

    /** @param keepRecoverable require the engines that recovered the focus method to keep recovering it */
    public Result minimize(Path caseDirectory, Path output, List<DecompilerEngine> available, Budget budget, boolean keepRecoverable)
        throws IOException, InterruptedException {
        caseDirectory = caseDirectory.toAbsolutePath().normalize();
        this.output = Files.createDirectory(output.toAbsolutePath().normalize());
        this.budget = budget; this.deadline = System.nanoTime() + budget.maxTime().toNanos();
        long start = System.nanoTime();
        failure = FailureCase.fromJson(Json.decode(Files.readString(caseDirectory.resolve("case.json"), StandardCharsets.UTF_8)));
        Path jar = caseDirectory.resolve("classes.jar");
        String base = failure.engine().endsWith("+repair") ? failure.engine().substring(0, failure.engine().length() - "+repair".length()) : failure.engine();
        engine = available.stream().filter(item -> item.id().equals(base)).findFirst().orElse(null);
        if (!Files.isRegularFile(jar, LinkOption.NOFOLLOW_LINKS) || engine == null)
            return finish(UNSUPPORTED, engine == null ? "producer " + failure.engine() + " is not an available engine" : "case has no classes.jar", null, start);
        if (keepRecoverable && failure.method() != null)
            contrast = available.stream().filter(item -> failure.recoverableBy().contains(item.id()) && item != engine).toList();
        var original = MethodRoundTrip.readJar(jar);
        state = original;
        if (failure.className() != null && !original.containsKey(failure.className()))
            return finish(UNSUPPORTED, "focus class missing from classes.jar", original, start);
        context = context(caseDirectory, original.keySet());
        steps = Files.createDirectory(this.output.resolve("steps"));
        try {
            if (!evaluate(original, "original", "unreduced case")) {
                boolean linkFailed = String.valueOf(log.getLast().get("outcome")).equals("REJECTED_LINK");
                return finish(linkFailed ? ORIGINAL_DOES_NOT_LINK : NOT_REPRODUCIBLE, lastRejection, original, start);
            }
            reduceClasses();
            reduceMembers();
            stubBodies();
            reduceInstructions();
            accept(stripDebug(state), "debug", "drop debug attributes");
        } catch (Exhausted exhausted) {
            return finish(BUDGET_EXHAUSTED, "budget exhausted after " + evaluations + " evaluation(s)", original, start);
        }
        return finish(MINIMIZED, "", original, start);
    }

    /** Phase 1: other classes of the focus class's source file (nested and sibling classes). */
    private void reduceClasses() throws Exhausted, IOException, InterruptedException {
        var others = new ArrayList<String>();
        for (var name : state.keySet()) if (!name.equals(failure.className())) others.add(name);
        if (others.isEmpty() || failure.className() == null) return;
        var all = new TreeMap<>(state);
        ddmin(others, kept -> {
            var removed = new TreeSet<>(all.keySet()); removed.remove(failure.className()); removed.removeAll(kept);
            var candidate = new TreeMap<String,byte[]>();
            for (var entry : all.entrySet())
                if (!removed.contains(entry.getKey())) candidate.put(entry.getKey(), ClassReducer.dropReferences(entry.getValue(), removed));
            return accept(candidate, "classes", "keep " + kept.size() + " of " + others.size() + " other class(es)");
        });
    }

    /** Phase 2: fields and methods of the focus class other than the focus method. */
    private void reduceMembers() throws Exhausted, IOException, InterruptedException {
        if (failure.className() == null) return;
        byte[] focus = state.get(failure.className());
        var members = new ArrayList<String>();
        for (var member : ClassReducer.members(focus)) if (!member.key().equals(failure.method())) members.add(member.key());
        ddmin(members, kept -> {
            var keep = new HashSet<>(kept); if (failure.method() != null) keep.add(failure.method());
            return accept(with(failure.className(), ClassReducer.keepMembers(focus, keep)), "members",
                "keep " + kept.size() + " of " + members.size() + " other member(s)");
        });
    }

    /** Phase 3: replace the bodies of the methods that had to stay with {@code throw null} or a bare super constructor call. */
    private void stubBodies() throws Exhausted, IOException, InterruptedException {
        if (failure.className() == null) return;
        byte[] focus = state.get(failure.className());
        var bodies = new ArrayList<String>();
        for (var member : ClassReducer.members(focus))
            if (member.method() && member.hasBody() && !member.key().equals(failure.method())) bodies.add(member.key());
        ddmin(bodies, kept -> {
            var stubbed = new HashSet<>(bodies); kept.forEach(stubbed::remove);
            return accept(with(failure.className(), ClassReducer.stubMethods(focus, stubbed)), "stubs",
                "stub " + stubbed.size() + " of " + bodies.size() + " other method bod(ies)");
        });
    }

    /** Phase 4: one instruction edit at a time in the focus method, restarting after each accepted edit. */
    private void reduceInstructions() throws Exhausted, IOException, InterruptedException {
        if (failure.className() == null || failure.method() == null) return;
        boolean progressed = true;
        while (progressed) {
            progressed = false;
            byte[] focus = state.get(failure.className());
            var edits = ClassReducer.edits(focus, failure.method());
            for (int index = 0; index < edits.size() && !progressed; index++) {
                byte[] candidate = ClassReducer.applyEdit(focus, failure.method(), index, state);
                if (candidate == null || Arrays.equals(candidate, focus)) continue;
                progressed = accept(with(failure.className(), candidate), "instructions", edits.get(index));
            }
        }
    }

    private Map<String,byte[]> stripDebug(Map<String,byte[]> classes) {
        var result = new TreeMap<String,byte[]>();
        classes.forEach((name, bytes) -> result.put(name, ClassReducer.stripDebug(bytes)));
        return result;
    }

    private Map<String,byte[]> with(String name, byte[] bytes) {
        var result = new TreeMap<>(state); result.put(name, bytes); return result;
    }

    @FunctionalInterface private interface Test { boolean keep(List<String> kept) throws Exhausted, IOException, InterruptedException; }

    /** Zeller's ddmin over the removable items: returns a 1-minimal list of items that must stay for the failure to persist. */
    private static List<String> ddmin(List<String> items, Test test) throws Exhausted, IOException, InterruptedException {
        if (items.isEmpty() || test.keep(List.of())) return List.of();
        var current = new ArrayList<>(items); int n = 2;
        while (current.size() >= 2) {
            var chunks = split(current, n); boolean reduced = false;
            for (var chunk : chunks) if (test.keep(chunk)) { current = new ArrayList<>(chunk); n = 2; reduced = true; break; }
            if (!reduced && n > 2)
                for (var chunk : chunks) {
                    var complement = new ArrayList<>(current); complement.removeAll(chunk);
                    if (test.keep(complement)) { current = complement; n = Math.max(n - 1, 2); reduced = true; break; }
                }
            if (!reduced) { if (n >= current.size()) break; n = Math.min(current.size(), n * 2); }
        }
        return current;
    }

    private static List<List<String>> split(List<String> items, int parts) {
        var result = new ArrayList<List<String>>();
        int size = items.size();
        for (int i = 0; i < parts; i++) {
            var chunk = items.subList(i * size / parts, (i + 1) * size / parts);
            if (!chunk.isEmpty()) result.add(List.copyOf(chunk));
        }
        return result;
    }

    /** Evaluates a candidate state and makes it current when the failure persists. */
    private boolean accept(Map<String,byte[]> candidate, String phase, String description) throws Exhausted, IOException, InterruptedException {
        if (!evaluate(candidate, phase, description)) return false;
        state = candidate; accepted++;
        return true;
    }

    private boolean evaluate(Map<String,byte[]> candidate, String phase, String description) throws Exhausted, IOException, InterruptedException {
        String hash = digest(candidate);
        if (!evaluated.add(hash)) return false;
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
        if (evaluations >= budget.maxSteps() || System.nanoTime() > deadline) throw new Exhausted();
        long start = System.nanoTime();
        Path step = Files.createDirectory(steps.resolve(String.format(Locale.ROOT, "%04d", ++evaluations)));
        Path jar = step.resolve("classes.jar");
        FailureCollector.writeJar(jar, candidate);
        var entry = new TreeMap<String,Object>();
        entry.put("step", (long) evaluations); entry.put("phase", phase); entry.put("description", description);
        entry.put("classes_sha256", hash);
        Map<String,String> pendingContrast = Map.of();
        try {
            var link = new LinkCheck().check(jar, step.resolve("link"), context, budget.timeout());
            if (!link.linked()) return reject(entry, "REJECTED_LINK", link.errorType() + ": " + link.message(), start);
            for (String run : List.of("a", "b")) {
                if (System.nanoTime() > deadline) throw new Exhausted();
                Path directory = step.resolve("run-" + run);
                DecompilationJob.VerifiedJob job;
                try { job = new DecompilationJob().runVerified(jar, directory, List.of(engine), budget.timeout(), context, failure.sourceRepair()); }
                catch (IOException error) { return reject(entry, "REJECTED_ERROR", String.valueOf(error.getMessage()), start); }
                var seen = new TreeSet<String>();
                FailureCase same = null;
                for (var found : FailureCollector.find(job, directory, "lab", null, true)) {
                    var data = found.data();
                    if (!Objects.equals(data.className(), failure.className())) continue;
                    if (data.signature().equals(failure.signature()) && Objects.equals(data.method(), failure.method())) same = data;
                    seen.add(data.signature());
                }
                if (same == null) return reject(entry, run.equals("a") ? "REJECTED_SIGNATURE" : "REJECTED_NOT_REPEATED",
                    "observed on the focus class: " + (seen.isEmpty() ? "no failure" : String.join(", ", seen)), start);
                if (run.equals("b")) lastMatch = same;
                if (run.equals("a") && !contrast.isEmpty()) {
                    var levels = new TreeMap<String,String>();
                    for (var other : contrast) {
                        Path contrastRun = step.resolve("contrast-" + other.id());
                        String level;
                        try {
                            var check = new DecompilationJob().runVerified(jar, contrastRun, List.of(other), budget.timeout(), context, false);
                            level = RegressionSuite.levels(check, candidate).getOrDefault(failure.className() + "." + failure.method(), "NOT_RECOMPILED");
                        } catch (IOException error) { level = "NOT_RECOMPILED"; }
                        levels.put(other.id(), level);
                        if (FailureCase.level(level) < FailureCase.level("EQUIVALENT_AFTER_NORMALIZATION"))
                            return reject(entry, "REJECTED_CONTRAST", other.id() + " no longer recovers the focus method (" + level + ")", start);
                    }
                    pendingContrast = levels;
                }
            }
            lastContrast = pendingContrast;
            entry.put("outcome", "ACCEPTED");
        } catch (Exhausted stop) {
            entry.put("outcome", "STOPPED_BY_BUDGET");
            throw stop;
        } finally {
            entry.putIfAbsent("outcome", "ABORTED");
            entry.put("elapsed_ms", (System.nanoTime() - start) / 1_000_000);
            log.add(entry);
        }
        return true;
    }

    private boolean reject(Map<String,Object> entry, String outcome, String detail, long start) {
        entry.put("outcome", outcome); entry.put("detail", FailureCollector.redact(detail, output));
        lastRejection = outcome + ": " + entry.get("detail");
        return false;
    }

    /** Context classes of the case's job, minus the case's own classes, as a compile-only (and link-only) dependency. */
    private List<Path> context(Path caseDirectory, Set<String> own) throws IOException {
        if (failure.context() == null) return List.of();
        Path source = caseDirectory.resolve(failure.context()).normalize();
        if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) return List.of();
        var classes = MethodRoundTrip.readJar(source);
        classes.keySet().removeAll(own);
        if (classes.isEmpty()) return List.of();
        Path jar = output.resolve("context.jar");
        FailureCollector.writeJar(jar, classes);
        return List.of(jar);
    }

    private Result finish(String status, String reason, Map<String,byte[]> original, long start) throws IOException {
        Size before = original == null ? null : size(original), after = original == null ? null : size(state);
        Path minimized = null;
        if (original != null && (status.equals(MINIMIZED) || status.equals(BUDGET_EXHAUSTED))) {
            minimized = Files.createDirectory(output.resolve("minimized"));
            FailureCollector.writeJar(minimized.resolve("classes.jar"), state);
            var data = (lastMatch == null ? failure : failure.withEvidence(lastMatch.excerpt(), lastMatch.engineVersion(), lastContrast))
                .withClassHash(failure.className() == null ? null : FailureCollector.sha256(state.get(failure.className())))
                .withContext(context.isEmpty() ? null : "../context.jar");
            EvidenceReport.write(minimized.resolve("case.json"), data.toJson());
        }
        var report = new TreeMap<String,Object>();
        report.put("schema_version", 1); report.put("kind", "failure-minimization"); report.put("case", failure.id());
        report.put("signature", failure.signature()); report.put("status", status); report.put("reason", reason);
        report.put("engine", engine == null ? failure.engine() : engine.id() + " " + engine.version());
        report.put("source_repair", failure.sourceRepair());
        report.put("contrast_engines", contrast.stream().map(DecompilerEngine::id).toList());
        report.put("contrast_levels", lastContrast);
        report.put("budget", Map.of("max_steps", (long) budget.maxSteps(), "max_seconds", budget.maxTime().toSeconds(),
            "timeout_seconds", budget.timeout().toSeconds()));
        report.put("evaluations", (long) evaluations); report.put("accepted", (long) accepted);
        report.put("elapsed_ms", (System.nanoTime() - start) / 1_000_000);
        report.put("acceptance", "every class links under the JVM verifier (isolated loader, nothing initialized or executed), "
            + "two fresh single-engine runs reproduce the same signature on the same class and method, and every contrast engine "
            + "still recovers the focus method (identical or equivalent)");
        report.put("original", before == null ? null : size(before)); report.put("minimized", after == null ? null : size(after));
        report.put("steps", log);
        report.put("behavior", "NOT_VERIFIED");
        EvidenceReport.write(output.resolve("minimization.json"), report);
        return new Result(status, failure.signature(), evaluations, accepted, before, after, minimized);
    }

    private static Map<String,Object> size(Size size) {
        return Map.of("classes", (long) size.classes(), "bytes", (long) size.bytes(), "members", (long) size.members(),
            "instructions", (long) size.instructions());
    }

    private static Size size(Map<String,byte[]> classes) {
        int bytes = 0, members = 0, instructions = 0;
        for (var value : classes.values()) {
            bytes += value.length; members += ClassReducer.members(value).size(); instructions += ClassReducer.instructions(value);
        }
        return new Size(classes.size(), bytes, members, instructions);
    }

    private static String digest(Map<String,byte[]> classes) {
        var text = new StringBuilder();
        new TreeMap<>(classes).forEach((name, bytes) -> text.append(name).append('=').append(FailureCollector.sha256(bytes)).append(';'));
        return FailureCollector.sha256(text.toString().getBytes(StandardCharsets.UTF_8));
    }
}
