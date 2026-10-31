# Failure-lab regression cases

Each folder is one permanent regression case produced by the failure lab (`classrevive lab`):

| File | Content |
|---|---|
| `classes.jar` | The reduced classes. Every class passed the JVM verifier when it was reduced. Nothing here is ever executed. |
| `expected.json` | For each variant (one engine alone, or `combined` with all engines and source repair, as in the benchmark), the minimum round-trip level of every method body: `NOT_RECOMPILED` < `DIFFERENT` < `EQUIVALENT_AFTER_NORMALIZATION` < `IDENTICAL`. It also records provenance, the failure signature, the SHA-256 of `classes.jar` and the engine versions used at promotion. |
| `case.json` | The minimized failure case (signature, engine, focus class and method, diagnostic excerpt). |
| `context.jar` | Optional compile-only classes that the case needs on the javac classpath. |

`./gradlew test` (and `./gradlew checkRegressions`, which runs only these cases) decompiles every case again and fails when a method drops below its minimum. A method that rises is reported as `IMPROVED`; raise its level in `expected.json` in the change that fixed it. The same check is available as `classrevive lab-check benchmarks/regressions --output <new-directory>`.

**License rule.** Only commit cases derived from inputs written for this repository or under a permissive license compatible with the ClassRevive License, and say so in `provenance`. Reduced classes from third-party JARs are still derived works: keep them in `.analysis/`, which Git ignores.

To add a case: `classrevive lab <input> --output <dir>`, then `classrevive lab-minimize <dir>/failures/failure-NNNN --output <dir2>`, then `classrevive lab-promote <dir2>/minimized --output benchmarks/regressions/<name> --provenance "<source and license>"`.
