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

/** Compiler idioms undone in decompiler-shaped files (Phase 2DH). Sources and classes are compiled, never run. */
class IdiomResugarTest {
    @TempDir Path dir;
    record Original(Path jar, List<ClassInfo> infos) {}

    /** javac 21's classes (release 8) for the original sources, as the job's normalized class JAR. */
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
    IdiomResugar.Proposal propose(Original original, Path mix) throws IOException {
        return new IdiomResugar().propose(mix, dir.resolve("idioms"), original.infos(), Duration.ofSeconds(60), List.of(), original.jar());
    }
    /** The mix with the rewritten files compiles with every signature, and no body is DIFFERENT. */
    void recompilesToTheOriginal(Original original, Path mix, IdiomResugar.Proposal proposal) throws IOException {
        var files = new TreeMap<String,String>();
        try (var paths = Files.walk(mix)) {
            for (var path : paths.filter(Files::isRegularFile).toList()) {
                String file = mix.relativize(path).toString().replace('\\', '/');
                files.put(file, Files.readString(proposal.files().getOrDefault(file, path)));
            }
        }
        var verified = new CompilerVerifier().verify(write("merged", files), dir.resolve("verify"), original.infos(), Duration.ofSeconds(60),
            List.of(), original.jar());
        assertTrue(verified.complete(), verified.toString());
        assertEquals(0, verified.roundTripDifferent(), verified.toString());
    }

    @Test void aSwitchOnTheOrdinalOfAnEnumBecomesASwitchOnTheEnumWithItsConstantsFromTheBytecode() throws Exception {
        // The first test JAR (Phase 2DH): javac 21 compiles a switch on an enum of the same file to a switch on ordinal(),
        // and CFR printed it so. The constants come from the enum's class initializer, not from the case values.
        var original = original(Map.of("p/Inv.java", "package p; public class Inv { public enum Kind { TOOL, FOOD, BOOK }\n"
            + " public static String describe(Kind k) { switch (k) { case TOOL: return \"util\"; case FOOD: return \"fresh\"; default: return \"read\"; } } }\n"));
        Path mix = write("mix", Map.of("p/Inv.java", "package p;\n\npublic class Inv {\n"
            + "    public static String describe(Kind kind) {\n        switch (kind.ordinal()) {\n            case 0: {\n                return \"util\";\n"
            + "            }\n            case 1: {\n                return \"fresh\";\n            }\n        }\n        return \"read\";\n    }\n\n"
            + "    public static enum Kind {\n        TOOL,\n        FOOD,\n        BOOK;\n\n    }\n}\n"));
        var proposal = propose(original, mix);
        assertEquals(List.of(new IdiomResugar.Rewrite("p/Inv.java", 5, "enum-switch", "switch (kind.ordinal())", "switch (kind) case TOOL, FOOD")),
            proposal.rewrites(), proposal.toString());
        String text = Files.readString(proposal.files().get("p/Inv.java"));
        assertTrue(text.contains("switch (kind) {") && text.contains("case TOOL: {") && text.contains("case FOOD: {"), text);
        recompilesToTheOriginal(original, mix, proposal);
    }

    @Test void aSwitchOnASyntheticSwitchMapIsReadFromTheHoldersInitializerAndMakesTheFileCompile() throws Exception {
        // javac keeps the switch map of an enum of another class in the synthetic Use$1; javac cannot see that synthetic
        // field in a class file, so the printed file does not compile alone. Case 1 is BLUE and case 2 GREEN here: the
        // values follow the order of the original case labels, not the ordinals.
        var original = original(Map.of("p/Color.java", "package p; public enum Color { RED, GREEN, BLUE }\n",
            "p/Use.java", "package p; public class Use { static int f(Color c) { switch (c) { case BLUE: return 1; case GREEN: return 2; default: return 0; } } }\n"));
        Path mix = write("mix", Map.of("p/Color.java", "package p;\n\npublic enum Color {\n    RED,\n    GREEN,\n    BLUE;\n\n}\n",
            "p/Use.java", "package p;\n\npublic class Use {\n    static int f(Color color) {\n"
            + "        switch (Use$1.$SwitchMap$p$Color[color.ordinal()]) {\n            case 1: {\n                return 1;\n            }\n"
            + "            case 2: {\n                return 2;\n            }\n        }\n        return 0;\n    }\n}\n"));
        var proposal = propose(original, mix);
        assertEquals(List.of(new IdiomResugar.Rewrite("p/Use.java", 5, "enum-switch", "switch (Use$1.$SwitchMap$p$Color[color.ordinal()])",
            "switch (color) case BLUE, GREEN")), proposal.rewrites(), proposal.toString());
        recompilesToTheOriginal(original, mix, proposal);
    }

    @Test void aSwitchMapHolderPrintedAsAFileOfItsOwnIsLeftAlone() throws Exception {
        // Like an obfuscated jar without InnerClasses: the holder Use$1 is a source file of the mix, so Use compiles in the mix but
        // not alone, and a rewritten switch would make javac generate a second Use$1.
        var original = original(Map.of("p/Color.java", "package p; public enum Color { RED, GREEN, BLUE }\n",
            "p/Use.java", "package p; public class Use { static int f(Color c) { switch (c) { case BLUE: return 1; default: return 0; } } }\n"));
        Path mix = write("mix", Map.of("p/Color.java", "package p;\npublic enum Color { RED, GREEN, BLUE }\n",
            "p/Use$1.java", "package p;\nclass Use$1 {\n    static final int[] $SwitchMap$p$Color = new int[Color.values().length];\n}\n",
            "p/Use.java", "package p;\npublic class Use {\n    static int f(Color c) {\n"
            + "        switch (Use$1.$SwitchMap$p$Color[c.ordinal()]) {\n            case 1: return 1;\n            default: return 0;\n        }\n    }\n}\n"));
        var proposal = propose(original, mix);
        assertEquals(List.of(), proposal.rewrites(), proposal.toString());
        assertEquals(List.of(new IdiomResugar.Refusal("p/Use.java", 4, "enum-switch", "holder-declared-in-another-file")), proposal.refusals());
    }

    @Test void syntheticAccessorCallsBecomeTheMemberAccessTheirBytecodePerforms() throws Exception {
        var original = original(Map.of("p/Outer.java", "package p; public class Outer { private int count; private static String label = \"x\";\n"
            + " private int bump(int by) { return count + by; }\n"
            + " class Inner { int read() { return count; } void write(int v) { count = v; } int both(int v) { return bump(v) + (count = v); }\n"
            + "  String name() { return label; } int post() { return count++; } } }\n"));
        // CFR-shaped: the accessors javac generated are called by name. access$008 (count++) does more than one access.
        Path mix = write("mix", Map.of("p/Outer.java", "package p;\n\npublic class Outer {\n    private int count;\n    private static String label = \"x\";\n\n"
            + "    private int bump(int by) {\n        return this.count + by;\n    }\n\n    class Inner {\n"
            + "        int read() {\n            return Outer.access$000(Outer.this);\n        }\n\n"
            + "        void write(int v) {\n            Outer.access$002(Outer.this, v);\n        }\n\n"
            + "        int both(int v) {\n            return Outer.access$100(Outer.this, v) + Outer.access$002(Outer.this, v);\n        }\n\n"
            + "        String name() {\n            return Outer.access$200();\n        }\n\n"
            + "        int post() {\n            return Outer.access$008(Outer.this);\n        }\n    }\n}\n"));
        var proposal = propose(original, mix);
        // The file does not compile alone (javac does not see the synthetic accessors), so a rewrite is kept when it adds
        // no error; access$008 (count++) does more than one access and stays.
        assertEquals(5, proposal.rewrites().size(), proposal.toString());
        assertTrue(Files.readString(proposal.files().get("p/Outer.java")).contains("return Outer.access$008(Outer.this);"));
        assertTrue(proposal.refusals().contains(new IdiomResugar.Refusal("p/Outer.java", 29, "accessor-call", "accessor-not-a-plain-access")),
            proposal.toString());
        // Without the increment, every call is a plain access and the file compiles once they are all rewritten.
        Path plain = write("plain", Map.of("p/Outer.java", Files.readString(mix.resolve("p/Outer.java"))
            .replace("return Outer.access$008(Outer.this);", "return Outer.this.count++;")));
        proposal = new IdiomResugar().propose(plain, dir.resolve("idioms-plain"), original.infos(), Duration.ofSeconds(60), List.of(), original.jar());
        assertEquals(List.of("Outer.this.count", "Outer.this.count = v", "Outer.this.bump(v)", "(Outer.this.count = v)", "Outer.label"),
            proposal.rewrites().stream().map(IdiomResugar.Rewrite::after).toList(), proposal.toString());
        String text = Files.readString(proposal.files().get("p/Outer.java"));
        assertTrue(text.contains("return Outer.this.bump(v) + (Outer.this.count = v);") && !text.contains("access$"), text);
        recompilesToTheOriginal(original, plain, proposal);
    }

    @Test void assertionsDisabledTestsBecomeAssertStatementsAndTheSyntheticFieldGoes() throws Exception {
        var original = original(Map.of("p/Checked.java", "package p; public class Checked {\n"
            + " static int half(int x) { assert x % 2 == 0 : \"odd\"; return x / 2; }\n"
            + " static String name(String s) { assert s != null; return s; } }\n"));
        // JADX-shaped: the synthetic field, its initialization in a static block and the tests are printed.
        Path mix = write("mix", Map.of("p/Checked.java", "package p;\n\n/* loaded from: classes.jar */\npublic class Checked {\n"
            + "    static final /* synthetic */ boolean $assertionsDisabled;\n\n    static {\n"
            + "        $assertionsDisabled = !Checked.class.desiredAssertionStatus();\n    }\n\n"
            + "    static int half(int x) {\n        if (!$assertionsDisabled && x % 2 != 0) {\n            throw new AssertionError(\"odd\");\n        }\n"
            + "        return x / 2;\n    }\n\n    static String name(String s) {\n        if (!$assertionsDisabled && s == null) {\n"
            + "            throw new AssertionError();\n        }\n        return s;\n    }\n}\n"));
        var proposal = propose(original, mix);
        assertEquals(List.of("assert x % 2 == 0 : \"odd\";"), proposal.rewrites().stream().map(IdiomResugar.Rewrite::after).toList(), proposal.toString());
        String text = Files.readString(proposal.files().get("p/Checked.java"));
        assertTrue(text.contains("assert x % 2 == 0 : \"odd\";") && text.contains("assert s != null;"), text);
        assertFalse(text.contains("$assertionsDisabled") || text.contains("static {"), text);
        recompilesToTheOriginal(original, mix, proposal);
    }

    @Test void leavesAlonePlainIntSwitchesEnumsItCannotReadAndFieldsThatAreNotSynthetic() throws Exception {
        var original = original(Map.of("p/Plain.java", "package p; public class Plain {\n"
            + " static boolean $assertionsDisabled = false;\n"
            + " static int f(int n) { switch (n) { case 0: return 1; default: return 2; } }\n"
            + " static int g(java.util.concurrent.TimeUnit unit) { switch (unit.ordinal()) { case 0: return 1; default: return 2; } }\n"
            + " static void h(int x) { if (!$assertionsDisabled && x < 0) throw new AssertionError(); } }\n"));
        Path mix = write("mix", Map.of("p/Plain.java", "package p;\n\npublic class Plain {\n    static boolean $assertionsDisabled = false;\n\n"
            + "    static int f(int n) {\n        switch (n) {\n            case 0: {\n                return 1;\n            }\n        }\n        return 2;\n    }\n\n"
            + "    static int g(java.util.concurrent.TimeUnit unit) {\n        switch (unit.ordinal()) {\n            case 0: {\n                return 1;\n            }\n        }\n        return 2;\n    }\n\n"
            + "    static void h(int x) {\n        if (!$assertionsDisabled && x < 0) {\n            throw new AssertionError();\n        }\n    }\n}\n"));
        var proposal = propose(original, mix);
        assertEquals(List.of(), proposal.rewrites(), proposal.toString());
        assertEquals(Set.of("enum-switch:enum-ordinals-unknown", "assert:field-not-synthetic-in-original"),
            new TreeSet<>(proposal.refusals().stream().map(refusal -> refusal.rule() + ":" + refusal.reason()).toList()), proposal.toString());
    }

    @Test void aKotlinWhenMappingsHolderTheSourceDoesNotDeclareIsFoundAmongTheEnclosingClassesMembers() throws Exception {
        // kotlinc keeps a `when` on an enum in Outer$WhenMappings; decompilers print WhenMappings.$EnumSwitchMapping$0
        // without declaring it, so javac cannot resolve the holder. Its values are read from the original class.
        var original = original(Map.of("p/T.java", "package p; public class T { enum P { A, B, C }\n"
            + " static final class WhenMappings { static final int[] $EnumSwitchMapping$0;\n"
            + "  static { int[] map = new int[P.values().length]; map[P.B.ordinal()] = 1; map[P.A.ordinal()] = 2; $EnumSwitchMapping$0 = map; } }\n"
            + " static int f(P p) { switch (WhenMappings.$EnumSwitchMapping$0[p.ordinal()]) { case 1: return 10; case 2: return 20; default: return 0; } } }\n"));
        Path mix = write("mix", Map.of("p/T.java", "package p;\n\npublic final class T {\n    static int f(P p) {\n"
            + "        switch (WhenMappings.$EnumSwitchMapping$0[p.ordinal()]) {\n            case 1: return 10;\n            case 2: return 20;\n"
            + "            default: return 0;\n        }\n    }\n\n    enum P { A, B, C }\n}\n"));
        var proposal = propose(original, mix);
        assertEquals(List.of(new IdiomResugar.Rewrite("p/T.java", 5, "enum-switch", "switch (WhenMappings.$EnumSwitchMapping$0[p.ordinal()])",
            "switch (p) case B, A")), proposal.rewrites(), proposal.toString());
        assertTrue(Files.readString(proposal.files().get("p/T.java")).contains("case B: return 10;"), proposal.toString());
    }

    @Test void anAccessorThatStoresAndLoadsTheValueAgainAsD8WritesItIsAnAssignment() throws Exception {
        // d8 returns the stored value by loading the parameter again instead of dup_x1. The classes are written with ASM.
        Path classes = Files.createDirectories(dir.resolve("d8-classes").resolve("p"));
        var writer = new org.objectweb.asm.ClassWriter(org.objectweb.asm.ClassWriter.COMPUTE_FRAMES | org.objectweb.asm.ClassWriter.COMPUTE_MAXS);
        writer.visit(org.objectweb.asm.Opcodes.V1_8, org.objectweb.asm.Opcodes.ACC_PUBLIC | org.objectweb.asm.Opcodes.ACC_SUPER, "p/O", null,
            "java/lang/Object", null);
        writer.visitField(org.objectweb.asm.Opcodes.ACC_PRIVATE, "v", "I", null, null).visitEnd();
        var init = writer.visitMethod(org.objectweb.asm.Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode(); init.visitVarInsn(org.objectweb.asm.Opcodes.ALOAD, 0);
        init.visitMethodInsn(org.objectweb.asm.Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(org.objectweb.asm.Opcodes.RETURN); init.visitMaxs(0, 0); init.visitEnd();
        var accessor = writer.visitMethod(org.objectweb.asm.Opcodes.ACC_STATIC | org.objectweb.asm.Opcodes.ACC_SYNTHETIC, "access$002", "(Lp/O;I)I", null, null);
        accessor.visitCode(); accessor.visitVarInsn(org.objectweb.asm.Opcodes.ALOAD, 0); accessor.visitVarInsn(org.objectweb.asm.Opcodes.ILOAD, 1);
        accessor.visitFieldInsn(org.objectweb.asm.Opcodes.PUTFIELD, "p/O", "v", "I"); accessor.visitVarInsn(org.objectweb.asm.Opcodes.ILOAD, 1);
        accessor.visitInsn(org.objectweb.asm.Opcodes.IRETURN); accessor.visitMaxs(0, 0); accessor.visitEnd();
        var f = writer.visitMethod(org.objectweb.asm.Opcodes.ACC_STATIC, "f", "(Lp/O;)I", null, null);
        f.visitCode(); f.visitVarInsn(org.objectweb.asm.Opcodes.ALOAD, 0); f.visitInsn(org.objectweb.asm.Opcodes.ICONST_5);
        f.visitMethodInsn(org.objectweb.asm.Opcodes.INVOKESTATIC, "p/O", "access$002", "(Lp/O;I)I", false);
        f.visitInsn(org.objectweb.asm.Opcodes.IRETURN); f.visitMaxs(0, 0); f.visitEnd();
        writer.visitEnd();
        byte[] bytes = writer.toByteArray();
        Path jar = dir.resolve("d8.jar");
        try (var zip = new ZipOutputStream(Files.newOutputStream(jar))) { zip.putNextEntry(new ZipEntry("p/O.class")); zip.write(bytes); zip.closeEntry(); }
        var original = new Original(jar, List.of(new ClassInspector().inspect(bytes)));
        Path mix = write("mix", Map.of("p/O.java", "package p;\n\npublic class O {\n    private int v;\n\n"
            + "    static int f(O o) {\n        return O.access$002(o, 5);\n    }\n}\n"));
        var proposal = propose(original, mix);
        assertEquals(List.of("(o.v = 5)"), proposal.rewrites().stream().map(IdiomResugar.Rewrite::after).toList(), proposal.toString());
    }
}
