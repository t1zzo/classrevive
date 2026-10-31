package dev.classrevive.pipeline;

import dev.classrevive.model.ClassInfo;
import dev.classrevive.verification.VerificationResult;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.ZipFile;

/**
 * Phase 2CG: the selected sources as a project an IDE opens directly, under {@code project/}: sources in
 * {@code src/main/java}, the input JAR's resources in {@code src/main/resources}, the {@code --classpath} JARs in
 * {@code lib/}, a {@code build.gradle} and {@code settings.gradle} for the originals' Java release, and a README that
 * says where the code came from. Resources are copied from the job's own snapshot of the input, entry by entry: names
 * that could leave the folder are refused, and counts and bytes are bounded. Nothing is executed.
 */
final class ProjectExport {
    private ProjectExport() {}
    static final int MAX_RESOURCES = 20_000;
    static final long MAX_RESOURCE_BYTES = 256L * 1024 * 1024;

    record Result(int sources, int resources, int skippedResources, int libraries, int release) {}

    static Result write(Path output, Path selected, Path snapshot, List<Path> dependencies, List<ClassInfo> originals,
                        VerificationResult verification, String inputName) throws IOException {
        Path project = Files.createDirectory(output.resolve("project"));
        Path java = Files.createDirectories(project.resolve("src/main/java"));
        int sources = 0;
        if (Files.isDirectory(selected, LinkOption.NOFOLLOW_LINKS)) {
            try (var paths = Files.walk(selected)) {
                for (var path : paths.filter(Files::isRegularFile).sorted().toList()) {
                    if (Files.isSymbolicLink(path)) throw new IOException("Unsafe selected sources");
                    Path target = java.resolve(selected.relativize(path).toString()).normalize();
                    if (!target.startsWith(java)) throw new IOException("Unsafe selected sources");
                    Files.createDirectories(target.getParent());
                    Files.copy(path, target);
                    if (path.toString().endsWith(".java")) sources++;
                }
            }
        }
        int resources = 0, skipped = 0;
        if (snapshot.getFileName().toString().endsWith(".jar")) {
            Path folder = Files.createDirectories(project.resolve("src/main/resources"));
            long bytes = 0;
            try (var zip = new ZipFile(snapshot.toFile())) {
                var entries = zip.entries();
                while (entries.hasMoreElements()) {
                    var entry = entries.nextElement();
                    String name = entry.getName();
                    if (entry.isDirectory() || name.endsWith(".class") || !resource(name)) continue;
                    if (!safe(name) || resources >= MAX_RESOURCES) { skipped++; continue; }
                    Path target = folder.resolve(name).normalize();
                    if (!target.startsWith(folder) || entry.getSize() > MAX_RESOURCE_BYTES - bytes) { skipped++; continue; }
                    Files.createDirectories(target.getParent());
                    // A resource that would pass the byte budget is left out (and its partial copy removed); the job goes on.
                    long before = bytes; boolean fits = true;
                    try (InputStream in = zip.getInputStream(entry); var out = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW)) {
                        byte[] buffer = new byte[8192]; int read;
                        while ((read = in.read(buffer)) != -1) {
                            bytes += read;
                            if (bytes > MAX_RESOURCE_BYTES) { fits = false; break; }
                            out.write(buffer, 0, read);
                        }
                    } catch (FileAlreadyExistsException duplicate) { skipped++; continue; } // e.g. names differing only by case
                    if (!fits) { Files.deleteIfExists(target); bytes = before; skipped++; continue; }
                    resources++;
                }
            }
        }
        var libraries = new ArrayList<String>();
        if (!dependencies.isEmpty()) {
            Path lib = Files.createDirectory(project.resolve("lib"));
            var used = new HashSet<String>();
            for (var dependency : dependencies) {
                String name = dependency.getFileName().toString();
                if (!safe(name) || name.contains("/") || !used.add(name)) name = "dependency-" + (libraries.size() + 1) + ".jar";
                Files.copy(dependency, lib.resolve(name), LinkOption.NOFOLLOW_LINKS);
                libraries.add(name);
            }
        }
        int release = Math.max(8, originals.stream().filter(info -> !info.name().equals("module-info"))
            .mapToInt(ClassInfo::javaVersion).max().orElse(8));
        Files.writeString(project.resolve("settings.gradle"), "rootProject.name = '" + projectName(inputName) + "'\n", StandardCharsets.UTF_8);
        // No toolchain block: options.release works with any installed JDK at or above the release, without downloads.
        var build = new StringBuilder("// Generated by ClassRevive from decompiled sources; see README.md.\nplugins {\n    id 'java'\n}\n\n")
            .append("tasks.withType(JavaCompile).configureEach {\n    options.release = ").append(release).append("\n    options.encoding = 'UTF-8'\n}\n");
        if (!libraries.isEmpty()) {
            build.append("\ndependencies {\n");
            for (var library : libraries) build.append("    implementation files('lib/").append(library).append("')\n");
            build.append("}\n");
        }
        Files.writeString(project.resolve("build.gradle"), build, StandardCharsets.UTF_8);
        Files.writeString(project.resolve("README.md"), readme(inputName, verification, sources, resources, skipped, libraries, release), StandardCharsets.UTF_8);
        return new Result(sources, resources, skipped, libraries.size(), release);
    }

    /** Resource entries worth keeping: not JAR signatures, the manifest, or multi-release class variants. */
    private static boolean resource(String name) {
        String upper = name.toUpperCase(Locale.ROOT);
        if (upper.equals("META-INF/MANIFEST.MF") || upper.startsWith("META-INF/VERSIONS/")) return false;
        return !(upper.startsWith("META-INF/") && (upper.endsWith(".SF") || upper.endsWith(".RSA") || upper.endsWith(".DSA") || upper.endsWith(".EC")));
    }
    /** A relative name with "/" separators and no empty, "." or ".." part, no drive, backslash or control character. */
    static boolean safe(String name) {
        if (name.isEmpty() || name.startsWith("/") || name.contains("\\") || name.contains(":") || name.length() > 1024) return false;
        for (int i = 0; i < name.length(); i++) if (name.charAt(i) < 0x20) return false;
        for (String part : name.split("/", -1)) if (part.isEmpty() || part.equals(".") || part.equals("..")) return false;
        return true;
    }
    private static String projectName(String inputName) {
        String base = inputName.replaceAll("(?i)\\.(jar|class)$", "");
        String cleaned = base.replaceAll("[^A-Za-z0-9._-]", "-");
        return cleaned.isEmpty() ? "decompiled" : cleaned + "-decompiled";
    }
    private static String readme(String inputName, VerificationResult verification, int sources, int resources, int skipped,
                                 List<String> libraries, int release) {
        var out = new StringBuilder("# ").append(projectName(inputName)).append("\n\n")
            .append("Decompiled by ClassRevive from `").append(inputName.replace("`", "'")).append("`. This is **not the original source**: ")
            .append("comments, formatting and some names were lost in compilation. Behavior was not verified.\n\n")
            .append("- Compilation check: ").append(verification.status()).append(verification.status() == VerificationResult.Status.COMPILED
                ? " (the sources recompiled with javac)" : " (" + verification.errors() + " javac error(s); see `../report.html`)").append('\n')
            .append("- Structural signatures: ").append(verification.matchedSymbols()).append('/').append(verification.expectedSymbols()).append('\n')
            .append("- Java sources: ").append(sources).append(" in `src/main/java`\n")
            .append("- Resources from the JAR: ").append(resources).append(" in `src/main/resources`")
            .append(skipped == 0 ? "" : " (" + skipped + " skipped: unsafe names, duplicates or over the limit)").append('\n')
            .append("- Libraries: ").append(libraries.isEmpty() ? "none given (pass `--classpath` to `decompile`)" : String.join(", ", libraries) + " in `lib/`").append('\n')
            .append(libraries.contains(DecompilationJob.STUB_JAR) ? "- `lib/" + DecompilationJob.STUB_JAR + "` holds **inferred stand-ins** for libraries that were "
                + "missing: they only let the code compile and do nothing at run time. Replace them with the real libraries.\n" : "")
            .append('\n')
            .append("## Open it\n\nOpen this folder in IntelliJ IDEA, Eclipse or VS Code (with the Java extension) and import it as a Gradle project. ")
            .append("It compiles with `options.release = ").append(release).append("`. Build it from a terminal with an installed Gradle:\n\n")
            .append("```\ngradle build\n```\n\nThe confidence of each class is in `../report.html`.\n");
        return out.toString();
    }
}
