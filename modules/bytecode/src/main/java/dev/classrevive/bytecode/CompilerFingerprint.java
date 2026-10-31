package dev.classrevive.bytecode;

import java.util.*;
import java.util.regex.Pattern;
import org.objectweb.asm.*;

/**
 * Phase 2CJ: which compiler produced the classes, from traces each compiler leaves in the bytecode. ASM reads the
 * class bytes; nothing is defined, loaded or run. Every finding is a count of classes with a documented signal, so a
 * verdict can be checked.
 * <ul>
 * <li><b>Kotlin</b>: the {@code @kotlin.Metadata} annotation kotlinc puts on every class.</li>
 * <li><b>Scala</b>: the {@code ScalaSig}, {@code Scala} or {@code TASTY} attribute, or {@code @ScalaSignature}.</li>
 * <li><b>Groovy</b>: {@code groovy.lang.GroovyObject} implemented, or the {@code $staticClassInfo} field.</li>
 * <li><b>javac versus ecj</b> (Eclipse), for Java classes: javac names lambda bodies {@code lambda$method$N}, keeps
 * enum switch maps in {@code $SwitchMap$...} fields and builds {@code values()} with {@code $VALUES.clone()}; ecj
 * names them {@code lambda$N}, uses {@code $SWITCH_TABLE$...} methods and copies with {@code System.arraycopy}.</li>
 * <li><b>javac version</b>, a lower bound: string concatenation through {@code StringConcatFactory} (javac 9+) and
 * the synthetic enum {@code $values()} method (javac 15+).</li>
 * <li><b>Debug information</b>: {@code SourceFile}, line numbers and local variable tables. Without local variable
 * tables every decompiler invents local names.</li>
 * </ul>
 */
public final class CompilerFingerprint {
    private CompilerFingerprint() {}

    private static final Pattern JAVAC_LAMBDA = Pattern.compile("lambda\\$[^$]+\\$\\d+"), ECJ_LAMBDA = Pattern.compile("lambda\\$\\d+");

    /** Class counts per finding; {@code classVersions}: Java version of the class files to class count. */
    public record Result(int classes, int kotlin, int scala, int groovy, int java, int javacSignals, int ecjSignals,
                         int javacAtLeast, int withSourceFile, int withLineNumbers, int withLocalVariables, int withCode,
                         SortedMap<Integer,Integer> classVersions) {
        /** {@code javac}, {@code ecj}, {@code mixed} or {@code undetermined}, for the Java classes. */
        public String javaCompiler() {
            if (javacSignals > 0 && ecjSignals == 0) return "javac";
            if (ecjSignals > 0 && javacSignals == 0) return "ecj";
            return javacSignals > 0 ? "mixed" : "undetermined";
        }
        /** The main language by class count. */
        public String language() {
            int best = Math.max(Math.max(kotlin, scala), Math.max(groovy, java));
            if (classes == 0) return "none";
            return best == kotlin && kotlin > 0 ? "Kotlin" : best == scala && scala > 0 ? "Scala" : best == groovy && groovy > 0 ? "Groovy" : "Java";
        }
    }

    public static Result analyze(Collection<byte[]> classFiles) {
        int classes = 0, kotlin = 0, scala = 0, groovy = 0, java = 0, javac = 0, ecj = 0, atLeast = 0;
        int sourceFile = 0, lines = 0, locals = 0, code = 0;
        var versions = new TreeMap<Integer,Integer>();
        for (byte[] bytes : classFiles) {
            var visitor = new Probe();
            try { new ClassReader(bytes).accept(visitor, ClassReader.SKIP_FRAMES); }
            catch (RuntimeException unreadable) { continue; } // A class ASM cannot parse gives no evidence.
            classes++;
            versions.merge(visitor.javaVersion, 1, Integer::sum);
            if (visitor.kotlin) kotlin++;
            else if (visitor.scala) scala++;
            else if (visitor.groovy) groovy++;
            else {
                java++;
                if (visitor.javac) javac++;
                if (visitor.ecj) ecj++;
                if (!visitor.ecj) atLeast = Math.max(atLeast, visitor.javacAtLeast);
            }
            if (visitor.sourceFile) sourceFile++;
            if (visitor.methodsWithCode > 0) {
                code++;
                if (visitor.methodsWithLines > 0) lines++;
                if (visitor.methodsWithLocals > 0) locals++;
            }
        }
        return new Result(classes, kotlin, scala, groovy, java, javac, ecj, atLeast, sourceFile, lines, locals, code,
            Collections.unmodifiableSortedMap(versions));
    }

    /** Readable lines for reports. */
    public static String text(Result result) {
        if (result.classes() == 0) return "";
        var out = new StringBuilder("Compiler fingerprint: ").append(result.language());
        if (result.java() > 0 && result.javaCompiler().equals("undetermined")) {
            out.append("; Java compiler undetermined (no javac or ecj signal in ").append(result.java()).append(" Java classes)");
        } else if (result.java() > 0) {
            out.append("; Java classes by ").append(result.javaCompiler());
            if (result.javaCompiler().equals("javac") && result.javacAtLeast() > 0) out.append(' ').append(result.javacAtLeast()).append('+');
            out.append(" (javac signals in ").append(result.javacSignals()).append(", ecj signals in ").append(result.ecjSignals()).append(" classes)");
        }
        if (result.kotlin() + result.scala() + result.groovy() > 0)
            out.append("; Kotlin ").append(result.kotlin()).append(", Scala ").append(result.scala()).append(", Groovy ").append(result.groovy()).append(" classes");
        out.append("\n  Class files: ");
        var versions = new StringJoiner(", ");
        result.classVersions().forEach((version, count) -> versions.add("Java " + version + " (" + count + ")"));
        out.append(versions).append("\n  Debug information: source file in ").append(result.withSourceFile()).append('/').append(result.classes())
            .append(" classes, line numbers in ").append(result.withLineNumbers()).append('/').append(result.withCode())
            .append(", local variable names in ").append(result.withLocalVariables()).append('/').append(result.withCode());
        if (result.withCode() > 0 && result.withLocalVariables() * 2 < result.withCode())
            out.append(" (most local variable names were not compiled in: decompilers invent them)");
        if (result.kotlin() > 0) out.append("\n  Kotlin classes decompile to Java: expect Intrinsics null checks, companion objects and synthetic accessors.");
        return out.append('\n').toString();
    }

    public static Map<String,Object> json(Result result) {
        var data = new TreeMap<String,Object>();
        data.put("classes", result.classes()); data.put("language", result.language());
        data.put("kotlin_classes", result.kotlin()); data.put("scala_classes", result.scala()); data.put("groovy_classes", result.groovy());
        data.put("java_classes", result.java()); data.put("java_compiler", result.javaCompiler());
        data.put("javac_signal_classes", result.javacSignals()); data.put("ecj_signal_classes", result.ecjSignals());
        data.put("javac_at_least", result.javacAtLeast() == 0 ? null : result.javacAtLeast());
        var versions = new TreeMap<String,Object>();
        result.classVersions().forEach((version, count) -> versions.put(Integer.toString(version), count));
        data.put("class_file_versions", versions);
        data.put("debug", Map.of("source_file", result.withSourceFile(), "line_numbers", result.withLineNumbers(),
            "local_variables", result.withLocalVariables(), "classes_with_code", result.withCode()));
        data.put("method", "documented bytecode traces: kotlin.Metadata, Scala signature attributes, GroovyObject, javac/ecj naming of "
            + "lambdas, enum switch maps and values(), StringConcatFactory (javac 9+), enum $values() (javac 15+); a hypothesis, not proof");
        return data;
    }

    /** Phase 2DC: a class kotlinc compiled (it carries {@code kotlin.Metadata}). */
    public static boolean kotlin(org.objectweb.asm.tree.ClassNode node) {
        for (var list : Arrays.asList(node.visibleAnnotations, node.invisibleAnnotations))
            if (list != null) for (var annotation : list) if (annotation.desc.equals("Lkotlin/Metadata;")) return true;
        return false;
    }

    private static final class Probe extends ClassVisitor {
        boolean kotlin, scala, groovy, javac, ecj, sourceFile, isEnum;
        int javaVersion, javacAtLeast, methodsWithCode, methodsWithLines, methodsWithLocals;
        Probe() { super(Opcodes.ASM9); }

        @Override public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
            javaVersion = Math.max(1, (version & 0xFFFF) - 44);
            isEnum = (access & Opcodes.ACC_ENUM) != 0;
            if (interfaces != null) for (String type : interfaces) if (type.equals("groovy/lang/GroovyObject")) groovy = true;
        }
        @Override public void visitSource(String source, String debug) { if (source != null) sourceFile = true; }
        @Override public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
            if (descriptor.equals("Lkotlin/Metadata;")) kotlin = true;
            if (descriptor.equals("Lscala/reflect/ScalaSignature;") || descriptor.equals("Lscala/reflect/ScalaLongSignature;")) scala = true;
            return null;
        }
        @Override public void visitAttribute(Attribute attribute) {
            if (attribute.type.equals("ScalaSig") || attribute.type.equals("Scala") || attribute.type.equals("TASTY")) scala = true;
        }
        @Override public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
            if (name.equals("$staticClassInfo")) groovy = true;
            if (name.startsWith("$SwitchMap$") && (access & Opcodes.ACC_SYNTHETIC) != 0) javac = true;
            return null;
        }
        @Override public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
            boolean synthetic = (access & Opcodes.ACC_SYNTHETIC) != 0;
            if (synthetic && JAVAC_LAMBDA.matcher(name).matches()) javac = true;
            if (synthetic && ECJ_LAMBDA.matcher(name).matches()) ecj = true;
            if (name.startsWith("$SWITCH_TABLE$") && synthetic) ecj = true;
            if (isEnum && name.equals("$values") && synthetic) { javac = true; javacAtLeast = Math.max(javacAtLeast, 15); }
            boolean enumValues = isEnum && name.equals("values") && descriptor.startsWith("()[");
            return new MethodVisitor(Opcodes.ASM9) {
                boolean hasCode, hasLines, hasLocals;
                @Override public void visitCode() { hasCode = true; }
                @Override public void visitLineNumber(int line, Label start) { hasLines = true; }
                @Override public void visitLocalVariable(String local, String type, String generic, Label start, Label end, int index) { hasLocals = true; }
                @Override public void visitMethodInsn(int opcode, String owner, String method, String desc, boolean isInterface) {
                    if (!enumValues) return;
                    if (owner.equals("java/lang/System") && method.equals("arraycopy")) ecj = true;
                    if (method.equals("clone") && owner.startsWith("[")) javac = true;
                }
                @Override public void visitInvokeDynamicInsn(String method, String desc, Handle bootstrap, Object... arguments) {
                    if (bootstrap.getOwner().equals("java/lang/invoke/StringConcatFactory")) javacAtLeast = Math.max(javacAtLeast, 9);
                }
                @Override public void visitEnd() {
                    if (hasCode) { methodsWithCode++; if (hasLines) methodsWithLines++; if (hasLocals) methodsWithLocals++; }
                }
            };
        }
    }
}
