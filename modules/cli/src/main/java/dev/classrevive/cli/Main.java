package dev.classrevive.cli;

import dev.classrevive.workbench.EngineRegistry;
import java.io.PrintStream;
import java.nio.file.*;
import java.util.Map;
import dev.classrevive.intake.InventoryReader;
import dev.classrevive.intake.MultiRelease;
import dev.classrevive.model.Limits;

public final class Main {
    public static void main(String[] args) {
        // Phase G3: the desktop application keeps running on its own threads; only a refusal ends the process here.
        if (args.length > 0 && args[0].equals("gui") && !(args.length == 2 && help(args[1]))) {
            int refused = GuiCommand.start(args, System.err);
            if (refused != 0) System.exit(refused);
            return;
        }
        System.exit(run(args, System.out, System.err));
    }
    public static int run(String[] args, PrintStream out, PrintStream err) {
        // Phase 2CV: overview, version and per-command help.
        if (args.length == 0 || (args.length == 1 && (help(args[0]) || args[0].equals("help")))) { Help.overview(out); return 0; }
        if (args.length == 1 && (args[0].equals("--version") || args[0].equals("-V") || args[0].equals("version"))) {
            out.println("ClassRevive " + Help.VERSION);
            return 0;
        }
        if (args[0].equals("help")) return args.length == 2 ? Help.command(args[1], out, err) : 2;
        if (args.length >= 2 && help(args[1])) return Help.command(args[0], out, err);
        if (!Help.COMMANDS.containsKey(args[0])) return Help.unknown(args[0], err);
        args = shortOptions(args);
        if (args[0].equals("gui")) return GuiCommand.start(args, err);
        if (args[0].equals("decompile")) return DecompileCommand.run(args, out, err);
        if (args[0].equals("diagnose")) return DiagnoseCommand.run(args,out,err);
        if (args[0].equals("identify")) return IdentifyCommand.run(args, out, err);
        if (args.length==1 && args[0].equals("sandbox-check")) {
            try {
                var result=new dev.classrevive.sandbox.SandboxProbe().check("docker",java.time.Duration.ofSeconds(5));
                out.println(result.status()+": "+result.detail());
                return result.available()?0:1;
            } catch(InterruptedException interrupted) {
                Thread.currentThread().interrupt(); err.println("Sandbox check interrupted."); return 130;
            }
        }
        if (args[0].equals("compare-reference")) return CompareReferenceCommand.run(args, out, err);
        if (args[0].equals("transform")) return TransformCommand.run(args, out, err);
        if (args[0].equals("rename")) return RenameCommand.run(args, out, err);
        if (args[0].equals("benchmark")) return BenchmarkCommand.run(args, out, err);
        if (java.util.Set.of("lab", "lab-minimize", "lab-promote", "lab-check").contains(args[0])) return LabCommand.run(args, out, err);
        if (args.length == 1 && args[0].equals("engines")) {
            for (var engine : EngineRegistry.all()) out.printf("%s %s%n", engine.id(), engine.version());
            return 0;
        }
        if (args.length != 2 || !args[0].equals("inspect")) {
            // A known command with the wrong arguments: its own usage line (inspect, engines and sandbox-check end here).
            err.println(Help.text(args[0], Help.COMMANDS.get(args[0])).lines().findFirst().orElse(""));
            err.println("Run 'classrevive help " + args[0] + "' for details.");
            return 2;
        }
        try {
            if (IdentifyCommand.needsIdentification(Path.of(args[1])) && Files.isRegularFile(Path.of(args[1]), LinkOption.NOFOLLOW_LINKS)) {
                // Phase 2CE: inspect inventories .class and .jar files; anything else is identified instead (exit 1: no inventory).
                try {
                    var identification = dev.classrevive.pipeline.InputIdentifier.identify(Path.of(args[1]));
                    IdentifyCommand.print(identification, out);
                    err.println("No class inventory: inspect reads .class and .jar files" + (identification.javaAvailable()
                        ? "; decompile and diagnose take the Java found in this file." : "."));
                } catch (java.io.IOException error) {
                    err.println("Identification failed: " + safe(String.valueOf(error.getMessage())));
                }
                return 1;
            }
            var result = new InventoryReader().inspect(Path.of(args[1]), Limits.defaults());
            out.println("Input: " + safe(result.input()));
            if (!result.sha256().isEmpty()) out.println("SHA-256: " + result.sha256());
            out.printf("Bytes: %d input, %d expanded%n", result.inputBytes(), result.expandedBytes());
            long classes = result.entries().stream().filter(e -> e.classInfo() != null).count();
            out.printf("Entries: %d; parsed classes: %d; diagnostics: %d%n", result.entries().size(), classes, result.diagnostics().size());
            var variants = new java.util.HashMap<String, MultiRelease.Variant>();
            MultiRelease.variants(result).forEach(variant -> variants.put(variant.entry(), variant));
            for (var entry : result.entries()) {
                var info = entry.classInfo();
                if (info == null) {
                    out.printf("ENTRY %s (%d bytes)%n", safe(entry.name()), entry.size());
                } else {
                    var variant = variants.get(entry.name());
                    out.printf("CLASS %s | %s | Java %d (%d.%d) | fields=%d methods=%d%s%n",
                        safe(entry.name()), safe(info.name()), info.javaVersion(), info.majorVersion(),
                        info.minorVersion(), info.fields().size(), info.methods().size(),
                        variant == null ? "" : " | multi-release variant for Java " + variant.release() + "+ (decompile uses the base version)");
                }
            }
            for (var diagnostic : result.diagnostics()) {
                err.printf("%s [%s]: %s%n", diagnostic.code(), safe(diagnostic.entry()), safe(diagnostic.message()));
            }
            for (var notice : result.notices()) {
                out.printf("NOTICE %s [%s]: %s%n", notice.code(), safe(notice.entry()), safe(notice.message()));
            }
            out.println("Static inventory only; successful parsing does not establish JVM validity or safety.");
            return result.successful() ? 0 : 1;
        } catch (InvalidPathException error) {
            err.println("Invalid input path.");
            return 2;
        }
    }

    private static boolean help(String arg) { return arg.equals("--help") || arg.equals("-h"); }

    /** Phase 2CV: -o, -e, -t and -cp stand for --output, --engines, --timeout-seconds and --classpath after the command. */
    static String[] shortOptions(String[] args) {
        var aliases = Map.of("-o", "--output", "-e", "--engines", "-t", "--timeout-seconds", "-cp", "--classpath");
        var expanded = args.clone();
        for (int i = 1; i < expanded.length; i++) expanded[i] = aliases.getOrDefault(expanded[i], expanded[i]);
        return expanded;
    }

    static String safe(String value) {
        var result = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isISOControl(c) || Character.getType(c) == Character.FORMAT)
                result.append(String.format("\\u%04x", (int) c));
            else result.append(c);
        }
        return result.toString();
    }
}
