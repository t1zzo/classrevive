package dev.classrevive.pipeline;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;

/**
 * Phase 2CY: what an APK says about itself, read statically. Its binary {@code AndroidManifest.xml} (Android's compiled XML:
 * a string pool, a resource-id map and element chunks) is decoded to text and summarized: package, versions, SDK levels,
 * permissions, components and the launcher activity. Native libraries ({@code lib/<abi>/*.so}) are listed and triaged with
 * 2CC's header reader: format, JNI exports, {@code JNI_OnLoad}, packing hints. Nothing is run, loaded or written back; every
 * read is bounded, and a malformed chunk ends decoding with a problem noted instead of an exception.
 */
final class AndroidPackage {
    private AndroidPackage() {}

    static final int MAX_MANIFEST_BYTES = 4 * 1024 * 1024, MAX_LIBRARY_BYTES = 32 * 1024 * 1024, MAX_TRIAGED = 32, MAX_LISTED = 1000;
    static final long MAX_LIBRARY_TOTAL = 256L * 1024 * 1024;

    record Manifest(String packageName, String versionCode, String versionName, Integer minSdk, Integer targetSdk,
                    List<String> permissions, SortedMap<String, List<String>> components, String launcher, boolean debuggable,
                    String xml, List<String> problems) {}

    /** {@code format} is null for a library that was listed but not read (over the size or count limits). */
    record NativeLibrary(String entry, String abi, long size, String format, int jniExports, boolean jniOnLoad, List<String> packingHints) {}

    record Contents(Manifest manifest, List<NativeLibrary> libraries) {}

    /** Reads the APK archive's manifest and native libraries. */
    static Contents read(byte[] archive) {
        Manifest manifest = null;
        var libraries = new ArrayList<NativeLibrary>();
        long read = 0;
        try (var zip = new ZipInputStream(new ByteArrayInputStream(archive))) {
            for (ZipEntry entry; (entry = zip.getNextEntry()) != null; ) {
                String name = entry.getName();
                if (name.equals("AndroidManifest.xml") && manifest == null) {
                    byte[] bytes = zip.readNBytes(MAX_MANIFEST_BYTES + 1);
                    manifest = bytes.length > MAX_MANIFEST_BYTES ? empty("manifest over " + MAX_MANIFEST_BYTES + " bytes: not decoded")
                        : decode(bytes);
                } else if (name.startsWith("lib/") && name.endsWith(".so") && name.chars().filter(c -> c == '/').count() == 2
                        && libraries.size() < MAX_LISTED) {
                    String abi = name.substring(4, name.indexOf('/', 4));
                    if (libraries.size() < MAX_TRIAGED && read < MAX_LIBRARY_TOTAL) {
                        byte[] bytes = zip.readNBytes(MAX_LIBRARY_BYTES + 1);
                        if (bytes.length <= MAX_LIBRARY_BYTES) {
                            read += bytes.length;
                            libraries.add(triage(name, abi, bytes));
                            continue;
                        }
                    }
                    long size = entry.getSize() >= 0 ? entry.getSize() : zip.transferTo(OutputStream.nullOutputStream());
                    libraries.add(new NativeLibrary(name, abi, size, null, 0, false, List.of()));
                }
            }
        } catch (IOException | RuntimeException unreadable) {
            if (manifest == null) manifest = empty("archive not fully readable: " + unreadable.getClass().getSimpleName());
        }
        return new Contents(manifest, libraries);
    }

    private static NativeLibrary triage(String name, String abi, byte[] bytes) {
        var headers = NativeBinary.parse(bytes);
        if (!headers.known()) return new NativeLibrary(name, abi, bytes.length, "unknown", 0, false, List.of());
        int jni = (int) headers.exports().stream().filter(symbol -> symbol.startsWith("Java_")).count();
        boolean onLoad = headers.exports().contains("JNI_OnLoad");
        var packing = NativeBinary.packing(headers);
        return new NativeLibrary(name, abi, bytes.length, headers.format() + " " + headers.machine(), jni, onLoad,
            packing.packed() ? packing.hints() : List.of());
    }

    private static Manifest empty(String problem) {
        return new Manifest(null, null, null, null, null, List.of(), new TreeMap<>(), null, false, "", List.of(problem));
    }

    // ---- binary XML ----

    static final int XML = 0x0003, STRING_POOL = 0x0001, RESOURCE_MAP = 0x0180;
    static final int START_NAMESPACE = 0x0100, END_NAMESPACE = 0x0101, START_ELEMENT = 0x0102, END_ELEMENT = 0x0103, CDATA = 0x0104;
    static final String ANDROID = "http://schemas.android.com/apk/res/android";
    /** Attribute resource ids, for manifests whose attribute names were stripped (R8, obfuscators). */
    static final Map<Integer, String> ATTRIBUTES = Map.ofEntries(
        Map.entry(0x01010003, "name"), Map.entry(0x0101021b, "versionCode"), Map.entry(0x0101021c, "versionName"),
        Map.entry(0x0101020c, "minSdkVersion"), Map.entry(0x01010270, "targetSdkVersion"), Map.entry(0x0101000f, "debuggable"),
        Map.entry(0x01010010, "exported"), Map.entry(0x01010001, "label"), Map.entry(0x01010002, "icon"));
    static final int MAX_ELEMENTS = 100_000, MAX_DEPTH = 64;

    /** Decodes compiled XML; whatever was decoded before a malformed chunk is kept, with the problem noted. */
    static Manifest decode(byte[] b) {
        var problems = new ArrayList<String>();
        var xml = new StringBuilder();
        String packageName = null, versionCode = null, versionName = null;
        Integer minSdk = null, targetSdk = null;
        boolean debuggable = false;
        var permissions = new TreeSet<String>();
        var components = new TreeMap<String, List<String>>();
        String launcher = null;
        try {
            if (b.length < 8 || u16(b, 0) != XML) return empty("not Android binary XML (first chunk type " + (b.length < 2 ? "none" : u16(b, 0)) + ")");
            int end = (int) Math.min(b.length, u32(b, 4));
            int at = u16(b, 2);
            List<String> strings = List.of();
            int[] resourceIds = new int[0];
            var path = new ArrayDeque<String>();
            var namespaces = new LinkedHashMap<String, String>();
            String component = null, componentTag = null;
            boolean main = false, launcherCategory = false;
            int elements = 0;
            while (at + 8 <= end) {
                int type = u16(b, at), headerSize = u16(b, at + 2);
                long size = u32(b, at + 4);
                if (size < 8 || at + size > end || headerSize > size) { problems.add("chunk at " + at + " has a bad size"); break; }
                int chunkEnd = (int) (at + size);
                switch (type) {
                    case STRING_POOL -> strings = strings(b, at, chunkEnd);
                    case RESOURCE_MAP -> {
                        resourceIds = new int[(chunkEnd - at - headerSize) / 4];
                        for (int i = 0; i < resourceIds.length; i++) resourceIds[i] = (int) u32(b, at + headerSize + 4 * i);
                    }
                    case START_NAMESPACE -> namespaces.put(string(strings, (int) u32(b, at + 20)), string(strings, (int) u32(b, at + 16)));
                    case START_ELEMENT -> {
                        if (++elements > MAX_ELEMENTS || path.size() >= MAX_DEPTH) { problems.add("element limit reached"); at = end; continue; }
                        int ext = at + headerSize;
                        String tag = string(strings, (int) u32(b, ext + 4));
                        int attrStart = u16(b, ext + 8), attrSize = u16(b, ext + 10), count = u16(b, ext + 12);
                        xml.append("  ".repeat(path.size())).append('<').append(tag);
                        if (path.isEmpty()) namespaces.forEach((uri, prefix) -> xml.append(" xmlns:").append(prefix).append("=\"").append(escape(uri)).append('"'));
                        var attributes = new LinkedHashMap<String, String>();
                        for (int i = 0; i < count; i++) {
                            int a = ext + attrStart + i * attrSize;
                            if (attrSize < 20 || a + 20 > chunkEnd) { problems.add("attribute outside its element"); break; }
                            String ns = string(strings, (int) u32(b, a)), name = string(strings, (int) u32(b, a + 4));
                            int nameIndex = (int) u32(b, a + 4);
                            if ((name == null || name.isEmpty()) && nameIndex >= 0 && nameIndex < resourceIds.length)
                                name = ATTRIBUTES.getOrDefault(resourceIds[nameIndex], String.format("attr_0x%08x", resourceIds[nameIndex]));
                            String value = value(strings, (int) u32(b, a + 8), b[a + 15] & 0xff, (int) u32(b, a + 16));
                            String prefix = ns == null ? null : namespaces.get(ns);
                            xml.append(' ').append(prefix == null ? "" : prefix + ":").append(name).append("=\"").append(escape(value)).append('"');
                            attributes.put(name, value);
                        }
                        xml.append(">\n");
                        String name = attributes.get("name");
                        switch (tag == null ? "" : tag) {
                            case "manifest" -> { packageName = attributes.get("package"); versionCode = attributes.get("versionCode"); versionName = attributes.get("versionName"); }
                            case "uses-sdk" -> { minSdk = integer(attributes.get("minSdkVersion")); targetSdk = integer(attributes.get("targetSdkVersion")); }
                            case "uses-permission", "uses-permission-sdk-23" -> { if (name != null) permissions.add(name); }
                            case "application" -> debuggable = "true".equals(attributes.get("debuggable"));
                            case "activity", "activity-alias", "service", "receiver", "provider" -> {
                                component = qualified(packageName, name);
                                componentTag = tag;
                                if (component != null) components.computeIfAbsent(tag, key -> new ArrayList<>()).add(component);
                            }
                            case "intent-filter" -> { main = false; launcherCategory = false; }
                            case "action" -> { if ("android.intent.action.MAIN".equals(name)) main = true; }
                            case "category" -> { if ("android.intent.category.LAUNCHER".equals(name)) launcherCategory = true; }
                            default -> { }
                        }
                        path.push(tag == null ? "" : tag);
                    }
                    case END_ELEMENT -> {
                        String tag = path.isEmpty() ? null : path.pop();
                        xml.append("  ".repeat(path.size())).append("</").append(tag).append(">\n");
                        // The launcher: an activity (or alias) with an intent filter for MAIN and LAUNCHER.
                        if ("intent-filter".equals(tag) && main && launcherCategory && launcher == null && component != null
                                && ("activity".equals(componentTag) || "activity-alias".equals(componentTag))) launcher = component;
                        if (tag != null && tag.equals(componentTag)) { component = null; componentTag = null; }
                    }
                    case END_NAMESPACE, CDATA -> { }
                    default -> { }
                }
                at = chunkEnd;
            }
        } catch (IndexOutOfBoundsException truncated) {
            problems.add("truncated binary XML");
        }
        return new Manifest(packageName, versionCode, versionName, minSdk, targetSdk, List.copyOf(permissions), components, launcher,
            debuggable, xml.toString(), problems);
    }

    private static String qualified(String packageName, String name) {
        if (name == null) return null;
        return name.startsWith(".") && packageName != null ? packageName + name : name.contains(".") || packageName == null ? name : packageName + "." + name;
    }

    private static Integer integer(String value) {
        try { return value == null ? null : Integer.valueOf(value); } catch (NumberFormatException notANumber) { return null; }
    }

    /** A typed attribute value as text: strings, decimal and hex integers, booleans, references, or the raw type. */
    private static String value(List<String> strings, int raw, int type, int data) {
        return switch (type) {
            case 0x03 -> Objects.requireNonNullElse(string(strings, data), "");
            case 0x10 -> Integer.toString(data);
            case 0x11 -> "0x" + Integer.toHexString(data);
            case 0x12 -> data != 0 ? "true" : "false";
            case 0x01 -> String.format("@0x%08x", data);
            case 0x02 -> String.format("?0x%08x", data);
            default -> raw >= 0 && raw < strings.size() ? strings.get(raw) : String.format("(type 0x%02x) 0x%08x", type, data);
        };
    }

    private static List<String> strings(byte[] b, int at, int end) {
        int count = (int) u32(b, at + 8);
        boolean utf8 = (u32(b, at + 16) & 0x100) != 0;
        int start = at + (int) u32(b, at + 20), offsets = at + u16(b, at + 2);
        if (count < 0 || count > 1_000_000 || offsets + 4L * count > end) throw new IndexOutOfBoundsException("string pool");
        var out = new ArrayList<String>(count);
        for (int i = 0; i < count; i++) {
            int p = start + (int) u32(b, offsets + 4 * i);
            if (p < at || p >= end) { out.add(""); continue; }
            if (utf8) {
                int skip = (b[p] & 0x80) != 0 ? 2 : 1;
                p += skip;
                int length = b[p] & 0xff;
                if ((length & 0x80) != 0) { length = (length & 0x7f) << 8 | b[p + 1] & 0xff; p += 2; } else p += 1;
                if (p + length > end) throw new IndexOutOfBoundsException("string");
                out.add(new String(b, p, length, StandardCharsets.UTF_8));
            } else {
                int length = u16(b, p);
                if ((length & 0x8000) != 0) { length = (length & 0x7fff) << 16 | u16(b, p + 2); p += 4; } else p += 2;
                if (p + 2L * length > end) throw new IndexOutOfBoundsException("string");
                out.add(new String(b, p, 2 * length, StandardCharsets.UTF_16LE));
            }
        }
        return out;
    }

    private static String string(List<String> strings, int index) { return index >= 0 && index < strings.size() ? strings.get(index) : null; }

    private static String escape(String text) {
        return NativeBinary.clean(text == null ? "" : text).replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static int u16(byte[] b, int at) { return (b[at] & 0xff) | (b[at + 1] & 0xff) << 8; }
    private static long u32(byte[] b, int at) { return (b[at] & 0xffL) | (b[at + 1] & 0xffL) << 8 | (b[at + 2] & 0xffL) << 16 | (b[at + 3] & 0xffL) << 24; }
}
