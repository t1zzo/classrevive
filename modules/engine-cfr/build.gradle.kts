plugins { `java-library` }
dependencies {
    api(project(":engine-spi"))
    runtimeOnly("org.benf:cfr:0.152")
}
