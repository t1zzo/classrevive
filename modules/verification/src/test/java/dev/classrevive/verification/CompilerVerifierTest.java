package dev.classrevive.verification;

import dev.classrevive.model.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.Duration;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class CompilerVerifierTest {
    @TempDir Path dir;
    ClassInfo original() { return new ClassInfo("Sample", "java/lang/Object", 52, 0, 1, List.of(), List.of(),
        List.of(new MemberInfo("<init>", "()V", 1), new MemberInfo("answer", "()I", 1))); }
    VerificationResult verify(String code) throws Exception {
        var sources = Files.createDirectory(dir.resolve("src"));
        Files.writeString(sources.resolve("Sample.java"), code);
        return new CompilerVerifier().verify(sources, dir.resolve("verify"), List.of(original()), Duration.ofSeconds(20));
    }
    @Test void recompilesWithoutExecutingInitializer() throws Exception {
        var result = verify("public class Sample { static { if(true) throw new Error(); } public int answer(){return 42;} }");
        assertEquals(VerificationResult.Status.COMPILED, result.status());
        assertTrue(result.complete());
    }
    @Test void readsJdkTypesOutsideTheJava8ApiFromTheRunningJdkWhileJava8TypesKeepTheirMembers() throws Exception {
        // Phase 2BL: a Java 8 class built against a full JDK (sun.misc.Unsafe, and a Java 9 type behind a runtime check).
        String code = "public class Sample { static sun.misc.Unsafe u;"
            + " public boolean cas(Object o, long off) { return u.compareAndSwapLong(o, off, 1L, 2L); }"
            + " public Object tool() { return java.util.spi.ToolProvider.findFirst(\"jar\"); }"
            + " public java.nio.Buffer flip(java.nio.ByteBuffer b) { return b.flip(); } }";
        var sources = Files.createDirectory(dir.resolve("src"));
        Files.writeString(sources.resolve("Sample.java"), code);
        var built = Files.createDirectory(dir.resolve("built"));
        assertEquals(0, javax.tools.ToolProvider.getSystemJavaCompiler().run(null, null, null, "-source", "8", "-target", "8",
            "-Xlint:-options", "-proc:none", "-d", built.toString(), sources.resolve("Sample.java").toString()));
        var info = new dev.classrevive.bytecode.ClassInspector().inspect(Files.readAllBytes(built.resolve("Sample.class")));
        var result = new CompilerVerifier().verify(sources, dir.resolve("verify"), List.of(info), Duration.ofSeconds(30));
        assertEquals(VerificationResult.Status.COMPILED, result.status(), result.detail());
        assertTrue(result.complete(), result.detail());
        assertTrue(result.detail().contains("outside the Java 8 API, read from the running JDK: java.util.spi.ToolProvider, sun.misc.Unsafe;"), result.detail());
        // The Java 8 API still comes first: ByteBuffer.flip() binds to Java 8's Buffer-returning method, not Java 9's override.
        String recompiled = new String(Files.readAllBytes(dir.resolve("verify/classes/Sample.class")), java.nio.charset.StandardCharsets.ISO_8859_1);
        assertTrue(recompiled.contains("()Ljava/nio/Buffer;") && !recompiled.contains("()Ljava/nio/ByteBuffer;"));
    }
    @Test void readsNoRuntimeTypeWhenTheJava8ApiSuffices() throws Exception {
        var result = verify("public class Sample { public int answer(){ return new java.util.ArrayList<String>().size(); } }");
        assertEquals(VerificationResult.Status.COMPILED, result.status());
        assertFalse(result.detail().contains("running JDK"), result.detail());
    }
    @Test void reportsSyntaxFailure() throws Exception {
        var result = verify("public class Sample { broken syntax }");
        assertEquals(VerificationResult.Status.COMPILE_FAILED, result.status());
        assertEquals(result.errors(), result.syntaxErrors());
        assertEquals(java.util.Map.of("Sample.java", result.errors()), result.fileErrors());
    }
    @Test void attributesErrorsToSourceFilesAndSeparatesSyntaxFromAttribution() throws Exception {
        var sources = Files.createDirectories(dir.resolve("src/p"));
        Files.writeString(sources.resolve("Sample.java"), "package p; public class Sample { Missing m; int answer(){return 42;} }");
        Files.writeString(sources.resolve("Fine.java"), "package p; class Fine {}");
        var result = new CompilerVerifier().verify(dir.resolve("src"), dir.resolve("verify"), List.of(original()), Duration.ofSeconds(20));
        assertEquals(VerificationResult.Status.COMPILE_FAILED, result.status());
        assertEquals(0, result.syntaxErrors());
        assertEquals(java.util.Map.of("p/Sample.java", 1), result.fileErrors());
    }
    @Test void recordsLocatedDiagnosticsWithCodesForFailureCases() throws Exception {
        var sources = Files.createDirectories(dir.resolve("src/p"));
        Files.writeString(sources.resolve("Sample.java"), "package p; public class Sample {\n Missing m; int answer(){return 42;} }");
        var result = new CompilerVerifier().verify(dir.resolve("src"), dir.resolve("verify"), List.of(original()), Duration.ofSeconds(20));
        assertEquals(1, result.diagnostics().size(), result.diagnostics().toString());
        var diagnostic = result.diagnostics().getFirst();
        assertEquals("p/Sample.java", diagnostic.file());
        assertEquals(2, diagnostic.line()); assertEquals(2, diagnostic.column());
        assertEquals("compiler.err.cant.resolve.location", diagnostic.code());
        assertTrue(diagnostic.message().contains("Missing"), diagnostic.message());
        assertEquals(List.of(), verifyCompiling().diagnostics());
    }
    VerificationResult verifyCompiling() throws Exception {
        var sources = Files.createDirectories(dir.resolve("ok"));
        Files.writeString(sources.resolve("Sample.java"), "public class Sample { public int answer(){return 42;} }");
        return new CompilerVerifier().verify(sources, dir.resolve("verify-ok"), List.of(original()), Duration.ofSeconds(20));
    }
    @Test void rejectsMalformedDiagnosticLogs() throws Exception {
        for (String line : List.of("A.java\t1\t1\tcompiler.err.x", "../A.java\t1\t1\tcompiler.err.x\tm", "A.java\t1\t1\tcompiler.warn.x\tm",
                "A.java\tone\t1\tcompiler.err.x\tm", "A.java\t-2\t1\tcompiler.err.x\tm", "A.java\t1\t1\tcompiler.err.x\tbad\\q")) {
            Path file = dir.resolve("diagnostics-" + Math.abs(line.hashCode()) + ".tsv");
            Files.writeString(file, line + "\n");
            assertThrows(java.io.IOException.class, () -> CompilerVerifier.diagnostics(file), line);
        }
        Path good = dir.resolve("good.tsv");
        Files.writeString(good, "p/A.java\t3\t-1\tcompiler.err.x\tline\\none\\ttab\n");
        assertEquals(List.of(new VerificationResult.CompilerDiagnostic("p/A.java", 3, -1, "compiler.err.x", "line\none\ttab")),
            CompilerVerifier.diagnostics(good));
        assertEquals(List.of(), CompilerVerifier.diagnostics(dir.resolve("absent.tsv")));
    }
    @Test void rejectsUnsafePerFileErrorPaths() throws Exception {
        for (String line : List.of("1\t../Escape.java", "1\t/abs.java", "0\tA.java", "x\tA.java", "1\ta\\b.java", "1\tA.txt", "1\tA.java\n1\tA.java")) {
            Path file = dir.resolve("errors-" + Math.abs(line.hashCode()) + ".txt");
            Files.writeString(file, line + "\n");
            assertThrows(java.io.IOException.class, () -> CompilerVerifier.fileErrors(file), line);
        }
        assertEquals(java.util.Map.of(), CompilerVerifier.fileErrors(dir.resolve("absent.txt")));
    }
    @Test void changedVisibilityAndStaticModifierBreakSignatureCoverage() throws Exception {
        var result = verify("public class Sample { private static int answer(){return 42;} }");
        assertEquals(VerificationResult.Status.COMPILED,result.status());
        assertFalse(result.complete());
    }
    @Test void changedStaticModifierAloneIsDetected() throws Exception {
        assertFalse(verify("public class Sample { public static int answer(){return 42;} }").complete());
    }
    @Test void changedVisibilityAloneIsDetected() throws Exception {
        assertFalse(verify("public class Sample { private int answer(){return 42;} }").complete());
    }
    @Test void changedConcreteClassToAbstractIsDetected() throws Exception {
        assertFalse(verify("public abstract class Sample { public abstract int answer(); }").complete());
    }
    @Test void placeholderSignalPreventsUnqualifiedSuccess() throws Exception {
        var result = verify("public class Sample { public int answer(){ throw new UnsupportedOperationException(); } }");
        assertEquals(VerificationResult.Status.COMPILED, result.status());
        assertFalse(result.complete());
        assertTrue(result.placeholderSignals() > 0);
    }
    @Test void recognizesEachPinnedEngineStubMarker() {
        // Shapes follow the stub text each pinned engine emits for a method it could not decompile.
        var stubs = java.util.Map.of(
            "cfr", "// This method has failed to decompile.  When submitting a bug report, please provide this stack trace\n"
                + "throw new IllegalStateException(\"Decompilation failed\");",
            "procyon", "throw new IllegalStateException(\"An error occurred while decompiling this method.\");",
            "vineflower", "// $VF: Couldn't be decompiled",
            "jadx-not-decompiled", "throw new UnsupportedOperationException(\"Method not decompiled: Sample.answer():int\");",
            "jadx-dump-skipped", "/* Code decompiled incorrectly, please refer to instructions dump.\n    Method dump skipped, instructions count: 9000 */",
            "jadx-error", "/* JADX ERROR: Method code generation error */",
            "jadx-restructure", "/* JADX WARNING: Code restructure failed: missing block: B:5:0x0010 */");
        stubs.forEach((engine, text) -> assertTrue(CompilerVerifier.placeholderSignals(text) > 0, engine));
    }
    @Test void cleanSourceHasNoPlaceholderSignals() {
        assertEquals(0, CompilerVerifier.placeholderSignals(
            "public class Sample { public int answer(){ if (x) throw new IllegalStateException(\"bad state\"); return 42; } }"));
    }
    @Test void procyonStubPreventsUnqualifiedSuccess() throws Exception {
        var result = verify("public class Sample { public int answer(){ "
            + "throw new IllegalStateException(\"An error occurred while decompiling this method.\"); } }");
        assertEquals(VerificationResult.Status.COMPILED, result.status());
        assertFalse(result.complete());
    }
    @Test void exposesMissingDependencies() throws Exception {
        var result = verify("public class Sample extends AbsentDependency { public int answer(){return 42;} }");
        assertEquals(VerificationResult.Status.COMPILE_FAILED, result.status());
        assertTrue(Files.readString(dir.resolve("verify/engine.log")).contains("cant.resolve"));
    }
    @Test void namesPackagesThatLookLikeMissingDependencies() throws Exception {
        // commons-compress without its optional xz and zstd jars: javac reports `package X does not exist`.
        var result = verify("import org.tukaani.xz.LZMA2Options; import org.tukaani.xz.XZ; import com.github.luben.zstd.ZstdOutputStream;\n"
            + "public class Sample { public int answer(){ return 42; } }");
        assertEquals(VerificationResult.Status.COMPILE_FAILED, result.status());
        assertTrue(result.detail().contains("missing packages (supply with --classpath): org.tukaani.xz (2), com.github.luben.zstd (1)"),
            result.detail());
        assertEquals("", CompilerVerifier.missingPackages("p.own:3", List.of(new ClassInfo("p/own/Thing", "java/lang/Object", 52, 0, 1,
            List.of(), List.of(), List.of()))), "a package of the input is never reported as a dependency");
        assertEquals("; missing packages (supply with --classpath): org.tukaani.xz (27)",
            CompilerVerifier.missingPackages("XZ:2,org.tukaani.xz:27", List.of()), "an unresolved class used as a qualifier is not a package");
    }
    @Test void compileSuccessWithMissingMethodIsIncomplete() throws Exception {
        var result = verify("public class Sample {}");
        assertEquals(VerificationResult.Status.COMPILED, result.status());
        assertFalse(result.complete());
        assertEquals(2, result.matchedSymbols());
        assertEquals(3, result.expectedSymbols());
    }
    @Test void doesNotPretendToSupportNewerRelease() throws Exception {
        var src = Files.createDirectory(dir.resolve("src")); Files.writeString(src.resolve("Sample.java"), "class Sample {}");
        var future = new ClassInfo("Sample", "java/lang/Object", 67, 0, 1, List.of(), List.of(), List.of());
        assertEquals(VerificationResult.Status.UNSUPPORTED_RELEASE,
            new CompilerVerifier().verify(src, dir.resolve("verify"), List.of(future), Duration.ofSeconds(5)).status());
    }
    @Test void reportsJava25AsUnsupportedReleaseUnderJdk21() throws Exception {
        var src = Files.createDirectory(dir.resolve("src")); Files.writeString(src.resolve("Sample.java"), "class Sample {}");
        var java25 = new ClassInfo("Sample", "java/lang/Object", 69, 0, 1, List.of(), List.of(), List.of());
        var result = new CompilerVerifier().verify(src, dir.resolve("verify"), List.of(java25), Duration.ofSeconds(5));
        assertEquals(VerificationResult.Status.UNSUPPORTED_RELEASE, result.status());
        assertFalse(result.complete());
        assertFalse(Files.exists(dir.resolve("verify")));
    }
    @Test void verifiesJava5To7ClassFilesWithRelease8() throws Exception {
        // Phase 2AZ: javac 21 cannot target 5..7, but their decompiled source is valid Java 8, so it is checked with --release 8.
        var src = Files.createDirectory(dir.resolve("src")); Files.writeString(src.resolve("Sample.java"), "public class Sample {}");
        for (int major : new int[] {49, 51}) {
            var old = new ClassInfo("Sample", "java/lang/Object", major, 0, 1, List.of(), List.of(), List.of(new MemberInfo("<init>", "()V", 1)));
            var result = new CompilerVerifier().verify(src, dir.resolve("verify-" + major), List.of(old), Duration.ofSeconds(20));
            assertEquals(VerificationResult.Status.COMPILED, result.status(), result.detail());
            assertTrue(result.detail().contains("release=8 (Java " + (major - 44) + " class files"), result.detail());
        }
        assertEquals(8, CompilerVerifier.release(List.of(new ClassInfo("A", "java/lang/Object", 45, 3, 1, List.of(), List.of(), List.of()))));
    }
    @Test void moduleDescriptorIsNeitherRecompiledNorCounted() throws Exception {
        // How several engines print module-info.class: not valid Java, and not ordinary source either way.
        var src = Files.createDirectory(dir.resolve("src"));
        Files.writeString(src.resolve("module-info.java"), "/* module-info */ class module-info {\n}\n");
        Files.writeString(src.resolve("Sample.java"), "public class Sample { public int answer(){return 42;} }");
        var descriptor = new ClassInfo("module-info", null, 53, 0, 0x8000, List.of(), List.of(), List.of());
        var java5 = new ClassInfo("Sample", "java/lang/Object", 49, 0, 1, List.of(), List.of(),
            List.of(new MemberInfo("<init>", "()V", 1), new MemberInfo("answer", "()I", 1)));
        var result = new CompilerVerifier().verify(src, dir.resolve("verify"), List.of(descriptor, java5), Duration.ofSeconds(20));
        assertEquals(VerificationResult.Status.COMPILED, result.status(), result.detail());
        assertTrue(result.complete(), result.detail());
        assertEquals(3, result.expectedSymbols());
        assertTrue(result.detail().contains("release=8") && result.detail().contains("module descriptor not recompiled"), result.detail());
    }
}
