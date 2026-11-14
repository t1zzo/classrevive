package dev.classrevive.gui;

import com.formdev.flatlaf.FlatClientProperties;
import dev.classrevive.workbench.ResultIndex;
import java.awt.*;
import java.awt.event.*;
import java.util.*;
import java.util.List;
import java.util.function.Consumer;
import javax.swing.*;

/**
 * Phase G3: the result of a decompile at a glance, with what to do next. Shown as the first editor tab. Phase G8: the
 * actions sit next to the title, a verdict says in plain words what the result means, and each confidence level in the
 * legend opens the explorer on the classes at that level.
 */
final class OverviewPanel extends JPanel {
    static final String KEY = "overview";

    /** Phase G8: the overall reading of a result: how sure, one headline, one line of detail, and how many classes to check. */
    record Verdict(ResultInsights.Severity severity, String headline, String detail, int attention) {}

    /** {@code showLevel} opens the explorer on one level's classes; null asks for the classes that need attention. */
    OverviewPanel(Workspace workspace, List<Action> actions, Consumer<ResultIndex.Level> showLevel) {
        super(new BorderLayout());
        var index = workspace.result();
        var summary = index.summary();
        var content = new Ui.Column() {
            @Override public void updateUI() { super.updateUI(); setBackground(Theme.editor()); }
        };
        content.setBorder(Ui.padding(26, 36, 32, 36));

        // The title, where the result is saved, and what to do with it.
        var title = Ui.styled(Ui.visible(workspace.displayName()), "h2");
        title.setToolTipText(Ui.visible(workspace.input().toString()));
        content.add(title);
        content.add(Box.createVerticalStrut(2));
        var subtitle = Ui.muted(Text.t("overview.subtitle", WelcomePanel.shorten(index.output().toString(), 110)));
        subtitle.setToolTipText(index.output().toString());
        content.add(subtitle);
        content.add(Box.createVerticalStrut(14));
        var buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        buttons.setOpaque(false);
        boolean first = true;
        for (var action : actions) {
            var button = first ? Ui.primary(action) : new JButton(action);
            button.setFocusable(first);
            if (!first) buttons.add(Box.createHorizontalStrut(8));
            buttons.add(button);
            first = false;
        }
        content.add(buttons);
        content.add(Box.createVerticalStrut(22));

        // The verdict in plain words.
        var verdict = verdict(index);
        content.add(verdictCard(verdict, showLevel));
        content.add(Box.createVerticalStrut(16));

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
        tiles.add(new Tile(Text.t("overview.selected"), selectedName(summary.selectedCandidate()), Text.t("overview.selected.note"), null));
        // Phase G4: the language and compiler the bytecode points to (Phase 2CJ), when the report has them.
        var compiler = index.compiler();
        if (compiler != null && compiler.classes() > 0) tiles.add(new Tile(Text.t("overview.language"), compiler.language(), languageNote(compiler), null));
        // Phase G5: the obfuscator the classes point to, and whether the Skidfuscator reversal ran first.
        if (workspace.reversedFrom() != null)
            tiles.add(new Tile(Text.t("overview.obfuscation"), Text.t("obfuscation.reversed"), Text.t("obfuscation.reversedFrom", Ui.visible(workspace.reversedFrom())), Theme.ACCENT));
        else if (workspace.obfuscation() != null) {
            var detection = workspace.obfuscation();
            tiles.add(new Tile(Text.t("overview.obfuscation"), ObfuscationBanner.shortVerdict(detection), ObfuscationBanner.shortNote(detection),
                detection.verdict().equals("NONE") ? null : Theme.ACCENT));
        }
        // As many tiles per row as fit at about 230 pixels each (at least two), in rows of equal length.
        tiles.addComponentListener(new ComponentAdapter() {
            @Override public void componentResized(ComponentEvent event) {
                int count = tiles.getComponentCount();
                int fit = Math.max(2, Math.min(count, tiles.getWidth() / 230));
                // Balanced rows: six tiles that fit four abreast become two rows of three, not four and two.
                int rows = (count + fit - 1) / fit, columns = (count + rows - 1) / rows;
                if (grid.getColumns() != columns) { grid.setColumns(columns); grid.setRows(0); tiles.revalidate(); }
            }
        });
        content.add(tiles);
        content.add(Box.createVerticalStrut(24));

        // Phase G7: what needs attention first, then what the deobfuscation did, then the confidence breakdown.
        var insights = workspace.insights();
        if (!insights.hasDeobfuscation()) {
            for (var notice : insights.notices()) { content.add(limit(Ui.notice(notice))); content.add(Box.createVerticalStrut(8)); }
            if (!insights.notices().isEmpty()) content.add(Box.createVerticalStrut(16));
        } else {
            content.add(new DeobfuscationPanel(insights));
            content.add(Box.createVerticalStrut(26));
        }

        content.add(Ui.section(Text.t("overview.confidence")));
        var counts = index.levelCounts();
        if (counts.isEmpty()) content.add(limit(Ui.text(Text.t("overview.noLevels"), true)));
        else {
            var card = new Ui.Card(null);
            card.setLayout(new BorderLayout(0, 12));
            card.setBorder(Ui.padding(16, 16, 10, 16));
            card.add(levelBar(new EnumMap<>(counts)), BorderLayout.NORTH);
            var legend = new JPanel(new GridLayout(0, 1, 0, 0));
            legend.setOpaque(false);
            int total = Math.max(1, index.classes().size());
            for (var level : ResultIndex.Level.values()) legend.add(new LegendRow(level, counts.getOrDefault(level, 0), total, showLevel));
            card.add(legend, BorderLayout.CENTER);
            content.add(limit(card));
            content.add(Box.createVerticalStrut(4));
            content.add(Ui.faint(Text.t("overview.legendHint")));
        }
        content.add(Box.createVerticalStrut(26));

        content.add(Ui.section(Text.t("overview.engines")));
        content.add(limit(engines(index)));
        content.add(Box.createVerticalStrut(26));

        var behavior = new JPanel(new BorderLayout(10, 0));
        behavior.setOpaque(false);
        var info = new JLabel(Icons.of(Icons.Glyph.INFO, 15, Theme.secondary()));
        info.setVerticalAlignment(SwingConstants.TOP);
        behavior.add(info, BorderLayout.WEST);
        behavior.add(Ui.text(Text.t("overview.behavior"), true), BorderLayout.CENTER);
        content.add(limit(behavior));

        var scroll = new JScrollPane(content);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        add(scroll, BorderLayout.CENTER);
        // Text areas lay out late and can pull the view down to a section; the overview always opens at its top.
        SwingUtilities.invokeLater(() -> scroll.getViewport().setViewPosition(new Point(0, 0)));
    }

    /** Keeps reading width comfortable on wide windows. */
    private static JComponent limit(JComponent component) {
        var holder = new JPanel(new BorderLayout()) {
            @Override public Dimension getMaximumSize() { return new Dimension(Integer.MAX_VALUE, getPreferredSize().height); }
            @Override public Dimension getPreferredSize() {
                var size = component.getPreferredSize();
                return new Dimension(Math.min(size.width, 980), size.height);
            }
        };
        holder.setOpaque(false);
        holder.add(component, BorderLayout.CENTER);
        return holder;
    }

    /** "hybrid-6" reads as "Mix (6)", "recompose-1" as "Methods combined (1)"; an engine id reads as its name. */
    static String selectedName(String candidate) {
        if (candidate == null || candidate.isBlank()) return "—";
        var numbered = java.util.regex.Pattern.compile("(hybrid|recompose)-(\\d+)").matcher(candidate);
        if (numbered.matches()) return Text.t("overview.selected." + numbered.group(1), Integer.parseInt(numbered.group(2)));
        return CodeTab.engineName(candidate);
    }

    /** Phase G8: the plain-language reading of a result; never a "recovery %", and behavior is never claimed. */
    static Verdict verdict(ResultIndex index) {
        var summary = index.summary();
        var counts = index.levelCounts();
        int total = index.classes().size();
        int exact = counts.getOrDefault(ResultIndex.Level.EXACT, 0), close = counts.getOrDefault(ResultIndex.Level.CLOSE, 0);
        int attention = 0;
        for (var level : ExplorerPanel.ATTENTION) attention += counts.getOrDefault(level, 0);
        return switch (summary.compilation()) {
            case "COMPILED" -> {
                if (counts.isEmpty()) yield new Verdict(ResultInsights.Severity.INFO, Text.t("verdict.compiled"), Text.t("verdict.noLevels"), 0);
                if (attention == 0 && close == 0) yield new Verdict(ResultInsights.Severity.INFO, Text.t("verdict.allExact", total), Text.t("verdict.allExact.detail"), 0);
                if (attention == 0) yield new Verdict(ResultInsights.Severity.INFO, Text.t("verdict.compiled"), Text.t("verdict.close", exact, total, close), 0);
                yield new Verdict(ResultInsights.Severity.WARNING, Text.t("verdict.compiledAttention", attention, total), Text.t("verdict.attention.detail", exact), attention);
            }
            case "COMPILE_FAILED" -> new Verdict(ResultInsights.Severity.ERROR, Text.t("verdict.failed", summary.errors()),
                counts.isEmpty() ? Text.t("verdict.failed.detail") : Text.t("verdict.failed.levels", total - attention, total), attention);
            default -> new Verdict(ResultInsights.Severity.WARNING, Text.t("verdict.notChecked", Text.t("compilation." + summary.compilation())),
                Text.t("verdict.notChecked.detail"), attention);
        };
    }

    private static JComponent verdictCard(Verdict verdict, Consumer<ResultIndex.Level> showLevel) {
        var color = verdict.severity() == ResultInsights.Severity.INFO ? Theme.level(ResultIndex.Level.EXACT) : Ui.severityColor(verdict.severity());
        var card = new Ui.Card(color);
        card.setLayout(new BorderLayout(14, 0));
        card.setBorder(Ui.padding(16, 20, 16, 18));
        var icon = new JLabel(new VerdictIcon(color, verdict.severity()));
        icon.setVerticalAlignment(SwingConstants.TOP);
        card.add(icon, BorderLayout.WEST);
        var text = new Ui.Column();
        text.setOpaque(false);
        var headline = Ui.text(verdict.headline(), false);
        headline.putClientProperty(FlatClientProperties.STYLE, "font: +3 $semibold.font");
        text.add(headline);
        text.add(Box.createVerticalStrut(4));
        text.add(Ui.text(verdict.detail(), true));
        if (verdict.attention() > 0) {
            text.add(Box.createVerticalStrut(6));
            var link = Ui.link(Text.t("verdict.showAttention", verdict.attention()), () -> showLevel.accept(null));
            link.setIcon(Icons.of(Icons.Glyph.FILTER, 13, Theme.accentText()));
            link.setHorizontalAlignment(SwingConstants.LEFT);
            var row = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
            row.setOpaque(false);
            row.add(link);
            text.add(row);
        }
        card.add(text, BorderLayout.CENTER);
        return limit(card);
    }

    /** A check for a good result, "!" otherwise, in a soft circle of the verdict's color. */
    private record VerdictIcon(Color color, ResultInsights.Severity severity) implements Icon {
        @Override public int getIconWidth() { return 32; }
        @Override public int getIconHeight() { return 32; }
        @Override public void paintIcon(Component c, Graphics g, int x, int y) {
            var g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(Theme.alpha(color, Theme.dark() ? 48 : 40));
            g2.fillOval(x, y, 32, 32);
            if (severity == ResultInsights.Severity.INFO) Icons.of(Icons.Glyph.CHECK, 18, Ui.readable(color)).paintIcon(c, g2, x + 7, y + 7);
            else {
                g2.setColor(Ui.readable(color));
                g2.fillRoundRect(x + 14, y + 7, 4, 12, 4, 4);
                g2.fillOval(x + 14, y + 21, 4, 4);
            }
            g2.dispose();
        }
    }

    /** One line per engine: name and version, its status, and how many source files it wrote. */
    private static JComponent engines(ResultIndex index) {
        var card = new Ui.Card(null);
        card.setLayout(new GridBagLayout());
        card.setBorder(Ui.padding(10, 16, 10, 16));
        var at = new GridBagConstraints();
        at.insets = new Insets(5, 0, 5, 18);
        at.anchor = GridBagConstraints.WEST;
        int row = 0;
        for (var engine : index.engines()) {
            at.gridy = row++;
            at.gridx = 0; at.weightx = 0;
            var name = Ui.styled(CodeTab.engineName(engine.id()), "semibold");
            name.setIcon(Icons.of(Icons.Glyph.CODE, 14, Theme.secondary()));
            name.setIconTextGap(8);
            card.add(name, at);
            at.gridx = 1;
            card.add(Ui.faint(Ui.visible(engine.version())), at);
            at.gridx = 2;
            boolean ok = engine.status().equals("OUTPUT_PRODUCED");
            card.add(Ui.softChip(Text.t("engine." + engine.status(), engine.status()), ok ? Theme.level(ResultIndex.Level.EXACT) : Theme.warning()), at);
            at.gridx = 3; at.weightx = 1; at.anchor = GridBagConstraints.EAST; at.insets = new Insets(5, 0, 5, 0);
            card.add(Ui.muted(Text.t("overview.engines.files", engine.sourceFiles())), at);
            at.anchor = GridBagConstraints.WEST; at.insets = new Insets(5, 0, 5, 18);
        }
        if (row == 0) card.add(Ui.muted(Text.t("overview.engines.none")), at);
        return card;
    }

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

    /** A card with a label, a big value and a note (wrapped, never cut), and an optional accent stripe. */
    static final class Tile extends Ui.Card {
        Tile(String label, String value, String note, Color accent) {
            super(accent);
            setLayout(new BorderLayout());
            setBorder(Ui.padding(14, 18, 14, 16));
            var stack = new Ui.Column();
            stack.setOpaque(false);
            stack.add(Ui.caption(label));
            stack.add(Box.createVerticalStrut(6));
            var big = new JLabel(Ui.visible(value));
            big.putClientProperty(FlatClientProperties.STYLE, "font: +5 $semibold.font");
            big.setToolTipText(Ui.visible(value));
            stack.add(big);
            stack.add(Box.createVerticalStrut(4));
            stack.add(Ui.text(note, true));
            // Extra height in a row of tiles goes below the content, never between the value and its note.
            add(stack, BorderLayout.NORTH);
        }
        @Override public Dimension getMinimumSize() { return new Dimension(120, super.getMinimumSize().height); }
    }

    /** One level of the legend: dot, name, count and share, and what it means; a click shows those classes in the explorer. */
    private static final class LegendRow extends JPanel {
        private boolean hover;
        LegendRow(ResultIndex.Level level, int count, int total, Consumer<ResultIndex.Level> showLevel) {
            super(new GridBagLayout());
            setOpaque(false);
            setBorder(Ui.padding(6, 8, 6, 8));
            var at = new GridBagConstraints();
            at.anchor = GridBagConstraints.WEST;
            at.insets = new Insets(0, 0, 0, 12);
            var name = Ui.styled(Text.t("level." + level.name()), "semibold");
            name.setIcon(Icons.dot(Theme.level(level), 12));
            name.setIconTextGap(8);
            name.setPreferredSize(new Dimension(150, name.getPreferredSize().height));
            add(name, at);
            at.gridx = 1;
            var number = Ui.styled(String.valueOf(count), "semibold");
            number.setHorizontalAlignment(SwingConstants.RIGHT);
            number.setPreferredSize(new Dimension(48, number.getPreferredSize().height));
            add(number, at);
            at.gridx = 2;
            var share = Ui.faint(count == 0 ? "" : percent(count, total));
            share.setPreferredSize(new Dimension(56, share.getPreferredSize().height));
            add(share, at);
            at.gridx = 3; at.weightx = 1; at.fill = GridBagConstraints.HORIZONTAL;
            add(Ui.muted(Text.t("level." + level.name() + ".meaning")), at);
            if (count > 0) {
                setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
                setToolTipText(Text.t("overview.showLevel", Text.t("level." + level.name())));
                var mouse = new MouseAdapter() {
                    @Override public void mouseEntered(MouseEvent e) { hover = true; repaint(); }
                    @Override public void mouseExited(MouseEvent e) {
                        hover = contains(SwingUtilities.convertPoint((Component) e.getSource(), e.getPoint(), LegendRow.this)); repaint();
                    }
                    @Override public void mouseClicked(MouseEvent e) { if (SwingUtilities.isLeftMouseButton(e)) showLevel.accept(level); }
                };
                addMouseListener(mouse);
                for (var child : getComponents()) child.addMouseListener(mouse);
            } else number.putClientProperty(FlatClientProperties.STYLE, "foreground: $ClassRevive.mutedForeground");
        }
        @Override protected void paintComponent(Graphics g) {
            if (!hover) return;
            var g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(Theme.hover());
            g2.fillRoundRect(0, 0, getWidth(), getHeight(), 8, 8);
            g2.dispose();
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
