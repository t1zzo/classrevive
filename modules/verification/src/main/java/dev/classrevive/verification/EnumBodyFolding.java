package dev.classrevive.verification;

import dev.classrevive.model.ClassInfo;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

/**
 * Phase 2BX: enum constant bodies folded back into their enum. Without an {@code InnerClasses} attribute, CFR, Vineflower
 * and Procyon write the body of {@code JAVA_7(7, 51, false) { boolean isSupported() {...} }} as a separate top-level file
 * {@code E$1.java} extending {@code E}, and {@code E.java} as an enum without bodies: javac then treats {@code E} as final
 * and rejects {@code E$1} ({@code compiler.err.cant.inherit.from.final}). Java source can express such a class only as a
 * constant body, so each body file is folded into the constant the original {@code <clinit>} assigns it to.
 *
 * <p>The plan comes from the original bytecode alone: an {@code ACC_ENUM} class {@code E}, a class {@code E$N} whose
 * superclass is {@code E}, and in {@code E.<clinit>} a {@code NEW E$N} followed by {@code PUTSTATIC E.K:LE;}. The class
 * bytes are parsed with ASM, nothing is defined or run. A fold is applied to a source tree only when {@code E.java}
 * declares {@code enum E} and constant {@code K} has no body; a constant that already has one (an earlier fold, a
 * repaired copy) only drops the duplicate {@code E$N.java}. Constructors of the body are dropped (a constant body has
 * none; its arguments stay on the constant), its other members and its imports are kept. Evidence only: the folded
 * source is measured by the same compilation and signature checks as any other; behavior is not verified.
 */
public final class EnumBodyFolding {
    /** {@code bodyClass} (for example {@code p/E$1}) is the body of constant {@code constant} of {@code enumClass}. */
    public record Body(String enumClass, String bodyClass, String constant) {}
    /** A body folded into a source tree: {@code bodyFile} removed, its members now in {@code enumFile}. */
    public record Fold(String enumFile, String bodyFile, String constant) {}
    private static final EnumBodyFolding NONE = new EnumBodyFolding(List.of());
    private static final Pattern MODIFIERS = Pattern.compile(
        "\\s*(?:@[\\w.$]+\\s*(?:\\([^)]*\\))?\\s*|(?:public|protected|private|static|final|synchronized|strictfp|native|abstract)\\s+)*(?:<[^>]*>\\s*)?");
    private static final Pattern IMPORT = Pattern.compile("(?m)^\\s*import\\s+(static\\s+)?([\\w.$*]+)\\s*;");
    private final List<Body> bodies;

    public EnumBodyFolding(List<Body> bodies) { this.bodies = List.copyOf(bodies); }
    public List<Body> bodies() { return bodies; }
    public static EnumBodyFolding none() { return NONE; }

    /** The bodies of the original classes; none when {@code originalClasses} is null or holds no such enum. */
    public static EnumBodyFolding plan(List<ClassInfo> originals, Path originalClasses) throws IOException {
        if (originalClasses == null) return NONE;
        var byName = new HashMap<String,ClassInfo>();
        originals.forEach(info -> byName.put(info.name(), info));
        var enums = new TreeMap<String,Set<String>>();
        for (var info : originals) {
            var parent = info.superName() == null ? null : byName.get(info.superName());
            if (parent != null && (parent.access() & Opcodes.ACC_ENUM) != 0 && info.name().startsWith(parent.name() + "$"))
                enums.computeIfAbsent(parent.name(), ignored -> new TreeSet<>()).add(info.name());
        }
        if (enums.isEmpty()) return NONE;
        var bytes = MethodRoundTrip.readJar(originalClasses);
        var result = new ArrayList<Body>();
        for (var entry : enums.entrySet()) {
            byte[] data = bytes.get(entry.getKey());
            if (data == null) continue;
            var node = new ClassNode();
            try { new ClassReader(data).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES); }
            catch (RuntimeException malformed) { continue; }
            String descriptor = "L" + entry.getKey() + ";";
            for (var method : node.methods) {
                if (!method.name.equals("<clinit>")) continue;
                String pending = null; var seen = new HashSet<String>();
                for (var insn : method.instructions) {
                    if (insn instanceof TypeInsnNode type && type.getOpcode() == Opcodes.NEW)
                        pending = entry.getValue().contains(type.desc) ? type.desc : null;
                    else if (insn instanceof FieldInsnNode field && field.getOpcode() == Opcodes.PUTSTATIC && pending != null
                        && field.owner.equals(entry.getKey()) && field.desc.equals(descriptor)) {
                        if (seen.add(pending)) result.add(new Body(entry.getKey(), pending, field.name));
                        pending = null;
                    }
                }
            }
        }
        return new EnumBodyFolding(result);
    }

    /** Folds every planned body whose files are in {@code root}; returns the folds made, in plan order. */
    public List<Fold> apply(Path root) throws IOException {
        if (bodies.isEmpty()) return List.of();
        var folds = new ArrayList<Fold>();
        var byEnum = new LinkedHashMap<String,List<Body>>();
        for (var body : bodies) byEnum.computeIfAbsent(body.enumClass(), ignored -> new ArrayList<>()).add(body);
        for (var entry : byEnum.entrySet()) {
            Path enumPath = root.resolve(entry.getKey() + ".java");
            if (!Files.isRegularFile(enumPath, LinkOption.NOFOLLOW_LINKS)) continue;
            String text = Files.readString(enumPath), original = text;
            String simple = entry.getKey().substring(entry.getKey().lastIndexOf('/') + 1);
            var removed = new ArrayList<Path>();
            for (var body : entry.getValue()) {
                Path bodyPath = root.resolve(body.bodyClass() + ".java");
                if (!Files.isRegularFile(bodyPath, LinkOption.NOFOLLOW_LINKS)) continue;
                var constant = constant(text, simple, body.constant());
                if (constant == null) continue;
                if (!constant.hasBody()) {
                    String bodyText = Files.readString(bodyPath);
                    String members = members(bodyText, body.bodyClass().substring(body.bodyClass().lastIndexOf('/') + 1));
                    if (members == null) continue;
                    text = text.substring(0, constant.end()) + " {\n" + members + "\n    }" + text.substring(constant.end());
                    text = imports(text, bodyText, entry.getKey(), entry.getValue());
                }
                removed.add(bodyPath);
                folds.add(new Fold(entry.getKey() + ".java", body.bodyClass() + ".java", body.constant()));
            }
            if (removed.isEmpty()) continue;
            if (!text.equals(original)) Files.writeString(enumPath, text);
            for (var path : removed) Files.delete(path);
        }
        return folds;
    }

    private record Constant(int end, boolean hasBody) {}
    /** Constant {@code name} of {@code enum simple}: the offset right after its name or arguments, and whether a body follows. */
    private static Constant constant(String text, String simple, String name) {
        String masked = SourceText.mask(text);
        var header = Pattern.compile("\\benum\\s+" + Pattern.quote(simple) + "(?![\\w$])").matcher(masked);
        if (!header.find()) return null;
        int open = masked.indexOf('{', header.end());
        if (open < 0) return null;
        int close = SourceText.close(masked, open);
        if (close < 0) return null;
        // The constant list ends at the first ';' at depth 0, or at the closing brace.
        int i = open + 1;
        while (i < close) {
            while (i < close && (Character.isWhitespace(masked.charAt(i)) || masked.charAt(i) == ',')) i++;
            while (i < close && masked.charAt(i) == '@') { // annotations on a constant
                i++;
                while (i < close && (Character.isJavaIdentifierPart(masked.charAt(i)) || masked.charAt(i) == '.')) i++;
                while (i < close && Character.isWhitespace(masked.charAt(i))) i++;
                if (i < close && masked.charAt(i) == '(') { int end = SourceText.close(masked, i); if (end < 0) return null; i = end + 1; }
                while (i < close && Character.isWhitespace(masked.charAt(i))) i++;
            }
            if (i >= close || masked.charAt(i) == ';') return null;
            int start = i;
            while (i < close && Character.isJavaIdentifierPart(masked.charAt(i))) i++;
            if (i == start) return null;
            String found = masked.substring(start, i);
            int j = i;
            while (j < close && Character.isWhitespace(masked.charAt(j))) j++;
            if (j < close && masked.charAt(j) == '(') { int end = SourceText.close(masked, j); if (end < 0) return null; i = end + 1; j = i; }
            int end = i;
            while (j < close && Character.isWhitespace(masked.charAt(j))) j++;
            boolean body = j < close && masked.charAt(j) == '{';
            if (found.equals(name)) return new Constant(end, body);
            if (body) { int bodyEnd = SourceText.close(masked, j); if (bodyEnd < 0) return null; j = bodyEnd + 1; }
            while (j < close && Character.isWhitespace(masked.charAt(j))) j++;
            if (j >= close || masked.charAt(j) == ';') return null;
            if (masked.charAt(j) != ',') return null; // not a constant list: give up rather than guess
            i = j + 1;
        }
        return null;
    }
    /** The members of type {@code simple} in {@code text}, constructors left out; null when the declaration is not found. */
    static String members(String text, String simple) {
        String masked = SourceText.mask(text);
        var header = Pattern.compile("\\b(?:class|enum)\\s+" + Pattern.quote(simple) + "(?![\\w$])").matcher(masked);
        if (!header.find()) return null;
        int open = masked.indexOf('{', header.end());
        if (open < 0) return null;
        int close = SourceText.close(masked, open);
        if (close < 0) return null;
        var kept = new StringBuilder();
        var constructor = Pattern.compile(MODIFIERS.pattern() + Pattern.quote(simple) + "\\s*\\(");
        int i = open + 1;
        while (i < close) {
            while (i < close && (Character.isWhitespace(masked.charAt(i)) || masked.charAt(i) == ';')) i++;
            if (i >= close) break;
            int start = i, depth = 0; boolean initializer = false;
            while (i < close) {
                char c = masked.charAt(i);
                if (c == '(' || c == '[') depth++;
                else if (c == ')' || c == ']') depth--;
                else if (c == '=' && depth == 0) initializer = true;
                else if (c == ';' && depth == 0) { i++; break; }
                else if (c == '{' && depth == 0) {
                    int end = SourceText.close(masked, i);
                    if (end < 0 || end > close) return null;
                    i = end + 1;
                    if (!initializer) break;
                    continue;
                }
                i++;
            }
            String member = text.substring(start, i);
            if (!constructor.matcher(masked.substring(start, i)).lookingAt()) kept.append("        ").append(member.strip()).append('\n');
        }
        return kept.toString().stripTrailing();
    }
    /** {@code text} with the imports of {@code bodyText} it lacks, none naming the enum or its body classes. */
    private static String imports(String text, String bodyText, String enumClass, List<Body> bodies) {
        var excluded = new HashSet<String>();
        excluded.add(enumClass.replace('/', '.'));
        bodies.forEach(body -> excluded.add(body.bodyClass().replace('/', '.')));
        var present = new HashSet<String>(); int last = -1;
        var own = IMPORT.matcher(text);
        while (own.find()) { present.add(own.group(0).strip()); last = own.end(); }
        var added = new StringBuilder();
        var other = IMPORT.matcher(bodyText);
        while (other.find()) {
            String line = other.group(0).strip();
            if (other.group(1) == null && excluded.contains(other.group(2))) continue;
            if (present.add(line)) added.append('\n').append(line);
        }
        if (added.isEmpty()) return text;
        if (last < 0) {
            var pkg = Pattern.compile("(?m)^\\s*package\\s+[\\w.$]+\\s*;").matcher(SourceText.mask(text));
            last = pkg.find() ? pkg.end() : 0;
            if (last == 0) return added.substring(1) + "\n" + text;
        }
        return text.substring(0, last) + added + text.substring(last);
    }
}
