package dev.classrevive.bytecode;

import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import java.io.*;
import java.util.*;
import static org.objectweb.asm.Opcodes.*;

/**
 * Phase 2BZ: static obfuscation profile of a class set. ASM only parses the class bytes; no class is defined, loaded,
 * initialized or run, and no bootstrap or decryption routine is evaluated. Every count is a heuristic indicator
 * ({@link ObfuscatorClassifier} turns them into suggestions, never proof).
 *
 * <p>String literal contents are never reported. The only literal values that reach a report are cipher algorithm
 * names from a fixed list ({@link #ALGORITHMS}) and fixed marker words.
 */
public final class ObfuscationProfile {
    /** Techniques, in report order. */
    public enum Technique {
        STRING_ENCRYPTION("string encryption"),
        CONSTANT_ENCRYPTION("constant encryption (int/long values decrypted at run time)"),
        HIDDEN_CALLS("hidden calls (invokedynamic with a non-JDK bootstrap)"),
        SHORT_NAMES("short or meaningless names"),
        NON_IDENTIFIER_NAMES("names that are no Java identifiers"),
        CASE_COLLISIONS("class names differing only in letter case"),
        STRIPPED_ATTRIBUTES("stripped attributes (InnerClasses, SourceFile, LineNumberTable)"),
        NATIVE_LAYER("native code layer (native methods with obfuscated names)"),
        CONTROL_FLOW("control-flow obfuscation"),
        EXCEPTION_OBFUSCATION("exception obfuscation (catch blocks rethrowing through an identity helper)");
        private final String label;
        Technique(String label) { this.label = label; }
        public String label() { return label; }
    }
    /** {@code classes}: classes showing the technique; {@code occurrences}: sites, methods or entries counted. */
    public record Count(int classes, int occurrences, List<String> samples) {
        public Count { samples = List.copyOf(samples); }
    }

    public static final int SAMPLES = 10;
    static final Set<String> ALGORITHMS = Set.of("DES", "DESEDE", "TRIPLEDES", "AES", "BLOWFISH", "RC2", "RC4", "ARCFOUR", "CHACHA20",
        "PBEWITHMD5ANDDES", "PBEWITHSHA1ANDDESEDE", "PBKDF2WITHHMACSHA1", "PBKDF2WITHHMACSHA256");
    /** Bootstraps of language runtimes (Groovy, Scala, Kotlin, Clojure, JRuby): not hidden calls, even when not owned by the JDK. */
    static final List<String> RUNTIME_BOOTSTRAPS = List.of("org/codehaus/groovy/", "org/apache/groovy/", "groovy/", "scala/", "kotlin/",
        "clojure/", "org/jruby/", "jdk/", "java/", "javax/", "sun/", "com/sun/");

    private final int classes, unparsable;
    private final Map<Technique,Count> techniques;
    private final Map<String,Integer> metrics;

    public ObfuscationProfile(int classes, int unparsable, Map<Technique,Count> techniques, Map<String,Integer> metrics) {
        this.classes = classes; this.unparsable = unparsable;
        var copy = new EnumMap<Technique,Count>(Technique.class); copy.putAll(techniques);
        this.techniques = Collections.unmodifiableMap(copy);
        this.metrics = Collections.unmodifiableMap(new TreeMap<>(metrics));
    }
    public int classes() { return classes; }
    public int unparsable() { return unparsable; }
    /** Detected techniques only (each passed its documented threshold). */
    public Map<Technique,Count> techniques() { return techniques; }
    /** Raw indicator counts, detected or not (e.g. {@code cipher.DES}, {@code indy.custom_sites}). */
    public Map<String,Integer> metrics() { return metrics; }
    public int metric(String key) { return metrics.getOrDefault(key, 0); }
    public boolean has(Technique technique) { return techniques.containsKey(technique); }

    // ---------------------------------------------------------------- serialization (worker to parent)

    public void write(DataOutputStream out) throws IOException {
        out.writeInt(1); out.writeInt(classes); out.writeInt(unparsable);
        out.writeInt(techniques.size());
        for (var entry : techniques.entrySet()) {
            out.writeUTF(entry.getKey().name()); out.writeInt(entry.getValue().classes()); out.writeInt(entry.getValue().occurrences());
            out.writeInt(entry.getValue().samples().size());
            for (String sample : entry.getValue().samples()) out.writeUTF(sample);
        }
        out.writeInt(metrics.size());
        for (var entry : metrics.entrySet()) { out.writeUTF(entry.getKey()); out.writeInt(entry.getValue()); }
    }
    /** Strict, bounded reader of {@link #write}. */
    public static ObfuscationProfile read(DataInputStream in) throws IOException {
        if (in.readInt() != 1) throw new IOException("Unknown obfuscation profile schema");
        int classes = in.readInt(), unparsable = in.readInt(), count = in.readInt();
        if (classes < 0 || unparsable < 0 || count < 0 || count > Technique.values().length) throw new IOException("Invalid obfuscation profile");
        var techniques = new EnumMap<Technique,Count>(Technique.class);
        for (int i = 0; i < count; i++) {
            Technique technique;
            try { technique = Technique.valueOf(in.readUTF()); } catch (IllegalArgumentException error) { throw new IOException("Unknown technique"); }
            int techniqueClasses = in.readInt(), occurrences = in.readInt(), samples = in.readInt();
            if (techniqueClasses < 0 || occurrences < 0 || samples < 0 || samples > SAMPLES) throw new IOException("Invalid technique counts");
            var list = new ArrayList<String>();
            for (int s = 0; s < samples; s++) list.add(in.readUTF());
            if (techniques.put(technique, new Count(techniqueClasses, occurrences, list)) != null) throw new IOException("Duplicate technique");
        }
        int metricCount = in.readInt();
        if (metricCount < 0 || metricCount > 4096) throw new IOException("Invalid metric count");
        var metrics = new TreeMap<String,Integer>();
        for (int i = 0; i < metricCount; i++) {
            String key = in.readUTF(); int value = in.readInt();
            if (value < 0 || metrics.put(key, value) != null) throw new IOException("Invalid metric");
        }
        return new ObfuscationProfile(classes, unparsable, techniques, metrics);
    }

    // ---------------------------------------------------------------- builder

    /** Accumulates classes and resource names; {@link #build} applies the per-technique thresholds. */
    public static final class Builder {
        private int classes, unparsable;
        private final Map<String,Integer> metrics = new TreeMap<>();
        private final Map<Technique,Set<String>> classSets = new EnumMap<>(Technique.class);
        private final Map<Technique,Integer> occurrences = new EnumMap<>(Technique.class);
        private final Map<Technique,LinkedHashSet<String>> samples = new EnumMap<>(Technique.class);
        private final Set<String> names = new HashSet<>();
        /** Static String-returning methods shaped like decryptors: key {@code owner.name desc}. */
        private final Map<String,String> decryptors = new HashMap<>();
        /** Call sites to static String-returning methods: key to calling classes (with multiplicity); constant-argument ones counted apart. */
        private final Map<String,List<String>> stringCalls = new HashMap<>();
        private final Map<String,Integer> constantCalls = new HashMap<>();
        private int callBudget = 1_000_000;
        /** Classes whose {@code <clinit>} decrypts strings (crypto or XOR feeding a static String/String[] field). */
        private final Map<String,String> clinitDecryption = new LinkedHashMap<>();
        /** Calls to static String-returning methods made from a bootstrap or call-site handler ({@code (Lookup,...)}): forwarding, not sites. */
        private final Map<String,Integer> forwardingCalls = new HashMap<>();
        /** Invokedynamic sites with a non-JDK, non-runtime bootstrap: {class, bootstrap owner, sample, bootstrap key, site descriptor}. */
        private final List<String[]> indySites = new ArrayList<>();
        /** Phase 2CB: bootstraps (key {@code owner.name desc}) that reach a value decoder of their own class: the decoders' descriptors. */
        private final Map<String,Set<String>> bootstrapDecoders = new HashMap<>();
        /** Phase 2CB: classes whose {@code <clinit>} only registers natives and calls a native of the class (material comes from native code). */
        private final Set<String> nativeClinits = new LinkedHashSet<>();
        private final Map<String,Integer> shortClasses = new LinkedHashMap<>();
        private final Map<String,int[]> controlFlow = new LinkedHashMap<>();
        /** Phase 2CA: static no-argument accessors of a static field ({@code getstatic; return}), key {@code owner.name desc}. */
        private final Set<String> flagAccessors = new HashSet<>();
        /** Accessor calls stored in a local that is only ever tested (ifnull/ifnonnull/ifeq/ifne): key to {class, where, tests}. */
        private final Map<String,List<String[]>> flagSites = new HashMap<>();
        /** Static identity helpers {@code (LX;)LX;} returning their argument, key {@code owner.name desc}. */
        private final Set<String> identityHelpers = new HashSet<>();
        /** Exception handlers that start by passing the caught exception to a static (LX;)LX; method and throwing the result. */
        private final Map<String,List<String[]>> rethrowSites = new HashMap<>();

        public void addResource(String name, long size) {
            String lower = name.toLowerCase(Locale.ROOT);
            if (lower.endsWith(".dll") || lower.endsWith(".so") || lower.endsWith(".dylib") || lower.endsWith(".jnilib")) {
                metric("native.libraries", 1);
                sample(Technique.NATIVE_LAYER, "library " + clean(name) + (size >= 0 ? " (" + size + " bytes)" : ""));
            }
        }

        /** Parses one class (debug attributes included). A class ASM cannot parse is counted, not guessed about. */
        public void addClass(byte[] bytes) {
            var node = new ClassNode();
            try { new ClassReader(bytes).accept(node, ClassReader.SKIP_FRAMES); }
            catch (RuntimeException unreadable) { unparsable++; return; }
            classes++;
            names.add(node.name);
            String cls = node.name;
            names(node, CompilerFingerprint.kotlin(node));
            attributes(node);
            for (var method : node.methods) {
                if ((method.access & ACC_NATIVE) != 0) {
                    metric("native.methods", 1);
                    if (!javaIdentifier(method.name) || hexLike(method.name)) {
                        metric("native.obfuscated_names", 1);
                        hit(Technique.NATIVE_LAYER, cls, "native " + clean(cls) + "#" + clean(method.name) + clean(method.desc));
                    }
                }
                if (method.instructions.size() == 0) continue;
                code(node, method);
            }
            bootstraps(node);
            if (nativeOnlyClinit(node)) nativeClinits.add(cls);
            String simple = cls.substring(cls.lastIndexOf('/') + 1);
            if (simple.contains("ALLATORI") || cls.contains("ALLATORI")) metric("marker.allatori", 1);
            if (cls.endsWith("native0/Loader") || simple.startsWith("JNIC") || cls.startsWith("jnic/")) {
                metric("marker.native_obfuscator", 1);
                hit(Technique.NATIVE_LAYER, cls, "loader " + clean(cls));
            }
            if (references(node, "com/preemptive/")) metric("marker.dasho", 1);
            skidfuscator(node);
            aidsfuscator(node);
        }

        /**
         * Phase 2CZ: Aidsfuscator's generated classes and shapes: a salt dispatcher (an FNV-1a {@code (String)J} name hash
         * and a {@code (Object, int)int} retriever), a reference bootstrap returning a {@code MutableCallSite} with an
         * {@code (int, int)String} decoder, a hash integrity class (a static SHA-256 hex hash, a {@code (Class, int)int}
         * retriever and a {@code "/<name>.bin"} resource), string decryptors that read their caller from a stack trace,
         * and {@code <clinit>} statements {@code f = X.m(MethodHandles.lookup().lookupClass(), <int>)}.
         */
        private void aidsfuscator(ClassNode node) {
            boolean fnv = false, saltRetriever = false, bootstrap = false, decoder = false, sha = false, hashRetriever = false, resource = false;
            for (var method : node.methods) {
                boolean isStatic = (method.access & ACC_STATIC) != 0;
                boolean offset = false, prime = false, newThrowable = false, trace = false, strings = false, cache = false, mutable = false;
                for (var insn : method.instructions) {
                    if (insn instanceof LdcInsnNode ldc) {
                        if (ldc.cst instanceof Long l && l == 0xcbf29ce484222325L) offset = true;
                        if (ldc.cst instanceof Long l && l == 0x100000001b3L) prime = true;
                        if ("SHA-256".equals(ldc.cst) && isStatic && method.desc.equals("([B)Ljava/lang/String;")) sha = true;
                        if (ldc.cst instanceof String text && text.startsWith("/") && text.endsWith(".bin") && method.name.equals("<clinit>")) resource = true;
                    }
                    if (insn instanceof TypeInsnNode type && type.getOpcode() == NEW) {
                        if (type.desc.equals("java/lang/Throwable")) newThrowable = true;
                        if (type.desc.equals("java/lang/invoke/MutableCallSite")) mutable = true;
                    }
                    if (insn instanceof MethodInsnNode call && call.owner.equals("java/lang/Throwable") && call.name.equals("getStackTrace")) trace = true;
                    if (insn instanceof FieldInsnNode field && field.owner.equals(node.name) && field.getOpcode() == GETSTATIC) {
                        if (field.desc.equals("[Ljava/lang/String;")) strings = true;
                        if (field.desc.equals("[Ljava/lang/Object;")) cache = true;
                    }
                    // f = X.m(MethodHandles.lookup().lookupClass(), <int>)
                    if (method.name.equals("<clinit>") && insn instanceof MethodInsnNode lookupClass && lookupClass.name.equals("lookupClass")
                        && previous(lookupClass) instanceof MethodInsnNode lookup && lookup.owner.equals("java/lang/invoke/MethodHandles")
                        && next(next(lookupClass)) instanceof MethodInsnNode retriever && retriever.getOpcode() == INVOKESTATIC
                        && (retriever.desc.equals("(Ljava/lang/Object;I)I") || retriever.desc.equals("(Ljava/lang/Class;I)I"))
                        && next(retriever) instanceof FieldInsnNode put && put.getOpcode() == PUTSTATIC && put.desc.equals("I"))
                        metric("aids.lookup_class_retrievers", 1);
                }
                if (isStatic && method.desc.equals("(Ljava/lang/String;)J") && offset && prime) fnv = true;
                if (isStatic && method.desc.equals("(Ljava/lang/Object;I)I")) saltRetriever = true;
                if (isStatic && method.desc.equals("(Ljava/lang/Class;I)I")) hashRetriever = true;
                if (isStatic && method.desc.endsWith(")Ljava/lang/invoke/CallSite;") && mutable) bootstrap = true;
                if (isStatic && method.desc.equals("(II)Ljava/lang/String;")) decoder = true;
                if (isStatic && method.desc.endsWith(")Ljava/lang/String;") && newThrowable && trace && strings && cache) {
                    metric("aids.stacktrace_string_decryptors", 1);
                    hit(Technique.STRING_ENCRYPTION, node.name, "string decryptor keyed by its caller's stack trace in " + clean(node.name) + "#" + clean(method.name));
                }
            }
            if (fnv && saltRetriever) metric("aids.salt_dispatcher", 1);
            if (bootstrap && decoder) metric("aids.reference_bootstrap", 1);
            if (sha && hashRetriever && resource) metric("aids.hash_integrity", 1);
        }

        /**
         * Phase 2CD: Skidfuscator's fingerprints. Its flow obfuscation keeps a per-block seed in a local that every edge
         * updates as {@code seed = C ^ seed} (constant first, which javac's {@code x ^= C} never emits), dispatches through
         * {@code switch (hash(seed))} with a case that jumps back to its own seed load, and turns gotos into a
         * {@code throw new X()} caught by a handler that pops it. Its runtime and markers: {@code sdk/SDK}, a
         * {@code nothing_to_see_here} String[] field, a static int seeded by {@code new Random(long).nextInt()} in
         * {@code <clinit>}, and string decryptors keyed by {@code Integer.toString(seed).getBytes()}.
         */
        private void skidfuscator(ClassNode node) {
            String cls = node.name;
            if (cls.equals("sdk/SDK")) metric("marker.skidfuscator_sdk", 1);
            for (var field : node.fields)
                if (field.name.startsWith("nothing_to_see_here") && field.desc.equals("[Ljava/lang/String;") && (field.access & ACC_STATIC) != 0)
                    metric("marker.ahegao", 1);
            int sdkCalls = 0;
            for (var method : node.methods) {
                var insns = method.instructions.toArray();
                int seedUpdates = 0;
                for (int i = 0; i < insns.length; i++) {
                    var insn = insns[i];
                    if (insn instanceof MethodInsnNode call && call.owner.equals("sdk/SDK")) sdkCalls++;
                    // seed = C ^ seed
                    if (insn.getOpcode() == IXOR && previous(insn) instanceof VarInsnNode load && load.getOpcode() == ILOAD
                        && constant(previous(load)) && next(insn) instanceof VarInsnNode store && store.getOpcode() == ISTORE && store.var == load.var)
                        seedUpdates++;
                    // switch (hash(seed)) with a case jumping back to its own seed load
                    if ((insn instanceof LookupSwitchInsnNode || insn instanceof TableSwitchInsnNode) && previous(insn) instanceof MethodInsnNode hash
                        && hash.getOpcode() == INVOKESTATIC && hash.desc.endsWith(")I") && previous(hash) instanceof VarInsnNode seed && seed.getOpcode() == ILOAD) {
                        var labels = insn instanceof LookupSwitchInsnNode lookup ? lookup.labels : ((TableSwitchInsnNode) insn).labels;
                        boolean selfLoop = false;
                        for (var label : labels)
                            for (AbstractInsnNode at = label; at != null && !selfLoop; at = at.getNext()) {
                                if (at == seed) selfLoop = true;
                                if (at.getOpcode() >= 0) break;
                            }
                        if (selfLoop) {
                            metric("skid.self_loop_hash_switches", 1);
                            hit(Technique.CONTROL_FLOW, cls, "switch on a hash of the block seed jumping back to itself in "
                                + clean(cls) + "#" + clean(method.name) + clean(method.desc));
                        }
                    }
                    // Integer.parseInt("digits") folded into a seed
                    if (insn instanceof MethodInsnNode parse && parse.owner.equals("java/lang/Integer") && parse.name.equals("parseInt")
                        && parse.desc.equals("(Ljava/lang/String;)I") && previous(parse) instanceof LdcInsnNode digits
                        && digits.cst instanceof String text && text.matches("-?[0-9]{1,10}") && next(parse) != null && next(parse).getOpcode() == IXOR)
                        metric("skid.parseint_seeds", 1);
                    // new Random(long).nextInt() into a static int of the class
                    if (method.name.equals("<clinit>") && insn instanceof MethodInsnNode nextInt && nextInt.owner.equals("java/util/Random")
                        && nextInt.name.equals("nextInt") && nextInt.desc.equals("()I") && previous(nextInt) instanceof MethodInsnNode init
                        && init.name.equals("<init>") && init.desc.equals("(J)V") && constant(previous(init))) {
                        for (int j = i + 1; j < Math.min(insns.length, i + 8); j++)
                            if (insns[j] instanceof FieldInsnNode put && put.getOpcode() == PUTSTATIC && put.owner.equals(cls) && put.desc.equals("I")) {
                                metric("skid.random_seed_fields", 1); break;
                            }
                    }
                    // throw new X() caught by a handler that only pops it: a goto in disguise
                    if (insn.getOpcode() == ATHROW && previous(insn) instanceof MethodInsnNode ctor && ctor.name.equals("<init>") && ctor.desc.equals("()V")) {
                        int index = method.instructions.indexOf(insn);
                        for (var block : method.tryCatchBlocks) {
                            if (index < method.instructions.indexOf(block.start) || index >= method.instructions.indexOf(block.end)) continue;
                            // Skidfuscator's handler first re-derives the seed, then pops the exception and jumps on.
                            if (block.type != null && block.type.equals(ctor.owner) && previous(ctor) != null && previous(ctor).getOpcode() == DUP)
                                metric("skid.exception_gotos", 1);
                            break;
                        }
                    }
                    if (insn instanceof MethodInsnNode call && call.getOpcode() == INVOKESTATIC
                        && (call.desc.equals("([B[BI)Ljava/lang/String;") || call.desc.equals("([BI)Ljava/lang/String;")))
                        skidStringCalls.computeIfAbsent(call.owner + "." + call.name + call.desc, k -> new ArrayList<>()).add(cls);
                }
                if (seedUpdates >= 3) metric("skid.seed_methods", 1);
                // Decryptor: static (byte[], [byte[],] int)String keyed by Integer.toString(key).getBytes()
                if ((method.access & ACC_STATIC) != 0 && (method.desc.equals("([B[BI)Ljava/lang/String;") || method.desc.equals("([BI)Ljava/lang/String;"))) {
                    boolean toString = false, getBytes = false;
                    for (var insn : insns) if (insn instanceof MethodInsnNode call) {
                        if (call.owner.equals("java/lang/Integer") && call.name.equals("toString") && call.desc.equals("(I)Ljava/lang/String;")) toString = true;
                        if (call.owner.equals("java/lang/String") && call.name.equals("getBytes")) getBytes = true;
                    }
                    if (toString && getBytes) { metric("skid.seed_string_decryptors", 1); skidDecryptors.add(cls + "." + method.name + method.desc); }
                }
            }
            if (sdkCalls > 0) { metric("skid.sdk_calls", sdkCalls); metric("skid.sdk_calling_classes", 1); }
        }
        /** Phase 2CD: Skidfuscator string decryptors (key {@code owner.name desc}) and the classes calling them. */
        private final Set<String> skidDecryptors = new HashSet<>();
        private final Map<String,List<String>> skidStringCalls = new HashMap<>();

        /** Phase 2DC: in a Kotlin class (kotlin.Metadata) Java keywords such as {@code int} or {@code default} are legal member names. */
        private void names(ClassNode node, boolean kotlin) {
            String cls = node.name;
            String simple = cls.substring(cls.lastIndexOf('/') + 1);
            String own = simple.substring(simple.lastIndexOf('$') + 1);
            boolean info = simple.equals("package-info") || simple.equals("module-info");
            for (String segment : cls.split("[/$]")) {
                // Local classes are named Outer$1Local: the digits after $ are the compiler's, the rest must be an identifier.
                String local = segment.replaceFirst("^[0-9]+", "");
                if (info || segment.isEmpty() || javaIdentifier(segment) || local.isEmpty() || cls.indexOf('$') >= 0 && javaIdentifier(local)
                    && !cls.substring(0, cls.lastIndexOf('/') + 1).contains(segment + "/")) continue;
                metric("names.non_identifier_classes", 1);
                hit(Technique.NON_IDENTIFIER_NAMES, cls, "class " + clean(cls));
                break;
            }
            int members = 0, shortMembers = 0;
            for (var method : node.methods) {
                if (method.name.startsWith("<")) continue;
                members++; if (method.name.length() <= 2) shortMembers++;
                if (!javaIdentifier(method.name) && !kotlinMangled(method.name) && !(kotlin && KEYWORDS.contains(method.name))) {
                    metric("names.non_identifier_methods", 1);
                    hit(Technique.NON_IDENTIFIER_NAMES, cls, "method " + clean(cls) + "#" + clean(method.name) + clean(method.desc));
                }
                if (method.name.contains("ALLATORI")) metric("marker.allatori", 1);
            }
            for (var field : node.fields) {
                members++; if (field.name.length() <= 2) shortMembers++;
                if (!javaIdentifier(field.name) && !kotlinMangled(field.name) && !(kotlin && KEYWORDS.contains(field.name))) {
                    metric("names.non_identifier_fields", 1);
                    hit(Technique.NON_IDENTIFIER_NAMES, cls, "field " + clean(cls) + "#" + clean(field.name));
                }
                if (field.name.contains("ALLATORI")) metric("marker.allatori", 1);
            }
            boolean anonymous = !own.isEmpty() && own.chars().allMatch(Character::isDigit);
            boolean shortClass = !info && !anonymous && own.length() <= 2;
            boolean shortMembersMostly = members >= 3 && shortMembers * 10 >= members * 6;
            if (shortClass || shortMembersMostly) {
                shortClasses.put(cls, shortMembers);
                metric(shortClass ? "names.short_classes" : "names.short_member_classes", 1);
            }
            String pkg = cls.lastIndexOf('/') < 0 ? "" : cls.substring(0, cls.lastIndexOf('/'));
            if (!pkg.isEmpty() && Arrays.stream(pkg.split("/")).allMatch(s -> s.length() <= 2)) metric("names.short_packages", 1);
        }

        private void attributes(ClassNode node) {
            String cls = node.name;
            int hits = 0;
            if (node.sourceFile == null) { metric("debug.no_sourcefile", 1); hits++; }
            else if (node.sourceFile.equals("SourceFile") || node.sourceFile.startsWith("r8-map-id-")) metric("debug.sourcefile_renamed", 1);
            boolean code = false, lines = false, locals = false;
            for (var method : node.methods) {
                if (method.instructions.size() == 0) continue;
                code = true;
                if (method.localVariables != null && !method.localVariables.isEmpty()) locals = true;
                for (var insn : method.instructions) if (insn instanceof LineNumberNode) { lines = true; break; }
            }
            if (code && !lines) { metric("debug.no_line_numbers", 1); hits++; }
            if (code && !locals) metric("debug.no_local_variables", 1);
            String simple = cls.substring(cls.lastIndexOf('/') + 1);
            int dollar = simple.indexOf('$');
            if (dollar > 0 && dollar < simple.length() - 1 && node.innerClasses.isEmpty() && node.nestHostClass == null) {
                metric("debug.inner_classes_stripped", 1);
                sample(Technique.STRIPPED_ATTRIBUTES, "no InnerClasses: " + clean(cls));
                hits++;
            }
            if (hits > 0) {
                count(Technique.STRIPPED_ATTRIBUTES, cls, hits);
                sample(Technique.STRIPPED_ATTRIBUTES, (node.sourceFile == null ? "no SourceFile" : "no LineNumberTable") + ": " + clean(cls));
            }
        }

        private void code(ClassNode node, MethodNode method) {
            String cls = node.name, where = clean(cls) + "#" + clean(method.name) + clean(method.desc);
            boolean clinit = method.name.equals("<clinit>");
            int real = 0, gotos = 0, xor = 0, chains = 0, constantConditions = 0, flattening = 0;
            boolean crypto = false, stringBuilt = false, stringStore = false, stackTrace = false, keyed = false;
            var insns = method.instructions.toArray();
            Set<Integer> longSlots = longParameterSlots(method);
            Set<LabelNode> targets = jumpTargets(method);
            Set<AbstractInsnNode> exits = structuredExits(insns);
            for (int i = 0; i < insns.length; i++) {
                var insn = insns[i];
                if (insn.getOpcode() < 0) continue;
                real++;
                int op = insn.getOpcode();
                if (op == IXOR || op == LXOR) xor++;
                if (op == GOTO) {
                    if (!exits.contains(insn)) gotos++;
                    var target = next(((JumpInsnNode) insn).label);
                    if (target != null && target.getOpcode() == GOTO) chains++;
                }
                if (insn instanceof JumpInsnNode jump && op != GOTO && op != JSR) {
                    var before = previous(insn);
                    // Phase 2DC: a constant that reaches the test through a merge label is one arm of a value-producing branch
                    // (Kotlin's {@code cond ? 1 : 0} before ifeq), not an opaque predicate.
                    if (op >= IFEQ && op <= IFLE && constant(before) && straight(before, insn, targets)) constantConditions++;
                    else if (op >= IF_ICMPEQ && op <= IF_ICMPLE && constant(before) && constant(previous(before))
                        && straight(previous(before), insn, targets)) constantConditions++;
                }
                if ((insn instanceof TableSwitchInsnNode || insn instanceof LookupSwitchInsnNode) && dispatcher(method, insns, i)) flattening++;
                if (op == LLOAD && longSlots.contains(((VarInsnNode) insn).var)) {
                    var a = next(insn); var b = a == null ? null : next(a);
                    if (a instanceof LdcInsnNode ldc && ldc.cst instanceof Long && b != null && b.getOpcode() == LXOR) keyed = true;
                }
                if (insn instanceof FieldInsnNode field && op == PUTSTATIC && (field.desc.equals("Ljava/lang/String;") || field.desc.equals("[Ljava/lang/String;")))
                    stringStore = true;
                if (insn instanceof InvokeDynamicInsnNode indy) indy(cls, where, indy);
                if (insn instanceof MethodInsnNode call) {
                    if (cryptoCall(call)) {
                        crypto = true;
                        var before = previous(insn);
                        if (before instanceof LdcInsnNode ldc && ldc.cst instanceof String text) {
                            String algorithm = text.split("/", 2)[0].toUpperCase(Locale.ROOT);
                            if (ALGORITHMS.contains(algorithm)) metric("cipher." + algorithm, 1);
                        }
                    }
                    if (call.owner.equals("java/lang/String") && (call.name.equals("<init>") || call.name.equals("toCharArray")
                        || call.name.equals("charAt") || call.name.equals("intern") || call.name.equals("valueOf"))) stringBuilt = true;
                    if (call.name.equals("getStackTrace") && (call.owner.equals("java/lang/Thread") || call.owner.equals("java/lang/Throwable")))
                        stackTrace = true;
                    if ((call.owner.equals("java/lang/System") || call.owner.equals("java/lang/Runtime"))
                        && (call.name.equals("load") || call.name.equals("loadLibrary"))) metric("native.load_calls", 1);
                    if (op == INVOKESTATIC && call.desc.endsWith(")Ljava/lang/String;") && callBudget-- > 0) {
                        String key = call.owner + "." + call.name + call.desc;
                        if (callSiteHandler(method.desc)) { forwardingCalls.merge(key, 1, Integer::sum); continue; }
                        stringCalls.computeIfAbsent(key, k -> new ArrayList<>()).add(cls);
                        if (constant(previous(insn))) constantCalls.merge(key, 1, Integer::sum);
                    }
                }
                if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof String text && text.contains("ALLATORI")) metric("marker.allatori", 1);
            }
            if (keyed) metric("zkm.long_key_methods", 1);
            accessorsAndHelpers(cls, method, insns);
            flagLocals(cls, where, insns);
            rethrows(cls, where, method);
            if (clinit && (crypto || xor > 0) && stringStore && (stringBuilt || crypto)) {
                clinitDecryption.putIfAbsent(cls, where);
                metric(crypto ? "strings.clinit_crypto" : "strings.clinit_xor", 1);
            }
            boolean isStatic = (method.access & ACC_STATIC) != 0;
            if (isStatic && !clinit && method.desc.endsWith(")Ljava/lang/String;") && decryptorParameters(method.desc)
                && (xor > 0 || crypto) && (stringBuilt || readsStringArray(insns) || crypto)) {
                String key = cls + "." + method.name + method.desc;
                decryptors.put(key, where);
                String shape = method.desc.substring(0, method.desc.indexOf(')') + 1);
                metric("strings.decryptor" + (Set.of("(IJ)", "(JI)", "(II)", "(Ljava/lang/String;)", "(Ljava/lang/Object;)", "(J)", "(I)").contains(shape) ? shape : "(other)"), 1);
                if (stackTrace) metric("marker.stringer_stacktrace", 1);
            }
            int flagEntry = flagAtEntry(cls, insns) ? 1 : 0;
            boolean gotoHeavy = real >= 30 && gotos * 4 >= real;
            var cf = controlFlow.computeIfAbsent(cls, k -> new int[6]);
            cf[0] += chains; cf[1] += constantConditions; cf[2] += flattening; cf[3] += gotoHeavy ? 1 : 0; cf[4] += flagEntry; cf[5]++;
            if (constantConditions + flattening > 0 || gotoHeavy) sample(Technique.CONTROL_FLOW, where);
        }

        private void accessorsAndHelpers(String cls, MethodNode method, AbstractInsnNode[] insns) {
            if ((method.access & ACC_STATIC) == 0) return;
            var real = Arrays.stream(insns).filter(insn -> insn.getOpcode() >= 0).toList();
            if (real.size() != 2) return;
            String key = cls + "." + method.name + method.desc;
            if (flagDesc(method.desc) && real.get(0).getOpcode() == GETSTATIC && (real.get(1).getOpcode() == ARETURN || real.get(1).getOpcode() == IRETURN))
                flagAccessors.add(key);
            if (identityDesc(method.desc) && real.get(0) instanceof VarInsnNode load && load.getOpcode() == ALOAD && load.var == 0
                && real.get(1).getOpcode() == ARETURN) identityHelpers.add(key);
        }

        /** {@code invokestatic ()[X / ()Z / ()I; astore/istore n} where every other access to n is a load feeding a null or zero test. */
        private void flagLocals(String cls, String where, AbstractInsnNode[] insns) {
            for (var insn : insns) {
                if (!(insn instanceof MethodInsnNode call) || call.getOpcode() != INVOKESTATIC || !flagDesc(call.desc)) continue;
                boolean array = call.desc.startsWith("()[");
                if (!(next(call) instanceof VarInsnNode store) || store.getOpcode() != (array ? ASTORE : ISTORE)) continue;
                int tests = 0; boolean other = false;
                for (var use : insns) {
                    if (use == store || !(use instanceof VarInsnNode var) || var.var != store.var) continue;
                    if (var.getOpcode() == (array ? ALOAD : ILOAD)) {
                        var test = next(var); int op = test == null ? -1 : test.getOpcode();
                        if (array ? op == IFNULL || op == IFNONNULL : op == IFEQ || op == IFNE) tests++; else other = true;
                    } else other = true;
                }
                if (tests > 0 && !other)
                    flagSites.computeIfAbsent(call.owner + "." + call.name + call.desc, k -> new ArrayList<>()).add(new String[] {cls, where, String.valueOf(tests)});
            }
        }

        /** Handlers whose first instruction is {@code invokestatic (LX;)LX;} followed by athrow (javac stores the exception first). */
        private void rethrows(String cls, String where, MethodNode method) {
            var seen = new HashSet<LabelNode>();
            for (var block : method.tryCatchBlocks) {
                if (!seen.add(block.handler)) continue;
                var first = next(block.handler);
                if (first instanceof MethodInsnNode call && call.getOpcode() == INVOKESTATIC && identityDesc(call.desc)
                    && next(call) != null && next(call).getOpcode() == ATHROW)
                    rethrowSites.computeIfAbsent(call.owner + "." + call.name + call.desc, k -> new ArrayList<>()).add(new String[] {cls, where});
            }
        }

        private void indy(String cls, String where, InvokeDynamicInsnNode site) {
            Handle bootstrap = site.bsm;
            for (String prefix : RUNTIME_BOOTSTRAPS) if (bootstrap.getOwner().startsWith(prefix)) return;
            indySites.add(new String[] {cls, bootstrap.getOwner(), where + " -> " + clean(bootstrap.getOwner()) + "." + clean(bootstrap.getName()) + bootstrap.getDesc(),
                bootstrap.getOwner() + "." + bootstrap.getName() + bootstrap.getDesc(), site.desc});
            String shape = bootstrap.getDesc().startsWith("(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;)")
                ? "lookup_string_methodtype" : bootstrap.getDesc().startsWith("(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;")
                ? "lookup_string_methodtype_extra" : "other";
            metric("indy.bootstrap_" + shape, 1);
        }

        /**
         * Phase 2CB: for each bootstrap-shaped method ({@code (Lookup, String, MethodType...)}), the value decoders of the same
         * class it reaches through static calls and {@code ldc} method handles (at most 3 steps). ZKM's bootstrap installs a
         * handler that passes the site's (int, long) key to a decoder, so the decryption sites are the invokedynamic sites, not
         * the handler's call. Nothing is evaluated: only the call graph inside the class is read.
         */
        private void bootstraps(ClassNode node) {
            var byKey = new HashMap<String,MethodNode>();
            for (var method : node.methods) if ((method.access & ACC_STATIC) != 0 && method.instructions.size() > 0) byKey.put(method.name + method.desc, method);
            for (var method : byKey.values()) {
                if (!method.desc.startsWith("(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;")) continue;
                var seen = new HashSet<String>(); var frontier = List.of(method.name + method.desc); var found = new TreeSet<String>();
                for (int depth = 0; depth < 3 && !frontier.isEmpty(); depth++) {
                    var nextFrontier = new ArrayList<String>();
                    for (String key : frontier) {
                        if (!seen.add(key)) continue;
                        for (var insn : byKey.get(key).instructions) {
                            String target = null;
                            if (insn instanceof MethodInsnNode call && call.getOpcode() == INVOKESTATIC && call.owner.equals(node.name)) target = call.name + call.desc;
                            if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof Handle handle && handle.getTag() == H_INVOKESTATIC && handle.getOwner().equals(node.name))
                                target = handle.getName() + handle.getDesc();
                            if (target == null || !byKey.containsKey(target)) continue;
                            if (decoder(byKey.get(target))) found.add(target.substring(target.indexOf('(')));
                            else nextFrontier.add(target);
                        }
                    }
                    frontier = nextFrontier;
                }
                if (!found.isEmpty()) bootstrapDecoders.put(node.name + "." + method.name + method.desc, found);
            }
        }

        /** {@code <clinit>} made of constants, static calls (at least one to a native method of the class) and return. */
        private static boolean nativeOnlyClinit(ClassNode node) {
            var natives = new HashSet<String>();
            for (var method : node.methods) if ((method.access & ACC_NATIVE) != 0) natives.add(method.name + method.desc);
            if (natives.isEmpty()) return false;
            for (var method : node.methods) {
                if (!method.name.equals("<clinit>")) continue;
                boolean callsNative = false;
                for (var insn : method.instructions) {
                    int op = insn.getOpcode();
                    if (op < 0 || op == RETURN || constant(insn) || insn instanceof LdcInsnNode ldc && ldc.cst instanceof Type) continue;
                    if (!(insn instanceof MethodInsnNode call) || op != INVOKESTATIC) return false;
                    if (call.owner.equals(node.name) && natives.contains(call.name + call.desc)) callsNative = true;
                }
                return callsNative;
            }
            return false;
        }

        public ObfuscationProfile build() {
            var counted = new EnumMap<Technique,Count>(Technique.class);
            // String encryption: decryptor-shaped methods called with constant arguments, or <clinit> decryption into static strings.
            int calls = 0, constantArguments = 0, keyedCalls = 0; var stringClasses = new LinkedHashSet<String>(clinitDecryption.keySet());
            var calledDecryptors = new TreeSet<String>();
            int forwarding = 0;
            for (var entry : forwardingCalls.entrySet()) if (decryptors.containsKey(entry.getKey())) forwarding += entry.getValue();
            for (var entry : stringCalls.entrySet()) {
                if (!decryptors.containsKey(entry.getKey())) continue;
                calls += entry.getValue().size(); stringClasses.addAll(entry.getValue()); calledDecryptors.add(entry.getKey());
                constantArguments += constantCalls.getOrDefault(entry.getKey(), 0);
                String parameters = entry.getKey().substring(entry.getKey().indexOf('(') + 1, entry.getKey().indexOf(')'));
                if (parameters.matches("[IJSBC]+")) keyedCalls += entry.getValue().size();
                stringClasses.add(entry.getKey().substring(0, entry.getKey().lastIndexOf('.', entry.getKey().indexOf('('))));
            }
            metric("strings.decryptor_calls", calls); metric("strings.decryptor_constant_calls", constantArguments);
            metric("strings.decryptor_forwarding_calls", forwarding);
            // Phase 2CB: invokedynamic sites whose bootstrap reaches a decoder with the site's own descriptor ((IJ)String, (IJ)I, (IJ)J).
            int stringSites = 0, intSites = 0, longSites = 0;
            var constantClasses = new LinkedHashSet<String>(); var decodingClasses = new HashSet<String>();
            var indySamples = new ArrayList<String>(); var constantSamples = new ArrayList<String>();
            for (var site : indySites) {
                var decoders = bootstrapDecoders.get(site[3]);
                if (decoders == null || !decoders.contains(site[4])) continue;
                decodingClasses.add(site[0]); decodingClasses.add(site[1]);
                String sample = site[2].substring(0, site[2].indexOf(" -> ")) + " -> " + clean(site[4]) + " through " + clean(site[1]);
                switch (site[4].substring(site[4].indexOf(')') + 1)) {
                    case "Ljava/lang/String;" -> { stringSites++; stringClasses.add(site[0]); if (indySamples.size() < 2) indySamples.add("invokedynamic " + sample); }
                    case "I" -> { intSites++; constantClasses.add(site[0]); if (constantSamples.size() < SAMPLES) constantSamples.add("int " + sample); }
                    case "J" -> { longSites++; constantClasses.add(site[0]); if (constantSamples.size() < SAMPLES) constantSamples.add("long " + sample); }
                    default -> { }
                }
            }
            metric("strings.indy_sites", stringSites); metric("constants.indy_int_sites", intSites); metric("constants.indy_long_sites", longSites);
            metric("indy.decoding_bootstraps", bootstrapDecoders.size());
            // Phase 2CB: a class that decodes values while its <clinit> only registers natives gets its keys and tables from native code.
            for (String key : decryptors.keySet()) decodingClasses.add(key.substring(0, key.lastIndexOf('.', key.indexOf('('))));
            for (String key : bootstrapDecoders.keySet()) decodingClasses.add(key.substring(0, key.lastIndexOf('.', key.indexOf('('))));
            var nativeKeys = nativeClinits.stream().filter(decodingClasses::contains).toList();
            metric("strings.native_key_classes", nativeKeys.size());
            // Phase 2CD: Skidfuscator's seed-keyed decryptors, called with byte arrays from generated methods.
            int skidSites = 0; var skidSamples = new ArrayList<String>();
            for (var entry : new TreeMap<>(skidStringCalls).entrySet()) {
                if (!skidDecryptors.contains(entry.getKey())) continue;
                skidSites += entry.getValue().size(); stringClasses.addAll(entry.getValue());
                if (skidSamples.size() < 3) skidSamples.add("seed-keyed decryptor " + clean(entry.getKey()) + " (" + entry.getValue().size() + " call sites)");
            }
            metric("strings.skid_sites", skidSites);
            // A (String)String helper such as Ascii.toLowerCase also XORs and builds strings; it counts only with literal arguments.
            if (skidSites >= 3 && constantArguments < 3 && keyedCalls < 3 && stringSites < 3 && clinitDecryption.isEmpty())
                counted.put(Technique.STRING_ENCRYPTION, new Count(stringClasses.size(), skidSites, skidSamples));
            else if (constantArguments >= 3 || keyedCalls >= 3 || stringSites >= 3 || !clinitDecryption.isEmpty()) {
                var list = new ArrayList<String>(indySamples);
                for (String key : calledDecryptors) if (list.size() < 5) list.add("decryptor " + decryptors.get(key) + " (" + stringCalls.get(key).size() + " direct call sites)");
                for (String cls : nativeKeys) if (list.size() < 7) list.add("keys supplied by the native layer: " + clean(cls) + " <clinit> only registers natives");
                for (String where : clinitDecryption.values()) if (list.size() < SAMPLES) list.add("decrypts in " + where);
                counted.put(Technique.STRING_ENCRYPTION, new Count(stringClasses.size(), stringSites + calls + clinitDecryption.size(), list));
            }
            if (intSites + longSites >= 3)
                counted.put(Technique.CONSTANT_ENCRYPTION, new Count(constantClasses.size(), intSites + longSites, constantSamples));
            // Hidden calls: invokedynamic with a bootstrap outside the JDK and known language runtimes.
            if (!indySites.isEmpty()) {
                var classSet = new LinkedHashSet<String>(); var list = new ArrayList<String>(); int inJar = 0;
                for (var site : indySites) {
                    classSet.add(site[0]); if (names.contains(site[1])) inJar++;
                    if (list.size() < SAMPLES && list.stream().noneMatch(s -> s.startsWith(site[2].substring(0, site[2].indexOf('#'))))) list.add(site[2]);
                }
                metric("indy.custom_sites", indySites.size()); metric("indy.bootstrap_in_jar", inJar);
                counted.put(Technique.HIDDEN_CALLS, new Count(classSet.size(), indySites.size(), list));
            }
            // Short names: at least 5 classes and 10% of the classes.
            if (shortClasses.size() >= 5 && shortClasses.size() * 10 >= classes) {
                var list = shortClasses.keySet().stream().limit(SAMPLES).map(ObfuscationProfile::clean).toList();
                counted.put(Technique.SHORT_NAMES, new Count(shortClasses.size(), shortClasses.size(), list));
            }
            // Case collisions.
            var byCase = new TreeMap<String,List<String>>();
            for (String name : names) byCase.computeIfAbsent(name.toLowerCase(Locale.ROOT), k -> new ArrayList<>()).add(name);
            var groups = byCase.values().stream().filter(group -> group.size() > 1).toList();
            if (!groups.isEmpty()) {
                int collided = groups.stream().mapToInt(List::size).sum();
                metric("names.case_collision_groups", groups.size());
                counted.put(Technique.CASE_COLLISIONS, new Count(collided, groups.size(),
                    groups.stream().limit(SAMPLES).map(group -> String.join(" / ", group.stream().sorted().map(ObfuscationProfile::clean).toList())).toList()));
            }
            // Non-identifier names: any (javac never emits one; Kotlin's mangled names are excluded).
            copy(Technique.NON_IDENTIFIER_NAMES, counted, 1);
            // Stripped attributes: InnerClasses missing from a $ class, or SourceFile / LineNumberTable missing from half the classes.
            if (metric.apply("debug.inner_classes_stripped") > 0 || classes > 0
                && (metric.apply("debug.no_sourcefile") * 2 >= classes || metric.apply("debug.no_line_numbers") * 2 >= classes))
                copy(Technique.STRIPPED_ATTRIBUTES, counted, 1);
            // Native layer: native methods with obfuscated names, or a known native-obfuscator loader.
            if (metric.apply("native.obfuscated_names") > 0 || metric.apply("marker.native_obfuscator") > 0) {
                var set = classSets.getOrDefault(Technique.NATIVE_LAYER, Set.of());
                counted.put(Technique.NATIVE_LAYER, new Count(set.size(), metric.apply("native.obfuscated_names") + metric.apply("native.libraries"),
                    List.copyOf(samples.getOrDefault(Technique.NATIVE_LAYER, new LinkedHashSet<>()))));
            }
            // Control flow: patterns javac never emits (goto chains, constant conditions, switch dispatch loops), or flag loads
            // at method entry in at least a third of a class's methods.
            var cfHitClasses = new HashSet<String>();
            int cfClasses = 0, cfOccurrences = 0, chains = 0, conditions = 0, dispatchers = 0, heavy = 0, flagged = 0;
            for (var classFlow : controlFlow.entrySet()) {
                var cf = classFlow.getValue();
                chains += cf[0]; conditions += cf[1]; dispatchers += cf[2]; heavy += cf[3];
                boolean flags = cf[5] >= 3 && cf[4] * 3 >= cf[5];
                if (flags) flagged++;
                int hits = cf[1] + cf[2] + cf[3] + (flags ? cf[4] : 0);
                if (hits > 0) { cfClasses++; cfOccurrences += hits; cfHitClasses.add(classFlow.getKey()); }
            }
            metric("cf.goto_chains", chains); metric("cf.constant_conditions", conditions); metric("cf.switch_dispatchers", dispatchers);
            metric("cf.goto_heavy_methods", heavy); metric("cf.flag_at_entry_classes", flagged);
            // Phase 2CA: opaque predicates on a static flag read through an accessor (ZKM keeps a static String[] or boolean and
            // tests a local copy of it with ifnull/ifnonnull or ifeq/ifne). Only accessors defined in the class set count.
            var flagClasses = new LinkedHashSet<String>(); int flagMethods = 0, flagTests = 0, usedAccessors = 0;
            var flagSamples = new ArrayList<String>();
            for (var entry : new TreeMap<>(flagSites).entrySet()) {
                if (!flagAccessors.contains(entry.getKey())) continue;
                usedAccessors++;
                for (var site : entry.getValue()) {
                    flagClasses.add(site[0]); flagMethods++; flagTests += Integer.parseInt(site[2]);
                    if (flagSamples.size() < 3) flagSamples.add("flag " + clean(entry.getKey()) + " tested in " + site[1]);
                }
            }
            metric("cf.flag_accessors", usedAccessors); metric("cf.flag_accessor_methods", flagMethods);
            metric("cf.flag_accessor_tests", flagTests); metric("cf.flag_accessor_classes", flagClasses.size());
            boolean flagAccessor = flagMethods >= 10 && flagClasses.size() >= 3;
            if (flagAccessor) {
                for (String cls : flagClasses) if (cfHitClasses.add(cls)) cfClasses++;
                cfOccurrences += flagTests;
                for (String sample : flagSamples) sample(Technique.CONTROL_FLOW, sample);
            }
            // Goto chains are only an indicator: javac emits them too (finally and try-with-resources exits).
            // Phase 2CD: Skidfuscator's self-looping switches on a hash of the block seed.
            int selfLoops = metric.apply("skid.self_loop_hash_switches");
            if (selfLoops > 0) {
                cfOccurrences += selfLoops;
                for (String cls : classSets.getOrDefault(Technique.CONTROL_FLOW, Set.of())) if (cfHitClasses.add(cls)) cfClasses++;
            }
            if (conditions >= 3 || dispatchers >= 2 || heavy >= 3 || flagged >= 3 || flagAccessor || selfLoops >= 2)
                counted.put(Technique.CONTROL_FLOW, new Count(cfClasses, cfOccurrences, List.copyOf(samples.getOrDefault(Technique.CONTROL_FLOW, new LinkedHashSet<>()))));
            // Phase 2CA: exception obfuscation, handlers rethrowing through an identity helper of the class set.
            var rethrowClasses = new LinkedHashSet<String>(); int rethrows = 0, helpers = 0; var rethrowSamples = new ArrayList<String>();
            for (var entry : new TreeMap<>(rethrowSites).entrySet()) {
                if (!identityHelpers.contains(entry.getKey())) continue;
                helpers++;
                if (rethrowSamples.size() < SAMPLES / 2) rethrowSamples.add("helper " + clean(entry.getKey()) + " (" + entry.getValue().size() + " handlers)");
                for (var site : entry.getValue()) { rethrowClasses.add(site[0]); rethrows++; }
            }
            metric("exceptions.identity_helpers", helpers); metric("exceptions.rethrow_handlers", rethrows);
            metric("exceptions.rethrow_classes", rethrowClasses.size());
            if (rethrows >= 10 && rethrowClasses.size() >= 2)
                counted.put(Technique.EXCEPTION_OBFUSCATION, new Count(rethrowClasses.size(), rethrows, rethrowSamples));
            metrics.entrySet().removeIf(entry -> entry.getValue() == 0);
            return new ObfuscationProfile(classes, unparsable, counted, metrics);
        }
        private final java.util.function.Function<String,Integer> metric = key -> metrics.getOrDefault(key, 0);

        private void copy(Technique technique, Map<Technique,Count> counted, int minimum) {
            int found = occurrences.getOrDefault(technique, 0);
            if (found < minimum) return;
            counted.put(technique, new Count(classSets.getOrDefault(technique, Set.of()).size(), found,
                List.copyOf(samples.getOrDefault(technique, new LinkedHashSet<>()))));
        }
        private void metric(String key, int delta) { metrics.merge(key, delta, Integer::sum); }
        private void hit(Technique technique, String cls, String sample) { count(technique, cls, 1); sample(technique, sample); }
        private void count(Technique technique, String cls, int delta) {
            classSets.computeIfAbsent(technique, k -> new HashSet<>()).add(cls);
            occurrences.merge(technique, delta, Integer::sum);
        }
        private void sample(Technique technique, String sample) {
            var set = samples.computeIfAbsent(technique, k -> new LinkedHashSet<>());
            if (set.size() < SAMPLES) set.add(sample.length() > 300 ? sample.substring(0, 300) + "..." : sample);
        }
    }

    // ---------------------------------------------------------------- instruction helpers

    private static AbstractInsnNode next(AbstractInsnNode insn) {
        for (var node = insn.getNext(); node != null; node = node.getNext()) if (node.getOpcode() >= 0) return node;
        return null;
    }
    private static AbstractInsnNode previous(AbstractInsnNode insn) {
        if (insn == null) return null;
        for (var node = insn.getPrevious(); node != null; node = node.getPrevious()) if (node.getOpcode() >= 0) return node;
        return null;
    }
    private static boolean constant(AbstractInsnNode insn) {
        if (insn == null) return false;
        int op = insn.getOpcode();
        return op >= ICONST_M1 && op <= LCONST_1 || op == BIPUSH || op == SIPUSH
            || insn instanceof LdcInsnNode ldc && (ldc.cst instanceof Integer || ldc.cst instanceof Long || ldc.cst instanceof String);
    }
    /** A switch that at least three later GOTOs jump back to (to a label at most four instructions before it): a dispatch loop. */
    private static boolean dispatcher(MethodNode method, AbstractInsnNode[] insns, int index) {
        var heads = new HashSet<LabelNode>();
        int realBack = 0;
        for (int i = index - 1; i >= 0 && realBack <= 4; i--) {
            if (insns[i] instanceof LabelNode label) heads.add(label);
            else if (insns[i].getOpcode() >= 0) realBack++;
        }
        int cases = insns[index] instanceof TableSwitchInsnNode table ? table.labels.size() : ((LookupSwitchInsnNode) insns[index]).labels.size();
        if (heads.isEmpty() || cases < 4) return false;
        int back = 0;
        for (int i = index + 1; i < insns.length; i++)
            if (insns[i] instanceof JumpInsnNode jump && jump.getOpcode() == GOTO && heads.contains(jump.label)) back++;
        return back >= 4 && back * 2 >= cases;
    }
    /** Labels some instruction can jump to: jump, switch and handler targets. */
    private static Set<LabelNode> jumpTargets(MethodNode method) {
        var targets = new HashSet<LabelNode>();
        for (var insn : method.instructions) {
            if (insn instanceof JumpInsnNode jump) targets.add(jump.label);
            if (insn instanceof TableSwitchInsnNode table) { targets.add(table.dflt); targets.addAll(table.labels); }
            if (insn instanceof LookupSwitchInsnNode lookup) { targets.add(lookup.dflt); targets.addAll(lookup.labels); }
        }
        for (var block : method.tryCatchBlocks) targets.add(block.handler);
        return targets;
    }
    /** No jump target between {@code from} and {@code to}: control reaches {@code to} only through {@code from}. */
    private static boolean straight(AbstractInsnNode from, AbstractInsnNode to, Set<LabelNode> targets) {
        for (var at = from.getNext(); at != null && at != to; at = at.getNext()) if (at instanceof LabelNode label && targets.contains(label)) return false;
        return true;
    }
    /**
     * Phase 2DC: forward gotos to a label at least three forward gotos share: the exits of a {@code when}/{@code switch}
     * expression or an if-else chain joining at one point, which javac and kotlinc emit for every arm.
     */
    private static Set<AbstractInsnNode> structuredExits(AbstractInsnNode[] insns) {
        var position = new HashMap<AbstractInsnNode,Integer>();
        for (int i = 0; i < insns.length; i++) position.put(insns[i], i);
        var byTarget = new HashMap<LabelNode,List<AbstractInsnNode>>();
        for (int i = 0; i < insns.length; i++)
            if (insns[i] instanceof JumpInsnNode jump && jump.getOpcode() == GOTO && position.getOrDefault(jump.label, -1) > i)
                byTarget.computeIfAbsent(jump.label, k -> new ArrayList<>()).add(jump);
        var exits = new HashSet<AbstractInsnNode>();
        for (var group : byTarget.values()) if (group.size() >= 3) exits.addAll(group);
        return exits;
    }
    private static boolean flagAtEntry(String cls, AbstractInsnNode[] insns) {
        AbstractInsnNode first = null;
        for (var insn : insns) if (insn.getOpcode() >= 0) { first = insn; break; }
        return first instanceof FieldInsnNode field && first.getOpcode() == GETSTATIC && field.owner.equals(cls)
            && (field.desc.equals("Z") || field.desc.equals("I")) && next(first) != null && next(first).getOpcode() == ISTORE;
    }
    /** {@code ()[X}, {@code ()Z} or {@code ()I}: the shapes of a static flow flag accessor. */
    private static boolean flagDesc(String desc) { return desc.startsWith("()[") || desc.equals("()Z") || desc.equals("()I"); }
    /** A bootstrap or call-site handler: its first parameter is a {@code MethodHandles.Lookup}. */
    private static boolean callSiteHandler(String desc) { return desc.startsWith("(Ljava/lang/invoke/MethodHandles$Lookup;"); }
    /** A value decoder: static, 1-4 int/long/short/byte/char parameters, returns String, int or long, and XORs or calls JCE. */
    private static boolean decoder(MethodNode method) {
        if (method.name.startsWith("<") || (method.access & ACC_STATIC) == 0) return false;
        String desc = method.desc, result = desc.substring(desc.indexOf(')') + 1);
        if (!result.equals("Ljava/lang/String;") && !result.equals("I") && !result.equals("J")) return false;
        if (!desc.substring(1, desc.indexOf(')')).matches("[IJSBC]{1,4}")) return false;
        for (var insn : method.instructions)
            if (insn.getOpcode() == IXOR || insn.getOpcode() == LXOR || insn instanceof MethodInsnNode call && cryptoCall(call)) return true;
        return false;
    }
    /** {@code (LX;)LX;} with the same reference type on both sides. */
    private static boolean identityDesc(String desc) {
        int close = desc.indexOf(')');
        return desc.startsWith("(L") && close > 0 && desc.indexOf(';') == close - 1 && desc.substring(1, close).equals(desc.substring(close + 1));
    }
    private static boolean readsStringArray(AbstractInsnNode[] insns) {
        for (var insn : insns) if (insn instanceof FieldInsnNode field && insn.getOpcode() == GETSTATIC && field.desc.equals("[Ljava/lang/String;")) return true;
        return false;
    }
    private static Set<Integer> longParameterSlots(MethodNode method) {
        var slots = new HashSet<Integer>();
        int slot = (method.access & ACC_STATIC) != 0 ? 0 : 1;
        for (var type : Type.getArgumentTypes(method.desc)) { if (type.getSort() == Type.LONG) slots.add(slot); slot += type.getSize(); }
        return slots;
    }
    private static boolean decryptorParameters(String desc) {
        var types = Type.getArgumentTypes(desc);
        if (types.length == 0 || types.length > 4) return false;
        for (var type : types) {
            String d = type.getDescriptor();
            if (!Set.of("I", "J", "S", "B", "C", "Ljava/lang/String;", "Ljava/lang/Object;", "[C", "[B").contains(d)) return false;
        }
        return true;
    }
    private static boolean cryptoCall(MethodInsnNode call) {
        return call.owner.equals("javax/crypto/Cipher") || call.owner.equals("javax/crypto/SecretKeyFactory")
            || call.owner.equals("javax/crypto/KeyGenerator") || call.owner.startsWith("javax/crypto/spec/");
    }
    private static boolean references(ClassNode node, String prefix) {
        if (node.superName != null && node.superName.startsWith(prefix)) return true;
        for (var list : Arrays.asList(node.visibleAnnotations, node.invisibleAnnotations))
            if (list != null) for (var annotation : list) if (annotation.desc.startsWith("L" + prefix)) return true;
        for (var method : node.methods)
            for (var insn : method.instructions)
                if (insn instanceof MethodInsnNode call && call.owner.startsWith(prefix) || insn instanceof FieldInsnNode field && field.owner.startsWith(prefix))
                    return true;
        return false;
    }

    // ---------------------------------------------------------------- names

    private static final Set<String> KEYWORDS = Set.of("abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class",
        "const", "continue", "default", "do", "double", "else", "enum", "extends", "final", "finally", "float", "for", "goto", "if",
        "implements", "import", "instanceof", "int", "interface", "long", "native", "new", "package", "private", "protected", "public",
        "return", "short", "static", "strictfp", "super", "switch", "synchronized", "this", "throw", "throws", "transient", "try", "void",
        "volatile", "while", "true", "false", "null", "_");
    static boolean javaIdentifier(String name) {
        if (name.isEmpty() || KEYWORDS.contains(name) || !Character.isJavaIdentifierStart(name.codePointAt(0))) return false;
        return name.codePoints().allMatch(Character::isJavaIdentifierPart) && name.codePoints().noneMatch(Character::isIdentifierIgnorable);
    }
    /**
     * Kotlin's name mangling ({@code constructor-impl}, {@code getValue-UwyO8pc}), with the compiler's {@code $} suffixes
     * (Phase 2DC: {@code getIndices--ajY-9A$annotations}, {@code foo-abc1234$default}).
     */
    private static boolean kotlinMangled(String name) {
        int dash = name.indexOf('-');
        return dash > 0 && javaIdentifier(name.substring(0, dash)) && name.substring(dash + 1).matches("[A-Za-z0-9_-]{1,16}([$][A-Za-z0-9_$]{1,40})?");
    }
    private static boolean hexLike(String name) { return name.length() >= 16 && name.matches("[0-9a-fA-F]+"); }
    /** Escapes control characters and lone surrogates so every sample stays printable on one line. */
    static String clean(String text) {
        var out = new StringBuilder();
        text.chars().forEach(c -> { if (c < ' ' || Character.isISOControl(c) || Character.isSurrogate((char) c))
            out.append(String.format(Locale.ROOT, "\\u%04x", c)); else out.append((char) c); });
        return out.toString();
    }
}
