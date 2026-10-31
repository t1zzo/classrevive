package dev.classrevive.verification;

import dev.classrevive.bytecode.ClassInspector;
import dev.classrevive.model.ClassInfo;
import java.io.IOException;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.zip.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class PartialRoundTripTest {
    @TempDir Path dir;

    @Test void measuresTheFilesThatCompileAgainstTheOriginalClassesAndLeavesTheOthersOut() throws Exception {
        // Originals: Calc and its user Report. The candidate has a correct Calc, a Report that does not compile, and a
        // Helper that only compiles once Report's original stands in for the broken source.
        var originals = original(Map.of(
            "p/Calc.java", "package p; public class Calc { public int twice(int x) { return x * 2; } }",
            "p/Report.java", "package p; public class Report { public static String show(int x) { return \"v\" + new Calc().twice(x); } }",
            "p/Helper.java", "package p; public class Helper { public String run() { return Report.show(3); } }"));
        Path candidate = write("candidate", Map.of(
            "p/Calc.java", "package p;\npublic class Calc {\n   public int twice(int x) {\n      return x * 2;\n   }\n}\n",
            "p/Report.java", "package p;\npublic class Report {\n   public static String show(int x) {\n      return \"v\" + new Calc().twice(missing);\n   }\n}\n",
            "p/Helper.java", "package p;\npublic class Helper {\n   public String run() {\n      return Report.show(3);\n   }\n}\n"));
        var whole = new CompilerVerifier().verify(candidate, dir.resolve("whole"), originals.infos(), Duration.ofSeconds(60), List.of(), originals.jar());
        assertEquals(VerificationResult.Status.COMPILE_FAILED, whole.status());

        var result = new PartialRoundTrip().measure(candidate, dir.resolve("per-file"), originals.infos(), Duration.ofSeconds(60), List.of(), originals.jar());
        assertTrue(result.compiled());
        assertEquals(3, result.files()); assertEquals(2, result.compiledFiles()); assertEquals(2, result.rounds());
        assertEquals(List.of("p/Calc", "p/Helper"), result.classes().stream().map(ClassRoundTrip::className).toList());
        // Each class has a constructor and one method, both recompiled to the original instructions.
        assertEquals(4, result.identical()); assertEquals(0, result.different());
        assertEquals("1\tp/Report.java\n", Files.readString(dir.resolve("per-file/excluded.tsv")));
    }

    @Test void leavesOutEveryFileWithAFlowErrorInOneRound() throws Exception {
        // javac normally stops flow analysis after the first class with an error; both files must leave in round 1.
        var originals = original(Map.of(
            "p/A.java", "package p; public class A { public int f(int x) { if (x > 0) return 1; return 0; } }",
            "p/B.java", "package p; public class B { public int g(int x) { if (x > 0) return 2; return 0; } }",
            "p/C.java", "package p; public class C { public int h() { return new A().f(1) + new B().g(1); } }"));
        Path candidate = write("candidate", Map.of(
            "p/A.java", "package p; public class A { public int f(int x) { if (x > 0) return 1; } }",
            "p/B.java", "package p; public class B { public int g(int x) { if (x > 0) return 2; } }",
            "p/C.java", "package p; public class C { public int h() { return new A().f(1) + new B().g(1); } }"));
        var result = new PartialRoundTrip().measure(candidate, dir.resolve("per-file"), originals.infos(), Duration.ofSeconds(60), List.of(), originals.jar());
        assertTrue(result.compiled());
        assertEquals(1, result.compiledFiles()); assertEquals(2, result.rounds());
        assertEquals("1\tp/A.java\n1\tp/B.java\n", Files.readString(dir.resolve("per-file/excluded.tsv")));
    }

    @Test void reportsNothingCompiledWhenEveryFileFails() throws Exception {
        var originals = original(Map.of("p/Calc.java", "package p; public class Calc { public int twice(int x) { return x * 2; } }"));
        Path candidate = write("candidate", Map.of("p/Calc.java", "package p; public class Calc { public int twice(int x) { return y * 2; } }"));
        var result = new PartialRoundTrip().measure(candidate, dir.resolve("per-file"), originals.infos(), Duration.ofSeconds(60), List.of(), originals.jar());
        assertFalse(result.compiled());
        assertEquals(0, result.compiledFiles()); assertEquals(List.of(), result.classes());
    }

    record Original(Path jar, List<ClassInfo> infos) {}

    Original original(Map<String,String> files) throws IOException {
        Path sources = write("original-src", files), classes = dir.resolve("original-classes");
        var args = new ArrayList<>(List.of("--release", "8", "-Xlint:-options", "-proc:none", "-d", classes.toString()));
        for (var name : files.keySet()) args.add(sources.resolve(name).toString());
        assertEquals(0, javax.tools.ToolProvider.getSystemJavaCompiler().run(null, null, null, args.toArray(String[]::new)));
        Path jar = dir.resolve("classes.jar"); var infos = new ArrayList<ClassInfo>();
        try (var zip = new ZipOutputStream(Files.newOutputStream(jar)); var paths = Files.walk(classes)) {
            for (var path : paths.filter(path -> path.toString().endsWith(".class")).sorted().toList()) {
                byte[] bytes = Files.readAllBytes(path);
                zip.putNextEntry(new ZipEntry(classes.relativize(path).toString().replace('\\', '/'))); zip.write(bytes); zip.closeEntry();
                infos.add(new ClassInspector().inspect(bytes));
            }
        }
        return new Original(jar, infos);
    }

    Path write(String name, Map<String,String> files) throws IOException {
        Path root = Files.createDirectory(dir.resolve(name));
        for (var file : files.entrySet()) {
            Path path = root.resolve(file.getKey());
            Files.createDirectories(path.getParent());
            Files.writeString(path, file.getValue());
        }
        return root;
    }
}
