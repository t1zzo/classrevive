package dev.classrevive.transforms;

import dev.classrevive.engine.*;
import java.nio.file.Path;
import java.util.List;

public final class TransformEngine implements DecompilerEngine {
    private final int maxChanges;
    private final String passes;
    public TransformEngine(int maxChanges) { this(maxChanges,BuiltinPasses.DEFAULT); }
    public TransformEngine(int maxChanges,String passes) { BuiltinPasses.names(passes); this.maxChanges=maxChanges; this.passes=passes; }
    public String id() { return "static-transforms"; }
    public String version() { return "1"; }
    public List<String> command(Path input,Path output) {
        // Phase 2CN: whole-jar passes hold every class in memory, so the heap follows the input size.
        return EngineCommand.sizedHeap(input,EngineCommand.javaWorker(TransformEngine.class,"dev.classrevive.transforms.TransformWorker",
            List.of("org/objectweb/asm/ClassReader.class","org/objectweb/asm/tree/ClassNode.class","org/objectweb/asm/tree/analysis/Frame.class",
                "org/objectweb/asm/tree/analysis/Analyzer.class","net/openhft/hashing/LongHashFunction.class"),
            input.toString(),output.toString(),Integer.toString(maxChanges),passes));
    }
}
