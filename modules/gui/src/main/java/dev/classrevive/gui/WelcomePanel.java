package dev.classrevive.gui;

import java.awt.*;
import java.awt.event.*;
import java.nio.file.*;
import java.util.function.Consumer;
import javax.swing.*;

/** Phase G3: the first screen: open a file or a result, reopen a recent one, or drop a file anywhere on the window. */
final class WelcomePanel extends JPanel {
    private final JPanel recent = new JPanel();
    private final GuiSettings settings;
    private final Consumer<Path> open;

    WelcomePanel(GuiSettings settings, Action openFile, Action openResult, Consumer<Path> open) {
        super(new GridBagLayout());
        this.settings = settings;
        this.open = open;
        var column = new JPanel();
        column.setOpaque(false);
        column.setLayout(new BoxLayout(column, BoxLayout.Y_AXIS));

        var logo = new JLabel(new ImageIcon(ClassReviveApp.logo(56)));
        column.add(center(logo));
        column.add(Box.createVerticalStrut(12));
        column.add(center(Ui.heading("ClassRevive", 28f)));
        column.add(Box.createVerticalStrut(4));
        column.add(center(Ui.muted(Text.t("welcome.tagline"))));
        column.add(Box.createVerticalStrut(24));

        var buttons = new JPanel(new FlowLayout(FlowLayout.CENTER, 10, 0));
        buttons.setOpaque(false);
        var primary = Ui.primary(openFile);
        primary.setMargin(new Insets(8, 16, 8, 16));
        var secondary = new JButton(openResult);
        secondary.setMargin(new Insets(8, 16, 8, 16));
        buttons.add(primary);
        buttons.add(secondary);
        column.add(center(buttons));
        column.add(Box.createVerticalStrut(18));
        column.add(center(new DropHint()));
        column.add(Box.createVerticalStrut(26));

        recent.setOpaque(false);
        recent.setLayout(new BoxLayout(recent, BoxLayout.Y_AXIS));
        column.add(center(recent));
        column.add(Box.createVerticalStrut(24));
        column.add(center(Ui.muted(Text.t("welcome.safety"))));
        add(column);
        refresh();
    }

    /** Rebuilds the recent list from the settings. */
    void refresh() {
        recent.removeAll();
        var items = settings.recent();
        if (!items.isEmpty()) {
            var title = Ui.muted(Text.t("welcome.recent").toUpperCase(Text.locale()));
            title.setFont(title.getFont().deriveFont(Font.BOLD, title.getFont().getSize2D() - 1.5f));
            title.setAlignmentX(LEFT_ALIGNMENT);
            recent.add(title);
            recent.add(Box.createVerticalStrut(6));
        }
        for (Path path : items.stream().limit(8).toList()) {
            boolean folder = Files.isDirectory(path);
            var link = new JButton("<html><nobr><b>" + Ui.escape(path.getFileName() == null ? path.toString() : path.getFileName().toString())
                + "</b> &nbsp;<span style='color:gray'>" + Ui.escape(shorten(path.getParent() == null ? "" : path.getParent().toString(), 64))
                + "</span></nobr></html>");
            link.setIcon(Icons.of(folder ? Icons.Glyph.FOLDER_RESULT : Icons.Glyph.RESOURCE, 14));
            link.putClientProperty(com.formdev.flatlaf.FlatClientProperties.BUTTON_TYPE, com.formdev.flatlaf.FlatClientProperties.BUTTON_TYPE_TOOLBAR_BUTTON);
            link.setHorizontalAlignment(SwingConstants.LEFT);
            link.setAlignmentX(LEFT_ALIGNMENT);
            link.setMaximumSize(new Dimension(560, link.getPreferredSize().height));
            link.setToolTipText(path.toString());
            link.addActionListener(e -> open.accept(path));
            recent.add(link);
        }
        recent.revalidate();
        recent.repaint();
    }

    /** Keeps both ends of a long path, with an ellipsis in the middle. */
    static String shorten(String path, int max) {
        if (path.length() <= max) return path;
        int keep = (max - 1) / 2;
        return path.substring(0, keep) + "…" + path.substring(path.length() - (max - 1 - keep));
    }

    private static JComponent center(JComponent component) {
        component.setAlignmentX(CENTER_ALIGNMENT);
        return component;
    }

    /** A dashed drop zone hint. */
    private static final class DropHint extends JComponent {
        DropHint() { setPreferredSize(new Dimension(520, 64)); setMaximumSize(new Dimension(520, 64)); }
        @Override protected void paintComponent(Graphics g) {
            var g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g2.setColor(Theme.line());
            g2.setStroke(new BasicStroke(1.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 1f, new float[] {6f, 5f}, 0f));
            g2.drawRoundRect(1, 1, getWidth() - 3, getHeight() - 3, 14, 14);
            g2.setColor(Theme.muted());
            g2.setFont(UIManager.getFont("Label.font"));
            String text = Text.t("welcome.drop");
            var metrics = g2.getFontMetrics();
            g2.drawString(text, (getWidth() - metrics.stringWidth(text)) / 2, getHeight() / 2 + metrics.getAscent() / 2 - 2);
            g2.dispose();
        }
    }
}
