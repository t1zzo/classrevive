# Skidfuscator fixtures and corpus (`skid-fixture*.jar`)

An answer-key corpus for Skidfuscator, the free open-source (MIT) Java obfuscator.

- `src/` is authored for ClassRevive (package `fixtures.skid`): instance methods called inside the jar, loops,
  `try`/`catch`/`finally`, an enum with a constant body, an interface hierarchy with `instanceof`, switches on `int`,
  `char` and `String`, `equals`/`equalsIgnoreCase` against literals, constants of every width, lambdas, method
  references and a static initializer. It exercises every transformer of Skidfuscator's community edition.
- `skid-fixture.jar`: `src/` compiled with `javac --release 8 -proc:none`, entries sorted with fixed timestamps (the
  answer key). SHA-256 `cb3547c2272a77457c3f312c807195c69ccbbe65e638975971c977da5ed28f9b`.
- `skid-fixture-obf.jar`: one Skidfuscator 2.1.0 run over it (commit `e4c0f981911703e86ac8a2b1a77d7cfd18b9d719`,
  Skidfuscator's default configuration; at that time the default exclusions, which do not touch `fixtures.*`).
  SHA-256 `7b3cbb0f686656c5f6c47ab9485022d236e1f4a0302f58882a39db551690aa23`. Skidfuscator draws every key from an
  unseeded `java.util.Random`, so a new run gives different bytes; this one is kept for deterministic tests.
- `skidfuscator.hocon`: the corpus configuration: Skidfuscator's `defaultConfig.hocon` with `exclude` limited to the JDK
  packages (its default list also skips `com.google`, `org.apache`, `org.json`, `org.objectweb`, `net.sf`, ...).
- `skidfuscator-libraries.hocon`: the same without "Pure Encryption" for library-sized jars, where Skidfuscator 2.1.0
  runs out of hash candidates and throws.

## Regenerating and measuring

```
gradlew :cli:installDist
python scripts/benchmarks/skidfuscator.py --sets fixture,dev,holdout --runs 3 --output .analysis/skid-bench/<name> [--passes skidfuscator]
```

The script clones and builds Skidfuscator at the pinned commit under `.analysis/`, refuses any other checkout, obfuscates
each sample (always with `-notrack`: Skidfuscator otherwise sends analytics; inside the case folder, where it writes error
dumps), optionally runs `classrevive transform --passes ...`, then `decompile --engines all` and
`compare-reference` against the original jar. `summary.json` records the SHA-256 of every input and output. Nothing
obfuscated by the script is committed.
