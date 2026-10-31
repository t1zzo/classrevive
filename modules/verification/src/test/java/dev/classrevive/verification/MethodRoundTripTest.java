package dev.classrevive.verification;

import dev.classrevive.bytecode.ClassInspector;
import dev.classrevive.model.ClassInfo;
import dev.classrevive.verification.MethodRoundTrip.Outcome;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.zip.*;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.*;
import static org.junit.jupiter.api.Assertions.*;

class MethodRoundTripTest {
    @TempDir Path dir;

    /** Known source; javac output is only parsed, never loaded. */
    static final String FIXTURE = """
        package demo;
        public class Fixture {
            private final StringBuilder log = new StringBuilder();
            public int add(int a, int b) { return a + b; }
            public int answer() { return 42; }
            public long wide(long x) { return x * 100000L + 7; }
            public void record(String value) { log.append(value); log.append('|'); }
            public String choose(String key) {
                switch (key) { case "one": return "1"; case "two": return "2"; default: return "?"; }
            }
            public int loop(int n) {
                int total = 0;
                for (int i = 0; i < n; i++) { if (i % 3 == 0) continue; total += i; }
                return total;
            }
            public int guarded(String text) {
                try { return Integer.parseInt(text); } catch (NumberFormatException error) { return -1; }
            }
            static int helper(int v) { return v << 2; }
            interface Shape { double area(); }
            static final class Square implements Shape { final double side; Square(double side) { this.side = side; } public double area() { return side * side; } }
        }
        """;

    Map<String,byte[]> compile(String source, String name) throws Exception { return compile(source, name, "17"); }

    Map<String,byte[]> compile(String source, String name, String release) throws Exception {
        Path src = Files.createDirectories(dir.resolve(name).resolve("src/demo"));
        Path out = Files.createDirectories(dir.resolve(name).resolve("classes"));
        Files.writeString(src.resolve("Fixture.java"), source);
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "--release", release, "-proc:none", "-Xlint:-options",
            "-d", out.toString(), src.resolve("Fixture.java").toString()));
        var result = new TreeMap<String,byte[]>();
        try (var paths = Files.walk(out)) {
            for (var path : paths.filter(p -> p.toString().endsWith(".class")).toList()) {
                byte[] bytes = Files.readAllBytes(path);
                result.put(new ClassInspector().inspect(bytes).name(), bytes);
            }
        }
        return result;
    }

    Path jar(Map<String,byte[]> classes) throws Exception {
        Path jar = dir.resolve("classes.jar");
        try (var zip = new ZipOutputStream(Files.newOutputStream(jar))) {
            for (var entry : classes.entrySet()) { zip.putNextEntry(new ZipEntry(entry.getKey() + ".class")); zip.write(entry.getValue()); zip.closeEntry(); }
        }
        return jar;
    }

    /** Runs the real verification path: worker javac on the candidate source, then per-method comparison. */
    VerificationResult roundTrip(String candidate) throws Exception {
        var originals = compile(FIXTURE, "original");
        List<ClassInfo> infos = new ArrayList<>();
        for (var bytes : originals.values()) infos.add(new ClassInspector().inspect(bytes));
        Path sources = Files.createDirectories(dir.resolve("candidate/demo"));
        Files.writeString(sources.resolve("Fixture.java"), candidate);
        var result = new CompilerVerifier().verify(dir.resolve("candidate"), dir.resolve("verify"), infos, Duration.ofSeconds(30),
            List.of(), jar(originals));
        assertEquals(VerificationResult.Status.COMPILED, result.status());
        assertTrue(result.roundTripMeasured());
        return result;
    }

    static Map<String,Outcome> outcomes(VerificationResult result) {
        var map = new TreeMap<String,Outcome>();
        for (var type : result.roundTrip()) for (var method : type.methods())
            map.put(type.className() + "." + method.name() + method.descriptor(), method.outcome());
        return map;
    }

    static void onlyDifferent(VerificationResult result, String method) {
        var outcomes = outcomes(result);
        assertEquals(Outcome.DIFFERENT, outcomes.get(method), outcomes.toString());
        outcomes.forEach((name, outcome) -> { if (!name.equals(method)) assertEquals(Outcome.IDENTICAL, outcome, name); });
        assertEquals(1, result.roundTripDifferent());
    }

    @Test void sameSourceIsIdenticalForEveryMethod() throws Exception {
        var result = roundTrip(FIXTURE);
        var outcomes = outcomes(result);
        assertEquals(11, outcomes.size(), outcomes.toString()); // 9 bodies in Fixture, 2 in Square, none in Shape
        outcomes.forEach((name, outcome) -> assertEquals(Outcome.IDENTICAL, outcome, name));
        assertEquals(outcomes.size(), result.roundTripIdentical());
        assertEquals(Set.of("demo/Fixture", "demo/Fixture$Shape", "demo/Fixture$Square"),
            new TreeSet<>(result.roundTrip().stream().map(ClassRoundTrip::className).toList()));
    }

    @Test void swappedOperatorIsDifferent() throws Exception {
        onlyDifferent(roundTrip(FIXTURE.replace("return a + b;", "return a - b;")), "demo/Fixture.add(II)I");
    }

    @Test void changedConstantIsDifferent() throws Exception {
        onlyDifferent(roundTrip(FIXTURE.replace("return 42;", "return 43;")), "demo/Fixture.answer()I");
    }

    @Test void removedCallIsDifferent() throws Exception {
        onlyDifferent(roundTrip(FIXTURE.replace(" log.append('|');", "")), "demo/Fixture.record(Ljava/lang/String;)V");
    }

    @Test void engineThrowStubIsDifferentEvenThoughSignaturesMatch() throws Exception {
        var result = roundTrip(FIXTURE.replace("return v << 2;",
            "throw new IllegalStateException(\"An error occurred while decompiling this method.\");"));
        assertEquals(result.expectedSymbols(), result.matchedSymbols(), "signatures alone cannot see the stub");
        onlyDifferent(result, "demo/Fixture.helper(I)I");
    }

    @Test void smallMutationsAreAlwaysDetectedInProcess() throws Exception {
        var original = compile(FIXTURE, "base");
        var mutations = Map.of(
            "loop(I)I", FIXTURE.replace("i % 3 == 0", "i % 3 == 1"),
            "loop(I)I#", FIXTURE.replace("i < n", "i <= n"),
            "wide(J)J", FIXTURE.replace("+ 7;", "+ 8;"),
            "choose(Ljava/lang/String;)Ljava/lang/String;", FIXTURE.replace("return \"2\";", "return \"3\";"),
            "guarded(Ljava/lang/String;)I", FIXTURE.replace("return -1;", "return 0;"));
        int index = 0;
        for (var mutation : new TreeMap<>(mutations).entrySet()) {
            var mutated = compile(mutation.getValue(), "mutant" + index++);
            String method = mutation.getKey().replace("#", "");
            var results = MethodRoundTrip.compare(original, mutated);
            for (var type : results) for (var result : type.methods()) {
                boolean target = type.className().equals("demo/Fixture") && (result.name() + result.descriptor()).equals(method);
                assertEquals(target ? Outcome.DIFFERENT : Outcome.IDENTICAL, result.outcome(), mutation.getKey() + " -> " + result);
            }
        }
    }

    @Test void aDifferentBodyNamesItsFirstDivergingOpcodesWithoutOperands() throws Exception {
        var original = compile(FIXTURE, "base");
        var operator = compile(FIXTURE.replace("return a + b;", "return a - b;"), "operator");
        var constant = compile(FIXTURE.replace("return \"2\";", "return \"secret\";"), "constant");
        var added = MethodRoundTrip.compare(original, operator).stream().flatMap(type -> type.methods().stream())
            .filter(method -> method.name().equals("add")).findFirst().orElseThrow();
        assertEquals(Outcome.DIFFERENT, added.outcome());
        assertEquals("iadd/isub", added.difference());
        var chosen = MethodRoundTrip.compare(original, constant).stream().flatMap(type -> type.methods().stream())
            .filter(method -> method.name().equals("choose")).findFirst().orElseThrow();
        assertEquals("const-string/const-string", chosen.difference(), "the detail names no target constant");
        for (var type : MethodRoundTrip.compare(original, operator))
            for (var method : type.methods()) if (method.outcome() != Outcome.DIFFERENT) assertEquals("", method.difference());
        String context = MethodRoundTrip.differenceContext(original.get("demo/Fixture"), constant.get("demo/Fixture"),
            "choose", "(Ljava/lang/String;)Ljava/lang/String;", 2);
        assertTrue(context.contains("secret") && context.startsWith("first difference at normalized instruction"), context);
        assertEquals("", MethodRoundTrip.differenceContext(original.get("demo/Fixture"), null, "choose", "(Ljava/lang/String;)Ljava/lang/String;", 2));
        assertEquals("<end>/ireturn", MethodRoundTrip.difference(List.of("a"), List.of("a", "op172")));
    }

    @Test void lineNumbersDoNotMatter() throws Exception {
        var original = compile(FIXTURE, "base");
        var shifted = compile(FIXTURE.replace("{\n", "{\n\n\n"), "shifted");
        assertNotEquals(Arrays.toString(original.get("demo/Fixture")), Arrays.toString(shifted.get("demo/Fixture")));
        for (var type : MethodRoundTrip.compare(original, shifted))
            for (var method : type.methods()) assertEquals(Outcome.IDENTICAL, method.outcome(), method.toString());
    }

    @Test void constantPoolOrderDoesNotMatter() {
        byte[] plain = generated(false, false, false);
        byte[] reordered = generated(false, false, true);
        assertFalse(Arrays.equals(plain, reordered));
        assertEquals(Outcome.IDENTICAL, single(plain, reordered));
    }

    @Test void constantFormsAndSlotNumbersAreEquivalentAfterNormalization() {
        assertEquals(Outcome.EQUIVALENT_AFTER_NORMALIZATION, single(generated(false, false, false), generated(true, false, false)));
        assertEquals(Outcome.EQUIVALENT_AFTER_NORMALIZATION, single(generated(false, false, false), generated(false, true, false)));
        assertEquals(Outcome.EQUIVALENT_AFTER_NORMALIZATION, single(generated(false, false, false), generated(true, true, true)));
    }

    @Test void jumpToReturnIsEquivalentToReturningInPlace() throws Exception {
        // javac compiles a returned conditional as one return reached by GOTO; decompilers often print two returns.
        String ternary = "package demo; public class Fixture { int f(boolean c, int x) { return c ? 0 : x; }"
            + " void g(boolean c) { if (c) { System.out.println(); } else { System.out.print(1); } } }";
        var original = compile(ternary, "ternary").get("demo/Fixture");
        var branches = compile(ternary.replace("return c ? 0 : x;", "if (c) return 0; return x;"), "branches").get("demo/Fixture");
        var swapped = compile(ternary.replace("return c ? 0 : x;", "if (c) return x; return 0;"), "swapped").get("demo/Fixture");
        var changed = compile(ternary.replace("return c ? 0 : x;", "if (c) return 1; return x;"), "changed").get("demo/Fixture");
        assertEquals(Outcome.EQUIVALENT_AFTER_NORMALIZATION, outcome(original, branches, "f(ZI)I"));
        assertEquals(Outcome.DIFFERENT, outcome(original, swapped, "f(ZI)I"));
        assertEquals(Outcome.DIFFERENT, outcome(original, changed, "f(ZI)I"));
        // A GOTO to code that is not a return stays a jump, so g's if/else is still compared as written.
        assertEquals(Outcome.IDENTICAL, outcome(original, branches, "g(Z)V"));
        var noElse = compile(ternary.replace("} else { System.out.print(1); }", "} System.out.print(1);"), "no-else").get("demo/Fixture");
        assertEquals(Outcome.DIFFERENT, outcome(original, noElse, "g(Z)V"));
    }

    @Test void invertedBranchWithSwappedBlocksIsEquivalentButOtherChangesAreNot() throws Exception {
        String base = "package demo; public class Fixture { static void a() {} static void b() {} static void c() {}"
            + " static void h(int x) { if (x > 0) { a(); } else { b(); } c(); }"
            + " static int t(int x) { try { a(); return x; } catch (RuntimeException e) { return 0; } } }";
        var original = compile(base, "branch-original").get("demo/Fixture");
        var inverted = compile(base.replace("if (x > 0) { a(); } else { b(); }", "if (x <= 0) { b(); } else { a(); }"), "inverted").get("demo/Fixture");
        var swappedOnly = compile(base.replace("if (x > 0) { a(); } else { b(); }", "if (x > 0) { b(); } else { a(); }"), "swapped-only").get("demo/Fixture");
        var otherTest = compile(base.replace("if (x > 0) { a(); } else { b(); }", "if (x < 0) { b(); } else { a(); }"), "other-test").get("demo/Fixture");
        var reordered = compile(base.replace("try { a(); return x; } catch (RuntimeException e) { return 0; }",
            "try { a(); } catch (RuntimeException e) { return 0; } return x;"), "reordered-try").get("demo/Fixture");
        assertEquals(Outcome.EQUIVALENT_AFTER_NORMALIZATION, outcome(original, inverted, "h(I)V"));
        assertEquals("same control flow, different block layout", MethodRoundTrip.compareClass(original, inverted).stream()
            .filter(result -> result.name().equals("h")).findFirst().orElseThrow().reason());
        assertEquals(Outcome.DIFFERENT, outcome(original, swappedOnly, "h(I)V"));
        assertEquals(Outcome.DIFFERENT, outcome(original, otherTest, "h(I)V"));
        // Since Phase 2BH bodies with exception handlers are matched by control flow too: the load and return that left
        // the try block cannot throw, so where the handler's range ends does not matter for them.
        assertNotEquals(Outcome.DIFFERENT, outcome(original, reordered, "t(I)I"));
    }

    @Test void objectMethodOnInterfaceReceiverMatchesAcrossJavacVersions() {
        // Older javac: invokevirtual java/lang/Object.toString on a Map receiver; javac 21: invokeinterface java/util/Map.toString.
        byte[] older = call(Opcodes.INVOKEVIRTUAL, "java/lang/Object", "toString", "()Ljava/lang/String;", false);
        byte[] newer = call(Opcodes.INVOKEINTERFACE, "java/util/Map", "toString", "()Ljava/lang/String;", true);
        assertEquals(Outcome.EQUIVALENT_AFTER_NORMALIZATION, single(older, newer));
        assertEquals(Outcome.DIFFERENT, single(older, call(Opcodes.INVOKEINTERFACE, "java/util/Map", "hashCode", "()I", true)));
        assertEquals(Outcome.DIFFERENT, single(call(Opcodes.INVOKEVIRTUAL, "java/util/HashMap", "size", "()I", false),
            call(Opcodes.INVOKEINTERFACE, "java/util/Map", "size", "()I", true)));
    }

    /** static Object f(java.util.Map m) { return m.&lt;call&gt;(); } with the given invoke spelling (the result is boxed or returned). */
    static byte[] call(int opcode, String owner, String name, String descriptor, boolean itf) {
        var writer = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "G", null, "java/lang/Object", null);
        var method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "f", "(Ljava/util/Map;)Ljava/lang/Object;", null, null);
        method.visitCode(); method.visitVarInsn(Opcodes.ALOAD, 0); method.visitMethodInsn(opcode, owner, name, descriptor, itf);
        if (descriptor.endsWith(")I")) method.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;", false);
        method.visitInsn(Opcodes.ARETURN); method.visitMaxs(0, 0); method.visitEnd(); writer.visitEnd();
        return writer.toByteArray();
    }

    @Test void privateCallOpcodeMatchesAcrossJavacVersions() {
        // javac before 11 calls a private method with invokespecial; with nestmates (JEP 181) it uses invokevirtual.
        assertEquals(Outcome.EQUIVALENT_AFTER_NORMALIZATION, outcome(selfCall(true, Opcodes.INVOKEVIRTUAL), selfCall(true, Opcodes.INVOKESPECIAL), "f()I"));
        // For an overridable method, invokespecial is a non-virtual call (like super.m()), so the two forms differ.
        assertEquals(Outcome.DIFFERENT, outcome(selfCall(false, Opcodes.INVOKEVIRTUAL), selfCall(false, Opcodes.INVOKESPECIAL), "f()I"));
    }

    /** class G { [private|public] int p() { return 1; } int f() { return this.p(); } } with the given call opcode. */
    static byte[] selfCall(boolean privateTarget, int opcode) {
        var writer = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        writer.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, "G", null, "java/lang/Object", null);
        var p = writer.visitMethod(privateTarget ? Opcodes.ACC_PRIVATE : Opcodes.ACC_PUBLIC, "p", "()I", null, null);
        p.visitCode(); p.visitInsn(Opcodes.ICONST_1); p.visitInsn(Opcodes.IRETURN); p.visitMaxs(0, 0); p.visitEnd();
        var f = writer.visitMethod(Opcodes.ACC_PUBLIC, "f", "()I", null, null);
        f.visitCode(); f.visitVarInsn(Opcodes.ALOAD, 0); f.visitMethodInsn(opcode, "G", "p", "()I", false);
        f.visitInsn(Opcodes.IRETURN); f.visitMaxs(0, 0); f.visitEnd(); writer.visitEnd();
        return writer.toByteArray();
    }

    @Test void aLocalThatOnlyPassesAValueThroughIsEquivalentToTheExpression() throws Exception {
        String direct = "package demo; public class Fixture { static String f(String s) { return s.trim(); } }";
        var original = compile(direct, "temp-direct").get("demo/Fixture");
        var temp = compile(direct.replace("return s.trim();", "String t = s.trim(); return t;"), "temp-local").get("demo/Fixture");
        var reused = compile(direct.replace("return s.trim();", "String t = s.trim(); System.out.print(t); return t;"), "temp-reused").get("demo/Fixture");
        var parameter = compile(direct.replace("return s.trim();", "s = s.trim(); return s;"), "temp-parameter").get("demo/Fixture");
        assertEquals(Outcome.EQUIVALENT_AFTER_NORMALIZATION, outcome(original, temp, "f(Ljava/lang/String;)Ljava/lang/String;"));
        assertEquals(Outcome.DIFFERENT, outcome(original, reused, "f(Ljava/lang/String;)Ljava/lang/String;"));
        // Since Phase 2BH locals are variables (webs), not slots: the parameter's new value is a variable of its own,
        // read only by the load right after its store, so it passes the value through like any local.
        assertEquals(Outcome.EQUIVALENT_AFTER_NORMALIZATION, outcome(original, parameter, "f(Ljava/lang/String;)Ljava/lang/String;"));
        var parameterRead = compile(direct.replace("return s.trim();", "String t = s.trim(); return s;"), "temp-parameter-read").get("demo/Fixture");
        assertEquals(Outcome.DIFFERENT, outcome(original, parameterRead, "f(Ljava/lang/String;)Ljava/lang/String;"));
    }

    static Outcome outcome(byte[] original, byte[] recompiled, String method) {
        return MethodRoundTrip.compareClass(original, recompiled).stream()
            .filter(result -> (result.name() + result.descriptor()).equals(method)).findFirst().orElseThrow().outcome();
    }

    @Test void mergingTwoLocalsIntoOneSlotIsDifferent() {
        assertEquals(Outcome.DIFFERENT, single(locals(1, 2), locals(1, 1)));
        assertEquals(Outcome.EQUIVALENT_AFTER_NORMALIZATION, single(locals(1, 2), locals(2, 1)));
    }

    @Test void negativeZeroIsNotZero() {
        assertEquals(Outcome.DIFFERENT, single(floating(0.0), floating(-0.0)));
    }

    @Test void missingClassAndMissingMethodAreDifferent() throws Exception {
        var original = compile(FIXTURE, "base");
        var withoutSquare = new TreeMap<>(original); withoutSquare.remove("demo/Fixture$Square");
        var results = MethodRoundTrip.compare(original, withoutSquare);
        var square = results.stream().filter(type -> type.className().equals("demo/Fixture$Square")).findFirst().orElseThrow();
        assertEquals(square.methods().size(), square.different());
        assertTrue(square.methods().stream().allMatch(method -> method.reason().contains("class missing")));
        var stripped = compile(FIXTURE.replace("static int helper(int v) { return v << 2; }", ""), "stripped");
        var fixture = MethodRoundTrip.compare(original, stripped).stream()
            .filter(type -> type.className().equals("demo/Fixture")).findFirst().orElseThrow();
        assertEquals(1, fixture.different());
        assertTrue(fixture.methods().stream().anyMatch(method -> method.name().equals("helper") && method.reason().contains("method missing")));
    }

    @Test void abstractMethodsContributeNoBodyEvidence() throws Exception {
        var original = compile(FIXTURE, "base");
        var shape = MethodRoundTrip.compare(original, original).stream()
            .filter(type -> type.className().equals("demo/Fixture$Shape")).findFirst().orElseThrow();
        assertTrue(shape.methods().isEmpty());
    }

    @Test void readsNormalizedClassJar() throws Exception {
        var original = compile(FIXTURE, "base");
        var read = MethodRoundTrip.readJar(jar(original));
        assertEquals(original.keySet(), read.keySet());
        assertArrayEquals(original.get("demo/Fixture"), read.get("demo/Fixture"));
    }

    static Outcome single(byte[] original, byte[] recompiled) {
        var results = MethodRoundTrip.compare(Map.of("G", original), Map.of("G", recompiled));
        var methods = results.getFirst().methods();
        assertEquals(1, methods.size());
        return methods.getFirst().outcome();
    }

    /** static int f(int p) { int a = 5; int b = p + 200; return a * b + 70000; } in several byte-level spellings. */
    static byte[] generated(boolean ldcConstants, boolean shiftedSlots, boolean padPool) {
        var writer = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "G", null, "java/lang/Object", null);
        if (padPool) { writer.newUTF8("padding entry"); writer.newConst(123456789); writer.newClass("java/util/List"); }
        var method = writer.visitMethod(Opcodes.ACC_STATIC, "f", "(I)I", null, null);
        method.visitCode();
        int a = shiftedSlots ? 3 : 1, b = shiftedSlots ? 5 : 2;
        if (ldcConstants) method.visitLdcInsn(5); else method.visitInsn(Opcodes.ICONST_5);
        method.visitVarInsn(Opcodes.ISTORE, a);
        method.visitVarInsn(Opcodes.ILOAD, 0);
        if (ldcConstants) method.visitLdcInsn(200); else method.visitIntInsn(Opcodes.SIPUSH, 200);
        method.visitInsn(Opcodes.IADD);
        method.visitVarInsn(Opcodes.ISTORE, b);
        var line = new Label(); method.visitLabel(line); method.visitLineNumber(shiftedSlots ? 99 : 3, line);
        method.visitVarInsn(Opcodes.ILOAD, a);
        method.visitVarInsn(Opcodes.ILOAD, b);
        method.visitInsn(Opcodes.IMUL);
        method.visitLdcInsn(70000);
        method.visitInsn(Opcodes.IADD);
        method.visitInsn(Opcodes.IRETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    /** static int f() { x = 1; y = 2; return x + y; } with x and y stored in the given slots. */
    static byte[] locals(int x, int y) {
        var writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "G", null, "java/lang/Object", null);
        var method = writer.visitMethod(Opcodes.ACC_STATIC, "f", "()I", null, null);
        method.visitCode();
        method.visitInsn(Opcodes.ICONST_1); method.visitVarInsn(Opcodes.ISTORE, x);
        method.visitInsn(Opcodes.ICONST_2); method.visitVarInsn(Opcodes.ISTORE, y);
        method.visitVarInsn(Opcodes.ILOAD, x); method.visitVarInsn(Opcodes.ILOAD, y);
        method.visitInsn(Opcodes.IADD); method.visitInsn(Opcodes.IRETURN);
        method.visitMaxs(0, 0); method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    static byte[] floating(double value) {
        var writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "G", null, "java/lang/Object", null);
        var method = writer.visitMethod(Opcodes.ACC_STATIC, "f", "()D", null, null);
        method.visitCode();
        if (value == 0.0 && Double.doubleToRawLongBits(value) == 0) method.visitInsn(Opcodes.DCONST_0); else method.visitLdcInsn(value);
        method.visitInsn(Opcodes.DRETURN);
        method.visitMaxs(0, 0); method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    // Phase 2BH: javac's variations and decompilers' rewritings that keep the JVM semantics.

    static Map<String,Outcome> outcomes(List<ClassRoundTrip> results) {
        var map = new TreeMap<String,Outcome>();
        for (var type : results) for (var method : type.methods()) map.put(type.className() + "." + method.name() + method.descriptor(), method.outcome());
        return map;
    }

    @Test void anEnumsValuesMethodIsTheArrayItBuilds() throws Exception {
        // javac 15 and later build an enum's $VALUES in a private static synthetic $values(); older javac inline it.
        byte[] newer = compile("package demo; public enum Fixture { A, B, C; }", "enum-values").get("demo/Fixture");
        assertEquals(Outcome.EQUIVALENT_AFTER_NORMALIZATION, outcome(inlinedValues(newer, false), newer, "<clinit>()V"));
        // The array built in another order is another class initializer.
        assertEquals(Outcome.DIFFERENT, outcome(inlinedValues(newer, true), newer, "<clinit>()V"));
        // A $values() that is not synthetic is an ordinary call, never read as its body.
        assertEquals(Outcome.DIFFERENT, outcome(inlinedValues(newer, false), declaredValues(newer), "<clinit>()V"));
    }

    /** The enum with $values() inlined into its class initializer and removed, as javac before 15 wrote it; optionally A and B swapped. */
    static byte[] inlinedValues(byte[] bytes, boolean swap) {
        var node = new org.objectweb.asm.tree.ClassNode();
        new ClassReader(bytes).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        var values = node.methods.stream().filter(method -> method.name.equals("$values")).findFirst().orElseThrow();
        var initializer = node.methods.stream().filter(method -> method.name.equals("<clinit>")).findFirst().orElseThrow();
        var body = new org.objectweb.asm.tree.InsnList(); var fields = new ArrayList<org.objectweb.asm.tree.FieldInsnNode>();
        for (var insn : values.instructions) {
            if (insn.getOpcode() < 0 || insn.getOpcode() == Opcodes.ARETURN) continue;
            var copy = insn.clone(Map.of()); body.add(copy);
            if (copy instanceof org.objectweb.asm.tree.FieldInsnNode field) fields.add(field);
        }
        if (swap) { String first = fields.get(0).name; fields.get(0).name = fields.get(1).name; fields.get(1).name = first; }
        var call = Arrays.stream(initializer.instructions.toArray())
            .filter(insn -> insn instanceof org.objectweb.asm.tree.MethodInsnNode method && method.name.equals("$values")).findFirst().orElseThrow();
        initializer.instructions.insertBefore(call, body); initializer.instructions.remove(call);
        node.methods.remove(values);
        var writer = new ClassWriter(ClassWriter.COMPUTE_MAXS); node.accept(writer);
        return writer.toByteArray();
    }

    /** The enum with its $values() no longer marked synthetic. */
    static byte[] declaredValues(byte[] bytes) {
        var node = new org.objectweb.asm.tree.ClassNode();
        new ClassReader(bytes).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        for (var method : node.methods) if (method.name.equals("$values")) method.access &= ~Opcodes.ACC_SYNTHETIC;
        var writer = new ClassWriter(ClassWriter.COMPUTE_MAXS); node.accept(writer);
        return writer.toByteArray();
    }

    @Test void accessorsNumberedInAnotherOrderAreTheSameAccess() throws Exception {
        // Before Java 11, javac reaches an outer class's private fields through access$NNN, numbered in the order it
        // meets the accesses: with g first, b's accessor is access$000 instead of access$100.
        String source = "package demo; public class Fixture { private int a = 1; private int b = 2;"
            + " class In { int f() { return a + b; } int g() { return b; } } }";
        String methods = "int f() { return a + b; } int g() { return b; }";
        var original = compile(source, "accessors", "8");
        var reordered = compile(source.replace(methods, "int g() { return b; } int f() { return a + b; }"), "accessors-reordered", "8");
        var results = MethodRoundTrip.compare(original, reordered);
        var outcomes = outcomes(results);
        assertEquals(Outcome.EQUIVALENT_AFTER_NORMALIZATION, outcomes.get("demo/Fixture$In.f()I"), outcomes.toString());
        assertEquals(Outcome.EQUIVALENT_AFTER_NORMALIZATION, outcomes.get("demo/Fixture$In.g()I"));
        assertEquals("synthetic accessor numbered differently", results.stream().filter(type -> type.className().equals("demo/Fixture"))
            .flatMap(type -> type.methods().stream()).filter(method -> method.name().equals("access$000")).findFirst().orElseThrow().reason());
        // g reading a instead of b calls an accessor that forwards to another field.
        var changed = compile(source.replace(methods, "int g() { return a; } int f() { return a + b; }"), "accessors-changed", "8");
        assertEquals(Outcome.DIFFERENT, outcomes(MethodRoundTrip.compare(original, changed)).get("demo/Fixture$In.g()I"));
    }

    @Test void aDupOfAJustLoadedLocalIsThatLoadAgain() throws Exception {
        // javac writes this.n += 1 as aload_0; dup; getfield and this.n = this.n + 1 as aload_0; aload_0; getfield.
        String source = "package demo; public class Fixture { int n; int m; void f() { this.n += 1; } }";
        var compound = compile(source, "dup-compound").get("demo/Fixture");
        var spelled = compile(source.replace("this.n += 1;", "this.n = this.n + 1;"), "dup-spelled").get("demo/Fixture");
        var other = compile(source.replace("this.n += 1;", "this.n = this.m + 1;"), "dup-other").get("demo/Fixture");
        assertEquals(Outcome.EQUIVALENT_AFTER_NORMALIZATION, outcome(compound, spelled, "f()V"));
        assertEquals(Outcome.DIFFERENT, outcome(compound, other, "f()V"));
    }

    @Test void aTestOfAZeroOrOneValueWhoseBranchesReturnItReturnsIt() throws Exception {
        String base = "package demo; public class Fixture {"
            + " static boolean f(String s) { if (!s.isEmpty()) return false; return true; }"
            + " static boolean g(Object o) { if (o instanceof String) return true; return false; }"
            + " static int h(String s) { if (s.length() != 0) return 1; return 0; } }";
        var original = compile(base, "boolean").get("demo/Fixture");
        var direct = compile(base.replace("if (!s.isEmpty()) return false; return true;", "return s.isEmpty();")
            .replace("if (o instanceof String) return true; return false;", "return o instanceof String;")
            .replace("if (s.length() != 0) return 1; return 0;", "return s.length();"), "boolean-direct").get("demo/Fixture");
        var negated = compile(base.replace("if (!s.isEmpty()) return false; return true;", "return !s.isEmpty();"), "boolean-negated").get("demo/Fixture");
        assertEquals(Outcome.EQUIVALENT_AFTER_NORMALIZATION, outcome(original, direct, "f(Ljava/lang/String;)Z"));
        assertEquals(Outcome.EQUIVALENT_AFTER_NORMALIZATION, outcome(original, direct, "g(Ljava/lang/Object;)Z"));
        // length() can be any int, so returning it is not returning the test's 0 or 1.
        assertEquals(Outcome.DIFFERENT, outcome(original, direct, "h(Ljava/lang/String;)I"));
        assertEquals(Outcome.DIFFERENT, outcome(original, negated, "f(Ljava/lang/String;)Z"));
    }

    @Test void aJumpOverAGotoIsTheNegatedJumpAndASharedTailEqualsItsCopies() {
        assertEquals(Outcome.EQUIVALENT_AFTER_NORMALIZATION, single(branches(0), branches(1)));
        assertEquals(Outcome.DIFFERENT, single(branches(0), branches(2)));
        assertEquals(Outcome.EQUIVALENT_AFTER_NORMALIZATION, single(tails(false, 1), tails(true, 1)));
        assertEquals(Outcome.DIFFERENT, single(tails(false, 1), tails(true, 2)));
    }

    /** static void f(boolean c) { if (c) a(); b(); } as javac writes it (0), with the test over a goto (1), or with the opposite test over a goto (2). */
    static byte[] branches(int shape) {
        var writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "G", null, "java/lang/Object", null);
        var method = writer.visitMethod(Opcodes.ACC_STATIC, "f", "(Z)V", null, null);
        method.visitCode();
        var skip = new Label(); var call = new Label();
        method.visitVarInsn(Opcodes.ILOAD, 0);
        if (shape == 0) method.visitJumpInsn(Opcodes.IFEQ, skip);
        else { method.visitJumpInsn(shape == 1 ? Opcodes.IFNE : Opcodes.IFEQ, call); method.visitJumpInsn(Opcodes.GOTO, skip); method.visitLabel(call); }
        method.visitMethodInsn(Opcodes.INVOKESTATIC, "G", "a", "()V", false);
        method.visitLabel(skip);
        method.visitMethodInsn(Opcodes.INVOKESTATIC, "G", "b", "()V", false);
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(0, 0); method.visitEnd(); writer.visitEnd();
        return writer.toByteArray();
    }

    /** static int f(int x) { if (x > 0) { a(); return 1; } b(); return k; } with each return written out, or reaching one shared tail. */
    static byte[] tails(boolean shared, int k) {
        var writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "G", null, "java/lang/Object", null);
        var method = writer.visitMethod(Opcodes.ACC_STATIC, "f", "(I)I", null, null);
        method.visitCode();
        var other = new Label(); var tail = new Label();
        method.visitVarInsn(Opcodes.ILOAD, 0); method.visitJumpInsn(Opcodes.IFLE, other);
        method.visitMethodInsn(Opcodes.INVOKESTATIC, "G", "a", "()V", false);
        if (shared) method.visitJumpInsn(Opcodes.GOTO, tail); else { method.visitInsn(Opcodes.ICONST_1); method.visitInsn(Opcodes.IRETURN); }
        method.visitLabel(other);
        method.visitMethodInsn(Opcodes.INVOKESTATIC, "G", "b", "()V", false);
        if (!shared || k != 1) { method.visitInsn(Opcodes.ICONST_0 + k); method.visitInsn(Opcodes.IRETURN); }
        if (shared) { method.visitLabel(tail); method.visitInsn(Opcodes.ICONST_1); method.visitInsn(Opcodes.IRETURN); }
        method.visitMaxs(0, 0); method.visitEnd(); writer.visitEnd();
        return writer.toByteArray();
    }

    @Test void bodiesWithHandlersAreComparedUnderTheirHandlers() throws Exception {
        String base = "package demo; public class Fixture { static void a() {}"
            + " static int t(int x) { try { a(); return x; } catch (RuntimeException e) { return 0; } } }";
        String body = "try { a(); return x; } catch (RuntimeException e) { return 0; }";
        var original = compile(base, "handlers").get("demo/Fixture");
        var moved = compile(base.replace(body, "try { a(); } catch (RuntimeException e) { return 0; } return x;"), "handlers-moved").get("demo/Fixture");
        var otherType = compile(base.replace("RuntimeException", "IllegalStateException"), "handlers-type").get("demo/Fixture");
        var outside = compile(base.replace(body, "a(); try { x = x + 0; } catch (RuntimeException e) { return 0; } return x;"), "handlers-outside").get("demo/Fixture");
        var otherHandler = compile(base.replace("return 0;", "return 1;"), "handlers-handler").get("demo/Fixture");
        // Only the call can throw, and it is caught the same way in both.
        assertEquals(Outcome.EQUIVALENT_AFTER_NORMALIZATION, outcome(original, moved, "t(I)I"));
        assertEquals(Outcome.DIFFERENT, outcome(original, otherType, "t(I)I"));
        assertEquals(Outcome.DIFFERENT, outcome(original, outside, "t(I)I"));
        assertEquals(Outcome.DIFFERENT, outcome(original, otherHandler, "t(I)I"));
    }

    @Test void aSlotReusedByVariablesOfDisjointScopesHoldsTwoVariables() throws Exception {
        String base = "package demo; public class Fixture { static int a() { return 1; } static int b() { return 2; } static void use(int v) {}"
            + " static void f() { { int i = a(); use(i); use(i); } { int j = b(); use(j); use(j); } } }";
        String body = "{ int i = a(); use(i); use(i); } { int j = b(); use(j); use(j); }";
        var shared = compile(base, "webs-shared").get("demo/Fixture"); // javac gives j the slot i had
        var separate = compile(base.replace(body, "int i = a(); use(i); use(i); int j = b(); use(j); use(j);"), "webs-separate").get("demo/Fixture");
        var crossed = compile(base.replace(body, "int i = a(); use(i); use(i); int j = b(); use(j); use(i);"), "webs-crossed").get("demo/Fixture");
        assertEquals(Outcome.EQUIVALENT_AFTER_NORMALIZATION, outcome(shared, separate, "f()V"));
        assertEquals(Outcome.DIFFERENT, outcome(shared, crossed, "f()V"));
    }

    @Test void aLocalStoredAndAtOnceLoadedTwiceIsADupAndAStoreNeverLoadedIsDead() throws Exception {
        assertEquals(Outcome.EQUIVALENT_AFTER_NORMALIZATION, single(twice(0), twice(1)));
        assertEquals(Outcome.DIFFERENT, single(twice(0), twice(2)));
        String base = "package demo; public class Fixture { static int f(int x) { int y = 0; y = x + 1; return y; }"
            + " static int g(String s) { String t = s.trim(); return 1; } }";
        var original = compile(base, "dead").get("demo/Fixture");
        var direct = compile(base.replace("int y = 0; y = x + 1; return y;", "return x + 1;")
            .replace("String t = s.trim(); return 1;", "s.trim(); return 1;"), "dead-direct").get("demo/Fixture");
        var read = compile(base.replace("int y = 0; y = x + 1; return y;", "int y = 0; if (x > 0) y = x + 1; return y;"), "dead-read").get("demo/Fixture");
        var dropped = compile(base.replace("String t = s.trim(); return 1;", "return 1;"), "dead-dropped").get("demo/Fixture");
        // y = 0 is overwritten before it is read; t is never read, but trim() still runs.
        assertEquals(Outcome.EQUIVALENT_AFTER_NORMALIZATION, outcome(original, direct, "f(I)I"));
        assertEquals(Outcome.EQUIVALENT_AFTER_NORMALIZATION, outcome(original, direct, "g(Ljava/lang/String;)I"));
        assertEquals(Outcome.DIFFERENT, outcome(original, read, "f(I)I"));
        assertEquals(Outcome.DIFFERENT, outcome(original, dropped, "g(Ljava/lang/String;)I"));
    }

    /** static void f(Object p) { pair(v(), v()'s value again) } with a dup (0), a local stored and loaded twice (1), or that local and then p (2). */
    static byte[] twice(int shape) {
        var writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "G", null, "java/lang/Object", null);
        var method = writer.visitMethod(Opcodes.ACC_STATIC, "f", "(Ljava/lang/Object;)V", null, null);
        method.visitCode();
        method.visitMethodInsn(Opcodes.INVOKESTATIC, "G", "v", "()Ljava/lang/Object;", false);
        if (shape == 0) method.visitInsn(Opcodes.DUP);
        else { method.visitVarInsn(Opcodes.ASTORE, 1); method.visitVarInsn(Opcodes.ALOAD, 1); method.visitVarInsn(Opcodes.ALOAD, shape == 1 ? 1 : 0); }
        method.visitMethodInsn(Opcodes.INVOKESTATIC, "G", "pair", "(Ljava/lang/Object;Ljava/lang/Object;)V", false);
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(0, 0); method.visitEnd(); writer.visitEnd();
        return writer.toByteArray();
    }

    @Test void capturedVariableFieldsMatchWhateverTheVariablesAreCalled() throws Exception {
        // An anonymous class keeps each captured local in a synthetic field named after it (val$name).
        String base = "package demo; public class Fixture { static Runnable r(String name, String other) {"
            + " return new Runnable() { public void run() { System.out.println(name); System.out.println(other); } }; } }";
        var original = compile(base, "captured");
        var renamed = compile(base.replace("name", "label").replace("other", "second"), "captured-renamed");
        var outcomes = outcomes(MethodRoundTrip.compare(original, renamed));
        assertEquals(Outcome.EQUIVALENT_AFTER_NORMALIZATION, outcomes.get("demo/Fixture$1.run()V"), outcomes.toString());
        assertEquals(Outcome.EQUIVALENT_AFTER_NORMALIZATION, outcomes.get("demo/Fixture$1.<init>(Ljava/lang/String;Ljava/lang/String;)V"));
        // Printing one captured value twice captures one variable: nothing to pair the other field with.
        var one = compile(base.replace("System.out.println(other);", "System.out.println(name);"), "captured-one");
        assertEquals(Outcome.DIFFERENT, outcomes(MethodRoundTrip.compare(original, one)).get("demo/Fixture$1.run()V"));
    }
}
