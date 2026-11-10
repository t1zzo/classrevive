package dev.classrevive.transforms.radon;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import java.nio.file.*;
import java.util.*;
import javax.tools.ToolProvider;
import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/**
 * Phase 2DK: the Radon reversal over a synthetic jar written for ClassRevive in Radon 2's shapes, applied in Radon's own
 * order (references, numbers, strings, flow, hide code, invalid annotations, static initialization). Radon is GPL-3.0:
 * none of its code or output is used; the shapes follow docs/radon-yguard.md. The encryptions below are written
 * independently of {@link RadonCipher}. Classes are linked by the JVM verifier in an isolated loader; none is initialized
 * or executed.
 */
class RadonReverserTest {
    @TempDir Path dir;

    static final String BOOT = "rb/Boot", STR = "rs/Str", DEC = "rn/Dec", FAKE = "rf/Fake", FAST = "rq/Fast";
    static final int STRING_KEY = 0x13572468;

    // ------------------------------------------------------------------------------------- independent re-statements

    private static String javaName(Type type) { return type.getSort() == Type.ARRAY ? type.getInternalName().replace('/', '.') : type.getClassName(); }
    private static int descHash(String desc) {
        int hash = 0;
        for (var type : Type.getArgumentTypes(desc)) hash ^= javaName(type).hashCode();
        return hash ^ javaName(Type.getReturnType(desc)).hashCode();
    }
    private static String widen(String desc) {
        var args = Type.getArgumentTypes(desc);
        for (int i = 0; i < args.length; i++) if (args[i].getSort() == Type.OBJECT) args[i] = Type.getObjectType("java/lang/Object");
        return Type.getMethodDescriptor(Type.getReturnType(desc), args);
    }
    private static String encrypt(String plain, int key, String callerClass, String callerMethod) {
        int cc = callerClass.replace('/', '.').hashCode(), cm = callerMethod.hashCode(), dc = STR.replace('/', '.').hashCode(), dm = "s".hashCode();
        int[] keys = {(cc + dc + cm) ^ key, (cm + dm + cc) ^ key, (dc + cc + cm) ^ key, (dm + cc + dc) ^ key};
        var out = new StringBuilder();
        for (int i = 0; i < plain.length(); i++) out.append((char) (plain.charAt(i) ^ keys[i % 4]));
        return out.toString();
    }

    // ------------------------------------------------------------------------------------- the obfuscated jar

    private Map<String,byte[]> obfuscatedJar() throws Exception {
        var src = Files.createDirectories(dir.resolve("src/app"));
        Files.writeString(src.resolve("App.java"), """
            package app;
            public class App {
                static final int LIMIT = 42;
                static final String NAME = "radon";
                private int count;
                public String greet(String who) { return "Hello, " + who + "!"; }
                public int sum(int[] values) { int total = 0; for (int i = 0; i < values.length; i++) total += values[i]; return total * 3; }
                public static long big() { return 1234567890123L; }
                public static double ratio() { return 2.5; }
                public boolean present(Object value) { if (value != null) return true; return false; }
                public boolean absent(Object value) { if (value == null) return true; return false; }
                public void bump() { count++; }
                public int count() { return count; }
            }
            """);
        var classes = Files.createDirectories(dir.resolve("classes"));
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "--release", "8", "-Xlint:-options", "-proc:none", "-nowarn",
            "-d", classes.toString(), src.resolve("App.java").toString()));
        var app = new ClassNode();
        new ClassReader(Files.readAllBytes(classes.resolve("app/App.class"))).accept(app, ClassReader.SKIP_FRAMES);
        references(app); numbers(app); strings(app); flow(app); hide(app); annotate(app); staticInit(app);
        var entries = new LinkedHashMap<String,byte[]>();
        entries.put("app/App.class", write(app));
        entries.put(BOOT + ".class", write(boot()));
        entries.put(FAST + ".class", write(fast()));
        entries.put(STR + ".class", write(stringDecryptor()));
        entries.put(DEC + ".class", write(numberDecoder()));
        entries.put(FAKE + ".class", write(fakeHandler()));
        entries.put("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\n".getBytes());
        // For a local measurement (docs/radon-yguard.md): the synthetic jar and its answer key, written outside the repository.
        String out = System.getenv("CLASSREVIVE_RADON_FIXTURE_OUT");
        if (out != null) {
            var folder = Files.createDirectories(Path.of(out));
            writeJar(folder.resolve("radon-shapes-obf.jar"), entries);
            writeJar(folder.resolve("radon-shapes.jar"), Map.of("app/App.class", Files.readAllBytes(classes.resolve("app/App.class"))));
        }
        return entries;
    }

    private static void writeJar(Path jar, Map<String,byte[]> entries) throws java.io.IOException {
        try (var zip = new java.util.zip.ZipOutputStream(Files.newOutputStream(jar))) {
            for (var entry : new TreeMap<>(entries).entrySet()) {
                var item = new java.util.zip.ZipEntry(entry.getKey()); item.setTime(0);
                zip.putNextEntry(item); zip.write(entry.getValue()); zip.closeEntry();
            }
        }
    }

    private static byte[] write(ClassNode node) {
        var writer = new ClassWriter(ClassWriter.COMPUTE_MAXS); node.accept(writer); return writer.toByteArray();
    }

    /** Heavy invokedynamic for every call and field access outside constructors; StringBuilder.toString goes through the fast bootstrap. */
    private static void references(ClassNode app) {
        var heavy = new Handle(H_INVOKESTATIC, BOOT, "b", RadonReverser.HEAVY_BOOTSTRAP, false);
        var fast = new Handle(H_INVOKESTATIC, FAST, "bsm", RadonReverser.FAST_BOOTSTRAP, false);
        for (var method : app.methods) {
            if (method.name.startsWith("<")) continue;
            for (var insn : method.instructions.toArray()) {
                var list = new InsnList();
                if (insn instanceof MethodInsnNode m && !m.name.startsWith("<")) {
                    if (m.name.equals("toString")) {
                        String plain = m.owner.replace('/', '.') + "\0\0" + m.name + "\0\0" + m.desc + "\0\0b";
                        int[] keys = {FAST.replace('/', '.').hashCode(), "bsm".hashCode(), "dec".hashCode()};
                        var name = new StringBuilder();
                        for (int i = 0; i < plain.length(); i++) name.append((char) (plain.charAt(i) ^ keys[i % 3]));
                        method.instructions.set(m, new InvokeDynamicInsnNode(name.toString(), widen("(Ljava/lang/Object;" + m.desc.substring(1)), fast));
                        continue;
                    }
                    String desc = m.desc.replace(")", "Ljava/lang/String;J)");
                    if (m.getOpcode() != INVOKESTATIC) desc = "(Ljava/lang/Object;" + desc.substring(1);
                    list.add(new LdcInsnNode(m.owner.replace('/', '.')));
                    list.add(new LdcInsnNode(((long) m.name.hashCode() << 32) | (descHash(m.desc) & 0xFFFFFFFFL)));
                    method.instructions.insertBefore(m, list);
                    method.instructions.set(m, new InvokeDynamicInsnNode(m.getOpcode() == INVOKESTATIC ? "a" : "b", widen(desc), heavy));
                } else if (insn instanceof FieldInsnNode f) {
                    boolean put = f.getOpcode() == PUTFIELD || f.getOpcode() == PUTSTATIC, isStatic = f.getOpcode() == GETSTATIC || f.getOpcode() == PUTSTATIC;
                    String desc = put ? "(" + f.desc + "Ljava/lang/String;J)V" : "(Ljava/lang/String;J)" + f.desc;
                    if (!isStatic) desc = "(Ljava/lang/Object;" + desc.substring(1);
                    String name = switch (f.getOpcode()) { case GETSTATIC -> "d"; case GETFIELD -> "e"; case PUTSTATIC -> "f"; default -> "g"; };
                    list.add(new LdcInsnNode(f.owner.replace('/', '.')));
                    list.add(new LdcInsnNode(((long) f.name.hashCode() << 32) | (javaName(Type.getType(f.desc)).hashCode() & 0xFFFFFFFFL)));
                    method.instructions.insertBefore(f, list);
                    method.instructions.set(f, new InvokeDynamicInsnNode(name, desc, heavy));
                }
            }
        }
    }

    /** Context checking on the first long key (greet), 3 (sum) and 2.5 (ratio); arithmetic and bitwise chains elsewhere. */
    private static void numbers(ClassNode app) {
        for (var method : app.methods) {
            var insns = method.instructions; int hash = method.name.hashCode();
            for (var insn : insns.toArray()) {
                var list = new InsnList();
                if (method.name.equals("greet") && insn instanceof LdcInsnNode ldc && ldc.cst instanceof Long value && !greetDone) {
                    greetDone = true;
                    list.add(new LdcInsnNode(value ^ (long) hash));
                    list.add(new MethodInsnNode(INVOKESTATIC, "java/lang/Long", "valueOf", "(J)Ljava/lang/Long;", false));
                    list.add(new InsnNode(ICONST_0));
                    unbox(list, "java/lang/Long", "longValue", "()J");
                } else if (method.name.equals("sum") && insn.getOpcode() == ICONST_3) {
                    list.add(new LdcInsnNode(3 ^ hash));
                    list.add(new MethodInsnNode(INVOKESTATIC, "java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;", false));
                    list.add(new InsnNode(ICONST_0));
                    unbox(list, "java/lang/Integer", "intValue", "()I");
                } else if (method.name.equals("sum") && insn.getOpcode() == ICONST_0 && insn.getNext() instanceof VarInsnNode) {
                    // 13 << 6 ^ 832 == 0, then (x + 5) - 5
                    list.add(new IntInsnNode(BIPUSH, 13)); list.add(new IntInsnNode(BIPUSH, 6)); list.add(new InsnNode(ISHL));
                    list.add(new IntInsnNode(SIPUSH, 832)); list.add(new InsnNode(IXOR)); list.add(new InsnNode(ICONST_5)); list.add(new InsnNode(IADD));
                    list.add(new InsnNode(ICONST_5)); list.add(new InsnNode(ISUB));
                } else if (method.name.equals("ratio") && insn instanceof LdcInsnNode ldc && ldc.cst instanceof Double value) {
                    list.add(new LdcInsnNode(Double.doubleToLongBits(value) ^ (long) hash));
                    list.add(new MethodInsnNode(INVOKESTATIC, "java/lang/Long", "valueOf", "(J)Ljava/lang/Long;", false));
                    list.add(new LdcInsnNode(0x2468));
                    unbox(list, "java/lang/Double", "doubleValue", "()D");
                } else if (method.name.equals("big") && insn instanceof LdcInsnNode ldc && ldc.cst instanceof Long value) {
                    list.add(new LdcInsnNode(value + 77)); list.add(new LdcInsnNode(77L)); list.add(new InsnNode(LSUB));
                } else continue;
                insns.insertBefore(insn, list); insns.remove(insn);
            }
        }
    }
    private static boolean greetDone;
    private static void unbox(InsnList list, String box, String name, String desc) {
        list.add(new MethodInsnNode(INVOKESTATIC, DEC, "d", RadonReverser.NUMBER_DECODER, false));
        list.add(new TypeInsnNode(CHECKCAST, box));
        list.add(new MethodInsnNode(INVOKEVIRTUAL, box, name, desc, false));
    }

    private static void strings(ClassNode app) {
        for (var method : app.methods)
            for (var insn : method.instructions.toArray())
                if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof String plain) {
                    ldc.cst = encrypt(plain, STRING_KEY, app.name, method.name);
                    var list = new InsnList();
                    list.add(new LdcInsnNode(STRING_KEY));
                    list.add(new MethodInsnNode(INVOKESTATIC, STR, "s", RadonReverser.STRING_DECRYPTOR, false));
                    method.instructions.insert(ldc, list);
                }
    }

    private static void flow(ClassNode app) {
        app.fields.add(new FieldNode(ACC_PUBLIC | ACC_STATIC | ACC_FINAL, "pz", "Z", null, null));
        app.fields.add(new FieldNode(ACC_PUBLIC | ACC_STATIC | ACC_FINAL, "pi", "I", null, null));
        for (var method : app.methods) {
            var insns = method.instructions;
            switch (method.name) {
                case "sum" -> { // goto replacement
                    int v = method.maxLocals++;
                    for (var insn : insns.toArray()) if (insn instanceof JumpInsnNode go && go.getOpcode() == GOTO) {
                        insns.insertBefore(go, new VarInsnNode(ILOAD, v));
                        insns.insertBefore(go, new JumpInsnNode(IFEQ, go.label));
                        insns.insert(go, new InsnNode(ATHROW)); insns.insert(go, new InsnNode(ACONST_NULL));
                        insns.remove(go);
                    }
                    predicate(insns, "pz", "Z", v);
                }
                case "present" -> { // ifnull mutilated, then a bogus jump to an exit block
                    mutilate(method, IFNULL, "getClass", "()Ljava/lang/Class;");
                    int v = method.maxLocals++;
                    var exit = new LabelNode(); var escape = new LabelNode();
                    for (var insn : insns.toArray()) if (insn.getOpcode() == ICONST_1) {
                        insns.insertBefore(insn, new VarInsnNode(ILOAD, v)); insns.insertBefore(insn, new JumpInsnNode(IFNE, exit)); break;
                    }
                    var block = new InsnList();
                    block.add(new JumpInsnNode(GOTO, escape)); block.add(exit); block.add(new InsnNode(ICONST_1)); block.add(new InsnNode(IRETURN)); block.add(escape);
                    insns.insert(block);
                    predicate(insns, "pz", "Z", v);
                }
                case "absent" -> mutilate(method, IFNONNULL, "hashCode", "()I");
                case "count" -> { // bogus switch
                    int v = method.maxLocals++;
                    var t0 = new LabelNode(); var t1 = new LabelNode(); var dflt = new LabelNode(); var back = new LabelNode();
                    var block = new InsnList();
                    block.add(new VarInsnNode(ILOAD, v)); block.add(new JumpInsnNode(IFEQ, dflt)); block.add(back);
                    block.add(new VarInsnNode(ILOAD, v)); block.add(new TableSwitchInsnNode(0, 1, dflt, t0, t1)); block.add(dflt);
                    insns.insert(block);
                    insns.add(t0); insns.add(new InsnNode(ICONST_1)); insns.add(new VarInsnNode(ISTORE, v)); insns.add(new JumpInsnNode(GOTO, t1));
                    insns.add(t1); insns.add(new InsnNode(ICONST_0)); insns.add(new VarInsnNode(ISTORE, v)); insns.add(new JumpInsnNode(GOTO, t0));
                    predicate(insns, "pi", "I", v);
                }
                case "bump" -> { // fake catch block around the first instruction
                    var first = insns.getFirst(); while (first.getOpcode() < 0) first = first.getNext();
                    var start = new LabelNode(); var end = new LabelNode(); var handler = new LabelNode(); var after = new LabelNode();
                    insns.insertBefore(first, start);
                    var tail = new InsnList();
                    tail.add(end); tail.add(new JumpInsnNode(GOTO, after)); tail.add(handler); tail.add(new InsnNode(DUP));
                    tail.add(new MethodInsnNode(INVOKEVIRTUAL, FAKE, "x", "()V", false)); tail.add(new InsnNode(ATHROW)); tail.add(after);
                    insns.insert(first, tail);
                    method.tryCatchBlocks.add(new TryCatchBlockNode(start, end, handler, FAKE));
                }
                default -> { }
            }
        }
    }
    private static void predicate(InsnList insns, String field, String desc, int slot) {
        insns.insert(new VarInsnNode(ISTORE, slot));
        insns.insert(new FieldInsnNode(GETSTATIC, "app/App", field, desc));
    }
    private static void mutilate(MethodNode method, int opcode, String probe, String desc) {
        var insns = method.instructions;
        for (var insn : insns.toArray()) {
            if (insn.getOpcode() != opcode) continue;
            var jump = (JumpInsnNode) insn;
            var start = new LabelNode(); var end = new LabelNode(); var catchStart = new LabelNode(); var catchEnd = new LabelNode();
            var list = new InsnList();
            list.add(start); list.add(new MethodInsnNode(INVOKEVIRTUAL, "java/lang/Object", probe, desc, false)); list.add(new InsnNode(POP)); list.add(end);
            if (opcode == IFNONNULL) { list.add(new JumpInsnNode(GOTO, jump.label)); list.add(catchStart); list.add(new InsnNode(POP)); list.add(catchEnd); }
            else { list.add(new JumpInsnNode(GOTO, catchEnd)); list.add(catchStart); list.add(new InsnNode(POP)); list.add(new JumpInsnNode(GOTO, jump.label)); list.add(catchEnd); }
            insns.insert(insn, list); insns.remove(insn);
            method.tryCatchBlocks.add(0, new TryCatchBlockNode(start, end, catchStart, "java/lang/NullPointerException"));
        }
    }
    private static void hide(ClassNode app) {
        app.access |= ACC_SYNTHETIC;
        for (var method : app.methods) {
            method.access |= ACC_SYNTHETIC;
            if (!method.name.startsWith("<")) method.access |= ACC_BRIDGE;
        }
        for (var field : app.fields) field.access |= ACC_SYNTHETIC;
    }
    private static void annotate(ClassNode app) {
        for (var method : app.methods) {
            method.visibleAnnotations = new ArrayList<>(List.of(new AnnotationNode("@")));
            method.invisibleAnnotations = new ArrayList<>(List.of(new AnnotationNode("@")));
        }
    }
    private static void staticInit(ClassNode app) {
        var clinit = new MethodNode(ACC_STATIC, "<clinit>", "()V", null, null);
        clinit.instructions.add(new InsnNode(RETURN));
        for (var field : app.fields) if (field.value != null) {
            var list = new InsnList();
            list.add(new LdcInsnNode(field.value)); list.add(new FieldInsnNode(PUTSTATIC, app.name, field.name, field.desc));
            clinit.instructions.insert(list); field.value = null;
        }
        app.methods.add(clinit);
    }

    // ------------------------------------------------------------------------------------- injected classes (shapes only)

    private static ClassNode type(String name, String superName) {
        var node = new ClassNode(); node.version = V1_5; node.access = ACC_PUBLIC | ACC_SUPER; node.name = name; node.superName = superName; return node;
    }
    private static MethodNode method(ClassNode node, String name, String desc) {
        var method = new MethodNode(ACC_PUBLIC | ACC_STATIC, name, desc, null, null); node.methods.add(method); return method;
    }
    private static ClassNode boot() {
        var node = type(BOOT, "java/lang/Object");
        var b = method(node, "b", RadonReverser.HEAVY_BOOTSTRAP); b.instructions.add(new InsnNode(ACONST_NULL)); b.instructions.add(new InsnNode(ARETURN));
        return node;
    }
    private static ClassNode fast() {
        var node = type(FAST, "java/lang/Object");
        var b = method(node, "bsm", RadonReverser.FAST_BOOTSTRAP); b.instructions.add(new InsnNode(ACONST_NULL)); b.instructions.add(new InsnNode(ARETURN));
        var d = method(node, "dec", "()V"); d.instructions.add(new InsnNode(RETURN));
        return node;
    }
    private static ClassNode stringDecryptor() {
        var node = type(STR, "java/lang/Object");
        node.fields.add(new FieldNode(ACC_PRIVATE | ACC_STATIC | ACC_FINAL, "m", "Ljava/util/Map;", null, null));
        node.fields.add(new FieldNode(ACC_PRIVATE | ACC_STATIC | ACC_FINAL, "k", "[J", null, null));
        var s = method(node, "s", RadonReverser.STRING_DECRYPTOR);
        s.instructions.add(new MethodInsnNode(INVOKESTATIC, "java/lang/Thread", "currentThread", "()Ljava/lang/Thread;", false));
        s.instructions.add(new MethodInsnNode(INVOKEVIRTUAL, "java/lang/Thread", "getStackTrace", "()[Ljava/lang/StackTraceElement;", false));
        s.instructions.add(new InsnNode(POP));
        for (int i = 0; i < 4; i++) {
            s.instructions.add(new InsnNode(ICONST_0)); s.instructions.add(new InsnNode(ICONST_0)); s.instructions.add(new InsnNode(IADD));
            s.instructions.add(new VarInsnNode(ILOAD, 1)); s.instructions.add(new InsnNode(IXOR)); s.instructions.add(new VarInsnNode(ISTORE, 2 + i));
        }
        s.instructions.add(new VarInsnNode(ALOAD, 0)); s.instructions.add(new TypeInsnNode(CHECKCAST, "java/lang/String")); s.instructions.add(new InsnNode(ARETURN));
        return node;
    }
    private static ClassNode numberDecoder() {
        var node = type(DEC, "java/lang/Thread");
        var d = method(node, "d", RadonReverser.NUMBER_DECODER);
        d.instructions.add(new MethodInsnNode(INVOKESTATIC, "java/lang/Thread", "currentThread", "()Ljava/lang/Thread;", false));
        d.instructions.add(new MethodInsnNode(INVOKEVIRTUAL, "java/lang/Thread", "getStackTrace", "()[Ljava/lang/StackTraceElement;", false));
        d.instructions.add(new InsnNode(POP)); d.instructions.add(new VarInsnNode(ALOAD, 0)); d.instructions.add(new InsnNode(ARETURN));
        return node;
    }
    private static ClassNode fakeHandler() { return type(FAKE, "java/lang/IllegalStateException"); }

    // ------------------------------------------------------------------------------------- tests

    @Test void reversesEveryShapeAndRemovesTheInjectedClasses() throws Exception {
        greetDone = false;
        var result = new RadonReverser().apply(obfuscatedJar(), 1000);
        var report = result.report();
        assertTrue(report.detected(), report.toString());
        assertEquals(Set.of("app/App.class", "META-INF/MANIFEST.MF"), result.entries().keySet(), report.toString());
        assertEquals(5, report.count("injected classes removed"), report.toString());
        assertEquals(0, report.count("decryptor calls left") + report.count("context-checked numbers left") + report.count("invokedynamic references left"), report.toString());
        assertTrue(report.count("strings decrypted") >= 2, report.toString());
        assertEquals(3, report.count("context-checked numbers decoded"), report.toString());
        assertEquals(2, report.count("null checks restored"), report.toString());
        assertEquals(1, report.count("fake catch blocks removed"), report.toString());
        assertEquals(1, report.count("bogus switches removed"), report.toString());
        assertEquals(1, report.count("exit blocks removed"), report.toString());
        assertTrue(report.count("gotos restored") >= 1, report.toString());
        assertEquals(2, report.count("predicate fields removed"), report.toString());
        assertEquals(2, report.count("constants put back into fields"), report.toString());

        byte[] bytes = result.entries().get("app/App.class");
        var app = new ClassNode(); new ClassReader(bytes).accept(app, 0);
        assertEquals(0, app.access & ACC_SYNTHETIC);
        var literals = new HashSet<Object>();
        for (var method : app.methods) {
            assertNotEquals("<clinit>", method.name);
            assertEquals(0, method.access & (ACC_SYNTHETIC | ACC_BRIDGE), method.name);
            assertTrue(method.visibleAnnotations == null || method.visibleAnnotations.isEmpty(), method.name);
            assertTrue(method.tryCatchBlocks == null || method.tryCatchBlocks.isEmpty(), method.name);
            for (var insn : method.instructions) {
                assertFalse(insn instanceof InvokeDynamicInsnNode, method.name);
                assertNotEquals(ATHROW, insn.getOpcode(), method.name);
                assertNotEquals(TABLESWITCH, insn.getOpcode(), method.name);
                if (insn instanceof FieldInsnNode field) assertFalse(field.name.startsWith("p") && field.desc.length() == 1, method.name);
                if (insn instanceof LdcInsnNode ldc) literals.add(ldc.cst);
                if (insn instanceof MethodInsnNode call) assertTrue(call.owner.startsWith("java/") || call.owner.equals("app/App"), call.owner);
            }
        }
        assertTrue(literals.containsAll(List.of("Hello, ", "!", 1234567890123L, 2.5)), literals.toString());
        var fields = new HashMap<String,FieldNode>(); for (var field : app.fields) fields.put(field.name, field);
        assertEquals(Set.of("LIMIT", "NAME", "count"), fields.keySet());
        assertEquals(42, fields.get("LIMIT").value); assertEquals("radon", fields.get("NAME").value);
        assertEquals(0, fields.get("count").access & ACC_SYNTHETIC);
        var greet = app.methods.stream().filter(m -> m.name.equals("greet")).findFirst().orElseThrow();
        assertTrue(Arrays.stream(greet.instructions.toArray()).anyMatch(i -> i instanceof MethodInsnNode m && m.name.equals("toString") && m.getOpcode() == INVOKEVIRTUAL));
        var type = new ClassLoader(null) { Class<?> define() { return defineClass("app.App", bytes, 0, bytes.length); } }.define();
        assertDoesNotThrow(type::getDeclaredMethods, "the reversed class must pass JVM verification");
    }

    @Test void aJarWithoutRadonIsLeftAsItIs() throws Exception {
        var src = Files.createDirectories(dir.resolve("plain"));
        Files.writeString(src.resolve("Plain.java"), "public class Plain { static final boolean F = false; static int twice(int x) { if (F) return 0; return x * 2; } }");
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "--release", "8", "-Xlint:-options", "-proc:none", "-d",
            src.toString(), src.resolve("Plain.java").toString()));
        var entries = Map.of("Plain.class", Files.readAllBytes(src.resolve("Plain.class")));
        var result = new RadonReverser().apply(entries, 100);
        assertFalse(result.report().detected());
        assertSame(entries, result.entries());
    }
}
