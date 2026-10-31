plugins { `java-library` }
dependencies {
    api(project(":model"))
    implementation(project(":bytecode"))
}
