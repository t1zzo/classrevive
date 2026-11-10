package dev.classrevive.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.SimpleRemapper;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import javax.tools.ToolProvider;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 2DK: {@code rename --yguard-log} over a jar renamed in yGuard's default style (one-character names outside ASCII)
 * with a log in yGuard's {@code <map>} format, gzipped as yGuard can write it. The renaming is done here with ASM; no yGuard
 * output is used. Classes are only defined and linked.
 */
class YguardLogRenameTest {
    @TempDir Path directory;
    ByteArrayOutputStream output = new ByteArrayOutputStream(), errors = new ByteArrayOutputStream();

    @Test void restoresClassesNestedClassesAndPrivateMembersFromAYguardLog() throws Exception {
        var src = Files.createDirectories(directory.resolve("src/demo"));
        Files.writeString(src.resolve("Ledger.java"), """
            package demo;
            public class Ledger {
                private int balance;
                private String owner = "nobody";
                private int add(int amount) { balance += amount; return balance; }
                public int deposit(int amount) { return add(amount) + new Entry().weight(); }
                static class Entry { private int size = 2; int weight() { return size; } }
            }
            """);
        var classes = Files.createDirectories(directory.resolve("classes"));
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "--release", "8", "-Xlint:-options", "-proc:none", "-d",
            classes.toString(), src.resolve("Ledger.java").toString()));
        var mapping = new HashMap<String,String>();
        mapping.put("demo/Ledger", "demo/Ä"); mapping.put("demo/Ledger$Entry", "demo/Ä$ß");
        mapping.put("demo/Ledger.balance", "ą"); mapping.put("demo/Ledger.owner", "ć");
        mapping.put("demo/Ledger.add(I)I", "ę"); mapping.put("demo/Ledger$Entry.size", "ą");
        var remapper = new SimpleRemapper(mapping);
        Path jar = directory.resolve("obfuscated.jar");
        try (var zip = new ZipOutputStream(Files.newOutputStream(jar))) {
            for (String name : List.of("demo/Ledger", "demo/Ledger$Entry")) {
                var writer = new ClassWriter(0);
                new ClassReader(Files.readAllBytes(classes.resolve(name + ".class"))).accept(new ClassRemapper(writer, remapper), 0);
                zip.putNextEntry(new ZipEntry(mapping.get(name) + ".class")); zip.write(writer.toByteArray()); zip.closeEntry();
            }
        }
        String log = """
            <?xml version="1.0" encoding="UTF-8"?>
            <yguard version="1.5">
            <!--
            -->
            <map>
              <class name="demo.Ledger" map="&#x00c4;"/>
              <class name="demo.Ledger$Entry" map="&#x00df;"/>
              <method class="demo.Ledger" name="int add(int)" map="&#x0119;"/>
              <field class="demo.Ledger" name="balance" map="&#x0105;"/>
              <field class="demo.Ledger" name="owner" map="&#x0107;"/>
              <field class="demo.Ledger$Entry" name="size" map="&#x0105;"/>
            </map>
            </yguard>
            """;
        Path logFile = directory.resolve("yguardlog.xml.gz");
        try (var gzip = new GZIPOutputStream(Files.newOutputStream(logFile))) { gzip.write(log.getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
        Path result = directory.resolve("renamed");
        int code = Main.run(new String[] {"rename", jar.toString(), "--yguard-log", logFile.toString(), "--output", result.toString()},
            new PrintStream(output), new PrintStream(errors));
        assertEquals(0, code, errors + output.toString());
        String report = Files.readString(result.resolve("rename.json"));
        assertTrue(report.contains("\"mapping\": \"yguard\"") || report.contains("\"mapping\":\"yguard\""), report);
        var bytes = ProguardFixtureTest.classes(result.resolve("renamed.jar"));
        assertEquals(Set.of("demo.Ledger", "demo.Ledger$Entry"), bytes.keySet());
        var types = ProguardFixtureTest.link(bytes);
        assertEquals(Set.of("balance", "owner"), ProguardFixtureTest.names(types.get("demo.Ledger").getDeclaredFields()));
        assertTrue(ProguardFixtureTest.names(types.get("demo.Ledger").getDeclaredMethods()).contains("add"));
        assertEquals(Set.of("size"), ProguardFixtureTest.names(types.get("demo.Ledger$Entry").getDeclaredFields()));
    }
}
