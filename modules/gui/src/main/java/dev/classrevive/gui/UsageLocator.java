package dev.classrevive.gui;

import dev.classrevive.bytecode.ReferenceIndex;
import java.util.*;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;

/**
 * Phase G6: the line of a usage site in the text the editor shows. Decompiled sources do not keep the original line
 * numbers, so the {@code LineNumberTable} line is shown in the results and the text is searched instead: the member that
 * holds the site is found by {@link MethodLocator} (a lambda body by the method it was written in), then the site's
 * ordinal occurrence of the target's name inside that member. Not found: the member's declaration; no member: the class.
 * In ASM's bytecode text the member's header is found and then the instruction naming the target. Best effort, never a
 * guess presented as exact: when nothing matches the result is -1 and the editor shows the class from its declaration.
 */
final class UsageLocator {
    private UsageLocator() {}

    private static final Pattern LAMBDA = Pattern.compile("lambda\\$(.+)\\$\\d+");

    /** 1-based line in decompiled {@code text} for {@code site}; {@code index} names the class's methods for lambdas. */
    static int source(String text, ReferenceIndex.Site site, ReferenceIndex index) {
        String code = MethodLocator.blank(text);
        String className = site.className();
        var from = site.from();
        int start = -1, end = -1;
        if (from.isMethod()) {
            var keys = new ArrayList<String>();
            var lambda = LAMBDA.matcher(from.name());
            if (lambda.matches()) {
                String written = lambda.group(1).equals("new") ? "<init>" : lambda.group(1).equals("static") ? "<clinit>" : lambda.group(1);
                index.declared(className, written, true).forEach(member -> keys.add(member.name() + member.descriptor()));
            } else keys.add(from.key());
            var found = MethodLocator.locate(text, Map.of(className, keys)).getOrDefault(className, Map.of());
            int line = found.values().stream().min(Integer::compare).orElse(-1);
            if (line > 0) {
                start = lineStart(text, line);
                int brace = code.indexOf('{', start), semicolon = code.indexOf(';', start);
                end = brace >= 0 && (semicolon < 0 || brace < semicolon) ? match(code, brace) : lineEnd(text, start);
                if (end < 0) end = lineEnd(text, start);
                // Skip the declaration's own name, which a recursive call or an overload would otherwise match.
                String written = lambda.matches() ? lambda.group(1) : from.name();
                String declared = written.equals("<init>") || written.equals("new") ? MethodLocator.simpleName(className)
                    : written.equals("<clinit>") || written.equals("static") ? null : written;
                if (declared != null && !declared.isEmpty()) {
                    var name = Pattern.compile("\\b" + Pattern.quote(declared) + "\\s*\\(").matcher(code).region(start, end);
                    if (name.find()) start = name.end();
                }
            }
        }
        int declaration = start < 0 ? -1 : lineOf(text, start);
        if (start < 0) {
            // A field, the class header or a member the text does not show: the class's own body.
            String simple = MethodLocator.simpleName(className);
            var header = simple.isEmpty() ? null : Pattern.compile("\\b(class|interface|enum|record)\\s+" + Pattern.quote(simple) + "\\b").matcher(code);
            if (header == null || !header.find()) return -1;
            start = header.start();
            int brace = code.indexOf('{', header.end());
            end = brace < 0 ? code.length() : Math.max(brace, match(code, brace));
            declaration = lineOf(text, start);
            if (from.isField()) {
                var field = Pattern.compile("\\b" + Pattern.quote(from.name()) + "\\b\\s*[;=,]").matcher(code).region(start, end);
                if (field.find()) return lineOf(text, field.start());
            }
        }
        String word = word(site.target());
        if (word == null) return declaration;
        var matcher = Pattern.compile("\\b" + Pattern.quote(word) + "\\b").matcher(code).region(start, Math.max(start, end));
        int line = -1;
        for (int seen = 0; matcher.find(); seen++) {
            line = lineOf(text, matcher.start());
            if (seen == site.ordinal()) break;
        }
        return line > 0 ? line : declaration;
    }

    /** The name a target is written with in Java: a member's name, a constructor's class, a class's own simple name. */
    static String word(ReferenceIndex.Ref target) {
        String word = target.isClass() || "<init>".equals(target.name()) ? MethodLocator.simpleName(target.owner())
            : target.name().startsWith("<") ? null : target.name();
        return word == null || word.isEmpty() ? null : word;
    }

    /**
     * 1-based line in ASM's text ({@link dev.classrevive.bytecode.ClassText}) for {@code site}. {@code names} maps the
     * index's class names to those the text uses (the input's own, when the index read a result's renamed classes).
     */
    static int bytecode(String text, ReferenceIndex.Site site, UnaryOperator<String> names) {
        var lines = text.split("\n", -1);
        var from = site.from();
        int first = 0, last = lines.length;
        if (!from.isClass()) {
            int header = header(lines, from, names);
            if (header < 0) return -1;
            first = header;
            last = header + 1;
            while (last < lines.length && !lines[last].isBlank()) last++;
        } else {
            for (int i = 0; i < lines.length; i++) if (lines[i].startsWith("  // access flags")) { last = i; break; }
        }
        var target = site.target();
        String owner = names.apply(target.owner());
        Pattern needle = target.isClass()
            ? Pattern.compile("(^|[\\s\\[L(])" + Pattern.quote(owner) + "([;.\\s]|$)")
            : Pattern.compile(Pattern.quote(owner + "." + target.name()) + "[\\s(]");
        int seen = 0, found = -1;
        for (int i = first + (from.isClass() ? 0 : 1); i < last; i++) {
            if (!needle.matcher(lines[i]).find()) continue;
            found = i + 1;
            if (seen++ == site.ordinal()) break;
        }
        return found > 0 ? found : from.isClass() ? -1 : first + 1;
    }

    /** The line index of a member's header: {@code "  [modifiers] name(desc)"} or {@code "  [modifiers] desc name"}. */
    private static int header(String[] lines, ReferenceIndex.Ref member, UnaryOperator<String> names) {
        String key = member.isMethod() ? member.name() + mapDescriptor(member.descriptor(), names) : mapDescriptor(member.descriptor(), names) + " " + member.name();
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (!line.startsWith("  ") || line.length() < 3 || line.charAt(2) == ' ' || line.startsWith("  //") || line.startsWith("  @")) continue;
            String trimmed = member.isField() ? line.replaceFirst("\\s+=.*$", "") : line;
            if (trimmed.equals("  " + key) || trimmed.endsWith(" " + key)) return i;
        }
        return -1;
    }

    private static String mapDescriptor(String descriptor, UnaryOperator<String> names) {
        var matcher = Pattern.compile("L([^;]+);").matcher(descriptor);
        var out = new StringBuilder();
        while (matcher.find()) matcher.appendReplacement(out, java.util.regex.Matcher.quoteReplacement("L" + names.apply(matcher.group(1)) + ";"));
        matcher.appendTail(out);
        return out.toString();
    }

    private static int match(String code, int open) {
        int depth = 0;
        for (int i = open; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c == '{') depth++;
            else if (c == '}' && --depth == 0) return i;
        }
        return -1;
    }

    private static int lineStart(String text, int line) {
        int offset = 0;
        for (int i = 1; i < line && offset >= 0; i++) { offset = text.indexOf('\n', offset); if (offset >= 0) offset++; }
        return Math.max(0, offset);
    }

    private static int lineEnd(String text, int offset) {
        int end = text.indexOf('\n', offset);
        return end < 0 ? text.length() : end;
    }

    private static int lineOf(String text, int offset) {
        int line = 1;
        for (int i = 0; i < offset && i < text.length(); i++) if (text.charAt(i) == '\n') line++;
        return line;
    }
}
