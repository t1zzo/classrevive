package dev.classrevive.transforms.skid;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;
import java.io.IOException;
import java.util.*;

/**
 * Phase 2CZ: Aidsfuscator's Reference Obfuscation. Each method call ({@code invokestatic}, {@code invokevirtual},
 * {@code invokeinterface}) and field access became {@code <int index>; <int key>; invokedynamic "<kind char>"} with the
 * bootstrap of a generated class. That class holds the references as {@code "owner:name:desc"} strings whose chars are
 * XORed with the key's upper 16 bits, at {@code index ^ indexKey}; its main invoker maps the kind char (a lookupswitch)
 * to {@code findVirtual}, {@code findStatic}, {@code findGetter}, {@code findSetter}, {@code findStaticGetter} or
 * {@code findStaticSetter}. Once both ints are literals (the key is salted: it becomes one when the caller's salt is
 * known), the original instruction is written back. Whether a virtual call was {@code invokeinterface} comes from the
 * owner's class header (the jar's, or the JDK's read as a resource, never loaded).
 */
final class AidsReferences implements Opcodes {
    /** {@code searched}: decoded with a key found by search (the caller's salt unknown). */
    int decoded, searched;
    private final String owner, bootstrapName, bootstrapDesc;
    private final Map<Integer,String> references = new HashMap<>();
    private final Map<Integer,String> kinds = new HashMap<>();
    private Integer indexKey;
    private final Map<String,ClassNode> nodes;
    private final Map<String,Boolean> interfaces = new HashMap<>();

    AidsReferences(ClassNode reference, Map<String,ClassNode> nodes) {
        this.owner = reference.name; this.nodes = nodes;
        String name = null, desc = null;
        for (var method : reference.methods) {
            if (method.desc.equals("(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;")) {
                name = method.name; desc = method.desc;
            }
            if (method.desc.equals("(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/invoke/MutableCallSite;Ljava/lang/String;Ljava/lang/invoke/MethodType;II)Ljava/lang/invoke/MethodHandle;"))
                readInvoker(method);
            // <the array>; <int j>; ldc "<token>"; aastore (Aidsfuscator keeps the array on the stack with dup)
            if ((method.access & ACC_STATIC) == 0) continue;
            for (var insn : method.instructions) {
                if (insn.getOpcode() != AASTORE) continue;
                var value = SeedInterpreter.previous(insn);
                Integer at = SkidModel.literal(SeedInterpreter.previous(value));
                if (value instanceof LdcInsnNode ldc && ldc.cst instanceof String token && at != null) references.put(at, token);
            }
        }
        bootstrapName = name; bootstrapDesc = desc;
    }

    boolean usable() { return bootstrapName != null && indexKey != null && !references.isEmpty() && kinds.size() >= 2; }

    /** The index key ({@code iload 4; <int>; ixor}) and the kind char of each lookupswitch case. */
    private void readInvoker(MethodNode method) {
        for (var insn : method.instructions) {
            if (insn.getOpcode() == IXOR && indexKey == null) {
                var right = SeedInterpreter.previous(insn); var left = SeedInterpreter.previous(right);
                if (left instanceof VarInsnNode var && var.getOpcode() == ILOAD && var.var == 4 && SkidModel.literal(right) != null) indexKey = SkidModel.literal(right);
                else if (right instanceof VarInsnNode var && var.getOpcode() == ILOAD && var.var == 4 && SkidModel.literal(left) != null) indexKey = SkidModel.literal(left);
            }
            if (insn instanceof LookupSwitchInsnNode lookup)
                for (int k = 0; k < lookup.keys.size(); k++) {
                    String kind = kind(lookup.keys.get(k), lookup.labels.get(k));
                    if (kind != null) kinds.put(lookup.keys.get(k), kind);
                }
        }
    }

    /** The {@code find*} a case reaches; a field case tests the char again to pick the getter or the setter. */
    private static String kind(int key, LabelNode label) {
        for (var node = SeedInterpreter.next(label); node != null; node = SeedInterpreter.next(node)) {
            if (node instanceof MethodInsnNode call && call.owner.equals("java/lang/invoke/MethodHandles$Lookup") && call.name.startsWith("find")) return call.name;
            if (node instanceof JumpInsnNode jump && jump.getOpcode() == IF_ICMPEQ) {
                Integer getter = SkidModel.literal(SeedInterpreter.previous(jump));
                if (getter == null) return null;
                return key == getter ? find(SeedInterpreter.next(jump.label)) : find(SeedInterpreter.next(jump));
            }
            if (node instanceof JumpInsnNode || node.getOpcode() >= IRETURN && node.getOpcode() <= RETURN || node.getOpcode() == ATHROW) return null;
        }
        return null;
    }
    private static String find(AbstractInsnNode from) {
        for (var node = from; node != null; node = SeedInterpreter.next(node)) {
            if (node instanceof MethodInsnNode call && call.owner.equals("java/lang/invoke/MethodHandles$Lookup") && call.name.startsWith("find")) return call.name;
            if (node instanceof JumpInsnNode) return null;
        }
        return null;
    }

    /**
     * Writes back every reference of the method whose index and key are literals, and with {@code search} also those
     * whose key is salted with a salt no call reveals; returns the number decoded.
     */
    int decode(MethodNode method, boolean search) {
        int count = 0;
        for (var insn : method.instructions.toArray()) {
            if (!(insn instanceof InvokeDynamicInsnNode indy) || !indy.bsm.getOwner().equals(owner) || !indy.bsm.getName().equals(bootstrapName)
                || !indy.bsm.getDesc().equals(bootstrapDesc) || indy.name.length() != 1) continue;
            var keyPush = SeedInterpreter.previous(indy);
            Integer key = SkidModel.literal(keyPush);
            // A key salted with an unknown salt: iload s; <mask>; iand|ior; <c>; ixor (only its upper 16 bits matter).
            List<AbstractInsnNode> keyCode = key != null ? List.of(keyPush) : search ? saltedKey(keyPush) : null;
            if (keyCode == null) continue;
            var indexPush = SeedInterpreter.previous(keyCode.getFirst());
            Integer index = SkidModel.literal(indexPush);
            if (index == null || label(indexPush, indy)) continue;
            String token = references.get(index ^ indexKey);
            String kind = kinds.get((int) indy.name.charAt(0));
            if (token == null || kind == null) continue;
            int upper = key != null ? key >>> 16 : search(token, keyCode, kind);
            if (upper < 0) continue;
            String[] parts = split(token, upper);
            if (parts == null) continue;
            if (key == null) { searched++; deduce(method, keyCode, upper); }
            String target = parts[0].replace('.', '/'), name = parts[1], desc = parts[2];
            AbstractInsnNode replacement = switch (kind) {
                case "findStatic" -> new MethodInsnNode(INVOKESTATIC, target, name, desc, isInterface(target));
                case "findVirtual" -> {
                    Boolean itf = isInterface(target);
                    yield itf == null ? null : new MethodInsnNode(itf ? INVOKEINTERFACE : INVOKEVIRTUAL, target, name, desc, itf);
                }
                case "findGetter" -> new FieldInsnNode(GETFIELD, target, name, Type.getReturnType(desc).getDescriptor());
                case "findSetter" -> new FieldInsnNode(PUTFIELD, target, name, Type.getReturnType(desc).getDescriptor());
                case "findStaticGetter" -> new FieldInsnNode(GETSTATIC, target, name, Type.getReturnType(desc).getDescriptor());
                case "findStaticSetter" -> new FieldInsnNode(PUTSTATIC, target, name, Type.getReturnType(desc).getDescriptor());
                default -> null;
            };
            if (replacement instanceof MethodInsnNode call && call.getOpcode() == INVOKESTATIC && isInterface(target) == null) replacement = null;
            if (replacement == null) continue;
            method.instructions.remove(indexPush);
            for (var node : keyCode) method.instructions.remove(node);
            method.instructions.set(indy, replacement);
            count++;
        }
        decoded += count;
        return count;
    }

    private static String[] split(String token, int key) {
        var chars = token.toCharArray();
        for (int i = 0; i < chars.length; i++) chars[i] = (char) (chars[i] ^ key);
        var parts = new String(chars).split(":", -1);
        return parts.length == 3 ? parts : null;
    }

    /** The five instructions {@code iload s; <mask>; iand|ior; <c>; ixor} ending at {@code xor}, or null. */
    private static List<AbstractInsnNode> saltedKey(AbstractInsnNode xor) {
        if (xor == null || xor.getOpcode() != IXOR) return null;
        var c = SeedInterpreter.previous(xor); var op = SeedInterpreter.previous(c);
        var mask = SeedInterpreter.previous(op); var load = SeedInterpreter.previous(mask);
        if (SkidModel.literal(c) == null || op == null || op.getOpcode() != IAND && op.getOpcode() != IOR || SkidModel.literal(mask) == null
            || !(load instanceof VarInsnNode var) || var.getOpcode() != ILOAD || label(load, xor)) return null;
        return List.of(load, mask, op, c, xor);
    }

    /**
     * The one reference the token decodes to over every upper half the salted key can take: with {@code ior} the mask's
     * bits are 1, with {@code iand} the bits outside it are 0, the other bits are free (at most 2^16 keys). A key is
     * accepted when the reference is well formed and its owner (the jar's or the JDK's) declares the member; null when
     * no key or more than one key passes.
     */
    private int search(String token, List<AbstractInsnNode> keyCode, String kind) {
        int mask = SkidModel.literal(keyCode.get(1)) >>> 16, c = SkidModel.literal(keyCode.get(3)) >>> 16;
        boolean or = keyCode.get(2).getOpcode() == IOR;
        int free = (or ? ~mask : mask) & 0xFFFF, fixed = or ? mask : 0;
        int found = -1;
        // Every subset of the free bits.
        for (int bits = free; ; bits = (bits - 1) & free) {
            int upper = ((fixed | bits) ^ c) & 0xFFFF;
            var parts = split(token, upper);
            if (parts != null && valid(parts, kind)) {
                if (found >= 0) return -1;
                found = upper;
            }
            if (bits == 0) break;
        }
        return found;
    }

    /**
     * What a searched key tells about the salt: a salted key is {@code decKey << 16} with its low half zero, so
     * {@code salt | mask} (with {@code ior}) or {@code salt & mask} (with {@code iand}) is {@code (decKey << 16) ^ c}.
     * Keyed by {@code slot:mask:opcode}; a value seen twice differently is dropped.
     */
    final Map<MethodNode,Map<String,Integer>> deduced = new IdentityHashMap<>();
    private final Set<String> conflicting = new HashSet<>();
    private void deduce(MethodNode method, List<AbstractInsnNode> keyCode, int upper) {
        int slot = ((VarInsnNode) keyCode.get(0)).var, mask = SkidModel.literal(keyCode.get(1)), c = SkidModel.literal(keyCode.get(3));
        String key = slot + ":" + mask + ":" + keyCode.get(2).getOpcode();
        int value = (upper << 16) ^ c;
        var values = deduced.computeIfAbsent(method, m -> new HashMap<>());
        Integer previous = values.putIfAbsent(key, value);
        if (previous != null && previous != value) conflicting.add(System.identityHashCode(method) + "|" + key);
        if (slot == AidsParameters.lastSlot(method) && method.desc.contains("I)")) confirmed.add(method);
    }

    /** Methods whose trailing int a searched key showed to be Aidsfuscator's salt. */
    final Set<MethodNode> confirmed = Collections.newSetFromMap(new IdentityHashMap<>());

    /**
     * Writes each deduced {@code iload slot; <mask>; ior|iand} of the method as the constant (the slot must never be
     * stored: a salt parameter or salt local); returns the number of places rewritten.
     */
    int applyDeduced(MethodNode method) {
        // Kept for later rounds: more such expressions appear as the method folds (a mask hidden by the flattening key).
        var values = deduced.get(method);
        if (values == null) return 0;
        int count = 0;
        for (var entry : values.entrySet()) {
            if (conflicting.contains(System.identityHashCode(method) + "|" + entry.getKey())) continue;
            var parts = entry.getKey().split(":");
            int slot = Integer.parseInt(parts[0]), mask = Integer.parseInt(parts[1]), op = Integer.parseInt(parts[2]);
            boolean stored = false;
            for (var insn : method.instructions)
                if (insn instanceof VarInsnNode var && var.var == slot && var.getOpcode() != ILOAD || insn instanceof IincInsnNode inc && inc.var == slot) stored = true;
            if (stored) continue;
            for (var insn : method.instructions.toArray()) {
                if (!(insn instanceof VarInsnNode load) || load.getOpcode() != ILOAD || load.var != slot) continue;
                var push = SeedInterpreter.next(load); var combine = SeedInterpreter.next(push);
                Integer literal = SkidModel.literal(push);
                if (literal == null || literal != mask || combine == null || combine.getOpcode() != op || label(load, combine)) continue;
                method.instructions.insertBefore(load, new LdcInsnNode(entry.getValue()));
                method.instructions.remove(load); method.instructions.remove(push); method.instructions.remove(combine);
                count++;
            }
        }
        return count;
    }

    private boolean valid(String[] parts, String kind) {
        if (!parts[0].matches("[\\p{L}_$][\\p{L}\\p{N}_$]*(\\.[\\p{L}_$][\\p{L}\\p{N}_$]*)*")) return false;
        if (!parts[1].matches("[\\p{L}_$][\\p{L}\\p{N}_$]*|<init>")) return false;
        try { Type.getMethodType(parts[2]).getArgumentTypes(); } catch (RuntimeException malformed) { return false; }
        if (!parts[2].matches("\\(([ZBCSIJFD]|\\[+([ZBCSIJFD]|L[^;()]+;)|L[^;()]+;)*\\)(V|[ZBCSIJFD]|\\[+([ZBCSIJFD]|L[^;()]+;)|L[^;()]+;)")) return false;
        boolean field = !kind.equals("findStatic") && !kind.equals("findVirtual");
        if (field && !parts[2].startsWith("()")) return false;
        return declares(parts[0].replace('.', '/'), parts[1], field ? Type.getReturnType(parts[2]).getDescriptor() : parts[2], field, new HashSet<>());
    }

    /** Whether the class or one of its super types (the jar's, or the JDK's class files read as resources) declares the member. */
    private boolean declares(String type, String name, String desc, boolean field, Set<String> seen) {
        if (type == null || !seen.add(type)) return false;
        var node = header(type);
        if (node == null) return false;
        if (field) { for (var f : node.fields) if (f.name.equals(name) && f.desc.equals(desc)) return true; }
        else for (var m : node.methods) if (m.name.equals(name) && m.desc.equals(desc)) return true;
        if (declares(node.superName, name, desc, field, seen)) return true;
        for (String itf : node.interfaces) if (declares(itf, name, desc, field, seen)) return true;
        return false;
    }

    private final Map<String,Optional<ClassNode>> headers = new HashMap<>();
    private ClassNode header(String type) {
        return headers.computeIfAbsent(type, t -> {
            var node = nodes.get(t);
            if (node != null) return Optional.of(node);
            try (var in = ClassLoader.getPlatformClassLoader().getResourceAsStream(t + ".class")) {
                if (in == null) return Optional.empty();
                var read = new ClassNode();
                new ClassReader(in.readAllBytes()).accept(read, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                return Optional.of(read);
            } catch (IOException | RuntimeException unreadable) { return Optional.empty(); }
        }).orElse(null);
    }

    private static boolean label(AbstractInsnNode from, AbstractInsnNode to) {
        for (var node = from.getNext(); node != to; node = node.getNext()) if (node instanceof LabelNode) return true;
        return false;
    }

    /** Whether a class is an interface: the jar's header, else the JDK's class file read as a resource; null when unknown. */
    private Boolean isInterface(String type) {
        return interfaces.computeIfAbsent(type, t -> {
            var node = nodes.get(t);
            if (node != null) return (node.access & ACC_INTERFACE) != 0;
            try (var in = ClassLoader.getPlatformClassLoader().getResourceAsStream(t + ".class")) {
                if (in == null) return null;
                return (new ClassReader(in.readAllBytes()).getAccess() & ACC_INTERFACE) != 0;
            } catch (IOException | RuntimeException unreadable) { return null; }
        });
    }
}
