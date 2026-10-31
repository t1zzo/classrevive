package dev.classrevive.pipeline;

import dev.classrevive.engine.DecompilerEngine;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;

/**
 * Phase 2CH: what a benchmark row's result depends on, as one SHA-256: every sample and dependency by content, the
 * engines and their versions, the timeout, and the ClassRevive build itself (the bytes of every entry of the running
 * class path). A resumed run must match it exactly, so rows measured by different code are never mixed.
 */
final class RunFingerprint {
    private RunFingerprint() {}

    static String of(List<Path> inputs, Map<Path,List<Path>> dependencies, List<DecompilerEngine> engines, Duration timeout) throws IOException {
        var digest = sha256();
        update(digest, "classrevive-benchmark-v1");
        for (var input : inputs) {
            update(digest, input.getFileName().toString()); file(digest, input);
            for (var jar : dependencies.getOrDefault(input, List.of())) { update(digest, jar.getFileName().toString()); file(digest, jar); }
        }
        for (var engine : engines) { update(digest, engine.id()); update(digest, engine.version()); }
        update(digest, Long.toString(timeout.toSeconds()));
        for (String entry : System.getProperty("java.class.path", "").split(java.util.regex.Pattern.quote(File.pathSeparator))) {
            if (entry.isBlank()) continue;
            Path path = Path.of(entry);
            if (Files.isRegularFile(path)) { update(digest, "jar"); file(digest, path); }
            else if (Files.isDirectory(path)) {
                try (var paths = Files.walk(path)) {
                    for (var item : paths.filter(Files::isRegularFile).sorted().toList()) {
                        update(digest, path.relativize(item).toString().replace('\\', '/')); file(digest, item);
                    }
                }
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }
    private static void file(MessageDigest digest, Path file) throws IOException {
        try (var in = Files.newInputStream(file)) {
            byte[] buffer = new byte[65536]; int read;
            while ((read = in.read(buffer)) != -1) digest.update(buffer, 0, read);
        }
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
