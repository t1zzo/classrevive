# Command-line reference

This page describes every `classrevive` command in detail: inputs, options, outputs, limits and exit codes. For a first run, see [Getting started](getting-started.md); for how the pieces fit together, see [ARCHITECTURE.md](../ARCHITECTURE.md). `classrevive --help` lists the commands and `classrevive help <command>` prints each one's options, defaults and examples.

## Build from source

Building needs a Java 21 JDK; Gradle comes with the wrapper. Dependencies are downloaded from Maven Central and checked against the SHA-256 hashes in `gradle/verification-metadata.xml`. Windows, Linux and macOS are intended platforms; day-to-day development and verification happen on Windows. A release zip needs no build: its launchers are `bin/classrevive` and `bin/classrevive-gui` (`.bat` on Windows), and the commands below work the same with them.

PowerShell:

```powershell
$env:GRADLE_USER_HOME = "$PWD\.gradle-user"
.\gradlew.bat clean build :cli:installDist
.\modules\cli\build\install\classrevive\bin\classrevive.bat inspect "C:\path\sample.jar"
```

CMD:

```bat
set GRADLE_USER_HOME=%CD%\.gradle-user
gradlew.bat clean build :cli:installDist
modules\cli\build\install\classrevive\bin\classrevive.bat inspect "C:\path\sample.class"
```

Linux/macOS:

```sh
GRADLE_USER_HOME="$PWD/.gradle-user" ./gradlew clean build :cli:installDist
modules/cli/build/install/classrevive/bin/classrevive inspect /path/sample.jar
```

## Inspect

`classrevive --help` prints usage. The `inspect` command reports input/entry SHA-256, expanded size, class names, bytecode versions, and field/method counts. For an unsupported class-file version it reports the detected major/minor, the conventional Java release mapping, and the supported range (major 45..72, Java 1.1..28 for the pinned ASM parser). Detailed member descriptors are exposed through the Java model; the CLI shows counts. It never loads target classes. Exit codes: `0` complete inventory without diagnostics, `1` input/inspection failure or partial result, `2` invalid command/path syntax.

## Decompile

From a source build, the launcher is under `modules/cli/build/install/classrevive/bin/`:

```powershell
.\modules\cli\build\install\classrevive\bin\classrevive.bat decompile "C:\path\sample.jar" --output "C:\path\new-result"
```

Optional: `--timeout-seconds 120` (1..3600). The output parent must exist; the result directory must NOT already exist. No existing result is overwritten. Paths with spaces are supported.

`classrevive --help` lists the commands and `classrevive help <command>` shows each one's options, defaults and examples. Short forms: `-o` (`--output`), `-e` (`--engines`), `-t` (`--timeout-seconds`), `-cp` (`--classpath`). Without `--output`, `decompile app.jar` writes a new `app-classrevive` folder in the current folder (`diagnose`: `app-diagnosis`). Several files or a folder decompile in one run, each into its own new folder: `classrevive decompile plugins/ -o results`.

Use `classrevive engines` to list pinned versions. Decompilation defaults to CFR/Vineflower; add `--engines all` to try all four, or `--engines procyon,jadx` for a subset. Options can appear in any order after the input. Unknown/duplicate engines and flags fail before creating output. At most two decompiler workers run concurrently.

```bat
classrevive decompile sample.jar --engines all --output recovered
classrevive engines
```

```text
new-result/
  input.jar              # original snapshot (input.class for a standalone class)
  classes.jar            # class-only normalized input; resources omitted
  cfr/sources/           # CFR 0.152 source alternative
  cfr/engine.log         # first 1 MiB of engine output
  vineflower/sources/    # Vineflower 1.11.1 source alternative
  vineflower/engine.log
  report.txt             # engine versions, input hash, statuses and source counts
  report.json            # schema v1 evidence, engine outcomes, class coverage and selected-file provenance
  selection/sources/     # selected candidate; may still be partial
  selection/selection.txt # compilation, signature coverage and per-file provenance
  selection/checks/      # compiler logs, results and compiled classes per attempt
  fallback/bytecode/     # original instructions when selection is incomplete
```

For `decompile`, exit 0 now means the selected source set compiled, all expected structural signatures matched, and no configured placeholder text signals were found. It does **not** establish behavioral equivalence or restoration of the original source. Exit 1 indicates a processing failure, unsupported verification or partial result; alternatives and bytecode fallback are retained when available. Exit 2 indicates usage errors. Interrupted engine orchestration returns 130; interruption during later verification may report a partial/failure result. Abrupt terminal closure is OS-dependent.

## Executables, bundles and Android inputs

### Executables and other inputs

An input not named `.class` or `.jar` is identified from its bytes instead of being rejected: `classrevive identify <file> [--output <new-directory>]` says what it is (PE x86/x64/ARM console/GUI/DLL, .NET, ELF, Mach-O, zip/jar/war/jmod, class file, NSIS/Inno Setup/MSI installers, GraalVM native-image), lists sections with entropy, the overlay, the Authenticode signature apart from appended data, protector section names (Oreans Code Virtualizer `.vlizer`, Themida/WinLicense, VMProtect, UPX, Enigma), process-injection and JNI/JVMTI names, and gives a verdict. A jar inside an executable (Launch4j, exe4j, JSmooth, a jar concatenated to a stub or script) is found only when its end record, central directory and local headers agree, never by a bare `PK`; `decompile` and `diagnose` then extract it as data to `input.jar` and continue as usual, recording the origin in `input-origin/identification.txt`, `report.txt` and `input_origin` in `report.json`/`diagnostics.json`. Without Java to take (a protected native loader, a .NET assembly, a native-image, an installer), they print the identification and exit `1` without creating the output folder; `inspect` prints it and exits `1` (no class inventory). `identify` exits `0` when the file was read, `1` when it could not be read or the output exists, `2` for bad arguments. Nothing is run, unpacked or decrypted.

**Bundles of jars.** A valid zip whose classes are all in nested `.jar` entries (a launcher's payload inside an `.exe` section or overlay, or a plain zip of jars) is `JAVA_BUNDLE`: `identify` lists every nested jar with its classes, size, `Main-Class`, recognized library and library folder, read in memory under the intake limits (depth 1). `decompile` and `diagnose` take the jar with the most classes that is neither under `lib/`/`libs/` nor a recognized library, or the one named with `--entry <name>`; `decompile` gives the bundle's other jars with classes to the verifier as `--classpath` automatically (variants of the main jar, holding the same classes under other mappings, are left off). `identify --output` also writes the nested jars to `bundle/` as data.

### Android APK and dex

`classrevive decompile app.apk` (or a `.dex`) converts the Dalvik bytecode of the root `classes.dex`, `classes2.dex`, ... to class files with dex2jar 2.4.38, run in its own JVM worker, then decompiles, recompiles and measures them like any jar. Two Dalvik habits javac never has are undone first, without changing behavior: registers reused for values of another type (each local slot is split into its webs) and fields that lost `ACC_SYNTHETIC` (`this$0`, `$VALUES`, ...). `android.*` classes are not in the APK and get inferred stubs. Results are measured against the converted class files, not against the dex; `input-origin/conversion.txt` lists methods dex2jar could not translate (their bodies throw) and classes dropped as duplicates of an earlier dex. With JADX among the engines, JADX reads the original dex files (kept in `input-origin/dalvik.zip`) instead of the converted classes, unless they had to be renamed or rewritten. When a local Android SDK is found (`ANDROID_HOME`, `ANDROID_SDK_ROOT` or the default folder), javac compiles against its newest `platforms/android-N/android.jar` instead of inferred `android.*` stubs; the SDK is never downloaded. The binary `AndroidManifest.xml` is decoded to `input-origin/AndroidManifest.xml` and summarized in the identification (package, versions, SDK levels, permissions, components, launcher), its `targetSdkVersion` picks the SDK platform, and `lib/<abi>/*.so` libraries are listed and triaged. Local variable names are not carried over. An app bundle (`.aab`) or a split-APK set (`.apks`, `.xapk`) is identified and named, not converted: pass its base APK.

## Diagnose

For static evidence about dynamic linkage, use `classrevive diagnose sample.jar --output diagnosis`. It reports bootstrap sites, known reflection calls, selected class-loading APIs and selected JDK cryptography calls without executing them. Class-loading and crypto references are triage clues only; they do not prove packing, encryption or malware. See [diagnostic coverage and limits](dynamic-diagnostics.md). `diagnose` (and `decompile`, in `report.txt` and `report.json`) also counts obfuscation techniques found statically (string encryption (counted at its invokedynamic sites, with encrypted int/long constants and keys supplied by a native layer), invokedynamic call hiding, short and colliding names, stripped attributes, an obfuscated native layer, control-flow patterns) and suggests which obfuscator they point to, with a score and the reasons; the suggestion is never proof, and `unknown` and `none` are valid answers.

### Native libraries (optional Ghidra triage)

`--native` on `diagnose` or `decompile` triages the native libraries bundled in a jar (`.dll`, `.so`, `.dylib`, `.jnilib`) and links each native function to the Java `native` method it implements. It is static triage, not recovery of native code as Java:

```bat
classrevive diagnose app.jar --output diagnosis --native --ghidra-home C:\Users\me\Downloads\ghidra_12.1.4_PUBLIC
```

- Ghidra is **not** bundled or downloaded. It is found through `--ghidra-home <dir>` or the `GHIDRA_INSTALL_DIR` variable. Without it the command works as before, reports `Native analysis: skipped (Ghidra not configured)` and still gives ClassRevive's own header triage: format, sections with entropy, exports and imports (PE and ELF), packing hints, and links from `Java_*` exports to native methods.
- With Ghidra, each library (host platform first, `--native-max-libraries`, default 4) is analyzed by `support/analyzeHeadless` in its own process, with a throwaway project (`-readOnly -deleteProject`), a timeout (`--native-timeout-seconds`, default 900) and a 1 MiB log. A versioned post script exports imports, exports, `JNI_OnLoad`, `Java_*` functions, `RegisterNatives` call sites (JNIEnv slot 215), `JNINativeMethod` tables, strings and pseudo-C of the JNI functions. Only when that run produces nothing is the library imported again with `-noanalysis`.
- Libraries are extracted as data up to `--native-max-mib` (default 64; the 16 MiB intake limit does not apply here) and are never loaded, run or unpacked. A packed library (Themida, VMProtect, UPX, ...) shows Ghidra mostly its stub; its dynamically registered methods are then reported as such.
- Output: `native/summary.txt`, `native/report.json` and `native/<nn>-<library>/` with `report.json`, `functions.txt`, `strings.txt`, `pseudo-c/*.c` and Ghidra's `engine.log`; a summary is appended to `report.txt` / `obfuscation.txt`, and `native_analysis` is added to `report.json` / `diagnostics.json`.

## Static simplification

Use `--passes integer-unary,integer-constants,integer-branches` for an explicit ordered sweep including integer negation/narrowing and adjacent literal branch folding. The default remains `integer-constants`. See [pass API and ordering contract](pass-sdk.md) for extension rules and audit semantics.

Optional `string-length,string-char-at` passes evaluate adjacent literal String length/character access without invoking target methods. They preserve invalid-index calls and use UTF-16 code-unit semantics. The opt-in `string-decryptor` pass statically interprets a small allowlist of pure single-String decoder methods, including bounded `char[]` loops and `byte[]` loops using `UTF_8`, `US_ASCII`, `ISO_8859_1`, `UTF_16`, `UTF_16BE` or `UTF_16LE`; it never defines or invokes target classes and leaves unsupported patterns unchanged. Runtime charset lookup and arbitrary charset values remain unsupported. When it recognizes that the decoder returned a fresh `String`, it keeps a fresh allocation in the output. It is a conservative pattern, not general or guaranteed decryption.

The `zkm-flags` pass folds Zelix KlassMaster's opaque-predicate flags that it proves constant over the whole JAR (assumed value from the holder's static initializer, then a fixed point over every reachable store), and leaves flags that change at run time alone. `decompile` runs it by itself before the engines when the obfuscation profile finds ZKM flag accessors; the classes as they were stay in `deobfuscation/classes-before-flags.jar`.

The whole-jar `aidsfuscator` pass reverses Aidsfuscator 2.10.0: its salts and hash integrity values are read from the jar (dispatcher table, SHA-256 of the class files), its flow ints and flattening folded, its `invokedynamic` references written back, and its encrypted numbers and strings decrypted by a bounded interpreter of its own decryptors that runs no target class. Names it renamed stay renamed. `diagnose` names Aidsfuscator when its classes are present.

`decompile` applies these reversals by itself: when the obfuscation diagnosis names Skidfuscator or Aidsfuscator with HIGH confidence, the matching pass runs first, in the transform worker, and the JVM verifier links (never initializes) every reversed class. The engines, the selection and the round trip then read the reversed classes, so their signatures and round trip are measured against those, not the original; `report.txt`, `report.json` and `report.html` say so, name the obfuscator and score, the pass totals (`Skidfuscator detected (100/100): reversal applied, 6653 strings decrypted, ...`) and how many of the original's method and field signatures the reversal kept. `deobfuscation/` keeps the original classes (`input.jar`), the reversed ones (`worker/transformed/transformed.jar`) and `transform.json`. A worker failure, a pass with nothing to undo, or a verifier error the reversal introduced keeps the original classes (a class missing from the input only makes the check inconclusive). `--deobfuscate off` turns it off; the report then says which `transform` command applies it.

```bat
classrevive transform protected.jar --output reversed --passes aidsfuscator
classrevive transform sample.jar --output simplified --max-changes 1000 --timeout-seconds 60
classrevive transform sample.jar --output decoded --passes string-decryptor --max-changes 1000 --timeout-seconds 60
classrevive decompile simplified/worker/transformed/transformed.jar --output recovered --engines all
```

`transform` conservatively folds adjacent literal integer arithmetic, bitwise and shift instructions; the opt-in `integer-branches` pass also folds direct constant integer conditions without crossing labels or debug boundaries; for version 50+ classes, a taken fold removes the dead fall-through up to the next stack map frame, or is refused, so the output still passes the JVM verifier. The string decoder supports only same-class static methods taking and returning one `String`, with a narrow integer/branch/`char[]`/bounded byte-array instruction allowlist, six explicit standard charsets, the basic, URL-safe and MIME Base64 decoders, exact `String.substring(int)`/`substring(int,int)` and `String.replace(char,char)`, exact `Integer.rotateLeft`, `rotateRight`, `reverse` and `reverseBytes` calls, exact bounded `Arrays.copyOf`/`copyOfRange` byte-array copies, selected `StringBuilder` default/integer/string construction, append, reverse, character access/edit, insertion, deletion and logical-length changes, bounded same-class static helpers, and `String.valueOf(char[])`; no exception handlers, class initializer, interfaces, or custom superclass are accepted. It refuses unsupported instructions and stops at fixed instruction, helper-depth, 4,096-byte/code-unit, 64 KiB per-candidate allocation, and 4 MiB per-class allocation budgets. These passes do not cross labels, frames or debug boundaries, fold division by zero into a value, execute target code, or remove arbitrary obfuscation. Classes containing unknown attributes remain byte-identical. Apart from the reversals above and ZKM flag folding, which `decompile` reports when it applies them, transformations are opt-in.

The new output directory retains `input.jar` (or `input.class`), `worker/engine.log`, `worker/transformed/transformed.jar`, a binary worker audit and `transform.json`. The JSON records original/output hashes and each changed instruction's operands and result. Resources retain their bytes; ZIP timestamps/compression metadata are not preserved. Signed or digest-bearing JARs are rejected rather than silently invalidating signatures.

Defaults: 1,000 changes and 60 seconds; limits: 1..10,000 changes and 1..3,600 seconds. Hitting the change budget means additional opportunities may remain. Exit 0 means the worker completed and output inventory/audit passed validation, including a valid no-change result. It does not prove behavioral equivalence. Exit codes 1/2/130 mean processing failure/invalid arguments/interruption. Workers have the same process limitations described below; they are not an OS sandbox.

## Rename (experimental)

```bat
classrevive rename sample.jar --output renamed-result
classrevive rename sample.jar --mapping mapping.txt --output renamed-result
classrevive rename app.jar --proguard-mapping mapping.txt --output restored-result
```

Without `--mapping`, the command plans deterministic readable aliases for short simple class names with `ClassRenamer` and rewrites class names and their type references in a separate bounded JVM. With `--mapping <file>`, the plan is taken verbatim from a UTF-8 file (at most 1 MiB, full-line `#` comments) with three rule kinds using dotted or internal names: `class from=to`, `method owner#name[(descriptor)]=newName`, `field owner#name[:descriptor]=newName`; a rule without a descriptor applies to every member with that name and kind. Only private methods and private fields can be renamed; unknown or non-private members, constructors/initializers, record fields, Java serialization members (`serialVersionUID`, `serialPersistentFields`, `writeObject`, `readObject`, `readObjectNoData`, `writeReplace`, `readResolve`, renamed away from or onto those names), invalid target names, overlapping or duplicate rules (descriptor-less rules expand over the target inventory) and post-rename collisions fail closed. Mapping syntax errors exit 2 before the output directory is created; semantic member errors fail the worker (exit 1) with evidence retained. The new output directory retains:

```text
renamed-result/
  input.jar              # original snapshot (input.class for a standalone class)
  mapping.txt            # retained mapping file when --mapping was used
  renamed.jar            # rewritten artifact; resources copied byte-identical
  rename.json            # schema v1 evidence, mapping mode, forward/inverse maps, member evidence, reflection rewrite count and hashes
  worker/                # worker log, rewritten artifact and bounded mapping audit
```

When member rules are present every class is re-serialized, so class byte-identity is not preserved in this mode; aliases and member targets are deterministic names chosen by the planner or the mapping file, not names recovered from the target. Signed or digest-bearing JARs, `module-info`/`package-info` classes, multi-release versions directories, class entries whose archive path differs from their internal name (including uppercase `.CLASS`) and output path collisions are rejected. Standard manifest class attributes (`Main-Class`, `Premain-Class`, `Agent-Class`, `Launcher-Agent-Class`) and `META-INF/services` entry paths/provider lines are rewritten when they reference renamed classes, and unchanged files stay byte-identical; changed manifests are re-serialized with the JDK writer. Literal class names consumed directly by `Class.forName(String)` or `ClassLoader.loadClass(String)` are rewritten; reflection through variables, concatenation, other resources, external callers and nested archives is not followed, and reflection strings are not rewritten for member rules. Behavior is `NOT_VERIFIED` and no target code is executed. Exit 0 means the worker rewrote the archive and the renamed inventory, class set and mapping evidence validated; exit 1 processing failure or worker failure (evidence retained); exit 2 usage errors; 130 interruption.

With `--proguard-mapping <mapping.txt>` (ProGuard or R8 format, at most 32 MiB), the command restores original names recorded in that file. It converts only what the renamer can apply safely: same-package class renames and private, non-serialization members whose descriptor (computed from the mapping's original types) exists in the input. Cross-package moves, non-private members (which need hierarchy-aware renaming), members missing from the input (for example, after R8 changed a signature), record fields, nested-class incoherence, output collisions and keys claimed by more than one original name (such as unqualified same-class inline frames) are skipped. `rename.json` lists each skip reason with a count and up to 200 samples; the original file is kept as `proguard-mapping.txt` and the converted rules as `mapping.txt`. Restored names are only as trustworthy as the supplied mapping. A mapping with no applicable rule fails with evidence retained.

## Selection evidence and limits

For projects with external libraries, supply local JARs explicitly (Windows uses `;`, Linux/macOS use `:`):

```bat
classrevive decompile target.jar --output recovered --classpath "libs/api.jar;libs/util.jar"
```

Compile-only annotations count as dependencies too: Gson 2.13.1 sources, for example, do not compile without `error_prone_annotations-2.38.0.jar`. The selected attempt's `selection/checks/attempt-N/engine.log` shows each javac diagnostic and `file-errors.txt` the error count per source file.

Types that neither the input, the `--classpath` JARs nor the JDK supply get inferred stand-ins on the compiler's class path only (`--stubs false` turns this off). A candidate that compiled with them is still reported complete, with the same status and exit code, but is marked **stub-backed**: `stub_backed` in `report.json`'s `verification` and in each benchmark row (with the row's own `missing_type_stubs`), a `Stub-backed: yes` line in `report.txt`, a warning in `report.html`, and the bytecode fallback is kept. Its signatures were checked against guesses, not the real library; supply the real JARs for a faithful check.

At most 16 JARs, 128 MiB compressed and 512 MiB expanded in aggregate per verification attempt. Dependency snapshots and SHA-256 evidence are retained next to each compiler attempt as `attempt-N-dependencies/`. Normalized class-only JARs exclude manifest `Class-Path`, source files and annotation-processor services. Multi-release dependencies contribute their base classes only: valid `META-INF/versions/N/` variants (N of at least 9, path equal to the class name) are dropped and counted in `dependencies.txt`. Duplicate classes, overlap with original target classes, and other path/identity mismatches are rejected. No downloads, processor execution or implicit source compilation occur. Each candidate attempt prepares its own bounded snapshots, so a multi-engine/hybrid job may consume several times the per-attempt dependency storage. Keep dependency files unchanged while a job runs. `benchmark` takes dependencies per sample instead, from a sibling `<sample>.deps/` folder (below).

The compiler runs in a separate JVM with `-proc:none`, `-implicit:none`, an empty classpath by default and an always-empty source path. Original classes are never put on the compiler classpath, so they cannot hide missing generated sources. The one exception is the per-method composition worker, which compiles one file at a time against the original classes, in memory, only to measure that file's round trip; the selection verdict always comes from a normal attempt without them. Original programs and compiled outputs are never executed. JDK 21 verifies class files up to Java 21: Java 1.1..7 inputs are compiled with `--release 8` (the lowest javac 21 accepts) and `module-info.java` is not recompiled; preview and newer releases are explicitly unsupported. Explicit local dependency JARs are supported for verification through --classpath; module paths and automatic dependency resolution are not supported. Unresolved symbols remain in compiler diagnostics, without being mislabeled as obfuscation.

Candidates are ranked deterministically by complete compilation/signature coverage, compilation status, count of fully covered classes, equal-weight average per-class coverage, matched signatures, whether javac got past parsing, diagnostic error count, then (when both candidates have it) the per-method round trip: more bodies identical or equivalent to the original bytecode, then fewer different bodies; placeholder signals break any remaining tie. Ties keep the first engine in lexical order. Reports include per-class coverage. Signatures include class names/hierarchy and relevant access/static/final/abstract modifiers plus field/method names and descriptors; synthetic/bridge members and class initializers are excluded. Generic metadata, annotations, constant values and method-body behavior are not verified.

A candidate that parsed ranks above one that javac stopped at parsing, because javac reports no attribution errors and generates no class after a syntax error, so their error counts are not comparable. If neither coherent engine output passes, selection tries at most eight hybrids, recompiling the complete set after each attempt. Hybrids are first guided by javac's per-file errors: every file that fails in the current mix is replaced by the untried engine version of that file with the fewest errors, replacements that did not help their file are reverted, and the remaining budget falls back to single-file replacements in lexical order. This is a bounded greedy search, not an exhaustive optimum; method bodies are combined only by the composition step described below. Files containing nested classes stay together. Each report records the origin of selected files. Placeholder detection is a conservative text heuristic over the stub/warning text that CFR, Procyon, Vineflower and JADX emit for methods they could not decompile; it may flag legitimate UnsupportedOperationException implementations and misses failures that emit no text.

If the best candidate still parses but does not compile, a bounded source repair pass follows (at most three rounds). A separate javac-only worker analyzes the candidate (no class generation, processors or target loading) and maps each supported error, by code, file, line and column, to a minimal edit inside a method body: an upcast to the target's raw type, a raw diamond or type argument, `final` dropped from a local assigned twice (with a `final` copy for inner classes that capture it), or the visible decompiler-renamed local; and a class initializer printed as `static { throw ...; }`, whose statements are wrapped in `if (true) { ... }` so javac accepts it and emits the same bytecode; a boolean literal compared with an int restored to `1`/`0`, and an unreachable `break`/`continue` removed. Declarations are never edited. Files that still fail after the repair rounds are retried with each other engine's version (post-repair swaps, at most six). The edited mix is recompiled as a normal attempt; a file keeps its edits only when its own javac errors drop, and a repaired attempt can be selected only when it has fewer errors and no class loses a matched signature. Every edit is listed in `selection.txt` and in `report.json` (`source_repairs`) as compiler evidence, not proof of behavior; repaired files show `+repair` in their provenance. `benchmark` applies it only to the `combined` row, so single-engine rows stay engine baselines.

If files still fail after the repairs and swaps, per-method composition follows. A separate javac-only worker maps each remaining error to the method whose body holds it, finds the same method (owner, name and erased descriptor) in the other engines' versions of that file, and replaces only that body, one method at a time. A replacement is kept only when the file then compiles alone against the original classes, every name in the copied body resolves to the same declaration as in its engine's own file, and the method's round trip is identical or equivalent while no other method of the file gets worse. Imports, fields, initializers and declarations are never copied; a type the file does not import is written canonically inside the copied body. Bodies with generated names (`access$`, `this$0`, `Outer$1`, `lambda$`) or a different number of anonymous classes or lambdas are refused. The composed mix is recompiled under the repair eligibility rule; `selection.txt` lists each method's origin and every refusal, provenance shows `+methods`, and `report.json` has `method_composition`.

Once the selected set compiles, the same worker composes `DIFFERENT` bodies: each method whose round trip is `DIFFERENT` gets another engine's body of the same method only when its file, otherwise as it is and recompiled alone against the original classes, makes that method identical or equivalent with no other method of the file worse, under the same refusal rules. The mix is then recompiled (`recompose-1`, and `recompose-2` with only the files whose own bodies improved) and selected only when it still compiles with every signature (and stays complete) and recovers more bodies in total. Such bodies are listed with `DIFFERENT -> IDENTICAL` (or equivalent) in `selection.txt` and `replaced_body: ROUND_TRIP_DIFFERENT` in `report.json`. This takes Gson 2.13.1 from 1100 to 1120 of 1161 bodies identical or equivalent, ASM 9.11.1 from 542 to 575 of 601 and CFR 0.152 from 7367 to 7561 of 8039.

A `DIFFERENT` lambda method or method of an anonymous or local class is mapped (through the original class's `EnclosingMethod` attribute or the `lambda$m$N` name) to the written method that holds it, whose whole body is then replaced when one of those methods is recovered and none gets worse. A copied body that fails in place only by errors the source repair rules fix (an argument cast up to `Object`, a cast's type arguments, an unreachable `break`) is repaired and tried again under the same checks, in both composition steps.

The round trip's normalized comparison also reads the javac variations and decompiler rewritings that keep the JVM semantics: a call to a synthetic accessor `access$NNN` or to an enum's `$values()` is the instructions it forwards to (javac numbers accessors in the order it meets them, and builds `$VALUES` inline before Java 15), a `dup` right after a load is that load again, locals are numbered by variable (the stores of a slot with the loads they reach) instead of by slot, a store read only by the loads right after it is a `dup` and a store never read is a `pop`, and captured-variable fields (`val$name`) are paired when only their names differ. The control-flow comparison redirects jumps through blocks that only jump, reads a test of a `boolean` whose branches return 1 and 0 as returning it, pairs blocks as a bisimulation (a shared tail equals its copies) and now covers bodies with exception handlers. `IDENTICAL` is unchanged.

The composition worker also removes `checkcast`s the original does not have, which the comparator cannot excuse (a cast can throw). Most come from javac itself: since JDK 9 it casts a generic value passed to a parameter declared as a type variable (`set.add(list.get(0))`) to the instantiated type, while classes built by an older javac, such as CFR 0.152, have no cast there. Once no body is proved as written, the method's own body and every other engine's body that compiled but was refused are tried again with only the sites that cast to a class the recompiled method checks more often than the original edited: a raw receiver (`((java.util.Set) set).add(...)`), a raw creation (`new Box(...)`), an argument cast up to its type variable's erasure with a raw result for a generic method, or a cast dropped when javac reports it redundant (`-Xlint:cast`) or it is an upcast. The proof is unchanged, and the rules are listed among the body repairs.

`--timeout-seconds` applies per worker/compile attempt, not to the whole job. Up to four engines, fifteen compiler attempts, three repair analyses, two composition analyses (failing and `DIFFERENT` bodies) with two more compiler attempts each, and one fallback can take longer in total. Hybrid and repair source copies and compiled checks consume additional bounded-per-attempt disk space. Fallback uses ASM textual instructions in a heap/time-limited worker; fallback itself can fail, and its status is reported. Original snapshots are retained regardless.

Decompilation rejects malformed inventories, unsafe/nonportable class identities and identity/path mismatches. Multi-release JARs are decompiled at their base version: class entries under `META-INF/versions/N/` whose path equals their class name (N a decimal release of at least 9) are not passed to engines or compared, and `report.txt` and `report.json` (`multi_release`) list each one, whether it replaces a base class and whether the manifest declares `Multi-Release: true`. Any other versioned class entry is still rejected. Class names that differ only in letter case (`app/A` and `app/a`, an obfuscator trick against case-insensitive file systems) or contain segments that are no Java identifiers (`anti-leak/Loader`) are renamed before any engine runs: invalid characters become `_` (a package moves as a whole), and of names equal ignoring case the first in string order stays while the others get a stable `_cr2`, `_cr3`, ... suffix. The sandboxed rename worker rewrites every reference, and `classes.jar`, sources, signatures and verification use the new names. The mapping is kept in `portable-names/mapping.txt` and listed in `report.txt`, `selection/selection.txt` and `report.json` (`portable_names`, with the intake notices in `inventory_notices`). `rename` and `transform` still refuse such archives. private fields and methods whose names are no Java identifiers (`0a2b4acb...()`, a native stub the JVM accepts but javac cannot parse) or are reserved words get portable names in the same pass (`_0a2b4acb`, `do_`); non-private ones are left unchanged and listed (`renamed_members` and `members_left_unchanged` in `portable_names`). When an obfuscator stripped `InnerClasses`, engines disagree on nesting (JADX nests `B$C` inside `B.java` by name, the others write `B$C.java`); a hybrid keeps each class family in one form, normalized to top-level whenever an engine offers it (a nested family is replaced whole, never mixed), so no binary class is declared twice, and an enum constant body written as its own file is folded back into its constant (`enum_bodies_folded` in `report.json`). `rename` refuses multi-release JARs, because renaming only the base classes would leave the variants inconsistent. Rejected inputs remain inspectable with `inspect`, which marks valid variants. Nested archives/resources are not forwarded to engines. Dependencies are not downloaded or passed to the decompiler engines; external types can remain unresolved unless explicit local JARs are supplied for compiler verification.

The pipeline runs static decompiler tools, never the target program. Separate JVMs provide fault isolation, **not an OS sandbox**. Use an isolated VM for deliberately hostile samples. Default per-engine limits: 120 seconds, 256 MiB heap, 128 MiB metaspace, 1 MiB retained log, sampled 128 MiB output/20,000 entries. JVM total memory can exceed heap; sampled disk limits can overshoot. Initial inventory/snapshot has byte limits but no wall-clock deadline yet.

## Limits and safety

- Input: 256 MiB; expanded entry: 16 MiB; aggregate expanded data: 512 MiB; archive entries: 20,000.
- No extraction, class loading, annotation processing, target execution, downloads or network access in the inspection pipeline. The opt-in `--native` triage extracts bundled native libraries as data into the output folder; they are only read (by ClassRevive and by an external Ghidra the user installed), never loaded or run. Decompilation writes generated sources through the engines.
- Unsafe archive names and exact duplicate paths produce diagnostics. Paths that differ only in letter case are informational notices: class entries are all kept, and a resource colliding by case with a kept entry is left out (the first in archive order is kept; a class always wins). A resource declaring more than the per-entry limit (a bundled native library, say) is left out unread with a notice; an oversized class still fails. `inspect` prints notices as `NOTICE` lines. A malformed class does not hide successfully parsed siblings.
- ASM 9.11.1 parses supported class versions through Java 28 (major 72) for inventory, dump, diagnosis and transforms. Recompilation verification runs on JDK 21 and reports Java 22+ inputs as `UNSUPPORTED_RELEASE`. Decompiler engines may have lower limits of their own. Parsing is not JVM verification, a malware scan or proof of safety.
- Multi-release variants are reported and skipped by `decompile` and `--classpath`, refused by `rename`, and marked by `inspect`. Nested JARs are resources. Dependencies are never resolved or downloaded.
- Class files are not treated as source code. Benchmark scores measure recompilation, signatures and round-trip bodies on a fixed corpus; they are not a promise for any other input.
- SHA-256 before and after inspection detects ordinary concurrent modifications. This is not an atomic filesystem snapshot; use immutable copies for adversarial inputs.
- The current parser runs in the CLI process. For deliberately hostile files, use an isolated VM with resource limits. Dynamic execution remains unavailable.

## Tests

`gradlew test` runs fixtures generated locally by tests. No private samples are included. Tests cover Java 8 metadata, static initializers not executing, corrupted classes and archives, unsupported versions, unsafe paths, size/count limits, CLI exit codes and terminal escape injection.

The reproducible baseline compares CFR 0.152, Vineflower 1.11.1, Procyon 0.6.0, JADX 1.5.3 and combined selection. Pinned versions are tested integrations, not a claim to always bundle the latest release. No superiority claims are made.

## Benchmark and JSON reports

```powershell
.\gradlew.bat benchmarkFixtures :cli:installDist
.\modules\cli\build\install\classrevive\bin\classrevive.bat benchmark build\benchmark-corpus --output build\benchmark-results
```

The corpus contains synthetic Java 8 resources/string switches, Java 8 lambdas/nested classes/exceptions, Java 17 records and switches, Java 21 pattern switches, a deliberately difficult throwing initializer, and short names/XOR logic. These are authored samples, not comprehensive obfuscator coverage. Fixtures are compiled with annotation processing disabled and never executed. JAR order/timestamps are deterministic for a fixed JDK/compiler; hashes can differ across compiler versions.

`benchmark.json` records schema version, runtime environment, sample hashes, engine versions/statuses, compilation and structural counts, total elapsed milliseconds and standalone engine timing. `benchmark.md` provides a GitHub-readable summary. The default is all four engines; `--engines` can select a subset. Each sample runs once per standalone engine and once with combined selection (combined is omitted with one engine). Variants run sequentially in fixed order; the combined variant runs 2 to 4 engines concurrently (half the available processors, clamped), and candidate recompilations run with the same bound. No warmup, randomized ordering, statistical confidence or behavioral equivalence is claimed. Total elapsed time includes intake, verification and any fallback; it is **not** pure decompiler speed. Recompilation rate is `recompiled / attempted`; structural rate uses `structurally_complete / attempted`. Failures remain in the denominator. Scores are not source recovery percentages.

Inputs must be 1..100 top-level regular `.jar`/`.class` files, each at most 256 MiB, aggregate 512 MiB. Directory listing is capped at 1000 entries; linked inputs/ancestors and outputs inside the corpus are rejected. Samples are copied once and shared across variants. Use immutable inputs in a trusted local directory. Output must be new; each attempt retains its own evidence. `progress.jsonl` preserves completed rows during an interrupted run; only `benchmark.json` means aggregation finished. A completed benchmark returns 1 if any attempt is partial/failed, 0 if all meet structural checks, 2 on usage errors, 130 on interruption.

A sample `name.jar` compiles against the jars in a sibling folder `name.jar.deps/` (at most 16 regular `.jar` files, counted in the 512 MiB; a `.deps` folder without its sample is refused). They are snapshotted once per case, used only by javac with the same rules as `--classpath`, and listed with their SHA-256 in each row. `benchmark.md` ends its metrics with a per-sample table: for each sample and variant, recovered bodies over all the sample's bodies when the candidate recompiled, otherwise the javac error count. A candidate that does not compile as a whole is also measured file by file: its files are compiled against the original classes, leaving out those with errors, and the kept files' bodies are compared. This partial credit is reported in its own table and cells (`3 errors; per file 10753/10988`), never in place of the whole-candidate numbers.

The official scoreboard runs this on real libraries from Maven Central, hash-checked by `gradle/verification-metadata.xml`, never committed and never executed:

```powershell
.\gradlew.bat :cli:scoreboard
.\gradlew.bat :cli:scoreboard -PscoreboardSet=holdout
.\gradlew.bat :cli:scoreboard -PscoreboardSet=holdout2
```

See [benchmarks](benchmarks.md) for the latest measured numbers and how to read them.

The `dev` set is Gson, ASM, CFR, Guava, commons-compress and Ant with their compile-time dependencies; the `holdout` set is JavaEWAH, jopt-simple and org.json; the `holdout2` set is thirteen more modern libraries (commons-lang3, commons-text, Jackson core and databind, Joda-Time, SLF4J, jsoup, picocli, Caffeine, ANTLR runtime, Jenetics, mccue json, resilience4j-core). Each run writes a new `build/scoreboard/<set>-<timestamp>/`.

Each benchmark also writes `failures/`: every row's selected candidate is captured by the failure lab below, and `benchmark.md` ends with the most frequent failure signatures.

## Failure lab

```powershell
.\modules\cli\build\install\classrevive\bin\classrevive.bat lab build\benchmark-corpus\java17.jar --output build\lab
.\modules\cli\build\install\classrevive\bin\classrevive.bat lab-minimize build\lab\failures\failure-0001 --output build\lab-min
.\modules\cli\build\install\classrevive\bin\classrevive.bat lab-promote build\lab-min\minimized --output benchmarks\regressions\my-case --provenance "authored fixture, Apache-2.0"
.\modules\cli\build\install\classrevive\bin\classrevive.bat lab-check benchmarks\regressions --output build\regression-check
```

`lab <input>` decompiles with every engine and writes one `failures/failure-NNNN/` case per failure: a method body that differs after recompilation, a candidate file javac rejects, an engine stub marker, or a worker failure. Cases are grouped in `failures.json`/`failures.md` by a stable signature such as `METHOD_DIFFERENT:cfr:body-differs:astore/dup` (category, producer, first diverging opcodes or javac code). Signatures never name target classes, members, constants or paths. The target-derived evidence (classes, candidate source, javac messages) stays in the case directory. With a corpus directory, `lab` runs the benchmark with the same capture. `lab-minimize` reduces a case with ASM (other classes, other members, stub bodies, instruction edits, debug attributes). A reduction is kept only if the real JVM verifier accepts every class, linked in an isolated loader without initializing or running anything, and two fresh runs reproduce the same signature. By default, an engine that recovered the method must keep recovering it. `--max-steps`, `--max-seconds` and `--timeout-seconds` bound the work. `lab-promote` measures the per-method level of every variant twice and writes `expected.json`. `lab-check`, `./gradlew test` and `./gradlew checkRegressions` fail when a committed case in `benchmarks/regressions/` gets worse. Commit only cases reduced from authored or license-compatible inputs.

JSON is UTF-8, object keys sorted, enums stable within schema v1. `behavior` is always `NOT_VERIFIED`. `report.json` is emitted when verified-job processing completes (including partial results); early intake failures still use `failure.txt` and benchmark failed rows. Per-worker limits are inherited, not a global benchmark disk/time budget; large corpora can consume substantial space in retained alternatives and compile attempts. See [report format](reports.md).
