package dev.classrevive.pipeline;

import dev.classrevive.pipeline.InputIdentifier.Verdict;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.zip.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import static dev.classrevive.pipeline.ExecutableFixtures.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/** Phase 2DB: zips of jars, alone or inside an executable, written by the test itself and only read as bytes. */
class JavaBundleTest {
    @TempDir Path directory;

    /** A jar of empty public classes with these internal names, and a manifest (with Main-Class when {@code main} is set). */
    static byte[] classes(String main, String... names) throws IOException {
        var entries = new TreeMap<String,byte[]>();
        entries.put("META-INF/MANIFEST.MF", ("Manifest-Version: 1.0\r\n" + (main == null ? "" : "Main-Class: " + main + "\r\n") + "\r\n")
            .getBytes(StandardCharsets.US_ASCII));
        for (String name : names) {
            var writer = new ClassWriter(0);
            writer.visit(V1_8, ACC_PUBLIC | ACC_SUPER, name, null, "java/lang/Object", null);
            writer.visitEnd();
            entries.put(name + ".class", writer.toByteArray());
        }
        return zip(entries);
    }

    /** A zip whose entries are all stored (not deflated), as some launchers write their payload. */
    static byte[] stored(Map<String,byte[]> entries) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var out = new ZipOutputStream(bytes)) {
            for (var entry : new TreeMap<>(entries).entrySet()) {
                var item = entry(entry.getKey());
                var crc = new CRC32(); crc.update(entry.getValue());
                item.setMethod(ZipEntry.STORED); item.setSize(entry.getValue().length); item.setCompressedSize(entry.getValue().length);
                item.setCrc(crc.getValue());
                out.putNextEntry(item); out.write(entry.getValue()); out.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    private Map<String,byte[]> launcherPayload() throws IOException {
        var payload = new TreeMap<String,byte[]>();
        payload.put("libs/gson.jar", classes(null, "com/google/gson/Gson", "com/google/gson/JsonElement", "com/google/gson/JsonParser"));
        payload.put("client-1.0.jar", classes(null, "app/Client", "app/Render"));
        payload.put("loader.jar", classes("boot/Loader", "boot/Loader"));
        payload.put("bridge.jar", zip(Map.of("readme.txt", new byte[] {1})));
        payload.put("native.dll", random(512, 9));
        return payload;
    }

    @Test void zipInsideAnExecutableSectionIsABundleAndTheApplicationJarIsTaken() throws Exception {
        byte[] exe = pe(List.of("CreateProcessW"), "javaw.exe", ".rdata2", zip(launcherPayload()), false, 2);
        var found = InputIdentifierTest.identify(exe, "Launcher.exe");
        assertEquals(Verdict.JAVA_BUNDLE, found.verdict(), found.text());
        assertTrue(found.javaAvailable());
        assertTrue(found.selected().location().contains("inside the executable's image"), found.selected().location());
        assertEquals(EXTRA, found.selected().offset());
        String text = found.text();
        assertTrue(text.contains("nested jar client-1.0.jar: 2 classes"), text);
        assertTrue(text.contains("<- main"), text);
        assertTrue(text.contains("nested jar libs/gson.jar: 3 classes") && text.contains("library Gson, library folder"), text);
        assertTrue(text.contains("nested jar loader.jar: 1 classes") && text.contains("Main-Class"), text);
        assertTrue(text.contains("nested jar bridge.jar: 0 classes"), text);
        assertTrue(text.contains("1 other entry in the bundle (not Java, never run): native.dll"), text);
        assertTrue(found.explanation().contains("4 nested .jar entries") && found.explanation().contains("3 with classes (6 classes in all)"),
            found.explanation());
        assertTrue(found.explanation().contains("take client-1.0.jar"), found.explanation());
        // The JNI wording no longer suggests there is no Java.
        assertTrue(text.contains("does not start a JVM through JNI itself"), text);
        assertFalse(text.contains("no readable JNI or JVMTI name (JNI_CreateJavaVM"), text);
        var bundle = (Map<?,?>) found.json().get("bundle");
        assertEquals("client-1.0.jar", bundle.get("main"));
        assertEquals(4, ((List<?>) bundle.get("jars")).size());

        Path input = Files.write(directory.resolve("Launcher.exe"), exe);
        var resolved = InputIdentifier.resolve(input);
        assertEquals("client-1.0.jar", resolved.entry());
        assertArrayEquals(launcherPayload().get("client-1.0.jar"), resolved.bytes());
        assertEquals(List.of("libs/gson.jar", "loader.jar"), List.copyOf(resolved.classpath().keySet()));
        // --entry: a full name or a unique base name; anything else is refused with the names.
        assertEquals("loader.jar", InputIdentifier.resolve(input, Duration.ofSeconds(10), "loader.jar").entry());
        assertEquals("libs/gson.jar", InputIdentifier.resolve(input, Duration.ofSeconds(10), "gson.jar").entry());
        var missing = assertThrows(IllegalArgumentException.class, () -> InputIdentifier.resolve(input, Duration.ofSeconds(10), "nope.jar"));
        assertTrue(missing.getMessage().contains("client-1.0.jar"), missing.getMessage());
        assertThrows(IllegalArgumentException.class, () -> InputIdentifier.resolve(input, Duration.ofSeconds(10), "bridge.jar"));
        Path plain = Files.write(directory.resolve("plain.exe"), concat(pe(List.of("GetTickCount")), jar()));
        assertThrows(IllegalArgumentException.class, () -> InputIdentifier.resolve(plain, Duration.ofSeconds(10), "x.jar"));

        // identify --output: the nested jars written as data.
        Path evidence = InputIdentifier.write(directory.resolve("evidence"), found);
        assertEquals(List.of("bundle/libs_gson.jar", "bundle/client-1.0.jar", "bundle/loader.jar", "bundle/bridge.jar").stream().sorted().toList(),
            InputIdentifier.extractBundle(input, found, evidence).stream().sorted().toList());
        assertArrayEquals(launcherPayload().get("loader.jar"), Files.readAllBytes(evidence.resolve("bundle/loader.jar")));
    }

    @Test void storedPayloadInTheOverlayAndALibraryOutsideALibFolder() throws Exception {
        // The largest jar is Gson by its packages: the smaller application jar is taken; nested jars are stored, not deflated.
        var payload = Map.of("gson-2.10.jar", classes(null, "com/google/gson/A", "com/google/gson/B", "com/google/gson/C", "com/google/gson/D"),
            "app.jar", classes(null, "app/Main"));
        var found = InputIdentifierTest.identify(concat(pe(List.of("GetTickCount")), stored(payload)), "app.exe");
        assertEquals(Verdict.JAVA_BUNDLE, found.verdict(), found.text());
        assertTrue(found.selected().location().contains("overlay"), found.selected().location());
        assertEquals("app.jar", ((Map<?,?>) found.json().get("bundle")).get("main"));
        // Only libraries: the largest is taken.
        var libraries = InputIdentifierTest.identify(zip(Map.of("lib/gson.jar", payload.get("gson-2.10.jar"))), "libs.zip");
        assertEquals(Verdict.JAVA_BUNDLE, libraries.verdict(), libraries.text());
        assertEquals("lib/gson.jar", ((Map<?,?>) libraries.json().get("bundle")).get("main"));
    }

    @Test void backslashFoldersAndVariantsOfTheMainJar() throws Exception {
        // Zips written on Windows may name "libs\\x.jar"; a jar holding the main jar's classes under other mappings is a variant.
        var payload = new TreeMap<String,byte[]>();
        payload.put("libs\\big.jar", classes(null, "x/A", "x/B", "x/C", "x/D", "x/E"));
        payload.put("client-a.jar", classes(null, "app/Client", "app/Render", "app/World"));
        payload.put("client-b.jar", classes(null, "app/Client", "app/Render", "app/World", "app/Extra"));
        payload.put("bridge.jar", classes(null, "bridge/Hook"));
        // Mostly other names, but one class of the main jar: still left off, since the verifier refuses a class defined twice.
        payload.put("client-c.jar", classes(null, "a/a", "a/b", "app/Client"));
        Path input = Files.write(directory.resolve("variants.exe"), concat(pe(List.of("GetTickCount")), zip(payload)));
        var found = InputIdentifier.identify(input);
        assertEquals("client-b.jar", ((Map<?,?>) found.json().get("bundle")).get("main"), found.text());
        assertTrue(found.text().contains("nested jar libs\\big.jar: 5 classes, ") && found.text().contains("library folder"), found.text());
        assertTrue(found.text().contains("nested jar client-a.jar: 3 classes") && found.text().contains("variant of the main jar"), found.text());
        var resolved = InputIdentifier.resolve(input);
        assertEquals(List.of("libs\\big.jar", "bridge.jar"), List.copyOf(resolved.classpath().keySet()));
        assertEquals(List.of("client-a.jar", "client-c.jar"), resolved.leftOff());
        assertEquals("libs\\big.jar", InputIdentifier.resolve(input, Duration.ofSeconds(10), "big.jar").entry());
    }

    @Test void nestedJarsWithoutClassesAreNoJava() throws Exception {
        byte[] payload = zip(Map.of("a.jar", zip(Map.of("readme.txt", new byte[] {1})), "b.jar", new byte[] {1, 2, 3}));
        var exe = InputIdentifierTest.identify(pe(List.of("GetTickCount"), null, ".rdata2", payload, false, 2), "empty.exe");
        assertEquals(Verdict.NATIVE_NO_JAVA, exe.verdict(), exe.text());
        assertTrue(exe.text().contains("2 nested .jar entries"), exe.text());
        assertFalse(exe.javaAvailable());
        var plain = InputIdentifierTest.identify(payload, "empty.zip");
        assertEquals(Verdict.ARCHIVE_WITHOUT_CLASSES, plain.verdict(), plain.text());
    }

    @Test void inflationIsBoundedByTheRecordedSize() throws Exception {
        byte[] jar = classes(null, "p/A");
        byte[] payload = zip(Map.of("p.jar", jar));
        var archive = InputIdentifier.archives(payload, -1).getFirst();
        var entry = JavaBundle.entries(payload, archive).getFirst();
        assertArrayEquals(jar, JavaBundle.data(payload, archive, entry));
        var lie = new JavaBundle.Entry(entry.name(), entry.flags(), entry.method(), entry.compressed(), entry.size() - 1, entry.local());
        assertNull(JavaBundle.data(payload, archive, lie));
        assertEquals("Gson", JavaBundle.library(List.of("com/google/gson/A", "com/google/gson/B")));
        assertNull(JavaBundle.library(List.of("com/google/gson/A", "app/Main")));
    }
}
