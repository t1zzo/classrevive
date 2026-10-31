# Aidsfuscator fixture (`fixtures.aids`)

The answer-key program for the Aidsfuscator reversal. `src/` is the program of the
Skidfuscator corpus (`../skidfuscator`) in package `fixtures.aids`, authored for ClassRevive.

Aidsfuscator's repository has no license, so neither the obfuscator nor anything it produced is committed here. The
obfuscated jar is made locally, under `.analysis/` (never committed):

1. Clone <https://github.com/LvStrnggg/aidsfuscator> at commit `8dbc9b09b760d38f5aa8cff619641cc4c9e4a3dc` (v2.10.0) and
   read it (it only reads the input jar and asks GitHub's API for its latest release).
2. Compile `aidsfuscator-api/src` and `aidsfuscator-main/src` with a JDK 25 (`javac -d classes`) against ASM 9.11.1
   (`asm`, `asm-tree`, `asm-commons`, `asm-util`, `asm-analysis`) and Gson 2.13.1.
3. Compile `src/` with `javac --release 8 -proc:none` into `in.jar` (main class `fixtures.aids.Main`).
4. Use the repository's `workspace/config.json`, `exclusions.json` and `references.json`, with `initOrder.json` set to
   `[]` and, in `config.json`, `trim`, `renameFields`, `renameMethods` and `renameClasses` disabled (renaming cannot be
   reversed without a mapping and would hide every name from `compare-reference`). Every other transformer stays on.
5. From the folder holding `in.jar` and `workspace/`:
   `java -cp <classes and libraries> dev.lvstrng.aidsfuscator.Main --config=config.json --exclusions=exclusions.json --initOrder=initOrder.json --references=references.json`
   writes `out.jar`.
6. `classrevive transform out.jar --output t --passes aidsfuscator`, then
   `classrevive decompile t/worker/transformed/transformed.jar --output d --engines all` and
   `classrevive compare-reference in.jar d --output r`.

Aidsfuscator draws its keys from `SecureRandom`, so every run gives different bytes; record the SHA-256 of
`in.jar` and `out.jar` with any measurement.
