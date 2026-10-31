package dev.classrevive.dex;

import java.util.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.*;

/**
 * Phase 2CW: two traits of Dalvik bytecode that javac never produces, undone in the converted class files so Java
 * decompilers read them as they read javac's output. Runs in {@link DexWorker}; semantics are unchanged.
 * <ul>
 * <li><b>Registers reused for unrelated values.</b> dx and d8 put new values, often of another type, in a register whose
 * value is dead, parameters included ({@code object = new Note(...)} over a {@code String} parameter). dex2jar keeps one
 * local slot per register, so a decompiler sees one variable with two types and writes code that does not compile. Each
 * slot is split into its webs (the stores linked by the loads they reach, found with ASM's source analysis), and every web
 * but one moves to a slot of its own. A parameter keeps the web of its incoming value.</li>
 * <li><b>Fields lose ACC_SYNTHETIC</b> (synthetic methods keep it). javac's own synthetic fields are recognized by name
 * and type and get it back: {@code this$N} and {@code val$name} in inner, local and anonymous classes (otherwise decompilers
 * print them and the constructor that sets them), an enum's {@code $VALUES}, {@code $assertionsDisabled} and
 * {@code $SwitchMap$...} tables.</li>
 * <li><b>Anonymous and local classes created outside their method</b> (Phase 2DA). R8 inlines a method together with its
 * {@code new Outer$m$1(...)}, so the class is created in other methods, or other classes, than the one its
 * EnclosingMethod names. A decompiler can declare such a class in one place only and writes the others as
 * {@code new /* invalid duplicate definition of identical inner class * /} or {@code new 1(...)}. These classes lose their
 * anonymous/local marking (their own InnerClasses entry, EnclosingMethod, and the entries naming them elsewhere), so
 * decompilers write them as top-level classes named {@code Outer$m$1} and every creation as a plain {@code new}.</li>
 * <li><b>Synthetic methods called from other classes</b> (Phase 2DA). R8 turns a lambda into a class of its own
 * ({@code Outer$$ExternalSyntheticLambda0}) that calls a synthetic static method {@code Outer.$r8$lambda$...}. Decompilers
 * hide synthetic methods, so the call has nothing to call. A synthetic method called from a class outside its owner's nest
 * (a different outermost class) is no longer marked synthetic.</li>
 * </ul>
 */
final class DexNormalizer {
    private DexNormalizer() {}

    static final int MAX_INSTRUCTIONS = 50_000;

    static byte[] normalize(byte[] bytes, int[] counts) { return normalize(bytes, counts, Set.of(), Set.of()); }

    static byte[] normalize(byte[] bytes, int[] counts, Set<String> demoted) { return normalize(bytes, counts, demoted, Set.of()); }

    /**
     * {@code counts[0]} gains the methods whose locals were split, {@code counts[1]} the fields given ACC_SYNTHETIC,
     * {@code counts[2]} (when present) the classes that stop being anonymous or local, from {@link #createdElsewhere}.
     */
    /** {@code calledAcross}: {@code owner.name(desc)} of synthetic methods to unmark, from {@link #calledAcrossNests}; counted in counts[3]. */
    static byte[] normalize(byte[] bytes, int[] counts, Set<String> demoted, Set<String> calledAcross) {
        var node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        int methods = 0, fields = 0;
        boolean unmarked = false;
        for (var method : node.methods)
            if ((method.access & Opcodes.ACC_SYNTHETIC) != 0 && calledAcross.contains(node.name + "." + method.name + method.desc)) {
                method.access &= ~Opcodes.ACC_SYNTHETIC;
                unmarked = true;
                if (counts.length > 3) counts[3]++;
            }
        if (!demoted.isEmpty()) {
            if (demoted.contains(node.name)) {
                node.outerClass = null; node.outerMethod = null; node.outerMethodDesc = null;
                unmarked = true;
            }
            unmarked |= node.innerClasses.removeIf(inner -> demoted.contains(inner.name));
        }
        for (var method : node.methods) if (splitWebs(node.name, method)) methods++;
        boolean inner = inner(node), isEnum = (node.access & Opcodes.ACC_ENUM) != 0;
        for (var field : node.fields) {
            if ((field.access & Opcodes.ACC_SYNTHETIC) != 0 || (field.access & Opcodes.ACC_FINAL) == 0) continue;
            boolean isStatic = (field.access & Opcodes.ACC_STATIC) != 0;
            boolean javacSynthetic = !isStatic && inner && (field.name.matches("this\\$\\d+") || field.name.startsWith("val$"))
                || isStatic && isEnum && field.name.equals("$VALUES") && field.desc.equals("[L" + node.name + ";")
                || isStatic && field.name.equals("$assertionsDisabled") && field.desc.equals("Z")
                || isStatic && field.name.startsWith("$SwitchMap$") && field.desc.equals("[I");
            if (javacSynthetic) { field.access |= Opcodes.ACC_SYNTHETIC; fields++; }
        }
        if (methods == 0 && fields == 0 && !unmarked) return bytes;
        counts[0] += methods;
        counts[1] += fields;
        if (demoted.contains(node.name) && counts.length > 2) counts[2]++;
        // No frames: the class files dex2jar writes are version 50, which the JVM verifies without a StackMapTable.
        var writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return writer.toByteArray();
    }

    /**
     * The anonymous and local classes Java source could not have written where they are used: created anywhere but in the
     * method their EnclosingMethod names (or, without a method, in their outer class's constructors and initializer), or
     * created in more than one method, or named by any other code. R8 does all three: it inlines a method together with its
     * {@code new Outer$m$1(...)}, its lambda classes ({@code $$ExternalSyntheticLambda0}) take an anonymous class as a field
     * and constructor parameter, and it strengthens field and parameter types to anonymous classes. A decompiler then writes
     * {@code Outer.1}, which is no Java. Classes nested in the anonymous class itself may name it, as javac's do.
     */
    static Set<String> createdElsewhere(Map<String, byte[]> classes) {
        var nodes = new ArrayList<ClassNode>();
        var enclosing = new HashMap<String, String>();   // anonymous or local class -> "Owner.method(desc)" or "Owner."
        var outer = new HashMap<String, String>();       // any nested class -> its outer class
        for (byte[] bytes : classes.values()) {
            var node = new ClassNode();
            try { new ClassReader(bytes).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES); } catch (RuntimeException unreadable) { continue; }
            nodes.add(node);
            if (node.outerClass != null) outer.put(node.name, node.outerClass);
            for (var inner : node.innerClasses) {
                if (!inner.name.equals(node.name)) continue;
                if (inner.outerName != null) outer.put(node.name, inner.outerName);
                else if (node.outerClass != null)
                    enclosing.put(node.name, node.outerClass + "." + (node.outerMethod == null ? "" : node.outerMethod + node.outerMethodDesc));
            }
        }
        var demoted = new TreeSet<String>();
        var sites = new HashMap<String, Set<String>>();
        for (var node : nodes) {
            // The classes this one may name freely: itself and the classes it is nested in.
            var chain = new HashSet<String>();
            for (String at = node.name; at != null && chain.add(at); ) at = outer.get(at);
            java.util.function.Consumer<String> named = type -> {
                if (type != null && enclosing.containsKey(type) && !chain.contains(type)) demoted.add(type);
            };
            for (var field : node.fields) descriptor(field.desc, named);
            for (var method : node.methods) {
                descriptor(method.desc, named);
                String site = node.name + "." + method.name + method.desc;
                for (var insn : method.instructions) {
                    String type = null, desc = null;
                    if (insn instanceof TypeInsnNode t) {
                        type = t.desc.startsWith("[") ? null : t.desc;
                        if (t.desc.startsWith("[")) desc = t.desc;
                        if (t.getOpcode() == Opcodes.NEW) sites.computeIfAbsent(t.desc, key -> new HashSet<>()).add(site);
                    } else if (insn instanceof MethodInsnNode m) { type = m.owner; desc = m.desc; }
                    else if (insn instanceof FieldInsnNode f) { type = f.owner; desc = f.desc; }
                    else if (insn instanceof LdcInsnNode l && l.cst instanceof Type t) desc = t.getDescriptor();
                    else if (insn instanceof MultiANewArrayInsnNode a) desc = a.desc;
                    else if (insn instanceof InvokeDynamicInsnNode d) desc = d.desc;
                    // The method an anonymous class belongs to creates it and may call it: that is javac's own code.
                    String home = type == null ? null : enclosing.get(type);
                    boolean ownMethod = home != null && (home.endsWith(".") ? site.startsWith(home + "<init>(") || site.startsWith(home + "<clinit>(")
                        : site.equals(home));
                    if (!ownMethod) named.accept(type);
                    if (desc != null) descriptor(desc, named);
                }
            }
        }
        for (var entry : enclosing.entrySet()) {
            var created = sites.getOrDefault(entry.getKey(), Set.of());
            String home = entry.getValue();
            boolean inPlace = home.endsWith(".")
                ? created.stream().allMatch(site -> site.startsWith(home + "<init>(") || site.startsWith(home + "<clinit>("))
                : created.size() <= 1 && (created.isEmpty() || created.contains(home));
            if (!inPlace) demoted.add(entry.getKey());
        }
        return demoted;
    }

    /** The synthetic methods called from a class outside their owner's nest: {@code owner.name(desc)}. */
    static Set<String> calledAcrossNests(Map<String, byte[]> classes) {
        var outer = new HashMap<String, String>();
        var synthetic = new HashSet<String>();
        var nodes = new ArrayList<ClassNode>();
        for (byte[] bytes : classes.values()) {
            var node = new ClassNode();
            try { new ClassReader(bytes).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES); } catch (RuntimeException unreadable) { continue; }
            nodes.add(node);
            if (node.outerClass != null) outer.put(node.name, node.outerClass);
            for (var inner : node.innerClasses) if (inner.name.equals(node.name) && inner.outerName != null) outer.put(node.name, inner.outerName);
            for (var method : node.methods) if ((method.access & Opcodes.ACC_SYNTHETIC) != 0) synthetic.add(node.name + "." + method.name + method.desc);
        }
        java.util.function.Function<String, String> root = name -> {
            var seen = new HashSet<String>();
            String at = name;
            while (outer.containsKey(at) && seen.add(at)) at = outer.get(at);
            return at;
        };
        var across = new TreeSet<String>();
        for (var node : nodes) {
            String nest = root.apply(node.name);
            for (var method : node.methods)
                for (var insn : method.instructions)
                    if (insn instanceof MethodInsnNode call && !call.owner.equals(node.name) && synthetic.contains(call.owner + "." + call.name + call.desc)
                            && !root.apply(call.owner).equals(nest)) across.add(call.owner + "." + call.name + call.desc);
        }
        return across;
    }

    /** Every class name in a field or method descriptor. */
    private static void descriptor(String desc, java.util.function.Consumer<String> named) {
        for (int at = desc.indexOf('L'); at >= 0; at = desc.indexOf('L', at + 1)) {
            if (at > 0 && "([)VZBCSIJFDL;".indexOf(desc.charAt(at - 1)) < 0) continue;
            int end = desc.indexOf(';', at);
            if (end < 0) return;
            named.accept(desc.substring(at + 1, end));
            at = end;
        }
    }

    /** A class listed in its own InnerClasses entry as a member that is not static, or as local or anonymous. */
    private static boolean inner(ClassNode node) {
        for (var inner : node.innerClasses)
            if (inner.name.equals(node.name)) return inner.outerName == null || (inner.access & Opcodes.ACC_STATIC) == 0;
        return false;
    }

    static boolean splitWebs(String owner, MethodNode method) {
        if (method.instructions.size() == 0 || method.instructions.size() > MAX_INSTRUCTIONS) return false;
        // Each parameter's incoming value becomes an explicit store, so it is a definition like the others.
        var entry = new InsnList();
        var entryStores = new HashMap<Integer, VarInsnNode>();
        int slot = 0;
        var parameters = new ArrayList<Type>();
        if ((method.access & Opcodes.ACC_STATIC) == 0) parameters.add(Type.getObjectType(owner));
        parameters.addAll(Arrays.asList(Type.getArgumentTypes(method.desc)));
        for (Type type : parameters) {
            var store = new VarInsnNode(type.getOpcode(Opcodes.ISTORE), slot);
            entry.add(new VarInsnNode(type.getOpcode(Opcodes.ILOAD), slot));
            entry.add(store);
            entryStores.put(slot, store);
            slot += type.getSize();
        }
        method.instructions.insert(entry);
        try {
            return split(owner, method, entryStores);
        } finally {
            // The identity copies (load p, store p) are only analysis aids: drop the ones still writing their own slot.
            for (var store : entryStores.values()) {
                var load = (VarInsnNode) store.getPrevious();
                if (load.var == store.var) { method.instructions.remove(load); method.instructions.remove(store); }
            }
        }
    }

    private static boolean split(String owner, MethodNode method, Map<Integer, VarInsnNode> entryStores) {
        Frame<SourceValue>[] frames;
        try {
            frames = new Analyzer<>(new SourceInterpreter()).analyze(owner, method);
        } catch (AnalyzerException | RuntimeException unanalyzable) {
            return false;
        }
        var insns = method.instructions.toArray();
        var index = new IdentityHashMap<AbstractInsnNode, Integer>();
        for (int i = 0; i < insns.length; i++) index.put(insns[i], i);
        int[] parent = new int[insns.length];
        for (int i = 0; i < parent.length; i++) parent[i] = i;
        var unsafe = new HashSet<Integer>();           // slots read with no store reaching them: left alone
        var useSlot = new HashMap<Integer, Integer>(); // load or iinc -> its slot
        var entryLoads = Collections.newSetFromMap(new IdentityHashMap<AbstractInsnNode, Boolean>());
        for (var store : entryStores.values()) entryLoads.add(store.getPrevious());
        for (int i = 0; i < insns.length; i++) {
            int var = load(insns[i]);
            if (var < 0 || frames[i] == null || entryLoads.contains(insns[i])) continue;
            if (var >= frames[i].getLocals()) { unsafe.add(var); continue; }
            var sources = frames[i].getLocal(var).insns;
            if (sources.isEmpty()) { unsafe.add(var); continue; }
            useSlot.put(i, var);
            for (var source : sources) {
                Integer at = index.get(source);
                if (at == null || store(source) != var) { unsafe.add(var); continue; }
                union(parent, i, at);
            }
        }
        // Webs per slot: the stores (and increments) grouped by their root, in instruction order.
        var webs = new TreeMap<Integer, LinkedHashMap<Integer, List<Integer>>>();
        for (int i = 0; i < insns.length; i++) {
            int var = store(insns[i]);
            if (var < 0 || frames[i] == null && !(insns[i] == entryStores.get(var))) continue;
            webs.computeIfAbsent(var, key -> new LinkedHashMap<>()).computeIfAbsent(find(parent, i), key -> new ArrayList<>()).add(i);
        }
        int next = method.maxLocals;
        var moved = new HashMap<Integer, Integer>(); // web root -> new slot
        for (var bySlot : webs.entrySet()) {
            int var = bySlot.getKey();
            if (unsafe.contains(var) || bySlot.getValue().size() < 2) continue;
            var entryStore = entryStores.get(var);
            int keep = entryStore != null ? find(parent, index.get(entryStore)) : bySlot.getValue().keySet().iterator().next();
            for (var web : bySlot.getValue().entrySet()) {
                if (web.getKey() == keep) continue;
                boolean wide = web.getValue().stream().anyMatch(i -> insns[i].getOpcode() == Opcodes.LSTORE || insns[i].getOpcode() == Opcodes.DSTORE);
                moved.put(web.getKey(), next);
                next += wide ? 2 : 1;
            }
        }
        if (moved.isEmpty()) return false;
        for (int i = 0; i < insns.length; i++) {
            boolean definition = store(insns[i]) >= 0, use = useSlot.containsKey(i);
            if (!definition && !use) continue;
            Integer target = moved.get(find(parent, i));
            if (target == null) continue;
            if (insns[i] instanceof VarInsnNode variable) variable.var = target;
            else if (insns[i] instanceof IincInsnNode increment) increment.var = target;
        }
        if (method.localVariables != null) method.localVariables.clear();
        method.maxLocals = next;
        return true;
    }

    /** The slot a load or increment reads, or -1. */
    private static int load(AbstractInsnNode insn) {
        if (insn instanceof VarInsnNode variable && variable.getOpcode() >= Opcodes.ILOAD && variable.getOpcode() <= Opcodes.ALOAD) return variable.var;
        if (insn instanceof IincInsnNode increment) return increment.var;
        return -1;
    }

    /** The slot a store or increment writes, or -1. */
    private static int store(AbstractInsnNode insn) {
        if (insn instanceof VarInsnNode variable && variable.getOpcode() >= Opcodes.ISTORE && variable.getOpcode() <= Opcodes.ASTORE) return variable.var;
        if (insn instanceof IincInsnNode increment) return increment.var;
        return -1;
    }

    private static int find(int[] parent, int i) {
        while (parent[i] != i) { parent[i] = parent[parent[i]]; i = parent[i]; }
        return i;
    }

    private static void union(int[] parent, int a, int b) { parent[find(parent, a)] = find(parent, b); }
}
