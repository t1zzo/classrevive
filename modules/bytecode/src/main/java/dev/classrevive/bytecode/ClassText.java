package dev.classrevive.bytecode;

import java.io.*;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.util.TraceClassVisitor;

/**
 * Phase G3: readable bytecode of one class for the desktop GUI, before or without decompilation. ASM only parses the
 * bytes (nothing is defined, loaded or run). The text is cut at {@code maxChars}; a class ASM cannot parse gives an
 * {@link IOException} with ASM's reason.
 */
public final class ClassText {
    private ClassText() {}

    public static String disassemble(byte[] classBytes, int maxChars) throws IOException {
        var text = new StringWriter();
        try (var writer = new PrintWriter(new BoundedWriter(text, maxChars))) {
            new ClassReader(classBytes).accept(new TraceClassVisitor(writer), 0);
        } catch (RuntimeException malformed) {
            throw new IOException("ASM cannot read this class: " + malformed, malformed);
        }
        return text.toString();
    }

    /** Stops copying at the limit and appends one truncation note; writing past it is ignored. */
    private static final class BoundedWriter extends Writer {
        private final StringWriter out;
        private final int max;
        private boolean truncated;
        BoundedWriter(StringWriter out, int max) { this.out = out; this.max = max; }
        @Override public void write(char[] buffer, int offset, int length) {
            if (truncated) return;
            int room = max - out.getBuffer().length();
            if (length <= room) { out.write(buffer, offset, length); return; }
            out.write(buffer, offset, Math.max(0, room));
            out.write("\n// ... truncated at " + max + " characters\n");
            truncated = true;
        }
        @Override public void flush() {}
        @Override public void close() {}
    }
}
