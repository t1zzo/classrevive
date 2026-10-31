package dev.classrevive.model;

import java.util.List;

/**
 * {@code diagnostics} reject the input; {@code notices} are informational (Phase 2BV: class entries that differ only in
 * letter case, resources dropped because their path collides by case with an entry already kept, and resources left
 * out unread because they declare more than the entry byte limit).
 */
public record Inventory(String input, String sha256, long inputBytes, long expandedBytes,
                        List<Entry> entries, List<Diagnostic> diagnostics, List<Diagnostic> notices) {
    public Inventory {
        entries = List.copyOf(entries);
        diagnostics = List.copyOf(diagnostics);
        notices = List.copyOf(notices);
    }
    public Inventory(String input, String sha256, long inputBytes, long expandedBytes, List<Entry> entries, List<Diagnostic> diagnostics) {
        this(input, sha256, inputBytes, expandedBytes, entries, diagnostics, List.of());
    }
    public boolean successful() { return diagnostics.isEmpty(); }
    public record Entry(String name, long size, String sha256, ClassInfo classInfo) {}
}
