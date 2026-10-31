package dev.classrevive.engine;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class SafePathsTest {
    @TempDir Path directory;

    /** A link standing for a parent folder, as {@code /var -> /private/var} on macOS; skipped where links cannot be made. */
    static Path linkOrSkip(Path link, Path target) {
        try { return Files.createSymbolicLink(link, target); }
        catch (IOException | UnsupportedOperationException | SecurityException unavailable) {
            Assumptions.abort("Symbolic links unavailable here (Windows needs Developer Mode or the privilege): " + unavailable);
            return null;
        }
    }

    @Test void followsLinkedParentFoldersOnceAndKeepsMissingOnes() throws Exception {
        Path real = Files.createDirectory(directory.resolve("real"));
        Path alias = linkOrSkip(directory.resolve("alias"), real);
        assertEquals(real.toRealPath().resolve("result"), SafePaths.resolveParents(alias.resolve("result")));
        assertEquals(real.toRealPath().resolve("a/b/result"), SafePaths.resolveParents(alias.resolve("a/b/result")));
        assertEquals(real.toRealPath().resolve("result"), SafePaths.resolveParents(alias.resolve("x/../result")));
    }

    @Test void refusesTheNamedItemWhenItIsALink() throws Exception {
        Path real = Files.createDirectory(directory.resolve("real"));
        Path alias = linkOrSkip(directory.resolve("alias"), real);
        assertThrows(IOException.class, () -> SafePaths.resolveParents(alias));
        Path file = Files.writeString(real.resolve("input.jar"), "x");
        Path fileLink = linkOrSkip(directory.resolve("link.jar"), file);
        assertThrows(IOException.class, () -> SafePaths.resolveParents(fileLink));
        // Through a linked parent, the regular file itself is accepted.
        assertEquals(file.toRealPath(), SafePaths.resolveParents(alias.resolve("input.jar")));
    }

    /** Windows junctions need no privilege: one above the item is followed, the item itself being one is refused. */
    @Test void followsAParentJunctionAndRefusesAJunctionItem() throws Exception {
        Assumptions.assumeTrue(System.getProperty("os.name").startsWith("Windows"));
        Path real = Files.createDirectory(directory.resolve("real"));
        Path junction = directory.resolve("junction");
        var process = new ProcessBuilder("cmd.exe", "/c", "mklink", "/J", junction.toString(), real.toString()).redirectErrorStream(true).start();
        String message = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(0, process.waitFor(), message);
        try {
            assertEquals(real.toRealPath().resolve("result"), SafePaths.resolveParents(junction.resolve("result")));
            assertThrows(IOException.class, () -> SafePaths.resolveParents(junction));
        } finally { Files.delete(junction); }
    }

    @Test void plainPathsAreOnlyNormalized() throws Exception {
        Path plain = directory.resolve("new/output");
        assertEquals(directory.toRealPath().resolve("new/output"), SafePaths.resolveParents(plain));
        Path root = directory.getRoot();
        assertEquals(root, SafePaths.resolveParents(root));
    }
}
