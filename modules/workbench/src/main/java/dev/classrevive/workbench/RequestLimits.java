package dev.classrevive.workbench;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/** Limits every front end applies to a job before it starts; the messages are the CLI's. */
public final class RequestLimits {
    public static final int MIN_TIMEOUT_SECONDS = 1, MAX_TIMEOUT_SECONDS = 3600;
    /** The verifier's limit on explicit dependency JARs (Phase 3A). */
    public static final int MAX_DEPENDENCIES = 16;

    private RequestLimits() {}

    public static Duration timeoutSeconds(int seconds) { return timeout(Duration.ofSeconds(seconds)); }

    /** Per worker or compile attempt, not for the whole job. */
    public static Duration timeout(Duration timeout) {
        if (timeout.compareTo(Duration.ofSeconds(MIN_TIMEOUT_SECONDS)) < 0 || timeout.compareTo(Duration.ofSeconds(MAX_TIMEOUT_SECONDS)) > 0)
            throw new IllegalArgumentException("Timeout must be 1..3600 seconds");
        return timeout;
    }

    public static List<Path> dependencies(List<Path> dependencies) {
        if (dependencies.size() > MAX_DEPENDENCIES) throw new IllegalArgumentException("At most 16 dependency JARs");
        return List.copyOf(dependencies);
    }
}
