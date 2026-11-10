# yGuard corpus configuration

[Radon and yGuard](../../../docs/radon-yguard.md): answer-key measurement for [yGuard](https://github.com/yWorks/yGuard) (MIT),
pinned to tag `5.0.0` (commit `16218758fcfef763322c9092405addd6556d1f91`).

- `yguard.xml`: an Ant build with yGuard's default renaming (naming scheme `small`, language conformity `legal`) and its
  log; it keeps `fixtures.skid.Main` and every `main(String[])`. `${input}`, `${output}`, `${log}` and `${libraries}`
  are set by the script.

```
gradlew :cli:installDist
python scripts/benchmarks/obfuscators.py --tool yguard --config benchmarks/obfuscated/yguard/yguard.xml --sets fixture,inventario --output .analysis/obf-bench/<name>
```

The script builds yGuard from the pinned commit with `javac` (ASM 9.11.1 and Ant 1.10.17 from the Gradle cache), runs it
through Ant's launcher in its own JVM, restores names with `classrevive rename --yguard-log`, decompiles and compares with
the original jar. yGuard runs only as a tool on our own code; nothing it produces is committed here.
