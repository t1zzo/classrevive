package dev.classrevive.selection;

import java.util.*;

/**
 * Phase 2DG: which engine to try first, per source file, from the compiler fingerprint (Phase 2CJ), for the cases where
 * selection's own evidence cannot decide. Measured per file compiled alone against the original classes: on two Kotlin
 * jars (kotlin-stdlib 2.4.20 and a 991-class client) JADX leads on classes kotlinc wrote (406 of 671 and 86 of 322; the
 * next best 354 and 63), and Vineflower on Java classes, in those jars (32 of 32, 187 of 191) and on 19 of 22 javac
 * libraries. It only orders or proposes candidates: no engine is left out and every choice is still verified.
 *
 * @param kotlinFiles source files ({@code a/b/C.java}) whose top-level class carries {@code kotlin.Metadata}
 */
public record EnginePreference(Set<String> kotlinFiles, String reason) {
    public static final String KOTLIN_FIRST = "jadx";
    public static final String JAVA_FIRST = "vineflower";
    public static final EnginePreference NONE = new EnginePreference(Set.of(), "");

    public EnginePreference { kotlinFiles = Set.copyOf(kotlinFiles); }

    public boolean active() { return !kotlinFiles.isEmpty(); }
    /** The engine measured best for the language of {@code file}'s class. */
    public String preferred(String file) { return kotlinFiles.contains(file) ? KOTLIN_FIRST : JAVA_FIRST; }
    /**
     * The alternatives in the order to try them for {@code file} when their evidence ties: for a Kotlin file JADX first,
     * then by name; any other file by name, as before this phase.
     */
    public <V> List<Map.Entry<String,V>> order(String file, NavigableMap<String,V> alternatives) {
        var entries = new ArrayList<>(alternatives.entrySet());
        if (kotlinFiles.contains(file))
            entries.sort(Comparator.comparing((Map.Entry<String,V> entry) -> !entry.getKey().equals(KOTLIN_FIRST)).thenComparing(Map.Entry::getKey));
        return entries;
    }
}
