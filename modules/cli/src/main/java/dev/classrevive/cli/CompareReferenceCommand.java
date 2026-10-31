package dev.classrevive.cli;

import dev.classrevive.pipeline.ReferenceCompareJob;
import java.io.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;

/** Phase 2CD: {@code compare-reference <reference.jar> <decompile-output> --output <new-directory>}. */
final class CompareReferenceCommand {
    private CompareReferenceCommand() {}
    static int run(String[] args, PrintStream out, PrintStream err) {
        try {
            if (args.length < 5 || args.length % 2 == 0) throw new IllegalArgumentException("Expected reference JAR, decompile output and --output <new-directory>.");
            Path reference = Path.of(args[1]), decompiled = Path.of(args[2]), output = null;
            int seconds = 600; var dependencies = new ArrayList<Path>(); var seen = new HashSet<String>();
            for (int i = 3; i < args.length; i += 2) {
                if (!seen.add(args[i])) throw new IllegalArgumentException("Duplicate option: " + args[i]);
                switch (args[i]) {
                    case "--output" -> output = Path.of(args[i + 1]);
                    case "--timeout-seconds" -> seconds = Integer.parseInt(args[i + 1]);
                    case "--classpath" -> {
                        for (String path : args[i + 1].split(java.util.regex.Pattern.quote(File.pathSeparator), -1)) {
                            if (path.isBlank()) throw new IllegalArgumentException("Empty classpath entry");
                            dependencies.add(Path.of(path));
                        }
                    }
                    default -> throw new IllegalArgumentException("Unknown option: " + args[i]);
                }
            }
            if (output == null || seconds < 1 || seconds > 3600 || dependencies.size() > 16)
                throw new IllegalArgumentException("Require --output, timeout-seconds 1..3600 and at most 16 classpath JARs.");
            var result = new ReferenceCompareJob().run(reference, decompiled, output, Duration.ofSeconds(seconds), dependencies);
            out.print(ReferenceCompareJob.text(result));
            out.println("Evidence: " + Main.safe(output.toAbsolutePath().resolve("reference.json").toString()));
            return 0;
        } catch (IllegalArgumentException error) {
            err.println("Invalid command arguments: " + Main.safe(String.valueOf(error.getMessage()))); return 2;
        } catch (FileAlreadyExistsException error) {
            err.println("Output already exists. Choose a new directory; existing results were not overwritten."); return 1;
        } catch (IOException error) {
            err.println("Comparison failed: " + Main.safe(String.valueOf(error.getMessage()))); return 1;
        }
    }
}
