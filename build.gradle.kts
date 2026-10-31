plugins { base }
val fixtureTasks = listOf("legacy8" to 8, "java8" to 8, "java17" to 17, "java21" to 21, "edge8" to 8, "shortnames8" to 8).map { (fixture, release) ->
    val compile = tasks.register<JavaCompile>("compileFixture_$fixture") {
        source(fileTree("benchmarks/fixtures/$fixture") { include("**/*.java") })
        classpath = files()
        destinationDirectory.set(layout.buildDirectory.dir("fixtures/$fixture"))
        options.release.set(release)
        sourceCompatibility = release.toString()
        targetCompatibility = release.toString()
        options.encoding = "UTF-8"
        options.compilerArgs.add("-proc:none")
    }
    tasks.register<Jar>("jarFixture_$fixture") {
        dependsOn(compile)
        from(compile.flatMap { it.destinationDirectory })
        destinationDirectory.set(layout.buildDirectory.dir("benchmark-corpus"))
        archiveFileName.set("$fixture.jar")
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
    }
}
// Regression fixtures produced by a real obfuscator (ProGuard, GPL-2.0, run only as an external build tool; see
// benchmarks/obfuscated/proguard8/README.md). The committed jars and mappings are the fixtures; these tasks regenerate them.
val proguardTool: Configuration by configurations.creating { isCanBeConsumed = false }
dependencies { proguardTool("com.guardsquare:proguard-base:7.10.0") }
val obfuscatedDir = layout.projectDirectory.dir("benchmarks/obfuscated/proguard8")
val compileObfuscatedSource = tasks.register<JavaCompile>("compileObfuscatedSource_proguard8") {
    source(fileTree(obfuscatedDir.dir("src")) { include("**/*.java") })
    classpath = files()
    destinationDirectory.set(layout.buildDirectory.dir("obfuscated/proguard8/classes"))
    options.release.set(8)
    sourceCompatibility = "8"
    targetCompatibility = "8"
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-proc:none", "-Xlint:-options"))
}
val obfuscatedVariants = listOf("obf8", "obf8-opt").map { variant ->
    val raw = layout.buildDirectory.dir("obfuscated/proguard8/$variant")
    val run = tasks.register<JavaExec>("proguardFixture_$variant") {
        classpath = proguardTool
        mainClass.set("proguard.ProGuard")
        inputs.file(obfuscatedDir.file("$variant.pro"))
        inputs.dir(compileObfuscatedSource.flatMap { it.destinationDirectory })
        outputs.dir(raw)
        doFirst { delete(raw); mkdir(raw) }
        argumentProviders.add(CommandLineArgumentProvider {
            val out = raw.get().asFile
            listOf("-include", obfuscatedDir.file("$variant.pro").asFile.path,
                "-injars", compileObfuscatedSource.get().destinationDirectory.get().asFile.path,
                "-outjars", out.resolve("classes").path,
                "-libraryjars", javaLauncher.get().metadata.installationPath.file("jmods/java.base.jmod").asFile.path + "(!**.jar;!module-info.class)",
                "-printmapping", out.resolve("mapping.txt").path)
        })
    }
    // ProGuard stamps jar entries with the current time, so it writes a directory and Gradle packs it reproducibly.
    val pack = tasks.register<Zip>("packProguardFixture_$variant") {
        from(run.map { it.outputs.files.singleFile.resolve("classes") })
        destinationDirectory.set(layout.buildDirectory.dir("obfuscated/proguard8/packed"))
        archiveFileName.set("$variant.jar")
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
    }
    Triple(variant, run, pack)
}
tasks.register<Copy>("regenerateObfuscatedFixtures") {
    description = "Re-runs ProGuard and overwrites the committed obfuscated jars and mappings."
    for ((variant, run, pack) in obfuscatedVariants) {
        from(pack)
        from(run) { include("mapping.txt"); rename { "$variant.mapping.txt" } }
    }
    into(obfuscatedDir)
    // ProGuard writes the platform line separator; the committed mappings always use LF (see .gitattributes).
    doLast { obfuscatedVariants.forEach { (variant) -> obfuscatedDir.file("$variant.mapping.txt").asFile.let { it.writeText(it.readText().replace("\r\n", "\n")) } } }
}
tasks.register("verifyObfuscatedFixtures") {
    description = "Fails when a fresh ProGuard run no longer reproduces the committed obfuscated jars and mappings."
    val pairs = obfuscatedVariants.flatMap { (variant, run, pack) ->
        dependsOn(run, pack)
        listOf(obfuscatedDir.file("$variant.jar") to pack.flatMap { it.archiveFile },
            obfuscatedDir.file("$variant.mapping.txt") to layout.buildDirectory.file("obfuscated/proguard8/$variant/mapping.txt"))
    }
    doLast {
        fun bytes(file: File) = if (file.name.endsWith(".txt")) file.readText().replace("\r\n", "\n").toByteArray() else file.readBytes()
        for ((committed, generated) in pairs)
            if (!bytes(committed.asFile).contentEquals(bytes(generated.get().asFile)))
                throw GradleException("${committed.asFile.name} differs from a fresh ProGuard run")
    }
}
// Phase 2CW: an Android fixture made by our own code. benchmarks/android/src is compiled for Java 8 against compile-only
// stand-ins of three framework classes (stubs/, never dexed), dx (Apache-2.0, bundled in dex2jar's d2j-external and run only
// as a build tool) turns demo/notes into classes.dex and demo/util into classes2.dex, and both are packed with a placeholder
// binary manifest into notes.apk. The committed notes.apk and notes.dex are the fixtures; these tasks regenerate them.
val dexTool: Configuration by configurations.creating { isCanBeConsumed = false }
dependencies { dexTool("de.femtopedia.dex2jar:dex-translator:2.4.38") }
val androidDir = layout.projectDirectory.dir("benchmarks/android")
val compileAndroidStubs = tasks.register<JavaCompile>("compileAndroidFixtureStubs") {
    source(fileTree(androidDir.dir("stubs")) { include("**/*.java") })
    classpath = files()
    destinationDirectory.set(layout.buildDirectory.dir("android/stubs"))
    options.release.set(8)
    sourceCompatibility = "8"
    targetCompatibility = "8"
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-proc:none", "-Xlint:-options"))
}
val compileAndroidFixture = tasks.register<JavaCompile>("compileAndroidFixture") {
    source(fileTree(androidDir.dir("src")) { include("**/*.java") })
    classpath = files(compileAndroidStubs.flatMap { it.destinationDirectory })
    destinationDirectory.set(layout.buildDirectory.dir("android/classes"))
    options.release.set(8)
    sourceCompatibility = "8"
    targetCompatibility = "8"
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-proc:none", "-Xlint:-options", "-g"))
}
val androidDexes = listOf("classes.dex" to "demo/notes/**", "classes2.dex" to "demo/util/**").map { (dex, classes) ->
    val split = tasks.register<Sync>("splitAndroidFixture_${dex.substringBefore('.')}") {
        from(compileAndroidFixture.flatMap { it.destinationDirectory }) { include(classes) }
        into(layout.buildDirectory.dir("android/split/${dex.substringBefore('.')}"))
    }
    tasks.register<JavaExec>("dexAndroidFixture_${dex.substringBefore('.')}") {
        classpath = dexTool
        mainClass.set("com.android.dx.command.Main")
        val out = layout.buildDirectory.file("android/dex/$dex")
        inputs.files(split)
        outputs.file(out)
        doFirst { out.get().asFile.parentFile.mkdirs() }
        argumentProviders.add(CommandLineArgumentProvider {
            listOf("--dex", "--output=" + out.get().asFile.path, split.get().destinationDir.path)
        })
    }
}
val packAndroidFixture = tasks.register<Zip>("packAndroidFixture") {
    from(androidDexes.map { task -> task.map { it.outputs.files.singleFile } })
    from(androidDir.dir("package")) { include("AndroidManifest.xml") }
    destinationDirectory.set(layout.buildDirectory.dir("android/packed"))
    archiveFileName.set("notes.apk")
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}
tasks.register<Copy>("regenerateAndroidFixtures") {
    description = "Recompiles and re-dexes benchmarks/android and overwrites the committed notes.apk and notes.dex."
    from(packAndroidFixture)
    from(androidDexes.first()) { rename { "notes.dex" } }
    into(androidDir)
}
val copyObfuscatedFixtures = tasks.register<Copy>("copyObfuscatedFixtures") {
    from(obfuscatedDir) { include("*.jar") }
    into(layout.buildDirectory.dir("benchmark-corpus"))
}
tasks.register("benchmarkFixtures") { dependsOn(fixtureTasks, copyObfuscatedFixtures) }
tasks.register("checkRegressions") {
    description = "Re-decompiles every failure-lab regression case in benchmarks/regressions and fails if a method got worse."
    group = "verification"
    dependsOn(":pipeline:regressionTest")
}
allprojects {
    group = "dev.classrevive"
    version = "0.1.0"
    repositories { mavenCentral() }
}
subprojects {
    apply(plugin = "java-library")
    extensions.configure<JavaPluginExtension> {
        toolchain { languageVersion.set(JavaLanguageVersion.of(21)) }
    }
    dependencies {
        "testImplementation"(platform("org.junit:junit-bom:5.14.4"))
        "testImplementation"("org.junit.jupiter:junit-jupiter")
        "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
    }
    tasks.withType<Test>().configureEach { useJUnitPlatform() }
    tasks.withType<JavaCompile>().configureEach { options.encoding = "UTF-8" }
}
