# Evidence format, schema version 1

All JSON files are UTF-8. Unknown additive fields should be ignored by consumers; incompatible meaning changes require a schema version increment. No wall-clock timestamps or machine-specific absolute input paths are required. Diagnostics can still contain local paths: review before sharing.

## report.json

- `kind`: `decompilation`; `tool_version`; `schema_version`.
- `input_sha256`: original snapshot hash, not normalized JAR hash.
- `environment`: Java version/vendor, OS and architecture.
- `engines[]`: id/version/status, stable `diagnostic_code`, exit code, source file count, elapsed milliseconds and explanatory message. Codes distinguish `NONE`, `NO_SOURCE_FILES`, `ENGINE_EXIT_NONZERO`, `DEADLINE_EXCEEDED`, `OUTPUT_BUDGET_EXCEEDED`, `CANCELLED`, `WORKER_SETUP_FAILED`, `WORKER_START_FAILED`, and `WORKER_RUNTIME_FAILURE`.
- `verification`: status (`COMPILED`, `COMPILE_FAILED`, `UNSUPPORTED_RELEASE`, `WORKER_FAILED`), error count, matched/expected structural signatures, placeholder signals, structurally_complete, detail, and `class_coverage[]` entries with class name, matched/expected signatures, permille coverage and completion flag. This is structural evidence only. The additive `round_trip` object holds per-method body comparison of the recompiled candidate against the decompiled bytes: `measured`, totals `identical`, `equivalent_after_normalization`, `different`, and `classes[]` with the same counts per class. Selection uses these totals only to break ties between candidates that are equal on every structural criterion. `measured: false` means no comparison ran (the candidate did not compile), not a negative result. Selection does not use these counts yet.
- `selected_candidate`, `provenance` (relative source path to engine), `selection_attempts`.
- `source_repairs`: compiler-guided edits in the selected sources (`count`, `edits[]` with file, line, column, diagnostic code, rule, before and after). Repaired files show `+repair` in `provenance`.
- `method_composition` (additive): method bodies in the selected sources taken from another engine's version of their file: `count`, `files`, and `methods[]` with `file`, `class_name`, `method`, `descriptor`, `engine`, `round_trip_in_place` (`IDENTICAL` or `EQUIVALENT_AFTER_NORMALIZATION`, measured when that file was recompiled alone against the original classes) `canonical_type_names` (types the copied body names canonically because the file's imports did not provide them) and `replaced_body`: `COMPILE_ERROR` when the replaced body failed to compile or `ROUND_TRIP_DIFFERENT` when it compiled but its round trip was `DIFFERENT`; `round_trip_in_place_before` (the method's own outcome before, null when the replaced body did not compile), `also_recovered` (lambda or anonymous-class methods of the body that went from `DIFFERENT` to identical or equivalent, as `class.name(descriptor)`) and `body_repairs` (source repair rules applied to the copied body). `body_repairs` may also name `raw-receiver`, `raw-creation`, `erased-argument`, `drop-redundant-cast` or `drop-upcast` (edits that take away `checkcast`s the original does not have), and `engine` may name the engine whose file is the selected one: that method's own body was edited, not copied. Such files show `+methods` in `provenance`; the rest of the file keeps the listed origin. `evidence` is `METHOD_BODIES_FROM_OTHER_ENGINES_ROUND_TRIP_IN_PLACE_BEHAVIOR_NOT_VERIFIED`.
- `fallback`: null if unnecessary; otherwise an engine-result object. Non-null does not imply successful disassembly.
- `class_confidence` (additive): one entry per class with the level `report.html` shows for it, computed by the same code. Each entry has:
  - `class_name`;
  - `file`: the top-level source file;
  - `origin`: its provenance in the selected sources, or `none`;
  - `matched_symbols` and `expected_symbols`;
  - `identical`, `equivalent_after_normalization` and `different`: method bodies, from the whole candidate's round trip or, when it did not compile, the per-file one;
  - `different_methods`: `name(descriptor)`;
  - `level`: `EXACT`, `CLOSE`, `PER_FILE`, `PARTIAL` or `UNVERIFIED`;
  - `methods`: every measured method as `{method: name + descriptor, outcome: IDENTICAL | EQUIVALENT_AFTER_NORMALIZATION | DIFFERENT}`, empty when the class was not recompiled.

  Levels are recompilation evidence, not behavior.

  A result whose selected sources do not compile also has `selection/diagnostics.tsv`: the selected attempt's javac errors, one per line (file relative to `selection/sources`, line, column, `compiler.err.*` code, message), tab separated and escaped like the compiler worker's own file. They are kept out of `report.json` because messages can name target symbols.
- `behavior`: `NOT_VERIFIED`.

Compilation with full structural coverage is not proof of recovered implementation, malware safety or behavioral equivalence. Annotations and generics are not compared; method bodies are compared only by `round_trip`, which is static bytecode evidence, not behavior. Placeholder counts are not a readability score.

## benchmark.json and progress.jsonl

`rows` contains every completed sample/variant attempt, with snapshot hash, input basename, relative report link when available, `total_elapsed_ms`, and status `STRUCTURALLY_COMPLETE`, `PARTIAL` or `FAILED`. Failed rows include an error rather than invented verification metrics. `variants` holds attempted/recompiled/structurally_complete numerators and denominators. `samples` is the corpus size; `attempted` is the row count across variants. `not_measured` lists missing comparisons/metrics explicitly.

Additive fields (`schema_version` stays 1): each row has `original_method_bodies` (method bodies in the normalized input, counted once per case; `null` when no `classes.jar` was produced) and each variant has a `metrics` object that keeps the metrics apart: `compilable` and `structural` (numerator over every attempt, failures included), `method_round_trip`, and `behavioral_equivalence` / `deobfuscation` with `status: NOT_MEASURED` and a reason. `method_round_trip` sums the rows' `verification.round_trip` counts: `measured_attempts`, `identical`, `equivalent_after_normalization`, `different`, `methods_compared`, `recovered_methods` (identical + equivalent) and `recovered_permille` over `methods_compared` (only attempts whose candidate recompiled), plus `corpus_method_bodies` and `recovered_of_corpus_permille`, where attempts that did not recompile count as not recovered (`attempts_without_method_count` are excluded from that denominator). Permille values are rounded down and are `null` with a zero denominator. `different` includes legitimate compiler variation. None of these numbers is an original-source recovery percentage, and selection does not use them.

Each row also lists its `failure_cases` (ids under `failures/`), and `failures` in the report gives the case, signature and category counts with a link to `failures/failures.json`.

`progress.jsonl` contains one completed row per line. It is incremental and may be truncated on abrupt process termination. It is not a completed aggregate or a resume protocol. Deleting an output is never automatic.

## Failure lab files

- `failures/failures.json` (`kind: failure-lab`): `cases`, `signatures`, `categories` (count per category) and `groups[]`, most frequent first. Each group has `signature`, `category`, `engine` (producer), `detail`, `cases`, `selected` (cases in the candidate the job chose), `recoverable_by_other_candidate` (METHOD_DIFFERENT cases where another engine candidate of the same job got the method identical or equivalent), `variants` and `case_ids`. It names no target class, member, constant or path.
- `failures/failure-NNNN/case.json` (`kind: failure-case`): `signature`, `category`, `engine`, `detail`, `engine_version`, `variant`, `sample`, `candidate`, `selected`, `source_repair`, `class_name`, `class_sha256`, `method`, `source_file`, `reason`, `excerpt` (bounded, absolute paths redacted), `alternatives`, `recoverable_by`, `input_sha256` and `context` (a relative path to the job's class JAR, or null). It sits next to `classes.jar`, and `candidate.java` and `recompiled.class` when available. It is target-derived evidence.
- `minimization.json` (`kind: failure-minimization`): `status` (`MINIMIZED`, `BUDGET_EXHAUSTED`, `NOT_REPRODUCIBLE`, `ORIGINAL_DOES_NOT_LINK`, `UNSUPPORTED`), `budget`, `evaluations`, `accepted`, `original`/`minimized` sizes (classes, bytes, members, instructions), `contrast_engines`, `contrast_levels` and `steps[]` (phase, description, outcome `ACCEPTED`/`REJECTED_LINK`/`REJECTED_SIGNATURE`/`REJECTED_NOT_REPEATED`/`REJECTED_CONTRAST`/`REJECTED_ERROR`, detail, time).
- `benchmarks/regressions/<case>/expected.json` (`kind: regression-case`): `variants.<variant>` with `engines`, `source_repair` and `minimum_levels` (method key `class.name(desc)` to `NOT_RECOMPILED`, `DIFFERENT`, `EQUIVALENT_AFTER_NORMALIZATION` or `IDENTICAL`), plus `provenance`, `signature`, `classes_sha256`, `measured_with` and `unstable_at_promotion`. `lab-check` writes `regressions.json` (`kind: regression-check`, `passed`, `checks[]` with verdict `SAME`, `IMPROVED` or `REGRESSED`, and `errors`) and `regressions.md`.

Reproducibility means same corpus bytes, pinned engines and method can be rerun; timings naturally vary. Engine versions and Java environment must match for meaningful comparison. This small authored corpus does not represent arbitrary obfuscators or encrypted loaders.
