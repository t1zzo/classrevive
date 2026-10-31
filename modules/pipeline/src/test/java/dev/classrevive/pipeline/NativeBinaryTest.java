package dev.classrevive.pipeline;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class NativeBinaryTest {
    @Test void readsPeSectionsExportsAndImports() {
        var headers = NativeBinary.parse(NativeFixtures.pe(false));
        assertEquals("PE32+", headers.format());
        assertEquals("x86-64", headers.machine());
        assertEquals(List.of(".text", ".rdata", ".data"), headers.sections().stream().map(NativeBinary.Section::name).toList());
        assertEquals("r-x", headers.sections().getFirst().permissions());
        assertEquals(List.of("JNI_OnLoad", "Java_p_Lib_add__II", "Java_p_Lib_name"), headers.exports());
        assertEquals(List.of(new NativeBinary.Import("KERNEL32.dll", "GetTickCount")), headers.imports());
        assertEquals(List.of("KERNEL32.dll"), headers.neededLibraries());
        var packing = NativeBinary.packing(headers);
        assertEquals("NOT_DETECTED", packing.verdict(), packing.hints().toString());
        assertTrue(packing.suspectedPackers().isEmpty());
    }

    @Test void packedLayoutGivesHintsAndAThemidaHypothesis() {
        var headers = NativeBinary.parse(NativeFixtures.pe(true));
        var packing = NativeBinary.packing(headers);
        assertEquals("LIKELY_PACKED", packing.verdict());
        String hints = String.join("\n", packing.hints());
        assertTrue(hints.contains("3 sections without a name"), hints);
        assertTrue(hints.contains("section .boot (Themida/WinLicense name)"), hints);
        assertTrue(hints.contains("section .upx1 (UPX name)"), hints);
        assertTrue(hints.contains("executable section .upx1 has no file data (4096 bytes filled at run time)"), hints);
        assertTrue(hints.contains("executable section .boot has entropy 7.9"), hints);
        assertTrue(hints.contains("entry point in section .boot, not the first code section"), hints);
        assertEquals(List.of("Themida/WinLicense 3.x", "UPX"), packing.suspectedPackers());
        assertTrue(headers.sections().getFirst().unnamed());
    }

    @Test void readsElfDynamicSymbols() {
        var headers = NativeBinary.parse(NativeFixtures.elf());
        assertEquals("ELF64", headers.format());
        assertEquals("x86-64", headers.machine());
        assertEquals(List.of(".text", ".dynsym", ".dynstr", ".shstrtab"), headers.sections().stream().map(NativeBinary.Section::name).toList());
        assertEquals(List.of("Java_p_Lib_add__II"), headers.exports());
        assertEquals(List.of(new NativeBinary.Import("", "puts")), headers.imports());
        assertEquals("NOT_DETECTED", NativeBinary.packing(headers).verdict());
    }

    @Test void malformedInputIsReportedNotThrown() {
        byte[] pe = NativeFixtures.pe(false);
        var truncated = NativeBinary.parse(Arrays.copyOf(pe, 0x150));
        assertTrue(truncated.problems().stream().anyMatch(p -> p.contains("truncated")), truncated.problems().toString());
        assertFalse(NativeBinary.parse(new byte[] {1, 2, 3}).known());
        var random = new byte[4096]; new Random(7).nextBytes(random);
        assertFalse(NativeBinary.parse(random).known());
        byte[] badOffset = pe.clone(); badOffset[0x3c] = (byte) 0xff; badOffset[0x3f] = 0x7f;
        assertFalse(NativeBinary.parse(badOffset).known());
        var elf = Arrays.copyOf(NativeFixtures.elf(), 0x210);
        assertDoesNotThrow(() -> NativeBinary.parse(elf));
    }

    @Test void entropyAndNamesAreBounded() {
        assertEquals(0, NativeBinary.entropy(new byte[100], 0, 100));
        byte[] all = new byte[256]; for (int i = 0; i < 256; i++) all[i] = (byte) i;
        assertEquals(8000, NativeBinary.entropy(all, 0, 256));
        assertEquals(-1, NativeBinary.entropy(all, 300, 10));
        assertEquals("a\\x0ab\\xe9", NativeBinary.clean("a\nbé"));
    }
}
