package dev.classrevive.intake;

import dev.classrevive.model.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.io.*;
import java.util.*;
import java.util.zip.*;
import static org.junit.jupiter.api.Assertions.*;

class InventoryReaderTest {
    @TempDir Path directory;
    Path jar(String... names) throws Exception {
        Path file = directory.resolve("sample.jar");
        try (var out = new ZipOutputStream(Files.newOutputStream(file))) {
            for (String name : names) {
                out.putNextEntry(new ZipEntry(name));
                out.write(name.endsWith(".class") ? classBytes() : "resource".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                out.closeEntry();
            }
        }
        return file;
    }
    byte[] classBytes() throws Exception {
        try (var in = InventoryReaderTest.class.getResourceAsStream("/dev/classrevive/intake/InventoryReaderTest.class")) {
            return in.readAllBytes();
        }
    }
    Inventory inspect(Path file) { return new InventoryReader().inspect(file, Limits.defaults()); }
    void has(Inventory value, Diagnostic.Code code) {
        assertTrue(value.diagnostics().stream().anyMatch(d -> d.code() == code), value.diagnostics().toString());
    }
    @Test void inventoriesClassAndHashesWithoutModifyingInput() throws Exception {
        Path file = directory.resolve("Sample.class"); Files.write(file, classBytes());
        var before = Files.readAllBytes(file);
        var result = inspect(file);
        assertTrue(result.successful(), result.diagnostics().toString());
        assertEquals(1, result.entries().size());
        assertEquals("dev/classrevive/intake/InventoryReaderTest", result.entries().getFirst().classInfo().name());
        assertEquals(64, result.sha256().length());
        assertArrayEquals(before, Files.readAllBytes(file));
    }
    @Test void inventoriesResourcesAndMultiReleaseVariantsDeterministically() throws Exception {
        var result = inspect(jar("z.txt", "Thing.class", "META-INF/versions/11/Thing.class"));
        assertTrue(result.successful(), result.diagnostics().toString());
        assertEquals(List.of("META-INF/versions/11/Thing.class", "Thing.class", "z.txt"), result.entries().stream().map(Inventory.Entry::name).toList());
        assertEquals(2, result.entries().stream().filter(e -> e.classInfo() != null).count());
    }
    @Test void rejectsTraversalAndWindowsPaths() throws Exception {
        for (var name : List.of("../escape.class", "/absolute.class", "C:/evil.class", "a\\evil.class", "a/../evil.class", "a/CON.txt", "a/file:stream", "a./file")) {
            has(inspect(jar(name)), Diagnostic.Code.UNSAFE_ENTRY);
        }
        assertFalse(Files.exists(directory.getParent().resolve("escape.class")));
    }
    @Test void keepsTheFirstOfResourcesThatCollideOnlyByCase() throws Exception {
        var result = inspect(jar("a.txt", "A.txt", "dir/", "DIR/"));
        assertTrue(result.successful(), result.diagnostics().toString());
        assertEquals(List.of("a.txt"), result.entries().stream().map(Inventory.Entry::name).toList());
        assertEquals(List.of(new Diagnostic(Diagnostic.Code.CASE_COLLISION_SKIPPED, "A.txt", "Left out: path collides by case with a.txt")),
            result.notices());
    }
    @Test void keepsClassesThatCollideOnlyByCaseAndReportsThem() throws Exception {
        var result = inspect(jar("p/a.txt", "p/a.class", "p/B.class", "p/A.class", "p/b.class", "p/A.CLASS"));
        assertTrue(result.successful(), result.diagnostics().toString());
        assertEquals(List.of("p/A.class", "p/B.class", "p/a.class", "p/a.txt", "p/b.class"), result.entries().stream().map(Inventory.Entry::name).toList());
        assertEquals(List.of(
            new Diagnostic(Diagnostic.Code.CASE_COLLISION_SKIPPED, "p/A.CLASS", "Left out: path collides by case with p/a.class"),
            new Diagnostic(Diagnostic.Code.CASE_COLLISION, "p/A.class", "Class entries differ only in letter case: p/A.class, p/a.class"),
            new Diagnostic(Diagnostic.Code.CASE_COLLISION, "p/B.class", "Class entries differ only in letter case: p/B.class, p/b.class")),
            result.notices());
    }
    @Test void aClassWinsOverAResourceThatCollidesWithItByCase() throws Exception {
        var result = inspect(jar("p/A.CLASS", "p/a.class"));
        assertTrue(result.successful(), result.diagnostics().toString());
        assertEquals(List.of("p/a.class"), result.entries().stream().map(Inventory.Entry::name).toList());
        assertEquals(Diagnostic.Code.CASE_COLLISION_SKIPPED, result.notices().getFirst().code());
    }
    @Test void stillRejectsExactDuplicates() throws Exception {
        // ZipOutputStream refuses duplicate names, so a second, same-length name is patched in both headers.
        Path file = jar("a.txt", "b.txt");
        byte[] bytes = Files.readAllBytes(file);
        byte[] from = "b.txt".getBytes(java.nio.charset.StandardCharsets.US_ASCII), to = "a.txt".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        for (int i = 0; i + from.length <= bytes.length; i++)
            if (Arrays.equals(bytes, i, i + from.length, from, 0, from.length)) System.arraycopy(to, 0, bytes, i, to.length);
        Files.write(file, bytes);
        has(inspect(file), Diagnostic.Code.DUPLICATE_ENTRY);
    }
    @Test void boundsEntryCount() throws Exception {
        has(new InventoryReader().inspect(jar("a", "b"), new Limits(100_000, 10_000, 100_000, 1)), Diagnostic.Code.RESOURCE_LIMIT);
    }
    @Test void leavesOutOversizedResourcesUnreadButStillBoundsClasses() throws Exception {
        var limits = new Limits(1_000_000, 3, 1_000_000, 10);
        var result = new InventoryReader().inspect(jar("a"), limits);
        assertTrue(result.successful(), result.diagnostics().toString());
        assertEquals(List.of(), result.entries());
        assertEquals(List.of(new Diagnostic(Diagnostic.Code.OVERSIZED_RESOURCE_SKIPPED, "a",
            "Left out unread: resource declares 8 bytes, over the 3-byte entry limit")), result.notices());
        has(new InventoryReader().inspect(jar("a", "Thing.class"), limits), Diagnostic.Code.RESOURCE_LIMIT);
    }
    @Test void boundsAggregateExpandedBytes() throws Exception {
        has(new InventoryReader().inspect(jar("a", "b"), new Limits(100_000, 100, 10, 10)), Diagnostic.Code.RESOURCE_LIMIT);
    }
    @Test void boundsInputSize() throws Exception {
        has(new InventoryReader().inspect(jar("a"), new Limits(1, 100, 100, 10)), Diagnostic.Code.RESOURCE_LIMIT);
    }
    @Test void reportsMalformedClassAndKeepsGoodSibling() throws Exception {
        var path = directory.resolve("broken.jar");
        try (var out = new ZipOutputStream(Files.newOutputStream(path))) {
            out.putNextEntry(new ZipEntry("bad.class")); out.write(new byte[10]); out.closeEntry();
            out.putNextEntry(new ZipEntry("good.class")); out.write(classBytes()); out.closeEntry();
        }
        var result = inspect(path);
        has(result, Diagnostic.Code.INVALID_CLASS);
        assertEquals(2, result.entries().size());
        assertNotNull(result.entries().get(1).classInfo());
    }
    @Test void reportsMissingDirectoryAndWrongExtension() throws Exception {
        has(inspect(directory.resolve("missing.jar")), Diagnostic.Code.INVALID_INPUT);
        has(inspect(directory), Diagnostic.Code.INVALID_INPUT);
        var path = directory.resolve("fake.exe"); Files.write(path, new byte[10]);
        has(inspect(path), Diagnostic.Code.INVALID_INPUT);
    }
    @Test void rejectsInvalidAndTruncatedZip() throws Exception {
        var path = directory.resolve("bad.jar"); Files.write(path, new byte[100]);
        has(inspect(path), Diagnostic.Code.INVALID_ARCHIVE);
        byte[] valid = Files.readAllBytes(jar("a"));
        Files.write(path, Arrays.copyOf(valid, valid.length - 15));
        has(inspect(path), Diagnostic.Code.INVALID_ARCHIVE);
    }
    @Test void acceptsEmptyArchive() throws Exception {
        var result = inspect(jar());
        assertTrue(result.successful()); assertTrue(result.entries().isEmpty());
    }
    @Test void identifiesFutureBytecode() throws Exception {
        var bytes = classBytes(); bytes[6] = 0; bytes[7] = 100;
        var path = directory.resolve("future.class"); Files.write(path, bytes);
        var result = inspect(path);
        has(result, Diagnostic.Code.UNSUPPORTED_CLASS_VERSION);
        var diagnostic = result.diagnostics().stream()
            .filter(value -> value.code() == Diagnostic.Code.UNSUPPORTED_CLASS_VERSION).findFirst().orElseThrow();
        assertTrue(diagnostic.message().contains("major 100"));
        assertTrue(diagnostic.message().contains("Java 56"));
        assertTrue(diagnostic.message().contains("supports major versions 45..72"));
    }
    @Test void inventoriesJava25BytecodeWithoutVersionDiagnostic() throws Exception {
        var bytes = classBytes(); bytes[6] = 0; bytes[7] = 69;
        var path = directory.resolve("java25.class"); Files.write(path, bytes);
        var result = inspect(path);
        assertTrue(result.successful(), result.diagnostics().toString());
        assertTrue(result.diagnostics().isEmpty(), result.diagnostics().toString());
        var info = result.entries().get(0).classInfo();
        assertEquals(69, info.majorVersion());
        assertEquals(25, info.javaVersion());
    }
    @Test void rejectsInvalidLimits() {
        assertThrows(IllegalArgumentException.class, () -> new Limits(0, 1, 1, 1));
    }
    @Test void containsDeepAnnotationParserFailureAndKeepsSibling() throws Exception {
        var payload = new ByteArrayOutputStream();
        try (var data = new DataOutputStream(payload)) {
            data.writeInt(0xcafebabe); data.writeShort(0); data.writeShort(52);
            data.writeShort(8);
            data.writeByte(1); data.writeUTF("Hostile");
            data.writeByte(7); data.writeShort(1);
            data.writeByte(1); data.writeUTF("java/lang/Object");
            data.writeByte(7); data.writeShort(3);
            data.writeByte(1); data.writeUTF("RuntimeVisibleAnnotations");
            data.writeByte(1); data.writeUTF("LHostile;");
            data.writeByte(1); data.writeUTF("value");
            data.writeShort(0x21); data.writeShort(2); data.writeShort(4);
            data.writeShort(0); data.writeShort(0); data.writeShort(0);
            data.writeShort(1); data.writeShort(5);
            data.writeInt(8 + 20_000 * 3 + 3);
            data.writeShort(1); data.writeShort(6); data.writeShort(1); data.writeShort(7);
            for (int i = 0; i < 20_000; i++) { data.writeByte('['); data.writeShort(1); }
            data.writeByte('s'); data.writeShort(1);
        }
        var path = directory.resolve("deep.jar");
        try (var zip = new ZipOutputStream(Files.newOutputStream(path))) {
            zip.putNextEntry(new ZipEntry("bad.class")); zip.write(payload.toByteArray()); zip.closeEntry();
            zip.putNextEntry(new ZipEntry("good.class")); zip.write(classBytes()); zip.closeEntry();
        }
        var result = inspect(path);
        has(result, Diagnostic.Code.INVALID_CLASS);
        assertEquals(2, result.entries().size());
        assertNotNull(result.entries().get(1).classInfo());
    }
}
