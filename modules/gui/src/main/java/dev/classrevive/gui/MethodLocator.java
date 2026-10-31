package dev.classrevive.gui;

import java.util.*;
import java.util.regex.*;

/**
 * Phase G4: where a method is declared in decompiled source, so the editor can mark it. Text only and best effort: a
 * class's body is found from its declaration by brace matching (strings, chars and comments skipped), and a method by
 * its name and parameter count inside that body but outside nested classes. Constructors are found by the class's
 * simple name, the class initializer by {@code static {}. Synthetic methods ({@code lambda$}, {@code access$}) have no
 * declaration and are not found. Overloads with the same parameter count are taken in order of appearance.
 */
final class MethodLocator {
    private MethodLocator() {}

    /** A located class body: offsets of its {@code {} and matching {@code }}. */
    record Body(int open, int close) {
        boolean contains(int offset) { return offset > open && offset < close; }
    }

    /**
     * 1-based declaration lines for the methods of each class in {@code text}. {@code methods} maps a class's internal
     * name ({@code a/B$C}) to its method keys ({@code name + descriptor}). Methods not found are absent from the result.
     */
    static Map<String,Map<String,Integer>> locate(String text, Map<String,? extends Collection<String>> methods) {
        String code = blank(text);
        var bodies = new LinkedHashMap<String,Body>();
        // Outer classes first: a nested class's body is recognized by being inside one already found.
        var outerFirst = methods.keySet().stream().sorted(Comparator.comparingInt(MethodLocator::nestingDepth)).toList();
        for (String className : outerFirst) {
            Body body = body(code, simpleName(className), nestingDepth(className), bodies.values());
            if (body != null) bodies.put(className, body);
        }
        var lines = lineStarts(text);
        var result = new LinkedHashMap<String,Map<String,Integer>>();
        for (var entry : methods.entrySet()) {
            Body body = bodies.get(entry.getKey());
            if (body == null) continue;
            var nested = bodies.values().stream().filter(other -> other != body && body.contains(other.open)).toList();
            var used = new HashSet<Integer>();
            var found = new LinkedHashMap<String,Integer>();
            for (String method : entry.getValue()) {
                int paren = method.indexOf('(');
                if (paren <= 0) continue;
                String name = method.substring(0, paren);
                if (name.startsWith("lambda$") || name.startsWith("access$") || name.contains("$")) continue;
                int offset = name.equals("<clinit>") ? staticBlock(code, body, nested, used)
                    : declaration(code, name.equals("<init>") ? simpleName(entry.getKey()) : name, parameters(method.substring(paren)), body, nested, used);
                if (offset < 0) continue;
                used.add(offset);
                found.put(method, line(lines, offset));
            }
            result.put(entry.getKey(), found);
        }
        return result;
    }

    /** The class's own name: after the last {@code /} and {@code $}; empty for anonymous classes ({@code A$1}). */
    static String simpleName(String internalName) {
        String simple = internalName.substring(Math.max(internalName.lastIndexOf('/'), internalName.lastIndexOf('$')) + 1);
        return simple.isEmpty() || Character.isDigit(simple.charAt(0)) ? "" : simple;
    }

    private static int nestingDepth(String internalName) {
        String simple = internalName.substring(internalName.lastIndexOf('/') + 1);
        return (int) simple.chars().filter(c -> c == '$').count();
    }

    /** Parameter count of a method descriptor's {@code (...)} part. */
    static int parameters(String descriptor) {
        int count = 0;
        for (int i = 1; i < descriptor.length() && descriptor.charAt(i) != ')'; i++) {
            char c = descriptor.charAt(i);
            if (c == '[') continue;
            if (c == 'L') i = descriptor.indexOf(';', i);
            count++;
        }
        return count;
    }

    private static Body body(String code, String simple, int depth, Collection<Body> known) {
        if (simple.isEmpty()) return null;
        var matcher = Pattern.compile("\\b(?:class|interface|enum|record|@interface)\\s+" + Pattern.quote(simple) + "\\b").matcher(code);
        while (matcher.find()) {
            int open = code.indexOf('{', matcher.end());
            if (open < 0) return null;
            int close = match(code, open);
            if (close < 0) continue;
            // A nested class sits inside another body; a top-level one does not.
            boolean inside = known.stream().anyMatch(body -> body.contains(open));
            if ((depth == 0) == !inside) return new Body(open, close);
        }
        return null;
    }

    private static int declaration(String code, String name, int parameters, Body body, List<Body> nested, Set<Integer> used) {
        var matcher = Pattern.compile("\\b" + Pattern.quote(name) + "\\s*\\(").matcher(code);
        matcher.region(body.open + 1, body.close);
        while (matcher.find()) {
            int start = matcher.start();
            if (used.contains(start) || nested.stream().anyMatch(other -> other.contains(start)) || !declarationContext(code, start)) continue;
            int close = match(code, matcher.end() - 1, '(', ')');
            if (close < 0 || count(code, matcher.end(), close) != parameters) continue;
            // A declaration's parameter list is followed by a body, "throws", or ";" (abstract and interface methods).
            int after = skipSpace(code, close + 1);
            if (after < code.length() && (code.charAt(after) == '{' || code.charAt(after) == ';' || code.startsWith("throws", after))) return start;
        }
        return -1;
    }

    /** The name follows a type, a modifier or a generic list, not an operator, a dot or a keyword such as {@code new}/{@code return}. */
    private static boolean declarationContext(String code, int start) {
        int i = start - 1;
        while (i >= 0 && Character.isWhitespace(code.charAt(i))) i--;
        if (i < 0) return false;
        char c = code.charAt(i);
        if (c == '>' || c == ']') return true;
        if (!Character.isJavaIdentifierPart(c)) return c == '}' || c == ';' || c == '{';
        int end = i + 1;
        while (i >= 0 && Character.isJavaIdentifierPart(code.charAt(i))) i--;
        String word = code.substring(i + 1, end);
        if (Set.of("new", "return", "throw", "else", "case", "assert", "yield").contains(word)) return false;
        return i < 0 || code.charAt(i) != '.';
    }

    private static int staticBlock(String code, Body body, List<Body> nested, Set<Integer> used) {
        var matcher = Pattern.compile("\\bstatic\\s*\\{").matcher(code);
        matcher.region(body.open + 1, body.close);
        while (matcher.find()) if (!used.contains(matcher.start()) && nested.stream().noneMatch(other -> other.contains(matcher.start()))) return matcher.start();
        return -1;
    }

    /** Top-level commas plus one, or 0 for an empty list, between {@code from} and the closing parenthesis. */
    private static int count(String code, int from, int close) {
        if (code.substring(from, close).isBlank()) return 0;
        int depth = 0, commas = 0;
        for (int i = from; i < close; i++) {
            char c = code.charAt(i);
            if (c == '<' || c == '(' || c == '[') depth++;
            else if (c == '>' || c == ')' || c == ']') depth--;
            else if (c == ',' && depth == 0) commas++;
        }
        return commas + 1;
    }

    private static int match(String code, int open) { return match(code, open, '{', '}'); }

    private static int match(String code, int open, char left, char right) {
        int depth = 0;
        for (int i = open; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c == left) depth++;
            else if (c == right && --depth == 0) return i;
        }
        return -1;
    }

    private static int skipSpace(String code, int i) {
        while (i < code.length() && Character.isWhitespace(code.charAt(i))) i++;
        return i;
    }

    /** The text with comments, string and char literals replaced by spaces (same length, newlines kept). */
    static String blank(String text) {
        var out = new StringBuilder(text);
        int i = 0, n = text.length();
        while (i < n) {
            char c = text.charAt(i);
            if (c == '/' && i + 1 < n && text.charAt(i + 1) == '/') { while (i < n && text.charAt(i) != '\n') out.setCharAt(i++, ' '); }
            else if (c == '/' && i + 1 < n && text.charAt(i + 1) == '*') {
                int end = text.indexOf("*/", i + 2);
                end = end < 0 ? n : end + 2;
                for (; i < end; i++) if (text.charAt(i) != '\n') out.setCharAt(i, ' ');
            } else if (c == '"' || c == '\'') {
                boolean block = c == '"' && text.startsWith("\"\"\"", i);
                int j = i + (block ? 3 : 1);
                while (j < n) {
                    if (text.charAt(j) == '\\') { j += 2; continue; }
                    if (block ? text.startsWith("\"\"\"", j) : text.charAt(j) == c || text.charAt(j) == '\n') break;
                    j++;
                }
                int end = Math.min(n, j + (block ? 3 : 1));
                for (int k = i; k < end; k++) if (text.charAt(k) != '\n') out.setCharAt(k, ' ');
                i = end;
            } else i++;
        }
        return out.toString();
    }

    private static int[] lineStarts(String text) {
        var starts = new ArrayList<Integer>();
        starts.add(0);
        for (int i = 0; i < text.length(); i++) if (text.charAt(i) == '\n') starts.add(i + 1);
        return starts.stream().mapToInt(Integer::intValue).toArray();
    }

    private static int line(int[] starts, int offset) {
        int index = Arrays.binarySearch(starts, offset);
        return (index >= 0 ? index : -index - 2) + 1;
    }
}
