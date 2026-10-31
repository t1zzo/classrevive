package fixtures.shortnames;

/** Authored stress sample, not output from a commercial obfuscator. Never run by the benchmark. */
public class a {
    private final int b;
    public a(int c) { b = c; }
    public String a(char[] c) {
        char[] d = c.clone();
        for (int e = 0; e < d.length; e++) d[e] = (char) (d[e] ^ b);
        return new String(d);
    }
    public int a(int c) {
        if ((c & 1) == 0) return c * 2;
        return c + 1;
    }
}
