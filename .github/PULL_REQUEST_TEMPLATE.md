## What and why

<!-- What changes, and the problem it solves. Link the issue: "Fixes #123". -->

## How it was checked

<!-- Tests added or changed, and any benchmark or decompile you ran (name public inputs only). -->

- [ ] `./gradlew test :cli:installDist :cli:distZip` passes locally
- [ ] New behavior has a test; a bug fix has a test that failed before it
- [ ] Nothing loads, links or runs analyzed classes
- [ ] No private or third-party binaries, local paths or secrets are added
- [ ] User-facing changes are reflected in the docs (and both `messages*.properties` for the desktop app)
- [ ] New or updated dependencies: `gradle/verification-metadata.xml`, `THIRD_PARTY_NOTICES.md` and `third-party-licenses/` are updated
