package dev.classrevive.bytecode;

import java.util.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.*;

/**
 * Bytecode reductions for failure minimization (Phase 2AT delta debugging). Every operation parses and writes bytes
 * with ASM only; nothing is loaded, linked or executed here. A result is only a candidate: the caller keeps it when the
 * JVM verifier accepts it ({@link LinkCheck}) and the failure still reproduces.
 *
 * <p>Instruction edits only touch the focus method. Its frames and limits are recomputed in a throwaway class that
 * holds that method alone, so the other methods keep their original bytes and frames. Common superclasses come from
 * the case classes and from JDK classes looked up without initialization through the platform loader, which cannot
 * see target classes; an unknown type counts as {@code java/lang/Object}.
 */
public final class ClassReducer {
    private ClassReducer() {}

    /** A field or method; {@link #key()} is {@code name(desc)ret} for methods and {@code name:desc} for fields. */
    public record Member(boolean method, String name, String descriptor, boolean hasBody) {
        public String key() { return method ? name + descriptor : name + ":" + descriptor; }
    }

    public static List<Member> members(byte[] bytes) {
        var node = read(bytes, ClassReader.SKIP_FRAMES);
        var result = new ArrayList<Member>();
        for (var field : node.fields) result.add(new Member(false, field.name, field.desc, false));
        for (var method : node.methods) result.add(new Member(true, method.name, method.desc, method.instructions.size() > 0));
        return List.copyOf(result);
    }

    /** Real instructions over all methods (labels, line numbers and frames excluded); a size measure for reports. */
    public static int instructions(byte[] bytes) {
        int total = 0;
        for (var method : read(bytes, ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG).methods)
            for (var insn : method.instructions) if (real(insn)) total++;
        return total;
    }

    /** Removes every field and method whose key is not in {@code keep}; the remaining bytes are unchanged. */
    public static byte[] keepMembers(byte[] bytes, Set<String> keep) {
        var node = read(bytes, 0);
        node.fields.removeIf(field -> !keep.contains(field.name + ":" + field.desc));
        node.methods.removeIf(method -> !keep.contains(method.name + method.desc));
        return write(node);
    }

    /**
     * Replaces the bodies of the given methods: constructors call the superclass no-argument constructor and return,
     * every other method becomes {@code throw null}. Neither needs a stack map frame.
     */
    public static byte[] stubMethods(byte[] bytes, Set<String> methods) {
        var node = read(bytes, 0);
        for (var method : node.methods) {
            if (!methods.contains(method.name + method.desc) || method.instructions.size() == 0) continue;
            var body = new InsnList();
            if (method.name.equals("<init>")) {
                body.add(new VarInsnNode(Opcodes.ALOAD, 0));
                body.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, node.superName, "<init>", "()V", false));
                body.add(new InsnNode(Opcodes.RETURN));
            } else {
                body.add(new InsnNode(Opcodes.ACONST_NULL));
                body.add(new InsnNode(Opcodes.ATHROW));
            }
            method.instructions = body; method.tryCatchBlocks = new ArrayList<>(); method.localVariables = new ArrayList<>();
            method.visibleLocalVariableAnnotations = null; method.invisibleLocalVariableAnnotations = null;
            method.visibleTypeAnnotations = null; method.invisibleTypeAnnotations = null;
            method.maxStack = 2; method.maxLocals = Math.max(1, Type.getArgumentsAndReturnSizes(method.desc) >> 2);
        }
        return write(node);
    }

    /** Drops LineNumberTable, LocalVariableTable, SourceFile and other debug attributes. */
    public static byte[] stripDebug(byte[] bytes) {
        var writer = new ClassWriter(0);
        new ClassReader(bytes).accept(writer, ClassReader.SKIP_DEBUG);
        return writer.toByteArray();
    }

    /** Removes InnerClasses, NestMembers and PermittedSubclasses entries that name a removed class. */
    public static byte[] dropReferences(byte[] bytes, Set<String> removed) {
        var node = read(bytes, 0);
        node.innerClasses.removeIf(inner -> removed.contains(inner.name));
        if (node.nestMembers != null) node.nestMembers.removeIf(removed::contains);
        if (node.permittedSubclasses != null) node.permittedSubclasses.removeIf(removed::contains);
        return write(node);
    }

    /** Human-readable list of the instruction edits {@link #applyEdit} accepts for the method, in index order. */
    public static List<String> edits(byte[] bytes, String methodKey) {
        var method = method(read(bytes, ClassReader.SKIP_FRAMES), methodKey);
        if (method == null) return List.of();
        var result = new ArrayList<String>();
        var starts = blockStarts(method);
        for (int i = 1; i < starts.size(); i++) result.add("cut block " + i + " of " + starts.size() + " to a default return");
        for (int i = 0; i < method.tryCatchBlocks.size(); i++) result.add("drop exception handler " + (i + 1));
        var jumps = branches(method);
        for (int i = 0; i < jumps.size(); i++) result.add("branch " + (i + 1) + " never taken");
        for (int i = 0; i < jumps.size(); i++) result.add("branch " + (i + 1) + " always taken");
        var switches = switches(method);
        for (int i = 0; i < switches.size(); i++) result.add("switch " + (i + 1) + " to its default target");
        return List.copyOf(result);
    }

    /**
     * Applies edit {@code index} of {@link #edits} to the focus method, removes the code it made unreachable and
     * recomputes that method's frames. Returns null when the edit is not applicable or the result cannot be written.
     */
    public static byte[] applyEdit(byte[] bytes, String methodKey, int index, Map<String,byte[]> caseClasses) {
        var node = read(bytes, 0);
        var method = method(node, methodKey);
        if (method == null || index < 0) return null;
        var starts = blockStarts(method);
        var jumps = branches(method);
        var switches = switches(method);
        int cuts = starts.size() - 1, handlers = method.tryCatchBlocks.size();
        try {
            if (index < cuts) cut(method, starts, index + 1);
            else if ((index -= cuts) < handlers) method.tryCatchBlocks.remove(index);
            else if ((index -= handlers) < jumps.size()) never(method, jumps.get(index));
            else if ((index -= jumps.size()) < jumps.size()) always(method, jumps.get(index));
            else if ((index -= jumps.size()) < switches.size()) toDefault(method, switches.get(index));
            else return null;
            removeUnreachable(node.name, method);
            var rebuilt = recompute(node, method, caseClasses);
            node.methods.set(node.methods.indexOf(method), rebuilt);
            return write(node);
        } catch (AnalyzerException | RuntimeException invalid) {
            return null;
        }
    }

    private static ClassNode read(byte[] bytes, int flags) {
        var node = new ClassNode();
        new ClassReader(bytes).accept(node, flags);
        return node;
    }

    private static byte[] write(ClassNode node) {
        var writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return writer.toByteArray();
    }

    private static MethodNode method(ClassNode node, String key) {
        for (var method : node.methods) if ((method.name + method.desc).equals(key) && method.instructions.size() > 0) return method;
        return null;
    }

    private static boolean real(AbstractInsnNode insn) { return insn.getOpcode() >= 0; }

    /** First real instruction of each basic block, in code order. */
    static List<AbstractInsnNode> blockStarts(MethodNode method) {
        var targets = new HashSet<LabelNode>();
        for (var block : method.tryCatchBlocks) { targets.add(block.start); targets.add(block.end); targets.add(block.handler); }
        for (var insn : method.instructions) {
            if (insn instanceof JumpInsnNode jump) targets.add(jump.label);
            else if (insn instanceof TableSwitchInsnNode table) { targets.add(table.dflt); targets.addAll(table.labels); }
            else if (insn instanceof LookupSwitchInsnNode lookup) { targets.add(lookup.dflt); targets.addAll(lookup.labels); }
        }
        var result = new ArrayList<AbstractInsnNode>();
        boolean starts = true;
        for (var insn : method.instructions) {
            if (insn instanceof LabelNode label && targets.contains(label)) starts = true;
            if (!real(insn)) continue;
            if (starts) result.add(insn);
            starts = transfers(insn);
        }
        return result;
    }

    private static boolean transfers(AbstractInsnNode insn) {
        int opcode = insn.getOpcode();
        return insn instanceof JumpInsnNode || insn instanceof TableSwitchInsnNode || insn instanceof LookupSwitchInsnNode
            || opcode >= Opcodes.IRETURN && opcode <= Opcodes.RETURN || opcode == Opcodes.ATHROW || opcode == Opcodes.RET;
    }

    private static List<JumpInsnNode> branches(MethodNode method) {
        var result = new ArrayList<JumpInsnNode>();
        for (var insn : method.instructions)
            if (insn instanceof JumpInsnNode jump && jump.getOpcode() != Opcodes.GOTO && jump.getOpcode() != Opcodes.JSR) result.add(jump);
        return result;
    }

    private static List<AbstractInsnNode> switches(MethodNode method) {
        var result = new ArrayList<AbstractInsnNode>();
        for (var insn : method.instructions)
            if (insn instanceof TableSwitchInsnNode || insn instanceof LookupSwitchInsnNode) result.add(insn);
        return result;
    }

    /** Replaces block {@code block} (up to the next block start) with a return of the method's default value. */
    private static void cut(MethodNode method, List<AbstractInsnNode> starts, int block) {
        AbstractInsnNode from = starts.get(block), until = block + 1 < starts.size() ? starts.get(block + 1) : null;
        var doomed = new ArrayList<AbstractInsnNode>();
        for (var insn = from; insn != null && insn != until; insn = insn.getNext()) if (real(insn)) doomed.add(insn);
        var ret = defaultReturn(Type.getReturnType(method.desc));
        method.instructions.insertBefore(from, ret);
        doomed.forEach(method.instructions::remove);
    }

    private static InsnList defaultReturn(Type type) {
        var list = new InsnList();
        switch (type.getSort()) {
            case Type.VOID -> list.add(new InsnNode(Opcodes.RETURN));
            case Type.BOOLEAN, Type.BYTE, Type.CHAR, Type.SHORT, Type.INT -> { list.add(new InsnNode(Opcodes.ICONST_0)); list.add(new InsnNode(Opcodes.IRETURN)); }
            case Type.LONG -> { list.add(new InsnNode(Opcodes.LCONST_0)); list.add(new InsnNode(Opcodes.LRETURN)); }
            case Type.FLOAT -> { list.add(new InsnNode(Opcodes.FCONST_0)); list.add(new InsnNode(Opcodes.FRETURN)); }
            case Type.DOUBLE -> { list.add(new InsnNode(Opcodes.DCONST_0)); list.add(new InsnNode(Opcodes.DRETURN)); }
            default -> { list.add(new InsnNode(Opcodes.ACONST_NULL)); list.add(new InsnNode(Opcodes.ARETURN)); }
        }
        return list;
    }

    private static InsnNode popOperands(int opcode) {
        boolean two = opcode >= Opcodes.IF_ICMPEQ && opcode <= Opcodes.IF_ACMPNE;
        return new InsnNode(two ? Opcodes.POP2 : Opcodes.POP);
    }

    private static void never(MethodNode method, JumpInsnNode jump) {
        method.instructions.set(jump, popOperands(jump.getOpcode()));
    }

    private static void always(MethodNode method, JumpInsnNode jump) {
        var replacement = new InsnList();
        replacement.add(popOperands(jump.getOpcode()));
        replacement.add(new JumpInsnNode(Opcodes.GOTO, jump.label));
        method.instructions.insertBefore(jump, replacement);
        method.instructions.remove(jump);
    }

    private static void toDefault(MethodNode method, AbstractInsnNode table) {
        LabelNode target = table instanceof TableSwitchInsnNode t ? t.dflt : ((LookupSwitchInsnNode) table).dflt;
        var replacement = new InsnList();
        replacement.add(new InsnNode(Opcodes.POP));
        replacement.add(new JumpInsnNode(Opcodes.GOTO, target));
        method.instructions.insertBefore(table, replacement);
        method.instructions.remove(table);
    }

    /**
     * Removes instructions no path reaches and handlers whose protected range no longer holds code, until nothing
     * changes. Frames are dropped here; the caller recomputes them.
     */
    static void removeUnreachable(String owner, MethodNode method) throws AnalyzerException {
        for (var insn : method.instructions.toArray()) if (insn instanceof FrameNode) method.instructions.remove(insn);
        method.maxStack += 4; // headroom for inserted constants; recomputed when written
        for (int round = 0; round < 16; round++) {
            var frames = new Analyzer<>(new BasicInterpreter()).analyze(owner, method);
            var insns = method.instructions.toArray();
            boolean changed = false;
            for (int i = 0; i < insns.length; i++)
                if (frames[i] == null && real(insns[i])) { method.instructions.remove(insns[i]); changed = true; }
            changed |= method.tryCatchBlocks.removeIf(block -> !covers(block));
            if (!changed) return;
        }
        throw new AnalyzerException(null, "dead code removal did not converge");
    }

    private static boolean covers(TryCatchBlockNode block) {
        for (AbstractInsnNode insn = block.start; insn != null && insn != block.end; insn = insn.getNext()) if (real(insn)) return true;
        return false;
    }

    /** Writes the class with only this method and frame computation, then reads the method back with its new frames. */
    private static MethodNode recompute(ClassNode node, MethodNode method, Map<String,byte[]> caseClasses) {
        var shell = new ClassNode();
        shell.version = node.version; shell.access = node.access; shell.name = node.name; shell.signature = node.signature;
        shell.superName = node.superName; shell.interfaces = node.interfaces;
        shell.methods.add(method);
        int flags = (node.version & 0xFFFF) >= Opcodes.V1_6 ? ClassWriter.COMPUTE_FRAMES : ClassWriter.COMPUTE_MAXS;
        var writer = new ClassWriter(flags) {
            @Override protected String getCommonSuperClass(String first, String second) { return commonSuperClass(first, second, caseClasses); }
        };
        shell.accept(writer);
        var reread = read(writer.toByteArray(), 0);
        return reread.methods.getFirst();
    }

    private record Header(String superName, List<String> interfaces, boolean isInterface) {}

    static String commonSuperClass(String first, String second, Map<String,byte[]> caseClasses) {
        if (assignable(first, second, caseClasses)) return first;
        if (assignable(second, first, caseClasses)) return second;
        Header a = header(first, caseClasses), b = header(second, caseClasses);
        if (a == null || b == null || a.isInterface || b.isInterface) return "java/lang/Object";
        for (String type = a.superName; type != null; ) {
            if (assignable(type, second, caseClasses)) return type;
            Header next = header(type, caseClasses);
            type = next == null ? null : next.superName;
        }
        return "java/lang/Object";
    }

    /** True when {@code target} is {@code type} or one of its supertypes, following known headers only. */
    private static boolean assignable(String target, String type, Map<String,byte[]> caseClasses) {
        var pending = new ArrayDeque<String>(List.of(type)); var seen = new HashSet<String>();
        while (!pending.isEmpty()) {
            String current = pending.pop();
            if (current.equals(target)) return true;
            if (!seen.add(current) || seen.size() > 1000) continue;
            Header header = header(current, caseClasses);
            if (header == null) continue;
            if (header.superName != null) pending.push(header.superName);
            header.interfaces.forEach(pending::push);
        }
        return false;
    }

    private static Header header(String name, Map<String,byte[]> caseClasses) {
        byte[] bytes = caseClasses.get(name);
        if (bytes != null) {
            try {
                var reader = new ClassReader(bytes);
                return new Header(reader.getSuperName(), List.of(reader.getInterfaces()), (reader.getAccess() & Opcodes.ACC_INTERFACE) != 0);
            } catch (RuntimeException malformed) { return null; }
        }
        try {
            // Platform loader: JDK classes only, loaded without initialization; it cannot see target or ClassRevive classes.
            Class<?> type = Class.forName(name.replace('/', '.'), false, ClassLoader.getPlatformClassLoader());
            var interfaces = new ArrayList<String>();
            for (var item : type.getInterfaces()) interfaces.add(Type.getInternalName(item));
            return new Header(type.getSuperclass() == null ? null : Type.getInternalName(type.getSuperclass()), interfaces, type.isInterface());
        } catch (ClassNotFoundException | LinkageError unknown) {
            return null;
        }
    }
}
