package dev.classrevive.transforms.flow;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;
import java.util.*;

/**
 * Phase 2DL: static {@code final} fields of the input whose value is proved, from the bytecode alone, to be one constant
 * whenever any code can read them:
 *
 * <ol>
 * <li>a {@code ConstantValue} attribute (the JVM sets it while preparing the class, before any code runs) and no
 * {@code putstatic} to the field anywhere in the input; or</li>
 * <li>no {@code ConstantValue}, and the only {@code putstatic} in the input is in the holder's own {@code <clinit>},
 * inside its straight-line prefix (constants and stores to the holder's own fields only, so no other code can run before
 * it), storing a literal; and no superclass or superinterface of the holder in the input has a {@code <clinit>} (which
 * could call into the holder before its initializer starts).</li>
 * </ol>
 *
 * Only {@code int}-like and {@code long} fields. Assumption (stated in the report): nothing writes a {@code static final}
 * field outside the bytecode (no {@code Unsafe}, no JNI); reflection cannot.
 */
final class JarFacts implements Opcodes {
    private final Map<String,Object> constants = new HashMap<>();

    static JarFacts of(Collection<ClassNode> classes) {
        var facts = new JarFacts();
        var byName = new HashMap<String,ClassNode>();
        for (var node : classes) byName.put(node.name, node);
        var writes = new HashMap<String,Integer>();
        for (var node : classes) for (var method : node.methods) for (var insn : method.instructions)
            if (insn instanceof FieldInsnNode field && insn.getOpcode() == PUTSTATIC) writes.merge(key(field.owner, field.name, field.desc), 1, Integer::sum);
        for (var node : classes) {
            var prefix = clinitPrefix(node);
            boolean quietSupers = quietSupertypes(node, byName);
            for (var field : node.fields) {
                if ((field.access & (ACC_STATIC | ACC_FINAL)) != (ACC_STATIC | ACC_FINAL)) continue;
                if (!List.of("I", "Z", "B", "C", "S", "J").contains(field.desc)) continue;
                String key = key(node.name, field.name, field.desc);
                int count = writes.getOrDefault(key, 0);
                Object value = null;
                if (field.value != null) { if (count == 0) value = field.value; }
                else if (count == 1 && quietSupers) value = prefix.get(key);
                if (fits(field.desc, value)) facts.constants.put(key, value);
            }
        }
        return facts;
    }

    /** A value the field's type holds as it is (no narrowing on store). */
    private static boolean fits(String desc, Object value) {
        if (desc.equals("J")) return value instanceof Long;
        if (!(value instanceof Integer v)) return false;
        return switch (desc) {
            case "Z" -> v == 0 || v == 1;
            case "B" -> v >= Byte.MIN_VALUE && v <= Byte.MAX_VALUE;
            case "C" -> v >= Character.MIN_VALUE && v <= Character.MAX_VALUE;
            case "S" -> v >= Short.MIN_VALUE && v <= Short.MAX_VALUE;
            default -> true;
        };
    }

    /** The proved value (Integer or Long) of a field, or null. */
    Object constant(String owner, String name, String desc) { return constants.get(key(owner, name, desc)); }
    int size() { return constants.size(); }

    static String key(String owner, String name, String desc) { return owner + "." + name + ":" + desc; }

    /** Literal stores to the holder's own static fields in the straight-line prefix of its {@code <clinit>}. */
    private static Map<String,Object> clinitPrefix(ClassNode node) {
        var stores = new HashMap<String,Object>();
        for (var method : node.methods) {
            if (!method.name.equals("<clinit>")) continue;
            Object pending = null;
            for (var insn : method.instructions) {
                int op = insn.getOpcode();
                if (op < 0 || op == NOP) continue;
                Object literal = literal(insn);
                if (literal != null && pending == null) { pending = literal; continue; }
                if (insn instanceof FieldInsnNode field && op == PUTSTATIC && field.owner.equals(node.name) && pending != null) {
                    boolean wide = field.desc.equals("J");
                    if (wide == pending instanceof Long) stores.put(key(field.owner, field.name, field.desc), pending);
                    pending = null; continue;
                }
                break;
            }
        }
        return stores;
    }

    private static Object literal(AbstractInsnNode insn) {
        int op = insn.getOpcode();
        if (op >= ICONST_M1 && op <= ICONST_5) return op - ICONST_0;
        if (op == LCONST_0 || op == LCONST_1) return (long) (op - LCONST_0);
        if (insn instanceof IntInsnNode number && (op == BIPUSH || op == SIPUSH)) return number.operand;
        if (insn instanceof LdcInsnNode ldc && (ldc.cst instanceof Integer || ldc.cst instanceof Long)) return ldc.cst;
        return null;
    }

    /** No superclass or superinterface of {@code node} inside the input declares a static initializer. */
    private static boolean quietSupertypes(ClassNode node, Map<String,ClassNode> byName) {
        var pending = new ArrayDeque<String>(); var seen = new HashSet<String>();
        if (node.superName != null) pending.add(node.superName);
        pending.addAll(node.interfaces);
        while (!pending.isEmpty()) {
            var type = byName.get(pending.poll());
            if (type == null || !seen.add(type.name)) continue;
            for (var method : type.methods) if (method.name.equals("<clinit>")) return false;
            if (type.superName != null) pending.add(type.superName);
            pending.addAll(type.interfaces);
        }
        return true;
    }
}
