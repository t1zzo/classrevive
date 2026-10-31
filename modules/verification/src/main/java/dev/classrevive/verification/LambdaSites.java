package dev.classrevive.verification;

import java.util.*;
import org.objectweb.asm.*;

/**
 * Phase 2BM: the lambda-metafactory call sites of original classes, read with ASM. Kept apart from
 * {@link SourceRepairWorker} so the compiler worker, which shares its helpers but runs without ASM, never loads ASM types.
 */
final class LambdaSites {
    private LambdaSites() {}

    /** One call site: functional interface (internal name), its method, implementation owner and name, instantiated type. */
    record Site(String iface, String method, String implOwner, String implName, String instantiated) {}

    /** The sites in class {@code top} and its nested classes ({@code top$...}), in class-name and code order. */
    static List<Site> read(Map<String,byte[]> originals, String top) {
        var found = new ArrayList<Site>();
        for (var entry : new TreeMap<>(originals).entrySet()) {
            if (!entry.getKey().equals(top) && !entry.getKey().startsWith(top + "$")) continue;
            new ClassReader(entry.getValue()).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override public void visitInvokeDynamicInsn(String method, String site, Handle bootstrap, Object... arguments) {
                            if (bootstrap.getOwner().equals("java/lang/invoke/LambdaMetafactory") && arguments.length >= 3
                                && arguments[1] instanceof Handle implementation && arguments[2] instanceof Type instantiated)
                                found.add(new Site(Type.getReturnType(site).getInternalName(), method, implementation.getOwner(),
                                    implementation.getName(), instantiated.getDescriptor()));
                        }
                    };
                }
            }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        }
        return found;
    }

    /** The parameter types of a method descriptor as source names; {@code null} entries for primitive types. */
    static List<String> parameters(String descriptor) {
        var result = new ArrayList<String>();
        for (var type : Type.getArgumentTypes(descriptor)) result.add(name(type));
        return result;
    }

    /** The result type of a method descriptor as a source name, or {@code null} for a primitive or {@code void}. */
    static String result(String descriptor) { return name(Type.getReturnType(descriptor)); }

    private static String name(Type type) {
        return type.getSort() == Type.OBJECT || type.getSort() == Type.ARRAY ? type.getClassName().replace('$', '.') : null;
    }
}
