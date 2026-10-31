package dev.classrevive.transforms.skid;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;
import java.util.*;
import java.util.function.IntUnaryOperator;

/**
 * Phase 2CQ: undoes Skidfuscator's Flow Switch. After the flow pass a protected switch reads
 * <pre>{@code <key>; ldc S; ixor; istore t; iload t; invokestatic Factory.h(I)I; lookupswitch {h(k ^ S): ...}}</pre>
 * where {@code h} is the hash factory's legacy hash {@code ((v * 31 >>> 4) % v) ^ (v >>> 16)}. Each original key {@code k}
 * is found by a bounded search over the values a switch key usually takes (−65536..1048575: enum ordinals, chars and
 * ordinary int constants, plus the {@code hashCode()} of string literals of the class for string switches). A switch is
 * rewritten only when every case has exactly one key in that space; the {@code tableswitch} / {@code lookupswitch}
 * choice follows javac's cost rule. The hash is evaluated statically (a native copy of the two known factory hashes,
 * otherwise {@link PureEvaluator}); nothing is executed.
 */
final class SwitchRecovery implements Opcodes {
    static final int LOW = -65536, HIGH = 1048575;
    int recovered, ambiguous, unmatched;
    private final Map<String,int[]> tables = new HashMap<>();

    void apply(Map<String,ClassNode> nodes, PureEvaluator pure, Set<String> changed) {
        for (var node : nodes.values()) {
            Set<Integer> stringHashes = null;
            for (var method : node.methods) {
                for (var insn : method.instructions.toArray()) {
                    if (!(insn instanceof LookupSwitchInsnNode || insn instanceof TableSwitchInsnNode)) continue;
                    var hash = SeedInterpreter.previous(insn);
                    if (!(hash instanceof MethodInsnNode call) || call.getOpcode() != INVOKESTATIC || !call.desc.equals("(I)I")) continue;
                    var load = SeedInterpreter.previous(call); var store = SeedInterpreter.previous(load);
                    var xor = SeedInterpreter.previous(store); var seed = SeedInterpreter.previous(xor);
                    Integer s = SkidModel.literal(seed);
                    if (!(load instanceof VarInsnNode l) || l.getOpcode() != ILOAD || !(store instanceof VarInsnNode st) || st.getOpcode() != ISTORE
                        || st.var != l.var || xor.getOpcode() != IXOR || s == null || !onlyPairedLoads(method, l.var)) continue;
                    for (var n = seed.getNext(); n != insn; n = n.getNext()) if (n instanceof LabelNode) { s = null; break; }
                    if (s == null) continue;
                    var function = function(nodes, pure, call);
                    if (function == null) { unmatched++; continue; }
                    if (stringHashes == null) stringHashes = stringHashes(node);
                    var keys = keys(insn);
                    var original = new LinkedHashMap<Integer,Integer>(); // obfuscated key -> original key
                    boolean ok = true;
                    var inverse = inverse(call, s, function);
                    for (int key : keys) {
                        var found = new TreeSet<Integer>(inverse.getOrDefault(key, List.of()));
                        for (int text : stringHashes) if (function.applyAsInt(text ^ s) == key) found.add(text);
                        if (found.size() != 1) { if (found.isEmpty()) unmatched++; else ambiguous++; ok = false; break; }
                        original.put(key, found.first());
                    }
                    if (!ok) continue;
                    var replacement = rebuild(insn, original);
                    for (var n : List.of(seed, xor, store, load, call)) method.instructions.remove(n);
                    method.instructions.set(insn, replacement);
                    recovered++; changed.add(node.name);
                }
            }
        }
    }

    /** Every load of the temporary comes straight after its store (Skidfuscator reuses one temporary for all switches). */
    private static boolean onlyPairedLoads(MethodNode method, int var) {
        for (var insn : method.instructions)
            if (insn instanceof VarInsnNode v && v.var == var && v.getOpcode() == ILOAD
                && !(SeedInterpreter.previous(insn) instanceof VarInsnNode p && p.getOpcode() == ISTORE && p.var == var)) return false;
        return true;
    }

    private static List<Integer> keys(AbstractInsnNode insn) {
        if (insn instanceof LookupSwitchInsnNode lookup) return lookup.keys;
        var table = (TableSwitchInsnNode) insn;
        var keys = new ArrayList<Integer>();
        for (int k = 0; k < table.labels.size(); k++) keys.add(table.min + k);
        return keys;
    }

    /** Original key -> obfuscated key preimages within the search space, cached per hash method and seed. */
    private Map<Integer,List<Integer>> inverse(MethodInsnNode call, int seed, IntUnaryOperator function) {
        String key = call.owner + "." + call.name + "#" + seed;
        int[] table = tables.computeIfAbsent(key, k -> {
            int[] values = new int[HIGH - LOW + 1];
            for (int v = LOW; v <= HIGH; v++) values[v - LOW] = function.applyAsInt(v ^ seed);
            return values;
        });
        var result = new HashMap<Integer,List<Integer>>();
        // Only the keys this switch uses are needed; the table is scanned once per switch.
        return new AbstractMap<Integer,List<Integer>>() {
            @Override public List<Integer> get(Object hashed) {
                return result.computeIfAbsent((Integer) hashed, h -> {
                    var found = new ArrayList<Integer>();
                    for (int i = 0; i < table.length && found.size() < 2; i++) if (table[i] == h) found.add(i + LOW);
                    return found;
                });
            }
            @Override public List<Integer> getOrDefault(Object hashed, List<Integer> fallback) { return get(hashed); }
            @Override public Set<Entry<Integer,List<Integer>>> entrySet() { return result.entrySet(); }
        };
    }

    /** The hash as a fast function: a native copy of the factory's legacy or bitwise hash, or the static evaluator. */
    static IntUnaryOperator function(Map<String,ClassNode> nodes, PureEvaluator pure, MethodInsnNode call) {
        var owner = nodes.get(call.owner);
        if (owner == null) return null;
        MethodNode method = null;
        for (var m : owner.methods) if (m.name.equals(call.name) && m.desc.equals(call.desc)) method = m;
        if (method == null) return null;
        var ops = new ArrayList<String>();
        for (var insn : method.instructions) {
            if (insn.getOpcode() < 0) continue;
            ops.add(insn instanceof IntInsnNode push ? "push" + push.operand : insn instanceof VarInsnNode v ? insn.getOpcode() + "@" + v.var : String.valueOf(insn.getOpcode()));
        }
        var legacy = List.of(ILOAD + "@0", String.valueOf(IFEQ), ILOAD + "@0", "push31", String.valueOf(IMUL), String.valueOf(ICONST_4),
            String.valueOf(IUSHR), ILOAD + "@0", String.valueOf(IREM), ILOAD + "@0", "push16", String.valueOf(IUSHR), String.valueOf(IXOR),
            String.valueOf(IRETURN), String.valueOf(ICONST_0), String.valueOf(IRETURN));
        if (ops.equals(legacy)) return v -> v == 0 ? 0 : (((v * 31) >>> 4) % v) ^ (v >>> 16);
        var bitwise = List.of(ILOAD + "@0", "push7", "push29", String.valueOf(ISHL), String.valueOf(IAND), "push29", String.valueOf(ISHR),
            ILOAD + "@0", String.valueOf(ICONST_3), String.valueOf(ISHL), String.valueOf(IOR), String.valueOf(IRETURN));
        if (ops.equals(bitwise)) return v -> ((v & (7 << 29)) >> 29) | (v << 3);
        if (!pure.pureBody(call.owner, call.name, call.desc)) return null;
        return v -> pure.evaluate(call.owner, call.name, call.desc, new Object[] {v}).map(r -> (Integer) r).orElse(Integer.MIN_VALUE);
    }

    private static Set<Integer> stringHashes(ClassNode node) {
        var result = new HashSet<Integer>();
        for (var method : node.methods)
            for (var insn : method.instructions)
                if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof String text) result.add(text.hashCode());
        return result;
    }

    /** A switch on the original keys, as javac would choose it (tableswitch when its cost is not higher). */
    static AbstractInsnNode rebuild(AbstractInsnNode insn, Map<Integer,Integer> original) {
        List<LabelNode> labels; LabelNode dflt;
        if (insn instanceof LookupSwitchInsnNode lookup) { labels = lookup.labels; dflt = lookup.dflt; }
        else { labels = ((TableSwitchInsnNode) insn).labels; dflt = ((TableSwitchInsnNode) insn).dflt; }
        var oldKeys = keys(insn);
        var cases = new TreeMap<Integer,LabelNode>();
        for (int k = 0; k < oldKeys.size(); k++) {
            if (labels.get(k) == dflt && insn instanceof TableSwitchInsnNode) continue; // a table's holes
            cases.put(original.get(oldKeys.get(k)), labels.get(k));
        }
        if (cases.isEmpty()) return new LookupSwitchInsnNode(dflt, new int[0], new LabelNode[0]);
        long lo = cases.firstKey(), hi = cases.lastKey(), n = cases.size();
        long tableSpace = 4 + (hi - lo + 1), tableTime = 3, lookupSpace = 3 + 2 * n, lookupTime = n;
        if (tableSpace + 3 * tableTime <= lookupSpace + 3 * lookupTime) {
            var table = new LabelNode[(int) (hi - lo + 1)];
            for (int k = 0; k < table.length; k++) table[k] = cases.getOrDefault((int) (lo + k), dflt);
            return new TableSwitchInsnNode((int) lo, (int) hi, dflt, table);
        }
        return new LookupSwitchInsnNode(dflt, cases.keySet().stream().mapToInt(Integer::intValue).toArray(), cases.values().toArray(new LabelNode[0]));
    }
}
