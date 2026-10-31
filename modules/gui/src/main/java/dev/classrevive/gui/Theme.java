package dev.classrevive.gui;

import com.formdev.flatlaf.*;
import dev.classrevive.workbench.ResultIndex;
import java.awt.*;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Phase G3, recolored in G4: FlatLaf with the ClassRevive look. The dark theme (the default) is near-black with orange
 * accents; the light theme keeps the same orange. Confidence colors stay clear of orange so they never read as the accent.
 */
final class Theme {
    static final Color ACCENT = new Color(0xFF8A1F);
    /** Text and icons drawn on the accent: dark, for contrast on orange. */
    static final Color ON_ACCENT = new Color(0x141416);
    private static final Map<String,String> SHARED = Map.ofEntries(
        Map.entry("@accentColor", "#FF8A1F"), Map.entry("Component.arc", "8"), Map.entry("Button.arc", "8"), Map.entry("TextComponent.arc", "8"),
        Map.entry("ScrollBar.thumbArc", "999"), Map.entry("ScrollBar.thumbInsets", "2,2,2,2"), Map.entry("ScrollBar.width", "11"),
        Map.entry("Tree.rowHeight", "24"), Map.entry("TabbedPane.tabHeight", "32"), Map.entry("TitlePane.unifiedBackground", "true"),
        Map.entry("SplitPaneDivider.style", "plain"), Map.entry("Component.focusColor", "#FF8A1F"));
    /** Near-black surfaces, light gray text, orange focus and a dark-orange selection. */
    private static final Map<String,String> DARK = Map.ofEntries(
        Map.entry("@background", "#141416"), Map.entry("@foreground", "#E4E4E7"), Map.entry("@selectionBackground", "#7A3A08"),
        Map.entry("@selectionForeground", "#FFFFFF"), Map.entry("@selectionInactiveBackground", "#2A2A2E"),
        Map.entry("Component.borderColor", "#2A2A2E"), Map.entry("Separator.foreground", "#2A2A2E"),
        Map.entry("TabbedPane.underlineColor", "#FF8A1F"), Map.entry("TabbedPane.hoverColor", "#1F1F23"),
        Map.entry("ToolBar.background", "#0E0E10"), Map.entry("MenuBar.background", "#0E0E10"), Map.entry("TitlePane.background", "#0E0E10"),
        Map.entry("Tree.background", "#111113"), Map.entry("List.background", "#111113"), Map.entry("TextField.background", "#1B1B1E"),
        Map.entry("ComboBox.background", "#1B1B1E"), Map.entry("Spinner.background", "#1B1B1E"), Map.entry("Button.background", "#1F1F23"),
        Map.entry("ProgressBar.foreground", "#FF8A1F"), Map.entry("CheckBox.icon.checkmarkColor", "#141416"));
    private static final Map<String,String> LIGHT = Map.ofEntries(
        Map.entry("@selectionBackground", "#FFD7B0"), Map.entry("@selectionForeground", "#141416"), Map.entry("TabbedPane.underlineColor", "#E8720C"),
        Map.entry("ProgressBar.foreground", "#E8720C"));

    private Theme() {}

    /** Installs the look and feel; call before any window exists. */
    static void install(GuiSettings.ThemeChoice choice) {
        boolean dark = dark(choice);
        var defaults = new java.util.HashMap<>(SHARED);
        defaults.putAll(dark ? DARK : LIGHT);
        FlatLaf.setGlobalExtraDefaults(defaults);
        if (dark) FlatDarkLaf.setup(); else FlatLightLaf.setup();
    }

    /** Switches every open window to {@code choice}. */
    static void switchTo(GuiSettings.ThemeChoice choice) {
        install(choice);
        FlatLaf.updateUI();
    }

    static boolean dark() { return FlatLaf.isLafDark(); }

    static boolean dark(GuiSettings.ThemeChoice choice) {
        return switch (choice) { case DARK -> true; case LIGHT -> false; case SYSTEM -> windowsAppsUseDarkTheme(); };
    }

    /** Windows' "app mode" setting, read with {@code reg query}; false elsewhere or when it cannot be read. */
    static boolean windowsAppsUseDarkTheme() {
        if (!System.getProperty("os.name", "").startsWith("Windows")) return false;
        try {
            var process = new ProcessBuilder("reg", "query", "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize",
                "/v", "AppsUseLightTheme").redirectErrorStream(true).start();
            process.getOutputStream().close();
            String text = new String(process.getInputStream().readNBytes(4096), StandardCharsets.UTF_8);
            if (!process.waitFor(2, TimeUnit.SECONDS)) { process.destroyForcibly(); return false; }
            return text.matches("(?s).*AppsUseLightTheme\\s+REG_DWORD\\s+0x0\\b.*");
        } catch (Exception unavailable) {
            return false;
        }
    }

    /** One color per confidence level, readable on both themes; null (not decompiled yet) is neutral. */
    static Color level(ResultIndex.Level level) {
        if (level == null) return new Color(0x8A8A93);
        return switch (level) {
            case EXACT -> new Color(0x3FB950);
            case CLOSE -> new Color(0x58A6FF);
            case PER_FILE -> new Color(0xD7B53C);
            case PARTIAL -> new Color(0xB980F0);
            case UNVERIFIED -> new Color(0xF0605A);
        };
    }

    /** Black or white, whichever reads better on {@code background}. */
    static Color textOn(Color background) {
        double luminance = (0.299 * background.getRed() + 0.587 * background.getGreen() + 0.114 * background.getBlue()) / 255;
        return luminance > 0.6 ? ON_ACCENT : Color.WHITE;
    }

    static Color muted() { return color("Label.disabledForeground", Color.GRAY); }
    static Color foreground() { return color("Label.foreground", Color.DARK_GRAY); }
    static Color panel() { return color("Panel.background", Color.WHITE); }
    static Color card() { return dark() ? new Color(0x1A1A1D) : Color.WHITE; }
    /** The code editor's background: darker than the panels in the dark theme. */
    static Color editor() { return dark() ? new Color(0x0C0C0E) : Color.WHITE; }
    static Color line() { return color("Component.borderColor", Color.LIGHT_GRAY); }

    static Color color(String key, Color fallback) {
        Color color = javax.swing.UIManager.getColor(key);
        return color == null ? fallback : color;
    }

    static Color blend(Color a, Color b, double amount) {
        return new Color((int) Math.round(a.getRed() + (b.getRed() - a.getRed()) * amount), (int) Math.round(a.getGreen() + (b.getGreen() - a.getGreen()) * amount),
            (int) Math.round(a.getBlue() + (b.getBlue() - a.getBlue()) * amount));
    }

    /** The first installed of the preferred monospaced families. */
    static Font codeFont(int size) {
        var families = java.util.Set.of(GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames());
        for (String family : new String[] {"JetBrains Mono", "Cascadia Mono", "Consolas", "Menlo", "DejaVu Sans Mono"})
            if (families.contains(family)) return new Font(family, Font.PLAIN, size);
        return new Font(Font.MONOSPACED, Font.PLAIN, size);
    }
}
