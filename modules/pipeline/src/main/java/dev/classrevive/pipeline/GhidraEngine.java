package dev.classrevive.pipeline;

import dev.classrevive.engine.DecompilerEngine;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * Phase 2CC: Ghidra's headless analyzer as an optional native engine. Ghidra is never bundled or downloaded; it is found
 * through {@code --ghidra-home} or {@code GHIDRA_INSTALL_DIR}. It runs as its own process (analyzeHeadless) with a
 * throwaway project in the run directory, imports one library, analyzes it statically and runs the versioned post script.
 */
public final class GhidraEngine implements DecompilerEngine {
    public static final String SCRIPT = "ClassReviveNativeExport.java";
    public static final String EXPORT = "ghidra.json";

    private final Path home, scripts, project;
    private final String version;
    private final boolean analysis;
    private final int analysisSeconds, maxStrings, maxDecompiled, maxCBytes;

    GhidraEngine(Path home, String version, Path scripts, Path project, boolean analysis, int analysisSeconds, int maxStrings, int maxDecompiled,
                 int maxCBytes) {
        this.home = home; this.version = version; this.scripts = scripts; this.project = project; this.analysis = analysis; this.analysisSeconds = analysisSeconds;
        this.maxStrings = maxStrings; this.maxDecompiled = maxDecompiled; this.maxCBytes = maxCBytes;
    }

    /** A located installation: its folder, the launcher and the version from {@code Ghidra/application.properties}. */
    public record Installation(Path home, Path launcher, String version) {}

    /**
     * The installation named by {@code option} (which must be valid) or else by {@code GHIDRA_INSTALL_DIR}; empty when
     * neither is set. {@code problem} receives why a set GHIDRA_INSTALL_DIR was not usable.
     */
    public static Optional<Installation> locate(String option, Map<String,String> environment, List<String> problem) {
        if (option != null) {
            var found = installation(Path.of(option));
            if (found.isEmpty()) throw new IllegalArgumentException("--ghidra-home is not a Ghidra installation (support/" + launcherName() + " missing)");
            return found;
        }
        String variable = environment.get("GHIDRA_INSTALL_DIR");
        if (variable == null || variable.isBlank()) return Optional.empty();
        Optional<Installation> found;
        try { found = installation(Path.of(variable)); }
        catch (InvalidPathException invalid) { found = Optional.empty(); }
        if (found.isEmpty()) problem.add("GHIDRA_INSTALL_DIR does not point to a Ghidra installation");
        return found;
    }

    static Optional<Installation> installation(Path home) {
        home = home.toAbsolutePath().normalize();
        Path launcher = home.resolve("support").resolve(launcherName());
        if (!Files.isRegularFile(launcher)) return Optional.empty();
        String version = "unknown";
        Path properties = home.resolve("Ghidra").resolve("application.properties");
        if (Files.isRegularFile(properties)) {
            var values = new Properties();
            try (var in = Files.newInputStream(properties)) { values.load(in); version = values.getProperty("application.version", "unknown"); }
            catch (IOException | IllegalArgumentException unreadable) { version = "unknown"; }
        }
        return Optional.of(new Installation(home, launcher, version.replaceAll("[^A-Za-z0-9._-]", "_")));
    }

    static String launcherName() { return windows() ? "analyzeHeadless.bat" : "analyzeHeadless"; }
    static boolean windows() { return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows"); }

    /**
     * Ghidra refuses a project location with a path element that starts with {@code .} (GhidraURL.checkValidProjectPath),
     * so an output folder such as {@code .analysis/out} cannot hold the project; a new temporary folder is used then.
     */
    static boolean projectLocationAccepted(Path location) {
        for (Path element : location.toAbsolutePath().normalize()) if (element.toString().startsWith(".")) return false;
        return true;
    }

    /** Writes the versioned post script into {@code directory} (it is compiled by Ghidra, never by this JVM). */
    static Path installScript(Path directory) throws IOException {
        Files.createDirectories(directory);
        try (var in = GhidraEngine.class.getResourceAsStream("ghidra/" + SCRIPT)) {
            if (in == null) throw new IOException("Ghidra post script missing from the ClassRevive build");
            Files.copy(in, directory.resolve(SCRIPT));
        }
        return directory;
    }

    @Override public String id() { return analysis ? "ghidra" : "ghidra-noanalysis"; }
    @Override public String version() { return version; }

    /** Ghidra needs a JDK; without JAVA_HOME the launcher would ask on the console, so this JVM's own JDK is offered. */
    @Override public Map<String,String> environment() {
        if (System.getenv("JAVA_HOME") != null) return Map.of();
        Path javaHome = Path.of(System.getProperty("java.home"));
        return Files.isRegularFile(javaHome.resolve("bin").resolve(windows() ? "javac.exe" : "javac")) ? Map.of("JAVA_HOME", javaHome.toString()) : Map.of();
    }

    /**
     * {@code input} is the extracted library; {@code output} the folder the export goes to. The throwaway project is
     * deleted by Ghidra ({@code -deleteProject}); {@code -readOnly} keeps the import from being saved at all.
     */
    @Override public List<String> command(Path input, Path output) {
        var command = new ArrayList<String>(List.of(home.resolve("support").resolve(launcherName()).toString(),
            project.toString(), "classrevive", "-import", input.toString(), "-readOnly", "-deleteProject",
            "-scriptPath", scripts.toString(), "-postScript", SCRIPT, output.resolve(EXPORT).toString(),
            String.valueOf(maxStrings), String.valueOf(maxDecompiled), String.valueOf(maxCBytes), "-max-cpu", "2"));
        if (analysis) { command.add("-analysisTimeoutPerFile"); command.add(String.valueOf(analysisSeconds)); }
        else command.add("-noanalysis");
        if (windows()) for (String argument : command) {
            // analyzeHeadless.bat re-expands every argument through cmd.exe; these characters would change its meaning.
            if (argument.isEmpty() || argument.chars().anyMatch(c -> "\"%^&|<>!".indexOf(c) >= 0))
                throw new IllegalArgumentException("Path not supported by analyzeHeadless.bat: " + argument);
        }
        return command;
    }
}
