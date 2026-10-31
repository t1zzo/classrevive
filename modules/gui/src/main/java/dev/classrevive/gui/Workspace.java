package dev.classrevive.gui;

import dev.classrevive.intake.InventoryReader;
import dev.classrevive.intake.MultiRelease;
import dev.classrevive.model.*;
import dev.classrevive.pipeline.InputIdentifier;
import dev.classrevive.pipeline.JobListener;
import dev.classrevive.workbench.*;
import java.io.IOException;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.zip.ZipFile;

/**
 * Phase G3: one opened input and, once decompiled, its result. Opening only inventories the bytes (as {@code classrevive
 * inspect}); classes are never loaded or run. Not thread-confined: the GUI builds it on a background thread and then reads it
 * on the event thread, and {@link #attach} is called once a decompile finishes.
 */
public final class Workspace {
    /** A class of the input, by its own internal name ({@code a/b/C$D}) and archive entry. */
    public record ClassItem(String name, String entry, ClassInfo info) {
        public String simpleName() { return name.substring(name.lastIndexOf('/') + 1); }
        public String packageName() { int slash = name.lastIndexOf('/'); return slash < 0 ? "" : name.substring(0, slash).replace('/', '.'); }
        /** The enclosing class for {@code A$B} when {@code A} is in the input, else null. */
        public String outer(Set<String> names) {
            int slash = name.lastIndexOf('/'), dollar = name.lastIndexOf('$');
            if (dollar <= slash + 1 || dollar == name.length() - 1) return null;
            String outer = name.substring(0, dollar);
            return names.contains(outer) ? outer : null;
        }
    }

    /** A non-class entry, or a multi-release class variant kept aside like the pipeline does. */
    public record ResourceItem(String entry, long size, String sha256) {}

    static final int MAX_ENTRY_BYTES = 16 * 1024 * 1024, MAX_BYTECODE_CHARS = 2_000_000;

    private final Path input;
    private final Path inspected;
    private final boolean jar;
    private final InputIdentifier.Identification identification;
    private final Inventory inventory;
    private final List<ClassItem> classes;
    private final Map<String,ClassItem> byName;
    private final List<ResourceItem> resources;
    private volatile ResultIndex result;
    private volatile Map<String,String> portableByOriginal = Map.of();
    private String label;
    private ObfuscationCheck.Detection obfuscation;
    private volatile String reversedFrom;

    private Workspace(Path input, Path inspected, InputIdentifier.Identification identification, Inventory inventory) {
        this.input = input;
        this.inspected = inspected;
        this.jar = !inspected.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".class");
        this.identification = identification;
        this.inventory = inventory;
        var variants = new HashSet<String>();
        if (jar) MultiRelease.variants(inventory).forEach(variant -> variants.add(variant.entry()));
        var classes = new ArrayList<ClassItem>();
        var resources = new ArrayList<ResourceItem>();
        for (var entry : inventory.entries()) {
            if (entry.classInfo() != null && !variants.contains(entry.name())) classes.add(new ClassItem(entry.classInfo().name(), entry.name(), entry.classInfo()));
            else resources.add(new ResourceItem(entry.name(), entry.size(), entry.sha256()));
        }
        classes.sort(Comparator.comparing(ClassItem::name));
        resources.sort(Comparator.comparing(ResourceItem::entry));
        this.classes = List.copyOf(classes);
        var byName = new LinkedHashMap<String,ClassItem>();
        for (var item : classes) byName.put(item.name(), item);
        this.byName = Collections.unmodifiableMap(byName);
        this.resources = List.copyOf(resources);
    }

    /** Thrown when the input holds nothing ClassRevive can read; the message is for people. */
    public static final class Unreadable extends IOException {
        private final String details;
        Unreadable(String message, String details) { super(message); this.details = details; }
        public String details() { return details; }
    }

    /**
     * Inventories a {@code .jar} or {@code .class}. Any other file is identified from its bytes first (Phase 2CE); a jar
     * found inside it is copied, as data, into {@code cache} and inventoried from there.
     */
    public static Workspace openInput(Path file, Path cache) throws IOException {
        file = file.toAbsolutePath().normalize();
        if (!Files.isRegularFile(file)) throw new Unreadable(Text.t("error.notAFile", file.getFileName()), file.toString());
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        InputIdentifier.Identification identification = null;
        Path inspected = file;
        if (!name.endsWith(".jar") && !name.endsWith(".class")) {
            identification = InputIdentifier.identify(file);
            if (!identification.javaAvailable())
                throw new Unreadable(Text.t("error.noJava", file.getFileName(), identification.format()), identification.text());
            var resolved = InputIdentifier.resolve(file);
            Files.createDirectories(cache);
            inspected = cache.resolve(resolved.identification().sha256() + (resolved.jar() ? ".jar" : ".class"));
            if (!Files.exists(inspected)) Files.write(inspected, resolved.bytes());
        }
        var workspace = new Workspace(file, inspected, identification, inventory(inspected));
        workspace.obfuscation = detect(inspected);
        return workspace;
    }

    /** Opens a folder a decompile wrote (by this application or by {@code classrevive decompile}). */
    public static Workspace openResult(Path folder) throws IOException {
        var index = ResultIndex.open(folder);
        Path snapshot = Files.isRegularFile(index.output().resolve("input.jar")) ? index.output().resolve("input.jar") : index.output().resolve("input.class");
        if (!Files.isRegularFile(snapshot)) throw new Unreadable(Text.t("error.noSnapshot", folder.getFileName()), snapshot.toString());
        var workspace = new Workspace(snapshot, snapshot, null, inventory(snapshot));
        workspace.label = index.output().getFileName().toString();
        workspace.obfuscation = detect(snapshot);
        workspace.attach(index);
        return workspace;
    }

    /** Phase G5: the obfuscator the input points to, or null when the bytes could not be classified. */
    private static ObfuscationCheck.Detection detect(Path file) {
        try { return ObfuscationCheck.analyze(file); }
        catch (IOException | RuntimeException unreadable) { return null; }
    }

    private static Inventory inventory(Path file) throws IOException {
        var inventory = new InventoryReader().inspect(file, Limits.defaults());
        if (!inventory.successful()) {
            var details = new StringBuilder();
            for (var diagnostic : inventory.diagnostics())
                details.append(diagnostic.code()).append(" [").append(diagnostic.entry()).append("]: ").append(diagnostic.message()).append('\n');
            throw new Unreadable(Text.t("error.rejected", file.getFileName()), details.toString());
        }
        return inventory;
    }

    /**
     * Decompiles the input into a new folder under {@code root} and attaches the result. Interrupting the calling thread
     * cancels the job (its folder keeps what was written so far).
     */
    public ResultIndex decompile(DecompileRequest request, JobListener listener) throws IOException, InterruptedException {
        var decompiled = new DecompileService().decompile(request, listener);
        var index = ResultIndex.open(decompiled.output());
        attach(index);
        return index;
    }

    /** A request for this input with the settings' engines, timeout and stubs, into a new folder under the workspace directory. */
    public DecompileRequest request(GuiSettings settings, List<Path> dependencies) throws IOException {
        return request(settings.engines(), settings.timeoutSeconds(), settings.stubs(), dependencies, settings);
    }

    /** A request with explicit engines, time limit and stubs; the folder and engine cache still follow the settings. */
    public DecompileRequest request(List<dev.classrevive.engine.DecompilerEngine> engines, int timeoutSeconds, boolean stubs, List<Path> dependencies,
                                    GuiSettings settings) throws IOException {
        return new DecompileRequest(input, newOutput(settings.workspaceDirectory(), displayName()), engines,
            RequestLimits.timeoutSeconds(timeoutSeconds), dependencies, settings.engineCache() ? settings.engineCacheDirectory() : null, stubs);
    }

    /** {@code root/<name>-<yyyyMMdd-HHmmss>}, with {@code -2}, {@code -3}... when taken; the folder itself is not created. */
    static Path newOutput(Path root, String inputName) throws IOException {
        Files.createDirectories(root);
        String base = inputName.replaceAll("\\.(jar|class)$", "").replaceAll("[^A-Za-z0-9._-]", "_");
        if (base.isEmpty() || base.startsWith(".")) base = "input" + base;
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        Path candidate = root.resolve(base + "-" + stamp);
        for (int n = 2; Files.exists(candidate); n++) candidate = root.resolve(base + "-" + stamp + "-" + n);
        return candidate;
    }

    void attach(ResultIndex index) {
        var map = new HashMap<String,String>();
        for (String portable : index.classes().keySet()) map.put(index.originalName(portable), portable);
        portableByOriginal = Map.copyOf(map);
        result = index;
    }

    /** The decompile evidence for a class of the input, or empty before a decompile (or for a class it did not cover). */
    public Optional<ResultIndex.ClassEntry> evidence(String name) {
        var index = result;
        if (index == null) return Optional.empty();
        return Optional.ofNullable(index.classes().get(portableByOriginal.getOrDefault(name, name)));
    }

    /** The source of a class of the input in one candidate of the result. */
    public Optional<Path> source(String name, String candidate) {
        var index = result;
        if (index == null) return Optional.empty();
        return index.source(portableByOriginal.getOrDefault(name, name), candidate);
    }

    /** The input's name for a class of the result (the inverse of the portable renaming). */
    public String originalOf(String portable) {
        var index = result;
        return index == null ? portable : index.originalName(portable);
    }

    /** Phase G6: the result's name for a class of the input (portable when Phase 2BV renamed it), or the name itself. */
    public String portableOf(String original) { return portableByOriginal.getOrDefault(original, original); }

    /** Bytes of one archive entry (or of the class file), at most 16 MiB. */
    public byte[] entryBytes(String entry) throws IOException {
        if (!jar) return readBounded(Files.readAllBytes(inspected));
        try (var zip = new ZipFile(inspected.toFile())) {
            var item = zip.getEntry(entry);
            if (item == null) throw new IOException("No entry " + entry);
            try (var in = zip.getInputStream(item)) { return readBounded(in.readNBytes(MAX_ENTRY_BYTES + 1)); }
        }
    }

    private static byte[] readBounded(byte[] bytes) throws IOException {
        if (bytes.length > MAX_ENTRY_BYTES) throw new IOException("Entry larger than 16 MiB");
        return bytes;
    }

    /** ASM's text of the class, parsed in this process and never loaded. */
    public String bytecode(ClassItem item) throws IOException {
        return dev.classrevive.bytecode.ClassText.disassemble(entryBytes(item.entry()), MAX_BYTECODE_CHARS);
    }

    public Path input() { return input; }
    /** The file actually inventoried: the input, or the jar extracted from it (an .exe, a renamed zip). */
    public Path inspected() { return inspected; }
    public boolean jar() { return jar; }
    /** The input's file name, or the folder name of a result opened directly. */
    public String displayName() { return label != null ? label : input.getFileName().toString(); }
    public InputIdentifier.Identification identification() { return identification; }
    public Inventory inventory() { return inventory; }
    public List<ClassItem> classes() { return classes; }
    public Optional<ClassItem> classItem(String name) { return Optional.ofNullable(byName.get(name)); }

    private volatile Map<String,List<String>> bySimpleName;

    /** Phase G4: the input's classes whose own name (after the last {@code /} or {@code $}) is {@code simple}. */
    public List<String> classesNamed(String simple) {
        var index = bySimpleName;
        if (index == null) {
            var built = new HashMap<String,List<String>>();
            for (var item : classes) {
                String own = item.name().substring(Math.max(item.name().lastIndexOf('/'), item.name().lastIndexOf('$')) + 1);
                built.computeIfAbsent(own, k -> new ArrayList<>()).add(item.name());
            }
            built.replaceAll((k, v) -> List.copyOf(v));
            bySimpleName = index = Map.copyOf(built);
        }
        return index.getOrDefault(simple, List.of());
    }
    public Set<String> classNames() { return byName.keySet(); }
    public List<ResourceItem> resources() { return resources; }
    public ResultIndex result() { return result; }
    /** Phase G5: the obfuscator classification of this workspace's classes, or null. */
    public ObfuscationCheck.Detection obfuscation() { return obfuscation; }
    /** Phase G5: the original input's name when these classes are the Skidfuscator reversal of it, else null. */
    public String reversedFrom() { return reversedFrom; }
    void reversedFrom(String original) { reversedFrom = original; }
    public boolean decompiled() { return result != null; }
}
