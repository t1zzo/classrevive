package dev.classrevive.cli;

import dev.classrevive.pipeline.NativeAnalysis;
import dev.classrevive.workbench.NativeTriage;
import java.io.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;

/**
 * Phase 2CC: the optional native triage options of {@code decompile} and {@code diagnose}. {@code --native} is a flag; any
 * other native option turns it on too. They are removed from the arguments before the command's own option pairs are read.
 */
record NativeOptions(boolean enabled, NativeAnalysis.Options options) {
    static final Set<String> VALUED = Set.of("--ghidra-home", "--native-max-mib", "--native-max-libraries", "--native-timeout-seconds");
    static final String USAGE = "[--native] [--ghidra-home <dir>] [--native-max-mib <1..512>] [--native-max-libraries <1..32>] [--native-timeout-seconds <30..7200>]";

    /** Returns the arguments without the native options; {@code parsed[0]} receives the options. */
    static String[] strip(String[] args, NativeOptions[] parsed) {
        var kept = new ArrayList<String>();
        var values = new HashMap<String,String>();
        boolean enabled = false;
        for (int i = 0; i < args.length; i++) {
            if (i >= 2 && args[i].equals("--native")) { enabled = true; continue; }
            if (i >= 2 && VALUED.contains(args[i])) {
                if (i + 1 >= args.length) throw new IllegalArgumentException("Missing value for " + args[i]);
                if (values.putIfAbsent(args[i], args[++i]) != null) throw new IllegalArgumentException("Repeated option: " + args[i - 1]);
                enabled = true;
                continue;
            }
            kept.add(args[i]);
        }
        var options = NativeAnalysis.Options.defaults(values.get("--ghidra-home"), System.getenv());
        long mib = integer(values, "--native-max-mib", 64, 1, 512);
        int libraries = (int) integer(values, "--native-max-libraries", 4, 1, 32);
        long seconds = integer(values, "--native-timeout-seconds", 900, 30, 7200);
        parsed[0] = new NativeOptions(enabled, options.withLimits(mib * 1024 * 1024, libraries, Duration.ofSeconds(seconds)));
        return kept.toArray(String[]::new);
    }

    private static long integer(Map<String,String> values, String key, long fallback, long min, long max) {
        if (!values.containsKey(key)) return fallback;
        long value;
        try { value = Long.parseLong(values.get(key)); }
        catch (NumberFormatException bad) { throw new IllegalArgumentException(key + " must be an integer"); }
        if (value < min || value > max) throw new IllegalArgumentException(key + " must be " + min + ".." + max);
        return value;
    }

    /** Runs the triage on {@code output/input.jar} into {@code output/native} and attaches it to the given evidence pair. */
    void run(Path output, Path text, Path json, PrintStream out) throws IOException, InterruptedException {
        if (!enabled) return;
        if (!NativeTriage.applicable(output)) { out.println("Native analysis: not applicable (input is not a jar)"); return; }
        out.println("Native analysis: extracting bundled libraries as data; Ghidra, when configured, runs in its own process (static only).");
        var result = NativeTriage.run(output, text, json, options);
        for (String line : result.text().split("\n")) out.println(Main.safe(line));
        out.println("Native evidence: " + Main.safe(output.resolve("native").toAbsolutePath().toString()));
    }
}
