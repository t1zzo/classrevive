package dev.classrevive.pipeline;

import dev.classrevive.cfr.CfrEngine;
import dev.classrevive.verification.VerificationResult;
import dev.classrevive.vineflower.VineflowerEngine;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.zip.*;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class StubJobTest {
    @TempDir Path dir;

    /** An application JAR whose library (lib/*) is missing, as with a mod decompiled without its game. */
    private Path applicationOnly() throws Exception {
        var library = Files.createDirectories(dir.resolve("src/lib"));
        Files.writeString(library.resolve("Base.java"), "package lib; public abstract class Base { protected Base(String name) {} public int size() { return 0; } public abstract void tick(); }");
        Files.writeString(library.resolve("Registry.java"), "package lib; public class Registry { public static Registry get() { return null; } public void add(String key, int value) {} }");
        var app = Files.createDirectories(dir.resolve("src/app"));
        Files.writeString(app.resolve("Mod.java"), "package app; import lib.*; public class Mod extends Base { public Mod() { super(\"mod\"); }"
            + " @Override public void tick() { Registry.get().add(\"ticks\", size()); } public int twice() { return size() * 2; } }");
        var classes = dir.resolve("classes");
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "--release", "8", "-g", "-Xlint:-options", "-proc:none", "-d",
            classes.toString(), library.resolve("Base.java").toString(), library.resolve("Registry.java").toString(), app.resolve("Mod.java").toString()));
        var jar = dir.resolve("mod.jar");
        try (var zip = new ZipOutputStream(Files.newOutputStream(jar))) {
            zip.putNextEntry(new ZipEntry("app/Mod.class")); zip.write(Files.readAllBytes(classes.resolve("app/Mod.class"))); zip.closeEntry();
        }
        return jar;
    }

    @Test void compilesAgainstInferredStubsAndSaysSo() throws Exception {
        var jar = applicationOnly();
        var engines = List.<dev.classrevive.engine.DecompilerEngine>of(new CfrEngine(), new VineflowerEngine());
        var without = new DecompilationJob().withStubs(false).runVerified(jar, dir.resolve("without"), engines, Duration.ofSeconds(60));
        assertEquals(VerificationResult.Status.COMPILE_FAILED, without.selection().verification().status());
        assertFalse(Files.exists(dir.resolve("without/stubs")));
        var with = new DecompilationJob().runVerified(jar, dir.resolve("with"), engines, Duration.ofSeconds(60));
        assertEquals(VerificationResult.Status.COMPILED, with.selection().verification().status(), with.selection().verification().detail());
        assertTrue(with.selection().verification().complete());
        // Phase 2DF: still complete, but marked as compiled against stubs, and the bytecode fallback is kept.
        assertTrue(with.selection().stubBacked());
        assertNotNull(with.fallback());
        assertFalse(without.selection().stubBacked());
        assertTrue(Files.readString(dir.resolve("with/report.json")).contains("\"stub_backed\":true"));
        assertTrue(Files.readString(dir.resolve("with/report.txt")).contains("Result: COMPILED_WITH_SIGNATURE_COVERAGE\nStub-backed: yes"));
        assertTrue(Files.readString(dir.resolve("with/report.html")).contains("Stub-backed: compiled against 2 inferred stub classes"));
        assertTrue(Files.readString(dir.resolve("with/report.html")).contains("the bytecode fallback is kept"));
        assertEquals(List.of("lib/Base", "lib/Registry"), Files.readAllLines(dir.resolve("with/stubs/stubs.txt")));
        String json = Files.readString(dir.resolve("with/report.json"));
        assertTrue(json.contains("\"missing_type_stubs\":{\"classes\":2,"), json);
        assertTrue(Files.readString(dir.resolve("with/report.txt")).contains("Inferred stubs: 2 missing class(es) in 1 package(s)"));
        // The exported project carries the stubs as a labeled library, so it builds too.
        assertTrue(Files.isRegularFile(dir.resolve("with/project/lib/" + DecompilationJob.STUB_JAR)));
        assertTrue(Files.readString(dir.resolve("with/project/README.md")).contains("inferred stand-ins"));
    }

    @Test void stubsAreStillInferredWithSixteenDependencyJars() throws Exception {
        // Phase 2DF: 16 unrelated dependency JARs used to skip stub synthesis silently.
        var jar = applicationOnly();
        var dependencies = new ArrayList<Path>();
        for (int i = 0; i < 16; i++) {
            var writer = new org.objectweb.asm.ClassWriter(0);
            writer.visit(org.objectweb.asm.Opcodes.V1_8, org.objectweb.asm.Opcodes.ACC_PUBLIC, "unrelated/D" + i, null, "java/lang/Object", null);
            writer.visitEnd();
            var dependency = dir.resolve("dep" + i + ".jar");
            try (var zip = new ZipOutputStream(Files.newOutputStream(dependency))) {
                zip.putNextEntry(new ZipEntry("unrelated/D" + i + ".class")); zip.write(writer.toByteArray()); zip.closeEntry();
            }
            dependencies.add(dependency);
        }
        var job = new DecompilationJob().runVerified(jar, dir.resolve("deps"), List.of(new CfrEngine()), Duration.ofSeconds(60), dependencies);
        assertEquals(List.of("lib/Base", "lib/Registry"), Files.readAllLines(dir.resolve("deps/stubs/stubs.txt")));
        assertEquals(VerificationResult.Status.COMPILED, job.selection().verification().status(), job.selection().verification().detail());
        assertTrue(job.selection().stubBacked());
    }
}
