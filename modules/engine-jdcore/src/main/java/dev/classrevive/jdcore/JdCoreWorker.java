package dev.classrevive.jdcore;

import java.io.IOException;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.ZipFile;

/**
 * Phase 2DO: runs in its own JVM with the user's JD-Core jar on the class path. It reads only the normalized jar supplied
 * by the parent and hands JD-Core the bytes of its classes; no target class is defined, loaded or run. JD-Core is called
 * through reflection (its Loader and Printer interfaces through proxies), so ClassRevive is never compiled against it.
 * One file per class JD-Core does not print inside another, as JD-GUI saves them; which classes those are is read from
 * the {@code InnerClasses} attributes (Phase 2DP, {@link ClassFileFacts}). A class JD-Core fails on is reported on standard
 * error and counted; any failure makes the exit code 1, as the Procyon worker does.
 */
public final class JdCoreWorker {
    static final int MAX_CLASSES = 20_000, MAX_CLASS_BYTES = 16 * 1024 * 1024, MAX_ABANDONED = 3;
    static final long CLASS_SECONDS = 30, STACK_BYTES = 64L * 1024 * 1024;

    private JdCoreWorker() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Expected normalized JAR and output directory");
        Path output = Path.of(args[1]).toAbsolutePath().normalize();
        Class<?> loaderType = Class.forName("org.jd.core.v1.api.loader.Loader");
        Class<?> printerType = Class.forName("org.jd.core.v1.api.printer.Printer");
        Object decompiler = Class.forName("org.jd.core.v1.ClassFileToJavaSourceDecompiler").getConstructor().newInstance();
        Method decompile = decompiler.getClass().getMethod("decompile", loaderType, printerType, String.class);
        int failures = 0;
        var dropped = ConcurrentHashMap.<String>newKeySet();
        try (var jar = new ZipFile(args[0])) {
            var classes = new TreeMap<String,java.util.zip.ZipEntry>();
            for (var entries = jar.entries(); entries.hasMoreElements(); ) {
                var entry = entries.nextElement();
                if (entry.isDirectory() || !entry.getName().endsWith(".class")) continue;
                if (classes.size() >= MAX_CLASSES) throw new IllegalArgumentException("Too many classes");
                classes.put(entry.getName().substring(0, entry.getName().length() - ".class".length()), entry);
            }
            // Phase 2DP: which classes JD-Core prints inside another, read from each class's own InnerClasses attribute.
            var claims = new HashMap<String,List<String>>();
            for (var item : classes.entrySet()) {
                try { claims.put(item.getKey(), ClassFileFacts.read(read(jar, item.getValue(), item.getKey())).claimed()); }
                catch (IllegalArgumentException unreadable) { claims.put(item.getKey(), List.of()); }
            }
            var printedInside = printedInside(classes.keySet(), claims);
            Object loader = Proxy.newProxyInstance(JdCoreWorker.class.getClassLoader(), new Class<?>[] {loaderType}, (proxy, method, arguments) -> switch (method.getName()) {
                case "canLoad" -> classes.containsKey((String) arguments[0]);
                case "load" -> {
                    var entry = classes.get((String) arguments[0]);
                    if (entry == null) yield null;
                    byte[] bytes = read(jar, entry, (String) arguments[0]);
                    try {
                        byte[] readable = ClassFileFacts.withoutNamelessParameters(bytes);
                        if (readable != bytes) dropped.add((String) arguments[0]);
                        yield readable;
                    } catch (IllegalArgumentException unreadable) {
                        yield bytes;
                    }
                }
                default -> defaults(proxy, method, arguments);
            });
            int abandoned = 0;
            for (String name : classes.keySet()) {
                // module-info: JD-Core 1.1.3 fails on a Module attribute with no version, and the other engines write it.
                if (printedInside.contains(name) || name.equals("module-info") || name.endsWith("/module-info")) continue;
                Path destination = output.resolve(name + ".java").normalize();
                if (!destination.startsWith(output)) throw new IllegalArgumentException("Unsafe class path");
                // Each class on its own daemon thread with a deadline: JD-Core can loop forever on one class (seen on an
                // obfuscated jar), and without a deadline that one class costs the whole job's timeout and every class after it.
                var task = new FutureTask<String>(() -> {
                    var printer = new TextPrinter();
                    decompile.invoke(decompiler, loader, Proxy.newProxyInstance(JdCoreWorker.class.getClassLoader(), new Class<?>[] {printerType}, printer), name);
                    return printer.text();
                });
                var thread = new Thread(null, task, "jd-core", STACK_BYTES);
                thread.setDaemon(true);
                thread.start();
                try {
                    String text = task.get(CLASS_SECONDS, TimeUnit.SECONDS);
                    Files.createDirectories(destination.getParent());
                    Files.writeString(destination, escapeLoneSurrogates(text), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
                } catch (TimeoutException late) {
                    failures++;
                    task.cancel(true);
                    System.err.println("Timed out class " + name + " after " + CLASS_SECONDS + " s");
                    if (++abandoned >= MAX_ABANDONED) {
                        System.err.println("Stopped after " + abandoned + " classes timed out");
                        break;
                    }
                } catch (ExecutionException failed) {
                    Throwable cause = failed.getCause();
                    if (cause instanceof InvocationTargetException wrapped && wrapped.getCause() != null) cause = wrapped.getCause();
                    failures++;
                    var frames = Arrays.stream(cause.getStackTrace()).limit(3).map(String::valueOf).toList();
                    System.err.println("Failed class " + name + ": " + cause.getClass().getSimpleName() + (frames.isEmpty() ? "" : " at " + String.join(" < ", frames)));
                } catch (IOException unwritable) {
                    failures++;
                    System.err.println("Failed class " + name + ": " + unwritable.getClass().getSimpleName());
                }
            }
        }
        if (!dropped.isEmpty())
            System.err.println("Note: " + dropped.size() + " class(es) read without MethodParameters entries that name no parameter (JD-Core 1.1.3 fails on them)");
        if (failures > 0) System.exit(1);
    }

    /**
     * JD-Core prints string and char constants as they are, so a constant holding half of a surrogate pair cannot be
     * written as UTF-8. Java reads Unicode escapes before it reads anything else, so the escape keeps the constant's value.
     */
    static String escapeLoneSurrogates(String text) {
        StringBuilder out = null;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            boolean paired = Character.isHighSurrogate(c) ? i + 1 < text.length() && Character.isLowSurrogate(text.charAt(i + 1))
                : !Character.isLowSurrogate(c) || i > 0 && Character.isHighSurrogate(text.charAt(i - 1));
            if (paired && out == null) continue;
            if (out == null) out = new StringBuilder(text.length() + 16).append(text, 0, i);
            if (paired) out.append(c);
            else out.append(String.format("\\u%04X", (int) c));
        }
        return out == null ? text : out.toString();
    }

    private static byte[] read(ZipFile jar, java.util.zip.ZipEntry entry, String name) throws IOException {
        try (var in = jar.getInputStream(entry)) {
            byte[] bytes = in.readNBytes(MAX_CLASS_BYTES + 1);
            if (bytes.length > MAX_CLASS_BYTES) throw new IllegalArgumentException("Class larger than 16 MiB: " + name);
            return bytes;
        }
    }

    /**
     * Phase 2DP: the classes JD-Core prints inside another class's file: those reached from a class nobody claims through
     * the {@code InnerClasses} claims (an inner class's own inner classes come with it). Until 2DP every {@code a/B$C} was
     * skipped when {@code a/B} existed; on a jar whose obfuscator stripped {@code InnerClasses} JD-Core prints such classes
     * on their own, so they were lost. A class claimed only inside a cycle of claims gets its own file.
     */
    static Set<String> printedInside(Set<String> classes, Map<String,List<String>> claims) {
        var claimed = new HashSet<String>();
        claims.values().forEach(list -> list.stream().filter(classes::contains).forEach(claimed::add));
        var inside = new HashSet<String>();
        var pending = new ArrayDeque<String>();
        for (String name : classes) if (!claimed.contains(name)) pending.add(name);
        while (!pending.isEmpty())
            for (String inner : claims.getOrDefault(pending.poll(), List.of()))
                if (classes.contains(inner) && inside.add(inner)) pending.add(inner);
        return inside;
    }

    private static Object defaults(Object proxy, Method method, Object[] arguments) {
        return switch (method.getName()) {
            case "toString" -> "JD-Core proxy";
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == arguments[0];
            default -> null;
        };
    }

    /** JD-Core's Printer as plain text: lines, four-space indentation, every token as JD-Core spells it. */
    static final class TextPrinter implements InvocationHandler {
        private final StringBuilder out = new StringBuilder(8192);
        private int indentation;

        String text() { return out.toString(); }

        @Override public Object invoke(Object proxy, Method method, Object[] arguments) {
            switch (method.getName()) {
                case "printText", "printNumericConstant", "printKeyword" -> out.append((String) arguments[0]);
                case "printStringConstant" -> out.append((String) arguments[0]);
                case "printDeclaration", "printReference" -> out.append((String) arguments[2]);
                case "indent" -> indentation++;
                case "unindent" -> indentation = Math.max(0, indentation - 1);
                case "startLine" -> out.append("    ".repeat(indentation));
                case "endLine" -> out.append('\n');
                case "extraLine" -> out.append("\n".repeat(Math.max(0, (Integer) arguments[0])));
                case "start", "end", "startMarker", "endMarker" -> { }
                default -> { return defaults(proxy, method, arguments); }
            }
            return null;
        }
    }
}
