package dev.classrevive.cli;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Phase 2CV: help, version, short options, default output folders, several inputs, clear errors. */
class CliUsabilityTest {
    @TempDir Path directory;
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    ByteArrayOutputStream errors = new ByteArrayOutputStream();

    int run(String... args) {
        output.reset(); errors.reset();
        return Main.run(args, new PrintStream(output), new PrintStream(errors));
    }

    @Test void overviewListsEveryCommandGroupedWithExitCodes() {
        assertEquals(0, run());
        String text = output.toString();
        for (String command : Help.COMMANDS.keySet()) assertTrue(text.contains(command), command);
        assertTrue(text.contains("Main commands:") && text.contains("Tools:") && text.contains("Failure lab:"));
        assertTrue(text.contains("Exit codes: 0"));
        assertEquals(0, run("--help"));
        assertEquals(0, run("-h"));
        assertEquals(0, run("help"));
    }

    @Test void printsTheVersion() {
        for (String flag : List.of("--version", "-V", "version")) {
            assertEquals(0, run(flag));
            assertEquals("ClassRevive " + Help.VERSION, output.toString().strip());
        }
    }

    @Test void everyCommandHasHelpThroughBothForms() {
        for (String command : Help.COMMANDS.keySet()) {
            assertEquals(0, run("help", command), command);
            String viaHelp = output.toString();
            assertTrue(viaHelp.contains("Usage: classrevive " + command), viaHelp);
            assertFalse(viaHelp.contains("%s"), "unformatted placeholder in " + command);
            assertEquals(0, run(command, "--help"), command);
            assertEquals(viaHelp, output.toString());
        }
        assertEquals(0, run("decompile", "-h"));
        assertTrue(output.toString().contains("-o, --output") && output.toString().contains("Examples:"));
        assertEquals(2, run("help", "decomplie"));
        assertTrue(errors.toString().contains("Did you mean 'decompile'?"), errors.toString());
    }

    @Test void unknownCommandsAndWrongArgumentsSayWhatToDo() {
        assertEquals(2, run("decomple", "app.jar"));
        assertTrue(errors.toString().contains("Unknown command: decomple. Did you mean 'decompile'?"), errors.toString());
        assertEquals(2, run("frobnicate"));
        assertFalse(errors.toString().contains("Did you mean"), errors.toString());
        assertEquals(2, run("inspect"));
        assertTrue(errors.toString().contains("Usage: classrevive inspect") && errors.toString().contains("classrevive help inspect"));
        assertEquals(2, run("engines", "extra"));
        assertTrue(errors.toString().contains("Usage: classrevive engines"));
        assertEquals(2, run("decompile"));
        assertTrue(errors.toString().contains("expected an input file"));
    }

    @Test void shortOptionsMeanTheLongOnes() throws Exception {
        Path input = compile("Single", "public class Single { public int twice(int x) { return x * 2; } }");
        Path out = directory.resolve("short");
        assertEquals(0, run("decompile", input.toString(), "-o", out.toString(), "-e", "cfr", "-t", "60"), errors + "\n" + output);
        assertTrue(Files.isRegularFile(out.resolve("report.json")));
        assertTrue(output.toString().contains("cfr 0.152"));
        assertFalse(output.toString().contains("vineflower"), "-e cfr picked one engine");
        assertArrayEquals(new String[] {"decompile", "a.jar", "--output", "x", "--engines", "all", "--timeout-seconds", "5", "--classpath", "l.jar"},
            Main.shortOptions(new String[] {"decompile", "a.jar", "-o", "x", "-e", "all", "-t", "5", "-cp", "l.jar"}));
    }

    @Test void defaultOutputFoldersAreNewAndNamedAfterTheInput() throws Exception {
        Path first = Outputs.next(directory, Path.of("libs/My-App.JAR"), "classrevive");
        assertEquals(directory.resolve("My-App-classrevive"), first);
        Files.createDirectories(first);
        assertEquals(directory.resolve("My-App-classrevive-2"), Outputs.next(directory, Path.of("My-App.jar"), "classrevive"));
        assertEquals(directory.resolve("tool.exe-diagnosis"), Outputs.next(directory, Path.of("tool.exe"), "diagnosis"));
        var withDefault = Outputs.withDefault(new String[] {"diagnose", "x/app.jar", "--timeout-seconds", "5"}, "diagnosis");
        assertEquals(List.of("diagnose", "x/app.jar", "--timeout-seconds", "5", "--output"), Arrays.asList(withDefault).subList(0, 5));
        assertEquals(Path.of("").toAbsolutePath().resolve("app-diagnosis").getFileName(), Path.of(withDefault[5]).getFileName());
    }

    @Test void decompilesEveryFileOfAFolderIntoItsOwnNewFolder() throws Exception {
        Path folder = Files.createDirectories(directory.resolve("inputs"));
        Files.copy(compile("Alpha", "public class Alpha { public int one() { return 1; } }"), folder.resolve("Alpha.class"));
        Files.copy(compile("Beta", "public class Beta { public String two() { return \"two\"; } }"), folder.resolve("Beta.class"));
        Files.writeString(folder.resolve("notes.txt"), "not an input");
        Path out = directory.resolve("results");
        assertEquals(0, run("decompile", folder.toString(), "-o", out.toString(), "-e", "cfr"), errors + "\n" + output);
        assertTrue(Files.isRegularFile(out.resolve("Alpha-classrevive/report.json")));
        assertTrue(Files.isRegularFile(out.resolve("Beta-classrevive/report.json")));
        assertTrue(output.toString().contains("== [1/2]") && output.toString().contains("== [2/2]"));
        assertTrue(output.toString().contains("Decompiled 2 inputs") && output.toString().contains("2 compiled with every signature, 0 partial or failed"));
        // A second run reuses the output folder and never touches the first results.
        assertEquals(0, run("decompile", folder.resolve("Alpha.class").toString(), folder.resolve("Beta.class").toString(), "-o", out.toString(), "-e", "cfr"));
        assertTrue(Files.isRegularFile(out.resolve("Alpha-classrevive-2/report.json")));
        assertTrue(Files.isRegularFile(out.resolve("Beta-classrevive-2/report.json")));

        Path empty = Files.createDirectories(directory.resolve("empty"));
        assertEquals(2, run("decompile", empty.toString(), "-o", out.toString()));
        assertTrue(errors.toString().contains("no .jar or .class file"));
        assertEquals(2, run("decompile", folder.toString(), "-o", out.toString(), "-e", "nope"), "invalid options stop the whole run");
        assertFalse(Files.exists(out.resolve("Alpha-classrevive-3")));
    }

    private Path compile(String name, String source) throws Exception {
        Path sources = Files.createDirectories(directory.resolve("src-" + name));
        Path file = sources.resolve(name + ".java");
        Files.writeString(file, source);
        Path classes = Files.createDirectories(directory.resolve("classes-" + name));
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "--release", "8", "-proc:none", "-d", classes.toString(), file.toString()));
        return classes.resolve(name + ".class");
    }
}
