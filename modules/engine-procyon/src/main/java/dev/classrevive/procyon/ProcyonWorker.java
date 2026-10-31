package dev.classrevive.procyon;

import com.strobel.assembler.metadata.*;
import com.strobel.decompiler.*;
import java.nio.file.*;
import java.util.jar.JarFile;
import java.util.*;

/** Reads only the normalized JAR supplied by the parent; never defines target classes. */
public final class ProcyonWorker {
    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Expected normalized JAR and output directory");
        Path output = Path.of(args[1]).toAbsolutePath().normalize();
        int failures = 0;
        try (var jar = new JarFile(args[0])) {
            var loader = new JarTypeLoader(jar);
            var metadata = new MetadataSystem(loader);
            var settings = DecompilerSettings.javaDefaults();
            settings.setTypeLoader(loader);
            var options = new DecompilationOptions(); options.setSettings(settings); options.setFullDecompilation(true);
            var entries = jar.stream().filter(e -> e.getName().endsWith(".class")).limit(20001)
                .sorted(Comparator.comparingInt(entry -> entry.getName().length())).toList();
            if (entries.size() > 20000) throw new IllegalArgumentException("Too many classes");
            var emitted = new HashSet<String>();
            for (var entry : entries) {
                String name = entry.getName().substring(0, entry.getName().length() - 6);
                Path destination = output.resolve(name + ".java").normalize();
                if (!destination.startsWith(output)) throw new IllegalArgumentException("Unsafe class path");
                try {
                    var reference = metadata.lookupType(name);
                    var type = reference == null ? null : reference.resolve();
                    if (type == null) throw new IllegalStateException("Class metadata unavailable");
                    if (coveredByEnclosingType(type, emitted)) continue;
                    Files.createDirectories(destination.getParent());
                    try (var writer = Files.newBufferedWriter(destination, StandardOpenOption.CREATE_NEW)) {
                        settings.getLanguage().decompileType(type, new PlainTextOutput(writer), options);
                    }
                    emitted.add(name);
                } catch (RuntimeException error) {
                    failures++; System.err.println("Failed class " + name + ": " + error.getClass().getSimpleName());
                }
            }
        }
        if (failures > 0) System.exit(1);
    }
    private static boolean coveredByEnclosingType(TypeDefinition type, Set<String> emitted) {
        var parent = type.getDeclaringType();
        var seen = new HashSet<String>();
        while (parent != null && seen.size() < 1000 && seen.add(parent.getInternalName())) {
            if (emitted.contains(parent.getInternalName())) return true;
            parent = parent.getDeclaringType();
        }
        return false;
    }
}
