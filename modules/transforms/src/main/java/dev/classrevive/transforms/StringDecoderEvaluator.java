package dev.classrevive.transforms;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Interprets a deliberately small, pure subset of JVM bytecode without defining or invoking target classes. */
final class StringDecoderEvaluator implements Opcodes {
    record Evaluation(String value, boolean freshObject) {}
    static final int MAX_INPUT_CHARS = 4096;
    private static final int MAX_BUILDER_CAPACITY = 8192;
    private static final int MAX_METHOD_INSTRUCTIONS = 5000;
    private static final int MAX_STEPS_PER_CANDIDATE = 100_000;
    private static final int MAX_HELPER_DEPTH = 8;
    private static final int MAX_STACK = 128;
    private static final int MAX_LOCALS = 128;
    private static final int MAX_ALLOCATION_BYTES_PER_CALL = 64 * 1024;
    private static final int MAX_ALLOCATION_BYTES_PER_CLASS = 4 * 1024 * 1024;
    private static final int ARRAY_OBJECT_OVERHEAD_BYTES = 16;

    private long remainingClassSteps = 1_000_000;
    private int remainingClassAllocationBytes = MAX_ALLOCATION_BYTES_PER_CLASS;

    Optional<Evaluation> evaluate(MethodNode method, String input) {
        return evaluate(method, input, "", Map.of());
    }

    Optional<Evaluation> evaluate(MethodNode method, String input, String className, Map<String,MethodNode> methods) {
        return evaluate(method, input, className, methods, new EvaluationContext());
    }

    private Optional<Evaluation> evaluate(MethodNode method, String input, String className, Map<String,MethodNode> methods,
                                          EvaluationContext context) {
        if (context.depth >= MAX_HELPER_DEPTH || !context.active.add(method)) return Optional.empty();
        context.depth++;
        try {
            return evaluateBody(method, input, className, methods, context);
        } finally {
            context.depth--;
            context.active.remove(method);
        }
    }

    private Optional<Evaluation> evaluateBody(MethodNode method, String input, String className, Map<String,MethodNode> methods,
                                               EvaluationContext context) {
        if (input.length() > MAX_INPUT_CHARS || remainingClassSteps <= 0 || !eligible(method, className, methods)) return Optional.empty();
        var instructions = method.instructions.toArray();
        if (instructions.length == 0 || instructions.length > MAX_METHOD_INSTRUCTIONS || method.maxStack > MAX_STACK
            || method.maxLocals < 1 || method.maxLocals > MAX_LOCALS) return Optional.empty();
        var indices = new IdentityHashMap<AbstractInsnNode,Integer>();
        for (int i = 0; i < instructions.length; i++) indices.put(instructions[i], i);
        Object[] locals = new Object[method.maxLocals]; locals[0] = input;
        var stack = new ArrayList<Object>(Math.min(method.maxStack, 16));
        var allocationBudget = context.allocationBudget;
        int pc = 0;
        try {
            while (pc >= 0 && pc < instructions.length) {
                if (--context.remainingCandidateSteps < 0 || --remainingClassSteps < 0) throw Refusal.INSTANCE;
                AbstractInsnNode instruction = instructions[pc];
                int next = pc + 1;
                if (instruction instanceof LabelNode || instruction instanceof LineNumberNode || instruction instanceof FrameNode) {
                    // Debug and frame nodes do not affect the interpreted state.
                } else if (instruction instanceof InsnNode node) {
                    int opcode = node.getOpcode();
                    if (opcode >= ICONST_M1 && opcode <= ICONST_5) stack.add(opcode - ICONST_0);
                    else switch (opcode) {
                        case NOP -> { }
                        case DUP -> stack.add(peek(stack));
                        case POP -> pop(stack);
                        case ARRAYLENGTH -> stack.add(arrayLength(pop(stack)));
                        case CALOAD -> { int index = popInt(stack); char[] array = charArray(pop(stack)); stack.add((int)array[index]); }
                        case CASTORE -> { int value = popInt(stack), index = popInt(stack); charArray(pop(stack))[index] = (char)value; }
                        case BALOAD -> { int index = popInt(stack); byte[] array = byteArray(pop(stack)); stack.add((int)array[index]); }
                        case BASTORE -> { int value = popInt(stack), index = popInt(stack); byteArray(pop(stack))[index] = (byte)value; }
                        case IADD, ISUB, IMUL, IDIV, IREM, ISHL, ISHR, IUSHR, IAND, IOR, IXOR -> {
                            int right = popInt(stack), left = popInt(stack);
                            stack.add(switch (opcode) {
                                case IADD -> left + right; case ISUB -> left - right; case IMUL -> left * right;
                                case IDIV -> left / right; case IREM -> left % right; case ISHL -> left << right;
                                case ISHR -> left >> right; case IUSHR -> left >>> right; case IAND -> left & right;
                                case IOR -> left | right; default -> left ^ right;
                            });
                        }
                        case INEG -> stack.add(-popInt(stack));
                        case I2B -> stack.add((int)(byte)popInt(stack));
                        case I2C -> stack.add((int)(char)popInt(stack));
                        case I2S -> stack.add((int)(short)popInt(stack));
                        case ARETURN -> {
                            Object value = pop(stack);
                            if (value instanceof String text && text.length() <= MAX_INPUT_CHARS) return Optional.of(new Evaluation(text, false));
                            if (value instanceof StringObject object && object.value != null && object.value.length() <= MAX_INPUT_CHARS)
                                return Optional.of(new Evaluation(object.value, true));
                            throw Refusal.INSTANCE;
                        }
                        default -> throw Refusal.INSTANCE;
                    }
                } else if (instruction instanceof IntInsnNode node) {
                    if (node.getOpcode() == BIPUSH || node.getOpcode() == SIPUSH) stack.add(node.operand);
                    else if (node.getOpcode() == NEWARRAY && (node.operand == T_CHAR || node.operand == T_BYTE)) {
                        int length = popInt(stack);
                        if (length < 0 || length > MAX_INPUT_CHARS) throw Refusal.INSTANCE;
                        reserve(allocationBudget, ARRAY_OBJECT_OVERHEAD_BYTES
                            + length * (node.operand == T_CHAR ? Character.BYTES : Byte.BYTES));
                        stack.add(node.operand == T_CHAR ? new char[length] : new byte[length]);
                    } else throw Refusal.INSTANCE;
                } else if (instruction instanceof LdcInsnNode node) {
                    if (node.cst instanceof String || node.cst instanceof Integer) stack.add(node.cst);
                    else throw Refusal.INSTANCE;
                } else if (instruction instanceof VarInsnNode node) {
                    if (node.var < 0 || node.var >= locals.length) throw Refusal.INSTANCE;
                    if (node.getOpcode() == ILOAD || node.getOpcode() == ALOAD) {
                        Object value = locals[node.var];
                        if (node.getOpcode() == ILOAD && !(value instanceof Integer)) throw Refusal.INSTANCE;
                        if (value == null) throw Refusal.INSTANCE;
                        stack.add(value);
                    } else if (node.getOpcode() == ISTORE || node.getOpcode() == ASTORE) {
                        Object value = pop(stack);
                        if (node.getOpcode() == ISTORE && !(value instanceof Integer)) throw Refusal.INSTANCE;
                        if (node.getOpcode() == ASTORE && !(value instanceof String || value instanceof char[] || value instanceof byte[]
                            || value instanceof StringObject || value instanceof BuilderObject || value instanceof CharsetValue
                            || value instanceof Base64DecoderValue)) throw Refusal.INSTANCE;
                        locals[node.var] = value;
                    } else throw Refusal.INSTANCE;
                } else if (instruction instanceof IincInsnNode node) {
                    if (node.var < 0 || node.var >= locals.length || !(locals[node.var] instanceof Integer value)) throw Refusal.INSTANCE;
                    locals[node.var] = value + node.incr;
                } else if (instruction instanceof JumpInsnNode node) {
                    boolean branch = switch (node.getOpcode()) {
                        case GOTO -> true;
                        case IFEQ -> popInt(stack) == 0; case IFNE -> popInt(stack) != 0;
                        case IFLT -> popInt(stack) < 0; case IFGE -> popInt(stack) >= 0;
                        case IFGT -> popInt(stack) > 0; case IFLE -> popInt(stack) <= 0;
                        case IF_ICMPEQ -> compare(stack) == 0; case IF_ICMPNE -> compare(stack) != 0;
                        case IF_ICMPLT -> compare(stack) < 0; case IF_ICMPGE -> compare(stack) >= 0;
                        case IF_ICMPGT -> compare(stack) > 0; case IF_ICMPLE -> compare(stack) <= 0;
                        default -> throw Refusal.INSTANCE;
                    };
                    if (branch) {
                        Integer target = indices.get(node.label);
                        if (target == null) throw Refusal.INSTANCE;
                        next = target;
                    }
                } else if (instruction instanceof TypeInsnNode node) {
                    if (node.getOpcode() != NEW || !(node.desc.equals("java/lang/String") || node.desc.equals("java/lang/StringBuilder")))
                        throw Refusal.INSTANCE;
                    reserve(allocationBudget, 32);
                    stack.add(node.desc.equals("java/lang/String") ? new StringObject() : new BuilderObject());
                } else if (instruction instanceof FieldInsnNode node) {
                    if (!allowedField(node)) throw Refusal.INSTANCE;
                    stack.add(new CharsetValue(standardCharset(node.name)));
                } else if (instruction instanceof MethodInsnNode node) {
                    invoke(stack, node, allocationBudget, className, methods, context);
                } else throw Refusal.INSTANCE;
                if (stack.size() > MAX_STACK) throw Refusal.INSTANCE;
                pc = next;
            }
        } catch (Refusal | ArithmeticException | IndexOutOfBoundsException refused) {
            return Optional.empty();
        }
        return Optional.empty();
    }

    private static boolean eligible(MethodNode method, String className, Map<String,MethodNode> methods) {
        int forbidden = ACC_ABSTRACT | ACC_NATIVE | ACC_SYNCHRONIZED;
        if ((method.access & ACC_STATIC) == 0 || (method.access & forbidden) != 0
            || !method.desc.equals("(Ljava/lang/String;)Ljava/lang/String;") || !method.tryCatchBlocks.isEmpty()) return false;
        for (var instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call) {
                if (!allowedCall(call, className, methods)) return false;
            } else if (instruction instanceof FieldInsnNode field && !allowedField(field)
                || instruction instanceof InvokeDynamicInsnNode
                || instruction instanceof MultiANewArrayInsnNode || instruction instanceof TableSwitchInsnNode
                || instruction instanceof LookupSwitchInsnNode || instruction instanceof LdcInsnNode ldc
                    && !(ldc.cst instanceof String || ldc.cst instanceof Integer)
                || instruction instanceof TypeInsnNode type
                    && !(type.getOpcode() == NEW && (type.desc.equals("java/lang/String") || type.desc.equals("java/lang/StringBuilder")))
                || instruction instanceof IntInsnNode operand
                    && !((operand.getOpcode() == BIPUSH || operand.getOpcode() == SIPUSH)
                        || (operand.getOpcode() == NEWARRAY && (operand.operand == T_CHAR || operand.operand == T_BYTE)))) return false;
            else if (instruction instanceof VarInsnNode variable
                && variable.getOpcode() != ILOAD && variable.getOpcode() != ISTORE
                && variable.getOpcode() != ALOAD && variable.getOpcode() != ASTORE) return false;
            else if (instruction instanceof IincInsnNode) { /* supported */ }
            else if (instruction instanceof JumpInsnNode jump && !allowedJump(jump.getOpcode())) return false;
            else if (instruction instanceof InsnNode simple && !allowedSimple(simple.getOpcode())) return false;
            else if (!(instruction instanceof MethodInsnNode || instruction instanceof FieldInsnNode
                || instruction instanceof InvokeDynamicInsnNode || instruction instanceof MultiANewArrayInsnNode
                || instruction instanceof TableSwitchInsnNode || instruction instanceof LookupSwitchInsnNode
                || instruction instanceof LdcInsnNode || instruction instanceof TypeInsnNode
                || instruction instanceof IntInsnNode || instruction instanceof VarInsnNode
                || instruction instanceof IincInsnNode || instruction instanceof JumpInsnNode
                || instruction instanceof InsnNode || instruction instanceof LabelNode
                || instruction instanceof LineNumberNode || instruction instanceof FrameNode)) return false;
        }
        return true;
    }

    private static boolean allowedCall(MethodInsnNode call, String className, Map<String,MethodNode> methods) {
        if (call.itf) return false;
        if (call.getOpcode() == INVOKESTATIC && call.owner.equals(className)
            && call.desc.equals("(Ljava/lang/String;)Ljava/lang/String;"))
            return methods.containsKey(methodKey(call.name, call.desc));
        if (call.owner.equals("java/lang/Integer"))
            return call.getOpcode() == INVOKESTATIC && switch (call.name + call.desc) {
                case "rotateLeft(II)I", "rotateRight(II)I", "reverse(I)I", "reverseBytes(I)I" -> true;
                default -> false;
            };
        if (call.owner.equals("java/util/Arrays"))
            return call.getOpcode() == INVOKESTATIC
                && (call.name.equals("copyOf") && call.desc.equals("([BI)[B")
                    || call.name.equals("copyOfRange") && call.desc.equals("([BII)[B"));
        if (call.owner.equals("java/util/Base64"))
            return call.getOpcode() == INVOKESTATIC && call.desc.equals("()Ljava/util/Base64$Decoder;")
                && switch (call.name) {
                    case "getDecoder", "getUrlDecoder", "getMimeDecoder" -> true;
                    default -> false;
                };
        if (call.owner.equals("java/util/Base64$Decoder"))
            return call.getOpcode() == INVOKEVIRTUAL && call.name.equals("decode")
                && call.desc.equals("(Ljava/lang/String;)[B");
        if (call.owner.equals("java/lang/StringBuilder"))
            return call.getOpcode() == INVOKESPECIAL && call.name.equals("<init>")
                    && (call.desc.equals("()V") || call.desc.equals("(I)V") || call.desc.equals("(Ljava/lang/String;)V"))
                || call.getOpcode() == INVOKEVIRTUAL && (call.name.equals("length") && call.desc.equals("()I")
                    || call.name.equals("charAt") && call.desc.equals("(I)C")
                    || call.name.equals("setCharAt") && call.desc.equals("(IC)V")
                    || call.name.equals("setLength") && call.desc.equals("(I)V")
                    || call.name.equals("deleteCharAt") && call.desc.equals("(I)Ljava/lang/StringBuilder;")
                    || call.name.equals("insert") && call.desc.equals("(IC)Ljava/lang/StringBuilder;")
                    || call.name.equals("reverse") && call.desc.equals("()Ljava/lang/StringBuilder;")
                    || call.name.equals("append") && (call.desc.equals("(C)Ljava/lang/StringBuilder;")
                        || call.desc.equals("(I)Ljava/lang/StringBuilder;")
                        || call.desc.equals("(Ljava/lang/String;)Ljava/lang/StringBuilder;"))
                    || call.name.equals("toString") && call.desc.equals("()Ljava/lang/String;"));
        if (!call.owner.equals("java/lang/String")) return false;
        if (call.getOpcode() == INVOKESTATIC)
            return call.name.equals("valueOf") && call.desc.equals("([C)Ljava/lang/String;");
        return call.getOpcode() == INVOKEVIRTUAL && (call.name.equals("length") && call.desc.equals("()I")
            || call.name.equals("charAt") && call.desc.equals("(I)C")
            || call.name.equals("substring") && (call.desc.equals("(I)Ljava/lang/String;")
                || call.desc.equals("(II)Ljava/lang/String;"))
            || call.name.equals("replace") && call.desc.equals("(CC)Ljava/lang/String;")
            || call.name.equals("toCharArray") && call.desc.equals("()[C")
            || call.name.equals("getBytes") && call.desc.equals("(Ljava/nio/charset/Charset;)[B"))
            || call.getOpcode() == INVOKESPECIAL && call.name.equals("<init>")
                && (call.desc.equals("([C)V") || call.desc.equals("([BLjava/nio/charset/Charset;)V"));
    }

    private static boolean allowedField(FieldInsnNode field) {
        return field.getOpcode() == GETSTATIC && field.owner.equals("java/nio/charset/StandardCharsets")
            && field.desc.equals("Ljava/nio/charset/Charset;") && switch (field.name) {
                case "UTF_8", "US_ASCII", "ISO_8859_1", "UTF_16", "UTF_16BE", "UTF_16LE" -> true;
                default -> false;
            };
    }

    private static Charset standardCharset(String name) {
        return switch (name) {
            case "UTF_8" -> StandardCharsets.UTF_8;
            case "US_ASCII" -> StandardCharsets.US_ASCII;
            case "ISO_8859_1" -> StandardCharsets.ISO_8859_1;
            case "UTF_16" -> StandardCharsets.UTF_16;
            case "UTF_16BE" -> StandardCharsets.UTF_16BE;
            case "UTF_16LE" -> StandardCharsets.UTF_16LE;
            default -> throw Refusal.INSTANCE;
        };
    }

    private static boolean allowedJump(int opcode) {
        return opcode == GOTO || opcode == IFEQ || opcode == IFNE || opcode == IFLT || opcode == IFGE
            || opcode == IFGT || opcode == IFLE || opcode == IF_ICMPEQ || opcode == IF_ICMPNE
            || opcode == IF_ICMPLT || opcode == IF_ICMPGE || opcode == IF_ICMPGT || opcode == IF_ICMPLE;
    }

    private static boolean allowedSimple(int opcode) {
        return opcode >= ICONST_M1 && opcode <= ICONST_5 || switch (opcode) {
            case NOP, DUP, POP, ARRAYLENGTH, CALOAD, CASTORE, BALOAD, BASTORE, IADD, ISUB, IMUL, IDIV, IREM,
                ISHL, ISHR, IUSHR, IAND, IOR, IXOR, INEG, I2B, I2C, I2S, ARETURN -> true;
            default -> false;
        };
    }

    private void invoke(ArrayList<Object> stack, MethodInsnNode call, AllocationBudget allocationBudget,
                        String className, Map<String,MethodNode> methods, EvaluationContext context) {
        if (call.getOpcode() == INVOKESTATIC && call.owner.equals(className)
            && call.desc.equals("(Ljava/lang/String;)Ljava/lang/String;") && !call.itf) {
            MethodNode helper = methods.get(methodKey(call.name, call.desc));
            String argument = string(pop(stack));
            if (helper == null) throw Refusal.INSTANCE;
            Evaluation result = evaluate(helper, argument, className, methods, context).orElseThrow(() -> Refusal.INSTANCE);
            stack.add(result.freshObject() ? new StringObject(result.value()) : result.value());
            return;
        }
        if (call.getOpcode() == INVOKESTATIC && call.owner.equals("java/lang/Integer") && !call.itf) {
            switch (call.name + call.desc) {
                case "rotateLeft(II)I" -> {
                    int distance = popInt(stack), value = popInt(stack);
                    stack.add(Integer.rotateLeft(value, distance));
                }
                case "rotateRight(II)I" -> {
                    int distance = popInt(stack), value = popInt(stack);
                    stack.add(Integer.rotateRight(value, distance));
                }
                case "reverse(I)I" -> stack.add(Integer.reverse(popInt(stack)));
                case "reverseBytes(I)I" -> stack.add(Integer.reverseBytes(popInt(stack)));
                default -> throw Refusal.INSTANCE;
            }
            return;
        }
        if (call.getOpcode() == INVOKESTATIC && call.owner.equals("java/util/Arrays") && !call.itf) {
            if (call.name.equals("copyOf") && call.desc.equals("([BI)[B")) {
                int length = popInt(stack);
                byte[] original = byteArray(pop(stack));
                if (length < 0 || length > MAX_INPUT_CHARS) throw Refusal.INSTANCE;
                reserve(allocationBudget, ARRAY_OBJECT_OVERHEAD_BYTES + length);
                stack.add(Arrays.copyOf(original, length));
                return;
            }
            if (call.name.equals("copyOfRange") && call.desc.equals("([BII)[B")) {
                int to = popInt(stack), from = popInt(stack);
                byte[] original = byteArray(pop(stack));
                long length = (long)to - from;
                if (from < 0 || from > original.length || length < 0 || length > MAX_INPUT_CHARS) throw Refusal.INSTANCE;
                reserve(allocationBudget, ARRAY_OBJECT_OVERHEAD_BYTES + (int)length);
                stack.add(Arrays.copyOfRange(original, from, to));
                return;
            }
            throw Refusal.INSTANCE;
        }
        if (call.getOpcode() == INVOKESTATIC && call.owner.equals("java/lang/String")
            && call.name.equals("valueOf") && call.desc.equals("([C)Ljava/lang/String;") && !call.itf) {
            char[] characters = charArray(pop(stack));
            reserve(allocationBudget, 32 + characters.length * Character.BYTES);
            stack.add(new StringObject(new String(characters)));
            return;
        }
        if (call.getOpcode() == INVOKESPECIAL && call.owner.equals("java/lang/StringBuilder")
            && call.name.equals("<init>") && !call.itf) {
            String initial = call.desc.equals("(Ljava/lang/String;)V") ? string(pop(stack)) : "";
            int capacity = switch (call.desc) {
                case "(I)V" -> popInt(stack);
                case "(Ljava/lang/String;)V" -> initial.length() + 16;
                case "()V" -> 16;
                default -> throw Refusal.INSTANCE;
            };
            Object receiver = pop(stack);
            if (!(receiver instanceof BuilderObject builder) || builder.value != null
                || capacity < 0 || capacity > MAX_BUILDER_CAPACITY) throw Refusal.INSTANCE;
            reserve(allocationBudget, 32 + capacity * Character.BYTES);
            builder.value = call.desc.equals("(Ljava/lang/String;)V") ? new StringBuilder(initial) : new StringBuilder(capacity);
            builder.capacity = capacity;
            return;
        }
        if (call.getOpcode() == INVOKEVIRTUAL && call.owner.equals("java/lang/StringBuilder") && !call.itf) {
            switch (call.name + call.desc) {
                case "length()I" -> stack.add(builder(pop(stack)).value.length());
                case "charAt(I)C" -> {
                    int index = popInt(stack);
                    stack.add((int)builder(pop(stack)).value.charAt(index));
                }
                case "setCharAt(IC)V" -> {
                    int character = popInt(stack), index = popInt(stack);
                    builder(pop(stack)).value.setCharAt(index, (char)character);
                }
                case "setLength(I)V" -> {
                    int length = popInt(stack);
                    setBuilderLength(builder(pop(stack)), length, allocationBudget);
                }
                case "deleteCharAt(I)Ljava/lang/StringBuilder;" -> {
                    int index = popInt(stack);
                    BuilderObject builder = builder(pop(stack));
                    builder.value.deleteCharAt(index);
                    stack.add(builder);
                }
                case "insert(IC)Ljava/lang/StringBuilder;" -> {
                    int character = popInt(stack), index = popInt(stack);
                    BuilderObject builder = builder(pop(stack));
                    insert(builder, index, (char)character, allocationBudget);
                    stack.add(builder);
                }
                case "reverse()Ljava/lang/StringBuilder;" -> {
                    BuilderObject builder = builder(pop(stack));
                    builder.value.reverse();
                    stack.add(builder);
                }
                case "append(C)Ljava/lang/StringBuilder;" -> {
                    int character = popInt(stack); BuilderObject builder = builder(pop(stack));
                    append(builder, Character.toString((char)character), allocationBudget); stack.add(builder);
                }
                case "append(I)Ljava/lang/StringBuilder;" -> {
                    int number = popInt(stack); BuilderObject builder = builder(pop(stack));
                    append(builder, Integer.toString(number), allocationBudget); stack.add(builder);
                }
                case "append(Ljava/lang/String;)Ljava/lang/StringBuilder;" -> {
                    String text = string(pop(stack)); BuilderObject builder = builder(pop(stack));
                    append(builder, text, allocationBudget); stack.add(builder);
                }
                case "toString()Ljava/lang/String;" -> {
                    BuilderObject builder = builder(pop(stack));
                    String text = builder.value.toString();
                    reserve(allocationBudget, 32 + text.length() * Character.BYTES);
                    stack.add(new StringObject(text));
                }
                default -> throw Refusal.INSTANCE;
            }
            return;
        }
        if (call.getOpcode() == INVOKESTATIC && call.owner.equals("java/util/Base64")
            && call.desc.equals("()Ljava/util/Base64$Decoder;") && !call.itf) {
            stack.add(switch (call.name) {
                case "getDecoder" -> Base64DecoderValue.BASIC;
                case "getUrlDecoder" -> Base64DecoderValue.URL_SAFE;
                case "getMimeDecoder" -> Base64DecoderValue.MIME;
                default -> throw Refusal.INSTANCE;
            });
            return;
        }
        if (call.getOpcode() == INVOKEVIRTUAL && call.owner.equals("java/util/Base64$Decoder")
            && call.name.equals("decode") && call.desc.equals("(Ljava/lang/String;)[B") && !call.itf) {
            String encoded = string(pop(stack)); Object decoder = pop(stack);
            if (!(decoder instanceof Base64DecoderValue variant) || encoded.length() > MAX_INPUT_CHARS) throw Refusal.INSTANCE;
            reserve(allocationBudget, ARRAY_OBJECT_OVERHEAD_BYTES + encoded.length() * 3 / 4);
            byte[] decoded;
            try { decoded = variant.decoder().decode(encoded); }
            catch (IllegalArgumentException malformed) { throw Refusal.INSTANCE; }
            if (decoded.length > MAX_INPUT_CHARS) throw Refusal.INSTANCE;
            stack.add(decoded); return;
        }
        if (call.getOpcode() == INVOKESPECIAL && call.owner.equals("java/lang/String")
            && call.name.equals("<init>") && !call.itf) {
            Object receiver;
            if (call.desc.equals("([C)V")) {
                char[] characters = charArray(pop(stack)); receiver = pop(stack);
                if (!(receiver instanceof StringObject object) || object.value != null) throw Refusal.INSTANCE;
                reserve(allocationBudget, characters.length * Character.BYTES);
                object.value = new String(characters); return;
            }
            if (call.desc.equals("([BLjava/nio/charset/Charset;)V")) {
                Charset charset = charset(pop(stack)); byte[] bytes = byteArray(pop(stack)); receiver = pop(stack);
                if (!(receiver instanceof StringObject object) || object.value != null) throw Refusal.INSTANCE;
                reserve(allocationBudget, bytes.length * Character.BYTES);
                object.value = new String(bytes, charset);
                if (object.value.length() > MAX_INPUT_CHARS) throw Refusal.INSTANCE;
                return;
            }
            throw Refusal.INSTANCE;
        }
        if (call.getOpcode() != INVOKEVIRTUAL || call.itf || !call.owner.equals("java/lang/String")) throw Refusal.INSTANCE;
        switch (call.name + call.desc) {
            case "length()I" -> stack.add(string(pop(stack)).length());
            case "charAt(I)C" -> { int index = popInt(stack); stack.add((int)string(pop(stack)).charAt(index)); }
            case "substring(I)Ljava/lang/String;" -> {
                int begin = popInt(stack); Object receiver = pop(stack); String source = string(receiver);
                if (begin < 0 || begin > source.length()) throw Refusal.INSTANCE;
                int length = source.length() - begin;
                if (begin == 0) stack.add(receiver);
                else if (length == 0) stack.add("");
                else {
                    reserve(allocationBudget, 32 + length * Character.BYTES);
                    stack.add(new StringObject(source.substring(begin)));
                }
            }
            case "substring(II)Ljava/lang/String;" -> {
                int end = popInt(stack), begin = popInt(stack); Object receiver = pop(stack); String source = string(receiver);
                if (begin < 0 || end > source.length() || begin > end) throw Refusal.INSTANCE;
                int length = end - begin;
                if (begin == 0 && end == source.length()) stack.add(receiver);
                else if (length == 0) stack.add("");
                else {
                    reserve(allocationBudget, 32 + length * Character.BYTES);
                    stack.add(new StringObject(source.substring(begin, end)));
                }
            }
            case "replace(CC)Ljava/lang/String;" -> {
                char replacement = (char)popInt(stack), target = (char)popInt(stack);
                Object receiver = pop(stack); String source = string(receiver);
                if (target == replacement || source.indexOf(target) < 0) stack.add(receiver);
                else {
                    reserve(allocationBudget, 32 + source.length() * Character.BYTES);
                    stack.add(new StringObject(source.replace(target, replacement)));
                }
            }
            case "toCharArray()[C" -> {
                String text = string(pop(stack)); reserve(allocationBudget, ARRAY_OBJECT_OVERHEAD_BYTES + text.length() * Character.BYTES);
                stack.add(text.toCharArray());
            }
            case "getBytes(Ljava/nio/charset/Charset;)[B" -> {
                Charset charset = charset(pop(stack)); String text = string(pop(stack));
                int byteLength = encodedLength(text, charset);
                if (byteLength > MAX_INPUT_CHARS) throw Refusal.INSTANCE;
                reserve(allocationBudget, ARRAY_OBJECT_OVERHEAD_BYTES + byteLength);
                byte[] bytes = text.getBytes(charset);
                if (bytes.length > MAX_INPUT_CHARS || bytes.length > byteLength) throw Refusal.INSTANCE;
                stack.add(bytes);
            }
            default -> throw Refusal.INSTANCE;
        }
    }

    private static int compare(ArrayList<Object> stack) { int right = popInt(stack), left = popInt(stack); return Integer.compare(left, right); }
    private static Object peek(ArrayList<Object> stack) { if (stack.isEmpty()) throw Refusal.INSTANCE; return stack.getLast(); }
    private static Object pop(ArrayList<Object> stack) { if (stack.isEmpty()) throw Refusal.INSTANCE; return stack.removeLast(); }
    private static int popInt(ArrayList<Object> stack) { Object value = pop(stack); if (!(value instanceof Integer integer)) throw Refusal.INSTANCE; return integer; }
    private static char[] charArray(Object value) { if (!(value instanceof char[] characters)) throw Refusal.INSTANCE; return characters; }
    private static byte[] byteArray(Object value) { if (!(value instanceof byte[] bytes)) throw Refusal.INSTANCE; return bytes; }
    private static int arrayLength(Object value) {
        if (value instanceof char[] characters) return characters.length;
        if (value instanceof byte[] bytes) return bytes.length;
        throw Refusal.INSTANCE;
    }
    private static String string(Object value) {
        if (value instanceof String text) return text;
        if (value instanceof StringObject object && object.value != null) return object.value;
        throw Refusal.INSTANCE;
    }
    private static Charset charset(Object value) { if (!(value instanceof CharsetValue token)) throw Refusal.INSTANCE; return token.value; }
    private static BuilderObject builder(Object value) {
        if (!(value instanceof BuilderObject builder) || builder.value == null) throw Refusal.INSTANCE;
        return builder;
    }

    private void append(BuilderObject builder, String text, AllocationBudget allocationBudget) {
        int required = builder.value.length() + text.length();
        if (required > MAX_INPUT_CHARS) throw Refusal.INSTANCE;
        if (required > builder.capacity) {
            int capacity = (builder.capacity << 1) + 2;
            if (capacity < required) capacity = required;
            if (capacity > MAX_BUILDER_CAPACITY) throw Refusal.INSTANCE;
            reserve(allocationBudget, capacity * Character.BYTES);
            builder.capacity = capacity;
        }
        reserve(allocationBudget, text.length() * Character.BYTES);
        builder.value.append(text);
    }

    private void insert(BuilderObject builder, int index, char character, AllocationBudget allocationBudget) {
        int length = builder.value.length();
        if (index < 0 || index > length || length >= MAX_INPUT_CHARS) throw Refusal.INSTANCE;
        int required = length + 1;
        if (required > builder.capacity) {
            int capacity = (builder.capacity << 1) + 2;
            if (capacity < required) capacity = required;
            if (capacity > MAX_BUILDER_CAPACITY) throw Refusal.INSTANCE;
            reserve(allocationBudget, capacity * Character.BYTES);
            builder.capacity = capacity;
        }
        reserve(allocationBudget, Character.BYTES);
        builder.value.insert(index, character);
    }

    private void setBuilderLength(BuilderObject builder, int length, AllocationBudget allocationBudget) {
        int previousLength = builder.value.length();
        if (length < 0 || length > MAX_INPUT_CHARS) throw Refusal.INSTANCE;
        if (length > builder.capacity) {
            int capacity = (builder.capacity << 1) + 2;
            if (capacity < length) capacity = length;
            if (capacity > MAX_BUILDER_CAPACITY) throw Refusal.INSTANCE;
            reserve(allocationBudget, capacity * Character.BYTES);
            builder.capacity = capacity;
        }
        if (length > previousLength) reserve(allocationBudget, (length - previousLength) * Character.BYTES);
        builder.value.setLength(length);
    }

    private void reserve(AllocationBudget budget, int bytes) {
        if (bytes < 0 || bytes > budget.remaining || bytes > remainingClassAllocationBytes) throw Refusal.INSTANCE;
        budget.remaining -= bytes;
        remainingClassAllocationBytes -= bytes;
    }

    private static int utf8Length(String text) {
        int length = 0;
        for (int i = 0; i < text.length(); i++) {
            char value = text.charAt(i);
            if (value <= 0x7f) length++;
            else if (value <= 0x7ff) length += 2;
            else if (Character.isHighSurrogate(value) && i + 1 < text.length() && Character.isLowSurrogate(text.charAt(i + 1))) {
                length += 4; i++;
            } else if (Character.isSurrogate(value)) length++;
            else length += 3;
        }
        if (length > MAX_INPUT_CHARS) throw Refusal.INSTANCE;
        return length;
    }

    private static int encodedLength(String text, Charset charset) {
        if (charset.equals(StandardCharsets.UTF_8)) return utf8Length(text);
        if (charset.equals(StandardCharsets.US_ASCII) || charset.equals(StandardCharsets.ISO_8859_1)) return text.length();
        if (charset.equals(StandardCharsets.UTF_16BE) || charset.equals(StandardCharsets.UTF_16LE)) return text.length() * 2;
        if (charset.equals(StandardCharsets.UTF_16)) return text.length() * 2 + 2;
        throw Refusal.INSTANCE;
    }

    private static String methodKey(String name, String descriptor) { return name + '\u0000' + descriptor; }

    private static final class EvaluationContext {
        private int remainingCandidateSteps = MAX_STEPS_PER_CANDIDATE;
        private int depth;
        private final Set<MethodNode> active = Collections.newSetFromMap(new IdentityHashMap<>());
        private final AllocationBudget allocationBudget = new AllocationBudget();
    }
    private static final class AllocationBudget { private int remaining = MAX_ALLOCATION_BYTES_PER_CALL; }
    private static final class StringObject {
        private String value;
        private StringObject() {}
        private StringObject(String value) { this.value = value; }
    }
    private static final class BuilderObject { private StringBuilder value; private int capacity; }
    private record CharsetValue(Charset value) {}
    private enum Base64DecoderValue {
        BASIC(Base64.getDecoder()), URL_SAFE(Base64.getUrlDecoder()), MIME(Base64.getMimeDecoder());
        private final Base64.Decoder decoder;
        Base64DecoderValue(Base64.Decoder decoder) { this.decoder = decoder; }
        Base64.Decoder decoder() { return decoder; }
    }
    private static final class Refusal extends RuntimeException {
        static final Refusal INSTANCE = new Refusal();
        private Refusal() { super(null, null, false, false); }
    }
}
