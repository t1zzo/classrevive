package dev.classrevive.workbench;

import dev.classrevive.engine.DecompilerEngine;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;

/**
 * A validated decompile job: what {@code classrevive decompile} options describe, for any front end.
 * {@code engineCache}: Phase 2CH output cache directory, or null; {@code stubs}: Phase 2CT inferred stubs; {@code entry}:
 * Phase 2DB nested jar to take from a bundle of jars, or null for the default rule; {@code deobfuscate}: Phase 2DD automatic
 * reversal of an obfuscator the diagnosis recognizes with HIGH confidence; {@code inferNames}: Phase 2DK guessed names for obfuscated
 * classes and private members, marked as inferred: null (off, the default), or the lowest confidence kept ({@code high},
 * {@code medium} or {@code low}).
 */
public record DecompileRequest(Path input, Path output, List<DecompilerEngine> engines, Duration timeout, List<Path> dependencies,
                               Path engineCache, boolean stubs, String entry, boolean deobfuscate, String inferNames) {
    public DecompileRequest(Path input, Path output, List<DecompilerEngine> engines, Duration timeout, List<Path> dependencies,
                            Path engineCache, boolean stubs, String entry, boolean deobfuscate) {
        this(input, output, engines, timeout, dependencies, engineCache, stubs, entry, deobfuscate, null);
    }
    public DecompileRequest(Path input, Path output, List<DecompilerEngine> engines, Duration timeout, List<Path> dependencies,
                            Path engineCache, boolean stubs) {
        this(input, output, engines, timeout, dependencies, engineCache, stubs, null);
    }
    /** With the automatic reversal on (the default). */
    public DecompileRequest(Path input, Path output, List<DecompilerEngine> engines, Duration timeout, List<Path> dependencies,
                            Path engineCache, boolean stubs, String entry) {
        this(input, output, engines, timeout, dependencies, engineCache, stubs, entry, true);
    }
    /** The same job with the automatic reversal turned on or off. */
    public DecompileRequest withDeobfuscation(boolean enabled) {
        return new DecompileRequest(input, output, engines, timeout, dependencies, engineCache, stubs, entry, enabled, inferNames);
    }
    /** The same job with inferred names off ({@code null}) or kept down to the given confidence ({@code high}, {@code medium}, {@code low}). */
    public DecompileRequest withInferNames(String level) {
        return new DecompileRequest(input, output, engines, timeout, dependencies, engineCache, stubs, entry, deobfuscate, level);
    }

    public DecompileRequest {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(output, "output");
        engines = List.copyOf(engines);
        if (engines.isEmpty() || engines.size() > 4) throw new IllegalArgumentException("Select one to four engines");
        timeout = RequestLimits.timeout(timeout);
        dependencies = RequestLimits.dependencies(dependencies);
        if (entry != null && entry.isBlank()) throw new IllegalArgumentException("Empty --entry name");
        if (inferNames != null && !Set.of("off", "high", "medium", "low").contains(inferNames)) throw new IllegalArgumentException("--infer-names takes off, high, medium or low");
        if ("off".equals(inferNames)) inferNames = null;
    }

    /** The CLI defaults: CFR and Vineflower, 120 seconds per worker, no dependencies or cache, stubs and automatic reversal on. */
    public static DecompileRequest defaults(Path input, Path output) {
        return new DecompileRequest(input, output, EngineRegistry.parse("cfr,vineflower"), Duration.ofSeconds(120), List.of(), null, true);
    }
}
