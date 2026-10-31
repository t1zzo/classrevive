# Contributing to ClassRevive

Thanks for your interest. Bug reports, detection reports, documentation fixes, regression cases and code are all welcome. By taking part you agree to follow the [Code of Conduct](CODE_OF_CONDUCT.md).

## Ground rules

- **Never execute the analyzed code.** ClassRevive reads target files as data: it must never load, link or run a target class on a JVM, run its static initializers or start a target program. The few passes that evaluate target bytecode do it in a bounded static interpreter with an instruction allowlist (see [ARCHITECTURE.md](ARCHITECTURE.md#principles)). A change that breaks this rule will not be merged, whatever it gains.
- **Only analyze what you have the right to analyze**, and never share files you may not share. This applies to issues, pull requests, test fixtures and benchmark cases alike.
- **Fail closed and report evidence.** Refuse unsafe input with a diagnostic instead of guessing. Recompiling is not behavioral equivalence: do not describe a result as equivalent or "100% recovered" because it compiles.

## Reporting a bug

Open an issue with the **Bug report** form. The most useful report is one anyone can reproduce:

1. **A minimal case you wrote.** A few lines of Java (or Kotlin, Scala…) plus the exact compiler command and flags, for example `javac --release 17 -d out Example.java && jar cf example.jar -C out .`.
2. **Or a public library jar**, named by its Maven coordinates (`group:artifact:version`), never re-uploaded.
3. The ClassRevive command you ran, `classrevive --version`, `classrevive engines`, `java -version` and your operating system.
4. The relevant part of `report.txt` or `report.json`, with local paths, user names and strings from the input removed.

**Do not attach private, proprietary or third-party binaries** (jars, APKs, executables, class files), secrets or source you are not allowed to share, and do not post links to them. If you can only reproduce a problem with such a file, describe what the file looks like (compiler, obfuscator and settings, the construct that fails) and we will try to build an equivalent case.

Obfuscation detection mistakes (a tool reported that was not used, or missed) have their own form: paste the diagnosis output instead of the file. Security vulnerabilities go to the [security policy](SECURITY.md), never to a public issue.

## Building and testing

You need **JDK 21** (the build and the recompilation step use its `javac`) and the checked-in Gradle wrapper; nothing else has to be installed. From the repository root:

```bash
./gradlew test :cli:installDist :cli:distZip
```

On Windows, use `gradlew.bat` (or `.\gradlew` in PowerShell). This runs every unit and integration test, the committed regression cases in `benchmarks/regressions/`, and builds the distribution:

- `modules/cli/build/install/classrevive/bin/classrevive` (and `classrevive-gui`) — the launchers you can run directly;
- `modules/cli/build/distributions/classrevive-<version>.zip` — the distributable zip.

The desktop tests detect a headless environment and skip what needs a display. `ScreenshotTour` is opt-in and does not run by default.

Every downloaded dependency is checked against `gradle/verification-metadata.xml`. If you add or update a dependency, regenerate the metadata with `./gradlew --write-verification-metadata sha256 help`, review the diff, and update [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) and `third-party-licenses/` for anything that ships in the distribution. New dependencies need a license and transitive-dependency review; strong copyleft licenses (such as GPL or AGPL) cannot be bundled.

## Project layout

The build has 17 Gradle modules under `modules/`, from the immutable `model` up to the `gui` and `cli` front ends. [ARCHITECTURE.md](ARCHITECTURE.md) describes each module, the worker JVMs, the decompile flow, selection and the transforms; read it before a non-trivial change. Report fields are documented in [docs/reports.md](docs/reports.md) and obfuscation detection in [docs/obfuscation-detection.md](docs/obfuscation-detection.md).

## Making a change

1. For anything larger than a small fix, open an issue (or a discussion) first, so the approach can be agreed before you write the code.
2. Fork the repository and create a branch from `main` with a short descriptive name, such as `fix/jadx-placeholder-cast` or `docs/report-fields`.
3. Keep the change focused: one topic per pull request.
4. **Write the test first.** A bug fix comes with a test that fails without it; a new feature comes with tests of its behavior and its limits.
5. Run `./gradlew test :cli:installDist :cli:distZip` before you push.
6. Open the pull request against `main` and fill in the template. CI runs the same command on Linux, Windows and macOS. Pull requests are squash-merged.

### Commits

- Write commit messages in English, with a short imperative subject (`Fold constant string switches in the Procyon output`) of about 70 characters or fewer, and a body that explains *why* when it is not obvious.
- Do not reference private files, samples or internal notes in messages.
- Signing off your commits ([Developer Certificate of Origin](https://developercertificate.org/), `git commit -s`) is welcome but not required. Contributions are accepted under the project's license, the [ClassRevive License](LICENSE) (section 5).

### Code style

- Code, comments, documentation and commit messages are in English.
- Follow the style of the surrounding code; there is no separate formatter configuration.
- Keep diagnostic codes and report schemas stable. A change to `report.json` must be backward compatible or bump its schema version.
- Every new limit (time, size, count, steps) is explicit, and hitting it is reported.
- Anything that runs third-party or heavy code on data derived from the input runs in a worker JVM through `ProcessRunner`, never in the main process.

## Adding a test fixture or regression case

- Prefer fixtures the test generates itself: Java source compiled by the test, or classes written with ASM. Existing tests in each module show both patterns.
- Committed binaries must be written for this repository or derived from inputs under a license compatible with the ClassRevive License (permissive licenses such as MIT, BSD or Apache-2.0), and documented next to the file.
- Reduced classes from a third-party jar are still derived from it: do not commit them unless the original license allows it.
- To turn a decompilation failure into a permanent regression case, use the failure lab (`classrevive lab`, `lab-minimize`, `lab-promote`) as described in [benchmarks/regressions/README.md](benchmarks/regressions/README.md), and record the provenance and license.

## Adding a bytecode pass

Passes implement `BytecodePass` and are registered explicitly in `BuiltinPasses` (module `transforms`); there is no plugin discovery. The contract (bounded input and changes, an audit record for every modification, no target execution, behavior preserved or input left unchanged) is in [docs/pass-sdk.md](docs/pass-sdk.md). A new pass needs tests for the shapes it folds, for the shapes it must leave alone, and for its budgets, and its output must pass the JVM verifier.

## Engines and benchmarks

For a change to an engine adapter, selection or verification, measure it:

```bash
./gradlew benchmarkFixtures :cli:installDist
modules/cli/build/install/classrevive/bin/classrevive benchmark build/benchmark-corpus --output build/your-result --engines all
python scripts/check_benchmark.py build/your-result
```

Include the Markdown summary and the failing cases in the pull request. `./gradlew :cli:scoreboard` measures real libraries from Maven Central; rules must not be tuned on the `holdout` sets. Never lower a threshold to make a benchmark look better, and report results with the corpus, versions, resource budget and failures.

A new engine must run in its own worker JVM, must not discover user plugins, execute target code or inherit injected JVM flags, and its whole runtime dependency graph needs a license review.

## Translations

English is the reference language for the code, the documentation and the desktop application. Portuguese (Brazil) is maintained alongside it: the desktop strings in `modules/gui/src/main/resources/dev/classrevive/gui/messages_pt_BR.properties`, the beginner's guide `docs/guia-iniciante.md` and the Portuguese README.

- A new desktop string goes into both `messages.properties` and `messages_pt_BR.properties`; a test fails when the keys differ.
- If you cannot write Portuguese, add the English text to both files and say so in the pull request, so it can be translated before release.
- Other languages are not maintained for now. Open a discussion before starting one, with a plan for keeping it up to date.

## Coding agents

Automated and AI-assisted contributions follow the same rules; [AGENTS.md](AGENTS.md) summarizes them. The contributor who opens the pull request is responsible for its content.

## Good first issues

Issues labeled `good first issue` are small, self-contained and come with a pointer to the code and the test to change. Comment on the issue before starting, so two people do not work on the same one.
