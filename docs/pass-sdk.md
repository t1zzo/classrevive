# Static pass API (experimental v1)

Implement `BytecodePass` with a stable lowercase identifier and `apply(byte[], int)` returning `TransformResult`. Supply trusted pass instances explicitly to `PassPipeline`. No automatic plugin discovery, downloaded code or target classes are loaded. Third-party pass code is executable Java and must be reviewed as trusted tooling; this API is not a sandbox for malicious plugins.

Contract:
- Input/output class bytes are bounded to 16 MiB and total changes to the caller budget.
- A pass must not execute or define target classes, resolve targets through reflection, or perform network requests.
- Every modification must have an audit record attributed to that pass. Zero records requires byte-identical output.
- Return defensive copies; the pipeline clones between stages and rejects oversized output, excess changes and foreign pass IDs.
- Preserve behavior and unknown metadata, or leave the input unchanged. Structural/behavioral validity cannot be established by this interface alone.
- `instructionIndex` is an ASM tree-node index relative to that pass's input. It is not a byte offset or a global original-source location.

The pipeline runs each pass once in the given order; it never repeats until convergence. This bounds orchestration and makes ordering explicit. Pass code itself must bound its work; the shipped CLI additionally uses a timed worker JVM. The change budget is shared across passes and classes. `changeLimitReached` indicates the budget was consumed, not that further opportunities definitely exist.

Example:
```java
var pipeline = new PassPipeline(BuiltinPasses.select("integer-unary,integer-constants"));
TransformResult result = pipeline.apply(classBytes, 1000);
```

CLI:
```bat
classrevive transform sample.jar --output simplified --passes integer-unary,integer-constants,integer-branches
```

Built-ins: `integer-constants` folds adjacent two-integer arithmetic/bitwise/shift operations; `integer-unary` folds INEG/I2B/I2C/I2S on adjacent integer literals using JVM overflow/narrowing rules; `integer-branches` folds adjacent literal operands for unary `IF*` and binary `IF_ICMP*` comparisons. It does not cross labels, frames or debug nodes, and it leaves reference comparisons and annotated instructions unchanged. Branch audit records preserve the comparison opcode, operands and a result of `1` (taken) or `0` (fallthrough). Unary audit records use `left` for the operand and `right=0`. Default remains `integer-constants` for compatibility. Unknown or duplicate names are rejected before creating output.

The opt-in `string-decryptor` uses a separate strict, bounded static interpreter; it supports only the bytecode/JDK subset listed in the [command-line reference](cli-reference.md#static-simplification), not arbitrary decoder methods. These passes do not provide variable-based branch analysis, general control-flow unflattening or behavioral equivalence. An external plugin loading/packaging protocol remains future work.

## Literal-string partial evaluation

Opt-in `string-length` and `string-char-at` resolve exact adjacent literal calls to `java/lang/String.length()I` and `java/lang/String.charAt(I)C`. The evaluator reads parsed string data; it never invokes a method defined by the target. UTF-16 length and code-unit indexing follow the [Java String contract](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/lang/String.html). Supplementary characters occupy two code units. Out-of-range indices remain unchanged, as do instruction annotations, opaque attributes and sequences crossing a label/frame/debug boundary.

```bat
classrevive transform sample.jar --output simplified --passes string-length,string-char-at,integer-unary,integer-constants,integer-branches
```

Distinct audit pass IDs identify the operation. `left` is string length, `right` is 0 for length or the character index, and `result` is the integer output. The literal text is not duplicated into audit logs; original snapshots and constant pools still contain it. This is not a redaction feature. As with other optimizing transformations, exceptional VM resource exhaustion or modified JDK implementations are not modeled. No arbitrary method evaluator is implemented.
