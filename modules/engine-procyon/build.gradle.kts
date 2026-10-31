plugins { `java-library` }
dependencies {
    api(project(":engine-spi"))
    implementation("org.bitbucket.mstrobel:procyon-compilertools:0.6.0")
}
