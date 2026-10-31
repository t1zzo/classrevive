package dev.classrevive.transforms.skid;

import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.LimitExceededException;
import org.objectweb.asm.MethodTooLargeException;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import java.util.*;

/**
 * Phase 2CN: common super classes for frame computation, from the jar's own class headers and the JDK's (platform loader,
 * loaded without initialization; it cannot see the jar's classes). Unknown types fall back to {@code java/lang/Object}.
 */
final class Hierarchy {
    private record Header(String superName, List<String> interfaces, boolean isInterface) {}
    private final Map<String,ClassNode> classes;
    private final Map<String,Optional<Header>> cache = new HashMap<>();

    Hierarchy(Map<String,ClassNode> classes) { this.classes = classes; }

    /**
     * Phase 2DC: frame computation budget. ASM's default (100 million merge operations per class) is too small for a large
     * method the reversal rebuilt with many locals; ten times it still bounds the work.
     */
    static final long OPERATIONS = 1_000_000_000L;
    static final int MEMORY = 64 << 20;

    ClassWriter writer(int flags) {
        var writer = new ClassWriter(flags) {
            @Override protected String getCommonSuperClass(String first, String second) { return common(first, second); }
            @Override protected ClassLoader getClassLoader() { return ClassLoader.getPlatformClassLoader(); }
        };
        writer.setComputeLimits(MEMORY, OPERATIONS);
        return writer;
    }

    /** Phase 2DC: why a rewritten class could not be written (its original bytes are kept), for the report. */
    static String failure(String internal, RuntimeException error) {
        String why = error instanceof LimitExceededException ? "frame computation over its budget (" + error.getMessage() + ")"
            : error instanceof MethodTooLargeException ? "a method grew past 64 KiB of code"
            : error.getClass().getSimpleName() + (error.getMessage() == null ? "" : ": " + error.getMessage());
        return internal + ": " + why;
    }

    String common(String first, String second) {
        if (assignable(first, second)) return first;
        if (assignable(second, first)) return second;
        var a = header(first); var b = header(second);
        if (a.isEmpty() || b.isEmpty() || a.get().isInterface || b.get().isInterface) return "java/lang/Object";
        for (String type = a.get().superName; type != null; ) {
            if (assignable(type, second)) return type;
            var next = header(type);
            type = next.map(Header::superName).orElse(null);
        }
        return "java/lang/Object";
    }

    private boolean assignable(String target, String type) {
        var pending = new ArrayDeque<String>(List.of(type)); var seen = new HashSet<String>();
        while (!pending.isEmpty()) {
            String current = pending.pop();
            if (current.equals(target)) return true;
            if (!seen.add(current) || seen.size() > 1000) continue;
            var header = header(current);
            if (header.isEmpty()) continue;
            if (header.get().superName != null) pending.push(header.get().superName);
            header.get().interfaces.forEach(pending::push);
        }
        return false;
    }

    private Optional<Header> header(String name) {
        return cache.computeIfAbsent(name, key -> {
            var node = classes.get(key);
            if (node != null) return Optional.of(new Header(node.superName, node.interfaces, (node.access & Opcodes.ACC_INTERFACE) != 0));
            try {
                Class<?> type = Class.forName(key.replace('/', '.'), false, ClassLoader.getPlatformClassLoader());
                var interfaces = new ArrayList<String>();
                for (var item : type.getInterfaces()) interfaces.add(Type.getInternalName(item));
                return Optional.of(new Header(type.getSuperclass() == null ? null : Type.getInternalName(type.getSuperclass()), interfaces, type.isInterface()));
            } catch (ClassNotFoundException | LinkageError unknown) {
                return Optional.empty();
            }
        });
    }
}
