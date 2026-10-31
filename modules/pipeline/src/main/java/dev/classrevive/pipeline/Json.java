package dev.classrevive.pipeline;

import java.util.*;

/** Small writer for evidence data only; deliberately rejects arbitrary object serialization. */
public final class Json {
    private Json() {}
    public static String encode(Object value) {
        if (value == null) return "null";
        if (value instanceof String text) return quote(text);
        if (value instanceof Boolean || value instanceof Integer || value instanceof Long) return value.toString();
        if (value instanceof Map<?,?> map) {
            var sorted = new TreeMap<String,Object>();
            for (var entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String key)) throw new IllegalArgumentException("JSON keys must be strings");
                sorted.put(key, entry.getValue());
            }
            var fields = new ArrayList<String>();
            sorted.forEach((key, item) -> fields.add(quote(key) + ":" + encode(item)));
            return "{" + String.join(",", fields) + "}";
        }
        if (value instanceof List<?> list) return "[" + String.join(",", list.stream().map(Json::encode).toList()) + "]";
        throw new IllegalArgumentException("Unsupported JSON value type: " + value.getClass().getName());
    }
    /**
     * Strict reader for the evidence files this tool writes (failure cases, regression expectations): objects become
     * sorted maps, arrays lists, integers {@link Long}; fractions, exponents, duplicate keys and trailing data are rejected.
     */
    public static Object decode(String text) {
        if (text.length() > 16 * 1024 * 1024) throw new IllegalArgumentException("JSON input exceeds limit");
        var reader = new Reader(text);
        Object value = reader.value(0);
        reader.space();
        if (reader.index != text.length()) throw reader.error("trailing data");
        return value;
    }
    private static final class Reader {
        private final String text; private int index;
        Reader(String text) { this.text = text; }
        Object value(int depth) {
            if (depth > 64) throw error("nesting too deep");
            space();
            if (index >= text.length()) throw error("unexpected end");
            char c = text.charAt(index);
            switch (c) {
                case '{' -> {
                    index++; var map = new TreeMap<String,Object>(); space();
                    if (peek('}')) { index++; return map; }
                    while (true) {
                        space(); String key = string(); space(); expect(':');
                        if (map.containsKey(key)) throw error("duplicate key");
                        map.put(key, value(depth + 1)); space();
                        if (peek(',')) { index++; continue; }
                        expect('}'); return map;
                    }
                }
                case '[' -> {
                    index++; var list = new ArrayList<Object>(); space();
                    if (peek(']')) { index++; return list; }
                    while (true) {
                        list.add(value(depth + 1)); space();
                        if (peek(',')) { index++; continue; }
                        expect(']'); return list;
                    }
                }
                case '"' -> { return string(); }
                default -> {
                    if (text.startsWith("true", index)) { index += 4; return Boolean.TRUE; }
                    if (text.startsWith("false", index)) { index += 5; return Boolean.FALSE; }
                    if (text.startsWith("null", index)) { index += 4; return null; }
                    int start = index;
                    if (peek('-')) index++;
                    while (index < text.length() && Character.isDigit(text.charAt(index))) index++;
                    if (index == start || text.substring(start, index).equals("-")) throw error("unexpected character");
                    try { return Long.parseLong(text.substring(start, index)); }
                    catch (NumberFormatException overflow) { throw error("number out of range"); }
                }
            }
        }
        String string() {
            expect('"');
            var out = new StringBuilder();
            while (true) {
                if (index >= text.length()) throw error("unterminated string");
                char c = text.charAt(index++);
                if (c == '"') return out.toString();
                if (c < 32) throw error("control character in string");
                if (c != '\\') { out.append(c); continue; }
                if (index >= text.length()) throw error("unterminated escape");
                char e = text.charAt(index++);
                switch (e) {
                    case '"', '\\', '/' -> out.append(e);
                    case 'n' -> out.append('\n');
                    case 'r' -> out.append('\r');
                    case 't' -> out.append('\t');
                    case 'b' -> out.append('\b');
                    case 'f' -> out.append('\f');
                    case 'u' -> {
                        if (index + 4 > text.length()) throw error("short unicode escape");
                        try { out.append((char) Integer.parseInt(text.substring(index, index + 4), 16)); }
                        catch (NumberFormatException bad) { throw error("bad unicode escape"); }
                        index += 4;
                    }
                    default -> throw error("bad escape");
                }
            }
        }
        void space() { while (index < text.length() && " \t\r\n".indexOf(text.charAt(index)) >= 0) index++; }
        boolean peek(char c) { return index < text.length() && text.charAt(index) == c; }
        void expect(char c) { if (!peek(c)) throw error("expected '" + c + "'"); index++; }
        IllegalArgumentException error(String reason) { return new IllegalArgumentException("Invalid JSON at " + index + ": " + reason); }
    }
    private static String quote(String text) {
        var out = new StringBuilder("\"");
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 32 || Character.isSurrogate(c)) out.append(String.format(Locale.ROOT, "\\u%04x", (int)c));
                    else out.append(c);
                }
            }
        }
        return out.append('"').toString();
    }
}
