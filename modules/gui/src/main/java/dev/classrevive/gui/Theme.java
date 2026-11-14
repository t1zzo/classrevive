package dev.classrevive.gui;

import com.formdev.flatlaf.*;
import dev.classrevive.workbench.ResultIndex;
import java.awt.*;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Phase G3, recolored in G4, rebuilt on design tokens in G8: FlatLaf with the ClassRevive look. The dark theme (the default)
 * is near-black with orange accents; the light theme is warm white with the same orange. Every panel draws with the
 * {@link Palette} of the current theme, so surfaces, borders and text tones match everywhere. Confidence colors stay clear of
 * orange so they never read as the accent.
 */
final class Theme {
    static final Color ACCENT = new Color(0xFF8A1F);
    static final Color ACCENT_HOVER = new Color(0xFF9D45);
    static final Color ACCENT_PRESSED = new Color(0xE5751A);
    /** Text and icons drawn on the accent: dark, for contrast on orange. */
    static final Color ON_ACCENT = new Color(0x141416);

    /**
     * Phase G8: the surfaces, lines and text tones of one theme.
     *
     * @param chrome       title bar, toolbar and status bar
     * @param base         panels (explorer, inspector, bottom panels)
     * @param editor       the code editor and the overview page
     * @param raised       cards on top of a panel or the overview
     * @param hover        a row or card under the mouse
     * @param border       hairlines between regions and around cards
     * @param borderStrong outlines that must stand out (inputs, keycaps)
     * @param text         primary text
     * @param secondary    supporting text (paths, notes, descriptions)
     * @param muted        the least important text (counts, placeholders)
     * @param accentText   the accent as text on this theme's surfaces (links, highlighted words)
     * @param accentSoft   a faint accent tint for selected cards and banners
     * @param selection    selected rows
     * @param currentLine  the editor's current line
     * @param codeSelection selected text in the editor
     */
    record Palette(Color chrome, Color base, Color editor, Color raised, Color hover, Color border, Color borderStrong,
                   Color text, Color secondary, Color muted, Color accentText, Color accentSoft, Color selection,
                   Color currentLine, Color codeSelection) {}

    static final Palette DARK = new Palette(rgb(0x0E0E10), rgb(0x141416), rgb(0x0C0C0E), rgb(0x1B1B1F), rgb(0x222227),
        rgb(0x26262B), rgb(0x3A3A41), rgb(0xE7E7EA), rgb(0xA8A8B0), rgb(0x77777F), rgb(0xFFA552), rgb(0x2A1A0B),
        rgb(0x5C2F0D), rgb(0x17171A), rgb(0x4D2A0E));
    static final Palette LIGHT = new Palette(rgb(0xEFEFF1), rgb(0xF7F7F8), rgb(0xFFFFFF), rgb(0xFFFFFF), rgb(0xECECEF),
        rgb(0xE1E1E5), rgb(0xC9C9D0), rgb(0x18181B), rgb(0x52525B), rgb(0x7C7C86), rgb(0xB8530A), rgb(0xFFF2E5),
        rgb(0xFFDDBC), rgb(0xF6F6F8), rgb(0xFFD9B5));

    /** Shared by both themes: shapes, sizes and spacing. */
    private static final Map<String,String> SHARED = Map.ofEntries(
        Map.entry("@accentColor", "#FF8A1F"), Map.entry("defaultFont", "+1"),
        Map.entry("Component.arc", "8"), Map.entry("Button.arc", "8"), Map.entry("TextComponent.arc", "8"), Map.entry("CheckBox.arc", "5"),
        Map.entry("Component.focusWidth", "1"), Map.entry("Component.innerFocusWidth", "0"), Map.entry("Button.margin", "4,14,4,14"),
        Map.entry("ScrollBar.thumbArc", "999"), Map.entry("ScrollBar.thumbInsets", "3,3,3,3"), Map.entry("ScrollBar.width", "12"),
        Map.entry("ScrollBar.showButtons", "false"), Map.entry("ScrollPane.smoothScrolling", "true"),
        Map.entry("Tree.rowHeight", "26"), Map.entry("Tree.selectionArc", "6"), Map.entry("Tree.selectionInsets", "0,6,0,6"),
        Map.entry("List.selectionArc", "6"), Map.entry("List.selectionInsets", "0,4,0,4"),
        Map.entry("MenuItem.selectionArc", "6"), Map.entry("MenuItem.selectionInsets", "0,4,0,4"), Map.entry("MenuItem.minimumWidth", "180"),
        Map.entry("Popup.borderCornerRadius", "8"), Map.entry("ComboBox.selectionArc", "6"),
        Map.entry("TabbedPane.tabHeight", "34"), Map.entry("TabbedPane.tabSelectionHeight", "2"), Map.entry("TabbedPane.tabInsets", "4,14,4,10"),
        Map.entry("TabbedPane.showTabSeparators", "false"), Map.entry("TitlePane.unifiedBackground", "true"),
        Map.entry("SplitPaneDivider.style", "plain"), Map.entry("SplitPane.dividerSize", "5"), Map.entry("Component.focusColor", "#FF8A1F"),
        Map.entry("ProgressBar.arc", "999"), Map.entry("ProgressBar.foreground", "#FF8A1F"), Map.entry("ToolTip.borderCornerRadius", "6"),
        Map.entry("Spinner.buttonStyle", "button"), Map.entry("CheckBox.icon.focusWidth", "1"));
    /** Near-black surfaces, light gray text, orange focus and a calm dark-orange selection. */
    private static final Map<String,String> DARK_KEYS = keys(DARK, Map.ofEntries(
        Map.entry("@selectionForeground", "#FFFFFF"), Map.entry("TextField.background", "#1A1A1D"), Map.entry("ComboBox.background", "#1A1A1D"),
        Map.entry("Spinner.background", "#1A1A1D"), Map.entry("FormattedTextField.background", "#1A1A1D"), Map.entry("Button.background", "#1F1F23"),
        Map.entry("Button.hoverBackground", "#28282D"), Map.entry("Button.toolbar.hoverBackground", "#222227"),
        Map.entry("Button.toolbar.pressedBackground", "#2A2A30"), Map.entry("CheckBox.icon.checkmarkColor", "#141416"),
        Map.entry("ToolTip.background", "#232328"), Map.entry("PopupMenu.background", "#18181B"), Map.entry("PopupMenu.borderColor", "#2E2E34"),
        Map.entry("MenuBar.hoverBackground", "#222227"), Map.entry("Menu.selectionBackground", "#2A2A30"), Map.entry("MenuItem.selectionBackground", "#2A2A30"),
        Map.entry("MenuItem.selectionForeground", "#FFFFFF"), Map.entry("Menu.selectionForeground", "#FFFFFF"),
        Map.entry("ScrollBar.thumb", "#34343A"), Map.entry("ScrollBar.hoverThumbColor", "#45454C"), Map.entry("ScrollBar.pressedThumbColor", "#55555C")));
    /** Warm white surfaces, near-black text and a dark orange for text on white. */
    private static final Map<String,String> LIGHT_KEYS = keys(LIGHT, Map.ofEntries(
        Map.entry("@selectionForeground", "#18181B"), Map.entry("TextField.background", "#FFFFFF"), Map.entry("ComboBox.background", "#FFFFFF"),
        Map.entry("Spinner.background", "#FFFFFF"), Map.entry("Button.background", "#FFFFFF"), Map.entry("Button.toolbar.hoverBackground", "#E4E4E8"),
        Map.entry("Button.toolbar.pressedBackground", "#DADADF"), Map.entry("ToolTip.background", "#FFFFFF"), Map.entry("PopupMenu.background", "#FFFFFF"),
        Map.entry("MenuItem.selectionBackground", "#F0F0F2"), Map.entry("Menu.selectionBackground", "#F0F0F2"),
        Map.entry("MenuItem.selectionForeground", "#18181B"), Map.entry("Menu.selectionForeground", "#18181B"),
        Map.entry("TabbedPane.underlineColor", "#E8720C"), Map.entry("ProgressBar.foreground", "#E8720C"),
        Map.entry("ScrollBar.thumb", "#D4D4DA"), Map.entry("ScrollBar.hoverThumbColor", "#BDBDC5"), Map.entry("ScrollBar.pressedThumbColor", "#A8A8B2")));

    private static Font code;
    private static int codeSize = -1;
    private static String codeFamily;

    private Theme() {}

    /** The FlatLaf keys a palette sets, plus the theme's own extras. */
    private static Map<String,String> keys(Palette p, Map<String,String> extras) {
        var keys = new HashMap<String,String>();
        keys.put("@background", hex(p.base()));
        keys.put("@foreground", hex(p.text()));
        keys.put("@disabledForeground", hex(p.muted()));
        keys.put("@selectionBackground", hex(p.selection()));
        keys.put("@selectionInactiveBackground", hex(p.hover()));
        keys.put("@selectionInactiveForeground", hex(p.text()));
        keys.put("Component.borderColor", hex(p.border()));
        keys.put("Component.disabledBorderColor", hex(p.border()));
        keys.put("Separator.foreground", hex(p.border()));
        keys.put("TabbedPane.underlineColor", "#FF8A1F");
        keys.put("TabbedPane.inactiveUnderlineColor", hex(p.borderStrong()));
        keys.put("TabbedPane.hoverColor", hex(p.hover()));
        keys.put("TabbedPane.focusColor", hex(p.base()));
        keys.put("TabbedPane.selectedBackground", hex(p.editor()));
        keys.put("TabbedPane.contentAreaColor", hex(p.border()));
        keys.put("ToolBar.background", hex(p.chrome()));
        keys.put("MenuBar.background", hex(p.chrome()));
        keys.put("TitlePane.background", hex(p.chrome()));
        keys.put("TitlePane.inactiveBackground", hex(p.chrome()));
        keys.put("Tree.background", hex(p.base()));
        keys.put("List.background", hex(p.base()));
        keys.put("Table.background", hex(p.base()));
        keys.put("ScrollBar.track", hex(p.base()));
        keys.put("Label.disabledForeground", hex(p.muted()));
        keys.put("ClassRevive.secondaryForeground", hex(p.secondary()));
        keys.put("ClassRevive.mutedForeground", hex(p.muted()));
        keys.put("ClassRevive.accentForeground", hex(p.accentText()));
        keys.put("ClassRevive.cardBackground", hex(p.raised()));
        keys.put("ClassRevive.hoverBackground", hex(p.hover()));
        keys.put("ClassRevive.borderStrongColor", hex(p.borderStrong()));
        keys.putAll(extras);
        return Map.copyOf(keys);
    }

    /** Installs the look and feel; call before any window exists. */
    static void install(GuiSettings.ThemeChoice choice) { install(dark(choice)); }

    /** Phase G8: with the theme already resolved, so a "follow the system" lookup never runs on the event thread. */
    static void install(boolean dark) {
        var defaults = new HashMap<>(SHARED);
        defaults.putAll(dark ? DARK_KEYS : LIGHT_KEYS);
        FlatLaf.setGlobalExtraDefaults(defaults);
        if (dark) FlatDarkLaf.setup(); else FlatLightLaf.setup();
    }

    /** Switches every open window to {@code choice}. */
    static void switchTo(GuiSettings.ThemeChoice choice) {
        install(choice);
        FlatLaf.updateUI();
    }

    static boolean dark() { return FlatLaf.isLafDark(); }

    /** The palette of the theme in use. */
    static Palette palette() { return dark() ? DARK : LIGHT; }

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

    private static final Color EXACT = rgb(0x3FB950), CLOSE = rgb(0x58A6FF), PER_FILE = rgb(0xD7B53C), PARTIAL = rgb(0xB980F0),
        UNVERIFIED = rgb(0xF0605A), NO_LEVEL = rgb(0x8A8A93), WARNING = rgb(0xE8A317);

    /** One color per confidence level, readable on both themes; null (not decompiled yet) is neutral. */
    static Color level(ResultIndex.Level level) {
        if (level == null) return NO_LEVEL;
        return switch (level) {
            case EXACT -> EXACT;
            case CLOSE -> CLOSE;
            case PER_FILE -> PER_FILE;
            case PARTIAL -> PARTIAL;
            case UNVERIFIED -> UNVERIFIED;
        };
    }

    static Color warning() { return WARNING; }

    /** Black or white, whichever reads better on {@code background}. */
    static Color textOn(Color background) {
        double luminance = (0.299 * background.getRed() + 0.587 * background.getGreen() + 0.114 * background.getBlue()) / 255;
        return luminance > 0.6 ? ON_ACCENT : Color.WHITE;
    }

    /** Text that matters least: counts, placeholders, disabled hints. */
    static Color muted() { return palette().muted(); }
    /** Supporting text: paths, notes, descriptions. */
    static Color secondary() { return palette().secondary(); }
    static Color foreground() { return palette().text(); }
    static Color panel() { return palette().base(); }
    static Color card() { return palette().raised(); }
    static Color hover() { return palette().hover(); }
    /** The code editor's background: darker than the panels in the dark theme, white in the light one. */
    static Color editor() { return palette().editor(); }
    static Color line() { return palette().border(); }
    static Color lineStrong() { return palette().borderStrong(); }
    static Color accentText() { return palette().accentText(); }
    static Color accentSoft() { return palette().accentSoft(); }

    static Color color(String key, Color fallback) {
        Color color = javax.swing.UIManager.getColor(key);
        return color == null ? fallback : color;
    }

    static Color blend(Color a, Color b, double amount) {
        return new Color((int) Math.round(a.getRed() + (b.getRed() - a.getRed()) * amount), (int) Math.round(a.getGreen() + (b.getGreen() - a.getGreen()) * amount),
            (int) Math.round(a.getBlue() + (b.getBlue() - a.getBlue()) * amount));
    }

    /** {@code color} with {@code alpha} (0-255). */
    static Color alpha(Color color, int alpha) { return new Color(color.getRed(), color.getGreen(), color.getBlue(), alpha); }

    /**
     * The first installed of the preferred monospaced families. Phase G8: the family is looked up once and the font kept per
     * size; the lookup lists every installed font and used to run on each repaint of the lists that show code.
     */
    static synchronized Font codeFont(int size) {
        if (codeFamily == null) {
            var families = java.util.Set.of(GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames());
            codeFamily = Font.MONOSPACED;
            for (String family : new String[] {"JetBrains Mono", "Cascadia Mono", "Consolas", "Menlo", "SF Mono", "DejaVu Sans Mono", "Liberation Mono"})
                if (families.contains(family)) { codeFamily = family; break; }
        }
        if (code == null || codeSize != size) { code = new Font(codeFamily, Font.PLAIN, size); codeSize = size; }
        return code;
    }

    private static Color rgb(int value) { return new Color(value); }
    private static String hex(Color color) { return String.format("#%06X", color.getRGB() & 0xFFFFFF); }
}
