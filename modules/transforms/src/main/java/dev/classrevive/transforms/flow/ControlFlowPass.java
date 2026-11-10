package dev.classrevive.transforms.flow;

import dev.classrevive.transforms.TransformResult;
import dev.classrevive.transforms.skid.Hierarchy;
import dev.classrevive.transforms.skid.PureEvaluator;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.BasicVerifier;
import org.objectweb.asm.tree.analysis.Frame;
import java.util.*;

/**
 * Phase 2DL: a general control-flow simplification over a whole jar, independent of any obfuscator, that changes only
 * what it proves statically ({@link FlowAnalyzer}; target code is never loaded or run):
 *
 * <ul>
 * <li><b>opaque predicates</b>: a branch or switch whose outcome the constant, interval and parity analysis decides
 * becomes a jump (or falls through), and switch cases no input reaches are dropped; inputs include static final fields
 * {@link JarFacts} proves constant and pure static calls on constants;</li>
 * <li><b>flattened switches</b>: an edge into a dispatcher (side-effect-free code ending in a switch, such as
 * {@code iload state; tableswitch}) on which the state is one constant goes straight to that case; an edge with an
 * undecided state is left as it is (and counted as ambiguous);</li>
 * <li><b>flow by exception</b>: {@code throw new X()} whose first matching handler in the same method discards the
 * exception becomes a jump past that discard, when constructing {@code X} has no side effect;</li>
 * <li><b>dead code</b>: unreachable instructions and the handlers nothing can reach.</li>
 * </ul>
 *
 * Only a method in which one of these finds something counts as obfuscated; only then are the cosmetic clean-ups applied
 * (jump chains, jumps to the next instruction, a jump to a lone return, pushes that are popped, stores nothing reads, try
 * ranges where nothing can throw, handlers that only rethrow). A method javac wrote is therefore left byte-for-byte as it
 * was. A changed method must pass ASM's {@link BasicVerifier} or it is restored; a method the analysis refuses is left
 * untouched and listed in the report.
 */
public final class ControlFlowPass implements Opcodes {
    public static final String ID = "control-flow";
    static final int MAX_ROUNDS = 48;

    /** Report totals in a fixed order, refused methods (up to 50, with the reason) and classes kept because writing failed. */
    public record Report(Map<String,Integer> counts, List<String> refused, List<String> writeFailures) {
        public Report { counts = Collections.unmodifiableMap(new LinkedHashMap<>(counts)); refused = List.copyOf(refused); writeFailures = List.copyOf(writeFailures); }
        public int count(String name) { return counts.getOrDefault(name, 0); }
        @Override public String toString() {
            var out = new StringBuilder("Control-flow simplification (Phase 2DL):");
            counts.forEach((name, value) -> out.append(System.lineSeparator()).append("  ").append(name).append(": ").append(value));
            if (!refused.isEmpty()) out.append(System.lineSeparator()).append("  methods refused (left unchanged): ").append(refused);
            if (!writeFailures.isEmpty()) out.append(System.lineSeparator()).append("  classes kept as they were (write failed): ").append(writeFailures);
            out.append(System.lineSeparator()).append("  assumptions: no code outside the bytecode writes a static final field; VirtualMachineErrors may occur anywhere");
            return out.toString();
        }
    }
    public record Result(Map<String,byte[]> entries, Report report, List<TransformResult.Change> changes) {}

    /** Per-method counts. */
    static final class Stats {
        int branches, switches, cases, threaded, ambiguous, throwGotos, dead, rethrows, idle, chains, nexts, returns, pushes, stores;
        int evidence() { return branches + switches + cases + threaded + throwGotos + dead; }
        int total() { return evidence() + rethrows + idle + chains + nexts + returns + pushes + stores; }
        void add(Stats o) {
            branches += o.branches; switches += o.switches; cases += o.cases; threaded += o.threaded; ambiguous += o.ambiguous;
            throwGotos += o.throwGotos; dead += o.dead; rethrows += o.rethrows; idle += o.idle; chains += o.chains; nexts += o.nexts;
            returns += o.returns; pushes += o.pushes; stores += o.stores;
        }
    }

    private Map<String,ClassNode> nodes;
    private JarFacts facts;
    private FlowAnalyzer analyzer;
    private String currentOwner;
    private final Map<String,Optional<String>> jdkSupers = new HashMap<>();

    /** {@code entries}: jar entry name to bytes; only {@code .class} entries are read, others are returned as they are. */
    public Result apply(Map<String,byte[]> entries, int maxChanges) {
        nodes = new LinkedHashMap<>();
        var entryOf = new HashMap<String,String>();
        for (var entry : entries.entrySet()) {
            if (!entry.getKey().endsWith(".class")) continue;
            try {
                var node = new ClassNode();
                new ClassReader(entry.getValue()).accept(node, ClassReader.SKIP_FRAMES);
                if (nodes.putIfAbsent(node.name, node) == null) entryOf.put(entry.getKey(), node.name);
            } catch (RuntimeException unreadable) { /* left as it is */ }
        }
        facts = JarFacts.of(nodes.values());
        analyzer = new FlowAnalyzer(new FlowInterpreter(facts, new PureEvaluator(nodes)));
        var total = new Stats();
        var refused = new ArrayList<String>();
        var changes = new ArrayList<TransformResult.Change>();
        var changed = new HashSet<String>();
        int analyzed = 0, methodsChanged = 0, refusals = 0, restored = 0, skippedClasses = 0;
        boolean budget = false;
        for (var node : nodes.values()) {
            if (dev.classrevive.transforms.IntegerConstantPass.hasOpaqueAttributes(node)) { skippedClasses++; continue; }
            currentOwner = node.name;
            for (int m = 0; m < node.methods.size(); m++) {
                var method = node.methods.get(m);
                if (method.instructions.size() == 0) continue;
                if (changes.size() >= maxChanges) { budget = true; break; }
                analyzed++;
                String where = node.name + "." + method.name + method.desc;
                String unsupported = unsupported(method);
                if (unsupported != null) { refusals++; if (refused.size() < 50) refused.add(where + ": " + unsupported); continue; }
                var backup = copy(method);
                var stats = new Stats();
                String failure;
                try { failure = simplify(method, stats); }
                catch (RuntimeException error) { failure = "rewrite failed: " + error; }
                if (failure == null && stats.total() > 0) {
                    try { new Analyzer<>(new BasicVerifier()).analyze(node.name, method); }
                    catch (AnalyzerException | RuntimeException invalid) { failure = "rewritten code failed ASM's BasicVerifier (" + invalid.getMessage() + ")"; restored++; }
                }
                if (failure != null) {
                    node.methods.set(m, backup);
                    refusals++; if (refused.size() < 50) refused.add(where + ": " + failure);
                    continue;
                }
                if (stats.total() == 0) continue;
                total.add(stats); methodsChanged++; changed.add(node.name);
                changes.add(new TransformResult.Change(ID, node.name, method.name, method.desc, -1, 0,
                    stats.branches + stats.switches + stats.cases, stats.threaded + stats.throwGotos, stats.total()));
            }
        }
        // Write the changed classes; a class that cannot be written keeps its original bytes.
        var output = new LinkedHashMap<String,byte[]>();
        var hierarchy = new Hierarchy(nodes);
        var writeFailed = new ArrayList<String>(); int writeFailures = 0;
        for (var entry : entries.entrySet()) {
            String internal = entryOf.get(entry.getKey());
            if (internal == null || !changed.contains(internal)) { output.put(entry.getKey(), entry.getValue()); continue; }
            var node = nodes.get(internal);
            try {
                var writer = hierarchy.writer((node.version & 0xFFFF) >= V1_6 ? ClassWriter.COMPUTE_FRAMES : ClassWriter.COMPUTE_MAXS);
                node.accept(writer);
                output.put(entry.getKey(), writer.toByteArray());
            } catch (RuntimeException error) {
                writeFailures++; if (writeFailed.size() < 20) writeFailed.add(Hierarchy.failure(internal, error));
                output.put(entry.getKey(), entry.getValue());
                changes.removeIf(change -> change.className().equals(internal));
            }
        }
        var counts = new LinkedHashMap<String,Integer>();
        counts.put("classes", nodes.size()); counts.put("classes skipped (unknown attributes)", skippedClasses);
        counts.put("methods analyzed", analyzed); counts.put("methods changed", methodsChanged);
        counts.put("static final constants proved", facts.size());
        counts.put("opaque branches decided", total.branches); counts.put("switches decided", total.switches);
        counts.put("switches pruned", total.cases);
        counts.put("dispatcher edges threaded", total.threaded); counts.put("dispatcher edges left (state not constant)", total.ambiguous);
        counts.put("throw-gotos turned into jumps", total.throwGotos); counts.put("dead instructions removed", total.dead);
        counts.put("rethrow-only handlers removed", total.rethrows); counts.put("idle try ranges removed", total.idle);
        counts.put("jump chains shortened", total.chains); counts.put("jumps to the next instruction removed", total.nexts);
        counts.put("jumps to a lone return inlined", total.returns); counts.put("dead pushes removed", total.pushes);
        counts.put("dead stores removed", total.stores);
        counts.put("methods refused", refusals); counts.put("methods restored by the verifier check", restored);
        counts.put("change budget reached", budget ? 1 : 0); counts.put("class write failures", writeFailures);
        return new Result(output, new Report(counts, refused, writeFailed), changes);
    }

    /** Why a method is not analyzed at all, or null. */
    private static String unsupported(MethodNode method) {
        if (method.attrs != null && !method.attrs.isEmpty()) return "unknown method attribute";
        if (nonEmpty(method.visibleLocalVariableAnnotations) || nonEmpty(method.invisibleLocalVariableAnnotations)) return "local variable type annotations";
        for (var block : method.tryCatchBlocks) if (nonEmpty(block.visibleTypeAnnotations) || nonEmpty(block.invisibleTypeAnnotations)) return "try-catch type annotations";
        for (var insn : method.instructions) {
            if (nonEmpty(insn.visibleTypeAnnotations) || nonEmpty(insn.invisibleTypeAnnotations)) return "instruction type annotations";
            if (insn.getOpcode() == JSR || insn.getOpcode() == RET) return "jsr/ret subroutines";
        }
        return null;
    }
    private static boolean nonEmpty(List<?> list) { return list != null && !list.isEmpty(); }

    static MethodNode copy(MethodNode method) {
        var copy = new MethodNode(ASM9, method.access, method.name, method.desc, method.signature,
            method.exceptions == null ? null : method.exceptions.toArray(String[]::new));
        method.accept(copy);
        return copy;
    }

    // ===================================================================================================== rounds

    /** Rewrites one method in place; returns why it was refused (the caller restores it), or null. */
    String simplify(MethodNode method, Stats stats) {
        boolean evidence = false;
        for (int round = 0; round < MAX_ROUNDS; round++) {
            var result = analyzer.analyze(currentOwner, method);
            if (result == null) return round == 0 ? "not analyzable: " + analyzer.lastFailure : "not analyzable after rewriting: " + analyzer.lastFailure;
            boolean step = decide(method, result, stats, evidence);
            if (!step) step = thread(method, result, stats, evidence);
            if (!step) step = throwGotos(method, result, stats);
            if (step) { evidence = true; continue; }
            if (evidence && cleanup(method, stats)) continue;
            break;
        }
        dropOrphanLineNumbers(method);
        return null;
    }

    /** Line numbers with no instruction after them (the code they named was removed): the JVM rejects a pc past the code. */
    private static void dropOrphanLineNumbers(MethodNode method) {
        for (var insn : method.instructions.toArray())
            if (insn instanceof LineNumberNode line && Cfg.start(line.start) == null) method.instructions.remove(insn);
    }

    // ===================================================================================================== decisions

    /**
     * Decided branches and switches, pruned switch cases and unreachable code. Applied when it holds evidence (a decided
     * test, or code no path at all reaches, which javac never emits) or the method already had some.
     */
    private boolean decide(MethodNode method, FlowAnalyzer.Result result, Stats stats, boolean evidenceSoFar) {
        var insns = result.insns(); var frames = result.frames();
        var replace = new IdentityHashMap<AbstractInsnNode,InsnList>();
        int branches = 0, switches = 0, cases = 0, strong = 0;
        for (int i = 0; i < insns.length; i++) {
            var insn = insns[i];
            if (frames[i] == null) continue;
            int op = insn.getOpcode();
            if (insn instanceof JumpInsnNode jump && op != GOTO) {
                var targets = result.feasible().get(insn);
                int labelIndex = result.index().get(jump.label);
                if (targets == null || targets.size() != 1 || labelIndex == i + 1) continue;
                int target = targets.iterator().next();
                var list = new InsnList();
                boolean single = op >= IFEQ && op <= IFLE || op == IFNULL || op == IFNONNULL;
                list.add(new InsnNode(POP)); if (!single) list.add(new InsnNode(POP));
                if (target != i + 1) list.add(new JumpInsnNode(GOTO, (LabelNode) insns[target]));
                replace.put(insn, list); branches++;
                if (strong(op, frames[i])) strong++;
            } else if (insn instanceof TableSwitchInsnNode || insn instanceof LookupSwitchInsnNode) {
                var targets = result.feasible().get(insn);
                if (targets == null || targets.isEmpty()) continue;
                var key = frames[i].getStack(frames[i].getStackSize() - 1);
                int before = replace.size();
                if (targets.size() == 1) {
                    var list = new InsnList();
                    list.add(new InsnNode(POP)); list.add(new JumpInsnNode(GOTO, (LabelNode) insns[targets.iterator().next()]));
                    replace.put(insn, list); switches++;
                } else {
                    var pruned = Cfg.prune(insn, targets, result.index());
                    if (pruned != null) { var list = new InsnList(); list.add(pruned); replace.put(insn, list); cases++; }
                }
                if (replace.size() > before && key.folded()) strong++;
            }
        }
        var dead = new ArrayList<AbstractInsnNode>();
        for (int i = 0; i < insns.length; i++) if (frames[i] == null && insns[i].getOpcode() >= 0) dead.add(insns[i]);
        int junk = 0;
        if (!dead.isEmpty()) {
            var structural = structurallyReachable(method, result.index());
            for (var insn : dead) if (!structural.contains(insn)) junk++;
        }
        // Evidence: a test javac would never leave (on a folded or opaque value, or decided only by value numbers or parity),
        // or code no path reaches. Tests decided from intervals and nullness alone (a resource javac null-checks right after
        // creating it, a switch on b & 0xFF with cases above 255) are applied only in a method that has evidence.
        boolean evidence = strong + junk > 0;
        if (!evidence && !evidenceSoFar) return false;
        if (branches + switches + cases + dead.size() == 0) return false;
        for (var insn : dead) method.instructions.remove(insn);
        for (var entry : replace.entrySet()) { method.instructions.insertBefore(entry.getKey(), entry.getValue()); method.instructions.remove(entry.getKey()); }
        // Handlers whose code is dead are reached by nothing; empty ranges go too.
        var idleHandlers = new ArrayList<TryCatchBlockNode>();
        for (var block : method.tryCatchBlocks) if (frames[result.index().get(block.handler)] == null) idleHandlers.add(block);
        method.tryCatchBlocks.removeAll(idleHandlers);
        stats.idle += idleHandlers.size();
        removeEmptyRanges(method);
        stats.branches += branches; stats.switches += switches; stats.cases += cases; stats.dead += dead.size();
        return true;
    }

    /** Whether a decided conditional jump is evidence: an operand javac would have folded, or a decision intervals alone do not make. */
    private static boolean strong(int op, Frame<FV> frame) {
        int size = frame.getStackSize();
        if (op == IFNULL || op == IFNONNULL) return frame.getStack(size - 1).folded();
        if (op == IF_ACMPEQ || op == IF_ACMPNE) return frame.getStack(size - 1).folded() || frame.getStack(size - 2).folded();
        FV left, right;
        if (op <= IFLE) { left = frame.getStack(size - 1); right = FV.constant(0); }
        else { left = frame.getStack(size - 2); right = frame.getStack(size - 1); }
        if (left.opaque || right.opaque || left.literal && (op <= IFLE || right.literal)) return true;
        int cmp = op <= IFLE ? op : op - IF_ICMPEQ + IFEQ;
        return FlowInterpreter.compare(cmp, left, right, true) == null;
    }

    /** Instructions reachable along every edge the JVM verifier considers (every covered instruction reaches its handlers). */
    private static Set<AbstractInsnNode> structurallyReachable(MethodNode method, Map<AbstractInsnNode,Integer> index) {
        var seen = Collections.newSetFromMap(new IdentityHashMap<AbstractInsnNode,Boolean>());
        var work = new ArrayDeque<AbstractInsnNode>();
        work.add(method.instructions.getFirst());
        while (!work.isEmpty()) {
            var insn = work.poll();
            if (insn == null || !seen.add(insn)) continue;
            int at = index.get(insn);
            for (var block : method.tryCatchBlocks)
                if (at >= index.get(block.start) && at < index.get(block.end)) work.add(block.handler);
            int op = insn.getOpcode();
            if (insn instanceof JumpInsnNode jump) { work.add(jump.label); if (op != GOTO) work.add(insn.getNext()); }
            else if (insn instanceof TableSwitchInsnNode table) { work.add(table.dflt); work.addAll(table.labels); }
            else if (insn instanceof LookupSwitchInsnNode lookup) { work.add(lookup.dflt); work.addAll(lookup.labels); }
            else if (!(op >= IRETURN && op <= RETURN) && op != ATHROW) work.add(insn.getNext());
        }
        return seen;
    }

    private static void removeEmptyRanges(MethodNode method) {
        method.tryCatchBlocks.removeIf(block -> {
            for (var insn = block.start.getNext(); insn != null && insn != block.end; insn = insn.getNext()) if (insn.getOpcode() >= 0) return false;
            return true;
        });
    }

    // ===================================================================================================== flattening

    /**
     * Edges into a dispatcher on which the switch key is one constant go straight to the case. A dispatcher is code from a
     * label up to a switch that only loads, pushes constants, computes without throwing and moves the stack, leaving the
     * stack as it found it; so skipping it changes nothing but the path.
     */
    private boolean thread(MethodNode method, FlowAnalyzer.Result result, Stats stats, boolean evidenceSoFar) {
        var insns = result.insns(); var frames = result.frames();
        int threaded = 0, ambiguous = 0, strong = 0;
        var strongKeys = new boolean[1];
        record Retarget(AbstractInsnNode site, LabelNode from, LabelNode to, boolean fallthrough) {}
        var plan = new ArrayList<Retarget>();
        for (int i = 0; i < insns.length; i++) {
            if (!(insns[i] instanceof LabelNode label) || frames[i] == null || !dispatcher(label)) continue;
            // Every edge into the label: jumps to it, and the fall-through from the instruction before it.
            var incoming = new ArrayList<Object[]>(); // {site, frame on the edge, fallthrough?}
            for (int j = 0; j < insns.length; j++) {
                if (frames[j] == null || !(insns[j] instanceof JumpInsnNode jump)) continue;
                try {
                    var after = analyzer.execute(frames[j], jump);
                    for (var edge : analyzer.edges(jump, frames[j], after, result.index(), j))
                        if (edge.target() == i && jump.label == label) incoming.add(new Object[] {jump, edge.frame(), false});
                } catch (AnalyzerException error) { return false; }
            }
            var before = Cfg.previousReal(label);
            if (before != null && frames[result.index().get(before)] != null && fallsThrough(before) && onlyMarkersBetween(before, label)) {
                int j = result.index().get(before);
                try {
                    var after = analyzer.execute(frames[j], before);
                    for (var edge : analyzer.edges(before, frames[j], after, result.index(), j))
                        if (edge.target() == j + 1) incoming.add(new Object[] {before, edge.frame(), true});
                } catch (AnalyzerException error) { return false; }
            }
            boolean randomKeys = randomKeys(label);
            for (var edge : incoming) {
                @SuppressWarnings("unchecked") var frame = (Frame<FV>) edge[1];
                var target = decision(label, frame, strongKeys);
                if (target == null || target == label) { ambiguous++; continue; }
                plan.add(new Retarget((AbstractInsnNode) edge[0], label, target, (Boolean) edge[2]));
                if (randomKeys || strongKeys[0]) strong++;
            }
        }
        stats.ambiguous = ambiguous;
        // A hand-written state machine (small literal states, javac's own switch) is threaded only in a method that already
        // has evidence; an opaque state or the random keys of an obfuscator's dispatcher are evidence themselves.
        if (plan.isEmpty() || strong == 0 && !evidenceSoFar) return false;
        for (var retarget : plan) {
            if (retarget.fallthrough()) method.instructions.insert(retarget.site(), new JumpInsnNode(GOTO, retarget.to()));
            else if (((JumpInsnNode) retarget.site()).label == retarget.from()) ((JumpInsnNode) retarget.site()).label = retarget.to();
            threaded++;
        }
        stats.threaded += threaded;
        return threaded > 0;
    }

    /** A dispatcher switch with a key at least 2^16 in size: an obfuscator's random state keys. */
    private static boolean randomKeys(LabelNode label) {
        var insn = Cfg.start(label);
        for (int steps = 0; insn != null && steps < 32; steps++, insn = Cfg.nextReal(insn)) {
            if (insn instanceof LookupSwitchInsnNode lookup) return lookup.keys.stream().anyMatch(k -> Math.abs((long) k) >= 1 << 16);
            if (insn instanceof TableSwitchInsnNode table) return Math.abs((long) table.min) >= 1 << 16 || Math.abs((long) table.max) >= 1 << 16;
        }
        return false;
    }

    /** Whether the code at {@code label} is a dispatcher: side-effect-free straight-line code ending in a switch. */
    private boolean dispatcher(LabelNode label) {
        var insn = Cfg.start(label);
        for (int steps = 0; insn != null && steps < 32; steps++, insn = Cfg.nextReal(insn)) {
            if (insn instanceof TableSwitchInsnNode || insn instanceof LookupSwitchInsnNode) return true;
            if (!quiet(insn, null)) return false;
        }
        return false;
    }

    /** The case label a dispatcher goes to from {@code frame}, or null when the key is not decided. */
    private LabelNode decision(LabelNode label, Frame<FV> entry, boolean[] opaqueKey) {
        opaqueKey[0] = false;
        var frame = new Frame<FV>(entry);
        int depth = frame.getStackSize();
        var insn = Cfg.start(label);
        try {
            for (int steps = 0; insn != null && steps < 32; steps++) {
                if (insn instanceof TableSwitchInsnNode || insn instanceof LookupSwitchInsnNode) {
                    if (frame.getStackSize() != depth + 1) return null;
                    FV key = frame.getStack(frame.getStackSize() - 1);
                    if (!key.isConstant()) return null;
                    opaqueKey[0] = key.opaque;
                    int value = key.value();
                    if (insn instanceof TableSwitchInsnNode table)
                        return value >= table.min && value <= table.max ? table.labels.get(value - table.min) : table.dflt;
                    var lookup = (LookupSwitchInsnNode) insn;
                    int at = lookup.keys.indexOf(value);
                    return at >= 0 ? lookup.labels.get(at) : lookup.dflt;
                }
                if (!quiet(insn, frame)) return null;
                frame = analyzer.execute(frame, insn);
                insn = Cfg.nextReal(insn);
            }
        } catch (AnalyzerException | RuntimeException error) { return null; }
        return null;
    }

    /**
     * An instruction with no effect but on the operand stack, that cannot throw: constants, loads, stack moves and int or
     * long arithmetic (a division only by a divisor {@code frame} proves non-zero), and reads of this class's own proved
     * constants (this class is already initialized).
     */
    private boolean quiet(AbstractInsnNode insn, Frame<FV> frame) {
        int op = insn.getOpcode();
        if (op == NOP || op >= ACONST_NULL && op <= SIPUSH || op >= ILOAD && op <= ALOAD || op >= POP && op <= SWAP) return true;
        if (insn instanceof LdcInsnNode ldc) return ldc.cst instanceof Number || ldc.cst instanceof String;
        if (op == IDIV || op == IREM) return frame != null && frame.getStack(frame.getStackSize() - 1).excludesZero();
        if (op == LDIV || op == LREM) return frame != null && frame.getStack(frame.getStackSize() - 1).lconst != null && frame.getStack(frame.getStackSize() - 1).lconst != 0;
        if (op >= IADD && op <= DREM || op >= INEG && op <= DCMPG) return true;
        if (insn instanceof FieldInsnNode field && op == GETSTATIC)
            return field.owner.equals(currentOwner) && facts.constant(field.owner, field.name, field.desc) != null;
        return false;
    }

    private static boolean fallsThrough(AbstractInsnNode insn) {
        int op = insn.getOpcode();
        return !(op == GOTO || op >= IRETURN && op <= RETURN || op == ATHROW || insn instanceof TableSwitchInsnNode || insn instanceof LookupSwitchInsnNode);
    }
    private static boolean onlyMarkersBetween(AbstractInsnNode from, AbstractInsnNode to) {
        for (var node = from.getNext(); node != null && node != to; node = node.getNext()) if (node.getOpcode() >= 0) return false;
        return true;
    }

    // ===================================================================================================== exceptions

    /**
     * {@code new X; dup; invokespecial X.<init>()V; athrow} on an empty stack, whose first matching handler in this method
     * starts by discarding the exception ({@code pop}, or {@code astore} to a local nothing reads), becomes a jump past
     * that discard: the exception is only a goto. {@code X} must be a JDK class or a class of the input whose no-argument
     * constructors only chain up and that has no static initializer, so not creating it changes nothing.
     */
    private boolean throwGotos(MethodNode method, FlowAnalyzer.Result result, Stats stats) {
        var insns = result.insns(); var frames = result.frames();
        var targets = Cfg.jumpTargets(method);
        record Site(AbstractInsnNode alloc, AbstractInsnNode dup, AbstractInsnNode ctor, AbstractInsnNode athrow, AbstractInsnNode discard) {}
        var sites = new ArrayList<Site>();
        for (int i = 0; i < insns.length; i++) {
            if (!(insns[i] instanceof TypeInsnNode alloc) || alloc.getOpcode() != NEW || frames[i] == null || frames[i].getStackSize() != 0) continue;
            var dup = Cfg.nextReal(alloc); var ctor = Cfg.nextReal(dup); var athrow = Cfg.nextReal(ctor);
            if (dup == null || dup.getOpcode() != DUP || !(ctor instanceof MethodInsnNode init) || init.getOpcode() != INVOKESPECIAL
                || !init.name.equals("<init>") || !init.desc.equals("()V") || !init.owner.equals(alloc.desc) || athrow == null || athrow.getOpcode() != ATHROW) continue;
            boolean entered = false;
            for (var node = alloc.getNext(); node != athrow; node = node.getNext()) if (node instanceof LabelNode label && targets.contains(label)) entered = true;
            if (entered || !silentConstruction(alloc.desc)) continue;
            var handler = firstHandler(method, result.index().get(athrow), alloc.desc, result.index());
            if (handler == null) continue;
            var first = Cfg.start(handler.handler);
            boolean discards = first != null && (first.getOpcode() == POP
                || first instanceof VarInsnNode store && store.getOpcode() == ASTORE && !read(method, store.var));
            if (discards) sites.add(new Site(alloc, dup, ctor, athrow, first));
        }
        if (sites.isEmpty()) return false;
        for (var site : sites) {
            var resume = new LabelNode();
            method.instructions.insert(site.discard(), resume);
            method.instructions.remove(site.alloc()); method.instructions.remove(site.dup()); method.instructions.remove(site.ctor());
            method.instructions.set(site.athrow(), new JumpInsnNode(GOTO, resume));
        }
        stats.throwGotos += sites.size();
        return true;
    }

    /** The first exception table entry that catches an exact {@code type} thrown at {@code at}; null when none or unknown. */
    private TryCatchBlockNode firstHandler(MethodNode method, int at, String type, Map<AbstractInsnNode,Integer> index) {
        var ancestors = ancestors(type);
        if (ancestors == null) return null;
        for (var block : method.tryCatchBlocks) {
            if (at < index.get(block.start) || at >= index.get(block.end)) continue;
            if (block.type == null || ancestors.contains(block.type)) return block;
        }
        return null;
    }

    /** {@code type} and its superclasses up to {@code java/lang/Throwable}; null when the chain is not fully known. */
    private List<String> ancestors(String type) {
        var chain = new ArrayList<String>();
        for (String current = type; current != null && chain.size() < 64; ) {
            chain.add(current);
            if (current.equals("java/lang/Throwable")) return chain;
            current = superclass(current);
        }
        return null;
    }
    private String superclass(String type) {
        var node = nodes.get(type);
        if (node != null) return node.superName;
        return jdkSupers.computeIfAbsent(type, name -> {
            if (!name.startsWith("java/") && !name.startsWith("javax/")) return Optional.empty();
            try {
                var found = Class.forName(name.replace('/', '.'), false, ClassLoader.getPlatformClassLoader()).getSuperclass();
                return found == null ? Optional.empty() : Optional.of(Type.getInternalName(found));
            } catch (ClassNotFoundException | LinkageError unknown) { return Optional.empty(); }
        }).orElse(null);
    }

    /** Creating {@code type} with its no-argument constructor has no effect: a JDK class, or input classes that only chain up. */
    private boolean silentConstruction(String type) {
        for (String current = type; current != null; ) {
            var node = nodes.get(current);
            if (node == null) return (current.startsWith("java/") || current.startsWith("javax/")) && ancestors(current) != null;
            boolean plain = false;
            for (var method : node.methods) {
                if (method.name.equals("<clinit>")) return false;
                if (method.name.equals("<init>") && method.desc.equals("()V")) {
                    var code = new ArrayList<AbstractInsnNode>();
                    for (var insn : method.instructions) if (insn.getOpcode() >= 0) code.add(insn);
                    plain = code.size() == 3 && code.get(0) instanceof VarInsnNode load && load.getOpcode() == ALOAD && load.var == 0
                        && code.get(1) instanceof MethodInsnNode call && call.getOpcode() == INVOKESPECIAL && call.name.equals("<init>")
                        && call.desc.equals("()V") && call.owner.equals(node.superName) && code.get(2).getOpcode() == RETURN;
                }
            }
            if (!plain) return false;
            current = node.superName;
        }
        return false;
    }

    private static boolean read(MethodNode method, int var) {
        for (var insn : method.instructions) {
            if (insn instanceof VarInsnNode use && use.var == var && (use.getOpcode() >= ILOAD && use.getOpcode() <= ALOAD || use.getOpcode() == RET)) return true;
            if (insn instanceof IincInsnNode increment && increment.var == var) return true;
        }
        return false;
    }

    // ===================================================================================================== clean-up

    /** Cosmetic clean-ups, only in a method that had evidence. Returns whether anything changed. */
    private boolean cleanup(MethodNode method, Stats stats) {
        boolean changed = false;
        var targets = Cfg.jumpTargets(method);
        // Jump chains and jumps to the next instruction.
        for (var insn : method.instructions.toArray()) {
            if (insn instanceof JumpInsnNode jump) {
                var destination = Cfg.resolve(jump.label);
                if (destination != jump.label) { jump.label = destination; stats.chains++; changed = true; }
                if (nextIs(jump, jump.label)) {
                    if (jump.getOpcode() == GOTO) method.instructions.remove(jump);
                    else {
                        boolean single = jump.getOpcode() >= IFEQ && jump.getOpcode() <= IFLE || jump.getOpcode() == IFNULL || jump.getOpcode() == IFNONNULL;
                        method.instructions.insertBefore(jump, new InsnNode(POP)); if (!single) method.instructions.insertBefore(jump, new InsnNode(POP));
                        method.instructions.remove(jump);
                    }
                    stats.nexts++; changed = true; continue;
                }
                var landing = Cfg.start(jump.label);
                if (jump.getOpcode() == GOTO && landing != null && landing.getOpcode() >= IRETURN && landing.getOpcode() <= RETURN
                    && sameCoverage(method, jump, landing)) {
                    method.instructions.set(jump, new InsnNode(landing.getOpcode())); stats.returns++; changed = true;
                }
            } else if (insn instanceof TableSwitchInsnNode table) {
                for (int k = 0; k < table.labels.size(); k++) { var d = Cfg.resolve(table.labels.get(k)); if (d != table.labels.get(k)) { table.labels.set(k, d); stats.chains++; changed = true; } }
                var d = Cfg.resolve(table.dflt); if (d != table.dflt) { table.dflt = d; stats.chains++; changed = true; }
            } else if (insn instanceof LookupSwitchInsnNode lookup) {
                for (int k = 0; k < lookup.labels.size(); k++) { var d = Cfg.resolve(lookup.labels.get(k)); if (d != lookup.labels.get(k)) { lookup.labels.set(k, d); stats.chains++; changed = true; } }
                var d = Cfg.resolve(lookup.dflt); if (d != lookup.dflt) { lookup.dflt = d; stats.chains++; changed = true; }
            }
        }
        // Stores nothing reads.
        var read = new HashSet<Integer>();
        for (var insn : method.instructions)
            if (insn instanceof VarInsnNode use && (use.getOpcode() >= ILOAD && use.getOpcode() <= ALOAD || use.getOpcode() == RET)) read.add(use.var);
        for (var insn : method.instructions.toArray()) {
            if (insn instanceof VarInsnNode store && store.getOpcode() >= ISTORE && store.getOpcode() <= ASTORE && !read.contains(store.var)) {
                method.instructions.set(insn, new InsnNode(store.getOpcode() == LSTORE || store.getOpcode() == DSTORE ? POP2 : POP));
                stats.stores++; changed = true;
            } else if (insn instanceof IincInsnNode increment && !read.contains(increment.var)) {
                method.instructions.remove(insn); stats.stores++; changed = true;
            }
        }
        if (changed && method.localVariables != null) {
            var used = new HashSet<Integer>();
            for (var insn : method.instructions) {
                if (insn instanceof VarInsnNode v) used.add(v.var);
                if (insn instanceof IincInsnNode v) used.add(v.var);
            }
            int parameters = (method.access & ACC_STATIC) == 0 ? 1 : 0;
            for (var type : Type.getArgumentTypes(method.desc)) parameters += type.getSize();
            int firstLocal = parameters;
            method.localVariables.removeIf(variable -> variable.index >= firstLocal && !used.contains(variable.index));
        }
        // Pushes that are popped at once.
        boolean again = true;
        while (again) {
            again = false;
            targets = Cfg.jumpTargets(method);
            for (var insn : method.instructions.toArray()) {
                if (insn.getOpcode() != POP && insn.getOpcode() != POP2) continue;
                var producer = Cfg.previousReal(insn);
                if (producer == null || labelBetween(producer, insn, targets) || !dropPopped(method, producer, insn)) continue;
                stats.pushes++; changed = again = true;
                break;
            }
        }
        // Try ranges where nothing can throw and that cannot catch a VirtualMachineError, and handlers that only rethrow.
        var index = new IdentityHashMap<AbstractInsnNode,Integer>();
        int position = 0;
        for (var insn : method.instructions) index.put(insn, position++);
        for (int b = method.tryCatchBlocks.size() - 1; b >= 0; b--) {
            var block = method.tryCatchBlocks.get(b);
            boolean throwing = false;
            for (var insn = block.start.getNext(); insn != null && insn != block.end; insn = insn.getNext()) if (Cfg.canThrow(insn)) { throwing = true; break; }
            if (!throwing && !FlowAnalyzer.catchesVmErrors(block)) { method.tryCatchBlocks.remove(b); stats.idle++; changed = true; continue; }
            if (rethrowOnly(method, b, index)) { method.tryCatchBlocks.remove(b); stats.rethrows++; changed = true; }
        }
        int before = method.tryCatchBlocks.size();
        removeEmptyRanges(method);
        stats.idle += before - method.tryCatchBlocks.size();
        return changed;
    }

    private static boolean nextIs(AbstractInsnNode jump, LabelNode label) {
        for (var node = jump.getNext(); node != null; node = node.getNext()) {
            if (node == label) return true;
            if (node.getOpcode() >= 0) return false;
        }
        return false;
    }
    private static boolean labelBetween(AbstractInsnNode first, AbstractInsnNode second, Set<LabelNode> targets) {
        for (var node = first.getNext(); node != null && node != second; node = node.getNext())
            if (node instanceof LabelNode label && targets.contains(label)) return true;
        return false;
    }

    /** Whether two instructions lie in exactly the same try ranges (so a return there throws to the same handlers). */
    private static boolean sameCoverage(MethodNode method, AbstractInsnNode a, AbstractInsnNode b) {
        if (method.tryCatchBlocks.isEmpty()) return true;
        int x = method.instructions.indexOf(a), y = method.instructions.indexOf(b);
        for (var block : method.tryCatchBlocks) {
            int start = method.instructions.indexOf(block.start), end = method.instructions.indexOf(block.end);
            if ((x >= start && x < end) != (y >= start && y < end)) return false;
        }
        return true;
    }

    /** Removes {@code producer; pop} when the producer has no effect but its push (its own operands are popped instead). */
    private boolean dropPopped(MethodNode method, AbstractInsnNode producer, AbstractInsnNode pop) {
        int op = producer.getOpcode();
        boolean wide = pop.getOpcode() == POP2;
        boolean pushWide = op == LCONST_0 || op == LCONST_1 || op == DCONST_0 || op == DCONST_1 || op == LLOAD || op == DLOAD
            || producer instanceof LdcInsnNode ldc && (ldc.cst instanceof Long || ldc.cst instanceof Double);
        boolean pushNarrow = op >= ACONST_NULL && op <= ICONST_5 || op >= FCONST_0 && op <= FCONST_2 || op == BIPUSH || op == SIPUSH
            || op == ILOAD || op == FLOAD || op == ALOAD || op == DUP
            || producer instanceof LdcInsnNode ldc2 && (ldc2.cst instanceof Integer || ldc2.cst instanceof Float || ldc2.cst instanceof String)
            || producer instanceof FieldInsnNode field && op == GETSTATIC && field.owner.equals(currentOwner) && Type.getType(field.desc).getSize() == 1
               && declares(field.owner, field.name, field.desc);
        if (wide ? pushWide : pushNarrow) { method.instructions.remove(producer); method.instructions.remove(pop); return true; }
        if (wide) return false;
        if (op == IADD || op == ISUB || op == IMUL || op == ISHL || op == ISHR || op == IUSHR || op == IAND || op == IOR || op == IXOR
            || op == FADD || op == FSUB || op == FMUL || op == FDIV || op == FREM || op == FCMPL || op == FCMPG) {
            method.instructions.insertBefore(producer, new InsnNode(POP)); method.instructions.remove(producer); return true;
        }
        if ((op == IDIV || op == IREM) && dev.classrevive.transforms.IntegerConstantPass.literal(Cfg.previousReal(producer)) instanceof Integer d && d != 0) {
            method.instructions.remove(Cfg.previousReal(producer)); method.instructions.remove(producer); return true;
        }
        if (op == INEG || op == I2B || op == I2C || op == I2S || op == I2F || op == F2I || op == FNEG) { method.instructions.remove(producer); return true; }
        return false;
    }
    private boolean declares(String owner, String name, String desc) {
        var node = nodes.get(owner);
        if (node == null) return false;
        for (var field : node.fields) if (field.name.equals(name) && field.desc.equals(desc) && (field.access & ACC_STATIC) != 0) return true;
        return false;
    }

    /**
     * Entry {@code b} rethrows what it catches ({@code athrow}, or {@code astore k; aload k; athrow}) outside every try
     * range, and no later entry overlaps its range: without it the same exceptions leave the method the same way.
     */
    private static boolean rethrowOnly(MethodNode method, int b, Map<AbstractInsnNode,Integer> index) {
        var block = method.tryCatchBlocks.get(b);
        var first = Cfg.start(block.handler);
        var code = new ArrayList<AbstractInsnNode>();
        if (first != null && first.getOpcode() == ATHROW) code.add(first);
        else if (first instanceof VarInsnNode store && store.getOpcode() == ASTORE && Cfg.nextReal(store) instanceof VarInsnNode load
                 && load.getOpcode() == ALOAD && load.var == store.var && Cfg.nextReal(load) != null && Cfg.nextReal(load).getOpcode() == ATHROW) {
            code.add(store); code.add(load); code.add(Cfg.nextReal(load));
        } else return false;
        int start = index.get(block.start), end = index.get(block.end);
        for (var other : method.tryCatchBlocks) {
            int s = index.get(other.start), e = index.get(other.end);
            for (var insn : code) { int at = index.get(insn); if (at >= s && at < e) return false; }
        }
        for (int later = b + 1; later < method.tryCatchBlocks.size(); later++) {
            var other = method.tryCatchBlocks.get(later);
            if (index.get(other.start) < end && start < index.get(other.end)) return false;
        }
        return true;
    }
}
