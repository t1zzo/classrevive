package dev.classrevive.gui;

import java.text.MessageFormat;
import java.util.*;

/** User-visible strings, in Brazilian Portuguese or English ({@code messages*.properties}, UTF-8). */
public final class Text {
    private static ResourceBundle bundle = load(Locale.getDefault());

    private Text() {}

    /** Chooses the language for every string created afterwards. */
    public static void use(GuiSettings.Language language) {
        bundle = load(switch (language) {
            case PORTUGUESE -> Locale.of("pt", "BR");
            case ENGLISH -> Locale.ENGLISH;
            case SYSTEM -> Locale.getDefault();
        });
    }

    public static Locale locale() { return bundle.getLocale(); }

    /** The string for {@code key}, formatted with {@code args} ({@link MessageFormat}); the key itself when missing. */
    public static String t(String key, Object... args) {
        String pattern;
        try { pattern = bundle.getString(key); } catch (MissingResourceException missing) { return key; }
        // Always through MessageFormat, so every string follows the same quoting rule ('' for an apostrophe).
        return new MessageFormat(pattern, bundle.getLocale()).format(args);
    }

    private static ResourceBundle load(Locale locale) {
        // English is the base bundle; any other language falls back to it rather than to the JVM default.
        return ResourceBundle.getBundle("dev.classrevive.gui.messages", locale,
            ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_PROPERTIES));
    }
}
