package dev.classrevive.pipeline;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class JniNamesTest {
    @Test void decodesShortAndLongNames() {
        assertEquals(new JniNames.Method("p/Lib", "name", null), JniNames.decode("Java_p_Lib_name").orElseThrow());
        assertEquals(new JniNames.Method("p/Lib", "add", "(II)"), JniNames.decode("Java_p_Lib_add__II").orElseThrow());
        assertEquals(new JniNames.Method("com/github/luben/zstd/Zstd", "compressUnsafe", "(JJJJI)"),
            JniNames.decode("Java_com_github_luben_zstd_Zstd_compressUnsafe__JJJJI").orElseThrow());
        assertEquals(new JniNames.Method("a/B", "m", "([BLjava/lang/String;)"),
            JniNames.decode("Java_a_B_m___3BLjava_lang_String_2").orElseThrow());
    }

    @Test void decodesEscapes() {
        // JNA: Native._getPointer is Native__1getPointer (a separator, then an escaped underscore), not a signature.
        assertEquals(new JniNames.Method("com/sun/jna/Native", "_getPointer", null), JniNames.decode("Java_com_sun_jna_Native__1getPointer").orElseThrow());
        assertEquals(new JniNames.Method("my_pkg/C", "x", null), JniNames.decode("Java_my_1pkg_C_x").orElseThrow());
        assertEquals(new JniNames.Method("p/C$I", "é", null), JniNames.decode("Java_p_C_00024I__000e9").orElseThrow());
        assertEquals(new JniNames.Method("p/A", "m", "(I)"), JniNames.decode("_Java_p_A_m__I@16").orElseThrow());
    }

    @Test void rejectsOtherSymbols() {
        assertTrue(JniNames.decode("JNI_OnLoad").isEmpty());
        assertTrue(JniNames.decode("Java_Only").isEmpty());
        assertTrue(JniNames.decode("Java_p_A_m_0zz").isEmpty());
        assertTrue(JniNames.decode("Java_p__").isEmpty());
    }

    @Test void matchesTheDeclaredMethod() {
        var shortName = JniNames.decode("Java_p_Lib_add").orElseThrow();
        assertTrue(shortName.matches("p/Lib", "add", "(II)I"));
        assertTrue(JniNames.decode("Java_p_Lib_add__II").orElseThrow().matches("p/Lib", "add", "(II)I"));
        assertFalse(JniNames.decode("Java_p_Lib_add__J").orElseThrow().matches("p/Lib", "add", "(II)I"));
    }

    @Test void obfuscatedNames() {
        assertTrue(JniNames.obfuscatedName("0a2b4acb11223344"));
        assertTrue(JniNames.obfuscatedName("abcdefabcdefabcdef"));
        assertTrue(JniNames.obfuscatedName("bad-name"));
        assertFalse(JniNames.obfuscatedName("compress"));
    }
}
