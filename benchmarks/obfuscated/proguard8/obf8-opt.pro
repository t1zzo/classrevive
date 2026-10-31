# ProGuard 7.10.0 configuration for obf8-opt.jar (see README.md).
# -injars, -outjars, -libraryjars (the JDK's java.base.jmod) and -printmapping are passed by build.gradle.kts.
# Default optimization and shrinking: private helpers are inlined and the mapping gains inline-frame lines.
-keep public class fixtures.obf.Main { public static java.lang.String describe(int); }
-keepattributes LineNumberTable,SourceFile
-renamesourcefileattribute SourceFile
# Deliberately no serialization keep rules, as in obf8.pro.
