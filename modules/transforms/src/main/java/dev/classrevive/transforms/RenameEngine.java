package dev.classrevive.transforms;

import dev.classrevive.engine.*;
import java.nio.file.Path;
import java.util.List;

public final class RenameEngine implements DecompilerEngine {
    private final Path mapping;
    public RenameEngine() { this(null); }
    public RenameEngine(Path mapping) { this.mapping = mapping; }
    public String id() { return "static-rename"; }
    public String version() { return "2"; }
    public List<String> command(Path input, Path output) {
        var resources = List.of("org/objectweb/asm/ClassReader.class", "org/objectweb/asm/tree/ClassNode.class", "org/objectweb/asm/commons/ClassRemapper.class");
        if (mapping == null)
            return EngineCommand.javaWorker(RenameEngine.class, "dev.classrevive.transforms.RenameWorker", resources, input.toString(), output.toString());
        return EngineCommand.javaWorker(RenameEngine.class, "dev.classrevive.transforms.RenameWorker", resources, input.toString(), output.toString(), mapping.toString());
    }
}
