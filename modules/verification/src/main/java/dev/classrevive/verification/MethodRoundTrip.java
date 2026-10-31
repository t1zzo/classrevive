package dev.classrevive.verification;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.zip.ZipFile;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/**
 * Per-method round trip (research proposal C1, first step): compares each original method body with the body javac
 * produced from the chosen source. Bytes are parsed with ASM only; no class is loaded, linked or executed.
 *
 * <p>Both sides are read without debug attributes or frames, so LineNumberTable, LocalVariableTable and constant pool
 * layout never matter. Labels are canonicalized: only jump, switch and handler targets survive, numbered in code order.
 * IDENTICAL means the remaining instruction stream is equal as written. EQUIVALENT_AFTER_NORMALIZATION additionally
 * renumbers non-parameter local slots by first use and folds iconst/bipush/sipush/ldc (and the long, float and double
 * forms) into one constant form. Anything else, including a method missing from the recompiled class, is DIFFERENT.
 * Equal bodies are strong static evidence, not proof of behavior; differences may be legitimate compiler variation.
 *
 * <p>Since Phase 2BH the normalized forms also read calls to synthetic accessors and an enum's {@code $values()} as the
 * instructions they forward to ({@link #forwarded}), a {@code dup} of a just-loaded local as that load
 * ({@link #reloadedDup}), locals by variable rather than slot ({@link #webs}), stores read only by the next loads or
 * never read as {@code dup} or {@code pop} ({@link #localRewrites}), and renamed captured-variable fields as the
 * original ones ({@link #capturedFields}).
 */
public final class MethodRoundTrip {
    public enum Outcome { IDENTICAL, EQUIVALENT_AFTER_NORMALIZATION, DIFFERENT }

    /**
     * @param difference for a body that differs, the first diverging normalized instruction of each side as
     *                   operand-free mnemonics ({@code "astore/dup"}, {@code "<end>"} when one body is shorter); it names
     *                   no constant, member or type of the target, so it can group failures. Empty otherwise.
     */
    public record MethodResult(String name, String descriptor, Outcome outcome, String reason, String difference) {
        public MethodResult(String name, String descriptor, Outcome outcome, String reason) { this(name, descriptor, outcome, reason, ""); }
    }

    private MethodRoundTrip() {}

    /** Compares every original class that has method bodies; classes absent from {@code recompiled} count as DIFFERENT. */
    public static List<ClassRoundTrip> compare(Map<String,byte[]> originals, Map<String,byte[]> recompiled) {
        var contexts = contexts(originals.values(), recompiled.values());
        var result = new ArrayList<ClassRoundTrip>();
        for (var entry : new TreeMap<>(originals).entrySet()) {
            var methods = compareClass(entry.getValue(), recompiled.get(entry.getKey()), contexts[0], contexts[1]);
            if (methods != null) result.add(new ClassRoundTrip(entry.getKey(), methods));
        }
        return List.copyOf(result);
    }

    /**
     * Phase 2BH: what one side's bodies refer to that javac names or emits differently between versions or sources: its
     * synthetic forwarding methods ({@link #forwarded}, by {@code owner.name+desc}) and, on the recompiled side, the
     * captured-variable fields renamed to the original fields they stand for ({@link #capturedFields}, by
     * {@code owner.name:desc}).
     */
    record Context(Map<String,List<AbstractInsnNode>> forwarders, Map<String,String> fields) {
        static final Context NONE = new Context(Map.of(), Map.of());
    }

    /** The original and recompiled sides' contexts for comparing classes of {@code originals} with {@code recompiled}. */
    static Context[] contexts(Collection<byte[]> originals, Collection<byte[]> recompiled) {
        var before = new HashMap<String,ClassNode>(); var after = new HashMap<String,ClassNode>();
        for (byte[] bytes : originals) { var node = parse(bytes); if (node != null) before.put(node.name, node); }
        for (byte[] bytes : recompiled) { var node = parse(bytes); if (node != null) after.put(node.name, node); }
        var fields = new HashMap<String,String>();
        for (var node : after.values()) if (before.containsKey(node.name)) capturedFields(before.get(node.name), node, fields);
        return new Context[] {new Context(forwarders(before.values()), Map.of()), new Context(forwarders(after.values()), fields)};
    }

    /** The forwarding methods ({@link #forwarded}) of the given classes, by {@code owner.name+desc}. */
    static Map<String,List<AbstractInsnNode>> forwarders(Collection<ClassNode> classes) {
        var result = new HashMap<String,List<AbstractInsnNode>>();
        for (var node : classes) {
            for (var method : node.methods) {
                var body = forwarded(node.name, method);
                if (body != null) result.put(node.name + "." + method.name + method.desc, body);
            }
        }
        return result;
    }

    /**
     * Phase 2BH: a local or anonymous class keeps each local variable it captures in a synthetic field named after the
     * variable ({@code val$name}), so a decompiler that names the local differently renames the field, which nothing but
     * reflection can observe. The recompiled class's captured fields that have no original field of the same name and
     * descriptor are paired, in declaration order and per descriptor, with the original's unpaired ones, when both have
     * as many of each descriptor; each pair is then one field for every compared body.
     */
    static void capturedFields(ClassNode before, ClassNode after, Map<String,String> into) {
        var original = captured(before); var recompiled = captured(after);
        var originalNames = new HashSet<String>(); var recompiledNames = new HashSet<String>();
        for (var field : original) originalNames.add(field.name + ":" + field.desc);
        for (var field : recompiled) recompiledNames.add(field.name + ":" + field.desc);
        var left = new LinkedHashMap<String,List<String>>(); var right = new LinkedHashMap<String,List<String>>();
        for (var field : original)
            if (!recompiledNames.contains(field.name + ":" + field.desc)) left.computeIfAbsent(field.desc, ignored -> new ArrayList<>()).add(field.name);
        for (var field : recompiled)
            if (!originalNames.contains(field.name + ":" + field.desc)) right.computeIfAbsent(field.desc, ignored -> new ArrayList<>()).add(field.name);
        if (!left.keySet().equals(right.keySet())) return;
        for (var desc : left.keySet()) if (left.get(desc).size() != right.get(desc).size()) return;
        for (var desc : left.keySet())
            for (int i = 0; i < left.get(desc).size(); i++) into.put(after.name + "." + right.get(desc).get(i) + ":" + desc, left.get(desc).get(i));
    }

    private static List<FieldNode> captured(ClassNode node) {
        var result = new ArrayList<FieldNode>();
        for (var field : node.fields) if ((field.access & Opcodes.ACC_SYNTHETIC) != 0 && field.name.startsWith("val$")) result.add(field);
        return result;
    }

    /** A field instruction as compared: the original field's name for a renamed captured field (see {@link #capturedFields}). */
    static String field(FieldInsnNode field, Map<String,String> renamed) {
        String name = renamed.getOrDefault(field.owner + "." + field.name + ":" + field.desc, field.name);
        return op(field) + " " + field.owner + "." + name + ":" + field.desc;
    }

    private static final java.util.regex.Pattern ACCESSOR = java.util.regex.Pattern.compile("access\\$\\d+");

    /**
     * The instructions a synthetic forwarding method runs on its arguments, or null when {@code method} is not one. Such a
     * method is a static synthetic accessor {@code access$NNN} (javac numbers them in the order it meets the accesses, so
     * the same accessor gets another number when members are printed in another order) or an enum's private static
     * synthetic {@code $values()} (javac 15 and later; older javac builds the array inline in the class initializer).
     * Its body must load each parameter once, in order, from its own slot, then run straight-line code that uses no
     * local, jump, switch or handler, then return the method's return type. A call to it is then the same as running that
     * code on the arguments already on the caller's stack: a static call to a method that cannot be overridden, whose
     * exceptions propagate to the caller as they would from the inlined instructions.
     */
    static List<AbstractInsnNode> forwarded(String owner, MethodNode method) {
        if ((method.access & Opcodes.ACC_STATIC) == 0 || (method.access & Opcodes.ACC_SYNTHETIC) == 0 || !method.tryCatchBlocks.isEmpty()) return null;
        boolean values = method.name.equals("$values") && (method.access & Opcodes.ACC_PRIVATE) != 0 && method.desc.equals("()[L" + owner + ";");
        if (!values && !ACCESSOR.matcher(method.name).matches()) return null;
        var real = new ArrayList<AbstractInsnNode>();
        for (var insn : method.instructions) if (insn.getOpcode() >= 0) real.add(insn);
        Type[] arguments = Type.getArgumentTypes(method.desc);
        if (real.size() < arguments.length + 1 || real.size() > arguments.length + 1 + 256) return null;
        int slot = 0;
        for (int i = 0; i < arguments.length; i++) {
            if (!(real.get(i) instanceof VarInsnNode load) || load.var != slot || load.getOpcode() != arguments[i].getOpcode(Opcodes.ILOAD)) return null;
            slot += arguments[i].getSize();
        }
        if (real.getLast().getOpcode() != Type.getReturnType(method.desc).getOpcode(Opcodes.IRETURN)) return null;
        var body = real.subList(arguments.length, real.size() - 1);
        for (var insn : body)
            if (insn instanceof VarInsnNode || insn instanceof IincInsnNode || insn instanceof JumpInsnNode || insn instanceof TableSwitchInsnNode
                || insn instanceof LookupSwitchInsnNode || insn.getOpcode() >= Opcodes.IRETURN && insn.getOpcode() <= Opcodes.RETURN
                || insn.getOpcode() == Opcodes.ATHROW) return null;
        return List.copyOf(body);
    }

    /** The forwarded instructions of a call to a forwarding method of {@code forwarders}, or null for any other instruction. */
    static List<AbstractInsnNode> inlined(AbstractInsnNode insn, Map<String,List<AbstractInsnNode>> forwarders) {
        return insn instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKESTATIC && !forwarders.isEmpty()
            ? forwarders.get(call.owner + "." + call.name + call.desc) : null;
    }

    /** Counts the method bodies {@link #compare} would classify: abstract, native and unparsable classes are skipped. */
    public static int countBodies(Map<String,byte[]> originals) {
        int total = 0;
        for (byte[] bytes : originals.values()) {
            ClassNode node = parse(bytes);
            if (node != null) for (var method : node.methods) if (method.instructions.size() > 0) total++;
        }
        return total;
    }

    /** Returns null when the original cannot be parsed, so it contributes no evidence either way. */
    static List<MethodResult> compareClass(byte[] original, byte[] recompiled) {
        var contexts = contexts(List.of(original), recompiled == null ? List.of() : List.of(recompiled));
        return compareClass(original, recompiled, contexts[0], contexts[1]);
    }

    /**
     * As {@link #compareClass(byte[], byte[])}, with each side's {@link Context} built from the classes the compared bodies
     * may refer to, such as the outer class's accessors for a member class ({@link #contexts}).
     */
    static List<MethodResult> compareClass(byte[] original, byte[] recompiled, Context originalContext, Context recompiledContext) {
        ClassNode before = parse(original);
        if (before == null) return null;
        ClassNode after = recompiled == null ? null : parse(recompiled);
        var compiled = new HashMap<String,MethodNode>();
        if (after != null) for (var method : after.methods) compiled.put(method.name + method.desc, method);
        var privates = new HashSet<String>();
        for (var method : before.methods)
            if ((method.access & Opcodes.ACC_PRIVATE) != 0 && !method.name.startsWith("<")) privates.add(before.name + "." + method.name + method.desc);
        var sides = new Sides(privates, originalContext, recompiledContext);
        var result = new ArrayList<MethodResult>();
        for (var method : before.methods) {
            if (method.instructions.size() == 0) continue; // abstract or native: no body to compare
            MethodNode other = compiled.get(method.name + method.desc);
            String missing = after == null ? (recompiled == null ? "class missing from recompiled output" : "recompiled class unreadable")
                : other == null ? "method missing from recompiled class"
                : other.instructions.size() == 0 ? "recompiled method has no body" : null;
            var outcome = missing != null ? new MethodResult(method.name, method.desc, Outcome.DIFFERENT, missing) : compare(method, other, sides);
            if (outcome.outcome() == Outcome.DIFFERENT && after != null) outcome = renumberedAccessor(before.name, method, after, sides, outcome);
            result.add(outcome);
        }
        return List.copyOf(result);
    }

    /**
     * Phase 2BH: an original accessor {@code access$NNN} is recovered by a synthetic accessor of the recompiled class with
     * the same descriptor that forwards to the same instructions, whatever its number.
     */
    private static MethodResult renumberedAccessor(String owner, MethodNode method, ClassNode after, Sides sides, MethodResult outcome) {
        var body = forwarded(owner, method);
        if (body == null || method.name.equals("$values")) return outcome;
        var expected = tokens(body, sides.privates(), sides.original().fields());
        for (var other : after.methods) {
            if (!other.desc.equals(method.desc) || other.name.equals(method.name) || !ACCESSOR.matcher(other.name).matches()) continue;
            var candidate = forwarded(after.name, other);
            if (candidate != null && tokens(candidate, sides.privates(), sides.recompiled().fields()).equals(expected))
                return new MethodResult(method.name, method.desc, Outcome.EQUIVALENT_AFTER_NORMALIZATION, "synthetic accessor numbered differently");
        }
        return outcome;
    }

    /**
     * What normalized comparison needs besides the two bodies: the original class's private methods (whose call opcode
     * varies by javac version) and each side's {@link Context}.
     */
    record Sides(Set<String> privates, Context original, Context recompiled) {
        static Sides of(Set<String> privates) { return new Sides(privates, Context.NONE, Context.NONE); }
    }

    static MethodResult compare(MethodNode original, MethodNode recompiled) { return compare(original, recompiled, Set.of()); }
    /** {@code privates}: {@code owner.name+desc} of the original class's private methods, whose call opcode varies by javac version. */
    static MethodResult compare(MethodNode original, MethodNode recompiled, Set<String> privates) { return compare(original, recompiled, Sides.of(privates)); }

    static MethodResult compare(MethodNode original, MethodNode recompiled, Sides sides) {
        if (render(original, false).equals(render(recompiled, false)))
            return new MethodResult(original.name, original.desc, Outcome.IDENTICAL, "");
        var before = render(original, true, sides.privates(), sides.original());
        var after = render(recompiled, true, sides.privates(), sides.recompiled());
        if (before.equals(after))
            return new MethodResult(original.name, original.desc, Outcome.EQUIVALENT_AFTER_NORMALIZATION, "");
        if (ControlFlowEquivalence.equivalent(original, recompiled, sides))
            return new MethodResult(original.name, original.desc, Outcome.EQUIVALENT_AFTER_NORMALIZATION, "same control flow, different block layout");
        // Phase 2AT: the first divergence, as operand-free mnemonics, is the failure lab grouping signature.
        return new MethodResult(original.name, original.desc, Outcome.DIFFERENT, "body differs after normalization", difference(before, after));
    }

    /**
     * The normalized instructions of both sides around their first divergence, with operands, for a failure case's
     * own evidence (it can name target constants and members). Empty when either side lacks the method.
     */
    public static String differenceContext(byte[] originalClass, byte[] recompiledClass, String name, String descriptor, int window) {
        ClassNode before = parse(originalClass), after = recompiledClass == null ? null : parse(recompiledClass);
        if (before == null || after == null) return "";
        MethodNode a = null, b = null;
        for (var method : before.methods) if (method.name.equals(name) && method.desc.equals(descriptor)) a = method;
        for (var method : after.methods) if (method.name.equals(name) && method.desc.equals(descriptor)) b = method;
        if (a == null || b == null) return "";
        var left = render(a, true); var right = render(b, true);
        int index = 0;
        while (index < left.size() && index < right.size() && left.get(index).equals(right.get(index))) index++;
        int from = Math.max(0, index - window);
        return "first difference at normalized instruction " + index + "; original: " + left.subList(from, Math.min(left.size(), index + window + 1))
            + "; recompiled: " + right.subList(from, Math.min(right.size(), index + window + 1));
    }

    /** First position where the normalized renderings diverge, as operand-free mnemonics of both sides. */
    static String difference(List<String> before, List<String> after) {
        int index = 0;
        while (index < before.size() && index < after.size() && before.get(index).equals(after.get(index))) index++;
        return mnemonic(before, index) + "/" + mnemonic(after, index);
    }

    private static String mnemonic(List<String> rendered, int index) {
        if (index >= rendered.size()) return "<end>";
        String token = rendered.get(index), head = token.contains(" ") ? token.substring(0, token.indexOf(' ')) : token;
        if (head.startsWith("op")) {
            int opcode = Integer.parseInt(head.substring(2));
            return opcode >= 0 && opcode < org.objectweb.asm.util.Printer.OPCODES.length
                ? org.objectweb.asm.util.Printer.OPCODES[opcode].toLowerCase(java.util.Locale.ROOT) : head;
        }
        if (head.matches("L[0-9]+")) return "label";
        return switch (head) {
            case "int", "long", "float", "double", "string", "type" -> "const-" + head;
            case "try" -> "try-catch";
            case "indy" -> "invokedynamic";
            default -> head; // tableswitch, lookupswitch, iinc, multianewarray, opaque
        };
    }

    /** Reads {@code name.class} entries of a normalized class JAR such as the job's {@code classes.jar}. */
    public static Map<String,byte[]> readJar(Path jar) throws IOException {
        var result = new TreeMap<String,byte[]>();
        try (var zip = new ZipFile(jar.toFile())) {
            var entries = zip.entries(); long total = 0;
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                if (entry.isDirectory() || !entry.getName().endsWith(".class")) continue;
                if (result.size() >= 100_000) throw new IOException("Too many original classes");
                try (var in = zip.getInputStream(entry)) {
                    byte[] bytes = in.readNBytes(16 * 1024 * 1024 + 1);
                    total += bytes.length;
                    if (bytes.length > 16 * 1024 * 1024 || total > 512L * 1024 * 1024) throw new IOException("Original class budget exceeded");
                    result.put(entry.getName().substring(0, entry.getName().length() - ".class".length()), bytes);
                }
            }
        }
        return result;
    }

    private static ClassNode parse(byte[] bytes) {
        try {
            var node = new ClassNode();
            new ClassReader(bytes).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            return node;
        } catch (RuntimeException malformed) {
            return null;
        }
    }

    static List<String> render(MethodNode method, boolean normalize) { return render(method, normalize, Set.of()); }

    static List<String> render(MethodNode method, boolean normalize, Set<String> privates) { return render(method, normalize, privates, Context.NONE); }

    static List<String> render(MethodNode method, boolean normalize, Set<String> privates, Context context) {
        var targets = new HashSet<LabelNode>();
        for (var block : method.tryCatchBlocks) { targets.add(block.start); targets.add(block.end); targets.add(block.handler); }
        for (var insn : method.instructions) {
            if (insn instanceof JumpInsnNode jump) { if (inlinedReturn(jump, normalize) < 0) targets.add(jump.label); }
            else if (insn instanceof TableSwitchInsnNode table) { targets.add(table.dflt); targets.addAll(table.labels); }
            else if (insn instanceof LookupSwitchInsnNode lookup) { targets.add(lookup.dflt); targets.addAll(lookup.labels); }
        }
        var labels = new HashMap<LabelNode,Integer>();
        for (var insn : method.instructions) if (insn instanceof LabelNode label && targets.contains(label)) labels.put(label, labels.size());
        int parameterSlots = Type.getArgumentsAndReturnSizes(method.desc) >> 2; // includes the implicit receiver slot
        if ((method.access & Opcodes.ACC_STATIC) != 0) parameterSlots--;
        var slots = new HashMap<Integer,Integer>();
        int fixed = parameterSlots;
        var webs = normalize ? webs(method, fixed) : null;
        java.util.function.ToIntFunction<AbstractInsnNode> slot = insn -> {
            int value = webs != null ? webs.get(insn) : insn instanceof VarInsnNode local ? local.var : ((IincInsnNode) insn).var;
            return !normalize || value < fixed ? value : slots.computeIfAbsent(value, ignored -> fixed + slots.size());
        };
        var out = new ArrayList<String>();
        var rewrites = normalize ? localRewrites(method, fixed, webs) : Map.<AbstractInsnNode,List<AbstractInsnNode>>of();
        AbstractInsnNode previous = null; // the last instruction written since the last jump target, for reloadedDup
        for (var written : method.instructions) {
            for (var insn : rewrites.getOrDefault(written, List.of(written))) {
                var forwarded = normalize ? inlined(insn, context.forwarders()) : null;
                if (forwarded != null) { for (var inner : forwarded) out.add(token(inner, privates, context.fields())); previous = null; continue; }
                var reloaded = normalize ? reloadedDup(insn, previous) : null;
                if (reloaded != null) { out.add(op(reloaded) + " " + slot.applyAsInt(reloaded)); continue; }
                if (insn.getOpcode() >= 0) previous = insn;
                switch (insn) {
                    case LabelNode label -> { if (labels.containsKey(label)) { out.add("L" + labels.get(label)); previous = null; } }
                    case LineNumberNode ignored -> {}
                    case FrameNode ignored -> {}
                    case InsnNode plain -> out.add(normalize ? constant(plain.getOpcode(), null) : op(plain));
                    case IntInsnNode value -> out.add(normalize && value.getOpcode() != Opcodes.NEWARRAY
                        ? "int " + value.operand : op(value) + " " + value.operand);
                    case LdcInsnNode ldc -> out.add(normalize ? constant(Opcodes.LDC, ldc.cst) : "ldc " + typed(ldc.cst));
                    case VarInsnNode local -> out.add(op(local) + " " + slot.applyAsInt(local));
                    case IincInsnNode inc -> out.add("iinc " + slot.applyAsInt(inc) + " " + inc.incr);
                    case TypeInsnNode type -> out.add(op(type) + " " + type.desc);
                    case FieldInsnNode field -> out.add(field(field, normalize ? context.fields() : Map.of()));
                    case MethodInsnNode call -> out.add(call(call, normalize, privates));
                    case InvokeDynamicInsnNode indy -> out.add("indy " + indy.name + indy.desc + " " + indy.bsm + " " + Arrays.stream(indy.bsmArgs).map(MethodRoundTrip::typed).toList());
                    case JumpInsnNode jump -> {
                        int inlined = inlinedReturn(jump, normalize);
                        out.add(inlined >= 0 ? "op" + inlined : op(jump) + " L" + labels.get(jump.label));
                    }
                    case TableSwitchInsnNode table -> out.add("tableswitch " + table.min + ".." + table.max + " L" + labels.get(table.dflt)
                        + " " + table.labels.stream().map(label -> "L" + labels.get(label)).toList());
                    case LookupSwitchInsnNode lookup -> out.add("lookupswitch " + lookup.keys + " L" + labels.get(lookup.dflt)
                        + " " + lookup.labels.stream().map(label -> "L" + labels.get(label)).toList());
                    case MultiANewArrayInsnNode array -> out.add("multianewarray " + array.desc + " " + array.dims);
                    default -> out.add("opaque " + insn.getType() + " " + insn.getOpcode());
                }
            }
        }
        for (var block : method.tryCatchBlocks)
            out.add("try L" + labels.get(block.start) + " L" + labels.get(block.end) + " L" + labels.get(block.handler) + " " + block.type);
        return out;
    }

    /**
     * Normalized only: a {@code GOTO} whose target is directly a return instruction returns the same value from the
     * same frame, so it is read as that return. This equates {@code return c ? a : b;} (one return reached by a jump)
     * with {@code if (c) return a; return b;}. Returns the return opcode, or -1 when the jump is kept as written.
     */
    private static int inlinedReturn(JumpInsnNode jump, boolean normalize) {
        if (!normalize || jump.getOpcode() != Opcodes.GOTO) return -1;
        AbstractInsnNode next = jump.label.getNext();
        while (next instanceof LabelNode || next instanceof LineNumberNode || next instanceof FrameNode) next = next.getNext();
        return next != null && next.getOpcode() >= Opcodes.IRETURN && next.getOpcode() <= Opcodes.RETURN ? next.getOpcode() : -1;
    }

    /**
     * Normalized only: locals that only hold a value for the instructions right after them, each mapped to what it is
     * read as (no instruction: skipped). Any label in between, or a parameter's own value, keeps them as written.
     * <ul>
     * <li>Phase 2BB: a store immediately followed by a load of the same local, where the local is used nowhere else,
     * only passes a value through ({@code T t = expr; return t;} against {@code return expr;}); both are skipped.</li>
     * <li>Phase 2BH, with {@link #webs}: a store immediately followed by several loads of its variable, which has no
     * other store or use, is the value duplicated ({@code T t = expr; t.a(t)} against {@code expr} and a {@code dup}):
     * the store and first load are skipped and every further load is a {@code dup} ({@code dup2} for two-slot values).</li>
     * <li>Phase 2BH: a store whose variable is never loaded is dead: it is read as {@code pop} ({@code pop2}), and when
     * it stores a constant or a load pushed right before it, both are skipped ({@code int x = 0;} that is always
     * overwritten before it is read, or an unused local).</li>
     * </ul>
     * Without webs (a very large method), only the Phase 2BB rule applies, counting uses per slot.
     */
    static Map<AbstractInsnNode,List<AbstractInsnNode>> localRewrites(MethodNode method, int fixed, Map<AbstractInsnNode,Integer> webs) {
        var result = new IdentityHashMap<AbstractInsnNode,List<AbstractInsnNode>>();
        var defs = new HashMap<Integer,Integer>(); var uses = new HashMap<Integer,Integer>();
        for (var insn : method.instructions) {
            if (!(insn instanceof VarInsnNode) && !(insn instanceof IincInsnNode)) continue;
            int variable = webs != null ? webs.get(insn) : var(insn);
            boolean store = insn instanceof VarInsnNode local && local.getOpcode() >= Opcodes.ISTORE;
            if (store || insn instanceof IincInsnNode) defs.merge(variable, 1, Integer::sum);
            if (!store) uses.merge(variable, 1, Integer::sum);
        }
        for (var insn : method.instructions) {
            if (!(insn instanceof VarInsnNode store) || store.getOpcode() < Opcodes.ISTORE || store.getOpcode() > Opcodes.ASTORE) continue;
            int variable = webs != null ? webs.get(store) : store.var;
            int load = store.getOpcode() - (Opcodes.ISTORE - Opcodes.ILOAD), count = uses.getOrDefault(variable, 0);
            if (variable < fixed || count > 0 && defs.get(variable) != 1) continue;
            boolean wide = store.getOpcode() == Opcodes.LSTORE || store.getOpcode() == Opcodes.DSTORE;
            if (count == 0 && webs != null) {
                var pushed = adjacent(store.getPrevious(), false);
                boolean pure = pushed != null && !result.containsKey(pushed) && (pushed instanceof VarInsnNode local && local.getOpcode() < Opcodes.ISTORE
                    || pushed.getOpcode() >= Opcodes.ACONST_NULL && pushed.getOpcode() <= Opcodes.SIPUSH
                    || pushed instanceof LdcInsnNode ldc && (ldc.cst instanceof Number || ldc.cst instanceof String));
                boolean size = pushed != null && (pushed.getOpcode() == Opcodes.LCONST_0 || pushed.getOpcode() == Opcodes.LCONST_1 || pushed.getOpcode() == Opcodes.DCONST_0
                    || pushed.getOpcode() == Opcodes.DCONST_1 || pushed.getOpcode() == Opcodes.LLOAD || pushed.getOpcode() == Opcodes.DLOAD
                    || pushed instanceof LdcInsnNode ldc && (ldc.cst instanceof Long || ldc.cst instanceof Double)) == wide;
                if (pure && size) { result.put(pushed, List.of()); result.put(store, List.of()); }
                else result.put(store, List.of(new InsnNode(wide ? Opcodes.POP2 : Opcodes.POP)));
                continue;
            }
            if (count == 0 || count > 1 && webs == null) continue;
            var loads = new ArrayList<AbstractInsnNode>();
            for (var next = adjacent(store.getNext(), true); next instanceof VarInsnNode local && local.getOpcode() == load && local.var == store.var
                     && loads.size() < count; next = adjacent(next.getNext(), true)) loads.add(next);
            if (loads.size() != count) continue;
            result.put(store, List.of());
            for (int i = 0; i < loads.size(); i++) result.put(loads.get(i), i == 0 ? List.of() : List.of(new InsnNode(wide ? Opcodes.DUP2 : Opcodes.DUP)));
        }
        return result;
    }

    /** The first real instruction from {@code insn} on (forward or backward), skipping line numbers and frames; null at a label. */
    private static AbstractInsnNode adjacent(AbstractInsnNode insn, boolean forward) {
        while (insn instanceof LineNumberNode || insn instanceof FrameNode) insn = forward ? insn.getNext() : insn.getPrevious();
        return insn instanceof LabelNode ? null : insn;
    }

    /** Definitions times instructions above which {@link #webs} gives up and locals are compared by slot. */
    private static final long MAX_WEB_STEPS = 20_000_000L;

    /**
     * Normalized only (Phase 2BH): each load, store and {@code iinc} mapped to the variable it belongs to, so that javac
     * reusing one slot for two variables of disjoint scopes (or giving each its own slot) does not matter. A variable is
     * a web: the stores of a slot joined with every load (or {@code iinc}) they reach, and with the other stores reaching
     * those same loads; parameters are reached by an entry store. Reaching is computed over every instruction's
     * successors, and an instruction inside a handler's range also reaches the handler with what reached it (more than
     * may really reach it, which only joins webs). A web holding a parameter's entry value is that parameter's slot;
     * any other web is {@code fixed} plus a number, renumbered later like any local. Returns null for {@code jsr} or
     * {@code ret}, or when the method is too large, and locals are then compared by slot.
     */
    static Map<AbstractInsnNode,Integer> webs(MethodNode method, int fixed) {
        var insns = method.instructions.toArray(); int n = insns.length;
        var index = new IdentityHashMap<AbstractInsnNode,Integer>();
        for (int i = 0; i < n; i++) index.put(insns[i], i);
        var defs = new ArrayList<Integer>();
        for (int i = 0; i < n; i++) {
            if (insns[i].getOpcode() == Opcodes.JSR || insns[i].getOpcode() == Opcodes.RET) return null;
            if (insns[i] instanceof IincInsnNode || insns[i] instanceof VarInsnNode store && store.getOpcode() >= Opcodes.ISTORE) defs.add(i);
        }
        if ((long) (defs.size() + fixed) * Math.max(1, n) > MAX_WEB_STEPS) return null;
        var handlers = new ArrayList<List<Integer>>();
        for (int i = 0; i < n; i++) handlers.add(new ArrayList<>());
        for (var block : method.tryCatchBlocks)
            for (int i = index.get(block.start); i < index.get(block.end); i++) handlers.get(i).add(index.get(block.handler));
        int[] parent = new int[n + fixed];
        for (int i = 0; i < parent.length; i++) parent[i] = i;
        // Node n + p is parameter slot p's entry store; every other node is an instruction.
        for (int d = -fixed; d < defs.size(); d++) {
            int from = d < 0 ? n + d + fixed : defs.get(d), slot = d < 0 ? d + fixed : var(insns[from]);
            var visited = new BitSet(n); var work = new ArrayDeque<Integer>();
            if (d < 0) { if (n > 0) work.push(0); } else for (int next : successors(insns, from, index)) work.push(next);
            while (!work.isEmpty()) {
                int i = work.pop();
                if (visited.get(i)) continue;
                visited.set(i);
                var insn = insns[i];
                boolean uses = insn instanceof IincInsnNode inc && inc.var == slot
                    || insn instanceof VarInsnNode load && load.getOpcode() < Opcodes.ISTORE && load.var == slot;
                if (uses) union(parent, from, i);
                for (int handler : handlers.get(i)) work.push(handler);
                // A store to the slot (or a two-slot store into the slot below) ends what reaches on.
                boolean kills = insn instanceof IincInsnNode inc && inc.var == slot || insn instanceof VarInsnNode store && store.getOpcode() >= Opcodes.ISTORE
                    && (store.var == slot || store.var == slot - 1 && (store.getOpcode() == Opcodes.LSTORE || store.getOpcode() == Opcodes.DSTORE));
                if (!kills) for (int next : successors(insns, i, index)) work.push(next);
            }
        }
        var entries = new HashMap<Integer,Integer>();
        for (int p = 0; p < fixed; p++) entries.put(find(parent, n + p), p);
        var result = new IdentityHashMap<AbstractInsnNode,Integer>();
        for (int i = 0; i < n; i++) {
            if (!(insns[i] instanceof VarInsnNode) && !(insns[i] instanceof IincInsnNode)) continue;
            int root = find(parent, i);
            result.put(insns[i], entries.getOrDefault(root, fixed + root));
        }
        return result;
    }

    private static int var(AbstractInsnNode insn) { return insn instanceof VarInsnNode local ? local.var : ((IincInsnNode) insn).var; }

    /** Normal successors: the next instruction unless control never falls through, and every jump or switch target. */
    private static List<Integer> successors(AbstractInsnNode[] insns, int i, Map<AbstractInsnNode,Integer> index) {
        var insn = insns[i]; int opcode = insn.getOpcode();
        var result = new ArrayList<Integer>(2);
        if (insn instanceof JumpInsnNode jump) result.add(index.get(jump.label));
        else if (insn instanceof TableSwitchInsnNode table) { result.add(index.get(table.dflt)); for (var label : table.labels) result.add(index.get(label)); }
        else if (insn instanceof LookupSwitchInsnNode lookup) { result.add(index.get(lookup.dflt)); for (var label : lookup.labels) result.add(index.get(label)); }
        boolean ends = opcode == Opcodes.GOTO || opcode >= Opcodes.IRETURN && opcode <= Opcodes.RETURN || opcode == Opcodes.ATHROW
            || insn instanceof TableSwitchInsnNode || insn instanceof LookupSwitchInsnNode;
        if (!ends && i + 1 < insns.length) result.add(i + 1);
        return result;
    }

    private static int find(int[] parent, int x) {
        while (parent[x] != x) { parent[x] = parent[parent[x]]; x = parent[x]; }
        return x;
    }

    private static void union(int[] parent, int a, int b) {
        int x = find(parent, a), y = find(parent, b);
        if (x != y) parent[Math.max(x, y)] = Math.min(x, y);
    }

    /**
     * Normalized only (Phase 2BH): a {@code dup} right after a load of a one-slot local (or {@code dup2} after a two-slot
     * one), with no jump target in between, pushes the same value as loading that local again. javac writes
     * {@code this.f += x} as {@code aload_0; dup; getfield} and {@code this.f = this.f + x} as
     * {@code aload_0; aload_0; getfield}. Returns the load to read the duplicate as, or null.
     */
    static VarInsnNode reloadedDup(AbstractInsnNode insn, AbstractInsnNode previous) {
        if (!(previous instanceof VarInsnNode load)) return null;
        int opcode = load.getOpcode();
        boolean single = opcode == Opcodes.ILOAD || opcode == Opcodes.FLOAD || opcode == Opcodes.ALOAD;
        boolean wide = opcode == Opcodes.LLOAD || opcode == Opcodes.DLOAD;
        return insn.getOpcode() == Opcodes.DUP && single || insn.getOpcode() == Opcodes.DUP2 && wide ? load : null;
    }

    /** The normalized spelling of an instruction that is not a label, local, jump or switch; null for those. */
    static String token(AbstractInsnNode insn, Set<String> privates) { return token(insn, privates, Map.of()); }

    /** As {@link #token(AbstractInsnNode, Set)}, with one side's renamed captured fields. */
    static String token(AbstractInsnNode insn, Set<String> privates, Map<String,String> fields) {
        return switch (insn) {
            case InsnNode plain -> constant(plain.getOpcode(), null);
            case IntInsnNode value -> value.getOpcode() == Opcodes.NEWARRAY ? op(value) + " " + value.operand : "int " + value.operand;
            case LdcInsnNode ldc -> constant(Opcodes.LDC, ldc.cst);
            case TypeInsnNode type -> op(type) + " " + type.desc;
            case FieldInsnNode field -> field(field, fields);
            case MethodInsnNode call -> call(call, true, privates);
            case InvokeDynamicInsnNode indy -> "indy " + indy.name + indy.desc + " " + indy.bsm + " " + Arrays.stream(indy.bsmArgs).map(MethodRoundTrip::typed).toList();
            case MultiANewArrayInsnNode array -> "multianewarray " + array.desc + " " + array.dims;
            default -> null;
        };
    }

    static List<String> tokens(List<AbstractInsnNode> instructions, Set<String> privates, Map<String,String> fields) {
        var result = new ArrayList<String>();
        for (var insn : instructions) result.add(token(insn, privates, fields));
        return result;
    }

    static String op(AbstractInsnNode insn) { return "op" + insn.getOpcode(); }

    /** Public instance methods of {@code Object}, which every interface implicitly declares (JLS 9.2). */
    private static final Set<String> OBJECT_PUBLIC = Set.of("toString()Ljava/lang/String;", "equals(Ljava/lang/Object;)Z",
        "hashCode()I", "getClass()Ljava/lang/Class;", "notify()V", "notifyAll()V", "wait()V", "wait(J)V", "wait(JI)V");

    /**
     * A call as compared. Normalized only: a public {@code Object} method called on an interface-typed receiver is
     * spelled as {@code invokevirtual java/lang/Object}, which is what older javac versions emitted; newer javac
     * emits {@code invokeinterface I.m}. Both resolve to the same {@code Object} method (JVMS 5.4.3.4).
     */
    static String call(MethodInsnNode call, boolean normalize) { return call(call, normalize, Set.of()); }

    /**
     * Also normalized (Phase 2BA): a call to a private method of the compared class is spelled the same whether javac
     * used {@code invokespecial} (before Java 11) or {@code invokevirtual}/{@code invokeinterface} (nestmates, JEP 181).
     * A private method cannot be overridden, so every form invokes the same method.
     */
    static String call(MethodInsnNode call, boolean normalize, Set<String> privates) {
        if (normalize && privates.contains(call.owner + "." + call.name + call.desc) && (call.getOpcode() == Opcodes.INVOKEVIRTUAL
            || call.getOpcode() == Opcodes.INVOKESPECIAL || call.getOpcode() == Opcodes.INVOKEINTERFACE))
            return "invoke-private " + call.owner + "." + call.name + call.desc;
        if (normalize && call.getOpcode() == Opcodes.INVOKEINTERFACE && OBJECT_PUBLIC.contains(call.name + call.desc))
            return "op" + Opcodes.INVOKEVIRTUAL + " java/lang/Object." + call.name + call.desc;
        return op(call) + " " + call.owner + "." + call.name + call.desc + (call.itf ? " itf" : "");
    }

    /** One spelling per constant value, whichever instruction pushed it; non-constant opcodes stay as written. */
    static String constant(int opcode, Object ldc) {
        return switch (opcode) {
            case Opcodes.ICONST_M1, Opcodes.ICONST_0, Opcodes.ICONST_1, Opcodes.ICONST_2, Opcodes.ICONST_3, Opcodes.ICONST_4,
                 Opcodes.ICONST_5 -> "int " + (opcode - Opcodes.ICONST_0);
            case Opcodes.LCONST_0, Opcodes.LCONST_1 -> "long " + (opcode - Opcodes.LCONST_0);
            case Opcodes.FCONST_0, Opcodes.FCONST_1, Opcodes.FCONST_2 -> typed((float) (opcode - Opcodes.FCONST_0));
            case Opcodes.DCONST_0, Opcodes.DCONST_1 -> typed((double) (opcode - Opcodes.DCONST_0));
            case Opcodes.LDC -> typed(ldc);
            default -> "op" + opcode;
        };
    }

    /** Type-tagged constant; floating values use raw bits so 0.0 and -0.0 or distinct NaNs never collapse. */
    static String typed(Object value) {
        return switch (value) {
            case Integer i -> "int " + i;
            case Long l -> "long " + l;
            case Float f -> "float " + Integer.toHexString(Float.floatToRawIntBits(f));
            case Double d -> "double " + Long.toHexString(Double.doubleToRawLongBits(d));
            case String s -> "string " + s.length() + ":" + s;
            case Type t -> "type " + t.getDescriptor();
            default -> value.getClass().getSimpleName() + " " + value;
        };
    }
}
