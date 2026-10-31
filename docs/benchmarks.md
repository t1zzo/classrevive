# Benchmarks

ClassRevive measures itself on real open-source libraries. This page explains what the scoreboard measures, how to run it, and the latest numbers. Every number here was produced by the command next to it; nothing comes from private samples.

## What the scoreboard is

The scoreboard runs `classrevive benchmark` on fixed sets of libraries from Maven Central. Gradle downloads each jar and its compile-time dependencies and checks them against the SHA-256 hashes in `gradle/verification-metadata.xml`, so every run reads the same bytes. The libraries are never committed to the repository, never put on a class path of the build and never executed: ClassRevive decompiles snapshots of them, and javac only reads their dependencies.

| Set | Libraries | Role |
|---|---|---|
| `dev` | Gson 2.13.1, ASM 9.11.1, CFR 0.152, Guava 17.0, commons-compress 1.27.1, Ant 1.10.17 | measured while developing; rules were tuned on these, so treat its numbers as optimistic |
| `holdout` | JavaEWAH 1.1.13, jopt-simple 5.0.4, org.json 20231013 | no rule was tuned on these |
| `holdout2` | commons-lang3 3.17.0, commons-text 1.13.0, jackson-core 2.17.3, jackson-databind 2.17.3, joda-time 2.13.0, slf4j-api 2.0.16, jsoup 1.18.3, picocli 4.7.6, caffeine 3.1.8, antlr4-runtime 4.13.2, jenetics 8.1.0, mccue json 2024.11.20, resilience4j-core 2.2.0 | a larger, more modern holdout; no rule was tuned on these |

The exact coordinates and dependencies are in `modules/cli/build.gradle.kts` (`scoreboardCorpora`).

## Running it

You need a JDK 21 and a checkout of the repository.

```bash
./gradlew :cli:scoreboard -PscoreboardSet=holdout
./gradlew :cli:scoreboard -PscoreboardSet=holdout2
./gradlew :cli:scoreboard                      # the dev set
```

On Windows, use `.\gradlew.bat`. Each run writes a new folder `build/scoreboard/<set>-<timestamp>/` with:

- `benchmark.md`: the summary tables quoted below;
- `benchmark.json`: versions, hashes, environment, timings and per-case evidence (format in [reports.md](reports.md));
- one result folder per library and variant, the same layout `classrevive decompile` writes;
- `failures/`: every failure captured by the failure lab, grouped by signature.

Every library is decompiled once by each engine alone and once with combined selection, with a 600-second limit per worker. The `holdout` set takes a few minutes; `holdout2` and `dev` take one to several hours on a laptop. Engine outputs are cached under `build/engine-cache`, so a second run is faster. An interrupted run continues with `-PscoreboardResume=<folder name under build/scoreboard>`.

## How to read the numbers

- **Recompiles**: the whole selected source set of the library compiles with javac against the library's declared dependencies. A library that does not compile as a whole gets no body credit in the main tables.
- **Signatures**: classes, fields and methods whose structure (names, descriptors, hierarchy, relevant modifiers) matches the original. Generic signatures, annotations and constant values are not compared.
- **Bodies identical or equivalent**: recompiled method bodies whose bytecode is identical to the original, or equal after normalizing javac variations and decompiler rewritings that keep the JVM semantics. The denominator is every method body of the original library.
- **Stub-backed**: the result compiled only with inferred stand-ins for missing types, so its signatures were checked against guesses. None of the results below needed them.
- **Per-file credit** (second table of each set): for a candidate that does not compile as a whole, each file is compiled alone against the original classes and the bodies of the files that compile are counted. It shows how much a single engine got right file by file; it is never mixed into the whole-candidate numbers.

What these numbers are not:

- They do not measure behavior. Target code is never executed, so a body that compiles to different bytecode may still be correct, and identical bytecode is not a proof of identical behavior in every environment.
- They are not a percentage of "original source recovered". Comments, formatting and local variable names are not compared.
- They come from a single trial per library and variant. Elapsed times include intake, recompilation and fallback, not just decompiler speed.
- They say nothing certain about other inputs, in particular obfuscated ones. On an obfuscated input the round trip measures agreement with the obfuscated bytecode.

## Latest results

Measured on 2026-10-29 with ClassRevive 0.1.0-dev, the pre-release source tree that became the first public version, on Windows 11 (amd64) with Eclipse Adoptium JDK 21.0.12. All four engines: CFR 0.152, Vineflower 1.11.1, Procyon 0.6.0 and JADX 1.5.3.

### holdout

Combined selection, per library:

| Library | Recompiles | Signatures | Bodies identical or equivalent | Stub-backed |
|---|:---:|---:|---:|:---:|
| JavaEWAH-1.1.13 | yes | 1,348/1,348 | 961/974 (98.6%) | no |
| jopt-simple-5.0.4 | yes | 546/548 | 379/379 (100.0%) | no |
| json-20231013 | yes | 478/479 | 412/412 (100.0%) | no |
| **Total** | **3/3** | **2,372/2,375 (99.8%)** | **1,752/1,765 (99.2%)** | 0 |

Each engine alone, against combined selection:

| Variant | Recompiled | Bodies, whole candidate | With per-file credit |
|---|---:|---:|---:|
| cfr | 0/3 | 0/1765 (0.0%) | 1266/1765 (71.7%) |
| vineflower | 1/3 | 947/1765 (53.6%) | 1650/1765 (93.4%) |
| procyon | 0/3 | 0/1765 (0.0%) | 832/1765 (47.1%) |
| jadx | 0/3 | 0/1765 (0.0%) | 1024/1765 (58.0%) |
| combined | 3/3 | 1752/1765 (99.2%) | 1752/1765 (99.2%) |

The three libraries are not reported as fully complete: a few signatures are missing and 8 placeholder signals were raised in total, some of them for `throw new UnsupportedOperationException(...)` statements that the original authors wrote themselves.

### holdout2

Combined selection, per library, then each engine alone:

| Library | Recompiles | Signatures | Bodies identical or equivalent | Stub-backed |
|---|:---:|---:|---:|:---:|
| antlr4-runtime-4.13.2 | yes | 2,367/2,367 | 1,590/1,603 (99.1%) | no |
| caffeine-3.1.8 | no (33 errors) | 1,159/9,870 | 0/6,608 (0.0%) | no |
| commons-lang3-3.17.0 | no (3 errors) | 1,118/5,809 | 0/4,616 (0.0%) | no |
| commons-text-1.13.0 | yes | 1,801/1,809 | 1,368/1,377 (99.3%) | no |
| jackson-core-2.17.3 | yes | 4,521/4,533 | 2,996/3,036 (98.6%) | no |
| jackson-databind-2.17.3 | no (5 errors) | 4,730/11,408 | 0/8,545 (0.0%) | no |
| jenetics-8.1.0 | no (19 errors) | 21/2,880 | 0/2,605 (0.0%) | no |
| joda-time-2.13.0 | no (3 errors) | 51/5,597 | 0/4,128 (0.0%) | no |
| json-2024.11.20 | no (6 errors) | 89/725 | 0/631 (0.0%) | no |
| jsoup-1.18.3 | no (1 error) | 1,945/2,882 | 0/2,105 (0.0%) | no |
| picocli-4.7.6 | yes | 2,904/2,913 | 2,266/2,295 (98.7%) | no |
| resilience4j-core-2.2.0 | no (1 error) | 18/428 | 0/342 (0.0%) | no |
| slf4j-api-2.0.16 | yes | 859/859 | 554/554 (100.0%) | no |
| **Total** | **5/13** | **21,583/52,080 (41.4%)** | **8,774/38,445 (22.8%)** | 0 |

| Variant | Recompiled | Bodies, whole candidate | With per-file credit |
|---|---:|---:|---:|
| cfr | 0/13 | 0/38445 (0.0%) | 22587/38445 (58.7%) |
| vineflower | 1/13 | 552/38445 (1.4%) | 31408/38445 (81.6%) |
| procyon | 0/13 | 0/38445 (0.0%) | 17864/38445 (46.4%) |
| jadx | 0/13 | 0/38445 (0.0%) | 19955/38445 (51.9%) |
| combined | 5/13 | 8774/38445 (22.8%) | 35899/38445 (93.3%) |

For the eight libraries that do not compile as a whole, per-file credit (each file compiled alone against the original classes) recovers:

| Library | javac errors | Bodies with per-file credit |
|---|---:|---:|
| caffeine-3.1.8 | 33 | 5,931/6,608 (89.7%) |
| commons-lang3-3.17.0 | 3 | 4,144/4,616 (89.7%) |
| jackson-databind-2.17.3 | 5 | 8,304/8,545 (97.1%) |
| jenetics-8.1.0 | 19 | 2,199/2,605 (84.4%) |
| joda-time-2.13.0 | 3 | 3,848/4,128 (93.2%) |
| json-2024.11.20 | 6 | 486/631 (77.0%) |
| jsoup-1.18.3 | 1 | 1,887/2,105 (89.6%) |
| resilience4j-core-2.2.0 | 1 | 326/342 (95.3%) |

The most frequent javac error left in the selected sources is `compiler.err.prob.found.req` (incompatible types); `failures/` in the run folder groups every case by signature. Three engine runs failed outright and are counted as failures: JADX on caffeine, and Procyon on jenetics and on mccue json; the other engines still ran for those libraries. This run was interrupted once and resumed with `-PscoreboardResume`; 29 of its 65 rows come from before the interruption, measured on the same tree.

### dev

Not re-measured for this page. Rules were tuned on the `dev` libraries, so their numbers are optimistic and are not used as a headline; run `./gradlew :cli:scoreboard` to measure them.

## The authored corpus

`./gradlew benchmarkFixtures :cli:installDist` builds a small corpus of authored samples (Java 8 to 21 language features, a deliberately throwing initializer, short names and XOR logic) into `build/benchmark-corpus`, and `classrevive benchmark build/benchmark-corpus --output <new folder>` measures it. These samples exercise specific features; they are not a coverage claim. Regression cases reduced from failures live in `benchmarks/regressions/` and are checked by `./gradlew test`.
