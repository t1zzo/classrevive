package dev.classrevive.verification;

import dev.classrevive.model.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Minimal decompiler-shaped generic errors, one per repair rule. Sources are only compiled, never run. */
class SourceRepairTest {
    @TempDir Path dir;
    static final List<ClassInfo> ORIGINALS = List.of(new ClassInfo("p/Box", "java/lang/Object", 52, 0, 0x401, List.of(), List.of(),
        List.of(new MemberInfo("<init>", "()V", 1))));
    static final Map<String,String> COMMON = Map.of(
        "p/Box.java", "package p; public abstract class Box<T> { public abstract T get(); }",
        "p/IntBox.java", "package p; public final class IntBox extends Box<Integer> { public Integer get() { return 1; } }",
        "p/Factory.java", "package p; public interface Factory { <T> Box<T> create(Class<T> type); }",
        "p/Holder.java", "package p; public final class Holder<E> { public Holder(Box<? extends java.util.List<E>> box) {} }");

    Path sources(String name, Map<String,String> files) throws IOException {
        Path root = Files.createDirectory(dir.resolve(name));
        var all = new TreeMap<>(COMMON); all.putAll(files);
        for (var file : all.entrySet()) {
            Path path = root.resolve(file.getKey());
            Files.createDirectories(path.getParent());
            Files.writeString(path, file.getValue());
        }
        return root;
    }
    /** The candidate with the proposal's repaired files on top, as selection would assemble it. */
    Path apply(Path sources, SourceRepair.Proposal proposal) throws IOException {
        Path merged = dir.resolve(sources.getFileName() + "-merged");
        try (var paths = Files.walk(sources)) {
            for (var path : paths.filter(Files::isRegularFile).toList()) {
                String name = sources.relativize(path).toString().replace('\\', '/');
                Path target = merged.resolve(name);
                Files.createDirectories(target.getParent());
                Files.copy(proposal.files().getOrDefault(name, path), target);
            }
        }
        return merged;
    }
    VerificationResult compile(Path sources, String name) throws IOException {
        return new CompilerVerifier().verify(sources, dir.resolve(name), ORIGINALS, Duration.ofSeconds(60));
    }

    @Test void guardsAnInitializerThatCannotCompleteNormallyWithoutChangingItsBytecode() throws Exception {
        // All four pinned engines print javac's `static { if (true) throw ...; }` as `static { throw ...; }`.
        Path broken = sources("initializer", Map.of("p/Throwing.java",
            "package p; public class Throwing {\n  public int value() { return 42; }\n  static {\n    throw new IllegalStateException(\"never\");\n  }\n}\n"));
        var proposal = new SourceRepair().propose(broken, dir.resolve("repair"), ORIGINALS, Duration.ofSeconds(60), List.of());
        assertEquals(List.of(), proposal.unrepaired());
        assertEquals(List.of("guard-initializer"), proposal.edits().stream().map(SourceRepair.Edit::rule).toList());
        assertEquals("compiler.err.initializer.must.be.able.to.complete.normally", proposal.edits().getFirst().code());
        assertTrue(Files.readString(proposal.files().get("p/Throwing.java")).contains("{ if (true) {"));
        Path merged = apply(broken, proposal);
        assertEquals(VerificationResult.Status.COMPILED, compile(merged, "verify").status());

        // The repaired class compiles to the same initializer and method bodies as javac's own source form.
        Path reference = Files.createDirectories(dir.resolve("reference/p"));
        Files.writeString(reference.resolve("Throwing.java"),
            "package p; public class Throwing { public int value() { return 42; } static { if (true) throw new IllegalStateException(\"never\"); } }");
        Path referenceClasses = dir.resolve("reference-classes"), repairedClasses = dir.resolve("repaired-classes");
        var javac = javax.tools.ToolProvider.getSystemJavaCompiler();
        assertEquals(0, javac.run(null, null, null, "--release", "8", "-Xlint:-options", "-proc:none", "-d", referenceClasses.toString(),
            reference.resolve("Throwing.java").toString()));
        assertEquals(0, javac.run(null, null, null, "--release", "8", "-Xlint:-options", "-proc:none", "-d", repairedClasses.toString(),
            merged.resolve("p/Throwing.java").toString()));
        var outcomes = MethodRoundTrip.compareClass(Files.readAllBytes(referenceClasses.resolve("p/Throwing.class")),
            Files.readAllBytes(repairedClasses.resolve("p/Throwing.class")));
        assertEquals(3, outcomes.size(), outcomes.toString());
        assertTrue(outcomes.stream().allMatch(result -> result.outcome() == MethodRoundTrip.Outcome.IDENTICAL), outcomes.toString());
    }

    @Test void restoresTheIntSpellingOfAConstantPrintedAsABooleanLiteral() throws Exception {
        // CFR printed ASM's `readCount != 1` (iconst_1; if_icmpne) as `readCount != true`.
        String shaped = "package p; public class Counter {\n  public static int f(int n) {\n    int count = 0;\n"
            + "    for (int i = 0; i < n; i++) ++count;\n    if (count != true) return -1;\n    return count;\n  }\n"
            + "  static boolean g(String s) { return s == true; }\n}\n";
        Path broken = sources("boolean-literal", Map.of("p/Counter.java", shaped));
        var proposal = new SourceRepair().propose(broken, dir.resolve("repair"), ORIGINALS, Duration.ofSeconds(60), List.of());
        assertEquals(List.of("boolean-literal-as-int"), proposal.edits().stream().map(SourceRepair.Edit::rule).toList());
        assertEquals("true", proposal.edits().getFirst().before()); assertEquals("1", proposal.edits().getFirst().after());
        // A String compared with a boolean (javac: operator.cant.be.applied.1) is reported and never edited.
        assertTrue(proposal.unrepaired().stream().anyMatch(left -> left.line() == 8), proposal.unrepaired().toString());
        assertTrue(proposal.edits().stream().noneMatch(edit -> edit.line() == 8));

        Path fixed = sources("boolean-literal-fixed", Map.of("p/Counter.java", shaped.replace("  static boolean g(String s) { return s == true; }\n", "")));
        var single = new SourceRepair().propose(fixed, dir.resolve("repair-fixed"), ORIGINALS, Duration.ofSeconds(60), List.of());
        Path merged = apply(fixed, single);
        Path reference = Files.createDirectories(dir.resolve("reference/p"));
        Files.writeString(reference.resolve("Counter.java"), shaped.replace("count != true", "count != 1")
            .replace("  static boolean g(String s) { return s == true; }\n", ""));
        Path referenceClasses = dir.resolve("reference-classes"), repairedClasses = dir.resolve("repaired-classes");
        var javac = javax.tools.ToolProvider.getSystemJavaCompiler();
        assertEquals(0, javac.run(null, null, null, "--release", "8", "-Xlint:-options", "-proc:none", "-d", referenceClasses.toString(),
            reference.resolve("Counter.java").toString()));
        assertEquals(0, javac.run(null, null, null, "--release", "8", "-Xlint:-options", "-proc:none", "-d", repairedClasses.toString(),
            merged.resolve("p/Counter.java").toString()));
        var outcomes = MethodRoundTrip.compareClass(Files.readAllBytes(referenceClasses.resolve("p/Counter.class")),
            Files.readAllBytes(repairedClasses.resolve("p/Counter.class")));
        assertTrue(outcomes.stream().allMatch(result -> result.outcome() == MethodRoundTrip.Outcome.IDENTICAL), outcomes.toString());
    }

    @Test void dropsOnlyUnreachableJumpStatementsAndKeepsTheBytecode() throws Exception {
        // Procyon prints a `break;` after an inner switch whose every case jumps out or throws (ASM's Frame).
        String body = "  static int f(int x, int y) {\n    outer: switch (x) {\n      case 1: {\n"
            + "        switch (y) { case 0: break outer; default: throw new AssertionError(); }\n        BREAK\n      }\n"
            + "      case 2: return 2;\n    }\n    return 0;\n  }\n";
        String shaped = "package p; public class Jumps {\n" + body.replace("BREAK", "break;")
            + "  static int g(int x) { return x;\n  }\n}\n";
        Path broken = sources("unreachable", Map.of("p/Jumps.java", shaped));
        var proposal = new SourceRepair().propose(broken, dir.resolve("repair"), ORIGINALS, Duration.ofSeconds(60), List.of());
        assertEquals(List.of("drop-unreachable-jump"), proposal.edits().stream().map(SourceRepair.Edit::rule).toList());
        assertEquals("break;", proposal.edits().getFirst().before()); assertEquals("", proposal.edits().getFirst().after());
        Path merged = apply(broken, proposal);
        assertEquals(VerificationResult.Status.COMPILED, compile(merged, "verify").status());

        Path reference = Files.createDirectories(dir.resolve("reference/p"));
        Files.writeString(reference.resolve("Jumps.java"), "package p; public class Jumps {\n" + body.replace("BREAK", "")
            + "  static int g(int x) { return x;\n  }\n}\n");
        Path referenceClasses = dir.resolve("reference-classes"), repairedClasses = dir.resolve("repaired-classes");
        var javac = javax.tools.ToolProvider.getSystemJavaCompiler();
        assertEquals(0, javac.run(null, null, null, "--release", "8", "-Xlint:-options", "-proc:none", "-d", referenceClasses.toString(),
            reference.resolve("Jumps.java").toString()));
        assertEquals(0, javac.run(null, null, null, "--release", "8", "-Xlint:-options", "-proc:none", "-d", repairedClasses.toString(),
            merged.resolve("p/Jumps.java").toString()));
        var outcomes = MethodRoundTrip.compareClass(Files.readAllBytes(referenceClasses.resolve("p/Jumps.class")),
            Files.readAllBytes(repairedClasses.resolve("p/Jumps.class")));
        assertTrue(outcomes.stream().allMatch(result -> result.outcome() == MethodRoundTrip.Outcome.IDENTICAL), outcomes.toString());

        // An unreachable statement with an effect is never removed.
        Path effect = sources("unreachable-effect", Map.of("p/Effect.java",
            "package p; public class Effect {\n  static int h(int x) {\n    return x;\n    x++;\n  }\n}\n"));
        var left = new SourceRepair().propose(effect, dir.resolve("repair-effect"), ORIGINALS, Duration.ofSeconds(60), List.of());
        assertTrue(left.edits().isEmpty(), left.edits().toString());
        assertTrue(left.unrepaired().stream().anyMatch(item -> item.reason().equals("not-a-jump-statement")), left.unrepaired().toString());
    }

    @Test void dropsAnUpcastToObjectThatBlocksAGenericCallButNeverABoxingCast() throws Exception {
        // CFR prints `entries.add((Object)new Entry(e))` for a List<Entry>; the original call had no cast.
        String shaped = "package p; import java.util.*; public class Entries {\n  final List<String> names = new ArrayList<>();\n"
            + "  void add(String name) { this.names.add((Object)name.trim()); }\n}\n";
        Path broken = sources("object-cast", Map.of("p/Entries.java", shaped));
        var proposal = new SourceRepair().propose(broken, dir.resolve("repair"), ORIGINALS, Duration.ofSeconds(60), List.of());
        assertEquals(List.of("drop-object-cast"), proposal.edits().stream().map(SourceRepair.Edit::rule).toList());
        assertEquals("(Object)name.trim()", proposal.edits().getFirst().before()); assertEquals("name.trim()", proposal.edits().getFirst().after());
        Path merged = apply(broken, proposal);
        Path reference = Files.createDirectories(dir.resolve("reference/p"));
        Files.writeString(reference.resolve("Entries.java"), shaped.replace("(Object)name.trim()", "name.trim()"));
        Path referenceClasses = dir.resolve("reference-classes"), repairedClasses = dir.resolve("repaired-classes");
        var javac = javax.tools.ToolProvider.getSystemJavaCompiler();
        assertEquals(0, javac.run(null, null, null, "--release", "8", "-Xlint:-options", "-proc:none", "-d", referenceClasses.toString(),
            reference.resolve("Entries.java").toString()));
        assertEquals(0, javac.run(null, null, null, "--release", "8", "-Xlint:-options", "-proc:none", "-d", repairedClasses.toString(),
            merged.resolve("p/Entries.java").toString()));
        var outcomes = MethodRoundTrip.compareClass(Files.readAllBytes(referenceClasses.resolve("p/Entries.class")),
            Files.readAllBytes(repairedClasses.resolve("p/Entries.class")));
        assertTrue(outcomes.stream().allMatch(result -> result.outcome() == MethodRoundTrip.Outcome.IDENTICAL), outcomes.toString());

        // (Object)5 boxes the int, so dropping the cast would call a different method: never edited by this rule.
        Path boxing = sources("object-cast-boxing", Map.of("p/Counts.java", "package p; import java.util.*; public class Counts {\n"
            + "  final List<String> names = new ArrayList<>();\n  void add() { this.names.add((Object)5); }\n}\n"));
        var refused = new SourceRepair().propose(boxing, dir.resolve("repair-boxing"), ORIGINALS, Duration.ofSeconds(60), List.of());
        assertTrue(refused.edits().stream().noneMatch(edit -> edit.rule().equals("drop-object-cast")), refused.edits().toString());
    }

    @Test void castsAnIteratedMethodReferenceToIterableWithoutChangingItsBytecode() throws Exception {
        // Ant 1.10.17's PathConvert and Pvcs: `for (String s : (Iterable<String>) stream::iterator)` printed without the cast.
        Path broken = sources("iterable", Map.of("p/Lines.java", "package p; import java.util.stream.Stream;\npublic class Lines {\n"
            + "  public static int count(Stream<String> lines) {\n    int n = 0;\n    for (String s : lines::iterator) {\n      n += s.length();\n    }\n"
            + "    for (String t : () -> lines.iterator()) {\n      n++;\n    }\n    return n;\n  }\n}\n"));
        var proposal = new SourceRepair().propose(broken, dir.resolve("repair"), ORIGINALS, Duration.ofSeconds(60), List.of());
        assertEquals(List.of("iterable-cast", "iterable-cast"), proposal.edits().stream().map(SourceRepair.Edit::rule).toList(), proposal.toString());
        Path merged = apply(broken, proposal);
        assertTrue(Files.readString(merged.resolve("p/Lines.java")).contains("(java.lang.Iterable<String>) (lines::iterator)"));
        assertEquals(VerificationResult.Status.COMPILED, compile(merged, "verify").status());
        Path reference = Files.createDirectories(dir.resolve("reference/p"));
        Files.writeString(reference.resolve("Lines.java"), "package p; import java.util.stream.Stream; public class Lines {"
            + " public static int count(Stream<String> lines) { int n = 0; for (String s : (Iterable<String>) lines::iterator) { n += s.length(); }"
            + " for (String t : (Iterable<String>) () -> lines.iterator()) { n++; } return n; } }");
        Path referenceClasses = dir.resolve("reference-classes"), repairedClasses = dir.resolve("repaired-classes");
        var javac = javax.tools.ToolProvider.getSystemJavaCompiler();
        assertEquals(0, javac.run(null, null, null, "--release", "8", "-Xlint:-options", "-proc:none", "-d", referenceClasses.toString(),
            reference.resolve("Lines.java").toString()));
        assertEquals(0, javac.run(null, null, null, "--release", "8", "-Xlint:-options", "-proc:none", "-d", repairedClasses.toString(),
            merged.resolve("p/Lines.java").toString()));
        var outcomes = MethodRoundTrip.compareClass(Files.readAllBytes(referenceClasses.resolve("p/Lines.class")),
            Files.readAllBytes(repairedClasses.resolve("p/Lines.class")));
        assertTrue(outcomes.stream().allMatch(result -> result.outcome() == MethodRoundTrip.Outcome.IDENTICAL), outcomes.toString());
    }

    @Test void castsAReceiverMethodReferenceToTheInterfaceOfItsOriginalInvokedynamic() throws Exception {
        // Ant 1.10.17's PatternSet and EchoProperties: a method reference used as a receiver, printed without its cast.
        Path reference = Files.createDirectories(dir.resolve("reference/p"));
        Files.writeString(reference.resolve("Refs.java"), "package p; import java.util.*; import java.util.function.*; public class Refs {"
            + " public static long nonEmpty(List<String> lines) { return lines.stream().filter(((Predicate<String>) String::isEmpty).negate()).count(); }"
            + " public static Comparator<Map.Entry<Object, Object>> byKey() {"
            + " return Comparator.comparing(((Function<Map.Entry<Object, Object>, Object>) Map.Entry::getKey).andThen(Object::toString)); } }");
        Path referenceClasses = dir.resolve("reference-classes"), repairedClasses = dir.resolve("repaired-classes");
        var javac = javax.tools.ToolProvider.getSystemJavaCompiler();
        assertEquals(0, javac.run(null, null, null, "--release", "8", "-Xlint:-options", "-proc:none", "-d", referenceClasses.toString(),
            reference.resolve("Refs.java").toString()));
        Path jar = dir.resolve("classes.jar");
        try (var zip = new java.util.zip.ZipOutputStream(Files.newOutputStream(jar))) {
            zip.putNextEntry(new java.util.zip.ZipEntry("p/Refs.class")); zip.write(Files.readAllBytes(referenceClasses.resolve("p/Refs.class"))); zip.closeEntry();
        }
        Path broken = sources("receiver", Map.of("p/Refs.java", "package p;\nimport java.util.*;\nimport java.util.function.*;\npublic class Refs {\n"
            + "  public static long nonEmpty(List<String> lines) {\n    return lines.stream().filter((String::isEmpty).negate()).count();\n  }\n"
            + "  public static Comparator<Map.Entry<Object, Object>> byKey() {\n"
            + "    return Comparator.comparing((Map.Entry::getKey).andThen(Object::toString));\n  }\n}\n"));
        // Without the original classes the interface is unknown, so nothing is proposed.
        var blind = new SourceRepair().propose(broken, dir.resolve("blind"), ORIGINALS, Duration.ofSeconds(60), List.of());
        assertEquals(List.of(), blind.edits(), blind.toString());
        var proposal = new SourceRepair().propose(broken, dir.resolve("repair"), ORIGINALS, Duration.ofSeconds(60), List.of(), jar);
        assertEquals(List.of("functional-cast", "functional-cast"), proposal.edits().stream().map(SourceRepair.Edit::rule).toList(), proposal.toString());
        Path merged = apply(broken, proposal);
        String repaired = Files.readString(merged.resolve("p/Refs.java"));
        assertTrue(repaired.contains("((java.util.function.Predicate<java.lang.String>) (String::isEmpty)).negate()"), repaired);
        assertTrue(repaired.contains("((java.util.function.Function<java.util.Map.Entry, java.lang.Object>) (Map.Entry::getKey)).andThen"), repaired);
        assertEquals(0, javac.run(null, null, null, "--release", "8", "-Xlint:-options", "-proc:none", "-d", repairedClasses.toString(),
            merged.resolve("p/Refs.java").toString()));
        var outcomes = MethodRoundTrip.compareClass(Files.readAllBytes(referenceClasses.resolve("p/Refs.class")),
            Files.readAllBytes(repairedClasses.resolve("p/Refs.class")));
        assertTrue(outcomes.stream().allMatch(result -> result.outcome() == MethodRoundTrip.Outcome.IDENTICAL), outcomes.toString());
    }

    @Test void givesAGenericCallComparedWithEqualsTheTypeWitnessOfTheOtherOperand() throws Exception {
        // Guava 17's TreeRangeSet: `lowerBound == Cut.<C>belowAll()` printed without the witness.
        String cut = "package p; public abstract class Cut<C extends Comparable> { static <C extends Comparable> Cut<C> belowAll() { return null; } }";
        String written = "package p; public class Ranges<C extends Comparable<?>> { Cut<C> lower;"
            + " boolean unbounded() { return lower == Cut.<C>belowAll(); } boolean bounded() { return Cut.<C>belowAll() != lower; } }";
        Path reference = Files.createDirectories(dir.resolve("reference/p"));
        Files.writeString(reference.resolve("Cut.java"), cut); Files.writeString(reference.resolve("Ranges.java"), written);
        Path broken = sources("witness", Map.of("p/Cut.java", cut, "p/Ranges.java", written.replace("Cut.<C>belowAll()", "Cut.belowAll()")));
        var proposal = new SourceRepair().propose(broken, dir.resolve("repair"), ORIGINALS, Duration.ofSeconds(60), List.of());
        assertEquals(List.of("type-witness", "type-witness"), proposal.edits().stream().map(SourceRepair.Edit::rule).toList(), proposal.toString());
        Path merged = apply(broken, proposal);
        assertEquals(written, Files.readString(merged.resolve("p/Ranges.java")));
        Path referenceClasses = dir.resolve("reference-classes"), repairedClasses = dir.resolve("repaired-classes");
        var javac = javax.tools.ToolProvider.getSystemJavaCompiler();
        for (var pair : List.of(List.of(reference, referenceClasses), List.of(merged.resolve("p"), repairedClasses)))
            assertEquals(0, javac.run(null, null, null, "--release", "8", "-Xlint:-options", "-proc:none", "-d", pair.get(1).toString(),
                pair.get(0).resolve("Cut.java").toString(), pair.get(0).resolve("Ranges.java").toString()));
        var outcomes = MethodRoundTrip.compareClass(Files.readAllBytes(referenceClasses.resolve("p/Ranges.class")),
            Files.readAllBytes(repairedClasses.resolve("p/Ranges.class")));
        assertTrue(outcomes.stream().allMatch(result -> result.outcome() == MethodRoundTrip.Outcome.IDENTICAL), outcomes.toString());
    }

    @Test void castsALambdaAndAnInvalidMethodReferenceToTheInterfacesOfTheirOriginalInvokedynamic() throws Exception {
        // Ant 1.10.17's Main (a lambda on a Map declared raw) and ProjectHelperTask (`Object::getClass` then a bound reference).
        String written = "package p; import java.util.*; import java.util.function.*; public class Props {"
            + " static void set(String key, String value) {} static void register(Class<? extends Number> type) {}"
            + " void apply(Map<String, Object> defined) { Map<String, Object> raw = new HashMap<>(defined);"
            + " raw.forEach((arg, value) -> set(arg, String.valueOf(value))); }"
            + " void each(List<Integer> numbers) { numbers.stream().map(Integer::getClass).forEach(Props::register); } }";
        Path reference = Files.createDirectories(dir.resolve("reference/p"));
        Files.writeString(reference.resolve("Props.java"), written);
        Path referenceClasses = dir.resolve("reference-classes"), repairedClasses = dir.resolve("repaired-classes");
        var javac = javax.tools.ToolProvider.getSystemJavaCompiler();
        assertEquals(0, javac.run(null, null, null, "--release", "8", "-Xlint:-options", "-proc:none", "-d", referenceClasses.toString(),
            reference.resolve("Props.java").toString()));
        Path jar = dir.resolve("classes.jar");
        try (var zip = new java.util.zip.ZipOutputStream(Files.newOutputStream(jar))) {
            zip.putNextEntry(new java.util.zip.ZipEntry("p/Props.class")); zip.write(Files.readAllBytes(referenceClasses.resolve("p/Props.class"))); zip.closeEntry();
        }
        // As decompilers print them: the local declared raw, and getClass referenced through Object.
        Path broken = sources("lambda", Map.of("p/Props.java", written.replace("Map<String, Object> raw", "Map raw")
            .replace("Integer::getClass", "Object::getClass")));
        var proposal = new SourceRepair().propose(broken, dir.resolve("repair"), ORIGINALS, Duration.ofSeconds(60), List.of(), jar);
        assertEquals(List.of("lambda-cast", "functional-cast"), proposal.edits().stream().map(SourceRepair.Edit::rule).toList(), proposal.toString());
        Path merged = apply(broken, proposal);
        String repaired = Files.readString(merged.resolve("p/Props.java"));
        assertTrue(repaired.contains("raw.forEach(((java.util.function.BiConsumer<java.lang.String, java.lang.Object>) ((arg, value) -> set(arg, String.valueOf(value)))))"), repaired);
        assertTrue(repaired.contains(".forEach(((java.util.function.Consumer<java.lang.Class>) (Props::register)))"), repaired);
        assertEquals(0, javac.run(null, null, null, "--release", "8", "-Xlint:-options", "-proc:none", "-d", repairedClasses.toString(),
            merged.resolve("p/Props.java").toString()));
        var outcomes = MethodRoundTrip.compareClass(Files.readAllBytes(referenceClasses.resolve("p/Props.class")),
            Files.readAllBytes(repairedClasses.resolve("p/Props.class")));
        assertTrue(outcomes.stream().allMatch(result -> result.outcome() == MethodRoundTrip.Outcome.IDENTICAL), outcomes.toString());
    }

    @Test void usesTheRenamedParameterWhereTheDecompilerReferencedTheCapturedVariable() throws Exception {
        // Vineflower on Guava 17's Futures.lazyTransform: the parameter `input` renamed `inputx`, then `input` (the
        // enclosing method's Future) passed where the parameter was.
        String written = "package p; import java.util.*; public class Lazy { public interface F<A, B> { B apply(A a); }"
            + " public static <I, O> Object make(final List<I> input, final F<? super I, ? extends O> function) {"
            + " return new Object() { O transform(I input) { return function.apply(input); } }; } }";
        Path reference = Files.createDirectories(dir.resolve("reference/p"));
        Files.writeString(reference.resolve("Lazy.java"), written);
        Path broken = sources("shadow", Map.of("p/Lazy.java", written.replace("transform(I input)", "transform(I inputx)")));
        var proposal = new SourceRepair().propose(broken, dir.resolve("repair"), ORIGINALS, Duration.ofSeconds(60), List.of());
        assertEquals(List.of("shadowed-parameter"), proposal.edits().stream().map(SourceRepair.Edit::rule).toList(), proposal.toString());
        Path merged = apply(broken, proposal);
        assertTrue(Files.readString(merged.resolve("p/Lazy.java")).contains("O transform(I inputx) { return function.apply(inputx); }"));
        Path referenceClasses = dir.resolve("reference-classes"), repairedClasses = dir.resolve("repaired-classes");
        var javac = javax.tools.ToolProvider.getSystemJavaCompiler();
        assertEquals(0, javac.run(null, null, null, "--release", "8", "-Xlint:-options", "-proc:none", "-d", referenceClasses.toString(),
            reference.resolve("Lazy.java").toString()));
        assertEquals(0, javac.run(null, null, null, "--release", "8", "-Xlint:-options", "-proc:none", "-d", repairedClasses.toString(),
            merged.resolve("p/Lazy.java").toString()));
        var outcomes = MethodRoundTrip.compareClass(Files.readAllBytes(referenceClasses.resolve("p/Lazy$1.class")),
            Files.readAllBytes(repairedClasses.resolve("p/Lazy$1.class")));
        assertTrue(!outcomes.isEmpty() && outcomes.stream().allMatch(result -> result.outcome() == MethodRoundTrip.Outcome.IDENTICAL), outcomes.toString());
    }

    @Test void givesAGenericReceiverTheWitnessOfTheMethodReferencePassedToIt() throws Exception {
        // Ant 1.10.17's Depend: `Predicate.<File>isEqual(known).or(File::exists)` printed without the witness.
        String written = "package p; import java.io.File; import java.util.*; import java.util.function.*; public class Find {"
            + " static Optional<File> find(List<File> files, File known) {"
            + " return files.stream().filter(Predicate.<File>isEqual(known).or(File::exists)).findFirst(); } }";
        Path reference = Files.createDirectories(dir.resolve("reference/p"));
        Files.writeString(reference.resolve("Find.java"), written);
        Path referenceClasses = dir.resolve("reference-classes"), repairedClasses = dir.resolve("repaired-classes");
        var javac = javax.tools.ToolProvider.getSystemJavaCompiler();
        assertEquals(0, javac.run(null, null, null, "--release", "8", "-Xlint:-options", "-proc:none", "-d", referenceClasses.toString(),
            reference.resolve("Find.java").toString()));
        Path jar = dir.resolve("classes.jar");
        try (var zip = new java.util.zip.ZipOutputStream(Files.newOutputStream(jar))) {
            zip.putNextEntry(new java.util.zip.ZipEntry("p/Find.class")); zip.write(Files.readAllBytes(referenceClasses.resolve("p/Find.class"))); zip.closeEntry();
        }
        Path broken = sources("receiver-witness", Map.of("p/Find.java", written.replace("Predicate.<File>isEqual", "Predicate.isEqual")));
        var proposal = new SourceRepair().propose(broken, dir.resolve("repair"), ORIGINALS, Duration.ofSeconds(60), List.of(), jar);
        assertEquals(List.of("receiver-witness"), proposal.edits().stream().map(SourceRepair.Edit::rule).toList(), proposal.toString());
        Path merged = apply(broken, proposal);
        assertTrue(Files.readString(merged.resolve("p/Find.java")).contains("Predicate.<java.io.File>isEqual(known).or(File::exists)"));
        assertEquals(0, javac.run(null, null, null, "--release", "8", "-Xlint:-options", "-proc:none", "-d", repairedClasses.toString(),
            merged.resolve("p/Find.java").toString()));
        var outcomes = MethodRoundTrip.compareClass(Files.readAllBytes(referenceClasses.resolve("p/Find.class")),
            Files.readAllBytes(repairedClasses.resolve("p/Find.class")));
        assertTrue(outcomes.stream().allMatch(result -> result.outcome() == MethodRoundTrip.Outcome.IDENTICAL), outcomes.toString());
    }

    @Test void givesARawCreationTheTypeArgumentsTheOtherArgumentsBind() throws Exception {
        // picocli 4.7.6: `new DefaultExceptionHandler<List<Object>>().useErr(err)` printed raw, so the self-typed chain
        // is erased and no overload of parseWithHandlers applies.
        String written = "package p; import java.util.*; public class Handlers {"
            + " public interface ResultHandler<R> { R handle(); } public interface ExceptionHandler<R> { R fail(); }"
            + " public static abstract class Base<R, T extends Base<R, T>> { @SuppressWarnings(\"unchecked\") public T named(String n) { return (T) this; } }"
            + " public static class Last extends Base<List<Object>, Last> implements ResultHandler<List<Object>> { public List<Object> handle() { return null; } }"
            + " public static class Fallback<R> extends Base<R, Fallback<R>> implements ExceptionHandler<R> { public R fail() { return null; } }"
            + " public static <R> R run(ResultHandler<R> handler, ExceptionHandler<R> exceptions, String... args) { return handler.handle(); }"
            + " public static Object run(Object handler, Object exceptions, int count) { return null; }"
            + " static void main(String[] args) { run(new Last().named(\"a\"), new Fallback<List<Object>>().named(\"b\"), args); } }";
        Path reference = Files.createDirectories(dir.resolve("reference/p"));
        Files.writeString(reference.resolve("Handlers.java"), written);
        Path broken = sources("creation", Map.of("p/Handlers.java", written.replace("new Fallback<List<Object>>()", "new Fallback()")));
        var proposal = new SourceRepair().propose(broken, dir.resolve("repair"), ORIGINALS, Duration.ofSeconds(60), List.of());
        assertEquals(List.of("creation-type-argument"), proposal.edits().stream().map(SourceRepair.Edit::rule).toList(), proposal.toString());
        Path merged = apply(broken, proposal);
        assertTrue(Files.readString(merged.resolve("p/Handlers.java")).contains("new Fallback<java.util.List<java.lang.Object>>().named(\"b\")"));
        Path referenceClasses = dir.resolve("reference-classes"), repairedClasses = dir.resolve("repaired-classes");
        var javac = javax.tools.ToolProvider.getSystemJavaCompiler();
        assertEquals(0, javac.run(null, null, null, "--release", "8", "-Xlint:-options", "-proc:none", "-d", referenceClasses.toString(),
            reference.resolve("Handlers.java").toString()));
        assertEquals(0, javac.run(null, null, null, "--release", "8", "-Xlint:-options", "-proc:none", "-d", repairedClasses.toString(),
            merged.resolve("p/Handlers.java").toString()));
        var outcomes = MethodRoundTrip.compareClass(Files.readAllBytes(referenceClasses.resolve("p/Handlers.class")),
            Files.readAllBytes(repairedClasses.resolve("p/Handlers.class")));
        assertTrue(outcomes.stream().allMatch(result -> result.outcome() == MethodRoundTrip.Outcome.IDENTICAL), outcomes.toString());
    }

    @Test void castsAnArgumentToTheTypeVariableItIsPassedTo() throws Exception {
        // Guava 17's MapConstraints: `ConstrainedMultimap.this.get((K) key)` printed without the unchecked cast.
        String written = "package p; import java.util.*; public class Outer<K, V> { Collection<V> get(K key) { return null; }"
            + " class View { Collection<V> lookup(Object key) { Collection<V> c = Outer.this.get((K)key); return c; } } }";
        Path reference = Files.createDirectories(dir.resolve("reference/p"));
        Files.writeString(reference.resolve("Outer.java"), written);
        Path broken = sources("typevar", Map.of("p/Outer.java", written.replace("get((K)key)", "get(key)")));
        var proposal = new SourceRepair().propose(broken, dir.resolve("repair"), ORIGINALS, Duration.ofSeconds(60), List.of());
        assertEquals(List.of("type-variable-cast"), proposal.edits().stream().map(SourceRepair.Edit::rule).toList(), proposal.toString());
        Path merged = apply(broken, proposal);
        assertEquals(written, Files.readString(merged.resolve("p/Outer.java")));
        Path referenceClasses = dir.resolve("reference-classes"), repairedClasses = dir.resolve("repaired-classes");
        var javac = javax.tools.ToolProvider.getSystemJavaCompiler();
        assertEquals(0, javac.run(null, null, null, "--release", "8", "-Xlint:-options", "-proc:none", "-d", referenceClasses.toString(),
            reference.resolve("Outer.java").toString()));
        assertEquals(0, javac.run(null, null, null, "--release", "8", "-Xlint:-options", "-proc:none", "-d", repairedClasses.toString(),
            merged.resolve("p/Outer.java").toString()));
        var outcomes = MethodRoundTrip.compareClass(Files.readAllBytes(referenceClasses.resolve("p/Outer$View.class")),
            Files.readAllBytes(repairedClasses.resolve("p/Outer$View.class")));
        assertTrue(!outcomes.isEmpty() && outcomes.stream().allMatch(result -> result.outcome() == MethodRoundTrip.Outcome.IDENTICAL), outcomes.toString());
    }

    @Test void dropsTypeArgumentsOutOfScopeFromACast() throws Exception {
        // Guava 17's MapMaker: a cast to its superclass's field type, whose type variables K0 and V0 are not in scope.
        String base = "package p; public class Base<K0, V0> { Listener<K0, V0> listener; public interface Listener<K, V> {} }";
        Path broken = sources("scope", Map.of("p/Base.java", base, "p/Maker.java", "package p;\npublic class Maker extends Base<Object, Object> {\n"
            + "  <K, V> void listen(Base.Listener<K, V> l) {\n    super.listener = (Base.Listener<K0, V0>)java.util.Objects.requireNonNull(l);\n  }\n}\n"));
        var proposal = new SourceRepair().propose(broken, dir.resolve("repair"), ORIGINALS, Duration.ofSeconds(60), List.of());
        assertEquals(List.of("raw-cast-type"), proposal.edits().stream().map(SourceRepair.Edit::rule).toList(), proposal.toString());
        Path merged = apply(broken, proposal);
        assertTrue(Files.readString(merged.resolve("p/Maker.java")).contains("super.listener = (Base.Listener)java.util.Objects.requireNonNull(l);"));
        assertEquals(VerificationResult.Status.COMPILED, compile(merged, "verify").status());
    }

    @Test void hoistsALocalClassUsedBeforeTheLoopItWasPrintedIn() throws Exception {
        // Guava 17's Types.ClassOwnership.detectJvmBehavior: Vineflower prints `class LocalClass<T> {}` inside the loop that
        // names `LocalClass.class`, after the statement that creates an anonymous subclass of it.
        String written = "package p;\npublic class Owner {\n  static Object owner(Class<?> c) { return c.getEnclosingClass(); }\n"
            + "  static int detect() {\n    class LocalClass<T> {}\n    Class<?> subclass = new LocalClass<String>() {}.getClass();\n"
            + "    for (int i = 0; i < 2; i++) {\n      if (owner(LocalClass.class) == subclass.getGenericSuperclass()) {\n        return i;\n      }\n"
            + "    }\n    return -1;\n  }\n}\n";
        String printed = written.replace("    class LocalClass<T> {}\n", "")
            .replace("    for (int i = 0; i < 2; i++) {\n", "    for (int i = 0; i < 2; i++) {\n      class LocalClass<T> {}\n");
        Path reference = Files.createDirectories(dir.resolve("reference/p"));
        Files.writeString(reference.resolve("Owner.java"), written);
        Path broken = sources("local-class", Map.of("p/Owner.java", printed));
        var proposal = new SourceRepair().propose(broken, dir.resolve("repair"), ORIGINALS, Duration.ofSeconds(60), List.of());
        assertEquals(List.of("hoist-local-class", "hoist-local-class"), proposal.edits().stream().map(SourceRepair.Edit::rule).toList(), proposal.toString());
        Path merged = apply(broken, proposal);
        assertEquals(written, Files.readString(merged.resolve("p/Owner.java")));
        Path referenceClasses = dir.resolve("reference-classes"), repairedClasses = dir.resolve("repaired-classes");
        var javac = javax.tools.ToolProvider.getSystemJavaCompiler();
        assertEquals(0, javac.run(null, null, null, "--release", "8", "-Xlint:-options", "-proc:none", "-d", referenceClasses.toString(),
            reference.resolve("Owner.java").toString()));
        assertEquals(0, javac.run(null, null, null, "--release", "8", "-Xlint:-options", "-proc:none", "-d", repairedClasses.toString(),
            merged.resolve("p/Owner.java").toString()));
        for (String name : List.of("Owner", "Owner$1", "Owner$1LocalClass")) {
            var outcomes = MethodRoundTrip.compareClass(Files.readAllBytes(referenceClasses.resolve("p/" + name + ".class")),
                Files.readAllBytes(repairedClasses.resolve("p/" + name + ".class")));
            assertTrue(!outcomes.isEmpty() && outcomes.stream().allMatch(result -> result.outcome() == MethodRoundTrip.Outcome.IDENTICAL), name + outcomes);
        }
    }

    @Test void stubsTheMethodsAJdkInterfaceGainedAfterTheOriginalTargetAndMarksThem() throws Exception {
        // Guava 17's Types.TypeVariableImpl: built for Java 6, it implements TypeVariable, which Java 8 made an
        // AnnotatedElement with getAnnotatedBounds(); javac 21 cannot build below release 8.
        Path broken = sources("environment", Map.of("p/Variable.java", "package p;\nimport java.lang.reflect.*;\n"
            + "public final class Variable<D extends GenericDeclaration> implements TypeVariable<D> {\n"
            + "   public Type[] getBounds() { return null; }\n   public D getGenericDeclaration() { return null; }\n"
            + "   public String getName() { return null; }\n}\n"));
        var blind = new SourceRepair().propose(broken, dir.resolve("blind"), ORIGINALS, Duration.ofSeconds(60), List.of());
        assertEquals(List.of("no-original-classes"), blind.unrepaired().stream().map(SourceRepair.Unrepaired::reason).toList(), blind.toString());
        // Built for Java 8, the original would have had to declare the methods: no stub.
        var java8 = new SourceRepair().propose(broken, dir.resolve("java8"), ORIGINALS, Duration.ofSeconds(60), List.of(),
            variableJar(org.objectweb.asm.Opcodes.V1_8, "java8.jar"));
        assertEquals(List.of("target-release-buildable"), java8.unrepaired().stream().map(SourceRepair.Unrepaired::reason).toList(), java8.toString());
        var proposal = new SourceRepair().propose(broken, dir.resolve("repair"), ORIGINALS, Duration.ofSeconds(60), List.of(),
            variableJar(org.objectweb.asm.Opcodes.V1_6, "java6.jar"));
        assertEquals(List.of(SourceRepair.ENVIRONMENT_STUB), proposal.edits().stream().map(SourceRepair.Edit::rule).toList(), proposal.toString());
        Path merged = apply(broken, proposal);
        String repaired = Files.readString(merged.resolve("p/Variable.java"));
        for (String stub : List.of("   public java.lang.reflect.AnnotatedType[] getAnnotatedBounds() {\n",
                "   public <T extends java.lang.annotation.Annotation> T getAnnotation(java.lang.Class<T> p0) {\n",
                "   public java.lang.annotation.Annotation[] getAnnotations() {\n", "   public java.lang.annotation.Annotation[] getDeclaredAnnotations() {\n"))
            assertTrue(repaired.contains(stub), repaired);
        assertEquals(4, repaired.split("// ClassRevive environment stub, not in the original class \\(class file version 50", -1).length - 1, repaired);
        assertTrue(repaired.endsWith("      throw new UnsupportedOperationException(\"getAnnotatedBounds: not in the original class\");\n   }\n}\n"), repaired);
        assertEquals(VerificationResult.Status.COMPILED, compile(merged, "verify").status());
    }

    /** {@code p/Variable} as javac would have built it for {@code version}: no method TypeVariable gained in Java 8. */
    private Path variableJar(int version, String name) throws Exception {
        var writer = new org.objectweb.asm.ClassWriter(org.objectweb.asm.ClassWriter.COMPUTE_MAXS);
        writer.visit(version, org.objectweb.asm.Opcodes.ACC_PUBLIC | org.objectweb.asm.Opcodes.ACC_FINAL | org.objectweb.asm.Opcodes.ACC_SUPER, "p/Variable",
            "<D::Ljava/lang/reflect/GenericDeclaration;>Ljava/lang/Object;Ljava/lang/reflect/TypeVariable<TD;>;", "java/lang/Object",
            new String[] {"java/lang/reflect/TypeVariable"});
        for (String[] method : new String[][] {{"getBounds", "()[Ljava/lang/reflect/Type;"},
                {"getGenericDeclaration", "()Ljava/lang/reflect/GenericDeclaration;"}, {"getName", "()Ljava/lang/String;"}}) {
            var code = writer.visitMethod(org.objectweb.asm.Opcodes.ACC_PUBLIC, method[0], method[1], null, null);
            code.visitCode(); code.visitInsn(org.objectweb.asm.Opcodes.ACONST_NULL); code.visitInsn(org.objectweb.asm.Opcodes.ARETURN);
            code.visitMaxs(0, 0); code.visitEnd();
        }
        writer.visitEnd();
        Path jar = dir.resolve(name);
        try (var zip = new java.util.zip.ZipOutputStream(Files.newOutputStream(jar))) {
            zip.putNextEntry(new java.util.zip.ZipEntry("p/Variable.class")); zip.write(writer.toByteArray()); zip.closeEntry();
        }
        return jar;
    }

    @Test void neverHoistsALocalClassThatReadsALocalOfItsMethod() throws Exception {
        Path broken = sources("local-capture", Map.of("p/Capture.java", "package p;\npublic class Capture {\n  static Object make(final int n) {\n"
            + "    Object first = new Local() {};\n    for (int i = 0; i < n; i++) {\n      final int j = i;\n"
            + "      class Local { int value() { return j; } }\n      first = new Local();\n    }\n    return first;\n  }\n}\n"));
        var proposal = new SourceRepair().propose(broken, dir.resolve("repair"), ORIGINALS, Duration.ofSeconds(60), List.of());
        assertTrue(proposal.edits().isEmpty(), proposal.toString());
        assertTrue(proposal.unrepaired().stream().anyMatch(left -> left.reason().equals("captures-local")), proposal.toString());
    }

    @Test void dropsAWrongFinalFromAConstantInitializedLoopVariable() throws Exception {
        // Vineflower on CFR 0.152: a loop variable printed as `final int attempt = 0` and captured by an anonymous class.
        Path broken = sources("final-loop", Map.of("p/Loop.java", "package p; public class Loop {\n  static void use(int value) {}\n"
            + "  static void run() {\n    for (final int attempt = 0; attempt < 2; attempt++) {\n"
            + "      Runnable task = new Runnable() { public void run() { use(attempt); } };\n      task.run();\n    }\n  }\n}\n"));
        var proposal = new SourceRepair().propose(broken, dir.resolve("repair"), ORIGINALS, Duration.ofSeconds(60), List.of());
        var rules = proposal.edits().stream().map(SourceRepair.Edit::rule).distinct().sorted().toList();
        assertEquals(List.of("capture-copy", "drop-final-local"), rules, proposal.unrepaired().toString());
        assertEquals(VerificationResult.Status.COMPILED, compile(apply(broken, proposal), "verify").status());
    }

    @Test void capturesFromSeveralStatementsOfOneBlockGetDistinctCopies() throws Exception {
        // CFR 0.152's LiteralRewriter: one reassigned local read by inner classes in several statements of an initializer.
        Path broken = sources("capture-many", Map.of("p/Init.java", "package p; public class Init {\n  static Runnable a, b;\n"
            + "  static {\n    int ii = 1;\n    ii = 2;\n    a = new Runnable() { public void run() { System.out.print(ii); } };\n"
            + "    b = new Runnable() { public void run() { System.out.print(ii + 1); } };\n  }\n}\n"));
        var proposal = new SourceRepair().propose(broken, dir.resolve("repair"), ORIGINALS, Duration.ofSeconds(60), List.of());
        var declared = proposal.edits().stream().filter(edit -> edit.after().startsWith("final int ")).map(SourceRepair.Edit::after).toList();
        assertEquals(2, declared.size(), proposal.edits().toString());
        assertTrue(declared.get(0).contains("iiCaptured =") && declared.get(1).contains("iiCaptured2 ="), declared.toString());
        assertEquals(VerificationResult.Status.COMPILED, compile(apply(broken, proposal), "verify").status());
    }

    @Test void dropsTheTypeArgumentsOfAnInconvertibleGenericCastWithoutChangingTheCheckcast() throws Exception {
        // Guava 17's `(ImmutableMap<K, Collection<V>>)this.map` for a field typed with a wildcard.
        String shaped = "package p; import java.util.*; public class Casts {\n"
            + "  final Map<String, ? extends List<Integer>> map = new HashMap<>();\n"
            + "  Map<String, Collection<Integer>> view() { return (Map<String, Collection<Integer>>)this.map; }\n}\n";
        Path broken = sources("raw-cast-type", Map.of("p/Casts.java", shaped));
        var proposal = new SourceRepair().propose(broken, dir.resolve("repair"), ORIGINALS, Duration.ofSeconds(60), List.of());
        assertEquals(List.of("raw-cast-type"), proposal.edits().stream().map(SourceRepair.Edit::rule).toList(), proposal.unrepaired().toString());
        assertEquals("Map<String, Collection<Integer>>", proposal.edits().getFirst().before());
        assertEquals("Map", proposal.edits().getFirst().after());
        Path merged = apply(broken, proposal);
        Path reference = Files.createDirectories(dir.resolve("reference/p"));
        Files.writeString(reference.resolve("Casts.java"), shaped.replace("(Map<String, Collection<Integer>>)this.map", "(Map)this.map"));
        Path referenceClasses = dir.resolve("reference-classes"), repairedClasses = dir.resolve("repaired-classes");
        var javac = javax.tools.ToolProvider.getSystemJavaCompiler();
        assertEquals(0, javac.run(null, null, null, "--release", "8", "-Xlint:-options", "-proc:none", "-d", referenceClasses.toString(),
            reference.resolve("Casts.java").toString()));
        assertEquals(0, javac.run(null, null, null, "--release", "8", "-Xlint:-options", "-proc:none", "-d", repairedClasses.toString(),
            merged.resolve("p/Casts.java").toString()));
        var outcomes = MethodRoundTrip.compareClass(Files.readAllBytes(referenceClasses.resolve("p/Casts.class")),
            Files.readAllBytes(repairedClasses.resolve("p/Casts.class")));
        assertTrue(outcomes.stream().allMatch(result -> result.outcome() == MethodRoundTrip.Outcome.IDENTICAL), outcomes.toString());
    }

    @Test void eachRuleRepairsItsDiagnosticAndTheResultCompiles() throws Exception {
        Path broken = sources("broken", Map.of(
            "p/Conditional.java", "package p; public final class Conditional implements Factory {\n"
                + "  public <T> Box<T> create(Class<T> type) { return type == Integer.class ? new IntBox() : null; }\n}\n",
            "p/Returned.java", "package p; public final class Returned implements Factory {\n"
                + "  public <T> Box<T> create(Class<T> type) { return new IntBox(); }\n}\n",
            "p/Diamond.java", "package p; final class Diamond {\n  <T> Object make(Box<T> box) { Holder<T> held = new Holder<>(box); return held; }\n}\n",
            "p/Statics.java", "package p; final class Statics<E> {\n  static Object wrap(Object value) { return (Box<E>) value; }\n}\n",
            "p/Calls.java", "package p; import java.util.*; final class Calls {\n  static <T> void both(List<T> a, List<T> b) {}\n"
                + "  void call(List<?> x, List<?> y) { both(x, y); }\n}\n",
            "p/Finals.java", "package p; final class Finals {\n  Runnable task(Object a, Object b) {\n    final Object chosen = null;\n"
                + "    if (a != null) chosen = a;\n    if (chosen == null) chosen = b;\n"
                + "    return new Runnable() { public void run() { System.out.println(chosen); } };\n  }\n}\n",
            "p/Renamed.java", "package p; final class Renamed {\n  Runnable task(boolean flag) {\n"
                + "    if (flag) { final String value = \"a\"; return () -> System.out.println(value); }\n"
                + "    final String valuex = \"b\";\n    return new Runnable() { public void run() { System.out.println(value); } };\n  }\n}\n"));
        var before = compile(broken, "before");
        assertEquals(VerificationResult.Status.COMPILE_FAILED, before.status());
        assertEquals(0, before.syntaxErrors());

        var proposal = new SourceRepair().propose(broken, dir.resolve("repair"), ORIGINALS, Duration.ofSeconds(60), List.of());
        assertEquals(List.of(), proposal.unrepaired(), "every diagnostic here has a rule");
        var rules = new TreeMap<String,String>();
        proposal.edits().forEach(edit -> rules.merge(edit.file(), edit.rule(), (a, b) -> a.endsWith(b) ? a : a + "," + b));
        assertEquals(Map.of("p/Conditional.java", "raw-cast-to-target", "p/Returned.java", "raw-cast-to-target",
            "p/Diamond.java", "raw-diamond", "p/Statics.java", "raw-type-argument", "p/Calls.java", "raw-argument",
            "p/Finals.java", "drop-final-local,capture-copy", "p/Renamed.java", "renamed-local"), rules);
        assertTrue(proposal.edits().stream().allMatch(edit -> edit.code().startsWith("compiler.err.") && edit.line() > 0 && edit.column() > 0));
        assertEquals(proposal.edits().stream().map(SourceRepair.Edit::file).collect(java.util.stream.Collectors.toSet()), proposal.files().keySet());

        String conditional = Files.readString(proposal.files().get("p/Conditional.java"));
        assertTrue(conditional.contains("? (p.Box)new IntBox() : null"), conditional);
        assertTrue(Files.readString(proposal.files().get("p/Diamond.java")).contains("new Holder(box)"));
        assertTrue(Files.readString(proposal.files().get("p/Statics.java")).contains("return (Box) value;"));
        assertTrue(Files.readString(proposal.files().get("p/Calls.java")).contains("both((java.util.List)x, (java.util.List)y)"));
        String finals = Files.readString(proposal.files().get("p/Finals.java"));
        assertTrue(finals.contains("    Object chosen = null;"), finals);
        assertTrue(finals.contains("final Object chosenCaptured = chosen;\n    return new Runnable()"), finals);
        assertTrue(finals.contains("System.out.println(chosenCaptured)"), finals);
        assertTrue(Files.readString(proposal.files().get("p/Renamed.java")).contains("public void run() { System.out.println(valuex); }"));
        // The originals are untouched; repaired copies live in the worker's own folder.
        assertTrue(Files.readString(broken.resolve("p/Finals.java")).contains("final Object chosen = null;"));

        var after = compile(apply(broken, proposal), "after");
        assertEquals(VerificationResult.Status.COMPILED, after.status(), Files.readString(dir.resolve("after/engine.log")));
    }

    @Test void declarationsAndUnknownDiagnosticsAreLeftAloneWithAReason() throws Exception {
        Path broken = sources("unrepaired", Map.of(
            "p/Field.java", "package p; final class Field {\n  Missing field;\n}\n",
            "p/Primitive.java", "package p; final class Primitive {\n  int value() { int x = \"text\"; return x; }\n}\n",
            "p/Downcast.java", "package p; final class Downcast implements Factory {\n"
                + "  public <T> Box<T> create(Class<T> type) { Object any = null; return any; }\n}\n"));
        var proposal = new SourceRepair().propose(broken, dir.resolve("repair"), ORIGINALS, Duration.ofSeconds(60), List.of());
        assertEquals(List.of(), proposal.edits());
        assertEquals(Map.of(), proposal.files());
        var reasons = new TreeMap<String,String>();
        proposal.unrepaired().forEach(left -> reasons.put(left.file(), left.reason()));
        // A field's declared type is part of the structural signature, so it is never edited.
        assertEquals(Map.of("p/Field.java", "outside-method-body", "p/Primitive.java", "target-not-parameterized",
            "p/Downcast.java", "not-an-upcast"), reasons);
    }

    @Test void aCandidateThatDoesNotParseGetsNoProposal() throws Exception {
        Path broken = sources("syntax", Map.of("p/Broken.java", "package p; final class Broken { void m() { int x = ; } }",
            "p/Returned.java", "package p; final class Returned implements Factory {\n"
                + "  public <T> Box<T> create(Class<T> type) { return new IntBox(); }\n}\n"));
        var proposal = new SourceRepair().propose(broken, dir.resolve("repair"), ORIGINALS, Duration.ofSeconds(60), List.of());
        assertEquals(List.of(), proposal.edits());
        assertEquals(List.of(), proposal.unrepaired());
    }

    @Test void logFieldsRoundTripThroughEscapingAndInvalidEscapesAreRejected() throws Exception {
        String text = "a\tb\\c\nd\re";
        assertEquals(text, SourceRepair.unescape(SourceRepairWorker.escape(text)));
        assertFalse(SourceRepairWorker.escape(text).contains("\t") || SourceRepairWorker.escape(text).contains("\n"));
        for (String bad : List.of("trailing\\", "\\x")) assertThrows(IOException.class, () -> SourceRepair.unescape(bad), bad);
        for (String row : List.of("../A.java\t1\t1\tcompiler.err.x\tno-rule", "/A.java\t1\t1\tcompiler.err.x\tno-rule",
                "A.java\t1\t1\tcompiler.warn.x\tno-rule", "A.java\t1\t1\tcompiler.err.x", "A.txt\t1\t1\tcompiler.err.x\tno-rule")) {
            Path log = dir.resolve("log-" + Math.abs(row.hashCode()) + ".tsv");
            Files.writeString(log, row + "\n");
            assertThrows(IOException.class, () -> SourceRepair.rows(log, 5, 10), row);
        }
    }

    /** The source with the cast edits of {@link SourceRepairWorker#castSites} for {@code types} applied to method {@code name}. */
    static String castEdited(String source, String name, Set<String> types) {
        var warnings = new ArrayList<javax.tools.Diagnostic<? extends javax.tools.JavaFileObject>>();
        var file = new javax.tools.SimpleJavaFileObject(java.net.URI.create("string:///p/T.java"), javax.tools.JavaFileObject.Kind.SOURCE) {
            @Override public CharSequence getCharContent(boolean ignoreEncodingErrors) { return source; }
        };
        var task = (com.sun.source.util.JavacTask) javax.tools.ToolProvider.getSystemJavaCompiler().getTask(null, null, diagnostic -> {
            if (diagnostic.getKind() == javax.tools.Diagnostic.Kind.ERROR) fail(diagnostic.toString());
            if (diagnostic.getKind() == javax.tools.Diagnostic.Kind.WARNING) warnings.add(diagnostic);
        }, List.of("--release", "8", "-Xlint:cast", "-Xlint:-options", "-proc:none"), null, List.of(file));
        com.sun.source.tree.CompilationUnitTree unit;
        try { unit = task.parse().iterator().next(); task.analyze(); } catch (IOException error) { throw new java.io.UncheckedIOException(error); }
        var body = new com.sun.source.util.TreePath[1];
        new com.sun.source.util.TreePathScanner<Void,Void>() {
            @Override public Void visitMethod(com.sun.source.tree.MethodTree tree, Void unused) {
                if (tree.getName().contentEquals(name)) body[0] = new com.sun.source.util.TreePath(getCurrentPath(), tree.getBody());
                return super.visitMethod(tree, null);
            }
        }.scan(unit, null);
        var edits = SourceRepairWorker.castEdits(SourceRepairWorker.castSites(task, warnings, body[0]), types);
        var text = new StringBuilder(source);
        for (int i = edits.size() - 1; i >= 0; i--) text.replace(edits.get(i).start(), edits.get(i).end(), edits.get(i).replacement());
        return text.toString();
    }

    @Test void castSitesTakeAwayOnlyTheRequestedCastsAndKeepEveryExpression() {
        String source = "package p; import java.util.*; class T {\n"
            + "  static class Pair<A, B> { static <A, B> Pair<A, B> make(A a, B b) { return null; } }\n"
            + "  Object joined(List<String> l) { return(String)l.get(0); }\n"
            + "  void nested(List<Pair<Integer, String>> out, List<String> l) { out.add(Pair.make(1, l.get(0))); }\n"
            + "  void statement(List<String> l) { Pair.make(1, l.get(0)); }\n"
            + "  void filtered(Set<String> s, Set<Integer> n, List<String> l, List<Integer> m) { s.add(l.get(0)); n.add(m.get(0)); }\n"
            + "  void creation(List<String> l) { Object b = new ArrayList<String>(Collections.singleton(l.get(0))); }\n"
            + "  List<String> varargs(List<String> l) { return Arrays.asList(l.get(0), l.get(1)); }\n"
            + "  String used(Map<String, String> m, Map.Entry<String, String> e) { return m.put(e.getKey(), e.getValue()); }\n"
            + "  boolean arrays(String[] a, String[] b) { return Arrays.equals((Object[]) a, (Object[])b); }\n"
            + "  void wild(Set<Object> s, List<? extends Number> l) { s.add((Number) l.get(0)); }\n}\n";
        var strings = Set.of("java/lang/String");
        // `return(T)x` keeps its tokens apart.
        assertTrue(castEdited(source, "joined", strings).contains("Object joined(List<String> l) { return l.get(0); }"));
        // The generic method's argument is upcast and its result raw inside the outer call; the outer call needs nothing.
        assertTrue(castEdited(source, "nested", strings).contains("{ out.add(((p.T.Pair) Pair.make(1, ((java.lang.Object) l.get(0))))); }"));
        // An unused result needs no raw cast (a cast is no statement).
        assertTrue(castEdited(source, "statement", strings).contains("{ Pair.make(1, ((java.lang.Object) l.get(0))); }"));
        // Only the sites casting to a requested class.
        assertTrue(castEdited(source, "filtered", Set.of("java/lang/Integer")).contains("{ s.add(l.get(0)); ((java.util.Set) n).add(m.get(0)); }"));
        // singleton's argument is cast for its own type variable; the creation's argument is a Set, which needs nothing.
        assertTrue(castEdited(source, "creation", strings)
            .contains("new ArrayList<String>(((java.util.Set) Collections.singleton(((java.lang.Object) l.get(0)))))"));
        // Variable-arity elements are cast by both javac generations; a raw receiver would erase a value that is used.
        assertEquals(source, castEdited(source, "varargs", strings));
        assertEquals(source, castEdited(source, "used", strings));
        // Upcasts javac does not call redundant but checks: to an array type, and of a wildcard-typed value.
        assertTrue(castEdited(source, "arrays", Set.of("[Ljava/lang/Object;")).contains("{ return Arrays.equals(a, b); }"));
        assertTrue(castEdited(source, "wild", Set.of("java/lang/Number")).contains("{ s.add(l.get(0)); }"));
        assertEquals(source, castEdited(source, "wild", strings));
    }
}
