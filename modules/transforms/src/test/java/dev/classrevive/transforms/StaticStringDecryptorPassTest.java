package dev.classrevive.transforms;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import javax.tools.ToolProvider;
import java.nio.file.*;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

class StaticStringDecryptorPassTest {
    @TempDir Path directory;

    @Test void registersAsAnOptInPass() {
        assertEquals("string-decryptor-v1", BuiltinPasses.select("string-decryptor").getFirst().id());
        assertEquals(BuiltinPasses.DEFAULT, "integer-constants");
    }

    @Test void decodesPureCharArrayXorLoopWithoutLoggingPlaintext() throws Exception {
        byte[] input = compile("DecodeFixture", "public class DecodeFixture {"
            + "private static String decode(String s) { char[] c=s.toCharArray();"
            + "for(int i=0;i<c.length;i++) c[i]=(char)(c[i]^7); return new String(c); }"
            + "public static String value() { return decode(\"Obkkh\"); } }");
        var result = decrypt(input, 10);
        assertEquals(1, result.changes().size());
        assertEquals("string-decryptor-v1", result.changes().getFirst().pass());
        assertFalse(result.changes().toString().contains("Hello"));
        assertFalse(result.changes().toString().contains("Obkkh"));
        var type = new ClassNode(); new ClassReader(result.bytes()).accept(type, 0);
        var value = type.methods.stream().filter(method -> method.name.equals("value")).findFirst().orElseThrow();
        assertTrue(Arrays.stream(value.instructions.toArray()).anyMatch(node -> node instanceof LdcInsnNode literal && "Hello".equals(literal.cst)));
        assertTrue(Arrays.stream(value.instructions.toArray()).anyMatch(node -> node instanceof TypeInsnNode allocation && allocation.getOpcode() == NEW && allocation.desc.equals("java/lang/String")));
        assertTrue(Arrays.stream(value.instructions.toArray()).anyMatch(node -> node instanceof MethodInsnNode constructor
            && constructor.owner.equals("java/lang/String") && constructor.name.equals("<init>") && constructor.desc.equals("(Ljava/lang/String;)V")));
        assertFalse(Arrays.stream(value.instructions.toArray()).anyMatch(node -> node instanceof MethodInsnNode call && call.name.equals("decode")));
    }

    @Test void doesNotAddFreshAllocationWhenDecoderReturnsItsInput() throws Exception {
        byte[] input = compile("IdentityFixture", "public class IdentityFixture {"
            + "private static String decode(String s) { return s; }"
            + "public static String value() { return decode(\"same\"); } }");
        var result = decrypt(input, 10);
        var type = new ClassNode(); new ClassReader(result.bytes()).accept(type, 0);
        var value = type.methods.stream().filter(method -> method.name.equals("value")).findFirst().orElseThrow();
        assertTrue(Arrays.stream(value.instructions.toArray()).anyMatch(node -> node instanceof LdcInsnNode literal && "same".equals(literal.cst)));
        assertFalse(Arrays.stream(value.instructions.toArray()).anyMatch(node -> node instanceof TypeInsnNode allocation && allocation.getOpcode() == NEW && allocation.desc.equals("java/lang/String")));
    }

    @Test void decodesUtf8ByteArrayXorLoop() throws Exception {
        byte[] input = compile("ByteDecodeFixture", "import java.nio.charset.StandardCharsets;"
            + "public class ByteDecodeFixture { private static String decode(String s) {"
            + "byte[] b=s.getBytes(StandardCharsets.UTF_8); for(int i=0;i<b.length;i++) b[i]=(byte)(b[i]^1);"
            + "return new String(b,StandardCharsets.UTF_8); }"
            + "public static String value() { return decode(\"Ih\"); } }");
        var result = decrypt(input, 10);
        assertEquals(1, result.changes().size());
        assertFalse(result.changes().toString().contains("Ih")); assertFalse(result.changes().toString().contains("Hi"));
        var type = new ClassNode(); new ClassReader(result.bytes()).accept(type, 0);
        var value = type.methods.stream().filter(method -> method.name.equals("value")).findFirst().orElseThrow();
        assertTrue(Arrays.stream(value.instructions.toArray()).anyMatch(node -> node instanceof LdcInsnNode literal && "Hi".equals(literal.cst)));
        assertTrue(Arrays.stream(value.instructions.toArray()).anyMatch(node -> node instanceof MethodInsnNode constructor
            && constructor.name.equals("<init>") && constructor.desc.equals("(Ljava/lang/String;)V")));
    }

    @Test void evaluatesStringCharAtArgumentsInJvmStackOrder() throws Exception {
        byte[] input = compile("CharAtDecodeFixture", "public class CharAtDecodeFixture {"
            + "private static String decode(String s) { char[] c=new char[s.length()];"
            + "for(int i=0;i<c.length;i++) c[i]=(char)(s.charAt(i)^7); return new String(c); }"
            + "public static String value() { return decode(\"Obkkh\"); } }");
        var result = decrypt(input, 10);
        assertEquals(1, result.changes().size());
        var type = new ClassNode(); new ClassReader(result.bytes()).accept(type, 0);
        var value = type.methods.stream().filter(method -> method.name.equals("value")).findFirst().orElseThrow();
        assertTrue(Arrays.stream(value.instructions.toArray()).anyMatch(node -> node instanceof LdcInsnNode literal && "Hello".equals(literal.cst)));
    }

    @Test void decodesSeveralExplicitStandardCharsetsWithoutInvokingTarget() throws Exception {
        assertDecodesCharset("Latin1Fixture", "ISO_8859_1", "é",
            "for(int i=0;i<b.length;i++) b[i]=(byte)(b[i]^1);", "è");
        assertDecodesCharset("AsciiFixture", "US_ASCII", "Hf",
            "for(int i=0;i<b.length;i++) b[i]=(byte)(b[i]^1);", "Ig");
        assertDecodesCharset("Utf16LeFixture", "UTF_16LE", "Hf",
            "for(int i=0;i<b.length;i+=2) b[i]=(byte)(b[i]^1);", "Ig");
        assertDecodesCharset("Utf16BeFixture", "UTF_16BE", "Hf",
            "for(int i=1;i<b.length;i+=2) b[i]=(byte)(b[i]^1);", "Ig");
        assertDecodesCharset("Utf16Fixture", "UTF_16", "Hf",
            "for(int i=3;i<b.length;i+=2) b[i]=(byte)(b[i]^1);", "Ig");
    }

    @Test void refusesRuntimeCharsetLookupEvenWhenNameIsStandard() throws Exception {
        byte[] input = compile("DynamicCharsetFixture", "import java.nio.charset.Charset;"
            + "public class DynamicCharsetFixture { private static String decode(String s) {"
            + "Charset c=Charset.forName(\"UTF-8\"); byte[] b=s.getBytes(c);"
            + "for(int i=0;i<b.length;i++) b[i]=(byte)(b[i]^1); return new String(b,c); }"
            + "public static String value() { return decode(\"Ih\"); } }");
        assertArrayEquals(input, decrypt(input, 10).bytes());
    }

    @Test void refusesDecoderThatExceedsCumulativeAllocationBudget() throws Exception {
        byte[] input = compile("AllocationBudgetFixture", "import java.nio.charset.StandardCharsets;"
            + "public class AllocationBudgetFixture { private static String decode(String s) {"
            + "for(int i=0;i<100;i++){ byte[] waste=new byte[4096]; }"
            + "byte[] b=s.getBytes(StandardCharsets.UTF_8); for(int i=0;i<b.length;i++) b[i]=(byte)(b[i]^1);"
            + "return new String(b,StandardCharsets.UTF_8); }"
            + "public static String value() { return decode(\"Ih\"); } }");
        assertArrayEquals(input, decrypt(input, 10).bytes());
    }

    @Test void decodesBasicBase64ThenUtf8String() throws Exception {
        byte[] input = compile("Base64DecodeFixture", "import java.nio.charset.StandardCharsets; import java.util.Base64;"
            + "public class Base64DecodeFixture { private static String decode(String s) {"
            + "return new String(Base64.getDecoder().decode(s),StandardCharsets.UTF_8); }"
            + "public static String value() { return decode(\"SGVsbG8=\"); } }");
        var result = decrypt(input, 10);
        assertEquals(1, result.changes().size());
        assertFalse(result.changes().toString().contains("Hello"));
        var type = new ClassNode(); new ClassReader(result.bytes()).accept(type, 0);
        var value = type.methods.stream().filter(method -> method.name.equals("value")).findFirst().orElseThrow();
        assertTrue(Arrays.stream(value.instructions.toArray()).anyMatch(node -> node instanceof LdcInsnNode literal && "Hello".equals(literal.cst)));
        assertTrue(Arrays.stream(value.instructions.toArray()).anyMatch(node -> node instanceof MethodInsnNode constructor
            && constructor.name.equals("<init>") && constructor.desc.equals("(Ljava/lang/String;)V")));
    }

    @Test void refusesMalformedBase64WithoutChangingClassBytes() throws Exception {
        byte[] input = compile("MalformedBase64Fixture", "import java.nio.charset.StandardCharsets; import java.util.Base64;"
            + "public class MalformedBase64Fixture { private static String decode(String s) {"
            + "return new String(Base64.getDecoder().decode(s),StandardCharsets.UTF_8); }"
            + "public static String value() { return decode(\"not base64!\"); } }");
        assertArrayEquals(input, decrypt(input, 10).bytes());
    }

    @Test void decodesUrlSafeBase64Decoder() throws Exception {
        byte[] input = compile("UrlBase64Fixture", "import java.nio.charset.StandardCharsets; import java.util.Base64;"
            + "public class UrlBase64Fixture { private static String decode(String s) {"
            + "return new String(Base64.getUrlDecoder().decode(s),StandardCharsets.UTF_8); }"
            + "public static String value() { return decode(\"SGVsbG8_\"); } }");
        var result = decrypt(input, 10);
        assertEquals(1, result.changes().size());
        var type = new ClassNode(); new ClassReader(result.bytes()).accept(type, 0);
        var value = type.methods.stream().filter(method -> method.name.equals("value")).findFirst().orElseThrow();
        assertTrue(Arrays.stream(value.instructions.toArray()).anyMatch(node -> node instanceof LdcInsnNode literal && "Hello?".equals(literal.cst)));
    }

    @Test void decodesMimeBase64WithIgnoredLineBreaks() throws Exception {
        byte[] input = compile("MimeBase64Fixture", "import java.nio.charset.StandardCharsets; import java.util.Base64;"
            + "public class MimeBase64Fixture { private static String decode(String s) {"
            + "return new String(Base64.getMimeDecoder().decode(s),StandardCharsets.UTF_8); }"
            + "public static String value() { return decode(\"SGVs\\nbG8=\"); } }");
        var result = decrypt(input, 10);
        assertEquals(1, result.changes().size());
        var type = new ClassNode(); new ClassReader(result.bytes()).accept(type, 0);
        var value = type.methods.stream().filter(method -> method.name.equals("value")).findFirst().orElseThrow();
        assertTrue(Arrays.stream(value.instructions.toArray()).anyMatch(node -> node instanceof LdcInsnNode literal && "Hello".equals(literal.cst)));
    }

    @Test void countsRepeatedEmptyDecodedArraysAgainstAllocationBudget() throws Exception {
        byte[] input = compile("EmptyBase64BudgetFixture", "import java.util.Base64;"
            + "public class EmptyBase64BudgetFixture { private static String decode(String s) {"
            + "for(int i=0;i<5000;i++){ byte[] b=Base64.getDecoder().decode(\"\"); } return \"\"; }"
            + "public static String value() { return decode(\"\"); } }");
        assertArrayEquals(input, decrypt(input, 10).bytes());
    }

    @Test void decodesCharacterLoopBuiltWithStringBuilder() throws Exception {
        byte[] input = compile("StringBuilderDecodeFixture", "public class StringBuilderDecodeFixture {"
            + "private static String decode(String s) { StringBuilder b=new StringBuilder(s.length());"
            + "for(int i=0;i<s.length();i++) b.append((char)(s.charAt(i)^7)); return b.toString(); }"
            + "public static String value() { return decode(\"Obkkh\"); } }");
        var result = decrypt(input, 10);
        assertEquals(1, result.changes().size());
        var type = new ClassNode(); new ClassReader(result.bytes()).accept(type, 0);
        var value = type.methods.stream().filter(method -> method.name.equals("value")).findFirst().orElseThrow();
        assertTrue(Arrays.stream(value.instructions.toArray()).anyMatch(node -> node instanceof LdcInsnNode literal && "Hello".equals(literal.cst)));
        assertTrue(Arrays.stream(value.instructions.toArray()).anyMatch(node -> node instanceof MethodInsnNode constructor
            && constructor.name.equals("<init>") && constructor.desc.equals("(Ljava/lang/String;)V")));
    }

    @Test void reversesStringBuilderWithJvmSurrogatePairSemantics() throws Exception {
        byte[] input = compile("StringBuilderReverseFixture", "public class StringBuilderReverseFixture {"
            + "private static String decode(String s) { return new StringBuilder().append(s).reverse().toString(); }"
            + "public static String value() { return decode(\"\\uD83D\\uDE42ab\"); } }");
        var result = decrypt(input, 10);
        assertEquals(1, result.changes().size());
        var type = new ClassNode(); new ClassReader(result.bytes()).accept(type, 0);
        var value = type.methods.stream().filter(method -> method.name.equals("value")).findFirst().orElseThrow();
        assertTrue(Arrays.stream(value.instructions.toArray()).anyMatch(node -> node instanceof LdcInsnNode literal && "ba\uD83D\uDE42".equals(literal.cst)));
        assertTrue(Arrays.stream(value.instructions.toArray()).anyMatch(node -> node instanceof MethodInsnNode constructor
            && constructor.name.equals("<init>") && constructor.desc.equals("(Ljava/lang/String;)V")));
    }

    @Test void reversesStringBuilderInitializedFromBoundedString() throws Exception {
        byte[] input = compile("StringBuilderStringConstructorFixture", "public class StringBuilderStringConstructorFixture {"
            + "private static String decode(String s) { return new StringBuilder(s).reverse().toString(); }"
            + "public static String value() { return decode(\"olleH\"); } }");
        var result = decrypt(input, 10);
        assertEquals(1, result.changes().size());
        var type = new ClassNode(); new ClassReader(result.bytes()).accept(type, 0);
        var value = type.methods.stream().filter(method -> method.name.equals("value")).findFirst().orElseThrow();
        assertTrue(Arrays.stream(value.instructions.toArray()).anyMatch(node -> node instanceof LdcInsnNode literal && "Hello".equals(literal.cst)));
        assertTrue(Arrays.stream(value.instructions.toArray()).anyMatch(node -> node instanceof MethodInsnNode constructor
            && constructor.name.equals("<init>") && constructor.desc.equals("(Ljava/lang/String;)V")));
    }

    @Test void decodesExactIntegerRotateOperations() throws Exception {
        byte[] input = compile("IntegerRotationFixture", "public class IntegerRotationFixture {"
            + "private static String decodeLeft(String s) { char[] c=s.toCharArray();"
            + "for(int i=0;i<c.length;i++) c[i]=(char)Integer.rotateLeft(s.charAt(i),5); return new String(c); }"
            + "private static String decodeRight(String s) { char[] c=s.toCharArray();"
            + "for(int i=0;i<c.length;i++) c[i]=(char)Integer.rotateRight(s.charAt(i),5); return new String(c); }"
            + "public static String left() { return decodeLeft(\"A\"); }"
            + "public static String right() { return decodeRight(\"A\"); } }");
        var result = decrypt(input, 10);
        assertEquals(2, result.changes().size());
        var type = new ClassNode(); new ClassReader(result.bytes()).accept(type, 0);
        String expectedLeft = String.valueOf((char)Integer.rotateLeft('A', 5));
        String expectedRight = String.valueOf((char)Integer.rotateRight('A', 5));
        for (var expected : java.util.Map.of("left", expectedLeft, "right", expectedRight).entrySet()) {
            var method = type.methods.stream().filter(value -> value.name.equals(expected.getKey())).findFirst().orElseThrow();
            assertTrue(Arrays.stream(method.instructions.toArray()).anyMatch(node -> node instanceof LdcInsnNode literal && expected.getValue().equals(literal.cst)), expected.getKey());
        }
    }

    @Test void decodesExactIntegerBitReversalOperations() throws Exception {
        byte[] input = compile("IntegerBitReverseFixture", "public class IntegerBitReverseFixture {"
            + "private static String decodeBits(String s) { char[] c=s.toCharArray();"
            + "for(int i=0;i<c.length;i++) c[i]=(char)Integer.reverse(s.charAt(i)<<16); return new String(c); }"
            + "private static String decodeBytes(String s) { char[] c=s.toCharArray();"
            + "for(int i=0;i<c.length;i++) c[i]=(char)Integer.reverseBytes(s.charAt(i)<<16); return new String(c); }"
            + "public static String bits() { return decodeBits(\"A\"); }"
            + "public static String bytes() { return decodeBytes(\"A\"); } }");
        var result = decrypt(input, 10);
        assertEquals(2, result.changes().size());
        var type = new ClassNode(); new ClassReader(result.bytes()).accept(type, 0);
        String expectedBits = String.valueOf((char)Integer.reverse('A' << 16));
        String expectedBytes = String.valueOf((char)Integer.reverseBytes('A' << 16));
        for (var expected : java.util.Map.of("bits", expectedBits, "bytes", expectedBytes).entrySet()) {
            var method = type.methods.stream().filter(value -> value.name.equals(expected.getKey())).findFirst().orElseThrow();
            assertTrue(Arrays.stream(method.instructions.toArray()).anyMatch(node -> node instanceof LdcInsnNode literal && expected.getValue().equals(literal.cst)), expected.getKey());
        }
    }

    @Test void slicesBoundedBase64ByteArraysWithExactArraysOverloads() throws Exception {
        byte[] input = compile("Base64ArraySliceFixture", "import java.nio.charset.StandardCharsets; import java.util.Arrays; import java.util.Base64;"
            + "public class Base64ArraySliceFixture { private static String decode(String s) {"
            + "byte[] b=Base64.getDecoder().decode(s); byte[] middle=Arrays.copyOfRange(b,1,b.length);"
            + "byte[] text=Arrays.copyOf(middle,middle.length-1); return new String(text,StandardCharsets.UTF_8); }"
            + "public static String value() { return decode(\"eEhlbGxvWQ==\"); } }");
        var result = decrypt(input, 10);
        assertEquals(1, result.changes().size());
        var type = new ClassNode(); new ClassReader(result.bytes()).accept(type, 0);
        var value = type.methods.stream().filter(method -> method.name.equals("value")).findFirst().orElseThrow();
        assertTrue(Arrays.stream(value.instructions.toArray()).anyMatch(node -> node instanceof LdcInsnNode literal && "Hello".equals(literal.cst)));
    }

    @Test void preservesJdkZeroPaddingForBoundedArrayCopies() throws Exception {
        byte[] input = compile("PaddedArrayCopyFixture", "import java.nio.charset.StandardCharsets; import java.util.Arrays; import java.util.Base64;"
            + "public class PaddedArrayCopyFixture { private static String decode(String s) {"
            + "byte[] b=Base64.getDecoder().decode(s); byte[] range=Arrays.copyOfRange(b,0,b.length+1);"
            + "byte[] padded=Arrays.copyOf(range,range.length+2); return new String(padded,StandardCharsets.ISO_8859_1); }"
            + "public static String value() { return decode(\"eA==\"); } }");
        var result = decrypt(input, 10);
        assertEquals(1, result.changes().size());
        var type = new ClassNode(); new ClassReader(result.bytes()).accept(type, 0);
        var value = type.methods.stream().filter(method -> method.name.equals("value")).findFirst().orElseThrow();
        assertTrue(Arrays.stream(value.instructions.toArray()).anyMatch(node -> node instanceof LdcInsnNode literal && "x\u0000\u0000\u0000".equals(literal.cst)));
    }

    @Test void mutatesBoundedStringBuilderCharactersWithExactAccessors() throws Exception {
        byte[] input = compile("StringBuilderCharMutationFixture", "public class StringBuilderCharMutationFixture {"
            + "private static String decode(String s) { StringBuilder b=new StringBuilder(s);"
            + "for(int i=0;i<b.length();i++) b.setCharAt(i,(char)(b.charAt(i)^7)); return b.toString(); }"
            + "public static String value() { return decode(\"Obkkh\"); } }");
        var result = decrypt(input, 10);
        assertEquals(1, result.changes().size());
        var type = new ClassNode(); new ClassReader(result.bytes()).accept(type, 0);
        var value = type.methods.stream().filter(method -> method.name.equals("value")).findFirst().orElseThrow();
        assertTrue(Arrays.stream(value.instructions.toArray()).anyMatch(node -> node instanceof LdcInsnNode literal && "Hello".equals(literal.cst)));
    }

    @Test void refusesOutOfRangeStringBuilderCharacterAccessWithoutThrowing() throws Exception {
        byte[] input = compile("InvalidStringBuilderIndexFixture", "public class InvalidStringBuilderIndexFixture {"
            + "private static String decode(String s) { StringBuilder b=new StringBuilder(s);"
            + "b.setCharAt(b.length(),'x'); return b.toString(); }"
            + "public static String value() { return decode(\"a\"); } }");
        assertArrayEquals(input, decrypt(input, 10).bytes());
    }

    @Test void insertsAndDeletesBoundedStringBuilderCharacters() throws Exception {
        byte[] input = compile("StringBuilderInsertDeleteFixture", "public class StringBuilderInsertDeleteFixture {"
            + "private static String decode(String s) { StringBuilder b=new StringBuilder(s);"
            + "b.deleteCharAt(1); b.insert(1,'e'); return b.toString(); }"
            + "public static String value() { return decode(\"Hlllo\"); } }");
        var result = decrypt(input, 10);
        assertEquals(1, result.changes().size());
        var type = new ClassNode(); new ClassReader(result.bytes()).accept(type, 0);
        var value = type.methods.stream().filter(method -> method.name.equals("value")).findFirst().orElseThrow();
        assertTrue(Arrays.stream(value.instructions.toArray()).anyMatch(node -> node instanceof LdcInsnNode literal && "Hello".equals(literal.cst)));
    }

    @Test void refusesOutOfRangeStringBuilderInsertWithoutThrowing() throws Exception {
        byte[] input = compile("InvalidStringBuilderInsertFixture", "public class InvalidStringBuilderInsertFixture {"
            + "private static String decode(String s) { StringBuilder b=new StringBuilder(s);"
            + "b.insert(b.length()+1,'x'); return b.toString(); }"
            + "public static String value() { return decode(\"a\"); } }");
        assertArrayEquals(input, decrypt(input, 10).bytes());
    }

    @Test void truncatesBoundedStringBuilderToItsLogicalLength() throws Exception {
        byte[] input = compile("StringBuilderSetLengthFixture", "public class StringBuilderSetLengthFixture {"
            + "private static String decode(String s) { StringBuilder b=new StringBuilder(s);"
            + "b.setLength(b.length()-1); return b.toString(); }"
            + "public static String value() { return decode(\"Hellox\"); } }");
        var result = decrypt(input, 10);
        assertEquals(1, result.changes().size());
        var type = new ClassNode(); new ClassReader(result.bytes()).accept(type, 0);
        var value = type.methods.stream().filter(method -> method.name.equals("value")).findFirst().orElseThrow();
        assertTrue(Arrays.stream(value.instructions.toArray()).anyMatch(node -> node instanceof LdcInsnNode literal && "Hello".equals(literal.cst)));
    }

    @Test void refusesNegativeAndOversizedStringBuilderLengthsWithoutThrowing() throws Exception {
        byte[] negative = compile("NegativeStringBuilderLengthFixture", "public class NegativeStringBuilderLengthFixture {"
            + "private static String decode(String s) { StringBuilder b=new StringBuilder(s); b.setLength(-1); return b.toString(); }"
            + "public static String value() { return decode(\"a\"); } }");
        byte[] oversized = compile("OversizedStringBuilderLengthFixture", "public class OversizedStringBuilderLengthFixture {"
            + "private static String decode(String s) { StringBuilder b=new StringBuilder(s); b.setLength(4097); return b.toString(); }"
            + "public static String value() { return decode(\"a\"); } }");
        var pass = BuiltinPasses.select("string-decryptor").getFirst();
        assertArrayEquals(negative, pass.apply(negative, 10).bytes());
        assertArrayEquals(oversized, pass.apply(oversized, 10).bytes());
    }

    @Test void decodesBoundedStringSubstringFromFreshStringObjects() throws Exception {
        byte[] input = compile("StringSubstringFixture", "public class StringSubstringFixture {"
            + "private static String decode(String s) { return new String(s.toCharArray()).substring(1,s.length()-1); }"
            + "private static String suffix(String s) { return s.substring(1); }"
            + "public static String value() { return decode(\"xHello!\"); }"
            + "public static String suffixValue() { return suffix(\"xHello\"); } }");
        var result = decrypt(input, 10);
        assertEquals(2, result.changes().size());
        var type = new ClassNode(); new ClassReader(result.bytes()).accept(type, 0);
        var value = type.methods.stream().filter(method -> method.name.equals("value")).findFirst().orElseThrow();
        assertTrue(Arrays.stream(value.instructions.toArray()).anyMatch(node -> node instanceof LdcInsnNode literal && "Hello".equals(literal.cst)));
        assertTrue(Arrays.stream(value.instructions.toArray()).anyMatch(node -> node instanceof MethodInsnNode call
            && call.owner.equals("java/lang/String") && call.name.equals("<init>") && call.desc.equals("(Ljava/lang/String;)V")));
        var suffix = type.methods.stream().filter(method -> method.name.equals("suffixValue")).findFirst().orElseThrow();
        assertTrue(Arrays.stream(suffix.instructions.toArray()).anyMatch(node -> node instanceof LdcInsnNode literal && "Hello".equals(literal.cst)));
    }

    @Test void preservesIdentityForFullRangeStringSubstring() throws Exception {
        byte[] input = compile("FullStringSubstringFixture", "public class FullStringSubstringFixture {"
            + "private static String decode(String s) { return s.substring(0); }"
            + "public static String value() { return decode(\"same\"); } }");
        var result = decrypt(input, 10);
        assertEquals(1, result.changes().size());
        var type = new ClassNode(); new ClassReader(result.bytes()).accept(type, 0);
        var value = type.methods.stream().filter(method -> method.name.equals("value")).findFirst().orElseThrow();
        assertTrue(Arrays.stream(value.instructions.toArray()).anyMatch(node -> node instanceof LdcInsnNode literal && "same".equals(literal.cst)));
        assertFalse(Arrays.stream(value.instructions.toArray()).anyMatch(node -> node instanceof TypeInsnNode allocation
            && allocation.getOpcode() == NEW && allocation.desc.equals("java/lang/String")));
    }

    @Test void refusesInvalidStringSubstringRangesWithoutThrowing() throws Exception {
        byte[] input = compile("InvalidSubstringFixture", "public class InvalidSubstringFixture {"
            + "private static String decode(String s) { return s.substring(2,1); }"
            + "public static String value() { return decode(\"abc\"); } }");
        assertArrayEquals(input, decrypt(input, 10).bytes());
    }

    @Test void replacesBoundedStringCharactersWithJdkSemantics() throws Exception {
        byte[] input = compile("StringCharacterReplaceFixture", "public class StringCharacterReplaceFixture {"
            + "private static String decode(String s) { return s.replace('x','H'); }"
            + "public static String value() { return decode(\"xello\"); } }");
        var result = decrypt(input, 10);
        assertEquals(1, result.changes().size());
        var type = new ClassNode(); new ClassReader(result.bytes()).accept(type, 0);
        var value = type.methods.stream().filter(method -> method.name.equals("value")).findFirst().orElseThrow();
        assertTrue(Arrays.stream(value.instructions.toArray()).anyMatch(node -> node instanceof LdcInsnNode literal && "Hello".equals(literal.cst)));
        assertTrue(Arrays.stream(value.instructions.toArray()).anyMatch(node -> node instanceof MethodInsnNode call
            && call.owner.equals("java/lang/String") && call.name.equals("<init>") && call.desc.equals("(Ljava/lang/String;)V")));
    }

    @Test void preservesStringIdentityWhenCharacterReplacementDoesNothing() throws Exception {
        byte[] input = compile("NoOpStringReplaceFixture", "public class NoOpStringReplaceFixture {"
            + "private static String decode(String s) { return s.replace('x','y'); }"
            + "public static String value() { return decode(\"same\"); } }");
        var result = decrypt(input, 10);
        assertEquals(1, result.changes().size());
        var type = new ClassNode(); new ClassReader(result.bytes()).accept(type, 0);
        var value = type.methods.stream().filter(method -> method.name.equals("value")).findFirst().orElseThrow();
        assertTrue(Arrays.stream(value.instructions.toArray()).anyMatch(node -> node instanceof LdcInsnNode literal && "same".equals(literal.cst)));
        assertFalse(Arrays.stream(value.instructions.toArray()).anyMatch(node -> node instanceof TypeInsnNode allocation
            && allocation.getOpcode() == NEW && allocation.desc.equals("java/lang/String")));
    }

    @Test void replacesEveryUtf16CodeUnitOccurrenceIncludingSurrogateHalves() throws Exception {
        byte[] input = compile("SurrogateReplaceFixture", "public class SurrogateReplaceFixture {"
            + "private static String decode(String s) { return s.replace('\\uD83D','\\uD83E').replace('a','b'); }"
            + "public static String value() { return decode(\"a\\uD83D\\uDE00a\"); } }");
        var result = decrypt(input, 10);
        assertEquals(1, result.changes().size());
        var type = new ClassNode(); new ClassReader(result.bytes()).accept(type, 0);
        var value = type.methods.stream().filter(method -> method.name.equals("value")).findFirst().orElseThrow();
        assertTrue(Arrays.stream(value.instructions.toArray()).anyMatch(node -> node instanceof LdcInsnNode literal
            && "b🨀b".equals(literal.cst)));
    }

    @Test void refusesCharSequenceStringReplaceOverload() throws Exception {
        byte[] input = compile("CharSequenceReplaceFixture", "public class CharSequenceReplaceFixture {"
            + "private static String decode(String s) { return s.replace(\"x\",\"H\"); }"
            + "public static String value() { return decode(\"xello\"); } }");
        assertArrayEquals(input, decrypt(input, 10).bytes());
    }

    @Test void refusesInvalidArrayCopyRangesWithoutAllocatingOrThrowing() throws Exception {
        byte[] invalidRange = compile("InvalidArrayRangeFixture", "import java.nio.charset.StandardCharsets; import java.util.Arrays; import java.util.Base64;"
            + "public class InvalidArrayRangeFixture { private static String decode(String s) {"
            + "byte[] b=Base64.getDecoder().decode(s); byte[] invalid=Arrays.copyOfRange(b,-1,b.length);"
            + "return new String(invalid,StandardCharsets.UTF_8); } public static String value() { return decode(\"eA==\"); } }");
        byte[] oversizedCopy = compile("OversizedArrayCopyFixture", "import java.nio.charset.StandardCharsets; import java.util.Arrays; import java.util.Base64;"
            + "public class OversizedArrayCopyFixture { private static String decode(String s) {"
            + "byte[] b=Base64.getDecoder().decode(s); byte[] invalid=Arrays.copyOf(b,4097);"
            + "return new String(invalid,StandardCharsets.UTF_8); } public static String value() { return decode(\"eA==\"); } }");
        var pass = BuiltinPasses.select("string-decryptor").getFirst();
        assertArrayEquals(invalidRange, pass.apply(invalidRange, 10).bytes());
        assertArrayEquals(oversizedCopy, pass.apply(oversizedCopy, 10).bytes());
    }

    @Test void refusesStringBuilderAppendObject() throws Exception {
        byte[] input = compile("StringBuilderObjectFixture", "public class StringBuilderObjectFixture {"
            + "private static String decode(String s) { StringBuilder b=new StringBuilder(); b.append(new Object()); return b.toString(); }"
            + "public static String value() { return decode(\"x\"); } }");
        assertArrayEquals(input, decrypt(input, 10).bytes());
    }

    @Test void decodesBoundedStringAndIntegerBuilderAppends() throws Exception {
        byte[] input = compile("StringBuilderAppendFixture", "public class StringBuilderAppendFixture {"
            + "private static String decode(String s) { return new StringBuilder(s.length()).append(s).append(123).toString(); }"
            + "public static String value() { return decode(\"Hi\"); } }");
        var result = decrypt(input, 10);
        assertEquals(1, result.changes().size());
        var type = new ClassNode(); new ClassReader(result.bytes()).accept(type, 0);
        var value = type.methods.stream().filter(method -> method.name.equals("value")).findFirst().orElseThrow();
        assertTrue(Arrays.stream(value.instructions.toArray()).anyMatch(node -> node instanceof LdcInsnNode literal && "Hi123".equals(literal.cst)));
    }

    @Test void decodesSameClassStaticStringHelper() throws Exception {
        byte[] input = compile("StringHelperFixture", "public class StringHelperFixture {"
            + "private static String decode(String s) { return helper(s); }"
            + "private static String helper(String s) { char[] c=s.toCharArray();"
            + "for(int i=0;i<c.length;i++) c[i]=(char)(c[i]^7); return new String(c); }"
            + "public static String value() { return decode(\"Obkkh\"); } }");
        var result = decrypt(input, 10);
        assertEquals(1, result.changes().size());
        var type = new ClassNode(); new ClassReader(result.bytes()).accept(type, 0);
        var value = type.methods.stream().filter(method -> method.name.equals("value")).findFirst().orElseThrow();
        assertTrue(Arrays.stream(value.instructions.toArray()).anyMatch(node -> node instanceof LdcInsnNode literal && "Hello".equals(literal.cst)));
    }

    @Test void refusesRecursiveStaticStringHelpers() throws Exception {
        byte[] input = compile("RecursiveStringHelperFixture", "public class RecursiveStringHelperFixture {"
            + "private static String decode(String s) { return helper(s); }"
            + "private static String helper(String s) { return decode(s); }"
            + "public static String value() { return decode(\"x\"); } }");
        assertArrayEquals(input, decrypt(input, 10).bytes());
    }

    @Test void refusesHelperWithUnlistedSideEffectCall() throws Exception {
        byte[] input = compile("SideEffectStringHelperFixture", "public class SideEffectStringHelperFixture {"
            + "private static String decode(String s) { return helper(s); }"
            + "private static String helper(String s) { System.getProperty(\"user.home\"); return s; }"
            + "public static String value() { return decode(\"x\"); } }");
        assertArrayEquals(input, decrypt(input, 10).bytes());
    }

    @Test void decodesCharArrayReturnedByStringValueOf() throws Exception {
        byte[] input = compile("StringValueOfFixture", "public class StringValueOfFixture {"
            + "private static String decode(String s) { char[] c=s.toCharArray();"
            + "for(int i=0;i<c.length;i++) c[i]=(char)(c[i]^7); return String.valueOf(c); }"
            + "public static String value() { return decode(\"Obkkh\"); } }");
        var result = decrypt(input, 10);
        assertEquals(1, result.changes().size());
        var type = new ClassNode(); new ClassReader(result.bytes()).accept(type, 0);
        var value = type.methods.stream().filter(method -> method.name.equals("value")).findFirst().orElseThrow();
        assertTrue(Arrays.stream(value.instructions.toArray()).anyMatch(node -> node instanceof LdcInsnNode literal && "Hello".equals(literal.cst)));
        assertTrue(Arrays.stream(value.instructions.toArray()).anyMatch(node -> node instanceof MethodInsnNode constructor
            && constructor.name.equals("<init>") && constructor.desc.equals("(Ljava/lang/String;)V")));
    }

    @Test void refusesStringValueOfObjectOverload() throws Exception {
        byte[] input = compile("StringValueOfObjectFixture", "public class StringValueOfObjectFixture {"
            + "private static String decode(String s) { return String.valueOf((Object)s); }"
            + "public static String value() { return decode(\"x\"); } }");
        assertArrayEquals(input, decrypt(input, 10).bytes());
    }

    @Test void refusesUnknownSideEffectCallsAndLeavesClassBytesUntouched() throws Exception {
        byte[] input = compile("SideEffectFixture", "public class SideEffectFixture {"
            + "private static String decode(String s) { System.getProperty(\"user.home\"); return s; }"
            + "public static String value() { return decode(\"x\"); } }");
        assertArrayEquals(input, decrypt(input, 10).bytes());
    }

    @Test void doesNotTreatALabelAsAnAdjacentLiteralArgument() throws Exception {
        byte[] input = labeledFixture();
        assertArrayEquals(input, decrypt(input, 10).bytes());
    }

    @Test void refusesClassInitializerAndExceptionHandlers() throws Exception {
        byte[] initializer = compile("InitializerFixture", "public class InitializerFixture {"
            + "static { System.setProperty(\"classrevive.fixture\",\"loaded\"); }"
            + "private static String decode(String s) { return s; }"
            + "public static String value() { return decode(\"x\"); } }");
        byte[] handler = compile("HandlerFixture", "public class HandlerFixture {"
            + "private static String decode(String s) { try { return new String(s.toCharArray()); }"
            + "catch (RuntimeException e) { return \"\"; } }"
            + "public static String value() { return decode(\"x\"); } }");
        var pass = BuiltinPasses.select("string-decryptor").getFirst();
        assertArrayEquals(initializer, pass.apply(initializer, 10).bytes());
        assertArrayEquals(handler, pass.apply(handler, 10).bytes());
    }

    @Test void stopsInfiniteDecoderAtFuelLimitAndHonorsChangeBudget() throws Exception {
        byte[] looping = loopingFixture();
        assertArrayEquals(looping, decrypt(looping, 10).bytes());
        byte[] twoCalls = compile("TwoCallsFixture", "public class TwoCallsFixture {"
            + "private static String decode(String s) { char[] c=s.toCharArray();"
            + "for(int i=0;i<c.length;i++) c[i]=(char)(c[i]^1); return new String(c); }"
            + "public static String one() { return decode(\"`\"); }"
            + "public static String two() { return decode(\"c\"); } }");
        var limited = decrypt(twoCalls, 1);
        assertEquals(1, limited.changes().size()); assertTrue(limited.changeLimitReached());
    }

    @Test void refusesOverlongStringInputs() throws Exception {
        String longValue = "x".repeat(4097);
        byte[] input = compile("LongFixture", "public class LongFixture {"
            + "private static String decode(String s) { return new String(s.toCharArray()); }"
            + "public static String value() { return decode(\"" + longValue + "\"); } }");
        assertArrayEquals(input, decrypt(input, 10).bytes());
    }

    /** Applies the pass and makes the JVM verifier check changed output; linking runs no fixture code. */
    private static TransformResult decrypt(byte[] input, int maxChanges) {
        var result = BuiltinPasses.select("string-decryptor").getFirst().apply(input, maxChanges);
        byte[] bytes = result.bytes();
        if (Arrays.equals(input, bytes)) return result; // some inputs are deliberately malformed; refusal keeps them as-is
        var type = new ClassLoader(null) { Class<?> define() { return defineClass(null, bytes, 0, bytes.length); } }.define();
        assertDoesNotThrow(type::getDeclaredMethods, "transformed class must pass JVM verification");
        return result;
    }

    private byte[] compile(String name, String source) throws Exception {
        Path file = directory.resolve(name + ".java"); Files.writeString(file, source);
        var compiler = ToolProvider.getSystemJavaCompiler(); assertNotNull(compiler);
        assertEquals(0, compiler.run(null, null, null, "--release", "8", "-Xlint:-options", "-proc:none",
            "-d", directory.toString(), file.toString()));
        return Files.readAllBytes(directory.resolve(name + ".class"));
    }

    private void assertDecodesCharset(String name, String charset, String input, String mutation, String expected) throws Exception {
        byte[] bytes = compile(name, "import java.nio.charset.StandardCharsets; public class " + name + " {"
            + "private static String decode(String s) { byte[] b=s.getBytes(StandardCharsets." + charset + "); "
            + mutation + " return new String(b,StandardCharsets." + charset + "); }"
            + "public static String value() { return decode(\"" + input + "\"); } }");
        var result = decrypt(bytes, 10);
        assertEquals(1, result.changes().size(), charset);
        var type = new ClassNode(); new ClassReader(result.bytes()).accept(type, 0);
        var value = type.methods.stream().filter(method -> method.name.equals("value")).findFirst().orElseThrow();
        assertTrue(Arrays.stream(value.instructions.toArray()).anyMatch(node -> node instanceof LdcInsnNode literal && expected.equals(literal.cst)), charset);
    }

    private static byte[] loopingFixture() {
        var writer = new ClassWriter(0); writer.visit(V1_8, ACC_PUBLIC, "LoopingFixture", null, "java/lang/Object", null);
        var decoder = writer.visitMethod(ACC_PRIVATE | ACC_STATIC, "decode", "(Ljava/lang/String;)Ljava/lang/String;", null, null);
        decoder.visitCode(); var loop = new Label(); decoder.visitLabel(loop); decoder.visitJumpInsn(GOTO, loop); decoder.visitMaxs(0, 1); decoder.visitEnd();
        var value = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "value", "()Ljava/lang/String;", null, null);
        value.visitCode(); value.visitLdcInsn("x"); value.visitMethodInsn(INVOKESTATIC, "LoopingFixture", "decode", "(Ljava/lang/String;)Ljava/lang/String;", false);
        value.visitInsn(ARETURN); value.visitMaxs(1, 0); value.visitEnd(); writer.visitEnd(); return writer.toByteArray();
    }

    private static byte[] labeledFixture() {
        var writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(V1_8, ACC_PUBLIC, "LabelFixture", null, "java/lang/Object", null);
        var decoder = writer.visitMethod(ACC_PRIVATE | ACC_STATIC, "decode", "(Ljava/lang/String;)Ljava/lang/String;", null, null);
        decoder.visitCode(); decoder.visitVarInsn(ALOAD, 0); decoder.visitInsn(ARETURN); decoder.visitMaxs(0, 0); decoder.visitEnd();
        var value = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "value", "()Ljava/lang/String;", null, null);
        var alternate = new Label(); var join = new Label();
        value.visitCode(); value.visitInsn(ICONST_0); value.visitJumpInsn(IFEQ, alternate);
        value.visitLdcInsn("encrypted"); value.visitJumpInsn(GOTO, join);
        value.visitLabel(alternate); value.visitLdcInsn("encrypted"); value.visitLabel(join);
        value.visitMethodInsn(INVOKESTATIC, "LabelFixture", "decode", "(Ljava/lang/String;)Ljava/lang/String;", false);
        value.visitInsn(ARETURN); value.visitMaxs(0, 0); value.visitEnd(); writer.visitEnd(); return writer.toByteArray();
    }
}
