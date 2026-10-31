package dev.classrevive.dex;

import dev.classrevive.engine.*;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.stream.Stream;

/**
 * Phase 2CW: Dalvik bytecode (an APK's {@code classes*.dex}, or a dex file) to a jar of class files, through dex2jar in
 * {@link DexWorker}, a separate JVM under the usual worker limits. The rest of the pipeline then treats that jar as its
 * input: engines decompile it and javac's round trip is measured against it, so "identical" means identical to the
 * conversion, which is as faithful as dex2jar is. Methods it could not translate and dropped duplicate classes are listed.
 */
public final class DexConversion {
    private DexConversion() {}

    public static final String TOOL = "dex2jar";
    public static final String VERSION = "2.4.38";
    /** The worker's own class path in tests and development: dex2jar's jars, joined with the path separator. */
    static final String CLASSPATH_PROPERTY = "classrevive.dex2jar.classpath";

    /** A method dex2jar could not translate: its class keeps it, with a body that throws. */
    public record FailedMethod(String className, String method, String error) {}
    /** A class defined again in a later dex: the first one is kept, as the runtime does. */
    public record Duplicate(String className, String keptFrom, String droppedFrom) {}
    /** One dex file of the input, in load order, and how many of its classes were kept. */
    public record DexFile(String name, long bytes, int classes) {}

    /**
     * {@code jar} is null when nothing was converted; {@code worker} says why. {@code dalvik}: the input's dex files alone,
     * in load order (Phase 2CX), or null. {@code movedParameters}: methods whose
     * reused parameter registers were moved to locals; {@code syntheticFields}: outer-instance and captured-variable fields
     * given ACC_SYNTHETIC back; {@code topLevelClasses}: anonymous or local classes created outside their method, now
     * top-level; {@code shownSyntheticMethods}: synthetic methods called from other classes, no longer synthetic
     * ({@link DexNormalizer}).
     */
    public record Result(Path jar, Path dalvik, List<DexFile> dexFiles, List<FailedMethod> failedMethods, List<Duplicate> duplicates,
                         List<String> errors, long failedMethodCount, long duplicateCount, int movedParameters, int syntheticFields,
                         int topLevelClasses, int shownSyntheticMethods, EngineResult worker) {
        public Result {
            dexFiles = List.copyOf(dexFiles); failedMethods = List.copyOf(failedMethods); duplicates = List.copyOf(duplicates);
            errors = List.copyOf(errors);
        }
        public int classes() { return dexFiles.stream().mapToInt(DexFile::classes).sum(); }
    }

    /** Converts {@code input} into {@code directory} (which must not exist): {@code directory/transformed/converted.jar}. */
    public static Result convert(Path input, Path directory, Duration timeout) throws IOException {
        var worker = new ProcessRunner().runTransform(engine(), input, directory, timeout);
        Path out = directory.resolve("transformed");
        var dexFiles = new ArrayList<DexFile>();
        var failed = new ArrayList<FailedMethod>();
        var duplicates = new ArrayList<Duplicate>();
        var errors = new ArrayList<String>();
        long failedCount = 0, duplicateCount = 0;
        int movedParameters = 0, syntheticFields = 0, topLevelClasses = 0, shownSyntheticMethods = 0;
        Path log = out.resolve("conversion.tsv");
        if (Files.isRegularFile(log, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.size(log) > 16L * 1024 * 1024) throw new IOException("Conversion log exceeds limit");
            for (String line : Files.readAllLines(log, StandardCharsets.UTF_8)) {
                String[] field = line.split("\t", -1);
                switch (field[0]) {
                    case "dex" -> { if (field.length == 4) dexFiles.add(new DexFile(field[1], Long.parseLong(field[2]), Integer.parseInt(field[3]))); }
                    case "method-failed" -> { if (field.length == 4) { failed.add(new FailedMethod(field[1], field[2], field[3])); failedCount++; } }
                    case "duplicate" -> { if (field.length == 4) { duplicates.add(new Duplicate(field[1], field[2], field[3])); duplicateCount++; } }
                    case "error" -> { if (field.length == 3) errors.add(field[1] + ": " + field[2]); }
                    case "normalized" -> {
                        if (field.length == 3 && field[1].equals("parameters")) movedParameters = Integer.parseInt(field[2]);
                        if (field.length == 3 && field[1].equals("synthetic-fields")) syntheticFields = Integer.parseInt(field[2]);
                        if (field.length == 3 && field[1].equals("top-level-classes")) topLevelClasses = Integer.parseInt(field[2]);
                        if (field.length == 3 && field[1].equals("synthetic-methods-shown")) shownSyntheticMethods = Integer.parseInt(field[2]);
                    }
                    case "truncated" -> {
                        if (field.length == 3 && field[1].equals("method-failed")) failedCount = Long.parseLong(field[2]);
                        if (field.length == 3 && field[1].equals("duplicate")) duplicateCount = Long.parseLong(field[2]);
                    }
                    default -> { }
                }
            }
        }
        Path jar = out.resolve("converted.jar");
        boolean produced = worker.status() == EngineResult.Status.OUTPUT_PRODUCED && Files.isRegularFile(jar, LinkOption.NOFOLLOW_LINKS);
        Path dalvik = out.resolve("dalvik.zip");
        return new Result(produced ? jar : null, produced && Files.isRegularFile(dalvik, LinkOption.NOFOLLOW_LINKS) ? dalvik : null, dexFiles, failed, duplicates, errors, failedCount, duplicateCount, movedParameters,
            syntheticFields, topLevelClasses, shownSyntheticMethods, worker);
    }

    private static DecompilerEngine engine() {
        return new DecompilerEngine() {
            public String id() { return TOOL; }
            public String version() { return VERSION; }
            public List<String> command(Path input, Path output) {
                var command = new ArrayList<String>();
                command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
                command.addAll(List.of("-Xmx256m", "-XX:MaxMetaspaceSize=128m", "-XX:ActiveProcessorCount=2", "-Dfile.encoding=UTF-8",
                    "-cp", classPath(), DexWorker.class.getName(), input.toString(), output.toString()));
                return EngineCommand.sizedHeap(input, command);
            }
        };
    }

    /**
     * This module's own classes plus dex2jar's jars: from {@value #CLASSPATH_PROPERTY}, or from the {@code dex2jar} folder
     * next to this module's jar (the distribution's {@code lib/dex2jar}).
     */
    static String classPath() {
        try {
            Path self = Path.of(DexConversion.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            var paths = new ArrayList<String>();
            paths.add(self.toString());
            String configured = System.getProperty(CLASSPATH_PROPERTY);
            if (configured != null && !configured.isBlank()) {
                for (String path : configured.split(File.pathSeparator)) if (!path.isBlank()) paths.add(path);
            } else {
                Path folder = self.resolveSibling("dex2jar");
                if (!Files.isDirectory(folder)) throw new IllegalStateException("dex2jar is not installed next to " + self.getFileName() + " (expected " + folder + ")");
                try (Stream<Path> jars = Files.list(folder)) {
                    jars.filter(path -> path.getFileName().toString().endsWith(".jar")).sorted().forEach(path -> paths.add(path.toString()));
                }
            }
            return String.join(File.pathSeparator, paths);
        } catch (Exception error) {
            throw new IllegalStateException("Cannot locate the dex2jar worker class path", error);
        }
    }
}
