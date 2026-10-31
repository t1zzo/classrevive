package dev.classrevive.pipeline;

import dev.classrevive.engine.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.io.*;
import java.util.concurrent.*;
import java.util.zip.*;
import dev.classrevive.intake.InventoryReader;
import dev.classrevive.intake.MultiRelease;
import dev.classrevive.model.*;
import dev.classrevive.selection.*;

public final class DecompilationJob {
    /** Phase 2CH: opt-in engine output cache; null runs every engine. */
    private Path engineCache;
    public DecompilationJob withEngineCache(Path cache) { this.engineCache = cache; return this; }
    /** Phase 2CT: inferred stubs for missing types on the compiler's class path; on unless turned off. */
    private boolean stubs = true;
    public DecompilationJob withStubs(boolean enabled) { this.stubs = enabled; return this; }
    /** Phase 2DD: static reversal of an obfuscator the diagnosis names with HIGH confidence; on unless turned off. */
    private boolean deobfuscate = true;
    public DecompilationJob withDeobfuscation(boolean enabled) { this.deobfuscate = enabled; return this; }
    /** Phase G1: stage and engine progress; {@link JobListener#NONE} unless set. */
    private JobListener listener = JobListener.NONE;
    public DecompilationJob withListener(JobListener listener) { this.listener = Objects.requireNonNull(listener); return this; }
    /** The stub JAR's name, also used in the exported project's lib/. */
    static final String STUB_JAR = "classrevive-inferred-stubs.jar";
    /** {@code perFile}: Phase 2BP per-file round trip when the selected candidate did not compile; null otherwise. */
    /** {@code caseRenames}: Phase 2BV classes renamed because their names differed only in letter case or were no Java identifiers (original to new). */
    /** {@code memberRenames}: Phase 2BW private members renamed because their names were no Java identifiers ({@code owner#name(desc)} or {@code owner#name:desc} to new name). */
    /** {@code deobfuscation}: Phase 2DD automatic reversal of a recognized obfuscator, or null when none was recognized. */
    /** {@code stubs}: Phase 2CT inferred stubs put on the compiler's class path, or null when stubs were off (exposed by Phase 2DF). */
    public record VerifiedJob(List<EngineResult> engines, SelectionResult selection, EngineResult fallback,
                              dev.classrevive.verification.PartialRoundTrip.Result perFile, Map<String,String> caseRenames,
                              Map<String,String> memberRenames, AutoDeobfuscation.Outcome deobfuscation,
                              dev.classrevive.bytecode.StubSynthesizer.Result stubs) {
        public VerifiedJob { engines = List.copyOf(engines); caseRenames = Collections.unmodifiableMap(new TreeMap<>(caseRenames));
            memberRenames = Collections.unmodifiableMap(new TreeMap<>(memberRenames)); }
        public VerifiedJob(List<EngineResult> engines, SelectionResult selection, EngineResult fallback,
                           dev.classrevive.verification.PartialRoundTrip.Result perFile, Map<String,String> caseRenames,
                           Map<String,String> memberRenames, AutoDeobfuscation.Outcome deobfuscation) {
            this(engines, selection, fallback, perFile, caseRenames, memberRenames, deobfuscation, null);
        }
        public VerifiedJob(List<EngineResult> engines, SelectionResult selection, EngineResult fallback,
                           dev.classrevive.verification.PartialRoundTrip.Result perFile, Map<String,String> caseRenames,
                           Map<String,String> memberRenames) {
            this(engines, selection, fallback, perFile, caseRenames, memberRenames, null);
        }
        public VerifiedJob(List<EngineResult> engines, SelectionResult selection, EngineResult fallback,
                           dev.classrevive.verification.PartialRoundTrip.Result perFile, Map<String,String> caseRenames) {
            this(engines, selection, fallback, perFile, caseRenames, Map.of());
        }
        public VerifiedJob(List<EngineResult> engines, SelectionResult selection, EngineResult fallback,
                           dev.classrevive.verification.PartialRoundTrip.Result perFile) { this(engines, selection, fallback, perFile, Map.of()); }
        public VerifiedJob(List<EngineResult> engines, SelectionResult selection, EngineResult fallback) { this(engines, selection, fallback, null); }
    }
    public VerifiedJob runVerified(Path input, Path output, List<DecompilerEngine> engines, Duration timeout) throws IOException, InterruptedException {
        return runVerified(input,output,engines,timeout,List.of());
    }
    public VerifiedJob runVerified(Path input, Path output, List<DecompilerEngine> engines, Duration timeout, List<Path> dependencies) throws IOException, InterruptedException {
        return runVerified(input, output, engines, timeout, dependencies, true);
    }
    /** @param sourceRepair whether selection may try compiler-guided source repairs (Phase 2AS) on a candidate that parsed */
    public VerifiedJob runVerified(Path input, Path output, List<DecompilerEngine> engines, Duration timeout, List<Path> dependencies,
                                   boolean sourceRepair) throws IOException, InterruptedException {
        dependencies=List.copyOf(dependencies);
        if(dependencies.size()>16) throw new IllegalArgumentException("At most 16 dependency JARs");
        output = SafePaths.resolveParents(output);
        var results = run(input, output, engines, timeout, dependencies);
        var inventory = new InventoryReader().inspect(output.resolve("classes.jar"), Limits.defaults());
        if (!inventory.successful()) throw new IOException("Normalized inventory failed");
        var originals = inventory.entries().stream().map(Inventory.Entry::classInfo).filter(Objects::nonNull).toList();
        // Phase 2CX: an Android input compiles against a local SDK's android.jar when there is one (javac only, never run).
        if (Files.isRegularFile(output.resolve(InputIdentifier.DIRECTORY).resolve(InputIdentifier.DALVIK))) {
            String line;
            if (AndroidSdk.supplied(dependencies)) line = "the android.jar given with the dependencies";
            else {
                // Phase 2DF: the verifier takes the SDK beyond the 16 user JARs (DependencyClasspath.MAX_INPUTS).
                var platform = AndroidSdk.find(targetSdk(output));
                if (platform.isPresent()) {
                    var withSdk = new ArrayList<>(dependencies); withSdk.add(platform.get().jar()); dependencies = List.copyOf(withSdk);
                    line = "Android SDK platform android-" + platform.get().apiLevel() + " (found through " + platform.get().foundBy() + "): "
                        + platform.get().jar();
                } else line = "no Android SDK found (ANDROID_HOME, ANDROID_SDK_ROOT or the default folder); android.* classes get inferred stubs";
            }
            Files.writeString(output.resolve("report.txt"), "Android framework for javac (Phase 2CX): " + line + "\n", StandardOpenOption.APPEND);
        }
        // Phase 2CT: types nobody supplied get stand-ins inferred from their uses, for the compiler only.
        dev.classrevive.bytecode.StubSynthesizer.Result stubResult = null;
        // Phase 2DF: no longer skipped when 16 dependencies were given; the verifier takes the stub JAR beyond them.
        if (stubs) {
            listener.stage(JobListener.Stage.STUBS);
            Path stubJar = output.resolve("stubs").resolve(STUB_JAR);
            stubResult = dev.classrevive.bytecode.StubSynthesizer.synthesize(
                dev.classrevive.verification.MethodRoundTrip.readJar(output.resolve("classes.jar")), dependencies, stubJar);
            if (stubResult.isEmpty()) Files.delete(stubJar);
            else {
                var withStubs = new ArrayList<>(dependencies); withStubs.add(stubJar); dependencies = List.copyOf(withStubs);
                Files.write(output.resolve("stubs").resolve("stubs.txt"), stubResult.classes(), StandardCharsets.UTF_8);
            }
        }
        var candidates = candidateSources(results, output);
        listener.stage(JobListener.Stage.SELECTION);
        var selection = new SourceSelector(sourceRepair).select(candidates, output.resolve("selection"), originals, timeout, dependencies,
            output.resolve("classes.jar"), enginePreference(output.resolve("classes.jar")));
        // Phase 2DF: a candidate that compiled with stubs on the class path compiled against guesses, not the real libraries.
        // It stays complete (same status and exit code) but is marked, and the bytecode fallback is kept below.
        selection = selection.withStubBacked(stubResult != null && !stubResult.isEmpty());
        var caseRenames = caseRenames(output);
        var memberRenames = memberRenames(output);
        var keptMembers = keptMembers(output);
        Path selectionReport = output.resolve("selection").resolve("selection.txt");
        if (!caseRenames.isEmpty() && Files.isRegularFile(selectionReport, LinkOption.NOFOLLOW_LINKS))
            Files.writeString(selectionReport, "\n" + caseRenameSummary(caseRenames), StandardOpenOption.APPEND);
        if ((!memberRenames.isEmpty() || !keptMembers.isEmpty()) && Files.isRegularFile(selectionReport, LinkOption.NOFOLLOW_LINKS))
            Files.writeString(selectionReport, "\n" + memberRenameSummary(memberRenames, keptMembers), StandardOpenOption.APPEND);
        // Phase 2BP: a candidate that does not compile as a whole is still measured file by file against the originals.
        dev.classrevive.verification.PartialRoundTrip.Result perFile = null;
        Path selected = output.resolve("selection").resolve("sources");
        if (selection.verification().status() != dev.classrevive.verification.VerificationResult.Status.COMPILED && Files.isDirectory(selected)) {
            listener.stage(JobListener.Stage.PER_FILE);
            perFile = new dev.classrevive.verification.PartialRoundTrip().measure(selected, output.resolve("per-file"), originals, timeout,
                dependencies, output.resolve("classes.jar"));
        }
        EngineResult fallback = null;
        if (!selection.verification().complete() || selection.stubBacked()) {
            listener.stage(JobListener.Stage.FALLBACK);
            fallback = new dev.classrevive.bytecode.BytecodeFallback().dump(output.resolve("classes.jar"), output.resolve("fallback"), timeout);
        }
        listener.stage(JobListener.Stage.REPORTS);
        var verification = selection.verification();
        // Phase G4: the selected candidate's javac errors beside its sources, for the desktop application's editor.
        if (!verification.diagnostics().isEmpty() && Files.isDirectory(output.resolve("selection"), LinkOption.NOFOLLOW_LINKS))
            dev.classrevive.verification.SelectedDiagnostics.write(output.resolve("selection").resolve(dev.classrevive.verification.SelectedDiagnostics.FILE),
                verification.diagnostics());
        Files.writeString(output.resolve("report.txt"), "\nPhase 1C selection: " + selection.candidate()
            + "\nCompilation: " + verification.status() + "\nSignatures: " + verification.matchedSymbols() + "/" + verification.expectedSymbols()
            + "\nPlaceholder signals: " + verification.placeholderSignals()
            + (selection.repairs().isEmpty() ? "" : "\nSource repairs: " + selection.repairs().size()
                + " compiler-guided edit(s) in the selected sources (evidence only; listed in selection/selection.txt and report.json)")
            + (EvidenceReport.environmentStubs(selection.repairs()) == 0 ? "" : "\nEnvironment stubs: " + EvidenceReport.environmentStubs(selection.repairs())
                + " class(es) given methods the original classes do not have: a JDK type requires them only since after the classes' target"
                + " release, which javac 21 cannot build (lowest: 8); each throws UnsupportedOperationException (rule environment-stub)")
            + (selection.idioms().isEmpty() ? "" : "\nCompiler idioms undone: " + selection.idioms().size() + " rewrite(s) in "
                + selection.idioms().stream().map(dev.classrevive.verification.IdiomResugar.Rewrite::file).distinct().count()
                + " file(s), read from the original bytecode (Phase 2DH; listed in selection/selection.txt and report.json)")
            + (selection.methods().isEmpty() ? "" : "\nMethod composition: " + selection.methods().size() + " method bod"
                + (selection.methods().size() == 1 ? "y" : "ies") + " from other engines in "
                + selection.methods().stream().map(dev.classrevive.verification.MethodSplice.Splice::file).distinct().count()
                + " file(s)" + differentBodies(selection.methods()) + " (round trip in place; listed in selection/selection.txt and report.json)")
            + (perFile == null ? "" : "\nPer-file round trip (each file compiled against the "
                + (AutoDeobfuscation.usable(deobfuscation) ? "reversed" : "original") + " classes): " + perFile.compiledFiles()
                + "/" + perFile.files() + " files compiled, " + (perFile.identical() + perFile.equivalent()) + " bodies identical or equivalent, "
                + perFile.different() + " different")
            + "\nResult: " + (verification.complete() ? "COMPILED_WITH_SIGNATURE_COVERAGE" : "PARTIAL")
            + (verification.stubBacked() ? "\nStub-backed: yes; the sources compiled against " + stubResult.classes().size()
                + " inferred stub class(es) for missing libraries, not the real ones (stubs/stubs.txt). The bytecode fallback is kept;"
                + " pass the real JARs with --classpath for a faithful check." : "")
            + "\nBehavior not verified. See selection/selection.txt and selection/checks/*/engine.log.\n"
            + (fallback == null ? "" : "Bytecode fallback: " + fallback.status() + "\n"), StandardOpenOption.APPEND);
        var job = new VerifiedJob(results, selection, fallback, perFile, caseRenames, memberRenames, deobfuscation, stubResult);
        Path snapshot = Files.exists(output.resolve("input.jar")) ? output.resolve("input.jar") : output.resolve("input.class");
        var originalInventory = new InventoryReader().inspect(snapshot, Limits.defaults());
        if (!originalInventory.successful()) throw new IOException("Snapshot inventory failed before reporting");
        var normalizedClasses = dev.classrevive.verification.MethodRoundTrip.readJar(output.resolve("classes.jar"));
        // Phase 2CI: bundled third-party libraries, named after the classes the engines saw (portable names included).
        var libraries = EmbeddedLibraries.detect(snapshot, normalizedClasses.keySet());
        // Phase 2CJ: which compiler (and language) produced the classes, from bytecode traces.
        var compiler = dev.classrevive.bytecode.CompilerFingerprint.analyze(normalizedClasses.values());
        EvidenceReport.job(output, originalInventory.sha256(), job, snapshot.getFileName().toString().endsWith(".jar")
            ? EvidenceReport.multiRelease(MultiRelease.variants(originalInventory), multiReleaseDeclared(snapshot)) : null,
            EvidenceReport.portableNames(caseRenames, memberRenames, keptMembers, originalInventory.notices()), originalInventory.notices(),
            obfuscation == null ? null : ObfuscationSummary.json(obfuscation.profile(), obfuscation.result()), EmbeddedLibraries.json(libraries),
            dev.classrevive.bytecode.CompilerFingerprint.json(compiler), stubJson(stubResult),
            deobfuscation == null ? null : AutoDeobfuscation.json(deobfuscation));
        String libraryText = dev.classrevive.bytecode.CompilerFingerprint.text(compiler) + EmbeddedLibraries.text(libraries) + stubText(stubResult);
        if (!libraryText.isEmpty()) Files.writeString(output.resolve("report.txt"), libraryText, StandardOpenOption.APPEND);
        // Phase 2CF: the same evidence as a confidence map, for people.
        int bodies = dev.classrevive.verification.MethodRoundTrip.countBodies(normalizedClasses);
        HtmlReport.write(output, input.getFileName() == null ? snapshot.getFileName().toString() : input.getFileName().toString(),
            originalInventory.sha256(), job, bodies, libraries, compiler, stubResult == null ? 0 : stubResult.classes().size());
        // Phase 2CG: the selected sources as a project an IDE opens directly.
        var project = ProjectExport.write(output, output.resolve("selection").resolve("sources"), snapshot, dependencies, originals,
            verification, input.getFileName() == null ? snapshot.getFileName().toString() : input.getFileName().toString());
        Files.writeString(output.resolve("report.txt"), "IDE project: project/ (" + project.sources() + " sources, " + project.resources()
            + " resources, " + project.libraries() + " libraries, release " + project.release() + ")\n", StandardOpenOption.APPEND);
        return job;
    }
    /** Phase 2CY: the targetSdkVersion the decoded manifest recorded in input-origin/identification.json, or null. */
    private static Integer targetSdk(Path output) {
        try {
            Path data = output.resolve(InputIdentifier.DIRECTORY).resolve("identification.json");
            if (Json.decode(Files.readString(data, StandardCharsets.UTF_8)) instanceof Map<?,?> origin
                    && origin.get("android_manifest") instanceof Map<?,?> manifest && manifest.get("target_sdk") instanceof Number level)
                return level.intValue();
        } catch (IOException | RuntimeException unreadable) {
            // No manifest level: the newest platform is used.
        }
        return null;
    }
    public List<EngineResult> run(Path input, Path output, List<DecompilerEngine> engines, Duration timeout) throws IOException, InterruptedException {
        return run(input, output, engines, timeout, List.of());
    }
    /** @param dependencies Phase 2DD: only for the JVM verifier that checks an automatic reversal */
    private List<EngineResult> run(Path input, Path output, List<DecompilerEngine> engines, Duration timeout, List<Path> dependencies)
            throws IOException, InterruptedException {
        engines = List.copyOf(engines);
        if (engines.isEmpty() || engines.size() > 4) throw new IllegalArgumentException("Select one to four engines");
        if (timeout.isNegative() || timeout.isZero() || timeout.compareTo(Duration.ofHours(1)) > 0)
            throw new IllegalArgumentException("Timeout must be between 1 nanosecond and 1 hour");
        var ids = new HashSet<String>();
        for (var engine : engines) {
            if (!engine.id().matches("[a-z][a-z0-9-]{0,31}") || !ids.add(engine.id()))
                throw new IllegalArgumentException("Invalid or duplicate engine id");
        }
        listener.stage(JobListener.Stage.INTAKE);
        input = input.toAbsolutePath().normalize();
        output = SafePaths.resolveParents(output); // Phase 2DI: parent links are followed once; the folder itself is never a link.
        String filename = input.getFileName() == null ? "" : input.getFileName().toString().toLowerCase(Locale.ROOT);
        boolean jar = filename.endsWith(".jar");
        if (!Files.isRegularFile(input, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Expected an existing regular .class or .jar input");
        // Phase 2CE: any other input is identified from its bytes; a class file or a jar found in it (an executable's overlay,
        // a renamed zip) is taken as data, and anything else fails with what the input is (InputIdentifier.NoJavaException).
        InputIdentifier.Resolved resolved = null;
        if (!jar && !filename.endsWith(".class")) {
            resolved = InputIdentifier.resolve(input, timeout);
            jar = resolved.jar();
        }
        if (output.getParent() == null) throw new IOException("Filesystem root cannot be an output directory");
        if (!Files.isDirectory(output.getParent())) throw new IOException("Output parent directory must exist");
        Files.createDirectory(output); // Atomic refusal to overwrite an existing result.
        try {
            Path snapshot = output.resolve(jar ? "input.jar" : "input.class");
            if (resolved != null) {
                Files.write(snapshot, resolved.bytes(), StandardOpenOption.CREATE_NEW);
                InputIdentifier.record(output, resolved);
            } else {
                try (var in = Files.newInputStream(input); var out = Files.newOutputStream(snapshot, StandardOpenOption.CREATE_NEW)) {
                    copyBounded(in, out, Limits.defaults().maxInputBytes());
                }
            }
            Inventory inventory = new InventoryReader().inspect(snapshot, Limits.defaults());
            if (!inventory.successful()) throw new IOException("Input rejected: " + inventory.diagnostics().getFirst().code());
            // Multi-release JARs: the base version is decompiled; valid META-INF/versions/N variants are reported and skipped.
            var variants = jar ? MultiRelease.variants(inventory) : List.<MultiRelease.Variant>of();
            var variantEntries = new HashSet<String>(); variants.forEach(variant -> variantEntries.add(variant.entry()));
            List<Inventory.Entry> classes = inventory.entries().stream()
                .filter(e -> e.classInfo() != null && !variantEntries.contains(e.name())).toList();
            if (classes.isEmpty()) throw new IOException("Input contains no supported classes");
            int inputClasses = classes.size();
            var names = identities(classes, jar);
            var classBytes = readClasses(snapshot, classes, jar);
            obfuscation = obfuscation(snapshot, classBytes, jar);
            // Phase 2DD: a recognized obfuscator with a reversal pass is undone first; the reversed classes replace the input's
            // for everything below (names, engines, selection, round trip). Any failure keeps the original classes.
            Path classSource = snapshot;
            deobfuscation = reverse(output, snapshot, classes, jar, dependencies, timeout);
            if (AutoDeobfuscation.usable(deobfuscation)) {
                classSource = deobfuscation.jar();
                var reversed = new InventoryReader().inspect(classSource, Limits.defaults());
                if (!reversed.successful()) throw new IOException("Reversed classes rejected: " + reversed.diagnostics().getFirst().code());
                classes = reversed.entries().stream().filter(e -> e.classInfo() != null).toList();
                if (classes.isEmpty()) throw new IOException("Reversed classes are empty");
                jar = true;
                names = identities(classes, true);
                classBytes = readClasses(classSource, classes, true);
            }
            Path normalized = output.resolve("classes.jar");
            // Phase 2BV: classes whose names differ only in letter case, or are no Java identifiers, are renamed before any
            // engine runs, so every source file and every compiled class gets its own path on a case-insensitive file system.
            Map<String,String> caseRenames;
            try { caseRenames = dev.classrevive.transforms.ClassRenamer.portableIdentities(names); }
            catch (IllegalArgumentException error) { throw new IOException("Class names cannot be made portable: " + error.getMessage()); }
            for (String name : names)
                if (!safeClassName(caseRenames.getOrDefault(name, name))) throw new IOException("Unsafe or duplicate class identity");
            // Phase 2BW: private fields and methods whose names are no Java identifiers (or are reserved words) get portable
            // names in the same rename pass, so a call such as 0a2b4acb...() becomes a statement javac can parse.
            var members = portableMembers(classBytes);
            if (!members.kept().isEmpty()) {
                Files.createDirectories(output.resolve(CASE_DIRECTORY));
                var kept = new StringBuilder("# Phase 2BW: member names that are no Java identifiers, left unchanged (member<TAB>reason)\n");
                members.kept().forEach((key, reason) -> kept.append(memberKey(key)).append('\t').append(reason).append('\n'));
                Files.writeString(output.resolve(CASE_DIRECTORY).resolve(KEPT_MEMBERS), kept, StandardOpenOption.CREATE_NEW);
            }
            if (caseRenames.isEmpty() && members.renamed().isEmpty()) writeClasses(classSource, normalized, classes, jar);
            else disambiguate(classSource, output.resolve(CASE_DIRECTORY), normalized, classes, jar, caseRenames, members, timeout);
            // Phase 2CM: ZKM's opaque-predicate flags that are proved constant are folded before any engine reads the classes.
            String flagSummary = foldZkmFlags(output, normalized, timeout);
            // Phase 2CX: an engine that reads Dalvik bytecode (JADX) gets the original dex files of an Android input, as long
            // as its classes keep their names and code: renamed classes or folded flags would no longer match.
            // Phase 2DA: private members renamed for Java (Kotlin's `final`, `default` fields) do not block it: they touch only
            // those members, which the engine names its own way. An open-source Android app had 4 such fields; JADX on the
            // converted jar instead of the dex left 826 methods undecompiled instead of 524, in 394 s instead of 44 s.
            Path dalvik = output.resolve(InputIdentifier.DIRECTORY).resolve(InputIdentifier.DALVIK);
            boolean dalvikAsIs = Files.isRegularFile(dalvik, LinkOption.NOFOLLOW_LINKS) && caseRenames.isEmpty()
                && !AutoDeobfuscation.usable(deobfuscation) && !Files.exists(output.resolve(DEOBFUSCATION_DIRECTORY).resolve("classes-before-flags.jar"));
            var tasks = new ArrayList<Future<EngineResult>>();
            var results = new ArrayList<EngineResult>();
            var cache = engineCache == null ? null : new EngineCache(engineCache);
            listener.stage(JobListener.Stage.ENGINES);
            try (var executor = Executors.newFixedThreadPool(Math.min(WorkerConcurrency.limit(), engines.size()))) {
                try {
                    for (var engine : engines) {
                        Path engineOutput = output.resolve(engine.id());
                        Path engineInput = dalvikAsIs && engine.readsDalvik() ? dalvik : normalized;
                        tasks.add(executor.submit(() -> {
                            listener.engineStarted(engine.id());
                            var result = cache == null ? new ProcessRunner().run(engine, engineInput, engineOutput, timeout)
                                : cache.run(engine, engineInput, engineOutput, timeout);
                            listener.engineFinished(result);
                            return result;
                        }));
                    }
                    for (var task : tasks) results.add(task.get());
                } catch (ExecutionException error) {
                    tasks.forEach(task -> task.cancel(true));
                    throw new IOException("Worker orchestration failed", error.getCause());
                } catch (InterruptedException error) {
                    tasks.forEach(task -> task.cancel(true));
                    throw error;
                }
            }
            var report = new StringBuilder("ClassRevive phase 1B\nSource correctness and behavior are not verified.\n");
            report.append("Input SHA-256: ").append(inventory.sha256()).append('\n');
            report.append("Input classes: ").append(inputClasses).append('\n');
            report.append("Original retained as input.jar or input.class; resources are exported to project/src/main/resources.\n");
            if (!caseRenames.isEmpty()) report.append(caseRenameSummary(caseRenames));
            if (!members.renamed().isEmpty() || !members.kept().isEmpty()) {
                var renamedMembers = new TreeMap<String,String>(); members.renamed().forEach((key, to) -> renamedMembers.put(memberKey(key), to));
                var keptMembers = new TreeMap<String,String>(); members.kept().forEach((key, reason) -> keptMembers.put(memberKey(key), reason));
                report.append(memberRenameSummary(renamedMembers, keptMembers));
            }
            long collided = inventory.notices().stream().filter(n -> n.code() == Diagnostic.Code.CASE_COLLISION_SKIPPED).count();
            if (collided > 0) report.append(collided).append(" resource path(s) colliding by case left out of the inventory (see report.json).\n");
            for (var notice : inventory.notices()) if (notice.code() == Diagnostic.Code.OVERSIZED_RESOURCE_SKIPPED)
                report.append("Oversized resource left out unread: ").append(notice.entry()).append('\n');
            if (!variants.isEmpty()) {
                report.append("Multi-release: base version decompiled; ").append(variants.size())
                    .append(" META-INF/versions variant class(es) ignored (manifest Multi-Release: ")
                    .append(multiReleaseDeclared(snapshot)).append("):\n");
                int shown = 0;
                for (var variant : variants) {
                    if (shown++ == 100) { report.append("  ... ").append(variants.size() - 100).append(" more in report.json\n"); break; }
                    report.append("  ").append(variant.entry()).append(" (Java ").append(variant.release()).append(", ")
                        .append(variant.hasBase() ? "replaces base class" : "no base class").append(")\n");
                }
            }
            if (obfuscation != null) report.append(ObfuscationSummary.text(obfuscation.profile(), obfuscation.result()));
            if (deobfuscation != null) report.append(AutoDeobfuscation.text(deobfuscation));
            if (flagSummary != null) report.append(flagSummary);
            if (Files.isRegularFile(dalvik, LinkOption.NOFOLLOW_LINKS)) {
                var dalvikReaders = engines.stream().filter(DecompilerEngine::readsDalvik).map(DecompilerEngine::id).toList();
                if (!dalvikReaders.isEmpty()) report.append("Dalvik input (Phase 2CX): ").append(String.join(", ", dalvikReaders))
                    .append(dalvikAsIs ? " read the original dex files (input-origin/dalvik.zip); the other engines read the converted classes"
                        + (members.renamed().isEmpty() ? ".\n" : "; the " + members.renamed().size()
                            + " private member(s) renamed for Java keep their own names there.\n")
                        : " read the converted classes too: classes were renamed, reversed or flags folded, so the dex names no longer match.\n");
            }
            for (var result : results) {
                report.append(result.engine()).append(' ').append(result.version()).append(": ")
                    .append(result.status()).append("; exit=").append(result.exitCode())
                    .append("; sources=").append(result.sourceFiles()).append("; ms=")
                    .append(result.elapsedMillis()).append('\n');
            }
            Files.writeString(output.resolve("report.txt"), report, StandardOpenOption.CREATE_NEW);
            return List.copyOf(results);
        } catch (IOException error) {
            Files.writeString(output.resolve("failure.txt"), "Job failed: " + error.getMessage(), StandardOpenOption.CREATE_NEW);
            throw error;
        }
    }

    /**
     * Every engine that left a {@code sources} tree is a candidate, also one that ended {@code FAILED}, {@code TIMED_OUT}
     * or {@code OUTPUT_LIMIT}: selection compiles and checks each file against the original classes before it counts, so
     * a partial tree can only add verified files (Phase 2CK fills the classes a truncated tree lacks from the others).
     * Only a {@code CANCELLED} worker is left out: the user stopped the job.
     */
    static Map<String,Path> candidateSources(List<EngineResult> results, Path output) {
        var candidates = new TreeMap<String,Path>();
        for (var result : results) {
            Path sources = output.resolve(result.engine()).resolve("sources");
            if (result.status() != EngineResult.Status.CANCELLED && Files.isDirectory(sources)) candidates.put(result.engine(), sources);
        }
        return candidates;
    }

    static final String CASE_DIRECTORY = "portable-names";
    /** Phase 2CM: where the ZKM flag folding keeps its transform evidence and the classes as they were before it. */
    static final String DEOBFUSCATION_DIRECTORY = "deobfuscation";
    /** Phase 2CM: methods with flag-tested locals (Phase 2CA's metric) needed before flags are folded; clean JARs have 0. */
    static final int ZKM_FLAG_METHODS = 10;

    /**
     * Phase 2CM: folds the ZKM flags {@link dev.classrevive.transforms.ZkmFlagPass} proves constant, in the transform
     * worker, and makes the folded classes the ones every engine, the selection and the round trip read; the classes as
     * normalized before stay in {@code deobfuscation/classes-before-flags.jar}. Runs only when the obfuscation profile
     * found ZKM flag accessors. Returns the report lines, or null when it did not run.
     */
    private String foldZkmFlags(Path output, Path normalized, Duration timeout) throws IOException, InterruptedException {
        if (obfuscation == null || obfuscation.profile().metric("cf.flag_accessor_methods") < ZKM_FLAG_METHODS) return null;
        Path directory = output.resolve(DEOBFUSCATION_DIRECTORY);
        // Phase 2DD: the profile describes the classes before an automatic reversal, so it no longer applies after one.
        if (Files.exists(directory, LinkOption.NOFOLLOW_LINKS))
            return "ZKM opaque flags (Phase 2CM): not folded; the automatic reversal (Phase 2DD) used " + DEOBFUSCATION_DIRECTORY + "/\n";
        var result = new TransformJob().run(normalized, directory, 10_000, timeout, dev.classrevive.transforms.ZkmFlagPass.NAME);
        String prefix = "ZKM opaque flags (Phase 2CM): ";
        if (!result.successful()) return prefix + "transform worker " + result.worker().status() + "; the engines read the classes unfolded\n";
        if (result.changes() == 0) return prefix + "no test on a flag proved constant; the engines read the classes unfolded\n";
        Path folded = directory.resolve("worker/transformed/transformed.jar");
        var before = new InventoryReader().inspect(normalized, Limits.defaults());
        var after = new InventoryReader().inspect(folded, Limits.defaults());
        if (!after.successful() || !classNames(before).equals(classNames(after))) throw new IOException("Folded classes differ from the input's class set");
        Files.move(normalized, directory.resolve("classes-before-flags.jar"));
        try (var in = Files.newInputStream(folded); var out = Files.newOutputStream(normalized, StandardOpenOption.CREATE_NEW)) {
            copyBounded(in, out, Limits.defaults().maxInputBytes());
        }
        var owners = new TreeSet<String>();
        for (String method : result.methods()) owners.add(method.substring(0, method.indexOf('.')));
        return prefix + result.changes() + " test(s) folded in " + result.methods().size() + " method(s) of " + owners.size()
            + " class(es); the engines, the selection and the round trip read the folded classes (before: "
            + DEOBFUSCATION_DIRECTORY + "/classes-before-flags.jar, evidence: " + DEOBFUSCATION_DIRECTORY + "/transform.json)\n";
    }
    private static SortedSet<String> classNames(Inventory inventory) {
        var names = new TreeSet<String>();
        for (var entry : inventory.entries()) if (entry.classInfo() != null) names.add(entry.classInfo().name());
        return names;
    }
    static final String KEPT_MEMBERS = "members-kept.txt";

    /** Phase 2DD: the outcome of the automatic reversal, kept for report.txt and report.json; null when none was due. */
    private AutoDeobfuscation.Outcome deobfuscation;

    /**
     * Phase 2DD: the automatic reversal for a HIGH obfuscator hypothesis with a reversal pass, or null when there is none.
     * The input's classes (base version only) are written to a jar the transform worker reads, as {@code transform} would.
     */
    private AutoDeobfuscation.Outcome reverse(Path output, Path snapshot, List<Inventory.Entry> classes, boolean jar, List<Path> dependencies,
                                              Duration timeout) throws IOException, InterruptedException {
        var hypothesis = obfuscation == null ? Optional.<dev.classrevive.bytecode.ObfuscatorClassifier.Hypothesis>empty()
            : AutoDeobfuscation.candidate(obfuscation.result());
        if (hypothesis.isEmpty()) return null;
        if (!deobfuscate) return AutoDeobfuscation.off(hypothesis.get());
        writeClasses(snapshot, output.resolve(AutoDeobfuscation.INPUT), classes, jar);
        return AutoDeobfuscation.run(output, hypothesis.get(), dependencies, timeout);
    }

    /** Internal class names, each checked once and against its entry name. */
    private static TreeSet<String> identities(List<Inventory.Entry> classes, boolean jar) throws IOException {
        var names = new TreeSet<String>();
        for (var entry : classes) {
            String name = entry.classInfo().name();
            if (!names.add(name)) throw new IOException("Unsafe or duplicate class identity");
            if (jar && !entry.name().equals(name + ".class"))
                throw new IOException("Class entry identity mismatch or invalid multi-release variant: " + entry.name());
        }
        return names;
    }

    /**
     * Reads the normalized class bytes (bounded) and plans Phase 2BW member names. ASM only parses the classes; nothing is
     * defined or run. A class ASM cannot parse leaves every member name unchanged, as before this phase.
     */
    private static dev.classrevive.transforms.ClassRenamer.MemberPlan portableMembers(Map<String,byte[]> bytes) {
        try { return dev.classrevive.transforms.ClassRenamer.portableMembers(bytes); }
        catch (RuntimeException unparsable) { return new dev.classrevive.transforms.ClassRenamer.MemberPlan(Map.of(), Map.of()); }
    }

    /** Bounded bytes of the decompiled classes, by internal name; read once for member names and the obfuscation profile. */
    private static Map<String,byte[]> readClasses(Path snapshot, List<Inventory.Entry> classes, boolean jar) throws IOException {
        var bytes = new TreeMap<String,byte[]>();
        try (var source = jar ? new ZipFile(snapshot.toFile()) : null) {
            for (var entry : classes) {
                try (var stream = jar ? source.getInputStream(source.getEntry(entry.name())) : Files.newInputStream(snapshot)) {
                    var buffer = new ByteArrayOutputStream();
                    copyBounded(stream, buffer, Limits.defaults().maxEntryBytes());
                    bytes.put(entry.classInfo().name(), buffer.toByteArray());
                }
            }
        }
        return bytes;
    }

    /** Phase 2BZ: profile and hypotheses of the input, kept for report.txt and report.json. */
    record Obfuscation(dev.classrevive.bytecode.ObfuscationProfile profile, dev.classrevive.bytecode.ObfuscatorClassifier.Result result) {}
    private Obfuscation obfuscation;

    /** Static obfuscation profile (Phase 2BZ). ASM parses the bytes; resources contribute only their names and sizes. */
    private static Obfuscation obfuscation(Path snapshot, Map<String,byte[]> classes, boolean jar) throws IOException {
        var builder = new dev.classrevive.bytecode.ObfuscationProfile.Builder();
        classes.values().forEach(builder::addClass);
        if (jar) try (var zip = new ZipFile(snapshot.toFile())) {
            var entries = zip.entries(); int visited = 0;
            while (entries.hasMoreElements() && visited++ < 100_000) {
                var entry = entries.nextElement();
                if (!entry.isDirectory() && !entry.getName().toLowerCase(Locale.ROOT).endsWith(".class")) builder.addResource(entry.getName(), entry.getSize());
            }
        }
        var profile = builder.build();
        return new Obfuscation(profile, dev.classrevive.bytecode.ObfuscatorClassifier.classify(profile));
    }

    /** {@code owner#name(desc)} for a method, {@code owner#name:desc} for a field: the member syntax of the mapping file. */
    static String memberKey(dev.classrevive.transforms.ClassRenamer.MemberKey key) {
        var name = new StringBuilder();
        // Control characters are legal in JVM member names; they are escaped so every report keeps one line per member.
        key.name().chars().forEach(c -> { if (c < ' ' || Character.isISOControl(c) || Character.isSurrogate((char) c))
            name.append(String.format(Locale.ROOT, "\\u%04x", c)); else name.append((char) c); });
        return key.owner() + "#" + name + (key.field() ? ":" : "") + key.descriptor();
    }

    /**
     * Writes the normalized classes to {@code portable-names/classes-original.jar}, renames them in the
     * sandboxed rename worker with an explicit mapping (every reference is rewritten), checks the worker's class set
     * and copies the result to {@code classes.jar}. Never executes or extracts the analyzed classes.
     */
    private static void disambiguate(Path snapshot, Path directory, Path normalized, List<Inventory.Entry> classes, boolean jar,
                                     Map<String,String> renames, dev.classrevive.transforms.ClassRenamer.MemberPlan members,
                                     Duration timeout) throws IOException {
        Files.createDirectories(directory);
        Path colliding = directory.resolve("classes-original.jar");
        writeClasses(snapshot, colliding, classes, jar);
        var mapping = new StringBuilder("# Phase 2BV: classes renamed because their names differ only in letter case or are no Java identifiers\n");
        renames.forEach((from, to) -> mapping.append("class ").append(from).append(" = ").append(to).append('\n'));
        if (!members.renamed().isEmpty())
            mapping.append("# Phase 2BW: private members renamed because their names are no Java identifiers or are reserved words (original owners)\n");
        members.renamed().forEach((key, to) -> mapping.append(key.field() ? "field " : "method ").append(memberKey(key)).append(" = ").append(to).append('\n'));
        Path mappingFile = directory.resolve("mapping.txt");
        Files.writeString(mappingFile, mapping, StandardOpenOption.CREATE_NEW);
        var worker = new ProcessRunner().runRename(new dev.classrevive.transforms.RenameEngine(mappingFile), colliding,
            directory.resolve("worker"), timeout);
        if (worker.status() != EngineResult.Status.OUTPUT_PRODUCED)
            throw new IOException("Portable-name rename worker failed: " + worker.status() + " (see " + CASE_DIRECTORY + "/worker)");
        Path renamed = directory.resolve("worker/renamed/renamed.jar");
        var result = new InventoryReader().inspect(renamed, Limits.defaults());
        if (!result.successful() || !result.notices().isEmpty()) throw new IOException("Portable-name inventory failed");
        var expected = new TreeSet<String>();
        for (var entry : classes) expected.add(renames.getOrDefault(entry.classInfo().name(), entry.classInfo().name()));
        var actual = new TreeSet<String>();
        for (var entry : result.entries()) {
            if (entry.classInfo() == null || !entry.name().equals(entry.classInfo().name() + ".class"))
                throw new IOException("Portable-name output has an unexpected entry");
            actual.add(entry.classInfo().name());
        }
        if (!expected.equals(actual)) throw new IOException("Portable-name class set mismatch");
        try (var in = Files.newInputStream(renamed); var out = Files.newOutputStream(normalized, StandardOpenOption.CREATE_NEW)) {
            copyBounded(in, out, Limits.defaults().maxInputBytes());
        }
    }

    /** The Phase 2BV mapping recorded by {@link #run}, or an empty map when every class name was already portable. */
    static Map<String,String> caseRenames(Path output) throws IOException {
        Path file = output.resolve(CASE_DIRECTORY).resolve("mapping.txt");
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) return Map.of();
        try { return dev.classrevive.transforms.MappingFile.parse(Files.readAllBytes(file)).classes(); }
        catch (IllegalArgumentException error) { throw new IOException("Invalid portable-name mapping: " + error.getMessage()); }
    }

    /** The Phase 2BW member mapping recorded by {@link #run} ({@link #memberKey} to new name), or an empty map. */
    static Map<String,String> memberRenames(Path output) throws IOException {
        Path file = output.resolve(CASE_DIRECTORY).resolve("mapping.txt");
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) return Map.of();
        try {
            var result = new TreeMap<String,String>();
            for (var rule : dev.classrevive.transforms.MappingFile.parse(Files.readAllBytes(file)).members())
                result.put(rule.owner() + "#" + rule.name() + (rule.field() ? ":" : "") + rule.descriptor(), rule.target());
            return result;
        } catch (IllegalArgumentException error) { throw new IOException("Invalid portable-name mapping: " + error.getMessage()); }
    }

    /** Phase 2BW member names left unchanged ({@link #memberKey} to reason), or an empty map. */
    static Map<String,String> keptMembers(Path output) throws IOException {
        Path file = output.resolve(CASE_DIRECTORY).resolve(KEPT_MEMBERS);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) return Map.of();
        var result = new TreeMap<String,String>();
        for (String line : Files.readAllLines(file)) {
            int tab = line.lastIndexOf('\t');
            if (!line.startsWith("#") && tab > 0) result.put(line.substring(0, tab), line.substring(tab + 1));
        }
        return result;
    }

    static String memberRenameSummary(Map<String,String> renamed, Map<String,String> kept) {
        var text = new StringBuilder();
        if (!renamed.isEmpty()) {
            text.append("Portable member names: ").append(renamed.size())
                .append(" private member(s) renamed because their names were no Java identifiers or were reserved words (references rewritten; mapping in ")
                .append(CASE_DIRECTORY).append("/mapping.txt and report.json):\n");
            int shown = 0;
            for (var entry : renamed.entrySet()) {
                if (shown++ == 100) { text.append("  ... ").append(renamed.size() - 100).append(" more\n"); break; }
                text.append("  ").append(entry.getKey()).append(" -> ").append(entry.getValue()).append('\n');
            }
        }
        if (!kept.isEmpty()) {
            text.append(kept.size()).append(" member name(s) that are no Java identifiers left unchanged (")
                .append(CASE_DIRECTORY).append('/').append(KEPT_MEMBERS).append(" and report.json):\n");
            int shown = 0;
            for (var entry : kept.entrySet()) {
                if (shown++ == 20) { text.append("  ... ").append(kept.size() - 20).append(" more\n"); break; }
                text.append("  ").append(entry.getKey()).append(" (").append(entry.getValue()).append(")\n");
            }
        }
        return text.toString();
    }

    static String caseRenameSummary(Map<String,String> renames) {
        var text = new StringBuilder("Portable class names: ").append(renames.size())
            .append(" class(es) renamed because their names differed only in letter case or were no Java identifiers (references rewritten; mapping in ")
            .append(CASE_DIRECTORY).append("/mapping.txt and report.json):\n");
        int shown = 0;
        for (var entry : renames.entrySet()) {
            if (shown++ == 100) { text.append("  ... ").append(renames.size() - 100).append(" more\n"); break; }
            text.append("  ").append(entry.getKey()).append(" -> ").append(entry.getValue()).append('\n');
        }
        return text.toString();
    }

    /**
     * Phase 2DG: the source files whose top-level class kotlinc compiled (it carries {@code kotlin.Metadata}), read from the
     * normalized classes with ASM, never loaded. Selection tries JADX first for them when its own evidence ties.
     */
    static EnginePreference enginePreference(Path classes) throws IOException {
        var bytes = dev.classrevive.verification.MethodRoundTrip.readJar(classes).values();
        var files = new TreeSet<String>();
        for (byte[] data : bytes) {
            var node = new org.objectweb.asm.tree.ClassNode();
            new org.objectweb.asm.ClassReader(data).accept(node, org.objectweb.asm.ClassReader.SKIP_CODE);
            if (!dev.classrevive.bytecode.CompilerFingerprint.kotlin(node)) continue;
            int slash = node.name.lastIndexOf('/'), dollar = node.name.indexOf('$', slash + 1);
            files.add((dollar > slash + 1 ? node.name.substring(0, dollar) : node.name) + ".java");
        }
        if (files.isEmpty()) return EnginePreference.NONE;
        return new EnginePreference(files, "Kotlin classes in " + files.size() + " source file(s); " + EnginePreference.KOTLIN_FIRST
            + " is tried first for them among alternatives that tie, and when the best engine tree stops at javac's error cap"
            + " while parsing, a by-language mix (" + EnginePreference.KOTLIN_FIRST + " for Kotlin classes, " + EnginePreference.JAVA_FIRST
            + " for Java classes) is verified too; no engine is left out");
    }

    /** Phase 2CT report lines; empty when no stub was needed. */
    static String stubText(dev.classrevive.bytecode.StubSynthesizer.Result result) {
        if (result == null || result.isEmpty()) return "";
        var out = new StringBuilder("Inferred stubs: ").append(result.classes().size()).append(" missing class(es) in ").append(result.packages().size())
            .append(" package(s) were synthesized from their uses (").append(result.methods()).append(" methods, ").append(result.fields())
            .append(" fields) and put on the compiler's class path only; results compiled against them, not against the real library")
            .append(result.truncated() ? "; the stub limit was reached" : "").append(". List: stubs/stubs.txt\n");
        result.packages().entrySet().stream().sorted(Map.Entry.<String,Integer>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
            .limit(10).forEach(entry -> out.append("  ").append(entry.getKey().isEmpty() ? "(default package)" : entry.getKey()).append(": ").append(entry.getValue()).append('\n'));
        return out.toString();
    }
    static Map<String,Object> stubJson(dev.classrevive.bytecode.StubSynthesizer.Result result) {
        if (result == null || result.isEmpty()) return null;
        var data = new TreeMap<String,Object>();
        data.put("classes", result.classes().size()); data.put("methods", result.methods()); data.put("fields", result.fields());
        data.put("packages", new TreeMap<>(result.packages())); data.put("truncated", result.truncated());
        data.put("jar", "stubs/" + STUB_JAR); data.put("list", "stubs/stubs.txt");
        data.put("meaning", "Types referenced by the input and supplied by nobody (not the input, the --classpath JARs or the JDK) were "
            + "synthesized from their uses and put on the compiler's class path only. Compilation and signature results that rely on "
            + "them were measured against inferred stand-ins, not the real library; pass the real JARs with --classpath to remove them.");
        return data;
    }

    /** Whether the manifest declares {@code Multi-Release: true}; without it the JVM never loads the variants. */
    static boolean multiReleaseDeclared(Path jar) throws IOException {
        try (var zip = new ZipFile(jar.toFile())) {
            var entry = zip.getEntry("META-INF/MANIFEST.MF");
            if (entry == null) return false;
            try (var stream = zip.getInputStream(entry)) {
                var manifest = new java.util.jar.Manifest(new ByteArrayInputStream(stream.readNBytes(1024 * 1024)));
                return "true".equalsIgnoreCase(String.valueOf(manifest.getMainAttributes().getValue("Multi-Release")).trim());
            }
        }
    }

    /** ", N replacing bodies whose round trip was DIFFERENT" when Phase 2BF composed any; empty otherwise. */
    private static String differentBodies(List<dev.classrevive.verification.MethodSplice.Splice> methods) {
        long different = methods.stream().filter(splice -> splice.replaced().equals(dev.classrevive.verification.MethodSplice.ROUND_TRIP_DIFFERENT)).count();
        return different == 0 ? "" : ", " + different + " replacing bodies whose round trip was DIFFERENT";
    }

    private static boolean safeClassName(String name) {
        if (name == null || name.length() > 1024) return false;
        for (var part : name.split("/", -1)) {
            if (!part.matches("[\\p{L}_$][\\p{L}\\p{N}_$]*") && !part.equals("module-info") && !part.equals("package-info")) return false;
            if (part.toUpperCase(Locale.ROOT).matches("CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9]")) return false;
        }
        return true;
    }

    private static void writeClasses(Path snapshot, Path output, List<Inventory.Entry> classes, boolean jar) throws IOException {
        try (var zip = new ZipOutputStream(Files.newOutputStream(output, StandardOpenOption.CREATE_NEW));
             var source = jar ? new ZipFile(snapshot.toFile()) : null) {
            for (var entry : classes) {
                var item = new ZipEntry(entry.classInfo().name() + ".class"); item.setTime(0);
                zip.putNextEntry(item);
                try (var stream = jar ? source.getInputStream(source.getEntry(entry.name())) : Files.newInputStream(snapshot)) {
                    copyBounded(stream, zip, Limits.defaults().maxEntryBytes());
                }
                zip.closeEntry();
            }
        }
    }

    private static void copyBounded(InputStream input, OutputStream output, long max) throws IOException {
        byte[] buffer = new byte[8192]; long total = 0; int read;
        while ((read = input.read(buffer)) != -1) {
            total += read;
            if (total > max) throw new IOException("Input exceeds byte limit");
            output.write(buffer, 0, read);
        }
    }
}
