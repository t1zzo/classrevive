package dev.classrevive.transforms.flow;

import org.objectweb.asm.tree.analysis.Value;
import java.util.Objects;

/**
 * Phase 2DL: an abstract JVM value for {@link FlowAnalyzer}.
 *
 * <ul>
 * <li>{@code int} values carry an interval {@code [lo, hi]} and a parity: a constant bit, or "the parity of value
 * {@code pbase}, flipped by {@code pbit}", or unknown. The parity form proves the classic algebraic opaque predicates
 * ({@code x * (x + 1)} and {@code x * x + x} are even) without knowing {@code x};</li>
 * <li>{@code int} and reference values may carry a value number ({@code id}): two slots with the same id hold the same
 * run-time value, so {@code x - x}, {@code x ^ x} and {@code x == x} are decided. An id names the instruction (or the
 * parameter, or the handler) that produced the value; the analyzer strips an id from every slot when its producer runs
 * again, so an id always means "the latest value that producer made";</li>
 * <li>{@code long} values are a constant or unknown; references know whether they are {@code null}, non-null or either,
 * and a string constant.</li>
 * </ul>
 */
public final class FV implements Value {
    public enum Kind { UNINIT, INT, LONG, FLOAT, DOUBLE, REF, RET }
    static final int UNKNOWN = 0, NULL = 1, NONNULL = 2;

    static final FV UNINIT = new FV(Kind.UNINIT, 0, 0, null, 0, true, null, null, 0, null);
    static final FV FLOAT = new FV(Kind.FLOAT, 0, 0, null, 0, true, null, null, 0, null);
    static final FV DOUBLE = new FV(Kind.DOUBLE, 0, 0, null, 0, true, null, null, 0, null);
    static final FV LONG_TOP = new FV(Kind.LONG, 0, 0, null, 0, true, null, null, 0, null);
    static final FV RET = new FV(Kind.RET, 0, 0, null, 0, true, null, null, 0, null);
    static final FV REF_TOP = new FV(Kind.REF, 0, 0, null, 0, true, null, null, UNKNOWN, null);
    static final FV NULL_REF = new FV(Kind.REF, 0, 0, null, 0, true, null, null, NULL, null);
    static final FV INT_TOP = new FV(Kind.INT, Integer.MIN_VALUE, Integer.MAX_VALUE, null, 0, true, null, null, 0, null);

    final Kind kind;
    final int lo, hi;
    /** Parity: unknown when {@code ptop}; else {@code pbit} (pbase null) or parity(pbase) xor pbit. */
    final Object pbase; final int pbit; final boolean ptop;
    final Object id;
    final Long lconst;
    final int nullness;
    final String string;
    /**
     * Evidence flags. {@code literal}: pushed by a constant instruction ({@code iconst}, {@code bipush}, {@code ldc},
     * {@code aconst_null}); a load from a local is never literal. {@code opaque}: computed in a way javac never writes (an
     * operation on literals, which javac folds; a read of a static final field proved constant, which javac inlines; a
     * pure call or string operation evaluated on constants; an algebraic identity such as {@code x - x}).
     */
    final boolean literal, opaque;

    private FV(Kind kind, int lo, int hi, Object pbase, int pbit, boolean ptop, Object id, Long lconst, int nullness, String string) {
        this(kind, lo, hi, pbase, pbit, ptop, id, lconst, nullness, string, false, false);
    }
    private FV(Kind kind, int lo, int hi, Object pbase, int pbit, boolean ptop, Object id, Long lconst, int nullness, String string,
               boolean literal, boolean opaque) {
        this.kind = kind; this.lo = lo; this.hi = hi; this.pbase = pbase; this.pbit = pbit; this.ptop = ptop; this.id = id;
        this.lconst = lconst; this.nullness = nullness; this.string = string; this.literal = literal; this.opaque = opaque;
    }
    FV flags(boolean literal, boolean opaque) {
        if (this.literal == literal && this.opaque == opaque) return this;
        return new FV(kind, lo, hi, pbase, pbit, ptop, id, lconst, nullness, string, literal, opaque);
    }
    /** A literal or an opaque value: a constant javac would not leave for run time. */
    boolean folded() { return literal || opaque; }

    // ------------------------------------------------------------------------------------------------- factories

    static FV constant(int value) { return new FV(Kind.INT, value, value, null, value & 1, false, null, null, 0, null); }
    /** An int in {@code [lo, hi]} (clamped to int), with a parity form and an id; a single value becomes a constant. */
    static FV range(long lo, long hi, Parity parity, Object id) {
        if (lo < Integer.MIN_VALUE || hi > Integer.MAX_VALUE || lo > hi) { lo = Integer.MIN_VALUE; hi = Integer.MAX_VALUE; }
        if (parity == null) parity = Parity.TOP;
        // A known parity tightens the bounds to values of that parity ([-1, 1] and even is 0).
        boolean collapsed = false;
        if (parity.constant() && lo < hi) {
            if ((lo & 1) != parity.bit()) lo++;
            if ((hi & 1) != parity.bit()) hi--;
            collapsed = lo == hi; // only a parity identity (x * (x + 1) is even) makes it one value: javac never writes that
        }
        if (lo == hi) return collapsed ? constant((int) lo).flags(false, true) : constant((int) lo);
        return new FV(Kind.INT, (int) lo, (int) hi, parity.base, parity.bit, parity.top, id, null, 0, null);
    }
    /** An unknown int produced by {@code producer} (its own parity form refers to itself). */
    static FV unknownInt(Object producer, long lo, long hi) { return range(lo, hi, producer == null ? Parity.TOP : new Parity(producer, 0, false), producer); }
    static FV longConstant(long value) { return new FV(Kind.LONG, 0, 0, null, 0, true, null, value, 0, null); }
    static FV ref(int nullness, Object id, String string) { return new FV(Kind.REF, 0, 0, null, 0, true, id, null, nullness, string); }

    /** The parity form of an int value. */
    record Parity(Object base, int bit, boolean top) {
        static final Parity TOP = new Parity(null, 0, true);
        static Parity bit(int bit) { return new Parity(null, bit & 1, false); }
        boolean constant() { return !top && base == null; }
        Parity flip(int bit) { return top ? this : new Parity(base, this.bit ^ (bit & 1), false); }
        /** Parity of a sum or a difference. */
        static Parity add(Parity a, Parity b) {
            if (a.top || b.top) return TOP;
            if (a.base == null) return new Parity(b.base, a.bit ^ b.bit, false);
            if (b.base == null) return new Parity(a.base, a.bit ^ b.bit, false);
            if (a.base == b.base) return bit(a.bit ^ b.bit);
            return TOP;
        }
        /** Parity of a product: the bitwise and of the operands' parities. */
        static Parity mul(Parity a, Parity b) {
            if (a.constant() && a.bit == 0 || b.constant() && b.bit == 0) return bit(0);
            if (a.constant()) return b;
            if (b.constant()) return a;
            if (a.top || b.top) return TOP;
            if (a.base == b.base) return a.bit == b.bit ? a : bit(0); // x*x has x's parity; x*(x+1) is even
            return TOP;
        }
        @Override public boolean equals(Object o) { return o instanceof Parity p && p.top == top && p.bit == bit && p.base == base; }
        @Override public int hashCode() { return Objects.hash(top, bit, System.identityHashCode(base)); }
    }
    Parity parity() { return kind != Kind.INT || ptop ? Parity.TOP : new Parity(pbase, pbit, false); }

    // ------------------------------------------------------------------------------------------------- queries

    boolean isConstant() { return kind == Kind.INT && lo == hi; }
    int value() { return lo; }
    boolean contains(long v) { return kind == Kind.INT && v >= lo && v <= hi; }
    boolean excludesZero() { return kind == Kind.INT && (lo > 0 || hi < 0 || !ptop && pbase == null && pbit == 1); }

    FV withInterval(long lo, long hi) {
        if (lo > hi) return null;
        if (lo == hi) return constant((int) lo);
        return new FV(kind, (int) lo, (int) hi, pbase, pbit, ptop, id, null, 0, null, literal, opaque);
    }
    FV withNullness(int nullness) { return new FV(kind, lo, hi, pbase, pbit, ptop, id, lconst, nullness, nullness == NULL ? null : string, literal, opaque); }
    /** The value without references to {@code producer} (it ran again, so its old values are not "the latest" any more). */
    FV forget(Object producer) {
        boolean dropId = id == producer, dropParity = pbase == producer;
        if (!dropId && !dropParity) return this;
        return new FV(kind, lo, hi, dropParity ? null : pbase, dropParity ? 0 : pbit, dropParity || ptop, dropId ? null : id, lconst, nullness, string, literal, opaque);
    }
    boolean mentions(Object producer) { return producer != null && (id == producer || pbase == producer); }

    FV merge(FV other) {
        if (this == other || equals(other)) return this;
        if (kind != other.kind) return UNINIT;
        var merged = mergeValue(other);
        return merged.kind == Kind.UNINIT ? merged : merged.flags(false, opaque && other.opaque); // a join is not one pushed literal
    }
    private FV mergeValue(FV other) {
        return switch (kind) {
            case INT -> {
                var p = parity(); var q = other.parity();
                var parity = p.equals(q) ? p : Parity.TOP;
                yield new FV(Kind.INT, Math.min(lo, other.lo), Math.max(hi, other.hi), parity.base, parity.bit, parity.top,
                    id == other.id ? id : null, null, 0, null);
            }
            case LONG -> Objects.equals(lconst, other.lconst) ? this : LONG_TOP;
            case REF -> new FV(Kind.REF, 0, 0, null, 0, true, id == other.id ? id : null, null, nullness == other.nullness ? nullness : UNKNOWN,
                Objects.equals(string, other.string) ? string : null);
            default -> this;
        };
    }
    /** The interval widened to every int (loops); parity and id kept. */
    FV widen() {
        if (kind != Kind.INT) return this;
        return new FV(Kind.INT, Integer.MIN_VALUE, Integer.MAX_VALUE, pbase, pbit, ptop, id, null, 0, null, false, opaque);
    }

    @Override public int getSize() { return kind == Kind.LONG || kind == Kind.DOUBLE ? 2 : 1; }
    @Override public boolean equals(Object o) {
        return o instanceof FV v && v.kind == kind && v.lo == lo && v.hi == hi && v.pbase == pbase && v.pbit == pbit && v.ptop == ptop
            && v.id == id && Objects.equals(v.lconst, lconst) && v.nullness == nullness && Objects.equals(v.string, string)
            && v.literal == literal && v.opaque == opaque;
    }
    @Override public int hashCode() { return Objects.hash(kind, lo, hi, pbit, ptop, lconst, nullness); }
    @Override public String toString() {
        return switch (kind) {
            case INT -> lo == hi ? "I=" + lo : "I[" + lo + "," + hi + "]" + (ptop ? "" : pbase == null ? "%" + pbit : "%p" + pbit) + (id != null ? "#" : "");
            case LONG -> lconst == null ? "J" : "J=" + lconst;
            case REF -> nullness == NULL ? "null" : nullness == NONNULL ? "R!" : "R";
            default -> kind.name();
        };
    }
}
