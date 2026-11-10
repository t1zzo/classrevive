package dev.classrevive.transforms.radon;

import dev.classrevive.transforms.TransformResult;
import dev.classrevive.transforms.skid.Hierarchy;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import java.util.*;

/**
 * Phase 2DK: reverses Radon 2 (ItzSomebody/radon, the last Radon 2 commit {@code 00ac992}) over a whole jar, statically.
 * Written for ClassRevive from the bytecode shapes documented in docs/radon-yguard.md; Radon is GPL-3.0 and none of its
 * code is copied. Radon applies its transformers in a fixed order (references, numbers, strings, flow, hide code, ...),
 * so each later one wraps the earlier ones' output; the reversal runs in the opposite order:
 * <ol>
 *   <li><b>numbers</b>: arithmetic and bitwise chains folded ({@link ConstantFolder}); context-checked constants
 *   ({@code valueOf(enc); key; invokestatic decoder(Object,int)Object; checkcast; xValue()}) decoded with the caller's
 *   method name;</li>
 *   <li><b>strings</b>: {@code ldc enc; int; invokestatic decryptor(Object,int)String} decrypted ({@link RadonCipher#string}),
 *   with or without context checking, read from the decryptor's own key computation;</li>
 *   <li><b>references</b>: heavy {@code invokedynamic} sites ({@code ldc owner; ldc2_w key}, names {@code a b d e f g})
 *   resolved against the owner's members by the two hashes of the key, and "fast" sites by decrypting their name; both
 *   become the original {@code invoke*}/field instruction;</li>
 *   <li><b>flow</b>: the exact predicate, null-check and fake-catch shapes ({@link RadonFlow});</li>
 *   <li><b>hide code</b>: the {@code synthetic} and {@code bridge} flags Radon put on ordinary classes, methods and fields are
 *   cleared (decompilers hide such members); the invalid {@code @} annotations go; constants Radon moved into
 *   {@code <clinit>} become {@code ConstantValue}s again.</li>
 * </ol>
 * The decryptor, decoder, bootstrap and fake handler classes and the predicate fields are removed once nothing refers to
 * them. A site whose shape or key cannot be proven is left as it is and counted. Target code is never executed.
 */
public final class RadonReverser implements Opcodes {
    public static final String ID = "radon";
    static final String STRING_DECRYPTOR = "(Ljava/lang/Object;I)Ljava/lang/String;";
    static final String NUMBER_DECODER = "(Ljava/lang/Object;I)Ljava/lang/Object;";
    static final String HEAVY_BOOTSTRAP = "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;";
    static final String FAST_BOOTSTRAP = "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;";
    /** Superclasses Radon 2 draws its fake handler class from. */
    static final Set<String> HANDLER_SUPERS = Set.of("java/lang/RuntimeException", "java/lang/LinkageError", "java/lang/Error",
        "java/lang/Exception", "java/lang/Throwable", "java/lang/IllegalArgumentException", "java/lang/IllegalStateException",
        "java/lang/IllegalAccessError", "java/lang/reflect/InvocationTargetException", "java/io/IOException", "java/io/IOError");

    public record Report(boolean detected, Map<String,Integer> counts, List<String> notes, List<String> writeFailures) {
        public Report { counts = Collections.unmodifiableMap(new LinkedHashMap<>(counts)); notes = List.copyOf(notes); writeFailures = List.copyOf(writeFailures); }
        public int count(String name) { return counts.getOrDefault(name, 0); }
        @Override public String toString() {
            var out = new StringBuilder("Radon reversal: ").append(detected ? "detected" : "not detected");
            counts.forEach((name, value) -> out.append(System.lineSeparator()).append("  ").append(name).append(": ").append(value));
            if (!notes.isEmpty()) out.append(System.lineSeparator()).append("  sites left as they were: ").append(notes);
            if (!writeFailures.isEmpty()) out.append(System.lineSeparator()).append("  classes kept as they were (write failed): ").append(writeFailures);
            return out.toString();
        }
    }
    public record Result(Map<String,byte[]> entries, Report report, List<TransformResult.Change> changes) {}

    private record Decryptor(String owner, String name, boolean context) {}

    private final List<String> notes = new ArrayList<>();
    private void note(String text) { if (notes.size() < 20) notes.add(text); }

    /** {@code entries}: jar entry name to bytes; only {@code .class} entries are read, others are returned as they are. */
    public Result apply(Map<String,byte[]> entries, int maxChanges) {
        var nodes = new LinkedHashMap<String,ClassNode>(); var entryOf = new HashMap<String,String>();
        for (var entry : entries.entrySet()) {
            if (!entry.getKey().endsWith(".class")) continue;
            try {
                var node = new ClassNode();
                new ClassReader(entry.getValue()).accept(node, ClassReader.SKIP_FRAMES);
                if (nodes.putIfAbsent(node.name, node) == null) entryOf.put(node.name, entry.getKey());
            } catch (RuntimeException unreadable) { /* left as it is */ }
        }
        // ------------------------------------------------------------------ what Radon injected
        var decryptors = new HashMap<String,Decryptor>(); var decoders = new HashSet<String>(); var decoderOwners = new HashSet<String>();
        var heavy = new HashSet<String>(); var fast = new HashSet<String>(); var fakeHandlers = new HashSet<String>();
        for (var node : nodes.values()) {
            var decryptor = stringDecryptor(node);
            if (decryptor != null) decryptors.put(node.name + "." + decryptor.name + STRING_DECRYPTOR, decryptor);
            String decoder = numberDecoder(node);
            if (decoder != null) { decoders.add(node.name + "." + decoder + NUMBER_DECODER); decoderOwners.add(node.name); }
            if (node.methods.isEmpty() && node.fields.isEmpty() && HANDLER_SUPERS.contains(node.superName)) fakeHandlers.add(node.name);
        }
        for (var node : nodes.values()) for (var method : node.methods) for (var insn : method.instructions)
            if (insn instanceof InvokeDynamicInsnNode indy && nodes.containsKey(indy.bsm.getOwner())) {
                if (indy.bsm.getDesc().equals(HEAVY_BOOTSTRAP)) heavy.add(indy.bsm.getOwner());
                else if (indy.bsm.getDesc().equals(FAST_BOOTSTRAP) && fastSite(indy, nodes.get(indy.bsm.getOwner())) != null) fast.add(indy.bsm.getOwner());
            }
        var predicates = predicateFields(nodes);
        int hidden = hiddenMembers(nodes), badAnnotations = badAnnotations(nodes, false);
        var injected = new LinkedHashSet<String>();
        decryptors.values().forEach(d -> injected.add(d.owner));
        injected.addAll(decoderOwners);
        injected.addAll(heavy); injected.addAll(fast); injected.addAll(fakeHandlers);
        boolean detected = !injected.isEmpty() || !predicates.isEmpty() || badAnnotations > 0;
        if (!detected) return new Result(entries, new Report(false, Map.of("classes", nodes.size()), List.of(), List.of()), List.of());

        var counts = new LinkedHashMap<String,Integer>();
        counts.put("classes", nodes.size());
        counts.put("string decryptor classes", (int) decryptors.values().stream().map(Decryptor::owner).distinct().count());
        counts.put("number decoder classes", decoders.size());
        counts.put("invokedynamic bootstrap classes", heavy.size() + fast.size());
        counts.put("fake handler classes", fakeHandlers.size());
        counts.put("predicate fields", predicates.size());
        var resolver = new MemberResolver(nodes);
        var flow = new RadonFlow();
        var changed = new LinkedHashSet<String>(); var changes = new ArrayList<TransformResult.Change>();
        int chains = 0, numbers = 0, numbersLeft = 0, strings = 0, stringsLeft = 0, references = 0, referencesLeft = 0;
        for (var node : nodes.values()) {
            if (injected.contains(node.name)) continue;
            for (var method : node.methods) {
                if (method.instructions.size() == 0) continue;
                int before = chains + numbers + strings + references;
                chains += ConstantFolder.fold(method);
                int[] n = numbers(node, method, decoders); numbers += n[0]; numbersLeft += n[1];
                chains += ConstantFolder.fold(method);
                int[] s = strings(node, method, decryptors); strings += s[0]; stringsLeft += s[1];
                int[] r = references(node, method, heavy, fast, nodes, resolver); references += r[0]; referencesLeft += r[1];
                boolean flowChanged = flow.apply(method, predicates, fakeHandlers);
                int after = chains + numbers + strings + references;
                if (after != before || flowChanged) {
                    changed.add(node.name);
                    if (changes.size() < maxChanges)
                        changes.add(new TransformResult.Change(ID, node.name, method.name, method.desc, -1, 0, n[0] + s[0], r[0], after - before));
                }
            }
        }
        counts.put("number chains folded", chains);
        counts.put("context-checked numbers decoded", numbers); counts.put("context-checked numbers left", numbersLeft);
        counts.put("strings decrypted", strings); counts.put("decryptor calls left", stringsLeft);
        counts.put("references restored", references); counts.put("invokedynamic references left", referencesLeft);
        counts.put("gotos restored", flow.gotosRestored); counts.put("bogus jumps removed", flow.predicateJumps);
        counts.put("exit blocks removed", flow.exitBlocks); counts.put("bogus switches removed", flow.bogusSwitches);
        counts.put("null checks restored", flow.nullChecks); counts.put("fake catch blocks removed", flow.fakeCatches);
        counts.put("predicate locals removed", flow.predicateLocals); counts.put("predicate reads replaced by 0", flow.predicatesZeroed);
        // ------------------------------------------------------------------ hide code, annotations, static constants
        counts.put("hidden flags cleared", hidden > 0 ? unhide(nodes, injected, changed) : 0);
        int annotationsRemoved = 0;
        for (var node : nodes.values()) {
            int removedHere = badAnnotations(Map.of(node.name, node), true);
            if (removedHere > 0) { annotationsRemoved += removedHere; changed.add(node.name); }
        }
        counts.put("invalid annotations removed", annotationsRemoved);
        counts.put("constants put back into fields", constants(nodes, injected, changed));
        // ------------------------------------------------------------------ removal
        int fieldsRemoved = 0;
        var read = new HashSet<String>();
        for (var node : nodes.values()) for (var method : node.methods) for (var insn : method.instructions)
            if (insn instanceof FieldInsnNode field) read.add(field.owner + "." + field.name);
        for (var node : nodes.values())
            for (var iterator = node.fields.iterator(); iterator.hasNext(); ) {
                var field = iterator.next();
                if (predicates.contains(node.name + "." + field.name) && !read.contains(node.name + "." + field.name)) {
                    iterator.remove(); fieldsRemoved++; changed.add(node.name);
                }
            }
        counts.put("predicate fields removed", fieldsRemoved);
        var removed = new LinkedHashSet<String>();
        for (boolean progress = true; progress; ) {
            progress = false;
            for (String name : injected) {
                if (removed.contains(name)) continue;
                boolean referenced = false;
                for (var node : nodes.values()) if (!node.name.equals(name) && !removed.contains(node.name) && references(node, name)) { referenced = true; break; }
                if (!referenced) { removed.add(name); progress = true; }
            }
        }
        counts.put("injected classes removed", removed.size());
        // ------------------------------------------------------------------ output
        var output = new LinkedHashMap<String,byte[]>();
        var writeFailed = new ArrayList<String>(); var hierarchy = new Hierarchy(nodes);
        var internalOf = new HashMap<String,String>(); entryOf.forEach((internal, entry) -> internalOf.put(entry, internal));
        for (var entry : entries.entrySet()) {
            String internal = internalOf.get(entry.getKey());
            if (internal != null && removed.contains(internal)) continue;
            if (internal == null || !changed.contains(internal)) { output.put(entry.getKey(), entry.getValue()); continue; }
            var node = nodes.get(internal);
            for (var method : node.methods) dropStaleDebug(method);
            try {
                int flags = (node.version & 0xFFFF) >= V1_6 ? ClassWriter.COMPUTE_FRAMES : ClassWriter.COMPUTE_MAXS;
                var writer = hierarchy.writer(flags);
                node.accept(writer);
                output.put(entry.getKey(), writer.toByteArray());
            } catch (RuntimeException error) {
                if (writeFailed.size() < 20) writeFailed.add(Hierarchy.failure(internal, error));
                output.put(entry.getKey(), entry.getValue());
            }
        }
        counts.put("classes changed", changed.size());
        return new Result(output, new Report(true, counts, notes, writeFailed), changes);
    }

    // ---------------------------------------------------------------------------------------------- recognition

    /**
     * The string decryptor: a static {@code (Object, int)String} method in a class with a static {@code Map} and a static
     * {@code long[]}. Context checking is read from its four key computations {@code <x> iload_1 ixor istore}: {@code x} is
     * {@code iconst_0} without context and the last {@code iadd} of a hash sum with it; mixed or missing shapes are refused.
     */
    static Decryptor stringDecryptor(ClassNode node) {
        boolean map = false, longs = false;
        for (var field : node.fields) if ((field.access & ACC_STATIC) != 0) {
            if (field.desc.equals("Ljava/util/Map;")) map = true;
            if (field.desc.equals("[J")) longs = true;
        }
        if (!map || !longs) return null;
        for (var method : node.methods) {
            if ((method.access & ACC_STATIC) == 0 || !method.desc.equals(STRING_DECRYPTOR)) continue;
            int zero = 0, sum = 0; boolean trace = false;
            for (var insn : method.instructions) {
                if (insn instanceof MethodInsnNode call && call.name.equals("getStackTrace")) trace = true;
                if (insn instanceof VarInsnNode load && load.getOpcode() == ILOAD && load.var == 1) {
                    var x = previous(load); var xor = next(load); var store = xor == null ? null : next(xor);
                    if (xor == null || xor.getOpcode() != IXOR || store == null || store.getOpcode() != ISTORE || x == null) continue;
                    if (x.getOpcode() == ICONST_0) zero++;
                    else if (x.getOpcode() == IADD) sum++;
                }
            }
            if (!trace) continue;
            if (zero >= 4 && sum == 0) return new Decryptor(node.name, method.name, false);
            if (sum >= 4 && zero == 0) return new Decryptor(node.name, method.name, true);
        }
        return null;
    }

    /** The number decoder: a {@code Thread} subclass with a static {@code (Object, int)Object} that reads the stack trace. */
    static String numberDecoder(ClassNode node) {
        if (!"java/lang/Thread".equals(node.superName)) return null;
        for (var method : node.methods) {
            if ((method.access & ACC_STATIC) == 0 || !method.desc.equals(NUMBER_DECODER)) continue;
            for (var insn : method.instructions) if (insn instanceof MethodInsnNode call && call.name.equals("getStackTrace")) return method.name;
        }
        return null;
    }

    /** Proven-zero predicates: {@code static final boolean|int} fields without a value that no class of the jar writes. */
    static Set<String> predicateFields(Map<String,ClassNode> nodes) {
        var written = new HashSet<String>(); var loadedIntoLocal = new HashSet<String>();
        for (var node : nodes.values()) for (var method : node.methods) for (var insn : method.instructions)
            if (insn instanceof FieldInsnNode field) {
                if (field.getOpcode() == PUTSTATIC || field.getOpcode() == PUTFIELD) written.add(field.owner + "." + field.name);
                if (field.getOpcode() == GETSTATIC && next(field) instanceof VarInsnNode store && store.getOpcode() == ISTORE)
                    loadedIntoLocal.add(field.owner + "." + field.name);
            }
        var result = new HashSet<String>();
        for (var node : nodes.values()) for (var field : node.fields) {
            String key = node.name + "." + field.name;
            if ((field.access & (ACC_STATIC | ACC_FINAL)) == (ACC_STATIC | ACC_FINAL) && field.value == null
                && (field.desc.equals("Z") || field.desc.equals("I")) && !written.contains(key) && loadedIntoLocal.contains(key)) result.add(key);
        }
        return result;
    }

    // ---------------------------------------------------------------------------------------------- numbers

    private static final Map<String,String> UNBOX = Map.of("java/lang/Integer", "intValue()I", "java/lang/Long", "longValue()J",
        "java/lang/Float", "floatValue()F", "java/lang/Double", "doubleValue()D");

    private int[] numbers(ClassNode node, MethodNode method, Set<String> decoders) {
        int done = 0, left = 0;
        for (var insn : method.instructions.toArray()) {
            if (!(insn instanceof MethodInsnNode call) || call.getOpcode() != INVOKESTATIC || !decoders.contains(call.owner + "." + call.name + call.desc)) continue;
            var code = new Code(method);
            var cast = code.after(call); var unbox = cast == null ? null : code.after(cast);
            var indicator = code.before(call); var box = indicator == null ? null : code.before(indicator);
            var encoded = box == null ? null : code.before(box);
            Object decoded = null;
            if (cast instanceof TypeInsnNode type && type.getOpcode() == CHECKCAST && UNBOX.containsKey(type.desc)
                && unbox instanceof MethodInsnNode value && value.owner.equals(type.desc) && (value.name + value.desc).equals(UNBOX.get(type.desc))
                && ConstantFolder.constant(indicator) instanceof Integer && box instanceof MethodInsnNode valueOf && valueOf.getOpcode() == INVOKESTATIC
                && valueOf.name.equals("valueOf") && encoded != null) {
                boolean wide = type.desc.equals("java/lang/Long") || type.desc.equals("java/lang/Double");
                String boxed = wide ? "java/lang/Long" : "java/lang/Integer";
                if (valueOf.owner.equals(boxed) && valueOf.desc.equals(wide ? "(J)Ljava/lang/Long;" : "(I)Ljava/lang/Integer;"))
                    decoded = RadonCipher.number(ConstantFolder.constant(encoded), type.desc, method.name);
            }
            if (decoded == null) { left++; note("number " + node.name + "." + method.name); continue; }
            var insns = method.instructions;
            insns.remove(encoded); insns.remove(box); insns.remove(indicator); insns.remove(call); insns.remove(cast);
            insns.set(unbox, ConstantFolder.push(decoded));
            done++;
        }
        return new int[] {done, left};
    }

    // ---------------------------------------------------------------------------------------------- strings

    private int[] strings(ClassNode node, MethodNode method, Map<String,Decryptor> decryptors) {
        int done = 0, left = 0;
        for (var insn : method.instructions.toArray()) {
            if (!(insn instanceof MethodInsnNode call) || call.getOpcode() != INVOKESTATIC) continue;
            var decryptor = decryptors.get(call.owner + "." + call.name + call.desc);
            if (decryptor == null) continue;
            var code = new Code(method);
            var key = code.before(call); var literal = key == null ? null : code.before(key);
            if (!(ConstantFolder.constant(key) instanceof Integer k) || !(literal instanceof LdcInsnNode ldc) || !(ldc.cst instanceof String cipher)) {
                left++; note("string " + node.name + "." + method.name); continue;
            }
            String plain = RadonCipher.string(cipher, k, decryptor.context, node.name, method.name, decryptor.owner, decryptor.name);
            method.instructions.remove(literal); method.instructions.remove(key);
            method.instructions.set(call, new LdcInsnNode(plain));
            done++;
        }
        return new int[] {done, left};
    }

    // ---------------------------------------------------------------------------------------------- references

    private int[] references(ClassNode node, MethodNode method, Set<String> heavy, Set<String> fast, Map<String,ClassNode> nodes, MemberResolver resolver) {
        int done = 0, left = 0;
        for (var insn : method.instructions.toArray()) {
            if (!(insn instanceof InvokeDynamicInsnNode indy)) continue;
            AbstractInsnNode replacement = null; List<AbstractInsnNode> extra = List.of();
            if (heavy.contains(indy.bsm.getOwner()) && indy.bsm.getDesc().equals(HEAVY_BOOTSTRAP)) {
                var code = new Code(method);
                var key = code.before(indy); var owner = key == null ? null : code.before(key);
                if (ConstantFolder.constant(key) instanceof Long k && owner instanceof LdcInsnNode ldc && ldc.cst instanceof String dotted) {
                    replacement = heavySite(node, indy, dotted.replace('.', '/'), k, resolver);
                    extra = List.of(key, owner);
                }
            } else if (fast.contains(indy.bsm.getOwner()) && indy.bsm.getDesc().equals(FAST_BOOTSTRAP)) {
                var site = fastSite(indy, nodes.get(indy.bsm.getOwner()));
                if (site != null) replacement = invoke(node, site[0], site[1], site[2], site[3].equals("a"), resolver);
            } else continue;
            if (replacement == null) { left++; note("reference " + node.name + "." + method.name); continue; }
            for (var push : extra) method.instructions.remove(push);
            method.instructions.set(indy, replacement);
            done++;
        }
        return new int[] {done, left};
    }

    /** The original instruction of a heavy site, or null when the key matches no member, or more than one, of a known hierarchy. */
    private static AbstractInsnNode heavySite(ClassNode node, InvokeDynamicInsnNode indy, String owner, long key, MemberResolver resolver) {
        int nameHash = (int) (key >>> 32), low = (int) key;
        switch (indy.name) {
            case "a", "b" -> {
                boolean isStatic = indy.name.equals("a");
                var found = resolver.methods(owner, nameHash, low);
                if (found.isEmpty()) return null;
                var matches = new TreeSet<String>();
                for (var member : found.get()) {
                    if (member.isStatic() != isStatic) continue;
                    String expected = member.descriptor().replaceFirst("\\)", "Ljava/lang/String;J)");
                    if (!isStatic) expected = "(Ljava/lang/Object;" + expected.substring(1);
                    if (RadonCipher.generic(expected).equals(indy.desc)) matches.add(member.name() + member.descriptor());
                }
                if (matches.size() != 1) return null;
                String match = matches.first();
                return invoke(node, owner, match.substring(0, match.indexOf('(')), match.substring(match.indexOf('(')), isStatic, resolver);
            }
            case "d", "e", "f", "g" -> {
                boolean isStatic = indy.name.equals("d") || indy.name.equals("f"), put = indy.name.equals("f") || indy.name.equals("g");
                var found = resolver.fields(owner, nameHash, low);
                if (found.isEmpty()) return null;
                var matches = new TreeSet<String>();
                for (var member : found.get()) {
                    if (member.isStatic() != isStatic) continue;
                    String expected = put ? "(" + member.descriptor() + "Ljava/lang/String;J)V" : "(Ljava/lang/String;J)" + member.descriptor();
                    if (!isStatic) expected = "(Ljava/lang/Object;" + expected.substring(1);
                    if (expected.equals(indy.desc)) matches.add(member.name() + " " + member.descriptor());
                }
                if (matches.size() != 1) return null;
                String match = matches.first();
                int opcode = isStatic ? (put ? PUTSTATIC : GETSTATIC) : (put ? PUTFIELD : GETFIELD);
                return new FieldInsnNode(opcode, owner, match.substring(0, match.indexOf(' ')), match.substring(match.indexOf(' ') + 1));
            }
            default -> { return null; }
        }
    }

    /**
     * A "fast" site's target {@code {owner, name, descriptor, a|b}}: the name decrypted with one of the bootstrap class's
     * method names as the third key, accepted only when exactly one name yields {@code owner\0\0name\0\0descriptor\0\0a|b}
     * with a valid descriptor whose widened form is the site's own descriptor.
     */
    static String[] fastSite(InvokeDynamicInsnNode indy, ClassNode bootstrap) {
        if (bootstrap == null) return null;
        String[] found = null;
        for (var method : bootstrap.methods) {
            String plain = RadonCipher.fastName(indy.name, bootstrap.name, indy.bsm.getName(), method.name);
            var parts = plain.split("\u0000\u0000", -1);
            if (parts.length != 4 || !(parts[3].equals("a") || parts[3].equals("b")) || parts[0].isEmpty() || parts[1].isEmpty()) continue;
            String descriptor = parts[2];
            try { Type.getMethodType(descriptor).getArgumentTypes(); } catch (RuntimeException invalid) { continue; }
            if (!descriptor.startsWith("(") || descriptor.indexOf(')') < 0) continue;
            String expected = parts[3].equals("a") ? descriptor : "(Ljava/lang/Object;" + descriptor.substring(1);
            if (!RadonCipher.generic(expected).equals(indy.desc)) continue;
            var site = new String[] {parts[0].replace('.', '/'), parts[1], descriptor, parts[3]};
            if (found != null && !Arrays.equals(found, site)) return null;
            found = site;
        }
        return found;
    }

    /** {@code invokestatic}, {@code invokeinterface}, {@code invokespecial} (a private method of the class itself, before Java 11) or {@code invokevirtual}. */
    private static AbstractInsnNode invoke(ClassNode node, String owner, String name, String descriptor, boolean isStatic, MemberResolver resolver) {
        var itf = resolver.isInterface(owner);
        if (itf.isEmpty()) return null;
        if (isStatic) return new MethodInsnNode(INVOKESTATIC, owner, name, descriptor, itf.get());
        if (itf.get()) return new MethodInsnNode(INVOKEINTERFACE, owner, name, descriptor, true);
        if (owner.equals(node.name) && (node.version & 0xFFFF) < V11) {
            var found = resolver.methods(owner, name.hashCode(), RadonCipher.descriptorHash(descriptor));
            if (found.isPresent() && found.get().stream().anyMatch(m -> m.owner().equals(owner) && m.name().equals(name)
                && m.descriptor().equals(descriptor) && (m.access() & ACC_PRIVATE) != 0))
                return new MethodInsnNode(INVOKESPECIAL, owner, name, descriptor, false);
        }
        return new MethodInsnNode(INVOKEVIRTUAL, owner, name, descriptor, false);
    }

    // ---------------------------------------------------------------------------------------------- hide code

    /** Members with Radon's hiding flags: {@code bridge} on a method that is no bridge, or {@code synthetic} on an initializer. */
    static int hiddenMembers(Map<String,ClassNode> nodes) {
        int hidden = 0;
        for (var node : nodes.values()) for (var method : node.methods) {
            if ((method.access & ACC_BRIDGE) != 0 && !method.name.startsWith("<") && !bridge(node, method)) hidden++;
            if ((method.access & ACC_SYNTHETIC) != 0 && method.name.startsWith("<") && !accessConstructor(node, method)) hidden++;
        }
        return hidden;
    }

    private static int unhide(Map<String,ClassNode> nodes, Set<String> injected, Set<String> changed) {
        int cleared = 0;
        for (var node : nodes.values()) {
            if (injected.contains(node.name)) continue;
            int before = cleared;
            if ((node.access & ACC_SYNTHETIC) != 0 && !switchMap(node)) { node.access &= ~ACC_SYNTHETIC; cleared++; }
            for (var method : node.methods) {
                if ((method.access & ACC_BRIDGE) != 0 && !method.name.startsWith("<") && !bridge(node, method)) {
                    method.access &= ~(ACC_BRIDGE | ACC_SYNTHETIC); cleared++;
                } else if ((method.access & ACC_SYNTHETIC) != 0 && (method.access & ACC_BRIDGE) == 0 && !compilerMethod(node, method)) {
                    method.access &= ~ACC_SYNTHETIC; cleared++;
                }
            }
            for (var field : node.fields)
                if ((field.access & ACC_SYNTHETIC) != 0 && !compilerField(field.name)) { field.access &= ~ACC_SYNTHETIC; cleared++; }
            if (cleared != before) changed.add(node.name);
        }
        return cleared;
    }

    /** javac's bridge: loads (with casts), one call to a method of the same name in the class, and a return. */
    static boolean bridge(ClassNode node, MethodNode method) {
        MethodInsnNode call = null;
        for (var insn : method.instructions) {
            int op = insn.getOpcode();
            if (op < 0 || op >= ILOAD && op <= ALOAD || op == CHECKCAST || op >= IRETURN && op <= RETURN) continue;
            if (insn instanceof MethodInsnNode m && call == null && m.name.equals(method.name) && !m.desc.equals(method.desc)) { call = m; continue; }
            return false;
        }
        return call != null;
    }
    private static boolean accessConstructor(ClassNode node, MethodNode method) {
        if (!method.name.equals("<init>")) return false;
        var arguments = Type.getArgumentTypes(method.desc);
        return arguments.length > 0 && arguments[arguments.length - 1].getSort() == Type.OBJECT
            && arguments[arguments.length - 1].getInternalName().matches(".*\\$\\d+");
    }
    private static boolean compilerMethod(ClassNode node, MethodNode method) {
        String name = method.name;
        return name.startsWith("lambda$") || name.startsWith("access$") || name.equals("$values") || name.equals("$deserializeLambda$")
            || name.startsWith("$SWITCH_TABLE$") || accessConstructor(node, method);
    }
    private static boolean compilerField(String name) {
        return name.startsWith("this$") || name.startsWith("val$") || name.equals("$assertionsDisabled") || name.startsWith("$SwitchMap$")
            || name.equals("$VALUES") || name.startsWith("class$") || name.startsWith("$SWITCH_TABLE$");
    }
    private static boolean switchMap(ClassNode node) {
        return !node.fields.isEmpty() && node.fields.stream().allMatch(field -> field.name.startsWith("$SwitchMap$"));
    }

    /** Counts (or, with {@code remove}, removes) annotations whose type descriptor is {@code @}, which is no type. */
    static int badAnnotations(Map<String,ClassNode> nodes, boolean remove) {
        int found = 0;
        for (var node : nodes.values()) {
            found += bad(node.visibleAnnotations, remove) + bad(node.invisibleAnnotations, remove);
            for (var method : node.methods) found += bad(method.visibleAnnotations, remove) + bad(method.invisibleAnnotations, remove);
            for (var field : node.fields) found += bad(field.visibleAnnotations, remove) + bad(field.invisibleAnnotations, remove);
        }
        return found;
    }
    private static int bad(List<AnnotationNode> annotations, boolean remove) {
        if (annotations == null) return 0;
        int found = (int) annotations.stream().filter(a -> a.desc.equals("@")).count();
        if (remove) annotations.removeIf(a -> a.desc.equals("@"));
        return found;
    }
    /**
     * Static constants Radon moved into {@code <clinit>}: {@code constant; putstatic own final field} pairs at its very start
     * become the field's {@code ConstantValue} again; an initializer left with only {@code return} (Radon creates one when
     * the class had none) is removed.
     */
    private static int constants(Map<String,ClassNode> nodes, Set<String> injected, Set<String> changed) {
        int restored = 0;
        for (var node : nodes.values()) {
            if (injected.contains(node.name)) continue;
            var clinit = node.methods.stream().filter(m -> m.name.equals("<clinit>")).findFirst().orElse(null);
            if (clinit == null) continue;
            var fields = new HashMap<String,FieldNode>();
            for (var field : node.fields) if ((field.access & (ACC_STATIC | ACC_FINAL)) == (ACC_STATIC | ACC_FINAL) && field.value == null) fields.put(field.name, field);
            int before = restored;
            for (var insn = Code.real(clinit.instructions.getFirst()); insn != null; ) {
                var put = insn.getNext() == null ? null : Code.real(insn.getNext());
                Object value = insn instanceof LdcInsnNode ldc && ldc.cst instanceof String text ? text : ConstantFolder.constant(insn);
                if (value == null || !(put instanceof FieldInsnNode field) || field.getOpcode() != PUTSTATIC || !field.owner.equals(node.name)) break;
                var target = fields.get(field.name);
                if (target == null || !target.desc.equals(field.desc) || !fits(value, field.desc) || labelBetween(insn, put)) break;
                target.value = value; fields.remove(field.name);
                var next = put.getNext() == null ? null : Code.real(put.getNext());
                clinit.instructions.remove(insn); clinit.instructions.remove(put);
                restored++; insn = next;
            }
            if (restored == before) continue;
            changed.add(node.name);
            var first = Code.real(clinit.instructions.getFirst());
            if (first != null && first.getOpcode() == RETURN && Code.real(first.getNext()) == null
                && (clinit.tryCatchBlocks == null || clinit.tryCatchBlocks.isEmpty())) node.methods.remove(clinit);
        }
        return restored;
    }
    private static boolean fits(Object value, String descriptor) {
        return switch (descriptor) {
            case "I", "S", "B", "C", "Z" -> value instanceof Integer;
            case "J" -> value instanceof Long; case "F" -> value instanceof Float; case "D" -> value instanceof Double;
            case "Ljava/lang/String;" -> value instanceof String;
            default -> false;
        };
    }
    private static boolean labelBetween(AbstractInsnNode from, AbstractInsnNode to) {
        for (var node = from.getNext(); node != null && node != to; node = node.getNext()) if (node instanceof LabelNode) return true;
        return false;
    }

    // ---------------------------------------------------------------------------------------------- helpers

    /** Whether the class still names {@code type} anywhere in its code, members or headers. */
    private static boolean references(ClassNode node, String type) {
        String descriptor = "L" + type + ";";
        if (type.equals(node.superName) || node.interfaces != null && node.interfaces.contains(type)) return true;
        for (var field : node.fields) if (field.desc.contains(descriptor)) return true;
        for (var method : node.methods) {
            if (method.desc.contains(descriptor)) return true;
            if (method.tryCatchBlocks != null) for (var block : method.tryCatchBlocks) if (type.equals(block.type)) return true;
            for (var insn : method.instructions) {
                if (insn instanceof MethodInsnNode m && (m.owner.equals(type) || m.desc.contains(descriptor))) return true;
                if (insn instanceof FieldInsnNode f && (f.owner.equals(type) || f.desc.contains(descriptor))) return true;
                if (insn instanceof TypeInsnNode t && (t.desc.equals(type) || t.desc.contains(descriptor))) return true;
                if (insn instanceof InvokeDynamicInsnNode d && (d.bsm.getOwner().equals(type) || d.desc.contains(descriptor))) return true;
                if (insn instanceof LdcInsnNode l && (l.cst instanceof Type t && t.getDescriptor().contains(descriptor)
                    || l.cst instanceof Handle h && h.getOwner().equals(type))) return true;
                if (insn instanceof MultiANewArrayInsnNode a && a.desc.contains(descriptor)) return true;
            }
        }
        return false;
    }

    /** Line numbers and local variable ranges whose labels were orphaned are harmless, but variables of removed slots go. */
    private static void dropStaleDebug(MethodNode method) {
        if (method.localVariables == null) return;
        var labels = new HashSet<LabelNode>();
        for (var insn : method.instructions) if (insn instanceof LabelNode label) labels.add(label);
        method.localVariables.removeIf(local -> !labels.contains(local.start) || !labels.contains(local.end)
            || method.instructions.indexOf(local.start) > method.instructions.indexOf(local.end));
    }

    private static AbstractInsnNode previous(AbstractInsnNode insn) {
        for (var node = insn.getPrevious(); node != null; node = node.getPrevious()) if (node.getOpcode() >= 0) return node;
        return null;
    }
    private static AbstractInsnNode next(AbstractInsnNode insn) {
        for (var node = insn.getNext(); node != null; node = node.getNext()) if (node.getOpcode() >= 0) return node;
        return null;
    }
}
