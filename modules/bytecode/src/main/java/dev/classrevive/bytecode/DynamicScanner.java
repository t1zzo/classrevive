package dev.classrevive.bytecode;

import org.objectweb.asm.*;
import java.util.*;
import static org.objectweb.asm.Opcodes.*;

/** Metadata-only linkage findings; no bootstrap or reflective invocation is performed. */
public final class DynamicScanner {
    public record Finding(String className,String method,String descriptor,int instruction,String kind,
                          String owner,String name,String targetDescriptor,int bootstrapArguments) {}
    public record Result(List<Finding> findings,boolean limitReached) { public Result { findings=List.copyOf(findings); } }
    public Result scan(byte[] bytes,int limit) {
        if(bytes.length>16*1024*1024 || limit<1 || limit>10000) throw new IllegalArgumentException("Invalid class or finding budget");
        var findings=new ArrayList<Finding>(); boolean[] limited={false};
        new ClassReader(bytes).accept(new ClassVisitor(ASM9) {
            String className, superName;
            @Override public void visit(int v,int a,String n,String s,String p,String[] i) { className=n; superName=p; }
            @Override public MethodVisitor visitMethod(int access,String method,String descriptor,String signature,String[] exceptions) {
                return new MethodVisitor(ASM9) {
                    int instruction;
                    void add(String kind,Handle handle,int arguments) {
                        if(findings.size()==limit) { limited[0]=true; return; }
                        findings.add(new Finding(className,method,descriptor,instruction,kind,handle.getOwner(),handle.getName(),handle.getDesc(),arguments));
                    }
                    void constant(Object value,int depth) {
                        if(!(value instanceof ConstantDynamic dynamic)) return;
                        if(depth>8 || findings.size()==limit) { limited[0]=true; return; }
                        add("CONSTANT_DYNAMIC",dynamic.getBootstrapMethod(),dynamic.getBootstrapMethodArgumentCount());
                        for(int i=0;i<dynamic.getBootstrapMethodArgumentCount();i++) constant(dynamic.getBootstrapMethodArgument(i),depth+1);
                    }
                    @Override public void visitInvokeDynamicInsn(String name,String desc,Handle bootstrap,Object... arguments) {
                        add("INVOKEDYNAMIC",bootstrap,arguments.length);
                        for(Object argument:arguments) constant(argument,0);
                        instruction++;
                    }
                    @Override public void visitLdcInsn(Object value) { constant(value,0); instruction++; }
                    @Override public void visitMethodInsn(int op,String owner,String name,String desc,boolean itf) {
                        String signal = staticApiSignal(owner,name,desc,className,superName);
                        if(signal != null)
                            add(signal,new Handle(H_INVOKESTATIC,owner,name,desc,itf),0);
                        else if(owner.startsWith("java/lang/reflect/") || owner.equals("java/lang/invoke/MethodHandles$Lookup") ||
                            (owner.equals("java/lang/Class") && (name.equals("forName") || name.startsWith("getDeclared") || name.equals("getMethod") || name.equals("getField") || name.equals("getConstructor") || name.equals("newInstance"))))
                            add("REFLECTION_API",new Handle(H_INVOKESTATIC,owner,name,desc,itf),0);
                        instruction++;
                    }
                    @Override public void visitInsn(int op) { instruction++; }
                    @Override public void visitIntInsn(int op,int operand) { instruction++; }
                    @Override public void visitVarInsn(int op,int variable) { instruction++; }
                    @Override public void visitTypeInsn(int op,String type) { instruction++; }
                    @Override public void visitFieldInsn(int op,String owner,String name,String desc) { instruction++; }
                    @Override public void visitJumpInsn(int op,Label label) { instruction++; }
                    @Override public void visitIincInsn(int variable,int increment) { instruction++; }
                    @Override public void visitTableSwitchInsn(int min,int max,Label d,Label... labels) { instruction++; }
                    @Override public void visitLookupSwitchInsn(Label d,int[] keys,Label[] labels) { instruction++; }
                    @Override public void visitMultiANewArrayInsn(String desc,int dimensions) { instruction++; }
                };
            }
        },ClassReader.SKIP_DEBUG|ClassReader.SKIP_FRAMES);
        return new Result(findings,limited[0] || findings.size()==limit);
    }

    /** JDK loaders whose loadClass/defineClass a subclass inherits. Deeper custom hierarchies need cross-class analysis. */
    private static final Set<String> JDK_CLASS_LOADERS=Set.of("java/lang/ClassLoader","java/security/SecureClassLoader","java/net/URLClassLoader");
    private static final Set<String> DEFINE_CLASS_DESCRIPTORS=Set.of("([BII)Ljava/lang/Class;","([BIILjava/security/ProtectionDomain;)Ljava/lang/Class;",
        "(Ljava/lang/String;[BII)Ljava/lang/Class;","(Ljava/lang/String;[BIILjava/security/ProtectionDomain;)Ljava/lang/Class;",
        "(Ljava/lang/String;Ljava/nio/ByteBuffer;Ljava/security/ProtectionDomain;)Ljava/lang/Class;",
        "(Ljava/lang/String;[BIILjava/security/CodeSource;)Ljava/lang/Class;",
        "(Ljava/lang/String;Ljava/nio/ByteBuffer;Ljava/security/CodeSource;)Ljava/lang/Class;");

    private static String staticApiSignal(String owner,String name,String descriptor,String className,String superName) {
        boolean classLoaderOwner=JDK_CLASS_LOADERS.contains(owner)
            || owner.equals(className) && JDK_CLASS_LOADERS.contains(superName);
        boolean classLoadingMethod=name.equals("loadClass")
                && (descriptor.equals("(Ljava/lang/String;)Ljava/lang/Class;")
                    || descriptor.equals("(Ljava/lang/String;Z)Ljava/lang/Class;"))
            || name.equals("defineClass") && DEFINE_CLASS_DESCRIPTORS.contains(descriptor);
        if(classLoaderOwner && classLoadingMethod
            || owner.equals("java/lang/invoke/MethodHandles$Lookup")
                && (name.equals("defineClass") || name.equals("defineHiddenClass") || name.equals("defineHiddenClassWithClassData"))
            || (owner.equals("sun/misc/Unsafe") || owner.equals("jdk/internal/misc/Unsafe"))
                && (name.equals("defineClass") || name.equals("defineAnonymousClass")))
            return "CLASS_LOADING_API";
        if(owner.equals("javax/crypto/Cipher") && Set.of("getInstance","init","update","updateAAD","doFinal","wrap","unwrap").contains(name)
            || owner.equals("javax/crypto/SecretKeyFactory") && Set.of("getInstance","generateSecret").contains(name)
            || owner.equals("javax/crypto/KeyGenerator") && Set.of("getInstance","generateKey","init").contains(name)
            || owner.equals("javax/crypto/spec/SecretKeySpec") && name.equals("<init>")
            || owner.equals("java/security/MessageDigest") && Set.of("getInstance","digest","update").contains(name)
            || owner.equals("java/security/KeyFactory") && Set.of("getInstance","generatePublic","generatePrivate").contains(name)
            || owner.equals("java/security/Signature") && Set.of("getInstance","initSign","initVerify","update","sign","verify").contains(name))
            return "CRYPTO_API";
        return null;
    }
}
