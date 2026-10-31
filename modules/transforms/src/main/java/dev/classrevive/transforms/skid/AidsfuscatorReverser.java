package dev.classrevive.transforms.skid;

import dev.classrevive.transforms.TransformResult;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/**
 * Phase 2CZ: reverses Aidsfuscator's salts and flow obfuscation over a whole jar, statically, with the seed machinery of
 * the Skidfuscator reversal ({@link SeedAnalyzer}, {@link MethodRewriter}). Aidsfuscator derives every key from values
 * the jar itself holds:
 * <ul>
 *   <li><b>hash integrity</b>: a class's {@code static final int} is set in {@code <clinit>} from a resource
 *   ({@code /<uuid>.bin}: the SHA-256 of each class file in hex, then a 4-byte value); the value is
 *   {@code stored ^ hex.hashCode() ^ param}, with {@code param} pushed by the call. The class files are hashed here;</li>
 *   <li><b>class salts</b>: a class's {@code public static final int} is set from the salt dispatcher's table, keyed by
 *   the FNV-1a hash of the class name: {@code table[fnv(name)] ^ fake}. The ordered variant, which also XORs the salt of
 *   the class whose initializer ran first (read from a stack trace), is left alone;</li>
 *   <li><b>method salts</b>: a trailing {@code int} parameter every call in the jar passes as a constant (after the
 *   callers are rewritten), or a local set from the class salt;</li>
 *   <li><b>flow ints</b> (a local updated per block), <b>flattening</b> (a dispatcher {@code switch} on a local set to a
 *   key before each {@code goto}) and the salted constants all become constants once these are known, and the dispatcher's
 *   gotos are threaded to their cases.</li>
 * </ul>
 * Aidsfuscator's post-processing keeps an int used five times or more in a local set at the start of the method; it is
 * put back in place first. The salt parameters are then removed from every declaration of their override group, the
 * salt and hash fields and their initializers go, and so do the salt dispatcher, the hash class and its resource once
 * nothing refers to them. Encrypted numbers, strings and the {@code invokedynamic} references are later phases.
 * Target code is never executed.
 */
public final class AidsfuscatorReverser implements Opcodes {
    public static final String ID = "aidsfuscator";
    static final long FNV_OFFSET = 0xcbf29ce484222325L, FNV_PRIME = 0x100000001b3L;
    private static final String CLASS_RETRIEVER = "(Ljava/lang/Object;I)I", HASH_RETRIEVER = "(Ljava/lang/Class;I)I";
    private static final int MAX_GLOBAL_ROUNDS = 12;

    /** Totals for the report: named counts in pass order, refused methods and classes that could not be written (up to 20 each). */
    public record Report(boolean detected, Map<String,Integer> counts, List<String> refused, List<String> parametersKept, List<String> writeFailures) {
        public Report { counts = Collections.unmodifiableMap(new LinkedHashMap<>(counts)); refused = List.copyOf(refused); parametersKept = List.copyOf(parametersKept);
            writeFailures = List.copyOf(writeFailures); }
        public int count(String name) { return counts.getOrDefault(name, 0); }
        @Override public String toString() {
            var out = new StringBuilder("Aidsfuscator reversal: ").append(detected ? "detected" : "not detected");
            counts.forEach((name, value) -> out.append(System.lineSeparator()).append("  ").append(name).append(": ").append(value));
            if (!refused.isEmpty()) out.append(System.lineSeparator()).append("  methods the analysis refused: ").append(refused);
            if (!parametersKept.isEmpty()) out.append(System.lineSeparator()).append("  salt parameters kept: ").append(parametersKept);
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
        var dispatchers = new LinkedHashSet<String>(); var hashClasses = new LinkedHashSet<String>(); var referenceClasses = new LinkedHashSet<String>();
        for (var node : nodes.values()) {
            if (saltDispatcher(node)) dispatchers.add(node.name);
            if (hashClass(node)) hashClasses.add(node.name);
            if (referenceClass(node)) referenceClasses.add(node.name);
        }
        if (dispatchers.isEmpty() && hashClasses.isEmpty() && referenceClasses.isEmpty())
            return new Result(entries, new Report(false, Map.of("classes", nodes.size()), List.of(), List.of(), List.of()), List.of());
        var artificial = new HashSet<String>(dispatchers); artificial.addAll(hashClasses); artificial.addAll(referenceClasses);

        // Integer reuse: put the constants back before anything reads the methods.
        int reusedInlined = 0;
        for (var node : nodes.values()) {
            if (artificial.contains(node.name)) continue;
            for (var method : node.methods) reusedInlined += inlineReusedIntegers(method);
        }

        var model = new SkidModel(nodes);
        var counts = new LinkedHashMap<String,Integer>();
        var changed = new LinkedHashSet<String>();
        // Hash integrity first: the class salts' fake numbers may be pushed through a hash field.
        var hashValues = hashFieldValues(nodes, entries, entryOf, hashClasses, model, counts);
        model.staticSeeds.putAll(hashValues);
        var saltValues = classSaltValues(nodes, dispatchers, model, counts);
        model.staticSeeds.putAll(saltValues);
        // Their initializers become constants: the <clinit> then runs without the dispatcher or the hash class.
        var seedFields = new HashSet<String>(saltValues.keySet()); seedFields.addAll(hashValues.keySet());
        for (var node : nodes.values()) {
            if (artificial.contains(node.name)) continue;
            var calls = new ArrayList<RetrieverCall>(retrieverCalls(node, hashClasses, HASH_RETRIEVER));
            calls.addAll(retrieverCalls(node, dispatchers, CLASS_RETRIEVER));
            for (var call : calls) {
                Integer value = model.staticSeeds.get(node.name + "." + call.field.name);
                if (value == null) continue;
                for (var insn = call.lookup; insn != call.field; ) { var next = insn.getNext(); if (insn.getOpcode() >= 0) call.clinit.instructions.remove(insn); insn = next; }
                call.clinit.instructions.insertBefore(call.field, new LdcInsnNode(value));
                changed.add(node.name);
            }
        }

        var rewriter = new MethodRewriter(model.interpreter(), (call, args) -> false);
        var total = new MethodRewriter.Stats();
        var methodsChanged = Collections.newSetFromMap(new IdentityHashMap<MethodNode,Boolean>());
        var changes = new ArrayList<TransformResult.Change>();
        var parameters = new AidsParameters(nodes, artificial);
        var references = new ArrayList<AidsReferences>();
        for (String name : referenceClasses) {
            var decoder = new AidsReferences(nodes.get(name), nodes);
            if (decoder.usable()) references.add(decoder);
        }
        var constants = new AidsConstants();
        int referencesDecoded = 0, decodedBefore = 0, saltsDeduced = 0, deducedBefore = 0, decrypted = 0, decryptedBefore = 0;
        // Rounds until nothing new is learned; then one more round may search the keys no salt reveals (methods nothing
        // in the jar calls), and the rounds go on while that decodes something.
        boolean search = false;
        var refused = new MethodRewriter.Stats();
        for (int round = 0; round < MAX_GLOBAL_ROUNDS; round++) {
            refused = new MethodRewriter.Stats();
            for (var node : nodes.values()) {
                if (artificial.contains(node.name)) continue;
                for (var method : node.methods) {
                    if (method.instructions.size() == 0) continue;
                    if (constants.foldMaskedLongs(method) > 0) changed.add(node.name);
                    var stats = new MethodRewriter.Stats();
                    var known = model.seedParameters.getOrDefault(SkidModel.key(node.name, method.name, method.desc), Map.of());
                    if (rewriter.rewrite(node.name, method, known, stats, flowIntLocals(method))) {
                        changed.add(node.name);
                        if (methodsChanged.add(method) && changes.size() < maxChanges)
                            changes.add(new TransformResult.Change(ID, node.name, method.name, method.desc, -1, 0, stats.folded, stats.branches, stats.total()));
                    }
                    // Refusals count once, from the last round (an earlier one may lack a parameter's value).
                    for (String item : stats.refused) if (refused.refused.size() < 20) refused.refused.add(item);
                    refused.unanalyzable += stats.unanalyzable;
                    stats.unanalyzable = 0; stats.refused.clear();
                    total.add(stats);
                    // References whose index and (salted) key are literals now.
                    int decoded = 0;
                    for (var decoder : references) {
                        decoded += decoder.decode(method, search);
                        // A searched key reveals the method's salt (or salt | seed): the rest of the method folds with it.
                        saltsDeduced += decoder.applyDeduced(method);
                    }
                    if (decoded > 0) { changed.add(node.name); referencesDecoded += decoded; }
                }
            }
            // Encrypted numbers and strings whose keys are literals now (a decrypted number may be a salt passed on).
            constants.prepare(nodes, artificial, seedFields);
            for (var node : nodes.values()) {
                if (artificial.contains(node.name)) continue;
                int count = constants.decrypt(node);
                if (count > 0) { changed.add(node.name); decrypted += count; }
            }
            // Salt parameters every call now passes as a constant (the decoded references are calls again).
            int found = parameters.find(model.seedParameters);
            boolean learned = found > 0 || referencesDecoded != decodedBefore || saltsDeduced != deducedBefore || decrypted != decryptedBefore;
            decryptedBefore = decrypted;
            deducedBefore = saltsDeduced;
            decodedBefore = referencesDecoded;
            if (!learned) { if (search) break; search = true; }
        }
        total.unanalyzable = refused.unanalyzable; total.refused.addAll(refused.refused);
        var confirmed = Collections.newSetFromMap(new IdentityHashMap<MethodNode,Boolean>());
        for (var decoder : references) confirmed.addAll(decoder.confirmed);
        parameters.remove(model.seedParameters, changed, confirmed);

        // Salt and hash fields nothing reads any more: their initializers and the fields go.
        int fieldsRemoved = removeSeedFields(nodes, seedFields, changed);
        // Decryptors nothing calls, and the initializers that only they needed.
        constants.finish(nodes, changed, rewriter);

        // The dispatcher and the hash class once nothing refers to them; the hash resource with its class.
        var removedClasses = new HashSet<String>();
        var removedEntries = new HashSet<String>();
        for (int pass = 0; pass < 2; pass++) {
            var referenced = referencedClasses(nodes, removedClasses);
            for (String name : dispatchers) if (!removedClasses.contains(name) && !referenced.contains(name)) removedClasses.add(name);
            for (String name : referenceClasses) if (!removedClasses.contains(name) && !referenced.contains(name)) removedClasses.add(name);
            for (String name : hashClasses)
                if (!removedClasses.contains(name) && !referenced.contains(name)) {
                    removedClasses.add(name);
                    String resource = hashResource(nodes.get(name));
                    if (resource != null) removedEntries.add(resource);
                }
        }

        var output = new LinkedHashMap<String,byte[]>();
        int writeFailures = 0; var writeFailed = new ArrayList<String>();
        var hierarchy = new Hierarchy(nodes);
        var internalOf = new HashMap<String,String>();
        entryOf.forEach((internal, entry) -> internalOf.put(entry, internal));
        for (var entry : entries.entrySet()) {
            String name = entry.getKey();
            if (removedEntries.contains(name)) continue;
            String internal = internalOf.get(name);
            if (internal != null && removedClasses.contains(internal)) continue;
            if (internal == null || !changed.contains(internal)) { output.put(name, entry.getValue()); continue; }
            var node = nodes.get(internal);
            for (var method : node.methods) debugInfo(method);
            try {
                int flags = (node.version & 0xFFFF) >= V1_6 ? org.objectweb.asm.ClassWriter.COMPUTE_FRAMES : org.objectweb.asm.ClassWriter.COMPUTE_MAXS;
                var writer = hierarchy.writer(flags);
                node.accept(writer);
                output.put(name, writer.toByteArray());
            } catch (RuntimeException error) {
                writeFailures++; if (writeFailed.size() < 20) writeFailed.add(Hierarchy.failure(internal, error));
                output.put(name, entry.getValue());
            }
        }
        var report = new LinkedHashMap<String,Integer>();
        report.put("classes", nodes.size());
        report.put("salt dispatchers", dispatchers.size()); report.put("hash integrity classes", hashClasses.size());
        report.put("reference obfuscation classes", referenceClasses.size());
        report.put("reused integers put back", reusedInlined);
        report.putAll(counts);
        report.put("methods changed", methodsChanged.size());
        report.put("salted values folded", total.folded); report.put("branches decided", total.branches);
        report.put("dispatcher gotos threaded", total.threaded);
        report.put("switches pruned", total.switchesPruned); report.put("dead instructions removed", total.dead);
        report.put("salt stores removed", total.storesDropped); report.put("peephole edits", total.peephole);
        report.put("methods refused", total.unanalyzable);
        report.put("references decoded", referencesDecoded);
        report.put("of which with a searched key (no call reveals the salt)", references.stream().mapToInt(r -> r.searched).sum());
        report.put("salted expressions written from a searched key", saltsDeduced);
        report.put("references not decoded", remainingReferences(nodes, referenceClasses, removedClasses));
        report.put("salt parameter groups", parameters.groupsFound); report.put("salt parameters removed", parameters.methods);
        report.put("calls rewritten", parameters.calls);
        report.put("salt and hash fields removed", fieldsRemoved);
        report.put("strings decrypted", constants.strings); report.put("numbers decrypted", constants.numbers);
        report.put("decryptor calls not decrypted", constants.notDecrypted); report.put("masked longs folded", constants.longsFolded);
        report.put("decryptors removed", constants.decryptorsRemoved); report.put("initializers removed", constants.initializersRemoved);
        report.put("encryption arrays removed", constants.fieldsRemoved); report.put("encryption arrays kept and renamed", constants.fieldsRenamed);
        report.put("initializers the evaluator refused", constants.refused);
        report.put("obfuscator classes removed", removedClasses.size()); report.put("obfuscator resources removed", removedEntries.size());
        report.put("class write failures", writeFailures);
        var refusedAll = new ArrayList<String>(total.refused);
        for (String failure : constants.failures) refusedAll.add("initializer " + failure);
        return new Result(output, new Report(true, report, refusedAll, parameters.kept, writeFailed), changes);
    }

    /**
     * Debug information the rewriting left pointing nowhere: a line number with no instruction after it (dead code at the
     * end went) and local variables whose range labels are gone. The JVM rejects a line number at the end of the code.
     */
    static void debugInfo(MethodNode method) {
        for (var insn : method.instructions.toArray()) {
            if (!(insn instanceof LineNumberNode line)) continue;
            var next = line.getNext();
            while (next != null && next.getOpcode() < 0) next = next.getNext();
            if (next == null || !method.instructions.contains(line.start)) method.instructions.remove(line);
        }
        if (method.localVariables != null)
            method.localVariables.removeIf(local -> !method.instructions.contains(local.start) || !method.instructions.contains(local.end)
                || method.instructions.indexOf(local.start) > method.instructions.indexOf(local.end));
    }

    // ---------------------------------------------------------------- recognition

    /** A class with Aidsfuscator's FNV-1a name hash {@code (String)J} and a salt retriever {@code (Object, int)int}. */
    static boolean saltDispatcher(ClassNode node) {
        boolean hasher = false, retriever = false;
        for (var method : node.methods) {
            if ((method.access & ACC_STATIC) == 0) continue;
            if (method.desc.equals("(Ljava/lang/String;)J") && hasLong(method, FNV_OFFSET) && hasLong(method, FNV_PRIME)) hasher = true;
            if (method.desc.equals(CLASS_RETRIEVER)) retriever = true;
        }
        return hasher && retriever;
    }

    /** A class with a static SHA-256 hex hash {@code (byte[])String} and a value retriever {@code (Class, int)int}. */
    static boolean hashClass(ClassNode node) {
        boolean hasher = false, retriever = false;
        for (var method : node.methods) {
            if ((method.access & ACC_STATIC) == 0) continue;
            if (method.desc.equals("([B)Ljava/lang/String;") && hasString(method, "SHA-256")) hasher = true;
            if (method.desc.equals(HASH_RETRIEVER)) retriever = true;
        }
        return hasher && retriever && hashResource(node) != null;
    }

    /** The bootstrap class of the reference obfuscation: a {@code MutableCallSite} bootstrap and an {@code (int, int)String} decoder. */
    static boolean referenceClass(ClassNode node) {
        boolean bootstrap = false, decoder = false;
        for (var method : node.methods) {
            if ((method.access & ACC_STATIC) == 0) continue;
            if (method.desc.equals("(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;")
                && hasNew(method, "java/lang/invoke/MutableCallSite")) bootstrap = true;
            if (method.desc.equals("(II)Ljava/lang/String;")) decoder = true;
        }
        return bootstrap && decoder;
    }

    /** The jar entry of the hash class's resource ({@code ldc "/<name>.bin"} in its {@code <clinit>}), or null. */
    static String hashResource(ClassNode node) {
        for (var method : node.methods) {
            if (!method.name.equals("<clinit>")) continue;
            for (var insn : method.instructions)
                if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof String text && text.startsWith("/") && text.endsWith(".bin") && text.length() > 5)
                    return text.substring(1);
        }
        return null;
    }

    private static boolean hasLong(MethodNode method, long value) {
        for (var insn : method.instructions) if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof Long l && l == value) return true;
        return false;
    }
    private static boolean hasString(MethodNode method, String value) {
        for (var insn : method.instructions) if (insn instanceof LdcInsnNode ldc && value.equals(ldc.cst)) return true;
        return false;
    }
    private static boolean hasNew(MethodNode method, String type) {
        for (var insn : method.instructions) if (insn instanceof TypeInsnNode t && t.getOpcode() == NEW && t.desc.equals(type)) return true;
        return false;
    }

    // ---------------------------------------------------------------- integer reuse

    /**
     * Puts back the constants Aidsfuscator's post-processing moved into locals: a leading run of {@code ldc int; istore n}
     * where {@code n} is no parameter and is never stored or incremented elsewhere. Each {@code iload n} becomes the
     * constant again. Returns the number of locals put back.
     */
    static int inlineReusedIntegers(MethodNode method) {
        if (method.instructions.size() == 0) return 0;
        int parameterSlots = SkidfuscatorReverser.argumentSlots(method.desc) + ((method.access & ACC_STATIC) != 0 ? 0 : 1);
        var pairs = new ArrayList<AbstractInsnNode[]>();
        AbstractInsnNode at = method.instructions.getFirst();
        while (true) {
            while (at != null && at.getOpcode() < 0) at = at.getNext();
            if (!(at instanceof LdcInsnNode ldc) || !(ldc.cst instanceof Integer)) break;
            var store = at.getNext();
            while (store != null && store.getOpcode() < 0 && !(store instanceof LabelNode)) store = store.getNext();
            if (!(store instanceof VarInsnNode var) || var.getOpcode() != ISTORE || var.var < parameterSlots) break;
            pairs.add(new AbstractInsnNode[] {ldc, store});
            at = store.getNext();
        }
        if (pairs.isEmpty()) return 0;
        // A jump into the run would see the locals unset; Aidsfuscator puts the run first, before any label is targeted.
        var targets = new HashSet<LabelNode>();
        for (var insn : method.instructions) {
            if (insn instanceof JumpInsnNode jump) targets.add(jump.label);
            else if (insn instanceof TableSwitchInsnNode table) { targets.add(table.dflt); targets.addAll(table.labels); }
            else if (insn instanceof LookupSwitchInsnNode lookup) { targets.add(lookup.dflt); targets.addAll(lookup.labels); }
        }
        for (var block : method.tryCatchBlocks) targets.add(block.handler);
        int inlined = 0;
        for (var pair : pairs) {
            for (var node = method.instructions.getFirst(); node != pair[1]; node = node.getNext())
                if (node instanceof LabelNode label && targets.contains(label)) return inlined;
            int slot = ((VarInsnNode) pair[1]).var;
            int value = (Integer) ((LdcInsnNode) pair[0]).cst;
            boolean single = true;
            for (var insn : method.instructions) {
                if (insn == pair[1]) continue;
                if (insn instanceof VarInsnNode var && var.var == slot && var.getOpcode() != ILOAD) { single = false; break; }
                if (insn instanceof IincInsnNode inc && inc.var == slot) { single = false; break; }
            }
            if (!single) continue;
            for (var insn : method.instructions.toArray())
                if (insn instanceof VarInsnNode var && var.var == slot && var.getOpcode() == ILOAD) method.instructions.set(insn, new LdcInsnNode(value));
            method.instructions.remove(pair[0]); method.instructions.remove(pair[1]);
            inlined++;
        }
        return inlined;
    }

    /**
     * Flow Ints' locals in a method without a salt (a decryptor, a {@code <clinit>}): set by a literal, updated only as
     * {@code v = v ^ <literal>}, and read only into {@code ^ <literal>}, {@code | <literal>} or a switch. In a salted
     * method the salt already marks them; here their shape does, so the rewriting folds what they compute.
     */
    static Set<Integer> flowIntLocals(MethodNode method) {
        int parameterSlots = SkidfuscatorReverser.argumentSlots(method.desc) + ((method.access & ACC_STATIC) != 0 ? 0 : 1);
        var candidates = new HashMap<Integer,Boolean>(); // slot -> has an update
        var rejected = new HashSet<Integer>();
        for (var insn : method.instructions) {
            if (insn instanceof IincInsnNode inc) { rejected.add(inc.var); continue; }
            if (!(insn instanceof VarInsnNode var) || var.var < parameterSlots) continue;
            int op = var.getOpcode();
            if (op != ISTORE && op != ILOAD) { rejected.add(var.var); continue; }
            if (op == ISTORE) {
                var value = SeedInterpreter.previous(var);
                if (SkidModel.literal(value) != null) { candidates.putIfAbsent(var.var, false); continue; }
                var constant = SeedInterpreter.previous(value);
                var load = SeedInterpreter.previous(constant);
                if (value != null && value.getOpcode() == IXOR && SkidModel.literal(constant) != null
                    && load instanceof VarInsnNode l && l.getOpcode() == ILOAD && l.var == var.var) candidates.put(var.var, true);
                else rejected.add(var.var);
            } else {
                var next = SeedInterpreter.next(var);
                if (next instanceof LookupSwitchInsnNode || next instanceof TableSwitchInsnNode) continue;
                var combine = SeedInterpreter.next(next);
                if (SkidModel.literal(next) == null || combine == null || combine.getOpcode() != IXOR && combine.getOpcode() != IOR) rejected.add(var.var);
            }
        }
        var result = new HashSet<Integer>();
        candidates.forEach((slot, updated) -> { if (updated && !rejected.contains(slot)) result.add(slot); });
        return result;
    }

    // ---------------------------------------------------------------- hash integrity and class salts

    /**
     * {@code owner.field} to its value for every {@code putstatic} of a hash retriever's result in a {@code <clinit>}:
     * {@code stored ^ sha256hex(classFile).hashCode() ^ param}, with the stored value read from the hash resource.
     */
    private static Map<String,Integer> hashFieldValues(Map<String,ClassNode> nodes, Map<String,byte[]> entries, Map<String,String> entryOf,
                                                       Set<String> hashClasses, SkidModel model, Map<String,Integer> counts) {
        var result = new HashMap<String,Integer>();
        var stored = new HashMap<String,Integer>();
        for (String name : hashClasses) {
            String resource = hashResource(nodes.get(name));
            byte[] bytes = resource == null ? null : entries.get(resource);
            if (bytes == null) continue;
            for (int at = 0; at + 68 <= bytes.length; at += 68) {
                String hex = new String(bytes, at, 64, StandardCharsets.ISO_8859_1);
                int value = ((bytes[at + 64] & 0xFF) << 24) | ((bytes[at + 65] & 0xFF) << 16) | ((bytes[at + 66] & 0xFF) << 8) | (bytes[at + 67] & 0xFF);
                stored.put(hex, value);
            }
        }
        int unresolved = 0;
        if (!stored.isEmpty()) {
            for (var node : nodes.values()) {
                for (var call : retrieverCalls(node, hashClasses, HASH_RETRIEVER)) {
                    String field = node.name + "." + call.field.name;
                    String entry = entryOf.get(node.name);
                    String hex = entry == null ? null : sha256(entries.get(entry));
                    Integer value = hex == null ? null : stored.get(hex);
                    Integer param = argument(node.name, call, model);
                    if (value == null || param == null) { unresolved++; continue; }
                    result.put(field, value ^ hex.hashCode() ^ param);
                }
            }
        }
        counts.put("hash fields resolved", result.size()); counts.put("hash fields not resolved", unresolved);
        return result;
    }

    /** {@code owner.field} to the salt every plain retriever call sets it to: {@code table[fnv(name)] ^ fake}. */
    private static Map<String,Integer> classSaltValues(Map<String,ClassNode> nodes, Set<String> dispatchers, SkidModel model, Map<String,Integer> counts) {
        var result = new HashMap<String,Integer>();
        var tables = new HashMap<String,Map<Long,Integer>>();
        var plain = new HashSet<String>();
        for (String name : dispatchers) {
            var node = nodes.get(name);
            tables.put(name, saltTable(node));
            for (var method : node.methods)
                if (method.desc.equals(CLASS_RETRIEVER) && !hasNew(method, "java/lang/Throwable")) plain.add(SkidModel.key(name, method.name, method.desc));
        }
        int unresolved = 0, ordered = 0;
        for (var node : nodes.values()) {
            for (var call : retrieverCalls(node, dispatchers, CLASS_RETRIEVER)) {
                if (!plain.contains(SkidModel.key(call.call.owner, call.call.name, call.call.desc))) { ordered++; continue; }
                Integer stored = tables.get(call.call.owner).get(fnv(node.name.replace('/', '.')));
                Integer fake = argument(node.name, call, model);
                if (stored == null || fake == null) { unresolved++; continue; }
                result.put(node.name + "." + call.field.name, stored ^ fake);
            }
        }
        counts.put("class salts resolved", result.size()); counts.put("class salts not resolved", unresolved);
        counts.put("class salts in initialization order (left alone)", ordered);
        return result;
    }

    /** The dispatcher's table: {@code map.put(Long.valueOf(<long>), Integer.valueOf(<int>))} in its static methods. */
    private static Map<Long,Integer> saltTable(ClassNode node) {
        var table = new HashMap<Long,Integer>();
        for (var method : node.methods) {
            if ((method.access & ACC_STATIC) == 0) continue;
            Long key = null; Integer value = null;
            for (var insn : method.instructions) {
                if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof Long l) { key = l; value = null; continue; }
                if (insn instanceof MethodInsnNode call && call.owner.equals("java/lang/Integer") && call.name.equals("valueOf")) {
                    value = SkidModel.literal(SeedInterpreter.previous(call)); continue;
                }
                if (insn instanceof MethodInsnNode call && call.owner.equals("java/util/Map") && call.name.equals("put") && key != null && value != null) {
                    table.put(key, value); key = null; value = null;
                }
            }
        }
        return table;
    }

    /** A {@code <clinit>} statement {@code field = Retriever.m(MethodHandles.lookup().lookupClass(), <int>)}. */
    record RetrieverCall(MethodNode clinit, AbstractInsnNode lookup, MethodInsnNode call, FieldInsnNode field) {}

    static List<RetrieverCall> retrieverCalls(ClassNode node, Set<String> owners, String desc) {
        var result = new ArrayList<RetrieverCall>();
        for (var method : node.methods) {
            if (!method.name.equals("<clinit>")) continue;
            for (var insn : method.instructions) {
                if (!(insn instanceof MethodInsnNode call) || call.getOpcode() != INVOKESTATIC || !owners.contains(call.owner) || !call.desc.equals(desc)) continue;
                var store = SeedInterpreter.next(call);
                if (!(store instanceof FieldInsnNode field) || field.getOpcode() != PUTSTATIC || !field.owner.equals(node.name) || !field.desc.equals("I")) continue;
                // Back to MethodHandles.lookup(): lookupClass() is the first argument.
                AbstractInsnNode lookup = null;
                for (var back = call.getPrevious(); back != null; back = back.getPrevious())
                    if (back instanceof MethodInsnNode m && m.owner.equals("java/lang/invoke/MethodHandles") && m.name.equals("lookup")) { lookup = back; break; }
                if (lookup == null || !(SeedInterpreter.next(lookup) instanceof MethodInsnNode lookupClass) || !lookupClass.name.equals("lookupClass")) continue;
                result.add(new RetrieverCall(method, lookup, call, field));
            }
        }
        return result;
    }

    /** The int argument of a retriever call, from the seed analysis of the {@code <clinit>} (facts known so far). */
    private static Integer argument(String owner, RetrieverCall call, SkidModel model) {
        var result = new SeedAnalyzer(model.interpreter()).analyze(owner, call.clinit, Map.of());
        if (result == null) return null;
        int index = result.index(call.call);
        var frame = index < 0 ? null : result.frames()[index];
        if (frame == null) return null;
        SV value = frame.getStack(frame.getStackSize() - 1);
        return value.singleton() ? value.single() : null;
    }

    static long fnv(String text) {
        long hash = FNV_OFFSET;
        for (int i = 0; i < text.length(); i++) { hash ^= text.charAt(i); hash *= FNV_PRIME; }
        return hash;
    }

    static String sha256(byte[] bytes) {
        if (bytes == null) return null;
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    // ---------------------------------------------------------------- clean-up

    /**
     * Removes the initializers and declarations of salt and hash fields that nothing reads. Aidsfuscator gives fields of
     * different types one name ({@code int a}, {@code int[] a}), so a read is matched by name and descriptor.
     */
    private static int removeSeedFields(Map<String,ClassNode> nodes, Set<String> seedFields, Set<String> changed) {
        var reads = new HashSet<String>();
        for (var node : nodes.values())
            for (var method : node.methods)
                for (var insn : method.instructions)
                    if (insn instanceof FieldInsnNode field && (insn.getOpcode() == GETSTATIC || insn.getOpcode() == GETFIELD))
                        reads.add(field.owner + "." + field.name + ":" + field.desc);
        int removed = 0;
        for (var node : nodes.values()) {
            for (var method : node.methods) {
                if (!method.name.equals("<clinit>")) continue;
                for (var insn : method.instructions.toArray()) {
                    if (!(insn instanceof FieldInsnNode field) || insn.getOpcode() != PUTSTATIC || !field.owner.equals(node.name) || !field.desc.equals("I")) continue;
                    String name = node.name + "." + field.name;
                    if (!seedFields.contains(name) || reads.contains(name + ":I")) continue;
                    if (removeStatement(method, field)) changed.add(node.name);
                }
            }
            int before = node.fields.size();
            node.fields.removeIf(field -> {
                String name = node.name + "." + field.name;
                return field.desc.equals("I") && seedFields.contains(name) && !reads.contains(name + ":I") && !written(nodes, node.name, field.name);
            });
            removed += before - node.fields.size();
            if (before != node.fields.size()) changed.add(node.name);
        }
        return removed;
    }

    /**
     * Removes the statement ending at {@code store}: {@code <int>; putstatic} (the constant written for a retriever call)
     * or {@code MethodHandles.lookup().lookupClass(); <int>; invokestatic; putstatic}.
     */
    private static boolean removeStatement(MethodNode method, FieldInsnNode store) {
        var call = SeedInterpreter.previous(store);
        if (SkidModel.literal(call) != null) {
            for (var node = call.getNext(); node != store; node = node.getNext()) if (node instanceof LabelNode label && targeted(method, label)) return false;
            method.instructions.remove(call); method.instructions.remove(store);
            return true;
        }
        if (!(call instanceof MethodInsnNode)) return false;
        AbstractInsnNode lookup = null;
        for (var back = call.getPrevious(); back != null; back = back.getPrevious()) {
            if (back instanceof LabelNode label && targeted(method, label)) return false;
            if (back instanceof MethodInsnNode m && m.owner.equals("java/lang/invoke/MethodHandles") && m.name.equals("lookup")) { lookup = back; break; }
            if (back instanceof JumpInsnNode || back instanceof TableSwitchInsnNode || back instanceof LookupSwitchInsnNode) return false;
        }
        if (lookup == null) return false;
        var list = new ArrayList<AbstractInsnNode>();
        for (var node = lookup; node != store.getNext(); node = node.getNext()) if (node.getOpcode() >= 0) list.add(node);
        list.forEach(method.instructions::remove);
        return true;
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

    private static boolean written(Map<String,ClassNode> nodes, String owner, String name) {
        for (var node : nodes.values())
            for (var method : node.methods)
                for (var insn : method.instructions)
                    if (insn instanceof FieldInsnNode field && field.owner.equals(owner) && field.name.equals(name) && field.desc.equals("I")) return true;
        return false;
    }

    /** The {@code invokedynamic} instructions still bound to a reference obfuscation bootstrap. */
    private static int remainingReferences(Map<String,ClassNode> nodes, Set<String> referenceClasses, Set<String> removed) {
        int count = 0;
        for (var node : nodes.values()) {
            if (removed.contains(node.name) || referenceClasses.contains(node.name)) continue;
            for (var method : node.methods)
                for (var insn : method.instructions)
                    if (insn instanceof InvokeDynamicInsnNode indy && referenceClasses.contains(indy.bsm.getOwner())) count++;
        }
        return count;
    }

    /** Every class another kept class refers to by an instruction, a handle, a constant or a field or super type. */
    private static Set<String> referencedClasses(Map<String,ClassNode> nodes, Set<String> removed) {
        var result = new HashSet<String>();
        for (var node : nodes.values()) {
            if (removed.contains(node.name)) continue;
            var own = new HashSet<String>();
            if (node.superName != null) own.add(node.superName);
            own.addAll(node.interfaces);
            for (var field : node.fields) if (Type.getType(field.desc).getSort() == Type.OBJECT) own.add(Type.getType(field.desc).getInternalName());
            for (var method : node.methods)
                for (var insn : method.instructions) {
                    if (insn instanceof MethodInsnNode call) own.add(call.owner);
                    else if (insn instanceof FieldInsnNode field) own.add(field.owner);
                    else if (insn instanceof TypeInsnNode type) own.add(type.desc);
                    else if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof Type type && type.getSort() == Type.OBJECT) own.add(type.getInternalName());
                    else if (insn instanceof InvokeDynamicInsnNode indy) {
                        own.add(indy.bsm.getOwner());
                        for (Object arg : indy.bsmArgs) if (arg instanceof Handle h) own.add(h.getOwner());
                    }
                }
            own.remove(node.name);
            result.addAll(own);
        }
        return result;
    }
}
