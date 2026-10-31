package dev.classrevive.pipeline;

import static dev.classrevive.pipeline.AxmlFixture.*;
import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Phase 2CY: the binary manifest decoder, native library triage, and the SDK platform chosen by targetSdkVersion. */
class AndroidPackageTest {
    @TempDir Path directory;

    static List<Object> manifest() {
        return List.of(
            new Start("manifest", List.of(plain("package", "demo.notes"), android("versionCode", 0x0101021b, 7),
                android("versionName", 0x0101021c, "1.2"))),
            new Start("uses-sdk", List.of(android("minSdkVersion", 0x0101020c, 21), android("targetSdkVersion", 0x01010270, 30))), new End("uses-sdk"),
            new Start("uses-permission", List.of(android("name", 0x01010003, "android.permission.INTERNET"))), new End("uses-permission"),
            new Start("uses-permission", List.of(android("name", 0x01010003, "android.permission.CAMERA"))), new End("uses-permission"),
            new Start("application", List.of(android("debuggable", 0x0101000f, true))),
            new Start("activity", List.of(android("name", 0x01010003, ".SettingsActivity"))), new End("activity"),
            new Start("activity", List.of(android("name", 0x01010003, ".MainActivity"))),
            new Start("intent-filter", List.of()),
            new Start("action", List.of(android("name", 0x01010003, "android.intent.action.MAIN"))), new End("action"),
            new Start("category", List.of(android("name", 0x01010003, "android.intent.category.LAUNCHER"))), new End("category"),
            new End("intent-filter"),
            new End("activity"),
            new Start("service", List.of(android("name", 0x01010003, "demo.notes.SyncService"))), new End("service"),
            new End("application"),
            new End("manifest"));
    }

    @Test void decodesTheManifestAndItsLauncher() {
        var decoded = AndroidPackage.decode(encode(manifest(), false));
        assertEquals("demo.notes", decoded.packageName());
        assertEquals("7", decoded.versionCode());
        assertEquals("1.2", decoded.versionName());
        assertEquals(21, decoded.minSdk());
        assertEquals(30, decoded.targetSdk());
        assertEquals(List.of("android.permission.CAMERA", "android.permission.INTERNET"), decoded.permissions());
        assertEquals(List.of("demo.notes.SettingsActivity", "demo.notes.MainActivity"), decoded.components().get("activity"));
        assertEquals(List.of("demo.notes.SyncService"), decoded.components().get("service"));
        assertEquals("demo.notes.MainActivity", decoded.launcher(), "the activity whose intent filter has MAIN and LAUNCHER");
        assertTrue(decoded.debuggable());
        assertTrue(decoded.problems().isEmpty(), decoded.problems().toString());
        assertTrue(decoded.xml().startsWith("<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\" package=\"demo.notes\""), decoded.xml());
        assertTrue(decoded.xml().contains("    <activity android:name=\".MainActivity\">"), decoded.xml());
    }

    @Test void strippedAttributeNamesAreRecoveredFromTheResourceMap() {
        var decoded = AndroidPackage.decode(encode(manifest(), true));
        assertEquals(30, decoded.targetSdk());
        assertEquals("demo.notes.MainActivity", decoded.launcher());
        assertEquals(2, decoded.permissions().size());
    }

    @Test void malformedInputEndsDecodingWithAProblemNotAnException() {
        byte[] good = encode(manifest(), false);
        var cut = AndroidPackage.decode(Arrays.copyOf(good, good.length / 2));
        assertFalse(cut.problems().isEmpty());
        var placeholder = AndroidPackage.decode(new byte[] {3, 0, 8, 0, 8, 0, 0, 0});
        assertNull(placeholder.packageName());
        assertTrue(placeholder.problems().isEmpty(), "an empty document is valid");
        var text = AndroidPackage.decode("<manifest/>".getBytes());
        assertTrue(text.problems().getFirst().startsWith("not Android binary XML"), text.problems().toString());
        var random = new byte[4096];
        new Random(7).nextBytes(random);
        random[0] = 3; random[1] = 0; random[2] = 8; random[3] = 0;
        assertDoesNotThrow(() -> AndroidPackage.decode(random));
    }

    @Test void anApkReportsItsManifestAndNativeLibraries() throws Exception {
        byte[] dex = Files.readAllBytes(AndroidInputTest.FIXTURES.resolve("notes.dex"));
        var entries = new TreeMap<String, byte[]>();
        entries.put("classes.dex", dex);
        entries.put("AndroidManifest.xml", encode(manifest(), false));
        entries.put("lib/arm64-v8a/libnotes.so", NativeFixtures.elf());
        entries.put("lib/x86_64/libnotes.so", NativeFixtures.elf());
        Path apk = Files.write(directory.resolve("notes.apk"), AndroidInputTest.zip(entries));
        var found = InputIdentifier.identify(apk);
        assertEquals(InputIdentifier.Verdict.ANDROID_PACKAGE, found.verdict(), found.text());
        String text = found.text();
        assertTrue(text.contains("Android package demo.notes, version 1.2 (code 7), minSdk 21, targetSdk 30"), text);
        assertTrue(text.contains("components: 2 activities, 1 service; launcher demo.notes.MainActivity"), text);
        assertTrue(text.contains("2 permission(s) requested: android.permission.CAMERA, android.permission.INTERNET"), text);
        assertTrue(text.contains("android:debuggable=\"true\""), text);
        assertTrue(text.contains("native libraries lib/arm64-v8a: 1 .so (ELF64 x86-64"), text);
        assertTrue(text.contains("not decompiled to Java"), text);
        var json = found.json();
        assertFalse(json.containsKey(InputIdentifier.MANIFEST_TEXT), "the decoded XML is written apart");
        assertEquals(30L, ((Map<?, ?>) json.get("android_manifest")).get("target_sdk"));
        assertEquals(2, ((List<?>) json.get("native_libraries")).size());

        var resolved = InputIdentifier.resolve(apk);
        Path output = Files.createDirectories(directory.resolve("out"));
        InputIdentifier.record(output, resolved);
        String manifest = Files.readString(output.resolve("input-origin/AndroidManifest.xml"));
        assertTrue(manifest.contains("<uses-sdk android:minSdkVersion=\"21\" android:targetSdkVersion=\"30\">"), manifest);
    }

    @Test void theSdkPlatformFollowsTheTargetLevel() {
        var installed = List.of(new AndroidSdk.Platform(Path.of("29.jar"), 29, "t"), new AndroidSdk.Platform(Path.of("33.jar"), 33, "t"),
            new AndroidSdk.Platform(Path.of("34.jar"), 34, "t"));
        assertEquals(33, AndroidSdk.choose(installed, 30).orElseThrow().apiLevel(), "the oldest platform at or above the target");
        assertEquals(29, AndroidSdk.choose(installed, 29).orElseThrow().apiLevel());
        assertEquals(34, AndroidSdk.choose(installed, 35).orElseThrow().apiLevel(), "nothing newer: the newest");
        assertEquals(34, AndroidSdk.choose(installed, null).orElseThrow().apiLevel());
        assertTrue(AndroidSdk.choose(List.of(), 30).isEmpty());
    }
}
