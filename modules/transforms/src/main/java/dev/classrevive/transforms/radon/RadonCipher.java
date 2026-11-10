package dev.classrevive.transforms.radon;

import org.objectweb.asm.Type;

/**
 * Phase 2DK: the key schedules of Radon 2's injected decoders, written for ClassRevive from the behaviour documented in
 * docs/radon-yguard.md (Radon is GPL-3.0; none of its code is copied). Every function is a pure computation on names and
 * constants the obfuscated jar itself holds; nothing of the jar is loaded or run.
 */
final class RadonCipher {
    private RadonCipher() {}

    /**
     * String encryption: character {@code i} is XOR-ed with key {@code i % 4}. Without context checking every key is the
     * call's int; with it, key {@code j} is that int XOR a sum of three of the {@code String.hashCode()} of the caller's
     * class (binary name with dots), the caller's method name, the decryptor's class and the decryptor's method name.
     */
    static String string(String cipher, int key, boolean context, String callerClass, String callerMethod,
                         String decryptorClass, String decryptorMethod) {
        int cc = dotted(callerClass).hashCode(), cm = callerMethod.hashCode();
        int dc = dotted(decryptorClass).hashCode(), dm = decryptorMethod.hashCode();
        int[] keys = context
            ? new int[] {(cc + dc + cm) ^ key, (cm + dm + cc) ^ key, (dc + cc + cm) ^ key, (dm + cc + dc) ^ key}
            : new int[] {key, key, key, key};
        var chars = cipher.toCharArray();
        for (int i = 0; i < chars.length; i++) chars[i] = (char) (chars[i] ^ keys[i % 4]);
        return new String(chars);
    }

    /** Number context checking: the boxed value (or its IEEE bits) XOR the {@code hashCode()} of the caller's method name. */
    static Object number(Object encoded, String boxType, String callerMethod) {
        int hash = callerMethod.hashCode();
        return switch (boxType) {
            case "java/lang/Integer" -> encoded instanceof Integer value ? value ^ hash : null;
            case "java/lang/Float" -> encoded instanceof Integer value ? Float.intBitsToFloat(value ^ hash) : null;
            case "java/lang/Long" -> encoded instanceof Long value ? value ^ (long) hash : null;
            case "java/lang/Double" -> encoded instanceof Long value ? Double.longBitsToDouble(value ^ (long) hash) : null;
            default -> null;
        };
    }

    /** "Fast" invokedynamic: the site name XOR-ed with three name hashes, cycling every three characters. */
    static String fastName(String name, String bootstrapClass, String bootstrapMethod, String decryptMethod) {
        int[] keys = {dotted(bootstrapClass).hashCode(), bootstrapMethod.hashCode(), decryptMethod.hashCode()};
        var chars = name.toCharArray();
        for (int i = 0; i < chars.length; i++) chars[i] = (char) (chars[i] ^ keys[i % 3]);
        return new String(chars);
    }

    /** Heavy invokedynamic key: high word {@code name.hashCode()}, low word {@link #descriptorHash} (methods) or {@link #typeHash}. */
    static long key(String name, int low) { return ((long) name.hashCode() << 32) | (low & 0xFFFFFFFFL); }

    /** XOR of the type names of every parameter and of the return type ({@link #typeName}). */
    static int descriptorHash(String descriptor) {
        int hash = 0;
        for (var type : Type.getArgumentTypes(descriptor)) hash ^= typeName(type).hashCode();
        return hash ^ typeName(Type.getReturnType(descriptor)).hashCode();
    }

    static int typeHash(String descriptor) { return typeName(Type.getType(descriptor)).hashCode(); }

    /** {@code Class.getName()} spelling: {@code int}, {@code java.lang.String}, {@code [I}, {@code [Ljava.lang.String;}. */
    static String typeName(Type type) {
        return type.getSort() == Type.ARRAY ? type.getInternalName().replace('/', '.') : type.getClassName();
    }

    /** The descriptor with every object parameter widened to {@code Object} (arrays and primitives kept). */
    static String generic(String descriptor) {
        var arguments = Type.getArgumentTypes(descriptor);
        for (int i = 0; i < arguments.length; i++) if (arguments[i].getSort() == Type.OBJECT) arguments[i] = Type.getType(Object.class);
        return Type.getMethodDescriptor(Type.getReturnType(descriptor), arguments);
    }

    static String dotted(String internal) { return internal.replace('/', '.'); }
}
