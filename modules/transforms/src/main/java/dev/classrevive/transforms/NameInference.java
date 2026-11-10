package dev.classrevive.transforms;

import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.*;
import java.util.*;
import java.util.regex.*;

/**
 * Phase 2DK: useful names for classes, private fields and private methods whose names an obfuscator shortened ({@code a},
 * {@code b}, {@code aa}, {@code lIl}). Every name is a <b>guess</b> read from bytecode evidence (never from running anything)
 * and carries the rule, the evidence and a confidence, so a person can decide what to trust. The plan is a
 * {@link ClassRenamer} mapping: it is applied by the same rename pass as the portable names, every reference follows, and
 * a name that is already readable is never touched.
 *
 * <p>Evidence, strongest first:
 * <ul>
 * <li>classes: a {@code toString} literal that starts with an identifier and a delimiter ({@code Player{name=}); the
 * SourceFile attribute when no other class shares it; a {@code main} method; a well-known JDK or library supertype
 * (a {@code Runnable} is a {@code Task}).</li>
 * <li>fields: the label before the field in a {@code toString}, log or exception message ({@code ", age=" + age});
 * the name of the constructor/method parameter assigned to it (LocalVariableTable); a {@code requireNonNull} message;
 * the key it is read from or written to in {@code put}/{@code get}-style calls (JSON, properties, maps); the generic
 * type ({@code List<Player>} is {@code players}).</li>
 * <li>methods: a getter, setter, adder or remover of a field with a readable name.</li>
 * </ul>
 * Only classes of the input are renamed (a nested class follows its outer class) and only <i>private</i> fields and methods:
 * a private member is never inherited or overridden, so every reference names its declaring class. Skipped on purpose:
 * Serializable classes without a serialVersionUID, classes with native methods or opaque attributes, annotated fields,
 * members whose name is also a string constant of the input (reflection by name), jars that enumerate their own members
 * reflectively or use Gson/Jackson, and methods in classes with a supertype outside the input and the JDK (a new name could
 * clash with an inherited method). Deterministic for a given class set.
 */
public final class NameInference {
    public enum Confidence {
        HIGH, MEDIUM, LOW;
        public boolean atLeast(Confidence minimum) { return ordinal() <= minimum.ordinal(); }
    }
    /** {@code kind}: class, field or method. {@code owner}/{@code name}/{@code descriptor} are the original identity (class: owner is the class, name empty). */
    public record Entry(String kind, String owner, String name, String descriptor, String inferred, String rule, String evidence, Confidence confidence) {}
    /** {@code locals}: the plan also names obfuscated local variables ({@code option locals = infer}); {@code localVariables}: how many variables that renames. */
    public record Result(Map<String,String> classes, List<ClassRenamer.MemberRule> members, List<Entry> entries, Map<String,Integer> skipped,
                         boolean locals, int localVariables) {
        public Result {
            classes = Collections.unmodifiableMap(new TreeMap<>(classes)); members = List.copyOf(members); entries = List.copyOf(entries);
            skipped = Collections.unmodifiableMap(new TreeMap<>(skipped));
        }
        public boolean isEmpty() { return classes.isEmpty() && members.isEmpty() && !locals; }
        public long count(String kind) { return entries.stream().filter(e -> e.kind().equals(kind)).count(); }
        /** The plan as a mapping file ({@link MappingFile} syntax), each rule preceded by a comment with its confidence, rule and evidence. */
        public String mapping() {
            var text = new StringBuilder("# Phase 2DK: INFERRED names. Each is a guess from bytecode evidence, not the original name.\n");
            if (locals) text.append("option locals = infer\n");
            var byRule = new TreeMap<String,Entry>();
            for (var entry : entries) byRule.put(entry.kind().equals("class") ? "0 " + entry.owner() : (entry.kind().equals("field") ? "1 " : "2 ") + entry.owner() + "#" + entry.name() + entry.descriptor(), entry);
            for (var entry : byRule.values()) {
                text.append("# ").append(entry.confidence()).append(' ').append(entry.rule()).append(": ").append(clean(entry.evidence())).append('\n');
                switch (entry.kind()) {
                    case "class" -> text.append("class ").append(entry.owner()).append(" = ").append(classes.get(entry.owner())).append('\n');
                    case "field" -> text.append("field ").append(entry.owner()).append('#').append(entry.name()).append(':').append(entry.descriptor())
                        .append(" = ").append(entry.inferred()).append('\n');
                    default -> text.append("method ").append(entry.owner()).append('#').append(entry.name()).append(entry.descriptor())
                        .append(" = ").append(entry.inferred()).append('\n');
                }
            }
            return text.toString();
        }
        private static String clean(String evidence) {
            var text = new StringBuilder();
            evidence.chars().forEach(c -> text.append(c < ' ' || Character.isISOControl(c) ? ' ' : (char) c));
            return text.length() > 200 ? text.substring(0, 200) : text.toString();
        }
    }

    private static final Result NONE = new Result(Map.of(), List.of(), List.of(), Map.of(), false, 0);
    private static final Set<String> READABLE_SHORT = Set.of("id", "ok", "to", "in", "on", "up", "is", "it", "no", "of", "or", "at", "by", "db", "ui",
        "io", "os", "ip", "pi", "dx", "dy", "dz", "tx", "rx", "me", "my", "am", "pm", "us", "uk", "tv", "pc", "vm", "fs", "ex", "op", "cb", "fn", "ms", "ns");
    /** Single letters people really use; obfuscated only inside a class whose own name is obfuscated. */
    private static final Set<String> AMBIGUOUS_LETTER = Set.of("x", "y", "z", "i", "j", "k", "n", "w", "h");
    private static final Set<String> RESERVED = Set.of("abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class", "const",
        "continue", "default", "do", "double", "else", "enum", "extends", "final", "finally", "float", "for", "goto", "if", "implements", "import",
        "instanceof", "int", "interface", "long", "native", "new", "package", "private", "protected", "public", "return", "short", "static",
        "strictfp", "super", "switch", "synchronized", "this", "throw", "throws", "transient", "try", "void", "volatile", "while", "true", "false",
        "null", "_", "yield", "var", "record", "sealed", "permits");
    /** Simple names a class must not take: they would shadow java.lang (or very common java.util) types in its package. */
    private static final Set<String> SHADOWS = Set.of("Object", "String", "Class", "Thread", "Runnable", "Exception", "Error", "Throwable", "Enum", "Record",
        "Math", "System", "Number", "Integer", "Long", "Short", "Byte", "Double", "Float", "Boolean", "Character", "Void", "Process", "Iterable", "Override",
        "Deprecated", "Module", "Package", "Runtime", "StringBuilder", "StringBuffer", "Comparable", "Cloneable", "AutoCloseable", "SecurityManager",
        "List", "Map", "Set", "Collection", "Queue", "Deque", "Iterator", "Optional", "Date", "Objects", "Arrays", "Collections", "Random", "Timer",
        "Scanner", "ArrayList", "HashMap", "HashSet", "Stack", "Vector", "Properties", "Locale", "UUID", "File", "Path", "Files", "Stream");
    private static final Set<String> COLLECTIONS = Set.of("java/util/List", "java/util/Set", "java/util/Collection", "java/util/Queue", "java/util/Deque",
        "java/lang/Iterable", "java/util/ArrayList", "java/util/LinkedList", "java/util/HashSet", "java/util/TreeSet", "java/util/LinkedHashSet",
        "java/util/Stack", "java/util/Vector", "java/util/ArrayDeque", "java/util/concurrent/CopyOnWriteArrayList", "java/util/SortedSet", "java/util/NavigableSet");
    private static final Set<String> MAPS = Set.of("java/util/Map", "java/util/HashMap", "java/util/TreeMap", "java/util/LinkedHashMap",
        "java/util/concurrent/ConcurrentHashMap", "java/util/SortedMap", "java/util/NavigableMap", "java/util/WeakHashMap", "java/util/EnumMap");
    private static final Set<String> GENERIC_TYPES = Set.of("java/lang/Object", "java/lang/String", "java/lang/Integer", "java/lang/Long", "java/lang/Short",
        "java/lang/Byte", "java/lang/Double", "java/lang/Float", "java/lang/Boolean", "java/lang/Character", "java/lang/Class", "java/lang/Number",
        "java/util/List", "java/util/Map", "java/util/Set", "java/util/Collection", "java/lang/Void", "java/lang/CharSequence", "java/lang/Throwable");
    private static final Pattern DECLARED_NAME = Pattern.compile("[a-z][A-Za-z0-9]{1,39}");
    private static final Pattern CLASS_LABEL = Pattern.compile("^([A-Z][A-Za-z0-9]{2,39})\\s*[\\[{(]");
    private static final Set<String> SINK_PREFIXES = Set.of("put", "set", "add", "write", "append", "with", "property");
    private static final Set<String> SOURCE_PREFIXES = Set.of("get", "opt", "read", "load", "parse", "has", "contains", "find");

    private final Map<String,ClassNode> nodes = new TreeMap<>();
    private final Map<String,Integer> skipped = new TreeMap<>();
    private final List<Entry> entries = new ArrayList<>();
    private final Set<String> strings = new HashSet<>();
    private final Map<String,String> finalClass = new TreeMap<>();
    private boolean reflectionHeavy;
    private final Map<String,Set<String>> jdkMethodCache = new HashMap<>();

    /** The plan for {@code classes}; {@code existingRenames}/{@code existingMembers} are renames already planned (portable names) and are left alone. */
    public static Result infer(Map<String,byte[]> classes, Map<String,String> existingRenames, Collection<ClassRenamer.MemberKey> existingMembers,
                               boolean locals, Confidence minimum) {
        return infer(classes, existingRenames, existingMembers, locals, minimum, Set.of());
    }
    /** {@code protectedClasses}: classes (internal names) that must keep their names because something outside the bytecode names them (manifest, services, plugin descriptors). */
    public static Result infer(Map<String,byte[]> classes, Map<String,String> existingRenames, Collection<ClassRenamer.MemberKey> existingMembers,
                               boolean locals, Confidence minimum, Set<String> protectedClasses) {
        if (classes.isEmpty() || classes.size() > 20000) return NONE;
        var inference = new NameInference();
        inference.protectedClasses = Set.copyOf(protectedClasses);
        return inference.run(classes, existingRenames, existingMembers == null ? Set.of() : new HashSet<>(existingMembers), locals, minimum);
    }
    private Set<String> protectedClasses = Set.of();

    private Result run(Map<String,byte[]> classes, Map<String,String> existingRenames, Set<ClassRenamer.MemberKey> existingMembers, boolean locals, Confidence minimum) {
        for (var type : classes.entrySet()) {
            try {
                var node = new ClassNode(); new ClassReader(type.getValue()).accept(node, 0);
                if (!node.name.equals(type.getKey())) return NONE;
                nodes.put(node.name, node);
            } catch (RuntimeException unreadable) { return NONE; }
        }
        for (var node : nodes.values()) if (IntegerConstantPass.hasOpaqueAttributes(node)) { skipped.put("opaque-attributes", nodes.size()); return NONE; }
        for (var node : nodes.values()) scanReflection(node);
        for (var name : nodes.keySet()) finalClass.put(name, existingRenames.getOrDefault(name, name));
        var classPlan = new TreeMap<String,String>();
        planClasses(existingRenames, minimum, classPlan);
        var members = new ArrayList<ClassRenamer.MemberRule>();
        planMembers(existingRenames, existingMembers, classPlan, minimum, members);
        int localCount = locals ? countLocals() : 0;
        var plan = new Result(classPlan, members, entries, skipped, locals && localCount > 0, localCount);
        return validate(classes, plan);
    }

    // ---------------------------------------------------------------- classes

    private void planClasses(Map<String,String> existing, Confidence minimum, Map<String,String> plan) {
        var taken = new HashSet<String>();
        for (var name : nodes.keySet()) taken.add(finalClass.get(name).toLowerCase(Locale.ROOT));
        var byStem = new TreeMap<String,List<String>>();
        var candidates = new TreeMap<String,Candidate>();
        for (var node : nodes.values()) {
            String name = node.name, simple = simple(name);
            if (existing.containsKey(name) || name.contains("$") || !obfuscated(simple, true) || special(name)) continue;
            String reason = classBlocker(node, existing);
            if (reason != null) { skipped.merge(reason, 1, Integer::sum); continue; }
            var candidate = classCandidate(node);
            if (candidate != null) candidates.put(name, candidate);
            String stem = sourceStem(node);
            if (stem != null) byStem.computeIfAbsent(stem, key -> new ArrayList<>()).add(name);
        }
        // The SourceFile stem names a class only when it is unambiguous or this class is the file's public class.
        for (var group : byStem.entrySet()) {
            var winner = pickStemWinner(group.getValue());
            if (winner == null) { skipped.merge("source-file-ambiguous", group.getValue().size(), Integer::sum); continue; }
            var current = candidates.get(winner);
            var stem = new Candidate(group.getKey(), "source-file", "SourceFile = " + nodes.get(winner).sourceFile, group.getValue().size() == 1 ? Confidence.HIGH : Confidence.MEDIUM);
            // A toString that names the class itself is more specific than the file name; the other rules are weaker than the file name.
            if (current == null || !current.rule().equals("tostring-class-label")) candidates.put(winner, stem);
            else if (current.name().equals(stem.name())) candidates.put(winner, new Candidate(current.name(), current.rule(), current.evidence() + "; " + stem.evidence(), Confidence.HIGH));
        }
        var order = new ArrayList<>(candidates.entrySet());
        order.sort(Comparator.comparing((Map.Entry<String,Candidate> e) -> e.getValue().confidence()).thenComparing(Map.Entry::getKey));
        for (var item : order) {
            var candidate = item.getValue();
            if (!candidate.confidence().atLeast(minimum)) { skipped.merge("below-minimum-confidence", 1, Integer::sum); continue; }
            String name = item.getKey(), base = candidate.name(), packageName = packageName(name);
            if (!validClassName(base) || SHADOWS.contains(base)) { skipped.merge("unusable-name", 1, Integer::sum); continue; }
            String target = null;
            for (int suffix = 1; target == null; suffix++) {
                String attempt = suffix == 1 ? base : base + suffix;
                if (!taken.contains((packageName + attempt).toLowerCase(Locale.ROOT))) target = attempt;
            }
            taken.add((packageName + target).toLowerCase(Locale.ROOT));
            plan.put(name, packageName + target);
            finalClass.put(name, packageName + target);
            for (var nested : nodes.keySet()) if (nested.startsWith(name + "$")) {
                plan.put(nested, packageName + target + nested.substring(name.length()));
                finalClass.put(nested, plan.get(nested));
            }
            entries.add(new Entry("class", name, "", "", packageName + target, candidate.rule(),
                candidate.evidence() + (target.equals(base) ? "" : " (name taken: " + target + ")"), candidate.confidence()));
        }
    }

    private record Candidate(String name, String rule, String evidence, Confidence confidence) {}

    private String classBlocker(ClassNode node, Map<String,String> existing) {
        if (protectedClasses.contains(node.name)) return "named-by-a-resource";
        if (nodes.keySet().stream().anyMatch(other -> other.startsWith(node.name + "$") && existing.containsKey(other))) return "nested-class-already-renamed";
        for (var method : node.methods) if ((method.access & Opcodes.ACC_NATIVE) != 0) return "native-methods";
        if ((node.access & Opcodes.ACC_MODULE) != 0) return "module";
        if (serializableWithoutUid(node)) return "serializable-without-serialVersionUID";
        return null;
    }
    private boolean serializableWithoutUid(ClassNode node) {
        if (!isSubtypeOf(node.name, "java/io/Serializable", new HashSet<>())) return false;
        for (var field : node.fields) if (field.name.equals("serialVersionUID") && field.desc.equals("J")) return false;
        return true;
    }
    private boolean isSubtypeOf(String type, String target, Set<String> seen) {
        if (type == null || !seen.add(type)) return false;
        if (type.equals(target)) return true;
        var node = nodes.get(type);
        if (node == null) return false;
        if (isSubtypeOf(node.superName, target, seen)) return true;
        for (String face : node.interfaces) if (isSubtypeOf(face, target, seen)) return true;
        return false;
    }
    private Candidate classCandidate(ClassNode node) {
        for (var method : node.methods) {
            if (!method.name.equals("toString") || !method.desc.equals("()Ljava/lang/String;") || (method.access & Opcodes.ACC_STATIC) != 0) continue;
            for (String text : stringsOf(method)) {
                var match = CLASS_LABEL.matcher(text);
                if (match.find()) return new Candidate(match.group(1), "tostring-class-label", "toString starts with \"" + abbreviate(text) + "\"", Confidence.HIGH);
            }
        }
        for (var method : node.methods) if (method.name.equals("main") && method.desc.equals("([Ljava/lang/String;)V")
            && (method.access & Opcodes.ACC_STATIC) != 0 && (method.access & Opcodes.ACC_PUBLIC) != 0)
            return new Candidate("Main", "main-method", "declares public static void main(String[])", Confidence.MEDIUM);
        return superCandidate(node);
    }
    private Candidate superCandidate(ClassNode node) {
        var externals = new ArrayList<String>();
        collectExternalSupers(node, externals, new HashSet<>());
        for (String external : externals) {
            String standalone = standaloneName(external);
            if (standalone != null) return new Candidate(standalone, "known-supertype", "extends or implements " + external.replace('/', '.'),
                standalone.endsWith("Exception") ? Confidence.MEDIUM : Confidence.LOW);
        }
        return null;
    }
    private void collectExternalSupers(ClassNode node, List<String> out, Set<String> seen) {
        var direct = new ArrayList<String>();
        if (node.superName != null) direct.add(node.superName);
        direct.addAll(node.interfaces);
        for (String type : direct) {
            if (!seen.add(type)) continue;
            var inJar = nodes.get(type);
            if (inJar != null) collectExternalSupers(inJar, out, seen);
            else if (!type.equals("java/lang/Object")) out.add(type);
        }
    }
    private static String standaloneName(String external) {
        String simple = simple(external);
        switch (external) {
            case "java/lang/Runnable", "java/util/concurrent/Callable" -> { return "Task"; }
            case "java/lang/Thread" -> { return "Worker"; }
            case "java/util/TimerTask" -> { return "TimerJob"; }
            case "java/util/Comparator" -> { return "CustomComparator"; }
            case "java/util/Iterator" -> { return "CustomIterator"; }
            case "java/io/Closeable", "java/lang/AutoCloseable" -> { return "Resource"; }
            case "java/io/InputStream" -> { return "CustomInputStream"; }
            case "java/io/OutputStream" -> { return "CustomOutputStream"; }
            case "java/io/Reader" -> { return "CustomReader"; }
            case "java/io/Writer" -> { return "CustomWriter"; }
            case "java/lang/Enum" -> { return "Kind"; }
            case "javax/swing/JFrame" -> { return "Frame"; }
            case "javax/swing/JPanel" -> { return "Panel"; }
            case "javax/swing/JDialog" -> { return "Dialog"; }
            case "org/bukkit/plugin/java/JavaPlugin" -> { return "MainPlugin"; }
            case "org/bukkit/command/CommandExecutor" -> { return "Command"; }
            case "android/app/Activity", "androidx/appcompat/app/AppCompatActivity" -> { return "Activity"; }
            case "android/app/Service" -> { return "Service"; }
            case "android/content/BroadcastReceiver" -> { return "Receiver"; }
            case "javafx/application/Application" -> { return "App"; }
            default -> { }
        }
        if (simple.length() > 4 && simple.endsWith("Exception") && !simple.equals("Exception")) return "CustomException";
        if (simple.equals("Exception") || simple.equals("RuntimeException") || simple.equals("Throwable")) return "CustomException";
        if (simple.length() > 8 && simple.endsWith("Listener")) return "Listener";
        if (simple.length() > 7 && simple.endsWith("Adapter")) return "Adapter";
        if (simple.length() > 7 && simple.endsWith("Handler")) return "Handler";
        if (simple.length() > 8 && simple.endsWith("Callback")) return "Callback";
        return null;
    }
    private static String sourceStem(ClassNode node) {
        String file = node.sourceFile;
        if (file == null) return null;
        int dot = file.lastIndexOf('.');
        if (dot < 3) return null;
        String stem = file.substring(0, dot), ext = file.substring(dot);
        if (!ext.equals(".java") && !ext.equals(".kt") && !ext.equals(".scala")) return null;
        if (stem.equals("SourceFile") || stem.equals("Unknown") || !validClassName(stem)) return null;
        return stem;
    }
    private String pickStemWinner(List<String> names) {
        if (names.size() == 1) return names.getFirst();
        var publics = names.stream().filter(n -> (nodes.get(n).access & Opcodes.ACC_PUBLIC) != 0).toList();
        return publics.size() == 1 ? publics.getFirst() : null;
    }

    // ---------------------------------------------------------------- members

    private record FieldKey(String owner, String name, String desc) {}
    private record Proposal(String name, String rule, String evidence, Confidence confidence) {}

    private void planMembers(Map<String,String> existing, Set<ClassRenamer.MemberKey> existingMembers, Map<String,String> classPlan, Confidence minimum,
                             List<ClassRenamer.MemberRule> rules) {
        if (reflectionHeavy) { skipped.merge("reflection-over-members", nodes.size(), Integer::sum); return; }
        var fieldFinal = new HashMap<FieldKey,String>();
        for (var node : nodes.values()) for (var field : node.fields) fieldFinal.put(new FieldKey(node.name, field.name, field.desc), field.name);
        for (var node : new ArrayList<>(nodes.values())) {
            if (special(node.name) || existing.containsKey(node.name)) continue;
            String block = memberBlocker(node);
            boolean gate = classGate(node, existingMembers);
            var fieldProposals = new TreeMap<String,List<Proposal>>();
            if (block == null && gate) analyzeFields(node, existingMembers, fieldProposals);
            var usedFields = new HashSet<String>();
            for (var field : node.fields) usedFields.add(field.name);
            for (var key : existingMembers) if (key.field() && key.owner().equals(node.name)) usedFields.add(key.name());
            var chosen = new TreeMap<String,Proposal>();
            var proposals = new ArrayList<>(fieldProposals.entrySet());
            for (var item : proposals) {
                var best = best(item.getValue());
                if (best != null && best.confidence().atLeast(minimum)) chosen.put(item.getKey(), best);
            }
            var order = new ArrayList<>(chosen.entrySet());
            order.sort(Comparator.comparing((Map.Entry<String,Proposal> e) -> e.getValue().confidence()).thenComparing(Map.Entry::getKey));
            for (var item : order) {
                var field = field(node, item.getKey());
                var proposal = item.getValue();
                String target = unique(proposal.name(), usedFields, name -> !RESERVED.contains(name) && !ClassRenamer.serializationMember(name, field.desc, true));
                usedFields.add(target);
                fieldFinal.put(new FieldKey(node.name, field.name, field.desc), target);
                rules.add(new ClassRenamer.MemberRule(node.name, field.name, field.desc, true, target));
                entries.add(new Entry("field", node.name, field.name, field.desc, target, proposal.rule(),
                    proposal.evidence() + (target.equals(proposal.name()) ? "" : " (name taken: " + target + ")"), proposal.confidence()));
            }
        }
        // Methods come last: a getter takes the name its field has now.
        for (var node : new ArrayList<>(nodes.values())) {
            if (special(node.name) || existing.containsKey(node.name) || !classGate(node, existingMembers) || memberBlocker(node) != null) continue;
            if (!methodsSafe(node)) { skipped.merge("unknown-supertype-methods", 1, Integer::sum); continue; }
            var inherited = inheritedMethods(node);
            var used = new HashSet<String>();
            for (var method : node.methods) used.add(method.name + paramsOf(method.desc));
            for (var key : existingMembers) if (!key.field() && key.owner().equals(node.name)) used.add(key.name() + paramsOf(key.descriptor()));
            var methodProposals = new TreeMap<String,Proposal>();
            for (var method : node.methods) {
                if ((method.access & Opcodes.ACC_PRIVATE) == 0 || (method.access & (Opcodes.ACC_SYNTHETIC | Opcodes.ACC_NATIVE | Opcodes.ACC_BRIDGE)) != 0) continue;
                if (method.name.startsWith("<") || !obfuscated(method.name, obfuscated(simple(node.name), true))) continue;
                if (existingMembers.contains(new ClassRenamer.MemberKey(node.name, method.name, method.desc, false)) || strings.contains(method.name)) continue;
                if (ClassRenamer.serializationMember(method.name, method.desc, false)) continue;
                var proposal = accessorProposal(node, method, fieldFinal);
                if (proposal != null && proposal.confidence().atLeast(minimum)) methodProposals.put(method.name + method.desc, proposal);
            }
            var order = new ArrayList<>(methodProposals.entrySet());
            order.sort(Comparator.comparing((Map.Entry<String,Proposal> e) -> e.getValue().confidence()).thenComparing(Map.Entry::getKey));
            for (var item : order) {
                String key = item.getKey();
                var method = node.methods.stream().filter(m -> (m.name + m.desc).equals(key)).findFirst().orElseThrow();
                var proposal = item.getValue();
                String params = paramsOf(method.desc), target = null;
                for (int suffix = 1; target == null; suffix++) {
                    String attempt = suffix == 1 ? proposal.name() : proposal.name() + suffix;
                    if (!used.contains(attempt + params) && !inherited.contains(attempt + params) && !RESERVED.contains(attempt)
                        && !ClassRenamer.serializationMember(attempt, method.desc, false)) target = attempt;
                }
                used.add(target + params);
                rules.add(new ClassRenamer.MemberRule(node.name, method.name, method.desc, false, target));
                entries.add(new Entry("method", node.name, method.name, method.desc, target, proposal.rule(),
                    proposal.evidence() + (target.equals(proposal.name()) ? "" : " (name taken: " + target + ")"), proposal.confidence()));
            }
        }
    }
    private static FieldNode field(ClassNode node, String key) {
        for (var field : node.fields) if ((field.name + ":" + field.desc).equals(key)) return field;
        throw new IllegalStateException(key);
    }
    private static Proposal best(List<Proposal> proposals) {
        Proposal best = null;
        for (var proposal : proposals) if (best == null || proposal.confidence().ordinal() < best.confidence().ordinal()) best = proposal;
        return best;
    }
    private static String unique(String base, Set<String> used, java.util.function.Predicate<String> allowed) {
        for (int suffix = 1; ; suffix++) {
            String attempt = suffix == 1 ? base : base + suffix;
            if (!used.contains(attempt) && allowed.test(attempt)) return attempt;
        }
    }
    private String memberBlocker(ClassNode node) {
        if ((node.access & Opcodes.ACC_RECORD) != 0) return "record";
        if (isSubtypeOf(node.name, "java/io/Serializable", new HashSet<>())) { skipped.merge("serializable", 1, Integer::sum); return "serializable"; }
        return null;
    }
    /** A class is a target for member names when it, or most of its private members, carry obfuscated names. */
    private boolean classGate(ClassNode node, Set<ClassRenamer.MemberKey> existingMembers) {
        if (obfuscated(simple(node.name), true)) return true;
        int privateMembers = 0, shortMembers = 0;
        for (var field : node.fields) if ((field.access & Opcodes.ACC_PRIVATE) != 0 && (field.access & Opcodes.ACC_SYNTHETIC) == 0) { privateMembers++; if (obfuscated(field.name, false)) shortMembers++; }
        for (var method : node.methods) if ((method.access & Opcodes.ACC_PRIVATE) != 0 && (method.access & Opcodes.ACC_SYNTHETIC) == 0 && !method.name.startsWith("<")) {
            privateMembers++; if (obfuscated(method.name, false)) shortMembers++;
        }
        return privateMembers >= 2 && shortMembers * 10 >= privateMembers * 6 && shortMembers >= 2;
    }

    private void analyzeFields(ClassNode node, Set<ClassRenamer.MemberKey> existingMembers, Map<String,List<Proposal>> out) {
        boolean strong = obfuscated(simple(node.name), true);
        var eligible = new HashMap<String,FieldNode>();
        for (var field : node.fields) {
            if ((field.access & Opcodes.ACC_PRIVATE) == 0 || (field.access & Opcodes.ACC_SYNTHETIC) != 0) continue;
            if (!obfuscated(field.name, strong) || strings.contains(field.name)) continue;
            if (existingMembers.contains(new ClassRenamer.MemberKey(node.name, field.name, field.desc, true))) continue;
            if (field.visibleAnnotations != null && !field.visibleAnnotations.isEmpty() || field.invisibleAnnotations != null && !field.invisibleAnnotations.isEmpty()) {
                skipped.merge("annotated-field", 1, Integer::sum); continue;
            }
            if (ClassRenamer.serializationMember(field.name, field.desc, true)) continue;
            eligible.put(field.name + ":" + field.desc, field);
        }
        if (eligible.isEmpty()) return;
        for (var method : node.methods) {
            if (method.instructions.size() == 0) continue;
            boolean toStringMethod = method.name.equals("toString") && method.desc.equals("()Ljava/lang/String;");
            labelScan(node, method, eligible, toStringMethod ? Confidence.HIGH : Confidence.MEDIUM, out);
            analyzeFlow(node, method, eligible, out);
        }
        for (var item : eligible.entrySet()) {
            var field = item.getValue();
            String typed = typeName(field);
            if (typed != null) {
                boolean constant = (field.access & Opcodes.ACC_STATIC) != 0 && (field.access & Opcodes.ACC_FINAL) != 0;
                if (!constant) add(out, item.getKey(), typed, "field-type", "declared type " + (field.signature != null ? field.signature : field.desc), Confidence.LOW);
            }
        }
        // Candidates must be usable field names; static final constants take UPPER_SNAKE_CASE.
        for (var item : new ArrayList<>(out.entrySet())) {
            var field = eligible.get(item.getKey());
            item.getValue().removeIf(p -> !DECLARED_NAME.matcher(p.name()).matches() || RESERVED.contains(p.name()) || obfuscated(p.name(), false));
            if (field != null && (field.access & Opcodes.ACC_STATIC) != 0 && (field.access & Opcodes.ACC_FINAL) != 0)
                item.setValue(item.getValue().stream().map(p -> new Proposal(upperSnake(p.name()), p.rule(), p.evidence(), p.confidence())).toList());
        }
        out.keySet().removeIf(key -> !eligible.containsKey(key));
    }
    private static void add(Map<String,List<Proposal>> out, String field, String name, String rule, String evidence, Confidence confidence) {
        if (name == null) return;
        out.computeIfAbsent(field, key -> new ArrayList<>()).add(new Proposal(name, rule, evidence, confidence));
    }

    /** Label strings before a field in a concatenation: {@code ", age=" + age} (StringBuilder chains and invokedynamic recipes). */
    private void labelScan(ClassNode node, MethodNode method, Map<String,FieldNode> eligible, Confidence confidence, Map<String,List<Proposal>> out) {
        String label = null; int distance = 0;
        for (var insn : method.instructions) {
            int op = insn.getOpcode();
            if (op < 0) continue;
            if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof String text) { label = text; distance = 0; continue; }
            if (label != null && ++distance > 5) label = null;
            if (label != null && insn instanceof FieldInsnNode field && op == Opcodes.GETFIELD && field.owner.equals(node.name) && eligible.containsKey(field.name + ":" + field.desc)) {
                String name = labelName(label);
                if (name != null) add(out, field.name + ":" + field.desc, name, "text-label", "\"" + abbreviate(label) + "\" precedes it in " + method.name, confidence);
                label = null;
            }
        }
        for (var insn : method.instructions) {
            if (!(insn instanceof InvokeDynamicInsnNode dynamic) || !dynamic.bsm.getOwner().equals("java/lang/invoke/StringConcatFactory")
                || dynamic.bsmArgs.length == 0 || !(dynamic.bsmArgs[0] instanceof String recipe)) continue;
            Frame<SourceValue>[] frames = frames(node, method);
            if (frames == null) break;
            int index = method.instructions.indexOf(insn);
            var frame = frames[index];
            if (frame == null) continue;
            int args = Type.getArgumentTypes(dynamic.desc).length;
            String[] parts = recipe.split("\u0001", -1);
            for (int k = 0; k < args && k < parts.length - 0; k++) {
                var value = frame.getStack(frame.getStackSize() - args + k);
                var field = singleField(value, node.name);
                if (field == null || !eligible.containsKey(field.name + ":" + field.desc) || k >= parts.length) continue;
                String name = labelName(parts[k]);
                if (name != null) add(out, field.name + ":" + field.desc, name, "text-label", "\"" + abbreviate(parts[k]) + "\" precedes it in " + method.name, confidence);
            }
        }
    }
    private static FieldInsnNode singleField(SourceValue value, String owner) {
        if (value == null || value.insns.size() != 1) return null;
        var insn = value.insns.iterator().next();
        if (insn instanceof FieldInsnNode field && field.getOpcode() == Opcodes.GETFIELD && field.owner.equals(owner)) return field;
        return null;
    }
    private final Map<MethodNode,Frame<SourceValue>[]> frameCache = new IdentityHashMap<>();
    @SuppressWarnings("unchecked")
    private Frame<SourceValue>[] frames(ClassNode node, MethodNode method) {
        if (frameCache.containsKey(method)) return frameCache.get(method);
        Frame<SourceValue>[] frames = null;
        if (method.instructions.size() <= 5000) {
            try { frames = new Analyzer<>(new SourceInterpreter()).analyze(node.name, method); }
            catch (AnalyzerException | RuntimeException failed) { frames = null; }
        }
        frameCache.put(method, frames);
        return frames;
    }

    /** Parameter names, requireNonNull messages and map/JSON/properties keys that reach or leave a field. */
    private void analyzeFlow(ClassNode node, MethodNode method, Map<String,FieldNode> eligible, Map<String,List<Proposal>> out) {
        boolean relevant = false;
        for (var insn : method.instructions) if (insn instanceof FieldInsnNode f && f.owner.equals(node.name) && eligible.containsKey(f.name + ":" + f.desc)) { relevant = true; break; }
        if (!relevant) return;
        Frame<SourceValue>[] frames = frames(node, method);
        if (frames == null) return;
        var locals = new HashMap<Integer,List<LocalVariableNode>>();
        if (method.localVariables != null) for (var local : method.localVariables) locals.computeIfAbsent(local.index, key -> new ArrayList<>()).add(local);
        for (int i = 0; i < method.instructions.size(); i++) {
            var insn = method.instructions.get(i);
            var frame = frames[i];
            if (frame == null) continue;
            if (insn instanceof FieldInsnNode put && put.getOpcode() == Opcodes.PUTFIELD && put.owner.equals(node.name) && eligible.containsKey(put.name + ":" + put.desc)) {
                String field = put.name + ":" + put.desc;
                var value = frame.getStack(frame.getStackSize() - 1);
                var source = single(value);
                // Unwrap Objects.requireNonNull(param, "name") and checkcasts.
                while (source instanceof TypeInsnNode cast && cast.getOpcode() == Opcodes.CHECKCAST) source = single(frames[method.instructions.indexOf(cast)].getStack(frames[method.instructions.indexOf(cast)].getStackSize() - 1));
                if (source instanceof MethodInsnNode call && call.owner.equals("java/util/Objects") && call.name.equals("requireNonNull") && call.desc.startsWith("(Ljava/lang/Object;Ljava/lang/String;)")) {
                    var callFrame = frames[method.instructions.indexOf(call)];
                    var message = single(callFrame.getStack(callFrame.getStackSize() - 1));
                    var argument = single(callFrame.getStack(callFrame.getStackSize() - 2));
                    if (message instanceof LdcInsnNode ldc && ldc.cst instanceof String text) {
                        String name = keyName(text);
                        if (name != null) add(out, field, name, "require-non-null", "requireNonNull(..., \"" + abbreviate(text) + "\")", Confidence.HIGH);
                    }
                    source = argument;
                }
                int parameterSlots = Type.getArgumentsAndReturnSizes(method.desc) >> 2;
                if (source instanceof VarInsnNode load && load.getOpcode() >= Opcodes.ILOAD && load.getOpcode() <= Opcodes.ALOAD && load.var > 0
                    && load.var < parameterSlots) {
                    String parameter = localName(locals.get(load.var), method.instructions.indexOf(load), method);
                    if (parameter != null && !obfuscated(parameter, false) && !parameter.startsWith("arg"))
                        add(out, field, parameter, "parameter-name", "assigned from parameter \"" + parameter + "\" of " + method.name, Confidence.HIGH);
                }
                source = unwrapConversion(method, frames, source);
                if (source instanceof MethodInsnNode read && sourceCall(read)) {
                    var readFrame = frames[method.instructions.indexOf(read)];
                    int args = Type.getArgumentTypes(read.desc).length;
                    for (int a = 0; a < args; a++) {
                        var literal = single(readFrame.getStack(readFrame.getStackSize() - args + a));
                        if (literal instanceof LdcInsnNode ldc && ldc.cst instanceof String text && keyName(text) != null)
                            add(out, field, keyName(text), "key-read", "read with key \"" + abbreviate(text) + "\" by " + read.owner.substring(read.owner.lastIndexOf('/') + 1) + "." + read.name, Confidence.MEDIUM);
                    }
                }
            }
            if (insn instanceof MethodInsnNode sink && sinkCall(sink)) {
                int args = Type.getArgumentTypes(sink.desc).length;
                for (int a = 0; a + 1 < args; a++) {
                    var literal = single(frame.getStack(frame.getStackSize() - args + a));
                    var value = single(frame.getStack(frame.getStackSize() - args + a + 1));
                    if (value instanceof MethodInsnNode boxing && boxing.getOpcode() == Opcodes.INVOKESTATIC && boxing.name.equals("valueOf") && boxing.desc.startsWith("(")) {
                        var boxFrame = frames[method.instructions.indexOf(boxing)];
                        value = single(boxFrame.getStack(boxFrame.getStackSize() - 1));
                    }
                    if (literal instanceof LdcInsnNode ldc && ldc.cst instanceof String text && value instanceof FieldInsnNode got && got.getOpcode() == Opcodes.GETFIELD
                        && got.owner.equals(node.name) && eligible.containsKey(got.name + ":" + got.desc) && keyName(text) != null)
                        add(out, got.name + ":" + got.desc, keyName(text), "key-write", "written with key \"" + abbreviate(text) + "\" by " + sink.owner.substring(sink.owner.lastIndexOf('/') + 1) + "." + sink.name, Confidence.MEDIUM);
                }
            }
        }
    }
    /** Looks through {@code Integer.parseInt(x)}, {@code Boolean.valueOf(x)}, {@code x.intValue()} and {@code x.trim()} to the value they convert. */
    private static AbstractInsnNode unwrapConversion(MethodNode method, Frame<SourceValue>[] frames, AbstractInsnNode source) {
        for (int depth = 0; depth < 2 && source instanceof MethodInsnNode call && conversion(call); depth++) {
            var frame = frames[method.instructions.indexOf(call)];
            if (frame == null) break;
            int args = Type.getArgumentTypes(call.desc).length;
            int slot = frame.getStackSize() - args - (call.getOpcode() == Opcodes.INVOKESTATIC ? 0 : 1);
            if (call.getOpcode() == Opcodes.INVOKESTATIC && args < 1 || slot < 0) break;
            source = single(frame.getStack(call.getOpcode() == Opcodes.INVOKESTATIC ? slot : slot));
        }
        return source;
    }
    private static boolean conversion(MethodInsnNode call) {
        return call.name.startsWith("parse") || call.name.equals("valueOf") || call.name.endsWith("Value") || call.name.equals("trim")
            || call.name.equals("toLowerCase") || call.name.equals("toUpperCase");
    }
    private static boolean sourceCall(MethodInsnNode call) {
        if (call.getOpcode() == Opcodes.INVOKESPECIAL || call.name.startsWith("<")) return false;
        for (String prefix : SOURCE_PREFIXES) if (call.name.startsWith(prefix)) return Type.getArgumentTypes(call.desc).length >= 1;
        return false;
    }
    private static boolean sinkCall(MethodInsnNode call) {
        if (call.name.startsWith("<")) return false;
        for (String prefix : SINK_PREFIXES) if (call.name.startsWith(prefix)) return true;
        return false;
    }
    private static AbstractInsnNode single(SourceValue value) {
        if (value == null || value.insns.size() != 1) return null;
        return value.insns.iterator().next();
    }
    private static String localName(List<LocalVariableNode> candidates, int instructionIndex, MethodNode method) {
        if (candidates == null) return null;
        for (var local : candidates) {
            int start = method.instructions.indexOf(local.start), end = method.instructions.indexOf(local.end);
            if (instructionIndex >= start && instructionIndex < end) return local.name;
        }
        return null;
    }

    // ---------------------------------------------------------------- methods

    private Proposal accessorProposal(ClassNode node, MethodNode method, Map<FieldKey,String> fieldFinal) {
        var real = new ArrayList<AbstractInsnNode>();
        for (var insn : method.instructions) if (insn.getOpcode() >= 0) real.add(insn);
        String params = method.desc;
        boolean isStatic = (method.access & Opcodes.ACC_STATIC) != 0;
        int shift = isStatic ? 0 : 1;
        Type ret = Type.getReturnType(method.desc);
        // getter: [aload 0] getfield f; xreturn
        if (real.size() == 2 + shift && ret.getSort() != Type.VOID && Type.getArgumentTypes(params).length == 0
            && real.get(shift) instanceof FieldInsnNode got && got.getOpcode() == (isStatic ? Opcodes.GETSTATIC : Opcodes.GETFIELD)
            && got.owner.equals(node.name) && got.desc.equals(ret.getDescriptor()) && real.get(shift + 1).getOpcode() >= Opcodes.IRETURN && real.get(shift + 1).getOpcode() <= Opcodes.ARETURN
            && (!isStatic == (real.get(0).getOpcode() == Opcodes.ALOAD))) {
            String name = fieldFinal.get(new FieldKey(got.owner, got.name, got.desc));
            if (readable(name)) return new Proposal((ret.getSort() == Type.BOOLEAN ? booleanGetter(name) : "get" + capitalize(name)), "getter",
                "returns field " + name + (name.equals(got.name) ? "" : " (inferred)"), derived(got, name, Confidence.HIGH));
        }
        // setter: aload 0; xload 1; putfield f; return
        if (!isStatic && real.size() == 4 && ret.getSort() == Type.VOID && Type.getArgumentTypes(params).length == 1
            && real.get(2) instanceof FieldInsnNode put && put.getOpcode() == Opcodes.PUTFIELD && put.owner.equals(node.name)
            && real.get(0).getOpcode() == Opcodes.ALOAD && real.get(1) instanceof VarInsnNode load && load.var == 1 && real.get(3).getOpcode() == Opcodes.RETURN
            && put.desc.equals(Type.getArgumentTypes(params)[0].getDescriptor())) {
            String name = fieldFinal.get(new FieldKey(put.owner, put.name, put.desc));
            if (readable(name)) return new Proposal("set" + capitalize(name), "setter", "assigns field " + name + (name.equals(put.name) ? "" : " (inferred)"), derived(put, name, Confidence.HIGH));
        }
        // adder / remover: aload 0; getfield f; xload 1; invoke add/remove; [pop]; return
        if (!isStatic && Type.getArgumentTypes(params).length == 1 && real.size() >= 5 && real.get(0).getOpcode() == Opcodes.ALOAD
            && real.get(1) instanceof FieldInsnNode got && got.getOpcode() == Opcodes.GETFIELD && got.owner.equals(node.name)
            && real.get(2) instanceof VarInsnNode load && load.var == 1 && real.get(3) instanceof MethodInsnNode call
            && (call.name.equals("add") || call.name.equals("remove")) && call.desc.startsWith("(Ljava/lang/Object;)")
            && collectionDesc(got.desc) && tail(real, 4)) {
            String name = fieldFinal.get(new FieldKey(got.owner, got.name, got.desc));
            if (readable(name)) return new Proposal(call.name + capitalize(singular(name)), call.name.equals("add") ? "adder" : "remover",
                call.name + "s to/from collection field " + name + (name.equals(got.name) ? "" : " (inferred)"), derived(got, name, Confidence.MEDIUM));
        }
        return null;
    }
    private static boolean tail(List<AbstractInsnNode> real, int from) {
        for (int i = from; i < real.size(); i++) {
            int op = real.get(i).getOpcode();
            boolean last = i == real.size() - 1;
            if (last && (op == Opcodes.RETURN || (op >= Opcodes.IRETURN && op <= Opcodes.ARETURN))) return true;
            if (op != Opcodes.POP && op != Opcodes.POP2 && op != Opcodes.ICONST_0 && op != Opcodes.ICONST_1 && op != Opcodes.ALOAD) return false;
        }
        return false;
    }
    private static boolean collectionDesc(String desc) { return desc.startsWith("L") && (COLLECTIONS.contains(desc.substring(1, desc.length() - 1)) || MAPS.contains(desc.substring(1, desc.length() - 1))); }
    private Confidence derived(FieldInsnNode field, String name, Confidence ceiling) {
        // A readable original field name is a fact; an inferred one weakens the derived method name.
        if (name.equals(field.name)) return ceiling;
        var inferred = entries.stream().filter(e -> e.kind().equals("field") && e.owner().equals(field.owner) && e.name().equals(field.name) && e.descriptor().equals(field.desc)).findFirst();
        Confidence base = inferred.map(Entry::confidence).orElse(Confidence.LOW);
        Confidence weaker = base == Confidence.HIGH ? Confidence.MEDIUM : Confidence.LOW;
        return weaker.ordinal() < ceiling.ordinal() ? ceiling : weaker;
    }
    private static boolean readable(String name) { return name != null && DECLARED_NAME.matcher(name).matches() && !obfuscated(name, false); }
    private static String booleanGetter(String name) {
        if (name.matches("(is|has|can|should|was|will)[A-Z].*")) return name;
        return "is" + capitalize(name);
    }
    private static String paramsOf(String desc) { return desc.substring(0, desc.indexOf(')') + 1); }

    /** Names of non-private methods (name + parameters) inherited by {@code node} from supertypes in the input or the JDK. */
    private Set<String> inheritedMethods(ClassNode node) {
        var names = new HashSet<String>();
        var seen = new HashSet<String>();
        var queue = new ArrayDeque<String>();
        if (node.superName != null) queue.add(node.superName);
        queue.addAll(node.interfaces);
        while (!queue.isEmpty()) {
            String type = queue.poll();
            if (!seen.add(type)) continue;
            var inJar = nodes.get(type);
            if (inJar != null) {
                for (var method : inJar.methods) if ((method.access & Opcodes.ACC_PRIVATE) == 0 && !method.name.startsWith("<")) names.add(method.name + paramsOf(method.desc));
                if (inJar.superName != null) queue.add(inJar.superName);
                queue.addAll(inJar.interfaces);
            } else names.addAll(jdkMethods(type));
        }
        return names;
    }
    private boolean methodsSafe(ClassNode node) {
        var seen = new HashSet<String>();
        var queue = new ArrayDeque<String>();
        if (node.superName != null) queue.add(node.superName);
        queue.addAll(node.interfaces);
        while (!queue.isEmpty()) {
            String type = queue.poll();
            if (!seen.add(type)) continue;
            var inJar = nodes.get(type);
            if (inJar != null) { if (inJar.superName != null) queue.add(inJar.superName); queue.addAll(inJar.interfaces); }
            else if (jdkMethods(type) == null) return false;
        }
        return true;
    }
    private Set<String> jdkMethods(String type) {
        if (jdkMethodCache.containsKey(type)) return jdkMethodCache.get(type);
        Set<String> result = null;
        if (type.startsWith("java/") || type.startsWith("javax/")) {
            try {
                // Loads the JDK class without initializing it; classes of the input are never loaded.
                var loaded = Class.forName(type.replace('/', '.'), false, ClassLoader.getPlatformClassLoader());
                var names = new HashSet<String>();
                collectJdk(loaded, names, new HashSet<>());
                result = names;
            } catch (ClassNotFoundException | LinkageError | RuntimeException unavailable) { result = null; }
        }
        jdkMethodCache.put(type, result);
        return result == null ? null : result;
    }
    private static void collectJdk(Class<?> type, Set<String> names, Set<Class<?>> seen) {
        if (type == null || !seen.add(type)) return;
        for (var method : type.getDeclaredMethods()) {
            if (java.lang.reflect.Modifier.isPrivate(method.getModifiers())) continue;
            var params = new StringBuilder("(");
            for (var parameter : method.getParameterTypes()) params.append(Type.getDescriptor(parameter));
            names.add(method.getName() + params.append(')'));
        }
        collectJdk(type.getSuperclass(), names, seen);
        for (var face : type.getInterfaces()) collectJdk(face, names, seen);
    }

    // ---------------------------------------------------------------- names from text and types

    /** The last words of a label such as {@code "Player{name="} or {@code ", total balance: "}, as a camelCase field name; null when it is no label. */
    static String labelName(String label) {
        var match = Pattern.compile("([A-Za-z][A-Za-z0-9_ ]{1,60}?)['\"\\]]*\\s*[=:]+\\s*['\"\\[]*\\s*$").matcher(label);
        if (!match.find()) return null;
        // The phrase is the whole run of words before the delimiter; it must not start in the middle of a word.
        if (match.start(1) > 0 && Character.isLetterOrDigit(label.charAt(match.start(1) - 1))) return null;
        return camel(match.group(1).trim().split("[ _]+"));
    }
    /** A key such as {@code user_name}, {@code user-name} or {@code server.port} as a camelCase field name; null when it is no plain key. */
    static String keyName(String key) {
        if (key == null || !key.matches("[A-Za-z][A-Za-z0-9_.\\- ]{1,40}") || key.contains("..") ) return null;
        return camel(key.split("[_.\\- ]+"));
    }
    private static String camel(String[] words) {
        if (words.length == 0 || words.length > 3) return null;
        var name = new StringBuilder();
        for (int i = 0; i < words.length; i++) {
            String word = words[i];
            if (word.isEmpty() || !word.matches("[A-Za-z][A-Za-z0-9]*")) return null;
            boolean allCaps = word.length() > 1 && word.equals(word.toUpperCase(Locale.ROOT));
            word = allCaps ? word.toLowerCase(Locale.ROOT) : word;
            name.append(i == 0 ? Character.toLowerCase(word.charAt(0)) + word.substring(1) : capitalize(word));
        }
        String result = name.toString();
        return DECLARED_NAME.matcher(result).matches() && !RESERVED.contains(result) ? result : null;
    }
    private String typeName(FieldNode field) {
        Type type = Type.getType(field.desc);
        if (type.getSort() == Type.ARRAY) {
            Type element = type.getElementType();
            String base = element.getSort() == Type.OBJECT ? classWord(element.getInternalName()) : null;
            return base == null ? null : plural(base);
        }
        if (type.getSort() != Type.OBJECT) return null;
        String internal = type.getInternalName();
        if (field.signature != null) {
            var generic = Pattern.compile("^L([^<;]+)<(.+)>;$").matcher(field.signature);
            if (generic.find()) {
                var args = splitArguments(generic.group(2));
                if (COLLECTIONS.contains(generic.group(1)) && args.size() == 1) {
                    String element = signatureClass(args.getFirst());
                    String word = element == null ? null : classWord(element);
                    if (word != null) return plural(word);
                    return null;
                }
                if (MAPS.contains(generic.group(1)) && args.size() == 2) {
                    String element = signatureClass(args.get(1));
                    String word = element == null ? null : classWord(element);
                    return word == null ? null : word + "Map";
                }
            }
        }
        if (GENERIC_TYPES.contains(internal) || COLLECTIONS.contains(internal) || MAPS.contains(internal)) return null;
        String word = classWord(internal);
        return word;
    }
    private static List<String> splitArguments(String text) {
        var parts = new ArrayList<String>(); int depth = 0, start = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '<') depth++; else if (c == '>') depth--;
            else if (depth == 0 && c == ';') { parts.add(text.substring(start, i + 1)); start = i + 1; }
        }
        if (start < text.length()) parts.add(text.substring(start));
        return parts;
    }
    private static String signatureClass(String argument) {
        var match = Pattern.compile("^L([^<;]+)(?:<.*>)?;$").matcher(argument);
        return match.find() ? match.group(1) : null;
    }
    /** A camelCase word for a class: its final simple name, decapitalized; null for generic or obfuscated classes. */
    private String classWord(String internal) {
        if (GENERIC_TYPES.contains(internal)) return null;
        String name = finalClass.getOrDefault(internal, internal);
        String simple = simple(name);
        int dollar = simple.lastIndexOf('$');
        if (dollar >= 0) simple = simple.substring(dollar + 1);
        if (simple.length() < 3 || obfuscated(simple, true) || !simple.matches("[A-Za-z][A-Za-z0-9]*")) return null;
        String word = Character.toLowerCase(simple.charAt(0)) + simple.substring(1);
        return RESERVED.contains(word) ? null : word;
    }
    static String plural(String word) {
        if (word.endsWith("s") || word.endsWith("x") || word.endsWith("ch") || word.endsWith("sh")) return word + "es";
        if (word.endsWith("y") && word.length() > 1 && "aeiou".indexOf(word.charAt(word.length() - 2)) < 0) return word.substring(0, word.length() - 1) + "ies";
        return word + "s";
    }
    static String singular(String word) {
        if (word.endsWith("ies") && word.length() > 4) return word.substring(0, word.length() - 3) + "y";
        if (word.endsWith("ses") || word.endsWith("xes") || word.endsWith("ches") || word.endsWith("shes")) return word.substring(0, word.length() - 2);
        if (word.endsWith("s") && !word.endsWith("ss") && word.length() > 3) return word.substring(0, word.length() - 1);
        return word;
    }
    private static String upperSnake(String name) {
        var text = new StringBuilder();
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (Character.isUpperCase(c) && i > 0) text.append('_');
            text.append(Character.toUpperCase(c));
        }
        return text.toString();
    }
    private static String capitalize(String word) { return word.isEmpty() ? word : Character.toUpperCase(word.charAt(0)) + word.substring(1); }
    private static String abbreviate(String text) {
        String clean = text.replace('\n', ' ').replace('\r', ' ');
        return clean.length() > 60 ? clean.substring(0, 57) + "..." : clean;
    }

    // ---------------------------------------------------------------- obfuscation test, scans

    /** Whether the name looks machine-made: {@code a}, {@code Zz}, {@code lIl}, {@code OO0}. {@code strong}: inside an obfuscated class a single x or i counts too. */
    static boolean obfuscated(String name, boolean strong) {
        if (name == null || name.isEmpty() || name.startsWith("<")) return false;
        if (name.matches("[Il1]{2,}") || name.matches("[Oo0]{3,}") || name.matches("[Il1][Oo0]+[Il1Oo0]*")) return true;
        if (!name.matches("[A-Za-z]{1,2}")) return false;
        String lower = name.toLowerCase(Locale.ROOT);
        if (READABLE_SHORT.contains(lower)) return false;
        return name.length() == 2 || strong || !AMBIGUOUS_LETTER.contains(lower);
    }
    private void scanReflection(ClassNode node) {
        for (var method : node.methods) {
            for (var insn : method.instructions) {
                if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof String text && text.length() <= 200) strings.add(text);
                if (insn instanceof MethodInsnNode call) {
                    if (call.owner.equals("java/lang/Class") && (call.name.equals("getDeclaredFields") || call.name.equals("getFields") || call.name.equals("getDeclaredMethods")
                        || call.name.equals("getMethods") || call.name.equals("getDeclaredConstructors"))) reflectionHeavy = true;
                    if (call.owner.startsWith("com/google/gson/") || call.owner.startsWith("com/fasterxml/jackson/") || call.owner.startsWith("org/yaml/")
                        || call.owner.startsWith("sun/misc/Unsafe") || call.owner.equals("java/lang/reflect/Field")) reflectionHeavy = true;
                }
                if (insn instanceof FieldInsnNode access && (access.owner.startsWith("com/google/gson/") || access.owner.startsWith("com/fasterxml/jackson/"))) reflectionHeavy = true;
            }
        }
        for (var field : node.fields) if (field.desc.startsWith("Lcom/google/gson/") || field.desc.startsWith("Lcom/fasterxml/jackson/")) reflectionHeavy = true;
        if (node.visibleAnnotations != null) for (var annotation : node.visibleAnnotations)
            if (annotation.desc.startsWith("Lcom/google/gson/") || annotation.desc.startsWith("Lcom/fasterxml/")) reflectionHeavy = true;
    }
    private static List<String> stringsOf(MethodNode method) {
        var result = new ArrayList<String>();
        for (var insn : method.instructions) {
            if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof String text) result.add(text);
            if (insn instanceof InvokeDynamicInsnNode dynamic && dynamic.bsm.getOwner().equals("java/lang/invoke/StringConcatFactory")
                && dynamic.bsmArgs.length > 0 && dynamic.bsmArgs[0] instanceof String recipe) result.add(recipe);
        }
        return result.stream().map(s -> s.replace("\u0001", "").replace("\u0002", "")).toList();
    }

    // ---------------------------------------------------------------- locals

    private int countLocals() {
        int total = 0;
        for (var node : nodes.values()) {
            var copy = new ClassNode(); node.accept(copy);
            total += LocalNames.rename(copy);
        }
        return total;
    }

    // ---------------------------------------------------------------- validation

    /** The plan is run through the rename pass once; whatever it rejects is dropped, never forced. */
    private Result validate(Map<String,byte[]> classes, Result plan) {
        if (plan.isEmpty()) return plan;
        try { new ClassRenamer().rename(classes, plan.classes(), plan.members(), false, plan.locals()); return plan; }
        catch (IllegalArgumentException | IllegalStateException rejected) {
            var withoutMembers = new Result(plan.classes(), List.of(), entries.stream().filter(e -> e.kind().equals("class")).toList(), skipped, plan.locals(), plan.localVariables());
            try {
                new ClassRenamer().rename(classes, withoutMembers.classes(), List.of(), false, withoutMembers.locals());
                var counts = new TreeMap<>(skipped); counts.merge("validation-dropped-members", plan.members().size(), Integer::sum);
                return new Result(withoutMembers.classes(), List.of(), withoutMembers.entries(), counts, withoutMembers.locals(), withoutMembers.localVariables());
            } catch (IllegalArgumentException | IllegalStateException again) {
                var counts = new TreeMap<>(skipped); counts.merge("validation-failed", 1, Integer::sum);
                return new Result(Map.of(), List.of(), List.of(), counts, false, 0);
            }
        }
    }

    private static String simple(String internal) { return internal.substring(internal.lastIndexOf('/') + 1); }
    private static String packageName(String name) { return name.substring(0, name.lastIndexOf('/') + 1); }
    private static boolean special(String name) { return name.equals("module-info") || name.endsWith("/package-info") || name.equals("package-info"); }
    private static boolean validClassName(String name) { return name != null && name.matches("[A-Z][A-Za-z0-9]{2,39}") && !RESERVED.contains(name); }
}
