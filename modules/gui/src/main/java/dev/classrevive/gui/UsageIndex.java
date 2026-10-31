package dev.classrevive.gui;

import dev.classrevive.bytecode.ReferenceIndex;
import dev.classrevive.workbench.ResultIndex;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;
import java.util.zip.ZipFile;
import javax.swing.SwingUtilities;

/**
 * Phase G6: the reference index of one workspace, built in the background on first use. After a decompile it reads the
 * classes the engines saw ({@code classes.jar}: portable names, and the reversed classes when Phase 2DD applied a
 * reversal), so its names match the decompiled sources; before, it reads the input's own classes. Either way the bytes
 * are only parsed. A newer result makes the next request rebuild; {@link #cancel} stops a build.
 */
final class UsageIndex {
    static final ReferenceIndex.Limits LIMITS = ReferenceIndex.Limits.defaults();

    /**
     * A finished index and what it cost. {@code requestedFor}: the result current when it was built (null before a
     * decompile); {@code basedOn}: that result when its {@code classes.jar} was read, null when the input's classes were.
     */
    record Built(ReferenceIndex index, ResultIndex requestedFor, ResultIndex basedOn, long millis) {}

    private final Workspace workspace;
    private Built built;
    private Thread building;
    private java.util.concurrent.atomic.AtomicBoolean cancelled = new java.util.concurrent.atomic.AtomicBoolean();
    private ResultIndex buildingFor;
    private final List<Consumer<Built>> waiting = new ArrayList<>();
    private final List<Consumer<Throwable>> failing = new ArrayList<>();

    UsageIndex(Workspace workspace) { this.workspace = workspace; }

    /** On the event thread: {@code ready} gets the index for the current result, now or once built; {@code failed} otherwise. */
    void whenReady(Consumer<Built> ready, Consumer<Throwable> failed) {
        var result = workspace.result();
        if (built != null && built.requestedFor() == result) { ready.accept(built); return; }
        waiting.add(ready);
        failing.add(failed);
        if (building != null && buildingFor == result) return;
        if (building != null) { cancelled.set(true); building.interrupt(); }
        var flag = new java.util.concurrent.atomic.AtomicBoolean();
        cancelled = flag;
        buildingFor = result;
        building = Thread.ofPlatform().daemon().name("classrevive-usages").start(() -> {
            try {
                var made = build(workspace, result, flag::get);
                SwingUtilities.invokeLater(() -> finish(result, made, null));
            } catch (Throwable failure) {
                SwingUtilities.invokeLater(() -> finish(result, null, failure));
            }
        });
    }

    boolean building() { return building != null; }

    /** Stops a build; whoever waits is told it was cancelled. */
    void cancel() {
        if (building == null) return;
        cancelled.set(true);
        building.interrupt();
    }

    private void finish(ResultIndex result, Built made, Throwable failure) {
        if (result != buildingFor) return;
        building = null;
        var ready = List.copyOf(waiting);
        var failed = List.copyOf(failing);
        waiting.clear();
        failing.clear();
        if (made != null) { built = made; ready.forEach(consumer -> consumer.accept(made)); }
        else failed.forEach(consumer -> consumer.accept(failure));
    }

    /** The input's name for a class of the index (they differ when the index reads a result's renamed classes). */
    String treeName(Built built, String indexName) { return built.basedOn() == null ? indexName : workspace.originalOf(indexName); }

    /** The index's name for a class of the input. */
    String indexName(Built built, String treeName) { return built.basedOn() == null ? treeName : workspace.portableOf(treeName); }

    static Built build(Workspace workspace, ResultIndex result, java.util.function.BooleanSupplier cancelled) throws IOException, InterruptedException {
        long start = System.nanoTime();
        ReferenceIndex index;
        Path normalized = result == null ? null : result.output().resolve("classes.jar");
        if (normalized != null && Files.isRegularFile(normalized, LinkOption.NOFOLLOW_LINKS)) index = ReferenceIndex.ofJar(normalized, LIMITS, cancelled);
        else index = ofInput(workspace, cancelled);
        long millis = (System.nanoTime() - start) / 1_000_000;
        return new Built(index, result, normalized != null && Files.isRegularFile(normalized) ? result : null, millis);
    }

    /** The input's own classes, by the entries the tree lists (multi-release variants stay aside, as in the tree). */
    private static ReferenceIndex ofInput(Workspace workspace, java.util.function.BooleanSupplier cancelled) throws IOException, InterruptedException {
        var builder = new ReferenceIndex.Builder(LIMITS);
        if (!workspace.jar()) {
            if (Files.size(workspace.inspected()) <= LIMITS.maxClassBytes()) builder.add(Files.readAllBytes(workspace.inspected()));
            return builder.build();
        }
        try (var zip = new ZipFile(workspace.inspected().toFile())) {
            for (var item : workspace.classes()) {
                if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new InterruptedException("index cancelled");
                var entry = zip.getEntry(item.entry());
                if (entry == null || entry.getSize() > LIMITS.maxClassBytes()) continue;
                byte[] bytes;
                try (var in = zip.getInputStream(entry)) { bytes = in.readNBytes(LIMITS.maxClassBytes() + 1); }
                if (bytes.length > LIMITS.maxClassBytes()) continue;
                if (!builder.add(bytes)) break;
            }
        }
        return builder.build();
    }
}
