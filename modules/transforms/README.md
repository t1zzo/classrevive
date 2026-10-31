# Static transform API (experimental)

`BytecodePass.apply(byte[], maxChanges)` returns a defensive byte-array copy, immutable change records and a limit-reached flag. It never defines or executes target classes. `IntegerConstantPass` folds adjacent integer constants for arithmetic, bitwise and shift operations using JVM integer semantics. Division/remainder by zero are preserved. It does not skip labels, stack-map frames or debug nodes, and classes containing opaque attributes are left unchanged.

Audit indexes identify the original ASM instruction-list position (including metadata nodes), not a byte offset. Chained replacements retain the first original index. `changeLimitReached` means the requested cap was reached, not that additional opportunities were proven to exist.

This module is not connected to CLI commands yet. It does not rename symbols, decrypt protected strings, resolve reflection or prove behavioral equivalence. Parsing is in-process and not a security sandbox. Future job integration must preserve original snapshots, run transforms within resource-limited workers and report before/after hashes and skipped transformations explicitly.
