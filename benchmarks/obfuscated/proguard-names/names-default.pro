# ProGuard 7.10.0 configuration for names-default.jar (see README.md).
# The usual release configuration: names only, SourceFile replaced by the generic "SourceFile", no LocalVariableTable.
-dontoptimize
-dontshrink
-keep public class app.Main { public static void main(java.lang.String[]); }
-keepattributes LineNumberTable,SourceFile
-renamesourcefileattribute SourceFile
