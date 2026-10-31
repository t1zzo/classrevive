package dev.classrevive.pipeline;

import dev.classrevive.cfr.CfrEngine;
import dev.classrevive.engine.DecompilerEngine;
import dev.classrevive.jadx.JadxEngine;
import dev.classrevive.procyon.ProcyonEngine;
import dev.classrevive.vineflower.VineflowerEngine;
import java.io.IOException;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.zip.*;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 2BX: an obfuscated jar's shape. An obfuscator stripped {@code InnerClasses} (and here also {@code EnclosingMethod},
 * {@code NestHost}, {@code NestMembers}), so engines disagree on {@code p/Outer$Helper}: JADX nests it inside
 * {@code Outer.java} by name, the others write {@code Outer$Helper.java}. The enum {@code p/Outer$Level} has a constant
 * body {@code Outer$Level$1}, which the top-level form writes as a class extending a final enum. Nothing runs.
 */
class NestingJobTest {
    @TempDir Path directory;
    static final String SOURCE = """
        package p;
        public class Outer {
            int value = 3;
            public int twice() { return new Helper().scale(value) + Level.HIGH.weight(); }
            static class Helper { int scale(int x) { return x * 2; } }
            public enum Level {
                LOW(1), HIGH(2) { @Override public int weight() { return code * 10; } };
                final int code;
                Level(int code) { this.code = code; }
                public int weight() { return code; }
            }
        }
        """;

    /** javac output with every nesting attribute removed by ASM, as an obfuscator left the classes of a real jar. */
    Path jar() throws IOException {
        Path src = Files.createDirectories(directory.resolve("src/p")), out = Files.createDirectories(directory.resolve("classes"));
        Files.writeString(src.resolve("Outer.java"), SOURCE);
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "--release", "8", "-Xlint:-options", "-proc:none",
            "-d", out.toString(), src.resolve("Outer.java").toString()));
        Path jar = directory.resolve("stripped.jar");
        try (var zip = new ZipOutputStream(Files.newOutputStream(jar)); var classes = Files.list(out.resolve("p"))) {
            for (var file : classes.sorted().toList()) {
                var writer = new ClassWriter(0);
                new ClassReader(Files.readAllBytes(file)).accept(new ClassVisitor(Opcodes.ASM9, writer) {
                    @Override public void visitInnerClass(String name, String outer, String inner, int access) {}
                    @Override public void visitOuterClass(String owner, String name, String descriptor) {}
                    @Override public void visitNestHost(String host) {}
                    @Override public void visitNestMember(String member) {}
                }, 0);
                zip.putNextEntry(new ZipEntry("p/" + file.getFileName())); zip.write(writer.toByteArray()); zip.closeEntry();
            }
        }
        try (var zip = new ZipFile(jar.toFile())) {
            var reader = new ClassReader(zip.getInputStream(zip.getEntry("p/Outer.class")).readAllBytes());
            var inner = new ArrayList<String>();
            reader.accept(new ClassVisitor(Opcodes.ASM9) {
                @Override public void visitInnerClass(String name, String outer, String innerName, int access) { inner.add(name); }
            }, 0);
            assertEquals(List.of(), inner, "the fixture has no InnerClasses attribute");
        }
        return jar;
    }

    @Test void theSelectedSourcesDeclareEachBinaryClassOnceAndFoldTheEnumConstantBody() throws Exception {
        Path out = directory.resolve("out");
        List<DecompilerEngine> engines = List.of(new CfrEngine(), new JadxEngine(), new ProcyonEngine(), new VineflowerEngine());
        var job = new DecompilationJob().runVerified(jar(), out, engines, Duration.ofSeconds(90));
        String report = Files.readString(out.resolve("selection/selection.txt"));
        // The engines really disagree: JADX nests Helper by name, CFR writes it as its own file.
        assertTrue(Files.exists(out.resolve("cfr/sources/p/Outer$Helper.java")), report);
        assertFalse(Files.exists(out.resolve("jadx/sources/p/Outer$Helper.java")), report);
        assertTrue(Files.exists(out.resolve("cfr/sources/p/Outer$Level$1.java")), report);
        assertTrue(job.selection().verification().complete(), report);
        for (var attempt : job.selection().tried()) {
            var origins = attempt.origins();
            // No attempt combines JADX's nesting Outer.java with another engine's top-level $ file.
            if ("jadx".equals(origins.get("p/Outer.java")))
                assertTrue(origins.keySet().stream().noneMatch(file -> file.startsWith("p/Outer$")), attempt.id() + " " + origins);
            for (var line : attempt.verification().toString().split("\n")) assertFalse(line.contains("same.binary.name"), line);
        }
        // Vineflower writes Outer$Level$1 as its own file; folded into HIGH's body, its whole tree compiles with every signature.
        assertEquals("vineflower", job.selection().candidate(), report);
        assertEquals(List.of(new dev.classrevive.verification.EnumBodyFolding.Fold("p/Outer$Level.java", "p/Outer$Level$1.java", "HIGH")),
            job.selection().folds(), report);
        assertTrue(Files.exists(out.resolve("vineflower/sources/p/Outer$Level$1.java")), "engine output is kept as written");
        assertFalse(Files.exists(out.resolve("selection/sources/p/Outer$Level$1.java")), report);
        String level = Files.readString(out.resolve("selection/sources/p/Outer$Level.java"));
        assertTrue(level.matches("(?s).*HIGH\\(2\\) \\{.*return this\\.code \\* 10;.*"), level);
        assertTrue(report.contains("p/Outer$Level$1.java <- vineflower (folded into p/Outer$Level.java)"), report);
        assertTrue(Files.readString(out.resolve("report.json")).contains("\"enum_bodies_folded\""), "report.json lists the folds");
    }
}
