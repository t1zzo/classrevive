plugins { `java-library` }
// Phase 2CW: dex2jar (Apache-2.0, the maintained ThexXTURBOXx fork) turns Dalvik bytecode into class files. It runs only in
// DexWorker, a separate JVM with the usual worker limits, and reads the dex statically; target code is never loaded.
// d2j-external bundles its own copy of ASM's core classes (plus AsmBridge, which reaches into them), so dex2jar never joins
// the application's class path, where that copy could shadow ASM 9.11.1: the worker gets these jars as its own class path,
// from a folder next to this module's jar in the distribution (lib/dex2jar) or from a system property in tests.
val dexTools: Configuration by configurations.creating {
    isCanBeConsumed = true
    isCanBeResolved = true
    // The core classes come from d2j-external, where AsmBridge expects them.
    exclude(group = "org.ow2.asm", module = "asm")
}
dependencies {
    api(project(":engine-spi"))
    dexTools("de.femtopedia.dex2jar:dex-translator:2.4.38")
    compileOnly("de.femtopedia.dex2jar:dex-translator:2.4.38")
    testImplementation("org.ow2.asm:asm:9.11.1")
    testImplementation("org.ow2.asm:asm-tree:9.11.1")
    testImplementation("org.ow2.asm:asm-analysis:9.11.1")
}
tasks.withType<Test>().configureEach {
    inputs.files(dexTools).withPropertyName("dexTools")
    jvmArgumentProviders.add(CommandLineArgumentProvider { listOf("-Dclassrevive.dex2jar.classpath=" + dexTools.asPath) })
}
