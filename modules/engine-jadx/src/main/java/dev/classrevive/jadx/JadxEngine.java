package dev.classrevive.jadx;

import dev.classrevive.engine.*;
import java.nio.file.Path;
import java.util.List;

public final class JadxEngine implements DecompilerEngine {
    public String id() { return "jadx"; }
    public String version() { return "1.5.3"; }
    /** Phase 2CX: JADX reads Dalvik bytecode natively, so an Android input reaches it as the original dex files. */
    @Override public boolean readsDalvik() { return true; }
    public List<String> command(Path input, Path output) {
        return EngineCommand.sizedHeap(input, EngineCommand.javaWorker(JadxEngine.class, "dev.classrevive.jadx.JadxWorker", List.of(
            "jadx/api/JadxDecompiler.class", "jadx/api/plugins/input/insns/InsnData.class",
            "jadx/zip/ZipReader.class", "jadx/plugins/input/java/JavaInputPlugin.class", "jadx/plugins/input/dex/DexInputPlugin.class",
            "org/slf4j/Logger.class", "com/google/gson/Gson.class", "org/objectweb/asm/ClassReader.class",
            "com/google/errorprone/annotations/CheckReturnValue.class",
            "io/github/skylot/raung/common/asm/TypeRef.class", "io/github/skylot/raung/disasm/api/IRaungDisasm.class"
        ), input.toString(), output.toString()));
    }
}
