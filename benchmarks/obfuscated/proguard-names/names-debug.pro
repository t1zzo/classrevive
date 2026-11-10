# ProGuard 7.10.0 configuration for names-debug.jar (see README.md).
# -injars, -outjars, -libraryjars (the JDK's java.base.jmod) and -printmapping are passed by build.gradle.kts.
# Rename only, and the debug attributes a project that wants readable stack traces keeps: the real SourceFile names
# and the LocalVariableTable (ProGuard does not rename local variables).
-dontoptimize
-dontshrink
-keep public class app.Main { public static void main(java.lang.String[]); }
-keepattributes LineNumberTable,SourceFile,LocalVariableTable
