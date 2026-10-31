package dev.classrevive.pipeline;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NativeAnalysisTest {
    @TempDir Path temp;

    private Path fixtureJar() throws IOException {
        var resources = new LinkedHashMap<String,byte[]>();
        resources.put("native/linux-x64/libfixture.so", NativeFixtures.elf());
        resources.put("native/win-x64/fixture.dll", NativeFixtures.pe(false));
        resources.put("native/readme.txt", "not native".getBytes(StandardCharsets.US_ASCII));
        return NativeFixtures.jar(temp, resources);
    }

    private static NativeAnalysis.Options options(String home, Map<String,String> environment) {
        return NativeAnalysis.Options.defaults(home, environment).withLimits(1024 * 1024, 4, Duration.ofSeconds(60));
    }

    /** A stand-in for analyzeHeadless: records its arguments and copies canned.json (when present) to the export path. */
    private Path fakeGhidra(String canned) throws IOException {
        Path home = Files.createDirectories(temp.resolve("fake-ghidra"));
        Path support = Files.createDirectories(home.resolve("support"));
        Files.createDirectories(home.resolve("Ghidra"));
        Files.writeString(home.resolve("Ghidra/application.properties"), "application.version=9.9-test\n");
        if (canned != null) Files.writeString(support.resolve("canned.json"), canned);
        if (GhidraEngine.windows()) {
            // %0 launcher, %1 project, ... %11 export path; after two shifts the export path is %9.
            Files.writeString(support.resolve("analyzeHeadless.bat"), """
                @echo off\r
                set "HERE=%~dp0"\r
                echo %* >> "%HERE%args.txt"\r
                if not exist "%~1" echo missing-project >> "%HERE%args.txt"\r
                shift\r
                shift\r
                if exist "%HERE%canned.json" copy /y "%HERE%canned.json" "%~9" > nul\r
                exit /b 0\r
                """);
        } else {
            Path script = support.resolve("analyzeHeadless");
            Files.writeString(script, """
                #!/bin/sh
                here=$(dirname "$0")
                echo "$@" >> "$here/args.txt"
                [ -d "$1" ] || echo missing-project >> "$here/args.txt"
                [ -f "$here/canned.json" ] && cp "$here/canned.json" "${11}"
                exit 0
                """);
            Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwxr-xr-x"));
        }
        return home;
    }

    @Test void withoutGhidraTheHeadersStillLinkExports() throws Exception {
        Path jar = fixtureJar();
        var result = new NativeAnalysis().run(jar, temp.resolve("native"), options(null, Map.of()));
        assertEquals("SKIPPED_NOT_CONFIGURED", result.status());
        assertTrue(result.text().startsWith("Native analysis: " + NativeAnalysis.NOT_CONFIGURED + "\n"), result.text());
        assertEquals(2, result.libraries().size());
        // The host platform's library comes first.
        if (GhidraEngine.windows()) assertEquals("native/win-x64/fixture.dll", result.libraries().getFirst().entry());
        assertTrue(result.libraries().stream().allMatch(l -> l.status().equals("HEADERS_ONLY")));
        var linked = result.links().stream().map(l -> l.method().key() + " " + l.kind() + " " + l.symbol()).sorted().toList();
        assertEquals(List.of("p/Lib#add(II)I EXPORT Java_p_Lib_add__II", "p/Lib#add(II)I EXPORT Java_p_Lib_add__II",
            "p/Lib#name()Ljava/lang/String; EXPORT Java_p_Lib_name"), linked);
        assertEquals(Map.of("p/Lib#" + NativeFixtures.HEX_NATIVE + NativeFixtures.HEX_DESCRIPTOR,
            "registered dynamically by JNI_OnLoad (no Java_* export matches)"), result.unresolved());
        assertTrue(result.text().contains("Native layer (2BZ hex-named natives): 0/1 bound statically; the others registered dynamically by JNI_OnLoad"), result.text());
        Path dll = temp.resolve("native").resolve(result.libraries().stream().filter(l -> l.entry().endsWith(".dll")).findFirst().orElseThrow().directory());
        assertArrayEquals(NativeFixtures.pe(false), Files.readAllBytes(dll.resolve("fixture.dll")));
        assertTrue(Files.readString(dll.resolve("functions.txt")).contains("Exports (ClassRevive header reader):\n  JNI_OnLoad\n"));
        assertTrue(Files.isRegularFile(dll.resolve("report.json")));
        assertTrue(Files.readString(temp.resolve("native/summary.txt")).contains(NativeAnalysis.INTERPRETATION));
        var json = (Map<?,?>) Json.decode(Files.readString(temp.resolve("native/report.json")));
        assertEquals("SKIPPED_NOT_CONFIGURED", json.get("status"));
        assertEquals(1L, json.get("obfuscated_native_methods"));
    }

    @Test void fakeGhidraIsRunWithTheIsolatedCommandAndItsExportIsLinked() throws Exception {
        Path jar = fixtureJar();
        Path home = fakeGhidra(GhidraExportTest.sample());
        Files.createDirectory(temp.resolve("out"));
        var result = new NativeAnalysis().run(jar, temp.resolve("out").resolve("native"), options(null, Map.of("GHIDRA_INSTALL_DIR", home.toString())));
        assertEquals("ANALYZED", result.status(), result.text());
        assertEquals("9.9-test", result.ghidraVersion());
        assertTrue(result.libraries().stream().allMatch(l -> l.status().equals("ANALYZED")), result.text());
        String args = Files.readString(home.resolve("support/args.txt"));
        assertFalse(args.contains("missing-project"), args);
        for (String expected : List.of("-import", "-readOnly", "-deleteProject", "-scriptPath", "-postScript", GhidraEngine.SCRIPT,
                "-analysisTimeoutPerFile", "40", "-max-cpu"))
            assertTrue(args.contains(expected), expected + " in " + args);
        assertFalse(args.contains("-noanalysis"), args);
        var table = result.links().stream().filter(l -> l.kind().equals("TABLE")).toList();
        assertEquals(2, table.size());
        assertEquals("p/Lib#" + NativeFixtures.HEX_NATIVE + NativeFixtures.HEX_DESCRIPTOR, table.getFirst().method().key());
        assertEquals("FUN_180001030", table.getFirst().symbol());
        assertTrue(result.unresolved().containsKey("p/Lib#name()Ljava/lang/String;"), result.unresolved().toString());
        assertTrue(result.text().contains("Native layer (2BZ hex-named natives): 1/1 bound statically"), result.text());
        assertTrue(result.text().contains("1 RegisterNatives site(s), 1 JNINativeMethod entry, 1 pseudo-C"), result.text());
        Path library = temp.resolve("out/native").resolve(result.libraries().getFirst().directory());
        assertTrue(Files.readString(library.resolve("pseudo-c/01-JNI_OnLoad.c")).contains("return 0x10006;"));
        assertTrue(Files.readString(library.resolve("pseudo-c/02-Java_p_Lib_add__II.c")).contains("could not decompile"));
        String functions = Files.readString(library.resolve("functions.txt"));
        assertTrue(functions.contains("FUN_180001040 @ 180001043: CALL qword ptr [RAX + 0x6b8]"), functions);
        assertTrue(functions.contains("implements p.Lib#add(II)"), functions);
        assertTrue(Files.readString(library.resolve("strings.txt")).contains(NativeFixtures.HEX_NATIVE));
        assertTrue(Files.isRegularFile(temp.resolve("out/native/ghidra-script").resolve(GhidraEngine.SCRIPT)));
        try (var left = Files.list(library)) {
            assertTrue(left.noneMatch(p -> p.getFileName().toString().endsWith("-project")), "the throwaway project folder is removed");
        }
        // attach adds the section to report.txt and native_analysis to report.json.
        Path out = temp.resolve("out");
        Files.writeString(out.resolve("report.txt"), "ClassRevive\n");
        EvidenceReport.write(out.resolve("report.json"), Map.of("kind", "decompilation"));
        NativeAnalysis.attach(out.resolve("report.txt"), out.resolve("report.json"), result);
        assertTrue(Files.readString(out.resolve("report.txt")).contains("\nNative analysis: Ghidra 9.9-test (static)\n"));
        var report = (Map<?,?>) Json.decode(Files.readString(out.resolve("report.json")));
        assertEquals("decompilation", report.get("kind"));
        assertEquals("ANALYZED", ((Map<?,?>) report.get("native_analysis")).get("status"));
    }

    @Test void aGhidraRunWithoutExportIsRetriedWithoutAnalysisAndReported() throws Exception {
        var resources = Map.of("native/fixture.dll", NativeFixtures.pe(false));
        Path jar = NativeFixtures.jar(temp, resources);
        Path home = fakeGhidra(null);
        var result = new NativeAnalysis().run(jar, temp.resolve("native"), options(home.toString(), Map.of()));
        assertEquals("PARTIAL", result.status());
        var library = result.libraries().getFirst();
        assertEquals("GHIDRA_FAILED", library.status());
        assertEquals("ghidra-noanalysis", library.engine().engine());
        assertTrue(library.notes().stream().anyMatch(n -> n.contains("retried with -noanalysis")), library.notes().toString());
        assertTrue(Files.readString(home.resolve("support/args.txt")).contains("-noanalysis"));
        // The header triage still links the exports.
        assertTrue(result.links().stream().anyMatch(l -> l.symbol().equals("Java_p_Lib_add__II")));
    }

    @Test void limitsAndConfigurationProblems() throws Exception {
        var resources = Map.of("native/fixture.dll", NativeFixtures.pe(false), "native/libjunk.so", new byte[] {1, 2, 3, 4});
        Path jar = NativeFixtures.jar(temp, resources);
        var small = NativeAnalysis.Options.defaults(null, Map.of("GHIDRA_INSTALL_DIR", temp.resolve("nowhere").toString()))
            .withLimits(1024, 1, Duration.ofSeconds(30));
        var result = new NativeAnalysis().run(jar, temp.resolve("native"), small);
        assertEquals("SKIPPED_NOT_CONFIGURED", result.status());
        assertTrue(result.text().contains(NativeAnalysis.NOT_CONFIGURED + "; GHIDRA_INSTALL_DIR does not point to a Ghidra installation"), result.text());
        var byEntry = new TreeMap<String,String>(); result.libraries().forEach(l -> byEntry.put(l.entry(), l.status()));
        assertEquals(Map.of("native/fixture.dll", "SKIPPED_OVERSIZED", "native/libjunk.so", "NOT_NATIVE"), byEntry);
        assertTrue(result.unresolved().values().stream().allMatch(r -> r.startsWith("no bundled native library could be read")));
        assertThrows(IllegalArgumentException.class, () -> new NativeAnalysis().run(jar, temp.resolve("other"),
            options(temp.resolve("nowhere").toString(), Map.of())));
        assertThrows(IllegalArgumentException.class, () -> NativeAnalysis.Options.defaults(null, Map.of()).withLimits(0, 1, Duration.ofSeconds(1)));
    }

    @Test void projectLocationsWithDotElementsAreRefusedByGhidra() {
        assertFalse(GhidraEngine.projectLocationAccepted(temp.resolve(".analysis").resolve("out")));
        assertTrue(GhidraEngine.projectLocationAccepted(temp.resolve("analysis").resolve("out")));
        assertEquals("a_b_c.dll", NativeAnalysis.safe("a/b c.dll", 80));
        assertEquals("library", NativeAnalysis.safe("..", 80));
        assertTrue(NativeAnalysis.nativeName("lib/libz.so.1"));
        assertFalse(NativeAnalysis.nativeName("lib/notes.solution"));
    }
}
