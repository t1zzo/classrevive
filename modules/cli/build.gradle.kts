import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
plugins { application }
dependencies {
    implementation(project(":intake"))
    implementation(project(":pipeline"))
    implementation(project(":bytecode"))
    implementation(project(":sandbox"))
    // Phase G1: engines, request limits and the decompile facade, shared with the desktop GUI.
    implementation(project(":workbench"))
    // Phase G3: `classrevive gui` and the classrevive-gui launcher start the desktop application.
    implementation(project(":gui"))
}
// Phase 2CW: dex2jar's jars, for the distribution (lib/dex2jar) and for tests that convert an APK.
val dexTools: Configuration by configurations.creating { isCanBeConsumed = false }
dependencies { dexTools(project(path = ":dex", configuration = "dexTools")) }
tasks.withType<Test>().configureEach {
    inputs.files(dexTools).withPropertyName("dexTools")
    jvmArgumentProviders.add(CommandLineArgumentProvider { listOf("-Dclassrevive.dex2jar.classpath=" + dexTools.asPath) })
}
// Phase G3: a second launcher for the desktop application. On Windows it starts javaw through `start`, so no console
// window stays open behind the application.
val guiStartScripts = tasks.register<CreateStartScripts>("guiStartScripts") {
    description = "Creates the classrevive-gui launchers for the desktop application."
    applicationName = "classrevive-gui"
    mainClass.set("dev.classrevive.gui.ClassReviveApp")
    outputDir = layout.buildDirectory.dir("gui-scripts").get().asFile
    classpath = tasks.named<CreateStartScripts>("startScripts").get().classpath
    doLast {
        val bat = outputDir!!.resolve("classrevive-gui.bat")
        val text = bat.readText()
        val windowed = text.replace("java.exe", "javaw.exe").replace("\"%JAVA_EXE%\" %DEFAULT_JVM_OPTS%", "start \"\" \"%JAVA_EXE%\" %DEFAULT_JVM_OPTS%")
        check(windowed.contains("start \"\" \"%JAVA_EXE%\"")) { "Unexpected start script layout" }
        bat.writeText(windowed)
    }
}
val obfuscatedFixtures = rootProject.layout.projectDirectory.dir("benchmarks/obfuscated/proguard8")
tasks.test {
    inputs.files(obfuscatedFixtures.asFileTree.matching { include("*.jar", "*.mapping.txt") }).withPropertyName("obfuscatedFixtures")
    systemProperty("classrevive.obfuscatedFixtures", obfuscatedFixtures.asFile.absolutePath)
}
application {
    mainClass.set("dev.classrevive.cli.Main")
    applicationName = "classrevive"
}
// Phase 2BK: the official scoreboard. Real libraries from Maven Central, hash-checked by gradle/verification-metadata.xml,
// laid out as benchmark samples with their compile-time dependencies in <sample>.deps/ (BenchmarkRunner). They are never
// committed, never put on a classpath of this build and never executed: ClassRevive decompiles snapshots of them and javac
// only reads the dependencies. "dev" is measured while developing; "holdout" holds libraries no rule was tuned on.
val scoreboardCorpora = mapOf(
    "dev" to mapOf(
        "com.google.code.gson:gson:2.13.1" to listOf("com.google.errorprone:error_prone_annotations:2.38.0"),
        "org.ow2.asm:asm:9.11.1" to listOf(),
        "org.benf:cfr:0.152" to listOf(),
        "com.google.guava:guava:17.0" to listOf("com.google.code.findbugs:jsr305:3.0.2"),
        // Phase 2BQ: its optional dependencies at the versions its POM declares (xz, zstd-jni, brotli); zstd-jni's native
        // libraries are resources, which the verifier drops with every non-class entry.
        "org.apache.commons:commons-compress:1.27.1" to listOf("commons-io:commons-io:2.22.0", "org.apache.commons:commons-lang3:3.16.0",
            "commons-codec:commons-codec:1.22.0", "org.ow2.asm:asm:9.11.1", "org.tukaani:xz:1.10", "com.github.luben:zstd-jni:1.5.6-4",
            "org.brotli:dec:0.1.2"),
        "org.apache.ant:ant:1.10.17" to listOf("org.apache.ant:ant-launcher:1.10.17")),
    "holdout" to mapOf(
        "com.googlecode.javaewah:JavaEWAH:1.1.13" to listOf(),
        "net.sf.jopt-simple:jopt-simple:5.0.4" to listOf(),
        "org.json:json:20231013" to listOf()),
    // Phase 2BT: a larger, more modern holdout. No rule was tuned on these; the dependencies are each POM's compile and
    // provided ones (optional included) at the versions it declares (resilience4j declares slf4j-api at runtime scope).
    // Jackson is 2.17.3 because 2.18 and 3.0 carry Java 22/23 multi-release variants.
    "holdout2" to mapOf(
        "org.apache.commons:commons-lang3:3.17.0" to listOf(),
        "org.apache.commons:commons-text:1.13.0" to listOf("org.apache.commons:commons-lang3:3.17.0"),
        "com.fasterxml.jackson.core:jackson-core:2.17.3" to listOf(),
        "com.fasterxml.jackson.core:jackson-databind:2.17.3" to listOf("com.fasterxml.jackson.core:jackson-annotations:2.17.3",
            "com.fasterxml.jackson.core:jackson-core:2.17.3"),
        "joda-time:joda-time:2.13.0" to listOf("org.joda:joda-convert:1.9.2"),
        "org.slf4j:slf4j-api:2.0.16" to listOf(),
        "org.jsoup:jsoup:1.18.3" to listOf("org.jspecify:jspecify:1.0.0"),
        "info.picocli:picocli:4.7.6" to listOf(),
        "com.github.ben-manes.caffeine:caffeine:3.1.8" to listOf("org.checkerframework:checker-qual:3.37.0",
            "com.google.errorprone:error_prone_annotations:2.21.1"),
        "org.antlr:antlr4-runtime:4.13.2" to listOf(),
        "io.jenetics:jenetics:8.1.0" to listOf(),
        "dev.mccue:json:2024.11.20" to listOf("org.jspecify:jspecify:1.0.0"),
        "io.github.resilience4j:resilience4j-core:2.2.0" to listOf("org.slf4j:slf4j-api:1.7.30")))
// One non-transitive configuration per sample (phase 2BT): a single shared one resolved version conflicts between samples
// (commons-lang3 3.16.0 for commons-compress, 3.17.0 in holdout2) to the newest and laid out only that.
fun jarName(coordinate: String) = coordinate.split(':').let { "${it[1]}-${it[2]}.jar" }
val scoreboardCorpusTasks = scoreboardCorpora.mapValues { (set, samples) ->
    val libraries = samples.mapValues { (sample, deps) ->
        configurations.create("scoreboard_${set}_" + sample.replace(Regex("[^A-Za-z0-9]"), "_")) {
            isCanBeConsumed = false; isTransitive = false
        }.also { configuration -> (listOf(sample) + deps).forEach { dependencies.add(configuration.name, it) } }
    }
    tasks.register<Sync>("scoreboardCorpus${set.replaceFirstChar { it.uppercase() }}") {
        description = "Lays out the '$set' scoreboard corpus (real libraries plus <sample>.deps/ folders) under build/scoreboard-corpus/$set."
        group = "verification"
        into(rootProject.layout.buildDirectory.dir("scoreboard-corpus/$set"))
        for ((sample, deps) in samples) {
            from(libraries.getValue(sample)) { include(jarName(sample)) }
            for (dep in deps) from(libraries.getValue(sample)) { include(jarName(dep)); into(jarName(sample) + ".deps") }
        }
    }
}
tasks.register<JavaExec>("scoreboard") {
    description = "Runs 'classrevive benchmark' on a scoreboard corpus (-PscoreboardSet=dev|holdout|holdout2, default dev) into a new build/scoreboard/ folder."
    group = "verification"
    val set = providers.gradleProperty("scoreboardSet").getOrElse("dev")
    val corpus = scoreboardCorpusTasks[set] ?: throw GradleException("scoreboardSet must be one of ${scoreboardCorpora.keys}")
    dependsOn(corpus)
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set(application.mainClass)
    // -PscoreboardResume=<folder under build/scoreboard> continues that interrupted run instead of starting a new one.
    val resumeFrom = providers.gradleProperty("scoreboardResume").orNull
    val output = if (resumeFrom != null) rootProject.layout.buildDirectory.dir("scoreboard/$resumeFrom")
        else rootProject.layout.buildDirectory.dir("scoreboard/$set-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")))
    argumentProviders.add(CommandLineArgumentProvider {
        // Phase 2CH: engine outputs are cached across runs under build/engine-cache (keyed by input and engine bytes).
        listOf("benchmark", corpus.get().destinationDir.path, "--output", output.get().asFile.path, "--timeout-seconds", "600",
            "--engine-cache", rootProject.layout.buildDirectory.dir("engine-cache").get().asFile.path) +
            (if (resumeFrom != null) listOf("--resume", "true") else listOf())
    })
    doFirst { output.get().asFile.parentFile.mkdirs() }
    // Exit 1 also means some attempt was not structurally complete, which is a measurement, not a build failure;
    // only benchmark.json proves the run finished.
    isIgnoreExitValue = true
    doLast {
        val code = executionResult.get().exitValue
        if (code !in 0..1 || !output.get().file("benchmark.json").asFile.isFile) throw GradleException("benchmark did not finish (exit $code)")
        logger.lifecycle("Scoreboard: " + output.get().file("benchmark.md").asFile.path)
    }
}
// The distribution carries only these user documents, never the whole docs/ folder. The desktop application opens
// docs/guia-iniciante.md from it, and obfuscation reports cite docs/obfuscation-detection.md.
val publicDocs = listOf("README.md", "getting-started.md", "guia-iniciante.md", "cli-reference.md", "benchmarks.md", "status.md",
    "reports.md", "obfuscation-detection.md", "dynamic-diagnostics.md", "pass-sdk.md", "sandbox.md", "behavior-protocol.md",
    "images/desktop-class-dark.png", "images/desktop-class-dark-pt-BR.png", "images/desktop-overview-dark.png",
    "images/desktop-compare-light.png")
publicDocs.forEach { check(rootProject.file("docs/$it").isFile) { "Public document docs/$it is missing" } }
distributions {
    main {
        contents {
            from(guiStartScripts) { into("bin") }
            // Phase 2CW: dex2jar is the conversion worker's own class path, next to the dex module's jar, never the application's.
            from(dexTools) { into("lib/dex2jar") }
            from(rootProject.file("LICENSE"))
            from(rootProject.file("NOTICE"))
            from(rootProject.file("THIRD_PARTY_NOTICES.md"))
            from(rootProject.file("README.md"))
            from(rootProject.file("README.pt-BR.md"))
            from(rootProject.file("DISCLAIMER.md"))
            from(rootProject.file("ARCHITECTURE.md"))
            from(rootProject.file("CHANGELOG.md"))
            from(rootProject.file("SECURITY.md"))
            from(rootProject.file("CONTRIBUTING.md"))
            from(rootProject.file("docs")) { include(publicDocs); into("docs") }
            from(rootProject.file("third-party-licenses")) { into("third-party-licenses") }
        }
    }
}
