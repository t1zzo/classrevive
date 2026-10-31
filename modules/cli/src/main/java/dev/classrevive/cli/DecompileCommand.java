package dev.classrevive.cli;

import dev.classrevive.pipeline.JobListener;
import dev.classrevive.workbench.DecompileService;
import java.io.*;
import java.nio.file.*;
import java.util.*;

final class DecompileCommand {
    /** Phase 2CV: a folder given as input contributes its .jar and .class files, at most this many. */
    static final int MAX_FOLDER_INPUTS = 100;

    private DecompileCommand() {}

    /**
     * Phase 2CV: {@code decompile <file|folder>... [--output <folder>] [options]}. One input works as before, and without
     * {@code --output} it writes a new {@code <name>-classrevive} folder in the current folder. Several inputs, or a folder,
     * each get a new folder inside the output folder (default: the current folder), decompiled one after another.
     */
    static int run(String[] args, PrintStream out, PrintStream err) {
        int first = 1, end = first;
        while (end < args.length && !args[end].startsWith("-")) end++;
        if (end == first) { err.println("Invalid command arguments: expected an input file. Run 'classrevive help decompile'."); return 2; }
        var options = new ArrayList<>(Arrays.asList(args).subList(end, args.length));
        List<Path> inputs;
        try { inputs = inputs(Arrays.asList(args).subList(first, end)); }
        catch (IllegalArgumentException | IOException invalid) { err.println("Invalid command arguments: " + Main.safe(String.valueOf(invalid.getMessage()))); return 2; }
        int outputAt = options.indexOf("--output");
        boolean batch = inputs.size() > 1 || Files.isDirectory(Path.of(args[first]));
        if (!batch) {
            var single = new ArrayList<String>(List.of("decompile", inputs.getFirst().toString()));
            single.addAll(options);
            if (outputAt < 0) {
                Path output = Outputs.next(Path.of("").toAbsolutePath(), inputs.getFirst(), "classrevive");
                single.addAll(List.of("--output", output.toString()));
                out.println("Output: " + Main.safe(output.toString()));
            }
            return single(single.toArray(String[]::new), out, err);
        }
        if (outputAt == options.size() - 1) { err.println("Invalid command arguments: --output needs a folder."); return 2; }
        Path folder = outputAt < 0 ? Path.of("").toAbsolutePath() : Path.of(options.get(outputAt + 1)).toAbsolutePath();
        if (outputAt >= 0) { options.remove(outputAt + 1); options.remove(outputAt); }
        try { Files.createDirectories(folder); }
        catch (IOException | RuntimeException failure) { err.println("Cannot create the output folder: " + Main.safe(String.valueOf(failure.getMessage()))); return 1; }
        int complete = 0, failed = 0;
        for (int i = 0; i < inputs.size(); i++) {
            Path input = inputs.get(i);
            Path output = Outputs.next(folder, input, "classrevive");
            out.println("== [" + (i + 1) + "/" + inputs.size() + "] " + Main.safe(input.toString()));
            var single = new ArrayList<String>(List.of("decompile", input.toString()));
            single.addAll(options);
            single.addAll(List.of("--output", output.toString()));
            int code = single(single.toArray(String[]::new), out, err);
            if (code == 2 || code == 130) return code;
            if (code == 0) complete++; else failed++;
        }
        out.println("Decompiled " + inputs.size() + " inputs into " + Main.safe(folder.toString()) + ": " + complete
            + " compiled with every signature, " + failed + " partial or failed.");
        return failed == 0 ? 0 : 1;
    }

    /** The positional inputs, with each folder replaced by its .jar and .class files (sorted, not recursive). */
    static List<Path> inputs(List<String> names) throws IOException {
        var inputs = new ArrayList<Path>();
        for (String name : names) {
            Path path = Path.of(name);
            if (!Files.isDirectory(path)) { inputs.add(path); continue; }
            try (var files = Files.list(path)) {
                var found = files.filter(file -> Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
                    .filter(file -> file.getFileName().toString().toLowerCase(Locale.ROOT).matches(".*\\.(jar|class)")).sorted().toList();
                if (found.isEmpty()) throw new IllegalArgumentException("no .jar or .class file in " + name);
                inputs.addAll(found);
            }
        }
        if (inputs.size() > MAX_FOLDER_INPUTS) throw new IllegalArgumentException("at most " + MAX_FOLDER_INPUTS + " inputs per run");
        return inputs;
    }

    private static int single(String[] args, PrintStream out, PrintStream err) {
        try {
            var nativeOptions = new NativeOptions[1];
            var options = JobOptions.parse(NativeOptions.strip(args, nativeOptions), "cfr,vineflower");
            Path output = options.output();
            if (IdentifyCommand.needsIdentification(options.input()))
                out.println("Input is not named .class or .jar: identifying it from its bytes (nothing is run).");
            out.println("Preparing input; selected engines run in separate JVMs (2 to 4 concurrently, by processor count).");
            var decompiled = new DecompileService().decompile(options.request(), progress(err));
            var job = decompiled.job();
            if (job.deobfuscation() != null)
                out.println("Deobfuscation: " + Main.safe(dev.classrevive.pipeline.AutoDeobfuscation.headline(job.deobfuscation())));
            for (var result : job.engines()) {
                out.printf("%s %s: %s (%d source files, %d ms)%n", result.engine(), result.version(),
                    result.status(), result.sourceFiles(), result.elapsedMillis());
            }
            out.println("Result: " + Main.safe(output.toAbsolutePath().normalize().toString()));
            var verified = job.selection().verification();
            out.printf("Selected: %s | compilation=%s | signatures=%d/%d | placeholder-signals=%d%n",
                job.selection().candidate(), verified.status(), verified.matchedSymbols(), verified.expectedSymbols(), verified.placeholderSignals());
            int missing = verified.detail().indexOf("missing packages (supply with --classpath): ");
            if (missing >= 0) {
                String list = verified.detail().substring(missing + "missing packages (supply with --classpath): ".length());
                int end = list.indexOf(';');
                out.println("Likely missing dependencies (pass their JARs with --classpath): " + Main.safe(end < 0 ? list : list.substring(0, end)));
            }
            out.println("Sources: selection/sources. Behavior not verified; see selection/selection.txt for evidence.");
            if (job.fallback() != null) out.println("Bytecode fallback: " + job.fallback().status() + " (fallback/bytecode)");
            Path absolute = decompiled.output();
            if (decompiled.origin() != null) out.println(Main.safe(decompiled.origin()));
            nativeOptions[0].run(absolute, absolute.resolve("report.txt"), absolute.resolve("report.json"), out);
            return verified.complete() ? 0 : 1;
        } catch (IllegalArgumentException error) {
            err.println("Invalid command arguments: " + Main.safe(error.getMessage()));
            return 2;
        } catch (dev.classrevive.pipeline.InputIdentifier.NoJavaException error) {
            return IdentifyCommand.noJava(error, out, err);
        } catch (FileAlreadyExistsException error) {
            err.println("Output already exists. Choose a new directory; existing results were not overwritten.");
            return 1;
        } catch (IOException error) {
            err.println("Decompilation failed: " + Main.safe(String.valueOf(error.getMessage())));
            return 1;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            err.println("Decompilation interrupted.");
            return 130;
        }
    }

    /**
     * Phase 2CV: each stage on standard error, only when it is the interactive terminal, so scripts that read standard
     * output (or capture both streams) see exactly what they saw before.
     */
    static JobListener progress(PrintStream err) {
        if (System.console() == null || err != System.err) return JobListener.NONE;
        var names = Map.of(JobListener.Stage.INTAKE, "reading and checking the input", JobListener.Stage.ENGINES, "running the decompilers",
            JobListener.Stage.STUBS, "inferring missing libraries", JobListener.Stage.SELECTION, "recompiling and choosing the best source",
            JobListener.Stage.PER_FILE, "measuring file by file", JobListener.Stage.FALLBACK, "writing readable bytecode",
            JobListener.Stage.REPORTS, "writing the reports");
        return new JobListener() {
            @Override public void stage(Stage stage) { err.println("  [" + (stage.ordinal() + 1) + "/" + Stage.values().length + "] " + names.get(stage)); }
        };
    }
}
