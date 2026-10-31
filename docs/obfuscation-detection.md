# Static obfuscation detection

ClassRevive recognizes obfuscation techniques by static analysis and suggests which obfuscator the pattern points to. It never runs the analyzed code: ASM parses the class bytes, and no class is defined, loaded or initialized; no bootstrap method or string decryptor is evaluated by the detector. The suggestion is a hypothesis from the documented rules below, never proof. `none` (no technique) and `unknown` (techniques, no matching rule) are valid answers.

The detector is `ObfuscationProfile` (module `bytecode`); the rules that turn a profile into hypotheses are `ObfuscatorClassifier` (same module); the readable and JSON forms are `ObfuscationSummary` (module `pipeline`).

## 1. Detector (`ObfuscationProfile`)

`ObfuscationProfile.Builder` takes class bytes (`addClass`) and resource names and sizes (`addResource`; a resource is never read). `build()` applies each technique's threshold. Detected techniques have a class count, an occurrence count and up to 10 samples (class, method and descriptor). Every raw indicator stays in `indicators`, detected or not.

String literal contents are never reported. The only literal values that reach a report are cipher algorithm names from a fixed list (`DES`, `DESede`, `AES`, `Blowfish`, `RC4`, ...) and the fixed word `ALLATORI`.

| Technique | Counted when | Detected when |
|---|---|---|
| `STRING_ENCRYPTION` | a `<clinit>` that calls JCE (`Cipher`, `SecretKeyFactory`, `KeyGenerator`, `javax/crypto/spec`) or XORs, and stores into a static `String`/`String[]` field; a **decryptor**: a static method returning `String`, with 1–4 parameters of type `I J S B C String Object char[] byte[]`, that XORs or calls JCE and builds a string (`new String`, `toCharArray`, `charAt`, `valueOf`, `intern`) or reads a static `String[]`; calls to decryptors, except calls from a bootstrap or call-site handler (first parameter `MethodHandles.Lookup`), which only forward (`strings.decryptor_forwarding_calls`); **invokedynamic sites** whose descriptor is `(...)Ljava/lang/String;` and equals the descriptor of a decoder that the site's bootstrap reaches (see `CONSTANT_ENCRYPTION`); Skidfuscator's seed-keyed decryptor sites | a `<clinit>` decryption, 3 decryptor calls with a literal argument, 3 calls to a decryptor whose parameters are all primitive (`(IJ)`, `(II)`), or 3 invokedynamic sites whose bootstrap reaches a decoder. A `(String)String` helper such as a case-conversion utility also XORs and builds strings; it counts only with literal arguments |
| `CONSTANT_ENCRYPTION` | invokedynamic sites with descriptor `(...)I` or `(...)J` equal to the descriptor of a decoder that the site's bootstrap reaches. A **decoder** is a static method with 1–4 `I J S B C` parameters, returning `String`, `int` or `long`, that XORs or calls JCE; the bootstrap (`(Lookup, String, MethodType...)`) **reaches** it through static calls and `ldc` method handles inside its own class, at most 3 steps (for example bootstrap → `ldc` handler `(Lookup, MutableCallSite, String, Object[])` → decoder `a(IJ)`) | 3 sites. Indicators `constants.indy_int_sites`, `constants.indy_long_sites` |
| `HIDDEN_CALLS` | invokedynamic whose bootstrap owner is outside the JDK (`java/`, `javax/`, `jdk/`, `sun/`, `com/sun/`) and outside language runtimes (Groovy, Scala, Kotlin, Clojure, JRuby); lambdas and string concatenation are never counted. Encrypted-value sites are hidden calls too | any site. Indicators: bootstrap shape `(Lookup, String, MethodType)` exactly or with extra parameters, and whether the owner is a class of the jar |
| `SHORT_NAMES` | a class whose own simple name has at most 2 characters (anonymous `$1` excluded), or with at least 3 members of which 60% have at most 2 characters | at least 5 classes and 10% of the classes |
| `NON_IDENTIFIER_NAMES` | a class name segment, method or field name that is no Java identifier or is a reserved word. Excluded: local classes (`Outer$1Local`), `package-info`/`module-info`, Kotlin's mangled names (`constructor-impl`, `getValue-UwyO8pc`, also with a `$suffix` after the hash), and Java keywords used as member names in a class carrying `kotlin.Metadata` | any |
| `CASE_COLLISIONS` | class names equal ignoring case (`app/A`, `app/a`) | any group |
| `STRIPPED_ATTRIBUTES` | a `$` class without `InnerClasses` (and without `NestHost`), a class without `SourceFile`, a class with code but no `LineNumberTable` | a stripped `InnerClasses`, or `SourceFile` or `LineNumberTable` missing from half the classes. `javac -g:none` also strips the latter two, so this alone is no obfuscator evidence. A missing `LocalVariableTable` is only an indicator: javac omits it by default |
| `EXCEPTION_OBFUSCATION` | an exception handler whose first instruction is `invokestatic` of a `(LX;)LX;` method followed by `athrow`, where that method is a static identity helper of the class set (`aload_0; areturn`). javac always stores the caught exception first (`astore`), even for `catch (E e) { throw wrap(e); }` | 10 handlers in 2 classes |
| `NATIVE_LAYER` | a native method whose name is no identifier or is 16+ hex digits; a known native-obfuscator loader (`native0/Loader`, `JNIC*`); bundled `.dll`/`.so`/`.dylib`/`.jnilib` (indicator) | an obfuscated native name or a loader. Ordinary JNI bindings (identifier names) beside a bundled library are not a protection layer |
| `CONTROL_FLOW` | conditional jumps on constants (`ICONST_1; IFEQ`) with no jump target between the constant and the test; switch dispatch loops (a switch with at least 4 cases that at least 4 later `GOTO`s jump back to); methods with at least 30 instructions of which a quarter are `GOTO`, not counting forward gotos to a label that at least three forward gotos share; a static flag of the class loaded into a local at method entry (in at least a third of a class's methods); a **flag accessor** (a static `()[X`/`()Z`/`()I` method of the class set whose body is `getstatic; return`) whose result is stored in a local that is only ever loaded straight into `ifnull`/`ifnonnull` (array) or `ifeq`/`ifne` (`Z`/`I`); Skidfuscator's self-looping hash switches | 3 constant conditions, 2 dispatch loops, 3 goto-heavy methods, 3 flag classes, or flag-accessor locals in 10 methods of 3 classes. `GOTO` chains (a `GOTO` to a `GOTO`) are only an indicator: javac emits them too, for `finally` and try-with-resources exits |

The two exclusions in `CONTROL_FLOW` keep compiler output out: a materialized boolean (`iload x; ifeq Z; iconst_1; goto M; Z: iconst_0; M: ifeq ...`, as kotlinc writes it) reaches the test through the merge label `M`, and a `when`/`switch` expression with many arms compiles to one `goto exit` per arm, all to the same label.

Other indicators feed the classifier:

- `zkm.long_key_methods` (a method XOR-ing a `long` parameter with a `long` constant), `cipher.<ALGORITHM>`, `strings.decryptor(<shape>)`;
- `debug.sourcefile_renamed` (`SourceFile` set to `"SourceFile"` or an `r8-map-id-…`), `names.short_packages`;
- `marker.allatori`, `marker.stringer_stacktrace` (a decryptor that reads the stack trace), `marker.dasho` (references to `com/preemptive/`), `native.load_calls`;
- `strings.indy_sites`, `indy.decoding_bootstraps`, and `strings.native_key_classes`: a class that decodes values, by a decryptor or a decoding bootstrap, while its `<clinit>` is only constants and static calls, at least one of them to a native method of the class (the keys and tables come from native code);
- `cf.flag_accessors`, `cf.flag_accessor_methods`, `cf.flag_accessor_tests`, `cf.flag_accessor_classes`, `exceptions.identity_helpers`, `exceptions.rethrow_handlers`, `exceptions.rethrow_classes`;
- Skidfuscator: `skid.self_loop_hash_switches`, `skid.seed_methods` (methods with at least 3 `seed = C ^ seed` updates), `skid.seed_string_decryptors` and `strings.skid_sites`, `skid.random_seed_fields`, `skid.parseint_seeds`, `skid.exception_gotos`, `marker.skidfuscator_sdk` (`sdk/SDK`), `marker.ahegao` (its `nothing_to_see_here` marker field), `skid.sdk_calls`;
- Aidsfuscator: `aids.salt_dispatcher` (an FNV-1a class-name hash with a salt retriever), `aids.reference_bootstrap` (a `MutableCallSite` bootstrap with an `(int, int)` reference decoder), `aids.hash_integrity` (SHA-256 hashes of the class files read from a `.bin` resource), `aids.stacktrace_string_decryptors`, `aids.lookup_class_retrievers` (`<clinit>` salts read through `MethodHandles.lookup().lookupClass()`).

## 2. Classifier (`ObfuscatorClassifier`)

Each rule adds points for its evidence and subtracts points for evidence pointing elsewhere. The score is bounded to 0..100. A hypothesis is reported from 30 points: `LOW` below 45, `MEDIUM` below 70, `HIGH` from 70. Hypotheses are sorted by score, and every reason carries its points.

| Hypothesis | Points |
|---|---|
| **Zelix KlassMaster** | +25 hidden calls through a jar-owned `(Lookup, String, MethodType)` bootstrap; +25 `(IJ)`/`(JI)` string decryptors; +10 `(II)` decryptors; +20 DES/DESede with `<clinit>` decryption (+10 for another JCE algorithm); +10 three or more long-key methods; +5 static flags at method entry with control-flow obfuscation; +10 flag-accessor opaque predicates in 10 methods; +15 exception obfuscation; +10 int/long constant encryption with a `(Lookup, String, MethodType)` bootstrap; +10 short or case-colliding names (only with other evidence); −30 with an Allatori or Stringer marker. Flag accessors and exception obfuscation together give 25 points, below the reporting threshold: they confirm the hypothesis, they do not suggest it alone |
| **Allatori** | +80 `ALLATORI` marker (names or literals); +25 `(String)String` decryptors called with literals; +5 short names (with the marker); −20 without the marker when `(IJ)` decryptors or `(Lookup, String, MethodType)` bootstraps point to another tool |
| **Stringer** | +50 decryptors reading the stack trace; +20 `(Object)String` decryptors; +15 hidden calls (only with other evidence) |
| **DashO (PreEmptive)** | +70 references to `com/preemptive/`; +10 string encryption (with the marker) |
| **ProGuard / R8 (renaming only)** | +35 short names; +15 five classes in packages whose segments have at most 2 characters (`a/a/a`); +25 renamed `SourceFile`; +5 no `LocalVariableTable` in half the classes; −30 string encryption, −30 hidden calls, −15 non-identifier names (ProGuard and R8 have none of them by default) |
| **custom native protection layer** (or **JNIC / native-obfuscator style** with a known loader) | +40 obfuscated native names; +40 a known loader; +25 bundled native libraries, +10 `System`/`Runtime` `load` calls and +10 decryption keys supplied by the native layer (all three only with other evidence) |
| **Skidfuscator** | +35 two or more self-looping switches on a hash of the seed; +20 seed updates in 3 methods; +15 three seed-keyed decryptor sites; +10 `Random(long).nextInt()` seed fields; +5 `Integer.parseInt("digits")` seeds; +10 caught `throw new X()` gotos (only with other evidence: javac output can contain a few); +25 `sdk/SDK`; +25 `nothing_to_see_here` marker |
| **Aidsfuscator** | +30 salt dispatcher with an FNV-1a class-name hash; +25 `MutableCallSite` reference bootstrap with an `(int, int)` decoder; +20 hash integrity class; +15 two or more stack-trace string decryptors; +10 two or more `lookupClass()` salt reads |

`verdict` is `SUGGESTED` with at least one hypothesis, `NONE` without techniques and hypotheses, `UNKNOWN` otherwise.

## 3. Output

- `diagnose`: `diagnostics.json` gains `obfuscation` (`techniques`, `indicators`, `verdict`, `suggested_obfuscator`, `hypotheses`, `summary`, `interpretation`). The readable form is in `obfuscation.txt` and on the console. The profile is built in the same sandboxed worker as the dynamic linkage scan (`obfuscation.bin`, a bounded format read strictly by the parent) and classified in the parent.
- `decompile`: the profile of the original classes (before portable renames) is built in the parent with ASM, from the bytes read once. `report.txt` gets the readable block before the engine lines, and `report.json` gets `obfuscation`. A Skidfuscator or Aidsfuscator diagnosis with `HIGH` confidence lets `decompile --deobfuscate auto` (the default) run that reversal before the engines.
- The desktop application shows the same summary and hypotheses in its detection view.

The readable block has one line per technique and the suggestion, in this form:

```
Obfuscation (static, N classes):
  string encryption: C classes, S decryption sites (X invokedynamic, Y direct calls, Z <clinit>); keys supplied by the native layer in K classes
  constant encryption (int/long values decrypted at run time): S sites in C classes (I int, L long)
  hidden calls (invokedynamic with a non-JDK bootstrap): S sites in C classes
  ...
Suggested obfuscator: <hypothesis> (<confidence>, <score>/100); ...
```

## 4. How the rules are checked

- `ObfuscationProfileTest` builds every shape with ASM (nothing is loaded): a clean class set gives `NONE`; ZKM-like, Allatori, Stringer, DashO, ProGuard-style, native-layer, Skidfuscator and Aidsfuscator shapes give their hypothesis; javac-shaped and kotlinc-shaped code (materialized booleans, `when` exits, keyword member names, JNI bindings, natives registered without decoding) gives nothing.
- Clean library jars (Apache Commons, Guava, Gson, Ant, ASM, CFR, JNA, zstd-jni, the Kotlin standard library, ...) are expected to give `none`. Each false positive found on such a jar was fixed in the rule, not by an exception for that jar.
- The committed Skidfuscator fixture (`benchmarks/obfuscated/skidfuscator/skid-fixture-obf.jar`) is diagnosed as Skidfuscator with `HIGH` confidence, which is what lets `decompile` reverse it automatically (`AutoDeobfuscationTest`, `ObfuscationCheckTest`).

## 5. Limits

- Every rule is a heuristic on static patterns. Obfuscators share techniques, change between versions and can be chained; a hand-written XOR helper with key parameters can look like a decryptor. Hypotheses are suggestions with their reasons, never proof.
- Rules exist only for the tools listed above. Other tools (Radon, Branchlock, Paramorphism, ...) show up as `UNKNOWN` with their techniques counted.
- Product names are used only to describe the patterns the rules look for; they belong to their owners, and ClassRevive is not affiliated with any of them.
- Control-flow detection is cheap and local (no data-flow analysis). Opaque predicates computed through fields or arithmetic are not recognized; a flag local is followed only when every load of it feeds a test directly.
- Decryption sites are counted, never decrypted, by the detector. Bootstraps that reach a decoder of another class are not followed.
- Indicators are counted over every class, including shaded libraries; samples show which classes they come from.
