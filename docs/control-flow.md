# General control-flow simplification (`control-flow`)

**Goal:** a bytecode pass that undoes control-flow obfuscation whatever tool produced it, changing only what it proves
statically. The Skidfuscator (2CN), Aidsfuscator (2CZ) and ZKM (2CM) reversals each know their obfuscator; this pass knows
none. Target code is never loaded, initialized or run.

## What it does

`classrevive transform <jar> --output <dir> --passes control-flow` (a whole-jar pass; after `skidfuscator` or
`aidsfuscator` when combined). Package `dev.classrevive.transforms.flow`:

| Class | Role |
|---|---|
| `FV` | abstract value: an `int` interval, a parity (a constant bit, or "parity of value *v* flipped by *k*"), a value number (two slots with the same number hold the same run-time value; a number is stripped everywhere when its producer runs again), `long` constants, reference nullness and string constants, and two evidence flags (below) |
| `FlowInterpreter` | transfer functions; exact on constants (`skid.Interpret`), intervals and parity otherwise; static calls with constant primitive arguments evaluated by `skid.PureEvaluator` when their body is pure; `String.length/charAt/hashCode` on constants |
| `FlowAnalyzer` | forward analysis that follows only feasible edges, narrows the tested value (every slot with its value number) on each edge, widens growing intervals along back edges. Handlers that can catch a `VirtualMachineError` (any-type, `Throwable`, `Error`, ...) are reached from every covered instruction |
| `JarFacts` | `static final` fields proved constant: a `ConstantValue` and no `putstatic` in the input, or one literal store in the straight-line prefix of the holder's `<clinit>` with no supertype initializer in the input that could call in first |
| `ControlFlowPass` | rewrites each method round after round, checks it with ASM's `BasicVerifier` (restored if it fails), writes changed classes with frames computed from the input's hierarchy (`skid.Hierarchy`) |
| `Cfg` | helpers extracted from the Skidfuscator reversal's `MethodRewriter` (which now delegates to them): `canThrow`, jump-chain `resolve`, `prune`, `jumpTargets` |

Rewrites:

- **Opaque predicates**: a branch or switch with one feasible successor becomes `pop; goto` (or falls through); unreachable
  switch cases are dropped. Proved over constants and arithmetic on them, proved `static final` fields, pure calls,
  intervals (`arraylength >= 0`, `i2b`, `& mask`, `% c`), parity (`x * (x + 1)` and `x * x + x` are even), value numbers
  (`x - x`, `x ^ x`, `x == x`) and nullness.
- **Flattened switches**: an edge into a dispatcher (side-effect-free code from a label to a switch) on which the key is
  one constant goes straight to that case. An edge whose state is not constant is left alone and counted
  (`dispatcher edges left (state not constant)`): ambiguous means refused.
- **Flow by exception**: `new X; dup; invokespecial X.<init>()V; athrow` on an empty stack, whose first matching handler
  in the method (by the exception table order and `X`'s fully known superclass chain) starts by discarding the exception
  (`pop`, or `astore` to a local nothing reads), becomes `goto` past the discard. `X` must be a JDK class, or input
  classes whose no-argument constructors only chain up and that have no static initializer.
- **Dead code**: unreachable instructions, handlers nothing reaches, try ranges left empty.
- **Clean-ups** (only in a method that already has evidence): jump chains, jumps to the next instruction, `goto` to a
  lone return (same try coverage), pushes popped at once, stores nothing reads (and their local variable entries), try
  ranges where nothing can throw that cannot catch a `VirtualMachineError`, handlers that only rethrow (outside every try
  range, with no later overlapping entry), line numbers left past the code.

Every change is in `transform.json` (one row per method: `left` = branches and switches decided or pruned, `right` =
dispatcher edges threaded plus throw-gotos, `result` = all edits) and the totals and refused methods (with the reason)
in `jar_passes`. The JVM verifier then links every class (`link_check`).

## Not touching clean code: two levels of evidence

The first run changed clean libraries: 78 methods of jackson-databind, 33 of Ant, 31 of commons-compress, 16 of ASM, and
more (22-jar scoreboard: 250 methods in 17 jars). The proofs were right but the code was not obfuscated: javac's try-with-resources
null-checks a resource it just created, `switch (b & 0xFF)` has cases a byte never reaches, Ant and commons-compress have
hand-written state machines (`state = 2; break;` back to `switch (state)`), and javac writes `goto` chains and
`catch (E e) { throw e; }`.

So a method is rewritten only when it holds **evidence**, something javac never writes:

- a test decided on a value that is *literal* (pushed by a constant instruction and tested directly: javac folds that) or
  *opaque* (an operation on literals, a read of a proved `static final`, which javac would have inlined, an evaluated pure
  call or string operation, an algebraic identity, a parity collapse);
- a test only value numbers or parity decide (intervals alone do not);
- code no path at all reaches (the verifier's view, with every covered instruction reaching its handlers);
- a throw-goto;
- a dispatcher edge whose key is opaque, or a dispatcher with random keys (some `|key| >= 2^16`, as obfuscators draw them).

Everything else (interval and nullness decisions, small-state dispatchers, the clean-ups) is applied only in a method that
has evidence. After this, the same run changes **0 methods in all 22 scoreboard jars**, and every jar still links.

## Measurements

Scoreboard (dev, holdout, holdout2; 22 jars, 78 140 methods analyzed), `transform --passes control-flow`:

| | Before the evidence gate | After |
|---|---:|---:|
| methods changed | 250 (17 jars) | **0** |
| bytecode errors in the link check | 1 (Gson: `Invalid pc in LineNumberTable`) | 0 (5 jars inconclusive, `NoClassDefFoundError`: their dependencies were not passed to `transform`; no class of theirs changed) |
| methods refused | 1 (Caffeine, local variable type annotations) | 1 (same) |

Own corpus (`benchmarks/obfuscated`, plus the local Aidsfuscator run of 2CZ), pass alone:

| Input | Methods changed | Decided branches / switches / pruned | Dispatcher edges threaded (left) | Dead instructions | Link |
|---|---:|---|---:|---:|---|
| `skid-fixture-obf.jar` (Skidfuscator) | 29 of 488 | 43 / 26 / 5 | 0 | 1647 | all 46 classes |
| Aidsfuscator `out.jar` | 12 of 73 | 0 / 0 / 23 | 465 (21) | 126 | all 12 classes |
| `skid-fixture.jar`, Aidsfuscator `in.jar` (clean) | 0 | | | | all |
| ProGuard `obf8.jar`, `obf8-opt.jar` (no flow obfuscation) | 0 | | | | all |
| after `skidfuscator` / `aidsfuscator` | 0 (the dedicated reversal left nothing) | | | | all |

Decompiling with `--engines all --deobfuscate off` and `compare-reference` against the answer key, before and after the pass:

| | Skidfuscator before | after | Aidsfuscator before | after |
|---|---:|---:|---:|---:|
| whole compilation errors | 24 in 3 files | 21 in 3 files | 2 in 1 file | 13 in 2 files |
| files compiled one by one | 21/24 | 20/24 | 0/7 | 0/7 |
| reference bodies reproduced | 1/42 | 0/42 | 0/42 | 0/42 |
| reference strings present | 1/30 | 1/30 | 4/30 | 6/30 |
| per-file round trip (vs. the decompiled classes) | 387 same, 28 different | 372 same, 9 different | 0 | 0 |

The answer-key numbers are dominated by what a control-flow pass cannot undo: encrypted strings and numbers, injected
parameters and `invokedynamic` references, which the dedicated reversals handle (2CN, 2CZ). The bodies the engines print
are closer to their bytecode (different bodies 28 → 9), but a file is lost on Skidfuscator and Aidsfuscator's whole
compilation gets worse (recorded in the failure log).

## `--deobfuscate auto`: not included

The pass cannot hurt the scoreboard (it changes nothing there), but on the obfuscated corpus it does not improve the
answer-key results by itself, it costs one worker JVM and a link check per input, and after the dedicated reversals it finds
nothing. It stays opt-in (`transform --passes control-flow`); revisit when an obfuscator without a dedicated reversal
([Radon, yGuard](radon-yguard.md)) is measured with it.

## Assumptions (stated in the report)

No code outside the bytecode writes a `static final` field (no `Unsafe`, no JNI; reflection cannot). A
`VirtualMachineError` may occur anywhere, so handlers that catch one keep every covered instruction as a source. Constructing
a JDK exception with its no-argument constructor has no effect beyond the object.

## Tests

`ControlFlowPassTest` (20, ASM-generated classes, the JVM verifier links each output): constant-value and initializer-prefix
fields, a field read through a call before its store (not proved), parity, `arraylength`, value numbers, narrowing,
pure calls and string constants, null tests (literal vs. a fresh object in clean code), junk code, throw-goto (and a
handler that uses the exception: unchanged), rethrow-only handlers (removed with evidence, kept in clean code), a
dispatcher with random keys (unflattened), with an ambiguous entry (only constant edges threaded), a hand-written state
machine (unchanged), `jsr` (refused, unchanged), dead code with a trailing line number, and eight JDK classes (unchanged).
