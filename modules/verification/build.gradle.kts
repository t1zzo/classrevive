plugins { `java-library` }
dependencies {
    api(project(":engine-spi"))
    api(project(":model"))
    implementation(project(":bytecode"))
    implementation(project(":intake"))
    implementation("org.ow2.asm:asm:9.11.1")
    implementation("org.ow2.asm:asm-tree:9.11.1")
    implementation("org.ow2.asm:asm-util:9.11.1")
}
