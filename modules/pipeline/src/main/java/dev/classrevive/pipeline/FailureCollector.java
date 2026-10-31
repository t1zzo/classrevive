package dev.classrevive.pipeline;

import dev.classrevive.engine.EngineResult;
import dev.classrevive.selection.SelectionResult;
import dev.classrevive.verification.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.regex.Pattern;
import java.util.zip.*;

/**
 * Failure lab capture and grouping (Phase 2AT). Reads what a verified job already measured (per-attempt verification,
 * per-method round trip, javac diagnostics, placeholder markers, worker outcomes) and writes one directory per failure
 * with its classes, plus {@code failures.json} and {@code failures.md} that count cases per stable signature. It runs no
 * engine, compiler or target code itself.
 *
 * <p>Case directories hold target-derived evidence (the failing classes, the candidate source file, javac messages).
 * The two summary files hold only signatures, counts and case ids. Absolute paths are redacted from every excerpt.
 */
public final class FailureCollector {
    static final int MAX_EXCERPT = 2000, MAX_CASES = 10_000;
    private static final Pattern EXCEPTION = Pattern.compile(
        "\\b(?:java|javax|jdk|sun|org\\.benf|org\\.jetbrains|com\\.strobel|jadx|dev\\.classrevive)(?:\\.[A-Za-z_$][\\w$]*)+(?:Exception|Error)\\b");
    private static final Pattern WINDOWS_PATH = Pattern.compile("(?i)\\b[a-z]:[\\\\/][^\\s\"'<>|]*");
    private static final Pattern UNIX_PATH = Pattern.compile("(?<![\\w.$/])/(?:home|Users|tmp|var|private|opt|mnt|root)/[^\\s\"'<>|]*");

    /** A failure found in a job; {@code group} names the classes written to the case (the focus class's source file). */
    record Found(FailureCase data, SortedSet<String> group, Path candidateSource, byte[] recompiled) {}

    private final Path directory;
    private final List<FailureCase> cases = new ArrayList<>();

    /** @param directory new directory for the cases and the two summary files */
    public FailureCollector(Path directory) throws IOException {
        this.directory = Files.createDirectory(directory.toAbsolutePath().normalize());
    }

    private FailureCollector(Path directory, boolean existing) { this.directory = directory; }

    /**
     * Phase 2CH: the collector of an interrupted benchmark, with the cases of its complete rows ({@code keep}) loaded back
     * from their {@code case.json}; every other case directory (from the row that was cut) is moved aside.
     */
    static FailureCollector resume(Path directory, Set<String> keep) throws IOException {
        directory = directory.toAbsolutePath().normalize();
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) return new FailureCollector(directory);
        var collector = new FailureCollector(directory, true);
        // Summaries of a run cut between the failure lab and benchmark.json are rewritten at the end.
        for (String summary : List.of("failures.json", "failures.md")) {
            Path written = directory.resolve(summary);
            if (Files.exists(written, LinkOption.NOFOLLOW_LINKS)) Files.move(written, directory.resolve("interrupted-" + summary), StandardCopyOption.REPLACE_EXISTING);
        }
        List<Path> children;
        try (var stream = Files.list(directory)) { children = stream.sorted().toList(); }
        for (var child : children) {
            String name = child.getFileName().toString();
            if (!name.matches("failure-\\d{4}") || !Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)) continue;
            if (keep.contains(name) && Files.isRegularFile(child.resolve("case.json"), LinkOption.NOFOLLOW_LINKS)) {
                collector.cases.add(FailureCase.fromJson(Json.decode(Files.readString(child.resolve("case.json"), java.nio.charset.StandardCharsets.UTF_8))));
            } else {
                for (int n = 1; ; n++) {
                    Path target = directory.resolve("interrupted-" + name + "-" + n);
                    if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) { Files.move(child, target); break; }
                }
            }
        }
        for (int i = 0; i < collector.cases.size(); i++)
            if (!collector.cases.get(i).id().equals(String.format(Locale.ROOT, "failure-%04d", i + 1)))
                throw new IOException("Cannot resume: failure cases of complete rows are not numbered in sequence");
        return collector;
    }

    public Path directory() { return directory; }
    public List<FailureCase> cases() { return List.copyOf(cases); }

    /**
     * Captures the failures of one job and writes their case directories.
     *
     * @param allCandidates also capture the engine candidates that were not selected (a lab run); the benchmark captures
     *                      only the selected candidate of each row, since its single-engine rows already cover each engine
     */
    public List<FailureCase> capture(DecompilationJob.VerifiedJob job, Path jobDirectory, String variant, String sample,
                                     boolean allCandidates) throws IOException {
        jobDirectory = jobDirectory.toAbsolutePath().normalize();
        var originals = MethodRoundTrip.readJar(jobDirectory.resolve("classes.jar"));
        var written = new ArrayList<FailureCase>();
        for (var found : find(job, jobDirectory, variant, sample, allCandidates)) {
            if (cases.size() >= MAX_CASES) break;
            String id = String.format(Locale.ROOT, "failure-%04d", cases.size() + 1);
            Path caseDirectory = Files.createDirectory(directory.resolve(id));
            var data = found.data().withId(id);
            if (!found.group().isEmpty()) {
                var group = new TreeMap<String,byte[]>();
                found.group().forEach(name -> group.put(name, originals.get(name)));
                writeJar(caseDirectory.resolve("classes.jar"), group);
                if (originals.size() > group.size())
                    data = data.withContext(caseDirectory.relativize(jobDirectory.resolve("classes.jar")).toString().replace('\\', '/'));
            }
            if (found.candidateSource() != null && Files.isRegularFile(found.candidateSource(), LinkOption.NOFOLLOW_LINKS)
                && Files.size(found.candidateSource()) <= 1024 * 1024)
                Files.copy(found.candidateSource(), caseDirectory.resolve("candidate.java"));
            if (found.recompiled() != null) Files.write(caseDirectory.resolve("recompiled.class"), found.recompiled(), StandardOpenOption.CREATE_NEW);
            EvidenceReport.write(caseDirectory.resolve("case.json"), data.toJson());
            cases.add(data); written.add(data);
        }
        return List.copyOf(written);
    }

    /** Writes {@code failures.json} and {@code failures.md} and returns the summary (signatures and counts only). */
    public Map<String,Object> finish() throws IOException {
        var summary = summary(cases);
        EvidenceReport.write(directory.resolve("failures.json"), summary);
        Files.writeString(directory.resolve("failures.md"), "# ClassRevive failure lab\n\n" + markdown(summary, 50)
            + "\nEach `failure-NNNN/case.json` holds the target-specific evidence; reduce one with `classrevive lab-minimize`.\n",
            StandardOpenOption.CREATE_NEW);
        return summary;
    }

    /** Groups cases by signature, most frequent first; ties by signature. */
    static Map<String,Object> summary(List<FailureCase> cases) {
        var bySignature = new TreeMap<String,List<FailureCase>>();
        for (var item : cases) bySignature.computeIfAbsent(item.signature(), ignored -> new ArrayList<>()).add(item);
        var groups = new ArrayList<Map<String,Object>>();
        bySignature.entrySet().stream()
            .sorted(Comparator.<Map.Entry<String,List<FailureCase>>>comparingInt(entry -> -entry.getValue().size()).thenComparing(Map.Entry::getKey))
            .forEach(entry -> {
                var first = entry.getValue().getFirst();
                var variants = new TreeMap<String,Long>();
                entry.getValue().forEach(item -> variants.merge(item.variant(), 1L, Long::sum));
                var group = new TreeMap<String,Object>();
                group.put("signature", entry.getKey()); group.put("category", first.category()); group.put("engine", first.engine());
                group.put("detail", first.detail()); group.put("cases", (long) entry.getValue().size());
                group.put("selected", entry.getValue().stream().filter(FailureCase::selected).count());
                group.put("recoverable_by_other_candidate", entry.getValue().stream().filter(item -> !item.recoverableBy().isEmpty()).count());
                group.put("variants", variants);
                group.put("case_ids", entry.getValue().stream().map(FailureCase::id).toList());
                groups.add(group);
            });
        var categories = new TreeMap<String,Long>();
        cases.forEach(item -> categories.merge(item.category(), 1L, Long::sum));
        var summary = new TreeMap<String,Object>();
        summary.put("schema_version", 1); summary.put("kind", "failure-lab"); summary.put("tool_version", "0.1.0-dev");
        summary.put("cases", (long) cases.size()); summary.put("signatures", (long) groups.size());
        summary.put("categories", categories); summary.put("groups", groups);
        summary.put("behavior", "NOT_VERIFIED");
        summary.put("note", "Signatures name no target class, member, constant or path; case directories hold the target-derived "
            + "evidence. METHOD_DIFFERENT includes legitimate compiler variation; PLACEHOLDER markers are text heuristics.");
        return summary;
    }

    static String markdown(Map<String,Object> summary, int limit) {
        var out = new StringBuilder();
        out.append("Cases: ").append(summary.get("cases")).append("; signatures: ").append(summary.get("signatures"))
            .append(". \"Selected\" counts cases in the candidate a job chose; \"recoverable\" counts METHOD_DIFFERENT cases where ")
            .append("another engine candidate of the same job got that method identical or equivalent.\n\n")
            .append("| Signature | Cases | Selected | Recoverable | Variants |\n|---|---:|---:|---:|---|\n");
        int shown = 0;
        var groups = (List<?>) summary.get("groups");
        for (var value : groups) {
            if (shown++ == limit) { out.append("| ... ").append(groups.size() - limit).append(" more in failures.json | | | | |\n"); break; }
            var group = (Map<?,?>) value;
            var variants = new StringJoiner(", ");
            ((Map<?,?>) group.get("variants")).forEach((variant, count) -> variants.add(variant + " " + count));
            out.append("| `").append(group.get("signature")).append("` | ").append(group.get("cases")).append(" | ")
                .append(group.get("selected")).append(" | ").append(group.get("recoverable_by_other_candidate")).append(" | ")
                .append(variants).append(" |\n");
        }
        return out.toString();
    }

    /** Finds the failures of one job without writing anything; used by capture and by the minimizer's reproduction check. */
    static List<Found> find(DecompilationJob.VerifiedJob job, Path jobDirectory, String variant, String sample, boolean allCandidates) throws IOException {
        jobDirectory = jobDirectory.toAbsolutePath().normalize();
        var originals = MethodRoundTrip.readJar(jobDirectory.resolve("classes.jar"));
        var versions = new TreeMap<String,String>();
        job.engines().forEach(engine -> versions.put(engine.engine(), engine.version()));
        String input = inputHash(jobDirectory);
        var selection = job.selection();
        var tried = selection.tried();
        var result = new ArrayList<Found>();
        for (int index = 0; index < tried.size(); index++) {
            var attempt = tried.get(index);
            boolean selected = attempt.id().equals(selection.candidate()), engineCandidate = versions.containsKey(attempt.id());
            // An attempt without source files (an engine that wrote nothing) is a worker failure, captured below.
            if (attempt.origins().isEmpty() || !selected && !(allCandidates && engineCandidate)) continue;
            var context = new Context(jobDirectory, originals, versions, variant, sample, input, selected, attempt,
                jobDirectory.resolve("selection/checks/attempt-" + index + "/classes"), sources(jobDirectory, attempt.id(), versions, selected), tried);
            result.addAll(fromAttempt(context));
        }
        if (allCandidates || job.engines().size() == 1)
            for (var engine : job.engines()) if (engine.status() != EngineResult.Status.OUTPUT_PRODUCED) result.add(workerFailure(engine, jobDirectory, variant, sample, input));
        return List.copyOf(result);
    }

    private record Context(Path job, Map<String,byte[]> originals, Map<String,String> versions, String variant, String sample,
                           String input, boolean selected, SelectionResult.Attempt attempt, Path compiled, Path sources,
                           List<SelectionResult.Attempt> tried) {
        String producer(String file) {
            String origin = file == null ? null : attempt.origins().get(file);
            return origin != null ? origin : attempt.id();
        }
        String version(String producer) {
            String engine = producer.endsWith("+repair") ? producer.substring(0, producer.length() - "+repair".length()) : producer;
            return versions.getOrDefault(engine, "");
        }
    }

    private static List<Found> fromAttempt(Context context) throws IOException {
        var result = new ArrayList<Found>();
        var verification = context.attempt().verification();
        switch (verification.status()) {
            case WORKER_FAILED -> result.add(found(context, FailureCase.WORKER_FAILED, "javac", "verification-" + slug(verification.detail()),
                null, null, null, "compiler worker did not complete", "", null, null));
            case COMPILE_FAILED -> {
                for (var file : verification.fileErrors().keySet()) {
                    var diagnostics = verification.diagnostics().stream().filter(item -> item.file().equals(file)).toList();
                    String code = diagnostics.isEmpty() ? "unlocated" : diagnostics.getFirst().code();
                    var excerpt = new StringBuilder();
                    for (var item : diagnostics.subList(0, Math.min(5, diagnostics.size())))
                        excerpt.append(item.line()).append(':').append(item.column()).append(' ').append(item.code()).append(": ").append(item.message()).append('\n');
                    String focus = focusClass(file, context.originals());
                    result.add(found(context, FailureCase.COMPILE_FAILED, context.producer(file), code, focus, null, file,
                        verification.fileErrors().get(file) + " javac error(s) in this file", excerpt.toString(), null, null));
                }
                if (verification.fileErrors().isEmpty())
                    result.add(found(context, FailureCase.COMPILE_FAILED, context.attempt().id(), "unlocated", null, null, null,
                        verification.errors() + " javac error(s) without a source file", "", null, null));
            }
            case COMPILED -> {
                for (var item : verification.roundTrip()) {
                    String file = topLevel(item.className()) + ".java";
                    for (var method : item.methods()) {
                        if (method.outcome() != MethodRoundTrip.Outcome.DIFFERENT) continue;
                        String detail = reasonCode(method.reason()) + (method.difference().isEmpty() ? "" : ":" + method.difference());
                        byte[] recompiled = readCompiled(context.compiled(), item.className());
                        String excerpt = recompiled == null ? "" : MethodRoundTrip.differenceContext(context.originals().get(item.className()),
                            recompiled, method.name(), method.descriptor(), 4);
                        result.add(found(context, FailureCase.METHOD_DIFFERENT, context.producer(file), detail, item.className(),
                            method.name() + method.descriptor(), file, method.reason(), excerpt, alternatives(context, item.className(), method), recompiled));
                    }
                }
            }
            case UNSUPPORTED_RELEASE -> { /* a JDK limit, not an engine failure */ }
        }
        if (context.sources() != null && Files.isDirectory(context.sources(), LinkOption.NOFOLLOW_LINKS)) {
            for (var path : javaFiles(context.sources())) {
                String file = context.sources().relativize(path).toString().replace('\\', '/');
                String text = Files.readString(path, StandardCharsets.UTF_8);
                for (var marker : CompilerVerifier.PLACEHOLDER_SIGNALS) {
                    int at = text.indexOf(marker);
                    if (at < 0) continue;
                    int start = Math.max(0, text.lastIndexOf('\n', Math.max(0, at - 200)) + 1), end = text.indexOf('\n', at);
                    String excerpt = text.substring(start, end < 0 ? text.length() : Math.min(end, at + 400));
                    result.add(found(context, FailureCase.PLACEHOLDER, context.producer(file), slug(marker), focusClass(file, context.originals()),
                        null, file, "engine stub or failure marker in the source (text heuristic)", excerpt, null, null));
                }
            }
        }
        return result;
    }

    private static Found found(Context context, String category, String engine, String detail, String className, String method,
                               String file, String reason, String excerpt, Map<String,String> alternatives, byte[] recompiled) {
        var group = new TreeSet<String>();
        if (className != null) {
            String top = topLevel(className);
            for (var name : context.originals().keySet()) if (topLevel(name).equals(top)) group.add(name);
        }
        String hash = className == null || !context.originals().containsKey(className) ? null : sha256(context.originals().get(className));
        boolean repaired = engine.endsWith("+repair");
        var data = new FailureCase(null, category, engine, detail, context.version(engine), context.variant(), context.sample(),
            context.attempt().id(), context.selected(), repaired, className, hash, method, file, reason,
            redact(excerpt, context.job()), alternatives == null ? Map.of() : alternatives, context.input(), null);
        Path source = file == null || context.sources() == null ? null : context.sources().resolve(file).normalize();
        if (source != null && !source.startsWith(context.sources())) source = null;
        return new Found(data, group, source, recompiled);
    }

    private static Found workerFailure(EngineResult engine, Path job, String variant, String sample, String input) throws IOException {
        Path log = job.resolve(engine.engine()).resolve("engine.log");
        String text = Files.isRegularFile(log, LinkOption.NOFOLLOW_LINKS) && Files.size(log) <= 2L * 1024 * 1024
            ? new String(Files.readAllBytes(log), StandardCharsets.UTF_8) : "";
        var matcher = EXCEPTION.matcher(text);
        String detail = engine.diagnosticCode().name() + (matcher.find() ? ":" + matcher.group() : "");
        var lines = text.lines().limit(20).toList();
        var data = new FailureCase(null, FailureCase.WORKER_FAILED, engine.engine(), detail, engine.version(), variant, sample,
            engine.engine(), true, false, null, null, null, null, engine.status() + ": " + engine.message(),
            redact(String.join("\n", lines), job), Map.of(), input, null);
        return new Found(data, new TreeSet<>(), null, null);
    }

    /** Level of the same method in every other engine candidate of the job; candidates that did not compile are NOT_RECOMPILED. */
    private static Map<String,String> alternatives(Context context, String className, MethodRoundTrip.MethodResult method) {
        var result = new TreeMap<String,String>();
        for (var other : context.tried()) {
            if (other == context.attempt() || !context.versions().containsKey(other.id())) continue;
            String level = "NOT_RECOMPILED";
            for (var item : other.verification().roundTrip())
                if (item.className().equals(className))
                    for (var candidate : item.methods())
                        if (candidate.name().equals(method.name()) && candidate.descriptor().equals(method.descriptor())) level = candidate.outcome().name();
            result.put(other.id(), level);
        }
        return result;
    }

    /** Engine candidates keep their sources under {@code <engine>/sources}; the selected copy is {@code selection/sources}. */
    private static Path sources(Path job, String id, Map<String,String> versions, boolean selected) {
        if (versions.containsKey(id)) return job.resolve(id).resolve("sources");
        return selected ? job.resolve("selection/sources") : job.resolve("selection").resolve(id);
    }

    private static List<Path> javaFiles(Path root) throws IOException {
        var result = new ArrayList<Path>();
        try (var paths = Files.walk(root)) {
            var iterator = paths.iterator(); int visited = 0;
            while (iterator.hasNext()) {
                Path path = iterator.next();
                if (++visited > 20_001 || Files.isSymbolicLink(path)) throw new IOException("Unsafe source tree");
                if (Files.isRegularFile(path) && path.toString().endsWith(".java") && Files.size(path) <= 16L * 1024 * 1024) result.add(path);
            }
        }
        result.sort(Comparator.comparing(Path::toString));
        return result;
    }

    private static byte[] readCompiled(Path classes, String name) throws IOException {
        Path path = classes.resolve(name + ".class").normalize();
        if (!path.startsWith(classes) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > 16L * 1024 * 1024) return null;
        return Files.readAllBytes(path);
    }

    /** The class whose source file this is: its top-level class when present, else the first nested class of that file. */
    static String focusClass(String file, Map<String,byte[]> originals) {
        if (!file.endsWith(".java")) return null;
        String top = file.substring(0, file.length() - ".java".length());
        if (originals.containsKey(top)) return top;
        for (var name : originals.keySet()) if (topLevel(name).equals(top)) return name;
        return null;
    }

    static String topLevel(String className) {
        int slash = className.lastIndexOf('/'), dollar = className.indexOf('$', slash + 1);
        return dollar > slash + 1 ? className.substring(0, dollar) : className;
    }

    static String reasonCode(String reason) {
        return switch (reason) {
            case "body differs after normalization" -> "body-differs";
            case "method missing from recompiled class" -> "method-missing";
            case "recompiled method has no body" -> "no-body";
            case "class missing from recompiled output" -> "class-missing";
            case "recompiled class unreadable" -> "class-unreadable";
            default -> slug(reason);
        };
    }

    static String slug(String text) {
        String value = text.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        return value.isEmpty() ? "unknown" : value.length() <= 60 ? value : value.substring(0, 60);
    }

    /** Removes the job directory, temp and home folders and other absolute paths; bounds the excerpt. */
    static String redact(String text, Path job) {
        if (text == null) return "";
        String value = text;
        for (Path root : List.of(job, Path.of(System.getProperty("java.io.tmpdir")), Path.of(System.getProperty("user.home")))) {
            String absolute = root.toAbsolutePath().normalize().toString();
            if (absolute.length() < 3) continue;
            value = Pattern.compile(Pattern.quote(absolute), Pattern.CASE_INSENSITIVE).matcher(value).replaceAll("<path>");
            value = Pattern.compile(Pattern.quote(absolute.replace('\\', '/')), Pattern.CASE_INSENSITIVE).matcher(value).replaceAll("<path>");
        }
        value = WINDOWS_PATH.matcher(value).replaceAll("<path>");
        value = UNIX_PATH.matcher(value).replaceAll("<path>");
        return value.length() <= MAX_EXCERPT ? value : value.substring(0, MAX_EXCERPT) + "...";
    }

    private static String inputHash(Path job) throws IOException {
        for (String name : List.of("input.jar", "input.class")) {
            Path path = job.resolve(name);
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) continue;
            try (var in = Files.newInputStream(path)) {
                var digest = MessageDigest.getInstance("SHA-256");
                byte[] buffer = new byte[8192]; int read;
                while ((read = in.read(buffer)) != -1) digest.update(buffer, 0, read);
                return HexFormat.of().formatHex(digest.digest());
            } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
        }
        return null;
    }

    static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    /** Deterministic class JAR: sorted entries, fixed timestamps, nothing but {@code name.class} entries. */
    static void writeJar(Path path, Map<String,byte[]> classes) throws IOException {
        try (var zip = new ZipOutputStream(Files.newOutputStream(path, StandardOpenOption.CREATE_NEW))) {
            for (var entry : new TreeMap<>(classes).entrySet()) {
                var item = new ZipEntry(entry.getKey() + ".class"); item.setTime(0);
                zip.putNextEntry(item); zip.write(entry.getValue()); zip.closeEntry();
            }
        }
    }
}
