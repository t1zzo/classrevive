plugins { `java-library` }
dependencies {
    api(project(":engine-spi"))
    implementation("io.github.skylot:jadx-core:1.5.3")
    implementation("io.github.skylot:jadx-java-input:1.5.3")
    // Phase 2CX: JADX's own dex reader, for Android inputs. Its runtime dependencies smali-baksmali (published only on
    // Google's repository) and Guava serve smali disassembly, which ClassRevive never asks for, so they are left out.
    implementation("io.github.skylot:jadx-dex-input:1.5.3") {
        exclude(group = "com.android.tools.smali")
        exclude(group = "com.google.guava")
    }
    runtimeOnly("org.ow2.asm:asm:9.11.1")
}
