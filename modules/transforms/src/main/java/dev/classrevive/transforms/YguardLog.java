package dev.classrevive.transforms;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.*;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

/**
 * Phase 2DK: reads the {@code <map>} section of a yGuard log ({@code yguardlog.xml}, plain or gzipped) and turns it into
 * the entries {@link ProguardMapping#convert} already knows how to apply safely. yGuard writes one element per renamed item:
 * {@code <package name="a.b" map="X"/>}, {@code <class name="a.b.C$D" map="Y"/>} (the new name of the last segment),
 * {@code <method class="a.b.C" name="int f(java.lang.String, int[])" map="Z"/>} and {@code <field class="a.b.C" name="g" map="W"/>}.
 * Field elements carry no type; the type is read from the obfuscated class, where yGuard gives every field of a class a
 * distinct name. The file is parsed as text with fixed element patterns (no XML parser, no entities beyond the five
 * predefined ones and character references), so it cannot reach files or the network. Names come from the log, so they are
 * only as trustworthy as that file.
 */
public final class YguardLog {
    private static final Pattern ELEMENT = Pattern.compile("<(package|class|method|field)\\s+([^<>]*?)/>");
    private static final Pattern ATTRIBUTE = Pattern.compile("([a-z]+)=\"([^\"]*)\"");
    private static final Pattern METHOD = Pattern.compile("(\\S+) ([^\\s(]+)\\(([^()]*)\\)");
    private static final int MAX_ELEMENTS = 2_000_000;

    private YguardLog() {}

    /** Whether the bytes are a yGuard log (gzip, or XML text mentioning yguard and a map section). */
    public static boolean looksLike(byte[] bytes) {
        if (bytes.length >= 2 && (bytes[0] & 0xFF) == 0x1F && (bytes[1] & 0xFF) == 0x8B) return true;
        String head = new String(bytes, 0, Math.min(bytes.length, 4096), java.nio.charset.StandardCharsets.ISO_8859_1);
        return head.contains("<yguard") || head.trim().startsWith("<?xml") && head.contains("<map>");
    }

    private record Item(String kind, Map<String,String> attributes) {}

    /**
     * Converts the log into {@link ProguardMapping.Parsed} entries (original to obfuscated). {@code obfuscated}: the input's
     * class shapes by obfuscated internal name, used to give fields their types; with null, fields are left out (validation).
     */
    public static ProguardMapping.Parsed parse(byte[] bytes, Map<String,ProguardMapping.ClassShape> obfuscated) {
        String text = text(bytes);
        int start = text.indexOf("<map>"), end = text.lastIndexOf("</map>");
        if (start < 0 || end < start) throw new IllegalArgumentException("yGuard log has no <map> section");
        var items = new ArrayList<Item>();
        var matcher = ELEMENT.matcher(text.substring(start, end));
        while (matcher.find()) {
            if (items.size() >= MAX_ELEMENTS) throw new IllegalArgumentException("Too many yGuard map entries");
            var attributes = new HashMap<String,String>();
            var a = ATTRIBUTE.matcher(matcher.group(2));
            while (a.find()) attributes.put(a.group(1), unescape(a.group(2)));
            items.add(new Item(matcher.group(1), attributes));
        }
        var packages = new HashMap<String,String>(); var classes = new LinkedHashMap<String,String>();
        for (var item : items) {
            String name = item.attributes.get("name"), map = item.attributes.get("map");
            if (name == null || map == null || map.isEmpty() || map.indexOf('/') >= 0 || map.indexOf('.') >= 0) continue;
            if (item.kind.equals("package")) packages.put(name, map);
            if (item.kind.equals("class")) classes.put(name, map);
        }
        // Obfuscated internal name of every class the log names.
        var renamed = new LinkedHashMap<String,String>();
        for (String original : classes.keySet()) renamed.put(original.replace('.', '/'), obfuscatedName(original, packages, classes));
        var inverse = new HashMap<String,String>(); renamed.forEach((from, to) -> inverse.put(to, from));
        var members = new LinkedHashMap<String,List<ProguardMapping.MemberLine>>();
        for (String original : renamed.keySet()) members.put(original, new ArrayList<>());
        for (var item : items) {
            String owner = item.attributes.get("class"), name = item.attributes.get("name"), map = item.attributes.get("map");
            if (owner == null || name == null || map == null) continue;
            String internal = owner.replace('.', '/');
            if (item.kind.equals("method")) {
                var m = METHOD.matcher(name);
                if (!m.matches()) throw new IllegalArgumentException("Invalid yGuard method entry: " + name);
                String parameters = m.group(3).replace(" ", "");
                members.computeIfAbsent(internal, k -> new ArrayList<>()).add(new ProguardMapping.MemberLine(m.group(1), m.group(2), parameters, false, map));
            } else if (item.kind.equals("field") && obfuscated != null) {
                var shape = obfuscated.get(renamed.getOrDefault(internal, internal));
                if (shape == null) continue;
                var types = shape.fields().stream().filter(f -> f.name().equals(map)).map(ProguardMapping.Member::descriptor).distinct().toList();
                if (types.size() != 1) continue;
                members.computeIfAbsent(internal, k -> new ArrayList<>()).add(new ProguardMapping.MemberLine(javaType(types.getFirst(), inverse), name, null, true, map));
            }
        }
        var entries = new ArrayList<ProguardMapping.ClassEntry>();
        members.forEach((original, lines) -> entries.add(new ProguardMapping.ClassEntry(original, renamed.getOrDefault(original, original), lines)));
        if (entries.isEmpty()) throw new IllegalArgumentException("yGuard log maps no class");
        return new ProguardMapping.Parsed(entries);
    }

    /** The obfuscated internal name of a dotted original class name ({@code a.b.C$D}). */
    static String obfuscatedName(String original, Map<String,String> packages, Map<String,String> classes) {
        int dot = original.lastIndexOf('.');
        String pkg = dot < 0 ? "" : original.substring(0, dot), simple = original.substring(dot + 1);
        var out = new StringBuilder();
        if (!pkg.isEmpty()) {
            String prefix = "";
            for (String segment : pkg.split("\\.")) {
                prefix = prefix.isEmpty() ? segment : prefix + "." + segment;
                out.append(packages.getOrDefault(prefix, segment)).append('/');
            }
        }
        var parts = simple.split("\\$", -1);
        String path = pkg.isEmpty() ? parts[0] : pkg + "." + parts[0];
        out.append(classes.getOrDefault(path, parts[0]));
        for (int i = 1; i < parts.length; i++) {
            path = path + "$" + parts[i];
            out.append('$').append(classes.getOrDefault(path, parts[i]));
        }
        return out.toString();
    }

    /** A field descriptor of the obfuscated class as a Java type name in original names ({@code a.b.C[]}). */
    private static String javaType(String descriptor, Map<String,String> inverse) {
        int dimensions = 0;
        while (descriptor.charAt(dimensions) == '[') dimensions++;
        String base = switch (descriptor.charAt(dimensions)) {
            case 'Z' -> "boolean"; case 'B' -> "byte"; case 'C' -> "char"; case 'S' -> "short"; case 'I' -> "int";
            case 'J' -> "long"; case 'F' -> "float"; case 'D' -> "double";
            default -> {
                String internal = descriptor.substring(dimensions + 1, descriptor.length() - 1);
                yield inverse.getOrDefault(internal, internal).replace('/', '.');
            }
        };
        return base + "[]".repeat(dimensions);
    }

    private static String text(byte[] bytes) {
        byte[] plain = bytes;
        if (bytes.length >= 2 && (bytes[0] & 0xFF) == 0x1F && (bytes[1] & 0xFF) == 0x8B) {
            try (var in = new GZIPInputStream(new ByteArrayInputStream(bytes))) {
                plain = in.readNBytes(ProguardMapping.MAX_BYTES + 1);
            } catch (IOException error) { throw new IllegalArgumentException("Unreadable gzipped yGuard log"); }
            if (plain.length > ProguardMapping.MAX_BYTES) throw new IllegalArgumentException("yGuard log exceeds 32 MiB");
        }
        try { return ArchiveMetadata.decodeStrict(plain, "yGuard log"); }
        catch (IOException error) { throw new IllegalArgumentException(error.getMessage()); }
    }

    static String unescape(String value) {
        if (value.indexOf('&') < 0) return value;
        var out = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            int semicolon = c == '&' ? value.indexOf(';', i) : -1;
            if (semicolon < 0) { out.append(c); continue; }
            String entity = value.substring(i + 1, semicolon);
            switch (entity) {
                case "amp" -> out.append('&'); case "lt" -> out.append('<'); case "gt" -> out.append('>');
                case "quot" -> out.append('"'); case "apos" -> out.append('\'');
                default -> {
                    try {
                        int code = entity.startsWith("#x") || entity.startsWith("#X") ? Integer.parseInt(entity.substring(2), 16)
                            : entity.startsWith("#") ? Integer.parseInt(entity.substring(1)) : -1;
                        if (code < 0 || !Character.isValidCodePoint(code)) throw new IllegalArgumentException("Invalid entity in yGuard log");
                        out.appendCodePoint(code);
                    } catch (NumberFormatException error) { throw new IllegalArgumentException("Invalid entity in yGuard log"); }
                }
            }
            i = semicolon;
        }
        return out.toString();
    }
}
