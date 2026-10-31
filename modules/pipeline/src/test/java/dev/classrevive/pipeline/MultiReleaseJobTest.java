package dev.classrevive.pipeline;

import dev.classrevive.cfr.CfrEngine;
import dev.classrevive.engine.DecompilerEngine;
import java.io.IOException;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.zip.*;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** A minimal multi-release JAR built here (base Java 8 class, Java 9 variant and module descriptor); nothing runs. */
class MultiReleaseJobTest {
    @TempDir Path directory;
    List<DecompilerEngine> engines() { return List.of(new CfrEngine()); }

    Path compile(String release, String folder, Map<String,String> sources) throws Exception {
        Path root = Files.createDirectories(directory.resolve(folder)); var paths = new ArrayList<String>();
        for (var source : sources.entrySet()) {
            Path path = root.resolve("src").resolve(source.getKey()); Files.createDirectories(path.getParent());
            Files.writeString(path, source.getValue()); paths.add(path.toString());
        }
        var args = new ArrayList<>(List.of("--release", release, "-proc:none", "-d", root.resolve("classes").toString())); args.addAll(paths);
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, args.toArray(String[]::new)));
        return root.resolve("classes");
    }
    /** {@code entries} maps archive paths to compiled class files; the manifest declares Multi-Release. */
    Path jar(String name, Map<String,Path> entries) throws Exception {
        Path jar = directory.resolve(name);
        try (var zip = new ZipOutputStream(Files.newOutputStream(jar))) {
            zip.putNextEntry(new ZipEntry("META-INF/MANIFEST.MF"));
            zip.write("Manifest-Version: 1.0\r\nMulti-Release: true\r\n\r\n".getBytes()); zip.closeEntry();
            for (var entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey())); zip.write(Files.readAllBytes(entry.getValue())); zip.closeEntry();
            }
        }
        return jar;
    }
    Path base() throws Exception {
        return compile("8", "base", Map.of("p/A.java", "package p; public class A { public int value() { return 1; } }"));
    }
    Path nine() throws Exception {
        return compile("9", "nine", Map.of("module-info.java", "module demo { exports p; }",
            "p/A.java", "package p; public class A { public int value() { return 9; } }"));
    }

    @Test void decompilesTheBaseVersionAndReportsIgnoredVariants() throws Exception {
        Path base = base(), nine = nine();
        Path input = jar("multi.jar", new TreeMap<>(Map.of("p/A.class", base.resolve("p/A.class"),
            "META-INF/versions/9/p/A.class", nine.resolve("p/A.class"),
            "META-INF/versions/9/module-info.class", nine.resolve("module-info.class"))));
        Path out = directory.resolve("out");
        var job = new DecompilationJob().runVerified(input, out, engines(), Duration.ofSeconds(30));
        assertTrue(job.selection().verification().complete(), job.selection().toString());
        try (var zip = new ZipFile(out.resolve("classes.jar").toFile())) {
            assertEquals(List.of("p/A.class"), zip.stream().map(ZipEntry::getName).toList());
        }
        assertTrue(Files.readString(out.resolve("cfr/sources/p/A.java")).contains("return 1;"));
        String report = Files.readString(out.resolve("report.txt"));
        assertTrue(report.contains("Input classes: 1"), report);
        assertTrue(report.contains("Multi-release: base version decompiled; 2 META-INF/versions variant class(es) ignored (manifest Multi-Release: true)"), report);
        assertTrue(report.contains("META-INF/versions/9/module-info.class (Java 9, no base class)"), report);
        assertTrue(report.contains("META-INF/versions/9/p/A.class (Java 9, replaces base class)"), report);
        String json = Files.readString(out.resolve("report.json"));
        assertTrue(json.contains("BASE_VERSION_DECOMPILED_VARIANTS_IGNORED"), json);
        assertTrue(json.contains("\"manifest_multi_release\":true"), json);
        assertTrue(json.contains("\"has_base_class\":false"), json);
    }
    @Test void stillRejectsVersionedEntriesThatDoNotNameTheirClass() throws Exception {
        Path base = base(), nine = nine();
        for (String path : List.of("META-INF/versions/9/p/B.class", "META-INF/versions/8/p/A.class", "META-INF/versions/09/p/A.class")) {
            Path input = jar("bad-" + Math.abs(path.hashCode()) + ".jar", new TreeMap<>(Map.of("p/A.class", base.resolve("p/A.class"), path, nine.resolve("p/A.class"))));
            Path out = directory.resolve("out-" + Math.abs(path.hashCode()));
            var error = assertThrows(IOException.class, () -> new DecompilationJob().run(input, out, engines(), Duration.ofSeconds(30)));
            assertTrue(error.getMessage().contains("invalid multi-release variant: " + path), error.getMessage());
            assertFalse(Files.exists(out.resolve("cfr")));
        }
    }
    @Test void renameStillRefusesMultiReleaseArchivesWithAReason() throws Exception {
        Path base = base(), nine = nine();
        Path input = jar("rename.jar", new TreeMap<>(Map.of("p/A.class", base.resolve("p/A.class"),
            "META-INF/versions/9/module-info.class", nine.resolve("module-info.class"))));
        Path out = directory.resolve("rename");
        var error = assertThrows(IOException.class, () -> new RenameJob().run(input, out, Duration.ofSeconds(20)));
        assertTrue(error.getMessage().contains("unsupported by rename (1 META-INF/versions entries)"), error.getMessage());
        assertFalse(Files.exists(out.resolve("worker")));
    }
}
