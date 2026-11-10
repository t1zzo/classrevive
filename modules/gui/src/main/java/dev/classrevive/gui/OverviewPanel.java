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
        content.setBorder(Ui.padding(24, 32, 28, 32));

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
        content.add(Box.createVerticalStrut(18));

        // Phase G7: what needs attention first, then what the deobfuscation did, then the confidence breakdown.
        var insights = workspace.insights();
        if (!insights.hasDeobfuscation()) {
            for (var notice : insights.notices()) { content.add(Ui.notice(notice)); content.add(Box.createVerticalStrut(6)); }
            if (!insights.notices().isEmpty()) content.add(Box.createVerticalStrut(12));
        } else {
            content.add(new DeobfuscationPanel(insights));
            content.add(Box.createVerticalStrut(22));
        }

        content.add(Ui.section(Text.t("overview.confidence")));
        var counts = index.levelCounts();
        if (counts.isEmpty()) content.add(left(Ui.muted(Text.t("overview.noLevels"))));
        else {
            var bar = levelBar(new EnumMap<>(counts));
            bar.setAlignmentX(LEFT_ALIGNMENT);
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

        content.add(Ui.section(Text.t("overview.engines")));
        var engines = new JPanel(new GridLayout(0, 3, 16, 6));
        engines.setOpaque(false);
        for (var engine : index.engines()) {
            engines.add(new JLabel("<html><b>" + Ui.escape(engine.id()) + "</b> " + Ui.escape(engine.version()) + "</html>"));
            boolean ok = engine.status().equals("OUTPUT_PRODUCED");
            var status = new JLabel(Text.t("engine." + engine.status(), engine.status()));
            status.setIcon(ok ? Icons.dot(Theme.level(ResultIndex.Level.EXACT), 10) : Ui.severityIcon(ResultInsights.Severity.WARNING, 12));
            status.setIconTextGap(6);
            engines.add(status);
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
        // Text areas lay out late and can pull the view down to a section; the overview always opens at its top.
        SwingUtilities.invokeLater(() -> scroll.getViewport().setViewPosition(new Point(0, 0)));
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

    /** A card with a label, a big value and a note, and an optional accent stripe. */
    static final class Tile extends Ui.Card {
        Tile(String label, String value, String note, Color accent) {
            super(accent);
            setLayout(new BorderLayout(0, 2));
            setBorder(Ui.padding(12, 16, 12, 14));
            add(Ui.caption(label), BorderLayout.NORTH);
            var big = new JLabel(Ui.visible(value));
            big.setFont(big.getFont().deriveFont(Font.BOLD, 20f));
            add(big, BorderLayout.CENTER);
            add(Ui.muted(note), BorderLayout.SOUTH);
        }
    }

    /** Classes per level as one proportional bar. */
    static JComponent levelBar(Map<ResultIndex.Level,Integer> counts) {
        var tip = new StringJoiner(", ");
        counts.forEach((level, count) -> tip.add(Text.t("level." + level.name()) + " " + count));
        var levels = ResultIndex.Level.values();
        var values = new int[levels.length];
        var colors = new Color[levels.length];
        for (int i = 0; i < levels.length; i++) { values[i] = counts.getOrDefault(levels[i], 0); colors[i] = Theme.level(levels[i]); }
        return new Ui.Bar(values, colors, tip.toString());
    }
}
