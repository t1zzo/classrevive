package dev.classrevive.gui;

import com.formdev.flatlaf.FlatClientProperties;
import java.awt.*;
import java.awt.event.*;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import javax.swing.*;
import javax.swing.border.Border;

/** Phase G3, extended in G8: shared pieces so every panel spaces, labels, animates and reports errors the same way. */
final class Ui {
    static final int GAP = 8, PAD = 12;
    static final boolean MAC = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).startsWith("mac");
    /**
     * Every state spelled out: FlatLaf would otherwise paint its own focused, hover or toolbar colors over the accent.
     * Phase G8: a disabled primary button is neutral gray instead of a dimmed (brownish) orange.
     */
    static final String PRIMARY_STYLE = "background: #FF8A1F; focusedBackground: #FF8A1F; hoverBackground: #FF9D45; pressedBackground: #E5751A; "
        + "selectedBackground: #E5751A; disabledBackground: $ClassRevive.hoverBackground; foreground: #141416; disabledText: $ClassRevive.mutedForeground; "
        + "font: $semibold.font; default.background: #FF8A1F; default.focusedBackground: #FF8A1F; default.hoverBackground: #FF9D45; "
        + "default.pressedBackground: #E5751A; default.foreground: #141416; default.borderWidth: 0; "
        + "borderWidth: 0; focusWidth: 0; innerFocusWidth: 0; arc: 8";

    private Ui() {}

    static Action action(String name, Icon icon, KeyStroke key, Runnable run) {
        var action = new AbstractAction(name, icon) { @Override public void actionPerformed(ActionEvent event) { run.run(); } };
        if (key != null) action.putValue(Action.ACCELERATOR_KEY, key);
        action.putValue(Action.SHORT_DESCRIPTION, key == null ? name : name + "  (" + keyText(key) + ")");
        return action;
    }

    static String keyText(KeyStroke key) {
        String modifiers = InputEvent.getModifiersExText(key.getModifiers());
        String text = KeyEvent.getKeyText(key.getKeyCode());
        return modifiers.isEmpty() ? text : modifiers + "+" + text;
    }

    /**
     * A key stroke from {@link KeyStroke#getKeyStroke(String)} syntax. Phase G8: on macOS "ctrl" means the Command key, as
     * Mac applications expect; Ctrl+click and Ctrl+Alt shortcuts that clash with the system stay as written there.
     */
    static KeyStroke key(String spec) { return KeyStroke.getKeyStroke(platform(spec, MAC)); }

    /** {@code spec} with "ctrl" as "meta" on macOS, except with "alt" (Cmd+Option is taken by the system for many letters). */
    static String platform(String spec, boolean mac) {
        if (!mac || !spec.startsWith("ctrl ") || spec.contains(" alt ")) return spec;
        return "meta " + spec.substring("ctrl ".length());
    }

    /** How a shortcut is written for people on this platform: "Ctrl+Shift+F", or "⌘⇧F" on macOS. */
    static String shortcut(String spec) {
        var key = key(spec);
        return key == null ? spec : keyText(key);
    }

    static JLabel heading(String text, float size) {
        var label = new JLabel(text);
        label.setFont(label.getFont().deriveFont(Font.BOLD, size));
        return label;
    }

    /** Phase G8: a label in one of FlatLaf's type styles: {@code h1}, {@code h2}, {@code h3}, {@code h4}, {@code semibold}, {@code large}, {@code small}. */
    static JLabel styled(String text, String styleClass) {
        var label = new JLabel(text);
        label.putClientProperty(FlatClientProperties.STYLE_CLASS, styleClass);
        return label;
    }

    /** Supporting text: paths, notes, descriptions. */
    static JLabel muted(String text) {
        var label = new JLabel(text);
        label.putClientProperty(FlatClientProperties.STYLE, "foreground: $ClassRevive.secondaryForeground");
        return label;
    }

    /** Phase G8: the faintest text tone, for counts and hints next to a name. */
    static JLabel faint(String text) {
        var label = new JLabel(text);
        label.putClientProperty(FlatClientProperties.STYLE, "foreground: $ClassRevive.mutedForeground");
        return label;
    }

    /** A rounded label in {@code color}, for levels and statuses. */
    static Chip chip(String text, Color color) { return new Chip(text, color, false); }

    /** Phase G8: a quieter chip: a faint tint of {@code color} with a dot and the text in that color. */
    static Chip softChip(String text, Color color) { return new Chip(text, color, true); }

    /** A pill-shaped label; painted by the component itself so a theme switch keeps it. */
    static final class Chip extends JLabel {
        private Color color;
        private final boolean soft;
        Chip(String text, Color color, boolean soft) {
            super(text);
            this.color = color;
            this.soft = soft;
            setBorder(BorderFactory.createEmptyBorder(2, soft ? 18 : 9, 2, 9));
            recolor();
        }
        void set(String text, Color color) { setText(text); this.color = color; recolor(); repaint(); }
        private void recolor() { if (color != null) setForeground(soft ? readable(color) : Theme.textOn(color)); }
        @Override public void updateUI() {
            super.updateUI();
            var font = UIManager.getFont("semibold.font");
            if (font == null) font = UIManager.getFont("Label.font").deriveFont(Font.BOLD);
            setFont(font.deriveFont(font.getSize2D() - 1.5f));
            recolor();
        }
        @Override protected void paintComponent(Graphics g) {
            var g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int h = getHeight();
            if (soft) {
                g2.setColor(Theme.alpha(color, Theme.dark() ? 44 : 34));
                g2.fillRoundRect(0, 0, getWidth(), h, h, h);
                g2.setColor(color);
                g2.fillOval(8, h / 2 - 3, 6, 6);
            } else {
                g2.setColor(color);
                g2.fillRoundRect(0, 0, getWidth(), h, h, h);
            }
            g2.dispose();
            super.paintComponent(g);
        }
    }

    /** {@code color} as text on the current theme's surfaces: darker on the light theme, as is on the dark one. */
    static Color readable(Color color) { return Theme.dark() ? Theme.blend(color, Color.WHITE, 0.12) : Theme.blend(color, Color.BLACK, 0.32); }

    /** A vertical stack for scroll panes: as wide as the viewport, so text wraps instead of running off the right edge. */
    static class Column extends JPanel implements Scrollable {
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
            /** Phase G8: before its first layout the area has no width, and wrapping at zero width asks for a huge height. */
            @Override public Dimension getPreferredSize() {
                if (getWidth() == 0) setSize(new Dimension(260, Short.MAX_VALUE));
                return super.getPreferredSize();
            }
            /** Phase G8: when the width settles the wrapped height changes; the parent is told so it can lay out again. */
            @Override public void setBounds(int x, int y, int width, int height) {
                int before = getWidth();
                super.setBounds(x, y, width, height);
                if (width != before && width > 0) SwingUtilities.invokeLater(() -> { if (getPreferredSize().height != getHeight()) revalidate(); });
            }
            @Override public void updateUI() { super.updateUI(); setFont(UIManager.getFont("Label.font")); setOpaque(false); setBorder(BorderFactory.createEmptyBorder()); }
        };
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setEditable(false);
        area.setFocusable(false);
        area.setOpaque(false);
        area.setBorder(BorderFactory.createEmptyBorder());
        area.setFont(UIManager.getFont("Label.font"));
        if (muted) area.putClientProperty(FlatClientProperties.STYLE, "foreground: $ClassRevive.secondaryForeground");
        area.setAlignmentX(Component.LEFT_ALIGNMENT);
        return area;
    }

    /** Phase G7: a section heading with its own top gap, so panels space their parts the same way. */
    static JLabel section(String title) {
        var label = styled(title, "h4");
        label.putClientProperty(FlatClientProperties.STYLE, "font: +2 $semibold.font");
        label.setBorder(padding(0, 0, 10, 0));
        return label;
    }

    /** Phase G7: the small upper-case caption above a group of rows (inspector and cards). */
    static JLabel caption(String title) {
        var label = new JLabel(title.toUpperCase(Text.locale()));
        label.putClientProperty(FlatClientProperties.STYLE, "font: -2 $semibold.font; foreground: $ClassRevive.mutedForeground");
        return label;
    }

    static Color severityColor(ResultInsights.Severity severity) {
        return switch (severity) {
            case ERROR -> Theme.level(dev.classrevive.workbench.ResultIndex.Level.UNVERIFIED);
            case WARNING -> Theme.warning();
            case INFO -> Theme.level(dev.classrevive.workbench.ResultIndex.Level.CLOSE);
        };
    }

    /** A filled circle with "!" (error, warning) or "i" (information). */
    static Icon severityIcon(ResultInsights.Severity severity, int size) {
        return new Icon() {
            @Override public void paintIcon(Component c, Graphics g, int x, int y) {
                var g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                var color = severityColor(severity);
                g2.setColor(color);
                if (severity == ResultInsights.Severity.WARNING)
                    g2.fill(new java.awt.geom.RoundRectangle2D.Double(x + 1, y + 1, size - 2, size - 2, size / 2.5, size / 2.5));
                else g2.fillOval(x + 1, y + 1, size - 2, size - 2);
                g2.setColor(Theme.textOn(color));
                g2.setFont(new Font(Font.SANS_SERIF, Font.BOLD, Math.max(8, size - 5)));
                var metrics = g2.getFontMetrics();
                String mark = severity == ResultInsights.Severity.INFO ? "i" : "!";
                g2.drawString(mark, x + size / 2f - metrics.stringWidth(mark) / 2f, y + size / 2f + (metrics.getAscent() - metrics.getDescent()) / 2f);
                g2.dispose();
            }
            @Override public int getIconWidth() { return size; }
            @Override public int getIconHeight() { return size; }
        };
    }

    /** A message with an icon, a bold line saying what happened and a muted line saying what to do. */
    static JComponent notice(ResultInsights.Notice notice) {
        var card = new Card(severityColor(notice.severity()));
        card.setLayout(new BorderLayout(12, 0));
        card.setBorder(padding(11, 18, 11, 14));
        var icon = new JLabel(severityIcon(notice.severity(), 16));
        icon.setVerticalAlignment(SwingConstants.TOP);
        icon.setBorder(padding(1, 0, 0, 0));
        card.add(icon, BorderLayout.WEST);
        var body = new Column();
        body.setOpaque(false);
        var headline = text(visible(notice.text()), false);
        headline.putClientProperty(FlatClientProperties.STYLE, "font: $semibold.font");
        body.add(headline);
        if (!notice.hint().isBlank()) { body.add(Box.createVerticalStrut(2)); body.add(text(notice.hint(), true)); }
        card.add(body, BorderLayout.CENTER);
        return card;
    }

    /**
     * A rounded panel with the theme's card color, a hairline border and an optional accent stripe on the left. Phase G8: it
     * can be clickable (hover tint, hand cursor, Enter or Space when focused) and selected (accent outline).
     */
    static class Card extends JPanel {
        private final Color accent;
        private boolean hover, selected;
        private Runnable click;
        Card(Color accent) { this.accent = accent; setOpaque(false); setBorder(padding(12, 14, 12, 14)); }
        @Override public Dimension getMaximumSize() { return new Dimension(Integer.MAX_VALUE, getPreferredSize().height); }

        /** Makes the whole card a button. */
        Card clickable(Runnable run) {
            this.click = run;
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setFocusable(true);
            var mouse = new MouseAdapter() {
                @Override public void mouseEntered(MouseEvent e) { hover = true; repaint(); }
                @Override public void mouseExited(MouseEvent e) { hover = isMouseInside(); repaint(); }
                @Override public void mouseClicked(MouseEvent e) { if (SwingUtilities.isLeftMouseButton(e) && isEnabled()) click.run(); }
            };
            addMouseListener(mouse);
            addKeyListener(new KeyAdapter() {
                @Override public void keyPressed(KeyEvent e) {
                    if ((e.getKeyCode() == KeyEvent.VK_ENTER || e.getKeyCode() == KeyEvent.VK_SPACE) && isEnabled()) click.run();
                }
            });
            addFocusListener(new FocusAdapter() {
                @Override public void focusGained(FocusEvent e) { repaint(); }
                @Override public void focusLost(FocusEvent e) { repaint(); }
            });
            for (var child : getComponents()) forward(child);
            return this;
        }

        /** Children (labels) take the mouse; hovering them still counts as hovering the card. */
        private boolean isMouseInside() {
            var point = MouseInfo.getPointerInfo() == null ? null : MouseInfo.getPointerInfo().getLocation();
            if (point == null || !isShowing()) return false;
            SwingUtilities.convertPointFromScreen(point, this);
            return contains(point);
        }

        void selected(boolean value) { selected = value; repaint(); }

        @Override protected void addImpl(Component component, Object constraints, int index) {
            super.addImpl(component, constraints, index);
            if (click != null) forward(component);
        }

        /** Everything inside a clickable card (labels, wrapped text) forwards hover and clicks to it. */
        private void forward(Component component) {
            if (!(component instanceof JComponent child) || component instanceof AbstractButton) return;
            child.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            child.addMouseListener(new MouseAdapter() {
                @Override public void mouseEntered(MouseEvent e) { hover = true; repaint(); }
                @Override public void mouseExited(MouseEvent e) { hover = isMouseInside(); repaint(); }
                @Override public void mouseClicked(MouseEvent e) { if (SwingUtilities.isLeftMouseButton(e) && isEnabled()) click.run(); }
            });
            for (var inner : child.getComponents()) forward(inner);
        }

        @Override protected void paintComponent(Graphics g) {
            var g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int w = getWidth() - 1, h = getHeight() - 1;
            g2.setColor(selected ? Theme.accentSoft() : hover ? Theme.blend(Theme.card(), Theme.hover(), Theme.dark() ? 0.7 : 0.6) : Theme.card());
            g2.fillRoundRect(0, 0, w, h, 14, 14);
            g2.setColor(selected || (click != null && isFocusOwner()) ? Theme.ACCENT : hover ? Theme.lineStrong() : Theme.line());
            g2.drawRoundRect(0, 0, w, h, 14, 14);
            if (accent != null) { g2.setColor(accent); g2.fillRoundRect(0, 10, 3, Math.max(8, getHeight() - 20), 3, 3); }
            g2.dispose();
            super.paintComponent(g);
        }
    }

    /** Values as one proportional bar in the given colors; a zero total paints the empty track. */
    static final class Bar extends JComponent {
        private final int[] values;
        private final Color[] colors;
        Bar(int[] values, Color[] colors, String tooltip) {
            this.values = values.clone(); this.colors = colors.clone();
            setPreferredSize(new Dimension(240, 10));
            setMinimumSize(new Dimension(60, 6));
            setToolTipText(tooltip);
        }
        @Override public Dimension getMaximumSize() { return new Dimension(Integer.MAX_VALUE, getPreferredSize().height); }
        @Override protected void paintComponent(Graphics g) {
            var g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int total = java.util.Arrays.stream(values).sum();
            var clip = new java.awt.geom.RoundRectangle2D.Double(0, 0, getWidth(), getHeight(), getHeight(), getHeight());
            g2.setColor(Theme.line());
            g2.fill(clip);
            if (total > 0) {
                g2.clip(clip);
                double x = 0;
                int shown = 0;
                for (int value : values) if (value > 0) shown++;
                // Phase G8: a hairline gap between segments, so neighboring levels stay apart.
                double gap = shown > 1 ? 2 : 0;
                double width = getWidth() - gap * (shown - 1);
                for (int i = 0; i < values.length; i++) {
                    if (values[i] == 0) continue;
                    double part = Math.max(2, width * values[i] / total);
                    g2.setColor(colors[i]);
                    g2.fill(new java.awt.geom.Rectangle2D.Double(x, 0, part, getHeight()));
                    x += part + gap;
                }
            }
            g2.dispose();
        }
    }

    /** Two columns of label and right-aligned value, as wide as the parent allows up to {@code maxWidth}. */
    static JPanel rows(int maxWidth) {
        var panel = new JPanel(new GridBagLayout()) {
            @Override public Dimension getMaximumSize() { return new Dimension(maxWidth, getPreferredSize().height); }
        };
        panel.setOpaque(false);
        return panel;
    }

    /** Adds one label/value line to a panel made by {@link #rows}; {@code icon} may be null. */
    static void row(JPanel rows, String label, String value, Icon icon) {
        var constraints = new GridBagConstraints();
        constraints.gridy = rows.getComponentCount() / 2;
        constraints.insets = new Insets(3, 0, 3, 0);
        constraints.anchor = GridBagConstraints.WEST;
        constraints.weightx = 1;
        constraints.fill = GridBagConstraints.HORIZONTAL;
        var name = new JLabel(label);
        name.setIcon(icon);
        name.setIconTextGap(8);
        rows.add(name, constraints);
        constraints = (GridBagConstraints) constraints.clone();
        constraints.weightx = 0;
        constraints.fill = GridBagConstraints.NONE;
        constraints.anchor = GridBagConstraints.EAST;
        constraints.insets = new Insets(3, 16, 3, 0);
        var number = new JLabel(value);
        number.putClientProperty(FlatClientProperties.STYLE, "font: $semibold.font");
        rows.add(number, constraints);
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

    /** Phase G8: a borderless button that looks like a link, for secondary actions inside text. */
    static JButton link(String text, Runnable run) {
        var button = new JButton(text);
        button.putClientProperty(FlatClientProperties.BUTTON_TYPE, FlatClientProperties.BUTTON_TYPE_BORDERLESS);
        button.putClientProperty(FlatClientProperties.STYLE, "foreground: $ClassRevive.accentForeground; margin: 2,4,2,4; font: $semibold.font");
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        button.addActionListener(e -> run.run());
        return button;
    }

    /**
     * A message dialog with an optional "details" area for the evidence behind it. Phase G8: the details (a stack trace, the
     * intake diagnostics) start folded behind a "Show details" toggle, so the plain message comes first.
     */
    static void error(Component parent, String title, String message, String details) {
        var panel = new JPanel(new BorderLayout(0, GAP));
        var text = new JLabel("<html><body style='width:380px'>" + escape(message) + "</body></html>");
        panel.add(text, BorderLayout.NORTH);
        if (details != null && !details.isBlank()) {
            var area = new JTextArea(details, Math.min(14, details.split("\n").length + 1), 64);
            area.setEditable(false);
            area.setFont(Theme.codeFont(12));
            area.setCaretPosition(0);
            var scroll = new JScrollPane(area);
            scroll.setVisible(false);
            var toggle = new JToggleButton(Text.t("error.showDetails"), Icons.of(Icons.Glyph.CHEVRON_DOWN, 12));
            toggle.putClientProperty(FlatClientProperties.BUTTON_TYPE, FlatClientProperties.BUTTON_TYPE_BORDERLESS);
            toggle.setHorizontalTextPosition(SwingConstants.LEFT);
            var copy = new JButton(Text.t("error.copyDetails"), Icons.of(Icons.Glyph.COPY, 13));
            copy.putClientProperty(FlatClientProperties.BUTTON_TYPE, FlatClientProperties.BUTTON_TYPE_BORDERLESS);
            copy.setVisible(false);
            copy.addActionListener(e -> Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new java.awt.datatransfer.StringSelection(details), null));
            toggle.addActionListener(e -> {
                scroll.setVisible(toggle.isSelected());
                copy.setVisible(toggle.isSelected());
                toggle.setText(Text.t(toggle.isSelected() ? "error.hideDetails" : "error.showDetails"));
                var window = SwingUtilities.getWindowAncestor(panel);
                if (window != null) window.pack();
            });
            var bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
            bar.add(toggle);
            bar.add(copy);
            var holder = new JPanel(new BorderLayout(0, 6));
            holder.add(bar, BorderLayout.NORTH);
            holder.add(scroll, BorderLayout.CENTER);
            panel.add(holder, BorderLayout.CENTER);
        }
        JOptionPane.showMessageDialog(parent, panel, title, JOptionPane.ERROR_MESSAGE);
    }

    static void info(Component parent, String title, String message) {
        JOptionPane.showMessageDialog(parent, "<html><body style='width:380px'>" + escape(message) + "</body></html>", title, JOptionPane.INFORMATION_MESSAGE);
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

    // ----- Phase G8: keycaps, the split button, the segmented control, motion -------------------------------------------

    /** A shortcut drawn as key caps: "Ctrl+Shift+F" becomes three small rounded keys. */
    static JComponent keycaps(String shortcut) {
        var row = new JPanel(new FlowLayout(FlowLayout.LEFT, 3, 0));
        row.setOpaque(false);
        for (String part : shortcut.split("(?<=[^+])\\+(?=.)")) row.add(new Keycap(part.strip()));
        return row;
    }

    /** One key cap. */
    static final class Keycap extends JLabel {
        Keycap(String key) {
            super(key);
            setBorder(padding(1, 6, 1, 6));
            setHorizontalAlignment(CENTER);
        }
        @Override public void updateUI() {
            super.updateUI();
            putClientProperty(FlatClientProperties.STYLE, "font: -1 $semibold.font; foreground: $ClassRevive.secondaryForeground");
        }
        @Override public Dimension getPreferredSize() {
            var size = super.getPreferredSize();
            return new Dimension(Math.max(size.width, size.height + 4), size.height + 2);
        }
        @Override protected void paintComponent(Graphics g) {
            var g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int w = getWidth() - 1, h = getHeight() - 2;
            g2.setColor(Theme.lineStrong());
            g2.fillRoundRect(0, 1, w, h + 1, 7, 7);
            g2.setColor(Theme.card());
            g2.fillRoundRect(1, 0, w - 2, h, 6, 6);
            g2.setColor(Theme.line());
            g2.drawRoundRect(0, 0, w, h, 7, 7);
            g2.dispose();
            super.paintComponent(g);
        }
    }

    /**
     * The main action with its options attached: one accent button, the left part runs {@code main}, the chevron opens
     * {@code menu}. Drawn as one rounded shape with a seam, hover and pressed per part.
     */
    static final class SplitButton extends JComponent {
        private final Action main;
        private final JPopupMenu menu;
        private int hover = -1, pressed = -1;
        private static final int ARROW = 26;

        SplitButton(Action main, JPopupMenu menu) {
            this.main = main;
            this.menu = menu;
            setFocusable(false);
            setToolTipText((String) main.getValue(Action.SHORT_DESCRIPTION));
            main.addPropertyChangeListener(e -> { if ("enabled".equals(e.getPropertyName())) { hover = -1; repaint(); } });
            var mouse = new MouseAdapter() {
                @Override public void mouseMoved(MouseEvent e) { int part = part(e); if (part != hover) { hover = part; repaint(); } }
                @Override public void mouseExited(MouseEvent e) { hover = -1; pressed = -1; repaint(); }
                @Override public void mousePressed(MouseEvent e) {
                    if (!main.isEnabled() || !SwingUtilities.isLeftMouseButton(e)) return;
                    pressed = part(e);
                    repaint();
                    if (pressed == 1) menu.show(SplitButton.this, 0, getHeight() + 2);
                }
                @Override public void mouseReleased(MouseEvent e) {
                    int was = pressed;
                    pressed = -1;
                    repaint();
                    if (was == 0 && part(e) == 0 && main.isEnabled()) main.actionPerformed(new ActionEvent(SplitButton.this, ActionEvent.ACTION_PERFORMED, "run"));
                }
            };
            addMouseListener(mouse);
            addMouseMotionListener(mouse);
        }

        private int part(MouseEvent e) { return e.getX() >= getWidth() - ARROW ? 1 : 0; }

        @Override public void updateUI() {
            super.updateUI();
            var font = UIManager.getFont("semibold.font");
            setFont(font != null ? font : UIManager.getFont("Button.font").deriveFont(Font.BOLD));
        }

        @Override public String getToolTipText(MouseEvent e) {
            return part(e) == 1 ? Text.t("toolbar.decompileOptions") : (String) main.getValue(Action.SHORT_DESCRIPTION);
        }

        @Override public Dimension getPreferredSize() {
            var metrics = getFontMetrics(getFont());
            int text = metrics.stringWidth((String) main.getValue(Action.NAME));
            return new Dimension(12 + 14 + 6 + text + 12 + ARROW, Math.max(28, metrics.getHeight() + 10));
        }
        @Override public Dimension getMaximumSize() { return getPreferredSize(); }
        @Override public Dimension getMinimumSize() { return getPreferredSize(); }

        @Override protected void paintComponent(Graphics g) {
            var g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB);
            boolean enabled = main.isEnabled();
            int w = getWidth(), h = getHeight(), split = w - ARROW;
            Color base = enabled ? Theme.ACCENT : Theme.hover();
            g2.setColor(base);
            g2.fillRoundRect(0, 0, w, h, 8, 8);
            if (enabled) {
                for (int part = 0; part < 2; part++) {
                    Color tint = pressed == part ? Theme.ACCENT_PRESSED : hover == part ? Theme.ACCENT_HOVER : null;
                    if (tint == null) continue;
                    var shape = new java.awt.geom.Area(new java.awt.geom.RoundRectangle2D.Double(0, 0, w, h, 8, 8));
                    shape.intersect(new java.awt.geom.Area(part == 0 ? new Rectangle(0, 0, split, h) : new Rectangle(split, 0, ARROW, h)));
                    g2.setColor(tint);
                    g2.fill(shape);
                }
            }
            Color ink = enabled ? Theme.ON_ACCENT : Theme.muted();
            g2.setColor(Theme.alpha(ink, enabled ? 70 : 90));
            g2.fillRect(split, 6, 1, h - 12);
            Icons.of(Icons.Glyph.PLAY, 14, ink).paintIcon(this, g2, 12, (h - 14) / 2);
            g2.setColor(ink);
            g2.setFont(getFont());
            var metrics = g2.getFontMetrics();
            g2.drawString((String) main.getValue(Action.NAME), 12 + 14 + 6, (h - metrics.getHeight()) / 2 + metrics.getAscent());
            Icons.of(Icons.Glyph.CHEVRON_DOWN, 12, ink).paintIcon(this, g2, split + (ARROW - 12) / 2, (h - 12) / 2);
            g2.dispose();
        }
    }

    /**
     * A row of toggle buttons where exactly one is on, for switching between views. {@code onChange} runs when the user (or
     * {@link #select}) picks another value.
     */
    static final class Segmented<T> extends JPanel {
        private final List<T> values = new ArrayList<>();
        private final List<JToggleButton> buttons = new ArrayList<>();
        private ButtonGroup group = new ButtonGroup();
        private final Consumer<T> onChange;
        private boolean adjusting;

        Segmented(Consumer<T> onChange) {
            super(new FlowLayout(FlowLayout.LEFT, 2, 0));
            this.onChange = onChange;
            setOpaque(false);
            setBorder(padding(2, 2, 2, 2));
        }

        /** Replaces the values; {@code label} and {@code tooltip} describe each; keeps no selection. */
        void values(List<T> list, java.util.function.Function<T,String> label, java.util.function.Function<T,String> tooltip) {
            removeAll();
            values.clear();
            buttons.clear();
            group = new ButtonGroup();
            for (T value : list) {
                var button = new JToggleButton(label.apply(value));
                button.putClientProperty(FlatClientProperties.STYLE, "arc: 6; margin: 2,10,2,10; borderWidth: 0; focusWidth: 0; innerFocusWidth: 0;"
                    + "background: null; toolbar.hoverBackground: $ClassRevive.hoverBackground; selectedBackground: $ClassRevive.cardBackground;"
                    + "selectedForeground: $Label.foreground; foreground: $ClassRevive.secondaryForeground; font: $semibold.font");
                button.putClientProperty(FlatClientProperties.BUTTON_TYPE, FlatClientProperties.BUTTON_TYPE_TOOLBAR_BUTTON);
                button.setFocusable(false);
                String tip = tooltip.apply(value);
                if (tip != null) button.setToolTipText(tip);
                button.addItemListener(e -> {
                    if (e.getStateChange() == java.awt.event.ItemEvent.SELECTED && !adjusting) onChange.accept(value);
                });
                group.add(button);
                buttons.add(button);
                values.add(value);
                add(button);
            }
            revalidate();
            repaint();
        }

        /** Selects {@code value} without telling {@code onChange} when {@code quiet}. */
        void select(T value, boolean quiet) {
            int index = values.indexOf(value);
            if (index < 0) return;
            adjusting = quiet;
            try { buttons.get(index).setSelected(true); } finally { adjusting = false; }
        }

        T selected() {
            for (int i = 0; i < buttons.size(); i++) if (buttons.get(i).isSelected()) return values.get(i);
            return null;
        }

        List<T> values() { return List.copyOf(values); }

        @Override protected void paintComponent(Graphics g) {
            var g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(Theme.dark() ? Theme.panel() : Theme.blend(Theme.panel(), Theme.hover(), 0.6));
            g2.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 9, 9);
            g2.setColor(Theme.line());
            g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 9, 9);
            g2.dispose();
        }
    }

    /**
     * Runs {@code step} with values from 0 to 1 over {@code millis} on the event thread (ease-out), then {@code done}.
     * Returns the timer, so a caller can stop an animation that a newer one replaces.
     */
    static Timer animate(int millis, DoubleConsumer step, Runnable done) {
        long start = System.nanoTime();
        var timer = new Timer(15, null);
        timer.addActionListener(e -> {
            double t = Math.min(1, (System.nanoTime() - start) / (millis * 1_000_000.0));
            step.accept(1 - Math.pow(1 - t, 3));
            if (t >= 1) { timer.stop(); if (done != null) done.run(); }
        });
        timer.setInitialDelay(0);
        timer.start();
        return timer;
    }
}
