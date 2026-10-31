package dev.classrevive.bytecode;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

class DynamicScannerTest {
    @org.junit.jupiter.api.io.TempDir java.nio.file.Path directory;
    @Test void serializesBoundedEvidenceAsPartialInsteadOfOversizedReport() throws Exception {
        var rows=new java.util.ArrayList<DynamicWorker.Row>();
        var finding=new DynamicScanner().scan(fixture(),100).findings().getFirst();
        for(int i=0;i<20;i++) rows.add(new DynamicWorker.Row("entry.class",finding));
        var path=directory.resolve("bounded.bin");
        DynamicWorker.writeAudit(path,1,false,rows,512);
        assertTrue(java.nio.file.Files.size(path)<=512);
        try(var in=new java.io.DataInputStream(java.nio.file.Files.newInputStream(path))) {
            assertEquals(1,in.readInt()); assertEquals(1,in.readInt()); assertTrue(in.readBoolean());
            int count=in.readInt(); assertTrue(count>0 && count<20);
        }
    }
    byte[] fixture() {
        var writer=new ClassWriter(0); writer.visit(V11,ACC_PUBLIC,"Dynamic",null,"java/lang/Object",null);
        var method=writer.visitMethod(ACC_PUBLIC|ACC_STATIC,"test","()V",null,null); method.visitCode();
        method.visitInvokeDynamicInsn("call","()Ljava/lang/Runnable;",new Handle(H_INVOKESTATIC,"example/Bootstrap","link","()Ljava/lang/invoke/CallSite;",false));
        method.visitInsn(POP);
        method.visitLdcInsn(new ConstantDynamic("constant","Ljava/lang/Object;",new Handle(H_INVOKESTATIC,"example/Bootstrap","constant","()Ljava/lang/Object;",false)));
        method.visitInsn(POP); method.visitLdcInsn("sensitive.Name");
        method.visitMethodInsn(INVOKESTATIC,"java/lang/Class","forName","(Ljava/lang/String;)Ljava/lang/Class;",false);
        method.visitInsn(POP); method.visitInsn(RETURN); method.visitMaxs(2,0); method.visitEnd(); writer.visitEnd();
        return writer.toByteArray();
    }
    @Test void reportsBootstrapAndReflectionWithoutRunningThem() {
        var result=new DynamicScanner().scan(fixture(),100);
        assertEquals(3,result.findings().size()); assertFalse(result.limitReached());
        assertEquals("INVOKEDYNAMIC",result.findings().get(0).kind());
        assertEquals("CONSTANT_DYNAMIC",result.findings().get(1).kind());
        assertEquals("REFLECTION_API",result.findings().get(2).kind());
        assertFalse(result.toString().contains("sensitive.Name"));
        assertEquals("example/Bootstrap",result.findings().getFirst().owner());
    }
    @Test void boundsFindingsAndPreservesInstructionOrdinals() {
        var result=new DynamicScanner().scan(fixture(),1);
        assertEquals(1,result.findings().size()); assertTrue(result.limitReached());
        assertEquals(0,result.findings().getFirst().instruction());
        assertThrows(IllegalArgumentException.class,()->new DynamicScanner().scan(fixture(),0));
    }
    @Test void ignoresOrdinaryCallsAndFindsNestedDynamicConstants() {
        var writer=new ClassWriter(0); writer.visit(V11,ACC_PUBLIC,"Nested",null,"java/lang/Object",null);
        var method=writer.visitMethod(ACC_PUBLIC|ACC_STATIC,"test","()V",null,null); method.visitCode();
        method.visitLdcInsn("text"); method.visitMethodInsn(INVOKEVIRTUAL,"java/lang/String","length","()I",false); method.visitInsn(POP);
        var bootstrap=new Handle(H_INVOKESTATIC,"example/Bootstrap","link","()Ljava/lang/Object;",false);
        method.visitLdcInsn(new ConstantDynamic("outer","Ljava/lang/Object;",bootstrap,new ConstantDynamic("inner","Ljava/lang/Object;",bootstrap)));
        method.visitInsn(POP); method.visitInsn(RETURN); method.visitMaxs(2,0); method.visitEnd(); writer.visitEnd();
        var result=new DynamicScanner().scan(writer.toByteArray(),100);
        assertEquals(2,result.findings().size());
        assertEquals(3,result.findings().getFirst().instruction());
        assertEquals(1,result.findings().getFirst().bootstrapArguments());
    }

    @Test void reportsExactClassLoadingAndCryptoApiReferencesAsStaticSignals() {
        var writer=new ClassWriter(0); writer.visit(V11,ACC_PUBLIC,"ApiSignals",null,"java/lang/ClassLoader",null);
        var method=writer.visitMethod(ACC_PUBLIC|ACC_STATIC,"test","()V",null,null); method.visitCode();
        method.visitLdcInsn("x");
        method.visitMethodInsn(INVOKEVIRTUAL,"ApiSignals","loadClass","(Ljava/lang/String;)Ljava/lang/Class;",false);
        method.visitInsn(POP);
        method.visitInsn(ACONST_NULL); method.visitInsn(ICONST_0); method.visitInsn(ICONST_0);
        method.visitMethodInsn(INVOKEVIRTUAL,"ApiSignals","defineClass","([BII)Ljava/lang/Class;",false);
        method.visitInsn(POP);
        method.visitInsn(ACONST_NULL); method.visitInsn(ICONST_0); method.visitInsn(ICONST_0);
        method.visitMethodInsn(INVOKESTATIC,"example/Codec","defineClass","([BII)Ljava/lang/Class;",false);
        method.visitInsn(POP);
        method.visitLdcInsn("AES");
        method.visitMethodInsn(INVOKESTATIC,"javax/crypto/Cipher","getInstance","(Ljava/lang/String;)Ljavax/crypto/Cipher;",false);
        method.visitInsn(ACONST_NULL);
        method.visitMethodInsn(INVOKEVIRTUAL,"javax/crypto/Cipher","doFinal","([B)[B",false);
        method.visitInsn(POP); method.visitInsn(RETURN); method.visitMaxs(4,0); method.visitEnd(); writer.visitEnd();

        var findings=new DynamicScanner().scan(writer.toByteArray(),100).findings();
        assertEquals(List.of("CLASS_LOADING_API","CLASS_LOADING_API","CRYPTO_API","CRYPTO_API"),findings.stream().map(DynamicScanner.Finding::kind).toList());
        assertEquals(List.of("loadClass","defineClass","getInstance","doFinal"),findings.stream().map(DynamicScanner.Finding::name).toList());
    }

    @Test void reportsClassLoadingThroughJdkLoaderSubclassesBufferOverloadAndUnsafe() {
        var writer=new ClassWriter(0); writer.visit(V11,ACC_PUBLIC,"Loader",null,"java/net/URLClassLoader",null);
        var method=writer.visitMethod(ACC_PUBLIC,"test","()V",null,null); method.visitCode();
        method.visitVarInsn(ALOAD,0); method.visitInsn(ACONST_NULL); method.visitInsn(ACONST_NULL); method.visitInsn(ACONST_NULL);
        method.visitMethodInsn(INVOKEVIRTUAL,"Loader","defineClass","(Ljava/lang/String;Ljava/nio/ByteBuffer;Ljava/security/ProtectionDomain;)Ljava/lang/Class;",false);
        method.visitInsn(POP);
        method.visitVarInsn(ALOAD,0); method.visitInsn(ACONST_NULL); method.visitInsn(ACONST_NULL); method.visitInsn(ICONST_0); method.visitInsn(ICONST_0); method.visitInsn(ACONST_NULL);
        method.visitMethodInsn(INVOKESPECIAL,"java/security/SecureClassLoader","defineClass","(Ljava/lang/String;[BIILjava/security/CodeSource;)Ljava/lang/Class;",false);
        method.visitInsn(POP);
        method.visitInsn(ACONST_NULL); method.visitInsn(ACONST_NULL); method.visitInsn(ACONST_NULL); method.visitInsn(ACONST_NULL);
        method.visitMethodInsn(INVOKEVIRTUAL,"sun/misc/Unsafe","defineAnonymousClass","(Ljava/lang/Class;[B[Ljava/lang/Object;)Ljava/lang/Class;",false);
        method.visitInsn(POP);
        method.visitVarInsn(ALOAD,0); method.visitInsn(ACONST_NULL);
        method.visitMethodInsn(INVOKEVIRTUAL,"Loader","defineClass","(Ljava/lang/String;)Ljava/lang/Class;",false);
        method.visitInsn(POP); method.visitInsn(RETURN); method.visitMaxs(7,1); method.visitEnd(); writer.visitEnd();

        var findings=new DynamicScanner().scan(writer.toByteArray(),100).findings();
        assertEquals(List.of("CLASS_LOADING_API","CLASS_LOADING_API","CLASS_LOADING_API"),findings.stream().map(DynamicScanner.Finding::kind).toList());
        assertEquals(List.of("Loader","java/security/SecureClassLoader","sun/misc/Unsafe"),findings.stream().map(DynamicScanner.Finding::owner).toList());
    }

    @Test void doesNotTreatUnrelatedSuperclassesAsClassLoaders() {
        var writer=new ClassWriter(0); writer.visit(V11,ACC_PUBLIC,"Plain",null,"example/Base",null);
        var method=writer.visitMethod(ACC_PUBLIC,"test","()V",null,null); method.visitCode();
        method.visitVarInsn(ALOAD,0); method.visitInsn(ACONST_NULL); method.visitInsn(ACONST_NULL); method.visitInsn(ACONST_NULL);
        method.visitMethodInsn(INVOKEVIRTUAL,"Plain","defineClass","(Ljava/lang/String;Ljava/nio/ByteBuffer;Ljava/security/ProtectionDomain;)Ljava/lang/Class;",false);
        method.visitInsn(POP); method.visitInsn(RETURN); method.visitMaxs(4,1); method.visitEnd(); writer.visitEnd();
        assertTrue(new DynamicScanner().scan(writer.toByteArray(),100).findings().isEmpty());
    }
}
