package dev.classrevive.pipeline;

import dev.classrevive.cfr.CfrEngine;
import dev.classrevive.pipeline.InputIdentifier.Verdict;
import java.io.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.zip.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Phase 2CW: APK and dex inputs, on the committed fixtures (benchmarks/android) and archives built here. Nothing is run. */
class AndroidInputTest {
    static final Path FIXTURES = Path.of("../../benchmarks/android");
    @TempDir Path directory;

    @Test void apkAndDexAreRecognizedAndAppBundlesAreNamed() throws Exception {
        var apk = InputIdentifier.identify(FIXTURES.resolve("notes.apk"));
        assertEquals(Verdict.ANDROID_PACKAGE, apk.verdict(), apk.text());
        assertEquals("Android package (APK: 2 dex file(s) at the root, AndroidManifest.xml)", apk.format());
        assertTrue(apk.javaAvailable() && apk.dalvik());
        assertTrue(apk.explanation().contains("dex2jar 2.4.38"), apk.explanation());
        assertTrue(apk.json().toString().contains("root_dex=2"), apk.json().toString());

        var dex = InputIdentifier.identify(FIXTURES.resolve("notes.dex"));
        assertEquals(Verdict.DEX_FILE, dex.verdict(), dex.text());
        assertEquals("Dalvik executable (dex 035, Android)", dex.format());
        assertTrue(dex.text().contains("6 class definitions in the header"), dex.text());
        // A dex whose header size does not match the file is not taken for one.
        byte[] cut = Arrays.copyOf(Files.readAllBytes(FIXTURES.resolve("notes.dex")), 4000);
        assertEquals(Verdict.UNKNOWN, InputIdentifierTest.identify(cut, "cut.dex").verdict());

        byte[] dexBytes = Files.readAllBytes(FIXTURES.resolve("notes.dex"));
        var bundle = InputIdentifierTest.identify(zip(Map.of("base/dex/classes.dex", dexBytes, "base/manifest/AndroidManifest.xml", new byte[8],
            "BundleConfig.pb", new byte[4])), "app.aab");
        assertEquals(Verdict.ANDROID_BUNDLE, bundle.verdict(), bundle.text());
        assertFalse(bundle.javaAvailable());
        assertTrue(bundle.explanation().contains("bundletool"), bundle.explanation());
        var splits = InputIdentifierTest.identify(zip(Map.of("base.apk", new byte[30], "split_config.arm64_v8a.apk", new byte[30])), "app.apks");
        assertEquals(Verdict.ANDROID_BUNDLE, splits.verdict(), splits.text());
        Path splitsFile = Files.write(directory.resolve("app.xapk"), zip(Map.of("base.apk", new byte[30], "split.apk", new byte[30])));
        assertThrows(InputIdentifier.NoJavaException.class, () -> InputIdentifier.resolve(splitsFile));

        // A dex under assets/ is data: reported, not taken for the app's code.
        var payload = InputIdentifierTest.identify(zip(Map.of("classes.dex", dexBytes, "assets/payload.dex", dexBytes)), "app.apk");
        assertEquals(Verdict.ANDROID_PACKAGE, payload.verdict());
        assertTrue(payload.signals().stream().anyMatch(s -> s.startsWith("1 .dex file(s) outside the archive root")), payload.signals().toString());
        // Class files win: a library jar that also carries a classes.dex is still a jar.
        byte[] classFile = classFile();
        var jar = InputIdentifierTest.identify(zip(Map.of("p/Hello.class", classFile, "classes.dex", dexBytes)), "lib.zip");
        assertEquals(Verdict.JAVA_ARCHIVE, jar.verdict(), jar.text());
    }

    @Test void anApkIsConvertedDecompiledAndItsOriginRecorded() throws Exception {
        Path apk = Files.copy(FIXTURES.resolve("notes.apk"), directory.resolve("notes.apk"));
        Path output = directory.resolve("out");
        var job = new DecompilationJob().runVerified(apk, output, List.of(new CfrEngine()), Duration.ofSeconds(180));
        assertTrue(Files.exists(output.resolve("selection/sources/demo/notes/NoteStore.java")));
        assertTrue(Files.exists(output.resolve("selection/sources/demo/util/Strings.java")), "classes2.dex is decompiled too");
        assertTrue(job.selection().verification().complete(), job.selection().verification() + "\n" + Files.readString(output.resolve("selection/selection.txt")));
        try (var zip = new ZipFile(output.resolve("input.jar").toFile())) {
            assertNotNull(zip.getEntry("demo/notes/MainActivity.class"));
            assertNull(zip.getEntry("classes.dex"));
        }
        String origin = Files.readString(output.resolve("input-origin/identification.txt"));
        assertTrue(origin.contains("Verdict: ANDROID_PACKAGE"), origin);
        assertTrue(origin.contains("Converted: Dalvik bytecode (2 dex file(s)) to input.jar by dex2jar 2.4.38"), origin);
        assertTrue(origin.contains("7 classes, 0 untranslated method(s), 0 duplicate class(es) dropped"), origin);
        String conversion = Files.readString(output.resolve("input-origin/conversion.txt"));
        assertTrue(conversion.contains("classes2.dex: 1028 bytes, 1 classes kept"), conversion);
        String line = InputIdentifier.attach(output, output.resolve("report.txt"), output.resolve("report.json"));
        assertTrue(line.startsWith("Input origin: Android package (APK"), line);
        assertTrue(line.contains("converted from Dalvik bytecode by dex2jar 2.4.38 (7 classes, 0 untranslated methods)"), line);
        var report = (Map<?,?>) Json.decode(Files.readString(output.resolve("report.json")));
        var recorded = (Map<?,?>) ((Map<?,?>) report.get("input_origin")).get("conversion");
        assertEquals("the converted class files, not the dex", recorded.get("measured_against"));
    }

    /** Phase 2CX: JADX reads the original dex files; CFR reads the converted classes; both are measured the same way. */
    @Test void jadxReadsTheOriginalDexFiles() throws Exception {
        Path apk = Files.copy(FIXTURES.resolve("notes.apk"), directory.resolve("notes.apk"));
        Path output = directory.resolve("out");
        var job = new DecompilationJob().runVerified(apk, output, List.of(new CfrEngine(), new dev.classrevive.jadx.JadxEngine()),
            Duration.ofSeconds(240));
        var jadx = job.engines().stream().filter(result -> result.engine().equals("jadx")).findFirst().orElseThrow();
        assertEquals(dev.classrevive.engine.EngineResult.Status.OUTPUT_PRODUCED, jadx.status(), jadx.message());
        try (var zip = new ZipFile(output.resolve("input-origin/dalvik.zip").toFile())) {
            assertEquals(List.of("classes.dex", "classes2.dex"), zip.stream().map(ZipEntry::getName).toList());
        }
        assertTrue(Files.exists(output.resolve("jadx/sources/demo/notes/NoteStore.java")));
        assertTrue(Files.exists(output.resolve("jadx/sources/demo/util/Strings.java")), "classes2.dex reached JADX too");
        assertTrue(Files.readString(output.resolve("report.txt"))
            .contains("Dalvik input (Phase 2CX): jadx read the original dex files (input-origin/dalvik.zip)"));
        assertTrue(job.selection().verification().complete(), job.selection().verification().detail());
    }

    /** Phase 2CX: a local SDK's android.jar (here a stand-in built from the fixture's stubs) replaces inferred android.* stubs. */
    @Test void aLocalSdkPlatformIsTheAndroidFrameworkForJavac() throws Exception {
        Path classes = Files.createDirectories(directory.resolve("framework"));
        var sources = new ArrayList<String>(List.of("--release", "8", "-Xlint:-options", "-proc:none", "-d", classes.toString()));
        try (var files = Files.walk(FIXTURES.resolve("stubs"))) { files.filter(f -> f.toString().endsWith(".java")).forEach(f -> sources.add(f.toString())); }
        assertEquals(0, javax.tools.ToolProvider.getSystemJavaCompiler().run(null, null, null, sources.toArray(String[]::new)));
        Path platforms = Files.createDirectories(directory.resolve("sdk/platforms"));
        Files.createDirectories(platforms.resolve("android-29"));
        Files.write(platforms.resolve("android-29/android.jar"), jarOf(classes));
        Files.createDirectories(platforms.resolve("android-33"));
        Files.write(platforms.resolve("android-33/android.jar"), jarOf(classes));
        Files.createDirectories(platforms.resolve("android-35"));   // no android.jar: an incomplete install is skipped
        assertEquals(33, AndroidSdk.newest(directory.resolve("sdk"), "test").orElseThrow().apiLevel());
        System.setProperty(AndroidSdk.PROPERTY, directory.resolve("sdk").toString());
        try {
            Path apk = Files.copy(FIXTURES.resolve("notes.apk"), directory.resolve("notes.apk"));
            Path output = directory.resolve("out");
            var job = new DecompilationJob().runVerified(apk, output, List.of(new CfrEngine()), Duration.ofSeconds(180));
            assertTrue(job.selection().verification().complete(), job.selection().verification().detail());
            assertTrue(Files.readString(output.resolve("report.txt")).contains(
                "Android framework for javac (Phase 2CX): Android SDK platform android-33 (found through -Dclassrevive.android.sdk)"));
            Path stubs = output.resolve("stubs/stubs.txt");
            assertFalse(Files.exists(stubs) && Files.readString(stubs).contains("android/app/Activity"), "the SDK supplies android.*");
        } finally {
            System.clearProperty(AndroidSdk.PROPERTY);
        }
    }

    private static byte[] jarOf(Path classes) throws IOException {
        var entries = new TreeMap<String, byte[]>();
        try (var files = Files.walk(classes)) {
            for (Path file : files.filter(Files::isRegularFile).toList())
                entries.put(classes.relativize(file).toString().replace('\\', '/'), Files.readAllBytes(file));
        }
        return zip(entries);
    }

    @Test void aDexFileIsDecompiledToo() throws Exception {
        Path dex = Files.copy(FIXTURES.resolve("notes.dex"), directory.resolve("classes.dex"));
        Path output = directory.resolve("out");
        new DecompilationJob().runVerified(dex, output, List.of(new CfrEngine()), Duration.ofSeconds(180));
        assertTrue(Files.exists(output.resolve("selection/sources/demo/notes/Note.java")));
        assertTrue(Files.readString(output.resolve("input-origin/identification.txt")).contains("Verdict: DEX_FILE"));
    }

    static byte[] zip(Map<String, byte[]> entries) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(bytes)) {
            for (var entry : new TreeMap<>(entries).entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    private static byte[] classFile() throws IOException {
        try (var zip = new ZipInputStream(new ByteArrayInputStream(ExecutableFixtures.jar()))) {
            for (ZipEntry entry; (entry = zip.getNextEntry()) != null; ) if (entry.getName().endsWith(".class")) return zip.readAllBytes();
        }
        throw new AssertionError("no class");
    }
}
