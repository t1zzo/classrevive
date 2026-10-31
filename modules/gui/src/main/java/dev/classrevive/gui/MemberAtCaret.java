package dev.classrevive.gui;

import dev.classrevive.bytecode.ReferenceIndex;
import dev.classrevive.bytecode.ReferenceIndex.Ref;
import java.util.*;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;

/**
 * Phase G6: what the word under the caret names, as candidates from the reference index. Decompiled text has no types,
 * so a name is resolved by its context and never guessed: a class through the Phase G4 resolution (import, nested class,
 * same package, unique simple name); a member through its qualifier ({@code Type.m}, {@code this.m}, {@code super.m},
 * {@code Type::m}), else the classes of this file and their supertypes, else every class of the input; overloads are
 * narrowed by the number of arguments. More than one candidate is for the user to choose.
 */
final class MemberAtCaret {
    private MemberAtCaret() {}

    /** The identifier at an offset and its surroundings; {@code arguments} is -1 unless a call's list was counted. */
    record Query(String word, int start, boolean call, int arguments, String qualifier, boolean afterNew, boolean declaration) {}

    private static final Set<String> KEYWORDS = Set.of("abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class",
        "const", "continue", "default", "do", "double", "else", "enum", "extends", "final", "finally", "float", "for", "goto", "if",
        "implements", "import", "instanceof", "int", "interface", "long", "native", "new", "package", "private", "protected", "public",
        "return", "short", "static", "strictfp", "super", "switch", "synchronized", "this", "throw", "throws", "transient", "try",
        "void", "volatile", "while", "var", "record", "true", "false", "null");

    /** The query at {@code offset} in Java text, or null on a keyword, literal, comment or nothing. */
    static Query at(String text, int offset) {
        String code = MethodLocator.blank(text);
        if (offset < 0 || offset > code.length()) return null;
        int start = offset, end = offset;
        while (start > 0 && Character.isJavaIdentifierPart(code.charAt(start - 1))) start--;
        while (end < code.length() && Character.isJavaIdentifierPart(code.charAt(end))) end++;
        if (start == end) return null;
        String word = code.substring(start, end);
        if (KEYWORDS.contains(word) || !Character.isJavaIdentifierStart(word.charAt(0))) return null;
        int after = skip(code, end);
        boolean call = after < code.length() && code.charAt(after) == '(';
        int arguments = -1;
        boolean declaration = false;
        if (call) {
            int close = matchParen(code, after);
            if (close > 0) {
                int next = skip(code, close + 1);
                declaration = next < code.length() && (code.charAt(next) == '{' || code.startsWith("throws", next)
                    || code.charAt(next) == ';' && !precededByOperator(code, start));
                // Parameter lists nest generic arguments (Map<K, V>); call arguments may hold comparisons instead.
                arguments = count(code, after + 1, close, declaration);
            }
        }
        String qualifier = null;
        boolean afterNew = false;
        int before = start - 1;
        while (before >= 0 && Character.isWhitespace(code.charAt(before))) before--;
        if (before >= 0 && (code.charAt(before) == '.' || before > 0 && code.charAt(before) == ':' && code.charAt(before - 1) == ':')) {
            int qualifierEnd = code.charAt(before) == '.' ? before : before - 1;
            while (qualifierEnd > 0 && Character.isWhitespace(code.charAt(qualifierEnd - 1))) qualifierEnd--;
            int qualifierStart = qualifierEnd;
            while (qualifierStart > 0 && Character.isJavaIdentifierPart(code.charAt(qualifierStart - 1))) qualifierStart--;
            qualifier = qualifierStart < qualifierEnd ? code.substring(qualifierStart, qualifierEnd) : "?";
            if (code.charAt(before) == ':') call = true;
        } else if (before >= 2 && code.startsWith("new", before - 2) && (before < 3 || !Character.isJavaIdentifierPart(code.charAt(before - 3)))) {
            afterNew = true;
        }
        if (declaration && (qualifier != null || afterNew)) declaration = false;
        return new Query(word, start, call, arguments, qualifier, afterNew, declaration);
    }

    /** A {@code ;} after a call ends a statement (a call), unless the name follows a type (an abstract declaration). */
    private static boolean precededByOperator(String code, int start) {
        int i = start - 1;
        while (i >= 0 && Character.isWhitespace(code.charAt(i))) i--;
        if (i < 0) return true;
        char c = code.charAt(i);
        if (c == '>' || c == ']') return false;
        if (!Character.isJavaIdentifierPart(c)) return true;
        int end = i + 1;
        while (i >= 0 && Character.isJavaIdentifierPart(code.charAt(i))) i--;
        return Set.of("return", "new", "throw", "else", "case", "yield").contains(code.substring(i + 1, end)) || i >= 0 && code.charAt(i) == '.';
    }

    /**
     * Candidates for {@code query} in the class {@code current} (index name). {@code classes} resolves a simple class
     * name written in this file to an index class name, or null; {@code fileClasses} are the index classes declared in
     * this file, the current one first.
     */
    static List<Ref> candidates(Query query, ReferenceIndex index, String current, List<String> fileClasses, UnaryOperator<String> classes) {
        if (query == null) return List.of();
        String asClass = Character.isUpperCase(query.word().charAt(0)) || query.afterNew() ? classes.apply(query.word()) : null;
        if (asClass != null && query.afterNew() && query.call()) {
            var constructors = arity(index.declared(asClass, "<init>", true).stream().map(ReferenceIndex.Member::ref).toList(), query.arguments());
            return constructors.isEmpty() ? List.of(Ref.ofClass(asClass)) : constructors;
        }
        if (asClass != null && !query.call()) return List.of(Ref.ofClass(asClass));
        // A constructor declaration, or this(...)/super(...): the class's constructors.
        if (query.call() && query.declaration() && fileClasses.stream().anyMatch(name -> MethodLocator.simpleName(name).equals(query.word()))) {
            var owner = fileClasses.stream().filter(name -> MethodLocator.simpleName(name).equals(query.word())).toList();
            var found = new ArrayList<Ref>();
            for (String name : owner) index.declared(name, "<init>", true).forEach(member -> found.add(member.ref()));
            return arity(found, query.arguments());
        }
        boolean methods = query.call();
        List<String> scope;
        boolean known = true;
        if (query.declaration()) scope = fileClasses;
        else if (query.qualifier() == null) scope = fileClasses;
        else if (query.qualifier().equals("this")) scope = List.of(current);
        else if (query.qualifier().equals("super")) scope = index.declaration(current).map(decl -> decl.superName() == null ? List.<String>of() : List.of(decl.superName())).orElse(List.of());
        else {
            String qualifierClass = Character.isUpperCase(query.qualifier().charAt(0)) ? classes.apply(query.qualifier()) : null;
            scope = qualifierClass == null ? List.of() : List.of(qualifierClass);
            known = qualifierClass != null;
        }
        var found = new ArrayList<Ref>();
        if (known) {
            var seen = new HashSet<String>();
            for (String type : scope) {
                var walk = new ArrayList<String>();
                walk.add(type);
                if (!query.declaration()) walk.addAll(index.supertypes(type));
                for (String owner : walk)
                    for (var member : index.declared(owner, query.word(), methods))
                        // The most derived declaration of a signature hides the ones it overrides.
                        if (seen.add(member.isPrivate() ? owner + "#" + member.descriptor() : member.descriptor())) found.add(member.ref());
            }
        }
        if (found.isEmpty() && !query.declaration()) {
            for (var decl : new TreeMap<>(index.classes()).values())
                for (var member : index.declared(decl.name(), query.word(), methods)) found.add(member.ref());
        }
        return methods ? arity(found, query.arguments()) : found;
    }

    /** Keeps the overloads with {@code arguments} parameters, when that is known and at least one has them. */
    private static List<Ref> arity(List<Ref> refs, int arguments) {
        if (arguments < 0) return refs;
        var matching = refs.stream().filter(ref -> MethodLocator.parameters(ref.descriptor()) == arguments).toList();
        return matching.isEmpty() ? refs : matching;
    }

    private static final Pattern INSTRUCTION = Pattern.compile("^\\s+(?:INVOKE\\w+|GETFIELD|PUTFIELD|GETSTATIC|PUTSTATIC)\\s+([^\\s.]+)\\.(\\S+)\\s+(?::\\s+)?(\\S+)");
    private static final Pattern TYPE = Pattern.compile("^\\s+(?:NEW|ANEWARRAY|CHECKCAST|INSTANCEOF)\\s+(\\S+)\\s*$");
    private static final Pattern METHOD_HEADER = Pattern.compile("^  (?:[a-z]+ )*([^\\s(]+)(\\([^\\s]*)$");
    private static final Pattern FIELD_HEADER = Pattern.compile("^  (?:[a-z]+ )*(\\S+) (\\S+)(?: = .*)?$");

    /**
     * In ASM's bytecode text the line says exactly what it references: an instruction names its owner, name and
     * descriptor; a member header is a member of {@code current} (the text's own class name). {@code toIndex} maps the
     * text's class names to the index's. Null when the line names nothing.
     */
    static Ref inBytecode(String line, String current, UnaryOperator<String> toIndex) {
        var instruction = INSTRUCTION.matcher(line);
        if (instruction.find()) return new Ref(toIndex.apply(instruction.group(1)), instruction.group(2), mapDescriptor(instruction.group(3), toIndex));
        var type = TYPE.matcher(line);
        if (type.find()) {
            String name = type.group(1);
            if (name.startsWith("[")) { int l = name.indexOf('L'); if (l < 0) return null; name = name.substring(l + 1, name.length() - 1); }
            return Ref.ofClass(toIndex.apply(name));
        }
        if (line.startsWith("  //") || line.startsWith("  @") || line.startsWith("   ")) return null;
        var method = METHOD_HEADER.matcher(line);
        if (method.find()) return new Ref(toIndex.apply(current), method.group(1), mapDescriptor(method.group(2), toIndex));
        var field = FIELD_HEADER.matcher(line);
        if (field.find() && field.group(1).matches("\\[*([BCDFIJSZ]|L[^;]+;)")) return new Ref(toIndex.apply(current), field.group(2), mapDescriptor(field.group(1), toIndex));
        return null;
    }

    private static String mapDescriptor(String descriptor, UnaryOperator<String> names) {
        var matcher = Pattern.compile("L([^;]+);").matcher(descriptor);
        var out = new StringBuilder();
        while (matcher.find()) matcher.appendReplacement(out, java.util.regex.Matcher.quoteReplacement("L" + names.apply(matcher.group(1)) + ";"));
        matcher.appendTail(out);
        return out.toString();
    }

    private static int skip(String code, int i) {
        while (i < code.length() && Character.isWhitespace(code.charAt(i))) i++;
        return i;
    }

    private static int matchParen(String code, int open) {
        int depth = 0;
        for (int i = open; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c == '(') depth++;
            else if (c == ')' && --depth == 0) return i;
        }
        return -1;
    }

    /** Top-level commas plus one; {@code <} nests only in a declaration's parameters (in a call it may be a comparison). */
    private static int count(String code, int from, int close, boolean generics) {
        if (code.substring(from, close).isBlank()) return 0;
        int depth = 0, commas = 0;
        for (int i = from; i < close; i++) {
            char c = code.charAt(i);
            if (c == '(' || c == '[' || c == '{' || generics && c == '<') depth++;
            else if (c == ')' || c == ']' || c == '}' || generics && c == '>') depth--;
            else if (c == ',' && depth == 0) commas++;
        }
        return commas + 1;
    }
}
