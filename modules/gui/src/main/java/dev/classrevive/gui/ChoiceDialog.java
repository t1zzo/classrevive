package dev.classrevive.gui;

import java.awt.*;
import java.awt.event.*;
import java.util.List;
import javax.swing.*;

/**
 * Phase G9: a question with its answers as cards (a title and what the answer does), instead of a row of bare buttons.
 * The first card is the recommended answer and has the focus; arrows move between cards, Enter or a click chooses, Esc
 * cancels.
 */
final class ChoiceDialog extends JDialog {
    /** One answer: what it is called and what it does. */
    record Option(String title, String description) {}

    private int chosen = -1;

    private ChoiceDialog(Window owner, String title, Icon icon, String headline, String message, List<Option> options) {
        super(owner, title, ModalityType.APPLICATION_MODAL);
        var content = new Ui.Column();
        content.setBorder(Ui.padding(20, 22, 10, 22));
        var head = new JPanel(new BorderLayout(14, 0));
        head.setOpaque(false);
        if (icon != null) { var mark = new JLabel(icon); mark.setVerticalAlignment(SwingConstants.TOP); head.add(mark, BorderLayout.WEST); }
        var text = new Ui.Column();
        text.setOpaque(false);
        var heading = Ui.text(headline, false);
        heading.putClientProperty(com.formdev.flatlaf.FlatClientProperties.STYLE, "font: +3 $semibold.font");
        text.add(heading);
        if (message != null && !message.isBlank()) { text.add(Box.createVerticalStrut(6)); text.add(Ui.text(message, true)); }
        head.add(text, BorderLayout.CENTER);
        content.add(head);
        content.add(Box.createVerticalStrut(16));
        var cards = new java.util.ArrayList<Ui.Card>();
        for (int i = 0; i < options.size(); i++) {
            int index = i;
            var option = options.get(i);
            var card = new Ui.Card(i == 0 ? Theme.ACCENT : null).clickable(() -> { chosen = index; dispose(); });
            card.setLayout(new BorderLayout(0, 3));
            card.setBorder(Ui.padding(11, 16, 12, 14));
            var name = Ui.styled(option.title(), "semibold");
            if (i == 0) name.setIcon(Icons.of(Icons.Glyph.PLAY, 12, Theme.ACCENT));
            name.setIconTextGap(8);
            card.add(name, BorderLayout.NORTH);
            if (option.description() != null && !option.description().isBlank()) card.add(Ui.text(option.description(), true), BorderLayout.CENTER);
            card.getAccessibleContext().setAccessibleName(option.title());
            card.getAccessibleContext().setAccessibleDescription(option.description());
            cards.add(card);
            content.add(card);
            content.add(Box.createVerticalStrut(8));
        }
        // Up and Down move between the answers.
        for (int i = 0; i < cards.size(); i++) {
            int index = i;
            cards.get(i).addKeyListener(new KeyAdapter() {
                @Override public void keyPressed(KeyEvent e) {
                    if (e.getKeyCode() == KeyEvent.VK_DOWN) cards.get(Math.min(cards.size() - 1, index + 1)).requestFocusInWindow();
                    if (e.getKeyCode() == KeyEvent.VK_UP) cards.get(Math.max(0, index - 1)).requestFocusInWindow();
                }
            });
        }
        var cancel = new JButton(Text.t("dialog.cancel"));
        cancel.addActionListener(e -> dispose());
        var footer = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        footer.setBorder(BorderFactory.createCompoundBorder(Ui.topLine(), Ui.padding(10, 22, 12, 22)));
        footer.add(cancel);
        var root = new JPanel(new BorderLayout());
        root.add(content, BorderLayout.CENTER);
        root.add(footer, BorderLayout.SOUTH);
        setContentPane(root);
        getRootPane().registerKeyboardAction(e -> dispose(), KeyStroke.getKeyStroke("ESCAPE"), JComponent.WHEN_IN_FOCUSED_WINDOW);
        pack();
        setSize(Math.max(520, Math.min(600, getWidth())), getHeight());
        pack();
        setLocationRelativeTo(owner);
        if (!cards.isEmpty()) SwingUtilities.invokeLater(() -> cards.getFirst().requestFocusInWindow());
    }

    /** For the screenshot tour: the dialog, not shown. */
    static JDialog forTour(Window owner, String title, Icon icon, String headline, String message, List<Option> options) {
        return new ChoiceDialog(owner, title, icon, headline, message, options);
    }

    /** Shows the question; the index of the chosen option, or -1 when cancelled. */
    static int ask(Component parent, String title, Icon icon, String headline, String message, List<Option> options) {
        Window owner = parent instanceof Window window ? window : parent == null ? null : SwingUtilities.getWindowAncestor(parent);
        var dialog = new ChoiceDialog(owner, title, icon, headline, message, options);
        dialog.setVisible(true);
        return dialog.chosen;
    }
}
