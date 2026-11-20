plugins { `java-library` }
// Phase 2DO: JD-Core as an optional fifth engine. JD-Core is GPL-3.0: it is never a dependency of this build, never
// bundled and never downloaded. The user supplies its jar (CLASSREVIVE_JDCORE_JAR); the worker calls it by reflection in
// its own JVM, so nothing here is compiled against it.
dependencies {
    api(project(":engine-spi"))
}
