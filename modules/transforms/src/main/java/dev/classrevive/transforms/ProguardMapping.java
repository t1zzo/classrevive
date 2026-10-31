package dev.classrevive.transforms;

import java.io.IOException;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Reads a ProGuard/R8 {@code mapping.txt} and converts the part the renamer can apply safely into an explicit
 * {@link MappingFile.Plan} that restores original names (obfuscated -> original). Everything else is skipped with a
 * recorded reason instead of being guessed. Restored names come from the mapping file, so they are only as
 * trustworthy as that file.
 */
public final class ProguardMapping {
    public static final int MAX_BYTES = 32 * 1024 * 1024;
    private static final int MAX_LINE = 8192;
    private static final int MAX_CLASSES = 200_000;
    private static final int MAX_MEMBERS = 2_000_000;
    private static final int MAX_SKIP_SAMPLES = 200;
    private static final Pattern CLASS_LINE = Pattern.compile("(\\S+) -> (\\S+):");
    private static final Pattern METHOD_LINE = Pattern.compile("(?:\\d+:\\d+:)?(\\S+) (\\S+)\\(([^()]*)\\)(?::\\d+(?::\\d+)?)? -> (\\S+)");
    private static final Pattern FIELD_LINE = Pattern.compile("(\\S+) (\\S+) -> (\\S+)");
    private static final Pattern JAVA_NAME = Pattern.compile("[\\p{L}_$][\\p{L}\\p{N}_$]*(?:[.][\\p{L}_$][\\p{L}\\p{N}_$]*)*");
    private static final Pattern MEMBER_NAME = Pattern.compile("[\\p{L}_$][\\p{L}\\p{N}_$]*");

    public record MemberLine(String type, String name, String parameters, boolean field, String obfuscated) {}
    public record ClassEntry(String original, String obfuscated, List<MemberLine> members) {
        public ClassEntry { members = List.copyOf(members); }
    }
    public record Parsed(List<ClassEntry> classes) { public Parsed { classes = List.copyOf(classes); } }

    /** Bytecode facts the conversion needs; built by the caller from its own inventory, never from the mapping. */
    public record Member(String name, String descriptor, int access) {}
    public record ClassShape(int access, List<Member> fields, List<Member> methods) {}

    public enum Reason {
        CLASS_NOT_IN_INPUT, CROSS_PACKAGE, NESTED_INCOHERENT, OUTPUT_COLLISION, DESCRIPTOR_CLASS,
        MEMBER_NOT_FOUND, NOT_PRIVATE, SERIALIZATION_MEMBER, RECORD_FIELD, AMBIGUOUS, MEMBER_COLLISION, INVALID_NAME
    }
    public record Skip(Reason reason, String rule) {}
    public record Conversion(MappingFile.Plan plan, Map<Reason,Integer> skipped, List<Skip> samples, int inlineFrames) {
        public Conversion {
            var copy = new EnumMap<Reason,Integer>(Reason.class); copy.putAll(skipped);
            skipped = Collections.unmodifiableMap(copy);
            samples = List.copyOf(samples);
        }
        public int applied() { return plan.classes().size() + plan.members().size(); }
    }

    private ProguardMapping() {}

    public static Parsed parse(byte[] bytes) {
        if (bytes.length > MAX_BYTES) throw new IllegalArgumentException("ProGuard mapping exceeds 32 MiB");
        int offset = bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF && (bytes[1] & 0xFF) == 0xBB && (bytes[2] & 0xFF) == 0xBF ? 3 : 0;
        String text;
        try { text = ArchiveMetadata.decodeStrict(Arrays.copyOfRange(bytes, offset, bytes.length), "ProGuard mapping"); }
        catch (IOException error) { throw new IllegalArgumentException(error.getMessage()); }
        var classes = new ArrayList<ClassEntry>();
        var originals = new HashSet<String>(); var obfuscated = new HashSet<String>();
        String original = null, renamed = null; List<MemberLine> members = null;
        int number = 0, memberCount = 0;
        for (String raw : text.split("\n", -1)) {
            number++;
            String line = raw.endsWith("\r") ? raw.substring(0, raw.length() - 1) : raw;
            if (line.length() > MAX_LINE) throw new IllegalArgumentException("ProGuard mapping line " + number + " is too long");
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
            if (!Character.isWhitespace(line.charAt(0))) {
                var match = CLASS_LINE.matcher(trimmed);
                if (!match.matches() || !JAVA_NAME.matcher(match.group(1)).matches() || !JAVA_NAME.matcher(match.group(2)).matches())
                    throw new IllegalArgumentException("Invalid ProGuard class line " + number);
                if (original != null) classes.add(new ClassEntry(original, renamed, members));
                original = match.group(1).replace('.', '/'); renamed = match.group(2).replace('.', '/'); members = new ArrayList<>();
                if (!originals.add(original) || !obfuscated.add(renamed))
                    throw new IllegalArgumentException("Duplicate ProGuard class mapping on line " + number);
                if (classes.size() >= MAX_CLASSES) throw new IllegalArgumentException("Too many ProGuard class mappings");
                continue;
            }
            if (original == null) throw new IllegalArgumentException("ProGuard member line " + number + " precedes any class");
            var method = METHOD_LINE.matcher(trimmed);
            if (method.matches()) members.add(new MemberLine(method.group(1), method.group(2), method.group(3), false, method.group(4)));
            else {
                var field = FIELD_LINE.matcher(trimmed);
                if (!field.matches()) throw new IllegalArgumentException("Invalid ProGuard member line " + number);
                members.add(new MemberLine(field.group(1), field.group(2), null, true, field.group(3)));
            }
            if (++memberCount > MAX_MEMBERS) throw new IllegalArgumentException("Too many ProGuard member mappings");
        }
        if (original != null) classes.add(new ClassEntry(original, renamed, members));
        if (classes.isEmpty()) throw new IllegalArgumentException("ProGuard mapping has no class entries");
        return new Parsed(classes);
    }

    /** Keeps only rules the renamer can apply: same-package classes and private, non-serialization members. */
    public static Conversion convert(Parsed parsed, Map<String,ClassShape> input) {
        var skips = new EnumMap<Reason,Integer>(Reason.class); var samples = new ArrayList<Skip>();
        var originalToObfuscated = new HashMap<String,String>();
        for (var entry : parsed.classes()) originalToObfuscated.put(entry.original(), entry.obfuscated());

        var classRules = new TreeMap<String,String>();
        for (var entry : parsed.classes()) {
            String from = entry.obfuscated(), to = entry.original();
            if (from.equals(to)) continue;
            String rule = "class " + from + " -> " + to;
            if (!input.containsKey(from)) { skip(skips, samples, Reason.CLASS_NOT_IN_INPUT, rule); continue; }
            if (descriptorClass(from) || descriptorClass(to)) { skip(skips, samples, Reason.DESCRIPTOR_CLASS, rule); continue; }
            if (!packageName(from).equals(packageName(to))) { skip(skips, samples, Reason.CROSS_PACKAGE, rule); continue; }
            classRules.put(from, to);
        }
        boolean changed = true;
        while (changed) {
            changed = false;
            for (var iterator = classRules.entrySet().iterator(); iterator.hasNext(); ) {
                var rule = iterator.next(); boolean coherent = true;
                for (String nested : input.keySet())
                    if (nested.startsWith(rule.getKey() + "$") && !classRules.getOrDefault(nested, nested).startsWith(rule.getValue() + "$")) coherent = false;
                if (!coherent) {
                    skip(skips, samples, Reason.NESTED_INCOHERENT, "class " + rule.getKey() + " -> " + rule.getValue());
                    iterator.remove(); changed = true;
                }
            }
        }
        var outputs = new HashMap<String,List<String>>();
        for (String name : input.keySet())
            outputs.computeIfAbsent(classRules.getOrDefault(name, name).toLowerCase(Locale.ROOT), key -> new ArrayList<>()).add(name);
        for (var clash : outputs.values()) if (clash.size() > 1) for (String name : clash) {
            String target = classRules.remove(name);
            if (target != null) skip(skips, samples, Reason.OUTPUT_COLLISION, "class " + name + " -> " + target);
        }

        // Every line, including identity lines and unqualified same-class inline frames, is an origin claim for its key;
        // a key is applied only when exactly one original name claims it.
        var memberTargets = new TreeMap<ClassRenamer.MemberKey,String>(); var origins = new HashMap<ClassRenamer.MemberKey,Set<String>>();
        int inlineFrames = 0;
        for (var entry : parsed.classes()) {
            var shape = input.get(entry.obfuscated());
            for (var line : entry.members()) {
                if (line.name().indexOf('.') >= 0) { inlineFrames++; continue; }
                if (line.name().startsWith("<")) continue;
                String rule = describe(entry, line);
                String descriptor;
                try {
                    descriptor = line.field() ? descriptor(line.type(), originalToObfuscated)
                        : methodDescriptor(line.parameters(), line.type(), originalToObfuscated);
                } catch (IllegalArgumentException invalid) { skip(skips, samples, Reason.INVALID_NAME, rule); continue; }
                var key = new ClassRenamer.MemberKey(entry.obfuscated(), line.obfuscated(), descriptor, line.field());
                origins.computeIfAbsent(key, k -> new HashSet<>()).add(line.name());
                if (line.name().equals(line.obfuscated())) continue;
                if (shape == null) { skip(skips, samples, Reason.CLASS_NOT_IN_INPUT, rule); continue; }
                if (!MEMBER_NAME.matcher(line.name()).matches()) { skip(skips, samples, Reason.INVALID_NAME, rule); continue; }
                Member member = null;
                for (var candidate : line.field() ? shape.fields() : shape.methods())
                    if (candidate.name().equals(key.name()) && candidate.descriptor().equals(descriptor)) member = candidate;
                if (member == null) { skip(skips, samples, Reason.MEMBER_NOT_FOUND, rule); continue; }
                if ((member.access() & 0x0002) == 0) { skip(skips, samples, Reason.NOT_PRIVATE, rule); continue; }
                if (ClassRenamer.serializationMember(key.name(), descriptor, line.field())
                    || ClassRenamer.serializationMember(line.name(), descriptor, line.field())) { skip(skips, samples, Reason.SERIALIZATION_MEMBER, rule); continue; }
                if (line.field() && (shape.access() & 0x10000) != 0) { skip(skips, samples, Reason.RECORD_FIELD, rule); continue; }
                memberTargets.put(key, line.name());
            }
        }
        for (var iterator = memberTargets.entrySet().iterator(); iterator.hasNext(); ) {
            var key = iterator.next().getKey();
            if (origins.get(key).size() > 1) { iterator.remove(); skip(skips, samples, Reason.AMBIGUOUS, key.owner() + "#" + key.name() + key.descriptor()); }
        }
        for (var owner : input.keySet()) {
            var shape = input.get(owner); var seen = new HashMap<String,List<ClassRenamer.MemberKey>>();
            for (var field : shape.fields()) { var key = new ClassRenamer.MemberKey(owner, field.name(), field.descriptor(), true);
                seen.computeIfAbsent("F" + memberTargets.getOrDefault(key, field.name()) + "\u0000" + field.descriptor(), k -> new ArrayList<>()).add(key); }
            for (var method : shape.methods()) { var key = new ClassRenamer.MemberKey(owner, method.name(), method.descriptor(), false);
                seen.computeIfAbsent("M" + memberTargets.getOrDefault(key, method.name()) + "\u0000" + method.descriptor(), k -> new ArrayList<>()).add(key); }
            for (var clash : seen.values()) if (clash.size() > 1) for (var key : clash)
                if (memberTargets.remove(key) != null) skip(skips, samples, Reason.MEMBER_COLLISION, key.owner() + "#" + key.name() + key.descriptor());
        }
        var rules = new ArrayList<ClassRenamer.MemberRule>();
        memberTargets.forEach((key, target) -> rules.add(new ClassRenamer.MemberRule(key.owner(), key.name(), key.descriptor(), key.field(), target)));
        return new Conversion(new MappingFile.Plan(classRules, rules), skips, samples, inlineFrames);
    }

    private static void skip(Map<Reason,Integer> skips, List<Skip> samples, Reason reason, String rule) {
        skips.merge(reason, 1, Integer::sum);
        if (samples.size() < MAX_SKIP_SAMPLES) samples.add(new Skip(reason, rule));
    }
    private static String describe(ClassEntry entry, MemberLine line) {
        return (line.field() ? "field " : "method ") + entry.obfuscated() + "#" + line.obfuscated() + " -> " + line.name();
    }
    static String methodDescriptor(String parameters, String returnType, Map<String,String> classes) {
        var result = new StringBuilder("(");
        if (!parameters.isBlank()) for (String parameter : parameters.split(",", -1)) result.append(descriptor(parameter.trim(), classes));
        return result.append(')').append(returnType.equals("void") ? "V" : descriptor(returnType, classes)).toString();
    }
    static String descriptor(String type, Map<String,String> classes) {
        int dimensions = 0; String base = type;
        while (base.endsWith("[]")) { dimensions++; base = base.substring(0, base.length() - 2); }
        if (dimensions > 255) throw new IllegalArgumentException("Too many array dimensions");
        String element = switch (base) {
            case "boolean" -> "Z"; case "byte" -> "B"; case "char" -> "C"; case "short" -> "S";
            case "int" -> "I"; case "long" -> "J"; case "float" -> "F"; case "double" -> "D";
            default -> {
                if (!JAVA_NAME.matcher(base).matches()) throw new IllegalArgumentException("Invalid type in ProGuard mapping");
                String internal = base.replace('.', '/');
                yield "L" + classes.getOrDefault(internal, internal) + ";";
            }
        };
        return "[".repeat(dimensions) + element;
    }
    /** Serializes a plan in the explicit {@link MappingFile} syntax so the existing worker validates and applies it. */
    public static String format(MappingFile.Plan plan) {
        var text = new StringBuilder("# Converted from a ProGuard/R8 mapping by ClassRevive; obfuscated -> original.\n");
        plan.classes().forEach((from, to) -> text.append("class ").append(from).append(" = ").append(to).append('\n'));
        for (var rule : plan.members()) {
            text.append(rule.field() ? "field " : "method ").append(rule.owner()).append('#').append(rule.name());
            if (rule.field()) text.append(':');
            text.append(rule.descriptor()).append(" = ").append(rule.target()).append('\n');
        }
        return text.toString();
    }
    private static String packageName(String name) { return name.substring(0, name.lastIndexOf('/') + 1); }
    private static boolean descriptorClass(String name) {
        return name.equals("module-info") || name.equals("package-info") || name.endsWith("/package-info");
    }
}
