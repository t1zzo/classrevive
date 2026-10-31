package dev.classrevive.gui;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.concurrent.*;
import javax.imageio.ImageIO;
import javax.swing.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Design review aid, off by default: with {@code -Dclassrevive.screenshots=<folder>} (and optionally
 * {@code -Dclassrevive.screenshots.result=<a result folder>}), walks the main screens in both themes and writes PNGs there.
 * {@code -Dclassrevive.screenshots.language=en} (default) or {@code pt} picks the interface language. Gradle passes
 * {@code -Pclassrevive.screenshots...} properties through (see the module's build script).
 */
class ScreenshotTour {
    @TempDir Path directory;

    @Test void tour() throws Exception {
        String target = System.getProperty("classrevive.screenshots");
        assumeTrue(target != null && !GraphicsEnvironment.isHeadless(), "screenshots not requested");
        Path out = Files.createDirectories(Path.of(target));
        System.setProperty("classrevive.home", directory.resolve("home").toString());
        var settings = GuiSettings.load();
        settings.profile(GuiSettings.Profile.CUSTOM);
        settings.customEngines("cfr,vineflower");
        settings.engineCache(false);
        var language = System.getProperty("classrevive.screenshots.language", "en").equals("pt")
            ? GuiSettings.Language.PORTUGUESE : GuiSettings.Language.ENGLISH;
        settings.language(language);
        Text.use(language);
        Path jar = Fixtures.jar(directory);
        String result = System.getProperty("classrevive.screenshots.result");
        if (result != null) settings.remember(Path.of(result));
        settings.remember(jar);
        for (var theme : new GuiSettings.ThemeChoice[] {GuiSettings.ThemeChoice.LIGHT, GuiSettings.ThemeChoice.DARK}) {
            String suffix = theme.name().toLowerCase();
            var window = onEdt(() -> { Theme.install(theme); var w = new MainWindow(settings); w.setSize(1440, 900); w.setVisible(true); return w; });
            try {
                settle();
                shot(window, out.resolve("01-welcome-" + suffix + ".png"));
                SwingUtilities.invokeAndWait(() -> window.openPath(jar));
                waitFor(() -> onEdt(() -> window.workspace() != null));
                SwingUtilities.invokeAndWait(() -> window.openClass(window.workspace().classItem("demo/shapes/Circle").orElseThrow()));
                settle();
                shot(window, out.resolve("02-opened-" + suffix + ".png"));
                SwingUtilities.invokeAndWait(() -> window.decompile.actionPerformed(null));
                Thread.sleep(700);
                shot(window, out.resolve("03-decompiling-" + suffix + ".png"));
                waitFor(() -> onEdt(() -> !window.running()));
                settle();
                shot(window, out.resolve("04-overview-" + suffix + ".png"));
                SwingUtilities.invokeAndWait(() -> window.openClass(window.workspace().classItem("demo/shapes/Circle").orElseThrow()));
                settle();
                shot(window, out.resolve("05-class-" + suffix + ".png"));
                // A result folder opens in a window of its own, so its activity panel does not show the demo's decompile.
                if (result != null) resultTour(settings, Path.of(result), out, suffix);
                // Optional: a result that does not compile, opened at its first javac error.
                String errors = System.getProperty("classrevive.screenshots.errors");
                if (errors != null) {
                    SwingUtilities.invokeAndWait(() -> window.openPath(Path.of(errors)));
                    waitFor(() -> onEdt(() -> window.workspace() != null && window.workspace().input().startsWith(Path.of(errors))));
                    var diagnostic = window.workspace().result().diagnostics().getFirst();
                    String className = window.workspace().originalOf(diagnostic.file().replaceAll("\\.java$", ""));
                    SwingUtilities.invokeAndWait(() -> window.openClass(window.workspace().classItem(className).orElseThrow(), (int) diagnostic.line()));
                    settle();
                    Thread.sleep(2500);
                    shot(window, out.resolve("12-errors-" + suffix + ".png"));
                }
                // Optional: a Skidfuscator jar, its banner, then the overview of its reversal.
                String skid = System.getProperty("classrevive.screenshots.skid");
                if (skid != null) {
                    SwingUtilities.invokeAndWait(() -> window.openPath(Path.of(skid)));
                    waitFor(() -> onEdt(() -> window.workspace() != null && window.workspace().input().equals(Path.of(skid))));
                    settle();
                    shot(window, out.resolve("13-skidfuscator-" + suffix + ".png"));
                    SwingUtilities.invokeAndWait(() -> window.decompile(false, true));
                    waitFor(() -> onEdt(() -> !window.running() && window.workspace().reversedFrom() != null));
                    settle();
                    shot(window, out.resolve("14-skidfuscator-reversed-" + suffix + ".png"));
                }
                if (theme == GuiSettings.ThemeChoice.LIGHT) {
                    dialog(new GoToClassDialog(window, window.workspace(), x -> { }), out.resolve("09-goto.png"));
                    dialog(onEdt(() -> new SettingsDialog(window, settings)), out.resolve("10-settings.png"));
                    dialog(onEdt(() -> new DecompileDialog(window, settings, java.util.List.of())), out.resolve("11-decompile-options.png"));
                }
            } finally {
                SwingUtilities.invokeAndWait(window::dispose);
            }
        }
    }

    private static void resultTour(GuiSettings settings, Path result, Path out, String suffix) throws Exception {
        var window = onEdt(() -> { var w = new MainWindow(settings); w.setSize(1440, 900); w.setVisible(true); return w; });
        try {
            SwingUtilities.invokeAndWait(() -> window.openPath(result));
            waitFor(() -> onEdt(() -> window.workspace() != null && window.workspace().decompiled() && window.workspace().input().startsWith(result)));
            settle();
            shot(window, out.resolve("06-result-overview-" + suffix + ".png"));
            var item = onEdt(() -> window.workspace().classes().stream().filter(c -> c.simpleName().equals("JsonReader")).findFirst()
                .orElse(window.workspace().classes().get(window.workspace().classes().size() / 2)));
            SwingUtilities.invokeAndWait(() -> window.openClass(item));
            settle();
            // Scroll to the first method with an evidence icon, a few lines below the top.
            SwingUtilities.invokeAndWait(() -> {
                var tab = (CodeTab) window.tabs().getSelectedComponent();
                if (tab.firstEvidenceLine() > 0) tab.goToLine(tab.firstEvidenceLine() + 12);
            });
            settle();
            shot(window, out.resolve("07-result-class-" + suffix + ".png"));
            SwingUtilities.invokeAndWait(() -> window.compare.actionPerformed(null));
            settle();
            Thread.sleep(800);
            shot(window, out.resolve("08-compare-" + suffix + ".png"));
        } finally {
            SwingUtilities.invokeAndWait(window::dispose);
        }
    }

    private static void dialog(JDialog dialog, Path file) throws Exception {
        SwingUtilities.invokeAndWait(() -> { dialog.pack(); dialog.setLocation(-5000, -5000); dialog.setVisible(false); });
        var image = onEdt(() -> {
            var root = dialog.getRootPane();
            root.setSize(root.getPreferredSize());
            root.doLayout();
            layout(root);
            var picture = new BufferedImage(root.getWidth(), root.getHeight(), BufferedImage.TYPE_INT_ARGB);
            var g = picture.createGraphics();
            root.paint(g);
            g.dispose();
            return picture;
        });
        ImageIO.write(image, "png", file.toFile());
        SwingUtilities.invokeAndWait(dialog::dispose);
    }

    private static void layout(Container container) {
        container.doLayout();
        for (var child : container.getComponents()) if (child instanceof Container c) layout(c);
    }

    private static void shot(JFrame window, Path file) throws Exception {
        var image = onEdt(() -> {
            var root = window.getRootPane();
            window.validate();
            var picture = new BufferedImage(root.getWidth(), root.getHeight(), BufferedImage.TYPE_INT_ARGB);
            var g = picture.createGraphics();
            root.paint(g);
            g.dispose();
            return picture;
        });
        ImageIO.write(image, "png", file.toFile());
    }

    private static void settle() throws Exception {
        for (int i = 0; i < 8; i++) { Thread.sleep(150); SwingUtilities.invokeAndWait(() -> { }); }
    }

    private static <T> T onEdt(Callable<T> call) throws Exception {
        var result = new CompletableFuture<T>();
        SwingUtilities.invokeAndWait(() -> { try { result.complete(call.call()); } catch (Exception e) { result.completeExceptionally(e); } });
        return result.get();
    }

    private interface Check { boolean ok() throws Exception; }

    private static void waitFor(Check check) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(180);
        while (System.nanoTime() < deadline) { if (check.ok()) return; Thread.sleep(100); }
        throw new AssertionError("condition not reached in time");
    }
}
