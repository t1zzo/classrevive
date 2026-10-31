# Behavioral comparison protocol (experimental v1)

This Java API compares observations supplied by a trusted test coordinator. It does not run tests or turn sandbox readiness into verified isolation. The CLI does not expose behavioral execution yet.

`BehaviorObservation` binds a case ID to:
- A SHA-256 of the complete logical test input (arguments, stdin, fixture files and test-harness identity). This is not the target artifact digest, which necessarily differs after reconstruction.
- An immutable runtime image ID and an isolation-evidence ID identifying the same validated policy/configuration for both runs.
- An isolation-verified flag set only by the future containment coordinator, never from target stdout or target-written files.
- Completion outcome, exit code, truncation indicator and exact stdout/stderr bytes.

`BehaviorComparison.compare` joins both sets by unique case ID, sorting results deterministically. At most 100 observations per side, 1 MiB per stream and 32 MiB combined output are accepted. Input/output arrays and result lists are copied defensively.

- `MATCHED_CASES`: every supplied case has both observations, matching context, verified isolation, completed exit-zero execution, complete capture and identical stdout/stderr bytes. This means only those observed cases match.
- `MISMATCH`: at least one comparable case differs in observed bytes. Other inconclusive cases remain listed and counted.
- `INCONCLUSIVE`: no comparable mismatch exists, but there are missing cases, unverified isolation, context differences, incomplete/failed execution, truncation, or an empty suite.

Nonzero exits are conservatively inconclusive, including two identical failures. Expected-failure test semantics are not modeled yet. Filesystem effects, timing, memory, nondeterminism and behavior outside the supplied observations are not compared. No whitespace/newline normalization or filtering can conceal a difference.

`BehaviorEvidence.write` exports schema-v1 JSON with original/recovered artifact SHA-256 values, case context, counts, reasons and output lengths/hashes, never raw output. Existing reports are not overwritten. Hashes do not make sensitive output anonymous and do not authenticate a report. Callers can fabricate observations; this API is not a security attestation endpoint or proof of universal equivalence.

Remaining execution gates: reviewed image, validated input staging, containment conformance, bounded runner/capture and reliable cleanup, followed by actual original-versus-recovered tests. Until then, all ordinary decompilation reports continue to state behavior NOT_VERIFIED.
