package dev.classrevive.engine;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayDeque;

/**
 * Phase 2DI: the link rule for paths the user names (an input, a corpus, an output folder). The folders above the named
 * item are the user's or the system's, not the target's: links there are followed once, by resolving the deepest
 * existing one with {@link Path#toRealPath}, so macOS's {@code /var -> /private/var} (where {@code java.io.tmpdir} lives)
 * or a user's own link to another disk are accepted, and every later check and write uses the resolved path. The named
 * item itself must not be a symbolic link or another special file (a Windows junction is a reparse point that NIO reports
 * as {@code isOther}). Links met inside a corpus, a dependency folder or a folder ClassRevive created are still refused
 * by the walks that read or write them; this class does not relax those.
 */
public final class SafePaths {
    private SafePaths() {}

    /**
     * Returns {@code path} absolute and normalized, with its parent folders resolved to their real location, and refuses
     * it when the item itself is a link or a special file. A missing item or missing parent folders are allowed (an output
     * folder about to be created); the caller still checks what must exist.
     */
    public static Path resolveParents(Path path) throws IOException {
        Path absolute = path.toAbsolutePath().normalize();
        Path name = absolute.getFileName();
        if (name == null) return absolute;
        var missing = new ArrayDeque<Path>();
        Path existing = absolute.getParent();
        while (existing != null && !Files.exists(existing)) { missing.push(existing.getFileName()); existing = existing.getParent(); }
        Path resolved = existing == null ? absolute.getParent() : existing.toRealPath();
        while (!missing.isEmpty()) resolved = resolved.resolve(missing.pop());
        resolved = resolved.resolve(name);
        try {
            var attributes = Files.readAttributes(resolved, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (attributes.isSymbolicLink() || attributes.isOther())
                throw new IOException("Linked or special paths are not supported: " + name);
        } catch (NoSuchFileException absent) { /* A new output does not exist yet. */ }
        return resolved;
    }
}
