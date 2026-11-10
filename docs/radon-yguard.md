# Radon 2 and yGuard: detection, reversal and corpus

**Goal:** support two more open-source obfuscators the way Skidfuscator is supported:
study each one from its source, document how its transformations look in bytecode, detect it with no false positive on
clean jars, reverse what stops reading or recompiling, run the reversal by itself in `decompile` when the diagnosis is
HIGH, and measure against an answer key.

ClassRevive never runs the analyzed code. The obfuscators are only tools, run on our own code; every reversal below was
written for ClassRevive from the behaviour documented here.

## The tools

| | Radon 2 | yGuard |
|---|---|---|
| Source | [ItzSomebody/radon](https://github.com/ItzSomebody/radon), commit `00ac9929adefedb21057868d77e9c95d2fb459c6` (2022-08-30, version 2.0.0, the last before the unfinished Radon 3 rewrite `e7caab8`; the project is archived) | [yWorks/yGuard](https://github.com/yWorks/yGuard), tag `5.0.0`, commit `16218758fcfef763322c9092405addd6556d1f91` |
| License | **GPL-3.0**: no Radon code is copied into ClassRevive and no Radon output is committed (its injected decryptors are code its generators wrote). Our passes and fixtures are written from the behaviour below | **MIT** |
| Build | `javac --release 8` of `src/main/java` against ASM 9.11.1 and SnakeYAML 1.33 (its Gradle 5.5 wrapper does not run on JDK 21) | `javac` of `src/main/java` against ASM 9.11.1 and Ant 1.10.17; run as an Ant task |

## Radon 2: transformations as they appear in bytecode

Radon sorts its transformers by `ExclusionType` before running them, so the effective order is: renamer, anti-debug,
**reference obfuscation, number obfuscation, string encryption, flow obfuscation, hide code**, expiration, shuffler,
ejector, instruction set reducer, **bad annotations, static initialization**, virtualizer, watermark, anti-tamper. Each
later transformer wraps the earlier ones' output (the owner string of an `invokedynamic` site is then string-encrypted,
its long key number-obfuscated), so the reversal runs in the opposite order.

| Transformer (config key) | Bytecode | Reversed by `radon` |
|---|---|---|
| String encryption (`string_encryption`) | `ldc "<cipher>"; <int key>; invokestatic D.m(Ljava/lang/Object;I)Ljava/lang/String;`. `D` (version 49) has a static `Map` and a static `long[]` that are never set, decoy `String.hashCode()` constants and a stack-trace read. Character `i` is XOR-ed with key `i % 4`; without `check_context` every key is the call's int, with it key `j` is the int XOR a sum of three `hashCode()`s of the caller's class (dotted), caller's method, `D` (dotted) and `m` (`cc+dc+cm`, `cm+dm+cc`, `dc+cc+cm`, `dm+cc+dc`). The decryptor's four key computations end in `<x> iload_1 ixor istore`, with `x = iconst_0` without context | yes: context read from those four shapes; any mix is refused |
| Number obfuscation (`number_obfuscation`, needs `*_tampering: true`): arithmetic, bitwise | a constant becomes `c0 (c op)* corr op` (`+ - * / %` or `& \| ^ << >> >>>`), for int, long, float, double; the bitwise pass rewrites the constants of the arithmetic one again | yes: constant chains folded with JVM semantics (no division by zero) |
| Number obfuscation: context checking | `<enc>; Integer.valueOf / Long.valueOf; <indicator>; invokestatic T.m(Ljava/lang/Object;I)Ljava/lang/Object;; checkcast Integer/Long/Float/Double; xValue()`. `T` extends `Thread` and reads the caller's method name from a stack trace; `value = enc ^ callerMethod.hashCode()` (the IEEE bits for float/double; sign-extended for long) | yes |
| Reference obfuscation, heavy (`hide_with_indy`) | every call (but constructors) and field access (not in `<init>`) becomes `ldc "owner.dotted"; ldc2_w key; invokedynamic a\|b\|d\|e\|f\|g` (static call, virtual call, getstatic, getfield, putstatic, putfield) with `(Object, Object, Object)Object` bootstrap; the site descriptor widens object parameters to `Object` and appends `(String, long)`. `key = name.hashCode() << 32 \| hash(desc)`, `hash` the XOR of `Class.getName()`-style type names (fields: of the field type). Enums are left alone | yes: the owner's (and supertypes') members from the jar or the JDK matched by both hashes and the widened descriptor; one match or nothing. A library outside jar and JDK leaves the site |
| Reference obfuscation, fast (`hide_with_fast_indy`) | `invokedynamic <name>` with a `(Lookup, String, MethodType)CallSite` bootstrap; the name is `owner\0\0name\0\0desc\0\0a\|b` XOR the `hashCode()`s of the bootstrap class (dotted), the bootstrap method and a third method of that class, cycling every 3 characters | yes: the third name is found by trying the class's method names; exactly one must give a valid descriptor |
| Flow: goto replacer | `iload p; ifeq L; aconst_null; athrow` where `p` is set at entry from a `public static final boolean` that nothing writes | yes → `goto L` |
| Flow: bogus jumps | `iload p; ifne X` before many instructions; `X` is an exit block at the start: `goto E; X: [constant] return; E:` | yes |
| Flow: bogus switch | `iload p; ifeq D; iload p; tableswitch 0..n-1 -> T* default D; D:`; each case `T: constant; istore p; goto T'` loops among the cases (a closed region) | yes (all or none per local) |
| Flow: null check mutilator | `ifnonnull L` / `ifnull L` written as `getClass/hashCode/toString/equals(null); pop` in a `try` that catches `NullPointerException` | yes |
| Flow: block splitter | the first half of a method moved after its end, joined by `goto`s (recursively, up to 11 times) | no: decompilers reorder it; generic straightening is the [control-flow pass](#relation-to-the-control-flow-pass)'s job. The reversal follows such a `goto` when it separates an injected constant from its consumer |
| Flow: fake catch blocks | `try { insn } catch (F)`, `F` an empty class of the jar extending one of 11 exception types, handler `dup; invokevirtual F.<missing>()V; athrow` (does not compile when decompiled) | yes; `F` removed |
| Hide code (`hide_code`) | `synthetic` on classes, methods (also `<init>`/`<clinit>`) and fields, `bridge` on every ordinary method: decompilers hide them | yes: cleared except on javac's own (`lambda$`, `access$`, `$values`, `this$0`, `val$`, `$SwitchMap$`, real bridges, access constructors) |
| Bad annotations (`bad_annotation`) | a visible and an invisible annotation of type `@` on every method | yes: removed |
| Static initialization (`static_initialization`) | `ConstantValue` of static fields moved to `constant; putstatic` at the start of `<clinit>` (created if needed) | yes for `static final` fields; an initializer left with only `return` goes |
| Instruction set reducer | `tableswitch` → an if-chain trampoline; `iconst_*` → `ldc` | no: compiles as is (cosmetic) |
| Renamer, member shuffler, shrinker, watermark, expiration, anti-debug, anti-tamper, ejector, virtualizer, packer, trash classes | renaming, attribute stripping, runtime checks; the virtualizer turns methods into a VM interpreter's data | not in this phase (names: like ProGuard, with Radon's `dump_mappings` file still to import; virtualizer: out of scope) |

## yGuard: transformations

yGuard is a renamer (ProGuard-like) with an optional shrinker; it injects no runtime. Default naming (`naming-scheme
small`, `language-conformity legal`) takes names of one or more characters from every character that is a legal Java
identifier on every JDK, in a scrambled order, so most names are single letters outside ASCII (`ą`, `Ω`, CJK); they
are valid Java and compile as UTF-8. `overload-enabled` (default) gives the same name to methods whose parameter lists
differ (never only the return type). `best`/`mix` and `illegal` schemes use Java keywords (`if`, `this`) and spacers,
which ClassRevive's portable names already rename for private members. The log (`yguardlog.xml`, optionally
gzipped) lists `<package|class|method|field ... map="..."/>` entries; `map` is the new name of the last segment, methods
are written `int add(int, java.lang.String)`, fields carry no type, and characters outside ASCII are `&#x....;`.

## Detection (`ObfuscationProfile`, `ObfuscatorClassifier`)

New metrics: `radon.string_decryptors`, `radon.string_sites`, `radon.number_decoders`, `radon.number_sites`,
`radon.indy_heavy_sites`, `radon.indy_fast_sites`, `radon.predicate_fields`, `radon.predicate_reads`,
`radon.fake_catch_sites`, `radon.null_check_traps`, `radon.hidden_methods`, `radon.bad_annotations`,
`names.non_ascii_short_members`, `names.non_ascii_short_classes`.

| Rule | Points |
|---|---|
| Radon: string decryptor sites +40; context number sites +35; heavy indy +40; fast indy +40; ≥3 predicate reads +25; fake catch +20; ≥3 null-check traps +15; `@` annotations +45; ≥3 non-bridge `bridge` methods +10 (with other evidence) | HIGH from 70 |
| yGuard (renaming): ≥10 member names of ≤2 characters outside ASCII +50; ≥3 such class names +20; short names +10; string encryption or hidden calls −30 each | |
| Stringer −50 and Zelix KlassMaster −40 when Radon's own shapes are present (Radon's decryptor reads the stack trace, its fast bootstrap is `(Lookup, String, MethodType)`) | |
| ProGuard/R8 −20 when ≥10 short non-ASCII member names | |

False positives: none. Over 19 clean open-source jars (asm, cfr, gson, guava 17, ant, commons-*, jna, json,
JavaEWAH, zstd-jni, ...), the Skidfuscator fixture pair and both ProGuard 8 fixtures,
every `radon.*` and non-ASCII-name metric is 0 and every other diagnosis is unchanged.

## Reversal

- `transform --passes radon` (whole-jar pass, `RadonReverser`, package `transforms/radon`), in Radon's reverse order:
  number chains folded → context-checked numbers decoded → chains folded again → strings decrypted → references
  restored → flow shapes undone (`RadonFlow`) → hide code cleared, `@` annotations removed, static constants put back →
  predicate fields, decryptor, decoder, bootstrap and fake handler classes removed once nothing refers to them. The
  report (`jar-passes.txt`) counts every step and what was left.
- `decompile --deobfuscate auto` runs it when Radon is the best hypothesis and HIGH (other diagnoses are
  unchanged); the JVM verifier links every reversed class.
- `rename --yguard-log <yguardlog.xml[.gz]>` turns the log into the same entries the ProGuard import uses and applies the
  safe subset (same-package classes, private members; field types read from the obfuscated classes, where yGuard names a
  class's fields apart). `rename.json` reports it under `"yguard"`.
- `BuiltinPasses` only gains the pass name; `skid/Hierarchy` is made public (the same change as 2DL).

## Measurement

Runs of the real Radon and yGuard on our own code are still to be done. The corpus is committed as configuration and
script only; the numbers below are on a synthetic jar that reproduces the documented shapes.

**Synthetic Radon jar** (`RadonReverserTest`: our own `App` compiled with `javac --release 8`, then every reversed shape
applied with ASM in Radon's order: heavy and fast `invokedynamic`, context-checked int/long/double, arithmetic and bitwise
chains, context-checked strings, goto replacer, bogus jump with exit block, bogus switch, both null-check forms, a fake
catch, hide code, `@` annotations, static initialization). `decompile --engines all` then `compare-reference` against the
`javac` jar:

| | `--deobfuscate off` | `auto` |
|---|---|---|
| Diagnosis | Radon HIGH 100/100 | Radon HIGH 100/100 |
| Whole compilation against the reference | `COMPILE_FAILED`, 2 errors | `COMPILED` |
| Reference signatures declared | 0/13 | **13/13** |
| Reference bodies recovered | 0/9 | **9/9** |
| Reference string literals present | 1/3 | **3/3** |
| Files naming an obfuscator class | 1/1 | 0/1 |
| Reversal | n/a | 8 strings (2 literals and 6 encrypted `invokedynamic` owners), 3 chains and 3 context numbers, 7 references (6 heavy, 1 fast), 1 goto, 1 bogus jump and its exit block, 1 bogus switch, 2 null checks, 1 fake catch, 15 hidden flags, 18 `@` annotations, 2 constants; 5 injected classes and 2 predicate fields removed |

`YguardLogRenameTest` renames a two-class jar (a nested class, private fields and a private method) in yGuard's default
style and restores every name from a gzipped log.

To measure the real tools:

```
python scripts/benchmarks/obfuscators.py --tool radon --config benchmarks/obfuscated/radon/radon-full.yml --sets fixture,inventario,dev --output build/obf-bench/radon-full
python scripts/benchmarks/obfuscators.py --tool yguard --config benchmarks/obfuscated/yguard/yguard.xml --sets fixture,inventario --output build/obf-bench/yguard
```

## Relation to the control-flow pass

The generic [control-flow pass](control-flow.md) straightens Radon's block splitting and folds what
remains after a predicate local could not be removed (its reads then load `0`). The Radon pass does not duplicate it.

## Limits

- Static reversal only; behaviour is not verified. A shape that differs from the documented one is left and counted.
- Radon's heavy sites whose owner is a library outside the jar and the JDK, its `super.m()` calls (Radon itself turns
  them into virtual calls), string pooling, the renamer's names, ejector, virtualizer and instruction set reducer are not
  reversed.
- The yGuard import has the ProGuard import's limits (non-private members and cross-package classes need hierarchy-aware
  renaming).
- Radon and yGuard have not been run on the corpus yet (see Measurement); the detection thresholds come from their source.
