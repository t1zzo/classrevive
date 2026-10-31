package dev.classrevive.bytecode;

import dev.classrevive.model.ClassInfo;
import dev.classrevive.model.MemberInfo;
import java.util.*;
import org.objectweb.asm.*;

public final class ClassInspector {
    public static final int MIN_SUPPORTED_MAJOR_VERSION = 45;
    // Tied to the newest class-file constant of the pinned ASM parser (9.11.x: Java 28).
    public static final int MAX_SUPPORTED_MAJOR_VERSION = Opcodes.V28 & 0xFFFF;

    public ClassInfo inspect(byte[] bytes) {
        if (bytes.length < 10 || bytes[0] != (byte) 0xca || bytes[1] != (byte) 0xfe
                || bytes[2] != (byte) 0xba || bytes[3] != (byte) 0xbe)
            throw new IllegalArgumentException("Invalid class header");
        int major = ((bytes[6] & 255) << 8) | (bytes[7] & 255);
        int minor = ((bytes[4] & 255) << 8) | (bytes[5] & 255);
        if (major < MIN_SUPPORTED_MAJOR_VERSION || major > MAX_SUPPORTED_MAJOR_VERSION)
            throw new UnsupportedClassVersion(major, minor);
        try {
            var reader = new ClassReader(bytes);
            var fields = new ArrayList<MemberInfo>();
            var methods = new ArrayList<MemberInfo>();
            reader.accept(new ClassVisitor(Opcodes.ASM9) {
                @Override public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
                    fields.add(new MemberInfo(name, descriptor, access));
                    return new FieldVisitor(Opcodes.ASM9) {};
                }
                @Override public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                    methods.add(new MemberInfo(name, descriptor, access));
                    return new MethodVisitor(Opcodes.ASM9) {};
                }
            }, 0);
            return new ClassInfo(reader.getClassName(), reader.getSuperName(), major, minor,
                reader.getAccess(), Arrays.asList(reader.getInterfaces()), fields, methods);
        } catch (StackOverflowError excessiveNesting) {
            // ASM traverses nested annotation values recursively. Contain this
            // specific parser failure; never attempt to recover from arbitrary VM errors.
            throw new IllegalArgumentException("Class metadata exceeds parser nesting capacity");
        } catch (RuntimeException error) {
            throw new IllegalArgumentException("Malformed or truncated class file", error);
        }
    }
    public static final class UnsupportedClassVersion extends IllegalArgumentException {
        private final int majorVersion;
        private final int minorVersion;

        public UnsupportedClassVersion(int majorVersion, int minorVersion) {
            super(message(majorVersion, minorVersion));
            this.majorVersion = majorVersion;
            this.minorVersion = minorVersion;
        }

        public int majorVersion() { return majorVersion; }
        public int minorVersion() { return minorVersion; }

        private static String message(int major, int minor) {
            String javaVersion = javaVersion(major);
            String detected = "major " + major + ", minor " + minor
                + (javaVersion == null ? "" : " (Java " + javaVersion + ")");
            return "Unsupported class-file version: " + detected + "; this build supports major versions "
                + MIN_SUPPORTED_MAJOR_VERSION + ".." + MAX_SUPPORTED_MAJOR_VERSION
                + " (Java 1.1.." + javaVersion(MAX_SUPPORTED_MAJOR_VERSION) + ")";
        }

        private static String javaVersion(int major) {
            if (major < MIN_SUPPORTED_MAJOR_VERSION) return null;
            int release = major - 44;
            return release <= 4 ? "1." + release : Integer.toString(release);
        }
    }
}
