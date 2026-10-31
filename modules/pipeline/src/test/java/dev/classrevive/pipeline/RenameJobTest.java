package dev.classrevive.pipeline;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.jar.*;
import java.util.zip.*;
import java.io.*;
import static org.junit.jupiter.api.Assertions.*;

class RenameJobTest {
    @TempDir Path directory;
    byte[] type(String name,String fieldType) {
        var writer=new org.objectweb.asm.ClassWriter(0); writer.visit(52,1,name,null,"java/lang/Object",null);
        writer.visitField(1,"other","L"+fieldType+";",null,null).visitEnd(); writer.visitEnd(); return writer.toByteArray();
    }
    private static List<String> fieldDescriptors(byte[] bytes) {
        var result=new ArrayList<String>();
        new org.objectweb.asm.ClassReader(bytes).accept(new org.objectweb.asm.ClassVisitor(org.objectweb.asm.Opcodes.ASM9) {
            @Override public org.objectweb.asm.FieldVisitor visitField(int access,String name,String descriptor,String signature,Object value) {
                result.add(descriptor); return null;
            }
        },0);
        return result;
    }
    private static List<Object> constants(byte[] bytes) {
        var result=new ArrayList<Object>();
        new org.objectweb.asm.ClassReader(bytes).accept(new org.objectweb.asm.ClassVisitor(org.objectweb.asm.Opcodes.ASM9) {
            @Override public org.objectweb.asm.MethodVisitor visitMethod(int access,String name,String descriptor,String signature,String[] exceptions) {
                return new org.objectweb.asm.MethodVisitor(org.objectweb.asm.Opcodes.ASM9) {
                    @Override public void visitLdcInsn(Object value) { result.add(value); }
                };
            }
        },0);
        return result;
    }
    byte[] caller() {
        var writer=new org.objectweb.asm.ClassWriter(0); writer.visit(52,1,"p/caller",null,"java/lang/Object",null);
        var method=writer.visitMethod(9,"lookup","()Ljava/lang/Class;",null,null);
        method.visitCode(); method.visitLdcInsn("p.ab");
        method.visitMethodInsn(184,"java/lang/Class","forName","(Ljava/lang/String;)Ljava/lang/Class;",false);
        method.visitInsn(176); method.visitMaxs(1,0); method.visitEnd(); writer.visitEnd(); return writer.toByteArray();
    }
    byte[] memberType(String name) {
        var writer=new org.objectweb.asm.ClassWriter(0); writer.visit(52,1,name,null,"java/lang/Object",null);
        writer.visitField(2,"x","I",null,null).visitEnd();
        var getter=writer.visitMethod(2,"a","()I",null,null);
        getter.visitCode(); getter.visitVarInsn(25,0); getter.visitFieldInsn(180,name,"x","I"); getter.visitInsn(172); getter.visitMaxs(1,1);
        getter.visitEnd();
        var caller=writer.visitMethod(1,"run","()I",null,null);
        caller.visitCode(); caller.visitVarInsn(25,0); caller.visitMethodInsn(183,name,"a","()I",false); caller.visitInsn(172); caller.visitMaxs(1,1);
        caller.visitEnd(); writer.visitEnd(); return writer.toByteArray();
    }
    private static Set<String> memberNames(byte[] bytes,boolean fields) {
        var result=new TreeSet<String>();
        new org.objectweb.asm.ClassReader(bytes).accept(new org.objectweb.asm.ClassVisitor(org.objectweb.asm.Opcodes.ASM9) {
            @Override public org.objectweb.asm.FieldVisitor visitField(int access,String name,String descriptor,String signature,Object value) {
                if(fields) result.add(name); return null;
            }
            @Override public org.objectweb.asm.MethodVisitor visitMethod(int access,String name,String descriptor,String signature,String[] exceptions) {
                if(!fields) result.add(name); return null;
            }
        },0);
        return result;
    }
    Path fixture(boolean signed) throws Exception {
        Path input=directory.resolve("input.jar");
        var manifest=new Manifest(); manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION,"1.0");
        manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS,"p.ab");
        var header=new ByteArrayOutputStream(); manifest.write(header);
        try(var zip=new ZipOutputStream(Files.newOutputStream(input))) {
            zip.putNextEntry(new ZipEntry("p/ab.class")); zip.write(type("p/ab","p/cd")); zip.closeEntry();
            zip.putNextEntry(new ZipEntry("p/cd.class")); zip.write(type("p/cd","p/ab")); zip.closeEntry();
            zip.putNextEntry(new ZipEntry("p/caller.class")); zip.write(caller()); zip.closeEntry();
            zip.putNextEntry(new ZipEntry("asset.txt")); zip.write(new byte[]{0,1,2,3}); zip.closeEntry();
            zip.putNextEntry(new ZipEntry("META-INF/MANIFEST.MF")); zip.write(header.toByteArray()); zip.closeEntry();
            zip.putNextEntry(new ZipEntry("META-INF/services/p.ab")); zip.write("p.cd\n".getBytes(java.nio.charset.StandardCharsets.UTF_8)); zip.closeEntry();
            if(signed) { zip.putNextEntry(new ZipEntry("META-INF/TEST.SF")); zip.write(new byte[]{1}); zip.closeEntry(); }
        }
        return input;
    }
    @Test void renamesJarClassesAndPreservesResourcesAndOriginal() throws Exception {
        Path input=fixture(false); byte[] before=Files.readAllBytes(input); Path output=directory.resolve("result");
        var result=new RenameJob().run(input,output,Duration.ofSeconds(20));
        assertTrue(result.successful(),result.toString()); assertEquals(3,result.classes()); assertEquals(2,result.renamed()); assertEquals(1,result.reflectionRewrites());
        assertArrayEquals(before,Files.readAllBytes(input));
        assertTrue(Files.exists(output.resolve("input.jar")));
        try(var zip=new ZipFile(output.resolve("renamed.jar").toFile())) {
            assertArrayEquals(new byte[]{0,1,2,3},zip.getInputStream(zip.getEntry("asset.txt")).readAllBytes());
            var manifest=new Manifest(zip.getInputStream(zip.getEntry("META-INF/MANIFEST.MF")));
            assertEquals("p.Class00001",manifest.getMainAttributes().getValue("Main-Class"));
            assertEquals("p.Class00002\n",new String(zip.getInputStream(zip.getEntry("META-INF/services/p.Class00001")).readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));
            assertNull(zip.getEntry("META-INF/services/p.ab"));
            var first=zip.getInputStream(zip.getEntry("p/Class00001.class")).readAllBytes();
            assertEquals(List.of("Lp/Class00002;"),fieldDescriptors(first));
            assertNotNull(zip.getEntry("p/Class00002.class"));
            assertEquals(List.of("p.Class00001"),constants(zip.getInputStream(zip.getEntry("p/caller.class")).readAllBytes()));
        }
        String report=Files.readString(output.resolve("rename.json"));
        assertTrue(report.contains("NOT_VERIFIED")); assertTrue(report.contains("\"renamed\":2"));
        assertTrue(report.contains("\"reflection_rewrites\":1"));
        assertTrue(report.contains("\"p/ab\":\"p/Class00001\""));
        assertThrows(IOException.class,()->new RenameJob().run(input,output,Duration.ofSeconds(20)));
    }
    @Test void keepsUnchangedManifestBytesIdentical() throws Exception {
        Path input=directory.resolve("external-main.jar");
        var manifest=new Manifest(); manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION,"1.0");
        manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS,"external.Main");
        var header=new ByteArrayOutputStream(); manifest.write(header);
        try(var zip=new ZipOutputStream(Files.newOutputStream(input))) {
            zip.putNextEntry(new ZipEntry("p/ab.class")); zip.write(type("p/ab","p/ab")); zip.closeEntry();
            zip.putNextEntry(new ZipEntry("META-INF/MANIFEST.MF")); zip.write(header.toByteArray()); zip.closeEntry();
        }
        Path output=directory.resolve("external-main");
        assertTrue(new RenameJob().run(input,output,Duration.ofSeconds(20)).successful());
        try(var zip=new ZipFile(output.resolve("renamed.jar").toFile())) {
            assertArrayEquals(header.toByteArray(),zip.getInputStream(zip.getEntry("META-INF/MANIFEST.MF")).readAllBytes());
        }
    }
    @Test void rejectsInvalidServiceEncodingThroughWorker() throws Exception {
        Path input=directory.resolve("bad-service.jar");
        try(var zip=new ZipOutputStream(Files.newOutputStream(input))) {
            zip.putNextEntry(new ZipEntry("p/ab.class")); zip.write(type("p/ab","p/ab")); zip.closeEntry();
            zip.putNextEntry(new ZipEntry("META-INF/services/p.ab")); zip.write(new byte[]{(byte)0xC3}); zip.closeEntry();
        }
        Path output=directory.resolve("bad-service-out");
        var result=new RenameJob().run(input,output,Duration.ofSeconds(20));
        assertFalse(result.successful());
        assertFalse(Files.exists(output.resolve("renamed.jar")));
        assertTrue(Files.readString(output.resolve("rename.json")).contains("WORKER_FAILED"));
    }
    @Test void rejectsDescriptorMismatchAndMultiReleaseEntries() throws Exception {
        Path mismatch=directory.resolve("mismatch.jar");
        try(var zip=new ZipOutputStream(Files.newOutputStream(mismatch))) {
            zip.putNextEntry(new ZipEntry("p/X.class")); zip.write(type("p/Y","p/Y")); zip.closeEntry();
        }
        assertThrows(IOException.class,()->new RenameJob().run(mismatch,directory.resolve("mismatch-out"),Duration.ofSeconds(20)));
        assertTrue(Files.readString(directory.resolve("mismatch-out/failure.txt")).contains("internal name"));
        Path uppercase=directory.resolve("uppercase.jar");
        try(var zip=new ZipOutputStream(Files.newOutputStream(uppercase))) {
            zip.putNextEntry(new ZipEntry("P/XY.CLASS")); zip.write(type("P/XY","P/XY")); zip.closeEntry();
        }
        assertThrows(IOException.class,()->new RenameJob().run(uppercase,directory.resolve("uppercase-out"),Duration.ofSeconds(20)));
        Path descriptor=directory.resolve("descriptor.jar");
        try(var zip=new ZipOutputStream(Files.newOutputStream(descriptor))) {
            zip.putNextEntry(new ZipEntry("p/package-info.class")); zip.write(type("p/package-info","p/ab")); zip.closeEntry();
        }
        assertThrows(IOException.class,()->new RenameJob().run(descriptor,directory.resolve("descriptor-out"),Duration.ofSeconds(20)));
        assertTrue(Files.readString(directory.resolve("descriptor-out/failure.txt")).contains("descriptors"));
        Path multiRelease=directory.resolve("multi.jar");
        try(var zip=new ZipOutputStream(Files.newOutputStream(multiRelease))) {
            zip.putNextEntry(new ZipEntry("META-INF/versions/9/p/ab.class")); zip.write(type("META-INF/versions/9/p/ab","p/ab")); zip.closeEntry();
        }
        assertThrows(IOException.class,()->new RenameJob().run(multiRelease,directory.resolve("multi-out"),Duration.ofSeconds(20)));
    }
    @Test void renamesStandaloneClassIntoJar() throws Exception {
        Path input=directory.resolve("input.class"); Files.write(input,type("p/xy","p/xy"));
        Path output=directory.resolve("standalone");
        var result=new RenameJob().run(input,output,Duration.ofSeconds(20));
        assertTrue(result.successful()); assertEquals(1,result.classes()); assertEquals(1,result.renamed());
        try(var zip=new ZipFile(output.resolve("renamed.jar").toFile())) { assertNotNull(zip.getEntry("p/Class00001.class")); }
        assertTrue(Files.exists(output.resolve("input.class")));
    }
    @Test void rejectsSignedJarWithoutCreatingWorkerOutput() throws Exception {
        Path input=fixture(true); Path output=directory.resolve("signed");
        assertThrows(IOException.class,()->new RenameJob().run(input,output,Duration.ofSeconds(20)));
        assertFalse(Files.exists(output.resolve("worker")));
        String failure=Files.readString(output.resolve("rename.json"));
        assertTrue(failure.contains("FAILED")); assertTrue(failure.contains("input_sha256"));
    }
    @Test void rejectsInvalidTimeoutAndInputBeforeCreatingOutput() throws Exception {
        Path input=fixture(false);
        assertThrows(IllegalArgumentException.class,()->new RenameJob().run(input,directory.resolve("bad"),Duration.ZERO));
        assertFalse(Files.exists(directory.resolve("bad")));
    }
    @Test void appliesExplicitMappingAndDisablesPlanner() throws Exception {
        Path input=directory.resolve("explicit.jar");
        try(var zip=new ZipOutputStream(Files.newOutputStream(input))) {
            zip.putNextEntry(new ZipEntry("p/ab.class")); zip.write(memberType("p/ab")); zip.closeEntry();
            zip.putNextEntry(new ZipEntry("p/cd.class")); zip.write(type("p/cd","p/cd")); zip.closeEntry();
        }
        Path mapping=directory.resolve("mapping.txt");
        String rules="# explicit rules\nclass p.ab=p/Class00009\nmethod p/ab#a()I=value\nfield p/ab#x:I=count\n";
        Files.writeString(mapping,rules,java.nio.charset.StandardCharsets.UTF_8);
        Path output=directory.resolve("explicit-out");
        var result=new RenameJob().run(input,output,Duration.ofSeconds(20),mapping);
        assertTrue(result.successful(),result.toString());
        assertEquals(2,result.classes()); assertEquals(1,result.renamed()); assertEquals(2,result.members()); assertEquals(0,result.reflectionRewrites());
        assertEquals(rules,Files.readString(output.resolve("mapping.txt"),java.nio.charset.StandardCharsets.UTF_8));
        try(var zip=new ZipFile(output.resolve("renamed.jar").toFile())) {
            assertNotNull(zip.getEntry("p/Class00009.class"));
            assertNull(zip.getEntry("p/Class00001.class"));
            assertNotNull(zip.getEntry("p/cd.class"));
            var bytes=zip.getInputStream(zip.getEntry("p/Class00009.class")).readAllBytes();
            assertEquals(new TreeSet<>(List.of("run","value")),memberNames(bytes,false));
            assertEquals(new TreeSet<>(List.of("count")),memberNames(bytes,true));
        }
        String report=Files.readString(output.resolve("rename.json"));
        assertTrue(report.contains("\"mapping\":\"explicit\""));
        assertTrue(report.contains("\"member_rules\":2"));
        assertTrue(report.contains("\"members_renamed\":2"));
    }
    @Test void restoresSafeProguardNamesAndReportsSkippedRules() throws Exception {
        // Authored as if already obfuscated; the mapping says what the names were before obfuscation.
        Path sources=Files.createDirectories(directory.resolve("pg-src/p"));
        Files.writeString(sources.resolve("a.java"),"package p; public class a implements java.io.Serializable {"
            +" private static final long serialVersionUID = 1L; private int b; public int c() { return d(this) + b; }"
            +" private static int d(a self) { return self.b + 1; } }");
        Files.writeString(sources.resolve("b.java"),"package p; public class b { public int e(a target) { return target.c(); } }");
        Path classes=directory.resolve("pg-classes");
        assertEquals(0,javax.tools.ToolProvider.getSystemJavaCompiler().run(null,null,null,"--release","8","-Xlint:-options","-proc:none",
            "-d",classes.toString(),sources.resolve("a.java").toString(),sources.resolve("b.java").toString()));
        Path input=directory.resolve("proguarded.jar");
        try(var zip=new ZipOutputStream(Files.newOutputStream(input))) {
            for(String name:List.of("p/a.class","p/b.class")) { zip.putNextEntry(new ZipEntry(name)); zip.write(Files.readAllBytes(classes.resolve(name))); zip.closeEntry(); }
        }
        Path mapping=directory.resolve("proguard-mapping.txt");
        Files.writeString(mapping,"""
            # compiler: R8
            p.Account -> p.a:
                long serialVersionUID -> serialVersionUID
                int balance -> b
                1:1:int total():1:1 -> c
                1:1:int next(p.Account):1:1 -> d
            p.Ledger -> p.b:
                int read(p.Account) -> e
            p.Moved -> q.z:
            """,java.nio.charset.StandardCharsets.UTF_8);
        Path output=directory.resolve("proguard-out");
        var result=new RenameJob().run(input,output,Duration.ofSeconds(20),mapping,true);
        assertTrue(result.successful(),result.toString());
        assertEquals(2,result.renamed()); assertEquals(2,result.members());
        try(var zip=new ZipFile(output.resolve("renamed.jar").toFile())) {
            var account=zip.getInputStream(zip.getEntry("p/Account.class")).readAllBytes();
            var ledger=zip.getInputStream(zip.getEntry("p/Ledger.class")).readAllBytes();
            assertEquals(new TreeSet<>(List.of("serialVersionUID","balance")),memberNames(account,true));
            assertTrue(memberNames(account,false).containsAll(List.of("c","next")));
            assertTrue(memberNames(ledger,false).contains("e"));
            var loader=new ClassLoader(null) {
                final Map<String,byte[]> bytes=Map.of("p.Account",account,"p.Ledger",ledger);
                @Override protected Class<?> findClass(String name) throws ClassNotFoundException {
                    byte[] value=bytes.get(name); if(value==null) throw new ClassNotFoundException(name);
                    return defineClass(name,value,0,value.length);
                }
            };
            // Linking runs the JVM verifier on the renamed classes; no initializer or method is invoked.
            assertDoesNotThrow(()->Class.forName("p.Account",false,loader).getDeclaredMethods());
            assertDoesNotThrow(()->Class.forName("p.Ledger",false,loader).getDeclaredMethods());
        }
        assertTrue(Files.exists(output.resolve("proguard-mapping.txt")));
        assertTrue(Files.readString(output.resolve("mapping.txt")).contains("method p/a#d(Lp/a;)I = next"));
        String report=Files.readString(output.resolve("rename.json"));
        assertTrue(report.contains("\"mapping\":\"proguard\""));
        assertTrue(report.contains("\"NOT_PRIVATE\":2"),report);
        assertTrue(report.contains("\"CLASS_NOT_IN_INPUT\":1"),report);
    }
    @Test void proguardMappingWithNothingApplicableFailsWithEvidence() throws Exception {
        Path input=directory.resolve("pg-none.jar");
        try(var zip=new ZipOutputStream(Files.newOutputStream(input))) {
            zip.putNextEntry(new ZipEntry("p/ab.class")); zip.write(memberType("p/ab")); zip.closeEntry();
        }
        Path mapping=directory.resolve("pg-none.txt"); Files.writeString(mapping,"p.Elsewhere -> q.a:\n");
        Path output=directory.resolve("pg-none-out");
        assertThrows(IOException.class,()->new RenameJob().run(input,output,Duration.ofSeconds(20),mapping,true));
        String report=Files.readString(output.resolve("rename.json"));
        assertTrue(report.contains("no rule applicable")); assertTrue(report.contains("CLASS_NOT_IN_INPUT"));
    }
    @Test void workerFailureOnUnknownMemberRetainsEvidence() throws Exception {
        Path input=directory.resolve("bad-member.jar");
        try(var zip=new ZipOutputStream(Files.newOutputStream(input))) {
            zip.putNextEntry(new ZipEntry("p/ab.class")); zip.write(memberType("p/ab")); zip.closeEntry();
        }
        Path mapping=directory.resolve("bad-member.txt"); Files.writeString(mapping,"method p/ab#missing()V=x\n");
        Path output=directory.resolve("bad-member-out");
        var result=new RenameJob().run(input,output,Duration.ofSeconds(20),mapping);
        assertFalse(result.successful());
        assertFalse(Files.exists(output.resolve("renamed.jar")));
        assertTrue(Files.readString(output.resolve("rename.json")).contains("WORKER_FAILED"));
        assertTrue(Files.exists(output.resolve("mapping.txt")));
        assertTrue(Files.exists(output.resolve("worker/engine.log")));
    }
    @Test void rejectsMissingOrMalformedMappingBeforeOutputCreation() throws Exception {
        Path input=fixture(false); Path output=directory.resolve("mapping-bad");
        assertThrows(IOException.class,()->new RenameJob().run(input,output,Duration.ofSeconds(20),directory.resolve("absent.txt")));
        assertFalse(Files.exists(output));
        Path bad=directory.resolve("bad.txt"); Files.writeString(bad,"garbage without equals\n");
        assertThrows(IllegalArgumentException.class,()->new RenameJob().run(input,output,Duration.ofSeconds(20),bad));
        assertFalse(Files.exists(output));
    }
}
