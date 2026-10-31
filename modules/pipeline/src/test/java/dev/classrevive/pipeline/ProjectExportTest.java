package dev.classrevive.pipeline;

import dev.classrevive.cfr.CfrEngine;
import dev.classrevive.vineflower.VineflowerEngine;
import dev.classrevive.verification.VerificationResult;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.List;
import java.util.zip.*;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ProjectExportTest {
    @TempDir Path dir;

    @Test void exportsAProjectWithSourcesResourcesAndABuildFileThatCompiles() throws Exception {
        var source = Files.createDirectories(dir.resolve("src/p"));
        Files.writeString(source.resolve("Config.java"), "package p; public class Config { public String file() { return \"app.properties\"; } }");
        var classes = dir.resolve("classes");
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "--release", "8", "-Xlint:-options", "-proc:none",
            "-d", classes.toString(), source.resolve("Config.java").toString()));
        var jar = dir.resolve("app.jar");
        try (var zip = new ZipOutputStream(Files.newOutputStream(jar))) {
            entry(zip, "META-INF/MANIFEST.MF", "Manifest-Version: 1.0\n".getBytes(StandardCharsets.UTF_8));
            entry(zip, "META-INF/APP.SF", "signature".getBytes(StandardCharsets.UTF_8));
            entry(zip, "p/Config.class", Files.readAllBytes(classes.resolve("p/Config.class")));
            entry(zip, "app.properties", "name=demo\n".getBytes(StandardCharsets.UTF_8));
            entry(zip, "META-INF/services/p.Plugin", "p.Config\n".getBytes(StandardCharsets.UTF_8));
        }
        var output = dir.resolve("out");
        new DecompilationJob().runVerified(jar, output, List.of(new CfrEngine(), new VineflowerEngine()), Duration.ofSeconds(60));
        Path project = output.resolve("project");
        assertTrue(Files.readString(project.resolve("src/main/java/p/Config.java")).contains("app.properties"));
        assertEquals("name=demo\n", Files.readString(project.resolve("src/main/resources/app.properties")));
        assertEquals("p.Config\n", Files.readString(project.resolve("src/main/resources/META-INF/services/p.Plugin")));
        // The manifest and signature files describe the original JAR, not this project.
        assertFalse(Files.exists(project.resolve("src/main/resources/META-INF/MANIFEST.MF")));
        assertFalse(Files.exists(project.resolve("src/main/resources/META-INF/APP.SF")));
        String build = Files.readString(project.resolve("build.gradle"));
        assertTrue(build.contains("id 'java'") && build.contains("options.release = 8"), build);
        assertEquals("rootProject.name = 'app-decompiled'\n", Files.readString(project.resolve("settings.gradle")));
        String readme = Files.readString(project.resolve("README.md"));
        assertTrue(readme.contains("not the original source") && readme.contains("COMPILED"), readme);
        assertTrue(Files.readString(output.resolve("report.txt")).contains("IDE project: project/ (1 sources, 2 resources, 0 libraries, release 8)"));
        // The exported sources compile on their own, as the build file says.
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "--release", "8", "-Xlint:-options", "-proc:none",
            "-d", dir.resolve("rebuilt").toString(), project.resolve("src/main/java/p/Config.java").toString()));
    }

    @Test void refusesResourceNamesThatCouldLeaveTheProjectAndCopiesLibraries() throws Exception {
        var jar = dir.resolve("evil.jar");
        try (var zip = new ZipOutputStream(Files.newOutputStream(jar))) {
            entry(zip, "../escape.txt", new byte[]{1});
            entry(zip, "a/../../escape2.txt", new byte[]{2});
            entry(zip, "C:/abs.txt", new byte[]{3});
            entry(zip, "ok/data.bin", new byte[]{4});
        }
        var library = Files.write(dir.resolve("lib-1.0.jar"), new byte[]{5});
        var output = Files.createDirectory(dir.resolve("out"));
        var verification = new VerificationResult(VerificationResult.Status.COMPILE_FAILED, 2, 0, 3, 0, "test");
        var result = ProjectExport.write(output, output.resolve("missing-sources"), jar, List.of(library), List.of(), verification, "evil.jar");
        assertEquals(1, result.resources()); assertEquals(3, result.skippedResources()); assertEquals(1, result.libraries());
        assertArrayEquals(new byte[]{4}, Files.readAllBytes(output.resolve("project/src/main/resources/ok/data.bin")));
        assertFalse(Files.exists(dir.resolve("escape.txt")) || Files.exists(output.resolve("escape.txt")) || Files.exists(output.resolve("project/escape2.txt")));
        assertTrue(Files.readString(output.resolve("project/build.gradle")).contains("implementation files('lib/lib-1.0.jar')"));
        assertTrue(Files.readString(output.resolve("project/README.md")).contains("2 javac error(s)"));
        assertFalse(ProjectExport.safe("a//b") || ProjectExport.safe("/a") || ProjectExport.safe("a\\b") || ProjectExport.safe("./a"));
    }

    private static void entry(ZipOutputStream zip, String name, byte[] bytes) throws Exception {
        zip.putNextEntry(new ZipEntry(name)); zip.write(bytes); zip.closeEntry();
    }
}
