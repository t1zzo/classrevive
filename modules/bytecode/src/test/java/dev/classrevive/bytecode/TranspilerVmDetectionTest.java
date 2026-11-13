package dev.classrevive.bytecode;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/**
 * Detection of native-obfuscator (and native-obfuscator-plus), j2cc, BytecodeVM and the alpheratz obfuscator over class
 * sets written in their shapes with ASM (no tool output is used; nothing is loaded or run).
 */
class TranspilerVmDetectionTest {
    private static byte[] write(ClassNode node) { var writer = new ClassWriter(ClassWriter.COMPUTE_MAXS); node.accept(writer); return writer.toByteArray(); }
    private static ClassNode type(String name) {
        var node = new ClassNode(); node.version = V1_8; node.access = ACC_PUBLIC | ACC_SUPER; node.name = name; node.superName = "java/lang/Object"; return node;
    }
    private static MethodNode method(ClassNode node, int access, String name, String desc) {
        var method = new MethodNode(access, name, desc, null, null); node.methods.add(method); return method;
    }
    private static ObfuscationProfile profile(List<ClassNode> nodes, String... resources) {
        var builder = new ObfuscationProfile.Builder();
        for (var node : nodes) builder.addClass(write(node));
        for (String resource : resources) builder.addResource(resource, 100);
        return builder.build();
    }
    private static Optional<ObfuscatorClassifier.Hypothesis> hypothesis(ObfuscationProfile profile, String name) {
        return ObfuscatorClassifier.classify(profile).hypotheses().stream().filter(h -> h.obfuscator().equals(name)).findFirst();
    }
    private static String top(ObfuscationProfile profile) {
        return ObfuscatorClassifier.classify(profile).hypotheses().getFirst().obfuscator();
    }

    /** native-obfuscator's output: its loader under a package of the user's choice, and classes registering their natives. */
    private static List<ClassNode> nativeObfuscatorShapes(String loaderPackage) {
        var loader = type(loaderPackage + "/Loader");
        method(loader, ACC_PUBLIC | ACC_STATIC | ACC_NATIVE, "registerNativesForClass", "(ILjava/lang/Class;)V");
        method(loader, ACC_PUBLIC | ACC_STATIC, "init", "()V").instructions.add(new InsnNode(RETURN));
        var nodes = new ArrayList<ClassNode>(List.of(loader));
        for (int k = 0; k < 3; k++) {
            var app = type("app/C" + k);
            app.sourceFile = "app_C" + k + ".cpp";
            var clinit = method(app, ACC_STATIC, "<clinit>", "()V");
            clinit.instructions.add(new LdcInsnNode(k));
            clinit.instructions.add(new LdcInsnNode(Type.getObjectType(app.name)));
            clinit.instructions.add(new MethodInsnNode(INVOKESTATIC, loader.name, "init", "()V", false));
            clinit.instructions.add(new MethodInsnNode(INVOKESTATIC, loader.name, "registerNativesForClass", "(ILjava/lang/Class;)V", false));
            clinit.instructions.add(new MethodInsnNode(INVOKESTATIC, app.name, "native_clinit" + k, "()V", false));
            clinit.instructions.add(new InsnNode(RETURN));
            method(app, ACC_PRIVATE | ACC_STATIC | ACC_NATIVE | ACC_SYNTHETIC, "native_clinit" + k, "()V");
            method(app, ACC_PUBLIC | ACC_NATIVE, "run", "()V");
            nodes.add(app);
        }
        return nodes;
    }

    @Test void nativeObfuscatorIsNamedWhateverItsLoaderPackage() {
        for (String pkg : List.of("native0", "antiLeak-0c1cfaa1/x")) {
            var profile = profile(nativeObfuscatorShapes(pkg), "libs/x64-windows.dll");
            var found = hypothesis(profile, "native-obfuscator (radioegor146 / native-obfuscator-plus)").orElseThrow();
            assertEquals("HIGH", found.confidence(), found.toString());
            assertEquals(found.obfuscator(), top(profile));
            // The generic native layer rule does not repeat it.
            assertTrue(ObfuscatorClassifier.classify(profile).hypotheses().stream().noneMatch(h -> h.obfuscator().contains("native protection layer")));
        }
    }

    @Test void j2ccByItsLoaderAndResources() {
        var loader = type("j/Loader");
        method(loader, ACC_PRIVATE | ACC_STATIC | ACC_NATIVE, "bootstrap", "([B)V");
        method(loader, ACC_PRIVATE | ACC_STATIC | ACC_NATIVE, "initClass", "(Ljava/lang/Class;)V");
        var doInit = method(loader, ACC_PUBLIC | ACC_STATIC, "doInit", "(Ljava/lang/Class;)V");
        doInit.instructions.add(new InsnNode(RETURN));
        var app = type("app/Main");
        var clinit = method(app, ACC_STATIC, "<clinit>", "()V");
        clinit.instructions.add(new LdcInsnNode(Type.getObjectType(app.name)));
        clinit.instructions.add(new MethodInsnNode(INVOKESTATIC, "j/Loader", "doInit", "(Ljava/lang/Class;)V", false));
        clinit.instructions.add(new InsnNode(RETURN));
        method(app, ACC_PUBLIC | ACC_STATIC | ACC_NATIVE, "main", "([Ljava/lang/String;)V");
        var profile = profile(List.of(loader, app), "j2cc/natives.bin", "j2cc/relocationInfo.dat");
        var found = hypothesis(profile, "j2cc").orElseThrow();
        assertEquals("HIGH", found.confidence(), found.toString());
        assertEquals("j2cc", top(profile));
    }

    @Test void j2ccMethodHandleCallsAloneAreRecognized() {
        var app = type("app/Calls");
        app.fields.add(new FieldNode(ACC_PRIVATE | ACC_STATIC | ACC_FINAL, "j2$mh", "[Ljava/lang/invoke/MethodHandle;", null, null));
        var filler = method(app, ACC_PRIVATE | ACC_STATIC, "j2$mh", "(I)Ljava/lang/invoke/MethodHandle;");
        filler.instructions.add(new InsnNode(ACONST_NULL)); filler.instructions.add(new InsnNode(ARETURN));
        var run = method(app, ACC_PUBLIC | ACC_STATIC, "run", "()V");
        for (int k = 0; k < 3; k++) {
            run.instructions.add(new FieldInsnNode(GETSTATIC, app.name, "j2$mh", "[Ljava/lang/invoke/MethodHandle;"));
            run.instructions.add(new IntInsnNode(SIPUSH, k)); run.instructions.add(new InsnNode(AALOAD)); run.instructions.add(new InsnNode(POP));
        }
        run.instructions.add(new InsnNode(RETURN));
        var found = hypothesis(profile(List.of(app)), "j2cc").orElseThrow();
        assertEquals("MEDIUM", found.confidence(), found.toString());
        assertTrue(found.reasons().stream().anyMatch(r -> r.contains("3 sites")), found.toString());
    }

    @Test void bytecodeVmByItsStubsAndCodePool() {
        String execute = "(ILjava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;";
        var vm = type("p/VM");
        method(vm, ACC_PUBLIC | ACC_STATIC, "execute", execute).instructions.add(new InsnNode(ACONST_NULL));
        vm.methods.getFirst().instructions.add(new InsnNode(ARETURN));
        var pool = type("p/VM$CodePool");
        var app = type("app/Logic");
        for (int k = 0; k < 3; k++) {
            var stub = method(app, ACC_PUBLIC | ACC_STATIC, "m" + k, "(I)I");
            stub.instructions.add(new InsnNode(ICONST_1)); stub.instructions.add(new TypeInsnNode(ANEWARRAY, "java/lang/Object")); stub.instructions.add(new VarInsnNode(ASTORE, 1));
            stub.instructions.add(new VarInsnNode(ALOAD, 1)); stub.instructions.add(new InsnNode(ICONST_0)); stub.instructions.add(new VarInsnNode(ILOAD, 0));
            stub.instructions.add(new MethodInsnNode(INVOKESTATIC, "java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;", false)); stub.instructions.add(new InsnNode(AASTORE));
            stub.instructions.add(new LdcInsnNode(100 + k)); stub.instructions.add(new InsnNode(ACONST_NULL)); stub.instructions.add(new VarInsnNode(ALOAD, 1));
            stub.instructions.add(new MethodInsnNode(INVOKESTATIC, "p/VM", "execute", execute, false));
            stub.instructions.add(new TypeInsnNode(CHECKCAST, "java/lang/Integer"));
            stub.instructions.add(new MethodInsnNode(INVOKEVIRTUAL, "java/lang/Integer", "intValue", "()I", false));
            stub.instructions.add(new InsnNode(IRETURN));
        }
        var profile = profile(List.of(vm, pool, app));
        var found = hypothesis(profile, "BytecodeVM (virtualization)").orElseThrow();
        assertEquals("HIGH", found.confidence(), found.toString());
        assertEquals(found.obfuscator(), top(profile));
    }

    @Test void alpheratzByItsTablesTrapsAndThrows() {
        var app = type("app/Obf");
        app.fields.add(new FieldNode(ACC_PRIVATE | ACC_STATIC, "nums", "[I", null, null));
        app.fields.add(new FieldNode(ACC_PUBLIC | ACC_STATIC | ACC_FINAL, "flag", "Z", null, null));
        var trash = List.of("java/lang/Exception", "java/io/IOException", "java/nio/BufferUnderflowException", "java/lang/ExceptionInInitializerError",
            "java/lang/ArrayIndexOutOfBoundsException", "java/lang/ArrayStoreException", "java/lang/annotation/AnnotationTypeMismatchException");
        for (int k = 0; k < 3; k++) {
            var m = method(app, ACC_PUBLIC | ACC_STATIC, "m" + k, "()I");
            m.exceptions = new ArrayList<>(trash);
            var end = new LabelNode();
            m.instructions.add(new FieldInsnNode(GETSTATIC, app.name, "flag", "Z"));
            m.instructions.add(new JumpInsnNode(IFEQ, end));
            m.instructions.add(new InsnNode(ACONST_NULL)); m.instructions.add(new InsnNode(ATHROW));
            m.instructions.add(end);
            m.instructions.add(new LdcInsnNode(1234 ^ 77 * 900)); m.instructions.add(new LdcInsnNode(77));
            m.instructions.add(new FieldInsnNode(GETSTATIC, app.name, "nums", "[I")); m.instructions.add(new LdcInsnNode(k));
            m.instructions.add(new InsnNode(IALOAD)); m.instructions.add(new InsnNode(IMUL)); m.instructions.add(new InsnNode(IXOR));
            m.instructions.add(new InsnNode(IRETURN));
        }
        var profile = profile(List.of(app));
        var found = hypothesis(profile, "alpheratz obfuscator").orElseThrow();
        assertEquals("HIGH", found.confidence(), found.toString());
        assertEquals(found.obfuscator(), top(profile));
    }

    @Test void anOrdinaryJniBindingIsNoneOfThem() {
        var binding = type("lib/Binding");
        method(binding, ACC_PRIVATE | ACC_STATIC | ACC_NATIVE, "registerNatives", "()V");
        method(binding, ACC_PUBLIC | ACC_STATIC | ACC_NATIVE, "open", "(Ljava/lang/String;)J");
        var names = ObfuscatorClassifier.classify(profile(List.of(binding), "lib/binding.so")).hypotheses().stream().map(ObfuscatorClassifier.Hypothesis::obfuscator).toList();
        assertTrue(names.stream().noneMatch(n -> n.startsWith("native-obfuscator") || n.equals("j2cc") || n.startsWith("BytecodeVM") || n.startsWith("alpheratz")), names.toString());
    }
}
