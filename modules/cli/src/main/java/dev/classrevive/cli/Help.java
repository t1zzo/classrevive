package dev.classrevive.cli;

import java.io.PrintStream;
import java.util.*;

/** Phase 2CV: a short overview, and per-command help with options, defaults, examples and exit codes. */
final class Help {
    static final String VERSION = "0.1.0-dev";

    /** {@code summary}: one line for the overview; {@code text}: the full help of the command. */
    record Entry(String group, String summary, String text) {}

    static final String EXIT_CODES = """
        Exit codes: 0 done (for decompile: compiles with every signature) | 1 failure or partial result | 2 invalid arguments | 130 interrupted.""";

    static final Map<String,Entry> COMMANDS = new LinkedHashMap<>();
    static {
        COMMANDS.put("gui", new Entry("main", "Open the desktop application (also bin/classrevive-gui)", """
            Usage: classrevive gui [file.jar|file.class|result-folder]

            Opens the desktop application, optionally with a file or a result folder. Everything decompile does, with
            an explorer, code tabs, live progress, the confidence of every class, search, export and settings.
            Exit codes: 0 the window is opening | 1 no graphical display | 2 invalid arguments."""));
        COMMANDS.put("decompile", new Entry("main", "Recover Java sources with several engines and verify them by recompiling", """
            Usage: classrevive decompile <file|folder>... [-o <output>] [options]

            Decompiles .jar and .class files (any other file, such as an .exe, is identified from its bytes first;
            an Android .apk or .dex is converted to class files by dex2jar), recompiles the result and writes sources,
            evidence and an IDE project into a new folder.

              -o, --output <folder>       New result folder (one input), or the folder that receives one result folder per
                                          input (several inputs or a folder). Default: <name>-classrevive in the current folder.
              -e, --engines <list>        cfr,vineflower,procyon,jadx or all. Default: cfr,vineflower.
              -t, --timeout-seconds <n>   Limit per worker or compile attempt, 1..3600. Default: 120.
              -cp, --classpath <jars>     Library JARs used only to verify the result (separator: %s), at most 16.
              --stubs true|false          Infer stand-ins for missing libraries (reported as such). Default: true.
              --entry <name>              For a bundle of jars (a zip of jars, alone or inside an .exe): the nested jar
                                          to decompile. Default: the one with the most classes that is not a library;
                                          the bundle's other jars are added to --classpath automatically.
              --engine-cache <folder>     Reuse engine outputs keyed by input and engine bytes.
              --deobfuscate auto|off      Undo a recognized obfuscator (Skidfuscator, Aidsfuscator) before decompiling,
                                          when the diagnosis is HIGH confidence. Default: auto.
              %s
                                          Static triage of bundled native libraries with an installed Ghidra.

            Examples:
              classrevive decompile app.jar
              classrevive decompile app.jar -o recovered -e all -cp "libs/a.jar%slibs/b.jar"
              classrevive decompile plugins/ -o results

            Existing folders are never overwritten. The program is never executed; behavior is not verified.
            %s"""));
        COMMANDS.put("inspect", new Entry("main", "List the classes of a .jar or .class: versions, fields and methods", """
            Usage: classrevive inspect <file.class|file.jar>

            Prints the input and entry SHA-256, sizes, every class with its Java version and member counts, and notices.
            Nothing is loaded or run. Any other file is identified instead (exit 1).
            Exit codes: 0 complete inventory | 1 input or inspection failure | 2 invalid arguments."""));
        COMMANDS.put("identify", new Entry("main", "Say what a file is (PE, ELF, .NET, jar, APK, installer...) and whether it holds Java", """
            Usage: classrevive identify <file> [-o <new-folder>]

            Reads the bytes only: format, sections and entropy, overlay, signature, protector names, and any jar found
            inside (Launch4j, exe4j, a jar appended to a stub), bundles of nested jars, Android APK, dex and app
            bundles. With -o, a bundle's nested jars are also written to bundle/ as data. Nothing is run,
            unpacked or decrypted.
            Exit codes: 0 the file was read | 1 unreadable, or the output exists | 2 invalid arguments."""));
        COMMANDS.put("diagnose", new Entry("main", "Static triage: obfuscation techniques, reflection, class loading, crypto", """
            Usage: classrevive diagnose <input> [-o <new-folder>] [-t <seconds>] [--entry <nested.jar>] [native options]

            Counts obfuscation techniques and suggests the obfuscator (never proof), and lists bootstrap sites, reflection,
            class-loading and crypto references as clues. Default output: <name>-diagnosis in the current folder.
            %s"""));
        COMMANDS.put("engines", new Entry("tools", "List the bundled decompiler engines and their pinned versions", """
            Usage: classrevive engines"""));
        COMMANDS.put("transform", new Entry("tools", "Opt-in static simplification passes (constant folding, string decoding)", """
            Usage: classrevive transform <input> -o <new-folder> [--passes <list>] [--max-changes <1..10000>] [-t <seconds>]

            Passes, in the order given: integer-constants (default), integer-unary, integer-branches, string-length,
            string-char-at, string-decryptor; skidfuscator (first in the list) reverses Skidfuscator 2.1.0,
            aidsfuscator (first in the list) reverses Aidsfuscator 2.10.0.
            The output is checked by the JVM verifier; nothing is executed.
            %s"""));
        COMMANDS.put("rename", new Entry("tools", "Rename classes and private members (readable aliases or a mapping file)", """
            Usage: classrevive rename <input> -o <new-folder> [--mapping <file> | --proguard-mapping <mapping.txt>] [-t <seconds>]

            Without a mapping, short class names get deterministic readable aliases. --mapping takes rules
            "class a=b", "method owner#name[(desc)]=new", "field owner#name[:desc]=new"; --proguard-mapping restores the
            names a ProGuard or R8 mapping records where that is safe, and reports what it skipped.
            %s"""));
        COMMANDS.put("benchmark", new Entry("tools", "Measure each engine and the combined selection on a corpus", """
            Usage: classrevive benchmark <corpus-folder> -o <new-folder> [-e <list>] [-t <seconds>] [--engine-cache <folder>] [--resume true]

            Each .jar/.class sample compiles against the jars in a sibling <sample>.deps/ folder. Writes benchmark.json,
            benchmark.md and the failure lab cases. Default engines: all.
            %s"""));
        COMMANDS.put("compare-reference", new Entry("tools", "Score a decompile against a reference jar (answer key)", """
            Usage: classrevive compare-reference <reference.jar> <decompile-output> -o <new-folder> [-cp <jars>]"""));
        COMMANDS.put("sandbox-check", new Entry("tools", "Check local Docker readiness (read only; nothing is executed)", """
            Usage: classrevive sandbox-check"""));
        COMMANDS.put("lab", new Entry("lab", "Capture failure cases, grouped by stable signature", """
            Usage: classrevive lab <input|corpus-folder> -o <new-folder> [--minimize none|first|all|failure-NNNN] [--max-steps <n>] [--max-seconds <n>]"""));
        COMMANDS.put("lab-minimize", new Entry("lab", "Reduce a failure case while it still fails the same way", """
            Usage: classrevive lab-minimize <failure-case-folder> -o <new-folder> [--max-steps <n>] [--max-seconds <n>] [--keep-recoverable true|false]

            A reduction is kept only if the JVM verifier accepts it (nothing executed) and the failure repeats twice."""));
        COMMANDS.put("lab-promote", new Entry("lab", "Turn a minimized case into a regression case", """
            Usage: classrevive lab-promote <minimized-case-folder> -o <new-regression-folder> [--provenance <text>]"""));
        COMMANDS.put("lab-check", new Entry("lab", "Fail when a committed regression case got worse", """
            Usage: classrevive lab-check <regressions-folder> -o <new-folder>"""));
    }

    private Help() {}

    static void overview(PrintStream out) {
        out.println("ClassRevive " + VERSION + " - recover readable Java and measure how far to trust it");
        out.println();
        out.println("Usage: classrevive <command> [arguments]");
        for (var group : List.of(new String[] {"main", "Main commands"}, new String[] {"tools", "Tools"}, new String[] {"lab", "Failure lab"})) {
            out.println();
            out.println(group[1] + ":");
            COMMANDS.forEach((name, entry) -> { if (entry.group().equals(group[0])) out.printf("  %-18s %s%n", name, entry.summary()); });
        }
        out.println();
        out.println("Run 'classrevive help <command>' (or '<command> --help') for options and examples. 'classrevive --version' prints the version.");
        out.println(EXIT_CODES);
        out.println("Target code is never executed: ClassRevive only reads bytes. Compilation and signatures are checked; behavior is not verified.");
    }

    /** Prints a command's help; 2 for an unknown command. */
    static int command(String name, PrintStream out, PrintStream err) {
        var entry = COMMANDS.get(name);
        if (entry == null) return unknown(name, err);
        out.println("classrevive " + name + " - " + entry.summary());
        out.println();
        out.println(text(name, entry));
        return 0;
    }

    static int unknown(String name, PrintStream err) {
        String suggestion = closest(name);
        err.println("Unknown command: " + Main.safe(name) + "." + (suggestion == null ? "" : " Did you mean '" + suggestion + "'?")
            + " Run 'classrevive --help' for the list of commands.");
        return 2;
    }

    /** The full help text of a command, with the platform path separator and exit codes filled in. */
    static String text(String name, Entry entry) {
        String separator = java.io.File.pathSeparator;
        return switch (name) {
            case "decompile" -> entry.text().formatted(separator, NativeOptions.USAGE, separator, EXIT_CODES);
            case "diagnose", "transform", "rename", "benchmark" -> entry.text().formatted(EXIT_CODES);
            default -> entry.text();
        };
    }

    /** The known command nearest to {@code name} (edit distance at most 3), or null. */
    static String closest(String name) {
        String best = null;
        int bestDistance = 4;
        for (String command : COMMANDS.keySet()) {
            int distance = distance(name.toLowerCase(Locale.ROOT), command);
            if (distance < bestDistance) { best = command; bestDistance = distance; }
        }
        return best;
    }

    private static int distance(String a, String b) {
        int[] previous = new int[b.length() + 1], current = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) previous[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= b.length(); j++)
                current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1), previous[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1));
            var swap = previous; previous = current; current = swap;
        }
        return previous[b.length()];
    }
}
