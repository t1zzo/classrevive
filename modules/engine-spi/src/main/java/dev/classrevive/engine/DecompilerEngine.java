package dev.classrevive.engine;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public interface DecompilerEngine {
    String id();
    String version();
    List<String> command(Path input, Path output);
    /** Environment variables set for the worker in addition to the inherited ones (phase 2CC: JAVA_HOME for Ghidra). */
    default Map<String,String> environment() { return Map.of(); }
    /**
     * Phase 2CX: whether {@link #command} also takes an archive of Dalvik dex files. For an Android input whose converted
     * classes were not renamed or rewritten, such an engine gets the original dex instead of the converted jar.
     */
    default boolean readsDalvik() { return false; }
}
