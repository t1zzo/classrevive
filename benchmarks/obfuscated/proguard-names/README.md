# ProGuard name-inference fixtures (`names-debug`, `names-default`)

Real ProGuard 7.10.0 output for the Phase 2DK name inference. `src/app/` holds seven small classes written for
ClassRevive (players, items, a team, a config reader, a Runnable job, an exception and a kept `Main`) whose private
fields and methods have readable names, so the mapping file is a complete answer key.

| Jar | Attributes kept | Used for |
|---|---|---|
| `names-debug.jar` | real `SourceFile`, `LocalVariableTable` | the best case: class names from the file name, field names from parameters |
| `names-default.jar` | `SourceFile` renamed to "SourceFile", no local variable table | the usual release configuration |

Both are compiled with `javac --release 8` (so string concatenation is a `StringBuilder` chain), renamed only (`-dontoptimize
-dontshrink`), and only `app.Main.main` is kept. Regenerate with `gradlew regenerateNameFixtures`; `gradlew verifyNameFixtures`
fails when a fresh run no longer matches the committed files. `NameInferenceCorpusTest` (cli) infers names over both jars and
compares every one with the mapping's original name.
