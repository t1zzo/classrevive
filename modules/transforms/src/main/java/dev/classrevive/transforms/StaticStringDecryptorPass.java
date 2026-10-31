package dev.classrevive.transforms;

import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import java.util.*;

/** Folds only string-literal calls that the bounded static bytecode interpreter can prove. */
public final class StaticStringDecryptorPass implements BytecodePass {
    private static final int MAX_CLASS_BYTES = 16 * 1024 * 1024;
    private static final int MAX_CHANGES = 1_000_000;
    public String id() { return "string-decryptor-v1"; }

    @Override public TransformResult apply(byte[] original, int maxChanges) {
        Objects.requireNonNull(original);
        if (original.length > MAX_CLASS_BYTES || maxChanges < 1 || maxChanges > MAX_CHANGES)
            throw new IllegalArgumentException("Require class <=16 MiB and change budget 1..1000000");
        var reader = new ClassReader(original); var type = new ClassNode(); reader.accept(type, 0);
        if (IntegerConstantPass.hasOpaqueAttributes(type) || type.superName != null && !type.superName.equals("java/lang/Object")
            || type.interfaces != null && !type.interfaces.isEmpty()
            || type.methods.stream().anyMatch(method -> method.name.equals("<clinit>")))
            return new TransformResult(original, List.of(), false);

        var decoders = new HashMap<String,MethodNode>();
        for (var method : type.methods) if (method.desc.equals("(Ljava/lang/String;)Ljava/lang/String;"))
            decoders.put(key(method.name, method.desc), method);
        if (decoders.isEmpty()) return new TransformResult(original, List.of(), false);

        var evaluator = new StringDecoderEvaluator(); var changes = new ArrayList<TransformResult.Change>();
        var grown = Collections.newSetFromMap(new IdentityHashMap<MethodNode, Boolean>());
        outer: for (var caller : type.methods) {
            var instructions = caller.instructions.toArray();
            for (int index = 0; index < instructions.length; index++) {
                if (!(instructions[index] instanceof MethodInsnNode call) || call.getOpcode() != Opcodes.INVOKESTATIC
                    || call.itf || !call.owner.equals(type.name) || !call.desc.equals("(Ljava/lang/String;)Ljava/lang/String;")) continue;
                MethodNode decoder = decoders.get(key(call.name, call.desc));
                if (decoder == null) continue;
                AbstractInsnNode argument = previousReal(call);
                if (!(argument instanceof LdcInsnNode literal) || !(literal.cst instanceof String encrypted)
                    || IntegerConstantPass.hasInstructionAnnotations(argument)
                    || IntegerConstantPass.hasInstructionAnnotations(call)) continue;
                Optional<StringDecoderEvaluator.Evaluation> decoded = evaluator.evaluate(decoder, encrypted, type.name, decoders);
                if (decoded.isEmpty()) continue;
                var value = decoded.get();
                if (value.freshObject()) {
                    // NEW/DUP/LDC peaks two slots above the original LDC; replacements never nest, so +2 once per method.
                    if (grown.add(caller)) {
                        if (caller.maxStack > 0xFFFF - 2) { grown.remove(caller); continue; }
                        caller.maxStack += 2;
                    }
                    var replacement = new InsnList();
                    replacement.add(new TypeInsnNode(Opcodes.NEW, "java/lang/String"));
                    replacement.add(new InsnNode(Opcodes.DUP));
                    replacement.add(new LdcInsnNode(value.value()));
                    replacement.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/String", "<init>", "(Ljava/lang/String;)V", false));
                    caller.instructions.insertBefore(literal, replacement);
                    caller.instructions.remove(literal);
                } else literal.cst = value.value();
                caller.instructions.remove(call);
                changes.add(new TransformResult.Change(id(), type.name, caller.name, caller.desc, index,
                    Opcodes.INVOKESTATIC, encrypted.length(), decoder.instructions.size(), value.value().length()));
                if (changes.size() == maxChanges) break outer;
            }
        }
        if (changes.isEmpty()) return new TransformResult(original, changes, false);
        var writer = new ClassWriter(reader, 0); type.accept(writer); byte[] transformed = writer.toByteArray();
        if (transformed.length > MAX_CLASS_BYTES) return new TransformResult(original, List.of(), false);
        return new TransformResult(transformed, changes, changes.size() == maxChanges);
    }

    private static String key(String name, String descriptor) { return name + '\u0000' + descriptor; }
    private static AbstractInsnNode previousReal(AbstractInsnNode node) {
        var current = node.getPrevious();
        while (current instanceof LineNumberNode) current = current.getPrevious();
        return current;
    }
}
