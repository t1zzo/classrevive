package dev.classrevive.bytecode;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.ZipFile;

/**
 * Defines the classes of a case JAR in an isolated class loader and links each one, which runs the JVM bytecode
 * verifier. Nothing is initialized or invoked: {@code Class.forName(name, false, loader)} only loads, and
 * {@code getDeclaredMethods()} links (verifies) and reflects; no static initializer, constructor or method of the target
 * runs. The loader's parent is the platform loader, so the classes see the JDK, the case and its dependency JARs, never
 * this worker or ClassRevive.
 *
 * <p>Arguments: classes JAR, output folder, then dependency JARs (loaded on demand, not linked). Writes
 * {@code link.properties} into the output folder.
 */
public final class LinkWorker {
    static final int MAX_CLASSES = 20_000, MAX_MESSAGE = 300;
    static final long MAX_ENTRY = 16L * 1024 * 1024, MAX_TOTAL = 512L * 1024 * 1024;

    public static void main(String[] args) throws Exception {
        var classes = new TreeMap<String,byte[]>(); var dependencies = new HashMap<String,byte[]>();
        long[] total = {0};
        read(Path.of(args[0]), classes, total);
        for (int i = 2; i < args.length; i++) read(Path.of(args[i]), dependencies, total);
        var loader = new IsolatedLoader(classes, dependencies);
        int linked = 0; String failedClass = null; Throwable failure = null;
        boolean verifier = verifierActive();
        if (!verifier) { failedClass = ""; failure = new IllegalStateException("bytecode verification is disabled in this JVM"); }
        for (String name : verifier ? classes.keySet() : Set.<String>of()) {
            if (name.endsWith("module-info")) continue;
            try {
                Class<?> type = Class.forName(name.replace('/', '.'), false, loader);
                type.getDeclaredMethods();
                linked++;
            } catch (LinkageError | ClassNotFoundException | SecurityException | IllegalArgumentException error) {
                failedClass = name; failure = error; break;
            }
        }
        var result = new Properties();
        result.setProperty("linked", Boolean.toString(failure == null));
        result.setProperty("verifier-active", Boolean.toString(verifier));
        result.setProperty("classes", Integer.toString(classes.size()));
        result.setProperty("linked-classes", Integer.toString(linked));
        if (failure != null) {
            result.setProperty("failed-class", failedClass);
            result.setProperty("error-type", failure.getClass().getName());
            String message = String.valueOf(failure.getMessage()).replace('\r', ' ').replace('\n', ' ');
            result.setProperty("message", message.length() <= MAX_MESSAGE ? message : message.substring(0, MAX_MESSAGE) + "...");
        }
        try (var stream = Files.newOutputStream(Path.of(args[1]).resolve("link.properties"), StandardOpenOption.CREATE_NEW)) {
            result.store(stream, "JVM link check; nothing initialized or executed");
        }
    }

    /**
     * Self-test: a hand-written class whose static method returns {@code null} as an {@code int} must be rejected when
     * linked. If it links, verification is off in this JVM and no result of this worker would mean anything.
     */
    static boolean verifierActive() throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(bytes)) {
            out.writeInt(0xCAFEBABE); out.writeShort(0); out.writeShort(52);
            out.writeShort(8);
            out.writeByte(1); out.writeUTF("ClassReviveVerifierProbe");
            out.writeByte(7); out.writeShort(1);
            out.writeByte(1); out.writeUTF("java/lang/Object");
            out.writeByte(7); out.writeShort(3);
            out.writeByte(1); out.writeUTF("m");
            out.writeByte(1); out.writeUTF("()I");
            out.writeByte(1); out.writeUTF("Code");
            out.writeShort(0x21); out.writeShort(2); out.writeShort(4); out.writeShort(0); out.writeShort(0);
            out.writeShort(1);
            out.writeShort(0x09); out.writeShort(5); out.writeShort(6); out.writeShort(1);
            out.writeShort(7); out.writeInt(14); out.writeShort(1); out.writeShort(0);
            out.writeInt(2); out.writeByte(0x01); out.writeByte(0xAC); // aconst_null; ireturn
            out.writeShort(0); out.writeShort(0);
            out.writeShort(0);
        }
        byte[] probe = bytes.toByteArray();
        var loader = new ClassLoader("classrevive-verifier-probe", ClassLoader.getPlatformClassLoader()) {
            Class<?> define() { return defineClass("ClassReviveVerifierProbe", probe, 0, probe.length); }
        };
        try { loader.define().getDeclaredMethods(); return false; }
        catch (VerifyError expected) { return true; }
    }

    private static void read(Path jar, Map<String,byte[]> into, long[] total) throws IOException {
        try (var zip = new ZipFile(jar.toFile())) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                if (entry.isDirectory() || !entry.getName().endsWith(".class")) continue;
                if (into.size() >= MAX_CLASSES) throw new IOException("Too many classes");
                try (var input = zip.getInputStream(entry)) {
                    byte[] bytes = input.readNBytes((int) MAX_ENTRY + 1);
                    total[0] += bytes.length;
                    if (bytes.length > MAX_ENTRY || total[0] > MAX_TOTAL) throw new IOException("Class byte budget exceeded");
                    into.putIfAbsent(entry.getName().substring(0, entry.getName().length() - ".class".length()), bytes);
                }
            }
        }
    }

    /** Case classes first, then dependency classes; everything else comes from the platform loader (the JDK). */
    private static final class IsolatedLoader extends ClassLoader {
        private final Map<String,byte[]> classes, dependencies;
        IsolatedLoader(Map<String,byte[]> classes, Map<String,byte[]> dependencies) {
            super("classrevive-link-check", ClassLoader.getPlatformClassLoader());
            this.classes = classes; this.dependencies = dependencies;
        }
        @Override protected Class<?> findClass(String name) throws ClassNotFoundException {
            String internal = name.replace('.', '/');
            byte[] bytes = classes.containsKey(internal) ? classes.get(internal) : dependencies.get(internal);
            if (bytes == null) throw new ClassNotFoundException(name);
            return defineClass(name, bytes, 0, bytes.length);
        }
    }
}
