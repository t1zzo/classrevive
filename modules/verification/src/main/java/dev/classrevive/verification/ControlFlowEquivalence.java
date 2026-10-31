package dev.classrevive.verification;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;
import java.util.*;

/**
 * Control-flow comparison for the round trip (Phase 2AX). Two bodies are equivalent when their control-flow graphs,
 * walked in parallel from the entry, have the same instructions in every block (constants and local slots normalized
 * as in {@link MethodRoundTrip}, locals matched by a consistent bijection) and the same edges, where a conditional
 * branch may also be the negated condition with its two successors swapped. Block layout therefore does not matter:
 * {@code if (c) { A } else { B }} equals {@code if (!c) { B } else { A }}. A trace follows fall-through and
 * unconditional jumps from block to block until a real control decision, so a jump to a block that only returns is
 * read as that return. Bodies with {@code jsr} are never called equivalent. Everything is parsed with ASM only;
 * nothing is loaded or executed.
 *
 * <p>Phase 2BH: an edge to a block that only jumps elsewhere goes straight to where it jumps ({@code if (c) goto A;
 * goto B; A:} is {@code if (!c) goto B; A:}); a test of a value that is 0 or 1 whose two successors return the
 * constants 1 and 0 is a return of that value ({@code if (!b()) return false; return true;} is {@code return b();});
 * and blocks are paired as a relation, not one to one, and a trace follows an unconditional edge into a block
 * whatever else reaches it, so one return or one tail shared by several paths equals copies of it. The walk then
 * checks a bisimulation: every paired couple of traces runs the same instructions (under one mapping of locals) and
 * leads to paired successors, so both bodies run the same instructions on every path.
 *
 * <p>Also since Phase 2BH, bodies with exception handlers are compared: blocks also start where a handler's range
 * starts or ends and at each handler, a block's exceptional successors are the handlers whose range holds it (in
 * table order, without entries that an earlier one already catches), and every instruction of a trace that can throw
 * must have the same catch types leading to paired handlers as its counterpart. An instruction that cannot throw
 * (a load, a constant, a jump...) may lie inside a range on one side and outside on the other. A trace never runs
 * into a handler.
 */
final class ControlFlowEquivalence {
    private static final int MAX_BLOCKS = 20_000;
    /** Paired couples visited before giving up; a relation is quadratic at worst, so this bounds the walk. */
    private static final int MAX_PAIRS = 200_000;
    /** Blocks one trace follows through unconditional edges before it ends as a jump. */
    private static final int MAX_TRACE_BLOCKS = 256;
    private enum Kind { FALL, GOTO, COND, EXIT, SWITCH }

    /** An exceptional successor: the caught type (null catches anything) and the handler's block. */
    private record Handler(String type, int block) {}

    private static final class Block {
        final List<AbstractInsnNode> body = new ArrayList<>();
        Kind kind; int opcode; LabelNode label; int taken = -1, next = -1;
        AbstractInsnNode terminator;
        List<Integer> targets = List.of();
        /** Index in the method of the block's first instruction, or -1 while it has none. */
        int first = -1;
        List<Handler> handlers = List.of();
        boolean handler;
    }

    private ControlFlowEquivalence() {}

    static boolean equivalent(MethodNode a, MethodNode b) { return equivalent(a, b, Set.of()); }

    static boolean equivalent(MethodNode a, MethodNode b, Set<String> privates) { return equivalent(a, b, MethodRoundTrip.Sides.of(privates)); }

    static boolean equivalent(MethodNode a, MethodNode b, MethodRoundTrip.Sides sides) {
        try {
            // Locals are matched by variable (MethodRoundTrip.webs) when both bodies have them, else by slot.
            var websLeft = MethodRoundTrip.webs(a, parameterSlots(a)); var websRight = MethodRoundTrip.webs(b, parameterSlots(b));
            if (websLeft == null || websRight == null) websLeft = websRight = null;
            var left = blocks(a, MethodRoundTrip.localRewrites(a, parameterSlots(a), websLeft), sides.original().forwarders());
            var right = blocks(b, MethodRoundTrip.localRewrites(b, parameterSlots(b), websRight), sides.recompiled().forwarders());
            if (left == null || right == null) return false;
            return new Walk(left, right, parameterSlots(a), sides, websLeft, websRight).run();
        } catch (RuntimeException unexpected) {
            return false;
        }
    }

    private static int parameterSlots(MethodNode method) {
        int slots = Type.getArgumentsAndReturnSizes(method.desc) >> 2; // includes the implicit receiver slot
        return (method.access & Opcodes.ACC_STATIC) != 0 ? slots - 1 : slots;
    }

    /** Basic blocks in layout order, or null for shapes this comparison does not model. */
    private static List<Block> blocks(MethodNode method, Map<AbstractInsnNode,List<AbstractInsnNode>> rewrites, Map<String,List<AbstractInsnNode>> forwarders) {
        var targets = new HashSet<LabelNode>();
        for (var insn : method.instructions) {
            if (insn instanceof JumpInsnNode jump) targets.add(jump.label);
            else if (insn instanceof TableSwitchInsnNode table) { targets.add(table.dflt); targets.addAll(table.labels); }
            else if (insn instanceof LookupSwitchInsnNode lookup) { targets.add(lookup.dflt); targets.addAll(lookup.labels); }
        }
        for (var range : method.tryCatchBlocks) { targets.add(range.start); targets.add(range.end); targets.add(range.handler); }
        var blocks = new ArrayList<Block>(); var at = new HashMap<LabelNode,Integer>();
        Block current = null; int index = -1;
        var items = new ArrayList<AbstractInsnNode>(); var positions = new ArrayList<Integer>();
        for (var insn : method.instructions) {
            index++;
            for (var item : rewrites.getOrDefault(insn, List.of(insn))) { items.add(item); positions.add(index); }
        }
        for (int item = 0; item < items.size(); item++) {
            var insn = items.get(item); index = positions.get(item);
            if (insn instanceof LineNumberNode || insn instanceof FrameNode) continue;
            if (insn instanceof LabelNode label) {
                if (!targets.contains(label)) continue;
                if (current != null && !current.body.isEmpty()) { current.kind = Kind.FALL; current = null; }
                if (current == null) { current = new Block(); blocks.add(current); }
                at.put(label, blocks.size() - 1);
                continue;
            }
            if (current == null) { current = new Block(); blocks.add(current); }
            if (current.first < 0) current.first = index;
            int opcode = insn.getOpcode();
            if (opcode == Opcodes.JSR || opcode == Opcodes.RET) return null;
            if (insn instanceof JumpInsnNode jump) {
                current.kind = opcode == Opcodes.GOTO ? Kind.GOTO : Kind.COND; current.opcode = opcode; current.label = jump.label;
            } else if (insn instanceof TableSwitchInsnNode || insn instanceof LookupSwitchInsnNode) {
                current.kind = Kind.SWITCH; current.terminator = insn;
            } else if (opcode >= Opcodes.IRETURN && opcode <= Opcodes.RETURN || opcode == Opcodes.ATHROW) {
                current.kind = Kind.EXIT; current.opcode = opcode;
            } else {
                // As in MethodRoundTrip.render: a forwarding method's call is its instructions, a dup of a just-loaded
                // local is that load again.
                var forwarded = MethodRoundTrip.inlined(insn, forwarders);
                var reloaded = MethodRoundTrip.reloadedDup(insn, current.body.isEmpty() ? null : current.body.getLast());
                if (forwarded != null) current.body.addAll(forwarded);
                else current.body.add(reloaded != null ? reloaded : insn);
                continue;
            }
            current = null;
            if (blocks.size() > MAX_BLOCKS) return null;
        }
        // A trailing block without a terminator (an unused label at the end) carries nothing; anything else is malformed.
        if (!blocks.isEmpty() && blocks.getLast().kind == null) {
            if (!blocks.getLast().body.isEmpty()) return null;
            blocks.removeLast();
        }
        for (int i = 0; i < blocks.size(); i++) {
            var block = blocks.get(i);
            if (block.kind == null) return null;
            if (block.kind == Kind.FALL || block.kind == Kind.COND) {
                if (i + 1 >= blocks.size()) return null;
                block.next = i + 1;
            }
            if (block.kind == Kind.GOTO || block.kind == Kind.COND) {
                Integer target = at.get(block.label);
                if (target == null || target >= blocks.size()) return null;
                block.taken = target;
            }
            if (block.kind == Kind.SWITCH) {
                var list = new ArrayList<Integer>();
                var labels = new ArrayList<LabelNode>();
                if (block.terminator instanceof TableSwitchInsnNode table) { labels.add(table.dflt); labels.addAll(table.labels); }
                else if (block.terminator instanceof LookupSwitchInsnNode lookup) { labels.add(lookup.dflt); labels.addAll(lookup.labels); }
                for (var label : labels) {
                    Integer target = at.get(label);
                    if (target == null || target >= blocks.size()) return null;
                    list.add(target);
                }
                block.targets = List.copyOf(list);
            }
        }
        if (blocks.isEmpty() || !handlers(method, blocks, at)) return null;
        thread(blocks);
        for (var block : blocks) if (booleanReturn(blocks, block)) { block.kind = Kind.EXIT; block.opcode = Opcodes.IRETURN; block.taken = block.next = -1; }
        return blocks;
    }

    /**
     * Each block's exceptional successors, from the handler ranges that hold its first instruction (ranges start and end
     * at block boundaries, so they hold all of it). An entry for a type and handler already listed, or after an entry
     * that catches anything, is never used by that block and is left out. Returns false for a malformed table.
     */
    private static boolean handlers(MethodNode method, List<Block> blocks, Map<LabelNode,Integer> at) {
        if (method.tryCatchBlocks.isEmpty()) return true;
        var position = new IdentityHashMap<AbstractInsnNode,Integer>();
        int index = 0;
        for (var insn : method.instructions) position.put(insn, index++);
        for (var range : method.tryCatchBlocks) {
            Integer handler = at.get(range.handler);
            if (handler == null || !position.containsKey(range.start) || !position.containsKey(range.end)) return false;
            blocks.get(handler).handler = true;
        }
        for (var block : blocks) {
            if (block.first < 0) continue;
            var list = new ArrayList<Handler>();
            for (var range : method.tryCatchBlocks) {
                if (position.get(range.start) > block.first || block.first >= position.get(range.end)) continue;
                var entry = new Handler(range.type, at.get(range.handler));
                if (!list.contains(entry)) list.add(entry);
                if (range.type == null) break;
            }
            block.handlers = List.copyOf(list);
        }
        return true;
    }

    /**
     * Phase 2BH: every edge into a block that holds nothing but a {@code goto} (and is not the entry or a handler) is
     * redirected to where that jump leads, following chains of such blocks; a cycle of empty jumps keeps its last edge.
     */
    private static void thread(List<Block> blocks) {
        for (var block : blocks) {
            block.next = through(blocks, block.next);
            block.taken = through(blocks, block.taken);
            if (!block.targets.isEmpty()) block.targets = block.targets.stream().map(target -> through(blocks, target)).toList();
        }
    }

    private static int through(List<Block> blocks, int target) {
        var seen = new HashSet<Integer>();
        while (target > 0 && blocks.get(target).body.isEmpty() && blocks.get(target).kind == Kind.GOTO && !blocks.get(target).handler && seen.add(target))
            target = blocks.get(target).taken;
        return target;
    }

    /**
     * Phase 2BH: {@code ifne} (or {@code ifeq}) on a value that can only be 0 or 1, whose taken successor returns the
     * constant 1 (or 0) and whose other successor returns 0 (or 1), returns that value either way. Values known to be 0
     * or 1: the result of a method whose return type is {@code boolean} (JVMS 6.5 {@code ireturn} narrows it), of
     * {@code instanceof}, and a {@code boolean} field ({@code putfield} and {@code putstatic} narrow what they store).
     */
    private static boolean booleanReturn(List<Block> blocks, Block block) {
        if (block.kind != Kind.COND || block.opcode != Opcodes.IFNE && block.opcode != Opcodes.IFEQ || block.body.isEmpty()) return false;
        var last = block.body.getLast();
        boolean bit = last instanceof MethodInsnNode call && call.desc.endsWith(")Z") || last.getOpcode() == Opcodes.INSTANCEOF
            || last instanceof FieldInsnNode field && (field.getOpcode() == Opcodes.GETFIELD || field.getOpcode() == Opcodes.GETSTATIC) && field.desc.equals("Z");
        if (!bit) return false;
        int taken = constantReturn(blocks, block.taken), next = constantReturn(blocks, block.next);
        return block.opcode == Opcodes.IFNE ? taken == 1 && next == 0 : taken == 0 && next == 1;
    }

    /** The int constant 0 or 1 that a block pushes and returns at once (directly or through a jump to a bare return); else -1. */
    private static int constantReturn(List<Block> blocks, int index) {
        if (index < 0) return -1;
        var block = blocks.get(index);
        if (block.body.size() != 1) return -1;
        String constant = MethodRoundTrip.token(block.body.getFirst(), Set.of());
        if (!"int 0".equals(constant) && !"int 1".equals(constant)) return -1;
        boolean returns = block.kind == Kind.EXIT && block.opcode == Opcodes.IRETURN;
        if (!returns && (block.kind == Kind.GOTO || block.kind == Kind.FALL)) {
            var successor = blocks.get(block.kind == Kind.GOTO ? block.taken : block.next);
            returns = successor.body.isEmpty() && successor.kind == Kind.EXIT && successor.opcode == Opcodes.IRETURN;
        }
        return returns ? constant.charAt(4) - '0' : -1;
    }

    /**
     * Whether an instruction can throw: anything but locals, constants (other than class, method type, method handle and
     * dynamic constants, which may fail to resolve), stack and arithmetic operations except integer division and
     * remainder, conversions, comparisons, jumps, switches and returns.
     */
    private static boolean mayThrow(AbstractInsnNode insn) {
        int opcode = insn.getOpcode();
        if (insn instanceof VarInsnNode || insn instanceof IincInsnNode || insn instanceof JumpInsnNode
            || insn instanceof TableSwitchInsnNode || insn instanceof LookupSwitchInsnNode) return false;
        if (insn instanceof LdcInsnNode ldc) return !(ldc.cst instanceof Number || ldc.cst instanceof String);
        if (insn instanceof IntInsnNode) return opcode == Opcodes.NEWARRAY;
        if (insn instanceof InsnNode) {
            if (opcode >= Opcodes.IALOAD && opcode <= Opcodes.SALOAD || opcode >= Opcodes.IASTORE && opcode <= Opcodes.SASTORE) return true;
            return opcode == Opcodes.IDIV || opcode == Opcodes.LDIV || opcode == Opcodes.IREM || opcode == Opcodes.LREM
                || opcode == Opcodes.ARRAYLENGTH || opcode == Opcodes.ATHROW || opcode == Opcodes.MONITORENTER || opcode == Opcodes.MONITOREXIT;
        }
        return true;
    }

    /**
     * Joined blocks from one head up to a real control decision, with the handlers of each body instruction's block and
     * those of the block that ends the trace.
     */
    private record Trace(List<AbstractInsnNode> body, List<List<Handler>> handlers, Kind kind, int opcode, int taken, int next,
                         AbstractInsnNode terminator, List<Integer> targets, List<Handler> exit) {}

    private static final class Walk {
        private final List<Block> left, right; private final int fixed; private final MethodRoundTrip.Sides sides;
        private final Map<AbstractInsnNode,Integer> websLeft, websRight;
        private final Set<Long> paired = new HashSet<>();
        private final Map<Integer,Integer> slotsLeft = new HashMap<>(), slotsRight = new HashMap<>();
        private final ArrayDeque<int[]> work = new ArrayDeque<>();

        Walk(List<Block> left, List<Block> right, int fixed, MethodRoundTrip.Sides sides, Map<AbstractInsnNode,Integer> websLeft,
             Map<AbstractInsnNode,Integer> websRight) {
            this.sides = sides; this.websLeft = websLeft; this.websRight = websRight;
            this.left = left; this.right = right; this.fixed = fixed;
        }

        boolean run() {
            if (!pair(0, 0)) return false;
            while (!work.isEmpty()) {
                int[] next = work.pop();
                if (!same(trace(left, next[0]), trace(right, next[1]))) return false;
            }
            return true;
        }

        /** Records a couple of blocks to compare; each couple is compared once, and a block may be paired with several. */
        private boolean pair(int a, int b) {
            if (paired.add((long) a * right.size() + b)) {
                if (paired.size() > MAX_PAIRS) return false;
                work.push(new int[] {a, b});
            }
            return true;
        }

        private static Trace trace(List<Block> blocks, int head) {
            var body = new ArrayList<AbstractInsnNode>(); var handlers = new ArrayList<List<Handler>>(); var seen = new HashSet<Integer>();
            int at = head;
            while (true) {
                var block = blocks.get(at); seen.add(at); body.addAll(block.body);
                for (int i = 0; i < block.body.size(); i++) handlers.add(block.handlers);
                if (block.kind != Kind.FALL && block.kind != Kind.GOTO)
                    return new Trace(body, handlers, block.kind, block.opcode, block.taken, block.next, block.terminator, block.targets, block.handlers);
                int target = block.kind == Kind.FALL ? block.next : block.taken;
                var successor = blocks.get(target);
                if (successor.body.isEmpty() && successor.kind == Kind.EXIT && !successor.handler)
                    return new Trace(body, handlers, Kind.EXIT, successor.opcode, -1, -1, null, List.of(), successor.handlers);
                if (seen.contains(target) || successor.handler || seen.size() >= MAX_TRACE_BLOCKS)
                    return new Trace(body, handlers, Kind.GOTO, Opcodes.GOTO, target, -1, null, List.of(), block.handlers);
                at = target;
            }
        }

        private boolean same(Trace a, Trace b) {
            if (a.body.size() != b.body.size() || a.kind != b.kind) return false;
            for (int i = 0; i < a.body.size(); i++) {
                if (!sameInstruction(a.body.get(i), b.body.get(i))) return false;
                // Which handlers hold an instruction matters only when it can throw.
                if (mayThrow(a.body.get(i)) && !sameHandlers(a.handlers.get(i), b.handlers.get(i))) return false;
            }
            if (a.kind == Kind.EXIT && a.opcode == Opcodes.ATHROW && !sameHandlers(a.exit, b.exit)) return false;
            return switch (a.kind) {
                case EXIT -> a.opcode == b.opcode;
                case GOTO -> pair(a.taken, b.taken);
                case COND -> a.opcode == b.opcode ? pair(a.taken, b.taken) && pair(a.next, b.next)
                    : a.opcode == negate(b.opcode) && pair(a.taken, b.next) && pair(a.next, b.taken);
                case SWITCH -> sameSwitch(a.terminator, b.terminator) && a.targets.size() == b.targets.size()
                    && pairAll(a.targets, b.targets);
                case FALL -> false;
            };
        }

        private boolean sameHandlers(List<Handler> a, List<Handler> b) {
            if (a.size() != b.size()) return false;
            for (int i = 0; i < a.size(); i++)
                if (!Objects.equals(a.get(i).type(), b.get(i).type()) || !pair(a.get(i).block(), b.get(i).block())) return false;
            return true;
        }

        private boolean pairAll(List<Integer> a, List<Integer> b) {
            for (int i = 0; i < a.size(); i++) if (!pair(a.get(i), b.get(i))) return false;
            return true;
        }

        private static boolean sameSwitch(AbstractInsnNode a, AbstractInsnNode b) {
            if (a instanceof TableSwitchInsnNode x && b instanceof TableSwitchInsnNode y) return x.min == y.min && x.max == y.max;
            if (a instanceof LookupSwitchInsnNode x && b instanceof LookupSwitchInsnNode y) return x.keys.equals(y.keys);
            return false;
        }

        /** IFEQ/IFNE, IFLT/IFGE, IFGT/IFLE, the IF_ICMP and IF_ACMP pairs, and IFNULL/IFNONNULL are each other's negation. */
        private static int negate(int opcode) {
            if (opcode >= Opcodes.IFEQ && opcode <= Opcodes.IF_ACMPNE) return ((opcode - Opcodes.IFEQ) ^ 1) + Opcodes.IFEQ;
            if (opcode == Opcodes.IFNULL) return Opcodes.IFNONNULL;
            if (opcode == Opcodes.IFNONNULL) return Opcodes.IFNULL;
            return -1;
        }

        private boolean sameInstruction(AbstractInsnNode a, AbstractInsnNode b) {
            if (a instanceof VarInsnNode x && b instanceof VarInsnNode y) return x.getOpcode() == y.getOpcode() && sameSlot(variable(websLeft, x), variable(websRight, y));
            if (a instanceof IincInsnNode x && b instanceof IincInsnNode y) return x.incr == y.incr && sameSlot(variable(websLeft, x), variable(websRight, y));
            String left = MethodRoundTrip.token(a, sides.privates(), sides.original().fields());
            String right = MethodRoundTrip.token(b, sides.privates(), sides.recompiled().fields());
            return left != null && left.equals(right);
        }

        private static int variable(Map<AbstractInsnNode,Integer> webs, AbstractInsnNode insn) {
            return webs != null ? webs.get(insn) : insn instanceof VarInsnNode local ? local.var : ((IincInsnNode) insn).var;
        }

        /** Parameter slots must match; other locals must map one-to-one, in whatever order the two bodies use them. */
        private boolean sameSlot(int a, int b) {
            if (a < fixed || b < fixed) return a == b;
            Integer mapped = slotsLeft.get(a), back = slotsRight.get(b);
            if (mapped == null && back == null) { slotsLeft.put(a, b); slotsRight.put(b, a); return true; }
            return mapped != null && mapped == b && back != null && back == a;
        }
    }
}
