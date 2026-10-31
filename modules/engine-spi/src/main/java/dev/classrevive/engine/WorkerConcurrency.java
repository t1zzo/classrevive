package dev.classrevive.engine;

/**
 * How many worker JVMs one job runs at once. Each worker is capped at two processors and a 256 MiB heap, so half the
 * host processors, between 2 and 4, keeps the previous minimum of two while bounding memory to four workers.
 */
public final class WorkerConcurrency {
    public static final int MIN = 2, MAX = 4;
    private WorkerConcurrency() {}
    public static int limit() { return limit(Runtime.getRuntime().availableProcessors()); }
    static int limit(int processors) { return Math.max(MIN, Math.min(MAX, processors / 2)); }
}
