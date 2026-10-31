package dev.classrevive.pipeline;

import java.util.*;

/**
 * Phase 2CC: decodes JNI short and long names ({@code Java_<class>_<method>[__<arguments>]}, JNI specification "Resolving
 * Native Method Names"): {@code _} separates name parts, {@code _1} is {@code _}, {@code _2} is {@code ;}, {@code _3} is
 * {@code [} and {@code _0xxxx} a UTF-16 unit.
 */
public final class JniNames {
    private JniNames() {}

    /** {@code argumentDescriptor} is {@code (…)} without the return type, or null for a short name. */
    public record Method(String owner, String name, String argumentDescriptor) {
        public boolean matches(String owner, String name, String descriptor) {
            return this.owner.equals(owner) && this.name.equals(name) && (argumentDescriptor == null || descriptor.startsWith(argumentDescriptor));
        }
    }

    /** The decoded method, or empty when the symbol is no well-formed JNI name. A leading {@code _} (Mach-O, 32-bit Windows) and a {@code @N} suffix are ignored. */
    public static Optional<Method> decode(String symbol) {
        String name = symbol;
        if (name.startsWith("_Java_")) name = name.substring(1);
        int at = name.indexOf('@');
        if (at > 0) name = name.substring(0, at);
        if (!name.startsWith("Java_") || name.length() > 4096) return Optional.empty();
        String body = name.substring(5);
        int split = signatureSplit(body);
        String qualified = split < 0 ? body : body.substring(0, split);
        String arguments = split < 0 ? null : body.substring(split + 2);
        List<String> parts = parts(qualified);
        if (parts == null || parts.size() < 2 || parts.stream().anyMatch(String::isEmpty)) return Optional.empty();
        String owner = String.join("/", parts.subList(0, parts.size() - 1));
        String method = parts.getLast();
        String descriptor = null;
        if (arguments != null) {
            List<String> argumentParts = parts(arguments);
            if (argumentParts == null) return Optional.empty();
            descriptor = "(" + String.join("/", argumentParts) + ")";
        }
        return Optional.of(new Method(owner, method, descriptor));
    }

    /**
     * Index of the {@code __} that starts the argument signature. {@code __1x} is a separator followed by an escaped
     * {@code _} ({@code Native._getPointer} is {@code Native__1getPointer}): a signature never starts with a digit.
     */
    private static int signatureSplit(String body) {
        for (int i = 0; i < body.length() - 1; i++) {
            if (body.charAt(i) != '_') continue;
            char next = body.charAt(i + 1);
            if (next == '_') {
                if (i + 2 < body.length() && body.charAt(i + 2) >= '0' && body.charAt(i + 2) <= '3') continue;
                return i;
            }
            if (next >= '0' && next <= '3') i++; // skip the escape digit
        }
        return -1;
    }

    /** The name parts between unescaped {@code _}, with escapes decoded; null when an escape is malformed. */
    private static List<String> parts(String text) {
        var parts = new ArrayList<String>();
        var current = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c != '_') { current.append(c); continue; }
            if (i + 1 >= text.length()) return null;
            char next = text.charAt(i + 1);
            switch (next) {
                case '1' -> { current.append('_'); i++; }
                case '2' -> { current.append(';'); i++; }
                case '3' -> { current.append('['); i++; }
                case '0' -> {
                    if (i + 6 > text.length()) return null;
                    try { current.append((char) Integer.parseInt(text.substring(i + 2, i + 6), 16)); }
                    catch (NumberFormatException bad) { return null; }
                    i += 5;
                }
                default -> { parts.add(current.toString()); current.setLength(0); }
            }
        }
        parts.add(current.toString());
        return parts;
    }

    /** {@code JNI_OnLoad}, {@code JNI_OnLoad_<library>} (static linking) and their decorated forms ({@code _JNI_OnLoad@8}). */
    public static boolean onLoad(String symbol) {
        String name = symbol.startsWith("_JNI_") ? symbol.substring(1) : symbol;
        return name.equals("JNI_OnLoad") || name.startsWith("JNI_OnLoad_") || name.startsWith("JNI_OnLoad@");
    }

    /** Native method names that are no Java identifiers or are 16+ hex digits, as the 2BZ detector counts them. */
    public static boolean obfuscatedName(String name) {
        if (name.length() >= 16 && name.chars().allMatch(c -> Character.digit(c, 16) >= 0)) return true;
        if (name.isEmpty() || !Character.isJavaIdentifierStart(name.charAt(0))) return true;
        return !name.chars().allMatch(Character::isJavaIdentifierPart);
    }
}
