package dev.classrevive.bytecode;

import org.junit.jupiter.api.Test;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.*;

class ClassInspectorTest {
    @TempDir Path directory;
    byte[] fixture() throws Exception {
        var source = directory.resolve("Sample.java");
        Files.writeString(source, "public class Sample implements Runnable { public int value; static { System.setProperty(\"classrevive.executed\", \"yes\"); } public void run() {} public int add(int a,int b) { return a+b; } }");
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null,
            "--release", "8", "-proc:none", source.toString()));
        return Files.readAllBytes(directory.resolve("Sample.class"));
    }
    @Test void inventoriesJava8WithoutExecutingInitializer() throws Exception {
        System.clearProperty("classrevive.executed");
        var info = new ClassInspector().inspect(fixture());
        assertEquals("Sample", info.name());
        assertEquals("java/lang/Object", info.superName());
        assertEquals(52, info.majorVersion());
        assertEquals(8, info.javaVersion());
        assertEquals(java.util.List.of("java/lang/Runnable"), info.interfaces());
        assertTrue(info.fields().stream().anyMatch(f -> f.name().equals("value") && f.descriptor().equals("I")));
        assertTrue(info.methods().stream().anyMatch(m -> m.name().equals("add") && m.descriptor().equals("(II)I")));
        assertNull(System.getProperty("classrevive.executed"));
    }
    @Test void rejectsBadMagic() {
        assertThrows(IllegalArgumentException.class, () -> new ClassInspector().inspect(new byte[20]));
    }
    @Test void rejectsTruncation() throws Exception {
        var bytes = fixture();
        assertThrows(IllegalArgumentException.class, () -> new ClassInspector().inspect(Arrays.copyOf(bytes, bytes.length / 2)));
    }
    @Test void reportsDetectedAndSupportedClassFileVersions() throws Exception {
        var bytes = fixture(); bytes[4] = 0; bytes[5] = 2; bytes[6] = 0; bytes[7] = 73;
        var error = assertThrows(ClassInspector.UnsupportedClassVersion.class, () -> new ClassInspector().inspect(bytes));
        assertEquals(73, error.majorVersion());
        assertEquals(2, error.minorVersion());
        assertTrue(error.getMessage().contains("major 73, minor 2"));
        assertTrue(error.getMessage().contains("Java 29"));
        assertTrue(error.getMessage().contains("supports major versions 45..72"));
        assertTrue(error.getMessage().contains("Java 1.1..28"));
    }
    @Test void inventoriesJava24Through28ClassFiles() throws Exception {
        for (int major = 68; major <= 72; major++) {
            var bytes = fixture(); bytes[6] = 0; bytes[7] = (byte) major;
            var info = new ClassInspector().inspect(bytes);
            assertEquals(major, info.majorVersion());
            assertEquals(major - 44, info.javaVersion());
            assertTrue(info.methods().stream().anyMatch(m -> m.name().equals("add")));
        }
    }
    @Test void acceptsAsmGeneratedJava25Class() {
        var writer = new org.objectweb.asm.ClassWriter(org.objectweb.asm.ClassWriter.COMPUTE_FRAMES);
        writer.visit(org.objectweb.asm.Opcodes.V25, org.objectweb.asm.Opcodes.ACC_PUBLIC, "Modern", null, "java/lang/Object", null);
        var method = writer.visitMethod(org.objectweb.asm.Opcodes.ACC_PUBLIC | org.objectweb.asm.Opcodes.ACC_STATIC, "answer", "()I", null, null);
        method.visitCode(); method.visitIntInsn(org.objectweb.asm.Opcodes.BIPUSH, 42); method.visitInsn(org.objectweb.asm.Opcodes.IRETURN);
        method.visitMaxs(0, 0); method.visitEnd(); writer.visitEnd();
        var info = new ClassInspector().inspect(writer.toByteArray());
        assertEquals(69, info.majorVersion());
        assertEquals(25, info.javaVersion());
        assertEquals("Modern", info.name());
    }
}
