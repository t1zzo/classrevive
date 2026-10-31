package dev.classrevive.pipeline;

import dev.classrevive.engine.EngineResult;
import dev.classrevive.verification.VerificationResult;
import java.nio.file.*;
import java.io.IOException;
import java.util.*;

public final class EvidenceReport {
    private EvidenceReport() {}
    public static Map<String,Object> environment() {
        return Map.of("java_version", System.getProperty("java.version"), "java_vendor", System.getProperty("java.vendor"),
            "os", System.getProperty("os.name"), "architecture", System.getProperty("os.arch"));
    }
    public static Map<String,Object> verification(VerificationResult value) {
        var data = new TreeMap<String,Object>(verificationCore(value));
        // Additive schema-v1 fields (Phase 2AR): javac errors while parsing, and how many source files had errors.
        data.put("syntax_errors", value.syntaxErrors()); data.put("files_with_errors", value.fileErrors().size());
        // Additive (Phase 2DF): compiled with inferred stubs standing in for missing libraries; structurally_complete is unchanged.
        data.put("stub_backed", value.stubBacked());
        return data;
    }
    private static Map<String,Object> verificationCore(VerificationResult value) {
        return Map.of("status", value.status().name(), "errors", value.errors(), "matched_symbols", value.matchedSymbols(),
            "expected_symbols", value.expectedSymbols(), "placeholder_signals", value.placeholderSignals(),
            "structurally_complete", value.complete(), "detail", value.detail(), "class_coverage",
            value.classCoverage().stream().map(coverage -> Map.of("class_name", coverage.className(),
                "expected_symbols", coverage.expectedSymbols(), "matched_symbols", coverage.matchedSymbols(),
                "coverage_permille", coverage.coveragePermille(), "complete", coverage.complete())).toList(),
            "round_trip", roundTrip(value));
    }
    /** Additive schema-v1 field: per-method body comparison counts; selection uses them only as a structural tie-break. */
    public static Map<String,Object> roundTrip(VerificationResult value) {
        return Map.of("measured", value.roundTripMeasured(), "identical", value.roundTripIdentical(),
            "equivalent_after_normalization", value.roundTripEquivalent(), "different", value.roundTripDifferent(),
            "classes", value.roundTrip().stream().map(item -> Map.of("class_name", item.className(),
                "identical", item.identical(), "equivalent_after_normalization", item.equivalent(),
                "different", item.different())).toList());
    }
    public static Map<String,Object> engine(EngineResult value) {
        return Map.of("id", value.engine(), "version", value.version(), "status", value.status().name(),
            "diagnostic_code", value.diagnosticCode().name(), "exit_code", value.exitCode(),
            "source_files", value.sourceFiles(), "elapsed_ms", value.elapsedMillis(), "message", value.message());
    }
    /** Writes the report to a sibling temporary file and moves it into place, so a crash never leaves a half-written report. */
    public static void write(Path path, Object data) throws IOException {
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) throw new FileAlreadyExistsException(path.toString());
        Path temporary = Files.createTempFile(path.toAbsolutePath().getParent(), path.getFileName().toString(), ".tmp");
        try {
            Files.writeString(temporary, Json.encode(data) + "\n", StandardOpenOption.TRUNCATE_EXISTING);
            try { Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException unsupported) { Files.move(temporary, path); }
        } catch (IOException | RuntimeException error) {
            try { Files.deleteIfExists(temporary); } catch (IOException ignored) { }
            throw error;
        }
    }
    /**
     * Additive schema-v1 field (Phase 2BP): the per-file round trip of a selected candidate that did not compile as a
     * whole, or null. Each kept file was compiled against the original classes, not the other decompiled files.
     */
    public static Map<String,Object> perFile(dev.classrevive.verification.PartialRoundTrip.Result value) {
        if (value == null) return null;
        var data = new TreeMap<String,Object>();
        data.put("files", value.files()); data.put("compiled_files", value.compiledFiles()); data.put("rounds", value.rounds());
        data.put("compiled", value.compiled()); data.put("identical", value.identical());
        data.put("equivalent_after_normalization", value.equivalent()); data.put("different", value.different());
        data.put("context", "each kept file compiled against the original classes; bodies of files left out are not recovered");
        return data;
    }
    /** Additive schema-v1 field: versioned variants that were reported and not decompiled. */
    public static Map<String,Object> multiRelease(List<dev.classrevive.intake.MultiRelease.Variant> variants, boolean declared) {
        return Map.of("manifest_multi_release", declared, "policy", "BASE_VERSION_DECOMPILED_VARIANTS_IGNORED",
            "ignored_variants", variants.stream().map(variant -> Map.of("entry", variant.entry(), "release", variant.release(),
                "class_name", variant.className(), "has_base_class", variant.hasBase())).toList());
    }
    /** Additive schema-v1 field (Phase 2AS): compiler-guided edits in the selected sources; evidence, not behavior proof. */
    public static Map<String,Object> sourceRepairs(List<dev.classrevive.verification.SourceRepair.Edit> edits) {
        // Phase 2DE: environment_stubs counts the classes given methods they do not have in the original (environment-stub).
        return Map.of("evidence", "COMPILER_DIAGNOSTIC_GUIDED_EDITS_BEHAVIOR_NOT_VERIFIED", "count", edits.size(), "environment_stubs", environmentStubs(edits),
            "edits", edits.stream().map(edit -> Map.of("file", edit.file(), "line", edit.line(), "column", edit.column(),
                "diagnostic_code", edit.code(), "rule", edit.rule(), "before", edit.before(), "after", edit.after())).toList());
    }
    /** Phase 2DE: the edits that add methods the original classes do not have, one per class. */
    public static long environmentStubs(List<dev.classrevive.verification.SourceRepair.Edit> edits) {
        return edits.stream().filter(edit -> dev.classrevive.verification.SourceRepair.ENVIRONMENT_STUB.equals(edit.rule())).count();
    }
    /**
     * Additive schema-v1 field (Phase 2DH): compiler idioms undone in the selected sources (enum switches printed on
     * ordinals or switch maps, synthetic accessor calls, {@code $assertionsDisabled} tests), each read from the original
     * bytecode and kept only when the round trip of its file's methods did not get worse; behavior not verified.
     */
    public static Map<String,Object> compilerIdioms(List<dev.classrevive.verification.IdiomResugar.Rewrite> rewrites) {
        var rules = new TreeMap<String,Integer>();
        rewrites.forEach(rewrite -> rules.merge(rewrite.rule(), 1, Integer::sum));
        return Map.of("evidence", "COMPILER_IDIOMS_FROM_ORIGINAL_BYTECODE_ROUND_TRIP_NOT_WORSE_BEHAVIOR_NOT_VERIFIED", "count", rewrites.size(),
            "rules", rules, "rewrites", rewrites.stream().map(rewrite -> Map.of("file", rewrite.file(), "line", rewrite.line(),
                "rule", rewrite.rule(), "before", rewrite.before(), "after", rewrite.after())).toList());
    }
    /**
     * Additive schema-v1 field (Phase 2BD): method bodies in the selected sources taken from another engine's version of
     * their file, each with the round trip measured when that file was recompiled alone; evidence, not behavior proof.
     * {@code replaced_body} (Phase 2BF) says whether the body it replaced failed to compile or had a DIFFERENT round trip.
     * Phase 2BG adds {@code round_trip_in_place_before} (null for a body that did not compile), {@code also_recovered}
     * (DIFFERENT lambda or anonymous-class methods of the body that it recovered) and {@code body_repairs} (repair rules
     * applied to the copied body).
     */
    public static Map<String,Object> methodComposition(List<dev.classrevive.verification.MethodSplice.Splice> splices) {
        return Map.of("evidence", "METHOD_BODIES_FROM_OTHER_ENGINES_ROUND_TRIP_IN_PLACE_BEHAVIOR_NOT_VERIFIED", "count", splices.size(),
            "files", (int) splices.stream().map(dev.classrevive.verification.MethodSplice.Splice::file).distinct().count(),
            "methods", splices.stream().map(EvidenceReport::splice).toList());
    }
    private static Map<String,Object> splice(dev.classrevive.verification.MethodSplice.Splice splice) {
        var entry = new TreeMap<String,Object>();
        entry.put("file", splice.file()); entry.put("class_name", splice.className()); entry.put("method", splice.method());
        entry.put("descriptor", splice.descriptor()); entry.put("engine", splice.engine()); entry.put("round_trip_in_place", splice.roundTrip());
        entry.put("canonical_type_names", splice.canonicalNames()); entry.put("replaced_body", splice.replaced());
        entry.put("round_trip_in_place_before", splice.before().equals("-") ? null : splice.before());
        entry.put("also_recovered", splice.alsoRecovered()); entry.put("body_repairs", splice.bodyRepairs());
        return entry;
    }
    public static void job(Path output, String inputHash, DecompilationJob.VerifiedJob job) throws IOException {
        job(output, inputHash, job, null);
    }
    /** {@code multiRelease} is written as {@code multi_release} only for JAR inputs (null for a .class input). */
    public static void job(Path output, String inputHash, DecompilationJob.VerifiedJob job, Map<String,Object> multiRelease) throws IOException {
        job(output, inputHash, job, multiRelease, null, List.of());
    }
    /** Phase 2BV {@code portable_names}: null when every class name was already portable and none collided by case. */
    public static Map<String,Object> portableNames(Map<String,String> renames, List<dev.classrevive.model.Diagnostic> notices) {
        return portableNames(renames, Map.of(), Map.of(), notices);
    }
    /** Phase 2BW adds {@code renamed_members} (member to new name) and {@code members_left_unchanged} (member to reason). */
    public static Map<String,Object> portableNames(Map<String,String> renames, Map<String,String> members, Map<String,String> keptMembers,
                                                   List<dev.classrevive.model.Diagnostic> notices) {
        if (renames.isEmpty() && members.isEmpty() && keptMembers.isEmpty()
            && notices.stream().noneMatch(n -> n.code() == dev.classrevive.model.Diagnostic.Code.CASE_COLLISION)) return null;
        var data = new TreeMap<String,Object>();
        data.put("renamed_classes", new TreeMap<>(renames));
        data.put("renamed_members", new TreeMap<>(members));
        data.put("members_left_unchanged", new TreeMap<>(keptMembers));
        data.put("member_policy", "A private field or method whose name is no Java identifier, or is a reserved word, gets each offending character "
            + "replaced by _ (a leading digit gets a _ prefix, a reserved word a _ suffix; _crN when the name is taken in that class). "
            + "Private members are never inherited or overridden, so every reference names the declaring class and is rewritten. "
            + "Non-private members are left unchanged. Native methods are renamed too: JNI linkage by name is not verified.");
        data.put("mapping_file", renames.isEmpty() && members.isEmpty() ? null : DecompilationJob.CASE_DIRECTORY + "/mapping.txt");
        data.put("case_groups", notices.stream().filter(n -> n.code() == dev.classrevive.model.Diagnostic.Code.CASE_COLLISION)
            .map(dev.classrevive.model.Diagnostic::message).toList());
        data.put("policy", "A name segment that is no Java identifier gets each offending character replaced by _ (a package moves as a whole). "
            + "Of classes whose names then differ only in letter case, the first in string order keeps its name and the others get a stable _crN suffix. "
            + "Nested classes follow, every reference is rewritten, and sources, signatures and verification use the new names. "
            + "Resources are never extracted; a resource colliding by case with a kept entry is left out of the inventory "
            + "(the first in archive order is kept, a class always wins) and listed in inventory_notices.");
        return data;
    }
    public static void job(Path output, String inputHash, DecompilationJob.VerifiedJob job, Map<String,Object> multiRelease,
                           Map<String,Object> portableNames, List<dev.classrevive.model.Diagnostic> notices) throws IOException {
        job(output, inputHash, job, multiRelease, portableNames, notices, null);
    }
    /** Phase 2BZ {@code obfuscation}: static techniques and suggested obfuscator; omitted when null. */
    public static void job(Path output, String inputHash, DecompilationJob.VerifiedJob job, Map<String,Object> multiRelease,
                           Map<String,Object> portableNames, List<dev.classrevive.model.Diagnostic> notices,
                           Map<String,Object> obfuscation) throws IOException {
        job(output, inputHash, job, multiRelease, portableNames, notices, obfuscation, null);
    }
    /** Phase 2CI {@code embedded_libraries}: bundled third-party libraries and the application's share; omitted when null. */
    public static void job(Path output, String inputHash, DecompilationJob.VerifiedJob job, Map<String,Object> multiRelease,
                           Map<String,Object> portableNames, List<dev.classrevive.model.Diagnostic> notices,
                           Map<String,Object> obfuscation, Map<String,Object> embeddedLibraries) throws IOException {
        job(output, inputHash, job, multiRelease, portableNames, notices, obfuscation, embeddedLibraries, null);
    }
    /** Phase 2CJ {@code compiler}: language, javac or ecj, javac lower bound, class versions and debug information; omitted when null. */
    public static void job(Path output, String inputHash, DecompilationJob.VerifiedJob job, Map<String,Object> multiRelease,
                           Map<String,Object> portableNames, List<dev.classrevive.model.Diagnostic> notices,
                           Map<String,Object> obfuscation, Map<String,Object> embeddedLibraries, Map<String,Object> compiler) throws IOException {
        job(output, inputHash, job, multiRelease, portableNames, notices, obfuscation, embeddedLibraries, compiler, null);
    }
    /** Phase 2CT {@code missing_type_stubs}: stand-ins for types nobody supplied; omitted when none were needed. */
    public static void job(Path output, String inputHash, DecompilationJob.VerifiedJob job, Map<String,Object> multiRelease,
                           Map<String,Object> portableNames, List<dev.classrevive.model.Diagnostic> notices,
                           Map<String,Object> obfuscation, Map<String,Object> embeddedLibraries, Map<String,Object> compiler,
                           Map<String,Object> stubs) throws IOException {
        job(output, inputHash, job, multiRelease, portableNames, notices, obfuscation, embeddedLibraries, compiler, stubs, null);
    }
    /** Phase 2DD {@code deobfuscation}: the automatic reversal of a recognized obfuscator; omitted when none was recognized. */
    public static void job(Path output, String inputHash, DecompilationJob.VerifiedJob job, Map<String,Object> multiRelease,
                           Map<String,Object> portableNames, List<dev.classrevive.model.Diagnostic> notices,
                           Map<String,Object> obfuscation, Map<String,Object> embeddedLibraries, Map<String,Object> compiler,
                           Map<String,Object> stubs, Map<String,Object> deobfuscation) throws IOException {
        var data = new TreeMap<String,Object>();
        data.put("schema_version", 1); data.put("kind", "decompilation"); data.put("tool_version", "0.1.0-dev");
        data.put("input_sha256", inputHash); data.put("environment", environment());
        data.put("behavior", "NOT_VERIFIED"); data.put("engines", job.engines().stream().map(EvidenceReport::engine).toList());
        data.put("verification", verification(job.selection().verification()));
        data.put("selected_candidate", job.selection().candidate()); data.put("provenance", job.selection().provenance());
        data.put("selection_attempts", job.selection().attempts());
        data.put("fallback", job.fallback() == null ? null : engine(job.fallback()));
        data.put("source_repairs", sourceRepairs(job.selection().repairs()));
        data.put("method_composition", methodComposition(job.selection().methods()));
        data.put("compiler_idioms", compilerIdioms(job.selection().idioms()));
        // Phase 2BX: enum constant bodies an engine wrote as their own file, folded back into the constant in the selection.
        data.put("enum_bodies_folded", job.selection().folds().stream().map(fold -> {
            var row = new TreeMap<String,Object>(); row.put("body_file", fold.bodyFile()); row.put("enum_file", fold.enumFile());
            row.put("constant", fold.constant()); return (Object) row; }).toList());
        data.put("per_file_round_trip", perFile(job.perFile()));
        // Phase G2 (additive): each class's confidence level as report.html shows it, with the evidence behind it.
        data.put("class_confidence", HtmlReport.confidence(job));
        if (multiRelease != null) data.put("multi_release", multiRelease);
        if (portableNames != null) data.put("portable_names", portableNames);
        // Phase 2BV: informational intake notices (case collisions, resources left out); none of them rejected the input.
        if (!notices.isEmpty()) data.put("inventory_notices", notices.stream().map(n -> {
            var row = new TreeMap<String,Object>(); row.put("code", n.code().name()); row.put("entry", n.entry()); row.put("message", n.message());
            return (Object) row; }).toList());
        if (obfuscation != null) data.put("obfuscation", obfuscation);
        if (embeddedLibraries != null) data.put("embedded_libraries", embeddedLibraries);
        if (compiler != null) data.put("compiler", compiler);
        if (stubs != null) data.put("missing_type_stubs", stubs);
        if (deobfuscation != null) data.put("deobfuscation", deobfuscation);
        write(output.resolve("report.json"), data);
    }
}
