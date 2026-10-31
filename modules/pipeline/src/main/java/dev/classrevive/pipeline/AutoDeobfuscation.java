package dev.classrevive.pipeline;

import dev.classrevive.bytecode.LinkCheck;
import dev.classrevive.bytecode.ObfuscatorClassifier;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import org.objectweb.asm.*;

/**
 * Phase 2DD: when the static obfuscation diagnosis (Phase 2BZ) names, with HIGH confidence, an obfuscator that one of the
 * existing whole-jar reversal passes undoes, {@code decompile} applies that pass before any engine runs, exactly as
 * {@code transform --passes <pass>} would: in the transform worker JVM, then the JVM verifier links (never initializes)
 * every rewritten class. Nothing here knows any program; it only maps a classifier verdict to a pass and checks the result.
 *
 * <p>Fail closed: a worker failure, a pass that finds nothing to undo, or a verifier error that the reversal introduced
 * leaves the original classes for the engines. Link errors caused by a missing dependency (the program's libraries were
 * not supplied) say nothing about the reversal and do not block it; they are reported as inconclusive.
 */
public final class AutoDeobfuscation {
    /** Classifier obfuscator name (as {@link ObfuscatorClassifier} reports it) to the whole-jar pass that reverses it. */
    static final Map<String,String> PASSES = Map.of(
        "Skidfuscator", dev.classrevive.transforms.skid.SkidfuscatorReverser.ID,
        "Aidsfuscator", dev.classrevive.transforms.skid.AidsfuscatorReverser.ID);
    /** Verifier errors that mean the bytecode itself is invalid, as opposed to a class it refers to being absent. */
    static final Set<String> BYTECODE_ERRORS = Set.of("java.lang.VerifyError", "java.lang.ClassFormatError",
        "java.lang.UnsupportedClassVersionError", "java.lang.ClassCircularityError", "java.lang.IncompatibleClassChangeError",
        "java.lang.AbstractMethodError", "java.lang.IllegalAccessError", "java.lang.InstantiationError", "java.lang.NoSuchFieldError",
        "java.lang.NoSuchMethodError");
    static final String INPUT = "classes-before-reversal.jar";

    public enum Status { APPLIED, REFUSED, FAILED, OFF }

    /**
     * {@code obfuscator}, {@code score}: the HIGH hypothesis; {@code jar}: the reversed classes when {@link Status#APPLIED};
     * {@code counts}: the pass's own totals ({@code strings decrypted}, ...); {@code comparison}: what the reversal kept of
     * the original's signatures (null unless applied).
     */
    public record Outcome(String obfuscator, int score, String pass, Status status, String reason, Path jar, Map<String,Integer> counts,
                          int changes, LinkCheck.Result link, Comparison comparison) {
        public Outcome { counts = Collections.unmodifiableMap(new LinkedHashMap<>(counts)); }
        public boolean applied() { return status == Status.APPLIED; }
    }

    /**
     * Signatures of the original classes and how many the reversed classes still declare. The reversal removes what the
     * obfuscator injected (runtime classes, seed fields, extra parameters), so these are the part of the original the
     * round trip can no longer be measured on.
     */
    public record Comparison(int originalClasses, int reversedClasses, List<String> removedClasses, int originalMethods, int keptMethods,
                             int originalFields, int keptFields) {
        public Comparison { removedClasses = List.copyOf(removedClasses); }
    }

    private AutoDeobfuscation() {}

    /** The HIGH hypothesis with the best score, when a reversal pass exists for it; empty otherwise. */
    static Optional<ObfuscatorClassifier.Hypothesis> candidate(ObfuscatorClassifier.Result result) {
        if (result == null || result.hypotheses().isEmpty()) return Optional.empty();
        var top = result.hypotheses().getFirst();
        return top.confidence().equals("HIGH") && PASSES.containsKey(top.obfuscator()) ? Optional.of(top) : Optional.empty();
    }

    /** The outcome recorded when the reversal was turned off for a recognized obfuscator. */
    static Outcome off(ObfuscatorClassifier.Hypothesis hypothesis) {
        return new Outcome(hypothesis.obfuscator(), hypothesis.score(), PASSES.get(hypothesis.obfuscator()), Status.OFF,
            "turned off (--deobfuscate off)", null, Map.of(), 0, null, null);
    }

    /**
     * Runs the pass over {@code output/classes-before-reversal.jar} (written by the caller) into {@code output/deobfuscation}.
     * Never throws for a failed reversal: the outcome says why the original classes are kept.
     */
    static Outcome run(Path output, ObfuscatorClassifier.Hypothesis hypothesis, List<Path> dependencies, Duration timeout)
            throws IOException, InterruptedException {
        String pass = PASSES.get(hypothesis.obfuscator());
        Path input = output.resolve(INPUT), directory = output.resolve(DecompilationJob.DEOBFUSCATION_DIRECTORY);
        try {
            TransformJob.Result result;
            try { result = new TransformJob().run(input, directory, 10_000, timeout, pass, dependencies); }
            catch (IOException failure) { return failed(hypothesis, pass, "transform failed: " + failure.getMessage()); }
            if (!result.successful()) return failed(hypothesis, pass, "transform worker " + result.worker().status());
            Path jar = directory.resolve("worker/transformed/transformed.jar");
            var counts = counts(directory.resolve("worker/transformed/jar-passes.txt"));
            var original = dev.classrevive.verification.MethodRoundTrip.readJar(directory.resolve("input.jar"));
            var reversed = dev.classrevive.verification.MethodRoundTrip.readJar(jar);
            if (result.changes() == 0 && original.keySet().equals(reversed.keySet()))
                return new Outcome(hypothesis.obfuscator(), hypothesis.score(), pass, Status.REFUSED, "the pass found nothing to undo",
                    null, counts, 0, result.link(), null);
            var link = result.link();
            if (link != null && !link.linked() && BYTECODE_ERRORS.contains(link.errorType())
                    && !preexisting(directory.resolve("input.jar"), directory.resolve("link-original"), link, dependencies, timeout))
                return new Outcome(hypothesis.obfuscator(), hypothesis.score(), pass, Status.REFUSED, "the JVM verifier rejected the reversed class "
                    + link.failedClass() + " (" + link.errorType() + ")", null, counts, result.changes(), link, null);
            return new Outcome(hypothesis.obfuscator(), hypothesis.score(), pass, Status.APPLIED, null, jar, counts, result.changes(), link,
                compare(original, reversed));
        } finally {
            Files.deleteIfExists(input);
        }
    }

    /** Whether the original classes fail the verifier with the same error on the same class: then the reversal did not cause it. */
    private static boolean preexisting(Path original, Path work, LinkCheck.Result reversed, List<Path> dependencies, Duration timeout) throws IOException {
        var check = new LinkCheck().check(original, work, dependencies, timeout);
        return !check.linked() && Objects.equals(check.errorType(), reversed.errorType()) && Objects.equals(check.failedClass(), reversed.failedClass());
    }

    private static Outcome failed(ObfuscatorClassifier.Hypothesis hypothesis, String pass, String reason) {
        return new Outcome(hypothesis.obfuscator(), hypothesis.score(), pass, Status.FAILED, reason, null, Map.of(), 0, null, null);
    }

    /** The {@code name: value} lines of the pass report ({@code jar-passes.txt}). */
    static Map<String,Integer> counts(Path report) throws IOException {
        var counts = new LinkedHashMap<String,Integer>();
        if (!Files.isRegularFile(report, LinkOption.NOFOLLOW_LINKS) || Files.size(report) > 64 * 1024) return counts;
        for (String line : Files.readAllLines(report, StandardCharsets.UTF_8)) {
            int colon = line.lastIndexOf(": ");
            if (!line.startsWith("  ") || colon < 0) continue;
            try { counts.put(line.substring(2, colon), Integer.parseInt(line.substring(colon + 2).trim())); }
            catch (NumberFormatException notACount) { /* a list line, such as the refused methods */ }
        }
        return counts;
    }

    static Comparison compare(Map<String,byte[]> original, Map<String,byte[]> reversed) {
        var before = signatures(original); var after = signatures(reversed);
        var removed = new ArrayList<String>();
        for (String name : original.keySet()) if (!reversed.containsKey(name)) removed.add(name);
        int methods = 0, keptMethods = 0, fields = 0, keptFields = 0;
        for (String key : before) {
            boolean method = key.contains("(");
            if (method) methods++; else fields++;
            if (after.contains(key)) { if (method) keptMethods++; else keptFields++; }
        }
        return new Comparison(original.size(), reversed.size(), removed, methods, keptMethods, fields, keptFields);
    }

    /** {@code owner#name(desc)} for methods, {@code owner#name:desc} for fields; ASM only parses the bytes. */
    private static Set<String> signatures(Map<String,byte[]> classes) {
        var keys = new HashSet<String>();
        for (var entry : classes.entrySet()) {
            try {
                new ClassReader(entry.getValue()).accept(new ClassVisitor(Opcodes.ASM9) {
                    String owner;
                    @Override public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) { owner = name; }
                    @Override public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
                        keys.add(owner + "#" + name + ":" + descriptor); return null; }
                    @Override public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                        keys.add(owner + "#" + name + descriptor); return null; }
                }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            } catch (RuntimeException unparsable) {
                // Counted as a class without members.
            }
        }
        return keys;
    }

    /** The headline: {@code Skidfuscator detected (100/100): reversal applied, 6653 strings decrypted}. */
    public static String headline(Outcome outcome) {
        String detected = outcome.obfuscator() + " detected (" + outcome.score() + "/100): ";
        return detected + switch (outcome.status()) {
            case APPLIED -> "reversal applied" + (outcome.counts().containsKey("strings decrypted")
                ? ", " + outcome.counts().get("strings decrypted") + " strings decrypted" : "") + ", " + outcome.changes() + " method(s) rewritten";
            case REFUSED -> "reversal refused, " + outcome.reason() + "; the engines read the original classes";
            case FAILED -> "reversal failed, " + outcome.reason() + "; the engines read the original classes";
            case OFF -> "automatic reversal " + outcome.reason() + "; 'classrevive transform --passes " + outcome.pass() + "' applies it";
        };
    }

    /** report.txt lines. */
    static String text(Outcome outcome) {
        var out = new StringBuilder("Deobfuscation (Phase 2DD): ").append(headline(outcome)).append('\n');
        if (outcome.link() != null) out.append("  JVM verifier (classes linked, none initialized): ").append(linkText(outcome.link())).append('\n');
        if (!outcome.applied()) {
            if (outcome.status() != Status.OFF) out.append("  Evidence: ").append(DecompilationJob.DEOBFUSCATION_DIRECTORY).append("/transform.json\n");
            return out.toString();
        }
        var c = outcome.comparison();
        out.append("  Engines, selection and round trip read the reversed classes: signatures and round trip below are measured against "
            + "them, not against the original.\n");
        out.append("  The reversal kept ").append(c.keptMethods()).append('/').append(c.originalMethods()).append(" method and ")
            .append(c.keptFields()).append('/').append(c.originalFields()).append(" field signatures of the original; ")
            .append(c.removedClasses().size()).append(" of ").append(c.originalClasses()).append(" classes removed")
            .append(c.removedClasses().isEmpty() ? "" : " (" + String.join(", ", c.removedClasses().stream().limit(10).toList())
                + (c.removedClasses().size() > 10 ? ", ..." : "") + ")").append(".\n");
        out.append("  Original classes: ").append(DecompilationJob.DEOBFUSCATION_DIRECTORY).append("/input.jar; reversed: ")
            .append(DecompilationJob.DEOBFUSCATION_DIRECTORY).append("/worker/transformed/transformed.jar; evidence: ")
            .append(DecompilationJob.DEOBFUSCATION_DIRECTORY).append("/transform.json. Behavior not verified.\n");
        return out.toString();
    }

    private static String linkText(LinkCheck.Result link) {
        if (link.linked()) return "all " + link.classes() + " classes link";
        String at = link.failedClass() == null ? "" : " at " + link.failedClass();
        return BYTECODE_ERRORS.contains(link.errorType()) ? link.errorType() + at
            : "inconclusive, " + link.errorType() + at + " (a class outside the input is missing; supply the libraries with --classpath)";
    }

    /** {@code deobfuscation} in report.json. */
    static Map<String,Object> json(Outcome outcome) {
        var data = new TreeMap<String,Object>();
        data.put("obfuscator", outcome.obfuscator()); data.put("score", outcome.score()); data.put("confidence", "HIGH");
        data.put("pass", outcome.pass()); data.put("status", outcome.status().name()); data.put("summary", headline(outcome));
        if (outcome.reason() != null) data.put("reason", outcome.reason());
        data.put("pass_counts", new LinkedHashMap<String,Object>(outcome.counts()));
        data.put("methods_rewritten", outcome.changes());
        if (outcome.link() != null) {
            var link = new TreeMap<String,Object>(); link.put("linked", outcome.link().linked()); link.put("classes", outcome.link().classes());
            if (outcome.link().errorType() != null) {
                link.put("error_type", outcome.link().errorType()); link.put("failed_class", outcome.link().failedClass());
                link.put("bytecode_error", BYTECODE_ERRORS.contains(outcome.link().errorType()));
            }
            data.put("link_check", link);
        }
        if (outcome.status() != Status.OFF) data.put("evidence", DecompilationJob.DEOBFUSCATION_DIRECTORY + "/transform.json");
        if (outcome.applied()) {
            var c = outcome.comparison();
            data.put("original_classes", DecompilationJob.DEOBFUSCATION_DIRECTORY + "/input.jar");
            data.put("reversed_classes", DecompilationJob.DEOBFUSCATION_DIRECTORY + "/worker/transformed/transformed.jar");
            var comparison = new TreeMap<String,Object>();
            comparison.put("original_classes", c.originalClasses()); comparison.put("reversed_classes", c.reversedClasses());
            comparison.put("removed_classes", c.removedClasses());
            comparison.put("original_methods", c.originalMethods()); comparison.put("methods_kept", c.keptMethods());
            comparison.put("original_fields", c.originalFields()); comparison.put("fields_kept", c.keptFields());
            data.put("original_signatures", comparison);
            data.put("measured_against", "reversed classes");
            data.put("meaning", "The engines, the selection, the signature check and the round trip read the reversed classes; their numbers "
                + "measure the sources against them. original_signatures says how much of the original's shape the reversal kept: what it "
                + "removed (injected runtime classes, fields and parameters) is not measured. Static reversal only; behavior not verified.");
        }
        return data;
    }

    /** True when the engines read reversed classes: the worker result tells the caller to decompile {@code outcome.jar()}. */
    static boolean usable(Outcome outcome) {
        return outcome != null && outcome.applied() && outcome.jar() != null && Files.isRegularFile(outcome.jar(), LinkOption.NOFOLLOW_LINKS);
    }
}
