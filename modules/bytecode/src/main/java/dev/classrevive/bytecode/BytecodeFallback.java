package dev.classrevive.bytecode;

import dev.classrevive.engine.EngineResult;
import java.nio.file.Path;
import java.time.Duration;
import dev.classrevive.engine.*;
import java.util.*;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.util.TraceClassVisitor;

public final class BytecodeFallback {
    public EngineResult dump(Path jar, Path output, Duration timeout) {
        var engine = new DecompilerEngine() {
            public String id() { return "bytecode"; }
            public String version() { return "ASM-9.7.1"; }
            public List<String> command(Path input, Path destination) {
                try {
                    var paths = new LinkedHashSet<String>();
                    for (var type : List.of(BytecodeWorker.class, ClassReader.class, TraceClassVisitor.class))
                        paths.add(Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString());
                    return List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                        "-Xmx256m", "-Dfile.encoding=UTF-8", "-cp", String.join(java.io.File.pathSeparator, paths),
                        BytecodeWorker.class.getName(), input.toString(), destination.toString());
                } catch (Exception error) { throw new IllegalStateException(error); }
            }
        };
        return new ProcessRunner().runDisassembler(engine, jar, output, timeout);
    }
}
