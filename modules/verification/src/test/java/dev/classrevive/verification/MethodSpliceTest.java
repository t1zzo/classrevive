package dev.classrevive.verification;

import dev.classrevive.bytecode.ClassInspector;
import dev.classrevive.model.ClassInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.zip.*;
import static org.junit.jupiter.api.Assertions.*;

/** Per-method composition (Phase 2BD) on small decompiler-shaped files. Sources and classes are compiled, never run. */
class MethodSpliceTest {
    @TempDir Path dir;
    record Original(Path jar, List<ClassInfo> infos) {}

    /** javac's classes for the original sources, as the job's normalized class JAR, with their structural records. */
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

    @Test void takesAFailingBodyFromAnotherEngineWhenItRecompilesInPlaceToTheOriginalBytes() throws Exception {
        var original = original(Map.of("p/Calc.java",
            "package p; public class Calc { private int base = 3; public int f(int n) { return n * base + 1; } public int g(int x) { return x - base; } }"));
        // Engine a misses a name in f; engine b gets f right (under another parameter name) but breaks g, so neither
        // whole file compiles and a whole-file swap cannot help.
        Path a = write("a", Map.of("p/Calc.java", "package p;\n\npublic class Calc {\n   private int base = 3;\n\n"
            + "   public int f(int n) {\n      return n * bse + 1;\n   }\n\n   public int g(int x) {\n      return x - base;\n   }\n}\n"));
        Path b = write("b", Map.of("p/Calc.java", "package p;\npublic class Calc {\n    private int base = 3;\n"
            + "    public int f(int value) { return value * this.base + 1; }\n    public int g(int x) { return x - undefined; }\n}\n"));
        var proposal = new MethodSplice().propose(a, dir.resolve("splice"), original.infos(), Duration.ofSeconds(60), List.of(), original.jar(),
            Map.of("a", a, "b", b));
        assertEquals(List.of(new MethodSplice.Splice("p/Calc.java", "p/Calc", "f", "(I)I", "b", "IDENTICAL", 6, 8, List.of())), proposal.splices(), proposal.toString());
        assertEquals(List.of(), proposal.unspliced());
        String spliced = Files.readString(proposal.files().get("p/Calc.java"));
        assertTrue(spliced.contains("public int f(int value) { return value * this.base + 1; }"), spliced);
        assertTrue(spliced.contains("return x - base;"), spliced);

        // The mix with the spliced file compiles with every signature, and every body recompiles to the original.
        Path merged = write("merged", Map.of("p/Calc.java", spliced));
        var verified = new CompilerVerifier().verify(merged, dir.resolve("verify"), original.infos(), Duration.ofSeconds(60), List.of(), original.jar());
        assertTrue(verified.complete(), verified.toString());
        assertEquals(0, verified.roundTripDifferent(), verified.toString());
    }

    @Test void writesATypeNameCanonicallyWhenTheFileDoesNotImportIt() throws Exception {
        // Like Guava's TreeRangeSet: Vineflower imports java.util.Map.Entry, CFR imports java.util.Map and writes Map.Entry.
        var original = original(Map.of("p/Pairs.java", "package p; import java.util.Map; public class Pairs {\n"
            + " public static int first(Map<String, Integer> map) { Map.Entry<String, Integer> e = map.entrySet().iterator().next(); return e.getValue(); }\n}\n"));
        Path a = write("a", Map.of("p/Pairs.java", "package p;\n\nimport java.util.Map.Entry;\n\npublic class Pairs {\n"
            + "   public static int first(java.util.Map<String, Integer> map) {\n      Entry<String, Integer> e = map.entrySet().iterator().nxt();\n"
            + "      return e.getValue();\n   }\n}\n"));
        Path b = write("b", Map.of("p/Pairs.java", "package p;\nimport java.util.Map;\npublic class Pairs {\n"
            + "    public static int first(Map<String, Integer> map) {\n        Map.Entry<String, Integer> e = map.entrySet().iterator().next();\n"
            + "        return e.getValue();\n    }\n}\n"));
        var proposal = new MethodSplice().propose(a, dir.resolve("splice"), original.infos(), Duration.ofSeconds(60), List.of(), original.jar(),
            Map.of("a", a, "b", b));
        assertEquals(List.of(new MethodSplice.Splice("p/Pairs.java", "p/Pairs", "first", "(Ljava/util/Map;)I", "b", "IDENTICAL", 6, 9,
            List.of("java.util.Map"))), proposal.splices(), proposal.toString());
        String spliced = Files.readString(proposal.files().get("p/Pairs.java"));
        // The file's imports are untouched; only the copied body names the type canonically.
        assertTrue(spliced.contains("import java.util.Map.Entry;") && !spliced.contains("import java.util.Map;"), spliced);
        assertTrue(spliced.contains("java.util.Map.Entry<String, Integer> e = map.entrySet().iterator().next();"), spliced);
    }

    @Test void aConstructorWhoseOwnThisCallFailsIsStubbedWithNeutralArgumentsAndReplaced() throws Exception {
        // Like Guava's AbstractSortedMultiset(): the error is inside this(...), which the stub of the other bodies must keep.
        var original = original(Map.of("p/Sized.java", "package p; public class Sized { final int size;\n"
            + " public Sized() { this(16); }\n public Sized(int size) { this.size = size; }\n}\n"));
        Path a = write("a", Map.of("p/Sized.java", "package p;\npublic class Sized {\n   final int size;\n"
            + "   public Sized() {\n      this(sixteen());\n   }\n   public Sized(int size) {\n      this.size = size;\n   }\n}\n"));
        Path b = write("b", Map.of("p/Sized.java", "package p;\npublic class Sized {\n    final int size;\n"
            + "    public Sized() { this(16); }\n    public Sized(int n) { this.size = m; }\n}\n"));
        var proposal = new MethodSplice().propose(a, dir.resolve("splice"), original.infos(), Duration.ofSeconds(60), List.of(), original.jar(),
            Map.of("a", a, "b", b));
        assertEquals(List.of(new MethodSplice.Splice("p/Sized.java", "p/Sized", "<init>", "()V", "b", "IDENTICAL", 4, 6, List.of())),
            proposal.splices(), proposal.toString());
        assertTrue(Files.readString(proposal.files().get("p/Sized.java")).contains("public Sized() { this(16); }"));
    }

    @Test void inAFileThatCompilesADifferentBodyIsReplacedOnlyWhenTheOtherEngineRecompilesToTheOriginal() throws Exception {
        var original = original(Map.of("p/Calc.java", "package p; public class Calc {\n public int f(int n) { return n * 3 + 1; }\n"
            + " public int g(int x) { int y = x * 2; return y + x; }\n public int h(int x) { return x - 1; }\n}\n"));
        // Both engines compile. Engine a writes f and h with operands swapped (javac then emits other bytecode); engine b
        // gets f right under another parameter name but writes h its own different way.
        Path a = write("a", Map.of("p/Calc.java", "package p;\n\npublic class Calc {\n   public int f(int n) {\n      return 1 + n * 3;\n   }\n\n"
            + "   public int g(int x) {\n      int y = x * 2;\n      return y + x;\n   }\n\n   public int h(int x) {\n      return -1 + x;\n   }\n}\n"));
        Path b = write("b", Map.of("p/Calc.java", "package p;\npublic class Calc {\n    public int f(int value) { return value * 3 + 1; }\n"
            + "    public int g(int x) { return x * 2 + x; }\n    public int h(int x) { return x + -1; }\n}\n"));
        var measured = new CompilerVerifier().verify(a, dir.resolve("measure"), original.infos(), Duration.ofSeconds(60), List.of(), original.jar());
        assertEquals(VerificationResult.Status.COMPILED, measured.status(), measured.toString());
        assertEquals(2, measured.roundTripDifferent(), measured.toString());
        // The targets are what the mix measured, plus g, which is identical in place and must be left alone.
        var targets = new ArrayList<>(measured.roundTrip());
        targets.add(new ClassRoundTrip("p/Calc", List.of(new MethodRoundTrip.MethodResult("g", "(I)I", MethodRoundTrip.Outcome.DIFFERENT, ""))));
        var proposal = new MethodSplice().proposeForDifferent(a, dir.resolve("splice"), original.infos(), Duration.ofSeconds(60), List.of(),
            original.jar(), Map.of("a", a, "b", b), targets);
        assertEquals(List.of(new MethodSplice.Splice("p/Calc.java", "p/Calc", "f", "(I)I", "b", "IDENTICAL", 4, 6, List.of(),
            MethodSplice.ROUND_TRIP_DIFFERENT, "DIFFERENT", List.of(), List.of())), proposal.splices(), proposal.toString());
        var reasons = new TreeMap<String,String>();
        proposal.unspliced().forEach(item -> reasons.put(item.method(), item.reason()));
        assertEquals(Map.of("g(I)I", "not-different-in-place", "h(I)I", "b:round-trip-different"), reasons, proposal.toString());
        String spliced = Files.readString(proposal.files().get("p/Calc.java"));
        assertTrue(spliced.contains("public int f(int value) { return value * 3 + 1; }") && spliced.contains("return -1 + x;"), spliced);

        // In the mix, f is now identical and h is still the only DIFFERENT body.
        var verified = new CompilerVerifier().verify(write("merged", Map.of("p/Calc.java", spliced)), dir.resolve("verify"), original.infos(),
            Duration.ofSeconds(60), List.of(), original.jar());
        assertTrue(verified.complete(), verified.toString());
        assertEquals(1, verified.roundTripDifferent(), verified.toString());
        // Nothing DIFFERENT, nothing to do (and no worker started).
        var nothing = new MethodSplice().proposeForDifferent(a, dir.resolve("none"), original.infos(), Duration.ofSeconds(60), List.of(),
            original.jar(), Map.of("a", a, "b", b), verified.roundTrip().stream().filter(type -> type.different() == 0).toList());
        assertEquals(List.of(), nothing.splices());
        assertFalse(Files.exists(dir.resolve("none")));
    }

    @Test void aDifferentLambdaOrAnonymousClassMethodIsRecoveredThroughItsEnclosingMethod() throws Exception {
        var original = original(Map.of("p/Tasks.java", "package p; public class Tasks {\n"
            + " public Runnable task() { return new Runnable() { public void run() { int x = 5; System.out.println(x * 3 + 1); } }; }\n"
            + " public Runnable later() { return () -> { int x = 5; System.out.println(x * 3 + 1); }; }\n}\n"));
        // Engine a swaps the operands inside the anonymous class and the lambda: Tasks$1.run and lambda$later$0 are
        // DIFFERENT while task() and later() themselves are identical. Engine b gets both right.
        Path a = write("a", Map.of("p/Tasks.java", "package p;\n\npublic class Tasks {\n   public Runnable task() {\n      return new Runnable() {\n"
            + "         public void run() {\n            int x = 5;\n            System.out.println(1 + x * 3);\n         }\n      };\n   }\n\n"
            + "   public Runnable later() {\n      return () -> {\n         int x = 5;\n         System.out.println(1 + x * 3);\n      };\n   }\n}\n"));
        Path b = write("b", Map.of("p/Tasks.java", "package p;\npublic class Tasks {\n"
            + "    public Runnable task() { return new Runnable() { public void run() { int x = 5; System.out.println(x * 3 + 1); } }; }\n"
            + "    public Runnable later() { return () -> { int x = 5; System.out.println(x * 3 + 1); }; }\n}\n"));
        var measured = new CompilerVerifier().verify(a, dir.resolve("measure"), original.infos(), Duration.ofSeconds(60), List.of(), original.jar());
        assertEquals(2, measured.roundTripDifferent(), measured.toString());
        var proposal = new MethodSplice().proposeForDifferent(a, dir.resolve("splice"), original.infos(), Duration.ofSeconds(60), List.of(),
            original.jar(), Map.of("a", a, "b", b), measured.roundTrip());
        assertEquals(List.of(
            new MethodSplice.Splice("p/Tasks.java", "p/Tasks", "task", "()Ljava/lang/Runnable;", "b", "IDENTICAL", 4, 11, List.of(),
                MethodSplice.ROUND_TRIP_DIFFERENT, "IDENTICAL", List.of("p/Tasks$1.run()V"), List.of()),
            new MethodSplice.Splice("p/Tasks.java", "p/Tasks", "later", "()Ljava/lang/Runnable;", "b", "IDENTICAL", 13, 18, List.of(),
                MethodSplice.ROUND_TRIP_DIFFERENT, "IDENTICAL", List.of("p/Tasks.lambda$later$0()V"), List.of())), proposal.splices(), proposal.toString());
        var verified = new CompilerVerifier().verify(write("merged", Map.of("p/Tasks.java", Files.readString(proposal.files().get("p/Tasks.java")))),
            dir.resolve("verify"), original.infos(), Duration.ofSeconds(60), List.of(), original.jar());
        assertEquals(0, verified.roundTripDifferent(), verified.toString());
    }

    @Test void aCopiedBodyThatFailsOnlyByARepairableErrorIsRepairedInPlace() throws Exception {
        var original = original(Map.of("p/Names.java", "package p; import java.util.*; public class Names {\n"
            + " public int count() { List<String> l = new ArrayList<String>(); l.add(\"x\"); return l.size(); }\n"
            + " public int pick(int x) { switch (x) { case 1: return 10; default: return 20; } }\n}\n"));
        // Engine a compiles with both bodies DIFFERENT. Engine b has them right except for what decompilers print and
        // javac rejects: an argument cast up to Object for a List<String>, and an unreachable break after a return.
        Path a = write("a", Map.of("p/Names.java", "package p;\nimport java.util.*;\npublic class Names {\n"
            + "  public int count() { List<String> l = new ArrayList<String>(); l.add(\"x\"); return l.size() + 0; }\n"
            + "  public int pick(int x) { return x == 1 ? 10 : 20; }\n}\n"));
        Path b = write("b", Map.of("p/Names.java", "package p;\nimport java.util.*;\npublic class Names {\n"
            + "  public int count() { List<String> l = new ArrayList<String>(); l.add((Object)\"x\"); return l.size(); }\n"
            + "  public int pick(int x) { switch (x) { case 1: return 10; break; default: return 20; } }\n}\n"));
        var measured = new CompilerVerifier().verify(a, dir.resolve("measure"), original.infos(), Duration.ofSeconds(60), List.of(), original.jar());
        assertEquals(2, measured.roundTripDifferent(), measured.toString());
        var proposal = new MethodSplice().proposeForDifferent(a, dir.resolve("splice"), original.infos(), Duration.ofSeconds(60), List.of(),
            original.jar(), Map.of("a", a, "b", b), measured.roundTrip());
        assertEquals(List.of(
            new MethodSplice.Splice("p/Names.java", "p/Names", "count", "()I", "b", "IDENTICAL", 4, 4, List.of(),
                MethodSplice.ROUND_TRIP_DIFFERENT, "DIFFERENT", List.of(), List.of("drop-object-cast")),
            new MethodSplice.Splice("p/Names.java", "p/Names", "pick", "(I)I", "b", "IDENTICAL", 5, 5, List.of(),
                MethodSplice.ROUND_TRIP_DIFFERENT, "DIFFERENT", List.of(), List.of("drop-unreachable-jump"))), proposal.splices(), proposal.toString());
        String spliced = Files.readString(proposal.files().get("p/Names.java"));
        assertTrue(spliced.contains("l.add(\"x\"); return l.size(); }") && spliced.contains("case 1: return 10;  default: return 20;"), spliced);
        var verified = new CompilerVerifier().verify(write("merged", Map.of("p/Names.java", spliced)), dir.resolve("verify"), original.infos(),
            Duration.ofSeconds(60), List.of(), original.jar());
        assertTrue(verified.complete(), verified.toString());
        assertEquals(0, verified.roundTripDifferent(), verified.toString());
    }

    /** method name to "engine outcome before rules" for each splice, for tests that need not pin lines. */
    private static Map<String,String> summary(MethodSplice.Proposal proposal) {
        var result = new TreeMap<String,String>();
        for (var splice : proposal.splices())
            result.put(splice.method(), splice.engine() + " " + splice.roundTrip() + " " + splice.before() + " " + String.join(",", splice.bodyRepairs()));
        return result;
    }

    @Test void theMethodsOwnBodyLosesTheCastsJavacNineAddsAndItsRedundantCasts() throws Exception {
        // Like CFR 0.152, built by a javac older than 9: a generic value passed to a parameter declared as a type variable
        // was cast to the parameter's erasure only. javac 21 casts it to the instantiated type (JDK-8058199); the original
        // here is written in the forms javac 21 compiles to the older bytecode.
        var original = original(Map.of("p/Gen.java", "package p; import java.util.*; public class Gen {\n"
            + " static class Pair<A, B> { static <A, B> Pair<A, B> make(A a, B b) { return new Pair<A, B>(); } }\n"
            + " static class Box<T> { Box(T t) {} }\n"
            + " public void add(Set<String> s, List<String> l) { ((Set) s).add(l.get(0)); }\n"
            + " public void put(Map<String, Integer> m, Map.Entry<String, Integer> e) { ((Map) m).put(e.getKey(), e.getValue()); }\n"
            + " public Pair<Integer, String> pair(List<String> l) { return (Pair) Pair.make(1, (Object) l.get(0)); }\n"
            + " public Box<String> box(List<String> l) { return new Box(l.get(0)); }\n"
            + " public Object first(List<String> l) { Object o = l.get(0); return o; }\n"
            + " public int size(Set<String> s, List<String> l) { s.add(l.get(0)); return s.size(); }\n}\n"));
        // What a decompiler prints: plain generic calls, a redundant cast, and in size() a body DIFFERENT for another
        // reason whose cast the original has too (it must stay).
        Path a = write("a", Map.of("p/Gen.java", "package p;\nimport java.util.*;\npublic class Gen {\n"
            + "   static class Pair<A, B> {\n      static <A, B> Pair<A, B> make(A a, B b) {\n         return new Pair<A, B>();\n      }\n   }\n"
            + "   static class Box<T> {\n      Box(T t) {\n      }\n   }\n"
            + "   public void add(Set<String> s, List<String> l) {\n      s.add(l.get(0));\n   }\n"
            + "   public void put(Map<String, Integer> m, Map.Entry<String, Integer> e) {\n      m.put(e.getKey(), e.getValue());\n   }\n"
            + "   public Pair<Integer, String> pair(List<String> l) {\n      return Pair.make(1, l.get(0));\n   }\n"
            + "   public Box<String> box(List<String> l) {\n      return new Box<>(l.get(0));\n   }\n"
            + "   public Object first(List<String> l) {\n      Object o = (String)l.get(0);\n      return o;\n   }\n"
            + "   public int size(Set<String> s, List<String> l) {\n      s.add(l.get(0));\n      return s.size() + 0;\n   }\n}\n"));
        var measured = new CompilerVerifier().verify(a, dir.resolve("measure"), original.infos(), Duration.ofSeconds(60), List.of(), original.jar());
        assertEquals(VerificationResult.Status.COMPILED, measured.status(), measured.toString());
        assertEquals(6, measured.roundTripDifferent(), measured.toString());
        var proposal = new MethodSplice().proposeForDifferent(a, dir.resolve("splice"), original.infos(), Duration.ofSeconds(60), List.of(),
            original.jar(), Map.of("a", a), measured.roundTrip());
        // Engine a's file is the mix itself, so its own bodies are named after it.
        assertEquals(Map.of("add", "a IDENTICAL DIFFERENT raw-receiver", "put", "a IDENTICAL DIFFERENT raw-receiver",
            "pair", "a IDENTICAL DIFFERENT erased-argument", "box", "a IDENTICAL DIFFERENT raw-creation",
            "first", "a IDENTICAL DIFFERENT drop-redundant-cast"), summary(proposal), proposal.toString());
        assertEquals(List.of("size(Ljava/util/Set;Ljava/util/List;)I"), proposal.unspliced().stream().map(MethodSplice.Unspliced::method).toList(),
            proposal.toString());
        String spliced = Files.readString(proposal.files().get("p/Gen.java"));
        for (var expected : List.of("((java.util.Set) s).add(l.get(0));", "((java.util.Map) m).put(e.getKey(), e.getValue());",
            "return ((p.Gen.Pair) Pair.make(1, ((java.lang.Object) l.get(0))));", "return new Box(l.get(0));", "Object o = l.get(0);",
            "s.add(l.get(0));\n      return s.size() + 0;"))
            assertTrue(spliced.contains(expected), expected + " in " + spliced);
        var verified = new CompilerVerifier().verify(write("merged", Map.of("p/Gen.java", spliced)), dir.resolve("verify"), original.infos(),
            Duration.ofSeconds(60), List.of(), original.jar());
        assertTrue(verified.complete(), verified.toString());
        assertEquals(1, verified.roundTripDifferent(), verified.toString());
    }

    @Test void anotherEnginesBodyRefusedByTheRoundTripIsTriedAgainWithoutTheCastsJavacNineAdds() throws Exception {
        var original = original(Map.of("p/Sets.java", "package p; import java.util.*; public class Sets {\n"
            + " public void add(Set<String> s, List<String> l) { ((Set) s).add(l.get(0)); }\n}\n"));
        // Engine a (the mix) reads another element; engine b is right but for javac 21's cast, and also needs a repair.
        Path a = write("a", Map.of("p/Sets.java", "package p;\nimport java.util.*;\npublic class Sets {\n"
            + "  public void add(Set<String> s, List<String> l) { ((Set) s).add(l.get(1)); }\n}\n"));
        Path b = write("b", Map.of("p/Sets.java", "package p;\nimport java.util.*;\npublic class Sets {\n"
            + "  public void add(Set<String> s, List<String> l) { s.add((Object)l.get(0)); }\n}\n"));
        var measured = new CompilerVerifier().verify(a, dir.resolve("measure"), original.infos(), Duration.ofSeconds(60), List.of(), original.jar());
        assertEquals(1, measured.roundTripDifferent(), measured.toString());
        var proposal = new MethodSplice().proposeForDifferent(a, dir.resolve("splice"), original.infos(), Duration.ofSeconds(60), List.of(),
            original.jar(), Map.of("a", a, "b", b), measured.roundTrip());
        assertEquals(Map.of("add", "b IDENTICAL DIFFERENT drop-object-cast,raw-receiver"), summary(proposal), proposal.toString());
        assertTrue(Files.readString(proposal.files().get("p/Sets.java")).contains("{ ((java.util.Set) s).add(l.get(0)); }"));
    }

    @Test void refusesBodiesWhoseSyntheticsAnonymousClassesOrBytecodeDoNotMatch() throws Exception {
        var original = original(Map.of(
            "p/Mixed.java", "package p; import java.util.Date; public class Mixed {\n"
                + " public boolean m1() { Date d = null; return d == null; }\n"
                + " public int m2(int x) { return x + 1; }\n"
                + " public Runnable m3() { return new Runnable() { public void run() {} }; }\n"
                + " public int m4(int x) { return x * 2; }\n}\n",
            "p/Field.java", "package p; public class Field { int f = 1; public int k() { return f; } }"));
        Path a = write("a", Map.of(
            "p/Mixed.java", "package p; import java.sql.Date; public class Mixed {\n"
                + " public boolean m1() { Date d = null; return d == nul; }\n"
                + " public int m2(int x) { return x + one; }\n"
                + " public Runnable m3() { return new Runnable() { public void run() { missing(); } }; }\n"
                + " public int m4(int x) { return x * two; }\n}\n",
            "p/Field.java", "package p; public class Field { int f = \"1\"; public int k() { return f; } }"));
        Path b = write("b", Map.of(
            "p/Mixed.java", "package p; import java.util.Date; public class Mixed {\n"
                + " public boolean m1() { Date d = null; return d == null; }\n"
                + " public int m2(int x) { return x + 2; }\n"
                + " public Runnable m3() { return () -> {}; }\n"
                + " public int m4(int x) { return access$000(x); }\n"
                + " static int access$000(int x) { return x * 2; }\n}\n",
            "p/Field.java", "package p; public class Field { int f = 1; public int k() { return this.f; } }"));
        var proposal = new MethodSplice().propose(a, dir.resolve("splice"), original.infos(), Duration.ofSeconds(60), List.of(), original.jar(),
            Map.of("a", a, "b", b));
        // Same bytecode either way, but `Date` would mean java.sql.Date in this file: the import is not compatible, so the
        // copied body names java.util.Date canonically, and every name then resolves as in engine b's own file.
        assertEquals(List.of(new MethodSplice.Splice("p/Mixed.java", "p/Mixed", "m1", "()Z", "b", "IDENTICAL", 2, 2, List.of("java.util.Date"))),
            proposal.splices(), proposal.toString());
        String spliced = Files.readString(proposal.files().get("p/Mixed.java"));
        assertTrue(spliced.contains("import java.sql.Date;") && spliced.contains("{ java.util.Date d = null; return d == null; }"), spliced);
        var reasons = new TreeMap<String,String>();
        proposal.unspliced().forEach(item -> reasons.put(item.file() + " " + item.method(), item.reason()));
        assertEquals(4, reasons.size(), reasons.toString());
        assertEquals("b:round-trip-different", reasons.get("p/Mixed.java m2(I)I"), reasons.toString());
        assertEquals("b:anonymous-or-local-classes-differ", reasons.get("p/Mixed.java m3()Ljava/lang/Runnable;"), reasons.toString());
        assertEquals("b:synthetic-name:access$000", reasons.get("p/Mixed.java m4(I)I"), reasons.toString());
        // A field initializer is not a method body: the file is left whole.
        assertEquals("errors-outside-method-bodies", reasons.get("p/Field.java -"), reasons.toString());
    }
}
