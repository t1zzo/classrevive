package dev.classrevive.pipeline;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.*;
import java.util.zip.*;
import org.objectweb.asm.ClassWriter;
import static org.objectweb.asm.Opcodes.*;

/**
 * Phase 2CE test inputs, written byte by byte by our own code: minimal PE32+ images (an import table, an optional extra
 * section such as a high-entropy {@code .vlizer}, a CLR header, marker text), a jar, a zip and a fake certificate. They are
 * only read as bytes; nothing loads or runs them.
 */
final class ExecutableFixtures {
    private ExecutableFixtures() {}

    static final int RDATA = 0x600, RDATA_RVA = 0x2000, EXTRA = 0xe00;

    /**
     * {@code .text} (a ret), {@code .rdata} with an import table of KERNEL32.dll {@code imports} and {@code marker} as text,
     * and an optional third section {@code extraName} holding {@code extraData}; {@code clr} adds a CLR header directory.
     */
    static byte[] pe(List<String> imports, String marker, String extraName, byte[] extraData, boolean clr, int subsystem) {
        boolean extra = extraName != null;
        int extraRaw = extra ? align(extraData.length, 0x200) : 0;
        var b = ByteBuffer.allocate(EXTRA + extraRaw).order(ByteOrder.LITTLE_ENDIAN);
        b.put(0, (byte) 'M').put(1, (byte) 'Z').putInt(0x3c, 0x40);
        b.put(0x40, (byte) 'P').put(0x41, (byte) 'E');
        int coff = 0x44;
        b.putShort(coff, (short) 0x8664).putShort(coff + 2, (short) (extra ? 3 : 2)).putShort(coff + 16, (short) 240).putShort(coff + 18, (short) 0x22);
        int opt = coff + 20;
        b.putShort(opt, (short) 0x20b).putInt(opt + 16, 0x1000).putLong(opt + 24, 0x140000000L).putInt(opt + 32, 0x1000).putInt(opt + 36, 0x200);
        b.putShort(opt + 40, (short) 6).putShort(opt + 48, (short) 6).putInt(opt + 56, extra ? 0x3000 + align(extraData.length, 0x1000) : 0x3000)
            .putInt(opt + 60, 0x400).putShort(opt + 68, (short) subsystem).putInt(opt + 108, 16);
        int dirs = opt + 112;
        if (!imports.isEmpty()) b.putInt(dirs + 8, RDATA_RVA).putInt(dirs + 12, 40);
        if (clr) b.putInt(dirs + 112, 0x2700).putInt(dirs + 116, 72);
        int table = opt + 240;
        section(b, table, ".text", 0x1000, 0x10, 0x400, 0x200, 0x60000020);
        section(b, table + 40, ".rdata", RDATA_RVA, 0x800, RDATA, 0x800, 0x40000040);
        if (extra) {
            section(b, table + 80, extraName, 0x3000, extraData.length, EXTRA, extraRaw, 0xe0000060);
            b.put(EXTRA, extraData);
        }
        b.put(0x400, (byte) 0xc3);
        if (!imports.isEmpty()) {
            b.putInt(rdata(RDATA_RVA), 0x2040).putInt(rdata(RDATA_RVA) + 12, 0x2600).putInt(rdata(RDATA_RVA) + 16, 0x2040);
            for (int i = 0; i < imports.size(); i++) {
                b.putLong(rdata(0x2040) + 8 * i, 0x2100 + 32 * i);
                text(b, rdata(0x2100 + 32 * i) + 2, imports.get(i));
            }
            text(b, rdata(0x2600), "KERNEL32.dll");
        }
        if (marker != null) text(b, rdata(0x2680), marker);
        return b.array();
    }

    static byte[] pe(List<String> imports) { return pe(imports, null, null, null, false, 3); }

    /** A jar with {@code p/Hello} ({@code int value()}) and a manifest. */
    static byte[] jar() throws IOException {
        var writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(V1_8, ACC_PUBLIC | ACC_SUPER, "p/Hello", null, "java/lang/Object", null);
        var init = writer.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode(); init.visitVarInsn(ALOAD, 0); init.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(RETURN); init.visitMaxs(0, 0); init.visitEnd();
        var value = writer.visitMethod(ACC_PUBLIC, "value", "()I", null, null);
        value.visitCode(); value.visitIntInsn(BIPUSH, 42); value.visitInsn(IRETURN); value.visitMaxs(0, 0); value.visitEnd();
        writer.visitEnd();
        return zip(Map.of("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\r\n\r\n".getBytes(StandardCharsets.US_ASCII),
            "p/Hello.class", writer.toByteArray()));
    }

    /** Fixed entry time, so the same entries always give the same zip bytes (DOS times have a two-second resolution). */
    static final LocalDateTime ENTRY_TIME = LocalDateTime.of(2020, 1, 1, 0, 0);

    static ZipEntry entry(String name) {
        var item = new ZipEntry(name);
        item.setTimeLocal(ENTRY_TIME);
        return item;
    }

    static byte[] zip(Map<String,byte[]> entries) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var out = new ZipOutputStream(bytes)) {
            for (var entry : new TreeMap<>(entries).entrySet()) { out.putNextEntry(entry(entry.getKey())); out.write(entry.getValue()); out.closeEntry(); }
        }
        return bytes.toByteArray();
    }

    /** A WIN_CERTIFICATE (revision 2.0, PKCS#7) whose blob holds a common-name attribute; not a real signature. */
    static byte[] certificate(String commonName) {
        byte[] name = commonName.getBytes(StandardCharsets.US_ASCII);
        int size = align(8 + 7 + name.length + 16, 8);
        var b = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(0, size).putShort(4, (short) 0x0200).putShort(6, (short) 2);
        b.put(8, new byte[] {0x30, 0x10, 6, 3, 0x55, 4, 3, 0x0c, (byte) name.length}).put(17, name);
        return b.array();
    }

    /** Appends a certificate at the next 8-byte boundary and points the PE certificate directory at it. */
    static byte[] signed(byte[] image, byte[] certificate) {
        int at = align(image.length, 8);
        var b = ByteBuffer.allocate(at + certificate.length).order(ByteOrder.LITTLE_ENDIAN);
        b.put(image).put(at, certificate);
        b.putInt(0x58 + 112 + 32, at).putInt(0x58 + 112 + 36, certificate.length);
        return b.array();
    }

    static byte[] concat(byte[]... parts) {
        var out = new ByteArrayOutputStream();
        for (byte[] part : parts) out.writeBytes(part);
        return out.toByteArray();
    }

    static byte[] random(int size, long seed) {
        byte[] bytes = new byte[size];
        new Random(seed).nextBytes(bytes);
        return bytes;
    }

    static int align(int value, int alignment) { return (value + alignment - 1) / alignment * alignment; }
    private static int rdata(int rva) { return rva - RDATA_RVA + RDATA; }
    private static void section(ByteBuffer b, int at, String name, int rva, int virtualSize, int pointer, int rawSize, long flags) {
        byte[] bytes = name.getBytes(StandardCharsets.US_ASCII);
        for (int i = 0; i < bytes.length && i < 8; i++) b.put(at + i, bytes[i]);
        b.putInt(at + 8, virtualSize).putInt(at + 12, rva).putInt(at + 16, rawSize).putInt(at + 20, pointer).putInt(at + 36, (int) flags);
    }
    private static void text(ByteBuffer b, int at, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.US_ASCII);
        for (int i = 0; i < bytes.length; i++) b.put(at + i, bytes[i]);
    }
}
