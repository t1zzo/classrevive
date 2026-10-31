package dev.classrevive.verification;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import javax.tools.ToolProvider;
import static org.junit.jupiter.api.Assertions.*;

class DependencyClasspathTest {
    @TempDir Path directory;
    Path dependency() throws Exception {
        Path source=directory.resolve("Library.java"); Files.writeString(source,"public class Library { public static int value(){return 42;} }");
        assertEquals(0,ToolProvider.getSystemJavaCompiler().run(null,null,null,"--release","8","-proc:none",source.toString()));
        Path jar=directory.resolve("library.jar");
        try(var zip=new ZipOutputStream(Files.newOutputStream(jar))) {
            zip.putNextEntry(new ZipEntry("Library.class")); zip.write(Files.readAllBytes(directory.resolve("Library.class"))); zip.closeEntry();
            zip.putNextEntry(new ZipEntry("META-INF/MANIFEST.MF")); zip.write("Manifest-Version: 1.0\nClass-Path: secret.jar\n\n".getBytes()); zip.closeEntry();
            zip.putNextEntry(new ZipEntry("Library.java")); zip.write("invalid".getBytes()); zip.closeEntry();
        }
        return jar;
    }
    @Test void stripsImplicitDependenciesAndSources() throws Exception {
        Path jar=dependency(); Path output=directory.resolve("deps");
        var paths=DependencyClasspath.prepare(List.of(jar),output,Set.of("Target"));
        try(var zip=new ZipFile(paths.getFirst().toFile())) {
            assertEquals(1,zip.size()); assertNotNull(zip.getEntry("Library.class"));
        }
        assertTrue(Files.readString(output.resolve("dependencies.txt")).contains("sha256="));
    }
    @Test void acceptsDisjointModularJarsOnClasspath() throws Exception {
        var jars=new ArrayList<Path>();
        for(String name:List.of("First","Second")) {
            Path folder=Files.createDirectory(directory.resolve(name));
            Path module=folder.resolve("module-info.java"), source=folder.resolve(name+".java");
            Files.writeString(module,"module "+name.toLowerCase()+" {}");
            Files.writeString(source,"package sample; public class "+name+" {}");
            assertEquals(0,ToolProvider.getSystemJavaCompiler().run(null,null,null,"--release","9","-proc:none","-d",folder.toString(),module.toString(),source.toString()));
            Path jar=directory.resolve(name+".jar");
            try(var zip=new ZipOutputStream(Files.newOutputStream(jar))) {
                for(String entry:List.of("module-info.class","sample/"+name+".class")) {
                    zip.putNextEntry(new ZipEntry(entry)); zip.write(Files.readAllBytes(folder.resolve(entry))); zip.closeEntry();
                }
            }
            jars.add(jar);
        }
        var normalized=DependencyClasspath.prepare(jars,directory.resolve("modular"),Set.of("module-info","Target"));
        assertEquals(2,normalized.size());
        for(Path path:normalized) try(var zip=new ZipFile(path.toFile())) { assertNull(zip.getEntry("module-info.class")); assertEquals(1,zip.size()); }
    }
    /** Base Library (release 8) plus a Java 9 variant and module descriptor under META-INF/versions/9. */
    Path multiRelease(String variantPath) throws Exception {
        Path base=Files.createDirectory(directory.resolve("mr-base")), nine=Files.createDirectory(directory.resolve("mr-nine"));
        Files.writeString(base.resolve("Library.java"),"package lib; public class Library { public static int value(){return 1;} }");
        Files.writeString(nine.resolve("Library.java"),"package lib; public class Library { public static int value(){return 9;} }");
        Files.writeString(nine.resolve("module-info.java"),"module lib { exports lib; }");
        assertEquals(0,ToolProvider.getSystemJavaCompiler().run(null,null,null,"--release","8","-proc:none","-d",base.toString(),base.resolve("Library.java").toString()));
        assertEquals(0,ToolProvider.getSystemJavaCompiler().run(null,null,null,"--release","9","-proc:none","-d",nine.toString(),
            nine.resolve("module-info.java").toString(),nine.resolve("Library.java").toString()));
        Path jar=directory.resolve("multi-release.jar");
        try(var zip=new ZipOutputStream(Files.newOutputStream(jar))) {
            zip.putNextEntry(new ZipEntry("META-INF/MANIFEST.MF")); zip.write("Manifest-Version: 1.0\nMulti-Release: true\n\n".getBytes()); zip.closeEntry();
            zip.putNextEntry(new ZipEntry("lib/Library.class")); zip.write(Files.readAllBytes(base.resolve("lib/Library.class"))); zip.closeEntry();
            zip.putNextEntry(new ZipEntry(variantPath)); zip.write(Files.readAllBytes(nine.resolve("lib/Library.class"))); zip.closeEntry();
            zip.putNextEntry(new ZipEntry("META-INF/versions/9/module-info.class")); zip.write(Files.readAllBytes(nine.resolve("module-info.class"))); zip.closeEntry();
        }
        return jar;
    }
    @Test void multiReleaseDependencyContributesOnlyItsBaseClasses() throws Exception {
        Path output=directory.resolve("deps");
        var paths=DependencyClasspath.prepare(List.of(multiRelease("META-INF/versions/9/lib/Library.class")),output,Set.of("Target"));
        try(var zip=new ZipFile(paths.getFirst().toFile())) {
            assertEquals(List.of("lib/Library.class"),zip.stream().map(ZipEntry::getName).toList());
        }
        assertTrue(Files.readString(output.resolve("dependencies.txt")).contains("classes=1 multi-release-variants-ignored=2"));
    }
    @Test void rejectsVersionedDependencyEntryThatDoesNotNameItsClass() throws Exception {
        var error=assertThrows(java.io.IOException.class,()->DependencyClasspath.prepare(
            List.of(multiRelease("META-INF/versions/9/lib/Other.class")),directory.resolve("deps"),Set.of("Target")));
        assertTrue(error.getMessage().contains("META-INF/versions/9/lib/Other.class"),error.getMessage());
    }
    @Test void rejectsOriginalClassOverlap() throws Exception {
        Path jar=dependency();
        assertThrows(java.io.IOException.class,()->DependencyClasspath.prepare(List.of(jar),directory.resolve("deps"),Set.of("Library")));
    }
    @Test void compilerResolvesOnlyExplicitDependencyClasses() throws Exception {
        Path jar=dependency(); Path sources=Files.createDirectory(directory.resolve("src"));
        Files.writeString(sources.resolve("Target.java"),"public class Target { public int value(){return Library.value();} }");
        var original=new dev.classrevive.model.ClassInfo("Target","java/lang/Object",52,0,1,List.of(),List.of(),
            List.of(new dev.classrevive.model.MemberInfo("<init>","()V",1),new dev.classrevive.model.MemberInfo("value","()I",1)));
        var verifier=new CompilerVerifier();
        assertEquals(VerificationResult.Status.COMPILE_FAILED,verifier.verify(sources,directory.resolve("missing"),List.of(original),java.time.Duration.ofSeconds(20)).status());
        assertTrue(verifier.verify(sources,directory.resolve("resolved"),List.of(original),java.time.Duration.ofSeconds(20),List.of(jar)).complete());
    }
}
