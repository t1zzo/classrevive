package demo.util;

/** Lives in classes2.dex: the second dex file of the fixture APK. */
public final class Strings {
    private Strings() {}

    public static String trimToEmpty(String text) { return text == null ? "" : text.trim(); }

    public static String repeat(char c, int times) {
        if (times < 0) throw new IllegalArgumentException("times < 0");
        char[] out = new char[times];
        java.util.Arrays.fill(out, c);
        return new String(out);
    }
}
