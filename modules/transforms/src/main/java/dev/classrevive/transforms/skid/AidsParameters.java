package dev.classrevive.transforms.skid;

import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;
import java.util.*;

/**
 * Phase 2CZ: Aidsfuscator's method salts. Its Method Salting appends an {@code int} to every method it can edit and gives
 * a whole override tree (super classes, interfaces and their implementations, joined) one salt; every call passes that
 * salt, as a literal or computed from the caller's own salt. Here:
 * <ul>
 *   <li>declarations are grouped by overriding inside the jar, and calls are resolved to their group (up the super
 *   classes and interfaces of the named owner);</li>
 *   <li>{@link #find}: a group whose calls all pass one and the same literal (once the callers are rewritten) gets that
 *   value as a known parameter of each declaration, so the rewriting folds what the body computes from it;</li>
 *   <li>{@link #remove}: once no declaration of a group reads the parameter, every declaration gets its descriptor back
 *   ({@link ParameterRecovery#rewriteDeclaration}) and every call drops its literal.</li>
 * </ul>
 * A group is left alone when a method handle names one of its methods, a call passes a computed value, a declaration
 * still reads the parameter or the shorter descriptor is taken.
 */
final class AidsParameters implements Opcodes {
    int groupsFound, methods, calls;
    /** Up to 20 groups left alone and why. */
    final List<String> kept = new ArrayList<>();
    private final Map<String,ClassNode> nodes;
    private final Set<String> skip;
    private final Map<String,MethodNode> declarations = new LinkedHashMap<>();
    private final Map<String,String> owners = new HashMap<>();
    private final Map<String,String> parent = new HashMap<>();
    private final Set<String> assigned = new HashSet<>();

    AidsParameters(Map<String,ClassNode> nodes, Set<String> skip) {
        this.nodes = nodes; this.skip = skip;
        for (var node : nodes.values()) {
            if (skip.contains(node.name)) continue;
            for (var method : node.methods) {
                String key = SkidModel.key(node.name, method.name, method.desc);
                declarations.put(key, method); owners.put(key, node.name); parent.put(key, key);
            }
        }
        for (var node : nodes.values()) {
            if (skip.contains(node.name)) continue;
            for (var method : node.methods) {
                if (!virtual(method)) continue;
                for (String ancestor : ancestors(node.name)) {
                    var declared = declared(ancestor, method.name, method.desc);
                    if (declared != null && virtual(declared)) union(SkidModel.key(node.name, method.name, method.desc), SkidModel.key(ancestor, method.name, method.desc));
                }
            }
        }
    }

    private static boolean virtual(MethodNode method) {
        return (method.access & (ACC_STATIC | ACC_PRIVATE)) == 0 && !method.name.startsWith("<");
    }

    private String root(String key) {
        String current = key;
        while (!parent.get(current).equals(current)) current = parent.get(current);
        parent.put(key, current);
        return current;
    }
    private void union(String a, String b) { String ra = root(a), rb = root(b); if (!ra.equals(rb)) parent.put(ra, rb); }

    private MethodNode declared(String owner, String name, String desc) {
        var node = nodes.get(owner);
        if (node == null || skip.contains(owner)) return null;
        for (var method : node.methods) if (method.name.equals(name) && method.desc.equals(desc)) return method;
        return null;
    }

    /** The jar's super classes and interfaces of a class, transitively (not the class itself). */
    private List<String> ancestors(String owner) {
        var result = new ArrayList<String>(); var seen = new HashSet<String>(); var pending = new ArrayDeque<String>();
        var start = nodes.get(owner);
        if (start == null) return result;
        if (start.superName != null) pending.add(start.superName);
        pending.addAll(start.interfaces);
        while (!pending.isEmpty()) {
            String type = pending.poll();
            if (!seen.add(type)) continue;
            var node = nodes.get(type);
            if (node == null) continue;
            result.add(type);
            if (node.superName != null) pending.add(node.superName);
            pending.addAll(node.interfaces);
        }
        return result;
    }

    /** The declaration a call reaches: the named owner, then its super classes and interfaces in the jar; null for a library method. */
    private String resolve(String owner, String name, String desc) {
        if (declared(owner, name, desc) != null) return SkidModel.key(owner, name, desc);
        if (name.startsWith("<")) return null;
        for (String ancestor : ancestors(owner)) if (declared(ancestor, name, desc) != null) return SkidModel.key(ancestor, name, desc);
        return null;
    }

    private static boolean trailingInt(String desc) {
        var args = Type.getArgumentTypes(desc);
        return args.length > 0 && args[args.length - 1].getSort() == Type.INT;
    }

    static int lastSlot(MethodNode method) {
        int slot = (method.access & ACC_STATIC) != 0 ? 0 : 1;
        var args = Type.getArgumentTypes(method.desc);
        for (int k = 0; k < args.length - 1; k++) slot += args[k].getSize();
        return slot;
    }

    /** The literal pushed right before a call (no label in between), or null. */
    private static Integer literalArgument(MethodInsnNode call) {
        var push = SeedInterpreter.previous(call);
        Integer value = SkidModel.literal(push);
        if (value == null) return null;
        for (var node = push.getNext(); node != call; node = node.getNext()) if (node instanceof LabelNode) return null;
        return value;
    }

    /** Groups named by a method handle (a lambda or method reference), which cannot get a literal. */
    private Set<String> handleRoots() {
        var result = new HashSet<String>();
        for (var node : nodes.values())
            for (var method : node.methods)
                for (var insn : method.instructions) {
                    var handles = new ArrayList<Handle>();
                    if (insn instanceof InvokeDynamicInsnNode indy) {
                        handles.add(indy.bsm);
                        for (Object arg : indy.bsmArgs) if (arg instanceof Handle h) handles.add(h);
                    } else if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof Handle h) handles.add(h);
                    for (var h : handles) {
                        String key = resolve(h.getOwner(), h.getName(), h.getDesc());
                        if (key != null) result.add(root(key));
                    }
                }
        return result;
    }

    /** Gives every qualifying group its salt as a known parameter; returns the number of declarations newly given one. */
    int find(Map<String,Map<Integer,int[]>> seedParameters) {
        var values = new HashMap<String,Set<Integer>>();
        var computed = new HashSet<String>(handleRoots());
        for (var node : nodes.values())
            for (var method : node.methods)
                for (var insn : method.instructions) {
                    if (!(insn instanceof MethodInsnNode call) || !trailingInt(call.desc)) continue;
                    String key = resolve(call.owner, call.name, call.desc);
                    if (key == null) continue;
                    String root = root(key);
                    Integer literal = literalArgument(call);
                    if (literal == null) computed.add(root); else values.computeIfAbsent(root, k -> new TreeSet<>()).add(literal);
                }
        int added = 0;
        for (var entry : values.entrySet()) {
            if (computed.contains(entry.getKey()) || entry.getValue().size() != 1) continue;
            int value = entry.getValue().iterator().next();
            for (var declaration : declarations.entrySet()) {
                if (!root(declaration.getKey()).equals(entry.getKey()) || seedParameters.containsKey(declaration.getKey())) continue;
                var method = declaration.getValue();
                if (!trailingInt(method.desc)) continue;
                int slot = lastSlot(method);
                if (writes(method, slot)) continue;
                seedParameters.put(declaration.getKey(), Map.of(slot, new int[] {value}));
                added++;
            }
            if (assigned.add(entry.getKey())) groupsFound++;
        }
        return added;
    }

    private static boolean writes(MethodNode method, int slot) {
        for (var insn : method.instructions) {
            if (insn instanceof VarInsnNode var && var.var == slot && var.getOpcode() != ILOAD) return true;
            if (insn instanceof IincInsnNode inc && inc.var == slot) return true;
        }
        return false;
    }

    private static boolean reads(MethodNode method, int slot) {
        for (var insn : method.instructions) {
            if (insn instanceof VarInsnNode var && var.var == slot) return true;
            if (insn instanceof IincInsnNode inc && inc.var == slot) return true;
        }
        return false;
    }

    private void keep(String what) { if (kept.size() < 20) kept.add(what); }

    /**
     * Removes the salt parameter of every group whose declarations no longer read it. A group nothing in the jar calls
     * (or names by a handle) qualifies too when a searched key showed one of its declarations' trailing int to be the
     * salt ({@code confirmed}): then the parameter is never the program's own.
     */
    void remove(Map<String,Map<Integer,int[]>> seedParameters, Set<String> changed, Set<MethodNode> confirmed) {
        var handles = handleRoots();
        var groups = new LinkedHashMap<String,List<String>>();
        for (String key : declarations.keySet()) groups.computeIfAbsent(root(key), k -> new ArrayList<>()).add(key);
        // Calls are resolved before any descriptor changes (the keys are the original descriptors).
        var callRoots = new IdentityHashMap<MethodInsnNode,String>();
        for (var node : nodes.values())
            for (var method : node.methods)
                for (var insn : method.instructions)
                    if (insn instanceof MethodInsnNode call && trailingInt(call.desc)) {
                        String key = resolve(call.owner, call.name, call.desc);
                        if (key != null) callRoots.put(call, root(key));
                    }
        for (var group : groups.values()) {
            String first = group.getFirst();
            String groupRoot = root(first);
            boolean uncalled = !callRoots.containsValue(groupRoot) && !handles.contains(groupRoot)
                && group.stream().allMatch(key -> trailingInt(declarations.get(key).desc))
                && group.stream().anyMatch(key -> confirmed.contains(declarations.get(key)))
                && group.stream().allMatch(key -> declarations.get(key).instructions.size() == 0 || confirmed.contains(declarations.get(key)));
            if (!uncalled) {
                if (group.stream().noneMatch(seedParameters::containsKey)) continue;
                if (!group.stream().allMatch(seedParameters::containsKey)) { keep(first + ": not every declaration has the salt"); continue; }
            }
            if (group.stream().anyMatch(key -> reads(declarations.get(key), lastSlot(declarations.get(key))))) { keep(first + ": the salt is still read"); continue; }
            var sites = new ArrayList<MethodInsnNode>();
            var siteMethods = new IdentityHashMap<MethodInsnNode,MethodNode>();
            var siteOwners = new IdentityHashMap<MethodInsnNode,String>();
            String root = root(first);
            boolean literalOnly = true;
            for (var node : nodes.values())
                for (var method : node.methods)
                    for (var insn : method.instructions)
                        if (insn instanceof MethodInsnNode call && root.equals(callRoots.get(call))) {
                            if (literalArgument(call) == null) literalOnly = false;
                            sites.add(call); siteMethods.put(call, method); siteOwners.put(call, node.name);
                        }
            if (!literalOnly) { keep(first + ": a call passes a computed value"); continue; }
            if (group.stream().anyMatch(key -> taken(key, group))) { keep(first + ": the shorter descriptor is taken"); continue; }
            for (String key : group) {
                var method = declarations.get(key);
                ParameterRecovery.rewriteDeclaration(method, lastSlot(method));
                method.desc = ParameterRecovery.dropLastInt(method.desc);
                if (method.signature != null && parameterCount(method.signature) > Type.getArgumentTypes(method.desc).length)
                    method.signature = dropLastInt(method.signature);
                changed.add(owners.get(key));
                methods++;
            }
            for (var call : sites) {
                siteMethods.get(call).instructions.remove(SeedInterpreter.previous(call));
                call.desc = ParameterRecovery.dropLastInt(call.desc);
                changed.add(siteOwners.get(call));
                calls++;
            }
        }
    }

    /**
     * A generic signature without its trailing {@code I} parameter (Aidsfuscator appends the salt to the signature too);
     * unchanged when it has none, null for null. A class name ending in I ends in {@code ;} and an int array in {@code [I}.
     */
    static String dropLastInt(String signature) {
        if (signature == null) return null;
        int close = signature.indexOf(')');
        if (close < 2 || signature.charAt(close - 1) != 'I' || signature.charAt(close - 2) == '[') return signature;
        return signature.substring(0, close - 1) + signature.substring(close);
    }

    /** The number of parameters of a method signature (type parameters and generic arguments skipped). */
    static int parameterCount(String signature) {
        int at = signature.indexOf('(') + 1, count = 0;
        while (at < signature.length() && signature.charAt(at) != ')') {
            while (signature.charAt(at) == '[') at++;
            char c = signature.charAt(at);
            if (c == 'L' || c == 'T') {
                int depth = 0;
                for (; at < signature.length(); at++) {
                    char d = signature.charAt(at);
                    if (d == '<') depth++;
                    else if (d == '>') depth--;
                    else if (d == ';' && depth == 0) break;
                }
            }
            at++; count++;
        }
        return count;
    }

    /** True when the declaration's class, or a jar class above or below it, already has the shorter descriptor outside the group. */
    private boolean taken(String key, List<String> group) {
        var method = declarations.get(key);
        String owner = owners.get(key);
        String shorter = ParameterRecovery.dropLastInt(method.desc);
        var related = new ArrayList<String>(List.of(owner));
        if (virtual(method)) {
            related.addAll(ancestors(owner));
            for (var node : nodes.values()) if (ancestors(node.name).contains(owner)) related.add(node.name);
        }
        for (String type : related) {
            var other = declared(type, method.name, shorter);
            if (other != null && !group.contains(SkidModel.key(type, method.name, shorter))) return true;
        }
        return false;
    }
}
