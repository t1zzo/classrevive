package dev.classrevive.transforms.skid;

import dev.classrevive.transforms.TransformResult;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;
import java.util.*;

/**
 * Phase 2CN: reverses Skidfuscator's flow obfuscation over a whole jar, statically. It recovers the per-block seed
 * (class seed fields, injected seed parameters, {@code Integer.parseInt} and {@code Random(long).nextInt()} seeds, the
 * hash functions of the factory class and pure JDK methods, evaluated on constants), folds the encrypted numbers,
 * decides the opaque predicates and seed switches, turns the caught-throw gotos back into jumps, removes the dead traps,
 * and then the seed fields, the hash factory and the XOR helpers once nothing uses them. Target code is never executed.
 * The obfuscator's own idea of "seed" is recognized by taint (see {@link SV#tainted}), so the program's own constants and
 * branches are left as they are.
 */
public final class SkidfuscatorReverser implements Opcodes {
    public static final String ID = "skidfuscator";

    /** Totals for the report: named counts in pass order, refused methods, kept parameters and classes that could not be written (up to 20 each). */
    public record Report(boolean detected, Map<String,Integer> counts, List<String> refused, List<String> parametersKept, List<String> writeFailures) {
        public Report { counts = Collections.unmodifiableMap(new LinkedHashMap<>(counts)); refused = List.copyOf(refused); parametersKept = List.copyOf(parametersKept);
            writeFailures = List.copyOf(writeFailures); }
        public int count(String name) { return counts.getOrDefault(name, 0); }
        @Override public String toString() {
            var out = new StringBuilder("Skidfuscator reversal: ").append(detected ? "detected" : "not detected");
            counts.forEach((name, value) -> out.append(System.lineSeparator()).append("  ").append(name).append(": ").append(value));
            if (!refused.isEmpty()) out.append(System.lineSeparator()).append("  methods the analysis refused: ").append(refused);
            if (!parametersKept.isEmpty()) out.append(System.lineSeparator()).append("  injected parameters kept: ").append(parametersKept);
            if (!writeFailures.isEmpty()) out.append(System.lineSeparator()).append("  classes kept as they were (write failed): ").append(writeFailures);
            return out.toString();
        }
    }
    public record Result(Map<String,byte[]> entries, Report report, List<TransformResult.Change> changes) {}

    /** {@code entries}: jar entry name to bytes; only {@code .class} entries are read, others are returned as they are. */
    public Result apply(Map<String,byte[]> entries, int maxChanges) {
        var nodes = new LinkedHashMap<String,ClassNode>();
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
        if (!detected(nodes)) {
            return new Result(entries, new Report(false, Map.of("classes", nodes.size()), List.of(), List.of(), List.of()), List.of());
        }
        var model = new SkidModel(nodes);
        model.build();
        var interpreter = model.interpreter();
        // A call whose result is dropped goes when it has no side effect and, on its literal arguments, evaluates without error.
        // (null arguments: the analysis already evaluated the call on every input it can see.)
        // Only the obfuscator's functions and the JDK's allowlisted pure classes are dropped this way (never a call of the program).
        var rewriter = new MethodRewriter(interpreter, (call, args) ->
            (model.hashFunction(call.owner, call.name, call.desc) || PureEvaluator.Allowlist.CLASSES.contains(call.owner))
            && model.pure.pureBody(call.owner, call.name, call.desc)
            && (args == null || model.pure.evaluate(call.owner, call.name, call.desc, args).isPresent()));
        var total = new MethodRewriter.Stats();
        var changed = new LinkedHashSet<String>();
        var changes = new ArrayList<TransformResult.Change>();
        int methodsChanged = 0;
        var rewritten = new LinkedHashMap<MethodNode,String>(); // method -> owner, for the MapleIR clean-up
        boolean sdk = nodes.containsKey("sdk/SDK");
        for (var node : nodes.values()) {
            if (sdk && node.name.startsWith("sdk/")) continue;
            for (var method : node.methods) {
                if (method.instructions.size() == 0) continue;
                var stats = new MethodRewriter.Stats();
                var parameters = model.seedParameters.getOrDefault(SkidModel.key(node.name, method.name, method.desc), Map.of());
                if (rewriter.rewrite(node.name, method, parameters, stats)) {
                    changed.add(node.name); methodsChanged++; rewritten.put(method, node.name);
                    if (changes.size() < maxChanges)
                        changes.add(new TransformResult.Change(ID, node.name, method.name, method.desc, -1, 0, stats.folded, stats.branches, stats.total()));
                }
                total.add(stats);
            }
        }
        // Seed fields nothing reads any more: drop their writes and the fields.
        int seedFieldsRemoved = 0;
        var reads = new HashSet<String>();
        for (var node : nodes.values())
            for (var method : node.methods)
                for (var insn : method.instructions)
                    if (insn instanceof FieldInsnNode field && (insn.getOpcode() == GETSTATIC || insn.getOpcode() == GETFIELD))
                        reads.add(field.owner + "." + field.name);
        var seeds = new HashSet<String>(model.staticSeeds.keySet()); seeds.addAll(model.instanceSeeds.keySet());
        for (var node : nodes.values()) {
            boolean touched = false;
            for (var method : node.methods) {
                // Phase 2DC: per method; the class-wide flag re-ran the rewriter on every later method, abstract ones included.
                boolean methodTouched = false;
                for (var insn : method.instructions.toArray()) {
                    if (!(insn instanceof FieldInsnNode field) || !seeds.contains(field.owner + "." + field.name) || reads.contains(field.owner + "." + field.name)) continue;
                    if (insn.getOpcode() == PUTSTATIC) { method.instructions.set(insn, new InsnNode(POP)); methodTouched = true; }
                    else if (insn.getOpcode() == PUTFIELD) { method.instructions.insertBefore(insn, new InsnNode(POP)); method.instructions.set(insn, new InsnNode(POP)); methodTouched = true; }
                }
                if (methodTouched) { rewriter.rewrite(node.name, method, Map.of(), total); touched = true; }
            }
            int before = node.fields.size();
            node.fields.removeIf(field -> seeds.contains(node.name + "." + field.name) && !reads.contains(node.name + "." + field.name));
            seedFieldsRemoved += before - node.fields.size();
            if (touched || before != node.fields.size()) changed.add(node.name);
        }
        // Phase 2CO: strings (the seeds are literals now) and the ASCII-art field.
        var strings = new StringRecovery();
        strings.apply(nodes, changed);
        // Phase 2CP: the injected seed parameters, now unread.
        var parameters = new ParameterRecovery();
        parameters.apply(nodes, model, changed);
        // Phase 2CR: the SDK's hashed type checks and string comparisons (recovered literals also key string switches).
        var sdkRemoved = new HashSet<String>();
        var sdkRecovery = new SdkRecovery();
        sdkRecovery.apply(nodes, changed, sdkRemoved);
        // Phase 2CQ: switches on a hash of the key.
        var switches = new SwitchRecovery();
        switches.apply(nodes, model.pure, changed);
        // Phase 2CS: MapleIR's shapes in the methods Skidfuscator rebuilt (never in untouched javac code).
        var cleanup = new MapleCleanup();
        for (var entry : rewritten.entrySet()) {
            var method = entry.getKey();
            if (nodes.get(entry.getValue()).methods.contains(method) && cleanup.apply(entry.getValue(), method)) changed.add(entry.getValue());
        }
        // Unreferenced XOR helpers and hash factories, and empty static initializers.
        int helpersRemoved = 0, factoriesRemoved = 0;
        var referenced = references(nodes);
        for (var node : nodes.values()) {
            int before = node.methods.size();
            node.methods.removeIf(method -> xorHelper(method) && !referenced.contains(SkidModel.key(node.name, method.name, method.desc)));
            helpersRemoved += before - node.methods.size();
            if (node.methods.removeIf(SkidfuscatorReverser::emptyClinit)) changed.add(node.name);
            if (before != node.methods.size()) changed.add(node.name);
        }
        var removedClasses = new HashSet<String>(sdkRemoved);
        var referencedClasses = new HashSet<String>();
        for (String reference : references(nodes)) referencedClasses.add(reference.substring(0, reference.lastIndexOf('.', reference.indexOf('('))));
        for (var node : nodes.values())
            if (factory(node, model.pure) && !referencedClasses.contains(node.name)) { removedClasses.add(node.name); factoriesRemoved++; }

        var output = new LinkedHashMap<String,byte[]>();
        int writeFailures = 0; var writeFailed = new ArrayList<String>();
        var hierarchy = new Hierarchy(nodes);
        for (var entry : entries.entrySet()) {
            String name = entry.getKey();
            String internal = null;
            if (name.endsWith(".class")) for (var e : entryOf.entrySet()) if (e.getValue().equals(name)) { internal = e.getKey(); break; }
            if (internal != null && removedClasses.contains(internal)) continue;
            if (internal == null || !changed.contains(internal)) { output.put(name, entry.getValue()); continue; }
            var node = nodes.get(internal);
            try {
                int flags = (node.version & 0xFFFF) >= V1_6 ? ClassWriter.COMPUTE_FRAMES : ClassWriter.COMPUTE_MAXS;
                var writer = hierarchy.writer(flags);
                node.accept(writer);
                output.put(name, writer.toByteArray());
            } catch (RuntimeException error) {
                writeFailures++; if (writeFailed.size() < 20) writeFailed.add(Hierarchy.failure(internal, error));
                output.put(name, entry.getValue());
            }
        }
        var counts = new LinkedHashMap<String,Integer>();
        counts.put("classes", nodes.size()); counts.put("methods changed", methodsChanged);
        counts.put("seed values folded", total.folded); counts.put("opaque branches decided", total.branches);
        counts.put("of which by the trap invariant (unevaluable hash)", total.assumed);
        counts.put("switches pruned", total.switchesPruned); counts.put("dead instructions removed", total.dead);
        counts.put("throw-gotos turned into jumps", total.throwGotos); counts.put("idle try ranges removed", total.handlers);
        counts.put("seed stores removed", total.storesDropped); counts.put("peephole edits", total.peephole);
        counts.put("methods refused", total.unanalyzable);
        counts.put("seed fields", model.staticSeeds.size() + model.instanceSeeds.size()); counts.put("seed fields removed", seedFieldsRemoved);
        counts.put("seed parameters", model.seedParameters.size()); counts.put("hash factories removed", factoriesRemoved);
        counts.put("XOR helpers removed", helpersRemoved);
        counts.put("strings decrypted", strings.decrypted); counts.put("strings not decrypted", strings.failed);
        counts.put("decryptors removed", strings.decryptorsRemoved); counts.put("array generators removed", strings.generatorsRemoved);
        counts.put("key fields removed", strings.fieldsRemoved); counts.put("ASCII-art fields removed", strings.ahegaoRemoved);
        counts.put("injected parameters removed", parameters.methods); counts.put("calls rewritten", parameters.calls);
        counts.put("names restored", parameters.renamed); counts.put("broken calls fixed", parameters.brokenCalls);
        counts.put("type checks recovered", sdkRecovery.typeChecks); counts.put("type checks not recovered", sdkRecovery.typeUnresolved);
        counts.put("equals literals recovered", sdkRecovery.equalsRecovered); counts.put("equals literals not recovered", sdkRecovery.equalsUnresolved);
        counts.put("SDK classes removed", sdkRecovery.sdkClassesRemoved);
        counts.put("hashed switches recovered", switches.recovered); counts.put("hashed switches ambiguous", switches.ambiguous);
        counts.put("hashed switches not matched", switches.unmatched);
        counts.put("stack spills removed", cleanup.spills); counts.put("constructions restored", cleanup.constructions);
        counts.put("dead stores removed", cleanup.deadStores); counts.put("zero comparisons restored", cleanup.comparisons);
        counts.put("class write failures", writeFailures);
        var report = new Report(true, counts, total.refused, parameters.kept, writeFailed);
        return new Result(output, report, changes);
    }

    /** Skidfuscator's seed updates ({@code ldc C; iload s; ixor; istore s}) in at least one method, or its SDK. */
    static boolean detected(Map<String,ClassNode> nodes) {
        if (nodes.containsKey("sdk/SDK")) return true;
        for (var node : nodes.values())
            for (var method : node.methods) {
                int updates = 0;
                for (var insn : method.instructions) {
                    if (insn.getOpcode() != IXOR) continue;
                    var load = SeedInterpreter.previous(insn); var constant = SeedInterpreter.previous(load); var store = SeedInterpreter.next(insn);
                    if (load instanceof VarInsnNode l && l.getOpcode() == ILOAD && SkidModel.literal(constant) != null
                        && store instanceof VarInsnNode s && s.getOpcode() == ISTORE && s.var == l.var) updates++;
                }
                if (updates >= 3) return true;
            }
        return false;
    }

    /** {@code owner.name desc} of every method referenced by an instruction or a method handle. */
    private static Set<String> references(Map<String,ClassNode> nodes) {
        var result = new HashSet<String>();
        for (var node : nodes.values())
            for (var method : node.methods)
                for (var insn : method.instructions) {
                    if (insn instanceof MethodInsnNode call) result.add(SkidModel.key(call.owner, call.name, call.desc));
                    else if (insn instanceof InvokeDynamicInsnNode indy) {
                        handle(indy.bsm, result);
                        for (Object arg : indy.bsmArgs) if (arg instanceof org.objectweb.asm.Handle h) handle(h, result);
                    } else if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof org.objectweb.asm.Handle h) handle(h, result);
                }
        return result;
    }
    private static void handle(org.objectweb.asm.Handle handle, Set<String> into) { into.add(SkidModel.key(handle.getOwner(), handle.getName(), handle.getDesc())); }

    /** {@code private static int x(int a, int b) { return b ^ a; }}, Skidfuscator's per-class XOR helper. */
    static boolean xorHelper(MethodNode method) {
        if (!method.desc.equals("(II)I") || (method.access & ACC_STATIC) == 0 || (method.access & ACC_PRIVATE) == 0) return false;
        var ops = new ArrayList<Integer>();
        for (var insn : method.instructions) if (insn.getOpcode() >= 0) ops.add(insn.getOpcode());
        return ops.equals(List.of(ILOAD, ILOAD, IXOR, IRETURN));
    }

    /** A class of only pure static {@code (I)I} methods and no fields: the hash factory. */
    static boolean factory(ClassNode node, PureEvaluator pure) {
        if (!node.fields.isEmpty() || node.methods.isEmpty() || (node.access & ACC_INTERFACE) != 0) return false;
        for (var method : node.methods) {
            if (method.name.equals("<init>") && method.desc.equals("()V")) continue;
            if (!method.desc.equals("(I)I") || (method.access & ACC_STATIC) == 0 || !pure.pureBody(node.name, method.name, method.desc)) return false;
        }
        return node.methods.stream().anyMatch(m -> m.desc.equals("(I)I"));
    }

    private static boolean emptyClinit(MethodNode method) {
        if (!method.name.equals("<clinit>")) return false;
        for (var insn : method.instructions) if (insn.getOpcode() >= 0 && insn.getOpcode() != RETURN) return false;
        return true;
    }

    static int argumentSlots(String desc) { int slots = 0; for (var t : Type.getArgumentTypes(desc)) slots += t.getSize(); return slots; }
}
