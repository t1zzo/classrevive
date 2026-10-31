package dev.classrevive.pipeline;

import dev.classrevive.cfr.CfrEngine;
import dev.classrevive.pipeline.InputIdentifier.Verdict;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.zip.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static dev.classrevive.pipeline.ExecutableFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

class InputIdentifierTest {
    @TempDir Path directory;

    static InputIdentifier.Identification identify(byte[] bytes, String name) { return InputIdentifier.identify(bytes, name, null); }

    @Test void jarConcatenatedToPeStubIsFoundAndExtractedExactly() throws Exception {
        byte[] jar = jar();
        byte[] exe = concat(pe(List.of("GetTickCount")), jar);
        var found = identify(exe, "app.exe");
        assertEquals(Verdict.EMBEDDED_JAR, found.verdict(), found.text());
        assertTrue(found.format().startsWith("PE32+ x86-64 console executable"), found.format());
        assertTrue(found.selected().location().contains("overlay"), found.selected().location());
        assertEquals(EXTRA, found.selected().offset());
        assertEquals(1, found.selected().classEntries());
        assertTrue(found.javaAvailable());
        assertNull(found.wrapper());
        Path input = Files.write(directory.resolve("app.exe"), exe);
        var resolved = InputIdentifier.resolve(input);
        assertTrue(resolved.jar());
        assertArrayEquals(jar, resolved.bytes());
        try (var zip = new ZipInputStream(new ByteArrayInputStream(resolved.bytes()))) {
            var names = new ArrayList<String>();
            for (ZipEntry entry; (entry = zip.getNextEntry()) != null; ) names.add(entry.getName());
            assertEquals(List.of("META-INF/MANIFEST.MF", "p/Hello.class"), names);
        }
    }

    @Test void launch4jStyleSignedExecutableSeparatesSignatureFromJar() throws Exception {
        byte[] jar = jar();
        byte[] exe = signed(concat(pe(List.of("GetTickCount"), "Launch4j", null, null, false, 2), jar), certificate("ClassRevive Test Signer"));
        var found = identify(exe, "launcher.exe");
        assertEquals(Verdict.EMBEDDED_JAR, found.verdict(), found.text());
        assertEquals("Launch4j", found.wrapper());
        assertTrue(found.format().contains("GUI executable"), found.format());
        String text = found.text();
        assertTrue(text.contains("Authenticode signature (WIN_CERTIFICATE revision 2.0, type PKCS#7 SignedData)"), text);
        assertTrue(text.contains("ClassRevive Test Signer"), text);
        assertTrue(text.contains("data overlay (not the signature)"), text);
        assertTrue(text.contains("by Launch4j"), text);
        // sign4j style: the zip comment is stretched over the signature, so the end record no longer ends the file.
        byte[] stretched = exe.clone();
        int eocd = EXTRA + jar.length - 22;
        int comment = stretched.length - (eocd + 22);
        ByteBuffer.wrap(stretched).order(ByteOrder.LITTLE_ENDIAN).putShort(eocd + 20, (short) comment);
        var sign4j = identify(stretched, "launcher.exe");
        assertEquals(Verdict.EMBEDDED_JAR, sign4j.verdict(), sign4j.text());
        assertEquals(EXTRA, sign4j.selected().offset());
        // Only the signature after the sections: the overlay holds no data.
        var plain = identify(signed(pe(List.of("GetTickCount")), certificate("Signer")), "signed.exe");
        assertEquals(Verdict.NATIVE_NO_JAVA, plain.verdict(), plain.text());
        assertTrue(plain.text().contains("the overlay is only the signature"), plain.text());
    }

    @Test void pkSequencesWithoutCentralDirectoryAreNotArchives() throws Exception {
        byte[] noise = random(64 * 1024, 7);
        var b = ByteBuffer.wrap(noise).order(ByteOrder.LITTLE_ENDIAN);
        for (int at : new int[] {100, 5000, 30000}) b.put(at, (byte) 'P').put(at + 1, (byte) 'K').put(at + 2, (byte) 3).put(at + 3, (byte) 4);
        // A fake end record whose central directory points into noise.
        int e = 60000;
        b.put(e, (byte) 'P').put(e + 1, (byte) 'K').put(e + 2, (byte) 5).put(e + 3, (byte) 6);
        b.putShort(e + 4, (short) 0).putShort(e + 6, (short) 0).putShort(e + 8, (short) 1).putShort(e + 10, (short) 1)
            .putInt(e + 12, 46 + 9).putInt(e + 16, 100).putShort(e + 20, (short) 0);
        var unknown = identify(noise, "data.bin");
        assertTrue(unknown.archives().isEmpty(), unknown.text());
        assertEquals(Verdict.UNKNOWN, unknown.verdict(), unknown.text());
        assertTrue(unknown.text().contains("3 'PK\\x03\\x04' sequence(s) outside every valid archive"), unknown.text());
        var inExe = identify(concat(pe(List.of("GetTickCount")), noise), "tool.exe");
        assertEquals(Verdict.NATIVE_NO_JAVA, inExe.verdict(), inExe.text());
        Path input = Files.write(directory.resolve("tool.exe"), concat(pe(List.of("GetTickCount")), noise));
        var error = assertThrows(InputIdentifier.NoJavaException.class, () -> InputIdentifier.resolve(input));
        assertTrue(error.getMessage().contains("NATIVE_NO_JAVA"), error.getMessage());
    }

    @Test void protectedExecutableWithVlizerAndInjectionImportsIsExplained() throws Exception {
        byte[] exe = pe(List.of("OpenProcess", "VirtualAllocEx", "WriteProcessMemory", "CreateRemoteThread", "GetTickCount"),
            "javaw.exe", ".vlizer", random(128 * 1024, 11), false, 3);
        var found = identify(exe, "loader.exe");
        assertEquals(Verdict.NATIVE_PROTECTED_NO_JAVA, found.verdict(), found.text());
        String text = found.text();
        assertTrue(text.contains("section .vlizer"), text);
        assertTrue(text.contains("Oreans Code Virtualizer"), text);
        assertTrue(text.contains("high-entropy section .vlizer: 131072 bytes"), text);
        assertTrue(text.contains("OpenProcess (import), VirtualAllocEx (import), WriteProcessMemory (import), CreateRemoteThread (import)"), text);
        assertTrue(text.contains("4/4 of open, allocate, write, start thread"), text);
        assertTrue(text.contains("no readable JNI or JVMTI name"), text);
        assertTrue(text.contains("names javaw.exe as text"), text);
        assertTrue(text.contains("possibly encrypted inside the binary or loaded at run time; not recoverable statically"), text);
        assertEquals(Verdict.NATIVE_PROTECTED_NO_JAVA, ((Map<?,?>) Json.decode(Json.encode(found.json()))).get("verdict") instanceof String v
            ? Verdict.valueOf(v) : null);
        Path input = Files.write(directory.resolve("loader.exe"), exe);
        var error = assertThrows(InputIdentifier.NoJavaException.class, () -> InputIdentifier.resolve(input));
        assertTrue(error.getMessage().contains("(NATIVE_PROTECTED_NO_JAVA)"), error.getMessage());
        // Injection imports alone, with low-entropy code, are not "protected".
        var injector = identify(pe(List.of("OpenProcess", "VirtualAllocEx", "WriteProcessMemory", "CreateRemoteThread")), "injector.exe");
        assertEquals(Verdict.NATIVE_NO_JAVA, injector.verdict(), injector.text());
        assertTrue(injector.text().contains("process-injection API set"), injector.text());
    }

    @Test void elfMachOAndNativeImageAreRecognized() {
        var elf = identify(NativeFixtures.elf(), "libfixture.so");
        assertEquals(Verdict.NATIVE_NO_JAVA, elf.verdict(), elf.text());
        assertTrue(elf.format().startsWith("ELF64 x86-64 shared object"), elf.format());
        var machO = ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN).putInt(0, 0xfeedfacf).putInt(4, 0x01000007).putInt(12, 2).array();
        var mac = identify(machO, "tool");
        assertEquals("Mach-O 64 x86-64 executable (macOS)", mac.format());
        assertEquals(Verdict.NATIVE_NO_JAVA, mac.verdict());
        var graal = identify(concat(NativeFixtures.elf(), "com.oracle.svm.core.JavaMainWrapper\0".getBytes(StandardCharsets.US_ASCII)), "app");
        assertEquals(Verdict.NATIVE_IMAGE, graal.verdict(), graal.text());
        assertTrue(graal.explanation().contains("no bytecode exists to recover"), graal.explanation());
    }

    @Test void archivesClassFilesAndOtherFormats() throws Exception {
        byte[] jar = jar();
        assertEquals(Verdict.JAVA_ARCHIVE, identify(jar, "renamed.zip").verdict());
        byte[] hello = classBytes(jar);
        var cls = identify(hello, "Hello.bin");
        assertEquals(Verdict.JAVA_CLASS, cls.verdict());
        assertTrue(cls.format().contains("Java 8"), cls.format());
        var war = identify(zip(Map.of("WEB-INF/classes/p/Hello.class", hello, "WEB-INF/web.xml", new byte[] {1})), "app.war");
        assertEquals(Verdict.JAVA_ARCHIVE_OTHER_LAYOUT, war.verdict(), war.text());
        assertTrue(war.explanation().contains("WEB-INF/classes"), war.explanation());
        var jmod = identify(concat(new byte[] {'J', 'M', 1, 0}, zip(Map.of("classes/p/Hello.class", hello))), "p.jmod");
        assertTrue(jmod.format().startsWith("Java module (jmod"), jmod.format());
        assertEquals(Verdict.JAVA_ARCHIVE_OTHER_LAYOUT, jmod.verdict(), jmod.text());
        var bundle = identify(zip(Map.of("lib/a.jar", jar, "readme.txt", new byte[] {1})), "bundle.zip");
        // Phase 2DB: a zip of jars with classes is a bundle of Java jars (JavaBundleTest has the details).
        assertEquals(Verdict.JAVA_BUNDLE, bundle.verdict(), bundle.text());
        assertTrue(bundle.explanation().contains("take lib/a.jar"), bundle.explanation());
        var dotnet = identify(pe(List.of("_CorExeMain"), null, null, null, true, 3), "managed.exe");
        assertEquals(Verdict.DOTNET, dotnet.verdict(), dotnet.text());
        var nsis = identify(concat(pe(List.of("GetTickCount")), "ï¾­ÞNullsoftInst".getBytes(StandardCharsets.ISO_8859_1), random(4096, 3)), "setup.exe");
        assertEquals(Verdict.INSTALLER, nsis.verdict(), nsis.text());
        assertTrue(nsis.explanation().startsWith("NSIS"), nsis.explanation());
        var msi = identify(concat(new byte[] {(byte) 0xd0, (byte) 0xcf, 0x11, (byte) 0xe0, (byte) 0xa1, (byte) 0xb1, 0x1a, (byte) 0xe1}, new byte[512]), "setup.msi");
        assertEquals(Verdict.INSTALLER, msi.verdict(), msi.text());
        assertTrue(msi.format().contains("Windows Installer"), msi.format());
        var script = identify(concat("#!/bin/sh\nexec java -jar \"$0\" \"$@\"\n".getBytes(StandardCharsets.US_ASCII), jar), "tool.run");
        assertEquals(Verdict.EMBEDDED_JAR, script.verdict(), script.text());
        assertTrue(script.wrapper().startsWith("shell-script stub"), script.wrapper());
        assertTrue(script.format().startsWith("script (#!/bin/sh)"), script.format());
        var random = identify(random(4096, 5), "blob.dat");
        assertEquals(Verdict.UNKNOWN, random.verdict());
    }

    @Test void launcherWithoutEmbeddedJarPointsToTheJarNextToIt() throws Exception {
        Files.createDirectories(directory.resolve("app"));
        Files.write(directory.resolve("app").resolve("main.jar"), jar());
        Path exe = Files.write(directory.resolve("Main.exe"), pe(List.of("GetTickCount"), "launch4j lib/main.jar", null, null, false, 2));
        var found = InputIdentifier.identify(exe);
        assertEquals(Verdict.EXTERNAL_JAR_LAUNCHER, found.verdict(), found.text());
        assertTrue(found.explanation().contains("app/main.jar"), found.explanation());
        assertTrue(found.text().contains(".jar names written in the file: lib/main.jar"), found.text());
    }

    @Test void decompilationTakesTheEmbeddedJarAndRecordsItsOrigin() throws Exception {
        byte[] jar = jar();
        Path exe = Files.write(directory.resolve("app.exe"), signed(concat(pe(List.of("GetTickCount"), "Launch4j", null, null, false, 2), jar),
            certificate("Signer")));
        Path output = directory.resolve("out");
        var job = new DecompilationJob().runVerified(exe, output, List.of(new CfrEngine()), Duration.ofSeconds(60));
        assertTrue(job.selection().verification().complete(), job.selection().verification().detail());
        assertArrayEquals(jar, Files.readAllBytes(output.resolve("input.jar")));
        String origin = Files.readString(output.resolve("input-origin/identification.txt"));
        assertTrue(origin.contains("Verdict: EMBEDDED_JAR"), origin);
        assertTrue(origin.contains("Extracted: bytes 0xe00.."), origin);
        String line = InputIdentifier.attach(output, output.resolve("report.txt"), output.resolve("report.json"));
        assertTrue(line.startsWith("Input origin: PE32+ x86-64 GUI executable"), line);
        assertTrue(line.contains("wrapper: Launch4j"), line);
        assertTrue(Files.readString(output.resolve("report.txt")).contains("Input origin:"));
        var report = (Map<?,?>) Json.decode(Files.readString(output.resolve("report.json")));
        assertEquals("EMBEDDED_JAR", ((Map<?,?>) report.get("input_origin")).get("verdict"));
        assertTrue(Files.exists(output.resolve("selection/sources/p/Hello.java")));
        // A protected executable: refused with what it is, and no output directory.
        Path loader = Files.write(directory.resolve("loader.exe"), pe(List.of("OpenProcess"), null, ".vlizer", random(96 * 1024, 2), false, 3));
        assertThrows(InputIdentifier.NoJavaException.class,
            () -> new DecompilationJob().runVerified(loader, directory.resolve("refused"), List.of(new CfrEngine()), Duration.ofSeconds(60)));
        assertFalse(Files.exists(directory.resolve("refused")));
    }

    private static byte[] classBytes(byte[] jar) throws IOException {
        try (var zip = new ZipInputStream(new ByteArrayInputStream(jar))) {
            for (ZipEntry entry; (entry = zip.getNextEntry()) != null; ) if (entry.getName().endsWith(".class")) return zip.readAllBytes();
        }
        throw new AssertionError("no class");
    }
}
