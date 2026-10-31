package dev.classrevive.model;

public record Limits(long maxInputBytes, int maxEntryBytes, long maxExpandedBytes, int maxEntries) {
    public Limits {
        if (maxInputBytes <= 0 || maxEntryBytes <= 0 || maxExpandedBytes <= 0 || maxEntries <= 0)
            throw new IllegalArgumentException("All limits must be positive");
    }
    public static Limits defaults() {
        return new Limits(256L * 1024 * 1024, 16 * 1024 * 1024, 512L * 1024 * 1024, 20_000);
    }
}
