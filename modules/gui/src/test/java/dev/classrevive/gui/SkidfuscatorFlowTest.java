package dev.classrevive.gui;

import java.awt.GraphicsEnvironment;
import java.nio.file.*;
import java.util.concurrent.*;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/** Phase G5, in the real window: a Skidfuscator jar is detected, reversed, decompiled and reopened as its reversal. */
class SkidfuscatorFlowTest {
    @TempDir Path directory;

    @Test void detectsReversesAndDecompiles() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "needs a display");
        System.setProperty("classrevive.home", directory.resolve("home").toString());
        try {
            var settings = GuiSettings.load();
            settings.profile(GuiSettings.Profile.CUSTOM);
            settings.customEngines("cfr");
            settings.engineCache(false);
            Text.use(GuiSettings.Language.ENGLISH);
            Path jar = Files.copy(Path.of("../../benchmarks/obfuscated/skidfuscator/skid-fixture-obf.jar"), directory.resolve("skid-app.jar"));
            var window = onEdt(() -> { Theme.install(GuiSettings.ThemeChoice.DARK); return new MainWindow(settings); });
            try {
                SwingUtilities.invokeAndWait(() -> window.openPath(jar));
                waitFor(() -> onEdt(() -> window.workspace() != null));
                assertTrue(window.workspace().obfuscation().skidfuscator());
                assertTrue(onEdt(() -> window.banner().offersReversal()), "the banner offers the reversal");

                SwingUtilities.invokeAndWait(() -> window.decompile(false, true));
                waitFor(() -> onEdt(() -> !window.running()));
                var reversed = onEdt(window::workspace);
                assertEquals("skid-app.jar", reversed.reversedFrom(), "the window now shows the reversal");
                assertTrue(reversed.decompiled());
                assertFalse(reversed.obfuscation().skidfuscator(), reversed.obfuscation().summary());
                assertFalse(onEdt(() -> window.banner().offersReversal()));
                assertTrue(onEdt(() -> window.banner().isVisible()), "the banner says the classes were reversed");
                assertTrue(onEdt(() -> window.tabs().getComponentAt(0) instanceof OverviewPanel));
                // The reversal folder sits beside the result in the workspace directory.
                try (var folders = Files.list(settings.workspaceDirectory())) {
                    assertTrue(folders.anyMatch(folder -> folder.getFileName().toString().startsWith("skid-app-skidfuscator-reversal")));
                }
            } finally {
                SwingUtilities.invokeAndWait(window::dispose);
            }
        } finally {
            System.clearProperty("classrevive.home");
        }
    }

    private static <T> T onEdt(Callable<T> call) throws Exception {
        var result = new CompletableFuture<T>();
        SwingUtilities.invokeAndWait(() -> { try { result.complete(call.call()); } catch (Exception e) { result.completeExceptionally(e); } });
        return result.get();
    }

    private interface Check { boolean ok() throws Exception; }

    private static void waitFor(Check check) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(300);
        while (System.nanoTime() < deadline) { if (check.ok()) return; Thread.sleep(100); }
        fail("condition not reached in time");
    }
}
