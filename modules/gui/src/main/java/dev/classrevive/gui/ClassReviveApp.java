package dev.classrevive.gui;

import java.awt.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import javax.swing.*;

/** Phase G3: starts the desktop application ({@code classrevive gui [file]} or the {@code classrevive-gui} launcher). */
public final class ClassReviveApp {
    public static final String VERSION = "0.1.0-dev";

    private ClassReviveApp() {}

    public static void main(String[] args) {
        if (GraphicsEnvironment.isHeadless()) {
            System.err.println("ClassRevive desktop needs a graphical display. Use the command line instead: classrevive --help");
            System.exit(1);
        }
        launch(args.length > 0 ? Path.of(args[0]) : null);
    }

    /** Opens the main window on the event thread, then {@code file} when given; returns at once. */
    public static void launch(Path file) {
        var settings = GuiSettings.load();
        Text.use(settings.language());
        SwingUtilities.invokeLater(() -> {
            Theme.install(settings.theme());
            var window = new MainWindow(settings);
            window.setVisible(true);
            if (file != null) window.openPath(file.toAbsolutePath().normalize());
        });
    }

    /** The application logo at {@code size} pixels: a near-black rounded square with orange braces. */
    static Image logo(int size) {
        var image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        var g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.scale(size / 64.0, size / 64.0);
        g.setPaint(new GradientPaint(0, 0, new Color(0x26262B), 64, 64, new Color(0x0E0E10)));
        g.fill(new RoundRectangle2D.Double(2, 2, 60, 60, 18, 18));
        g.setColor(new Color(0x3A3A40));
        g.setStroke(new BasicStroke(1.5f));
        g.draw(new RoundRectangle2D.Double(2.75, 2.75, 58.5, 58.5, 17, 17));
        g.setPaint(new GradientPaint(0, 12, new Color(0xFFA54A), 0, 52, new Color(0xFF7A0F)));
        g.setStroke(new BasicStroke(5.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        var left = new Path2D.Double();
        left.moveTo(24, 15); left.curveTo(17, 15, 18, 22, 18, 26); left.curveTo(18, 30, 16, 32, 13, 32);
        left.curveTo(16, 32, 18, 34, 18, 38); left.curveTo(18, 42, 17, 49, 24, 49);
        g.draw(left);
        var right = new Path2D.Double();
        right.moveTo(40, 15); right.curveTo(47, 15, 46, 22, 46, 26); right.curveTo(46, 30, 48, 32, 51, 32);
        right.curveTo(48, 32, 46, 34, 46, 38); right.curveTo(46, 42, 47, 49, 40, 49);
        g.draw(right);
        g.fill(new Ellipse2D.Double(28.5, 28.5, 7, 7));
        g.dispose();
        return image;
    }

    /** A file of the installed distribution's {@code docs/} folder, or null when running from a build tree without it. */
    static Path installedDoc(String name) {
        try {
            Path jar = Path.of(ClassReviveApp.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            Path doc = jar.getParent() == null || jar.getParent().getParent() == null ? null : jar.getParent().getParent().resolve("docs").resolve(name);
            return doc != null && Files.isRegularFile(doc) ? doc : null;
        } catch (Exception unknown) {
            return null;
        }
    }
}
