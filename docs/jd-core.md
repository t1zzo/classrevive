# JD-Core as an optional fifth engine

ClassRevive bundles four decompilers: CFR, Vineflower, Procyon and JADX. JD-Core can be added as a fifth one on your own machine.

**What it did in measurements** (made during development; the selection has changed a little since, so the numbers may move):
- **The sets:** the scoreboard's `holdout` and `holdout2` sets plus seven obfuscated jars, 23 jars in all.
- **The gains:** JD-Core recovered method bodies that none of the four did. On `holdout2` that was 529 more method bodies and 22 more classes compiling on their own, and one library compiled as a whole.
- **The losses:** JD-Core also made some jars worse. ClassRevive therefore keeps its result only when it is no worse; see "Never worse" below.

## License: you supply the jar

JD-Core is licensed under **GPL-3.0**. ClassRevive does not bundle it, download it, or compile against it.
- The JD-Core worker loads JD-Core by reflection, in a separate JVM, from the jar you name.
- Using JD-Core this way on your machine is up to you and its license. Read the license before you redistribute anything that contains JD-Core.

## Install

1. Download `jd-core-1.1.3.jar` from the project's GitHub releases (`github.com/java-decompiler/jd-core/releases`). Version 1.1.3, from December 2019, is the latest release, and it is not published on Maven Central.
2. Point `CLASSREVIVE_JDCORE_JAR` at the jar.

   Windows:

   ```bat
   setx CLASSREVIVE_JDCORE_JAR "C:\tools\jd-core-1.1.3.jar"
   ```

   Linux or macOS:

   ```bash
   export CLASSREVIVE_JDCORE_JAR="$HOME/tools/jd-core-1.1.3.jar"
   ```

3. Check it. `classrevive engines` prints `jd-core 1.1.3 (optional, ...)` when the jar is usable. It prints `jd-core not installed` when the variable is not set, and `jd-core unusable: ...` when the file is missing or is not a JD-Core jar. ClassRevive checks the jar by its entries and manifest only.

## Use

Name `jd-core` explicitly with the other engines:

```bat
classrevive decompile app.jar --engines cfr,vineflower,procyon,jadx,jd-core
classrevive benchmark samples/ --engines cfr,vineflower,procyon,jadx,jd-core
```

- **`all`:** still means the four bundled engines.
- **Without the variable:** asking for `jd-core` stops before any output is written, with a message that names the variable.
- **Desktop application:** it does not offer JD-Core yet; use the command line.

## Never worse

With `jd-core` and at least one other engine, the job runs the selection and the per-file round trip twice: once with JD-Core's sources and once without. The result with JD-Core is kept only when it is at least as good on every one of these measures and better on at least one:
- the result is complete (it compiles and every signature matches);
- it compiles as a whole;
- matched structural signatures;
- classes that compile on their own;
- method bodies identical or equivalent to the original.

Otherwise the result without JD-Core is kept, exactly as the four engines would produce it.

**Where the decision is recorded:**
- `guard/guard.txt` and the end of `selection/selection.txt` and `report.txt` record the decision with both sets of numbers.
- The run that was not kept stays in `guard/with/` or `guard/without/`.

**In the measurements:**
- **On the 23 jars:** the guard kept JD-Core's result on 9 and left it out on the rest. No measure was worse than with the four engines on any jar.

**The price:** a second selection. In the measurements the job took 2.1 times as long overall, from 1.4 to 3.2 times per jar. JD-Core itself is fast: 0.2 to 9 s per jar.

## What the worker does

The worker reads the normalized jar and hands JD-Core each class's bytes. No class of the input is defined, loaded or run. It applies the same limits and result codes as the other engines: sized heap, two processors, the job's timeout and the process-tree kill. In addition:
- **One file per class JD-Core prints on its own.** The worker reads each class's `InnerClasses` attribute to tell which those are, using JD-Core's own rule. When an obfuscator stripped that attribute, the `$` classes get their own files.
- **30 seconds per class.** JD-Core can loop forever on one class. After three classes time out, the worker stops.
- **Half surrogate pairs.** A string or char constant holding half of a surrogate pair is written as a `\uXXXX` escape, which keeps its value.
- **`MethodParameters` entries with no name** are left out of the bytes JD-Core reads. javac 21 writes such entries for mandated parameters, such as an enum's `valueOf`, and JD-Core 1.1.3 fails on the whole class when it finds one. JD-Core then names those parameters itself.
- **`module-info`** is skipped: JD-Core 1.1.3 fails on a module with no version, and the other engines write it.
- **Failures:** a class JD-Core fails on is listed in `jd-core/engine.log`, and any such failure makes the engine status `FAILED`. The classes it did write still count as a candidate.

## Limits

- JD-Core 1.1.3 has had no release since 2019. It does not parse the `Record`, `PermittedSubclasses` or nest attributes. Its `invokedynamic` support covers string concatenation and lambdas only.
- In the measurements, one class of an obfuscated jar made JD-Core loop forever, and another failed while printing a string constant.
- Nothing about behavior is verified, as with every engine: compilation, signatures and the bytecode round trip are evidence, not proof.
