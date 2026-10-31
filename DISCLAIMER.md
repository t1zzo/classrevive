# Disclaimer

ClassRevive is a decompiler and deobfuscation tool. It turns compiled Java back into readable source and can undo some obfuscation. Tools like this have legitimate uses, and they can also be misused. This page explains what ClassRevive expects of you and what it does not promise.

## Analyze only what you have the right to analyze

Use ClassRevive on software you are entitled to examine. Typical cases:

- your own code, or code your organization owns and has asked you to work on;
- open-source software, under the terms of its license;
- software whose license, contract or written permission from the rights holder allows reverse engineering;
- cases where the law where you live allows it.

Do not use ClassRevive to infringe copyright, break license terms you agreed to, remove license checks or copy protection, take trade secrets, cheat in online services, or build software meant to harm people or systems.

## Reverse engineering and the law

Many legal systems allow some reverse engineering, for example to achieve interoperability between programs, to research or fix security problems, or to study how a program works. What is allowed, under which conditions, and how far a license agreement (EULA) can restrict it differ from country to country and change over time.

You are responsible for knowing and following the law that applies to you and the license terms of the software you analyze. **Nothing in this project is legal advice.** If you are unsure whether an analysis is allowed, ask a qualified lawyer before you start.

## Recovered code may belong to someone else

Decompiled source is derived from the original program. Its copyright and license stay with the original authors: decompiling a program does not give you any right to publish, redistribute or reuse its code. Results also contain code produced by third-party decompilers and may include material from libraries bundled in the input. Treat the output with the same care as the input.

## Trademarks and detection

Names of obfuscators, decompilers and other products mentioned in ClassRevive's code, reports and documentation, such as Zelix KlassMaster (ZKM), Allatori, DashO, Stringer, ProGuard, R8, Skidfuscator and Aidsfuscator, are trademarks or names of their respective owners. They are used only to identify the tools whose output ClassRevive recognizes. ClassRevive is not affiliated with, endorsed by or sponsored by any of them.

Obfuscator detection is heuristic. A report that names an obfuscator is a hypothesis with a score and the reasons behind it, not proof that the tool was used, and "unknown" or "none" are ordinary answers. Detection results say nothing about whether a program is safe, malicious, licensed or pirated.

## Handling suspicious files

ClassRevive never loads, links or runs the classes it analyzes, but parsers and decompilers can still have bugs. Analyze files you do not trust inside a disposable virtual machine. [SECURITY.md](SECURITY.md) describes the exact boundary.

## No warranty

ClassRevive is provided "as is", without warranties or conditions of any kind, as stated in the [Apache License 2.0](LICENSE). Recovered source may be incomplete, may not compile, and may behave differently from the original even when it compiles. You use the tool and its output at your own risk.
