package dev.classrevive.transforms;

import java.io.IOException;
import java.util.*;
import java.util.regex.Pattern;

/** Parses an explicit rename mapping file: class rules plus private method/field rules. */
public final class MappingFile {
    /** Explicit plan; a member rule with a null descriptor applies to every member with that name and kind. */
    public record Plan(Map<String,String> classes,List<ClassRenamer.MemberRule> members) {
        public Plan {
            classes=Collections.unmodifiableMap(new TreeMap<>(classes));
            members=List.copyOf(members);
        }
    }
    public static final int MAX_BYTES = 1024 * 1024;
    private static final int MAX_CLASS_RULES = 20000;
    private static final int MAX_MEMBER_RULES = 50000;
    private static final int MAX_LINE = 4096;
    private static final Pattern FIELD_DESCRIPTOR = Pattern.compile("\\[*(?:[BCDFIJSZ]|L[^;\\s]+;)");
    private static final Pattern METHOD_DESCRIPTOR = Pattern.compile("\\((?:\\[*(?:[BCDFIJSZ]|L[^;\\s]+;))*\\)(?:V|\\[*(?:[BCDFIJSZ]|L[^;\\s]+;))");

    public static Plan parse(byte[] bytes) {
        if (bytes.length > MAX_BYTES) throw new IllegalArgumentException("Mapping file exceeds 1 MiB");
        int offset = bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF && (bytes[1] & 0xFF) == 0xBB && (bytes[2] & 0xFF) == 0xBF ? 3 : 0;
        String text;
        try { text = ArchiveMetadata.decodeStrict(Arrays.copyOfRange(bytes, offset, bytes.length), "Mapping file"); }
        catch (IOException error) { throw new IllegalArgumentException(error.getMessage()); }
        var classes = new TreeMap<String,String>();
        var members = new ArrayList<ClassRenamer.MemberRule>();
        int number = 0;
        for (String raw : text.split("\n", -1)) {
            number++;
            String line = raw.endsWith("\r") ? raw.substring(0, raw.length() - 1) : raw;
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            if (line.length() > MAX_LINE) throw new IllegalArgumentException("Mapping line " + number + " exceeds " + MAX_LINE + " characters");
            int equals = line.indexOf('=');
            if (equals < 0) throw new IllegalArgumentException("Mapping line " + number + " has no '='");
            String left = line.substring(0, equals).trim(), target = line.substring(equals + 1).trim();
            if (left.isEmpty() || target.isEmpty() || target.chars().anyMatch(Character::isWhitespace) || target.indexOf('=') >= 0 || target.indexOf('#') >= 0)
                throw new IllegalArgumentException("Invalid mapping target on line " + number);
            int space = left.indexOf(' ');
            if (space < 1) throw new IllegalArgumentException("Mapping line " + number + " requires a rule kind");
            String kind = left.substring(0, space), rest = left.substring(space + 1).trim();
            switch (kind) {
                case "class" -> {
                    if (classes.put(toInternal(rest, number), toInternal(target, number)) != null)
                        throw new IllegalArgumentException("Duplicate class mapping on line " + number);
                    if (classes.size() > MAX_CLASS_RULES) throw new IllegalArgumentException("Too many class mappings");
                }
                case "method", "field" -> {
                    int hash = rest.indexOf('#');
                    if (hash < 1) throw new IllegalArgumentException("Member rule on line " + number + " requires owner#name");
                    String owner = toInternal(rest.substring(0, hash), number);
                    String member = rest.substring(hash + 1).trim();
                    boolean field = kind.equals("field");
                    String name, descriptor = null;
                    if (field) {
                        int colon = member.indexOf(':');
                        if (colon >= 0) { name = member.substring(0, colon).trim(); descriptor = member.substring(colon + 1).trim(); }
                        else name = member;
                        if (descriptor != null && !FIELD_DESCRIPTOR.matcher(descriptor).matches())
                            throw new IllegalArgumentException("Invalid field descriptor on line " + number);
                    } else {
                        int paren = member.indexOf('(');
                        if (paren >= 0) { name = member.substring(0, paren).trim(); descriptor = member.substring(paren).trim(); }
                        else name = member;
                        if (descriptor != null && !METHOD_DESCRIPTOR.matcher(descriptor).matches())
                            throw new IllegalArgumentException("Invalid method descriptor on line " + number);
                    }
                    if (name.isEmpty() || name.chars().anyMatch(Character::isWhitespace) || name.indexOf('#') >= 0)
                        throw new IllegalArgumentException("Invalid member name on line " + number);
                    members.add(new ClassRenamer.MemberRule(owner, name, descriptor, field, target));
                    if (members.size() > MAX_MEMBER_RULES) throw new IllegalArgumentException("Too many member rules");
                }
                default -> throw new IllegalArgumentException("Unknown mapping kind on line " + number);
            }
        }
        if (classes.isEmpty() && members.isEmpty()) throw new IllegalArgumentException("Mapping file has no entries");
        members.sort(Comparator.comparing(ClassRenamer.MemberRule::owner)
            .thenComparing(ClassRenamer.MemberRule::field)
            .thenComparing(ClassRenamer.MemberRule::name)
            .thenComparing(rule -> rule.descriptor() == null ? "" : rule.descriptor())
            .thenComparing(ClassRenamer.MemberRule::target));
        for (int i = 1; i < members.size(); i++)
            if (sameRule(members.get(i - 1), members.get(i))) throw new IllegalArgumentException("Duplicate member mapping");
        return new Plan(classes, members);
    }

    private static boolean sameRule(ClassRenamer.MemberRule first, ClassRenamer.MemberRule second) {
        return first.owner().equals(second.owner()) && first.field() == second.field()
            && first.name().equals(second.name()) && Objects.equals(first.descriptor(), second.descriptor());
    }
    private static String toInternal(String name, int number) {
        if (name.isEmpty()) throw new IllegalArgumentException("Empty name on line " + number);
        return name.indexOf('/') >= 0 ? name : name.replace('.', '/');
    }
}
