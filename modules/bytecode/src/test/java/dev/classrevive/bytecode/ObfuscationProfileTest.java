package dev.classrevive.bytecode;

import dev.classrevive.bytecode.ObfuscationProfile.Technique;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;
import java.io.*;
import java.util.*;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/** Phase 2BZ: one synthetic class set per technique, generated with ASM; nothing is loaded or run. */
class ObfuscationProfileTest {
    static final String BOOTSTRAP = "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;";

    /** A class as javac writes it: SourceFile, line numbers, local variables. */
    static byte[] clean(String name, Consumer<ClassWriter> members) {
        var writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(V1_8, ACC_PUBLIC | ACC_SUPER, name, null, "java/lang/Object", null);
        writer.visitSource(name.substring(name.lastIndexOf('/') + 1) + ".java", null);
        var init = writer.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode(); var start = new Label(); init.visitLabel(start); init.visitLineNumber(1, start);
        init.visitVarInsn(ALOAD, 0); init.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false); init.visitInsn(RETURN);
        var end = new Label(); init.visitLabel(end); init.visitLocalVariable("this", "L" + name + ";", null, start, end, 0);
        init.visitMaxs(0, 0); init.visitEnd();
        members.accept(writer);
        writer.visitEnd();
        return writer.toByteArray();
    }
    /** A class without SourceFile or debug tables. */
    static byte[] bare(String name, Consumer<ClassWriter> members) {
        var writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(V1_8, ACC_PUBLIC | ACC_SUPER, name, null, "java/lang/Object", null);
        members.accept(writer);
        writer.visitEnd();
        return writer.toByteArray();
    }
    static ObfuscationProfile profile(byte[]... classes) {
        var builder = new ObfuscationProfile.Builder();
        for (byte[] bytes : classes) builder.addClass(bytes);
        return builder.build();
    }
    static void method(ClassWriter writer, int access, String name, String desc, Consumer<MethodVisitor> body) {
        var method = writer.visitMethod(access, name, desc, null, null);
        method.visitCode(); var line = new Label(); method.visitLabel(line); method.visitLineNumber(2, line);
        body.accept(method); method.visitMaxs(0, 0); method.visitEnd();
    }

    @Test void aCleanClassSetHasNoTechniqueAndNoObfuscator() {
        var profile = profile(clean("org/example/Calculator", w -> {
            method(w, ACC_PUBLIC | ACC_STATIC, "add", "(II)I", m -> { m.visitVarInsn(ILOAD, 0); m.visitVarInsn(ILOAD, 1); m.visitInsn(IADD); m.visitInsn(IRETURN); });
            // An ordinary lambda: LambdaMetafactory is a JDK bootstrap, not a hidden call.
            method(w, ACC_PUBLIC | ACC_STATIC, "task", "()Ljava/lang/Runnable;", m -> {
                m.visitInvokeDynamicInsn("run", "()Ljava/lang/Runnable;", new Handle(H_INVOKESTATIC, "java/lang/invoke/LambdaMetafactory", "metafactory",
                    "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;", false),
                    Type.getType("()V"), new Handle(H_INVOKESTATIC, "org/example/Calculator", "lambda$task$0", "()V", false), Type.getType("()V"));
                m.visitInsn(ARETURN); });
        }), clean("org/example/Calculator$Helper", w -> w.visitInnerClass("org/example/Calculator$Helper", "org/example/Calculator", "Helper", ACC_STATIC)),
            clean("org/example/Outer$1Local", w -> w.visitInnerClass("org/example/Outer$1Local", null, "Local", 0)));
        assertTrue(profile.techniques().isEmpty(), profile.techniques().toString());
        var result = ObfuscatorClassifier.classify(profile);
        assertEquals("NONE", result.verdict());
        assertTrue(result.summary().startsWith("none"));
    }

    static byte[] zkmLike(String name) {
        return bare(name, w -> {
            w.visitField(ACC_PRIVATE | ACC_STATIC, "a", "[Ljava/lang/String;", null, null).visitEnd();
            // <clinit>: DES cipher decrypting into a static String[].
            method(w, ACC_STATIC, "<clinit>", "()V", m -> {
                m.visitLdcInsn("DES/CBC/PKCS5Padding");
                m.visitMethodInsn(INVOKESTATIC, "javax/crypto/Cipher", "getInstance", "(Ljava/lang/String;)Ljavax/crypto/Cipher;", false);
                m.visitInsn(POP);
                m.visitInsn(ICONST_2); m.visitTypeInsn(ANEWARRAY, "java/lang/String");
                m.visitFieldInsn(PUTSTATIC, name, "a", "[Ljava/lang/String;"); m.visitInsn(RETURN); });
            // a(int, long) decryptor: XOR plus a new String.
            method(w, ACC_PRIVATE | ACC_STATIC, "a", "(IJ)Ljava/lang/String;", m -> {
                m.visitTypeInsn(NEW, "java/lang/String"); m.visitInsn(DUP); m.visitInsn(ICONST_1); m.visitIntInsn(NEWARRAY, T_CHAR);
                m.visitMethodInsn(INVOKESPECIAL, "java/lang/String", "<init>", "([C)V", false);
                m.visitVarInsn(ILOAD, 0); m.visitVarInsn(LLOAD, 1); m.visitInsn(L2I); m.visitInsn(IXOR); m.visitInsn(POP);
                m.visitInsn(ARETURN); });
            // Callers: invokedynamic through the class's own bootstrap, and the decryptor with a long key.
            method(w, ACC_PUBLIC | ACC_STATIC, "b", "(J)V", m -> {
                for (int i = 0; i < 3; i++) {
                    m.visitIntInsn(BIPUSH, i); m.visitVarInsn(LLOAD, 0); m.visitLdcInsn(0x5DEECE66DL); m.visitInsn(LXOR);
                    m.visitMethodInsn(INVOKESTATIC, name, "a", "(IJ)Ljava/lang/String;", false);
                    m.visitInvokeDynamicInsn("c", "(Ljava/lang/Object;)V", new Handle(H_INVOKESTATIC, name, "d", BOOTSTRAP, false));
                }
                m.visitInsn(RETURN); });
            method(w, ACC_PRIVATE | ACC_STATIC, "d", BOOTSTRAP, m -> { m.visitInsn(ACONST_NULL); m.visitInsn(ARETURN); });
        });
    }

    @Test void stringEncryptionInClinitAndKeyedDecryptorsAreCounted() {
        var profile = profile(zkmLike("p/A"));
        assertTrue(profile.has(Technique.STRING_ENCRYPTION), profile.techniques().toString());
        var strings = profile.techniques().get(Technique.STRING_ENCRYPTION);
        assertEquals(1, strings.classes());
        assertEquals(4, strings.occurrences()); // 3 decryptor calls + 1 <clinit>
        assertEquals(1, profile.metric("cipher.DES"));
        assertEquals(1, profile.metric("strings.decryptor(IJ)"));
        assertEquals(1, profile.metric("strings.clinit_crypto"));
        assertEquals(1, profile.metric("zkm.long_key_methods"));
        assertFalse(strings.samples().toString().contains("PKCS5"), "literal contents are never reported");
    }

    @Test void invokedynamicWithAJarOwnedBootstrapIsAHiddenCall() {
        var profile = profile(zkmLike("p/A"));
        var hidden = profile.techniques().get(Technique.HIDDEN_CALLS);
        assertEquals(3, hidden.occurrences()); assertEquals(1, hidden.classes());
        assertEquals(3, profile.metric("indy.bootstrap_in_jar"));
        assertEquals(3, profile.metric("indy.bootstrap_lookup_string_methodtype"));
    }

    @Test void zelixPatternsSuggestZelixKlassMasterWithReasons() {
        var classes = new ArrayList<byte[]>();
        for (String name : List.of("p/A", "p/a", "p/B", "p/C", "p/D")) classes.add(zkmLike(name));
        var profile = profile(classes.toArray(byte[][]::new));
        assertTrue(profile.has(Technique.SHORT_NAMES)); assertTrue(profile.has(Technique.CASE_COLLISIONS));
        var result = ObfuscatorClassifier.classify(profile);
        assertEquals("SUGGESTED", result.verdict());
        var top = result.hypotheses().getFirst();
        assertEquals("Zelix KlassMaster", top.obfuscator());
        assertEquals("HIGH", top.confidence());
        assertTrue(top.reasons().stream().anyMatch(r -> r.contains("(IJ)")), top.reasons().toString());
        assertTrue(result.hypotheses().stream().noneMatch(h -> h.obfuscator().startsWith("ProGuard")), result.hypotheses().toString());
        assertTrue(result.hypotheses().stream().noneMatch(h -> h.obfuscator().equals("Allatori")), result.hypotheses().toString());
    }

    @Test void shortNamesWithRenamedSourceFileSuggestProGuardOnly() {
        var classes = new ArrayList<byte[]>();
        for (String name : List.of("a/a/a", "a/a/b", "a/a/c", "a/b/a", "a/b/b", "a/b/c")) {
            var writer = new ClassWriter(0);
            writer.visit(V1_8, ACC_PUBLIC | ACC_SUPER, name, null, "java/lang/Object", null);
            writer.visitSource("SourceFile", null);
            writer.visitField(ACC_PRIVATE, "a", "I", null, null).visitEnd();
            writer.visitEnd(); classes.add(writer.toByteArray());
        }
        var profile = profile(classes.toArray(byte[][]::new));
        assertEquals(6, profile.techniques().get(Technique.SHORT_NAMES).classes());
        assertEquals(6, profile.metric("debug.sourcefile_renamed"));
        var result = ObfuscatorClassifier.classify(profile);
        assertEquals("ProGuard / R8 (renaming only)", result.hypotheses().getFirst().obfuscator(), result.hypotheses().toString());
        assertEquals(1, result.hypotheses().size(), result.hypotheses().toString());
    }

    @Test void caseCollisionsAndNonIdentifierNamesAreReported() {
        var profile = profile(clean("q/Item", w -> {}), clean("q/ITEM", w -> {}),
            clean("q/Bad", w -> method(w, ACC_PRIVATE | ACC_STATIC, "0abc", "()V", m -> m.visitInsn(RETURN))));
        var collisions = profile.techniques().get(Technique.CASE_COLLISIONS);
        assertEquals(1, collisions.occurrences()); assertEquals(2, collisions.classes());
        assertEquals(List.of("q/ITEM / q/Item"), collisions.samples());
        assertEquals(1, profile.techniques().get(Technique.NON_IDENTIFIER_NAMES).occurrences());
    }

    @Test void kotlinMangledNamesAreNotNonIdentifiers() {
        var profile = profile(clean("k/Value", w -> method(w, ACC_PUBLIC | ACC_STATIC, "constructor-impl", "(I)I", m -> { m.visitVarInsn(ILOAD, 0); m.visitInsn(IRETURN); })));
        assertFalse(profile.has(Technique.NON_IDENTIFIER_NAMES));
    }

    /** Phase 2DC: a class as kotlinc writes it (kotlin.Metadata). */
    static byte[] kotlin(String name, Consumer<ClassWriter> members) {
        return clean(name, w -> { w.visitAnnotation("Lkotlin/Metadata;", true).visitEnd(); members.accept(w); });
    }

    @Test void javaKeywordsAreLegalMemberNamesInKotlinClasses() {
        Consumer<ClassWriter> members = w -> {
            w.visitField(ACC_PRIVATE, "int", "I", null, null).visitEnd();
            w.visitField(ACC_PRIVATE, "default", "Ljava/lang/Object;", null, null).visitEnd();
            method(w, ACC_PUBLIC, "boolean", "(Ljava/lang/String;Z)Ljava/lang/Object;", m -> { m.visitInsn(ACONST_NULL); m.visitInsn(ARETURN); });
            method(w, ACC_PUBLIC | ACC_STATIC, "getIndices--ajY-9A$annotations", "([I)V", m -> m.visitInsn(RETURN));
        };
        var kotlin = profile(kotlin("k/Module", members));
        assertFalse(kotlin.has(Technique.NON_IDENTIFIER_NAMES), kotlin.techniques().toString());
        assertEquals("NONE", ObfuscatorClassifier.classify(kotlin).verdict());
        // The same names in a class without kotlin.Metadata are no Java identifiers (the mangled name stays excluded).
        var java = profile(clean("k/Module", members));
        assertEquals(3, java.techniques().get(Technique.NON_IDENTIFIER_NAMES).occurrences(), java.techniques().toString());
        // A keyword is legal in Kotlin, a name no compiler emits is not.
        var odd = profile(kotlin("k/Odd", w -> method(w, ACC_PUBLIC | ACC_STATIC, "0abc", "()V", m -> m.visitInsn(RETURN))));
        assertTrue(odd.has(Technique.NON_IDENTIFIER_NAMES));
    }

    /** {@code if (x != 0) push 1 else push 0; ifeq}: kotlinc's boolean materialization, the constant reaching ifeq through a merge. */
    static void materializedBoolean(MethodVisitor m) {
        var zero = new Label(); var merge = new Label(); var skip = new Label();
        m.visitVarInsn(ILOAD, 0); m.visitJumpInsn(IFEQ, zero);
        m.visitInsn(ICONST_1); m.visitJumpInsn(GOTO, merge);
        m.visitLabel(zero); m.visitInsn(ICONST_0);
        m.visitLabel(merge); m.visitJumpInsn(IFEQ, skip);
        m.visitInsn(NOP); m.visitLabel(skip);
    }

    @Test void kotlinBooleanMaterializationAndWhenExitsAreNoControlFlowObfuscation() {
        var profile = profile(kotlin("k/Flow", w -> {
            method(w, ACC_PUBLIC | ACC_STATIC, "flags", "(I)I", m -> {
                for (int i = 0; i < 6; i++) materializedBoolean(m);
                // x?.y ?: false: the null arm pops the receiver and pushes 0 into the same merge.
                var isNull = new Label(); var merge = new Label(); var skip = new Label();
                m.visitInsn(ACONST_NULL); m.visitInsn(DUP); m.visitJumpInsn(IFNULL, isNull);
                m.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Object", "hashCode", "()I", false); m.visitJumpInsn(GOTO, merge);
                m.visitLabel(isNull); m.visitInsn(POP); m.visitInsn(ICONST_0);
                m.visitLabel(merge); m.visitJumpInsn(IFEQ, skip); m.visitInsn(NOP); m.visitLabel(skip);
                m.visitInsn(ICONST_0); m.visitInsn(IRETURN); });
            // when (x) { 0 -> A; 1 -> B; ... }: every arm loads its value and jumps to one exit.
            for (String name : List.of("when1", "when2", "when3")) method(w, ACC_PUBLIC | ACC_STATIC, name, "(I)Ljava/lang/Object;", m -> {
                var exit = new Label(); var arms = new Label[16]; var other = new Label();
                for (int i = 0; i < arms.length; i++) arms[i] = new Label();
                m.visitVarInsn(ILOAD, 0); m.visitTableSwitchInsn(0, arms.length - 1, other, arms);
                for (var arm : arms) { m.visitLabel(arm); m.visitFieldInsn(GETSTATIC, "k/Flow", "A", "Ljava/lang/Object;"); m.visitJumpInsn(GOTO, exit); }
                m.visitLabel(other); m.visitInsn(ACONST_NULL);
                m.visitLabel(exit); m.visitInsn(ARETURN); });
        }));
        assertFalse(profile.has(Technique.CONTROL_FLOW), profile.metrics().toString());
        assertEquals(0, profile.metric("cf.constant_conditions"));
        assertEquals(0, profile.metric("cf.goto_heavy_methods"));
        assertEquals("NONE", ObfuscatorClassifier.classify(profile).verdict());
    }

    @Test void gotosToScatteredTargetsStillMakeAMethodGotoHeavy() {
        var profile = profile(bare("c/Jumps", w -> {
            for (String name : List.of("a", "b", "c")) method(w, ACC_PUBLIC | ACC_STATIC, name, "()V", m -> {
                var blocks = new Label[16];
                for (int i = 0; i < blocks.length; i++) blocks[i] = new Label();
                m.visitJumpInsn(GOTO, blocks[0]);
                // Block i jumps to block i+1, laid out in reverse: one goto per target, none shared.
                for (int i = blocks.length - 1; i >= 0; i--) {
                    m.visitLabel(blocks[i]); m.visitInsn(NOP);
                    if (i == blocks.length - 1) m.visitInsn(RETURN); else m.visitJumpInsn(GOTO, blocks[i + 1]);
                }
            });
        }));
        assertEquals(3, profile.metric("cf.goto_heavy_methods"), profile.metrics().toString());
        assertTrue(profile.has(Technique.CONTROL_FLOW));
    }

    @Test void strippedInnerClassesSourceFileAndLineNumbersAreReported() {
        var profile = profile(bare("s/Outer", w -> method(w, ACC_PUBLIC, "m", "()V", m -> m.visitInsn(RETURN))),
            bare("s/Outer$Inner", w -> {}));
        var stripped = profile.techniques().get(Technique.STRIPPED_ATTRIBUTES);
        assertEquals(2, stripped.classes());
        assertEquals(1, profile.metric("debug.inner_classes_stripped"));
        assertEquals(2, profile.metric("debug.no_sourcefile"));
        // A nested class that keeps its InnerClasses attribute is not stripped.
        var kept = profile(clean("s/Outer$Inner", w -> w.visitInnerClass("s/Outer$Inner", "s/Outer", "Inner", ACC_STATIC)));
        assertFalse(kept.has(Technique.STRIPPED_ATTRIBUTES));
    }

    @Test void obfuscatedNativeNamesAndBundledLibrariesSuggestANativeLayer() {
        var builder = new ObfuscationProfile.Builder();
        builder.addClass(clean("n/Guard", w -> {
            w.visitMethod(ACC_PRIVATE | ACC_STATIC | ACC_NATIVE, "0a2b4acbbad9a0c69c16064156ba48f90f765d20", "()V", null, null).visitEnd();
            method(w, ACC_STATIC, "<clinit>", "()V", m -> { m.visitLdcInsn("guard");
                m.visitMethodInsn(INVOKESTATIC, "java/lang/System", "loadLibrary", "(Ljava/lang/String;)V", false); m.visitInsn(RETURN); });
        }));
        builder.addResource("natives/guard.dll", 1234);
        var profile = builder.build();
        var layer = profile.techniques().get(Technique.NATIVE_LAYER);
        assertEquals(1, layer.classes()); assertEquals(2, layer.occurrences());
        assertTrue(layer.samples().contains("library natives/guard.dll (1234 bytes)"), layer.samples().toString());
        var top = ObfuscatorClassifier.classify(profile).hypotheses().getFirst();
        assertEquals("custom native protection layer", top.obfuscator()); assertEquals("HIGH", top.confidence());
        // Ordinary JNI bindings (identifier names) next to a library are not a protection layer.
        var jni = new ObfuscationProfile.Builder();
        jni.addClass(clean("n/Binding", w -> w.visitMethod(ACC_PUBLIC | ACC_STATIC | ACC_NATIVE, "open", "()J", null, null).visitEnd()));
        jni.addResource("linux/libbinding.so", 10);
        assertTrue(jni.build().techniques().isEmpty());
    }

    @Test void constantConditionsAndSwitchDispatchLoopsAreControlFlowObfuscation() {
        var profile = profile(bare("c/Flow", w -> {
            method(w, ACC_PUBLIC | ACC_STATIC, "opaque", "()I", m -> {
                for (int i = 0; i < 3; i++) { var skip = new Label(); m.visitInsn(ICONST_1); m.visitJumpInsn(IFEQ, skip); m.visitInsn(NOP); m.visitLabel(skip); }
                m.visitInsn(ICONST_0); m.visitInsn(IRETURN); });
            for (String name : List.of("flat1", "flat2")) method(w, ACC_PUBLIC | ACC_STATIC, name, "(I)V", m -> {
                var head = new Label(); var exit = new Label(); var cases = new Label[] {new Label(), new Label(), new Label(), new Label()};
                m.visitLabel(head); m.visitVarInsn(ILOAD, 0);
                m.visitTableSwitchInsn(0, 3, exit, cases);
                for (int i = 0; i < 4; i++) { m.visitLabel(cases[i]); m.visitIntInsn(BIPUSH, (i + 2) % 5); m.visitVarInsn(ISTORE, 0); m.visitJumpInsn(GOTO, head); }
                m.visitLabel(exit); m.visitInsn(RETURN); });
        }));
        assertTrue(profile.has(Technique.CONTROL_FLOW), profile.metrics().toString());
        assertEquals(3, profile.metric("cf.constant_conditions"));
        assertEquals(2, profile.metric("cf.switch_dispatchers"));
        assertEquals("UNKNOWN", ObfuscatorClassifier.classify(profile).verdict());
    }

    @Test void markersSuggestAllatoriStringerAndDashO() {
        var allatori = profile(clean("m/ALLATORIxDEMO", w -> {}));
        assertEquals("Allatori", ObfuscatorClassifier.classify(allatori).hypotheses().getFirst().obfuscator());
        var stringer = profile(bare("m/S", w -> method(w, ACC_STATIC, "d", "(Ljava/lang/Object;)Ljava/lang/String;", m -> {
            m.visitMethodInsn(INVOKESTATIC, "java/lang/Thread", "currentThread", "()Ljava/lang/Thread;", false);
            m.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Thread", "getStackTrace", "()[Ljava/lang/StackTraceElement;", false); m.visitInsn(POP);
            m.visitInsn(ICONST_1); m.visitInsn(ICONST_2); m.visitInsn(IXOR); m.visitInsn(POP);
            m.visitVarInsn(ALOAD, 0); m.visitMethodInsn(INVOKESTATIC, "java/lang/String", "valueOf", "(Ljava/lang/Object;)Ljava/lang/String;", false);
            m.visitInsn(ARETURN); })));
        assertEquals("Stringer", ObfuscatorClassifier.classify(stringer).hypotheses().getFirst().obfuscator());
        var dasho = profile(clean("m/D", w -> { var a = w.visitAnnotation("Lcom/preemptive/annotation/Obfuscated;", false); a.visitEnd(); }));
        assertEquals("DashO (PreEmptive)", ObfuscatorClassifier.classify(dasho).hypotheses().getFirst().obfuscator());
    }

    @Test void techniquesWithoutAMatchingRuleAreUnknown() {
        var profile = profile(clean("u/Item", w -> {}), clean("u/ITEM", w -> {}));
        var result = ObfuscatorClassifier.classify(profile);
        assertEquals("UNKNOWN", result.verdict()); assertTrue(result.hypotheses().isEmpty());
        assertTrue(result.summary().startsWith("unknown"));
    }

    @Test void unparsableClassesAreCountedNotGuessed() {
        var builder = new ObfuscationProfile.Builder();
        builder.addClass(new byte[] {(byte) 0xCA, (byte) 0xFE, 0, 0});
        var profile = builder.build();
        assertEquals(1, profile.unparsable()); assertEquals(0, profile.classes());
    }

    /** Phase 2CA: the ZKM flag holder, {@code static String[] k} read by {@code static String[] t()}. */
    static byte[] flagHolder(String name) {
        return bare(name, w -> {
            w.visitField(ACC_PRIVATE | ACC_STATIC, "k", "[Ljava/lang/String;", null, null).visitEnd();
            method(w, ACC_PUBLIC | ACC_STATIC, "t", "()[Ljava/lang/String;", m -> { m.visitFieldInsn(GETSTATIC, name, "k", "[Ljava/lang/String;"); m.visitInsn(ARETURN); });
        });
    }
    /** {@code String[] f = holder.t(); ... if (f == null) ...} twice, as ZKM's flow obfuscation writes it. */
    static void flagged(ClassWriter w, String holder, String method) {
        method(w, ACC_PUBLIC | ACC_STATIC, method, "()V", m -> {
            var end = new Label();
            m.visitMethodInsn(INVOKESTATIC, holder, "t", "()[Ljava/lang/String;", false); m.visitVarInsn(ASTORE, 0);
            m.visitVarInsn(ALOAD, 0); m.visitJumpInsn(IFNULL, end);
            m.visitVarInsn(ALOAD, 0); m.visitJumpInsn(IFNONNULL, end);
            m.visitLabel(end); m.visitInsn(RETURN); });
    }
    /** Phase 2CA: a class whose handlers start with {@code invokestatic a(RuntimeException)RuntimeException; athrow}. */
    static byte[] exceptionObfuscated(String name, int handlers) {
        return bare(name, w -> {
            method(w, ACC_PRIVATE | ACC_STATIC, "a", "(Ljava/lang/RuntimeException;)Ljava/lang/RuntimeException;", m -> { m.visitVarInsn(ALOAD, 0); m.visitInsn(ARETURN); });
            for (int i = 0; i < handlers; i++) method(w, ACC_PUBLIC | ACC_STATIC, "run" + i, "()V", m -> {
                var start = new Label(); var end = new Label(); var handler = new Label(); var exit = new Label();
                m.visitTryCatchBlock(start, end, handler, "java/lang/RuntimeException");
                m.visitLabel(start); m.visitMethodInsn(INVOKESTATIC, "java/lang/Thread", "yield", "()V", false); m.visitLabel(end);
                m.visitJumpInsn(GOTO, exit);
                m.visitLabel(handler); m.visitMethodInsn(INVOKESTATIC, name, "a", "(Ljava/lang/RuntimeException;)Ljava/lang/RuntimeException;", false); m.visitInsn(ATHROW);
                m.visitLabel(exit); m.visitInsn(RETURN); });
        });
    }

    @Test void aStaticFlagReadThroughAnAccessorAndOnlyTestedIsControlFlowObfuscation() {
        var classes = new ArrayList<byte[]>(List.of(flagHolder("z/F")));
        for (String name : List.of("z/A", "z/B", "z/C", "z/D")) classes.add(bare(name, w -> { for (int i = 0; i < 3; i++) flagged(w, "z/F", "m" + i); }));
        var profile = profile(classes.toArray(byte[][]::new));
        assertTrue(profile.has(Technique.CONTROL_FLOW), profile.metrics().toString());
        assertEquals(12, profile.metric("cf.flag_accessor_methods"));
        assertEquals(24, profile.metric("cf.flag_accessor_tests"));
        assertEquals(4, profile.metric("cf.flag_accessor_classes"));
        assertEquals(1, profile.metric("cf.flag_accessors"));
        assertTrue(profile.techniques().get(Technique.CONTROL_FLOW).samples().stream().anyMatch(s -> s.startsWith("flag z/F.t()[Ljava/lang/String;")));
    }

    @Test void accessorResultsUsedAsValuesAndNonAccessorCallsAreNoFlags() {
        var classes = new ArrayList<byte[]>(List.of(flagHolder("v/F"), clean("v/Names", w ->
            // Not an accessor: computes its result.
            method(w, ACC_PUBLIC | ACC_STATIC, "names", "()[Ljava/lang/String;", m -> { m.visitInsn(ICONST_0); m.visitTypeInsn(ANEWARRAY, "java/lang/String"); m.visitInsn(ARETURN); }))));
        for (String name : List.of("v/A", "v/B", "v/C", "v/D")) classes.add(clean(name, w -> {
            for (int i = 0; i < 3; i++) {
                // javac: the array is tested and then used (its length), so the local is no opaque predicate.
                method(w, ACC_PUBLIC | ACC_STATIC, "used" + i, "()I", m -> {
                    var empty = new Label();
                    m.visitMethodInsn(INVOKESTATIC, "v/F", "t", "()[Ljava/lang/String;", false); m.visitVarInsn(ASTORE, 0);
                    m.visitVarInsn(ALOAD, 0); m.visitJumpInsn(IFNULL, empty);
                    m.visitVarInsn(ALOAD, 0); m.visitInsn(ARRAYLENGTH); m.visitInsn(IRETURN);
                    m.visitLabel(empty); m.visitInsn(ICONST_0); m.visitInsn(IRETURN); });
                flagged(w, "v/Names", "other" + i);
            }
        }));
        var profile = profile(classes.toArray(byte[][]::new));
        assertFalse(profile.has(Technique.CONTROL_FLOW), profile.metrics().toString());
        assertEquals(0, profile.metric("cf.flag_accessor_methods"));
    }

    @Test void handlersRethrowingThroughAnIdentityHelperAreExceptionObfuscation() {
        var profile = profile(exceptionObfuscated("x/A", 6), exceptionObfuscated("x/B", 6));
        var count = profile.techniques().get(Technique.EXCEPTION_OBFUSCATION);
        assertNotNull(count, profile.metrics().toString());
        assertEquals(2, count.classes()); assertEquals(12, count.occurrences());
        assertEquals(2, profile.metric("exceptions.identity_helpers"));
        assertTrue(count.samples().contains("helper x/A.a(Ljava/lang/RuntimeException;)Ljava/lang/RuntimeException; (6 handlers)"), count.samples().toString());
    }

    @Test void javacHandlersStoreTheExceptionFirstAndAreNoExceptionObfuscation() {
        var classes = new ArrayList<byte[]>();
        for (String name : List.of("j/A", "j/B", "j/C")) classes.add(clean(name, w -> {
            // A static identity-like wrapper exists, but javac's handler starts with astore: catch (RuntimeException e) { throw wrap(e); }
            method(w, ACC_PRIVATE | ACC_STATIC, "wrap", "(Ljava/lang/RuntimeException;)Ljava/lang/RuntimeException;", m -> { m.visitVarInsn(ALOAD, 0); m.visitInsn(ARETURN); });
            for (int i = 0; i < 6; i++) method(w, ACC_PUBLIC | ACC_STATIC, "m" + i, "()V", m -> {
                var start = new Label(); var end = new Label(); var handler = new Label(); var exit = new Label();
                m.visitTryCatchBlock(start, end, handler, "java/lang/RuntimeException");
                m.visitLabel(start); m.visitMethodInsn(INVOKESTATIC, "java/lang/Thread", "yield", "()V", false); m.visitLabel(end);
                m.visitJumpInsn(GOTO, exit);
                m.visitLabel(handler); m.visitVarInsn(ASTORE, 0); m.visitVarInsn(ALOAD, 0);
                m.visitMethodInsn(INVOKESTATIC, name, "wrap", "(Ljava/lang/RuntimeException;)Ljava/lang/RuntimeException;", false); m.visitInsn(ATHROW);
                m.visitLabel(exit); m.visitInsn(RETURN); });
        }));
        var profile = profile(classes.toArray(byte[][]::new));
        assertTrue(profile.techniques().isEmpty(), profile.techniques().toString());
        assertEquals("NONE", ObfuscatorClassifier.classify(profile).verdict());
    }

    @Test void flowFlagsAndExceptionObfuscationAddZkmReasons() {
        var classes = new ArrayList<byte[]>(List.of(flagHolder("p/F"), exceptionObfuscated("p/E", 6), exceptionObfuscated("p/G", 6)));
        for (String name : List.of("p/A", "p/a", "p/B", "p/C", "p/D")) classes.add(zkmLike(name));
        for (String name : List.of("p/H", "p/I", "p/J", "p/K")) classes.add(bare(name, w -> { for (int i = 0; i < 3; i++) flagged(w, "p/F", "m" + i); }));
        var result = ObfuscatorClassifier.classify(profile(classes.toArray(byte[][]::new)));
        var zkm = result.hypotheses().getFirst();
        assertEquals("Zelix KlassMaster", zkm.obfuscator());
        assertTrue(zkm.reasons().stream().anyMatch(r -> r.startsWith("opaque predicates on a static flag read through an accessor: 12 methods in 4 classes") && r.endsWith("(+10)")), zkm.reasons().toString());
        assertTrue(zkm.reasons().stream().anyMatch(r -> r.startsWith("exception obfuscation, handlers rethrowing through an identity helper: 12 handlers in 2 classes") && r.endsWith("(+15)")), zkm.reasons().toString());
        // Neither pattern alone reaches a hypothesis: they are ZKM features, but only with ZKM's other evidence.
        var alone = profile(flagHolder("q/Flags"), exceptionObfuscated("q/Errors", 6), exceptionObfuscated("q/Guards", 6),
            bare("q/Home", w -> { for (int i = 0; i < 5; i++) flagged(w, "q/Flags", "step" + i); }), bare("q/Index", w -> { for (int i = 0; i < 5; i++) flagged(w, "q/Flags", "step" + i); }),
            bare("q/Jobs", w -> { for (int i = 0; i < 5; i++) flagged(w, "q/Flags", "step" + i); }));
        assertTrue(alone.has(Technique.CONTROL_FLOW) && alone.has(Technique.EXCEPTION_OBFUSCATION));
        assertTrue(ObfuscatorClassifier.classify(alone).hypotheses().stream().noneMatch(h -> h.obfuscator().equals("Zelix KlassMaster")));
    }

    static final String HANDLER = "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/invoke/MutableCallSite;Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/Object;";
    static final String NATIVE_INIT = "afc1e86fe470a41a3fa7cbccf781387396ddb646";

    /**
     * Phase 2CB: ZKM's value encryption as ZKM writes it. Sites are {@code invokedynamic y:(IJ)Ljava/lang/String;} (and
     * {@code (IJ)I}, {@code (IJ)J}); the class's bootstrap installs a handler that passes the key to a decoder. With
     * {@code nativeKeys} the {@code <clinit>} only registers natives and calls a hex-named native (keys come from a DLL).
     * {@code reaches}: whether the bootstrap actually reaches the decoders.
     */
    static byte[] zkmValues(String name, int strings, int ints, int longs, boolean nativeKeys, boolean reaches) {
        return bare(name, w -> {
            String[][] decoders = {{"s", "(IJ)Ljava/lang/String;"}, {"i", "(IJ)I"}, {"l", "(IJ)J"}};
            method(w, ACC_PRIVATE | ACC_STATIC, "s", decoders[0][1], m -> {
                m.visitTypeInsn(NEW, "java/lang/String"); m.visitInsn(DUP); m.visitInsn(ICONST_1); m.visitIntInsn(NEWARRAY, T_CHAR);
                m.visitMethodInsn(INVOKESPECIAL, "java/lang/String", "<init>", "([C)V", false);
                m.visitVarInsn(ILOAD, 0); m.visitVarInsn(LLOAD, 1); m.visitInsn(L2I); m.visitInsn(IXOR); m.visitInsn(POP); m.visitInsn(ARETURN); });
            method(w, ACC_PRIVATE | ACC_STATIC, "i", decoders[1][1], m -> {
                m.visitVarInsn(ILOAD, 0); m.visitVarInsn(LLOAD, 1); m.visitInsn(L2I); m.visitInsn(IXOR); m.visitInsn(IRETURN); });
            method(w, ACC_PRIVATE | ACC_STATIC, "l", decoders[2][1], m -> {
                m.visitVarInsn(ILOAD, 0); m.visitInsn(I2L); m.visitVarInsn(LLOAD, 1); m.visitInsn(LXOR); m.visitInsn(LRETURN); });
            for (var decoder : decoders)
                method(w, ACC_PRIVATE | ACC_STATIC, "h" + decoder[0], HANDLER, m -> {
                    m.visitInsn(ICONST_0); m.visitInsn(LCONST_0);
                    if (reaches) m.visitMethodInsn(INVOKESTATIC, name, decoder[0], decoder[1], false);
                    m.visitInsn(ACONST_NULL); m.visitInsn(ARETURN); });
            method(w, ACC_PRIVATE | ACC_STATIC, "boot", BOOTSTRAP, m -> {
                for (var decoder : decoders) { m.visitLdcInsn(new Handle(H_INVOKESTATIC, name, "h" + decoder[0], HANDLER, false)); m.visitInsn(POP); }
                m.visitInsn(ACONST_NULL); m.visitInsn(ARETURN); });
            var boot = new Handle(H_INVOKESTATIC, name, "boot", BOOTSTRAP, false);
            method(w, ACC_PUBLIC | ACC_STATIC, "use", "(J)V", m -> {
                int[] counts = {strings, ints, longs}; String[] kinds = {"y", "k", "m"};
                for (int d = 0; d < 3; d++) for (int n = 0; n < counts[d]; n++) {
                    m.visitIntInsn(SIPUSH, 1000 + n); m.visitLdcInsn(0x5DEECE66DL); m.visitVarInsn(LLOAD, 0); m.visitInsn(LXOR);
                    m.visitInvokeDynamicInsn(kinds[d], decoders[d][1], boot); m.visitInsn(d == 2 ? POP2 : POP);
                }
                // A hidden call through the same bootstrap is no encrypted value.
                m.visitInsn(ACONST_NULL); m.visitInvokeDynamicInsn("c", "(Ljava/lang/Object;)V", boot);
                m.visitInsn(RETURN); });
            if (nativeKeys) {
                w.visitMethod(ACC_PRIVATE | ACC_STATIC | ACC_NATIVE, NATIVE_INIT, "()V", null, null).visitEnd();
                method(w, ACC_STATIC, "<clinit>", "()V", m -> {
                    m.visitLdcInsn(1); m.visitLdcInsn(Type.getObjectType(name));
                    m.visitMethodInsn(INVOKESTATIC, "loader/Loader", "registerNativesForClass", "(ILjava/lang/Class;)V", false);
                    m.visitMethodInsn(INVOKESTATIC, name, NATIVE_INIT, "()V", false); m.visitInsn(RETURN); });
            }
        });
    }

    @Test void invokedynamicSitesWhoseBootstrapReachesADecoderAreDecryptionSites() {
        var profile = profile(zkmValues("z/D", 4, 2, 1, false, true), zkmValues("z/E", 3, 0, 0, false, true));
        var strings = profile.techniques().get(Technique.STRING_ENCRYPTION);
        assertNotNull(strings, profile.techniques().toString());
        assertEquals(7, strings.occurrences()); // the invokedynamic sites; the handlers' own calls are forwarding, not sites
        assertEquals(2, strings.classes());
        assertEquals(7, profile.metric("strings.indy_sites"));
        assertEquals(0, profile.metric("strings.decryptor_calls"));
        assertEquals(2, profile.metric("strings.decryptor_forwarding_calls"));
        assertEquals(2, profile.metric("indy.decoding_bootstraps"));
        var constants = profile.techniques().get(Technique.CONSTANT_ENCRYPTION);
        assertEquals(3, constants.occurrences()); assertEquals(1, constants.classes());
        assertEquals(2, profile.metric("constants.indy_int_sites")); assertEquals(1, profile.metric("constants.indy_long_sites"));
        assertTrue(constants.samples().getFirst().startsWith("int z/D#use(J)V -> (IJ)I through z/D"), constants.samples().toString());
        // Hidden calls still count every custom invokedynamic site, encrypted values included.
        assertEquals(12, profile.techniques().get(Technique.HIDDEN_CALLS).occurrences());
        assertEquals(0, profile.metric("strings.native_key_classes"));
    }

    @Test void aBootstrapThatReachesNoDecoderGivesNoDecryptionSites() {
        var profile = profile(zkmValues("z/D", 4, 2, 1, false, false));
        assertFalse(profile.has(Technique.STRING_ENCRYPTION), profile.techniques().toString());
        assertFalse(profile.has(Technique.CONSTANT_ENCRYPTION));
        assertEquals(0, profile.metric("strings.indy_sites"));
        assertTrue(profile.has(Technique.HIDDEN_CALLS));
    }

    @Test void aClinitThatOnlyRegistersNativesSuppliesTheKeysFromTheNativeLayer() {
        var profile = profile(zkmValues("z/D", 4, 2, 1, true, true), zkmValues("z/E", 3, 0, 0, false, true));
        assertEquals(1, profile.metric("strings.native_key_classes"));
        assertTrue(profile.techniques().get(Technique.STRING_ENCRYPTION).samples().contains("keys supplied by the native layer: z/D <clinit> only registers natives"),
            profile.techniques().get(Technique.STRING_ENCRYPTION).samples().toString());
        var result = ObfuscatorClassifier.classify(profile);
        var nativeLayer = result.hypotheses().stream().filter(h -> h.obfuscator().contains("native")).findFirst().orElseThrow();
        assertTrue(nativeLayer.reasons().contains("decryption keys supplied by the native layer (<clinit> only registers natives): 1 classes (+10)"), nativeLayer.reasons().toString());
        var zkm = result.hypotheses().stream().filter(h -> h.obfuscator().equals("Zelix KlassMaster")).findFirst().orElseThrow();
        assertTrue(zkm.reasons().contains("int/long constants decoded through invokedynamic: 2 int and 1 long sites (+10)"), zkm.reasons().toString());
    }

    @Test void aNativeOnlyClinitWithoutDecodingIsNoNativeKey() {
        // JNA-style: <clinit> registers natives, but the class decodes nothing.
        var profile = profile(clean("j/Native", w -> {
            w.visitMethod(ACC_PRIVATE | ACC_STATIC | ACC_NATIVE, "initIDs", "()V", null, null).visitEnd();
            method(w, ACC_STATIC, "<clinit>", "()V", m -> {
                m.visitLdcInsn(Type.getObjectType("j/Native"));
                m.visitMethodInsn(INVOKESTATIC, "j/Loader", "register", "(Ljava/lang/Class;)V", false);
                m.visitMethodInsn(INVOKESTATIC, "j/Native", "initIDs", "()V", false); m.visitInsn(RETURN); });
        }));
        assertEquals(0, profile.metric("strings.native_key_classes"));
        assertEquals("NONE", ObfuscatorClassifier.classify(profile).verdict());
    }

    /** Phase 2CD: a Skidfuscator-shaped method: seed = C ^ seed updates, a self-looping switch on hash(seed), a caught throw. */
    static void skidMethod(ClassWriter w, String owner, String name) {
        method(w, ACC_PUBLIC | ACC_STATIC, name, "()I", m -> {
            m.visitLdcInsn(123456); m.visitVarInsn(ISTORE, 1);
            for (int i = 0; i < 3; i++) { m.visitLdcInsn(1000 + i); m.visitVarInsn(ILOAD, 1); m.visitInsn(IXOR); m.visitVarInsn(ISTORE, 1); }
            var head = new Label(); var next = new Label(); var trap = new Label();
            m.visitLabel(head); m.visitVarInsn(ILOAD, 1); m.visitMethodInsn(INVOKESTATIC, "f/Factory", "h", "(I)I", false);
            m.visitLookupSwitchInsn(trap, new int[] {1, 2}, new Label[] {next, head});
            m.visitLabel(trap); m.visitTypeInsn(NEW, "java/io/IOException"); m.visitInsn(DUP);
            m.visitMethodInsn(INVOKESPECIAL, "java/io/IOException", "<init>", "()V", false); m.visitInsn(ATHROW);
            m.visitLabel(next);
            var start = new Label(); var end = new Label(); var handler = new Label(); var done = new Label();
            m.visitTryCatchBlock(start, end, handler, "java/io/IOException");
            m.visitLabel(start); m.visitTypeInsn(NEW, "java/io/IOException"); m.visitInsn(DUP);
            m.visitMethodInsn(INVOKESPECIAL, "java/io/IOException", "<init>", "()V", false); m.visitInsn(ATHROW);
            m.visitLabel(end); m.visitLabel(handler); m.visitInsn(POP); m.visitLabel(done);
            m.visitInsn(ICONST_0); m.visitInsn(IRETURN); });
    }

    @Test void skidfuscatorSeedFlowStringsAndMarkersSuggestSkidfuscator() {
        var app = bare("s/App", w -> {
            w.visitField(ACC_PRIVATE | ACC_STATIC, "nothing_to_see_here", "[Ljava/lang/String;", null, null).visitEnd();
            w.visitField(ACC_PRIVATE | ACC_STATIC, "seed", "I", null, null).visitEnd();
            for (String name : List.of("a", "b", "c")) skidMethod(w, "s/App", name);
            method(w, ACC_STATIC, "<clinit>", "()V", m -> {
                m.visitTypeInsn(NEW, "java/util/Random"); m.visitInsn(DUP); m.visitLdcInsn(42L);
                m.visitMethodInsn(INVOKESPECIAL, "java/util/Random", "<init>", "(J)V", false);
                m.visitMethodInsn(INVOKEVIRTUAL, "java/util/Random", "nextInt", "()I", false); m.visitVarInsn(ISTORE, 0);
                m.visitLdcInsn(77); m.visitVarInsn(ILOAD, 0); m.visitInsn(IXOR); m.visitFieldInsn(PUTSTATIC, "s/App", "seed", "I"); m.visitInsn(RETURN); });
            method(w, ACC_PUBLIC | ACC_STATIC, "dec", "([B[BI)Ljava/lang/String;", m -> {
                m.visitVarInsn(ILOAD, 2); m.visitMethodInsn(INVOKESTATIC, "java/lang/Integer", "toString", "(I)Ljava/lang/String;", false);
                m.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "getBytes", "()[B", false); m.visitInsn(POP);
                m.visitTypeInsn(NEW, "java/lang/String"); m.visitInsn(DUP); m.visitVarInsn(ALOAD, 0);
                m.visitMethodInsn(INVOKESPECIAL, "java/lang/String", "<init>", "([B)V", false); m.visitInsn(ARETURN); });
            method(w, ACC_PRIVATE | ACC_STATIC, "bytes", "()[B", m -> { m.visitInsn(ICONST_1); m.visitIntInsn(NEWARRAY, T_BYTE); m.visitInsn(ARETURN); });
            method(w, ACC_PUBLIC | ACC_STATIC, "use", "(I)V", m -> {
                for (int i = 0; i < 3; i++) {
                    m.visitMethodInsn(INVOKESTATIC, "s/App", "bytes", "()[B", false); m.visitMethodInsn(INVOKESTATIC, "s/App", "bytes", "()[B", false);
                    m.visitVarInsn(ILOAD, 0); m.visitMethodInsn(INVOKESTATIC, "s/App", "dec", "([B[BI)Ljava/lang/String;", false); m.visitInsn(POP);
                }
                m.visitInsn(RETURN); });
        });
        var sdk = bare("sdk/SDK", w -> {});
        var profile = profile(app, sdk);
        assertEquals(3, profile.metric("skid.self_loop_hash_switches"), profile.metrics().toString());
        assertEquals(3, profile.metric("skid.seed_methods"));
        assertEquals(1, profile.metric("skid.random_seed_fields"));
        assertEquals(1, profile.metric("skid.seed_string_decryptors"));
        assertEquals(3, profile.metric("strings.skid_sites"));
        assertEquals(3, profile.metric("skid.exception_gotos")); // the trap throw outside any try range does not count
        assertEquals(1, profile.metric("marker.ahegao"));
        assertEquals(1, profile.metric("marker.skidfuscator_sdk"));
        assertTrue(profile.has(Technique.CONTROL_FLOW));
        assertTrue(profile.has(Technique.STRING_ENCRYPTION));
        var result = ObfuscatorClassifier.classify(profile);
        assertEquals("Skidfuscator", result.hypotheses().getFirst().obfuscator());
        assertEquals("HIGH", result.hypotheses().getFirst().confidence());
        assertTrue(result.hypotheses().stream().noneMatch(h -> h.obfuscator().startsWith("Zelix")), result.summary());
    }

    @Test void javacXorAssignmentsAndOrdinarySwitchesAreNoSkidfuscatorEvidence() {
        // javac's x ^= C loads x first; an ordinary switch on a hash result never jumps back to its own load.
        var profile = profile(clean("j/Plain", w -> method(w, ACC_PUBLIC | ACC_STATIC, "mix", "(I)I", m -> {
            for (int i = 0; i < 4; i++) { m.visitVarInsn(ILOAD, 0); m.visitLdcInsn(1000 + i); m.visitInsn(IXOR); m.visitVarInsn(ISTORE, 0); }
            var a = new Label(); var b = new Label();
            m.visitVarInsn(ILOAD, 0); m.visitMethodInsn(INVOKESTATIC, "java/lang/Integer", "hashCode", "(I)I", false);
            m.visitLookupSwitchInsn(b, new int[] {1}, new Label[] {a});
            m.visitLabel(a); m.visitInsn(ICONST_1); m.visitInsn(IRETURN);
            m.visitLabel(b); m.visitInsn(ICONST_0); m.visitInsn(IRETURN); })));
        assertEquals(0, profile.metric("skid.seed_methods"));
        assertEquals(0, profile.metric("skid.self_loop_hash_switches"));
        assertTrue(ObfuscatorClassifier.classify(profile).hypotheses().isEmpty());
    }

    /** Phase 2CZ: Aidsfuscator's generated classes: the FNV-1a salt dispatcher and the MutableCallSite reference bootstrap. */
    @Test void aidsfuscatorsDispatcherAndBootstrapAreRecognized() {
        var dispatcher = bare("a", w -> {
            method(w, ACC_PRIVATE | ACC_STATIC, "a", "(Ljava/lang/String;)J", m -> {
                m.visitLdcInsn(0xcbf29ce484222325L); m.visitInsn(POP2); m.visitLdcInsn(0x100000001b3L); m.visitInsn(LRETURN); });
            method(w, ACC_PUBLIC | ACC_STATIC, "a", "(Ljava/lang/Object;I)I", m -> { m.visitVarInsn(ILOAD, 1); m.visitInsn(IRETURN); });
        });
        var bootstrap = bare("b", w -> {
            method(w, ACC_PUBLIC | ACC_STATIC, "a", BOOTSTRAP, m -> {
                m.visitTypeInsn(NEW, "java/lang/invoke/MutableCallSite"); m.visitInsn(DUP); m.visitVarInsn(ALOAD, 2);
                m.visitMethodInsn(INVOKESPECIAL, "java/lang/invoke/MutableCallSite", "<init>", "(Ljava/lang/invoke/MethodType;)V", false);
                m.visitInsn(ARETURN); });
            method(w, ACC_STATIC, "a", "(II)Ljava/lang/String;", m -> { m.visitInsn(ACONST_NULL); m.visitInsn(ARETURN); });
        });
        var salted = bare("c", w -> method(w, ACC_STATIC, "<clinit>", "()V", m -> {
            for (int i = 0; i < 2; i++) {
                m.visitMethodInsn(INVOKESTATIC, "java/lang/invoke/MethodHandles", "lookup", "()Ljava/lang/invoke/MethodHandles$Lookup;", false);
                m.visitMethodInsn(INVOKEVIRTUAL, "java/lang/invoke/MethodHandles$Lookup", "lookupClass", "()Ljava/lang/Class;", false);
                m.visitLdcInsn(12345 + i);
                m.visitMethodInsn(INVOKESTATIC, "a", "a", "(Ljava/lang/Object;I)I", false);
                m.visitFieldInsn(PUTSTATIC, "c", "a" + i, "I");
            }
            m.visitInsn(RETURN); }));
        var profile = profile(dispatcher, bootstrap, salted);
        assertEquals(1, profile.metric("aids.salt_dispatcher"));
        assertEquals(1, profile.metric("aids.reference_bootstrap"));
        assertEquals(2, profile.metric("aids.lookup_class_retrievers"));
        var result = ObfuscatorClassifier.classify(profile);
        assertEquals("Aidsfuscator", result.hypotheses().getFirst().obfuscator(), result.summary());
        assertEquals(65, result.hypotheses().getFirst().score(), result.summary());
        // An FNV-1a hash alone (a hash map library, say) is no dispatcher.
        assertEquals(0, profile(bare("h", w -> method(w, ACC_STATIC, "a", "(Ljava/lang/String;)J", m -> {
            m.visitLdcInsn(0xcbf29ce484222325L); m.visitInsn(POP2); m.visitLdcInsn(0x100000001b3L); m.visitInsn(LRETURN); }))).metric("aids.salt_dispatcher"));
    }

    @Test void profileRoundTripsThroughTheBoundedWorkerFormat() throws IOException {
        var profile = profile(zkmLike("p/A"));
        var bytes = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(bytes)) { profile.write(out); }
        var read = ObfuscationProfile.read(new DataInputStream(new ByteArrayInputStream(bytes.toByteArray())));
        assertEquals(profile.techniques(), read.techniques());
        assertEquals(profile.metrics(), read.metrics());
        assertEquals(profile.classes(), read.classes());
        byte[] corrupt = bytes.toByteArray(); corrupt[3] = 9;
        assertThrows(IOException.class, () -> ObfuscationProfile.read(new DataInputStream(new ByteArrayInputStream(corrupt))));
    }
}
