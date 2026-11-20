package dev.classrevive.jdcore;

import java.io.*;
import java.util.*;

/**
 * Phase 2DP: what the JD-Core worker needs to know about a class file, read from its bytes; the class is never defined
 * or loaded. Two facts:
 * <ul>
 *   <li>the inner classes JD-Core prints inside it. JD-Core 1.1.3 ({@code ClassFileDeserializer.loadClassFile}) takes an
 *   {@code InnerClasses} entry as one of the class's own when the entry's outer class is this class or its name starts
 *   with this class's name and {@code $}. A {@code $} class no entry claims, as in a jar whose obfuscator stripped
 *   {@code InnerClasses}, is printed by nobody unless it gets its own file;</li>
 *   <li>a {@code MethodParameters} entry with no name (index 0, allowed by the class-file format; javac 21 writes one for
 *   each mandated parameter). JD-Core 1.1.3 reads index 0 as a name and fails on the whole class, so such attributes
 *   are left out of the bytes JD-Core reads: it then names those parameters as it does without the attribute.</li>
 * </ul>
 */
final class ClassFileFacts {
    private ClassFileFacts() {}

    /** The class's internal name and the inner classes JD-Core prints inside it. */
    record Facts(String name, List<String> claimed) {
        Facts { claimed = List.copyOf(claimed); }
    }

    static Facts read(byte[] bytes) {
        var in = new Reader(bytes);
        var pool = Pool.read(in);
        in.skip(2);
        String name = pool.className(in.u2());
        in.skip(2);
        in.skip(2 * in.u2());
        skipMembers(in);
        skipMembers(in);
        var claimed = new ArrayList<String>();
        for (int count = in.u2(); count > 0; count--) {
            String attribute = pool.utf8(in.u2());
            int length = in.u4(), end = in.position() + length;
            if ("InnerClasses".equals(attribute)) {
                for (int entries = in.u2(); entries > 0; entries--) {
                    String inner = pool.className(in.u2());
                    int outerIndex = in.u2();
                    String outer = outerIndex == 0 ? null : pool.className(outerIndex);
                    in.skip(4);
                    if (inner != null && !inner.equals(name) && (name.equals(outer) || inner.startsWith(name + "$"))) claimed.add(inner);
                }
            }
            in.seek(end);
        }
        return new Facts(name, claimed);
    }

    /** The bytes without the {@code MethodParameters} attributes that name a parameter with index 0; the same array when there are none. */
    static byte[] withoutNamelessParameters(byte[] bytes) {
        var in = new Reader(bytes);
        var pool = Pool.read(in);
        in.skip(6);
        in.skip(2 * in.u2());
        skipMembers(in);
        int methodsAt = in.position();
        int methods = in.u2();
        var dropped = new ArrayList<int[]>();
        for (int method = 0; method < methods; method++) {
            in.skip(6);
            for (int count = in.u2(); count > 0; count--) {
                int start = in.position();
                String attribute = pool.utf8(in.u2());
                int length = in.u4(), end = in.position() + length;
                if ("MethodParameters".equals(attribute)) {
                    boolean nameless = false;
                    for (int parameters = in.u1(); parameters > 0; parameters--) {
                        if (in.u2() == 0) nameless = true;
                        in.skip(2);
                    }
                    if (nameless) dropped.add(new int[] {start, end});
                }
                in.seek(end);
            }
        }
        if (dropped.isEmpty()) return bytes;
        // Rewrite the method table: each method's attribute count goes down by the attributes left out of it.
        var out = new ByteArrayOutputStream(bytes.length);
        out.write(bytes, 0, methodsAt + 2);
        var copy = new Reader(bytes);
        copy.seek(methodsAt + 2);
        int next = 0;
        for (int method = 0; method < methods; method++) {
            int header = copy.position();
            copy.skip(6);
            int countAt = copy.position(), count = copy.u2();
            var kept = new ByteArrayOutputStream();
            int keptCount = 0;
            for (int attribute = 0; attribute < count; attribute++) {
                int start = copy.position();
                copy.skip(2);
                int length = copy.u4();
                int end = copy.position() + length;
                copy.seek(end);
                if (next < dropped.size() && dropped.get(next)[0] == start) { next++; continue; }
                kept.write(bytes, start, end - start);
                keptCount++;
            }
            out.write(bytes, header, countAt - header);
            out.write(keptCount >>> 8); out.write(keptCount);
            out.writeBytes(kept.toByteArray());
        }
        out.write(bytes, copy.position(), bytes.length - copy.position());
        return out.toByteArray();
    }

    private static void skipMembers(Reader in) {
        for (int members = in.u2(); members > 0; members--) {
            in.skip(6);
            for (int count = in.u2(); count > 0; count--) {
                in.skip(2);
                in.skip(in.u4());
            }
        }
    }

    /** Only the UTF-8 entries and the class entries' name indexes; every other constant is skipped by its size. */
    private record Pool(String[] utf8, int[] classNames) {
        static Pool read(Reader in) {
            if (in.u4() != 0xCAFEBABE) throw new IllegalArgumentException("Not a class file");
            in.skip(4);
            int count = in.u2();
            var utf8 = new String[count]; var classNames = new int[count];
            for (int index = 1; index < count; index++) {
                int tag = in.u1();
                switch (tag) {
                    case 1 -> utf8[index] = in.utf8();
                    case 7 -> classNames[index] = in.u2();
                    case 8, 16, 19, 20 -> in.skip(2);
                    case 15 -> in.skip(3);
                    case 3, 4, 9, 10, 11, 12, 17, 18 -> in.skip(4);
                    case 5, 6 -> { in.skip(8); index++; }
                    default -> throw new IllegalArgumentException("Unknown constant tag " + tag);
                }
            }
            return new Pool(utf8, classNames);
        }
        String utf8(int index) {
            if (index <= 0 || index >= utf8.length || utf8[index] == null) throw new IllegalArgumentException("Bad UTF-8 index " + index);
            return utf8[index];
        }
        String className(int index) {
            if (index <= 0 || index >= classNames.length || classNames[index] == 0) return null;
            return utf8(classNames[index]);
        }
    }

    private static final class Reader {
        private final byte[] bytes;
        private int position;
        Reader(byte[] bytes) { this.bytes = bytes; }
        int position() { return position; }
        void seek(int to) {
            if (to < 0 || to > bytes.length) throw new IllegalArgumentException("Truncated class file");
            position = to;
        }
        void skip(int count) { seek(position + count); }
        int u1() { check(1); return bytes[position++] & 0xff; }
        int u2() { check(2); int value = (bytes[position] & 0xff) << 8 | bytes[position + 1] & 0xff; position += 2; return value; }
        int u4() {
            check(4);
            int value = (bytes[position] & 0xff) << 24 | (bytes[position + 1] & 0xff) << 16 | (bytes[position + 2] & 0xff) << 8 | bytes[position + 3] & 0xff;
            position += 4;
            return value; // a length past 2 GiB is negative here and fails in seek like any length past the end
        }
        /** Modified UTF-8, as {@link DataInputStream#readUTF()} reads it (the two length bytes included). */
        String utf8() {
            check(2);
            int length = (bytes[position] & 0xff) << 8 | bytes[position + 1] & 0xff;
            check(2 + length);
            try {
                String value = new DataInputStream(new ByteArrayInputStream(bytes, position, 2 + length)).readUTF();
                position += 2 + length;
                return value;
            } catch (IOException malformed) {
                throw new IllegalArgumentException("Malformed UTF-8 constant");
            }
        }
        private void check(int count) {
            if (position + count > bytes.length) throw new IllegalArgumentException("Truncated class file");
        }
    }
}
