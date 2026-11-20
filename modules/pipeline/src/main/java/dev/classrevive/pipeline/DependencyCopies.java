package dev.classrevive.pipeline;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Phase 2DW: every compile stage (each candidate, hybrid, repair, composition, the per-file round trip) copies the
 * dependency jars into its own {@code *-dependencies/} folder: the jar as given ({@code N-input.jar}) and its class-only
 * form ({@code N-classes.jar}). The stage reads them only while it compiles. On a large obfuscated jar with a dozen
 * dependencies they were most of the job's output.
 *
 * <p>Once every stage is done, those two kinds of file are removed. {@code dependencies.txt}, which names each jar by
 * SHA-256 with its class count, stays in every folder as the evidence. Only regular files with exactly those names, in
 * folders with that suffix, are touched; links are not followed.
 */
final class DependencyCopies {
    private static final Pattern COPY = Pattern.compile("\\d+-(input|classes)\\.jar");

    private DependencyCopies() {}

    record Removed(int folders, int files, long bytes) {}

    static Removed remove(Path output) throws IOException {
        var folders = new HashSet<Path>();
        int[] files = {0}; long[] bytes = {0};
        Files.walkFileTree(output, new SimpleFileVisitor<>() {
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                Path parent = file.getParent();
                if (attributes.isRegularFile() && parent != null && parent.getFileName().toString().endsWith("-dependencies")
                    && COPY.matcher(file.getFileName().toString()).matches()) {
                    long size = attributes.size();
                    Files.delete(file);
                    folders.add(parent); files[0]++; bytes[0] += size;
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return new Removed(folders.size(), files[0], bytes[0]);
    }

    /** report.json's record of the removal, or null when nothing was removed. */
    static Map<String,Object> json(Removed removed) {
        if (removed.files() == 0) return null;
        var data = new TreeMap<String,Object>();
        data.put("folders", removed.folders()); data.put("files", removed.files()); data.put("bytes", removed.bytes());
        data.put("kept", "dependencies.txt in each folder: every jar's SHA-256 and class count");
        return data;
    }

    /** report.txt's line, or empty when nothing was removed. */
    static String text(Removed removed) {
        if (removed.files() == 0) return "";
        return "Dependency jar copies removed after the job (Phase 2DW): " + removed.files() + " file(s) in " + removed.folders()
            + " *-dependencies folder(s), " + String.format(Locale.ROOT, "%.1f", removed.bytes() / 1048576.0)
            + " MiB; each folder keeps dependencies.txt (every jar's SHA-256 and class count).\n";
    }
}
