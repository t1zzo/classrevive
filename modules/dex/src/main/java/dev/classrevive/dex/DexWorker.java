package dev.classrevive.dex;

import com.googlecode.d2j.Method;
import com.googlecode.d2j.dex.Dex2jar;
import com.googlecode.d2j.dex.DexExceptionHandler;
import com.googlecode.d2j.node.DexMethodNode;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import java.util.regex.Pattern;
import java.util.zip.*;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Phase 2CW worker, run in its own JVM by {@link DexConversion}: reads the Dalvik bytecode of an APK ({@code classes.dex},
 * {@code classes2.dex}, ... at the archive root) or of a single dex file and writes the class files dex2jar makes of it to
 * {@code converted.jar}, with what happened in {@code conversion.tsv}, and the dex files alone to {@code dalvik.zip}. The dex is only parsed; nothing is loaded or run.
 * A method dex2jar cannot translate keeps its signature and gets a body that throws, and is listed; a class with the same
 * name in a later dex is dropped (the runtime loads the first), and is listed too.
 */
public final class DexWorker {
    private DexWorker() {}

    static final Pattern ROOT_DEX = Pattern.compile("classes(\\d*)\\.dex");
    static final long MAX_DEX_BYTES = 256L * 1024 * 1024, MAX_TOTAL_BYTES = 512L * 1024 * 1024;
    static final int MAX_DEX_FILES = 200, MAX_CLASSES = 200_000, MAX_RECORDS = 10_000;

    public static void main(String[] arguments) throws Exception {
        if (arguments.length != 2) throw new IllegalArgumentException("Expected an APK or dex file and an output directory");
        Path input = Path.of(arguments[0]);
        Path output = Path.of(arguments[1]);
        var log = new ArrayList<String>();
        var classes = new TreeMap<String, byte[]>();
        var origin = new HashMap<String, String>();
        int failedMethods = 0, duplicates = 0;
        var dexes = dexFiles(input);
        // Phase 2CX: the dex files themselves, in load order, for engines that read Dalvik bytecode (JADX).
        try (var zip = new ZipOutputStream(Files.newOutputStream(output.resolve("dalvik.zip"), StandardOpenOption.CREATE_NEW))) {
            int index = 1;
            for (var dex : dexes) {
                var entry = new ZipEntry(index == 1 ? "classes.dex" : "classes" + index + ".dex");
                entry.setTime(0);
                zip.putNextEntry(entry);
                zip.write(dex.bytes());
                zip.closeEntry();
                index++;
            }
        }
        for (var dex : dexes) {
            var failures = new ArrayList<String>();
            var errors = new ArrayList<String>();
            var buffer = new ByteArrayOutputStream();
            try {
                Dex2jar.from(dex.bytes()).skipDebug(false).resetRandom().withExceptionHandler(new Handler(failures, errors)).doTranslate(buffer);
            } catch (Exception | LinkageError error) {
                errors.add(describe(error));
            }
            int converted = 0;
            var data = ByteBuffer.wrap(buffer.toByteArray());
            while (data.remaining() >= 8) {
                byte[] name = new byte[data.getInt()];
                data.get(name);
                byte[] bytes = new byte[data.getInt()];
                data.get(bytes);
                String className = new String(name, StandardCharsets.UTF_8);
                String kept = origin.putIfAbsent(className, dex.name());
                if (kept != null) {
                    if (duplicates++ < MAX_RECORDS) log.add(row("duplicate", className, kept, dex.name()));
                    continue;
                }
                if (classes.size() >= MAX_CLASSES) throw new IOException("More than " + MAX_CLASSES + " classes");
                classes.put(className, bytes);
                converted++;
            }
            log.add(row("dex", dex.name(), Long.toString(dex.bytes().length), Integer.toString(converted)));
            for (String failure : failures) if (failedMethods++ < MAX_RECORDS) log.add(failure);
            for (String error : errors.stream().limit(100).toList()) log.add(row("error", dex.name(), error));
        }
        int[] normalized = new int[4];
        var demoted = DexNormalizer.createdElsewhere(classes);
        var calledAcross = DexNormalizer.calledAcrossNests(classes);
        for (var entry : classes.entrySet()) {
            try {
                entry.setValue(DexNormalizer.normalize(entry.getValue(), normalized, demoted, calledAcross));
            } catch (RuntimeException unreadable) {
                log.add(row("error", entry.getKey(), "not normalized: " + describe(unreadable)));
            }
        }
        log.add(row("normalized", "parameters", Integer.toString(normalized[0])));
        log.add(row("normalized", "synthetic-fields", Integer.toString(normalized[1])));
        log.add(row("normalized", "top-level-classes", Integer.toString(normalized[2])));
        log.add(row("normalized", "synthetic-methods-shown", Integer.toString(normalized[3])));
        if (failedMethods > MAX_RECORDS) log.add(row("truncated", "method-failed", Integer.toString(failedMethods)));
        if (duplicates > MAX_RECORDS) log.add(row("truncated", "duplicate", Integer.toString(duplicates)));
        Files.write(output.resolve("conversion.tsv"), log, StandardCharsets.UTF_8);
        if (classes.isEmpty()) {
            System.err.println("dex2jar produced no class file");
            System.exit(3);
        }
        try (var jar = new JarOutputStream(Files.newOutputStream(output.resolve("converted.jar"), StandardOpenOption.CREATE_NEW))) {
            for (var entry : classes.entrySet()) {
                var zipEntry = new JarEntry(entry.getKey() + ".class");
                zipEntry.setTime(0);
                jar.putNextEntry(zipEntry);
                jar.write(entry.getValue());
                jar.closeEntry();
            }
        }
    }

    record Dex(String name, byte[] bytes) {}

    /** The dex files of the input in load order: a dex file itself, or an archive's root classes.dex, classes2.dex, ... */
    static List<Dex> dexFiles(Path input) throws IOException {
        byte[] head = new byte[4];
        try (var in = Files.newInputStream(input)) { if (in.readNBytes(head, 0, 4) < 4) throw new IOException("Input too small"); }
        if (head[0] == 'd' && head[1] == 'e' && head[2] == 'x' && head[3] == '\n') {
            if (Files.size(input) > MAX_DEX_BYTES) throw new IOException("Dex file over " + MAX_DEX_BYTES + " bytes");
            return List.of(new Dex(String.valueOf(input.getFileName()), Files.readAllBytes(input)));
        }
        var found = new TreeMap<Integer, Dex>();
        long total = 0;
        try (var zip = new ZipFile(input.toFile())) {
            for (var entries = zip.entries(); entries.hasMoreElements(); ) {
                ZipEntry entry = entries.nextElement();
                var matcher = ROOT_DEX.matcher(entry.getName());
                if (entry.isDirectory() || !matcher.matches()) continue;
                // classes.dex is the first; classes2.dex, classes3.dex, ... follow it in number order.
                int index = matcher.group(1).isEmpty() ? 1 : Integer.parseInt(matcher.group(1));
                if (index < 1 || found.size() >= MAX_DEX_FILES) continue;
                var dex = new Dex(entry.getName(), read(zip, entry));
                total += dex.bytes().length;
                if (total > MAX_TOTAL_BYTES) throw new IOException("Dex files over " + MAX_TOTAL_BYTES + " bytes in total");
                found.put(index, dex);
            }
        }
        if (found.isEmpty()) throw new IOException("No classes.dex at the archive root");
        return List.copyOf(found.values());
    }

    private static byte[] read(ZipFile zip, ZipEntry entry) throws IOException {
        if (entry.getSize() > MAX_DEX_BYTES) throw new IOException(entry.getName() + " is over " + MAX_DEX_BYTES + " bytes");
        try (var in = zip.getInputStream(entry)) {
            byte[] bytes = in.readNBytes((int) MAX_DEX_BYTES + 1);
            if (bytes.length > MAX_DEX_BYTES) throw new IOException(entry.getName() + " is over " + MAX_DEX_BYTES + " bytes");
            return bytes;
        }
    }

    /** Lists each method dex2jar could not translate; its body becomes a throw, so the class stays valid and says so. */
    private static final class Handler implements DexExceptionHandler {
        private final List<String> failures, errors;
        Handler(List<String> failures, List<String> errors) { this.failures = failures; this.errors = errors; }

        @Override public void handleFileException(Exception error) { errors.add(describe(error)); }

        @Override public void handleMethodTranslateException(Method method, DexMethodNode node, MethodVisitor visitor, Exception error) {
            String owner = method.getOwner();
            if (owner.startsWith("L") && owner.endsWith(";")) owner = owner.substring(1, owner.length() - 1);
            failures.add(row("method-failed", owner, method.getName() + method.getDesc(), error.getClass().getSimpleName()));
            visitor.visitTypeInsn(Opcodes.NEW, "java/lang/RuntimeException");
            visitor.visitInsn(Opcodes.DUP);
            visitor.visitLdcInsn("ClassRevive: dex2jar could not translate this method from Dalvik bytecode");
            visitor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/RuntimeException", "<init>", "(Ljava/lang/String;)V", false);
            visitor.visitInsn(Opcodes.ATHROW);
        }
    }

    static String describe(Throwable error) {
        return error.getClass().getSimpleName() + (error.getMessage() == null ? "" : ": " + error.getMessage());
    }

    /** One tab-separated line; tabs, line breaks and control characters in a field become spaces, and long fields are cut. */
    static String row(String... fields) {
        var out = new StringJoiner("\t");
        for (String field : fields) {
            String clean = field.replaceAll("\\p{Cntrl}", " ");
            out.add(clean.length() > 300 ? clean.substring(0, 300) + "..." : clean);
        }
        return out.toString();
    }
}
