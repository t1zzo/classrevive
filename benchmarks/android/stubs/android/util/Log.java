package android.util;

/** Compile-time stand-in for the Android framework class; never put in the fixture's dex. */
public final class Log {
    private Log() { }
    public static int d(String tag, String message) { return 0; }
    public static int w(String tag, String message) { return 0; }
}
