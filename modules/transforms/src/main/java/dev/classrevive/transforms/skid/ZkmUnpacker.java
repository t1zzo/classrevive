package dev.classrevive.transforms.skid;

import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.*;
import java.util.*;
import java.util.function.Function;

/**
 * Phase 2DM: Zelix KlassMaster 27 packs the parameters of a method, and its key, into one {@code Object[]}: the method
 * reads {@code ((Integer) args[k]).intValue()}, {@code (T) args[k]}..., and its callers build the array with
 * {@code new Object[n]} and {@code args[k] = Integer.valueOf(x)}. Methods that differ only by their result then share the
 * name and {@code (Object[])} parameters, which no Java source can declare. This puts the parameters back: one per element,
 * typed by the method's reads (an unboxed primitive, a cast type, or {@code Object}), with the elements the method never
 * uses (its key, once decrypted code went) left out. Callers pass the values directly, unboxed where the method unboxed.
 *
 * <p>Only exact shapes are rewritten: the array never escapes the method (it is only loaded, duplicated, read at constant
 * indices and popped); every caller in the jar creates it with {@code anewarray}, stores each index once in order and
 * passes it straight on; no other class of the hierarchy declares the name with either descriptor; no method handle names
 * it. Each rewritten method and caller is checked by the JDK's verifier analysis, and a method is left packed as a whole
 * when any of its parts fails. Nothing is executed.
 */
final class ZkmUnpacker implements Opcodes {
    private static final String PACKED = "([Ljava/lang/Object;)";
    private static final Map<String,Type> UNBOXED = Map.of(
        "java/lang/Integer", Type.INT_TYPE, "java/lang/Long", Type.LONG_TYPE, "java/lang/Boolean", Type.BOOLEAN_TYPE,
        "java/lang/Character", Type.CHAR_TYPE, "java/lang/Short", Type.SHORT_TYPE, "java/lang/Byte", Type.BYTE_TYPE,
        "java/lang/Float", Type.FLOAT_TYPE, "java/lang/Double", Type.DOUBLE_TYPE);

    private final Map<String,ClassNode> nodes;
    private final Function<String,ClassNode> header;
    private final Set<String> pureStatics;
    int unpacked, callsRewritten, keysDropped;
    final List<String> refused = new ArrayList<>();

    /** {@code pureStatics}: static fields whose read the dead-code clean-up may drop (the class keys, {@code owner.name:desc}). */
    ZkmUnpacker(Map<String,ClassNode> nodes, Function<String,ClassNode> header, Set<String> pureStatics) {
        this.nodes = nodes; this.header = header; this.pureStatics = pureStatics;
    }

    /** One read of the array: the index pushed, the {@code aaload}, and the last instruction of its cast and unboxing. */
    private record Read(int index, AbstractInsnNode push, AbstractInsnNode load, AbstractInsnNode end, Type type) {}
    /** The array's own instructions in a method: what pushes or copies it, and what pops it. */
    private record Body(List<AbstractInsnNode> tokens, List<AbstractInsnNode> pops, List<Read> reads) {}
    /** One caller: its {@code anewarray} and size push, its copies, and each element's store with its index push. */
    private record Store(int index, AbstractInsnNode push, AbstractInsnNode store, AbstractInsnNode value) {}
    /** A call to rewrite: where it is and how many elements its array has. */
    private sealed interface Caller permits Site, Juggled {
        ClassNode owner(); MethodNode method(); int length();
    }
    private record Site(ClassNode owner, MethodNode method, MethodInsnNode call, AbstractInsnNode size, AbstractInsnNode creation,
                        List<AbstractInsnNode> copies, List<Store> stores) implements Caller {
        public int length() { return stores.size(); }
    }
    /**
     * A call whose array was built by stack shuffling over values pushed before it: the instructions from the array's size
     * to the call, how many stack values lie below the ones it took, the kinds of all of them (bottom to top), and each
     * element's source: an {@link Original} stack value or a {@link Constant} pushed in between, with the primitive a
     * {@code valueOf} boxed.
     */
    private record Juggled(ClassNode owner, MethodNode method, MethodInsnNode call, List<AbstractInsnNode> range, int below,
                           List<BasicValue> kinds, Object[] elements, Type[] boxed, String[] casts, TypeInsnNode receiverCast,
                           List<AbstractInsnNode> pushedBelow) implements Caller {
        public int length() { return elements.length; }
    }
    private record Original(int position) {}
    private record Constant(AbstractInsnNode insn) {}
    private record Boxed(Object inner, Type primitive) {}
    private record Cast(Object inner, String type) {}
    private static final Object ARRAY = new Object();

    /** A packed method that can be unpacked: its array's uses, its callers and each element's type (null: never read). */
    private record Plan(String name, ClassNode node, MethodNode method, int slot, Body body, List<Caller> sites, Type[] types, Set<String> related) {
        String key(int k) { return name + "#" + k; }
    }

    private static final int MAX_ATTEMPTS = 64;

    /**
     * Unpacks every method of the jar it can; returns the classes changed. The keys are left out together: a method's key
     * usually feeds only the keys it passes to the methods it calls, so each long element is first assumed unused. After
     * the rewrite and the dead-code clean-up, an element some code still reads (its stand-in local is loaded) is kept and
     * everything is done again; so is a method whose rewritten code does not verify, left packed.
     */
    Set<String> apply() {
        var keep = new HashSet<String>();
        var excluded = new HashSet<MethodNode>();
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            var plans = new ArrayList<Plan>();
            var reasons = new LinkedHashMap<String,String>();
            for (var node : List.copyOf(nodes.values()))
                for (var method : List.copyOf(node.methods))
                    if (method.desc.startsWith(PACKED) && (method.access & (ACC_ABSTRACT | ACC_NATIVE)) == 0 && method.instructions.size() > 0) {
                        String name = node.name + "." + method.name + method.desc;
                        if (excluded.contains(method)) { reasons.put(name, "the rewritten code does not verify"); continue; }
                        var plan = plan(node, method, reasons);
                        if (plan != null) plans.add(plan);
                    }
            // The descriptors: the elements read, but the long ones (the key) only when they are known to be needed.
            var kept = new IdentityHashMap<Plan,boolean[]>();
            var descs = new LinkedHashMap<Plan,String>();
            for (var it = plans.iterator(); it.hasNext(); ) {
                var plan = it.next();
                var flags = new boolean[plan.types().length];
                for (int k = 0; k < flags.length; k++) flags[k] = plan.types()[k] != null && (!plan.types()[k].equals(Type.LONG_TYPE) || keep.contains(plan.key(k)));
                String desc = descriptor(plan.types(), flags, plan.method().desc);
                String params = desc.substring(0, desc.indexOf(')') + 1);
                // Two unpacked methods of related classes with one name and the same parameters would collide.
                boolean clash = false;
                for (var other : descs.entrySet()) {
                    String otherParams = other.getValue().substring(0, other.getValue().indexOf(')') + 1);
                    if (other.getKey().method().name.equals(plan.method().name) && otherParams.equals(params) && plan.related().contains(other.getKey().node().name)) clash = true;
                }
                for (String type : plan.related()) if (declaresParameters(type, plan.method().name, params)) clash = true;
                for (String type : plan.related())
                    if (!type.equals(plan.node().name) && declares(type, plan.method().name, plan.method().desc)) {
                        reasons.put(plan.node().name + "." + plan.method().name + plan.method().desc, "overridden or overriding in " + type);
                        clash = true;
                    }
                if (clash) {
                    reasons.putIfAbsent(plan.node().name + "." + plan.method().name + plan.method().desc, "the unpacked parameters " + params + " are taken");
                    it.remove();
                    continue;
                }
                kept.put(plan, flags);
                descs.put(plan, desc);
            }
            if (plans.isEmpty()) { report(reasons); return Set.of(); }

            // Apply all: bodies first (their locals move), then callers; a snapshot of every method touched.
            var touched = new LinkedHashMap<MethodNode,ClassNode>();
            for (var plan : plans) {
                touched.put(plan.method(), plan.node());
                for (var site : plan.sites()) touched.put(site.method(), site.owner());
            }
            var snapshots = new IdentityHashMap<MethodNode,Snapshot>();
            touched.keySet().forEach(method -> snapshots.put(method, new Snapshot(method)));
            var markers = new IdentityHashMap<Plan,int[]>();
            for (var plan : plans) {
                markers.put(plan, rewriteBody(plan.method(), plan.slot(), plan.body(), plan.types(), kept.get(plan)));
                plan.method().desc = descs.get(plan);
            }
            var failed = new HashSet<MethodNode>();
            for (var plan : plans)
                for (var site : plan.sites()) {
                    boolean ok = site instanceof Site inPlace ? rewriteSite(inPlace, plan.types(), kept.get(plan), descs.get(plan))
                        : rewriteJuggled((Juggled) site, plan.types(), kept.get(plan), descs.get(plan));
                    if (!ok) failed.add(plan.method());
                }
            touched.forEach((method, owner) -> PureDeadCode.clean(owner.name, method, pureStatics));
            // A left-out key some code still reads: kept next time.
            var needed = new ArrayList<String>();
            for (var plan : plans) {
                var loaded = loadedSlots(plan.method());
                var marker = markers.get(plan);
                for (int k = 0; k < marker.length; k++) if (marker[k] >= 0 && loaded.contains(marker[k])) needed.add(plan.key(k));
            }
            if (failed.isEmpty() && needed.isEmpty())
                for (var plan : plans) {
                    if (!verifies(plan.node().name, plan.method())) failed.add(plan.method());
                    for (var site : plan.sites()) if (!verifies(site.owner().name, site.method())) failed.add(plan.method());
                }
            if (needed.isEmpty() && failed.isEmpty()) {
                var changed = new LinkedHashSet<String>();
                for (var plan : plans) {
                    unpacked++;
                    callsRewritten += plan.sites().size();
                    var flags = kept.get(plan);
                    for (int k = 0; k < flags.length; k++) if (!flags[k]) keysDropped++;
                }
                touched.values().forEach(owner -> changed.add(owner.name));
                report(reasons);
                return changed;
            }
            snapshots.values().forEach(Snapshot::restore);
            keep.addAll(needed);
            excluded.addAll(failed);
        }
        refused.add("gave up after " + MAX_ATTEMPTS + " attempts");
        return Set.of();
    }

    private void report(Map<String,String> reasons) {
        reasons.forEach((name, why) -> { if (refused.size() < 20) refused.add(name + ": " + why); });
    }

    /** What unpacking a method needs, from the code as it is; null (with the reason) when it cannot be. */
    private Plan plan(ClassNode node, MethodNode method, Map<String,String> reasons) {
        String name = node.name + "." + method.name + method.desc;
        int slot = (method.access & ACC_STATIC) != 0 ? 0 : 1;
        var body = body(node.name, method, slot);
        if (body == null) { reasons.put(name, "the array escapes"); return null; }
        var sites = new ArrayList<Caller>();
        var related = related(node.name);
        for (var owner : nodes.values())
            for (var caller : owner.methods)
                for (var insn : caller.instructions) {
                    if (insn instanceof MethodInsnNode call && call.name.equals(method.name) && call.desc.equals(method.desc) && related.contains(call.owner)) {
                        if (caller == method) { reasons.put(name, "recursive"); return null; }
                        Caller site = site(owner, caller, call);
                        if (site == null) site = juggled(owner, caller, call);
                        if (site == null) { reasons.put(name, "a caller in " + owner.name + "." + caller.name + " does not build the array in place (" + WHY.get() + ")"); return null; }
                        sites.add(site);
                    }
                    if (insn instanceof InvokeDynamicInsnNode indy && mentions(indy, method)) { reasons.put(name, "a method handle names it"); return null; }
                }
        if (sites.isEmpty()) { reasons.put(name, "no caller"); return null; }
        int size = sites.getFirst().length();
        for (var site : sites) if (site.length() != size) { reasons.put(name, "callers pass arrays of different lengths"); return null; }
        var types = new Type[size];
        for (var read : body.reads()) {
            if (read.index() >= size) { reasons.put(name, "a read past the arrays callers pass"); return null; }
            if (types[read.index()] != null && !types[read.index()].equals(read.type())) { reasons.put(name, "element " + read.index() + " read as two types"); return null; }
            types[read.index()] = read.type();
        }
        return new Plan(name, node, method, slot, body, sites, types, related);
    }

    /** A method's instructions, try blocks, frame sizes and the fields the rewrite changes in place, to put back. */
    private static final class Snapshot {
        private final MethodNode method;
        private final String desc;
        private final AbstractInsnNode[] insns;
        private final List<TryCatchBlockNode> blocks;
        private final List<LocalVariableNode> locals;
        private final int maxLocals, maxStack;
        private final Map<AbstractInsnNode,Object> fields = new IdentityHashMap<>();

        Snapshot(MethodNode method) {
            this.method = method;
            desc = method.desc;
            insns = method.instructions.toArray();
            blocks = new ArrayList<>(method.tryCatchBlocks);
            locals = method.localVariables == null ? null : new ArrayList<>(method.localVariables);
            maxLocals = method.maxLocals; maxStack = method.maxStack;
            for (var insn : insns) {
                if (insn instanceof VarInsnNode var) fields.put(insn, var.var);
                if (insn instanceof IincInsnNode inc) fields.put(insn, inc.var);
                if (insn instanceof MethodInsnNode call) fields.put(insn, call.desc);
            }
        }

        void restore() {
            method.instructions.clear();
            for (var insn : insns) method.instructions.add(insn);
            fields.forEach((insn, value) -> {
                if (insn instanceof VarInsnNode var) var.var = (Integer) value;
                if (insn instanceof IincInsnNode inc) inc.var = (Integer) value;
                if (insn instanceof MethodInsnNode call) call.desc = (String) value;
            });
            method.tryCatchBlocks = new ArrayList<>(blocks);
            method.localVariables = locals == null ? null : new ArrayList<>(locals);
            method.desc = desc;
            method.maxLocals = maxLocals; method.maxStack = maxStack;
        }
    }

    // ---------------------------------------------------------------- the method

    /** The array's pushes, copies, pops and reads in the method; null when it is used any other way. */
    private static Body body(String owner, MethodNode method, int slot) {
        Frame<SourceValue>[] frames;
        try { frames = new Analyzer<>(new SourceInterpreter()).analyze(owner, method); }
        catch (AnalyzerException malformed) { return null; }
        var insns = method.instructions.toArray();
        var tokens = Collections.newSetFromMap(new IdentityHashMap<AbstractInsnNode,Boolean>());
        for (var insn : insns) {
            if (insn instanceof VarInsnNode var && var.var == slot) {
                if (var.getOpcode() != ALOAD) return null; // stored over
                tokens.add(insn);
            }
            if (insn instanceof IincInsnNode inc && inc.var == slot) return null;
        }
        // Copies of the array: a dup whose operand is the array (to a fixed point, dups of dups).
        for (boolean grew = true; grew; ) {
            grew = false;
            for (int i = 0; i < insns.length; i++)
                if (insns[i].getOpcode() == DUP && frames[i] != null && !tokens.contains(insns[i])
                    && token(frames[i].getStack(frames[i].getStackSize() - 1), tokens)) { tokens.add(insns[i]); grew = true; }
        }
        var pops = new ArrayList<AbstractInsnNode>();
        var reads = new ArrayList<Read>();
        for (int i = 0; i < insns.length; i++) {
            var frame = frames[i];
            if (frame == null) continue;
            var insn = insns[i];
            int consumed = PureDeadCode.consumed(insn, frame);
            for (int k = 0; k < consumed && k < frame.getStackSize(); k++) {
                var value = frame.getStack(frame.getStackSize() - 1 - k);
                if (value.insns.stream().noneMatch(tokens::contains)) continue;
                if (!token(value, tokens)) return null; // merged with something else
                if (insn.getOpcode() == DUP && k == 0) continue;
                if (insn.getOpcode() == POP && k == 0) { pops.add(insn); continue; }
                if (insn.getOpcode() == AALOAD && k == 1) {
                    var index = frame.getStack(frame.getStackSize() - 1);
                    if (index.insns.size() != 1) return null;
                    var push = index.insns.iterator().next();
                    Integer constant = SkidModel.literal(push) instanceof Integer n ? n : null;
                    if (constant == null || constant < 0) return null;
                    reads.add(read(constant, push, insn));
                    continue;
                }
                return null;
            }
        }
        return new Body(new ArrayList<>(tokens), pops, reads);
    }

    private static boolean token(SourceValue value, Set<AbstractInsnNode> tokens) {
        return !value.insns.isEmpty() && tokens.containsAll(value.insns);
    }

    /** A read and what it becomes: {@code checkcast Integer; intValue()} an {@code int}, {@code checkcast T} a T, else Object. */
    private static Read read(int index, AbstractInsnNode push, AbstractInsnNode load) {
        var next = SeedInterpreter.next(load);
        if (next instanceof TypeInsnNode cast && cast.getOpcode() == CHECKCAST) {
            Type primitive = UNBOXED.get(cast.desc);
            var after = SeedInterpreter.next(cast);
            if (primitive != null && after instanceof MethodInsnNode call && call.getOpcode() == INVOKEVIRTUAL && call.owner.equals(cast.desc)
                && call.name.equals(primitive.getClassName() + "Value") && call.desc.equals("()" + primitive.getDescriptor()))
                return new Read(index, push, load, call, primitive);
            return new Read(index, push, load, cast, Type.getObjectType(cast.desc));
        }
        return new Read(index, push, load, load, Type.getType(Object.class));
    }

    /**
     * Rewrites the method's code for the parameters: kept elements take the first slots in order, the method's other
     * locals move up, each read loads its parameter. A read of an element left out loads a stand-in local set to the
     * type's default at the start: if dead-code removal does not take that load away, the element is needed after all. Returns the stand-in slots
     * (-1 for elements kept or never read).
     */
    private static int[] rewriteBody(MethodNode method, int slot, Body body, Type[] types, boolean[] kept) {
        var slots = new int[types.length];
        int next = slot;
        for (int k = 0; k < types.length; k++) if (types[k] != null && kept[k]) { slots[k] = next; next += types[k].getSize(); }
        int shift = next - slot - 1;
        for (var insn : method.instructions) {
            if (insn instanceof VarInsnNode var && var.var > slot) var.var += shift;
            if (insn instanceof IincInsnNode inc && inc.var > slot) inc.var += shift;
        }
        method.localVariables = null;
        method.maxLocals = Math.max(freeLocal(method), next);
        var markers = new int[types.length];
        for (int k = 0; k < types.length; k++) {
            markers[k] = -1;
            if (types[k] != null && !kept[k]) { markers[k] = method.maxLocals; method.maxLocals += types[k].getSize(); }
        }
        // The stand-ins hold the type's default from the start, so the analyses see a value of the right size.
        var entry = new InsnList();
        for (int k = 0; k < types.length; k++) {
            if (markers[k] < 0) continue;
            entry.add(switch (types[k].getSort()) {
                case Type.LONG -> new InsnNode(LCONST_0);
                case Type.FLOAT -> new InsnNode(FCONST_0);
                case Type.DOUBLE -> new InsnNode(DCONST_0);
                case Type.OBJECT, Type.ARRAY -> new InsnNode(ACONST_NULL);
                default -> new InsnNode(ICONST_0);
            });
            entry.add(new VarInsnNode(types[k].getOpcode(ISTORE), markers[k]));
        }
        method.instructions.insert(entry);
        for (var read : body.reads()) {
            Type type = types[read.index()];
            int from = kept[read.index()] ? slots[read.index()] : markers[read.index()];
            AbstractInsnNode replacement = new VarInsnNode(type.getOpcode(ILOAD), from);
            for (AbstractInsnNode insn = read.load(); insn != read.end(); ) {
                var following = insn.getNext();
                method.instructions.remove(insn);
                insn = following;
            }
            method.instructions.set(read.end(), replacement);
            method.instructions.remove(read.push());
        }
        for (var insn : body.tokens()) method.instructions.remove(insn);
        for (var insn : body.pops()) method.instructions.remove(insn);
        return markers;
    }

    /** The first local past every one the code uses (the recorded maximum may be stale after earlier rewrites). */
    private static int freeLocal(MethodNode method) {
        int free = method.maxLocals;
        for (var insn : method.instructions) {
            if (insn instanceof VarInsnNode var) free = Math.max(free, var.var + 2);
            if (insn instanceof IincInsnNode inc) free = Math.max(free, inc.var + 1);
        }
        return free;
    }

    private static Set<Integer> loadedSlots(MethodNode method) {
        var loaded = new HashSet<Integer>();
        for (var insn : method.instructions) {
            if (insn instanceof VarInsnNode var && var.getOpcode() >= ILOAD && var.getOpcode() <= ALOAD) loaded.add(var.var);
            if (insn instanceof IincInsnNode inc) loaded.add(inc.var);
        }
        return loaded;
    }

    private static String descriptor(Type[] types, boolean[] kept, String packed) {
        var out = new StringBuilder("(");
        for (int k = 0; k < types.length; k++) if (types[k] != null && kept[k]) out.append(types[k].getDescriptor());
        return out.append(")").append(Type.getReturnType(packed).getDescriptor()).toString();
    }

    // ---------------------------------------------------------------- callers

    /** A call whose array was created by {@code anewarray Object} in the caller and filled index by index, in order. */
    private static Site site(ClassNode owner, MethodNode method, MethodInsnNode call) {
        Frame<SourceValue>[] frames;
        try { frames = new Analyzer<>(new SourceInterpreter()).analyze(owner.name, method); }
        catch (AnalyzerException malformed) { return null; }
        var insns = method.instructions.toArray();
        var index = new IdentityHashMap<AbstractInsnNode,Integer>();
        for (int i = 0; i < insns.length; i++) index.put(insns[i], i);
        var frame = frames[index.get(call)];
        if (frame == null) return null;
        var array = frame.getStack(frame.getStackSize() - 1);
        if (array.insns.size() != 1) return null;
        var source = array.insns.iterator().next();
        // Back through the copies to the creation.
        var copies = new ArrayList<AbstractInsnNode>();
        while (source.getOpcode() == DUP) {
            copies.add(source);
            var before = frames[index.get(source)];
            var operand = before.getStack(before.getStackSize() - 1);
            if (operand.insns.size() != 1) return null;
            source = operand.insns.iterator().next();
        }
        if (!(source instanceof TypeInsnNode creation) || creation.getOpcode() != ANEWARRAY || !creation.desc.equals("java/lang/Object")) return null;
        var creationFrame = frames[index.get(creation)];
        var length = creationFrame.getStack(creationFrame.getStackSize() - 1);
        if (length.insns.size() != 1 || !(SkidModel.literal(length.insns.iterator().next()) instanceof Integer n) || n < 0) return null;
        var sizePush = length.insns.iterator().next();
        // Every use of the array and its copies: dups, stores at constant indices, and this call.
        var tokens = Collections.newSetFromMap(new IdentityHashMap<AbstractInsnNode,Boolean>());
        tokens.add(creation);
        for (boolean grew = true; grew; ) {
            grew = false;
            for (int i = 0; i < insns.length; i++)
                if (insns[i].getOpcode() == DUP && frames[i] != null && !tokens.contains(insns[i])
                    && token(frames[i].getStack(frames[i].getStackSize() - 1), tokens)) { tokens.add(insns[i]); grew = true; }
        }
        var stores = new ArrayList<Store>();
        for (int i = 0; i < insns.length; i++) {
            var at = frames[i];
            if (at == null) continue;
            int consumed = PureDeadCode.consumed(insns[i], at);
            for (int k = 0; k < consumed && k < at.getStackSize(); k++) {
                var value = at.getStack(at.getStackSize() - 1 - k);
                if (value.insns.stream().noneMatch(tokens::contains)) continue;
                if (!token(value, tokens)) return null;
                if (insns[i].getOpcode() == DUP && k == 0) continue;
                if (insns[i] == call && k == 0) continue;
                if (insns[i].getOpcode() == AASTORE && k == 2) {
                    var slot = at.getStack(at.getStackSize() - 2);
                    var stored = at.getStack(at.getStackSize() - 1);
                    if (slot.insns.size() != 1 || stored.insns.size() != 1) return null;
                    var push = slot.insns.iterator().next();
                    if (!(SkidModel.literal(push) instanceof Integer position)) return null;
                    stores.add(new Store(position, push, insns[i], stored.insns.iterator().next()));
                    continue;
                }
                return null;
            }
        }
        // Each index once, in order, all before the call.
        if (stores.size() != n) return null;
        for (int k = 0; k < stores.size(); k++) if (stores.get(k).index() != k || index.get(stores.get(k).store()) > index.get(call)) return null;
        var allCopies = new ArrayList<AbstractInsnNode>();
        for (var token : tokens) if (token != creation) allCopies.add(token);
        return new Site(owner, method, call, sizePush, creation, allCopies, stores);
    }

    private static boolean rewriteSite(Site site, Type[] types, boolean[] kept, String desc) {
        var list = site.method().instructions;
        for (var store : site.stores()) {
            Type type = types[store.index()];
            var conversion = new InsnList();
            int size = 1;
            if (type != null && type.getSort() <= Type.DOUBLE) {
                String box = boxOf(type);
                var before = store.store().getPrevious();
                while (before != null && before.getOpcode() < 0) before = before.getPrevious();
                if (store.value() == before && before instanceof MethodInsnNode valueOf && valueOf.getOpcode() == INVOKESTATIC && valueOf.owner.equals(box)
                    && valueOf.name.equals("valueOf") && valueOf.desc.equals("(" + type.getDescriptor() + ")L" + box + ";")) {
                    list.remove(valueOf);
                } else if (store.value() instanceof TypeInsnNode created && created.getOpcode() == NEW && created.desc.equals(box)
                    && SeedInterpreter.next(created) instanceof InsnNode dup && dup.getOpcode() == DUP
                    && before instanceof MethodInsnNode init && init.getOpcode() == INVOKESPECIAL && init.owner.equals(box) && init.name.equals("<init>")
                    && init.desc.equals("(" + type.getDescriptor() + ")V")) {
                    list.remove(created); list.remove(dup); list.remove(init);
                } else {
                    conversion.add(new TypeInsnNode(CHECKCAST, box));
                    conversion.add(new MethodInsnNode(INVOKEVIRTUAL, box, type.getClassName() + "Value", "()" + type.getDescriptor(), false));
                }
                size = type.getSize();
            } else if (type != null && kept[store.index()] && !type.getInternalName().equals("java/lang/Object")) {
                conversion.add(new TypeInsnNode(CHECKCAST, type.getInternalName()));
            }
            if (type == null || !kept[store.index()]) conversion.add(new InsnNode(size == 2 ? POP2 : POP));
            list.insertBefore(store.store(), conversion);
            list.remove(store.store());
            list.remove(store.push());
        }
        for (var copy : site.copies()) list.remove(copy);
        list.remove(site.creation());
        list.remove(site.size());
        site.call().desc = desc;
        site.method().maxStack += 4;
        return true;
    }

    /** Why the last {@link #juggled} on this thread returned null, for the report. */
    static final ThreadLocal<String> WHY = new ThreadLocal<>();
    private static Juggled no(String why) { WHY.set(why); return null; }
    /** A box created by {@code new} and not yet constructed. */
    private record NewBox(AbstractInsnNode creation) {}

    /**
     * The shuffled shape: values pushed first, then {@code new Object[n]} moved under them and filled by {@code aastore}s,
     * the values boxed by {@code valueOf} or {@code new Long(v)} on the way. The array may be put aside in a local while
     * the receiver is cast ({@code astore t; checkcast C; aload t}, written so by the reflection rewrite).
     */
    private static Juggled juggled(ClassNode owner, MethodNode method, MethodInsnNode call) {
        // A receiver cast right before the call, the array in a local meanwhile.
        AbstractInsnNode insn = real(call.getPrevious(), method);
        AbstractInsnNode store = null, load = null;
        TypeInsnNode cast = null;
        if (insn instanceof VarInsnNode aload && aload.getOpcode() == ALOAD && real(aload.getPrevious(), method) instanceof TypeInsnNode checkcast
            && checkcast.getOpcode() == CHECKCAST && real(checkcast.getPrevious(), method) instanceof VarInsnNode astore && astore.getOpcode() == ASTORE
            && astore.var == aload.var && uses(method, aload.var) == 2) {
            store = astore; load = aload; cast = checkcast;
            insn = astore.getPrevious();
        } else insn = call.getPrevious();
        // Back to the array's creation, through straight-line code only.
        var range = new ArrayDeque<AbstractInsnNode>();
        while (insn != null && !(insn instanceof TypeInsnNode create && create.getOpcode() == ANEWARRAY)) {
            if (insn instanceof LabelNode label && targeted(method, label)) return no("a jump target among the shuffles");
            if (insn instanceof JumpInsnNode || insn instanceof TableSwitchInsnNode || insn instanceof LookupSwitchInsnNode) return no("a jump among the shuffles");
            range.addFirst(insn);
            insn = insn.getPrevious();
        }
        if (insn == null || !((TypeInsnNode) insn).desc.equals("java/lang/Object")) return no("no new Object[]");
        range.addFirst(insn);
        var size = real(insn.getPrevious(), method);
        if (size == null || SkidModel.literal(size) == null) return no("the array's length is not a constant");
        range.addFirst(size);
        // Possibly further back over the shuffles, casts and plain pushes that set the values up (a key moved under the
        // others): the farthest start that replays wins.
        var starts = new ArrayList<AbstractInsnNode>();
        for (var before = real(size.getPrevious(), method); before != null && (shuffle(before) || simplePush(before) || before.getOpcode() == CHECKCAST); before = real(before.getPrevious(), method))
            starts.add(before);
        Frame<BasicValue>[] frames;
        try { frames = new Analyzer<>(new BasicInterpreter()).analyze(owner.name, method); }
        catch (AnalyzerException malformed) { return no("not analyzable"); }
        for (int extent = starts.size(); extent >= 0; extent--) {
            var first = extent == 0 ? size : starts.get(extent - 1);
            var span = new ArrayList<AbstractInsnNode>();
            for (var at = first; at != size; at = at.getNext()) span.add(at);
            span.addAll(range);
            var found = replay(owner, method, call, frames, first, span, store, load, cast);
            if (found != null) return found;
        }
        return null;
    }

    /** The symbolic replay of a shuffled call from {@code first}; null (with the reason) when it does not fit. */
    private static Juggled replay(ClassNode owner, MethodNode method, MethodInsnNode call, Frame<BasicValue>[] frames, AbstractInsnNode first,
                                  List<AbstractInsnNode> range, AbstractInsnNode store, AbstractInsnNode load, TypeInsnNode cast) {
        var frame = frames[method.instructions.indexOf(first)];
        if (frame == null) return no("unreachable");
        var stack = new ArrayList<ZkmReverser.Slot>();
        var kinds = new ArrayList<BasicValue>();
        for (int i = 0; i < frame.getStackSize(); i++) {
            kinds.add(frame.getStack(i));
            stack.add(new ZkmReverser.Slot(new Original(i), frame.getStack(i).getSize()));
        }
        Object[] elements = null;
        for (var at : range) {
            int op = at.getOpcode();
            if (op < 0) continue;
            if (op == ANEWARRAY) {
                if (stack.isEmpty() || elements != null) return no("two arrays");
                var length = stack.removeLast();
                if (!(length.id() instanceof Constant constant) || !(SkidModel.literal(constant.insn()) instanceof Integer n) || n < 0) return no("the array's length is not a constant");
                elements = new Object[n];
                stack.add(new ZkmReverser.Slot(ARRAY, 1));
            } else if (op == AASTORE) {
                if (stack.size() < 3) return no("a store under the shuffled values");
                var value = stack.removeLast(); var index = stack.removeLast(); var array = stack.removeLast();
                if (array.id() != ARRAY || !(index.id() instanceof Constant constant) || !(SkidModel.literal(constant.insn()) instanceof Integer k)
                    || elements == null || k < 0 || k >= elements.length || elements[k] != null || value.id() == ARRAY || value.id() instanceof NewBox)
                    return no("a store not of one value at a constant index");
                elements[k] = value.id();
            } else if (at instanceof MethodInsnNode valueOf && op == INVOKESTATIC && valueOf.name.equals("valueOf") && UNBOXED.containsKey(valueOf.owner)) {
                Type primitive = UNBOXED.get(valueOf.owner);
                if (!valueOf.desc.equals("(" + primitive.getDescriptor() + ")L" + valueOf.owner + ";") || stack.isEmpty()) return no("another valueOf");
                var top = stack.removeLast();
                if (!(top.id() instanceof Original || top.id() instanceof Constant)) return no("a boxed value that is not pushed before");
                stack.add(new ZkmReverser.Slot(new Boxed(top.id(), primitive), 1));
            } else if (at instanceof TypeInsnNode created && op == NEW && UNBOXED.containsKey(created.desc)) {
                stack.add(new ZkmReverser.Slot(new NewBox(created), 1));
            } else if (at instanceof MethodInsnNode init && op == INVOKESPECIAL && init.name.equals("<init>") && UNBOXED.containsKey(init.owner)) {
                Type primitive = UNBOXED.get(init.owner);
                if (!init.desc.equals("(" + primitive.getDescriptor() + ")V") || stack.size() < 2) return no("another box constructor");
                var value = stack.removeLast(); var box = stack.removeLast();
                if (!(box.id() instanceof NewBox created) || !((TypeInsnNode) created.creation()).desc.equals(init.owner)
                    || !(value.id() instanceof Original || value.id() instanceof Constant)) return no("a box constructed from another value");
                // Every copy of the new box now stands for the boxed value.
                var boxed = new ZkmReverser.Slot(new Boxed(value.id(), primitive), 1);
                stack.replaceAll(slot -> slot.id().equals(created) ? boxed : slot);
            } else if (op == CHECKCAST) {
                if (stack.isEmpty() || !(stack.getLast().id() instanceof Original || stack.getLast().id() instanceof Constant)) return no("a cast of a value not pushed before");
                var top = stack.removeLast();
                stack.add(new ZkmReverser.Slot(new Cast(top.id(), ((TypeInsnNode) at).desc), 1));
            } else if (simplePush(at)) {
                stack.add(new ZkmReverser.Slot(new Constant(at), constantKind(at).getSize()));
            } else if (!ZkmReverser.shuffle(op, stack)) return no("opcode " + op + " among the shuffles");
        }
        if (elements == null || stack.isEmpty() || stack.getLast().id() != ARRAY) return no("the array is not on top at the call");
        stack.removeLast();
        // What is left below: the original stack's bottom, untouched, then values pushed in between (a receiver), in order.
        int below = 0;
        while (below < stack.size() && stack.get(below).id() instanceof Original original && original.position() == below) below++;
        var pushedBelow = new ArrayList<AbstractInsnNode>();
        for (int i = below; i < stack.size(); i++) {
            Object entry = stack.get(i).id();
            if (entry instanceof Cast castEntry && castEntry.inner() instanceof Constant constant) {
                pushedBelow.add(constant.insn());
                pushedBelow.add(new TypeInsnNode(CHECKCAST, castEntry.type()));
            } else if (entry instanceof Constant constant) pushedBelow.add(constant.insn());
            else return no("the values below were moved");
        }
        if (cast != null && stack.isEmpty()) return no("a cast with no receiver");
        var used = new HashSet<Object>();
        var boxed = new Type[elements.length];
        var casts = new String[elements.length];
        for (int k = 0; k < elements.length; k++) {
            Object source = elements[k];
            if (source == null) return no("an element never stored");
            if (source instanceof Boxed box) { boxed[k] = box.primitive(); source = box.inner(); elements[k] = source; }
            if (source instanceof Cast castEntry) { casts[k] = castEntry.type(); source = castEntry.inner(); elements[k] = source; }
            if (source instanceof Original original && original.position() < below) return no("a value both kept and stored");
            if (!used.add(source)) return no("a value stored twice");
        }
        for (var push : pushedBelow) if (used.contains(new Constant(push))) return no("a value both kept and stored");
        var all = new ArrayList<>(range);
        if (store != null) { all.add(store); all.add(load); }
        return new Juggled(owner, method, call, all, below, kinds, elements, boxed, casts, cast, pushedBelow);
    }

    private static boolean shuffle(AbstractInsnNode insn) {
        int op = insn.getOpcode();
        return op == POP || op == POP2 || op >= DUP && op <= SWAP;
    }

    /** A push that can be repeated later in the same straight-line code: a constant or a local's value. */
    private static boolean simplePush(AbstractInsnNode insn) {
        int op = insn.getOpcode();
        if (insn instanceof LdcInsnNode ldc) return !(ldc.cst instanceof Handle) && !(ldc.cst instanceof org.objectweb.asm.ConstantDynamic);
        return SkidModel.literal(insn) != null || op == ACONST_NULL || op >= LCONST_0 && op <= DCONST_1 || op >= ILOAD && op <= ALOAD;
    }

    /** The instruction itself or the closest real one before it, null past a jump target. */
    private static AbstractInsnNode real(AbstractInsnNode insn, MethodNode method) {
        while (insn != null && insn.getOpcode() < 0) {
            if (insn instanceof LabelNode label && targeted(method, label)) return null;
            insn = insn.getPrevious();
        }
        return insn;
    }

    private static int uses(MethodNode method, int var) {
        int count = 0;
        for (var insn : method.instructions) {
            if (insn instanceof VarInsnNode use && use.var == var) count++;
            if (insn instanceof IincInsnNode inc && inc.var == var) count++;
        }
        return count;
    }

    /**
     * Rewrites a shuffled call: the shuffling goes; the values it took stay where they are when they already lie in
     * parameter order (the left-out ones on top, popped) and the receiver needs no cast, else they are stored in new
     * locals, top first, the receiver cast, and each kept element is loaded in order, converted to its parameter's type.
     */
    private static boolean rewriteJuggled(Juggled site, Type[] types, boolean[] kept, String desc) {
        var list = site.method().instructions;
        int total = site.kinds().size();
        var originals = new ArrayList<Integer>();
        boolean inOrder = site.receiverCast() == null && site.pushedBelow().isEmpty(), droppedSeen = false, constantSeen = false;
        for (int k = 0; k < site.elements().length; k++) {
            boolean keep = types[k] != null && kept[k];
            if (site.elements()[k] instanceof Original original) {
                if (!originals.isEmpty() && originals.getLast() + 1 != original.position()) inOrder = false;
                if (originals.isEmpty() && original.position() != site.below()) inOrder = false;
                originals.add(original.position());
                if (keep) {
                    var conversion = conversion(site.kinds().get(original.position()), site.boxed()[k], types[k]);
                    if (droppedSeen || constantSeen || conversion == null || conversion.size() > 0 || site.casts()[k] != null) inOrder = false;
                } else droppedSeen = true;
            } else if (keep) constantSeen = true;
        }
        if (originals.size() != total - site.below()) inOrder = false; // one popped among the shuffles
        var replacement = new InsnList();
        int[] temps = new int[total];
        if (inOrder) {
            for (int k = site.elements().length - 1; k >= 0; k--)
                if (site.elements()[k] instanceof Original original && !(types[k] != null && kept[k]))
                    replacement.add(new InsnNode(site.kinds().get(original.position()).getSize() == 2 ? POP2 : POP));
        } else {
            int next = freeLocal(site.method());
            for (int i = total - 1; i >= site.below(); i--) {
                var kind = site.kinds().get(i);
                if (kind.getType() == null) return false;
                temps[i] = next;
                replacement.add(new VarInsnNode(kind.getType().getOpcode(ISTORE), next));
                next += kind.getSize();
            }
            site.method().maxLocals = next;
            for (var push : site.pushedBelow()) replacement.add(push.clone(Map.of()));
            if (site.receiverCast() != null) { list.remove(site.receiverCast()); replacement.add(site.receiverCast()); }
        }
        for (int k = 0; k < site.elements().length; k++) {
            if (types[k] == null || !kept[k]) continue;
            Object source = site.elements()[k];
            BasicValue kind;
            if (source instanceof Original original) {
                kind = site.kinds().get(original.position());
                if (!inOrder) replacement.add(new VarInsnNode(kind.getType().getOpcode(ILOAD), temps[original.position()]));
            } else {
                var constant = ((Constant) source).insn();
                replacement.add(constant.clone(Map.of()));
                kind = constantKind(constant);
            }
            if (site.casts()[k] != null) replacement.add(new TypeInsnNode(CHECKCAST, site.casts()[k]));
            var conversion = site.casts()[k] != null && site.casts()[k].equals(types[k].getInternalName()) ? new InsnList()
                : conversion(kind, site.boxed()[k], types[k]);
            if (conversion == null) return false;
            replacement.add(conversion);
        }
        for (var insn : site.range()) if (insn.getOpcode() >= 0) list.remove(insn);
        list.insertBefore(site.call(), replacement);
        site.call().desc = desc;
        site.method().maxStack += 4;
        return true;
    }

    private static BasicValue constantKind(AbstractInsnNode constant) {
        int op = constant.getOpcode();
        if (op == ACONST_NULL || op == ALOAD) return BasicValue.REFERENCE_VALUE;
        if (op == LLOAD || op == LCONST_0 || op == LCONST_1) return BasicValue.LONG_VALUE;
        if (op == DLOAD || op == DCONST_0 || op == DCONST_1) return BasicValue.DOUBLE_VALUE;
        if (op == FLOAD || op >= FCONST_0 && op <= FCONST_2) return BasicValue.FLOAT_VALUE;
        if (!(constant instanceof LdcInsnNode ldc)) return BasicValue.INT_VALUE;
        if (ldc.cst instanceof Integer) return BasicValue.INT_VALUE;
        if (ldc.cst instanceof Long) return BasicValue.LONG_VALUE;
        if (ldc.cst instanceof Float) return BasicValue.FLOAT_VALUE;
        if (ldc.cst instanceof Double) return BasicValue.DOUBLE_VALUE;
        return BasicValue.REFERENCE_VALUE;
    }

    /**
     * What turns a value of {@code kind} (boxed by the caller as {@code boxed}, the boxing now gone, or not boxed) into a
     * parameter of {@code type}; empty when nothing is needed, null when it cannot.
     */
    private static InsnList conversion(BasicValue kind, Type boxed, Type type) {
        var list = new InsnList();
        boolean primitive = kind != BasicValue.REFERENCE_VALUE;
        if (type.getSort() <= Type.DOUBLE) {
            if (boxed != null) return boxed.equals(type) ? list : null;
            if (primitive) return null;
            String box = boxOf(type);
            list.add(new TypeInsnNode(CHECKCAST, box));
            list.add(new MethodInsnNode(INVOKEVIRTUAL, box, type.getClassName() + "Value", "()" + type.getDescriptor(), false));
            return list;
        }
        if (boxed != null) {
            String box = boxOf(boxed);
            list.add(new MethodInsnNode(INVOKESTATIC, box, "valueOf", "(" + boxed.getDescriptor() + ")L" + box + ";", false));
            if (!type.getInternalName().equals(box) && !type.getInternalName().equals("java/lang/Object")) list.add(new TypeInsnNode(CHECKCAST, type.getInternalName()));
            return list;
        }
        if (primitive) return null;
        if (!type.getInternalName().equals("java/lang/Object")) list.add(new TypeInsnNode(CHECKCAST, type.getInternalName()));
        return list;
    }

    private static boolean targeted(MethodNode method, LabelNode label) {
        for (var insn : method.instructions) {
            if (insn instanceof JumpInsnNode jump && jump.label == label) return true;
            if (insn instanceof TableSwitchInsnNode table && (table.dflt == label || table.labels.contains(label))) return true;
            if (insn instanceof LookupSwitchInsnNode lookup && (lookup.dflt == label || lookup.labels.contains(label))) return true;
        }
        for (var block : method.tryCatchBlocks) if (block.start == label || block.end == label || block.handler == label) return true;
        return false;
    }


    private static String boxOf(Type primitive) {
        for (var entry : UNBOXED.entrySet()) if (entry.getValue().equals(primitive)) return entry.getKey();
        throw new IllegalArgumentException(primitive.toString());
    }

    // ---------------------------------------------------------------- hierarchy and checks

    /** The class, its supertypes (the jar's and the JDK's) and the jar's subtypes. */
    private Set<String> related(String name) {
        var related = new LinkedHashSet<String>();
        var pending = new ArrayDeque<String>(List.of(name));
        while (!pending.isEmpty()) {
            String type = pending.pop();
            if (!related.add(type)) continue;
            var node = header.apply(type);
            if (node == null) continue;
            if (node.superName != null) pending.add(node.superName);
            pending.addAll(node.interfaces);
        }
        for (boolean grew = true; grew; ) {
            grew = false;
            for (var node : nodes.values()) {
                if (related.contains(node.name)) continue;
                boolean sub = node.superName != null && related.contains(node.superName) && isSubtype(node.superName, name)
                    || node.interfaces.stream().anyMatch(i -> related.contains(i) && isSubtype(i, name));
                if (sub) { related.add(node.name); grew = true; }
            }
        }
        return related;
    }

    private boolean isSubtype(String type, String of) {
        var pending = new ArrayDeque<String>(List.of(type));
        var seen = new HashSet<String>();
        while (!pending.isEmpty()) {
            String current = pending.pop();
            if (current.equals(of)) return true;
            if (!seen.add(current)) continue;
            var node = nodes.get(current);
            if (node == null) continue;
            if (node.superName != null) pending.add(node.superName);
            pending.addAll(node.interfaces);
        }
        return false;
    }

    private boolean declares(String type, String name, String desc) {
        var node = header.apply(type);
        if (node == null) return !nodes.containsKey(type) && !type.startsWith("java/"); // unknown library: assume it might
        for (var method : node.methods) if (method.name.equals(name) && method.desc.equals(desc)) return true;
        return false;
    }

    /** Whether the class declares a method of that name whose parameters are {@code params} (any result: Java cannot tell them apart). */
    private boolean declaresParameters(String type, String name, String params) {
        var node = header.apply(type);
        if (node == null) return false;
        for (var method : node.methods) if (method.name.equals(name) && method.desc.startsWith(params)) return true;
        return false;
    }

    private static boolean mentions(InvokeDynamicInsnNode indy, MethodNode method) {
        if (indy.bsmArgs == null) return false;
        for (Object arg : indy.bsmArgs)
            if (arg instanceof Handle handle && handle.getName().equals(method.name) && handle.getDesc().equals(method.desc)) return true;
        return false;
    }

    private static boolean verifies(String owner, MethodNode method) {
        try { new Analyzer<>(new BasicVerifier()).analyze(owner, method); return true; }
        catch (AnalyzerException | RuntimeException invalid) { return false; }
    }
}
