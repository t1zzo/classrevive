# Preparing a release

No public release is claimed until a maintainer publishes one. Current artifacts are development builds.

1. Use a clean checkout and JDK 21. Run `gradlew clean build benchmarkFixtures :cli:installDist` (use `./gradlew` on Unix).
2. Run the installed CLI's `benchmark build/benchmark-corpus --output build/release-benchmark --engines all`.
3. Exit 1 can mean a completed comparison with partial cases. Validate its evidence with `python scripts/check_benchmark.py build/release-benchmark`; inspect every regression instead of requiring artificial perfect scores.
4. Review pinned versions, dependency verification metadata and `THIRD_PARTY_NOTICES.md`. Every distributed dependency needs its applicable license/notice, including transitives.
5. Inspect `modules/cli/build/distributions/classrevive-0.1.0-dev.zip`. It includes Windows/Unix launchers, dependencies and licenses. Extract the whole directory; do not distribute the launcher alone. JDK 21 must be installed.
6. Test the distribution in the configured OS matrix and publish exact commands, corpus hashes, engine versions and limitations with the release. Local Windows results do not prove Linux/macOS support.

Do not label the project universally compatible, malware-safe, behaviorally equivalent or 100% recovered. Source comments/names and build context may be irrecoverable. Encrypted runtime payloads require different analysis; this CLI does not execute them.
