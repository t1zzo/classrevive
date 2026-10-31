package dev.classrevive.jadx;

import jadx.api.*;
import jadx.api.plugins.JadxPlugin;
import jadx.api.plugins.loader.JadxPluginLoader;
import jadx.core.plugins.files.IJadxFilesGetter;
import jadx.plugins.input.dex.DexInputPlugin;
import jadx.plugins.input.java.JavaInputPlugin;
import java.nio.file.*;
import java.util.*;

/**
 * Explicit plugin set: Java class input and (Phase 2CX) JADX's own dex input; no user plugins, scripts, network
 * installation or target execution. An archive of dex files is read by the dex plugin, a jar by the Java one.
 */
public final class JadxWorker {
    public static void main(String[] arguments) throws Exception {
        if (arguments.length != 2) throw new IllegalArgumentException("Expected a normalized JAR (or dex archive) and output directory");
        Path output = Path.of(arguments[1]).toAbsolutePath().normalize();
        Path state = Files.createDirectory(output.resolve(".jadx-state"));
        Path config = Files.createDirectory(state.resolve("config"));
        Path cache = Files.createDirectory(state.resolve("cache"));
        Path temp = Files.createDirectory(state.resolve("tmp"));
        var args = new JadxArgs();
        args.setInputFile(Path.of(arguments[0]).toFile());
        args.setOutDir(output.toFile()); args.setOutDirSrc(output.toFile());
        args.setSkipResources(true); args.setThreadsCount(1);
        args.setDeobfuscationOn(false); args.setRenameFlags(Set.of());
        args.setRespectBytecodeAccModifiers(true);
        args.setFilesGetter(new IJadxFilesGetter() {
            public Path getConfigDir() { return config; }
            public Path getCacheDir() { return cache; }
            public Path getTempDir() { return temp; }
        });
        args.setPluginLoader(new JadxPluginLoader() {
            public List<JadxPlugin> load() { return List.of(new JavaInputPlugin(), new DexInputPlugin()); }
            public void close() {}
        });
        int errors;
        try (var decompiler = new JadxDecompiler(args)) {
            decompiler.load(); decompiler.saveSources();
            errors = decompiler.getErrorsCount(); decompiler.printErrorsReport();
        }
        if (errors > 0) System.exit(1);
    }
}
