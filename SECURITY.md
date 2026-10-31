# Security policy

ClassRevive reads untrusted bytecode by design. This page explains which versions get security fixes, how to report a vulnerability, and where the security boundary is.

## Supported versions

ClassRevive is pre-1.0. Security fixes go into the latest release only.

| Version | Supported |
|---|---|
| Latest 0.x release | Yes |
| Older releases | No; please upgrade |
| Unreleased development builds | Best effort |

## Reporting a vulnerability

Please report vulnerabilities privately, not in public issues, discussions or pull requests.

1. Open the repository's **Security** tab and choose **Report a vulnerability**. This uses GitHub's private vulnerability reporting: only you and the maintainers can see the report.
2. Describe the problem, the affected version, and how to reproduce it. A minimal input you built yourself is the most useful reproducer. Do not attach private or proprietary binaries, credentials or live malware; if a reproducer needs a third-party file, describe how to obtain it instead.
3. If the **Report a vulnerability** button is not available, open a public issue that only asks for a private channel, with no technical details, and wait for a maintainer to reply.

ClassRevive is maintained by volunteers, so please be patient. Give the maintainers a reasonable time to release a fix before you disclose the problem publicly, and say whether you would like to be credited in the release notes.

Useful reports include: a crafted input that makes ClassRevive execute target code, write outside its output folder, overwrite an existing file, escape its resource limits in a way that harms the host, or inject terminal escape sequences into its output. Decompilation that is merely wrong, incomplete or slow is a normal bug: open a regular issue.

## Security boundary

**ClassRevive never runs the code it analyzes.** Target bytecode is read as data with ASM. No target class is ever initialized, no target method or constructor is ever invoked, and the target program is never started. Some passes evaluate pure constant code in a bounded static interpreter: string and number decoders of known obfuscators and integer folds, with an explicit instruction allowlist and step and memory budgets. That interpreter does not define classes or call target methods.

One step does load target-derived classes into a JVM, only to verify them. After bytecode is rewritten (an obfuscator reversal, a whole-jar `transform` pass, or a failure-lab reduction), a separate worker JVM defines the classes in an isolated class loader and links them, so the JVM bytecode verifier checks them. The classes are never initialized and none of their code runs there.

What the rest of the pipeline does:

- **Intake** reads archives in the main process under fixed limits: 256 MiB input, 16 MiB per expanded entry, 512 MiB expanded in total and 20,000 entries. Entries are never extracted to disk or executed. Unsafe archive names, duplicate paths and identity mismatches are reported or refused.
- **Decompilers, javac, dex2jar, the transform and rename passes, the bytecode fallback and the link check** each run in a separate JVM with a wall-clock timeout, heap and metaspace limits, a retained-log limit and an output budget. `JAVA_TOOL_OPTIONS`, `_JAVA_OPTIONS`, `JDK_JAVA_OPTIONS` and `CLASSPATH` are removed from their environment.
- **javac** runs with annotation processing disabled (`-proc:none`), `-implicit:none`, an always-empty source path and either an empty class path or the dependency jars you pass explicitly. It never loads the classes it compiles. No Gradle or Maven build from the input is ever run, and dependencies are never downloaded.
- **JADX** uses a fixed list of input plugins, with its cache, configuration and temporary files kept under the worker's output folder. External plugin discovery and scripting are not enabled.
- **Native libraries** found with `--native` are extracted as data into the output folder and only read, by ClassRevive and by a Ghidra installation you provide. They are never loaded or run.
- **Output folders** must not exist yet. Nothing is overwritten.

### What this boundary does not cover

- Worker JVMs provide **fault isolation, not an operating-system sandbox**. They inherit the rest of the user's environment and can read and write the file system with the user's permissions. A bug in a decompiler, in javac, in the JVM verifier or in ClassRevive's own parsers could be exploitable by a crafted input.
- The JDK parses a zip's central directory before ClassRevive counts its entries, and the initial intake has byte limits but no wall-clock deadline.
- Input hashes are taken before and after reading. They detect ordinary concurrent changes; they are not an atomic snapshot. Use immutable copies of adversarial inputs and do not modify the output tree while a job runs.
- Disk budgets are sampled while a worker runs, so a short overshoot is possible.
- Obfuscator detection and the static diagnostics are triage clues. They are not a malware scan and do not prove that a file is safe.

**Analyze deliberately hostile or untrusted files inside a disposable virtual machine** with no access to your personal files or credentials, and treat everything ClassRevive writes as derived from that input.

### Future behavioral verification

Behavioral comparison is not implemented and target execution is disabled. If it is added, it must run only inside an isolated sandbox and fail closed when that sandbox is unavailable. It must never fall back to running target code on the host.

## Responsible use

Analyze only software you have the right to analyze. See [DISCLAIMER.md](DISCLAIMER.md).
