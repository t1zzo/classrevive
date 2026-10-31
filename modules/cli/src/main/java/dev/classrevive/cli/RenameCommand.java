package dev.classrevive.cli;

import dev.classrevive.pipeline.RenameJob;
import java.io.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.HashSet;

final class RenameCommand {
    private RenameCommand() {}
    static int run(String[] args, PrintStream out, PrintStream err) {
        try {
            if (args.length < 4 || args.length % 2 != 0) throw new IllegalArgumentException("Expected input and --output <new-directory>.");
            Path input = Path.of(args[1]), output = null, mapping = null, proguard = null;
            int seconds = 60;
            var seen = new HashSet<String>();
            for (int i = 2; i < args.length; i += 2) {
                if (!seen.add(args[i])) throw new IllegalArgumentException("Duplicate option: " + args[i]);
                switch (args[i]) {
                    case "--output" -> output = Path.of(args[i + 1]);
                    case "--mapping" -> mapping = Path.of(args[i + 1]);
                    case "--proguard-mapping" -> proguard = Path.of(args[i + 1]);
                    case "--timeout-seconds" -> seconds = Integer.parseInt(args[i + 1]);
                    default -> throw new IllegalArgumentException("Unknown option: " + args[i]);
                }
            }
            if (mapping != null && proguard != null) throw new IllegalArgumentException("Use either --mapping or --proguard-mapping, not both.");
            if (output == null || seconds < 1 || seconds > 3600)
                throw new IllegalArgumentException("Require --output and timeout-seconds 1..3600.");
            out.println(proguard != null
                ? "Restoring names from a ProGuard/R8 mapping (same-package classes and private members only) in a separate worker JVM. Original retained; target code is not executed."
                : mapping == null
                ? "Renaming classes with generated readable aliases in a separate worker JVM. Original retained; target code is not executed."
                : "Renaming classes and private members with explicit mappings in a separate worker JVM. Original retained; target code is not executed.");
            var result = proguard != null
                ? new RenameJob().run(input, output, Duration.ofSeconds(seconds), proguard, true)
                : mapping == null
                ? new RenameJob().run(input, output, Duration.ofSeconds(seconds))
                : new RenameJob().run(input, output, Duration.ofSeconds(seconds), mapping);
            if (proguard != null) out.println("Skipped ProGuard rules and their reasons are listed in rename.json under \"proguard\".");
            out.printf("Worker: %s; classes: %d; renamed: %d; members: %d; reflected literals: %d%n", result.worker().status(), result.classes(), result.renamed(), result.members(), result.reflectionRewrites());
            out.println("Evidence: " + Main.safe(output.toAbsolutePath().resolve("rename.json").toString()));
            out.println("Aliases and explicit mappings are not recovered originals; standard manifest attributes, services, literal reflection calls and private members are rewritten. Variables, serialization, other resources and external callers are not. Behavior not verified.");
            return result.successful() ? 0 : 1;
        } catch (IllegalArgumentException error) {
            err.println("Invalid command arguments: " + Main.safe(String.valueOf(error.getMessage()))); return 2;
        } catch (FileAlreadyExistsException error) {
            err.println("Output already exists. Choose a new directory; existing results were not overwritten."); return 1;
        } catch (IOException error) {
            err.println("Renaming failed: " + Main.safe(String.valueOf(error.getMessage()))); return 1;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt(); err.println("Renaming interrupted."); return 130;
        }
    }
}
