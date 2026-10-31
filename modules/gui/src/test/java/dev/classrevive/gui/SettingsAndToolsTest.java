package dev.classrevive.gui;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class SettingsAndToolsTest {
    @TempDir Path directory;

    @Test void settingsRoundTripAndFallBackOnBadValues() throws Exception {
        Path file = directory.resolve("settings.properties");
        var settings = GuiSettings.load(file);
        assertEquals(GuiSettings.ThemeChoice.DARK, settings.theme(), "black and orange by default (Phase G4)");
        assertEquals(List.of("cfr", "vineflower"), settings.engines().stream().map(e -> e.id()).toList());
        settings.theme(GuiSettings.ThemeChoice.DARK);
        settings.language(GuiSettings.Language.ENGLISH);
        settings.fontSize(99);
        settings.profile(GuiSettings.Profile.CUSTOM);
        settings.customEngines("procyon,jadx");
        settings.timeoutSeconds(300);
        settings.stubs(false);
        for (int i = 0; i < 14; i++) settings.remember(directory.resolve("input-" + i + ".jar"));
        settings.remember(directory.resolve("input-3.jar"));
        settings.save();

        var loaded = GuiSettings.load(file);
        assertEquals(GuiSettings.ThemeChoice.DARK, loaded.theme());
        assertEquals(GuiSettings.Language.ENGLISH, loaded.language());
        assertEquals(GuiSettings.MAX_FONT, loaded.fontSize());
        assertEquals(List.of("procyon", "jadx"), loaded.engines().stream().map(e -> e.id()).toList());
        assertEquals(300, loaded.timeoutSeconds());
        assertFalse(loaded.stubs());
        assertEquals(GuiSettings.MAX_RECENT, loaded.recent().size());
        assertEquals(directory.resolve("input-3.jar").toAbsolutePath().normalize(), loaded.recent().getFirst(), "reopened goes first, once");

        Files.writeString(file, "theme=PURPLE\neditor.fontSize=abc\ndecompile.timeoutSeconds=0\ndecompile.customEngines=fernflower\ndecompile.profile=CUSTOM\n");
        var damaged = GuiSettings.load(file);
        assertEquals(GuiSettings.ThemeChoice.DARK, damaged.theme());
        assertEquals(13, damaged.fontSize());
        assertEquals(120, damaged.timeoutSeconds());
        assertEquals(List.of("cfr", "vineflower"), damaged.engines().stream().map(e -> e.id()).toList());
        assertThrows(IllegalArgumentException.class, () -> damaged.timeoutSeconds(0));
        assertThrows(IllegalArgumentException.class, () -> damaged.customEngines("cfr,cfr"));
    }

    @Test void searchFindsLinesInSourcesAndStopsAtTheLimit() throws Exception {
        Path root = Files.createDirectories(directory.resolve("sources/a"));
        Files.writeString(root.resolve("B.java"), "package a;\nclass B {\n  int Value = 1;\n  int value() { return Value; }\n}\n");
        Files.writeString(root.resolve("notes.txt"), "value");
        var hits = SourceSearch.search(directory.resolve("sources"), "value", false, 100, () -> false);
        assertEquals(List.of(3, 4), hits.stream().map(SourceSearch.Hit::line).toList());
        assertEquals("a/B", hits.getFirst().className());
        assertEquals(List.of(4), SourceSearch.search(directory.resolve("sources"), "value()", true, 100, () -> false).stream().map(SourceSearch.Hit::line).toList());
        assertEquals(1, SourceSearch.search(directory.resolve("sources"), "value", false, 1, () -> false).size());
        assertTrue(SourceSearch.search(directory.resolve("sources"), "value", false, 100, () -> true).isEmpty());
        assertTrue(SourceSearch.search(directory.resolve("missing"), "value", false, 100, () -> false).isEmpty());
    }

    @Test void exportCopiesIntoANewFolderAndZipsWithoutOverwriting() throws Exception {
        Path source = Files.createDirectories(directory.resolve("project/src/main/java/a"));
        Files.writeString(source.resolve("B.java"), "class B {}");
        Files.writeString(directory.resolve("project/README.md"), "readme");
        Path target = directory.resolve("out/project-copy");
        assertEquals(2, Exporter.copyTree(directory.resolve("project"), target));
        assertEquals("class B {}", Files.readString(target.resolve("src/main/java/a/B.java")));
        assertThrows(IOException.class, () -> Exporter.copyTree(directory.resolve("project"), target), "target not empty");
        assertThrows(IOException.class, () -> Exporter.copyTree(directory.resolve("project"), directory.resolve("project/inside")));
        Path zip = directory.resolve("sources.zip");
        assertEquals(2, Exporter.zip(directory.resolve("project"), zip));
        try (var archive = new ZipFile(zip.toFile())) { assertNotNull(archive.getEntry("src/main/java/a/B.java")); }
        assertThrows(FileAlreadyExistsException.class, () -> Exporter.zip(directory.resolve("project"), zip));
    }

    /** Every key the code asks for exists in both languages, both have the same keys, and every pattern formats. */
    @Test void bothLanguagesHaveEveryStringTheCodeUses() throws Exception {
        var english = load("messages.properties");
        var portuguese = load("messages_pt_BR.properties");
        assertEquals(english.keySet(), portuguese.keySet(), "same keys in both languages");
        var used = new TreeSet<String>();
        var literal = Pattern.compile("Text\\.t\\(\"([A-Za-z0-9_.@]+)\"\\s*[,)]");
        try (var files = Files.walk(Path.of("src/main/java"))) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                var matcher = literal.matcher(Files.readString(file));
                while (matcher.find()) used.add(matcher.group(1));
            }
        }
        for (String confidence : List.of("HIGH", "MEDIUM", "LOW")) used.add("confidence." + confidence);
        var families = Map.of("outcome.", Arrays.stream(dev.classrevive.workbench.ResultIndex.Outcome.values()).map(Enum::name).toList(), "level.", List.of("EXACT", "CLOSE", "PER_FILE", "PARTIAL", "UNVERIFIED", "EXACT.meaning", "CLOSE.meaning",
                "PER_FILE.meaning", "PARTIAL.meaning", "UNVERIFIED.meaning"),
            "kind.", List.of("C", "I", "E", "R", "@"), "compilation.", List.of("COMPILED", "COMPILE_FAILED", "UNSUPPORTED_RELEASE", "WORKER_FAILED"),
            "engine.", Arrays.stream(dev.classrevive.engine.EngineResult.Status.values()).map(Enum::name).toList(),
            "stage.", Arrays.stream(dev.classrevive.pipeline.JobListener.Stage.values()).map(Enum::name).toList(),
            "theme.", Arrays.stream(GuiSettings.ThemeChoice.values()).map(Enum::name).toList(),
            "language.", Arrays.stream(GuiSettings.Language.values()).map(Enum::name).toList(),
            "profile.", Arrays.stream(GuiSettings.Profile.values()).map(Enum::name).toList());
        families.forEach((prefix, names) -> names.forEach(name -> used.add(prefix + name)));
        for (String key : used) assertTrue(english.containsKey(key), "missing key " + key);
        for (var bundle : List.of(english, portuguese))
            for (String key : bundle.stringPropertyNames())
                assertDoesNotThrow(() -> new java.text.MessageFormat(bundle.getProperty(key)).format(new Object[] {1, 2, 3, 4}), key);
        assertTrue(new java.text.MessageFormat(english.getProperty("level.PER_FILE.meaning")).format(new Object[0]).contains("class's"));
    }

    private static Properties load(String name) throws IOException {
        var properties = new Properties();
        try (var in = SettingsAndToolsTest.class.getResourceAsStream("/dev/classrevive/gui/" + name);
             var reader = new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8)) { properties.load(reader); }
        return properties;
    }
}
