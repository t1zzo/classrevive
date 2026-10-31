package dev.classrevive.pipeline;

import dev.classrevive.model.Limits;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * Phase 2DB: the jars nested in a zip that {@link InputIdentifier} already validated (a launcher's payload, a distribution
 * zip). Each {@code .jar} entry is read in memory (stored or deflated, nothing written, nothing run) under the intake limits:
 * at most {@link Limits#maxEntries()} entries, {@link Limits#maxInputBytes()} per jar and {@link Limits#maxExpandedBytes()}
 * for all of them. Depth is one: a jar nested in a nested jar is counted, never opened.
 */
final class JavaBundle {
    private JavaBundle() {}

    /** The selection rule, written in reports as it is applied. */
    static final String RULE = "the nested jar with the most classes that is neither under a lib/ or libs/ folder nor a recognized "
        + "library (Phase 2CI catalog, at least 90% of its classes); ties go to the shorter, then alphabetically first name";
    static final int LIBRARY_PERCENT = 90;
    /** A nested jar sharing at least this share of its classes with the main jar is a variant of it (other mappings), not a dependency. */
    static final int VARIANT_PERCENT = 50;
    /** The verifier's budget for dependency JAR bytes (DependencyClasspath). */
    static final long MAX_CLASSPATH_BYTES = 128L * 1024 * 1024;

    /**
     * One nested jar. {@code classes}: class entries at the jar layout; {@code library}: the recognized library it is, or null;
     * {@code problem}: why it could not be read (then {@code bytes} is null and it is never selected). Names may use
     * backslashes (zips written on Windows): folders are matched either way.
     */
    record Jar(String name, long size, long compressed, int classes, int entries, int nestedJars, boolean mainClass, String library,
               String problem, byte[] bytes, Set<String> classNames) {
        boolean readable() { return problem == null; }
        boolean libraryFolder() {
            String lower = name.replace('\\', '/').toLowerCase(Locale.ROOT);
            return lower.startsWith("lib/") || lower.startsWith("libs/") || lower.contains("/lib/") || lower.contains("/libs/");
        }
        Map<String,Object> json() {
            var data = new TreeMap<String,Object>();
            data.put("name", name); data.put("size", size); data.put("compressed_size", compressed); data.put("classes", (long) classes);
            data.put("entries", (long) entries); data.put("nested_jars", (long) nestedJars); data.put("main_class", mainClass);
            data.put("library", library); data.put("library_folder", libraryFolder()); data.put("problem", problem);
            data.put("sha256", bytes == null ? null : InputIdentifier.sha256(bytes));
            return data;
        }
        /** Shares at least {@link #VARIANT_PERCENT}% of the smaller jar's classes with {@code other}: the same code under other mappings. */
        boolean variantOf(Jar other) {
            if (classNames.isEmpty() || other.classNames.isEmpty()) return false;
            long shared = classNames.stream().filter(other.classNames::contains).count();
            return shared * 100 >= (long) VARIANT_PERCENT * Math.min(classNames.size(), other.classNames.size());
        }
    }

    /** {@code jars}: every .jar entry in central-directory order; {@code main}: the default choice, or null without classes. */
    record Contents(InputIdentifier.Archive archive, List<Jar> jars, Jar main, int otherEntries, List<String> others) {
        int classes() { return jars.stream().mapToInt(Jar::classes).sum(); }
        Jar find(String entry) {
            for (Jar jar : jars) if (jar.name().equals(entry)) return jar;
            var byBase = jars.stream().filter(jar -> base(jar.name()).equalsIgnoreCase(base(entry))).toList();
            return byBase.size() == 1 ? byBase.getFirst() : null;
        }
    }

    /** One central-directory record of a validated archive. */
    record Entry(String name, int flags, int method, long compressed, long size, long local) {}

    /** Reads the nested jars of {@code archive} (a zip inside {@code b} that passed {@link InputIdentifier#archives}). */
    static Contents read(byte[] b, InputIdentifier.Archive archive) {
        var limits = Limits.defaults();
        var jars = new ArrayList<Jar>();
        var others = new ArrayList<String>();
        int otherEntries = 0;
        long expanded = 0;
        var entries = entries(b, archive);
        for (var entry : entries) {
            if (!entry.name().toLowerCase(Locale.ROOT).endsWith(".jar") || entry.name().endsWith("/")) {
                if (!entry.name().endsWith("/")) { otherEntries++; if (others.size() < 20) others.add(NativeBinary.clean(entry.name())); }
                continue;
            }
            String problem = null;
            byte[] bytes = null;
            if (jars.size() >= limits.maxEntries()) problem = "over the " + limits.maxEntries() + "-entry limit";
            else if ((entry.flags() & 1) != 0) problem = "encrypted entry";
            else if (entry.method() != 0 && entry.method() != 8) problem = "compression method " + entry.method() + " (only stored and deflated are read)";
            else if (entry.size() > limits.maxInputBytes()) problem = "over the " + limits.maxInputBytes() + "-byte limit";
            else if (expanded + entry.size() > limits.maxExpandedBytes()) problem = "over the " + limits.maxExpandedBytes() + "-byte total limit";
            else {
                bytes = data(b, archive, entry);
                if (bytes == null) problem = "its data does not inflate to the recorded size";
                else expanded += bytes.length;
            }
            jars.add(problem == null ? describe(entry, bytes) : new Jar(entry.name(), entry.size(), entry.compressed(), 0, 0, 0, false, null, problem, null, Set.of()));
        }
        return new Contents(archive, List.copyOf(jars), choose(jars), otherEntries, List.copyOf(others));
    }

    /** The default main jar: {@link #RULE}; with every candidate excluded, the jar with the most classes. Null when none has classes. */
    static Jar choose(List<Jar> jars) {
        Comparator<Jar> order = Comparator.comparingInt(Jar::classes).reversed().thenComparingInt((Jar jar) -> jar.name().length())
            .thenComparing(Jar::name);
        var withClasses = jars.stream().filter(jar -> jar.readable() && jar.classes() > 0).sorted(order).toList();
        return withClasses.stream().filter(jar -> jar.library() == null && !jar.libraryFolder()).findFirst()
            .orElse(withClasses.isEmpty() ? null : withClasses.getFirst());
    }

    private static Jar describe(Entry entry, byte[] bytes) {
        var inner = InputIdentifier.archives(bytes, -1).stream().filter(a -> a.offset() == 0 && a.length() == bytes.length).findFirst().orElse(null);
        if (inner == null) return new Jar(entry.name(), entry.size(), entry.compressed(), 0, 0, 0, false, null, "not a valid zip", bytes, Set.of());
        var classNames = new ArrayList<String>();
        for (var item : entries(bytes, inner))
            if (item.name().endsWith(".class") && !item.name().startsWith("META-INF/")) classNames.add(item.name().substring(0, item.name().length() - 6));
        boolean mainClass = false;
        for (var item : entries(bytes, inner)) {
            if (!item.name().equalsIgnoreCase("META-INF/MANIFEST.MF") || item.size() > 64 * 1024) continue;
            byte[] manifest = data(bytes, inner, item);
            mainClass = manifest != null && new String(manifest, StandardCharsets.UTF_8).lines().anyMatch(line -> line.startsWith("Main-Class:"));
        }
        return new Jar(entry.name(), entry.size(), entry.compressed(), inner.layoutClassEntries(), inner.entries(), inner.nestedJars(), mainClass,
            library(classNames), null, bytes, Set.copyOf(classNames));
    }

    /** The Phase 2CI library holding at least {@link #LIBRARY_PERCENT}% of these classes at its root package, or null. */
    static String library(Collection<String> classNames) {
        if (classNames.isEmpty()) return null;
        var counts = new TreeMap<String,Integer>();
        int matched = 0;
        for (String name : classNames) {
            for (var entry : EmbeddedLibraries.CATALOG) {
                if (name.startsWith(entry[0])) { counts.merge(entry[1], 1, Integer::sum); matched++; break; }
            }
        }
        if (matched * 100L < (long) LIBRARY_PERCENT * classNames.size()) return null;
        String library = null;
        for (var count : counts.entrySet()) if (library == null || count.getValue() > counts.get(library)) library = count.getKey();
        return library;
    }

    /** The central directory of an archive {@link InputIdentifier#archives} validated: every bound below was checked there. */
    static List<Entry> entries(byte[] b, InputIdentifier.Archive archive) {
        int e = (int) (archive.offset() + archive.length()) - 22;
        while (e >= archive.offset() && !(b[e] == 'P' && b[e + 1] == 'K' && b[e + 2] == 5 && b[e + 3] == 6
                && e + 22 + u16(b, e + 20) == archive.offset() + archive.length())) e--;
        if (e < archive.offset()) return List.of();
        int count = u16(b, e + 10);
        long p = e - u32(b, e + 12);
        var entries = new ArrayList<Entry>(count);
        for (int i = 0; i < count; i++) {
            int at = (int) p;
            int nameLength = u16(b, at + 28), extra = u16(b, at + 30), note = u16(b, at + 32);
            entries.add(new Entry(new String(b, at + 46, nameLength, StandardCharsets.UTF_8), u16(b, at + 8), u16(b, at + 10),
                u32(b, at + 20), u32(b, at + 24), archive.offset() + u32(b, at + 42)));
            p += 46L + nameLength + extra + note;
        }
        return entries;
    }

    /** An entry's bytes, stored or inflated, exactly {@code size} long; null when they are not. */
    static byte[] data(byte[] b, InputIdentifier.Archive archive, Entry entry) {
        int local = (int) entry.local();
        long start = local + 30L + u16(b, local + 26) + u16(b, local + 28);
        if (start + entry.compressed() > archive.offset() + archive.length() || entry.size() > Integer.MAX_VALUE - 8) return null;
        if (entry.method() == 0) {
            return entry.compressed() == entry.size() ? Arrays.copyOfRange(b, (int) start, (int) (start + entry.size())) : null;
        }
        var inflater = new Inflater(true);
        try {
            inflater.setInput(b, (int) start, (int) entry.compressed());
            byte[] out = new byte[(int) entry.size()];
            int filled = 0;
            while (filled < out.length && !inflater.finished()) {
                int n = inflater.inflate(out, filled, out.length - filled);
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) break;
                filled += n;
            }
            if (filled != out.length) return null;
            // More output than recorded is a lie about the size: refused, not truncated.
            if (!inflater.finished() && inflater.inflate(new byte[1]) > 0) return null;
            return out;
        } catch (DataFormatException corrupt) {
            return null;
        } finally {
            inflater.end();
        }
    }

    static String base(String name) { int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\')); return slash < 0 ? name : name.substring(slash + 1); }
    private static int u16(byte[] b, int at) { return (b[at] & 0xff) | (b[at + 1] & 0xff) << 8; }
    private static long u32(byte[] b, int at) { return (b[at] & 0xffL) | (b[at + 1] & 0xffL) << 8 | (b[at + 2] & 0xffL) << 16 | (b[at + 3] & 0xffL) << 24; }
}
