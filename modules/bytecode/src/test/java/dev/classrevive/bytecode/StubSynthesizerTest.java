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

    /** Phase 2DJ: a library whose types relate to each other, the way game and framework classes do. */
    static final Map<String,String> HIERARCHY = Map.ofEntries(
        Map.entry("game/Entity.java", "package game; public class Entity { public double x; public int id() { return 0; } }"),
        Map.entry("game/Living.java", "package game; public class Living extends Entity { public float health; }"),
        Map.entry("game/Player.java", "package game; public class Player extends Living { public String name() { return \"\"; } }"),
        Map.entry("game/LocalPlayer.java", "package game; public class LocalPlayer extends Player { public void jump() {} }"),
        Map.entry("game/Info.java", "package game; public class Info { public String name() { return \"\"; } }"),
        Map.entry("game/World.java", "package game; import java.util.*; public class World { public List<Player> players = new ArrayList<>();"
            + " public Collection<Info> infos() { return null; } public static void spawn(Entity e) {} public LocalPlayer self() { return null; } }"),
        Map.entry("game/Command.java", "package game; public interface Command { String name(); }"),
        Map.entry("game/CommandBase.java", "package game; public abstract class CommandBase implements Command {}"),
        Map.entry("game/Registry.java", "package game; public class Registry { public static void register(Command command) {} }"),
        Map.entry("game/Screen.java", "package game; public class Screen { public void handleInput() throws java.io.IOException {} }"),
        Map.entry("game/Mod.java", "package game; public @interface Mod { String id(); @interface EventHandler {} }"),
        Map.entry("game/Loader.java", "package game; public class Loader extends ClassLoader { public static Loader get() { return null; } }"),
        Map.entry("game/Item.java", "package game; public class Item {}"),
        Map.entry("game/Block.java", "package game; public class Block {}"),
        Map.entry("game/Glass.java", "package game; public class Glass extends Block {}"),
        Map.entry("game/BedBlock.java", "package game; public class BedBlock extends Block { public boolean occupied() { return false; } }"),
        Map.entry("game/Filter.java", "package game; public interface Filter<T> { boolean test(T value); }"),
        Map.entry("game/Search.java", "package game; public class Search { public static void find(Filter<Entity> filter) {} }"),
        Map.entry("game/MapItem.java", "package game; public class MapItem extends Item {}"),
        Map.entry("game/Items.java", "package game; public class Items { public static MapItem MAP; }"),
        Map.entry("game/ItemStack.java", "package game; public class ItemStack { public ItemStack(Item item) {} public ItemStack(Entity entity) {} }"),
        Map.entry("game/json/Element.java", "package game.json; public class Element {}"),
        Map.entry("game/json/Array.java", "package game.json; import java.util.*;"
            + " public class Array extends Element implements Iterable<Element> { public Iterator<Element> iterator() { return null; } }"),
        Map.entry("game/json/Obj.java", "package game.json; import java.util.*; public class Obj extends Element {"
            + " public void add(String key, Element value) {} public Set<Map.Entry<String, Element>> entrySet() { return null; } }"));

    static final String HIERARCHY_APP = "package app; import game.*; import game.json.*; import java.util.*;"
        + " @Mod(id = \"app\") public class Uses extends Screen {"
        + "   @Mod.EventHandler public void init() {}"
        + "   @Override public void handleInput() throws java.io.IOException { super.handleInput(); }"
        + "   static class Cmd extends CommandBase { public String name() { return \"cmd\"; } }"
        + "   static Entity pick(Player player) { return player; }"
        + "   static void run(World world) {"
        + "     LocalPlayer me = world.self(); World.spawn(me);"
        + "     Living living = me; Player player = me; living = player; Entity base = living;"
        + "     if (me == pick(player)) me.jump();"
        + "     for (Player each : world.players) System.out.println(each.name());"
        // Phase 2DJ: a direct cast to a subtype next to the loop; the element is still their common supertype.
        + "     LocalPlayer first = (LocalPlayer) world.players.get(0); first.jump();"
        // A loop variable of a supertype casts to that supertype: still List<Player>, not List<Entity>.
        + "     for (Entity each : world.players) System.out.println(each.id());"
        + "     for (Info info : world.infos()) System.out.println(info.name());"
        + "     Registry.register(new Cmd()); Command command = new Cmd(); command.name();"
        + "     ClassLoader loader = Loader.get();"
        + "     new ItemStack(Items.MAP);"
        // Phase 2DJ: an explicit downcast between two library classes, and a lambda for a library interface.
        + "     Filter<Entity> filter = entity -> entity.id() == 1; Search.find(filter);"
        + "   }"
        + "   static boolean bed(Block block) { return ((BedBlock) block).occupied() || block instanceof Glass; }"
        + "   static void json(Array array, Obj object) {"
        + "     object.add(\"list\", array);"
        + "     for (Element element : array) object.add(\"e\", element);"
        + "     for (Map.Entry<String, Element> entry : object.entrySet()) System.out.println(entry.getKey());"
        + "   } }";

    @Test void infersSupertypesElementTypesAndOverrideThrowsFromTheBytecode() throws Exception {
        var library = Files.createDirectories(dir.resolve("hierarchy-library"));
        var sources = new ArrayList<String>();
        for (var file : HIERARCHY.entrySet()) {
            var path = library.resolve(file.getKey()); Files.createDirectories(path.getParent()); Files.writeString(path, file.getValue());
            sources.add(path.toString());
        }
        var appSource = Files.createDirectories(dir.resolve("hierarchy-app/app")).resolve("Uses.java");
        Files.writeString(appSource, HIERARCHY_APP);
        var classes = dir.resolve("hierarchy-classes");
        var args = new ArrayList<>(List.of("--release", "8", "-g", "-Xlint:-options", "-proc:none", "-d", classes.toString()));
        args.addAll(sources); args.add(appSource.toString());
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, args.toArray(String[]::new)));
        var originals = new TreeMap<String,byte[]>();
        try (var paths = Files.walk(classes.resolve("app"))) {
            for (var path : paths.filter(p -> p.toString().endsWith(".class")).toList()) {
                String name = classes.relativize(path).toString().replace('\\', '/');
                originals.put(name.substring(0, name.length() - 6), Files.readAllBytes(path));
            }
        }
        var jar = dir.resolve("hierarchy-stubs/stubs.jar");
        var result = StubSynthesizer.synthesize(originals, List.of(), jar);
        // The chain the bytecode shows, in the library's order.
        assertEquals("game/Player", superName(jar, "game/LocalPlayer"));
        assertEquals("game/Living", superName(jar, "game/Player"));
        assertEquals("game/Entity", superName(jar, "game/Living"));
        assertEquals("game/Item", superName(jar, "game/MapItem"));
        assertEquals("game/Block", superName(jar, "game/BedBlock"));
        assertEquals("game/Block", superName(jar, "game/Glass"));
        assertEquals("game/json/Element", superName(jar, "game/json/Array"));
        // Nothing in the application uses an Obj as an Element: no supertype is invented.
        assertEquals("java/lang/Object", superName(jar, "game/json/Obj"));
        assertEquals("java/lang/ClassLoader", superName(jar, "game/Loader"));
        assertTrue(interfaces(jar, "game/CommandBase").contains("game/Command"));
        assertTrue(interfaces(jar, "game/json/Array").contains("java/lang/Iterable"));
        assertEquals(0, result.guessedOrder());
        // Three links up the player chain, MapItem, BedBlock (from the cast), Glass (instanceof), Array, Loader (to the JDK ClassLoader) and CommandBase implements Command.
        assertEquals(9, result.supertypes());
        assertTrue(fieldSignature(jar, "game/World", "players").equals("Ljava/util/List<Lgame/Player;>;"), fieldSignature(jar, "game/World", "players"));
        // The original source, with its typed loops, nested annotation and override throws, compiles against the stubs alone.
        var compiler = new java.io.StringWriter();
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, new java.io.PrintStream(new java.io.OutputStream() {
                @Override public void write(int b) { compiler.write(b); } }), "--release", "8", "-Xlint:-options", "-proc:none",
            "-cp", jar.toString(), "-d", dir.resolve("hierarchy-rebuilt").toString(), appSource.toString()), compiler.toString());
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
    private static String fieldSignature(Path jar, String owner, String field) throws Exception {
        try (var zip = new ZipFile(jar.toFile())) {
            var node = new org.objectweb.asm.tree.ClassNode();
            new ClassReader(zip.getInputStream(zip.getEntry(owner + ".class")).readAllBytes()).accept(node, 0);
            return node.fields.stream().filter(f -> f.name.equals(field)).findFirst().orElseThrow().signature;
        }
    }
    private static List<String> interfaces(Path jar, String name) throws Exception {
        try (var zip = new ZipFile(jar.toFile())) { return List.of(new ClassReader(zip.getInputStream(zip.getEntry(name + ".class")).readAllBytes()).getInterfaces()); }
    }
    private static String superName(Path jar, String name) throws Exception {
        try (var zip = new ZipFile(jar.toFile())) { return new ClassReader(zip.getInputStream(zip.getEntry(name + ".class")).readAllBytes()).getSuperName(); }
    }
}
