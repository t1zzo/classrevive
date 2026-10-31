plugins { `java-library` }
// Phase G3: the desktop application. Swing (in the JDK) with FlatLaf for the look and RSyntaxTextArea for the code view,
// chosen after a comparison spike. Everything it runs goes through the workbench layer.
dependencies {
    implementation(project(":workbench"))
    implementation(project(":intake"))
    implementation(project(":bytecode"))
    implementation("com.formdev:flatlaf:3.7.2")
    implementation("com.fifesoft:rsyntaxtextarea:4.0.1")
}
// The opt-in screenshot tour (ScreenshotTour) runs only when -Pclassrevive.screenshots=<folder> is given; these
// properties reach the test JVM as system properties.
tasks.test {
    providers.gradlePropertiesPrefixedBy("classrevive.screenshots").get().forEach { (key, value) -> systemProperty(key, value) }
}
