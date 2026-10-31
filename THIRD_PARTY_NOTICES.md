# Third-party notices

ClassRevive's own code is Apache-2.0 (`LICENSE`). The binary distribution bundles the components below, unmodified; their full license texts are in `third-party-licenses/`, and the attribution notices their NOTICE files require are reproduced in `NOTICE`. These permissive dependencies do not change the license of ClassRevive's own code. ClassRevive's adapters are new code written against the components' public APIs, not copied upstream implementation.

## Decompiler engines (run unmodified in separate JVM workers)

| Component | Version | License | Bundled license | Source |
|---|---|---|---|---|
| CFR | 0.152 | MIT, Copyright (c) 2011-2019 Lee Benfield | `third-party-licenses/CFR-MIT.txt` | https://github.com/leibnitz27/cfr/tree/0.152 |
| Vineflower | 1.11.1 | Apache-2.0 (derived from Fernflower and its successor forks) | `third-party-licenses/Vineflower-Apache-2.0.txt` | https://github.com/Vineflower/vineflower/tree/1.11.1 |
| Procyon compilertools/core | 0.6.0 | Apache-2.0, Copyright Mike Strobel | `third-party-licenses/Procyon-Apache-2.0.txt` (upstream `License.txt`) | https://github.com/mstrobel/procyon/tree/v0.6.0 |
| JADX core, input-api, java-input, dex-input, zip | 1.5.3 | Apache-2.0, Copyright Skylot and contributors | `third-party-licenses/JADX-Apache-2.0.txt`, `third-party-licenses/JADX-NOTICE.txt` | https://github.com/skylot/jadx/tree/v1.5.3 |

The full upstream JADX NOTICE is retained verbatim; its references to GUI and command-line libraries do not mean those libraries are bundled here. No JADX GUI, CLI, plugin installer or scripting plugin is included. JADX's dex input plugin is included; its runtime dependencies smali-baksmali and Guava, which serve smali disassembly only, are excluded and not redistributed.

Runtime libraries the JADX adapter brings in:

| Library | Version | License | Bundled license |
|---|---|---|---|
| Gson | 2.13.1 | Apache-2.0 | `third-party-licenses/Gson-Apache-2.0.txt` |
| Error Prone annotations | 2.38.0 | Apache-2.0 | `third-party-licenses/ErrorProne-Apache-2.0.txt` |
| SLF4J API | 2.0.17 | MIT, Copyright (c) 2004-2022 QOS.ch Sarl | `third-party-licenses/SLF4J-MIT.txt` |
| Raung common/disasm | 0.1.1 | MIT, Copyright (c) 2021-present Skylot and other contributors | `third-party-licenses/Raung-MIT.txt` |

Raung's ASM dependency resolves to the project's ASM 9.11.1.

## Bytecode and hashing libraries

| Library | Version | License | Bundled license | Source |
|---|---|---|---|---|
| ASM core, tree, analysis, util, commons | 9.11.1 | BSD-3-Clause, Copyright (c) 2000-2011 INRIA, France Telecom | `third-party-licenses/ASM-BSD-3-Clause.txt` | https://asm.ow2.io/license.html |
| OpenHFT zero-allocation-hashing | 0.16 | Apache-2.0, Copyright Chronicle Software | `third-party-licenses/ZeroAllocationHashing-Apache-2.0.txt` | https://github.com/OpenHFT/Zero-Allocation-Hashing |

zero-allocation-hashing is used unmodified to compute XXH3 hashes when reversing Skidfuscator's SDK hashes.

## Desktop application (used only by the `gui` module)

| Library | Version | License | Bundled license |
|---|---|---|---|
| FlatLaf | 3.7.2 | Apache-2.0, Copyright FormDev Software GmbH | `third-party-licenses/FlatLaf-Apache-2.0.txt` |
| RSyntaxTextArea | 4.0.1 | BSD-3-Clause, Copyright (c) 2021 Robert Futrell | `third-party-licenses/RSyntaxTextArea-BSD-3-Clause.txt` |

## Dalvik conversion worker (`lib/dex2jar`, run only in its own JVM, apart from the application's class path)

| Library | Version | License | Bundled license |
|---|---|---|---|
| dex2jar (de.femtopedia.dex2jar: dex-translator, dex-reader, dex-reader-api, dex-ir, d2j-base-cmd, d2j-external), Copyright Panxiaobo and contributors, https://github.com/ThexXTURBOXx/dex2jar | 2.4.38 | Apache-2.0 | `third-party-licenses/dex2jar-Apache-2.0.txt`, `third-party-licenses/dex2jar-NOTICE.txt` |
| Android Open Source Project `dx`, repackaged unmodified inside `d2j-external` | as shipped in dex2jar 2.4.38 | Apache-2.0, Copyright The Android Open Source Project | `third-party-licenses/AOSP-dx-NOTICE.txt` |
| ASM tree, util, analysis and commons (requested by dex2jar) | 9.10.1 | BSD-3-Clause | `third-party-licenses/ASM-BSD-3-Clause.txt` |

`d2j-external` also repackages ASM's core classes (BSD-3-Clause); the worker uses that ASM core, which is why these jars stay off the application's class path. This build also runs `dx` from that jar as a build tool, only to regenerate the test fixture in `benchmarks/android`.

## Optional external tool (not redistributed)

Ghidra (https://github.com/NationalSecurityAgency/ghidra), Apache-2.0. ClassRevive neither bundles nor downloads it; `--native` uses an installation the user already has (`--ghidra-home` or `GHIDRA_INSTALL_DIR`) and runs its `support/analyzeHeadless` launcher as a separate process. The post script `modules/pipeline/src/main/resources/dev/classrevive/pipeline/ghidra/ClassReviveNativeExport.java` is original ClassRevive code written against Ghidra's public scripting API; it is compiled by the user's Ghidra at run time, not by this build.

## Obfuscators ClassRevive interoperates with

ClassRevive recognizes and reverses the output of some obfuscators. This is interoperability by reading behaviour: the detectors and reversal passes were written from the bytecode these tools emit and from reading what their output does. No code of these tools is copied into ClassRevive, and none of them is bundled or run by the distribution.

- **Skidfuscator** (https://github.com/skidfuscatordev/skidfuscator-java-obfuscator), MIT, Copyright (c) 2021 Shanyu Juneja, full license `third-party-licenses/Skidfuscator-MIT.txt`. Benchmark fixture only (not part of the CLI distribution): `benchmarks/obfuscated/skidfuscator/skid-fixture-obf.jar` is ClassRevive's own fixture source obfuscated by Skidfuscator 2.1.0 (commit `e4c0f981911703e86ac8a2b1a77d7cfd18b9d719`). The jar carries the code Skidfuscator injects (decryptors, hash factory) and its `sdk/*` runtime, a copy of OpenHFT zero-allocation-hashing (XXH3), Apache-2.0. Skidfuscator runs only as an external tool when the corpus is regenerated.
- **Aidsfuscator** 2.10.0 (no license published upstream). Nothing of it is committed or distributed: `benchmarks/obfuscated/aidsfuscator/` holds only ClassRevive's own answer-key source, and obfuscated output is produced locally by whoever runs the corpus script.
- Commercial obfuscators named by the detector (Zelix KlassMaster, Allatori, Stringer, DashO, ProGuard/R8) are recognized from documented static patterns only (`docs/obfuscation-detection.md`). Their names are trademarks of their owners; ClassRevive is not affiliated with any of them.

## Build and test tooling (not in the distribution)

Gradle 8.12.1 (Apache-2.0); its wrapper is checked in and its distribution retains its own notices. Test-only: JUnit Jupiter/Platform (EPL-2.0), OpenTest4J and API Guardian (Apache-2.0). ProGuard 7.10.0 (GPL-2.0 with exception) was used only as an external tool to produce the committed ProGuard fixtures, which contain only ClassRevive's own classes. Test libraries are not included in the CLI distribution.

Gradle dependency verification metadata records SHA-256 values from the initial Maven Central resolution. This is trust-on-first-use integrity pinning, not independent signature authentication.
