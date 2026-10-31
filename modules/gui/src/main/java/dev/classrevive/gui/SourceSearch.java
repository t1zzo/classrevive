package dev.classrevive.gui;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Phase G3: plain-text search through a folder of decompiled sources, bounded and cancellable. */
public final class SourceSearch {
    /** {@code className}: internal name of the file's top-level class; {@code line} is 1-based. */
    public record Hit(String className, Path file, int line, String text) {}

    static final long MAX_FILE_BYTES = 8L * 1024 * 1024;
    static final int MAX_LINE_CHARS = 240;

    private SourceSearch() {}

    /** At most {@code limit} hits in {@code .java} files under {@code root}, in path order; stops early when {@code cancelled}. */
    public static List<Hit> search(Path root, String query, boolean matchCase, int limit, BooleanSupplier cancelled) throws IOException {
        if (query.isEmpty() || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) return List.of();
        String needle = matchCase ? query : query.toLowerCase(Locale.ROOT);
        var files = new ArrayList<Path>();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                if (attributes.isRegularFile() && file.getFileName().toString().endsWith(".java") && attributes.size() <= MAX_FILE_BYTES) files.add(file);
                return cancelled.getAsBoolean() ? FileVisitResult.TERMINATE : FileVisitResult.CONTINUE;
            }
        });
        files.sort(Comparator.naturalOrder());
        var hits = new ArrayList<Hit>();
        for (Path file : files) {
            if (cancelled.getAsBoolean() || hits.size() >= limit) break;
            String relative = root.relativize(file).toString().replace('\\', '/');
            String className = relative.substring(0, relative.length() - ".java".length());
            List<String> lines;
            try { lines = Files.readAllLines(file, StandardCharsets.UTF_8); }
            catch (IOException | java.io.UncheckedIOException unreadable) { continue; }
            for (int i = 0; i < lines.size() && hits.size() < limit; i++) {
                String line = lines.get(i);
                if ((matchCase ? line : line.toLowerCase(Locale.ROOT)).contains(needle)) {
                    String trimmed = line.strip();
                    hits.add(new Hit(className, file, i + 1, trimmed.length() > MAX_LINE_CHARS ? trimmed.substring(0, MAX_LINE_CHARS) + "…" : trimmed));
                }
            }
        }
        return hits;
    }
}
