package dev.classrevive.transforms.skid;

import dev.classrevive.transforms.TransformResult;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.*;
import java.io.IOException;
import java.util.*;

/**
 * Phase 2DM: reverses Zelix KlassMaster 27's keyed encryption and reflection obfuscation over a whole jar, statically.
 * <ul>
 *   <li><b>Initializers</b>: each class's {@code <clinit>} is interpreted by {@link AidsEvaluator} until the program's own
 *   code starts. That decrypts its {@code static final String} constants and fills the tables its decryptors read.</li>
 *   <li><b>Keys</b> ({@link ZkmKeys}): every method holds a {@code long} key, a local constant, a parameter or element 0 of
 *   its packed {@code Object[]} parameter; callers derive it from their own. The keys reaching a method through its
 *   calls are learned round after round.</li>
 *   <li><b>Decryptors</b>: a static method with primitive parameters that returns a {@code String}, an {@code int} or a
 *   {@code long} and reads its class's initializer tables. Each call whose arguments are constants is evaluated and
 *   replaced by its value.</li>
 *   <li><b>Reflection</b>: a call or field access became {@code R.d(id, key)} (a {@code Method}) or {@code R.c(id, key)} (a
 *   {@code Field}) of a generated class, then {@code invoke}, {@code get} or {@code set}. The generated class's own code
 *   finds the member, interpreted with {@code Class.forName} and its member finders answered by symbolic references,
 *   so no class is loaded; its arguments were packed by its {@code Object[]} helpers. The instruction is written back
 *   ({@code invokevirtual}, {@code invokestatic}, {@code invokeinterface}, {@code getfield}...), the packing call goes,
 *   results are boxed where {@code invoke} boxed them, and {@code InvocationTargetException} unwrapping handlers go.</li>
 * </ul>
 * Pure code left dead goes. Target code is never executed. Methods whose parameters Zelix packed into one
 * {@code Object[]} keep that signature; their keys are known, so what they decrypt is replaced too.
 */
public final class ZkmReverser implements Opcodes {
    public static final String ID = "zkm";
    private static final int MAX_ROUNDS = 12;
    static final String METHOD_FINDER = "(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/Class;I[Ljava/lang/Class;)Ljava/lang/reflect/Method;";
    static final String FIELD_FINDER = "(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/Class;)Ljava/lang/reflect/Field;";

    public record Report(boolean detected, Map<String,Integer> counts, List<String> refused) {
        public Report { counts = Collections.unmodifiableMap(new LinkedHashMap<>(counts)); refused = List.copyOf(refused); }
        public int count(String name) { return counts.getOrDefault(name, 0); }
        @Override public String toString() {
            var out = new StringBuilder("Zelix KlassMaster reversal: ").append(detected ? "detected" : "not detected");
            counts.forEach((name, value) -> out.append(System.lineSeparator()).append("  ").append(name).append(": ").append(value));
            if (!refused.isEmpty()) out.append(System.lineSeparator()).append("  refused: ").append(refused);
            return out.toString();
        }
    }
    public record Result(Map<String,byte[]> entries, Report report, List<TransformResult.Change> changes) {}

    /** A class named by {@code Class.forName} (binary name, dots), never loaded. */
    record TypeRef(String name) {}
    /** A member found by the reflection class's finders: owner and type are {@link TypeRef}s or JDK primitive classes. */
    record MemberRef(Object owner, String name, Object type, List<Object> parameters, boolean field) {}

    private final Map<String,ClassNode> nodes = new LinkedHashMap<>();
    private final Map<String,Optional<ClassNode>> headers = new HashMap<>();
    private final Map<String,Integer> counts = new LinkedHashMap<>();
    private final List<String> refused = new ArrayList<>();
    /** The reflection class's tables right after its initializer: every lookup starts from them. */
    private Map<String,Object> reflectionState = Map.of();
    /** Why each reflective access left in place was not written back (the last round's reason). */
    private final Map<AbstractInsnNode,String> unmatched = new IdentityHashMap<>();
    /** Methods the JDK's analyzer refused in the last round. */
    private final Set<String> notAnalyzable = new TreeSet<>();
    /** Static int and long fields the initializers set and nothing else writes ({@code owner.name:desc}): class keys. */
    private final Map<String,Object> classKeys = new HashMap<>();
    /** The calls written back: a program's own {@code Method.invoke} among them is no reflection of Zelix's. */
    private final Set<AbstractInsnNode> writtenBack = Collections.newSetFromMap(new IdentityHashMap<>());
    /** Where each class's program code starts in its {@code <clinit>}, after the obfuscator's initializer (null: none). */
    private final Map<String,LabelNode> starts = new HashMap<>();
    private void count(String name, int value) { counts.merge(name, value, Integer::sum); }
    private void refuse(String what) { if (refused.size() < 20) refused.add(what); }

    public Result apply(Map<String,byte[]> entries, int maxChanges) {
        var entryOf = new HashMap<String,String>();
        for (var entry : entries.entrySet()) {
            if (!entry.getKey().endsWith(".class")) continue;
            try {
                var node = new ClassNode();
                new ClassReader(entry.getValue()).accept(node, ClassReader.SKIP_FRAMES);
                if (nodes.putIfAbsent(node.name, node) == null) entryOf.put(node.name, entry.getKey());
            } catch (RuntimeException unreadable) {
                // Left as it is.
            }
        }
        ClassNode reflection = null;
        for (var node : nodes.values()) if (reflectionClass(node)) { reflection = node; break; }
        var keyed = new ArrayList<ClassNode>();
        for (var node : nodes.values()) if (keyedDecryptors(node)) keyed.add(node);
        // Java 7 and later targets: decryptors reached through invokedynamic.
        var indyTargets = new LinkedHashMap<String,Map<String,MethodNode>>();
        for (var node : nodes.values()) {
            var found = new HashMap<String,MethodNode>();
            for (var method : node.methods)
                for (var insn : method.instructions)
                    if (insn instanceof InvokeDynamicInsnNode indy && !found.containsKey(indyKey(indy))) {
                        MethodNode target = indyDecryptor(node, indy);
                        if (target != null) found.put(indyKey(indy), target);
                    }
            if (!found.isEmpty()) indyTargets.put(node.name, found);
        }
        if (reflection == null && keyed.isEmpty() && indyTargets.isEmpty())
            return new Result(entries, new Report(false, Map.of("classes", nodes.size()), List.of()), List.of());

        // Initializers: decrypted constants and filled tables.
        var evaluators = new HashMap<String,AidsEvaluator>();
        var decryptors = new HashMap<String,Map<String,MethodNode>>();
        int constants = 0;
        for (var node : nodes.values()) {
            MethodNode clinit = clinit(node);
            if (clinit == null || indyTargets.containsKey(node.name)) continue; // those run with ZkmRuntime below
            var fields = new HashSet<String>();
            for (var field : node.fields) if ((field.access & ACC_STATIC) != 0) fields.add(field.name + ":" + field.desc);
            var evaluator = new AidsEvaluator(node, node == reflection ? reflectionHooks(node) : null);
            AidsEvaluator.Stop stop;
            try { stop = evaluator.runInitializer(clinit, fields); }
            catch (AidsEvaluator.Refusal refusal) { refuse(node.name + ".<clinit>: " + refusal.getMessage()); continue; }
            evaluators.put(node.name, evaluator);
            // Marked now: the statement's first instruction may be a read that is replaced later. Null: it ran to its return.
            LabelNode start = null;
            if (stop.at() != null) { start = new LabelNode(); clinit.instructions.insertBefore(stop.at(), start); }
            starts.put(node.name, start);
            if (node == reflection) { reflectionState = evaluator.snapshot(); continue; }
            constants += stringConstants(node, evaluator);
            var found = new LinkedHashMap<String,MethodNode>();
            for (var method : node.methods) if (decryptor(node, method, evaluator)) found.put(method.name + method.desc, method);
            if (!found.isEmpty()) decryptors.put(node.name, found);
        }
        count("string constants decrypted", constants);
        classKeys(evaluators);
        var runtime = new ZkmRuntime(nodes);
        var indyReady = indyInitializers(runtime, indyTargets);

        // Rounds: rewrite what the known keys decrypt, then learn the keys calls pass on.
        var parameters = new HashMap<String,Map<Integer,Object>>();
        var packedKeys = new HashMap<String,Map<Integer,Object>>();
        var changed = new LinkedHashSet<String>();
        var changes = new ArrayList<TransformResult.Change>();
        for (int round = 0; round < MAX_ROUNDS; round++) {
            int before = counts.getOrDefault("strings decrypted", 0) + counts.getOrDefault("numbers decrypted", 0)
                + counts.getOrDefault("reflective accesses written back", 0);
            var calls = new HashMap<String,Map<Integer,Set<Object>>>();
            var packedCalls = new HashMap<String,Map<Integer,Set<Object>>>();
            for (var node : nodes.values()) {
                if (node == reflection) continue;
                for (var method : node.methods) {
                    if (method.instructions.size() == 0) continue;
                    String key = SkidModel.key(node.name, method.name, method.desc);
                    var known = parameters.getOrDefault(key, Map.of());
                    var analysis = ZkmKeys.analyze(node.name, method, known, packedKeys.getOrDefault(key, Map.of()), classKeys);
                    if (analysis == null || analysis.frames() == null) { notAnalyzable.add(key + " (" + ZkmKeys.FAILURE.get() + ")"); continue; }
                    notAnalyzable.removeIf(entry -> entry.startsWith(key + " ("));
                    int done = decrypt(node, method, analysis.frames(), decryptors.getOrDefault(node.name, Map.of()), evaluators.get(node.name));
                    if (indyReady.contains(node.name)) done += decryptIndy(node, method, analysis.frames(), indyTargets.get(node.name), runtime);
                    if (reflection != null) done += unreflect(node, method, known, packedKeys.getOrDefault(key, Map.of()), reflection, evaluators.get(reflection.name));
                    if (done > 0) {
                        changed.add(node.name);
                        if (changes.size() < maxChanges) changes.add(new TransformResult.Change(ID, node.name, method.name, method.desc, -1, 0, done, 0, done));
                        analysis = ZkmKeys.analyze(node.name, method, parameters.getOrDefault(key, Map.of()), packedKeys.getOrDefault(key, Map.of()), classKeys);
                        if (analysis == null || analysis.frames() == null) continue;
                    }
                    collectCalls(method, analysis, calls, packedCalls);
                }
            }
            int known = parameters.values().stream().mapToInt(Map::size).sum() + packedKeys.values().stream().mapToInt(Map::size).sum();
            learn(calls, packedCalls, parameters, packedKeys);
            int learned = parameters.values().stream().mapToInt(Map::size).sum() + packedKeys.values().stream().mapToInt(Map::size).sum() - known;
            int after = counts.getOrDefault("strings decrypted", 0) + counts.getOrDefault("numbers decrypted", 0)
                + counts.getOrDefault("reflective accesses written back", 0);
            if (after == before && learned == 0 && round > 0) break;
        }

        // Opaque flags: proved false, with the keys now known.
        var opaque = new ZkmFlags(nodes, parameters, packedKeys, classKeys);
        changed.addAll(opaque.apply());
        count("opaque flags proved", opaque.flags);
        count("opaque flag reads folded", opaque.reads);
        count("opaque branches folded", opaque.branches);

        // The clean-ups below only touch the classes the obfuscator protected: a library bundled in the jar keeps its bytes,
        // so the engines read it exactly as before.
        var scope = protectedClasses(reflection, keyed, indyTargets, changed);
        count("protected classes", scope.size());
        // Exception obfuscation: handlers rethrow through a helper returning its argument; without it they only rethrow.
        count("identity helper calls removed", removeIdentityCalls(scope, changed));
        // Clean-up: dead pure code, unwrapping handlers, decryptors nothing calls.
        int dead = 0, handlers = 0;
        for (var node : nodes.values()) {
            if (node == reflection || !scope.contains(node.name)) continue;
            for (var method : node.methods) {
                int removed = PureDeadCode.clean(node.name, method, classKeys.keySet());
                int unwrapped = removeUnwrappers(method);
                if (removed + unwrapped > 0) changed.add(node.name);
                dead += removed; handlers += unwrapped;
            }
        }
        // Packed Object[] parameters back to their own, the key left out.
        var unpacker = new ZkmUnpacker(nodes, this::header, classKeys.keySet());
        changed.addAll(unpacker.apply());
        count("packed methods unpacked", unpacker.unpacked);
        count("packed calls rewritten", unpacker.callsRewritten);
        count("key parameters dropped", unpacker.keysDropped);
        for (String why : unpacker.refused) refuse("left packed: " + why);

        // What the unpacking left: the dropped keys' expressions.
        for (var node : nodes.values()) {
            if (node == reflection || !scope.contains(node.name) && !changed.contains(node.name)) continue;
            for (var method : node.methods) {
                int removed = PureDeadCode.clean(node.name, method, classKeys.keySet());
                if (removed > 0) changed.add(node.name);
                dead += removed;
            }
        }
        count("dead instructions removed", dead);
        count("InvocationTargetException handlers removed", handlers);
        int unused = 0;
        for (var entry : decryptors.entrySet()) {
            var node = nodes.get(entry.getKey());
            var called = new HashSet<String>();
            for (var other : nodes.values())
                for (var method : other.methods)
                    for (var insn : method.instructions)
                        if (insn instanceof MethodInsnNode call && call.owner.equals(node.name)) called.add(call.name + call.desc);
            int size = node.methods.size();
            node.methods.removeIf(method -> entry.getValue().containsValue(method) && !called.contains(method.name + method.desc));
            if (size != node.methods.size()) { unused += size - node.methods.size(); changed.add(node.name); }
        }
        count("decryptors removed", unused);
        count("invokedynamic decryption methods removed", removeIndyMachinery(indyTargets, changed));
        int indyLeft = 0;
        var indyViews = new LinkedHashMap<String,InitView>();
        for (var entry : indyTargets.entrySet()) {
            int left = 0;
            for (var method : nodes.get(entry.getKey()).methods)
                for (var insn : method.instructions)
                    if (insn instanceof InvokeDynamicInsnNode indy && entry.getValue().containsKey(indyKey(indy))) left++;
            indyLeft += left;
            // Once nothing decrypts any more, the class's own initializer goes like the others' (its tables only fed the decryptor).
            if (left == 0 && indyReady.contains(entry.getKey()))
                indyViews.put(entry.getKey(), new InitView(runtime.written.getOrDefault(entry.getKey(), Set.of()), runtime.statics.getOrDefault(entry.getKey(), Map.of())));
        }
        count("invokedynamic decryptions left", indyLeft);
        evaluators.keySet().retainAll(scope);
        stripInitializers(evaluators, reflection, changed);
        stripInitializers(indyViews, changed);
        int hoisted = 0;
        for (var node : nodes.values())
            if (scope.contains(node.name) || changed.contains(node.name)) for (var method : node.methods)
                if (method.name.equals("<init>")) { int moved = PureDeadCode.afterSuper(node, method, classKeys.keySet()); if (moved > 0) { changed.add(node.name); hoisted += moved; } }
        count("key statements moved after super()", hoisted);
        int left = 0;
        if (reflection != null)
            for (var node : nodes.values())
                for (var method : node.methods)
                    for (var insn : method.instructions)
                        if (insn instanceof MethodInsnNode call && call.owner.equals(reflection.name)
                            && (call.desc.equals("(JJ)Ljava/lang/reflect/Method;") || call.desc.equals("(JJ)Ljava/lang/reflect/Field;"))) left++;
        count("reflective accesses left", left);
        if (reflection != null) count("methods declared to throw what their reflection throws", declareReflectiveThrows(reflection, changed));
        // Why: per reason, for the accesses still in place (an access with no reason had no constant key).
        var reasons = new TreeMap<String,Integer>();
        unmatched.forEach((call, reason) -> {
            for (var node : nodes.values()) for (var method : node.methods) if (method.instructions.contains(call)) reasons.merge(reason.replaceAll(" \\(.*", ""), 1, Integer::sum);
        });
        reasons.forEach((reason, n) -> count("left because: " + reason, n));
        unmatched.forEach((call, reason) -> {
            for (var node : nodes.values()) for (var method : node.methods)
                if (method.instructions.contains(call)) refuse(node.name + "." + method.name + ": " + reason);
        });
        for (String key : notAnalyzable) refuse(key + ": not analyzable");
        count("methods not analyzable", notAnalyzable.size());
        count("keyed parameters learned", parameters.values().stream().mapToInt(Map::size).sum());
        count("packed keys learned", packedKeys.values().stream().mapToInt(Map::size).sum());

        var output = new LinkedHashMap<String,byte[]>();
        int failures = 0;
        var hierarchy = new Hierarchy(nodes);
        var internalOf = new HashMap<String,String>();
        entryOf.forEach((internal, entry) -> internalOf.put(entry, internal));
        // The reflection class goes once nothing names it.
        String dropped = reflection != null && !referenced(reflection.name, reflection) ? reflection.name : null;
        count("reflection classes removed", dropped == null ? 0 : 1);
        for (var entry : entries.entrySet()) {
            String internal = internalOf.get(entry.getKey());
            if (internal != null && internal.equals(dropped)) continue;
            if (internal == null || !changed.contains(internal)) { output.put(entry.getKey(), entry.getValue()); continue; }
            var node = nodes.get(internal);
            for (var method : node.methods) AidsfuscatorReverser.debugInfo(method);
            try {
                int flags = (node.version & 0xFFFF) >= V1_6 ? ClassWriter.COMPUTE_FRAMES : ClassWriter.COMPUTE_MAXS;
                var writer = hierarchy.writer(flags);
                node.accept(writer);
                output.put(entry.getKey(), writer.toByteArray());
            } catch (RuntimeException error) {
                failures++; refuse(internal + ": write " + error);
                output.put(entry.getKey(), entry.getValue());
            }
        }
        var report = new LinkedHashMap<String,Integer>();
        report.put("classes", nodes.size());
        report.put("reflection classes", reflection == null ? 0 : 1);
        report.put("classes with decryptors", decryptors.size());
        report.putAll(counts);
        report.put("class write failures", failures);
        return new Result(output, new Report(true, report, refused), changes);
    }

    // ---------------------------------------------------------------- recognition

    /** The generated reflection class: static {@code (long, long)Method} and {@code (long, long)Field} getters and their finders. */
    static boolean reflectionClass(ClassNode node) {
        boolean method = false, field = false, finder = false;
        for (var m : node.methods) {
            if ((m.access & ACC_STATIC) == 0) continue;
            if (m.desc.equals("(JJ)Ljava/lang/reflect/Method;")) method = true;
            if (m.desc.equals("(JJ)Ljava/lang/reflect/Field;")) field = true;
            if (m.desc.equals(METHOD_FINDER) || m.desc.equals(FIELD_FINDER)) finder = true;
        }
        return method && field && finder;
    }

    /** A class with a static decryptor keyed by a {@code long}: {@code (int, long)int} or {@code (int, int, int)String} reading tables. */
    static boolean keyedDecryptors(ClassNode node) {
        for (var m : node.methods)
            if ((m.access & ACC_STATIC) != 0 && (m.desc.equals("(IJ)I") || m.desc.equals("(IJ)J") || m.desc.equals("(III)Ljava/lang/String;")))
                for (var insn : m.instructions)
                    if (insn instanceof FieldInsnNode field && field.getOpcode() == GETSTATIC && field.owner.equals(node.name) && field.desc.startsWith("[")) return true;
        return false;
    }

    private static MethodNode clinit(ClassNode node) {
        for (var method : node.methods) if (method.name.equals("<clinit>") && method.instructions.size() > 0) return method;
        return null;
    }

    /**
     * A decryptor: static, primitive parameters, a {@code String}, {@code int} or {@code long} result, reading at least one
     * table its class's initializer wrote, and only doing what the interpreter runs.
     */
    private static boolean decryptor(ClassNode node, MethodNode method, AidsEvaluator evaluator) {
        if ((method.access & ACC_STATIC) == 0 || method.name.startsWith("<")) return false;
        var args = Type.getArgumentTypes(method.desc);
        if (args.length == 0 || Arrays.stream(args).anyMatch(t -> t.getSort() < Type.CHAR || t.getSort() > Type.LONG || t.getSort() == Type.FLOAT)) return false;
        String result = Type.getReturnType(method.desc).getDescriptor();
        if (!result.equals("Ljava/lang/String;") && !result.equals("I") && !result.equals("J")) return false;
        boolean table = false;
        for (var insn : method.instructions) {
            if (insn instanceof FieldInsnNode field) {
                if (!field.owner.equals(node.name)) return false;
                if (field.getOpcode() == GETSTATIC && field.desc.startsWith("[") && evaluator.written.contains(field.name + ":" + field.desc)) table = true;
            } else if (insn instanceof MethodInsnNode call) {
                if (call.owner.equals(node.name)) return false;
                if (!call.owner.equals("java/lang/String") && !call.owner.equals("java/lang/Integer") && !call.owner.equals("java/lang/Long")) return false;
            } else if (insn instanceof InvokeDynamicInsnNode || insn.getOpcode() == ATHROW || insn.getOpcode() == MONITORENTER) return false;
        }
        return table;
    }

    private void classKeys(Map<String,AidsEvaluator> evaluators) {
        var written = new HashSet<String>();
        for (var node : nodes.values())
            for (var method : node.methods)
                if (!method.name.equals("<clinit>"))
                    for (var insn : method.instructions)
                        if (insn instanceof FieldInsnNode put && put.getOpcode() == PUTSTATIC) written.add(put.owner + "." + put.name + ":" + put.desc);
        evaluators.forEach((owner, evaluator) -> evaluator.statics.forEach((field, value) -> {
            String key = owner + "." + field;
            if (evaluator.written.contains(field) && (value instanceof Long || value instanceof Integer) && !written.contains(key)) classKeys.put(key, value);
        }));
        count("class key fields", classKeys.size());
    }

    /** Static final String fields the initializer set: each read becomes the constant, and the field gets it as its value. */
    private int stringConstants(ClassNode node, AidsEvaluator evaluator) {
        int count = 0;
        for (var field : node.fields) {
            if ((field.access & (ACC_STATIC | ACC_FINAL)) != (ACC_STATIC | ACC_FINAL) || !field.desc.equals("Ljava/lang/String;")) continue;
            String key = field.name + ":" + field.desc;
            if (!evaluator.written.contains(key) || !(evaluator.statics.get(key) instanceof String value)) continue;
            field.value = value;
            for (var other : nodes.values())
                for (var method : other.methods)
                    for (var insn : method.instructions.toArray())
                        if (insn instanceof FieldInsnNode get && get.getOpcode() == GETSTATIC && get.owner.equals(node.name)
                            && get.name.equals(field.name) && get.desc.equals(field.desc) && !(other == node && method.name.equals("<clinit>")))
                            method.instructions.set(insn, new LdcInsnNode(value));
            count++;
        }
        return count;
    }

    // ---------------------------------------------------------------- decryptors

    private int decrypt(ClassNode node, MethodNode method, Frame<ZkmKeys.KV>[] frames, Map<String,MethodNode> decryptors, AidsEvaluator evaluator) {
        if (decryptors.isEmpty() || evaluator == null || decryptors.containsValue(method)) return 0;
        int done = 0;
        var insns = method.instructions.toArray();
        for (int i = 0; i < insns.length; i++) {
            if (!(insns[i] instanceof MethodInsnNode call) || call.getOpcode() != INVOKESTATIC || !call.owner.equals(node.name)) continue;
            var target = decryptors.get(call.name + call.desc);
            if (target == null) continue;
            Object[] args = ZkmKeys.arguments(frames[i], call.desc);
            if (args == null) continue;
            Object value;
            try { value = evaluator.call(target, args, method.name); }
            catch (AidsEvaluator.Refusal refusal) { unmatched.put(call, "decryptor refused: " + refusal.getMessage()); continue; }
            var list = pops(call.desc);
            if (value instanceof String text) { list.add(new LdcInsnNode(text)); count("strings decrypted", 1); }
            else if (value instanceof Integer number) { list.add(MethodRewriter.push(number)); count("numbers decrypted", 1); }
            else if (value instanceof Long number) { list.add(new LdcInsnNode(number)); count("numbers decrypted", 1); }
            else continue;
            method.instructions.insertBefore(call, list);
            method.instructions.remove(call);
            method.maxStack += 2; // a long constant where an int key was; recomputed when the class is written
            done++;
        }
        return done;
    }

    // ---------------------------------------------------------------- invokedynamic (Java 7 and later targets)

    static final String BOOTSTRAP = "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;";
    /** The resolver's parameters; it returns the value as an {@code Object} or as the primitive itself. */
    static final String RESOLVER = "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/invoke/MutableCallSite;Ljava/lang/String;[Ljava/lang/Object;)";

    /** A call site's bootstrap and type: the sites sharing it reach the same decryptor. */
    static String indyKey(InvokeDynamicInsnNode indy) { return indy.bsm.getName() + indy.bsm.getDesc() + "|" + indy.desc; }

    /**
     * The decryptor an {@code invokedynamic} of Zelix KlassMaster 27 reaches: its bootstrap is a static method of the same
     * class that creates a {@code MutableCallSite} whose target collects the arguments into a resolver
     * {@code (Lookup, MutableCallSite, String, Object[])Object}; the resolver calls one static method of the class with the
     * call site's own type and installs the result as a constant ({@code MethodHandles.constant}). The site is then that
     * call. Null for any other shape.
     */
    static MethodNode indyDecryptor(ClassNode node, InvokeDynamicInsnNode indy) {
        var bsm = indy.bsm;
        if (bsm.getTag() != H_INVOKESTATIC || !bsm.getOwner().equals(node.name) || !bsm.getDesc().equals(BOOTSTRAP) || indy.bsmArgs.length != 0) return null;
        var args = Type.getArgumentTypes(indy.desc);
        if (args.length == 0 || Arrays.stream(args).anyMatch(t -> t.getSort() < Type.BOOLEAN || t.getSort() > Type.LONG || t.getSort() == Type.FLOAT)) return null;
        String result = Type.getReturnType(indy.desc).getDescriptor();
        if (!result.equals("Ljava/lang/String;") && !result.equals("I") && !result.equals("J")) return null;
        MethodNode bootstrap = find(node, bsm.getName(), bsm.getDesc());
        if (bootstrap == null || (bootstrap.access & ACC_STATIC) == 0) return null;
        Handle resolverHandle = null;
        boolean mutable = false;
        for (var insn : bootstrap.instructions) {
            if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof Handle handle) {
                if (resolverHandle != null || !handle.getOwner().equals(node.name) || !handle.getDesc().startsWith(RESOLVER)) return null;
                resolverHandle = handle;
            }
            if (insn instanceof TypeInsnNode type && type.getOpcode() == NEW && type.desc.equals("java/lang/invoke/MutableCallSite")) mutable = true;
        }
        if (resolverHandle == null || !mutable) return null;
        MethodNode resolver = find(node, resolverHandle.getName(), resolverHandle.getDesc());
        if (resolver == null) return null;
        MethodNode target = null;
        boolean constant = false;
        for (var insn : resolver.instructions) {
            if (!(insn instanceof MethodInsnNode call)) continue;
            if (call.owner.equals("java/lang/invoke/MethodHandles") && call.name.equals("constant")) constant = true;
            if (call.getOpcode() == INVOKESTATIC && call.owner.equals(node.name) && call.desc.equals(indy.desc)) {
                MethodNode found = find(node, call.name, call.desc);
                if (found == null || target != null && target != found) return null;
                target = found;
            }
        }
        if (target == null || !constant || (target.access & ACC_STATIC) == 0) return null;
        return target;
    }

    private static MethodNode find(ClassNode node, String name, String desc) {
        for (var m : node.methods) if (m.name.equals(name) && m.desc.equals(desc)) return m;
        return null;
    }

    /**
     * Runs the obfuscator's part of each such class's {@code <clinit>} with {@link ZkmRuntime}: its key generator and its
     * DES tables. The class keys it sets (int and long statics written once, in the initializer only) join the others.
     * Returns the classes whose decryptors can then be evaluated: those whose initializer filled at least one table.
     */
    private Set<String> indyInitializers(ZkmRuntime runtime, Map<String,Map<String,MethodNode>> indyTargets) {
        var ready = new LinkedHashSet<String>();
        if (indyTargets.isEmpty()) return ready;
        var writtenOutside = new HashSet<String>();
        var clinitWrites = new HashMap<String,Integer>();
        for (var node : nodes.values())
            for (var method : node.methods)
                for (var insn : method.instructions)
                    if (insn instanceof FieldInsnNode put && put.getOpcode() == PUTSTATIC) {
                        String key = put.owner + "." + put.name + ":" + put.desc;
                        if (method.name.equals("<clinit>") && put.owner.equals(node.name)) clinitWrites.merge(key, 1, Integer::sum);
                        else writtenOutside.add(key);
                    }
        int keys = 0, natives = 0;
        for (String owner : indyTargets.keySet()) {
            var node = nodes.get(owner);
            try { ZkmRuntime.onLargeStack(() -> runtime.initialize(node)); }
            catch (ZkmRuntime.Refusal refusal) { refuse(owner + ".<clinit> (invokedynamic): " + refusal.getMessage()); continue; }
            var map = runtime.statics.getOrDefault(owner, Map.of());
            boolean table = false;
            for (String field : runtime.written.getOrDefault(owner, Set.of())) {
                Object value = map.get(field);
                String key = owner + "." + field;
                if (field.contains(":[") && value != null) table = true;
                if ((value instanceof Long || value instanceof Integer) && !writtenOutside.contains(key) && clinitWrites.getOrDefault(key, 0) == 1
                    && classKeys.putIfAbsent(key, value) == null) keys++;
            }
            if (table) ready.add(owner);
            else if (nativeInitializer(node)) natives++;
            else refuse(owner + ".<clinit> (invokedynamic): no decryption table set before the program's code");
        }
        // A wrong class key still passes DES's padding check about once in 256 decryptions: a class any of whose sites with a
        // constant key fails it is not trusted at all, so no site of it is replaced by a value decrypted with a wrong key.
        int distrusted = 0;
        for (var it = ready.iterator(); it.hasNext(); ) {
            String owner = it.next();
            String failure = firstPaddingFailure(nodes.get(owner), indyTargets.get(owner), runtime);
            if (failure == null) continue;
            it.remove(); distrusted++;
            refuse(owner + ": class key not confirmed (" + failure + ")");
        }
        count("invokedynamic classes with a wrong key", distrusted);
        // Where the program's code starts in each initializer, marked before any instruction is replaced.
        for (String owner : ready) {
            var stop = runtime.stop(owner);
            MethodNode clinit = clinit(nodes.get(owner));
            if (stop == null || clinit == null) continue;
            LabelNode start = null;
            if (stop.at() != null) { start = new LabelNode(); clinit.instructions.insertBefore(stop.at(), start); }
            starts.put(owner, start);
        }
        count("invokedynamic classes initialized", ready.size());
        count("invokedynamic class keys", keys);
        count("classes whose initializer is native code", natives);
        return ready;
    }

    /** The first site of the class, with a key known from the class keys alone, whose decryption fails a padding check; null if none. */
    private String firstPaddingFailure(ClassNode node, Map<String,MethodNode> targets, ZkmRuntime runtime) {
        for (var method : node.methods) {
            if (targets.containsValue(method)) continue;
            boolean sites = false;
            for (var insn : method.instructions) if (insn instanceof InvokeDynamicInsnNode indy && targets.containsKey(indyKey(indy))) sites = true;
            if (!sites) continue;
            var analysis = ZkmKeys.analyze(node.name, method, Map.of(), Map.of(), classKeys);
            if (analysis == null || analysis.frames() == null) continue;
            var insns = method.instructions.toArray();
            for (int i = 0; i < insns.length; i++) {
                if (!(insns[i] instanceof InvokeDynamicInsnNode indy) || !targets.containsKey(indyKey(indy))) continue;
                Object[] args = ZkmKeys.arguments(analysis.frames()[i], indy.desc);
                if (args == null) continue;
                try { runtime.call(node.name, targets.get(indyKey(indy)), args); }
                catch (ZkmRuntime.Refusal refusal) {
                    if (refusal.getMessage().contains("Padding")) return method.name + ": " + refusal.getMessage().replaceAll(" <.*", "");
                }
            }
        }
        return null;
    }

    /** A {@code <clinit>} that only hands the class to native code (its keys and tables are set outside the bytecode). */
    private static boolean nativeInitializer(ClassNode node) {
        var clinit = find(node, "<clinit>", "()V");
        if (clinit == null) return false;
        for (var insn : clinit.instructions)
            if (insn instanceof MethodInsnNode call && call.owner.equals(node.name)) {
                var target = find(node, call.name, call.desc);
                if (target != null && (target.access & ACC_NATIVE) != 0) return true;
            }
        return false;
    }

    /** Replaces each {@code invokedynamic} decryption whose arguments are constants by its value. */
    private int decryptIndy(ClassNode node, MethodNode method, Frame<ZkmKeys.KV>[] frames, Map<String,MethodNode> targets, ZkmRuntime runtime) {
        if (targets == null || targets.containsValue(method)) return 0;
        int done = 0;
        var insns = method.instructions.toArray();
        for (int i = 0; i < insns.length; i++) {
            if (!(insns[i] instanceof InvokeDynamicInsnNode indy)) continue;
            var target = targets.get(indyKey(indy));
            if (target == null) continue;
            Object[] args = ZkmKeys.arguments(frames[i], indy.desc);
            if (args == null) continue;
            Object value;
            try { value = runtime.call(node.name, target, args); }
            catch (ZkmRuntime.Refusal refusal) { unmatched.put(indy, "decryptor refused: " + refusal.getMessage()); continue; }
            var list = pops(indy.desc);
            if (value instanceof String text) { list.add(new LdcInsnNode(text)); count("strings decrypted", 1); }
            else if (value instanceof Integer number) { list.add(MethodRewriter.push(number)); count("numbers decrypted", 1); }
            else if (value instanceof Long number) { list.add(new LdcInsnNode(number)); count("numbers decrypted", 1); }
            else continue;
            unmatched.remove(indy);
            method.instructions.insertBefore(indy, list);
            method.instructions.remove(indy);
            method.maxStack += 2;
            done++;
        }
        return done;
    }

    /**
     * The bootstraps no call site uses any more go, then the resolvers only they named, then the decryptors only those
     * called: each when nothing else in the jar refers to it.
     */
    private int removeIndyMachinery(Map<String,Map<String,MethodNode>> indyTargets, Set<String> changed) {
        int removed = 0;
        for (var entry : indyTargets.entrySet()) {
            var node = nodes.get(entry.getKey());
            var candidates = Collections.newSetFromMap(new IdentityHashMap<MethodNode,Boolean>());
            for (var target : entry.getValue().values()) candidates.add(target);
            for (var method : node.methods) {
                if (method.desc.equals(BOOTSTRAP) && (method.access & ACC_STATIC) != 0) candidates.add(method);
                if (method.desc.startsWith(RESOLVER) && (method.access & ACC_STATIC) != 0) candidates.add(method);
            }
            boolean progress = true;
            while (progress) {
                progress = false;
                for (var it = node.methods.iterator(); it.hasNext(); ) {
                    var method = it.next();
                    if (!candidates.contains(method) || referencedMethod(node.name, method)) continue;
                    it.remove(); removed++; progress = true; changed.add(node.name);
                    // What only the machinery called (a decryptor of a kind no site uses, its helpers) may go next.
                    for (var insn : method.instructions)
                        if (insn instanceof MethodInsnNode call && call.getOpcode() == INVOKESTATIC && call.owner.equals(node.name)) {
                            var callee = find(node, call.name, call.desc);
                            if (callee != null && !callee.name.startsWith("<")) candidates.add(callee);
                        }
                }
            }
        }
        return removed;
    }

    /**
     * Zelix KlassMaster's exception obfuscation: {@code catch (RuntimeException e) { throw a(e); }} where {@code a} is a
     * static method of the class returning its only argument ({@code aload_0; areturn}, the same reference type in and
     * out). Each call is dropped (the value on the stack is the same), then the helper once nothing calls it.
     */
    private int removeIdentityCalls(Set<String> scope, Set<String> changed) {
        var identities = new HashMap<String,MethodNode>();
        for (var node : nodes.values())
            if (scope.contains(node.name))
                for (var method : node.methods)
                    if (identity(method)) identities.put(node.name + "." + method.name + method.desc, method);
        if (identities.isEmpty()) return 0;
        int removed = 0;
        for (var node : nodes.values())
            if (scope.contains(node.name)) for (var method : node.methods)
                for (var insn : method.instructions.toArray())
                    if (insn instanceof MethodInsnNode call && call.getOpcode() == INVOKESTATIC && identities.containsKey(call.owner + "." + call.name + call.desc)) {
                        method.instructions.remove(call);
                        removed++;
                        changed.add(node.name);
                    }
        for (var node : nodes.values())
            node.methods.removeIf(method -> identities.get(node.name + "." + method.name + method.desc) == method && !referencedMethod(node.name, method));
        return removed;
    }

    /**
     * The classes Zelix KlassMaster protected: those whose code the decryption rounds already rewrote, those holding its
     * decryptors, call sites, reflection class or class keys, those calling the reflection class, and those deriving a
     * method key ({@code lxor} with a {@code long} constant).
     */
    private Set<String> protectedClasses(ClassNode reflection, List<ClassNode> keyed, Map<String,Map<String,MethodNode>> indyTargets, Set<String> changed) {
        var scope = new HashSet<>(changed);
        scope.addAll(indyTargets.keySet());
        for (var node : keyed) scope.add(node.name);
        if (reflection != null) scope.add(reflection.name);
        for (String key : classKeys.keySet()) scope.add(key.substring(0, key.indexOf('.')));
        for (var node : nodes.values()) {
            if (scope.contains(node.name)) continue;
            search:
            for (var method : node.methods)
                for (var insn : method.instructions) {
                    if (reflection != null && insn instanceof MethodInsnNode call && call.owner.equals(reflection.name)) { scope.add(node.name); break search; }
                    var previous = insn.getPrevious();
                    if (insn.getOpcode() == LXOR && previous != null && (constantLong(previous)
                        || previous.getOpcode() == LLOAD && constantLong(previous.getPrevious()))) { scope.add(node.name); break search; }
                }
        }
        return scope;
    }

    private static boolean constantLong(AbstractInsnNode insn) {
        return insn instanceof LdcInsnNode ldc && ldc.cst instanceof Long;
    }

    private static boolean identity(MethodNode method) {
        if ((method.access & (ACC_STATIC | ACC_NATIVE | ACC_ABSTRACT)) != ACC_STATIC || !method.tryCatchBlocks.isEmpty()) return false;
        var args = Type.getArgumentTypes(method.desc);
        if (args.length != 1 || args[0].getSort() != Type.OBJECT || !args[0].equals(Type.getReturnType(method.desc))) return false;
        var real = new ArrayList<AbstractInsnNode>();
        for (var insn : method.instructions) if (insn.getOpcode() >= 0) real.add(insn);
        return real.size() == 2 && real.get(0) instanceof VarInsnNode load && load.getOpcode() == ALOAD && load.var == 0 && real.get(1).getOpcode() == ARETURN;
    }

    /** Whether any method of the jar but {@code target} itself calls it, names it in a handle, or uses it as a bootstrap. */
    private boolean referencedMethod(String owner, MethodNode target) {
        for (var node : nodes.values())
            for (var method : node.methods) {
                if (method == target) continue;
                for (var insn : method.instructions) {
                    if (insn instanceof MethodInsnNode call && call.owner.equals(owner) && call.name.equals(target.name) && call.desc.equals(target.desc)) return true;
                    if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof Handle h && names(h, owner, target)) return true;
                    if (insn instanceof InvokeDynamicInsnNode indy) {
                        if (names(indy.bsm, owner, target)) return true;
                        for (Object arg : indy.bsmArgs) if (arg instanceof Handle h && names(h, owner, target)) return true;
                    }
                }
            }
        return false;
    }

    private static boolean names(Handle handle, String owner, MethodNode target) {
        return handle.getOwner().equals(owner) && handle.getName().equals(target.name) && handle.getDesc().equals(target.desc);
    }

    /** Pops for a call's arguments, top first. */
    private static InsnList pops(String desc) {
        var list = new InsnList();
        var types = Type.getArgumentTypes(desc);
        for (int k = types.length - 1; k >= 0; k--) list.add(new InsnNode(types[k].getSize() == 2 ? POP2 : POP));
        return list;
    }

    // ---------------------------------------------------------------- reflection

    private AidsEvaluator.Hooks reflectionHooks(ClassNode reflection) {
        return new AidsEvaluator.Hooks() {
            @Override public Object call(MethodInsnNode call, Object receiver, Object[] args) {
                if (call.owner.equals("java/lang/Class") && call.name.equals("forName") && call.desc.equals("(Ljava/lang/String;)Ljava/lang/Class;"))
                    return new TypeRef((String) args[0]);
                if (call.owner.equals(reflection.name) && call.desc.equals(METHOD_FINDER))
                    return new MemberRef(args[0], (String) args[1], args[2], Arrays.asList((Object[]) args[4]), false);
                if (call.owner.equals(reflection.name) && call.desc.equals(FIELD_FINDER))
                    return new MemberRef(args[0], (String) args[1], args[2], List.of(), true);
                return AidsEvaluator.UNHANDLED;
            }
            @Override public boolean castable(Object value, String type) {
                return value instanceof TypeRef && type.equals("java/lang/Class")
                    || value instanceof MemberRef member && (member.field() ? type.equals("java/lang/reflect/Field") : type.equals("java/lang/reflect/Method"));
            }
        };
    }

    /**
     * Writes back the reflective accesses of a method whose id and key are constants, one at a time: the method is analyzed
     * again after each, so a receiver an earlier access now reads directly has its declared type. Returns the number written.
     */
    private int unreflect(ClassNode node, MethodNode method, Map<Integer,Object> parameters, Map<Integer,Object> packedKey, ClassNode reflection, AidsEvaluator evaluator) {
        if (evaluator == null) return 0;
        int done = 0;
        var tried = Collections.newSetFromMap(new IdentityHashMap<AbstractInsnNode,Boolean>());
        while (true) {
            var analysis = ZkmKeys.analyze(node.name, method, parameters, packedKey, classKeys);
            if (analysis == null || analysis.frames() == null) return done;
            var frames = analysis.frames();
            Frame<SourceValue>[] sources;
            try { sources = new Analyzer<>(new SourceInterpreter()).analyze(node.name, method); }
            catch (AnalyzerException malformed) { return done; }
            var insns = method.instructions.toArray();
            var index = new IdentityHashMap<AbstractInsnNode,Integer>();
            for (int i = 0; i < insns.length; i++) index.put(insns[i], i);
            boolean rewritten = false;
            for (int i = 0; i < insns.length && !rewritten; i++) {
                if (!(insns[i] instanceof MethodInsnNode call) || call.getOpcode() != INVOKESTATIC || !call.owner.equals(reflection.name)
                    || !call.desc.equals("(JJ)Ljava/lang/reflect/Method;") && !call.desc.equals("(JJ)Ljava/lang/reflect/Field;")) continue;
                if (tried.contains(call) || frames[i] == null) continue;
                Object[] args = ZkmKeys.arguments(frames[i], call.desc);
                if (args == null) continue;
                tried.add(call);
                MethodNode getter = null;
                for (var m : reflection.methods) if (m.name.equals(call.name) && m.desc.equals(call.desc)) getter = m;
                Object found;
                evaluator.restore(reflectionState);
                try { found = evaluator.call(getter, args, method.name); }
                catch (AidsEvaluator.Refusal refusal) { unmatched.put(call, "lookup refused: " + refusal.getMessage()); continue; }
                if (!(found instanceof MemberRef member)) { unmatched.put(call, "lookup found no member"); continue; }
                String reason = rewriteSite(node, method, call, member, frames[i], sources, index, reflection,
                    Collections.newSetFromMap(new IdentityHashMap<>()));
                if (reason == null) { done++; count("reflective accesses written back", 1); unmatched.remove(call); rewritten = true; }
                else unmatched.put(call, reason + " (" + member.name() + ")");
            }
            if (!rewritten) return done;
        }
    }

    /** A stack value in the shuffle between the reflective getter and its use. */
    record Slot(Object id, int size) {}
    private static final Object REFLECTED = new Object(), NULL = new Object();

    /**
     * Rewrites one site: the shuffle after the getter is replayed on symbols to see which original stack values become
     * the receiver and the arguments of {@code invoke}, {@code get} or {@code set}. They must be the topmost original
     * values, in order, so the direct instruction consumes them where they are.
     */
    private String rewriteSite(ClassNode node, MethodNode method, MethodInsnNode call, MemberRef member, Frame<ZkmKeys.KV> frame,
                                Frame<SourceValue>[] sources, Map<AbstractInsnNode,Integer> index, ClassNode reflection,
                                Set<AbstractInsnNode> removed) {
        // The original stack below the getter's two longs.
        int depth = frame.getStackSize() - 2;
        var stack = new ArrayList<Slot>();
        for (int k = 0; k < depth; k++) stack.add(new Slot(k, frame.getStack(k).getSize()));
        var originals = new ArrayList<>(stack);
        stack.add(new Slot(REFLECTED, 1));
        var shuffle = new ArrayList<AbstractInsnNode>();
        AbstractInsnNode at = call.getNext();
        MethodInsnNode use = null;
        for (int steps = 0; at != null && steps < 12; at = at.getNext()) {
            if (at instanceof LabelNode label && targeted(method, label)) return "label inside the shuffle";
            if (at.getOpcode() < 0) continue;
            steps++;
            if (at instanceof MethodInsnNode m && (m.owner.equals("java/lang/reflect/Method") || m.owner.equals("java/lang/reflect/Field"))) { use = m; break; }
            if (!shuffle(at.getOpcode(), stack)) return "unknown shuffle instruction";
            shuffle.add(at);
        }
        if (use == null) return "no reflective use";
        var useArgs = Type.getArgumentTypes(use.desc);
        int operands = useArgs.length + 1;
        if (stack.size() < operands || stack.get(stack.size() - operands).id() != REFLECTED) return "operands not from the getter";
        var consumed = new ArrayList<Slot>(stack.subList(stack.size() - operands + 1, stack.size()));
        var rest = new ArrayList<Slot>(stack.subList(0, stack.size() - operands));
        // What remains below must be the originals not consumed, in order, and the consumed originals their top.
        var consumedOriginals = consumed.stream().filter(s -> s.id() != NULL).toList();
        if (!rest.equals(originals.subList(0, originals.size() - consumedOriginals.size()))) return "stack not in order";
        var top = originals.subList(originals.size() - consumedOriginals.size(), originals.size());
        // Two values the shuffle exchanged (a long value under its receiver): exchanged back where they are.
        var reorder = new InsnList();
        if (!consumedOriginals.equals(top)) {
            if (top.size() != 2 || !consumedOriginals.equals(List.of(top.get(1), top.get(0)))) return "stack not in order";
            int under = top.get(0).size(), over = top.get(1).size();
            if (under == 1 && over == 1) reorder.add(new InsnNode(SWAP));
            else if (over == 1) { reorder.add(new InsnNode(DUP_X2)); reorder.add(new InsnNode(POP)); }
            else if (under == 1) { reorder.add(new InsnNode(DUP2_X1)); reorder.add(new InsnNode(POP2)); }
            else { reorder.add(new InsnNode(DUP2_X2)); reorder.add(new InsnNode(POP2)); }
        }
        boolean nullReceiver = consumed.getFirst().id() == NULL;

        var direct = new InsnList();
        AbstractInsnNode packer = null;
        String owner = internal(member.owner());
        if (owner == null) return "owner unknown";
        var getterFrame = sources[index.get(call)];
        if (member.field()) {
            String desc = descriptor(member.type());
            if (desc == null) return "field type unknown";
            var declared = field(owner, member.name(), desc);
            if (declared == null) return "field not found";
            boolean isStatic = (declared.access & ACC_STATIC) != 0;
            if (isStatic != nullReceiver) return "field static mismatch";
            boolean get = use.name.startsWith("get");
            Type type = Type.getType(desc);
            boolean typed = isStatic || receiverTyped(node, method, consumed.getFirst(), getterFrame, owner);
            if (get) {
                if (!use.name.equals("get") && !primitiveMatches(use.name.substring(3), type)) return "field primitive accessor mismatch";
                if (!isStatic) castReceiver(direct, method, List.of(), owner, typed);
                direct.add(new FieldInsnNode(isStatic ? GETSTATIC : GETFIELD, owner, member.name(), desc));
                if (use.name.equals("get")) box(direct, type);
            } else {
                if (!use.name.equals("set") && !primitiveMatches(use.name.substring(3), type)) return "field primitive accessor mismatch";
                if (!isStatic) castReceiver(direct, method, List.of(use.name.equals("set") ? Type.getType(Object.class) : type), owner, typed);
                if (use.name.equals("set")) unbox(direct, type);
                direct.add(new FieldInsnNode(isStatic ? PUTSTATIC : PUTFIELD, owner, member.name(), desc));
            }
        } else {
            if (!use.name.equals("invoke")) return "not invoke";
            var parameterTypes = new ArrayList<Type>();
            for (Object parameter : member.parameters()) { String d = descriptor(parameter); if (d == null) return "parameter type unknown"; parameterTypes.add(Type.getType(d)); }
            String returnDesc = descriptor(member.type());
            if (returnDesc == null) return "return type unknown";
            String desc = Type.getMethodDescriptor(Type.getType(returnDesc), parameterTypes.toArray(Type[]::new));
            var declared = method(owner, member.name(), desc);
            if (declared == null) return "method not found";
            boolean isStatic = (declared.access() & ACC_STATIC) != 0;
            if (isStatic != nullReceiver) return "method static mismatch";
            // The argument array was made by a packing helper of the reflection class: its arguments are the call's. It is
            // an original stack value (the shuffle copies values, so it is looked up where the getter is called).
            var array = consumed.getLast();
            if (!(array.id() instanceof Integer slot) || stack.stream().filter(array::equals).count() != 1) return "packed array reused";
            var arraySource = getterFrame.getStack(slot);
            if (arraySource.insns.size() != 1 || !(arraySource.insns.iterator().next() instanceof MethodInsnNode pack)
                || !pack.owner.equals(reflection.name) || pack.getOpcode() != INVOKESTATIC || !pack.desc.endsWith(")[Ljava/lang/Object;")) return "arguments not packed by a helper";
            var packed = Type.getArgumentTypes(pack.desc);
            if (packed.length != parameterTypes.size()) return "packed count differs";
            for (int k = 0; k < packed.length; k++) {
                boolean packedObject = packed[k].getSort() >= Type.ARRAY;
                boolean targetObject = parameterTypes.get(k).getSort() >= Type.ARRAY;
                if (packedObject != targetObject || !packedObject && !packed[k].equals(parameterTypes.get(k))) return "packed types differ";
            }
            // No other value on the stack comes from the same packing call.
            for (int k = 0; k < getterFrame.getStackSize(); k++) if (k != slot && getterFrame.getStack(k).insns.contains(pack)) return "packed array reused";
            packer = pack;
            if (!isStatic) castReceiver(direct, method, Arrays.asList(packed), owner, receiverTyped(node, method, consumed.getFirst(), getterFrame, owner));
            int opcode = isStatic ? INVOKESTATIC : declared.itf() ? INVOKEINTERFACE : declared.privateMember() && owner.equals(node.name) ? INVOKESPECIAL : INVOKEVIRTUAL;
            var written = new MethodInsnNode(opcode, owner, member.name(), desc, declared.itf());
            writtenBack.add(written);
            direct.add(written);
            Type result = Type.getReturnType(desc);
            if (result.getSort() == Type.VOID) {
                var next = SeedInterpreter.next(use);
                if (next != null && next.getOpcode() == POP) removed.add(next); else direct.add(new InsnNode(ACONST_NULL));
            } else box(direct, result);
        }
        direct.insert(reorder);
        // Replace: the getter pops its two longs; the shuffle and the use become the direct instruction.
        var getterPops = new InsnList(); getterPops.add(new InsnNode(POP2)); getterPops.add(new InsnNode(POP2));
        method.instructions.insertBefore(call, getterPops);
        method.instructions.remove(call); removed.add(call);
        for (var insn : shuffle) { method.instructions.remove(insn); removed.add(insn); }
        method.instructions.insertBefore(use, direct);
        method.instructions.remove(use); removed.add(use);
        if (packer != null) { method.instructions.remove(packer); removed.add(packer); }
        // The pop of a void method's null result.
        for (var insn : removed.stream().filter(i -> i.getOpcode() == POP && method.instructions.contains(i)).toList()) method.instructions.remove(insn);
        // The unpacked arguments and a boxed result may need more stack; recomputed when the class is written.
        method.maxStack += 8;
        return null;
    }

    /**
     * Whether the receiver (an original stack value) provably has the owner's type: {@code this}, a parameter, a new
     * object, a cast, a field or a call result declared as the owner or one of its subtypes. Otherwise a reflective
     * {@code Field.get} left it an {@code Object}, and the direct instruction needs a cast.
     */
    private boolean receiverTyped(ClassNode node, MethodNode method, Slot receiver, Frame<SourceValue> getterFrame, String owner) {
        if (!(receiver.id() instanceof Integer slot)) return false;
        var producers = getterFrame.getStack(slot).insns;
        if (producers.size() != 1) return false;
        var producer = producers.iterator().next();
        String type = switch (producer) {
            case VarInsnNode load when load.getOpcode() == ALOAD -> localType(node, method, load.var);
            case TypeInsnNode t when t.getOpcode() == NEW || t.getOpcode() == CHECKCAST -> t.desc;
            case FieldInsnNode f when Type.getType(f.desc).getSort() == Type.OBJECT -> Type.getType(f.desc).getInternalName();
            case MethodInsnNode m when Type.getReturnType(m.desc).getSort() == Type.OBJECT -> Type.getReturnType(m.desc).getInternalName();
            case LdcInsnNode ldc when ldc.cst instanceof String -> "java/lang/String";
            default -> null;
        };
        return type != null && assignable(type, owner);
    }

    /** The declared type of {@code this} or of a parameter held in a local the method never stores to; null otherwise. */
    private static String localType(ClassNode node, MethodNode method, int var) {
        for (var insn : method.instructions) if (insn instanceof VarInsnNode store && store.getOpcode() == ASTORE && store.var == var) return null;
        boolean isStatic = (method.access & ACC_STATIC) != 0;
        if (!isStatic && var == 0) return node.name;
        int slot = isStatic ? 0 : 1;
        for (var type : Type.getArgumentTypes(method.desc)) {
            if (slot == var) return type.getSort() == Type.OBJECT ? type.getInternalName() : null;
            slot += type.getSize();
        }
        return null;
    }

    private boolean assignable(String from, String to) {
        var seen = new HashSet<String>();
        var pending = new ArrayDeque<String>(List.of(from));
        while (!pending.isEmpty()) {
            String type = pending.poll();
            if (type.equals(to)) return true;
            if (!seen.add(type)) continue;
            var node = header(type);
            if (node == null) continue;
            if (node.superName != null) pending.add(node.superName);
            pending.addAll(node.interfaces);
        }
        return false;
    }

    /**
     * Casts the receiver, which lies under {@code above} (the values on top of it at the direct instruction): they are
     * stored in fresh locals, the receiver is cast, and they are loaded back.
     */
    private static void castReceiver(InsnList list, MethodNode method, List<Type> above, String owner, boolean typed) {
        if (typed) return;
        var slots = new int[above.size()];
        for (int k = above.size() - 1; k >= 0; k--) {
            slots[k] = method.maxLocals; method.maxLocals += above.get(k).getSize();
            list.add(new VarInsnNode(above.get(k).getOpcode(ISTORE), slots[k]));
        }
        list.add(new TypeInsnNode(CHECKCAST, owner));
        for (int k = 0; k < above.size(); k++) list.add(new VarInsnNode(above.get(k).getOpcode(ILOAD), slots[k]));
    }

    /** Replays one shuffle instruction on symbols; false for anything else. */
    /** Replays one stack instruction on symbolic slots; false when it is another instruction or the sizes do not fit. */
    static boolean shuffle(int op, List<Slot> stack) {
        int n = stack.size();
        switch (op) {
            case ACONST_NULL -> stack.add(new Slot(NULL, 1));
            case SWAP -> {
                if (n < 2 || stack.get(n - 1).size() != 1 || stack.get(n - 2).size() != 1) return false;
                Collections.swap(stack, n - 1, n - 2);
            }
            case POP -> { if (n < 1 || stack.get(n - 1).size() != 1) return false; stack.removeLast(); }
            case POP2 -> {
                if (n < 1) return false;
                if (stack.get(n - 1).size() == 2) stack.removeLast();
                else { if (n < 2 || stack.get(n - 2).size() != 1) return false; stack.removeLast(); stack.removeLast(); }
            }
            case DUP -> { if (n < 1 || stack.get(n - 1).size() != 1) return false; stack.add(stack.get(n - 1)); }
            case DUP2 -> {
                if (n < 1) return false;
                if (stack.get(n - 1).size() == 2) stack.add(stack.get(n - 1));
                else { if (n < 2 || stack.get(n - 2).size() != 1) return false; stack.add(stack.get(n - 2)); stack.add(stack.get(n - 2)); }
            }
            case DUP_X1 -> {
                if (n < 2 || stack.get(n - 1).size() != 1 || stack.get(n - 2).size() != 1) return false;
                stack.add(n - 2, stack.get(n - 1));
            }
            case DUP_X2 -> {
                if (n < 2 || stack.get(n - 1).size() != 1) return false;
                if (stack.get(n - 2).size() == 2) stack.add(n - 2, stack.get(n - 1));
                else { if (n < 3 || stack.get(n - 3).size() != 1) return false; stack.add(n - 3, stack.get(n - 1)); }
            }
            case DUP2_X1 -> {
                if (n < 2) return false;
                if (stack.get(n - 1).size() == 2) { if (stack.get(n - 2).size() != 1) return false; stack.add(n - 2, stack.get(n - 1)); }
                else {
                    if (n < 3 || stack.get(n - 2).size() != 1 || stack.get(n - 3).size() != 1) return false;
                    var a = stack.get(n - 1); var b = stack.get(n - 2);
                    stack.add(n - 3, a); stack.add(n - 3, b);
                }
            }
            case DUP2_X2 -> {
                if (n < 2) return false;
                var v1 = stack.get(n - 1);
                if (v1.size() == 2) {
                    var v2 = stack.get(n - 2);
                    if (v2.size() == 2) stack.add(n - 2, v1);
                    else { if (n < 3 || stack.get(n - 3).size() != 1) return false; stack.add(n - 3, v1); }
                } else {
                    if (n < 3 || stack.get(n - 2).size() != 1) return false;
                    var v2 = stack.get(n - 2);
                    if (stack.get(n - 3).size() == 2) { stack.add(n - 3, v1); stack.add(n - 3, v2); }
                    else { if (n < 4 || stack.get(n - 4).size() != 1) return false; stack.add(n - 4, v1); stack.add(n - 4, v2); }
                }
            }
            default -> { return false; }
        }
        return true;
    }

    private static boolean targeted(MethodNode method, LabelNode label) {
        for (var insn : method.instructions) {
            if (insn instanceof JumpInsnNode jump && jump.label == label) return true;
            if (insn instanceof TableSwitchInsnNode table && (table.dflt == label || table.labels.contains(label))) return true;
            if (insn instanceof LookupSwitchInsnNode lookup && (lookup.dflt == label || lookup.labels.contains(label))) return true;
        }
        for (var block : method.tryCatchBlocks) if (block.handler == label) return true;
        return false;
    }

    private static boolean primitiveMatches(String suffix, Type type) {
        return switch (suffix) {
            case "Int" -> type == Type.INT_TYPE; case "Long" -> type == Type.LONG_TYPE; case "Boolean" -> type == Type.BOOLEAN_TYPE;
            case "Byte" -> type == Type.BYTE_TYPE; case "Char" -> type == Type.CHAR_TYPE; case "Short" -> type == Type.SHORT_TYPE;
            case "Float" -> type == Type.FLOAT_TYPE; case "Double" -> type == Type.DOUBLE_TYPE; default -> false;
        };
    }
    private static final Map<Integer,String> BOXES = Map.of(Type.INT, "java/lang/Integer", Type.LONG, "java/lang/Long", Type.BOOLEAN, "java/lang/Boolean",
        Type.BYTE, "java/lang/Byte", Type.CHAR, "java/lang/Character", Type.SHORT, "java/lang/Short", Type.FLOAT, "java/lang/Float", Type.DOUBLE, "java/lang/Double");
    /** What {@code invoke} and {@code Field.get} did to a primitive: box it. */
    private static void box(InsnList list, Type type) {
        String box = BOXES.get(type.getSort());
        if (box != null) list.add(new MethodInsnNode(INVOKESTATIC, box, "valueOf", "(" + type.getDescriptor() + ")L" + box + ";", false));
    }
    /** What {@code Field.set} did to a boxed value for a primitive field: unbox it. */
    private static void unbox(InsnList list, Type type) {
        String box = BOXES.get(type.getSort());
        if (box == null) { list.add(new TypeInsnNode(CHECKCAST, type.getSort() == Type.ARRAY ? type.getDescriptor() : type.getInternalName())); return; }
        list.add(new TypeInsnNode(CHECKCAST, box));
        list.add(new MethodInsnNode(INVOKEVIRTUAL, box, type.getClassName() + "Value", "()" + type.getDescriptor(), false));
    }

    // ---------------------------------------------------------------- types and members

    private static String internal(Object type) {
        if (type instanceof TypeRef ref && !ref.name().startsWith("[")) return ref.name().replace('.', '/');
        return null;
    }
    private static String descriptor(Object type) {
        if (type instanceof Class<?> primitive) return Type.getType(primitive).getDescriptor();
        if (type instanceof TypeRef ref) return ref.name().startsWith("[") ? ref.name().replace('.', '/') : "L" + ref.name().replace('.', '/') + ";";
        return null;
    }

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

    /** The field the finder found: declared by the owner or one of its super types. */
    private FieldNode field(String owner, String name, String desc) {
        var seen = new HashSet<String>();
        var pending = new ArrayDeque<String>(List.of(owner));
        while (!pending.isEmpty()) {
            String type = pending.poll();
            if (!seen.add(type)) continue;
            var node = header(type);
            if (node == null) continue;
            for (var field : node.fields) if (field.name.equals(name) && field.desc.equals(desc)) return field;
            if (node.superName != null) pending.add(node.superName);
            pending.addAll(node.interfaces);
        }
        return null;
    }

    record Declared(int access, boolean itf, boolean privateMember) {}
    /** The method the finder found, and whether the owner it is called on is an interface. */
    private Declared method(String owner, String name, String desc) {
        var start = header(owner);
        if (start == null) return null;
        boolean itf = (start.access & ACC_INTERFACE) != 0;
        var seen = new HashSet<String>();
        var pending = new ArrayDeque<String>(List.of(owner));
        while (!pending.isEmpty()) {
            String type = pending.poll();
            if (!seen.add(type)) continue;
            var node = header(type);
            if (node == null) continue;
            for (var m : node.methods) if (m.name.equals(name) && m.desc.equals(desc)) return new Declared(m.access, itf, (m.access & ACC_PRIVATE) != 0);
            if (node.superName != null) pending.add(node.superName);
            pending.addAll(node.interfaces);
        }
        return null;
    }

    // ---------------------------------------------------------------- keys passed on

    /** The jar declaration a call reaches: the named owner or the nearest super class declaring it; null outside the jar. */
    private String declaration(String owner, String name, String desc) {
        var seen = new HashSet<String>();
        for (String type = owner; type != null && seen.add(type); ) {
            var node = nodes.get(type);
            if (node == null) return null;
            for (var method : node.methods) if (method.name.equals(name) && method.desc.equals(desc)) return SkidModel.key(type, name, desc);
            type = node.superName;
        }
        return null;
    }

    /** Constant arguments of the calls to the jar's methods, and the keys packed into the arrays they pass. */
    private void collectCalls(MethodNode method, ZkmKeys.Result analysis, Map<String,Map<Integer,Set<Object>>> calls,
                              Map<String,Map<Integer,Set<Object>>> packedCalls) {
        var insns = method.instructions.toArray();
        var frames = analysis.frames();
        for (int i = 0; i < insns.length; i++) {
            if (!(insns[i] instanceof MethodInsnNode call) || frames[i] == null) continue;
            String key = declaration(call.owner, call.name, call.desc);
            if (key == null) continue;
            var types = Type.getArgumentTypes(call.desc);
            var slots = calls.computeIfAbsent(key, k -> new HashMap<>());
            int slot = call.getOpcode() == INVOKESTATIC ? 0 : 1;
            for (int k = 0; k < types.length; k++) {
                Object value = frames[i].getStack(frames[i].getStackSize() - types.length + k).value();
                boolean primitive = types[k].getSort() >= Type.BOOLEAN && types[k].getSort() <= Type.LONG && types[k].getSort() != Type.FLOAT;
                if (primitive) slots.computeIfAbsent(slot, s -> new HashSet<>()).add(value instanceof Integer || value instanceof Long ? value : "unknown");
                if (types.length == 1 && types[k].getDescriptor().equals("[Ljava/lang/Object;")) {
                    // Per index of the array: the key the caller stored there; index -1 marks an array of unknown origin.
                    var indices = packedCalls.computeIfAbsent(key, s -> new HashMap<>());
                    if (value instanceof ZkmKeys.Packed packed && analysis.packedKeys().containsKey(packed.creation()))
                        analysis.packedKeys().get(packed.creation()).forEach((index, packedKey) -> indices.computeIfAbsent(index, s -> new HashSet<>()).add(packedKey));
                    else indices.computeIfAbsent(-1, s -> new HashSet<>()).add("unknown");
                }
                slot += types[k].getSize();
            }
        }
    }

    /** A parameter (or packed key) every call to a declaration passes as the same constant becomes known for it. */
    private void learn(Map<String,Map<Integer,Set<Object>>> calls, Map<String,Map<Integer,Set<Object>>> packedCalls,
                       Map<String,Map<Integer,Object>> parameters, Map<String,Map<Integer,Object>> packedKeys) {
        for (var node : nodes.values())
            for (var method : node.methods) {
                String key = SkidModel.key(node.name, method.name, method.desc);
                var slots = calls.get(key);
                if (slots != null) {
                    for (var entry : slots.entrySet())
                        if (entry.getValue().size() == 1 && !entry.getValue().contains("unknown"))
                            parameters.computeIfAbsent(key, k -> new HashMap<>()).putIfAbsent(entry.getKey(), entry.getValue().iterator().next());
                }
                var packed = packedCalls.get(key);
                if (packed != null && !packed.containsKey(-1))
                    for (var entry : packed.entrySet())
                        if (entry.getValue().size() == 1)
                            packedKeys.computeIfAbsent(key, k -> new HashMap<>()).putIfAbsent(entry.getKey(), entry.getValue().iterator().next());
            }
    }

    /** {@code catch (InvocationTargetException e) { throw e.getTargetException(); }} around code that no longer reflects. */
    private int removeUnwrappers(MethodNode method) {
        int removed = 0;
        for (var it = method.tryCatchBlocks.iterator(); it.hasNext(); ) {
            var block = it.next();
            if (!"java/lang/reflect/InvocationTargetException".equals(block.type)) continue;
            var first = SeedInterpreter.next(block.handler);
            if (!(first instanceof MethodInsnNode get) || !get.name.equals("getTargetException")) continue;
            boolean reflective = false;
            for (AbstractInsnNode insn = block.start; insn != null && insn != block.end; insn = insn.getNext())
                if (insn instanceof MethodInsnNode call && call.owner.equals("java/lang/reflect/Method") && call.name.equals("invoke") && !writtenBack.contains(call)) reflective = true;
            if (reflective) continue;
            it.remove();
            removed++;
        }
        return removed;
    }

    /**
     * A method that keeps a reflective access (its key unknown: nothing in the jar calls it) throws the reflection's checked
     * exceptions, which Zelix's rewrite let through undeclared: legal bytecode, but no Java source. Such a method that
     * nothing calls and that overrides nothing declares {@code throws Exception}, so its source compiles as its bytecode
     * runs. Returns the number of methods.
     */
    private int declareReflectiveThrows(ClassNode reflection, Set<String> changed) {
        var called = new HashSet<String>();
        for (var node : nodes.values())
            for (var method : node.methods)
                for (var insn : method.instructions)
                    if (insn instanceof MethodInsnNode call) called.add(call.name + call.desc);
                    else if (insn instanceof InvokeDynamicInsnNode indy && indy.bsmArgs != null)
                        for (Object arg : indy.bsmArgs) if (arg instanceof Handle handle) called.add(handle.getName() + handle.getDesc());
        int count = 0;
        for (var node : nodes.values()) {
            if (node == reflection) continue;
            for (var method : node.methods) {
                boolean reflective = false;
                for (var insn : method.instructions)
                    if (insn instanceof MethodInsnNode call && call.owner.equals(reflection.name)
                        && (call.desc.equals("(JJ)Ljava/lang/reflect/Method;") || call.desc.equals("(JJ)Ljava/lang/reflect/Field;"))) reflective = true;
                if (!reflective || method.name.startsWith("<") || called.contains(method.name + method.desc) || overrides(node, method)) continue;
                if (method.exceptions.contains("java/lang/Exception") || method.exceptions.contains("java/lang/Throwable")) continue;
                method.exceptions.add("java/lang/Exception");
                changed.add(node.name);
                count++;
            }
        }
        return count;
    }

    /** Whether a supertype (the jar's or the JDK's) declares the method, which a {@code throws} clause must then match. */
    private boolean overrides(ClassNode node, MethodNode method) {
        if ((method.access & (ACC_STATIC | ACC_PRIVATE)) != 0) return false;
        var pending = new ArrayDeque<String>();
        if (node.superName != null) pending.add(node.superName);
        pending.addAll(node.interfaces);
        var seen = new HashSet<String>();
        while (!pending.isEmpty()) {
            String type = pending.pop();
            if (!seen.add(type)) continue;
            var header = header(type);
            if (header == null) return true; // unknown: assume it might
            for (var declared : header.methods) if (declared.name.equals(method.name) && declared.desc.equals(method.desc)) return true;
            if (header.superName != null) pending.add(header.superName);
            pending.addAll(header.interfaces);
        }
        return false;
    }

    // ---------------------------------------------------------------- initializers

    /**
     * The obfuscator's initializer leaves each {@code <clinit>}: it starts where the program's code started (or is empty
     * when the initializer ran to its return). Fields the skipped code wrote and something still reads are set again
     * first when the interpreter's value is a constant; any other such field, or a program local the skipped code set,
     * keeps the initializer as it is. Private static fields it wrote that nothing names any more go.
     */
    /** What a partial initializer left: the static fields it wrote ({@code name:desc}) and their values. */
    record InitView(Set<String> written, Map<String,Object> statics) {}

    private void stripInitializers(Map<String,AidsEvaluator> evaluators, ClassNode reflection, Set<String> changed) {
        var views = new LinkedHashMap<String,InitView>();
        for (var entry : evaluators.entrySet()) {
            // The reflection class's tables as its initializer left them, before any lookup decrypted entries in place.
            if (entry.getKey().equals(reflection == null ? null : reflection.name)) entry.getValue().restore(reflectionState);
            views.put(entry.getKey(), new InitView(entry.getValue().written, entry.getValue().statics));
        }
        stripInitializers(views, changed);
    }

    private void stripInitializers(Map<String,InitView> views, Set<String> changed) {
        int stripped = 0, kept = 0, fieldsRemoved = 0;
        for (var entry : views.entrySet()) {
            var node = nodes.get(entry.getKey());
            var evaluator = entry.getValue();
            MethodNode clinit = clinit(node);
            if (clinit == null || !starts.containsKey(node.name) || evaluator.written().isEmpty()) continue;
            var backup = new MethodNode(ASM9, clinit.access, clinit.name, clinit.desc, clinit.signature,
                clinit.exceptions == null ? null : clinit.exceptions.toArray(String[]::new));
            clinit.accept(backup);
            LabelNode start = starts.get(node.name);
            if (start == null) {
                clinit.instructions.clear(); clinit.tryCatchBlocks.clear();
                if (clinit.localVariables != null) clinit.localVariables.clear();
                clinit.instructions.add(new InsnNode(RETURN));
            } else if (clinit.instructions.contains(start)) {
                clinit.instructions.insert(new JumpInsnNode(GOTO, start));
            } else continue;
            PureDeadCode.clean(node.name, clinit, classKeys.keySet());
            var read = new HashSet<String>();
            for (var other : nodes.values())
                for (var method : other.methods)
                    for (var insn : method.instructions)
                        if (insn instanceof FieldInsnNode get && get.getOpcode() == GETSTATIC && get.owner.equals(node.name)) read.add(get.name + ":" + get.desc);
            var prologue = new InsnList();
            var arrays = Collections.newSetFromMap(new IdentityHashMap<Object,Boolean>());
            boolean keep = false;
            for (String field : new TreeSet<>(evaluator.written())) {
                if (!read.contains(field)) continue;
                String name = field.substring(0, field.indexOf(':')), desc = field.substring(field.indexOf(':') + 1);
                FieldNode declared = null;
                for (var candidate : node.fields) if (candidate.name.equals(name) && candidate.desc.equals(desc)) declared = candidate;
                if (declared != null && declared.value != null) continue; // a ConstantValue field
                var push = evaluator.statics().containsKey(field) ? constant(evaluator.statics().get(field), desc, arrays) : null;
                if (push == null) { keep = true; refuse(node.name + ".<clinit> kept: " + field + " is still read and not a constant" + sample(evaluator.statics().get(field))); break; }
                prologue.add(push);
                prologue.add(new FieldInsnNode(PUTSTATIC, node.name, name, desc));
            }
            if (!keep && uninitializedRead(node.name, clinit)) { keep = true; refuse(node.name + ".<clinit> kept: a local of the initializer is still read"); }
            if (keep) {
                node.methods.set(node.methods.indexOf(clinit), backup);
                kept++;
                continue;
            }
            clinit.instructions.insert(prologue);
            stripped++;
            changed.add(node.name);
            var named = new HashSet<String>();
            for (var other : nodes.values())
                for (var method : other.methods)
                    for (var insn : method.instructions)
                        if (insn instanceof FieldInsnNode use && use.owner.equals(node.name)) named.add(use.name + ":" + use.desc);
            int before = node.fields.size();
            node.fields.removeIf(field -> (field.access & (ACC_STATIC | ACC_PRIVATE)) == (ACC_STATIC | ACC_PRIVATE)
                && evaluator.written().contains(field.name + ":" + field.desc) && !named.contains(field.name + ":" + field.desc));
            fieldsRemoved += before - node.fields.size();
            boolean onlyReturn = true;
            for (var insn : clinit.instructions) if (insn.getOpcode() >= 0 && insn.getOpcode() != RETURN) onlyReturn = false;
            if (onlyReturn) node.methods.remove(clinit);
        }
        count("initializers removed", stripped);
        count("initializers kept (a table still read)", kept);
        count("initializer fields removed", fieldsRemoved);
    }

    private static final int MAX_ELEMENTS = 2000;

    /** A primitive's class the interpreter holds, as a constant: {@code Integer.TYPE} for {@code int}. */
    private static AbstractInsnNode classConstant(Class<?> type) {
        String box = switch (type.getName()) {
            case "int" -> "java/lang/Integer"; case "long" -> "java/lang/Long"; case "boolean" -> "java/lang/Boolean";
            case "byte" -> "java/lang/Byte"; case "char" -> "java/lang/Character"; case "short" -> "java/lang/Short";
            case "float" -> "java/lang/Float"; case "double" -> "java/lang/Double"; default -> "java/lang/Void";
        };
        return new FieldInsnNode(GETSTATIC, box, "TYPE", "Ljava/lang/Class;");
    }

    /** The kinds of value an array holds, for the report. */
    private static String sample(Object value) {
        if (value == null || !value.getClass().isArray()) return " (" + (value == null ? "null" : value.getClass().getSimpleName()) + ")";
        var kinds = new TreeSet<String>();
        for (int k = 0; k < java.lang.reflect.Array.getLength(value); k++) {
            Object item = java.lang.reflect.Array.get(value, k);
            kinds.add(item == null ? "null" : item.getClass().getSimpleName());
        }
        return " (" + value.getClass().getSimpleName() + " of " + kinds + ")";
    }

    /**
     * The code pushing an interpreter value for a field of type {@code desc}, or null when it is not a constant: a
     * primitive, a string, null, or an array of those (filled element by element, at most {@value #MAX_ELEMENTS}). An
     * array held by two fields is refused ({@code arrays}: those seen), since two copies would no longer be one.
     */
    private static InsnList constant(Object value, String desc, Set<Object> arrays) {
        var list = new InsnList();
        if (value == null) {
            if (!desc.startsWith("L") && !desc.startsWith("[")) return null;
            list.add(new InsnNode(ACONST_NULL));
            return list;
        }
        AbstractInsnNode single = switch (desc) {
            case "I", "S", "B", "C", "Z" -> value instanceof Integer number ? MethodRewriter.push(number) : null;
            case "J" -> value instanceof Long number ? new LdcInsnNode(number) : null;
            case "F" -> value instanceof Float number ? new LdcInsnNode(number) : null;
            case "D" -> value instanceof Double number ? new LdcInsnNode(number) : null;
            case "Ljava/lang/String;", "Ljava/lang/Object;" -> value instanceof String text ? new LdcInsnNode(text) : null;
            default -> null;
        };
        if (single != null) { list.add(single); return list; }
        if (!desc.startsWith("[") || !value.getClass().isArray() || !arrays.add(value)) return null;
        int length = java.lang.reflect.Array.getLength(value);
        if (length > MAX_ELEMENTS) return null;
        Type element = Type.getType(desc.substring(1));
        list.add(MethodRewriter.push(length));
        switch (element.getSort()) {
            case Type.INT, Type.LONG, Type.BYTE, Type.CHAR, Type.SHORT, Type.BOOLEAN, Type.FLOAT, Type.DOUBLE ->
                list.add(new IntInsnNode(NEWARRAY, switch (element.getSort()) {
                    case Type.INT -> T_INT; case Type.LONG -> T_LONG; case Type.BYTE -> T_BYTE; case Type.CHAR -> T_CHAR;
                    case Type.SHORT -> T_SHORT; case Type.BOOLEAN -> T_BOOLEAN; case Type.FLOAT -> T_FLOAT; default -> T_DOUBLE;
                }));
            case Type.OBJECT -> {
                String type = element.getInternalName();
                if (!type.equals("java/lang/String") && !type.equals("java/lang/Object") && !type.equals("java/lang/Integer") && !type.equals("java/lang/Class")) return null;
                list.add(new TypeInsnNode(ANEWARRAY, type));
            }
            default -> { return null; }
        }
        for (int k = 0; k < length; k++) {
            Object item = java.lang.reflect.Array.get(value, k);
            AbstractInsnNode push;
            int store;
            switch (element.getSort()) {
                case Type.LONG -> { if ((Long) item == 0L) continue; push = new LdcInsnNode(item); store = LASTORE; }
                case Type.FLOAT -> { if ((Float) item == 0f) continue; push = new LdcInsnNode(item); store = FASTORE; }
                case Type.DOUBLE -> { if ((Double) item == 0d) continue; push = new LdcInsnNode(item); store = DASTORE; }
                case Type.BOOLEAN -> { if (!(Boolean) item) continue; push = new InsnNode(ICONST_1); store = BASTORE; }
                case Type.CHAR -> { if ((Character) item == 0) continue; push = MethodRewriter.push((Character) item); store = CASTORE; }
                case Type.BYTE -> { if ((Byte) item == 0) continue; push = MethodRewriter.push((Byte) item); store = BASTORE; }
                case Type.SHORT -> { if ((Short) item == 0) continue; push = MethodRewriter.push((Short) item); store = SASTORE; }
                case Type.INT -> { if ((Integer) item == 0) continue; push = MethodRewriter.push((Integer) item); store = IASTORE; }
                default -> {
                    if (item == null) continue;
                    store = AASTORE;
                    String type = element.getInternalName();
                    if (item instanceof String text && (type.equals("java/lang/String") || type.equals("java/lang/Object"))) push = new LdcInsnNode(text);
                    else if (item instanceof Class<?> c && c.isPrimitive() && (type.equals("java/lang/Class") || type.equals("java/lang/Object"))) push = classConstant(c);
                    else if (item instanceof Integer && (type.equals("java/lang/Integer") || type.equals("java/lang/Object"))) push = null;
                    else return null;
                    if (push == null) {
                        // A boxed Integer or Long in an Integer[], Long[] or Object[] table.
                        list.add(new InsnNode(DUP)); list.add(MethodRewriter.push(k)); list.add(MethodRewriter.push((Integer) item));
                        list.add(new MethodInsnNode(INVOKESTATIC, "java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;", false));
                        list.add(new InsnNode(AASTORE));
                        continue;
                    }
                }
            }
            list.add(new InsnNode(DUP));
            list.add(MethodRewriter.push(k));
            list.add(push);
            list.add(new InsnNode(store));
        }
        return list;
    }

    /** Whether reachable code loads a local no path has stored (one the skipped initializer set), or the analysis fails. */
    private static boolean uninitializedRead(String owner, MethodNode method) {
        Frame<BasicValue>[] frames;
        try { frames = new Analyzer<>(new BasicInterpreter()).analyze(owner, method); }
        catch (AnalyzerException malformed) { return true; }
        var insns = method.instructions.toArray();
        for (int i = 0; i < insns.length; i++) {
            if (frames[i] == null) continue;
            int var = insns[i] instanceof VarInsnNode load && (load.getOpcode() >= ILOAD && load.getOpcode() <= ALOAD || load.getOpcode() == RET) ? load.var
                : insns[i] instanceof IincInsnNode inc ? inc.var : -1;
            if (var >= 0 && (var >= frames[i].getLocals() || frames[i].getLocal(var) == BasicValue.UNINITIALIZED_VALUE)) return true;
        }
        return false;
    }

    /** Whether any class but {@code self} names the class: a member, a type instruction, a constant or a descriptor. */
    private boolean referenced(String name, ClassNode self) {
        String descriptor = "L" + name + ";";
        for (var node : nodes.values()) {
            if (node == self) continue;
            if (name.equals(node.superName) || node.interfaces.contains(name)) return true;
            for (var field : node.fields) if (field.desc.contains(descriptor)) return true;
            for (var method : node.methods) {
                if (method.desc.contains(descriptor)) return true;
                for (var insn : method.instructions) {
                    if (insn instanceof MethodInsnNode call && (call.owner.equals(name) || call.desc.contains(descriptor))) return true;
                    if (insn instanceof FieldInsnNode use && (use.owner.equals(name) || use.desc.contains(descriptor))) return true;
                    if (insn instanceof TypeInsnNode type && (type.desc.equals(name) || type.desc.contains(descriptor))) return true;
                    if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof Type type && type.getDescriptor().contains(descriptor)) return true;
                    if (insn instanceof MultiANewArrayInsnNode multi && multi.desc.contains(descriptor)) return true;
                    if (insn instanceof InvokeDynamicInsnNode indy && (indy.desc.contains(descriptor) || String.valueOf(indy.bsmArgs == null ? "" : Arrays.asList(indy.bsmArgs)).contains(name))) return true;
                }
                for (var block : method.tryCatchBlocks) if (name.equals(block.type)) return true;
            }
        }
        return false;
    }
}
