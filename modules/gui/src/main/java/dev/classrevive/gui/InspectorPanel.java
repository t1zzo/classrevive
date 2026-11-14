package dev.classrevive.gui;

import dev.classrevive.workbench.ResultIndex;
import java.awt.*;
import java.util.*;
import java.util.List;
import javax.swing.*;

/**
 * Phase G3: details of what the explorer has selected: a class's evidence, a package's levels, a resource, or the input.
 * Phase G8: a clearer header per subject, a level chip with its meaning, copyable hashes, and an honest "no levels" note
 * for results written before levels existed (it used to list every level with zero classes).
 */
final class InspectorPanel extends JPanel {
    private final Ui.Column body = new Ui.Column();
    private final Runnable decompile;

    InspectorPanel(Runnable decompile) {
        super(new BorderLayout());
        this.decompile = decompile;
        setBorder(Ui.line(0, 1, 0, 0));
        body.setBorder(Ui.padding(14, 16, 14, 16));
        var scroll = new JScrollPane(body, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        add(scroll, BorderLayout.CENTER);
        setPreferredSize(new Dimension(300, 400));
    }

    @Override public void updateUI() { super.updateUI(); setBackground(Theme.panel()); if (body != null) body.setBackground(Theme.panel()); }

    void show(Workspace workspace, ExplorerPanel.Node node) {
        body.removeAll();
        switch (node) {
            case ExplorerPanel.ClassNode cls -> showClass(workspace, cls.item());
            case ExplorerPanel.Package pkg -> showPackage(workspace, pkg.name());
            case ExplorerPanel.Resource res -> showResource(res.item());
            case null, default -> showInput(workspace);
        }
        body.add(Box.createVerticalGlue());
        body.revalidate();
        body.repaint();
    }

    private void showInput(Workspace workspace) {
        section(Text.t("inspector.input"));
        var name = Ui.styled(Ui.visible(workspace.displayName()), "h4");
        name.putClientProperty(com.formdev.flatlaf.FlatClientProperties.STYLE, "font: +3 $semibold.font");
        name.setToolTipText(Ui.visible(workspace.input().toString()));
        put(name);
        put(Box.createVerticalStrut(6));
        row(Text.t("inspector.classes"), String.valueOf(workspace.classes().size()));
        row(Text.t("inspector.resources"), String.valueOf(workspace.resources().size()));
        row(Text.t("inspector.size"), bytes(workspace.inventory().inputBytes()));
        if (workspace.identification() != null) row(Text.t("inspector.format"), workspace.identification().format());
        if (workspace.reversedFrom() != null) row(Text.t("inspector.obfuscator"), Text.t("obfuscation.reversed"));
        else if (workspace.obfuscation() != null) row(Text.t("inspector.obfuscator"), ObfuscationBanner.shortVerdict(workspace.obfuscation()));
        var compiler = workspace.result() == null ? null : workspace.result().compiler();
        if (compiler != null && compiler.classes() > 0) {
            row(Text.t("inspector.language"), compiler.language());
            row(Text.t("inspector.compiler"), OverviewPanel.languageNote(compiler));
        }
        selectable("SHA-256", workspace.inventory().sha256());
        if (!workspace.decompiled()) decompileHint();
        else levels(workspace.classes().stream().map(item -> workspace.evidence(item.name()).map(ResultIndex.ClassEntry::level).orElse(null)).toList());
    }

    private void showClass(Workspace workspace, Workspace.ClassItem item) {
        section(Text.t("inspector.class"));
        var name = Ui.styled(Ui.visible(item.simpleName()), "h4");
        name.putClientProperty(com.formdev.flatlaf.FlatClientProperties.STYLE, "font: +3 $semibold.font");
        char kind = ExplorerPanel.kind(item);
        name.setIcon(Icons.kind(kind, ExplorerPanel.kindColor(kind), null));
        name.setIconTextGap(8);
        put(name);
        put(Box.createVerticalStrut(2));
        put(wrap(Ui.visible(item.name().replace('/', '.')), true));
        var evidence = workspace.evidence(item.name());
        var result = workspace.result();
        var insights = workspace.insights();
        var reversal = insights == null ? null : insights.reversal();
        boolean reversed = reversal != null && reversal.applied();
        if (evidence.isPresent() && evidence.get().level() != null) {
            var entry = evidence.get();
            put(Box.createVerticalStrut(10));
            var chips = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
            chips.setOpaque(false);
            chips.add(Ui.softChip(Text.t("level." + entry.level().name()), Theme.level(entry.level())));
            if (reversed) { chips.add(Box.createHorizontalStrut(6)); chips.add(Ui.softChip(Text.t("inspector.reversed"), Theme.ACCENT)); }
            chips.setAlignmentX(LEFT_ALIGNMENT);
            chips.setMaximumSize(new Dimension(Integer.MAX_VALUE, chips.getPreferredSize().height));
            put(chips);
            put(Box.createVerticalStrut(6));
            put(wrap(Text.t("level." + entry.level().name() + ".meaning"), true));

            section(Text.t("inspector.roundTrip"));
            int measured = entry.identical() + entry.equivalent() + entry.different();
            if (measured == 0) put(wrap(Text.t("inspector.noBodies"), true));
            else {
                var bar = new Ui.Bar(new int[] {entry.identical(), entry.equivalent(), entry.different()},
                    new Color[] {Theme.level(ResultIndex.Level.EXACT), Theme.level(ResultIndex.Level.CLOSE), Theme.level(ResultIndex.Level.UNVERIFIED)},
                    Text.t("inspector.roundTrip.tip", OverviewPanel.percent(entry.identical() + entry.equivalent(), measured)));
                bar.setPreferredSize(new Dimension(240, 8));
                put(bar);
                put(Box.createVerticalStrut(6));
                outcomeRow(Text.t("inspector.identical"), entry.identical(), ResultIndex.Level.EXACT);
                outcomeRow(Text.t("inspector.equivalent"), entry.equivalent(), ResultIndex.Level.CLOSE);
                outcomeRow(Text.t("inspector.different"), entry.different(), ResultIndex.Level.UNVERIFIED);
            }
            if (!entry.methods().isEmpty()) methodList(item, entry);

            section(Text.t("inspector.origin.section"));
            row(Text.t("inspector.origin"), originText(entry.origin()));
            row(Text.t("inspector.signatures"), entry.matchedSymbols() + " / " + entry.expectedSymbols());
            // Phase 2BV renamed this class (case collision or invalid identifier) before the engines saw it.
            if (!entry.className().equals(item.name())) row(Text.t("inspector.portableName"), Ui.visible(entry.className().replace('/', '.')));
            if (reversed) {
                int edits = result.editedClasses().getOrDefault(entry.className(), 0);
                row(Text.t("inspector.readFrom"), Text.t("inspector.readFrom.reversed"));
                if (edits > 0) row(Text.t("inspector.edits"), String.valueOf(edits));
            }
        } else if (!workspace.decompiled()) {
            decompileHint();
        } else {
            put(wrap(Text.t("inspector.noEvidence"), true));
        }
        section(Text.t("inspector.bytecode"));
        var info = item.info();
        row(Text.t("inspector.kind"), Text.t("kind." + kind));
        row(Text.t("inspector.java"), "Java " + info.javaVersion() + " (" + info.majorVersion() + "." + info.minorVersion() + ")");
        if (info.superName() != null) row(Text.t("inspector.super"), Ui.visible(info.superName().replace('/', '.')));
        row(Text.t("inspector.interfaces"), String.valueOf(info.interfaces().size()));
        row(Text.t("inspector.fields"), String.valueOf(info.fields().size()));
        row(Text.t("inspector.methods"), String.valueOf(info.methods().size()));
    }

    private void outcomeRow(String name, int count, ResultIndex.Level color) {
        var row = new JPanel(new BorderLayout(Ui.GAP, 0));
        row.setOpaque(false);
        var label = new JLabel(name);
        label.setIcon(Icons.dot(Theme.level(color), 10));
        row.add(label, BorderLayout.WEST);
        row.add(new JLabel(String.valueOf(count), SwingConstants.RIGHT), BorderLayout.CENTER);
        row.setAlignmentX(LEFT_ALIGNMENT);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height + 4));
        row.setBorder(Ui.padding(2, 0, 2, 0));
        body.add(row);
    }

    /** Every measured method with its outcome, the ones that differ first. */
    private void methodList(Workspace.ClassItem item, ResultIndex.ClassEntry entry) {
        section(Text.t("inspector.methodsMeasured", entry.methods().size()));
        var rank = Map.of(ResultIndex.Outcome.DIFFERENT, 0, ResultIndex.Outcome.EQUIVALENT_AFTER_NORMALIZATION, 1, ResultIndex.Outcome.IDENTICAL, 2);
        var rows = entry.methods().entrySet().stream()
            .sorted(Comparator.<Map.Entry<String,ResultIndex.Outcome>>comparingInt(e -> rank.get(e.getValue())).thenComparing(Map.Entry::getKey))
            .limit(200).toList();
        var model = new DefaultListModel<Map.Entry<String,ResultIndex.Outcome>>();
        rows.forEach(model::addElement);
        var list = new JList<>(model);
        list.setCellRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> l, Object value, int index, boolean selected, boolean focus) {
                @SuppressWarnings("unchecked") var e = (Map.Entry<String,ResultIndex.Outcome>) value;
                super.getListCellRendererComponent(l, Descriptors.pretty(item.name(), e.getKey()), index, selected, false);
                setIcon(Icons.dot(Theme.level(switch (e.getValue()) {
                    case IDENTICAL -> ResultIndex.Level.EXACT;
                    case EQUIVALENT_AFTER_NORMALIZATION -> ResultIndex.Level.CLOSE;
                    case DIFFERENT -> ResultIndex.Level.UNVERIFIED;
                }), 10));
                setToolTipText(Text.t("outcome." + e.getValue().name()));
                setFont(Theme.codeFont(11));
                return this;
            }
        });
        list.setVisibleRowCount(Math.min(8, rows.size()));
        list.setFixedCellHeight(24);
        list.setBackground(Theme.card());
        var scroll = new JScrollPane(list);
        scroll.setBorder(new javax.swing.border.AbstractBorder() {
            @Override public void paintBorder(Component c, Graphics g, int x, int y, int width, int height) {
                var g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(Theme.line());
                g2.drawRoundRect(x, y, width - 1, height - 1, 10, 10);
                g2.dispose();
            }
            @Override public Insets getBorderInsets(Component c) { return new Insets(4, 2, 4, 2); }
        });
        scroll.setAlignmentX(LEFT_ALIGNMENT);
        scroll.setMaximumSize(new Dimension(Integer.MAX_VALUE, scroll.getPreferredSize().height));
        put(scroll);
    }

    private void showPackage(Workspace workspace, String pkg) {
        section(Text.t("inspector.package"));
        put(title(pkg.isEmpty() ? Text.t("explorer.defaultPackage") : Ui.visible(pkg)));
        var items = workspace.classes().stream().filter(item -> item.packageName().equals(pkg)).toList();
        row(Text.t("inspector.classes"), String.valueOf(items.size()));
        if (workspace.decompiled())
            levels(items.stream().map(item -> workspace.evidence(item.name()).map(ResultIndex.ClassEntry::level).orElse(null)).toList());
    }

    private void showResource(Workspace.ResourceItem item) {
        section(Text.t("inspector.resource"));
        put(title(Ui.visible(item.entry().substring(item.entry().lastIndexOf('/') + 1))));
        put(wrap(Ui.visible(item.entry()), true));
        row(Text.t("inspector.size"), bytes(item.size()));
        if (item.sha256() != null) selectable("SHA-256", item.sha256());
    }

    /** Count of classes per level, with the level's color. */
    private void levels(List<ResultIndex.Level> levels) {
        section(Text.t("inspector.confidence"));
        var counts = new EnumMap<ResultIndex.Level,Integer>(ResultIndex.Level.class);
        for (var level : levels) if (level != null) counts.merge(level, 1, Integer::sum);
        if (counts.isEmpty()) { put(wrap(Text.t("inspector.noLevels"), true)); return; }
        var bar = OverviewPanel.levelBar(counts);
        bar.setAlignmentX(LEFT_ALIGNMENT);
        bar.setPreferredSize(new Dimension(240, 8));
        put(bar);
        put(Box.createVerticalStrut(6));
        for (var level : ResultIndex.Level.values()) outcomeRow(Text.t("level." + level.name()), counts.getOrDefault(level, 0), level);
    }

    private void decompileHint() {
        put(Box.createVerticalStrut(14));
        var card = new Ui.Card(Theme.ACCENT);
        card.setLayout(new BorderLayout(0, 10));
        card.setBorder(Ui.padding(12, 16, 12, 14));
        card.add(Ui.text(Text.t("inspector.notDecompiled"), true), BorderLayout.CENTER);
        var button = Ui.primary(Ui.action(Text.t("action.decompile"), Icons.of(Icons.Glyph.PLAY, 13, Theme.ON_ACCENT), null, decompile));
        var row = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        row.setOpaque(false);
        row.add(button);
        card.add(row, BorderLayout.SOUTH);
        card.setAlignmentX(LEFT_ALIGNMENT);
        put(card);
    }

    static String originText(String origin) {
        if (origin == null || origin.equals("none")) return Text.t("inspector.origin.none");
        String engine = origin.replace("+repair", "").replace("+methods", "");
        var extras = new ArrayList<String>();
        if (origin.contains("+repair")) extras.add(Text.t("inspector.origin.repair"));
        if (origin.contains("+methods")) extras.add(Text.t("inspector.origin.methods"));
        return engine + (extras.isEmpty() ? "" : " (" + String.join(", ", extras) + ")");
    }

    private void section(String title) {
        if (body.getComponentCount() > 0) {
            body.add(Box.createVerticalStrut(14));
            var rule = new JComponent() {
                @Override protected void paintComponent(Graphics g) { g.setColor(Theme.line()); g.fillRect(0, 0, getWidth(), 1); }
                @Override public Dimension getMaximumSize() { return new Dimension(Integer.MAX_VALUE, 1); }
            };
            rule.setPreferredSize(new Dimension(10, 1));
            rule.setAlignmentX(LEFT_ALIGNMENT);
            body.add(rule);
            body.add(Box.createVerticalStrut(12));
        }
        var label = Ui.caption(title);
        label.setAlignmentX(LEFT_ALIGNMENT);
        body.add(label);
        body.add(Box.createVerticalStrut(8));
    }

    private void row(String name, String value) {
        var row = new JPanel(new BorderLayout(Ui.GAP, 0));
        row.setOpaque(false);
        row.add(Ui.muted(name), BorderLayout.WEST);
        var right = new JLabel(value);
        right.setHorizontalAlignment(SwingConstants.RIGHT);
        row.add(right, BorderLayout.CENTER);
        row.setAlignmentX(LEFT_ALIGNMENT);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height + 4));
        row.setBorder(Ui.padding(2, 0, 2, 0));
        body.add(row);
    }

    private void selectable(String name, String value) {
        body.add(Box.createVerticalStrut(4));
        var label = Ui.muted(name);
        label.setAlignmentX(LEFT_ALIGNMENT);
        body.add(label);
        var field = new JTextField(value);
        field.setEditable(false);
        field.setFont(Theme.codeFont(11));
        var copy = new JButton(Icons.of(Icons.Glyph.COPY, 13));
        copy.setToolTipText(Text.t("inspector.copy"));
        copy.putClientProperty(com.formdev.flatlaf.FlatClientProperties.BUTTON_TYPE, com.formdev.flatlaf.FlatClientProperties.BUTTON_TYPE_TOOLBAR_BUTTON);
        copy.setFocusable(false);
        copy.addActionListener(e -> Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new java.awt.datatransfer.StringSelection(value), null));
        field.putClientProperty(com.formdev.flatlaf.FlatClientProperties.TEXT_FIELD_TRAILING_COMPONENT, copy);
        field.setAlignmentX(LEFT_ALIGNMENT);
        field.setMaximumSize(new Dimension(Integer.MAX_VALUE, field.getPreferredSize().height));
        body.add(Box.createVerticalStrut(4));
        body.add(field);
    }

    private JComponent wrap(String text, boolean muted) { return Ui.text(text, muted); }

    /** Phase G8: the subject's name, in the same style for an input, a class, a package and a resource. */
    private static JLabel title(String text) {
        var label = new JLabel(text);
        label.putClientProperty(com.formdev.flatlaf.FlatClientProperties.STYLE, "font: +3 $semibold.font");
        return label;
    }

    /** Everything shown goes into the scrolling column; a plain add() would replace the panel's own content. */
    private void put(Component component) { body.add(component); }

    static String bytes(long size) {
        if (size < 1024) return size + " B";
        if (size < 1024 * 1024) return String.format(Text.locale(), "%.1f KiB", size / 1024.0);
        return String.format(Text.locale(), "%.1f MiB", size / (1024.0 * 1024));
    }
}
