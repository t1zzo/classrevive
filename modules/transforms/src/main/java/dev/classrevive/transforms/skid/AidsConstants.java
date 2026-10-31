package dev.classrevive.transforms.skid;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;
import java.util.*;

/**
 * Phase 2CZ: Aidsfuscator's encrypted numbers and strings.
 * <ul>
 *   <li><b>decryptors</b> are recognized by their shape: a string decryptor is a static method with primitive
 *   parameters returning {@code String} that reads its own caller from a stack trace ({@code new Throwable()}) and two
 *   array fields of its class (the strings and the trace cache); a number decryptor is a static method with int
 *   parameters returning {@code int} that only computes on one {@code int[]} field of its class;</li>
 *   <li>{@link #prepare}: the {@code <clinit>} is interpreted by {@link AidsEvaluator} from its start until the
 *   program's own code begins, which fills those arrays (the salt and hash fields are set by constants by then);</li>
 *   <li>{@link #decrypt}, every round: each decryptor call whose arguments are all literals is evaluated (the stack
 *   trace's caller is the method of the call) and replaced by its value. Aidsfuscator also encrypts the salts it passes
 *   as literals, so a decrypted number can reveal a salt parameter in the next round;</li>
 *   <li>{@link #finish}: decryptors nothing calls go, and so does the initializer: the {@code <clinit>} starts where the
 *   program's code began, when the stack is empty there and nothing outside reads a field the skipped code writes. The
 *   arrays of a decryptor that stays are renamed apart (Java source cannot declare {@code int[] a} next to
 *   {@code String[] a});</li>
 *   <li>Flow Ints' masked longs, {@code (lo ^ v) | (hi ^ v) << 32} written out with the block value {@code v} as a
 *   literal, become the long again.</li>
 * </ul>
 */
final class AidsConstants implements Opcodes {
    int strings, numbers, notDecrypted, decryptorsRemoved, initializersRemoved, fieldsRemoved, fieldsRenamed, longsFolded, refused;
    /** Up to 20 classes whose initializer could not be run, and why. */
    final List<String> failures = new ArrayList<>();

    /** A class with decryptors whose initializer ran: its evaluator (arrays filled) and where the program's code starts. */
    private static final class Prepared {
        final ClassNode node; final MethodNode clinit; final Map<String,MethodNode> decryptors; final Set<String> fields;
        final AidsEvaluator evaluator; final AidsEvaluator.Stop stop; final LabelNode start = new LabelNode();
        Prepared(ClassNode node, MethodNode clinit, Map<String,MethodNode> decryptors, Set<String> fields, AidsEvaluator evaluator, AidsEvaluator.Stop stop) {
            this.node = node; this.clinit = clinit; this.decryptors = decryptors; this.fields = fields; this.evaluator = evaluator; this.stop = stop;
        }
    }
    private final Map<String,Prepared> prepared = new LinkedHashMap<>();
    private final Set<String> attempted = new HashSet<>();
    private final Map<String,String> lastFailure = new LinkedHashMap<>();

    /**
     * Runs the initializer of every class with decryptors that has not run yet. {@code seedFields}: the salt and hash
     * fields ({@code owner.name}), set by constants at the start of the {@code <clinit>} and readable by the initializer.
     */
    void prepare(Map<String,ClassNode> nodes, Set<String> skip, Set<String> seedFields) {
        for (var node : nodes.values()) {
            if (skip.contains(node.name) || attempted.contains(node.name)) continue;
            var decryptors = new LinkedHashMap<String,MethodNode>();
            for (var method : node.methods)
                if (stringDecryptor(node, method) || numberDecryptor(node, method)) decryptors.put(method.name + method.desc, method);
            if (decryptors.isEmpty()) continue;
            MethodNode clinit = null;
            for (var method : node.methods) if (method.name.equals("<clinit>")) clinit = method;
            if (clinit == null) continue;
            var fields = new HashSet<String>();
            for (var method : decryptors.values())
                for (var insn : method.instructions)
                    if (insn instanceof FieldInsnNode field && field.owner.equals(node.name)) fields.add(field.name + ":" + field.desc);
            var allowed = new HashSet<String>(fields);
            for (var field : node.fields) if (seedFields.contains(node.name + "." + field.name) && field.desc.equals("I")) allowed.add(field.name + ":I");
            var evaluator = new AidsEvaluator(node);
            AidsEvaluator.Stop stop;
            try {
                stop = evaluator.runInitializer(clinit, allowed);
            } catch (AidsEvaluator.Refusal refusal) {
                // Tried again next round (a key may be folded by then); only the last reason is kept.
                lastFailure.put(node.name, refusal.getMessage());
                continue;
            }
            if (!fields.stream().allMatch(evaluator.statics::containsKey)) { lastFailure.put(node.name, "the initializer did not fill " + fields); continue; }
            lastFailure.remove(node.name);
            attempted.add(node.name);
            var ready = new Prepared(node, clinit, decryptors, fields, evaluator, stop);
            // Marked now: the statement's first instruction may be a decryptor argument that is replaced later.
            if (stop.at() != null) clinit.instructions.insertBefore(stop.at(), ready.start);
            prepared.put(node.name, ready);
        }
    }

    /** Replaces every decryptor call whose arguments are literals; returns the number replaced. */
    int decrypt(ClassNode node) {
        var ready = prepared.get(node.name);
        if (ready == null) return 0;
        int count = 0;
        for (var method : node.methods) {
            if (ready.decryptors.containsValue(method)) continue;
            for (var insn : method.instructions.toArray()) {
                if (!(insn instanceof MethodInsnNode call) || call.getOpcode() != INVOKESTATIC || !call.owner.equals(node.name)) continue;
                var decryptor = ready.decryptors.get(call.name + call.desc);
                if (decryptor == null) continue;
                var pushes = literalArguments(call);
                if (pushes == null) continue;
                Object value;
                try {
                    var args = new Object[pushes.size()];
                    for (int k = 0; k < args.length; k++) args[k] = SkidModel.literal(pushes.get(k));
                    value = ready.evaluator.call(decryptor, args, method.name);
                } catch (AidsEvaluator.Refusal refusal) {
                    continue;
                }
                AbstractInsnNode replacement;
                if (value instanceof String text) { replacement = new LdcInsnNode(text); strings++; }
                else if (value instanceof Integer number) { replacement = MethodRewriter.push(number); numbers++; }
                else continue;
                for (var push : pushes) method.instructions.remove(push);
                method.instructions.set(call, replacement);
                count++;
            }
        }
        return count;
    }

    /** Removes the decryptors nothing calls and, when nothing else needs them, the initializers and their arrays. */
    void finish(Map<String,ClassNode> nodes, Set<String> changed, MethodRewriter rewriter) {
        lastFailure.forEach((name, why) -> { refused++; if (failures.size() < 20) failures.add(name + ": " + why); });
        for (var ready : prepared.values()) {
            var node = ready.node;
            for (var method : node.methods)
                for (var insn : method.instructions)
                    if (insn instanceof MethodInsnNode call && call.owner.equals(node.name) && ready.decryptors.containsKey(call.name + call.desc)
                        && !ready.decryptors.containsValue(method)) notDecrypted++;
            var called = new HashSet<String>();
            for (var method : node.methods)
                for (var insn : method.instructions)
                    if (insn instanceof MethodInsnNode call && call.owner.equals(node.name)) called.add(call.name + call.desc);
            int before = node.methods.size();
            node.methods.removeIf(method -> ready.decryptors.containsValue(method) && !called.contains(method.name + method.desc));
            decryptorsRemoved += before - node.methods.size();
            if (before != node.methods.size()) changed.add(node.name);
            // The skipped code writes the arrays and the salt and hash fields; nothing after it may read one.
            var written = ready.evaluator.written;
            boolean read = false;
            for (var other : nodes.values())
                for (var method : other.methods)
                    for (var insn : method.instructions)
                        if (insn instanceof FieldInsnNode field && field.owner.equals(node.name) && (insn.getOpcode() == GETSTATIC)
                            && written.contains(field.name + ":" + field.desc) && !(method == ready.clinit && before(ready, insn))) read = true;
            if (!read) {
                if (ready.stop.at() == null) {
                    ready.clinit.instructions.clear(); ready.clinit.tryCatchBlocks.clear();
                    ready.clinit.instructions.add(new InsnNode(RETURN));
                } else {
                    ready.clinit.instructions.insert(new JumpInsnNode(GOTO, ready.start));
                    rewriter.rewrite(node.name, ready.clinit, Map.of(), new MethodRewriter.Stats());
                }
                initializersRemoved++;
                changed.add(node.name);
                var still = new HashSet<String>();
                for (var method : node.methods)
                    for (var insn : method.instructions)
                        if (insn instanceof FieldInsnNode field && field.owner.equals(node.name)) still.add(field.name + ":" + field.desc);
                int count = node.fields.size();
                node.fields.removeIf(field -> (field.access & ACC_STATIC) != 0 && ready.fields.contains(field.name + ":" + field.desc) && !still.contains(field.name + ":" + field.desc));
                fieldsRemoved += count - node.fields.size();
                if (onlyReturn(ready.clinit)) node.methods.remove(ready.clinit);
            } else {
                if (renameKept(node, ready.fields)) changed.add(node.name);
                if (standardCharsets(ready) > 0) changed.add(node.name);
            }
        }
    }

    private static final Map<String,String> CHARSETS = Map.of("ISO-8859-1", "ISO_8859_1", "UTF-8", "UTF_8", "US-ASCII", "US_ASCII",
        "UTF-16", "UTF_16", "UTF-16BE", "UTF_16BE", "UTF-16LE", "UTF_16LE");

    /**
     * In an initializer that stays, {@code getBytes("ISO-8859-1")} and {@code new String(bytes, "ISO-8859-1")} become
     * their {@code StandardCharsets} forms: the same bytes, without the checked {@code UnsupportedEncodingException}
     * that javac refuses in a static initializer.
     */
    private static int standardCharsets(Prepared ready) {
        int count = 0;
        for (var insn : ready.clinit.instructions.toArray()) {
            if (!ready.evaluator.executed.contains(insn) || !(insn instanceof MethodInsnNode call) || !call.owner.equals("java/lang/String")) continue;
            boolean bytes = call.name.equals("getBytes") && call.desc.equals("(Ljava/lang/String;)[B");
            boolean string = call.name.equals("<init>") && call.desc.equals("([BLjava/lang/String;)V");
            if (!bytes && !string) continue;
            var name = SeedInterpreter.previous(call);
            if (!(name instanceof LdcInsnNode ldc) || !(ldc.cst instanceof String charset) || !CHARSETS.containsKey(charset)) continue;
            ready.clinit.instructions.set(name, new FieldInsnNode(GETSTATIC, "java/nio/charset/StandardCharsets", CHARSETS.get(charset), "Ljava/nio/charset/Charset;"));
            call.desc = bytes ? "(Ljava/nio/charset/Charset;)[B" : "([BLjava/nio/charset/Charset;)V";
            count++;
        }
        return count;
    }

    /** Whether an instruction of the {@code <clinit>} lies in the skipped initializer (before the program's start). */
    private static boolean before(Prepared ready, AbstractInsnNode insn) {
        if (ready.stop.at() == null) return true;
        return ready.evaluator.executed.contains(insn);
    }

    private static boolean onlyReturn(MethodNode method) {
        for (var insn : method.instructions) if (insn.getOpcode() >= 0 && insn.getOpcode() != RETURN) return false;
        return true;
    }

    // ---------------------------------------------------------------- decryptors

    static boolean stringDecryptor(ClassNode node, MethodNode method) {
        if ((method.access & ACC_STATIC) == 0 || !Type.getReturnType(method.desc).getDescriptor().equals("Ljava/lang/String;")) return false;
        var args = Type.getArgumentTypes(method.desc);
        if (args.length == 0 || Arrays.stream(args).anyMatch(t -> t.getSort() > Type.INT || t.getSort() < Type.CHAR)) return false;
        boolean trace = false, strings = false, cache = false;
        for (var insn : method.instructions) {
            if (insn instanceof MethodInsnNode call && call.owner.equals("java/lang/Throwable") && call.name.equals("getStackTrace")) trace = true;
            if (insn instanceof FieldInsnNode field && field.getOpcode() == GETSTATIC && field.owner.equals(node.name)) {
                if (field.desc.equals("[Ljava/lang/String;")) strings = true;
                if (field.desc.equals("[Ljava/lang/Object;")) cache = true;
            }
        }
        return trace && strings && cache;
    }

    static boolean numberDecryptor(ClassNode node, MethodNode method) {
        if ((method.access & ACC_STATIC) == 0 || !method.desc.endsWith(")I")) return false;
        var args = Type.getArgumentTypes(method.desc);
        if (args.length == 0 || Arrays.stream(args).anyMatch(t -> t.getSort() != Type.INT)) return false;
        boolean array = false;
        for (var insn : method.instructions) {
            if (insn instanceof MethodInsnNode || insn instanceof InvokeDynamicInsnNode || insn instanceof TypeInsnNode) return false;
            if (insn instanceof FieldInsnNode field) {
                if (field.getOpcode() != GETSTATIC || !field.owner.equals(node.name) || !field.desc.equals("[I")) return false;
                array = true;
            }
            int op = insn.getOpcode();
            if (op == IASTORE || op == PUTSTATIC || op == ATHROW) return false;
        }
        return array;
    }

    /** The literal pushes right before a call, one per parameter in order (no label in between), or null. */
    private static List<AbstractInsnNode> literalArguments(MethodInsnNode call) {
        var types = Type.getArgumentTypes(call.desc);
        var pushes = new ArrayList<AbstractInsnNode>();
        AbstractInsnNode at = call;
        for (int k = types.length - 1; k >= 0; k--) {
            var previous = SeedInterpreter.previous(at);
            if (SkidModel.literal(previous) == null) return null;
            for (var node = previous.getNext(); node != at; node = node.getNext()) if (node instanceof LabelNode) return null;
            pushes.addFirst(previous);
            at = previous;
        }
        return pushes;
    }

    /**
     * The arrays of a decryptor that stays (a call no key reveals) keep their initializer; Aidsfuscator names them like
     * other fields of other types ({@code int[] a}, {@code String[] a}), which Java source cannot declare. Each one whose
     * name another field of the class shares gets a name of its own ({@code aidsNumbers}, {@code aidsStrings},
     * {@code aidsTraceCache}); they are private to the class, so only the class's own instructions change.
     */
    private boolean renameKept(ClassNode node, Set<String> fields) {
        boolean renamed = false;
        var names = new HashSet<String>();
        for (var field : node.fields) names.add(field.name);
        for (var field : node.fields) {
            if (!fields.contains(field.name + ":" + field.desc) || (field.access & ACC_PRIVATE) == 0) continue;
            String name = field.name, desc = field.desc;
            if (node.fields.stream().noneMatch(other -> other != field && other.name.equals(name))) continue;
            String base = switch (desc) { case "[I" -> "aidsNumbers"; case "[Ljava/lang/String;" -> "aidsStrings"; default -> "aidsTraceCache"; };
            String fresh = base;
            for (int k = 2; names.contains(fresh); k++) fresh = base + k;
            names.add(fresh);
            field.name = fresh;
            for (var method : node.methods)
                for (var insn : method.instructions)
                    if (insn instanceof FieldInsnNode ref && ref.owner.equals(node.name) && ref.name.equals(name) && ref.desc.equals(desc)) ref.name = fresh;
            fieldsRenamed++;
            renamed = true;
        }
        return renamed;
    }

    // ---------------------------------------------------------------- masked longs

    /** {@code ldc2_w obf; dup2; 32 lshl 32 lushr; v i2l 0xFFFFFFFF land lxor; dup2_x2 pop2; 32 lushr; v i2l 0xFFFFFFFF land lxor; 32 lshl; lor}. */
    int foldMaskedLongs(MethodNode method) {
        int folded = 0;
        var removed = Collections.newSetFromMap(new IdentityHashMap<AbstractInsnNode,Boolean>());
        for (var insn : method.instructions.toArray()) {
            if (!(insn instanceof LdcInsnNode ldc) || !(ldc.cst instanceof Long obf) || removed.contains(insn)) continue;
            var nodes = new ArrayList<AbstractInsnNode>();
            nodes.add(insn);
            AbstractInsnNode at = insn;
            boolean label = false;
            for (int k = 0; k < 22; k++) {
                at = at.getNext();
                while (at != null && at.getOpcode() < 0) { if (at instanceof LabelNode) label = true; at = at.getNext(); }
                if (at == null) break;
                nodes.add(at);
            }
            if (label || nodes.size() != 23) continue;
            Integer v = SkidModel.literal(nodes.get(6)), w = SkidModel.literal(nodes.get(15));
            if (v == null || !v.equals(w) || !shape(nodes)) continue;
            long mask = v & 0xFFFFFFFFL;
            long first = (obf << 32 >>> 32) ^ mask, second = (obf >>> 32) ^ mask;
            method.instructions.insertBefore(insn, new LdcInsnNode(first | second << 32));
            for (var node : nodes) method.instructions.remove(node);
            removed.addAll(nodes);
            folded++;
        }
        longsFolded += folded;
        return folded;
    }

    private static boolean shape(List<AbstractInsnNode> n) {
        return n.get(1).getOpcode() == DUP2 && is32(n.get(2)) && n.get(3).getOpcode() == LSHL && is32(n.get(4)) && n.get(5).getOpcode() == LUSHR
            && n.get(7).getOpcode() == I2L && mask(n.get(8)) && n.get(9).getOpcode() == LAND && n.get(10).getOpcode() == LXOR
            && n.get(11).getOpcode() == DUP2_X2 && n.get(12).getOpcode() == POP2 && is32(n.get(13)) && n.get(14).getOpcode() == LUSHR
            && n.get(16).getOpcode() == I2L && mask(n.get(17)) && n.get(18).getOpcode() == LAND && n.get(19).getOpcode() == LXOR
            && is32(n.get(20)) && n.get(21).getOpcode() == LSHL && n.get(22).getOpcode() == LOR;
    }
    private static boolean is32(AbstractInsnNode insn) { Integer value = SkidModel.literal(insn); return value != null && value == 32; }
    private static boolean mask(AbstractInsnNode insn) { return insn instanceof LdcInsnNode ldc && ldc.cst instanceof Long l && l == 0xFFFFFFFFL; }
}
