plugins { `java-library` }
dependencies {
    api(project(":model"))
    api(project(":engine-spi"))
    implementation("org.ow2.asm:asm:9.11.1")
    implementation("org.ow2.asm:asm-tree:9.11.1")
    implementation("org.ow2.asm:asm-analysis:9.11.1")
    implementation("org.ow2.asm:asm-util:9.11.1")
}
