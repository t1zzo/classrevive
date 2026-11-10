# Radon 2 corpus configuration

[Radon and yGuard](../../../docs/radon-yguard.md): answer-key measurement for Radon 2 ([ItzSomebody/radon](https://github.com/ItzSomebody/radon),
GPL-3.0, archived), pinned to commit `00ac9929adefedb21057868d77e9c95d2fb459c6` (2.0.0, the last before the Radon 3 rewrite).

- `radon-full.yml`: every transformer ClassRevive reverses: heavy `invokedynamic` references, context-checked and
  arithmetic/bitwise numbers (int, long, float, double), context-checked strings, all six flow transformers, hide code,
  static initialization and bad annotations. `{input}`, `{output}` and `{libraries}` are filled in by the script.
- `radon-fast.yml`: the same with "fast" `invokedynamic` and strings without context checking.
- Samples: the authored Skidfuscator fixture (`../skidfuscator/skid-fixture.jar`, package `fixtures.skid`), the local
  `test-inputs/primeiro/inventario.jar` when present, and the scoreboard sets; the answer key is always the original jar.

```
gradlew :cli:installDist
python scripts/benchmarks/obfuscators.py --tool radon --config benchmarks/obfuscated/radon/radon-full.yml --sets fixture,inventario,dev --output .analysis/obf-bench/<name>
```

The script clones Radon at the pinned commit under `.analysis/tools/`, compiles it with `javac --release 8` against
ASM 9.11.1 and SnakeYAML 1.33 from the Gradle cache (its Gradle 5.5 wrapper does not run on JDK 21), gives it the JDK's
`java.base` classes as a library jar (`jmod extract`), and runs it in its own JVM. Radon runs only as a tool on our own
code. **Nothing it produces is committed**: Radon is GPL-3.0 and its output carries decryptor and bootstrap classes its
generators wrote. The reversal's tests use a synthetic jar written for ClassRevive in Radon's shapes
(`modules/transforms/src/test/java/dev/classrevive/transforms/radon/RadonReverserTest.java`).
