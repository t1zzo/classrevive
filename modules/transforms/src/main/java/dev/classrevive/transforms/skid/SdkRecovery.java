package dev.classrevive.transforms.skid;

import net.openhft.hashing.LongHashFunction;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.*;
import java.net.URI;
import java.nio.file.*;
import java.util.*;

/**
 * Phase 2CR: undoes the transformers that call Skidfuscator's {@code sdk/SDK} runtime.
 * <ul>
 *   <li><b>Type Check</b>: {@code o instanceof T} became {@code SDK.checkType(o, "<xxh3(seed) of T's internal name>", seed)};
 *   the type is found in a dictionary of class names (the jar's classes, every type the jar names, and the JDK's classes,
 *   listed from the {@code jrt:/} image without loading any) and the call becomes {@code instanceof T} again.</li>
 *   <li><b>String Equals Hash</b> / <b>Equals-IgnoreCase Hash</b>: {@code x.equals("lit")} became
 *   {@code SDK.hash(x).equals("<xxh3 of lit>")} (lower-cased first for {@code equalsIgnoreCase}). The literal is gone: it is
 *   recovered only when it is in a dictionary of the jar's strings and names. Unrecovered comparisons stay as they are.</li>
 * </ul>
 * XXH3 is computed with OpenHFT zero-allocation-hashing (Skidfuscator's {@code sdk/*} is a copy of it); the {@code sdk/*}
 * classes of the target are never run. They are removed once no other class refers to them.
 */
final class SdkRecovery implements Opcodes {
    static final String SDK = "sdk/SDK";
    int typeChecks, typeUnresolved, equalsRecovered, equalsUnresolved, sdkClassesRemoved;
    private Map<Long,String> unseeded;
    private final Map<Integer,Map<Long,String>> typeTables = new HashMap<>();
    private Set<String> typeNames;

    void apply(Map<String,ClassNode> nodes, Set<String> changed, Set<String> removedClasses) {
        if (!nodes.containsKey(SDK)) return;
        for (var node : nodes.values()) {
            if (node.name.startsWith("sdk/")) continue;
            for (var method : node.methods) {
                if (method.instructions.size() == 0) continue;
                boolean uses = false;
                for (var insn : method.instructions) if (insn instanceof MethodInsnNode call && call.owner.equals(SDK)) { uses = true; break; }
                if (!uses) continue;
                Frame<SourceValue>[] frames;
                try { frames = new Analyzer<>(new SourceInterpreter()).analyze(node.name, method); }
                catch (AnalyzerException error) { continue; }
                var insns = method.instructions.toArray();
                var edits = new ArrayList<Runnable>();
                for (int i = 0; i < insns.length; i++) {
                    if (!(insns[i] instanceof MethodInsnNode call) || frames[i] == null) continue;
                    var frame = frames[i];
                    if (call.owner.equals(SDK) && call.name.equals("checkType") && call.desc.equals("(Ljava/lang/Object;Ljava/lang/String;I)Z")) {
                        var hash = single(frame.getStack(frame.getStackSize() - 2)); var seed = single(frame.getStack(frame.getStackSize() - 1));
                        Integer seedValue = SkidModel.literal(seed);
                        if (!(hash instanceof LdcInsnNode ldc) || !(ldc.cst instanceof String text) || seedValue == null || !adjacent(hash, seed, call)) { typeUnresolved++; continue; }
                        String type = typeName(nodes, seedValue, text);
                        if (type == null) { typeUnresolved++; continue; }
                        edits.add(() -> {
                            method.instructions.remove(hash); method.instructions.remove(seed);
                            method.instructions.set(call, new TypeInsnNode(INSTANCEOF, type));
                        });
                        typeChecks++;
                    } else if (call.owner.equals("java/lang/String") && (call.name.equals("equals") && call.desc.equals("(Ljava/lang/Object;)Z")
                        || call.name.equals("equalsIgnoreCase") && call.desc.equals("(Ljava/lang/String;)Z"))) {
                        boolean ignoreCase = call.name.equals("equalsIgnoreCase");
                        var receiver = single(frame.getStack(frame.getStackSize() - 2)); var argument = single(frame.getStack(frame.getStackSize() - 1));
                        AbstractInsnNode constant, hashed;
                        if (isSdkHash(argument) && receiver instanceof LdcInsnNode) { constant = receiver; hashed = argument; }
                        else if (isSdkHash(receiver) && argument instanceof LdcInsnNode) { constant = argument; hashed = receiver; }
                        else continue;
                        if (!(((LdcInsnNode) constant).cst instanceof String text)) continue;
                        long value;
                        try { value = Long.parseLong(text); } catch (NumberFormatException error) { continue; }
                        String literal = literal(nodes, value, ignoreCase);
                        if (literal == null) { equalsUnresolved++; continue; }
                        // The hash's input: for equalsIgnoreCase, x.toLowerCase() (its call goes too).
                        int at = index(insns, hashed);
                        var input = at < 0 || frames[at] == null ? null : single(frames[at].getStack(frames[at].getStackSize() - 1));
                        boolean lower = ignoreCase && input instanceof MethodInsnNode m && m.owner.equals("java/lang/String") && m.name.equals("toLowerCase") && m.desc.equals("()Ljava/lang/String;");
                        edits.add(() -> {
                            ((LdcInsnNode) constant).cst = literal;
                            method.instructions.remove(hashed);
                            if (lower) method.instructions.remove(input);
                        });
                        equalsRecovered++;
                    }
                }
                if (!edits.isEmpty()) { edits.forEach(Runnable::run); changed.add(node.name); }
            }
        }
        // The runtime goes when nothing outside sdk/ refers to it.
        boolean referenced = false;
        for (var node : nodes.values()) {
            if (node.name.startsWith("sdk/")) continue;
            for (var method : node.methods)
                for (var insn : method.instructions)
                    if (insn instanceof MethodInsnNode call && call.owner.startsWith("sdk/") || insn instanceof TypeInsnNode type && type.desc.startsWith("sdk/")
                        || insn instanceof FieldInsnNode field && field.owner.startsWith("sdk/")) referenced = true;
        }
        if (!referenced) for (var name : nodes.keySet()) if (name.startsWith("sdk/")) { removedClasses.add(name); sdkClassesRemoved++; }
    }

    private static boolean isSdkHash(AbstractInsnNode insn) {
        return insn instanceof MethodInsnNode call && call.owner.equals(SDK) && call.name.equals("hash") && call.desc.equals("(Ljava/lang/String;)Ljava/lang/String;");
    }

    private static AbstractInsnNode single(SourceValue value) { return value.insns.size() == 1 ? value.insns.iterator().next() : null; }

    private static int index(AbstractInsnNode[] insns, AbstractInsnNode insn) { for (int i = 0; i < insns.length; i++) if (insns[i] == insn) return i; return -1; }

    /** {@code hash; seed; call} with nothing but labels-free code between: they are removed as one. */
    private static boolean adjacent(AbstractInsnNode hash, AbstractInsnNode seed, AbstractInsnNode call) {
        return SeedInterpreter.next(hash) == seed && SeedInterpreter.next(seed) == call;
    }

    private String typeName(Map<String,ClassNode> nodes, int seed, String hash) {
        long value;
        try { value = Long.parseLong(hash); } catch (NumberFormatException error) { return null; }
        var table = typeTables.computeIfAbsent(seed, s -> {
            var function = LongHashFunction.xx3(s);
            var result = new HashMap<Long,String>();
            for (String name : typeNames(nodes)) result.putIfAbsent(function.hashChars(name), name);
            return result;
        });
        return table.get(value);
    }

    /** Internal names of the jar's classes, every type the jar mentions, and the JDK's classes. */
    private Set<String> typeNames(Map<String,ClassNode> nodes) {
        if (typeNames != null) return typeNames;
        var names = new TreeSet<String>(nodes.keySet());
        for (var node : nodes.values()) {
            if (node.superName != null) names.add(node.superName);
            names.addAll(node.interfaces);
            for (var field : node.fields) addType(names, Type.getType(field.desc));
            for (var method : node.methods) {
                for (var type : Type.getArgumentTypes(method.desc)) addType(names, type);
                addType(names, Type.getReturnType(method.desc));
                for (var insn : method.instructions) {
                    if (insn instanceof TypeInsnNode t) addType(names, Type.getObjectType(t.desc));
                    else if (insn instanceof MethodInsnNode m) addType(names, Type.getObjectType(m.owner));
                    else if (insn instanceof FieldInsnNode f) { addType(names, Type.getObjectType(f.owner)); addType(names, Type.getType(f.desc)); }
                    else if (insn instanceof LdcInsnNode l && l.cst instanceof Type t && t.getSort() != Type.METHOD) addType(names, t);
                }
                for (var block : method.tryCatchBlocks) if (block.type != null) names.add(block.type);
            }
        }
        try {
            var jrt = FileSystems.getFileSystem(URI.create("jrt:/"));
            try (var paths = Files.walk(jrt.getPath("/modules"))) {
                paths.forEach(path -> {
                    String text = path.toString();
                    if (!text.endsWith(".class") || path.getNameCount() < 3) return;
                    String name = path.subpath(2, path.getNameCount()).toString().replace('\\', '/');
                    names.add(name.substring(0, name.length() - ".class".length()));
                });
            }
        } catch (Exception error) {
            // Without the runtime image only the jar's own names are tried.
        }
        typeNames = names;
        return names;
    }

    /** Short strings found by {@link #search}, for every equals hash of the jar (computed once). */
    private Map<Long,String> searched;

    private Map<Long,String> searched(Map<String,ClassNode> nodes) {
        if (searched != null) return searched;
        var wanted = new HashSet<Long>();
        for (var node : nodes.values())
            for (var method : node.methods)
                for (var insn : method.instructions)
                    if (isSdkHash(insn)) {
                        for (var near : new AbstractInsnNode[] {SeedInterpreter.next(insn), SeedInterpreter.previous(insn), SeedInterpreter.previous(SeedInterpreter.previous(insn))})
                            if (near instanceof LdcInsnNode ldc && ldc.cst instanceof String text)
                                try { wanted.add(Long.parseLong(text)); } catch (NumberFormatException ignored) { }
                    }
        searched = wanted.isEmpty() ? Map.of() : search(wanted);
        return searched;
    }

    static final String LOWER = "abcdefghijklmnopqrstuvwxyz", MIXED = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_-. ";

    /**
     * A bounded exhaustive search for the literals behind equals hashes: every lower-case string of 1..6 letters and every
     * string of 1..4 characters from letters, digits, {@code _-.} and space (about 360 million candidates, in parallel).
     * Longer or other literals stay unrecovered.
     */
    static Map<Long,String> search(Set<Long> wanted) {
        var found = new java.util.concurrent.ConcurrentHashMap<Long,String>();
        for (var plan : List.of(new Object[] {LOWER, 6}, new Object[] {MIXED, 4})) {
            String alphabet = (String) plan[0]; int max = (Integer) plan[1];
            java.util.stream.IntStream.range(0, alphabet.length()).parallel().forEach(first -> {
                var function = LongHashFunction.xx3();
                char[] buffer = new char[max];
                buffer[0] = alphabet.charAt(first);
                if (wanted.contains(function.hashChars(buffer, 0, 1))) found.putIfAbsent(function.hashChars(buffer, 0, 1), new String(buffer, 0, 1));
                for (int length = 2; length <= max; length++) {
                    int[] digits = new int[length];
                    for (int k = 1; k < length; k++) buffer[k] = alphabet.charAt(0);
                    while (true) {
                        long h = function.hashChars(buffer, 0, length);
                        if (wanted.contains(h)) found.putIfAbsent(h, new String(buffer, 0, length));
                        int k = length - 1;
                        while (k >= 1 && ++digits[k] == alphabet.length()) { digits[k] = 0; buffer[k] = alphabet.charAt(0); k--; }
                        if (k < 1) break;
                        buffer[k] = alphabet.charAt(digits[k]);
                    }
                }
            });
        }
        return found;
    }

    private static void addType(Set<String> names, Type type) {
        if (type.getSort() == Type.ARRAY) type = type.getElementType();
        if (type.getSort() == Type.OBJECT) names.add(type.getInternalName());
    }

    /** A dictionary of the jar's strings, names and their lower-case forms, keyed by unseeded XXH3. */
    private String literal(Map<String,ClassNode> nodes, long hash, boolean ignoreCase) {
        if (unseeded == null) {
            var function = LongHashFunction.xx3();
            var words = new LinkedHashSet<String>();
            for (var node : nodes.values()) {
                if (node.name.startsWith("sdk/")) continue;
                String simple = node.name.substring(node.name.lastIndexOf('/') + 1);
                words.add(simple); words.add(simple.substring(simple.lastIndexOf('$') + 1));
                for (var field : node.fields) { words.add(field.name); if (field.value instanceof String s) words.add(s); }
                for (var method : node.methods) {
                    words.add(method.name);
                    for (var insn : method.instructions) if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof String s && s.length() <= 4096) words.add(s);
                }
            }
            unseeded = new HashMap<>();
            for (String word : words) {
                unseeded.putIfAbsent(function.hashChars(word), word);
                unseeded.putIfAbsent(function.hashChars(word.toLowerCase(Locale.ROOT)), word);
            }
        }
        String found = unseeded.get(hash);
        if (found == null) found = searched(nodes).get(hash);
        if (found == null) return null;
        // equals needs the exact text; equalsIgnoreCase accepts any casing (the dictionary's spelling is kept).
        if (!ignoreCase && LongHashFunction.xx3().hashChars(found) != hash) return null;
        return found;
    }
}
