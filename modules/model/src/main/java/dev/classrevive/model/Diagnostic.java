package dev.classrevive.model;

public record Diagnostic(Code code, String entry, String message) {
    public enum Code {
        INVALID_INPUT, IO_ERROR, INVALID_ARCHIVE, UNSAFE_ENTRY, DUPLICATE_ENTRY,
        RESOURCE_LIMIT, INVALID_CLASS, UNSUPPORTED_CLASS_VERSION,
        /** Informational (Phase 2BV): class entries whose paths differ only in letter case; all are kept. */
        CASE_COLLISION,
        /** Informational (Phase 2BV): a resource or directory whose path collides by case with a kept entry; it is left out. */
        CASE_COLLISION_SKIPPED,
        /** Informational (Phase 2BV): a resource that declares more than the entry byte limit; it is left out unread. */
        OVERSIZED_RESOURCE_SKIPPED
    }
}
