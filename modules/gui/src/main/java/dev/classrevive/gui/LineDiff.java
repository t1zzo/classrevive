package dev.classrevive.gui;

import java.util.*;

/**
 * Phase G4: a line diff (Myers, O((N+M)D)) aligned for side-by-side display: both sides get the same number of rows,
 * with blank filler rows where one side has nothing. Whitespace does not count (engines indent differently): lines are
 * compared trimmed, with runs of spaces collapsed.
 */
final class LineDiff {
    enum Kind { SAME, CHANGED, REMOVED, ADDED }

    /** {@code left}/{@code right}: the aligned rows (null for a filler row); {@code kinds}: one per row. */
    record Aligned(List<String> left, List<String> right, List<Kind> kinds) {
        String leftText() { return text(left); }
        String rightText() { return text(right); }
        long count(Kind kind) { return kinds.stream().filter(k -> k == kind).count(); }
        private static String text(List<String> rows) {
            var out = new StringBuilder();
            for (String row : rows) out.append(row == null ? "" : row).append('\n');
            return out.toString();
        }
    }

    /** Above this many lines in total, or this many differences, rows are paired by position instead. */
    static final int MAX_LINES = 60_000, MAX_EDITS = 6_000;

    private LineDiff() {}

    static Aligned align(String leftText, String rightText) {
        var left = lines(leftText);
        var right = lines(rightText);
        var script = left.size() + right.size() > MAX_LINES ? null : script(left, right);
        var rows = new Aligned(new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
        if (script == null) {
            for (int i = 0; i < Math.max(left.size(), right.size()); i++) {
                String l = i < left.size() ? left.get(i) : null, r = i < right.size() ? right.get(i) : null;
                add(rows, l, r, l == null ? Kind.ADDED : r == null ? Kind.REMOVED : key(l).equals(key(r)) ? Kind.SAME : Kind.CHANGED);
            }
            return rows;
        }
        // Walk the edit script; a run of removals followed by additions is shown as changed rows side by side.
        int i = 0, j = 0;
        var removed = new ArrayList<String>();
        var added = new ArrayList<String>();
        for (char op : script) {
            if (op == '=') { flush(rows, removed, added); add(rows, left.get(i++), right.get(j++), Kind.SAME); }
            else if (op == '-') removed.add(left.get(i++));
            else added.add(right.get(j++));
        }
        flush(rows, removed, added);
        return rows;
    }

    private static void flush(Aligned rows, List<String> removed, List<String> added) {
        for (int k = 0; k < Math.max(removed.size(), added.size()); k++) {
            String l = k < removed.size() ? removed.get(k) : null, r = k < added.size() ? added.get(k) : null;
            add(rows, l, r, l == null ? Kind.ADDED : r == null ? Kind.REMOVED : Kind.CHANGED);
        }
        removed.clear();
        added.clear();
    }

    private static void add(Aligned rows, String left, String right, Kind kind) {
        rows.left().add(left);
        rows.right().add(right);
        rows.kinds().add(kind);
    }

    /** The shortest edit script ('=', '-', '+'), or null when it would need more than {@link #MAX_EDITS} edits. */
    static char[] script(List<String> a, List<String> b) {
        int n = a.size(), m = b.size(), max = Math.min(n + m, MAX_EDITS), offset = max + 1;
        var ka = a.stream().map(LineDiff::key).toList();
        var kb = b.stream().map(LineDiff::key).toList();
        int[] v = new int[2 * max + 3];
        var trace = new ArrayList<int[]>();
        for (int d = 0; d <= max; d++) {
            trace.add(v.clone());
            for (int k = -d; k <= d; k += 2) {
                int x = k == -d || (k != d && v[offset + k - 1] < v[offset + k + 1]) ? v[offset + k + 1] : v[offset + k - 1] + 1;
                int y = x - k;
                while (x < n && y < m && ka.get(x).equals(kb.get(y))) { x++; y++; }
                v[offset + k] = x;
                if (x >= n && y >= m) return backtrack(trace, n, m, offset);
            }
        }
        return null;
    }

    private static char[] backtrack(List<int[]> trace, int n, int m, int offset) {
        var ops = new StringBuilder();
        int x = n, y = m;
        for (int d = trace.size() - 1; d >= 0; d--) {
            int[] v = trace.get(d);
            int k = x - y;
            int previousK = k == -d || (k != d && v[offset + k - 1] < v[offset + k + 1]) ? k + 1 : k - 1;
            int previousX = d == 0 ? 0 : v[offset + previousK], previousY = previousX - previousK;
            while (x > previousX && y > previousY) { ops.append('='); x--; y--; }
            if (d > 0) { if (x == previousX) { ops.append('+'); y--; } else { ops.append('-'); x--; } }
        }
        return ops.reverse().toString().toCharArray();
    }

    private static String key(String line) { return line.strip().replaceAll("\\s+", " "); }

    static List<String> lines(String text) {
        var lines = new ArrayList<>(Arrays.asList(text.replace("\r\n", "\n").split("\n", -1)));
        if (!lines.isEmpty() && lines.getLast().isEmpty()) lines.removeLast();
        return lines;
    }
}
