# Changelog

All notable changes to ClassRevive are listed here. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versions follow [Semantic Versioning](https://semver.org/). Before 1.0.0, minor versions may change command options and report fields; report schema changes stay additive within `schema_version` 1.

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
