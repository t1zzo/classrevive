package dev.classrevive.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.io.*;
import java.util.List;
import java.util.zip.*;
import static org.junit.jupiter.api.Assertions.*;

class MainTest {
    @TempDir Path directory;
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    ByteArrayOutputStream errors = new ByteArrayOutputStream();
    int run(String... args) { return Main.run(args, new PrintStream(output), new PrintStream(errors)); }
    @Test void showsHelp() {
        assertEquals(0, run("--help"));
        assertTrue(output.toString().contains("inspect"));
    }
    @Test void diagnosesDynamicReferencesThroughBoundedWorker() throws Exception {
        Path input=directory.resolve("fixture.class");
        try(var stream=MainTest.class.getResourceAsStream("/dev/classrevive/cli/MainTest.class")) { Files.copy(stream,input); }
        Path result=directory.resolve("diagnosis");
        assertEquals(0,run("diagnose",input.toString(),"--output",result.toString()),errors.toString());
        String report=Files.readString(result.resolve("diagnostics.json"));
        assertTrue(report.contains("INVOKEDYNAMIC")); assertTrue(report.contains("NOT_VERIFIED"));
        assertTrue(Files.exists(result.resolve("input.class")));
        assertEquals(1,run("diagnose",input.toString(),"--output",result.toString()));
        assertEquals(2,run("diagnose",input.toString(),"--engines","all"));
    }
    @Test void diagnosesClassLoadingAndCryptoReferencesWithoutClaimingPackingOrMalware() throws Exception {
        Path source=directory.resolve("ApiSignalFixture.java");
        Files.writeString(source,"import javax.crypto.Cipher;"
            + "class ApiSignalLoader extends ClassLoader { Class<?> define(byte[] b){return defineClass(b,0,b.length);} }"
            + "public class ApiSignalFixture { static byte[] decrypt(byte[] b) throws Exception {"
            + "Cipher c=Cipher.getInstance(\"AES\"); return c.doFinal(b); }"
            + "static Class<?> load(byte[] b){return new ApiSignalLoader().define(b);} }");
        assertEquals(0,javax.tools.ToolProvider.getSystemJavaCompiler().run(null,null,null,"--release","8","-Xlint:-options","-proc:none",
            "-d",directory.toString(),source.toString()));
        Path input=directory.resolve("api-signals.jar");
        try(var archive=new ZipOutputStream(Files.newOutputStream(input))) {
            for(String name:List.of("ApiSignalFixture.class","ApiSignalLoader.class")) {
                archive.putNextEntry(new ZipEntry(name)); archive.write(Files.readAllBytes(directory.resolve(name))); archive.closeEntry();
            }
        }
        Path outputPath=directory.resolve("api-signals");
        assertEquals(0,run("diagnose",input.toString(),"--output",outputPath.toString()),errors.toString());
        String report=Files.readString(outputPath.resolve("diagnostics.json"));
        assertTrue(report.contains("CLASS_LOADING_API"),report);
        assertTrue(report.contains("CRYPTO_API"),report);
        assertTrue(report.contains("\"signal_counts\""),report);
        assertTrue(report.contains("\"CLASS_LOADING_API\":1"),report);
        assertTrue(report.contains("\"CRYPTO_API\":2"),report);
        assertTrue(report.contains("do not prove runtime reachability, packing, encryption or malicious behavior"),report);
        assertTrue(output.toString().contains("Static API references are clues only"));
        assertTrue(output.toString().contains("class-loading=1; crypto=2"));
        // Phase 2BZ: crypto API references alone are not string encryption; javac output is not obfuscated.
        assertTrue(report.contains("\"obfuscation\":{"),report);
        assertTrue(report.contains("\"verdict\":\"NONE\""),report);
        assertTrue(output.toString().contains("Suggested obfuscator: none"),output.toString());
        assertTrue(Files.readString(outputPath.resolve("obfuscation.txt")).contains("not proof"));
    }
    /** Phase 2CC: --native extracts bundled libraries as data; without Ghidra the command still works and says it skipped. */
    @Test void diagnoseWithNativeTriageWorksWithoutGhidra() throws Exception {
        Path input=directory.resolve("with-native.jar");
        try(var zip=new ZipOutputStream(Files.newOutputStream(input)); var stream=MainTest.class.getResourceAsStream("/dev/classrevive/cli/MainTest.class")) {
            zip.putNextEntry(new ZipEntry("dev/classrevive/cli/MainTest.class")); stream.transferTo(zip); zip.closeEntry();
            zip.putNextEntry(new ZipEntry("natives/win-x64/junk.dll")); zip.write("MZ not really a library".getBytes()); zip.closeEntry();
        }
        Path result=directory.resolve("with-native");
        assertEquals(0,run("diagnose",input.toString(),"--output",result.toString(),"--native"),errors.toString());
        String console=output.toString();
        if(System.getenv("GHIDRA_INSTALL_DIR")==null) assertTrue(console.contains("Native analysis: skipped (Ghidra not configured)"),console);
        assertTrue(console.contains("natives/win-x64/junk.dll: NOT_NATIVE"),console);
        assertTrue(Files.readString(result.resolve("obfuscation.txt")).contains("\nNative analysis: "));
        assertTrue(Files.readString(result.resolve("diagnostics.json")).contains("\"native_analysis\":{"));
        assertTrue(Files.isRegularFile(result.resolve("native/summary.txt")));
        assertTrue(Files.readString(result.resolve("native/report.json")).contains("\"status\":\"NOT_NATIVE\""));
        // Without --native nothing changes; invalid native options are argument errors.
        Path plain=directory.resolve("without-native");
        assertEquals(0,run("diagnose",input.toString(),"--output",plain.toString()),errors.toString());
        assertFalse(Files.exists(plain.resolve("native")));
        assertFalse(Files.readString(plain.resolve("diagnostics.json")).contains("native_analysis"));
        assertEquals(2,run("diagnose",input.toString(),"--output",directory.resolve("bad1").toString(),"--native-max-mib","0"));
        assertEquals(2,run("diagnose",input.toString(),"--output",directory.resolve("bad2").toString(),"--ghidra-home",directory.resolve("nowhere").toString()));
        assertTrue(errors.toString().contains("--ghidra-home is not a Ghidra installation"),errors.toString());
    }
    @Test void diagnosesUppercaseClassEntriesAcceptedByInventory() throws Exception {
        Path input=directory.resolve("uppercase.jar");
        try(var zip=new ZipOutputStream(Files.newOutputStream(input)); var stream=MainTest.class.getResourceAsStream("/dev/classrevive/cli/MainTest.class")) {
            zip.putNextEntry(new ZipEntry("MainTest.CLASS")); stream.transferTo(zip); zip.closeEntry();
        }
        Path result=directory.resolve("uppercase-result");
        assertEquals(0,run("diagnose",input.toString(),"--output",result.toString()));
        String report=Files.readString(result.resolve("diagnostics.json"));
        assertTrue(report.contains("\"classes_scanned\":1")); assertTrue(report.contains("INVOKEDYNAMIC"));
    }
    @Test void inspectsAndDiagnosesJava25ClassFile() throws Exception {
        Path input=directory.resolve("java25.class");
        try(var stream=MainTest.class.getResourceAsStream("/dev/classrevive/cli/MainTest.class")) {
            byte[] bytes=stream.readAllBytes(); bytes[6]=0; bytes[7]=69; Files.write(input,bytes);
        }
        assertEquals(0,run("inspect",input.toString()),errors.toString());
        assertTrue(output.toString().contains("Java 25 (69.0)"),output.toString());
        Path result=directory.resolve("java25-diagnosis");
        assertEquals(0,run("diagnose",input.toString(),"--output",result.toString()),errors.toString());
        String report=Files.readString(result.resolve("diagnostics.json"));
        assertTrue(report.contains("\"classes_scanned\":1"),report);
        assertTrue(report.contains("INVOKEDYNAMIC"),report);
        assertFalse(report.contains("UNSUPPORTED_CLASS_VERSION"),report);
    }
    @Test void parsesExplicitClasspathOnlyForDecompile() {
        var options=JobOptions.parse(new String[]{"decompile","input.jar","--output","out","--classpath","a.jar"+File.pathSeparator+"b.jar"},"cfr");
        assertEquals(2,options.dependencies().size());
        assertEquals(2,run("benchmark","corpus","--output","out","--classpath","a.jar"));
        assertThrows(IllegalArgumentException.class,()->JobOptions.parse(new String[]{"decompile","input.jar","--output","out","--classpath",""},"cfr"));
    }
    @Test void decompilesAndVerifiesWithExplicitDependency() throws Exception {
        Path library=directory.resolve("Library.java"), source=directory.resolve("Target.java");
        Files.writeString(library,"public class Library { public static int value(){return 42;} }");
        Files.writeString(source,"public class Target { public int value(){return Library.value();} }");
        assertEquals(0,javax.tools.ToolProvider.getSystemJavaCompiler().run(null,null,null,"--release","8","-proc:none",library.toString(),source.toString()));
        Path dependency=directory.resolve("lib.jar");
        try(var zip=new ZipOutputStream(Files.newOutputStream(dependency))) {
            zip.putNextEntry(new ZipEntry("Library.class")); zip.write(Files.readAllBytes(directory.resolve("Library.class"))); zip.closeEntry();
        }
        Path result=directory.resolve("recovered");
        assertEquals(0,run("decompile",directory.resolve("Target.class").toString(),"--output",result.toString(),"--engines","cfr","--classpath",dependency.toString()),errors+"\n"+output);
        assertTrue(Files.exists(result.resolve("selection/checks/attempt-0-dependencies/dependencies.txt")));
    }
    @Test void transformsClassWithoutExecutingIt() throws Exception {
        Path input = directory.resolve("fixture.class");
        try (var in = MainTest.class.getResourceAsStream("/dev/classrevive/cli/MainTest.class")) { Files.copy(in, input); }
        Path result = directory.resolve("transformed");
        assertEquals(0, run("transform", input.toString(), "--passes", "integer-constants,integer-unary", "--max-changes", "10", "--output", result.toString()));
        assertTrue(Files.readString(result.resolve("transform.json")).contains("NOT_VERIFIED"));
        assertEquals(1, run("transform", input.toString(), "--output", result.toString()));
    }
    @Test void transformsStringDecoderFromCliWithoutExposingDecodedText() throws Exception {
        Path source = directory.resolve("CliDecode.java");
        Files.writeString(source, "public class CliDecode {"
            + "private static String decode(String s) { char[] c=s.toCharArray();"
            + "for(int i=0;i<c.length;i++) c[i]=(char)(c[i]^1); return new String(c); }"
            + "public static String value() { return decode(\"Gdkkn\"); } }");
        assertEquals(0, javax.tools.ToolProvider.getSystemJavaCompiler().run(null, null, null, "--release", "8",
            "-Xlint:-options", "-proc:none", "-d", directory.toString(), source.toString()));
        Path input = directory.resolve("CliDecode.class"), result = directory.resolve("cli-decoded");
        assertEquals(0, run("transform", input.toString(), "--passes", "string-decryptor", "--output", result.toString()), errors.toString());
        assertTrue(output.toString().contains("target code is not executed"));
        String evidence = Files.readString(result.resolve("transform.json"));
        assertTrue(evidence.contains("string-decryptor-v1"));
        assertFalse(evidence.contains("Gdkkn")); assertFalse(evidence.contains("Hello"));
    }
    @Test void validatesTransformOptionsBeforeCreatingOutput() {
        for (String[] options : new String[][]{{"--max-changes","0"},{"--max-changes","10001"},{"--engines","all"},{"--timeout-seconds","0"},{"--output","duplicate"}}) {
            assertEquals(2, run("transform", "missing.jar", "--output", directory.resolve("out").toString(), options[0], options[1]));
        }
        assertFalse(Files.exists(directory.resolve("out")));
    }
    @Test void benchmarkReportsInvalidSamplesWithoutDroppingThem() throws Exception {
        var corpus = Files.createDirectory(directory.resolve("corpus"));
        Files.write(corpus.resolve("bad.class"), new byte[]{1});
        var result = directory.resolve("result");
        assertEquals(1, run("benchmark", corpus.toString(), "--output", result.toString()));
        assertTrue(Files.readString(result.resolve("benchmark.json")).contains("\"attempted\":5"));
        assertTrue(output.toString().contains("0/5"));
    }
    @Test void validatesBenchmarkOptions() {
        assertEquals(2, run("benchmark"));
        assertEquals(2, run("benchmark", "corpus", "--output", "result", "--timeout-seconds", "0"));
    }
    @Test void listsPinnedEngines() {
        assertEquals(0, run("engines"));
        for (String engine : new String[]{"cfr", "vineflower", "procyon", "jadx"}) assertTrue(output.toString().contains(engine));
    }
    @Test void rejectsUnknownDuplicateAndEmptyEngineSelections() {
        for (String engines : new String[]{"unknown", "cfr,cfr", "", "cfr,", "all,cfr"})
            assertEquals(2, run("decompile", "missing.jar", "--output", directory.resolve("out").toString(), "--engines", engines));
        assertEquals(2, run("benchmark", "missing", "--output", "out", "--output", "other"));
        assertFalse(Files.exists(directory.resolve("out")));
    }
    @Test void acceptsExplicitSingleEngineAndReorderedFlags() throws Exception {
        var corpus = Files.createDirectory(directory.resolve("corpus"));
        Files.write(corpus.resolve("bad.class"), new byte[]{1});
        var result = directory.resolve("result");
        assertEquals(1, run("benchmark", corpus.toString(), "--engines", "procyon", "--timeout-seconds", "2", "--output", result.toString()));
        assertTrue(Files.readString(result.resolve("benchmark.json")).contains("\"attempted\":1"));
    }
    @Test void rejectsUnknownCommandsAndArguments() {
        // Phase 2CV: --output is optional now, so a missing input is a failure (1), and no folder is created for it.
        assertEquals(1, run("decompile", "target.jar"));
        assertFalse(Files.exists(Path.of("target-classrevive")));
        assertEquals(2, run("inspect"));
        assertEquals(2, run("inspect", "target.jar", "--unknown"));
    }
    @Test void reportsMissingInputWithoutStackTrace() {
        assertEquals(1, run("inspect", directory.resolve("missing.jar").toString()));
        assertTrue(errors.toString().contains("INVALID_INPUT"));
        assertFalse(errors.toString().contains("Exception"));
    }
    @Test void inventoriesPackagedClass() throws Exception {
        var path = directory.resolve("fixture.class");
        try (var in = MainTest.class.getResourceAsStream("/dev/classrevive/cli/MainTest.class")) {
            Files.copy(in, path);
        }
        assertEquals(0, run("inspect", path.toString()));
        assertTrue(output.toString().contains("dev/classrevive/cli/MainTest"));
        assertTrue(output.toString().contains("SHA-256"));
        assertTrue(output.toString().contains("methods="));
    }
    @Test void escapesHostileTerminalText() throws Exception {
        var path = directory.resolve("hostile.jar");
        try (var zip = new ZipOutputStream(Files.newOutputStream(path))) {
            zip.putNextEntry(new ZipEntry("evil\u001b[2J.txt")); zip.closeEntry();
        }
        assertEquals(1, run("inspect", path.toString()));
        assertFalse(errors.toString().contains("\u001b"));
        assertTrue(errors.toString().contains("\\u001b"));
    }
    @Test void handlesInvalidPath() {
        assertEquals(2, run("inspect", "invalid\0.jar"));
    }
    @Test void decompilesWithBothEnginesThroughCli() throws Exception {
        var source = directory.resolve("Example.java");
        Files.writeString(source, "public class Example { public int answer() { return 42; } }");
        assertEquals(0, javax.tools.ToolProvider.getSystemJavaCompiler().run(null, null, null,
            "--release", "8", "-proc:none", source.toString()));
        var out = directory.resolve("output");
        assertEquals(0, run("decompile", directory.resolve("Example.class").toString(), "--output", out.toString()), errors+"\n"+output);
        assertTrue(Files.exists(out.resolve("cfr/sources/Example.java")));
        assertTrue(Files.exists(out.resolve("vineflower/sources/Example.java")));
        assertTrue(Files.exists(out.resolve("selection/sources/Example.java")));
        assertTrue(output.toString().contains("not verified"));
    }
    @Test void validatesDecompileOptionsBeforeCreatingOutput() {
        var out = directory.resolve("output");
        assertEquals(2, run("decompile", "input.jar", "--output", out.toString(), "--timeout-seconds", "0"));
        assertEquals(2, run("decompile", "input.jar", "--output", out.toString(), "--timeout-seconds", "garbage"));
        assertEquals(2, run("decompile", "input.jar", "--wrong", out.toString()));
        assertFalse(Files.exists(out));
    }
    @Test void renamesClassesThroughCliWithGeneratedAliases() throws Exception {
        var source = directory.resolve("ab.java");
        Files.writeString(source, "class ab { }");
        assertEquals(0, javax.tools.ToolProvider.getSystemJavaCompiler().run(null, null, null, "--release", "8", "-proc:none", source.toString()));
        Path input = directory.resolve("rename-input.jar");
        try (var zip = new ZipOutputStream(Files.newOutputStream(input))) {
            zip.putNextEntry(new ZipEntry("ab.class")); zip.write(Files.readAllBytes(directory.resolve("ab.class"))); zip.closeEntry();
        }
        Path result = directory.resolve("renamed");
        assertEquals(0, run("rename", input.toString(), "--output", result.toString()), errors.toString());
        assertTrue(Files.exists(result.resolve("renamed.jar")));
        assertTrue(Files.readString(result.resolve("rename.json")).contains("NOT_VERIFIED"));
        assertEquals(1, run("rename", input.toString(), "--output", result.toString()));
    }
    @Test void validatesRenameOptionsBeforeCreatingOutput() {
        var out = directory.resolve("rename-out");
        assertEquals(2, run("rename", directory.resolve("missing.jar").toString(), "--output", out.toString(), "--unknown", "x"));
        assertEquals(2, run("rename", directory.resolve("missing.jar").toString(), "--output", out.toString(), "--timeout-seconds", "0"));
        assertEquals(2, run("rename", directory.resolve("missing.jar").toString()));
        assertEquals(2, run("rename", directory.resolve("missing.jar").toString(), "--output", out.toString(), "--mapping", "a.txt", "--proguard-mapping", "b.txt"));
        assertTrue(errors.toString().contains("either --mapping or --proguard-mapping"));
        assertFalse(Files.exists(out));
    }
    @Test void labCapturesAndGroupsFailuresAndChecksRegressionsThroughCli() throws Exception {
        assertEquals(0, run("--help"));
        assertTrue(output.toString().contains("lab-minimize") && output.toString().contains("lab-promote") && output.toString().contains("lab-check"));
        var source = directory.resolve("src/demo/Probe.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "package demo; public class Probe { private final String name = \"n\";"
            + " public boolean named(Object other) { return other instanceof String text && text.equals(name); } }");
        assertEquals(0, javax.tools.ToolProvider.getSystemJavaCompiler().run(null, null, null, "--release", "17", "-proc:none",
            "-d", directory.resolve("classes").toString(), source.toString()));
        Path input = directory.resolve("probe.jar");
        try (var zip = new ZipOutputStream(Files.newOutputStream(input))) {
            zip.putNextEntry(new ZipEntry("demo/Probe.class")); zip.write(Files.readAllBytes(directory.resolve("classes/demo/Probe.class"))); zip.closeEntry();
        }
        Path invalid = directory.resolve("lab-invalid");
        assertEquals(2, run("lab", input.toString(), "--output", invalid.toString(), "--minimize", "bogus"));
        assertEquals(2, run("lab", input.toString(), "--output", invalid.toString(), "--keep-recoverable", "maybe"));
        assertEquals(2, run("lab", input.toString(), "--output", invalid.toString(), "--max-steps", "0"));
        assertEquals(2, run("lab-minimize", input.toString()));
        assertEquals(2, run("lab-promote", input.toString(), "--output", invalid.toString(), "--unknown", "1"));
        assertFalse(Files.exists(invalid));
        Path lab = directory.resolve("lab");
        assertEquals(0, run("lab", input.toString(), "--output", lab.toString(), "--engines", "cfr,vineflower"), errors.toString());
        // Selected 0: since the round-trip tie-break and per-method composition, the selected candidate no longer has this failure.
        assertTrue(output.toString().contains("METHOD_DIFFERENT:cfr:body-differs:const-string/dup (selected 0, recoverable by another engine 1)"), output.toString());
        assertTrue(Files.isRegularFile(lab.resolve("failures/failure-0001/case.json")));
        assertTrue(Files.isRegularFile(lab.resolve("job/selection/selection.txt")));
        Path empty = Files.createDirectory(directory.resolve("regressions"));
        assertEquals(0, run("lab-check", empty.toString(), "--output", directory.resolve("check").toString()), errors.toString());
        assertTrue(output.toString().contains("PASSED: 0 case(s), 0 method check(s)"), output.toString());
        assertEquals(1, run("lab", input.toString(), "--output", lab.toString()));
    }
    @Test void renamesPrivateMembersThroughExplicitMapping() throws Exception {
        var source = directory.resolve("em.java");
        Files.writeString(source, "class em { private int x; private int a(){ return x; } int run(){ return a()+x; } }");
        assertEquals(0, javax.tools.ToolProvider.getSystemJavaCompiler().run(null, null, null, "--release", "8", "-proc:none", source.toString()));
        Path input = directory.resolve("explicit-input.jar");
        try (var zip = new ZipOutputStream(Files.newOutputStream(input))) {
            zip.putNextEntry(new ZipEntry("em.class")); zip.write(Files.readAllBytes(directory.resolve("em.class"))); zip.closeEntry();
        }
        Path mapping = directory.resolve("mapping.txt");
        Files.writeString(mapping, "class em=Renamed\nmethod em#a()I=value\nfield em#x:I=count\n");
        Path result = directory.resolve("explicit-renamed");
        assertEquals(0, run("rename", input.toString(), "--mapping", mapping.toString(), "--output", result.toString()), errors.toString());
        assertTrue(Files.exists(result.resolve("renamed.jar")));
        assertTrue(Files.readString(result.resolve("rename.json")).contains("\"members_renamed\":2"));
        var malformed = directory.resolve("malformed.txt");
        Files.writeString(malformed, "not a mapping");
        var second = directory.resolve("explicit-second");
        assertEquals(2, run("rename", input.toString(), "--output", second.toString(), "--mapping", malformed.toString()));
        assertFalse(Files.exists(second));
    }

    /** Phase 2CW: an APK is identified, converted by dex2jar in its worker and decompiled; the origin line names the conversion. */
    @Test void identifiesAndDecompilesAnApk() throws Exception {
        Path apk = Files.copy(Path.of("../../benchmarks/android/notes.apk"), directory.resolve("notes.apk"));
        assertEquals(0, run("identify", apk.toString()), errors.toString());
        assertTrue(output.toString().contains("Verdict: ANDROID_PACKAGE"), output.toString());
        output.reset(); errors.reset();
        Path result = directory.resolve("notes-recovered");
        assertEquals(0, run("decompile", apk.toString(), "--output", result.toString(), "--engines", "cfr"), errors + "\n" + output);
        assertTrue(output.toString().contains("converted from Dalvik bytecode by dex2jar 2.4.38 (7 classes, 0 untranslated methods)"), output.toString());
        assertTrue(Files.exists(result.resolve("selection/sources/demo/notes/MainActivity.java")));
        assertTrue(Files.exists(result.resolve("input-origin/conversion.txt")));
    }

    /** Phase 2CE: an executable is identified; the jar inside a stub is decompiled; a protected one is explained (exit 1). */
    @Test void identifiesExecutablesAndDecompilesTheJarInside() throws Exception {
        Path source = directory.resolve("Hello.java");
        Files.writeString(source, "public class Hello { public int value() { return 42; } }");
        assertEquals(0, javax.tools.ToolProvider.getSystemJavaCompiler().run(null, null, null, "--release", "8", "-proc:none", source.toString()));
        var jar = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(jar)) {
            zip.putNextEntry(new ZipEntry("Hello.class")); zip.write(Files.readAllBytes(directory.resolve("Hello.class"))); zip.closeEntry();
        }
        var exe = new ByteArrayOutputStream();
        exe.writeBytes(minimalPe(null, null)); exe.writeBytes(jar.toByteArray());
        Path app = Files.write(directory.resolve("app.exe"), exe.toByteArray());

        assertEquals(0, run("identify", app.toString()), errors.toString());
        assertTrue(output.toString().contains("Format: PE32+ x86-64 console executable (Windows)"), output.toString());
        assertTrue(output.toString().contains("Verdict: EMBEDDED_JAR"), output.toString());
        Path evidence = directory.resolve("identified");
        assertEquals(0, run("identify", app.toString(), "--output", evidence.toString()), errors.toString());
        assertTrue(Files.readString(evidence.resolve("identification.json")).contains("\"verdict\":\"EMBEDDED_JAR\""));
        assertEquals(1, run("identify", app.toString(), "--output", evidence.toString()));
        assertEquals(2, run("identify", app.toString(), "--bogus", "x"));
        assertEquals(1, run("identify", directory.resolve("missing.exe").toString()));
        errors.reset();
        assertEquals(1, run("inspect", app.toString()));
        assertTrue(errors.toString().contains("decompile and diagnose take the Java found in this file"), errors.toString());

        output.reset(); errors.reset();
        Path result = directory.resolve("recovered");
        assertEquals(0, run("decompile", app.toString(), "--output", result.toString(), "--engines", "cfr"), errors + "\n" + output);
        assertTrue(output.toString().contains("Input origin: PE32+ x86-64 console executable (Windows) (EMBEDDED_JAR); jar extracted from offset 1024"),
            output.toString());
        assertTrue(Files.exists(result.resolve("selection/sources/Hello.java")));
        assertTrue(Files.readString(result.resolve("report.json")).contains("\"input_origin\":{"));

        var noise = new byte[96 * 1024];
        new java.util.Random(4).nextBytes(noise);
        Path loader = Files.write(directory.resolve("loader.exe"), minimalPe(".vlizer", noise));
        output.reset(); errors.reset();
        Path refused = directory.resolve("refused");
        assertEquals(1, run("decompile", loader.toString(), "--output", refused.toString(), "--engines", "cfr"));
        assertTrue(output.toString().contains("Verdict: NATIVE_PROTECTED_NO_JAVA"), output.toString());
        assertTrue(errors.toString().contains("No Java to process: Input is PE32+ x86-64 console executable (Windows) (NATIVE_PROTECTED_NO_JAVA)"),
            errors.toString());
        assertFalse(Files.exists(refused));
        assertEquals(1, run("diagnose", loader.toString(), "--output", refused.toString()));
        assertFalse(Files.exists(refused));
    }

    @Test void decompilesABundleOfJarsInsideAnExecutableWithItsOtherJarsOnTheClasspath() throws Exception {
        // Phase 2DB: app.jar calls lib.Util from libs/util.jar; with stubs off, verification needs the bundle's other jar.
        Path sources = Files.createDirectories(directory.resolve("src"));
        Files.createDirectories(sources.resolve("app")); Files.createDirectories(sources.resolve("lib"));
        Files.writeString(sources.resolve("lib/Util.java"), "package lib; public class Util { public static int value() { return 7; } }");
        Files.writeString(sources.resolve("app/Main.java"), "package app; public class Main { public int run() { return lib.Util.value() + 1; } }");
        Path classes = directory.resolve("classes");
        assertEquals(0, javax.tools.ToolProvider.getSystemJavaCompiler().run(null, null, null, "--release", "8", "-proc:none", "-d", classes.toString(),
            sources.resolve("lib/Util.java").toString(), sources.resolve("app/Main.java").toString()));
        var payload = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(payload)) {
            zip.putNextEntry(new ZipEntry("app.jar")); zip.write(jarOf(classes, "app/Main.class")); zip.closeEntry();
            zip.putNextEntry(new ZipEntry("libs/util.jar")); zip.write(jarOf(classes, "lib/Util.class")); zip.closeEntry();
            zip.putNextEntry(new ZipEntry("helper.dll")); zip.write(new byte[] {'M', 'Z'}); zip.closeEntry();
        }
        Path exe = Files.write(directory.resolve("Bundle.exe"), minimalPe(".rdata2", payload.toByteArray()));

        assertEquals(0, run("identify", exe.toString(), "--output", directory.resolve("identified").toString()), errors.toString());
        assertTrue(output.toString().contains("Verdict: JAVA_BUNDLE"), output.toString());
        assertTrue(output.toString().contains("Nested jars written (data, not run): 2"), output.toString());
        assertTrue(Files.isRegularFile(directory.resolve("identified/bundle/libs_util.jar")));

        output.reset(); errors.reset();
        Path result = directory.resolve("bundle-result");
        assertEquals(0, run("decompile", exe.toString(), "--output", result.toString(), "--engines", "cfr", "--stubs", "false"), errors + "\n" + output);
        assertTrue(output.toString().contains("(JAVA_BUNDLE); nested jar app.jar taken from the bundle at offset 1024"), output.toString());
        assertTrue(output.toString().contains("1 other nested jar(s) on the classpath"), output.toString());
        assertTrue(Files.exists(result.resolve("selection/sources/app/Main.java")));
        assertTrue(Files.isRegularFile(result.resolve("project/lib/util.jar")));
        String origin = Files.readString(result.resolve("input-origin/identification.txt"));
        assertTrue(origin.contains("Extracted: nested jar app.jar of the bundle at 0x400") && origin.contains("Classpath for verification: libs/util.jar"), origin);
        assertTrue(Files.readString(result.resolve("report.json")).contains("\"entry\":\"app.jar\""));

        output.reset(); errors.reset();
        Path chosen = directory.resolve("bundle-util");
        assertEquals(0, run("decompile", exe.toString(), "--output", chosen.toString(), "--engines", "cfr", "--entry", "util.jar"), errors + "\n" + output);
        assertTrue(Files.exists(chosen.resolve("selection/sources/lib/Util.java")));
        assertEquals(0, run("diagnose", exe.toString(), "--output", directory.resolve("bundle-diagnosis").toString(), "--entry", "libs/util.jar"),
            errors + "\n" + output);
        assertTrue(Files.readString(directory.resolve("bundle-diagnosis/input-origin/identification.txt")).contains("nested jar libs/util.jar"));
        errors.reset();
        assertEquals(2, run("decompile", exe.toString(), "--output", directory.resolve("bad").toString(), "--engines", "cfr", "--entry", "nope.jar"));
        assertTrue(errors.toString().contains("No nested jar named nope.jar"), errors.toString());
        assertFalse(Files.exists(directory.resolve("bad")));
    }

    private static byte[] jarOf(Path classes, String entry) throws IOException {
        var jar = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(jar)) { zip.putNextEntry(new ZipEntry(entry)); zip.write(Files.readAllBytes(classes.resolve(entry))); zip.closeEntry(); }
        return jar.toByteArray();
    }

    /** A PE32+ x64 console image with one .text section and an optional executable section filled with {@code data}. */
    private static byte[] minimalPe(String extraName, byte[] data) {
        int extra = data == null ? 0 : (data.length + 0x1ff) / 0x200 * 0x200;
        var b = java.nio.ByteBuffer.allocate(0x400 + extra).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        b.put(0, (byte) 'M').put(1, (byte) 'Z').putInt(0x3c, 0x40).put(0x40, (byte) 'P').put(0x41, (byte) 'E');
        b.putShort(0x44, (short) 0x8664).putShort(0x46, (short) (data == null ? 1 : 2)).putShort(0x54, (short) 240).putShort(0x56, (short) 0x22);
        b.putShort(0x58, (short) 0x20b).putInt(0x58 + 16, 0x1000).putShort(0x58 + 68, (short) 3).putInt(0x58 + 108, 16);
        int table = 0x58 + 240;
        b.put(table, ".text".getBytes()).putInt(table + 8, 0x10).putInt(table + 12, 0x1000).putInt(table + 16, 0x200)
            .putInt(table + 20, 0x200).putInt(table + 36, 0x60000020);
        b.put(0x200, (byte) 0xc3);
        if (data != null) {
            b.put(table + 40, extraName.getBytes()).putInt(table + 48, data.length).putInt(table + 52, 0x2000).putInt(table + 56, extra)
                .putInt(table + 60, 0x400).putInt(table + 76, 0xe0000060);
            b.put(0x400, data);
        }
        return b.array();
    }
}
