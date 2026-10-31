package dev.classrevive.gui;

import dev.classrevive.workbench.ResultIndex;
import java.awt.GraphicsEnvironment;
import java.nio.file.Path;
import java.util.concurrent.*;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/** The real window, driven through its actions: open, open a class, decompile, overview. Skipped without a display. */
class MainWindowSmokeTest {
    @TempDir Path directory;

    @Test void opensDecompilesAndShowsTheResult() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "needs a display");
        System.setProperty("classrevive.home", directory.resolve("home").toString());
        try {
            var settings = GuiSettings.load();
            settings.profile(GuiSettings.Profile.CUSTOM);
            settings.customEngines("cfr");
            settings.engineCache(false);
            Text.use(GuiSettings.Language.ENGLISH);
            Path jar = Fixtures.jar(directory);
            var window = onEdt(() -> { Theme.install(GuiSettings.ThemeChoice.LIGHT); return new MainWindow(settings); });
            try {
                SwingUtilities.invokeAndWait(() -> window.openPath(jar));
                waitFor(() -> onEdt(() -> window.workspace() != null));
                assertFalse(onEdt(() -> window.decompile.isEnabled() && window.searchCode.isEnabled()), "search needs a result");
                SwingUtilities.invokeAndWait(() -> window.openClass(window.workspace().classItem("demo/Main").orElseThrow()));
                assertEquals(1, (int) onEdt(() -> window.tabs().getTabCount()));

                SwingUtilities.invokeAndWait(() -> window.decompile.actionPerformed(null));
                assertTrue(onEdt(window::running));
                waitFor(() -> onEdt(() -> !window.running()));
                assertTrue(onEdt(() -> window.workspace().decompiled()), "decompile finished with a result");
                assertTrue(onEdt(() -> window.tabs().getComponentAt(0) instanceof OverviewPanel), "overview first");
                assertEquals(ResultIndex.Level.EXACT, window.workspace().evidence("demo/Main").orElseThrow().level());
                assertTrue(onEdt(() -> window.searchCode.isEnabled() && window.exportProject.isEnabled()));
                // The class tab switched from bytecode to the selected source.
                var tab = onEdt(() -> (CodeTab) window.tabs().getComponentAt(1));
                waitFor(() -> onEdt(() -> tab.content().contains("public class Main")));
                assertTrue(settings.recent().stream().anyMatch(path -> path.equals(window.workspace().result().output())));

                // Phase G4: evidence icons on the measured methods of the selected source.
                waitFor(() -> onEdt(() -> tab.evidenceIcons() >= 2));
                // Ctrl+click on "Shape" (a class of the input) opens it; back and forward return.
                SwingUtilities.invokeAndWait(() -> window.tabs().setSelectedComponent(tab));
                int offset = onEdt(() -> tab.content().indexOf("Shape shape") + 1);
                SwingUtilities.invokeAndWait(() -> tab.linkAt(offset).execute());
                assertEquals("demo/shapes/Shape", onEdt(() -> ((CodeTab) window.tabs().getSelectedComponent()).classItem().name()));
                assertNull(onEdt(() -> tab.linkAt(tab.content().indexOf("String") + 1)), "JDK classes are not links");
                SwingUtilities.invokeAndWait(() -> window.back.actionPerformed(null));
                assertEquals("demo/Main", onEdt(() -> ((CodeTab) window.tabs().getSelectedComponent()).classItem().name()));
                assertTrue(onEdt(() -> window.forward.isEnabled()));
                SwingUtilities.invokeAndWait(() -> window.forward.actionPerformed(null));
                assertEquals("demo/shapes/Shape", onEdt(() -> ((CodeTab) window.tabs().getSelectedComponent()).classItem().name()));
                // Opening a class at a line (search results, javac errors) puts the caret on that line.
                SwingUtilities.invokeAndWait(() -> window.openClass(window.workspace().classItem("demo/shapes/Circle").orElseThrow(), 4));
                var circle = onEdt(() -> (CodeTab) window.tabs().getSelectedComponent());
                waitFor(() -> onEdt(() -> circle.content().contains("class Circle") && circle.caretText().startsWith("Ln 4,")));
                SwingUtilities.invokeAndWait(() -> window.openClass(window.workspace().classItem("demo/shapes/Shape").orElseThrow()));
                // Compare engines opens a side-by-side tab for the current class.
                SwingUtilities.invokeAndWait(() -> window.compare.actionPerformed(null));
                assertTrue(onEdt(() -> window.tabs().getSelectedComponent() instanceof CompareTab));
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
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120);
        while (System.nanoTime() < deadline) { if (check.ok()) return; Thread.sleep(100); }
        fail("condition not reached in time");
    }
}
