package dev.classrevive.verification;

import java.io.*;
import java.net.URI;
import java.nio.file.*;
import java.util.*;
import java.util.zip.ZipFile;
import javax.tools.*;

/**
 * Phase 2BL: classes of the running JDK that are outside the Java 8 API, for {@code --release 8} compilations.
 *
 * <p>javac 21's {@code --release 8} exposes only Java 8's public API. A library built for Java 8 against a full JDK may
 * still reference types outside it: {@code sun.misc.Unsafe} (Guava 17's {@code Striped64}, {@code UnsignedBytes}), or
 * {@code java.util.spi.ToolProvider} from Java 9 behind a runtime check (Ant 1.10's {@code Jmod}, {@code Link}). With
 * {@code --release 8} no version of those files can compile. Here the running JDK's modules are appended to the class
 * path, after the dependencies. Release 8 is not modular, and javac looks in the platform API first, so a Java 8 type
 * keeps its Java 8 members (for example {@code ByteBuffer.flip()} still returns {@code Buffer}). Only types missing from
 * the Java 8 API are taken from the running JDK. Nothing is loaded or executed: javac reads the class files.
 * Later releases are modular, and their platform already holds every exported JDK type, so nothing is added.
 */
final class JdkRuntimeClasses {
    private JdkRuntimeClasses() {}

    static boolean applies(String release) { return "8".equals(release); }

    /**
     * Sets the class path to {@code classpath} (or {@code empty} when there is none) and, for release 8, the running JDK's
     * modules after it. The annotation-processor path is set to {@code empty}: javac builds its processor loader from the
     * class path even with {@code -proc:none}, and module paths of the JDK image have no {@code File} form.
     */
    static void configure(StandardJavaFileManager manager, String release, List<Path> classpath, Path empty) throws IOException {
        var paths = new ArrayList<Path>(classpath.isEmpty() ? List.of(empty) : classpath);
        if (applies(release)) paths.addAll(modules());
        manager.setLocationFromPaths(StandardLocation.CLASS_PATH, paths);
        manager.setLocationFromPaths(StandardLocation.ANNOTATION_PROCESSOR_PATH, List.of(empty));
    }

    /** The module roots of the running JDK image, in name order. */
    static List<Path> modules() throws IOException {
        var root = FileSystems.getFileSystem(URI.create("jrt:/")).getPath("/modules");
        try (var stream = Files.list(root)) { return stream.sorted().toList(); }
    }

    /**
     * The JDK types the compiled classes reference that are outside the Java 8 API and outside the dependencies: the ones
     * {@link #configure} made resolvable. Nested types are reported by their top-level class. At most {@code limit} names.
     */
    static SortedSet<String> used(JavaCompiler compiler, Path classes, List<Path> dependencies, Path empty, int limit) throws IOException {
        var referenced = new TreeSet<String>(); var own = new HashSet<String>();
        try (var paths = Files.walk(classes)) {
            for (var path : paths.filter(p -> p.toString().endsWith(".class")).toList()) {
                String name = classes.relativize(path).toString().replace('\\', '/');
                own.add(topLevel(name.substring(0, name.length() - ".class".length())));
                for (String type : ConstantPoolTypes.read(Files.readAllBytes(path))) referenced.add(topLevel(type));
            }
        }
        referenced.removeAll(own);
        var result = new TreeSet<String>();
        if (referenced.isEmpty()) return result;
        var modules = modules();
        try (var manager = compiler.getStandardFileManager(null, Locale.ROOT, null)) {
            manager.setLocationFromPaths(StandardLocation.CLASS_PATH, List.of(empty));
            manager.setLocationFromPaths(StandardLocation.ANNOTATION_PROCESSOR_PATH, List.of(empty));
            // A task with no sources only answers name lookups against the Java 8 API.
            var task = (com.sun.source.util.JavacTask) compiler.getTask(null, manager, diagnostic -> { },
                List.of("--release", "8", "-proc:none", "-Xlint:-options"), null, List.of());
            var elements = task.getElements();
            for (String type : referenced) {
                if (result.size() >= limit) break;
                if (elements.getTypeElement(type.replace('/', '.').replace('$', '.')) != null) continue;
                if (inJars(type, dependencies)) continue;
                for (var module : modules) if (Files.isRegularFile(module.resolve(type + ".class"))) { result.add(type.replace('/', '.')); break; }
            }
        }
        return result;
    }
    private static String topLevel(String internalName) {
        int slash = internalName.lastIndexOf('/'), dollar = internalName.indexOf('$', slash + 1);
        return dollar > slash + 1 ? internalName.substring(0, dollar) : internalName;
    }
    private static boolean inJars(String type, List<Path> jars) throws IOException {
        for (var jar : jars) try (var zip = new ZipFile(jar.toFile())) { if (zip.getEntry(type + ".class") != null) return true; }
        return false;
    }

    /** Class names a class file refers to: its constant pool's classes and every type in its descriptors and signatures. */
    static final class ConstantPoolTypes {
        private ConstantPoolTypes() {}
        static Set<String> read(byte[] bytes) throws IOException {
            var in = new DataInputStream(new ByteArrayInputStream(bytes));
            if (in.readInt() != 0xCAFEBABE) throw new IOException("Not a class file");
            in.readUnsignedShort(); in.readUnsignedShort();
            int count = in.readUnsignedShort();
            var utf8 = new String[count]; var classes = new ArrayList<Integer>(); var descriptors = new ArrayList<Integer>();
            for (int i = 1; i < count; i++) {
                int tag = in.readUnsignedByte();
                switch (tag) {
                    case 1 -> utf8[i] = in.readUTF();
                    case 7 -> classes.add(in.readUnsignedShort());
                    case 16 -> descriptors.add(in.readUnsignedShort());
                    case 12 -> { in.readUnsignedShort(); descriptors.add(in.readUnsignedShort()); }
                    case 8, 19, 20 -> in.readUnsignedShort();
                    case 15 -> { in.readUnsignedByte(); in.readUnsignedShort(); }
                    case 3, 4, 9, 10, 11, 17, 18 -> in.readInt();
                    case 5, 6 -> { in.readLong(); i++; }
                    default -> throw new IOException("Unknown constant pool tag " + tag);
                }
            }
            var result = new TreeSet<String>();
            for (int index : classes) {
                String name = utf8[index];
                if (name == null) continue;
                if (name.startsWith("[")) types(name, result); else result.add(name);
            }
            for (int index : descriptors) if (utf8[index] != null) types(utf8[index], result);
            // Field and method declarations, and Signature attributes, name types that need no constant pool class.
            in.readUnsignedShort(); in.readUnsignedShort(); in.readUnsignedShort();
            int interfaces = in.readUnsignedShort();
            for (int i = 0; i < interfaces; i++) in.readUnsignedShort();
            for (int group = 0; group < 2; group++) {
                int members = in.readUnsignedShort();
                for (int i = 0; i < members; i++) {
                    in.readUnsignedShort(); in.readUnsignedShort();
                    String descriptor = utf8[in.readUnsignedShort()];
                    if (descriptor != null) types(descriptor, result);
                    attributes(in, utf8, result);
                }
            }
            attributes(in, utf8, result);
            return result;
        }
        private static void attributes(DataInputStream in, String[] utf8, Set<String> result) throws IOException {
            int attributes = in.readUnsignedShort();
            for (int i = 0; i < attributes; i++) {
                String name = utf8[in.readUnsignedShort()];
                int length = in.readInt();
                if ("Signature".equals(name) && length == 2) {
                    String signature = utf8[in.readUnsignedShort()];
                    if (signature != null) types(signature, result);
                } else in.skipNBytes(length);
            }
        }
        private static final java.util.regex.Pattern CLASS_TYPE = java.util.regex.Pattern.compile("L([\\p{L}_$][\\p{L}\\p{N}_$]*(?:/[\\p{L}_$][\\p{L}\\p{N}_$]*)*)[;<.]");
        /**
         * Every {@code Lname;} or {@code Lname<} in a descriptor or generic signature. A type variable such as {@code TList;}
         * can yield a fragment ({@code ist}); fragments are harmless, since {@link #used} keeps only names the JDK has.
         */
        private static void types(String descriptor, Set<String> result) {
            var matcher = CLASS_TYPE.matcher(descriptor);
            while (matcher.find()) result.add(matcher.group(1));
        }
    }
}
