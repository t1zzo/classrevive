package dev.classrevive.pipeline;

import dev.classrevive.engine.DecompilerEngine;
import dev.classrevive.engine.EngineResult;
import dev.classrevive.engine.ProcessRunner;
import dev.classrevive.engine.SafePaths;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;

/**
 * Phase 2CH: an opt-in cache of engine outputs. A decompiler's sources depend only on its input and on the engine
 * itself, so the key is the SHA-256 of the normalized class JAR plus a fingerprint of the engine's worker command:
 * every argument except the input and output paths, the bytes of every class-path entry it runs (the adapter and
 * the engine library, also when passed as {@code -jar}) and the JVM version. A changed engine, adapter, heap, flag or
 * JDK gives a new key. Only an {@code OUTPUT_PRODUCED} run with exit code 0 is stored; failures, timeouts and budget
 * refusals always run again. A reused result says so in its message, and its elapsed time is the copy's, not a
 * decompilation's. Entries are written to a temporary folder and moved into place, so a half-written entry is never read.
 * A corrupt entry is deleted and the engine runs; the cache is an optimization and never fails a job.
 */
final class EngineCache {
    private final Path root;
    EngineCache(Path root) throws IOException {
        this.root = SafePaths.resolveParents(root);
        Files.createDirectories(this.root);
    }

    EngineResult run(DecompilerEngine engine, Path normalized, Path engineOutput, Duration timeout) throws IOException {
        String key = key(engine, normalized, engineOutput);
        Path entry = root.resolve(key);
        long start = System.nanoTime();
        var properties = entry.resolve("result.properties");
        if (Files.isRegularFile(properties, LinkOption.NOFOLLOW_LINKS) && Files.isDirectory(entry.resolve("sources"), LinkOption.NOFOLLOW_LINKS)) {
            boolean outputCreated = false;
            try {
                var values = new Properties();
                try (var in = Files.newInputStream(properties)) { values.load(in); }
                Files.createDirectory(engineOutput);
                outputCreated = true;
                long files = copySources(entry.resolve("sources"), Files.createDirectory(engineOutput.resolve("sources")));
                Files.writeString(engineOutput.resolve("engine.log"), "Reused from the engine cache, key " + key + "\n", StandardCharsets.UTF_8);
                return new EngineResult(engine.id(), engine.version(), EngineResult.Status.OUTPUT_PRODUCED, 0, files,
                    (System.nanoTime() - start) / 1_000_000, "Reused from the engine cache (first run " + values.getProperty("elapsed-ms", "?")
                        + " ms); correctness not verified");
            } catch (IOException | RuntimeException unusable) {
                // A cache is an optimization: a corrupt or unreadable entry must never fail the job.
                deleteTree(entry);
                if (outputCreated) deleteTree(engineOutput);
            }
        }
        var result = new ProcessRunner().run(engine, normalized, engineOutput, timeout);
        if (result.status() == EngineResult.Status.OUTPUT_PRODUCED && result.exitCode() == 0 && !Files.exists(entry)) {
            Path temporary = Files.createTempDirectory(root, "tmp-");
            try {
                copySources(engineOutput.resolve("sources"), Files.createDirectory(temporary.resolve("sources")));
                var values = new Properties();
                values.setProperty("engine", engine.id()); values.setProperty("version", engine.version());
                values.setProperty("elapsed-ms", Long.toString(result.elapsedMillis()));
                try (var out = Files.newOutputStream(temporary.resolve("result.properties"))) { values.store(out, "Engine cache entry"); }
                Files.move(temporary, entry, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException | RuntimeException race) {
                deleteTree(temporary); // Another job stored the same key first, or the copy failed: the run's own result stands.
            }
        }
        return result;
    }

    /** SHA-256 over the input's bytes, the JVM version and the worker command with its input and output paths replaced. */
    static String key(DecompilerEngine engine, Path normalized, Path engineOutput) throws IOException {
        var digest = sha256();
        update(digest, "engine-cache-key-v2");
        update(digest, "input");
        try (var in = Files.newInputStream(normalized)) {
            byte[] buffer = new byte[8192]; int read;
            while ((read = in.read(buffer)) != -1) digest.update(buffer, 0, read);
        }
        update(digest, engine.id()); update(digest, engine.version());
        // Decompiler output can change with the JDK that runs the worker, so the runtime is part of the key.
        update(digest, "jvm:" + System.getProperty("java.version") + ":" + System.getProperty("java.vendor"));
        String input = normalized.toAbsolutePath().toString(), output = engineOutput.resolve("sources").toAbsolutePath().toString();
        var command = engine.command(normalized.toAbsolutePath(), engineOutput.resolve("sources").toAbsolutePath());
        for (int i = 0; i < command.size(); i++) {
            String argument = command.get(i).replace(input, "<input>").replace(output, "<output>");
            if (i == 0) argument = "<java>"; // The JVM path differs between machines; the JDK is covered above.
            update(digest, argument);
            if (i == 0) continue;
            String previous = command.get(i - 1);
            if (previous.equals("-cp") || previous.equals("-classpath"))
                for (String part : command.get(i).split(java.util.regex.Pattern.quote(java.io.File.pathSeparator))) fingerprint(digest, Path.of(part));
            else if (previous.equals("-jar")) fingerprint(digest, Path.of(command.get(i)));
        }
        return HexFormat.of().formatHex(digest.digest());
    }
    private static void fingerprint(MessageDigest digest, Path part) throws IOException {
        if (Files.isRegularFile(part)) { update(digest, part.getFileName().toString()); digest.update(Files.readAllBytes(part)); return; }
        if (!Files.isDirectory(part)) { update(digest, "missing:" + part.getFileName()); return; }
        try (var paths = Files.walk(part)) {
            for (var path : paths.filter(Files::isRegularFile).sorted().toList()) {
                update(digest, part.relativize(path).toString().replace('\\', '/'));
                digest.update(Files.readAllBytes(path));
            }
        }
    }
    private static long copySources(Path from, Path to) throws IOException {
        long files = 0;
        try (var paths = Files.walk(from)) {
            for (var path : paths.sorted().toList()) {
                if (Files.isSymbolicLink(path)) throw new IOException("Unsafe engine sources");
                if (!Files.isRegularFile(path)) continue;
                Path target = to.resolve(from.relativize(path).toString()).normalize();
                if (!target.startsWith(to)) throw new IOException("Unsafe engine sources");
                Files.createDirectories(target.getParent());
                Files.copy(path, target);
                if (path.toString().endsWith(".java")) files++;
            }
        }
        return files;
    }
    private static void deleteTree(Path root) {
        try (var paths = Files.walk(root)) {
            for (var path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        } catch (IOException ignored) { }
    }
    private static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static void update(MessageDigest digest, String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        digest.update((byte) (bytes.length >>> 24)); digest.update((byte) (bytes.length >>> 16)); digest.update((byte) (bytes.length >>> 8));
        digest.update((byte) bytes.length); digest.update(bytes);
    }
}
