package dev.classrevive.gui;

import dev.classrevive.workbench.ResultIndex;
import java.awt.*;
import java.util.*;
import java.util.List;
import javax.swing.*;

/** Phase G3: details of what the explorer has selected: a class's evidence, a package's levels, a resource, or the input. */
final class InspectorPanel extends JPanel {
    private final Ui.Column body = new Ui.Column();
    private final Runnable decompile;

    InspectorPanel(Runnable decompile) {
        super(new BorderLayout());
        this.decompile = decompile;
        setBorder(Ui.line(0, 1, 0, 0));
        body.setBorder(Ui.padding(12, 14, 12, 14));
        var scroll = new JScrollPane(body, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        add(scroll, BorderLayout.CENTER);
        setPreferredSize(new Dimension(300, 400));
    }

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
        put(Ui.heading(Ui.visible(workspace.displayName()), 15f));
        row(Text.t("inspector.classes"), String.valueOf(workspace.classes().size()));
        row(Text.t("inspector.resources"), String.valueOf(workspace.resources().size()));
        row(Text.t("inspector.size"), bytes(workspace.inventory().inputBytes()));
        if (workspace.identification() != null) row(Text.t("inspector.format"), workspace.identification().format());
        if (workspace.reversedFrom() != null) row(Text.t("inspector.obfuscator"), Text.t("obfuscation.reversed"));
        else if (workspace.obfuscation() != null) row(Text.t("inspector.obfuscator"), ObfuscationBanner.headline(workspace.obfuscation()));
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
        var name = Ui.heading(Ui.visible(item.simpleName()), 15f);
        char kind = ExplorerPanel.kind(item);
        name.setIcon(Icons.kind(kind, ExplorerPanel.kindColor(kind), null));
        put(name);
        put(wrap(Ui.visible(item.name().replace('/', '.')), true));
        var evidence = workspace.evidence(item.name());
        if (evidence.isPresent() && evidence.get().level() != null) {
            var entry = evidence.get();
            put(Box.createVerticalStrut(10));
            var chip = Ui.chip(Text.t("level." + entry.level().name()), Theme.level(entry.level()));
            chip.setAlignmentX(LEFT_ALIGNMENT);
            put(chip);
            put(Box.createVerticalStrut(4));
            put(wrap(Text.t("level." + entry.level().name() + ".meaning"), true));
            section(Text.t("inspector.evidence"));
            row(Text.t("inspector.origin"), originText(entry.origin()));
            row(Text.t("inspector.signatures"), entry.matchedSymbols() + " / " + entry.expectedSymbols());
            row(Text.t("inspector.identical"), String.valueOf(entry.identical()));
            row(Text.t("inspector.equivalent"), String.valueOf(entry.equivalent()));
            row(Text.t("inspector.different"), String.valueOf(entry.different()));
            if (!entry.differentMethods().isEmpty()) {
                section(Text.t("inspector.differentMethods"));
                var list = new JList<>(entry.differentMethods().stream().limit(200).map(Ui::visible).toArray(String[]::new));
                list.setFont(Theme.codeFont(11));
                list.setVisibleRowCount(Math.min(8, entry.differentMethods().size()));
                var scroll = new JScrollPane(list);
                scroll.setAlignmentX(LEFT_ALIGNMENT);
                scroll.setMaximumSize(new Dimension(Integer.MAX_VALUE, scroll.getPreferredSize().height));
                put(scroll);
            }
            // Phase 2BV renamed this class (case collision or invalid identifier) before the engines saw it.
            if (!entry.className().equals(item.name())) row(Text.t("inspector.portableName"), Ui.visible(entry.className().replace('/', '.')));
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

    private void showPackage(Workspace workspace, String pkg) {
        section(Text.t("inspector.package"));
        put(Ui.heading(pkg.isEmpty() ? Text.t("explorer.defaultPackage") : Ui.visible(pkg), 15f));
        var items = workspace.classes().stream().filter(item -> item.packageName().equals(pkg)).toList();
        row(Text.t("inspector.classes"), String.valueOf(items.size()));
        if (workspace.decompiled())
            levels(items.stream().map(item -> workspace.evidence(item.name()).map(ResultIndex.ClassEntry::level).orElse(null)).toList());
    }

    private void showResource(Workspace.ResourceItem item) {
        section(Text.t("inspector.resource"));
        put(Ui.heading(Ui.visible(item.entry().substring(item.entry().lastIndexOf('/') + 1)), 15f));
        put(wrap(Ui.visible(item.entry()), true));
        row(Text.t("inspector.size"), bytes(item.size()));
        if (item.sha256() != null) selectable("SHA-256", item.sha256());
    }

    /** Count of classes per level, with the level's color. */
    private void levels(List<ResultIndex.Level> levels) {
        section(Text.t("inspector.confidence"));
        var counts = new EnumMap<ResultIndex.Level,Integer>(ResultIndex.Level.class);
        for (var level : levels) if (level != null) counts.merge(level, 1, Integer::sum);
        var bar = new OverviewPanel.LevelBar(counts);
        bar.setAlignmentX(LEFT_ALIGNMENT);
        bar.setMaximumSize(new Dimension(Integer.MAX_VALUE, 10));
        put(bar);
        put(Box.createVerticalStrut(6));
        for (var level : ResultIndex.Level.values()) {
            var label = new JLabel(Text.t("level." + level.name()) + "  " + counts.getOrDefault(level, 0));
            label.setIcon(Icons.dot(Theme.level(level), 10));
            label.setAlignmentX(LEFT_ALIGNMENT);
            put(label);
        }
    }

    private void decompileHint() {
        put(Box.createVerticalStrut(12));
        put(wrap(Text.t("inspector.notDecompiled"), true));
        put(Box.createVerticalStrut(8));
        var button = Ui.primary(Ui.action(Text.t("action.decompile"), Icons.of(Icons.Glyph.PLAY, 14, Theme.ON_ACCENT), null, decompile));
        button.setAlignmentX(LEFT_ALIGNMENT);
        put(button);
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
        if (body.getComponentCount() > 0) body.add(Box.createVerticalStrut(16));
        var label = Ui.muted(title.toUpperCase(Text.locale()));
        label.setFont(label.getFont().deriveFont(Font.BOLD, label.getFont().getSize2D() - 1.5f));
        label.setAlignmentX(LEFT_ALIGNMENT);
        body.add(label);
        body.add(Box.createVerticalStrut(6));
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
        field.setAlignmentX(LEFT_ALIGNMENT);
        field.setMaximumSize(new Dimension(Integer.MAX_VALUE, field.getPreferredSize().height));
        body.add(field);
    }

    private JComponent wrap(String text, boolean muted) { return Ui.text(text, muted); }

    /** Everything shown goes into the scrolling column; a plain add() would replace the panel's own content. */
    private void put(Component component) { body.add(component); }

    static String bytes(long size) {
        if (size < 1024) return size + " B";
        if (size < 1024 * 1024) return String.format(Text.locale(), "%.1f KiB", size / 1024.0);
        return String.format(Text.locale(), "%.1f MiB", size / (1024.0 * 1024));
    }
}
