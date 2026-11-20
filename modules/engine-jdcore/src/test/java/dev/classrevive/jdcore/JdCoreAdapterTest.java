package dev.classrevive.jdcore;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import java.util.spi.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Phase 2DO/2DP: the JD-Core adapter without JD-Core. Fixture classes are compiled here and only read as bytes. */
class JdCoreAdapterTest {
    @TempDir Path directory;

    private Path compile(String source) throws IOException {
        Path file = directory.resolve("p").resolve(source.substring(source.indexOf("class ") + 6).split("[ {]")[0] + ".java");
        if (source.contains("enum ")) file = directory.resolve("p").resolve(source.substring(source.indexOf("enum ") + 5).split("[ {]")[0] + ".java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, source);
        Path classes = Files.createDirectories(directory.resolve("classes"));
        assertEquals(0, javax.tools.ToolProvider.getSystemJavaCompiler().run(null, null, null,
            "--release", "21", "-proc:none", "-d", classes.toString(), file.toString()));
        return classes;
    }

    @Test void readsTheInnerClassesJdCorePrintsInsideTheirOuterClass() throws IOException {
        Path classes = compile("""
            package p;
            public class Outer {
                class Inner {}
                static class Nested { class Deep {} }
                Runnable task = new Runnable() { public void run() {} };
            }
            """);
        var outer = ClassFileFacts.read(Files.readAllBytes(classes.resolve("p/Outer.class")));
        assertEquals("p/Outer", outer.name());
        // javac also lists Deep in Outer's InnerClasses; its name starts with Outer's, so JD-Core loads it with Outer too.
        assertEquals(Set.of("p/Outer$Inner", "p/Outer$Nested", "p/Outer$Nested$Deep", "p/Outer$1"), Set.copyOf(outer.claimed()));
        var nested = ClassFileFacts.read(Files.readAllBytes(classes.resolve("p/Outer$Nested.class")));
        assertEquals(List.of("p/Outer$Nested$Deep"), nested.claimed());
        // Deep names Nested as its outer class, but Deep is not Nested's outer: no claim the other way.
        assertEquals(List.of(), ClassFileFacts.read(Files.readAllBytes(classes.resolve("p/Outer$Nested$Deep.class"))).claimed());
    }

    @Test void classesNoInnerClassesAttributeClaimsGetTheirOwnFile() {
        var classes = new TreeSet<>(List.of("a/B", "a/B$C", "a/B$C$D", "a/E$F"));
        // A normal jar: every $ class is claimed, directly or through its claimed outer class.
        assertEquals(Set.of("a/B$C", "a/B$C$D"), JdCoreWorker.printedInside(classes, Map.of("a/B", List.of("a/B$C"), "a/B$C", List.of("a/B$C$D"))));
        // InnerClasses stripped (an obfuscated jar): JD-Core prints each class on its own, so each gets a file.
        assertEquals(Set.of(), JdCoreWorker.printedInside(classes, Map.of()));
        // Claimed only inside a cycle: no class reaches them, so they are not taken for printed.
        assertEquals(Set.of(), JdCoreWorker.printedInside(Set.of("x/A", "x/B"), Map.of("x/A", List.of("x/B"), "x/B", List.of("x/A"))));
        // A claim of a class outside the jar changes nothing.
        assertEquals(Set.of(), JdCoreWorker.printedInside(Set.of("a/B"), Map.of("a/B", List.of("java/util/Map$Entry"))));
    }

    @Test void leavesOutOnlyMethodParametersThatNameNoParameter() throws IOException {
        Path classes = compile("""
            package p;
            public enum Kind { A, B; int twice(int value) { return value * 2; } }
            """);
        Path original = classes.resolve("p/Kind.class");
        byte[] bytes = Files.readAllBytes(original);
        assertTrue(javap(original).contains("MethodParameters:"), "javac 21 writes a nameless entry for valueOf's mandated parameter");
        byte[] readable = ClassFileFacts.withoutNamelessParameters(bytes);
        assertNotSame(bytes, readable);
        assertTrue(readable.length < bytes.length);
        Path rewritten = Files.createDirectories(directory.resolve("rewritten/p")).resolve("Kind.class");
        Files.write(rewritten, readable);
        String text = javap(rewritten);
        assertFalse(text.contains("MethodParameters:"), text); // the attribute is gone; its name stays in the constant pool
        assertTrue(text.contains("int twice(int)") && text.contains("valueOf(java.lang.String)") && text.contains("Code:"), text);
        assertSame(readable, ClassFileFacts.withoutNamelessParameters(readable));
        assertEquals("p/Kind", ClassFileFacts.read(readable).name());
    }

    @Test void classesWithoutNamelessParametersAreHandedOverUnchanged() throws IOException {
        Path classes = compile("package p;\npublic class Plain { int add(int a, int b) { return a + b; } }\n");
        byte[] bytes = Files.readAllBytes(classes.resolve("p/Plain.class"));
        assertSame(bytes, ClassFileFacts.withoutNamelessParameters(bytes));
        assertThrows(IllegalArgumentException.class, () -> ClassFileFacts.read(new byte[] {(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE, 0, 0}));
        assertThrows(IllegalArgumentException.class, () -> ClassFileFacts.read(new byte[] {1, 2, 3, 4}));
        assertThrows(IllegalArgumentException.class, () -> ClassFileFacts.read(Arrays.copyOf(bytes, bytes.length / 2)));
    }

    @Test void escapesHalfSurrogatesOnly() {
        assertEquals("plain", JdCoreWorker.escapeLoneSurrogates("plain"));
        assertEquals("\"\uD83D\uDE00\"", JdCoreWorker.escapeLoneSurrogates("\"\uD83D\uDE00\""));
        assertEquals("\"\\uD800x\"", JdCoreWorker.escapeLoneSurrogates("\"\uD800x\""));
        assertEquals("'\\uDC00'", JdCoreWorker.escapeLoneSurrogates("'\uDC00'"));
        assertEquals("\\uD83D\uD83D\uDE00", JdCoreWorker.escapeLoneSurrogates("\uD83D\uD83D\uDE00"));
    }

    @Test void findsJdCoreOnlyThroughItsVariable() throws IOException {
        assertTrue(JdCoreEngine.locate(Map.of()).isEmpty());
        assertTrue(JdCoreEngine.locate(Map.of(JdCoreEngine.VARIABLE, " ")).isEmpty());
        var missing = assertThrows(IllegalArgumentException.class,
            () -> JdCoreEngine.locate(Map.of(JdCoreEngine.VARIABLE, directory.resolve("none.jar").toString())));
        assertTrue(missing.getMessage().startsWith(JdCoreEngine.VARIABLE + " does not name a jar file"), missing.getMessage());
        Path other = jar("other.jar", "x/Y.class", null);
        var wrong = assertThrows(IllegalArgumentException.class, () -> JdCoreEngine.locate(Map.of(JdCoreEngine.VARIABLE, other.toString())));
        assertTrue(wrong.getMessage().contains("is not a JD-Core jar"), wrong.getMessage());
        Path jdCore = jar("jd-core-test.jar", "org/jd/core/v1/ClassFileToJavaSourceDecompiler.class", "1.1.3");
        var engine = JdCoreEngine.locate(Map.of(JdCoreEngine.VARIABLE, jdCore.toString())).orElseThrow();
        assertEquals("jd-core", engine.id());
        assertEquals("1.1.3", engine.version());
        assertTrue(engine.neverWorse());
        var command = String.join(" ", engine.command(directory.resolve("in.jar"), directory.resolve("out")));
        assertTrue(command.contains(jdCore.toAbsolutePath().normalize().toString()), command);
        assertTrue(command.contains(JdCoreEngine.WORKER), command);
        assertEquals("unknown", JdCoreEngine.at(jar("bare.jar", "org/jd/core/v1/ClassFileToJavaSourceDecompiler.class", null)).version());
    }

    private Path jar(String name, String entry, String version) throws IOException {
        Path jar = directory.resolve(name);
        var manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        if (version != null) manifest.getMainAttributes().putValue("JD-Core-Version", version);
        try (var out = new JarOutputStream(Files.newOutputStream(jar), manifest)) {
            out.putNextEntry(new JarEntry(entry));
            out.closeEntry();
        }
        return jar;
    }

    private static String javap(Path classFile) {
        var text = new StringWriter();
        var javap = ToolProvider.findFirst("javap").orElseThrow();
        assertEquals(0, javap.run(new PrintWriter(text), new PrintWriter(text), "-v", "-p", classFile.toString()));
        return text.toString();
    }
}
