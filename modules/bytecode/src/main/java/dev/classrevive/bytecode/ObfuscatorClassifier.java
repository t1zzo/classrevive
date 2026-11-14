package dev.classrevive.bytecode;

import dev.classrevive.bytecode.ObfuscationProfile.Technique;
import java.util.*;

/**
 * Phase 2BZ: documented rules that turn an {@link ObfuscationProfile} into obfuscator hypotheses. Every hypothesis is a
 * suggestion from static patterns, never proof: obfuscators share techniques, versions differ, and a jar can be processed
 * twice. Each rule adds points for its evidence (score 0..100); a hypothesis is reported from {@value #REPORTED} points,
 * as {@code LOW} below 45, {@code MEDIUM} below 70 and {@code HIGH} from 70. The rules are listed in docs/obfuscation-detection.md.
 */
public final class ObfuscatorClassifier {
    public static final int REPORTED = 30;
    public record Hypothesis(String obfuscator, int score, String confidence, List<String> reasons) {
        public Hypothesis { reasons = List.copyOf(reasons); }
    }
    /** {@code verdict}: SUGGESTED (at least one hypothesis), NONE (no technique and no marker) or UNKNOWN (techniques, no rule matched). */
    public record Result(String verdict, List<Hypothesis> hypotheses) {
        public Result { hypotheses = List.copyOf(hypotheses); }
        public String summary() {
            if (verdict.equals("NONE")) return "none (no obfuscation technique detected)";
            if (hypotheses.isEmpty()) return "unknown (techniques detected, but no rule matches a known obfuscator)";
            return String.join("; ", hypotheses.stream().map(h -> h.obfuscator() + " (" + h.confidence().toLowerCase(Locale.ROOT)
                + ", " + h.score() + "/100)").toList());
        }
    }

    private ObfuscatorClassifier() {}

    public static Result classify(ObfuscationProfile profile) {
        var hypotheses = new ArrayList<Hypothesis>();
        zkm(profile, hypotheses);
        zkmReflection(profile, hypotheses);
        allatori(profile, hypotheses);
        stringer(profile, hypotheses);
        dasho(profile, hypotheses);
        proguard(profile, hypotheses);
        nativeLayer(profile, hypotheses);
        skidfuscator(profile, hypotheses);
        aidsfuscator(profile, hypotheses);
        radon(profile, hypotheses);
        yguard(profile, hypotheses);
        nativeObfuscator(profile, hypotheses);
        j2cc(profile, hypotheses);
        bytecodeVm(profile, hypotheses);
        alpheratz(profile, hypotheses);
        branchlock(profile, hypotheses);
        ambien(profile, hypotheses);
        blackObfuscator(profile, hypotheses);
        hypotheses.sort(Comparator.comparingInt(Hypothesis::score).reversed().thenComparing(Hypothesis::obfuscator));
        String verdict = !hypotheses.isEmpty() ? "SUGGESTED" : profile.techniques().isEmpty() ? "NONE" : "UNKNOWN";
        return new Result(verdict, hypotheses);
    }

    private static final class Rule {
        final String name; int score; final List<String> reasons = new ArrayList<>();
        Rule(String name) { this.name = name; }
        void add(boolean condition, int points, String reason) { if (condition) { score += points; reasons.add(reason + " (+" + points + ")"); } }
        void subtract(boolean condition, int points, String reason) { if (condition) { score -= points; reasons.add(reason + " (-" + points + ")"); } }
        void into(List<Hypothesis> out) {
            int bounded = Math.max(0, Math.min(100, score));
            if (bounded < REPORTED) return;
            out.add(new Hypothesis(name, bounded, bounded >= 70 ? "HIGH" : bounded >= 45 ? "MEDIUM" : "LOW", reasons));
        }
    }

    /** Zelix KlassMaster: indy reference obfuscation with a (Lookup,String,MethodType) bootstrap, (IJ)/(II) string decryptors, DES keys. */
    private static void zkm(ObfuscationProfile p, List<Hypothesis> out) {
        var rule = new Rule("Zelix KlassMaster");
        int lookup = p.metric("indy.bootstrap_lookup_string_methodtype") + p.metric("indy.bootstrap_lookup_string_methodtype_extra");
        rule.add(p.has(Technique.HIDDEN_CALLS) && lookup > 0, 25, "invokedynamic with a jar-owned (Lookup, String, MethodType) bootstrap: " + lookup + " sites");
        rule.add(p.metric("strings.decryptor(IJ)") + p.metric("strings.decryptor(JI)") > 0, 25,
            "string decryptors taking an int and a long key, (IJ)Ljava/lang/String;: " + (p.metric("strings.decryptor(IJ)") + p.metric("strings.decryptor(JI)")));
        rule.add(p.metric("strings.decryptor(II)") > 0, 10, "string decryptors (II)Ljava/lang/String;: " + p.metric("strings.decryptor(II)"));
        int des = p.metric("cipher.DES") + p.metric("cipher.DESEDE");
        rule.add(des > 0 && p.metric("strings.clinit_crypto") > 0, 20, "DES used to decrypt strings in <clinit>: " + p.metric("strings.clinit_crypto") + " classes");
        rule.add(des == 0 && p.metric("strings.clinit_crypto") > 0, 10, "JCE decryption into static strings in <clinit>: " + p.metric("strings.clinit_crypto") + " classes");
        rule.add(p.metric("zkm.long_key_methods") >= 3, 10, "methods XOR-ing a long parameter with a long constant (method keys): " + p.metric("zkm.long_key_methods"));
        rule.add(p.has(Technique.CONTROL_FLOW) && p.metric("cf.flag_at_entry_classes") > 0, 5, "static flag loaded at method entry in " + p.metric("cf.flag_at_entry_classes") + " classes");
        // Phase 2CA: ZKM's flow obfuscation reads its static flag through an accessor and tests a local copy of it.
        rule.add(p.has(Technique.CONTROL_FLOW) && p.metric("cf.flag_accessor_methods") >= 10, 10, "opaque predicates on a static flag read through an accessor: "
            + p.metric("cf.flag_accessor_methods") + " methods in " + p.metric("cf.flag_accessor_classes") + " classes, " + p.metric("cf.flag_accessor_tests") + " tests");
        // Phase 2CB: int/long constants decoded through the same (int, long)-keyed invokedynamic sites as the strings.
        rule.add(p.has(Technique.CONSTANT_ENCRYPTION) && lookup > 0, 10, "int/long constants decoded through invokedynamic: "
            + p.metric("constants.indy_int_sites") + " int and " + p.metric("constants.indy_long_sites") + " long sites");
        rule.add(p.has(Technique.EXCEPTION_OBFUSCATION), 15, "exception obfuscation, handlers rethrowing through an identity helper: "
            + p.metric("exceptions.rethrow_handlers") + " handlers in " + p.metric("exceptions.rethrow_classes") + " classes");
        rule.add(rule.score > 0 && (p.has(Technique.SHORT_NAMES) || p.has(Technique.CASE_COLLISIONS)), 10, "short or case-colliding names");
        rule.subtract(p.metric("marker.allatori") > 0 || p.metric("marker.stringer_stacktrace") > 0, 30, "another obfuscator's marker present");
        rule.subtract(radonEvidence(p), 40, "Radon's injected decoders or invokedynamic shapes");
        rule.subtract(p.metric("zkm.reflection_class") > 0, 40, "its reflection class is reported as Zelix KlassMaster 27");
        rule.into(out);
    }

    /** Phase 2DM: Zelix KlassMaster 27's reflection obfuscation, the shape the {@code zkm} pass reverses. */
    private static void zkmReflection(ObfuscationProfile p, List<Hypothesis> out) {
        var rule = new Rule("Zelix KlassMaster 27");
        rule.add(p.metric("zkm.reflection_class") > 0, 85, "reflection class with (long, long) Method and Field getters and member finders");
        rule.add(rule.score > 0 && p.metric("zkm.long_key_methods") > 0, 10, "methods XOR-ing a long parameter with a long constant: " + p.metric("zkm.long_key_methods"));
        rule.into(out);
    }

    /** Allatori: its ALLATORI demo marker, or (String)String XOR decryptors called with literals. */
    private static void allatori(ObfuscationProfile p, List<Hypothesis> out) {
        var rule = new Rule("Allatori");
        rule.add(p.metric("marker.allatori") > 0, 80, "ALLATORI marker in names or literals: " + p.metric("marker.allatori"));
        rule.add(p.metric("strings.decryptor(Ljava/lang/String;)") > 0 && p.metric("strings.decryptor_calls") > 0, 25,
            "static (String)String decryptors called with literals: " + p.metric("strings.decryptor(Ljava/lang/String;)"));
        rule.add(p.metric("marker.allatori") > 0 && p.has(Technique.SHORT_NAMES), 5, "short names");
        rule.subtract(p.metric("marker.allatori") == 0 && (p.metric("strings.decryptor(IJ)") + p.metric("strings.decryptor(JI)") > 0
            || p.metric("indy.bootstrap_lookup_string_methodtype") > 0), 20, "no marker, and the other string and call evidence matches another tool");
        rule.into(out);
    }

    /** Stringer: decryptors that read the caller from the stack trace; (Object)String decryptors. */
    private static void stringer(ObfuscationProfile p, List<Hypothesis> out) {
        var rule = new Rule("Stringer");
        rule.add(p.metric("marker.stringer_stacktrace") > 0, 50, "string decryptors that read the stack trace: " + p.metric("marker.stringer_stacktrace"));
        rule.add(p.metric("strings.decryptor(Ljava/lang/Object;)") > 0, 20, "static (Object)String decryptors: " + p.metric("strings.decryptor(Ljava/lang/Object;)"));
        rule.add(rule.score > 0 && p.has(Technique.HIDDEN_CALLS), 15, "invokedynamic with a non-JDK bootstrap");
        rule.subtract(radonEvidence(p), 50, "the stack-trace decryptor is Radon's (Object, int)String with a Map and long[] cache");
        rule.into(out);
    }

    /** DashO: references to its com.preemptive runtime or annotations. */
    private static void dasho(ObfuscationProfile p, List<Hypothesis> out) {
        var rule = new Rule("DashO (PreEmptive)");
        rule.add(p.metric("marker.dasho") > 0, 70, "references to com.preemptive: " + p.metric("marker.dasho") + " classes");
        rule.add(rule.score > 0 && p.has(Technique.STRING_ENCRYPTION), 10, "string encryption");
        rule.into(out);
    }

    /** ProGuard / R8: renaming only (a/a/a), SourceFile renamed or removed; no string encryption or hidden calls (they have neither). */
    private static void proguard(ObfuscationProfile p, List<Hypothesis> out) {
        var rule = new Rule("ProGuard / R8 (renaming only)");
        rule.add(p.has(Technique.SHORT_NAMES), 35, "short names: " + (p.has(Technique.SHORT_NAMES) ? p.techniques().get(Technique.SHORT_NAMES).classes() : 0) + " classes");
        rule.add(p.metric("names.short_packages") >= 5, 15, "classes in packages whose every segment has at most 2 characters: " + p.metric("names.short_packages"));
        rule.add(p.metric("debug.sourcefile_renamed") > 0, 25, "SourceFile set to \"SourceFile\" or an r8-map-id: " + p.metric("debug.sourcefile_renamed") + " classes");
        rule.add(rule.score > 0 && p.metric("debug.no_local_variables") * 2 >= Math.max(1, p.classes()), 5, "no LocalVariableTable");
        rule.subtract(p.has(Technique.STRING_ENCRYPTION), 30, "string encryption is not a ProGuard/R8 feature");
        rule.subtract(p.has(Technique.HIDDEN_CALLS), 30, "invokedynamic call hiding is not a ProGuard/R8 feature");
        rule.subtract(p.has(Technique.NON_IDENTIFIER_NAMES), 15, "names that are no Java identifiers (ProGuard/R8 emit identifiers by default)");
        rule.subtract(p.metric("names.non_ascii_short_members") >= 10, 20, "short names outside ASCII (yGuard's default naming; ProGuard/R8 use a-z)");
        rule.into(out);
    }

    /** A native protection layer: obfuscated native method names, a bundled native library, a native-obfuscator loader. */
    private static void nativeLayer(ObfuscationProfile p, List<Hypothesis> out) {
        // A transpiler recognized by its own rule names the layer; this generic one would only repeat it.
        if (p.metric("natobf.loaders") > 0 || p.metric("j2cc.loaders") > 0) return;
        boolean known = p.metric("marker.native_obfuscator") > 0;
        var rule = new Rule(known ? "native protection layer (JNIC / native-obfuscator style)" : "custom native protection layer");
        rule.add(p.metric("native.obfuscated_names") > 0, 40, "native methods with hexadecimal or non-identifier names: " + p.metric("native.obfuscated_names"));
        rule.add(known, 40, "known native-obfuscator loader class");
        rule.add(rule.score > 0 && p.metric("native.libraries") > 0, 25, "native libraries bundled in the jar: " + p.metric("native.libraries"));
        rule.add(rule.score > 0 && p.metric("native.load_calls") > 0, 10, "System/Runtime load calls: " + p.metric("native.load_calls"));
        rule.add(rule.score > 0 && p.metric("strings.native_key_classes") > 0, 10, "decryption keys supplied by the native layer (<clinit> only registers natives): "
            + p.metric("strings.native_key_classes") + " classes");
        rule.into(out);
    }

    /**
     * Phase 2CD: Skidfuscator (community edition): per-block seed locals updated as {@code seed = C ^ seed}, switches on a
     * hash of the seed that loop back to themselves, gotos disguised as a caught {@code throw new X()}, seed-keyed string
     * decryptors, a static int seeded by {@code new Random(long).nextInt()}, and its {@code sdk/SDK} runtime and
     * {@code nothing_to_see_here} marker.
     */
    private static void skidfuscator(ObfuscationProfile p, List<Hypothesis> out) {
        var rule = new Rule("Skidfuscator");
        rule.add(p.metric("skid.self_loop_hash_switches") >= 2, 35, "switches on a hash of the block seed that jump back to themselves: "
            + p.metric("skid.self_loop_hash_switches"));
        rule.add(p.metric("skid.seed_methods") >= 3, 20, "methods updating a seed local as seed = C ^ seed: " + p.metric("skid.seed_methods"));
        rule.add(p.metric("strings.skid_sites") >= 3, 15, "string decryptors keyed by Integer.toString(seed).getBytes(): "
            + p.metric("skid.seed_string_decryptors") + " decryptors, " + p.metric("strings.skid_sites") + " call sites");
        rule.add(p.metric("skid.random_seed_fields") > 0, 10, "static int fields seeded by new Random(long).nextInt() in <clinit>: " + p.metric("skid.random_seed_fields"));
        rule.add(p.metric("skid.parseint_seeds") > 0, 5, "Integer.parseInt(\"digits\") folded into a seed: " + p.metric("skid.parseint_seeds"));
        rule.add(rule.score > 0 && p.metric("skid.exception_gotos") > 0, 10, "gotos disguised as throw new X() caught by a handler that pops it: " + p.metric("skid.exception_gotos"));
        rule.add(p.metric("marker.skidfuscator_sdk") > 0, 25, "Skidfuscator runtime class sdk/SDK (" + p.metric("skid.sdk_calls") + " calls)");
        rule.add(p.metric("marker.ahegao") > 0, 25, "nothing_to_see_here String[] marker field: " + p.metric("marker.ahegao") + " classes");
        rule.into(out);
    }

    /** Phase 2DK: evidence only Radon's injected classes and call shapes give. */
    static boolean radonEvidence(ObfuscationProfile p) {
        return p.metric("radon.string_sites") > 0 || p.metric("radon.number_sites") > 0 || p.metric("radon.indy_heavy_sites") > 0
            || p.metric("radon.indy_fast_sites") > 0 || p.metric("radon.bad_annotations") > 0;
    }

    /**
     * Phase 2DK: Radon 2 (ItzSomebody/radon, GPL-3.0): its string decryptor ({@code (Object, int)String} in a class with a
     * {@code Map} and a {@code long[]}, called as {@code ldc; int; invokestatic}), its number decoder (a {@code Thread}
     * subclass), its heavy ({@code a b d e f g}, {@code (..., String, long)}) and fast (encrypted name) invokedynamic sites,
     * flow predicates ({@code static final} fields nothing writes, read into locals), fake catch blocks, mutilated null
     * checks, hidden members and annotations of type {@code @}. Rules in docs/radon-yguard.md.
     */
    private static void radon(ObfuscationProfile p, List<Hypothesis> out) {
        var rule = new Rule("Radon");
        rule.add(p.metric("radon.string_sites") > 0, 40, "string decryptor (Object, int)String with a Map and long[] cache, called as ldc; int; invokestatic: "
            + p.metric("radon.string_sites") + " sites");
        rule.add(p.metric("radon.number_sites") > 0, 35, "numbers decoded through a Thread subclass's (Object, int)Object: " + p.metric("radon.number_sites") + " sites");
        rule.add(p.metric("radon.indy_heavy_sites") > 0, 40, "invokedynamic a/b/d/e/f/g with (..., String, long) arguments and an (Object, Object, Object) bootstrap: "
            + p.metric("radon.indy_heavy_sites") + " sites");
        rule.add(p.metric("radon.indy_fast_sites") > 0, 40, "invokedynamic names that decrypt to owner, name, descriptor and kind: " + p.metric("radon.indy_fast_sites") + " sites");
        rule.add(p.metric("radon.predicate_reads") >= 3, 25, "static final predicates nothing writes, read into locals: " + p.metric("radon.predicate_fields")
            + " fields, " + p.metric("radon.predicate_reads") + " methods");
        rule.add(p.metric("radon.fake_catch_sites") > 0, 20, "catch blocks of an empty exception class calling a method it lacks: " + p.metric("radon.fake_catch_sites"));
        rule.add(p.metric("radon.null_check_traps") >= 3, 15, "null checks written as an Object call caught as NullPointerException: " + p.metric("radon.null_check_traps"));
        rule.add(p.metric("radon.bad_annotations") > 0, 45, "annotations of type @: " + p.metric("radon.bad_annotations"));
        rule.add(rule.score > 0 && p.metric("radon.hidden_methods") >= 3, 10, "methods flagged bridge that are no bridges: " + p.metric("radon.hidden_methods"));
        rule.into(out);
    }

    /**
     * Phase 2DK: yGuard (yWorks, MIT): renaming only; its default naming draws one- or two-character names from every
     * character a Java identifier may hold, so most are outside ASCII; its "best"/"mix" schemes use Java keywords.
     */
    private static void yguard(ObfuscationProfile p, List<Hypothesis> out) {
        var rule = new Rule("yGuard (renaming)");
        int members = p.metric("names.non_ascii_short_members");
        rule.add(members >= 10, 50, "member names of one or two characters outside ASCII: " + members);
        rule.add(p.metric("names.non_ascii_short_classes") >= 3, 20, "class names of one or two characters outside ASCII: " + p.metric("names.non_ascii_short_classes"));
        rule.add(rule.score > 0 && p.has(Technique.SHORT_NAMES), 10, "short names");
        rule.subtract(p.has(Technique.STRING_ENCRYPTION), 30, "string encryption is not a yGuard feature");
        rule.subtract(p.has(Technique.HIDDEN_CALLS), 30, "invokedynamic call hiding is not a yGuard feature");
        rule.into(out);
    }

    /**
     * native-obfuscator (radioegor146) and its forks (native-obfuscator-plus): Java methods transpiled to C++ behind JNI.
     * The loader's static native {@code registerNativesForClass(int, Class)}, each processed initializer calling it, and
     * the C++ file named in {@code SourceFile}. The transpiled methods' bytecode is gone from the jar.
     */
    private static void nativeObfuscator(ObfuscationProfile p, List<Hypothesis> out) {
        var rule = new Rule("native-obfuscator (radioegor146 / native-obfuscator-plus)");
        rule.add(p.metric("natobf.loaders") > 0, 60, "loader with a static native registerNativesForClass(int, Class): " + p.metric("natobf.loaders"));
        rule.add(p.metric("natobf.registering_classes") > 0, 25, "static initializers registering their natives through it: " + p.metric("natobf.registering_classes"));
        rule.add(rule.score > 0 && p.metric("natobf.cpp_sourcefiles") > 0, 10, "classes whose SourceFile names a .cpp file: " + p.metric("natobf.cpp_sourcefiles"));
        rule.add(rule.score > 0 && p.metric("native.libraries") > 0, 10, "native libraries bundled in the jar: " + p.metric("native.libraries"));
        rule.into(out);
    }

    /**
     * j2cc (0x3C50/j2cc): Java to C++ transpiler. Its loader (native {@code bootstrap(byte[])} and {@code initClass(Class)},
     * {@code doInit(Class)}), the {@code j2cc/natives.bin} and {@code j2cc/relocationInfo.dat} resources, and its
     * method-handle call obfuscation ({@code j2$mh} table and filler), which it also applies to classes it does not transpile.
     */
    private static void j2cc(ObfuscationProfile p, List<Hypothesis> out) {
        var rule = new Rule("j2cc");
        rule.add(p.metric("j2cc.loaders") > 0, 50, "loader with native bootstrap(byte[]) and initClass(Class), and doInit(Class): " + p.metric("j2cc.loaders"));
        rule.add(p.metric("j2cc.resources") > 0, 40, "j2cc/natives.bin and j2cc/relocationInfo.dat resources: " + p.metric("j2cc.resources"));
        rule.add(p.metric("j2cc.initializing_classes") > 0 && p.metric("j2cc.loaders") > 0, 15, "static initializers calling doInit(Class): "
            + p.metric("j2cc.initializing_classes"));
        rule.add(p.metric("j2cc.mh_tables") > 0 && p.metric("j2cc.mh_fillers") > 0, 45, "calls through a j2$mh MethodHandle table: "
            + p.metric("j2cc.mh_tables") + " classes, " + p.metric("j2cc.mh_call_sites") + " sites");
        rule.into(out);
    }

    /**
     * BytecodeVM (NHCM-dev/BytecodeVM, MIT): methods virtualized into a generated VM's own bytecode. Each protected method
     * calls the VM's static {@code execute(code id, receiver, Object[] args[, integrity key])}; the VM keeps its programs
     * in a {@code $CodePool} class.
     */
    private static void bytecodeVm(ObfuscationProfile p, List<Hypothesis> out) {
        var rule = new Rule("BytecodeVM (virtualization)");
        rule.add(p.metric("bvm.vm_classes") > 0 && p.metric("bvm.stub_methods") > 0, 55, "methods whose body calls a VM's execute(code id, receiver, Object[] args): "
            + p.metric("bvm.stub_methods"));
        rule.add(p.metric("bvm.code_pools") > 0, 30, "$CodePool classes: " + p.metric("bvm.code_pools"));
        rule.add(rule.score > 0 && p.metric("bvm.stub_methods") >= 3, 10, "three or more virtualized methods");
        rule.into(out);
    }

    /**
     * BlackObfuscator (CodingGay/BlackObfuscator, Apache-2.0): Android dex control-flow flattening keyed by a string:
     * {@code switch (s.hashCode() ^ k)} in a loop whose cases assign the next state string and jump back.
     */
    private static void blackObfuscator(ObfuscationProfile p, List<Hypothesis> out) {
        var rule = new Rule("BlackObfuscator");
        rule.add(p.metric("black.flattened_methods") >= 1, 45, "methods flattened behind switch (state.hashCode() ^ k) dispatchers: "
            + p.metric("black.flattened_methods") + " methods, " + p.metric("black.dispatchers") + " dispatchers");
        rule.add(p.metric("black.flattened_methods") >= 3, 30, "three or more flattened methods");
        rule.into(out);
    }

    /**
     * Ambien (iiiiiiiris/Ambien, MIT): its zip comment (on by default), red herring jar, string decryptor, reference hider
     * and crashers. Each is a shape of its own; any one of the strong ones names it.
     */
    private static void ambien(ObfuscationProfile p, List<Hypothesis> out) {
        var rule = new Rule("Ambien");
        rule.add(p.metric("ambien.archive_comment") > 0, 80, "archive comment naming Ambien (its default)");
        rule.add(p.metric("ambien.red_herring") > 0, 80, "red herring class Ambien extending itself");
        rule.add(p.metric("ambien.string_decryptors") > 0, 50, "string decryptors (String, int, int)String seeding a Random and decoding Base64: "
            + p.metric("ambien.string_decryptors"));
        rule.add(p.metric("ambien.reference_hiders") > 0, 50, "reference hiders (String, String, String)MutableCallSite through Class.forName and findStatic: "
            + p.metric("ambien.reference_hiders"));
        rule.add(p.metric("ambien.line_feed_annotations") >= 3, 35, "annotations whose type is a run of line feeds: " + p.metric("ambien.line_feed_annotations"));
        rule.add(p.metric("ambien.html_class") > 0, 20, "class named <html><img src=\"https:...");
        rule.add(p.metric("ambien.self_inheritance") > 0, 20, "class extending itself: " + p.metric("ambien.self_inheritance"));
        rule.add(rule.score > 0 && p.metric("packaging.folder_classes") > 0, 10, "classes hidden as folders (X.class/): " + p.metric("packaging.folder_classes"));
        rule.into(out);
    }

    /**
     * Branchlock 4 (loerting/branchlock_obf): its watermark and demo markers, leftover annotations, its hash-keyed
     * reference lookup class, per-class string tables decrypted in {@code <clinit>} with a key from the class name and a
     * stack-trace check of the initializer's name, and its crasher classes.
     */
    private static void branchlock(ObfuscationProfile p, List<Hypothesis> out) {
        var rule = new Rule("Branchlock");
        rule.add(p.metric("branchlock.watermark") > 0, 80, "watermark class Branchlock_* naming branchlock.net");
        rule.add(p.metric("branchlock.demo") > 0, 80, "demo marker (BRANCHLOCK_DOT_NET_DEMO field or printed notice): " + p.metric("branchlock.demo"));
        rule.add(p.metric("branchlock.annotations") > 0, 60, "net/branchlock/annotations/ annotations left in place: " + p.metric("branchlock.annotations"));
        rule.add(p.metric("branchlock.reference_lookups") > 0, 50, "reference lookup class with static (long, Class)Method and (long, Class)Field finders");
        rule.add(p.metric("branchlock.string_tables") >= 2, 40, "static initializers decrypting a String[] table with a stack-trace check of their own name: "
            + p.metric("branchlock.string_tables"));
        rule.add(p.metric("branchlock.crasher_classes") > 0, 30, "crasher classes (HTML or oversized META-INF names): " + p.metric("branchlock.crasher_classes"));
        rule.into(out);
    }

    /**
     * alpheratzteam/obfuscator (MIT): table-keyed numbers and booleans, goto traps on a {@code static final boolean} nothing
     * sets, trash predicates in front of branches, the same unrelated exceptions declared by every method, anti-debug.
     */
    private static void alpheratz(ObfuscationProfile p, List<Hypothesis> out) {
        var rule = new Rule("alpheratz obfuscator");
        rule.add(p.metric("alph.number_sites") >= 3, 35, "numbers decoded from a per-class table (ldc; ldc; table[i]; mul; xor): " + p.metric("alph.number_sites"));
        rule.add(p.metric("alph.boolean_sites") >= 2, 20, "booleans as a comparison of two table entries: " + p.metric("alph.boolean_sites"));
        rule.add(p.metric("alph.flow_traps") >= 3, 25, "gotos as getstatic F:Z; ifeq; aconst_null; athrow on a field nothing sets: " + p.metric("alph.flow_traps"));
        rule.add(p.metric("alph.trash_predicates") >= 5, 15, "constant predicates (\"ab\".equals(\"ab\"), Float.isNaN(c)) before branches: " + p.metric("alph.trash_predicates"));
        rule.add(p.metric("alph.trash_throws") >= 3, 30, "methods declaring BufferUnderflowException and AnnotationTypeMismatchException among others: "
            + p.metric("alph.trash_throws"));
        rule.add(rule.score > 0 && p.metric("alph.anti_debug") > 0, 10, "anti-debug check of the JVM's input arguments: " + p.metric("alph.anti_debug"));
        rule.into(out);
    }

    /**
     * Phase 2CZ: Aidsfuscator: its salt dispatcher (FNV-1a class-name hash), reference bootstrap ({@code MutableCallSite}),
     * hash integrity class (SHA-256 of the class files in a {@code .bin} resource), string decryptors keyed by their caller's
     * stack trace, and {@code <clinit>} salts read through {@code MethodHandles.lookup().lookupClass()}.
     */
    private static void aidsfuscator(ObfuscationProfile p, List<Hypothesis> out) {
        var rule = new Rule("Aidsfuscator");
        rule.add(p.metric("aids.salt_dispatcher") > 0, 30, "salt dispatcher class with an FNV-1a class-name hash: " + p.metric("aids.salt_dispatcher"));
        rule.add(p.metric("aids.reference_bootstrap") > 0, 25, "invokedynamic bootstrap returning a MutableCallSite with an (int, int) reference decoder");
        rule.add(p.metric("aids.hash_integrity") > 0, 20, "hash integrity class reading SHA-256 hashes of the class files from a .bin resource");
        rule.add(p.metric("aids.stacktrace_string_decryptors") >= 2, 15, "string decryptors keyed by their caller's stack trace: "
            + p.metric("aids.stacktrace_string_decryptors"));
        rule.add(p.metric("aids.lookup_class_retrievers") >= 2, 10, "static initializers reading a salt through MethodHandles.lookup().lookupClass(): "
            + p.metric("aids.lookup_class_retrievers"));
        rule.into(out);
    }
}
