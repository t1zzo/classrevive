package dev.classrevive.transforms;

import java.util.*;

/** Explicit allowlist; no classpath scanning or target-controlled plugin discovery. */
public final class BuiltinPasses {
    public static final String DEFAULT = "integer-constants";
    /** Phase 2CN: passes that need the whole jar at once; they run before the per-class passes, in the order given. */
    public static final Set<String> JAR_PASSES = Set.of(dev.classrevive.transforms.skid.SkidfuscatorReverser.ID,
        dev.classrevive.transforms.skid.AidsfuscatorReverser.ID);
    private BuiltinPasses() {}
    /** The whole-jar passes of a selection, in order; they must come before every per-class pass. */
    public static List<String> jarPasses(String selection) {
        Objects.requireNonNull(selection);
        var result = new ArrayList<String>(); boolean classPassSeen = false;
        for (String name : selection.split(",",-1)) {
            if (JAR_PASSES.contains(name)) {
                if (classPassSeen) throw new IllegalArgumentException("Whole-jar pass must come first: " + name);
                result.add(name);
            } else classPassSeen = true;
        }
        return List.copyOf(result);
    }
    /** Every pass name of a selection, validated (whole-jar and per-class). */
    public static List<String> names(String selection) {
        jarPasses(selection); select(selection);
        return List.of(selection.split(",",-1));
    }
    public static List<BytecodePass> select(String selection) { return select(selection, List.of()); }
    /**
     * The per-class passes of a selection; whole-jar passes ({@link #JAR_PASSES}) are skipped here.
     * @param classes every class of the input, for passes that need the whole program (Phase 2CM: {@code zkm-flags}
     * proves a flag constant over all of its reads and writes); empty when only the names are validated
     */
    public static List<BytecodePass> select(String selection, Collection<byte[]> classes) {
        Objects.requireNonNull(selection);
        var result = new ArrayList<BytecodePass>(); var seen = new HashSet<String>();
        for (String name : selection.split(",",-1)) {
            if (!seen.add(name)) throw new IllegalArgumentException("Duplicate pass: " + name);
            if (JAR_PASSES.contains(name)) continue;
            result.add(switch(name) {
                case "integer-constants" -> new IntegerConstantPass();
                case "integer-unary" -> new IntegerUnaryPass();
                case "integer-branches" -> new IntegerBranchPass();
                case "string-length" -> new StringConstantPass(StringConstantPass.Operation.LENGTH);
                case "string-char-at" -> new StringConstantPass(StringConstantPass.Operation.CHAR_AT);
                case "string-decryptor" -> new StaticStringDecryptorPass();
                case ZkmFlagPass.NAME -> classes.isEmpty() ? ZkmFlagPass.none() : ZkmFlagPass.plan(classes);
                default -> throw new IllegalArgumentException("Unknown pass: " + name);
            });
        }
        return List.copyOf(result);
    }
}
