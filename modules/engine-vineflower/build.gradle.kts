plugins { `java-library` }
dependencies {
    api(project(":engine-spi"))
    runtimeOnly("org.vineflower:vineflower:1.11.1")
}
