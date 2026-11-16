package dev.classrevive.gui;

import com.formdev.flatlaf.FlatClientProperties;
import dev.classrevive.bytecode.ReferenceIndex.Ref;
import java.awt.*;
import java.awt.event.*;
import java.util.*;
import java.util.List;
import java.util.function.UnaryOperator;
import javax.swing.*;

/**
 * Phase G6: asks which member is meant when a name has several candidates (overloads, or the same name in several
 * classes). The user picks; nothing is chosen for them. Phase G9: a dialog of its own with a filter field, a badge per
 * kind (class, field, method), the owner in a muted tone and a count; Enter or a double click chooses, Esc cancels.
 */
final class MemberChooser {
    private MemberChooser() {}

    /** What the window asks; tests replace it. */
    interface Ask { Optional<Ref> choose(Component parent, String message, List<Ref> options, UnaryOperator<String> treeNames); }

    static Optional<Ref> choose(Component parent, String message, List<Ref> options, UnaryOperator<String> treeNames) {
        Window owner = parent instanceof Window window ? window : parent == null ? null : SwingUtilities.getWindowAncestor(parent);
        var dialog = new JDialog(owner, Text.t("usages.choose.title"), Dialog.ModalityType.APPLICATION_MODAL);
        var model = new DefaultListModel<Ref>();
        var list = new JList<>(model);
        var filter = new JTextField();
        var count = Ui.faint("");
        Ref[] answer = {null};
        filter.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, Text.t("usages.choose.filter"));
        filter.putClientProperty(FlatClientProperties.TEXT_FIELD_LEADING_ICON, Icons.of(Icons.Glyph.SEARCH, 14));
        Runnable refill = () -> {
            model.clear();
            for (var ref : matching(options, filter.getText(), treeNames)) model.addElement(ref);
            if (!model.isEmpty()) list.setSelectedIndex(0);
            count.setText(Text.t("usages.choose.count", model.size(), options.size()));
        };
        list.setCellRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> view, Object value, int index, boolean selected, boolean focus) {
                super.getListCellRendererComponent(view, value, index, selected, false);
                if (value instanceof Ref ref) {
                    String owner = Ui.visible(treeNames.apply(ref.owner()).replace('/', '.'));
                    String muted = String.format("#%06X", (selected ? getForeground() : Theme.muted()).getRGB() & 0xFFFFFF);
                    setText("<html><b>" + Ui.escape(UsagesPanel.member(ref, treeNames)) + "</b> &nbsp;<font color='" + muted + "'>" + Ui.escape(owner) + "</font></html>");
                    setIcon(Icons.kind(ref.isClass() ? 'C' : ref.isMethod() ? 'm' : 'f', ref.isClass() ? ExplorerPanel.kindColor('C')
                        : ref.isMethod() ? new Color(0xB07C3C) : new Color(0x3C8CA0), null));
                    setIconTextGap(8);
                }
                return this;
            }
        });
        list.setFixedCellHeight(28);
        list.setVisibleRowCount(Math.min(12, Math.max(4, options.size())));
        Runnable accept = () -> { answer[0] = list.getSelectedValue(); if (answer[0] != null) dialog.dispose(); };
        list.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent event) { if (event.getClickCount() == 2) accept.run(); }
        });
        filter.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            @Override public void insertUpdate(javax.swing.event.DocumentEvent e) { refill.run(); }
            @Override public void removeUpdate(javax.swing.event.DocumentEvent e) { refill.run(); }
            @Override public void changedUpdate(javax.swing.event.DocumentEvent e) { refill.run(); }
        });
        // The filter keeps the focus; arrows move the selection in the list, Enter chooses.
        filter.addKeyListener(new KeyAdapter() {
            @Override public void keyPressed(KeyEvent event) {
                int size = model.getSize();
                if (size == 0) return;
                if (event.getKeyCode() == KeyEvent.VK_DOWN) { list.setSelectedIndex(Math.min(size - 1, list.getSelectedIndex() + 1)); event.consume(); }
                if (event.getKeyCode() == KeyEvent.VK_UP) { list.setSelectedIndex(Math.max(0, list.getSelectedIndex() - 1)); event.consume(); }
                list.ensureIndexIsVisible(list.getSelectedIndex());
            }
        });
        filter.addActionListener(e -> accept.run());

        var content = new JPanel(new BorderLayout(0, 10));
        content.setBorder(Ui.padding(16, 18, 10, 18));
        var top = new Ui.Column();
        top.add(Ui.text(message, false));
        top.add(Box.createVerticalStrut(10));
        top.add(filter);
        content.add(top, BorderLayout.NORTH);
        var scroll = new JScrollPane(list);
        scroll.setPreferredSize(new Dimension(560, list.getFixedCellHeight() * list.getVisibleRowCount() + 6));
        content.add(scroll, BorderLayout.CENTER);
        var footer = new JPanel(new BorderLayout());
        footer.setBorder(BorderFactory.createCompoundBorder(Ui.topLine(), Ui.padding(10, 18, 12, 18)));
        footer.add(count, BorderLayout.WEST);
        var buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        var cancel = new JButton(Text.t("dialog.cancel"));
        cancel.addActionListener(e -> dialog.dispose());
        var ok = Ui.primary(Ui.action(Text.t("usages.choose.ok"), null, null, accept));
        buttons.add(cancel);
        buttons.add(ok);
        footer.add(buttons, BorderLayout.EAST);
        var root = new JPanel(new BorderLayout());
        root.add(content, BorderLayout.CENTER);
        root.add(footer, BorderLayout.SOUTH);
        dialog.setContentPane(root);
        dialog.getRootPane().setDefaultButton(ok);
        dialog.getRootPane().registerKeyboardAction(e -> dialog.dispose(), KeyStroke.getKeyStroke("ESCAPE"), JComponent.WHEN_IN_FOCUSED_WINDOW);
        refill.run();
        dialog.pack();
        dialog.setResizable(true);
        dialog.setLocationRelativeTo(owner);
        dialog.setVisible(true);
        return Optional.ofNullable(answer[0]);
    }

    /** Phase G9: the options whose member or owner name contains {@code filter} (case-insensitive), in their order. */
    static List<Ref> matching(List<Ref> options, String filter, UnaryOperator<String> treeNames) {
        String needle = filter.strip().toLowerCase(Locale.ROOT);
        if (needle.isEmpty()) return options;
        var found = new ArrayList<Ref>();
        for (var ref : options) {
            String text = (UsagesPanel.member(ref, treeNames) + " " + treeNames.apply(ref.owner()).replace('/', '.')).toLowerCase(Locale.ROOT);
            if (text.contains(needle)) found.add(ref);
        }
        return found;
    }
}
