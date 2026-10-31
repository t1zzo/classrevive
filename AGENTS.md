# Agent instructions

Coding agents follow the same rules as human contributors: read [CONTRIBUTING.md](CONTRIBUTING.md) and [ARCHITECTURE.md](ARCHITECTURE.md) before changing code.

Verification, from the repository root:

```
./gradlew test :cli:installDist :cli:distZip
```

Invariants: fail closed; never initialize target classes or invoke their code (the only JVM that sees them is the link-check worker, which links rewritten classes for the verifier without initializing them); behavior stays `NOT_VERIFIED`; do not change report schemas incompatibly; never commit private or third-party binaries.
