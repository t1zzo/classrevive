package dev.classrevive.pipeline;

import dev.classrevive.cfr.CfrEngine;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.zip.*;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class EmbeddedLibrariesTest {
    @TempDir Path dir;

    private Path jar(String name, Map<String,String> entries) throws Exception {
        Path jar = dir.resolve(name);
        try (var zip = new ZipOutputStream(Files.newOutputStream(jar))) {
            for (var entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey())); zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8)); zip.closeEntry();
            }
        }
        return jar;
    }

    @Test void recognizesRootRelocatedAndMavenDeclaredLibraries() throws Exception {
        var jar = jar("app.jar", Map.of("META-INF/maven/com.google.guava/guava/pom.properties", "groupId=com.google.guava\nartifactId=guava\nversion=33.0-jre\n"));
        var result = EmbeddedLibraries.detect(jar, List.of("app/Main", "app/util/Text", "com/google/common/base/Preconditions",
            "com/google/common/collect/Lists", "app/shaded/com/google/gson/Gson", "app/kotlin/Helper"));
        assertNull(result.self());
        // app/kotlin/Helper is the application's: one-segment prefixes (kotlin/) only count at the root.
        assertEquals(3, result.applicationClasses()); assertEquals(3, result.libraryClasses());
        var guava = result.libraries().getFirst();
        assertEquals("Guava", guava.name()); assertEquals(2, guava.classes()); assertEquals("33.0-jre", guava.version());
        assertTrue(guava.evidence().contains("Maven metadata com.google.guava:guava:33.0-jre"), guava.evidence().toString());
        var gson = result.libraries().get(1);
        assertEquals("Gson", gson.name()); assertEquals(Set.of("app.shaded"), gson.relocatedUnder());
        assertEquals(Set.of("app/shaded/com/google/gson/"), gson.packages());
        assertEquals("Gson", result.libraryOf("app/shaded/com/google/gson/Gson")); assertNull(result.libraryOf("app/Main"));
        String text = EmbeddedLibraries.text(result);
        assertTrue(text.startsWith("Embedded libraries: 3 of 6 classes belong to 2 recognized libraries; 3 are the application's own"), text);
        assertTrue(Json.encode(EmbeddedLibraries.json(result)).contains("\"relocated_under\":[\"app.shaded\"]"));
    }

    @Test void aLibraryDecompiledOnItsOwnIsNotEmbeddedInItself() throws Exception {
        var jar = jar("gson.jar", Map.of("META-INF/maven/com.google.code.gson/gson/pom.properties", "groupId=com.google.code.gson\nartifactId=gson\nversion=2.13.1\n"));
        var result = EmbeddedLibraries.detect(jar, List.of("com/google/gson/Gson", "com/google/gson/JsonObject"));
        assertEquals("Gson", result.self()); assertEquals(List.of(), result.libraries());
        assertEquals(2, result.applicationClasses()); assertEquals(0, result.libraryClasses());
        assertEquals("Embedded libraries: none (the input itself is Gson)\n", EmbeddedLibraries.text(result));
    }

    @Test void reportsAndFoldsABundledLibraryInARealJob() throws Exception {
        var source = Files.createDirectories(dir.resolve("src"));
        Files.createDirectories(source.resolve("app")); Files.createDirectories(source.resolve("org/json"));
        Files.writeString(source.resolve("app/Main.java"), "package app; public class Main { public String run() { return new org.json.Mini().text(); } }");
        Files.writeString(source.resolve("org/json/Mini.java"), "package org.json; public class Mini { public String text() { return \"{}\"; } }");
        var classes = dir.resolve("classes");
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "--release", "8", "-Xlint:-options", "-proc:none",
            "-d", classes.toString(), source.resolve("app/Main.java").toString(), source.resolve("org/json/Mini.java").toString()));
        var input = dir.resolve("bundle.jar");
        try (var zip = new ZipOutputStream(Files.newOutputStream(input))) {
            for (String name : List.of("app/Main.class", "org/json/Mini.class")) {
                zip.putNextEntry(new ZipEntry(name)); zip.write(Files.readAllBytes(classes.resolve(name))); zip.closeEntry();
            }
        }
        var output = dir.resolve("out");
        new DecompilationJob().runVerified(input, output, List.of(new CfrEngine()), Duration.ofSeconds(60));
        String json = Files.readString(output.resolve("report.json"));
        assertTrue(json.contains("\"embedded_libraries\":{\"application_classes\":1,"), json);
        assertTrue(json.contains("\"name\":\"JSON-java\""), json);
        assertTrue(Files.readString(output.resolve("report.txt")).contains("Embedded libraries: 1 of 2 classes belong to 1 recognized libraries"));
        String html = Files.readString(output.resolve("report.html"));
        assertTrue(html.contains("Application code") && html.contains("library: JSON-java") && html.contains("<details class=\"library\">"), html);
        // Phase 2CJ: the same job names its compiler (javac 21 writes no lambda or enum here, so the Java compiler stays undetermined).
        assertTrue(json.contains("\"compiler\":{") && json.contains("\"language\":\"Java\""), json);
        assertTrue(Files.readString(output.resolve("report.txt")).contains("Compiler fingerprint: Java"));
        assertTrue(html.contains(">Compiler<"), html);
    }
}
