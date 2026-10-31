package dev.classrevive.transforms.skid;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Phase 2CO: decrypts Skidfuscator's strings statically, once the flow pass has turned every seed into a literal.
 * Skidfuscator gives each class one of three decryptors (all keyed by {@code Integer.toString(blockSeed).getBytes()}):
 * <ul>
 *   <li>{@code dec(byte[] data, byte[] keys, int seed)}: {@code data[i] ^= seedBytes[i % n]; data[i] ^= keys[i % m]}, UTF-16;</li>
 *   <li>{@code dec(byte[] data, int seed)} with the key array in a static {@code byte[]} field set by {@code <clinit>};</li>
 *   <li>{@code dec(byte[] index, int seed)} reading a static {@code String} buffer built in {@code <clinit>} by
 *   {@code ByteBuffer.wrap(bytes).asCharBuffer().toString()}; the 8-byte index holds the length and offset (big-endian),
 *   the slice is UTF-16BE.</li>
 * </ul>
 * Byte arrays come from generated {@code private static byte[] x()} methods returning an array literal. The decryptors
 * are recognized by their shape and the algorithms are implemented here; the target's decryptor is never run. Each call
 * {@code gen(); [gen();] <literal>; dec(...)} becomes {@code ldc "<plain text>"}. Then the decryptors, generators, key
 * fields and their {@code <clinit>} code are removed once unused, and so is Skidfuscator's {@code nothing_to_see_here}
 * field (ASCII art written in {@code <clinit>}).
 */
final class StringRecovery implements Opcodes {
    enum Kind { KEY_ARGUMENT, KEY_FIELD, BUFFER }
    record Decryptor(String owner, MethodNode method, Kind kind, String field) {}
    int decrypted, failed, decryptorsRemoved, generatorsRemoved, fieldsRemoved, ahegaoRemoved;

    void apply(Map<String,ClassNode> nodes, Set<String> changed) {
        for (var node : nodes.values()) {
            var decryptors = new HashMap<String,Decryptor>();
            for (var method : node.methods) {
                var decryptor = recognize(node, method);
                if (decryptor != null) decryptors.put(method.name + method.desc, decryptor);
            }
            if (decryptors.isEmpty()) continue;
            var cache = new HashMap<String,byte[]>();
            for (var method : node.methods) {
                for (var insn : method.instructions.toArray()) {
                    if (!(insn instanceof MethodInsnNode call) || call.getOpcode() != INVOKESTATIC || !call.owner.equals(node.name)) continue;
                    var decryptor = decryptors.get(call.name + call.desc);
                    if (decryptor == null) continue;
                    if (decrypt(node, method, call, decryptor, cache)) { decrypted++; changed.add(node.name); } else failed++;
                }
            }
        }
        // Remove what no instruction uses any more: decryptors, array generators, key fields with their <clinit> code.
        for (var node : nodes.values()) {
            boolean removedSomething = true;
            while (removedSomething) {
                removedSomething = false;
                var used = usedMethods(node);
                for (var it = node.methods.iterator(); it.hasNext(); ) {
                    var method = it.next();
                    if (used.contains(method.name + method.desc)) continue;
                    if (recognize(node, method) != null) { it.remove(); decryptorsRemoved++; removedSomething = true; changed.add(node.name); }
                    else if (generator(node, method) != null && (method.access & ACC_PRIVATE) != 0) { it.remove(); generatorsRemoved++; removedSomething = true; changed.add(node.name); }
                }
                for (var it = node.fields.iterator(); it.hasNext(); ) {
                    var field = it.next();
                    if ((field.access & ACC_STATIC) == 0 || !(field.desc.equals("[B") || field.desc.equals("Ljava/lang/String;"))) continue;
                    if (readOutsideClinit(node, field) || readInClinit(node, field)) continue;
                    if (removeKeyInit(node, field)) { it.remove(); fieldsRemoved++; removedSomething = true; changed.add(node.name); }
                }
            }
            if (removeAhegao(node)) { ahegaoRemoved++; changed.add(node.name); }
        }
    }

    // ---------------------------------------------------------------- recognition

    /** A Skidfuscator decryptor of this class, or null. */
    static Decryptor recognize(ClassNode node, MethodNode method) {
        if ((method.access & ACC_STATIC) == 0) return null;
        boolean keyArgument = method.desc.equals("([B[BI)Ljava/lang/String;");
        if (!keyArgument && !method.desc.equals("([BI)Ljava/lang/String;")) return null;
        boolean toString = false, getBytes = false, utf16 = false, utf16be = false, substring = false;
        String bytesField = null, bufferField = null;
        for (var insn : method.instructions) {
            if (insn instanceof MethodInsnNode call) {
                if (call.owner.equals("java/lang/Integer") && call.name.equals("toString") && call.desc.equals("(I)Ljava/lang/String;")) toString = true;
                if (call.owner.equals("java/lang/String") && call.name.equals("getBytes")) getBytes = true;
                if (call.owner.equals("java/lang/String") && call.name.equals("substring")) substring = true;
            } else if (insn instanceof FieldInsnNode field && insn.getOpcode() == GETSTATIC) {
                if (field.owner.equals("java/nio/charset/StandardCharsets") && field.name.equals("UTF_16")) utf16 = true;
                if (field.owner.equals("java/nio/charset/StandardCharsets") && field.name.equals("UTF_16BE")) utf16be = true;
                if (field.owner.equals(node.name) && field.desc.equals("[B")) bytesField = field.name;
                if (field.owner.equals(node.name) && field.desc.equals("Ljava/lang/String;")) bufferField = field.name;
            }
        }
        if (!toString || !getBytes) return null;
        if (keyArgument && utf16 && bytesField == null) return new Decryptor(node.name, method, Kind.KEY_ARGUMENT, null);
        if (!keyArgument && utf16 && bytesField != null) return new Decryptor(node.name, method, Kind.KEY_FIELD, bytesField);
        if (!keyArgument && utf16be && substring && bufferField != null) return new Decryptor(node.name, method, Kind.BUFFER, bufferField);
        return null;
    }

    /** The literal bytes a {@code static byte[] x()} generator returns, or null. */
    static byte[] generator(ClassNode node, MethodNode method) {
        if ((method.access & ACC_STATIC) == 0 || !method.desc.equals("()[B")) return null;
        var insns = new ArrayList<AbstractInsnNode>();
        for (var insn : method.instructions) if (insn.getOpcode() >= 0) insns.add(insn);
        if (insns.size() < 3) return null;
        Integer length = SkidModel.literal(insns.get(0));
        if (length == null || length < 0 || length > 1 << 20 || !(insns.get(1) instanceof IntInsnNode array) || array.getOpcode() != NEWARRAY
            || array.operand != T_BYTE || insns.getLast().getOpcode() != ARETURN) return null;
        byte[] bytes = new byte[length];
        int at = 2;
        while (at + 4 <= insns.size() - 1) {
            if (insns.get(at).getOpcode() != DUP || insns.get(at + 3).getOpcode() != BASTORE) return null;
            Integer index = SkidModel.literal(insns.get(at + 1)), value = SkidModel.literal(insns.get(at + 2));
            if (index == null || value == null || index < 0 || index >= length) return null;
            bytes[index] = (byte) (int) value;
            at += 4;
        }
        return at == insns.size() - 1 ? bytes : null;
    }

    private static byte[] generated(ClassNode node, MethodInsnNode call, Map<String,byte[]> cache) {
        if (call.getOpcode() != INVOKESTATIC || !call.owner.equals(node.name) || !call.desc.equals("()[B")) return null;
        String key = call.name;
        if (cache.containsKey(key)) return cache.get(key);
        byte[] bytes = null;
        for (var method : node.methods) if (method.name.equals(call.name) && method.desc.equals("()[B")) bytes = generator(node, method);
        cache.put(key, bytes);
        return bytes;
    }

    // ---------------------------------------------------------------- decryption

    private boolean decrypt(ClassNode node, MethodNode method, MethodInsnNode call, Decryptor decryptor, Map<String,byte[]> cache) {
        var seed = previous(call);
        Integer key = SkidModel.literal(seed);
        if (key == null) return false;
        var data = previous(seed);
        AbstractInsnNode keys = null;
        if (decryptor.kind == Kind.KEY_ARGUMENT) { keys = data; data = previous(keys); }
        if (!(data instanceof MethodInsnNode dataCall) || keys != null && !(keys instanceof MethodInsnNode)) return false;
        byte[] payload = generated(node, dataCall, cache);
        if (payload == null) return false;
        String plain;
        switch (decryptor.kind) {
            case KEY_ARGUMENT -> {
                byte[] keyBytes = generated(node, (MethodInsnNode) keys, cache);
                if (keyBytes == null) return false;
                plain = xorUtf16(payload, key, keyBytes);
            }
            case KEY_FIELD -> {
                byte[] keyBytes = fieldBytes(node, decryptor.field, cache);
                if (keyBytes == null) return false;
                plain = xorUtf16(payload, key, keyBytes);
            }
            default -> {
                String buffer = bufferString(node, decryptor.field, cache);
                if (buffer == null || payload.length != 8) return false;
                int size = ((payload[0] & 0xFF) << 24) | ((payload[1] & 0xFF) << 16) | ((payload[2] & 0xFF) << 8) | (payload[3] & 0xFF);
                int offset = ((payload[4] & 0xFF) << 24) | ((payload[5] & 0xFF) << 16) | ((payload[6] & 0xFF) << 8) | (payload[7] & 0xFF);
                if (offset < 0 || size < 0 || offset + size > buffer.length()) return false;
                byte[] slice = buffer.substring(offset, offset + size).getBytes(StandardCharsets.UTF_16BE);
                byte[] seedBytes = Integer.toString(key).getBytes(StandardCharsets.ISO_8859_1);
                for (int i = 0; i < slice.length; i++) slice[i] ^= seedBytes[i % seedBytes.length];
                plain = new String(slice, StandardCharsets.UTF_16BE);
            }
        }
        // No label between the argument instructions and the call: they are one straight-line expression.
        AbstractInsnNode first = data;
        for (var node2 = first.getNext(); node2 != call; node2 = node2.getNext()) if (node2 instanceof LabelNode) return false;
        for (AbstractInsnNode node2 = first, next; node2 != call; node2 = next) { next = node2.getNext(); if (node2.getOpcode() >= 0) method.instructions.remove(node2); }
        method.instructions.set(call, new LdcInsnNode(plain));
        return true;
    }

    /** {@code dec(data, keys, seed)}: both XOR keys over the UTF-16 bytes (Integer.toString of a seed is ASCII in every charset). */
    static String xorUtf16(byte[] payload, int seed, byte[] keys) {
        byte[] bytes = payload.clone();
        byte[] seedBytes = Integer.toString(seed).getBytes(StandardCharsets.ISO_8859_1);
        if (keys.length == 0) return null;
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] ^= seedBytes[i % seedBytes.length];
            bytes[i] ^= keys[i % keys.length];
        }
        return new String(bytes, StandardCharsets.UTF_16);
    }

    /** The bytes {@code <clinit>} stores into a static byte[] field: {@code invokestatic gen()[B; putstatic field}. */
    private static byte[] fieldBytes(ClassNode node, String field, Map<String,byte[]> cache) {
        for (var put : clinitPuts(node, field, "[B")) {
            if (previous(put) instanceof MethodInsnNode gen) return generated(node, gen, cache);
        }
        return null;
    }

    /** The String {@code <clinit>} builds with {@code ByteBuffer.wrap(gen()).asCharBuffer().toString()}: raw big-endian chars. */
    private static String bufferString(ClassNode node, String field, Map<String,byte[]> cache) {
        for (var put : clinitPuts(node, field, "Ljava/lang/String;")) {
            var toString = previous(put); var asChars = previous(toString); var wrap = previous(asChars); var gen = previous(wrap);
            if (toString instanceof MethodInsnNode t && t.name.equals("toString") && asChars instanceof MethodInsnNode a && a.name.equals("asCharBuffer")
                && wrap instanceof MethodInsnNode w && w.owner.equals("java/nio/ByteBuffer") && w.name.equals("wrap") && gen instanceof MethodInsnNode g) {
                byte[] bytes = generated(node, g, cache);
                if (bytes == null) return null;
                char[] chars = new char[bytes.length / 2];
                for (int i = 0; i < chars.length; i++) chars[i] = (char) (((bytes[2 * i] & 0xFF) << 8) | (bytes[2 * i + 1] & 0xFF));
                return new String(chars);
            }
        }
        return null;
    }

    private static List<FieldInsnNode> clinitPuts(ClassNode node, String field, String desc) {
        var result = new ArrayList<FieldInsnNode>();
        for (var method : node.methods) {
            if (!method.name.equals("<clinit>")) continue;
            for (var insn : method.instructions)
                if (insn instanceof FieldInsnNode put && put.getOpcode() == PUTSTATIC && put.owner.equals(node.name) && put.name.equals(field) && put.desc.equals(desc))
                    result.add(put);
        }
        return result;
    }

    // ---------------------------------------------------------------- removal

    private static Set<String> usedMethods(ClassNode node) {
        var used = new HashSet<String>();
        for (var method : node.methods)
            for (var insn : method.instructions) {
                if (insn instanceof MethodInsnNode call && call.owner.equals(node.name)) used.add(call.name + call.desc);
                else if (insn instanceof InvokeDynamicInsnNode indy)
                    for (Object arg : indy.bsmArgs) if (arg instanceof org.objectweb.asm.Handle h && h.getOwner().equals(node.name)) used.add(h.getName() + h.getDesc());
            }
        return used;
    }

    private static boolean readOutsideClinit(ClassNode node, FieldNode field) {
        for (var method : node.methods) {
            if (method.name.equals("<clinit>")) continue;
            for (var insn : method.instructions)
                if (insn instanceof FieldInsnNode f && f.owner.equals(node.name) && f.name.equals(field.name)) return true;
        }
        return false;
    }

    private static boolean readInClinit(ClassNode node, FieldNode field) {
        for (var method : node.methods) {
            if (!method.name.equals("<clinit>")) continue;
            for (var insn : method.instructions)
                if (insn instanceof FieldInsnNode f && f.getOpcode() == GETSTATIC && f.owner.equals(node.name) && f.name.equals(field.name)) return true;
        }
        return false;
    }

    /** Removes {@code <clinit>}'s {@code gen()[B; [ByteBuffer.wrap; asCharBuffer; toString;] putstatic field}. */
    private static boolean removeKeyInit(ClassNode node, FieldNode field) {
        var puts = clinitPuts(node, field.name, field.desc);
        if (puts.isEmpty()) return false;
        for (var put : puts) {
            var chain = new ArrayList<AbstractInsnNode>(List.of(put));
            AbstractInsnNode at = previous(put);
            if (field.desc.equals("Ljava/lang/String;")) {
                for (int k = 0; k < 3; k++) { if (!(at instanceof MethodInsnNode)) return false; chain.add(at); at = previous(at); }
            }
            if (!(at instanceof MethodInsnNode gen) || !gen.desc.equals("()[B")) return false;
            chain.add(at);
            for (var node2 = at.getNext(); node2 != put; node2 = node2.getNext()) if (node2 instanceof LabelNode) return false;
            var clinit = node.methods.stream().filter(m -> m.instructions.contains(put)).findFirst().orElseThrow();
            for (var insn : chain) clinit.instructions.remove(insn);
        }
        return true;
    }

    /** Skidfuscator's {@code private static String[] nothing_to_see_here}, written only by its own {@code <clinit>} code. */
    private static boolean removeAhegao(ClassNode node) {
        FieldNode field = null;
        for (var candidate : node.fields)
            if (candidate.name.startsWith("nothing_to_see_here") && candidate.desc.equals("[Ljava/lang/String;") && (candidate.access & ACC_STATIC) != 0) field = candidate;
        if (field == null || readOutsideClinit(node, field)) return false;
        MethodNode clinit = null;
        for (var method : node.methods) if (method.name.equals("<clinit>")) clinit = method;
        if (clinit == null) return false;
        String name = field.name;
        var remove = new ArrayList<AbstractInsnNode>();
        for (var insn : clinit.instructions) {
            if (!(insn instanceof FieldInsnNode f) || !f.owner.equals(node.name) || !f.name.equals(name)) continue;
            if (f.getOpcode() == PUTSTATIC) {
                var alloc = previous(f); var length = previous(alloc);
                if (!(alloc instanceof TypeInsnNode t) || t.getOpcode() != ANEWARRAY || SkidModel.literal(length) == null) return false;
                remove.addAll(List.of(length, alloc, f));
            } else {
                var index = next(f); var value = next(index); var store = next(value);
                if (SkidModel.literal(index) == null || !(value instanceof LdcInsnNode) || store == null || store.getOpcode() != AASTORE) return false;
                remove.addAll(List.of(f, index, value, store));
            }
        }
        for (var insn : remove) clinit.instructions.remove(insn);
        node.fields.remove(field);
        return true;
    }

    static AbstractInsnNode previous(AbstractInsnNode insn) { return SeedInterpreter.previous(insn); }
    static AbstractInsnNode next(AbstractInsnNode insn) { return SeedInterpreter.next(insn); }
    static int slots(String desc) { int n = 0; for (var t : Type.getArgumentTypes(desc)) n += t.getSize(); return n; }
}
