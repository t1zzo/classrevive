package dev.classrevive.intake;

import dev.classrevive.model.*;
import dev.classrevive.bytecode.ClassInspector;
import java.nio.file.*;
import java.io.*;
import java.security.*;
import java.text.Normalizer;
import java.util.*;
import java.util.zip.*;
import static dev.classrevive.model.Diagnostic.Code.*;

public final class InventoryReader {
    public Inventory inspect(Path path, Limits limits) {
        Objects.requireNonNull(path);
        Objects.requireNonNull(limits);
        var entries = new ArrayList<Inventory.Entry>();
        var diagnostics = new ArrayList<Diagnostic>();
        var notices = new ArrayList<Diagnostic>();
        String hash = "";
        long size = 0;
        long[] expanded = {0};
        String input = path.toAbsolutePath().normalize().toString();
        try {
            String name = path.getFileName() == null ? "" : path.getFileName().toString();
            String lower = name.toLowerCase(Locale.ROOT);
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || !(lower.endsWith(".jar") || lower.endsWith(".class"))) {
                diagnostics.add(new Diagnostic(INVALID_INPUT, "", "Expected an existing regular .class or .jar file (not a symbolic link)"));
                return new Inventory(input, hash, size, 0, entries, diagnostics);
            }
            size = Files.size(path);
            if (size > limits.maxInputBytes()) throw new LimitExceeded("Input exceeds byte limit");
            hash = hashFile(path, limits.maxInputBytes());
            if (lower.endsWith(".class")) {
                try (var stream = Files.newInputStream(path)) {
                    byte[] bytes = readEntry(stream, limits, expanded);
                    entries.add(entry(name, bytes, diagnostics));
                }
            } else {
                readJar(path, limits, expanded, entries, diagnostics, notices);
            }
            if (!hash.equals(hashFile(path, limits.maxInputBytes()))) {
                entries.clear();
                notices.clear();
                diagnostics.add(new Diagnostic(IO_ERROR, "", "Input changed during inspection; results discarded"));
            }
        } catch (LimitExceeded error) {
            diagnostics.add(new Diagnostic(RESOURCE_LIMIT, "", error.getMessage()));
        } catch (ZipException error) {
            diagnostics.add(new Diagnostic(INVALID_ARCHIVE, "", "Invalid, truncated or inconsistent ZIP archive"));
        } catch (IOException | SecurityException error) {
            diagnostics.add(new Diagnostic(IO_ERROR, "", "Cannot read input: " + error.getClass().getSimpleName()));
        }
        entries.sort(Comparator.comparing(Inventory.Entry::name));
        return new Inventory(input, hash, size, expanded[0], entries, diagnostics, notices);
    }

    /**
     * Exact duplicates (after NFC) are rejected. Paths that differ only in letter case are legal in a JAR and a common
     * obfuscator trick against case-insensitive file systems (Phase 2BV): class entries are all kept and reported as a
     * {@code CASE_COLLISION} notice; a resource that collides by case with a kept entry is left out with a
     * {@code CASE_COLLISION_SKIPPED} notice (the first in archive order is kept, but a class always wins over a resource).
     * Directory entries that differ only in case are harmless and are not reported. A resource that declares more than the
     * entry byte limit is left out unread with an {@code OVERSIZED_RESOURCE_SKIPPED} notice; an oversized class still fails.
     */
    private void readJar(Path path, Limits limits, long[] expanded, List<Inventory.Entry> entries,
                         List<Diagnostic> diagnostics, List<Diagnostic> notices) throws IOException {
        try (var zip = new ZipFile(path.toFile())) {
            if (zip.size() > limits.maxEntries()) throw new LimitExceeded("Archive exceeds entry count limit");
            var seen = new HashSet<String>();
            var caseClasses = new TreeMap<String, List<String>>();
            var caseOther = new HashMap<String, String>();
            var enumeration = zip.entries();
            while (enumeration.hasMoreElements()) {
                var item = enumeration.nextElement();
                String name = item.getName();
                if (!isSafeEntryName(name)) {
                    diagnostics.add(new Diagnostic(UNSAFE_ENTRY, name, "Unsafe or non-portable archive path"));
                    continue;
                }
                String exact = Normalizer.normalize(name, Normalizer.Form.NFC);
                if (!seen.add(exact)) {
                    diagnostics.add(new Diagnostic(DUPLICATE_ENTRY, name, "Duplicate archive path"));
                    continue;
                }
                if (item.getSize() > limits.maxEntryBytes() && !name.toLowerCase(Locale.ROOT).endsWith(".class")) {
                    // Phase 2BV: an oversized resource (a bundled native library, say) is never read or extracted.
                    notices.add(new Diagnostic(OVERSIZED_RESOURCE_SKIPPED, name, "Left out unread: resource declares "
                        + item.getSize() + " bytes, over the " + limits.maxEntryBytes() + "-byte entry limit"));
                    continue;
                }
                String key = exact.toLowerCase(Locale.ROOT);
                boolean isClass = !item.isDirectory() && name.endsWith(".class");
                var classes = caseClasses.get(key);
                String other = caseOther.get(key);
                if (isClass) {
                    if (other != null) {
                        // A class always wins over a resource that only collides with it by case.
                        caseOther.remove(key);
                        entries.removeIf(kept -> kept.name().equals(other));
                        diagnostics.removeIf(found -> found.entry().equals(other));
                        notices.add(new Diagnostic(CASE_COLLISION_SKIPPED, other, "Left out: path collides by case with class entry " + name));
                    }
                    caseClasses.computeIfAbsent(key, ignored -> new ArrayList<>()).add(name);
                } else if (classes != null || other != null) {
                    if (!(item.isDirectory() && other != null && other.endsWith("/")))
                        notices.add(new Diagnostic(CASE_COLLISION_SKIPPED, name, "Left out: path collides by case with "
                            + (classes != null ? classes.getFirst() : other)));
                    continue;
                } else {
                    caseOther.put(key, name);
                }
                if (item.getSize() > limits.maxEntryBytes()) throw new LimitExceeded("Expanded entry exceeds byte limit: " + name);
                try (var stream = zip.getInputStream(item)) {
                    byte[] bytes = readEntry(stream, limits, expanded);
                    var crc = new CRC32(); crc.update(bytes);
                    if (bytes.length != item.getSize() || crc.getValue() != item.getCrc())
                        throw new ZipException("Entry size or CRC mismatch");
                    if (!item.isDirectory()) entries.add(entry(name, bytes, diagnostics));
                }
            }
            for (var group : caseClasses.values()) {
                if (group.size() < 2) continue;
                var sorted = new ArrayList<>(group); Collections.sort(sorted);
                notices.add(new Diagnostic(CASE_COLLISION, sorted.getFirst(),
                    "Class entries differ only in letter case: " + String.join(", ", sorted)));
            }
        }
    }

    private Inventory.Entry entry(String name, byte[] bytes, List<Diagnostic> diagnostics) {
        ClassInfo info = null;
        if (name.toLowerCase(Locale.ROOT).endsWith(".class")) {
            try {
                info = new ClassInspector().inspect(bytes);
            } catch (ClassInspector.UnsupportedClassVersion error) {
                diagnostics.add(new Diagnostic(UNSUPPORTED_CLASS_VERSION, name, error.getMessage()));
            } catch (IllegalArgumentException error) {
                diagnostics.add(new Diagnostic(INVALID_CLASS, name, error.getMessage()));
            }
        }
        return new Inventory.Entry(name, bytes.length, HexFormat.of().formatHex(digest().digest(bytes)), info);
    }

    private static byte[] readEntry(InputStream input, Limits limits, long[] expanded) throws IOException {
        var output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = input.read(buffer)) != -1) {
            if ((long) output.size() + count > limits.maxEntryBytes()
                    || expanded[0] + count > limits.maxExpandedBytes())
                throw new LimitExceeded("Expanded data exceeds configured byte limit");
            expanded[0] += count;
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }

    public static boolean isSafeEntryName(String name) {
        if (name.isEmpty() || name.length() > 4096 || name.startsWith("/") || name.contains("\\")) return false;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (Character.isISOControl(c) || Character.getType(c) == Character.FORMAT || ":*?\"<>|".indexOf(c) >= 0) return false;
        }
        String clean = name.endsWith("/") ? name.substring(0, name.length() - 1) : name;
        for (String part : clean.split("/", -1)) {
            if (part.isEmpty() || part.equals(".") || part.equals("..") || part.endsWith(".") || part.endsWith(" ")) return false;
            String base = part.split("\\.", 2)[0].toUpperCase(Locale.ROOT);
            if (base.matches("CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9]")) return false;
        }
        return true;
    }

    private static String hashFile(Path path, long limit) throws IOException {
        var digest = digest();
        try (var input = Files.newInputStream(path)) {
            byte[] buffer = new byte[8192]; long total = 0; int count;
            while ((count = input.read(buffer)) != -1) {
                total += count;
                if (total > limit) throw new LimitExceeded("Input exceeds byte limit");
                digest.update(buffer, 0, count);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }

    private static final class LimitExceeded extends IOException {
        LimitExceeded(String message) { super(message); }
    }
}
