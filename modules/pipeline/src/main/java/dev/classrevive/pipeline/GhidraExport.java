package dev.classrevive.pipeline;

import java.util.*;

/**
 * Phase 2CC: the JSON that {@code ClassReviveNativeExport.java} writes after Ghidra's static analysis, read strictly. A
 * missing or mistyped field fails the whole export; lists are bounded and strings are kept as untrusted data.
 */
public record GhidraExport(String ghidraVersion, String format, String language, String compiler, int pointerSize, String imageBase,
                           boolean analyzed, boolean analysisTimedOut, long functionCount, List<Block> blocks, List<Symbol> imports,
                           List<Export> exports, List<Function> jniFunctions, List<Site> registerNativesSites, List<TableEntry> nativeMethodTables,
                           List<Text> strings, List<Decompiled> decompiled) {
    public record Block(String name, String start, long size, String permissions, boolean initialized, long entropyMillibits) {}
    public record Symbol(String library, String name) {}
    public record Export(String name, String address, boolean function) {}
    public record Function(String name, String address, long size, String signature, List<String> calls, boolean createdByScript) {}
    public record Site(String function, String address, String instruction, long depth) {}
    public record TableEntry(String entry, String name, String descriptor, String functionAddress, String function) {}
    public record Text(String address, String type, long length, String value) {}
    public record Decompiled(String name, String address, boolean completed, boolean truncated, String code, String error) {}

    static final int MAX_ROWS = 20_000;

    public static GhidraExport parse(String json) {
        if (!(Json.decode(json) instanceof Map<?,?> root)) throw new IllegalArgumentException("Ghidra export is not a JSON object");
        if (number(root, "schema_version") != 1) throw new IllegalArgumentException("Unknown Ghidra export schema");
        if (!"ghidra".equals(text(root, "tool"))) throw new IllegalArgumentException("Not a Ghidra export");
        return new GhidraExport(text(root, "ghidra_version"), text(root, "format"), text(root, "language"), text(root, "compiler"),
            (int) number(root, "pointer_size"), text(root, "image_base"), flag(root, "analyzed", false), flag(root, "analysis_timed_out", false),
            number(root, "function_count"),
            rows(root, "blocks", m -> new Block(text(m, "name"), text(m, "start"), number(m, "size"), text(m, "permissions"),
                flag(m, "initialized", false), number(m, "entropy_millibits"))),
            rows(root, "imports", m -> new Symbol(text(m, "library"), text(m, "name"))),
            rows(root, "exports", m -> new Export(text(m, "name"), text(m, "address"), flag(m, "function", false))),
            rows(root, "jni_functions", m -> new Function(text(m, "name"), text(m, "address"), number(m, "size"), text(m, "signature"),
                strings(m, "calls"), flag(m, "created_by_script", false))),
            rows(root, "register_natives_sites", m -> new Site(text(m, "function"), text(m, "address"), text(m, "instruction"), number(m, "depth"))),
            rows(root, "native_method_tables", m -> new TableEntry(text(m, "entry"), text(m, "name"), text(m, "descriptor"),
                text(m, "function_address"), text(m, "function"))),
            rows(root, "strings", m -> new Text(text(m, "address"), text(m, "type"), number(m, "length"), text(m, "value"))),
            rows(root, "decompiled", m -> {
                boolean completed = flag(m, "completed", false);
                return new Decompiled(text(m, "name"), text(m, "address"), completed, completed && flag(m, "truncated", false),
                    completed ? text(m, "c") : "", completed ? "" : text(m, "error"));
            }));
    }

    public boolean exports(String name) { return exports.stream().anyMatch(e -> e.name().equals(name)); }

    private interface Row<T> { T read(Map<?,?> map); }
    private static <T> List<T> rows(Map<?,?> map, String key, Row<T> reader) {
        if (!(map.get(key) instanceof List<?> list)) throw new IllegalArgumentException("Missing list: " + key);
        if (list.size() > MAX_ROWS) throw new IllegalArgumentException("Too many rows: " + key);
        var rows = new ArrayList<T>();
        for (Object item : list) {
            if (!(item instanceof Map<?,?> row)) throw new IllegalArgumentException("Row is not an object: " + key);
            rows.add(reader.read(row));
        }
        return List.copyOf(rows);
    }
    private static List<String> strings(Map<?,?> map, String key) {
        if (!(map.get(key) instanceof List<?> list)) throw new IllegalArgumentException("Missing list: " + key);
        var values = new ArrayList<String>();
        for (Object item : list) {
            if (!(item instanceof String text)) throw new IllegalArgumentException("Not a string in " + key);
            values.add(text);
        }
        return List.copyOf(values);
    }
    private static String text(Map<?,?> map, String key) {
        if (!(map.get(key) instanceof String value)) throw new IllegalArgumentException("Missing string: " + key);
        return value;
    }
    private static long number(Map<?,?> map, String key) {
        if (!(map.get(key) instanceof Long value)) throw new IllegalArgumentException("Missing integer: " + key);
        return value;
    }
    private static boolean flag(Map<?,?> map, String key, boolean fallback) {
        Object value = map.get(key);
        if (value == null && !map.containsKey(key)) return fallback;
        if (!(value instanceof Boolean flag)) throw new IllegalArgumentException("Not a boolean: " + key);
        return flag;
    }
}
