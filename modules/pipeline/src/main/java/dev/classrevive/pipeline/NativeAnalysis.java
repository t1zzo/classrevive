package dev.classrevive.pipeline;

import dev.classrevive.engine.EngineResult;
import dev.classrevive.engine.ProcessRunner;
import dev.classrevive.intake.InventoryReader;
import dev.classrevive.model.Limits;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.time.Duration;
import java.util.*;
import java.util.zip.ZipFile;

/**
 * Phase 2CC: static triage of the native libraries bundled in a jar, linking each native function to the Java
 * {@code native} method it implements. Libraries are extracted as data (bounded), their headers are read by
 * {@link NativeBinary}, and, when Ghidra is configured, each one is analyzed statically by {@link GhidraEngine} in its own
 * process. Nothing is loaded into this JVM, run, emulated or unpacked. This is triage, not recovery of native code as Java.
 */
public final class NativeAnalysis {
    public static final String NOT_CONFIGURED = "skipped (Ghidra not configured)";
    public static final String INTERPRETATION = "Static triage only: native libraries were extracted as data and read, never loaded or run. "
        + "Packing hints and packer names are hypotheses from section names, sizes, entropy and imports. A packed library shows Ghidra "
        + "mostly its unpacking stub; methods registered by JNI_OnLoad through RegisterNatives cannot be bound statically then.";
    static final Set<String> EXTENSIONS = Set.of(".dll", ".so", ".dylib", ".jnilib");
    static final int MAX_HEADER_LIBRARIES = 64;

    /**
     * @param ghidraHome {@code --ghidra-home}, or null to use {@code GHIDRA_INSTALL_DIR} from {@code environment}
     * @param maxLibraryBytes largest library extracted (the 16 MiB intake limit does not apply here)
     * @param maxLibraries libraries given to Ghidra, host platform first; the others get header triage only
     */
    public record Options(String ghidraHome, Map<String,String> environment, long maxLibraryBytes, int maxLibraries, Duration timeout,
                          int maxDecompiled, int maxStrings) {
        public Options {
            environment = Map.copyOf(environment);
            if (maxLibraryBytes < 1 || maxLibraryBytes > 512L * 1024 * 1024) throw new IllegalArgumentException("Native library limit must be 1 byte..512 MiB");
            if (maxLibraries < 1 || maxLibraries > 32) throw new IllegalArgumentException("Native library count must be 1..32");
            if (timeout.isNegative() || timeout.isZero() || timeout.compareTo(Duration.ofHours(2)) > 0) throw new IllegalArgumentException("Native timeout must be 1 second..2 hours");
            if (maxDecompiled < 0 || maxDecompiled > 256) throw new IllegalArgumentException("Decompiled functions must be 0..256");
            if (maxStrings < 0 || maxStrings > 10_000) throw new IllegalArgumentException("Strings must be 0..10000");
        }
        public static Options defaults(String ghidraHome, Map<String,String> environment) {
            return new Options(ghidraHome, environment, 64L * 1024 * 1024, 4, Duration.ofMinutes(15), 32, 2000);
        }
        public Options withLimits(long maxLibraryBytes, int maxLibraries, Duration timeout) {
            return new Options(ghidraHome, environment, maxLibraryBytes, maxLibraries, timeout, maxDecompiled, maxStrings);
        }
    }

    public record JavaNative(String owner, String name, String descriptor, boolean obfuscatedName) {
        public String key() { return owner + "#" + name + descriptor; }
    }
    /** {@code kind}: {@code EXPORT} (a {@code Java_*} symbol) or {@code TABLE} (a JNINativeMethod entry found statically). */
    public record Link(JavaNative method, String library, String kind, String symbol, String address) {}
    /**
     * {@code status}: {@code ANALYZED} (Ghidra export read), {@code GHIDRA_FAILED}, {@code HEADERS_ONLY} (Ghidra not
     * configured or over the library count), {@code SKIPPED_OVERSIZED}, {@code NOT_NATIVE} (no PE, ELF or Mach-O header).
     */
    public record Library(String entry, String directory, long size, String sha256, String status, NativeBinary.Headers headers,
                          NativeBinary.Packing packing, GhidraExport ghidra, EngineResult engine, List<String> notes) {}
    public record Result(String status, String ghidraVersion, List<Library> libraries, List<JavaNative> natives, List<Link> links,
                         Map<String,String> unresolved, String text, Map<String,Object> json) {}

    public Result run(Path jar, Path directory, Options options) throws IOException, InterruptedException {
        var problems = new ArrayList<String>();
        var ghidra = GhidraEngine.locate(options.ghidraHome(), options.environment(), problems);
        Files.createDirectory(directory);
        var natives = javaNatives(jar);
        var candidates = candidates(jar);
        var libraries = new ArrayList<Library>();
        Path scripts = null;
        int analyzed = 0, index = 0;
        boolean failed = false;
        try (var zip = new ZipFile(jar.toFile())) {
            for (var candidate : candidates) {
                index++;
                if (index > MAX_HEADER_LIBRARIES) {
                    libraries.add(new Library(candidate.name(), "", candidate.size(), "", "SKIPPED_LIMIT", null, null, null, null,
                        List.of("more than " + MAX_HEADER_LIBRARIES + " native libraries; not extracted")));
                    continue;
                }
                if (candidate.size() > options.maxLibraryBytes()) {
                    libraries.add(new Library(candidate.name(), "", candidate.size(), "", "SKIPPED_OVERSIZED", null, null, null, null,
                        List.of("declared size " + candidate.size() + " bytes is over the native limit of " + options.maxLibraryBytes() + " bytes (--native-max-mib)")));
                    continue;
                }
                String folder = String.format(Locale.ROOT, "%02d-%s", index, safe(candidate.name().replace('/', '_'), 80));
                Path libraryDirectory = Files.createDirectory(directory.resolve(folder));
                Path file = libraryDirectory.resolve(safe(baseName(candidate.name()), 100));
                byte[] bytes;
                try (var in = zip.getInputStream(zip.getEntry(candidate.name()))) {
                    bytes = readBounded(in, options.maxLibraryBytes());
                }
                if (bytes == null) {
                    libraries.add(new Library(candidate.name(), folder, candidate.size(), "", "SKIPPED_OVERSIZED", null, null, null, null,
                        List.of("actual size is over the native limit of " + options.maxLibraryBytes() + " bytes")));
                    continue;
                }
                Files.write(file, bytes, StandardOpenOption.CREATE_NEW);
                var headers = NativeBinary.parse(bytes);
                var packing = NativeBinary.packing(headers);
                var notes = new ArrayList<String>(headers.problems());
                String status = headers.known() ? "HEADERS_ONLY" : "NOT_NATIVE";
                GhidraExport export = null; EngineResult engine = null;
                if (headers.known() && ghidra.isPresent() && analyzed < options.maxLibraries()) {
                    analyzed++;
                    if (scripts == null) scripts = GhidraEngine.installScript(directory.resolve("ghidra-script"));
                    var run = ghidra(ghidra.get(), scripts, file, libraryDirectory, options, notes);
                    engine = run.engine(); export = run.export();
                    status = export != null ? "ANALYZED" : "GHIDRA_FAILED";
                    failed |= export == null;
                } else if (headers.known() && ghidra.isPresent()) {
                    notes.add("not given to Ghidra: over --native-max-libraries " + options.maxLibraries());
                }
                bytes = null;
                libraries.add(new Library(candidate.name(), folder, candidate.size(), sha256(file), status, headers, packing, export, engine, notes));
            }
        }
        var links = links(natives, libraries);
        var unresolved = unresolved(natives, links, libraries);
        String status = candidates.isEmpty() ? "NO_LIBRARIES" : ghidra.isEmpty() ? "SKIPPED_NOT_CONFIGURED"
            : analyzed == 0 ? "HEADERS_ONLY" : failed ? "PARTIAL" : "ANALYZED";
        String version = ghidra.map(GhidraEngine.Installation::version).orElse(null);
        for (var library : libraries) if (!library.directory().isEmpty()) writeLibrary(directory.resolve(library.directory()), library, links);
        String text = text(status, version, problems, libraries, natives, links, unresolved);
        var json = json(status, version, problems, libraries, natives, links, unresolved, text);
        Files.writeString(directory.resolve("summary.txt"), text + INTERPRETATION + "\n", StandardOpenOption.CREATE_NEW);
        EvidenceReport.write(directory.resolve("report.json"), json);
        return new Result(status, version, List.copyOf(libraries), natives, links, unresolved, text, json);
    }

    private record Candidate(String name, long size) {}

    /** Native library entries in archive order, then the host platform's first (extension, then architecture). */
    static List<Candidate> candidates(Path jar) throws IOException {
        var list = new ArrayList<Candidate>();
        try (var zip = new ZipFile(jar.toFile())) {
            var entries = zip.entries(); int visited = 0;
            var seen = new HashSet<String>();
            while (entries.hasMoreElements() && visited++ < 100_000) {
                var entry = entries.nextElement();
                if (entry.isDirectory() || !seen.add(entry.getName())) continue;
                if (nativeName(entry.getName())) list.add(new Candidate(entry.getName(), entry.getSize()));
            }
        }
        list.sort(Comparator.comparingInt((Candidate c) -> -hostScore(c.name())));
        return list;
    }

    static boolean nativeName(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        String base = baseName(lower);
        return EXTENSIONS.stream().anyMatch(lower::endsWith) || base.matches(".+\\.so(\\.[0-9]+)+");
    }

    static int hostScore(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT), arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        int score = 0;
        if (os.startsWith("windows") ? lower.endsWith(".dll") : os.contains("mac") ? lower.endsWith(".dylib") || lower.endsWith(".jnilib") : lower.contains(".so"))
            score += 2;
        List<String> tokens = arch.equals("amd64") || arch.equals("x86_64") ? List.of("x64", "amd64", "x86_64", "x86-64")
            : arch.equals("aarch64") || arch.equals("arm64") ? List.of("aarch64", "arm64") : List.of(arch);
        if (tokens.stream().anyMatch(lower::contains)) score += 1;
        return score;
    }

    /** Native methods of the jar's classes, read from the class headers by the inventory (nothing is loaded). */
    static List<JavaNative> javaNatives(Path jar) {
        var inventory = new InventoryReader().inspect(jar, Limits.defaults());
        var natives = new ArrayList<JavaNative>();
        for (var entry : inventory.entries()) {
            var info = entry.classInfo();
            if (info == null) continue;
            for (var method : info.methods())
                if ((method.access() & 0x0100) != 0)
                    natives.add(new JavaNative(info.name(), method.name(), method.descriptor(), JniNames.obfuscatedName(method.name())));
        }
        natives.sort(Comparator.comparing(JavaNative::key));
        return List.copyOf(natives);
    }

    private record GhidraRun(EngineResult engine, GhidraExport export) {}

    private static GhidraRun ghidra(GhidraEngine.Installation installation, Path scripts, Path file, Path libraryDirectory, Options options,
                                    List<String> notes) throws InterruptedException, IOException {
        int seconds = (int) Math.max(30, options.timeout().toSeconds() * 2 / 3);
        var result = ghidraRun(installation, scripts, file, libraryDirectory, "ghidra", true, seconds, options, options.timeout(), notes);
        var export = export(libraryDirectory.resolve("ghidra"), result, notes);
        if (export != null) return new GhidraRun(result, export);
        if (scriptFailed(libraryDirectory.resolve("ghidra"))) {
            // A post script that does not compile or throws fails the same way without analysis; no retry.
            notes.add("Ghidra post script failed (REPORT SCRIPT ERROR in ghidra/engine.log)");
            return new GhidraRun(result, null);
        }
        // Only when the full run produced nothing: import and export again without auto-analysis (headers, imports, exports).
        notes.add("Ghidra with analysis: " + result.status() + " (" + result.message() + "); retried with -noanalysis");
        var retry = ghidraRun(installation, scripts, file, libraryDirectory, "ghidra-noanalysis", false, seconds, options,
            options.timeout().compareTo(Duration.ofMinutes(10)) > 0 ? Duration.ofMinutes(10) : options.timeout(), notes);
        export = export(libraryDirectory.resolve("ghidra-noanalysis"), retry, notes);
        if (export == null) notes.add("Ghidra without analysis: " + retry.status() + " (" + retry.message() + "); see ghidra*/engine.log");
        return new GhidraRun(retry, export);
    }

    private static EngineResult ghidraRun(GhidraEngine.Installation installation, Path scripts, Path file, Path libraryDirectory, String run,
                                          boolean analysis, int seconds, Options options, Duration timeout, List<String> notes)
        throws InterruptedException, IOException {
        Path project = libraryDirectory.resolve(run + "-project");
        boolean temporary = !GhidraEngine.projectLocationAccepted(project);
        if (temporary) {
            project = Files.createTempDirectory("classrevive-ghidra-");
            notes.add("Ghidra project in a temporary folder: the output path has an element starting with '.', which Ghidra refuses");
        } else Files.createDirectory(project);
        try {
            var engine = new GhidraEngine(installation.home(), installation.version(), scripts, project, analysis, seconds, options.maxStrings(),
                analysis ? options.maxDecompiled() : 0, 64 * 1024);
            var result = new ProcessRunner().runNativeAnalysis(engine, file, libraryDirectory.resolve(run), timeout);
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            return result;
        } finally {
            deleteTree(project);
        }
    }

    /** Removes a project folder this run created (Ghidra normally deletes its contents already); links are not followed. */
    private static void deleteTree(Path root) {
        try (var walk = Files.walk(root)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        } catch (IOException | UncheckedIOException leftOver) {
            // A project file still locked by a killed process stays behind; it is inside the output or the temp folder.
        }
    }

    private static boolean scriptFailed(Path run) {
        Path log = run.resolve("engine.log");
        try { return Files.isRegularFile(log, LinkOption.NOFOLLOW_LINKS)
            && new String(Files.readAllBytes(log), StandardCharsets.ISO_8859_1).contains("REPORT SCRIPT ERROR"); }
        catch (IOException unreadable) { return false; }
    }

    private static GhidraExport export(Path run, EngineResult result, List<String> notes) throws IOException {
        Path json = run.resolve("native").resolve(GhidraEngine.EXPORT);
        if (result.status() != EngineResult.Status.OUTPUT_PRODUCED || !Files.isRegularFile(json, LinkOption.NOFOLLOW_LINKS)) return null;
        if (Files.size(json) > 16L * 1024 * 1024) { notes.add("Ghidra export over 16 MiB; ignored"); return null; }
        try { return GhidraExport.parse(Files.readString(json, StandardCharsets.UTF_8)); }
        catch (IllegalArgumentException | UncheckedIOException | java.nio.charset.MalformedInputException invalid) {
            notes.add("Ghidra export rejected: " + invalid.getMessage());
            return null;
        }
    }

    /** Exported names: Ghidra's when it ran, else ClassRevive's own header reader. */
    static List<String> exports(Library library) {
        if (library.ghidra() != null) return library.ghidra().exports().stream().map(GhidraExport.Export::name).toList();
        return library.headers() == null ? List.of() : library.headers().exports();
    }

    static List<Link> links(List<JavaNative> natives, List<Library> libraries) {
        var links = new ArrayList<Link>();
        for (var library : libraries) {
            var addresses = new HashMap<String,String>();
            if (library.ghidra() != null) library.ghidra().exports().forEach(e -> addresses.putIfAbsent(e.name(), e.address()));
            for (String symbol : exports(library)) {
                var decoded = JniNames.decode(symbol);
                if (decoded.isEmpty()) continue;
                for (var method : natives)
                    if (decoded.get().matches(method.owner(), method.name(), method.descriptor()))
                        links.add(new Link(method, library.entry(), "EXPORT", symbol, addresses.getOrDefault(symbol, "")));
            }
            if (library.ghidra() != null) for (var entry : library.ghidra().nativeMethodTables())
                for (var method : natives)
                    if (method.name().equals(entry.name()) && method.descriptor().equals(entry.descriptor()))
                        links.add(new Link(method, library.entry(), "TABLE", entry.function().isEmpty() ? entry.functionAddress() : entry.function(),
                            entry.functionAddress()));
        }
        return List.copyOf(links);
    }

    /** Why each native method without a link has none, from what the libraries show statically. */
    static Map<String,String> unresolved(List<JavaNative> natives, List<Link> links, List<Library> libraries) {
        var bound = new HashSet<String>(); links.forEach(link -> bound.add(link.method().key()));
        boolean onLoad = libraries.stream().anyMatch(l -> exports(l).stream().anyMatch(JniNames::onLoad));
        boolean packed = libraries.stream().anyMatch(l -> l.packing() != null && l.packing().packed());
        boolean read = libraries.stream().anyMatch(l -> l.headers() != null && l.headers().known());
        long sites = libraries.stream().filter(l -> l.ghidra() != null).mapToLong(l -> l.ghidra().registerNativesSites().size()).sum();
        boolean ghidra = libraries.stream().anyMatch(l -> l.ghidra() != null);
        String reason;
        if (!read) reason = "no bundled native library could be read (none bundled, oversized or not a PE/ELF/Mach-O file)";
        else if (onLoad && packed) reason = "registered dynamically by JNI_OnLoad (RegisterNatives); binary packed: the registration table is not visible statically";
        else if (onLoad && sites > 0) reason = "registered dynamically by JNI_OnLoad: " + sites + " RegisterNatives call site(s), table not recovered statically";
        else if (onLoad) reason = "registered dynamically by JNI_OnLoad (no Java_* export matches)" + (ghidra ? "; no JNINativeMethod table found statically" : "");
        else reason = "no Java_* export or JNINativeMethod table matches (the library may be loaded from outside the jar)";
        var result = new TreeMap<String,String>();
        for (var method : natives) if (!bound.contains(method.key())) result.put(method.key(), reason);
        return result;
    }

    private static void writeLibrary(Path directory, Library library, List<Link> links) throws IOException {
        var functions = new StringBuilder("# Phase 2CC native functions of " + NativeBinary.clean(library.entry()) + " (static; nothing was run)\n");
        var ghidra = library.ghidra();
        if (library.headers() != null) {
            functions.append("Format: ").append(library.headers().format()).append(' ').append(library.headers().machine()).append('\n');
            if (library.packing() != null) {
                functions.append("Packing: ").append(library.packing().verdict());
                if (!library.packing().suspectedPackers().isEmpty()) functions.append(" (").append(String.join(", ", library.packing().suspectedPackers())).append(", hypothesis)");
                functions.append('\n');
                library.packing().hints().forEach(hint -> functions.append("  - ").append(hint).append('\n'));
            }
        }
        if (ghidra == null) {
            functions.append("Exports (ClassRevive header reader):\n");
            for (String name : exports(library)) functions.append("  ").append(NativeBinary.clean(name)).append('\n');
        } else {
            functions.append("Ghidra ").append(ghidra.ghidraVersion()).append(": ").append(ghidra.language()).append(", ").append(ghidra.functionCount())
                .append(" functions").append(ghidra.analyzed() ? "" : " (no auto-analysis)").append(ghidra.analysisTimedOut() ? " (analysis timed out)" : "").append('\n');
            functions.append("Exports:\n");
            for (var export : ghidra.exports())
                functions.append("  ").append(NativeBinary.clean(export.name())).append(" @ ").append(export.address())
                    .append(export.function() ? "" : " (no function at this address after analysis)").append('\n');
            functions.append("JNI functions:\n");
            for (var function : ghidra.jniFunctions()) {
                functions.append("  ").append(NativeBinary.clean(function.name())).append(" @ ").append(function.address()).append(", ")
                    .append(function.size()).append(" bytes").append(function.createdByScript() ? " (disassembled by the post script)" : "")
                    .append(": ").append(NativeBinary.clean(function.signature())).append('\n');
                JniNames.decode(function.name()).ifPresent(m -> functions.append("    implements ").append(NativeBinary.clean(m.owner().replace('/', '.')))
                    .append('#').append(NativeBinary.clean(m.name())).append(m.argumentDescriptor() == null ? "" : NativeBinary.clean(m.argumentDescriptor())).append('\n'));
            }
            functions.append("RegisterNatives sites (JNIEnv slot 215 offset in JNI functions and their callees):\n");
            for (var site : ghidra.registerNativesSites())
                functions.append("  ").append(NativeBinary.clean(site.function())).append(" @ ").append(site.address()).append(": ")
                    .append(NativeBinary.clean(site.instruction())).append('\n');
            functions.append("JNINativeMethod table entries:\n");
            for (var entry : ghidra.nativeMethodTables())
                functions.append("  ").append(entry.entry()).append(": ").append(NativeBinary.clean(entry.name())).append(NativeBinary.clean(entry.descriptor()))
                    .append(" -> ").append(NativeBinary.clean(entry.function().isEmpty() ? entry.functionAddress() : entry.function())).append('\n');
            var strings = new StringBuilder();
            for (var text : ghidra.strings())
                strings.append(text.address()).append('\t').append(NativeBinary.clean(text.type())).append('\t').append(NativeBinary.clean(text.value())).append('\n');
            Files.writeString(directory.resolve("strings.txt"), strings, StandardOpenOption.CREATE_NEW);
            if (!ghidra.decompiled().isEmpty()) {
                Path pseudo = Files.createDirectory(directory.resolve("pseudo-c"));
                int n = 0;
                for (var function : ghidra.decompiled()) {
                    String name = String.format(Locale.ROOT, "%02d-%s.c", ++n, safe(function.name(), 100));
                    String body = function.completed()
                        ? "// Ghidra " + ghidra.ghidraVersion() + " pseudo-C of " + NativeBinary.clean(function.name()) + " @ " + function.address()
                            + " (static decompilation, not compilable, not verified)" + (function.truncated() ? "; truncated" : "") + "\n" + function.code()
                        : "// Ghidra could not decompile " + NativeBinary.clean(function.name()) + ": " + NativeBinary.clean(function.error()) + "\n";
                    Files.writeString(pseudo.resolve(name), body, StandardOpenOption.CREATE_NEW);
                }
            }
        }
        functions.append("Linked Java native methods:\n");
        for (var link : links) if (link.library().equals(library.entry()))
            functions.append("  ").append(NativeBinary.clean(link.method().key())).append(" <- ").append(link.kind()).append(' ')
                .append(NativeBinary.clean(link.symbol())).append(link.address().isEmpty() ? "" : " @ " + link.address()).append('\n');
        library.notes().forEach(note -> functions.append("Note: ").append(NativeBinary.clean(note)).append('\n'));
        Files.writeString(directory.resolve("functions.txt"), functions, StandardOpenOption.CREATE_NEW);
        EvidenceReport.write(directory.resolve("report.json"), libraryJson(library, links, true));
    }

    static String text(String status, String version, List<String> problems, List<Library> libraries, List<JavaNative> natives,
                       List<Link> links, Map<String,String> unresolved) {
        var out = new StringBuilder("Native analysis: ");
        out.append(switch (status) {
            case "SKIPPED_NOT_CONFIGURED" -> NOT_CONFIGURED + (problems.isEmpty() ? "" : "; " + String.join("; ", problems));
            case "NO_LIBRARIES" -> "no bundled native library";
            case "HEADERS_ONLY" -> "Ghidra " + version + " configured; no library given to it";
            case "PARTIAL" -> "Ghidra " + version + ", some libraries failed (see native/*/functions.txt)";
            default -> "Ghidra " + version + " (static)";
        }).append('\n');
        for (var library : libraries) {
            out.append("  ").append(NativeBinary.clean(library.entry())).append(": ");
            if (library.headers() == null || !library.headers().known()) {
                out.append(library.status()).append(library.notes().isEmpty() ? "" : " (" + NativeBinary.clean(library.notes().getFirst()) + ")").append('\n');
                continue;
            }
            out.append(library.headers().format()).append(' ').append(library.headers().machine()).append(", ").append(library.size()).append(" bytes, ");
            var packing = library.packing();
            out.append(packing.packed() ? "likely packed" + (packing.suspectedPackers().isEmpty() ? "" : " (" + String.join(", ", packing.suspectedPackers()) + ", hypothesis)")
                : "no packing detected").append('\n');
            if (packing.packed()) out.append("    hints: ").append(String.join("; ", packing.hints().stream().limit(6).toList())).append('\n');
            var exports = exports(library);
            long jni = exports.stream().filter(n -> JniNames.decode(n).isPresent()).count();
            boolean onLoad = exports.stream().anyMatch(JniNames::onLoad);
            out.append("    exports: ").append(exports.size()).append(" (").append(jni).append(" Java_*").append(onLoad ? ", JNI_OnLoad" : "").append(')');
            var ghidra = library.ghidra();
            if (ghidra != null) {
                out.append("; Ghidra: ").append(ghidra.functionCount()).append(" functions").append(ghidra.analysisTimedOut() ? " (analysis timed out)" : "")
                    .append(", ").append(ghidra.registerNativesSites().size()).append(" RegisterNatives site(s), ")
                    .append(ghidra.nativeMethodTables().size()).append(" JNINativeMethod entr").append(ghidra.nativeMethodTables().size() == 1 ? "y" : "ies")
                    .append(", ").append(ghidra.decompiled().stream().filter(GhidraExport.Decompiled::completed).count()).append(" pseudo-C");
                ghidra.exports().stream().filter(e -> JniNames.onLoad(e.name())).findFirst().ifPresent(e -> {
                    var function = ghidra.jniFunctions().stream().filter(f -> f.address().equals(e.address())).findFirst();
                    out.append("; JNI_OnLoad @ ").append(e.address()).append(function.isEmpty() ? " (no code decodable there)"
                        : function.get().createdByScript() ? " (" + function.get().size() + " bytes, disassembled only by the post script)" : " (" + function.get().size() + " bytes)");
                });
            } else if (library.engine() != null) out.append("; Ghidra: ").append(library.engine().status());
            out.append('\n');
        }
        long obfuscated = natives.stream().filter(JavaNative::obfuscatedName).count();
        var linked = new HashSet<String>(); links.forEach(l -> linked.add(l.method().key()));
        long exportLinks = links.stream().filter(l -> l.kind().equals("EXPORT")).map(l -> l.method().key()).distinct().count();
        long tableLinks = links.stream().filter(l -> l.kind().equals("TABLE")).map(l -> l.method().key()).distinct().count();
        out.append("Java native methods: ").append(natives.size()).append(" (").append(obfuscated).append(" with hex or non-identifier names): ")
            .append(exportLinks).append(" bound to Java_* exports, ").append(tableLinks).append(" to JNINativeMethod tables, ")
            .append(unresolved.size()).append(" unresolved").append('\n');
        if (!unresolved.isEmpty()) out.append("  unresolved: ").append(unresolved.values().iterator().next()).append('\n');
        if (obfuscated > 0) {
            long obfuscatedLinked = natives.stream().filter(m -> m.obfuscatedName() && linked.contains(m.key())).count();
            out.append("Native layer (2BZ hex-named natives): ").append(obfuscatedLinked).append('/').append(obfuscated).append(" bound statically");
            String reason = natives.stream().filter(m -> m.obfuscatedName() && !linked.contains(m.key())).map(m -> unresolved.get(m.key())).findFirst().orElse(null);
            if (reason != null) out.append("; the others ").append(reason);
            out.append('\n');
        }
        return out.toString();
    }

    private static Map<String,Object> json(String status, String version, List<String> problems, List<Library> libraries, List<JavaNative> natives,
                                           List<Link> links, Map<String,String> unresolved, String text) {
        var data = new TreeMap<String,Object>();
        data.put("schema_version", 1); data.put("status", status); data.put("ghidra_version", version); data.put("problems", problems);
        data.put("libraries", libraries.stream().map(l -> (Object) libraryJson(l, links, false)).toList());
        data.put("native_methods", natives.size());
        data.put("obfuscated_native_methods", (int) natives.stream().filter(JavaNative::obfuscatedName).count());
        data.put("links", links.stream().map(NativeAnalysis::linkJson).toList());
        var rows = new ArrayList<Object>();
        unresolved.forEach((method, reason) -> { if (rows.size() < 500) { var row = new TreeMap<String,Object>(); row.put("method", method); row.put("reason", reason); rows.add(row); } });
        data.put("unresolved", rows);
        data.put("unresolved_count", unresolved.size());
        data.put("summary", text);
        data.put("interpretation", INTERPRETATION);
        return data;
    }

    private static Object linkJson(Link link) {
        var row = new TreeMap<String,Object>();
        row.put("method", link.method().key()); row.put("library", link.library()); row.put("kind", link.kind());
        row.put("symbol", link.symbol()); row.put("address", link.address());
        return row;
    }

    private static Map<String,Object> libraryJson(Library library, List<Link> links, boolean full) {
        var row = new TreeMap<String,Object>();
        row.put("entry", library.entry()); row.put("directory", library.directory().isEmpty() ? null : "native/" + library.directory());
        row.put("size", library.size()); row.put("sha256", library.sha256()); row.put("status", library.status()); row.put("notes", library.notes());
        if (library.headers() != null) {
            var headers = library.headers();
            var h = new TreeMap<String,Object>();
            h.put("format", headers.format()); h.put("machine", headers.machine()); h.put("bits", headers.bits()); h.put("entry_point", headers.entryPoint());
            h.put("tls", headers.tls()); h.put("problems", headers.problems());
            h.put("exports", full ? headers.exports() : List.of()); h.put("export_count", headers.exports().size());
            h.put("import_count", headers.imports().size()); h.put("libraries", headers.neededLibraries());
            if (full) h.put("imports", headers.imports().stream().map(i -> (Object) (i.library().isEmpty() ? i.name() : i.library() + "!" + i.name())).toList());
            h.put("sections", headers.sections().stream().map(s -> {
                var section = new TreeMap<String,Object>(); section.put("name", s.name()); section.put("virtual_address", s.virtualAddress());
                section.put("virtual_size", s.virtualSize()); section.put("raw_size", s.rawSize()); section.put("permissions", s.permissions());
                section.put("entropy_millibits", s.entropyMillibits()); return (Object) section; }).toList());
            row.put("headers", h);
            var p = new TreeMap<String,Object>(); p.put("verdict", library.packing().verdict()); p.put("hints", library.packing().hints());
            p.put("suspected_packers", library.packing().suspectedPackers()); row.put("packing", p);
        }
        if (library.engine() != null) row.put("engine", EvidenceReport.engine(library.engine()));
        var ghidra = library.ghidra();
        if (ghidra != null) {
            var g = new TreeMap<String,Object>();
            g.put("version", ghidra.ghidraVersion()); g.put("format", ghidra.format()); g.put("language", ghidra.language()); g.put("compiler", ghidra.compiler());
            g.put("image_base", ghidra.imageBase()); g.put("analyzed", ghidra.analyzed()); g.put("analysis_timed_out", ghidra.analysisTimedOut());
            g.put("function_count", ghidra.functionCount()); g.put("import_count", ghidra.imports().size());
            g.put("exports", ghidra.exports().stream().map(e -> { var x = new TreeMap<String,Object>(); x.put("name", e.name()); x.put("address", e.address());
                x.put("function", e.function()); return (Object) x; }).limit(full ? 5000 : 50).toList());
            g.put("jni_functions", ghidra.jniFunctions().stream().map(f -> { var x = new TreeMap<String,Object>(); x.put("name", f.name()); x.put("address", f.address());
                x.put("size", f.size()); x.put("signature", f.signature()); x.put("created_by_script", f.createdByScript());
                if (full) x.put("calls", f.calls());
                return (Object) x; }).limit(full ? 5000 : 200).toList());
            g.put("register_natives_sites", ghidra.registerNativesSites().stream().map(s -> { var x = new TreeMap<String,Object>(); x.put("function", s.function());
                x.put("address", s.address()); x.put("instruction", s.instruction()); return (Object) x; }).toList());
            g.put("native_method_tables", ghidra.nativeMethodTables().stream().map(t -> { var x = new TreeMap<String,Object>(); x.put("entry", t.entry());
                x.put("name", t.name()); x.put("descriptor", t.descriptor()); x.put("function_address", t.functionAddress()); x.put("function", t.function());
                return (Object) x; }).toList());
            g.put("string_count", ghidra.strings().size());
            g.put("decompiled", ghidra.decompiled().stream().map(d -> (Object) d.name()).toList());
            if (full) {
                g.put("blocks", ghidra.blocks().stream().map(b -> { var x = new TreeMap<String,Object>(); x.put("name", b.name()); x.put("start", b.start());
                    x.put("size", b.size()); x.put("permissions", b.permissions()); x.put("initialized", b.initialized());
                    x.put("entropy_millibits", b.entropyMillibits()); return (Object) x; }).toList());
                g.put("imports", ghidra.imports().stream().map(i -> (Object) (i.library() + "!" + i.name())).toList());
            }
            row.put("ghidra", g);
        }
        row.put("links", links.stream().filter(l -> l.library().equals(library.entry())).map(NativeAnalysis::linkJson).limit(full ? 5000 : 200).toList());
        return row;
    }

    /**
     * Adds the result to an existing evidence pair: a section at the end of {@code text} (report.txt, obfuscation.txt) and
     * {@code native_analysis} in {@code json} (report.json, diagnostics.json), which is rewritten with the new field.
     */
    public static void attach(Path text, Path json, Result result) throws IOException {
        Files.writeString(text, "\n" + result.text() + "(details in native/summary.txt and native/*/functions.txt)\n",
            StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        if (Files.isRegularFile(json, LinkOption.NOFOLLOW_LINKS) && Json.decode(Files.readString(json, StandardCharsets.UTF_8)) instanceof Map<?,?> map) {
            var data = new TreeMap<String,Object>();
            map.forEach((key, value) -> data.put((String) key, value));
            data.put("native_analysis", result.json());
            Path next = json.resolveSibling(json.getFileName() + ".next");
            EvidenceReport.write(next, data);
            Files.move(next, json, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static byte[] readBounded(InputStream in, long limit) throws IOException {
        var out = new ByteArrayOutputStream();
        byte[] buffer = new byte[65536]; int read; long total = 0;
        while ((read = in.read(buffer)) != -1) {
            total += read;
            if (total > limit) return null;
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    private static String sha256(Path file) throws IOException {
        try (var in = Files.newInputStream(file)) {
            var digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[65536]; int read;
            while ((read = in.read(buffer)) != -1) digest.update(buffer, 0, read);
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    static String baseName(String entry) {
        int slash = entry.lastIndexOf('/');
        return slash < 0 ? entry : entry.substring(slash + 1);
    }

    /** A file name made of {@code [A-Za-z0-9._-]} only, at most {@code max} characters, never empty or dot-only. */
    static String safe(String name, int max) {
        String cleaned = name.replaceAll("[^A-Za-z0-9._-]", "_");
        if (cleaned.length() > max) cleaned = cleaned.substring(0, max);
        if (cleaned.isEmpty() || cleaned.chars().allMatch(c -> c == '.')) cleaned = "library";
        return cleaned;
    }
}
