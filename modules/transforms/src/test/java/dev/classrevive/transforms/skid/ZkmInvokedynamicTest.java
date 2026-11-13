package dev.classrevive.transforms.skid;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.DESKeySpec;
import javax.crypto.spec.IvParameterSpec;
import javax.tools.ToolProvider;
import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/**
 * Zelix KlassMaster 27 for Java 7 and later targets, over a synthetic jar written for ClassRevive in its shapes (the
 * obfuscator is commercial, so none of its output is committed): a key generator of jar classes (objects in a
 * {@code HashMap} keyed by their own {@code hashCode} and {@code equals}, linked so that a subclass's key needs its super
 * class's initializer to have run first), string tables encrypted with DES and an encrypted number, all reached through
 * {@code invokedynamic} whose bootstrap builds a {@code MutableCallSite} over a resolver, and a class whose initializer is
 * native code. Classes are linked by the JVM verifier in an isolated loader; none is initialized or executed.
 */
class ZkmInvokedynamicTest {
    @TempDir Path dir;

    static final String Y = """
        package y;
        public interface Y { long a(long x); }
        """;
    static final String K = """
        package y;
        public class K implements Y {
            private final long v;
            K(long v) { this.v = v; }
            public long a(long x) { return (v ^ x) * 31L + 7L; }
            @Override public int hashCode() { return (int) (v & 0xFFL); }
            @Override public boolean equals(Object o) { return o instanceof K && ((K) o).v == v; }
        }
        """;
    static final String GEN = """
        package y;
        import java.util.*;
        public class Gen {
            private static final Map<Y,Y> MAP = new HashMap<>();
            private static final List<Object> SEEN = new ArrayList<>();
            static { MAP.put(new K(11L), new K(1000L)); }
            public static Y a(long k1, long k2, Object who) {
                K x = new K(k1), y = new K(k2);
                Y r = MAP.get(x);
                if (r == null) r = new K(k1 * 3L);
                MAP.put(y, x);
                SEEN.add(who);
                return r;
            }
        }
        """;

    /** A protected class: its key from the generator, its DES table, its decryptors and resolvers; calls to them become invokedynamic. */
    static String protectedClass(String name, String parent, long k1, long k2, long k3, List<String> table, String numbers, String body) {
        var literals = new StringBuilder();
        for (String entry : table) literals.append(literals.isEmpty() ? "" : ", ").append(javaString(entry));
        return """
            package y;
            import java.lang.invoke.*;
            import javax.crypto.*;
            import javax.crypto.spec.*;
            public class %s%s {
                private static final long a = Gen.a(%dL, %dL, MethodHandles.lookup().lookupClass()).a(%dL);
                private static final String[] b = {%s};
                private static final int[] n = {%s};
                private static final String[] c = new String[%d];
                static int counter = %s.class.hashCode();

                static String dec(int id, long key) throws Exception {
                    if (c[id] == null) {
                        byte[] k = new byte[8];
                        for (int i = 0; i < 8; i++) k[i] = (byte) (key >>> (56 - 8 * i));
                        Cipher cipher = Cipher.getInstance("DES/CBC/PKCS5Padding");
                        cipher.init(2, SecretKeyFactory.getInstance("DES").generateSecret(new DESKeySpec(k)), new IvParameterSpec(new byte[8]));
                        c[id] = new String(cipher.doFinal(b[id].getBytes("ISO-8859-1")), "UTF-8");
                    }
                    return c[id];
                }
                static int num(int id, long key) { return n[id] ^ (int) key; }
                private static Object rs(MethodHandles.Lookup l, MutableCallSite s, String name, Object[] args) throws Exception {
                    String v = dec((Integer) args[0], (Long) args[1]);
                    s.setTarget(MethodHandles.dropArguments(MethodHandles.constant(String.class, v), 0, int.class, long.class));
                    return v;
                }
                private static int rn(MethodHandles.Lookup l, MutableCallSite s, String name, Object[] args) {
                    int v = num((Integer) args[0], (Long) args[1]);
                    s.setTarget(MethodHandles.dropArguments(MethodHandles.constant(int.class, v), 0, int.class, long.class));
                    return v;
                }
            %s
            }
            """.formatted(name, parent == null ? "" : " extends " + parent, k1, k2, k3, literals, numbers.isEmpty() ? "0" : numbers,
                table.size(), name, body);
    }

    static final String NATIVE = """
        package y;
        import java.lang.invoke.*;
        public class N {
            static String[] c;
            static { init(); }
            private static native void init();
            static String dec(int id, long key) { return c[id]; }
            private static Object rs(MethodHandles.Lookup l, MutableCallSite s, String name, Object[] args) {
                String v = dec((Integer) args[0], (Long) args[1]);
                s.setTarget(MethodHandles.dropArguments(MethodHandles.constant(String.class, v), 0, int.class, long.class));
                return v;
            }
            public static String secret() { return dec(0, 42L); }
        }
        """;

    /** A bundled library the obfuscator did not protect: a handler the clean-up would remove, an identity method. */
    static final String LIB = """
        package y;
        public class Lib {
            static RuntimeException same(RuntimeException e) { return e; }
            public static int f(int v) { try { return 10 / v; } catch (RuntimeException e) { throw same(e); } }
            public static int g(int v) { try { return 10 / v; } catch (RuntimeException e) { throw e; } }
        }
        """;

    // The keys, as the generator computes them when Base runs before Sub (the JVM's order).
    static long kValue(long v, long x) { return (v ^ x) * 31L + 7L; }
    static final long BASE = kValue(1000L, 5L);  // MAP holds K(11) -> K(1000)
    static final long SUB = kValue(11L, 9L);     // Base put K(22) -> K(11); Sub looks up K(22)

    static String encrypt(String text, long key) throws Exception {
        byte[] k = new byte[8];
        for (int i = 0; i < 8; i++) k[i] = (byte) (key >>> (56 - 8 * i));
        var cipher = Cipher.getInstance("DES/CBC/PKCS5Padding");
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeyFactory.getInstance("DES").generateSecret(new DESKeySpec(k)), new IvParameterSpec(new byte[8]));
        return new String(cipher.doFinal(text.getBytes(StandardCharsets.UTF_8)), StandardCharsets.ISO_8859_1);
    }

    /** A Latin-1 string as a Java literal of octal escapes (unicode escapes would be read before the string is lexed). */
    static String javaString(String value) {
        var out = new StringBuilder("\"");
        for (char c : value.toCharArray()) {
            if (c > 0xFF) throw new IllegalArgumentException("not Latin-1");
            out.append(String.format("\\%03o", (int) c));
        }
        return out.append('"').toString();
    }

    private Map<String,byte[]> jar() throws Exception {
        var base = protectedClass("Base", null, 11L, 22L, 5L,
            List.of(encrypt("hello", BASE ^ 99L ^ 123L), encrypt("greeting", (BASE ^ 77L) ^ 0x55L)),
            String.valueOf(42 ^ (int) (BASE ^ 99L ^ 7L)), """
                public static String hello() throws Exception { long k = a ^ 99L; return dec(0, k ^ 123L); }
                public static int answer() { long k = a ^ 99L; return num(0, k ^ 7L); }
                public String greet(long key) throws Exception { return dec(1, key ^ 0x55L); }
                public static String call() throws Exception { return new Base().greet(a ^ 77L); }
                private static RuntimeException x(RuntimeException e) { return e; }
                public static int guarded(int v) { try { return 10 / v; } catch (RuntimeException e) { throw x(e); } }
            """);
        var sub = protectedClass("Sub", "Base", 22L, 33L, 9L, List.of(encrypt("hi there", SUB ^ 5L ^ 6L)), "", """
                public static String hi() throws Exception { long k = a ^ 5L; return dec(0, k ^ 6L); }
            """);
        var sources = Map.of("Y", Y, "K", K, "Gen", GEN, "Base", base, "Sub", sub, "N", NATIVE, "Lib", LIB);
        var src = Files.createDirectories(dir.resolve("src/y"));
        var files = new ArrayList<String>();
        for (var entry : sources.entrySet()) {
            var file = src.resolve(entry.getKey() + ".java");
            Files.writeString(file, entry.getValue());
            files.add(file.toString());
        }
        var classes = Files.createDirectories(dir.resolve("classes"));
        var args = new ArrayList<>(List.of("--release", "8", "-Xlint:-options", "-proc:none", "-nowarn", "-d", classes.toString()));
        args.addAll(files);
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, args.toArray(String[]::new)));
        // Sub first: the reversal must still initialize Base before it.
        var entries = new LinkedHashMap<String,byte[]>();
        for (String name : List.of("Sub", "N", "Base", "Gen", "K", "Y", "Lib")) {
            byte[] bytes = Files.readAllBytes(classes.resolve("y/" + name + ".class"));
            entries.put("y/" + name + ".class", name.equals("Base") || name.equals("Sub") || name.equals("N") ? obfuscate(bytes) : bytes);
        }
        return entries;
    }

    /** Calls to the decryptors become invokedynamic sites with Zelix KlassMaster's bootstrap over the resolvers. */
    static byte[] obfuscate(byte[] bytes) {
        var node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        var helpers = Set.of("dec", "num", "rs", "rn");
        for (var method : node.methods) {
            if (helpers.contains(method.name)) continue;
            for (var insn : method.instructions.toArray())
                if (insn instanceof MethodInsnNode call && call.getOpcode() == INVOKESTATIC && call.owner.equals(node.name)
                    && (call.name.equals("dec") || call.name.equals("num"))) {
                    String bootstrap = call.name.equals("dec") ? "bs" : "bn";
                    var handle = new Handle(H_INVOKESTATIC, node.name, bootstrap, ZkmReverser.BOOTSTRAP, false);
                    method.instructions.set(call, new InvokeDynamicInsnNode(call.name, call.desc, handle));
                }
        }
        bootstrap(node, "bs", "rs", "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/invoke/MutableCallSite;Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/Object;");
        if (node.methods.stream().anyMatch(m -> m.name.equals("rn")))
            bootstrap(node, "bn", "rn", "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/invoke/MutableCallSite;Ljava/lang/String;[Ljava/lang/Object;)I");
        // A call replaced by a site of the same type keeps every frame; the bootstraps have no branch.
        var writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return writer.toByteArray();
    }

    /** {@code new MutableCallSite(type)} whose target collects the arguments into the resolver, after (lookup, site, name). */
    static void bootstrap(ClassNode node, String name, String resolver, String resolverDesc) {
        var m = new MethodNode(ACC_PRIVATE | ACC_STATIC, name, ZkmReverser.BOOTSTRAP, null, null);
        var list = m.instructions;
        list.add(new TypeInsnNode(NEW, "java/lang/invoke/MutableCallSite"));
        list.add(new InsnNode(DUP));
        list.add(new VarInsnNode(ALOAD, 2));
        list.add(new MethodInsnNode(INVOKESPECIAL, "java/lang/invoke/MutableCallSite", "<init>", "(Ljava/lang/invoke/MethodType;)V", false));
        list.add(new VarInsnNode(ASTORE, 3));
        list.add(new VarInsnNode(ALOAD, 3));
        list.add(new LdcInsnNode(new Handle(H_INVOKESTATIC, node.name, resolver, resolverDesc, false)));
        list.add(new LdcInsnNode(Type.getType("[Ljava/lang/Object;")));
        list.add(new VarInsnNode(ALOAD, 2));
        list.add(new MethodInsnNode(INVOKEVIRTUAL, "java/lang/invoke/MethodType", "parameterCount", "()I", false));
        list.add(new MethodInsnNode(INVOKEVIRTUAL, "java/lang/invoke/MethodHandle", "asCollector", "(Ljava/lang/Class;I)Ljava/lang/invoke/MethodHandle;", false));
        list.add(new InsnNode(ICONST_0));
        list.add(new InsnNode(ICONST_3));
        list.add(new TypeInsnNode(ANEWARRAY, "java/lang/Object"));
        for (int k = 0; k < 3; k++) {
            list.add(new InsnNode(DUP));
            list.add(new InsnNode(ICONST_0 + k));
            list.add(new VarInsnNode(ALOAD, k == 0 ? 0 : k == 1 ? 3 : 1));
            list.add(new InsnNode(AASTORE));
        }
        list.add(new MethodInsnNode(INVOKESTATIC, "java/lang/invoke/MethodHandles", "insertArguments",
            "(Ljava/lang/invoke/MethodHandle;I[Ljava/lang/Object;)Ljava/lang/invoke/MethodHandle;", false));
        list.add(new VarInsnNode(ALOAD, 2));
        list.add(new MethodInsnNode(INVOKESTATIC, "java/lang/invoke/MethodHandles", "explicitCastArguments",
            "(Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/MethodHandle;", false));
        list.add(new MethodInsnNode(INVOKEVIRTUAL, "java/lang/invoke/MutableCallSite", "setTarget", "(Ljava/lang/invoke/MethodHandle;)V", false));
        list.add(new VarInsnNode(ALOAD, 3));
        list.add(new InsnNode(ARETURN));
        node.methods.add(m);
    }

    private static MethodNode method(ClassNode node, String name) {
        return node.methods.stream().filter(m -> m.name.equals(name)).findFirst().orElseThrow(() -> new AssertionError("no " + name));
    }
    private static boolean has(ClassNode node, String name) { return node.methods.stream().anyMatch(m -> m.name.equals(name)); }
    private static List<Integer> opcodes(MethodNode method) {
        var result = new ArrayList<Integer>();
        for (var insn : method.instructions) if (insn.getOpcode() >= 0) result.add(insn.getOpcode());
        return result;
    }

    @Test void stringsAndNumbersBehindInvokedynamicAreDecryptedAndTheMachineryGoes() throws Exception {
        var input = jar();
        SkidfuscatorReverserTest.link(input);
        var result = new ZkmReverser().apply(input, 10_000);
        var report = result.report();
        assertTrue(report.detected(), report.toString());
        assertEquals(2, report.count("invokedynamic classes initialized"), report.toString());
        assertEquals(3, report.count("strings decrypted"), report.toString());
        assertEquals(2, report.count("invokedynamic class keys"), report.toString());
        assertEquals(1, report.count("keyed parameters learned"), report.toString()); // greet(long), from call()
        assertEquals(1, report.count("numbers decrypted"), report.toString());
        assertEquals(1, report.count("classes whose initializer is native code"), report.toString());
        assertEquals(1, report.count("invokedynamic decryptions left"), report.toString()); // N's: its keys are set by native code
        assertEquals(0, report.count("class write failures"), report.toString());
        SkidfuscatorReverserTest.link(result.entries());

        var base = SkidfuscatorReverserTest.node(result.entries().get("y/Base.class"));
        var sub = SkidfuscatorReverserTest.node(result.entries().get("y/Sub.class"));
        assertEquals(Set.of("hello", "greeting"), SkidfuscatorReverserTest.literals(base));
        assertEquals(Set.of("hi there"), SkidfuscatorReverserTest.literals(sub), "Sub's key needs Base initialized first");
        assertEquals(List.of(BIPUSH, IRETURN), opcodes(method(base, "answer")));
        // Exception obfuscation: the identity helper and the handler that only rethrew through it are gone.
        assertEquals(1, report.count("identity helper calls removed"), report.toString());
        assertFalse(has(base, "x"), "identity helper removed");
        assertEquals(List.of(), method(base, "guarded").tryCatchBlocks);
        for (var node : List.of(base, sub)) {
            for (String gone : List.of("bs", "bn", "rs", "rn", "dec", "num")) assertFalse(has(node, gone), node.name + "." + gone + " removed");
            for (var m : node.methods) for (var insn : m.instructions) assertFalse(insn instanceof InvokeDynamicInsnNode, node.name + "." + m.name);
            // The program's own initializer statement stays; the obfuscator's tables go.
            assertTrue(opcodes(method(node, "<clinit>")).contains(INVOKEVIRTUAL), node.name + ": counter = X.class.hashCode() kept");
            assertTrue(node.fields.stream().noneMatch(f -> f.name.equals("b")), node.name + ": DES table removed");
        }
        // A class the obfuscator did not protect keeps its bytes: the engines read it as before.
        assertArrayEquals(input.get("y/Lib.class"), result.entries().get("y/Lib.class"));
        // N's site stays as it was, with what it needs.
        var n = SkidfuscatorReverserTest.node(result.entries().get("y/N.class"));
        assertTrue(has(n, "bs") && has(n, "rs") && has(n, "dec"));
    }

    @Test void runtimeClassesExcludeWhatTheInterpreterCannotRunAndWhatDependsOnIt() {
        var nodes = new LinkedHashMap<String,ClassNode>();
        nodes.put("p/Pure", type("p/Pure", m -> {
            m.instructions.add(new TypeInsnNode(NEW, "java/util/ArrayList"));
            m.instructions.add(new InsnNode(DUP));
            m.instructions.add(new MethodInsnNode(INVOKESPECIAL, "java/util/ArrayList", "<init>", "()V", false));
            m.instructions.add(new InsnNode(POP));
        }));
        nodes.put("p/Clock", type("p/Clock", m -> {
            m.instructions.add(new MethodInsnNode(INVOKESTATIC, "java/lang/System", "nanoTime", "()J", false));
            m.instructions.add(new InsnNode(POP2));
        }));
        nodes.put("p/UsesClock", type("p/UsesClock", m ->
            m.instructions.add(new MethodInsnNode(INVOKESTATIC, "p/Clock", "run", "()V", false))));
        assertEquals(Set.of("p/Pure"), ZkmRuntime.runtimeClasses(nodes));
    }

    @Test void anIdentityHashCodeIsRefused() {
        var nodes = new LinkedHashMap<String,ClassNode>();
        // static int run() { return new Plain().hashCode(); }  Plain inherits Object.hashCode: not reproducible statically.
        var plain = type("p/Plain", m -> { });
        var user = type("p/User", m -> { });
        var run = new MethodNode(ACC_STATIC, "h", "()I", null, null);
        run.instructions.add(new TypeInsnNode(NEW, "p/Plain"));
        run.instructions.add(new InsnNode(DUP));
        run.instructions.add(new MethodInsnNode(INVOKESPECIAL, "p/Plain", "<init>", "()V", false));
        run.instructions.add(new MethodInsnNode(INVOKEVIRTUAL, "java/lang/Object", "hashCode", "()I", false));
        run.instructions.add(new InsnNode(IRETURN));
        run.maxLocals = 0; run.maxStack = 2;
        user.methods.add(run);
        var init = new MethodNode(ACC_PUBLIC, "<init>", "()V", null, null);
        init.instructions.add(new VarInsnNode(ALOAD, 0));
        init.instructions.add(new MethodInsnNode(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false));
        init.instructions.add(new InsnNode(RETURN));
        init.maxLocals = 1; init.maxStack = 1;
        plain.methods.add(init);
        nodes.put("p/Plain", plain);
        nodes.put("p/User", user);
        var runtime = new ZkmRuntime(nodes);
        var refusal = assertThrows(ZkmRuntime.Refusal.class, () -> runtime.call("p/User", run, new Object[0]));
        assertTrue(refusal.getMessage().startsWith("identity hash code"), refusal.getMessage());
    }

    /** A class with one static method {@code run()V} whose body {@code body} writes (a return is added). */
    private static ClassNode type(String name, java.util.function.Consumer<MethodNode> body) {
        var node = new ClassNode();
        node.version = V1_8; node.access = ACC_PUBLIC; node.name = name; node.superName = "java/lang/Object";
        var run = new MethodNode(ACC_PUBLIC | ACC_STATIC, "run", "()V", null, null);
        body.accept(run);
        run.instructions.add(new InsnNode(RETURN));
        run.maxLocals = 0; run.maxStack = 4;
        node.methods.add(run);
        return node;
    }
}
