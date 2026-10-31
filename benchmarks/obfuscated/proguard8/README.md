# ProGuard regression fixtures (`obf8`, `obf8-opt`)

Real obfuscator output for regression tests and the benchmark corpus.

- `src/` is authored for ClassRevive: a kept `fixtures.obf.Main`, a `Vault` with XOR-masked literals and a private
  static `(String)String` decoder, a `Serializable` `Ledger` with private fields and helpers, and a nested `Ledger.Entry`.
- `obf8.jar` / `obf8.mapping.txt`: ProGuard 7.10.0 with [`obf8.pro`](obf8.pro) (`-dontoptimize -dontshrink`, rename only).
- `obf8-opt.jar` / `obf8-opt.mapping.txt`: ProGuard 7.10.0 with [`obf8-opt.pro`](obf8-opt.pro) (default optimization and
  shrinking; private helpers are inlined, so the mapping contains inline-frame lines).

Both configurations keep only `Main.describe(int)`, keep `LineNumberTable,SourceFile`, and deliberately omit the usual
serialization keep rules and `-keepattributes Exceptions`. Input is the `src/` tree compiled with `javac --release 8`;
the library is the build JDK's `jmods/java.base.jmod`.

## Tool and license

ProGuard (`com.guardsquare:proguard-base:7.10.0`, GPL-2.0 with the ProGuard linking exception; `proguard-core:9.4.0`,
Apache-2.0) is resolved from Maven Central through the `proguardTool` configuration in the root `build.gradle.kts`,
with SHA-256 entries in `gradle/verification-metadata.xml` checked against the published Maven Central SHA-1 files.
It runs only as an external program at fixture-generation time. No ProGuard code is copied into or distributed with
ClassRevive, and the committed jars contain only ClassRevive's own fixture classes.

## Regenerating

```
gradlew regenerateObfuscatedFixtures   # re-runs ProGuard and overwrites the jars and mappings here
gradlew verifyObfuscatedFixtures       # fails if a fresh run no longer matches the committed files
```

ProGuard stamps jar entries with the current time, so it writes a directory and Gradle packs the jar with fixed
timestamps and sorted entries. Mappings are committed with LF line endings (ProGuard writes the platform separator).

## What the tests expect

`modules/cli/src/test/java/dev/classrevive/cli/ProguardFixtureTest.java` runs `rename --proguard-mapping` and
`transform --passes string-decryptor,integer-branches` over both jars, then defines the outputs in an isolated class
loader and links them (`getDeclaredMethods()` etc.). No fixture code is initialized or executed.

| Fixture | Classes restored | Members restored | Skipped |
|---|---:|---:|---|
| `obf8` | 3 | 9 | `NOT_PRIVATE` 5, `SERIALIZATION_MEMBER` 2 |
| `obf8-opt` | 3 | 6 | `NOT_PRIVATE` 10, `MEMBER_NOT_FOUND` 3 (inline frames) |

The string decryptor folds 2 literals in `Vault` in both jars; the branch pass finds nothing (javac already folds
constant conditions, and ProGuard adds none).
