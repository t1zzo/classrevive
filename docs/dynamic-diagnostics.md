# Dynamic linkage diagnostics

```bat
classrevive diagnose target.jar --output diagnostic-result --timeout-seconds 60
```

Reads a .class or .jar snapshot and inventories it before scanning in a heap/time/output-limited worker. Does not extract classes, load the target, invoke bootstraps, resolve reflective targets or label software as malicious. The output directory must be new. Timeout defaults to 60 seconds, range 1..3600, for the worker; initial snapshot/inventory has byte limits but no wall-clock limit.

`diagnostics.json` contains snapshot SHA-256, worker outcome, scanned-class count, a sorted `signal_counts` map, and findings with archive entry, internal class, method/descriptor and instruction ordinal. Counts cover retained findings only; when status is `PARTIAL`, the counts may be incomplete. Ordinals count JVM instructions within a method, excluding labels/debug/frame metadata; they are not file byte offsets. Archive entry attribution distinguishes multi-release variants.

Categories:
- `INVOKEDYNAMIC`: bootstrap owner/name/descriptor and argument count. Standard lambda/string-concatenation sites also appear; their presence alone is not obfuscation.
- `CONSTANT_DYNAMIC`: bootstrap metadata, including nested dynamic constants to depth eight.
- `REFLECTION_API`: references to selected Class lookup methods, java.lang.reflect APIs and MethodHandles.Lookup APIs. This pattern list is intentionally incomplete and findings do not prove runtime reachability.
- `CLASS_LOADING_API`: selected `ClassLoader.loadClass`/`defineClass` calls, including calls owned by a class that directly extends `ClassLoader`, plus selected `MethodHandles.Lookup` class-definition calls. These can be used by plugin systems and ordinary frameworks.
- `CRYPTO_API`: selected JCE, `MessageDigest`, `KeyFactory` and `Signature` API calls. These references do not establish that class or string data is encrypted or decrypted.

The two additional categories are triage clues only. Their presence together does not identify a packer, prove encrypted payloads, establish that the methods run, or indicate malware. `diagnostics.json` includes this interpretation explicitly; no packer verdict is produced.

String literal contents and bootstrap argument values are omitted from reports. They remain in original artifacts; no redaction guarantee is made. Direct method-handle constants, native code, custom reflection wrappers and resolved runtime values are outside this version's coverage.

Budgets: 10,000 findings globally, 20,000 archive entries, 16 MiB per class, 512 MiB scanned class bytes; parent inventory limits also apply. Reaching the finding budget or nesting limit marks `PARTIAL` conservatively even if no further site is known. `SCANNED` means the documented patterns were scanned, not complete semantic understanding. CLI exit 0 means worker success without a coverage limit; exit 1 means partial/failure, exit 2 invalid syntax, 130 interruption. Worker failures retain their status/log and snapshot; intake failures are printed by the CLI and retain any already-created snapshot.

Serialized worker evidence is additionally capped at 16 MiB. Findings beyond that budget are omitted with explicit PARTIAL coverage; retained class count indicates scanning work, not that every finding from those classes fit in the report.

## Obfuscation profile

The same worker pass builds a static obfuscation profile: `diagnostics.json` gains `obfuscation` (techniques with class and occurrence counts and up to 10 samples, raw `indicators`, `verdict`, `hypotheses` with score, confidence and reasons, a one-line `summary`), and `obfuscation.txt` holds the readable form the CLI also prints. Resources contribute only their names and sizes (a bundled native library is never read). Rules, thresholds and limits are in [obfuscation detection](obfuscation-detection.md). The suggested obfuscator is a hypothesis, not proof.
