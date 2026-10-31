package dev.classrevive.dex;

import dev.classrevive.engine.EngineResult;
import java.io.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.zip.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import static org.junit.jupiter.api.Assertions.*;

/** Phase 2CW: dex2jar in its worker on the committed fixtures (benchmarks/android); nothing converted is loaded or run. */
class DexConversionTest {
    static final Path FIXTURES = Path.of("../../benchmarks/android");
    static final Duration TIMEOUT = Duration.ofMinutes(3);
    @TempDir Path directory;

    @Test void convertsBothDexFilesOfTheApkWithTheirDebugInformation() throws Exception {
        var result = DexConversion.convert(FIXTURES.resolve("notes.apk"), directory.resolve("run"), TIMEOUT);
        assertEquals(EngineResult.Status.OUTPUT_PRODUCED, result.worker().status(), result.worker().message());
        assertEquals(List.of("classes.dex", "classes2.dex"), result.dexFiles().stream().map(DexConversion.DexFile::name).toList());
        var classes = classes(result.jar());
        assertEquals(Set.of("demo/notes/MainActivity", "demo/notes/Note", "demo/notes/NoteStore", "demo/notes/NoteStore$1",
            "demo/notes/NoteStore$Stats", "demo/notes/Priority", "demo/util/Strings"), classes.keySet());
        assertEquals(7, result.classes());
        assertTrue(result.failedMethods().isEmpty(), result.failedMethods().toString());
        assertTrue(result.duplicates().isEmpty());
        // The framework types stay references: android.* is not in the dex, so it is not in the jar.
        assertEquals("android/app/Activity", classes.get("demo/notes/MainActivity").superName);
        // Line numbers come through (skipDebug off). Local variable names do not: dex2jar reallocates registers and writes
        // no LocalVariableTable, so engines name locals themselves.
        var words = classes.get("demo/notes/Note").methods.stream().filter(m -> m.name.equals("words")).findFirst().orElseThrow();
        assertTrue(java.util.stream.StreamSupport.stream(words.instructions.spliterator(), false)
            .anyMatch(insn -> insn instanceof org.objectweb.asm.tree.LineNumberNode line && line.line == 33), "line numbers survive");
        // Normalized for Java decompilers: Note.edit reuses its String parameter's register for the new Note, so its
        // locals were split; NoteStore$1.this$0 and Priority.$VALUES are synthetic again, as javac made them.
        assertTrue(result.movedParameters() > 0, "methods with locals split: " + result.movedParameters());
        assertTrue(result.syntheticFields() >= 2, "fields marked synthetic: " + result.syntheticFields());
        assertNotEquals(0, field(classes.get("demo/notes/NoteStore$1"), "this$0").access & org.objectweb.asm.Opcodes.ACC_SYNTHETIC);
        assertNotEquals(0, field(classes.get("demo/notes/Priority"), "$VALUES").access & org.objectweb.asm.Opcodes.ACC_SYNTHETIC);
        var edit = classes.get("demo/notes/Note").methods.stream().filter(m -> m.name.equals("edit")).findFirst().orElseThrow();
        assertTrue(java.util.stream.StreamSupport.stream(edit.instructions.spliterator(), false).noneMatch(insn ->
            insn instanceof org.objectweb.asm.tree.VarInsnNode store && store.getOpcode() == org.objectweb.asm.Opcodes.ASTORE && store.var == 1),
            "the String parameter's slot is no longer written");
        // Entries are sorted and carry no time, so the same dex always gives the same jar.
        var again = DexConversion.convert(FIXTURES.resolve("notes.apk"), directory.resolve("again"), TIMEOUT);
        assertArrayEquals(Files.readAllBytes(result.jar()), Files.readAllBytes(again.jar()));
    }

    @Test void convertsASingleDexFile() throws Exception {
        var result = DexConversion.convert(FIXTURES.resolve("notes.dex"), directory.resolve("run"), TIMEOUT);
        assertEquals(EngineResult.Status.OUTPUT_PRODUCED, result.worker().status(), result.worker().message());
        assertEquals(6, result.classes());
        assertFalse(classes(result.jar()).containsKey("demo/util/Strings"), "Strings lives in classes2.dex only");
    }

    @Test void keepsTheFirstOfTwoClassesWithTheSameNameAndListsTheOther() throws Exception {
        byte[] dex = Files.readAllBytes(FIXTURES.resolve("notes.dex"));
        Path apk = directory.resolve("twice.apk");
        try (var zip = new ZipOutputStream(Files.newOutputStream(apk))) {
            for (String name : List.of("classes.dex", "classes2.dex", "assets/payload.dex")) {
                zip.putNextEntry(new ZipEntry(name));
                zip.write(dex);
                zip.closeEntry();
            }
        }
        var result = DexConversion.convert(apk, directory.resolve("run"), TIMEOUT);
        assertEquals(6, result.classes());
        assertEquals(6, result.duplicateCount());
        assertTrue(result.duplicates().stream().allMatch(d -> d.keptFrom().equals("classes.dex") && d.droppedFrom().equals("classes2.dex")));
        // Only root classes*.dex are what the runtime loads; a dex under assets/ is data and is not converted.
        assertEquals(List.of("classes.dex", "classes2.dex"), result.dexFiles().stream().map(DexConversion.DexFile::name).toList());
    }

    @Test void anArchiveWithoutDexProducesNoJarAndSaysWhy() throws Exception {
        Path zip = directory.resolve("empty.apk");
        try (var out = new ZipOutputStream(Files.newOutputStream(zip))) {
            out.putNextEntry(new ZipEntry("AndroidManifest.xml"));
            out.write(new byte[] {3, 0, 8, 0, 8, 0, 0, 0});
            out.closeEntry();
        }
        var result = DexConversion.convert(zip, directory.resolve("run"), TIMEOUT);
        assertNull(result.jar());
        assertEquals(EngineResult.Status.FAILED, result.worker().status());
        assertTrue(Files.readString(directory.resolve("run/engine.log")).contains("No classes.dex at the archive root"));
    }

    private static org.objectweb.asm.tree.FieldNode field(ClassNode node, String name) {
        return node.fields.stream().filter(f -> f.name.equals(name)).findFirst().orElseThrow();
    }

    private static Map<String, ClassNode> classes(Path jar) throws IOException {
        var classes = new TreeMap<String, ClassNode>();
        try (var zip = new ZipFile(jar.toFile())) {
            for (var entries = zip.entries(); entries.hasMoreElements(); ) {
                var entry = entries.nextElement();
                var node = new ClassNode();
                try (var in = zip.getInputStream(entry)) { new ClassReader(in.readAllBytes()).accept(node, 0); }
                classes.put(node.name, node);
            }
        }
        return classes;
    }
}
