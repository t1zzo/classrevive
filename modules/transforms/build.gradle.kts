plugins { `java-library` }
dependencies {
    api(project(":engine-spi"))
    implementation("org.ow2.asm:asm:9.11.1")
    implementation("org.ow2.asm:asm-tree:9.11.1")
    implementation("org.ow2.asm:asm-commons:9.11.1")
    implementation("org.ow2.asm:asm-analysis:9.11.1")
    // Phase 2CR: XXH3 exactly as Skidfuscator computes it (its sdk/* runtime is a copy of this library); Apache-2.0.
    implementation("net.openhft:zero-allocation-hashing:0.16")
    testImplementation("org.ow2.asm:asm-util:9.11.1")
}
// Phase 2CN: a real Skidfuscator output of ClassRevive's own fixture source (benchmarks/obfuscated/skidfuscator).
val skidfuscatorFixtures = rootProject.layout.projectDirectory.dir("benchmarks/obfuscated/skidfuscator")
tasks.test {
    inputs.files(skidfuscatorFixtures.asFileTree.matching { include("*.jar") }).withPropertyName("skidfuscatorFixtures")
    systemProperty("classrevive.skidfuscatorFixtures", skidfuscatorFixtures.asFile.absolutePath)
}
