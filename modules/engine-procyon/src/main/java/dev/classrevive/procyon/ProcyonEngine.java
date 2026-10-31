package dev.classrevive.procyon;

import dev.classrevive.engine.*;
import java.nio.file.Path;
import java.util.List;

public final class ProcyonEngine implements DecompilerEngine {
    public String id() { return "procyon"; }
    public String version() { return "0.6.0"; }
    public List<String> command(Path input, Path output) {
        return EngineCommand.sizedHeap(input, EngineCommand.javaWorker(ProcyonEngine.class, "dev.classrevive.procyon.ProcyonWorker",
            List.of("com/strobel/decompiler/Decompiler.class", "com/strobel/core/VerifyArgument.class"), input.toString(), output.toString()));
    }
}
