package dev.classrevive.pipeline;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.Duration;
import java.util.jar.*;
import java.util.zip.*;
import java.io.*;
import javax.tools.ToolProvider;
import static org.junit.jupiter.api.Assertions.*;

class TransformJobTest {
    @TempDir Path directory;
    @Test void evaluatesLiteralStringInWorkerWithoutLoggingItsContents() throws Exception {
        var writer=new org.objectweb.asm.ClassWriter(0);
        writer.visit(52,1,"LiteralString",null,"java/lang/Object",null);
        var method=writer.visitMethod(9,"value","()I",null,null);
        method.visitCode(); method.visitLdcInsn("private-literal");
        method.visitMethodInsn(182,"java/lang/String","length","()I",false);
        method.visitInsn(172); method.visitMaxs(1,0); method.visitEnd(); writer.visitEnd();
        Path input=directory.resolve("LiteralString.class"); Files.write(input,writer.toByteArray());
        Path output=directory.resolve("string-result");
        var result=new TransformJob().run(input,output,100,Duration.ofSeconds(20),"string-length");
        assertTrue(result.successful()); assertEquals(1,result.changes());
        String report=Files.readString(output.resolve("transform.json"));
        assertTrue(report.contains("string-length-v1")); assertTrue(report.contains("\"result\":15"));
        assertFalse(report.contains("private-literal"));
    }
    /** Phase 2DI: an output under a linked parent folder (macOS's /var) is written to the real folder; a linked output is refused. */
    @Test void writesBelowALinkedParentButNotIntoALinkedOutput() throws Exception {
        var writer=new org.objectweb.asm.ClassWriter(0);
        writer.visit(52,1,"Plain",null,"java/lang/Object",null); writer.visitEnd();
        Path input=directory.resolve("Plain.class"); Files.write(input,writer.toByteArray());
        Path real=Files.createDirectory(directory.resolve("real"));
        Path alias;
        try { alias=Files.createSymbolicLink(directory.resolve("alias"),real); }
        catch(IOException|UnsupportedOperationException unavailable) { org.junit.jupiter.api.Assumptions.abort("Symbolic links unavailable: "+unavailable); return; }
        var result=new TransformJob().run(input,alias.resolve("result"),100,Duration.ofSeconds(20),"string-length");
        assertTrue(result.successful());
        assertTrue(Files.isRegularFile(real.resolve("result/transform.json"),LinkOption.NOFOLLOW_LINKS));
        Path target=Files.createDirectory(directory.resolve("target"));
        Path linkedOutput=Files.createSymbolicLink(directory.resolve("linked-output"),target);
        assertThrows(IOException.class,() -> new TransformJob().run(input,linkedOutput,100,Duration.ofSeconds(20),"string-length"));
        try(var entries=Files.list(target)) { assertEquals(0,entries.count()); }
    }
    @Test void foldsAZkmFlagProvedOverTheWholeJarInTheWorker() throws Exception {
        // Phase 2CM: the worker reads every class before it transforms any, so a flag held in H is folded in U.
        Files.writeString(directory.resolve("H.java"), "public class H { private static String[] k; static { Q(new String[3]); }"
            + " public static String[] t() { return k; } public static void Q(String[] a) { k = a; } }");
        Files.writeString(directory.resolve("U.java"), "public class U { public static int m() { String[] f = H.t(); if (f == null) return 2; return 1; } }");
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "--release", "8", "-Xlint:-options", "-proc:none",
            "-d", directory.toString(), directory.resolve("H.java").toString(), directory.resolve("U.java").toString()));
        Path input = directory.resolve("flags.jar");
        try (var jar = new JarOutputStream(Files.newOutputStream(input))) {
            for (String name : new String[] {"H.class", "U.class"}) {
                jar.putNextEntry(new JarEntry(name)); jar.write(Files.readAllBytes(directory.resolve(name))); jar.closeEntry();
            }
        }
        var result = new TransformJob().run(input, directory.resolve("folded"), 100, Duration.ofSeconds(20), "zkm-flags");
        assertTrue(result.successful());
        assertEquals(1, result.changes());
        assertEquals(java.util.Set.of("U.m()I"), result.methods());
        assertTrue(Files.readString(directory.resolve("folded/transform.json")).contains("zkm-flags-v1"));
    }
    @Test void decryptsSupportedStringLiteralInWorkerWithoutLoggingItOrChangingInput() throws Exception {
        Path source = directory.resolve("DecodeFixture.java");
        Files.writeString(source, "public class DecodeFixture {"
            + "private static String decode(String s) { char[] c=s.toCharArray();"
            + "for(int i=0;i<c.length;i++) c[i]=(char)(c[i]^7); return new String(c); }"
            + "public static String value() { return decode(\"Obkkh\"); } }");
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "--release", "8", "-Xlint:-options",
            "-proc:none", "-d", directory.toString(), source.toString()));
        Path input = directory.resolve("DecodeFixture.class"), output = directory.resolve("decoded");
        byte[] original = Files.readAllBytes(input);
        var result = new TransformJob().run(input, output, 100, Duration.ofSeconds(20), "string-decryptor");
        assertTrue(result.successful(), result.toString()); assertEquals(1, result.changes());
        assertArrayEquals(original, Files.readAllBytes(input));
        String evidence = Files.readString(output.resolve("transform.json"));
        assertTrue(evidence.contains("string-decryptor-v1"));
        assertFalse(evidence.contains("Obkkh")); assertFalse(evidence.contains("Hello"));
        try (var zip = new ZipFile(output.resolve("worker/transformed/transformed.jar").toFile())) {
            var type = new org.objectweb.asm.tree.ClassNode();
            new org.objectweb.asm.ClassReader(zip.getInputStream(zip.getEntry("DecodeFixture.class")).readAllBytes()).accept(type, 0);
            var value = type.methods.stream().filter(method -> method.name.equals("value")).findFirst().orElseThrow();
            assertTrue(java.util.Arrays.stream(value.instructions.toArray()).anyMatch(node -> node instanceof org.objectweb.asm.tree.LdcInsnNode literal && "Hello".equals(literal.cst)));
            assertFalse(java.util.Arrays.stream(value.instructions.toArray()).anyMatch(node -> node instanceof org.objectweb.asm.tree.MethodInsnNode call && call.name.equals("decode")));
        }
    }
    @Test void decryptsUtf8ByteLoopInWorkerWithoutLoggingDecodedText() throws Exception {
        Path source = directory.resolve("Utf8DecodeFixture.java");
        Files.writeString(source, "import java.nio.charset.StandardCharsets; public class Utf8DecodeFixture {"
            + "private static String decode(String s) { byte[] b=s.getBytes(StandardCharsets.UTF_8);"
            + "for(int i=0;i<b.length;i++) b[i]=(byte)(b[i]^1); return new String(b,StandardCharsets.UTF_8); }"
            + "public static String value() { return decode(\"Ih\"); } }");
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "--release", "8", "-Xlint:-options",
            "-proc:none", "-d", directory.toString(), source.toString()));
        Path input = directory.resolve("Utf8DecodeFixture.class"), output = directory.resolve("utf8-decoded");
        byte[] original = Files.readAllBytes(input);
        var result = new TransformJob().run(input, output, 100, Duration.ofSeconds(20), "string-decryptor");
        assertTrue(result.successful(), result.toString()); assertEquals(1, result.changes());
        assertArrayEquals(original, Files.readAllBytes(input));
        String evidence = Files.readString(output.resolve("transform.json"));
        assertTrue(evidence.contains("string-decryptor-v1")); assertFalse(evidence.contains("Ih")); assertFalse(evidence.contains("Hi"));
        try (var zip = new ZipFile(output.resolve("worker/transformed/transformed.jar").toFile())) {
            var type = new org.objectweb.asm.tree.ClassNode();
            new org.objectweb.asm.ClassReader(zip.getInputStream(zip.getEntry("Utf8DecodeFixture.class")).readAllBytes()).accept(type, 0);
            var value = type.methods.stream().filter(method -> method.name.equals("value")).findFirst().orElseThrow();
            assertTrue(java.util.Arrays.stream(value.instructions.toArray()).anyMatch(node -> node instanceof org.objectweb.asm.tree.LdcInsnNode literal && "Hi".equals(literal.cst)));
            assertTrue(java.util.Arrays.stream(value.instructions.toArray()).anyMatch(node -> node instanceof org.objectweb.asm.tree.MethodInsnNode call
                && call.owner.equals("java/lang/String") && call.name.equals("<init>") && call.desc.equals("(Ljava/lang/String;)V")));
        }
    }
    @Test void decryptsBase64StringInWorkerWithoutLoggingDecodedText() throws Exception {
        Path source = directory.resolve("Base64DecodeFixture.java");
        Files.writeString(source, "import java.nio.charset.StandardCharsets; import java.util.Base64;"
            + "public class Base64DecodeFixture { private static String decode(String s) {"
            + "return new String(Base64.getDecoder().decode(s),StandardCharsets.UTF_8); }"
            + "public static String value() { return decode(\"SGVsbG8=\"); } }");
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "--release", "8", "-Xlint:-options",
            "-proc:none", "-d", directory.toString(), source.toString()));
        Path input = directory.resolve("Base64DecodeFixture.class"), output = directory.resolve("base64-decoded");
        byte[] original = Files.readAllBytes(input);
        var result = new TransformJob().run(input, output, 100, Duration.ofSeconds(20), "string-decryptor");
        assertTrue(result.successful(), result.toString()); assertEquals(1, result.changes());
        assertArrayEquals(original, Files.readAllBytes(input));
        String evidence = Files.readString(output.resolve("transform.json"));
        assertTrue(evidence.contains("string-decryptor-v1")); assertFalse(evidence.contains("SGVsbG8=")); assertFalse(evidence.contains("Hello"));
        try (var zip = new ZipFile(output.resolve("worker/transformed/transformed.jar").toFile())) {
            var type = new org.objectweb.asm.tree.ClassNode();
            new org.objectweb.asm.ClassReader(zip.getInputStream(zip.getEntry("Base64DecodeFixture.class")).readAllBytes()).accept(type, 0);
            var value = type.methods.stream().filter(method -> method.name.equals("value")).findFirst().orElseThrow();
            assertTrue(java.util.Arrays.stream(value.instructions.toArray()).anyMatch(node -> node instanceof org.objectweb.asm.tree.LdcInsnNode literal && "Hello".equals(literal.cst)));
            assertTrue(java.util.Arrays.stream(value.instructions.toArray()).anyMatch(node -> node instanceof org.objectweb.asm.tree.MethodInsnNode call
                && call.owner.equals("java/lang/String") && call.name.equals("<init>") && call.desc.equals("(Ljava/lang/String;)V")));
        }
    }
    @Test void decryptsUrlSafeBase64InWorkerWithoutLoggingDecodedText() throws Exception {
        Path source = directory.resolve("UrlBase64DecodeFixture.java");
        Files.writeString(source, "import java.nio.charset.StandardCharsets; import java.util.Base64;"
            + "public class UrlBase64DecodeFixture { private static String decode(String s) {"
            + "return new String(Base64.getUrlDecoder().decode(s),StandardCharsets.UTF_8); }"
            + "public static String value() { return decode(\"SGVsbG8_\"); } }");
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "--release", "8", "-Xlint:-options",
            "-proc:none", "-d", directory.toString(), source.toString()));
        Path input = directory.resolve("UrlBase64DecodeFixture.class"), output = directory.resolve("url-base64-decoded");
        byte[] original = Files.readAllBytes(input);
        var result = new TransformJob().run(input, output, 100, Duration.ofSeconds(20), "string-decryptor");
        assertTrue(result.successful(), result.toString()); assertEquals(1, result.changes());
        assertArrayEquals(original, Files.readAllBytes(input));
        String evidence = Files.readString(output.resolve("transform.json"));
        assertTrue(evidence.contains("string-decryptor-v1"));
        assertFalse(evidence.contains("SGVsbG8_")); assertFalse(evidence.contains("Hello?"));
        try (var zip = new ZipFile(output.resolve("worker/transformed/transformed.jar").toFile())) {
            var type = new org.objectweb.asm.tree.ClassNode();
            new org.objectweb.asm.ClassReader(zip.getInputStream(zip.getEntry("UrlBase64DecodeFixture.class")).readAllBytes()).accept(type, 0);
            var value = type.methods.stream().filter(method -> method.name.equals("value")).findFirst().orElseThrow();
            assertTrue(java.util.Arrays.stream(value.instructions.toArray()).anyMatch(node -> node instanceof org.objectweb.asm.tree.LdcInsnNode literal && "Hello?".equals(literal.cst)));
        }
    }
    @Test void decryptsLatin1ByteLoopInWorkerWithoutLoggingDecodedText() throws Exception {
        Path source = directory.resolve("Latin1DecodeFixture.java");
        Files.writeString(source, "import java.nio.charset.StandardCharsets; public class Latin1DecodeFixture {"
            + "private static String decode(String s) { byte[] b=s.getBytes(StandardCharsets.ISO_8859_1);"
            + "for(int i=0;i<b.length;i++) b[i]=(byte)(b[i]^1); return new String(b,StandardCharsets.ISO_8859_1); }"
            + "public static String value() { return decode(\"é\"); } }");
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "--release", "8", "-Xlint:-options",
            "-proc:none", "-d", directory.toString(), source.toString()));
        Path input = directory.resolve("Latin1DecodeFixture.class"), output = directory.resolve("latin1-decoded");
        byte[] original = Files.readAllBytes(input);
        var result = new TransformJob().run(input, output, 100, Duration.ofSeconds(20), "string-decryptor");
        assertTrue(result.successful(), result.toString()); assertEquals(1, result.changes());
        assertArrayEquals(original, Files.readAllBytes(input));
        String evidence = Files.readString(output.resolve("transform.json"));
        assertTrue(evidence.contains("string-decryptor-v1")); assertFalse(evidence.contains("é")); assertFalse(evidence.contains("è"));
        try (var zip = new ZipFile(output.resolve("worker/transformed/transformed.jar").toFile())) {
            var type = new org.objectweb.asm.tree.ClassNode();
            new org.objectweb.asm.ClassReader(zip.getInputStream(zip.getEntry("Latin1DecodeFixture.class")).readAllBytes()).accept(type, 0);
            var value = type.methods.stream().filter(method -> method.name.equals("value")).findFirst().orElseThrow();
            assertTrue(java.util.Arrays.stream(value.instructions.toArray()).anyMatch(node -> node instanceof org.objectweb.asm.tree.LdcInsnNode literal && "è".equals(literal.cst)));
        }
    }
    @Test void decryptsStringBuilderLoopInWorkerWithoutLoggingDecodedText() throws Exception {
        Path source = directory.resolve("StringBuilderDecodeFixture.java");
        Files.writeString(source, "public class StringBuilderDecodeFixture {"
            + "private static String decode(String s) { StringBuilder b=new StringBuilder(s.length());"
            + "for(int i=0;i<s.length();i++) b.append((char)(s.charAt(i)^7)); return b.toString(); }"
            + "public static String value() { return decode(\"Obkkh\"); } }");
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "--release", "8", "-Xlint:-options",
            "-proc:none", "-d", directory.toString(), source.toString()));
        Path input = directory.resolve("StringBuilderDecodeFixture.class"), output = directory.resolve("builder-decoded");
        byte[] original = Files.readAllBytes(input);
        var result = new TransformJob().run(input, output, 100, Duration.ofSeconds(20), "string-decryptor");
        assertTrue(result.successful(), result.toString()); assertEquals(1, result.changes());
        assertArrayEquals(original, Files.readAllBytes(input));
        String evidence = Files.readString(output.resolve("transform.json"));
        assertTrue(evidence.contains("string-decryptor-v1")); assertFalse(evidence.contains("Obkkh")); assertFalse(evidence.contains("Hello"));
        try (var zip = new ZipFile(output.resolve("worker/transformed/transformed.jar").toFile())) {
            var type = new org.objectweb.asm.tree.ClassNode();
            new org.objectweb.asm.ClassReader(zip.getInputStream(zip.getEntry("StringBuilderDecodeFixture.class")).readAllBytes()).accept(type, 0);
            var value = type.methods.stream().filter(method -> method.name.equals("value")).findFirst().orElseThrow();
            assertTrue(java.util.Arrays.stream(value.instructions.toArray()).anyMatch(node -> node instanceof org.objectweb.asm.tree.LdcInsnNode literal && "Hello".equals(literal.cst)));
            assertTrue(java.util.Arrays.stream(value.instructions.toArray()).anyMatch(node -> node instanceof org.objectweb.asm.tree.MethodInsnNode call
                && call.owner.equals("java/lang/String") && call.name.equals("<init>") && call.desc.equals("(Ljava/lang/String;)V")));
        }
    }
    @Test void decryptsStringBuilderReverseInWorkerWithoutLoggingDecodedText() throws Exception {
        Path source = directory.resolve("StringBuilderReverseFixture.java");
        Files.writeString(source, "public class StringBuilderReverseFixture {"
            + "private static String decode(String s) { return new StringBuilder().append(s).reverse().toString(); }"
            + "public static String value() { return decode(\"olleH\"); } }");
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "--release", "8", "-Xlint:-options",
            "-proc:none", "-d", directory.toString(), source.toString()));
        Path input = directory.resolve("StringBuilderReverseFixture.class"), output = directory.resolve("builder-reverse");
        byte[] original = Files.readAllBytes(input);
        var result = new TransformJob().run(input, output, 100, Duration.ofSeconds(20), "string-decryptor");
        assertTrue(result.successful(), result.toString()); assertEquals(1, result.changes());
        assertArrayEquals(original, Files.readAllBytes(input));
        String evidence = Files.readString(output.resolve("transform.json"));
        assertTrue(evidence.contains("string-decryptor-v1")); assertFalse(evidence.contains("olleH")); assertFalse(evidence.contains("Hello"));
        try (var zip = new ZipFile(output.resolve("worker/transformed/transformed.jar").toFile())) {
            var type = new org.objectweb.asm.tree.ClassNode();
            new org.objectweb.asm.ClassReader(zip.getInputStream(zip.getEntry("StringBuilderReverseFixture.class")).readAllBytes()).accept(type, 0);
            var value = type.methods.stream().filter(method -> method.name.equals("value")).findFirst().orElseThrow();
            assertTrue(java.util.Arrays.stream(value.instructions.toArray()).anyMatch(node -> node instanceof org.objectweb.asm.tree.LdcInsnNode literal && "Hello".equals(literal.cst)));
        }
    }
    @Test void decryptsStringBuilderStringConstructorInWorkerWithoutLoggingDecodedText() throws Exception {
        Path source = directory.resolve("StringBuilderStringConstructorFixture.java");
        Files.writeString(source, "public class StringBuilderStringConstructorFixture {"
            + "private static String decode(String s) { return new StringBuilder(s).reverse().toString(); }"
            + "public static String value() { return decode(\"olleH\"); } }");
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "--release", "8", "-Xlint:-options",
            "-proc:none", "-d", directory.toString(), source.toString()));
        Path input = directory.resolve("StringBuilderStringConstructorFixture.class"), output = directory.resolve("builder-string-constructor");
        byte[] original = Files.readAllBytes(input);
        var result = new TransformJob().run(input, output, 100, Duration.ofSeconds(20), "string-decryptor");
        assertTrue(result.successful(), result.toString()); assertEquals(1, result.changes());
        assertArrayEquals(original, Files.readAllBytes(input));
        String evidence = Files.readString(output.resolve("transform.json"));
        assertTrue(evidence.contains("string-decryptor-v1")); assertFalse(evidence.contains("olleH")); assertFalse(evidence.contains("Hello"));
        try (var zip = new ZipFile(output.resolve("worker/transformed/transformed.jar").toFile())) {
            var type = new org.objectweb.asm.tree.ClassNode();
            new org.objectweb.asm.ClassReader(zip.getInputStream(zip.getEntry("StringBuilderStringConstructorFixture.class")).readAllBytes()).accept(type, 0);
            var value = type.methods.stream().filter(method -> method.name.equals("value")).findFirst().orElseThrow();
            assertTrue(java.util.Arrays.stream(value.instructions.toArray()).anyMatch(node -> node instanceof org.objectweb.asm.tree.LdcInsnNode literal && "Hello".equals(literal.cst)));
        }
    }
    @Test void decryptsStringCharacterReplacementInWorkerWithoutLoggingDecodedText() throws Exception {
        Path source = directory.resolve("StringCharacterReplaceFixture.java");
        Files.writeString(source, "public class StringCharacterReplaceFixture {"
            + "private static String decode(String s) { return s.replace('x','H'); }"
            + "public static String value() { return decode(\"xello\"); } }");
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "--release", "8", "-Xlint:-options",
            "-proc:none", "-d", directory.toString(), source.toString()));
        Path input = directory.resolve("StringCharacterReplaceFixture.class"), output = directory.resolve("char-replace-decoded");
        byte[] original = Files.readAllBytes(input);
        var result = new TransformJob().run(input, output, 100, Duration.ofSeconds(20), "string-decryptor");
        assertTrue(result.successful(), result.toString()); assertEquals(1, result.changes());
        assertArrayEquals(original, Files.readAllBytes(input));
        String evidence = Files.readString(output.resolve("transform.json"));
        assertTrue(evidence.contains("string-decryptor-v1")); assertFalse(evidence.contains("xello")); assertFalse(evidence.contains("Hello"));
        try (var zip = new ZipFile(output.resolve("worker/transformed/transformed.jar").toFile())) {
            var type = new org.objectweb.asm.tree.ClassNode();
            new org.objectweb.asm.ClassReader(zip.getInputStream(zip.getEntry("StringCharacterReplaceFixture.class")).readAllBytes()).accept(type, 0);
            var value = type.methods.stream().filter(method -> method.name.equals("value")).findFirst().orElseThrow();
            assertTrue(java.util.Arrays.stream(value.instructions.toArray()).anyMatch(node -> node instanceof org.objectweb.asm.tree.LdcInsnNode literal && "Hello".equals(literal.cst)));
        }
    }
    @Test void decryptsStringSubstringInWorkerWithoutLoggingDecodedText() throws Exception {
        Path source = directory.resolve("StringSubstringFixture.java");
        Files.writeString(source, "public class StringSubstringFixture {"
            + "private static String decode(String s) { return s.substring(1,s.length()-1); }"
            + "public static String value() { return decode(\"xHello!\"); } }");
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "--release", "8", "-Xlint:-options",
            "-proc:none", "-d", directory.toString(), source.toString()));
        Path input = directory.resolve("StringSubstringFixture.class"), output = directory.resolve("substring-decoded");
        byte[] original = Files.readAllBytes(input);
        var result = new TransformJob().run(input, output, 100, Duration.ofSeconds(20), "string-decryptor");
        assertTrue(result.successful(), result.toString()); assertEquals(1, result.changes());
        assertArrayEquals(original, Files.readAllBytes(input));
        String evidence = Files.readString(output.resolve("transform.json"));
        assertTrue(evidence.contains("string-decryptor-v1")); assertFalse(evidence.contains("xHello!")); assertFalse(evidence.contains("Hello"));
        try (var zip = new ZipFile(output.resolve("worker/transformed/transformed.jar").toFile())) {
            var type = new org.objectweb.asm.tree.ClassNode();
            new org.objectweb.asm.ClassReader(zip.getInputStream(zip.getEntry("StringSubstringFixture.class")).readAllBytes()).accept(type, 0);
            var value = type.methods.stream().filter(method -> method.name.equals("value")).findFirst().orElseThrow();
            assertTrue(java.util.Arrays.stream(value.instructions.toArray()).anyMatch(node -> node instanceof org.objectweb.asm.tree.LdcInsnNode literal && "Hello".equals(literal.cst)));
        }
    }
    @Test void decryptsStringBuilderCharacterMutationInWorkerWithoutLoggingDecodedText() throws Exception {
        Path source = directory.resolve("StringBuilderCharMutationFixture.java");
        Files.writeString(source, "public class StringBuilderCharMutationFixture {"
            + "private static String decode(String s) { StringBuilder b=new StringBuilder(s);"
            + "for(int i=0;i<b.length();i++) b.setCharAt(i,(char)(b.charAt(i)^7)); return b.toString(); }"
            + "public static String value() { return decode(\"Obkkh\"); } }");
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "--release", "8", "-Xlint:-options",
            "-proc:none", "-d", directory.toString(), source.toString()));
        Path input = directory.resolve("StringBuilderCharMutationFixture.class"), output = directory.resolve("builder-char-mutation");
        byte[] original = Files.readAllBytes(input);
        var result = new TransformJob().run(input, output, 100, Duration.ofSeconds(20), "string-decryptor");
        assertTrue(result.successful(), result.toString()); assertEquals(1, result.changes());
        assertArrayEquals(original, Files.readAllBytes(input));
        String evidence = Files.readString(output.resolve("transform.json"));
        assertTrue(evidence.contains("string-decryptor-v1")); assertFalse(evidence.contains("Obkkh")); assertFalse(evidence.contains("Hello"));
        try (var zip = new ZipFile(output.resolve("worker/transformed/transformed.jar").toFile())) {
            var type = new org.objectweb.asm.tree.ClassNode();
            new org.objectweb.asm.ClassReader(zip.getInputStream(zip.getEntry("StringBuilderCharMutationFixture.class")).readAllBytes()).accept(type, 0);
            var value = type.methods.stream().filter(method -> method.name.equals("value")).findFirst().orElseThrow();
            assertTrue(java.util.Arrays.stream(value.instructions.toArray()).anyMatch(node -> node instanceof org.objectweb.asm.tree.LdcInsnNode literal && "Hello".equals(literal.cst)));
        }
    }
    @Test void decryptsStringBuilderInsertDeleteInWorkerWithoutLoggingDecodedText() throws Exception {
        Path source = directory.resolve("StringBuilderInsertDeleteFixture.java");
        Files.writeString(source, "public class StringBuilderInsertDeleteFixture {"
            + "private static String decode(String s) { StringBuilder b=new StringBuilder(s);"
            + "b.deleteCharAt(1); b.insert(1,'e'); return b.toString(); }"
            + "public static String value() { return decode(\"Hlllo\"); } }");
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "--release", "8", "-Xlint:-options",
            "-proc:none", "-d", directory.toString(), source.toString()));
        Path input = directory.resolve("StringBuilderInsertDeleteFixture.class"), output = directory.resolve("builder-insert-delete");
        byte[] original = Files.readAllBytes(input);
        var result = new TransformJob().run(input, output, 100, Duration.ofSeconds(20), "string-decryptor");
        assertTrue(result.successful(), result.toString()); assertEquals(1, result.changes());
        assertArrayEquals(original, Files.readAllBytes(input));
        String evidence = Files.readString(output.resolve("transform.json"));
        assertTrue(evidence.contains("string-decryptor-v1")); assertFalse(evidence.contains("Hlllo")); assertFalse(evidence.contains("Hello"));
        try (var zip = new ZipFile(output.resolve("worker/transformed/transformed.jar").toFile())) {
            var type = new org.objectweb.asm.tree.ClassNode();
            new org.objectweb.asm.ClassReader(zip.getInputStream(zip.getEntry("StringBuilderInsertDeleteFixture.class")).readAllBytes()).accept(type, 0);
            var value = type.methods.stream().filter(method -> method.name.equals("value")).findFirst().orElseThrow();
            assertTrue(java.util.Arrays.stream(value.instructions.toArray()).anyMatch(node -> node instanceof org.objectweb.asm.tree.LdcInsnNode literal && "Hello".equals(literal.cst)));
        }
    }
    @Test void decryptsStringBuilderSetLengthInWorkerWithoutLoggingDecodedText() throws Exception {
        Path source = directory.resolve("StringBuilderSetLengthFixture.java");
        Files.writeString(source, "public class StringBuilderSetLengthFixture {"
            + "private static String decode(String s) { StringBuilder b=new StringBuilder(s);"
            + "b.setLength(b.length()-1); return b.toString(); }"
            + "public static String value() { return decode(\"Hellox\"); } }");
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "--release", "8", "-Xlint:-options",
            "-proc:none", "-d", directory.toString(), source.toString()));
        Path input = directory.resolve("StringBuilderSetLengthFixture.class"), output = directory.resolve("builder-set-length");
        byte[] original = Files.readAllBytes(input);
        var result = new TransformJob().run(input, output, 100, Duration.ofSeconds(20), "string-decryptor");
        assertTrue(result.successful(), result.toString()); assertEquals(1, result.changes());
        assertArrayEquals(original, Files.readAllBytes(input));
        String evidence = Files.readString(output.resolve("transform.json"));
        assertTrue(evidence.contains("string-decryptor-v1")); assertFalse(evidence.contains("Hellox")); assertFalse(evidence.contains("Hello"));
        try (var zip = new ZipFile(output.resolve("worker/transformed/transformed.jar").toFile())) {
            var type = new org.objectweb.asm.tree.ClassNode();
            new org.objectweb.asm.ClassReader(zip.getInputStream(zip.getEntry("StringBuilderSetLengthFixture.class")).readAllBytes()).accept(type, 0);
            var value = type.methods.stream().filter(method -> method.name.equals("value")).findFirst().orElseThrow();
            assertTrue(java.util.Arrays.stream(value.instructions.toArray()).anyMatch(node -> node instanceof org.objectweb.asm.tree.LdcInsnNode literal && "Hello".equals(literal.cst)));
        }
    }
    @Test void decryptsIntegerRotationInWorkerWithoutLoggingDecodedText() throws Exception {
        Path source = directory.resolve("IntegerRotationFixture.java");
        Files.writeString(source, "public class IntegerRotationFixture {"
            + "private static String decode(String s) { char[] c=s.toCharArray();"
            + "for(int i=0;i<c.length;i++) c[i]=(char)Integer.rotateLeft(s.charAt(i),5); return new String(c); }"
            + "public static String value() { return decode(\"RotateInput\"); } }");
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "--release", "8", "-Xlint:-options",
            "-proc:none", "-d", directory.toString(), source.toString()));
        Path input = directory.resolve("IntegerRotationFixture.class"), output = directory.resolve("integer-rotation");
        byte[] original = Files.readAllBytes(input);
        var result = new TransformJob().run(input, output, 100, Duration.ofSeconds(20), "string-decryptor");
        assertTrue(result.successful(), result.toString()); assertEquals(1, result.changes());
        assertArrayEquals(original, Files.readAllBytes(input));
        String evidence = Files.readString(output.resolve("transform.json"));
        assertTrue(evidence.contains("string-decryptor-v1")); assertFalse(evidence.contains("RotateInput"));
        StringBuilder expected = new StringBuilder();
        for (char value : "RotateInput".toCharArray()) expected.append((char)Integer.rotateLeft(value, 5));
        assertFalse(evidence.contains(expected.toString()));
        try (var zip = new ZipFile(output.resolve("worker/transformed/transformed.jar").toFile())) {
            var type = new org.objectweb.asm.tree.ClassNode();
            new org.objectweb.asm.ClassReader(zip.getInputStream(zip.getEntry("IntegerRotationFixture.class")).readAllBytes()).accept(type, 0);
            var value = type.methods.stream().filter(method -> method.name.equals("value")).findFirst().orElseThrow();
            assertTrue(java.util.Arrays.stream(value.instructions.toArray()).anyMatch(node -> node instanceof org.objectweb.asm.tree.LdcInsnNode literal && expected.toString().equals(literal.cst)));
        }
    }
    @Test void decryptsIntegerBitReversalsInWorkerWithoutLoggingDecodedText() throws Exception {
        Path source = directory.resolve("IntegerBitReverseFixture.java");
        Files.writeString(source, "public class IntegerBitReverseFixture {"
            + "private static String decodeBits(String s) { char[] c=s.toCharArray();"
            + "for(int i=0;i<c.length;i++) c[i]=(char)Integer.reverse(s.charAt(i)<<16); return new String(c); }"
            + "private static String decodeBytes(String s) { char[] c=s.toCharArray();"
            + "for(int i=0;i<c.length;i++) c[i]=(char)Integer.reverseBytes(s.charAt(i)<<16); return new String(c); }"
            + "public static String bits() { return decodeBits(\"Masking\"); }"
            + "public static String bytes() { return decodeBytes(\"Masking\"); } }");
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "--release", "8", "-Xlint:-options",
            "-proc:none", "-d", directory.toString(), source.toString()));
        Path input = directory.resolve("IntegerBitReverseFixture.class"), output = directory.resolve("integer-bit-reversal");
        byte[] original = Files.readAllBytes(input);
        var result = new TransformJob().run(input, output, 100, Duration.ofSeconds(20), "string-decryptor");
        assertTrue(result.successful(), result.toString()); assertEquals(2, result.changes());
        assertArrayEquals(original, Files.readAllBytes(input));
        String evidence = Files.readString(output.resolve("transform.json"));
        assertTrue(evidence.contains("string-decryptor-v1")); assertFalse(evidence.contains("Masking"));
        try (var zip = new ZipFile(output.resolve("worker/transformed/transformed.jar").toFile())) {
            var type = new org.objectweb.asm.tree.ClassNode();
            new org.objectweb.asm.ClassReader(zip.getInputStream(zip.getEntry("IntegerBitReverseFixture.class")).readAllBytes()).accept(type, 0);
            StringBuilder expectedBits = new StringBuilder(), expectedBytes = new StringBuilder();
            for (char value : "Masking".toCharArray()) {
                expectedBits.append((char)Integer.reverse(value << 16));
                expectedBytes.append((char)Integer.reverseBytes(value << 16));
            }
            for (var expected : java.util.Map.of("bits", expectedBits.toString(), "bytes", expectedBytes.toString()).entrySet()) {
                var method = type.methods.stream().filter(value -> value.name.equals(expected.getKey())).findFirst().orElseThrow();
                assertTrue(java.util.Arrays.stream(method.instructions.toArray()).anyMatch(node -> node instanceof org.objectweb.asm.tree.LdcInsnNode literal && expected.getValue().equals(literal.cst)), expected.getKey());
                assertFalse(evidence.contains(expected.getValue()));
            }
        }
    }
    @Test void slicesBase64BytesInWorkerWithoutLoggingDecodedText() throws Exception {
        Path source = directory.resolve("Base64ArraySliceFixture.java");
        Files.writeString(source, "import java.nio.charset.StandardCharsets; import java.util.Arrays; import java.util.Base64;"
            + "public class Base64ArraySliceFixture { private static String decode(String s) {"
            + "byte[] b=Base64.getDecoder().decode(s); byte[] middle=Arrays.copyOfRange(b,1,b.length);"
            + "byte[] text=Arrays.copyOf(middle,middle.length-1); return new String(text,StandardCharsets.UTF_8); }"
            + "public static String value() { return decode(\"eEhlbGxvWQ==\"); } }");
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "--release", "8", "-Xlint:-options",
            "-proc:none", "-d", directory.toString(), source.toString()));
        Path input = directory.resolve("Base64ArraySliceFixture.class"), output = directory.resolve("base64-array-slice");
        byte[] original = Files.readAllBytes(input);
        var result = new TransformJob().run(input, output, 100, Duration.ofSeconds(20), "string-decryptor");
        assertTrue(result.successful(), result.toString()); assertEquals(1, result.changes());
        assertArrayEquals(original, Files.readAllBytes(input));
        String evidence = Files.readString(output.resolve("transform.json"));
        assertTrue(evidence.contains("string-decryptor-v1")); assertFalse(evidence.contains("eEhlbGxvWQ==")); assertFalse(evidence.contains("Hello"));
        try (var zip = new ZipFile(output.resolve("worker/transformed/transformed.jar").toFile())) {
            var type = new org.objectweb.asm.tree.ClassNode();
            new org.objectweb.asm.ClassReader(zip.getInputStream(zip.getEntry("Base64ArraySliceFixture.class")).readAllBytes()).accept(type, 0);
            var value = type.methods.stream().filter(method -> method.name.equals("value")).findFirst().orElseThrow();
            assertTrue(java.util.Arrays.stream(value.instructions.toArray()).anyMatch(node -> node instanceof org.objectweb.asm.tree.LdcInsnNode literal && "Hello".equals(literal.cst)));
        }
    }
    @Test void decryptsSameClassHelperInWorkerWithoutLoggingDecodedText() throws Exception {
        Path source = directory.resolve("StringHelperFixture.java");
        Files.writeString(source, "public class StringHelperFixture {"
            + "private static String decode(String s) { return helper(s); }"
            + "private static String helper(String s) { char[] c=s.toCharArray();"
            + "for(int i=0;i<c.length;i++) c[i]=(char)(c[i]^7); return new String(c); }"
            + "public static String value() { return decode(\"Obkkh\"); } }");
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "--release", "8", "-Xlint:-options",
            "-proc:none", "-d", directory.toString(), source.toString()));
        Path input = directory.resolve("StringHelperFixture.class"), output = directory.resolve("helper-decoded");
        byte[] original = Files.readAllBytes(input);
        var result = new TransformJob().run(input, output, 100, Duration.ofSeconds(20), "string-decryptor");
        assertTrue(result.successful(), result.toString()); assertEquals(1, result.changes());
        assertArrayEquals(original, Files.readAllBytes(input));
        String evidence = Files.readString(output.resolve("transform.json"));
        assertTrue(evidence.contains("string-decryptor-v1")); assertFalse(evidence.contains("Obkkh")); assertFalse(evidence.contains("Hello"));
        try (var zip = new ZipFile(output.resolve("worker/transformed/transformed.jar").toFile())) {
            var type = new org.objectweb.asm.tree.ClassNode();
            new org.objectweb.asm.ClassReader(zip.getInputStream(zip.getEntry("StringHelperFixture.class")).readAllBytes()).accept(type, 0);
            var value = type.methods.stream().filter(method -> method.name.equals("value")).findFirst().orElseThrow();
            assertTrue(java.util.Arrays.stream(value.instructions.toArray()).anyMatch(node -> node instanceof org.objectweb.asm.tree.LdcInsnNode literal && "Hello".equals(literal.cst)));
        }
    }
    @Test void decryptsStringValueOfCharArrayInWorkerWithoutLoggingDecodedText() throws Exception {
        Path source = directory.resolve("StringValueOfFixture.java");
        Files.writeString(source, "public class StringValueOfFixture {"
            + "private static String decode(String s) { char[] c=s.toCharArray();"
            + "for(int i=0;i<c.length;i++) c[i]=(char)(c[i]^7); return String.valueOf(c); }"
            + "public static String value() { return decode(\"Obkkh\"); } }");
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "--release", "8", "-Xlint:-options",
            "-proc:none", "-d", directory.toString(), source.toString()));
        Path input = directory.resolve("StringValueOfFixture.class"), output = directory.resolve("value-of-decoded");
        byte[] original = Files.readAllBytes(input);
        var result = new TransformJob().run(input, output, 100, Duration.ofSeconds(20), "string-decryptor");
        assertTrue(result.successful(), result.toString()); assertEquals(1, result.changes());
        assertArrayEquals(original, Files.readAllBytes(input));
        String evidence = Files.readString(output.resolve("transform.json"));
        assertTrue(evidence.contains("string-decryptor-v1")); assertFalse(evidence.contains("Obkkh")); assertFalse(evidence.contains("Hello"));
        try (var zip = new ZipFile(output.resolve("worker/transformed/transformed.jar").toFile())) {
            var type = new org.objectweb.asm.tree.ClassNode();
            new org.objectweb.asm.ClassReader(zip.getInputStream(zip.getEntry("StringValueOfFixture.class")).readAllBytes()).accept(type, 0);
            var value = type.methods.stream().filter(method -> method.name.equals("value")).findFirst().orElseThrow();
            assertTrue(java.util.Arrays.stream(value.instructions.toArray()).anyMatch(node -> node instanceof org.objectweb.asm.tree.LdcInsnNode literal && "Hello".equals(literal.cst)));
            assertTrue(java.util.Arrays.stream(value.instructions.toArray()).anyMatch(node -> node instanceof org.objectweb.asm.tree.MethodInsnNode call
                && call.owner.equals("java/lang/String") && call.name.equals("<init>") && call.desc.equals("(Ljava/lang/String;)V")));
        }
    }
    @Test void runsExplicitUnaryPassInWorker() throws Exception {
        var writer = new org.objectweb.asm.ClassWriter(0);
        writer.visit(52,1,"Unary",null,"java/lang/Object",null);
        var method = writer.visitMethod(9,"value","()I",null,null);
        method.visitCode(); method.visitLdcInsn(255); method.visitInsn(145); method.visitInsn(172);
        method.visitMaxs(1,0); method.visitEnd(); writer.visitEnd();
        Path input = directory.resolve("Unary.class"); Files.write(input,writer.toByteArray());
        Path output = directory.resolve("unary-result");
        var result = new TransformJob().run(input,output,100,Duration.ofSeconds(20),"integer-unary");
        assertTrue(result.successful()); assertEquals(1,result.changes());
        String report = Files.readString(output.resolve("transform.json"));
        assertTrue(report.contains("integer-unary-v1")); assertTrue(report.contains("\"result\":-1"));
    }
    @Test void foldsConstantConditionalInWorkerAndRecordsDecision() throws Exception {
        // Version 52 requires stack map frames; a frameless fixture would be refused as malformed.
        var writer = new org.objectweb.asm.ClassWriter(org.objectweb.asm.ClassWriter.COMPUTE_FRAMES);
        writer.visit(52, 1, "BranchFixture", null, "java/lang/Object", null);
        var method = writer.visitMethod(9, "value", "()I", null, null);
        var target = new org.objectweb.asm.Label();
        method.visitCode(); method.visitInsn(5); method.visitInsn(6); method.visitJumpInsn(161, target);
        method.visitIntInsn(16, 10); method.visitInsn(172);
        method.visitLabel(target); method.visitIntInsn(16, 20); method.visitInsn(172);
        method.visitMaxs(2, 0); method.visitEnd(); writer.visitEnd();
        Path input = directory.resolve("BranchFixture.class"); Files.write(input, writer.toByteArray());
        Path output = directory.resolve("branch-result");
        var result = new TransformJob().run(input, output, 100, Duration.ofSeconds(20), "integer-branches");
        assertTrue(result.successful(), result.toString()); assertEquals(1, result.changes());
        String evidence = Files.readString(output.resolve("transform.json"));
        assertTrue(evidence.contains("integer-branches-v2")); assertTrue(evidence.contains("\"result\":1"));
        try (var zip = new ZipFile(output.resolve("worker/transformed/transformed.jar").toFile())) {
            var type = new org.objectweb.asm.tree.ClassNode();
            new org.objectweb.asm.ClassReader(zip.getInputStream(zip.getEntry("BranchFixture.class")).readAllBytes()).accept(type, 0);
            assertFalse(java.util.Arrays.stream(type.methods.getFirst().instructions.toArray()).anyMatch(node -> node.getOpcode() == 161));
            assertTrue(java.util.Arrays.stream(type.methods.getFirst().instructions.toArray()).anyMatch(node -> node.getOpcode() == 167));
        }
    }
    @Test void acceptsValidNonJavaIdentifierClassNames() throws Exception {
        var writer = new org.objectweb.asm.ClassWriter(0);
        writer.visit(52, 1, "pkg/1-name", null, "java/lang/Object", null); writer.visitEnd();
        Path input = directory.resolve("odd.class"); Files.write(input, writer.toByteArray());
        assertTrue(new TransformJob().run(input, directory.resolve("odd-output"), 10, Duration.ofSeconds(20)).successful());
    }
    @Test void foldsRealBytecodeAndRecordsOperands() throws Exception {
        var writer = new org.objectweb.asm.ClassWriter(0);
        writer.visit(52, 1, "Arithmetic", null, "java/lang/Object", null);
        var method = writer.visitMethod(9, "value", "()I", null, null);
        method.visitCode(); method.visitInsn(5); method.visitInsn(6); method.visitInsn(96);
        method.visitInsn(172); method.visitMaxs(2, 0); method.visitEnd(); writer.visitEnd();
        Path input = directory.resolve("Arithmetic.class"); Files.write(input, writer.toByteArray());
        Path output = directory.resolve("folded");
        var result = new TransformJob().run(input, output, 1, Duration.ofSeconds(20));
        assertTrue(result.successful()); assertEquals(1, result.changes()); assertTrue(result.limitReached());
        String evidence = Files.readString(output.resolve("transform.json"));
        assertTrue(evidence.contains("\"left\":2")); assertTrue(evidence.contains("\"right\":3"));
        assertTrue(evidence.contains("\"result\":5"));
        try (var zip = new ZipFile(output.resolve("worker/transformed/transformed.jar").toFile())) {
            byte[] bytes = zip.getInputStream(zip.getEntry("Arithmetic.class")).readAllBytes();
            assertFalse(java.util.Arrays.equals(writer.toByteArray(), bytes));
        }
    }
    Path fixture(boolean signed) throws Exception {
        Path source=directory.resolve("Example.java");
        Files.writeString(source,"public class Example { public int value(int x) { return x + 1; } }");
        assertEquals(0,ToolProvider.getSystemJavaCompiler().run(null,null,null,"--release","8","-proc:none",source.toString()));
        Path jar=directory.resolve("input.jar");
        try(var out=new ZipOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new ZipEntry("Example.class")); out.write(Files.readAllBytes(directory.resolve("Example.class"))); out.closeEntry();
            out.putNextEntry(new ZipEntry("asset.txt")); out.write(new byte[]{0,1,2,3}); out.closeEntry();
            if(signed) { out.putNextEntry(new ZipEntry("META-INF/TEST.SF")); out.write(new byte[]{1}); out.closeEntry(); }
        }
        return jar;
    }
    @Test void preservesResourcesAndOriginalAndProducesEvidence() throws Exception {
        Path input=fixture(false); byte[] before=Files.readAllBytes(input); Path output=directory.resolve("result");
        var result=new TransformJob().run(input,output,100,Duration.ofSeconds(20));
        assertTrue(result.successful(),result.toString());
        assertArrayEquals(before,Files.readAllBytes(input));
        try(var zip=new ZipFile(output.resolve("worker/transformed/transformed.jar").toFile())) {
            assertArrayEquals(new byte[]{0,1,2,3},zip.getInputStream(zip.getEntry("asset.txt")).readAllBytes());
        }
        assertTrue(Files.readString(output.resolve("transform.json")).contains("NOT_VERIFIED"));
        assertThrows(IOException.class,()->new TransformJob().run(input,output,100,Duration.ofSeconds(20)));
    }
    @Test void rejectsSignedJarWithoutRewritingIt() throws Exception {
        Path input=fixture(true); Path output=directory.resolve("result");
        assertThrows(IOException.class,()->new TransformJob().run(input,output,100,Duration.ofSeconds(20)));
        assertFalse(Files.exists(output.resolve("worker")));
        String failure = Files.readString(output.resolve("transform.json"));
        assertTrue(failure.contains("FAILED")); assertTrue(failure.contains("input_sha256"));
    }
    @Test void acceptsStandaloneClassAndRejectsBadBudget() throws Exception {
        fixture(false); Path input=directory.resolve("Example.class");
        assertThrows(IllegalArgumentException.class,()->new TransformJob().run(input,directory.resolve("bad"),0,Duration.ofSeconds(20)));
        assertFalse(Files.exists(directory.resolve("bad")));
        assertTrue(new TransformJob().run(input,directory.resolve("result"),100,Duration.ofSeconds(20)).successful());
    }
}
