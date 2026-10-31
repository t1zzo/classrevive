# Architecture

ClassRevive turns compiled Java (`.class`, `.jar`, Android APK/dex, executables with an embedded jar) into readable source and measures how much of it is right. It runs several decompilers, recompiles their output, compares the result with the original bytecode, and keeps, file by file and method by method, what the evidence supports. This page describes how that is built. Commands and options are in the [command-line reference](docs/cli-reference.md); report fields are in [docs/reports.md](docs/reports.md).

## Principles

- **The target is data.** ClassRevive never initializes a target class, never invokes a target method or constructor and never executes a target program. Bytecode is read with ASM. The only place target-derived classes enter a JVM is the link check: after bytecode is rewritten (an obfuscator reversal, a whole-jar pass, a failure-lab reduction), a worker JVM defines the classes in an isolated class loader and links them so the JVM bytecode verifier checks them, without initializing them. A few passes evaluate pure constant code of the target (string and number decoders of known obfuscators, integer folds) in a bounded static interpreter written for that purpose, with an explicit instruction allowlist and step and memory budgets; the interpreter does not define classes or call target methods.
- **Fail closed.** Inputs that cannot be handled safely (unsafe archive names, identity/path mismatches, signed jars for rewriting passes, unsupported class versions) are refused with a diagnostic instead of being guessed at. Output folders must be new; nothing is overwritten.
- **Evidence over claims.** Every result says what was measured: recompilation, structural signatures, per-method round trip. Behavior is always `NOT_VERIFIED`. A compiled candidate is not called equivalent to the original.
- **Bounded everything.** Input size, expanded size, entry counts, worker time, heap, log and output sizes, search attempts and interpreter steps all have limits, and hitting one is reported.

## Modules

The build has 17 Gradle modules under `modules/`. Arrows point to what a module depends on.

| Module | Role |
|---|---|
| `model` | Immutable inventory types: `ClassInfo`, `MemberInfo`, `Inventory`, `Diagnostic`, `Limits` |
| `bytecode` | ASM-based readers: class inspection, readable bytecode fallback, dynamic-linkage scan, obfuscation profile and classifier, compiler fingerprint, reference index and call hierarchy, stub synthesis, class reduction, a JVM link check worker |
| `intake` | Bounded archive and class reading (`InventoryReader`), multi-release handling → `model`, `bytecode` |
| `engine-spi` | The decompiler engine interface, `ProcessRunner` (worker JVMs) and worker concurrency limits |
| `engine-cfr`, `engine-vineflower`, `engine-procyon`, `engine-jadx` | One adapter per decompiler, each run in its own worker JVM → `engine-spi` |
| `dex` | Dalvik conversion through dex2jar in a worker, and normalization of the converted classes → `engine-spi` |
| `transforms` | Bytecode passes (integer and string folds, static string decoder, ZKM flags), class-set renaming and mapping import, and the Skidfuscator and Aidsfuscator reversals (`transforms/skid`) → `engine-spi` |
| `verification` | javac workers, structural signature coverage, per-method round trip, source repairs, method splicing, placeholder typing → `bytecode`, `intake` |
| `selection` | Candidate ranking, hybrids, repairs, swaps and composition (`SourceSelector`) → `verification` |
| `sandbox` | Probe, policy and comparison model for future behavioral verification; no target execution |
| `pipeline` | The jobs: decompile, diagnose, transform, rename, benchmark, failure lab, reference comparison; input identification, native triage, reports and project export → everything above |
| `workbench` | Application layer shared by the front ends: decompile service, engine registry, result index, obfuscation check, reversal, native triage → `pipeline` |
| `gui` | Desktop application (Swing, FlatLaf, RSyntaxTextArea) → `workbench` |
| `cli` | The `classrevive` command line and the distribution (`installDist`, `distZip`, launchers `classrevive` and `classrevive-gui`) → `pipeline`, `workbench`, `gui` |

## Worker isolation

Everything that runs third-party or heavy code on target-derived data runs in a separate JVM started by `ProcessRunner`: each decompiler, javac, the transform and rename workers, the dex2jar conversion, the bytecode fallback, the diagnostics scan and the link check. A worker gets:

- the running JDK's `java`, an explicit class path, and an environment with `JAVA_TOOL_OPTIONS`, `_JAVA_OPTIONS` and `JDK_JAVA_OPTIONS` removed, so injected JVM flags are not inherited;
- a wall-clock timeout, a heap and metaspace limit (decompiler heaps are sized by the input, up to 1 GiB), a retained-log limit and an output budget measured while it runs;
- a fixed protocol: inputs and outputs are files in the job folder, read back strictly by the parent.

Worker JVMs are fault isolation, **not an operating-system sandbox**. For deliberately hostile files, run ClassRevive in an isolated VM.

dex2jar runs with its own class path (`lib/dex2jar`), apart from the application's, because it bundles its own copy of ASM and Android's `dx`.

## The decompile flow

1. **Intake.** The input is read once under the intake limits (256 MiB input, 16 MiB per expanded entry, 512 MiB expanded in total, 20,000 entries). Entries are never extracted or executed; nested jars are resources. An input that is not a class or jar is identified from its bytes: an executable with an embedded jar, a bundle of jars, an Android APK or dex, or something without Java to take, which is reported and refused.
2. **Normalization.** Android inputs are converted to class files by dex2jar and normalized for Java decompilers. Class names that collide by letter case or are no Java identifiers, and private members with non-identifier names, get portable names in the rename worker, with the mapping kept. Multi-release jars are decompiled at their base version. The result is `classes.jar`, class files only.
3. **Diagnosis and reversal.** The static obfuscation profile is built from the original classes ([docs/obfuscation-detection.md](docs/obfuscation-detection.md)). A Skidfuscator or Aidsfuscator diagnosis with `HIGH` confidence runs that reversal first; ZKM flag accessors trigger the flag fold. Reversed classes must pass the JVM verifier in a fresh JVM (linked, never initialized) or the originals are kept. From here on everything is measured against the classes the engines read.
4. **Engines.** CFR, Vineflower, Procyon and JADX (any subset) run concurrently, two to four at a time depending on the processor count. For Android inputs JADX may read the original dex files instead. Engine outputs can be cached by input and engine bytes.
5. **Verification.** Each engine tree is compiled by javac in a worker with `-proc:none`, `-implicit:none`, an always-empty source path and either an empty class path or the explicit `--classpath` jars (normalized, base versions only). Original classes are never on that class path, so they cannot hide missing sources. Types nobody supplies get inferred stand-ins on the compiler's class path only, and such results are marked stub-backed. JADX's placeholder types are typed from javac's attribution before compiling. Structural signatures (class hierarchy, relevant modifiers, field and method names and descriptors) are compared with the original, and every recompiled method body is compared with the original bytecode after normalizations for javac variations and decompiler rewritings that keep JVM semantics.
6. **Selection.** See below.
7. **Fallback and reports.** When recovery is partial, an ASM dump of the original instructions is kept. `report.txt`, `report.json` (schema v1) and a self-contained `report.html` record inputs and their hashes, engine outcomes, every attempt and the provenance of each selected file and method; `project/` is an IDE project of the result.

## Selection and composition

Selection is a bounded, deterministic search, not an exhaustive optimum.

- **Ranking.** Candidates are ordered by complete compilation and signature coverage, compilation status, fully covered classes, average per-class coverage (each class weighs the same), matched signatures, whether javac got past parsing, error count, then the per-method round trip, then placeholder signals. Ties keep the first engine in lexical order.
- **Hybrids.** When no engine tree passes, up to eight hybrids replace failing files with another engine's version of the same file, guided by javac's per-file errors. Files holding a nested-class family move together, and a family is never mixed between nested and top-level forms.
- **Fill.** Classes an engine left empty or missing are filled in from the engine whose version of that file has the fewest errors.
- **Source repairs.** Up to three rounds of compiler-guided edits inside method bodies (casts, type arguments, `final` locals, renamed locals, guarded initializers, boolean literals, unreachable jumps). Declarations are never edited, and an edit is kept only when its file's errors drop and no class loses a signature. Files that still fail are swapped for other engines' versions.
- **Per-method composition.** A method body that does not compile, or whose round trip is `DIFFERENT`, can be replaced by the same method from another engine. The replacement is kept only when its file compiles alone against the original classes, every name in the copied body resolves to the same declaration, and the method's round trip becomes identical or equivalent with no other method of the file getting worse. Imports, fields and declarations are never copied. This is the one place where the original classes are on javac's class path, in memory and only to measure; the selection verdict always comes from a normal attempt without them.

`selection/selection.txt` and `report.json` list every attempt, edit, replacement and refusal.

## Transforms

`transform` and `rename` are separate commands that write a new jar plus audit evidence; `decompile` applies some of them by itself as described above.

- **Pass pipeline.** Built-in passes are registered explicitly and run in a fixed order inside a worker, with change and time budgets ([docs/pass-sdk.md](docs/pass-sdk.md)). Folds never cross labels, frames or debug boundaries; a folded branch removes dead code only up to the next stack map frame, so the output passes the JVM verifier.
- **Static string decoder.** Evaluates same-class static `(String)String` decoders with an allowlist of instructions and JDK calls, step, allocation and output budgets. Unsupported shapes are left unchanged. Decoded values never appear in the audit.
- **Obfuscator reversals.** The Skidfuscator and Aidsfuscator reversals are whole-jar passes: they recover the keys and seeds these tools compute, fold their opaque predicates and flattening, decrypt strings and numbers with a bounded interpreter of the tool's own decryptors, and remove the generated helper classes. They were written by reading the bytecode these tools emit; no code of the tools is copied. Frames are recomputed with a class hierarchy built from the input itself; only JDK classes are looked up, through the platform class loader, never a class of the input.
- **Renaming.** Deterministic aliases, explicit mapping files or an imported ProGuard/R8 mapping, applied to class sets, private members, manifests, `META-INF/services` and literal reflection calls, with forward and inverse maps in `rename.json`. Only what can be applied safely is applied; every skip is counted with its reason.

## Diagnostics and native code

`diagnose` reports static invokedynamic, reflection, class-loading and crypto references (clues, never verdicts), the obfuscation profile and the compiler fingerprint. With `--native`, bundled native libraries are extracted as data and triaged: ClassRevive reads their headers itself, and an external Ghidra the user installed can analyze them headless in its own process. Native code is never loaded or run.

## Desktop application

The `gui` module is a Swing application with FlatLaf and RSyntaxTextArea. It calls the pipeline only through `workbench`, the same layer the CLI uses for decompiling, so both front ends write the same result folders. Long work runs off the event thread with progress and cancellation. The explorer shows the inventory and bytecode before any decompile; after one, each class carries its confidence level, each method its round-trip mark, and javac errors are underlined. Find usages and the call hierarchy come from an index of the bytecode built in the background with ASM. Results live under a workspace folder (`%APPDATA%\ClassRevive\workspaces` on Windows, `~/.classrevive/workspaces` elsewhere) and can be reopened. The interface is translated into English and Portuguese (`messages*.properties`), and the distribution's `docs/guia-iniciante.md` is opened from the Help menu.

## Measuring

- `./gradlew test` runs unit and integration tests on fixtures the tests generate, plus the committed regression cases in `benchmarks/regressions/`.
- `benchmark` measures any corpus per engine and combined; `./gradlew :cli:scoreboard` runs it on real libraries from Maven Central, hash-checked by `gradle/verification-metadata.xml` (`dev` is measured while developing; no rule was tuned on the `holdout` and `holdout2` sets).
- The failure lab (`lab`, `lab-minimize`, `lab-promote`, `lab-check`) captures each failure with a signature that names no target content, minimizes it with ASM under the JVM verifier, and promotes license-compatible cases to permanent regressions.
- `compare-reference` scores a decompile of an obfuscated jar against the original jar, for answer-key corpora such as `benchmarks/obfuscated/`.

## Known limits

- javac runs on JDK 21, so recompilation covers Java 1.1 to 21 class files; newer ones are reported as `UNSUPPORTED_RELEASE`. ASM parses class files through Java 28 for everything else.
- Dependencies are never downloaded; without the real libraries, external types are stand-ins and signatures are checked against guesses.
- Generic signatures, annotations and constant values are not compared, and method-body behavior is not verified.
- Placeholder detection is a text heuristic over the stub text the engines emit.
- Reflection through variables, names renamed by an obfuscator without a mapping, and code generated at run time are out of reach of static analysis.
