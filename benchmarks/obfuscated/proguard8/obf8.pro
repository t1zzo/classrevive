# ProGuard 7.10.0 configuration for obf8.jar (see README.md).
# -injars, -outjars, -libraryjars (the JDK's java.base.jmod) and -printmapping are passed by build.gradle.kts.
# Rename only: no optimization, no shrinking, so every member survives and appears in the mapping.
-dontoptimize
-dontshrink
-keep public class fixtures.obf.Main { public static java.lang.String describe(int); }
-keepattributes LineNumberTable,SourceFile
-renamesourcefileattribute SourceFile
# Deliberately no serialization keep rules: the mapping must contain renamed serialVersionUID/writeObject lines.
