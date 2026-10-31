package dev.classrevive.pipeline;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import org.objectweb.asm.ClassWriter;
import static org.objectweb.asm.Opcodes.*;

/**
 * Phase 2CC test inputs, written byte by byte: a minimal x86-64 JNI DLL (PE32+), a minimal ELF64 shared object and a jar
 * whose class declares the matching native methods. They are only parsed and given to Ghidra; nothing loads or runs them.
 */
final class NativeFixtures {
    private NativeFixtures() {}

    static final long IMAGE_BASE = 0x180000000L;
    static final String HEX_NATIVE = "0123456789abcdef0123";
    static final String HEX_DESCRIPTOR = "(Ljava/lang/String;)V";

    /**
     * Exports JNI_OnLoad, Java_p_Lib_add__II and Java_p_Lib_name and imports KERNEL32.dll!GetTickCount. JNI_OnLoad calls a
     * helper that does {@code call [rax+0x6b8]} (JNIEnv slot 215, RegisterNatives), and .data holds one JNINativeMethod
     * entry {@code {HEX_NATIVE, HEX_DESCRIPTOR, 0x1030}}. With {@code packed}, the sections lose their names and a
     * {@code .upx1} section without file data and a high-entropy {@code .boot} section holding the entry point are added.
     */
    static byte[] pe(boolean packed) {
        int sections = packed ? 5 : 3;
        int fileSize = 0x400 + 0x200 * 3 + (packed ? 0x1000 : 0);
        var b = ByteBuffer.allocate(fileSize).order(ByteOrder.LITTLE_ENDIAN);
        b.put(0, (byte) 'M').put(1, (byte) 'Z').putInt(0x3c, 0x40);
        b.put(0x40, (byte) 'P').put(0x41, (byte) 'E');
        int coff = 0x44;
        b.putShort(coff, (short) 0x8664).putShort(coff + 2, (short) sections).putShort(coff + 16, (short) 240).putShort(coff + 18, (short) 0x2022);
        int opt = coff + 20;
        b.putShort(opt, (short) 0x20b).putInt(opt + 4, 0x200).putInt(opt + 8, 0x400);
        b.putInt(opt + 16, packed ? 0x5000 : 0).putInt(opt + 20, 0x1000).putLong(opt + 24, IMAGE_BASE);
        b.putInt(opt + 32, 0x1000).putInt(opt + 36, 0x200).putShort(opt + 40, (short) 6).putShort(opt + 48, (short) 6);
        b.putInt(opt + 56, packed ? 0x6000 : 0x4000).putInt(opt + 60, 0x400).putShort(opt + 68, (short) 2).putShort(opt + 70, (short) 0x160);
        b.putLong(opt + 72, 0x100000).putLong(opt + 80, 0x1000).putLong(opt + 88, 0x100000).putLong(opt + 96, 0x1000);
        b.putInt(opt + 108, 16);
        int dirs = opt + 112;
        b.putInt(dirs, 0x2000).putInt(dirs + 4, 0x100);          // export
        b.putInt(dirs + 8, 0x2100).putInt(dirs + 12, 40);        // import
        b.putInt(dirs + 96, 0x2140).putInt(dirs + 100, 16);      // IAT
        int table = opt + 240;
        String blank = "        ";
        section(b, table, packed ? blank : ".text", 0x1000, 0x100, 0x400, 0x200, 0x60000020);
        section(b, table + 40, packed ? blank : ".rdata", 0x2000, 0x200, 0x600, 0x200, 0x40000040);
        section(b, table + 80, packed ? blank : ".data", 0x3000, 0x100, 0x800, 0x200, 0xc0000040);
        if (packed) {
            section(b, table + 120, ".upx1", 0x4000, 0x1000, 0, 0, 0xe0000060);
            section(b, table + 160, ".boot", 0x5000, 0x1000, 0xa00, 0x1000, 0x60000060);
            var random = new Random(2026);
            for (int i = 0; i < 0x1000; i++) b.put(0xa00 + i, (byte) random.nextInt(256));
        }
        // .text: JNI_OnLoad @0x1000 = call 0x1040; mov eax,0x10006; ret
        put(b, 0x400, 0xE8, 0x3B, 0, 0, 0, 0xB8, 0x06, 0x00, 0x01, 0x00, 0xC3);
        put(b, 0x410, 0x43, 0x8D, 0x04, 0x08, 0xC3);                   // Java_p_Lib_add__II: lea eax,[r8+r9]; ret
        put(b, 0x420, 0x31, 0xC0, 0xC3);                               // Java_p_Lib_name: xor eax,eax; ret
        put(b, 0x430, 0xC3);                                           // the hex-named native's implementation
        put(b, 0x440, 0x48, 0x8B, 0x01, 0xFF, 0x90, 0xB8, 0x06, 0, 0, 0xC3); // mov rax,[rcx]; call [rax+0x6b8]; ret
        // .rdata (file 0x600 = RVA 0x2000): export directory, names, strings, imports
        int r = 0x600 - 0x2000;
        b.putInt(r + 0x2000 + 12, 0x2050).putInt(r + 0x2000 + 16, 1).putInt(r + 0x2000 + 20, 3).putInt(r + 0x2000 + 24, 3);
        b.putInt(r + 0x2000 + 28, 0x2028).putInt(r + 0x2000 + 32, 0x2034).putInt(r + 0x2000 + 36, 0x2040);
        b.putInt(r + 0x2028, 0x1000).putInt(r + 0x202c, 0x1010).putInt(r + 0x2030, 0x1020);
        b.putInt(r + 0x2034, 0x2060).putInt(r + 0x2038, 0x2070).putInt(r + 0x203c, 0x2090);
        b.putShort(r + 0x2040, (short) 0).putShort(r + 0x2042, (short) 1).putShort(r + 0x2044, (short) 2);
        text(b, r + 0x2050, "fixture.dll");
        text(b, r + 0x2060, "JNI_OnLoad");
        text(b, r + 0x2070, "Java_p_Lib_add__II");
        text(b, r + 0x2090, "Java_p_Lib_name");
        text(b, r + 0x20a0, HEX_NATIVE);
        text(b, r + 0x20c0, HEX_DESCRIPTOR);
        b.putInt(r + 0x2100, 0x2130).putInt(r + 0x2100 + 12, 0x2170).putInt(r + 0x2100 + 16, 0x2140);
        b.putLong(r + 0x2130, 0x2150).putLong(r + 0x2140, 0x2150);
        text(b, r + 0x2152, "GetTickCount");
        text(b, r + 0x2170, "KERNEL32.dll");
        // .data (file 0x800 = RVA 0x3000): JNINativeMethod {name, signature, fnPtr} with absolute addresses
        b.putLong(0x800, IMAGE_BASE + 0x20a0).putLong(0x808, IMAGE_BASE + 0x20c0).putLong(0x810, IMAGE_BASE + 0x1030);
        return b.array();
    }

    /** ELF64 x86-64 shared object exporting Java_p_Lib_add__II and importing puts (section headers only, no program headers). */
    static byte[] elf() {
        var b = ByteBuffer.allocate(0x340).order(ByteOrder.LITTLE_ENDIAN);
        put(b, 0, 0x7f, 'E', 'L', 'F', 2, 1, 1);
        b.putShort(16, (short) 3).putShort(18, (short) 62).putInt(20, 1).putLong(24, 0x1000).putLong(40, 0x200);
        b.putShort(52, (short) 64).putShort(58, (short) 64).putShort(60, (short) 5).putShort(62, (short) 4);
        put(b, 0x40, 0x31, 0xC0, 0xC3);                                   // .text
        // .dynstr at 0x80: "\0Java_p_Lib_add__II\0puts\0"
        text(b, 0x81, "Java_p_Lib_add__II"); text(b, 0x94, "puts");
        // .dynsym at 0xa0: null, Java_p_Lib_add__II (GLOBAL FUNC, section 1), puts (GLOBAL FUNC, undefined)
        b.putInt(0xb8, 1).put(0xbc, (byte) 0x12).putShort(0xbe, (short) 1).putLong(0xc0, 0x1000);
        b.putInt(0xd0, 0x14).put(0xd4, (byte) 0x12).putShort(0xd6, (short) 0);
        // .shstrtab at 0x100: "\0.text\0.dynsym\0.dynstr\0.shstrtab\0"
        text(b, 0x101, ".text"); text(b, 0x107, ".dynsym"); text(b, 0x10f, ".dynstr"); text(b, 0x117, ".shstrtab");
        elfSection(b, 0x200 + 64, 1, 1, 6, 0x1000, 0x40, 3, 0, 0);
        elfSection(b, 0x200 + 128, 7, 11, 2, 0, 0xa0, 72, 3, 24);
        elfSection(b, 0x200 + 192, 15, 3, 2, 0, 0x80, 0x19, 0, 0);
        elfSection(b, 0x200 + 256, 23, 3, 0, 0, 0x100, 0x21, 0, 0);
        return b.array();
    }

    /** Class {@code p/Lib} with natives add(II)I, name()String and the hex-named one, plus the given resources. */
    static Path jar(Path directory, Map<String,byte[]> resources) throws IOException {
        var writer = new ClassWriter(0);
        writer.visit(V1_8, ACC_PUBLIC | ACC_SUPER, "p/Lib", null, "java/lang/Object", null);
        writer.visitMethod(ACC_PUBLIC | ACC_STATIC | ACC_NATIVE, "add", "(II)I", null, null).visitEnd();
        writer.visitMethod(ACC_PUBLIC | ACC_STATIC | ACC_NATIVE, "name", "()Ljava/lang/String;", null, null).visitEnd();
        writer.visitMethod(ACC_STATIC | ACC_NATIVE, HEX_NATIVE, HEX_DESCRIPTOR, null, null).visitEnd();
        writer.visitEnd();
        Path jar = directory.resolve("natives.jar");
        try (var out = new JarOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new JarEntry("p/Lib.class")); out.write(writer.toByteArray()); out.closeEntry();
            for (var resource : resources.entrySet()) { out.putNextEntry(new JarEntry(resource.getKey())); out.write(resource.getValue()); out.closeEntry(); }
        }
        return jar;
    }

    private static void section(ByteBuffer b, int at, String name, int rva, int virtualSize, int pointer, int rawSize, long flags) {
        byte[] bytes = name.getBytes(StandardCharsets.US_ASCII);
        for (int i = 0; i < bytes.length && i < 8; i++) b.put(at + i, bytes[i]);
        b.putInt(at + 8, virtualSize).putInt(at + 12, rva).putInt(at + 16, rawSize).putInt(at + 20, pointer).putInt(at + 36, (int) flags);
    }
    private static void elfSection(ByteBuffer b, int at, int name, int type, long flags, long address, long offset, long size, int link, long entrySize) {
        b.putInt(at, name).putInt(at + 4, type).putLong(at + 8, flags).putLong(at + 16, address).putLong(at + 24, offset)
            .putLong(at + 32, size).putInt(at + 40, link).putLong(at + 56, entrySize);
    }
    private static void put(ByteBuffer b, int at, int... values) { for (int i = 0; i < values.length; i++) b.put(at + i, (byte) values[i]); }
    private static void text(ByteBuffer b, int at, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.US_ASCII);
        for (int i = 0; i < bytes.length; i++) b.put(at + i, bytes[i]);
    }
}
