package dev.classrevive.gui;

import java.awt.GraphicsEnvironment;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import javax.swing.SwingUtilities;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import org.fife.ui.rtextarea.RTextScrollPane;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Phase G9: measurements behind the desktop's performance claims, off by default. With
 * {@code -Pclassrevive.perf=<a jar>} (and optionally {@code -Pclassrevive.perf.out=<file>}) it times the window, opening
 * the jar, building and filtering its tree, opening a large text with and without code folding, and the code font lookup
 * before and after Phase G8. Medians of several runs, after a warm-up; printed and written as a Markdown table.
 */
class PerformanceProbe {
    @TempDir Path directory;

    @Test void measure() throws Exception {
        String jarProperty = System.getProperty("classrevive.perf");
        assumeTrue(jarProperty != null && !GraphicsEnvironment.isHeadless(), "measurements not requested");
        Path jar = Path.of(jarProperty);
        System.setProperty("classrevive.home", directory.resolve("home").toString());
        Text.use(GuiSettings.Language.ENGLISH);
        var rows = new ArrayList<String[]>();

        // The window: construction to a laid-out frame (the event-thread part of startup).
        SwingUtilities.invokeAndWait(() -> Theme.install(true));
        rows.add(row("Main window: build and lay out", median(5, () -> onEdt(() -> {
            var window = new MainWindow(GuiSettings.load(directory.resolve("s.properties")));
            window.setSize(1440, 900);
            window.validate();
            window.dispose();
            return null;
        }))));

        // Opening the input (inventory and obfuscation check; off the event thread in the application).
        var workspace = new Workspace[1];
        rows.add(row("Open " + jar.getFileName() + " (inventory + obfuscation check)", median(5, () -> {
            workspace[0] = Workspace.openInput(jar, directory.resolve("cache"));
            return null;
        })));
        rows.add(row("  of which: inventory only", median(5, () -> new dev.classrevive.intake.InventoryReader().inspect(jar, dev.classrevive.model.Limits.defaults()))));
        rows.add(row("  of which: obfuscation check only", median(5, () -> dev.classrevive.workbench.ObfuscationCheck.analyze(jar))));
        int classes = workspace[0].classes().size();
        rows.add(row("Explorer tree, " + classes + " classes, no filter", median(9, () -> ExplorerPanel.build(workspace[0], ""))));
        rows.add(row("Explorer tree, " + classes + " classes, filter \"a\" (one keystroke)", median(9, () -> ExplorerPanel.build(workspace[0], "a"))));
        var item = workspace[0].classes().get(classes / 2);
        rows.add(row("Bytecode text of one class (ASM, off the event thread)", median(9, () -> workspace[0].bytecode(item))));

        // A 50,000-line Java text in the editor, as G0 measured it: with folding (before G8 for every size) and without.
        var text = new StringBuilder();
        for (int i = 0; text.length() < 2_000_000 || i < 50_000; i++)
            text.append(i % 25 == 0 ? "    public int method" + i + "(int value) {\n" : i % 25 == 24 ? "    }\n" : "        value = value * 31 + " + i + ";\n");
        String large = "public class Large {\n" + text + "}\n";
        long lines = large.chars().filter(c -> c == '\n').count() + 1;
        for (boolean folding : new boolean[] {true, false}) {
            rows.add(row(String.format(Locale.ROOT, "Editor: set a %,d-character text, folding %s", large.length(), folding ? "on" : "off"),
                median(5, () -> onEdt(() -> {
                    var area = new RSyntaxTextArea();
                    area.setSyntaxEditingStyle(SyntaxConstants.SYNTAX_STYLE_JAVA);
                    area.setCodeFoldingEnabled(folding);
                    area.setMarkOccurrences(folding);
                    var scroll = new RTextScrollPane(area);
                    scroll.setSize(900, 700);
                    area.setText(large);
                    scroll.validate();
                    area.getFoldManager().reparse();
                    return null;
                }))));
        }
        rows.add(row("Large-file mode decided (line count stops past " + CodeTab.LARGE_LINES + ")", median(9, () -> CodeTab.lines(large))));

        // The code font: what every list repaint paid before G8, and what it pays now.
        rows.add(row("Code font, listing installed families (before G8, per call)", median(9, () ->
            java.util.Set.of(GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames()))));
        Theme.codeFont(13);
        rows.add(row("Code font, cached family (since G8, per call)", median(9, () -> Theme.codeFont(12))));

        var table = new StringBuilder("| Measurement | Median |\n|---|---|\n");
        for (var r : rows) table.append("| ").append(r[0]).append(" | ").append(r[1]).append(" |\n");
        table.append("\n").append(String.format(Locale.ROOT, "Large text: %,d lines. Java %s, %d processors.%n", lines,
            System.getProperty("java.version"), Runtime.getRuntime().availableProcessors()));
        System.out.println(table);
        String out = System.getProperty("classrevive.perf.out");
        if (out != null) Files.writeString(Path.of(out), table);
    }

    private interface Work { Object run() throws Exception; }

    /** Median wall time of {@code runs} runs after one warm-up run, as "12.3 ms" or "0.04 ms". */
    private static String median(int runs, Work work) throws Exception {
        work.run();
        var times = new ArrayList<Double>();
        for (int i = 0; i < runs; i++) {
            long start = System.nanoTime();
            work.run();
            times.add((System.nanoTime() - start) / 1e6);
        }
        Collections.sort(times);
        double median = times.get(times.size() / 2);
        return String.format(Locale.ROOT, median < 1 ? "%.3f ms" : "%.1f ms", median);
    }

    private static String[] row(String name, String value) { return new String[] {name, value}; }

    private static <T> T onEdt(Callable<T> call) throws Exception {
        var result = new CompletableFuture<T>();
        SwingUtilities.invokeAndWait(() -> { try { result.complete(call.call()); } catch (Exception e) { result.completeExceptionally(e); } });
        return result.get();
    }
}
