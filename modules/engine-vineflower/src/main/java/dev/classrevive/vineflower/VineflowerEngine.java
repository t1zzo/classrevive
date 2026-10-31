package dev.classrevive.vineflower;

import dev.classrevive.engine.*;
import java.nio.file.Path;
import java.util.List;

public final class VineflowerEngine implements DecompilerEngine {
    public String id() { return "vineflower"; }
    public String version() { return "1.11.1"; }
    public List<String> command(Path input, Path output) {
        // Phase 2DG: Vineflower's bundled Kotlin plugin writes every class carrying @kotlin.Metadata as a .kt file, which
        // selection and javac never read; kotlin-stdlib 2.4.20 gave 0 of 671 Kotlin files. Off, those classes come out as
        // Java like the other engines'. Classes without the annotation are not affected.
        return EngineCommand.sizedHeap(input, EngineCommand.javaJar(VineflowerEngine.class, "org/jetbrains/java/decompiler/main/decompiler/ConsoleDecompiler.class",
            "--kt-enable=0", "--folder", input.toString(), output.toString()));
    }
}
