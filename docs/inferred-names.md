# Inferred names (`decompile --infer-names`)

Obfuscators leave names such as `a`, `b`, `aa`. ClassRevive guesses readable names from bytecode evidence (never by running
anything) and applies them with the existing rename pass. Every name is **inferred**: it carries a rule, its evidence and a
confidence, and is listed in `inferred-names/` and `report.json` (`inferred_names`). Off by default
(`--infer-names off|high|medium|low`; `true` = `low`); it keeps guesses down to the given confidence.

## Evidence

| Target | Rule | Confidence |
|---|---|---|
| class | `toString` literal `Player{...` | HIGH |
| class | SourceFile (unique, or the file's public class) | HIGH / MEDIUM |
| class | `public static void main` | MEDIUM |
| class | known supertype (Runnable gives `Task`, exceptions, listeners) | MEDIUM / LOW |
| field | label before it in a `toString`/message (`", age=" + age`) | HIGH (toString) / MEDIUM |
| field | parameter name assigned to it (LocalVariableTable), `requireNonNull(p, "name")` | HIGH |
| field | key it is read from / written to (`getProperty("host")`, `put("label", x)`) | MEDIUM |
| field | declared type (`List<Player>` gives `players`) | LOW |
| method | getter, setter, adder, remover of a field with a readable name | HIGH to LOW (follows the field) |
| local / parameter | type, only when the LocalVariableTable names are obfuscated | LOW |

## Safety

Only classes of the input and **private** fields and methods are renamed (never inherited or overridden, so all references
are rewritten). Left alone: readable names, Serializable classes/members, native methods, annotated fields, names that also
occur as string constants, jars using member reflection/Gson/Jackson, classes named by a resource (manifest, services,
plugin.yml) or any class of an Android conversion, and methods of classes with a supertype outside the input and the JDK.
The plan is validated by `ClassRenamer`; if applying it fails the job drops the guesses and continues.
Non-private members need hierarchy-aware renaming, which is not done yet.

## Outputs

`inferred-names/names.json`, `mapping.txt` (rename mapping syntax, usable with `rename --mapping`; `option locals = infer`),
`names.tsv`. The rename mapping is also in `portable-names/mapping.txt`, and `portable_names.renamed_classes` lists every class
rename (portable and inferred) so tools can map original names to the engines' names. The round trip measures the renamed classes.

## Measurements (real ProGuard 7.10 on `benchmarks/obfuscated/proguard-names`, answer key = mapping.txt)

| Jar | Inferred | Exact | HIGH |
|---|---:|---:|---:|
| names-debug (SourceFile + LVT kept) | 27 | 26 (96%) | 15/15 |
| names-default (usual release config) | 24 | 19 (79%) | 8/8 |
| obf8, obf8-opt | 0 | n/a | n/a |

Wrong guesses: `serverPort` for `port`, `unitPrice` for `price`, `CustomException`/`Task` for `AppException`/`Job` (LOW/MEDIUM).
The sources are written for this corpus, so rates on real programs may be lower. Linked and decompiled output still round-trips.
