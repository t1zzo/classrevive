package dev.classrevive.transforms.skid;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.tree.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import javax.tools.ToolProvider;
import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/**
 * Phase 2CZ: the Aidsfuscator reversal over a synthetic jar written for ClassRevive in Aidsfuscator's shapes (the
 * obfuscator itself has no license, so none of its output is committed): an FNV-1a salt dispatcher and a class salt read
 * through {@code MethodHandles.lookup().lookupClass()}, a hash integrity field backed by the SHA-256 of the class file,
 * salt parameters, a flattened method, a string decryptor keyed by its caller's stack trace and a number decryptor with
 * their initializer, and {@code invokedynamic} references through a {@code MutableCallSite} bootstrap, one of them in a
 * method nothing calls. Classes are linked by the JVM verifier in an isolated loader; none is initialized or executed.
 */
class AidsfuscatorReverserTest {
    @TempDir Path dir;

    static final int SALT = 0x5A5A1234, FAKE = 0x1111, MASK = 0x0F0F0F0F, LOCAL_SALT = 0x13572468;
    static final int WEIGHT_SALT = 0x2468ACE0, CALL_SALT = 0x0BADF00D, ORPHAN_SALT = 0x31415926, HASH_VALUE = 0x600DCAFE, HASH_PARAM = 0x0FF1CE;
    static final int K0 = 0x11111111, K1 = 0x22222222, K2 = 0x33333333, DEC_KEY = 0x4321, INDEX_KEY = 0x1234;
    static final char STATIC_KIND = '帀';

    private static String hex(int value) { return "0x" + Integer.toHexString(value); }
    private static String escape(String text) {
        var out = new StringBuilder();
        for (char c : text.toCharArray()) out.append(String.format("\\u%04x", (int) c));
        return out.toString();
    }
    /** The string decryptor's encryption for a call from {@code method} of {@code app.App}. */
    private static String encrypt(String plain, int key, String method) {
        int hash = ("app.App".hashCode() ^ method.hashCode()) >> 16;
        var chars = plain.toCharArray();
        for (int i = 0; i < chars.length; i++) chars[i] = (char) (chars[i] ^ key ^ hash);
        return new String(chars);
    }

    private Map<String,byte[]> obfuscatedJar() throws Exception {
        int q = WEIGHT_SALT | MASK;
        String token = "app.App:answer:()I";
        var tokenChars = token.toCharArray();
        for (int i = 0; i < tokenChars.length; i++) tokenChars[i] = (char) (tokenChars[i] ^ DEC_KEY);
        var sources = Map.of(
            "d/Dispatcher.java", """
                package d;
                import java.util.*;
                public class Dispatcher {
                    private static Map<Long,Integer> values = new HashMap<>();
                    static { init(); }
                    private static long hash(String s) { long h = 0xcbf29ce484222325L; for (int i = 0; i < s.length(); i++) { h ^= s.charAt(i); h *= 0x100000001b3L; } return h; }
                    private static void init() { values.put(%sL, %s); }
                    public static int get(Object type, int fake) { return values.get(hash(((Class<?>) type).getName())) ^ fake; }
                }
                """.formatted(AidsfuscatorReverser.fnv("app.App"), hex(SALT ^ FAKE)),
            "app/Hash.java", """
                package app;
                public class Hash {
                    static java.io.InputStream in;
                    static { in = Hash.class.getResourceAsStream("/h.bin"); }
                    public static String sha(byte[] bytes) throws Exception { java.security.MessageDigest.getInstance("SHA-256"); return ""; }
                    public static int get(Class<?> type, int key) { return 0; }
                }
                """,
            "app/Refs.java", """
                package app;
                import java.lang.invoke.*;
                public class Refs {
                    static String[] refs = new String[1];
                    static { fill(); }
                    static void fill() { refs[0] = "%s"; }
                    public static CallSite bootstrap(MethodHandles.Lookup lookup, String name, MethodType type) { return new MutableCallSite(type); }
                    static String decode(int index, int key) { return refs[index]; }
                    static int marker(int index, int key) { return 0; }
                    private static MethodHandle invoker(MethodHandles.Lookup lookup, MutableCallSite site, String name, MethodType type, int idx, int key) throws Exception {
                        int index = idx ^ %s;
                        char kind = name.charAt(0);
                        String[] parts = decode(index, key).split(":");
                        Class<?> owner = Class.forName(parts[0]);
                        MethodType target = MethodType.fromMethodDescriptorString(parts[2], Refs.class.getClassLoader());
                        switch (kind) {
                            case '\\u4e00': return lookup.findVirtual(owner, parts[1], target);
                            case '\\u5e00': return lookup.findStatic(owner, parts[1], target);
                            case '\\u6e00': case '\\u7e00':
                                if (kind != '\\u6e00') return lookup.findSetter(owner, parts[1], target.returnType());
                                else return lookup.findGetter(owner, parts[1], target.returnType());
                            default: return null;
                        }
                    }
                }
                """.formatted(escape(new String(tokenChars)), hex(INDEX_KEY)),
            "app/App.java", """
                package app;
                import java.lang.invoke.MethodHandles;
                public class App {
                    public static final int SALT = d.Dispatcher.get(MethodHandles.lookup().lookupClass(), %s);
                    static final int HASH = Hash.get(MethodHandles.lookup().lookupClass(), %s);
                    private static String[] strings = { "%s", "%s" };
                    private static Object[] cache = new Object[2];
                    private static int[] numbers = { %s };
                    static String GREETING;
                    static { GREETING = dec(0x55, %s); }

                    private static String dec(int index, int key) {
                        char[] chars = strings[index ^ 0x55].toCharArray();
                        StackTraceElement[] trace = (StackTraceElement[]) cache[index ^ 0x55];
                        if (trace == null) { trace = new Throwable().getStackTrace(); cache[index ^ 0x55] = trace; }
                        StackTraceElement caller = trace[1];
                        int hash = (caller.getClassName().hashCode() ^ caller.getMethodName().hashCode()) >> 16;
                        for (int i = 0; i < chars.length; i++) chars[i] = (char) (chars[i] ^ key ^ hash);
                        return new String(chars).intern();
                    }
                    private static int num(int index, int key) { return numbers[index ^ 0x66] ^ key; }

                    static int weight(int code, int salt) {
                        int state = (salt | %s) ^ %s;
                        while (true) {
                            switch (state) {
                                case %s: if (code == 1) { state = (salt | %s) ^ %s; break; } state = (salt | %s) ^ %s; break;
                                case %s: return (salt | %s) ^ %s;
                                case %s: return -1;
                                default: return 0;
                            }
                        }
                    }
                    static int local() { int s = (SALT & 0x00FF00FF) ^ %s; return (s | %s) ^ %s; }
                    static int hashed() { return HASH ^ %s; }
                    public static int answer() { return 42; }
                    static int callAnswer(int salt) { return Refs.marker(%s, (salt | %s) ^ %s); }
                    static int orphan(int salt) { return Refs.marker(%s, (salt | %s) ^ %s); }
                    static String greet() { return dec(0x54, %s); }
                    static int number() { return num(0x66, 0x7777); }
                    public static void main(String[] args) {
                        System.out.println(weight(7, %s) + local() + callAnswer(%s) + greet() + number() + GREETING);
                    }
                }
                """.formatted(hex(FAKE), hex(HASH_PARAM),
                    escape(encrypt("ledger", 0x2222, "<clinit>")), escape(encrypt("hello", 0x3333, "greet")),
                    hex(1000 ^ 0x7777), hex(0x2222),
                    hex(MASK), hex(q ^ K0),
                    hex(K0), hex(MASK), hex(q ^ K1), hex(MASK), hex(q ^ K2),
                    hex(K1), hex(MASK), hex(q ^ 10),
                    hex(K2),
                    hex((SALT & 0x00FF00FF) ^ LOCAL_SALT), hex(MASK), hex((LOCAL_SALT | MASK) ^ 42),
                    hex(HASH_VALUE ^ 77),
                    hex(INDEX_KEY), hex(MASK), hex((CALL_SALT | MASK) ^ (DEC_KEY << 16)),
                    hex(INDEX_KEY), hex(MASK), hex((ORPHAN_SALT | MASK) ^ (DEC_KEY << 16)),
                    hex(0x3333), hex(WEIGHT_SALT), hex(CALL_SALT)));
        var src = Files.createDirectories(dir.resolve("src"));
        var files = new ArrayList<String>();
        for (var source : sources.entrySet()) {
            var file = src.resolve(source.getKey());
            Files.createDirectories(file.getParent());
            Files.writeString(file, source.getValue());
            files.add(file.toString());
        }
        var classes = Files.createDirectories(dir.resolve("classes"));
        var args = new ArrayList<>(List.of("--release", "8", "-Xlint:-options", "-proc:none", "-nowarn", "-d", classes.toString()));
        args.addAll(files);
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, args.toArray(String[]::new)));
        var entries = new LinkedHashMap<String,byte[]>();
        for (String name : List.of("d/Dispatcher", "app/Hash", "app/Refs")) entries.put(name + ".class", Files.readAllBytes(classes.resolve(name + ".class")));
        // Refs.marker(index, key) becomes invokedynamic "<static kind>" (II)I through the bootstrap.
        var app = new ClassNode();
        new ClassReader(Files.readAllBytes(classes.resolve("app/App.class"))).accept(app, 0);
        var bootstrap = new Handle(H_INVOKESTATIC, "app/Refs", "bootstrap",
            "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;", false);
        for (var method : app.methods)
            for (var insn : method.instructions.toArray())
                if (insn instanceof MethodInsnNode call && call.name.equals("marker"))
                    method.instructions.set(insn, new InvokeDynamicInsnNode(String.valueOf(STATIC_KIND), "(II)I", bootstrap));
        var writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        app.accept(writer);
        byte[] appBytes = writer.toByteArray();
        entries.put("app/App.class", appBytes);
        // The hash resource: SHA-256 hex of App.class, then HASH_VALUE ^ hex.hashCode() ^ param.
        String sha = AidsfuscatorReverser.sha256(appBytes);
        var resource = new java.io.ByteArrayOutputStream();
        resource.write(sha.getBytes(StandardCharsets.ISO_8859_1));
        int stored = HASH_VALUE ^ sha.hashCode() ^ HASH_PARAM;
        resource.write(new byte[] {(byte) (stored >> 24), (byte) (stored >> 16), (byte) (stored >> 8), (byte) stored});
        entries.put("h.bin", resource.toByteArray());
        return entries;
    }

    private static MethodNode method(ClassNode node, String name) {
        return node.methods.stream().filter(m -> m.name.equals(name)).findFirst().orElseThrow(() -> new AssertionError("no " + name));
    }
    private static List<Integer> opcodes(MethodNode method) {
        var result = new ArrayList<Integer>();
        for (var insn : method.instructions) if (insn.getOpcode() >= 0) result.add(insn.getOpcode());
        return result;
    }
    private static Integer returnedConstant(MethodNode method) {
        var real = new ArrayList<AbstractInsnNode>();
        for (var insn : method.instructions) if (insn.getOpcode() >= 0) real.add(insn);
        return real.size() == 2 && real.get(1).getOpcode() == IRETURN ? SkidModel.literal(real.getFirst()) : null;
    }

    @Test void theSyntheticJarIsReversedLinkedAndCleaned() throws Exception {
        var result = new AidsfuscatorReverser().apply(obfuscatedJar(), 10_000);
        var report = result.report();
        assertTrue(report.detected(), report.toString());
        assertEquals(List.of(), report.refused(), report.toString());
        assertEquals(1, report.count("class salts resolved"), report.toString());
        assertEquals(1, report.count("hash fields resolved"), report.toString());
        assertEquals(2, report.count("references decoded"), report.toString());
        assertEquals(1, report.count("of which with a searched key (no call reveals the salt)"), report.toString());
        assertEquals(0, report.count("references not decoded"), report.toString());
        assertEquals(2, report.count("strings decrypted"), report.toString());
        assertEquals(1, report.count("numbers decrypted"), report.toString());
        assertEquals(0, report.count("class write failures"), report.toString());
        SkidfuscatorReverserTest.link(result.entries());

        // The dispatcher, the reference bootstrap and the hash class and its resource are gone.
        assertEquals(Set.of("app/App.class"), result.entries().keySet());
        var app = SkidfuscatorReverserTest.node(result.entries().get("app/App.class"));
        assertEquals(List.of("GREETING"), app.fields.stream().map(f -> f.name).toList());
        assertTrue(app.methods.stream().noneMatch(m -> m.name.equals("dec") || m.name.equals("num")), "decryptors removed");
        // Salt parameters are gone from the declarations and the calls; the method nothing calls too (its salt was searched).
        assertEquals("(I)I", method(app, "weight").desc);
        assertEquals("()I", method(app, "callAnswer").desc);
        assertEquals("()I", method(app, "orphan").desc);
        // Flattening and salts folded: weight has no dispatcher left and returns 10 as a constant.
        var weight = opcodes(method(app, "weight"));
        assertFalse(weight.contains(LOOKUPSWITCH) || weight.contains(TABLESWITCH), weight.toString());
        assertTrue(Arrays.stream(method(app, "weight").instructions.toArray()).anyMatch(i -> Integer.valueOf(10).equals(SkidModel.literal(i))));
        assertEquals(42, returnedConstant(method(app, "local")));
        assertEquals(77, returnedConstant(method(app, "hashed")));
        // References are calls again.
        for (String name : List.of("callAnswer", "orphan")) {
            var calls = Arrays.stream(method(app, name).instructions.toArray()).filter(i -> i instanceof MethodInsnNode).map(i -> (MethodInsnNode) i).toList();
            assertEquals(1, calls.size(), name);
            assertEquals("app/App.answer()I", calls.getFirst().owner + "." + calls.getFirst().name + calls.getFirst().desc);
        }
        // Strings and numbers are literals; the initializer is gone from <clinit>.
        assertEquals(Set.of("ledger", "hello"), SkidfuscatorReverserTest.literals(app));
        assertEquals(1000, returnedConstant(method(app, "number")));
        assertEquals(List.of(LDC, PUTSTATIC, RETURN), opcodes(method(app, "<clinit>")));
    }

    @Test void aJarWithoutAidsfuscatorIsLeftAsItIs() throws Exception {
        var src = Files.createDirectories(dir.resolve("plain"));
        Files.writeString(src.resolve("Plain.java"), "public class Plain { static int twice(int x) { int k = 100000; return x * 2 + k; } }");
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "--release", "8", "-Xlint:-options", "-proc:none", "-d",
            src.toString(), src.resolve("Plain.java").toString()));
        var entries = Map.of("Plain.class", Files.readAllBytes(src.resolve("Plain.class")));
        var result = new AidsfuscatorReverser().apply(entries, 100);
        assertFalse(result.report().detected());
        assertSame(entries, result.entries());
    }

    @Test void flowIntsMaskedLongsFoldBackToTheLong() {
        long original = 0x5DEECE66DL;
        int block = 0x13572468;
        long mask = block & 0xFFFFFFFFL;
        long obf = ((original << 32 >>> 32) ^ mask) | (((original >>> 32) ^ mask) << 32);
        var method = new MethodNode(ACC_STATIC, "m", "()J", null, null);
        var insns = method.instructions;
        insns.add(new LdcInsnNode(obf)); insns.add(new InsnNode(DUP2)); insns.add(new IntInsnNode(BIPUSH, 32)); insns.add(new InsnNode(LSHL));
        insns.add(new IntInsnNode(BIPUSH, 32)); insns.add(new InsnNode(LUSHR)); insns.add(new LdcInsnNode(block)); insns.add(new InsnNode(I2L));
        insns.add(new LdcInsnNode(0xFFFFFFFFL)); insns.add(new InsnNode(LAND)); insns.add(new InsnNode(LXOR)); insns.add(new InsnNode(DUP2_X2));
        insns.add(new InsnNode(POP2)); insns.add(new IntInsnNode(BIPUSH, 32)); insns.add(new InsnNode(LUSHR)); insns.add(new LdcInsnNode(block));
        insns.add(new InsnNode(I2L)); insns.add(new LdcInsnNode(0xFFFFFFFFL)); insns.add(new InsnNode(LAND)); insns.add(new InsnNode(LXOR));
        insns.add(new IntInsnNode(BIPUSH, 32)); insns.add(new InsnNode(LSHL)); insns.add(new InsnNode(LOR)); insns.add(new InsnNode(LRETURN));
        assertEquals(1, new AidsConstants().foldMaskedLongs(method));
        assertEquals(2, method.instructions.size());
        assertEquals(original, ((LdcInsnNode) method.instructions.getFirst()).cst);
    }

    @Test void reusedIntegersArePutBackOnlyForLocalsSetOnce() {
        var method = new MethodNode(ACC_STATIC, "m", "(I)I", null, null);
        var insns = method.instructions;
        insns.add(new LdcInsnNode(123456)); insns.add(new VarInsnNode(ISTORE, 1));
        insns.add(new LdcInsnNode(654321)); insns.add(new VarInsnNode(ISTORE, 2));
        insns.add(new VarInsnNode(ILOAD, 1)); insns.add(new VarInsnNode(ILOAD, 1)); insns.add(new InsnNode(IADD));
        insns.add(new VarInsnNode(ILOAD, 2)); insns.add(new InsnNode(IADD)); insns.add(new IincInsnNode(2, 1));
        insns.add(new InsnNode(IRETURN));
        // Local 1 is only loaded: put back. Local 2 is incremented: it is the program's and stays.
        assertEquals(1, AidsfuscatorReverser.inlineReusedIntegers(method));
        assertEquals(List.of(LDC, ISTORE, LDC, LDC, IADD, ILOAD, IADD, IINC, IRETURN), opcodes(method));
    }

    @Test void aGenericSignatureLosesTheAppendedSaltOnly() {
        assertEquals("()Ljava/util/List<Ljava/lang/String;>;", AidsParameters.dropLastInt("(I)Ljava/util/List<Ljava/lang/String;>;"));
        assertEquals("(Ljava/util/Map<TK;TV;>;)V", AidsParameters.dropLastInt("(Ljava/util/Map<TK;TV;>;I)V"));
        assertEquals("([I)V", AidsParameters.dropLastInt("([I)V"));
        assertEquals(2, AidsParameters.parameterCount("<T:Ljava/lang/Object;>(Ljava/util/Map<TT;Ljava/util/List<TT;>;>;[I)TT;"));
    }
}
