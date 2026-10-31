package dev.classrevive.gui;

import dev.classrevive.bytecode.ReferenceIndex.Ref;
import java.awt.*;
import java.awt.event.*;
import java.util.*;
import java.util.List;
import java.util.function.UnaryOperator;
import javax.swing.*;

/**
 * Phase G6: asks which member is meant when a name has several candidates (overloads, or the same name in several
 * classes). The user picks; nothing is chosen for them.
 */
final class MemberChooser {
    private MemberChooser() {}

    /** What the window asks; tests replace it. */
    interface Ask { Optional<Ref> choose(Component parent, String message, List<Ref> options, UnaryOperator<String> treeNames); }

    static Optional<Ref> choose(Component parent, String message, List<Ref> options, UnaryOperator<String> treeNames) {
        var list = new JList<>(options.toArray(Ref[]::new));
        list.setCellRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> view, Object value, int index, boolean selected, boolean focus) {
                super.getListCellRendererComponent(view, value, index, selected, focus);
                if (value instanceof Ref ref) {
                    String owner = treeNames.apply(ref.owner()).replace('/', '.');
                    setText("<html>" + Ui.escape(UsagesPanel.member(ref, treeNames)) + " <span style='color:gray'>" + Ui.escape(Ui.visible(owner)) + "</span></html>");
                }
                return this;
            }
        });
        list.setSelectedIndex(0);
        list.setVisibleRowCount(Math.min(12, options.size()));
        var pane = new JOptionPane(new Object[] {message, new JScrollPane(list)}, JOptionPane.QUESTION_MESSAGE, JOptionPane.OK_CANCEL_OPTION);
        var dialog = pane.createDialog(parent, Text.t("usages.choose.title"));
        list.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent event) {
                if (event.getClickCount() == 2) pane.setValue(JOptionPane.OK_OPTION);
            }
        });
        dialog.setResizable(true);
        dialog.setVisible(true);
        Object answer = pane.getValue();
        dialog.dispose();
        return Integer.valueOf(JOptionPane.OK_OPTION).equals(answer) ? Optional.ofNullable(list.getSelectedValue()) : Optional.empty();
    }
}
