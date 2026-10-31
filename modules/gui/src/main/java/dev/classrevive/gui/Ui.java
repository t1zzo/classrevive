package dev.classrevive.gui;

import com.formdev.flatlaf.FlatClientProperties;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.nio.file.*;
import javax.swing.*;
import javax.swing.border.Border;

/** Phase G3: small shared pieces so every panel spaces, labels and reports errors the same way. */
final class Ui {
    static final int GAP = 8, PAD = 12;
    /** Every state spelled out: FlatLaf would otherwise paint its own focused, hover or toolbar colors over the accent. */
    static final String PRIMARY_STYLE = "background: #FF8A1F; focusedBackground: #FF8A1F; hoverBackground: #FF9D45; pressedBackground: #E5751A; "
        + "selectedBackground: #E5751A; disabledBackground: #FF8A1F4D; foreground: #141416; disabledText: #14141699; font: bold; "
        + "default.background: #FF8A1F; default.focusedBackground: #FF8A1F; default.hoverBackground: #FF9D45; "
        + "default.pressedBackground: #E5751A; default.foreground: #141416; default.borderWidth: 0; "
        + "borderWidth: 0; focusWidth: 0; innerFocusWidth: 0; arc: 10";

    private Ui() {}

    static Action action(String name, Icon icon, KeyStroke key, Runnable run) {
        var action = new AbstractAction(name, icon) { @Override public void actionPerformed(ActionEvent event) { run.run(); } };
        if (key != null) action.putValue(Action.ACCELERATOR_KEY, key);
        action.putValue(Action.SHORT_DESCRIPTION, key == null ? name : name + "  (" + keyText(key) + ")");
        return action;
    }

    static String keyText(KeyStroke key) {
        String modifiers = java.awt.event.InputEvent.getModifiersExText(key.getModifiers());
        String text = java.awt.event.KeyEvent.getKeyText(key.getKeyCode());
        return modifiers.isEmpty() ? text : modifiers + "+" + text;
    }

    static KeyStroke key(String spec) { return KeyStroke.getKeyStroke(spec); }

    static JLabel heading(String text, float size) {
        var label = new JLabel(text);
        label.setFont(label.getFont().deriveFont(Font.BOLD, size));
        return label;
    }

    static JLabel muted(String text) {
        var label = new JLabel(text);
        label.putClientProperty(FlatClientProperties.STYLE, "foreground: $Label.disabledForeground");
        return label;
    }

    /** A rounded label in {@code color}, for levels and statuses. */
    static Chip chip(String text, Color color) { return new Chip(text, color); }

    /** A pill-shaped label; painted by the component itself so a theme switch keeps it. */
    static final class Chip extends JLabel {
        private Color color;
        Chip(String text, Color color) {
            super(text);
            this.color = color;
            setForeground(Theme.textOn(color));
            setBorder(BorderFactory.createEmptyBorder(2, 9, 2, 9));
        }
        void set(String text, Color color) { setText(text); this.color = color; setForeground(Theme.textOn(color)); repaint(); }
        @Override public void updateUI() {
            super.updateUI();
            setFont(UIManager.getFont("Label.font").deriveFont(Font.BOLD, UIManager.getFont("Label.font").getSize2D() - 1));
            if (color != null) setForeground(Theme.textOn(color));
        }
        @Override protected void paintComponent(Graphics g) {
            var g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(color);
            g2.fillRoundRect(0, 0, getWidth(), getHeight(), getHeight(), getHeight());
            g2.dispose();
            super.paintComponent(g);
        }
    }

    /** A vertical stack for scroll panes: as wide as the viewport, so text wraps instead of running off the right edge. */
    static final class Column extends JPanel implements Scrollable {
        Column() { setLayout(new BoxLayout(this, BoxLayout.Y_AXIS)); }
        /** BoxLayout squeezes left-aligned children when any child (a strut or glue, by default) is centered. */
        @Override protected void addImpl(Component component, Object constraints, int index) {
            if (component instanceof JComponent child) child.setAlignmentX(LEFT_ALIGNMENT);
            super.addImpl(component, constraints, index);
        }
        @Override public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
        @Override public int getScrollableUnitIncrement(Rectangle visible, int orientation, int direction) { return 16; }
        @Override public int getScrollableBlockIncrement(Rectangle visible, int orientation, int direction) { return visible.height - 32; }
        @Override public boolean getScrollableTracksViewportWidth() { return true; }
        @Override public boolean getScrollableTracksViewportHeight() { return false; }
    }

    /** Read-only text that wraps at the available width and takes only the height it needs. */
    static JTextArea text(String text, boolean muted) {
        var area = new JTextArea(text) {
            @Override public Dimension getMaximumSize() { return new Dimension(Integer.MAX_VALUE, getPreferredSize().height); }
            @Override public void updateUI() { super.updateUI(); setFont(UIManager.getFont("Label.font")); setOpaque(false); setBorder(BorderFactory.createEmptyBorder()); }
        };
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setEditable(false);
        area.setFocusable(false);
        area.setOpaque(false);
        area.setBorder(BorderFactory.createEmptyBorder());
        area.setFont(UIManager.getFont("Label.font"));
        if (muted) area.putClientProperty(FlatClientProperties.STYLE, "foreground: $Label.disabledForeground");
        area.setAlignmentX(Component.LEFT_ALIGNMENT);
        return area;
    }

    static Border padding(int top, int left, int bottom, int right) { return BorderFactory.createEmptyBorder(top, left, bottom, right); }

    static Border bottomLine() { return line(0, 0, 1, 0); }
    static Border topLine() { return line(1, 0, 0, 0); }

    /** A separator line in the current theme's border color, read when painted so a theme switch recolors it. */
    static Border line(int top, int left, int bottom, int right) {
        return new javax.swing.border.AbstractBorder() {
            @Override public void paintBorder(Component c, Graphics g, int x, int y, int width, int height) {
                g.setColor(Theme.line());
                if (top > 0) g.fillRect(x, y, width, top);
                if (bottom > 0) g.fillRect(x, y + height - bottom, width, bottom);
                if (left > 0) g.fillRect(x, y, left, height);
                if (right > 0) g.fillRect(x + width - right, y, right, height);
            }
            @Override public Insets getBorderInsets(Component c) { return new Insets(top, left, bottom, right); }
        };
    }

    static JButton toolButton(Action action) {
        var button = new JButton(action);
        button.putClientProperty(FlatClientProperties.BUTTON_TYPE, FlatClientProperties.BUTTON_TYPE_TOOLBAR_BUTTON);
        button.setFocusable(false);
        return button;
    }

    /** An accent-colored button for the one main action of a screen. */
    static JButton primary(Action action) {
        var button = new JButton(action);
        button.putClientProperty(FlatClientProperties.STYLE, PRIMARY_STYLE);
        return button;
    }

    /** A message dialog with an optional "details" area for the evidence behind it. */
    static void error(Component parent, String title, String message, String details) {
        var panel = new JPanel(new BorderLayout(0, GAP));
        var text = new JLabel("<html><body style='width:360px'>" + escape(message) + "</body></html>");
        panel.add(text, BorderLayout.NORTH);
        if (details != null && !details.isBlank()) {
            var area = new JTextArea(details, Math.min(14, details.split("\n").length + 1), 60);
            area.setEditable(false);
            area.setFont(Theme.codeFont(12));
            area.setCaretPosition(0);
            panel.add(new JScrollPane(area), BorderLayout.CENTER);
        }
        JOptionPane.showMessageDialog(parent, panel, title, JOptionPane.ERROR_MESSAGE);
    }

    static void info(Component parent, String title, String message) {
        JOptionPane.showMessageDialog(parent, "<html><body style='width:360px'>" + escape(message) + "</body></html>", title, JOptionPane.INFORMATION_MESSAGE);
    }

    /** Opens a file or folder with the system's default application. */
    static void open(Component parent, Path path) {
        try {
            if (!Desktop.isDesktopSupported()) throw new UnsupportedOperationException();
            Desktop.getDesktop().open(path.toFile());
        } catch (Exception failure) {
            error(parent, Text.t("error.title"), Text.t("error.cannotOpen", path), String.valueOf(failure.getMessage()));
        }
    }

    /** Text from the input can hold markup or control characters: shown as plain, visible text. */
    static String escape(String text) {
        var out = new StringBuilder(text.length());
        for (char c : text.toCharArray()) {
            switch (c) {
                case '<' -> out.append("&lt;"); case '>' -> out.append("&gt;"); case '&' -> out.append("&amp;");
                case '\n' -> out.append("<br>");
                default -> {
                    if (Character.isISOControl(c) || Character.getType(c) == Character.FORMAT) out.append(String.format("\\u%04x", (int) c));
                    else out.append(c);
                }
            }
        }
        return out.toString();
    }

    /** Like {@link #escape} without markup: makes control and bidi characters of names visible (Trojan Source). */
    static String visible(String text) {
        var out = new StringBuilder(text.length());
        for (char c : text.toCharArray())
            if (Character.isISOControl(c) || Character.getType(c) == Character.FORMAT) out.append(String.format("\\u%04x", (int) c));
            else out.append(c);
        return out.toString();
    }
}
