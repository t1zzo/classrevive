package dev.classrevive.pipeline;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

/**
 * Phase 2CC: the real Ghidra on the generated JNI DLL. Runs only when GHIDRA_INSTALL_DIR names an installation (it is
 * never downloaded); the build stays green without Ghidra. The DLL is analyzed statically and never loaded.
 */
@EnabledIfEnvironmentVariable(named = "GHIDRA_INSTALL_DIR", matches = ".+")
class GhidraIntegrationTest {
    @TempDir Path temp;

    @Test void ghidraAnalyzesTheFixtureDllAndTheExportIsLinked() throws Exception {
        Path jar = NativeFixtures.jar(temp, Map.of("native/win-x64/fixture.dll", NativeFixtures.pe(false)));
        var options = NativeAnalysis.Options.defaults(null, System.getenv()).withLimits(1024 * 1024, 1, Duration.ofMinutes(10));
        var result = new NativeAnalysis().run(jar, temp.resolve("native"), options);
        var library = result.libraries().getFirst();
        assertEquals("ANALYZED", result.status(), result.text() + library.notes());
        var ghidra = library.ghidra();
        assertTrue(ghidra.format().contains("PE"), ghidra.format());
        assertEquals(8, ghidra.pointerSize());
        assertTrue(ghidra.exports("JNI_OnLoad") && ghidra.exports("Java_p_Lib_add__II") && ghidra.exports("Java_p_Lib_name"), ghidra.exports().toString());
        var names = ghidra.jniFunctions().stream().map(GhidraExport.Function::name).toList();
        assertTrue(names.containsAll(List.of("JNI_OnLoad", "Java_p_Lib_add__II", "Java_p_Lib_name")), names.toString());
        assertTrue(ghidra.imports().stream().anyMatch(i -> i.name().equals("GetTickCount")), ghidra.imports().toString());
        assertFalse(ghidra.registerNativesSites().isEmpty(), "call [rax+0x6b8] in JNI_OnLoad's callee");
        assertTrue(ghidra.registerNativesSites().getFirst().instruction().toLowerCase(Locale.ROOT).contains("0x6b8"));
        assertTrue(ghidra.decompiled().stream().anyMatch(d -> d.completed() && d.name().equals("JNI_OnLoad")), ghidra.decompiled().toString());
        var kinds = new TreeSet<String>(); result.links().forEach(l -> kinds.add(l.method().name() + ":" + l.kind()));
        assertTrue(kinds.containsAll(List.of("add:EXPORT", "name:EXPORT")), kinds.toString());
        assertTrue(kinds.contains(NativeFixtures.HEX_NATIVE + ":TABLE"), "JNINativeMethod entry found statically: " + kinds + " " + ghidra.nativeMethodTables());
        assertTrue(result.unresolved().isEmpty(), result.unresolved().toString());
        assertTrue(Files.isRegularFile(temp.resolve("native").resolve(library.directory()).resolve("pseudo-c/01-JNI_OnLoad.c")));
    }
}
