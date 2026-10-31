package dev.classrevive.transforms.skid;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.Frame;
import java.util.*;

/**
 * Phase 2CN: what the whole jar tells about Skidfuscator's seeds, found by static analysis only:
 * <ul>
 *   <li><b>class seed fields</b>: a static int of a class that its {@code <clinit>} sets to one obfuscator-derived
 *   constant ({@code new Random(long).nextInt() ^ C}); other writes are allowed only right before a throw (the
 *   obfuscator's traps);</li>
 *   <li><b>instance seed fields</b>: an int field that every constructor of the class sets to the same obfuscator-derived
 *   constant ({@code C ^ classSeed});</li>
 *   <li><b>injected seed parameters</b>: a trailing int parameter that the body only XORs, and that every call in the jar
 *   passes as a literal.</li>
 * </ul>
 */
final class SkidModel implements SeedInterpreter.Facts, Opcodes {
    final Map<String,ClassNode> classes;
    final PureEvaluator pure;
    final Map<String,Integer> staticSeeds = new HashMap<>();
    final Map<String,Integer> instanceSeeds = new HashMap<>();
    /** Method key {@code owner.name desc} to the parameter's local index and possible values. */
    final Map<String,Map<Integer,int[]>> seedParameters = new HashMap<>();

    SkidModel(Map<String,ClassNode> classes) {
        this.classes = classes;
        this.pure = new PureEvaluator(classes);
    }

    static String key(String owner, String name, String desc) { return owner + "." + name + desc; }

    @Override public Integer staticInt(String owner, String name, String desc) { return desc.equals("I") ? staticSeeds.get(owner + "." + name) : null; }
    @Override public Integer instanceInt(String owner, String name, String desc) { return desc.equals("I") ? instanceSeeds.get(owner + "." + name) : null; }

    SeedInterpreter interpreter() { return new SeedInterpreter(this, pure); }

    private Set<String> factories;
    /**
     * A method of a hash factory class (only pure static {@code (I)I} methods, no fields), or a per-class XOR helper
     * {@code static int x(int a, int b) { return b ^ a; }}. With {@code desc} "hash": only the factory hashes (whose
     * results are "hashed").
     */
    @Override public boolean hashFunction(String owner, String name, String desc) {
        if (factories == null) {
            factories = new HashSet<>();
            for (var node : classes.values()) if (SkidfuscatorReverser.factory(node, pure)) factories.add(node.name);
        }
        if (factories.contains(owner)) return true;
        if (desc.equals("hash")) return false;
        var node = classes.get(owner);
        if (node == null) return false;
        for (var method : node.methods) if (method.name.equals(name) && method.desc.equals(desc)) return SkidfuscatorReverser.xorHelper(method);
        return false;
    }

    void build() {
        findSeedParameters();
        var writes = fieldWrites();
        for (var node : classes.values()) {
            for (var method : node.methods) {
                if (!method.name.equals("<clinit>") || method.instructions.size() == 0) continue;
                collect(node.name, method, PUTSTATIC, writes, staticSeeds);
            }
        }
        for (var node : classes.values()) {
            // Every constructor that writes the field writes the same constant (one delegating with this(...) may not write it).
            var values = new HashMap<String,Set<Integer>>();
            var rejected = new HashSet<String>();
            for (var method : node.methods) {
                if (!method.name.equals("<init>") || method.instructions.size() == 0) continue;
                var found = new HashMap<String,Integer>();
                rejected.addAll(collect(node.name, method, PUTFIELD, writes, found));
                found.forEach((name, value) -> values.computeIfAbsent(name, k -> new HashSet<>()).add(value));
            }
            values.forEach((name, set) -> { if (set.size() == 1 && !rejected.contains(name)) instanceSeeds.put(name, set.iterator().next()); });
        }
    }

    /** Finds writes {@code owner.f = <one tainted constant>} of int fields of the class in one method; returns the rejected fields. */
    private Set<String> collect(String owner, MethodNode method, int opcode, Map<String,List<AbstractInsnNode>> writes, Map<String,Integer> into) {
        var result = new SeedAnalyzer(interpreter()).analyze(owner, method, seedParameters.getOrDefault(key(owner, method.name, method.desc), Map.of()));
        if (result == null) return Set.of();
        var candidates = new HashMap<String,Integer>();
        var rejected = new HashSet<String>();
        for (int i = 0; i < result.insns().length; i++) {
            Frame<SV> frame = result.frames()[i];
            if (frame == null || !(result.insns()[i] instanceof FieldInsnNode field) || field.getOpcode() != opcode
                || !field.owner.equals(owner) || !field.desc.equals("I")) continue;
            String name = owner + "." + field.name;
            SV value = frame.getStack(frame.getStackSize() - 1);
            if (!value.tainted || !value.singleton()) { rejected.add(name); continue; }
            Integer previous = candidates.putIfAbsent(name, value.single());
            if (previous != null && previous != value.single()) rejected.add(name);
        }
        for (var entry : candidates.entrySet()) {
            if (rejected.contains(entry.getKey())) continue;
            // Every other write in the jar must be a trap: straight-line code that ends in a throw.
            boolean onlyTraps = true;
            for (var write : writes.getOrDefault(entry.getKey(), List.of())) {
                if (method.instructions.contains(write)) continue;
                if (!trap(write)) { onlyTraps = false; break; }
            }
            if (onlyTraps) into.put(entry.getKey(), entry.getValue()); else rejected.add(entry.getKey());
        }
        return rejected;
    }

    private static boolean trap(AbstractInsnNode write) {
        int steps = 0;
        for (var node = write.getNext(); node != null && steps < 16; node = node.getNext()) {
            int op = node.getOpcode();
            if (op < 0) continue;
            steps++;
            if (op == ATHROW) return true;
            if (node instanceof JumpInsnNode || node instanceof TableSwitchInsnNode || node instanceof LookupSwitchInsnNode
                || op >= IRETURN && op <= RETURN || node instanceof MethodInsnNode && !((MethodInsnNode) node).name.equals("<init>")) return false;
        }
        return false;
    }

    private Map<String,List<AbstractInsnNode>> fieldWrites() {
        var writes = new HashMap<String,List<AbstractInsnNode>>();
        for (var node : classes.values())
            for (var method : node.methods)
                for (var insn : method.instructions)
                    if (insn instanceof FieldInsnNode field && (insn.getOpcode() == PUTSTATIC || insn.getOpcode() == PUTFIELD) && field.desc.equals("I"))
                        writes.computeIfAbsent(field.owner + "." + field.name, k -> new ArrayList<>()).add(insn);
        return writes;
    }

    /**
     * A trailing int parameter that the body only loads straight into an XOR (never stores), and that every call in the
     * jar with the same name and descriptor passes as a literal.
     */
    private void findSeedParameters() {
        var literals = new HashMap<String,Set<Integer>>();
        var nonLiteral = new HashSet<String>();
        for (var node : classes.values())
            for (var method : node.methods)
                for (var insn : method.instructions) {
                    if (!(insn instanceof MethodInsnNode call) || !call.desc.contains("I)")) continue;
                    String declaring = declaring(call.owner, call.name, call.desc);
                    if (declaring == null) continue; // a library method
                    String signature = key(declaring, call.name, call.desc);
                    var previous = SeedInterpreter.previous(call);
                    Integer literal = literal(previous);
                    if (literal == null) nonLiteral.add(signature);
                    else literals.computeIfAbsent(signature, k -> new TreeSet<>()).add(literal);
                }
        for (var node : classes.values()) {
            for (var method : node.methods) {
                var args = Type.getArgumentTypes(method.desc);
                if (args.length == 0 || args[args.length - 1].getSort() != Type.INT || method.instructions.size() == 0
                    || SkidfuscatorReverser.xorHelper(method)) continue;
                String signature = key(node.name, method.name, method.desc);
                if (nonLiteral.contains(signature) || !literals.containsKey(signature)) continue;
                // An override is called through its super type's signature too: every declaration of the group must qualify.
                if (overridesOutside(node, method)) continue;
                int slot = (method.access & ACC_STATIC) != 0 ? 0 : 1;
                for (int k = 0; k < args.length - 1; k++) slot += args[k].getSize();
                int loads = 0; boolean onlyXor = true;
                for (var insn : method.instructions) {
                    if (insn instanceof VarInsnNode var && var.var == slot) {
                        if (var.getOpcode() != ILOAD) { onlyXor = false; break; }
                        loads++;
                        var next = SeedInterpreter.next(insn);
                        if (next == null || next.getOpcode() != IXOR) { onlyXor = false; break; }
                    }
                    if (insn instanceof IincInsnNode inc && inc.var == slot) { onlyXor = false; break; }
                }
                if (!onlyXor || loads == 0) continue;
                int[] values = literals.get(signature).stream().mapToInt(Integer::intValue).toArray();
                seedParameters.put(key(node.name, method.name, method.desc), Map.of(slot, values));
            }
        }
    }

    /** The jar class that declares {@code name desc} for a call on {@code owner} (walking up the super classes), or null. */
    String declaring(String owner, String name, String desc) {
        var seen = new HashSet<String>();
        for (String type = owner; type != null && seen.add(type); ) {
            var node = classes.get(type);
            if (node == null) return null;
            for (var method : node.methods) if (method.name.equals(name) && method.desc.equals(desc)) return type;
            type = node.superName;
        }
        return null;
    }

    /** True when a super class or interface (in the jar or not) also declares the method: calls may name the other owner. */
    private boolean overridesOutside(ClassNode node, MethodNode method) {
        if (method.name.startsWith("<") || (method.access & (ACC_STATIC | ACC_PRIVATE)) != 0) return false;
        var pending = new ArrayDeque<String>();
        if (node.superName != null) pending.add(node.superName);
        pending.addAll(node.interfaces);
        var seen = new HashSet<String>();
        while (!pending.isEmpty()) {
            String type = pending.poll();
            if (!seen.add(type)) continue;
            var parent = classes.get(type);
            if (parent == null) return !type.equals("java/lang/Object") || method.name.equals("hashCode") || method.name.equals("equals");
            for (var other : parent.methods) if (other.name.equals(method.name) && other.desc.equals(method.desc)) return true;
            if (parent.superName != null) pending.add(parent.superName);
            pending.addAll(parent.interfaces);
        }
        return false;
    }

    static Integer literal(AbstractInsnNode insn) {
        if (insn == null) return null;
        int op = insn.getOpcode();
        if (op >= ICONST_M1 && op <= ICONST_5) return op - ICONST_0;
        if (op == BIPUSH || op == SIPUSH) return ((IntInsnNode) insn).operand;
        if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof Integer value) return value;
        return null;
    }
}
