package dev.classrevive.gui;

import java.awt.*;
import java.awt.geom.*;
import javax.swing.Icon;

/**
 * Phase G3: the application's icons, drawn as vectors so they stay sharp at any display scale and follow the theme's text
 * color. No image files and no icon library.
 */
final class Icons {
    enum Glyph { OPEN, FOLDER_RESULT, PLAY, STOP, SEARCH, GOTO, EXPORT, SETTINGS, PACKAGE, RESOURCE, FOLDER, REPORT, BACK, FORWARD, COMPARE, SHIELD }

    private Icons() {}

    static Icon of(Glyph glyph) { return new GlyphIcon(glyph, 16, null); }
    static Icon of(Glyph glyph, int size) { return new GlyphIcon(glyph, size, null); }
    static Icon of(Glyph glyph, int size, Color color) { return new GlyphIcon(glyph, size, color); }

    /** A filled circle, for confidence levels. */
    static Icon dot(Color color, int size) {
        return new Icon() {
            @Override public void paintIcon(Component c, Graphics g, int x, int y) {
                var g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(color);
                g2.fill(new Ellipse2D.Double(x + 1, y + 1, size - 2, size - 2));
                g2.dispose();
            }
            @Override public int getIconWidth() { return size; }
            @Override public int getIconHeight() { return size; }
        };
    }

    /** A class kind badge: a rounded square with C, I, E, R or @, and an optional confidence dot at its corner. */
    static Icon kind(char letter, Color color, Color levelDot) {
        return new Icon() {
            @Override public void paintIcon(Component c, Graphics g, int x, int y) {
                var g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                g2.setColor(color);
                g2.fill(new RoundRectangle2D.Double(x + 1, y + 1, 14, 14, 5, 5));
                g2.setColor(Color.WHITE);
                g2.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 10));
                var metrics = g2.getFontMetrics();
                String text = String.valueOf(letter);
                g2.drawString(text, x + 8 - metrics.stringWidth(text) / 2f, y + 8 + (metrics.getAscent() - metrics.getDescent()) / 2f);
                if (levelDot != null) {
                    g2.setColor(Theme.panel());
                    g2.fill(new Ellipse2D.Double(x + 10, y + 10, 8, 8));
                    g2.setColor(levelDot);
                    g2.fill(new Ellipse2D.Double(x + 11, y + 11, 6, 6));
                }
                g2.dispose();
            }
            @Override public int getIconWidth() { return 18; }
            @Override public int getIconHeight() { return 18; }
        };
    }

    private record GlyphIcon(Glyph glyph, int size, Color fixed) implements Icon {
        @Override public int getIconWidth() { return size; }
        @Override public int getIconHeight() { return size; }

        @Override public void paintIcon(Component c, Graphics g, int x, int y) {
            var g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            g2.translate(x, y);
            g2.scale(size / 16.0, size / 16.0);
            Color color = fixed != null ? fixed : c != null && !c.isEnabled() ? Theme.muted() : Theme.foreground();
            g2.setColor(color);
            g2.setStroke(new BasicStroke(1.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            switch (glyph) {
                case OPEN -> {
                    var path = new Path2D.Double();
                    path.moveTo(1.5, 4); path.lineTo(6, 4); path.lineTo(7.5, 5.5); path.lineTo(13.5, 5.5); path.lineTo(13.5, 7.5);
                    g2.draw(path);
                    var front = new Path2D.Double();
                    front.moveTo(1.5, 4); front.lineTo(1.5, 13); front.lineTo(12.5, 13); front.lineTo(14.8, 7.5); front.lineTo(4, 7.5); front.lineTo(1.5, 13);
                    g2.draw(front);
                }
                case FOLDER_RESULT, FOLDER -> {
                    var path = new Path2D.Double();
                    path.moveTo(1.5, 3.5); path.lineTo(6, 3.5); path.lineTo(7.5, 5); path.lineTo(14.5, 5); path.lineTo(14.5, 13); path.lineTo(1.5, 13); path.closePath();
                    g2.draw(path);
                    if (glyph == Glyph.FOLDER_RESULT) { g2.draw(new Line2D.Double(5, 9.2, 7, 11)); g2.draw(new Line2D.Double(7, 11, 11, 7)); }
                }
                case PLAY -> {
                    var path = new Path2D.Double();
                    path.moveTo(4.5, 2.5); path.lineTo(13, 8); path.lineTo(4.5, 13.5); path.closePath();
                    g2.fill(path);
                }
                case STOP -> g2.fill(new RoundRectangle2D.Double(3.5, 3.5, 9, 9, 2.5, 2.5));
                case SEARCH -> {
                    g2.draw(new Ellipse2D.Double(2.5, 2.5, 8, 8));
                    g2.setStroke(new BasicStroke(1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    g2.draw(new Line2D.Double(9.4, 9.4, 13.5, 13.5));
                }
                case GOTO -> {
                    g2.draw(new RoundRectangle2D.Double(2, 2, 12, 12, 3, 3));
                    g2.draw(new Line2D.Double(5, 8, 11, 8));
                    g2.draw(new Line2D.Double(8.5, 5.5, 11, 8));
                    g2.draw(new Line2D.Double(8.5, 10.5, 11, 8));
                }
                case EXPORT -> {
                    g2.draw(new Line2D.Double(8, 2, 8, 10));
                    g2.draw(new Line2D.Double(5, 5, 8, 2));
                    g2.draw(new Line2D.Double(11, 5, 8, 2));
                    var tray = new Path2D.Double();
                    tray.moveTo(2.5, 9); tray.lineTo(2.5, 13.5); tray.lineTo(13.5, 13.5); tray.lineTo(13.5, 9);
                    g2.draw(tray);
                }
                case SETTINGS -> {
                    for (int i = 0; i < 8; i++) {
                        double angle = Math.PI / 4 * i;
                        g2.draw(new Line2D.Double(8 + Math.cos(angle) * 4.6, 8 + Math.sin(angle) * 4.6, 8 + Math.cos(angle) * 6.6, 8 + Math.sin(angle) * 6.6));
                    }
                    g2.draw(new Ellipse2D.Double(3.6, 3.6, 8.8, 8.8));
                    g2.draw(new Ellipse2D.Double(6, 6, 4, 4));
                }
                case PACKAGE -> {
                    var box = new Path2D.Double();
                    box.moveTo(8, 1.8); box.lineTo(14, 4.8); box.lineTo(14, 11.2); box.lineTo(8, 14.2); box.lineTo(2, 11.2); box.lineTo(2, 4.8); box.closePath();
                    g2.draw(box);
                    g2.draw(new Line2D.Double(2, 4.8, 8, 7.8)); g2.draw(new Line2D.Double(14, 4.8, 8, 7.8)); g2.draw(new Line2D.Double(8, 7.8, 8, 14.2));
                }
                case RESOURCE, REPORT -> {
                    var page = new Path2D.Double();
                    page.moveTo(3.5, 1.5); page.lineTo(9.5, 1.5); page.lineTo(12.5, 4.5); page.lineTo(12.5, 14.5); page.lineTo(3.5, 14.5); page.closePath();
                    g2.draw(page);
                    if (glyph == Glyph.REPORT) {
                        g2.fill(new Rectangle2D.Double(5.5, 10, 1.6, 2.8)); g2.fill(new Rectangle2D.Double(7.7, 8, 1.6, 4.8)); g2.fill(new Rectangle2D.Double(9.9, 6, 1.6, 6.8));
                    } else {
                        g2.draw(new Line2D.Double(5.5, 8, 10.5, 8)); g2.draw(new Line2D.Double(5.5, 10.5, 10.5, 10.5));
                    }
                }
                case BACK -> {
                    g2.draw(new Line2D.Double(13, 8, 3.5, 8));
                    g2.draw(new Line2D.Double(7, 4.5, 3.5, 8));
                    g2.draw(new Line2D.Double(7, 11.5, 3.5, 8));
                }
                case FORWARD -> {
                    g2.draw(new Line2D.Double(3, 8, 12.5, 8));
                    g2.draw(new Line2D.Double(9, 4.5, 12.5, 8));
                    g2.draw(new Line2D.Double(9, 11.5, 12.5, 8));
                }
                case SHIELD -> {
                    var shield = new Path2D.Double();
                    shield.moveTo(8, 1.5); shield.lineTo(13.5, 3.5); shield.lineTo(13.5, 8); shield.curveTo(13.5, 11.5, 11, 13.5, 8, 14.8);
                    shield.curveTo(5, 13.5, 2.5, 11.5, 2.5, 8); shield.lineTo(2.5, 3.5); shield.closePath();
                    g2.draw(shield);
                    g2.draw(new Line2D.Double(8, 5, 8, 9)); g2.fill(new Ellipse2D.Double(7.2, 10.4, 1.6, 1.6));
                }
                case COMPARE -> {
                    g2.draw(new RoundRectangle2D.Double(1.5, 2.5, 5.5, 11, 2, 2));
                    g2.draw(new RoundRectangle2D.Double(9, 2.5, 5.5, 11, 2, 2));
                    g2.draw(new Line2D.Double(3, 6, 5.5, 6)); g2.draw(new Line2D.Double(10.5, 6, 13, 6));
                    g2.draw(new Line2D.Double(3, 9.5, 5.5, 9.5)); g2.draw(new Line2D.Double(10.5, 10, 13, 10));
                }
            }
            g2.dispose();
        }
    }
}
