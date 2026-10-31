package dev.classrevive.bytecode;

import java.nio.file.*;
import java.util.zip.*;
import java.io.*;
import org.objectweb.asm.*;
import org.objectweb.asm.util.TraceClassVisitor;

/** Disassembles a previously validated class-only JAR; no target class loading. */
public final class BytecodeWorker {
    public static void main(String[] args) throws Exception {
        try (var jar = new ZipFile(args[0])) {
            var entries = jar.entries(); int count = 0;
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                if (!entry.getName().endsWith(".class")) continue;
                if (++count > 20_000) throw new IOException("Entry limit");
                try (var input = jar.getInputStream(entry)) {
                    byte[] bytes = input.readNBytes(16 * 1024 * 1024 + 1);
                    if (bytes.length > 16 * 1024 * 1024) throw new IOException("Class size limit");
                    Path destination = Path.of(args[1]).resolve(String.format("%05d.txt", count));
                    try (var writer = new PrintWriter(Files.newBufferedWriter(destination, StandardOpenOption.CREATE_NEW))) {
                        writer.println("Original entry: " + entry.getName());
                        new ClassReader(bytes).accept(new TraceClassVisitor(writer), 0);
                        if (writer.checkError()) throw new IOException("Cannot write bytecode");
                    }
                }
            }
        }
    }
}
