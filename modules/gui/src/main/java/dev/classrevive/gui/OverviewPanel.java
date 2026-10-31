package dev.classrevive.gui;

import dev.classrevive.workbench.ResultIndex;
import java.awt.*;
import java.util.*;
import java.util.List;
import javax.swing.*;

/** Phase G3: the result of a decompile at a glance, with what to do next. Shown as the first editor tab. */
final class OverviewPanel extends JPanel {
    static final String KEY = "overview";

    OverviewPanel(Workspace workspace, List<Action> actions) {
        super(new BorderLayout());
        var index = workspace.result();
        var summary = index.summary();
        var content = new Ui.Column();
        content.setBorder(Ui.padding(24, 28, 24, 28));

        var title = Ui.heading(Ui.visible(workspace.displayName()), 22f);
        content.add(left(title));
        var subtitle = Ui.muted(Text.t("overview.subtitle", index.output()));
        subtitle.setToolTipText(index.output().toString());
        content.add(left(subtitle));
        content.add(Box.createVerticalStrut(18));

        var grid = new GridLayout(1, 4, 12, 12);
        var tiles = new JPanel(grid);
        tiles.setOpaque(false);
        boolean compiled = summary.compilation().equals("COMPILED");
        tiles.add(new Tile(Text.t("overview.compilation"), Text.t("compilation." + summary.compilation()),
            compiled ? Text.t("overview.compilation.ok") : Text.t("overview.compilation.errors", summary.errors()),
            compiled ? Theme.level(ResultIndex.Level.EXACT) : Theme.level(ResultIndex.Level.UNVERIFIED)));
        tiles.add(new Tile(Text.t("overview.signatures"), summary.matchedSymbols() + " / " + summary.expectedSymbols(),
            Text.t("overview.signatures.note"), null));
        int identical = 0, equivalent = 0, different = 0;
        for (var entry : index.classes().values()) { identical += entry.identical(); equivalent += entry.equivalent(); different += entry.different(); }
        int measured = identical + equivalent + different;
        tiles.add(new Tile(Text.t("overview.bodies"), measured == 0 ? "—" : (identical + equivalent) + " / " + measured,
            measured == 0 ? Text.t("overview.bodies.none") : Text.t("overview.bodies.note", percent(identical + equivalent, measured)), null));
        tiles.add(new Tile(Text.t("overview.selected"), summary.selectedCandidate(), Text.t("overview.selected.note"), null));
        // Phase G4: the language and compiler the bytecode points to (Phase 2CJ), when the report has them.
        var compiler = index.compiler();
        if (compiler != null && compiler.classes() > 0) tiles.add(new Tile(Text.t("overview.language"), compiler.language(), languageNote(compiler), null));
        // Phase G5: the obfuscator the classes point to, and whether the Skidfuscator reversal ran first.
        if (workspace.reversedFrom() != null)
            tiles.add(new Tile(Text.t("overview.obfuscation"), Text.t("obfuscation.reversed"), Text.t("obfuscation.reversedFrom", Ui.visible(workspace.reversedFrom())), Theme.ACCENT));
        else if (workspace.obfuscation() != null) {
            var detection = workspace.obfuscation();
            tiles.add(new Tile(Text.t("overview.obfuscation"), ObfuscationBanner.headline(detection), ObfuscationBanner.note(detection),
                detection.verdict().equals("NONE") ? null : Theme.ACCENT));
        }
        tiles.setAlignmentX(LEFT_ALIGNMENT);
        // Four tiles in a row on wide windows, two by two on narrow ones.
        tiles.addComponentListener(new java.awt.event.ComponentAdapter() {
            @Override public void componentResized(java.awt.event.ComponentEvent event) {
                int count = tiles.getComponentCount();
                int columns = tiles.getWidth() >= 1100 ? count : tiles.getWidth() >= 700 ? Math.min(count, 3) : 2;
                if (grid.getColumns() != columns) { grid.setColumns(columns); grid.setRows(0); tiles.revalidate(); }
            }
        });
        content.add(tiles);
        content.add(Box.createVerticalStrut(22));

        content.add(left(Ui.heading(Text.t("overview.confidence"), 14f)));
        content.add(Box.createVerticalStrut(8));
        var counts = index.levelCounts();
        if (counts.isEmpty()) content.add(left(Ui.muted(Text.t("overview.noLevels"))));
        else {
            var bar = new LevelBar(new EnumMap<>(counts));
            bar.setAlignmentX(LEFT_ALIGNMENT);
            bar.setMaximumSize(new Dimension(Integer.MAX_VALUE, 14));
            content.add(bar);
            content.add(Box.createVerticalStrut(10));
            var legend = new JPanel(new GridLayout(0, 1, 0, 4));
            legend.setOpaque(false);
            for (var level : ResultIndex.Level.values()) {
                var label = new JLabel("<html><b>" + Text.t("level." + level.name()) + "</b> &nbsp;" + counts.get(level)
                    + " &nbsp;<span style='color:gray'>" + Ui.escape(Text.t("level." + level.name() + ".meaning")) + "</span></html>");
                label.setIcon(Icons.dot(Theme.level(level), 12));
                legend.add(label);
            }
            legend.setAlignmentX(LEFT_ALIGNMENT);
            content.add(legend);
        }
        content.add(Box.createVerticalStrut(22));

        content.add(left(Ui.heading(Text.t("overview.engines"), 14f)));
        content.add(Box.createVerticalStrut(8));
        var engines = new JPanel(new GridLayout(0, 3, 16, 4));
        engines.setOpaque(false);
        for (var engine : index.engines()) {
            engines.add(new JLabel("<html><b>" + Ui.escape(engine.id()) + "</b> " + Ui.escape(engine.version()) + "</html>"));
            engines.add(new JLabel(Text.t("engine." + engine.status(), engine.status())));
            engines.add(Ui.muted(Text.t("overview.engines.files", engine.sourceFiles())));
        }
        engines.setAlignmentX(LEFT_ALIGNMENT);
        engines.setMaximumSize(new Dimension(640, engines.getPreferredSize().height));
        content.add(engines);
        content.add(Box.createVerticalStrut(22));

        var buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        buttons.setOpaque(false);
        boolean first = true;
        for (var action : actions) {
            var button = first ? Ui.primary(action) : new JButton(action);
            if (!first) buttons.add(Box.createHorizontalStrut(8));
            buttons.add(button);
            first = false;
        }
        buttons.setAlignmentX(LEFT_ALIGNMENT);
        content.add(buttons);
        content.add(Box.createVerticalStrut(18));
        content.add(Ui.text(Text.t("overview.behavior"), true));

        var scroll = new JScrollPane(content);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        add(scroll, BorderLayout.CENTER);
    }

    private static JComponent left(JComponent component) { component.setAlignmentX(LEFT_ALIGNMENT); return component; }

    /** "javac 9 or later", "Eclipse compiler (ecj)", or how many classes another language produced. */
    static String languageNote(ResultIndex.Compiler compiler) {
        if (!compiler.language().equals("Java")) return Text.t("overview.language.other", compiler.classes());
        return switch (compiler.javaCompiler()) {
            case "javac" -> compiler.javacAtLeast() > 0 ? Text.t("overview.language.javacAtLeast", compiler.javacAtLeast()) : Text.t("overview.language.javac");
            case "ecj" -> Text.t("overview.language.ecj");
            case "mixed" -> Text.t("overview.language.mixed");
            default -> Text.t("overview.language.unknown");
        };
    }

    static String percent(long part, long whole) { return String.format(Text.locale(), "%.1f%%", part * 100.0 / whole); }

    /** A rounded card with a label, a big value, a note and an optional accent stripe. */
    static final class Tile extends JPanel {
        private final Color accent;
        Tile(String label, String value, String note, Color accent) {
            super(new BorderLayout(0, 2));
            this.accent = accent;
            setOpaque(false);
            setBorder(Ui.padding(12, 14, 12, 14));
            var top = Ui.muted(label.toUpperCase(Text.locale()));
            top.setFont(top.getFont().deriveFont(Font.BOLD, top.getFont().getSize2D() - 1.5f));
            add(top, BorderLayout.NORTH);
            var big = new JLabel(Ui.visible(value));
            big.setFont(big.getFont().deriveFont(Font.BOLD, 20f));
            add(big, BorderLayout.CENTER);
            var small = Ui.muted(note);
            add(small, BorderLayout.SOUTH);
        }
        @Override protected void paintComponent(Graphics g) {
            var g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(Theme.card());
            g2.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 14, 14);
            g2.setColor(Theme.line());
            g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 14, 14);
            if (accent != null) { g2.setColor(accent); g2.fillRoundRect(0, 10, 4, getHeight() - 20, 4, 4); }
            g2.dispose();
            super.paintComponent(g);
        }
    }

    /** Classes per level as one proportional bar. */
    static final class LevelBar extends JComponent {
        private final Map<ResultIndex.Level,Integer> counts;
        LevelBar(Map<ResultIndex.Level,Integer> counts) {
            this.counts = counts;
            setPreferredSize(new Dimension(240, 12));
            setMinimumSize(new Dimension(60, 12));
            var tip = new StringJoiner(", ");
            counts.forEach((level, count) -> tip.add(Text.t("level." + level.name()) + " " + count));
            setToolTipText(tip.toString());
        }
        @Override protected void paintComponent(Graphics g) {
            var g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int total = counts.values().stream().mapToInt(Integer::intValue).sum();
            var clip = new java.awt.geom.RoundRectangle2D.Double(0, 0, getWidth(), getHeight(), getHeight(), getHeight());
            g2.setColor(Theme.line());
            g2.fill(clip);
            if (total > 0) {
                g2.clip(clip);
                double x = 0;
                for (var level : ResultIndex.Level.values()) {
                    int count = counts.getOrDefault(level, 0);
                    if (count == 0) continue;
                    double width = getWidth() * (double) count / total;
                    g2.setColor(Theme.level(level));
                    g2.fill(new java.awt.geom.Rectangle2D.Double(x, 0, Math.max(2, width), getHeight()));
                    x += width;
                }
            }
            g2.dispose();
        }
    }
}
