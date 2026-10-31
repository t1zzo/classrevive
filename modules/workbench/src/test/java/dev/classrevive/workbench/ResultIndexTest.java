package dev.classrevive.workbench;

import dev.classrevive.pipeline.JobListener;
import java.io.IOException;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.regex.*;
import java.util.zip.*;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ResultIndexTest {
    @TempDir Path directory;

    @Test void readsAJobBackWithTheLevelsOfItsHtmlReport() throws Exception {
        var input = jar(Map.of("demo/Outer.java", """
            package demo;
            public class Outer {
                public int twice(int x) { return x * 2; }
                public static class Inner { public String name() { return "inner"; } }
            }
            """, "demo/Other.java", "package demo; public class Other { public long add(long a, long b) { return a + b; } }"));
        var request = new DecompileRequest(input, directory.resolve("out"), EngineRegistry.parse("cfr,vineflower"), Duration.ofSeconds(60),
            List.of(), null, true);
        var job = new DecompileService().decompile(request, JobListener.NONE).job();
        var index = ResultIndex.open(directory.resolve("out"));

        assertTrue(index.levelsAvailable());
        assertEquals(Set.of("demo/Outer", "demo/Outer$Inner", "demo/Other"), index.classes().keySet());
        var verification = job.selection().verification();
        var summary = index.summary();
        assertEquals(verification.status().name(), summary.compilation());
        assertEquals(verification.matchedSymbols(), summary.matchedSymbols());
        assertEquals(verification.expectedSymbols(), summary.expectedSymbols());
        assertEquals(job.selection().candidate(), summary.selectedCandidate());
        assertEquals("NOT_VERIFIED", summary.behavior());
        assertEquals(List.of("cfr", "vineflower"), index.engines().stream().map(ResultIndex.EngineEntry::id).toList());
        assertEquals(List.of(ResultIndex.SELECTION, "cfr", "vineflower"), index.candidates());

        // Every class carries exactly the level the page shows for it.
        String html = Files.readString(directory.resolve("out/report.html"));
        var pageLevels = HtmlLevels.parse(html);
        assertEquals(index.classes().size(), pageLevels.size());
        index.classes().values().forEach(entry -> assertEquals(pageLevels.get(entry.className()), entry.level(), entry.className()));
        assertEquals(3, index.levelCounts().get(ResultIndex.Level.EXACT), index.levelCounts().toString());

        // A nested class lives in its top-level file in every candidate.
        var inner = index.classes().get("demo/Outer$Inner");
        assertEquals("demo/Outer.java", inner.file());
        for (String candidate : index.candidates()) {
            Path source = index.source("demo/Outer$Inner", candidate).orElseThrow(() -> new AssertionError(candidate));
            assertTrue(Files.readString(source).contains("Inner"), candidate);
            assertTrue(source.startsWith(directory.resolve("out").toAbsolutePath()));
        }
        assertTrue(index.source("demo/Missing", "cfr").isEmpty());
        assertThrows(IllegalArgumentException.class, () -> index.source("demo/Outer", "procyon"));
        assertEquals("demo/Outer", index.originalName("demo/Outer"));
    }

    @Test void readsAFolderWrittenBeforeLevelsExistedWithoutInventingThem() throws Exception {
        var output = Files.createDirectories(directory.resolve("old"));
        Files.createDirectories(output.resolve("cfr/sources/a"));
        Files.writeString(output.resolve("cfr/sources/a/B.java"), "package a; class B {}");
        Files.writeString(output.resolve("report.json"), """
            {"behavior":"NOT_VERIFIED","engines":[{"id":"cfr","version":"0.152","status":"OUTPUT_PRODUCED","source_files":1}],
             "input_sha256":"00","kind":"decompilation","provenance":{"a/B.java":"cfr"},"selected_candidate":"cfr",
             "portable_names":{"renamed_classes":{"a/b":"a/b_cr2"}},
             "verification":{"status":"COMPILED","errors":0,"matched_symbols":2,"expected_symbols":2,"structurally_complete":true,
               "class_coverage":[{"class_name":"a/B$C","matched_symbols":1,"expected_symbols":1},
                                 {"class_name":"a/b_cr2","matched_symbols":1,"expected_symbols":1}]}}
            """);
        var index = ResultIndex.open(output);
        assertFalse(index.levelsAvailable());
        assertTrue(index.levelCounts().isEmpty());
        var nested = index.classes().get("a/B$C");
        assertNull(nested.level());
        assertEquals("cfr", nested.origin());
        assertEquals(output.toAbsolutePath().normalize().resolve("cfr/sources/a/B.java"), index.source("a/B$C", "cfr").orElseThrow());
        assertEquals(List.of("cfr"), index.candidates());
        assertEquals("a/b", index.originalName("a/b_cr2"));
    }

    @Test void refusesWhatIsNotAReadableDecompileReport() throws Exception {
        var none = Files.createDirectories(directory.resolve("none"));
        assertThrows(IOException.class, () -> ResultIndex.open(none));
        for (String text : List.of("not json", "[]", "{\"kind\":\"benchmark\"}", "{\"kind\":\"decompilation\"}",
                "{\"kind\":\"decompilation\",\"verification\":{\"status\":7}}")) {
            var folder = Files.createDirectories(directory.resolve("bad-" + Math.abs(text.hashCode())));
            Files.writeString(folder.resolve("report.json"), text);
            assertThrows(IOException.class, () -> ResultIndex.open(folder), text);
        }
    }

    @Test void neverReturnsAFileOutsideTheCandidateFolder() throws Exception {
        // A tampered report naming a class that climbs out of the sources folder.
        var output = Files.createDirectories(directory.resolve("tampered"));
        Files.createDirectories(output.resolve("cfr/sources"));
        Files.writeString(output.resolve("cfr/evil.java"), "outside");
        Files.writeString(output.resolve("report.json"), """
            {"behavior":"NOT_VERIFIED","engines":[{"id":"cfr","version":"0.152","status":"OUTPUT_PRODUCED","source_files":1}],
             "input_sha256":"00","kind":"decompilation","selected_candidate":"cfr",
             "verification":{"status":"COMPILED","errors":0,"matched_symbols":1,"expected_symbols":1,"structurally_complete":true,
               "class_coverage":[{"class_name":"../evil","matched_symbols":1,"expected_symbols":1}]}}
            """);
        var index = ResultIndex.open(output);
        assertTrue(index.classes().containsKey("../evil"));
        assertTrue(index.source("../evil", "cfr").isEmpty());
    }

    /** Class levels as report.html renders them: package summaries, then one table row per class with its level pill. */
    static final class HtmlLevels {
        private static final Pattern PACKAGE = Pattern.compile("<summary><code>([^<]*)</code>");
        private static final Pattern ROW = Pattern.compile("<tr><td><code>([^<]*)</code>.*?<span class=\"pill ([a-z-]+)\">", Pattern.DOTALL);

        static Map<String,ResultIndex.Level> parse(String html) {
            var levels = new TreeMap<String,ResultIndex.Level>();
            var packages = PACKAGE.matcher(html);
            var starts = new ArrayList<int[]>();
            var names = new ArrayList<String>();
            while (packages.find()) { starts.add(new int[]{packages.start()}); names.add(unescape(packages.group(1))); }
            for (int i = 0; i < names.size(); i++) {
                int from = starts.get(i)[0], to = i + 1 < starts.size() ? starts.get(i + 1)[0] : html.length();
                String pkg = names.get(i).equals("(default package)") ? "" : names.get(i).replace('.', '/') + "/";
                var rows = ROW.matcher(html.substring(from, to));
                while (rows.find()) levels.put(pkg + unescape(rows.group(1)), ResultIndex.Level.valueOf(rows.group(2).toUpperCase(Locale.ROOT).replace('-', '_')));
            }
            return levels;
        }

        static String unescape(String text) {
            return text.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'").replace("&amp;", "&");
        }
    }

    private Path jar(Map<String,String> sources) throws Exception {
        var sourceRoot = Files.createDirectories(directory.resolve("src"));
        var classes = Files.createDirectories(directory.resolve("classes"));
        var args = new ArrayList<>(List.of("--release", "8", "-proc:none", "-d", classes.toString()));
        for (var source : sources.entrySet()) {
            Path file = sourceRoot.resolve(source.getKey());
            Files.createDirectories(file.getParent());
            Files.writeString(file, source.getValue());
            args.add(file.toString());
        }
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, args.toArray(String[]::new)));
        var archive = directory.resolve("input.jar");
        try (var zip = new ZipOutputStream(Files.newOutputStream(archive)); var walk = Files.walk(classes)) {
            for (Path file : walk.filter(Files::isRegularFile).sorted().toList()) {
                zip.putNextEntry(new ZipEntry(classes.relativize(file).toString().replace('\\', '/')));
                zip.write(Files.readAllBytes(file));
                zip.closeEntry();
            }
        }
        return archive;
    }
}
