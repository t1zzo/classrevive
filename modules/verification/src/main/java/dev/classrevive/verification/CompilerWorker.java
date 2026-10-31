package dev.classrevive.verification;

import java.nio.file.*;
import java.util.*;
import javax.tools.*;

/** Runs only the JDK compiler; never loads target classes or annotation processors. */
public final class CompilerWorker {
    /** javac's message for {@code compiler.err.doesnt.exist}; the package is reported as a likely missing dependency. */
    private static final java.util.regex.Pattern MISSING_PACKAGE =
        java.util.regex.Pattern.compile("package ([\\p{L}_$][\\p{L}\\p{N}_$]*(?:\\.[\\p{L}_$][\\p{L}\\p{N}_$]*)*) does not exist");
    public static void main(String[] args) throws Exception {
        Path sources = Path.of(args[0]), output = Path.of(args[1]);
        var compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) throw new IllegalStateException("A JDK is required for verification");
        var files = sourceFiles(sources);
        Path empty = Files.createDirectory(output.resolve("empty-classpath"));
        var dependencies = new ArrayList<Path>();
        for (int i = 3; i < args.length; i++) dependencies.add(Path.of(args[i]));
        // Errors before the first ENTER event are syntax errors: javac then stops before attribution, so a single
        // unparsable file hides every other error and no class is generated. Per-file counts guide hybrid selection.
        int[] errors = {0}, syntaxErrors = {0};
        boolean[] entered = {false};
        var fileErrors = new TreeMap<String,Integer>();
        var missingPackages = new TreeMap<String,Integer>(); // "package X does not exist": likely a dependency not on the classpath
        var diagnostics = new StringBuilder(); int[] located = {0};
        var relative = new HashMap<Path,String>();
        for (var file : files) relative.put(file.toPath().toAbsolutePath().normalize(), sources.relativize(file.toPath()).toString().replace('\\', '/'));
        boolean success = false;
        var runtimeTypes = new TreeSet<String>();
        try (var manager = compiler.getStandardFileManager(null, Locale.ROOT, java.nio.charset.StandardCharsets.UTF_8)) {
            JdkRuntimeClasses.configure(manager, args[2], dependencies, empty);
            if (!files.isEmpty()) {
                var task = (com.sun.source.util.JavacTask) compiler.getTask(null, manager, diagnostic -> {
                    if (diagnostic.getKind() == Diagnostic.Kind.ERROR) {
                        errors[0]++;
                        if (!entered[0]) syntaxErrors[0]++;
                        var uri = diagnostic.getSource() == null ? null : diagnostic.getSource().toUri();
                        String name = uri == null || !"file".equals(uri.getScheme()) ? null : relative.get(Path.of(uri).toAbsolutePath().normalize());
                        if (name != null) {
                            fileErrors.merge(name, 1, Integer::sum);
                            if (located[0]++ < MAX_DIAGNOSTICS) diagnostics.append(SourceRepairWorker.escape(name)).append('\t')
                                .append(position(diagnostic.getLineNumber())).append('\t').append(position(diagnostic.getColumnNumber()))
                                .append('\t').append(SourceRepairWorker.escape(String.valueOf(diagnostic.getCode()))).append('\t')
                                .append(SourceRepairWorker.escape(clip(diagnostic.getMessage(Locale.ROOT)))).append('\n');
                        }
                        if ("compiler.err.doesnt.exist".equals(diagnostic.getCode())) {
                            var missing = MISSING_PACKAGE.matcher(diagnostic.getMessage(Locale.ROOT));
                            if (missing.find() && (missingPackages.size() < 50 || missingPackages.containsKey(missing.group(1)))) missingPackages.merge(missing.group(1), 1, Integer::sum);
                        }
                    }
                    System.out.println(diagnostic.getCode() + ": " + diagnostic.getMessage(Locale.ROOT));
                }, List.of("--release", args[2], "-proc:none", "-implicit:none", "-encoding", "UTF-8",
                    "-sourcepath", empty.toString(), "-d", output.toString(),
                    "-Xmaxerrs", "1000"), null, manager.getJavaFileObjectsFromFiles(files));
                task.addTaskListener(new com.sun.source.util.TaskListener() {
                    @Override public void started(com.sun.source.util.TaskEvent event) {
                        if (event.getKind() == com.sun.source.util.TaskEvent.Kind.ENTER) entered[0] = true;
                    }
                });
                success = task.call();
            }
        }
        if (success && JdkRuntimeClasses.applies(args[2])) runtimeTypes.addAll(JdkRuntimeClasses.used(compiler, output, dependencies, empty, 50));
        var result = new Properties();
        result.setProperty("compiled", Boolean.toString(success));
        result.setProperty("errors", Integer.toString(errors[0]));
        result.setProperty("syntax-errors", Integer.toString(syntaxErrors[0]));
        var missing = new StringJoiner(",");
        missingPackages.forEach((name, count) -> missing.add(name + ":" + count));
        result.setProperty("missing-packages", missing.toString());
        result.setProperty("jdk-runtime-types", String.join(",", runtimeTypes));
        try (var stream = Files.newOutputStream(output.getParent().resolve("compile.properties"), StandardOpenOption.CREATE_NEW)) {
            result.store(stream, "Compiler result; no target execution");
        }
        var lines = new StringBuilder();
        fileErrors.forEach((name, count) -> lines.append(count).append('\t').append(name).append('\n'));
        Files.writeString(output.getParent().resolve("file-errors.txt"), lines, java.nio.charset.StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        Files.writeString(output.getParent().resolve("diagnostics.tsv"), diagnostics, java.nio.charset.StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
    }
    /** Located errors kept as failure evidence (file, line, column, code, message); javac itself stops at 100. */
    static final int MAX_DIAGNOSTICS = 100, MAX_MESSAGE = 500;
    private static long position(long value) { return value == Diagnostic.NOPOS ? -1 : value; }
    private static String clip(String message) {
        String text = message == null ? "" : message;
        return text.length() <= MAX_MESSAGE ? text : text.substring(0, MAX_MESSAGE) + "...";
    }
    /** The candidate's .java files in path order, within the source tree limits; links are refused. */
    static List<java.io.File> sourceFiles(Path sources) throws java.io.IOException {
        var files = new ArrayList<java.io.File>();
        long bytes = 0;
        try (var paths = Files.walk(sources)) {
            var iterator = paths.iterator(); int visited = 0;
            while (iterator.hasNext()) {
                var path = iterator.next();
                if (++visited > 20_001 || Files.isSymbolicLink(path)) throw new IllegalArgumentException("Unsafe source tree");
                // A module descriptor is not compiled with ordinary sources (decompilers often print it as invalid Java).
                if (Files.isRegularFile(path) && path.toString().endsWith(".java") && !path.getFileName().toString().equals("module-info.java")) {
                    long size = Files.size(path); bytes += size;
                    if (size > 16L * 1024 * 1024 || bytes > 128L * 1024 * 1024) throw new IllegalArgumentException("Source size limit");
                    files.add(path.toFile());
                }
            }
        }
        files.sort(Comparator.comparing(java.io.File::getPath));
        return files;
    }
}
