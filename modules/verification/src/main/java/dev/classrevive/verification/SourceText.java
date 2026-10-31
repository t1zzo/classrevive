package dev.classrevive.verification;

/** Phase 2BX: structural text scans over Java source that ignore comments and literals. */
public final class SourceText {
    private SourceText() {}
    /**
     * The text with every comment and the inside of every string, text block and char literal replaced by spaces
     * (line breaks kept), so offsets match the original and braces, {@code $} or keywords inside them are not seen.
     */
    public static String mask(String text) {
        var out = text.toCharArray(); int n = out.length;
        for (int i = 0; i < n; ) {
            char c = out[i];
            if (c == '/' && i + 1 < n && out[i + 1] == '/') {
                while (i < n && out[i] != '\n' && out[i] != '\r') out[i++] = ' ';
            } else if (c == '/' && i + 1 < n && out[i + 1] == '*') {
                out[i++] = ' '; out[i++] = ' ';
                while (i < n && !(out[i] == '*' && i + 1 < n && out[i + 1] == '/')) blank(out, i++);
                if (i < n) { out[i++] = ' '; out[i++] = ' '; }
            } else if (c == '"' && i + 2 < n && out[i + 1] == '"' && out[i + 2] == '"') {
                i += 3;
                while (i < n && !(out[i] == '"' && i + 2 < n && out[i + 1] == '"' && out[i + 2] == '"')) {
                    if (out[i] == '\\' && i + 1 < n) blank(out, i++);
                    blank(out, i++);
                }
                i += 3;
            } else if (c == '"' || c == '\'') {
                i++;
                while (i < n && out[i] != c && out[i] != '\n') {
                    if (out[i] == '\\' && i + 1 < n) blank(out, i++);
                    blank(out, i++);
                }
                i++;
            } else i++;
        }
        return new String(out);
    }
    private static void blank(char[] text, int i) { if (text[i] != '\n' && text[i] != '\r') text[i] = ' '; }
    /** The offset of the bracket that closes the one at {@code open} in masked text, or -1. */
    public static int close(String masked, int open) {
        char start = masked.charAt(open), end = start == '{' ? '}' : start == '(' ? ')' : start == '[' ? ']' : '>';
        int depth = 0;
        for (int i = open; i < masked.length(); i++) {
            char c = masked.charAt(i);
            if (c == start) depth++;
            else if (c == end && --depth == 0) return i;
        }
        return -1;
    }
}
