plugins { `java-library` }
// Phase G1: the application layer between front ends (CLI, desktop GUI) and the pipeline. Plain Java, no UI code.
dependencies {
    api(project(":pipeline"))
    // Phase G5: the obfuscator classifier (Phase 2BZ) for the desktop application's detection.
    implementation(project(":bytecode"))
    implementation(project(":engine-cfr"))
    implementation(project(":engine-vineflower"))
    implementation(project(":engine-procyon"))
    implementation(project(":engine-jadx"))
}
