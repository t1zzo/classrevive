package dev.classrevive.verification;

import dev.classrevive.verification.MethodRoundTrip.Outcome;
import java.util.List;

/** Per-method round-trip outcomes for one original class; selection uses the totals only to break structural ties. */
public record ClassRoundTrip(String className, List<MethodRoundTrip.MethodResult> methods) {
    public ClassRoundTrip {
        if (className == null || className.isBlank()) throw new IllegalArgumentException("className is required");
        methods = List.copyOf(methods);
    }
    public int identical() { return count(Outcome.IDENTICAL); }
    public int equivalent() { return count(Outcome.EQUIVALENT_AFTER_NORMALIZATION); }
    public int different() { return count(Outcome.DIFFERENT); }
    private int count(Outcome outcome) { return (int) methods.stream().filter(method -> method.outcome() == outcome).count(); }
}
