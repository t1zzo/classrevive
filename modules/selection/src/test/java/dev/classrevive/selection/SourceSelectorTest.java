package dev.classrevive.selection;

import dev.classrevive.model.*;
import dev.classrevive.verification.VerificationResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SourceSelectorTest {
    @TempDir Path dir;
    ClassInfo info(String name) { return new ClassInfo(name, "java/lang/Object", 52, 0, 1, List.of(), List.of(), List.of(new MemberInfo("<init>", "()V", 1))); }
    Path candidate(String id, String a, String b) throws Exception {
        var root = Files.createDirectory(dir.resolve(id));
        Files.writeString(root.resolve("A.java"), a);
        if (b != null) Files.writeString(root.resolve("B.java"), b);
        return root;
    }
    private static String source(String name, int methods) {
        var text = new StringBuilder("class ").append(name).append(" { ").append(name).append("() {} ");
        for (int i = 0; i < methods; i++) text.append("int m").append(i).append("() { return ").append(i).append("; } ");
        return text.append('}').toString();
    }
    @Test void picksCompilingCandidateAndPreservesAlternatives() throws Exception {
        var bad = candidate("bad", "public class A { nonsense }", null);
        var good = candidate("good", "public class A {}", null);
        var result = new SourceSelector().select(Map.of("bad",bad,"good",good), dir.resolve("selection"), List.of(info("A")), Duration.ofSeconds(20));
        assertEquals("good", result.candidate()); assertTrue(result.verification().complete());
        assertEquals(List.of("bad", "good"), result.tried().stream().map(SelectionResult.Attempt::id).toList());
        assertEquals(VerificationResult.Status.COMPILE_FAILED, result.tried().getFirst().verification().status());
        assertEquals(Map.of("A.java", "good"), result.tried().get(1).origins());
        assertEquals("public class A {}", Files.readString(dir.resolve("selection/sources/A.java")));
        assertTrue(Files.readString(bad.resolve("A.java")).contains("nonsense"));
    }
    @Test void repairsComplementaryFailuresByWholeCompilationUnit() throws Exception {
        var first = candidate("first", "public class A {}", "public class B { broken }");
        var second = candidate("second", "public class A { broken }", "public class B {}");
        var result = new SourceSelector().select(Map.of("first",first,"second",second), dir.resolve("selection"), List.of(info("A"),info("B")), Duration.ofSeconds(20));
        assertTrue(result.verification().complete());
        assertEquals("first", result.provenance().get("A.java"));
        assertEquals("second", result.provenance().get("B.java"));
        assertTrue(result.attempts() <= 10);
    }
    @Test void perFileErrorsGuideTheHybridToTheBrokenFileBeyondTheFirstEightSwaps() throws Exception {
        // Each candidate breaks one file. The old blind order swapped F00..F07 first and never reached F10.
        var first = Files.createDirectory(dir.resolve("first")); var second = Files.createDirectory(dir.resolve("second"));
        var originals = new ArrayList<ClassInfo>();
        for (int i = 0; i <= 10; i++) {
            String name = String.format("F%02d", i); originals.add(info(name));
            Files.writeString(first.resolve(name + ".java"), i == 10 ? "public class " + name + " { broken }" : "public class " + name + " {}");
            Files.writeString(second.resolve(name + ".java"), i == 0 ? "public class " + name + " { broken }" : "public class " + name + " { /* second */ }");
        }
        var result = new SourceSelector().select(Map.of("first", first, "second", second), dir.resolve("selection"), originals, Duration.ofSeconds(20));
        String report = Files.readString(dir.resolve("selection/selection.txt"));
        assertTrue(result.verification().complete(), report);
        assertEquals("first", result.provenance().get("F00.java"));
        assertEquals("second", result.provenance().get("F10.java"));
        assertEquals(3, result.attempts(), report);
        assertTrue(report.contains("guided by per-file javac errors: 1 file(s) replaced"), report);
        assertTrue(report.contains("syntax-errors="), report);
    }
    @Test void aCandidateThatParsedRanksAboveOneStoppedAtParsingWithFewerErrors() throws Exception {
        var stopped = candidate("stopped", "public class A { broken }", null);
        var parsed = candidate("parsed", "public class A { Missing1 a; Missing2 b; Missing3 c; }", null);
        var result = new SourceSelector().select(Map.of("parsed", parsed, "stopped", stopped), dir.resolve("selection"), List.of(info("A")), Duration.ofSeconds(20));
        String report = Files.readString(dir.resolve("selection/selection.txt"));
        assertEquals("parsed", result.candidate(), report);
        assertEquals(0, result.verification().syntaxErrors());
        assertEquals(3, result.verification().errors(), report);
    }
    @Test void aCandidateWithoutSourceFilesRanksBelowOneWithCompilerErrors() throws Exception {
        // An engine that failed before writing sources compiles nothing: COMPILE_FAILED with 0 errors, which used to beat 1 error.
        var broken = Files.createDirectory(dir.resolve("broken"));
        var cfr = candidate("cfr", "public class A { Missing a; }", null);
        var result = new SourceSelector().select(Map.of("broken", broken, "cfr", cfr), dir.resolve("selection"), List.of(info("A")), Duration.ofSeconds(60));
        String report = Files.readString(dir.resolve("selection/selection.txt"));
        assertEquals("cfr", result.candidate(), report);
        assertEquals(1, result.verification().errors(), report);
        assertEquals("cfr", result.provenance().get("A.java"));
        assertEquals("public class A { Missing a; }", Files.readString(dir.resolve("selection/sources/A.java")));
        assertTrue(report.contains("broken: no .java files; ranked below every candidate with sources"), report);
    }
    static final String UNINFERABLE_CALL = "import java.util.*; public class A { static <T> void both(List<T> a, List<T> b) {} "
        + "void call(List<?> x, List<?> y) { both(x, y); } }";
    @Test void compilerGuidedRepairIsSelectedOnlyAfterItRecompilesAndIsRecordedAsEvidence() throws Exception {
        var engine = candidate("vf", UNINFERABLE_CALL, "public class B {}");
        var result = new SourceSelector().select(Map.of("vf", engine), dir.resolve("selection"), List.of(info("A"), info("B")), Duration.ofSeconds(60));
        String report = Files.readString(dir.resolve("selection/selection.txt"));
        assertEquals("repair-1", result.candidate(), report);
        assertTrue(result.verification().complete(), report);
        assertEquals(2, result.attempts(), report);
        assertEquals("vf+repair", result.provenance().get("A.java"));
        assertEquals("vf", result.provenance().get("B.java"));
        assertEquals(List.of("raw-argument", "raw-argument"), result.repairs().stream().map(edit -> edit.rule()).toList());
        assertTrue(result.repairs().stream().allMatch(edit -> edit.file().equals("A.java") && edit.code().equals("compiler.err.cant.apply.symbol")));
        assertTrue(report.contains("vf: COMPILE_FAILED"), report);
        assertTrue(report.contains("edit A.java:1:") && report.contains("raw-argument: `x` -> `(java.util.List)x` (kept)"), report);
        assertTrue(report.contains("eligible for selection=true"), report);
        assertTrue(report.contains("Source repairs in the selected sources: 2 edit(s) in 1 file(s)"), report);
        assertTrue(Files.readString(dir.resolve("selection/sources/A.java")).contains("both((java.util.List)x, (java.util.List)y)"));
        assertEquals(UNINFERABLE_CALL, Files.readString(engine.resolve("A.java")), "engine output is never edited in place");
    }
    @Test void aRepairThatDoesNotReduceItsFileErrorsIsRevertedAndNotSelected() throws Exception {
        // Renaming value -> valuex resolves the name but leaves an int where a String is needed: still one error.
        var engine = candidate("vf", "public class A { Runnable r() { final int valuex = 1; "
            + "return new Runnable() { public void run() { String s = value; } }; } }", null);
        var result = new SourceSelector().select(Map.of("vf", engine), dir.resolve("selection"), List.of(info("A")), Duration.ofSeconds(60));
        String report = Files.readString(dir.resolve("selection/selection.txt"));
        assertEquals("vf", result.candidate(), report);
        assertEquals(List.of(), result.repairs());
        assertTrue(report.contains("renamed-local: `value` -> `valuex` (reverted)"), report);
        assertTrue(report.contains("eligible for selection=false"), report);
        assertTrue(report.contains("repair-2 proposal: OUTPUT_PRODUCED; edits=0"), report);
        assertEquals("vf", result.provenance().get("A.java"));
    }
    @Test void sourceRepairCanBeTurnedOffForEngineBaselines() throws Exception {
        var engine = candidate("vf", UNINFERABLE_CALL, null);
        var result = new SourceSelector(false).select(Map.of("vf", engine), dir.resolve("selection"), List.of(info("A")), Duration.ofSeconds(60));
        assertEquals("vf", result.candidate());
        assertEquals(1, result.attempts());
        assertFalse(Files.readString(dir.resolve("selection/selection.txt")).contains("Source repair"));
    }
    @Test void aFileThatFailsOnlyAfterRepairIsRetriedWithAnotherEngine() throws Exception {
        // Like ASM: repairing X lets javac reach flow analysis, which then reports Y; only engine "b" has a valid Y.
        var a = Files.createDirectory(dir.resolve("a"));
        Files.writeString(a.resolve("X.java"), "public class X { public int f(int n) { int c = n; if (c != true) return 1; return c; } }");
        Files.writeString(a.resolve("Y.java"), "public class Y { public int g(int x) { return x;\n x++; } }");
        var b = Files.createDirectory(dir.resolve("b"));
        Files.writeString(b.resolve("X.java"), "public class X { public int f(int n) { broken syntax here } }");
        Files.writeString(b.resolve("Y.java"), "public class Y { public int g(int x) { return x; } }");
        var originals = List.of(
            new ClassInfo("X", "java/lang/Object", 52, 0, 1, List.of(), List.of(), List.of(new MemberInfo("<init>", "()V", 1), new MemberInfo("f", "(I)I", 1))),
            new ClassInfo("Y", "java/lang/Object", 52, 0, 1, List.of(), List.of(), List.of(new MemberInfo("<init>", "()V", 1), new MemberInfo("g", "(I)I", 1))));
        var result = new SourceSelector().select(Map.of("a", a, "b", b), dir.resolve("selection"), originals, Duration.ofSeconds(30));
        String report = Files.readString(dir.resolve("selection/selection.txt"));
        assertTrue(result.verification().complete(), report);
        assertTrue(result.candidate().startsWith("swap-"), report);
        assertEquals("a+repair", result.provenance().get("X.java"));
        assertEquals("b", result.provenance().get("Y.java"));
        assertTrue(report.contains("after repairs: Y.java taken from b (kept)"), report);
    }
    @Test void aFileThatFailsOnlyAfterCompositionIsRetriedWithAnotherEngine() throws Exception {
        // Like Ant 1.10.17 (Phase 2BS): each engine breaks a different method of X, so only method composition fixes X;
        // then javac reaches flow analysis and reports Y's missing return, which only engine "b" prints correctly.
        Path original = Files.createDirectories(dir.resolve("original-src"));
        Files.writeString(original.resolve("X.java"), "public class X { public int f(int n) { return n + 1; } public int h(int n) { return n * 2; } }");
        Files.writeString(original.resolve("Y.java"), "public class Y { public int g(int x) { if (x > 0) return 1; return 0; } }");
        Path classes = Files.createDirectories(dir.resolve("original-classes"));
        assertEquals(0, javax.tools.ToolProvider.getSystemJavaCompiler().run(null, null, null, "--release", "8", "-Xlint:-options",
            "-proc:none", "-d", classes.toString(), original.resolve("X.java").toString(), original.resolve("Y.java").toString()));
        Path jar = dir.resolve("classes.jar");
        try (var zip = new java.util.zip.ZipOutputStream(Files.newOutputStream(jar))) {
            for (String name : List.of("X.class", "Y.class")) {
                zip.putNextEntry(new java.util.zip.ZipEntry(name)); zip.write(Files.readAllBytes(classes.resolve(name))); zip.closeEntry();
            }
        }
        var a = Files.createDirectory(dir.resolve("a"));
        Files.writeString(a.resolve("X.java"), "public class X {\n  public int f(int n) {\n    return m + 1;\n  }\n  public int h(int n) {\n    return n * 2;\n  }\n}\n");
        Files.writeString(a.resolve("Y.java"), "public class Y {\n  public int g(int x) {\n    if (x > 0) return 1;\n  }\n}\n");
        var b = Files.createDirectory(dir.resolve("b"));
        Files.writeString(b.resolve("X.java"), "public class X {\n  public int f(int n) {\n    return n + 1;\n  }\n  public int h(int n) {\n    return k * 2;\n  }\n}\n");
        Files.writeString(b.resolve("Y.java"), "public class Y {\n  public int g(int x) {\n    if (x > 0) return 1;\n    return 0;\n  }\n}\n");
        var originals = List.of(
            new ClassInfo("X", "java/lang/Object", 52, 0, 1, List.of(), List.of(),
                List.of(new MemberInfo("<init>", "()V", 1), new MemberInfo("f", "(I)I", 1), new MemberInfo("h", "(I)I", 1))),
            new ClassInfo("Y", "java/lang/Object", 52, 0, 1, List.of(), List.of(), List.of(new MemberInfo("<init>", "()V", 1), new MemberInfo("g", "(I)I", 1))));
        var result = new SourceSelector().select(Map.of("a", a, "b", b), dir.resolve("selection"), originals, Duration.ofSeconds(60), List.of(), jar);
        String report = Files.readString(dir.resolve("selection/selection.txt"));
        assertTrue(result.verification().complete(), report);
        assertTrue(result.candidate().startsWith("swap-"), report);
        assertTrue(report.contains("after composition: Y.java taken from b (kept); eligible for selection=true"), report);
        assertEquals("b", result.provenance().get("Y.java"));
        assertFalse(result.methods().isEmpty(), report);
    }
    @Test void aFileTakenFromAnotherEngineIsRepairedInASecondCycle() throws Exception {
        // Like CFR 0.152: the swapped-in file has fewer errors, and the rest is a repairable final-local reassignment.
        var a = Files.createDirectory(dir.resolve("a"));
        Files.writeString(a.resolve("X.java"), "public class X { public int f(int n) { int c = n; if (c != true) return 1; return c; } }");
        Files.writeString(a.resolve("Y.java"), "public class Y { public int g(int x) { return x;\n x++; }\n public int h(int x) { return x;\n x--; } }");
        var b = Files.createDirectory(dir.resolve("b"));
        Files.writeString(b.resolve("X.java"), "public class X { public int f(int n) { broken syntax here } }");
        Files.writeString(b.resolve("Y.java"), "public class Y { public int g(int x) { final int v = x; v = x + 1; return v; }\n public int h(int x) { return x; } }");
        var originals = List.of(
            new ClassInfo("X", "java/lang/Object", 52, 0, 1, List.of(), List.of(), List.of(new MemberInfo("<init>", "()V", 1), new MemberInfo("f", "(I)I", 1))),
            new ClassInfo("Y", "java/lang/Object", 52, 0, 1, List.of(), List.of(),
                List.of(new MemberInfo("<init>", "()V", 1), new MemberInfo("g", "(I)I", 1), new MemberInfo("h", "(I)I", 1))));
        var result = new SourceSelector().select(Map.of("a", a, "b", b), dir.resolve("selection"), originals, Duration.ofSeconds(30));
        String report = Files.readString(dir.resolve("selection/selection.txt"));
        assertTrue(result.verification().complete(), report);
        assertTrue(result.candidate().startsWith("repair-"), report);
        assertEquals("b+repair", result.provenance().get("Y.java"));
        assertTrue(report.contains("after repairs: Y.java taken from b (kept)"), report);
        assertTrue(report.contains("drop-final-local"), report);
    }
    @Test void aPostRepairSwapThatLosesSignaturesIsNotKeptAsTheRepairBase() throws Exception {
        // Like Caffeine (Phase 2CU): engine "b"'s Y has fewer errors, but its error is in a member type, reported before
        // javac generates any class, so A's signatures are lost. Fewer errors alone no longer makes it the repair base.
        var a = Files.createDirectory(dir.resolve("a"));
        Files.writeString(a.resolve("A.java"), "public class A { public int f(int n) { return n; } }");
        Files.writeString(a.resolve("Y.java"), "public class Y { public int g(int x) { return zz1 + zz2; } }");
        var b = Files.createDirectory(dir.resolve("b"));
        Files.writeString(b.resolve("A.java"), "public class A { public int f(int n) { broken syntax here } }");
        Files.writeString(b.resolve("Y.java"), "public class Y { Missing m; public int g(int x) { return x; } }");
        var originals = List.of(
            new ClassInfo("A", "java/lang/Object", 52, 0, 1, List.of(), List.of(), List.of(new MemberInfo("<init>", "()V", 1), new MemberInfo("f", "(I)I", 1))),
            new ClassInfo("Y", "java/lang/Object", 52, 0, 1, List.of(), List.of(), List.of(new MemberInfo("<init>", "()V", 1), new MemberInfo("g", "(I)I", 1))));
        var result = new SourceSelector().select(Map.of("a", a, "b", b), dir.resolve("selection"), originals, Duration.ofSeconds(30));
        String report = Files.readString(dir.resolve("selection/selection.txt"));
        assertTrue(report.contains("after repairs: Y.java taken from b (reverted); eligible for selection=false"), report);
        assertEquals("a", result.provenance().get("Y.java"), report);
        assertTrue(result.verification().matchedSymbols() > 0, report);
    }
    @Test void roundTripBreaksTiesBetweenStructurallyEqualCandidates() throws Exception {
        // Both candidates compile with the same signatures; only "second" recompiles to the original body bytes.
        Path original = Files.createDirectories(dir.resolve("original-src"));
        Files.writeString(original.resolve("A.java"), "public class A { public int m(int x) { return x + 1; } }");
        Path classes = Files.createDirectories(dir.resolve("original-classes"));
        assertEquals(0, javax.tools.ToolProvider.getSystemJavaCompiler().run(null, null, null, "--release", "8", "-Xlint:-options",
            "-proc:none", "-d", classes.toString(), original.resolve("A.java").toString()));
        Path jar = dir.resolve("classes.jar");
        try (var zip = new java.util.zip.ZipOutputStream(Files.newOutputStream(jar))) {
            zip.putNextEntry(new java.util.zip.ZipEntry("A.class")); zip.write(Files.readAllBytes(classes.resolve("A.class"))); zip.closeEntry();
        }
        var first = candidate("first", "public class A { public int m(int x) { return 1 + x; } }", null);
        var second = candidate("second", "public class A { public int m(int x) { return x + 1; } }", null);
        var originals = List.of(new ClassInfo("A", "java/lang/Object", 52, 0, 1, List.of(), List.of(),
            List.of(new MemberInfo("<init>", "()V", 1), new MemberInfo("m", "(I)I", 1))));
        var result = new SourceSelector().select(Map.of("first", first, "second", second), dir.resolve("selection"), originals,
            Duration.ofSeconds(20), List.of(), jar);
        assertTrue(result.verification().complete());
        assertEquals("second", result.candidate());
        String report = Files.readString(dir.resolve("selection/selection.txt"));
        assertTrue(report.contains("first: COMPILED") && report.contains("round-trip-recovered=1/2"), report);
        assertTrue(report.contains("second: COMPILED") && report.contains("round-trip-recovered=2/2"), report);
    }
    @Test void aMethodThatFailsInEveryWholeFileIsTakenFromTheEngineThatGotItRight() throws Exception {
        // Like Guava 17.0: each engine breaks a different method of the same file, so no whole-file choice compiles and no
        // repair rule applies. Only composing f from b with g from a compiles, and both bodies recompile to the original.
        Path original = Files.createDirectories(dir.resolve("original-src"));
        Files.writeString(original.resolve("X.java"), "public class X { public int f(int n) { return n * 3 + 1; } public int g(int x) { return x - 3; } }");
        Path classes = Files.createDirectories(dir.resolve("original-classes"));
        assertEquals(0, javax.tools.ToolProvider.getSystemJavaCompiler().run(null, null, null, "--release", "8", "-Xlint:-options",
            "-proc:none", "-d", classes.toString(), original.resolve("X.java").toString()));
        Path jar = dir.resolve("classes.jar");
        try (var zip = new java.util.zip.ZipOutputStream(Files.newOutputStream(jar))) {
            zip.putNextEntry(new java.util.zip.ZipEntry("X.class")); zip.write(Files.readAllBytes(classes.resolve("X.class"))); zip.closeEntry();
        }
        var a = Files.createDirectory(dir.resolve("a"));
        Files.writeString(a.resolve("X.java"), "public class X {\n  public int f(int n) {\n    return n * three + 1;\n  }\n  public int g(int x) {\n    return x - 3;\n  }\n}\n");
        var b = Files.createDirectory(dir.resolve("b"));
        Files.writeString(b.resolve("X.java"), "public class X {\n  public int f(int value) { return value * 3 + 1; }\n  public int g(int x) { return x - missing; }\n}\n");
        var originals = List.of(new ClassInfo("X", "java/lang/Object", 52, 0, 1, List.of(), List.of(),
            List.of(new MemberInfo("<init>", "()V", 1), new MemberInfo("f", "(I)I", 1), new MemberInfo("g", "(I)I", 1))));
        var result = new SourceSelector().select(Map.of("a", a, "b", b), dir.resolve("selection"), originals, Duration.ofSeconds(60), List.of(), jar);
        String report = Files.readString(dir.resolve("selection/selection.txt"));
        assertTrue(result.verification().complete(), report);
        assertEquals("compose-1", result.candidate(), report);
        assertEquals(0, result.verification().roundTripDifferent(), report);
        assertEquals("a+methods", result.provenance().get("X.java"));
        assertEquals(List.of(new dev.classrevive.verification.MethodSplice.Splice("X.java", "X", "f", "(I)I", "b", "IDENTICAL", 2, 4, List.of())), result.methods());
        assertTrue(report.contains("compose proposal: OUTPUT_PRODUCED; methods=1 in 1 file(s); left=0"), report);
        assertTrue(report.contains("X.java <- a+methods\n  method X.f(I)I <- b (round trip in place: IDENTICAL)\n"), report);
        String selected = Files.readString(dir.resolve("selection/sources/X.java"));
        assertTrue(selected.contains("public int f(int value) { return value * 3 + 1; }") && selected.contains("return x - 3;"), selected);
        // Without the original classes there is no round trip to prove a body, so nothing is composed.
        var unproved = new SourceSelector().select(Map.of("a", a, "b", b), dir.resolve("unproved"), originals, Duration.ofSeconds(60));
        assertFalse(unproved.verification().complete());
        assertEquals(List.of(), unproved.methods());
        assertFalse(Files.readString(dir.resolve("unproved/selection.txt")).contains("Method composition"));
    }
    @Test void aCompilingSetTakesTheBodyAnotherEngineRecompilesToTheOriginal() throws Exception {
        // Like Gson, ASM and CFR: every engine compiles, but each writes a different method whose bytecode is not the
        // original's. Only composing f from b into a recovers every body.
        Path original = Files.createDirectories(dir.resolve("original-src"));
        Files.writeString(original.resolve("X.java"), "public class X { public int f(int n) { return n * 3 + 1; } public int g(int x) { return x - 3; } }");
        Path classes = Files.createDirectories(dir.resolve("original-classes"));
        assertEquals(0, javax.tools.ToolProvider.getSystemJavaCompiler().run(null, null, null, "--release", "8", "-Xlint:-options",
            "-proc:none", "-d", classes.toString(), original.resolve("X.java").toString()));
        Path jar = dir.resolve("classes.jar");
        try (var zip = new java.util.zip.ZipOutputStream(Files.newOutputStream(jar))) {
            zip.putNextEntry(new java.util.zip.ZipEntry("X.class")); zip.write(Files.readAllBytes(classes.resolve("X.class"))); zip.closeEntry();
        }
        var a = Files.createDirectory(dir.resolve("a"));
        Files.writeString(a.resolve("X.java"), "public class X {\n  public int f(int n) {\n    return 1 + n * 3;\n  }\n  public int g(int x) {\n    return x - 3;\n  }\n}\n");
        var b = Files.createDirectory(dir.resolve("b"));
        Files.writeString(b.resolve("X.java"), "public class X {\n  public int f(int value) { return value * 3 + 1; }\n  public int g(int x) { return -3 + x; }\n}\n");
        var originals = List.of(new ClassInfo("X", "java/lang/Object", 52, 0, 1, List.of(), List.of(),
            List.of(new MemberInfo("<init>", "()V", 1), new MemberInfo("f", "(I)I", 1), new MemberInfo("g", "(I)I", 1))));
        var result = new SourceSelector().select(Map.of("a", a, "b", b), dir.resolve("selection"), originals, Duration.ofSeconds(60), List.of(), jar);
        String report = Files.readString(dir.resolve("selection/selection.txt"));
        assertTrue(report.contains("a: COMPILED") && report.contains("b: COMPILED") && report.contains("round-trip-recovered=2/3"), report);
        assertEquals("recompose-1", result.candidate(), report);
        assertTrue(result.verification().complete(), report);
        assertEquals(0, result.verification().roundTripDifferent(), report);
        assertEquals("a+methods", result.provenance().get("X.java"));
        assertEquals(List.of(new dev.classrevive.verification.MethodSplice.Splice("X.java", "X", "f", "(I)I", "b", "IDENTICAL", 2, 4, List.of(),
            dev.classrevive.verification.MethodSplice.ROUND_TRIP_DIFFERENT, "DIFFERENT", List.of(), List.of())), result.methods());
        assertTrue(report.contains("recompose proposal: OUTPUT_PRODUCED; methods=1 in 1 file(s); left=0"), report);
        assertTrue(report.contains("recompose-1: COMPILED") && report.contains("round-trip-recovered=3/3"), report);
        assertTrue(report.contains("  files kept=1, reverted=0; eligible for selection=true\n"), report);
        assertTrue(report.contains("X.java <- a+methods\n  method X.f(I)I <- b (round trip in place: DIFFERENT -> IDENTICAL)\n"), report);
        String selected = Files.readString(dir.resolve("selection/sources/X.java"));
        assertTrue(selected.contains("public int f(int value) { return value * 3 + 1; }") && selected.contains("return x - 3;"), selected);
        // Without the original classes the round trip is not measured, so nothing is composed and engine a stays selected.
        var unproved = new SourceSelector().select(Map.of("a", a, "b", b), dir.resolve("unproved"), originals, Duration.ofSeconds(60));
        assertEquals("a", unproved.candidate());
        assertEquals(List.of(), unproved.methods());
        assertFalse(Files.readString(dir.resolve("unproved/selection.txt")).contains("Method composition"));
    }
    @Test void aDifferentLambdaIsRecoveredThroughTheMethodThatHoldsIt() throws Exception {
        // Engine a gets r() itself right but not its lambda (lambda$r$0); engine b breaks g instead. Phase 2BG maps the
        // lambda to r() and takes r()'s whole body from b.
        Path original = Files.createDirectories(dir.resolve("original-src"));
        Files.writeString(original.resolve("X.java"), "public class X { public Runnable r() { return () -> { int x = 5; System.out.println(x * 3 + 1); }; }"
            + " public int g(int x) { return x - 3; } }");
        Path classes = Files.createDirectories(dir.resolve("original-classes"));
        assertEquals(0, javax.tools.ToolProvider.getSystemJavaCompiler().run(null, null, null, "--release", "8", "-Xlint:-options",
            "-proc:none", "-d", classes.toString(), original.resolve("X.java").toString()));
        Path jar = dir.resolve("classes.jar");
        try (var zip = new java.util.zip.ZipOutputStream(Files.newOutputStream(jar))) {
            zip.putNextEntry(new java.util.zip.ZipEntry("X.class")); zip.write(Files.readAllBytes(classes.resolve("X.class"))); zip.closeEntry();
        }
        var a = Files.createDirectory(dir.resolve("a"));
        Files.writeString(a.resolve("X.java"), "public class X {\n  public Runnable r() {\n    return () -> { int x = 5; System.out.println(1 + x * 3); };\n  }\n"
            + "  public int g(int x) {\n    return x - 3;\n  }\n}\n");
        var b = Files.createDirectory(dir.resolve("b"));
        Files.writeString(b.resolve("X.java"), "public class X {\n  public Runnable r() { return () -> { int x = 5; System.out.println(x * 3 + 1); }; }\n"
            + "  public int g(int x) { return -3 + x; }\n}\n");
        var originals = List.of(new ClassInfo("X", "java/lang/Object", 52, 0, 1, List.of(), List.of(),
            List.of(new MemberInfo("<init>", "()V", 1), new MemberInfo("r", "()Ljava/lang/Runnable;", 1), new MemberInfo("g", "(I)I", 1))));
        var result = new SourceSelector().select(Map.of("a", a, "b", b), dir.resolve("selection"), originals, Duration.ofSeconds(60), List.of(), jar);
        String report = Files.readString(dir.resolve("selection/selection.txt"));
        assertTrue(report.contains("round-trip-recovered=3/4"), report);
        assertEquals("recompose-1", result.candidate(), report);
        assertEquals(0, result.verification().roundTripDifferent(), report);
        assertEquals(List.of(new dev.classrevive.verification.MethodSplice.Splice("X.java", "X", "r", "()Ljava/lang/Runnable;", "b", "IDENTICAL", 2, 4,
            List.of(), dev.classrevive.verification.MethodSplice.ROUND_TRIP_DIFFERENT, "IDENTICAL", List.of("X.lambda$r$0()V"), List.of())), result.methods());
        assertTrue(report.contains("X.java <- a+methods\n  method X.r()Ljava/lang/Runnable; <- b (round trip in place: IDENTICAL; also recovered: X.lambda$r$0()V)\n"), report);
    }
    @Test void incompleteCompileIsReportedAsPartial() throws Exception {
        var first = candidate("first", "public class A {}", null);
        var result = new SourceSelector().select(Map.of("first",first), dir.resolve("selection"), List.of(info("A"),info("B")), Duration.ofSeconds(20));
        assertFalse(result.verification().complete());
        assertTrue(Files.readString(dir.resolve("selection/selection.txt")).contains("PARTIAL"));
    }
    @Test void reportsStructuralCoverageForEachOriginalClass() throws Exception {
        var a = new ClassInfo("A", "java/lang/Object", 52, 0, 1, List.of(), List.of(), List.of(
            new MemberInfo("<init>", "()V", 1), new MemberInfo("answer", "()I", 1), new MemberInfo("extra", "()I", 1)));
        var b = info("B");
        var result = new SourceSelector().select(Map.of("one", candidate("one", "public class A { public int answer(){return 1;} }", "public class B {}")),
            dir.resolve("selection"), List.of(a, b), Duration.ofSeconds(20));
        String report = Files.readString(dir.resolve("selection/selection.txt"));
        assertTrue(report.contains("A: 3/4 signatures"), report);
        assertTrue(report.contains("B: 2/2 signatures"), report);
        var coverage = new TreeMap<String,dev.classrevive.verification.ClassCoverage>();
        result.verification().classCoverage().forEach(value -> coverage.put(value.className(), value));
        assertEquals(3, coverage.get("A").matchedSymbols());
        assertEquals(4, coverage.get("A").expectedSymbols());
        assertTrue(coverage.get("B").complete());
        assertEquals(875, result.verification().averageClassCoveragePermille());
    }
    @Test void balancesCoverageAcrossClassesInsteadOfFavoringOneLargeClass() throws Exception {
        var aMethods = new ArrayList<MemberInfo>();
        aMethods.add(new MemberInfo("<init>", "()V", 0));
        for (int i = 0; i < 6; i++) aMethods.add(new MemberInfo("m" + i, "()I", 0));
        var a = new ClassInfo("A", "java/lang/Object", 52, 0, 0, List.of(), List.of(), aMethods);
        var bMethods = new ArrayList<MemberInfo>();
        bMethods.add(new MemberInfo("<init>", "()V", 0));
        for (int i = 0; i < 10; i++) bMethods.add(new MemberInfo("m" + i, "()I", 0));
        var b = new ClassInfo("B", "java/lang/Object", 52, 0, 0, List.of(), List.of(), bMethods);
        String balanced = source("A", 2) + " " + source("B", 10);
        String lopsided = source("A", 6) + " " + source("B", 0);
        var candidates = Map.of("balanced", candidate("balanced", balanced, null), "lopsided", candidate("lopsided", lopsided, null));
        var result = new SourceSelector().select(candidates, dir.resolve("selection"), List.of(a, b), Duration.ofSeconds(20));
        assertEquals("balanced", result.candidate(), Files.readString(dir.resolve("selection/selection.txt")));
    }
    Path tree(String id, Map<String,String> files) throws Exception {
        var root = Files.createDirectory(dir.resolve(id));
        for (var file : files.entrySet()) Files.writeString(root.resolve(file.getKey()), file.getValue());
        return root;
    }
    @Test void aFamilyInTheOtherNestingFormIsTakenWholeSoNoBinaryNameIsDeclaredTwice() throws Exception {
        // Phase 2BX: without InnerClasses one engine writes A$B.java top-level, another nests B inside A.java. Taking only
        // A$B.java alone from the top-level engine would declare A$B twice (compiler.err.same.binary.name).
        var nested = tree("nested", Map.of("A.java", "/* loaded from: A$B.class */ public class A { Missing m; B b; static class B {} }",
            "C.java", "public class C { D d; static class D {} }"));
        var top = tree("top", Map.of("A.java", "public class A { A$B b; }", "A$B.java", "class A$B {}",
            "C.java", "public class C { C$D d; Missing1 x; Missing2 y; }", "C$D.java", "class C$D {}"));
        var result = new SourceSelector().select(Map.of("nested", nested, "top", top), dir.resolve("selection"),
            List.of(info("A"), info("A$B"), info("C"), info("C$D")), Duration.ofSeconds(30));
        String report = Files.readString(dir.resolve("selection/selection.txt"));
        assertEquals(VerificationResult.Status.COMPILED, result.verification().status(), report);
        assertEquals(Map.of("A.java", "top", "A$B.java", "top", "C.java", "nested"), result.provenance(), report);
        assertTrue(report.contains("1 of them as a whole nesting family"), report);
        for (var attempt : result.tried())
            assertFalse(attempt.origins().containsKey("A$B.java") && "nested".equals(attempt.origins().get("A.java")), report);
    }
    @Test void aNestingOuterFileNeverReplacesATopLevelFamily() throws Exception {
        // Other files name top-level members by binary name (A$B), so a nested A.java would leave them unresolved.
        var top = tree("top", Map.of("A.java", "public class A { Missing m; A$B b; }", "A$B.java", "class A$B {}",
            "U.java", "class U { A$B b; }"));
        var nested = tree("nested", Map.of("A.java", "public class A { B b; static class B {} }", "U.java", "class U { A.B b; Missing u; Missing2 v; }"));
        var result = new SourceSelector().select(Map.of("nested", nested, "top", top), dir.resolve("selection"),
            List.of(info("A"), info("A$B"), info("U")), Duration.ofSeconds(30));
        String report = Files.readString(dir.resolve("selection/selection.txt"));
        for (var attempt : result.tried())
            if (attempt.origins().containsKey("A$B.java")) assertEquals("top", attempt.origins().get("A.java"), attempt.id() + "\n" + report);
        assertEquals("top", result.candidate(), report);
        assertEquals("top", result.provenance().get("A.java"), report);
    }
    @Test void anOuterFileThatNamesItsMemberByBinaryNameIsNotANestedForm() throws Exception {
        // The partial engine failed on A$B: its A.java still refers to A$B, so the member file is missing, not nested,
        // and the other engine's A$B.java is added alone rather than replacing the family.
        var partial = tree("partial", Map.of("A.java", "public class A { A$B b; }"));
        var top = tree("top", Map.of("A.java", "public class A { Missing m; A$B b; }", "A$B.java", "class A$B {}"));
        var result = new SourceSelector().select(Map.of("partial", partial, "top", top), dir.resolve("selection"),
            List.of(info("A"), info("A$B")), Duration.ofSeconds(30));
        String report = Files.readString(dir.resolve("selection/selection.txt"));
        assertEquals(VerificationResult.Status.COMPILED, result.verification().status(), report);
        assertEquals(Map.of("A.java", "partial", "A$B.java", "top"), result.provenance(), report);
    }
    @Test void anEnumConstantBodyWrittenAsItsOwnFileIsFoldedBackIntoTheEnum() throws Exception {
        // Phase 2BX: the body of HIGH, written as top-level E$1 extending E, cannot compile (E is final without bodies).
        // The original <clinit> assigns NEW E$1 to HIGH, so its members become HIGH's body.
        Path src = Files.createDirectories(dir.resolve("original/src")), out = Files.createDirectories(dir.resolve("original/out"));
        Files.writeString(src.resolve("E.java"), "public enum E { LOW(1), HIGH(2) { int weight() { return code * 10; } }; "
            + "final int code; E(int code) { this.code = code; } int weight() { return code; } }");
        assertEquals(0, javax.tools.ToolProvider.getSystemJavaCompiler().run(null, null, null, "--release", "8", "-Xlint:-options",
            "-proc:none", "-d", out.toString(), src.resolve("E.java").toString()));
        Path jar = dir.resolve("original/classes.jar");
        try (var zip = new java.util.zip.ZipOutputStream(Files.newOutputStream(jar))) {
            for (String name : List.of("E", "E$1")) {
                zip.putNextEntry(new java.util.zip.ZipEntry(name + ".class")); zip.write(Files.readAllBytes(out.resolve(name + ".class"))); zip.closeEntry();
            }
        }
        var originals = List.of(new ClassInfo("E", "java/lang/Enum", 52, 0, 0x4421, List.of(), List.of(), List.of()),
            new ClassInfo("E$1", "E", 52, 0, 0x4010, List.of(), List.of(), List.of()));
        var split = tree("split", Map.of(
            "E.java", "/* E$1 */ public enum E {\n    LOW(1),\n    HIGH(2);\n    final int code;\n    E(int code) { this.code = code; }\n"
                + "    int weight() { return code; }\n}\n",
            "E$1.java", "import java.util.List;\n\nfinal class E$1 extends E {\n    E$1(String name, int ordinal, int code) {\n    }\n\n"
                + "    @Override\n    int weight() { List<String> none = null; return code * 10; }\n}\n"));
        var result = new SourceSelector().select(Map.of("split", split), dir.resolve("selection"), originals, Duration.ofSeconds(30),
            List.of(), jar);
        String report = Files.readString(dir.resolve("selection/selection.txt"));
        assertEquals(VerificationResult.Status.COMPILED, result.verification().status(), report);
        assertEquals(List.of(new dev.classrevive.verification.EnumBodyFolding.Fold("E.java", "E$1.java", "HIGH")), result.folds());
        String folded = Files.readString(dir.resolve("selection/sources/E.java"));
        assertTrue(folded.contains("HIGH(2) {"), folded);
        assertTrue(folded.contains("return code * 10;"), folded);
        assertTrue(folded.contains("import java.util.List;"), folded);
        assertFalse(folded.contains("E$1("), folded);
        assertFalse(Files.exists(dir.resolve("selection/sources/E$1.java")));
        assertTrue(report.contains("  E$1.java -> E.java (constant HIGH)\n"), report);
        assertTrue(report.contains("E$1.java <- split (folded into E.java)\n"), report);
        assertTrue(Files.readString(split.resolve("E$1.java")).contains("extends E"), "engine output is never modified");
    }
    @Test void aFileThatDeclaresNoTypeNeverSuppliesAClass() throws Exception {
        // Phase 2BY: Procyon writes a 0-byte file for each class it fails on. It compiled with 0 errors, so the per-file
        // hybrid took it over a real version with errors, and the class vanished from the selected sources.
        var real = tree("real", Map.of("A.java", "public class A { Missing m; }", "B.java", "public class B { A a; }"));
        var empty = tree("empty", Map.of("A.java", "", "B.java", "public class B { A a; Missing x; Missing2 y; }"));
        var result = new SourceSelector().select(Map.of("empty", empty, "real", real), dir.resolve("selection"),
            List.of(info("A"), info("B")), Duration.ofSeconds(30));
        String report = Files.readString(dir.resolve("selection/selection.txt"));
        assertEquals("real", result.provenance().get("A.java"), report);
        for (var attempt : result.tried()) assertNotEquals("empty", attempt.origins().get("A.java"), attempt.id() + "\n" + report);
        assertEquals("public class A { Missing m; }", Files.readString(dir.resolve("selection/sources/A.java")));
        assertTrue(report.contains("empty: 1 file(s) declare no type (empty or comments only); they supply no class"), report);
    }
    @Test void classesLeftEmptyByTheBestEngineTreeAreFilledInOnceTheHybridsAreSpent() throws Exception {
        // Procyon (the tree that parses) has Y.java empty and Z.java with a comment only, so the selection grows from a
        // tree without Y and Z. CFR's nine F files differ only in a comment, so the single-file hybrids spend the budget
        // before they reach Y and Z. The fill then takes CFR's real Y and Z. package-info.java declares no type by design.
        var procyonFiles = new HashMap<>(Map.of("A.java", "public class A { Y y; Z z; }", "Y.java", "", "Z.java", "/* class Z failed */",
            "package-info.java", "/** Docs. */"));
        var cfrFiles = new HashMap<>(Map.of("A.java", "public class A { broken }", "Y.java", "public class Y {}", "Z.java", "public class Z { int v = 1; }"));
        var originals = new ArrayList<>(List.of(info("A"), info("Y"), info("Z")));
        for (int i = 0; i < 9; i++) {
            String name = "F0" + i; originals.add(info(name));
            procyonFiles.put(name + ".java", "public class " + name + " {}");
            cfrFiles.put(name + ".java", "public class " + name + " { /* cfr */ }");
        }
        var result = new SourceSelector().select(Map.of("cfr", tree("cfr", cfrFiles), "procyon", tree("procyon", procyonFiles)),
            dir.resolve("selection"), originals, Duration.ofSeconds(60));
        String report = Files.readString(dir.resolve("selection/selection.txt"));
        assertTrue(result.verification().complete(), report);
        assertEquals("filled", result.candidate(), report);
        assertEquals("cfr", result.provenance().get("Y.java"), report);
        assertEquals("cfr", result.provenance().get("Z.java"), report);
        assertEquals("procyon", result.provenance().get("A.java"), report);
        assertEquals("procyon", result.provenance().get("package-info.java"), report);
        assertTrue(report.contains("procyon: 2 file(s) declare no type (empty or comments only); they supply no class"), report);
        assertTrue(report.contains("Files that declare no type, absent from procyon: 2 taken from another engine, 0 declared by no engine"), report);
        try (var files = Files.list(dir.resolve("selection/sources"))) {
            for (var file : files.toList()) assertTrue(Files.size(file) > 0, file.toString());
        }
    }
    @Test void classesMissingFromATruncatedEngineTreeAreFilledInFromAnotherEngine() throws Exception {
        // Phase 2CK: Procyon timed out after A.java, nine F files and an empty Y.java, so the selection grows from its
        // few files. CFR wrote every class; its F files differ only in a comment, so the single-file hybrids spend their
        // budget before they reach W, X and Y (as in the 2BY test). The fill takes W, X and Y from CFR.
        var procyonFiles = new HashMap<>(Map.of("A.java", "public class A { }", "Y.java", ""));
        var cfrFiles = new HashMap<>(Map.of("A.java", "public class A { broken }", "W.java", "public class W {}",
            "X.java", "public class X { int v = 1; }", "Y.java", "public class Y {}", "Extra.java", "public class Extra {}"));
        var originals = new ArrayList<>(List.of(info("A"), info("W"), info("X"), info("Y")));
        for (int i = 0; i < 9; i++) {
            String name = "F0" + i; originals.add(info(name));
            procyonFiles.put(name + ".java", "public class " + name + " {}");
            cfrFiles.put(name + ".java", "public class " + name + " { /* cfr */ }");
        }
        var result = new SourceSelector().select(Map.of("cfr", tree("cfr", cfrFiles), "procyon", tree("procyon", procyonFiles)),
            dir.resolve("selection"), originals, Duration.ofSeconds(60));
        String report = Files.readString(dir.resolve("selection/selection.txt"));
        assertTrue(result.verification().complete(), report);
        assertEquals("filled", result.candidate(), report);
        assertEquals("procyon", result.provenance().get("A.java"), report);
        for (var file : List.of("W.java", "X.java", "Y.java")) assertEquals("cfr", result.provenance().get(file), report);
        // Extra.java is no class of the input: an engine's stray file is not filled in.
        assertFalse(result.provenance().containsKey("Extra.java"), report);
        assertTrue(report.contains("Files missing from procyon (an engine tree without them, e.g. timed out): 2 taken from another engine"), report);
        assertTrue(report.contains("Files that declare no type, absent from procyon: 1 taken from another engine, 0 declared by no engine"), report);
    }
    @Test void aMissingFamilyIsFilledInItsTopLevelFormNotFromTheEngineThatNestsIt() throws Exception {
        // Phase 2CK with 2BX: the truncated tree lacks family B. JADX nests C inside B.java, CFR writes B$C.java; the
        // other files name B$C by binary name, so the top-level form is the one filled in, although JADX has fewer errors.
        var procyon = tree("procyon", Map.of("A.java", "public class A { B b; B$C c; }"));
        var jadx = tree("jadx", Map.of("A.java", "public class A { broken }", "B.java", "public class B { static class C {} }"));
        var cfr = tree("cfr", Map.of("A.java", "public class A { broken broken broken }", "B.java", "public class B { }",
            "B$C.java", "public class B$C {}"));
        var result = new SourceSelector().select(Map.of("cfr", cfr, "jadx", jadx, "procyon", procyon), dir.resolve("selection"),
            List.of(info("A"), info("B"), info("B$C")), Duration.ofSeconds(60));
        String report = Files.readString(dir.resolve("selection/selection.txt"));
        assertTrue(result.verification().complete(), report);
        assertEquals("cfr", result.provenance().get("B.java"), report);
        assertEquals("cfr", result.provenance().get("B$C.java"), report);
    }
    @Test void jadxPlaceholderTypesAreWrittenAsDeclaredTypesSoTheFileParses() throws Exception {
        // Phase 2CL: JADX's `??` is no Java; one such file stops javac at parsing. Typed from its initializer, it compiles.
        var jadx = tree("jadx", Map.of("A.java", """
            public class A {
                static boolean f() { return true; }
                int g() {
                    ?? b = f();
                    return b ? 1 : 0;
                }
            }
            """));
        var result = new SourceSelector().select(Map.of("jadx", jadx), dir.resolve("selection"), List.of(info("A")), Duration.ofSeconds(60));
        String report = Files.readString(dir.resolve("selection/selection.txt"));
        assertTrue(result.verification().complete(), report);
        assertTrue(report.contains("jadx: JADX output that is no Java fixed in 1 file(s) (Phase 2CL): 1 placeholder type(s) `??` (1 from the initializer, 0 as Object), 0 oversized integer literal(s) (0 long, 0 double bits)"), report);
        assertTrue(Files.readString(dir.resolve("selection/sources/A.java")).contains("boolean /* ?? */ b = f();"));
    }
    @Test void typeDeclarationsAreFoundOutsideCommentsAndStringsOnly() throws Exception {
        var file = dir.resolve("X.java");
        for (var text : List.of("", "// class X {}\n", "/* enum E {} */", "String s = \"class X\";", "package a;\nimport b.C;\n")) {
            Files.writeString(file, text); assertFalse(SourceSelector.declaresType(file), text);
        }
        for (var text : List.of("class X {}", "public @interface X {}", "/* c */ enum E { A }", "record R(int a) {}", "final class \u00e9t\u00e9 {}")) {
            Files.writeString(file, text); assertTrue(SourceSelector.declaresType(file), text);
        }
    }
    @Test void aFileJavacNeverReachedInACappedCompilationIsNotCountedAsClean() {
        // Phase 2BY: past -Xmaxerrs javac stops reporting, so an unlisted file of such a compilation is unknown, not clean.
        var capped = new VerificationResult(VerificationResult.Status.COMPILE_FAILED, SourceSelector.JAVAC_ERROR_CAP, 0, 1, 0, "",
            List.of(), List.of(), SourceSelector.JAVAC_ERROR_CAP, Map.of("A.java", 1000));
        var complete = new VerificationResult(VerificationResult.Status.COMPILE_FAILED, 87, 0, 1, 0, "",
            List.of(), List.of(), 87, Map.of("B.java", 87));
        assertTrue(SourceSelector.fileErrors(capped, "B.java") > SourceSelector.fileErrors(complete, "B.java"));
        assertEquals(1000, SourceSelector.fileErrors(capped, "A.java"));
        assertEquals(0, SourceSelector.fileErrors(complete, "C.java"));
    }
    @Test void enginePreferenceOrdersOnlyKotlinFilesAndOnlyBlindTreesGetAByLanguageMix() {
        // Phase 2DG: JADX first for Kotlin files; Java files and inputs without Kotlin keep the name order.
        var preference = new EnginePreference(Set.of("k/Main.java"), "test");
        var alternatives = new TreeMap<String,Integer>(Map.of("cfr", 1, "jadx", 2, "procyon", 3, "vineflower", 4));
        assertEquals(List.of("jadx", "cfr", "procyon", "vineflower"), preference.order("k/Main.java", alternatives).stream().map(Map.Entry::getKey).toList());
        assertEquals(List.of("cfr", "jadx", "procyon", "vineflower"), preference.order("j/Util.java", alternatives).stream().map(Map.Entry::getKey).toList());
        assertEquals(List.of("cfr", "jadx", "procyon", "vineflower"), EnginePreference.NONE.order("k/Main.java", alternatives).stream().map(Map.Entry::getKey).toList());
        assertEquals("jadx", preference.preferred("k/Main.java")); assertEquals("vineflower", preference.preferred("j/Util.java"));
        assertFalse(EnginePreference.NONE.active());
        // Blind: both stopped while parsing at javac's cap. A tree that parsed, or one under the cap, is still compared by evidence.
        var capped = new VerificationResult(VerificationResult.Status.COMPILE_FAILED, SourceSelector.JAVAC_ERROR_CAP, 0, 1, 0, "",
            List.of(), List.of(), SourceSelector.JAVAC_ERROR_CAP, Map.of("A.java", 1000));
        var cappedToo = new VerificationResult(VerificationResult.Status.COMPILE_FAILED, SourceSelector.JAVAC_ERROR_CAP, 0, 1, 7, "",
            List.of(), List.of(), SourceSelector.JAVAC_ERROR_CAP, Map.of("B.java", 1000));
        var underCap = new VerificationResult(VerificationResult.Status.COMPILE_FAILED, 40, 0, 1, 0, "",
            List.of(), List.of(), 40, Map.of("B.java", 40));
        var parsedCapped = new VerificationResult(VerificationResult.Status.COMPILE_FAILED, SourceSelector.JAVAC_ERROR_CAP, 0, 1, 0, "",
            List.of(), List.of(), 0, Map.of("B.java", 1000));
        assertTrue(SourceSelector.blindTie(capped, cappedToo));
        assertFalse(SourceSelector.blindTie(capped, underCap));
        assertFalse(SourceSelector.blindTie(capped, parsedCapped));
    }
    @Test void anEnumSwitchPrintedOnOrdinalsIsUndoneInTheSelectedSourcesAndRecorded() throws Exception {
        // Phase 2DH, like the first test JAR: CFR printed javac 21's switch on the ordinal of an enum of the same file.
        Path original = Files.createDirectories(dir.resolve("original-src"));
        Files.writeString(original.resolve("S.java"), "public class S { enum K { A, B, C }\n"
            + " static int f(K k) { switch (k) { case A: return 1; case B: return 2; default: return 3; } } }");
        Path classes = Files.createDirectories(dir.resolve("original-classes"));
        assertEquals(0, javax.tools.ToolProvider.getSystemJavaCompiler().run(null, null, null, "--release", "8", "-Xlint:-options",
            "-proc:none", "-d", classes.toString(), original.resolve("S.java").toString()));
        Path jar = dir.resolve("classes.jar");
        try (var zip = new java.util.zip.ZipOutputStream(Files.newOutputStream(jar))) {
            for (var name : List.of("S.class", "S$K.class")) {
                zip.putNextEntry(new java.util.zip.ZipEntry(name)); zip.write(Files.readAllBytes(classes.resolve(name))); zip.closeEntry();
            }
        }
        var a = Files.createDirectory(dir.resolve("a"));
        Files.writeString(a.resolve("S.java"), "public class S {\n    static int f(K k) {\n        switch (k.ordinal()) {\n"
            + "            case 0: {\n                return 1;\n            }\n            case 1: {\n                return 2;\n            }\n"
            + "        }\n        return 3;\n    }\n\n    static enum K {\n        A,\n        B,\n        C;\n\n    }\n}\n");
        var originals = List.of(
            new ClassInfo("S", "java/lang/Object", 52, 0, 0x21, List.of(), List.of(), List.of(new MemberInfo("<init>", "()V", 1),
                new MemberInfo("f", "(LS$K;)I", 8))),
            new ClassInfo("S$K", "java/lang/Enum", 52, 0, 0x4030, List.of(), List.of(new MemberInfo("A", "LS$K;", 0x4019),
                new MemberInfo("B", "LS$K;", 0x4019), new MemberInfo("C", "LS$K;", 0x4019)), List.of()));
        var result = new SourceSelector().select(Map.of("a", a), dir.resolve("selection"), originals, Duration.ofSeconds(60), List.of(), jar);
        String report = Files.readString(dir.resolve("selection/selection.txt"));
        assertEquals("idioms-1", result.candidate(), report);
        assertEquals(List.of(new dev.classrevive.verification.IdiomResugar.Rewrite("S.java", 3, "enum-switch", "switch (k.ordinal())",
            "switch (k) case A, B")), result.idioms(), report);
        assertEquals(0, result.verification().roundTripDifferent(), report);
        assertTrue(report.contains("Compiler idioms undone in the selected sources (Phase 2DH): 1 rewrite(s) in 1 file(s)"), report);
        assertEquals("a", result.provenance().get("S.java"));
        String selected = Files.readString(dir.resolve("selection/sources/S.java"));
        assertTrue(selected.contains("switch (k) {") && selected.contains("case A: {") && selected.contains("case B: {"), selected);
        // Engine baselines without source repairs keep the engine's text.
        var baseline = new SourceSelector(false).select(Map.of("a", a), dir.resolve("baseline"), originals, Duration.ofSeconds(60), List.of(), jar);
        assertEquals("a", baseline.candidate());
        assertEquals(List.of(), baseline.idioms());
    }
}
