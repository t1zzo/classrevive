package dev.classrevive.cli;

import dev.classrevive.pipeline.InputIdentifier;
import java.io.*;
import java.nio.file.*;
import java.util.Locale;

/**
 * Phase 2CE: {@code identify <file> [--output <new-directory>]}: what the file is and whether it holds Java, from its bytes.
 * Phase 2DB: for a bundle of jars, {@code --output} also writes the nested jars to {@code bundle/}.
 */
final class IdentifyCommand {
    private IdentifyCommand() {}
    static int run(String[] args, PrintStream out, PrintStream err) {
        try {
            if (args.length != 2 && !(args.length == 4 && args[2].equals("--output")))
                throw new IllegalArgumentException("Usage: classrevive identify <file> [--output <new-directory>]");
            var identification = InputIdentifier.identify(Path.of(args[1]));
            print(identification, out);
            if (args.length == 4) {
                Path output = InputIdentifier.write(Path.of(args[3]), identification);
                out.println("Evidence: " + Main.safe(output.resolve("identification.json").toString()));
                // Phase 2DB: a bundle's nested jars are written next to the evidence, as data.
                var jars = InputIdentifier.extractBundle(Path.of(args[1]), identification, output);
                if (!jars.isEmpty()) out.println("Nested jars written (data, not run): " + jars.size() + " in " + Main.safe(output.resolve("bundle").toString()));
            }
            return 0;
        } catch (IllegalArgumentException error) {
            err.println("Invalid arguments: " + Main.safe(String.valueOf(error.getMessage())));
            return 2;
        } catch (FileAlreadyExistsException error) {
            err.println("Output already exists. Choose a new directory; existing results were not overwritten.");
            return 1;
        } catch (IOException error) {
            err.println("Identification failed: " + Main.safe(String.valueOf(error.getMessage())));
            return 1;
        }
    }

    static boolean needsIdentification(Path input) {
        Path name = input.getFileName();
        String lower = name == null ? "" : name.toString().toLowerCase(Locale.ROOT);
        return !lower.endsWith(".class") && !lower.endsWith(".jar");
    }

    static void print(InputIdentifier.Identification identification, PrintStream out) {
        for (String line : identification.text().split("\n")) out.println(Main.safe(line));
    }

    /** For decompile and diagnose: an input without Java the pipeline can take is explained, not just rejected (exit 1). */
    static int noJava(InputIdentifier.NoJavaException error, PrintStream out, PrintStream err) {
        print(error.identification(), out);
        err.println("No Java to process: " + Main.safe(error.getMessage()) + " No output directory was created.");
        return 1;
    }
}
