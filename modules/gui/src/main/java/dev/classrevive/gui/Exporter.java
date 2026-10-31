package dev.classrevive.gui;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.zip.*;

/** Phase G3: copies of a result for the user; never overwrites anything and never follows links. */
public final class Exporter {
    private Exporter() {}

    /** Copies the tree under {@code source} into {@code target}, which must not exist or be an empty folder. Returns the file count. */
    public static int copyTree(Path source, Path target) throws IOException {
        if (!Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)) throw new NoSuchFileException(source.toString());
        if (Files.exists(target)) {
            if (!Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) throw new FileAlreadyExistsException(target.toString());
            try (var children = Files.list(target)) { if (children.findAny().isPresent()) throw new DirectoryNotEmptyException(target.toString()); }
        }
        if (target.toAbsolutePath().normalize().startsWith(source.toAbsolutePath().normalize())) throw new IOException("Target is inside the source");
        int[] files = {0};
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) throws IOException {
                Files.createDirectories(target.resolve(source.relativize(directory).toString()));
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                if (attributes.isRegularFile()) { Files.copy(file, target.resolve(source.relativize(file).toString()), StandardCopyOption.COPY_ATTRIBUTES); files[0]++; }
                return FileVisitResult.CONTINUE;
            }
        });
        return files[0];
    }

    /** Writes every regular file under {@code source} into a new zip {@code target}, with {@code /} separated paths. Returns the file count. */
    public static int zip(Path source, Path target) throws IOException {
        if (!Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)) throw new NoSuchFileException(source.toString());
        int[] files = {0};
        try (var zip = new ZipOutputStream(Files.newOutputStream(target, StandardOpenOption.CREATE_NEW))) {
            Files.walkFileTree(source, new SimpleFileVisitor<>() {
                @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                    if (!attributes.isRegularFile()) return FileVisitResult.CONTINUE;
                    zip.putNextEntry(new ZipEntry(source.relativize(file).toString().replace('\\', '/')));
                    Files.copy(file, zip);
                    zip.closeEntry();
                    files[0]++;
                    return FileVisitResult.CONTINUE;
                }
            });
        }
        return files[0];
    }
}
