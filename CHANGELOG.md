# Changelog

All notable changes to ClassRevive are listed here. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versions follow [Semantic Versioning](https://semver.org/). Before 1.0.0, minor versions may change command options and report fields; report schema changes stay additive within `schema_version` 1.

## [Unreleased]

### Detection

- New rules for native-obfuscator (radioegor146) and its forks such as native-obfuscator-plus, j2cc, BytecodeVM and the alpheratz obfuscator, from shapes that survive their configurable names: native-obfuscator's `registerNativesForClass(int, Class)` loader and registering initializers; j2cc's `bootstrap`/`initClass`/`doInit` loader, `j2cc/natives.bin` resources and `j2$mh` method-handle calls; BytecodeVM's `execute(code id, receiver, Object[] args)` stubs and `$CodePool`; alpheratz's table-keyed numbers and booleans, goto traps, trash predicates and trash `throws`. The generic native-layer hypothesis is no longer reported beside a recognized transpiler. See `docs/obfuscation-detection.md`.
- Branchlock 4 (loerting/branchlock_obf): its watermark and demo markers, leftover annotations, its hash-keyed reference lookup class, its per-class string tables decrypted in `<clinit>` with a stack-trace check, and its crasher classes.
- BlackObfuscator (CodingGay/BlackObfuscator): methods flattened behind `switch (state.hashCode() ^ k)` dispatchers.
- Ambien (iiiiiiiris/Ambien): its archive comment, red herring class, string decryptor, reference hider and crashers. The detector now also reads the archive comment and directory entries (a class hidden as `X.class/`).

### Deobfuscation

- BlackObfuscator (Android dex control-flow flattening): `decompile` applies the control-flow pass on a HIGH diagnosis. Its dispatchers, `switch (s.hashCode() ^ k)` on a state string kept in locals, are now threaded: a constant string's `hashCode()` and `length()` count as side-effect free, and a store inside a dispatcher is skipped when only the dispatcher reads that local and nothing jumps into its middle.
- Zelix KlassMaster 27 for Java 7 and later targets: the `zkm` pass now also reverses the `invokedynamic` shape, and `decompile` applies it when the diagnosis names `Zelix KlassMaster` with HIGH confidence. Each class's key comes from the obfuscator's own key generator, interpreted statically by a new bounded interpreter (`ZkmRuntime`: objects of jar classes, modelled JDK collections, DES on constants, super classes initialized first as the JVM does). String and number sites become their values, and the bootstraps, resolvers, decryptors and decrypting initializers go. A class whose key fails DES's padding check anywhere is left as it is. Classes whose initializer is native code keep their sites. See `docs/zelix-klassmaster.md`.
- Exception obfuscation: `catch (RuntimeException e) { throw a(e); }` through a helper returning its argument loses the helper, then the handler that only rethrows (also in javac's `astore; aload; athrow` shape).
- `decompile`'s own reversal accepts archives whose class names differ only in letter case; they are made portable right after it, as before.

### Engines and selection

- Inferred stand-ins for missing libraries never give a type a supertype that reaches one of its own member types. Converted Android code could lead there (`Drawable` placed below `AdaptiveIconDrawable`, which implements `Drawable.Callback`), and javac's "cyclic inheritance" then stopped every file naming the type, so the per-file round trip of a large APK compiled nothing.
- Hybrid selection no longer keeps a replacement that fixes its own file but breaks the files that use it (a decompiler declaring a `throws` clause the bytecode does not, so every caller fails): when both mixes parse, the errors that grew in other files are charged to the replacements whose class they name (by simple name in the same package, by qualified name from another), and a replacement whose charge outweighs what it fixed is reverted. javac now reports up to 100,000 errors per compilation instead of 1,000, so per-file counts on large inputs are no longer cut short; the compiler log still prints the first 1,000.

## [0.3.0] - 2026-11-11

### Deobfuscation

- Zelix KlassMaster 27 support: detection and a static reversal pass (`zkm`), applied automatically by `decompile` on a HIGH diagnosis. Method keys are propagated across the jar, strings and numbers decrypted, reflective accesses written back as direct instructions, parameters packed in `Object[]` restored without their keys, opaque flags folded under a proof and decrypting initializers removed. Protected methods nothing calls have no key in the jar and stay as they are. See `docs/zelix-klassmaster.md`.

### Engines and selection

- A new source repair puts back the cast a decompiler drops after a raw `toArray(T[])`.

## [0.2.0] - 2026-11-10

### Deobfuscation

- General control-flow simplification (`transform --passes control-flow`), independent of any obfuscator: opaque predicates proved constant (constants, proved `static final` fields, intervals, parity, value numbers, pure calls), dispatcher unflattening, throw-to-goto rewriting, dead and unreachable code removal. Every change is audited and the JVM verifier links the result. See `docs/control-flow.md`.
- Radon 2 support: detection rules and a static reversal pass (`radon`) for its number and string encryption, `invokedynamic` references and flow shapes, applied automatically by `decompile` on a HIGH diagnosis. Measured on a synthetic jar that reproduces Radon's documented shapes; runs of the real tool on the corpus are still pending. See `docs/radon-yguard.md`.
- yGuard support: detection of its renaming, and `rename --yguard-log <yguardlog.xml[.gz]>` restores original names from its log where that is safe.

### Renaming

- Inferred names (`decompile --infer-names off|high|medium|low`, off by default): readable names for obfuscated classes, private fields, private methods and locals, guessed from bytecode evidence (`toString` and message labels, parameter names, property keys, types, getters and setters). Every name carries its rule, evidence and confidence and is listed in `inferred-names/`. See `docs/inferred-names.md`.

### Desktop application

- A Deobfuscation panel in the result overview: what was identified, what was reversed (with counts), what could not be recovered, and warnings, read from the reports.
- Tidier overview, inspector and activity panels: clearer hierarchy, spacing, warnings and per-step progress.

### Engines and selection

- Inferred stand-ins for missing libraries are typed from the original bytecode: supertypes between missing types and to JDK types (from values passed, stored, returned or cast where another type is expected), element types of collections, `Iterable` for types used in for-each loops, `throws` clauses of overridden methods, nested annotation kinds and functional interfaces used as lambda targets. Uncertain choices are reported. On a mod jar decompiled without its game libraries, whole-tree javac errors dropped from over 1,000 to 190 and per-file recovered method bodies rose from 5,274 to 8,576.

## [0.1.0] - 2026-10-31

First public release. Requires a Java 21 JDK.

ClassRevive is released under its own permissive license, the ClassRevive License 1.0 (`LICENSE`): commercial use, modification and redistribution with credit; the name and visual identity stay with the project. Bundled libraries keep their own licenses (`third-party-licenses/`).

### Engines and selection

- CFR 0.152, Vineflower 1.11.1, Procyon 0.6.0 and JADX 1.5.3, each in its own worker JVM with time, heap, log and output budgets and stable outcome codes; `--engines` picks any subset, and engine outputs can be cached (`--engine-cache`).
- Every candidate is recompiled by javac in a worker and measured: structural signature coverage per class, and a per-method round trip of each recompiled body against the original bytecode, with normalizations for javac variations and decompiler rewritings that keep JVM semantics.
- Deterministic ranking, bounded hybrids guided by javac's per-file errors, filling of classes an engine left empty or missing, compiler-guided source repairs, swaps after repair, and per-method composition that takes a body from another engine only when it compiles and its round trip is identical or equivalent.
- Inferred stand-ins for types no input or `--classpath` jar supplies, on the compiler's class path only; such results are marked stub-backed.
- Readable ASM fallback of the original instructions when recovery is partial.
- Compiler idioms undone in the selected sources, guided by the original bytecode and kept only when the file still compiles and its round trip does not get worse: enum switches lowered to switch maps or `ordinal()`, synthetic `access$` accessors and similar lowered forms.

### Inputs

- `.class` and `.jar` inputs, multi-release jars (decompiled at their base version, variants listed), several inputs or a folder per run.
- Portable names for classes that collide by letter case or are no Java identifiers, and for private members with non-identifier names, applied before the engines with the mapping kept.
- `identify`: executables, installers, native images and archives recognized from their bytes; a jar embedded in an executable, or the main jar of a bundle of jars, is extracted as data and decompiled.
- Android APK and dex inputs converted by dex2jar in a worker and normalized for Java decompilers; JADX can read the original dex; a local Android SDK is used when present; `AndroidManifest.xml` decoded and summarized.

### Deobfuscation

- Static obfuscation profile and obfuscator hypotheses with scores and reasons (Zelix KlassMaster, Allatori, Stringer, DashO, ProGuard/R8, native layers, Skidfuscator, Aidsfuscator), documented in `docs/obfuscation-detection.md`.
- Skidfuscator and Aidsfuscator reversals as whole-jar passes, checked by the JVM verifier without initializing classes; `decompile` applies them automatically on a `HIGH` diagnosis (`--deobfuscate auto|off`).
- ZKM opaque-predicate flags proved constant over the whole jar are folded before the engines.
- Opt-in bounded passes: integer constant, unary and branch folding, adjacent string operations, and a static decoder for pure `(String)String` methods.

### Renaming

- `rename`: deterministic aliases, explicit mapping files or an imported ProGuard/R8 mapping, applied to class sets, private members, manifests, `META-INF/services` and literal reflection calls, with forward and inverse maps and per-reason skip evidence.

### Diagnostics

- `diagnose`: static invokedynamic, reflection, class-loading and crypto references as clues, compiler fingerprint (javac with a version lower bound, ecj, Kotlin, Scala, Groovy) and embedded library recognition.
- Optional `--native` triage of bundled native libraries, with an external Ghidra when the user has one.

### Reports

- `report.txt`, `report.json` (schema v1), a self-contained `report.html` confidence map per class, and an IDE project export (`project/`).
- `compare-reference` scores a decompile against an original jar, for answer-key corpora.

### Desktop application

- `classrevive-gui` (or `classrevive gui`): open or drop a file, browse packages, classes, resources and bytecode, decompile with progress and cancellation, read the selected source or any engine's version, per-class confidence and per-method round-trip marks, javac errors underlined, side-by-side engine comparison, obfuscator banner with reversal, find usages and call hierarchy, go to class, code search, export as a project or a zip, dark and light themes, English and Portuguese.

### Measurement

- `benchmark` with per-engine and combined variants, separate compilation, structural and round-trip metrics, and per-file partial credit reported apart.
- Scoreboard on hash-checked Maven Central libraries (`./gradlew :cli:scoreboard`, sets `dev`, `holdout`, `holdout2`).
- Failure lab: capture, ASM minimization under the JVM verifier, promotion to permanent regression cases checked by `./gradlew test`.

### Documentation

- README in English and Brazilian Portuguese, a getting-started guide in both languages, a command-line reference, a benchmarks page with the measured scoreboard and how to reproduce it, a security policy and a disclaimer on responsible use.

### Platforms

- Builds and tests pass on Linux, macOS and Windows in CI. Paths through system symbolic links (such as macOS temporary folders) are accepted; links inside inputs and outputs are still refused.

### Safety

- Target code is never run: no target class is initialized and no target method is invoked. Rewritten classes are only linked, in a worker JVM, so the JVM verifier can check them. Worker JVMs drop injected JVM options. Worker isolation is fault containment, not an operating-system sandbox.
- Bounded intake (256 MiB input, 16 MiB per entry, 512 MiB expanded, 20,000 entries); dependencies are never downloaded.
