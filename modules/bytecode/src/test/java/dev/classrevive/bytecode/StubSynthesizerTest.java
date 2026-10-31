package dev.classrevive.bytecode;

import java.nio.file.*;
import java.util.*;
import java.util.zip.ZipFile;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.*;
import static org.junit.jupiter.api.Assertions.*;

class StubSynthesizerTest {
    @TempDir Path dir;

    static final Map<String,String> LIBRARY = Map.of(
        "lib/Base.java", "package lib; public abstract class Base { protected Base(String name) {} public int size() { return 0; } public abstract void tick();"
            // Phase 2CW: overridden as protected (Activity.onCreate), so the stub must not be public.
            + " protected void onShow(String screen) {} }",
        "lib/Service.java", "package lib; public interface Service { String call(int times); }",
        "lib/Mode.java", "package lib; public enum Mode { FAST, SAFE }",
        "lib/Marker.java", "package lib; public @interface Marker { String name(); Mode mode() default Mode.SAFE; Class<?>[] targets() default {}; }",
        "lib/LibException.java", "package lib; public class LibException extends Exception { public LibException(String message) { super(message); } }",
        "lib/Box.java", "package lib; public class Box<T> { public T value; public static <T> Box<T> of(T value) { return null; } public T get() { return value; } }",
        "lib/Io.java", "package lib; public class Io { public static String read(String path) throws java.io.IOException { return path; } }",
        "lib/Outer.java", "package lib; public class Outer { public static class Inner { public static int seven() { return 7; } } }",
        "lib/Target.java", "package lib; public class Target {}");

    static final String APP = "package app; import lib.*;"
        + " @Marker(name = \"app\", mode = Mode.FAST, targets = {String.class, Target.class})"
        + " public class App extends Base implements Service {"
        + "   public App() { super(\"app\"); }"
        + "   @Override public void tick() { System.out.println(size()); }"
        + "   @Override protected void onShow(String screen) { super.onShow(screen); }"
        + "   public String call(int times) { return String.valueOf(times); }"
        + "   static int code(Mode mode) { switch (mode) { case FAST: return 1; default: return 2; } }"
        + "   static String box() { Box<String> box = Box.of(\"x\"); return box.get() + box.value; }"
        + "   static String io() { try { return Io.read(\"p\"); } catch (java.io.IOException e) { return \"\"; } }"
        + "   static void fail() throws LibException { try { throw new LibException(\"no\"); } catch (LibException e) { throw e; } }"
        + "   static int nested() { return Outer.Inner.seven(); } }";

    @Test void synthesizesStubsTheOriginalSourceCompilesAgainst() throws Exception {
        var library = Files.createDirectories(dir.resolve("library"));
        var sources = new ArrayList<String>();
        for (var file : LIBRARY.entrySet()) {
            var path = library.resolve(file.getKey()); Files.createDirectories(path.getParent()); Files.writeString(path, file.getValue());
            sources.add(path.toString());
        }
        var appSource = Files.createDirectories(dir.resolve("app/app")).resolve("App.java");
        Files.writeString(appSource, APP);
        var classes = dir.resolve("classes");
        // -g, as Maven and Gradle compile by default: local variable signatures are the only trace of Box's type parameter here.
        var args = new ArrayList<>(List.of("--release", "8", "-g", "-Xlint:-options", "-proc:none", "-d", classes.toString()));
        args.addAll(sources); args.add(appSource.toString());
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, args.toArray(String[]::new)));
        // Only the application's classes are known: the library is "missing".
        var originals = new TreeMap<String,byte[]>();
        try (var paths = Files.walk(classes.resolve("app"))) {
            for (var path : paths.filter(p -> p.toString().endsWith(".class")).toList()) {
                String name = classes.relativize(path).toString().replace('\\', '/');
                originals.put(name.substring(0, name.length() - 6), Files.readAllBytes(path));
            }
        }
        var jar = dir.resolve("stubs/stubs.jar");
        var result = StubSynthesizer.synthesize(originals, List.of(), jar);
        assertTrue(result.classes().containsAll(List.of("lib/Base", "lib/Service", "lib/Mode", "lib/Marker", "lib/LibException", "lib/Box",
            "lib/Io", "lib/Outer", "lib/Outer$Inner", "lib/Target")), result.classes().toString());
        // lib/Target appears only as a class value of an annotation, as Mixin targets do.
        assertFalse(result.classes().stream().anyMatch(name -> name.startsWith("java/") || name.startsWith("app/")), result.classes().toString());
        assertEquals(Map.of("lib", result.classes().size()), result.packages());
        // The kinds were inferred from the uses.
        assertTrue(access(jar, "lib/Mode") == null || (access(jar, "lib/Mode") & Opcodes.ACC_ENUM) != 0);
        assertNotEquals(0, access(jar, "lib/Marker") & Opcodes.ACC_ANNOTATION);
        assertNotEquals(0, access(jar, "lib/Service") & Opcodes.ACC_INTERFACE);
        assertEquals("java/lang/RuntimeException", superName(jar, "lib/LibException"));
        // The original application source compiles against the stubs alone.
        var rebuilt = dir.resolve("rebuilt");
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "--release", "8", "-Xlint:-options", "-proc:none",
            "-cp", jar.toString(), "-d", rebuilt.toString(), appSource.toString()));
    }

    @Test void suppliedAndJdkTypesNeedNoStub() throws Exception {
        var writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "app/Uses", null, "java/lang/Object", null);
        var method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "run", "()V", null, null);
        method.visitCode();
        method.visitMethodInsn(Opcodes.INVOKESTATIC, "java/util/Collections", "emptyList", "()Ljava/util/List;", false);
        method.visitInsn(Opcodes.POP);
        method.visitMethodInsn(Opcodes.INVOKESTATIC, "dep/Helper", "go", "()V", false);
        method.visitInsn(Opcodes.RETURN); method.visitMaxs(0, 0); method.visitEnd();
        writer.visitEnd();
        var dependency = dir.resolve("dep.jar");
        try (var zip = new java.util.zip.ZipOutputStream(Files.newOutputStream(dependency))) {
            zip.putNextEntry(new java.util.zip.ZipEntry("dep/Helper.class")); zip.write(new byte[]{0}); zip.closeEntry();
        }
        var result = StubSynthesizer.synthesize(Map.of("app/Uses", writer.toByteArray()), List.of(dependency), dir.resolve("out/stubs.jar"));
        assertTrue(result.isEmpty(), result.classes().toString());
    }

    /** Phase 2DF: a class that calls a static {@code ()I} method on each owner, with no InnerClasses attribute (as obfuscators leave it). */
    private static byte[] caller(String... owners) {
        var writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "app/Calls", null, "java/lang/Object", null);
        var method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "run", "()V", null, null);
        method.visitCode();
        for (String owner : owners) { method.visitMethodInsn(Opcodes.INVOKESTATIC, owner, "seven", "()I", false); method.visitInsn(Opcodes.POP); }
        method.visitInsn(Opcodes.RETURN); method.visitMaxs(0, 0); method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    @Test void nestedStubsGetEveryEnclosingClassUpToTheTop() throws Exception {
        var jar = dir.resolve("deep/stubs.jar");
        var result = StubSynthesizer.synthesize(Map.of("app/Calls", caller("lib/Deep$Mid$Leaf")), List.of(), jar);
        assertEquals(new TreeSet<>(List.of("lib/Deep", "lib/Deep$Mid", "lib/Deep$Mid$Leaf")), result.classes());
        // javac resolves the three-level name against the stubs alone.
        var source = Files.createDirectories(dir.resolve("deep-src/app")).resolve("Use.java");
        Files.writeString(source, "package app; public class Use { static int run() { return lib.Deep.Mid.Leaf.seven(); } }");
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "--release", "8", "-Xlint:-options", "-proc:none",
            "-cp", jar.toString(), "-d", dir.resolve("deep-classes").toString(), source.toString()));
    }

    @Test void truncatedOnlyWhenAMissingTypeWasRefused() throws Exception {
        var exact = StubSynthesizer.synthesize(Map.of("app/Calls", caller("lib/A", "lib/B")), List.of(), dir.resolve("exact/stubs.jar"), 2);
        assertEquals(2, exact.classes().size());
        assertFalse(exact.truncated(), "reaching the limit without refusing anything is not truncation");
        var over = StubSynthesizer.synthesize(Map.of("app/Calls", caller("lib/A", "lib/B", "lib/C")), List.of(), dir.resolve("over/stubs.jar"), 2);
        assertEquals(2, over.classes().size());
        assertTrue(over.truncated());
    }

    private static Integer access(Path jar, String name) throws Exception {
        try (var zip = new ZipFile(jar.toFile())) {
            var entry = zip.getEntry(name + ".class");
            if (entry == null) return null;
            return new ClassReader(zip.getInputStream(entry).readAllBytes()).getAccess();
        }
    }
    private static String superName(Path jar, String name) throws Exception {
        try (var zip = new ZipFile(jar.toFile())) { return new ClassReader(zip.getInputStream(zip.getEntry(name + ".class")).readAllBytes()).getSuperName(); }
    }
}
