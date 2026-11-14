package dev.classrevive.gui;

import com.formdev.flatlaf.FlatClientProperties;
import java.awt.*;
import java.awt.event.*;
import java.util.*;
import java.util.List;
import java.util.function.Consumer;
import javax.swing.*;

/** Phase G3: type part of a class name, pick it, open it (Ctrl+N). Prefix matches of the simple name come first. */
final class GoToClassDialog extends JDialog {
    static final int LIMIT = 300;

    private final JTextField field = new JTextField(36);
    private final DefaultListModel<Workspace.ClassItem> model = new DefaultListModel<>();
    private final JList<Workspace.ClassItem> list = new JList<>(model);
    private final JLabel count = Ui.faint("");

    GoToClassDialog(Window owner, Workspace workspace, Consumer<Workspace.ClassItem> open) {
        super(owner, Text.t("goto.title"), ModalityType.APPLICATION_MODAL);
        field.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, Text.t("goto.placeholder"));
        field.putClientProperty(FlatClientProperties.TEXT_FIELD_LEADING_ICON, Icons.of(Icons.Glyph.SEARCH, 14));
        list.setCellRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> l, Object value, int index, boolean selected, boolean focus) {
                super.getListCellRendererComponent(l, value, index, selected, false);
                var item = (Workspace.ClassItem) value;
                char kind = ExplorerPanel.kind(item);
                var level = workspace.evidence(item.name()).map(e -> e.level()).orElse(null);
                setIcon(Icons.kind(kind, ExplorerPanel.kindColor(kind), level == null ? null : Theme.level(level)));
                setIconTextGap(8);
                String muted = String.format("#%06X", (selected ? getForeground() : Theme.muted()).getRGB() & 0xFFFFFF);
                String accent = String.format("#%06X", (selected ? getForeground() : Theme.accentText()).getRGB() & 0xFFFFFF);
                setText("<html>" + match(item.simpleName(), field.getText().strip(), accent) + " &nbsp;<font color='" + muted + "'>"
                    + Ui.escape(item.packageName()) + "</font></html>");
                return this;
            }
        });
        list.setFixedCellHeight(28);
        var panel = new JPanel(new BorderLayout(0, 10));
        panel.setBorder(Ui.padding(14, 14, 12, 14));
        field.putClientProperty(FlatClientProperties.STYLE, "font: +2; margin: 4,6,4,6");
        panel.add(field, BorderLayout.NORTH);
        var scroll = new JScrollPane(list);
        scroll.setPreferredSize(new Dimension(600, 380));
        panel.add(scroll, BorderLayout.CENTER);
        var footer = new JPanel(new BorderLayout());
        footer.add(Ui.muted(Text.t("goto.hint")), BorderLayout.WEST);
        footer.add(count, BorderLayout.EAST);
        panel.add(footer, BorderLayout.SOUTH);
        setContentPane(panel);
        Runnable choose = () -> { var item = list.getSelectedValue(); if (item != null) { dispose(); open.accept(item); } };
        field.addActionListener(e -> choose.run());
        field.addKeyListener(new KeyAdapter() {
            @Override public void keyPressed(KeyEvent event) {
                int size = model.getSize();
                if (size == 0) return;
                if (event.getKeyCode() == KeyEvent.VK_DOWN) { list.setSelectedIndex(Math.min(size - 1, list.getSelectedIndex() + 1)); list.ensureIndexIsVisible(list.getSelectedIndex()); event.consume(); }
                if (event.getKeyCode() == KeyEvent.VK_UP) { list.setSelectedIndex(Math.max(0, list.getSelectedIndex() - 1)); list.ensureIndexIsVisible(list.getSelectedIndex()); event.consume(); }
            }
        });
        list.addMouseListener(new MouseAdapter() { @Override public void mouseClicked(MouseEvent event) { if (event.getClickCount() == 2) choose.run(); } });
        getRootPane().registerKeyboardAction(e -> dispose(), KeyStroke.getKeyStroke("ESCAPE"), JComponent.WHEN_IN_FOCUSED_WINDOW);
        field.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            @Override public void insertUpdate(javax.swing.event.DocumentEvent e) { update(workspace); }
            @Override public void removeUpdate(javax.swing.event.DocumentEvent e) { update(workspace); }
            @Override public void changedUpdate(javax.swing.event.DocumentEvent e) { update(workspace); }
        });
        update(workspace);
        pack();
        setLocationRelativeTo(owner);
    }

    private void update(Workspace workspace) {
        model.clear();
        var ranked = rank(workspace.classes(), field.getText().strip());
        for (var item : ranked) model.addElement(item);
        if (!model.isEmpty()) list.setSelectedIndex(0);
        count.setText(ranked.isEmpty() ? Text.t("goto.none") : ranked.size() >= LIMIT ? Text.t("goto.first", LIMIT) : Text.t("goto.count", ranked.size()));
    }

    /** Phase G8: the simple name with the typed part in bold accent (the first match, case-insensitive). */
    static String match(String simple, String query, String color) {
        int at = query.isEmpty() ? -1 : simple.toLowerCase(Locale.ROOT).indexOf(query.toLowerCase(Locale.ROOT));
        if (at < 0 || simple.toLowerCase(Locale.ROOT).length() != simple.length()) return "<b>" + Ui.escape(simple) + "</b>";
        return "<b>" + Ui.escape(simple.substring(0, at)) + "<font color='" + color + "'>" + Ui.escape(simple.substring(at, at + query.length()))
            + "</font>" + Ui.escape(simple.substring(at + query.length())) + "</b>";
    }

    /** Simple-name prefix, then simple-name substring, then qualified-name substring, alphabetically; nested and anonymous classes last. */
    static List<Workspace.ClassItem> rank(List<Workspace.ClassItem> classes, String query) {
        String needle = query.toLowerCase(Locale.ROOT);
        var scored = new ArrayList<Map.Entry<Integer,Workspace.ClassItem>>();
        for (var item : classes) {
            String simple = item.simpleName().toLowerCase(Locale.ROOT), full = item.name().replace('/', '.').toLowerCase(Locale.ROOT);
            int score;
            if (needle.isEmpty()) score = 3;
            else if (simple.startsWith(needle)) score = 0;
            else if (simple.contains(needle)) score = 1;
            else if (full.contains(needle)) score = 2;
            else continue;
            if (item.simpleName().contains("$")) score += 4;
            scored.add(Map.entry(score, item));
        }
        scored.sort(Map.Entry.<Integer,Workspace.ClassItem>comparingByKey()
            .thenComparing(entry -> entry.getValue().simpleName().toLowerCase(Locale.ROOT)).thenComparing(entry -> entry.getValue().name()));
        return scored.stream().limit(LIMIT).map(Map.Entry::getValue).toList();
    }
}
