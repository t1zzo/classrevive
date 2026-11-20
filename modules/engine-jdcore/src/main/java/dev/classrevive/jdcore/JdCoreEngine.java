package dev.classrevive.jdcore;

import dev.classrevive.engine.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.zip.ZipFile;

/**
 * Phase 2DO: JD-Core (java-decompiler/jd-core, GPL-3.0) as an optional engine. It is never bundled or downloaded: the
 * user names its jar with {@code CLASSREVIVE_JDCORE_JAR}, and only then may {@code --engines} ask for {@code jd-core}.
 * The jar is only read here (its entries and manifest); it runs in the worker JVM, with the same limits as the other
 * engines (sized heap, two processors, the job's timeout, the process-tree kill on cancel).
 */
public final class JdCoreEngine implements DecompilerEngine {
    public static final String ID = "jd-core";
    public static final String VARIABLE = "CLASSREVIVE_JDCORE_JAR";
    static final String DECOMPILER = "org/jd/core/v1/ClassFileToJavaSourceDecompiler.class";
    static final String WORKER = "dev.classrevive.jdcore.JdCoreWorker";

    private final Path jar;
    private final String version;

    private JdCoreEngine(Path jar, String version) { this.jar = jar; this.version = version; }

    /** The engine named by {@code CLASSREVIVE_JDCORE_JAR}, or empty when it is not set. A set but unusable jar fails. */
    public static Optional<JdCoreEngine> locate(Map<String,String> environment) {
        String value = environment.get(VARIABLE);
        if (value == null || value.isBlank()) return Optional.empty();
        try { return Optional.of(at(Path.of(value))); }
        catch (InvalidPathException invalid) { throw new IllegalArgumentException(VARIABLE + " is not a valid path"); }
    }

    /** Checks that {@code jar} is a JD-Core jar (by its entries, nothing is loaded) and reads its version from the manifest. */
    public static JdCoreEngine at(Path jar) {
        Path absolute = jar.toAbsolutePath().normalize();
        if (!Files.isRegularFile(absolute, LinkOption.NOFOLLOW_LINKS)) throw new IllegalArgumentException(VARIABLE + " does not name a jar file: " + absolute);
        try (var zip = new ZipFile(absolute.toFile())) {
            if (zip.getEntry(DECOMPILER) == null) throw new IllegalArgumentException(absolute + " is not a JD-Core jar (" + DECOMPILER + " missing)");
            String version = "unknown";
            var manifest = zip.getEntry("META-INF/MANIFEST.MF");
            if (manifest != null) try (var in = zip.getInputStream(manifest)) {
                var attributes = new java.util.jar.Manifest(in).getMainAttributes();
                String declared = attributes.getValue("JD-Core-Version");
                if (declared != null && !declared.isBlank()) version = declared.strip();
            }
            return new JdCoreEngine(absolute, version.replaceAll("[^A-Za-z0-9._-]", "_"));
        } catch (IOException unreadable) {
            throw new IllegalArgumentException(absolute + " cannot be read as a jar: " + unreadable.getMessage());
        }
    }

    public Path jar() { return jar; }
    @Override public String id() { return ID; }
    @Override public String version() { return version; }
    /** Phase 2DP: kept only when the result with it is no worse than without it (2DO measured both gains and losses). */
    @Override public boolean neverWorse() { return true; }

    @Override public List<String> command(Path input, Path output) {
        return EngineCommand.sizedHeap(input, EngineCommand.javaWorker(JdCoreEngine.class, WORKER, List.of(), List.of(jar), input.toString(), output.toString()));
    }
}
