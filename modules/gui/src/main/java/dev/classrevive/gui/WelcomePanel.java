package dev.classrevive.gui;

import com.formdev.flatlaf.FlatClientProperties;
import java.awt.*;
import java.awt.event.*;
import java.nio.file.*;
import java.util.function.Consumer;
import javax.swing.*;

/**
 * Phase G3, redesigned in G8: the first screen. Two cards say what can be opened (a program or a saved result), a drop
 * zone lights up while a file is dragged over the window, and below come the recent files or, on a first run, the three
 * steps of how ClassRevive works.
 */
final class WelcomePanel extends JPanel {
    static final int COLUMN = 760, MAX_RECENT = 8;

    private final JPanel lower = new JPanel(new BorderLayout());
    private final GuiSettings settings;
    private final Consumer<Path> open;
    private final Runnable clearRecent;
    private final DropZone drop = new DropZone();
    private JScrollPane scroller;

    WelcomePanel(GuiSettings settings, Action openFile, Action openResult, Consumer<Path> open, Runnable clearRecent) {
        super(new GridBagLayout());
        this.settings = settings;
        this.open = open;
        this.clearRecent = clearRecent;
        var column = new JPanel() {
            @Override public Dimension getPreferredSize() { var size = super.getPreferredSize(); return new Dimension(COLUMN, size.height); }
            @Override public Dimension getMaximumSize() { return getPreferredSize(); }
            // Wrapped text has no sensible minimum before it knows its width; the column never shrinks below its own size.
            @Override public Dimension getMinimumSize() { return getPreferredSize(); }
        };
        column.setOpaque(false);
        column.setLayout(new BoxLayout(column, BoxLayout.Y_AXIS));

        var brand = new JPanel(new BorderLayout(16, 0));
        brand.setOpaque(false);
        brand.add(new JLabel(new ImageIcon(ClassReviveApp.logo(56))), BorderLayout.WEST);
        var names = new JPanel(new GridLayout(2, 1, 0, 2));
        names.setOpaque(false);
        names.add(Ui.styled("ClassRevive", "h1"));
        names.add(Ui.muted(Text.t("welcome.tagline")));
        brand.add(names, BorderLayout.CENTER);
        column.add(left(brand));
        column.add(Box.createVerticalStrut(28));

        var actions = new JPanel(new GridLayout(1, 2, 14, 0));
        actions.setOpaque(false);
        actions.add(actionCard(Icons.Glyph.OPEN, Text.t("welcome.openFile"), Text.t("welcome.openFile.note"), "ctrl O", openFile, true));
        actions.add(actionCard(Icons.Glyph.FOLDER_RESULT, Text.t("welcome.openResult"), Text.t("welcome.openResult.note"), "ctrl shift O", openResult, false));
        column.add(left(actions));
        column.add(Box.createVerticalStrut(12));
        column.add(left(drop));
        column.add(Box.createVerticalStrut(30));

        lower.setOpaque(false);
        column.add(left(lower));
        column.add(Box.createVerticalStrut(26));

        var safety = Ui.muted(Text.t("welcome.safety"));
        safety.setIcon(Icons.of(Icons.Glyph.SHIELD, 14, Theme.ACCENT));
        safety.setIconTextGap(8);
        var footer = new JPanel(new BorderLayout());
        footer.setOpaque(false);
        footer.add(safety, BorderLayout.WEST);
        footer.add(Ui.faint(Text.t("welcome.version", ClassReviveApp.VERSION)), BorderLayout.EAST);
        column.add(left(footer));

        var at = new GridBagConstraints();
        at.insets = new Insets(40, 24, 40, 24);
        add(column, at);
        refresh();
    }

    @Override public void updateUI() { super.updateUI(); setBackground(Theme.editor()); }

    /** This screen in a borderless scroll pane, so it stays usable in a short window. */
    JScrollPane scroller() {
        if (scroller == null) {
            scroller = new JScrollPane(this);
            scroller.setBorder(BorderFactory.createEmptyBorder());
            scroller.getVerticalScrollBar().setUnitIncrement(16);
        }
        return scroller;
    }

    /** Lights the drop zone while a file is dragged over the window. */
    void dragging(boolean on) { drop.highlight(on); }

    /** Rebuilds the lower part from the settings: the recent files, or the three steps when there are none. */
    void refresh() {
        lower.removeAll();
        var items = settings.recent();
        lower.add(items.isEmpty() ? steps() : recent(items.stream().limit(MAX_RECENT).toList()), BorderLayout.CENTER);
        lower.revalidate();
        lower.repaint();
    }

    private JComponent recent(java.util.List<Path> items) {
        var box = new JPanel();
        box.setOpaque(false);
        box.setLayout(new BoxLayout(box, BoxLayout.Y_AXIS));
        var header = new JPanel(new BorderLayout());
        header.setOpaque(false);
        header.add(Ui.caption(Text.t("welcome.recent")), BorderLayout.WEST);
        var clear = Ui.link(Text.t("welcome.clearRecent"), clearRecent);
        clear.putClientProperty(FlatClientProperties.STYLE, "foreground: $ClassRevive.secondaryForeground; margin: 0,4,0,4; font: -1");
        header.add(clear, BorderLayout.EAST);
        box.add(left(header));
        box.add(Box.createVerticalStrut(6));
        for (Path path : items) box.add(left(new RecentRow(path)));
        return box;
    }

    /** First run: what happens, in three steps. */
    private static JComponent steps() {
        var box = new JPanel(new BorderLayout(0, 10));
        box.setOpaque(false);
        box.add(Ui.caption(Text.t("welcome.how")), BorderLayout.NORTH);
        var row = new JPanel(new GridLayout(1, 3, 12, 0));
        row.setOpaque(false);
        for (int step = 1; step <= 3; step++) {
            var card = new Ui.Card(null);
            card.setLayout(new BorderLayout(0, 6));
            card.setBorder(Ui.padding(14, 16, 14, 16));
            var title = Ui.styled(Text.t("welcome.step" + step), "semibold");
            title.setIcon(new StepNumber(step));
            title.setIconTextGap(10);
            card.add(title, BorderLayout.NORTH);
            card.add(Ui.text(Text.t("welcome.step" + step + ".note"), true), BorderLayout.CENTER);
            row.add(card);
        }
        box.add(row, BorderLayout.CENTER);
        return box;
    }

    private static JComponent actionCard(Icons.Glyph glyph, String title, String note, String shortcut, Action action, boolean primary) {
        var card = new Ui.Card(null).clickable(() -> { if (action.isEnabled()) action.actionPerformed(new ActionEvent(action, ActionEvent.ACTION_PERFORMED, "open")); });
        card.setLayout(new BorderLayout(14, 0));
        card.setBorder(Ui.padding(16, 16, 16, 16));
        card.add(new JLabel(new Badge(glyph, primary)), BorderLayout.WEST);
        var text = new JPanel(new BorderLayout(0, 3));
        text.setOpaque(false);
        var heading = new JPanel(new BorderLayout(8, 0));
        heading.setOpaque(false);
        heading.add(Ui.styled(title, "h4"), BorderLayout.CENTER);
        heading.add(Ui.keycaps(Ui.shortcut(shortcut)), BorderLayout.EAST);
        text.add(heading, BorderLayout.NORTH);
        text.add(Ui.text(note, true), BorderLayout.CENTER);
        card.add(text, BorderLayout.CENTER);
        card.setToolTipText((String) action.getValue(Action.SHORT_DESCRIPTION));
        return card;
    }

    private static JComponent left(JComponent component) { component.setAlignmentX(LEFT_ALIGNMENT); return component; }

    /** Keeps both ends of a long path, with an ellipsis in the middle. */
    static String shorten(String path, int max) {
        if (path.length() <= max) return path;
        int keep = (max - 1) / 2;
        return path.substring(0, keep) + "…" + path.substring(path.length() - (max - 1 - keep));
    }

    /** One recent file or result: icon, name, folder, and a tag for results and for files that are gone. */
    private final class RecentRow extends JPanel {
        private boolean hover;

        RecentRow(Path path) {
            super(new BorderLayout(10, 0));
            setOpaque(false);
            setBorder(Ui.padding(7, 10, 7, 10));
            boolean folder = Files.isDirectory(path), missing = !Files.exists(path);
            var icon = new JLabel(Icons.of(folder ? Icons.Glyph.FOLDER_RESULT : Icons.Glyph.RESOURCE, 16, missing ? Theme.muted() : null));
            add(icon, BorderLayout.WEST);
            var name = new JLabel(Ui.visible(path.getFileName() == null ? path.toString() : path.getFileName().toString()));
            name.putClientProperty(FlatClientProperties.STYLE, missing ? "foreground: $ClassRevive.mutedForeground" : "font: $semibold.font");
            var where = Ui.muted(Ui.visible(shorten(path.getParent() == null ? "" : path.getParent().toString(), 70)));
            var text = new JPanel(new BorderLayout(10, 0));
            text.setOpaque(false);
            text.add(name, BorderLayout.WEST);
            text.add(where, BorderLayout.CENTER);
            add(text, BorderLayout.CENTER);
            if (missing) add(Ui.softChip(Text.t("welcome.missing"), Theme.muted()), BorderLayout.EAST);
            else if (folder) add(Ui.softChip(Text.t("welcome.result"), Theme.ACCENT), BorderLayout.EAST);
            setToolTipText(path.toString());
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            var mouse = new MouseAdapter() {
                @Override public void mouseEntered(MouseEvent e) { hover = true; repaint(); }
                @Override public void mouseExited(MouseEvent e) {
                    var point = SwingUtilities.convertPoint((Component) e.getSource(), e.getPoint(), RecentRow.this);
                    hover = contains(point); repaint();
                }
                @Override public void mouseClicked(MouseEvent e) { if (SwingUtilities.isLeftMouseButton(e)) open.accept(path); }
            };
            addMouseListener(mouse);
            for (var child : new Component[] {icon, name, where}) child.addMouseListener(mouse);
        }

        @Override public Dimension getMaximumSize() { return new Dimension(Integer.MAX_VALUE, getPreferredSize().height); }

        @Override protected void paintComponent(Graphics g) {
            if (!hover) return;
            var g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(Theme.hover());
            g2.fillRoundRect(0, 0, getWidth(), getHeight(), 10, 10);
            g2.dispose();
        }
    }

    /** The glyph of an action card in a rounded square: accent tint for the main action, neutral for the other. */
    private record Badge(Icons.Glyph glyph, boolean primary) implements Icon {
        @Override public int getIconWidth() { return 40; }
        @Override public int getIconHeight() { return 40; }
        @Override public void paintIcon(Component c, Graphics g, int x, int y) {
            var g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(primary ? Theme.accentSoft() : Theme.hover());
            g2.fillRoundRect(x, y, 40, 40, 12, 12);
            Icons.of(glyph, 20, primary ? Theme.ACCENT : Theme.foreground()).paintIcon(c, g2, x + 10, y + 10);
            g2.dispose();
        }
    }

    /** A step number in a small circle. */
    private record StepNumber(int number) implements Icon {
        @Override public int getIconWidth() { return 22; }
        @Override public int getIconHeight() { return 22; }
        @Override public void paintIcon(Component c, Graphics g, int x, int y) {
            var g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g2.setColor(Theme.accentSoft());
            g2.fillOval(x, y, 22, 22);
            g2.setColor(Theme.accentText());
            g2.setFont(c.getFont().deriveFont(Font.BOLD, 12f));
            var metrics = g2.getFontMetrics();
            String text = String.valueOf(number);
            g2.drawString(text, x + 11 - metrics.stringWidth(text) / 2f, y + 11 + (metrics.getAscent() - metrics.getDescent()) / 2f);
            g2.dispose();
        }
    }

    /** A dashed drop zone; while a file is dragged over the window it fades to the accent. */
    private static final class DropZone extends JComponent {
        private double glow;
        private Timer fade;

        DropZone() { setPreferredSize(new Dimension(COLUMN, 60)); setMaximumSize(new Dimension(Integer.MAX_VALUE, 60)); }

        void highlight(boolean on) {
            double from = glow, to = on ? 1 : 0;
            if (from == to) return;
            if (fade != null) fade.stop();
            fade = Ui.animate(160, t -> { glow = from + (to - from) * t; repaint(); }, null);
        }

        @Override protected void paintComponent(Graphics g) {
            var g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            if (glow > 0) {
                g2.setColor(Theme.alpha(Theme.accentSoft(), (int) (255 * glow)));
                g2.fillRoundRect(1, 1, getWidth() - 3, getHeight() - 3, 14, 14);
            }
            g2.setColor(Theme.blend(Theme.lineStrong(), Theme.ACCENT, glow));
            g2.setStroke(new BasicStroke(1.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 1f, new float[] {6f, 5f}, 0f));
            g2.drawRoundRect(1, 1, getWidth() - 3, getHeight() - 3, 14, 14);
            String text = Text.t(glow > 0.5 ? "welcome.dropNow" : "welcome.drop");
            g2.setFont(UIManager.getFont("Label.font"));
            var metrics = g2.getFontMetrics();
            var icon = Icons.of(Icons.Glyph.EXPORT, 16, Theme.blend(Theme.secondary(), Theme.ACCENT, glow));
            int width = 16 + 8 + metrics.stringWidth(text), x = (getWidth() - width) / 2;
            // The export glyph turned upside down reads as "drop in".
            var rotated = (Graphics2D) g2.create();
            rotated.rotate(Math.PI, x + 8, getHeight() / 2.0);
            icon.paintIcon(this, rotated, x, getHeight() / 2 - 8);
            rotated.dispose();
            g2.setColor(Theme.blend(Theme.secondary(), Theme.accentText(), glow));
            g2.drawString(text, x + 24, getHeight() / 2 + metrics.getAscent() / 2 - 2);
            g2.dispose();
        }
    }
}
