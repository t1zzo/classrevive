package dev.classrevive.gui;

import dev.classrevive.engine.DecompilerEngine;
import dev.classrevive.workbench.EngineRegistry;
import dev.classrevive.workbench.RequestLimits;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * The desktop application's preferences, kept in {@code settings.properties} under {@link #home()}. Unknown or invalid
 * values fall back to their defaults, so a damaged file never stops the application from starting.
 */
public final class GuiSettings {
    public enum ThemeChoice { SYSTEM, LIGHT, DARK }
    public enum Language { SYSTEM, PORTUGUESE, ENGLISH }
    /** FAST is the CLI default (CFR + Vineflower); COMPLETE runs all four engines; CUSTOM uses {@link #customEngines()}. */
    public enum Profile { FAST, COMPLETE, CUSTOM }

    static final int MAX_RECENT = 10, MIN_FONT = 10, MAX_FONT = 28;

    private final Path file;
    private ThemeChoice theme = ThemeChoice.DARK;
    private Language language = Language.SYSTEM;
    private int fontSize = 13;
    private Profile profile = Profile.FAST;
    private String customEngines = "cfr,vineflower";
    private int timeoutSeconds = 120;
    private boolean stubs = true;
    private boolean engineCache = true;
    private boolean showInspector = true;
    private boolean showActivity = true;
    private Path workspaceDirectory;
    private Path lastDirectory;
    private final List<Path> recent = new ArrayList<>();

    private GuiSettings(Path file) {
        this.file = file;
        this.workspaceDirectory = file.getParent().resolve("workspaces");
    }

    /** {@code classrevive.home} when set (tests), else {@code %APPDATA%\ClassRevive} on Windows, else {@code ~/.classrevive}. */
    public static Path home() {
        String override = System.getProperty("classrevive.home");
        if (override != null && !override.isBlank()) return Path.of(override);
        String appData = System.getenv("APPDATA");
        if (System.getProperty("os.name", "").startsWith("Windows") && appData != null && !appData.isBlank())
            return Path.of(appData, "ClassRevive");
        return Path.of(System.getProperty("user.home"), ".classrevive");
    }

    public static GuiSettings load() { return load(home().resolve("settings.properties")); }

    static GuiSettings load(Path file) {
        var settings = new GuiSettings(file);
        if (!Files.isRegularFile(file)) return settings;
        var values = new Properties();
        try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) { values.load(reader); }
        catch (IOException | IllegalArgumentException unreadable) { return settings; }
        settings.theme = choice(ThemeChoice.class, values.getProperty("theme"), settings.theme);
        settings.language = choice(Language.class, values.getProperty("language"), settings.language);
        settings.fontSize = number(values.getProperty("editor.fontSize"), settings.fontSize, MIN_FONT, MAX_FONT);
        settings.profile = choice(Profile.class, values.getProperty("decompile.profile"), settings.profile);
        String custom = values.getProperty("decompile.customEngines");
        if (custom != null && valid(custom)) settings.customEngines = custom;
        settings.timeoutSeconds = number(values.getProperty("decompile.timeoutSeconds"), settings.timeoutSeconds,
            RequestLimits.MIN_TIMEOUT_SECONDS, RequestLimits.MAX_TIMEOUT_SECONDS);
        settings.stubs = flag(values.getProperty("decompile.stubs"), settings.stubs);
        settings.engineCache = flag(values.getProperty("decompile.engineCache"), settings.engineCache);
        settings.showInspector = flag(values.getProperty("view.inspector"), settings.showInspector);
        settings.showActivity = flag(values.getProperty("view.activity"), settings.showActivity);
        settings.workspaceDirectory = path(values.getProperty("workspace.directory"), settings.workspaceDirectory);
        settings.lastDirectory = path(values.getProperty("files.lastDirectory"), null);
        for (int i = 0; i < MAX_RECENT; i++) {
            Path recent = path(values.getProperty("recent." + i), null);
            if (recent != null && !settings.recent.contains(recent)) settings.recent.add(recent);
        }
        return settings;
    }

    /** Writes the file through a temporary sibling, so a crash never leaves it half written. */
    public void save() throws IOException {
        var values = new Properties();
        values.setProperty("theme", theme.name());
        values.setProperty("language", language.name());
        values.setProperty("editor.fontSize", Integer.toString(fontSize));
        values.setProperty("decompile.profile", profile.name());
        values.setProperty("decompile.customEngines", customEngines);
        values.setProperty("decompile.timeoutSeconds", Integer.toString(timeoutSeconds));
        values.setProperty("decompile.stubs", Boolean.toString(stubs));
        values.setProperty("decompile.engineCache", Boolean.toString(engineCache));
        values.setProperty("view.inspector", Boolean.toString(showInspector));
        values.setProperty("view.activity", Boolean.toString(showActivity));
        values.setProperty("workspace.directory", workspaceDirectory.toString());
        if (lastDirectory != null) values.setProperty("files.lastDirectory", lastDirectory.toString());
        for (int i = 0; i < recent.size(); i++) values.setProperty("recent." + i, recent.get(i).toString());
        Files.createDirectories(file.getParent());
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        try (var writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) { values.store(writer, "ClassRevive desktop settings"); }
        Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    /** The engines a decompile runs with the current profile. */
    public List<DecompilerEngine> engines() {
        return switch (profile) {
            case FAST -> EngineRegistry.parse("cfr,vineflower");
            case COMPLETE -> EngineRegistry.all();
            case CUSTOM -> EngineRegistry.parse(customEngines);
        };
    }

    /** Puts {@code path} first in the recent list (inputs and result folders alike). */
    public void remember(Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        recent.remove(normalized);
        recent.addFirst(normalized);
        while (recent.size() > MAX_RECENT) recent.removeLast();
    }

    public void forget(Path path) { recent.remove(path.toAbsolutePath().normalize()); }

    public Path engineCacheDirectory() { return file.getParent().resolve("engine-cache"); }
    public Path cacheDirectory() { return file.getParent().resolve("cache"); }

    public ThemeChoice theme() { return theme; }
    public void theme(ThemeChoice value) { theme = Objects.requireNonNull(value); }
    public Language language() { return language; }
    public void language(Language value) { language = Objects.requireNonNull(value); }
    public int fontSize() { return fontSize; }
    public void fontSize(int value) { fontSize = Math.max(MIN_FONT, Math.min(MAX_FONT, value)); }
    public Profile profile() { return profile; }
    public void profile(Profile value) { profile = Objects.requireNonNull(value); }
    public String customEngines() { return customEngines; }
    public void customEngines(String value) { if (!valid(value)) throw new IllegalArgumentException(value); customEngines = value; }
    public int timeoutSeconds() { return timeoutSeconds; }
    public void timeoutSeconds(int value) { RequestLimits.timeoutSeconds(value); timeoutSeconds = value; }
    public boolean stubs() { return stubs; }
    public void stubs(boolean value) { stubs = value; }
    public boolean engineCache() { return engineCache; }
    public void engineCache(boolean value) { engineCache = value; }
    public boolean showInspector() { return showInspector; }
    public void showInspector(boolean value) { showInspector = value; }
    public boolean showActivity() { return showActivity; }
    public void showActivity(boolean value) { showActivity = value; }
    public Path workspaceDirectory() { return workspaceDirectory; }
    public void workspaceDirectory(Path value) { workspaceDirectory = value.toAbsolutePath().normalize(); }
    public Path lastDirectory() { return lastDirectory; }
    public void lastDirectory(Path value) { lastDirectory = value; }
    public List<Path> recent() { return List.copyOf(recent); }

    private static boolean valid(String engines) {
        try { EngineRegistry.parse(engines); return true; } catch (IllegalArgumentException invalid) { return false; }
    }
    private static <E extends Enum<E>> E choice(Class<E> type, String value, E fallback) {
        if (value == null) return fallback;
        try { return Enum.valueOf(type, value); } catch (IllegalArgumentException unknown) { return fallback; }
    }
    private static int number(String value, int fallback, int min, int max) {
        if (value == null) return fallback;
        try { int parsed = Integer.parseInt(value.trim()); return parsed < min || parsed > max ? fallback : parsed; }
        catch (NumberFormatException bad) { return fallback; }
    }
    private static boolean flag(String value, boolean fallback) {
        return value == null ? fallback : value.equals("true") ? true : value.equals("false") ? false : fallback;
    }
    private static Path path(String value, Path fallback) {
        if (value == null || value.isBlank()) return fallback;
        try { return Path.of(value); } catch (InvalidPathException bad) { return fallback; }
    }
}
