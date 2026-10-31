package dev.classrevive.cfr;

import dev.classrevive.engine.*;
import java.nio.file.Path;
import java.util.List;

public final class CfrEngine implements DecompilerEngine {
    public String id() { return "cfr"; }
    public String version() { return "0.152"; }
    public List<String> command(Path input, Path output) {
        return EngineCommand.sizedHeap(input, EngineCommand.javaJar(CfrEngine.class, "org/benf/cfr/reader/Main.class",
            input.toString(), "--outputdir", output.toString(), "--silent", "true"));
    }
}
