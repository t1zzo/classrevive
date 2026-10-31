package dev.classrevive.transforms.skid;

import org.objectweb.asm.tree.analysis.Value;
import java.util.*;
import java.util.function.IntFunction;

/**
 * Phase 2CN: an abstract JVM value. Int values carry the finite set of values they can take ({@code ints}, null when
 * unknown) and, when they were computed from one local variable by known pure operations, that provenance
 * ({@link Prov}): a branch on such a value narrows the local's set on each edge.
 */
final class SV implements Value {
    enum Kind { UNINIT, INT, LONG, FLOAT, DOUBLE, REF, RET }
    static final int MAX_SET = 1024;
    static final SV UNINIT = new SV(Kind.UNINIT, null, null, null, null, false, false);
    static final SV INT_TOP = new SV(Kind.INT, null, null, null, null, false, false);
    static final SV LONG_TOP = new SV(Kind.LONG, null, null, null, null, false, false);
    static final SV FLOAT_TOP = new SV(Kind.FLOAT, null, null, null, null, false, false);
    static final SV DOUBLE_TOP = new SV(Kind.DOUBLE, null, null, null, null, false, false);
    static final SV REF_TOP = new SV(Kind.REF, null, null, null, null, false, false);
    static final SV RET = new SV(Kind.RET, null, null, null, null, false, false);

    final Kind kind;
    /** INT: sorted distinct values, or null when unknown. */
    final int[] ints;
    /** INT: the local this value was computed from, or null. */
    final Prov prov;
    /** LONG: a Long constant; REF: a String constant (from ldc); otherwise null. */
    final Object constant;
    /** REF: an internal name or array descriptor when known. */
    final String type;
    /**
     * Computed from the obfuscator's material (a seed field, the injected seed parameter, an unfolded operation on two
     * literals, which javac never emits, or a seed local): only such values are folded and only such branches pruned, so
     * the program's own constants and dead branches stay as written.
     */
    final boolean tainted;
    /** Pushed directly by a constant instruction (iconst, bipush, sipush, ldc). */
    final boolean literal;
    /** The result of one of the obfuscator's hash functions (a factory hash or a pure function applied to one). */
    final boolean hashed;

    private SV(Kind kind, int[] ints, Prov prov, Object constant, String type, boolean tainted, boolean literal) {
        this(kind, ints, prov, constant, type, tainted, literal, false);
    }
    private SV(Kind kind, int[] ints, Prov prov, Object constant, String type, boolean tainted, boolean literal, boolean hashed) {
        this.kind = kind; this.ints = ints; this.prov = prov; this.constant = constant; this.type = type;
        this.tainted = tainted; this.literal = literal; this.hashed = hashed;
    }
    SV hashedValue() { return kind != Kind.INT || hashed ? this : new SV(kind, ints, prov, null, null, true, false, true); }

    /**
     * The provenance of an int value: {@code fn(value of local)}. {@code fn} returns null when it cannot be computed for
     * an input (then a branch keeps that input on both edges). Instances are canonical per producing instruction.
     */
    record Prov(int local, IntFunction<Integer> fn) {
        @Override public boolean equals(Object other) { return this == other; }
        @Override public int hashCode() { return System.identityHashCode(this); }
    }

    static SV ints(int[] values, Prov prov, boolean tainted) {
        if (values == null || values.length > MAX_SET) return prov == null && !tainted ? INT_TOP : new SV(Kind.INT, null, prov, null, null, tainted, false);
        return new SV(Kind.INT, values, prov, null, null, tainted, false);
    }
    static SV ints(int[] values, Prov prov) { return ints(values, prov, false); }
    static SV of(int value) { return new SV(Kind.INT, new int[] {value}, null, null, null, false, false); }
    static SV literal(int value) { return new SV(Kind.INT, new int[] {value}, null, null, null, false, true); }
    static SV ref(String type, Object constant) { return new SV(Kind.REF, null, null, constant, type, false, false); }
    static SV longConstant(Long value) { return new SV(Kind.LONG, null, null, value, null, false, false); }
    SV withProv(Prov prov) { return kind == Kind.INT ? new SV(kind, ints, prov, null, null, tainted, false, hashed) : this; }
    SV withoutProv() { return prov == null && !literal ? this : new SV(kind, ints, null, constant, type, tainted, false); }
    SV taint() { return tainted || kind != Kind.INT ? this : new SV(kind, ints, prov, null, null, true, literal); }

    boolean singleton() { return kind == Kind.INT && ints != null && ints.length == 1; }
    int single() { return ints[0]; }

    static int[] sorted(Collection<Integer> values) {
        if (values.size() > MAX_SET) return null;
        int[] result = values.stream().mapToInt(Integer::intValue).sorted().distinct().toArray();
        return result;
    }
    static int[] union(int[] a, int[] b) {
        if (a == null || b == null) return null;
        var set = new TreeSet<Integer>();
        for (int v : a) set.add(v);
        for (int v : b) set.add(v);
        return set.size() > MAX_SET ? null : sorted(set);
    }

    SV merge(SV other) {
        if (this == other || equals(other)) return this;
        if (kind != other.kind) return UNINIT;
        return switch (kind) {
            // Only the obfuscator's values need sets; the program's own values widen at once (loop counters converge).
            case INT -> tainted || other.tainted ? ints(union(ints, other.ints), prov == other.prov ? prov : null, true) : INT_TOP;
            case LONG -> Objects.equals(constant, other.constant) ? this : LONG_TOP;
            case REF -> ref(Objects.equals(type, other.type) ? type : null, Objects.equals(constant, other.constant) ? constant : null);
            default -> this;
        };
    }

    @Override public int getSize() { return kind == Kind.LONG || kind == Kind.DOUBLE ? 2 : 1; }
    @Override public boolean equals(Object o) {
        return o instanceof SV v && v.kind == kind && Arrays.equals(v.ints, ints) && v.prov == prov
            && Objects.equals(v.constant, constant) && Objects.equals(v.type, type) && v.tainted == tainted && v.literal == literal && v.hashed == hashed;
    }
    @Override public int hashCode() { return Objects.hash(kind, Arrays.hashCode(ints), constant, type); }
    @Override public String toString() {
        return kind + (ints == null ? "" : Arrays.toString(ints.length > 4 ? Arrays.copyOf(ints, 4) : ints)) + (prov != null ? "@" + prov.local : "")
            + (constant != null ? "=" + constant : "") + (tainted ? "*" : "");
    }
}
