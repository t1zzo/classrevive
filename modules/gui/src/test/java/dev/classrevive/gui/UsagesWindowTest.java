package dev.classrevive.gui;

import dev.classrevive.bytecode.ReferenceIndex;
import dev.classrevive.bytecode.ReferenceIndex.Ref;
import java.awt.GraphicsEnvironment;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import javax.swing.SwingUtilities;
import javax.swing.tree.DefaultMutableTreeNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/** Phase G6: find usages and the call hierarchy in the real window, before and after a decompile. Skipped without a display. */
class UsagesWindowTest {
    @TempDir Path directory;

    @Test void findsUsagesInBytecodeAndSourceAndShowsCallers() throws Exception {
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
            var offered = new ArrayList<List<Ref>>();
            window.chooser = (parent, message, options, names) -> {
                offered.add(options);
                return options.stream().filter(ref -> ref.owner().equals("demo/shapes/Shape") || ref.name().equals("<init>")).findFirst();
            };
            try {
                SwingUtilities.invokeAndWait(() -> window.openPath(jar));
                waitFor(() -> onEdt(() -> window.workspace() != null));

                // Before any decompile: the bytecode view, where the instruction names its target exactly.
                SwingUtilities.invokeAndWait(() -> window.openClass(window.workspace().classItem("demo/Main").orElseThrow()));
                var main = onEdt(() -> (CodeTab) window.tabs().getSelectedComponent());
                waitFor(() -> onEdt(() -> main.content().contains("INVOKEINTERFACE demo/shapes/Shape.area")));
                SwingUtilities.invokeAndWait(() -> main.caret(main.content().indexOf("INVOKEINTERFACE demo/shapes/Shape.area")));
                SwingUtilities.invokeAndWait(() -> window.findUsages.actionPerformed(null));
                waitFor(() -> onEdt(() -> window.usages().titleText().equals("1 usages of Shape.area()")));
                assertTrue(offered.isEmpty(), "an instruction is never ambiguous");
                var site = onEdt(() -> firstSite(window));
                assertEquals("demo/Main", site.className());
                assertEquals("describe", site.from().name());
                SwingUtilities.invokeAndWait(() -> window.openSite(site));
                waitFor(() -> onEdt(() -> main.caretLine().contains("INVOKEINTERFACE demo/shapes/Shape.area")));

                // After a decompile: the selected source, where the receiver's type is unknown, so the user chooses.
                SwingUtilities.invokeAndWait(() -> window.decompile.actionPerformed(null));
                waitFor(() -> onEdt(() -> !window.running()));
                assertTrue(window.workspace().decompiled());
                SwingUtilities.invokeAndWait(() -> window.openClass(window.workspace().classItem("demo/Main").orElseThrow()));
                waitFor(() -> onEdt(() -> main.content().contains("public class Main")));
                SwingUtilities.invokeAndWait(() -> main.caret(main.content().indexOf("area()") + 1));
                SwingUtilities.invokeAndWait(() -> window.findUsages.actionPerformed(null));
                waitFor(() -> onEdt(() -> !offered.isEmpty() && window.usages().titleText().equals("1 usages of Shape.area()")));
                assertEquals(Set.of(new Ref("demo/shapes/Shape", "area", "()D"), new Ref("demo/shapes/Circle", "area", "()D")), Set.copyOf(offered.getFirst()));
                var inSource = onEdt(() -> firstSite(window));
                SwingUtilities.invokeAndWait(() -> window.openSite(inSource));
                waitFor(() -> onEdt(() -> main.caretLine().contains("area()")));

                // Call hierarchy of Circle's constructor, from its declaration: Builder.build calls it.
                SwingUtilities.invokeAndWait(() -> window.openClass(window.workspace().classItem("demo/shapes/Circle").orElseThrow()));
                var circle = onEdt(() -> (CodeTab) window.tabs().getSelectedComponent());
                waitFor(() -> onEdt(() -> circle.content().contains("public Circle(double")));
                SwingUtilities.invokeAndWait(() -> circle.caret(circle.content().indexOf("public Circle(double") + 8));
                SwingUtilities.invokeAndWait(() -> window.callHierarchy.actionPerformed(null));
                waitFor(() -> onEdt(() -> window.usages().titleText().equals("Callers of Circle.Circle(double)")));
                var callers = onEdt(() -> {
                    var root = (DefaultMutableTreeNode) window.usages().tree().getModel().getRoot();
                    var top = (DefaultMutableTreeNode) root.getChildAt(0);
                    var names = new ArrayList<String>();
                    for (int i = 0; i < top.getChildCount(); i++)
                        names.add(((UsagesPanel.CallerNode) ((DefaultMutableTreeNode) top.getChildAt(i)).getUserObject()).node().method().toString());
                    return names;
                });
                assertEquals(List.of("demo/shapes/Circle$Builder.build()Ldemo/shapes/Circle;"), callers);
            } finally {
                SwingUtilities.invokeAndWait(window::dispose);
            }
        } finally {
            System.clearProperty("classrevive.home");
        }
    }

    private static ReferenceIndex.Site firstSite(MainWindow window) {
        var root = (DefaultMutableTreeNode) window.usages().tree().getModel().getRoot();
        for (var nodes = root.depthFirstEnumeration(); nodes.hasMoreElements(); )
            if (((DefaultMutableTreeNode) nodes.nextElement()).getUserObject() instanceof UsagesPanel.SiteNode site) return site.usage().site();
        throw new AssertionError("no site");
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
