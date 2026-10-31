package dev.classrevive.pipeline;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.jar.Manifest;
import java.util.zip.ZipFile;

final class ArchiveGuards {
    private ArchiveGuards() {}
    static void rejectSignedOrDigested(Path input) throws IOException {
        try (var zip = new ZipFile(input.toFile())) {
            for (var entry : zip.stream().toList()) {
                String name = entry.getName().toUpperCase(Locale.ROOT);
                if (name.startsWith("META-INF/") && (name.endsWith(".SF") || name.endsWith(".RSA") || name.endsWith(".DSA") || name.endsWith(".EC") || name.startsWith("META-INF/SIG-")))
                    throw new IOException("Signed JAR rewriting is unsupported; original retained");
                if (name.equals("META-INF/MANIFEST.MF")) {
                    Manifest manifest;
                    try (var stream = zip.getInputStream(entry)) { manifest = new Manifest(stream); }
                    var attributes = new ArrayList<java.util.jar.Attributes>(manifest.getEntries().values());
                    attributes.add(manifest.getMainAttributes());
                    if (attributes.stream().flatMap(a -> a.keySet().stream()).anyMatch(k -> k.toString().toUpperCase(Locale.ROOT).contains("-DIGEST")))
                        throw new IOException("Digest-bearing JAR rewriting is unsupported; original retained");
                }
            }
        }
    }
}
