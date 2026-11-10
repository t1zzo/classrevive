package dev.classrevive.transforms;

import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;
import java.util.*;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;

/**
 * Phase 2DK: names for local variables and parameters whose LocalVariableTable names an obfuscator shortened. The name comes
 * from the variable's type only ({@code List<Player>} gives {@code players}), so it is always a LOW-confidence guess; the
 * decompilers print the table's names when the table is present. Variables of methods without a table are untouched (the
 * engines already name those by type), names that already read well are kept, and no name repeats within a method.
 */
final class LocalNames {
    private static final Set<String> RESERVED = Set.of("abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class", "const",
        "continue", "default", "do", "double", "else", "enum", "extends", "final", "finally", "float", "for", "goto", "if", "implements", "import",
        "instanceof", "int", "interface", "long", "native", "new", "package", "private", "protected", "public", "return", "short", "static",
        "strictfp", "super", "switch", "synchronized", "this", "throw", "throws", "transient", "try", "void", "volatile", "while", "true", "false",
        "null", "_", "yield", "var", "record");
    private static final Pattern GENERIC = Pattern.compile("^L([^<;]+)<(.+)>;$");

    private LocalNames() {}

    /** Renames in place; {@code classNames} maps an internal class name to the name it has after the rename pass. Returns the variables renamed. */
    static int rename(ClassNode node) { return rename(node, UnaryOperator.identity()); }
    static int rename(ClassNode node, UnaryOperator<String> classNames) {
        int renamed = 0;
        for (var method : node.methods) {
            if (method.localVariables == null || method.localVariables.isEmpty()) continue;
            var used = new HashSet<String>();
            for (var local : method.localVariables) used.add(local.name);
            var groups = new LinkedHashMap<String,String>();
            for (var local : method.localVariables) {
                if (local.name.equals("this") || !NameInference.obfuscated(local.name, false)) continue;
                String key = local.index + ":" + local.name + ":" + local.desc;
                if (groups.containsKey(key)) continue;
                String base = word(local.desc, local.signature, classNames);
                String name = base;
                for (int suffix = 2; used.contains(name) || RESERVED.contains(name); suffix++) name = base + suffix;
                used.add(name); groups.put(key, name);
            }
            for (var local : method.localVariables) {
                String name = groups.get(local.index + ":" + local.name + ":" + local.desc);
                if (name != null) local.name = name;
            }
            renamed += groups.size();
        }
        return renamed;
    }

    /** A camelCase word for a type; never null. */
    static String word(String desc, String signature, UnaryOperator<String> classNames) {
        Type type = Type.getType(desc);
        switch (type.getSort()) {
            case Type.BOOLEAN: return "flag";
            case Type.BYTE: return "b";
            case Type.CHAR: return "c";
            case Type.SHORT: return "s";
            case Type.INT: return "i";
            case Type.LONG: return "l";
            case Type.FLOAT: return "f";
            case Type.DOUBLE: return "d";
            case Type.ARRAY: {
                Type element = type.getElementType();
                String base = element.getSort() == Type.OBJECT ? objectWord(element.getInternalName(), classNames) : switch (element.getSort()) {
                    case Type.BYTE -> "byte"; case Type.CHAR -> "char"; case Type.INT -> "int"; case Type.LONG -> "long"; case Type.SHORT -> "short";
                    case Type.BOOLEAN -> "boolean"; case Type.FLOAT -> "float"; case Type.DOUBLE -> "double"; default -> "item";
                };
                return NameInference.plural(base);
            }
            default: break;
        }
        if (signature != null) {
            var match = GENERIC.matcher(signature);
            if (match.find()) {
                String raw = match.group(1);
                int comma = match.group(2).indexOf(';');
                String first = comma < 0 ? null : match.group(2).substring(0, comma + 1);
                boolean collection = raw.equals("java/util/List") || raw.equals("java/util/Set") || raw.equals("java/util/Collection")
                    || raw.equals("java/util/ArrayList") || raw.equals("java/util/Deque") || raw.equals("java/util/Queue") || raw.equals("java/lang/Iterable")
                    || raw.equals("java/util/LinkedList") || raw.equals("java/util/HashSet");
                if (collection && first != null && first.startsWith("L")) {
                    String inner = first.substring(1, first.length() - 1);
                    int generic = inner.indexOf('<');
                    if (generic >= 0) inner = inner.substring(0, generic);
                    String word = objectWord(inner, classNames);
                    if (!word.equals("obj")) return NameInference.plural(word);
                }
            }
        }
        return objectWord(type.getInternalName(), classNames);
    }

    private static String objectWord(String internal, UnaryOperator<String> classNames) {
        switch (internal) {
            case "java/lang/String": return "str";
            case "java/lang/Object": return "obj";
            case "java/lang/Class": return "cls";
            case "java/lang/Integer": return "integer";
            case "java/lang/Boolean": return "bool";
            case "java/util/List": case "java/util/ArrayList": case "java/util/LinkedList": return "list";
            case "java/util/Map": case "java/util/HashMap": case "java/util/TreeMap": case "java/util/LinkedHashMap": return "map";
            case "java/util/Set": case "java/util/HashSet": return "set";
            case "java/lang/StringBuilder": return "sb";
            default: break;
        }
        String name = classNames.apply(internal);
        if (name == null) name = internal;
        String simple = name.substring(name.lastIndexOf('/') + 1);
        int dollar = simple.lastIndexOf('$');
        if (dollar >= 0) simple = simple.substring(dollar + 1);
        if (simple.length() < 3 || !simple.matches("[A-Za-z][A-Za-z0-9]*") || NameInference.obfuscated(simple, true)) return "obj";
        String word = Character.toLowerCase(simple.charAt(0)) + simple.substring(1);
        return RESERVED.contains(word) ? "obj" : word;
    }
}
