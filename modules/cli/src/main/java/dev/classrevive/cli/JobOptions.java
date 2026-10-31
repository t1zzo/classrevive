package dev.classrevive.cli;

import dev.classrevive.engine.DecompilerEngine;
import dev.classrevive.workbench.*;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;

/** {@code engineCache}: Phase 2BX opt-in engine output cache, or null; {@code resume}: continue an interrupted benchmark. */
/** Phase 2DB: {@code entry}: the nested jar to take from a bundle of jars (decompile only), or null. */
/** Phase 2DD: {@code deobfuscate}: automatic reversal ({@code --deobfuscate auto|off}, decompile only). */
record JobOptions(Path input, Path output, Duration timeout, List<DecompilerEngine> engines, List<Path> dependencies, Path engineCache, boolean resume,
                  boolean stubs, String entry, boolean deobfuscate) {
    static JobOptions parse(String[] args, String defaultEngines) {
        if (args.length < 4 || args.length % 2 != 0) throw new IllegalArgumentException("Expected input and option/value pairs");
        var options = new HashMap<String,String>();
        for (int i = 2; i < args.length; i += 2) {
            if (!Set.of("--output", "--timeout-seconds", "--engines", "--classpath", "--engine-cache", "--resume", "--stubs", "--entry", "--deobfuscate").contains(args[i]))
                throw new IllegalArgumentException("Unknown option: " + args[i]);
            if (options.putIfAbsent(args[i], args[i+1]) != null) throw new IllegalArgumentException("Repeated option: " + args[i]);
        }
        if (!options.containsKey("--output")) throw new IllegalArgumentException("Missing --output <new-directory>");
        Duration timeout = RequestLimits.timeoutSeconds(Integer.parseInt(options.getOrDefault("--timeout-seconds", "120")));
        var dependencies=new ArrayList<Path>();
        if(options.containsKey("--classpath")) {
            if(!args[0].equals("decompile")) throw new IllegalArgumentException("--classpath is supported only by decompile");
            for(String path:options.get("--classpath").split(java.util.regex.Pattern.quote(java.io.File.pathSeparator),-1)) {
                if(path.isBlank()) throw new IllegalArgumentException("Empty classpath entry");
                dependencies.add(Path.of(path));
            }
            RequestLimits.dependencies(dependencies);
        }
        String resume = options.getOrDefault("--resume", "false");
        if (!resume.equals("true") && !resume.equals("false")) throw new IllegalArgumentException("--resume takes true or false");
        if (resume.equals("true") && !args[0].equals("benchmark")) throw new IllegalArgumentException("--resume is supported only by benchmark");
        // Phase 2CT: inferred stubs for missing types (decompile and benchmark), on by default.
        String stubs = options.getOrDefault("--stubs", "true");
        if (!stubs.equals("true") && !stubs.equals("false")) throw new IllegalArgumentException("--stubs takes true or false");
        String entry = options.get("--entry");
        if (entry != null && !args[0].equals("decompile")) throw new IllegalArgumentException("--entry is supported only by decompile and diagnose");
        if (entry != null && entry.isBlank()) throw new IllegalArgumentException("Empty --entry name");
        // Phase 2DD: reversal of an obfuscator the diagnosis recognizes with HIGH confidence, on by default.
        String deobfuscate = options.getOrDefault("--deobfuscate", "auto");
        if (!deobfuscate.equals("auto") && !deobfuscate.equals("off")) throw new IllegalArgumentException("--deobfuscate takes auto or off");
        if (options.containsKey("--deobfuscate") && !args[0].equals("decompile")) throw new IllegalArgumentException("--deobfuscate is supported only by decompile");
        String cache = options.get("--engine-cache");
        if (cache != null && cache.isBlank()) throw new IllegalArgumentException("Empty --engine-cache directory");
        return new JobOptions(Path.of(args[1]), Path.of(options.get("--output")), timeout,
            EngineRegistry.parse(options.getOrDefault("--engines", defaultEngines)),List.copyOf(dependencies), cache == null ? null : Path.of(cache),
            resume.equals("true"), stubs.equals("true"), entry, deobfuscate.equals("auto"));
    }

    /** Phase G1: the same job as a front-end-neutral request. */
    DecompileRequest request() { return new DecompileRequest(input, output, engines, timeout, dependencies, engineCache, stubs, entry, deobfuscate); }
}
