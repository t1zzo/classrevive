package dev.classrevive.selection;

import dev.classrevive.model.ClassInfo;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.io.IOException;
import java.nio.file.*;
import dev.classrevive.verification.*;
import dev.classrevive.engine.WorkerConcurrency;
import java.util.concurrent.*;

public final class SourceSelector {
    /** Compiler-guided repair rounds after the hybrids; each is one javac analysis and one recompilation. */
    static final int MAX_REPAIR_ROUNDS = 3;
    /** Post-repair attempts that retry a still-failing file with another engine's version (Phase 2AZ). */
    /** Phase 2BS: 12 rather than 6, so files that fail only late (flow errors after repairs) still get their swaps. */
    private static final int MAX_POST_REPAIR_SWAPS = 12;
    /** Repair-then-swap cycles (Phase 2BB): each cycle runs up to MAX_REPAIR_ROUNDS repairs, then post-repair swaps. */
    private static final int MAX_REPAIR_CYCLES = 2;
    private final boolean sourceRepair;
    /** Phase 2BX: set per {@link #select} call; a selector is not shared between concurrent selections. */
    private EnumBodyFolding folding = EnumBodyFolding.none();
    private NestingForms forms = new NestingForms();
    private EnginePreference preference = EnginePreference.NONE;
    public SourceSelector() { this(true); }
    /** @param sourceRepair whether the best candidate that parsed but failed to compile gets the bounded repair pass */
    public SourceSelector(boolean sourceRepair) { this.sourceRepair = sourceRepair; }
    public SelectionResult select(Map<String,Path> candidates, Path output, List<ClassInfo> originals, Duration timeout) throws IOException {
        return select(candidates,output,originals,timeout,List.of());
    }
    public SelectionResult select(Map<String,Path> candidates, Path output, List<ClassInfo> originals, Duration timeout, List<Path> dependencies) throws IOException {
        return select(candidates,output,originals,timeout,dependencies,null);
    }
    /** Phase 2DG: as below, with ties between engines broken by the compiler fingerprint's {@link EnginePreference}. */
    public SelectionResult select(Map<String,Path> candidates, Path output, List<ClassInfo> originals, Duration timeout, List<Path> dependencies,
                                  Path originalClasses, EnginePreference preference) throws IOException {
        this.preference = preference;
        return select(candidates, output, originals, timeout, dependencies, originalClasses);
    }
    /** {@code originalClasses} feeds per-method round-trip evidence, used to break ties among structurally equal candidates. */
    public SelectionResult select(Map<String,Path> candidates, Path output, List<ClassInfo> originals, Duration timeout, List<Path> dependencies,
                                  Path originalClasses) throws IOException {
        dependencies=List.copyOf(dependencies);
        output = output.toAbsolutePath().normalize();
        Files.createDirectory(output);
        Path checks = Files.createDirectory(output.resolve("checks"));
        var verifier = new CompilerVerifier();
        folding = EnumBodyFolding.plan(originals, originalClasses); forms = new NestingForms();
        var alternatives = new TreeMap<String,NavigableMap<String,Path>>();
        var blanks = new TreeMap<String,SortedSet<String>>();
        var typedSites = new TreeMap<String,PlaceholderTypes.Result>();
        var report = new StringBuilder("Selection: compilation and structural signatures; behavior not verified.\n");
        if (preference.active()) report.append("Engine preference (Phase 2DG): ").append(preference.reason()).append('\n');
        Candidate best = null; int attempts = 0;
        var tried = new ArrayList<SelectionResult.Attempt>();
        // Engine candidates are independent, so they compile concurrently; results are ranked in the same sorted order
        // and attempt numbering as before, so the choice does not depend on which compilation finishes first.
        var ids = new ArrayList<String>(); var directories = new ArrayList<Path>(); var attemptDirectories = new ArrayList<Path>();
        for (var item : new TreeMap<>(candidates).entrySet()) {
            if (!item.getKey().matches("[a-z][a-z0-9-]{0,31}")) throw new IOException("Invalid candidate id");
            if (!Files.isDirectory(item.getValue(), LinkOption.NOFOLLOW_LINKS)) continue;
            var files = inventory(item.getValue());
            // Phase 2BY: a file that declares no type (Procyon writes 0 bytes for each class it fails on) supplies no class.
            var blank = new TreeSet<String>();
            // package-info.java and module-info.java declare no type by design and are kept.
            for (var file : files.entrySet())
                if (!file.getKey().matches("(.*/)?(package|module)-info\\.java") && !declaresType(file.getValue())) blank.add(file.getKey());
            blank.forEach(files::remove);
            if (!blank.isEmpty()) blanks.put(item.getKey(), blank);
            // Phase 2CL: JADX's placeholder type `??` stops javac at parsing; each such local gets its initializer's type.
            if (placeholders(files)) {
                Path work = Files.createDirectories(output.resolve("typed-work")).resolve(item.getKey());
                var typed = new PlaceholderTypes().type(item.getValue(), work, originals, timeout, dependencies);
                interrupted();
                files.putAll(typed.files());
                typedSites.put(item.getKey(), typed);
            }
            alternatives.put(item.getKey(), files);
            // An engine tree that splits enum constant bodies into files is verified as folded, like every mix (Phase 2BX).
            Path verified = item.getValue();
            if (folds(files)) { verified = output.resolve("folded").resolve(item.getKey()); Files.createDirectories(verified); copy(files, verified); }
            else if (typedSites.containsKey(item.getKey()) && !typedSites.get(item.getKey()).files().isEmpty()) {
                verified = output.resolve("typed").resolve(item.getKey()); Files.createDirectories(verified); copy(files, verified);
            }
            ids.add(item.getKey()); directories.add(verified); attemptDirectories.add(checks.resolve("attempt-" + attempts++));
        }
        var verifications = verifyAll(verifier, directories, attemptDirectories, originals, timeout, dependencies, originalClasses);
        for (int i = 0; i < ids.size(); i++) {
            String id = ids.get(i); var files = alternatives.get(id); var verification = verifications.get(i);
            var origins = new TreeMap<String,String>(); files.keySet().forEach(name -> origins.put(name, id));
            var candidate = new Candidate(id, files, origins, verification, List.of());
            append(report, candidate, tried);
            if (blanks.containsKey(id)) report.append(id).append(": ").append(blanks.get(id).size())
                .append(" file(s) declare no type (empty or comments only); they supply no class\n");
            if (typedSites.containsKey(id)) {
                var typed = typedSites.get(id);
                var rules = new TreeMap<String,Long>();
                typed.sites().forEach(site -> rules.merge(site.rule(), 1L, Long::sum));
                report.append(id).append(": JADX output that is no Java fixed in ").append(typed.files().size())
                    .append(" file(s) (Phase 2CL): ").append(rules.getOrDefault("initializer", 0L) + rules.getOrDefault("object", 0L))
                    .append(" placeholder type(s) `??` (").append(rules.getOrDefault("initializer", 0L)).append(" from the initializer, ")
                    .append(rules.getOrDefault("object", 0L)).append(" as Object), ")
                    .append(rules.getOrDefault("long-literal", 0L) + rules.getOrDefault("double-bits", 0L))
                    .append(" oversized integer literal(s) (").append(rules.getOrDefault("long-literal", 0L)).append(" long, ")
                    .append(rules.getOrDefault("double-bits", 0L)).append(" double bits)")
                    .append(typed.status() == dev.classrevive.engine.EngineResult.Status.OUTPUT_PRODUCED || typed.status() == dev.classrevive.engine.EngineResult.Status.NO_SOURCES
                        ? "" : "; typing worker " + typed.status()).append('\n');
            }
            if (files.isEmpty()) report.append(id).append(": no .java files; ranked below every candidate with sources\n");
            if (best == null || better(candidate, best)) best = candidate;
        }
        // Phase 2DG: when the best engine tree is blind (stopped while parsing at javac's error cap), the hybrids below
        // cannot rank most files either, so the tree they grow from decides them. With Kotlin classes in the input, one more
        // candidate takes each file from the engine measured best for its class's language; it is verified like the others
        // and kept only when it is better, or as blind as the tree it starts from.
        if (best != null && preference.active() && blind(best.verification)) {
            var files = new TreeMap<>(best.files); var origins = new TreeMap<>(best.origins); int taken = 0;
            for (var file : new ArrayList<>(best.files.keySet())) {
                String want = preference.preferred(file);
                if (!files.containsKey(file) || want.equals(origins.get(file)) || !alternatives.containsKey(want)
                    || !supplies(files, alternatives.get(want), file)) continue;
                if (take(files, origins, alternatives.get(want), want, file) != null) taken++;
            }
            if (taken > 0) {
                Path sources = Files.createDirectory(output.resolve("by-language"));
                copy(files, sources);
                var result = verifier.verify(sources, checks.resolve("attempt-" + attempts++), originals, timeout, dependencies, originalClasses);
                interrupted();
                var candidate = new Candidate("by-language", files, origins, result, List.of());
                append(report, candidate, tried);
                boolean keep = better(candidate, best) || blindTie(candidate.verification, best.verification);
                report.append("  ").append(taken).append(" file(s) taken from ").append(EnginePreference.KOTLIN_FIRST).append(" (Kotlin classes) or ")
                    .append(EnginePreference.JAVA_FIRST).append(" (Java classes) into ").append(best.id).append("; ")
                    .append(keep ? "kept as the base of the hybrids" : "not kept: worse than " + best.id).append('\n');
                if (keep) best = candidate;
            }
        }
        if (best == null) {
            Path empty = Files.createDirectory(output.resolve("empty"));
            best = new Candidate("none", new TreeMap<>(), Map.of(), verifier.verify(empty, checks.resolve("attempt-" + attempts++), originals, timeout, dependencies, originalClasses), List.of());
            append(report, best, tried);
        }
        var engineResults = new TreeMap<String,VerificationResult>();
        for (int i = 0; i < ids.size(); i++) engineResults.put(ids.get(i), verifications.get(i));
        int hybrids = 0;
        // Hybrids guided by javac's per-file errors: each file that fails in the current mix is replaced by an untried
        // alternative whose own version of that file had the fewest errors (preferring candidates that parsed). The
        // choice still rests only on verified attempts; per-file errors only decide which mixes are tried.
        var swappedIn = new HashMap<String,Set<String>>();
        best.origins.forEach((file, origin) -> swappedIn.computeIfAbsent(file, ignored -> new HashSet<>()).add(origin));
        var mix = new TreeMap<>(best.files); var mixOrigins = new TreeMap<>(best.origins);
        var mixErrors = new TreeMap<>(best.verification.fileErrors());
        boolean mixParsed = best.verification.syntaxErrors() == 0;
        while (!best.verification.complete() && hybrids < 8 && !mixErrors.isEmpty()) {
            var files = new TreeMap<>(mix); var origins = new TreeMap<>(mixOrigins); var swapped = new ArrayList<Take>();
            // Phase 2BX: a family taken whole (other nesting form) is the only swap in that family this round.
            var whole = new HashSet<String>(); var touched = new HashSet<String>();
            for (var file : mixErrors.keySet()) {
                String family = NestingForms.family(file);
                if (!files.containsKey(file) || whole.contains(family)) continue;
                String pick = null; long rank = Long.MAX_VALUE;
                for (var alternative : preference.order(file, alternatives)) {
                    String id = alternative.getKey();
                    if (swappedIn.getOrDefault(file, Set.of()).contains(id) || !supplies(files, alternative.getValue(), file)) continue;
                    var result = engineResults.get(id);
                    boolean other = forms.otherForm(files, alternative.getValue(), family);
                    if (other && touched.contains(family)) continue;
                    long errors = other ? NestingForms.all(alternative.getValue(), family).keySet().stream()
                        .mapToLong(key -> fileErrors(result, key)).sum() : fileErrors(result, file);
                    long value = errors * 2L + (result.syntaxErrors() > 0 ? 1 : 0);
                    if (value < rank) { rank = value; pick = id; }
                }
                if (pick == null) continue;
                var take = take(files, origins, alternatives.get(pick), pick, file);
                if (take == null) continue;
                if (take.whole()) whole.add(family);
                touched.add(family);
                for (var key : take.keys()) swappedIn.computeIfAbsent(key, ignored -> new HashSet<>()).add(pick);
                swappedIn.computeIfAbsent(file, ignored -> new HashSet<>()).add(pick); swapped.add(take);
            }
            if (swapped.isEmpty()) break;
            String id = "hybrid-" + (++hybrids);
            Path sources = Files.createDirectory(output.resolve(id));
            copy(files, sources);
            var result = verifier.verify(sources, checks.resolve("attempt-" + attempts++), originals, timeout, dependencies, originalClasses);
            interrupted();
            var candidate = new Candidate(id, new TreeMap<>(files), new TreeMap<>(origins), result, List.of());
            append(report, candidate, tried);
            long families = swapped.stream().filter(Take::whole).count();
            report.append("  guided by per-file javac errors: ").append(swapped.size()).append(" file(s) replaced")
                .append(families == 0 ? "" : "; " + families + " of them as a whole nesting family (outer file and $ files together)").append('\n');
            if (better(candidate, best)) best = candidate;
            // Keep each replacement that made its own file better; revert the others before the next round. A file
            // that now fails to parse is reverted when the mix used to parse: its count is not comparable.
            var next = new TreeMap<>(result.fileErrors()); var previous = mixErrors;
            for (var take : swapped) {
                int before = take.before().keySet().stream().mapToInt(key -> previous.getOrDefault(key, 0)).sum();
                int after = take.keys().stream().mapToInt(key -> result.fileErrors().getOrDefault(key, 0)).sum();
                if (after < before && !(after > 0 && result.syntaxErrors() > 0 && mixParsed)) continue;
                take.undo(files, origins); take.keys().forEach(next::remove);
                for (var key : take.before().keySet()) if (mixErrors.containsKey(key)) next.put(key, mixErrors.get(key));
            }
            mix = files; mixOrigins = origins; mixErrors = next; mixParsed |= result.syntaxErrors() == 0;
        }
        var wholeTried = new HashSet<String>();
        outer: for (var alternative : alternatives.entrySet()) {
            for (var unit : alternative.getValue().entrySet()) {
                if (best.verification.complete() || hybrids >= 8) break outer;
                if (unit.getValue().equals(best.files.get(unit.getKey()))) continue;
                var files = new TreeMap<>(best.files); var origins = new TreeMap<>(best.origins);
                var take = take(files, origins, alternative.getValue(), alternative.getKey(), unit.getKey());
                if (take == null || take.whole() && !wholeTried.add(alternative.getKey() + "|" + NestingForms.family(unit.getKey()))) continue;
                String id = "hybrid-" + (++hybrids);
                Path sources = Files.createDirectory(output.resolve(id));
                copy(files, sources);
                var result = verifier.verify(sources, checks.resolve("attempt-" + attempts++), originals, timeout, dependencies, originalClasses);
                interrupted();
                var candidate = new Candidate(id, files, origins, result, List.of());
                append(report, candidate, tried);
                if (better(candidate, best)) best = candidate;
            }
        }
        // Phase 2BY: a class whose file declares no type in the engine tree the selection grew from is filled in from
        // the engine whose own version of that file had the fewest errors, and the filled mix becomes the base of the
        // repair stage. An empty file compiles with 0 errors, but the class it should declare is gone and the files that
        // use it fail with cant.resolve (on an obfuscated jar, Procyon, the best engine tree, left 94 classes empty). The
        // fill comes after the hybrids: no engine's version of such a class may parse (JADX's `??` types, CFR's
        // `** GOTO`), and once
        // javac stops at parsing the per-file errors no longer show the semantic failures the hybrids are guided by.
        // Phase 2CK: the same holds for a class whose file is missing altogether. An engine that timed out leaves a
        // truncated tree (the same jar with its dependencies: Procyon wrote 98 of 715 files, 59 of them empty); its few files have
        // the fewest errors, so the selection grew from it and ended with 99 files. Every file another engine supplies
        // is filled in, in the nesting form the mix already holds (Phase 2BX), preferring a top-level form for a family
        // the mix lacks.
        if (alternatives.size() > 1) {
            var files = new TreeMap<>(best.files); var origins = new TreeMap<>(best.origins);
            int filled = 0, missing = 0, completed = 0;
            var absent = new TreeSet<String>();
            blanks.values().forEach(absent::addAll);
            var elsewhere = new TreeSet<String>();
            for (var alternative : alternatives.values()) elsewhere.addAll(alternative.keySet());
            elsewhere.removeAll(absent);
            var classNames = new HashSet<String>(); originals.forEach(info -> classNames.add(info.name()));
            elsewhere.removeIf(file -> !classNames.contains(file.substring(0, file.length() - ".java".length())));
            var all = new TreeSet<>(absent); all.addAll(elsewhere);
            for (var file : all) {
                if (files.containsKey(file)) continue;
                boolean blank = absent.contains(file);
                String pick = null; long rank = Long.MAX_VALUE;
                for (var alternative : preference.order(file, alternatives)) {
                    if (blank ? !supplies(files, alternative.getValue(), file) : !completes(files, alternatives, alternative.getValue(), file)) continue;
                    var result = engineResults.get(alternative.getKey());
                    long value = fileErrors(result, file) * 2L + (result.syntaxErrors() > 0 ? 1 : 0);
                    if (value < rank) { rank = value; pick = alternative.getKey(); }
                }
                boolean taken = pick != null && take(files, origins, alternatives.get(pick), pick, file) != null;
                if (blank) { if (taken) filled++; else missing++; }
                else if (taken) completed++;
            }
            if (filled + missing > 0)
                report.append("Files that declare no type, absent from ").append(best.id).append(": ").append(filled)
                    .append(" taken from another engine, ").append(missing).append(" declared by no engine\n");
            if (completed > 0)
                report.append("Files missing from ").append(best.id).append(" (an engine tree without them, e.g. timed out): ")
                    .append(completed).append(" taken from another engine\n");
            filled += completed;
            if (filled > 0) {
                Path sources = Files.createDirectory(output.resolve("filled"));
                copy(files, sources);
                var result = verifier.verify(sources, checks.resolve("attempt-" + attempts++), originals, timeout, dependencies, originalClasses);
                interrupted();
                best = new Candidate("filled", files, origins, result, List.of());
                append(report, best, tried);
            }
        }
        // Bounded source repair: javac's own diagnostics on the best candidate that parsed but did not compile drive
        // minimal edits inside method bodies. Each file keeps its edits only when its own errors went down; an attempt
        // can be selected only when it has fewer errors than its base and no class lost a matched signature.
        if (sourceRepair && repairable(best.verification)) {
            var base = best;
            var repairMix = new TreeMap<>(best.files); var repairOrigins = new TreeMap<>(best.origins);
            var repairEdits = new TreeMap<String,List<SourceRepair.Edit>>();
            var repairErrors = new TreeMap<>(best.verification.fileErrors());
            var blocked = new TreeSet<String>();
            report.append("Source repair: compiler-guided edits inside method, lambda and initializer bodies; evidence only, behavior not verified.\n");
            // Repair and post-repair swaps alternate (Phase 2BB): a file taken from another engine can carry errors that
            // the repair rules fix, so a second cycle repairs again after the swaps. Budgets are per job.
            var swapTried = new HashMap<String,Set<String>>();
            repairOrigins.forEach((file, origin) -> swapTried.computeIfAbsent(file, ignored -> new HashSet<>()).add(origin.replace("+repair", "")));
            int swaps = 0, repairRounds = 0;
            for (int cycle = 1; cycle <= MAX_REPAIR_CYCLES && !repairErrors.isEmpty() && !best.verification.complete(); cycle++) {
                boolean swappedThisCycle = false;
                for (int round = 1; round <= MAX_REPAIR_ROUNDS && !repairErrors.isEmpty(); round++) {
                    String id = "repair-" + (++repairRounds);
                    Path input = Files.createDirectory(output.resolve(id + "-input"));
                    copy(repairMix, input);
                    var proposal = new SourceRepair().propose(input, checks.resolve(id), originals, timeout, dependencies, originalClasses);
                    interrupted();
                    var current = repairMix;
                    var edits = proposal.edits().stream().filter(edit -> !blocked.contains(edit.file()) && current.containsKey(edit.file())).toList();
                    report.append(id).append(" proposal: ").append(proposal.status()).append("; edits=").append(edits.size())
                        .append("; diagnostics-left=").append(proposal.unrepaired().size()).append('\n');
                    int shown = 0;
                    for (var left : proposal.unrepaired()) {
                        if (shown++ == 50) { report.append("  ... ").append(proposal.unrepaired().size() - 50).append(" more\n"); break; }
                        report.append("  left ").append(left.file()).append(':').append(left.line()).append(':').append(left.column())
                            .append(' ').append(left.code()).append(": ").append(left.reason()).append('\n');
                    }
                    if (edits.isEmpty()) break;
                    var byFile = new TreeMap<String,List<SourceRepair.Edit>>();
                    for (var edit : edits) byFile.computeIfAbsent(edit.file(), ignored -> new ArrayList<>()).add(edit);
                    var files = new TreeMap<>(repairMix); var origins = new TreeMap<>(repairOrigins); var applied = new TreeMap<>(repairEdits);
                    for (var file : byFile.entrySet()) {
                        files.put(file.getKey(), proposal.files().get(file.getKey()));
                        String origin = repairOrigins.get(file.getKey());
                        origins.put(file.getKey(), origin.endsWith("+repair") ? origin : origin + "+repair");
                        var all = new ArrayList<>(applied.getOrDefault(file.getKey(), List.of())); all.addAll(file.getValue());
                        applied.put(file.getKey(), List.copyOf(all));
                    }
                    Path sources = Files.createDirectory(output.resolve(id));
                    copy(files, sources);
                    var result = verifier.verify(sources, checks.resolve("attempt-" + attempts++), originals, timeout, dependencies, originalClasses);
                    interrupted();
                    var candidate = new Candidate(id, new TreeMap<>(files), new TreeMap<>(origins), result, flatten(applied));
                    append(report, candidate, tried);
                    var next = new TreeMap<>(result.fileErrors()); int kept = 0;
                    for (var file : byFile.entrySet()) {
                        int before = repairErrors.getOrDefault(file.getKey(), 0), after = result.fileErrors().getOrDefault(file.getKey(), 0);
                        boolean keep = result.syntaxErrors() == 0 && after < before;
                        for (var edit : file.getValue()) appendEdit(report, edit, keep ? "kept" : "reverted");
                        if (keep) { kept++; continue; }
                        blocked.add(file.getKey());
                        files.put(file.getKey(), repairMix.get(file.getKey())); origins.put(file.getKey(), repairOrigins.get(file.getKey()));
                        if (repairEdits.containsKey(file.getKey())) applied.put(file.getKey(), repairEdits.get(file.getKey())); else applied.remove(file.getKey());
                        next.put(file.getKey(), before);
                    }
                    boolean fewer = result.errors() < base.verification.errors() || result.status() == VerificationResult.Status.COMPILED;
                    boolean eligible = result.syntaxErrors() == 0 && fewer && !lostSignatures(result, base.verification);
                    report.append("  files kept=").append(kept).append(", reverted=").append(byFile.size() - kept)
                        .append("; eligible for selection=").append(eligible).append(eligible ? "" : " (needs fewer errors than "
                            + base.id + ", no syntax errors and no class losing a matched signature)").append('\n');
                    if (eligible && better(candidate, best)) best = candidate;
                    repairMix = files; repairOrigins = origins; repairEdits = applied; repairErrors = next;
                }
                // Post-repair swaps (Phase 2AZ): javac reports flow errors only once earlier phases pass, so a file can fail
                // only after repairs. Each such file is retried with every other engine's version, one swap per attempt;
                // a swap is kept when total errors drop without syntax errors, and selected under the same eligibility rule.
                while (swaps < MAX_POST_REPAIR_SWAPS && !repairErrors.isEmpty() && !best.verification.complete()) {
                    String file = null, pick = null;
                    search: for (var failing : repairErrors.keySet()) {
                        if (!repairMix.containsKey(failing)) continue;
                        for (var alternative : preference.order(failing, alternatives))
                            if (supplies(repairMix, alternative.getValue(), failing) && swapTried.computeIfAbsent(failing, ignored -> new HashSet<>()).add(alternative.getKey())) {
                                file = failing; pick = alternative.getKey(); break search;
                            }
                    }
                    if (file == null) break;
                    String id = "swap-" + (++swaps);
                    var files = new TreeMap<>(repairMix); var origins = new TreeMap<>(repairOrigins); var applied = new TreeMap<>(repairEdits);
                    var take = take(files, origins, alternatives.get(pick), pick, file);
                    if (take == null) continue;
                    take.touched().forEach(applied::remove);
                    Path sources = Files.createDirectory(output.resolve(id));
                    copy(files, sources);
                    var result = verifier.verify(sources, checks.resolve("attempt-" + attempts++), originals, timeout, dependencies, originalClasses);
                    interrupted();
                    var candidate = new Candidate(id, new TreeMap<>(files), new TreeMap<>(origins), result, flatten(applied));
                    append(report, candidate, tried);
                    int before = repairErrors.values().stream().mapToInt(Integer::intValue).sum();
                    // Phase 2CU: a swap that costs a matched signature is not the next repair base either. Fewer errors with
                    // lost signatures (Caffeine: 76 -> 41 errors, 1159 -> 0 signatures) steered the next repair into an
                    // attempt that could never be selected, and the swapped-out file was never repaired.
                    boolean keep = result.syntaxErrors() == 0 && !lostSignatures(result, base.verification)
                        && (result.status() == VerificationResult.Status.COMPILED || result.errors() < before);
                    boolean fewer = result.errors() < base.verification.errors() || result.status() == VerificationResult.Status.COMPILED;
                    boolean eligible = result.syntaxErrors() == 0 && fewer && !lostSignatures(result, base.verification);
                    report.append("  after repairs: ").append(file).append(" taken from ").append(pick).append(keep ? " (kept)" : " (reverted)")
                        .append("; eligible for selection=").append(eligible).append('\n');
                    if (eligible && better(candidate, best)) best = candidate;
                    if (keep) { swappedThisCycle = true; blocked.remove(file); repairMix = files; repairOrigins = origins; repairEdits = applied; repairErrors = new TreeMap<>(result.fileErrors()); }
                }
                if (!swappedThisCycle) break;
            }
            // Method composition (Phase 2BD): a file that still fails gets the failing method bodies from another engine's
            // version of the same file, each proved by recompiling the file alone against the original classes with an
            // identical or equivalent round trip for that method. The mix is then recompiled; a file keeps its splice only
            // when its own errors drop, and the attempt is selected under the same eligibility rule as repairs.
            Candidate composed = null;
            if (originalClasses != null && alternatives.size() > 1 && !repairErrors.isEmpty() && !best.verification.complete()) {
                var roots = new TreeMap<String,Path>();
                for (int i = 0; i < ids.size(); i++) roots.put(ids.get(i), directories.get(i));
                report.append("Method composition: failing method bodies taken from another engine's version of the same file; each kept only when ")
                    .append("the file recompiles alone against the original classes and that method's round trip is identical or equivalent; behavior not verified.\n");
                Path input = Files.createDirectory(output.resolve("compose-input"));
                copy(repairMix, input);
                var proposal = new MethodSplice().propose(input, checks.resolve("compose"), originals, timeout, dependencies, originalClasses, roots);
                interrupted();
                appendProposal(report, "compose", proposal);
                var spliced = new TreeMap<String,List<MethodSplice.Splice>>();
                for (var splice : proposal.splices())
                    if (repairMix.containsKey(splice.file())) spliced.computeIfAbsent(splice.file(), ignored -> new ArrayList<>()).add(splice);
                for (int round = 1; round <= 2 && !spliced.isEmpty(); round++) {
                    String id = "compose-" + round;
                    var files = new TreeMap<>(repairMix); var origins = new TreeMap<>(repairOrigins); var applied = new TreeMap<>(repairEdits);
                    var methods = new ArrayList<MethodSplice.Splice>();
                    for (var file : spliced.entrySet()) {
                        files.put(file.getKey(), proposal.files().get(file.getKey()));
                        origins.put(file.getKey(), repairOrigins.get(file.getKey()) + "+methods");
                        // Repair edits inside a replaced method are gone with its body.
                        var kept = applied.getOrDefault(file.getKey(), List.of()).stream().filter(edit -> file.getValue().stream()
                            .noneMatch(splice -> edit.line() >= splice.firstLine() && edit.line() <= splice.lastLine())).toList();
                        if (kept.isEmpty()) applied.remove(file.getKey()); else applied.put(file.getKey(), kept);
                        methods.addAll(file.getValue());
                    }
                    Path sources = Files.createDirectory(output.resolve(id));
                    copy(files, sources);
                    var result = verifier.verify(sources, checks.resolve("attempt-" + attempts++), originals, timeout, dependencies, originalClasses);
                    interrupted();
                    var candidate = new Candidate(id, new TreeMap<>(files), new TreeMap<>(origins), result, flatten(applied), methods);
                    append(report, candidate, tried);
                    if (result.syntaxErrors() == 0) composed = candidate;
                    var worse = new ArrayList<String>();
                    for (var file : spliced.keySet())
                        if (result.syntaxErrors() > 0 || result.fileErrors().getOrDefault(file, 0) >= repairErrors.getOrDefault(file, 0)) worse.add(file);
                    boolean fewer = result.errors() < base.verification.errors() || result.status() == VerificationResult.Status.COMPILED;
                    boolean eligible = result.syntaxErrors() == 0 && fewer && !lostSignatures(result, base.verification);
                    report.append("  files kept=").append(spliced.size() - worse.size()).append(", reverted=").append(worse.size())
                        .append("; eligible for selection=").append(eligible).append('\n');
                    if (eligible && better(result, best.verification)) best = candidate;
                    // A second attempt keeps only the files whose own errors dropped, so the selected set is measured as is.
                    if (worse.isEmpty() || worse.size() == spliced.size()) break;
                    worse.forEach(spliced::remove);
                }
            }
            // Phase 2BS: a composed file can let javac reach flow analysis, so a file may fail only now (Ant's IsReachable,
            // a missing return in Vineflower's output). The swap budget left goes to the files still failing in the last
            // composed attempt, one swap per attempt; a swap is kept when errors drop, and each attempt is selected under
            // the same eligibility rule as the others.
            var current = composed;
            while (current != null && swaps < MAX_POST_REPAIR_SWAPS && current.verification.status() != VerificationResult.Status.COMPILED) {
                String file = null, pick = null;
                search: for (var failing : current.verification.fileErrors().keySet()) {
                    if (!current.files.containsKey(failing)) continue;
                    for (var alternative : alternatives.entrySet())
                        if (supplies(current.files, alternative.getValue(), failing) && swapTried.computeIfAbsent(failing, ignored -> new HashSet<>()).add(alternative.getKey())) {
                            file = failing; pick = alternative.getKey(); break search;
                        }
                }
                if (file == null) break;
                String id = "swap-" + (++swaps);
                var files = new TreeMap<>(current.files); var origins = new TreeMap<>(current.origins);
                var take = take(files, origins, alternatives.get(pick), pick, file);
                if (take == null) continue;
                var swapped = take.touched();
                Path sources = Files.createDirectory(output.resolve(id));
                copy(files, sources);
                var result = verifier.verify(sources, checks.resolve("attempt-" + attempts++), originals, timeout, dependencies, originalClasses);
                interrupted();
                var candidate = new Candidate(id, files, origins, result,
                    current.repairs.stream().filter(edit -> !swapped.contains(edit.file())).toList(),
                    current.methods.stream().filter(splice -> !swapped.contains(splice.file())).toList());
                append(report, candidate, tried);
                boolean keep = result.syntaxErrors() == 0
                    && (result.errors() < current.verification.errors() || result.status() == VerificationResult.Status.COMPILED);
                boolean eligible = result.syntaxErrors() == 0 && !lostSignatures(result, best.verification)
                    && (result.errors() < best.verification.errors() || result.status() == VerificationResult.Status.COMPILED);
                report.append("  after composition: ").append(file).append(" taken from ").append(pick).append(keep ? " (kept)" : " (reverted)")
                    .append("; eligible for selection=").append(eligible).append('\n');
                if (eligible && better(candidate, best)) best = candidate;
                if (keep) current = candidate;
            }
        }
        // Method composition of DIFFERENT bodies (Phase 2BF): once the selected set compiles, a method whose round trip is
        // DIFFERENT gets another engine's body of the same method when the file, otherwise as it is, recompiles alone
        // against the original classes with that method identical or equivalent and no other method worse. The whole mix
        // is then recompiled; it is selected only when it still compiles, keeps every signature (and completeness) and
        // recovers more bodies in total. A second attempt keeps only the files whose own bodies improved in the mix.
        if (originalClasses != null && alternatives.size() > 1 && best.verification.status() == VerificationResult.Status.COMPILED
            && best.verification.roundTripDifferent() > 0) {
            var base = best;
            var roots = new TreeMap<String,Path>();
            for (int i = 0; i < ids.size(); i++) roots.put(ids.get(i), directories.get(i));
            report.append("Method composition of different bodies: a method whose round trip is DIFFERENT in ").append(base.id)
                .append(" takes another engine's body only when its file, recompiled alone against the original classes, makes that method ")
                .append("identical or equivalent with no other method worse; behavior not verified.\n");
            Path input = Files.createDirectory(output.resolve("recompose-input"));
            copy(base.files, input);
            var proposal = new MethodSplice().proposeForDifferent(input, checks.resolve("recompose"), originals, timeout, dependencies,
                originalClasses, roots, base.verification.roundTrip());
            interrupted();
            appendProposal(report, "recompose", proposal);
            var spliced = new TreeMap<String,List<MethodSplice.Splice>>();
            for (var splice : proposal.splices())
                if (base.files.containsKey(splice.file())) spliced.computeIfAbsent(splice.file(), ignored -> new ArrayList<>()).add(splice);
            for (int round = 1; round <= 2 && !spliced.isEmpty(); round++) {
                String id = "recompose-" + round;
                var files = new TreeMap<>(base.files); var origins = new TreeMap<>(base.origins);
                var methods = new ArrayList<>(base.methods);
                for (var file : spliced.entrySet()) {
                    files.put(file.getKey(), proposal.files().get(file.getKey()));
                    String origin = base.origins.get(file.getKey());
                    origins.put(file.getKey(), origin.endsWith("+methods") ? origin : origin + "+methods");
                    methods.addAll(file.getValue());
                }
                // Repair edits inside a replaced method are gone with its body.
                var repairs = base.repairs.stream().filter(edit -> spliced.getOrDefault(edit.file(), List.of()).stream()
                    .noneMatch(splice -> edit.line() >= splice.firstLine() && edit.line() <= splice.lastLine())).toList();
                Path sources = Files.createDirectory(output.resolve(id));
                copy(files, sources);
                var result = verifier.verify(sources, checks.resolve("attempt-" + attempts++), originals, timeout, dependencies, originalClasses);
                interrupted();
                var candidate = new Candidate(id, files, origins, result, repairs, methods);
                append(report, candidate, tried);
                // A file keeps its bodies only when it recovers more bodies in the mix than before; when the mix does not
                // compile, the files javac reports are the ones dropped.
                var worse = new ArrayList<String>();
                for (var file : spliced.keySet())
                    if (result.status() == VerificationResult.Status.COMPILED ? recovered(result, file) <= recovered(base.verification, file)
                        : result.fileErrors().getOrDefault(file, 0) > 0) worse.add(file);
                if (result.status() != VerificationResult.Status.COMPILED && worse.isEmpty()) worse.addAll(spliced.keySet());
                boolean eligible = result.status() == VerificationResult.Status.COMPILED && result.syntaxErrors() == 0
                    && !lostSignatures(result, base.verification) && (result.complete() || !base.verification.complete())
                    && recovered(result) > recovered(base.verification);
                report.append("  files kept=").append(spliced.size() - worse.size()).append(", reverted=").append(worse.size())
                    .append("; eligible for selection=").append(eligible).append(eligible ? "" : " (needs to compile with every signature of "
                        + base.id + " and recover more bodies in total)").append('\n');
                if (eligible && better(result, best.verification)) best = candidate;
                if (worse.isEmpty() || worse.size() == spliced.size()) break;
                worse.forEach(spliced::remove);
            }
        }
        // Phase 2DH: compiler idioms (enum switches printed on ordinals or switch maps, access$NNN calls, $assertionsDisabled
        // tests) undone in the selected mix. Each rewrite is read from the original bytecode and was kept by the worker
        // only when its file, recompiled alone against the original classes, kept every method's round trip; the mix
        // is then recompiled and taken only when nothing is worse: the same status or better, no more errors, every signature, and
        // no method of an edited file's classes with a worse round trip. A second attempt drops the files that were.
        var idioms = List.<IdiomResugar.Rewrite>of();
        if (sourceRepair && originalClasses != null && best.verification.status() != VerificationResult.Status.WORKER_FAILED) {
            var base = best;
            Path input = Files.createDirectory(output.resolve("idioms-input"));
            var inputFolds = copy(base.files, input);
            var folded = new HashSet<String>();
            inputFolds.forEach(fold -> { folded.add(fold.bodyFile()); folded.add(fold.enumFile()); });
            var proposal = new IdiomResugar().propose(input, checks.resolve("idioms"), originals, timeout, dependencies, originalClasses);
            interrupted();
            var edited = new TreeMap<String,List<IdiomResugar.Rewrite>>();
            for (var rewrite : proposal.rewrites())
                if (base.files.containsKey(rewrite.file()) && !folded.contains(rewrite.file()) && proposal.files().containsKey(rewrite.file()))
                    edited.computeIfAbsent(rewrite.file(), ignored -> new ArrayList<>()).add(rewrite);
            report.append("Compiler idioms (Phase 2DH): proposal ").append(proposal.status()).append("; rewrites=").append(proposal.rewrites().size())
                .append(" in ").append(edited.size()).append(" file(s); refused=").append(proposal.refusals().size())
                .append(" (each read from the original bytecode, kept per file only when the file recompiled alone kept every method's round trip)\n");
            appendRefusals(report, proposal.refusals());
            for (int round = 1; round <= 2 && !edited.isEmpty(); round++) {
                String id = "idioms-" + round;
                var files = new TreeMap<>(base.files);
                for (var file : edited.keySet()) files.put(file, proposal.files().get(file));
                Path sources = Files.createDirectory(output.resolve(id));
                copy(files, sources);
                var result = verifier.verify(sources, checks.resolve("attempt-" + attempts++), originals, timeout, dependencies, originalClasses);
                interrupted();
                var candidate = new Candidate(id, files, base.origins, result, base.repairs, base.methods);
                append(report, candidate, tried);
                var worse = new ArrayList<String>();
                for (var file : edited.keySet())
                    if (result.fileErrors().getOrDefault(file, 0) > base.verification.fileErrors().getOrDefault(file, 0)
                        || roundTripWorse(result, base.verification, file)) worse.add(file);
                boolean eligible = (result.status() == base.verification.status() || result.status() == VerificationResult.Status.COMPILED)
                    && result.syntaxErrors() <= base.verification.syntaxErrors()
                    && result.errors() <= base.verification.errors() && !lostSignatures(result, base.verification)
                    && (result.complete() || !base.verification.complete()) && recovered(result) >= recovered(base.verification)
                    && result.placeholderSignals() <= base.verification.placeholderSignals() && worse.isEmpty();
                report.append("  files kept=").append(edited.size() - worse.size()).append(", reverted=").append(worse.size())
                    .append("; selected=").append(eligible).append(eligible ? "" : " (needs the same status or better, no more errors, every signature of "
                        + base.id + " and no method worse)").append('\n');
                if (eligible) {
                    best = candidate;
                    idioms = edited.values().stream().flatMap(List::stream).toList();
                    break;
                }
                if (worse.isEmpty() || worse.size() == edited.size()) break;
                worse.forEach(edited::remove);
            }
        }
        Path selected = Files.createDirectory(output.resolve("sources"));
        var folds = copy(best.files, selected);
        report.append("Selected: ").append(best.id).append('\n');
        if (!folds.isEmpty()) {
            report.append("Enum constant bodies folded into their enum (Phase 2BX; InnerClasses absent, the body was a separate file): ")
                .append(folds.size()).append('\n');
            for (var fold : folds) report.append("  ").append(fold.bodyFile()).append(" -> ").append(fold.enumFile())
                .append(" (constant ").append(fold.constant()).append(")\n");
        }
        if (!best.methods.isEmpty())
            report.append("Method composition in the selected sources: ").append(best.methods.size()).append(" method bod")
                .append(best.methods.size() == 1 ? "y" : "ies").append(" from other engines in ")
                .append(best.methods.stream().map(MethodSplice.Splice::file).distinct().count())
                .append(" file(s); round trip measured in place, behavior not verified\n");
        if (!idioms.isEmpty()) {
            report.append("Compiler idioms undone in the selected sources (Phase 2DH): ").append(idioms.size()).append(" rewrite(s) in ")
                .append(idioms.stream().map(IdiomResugar.Rewrite::file).distinct().count())
                .append(" file(s); read from the original bytecode, round trip not worse, behavior not verified\n");
            for (var rewrite : idioms)
                report.append("  ").append(rewrite.rule()).append(' ').append(rewrite.file()).append(':').append(rewrite.line()).append(": `")
                    .append(clip(rewrite.before())).append("` -> `").append(clip(rewrite.after())).append("`\n");
        }
        if (!best.repairs.isEmpty())
            report.append("Source repairs in the selected sources: ").append(best.repairs.size()).append(" edit(s) in ")
                .append(best.repairs.stream().map(SourceRepair.Edit::file).distinct().count())
                .append(" file(s); compiler evidence only, behavior not verified\n");
        report.append(best.verification.complete() ? "COMPILED_WITH_SIGNATURE_COVERAGE" : "PARTIAL").append('\n');
        if (!best.verification.classCoverage().isEmpty()) {
            report.append("Class coverage (structural signatures only):\n");
            int shown = 0;
            for (var coverage : best.verification.classCoverage()) {
                if (shown++ == 500) {
                    report.append("... ").append(best.verification.classCoverage().size() - 500).append(" additional classes are listed in report.json\n");
                    break;
                }
                report.append(coverage.className()).append(": ").append(coverage.matchedSymbols()).append('/')
                    .append(coverage.expectedSymbols()).append(" signatures (coverage=").append(coverage.coveragePermille())
                    .append("/1000, ").append(coverage.complete() ? "complete" : "partial").append(")\n");
            }
        }
        if (best.verification.roundTripMeasured())
            report.append("Method round trip (tie-break among structurally equal candidates): identical=").append(best.verification.roundTripIdentical())
                .append(", equivalent-after-normalization=").append(best.verification.roundTripEquivalent())
                .append(", different=").append(best.verification.roundTripDifferent()).append('\n');
        report.append("Placeholder signals are text heuristics; they may include false positives.\n");
        var folded = new HashMap<String,String>();
        folds.forEach(fold -> folded.put(fold.bodyFile(), fold.enumFile()));
        for (var item : best.origins.entrySet()) {
            report.append(item.getKey()).append(" <- ").append(item.getValue())
                .append(folded.containsKey(item.getKey()) ? " (folded into " + folded.get(item.getKey()) + ")" : "").append('\n');
            // Per-method origin: the listed bodies come from another engine; the rest of the file from the file's origin.
            for (var splice : best.methods) if (splice.file().equals(item.getKey())) appendSplice(report, "  method ", splice);
        }
        Files.writeString(output.resolve("selection.txt"), report, StandardOpenOption.CREATE_NEW);
        return new SelectionResult(best.id, best.verification, best.origins, attempts, best.repairs, best.methods, tried, folds, idioms);
    }
    /** Phase 2DH: some method of the classes {@code file} is named after has a worse round trip in {@code result} than in {@code base}. */
    private static boolean roundTripWorse(VerificationResult result, VerificationResult base, String file) {
        String top = file.substring(0, file.length() - ".java".length());
        var now = new HashMap<String,MethodRoundTrip.Outcome>();
        for (var type : result.roundTrip())
            for (var method : type.methods()) now.put(type.className() + "." + method.name() + method.descriptor(), method.outcome());
        for (var type : base.roundTrip()) {
            if (!type.className().equals(top) && !type.className().startsWith(top + "$")) continue;
            for (var method : type.methods()) {
                var outcome = now.get(type.className() + "." + method.name() + method.descriptor());
                if (outcome == null || outcome.ordinal() > method.outcome().ordinal()) return true;
            }
        }
        return false;
    }
    private static void appendRefusals(StringBuilder report, List<IdiomResugar.Refusal> refusals) {
        var reasons = new TreeMap<String,Integer>();
        for (var refusal : refusals) reasons.merge(refusal.rule() + ": " + refusal.reason().replaceAll(": .*", ""), 1, Integer::sum);
        reasons.forEach((reason, count) -> report.append("  refused ").append(reason).append(" x").append(count).append('\n'));
    }
    private record Candidate(String id, NavigableMap<String,Path> files, Map<String,String> origins, VerificationResult verification,
                             List<SourceRepair.Edit> repairs, List<MethodSplice.Splice> methods) {
        Candidate(String id, NavigableMap<String,Path> files, Map<String,String> origins, VerificationResult verification, List<SourceRepair.Edit> repairs) {
            this(id, files, origins, verification, repairs, List.of());
        }
    }
    /**
     * Phase 2BX: one replacement in a mix. {@code before} holds the entries it removed or overwrote, {@code keys} the
     * entries it wrote; {@code whole} when the outer class's family came in the other nesting form, all files together.
     */
    private record Take(Map<String,Path> before, Map<String,String> beforeOrigins, Set<String> keys, boolean whole) {
        void undo(TreeMap<String,Path> files, TreeMap<String,String> origins) {
            for (var key : keys) { files.remove(key); origins.remove(key); }
            files.putAll(before); origins.putAll(beforeOrigins);
        }
        Set<String> touched() { var all = new TreeSet<>(keys); all.addAll(before.keySet()); return all; }
    }
    /**
     * Whether {@code pick} can supply {@code file} to {@code mix}: it has the file in the same nesting form as the mix, or
     * it holds the file's family top-level while the mix nests it. A nesting form never replaces a top-level one (Phase
     * 2BX): CFR, Vineflower and Procyon refer to member classes from other files by binary name ({@code B$C}), which a
     * nested family would leave unresolved; an obfuscated jar's first 2BX run showed 30 such errors for {@code ClassInfo$Method} and
     * its siblings once JADX's nested {@code ClassInfo.java} came in whole.
     */
    private boolean supplies(NavigableMap<String,Path> mix, NavigableMap<String,Path> pick, String file) throws IOException {
        String family = NestingForms.family(file);
        if (forms.otherForm(mix, pick, family)) return !NestingForms.members(pick, family).isEmpty();
        return pick.containsKey(file);
    }
    /**
     * Phase 2CK: whether {@code pick} can supply {@code file}, which the mix lacks, without changing a nesting form: pick
     * has the file, the mix does not hold its family in another form, and when the mix has nothing of the family, pick
     * does not nest a family that another engine holds top-level (the 2BX rule, for a family not yet in the mix).
     */
    private boolean completes(NavigableMap<String,Path> mix, Map<String,NavigableMap<String,Path>> alternatives,
                              NavigableMap<String,Path> pick, String file) throws IOException {
        String family = NestingForms.family(file);
        if (!pick.containsKey(file) || forms.otherForm(mix, pick, family)) return false;
        if (!NestingForms.all(mix, family).isEmpty() || !NestingForms.members(pick, family).isEmpty()) return true;
        for (var other : alternatives.values())
            if (other != pick && !NestingForms.members(other, family).isEmpty() && forms.otherForm(pick, other, family)) return false;
        return true;
    }
    /**
     * Takes {@code file} from {@code pick} into the mix. When the mix nests the file's family and pick holds it top-level,
     * the mix's whole family is replaced by pick's, so no binary class is declared twice (nested in one file and top-level
     * in another) and none is lost. Returns null when pick cannot supply the file, including when pick nests a family the
     * mix holds top-level.
     */
    private Take take(TreeMap<String,Path> files, TreeMap<String,String> origins, NavigableMap<String,Path> pick, String id, String file)
        throws IOException {
        String family = NestingForms.family(file);
        if (forms.otherForm(files, pick, family)) {
            if (NestingForms.members(pick, family).isEmpty()) return null;
            var incoming = NestingForms.all(pick, family);
            var before = new TreeMap<>(NestingForms.all(files, family)); var beforeOrigins = new TreeMap<String,String>();
            for (var key : before.keySet()) { beforeOrigins.put(key, origins.remove(key)); files.remove(key); }
            for (var item : incoming.entrySet()) { files.put(item.getKey(), item.getValue()); origins.put(item.getKey(), id); }
            return new Take(before, beforeOrigins, Set.copyOf(incoming.keySet()), true);
        }
        Path path = pick.get(file);
        if (path == null) return null;
        var before = new TreeMap<String,Path>(); var beforeOrigins = new TreeMap<String,String>();
        if (files.containsKey(file)) { before.put(file, files.get(file)); beforeOrigins.put(file, origins.get(file)); }
        files.put(file, path); origins.put(file, id);
        return new Take(before, beforeOrigins, Set.of(file), false);
    }
    /** True when the plan folds an enum constant body found in {@code files} (both the enum's and the body's file present). */
    private boolean folds(Map<String,Path> files) {
        return folding.bodies().stream().anyMatch(body -> files.containsKey(body.enumClass() + ".java") && files.containsKey(body.bodyClass() + ".java"));
    }
    private static void appendSplice(StringBuilder report, String prefix, MethodSplice.Splice splice) {
        report.append(prefix).append(splice.className()).append('.').append(splice.method()).append(splice.descriptor()).append(" <- ")
            .append(splice.engine()).append(" (round trip in place: ")
            .append(splice.before().equals("DIFFERENT") ? "DIFFERENT -> " : "").append(splice.roundTrip())
            .append(splice.alsoRecovered().isEmpty() ? "" : "; also recovered: " + String.join(", ", splice.alsoRecovered()))
            .append(splice.bodyRepairs().isEmpty() ? "" : "; body repairs: " + String.join(", ", splice.bodyRepairs()))
            .append(splice.canonicalNames().isEmpty() ? "" : "; canonical type names: " + String.join(", ", splice.canonicalNames())).append(")\n");
    }
    private static void appendProposal(StringBuilder report, String name, MethodSplice.Proposal proposal) {
        report.append(name).append(" proposal: ").append(proposal.status()).append("; methods=").append(proposal.splices().size()).append(" in ")
            .append(proposal.files().size()).append(" file(s); left=").append(proposal.unspliced().size()).append('\n');
        for (var splice : proposal.splices()) appendSplice(report, "  method " + splice.file() + ": ", splice);
        int shown = 0;
        for (var left : proposal.unspliced()) {
            if (shown++ == 50) { report.append("  ... ").append(proposal.unspliced().size() - 50).append(" more\n"); break; }
            report.append("  left ").append(left.file()).append(left.method().equals("-") ? "" : ": " + left.className() + "." + left.method())
                .append(": ").append(clip(left.reason())).append('\n');
        }
    }
    /** Recovered bodies of the classes a source file is named after ({@code a/B.java}: {@code a/B} and {@code a/B$...}). */
    private static int recovered(VerificationResult value, String file) {
        String top = file.substring(0, file.length() - ".java".length());
        return value.roundTrip().stream().filter(type -> type.className().equals(top) || type.className().startsWith(top + "$"))
            .mapToInt(type -> type.identical() + type.equivalent()).sum();
    }
    private static boolean repairable(VerificationResult value) {
        return value.status() == VerificationResult.Status.COMPILE_FAILED && value.syntaxErrors() == 0 && !value.fileErrors().isEmpty();
    }
    /** True when some class matched fewer structural signatures than in {@code base}. */
    private static boolean lostSignatures(VerificationResult result, VerificationResult base) {
        var matched = new HashMap<String,Integer>();
        result.classCoverage().forEach(coverage -> matched.put(coverage.className(), coverage.matchedSymbols()));
        return base.classCoverage().stream().anyMatch(coverage -> matched.getOrDefault(coverage.className(), 0) < coverage.matchedSymbols());
    }
    private static List<SourceRepair.Edit> flatten(Map<String,List<SourceRepair.Edit>> edits) {
        var all = new ArrayList<SourceRepair.Edit>(); edits.values().forEach(all::addAll); return List.copyOf(all);
    }
    private static void appendEdit(StringBuilder report, SourceRepair.Edit edit, String outcome) {
        report.append("  edit ").append(edit.file()).append(':').append(edit.line()).append(':').append(edit.column()).append(' ')
            .append(edit.code()).append(' ').append(edit.rule()).append(": `").append(clip(edit.before())).append("` -> `")
            .append(clip(edit.after())).append("` (").append(outcome).append(")\n");
    }
    private static String clip(String text) {
        String line = text.replace("\r", "").replace("\n", "\\n");
        return line.length() <= 160 ? line : line.substring(0, 160) + "...";
    }
    /** Verifies each directory in its own attempt folder, at most {@link WorkerConcurrency#limit()} compilers at once. */
    private static List<VerificationResult> verifyAll(CompilerVerifier verifier, List<Path> directories, List<Path> attemptDirectories,
                                                      List<ClassInfo> originals, Duration timeout, List<Path> dependencies,
                                                      Path originalClasses) throws IOException {
        if (directories.isEmpty()) return List.of();
        var tasks = new ArrayList<Future<VerificationResult>>();
        var results = new ArrayList<VerificationResult>();
        try (var executor = Executors.newFixedThreadPool(Math.min(WorkerConcurrency.limit(), directories.size()))) {
            try {
                for (int i = 0; i < directories.size(); i++) {
                    Path sources = directories.get(i), work = attemptDirectories.get(i);
                    tasks.add(executor.submit(() -> verifier.verify(sources, work, originals, timeout, dependencies, originalClasses)));
                }
                for (var task : tasks) results.add(task.get());
            } catch (ExecutionException error) {
                tasks.forEach(task -> task.cancel(true));
                if (error.getCause() instanceof IOException io) throw io;
                throw new IOException("Candidate verification failed", error.getCause());
            } catch (InterruptedException error) {
                tasks.forEach(task -> task.cancel(true));
                Thread.currentThread().interrupt();
                throw new IOException("Selection interrupted");
            }
        }
        interrupted();
        return results;
    }
    private static void interrupted() throws IOException {
        if (Thread.currentThread().isInterrupted()) throw new IOException("Selection interrupted");
    }
    /**
     * A candidate without .java files (an engine that failed before writing sources) always ranks last: javac then
     * reports COMPILE_FAILED with 0 errors, which would otherwise beat a real candidate with errors (Phase 2BE).
     */
    private static boolean better(Candidate a, Candidate b) {
        if (a.files.isEmpty() != b.files.isEmpty()) return b.files.isEmpty();
        return better(a.verification, b.verification);
    }
    private static boolean better(VerificationResult a, VerificationResult b) {
        if (a.complete() != b.complete()) return a.complete();
        boolean ac = a.status() == VerificationResult.Status.COMPILED, bc = b.status() == VerificationResult.Status.COMPILED;
        if (ac != bc) return ac;
        if (a.fullyCoveredClasses() != b.fullyCoveredClasses()) return a.fullyCoveredClasses() > b.fullyCoveredClasses();
        if (a.averageClassCoveragePermille() != b.averageClassCoveragePermille())
            return a.averageClassCoveragePermille() > b.averageClassCoveragePermille();
        if (a.matchedSymbols() != b.matchedSymbols()) return a.matchedSymbols() > b.matchedSymbols();
        // A candidate that parsed reached attribution; its error count is not comparable with one stopped at parsing.
        boolean ap = parsed(a), bp = parsed(b);
        if (ap != bp) return ap;
        if (a.errors() != b.errors()) return a.errors() < b.errors();
        // Structurally tied candidates: prefer the one whose method bodies recompile to the original bytecode.
        // Only compared when both were measured, so an unmeasured candidate is never favored or penalized here.
        if (a.roundTripMeasured() && b.roundTripMeasured()) {
            int ar = recovered(a), br = recovered(b);
            if (ar != br) return ar > br;
            if (a.roundTripDifferent() != b.roundTripDifferent()) return a.roundTripDifferent() < b.roundTripDifferent();
        }
        return a.placeholderSignals() < b.placeholderSignals();
    }
    private static int recovered(VerificationResult value) { return value.roundTripIdentical() + value.roundTripEquivalent(); }
    /**
     * Phase 2DG: two candidates selection cannot tell apart. Both stopped while parsing with javac's error cap reached, so
     * their error counts say nothing and no class compiled for signatures; only placeholder signals would decide. On
     * kotlin-stdlib 2.4.20 all four engine trees ended there, and the tree picked that way decided most of the files.
     */
    static boolean blindTie(VerificationResult a, VerificationResult b) {
        return blind(a) && blind(b) && a.matchedSymbols() == b.matchedSymbols() && a.fullyCoveredClasses() == b.fullyCoveredClasses()
            && a.averageClassCoveragePermille() == b.averageClassCoveragePermille() && a.errors() == b.errors();
    }
    private static boolean blind(VerificationResult value) {
        return value.status() != VerificationResult.Status.COMPILED && value.syntaxErrors() > 0 && value.errors() >= JAVAC_ERROR_CAP;
    }
    private static boolean parsed(VerificationResult value) {
        return value.status() == VerificationResult.Status.COMPILED
            || value.status() == VerificationResult.Status.COMPILE_FAILED && value.syntaxErrors() == 0;
    }
    private static void append(StringBuilder report, Candidate candidate, List<SelectionResult.Attempt> tried) {
        tried.add(new SelectionResult.Attempt(candidate.id, candidate.origins, candidate.verification));
        var v = candidate.verification;
        report.append(candidate.id).append(": ").append(v.status()).append("; signatures=")
            .append(v.matchedSymbols()).append('/').append(v.expectedSymbols()).append("; errors=")
            .append(v.errors()).append("; syntax-errors=").append(v.syntaxErrors()).append("; files-with-errors=")
            .append(v.fileErrors().size()).append("; fully-covered-classes=").append(v.fullyCoveredClasses()).append('/')
            .append(v.classCoverage().size()).append("; average-class-coverage=").append(v.averageClassCoveragePermille())
            .append("/1000; placeholder-signals=").append(v.placeholderSignals());
        if (v.roundTripMeasured()) report.append("; round-trip-recovered=").append(recovered(v)).append('/')
            .append(recovered(v) + v.roundTripDifferent());
        report.append('\n');
    }
    /** javac's {@code -Xmaxerrs} in {@link CompilerWorker}: a compilation with this many errors stopped reporting. */
    static final int JAVAC_ERROR_CAP = 1000;
    /** Ranked above every count javac reported: the file was never reached. */
    private static final long UNREPORTED = 100_000;
    /**
     * Phase 2BY: a file's errors in an engine's own compilation. A file javac did not report is clean only when the
     * compilation reported all its errors; once it stopped at the cap, the file is unknown and ranks after every reported
     * count. Example: Vineflower's tree stops at 1000 parse errors in 23 files, so its version of a class Procyon left empty
     * read as "0 errors" that javac never checked.
     */
    static long fileErrors(VerificationResult result, String file) {
        Integer errors = result.fileErrors().get(file);
        if (errors != null) return errors;
        return result.errors() >= JAVAC_ERROR_CAP ? UNREPORTED : 0;
    }
    private static final java.util.regex.Pattern TYPE_DECLARATION =
        java.util.regex.Pattern.compile("\\b(class|interface|enum|record)\\s+[\\p{L}_$][\\p{L}\\p{N}_$]*");
    /** Phase 2CL: whether any file of an engine tree declares a local with JADX's placeholder type {@code ??}. */
    private static boolean placeholders(Map<String,Path> files) throws IOException {
        for (var file : files.values()) if (PlaceholderTypes.present(Files.readString(file))) return true;
        return false;
    }
    /** Phase 2BY: whether a source file declares a class, interface, enum or record outside comments and literals. */
    static boolean declaresType(Path file) throws IOException {
        return Files.size(file) > 0 && TYPE_DECLARATION.matcher(SourceText.mask(Files.readString(file))).find();
    }
    private static TreeMap<String,Path> inventory(Path source) throws IOException {
        var files = new TreeMap<String,Path>(); int count = 0; long bytes = 0;
        try (var paths = Files.walk(source)) {
            var iterator = paths.iterator();
            while (iterator.hasNext()) {
                Path path = iterator.next();
                if (++count > 20_001 || Files.isSymbolicLink(path)) throw new IOException("Unsafe source tree");
                if (Files.isRegularFile(path) && path.toString().endsWith(".java")) {
                    long size = Files.size(path); bytes += size;
                    if (size > 16L * 1024 * 1024 || bytes > 128L * 1024 * 1024) throw new IOException("Source budget exceeded");
                    files.put(source.relativize(path).toString().replace('\\', '/'), path);
                }
            }
        }
        return files;
    }
    /** Copies a mix and folds its enum constant bodies (Phase 2BX), so every attempt is measured as it is selected. */
    private List<EnumBodyFolding.Fold> copy(Map<String,Path> files, Path destination) throws IOException {
        for (var file : files.entrySet()) {
            Path path = destination.resolve(file.getKey()).normalize();
            if (!path.startsWith(destination)) throw new IOException("Source path escapes destination");
            Files.createDirectories(path.getParent());
            Files.copy(file.getValue(), path);
        }
        return folds(files) ? folding.apply(destination) : List.of();
    }
}
