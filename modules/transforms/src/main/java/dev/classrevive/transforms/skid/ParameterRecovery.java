package dev.classrevive.transforms.skid;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;
import java.util.*;

/**
 * Phase 2CP: removes the trailing {@code int} that Skidfuscator's interprocedural pass adds to methods only called from
 * inside the jar (the caller's constant is the method's seed key). After the flow pass the parameter is never read, so:
 * <ul>
 *   <li>every declaration of the method in the jar (overrides included) gets the original descriptor back, its locals
 *   above the parameter move down one slot, and its parameter annotations and names lose the extra entry;</li>
 *   <li>every call drops the literal it pushed for the parameter;</li>
 *   <li>a name Skidfuscator suffixed with {@code $<number>} to avoid a collision gets its base name back when that is free.</li>
 * </ul>
 * A method is left alone when the parameter is still read, a call passes a computed value, a method handle names it, or the
 * new descriptor would collide with a method of its class hierarchy. Calls that Skidfuscator rewrote to a descriptor no
 * method has (its own bug, seen as {@code m:(I)} calls to an {@code m()} method) lose the literal as well.
 */
final class ParameterRecovery implements Opcodes {
    int methods, calls, renamed, brokenCalls, skipped;
    /** Up to 20 groups left alone and why. */
    final List<String> kept = new ArrayList<>();
    private void keep(String what, int count) { skipped += count; if (kept.size() < 20) kept.add(what); }

    void apply(Map<String,ClassNode> nodes, SkidModel model, Set<String> changed) {
        var handles = handleTargets(nodes);
        // Candidate declarations: seed parameters (found before the flow pass) that nothing reads any more.
        var candidates = new LinkedHashMap<String,List<MethodNode>>(); // group key name+desc -> declarations
        var owners = new IdentityHashMap<MethodNode,String>();
        for (var node : nodes.values()) {
            for (var method : node.methods) {
                var parameter = model.seedParameters.get(SkidModel.key(node.name, method.name, method.desc));
                if (parameter == null) continue;
                int slot = parameter.keySet().iterator().next();
                if (reads(method, slot)) { keep(node.name + "." + method.name + method.desc + ": parameter still read", 1); continue; }
                if (handles.contains(SkidModel.key(node.name, method.name, method.desc))) { keep(node.name + "." + method.name + method.desc + ": method handle", 1); continue; }
                candidates.computeIfAbsent(SkidModel.key(node.name, method.name, method.desc), k -> new ArrayList<>()).add(method);
                owners.put(method, node.name);
            }
        }
        for (var entry : candidates.entrySet()) {
            var group = entry.getValue();
            String name = group.getFirst().name, desc = group.getFirst().desc;
            String newDesc = dropLastInt(desc);
            // A seed parameter never overrides (SkidModel); no subclass in the jar may override it either.
            String owner0 = owners.get(group.getFirst());
            boolean complete = true;
            var first = group.getFirst();
            if (!first.name.startsWith("<") && (first.access & (ACC_STATIC | ACC_PRIVATE)) == 0)
                for (var node : nodes.values())
                    if (subclass(node, owner0, nodes))
                        for (var method : node.methods) if (method.name.equals(name) && method.desc.equals(desc)) complete = false;
            if (!complete) { keep(name + desc + ": another declaration keeps the parameter", group.size()); continue; }
            // Every call to the group passes a literal right before the call.
            var sites = new ArrayList<MethodInsnNode>();
            var siteMethods = new IdentityHashMap<MethodInsnNode,MethodNode>();
            boolean literalOnly = true;
            for (var node : nodes.values())
                for (var method : node.methods)
                    for (var insn : method.instructions)
                        if (insn instanceof MethodInsnNode call && call.name.equals(name) && call.desc.equals(desc)
                            && owner0.equals(model.declaring(call.owner, call.name, call.desc))) {
                            if (!literalArgument(call)) literalOnly = false;
                            sites.add(call); siteMethods.put(call, method);
                        }
            if (!literalOnly) { keep(name + desc + ": a call passes a computed value", group.size()); continue; }
            // The new name and descriptor must be free in every class that declares the method and in their super classes.
            String newName = baseName(name, newDesc, group, owners, nodes, model);
            if (newName == null) { keep(name + desc + ": the shorter descriptor collides", group.size()); continue; }
            for (var method : group) {
                String owner = owners.get(method);
                rewriteDeclaration(method, model.seedParameters.get(SkidModel.key(owner, method.name, method.desc)).keySet().iterator().next());
                method.desc = newDesc;
                if (!newName.equals(method.name)) { method.name = newName; renamed++; }
                changed.add(owner);
                methods++;
            }
            for (var call : sites) {
                var push = SeedInterpreter.previous(call);
                siteMethods.get(call).instructions.remove(push);
                call.desc = newDesc; call.name = newName;
                calls++;
            }
            for (var node : nodes.values()) for (var method : node.methods) if (sites.stream().anyMatch(method.instructions::contains)) changed.add(node.name);
        }
        // Skidfuscator's own broken calls: m:(...I) on a class whose hierarchy declares only m:(...).
        for (var node : nodes.values())
            for (var method : node.methods)
                for (var insn : method.instructions.toArray()) {
                    if (!(insn instanceof MethodInsnNode call) || !call.desc.contains("I)") || model.declaring(call.owner, call.name, call.desc) != null
                        || !nodes.containsKey(call.owner) || !literalArgument(call)) continue;
                    String shorter = dropLastInt(call.desc);
                    if (shorter == null || model.declaring(call.owner, call.name, shorter) == null) continue;
                    method.instructions.remove(SeedInterpreter.previous(call));
                    call.desc = shorter;
                    brokenCalls++; changed.add(node.name);
                }
    }

    /** The descriptor without its last parameter when that is an int; null otherwise. */
    static String dropLastInt(String desc) {
        var args = Type.getArgumentTypes(desc);
        if (args.length == 0 || args[args.length - 1].getSort() != Type.INT) return null;
        return Type.getMethodDescriptor(Type.getReturnType(desc), Arrays.copyOf(args, args.length - 1));
    }

    private static boolean reads(MethodNode method, int slot) {
        for (var insn : method.instructions) {
            if (insn instanceof VarInsnNode var && var.var == slot) return true;
            if (insn instanceof IincInsnNode inc && inc.var == slot) return true;
        }
        return false;
    }

    private static boolean literalArgument(MethodInsnNode call) {
        var push = SeedInterpreter.previous(call);
        if (SkidModel.literal(push) == null) return false;
        for (var node = push.getNext(); node != call; node = node.getNext()) if (node instanceof LabelNode) return false;
        return true;
    }

    private static Set<String> handleTargets(Map<String,ClassNode> nodes) {
        var result = new HashSet<String>();
        for (var node : nodes.values())
            for (var method : node.methods)
                for (var insn : method.instructions) {
                    if (insn instanceof InvokeDynamicInsnNode indy) {
                        for (Object arg : indy.bsmArgs) if (arg instanceof org.objectweb.asm.Handle h) result.add(SkidModel.key(h.getOwner(), h.getName(), h.getDesc()));
                    } else if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof org.objectweb.asm.Handle h) result.add(SkidModel.key(h.getOwner(), h.getName(), h.getDesc()));
                }
        return result;
    }

    /**
     * The name to give the group: the base of a {@code name$<number>} when Skidfuscator suffixed it and the base is free,
     * else the name itself when it is free with the new descriptor; null when neither is.
     */
    private static String baseName(String name, String newDesc, List<MethodNode> group, Map<MethodNode,String> owners,
                                   Map<String,ClassNode> nodes, SkidModel model) {
        int dollar = name.lastIndexOf('$');
        if (dollar > 0 && name.substring(dollar + 1).matches("-?[0-9]{1,10}") && free(name.substring(0, dollar), newDesc, group, owners, nodes))
            return name.substring(0, dollar);
        return free(name, newDesc, group, owners, nodes) ? name : null;
    }

    private static boolean free(String name, String desc, List<MethodNode> group, Map<MethodNode,String> owners, Map<String,ClassNode> nodes) {
        for (var method : group) {
            var seen = new HashSet<String>();
            for (String type = owners.get(method); type != null && seen.add(type); ) {
                var node = nodes.get(type);
                if (node == null) break;
                for (var other : node.methods) if (other.name.equals(name) && other.desc.equals(desc)) return false;
                type = node.superName;
            }
            // Subclasses declaring name+desc would start to override it.
            for (var node : nodes.values()) {
                if (!subclass(node, owners.get(method), nodes)) continue;
                for (var other : node.methods) if (other.name.equals(name) && other.desc.equals(desc)) return false;
            }
        }
        return true;
    }

    private static boolean subclass(ClassNode node, String ancestor, Map<String,ClassNode> nodes) {
        var seen = new HashSet<String>();
        for (String type = node.superName; type != null && seen.add(type); ) {
            if (type.equals(ancestor)) return true;
            var parent = nodes.get(type);
            type = parent == null ? null : parent.superName;
        }
        return false;
    }

    /** Moves locals above {@code slot} down by one and drops the parameter's metadata (also Aidsfuscator's salts, 2CZ). */
    static void rewriteDeclaration(MethodNode method, int slot) {
        for (var insn : method.instructions) {
            if (insn instanceof VarInsnNode var && var.var > slot) var.var--;
            else if (insn instanceof IincInsnNode inc && inc.var > slot) inc.var--;
        }
        if (method.localVariables != null) {
            method.localVariables.removeIf(local -> local.index == slot);
            for (var local : method.localVariables) if (local.index > slot) local.index--;
        }
        int count = Type.getArgumentTypes(method.desc).length;
        if (method.visibleParameterAnnotations != null && method.visibleParameterAnnotations.length == count)
            method.visibleParameterAnnotations = Arrays.copyOf(method.visibleParameterAnnotations, count - 1);
        if (method.invisibleParameterAnnotations != null && method.invisibleParameterAnnotations.length == count)
            method.invisibleParameterAnnotations = Arrays.copyOf(method.invisibleParameterAnnotations, count - 1);
        if (method.visibleAnnotableParameterCount == count) method.visibleAnnotableParameterCount--;
        if (method.invisibleAnnotableParameterCount == count) method.invisibleAnnotableParameterCount--;
        if (method.parameters != null && method.parameters.size() == count) method.parameters.removeLast();
        method.maxLocals = Math.max(0, method.maxLocals - 1);
    }
}
