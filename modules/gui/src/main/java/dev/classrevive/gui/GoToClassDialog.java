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

    GoToClassDialog(Window owner, Workspace workspace, Consumer<Workspace.ClassItem> open) {
        super(owner, Text.t("goto.title"), ModalityType.APPLICATION_MODAL);
        field.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, Text.t("goto.placeholder"));
        field.putClientProperty(FlatClientProperties.TEXT_FIELD_LEADING_ICON, Icons.of(Icons.Glyph.SEARCH, 14));
        list.setCellRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> l, Object value, int index, boolean selected, boolean focus) {
                super.getListCellRendererComponent(l, value, index, selected, focus);
                var item = (Workspace.ClassItem) value;
                char kind = ExplorerPanel.kind(item);
                setIcon(Icons.kind(kind, ExplorerPanel.kindColor(kind), workspace.evidence(item.name()).map(e -> Theme.level(e.level())).orElse(null)));
                setText("<html><b>" + Ui.escape(item.simpleName()) + "</b> &nbsp;<span style='color:gray'>" + Ui.escape(item.packageName()) + "</span></html>");
                return this;
            }
        });
        list.setFixedCellHeight(26);
        var panel = new JPanel(new BorderLayout(0, Ui.GAP));
        panel.setBorder(Ui.padding(Ui.PAD, Ui.PAD, Ui.PAD, Ui.PAD));
        panel.add(field, BorderLayout.NORTH);
        var scroll = new JScrollPane(list);
        scroll.setPreferredSize(new Dimension(560, 360));
        panel.add(scroll, BorderLayout.CENTER);
        panel.add(Ui.muted(Text.t("goto.hint")), BorderLayout.SOUTH);
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
        for (var item : rank(workspace.classes(), field.getText().strip())) model.addElement(item);
        if (!model.isEmpty()) list.setSelectedIndex(0);
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
