package dev.classrevive.pipeline;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Phase 2CY test helper: writes Android binary XML (the format aapt compiles AndroidManifest.xml to) from a list of
 * elements, so the decoder is tested on manifests with known content. UTF-16 string pool, a resource map for the android:
 * attributes, one namespace. {@code stripNames} empties the android: attribute names, as R8 and obfuscators do.
 */
final class AxmlFixture {
    private AxmlFixture() {}

    /** {@code value}: a String (type string), Integer (decimal) or Boolean. */
    record Attr(boolean android, String name, int resourceId, Object value) {}
    record Start(String tag, List<Attr> attributes) {}
    record End(String tag) {}

    static Attr android(String name, int id, Object value) { return new Attr(true, name, id, value); }
    static Attr plain(String name, Object value) { return new Attr(false, name, 0, value); }

    static byte[] encode(List<Object> nodes, boolean stripNames) {
        // The android: attribute names come first, in the order of the resource map.
        var androidNames = new LinkedHashMap<String, Integer>();
        for (Object node : nodes)
            if (node instanceof Start start) for (var a : start.attributes()) if (a.android()) androidNames.putIfAbsent(a.name(), a.resourceId());
        var strings = new ArrayList<String>();
        androidNames.keySet().forEach(name -> strings.add(stripNames ? "" : name));
        var index = new HashMap<String, Integer>();
        java.util.function.Function<String, Integer> intern = text -> index.computeIfAbsent(text, key -> { strings.add(key); return strings.size() - 1; });
        int prefix = intern.apply("android"), uri = intern.apply(AndroidPackage.ANDROID);
        var androidIndex = new HashMap<String, Integer>();
        int i = 0;
        for (String name : androidNames.keySet()) androidIndex.put(name, i++);

        var body = new ByteArrayOutputStream();
        body.writeBytes(chunk(0x0100, 16, ints(1, -1, prefix, uri)));
        for (Object node : nodes) {
            if (node instanceof Start start) {
                var ext = ByteBuffer.allocate(20 + 20 * start.attributes().size()).order(ByteOrder.LITTLE_ENDIAN);
                ext.putInt(-1).putInt(intern.apply(start.tag())).putShort((short) 20).putShort((short) 20)
                    .putShort((short) start.attributes().size()).putShort((short) 0).putShort((short) 0).putShort((short) 0);
                for (var a : start.attributes()) {
                    int name = a.android() ? androidIndex.get(a.name()) : intern.apply(a.name());
                    int type, data, raw = -1;
                    if (a.value() instanceof String text) { type = 0x03; data = intern.apply(text); raw = data; }
                    else if (a.value() instanceof Boolean flag) { type = 0x12; data = flag ? -1 : 0; }
                    else { type = 0x10; data = (Integer) a.value(); }
                    ext.putInt(a.android() ? uri : -1).putInt(name).putInt(raw).putShort((short) 8).put((byte) 0).put((byte) type).putInt(data);
                }
                body.writeBytes(chunk(0x0102, 16, concat(ints(1, -1), ext.array())));
            } else if (node instanceof End end) {
                body.writeBytes(chunk(0x0103, 16, ints(1, -1, -1, intern.apply(end.tag()))));
            }
        }
        body.writeBytes(chunk(0x0101, 16, ints(1, -1, prefix, uri)));

        var pool = new ByteArrayOutputStream();
        var offsets = new int[strings.size()];
        for (int s = 0; s < strings.size(); s++) {
            offsets[s] = pool.size();
            byte[] text = strings.get(s).getBytes(StandardCharsets.UTF_16LE);
            pool.writeBytes(shorts(strings.get(s).length()));
            pool.writeBytes(text);
            pool.writeBytes(shorts(0));
        }
        while (pool.size() % 4 != 0) pool.write(0);
        var poolHeader = ByteBuffer.allocate(20 + 4 * strings.size()).order(ByteOrder.LITTLE_ENDIAN);
        poolHeader.putInt(strings.size()).putInt(0).putInt(0).putInt(28 + 4 * strings.size()).putInt(0);
        for (int offset : offsets) poolHeader.putInt(offset);
        byte[] stringPool = chunk(0x0001, 28, concat(poolHeader.array(), pool.toByteArray()));
        var ids = ByteBuffer.allocate(4 * androidNames.size()).order(ByteOrder.LITTLE_ENDIAN);
        androidNames.values().forEach(ids::putInt);
        byte[] resourceMap = chunk(0x0180, 8, ids.array());
        return chunk(0x0003, 8, concat(stringPool, resourceMap, body.toByteArray()));
    }

    /** A chunk: type, header size, total size, then {@code rest} (the header's remainder and the body). */
    private static byte[] chunk(int type, int headerSize, byte[] rest) {
        var b = ByteBuffer.allocate(8 + rest.length).order(ByteOrder.LITTLE_ENDIAN);
        b.putShort((short) type).putShort((short) headerSize).putInt(8 + rest.length).put(rest);
        return b.array();
    }

    private static byte[] ints(int... values) {
        var b = ByteBuffer.allocate(4 * values.length).order(ByteOrder.LITTLE_ENDIAN);
        for (int value : values) b.putInt(value);
        return b.array();
    }

    private static byte[] shorts(int value) { return new byte[] {(byte) value, (byte) (value >> 8)}; }

    private static byte[] concat(byte[]... parts) {
        var out = new ByteArrayOutputStream();
        for (byte[] part : parts) out.writeBytes(part);
        return out.toByteArray();
    }
}
