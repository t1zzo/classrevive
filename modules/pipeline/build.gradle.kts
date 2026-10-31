plugins { `java-library` }
dependencies {
    api(project(":engine-spi"))
    implementation(project(":intake"))
    api(project(":selection"))
    implementation(project(":bytecode"))
    implementation(project(":transforms"))
    // Phase 2CW: APK and dex inputs are converted to class files by dex2jar in a worker (see modules/dex).
    implementation(project(":dex"))
    api(project(":sandbox"))
    implementation("org.ow2.asm:asm:9.11.1")
    implementation("org.ow2.asm:asm-tree:9.11.1")
    testImplementation(project(":engine-cfr"))
    testImplementation(project(":engine-vineflower"))
    testImplementation(project(":engine-procyon"))
    testImplementation(project(":engine-jadx"))
    testImplementation("org.ow2.asm:asm:9.11.1")
    testImplementation("org.ow2.asm:asm-tree:9.11.1")
}
// Phase 2AR: a real multi-release library (Gson 2.13.1, Apache-2.0) and its compile-time annotation dependency,
// resolved and hash-checked by Gradle for one regression test. They are never committed, never put on a test or
// runtime classpath and never executed: the test copies them into a temporary folder and decompiles the copy.
val realLibraryFixture: Configuration by configurations.creating { isCanBeConsumed = false; isTransitive = false }
dependencies {
    realLibraryFixture("com.google.code.gson:gson:2.13.1")
    realLibraryFixture("com.google.errorprone:error_prone_annotations:2.38.0")
}
tasks.test {
    inputs.files(realLibraryFixture).withPropertyName("realLibraryFixture")
    jvmArgumentProviders.add(CommandLineArgumentProvider {
        realLibraryFixture.files.map { "-Dclassrevive.fixture." + it.name.substringBefore('-') + "=" + it.absolutePath }
    })
}
// Phase 2AT: permanent failure-lab regression cases (reduced authored classes plus expected.json). The test suite
// re-decompiles each case and fails when a method drops below its recorded minimum level; regressionTest runs only that.
val regressionCases = rootProject.layout.projectDirectory.dir("benchmarks/regressions")
tasks.withType<Test>().configureEach {
    inputs.dir(regressionCases).withPropertyName("regressionCases")
    systemProperty("classrevive.regressions", regressionCases.asFile.absolutePath)
}
tasks.register<Test>("regressionTest") {
    description = "Fails when a committed failure-lab regression case (benchmarks/regressions) drops below its expected per-method levels."
    group = "verification"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    filter { includeTestsMatching("dev.classrevive.pipeline.RegressionCasesTest") }
}
// Phase 2CC: GhidraIntegrationTest runs only when GHIDRA_INSTALL_DIR names a local Ghidra (never downloaded); the variable
// is a test input so a run with Ghidra is not taken as up to date from a run without it.
tasks.withType<Test>().configureEach {
    inputs.property("ghidraInstallDir", System.getenv("GHIDRA_INSTALL_DIR") ?: "")
}
// Phase 2CW: dex2jar's jars are the conversion worker's own class path, never this build's (see modules/dex).
val dexTools: Configuration by configurations.creating { isCanBeConsumed = false }
dependencies { dexTools(project(path = ":dex", configuration = "dexTools")) }
tasks.withType<Test>().configureEach {
    inputs.files(dexTools).withPropertyName("dexTools")
    jvmArgumentProviders.add(CommandLineArgumentProvider { listOf("-Dclassrevive.dex2jar.classpath=" + dexTools.asPath) })
}
// Phase 2DD: the committed Skidfuscator answer-key corpus (benchmarks/obfuscated/skidfuscator) for the automatic reversal.
val skidfuscatorFixtures = rootProject.layout.projectDirectory.dir("benchmarks/obfuscated/skidfuscator")
tasks.withType<Test>().configureEach {
    inputs.files(skidfuscatorFixtures.asFileTree.matching { include("*.jar") }).withPropertyName("skidfuscatorFixtures")
    systemProperty("classrevive.skidfuscatorFixtures", skidfuscatorFixtures.asFile.absolutePath)
}
