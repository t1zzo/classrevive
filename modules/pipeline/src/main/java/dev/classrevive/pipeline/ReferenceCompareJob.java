package dev.classrevive.pipeline;

import dev.classrevive.intake.InventoryReader;
import dev.classrevive.model.*;
import dev.classrevive.verification.*;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;

/**
 * Phase 2CD: measures a decompile result against a reference ("answer key") JAR, the unobfuscated original of the JAR
 * that was decompiled. Only javac runs (in the existing compiler workers); no class of either JAR is loaded or executed.
 *
 * <p>Two separate scores, never mixed:
 * <ul>
 *   <li><b>compiles + structure</b>: the selected sources compiled as a whole (and, if not, file by file) against the
 *   reference classes; reference signatures (classes, fields, methods with descriptors) the sources declare; reference
 *   method bodies the recompiled classes reproduce ({@link MethodRoundTrip}, identical or equivalent after
 *   normalization).</li>
 *   <li><b>readability</b>: reference string literals that appear verbatim in the sources, and source files of reference
 *   classes that still name a class the reference does not have (an obfuscator's helper, factory or runtime).</li>
 * </ul>
 */
public final class ReferenceCompareJob {
    public record Result(VerificationResult whole, PartialRoundTrip.Result perFile, int referenceBodies,
                         int literals, int literalsFound, int referenceFiles, int filesNamingForeignClasses,
                         List<String> foreignClasses) {
        public int bodiesRecovered() {
            if (whole.status() == VerificationResult.Status.COMPILED) return whole.roundTripIdentical() + whole.roundTripEquivalent();
            return perFile == null ? 0 : perFile.identical() + perFile.equivalent();
        }
    }

    public Result run(Path reference, Path decompileOutput, Path output, Duration timeout, List<Path> dependencies)
            throws IOException {
        reference = reference.toAbsolutePath().normalize(); decompileOutput = decompileOutput.toAbsolutePath().normalize();
        output = output.toAbsolutePath().normalize();
        Path sources = decompileOutput.resolve("selection").resolve("sources");
        if (!Files.isDirectory(sources, LinkOption.NOFOLLOW_LINKS)) throw new IOException("No selection/sources in the decompile output");
        if (output.getParent() == null || !Files.isDirectory(output.getParent())) throw new IOException("Output parent must exist");
        Files.createDirectory(output);
        var inventory = new InventoryReader().inspect(reference, Limits.defaults());
        if (!inventory.successful()) throw new IOException("Reference rejected: " + inventory.diagnostics().getFirst().code());
        var originals = inventory.entries().stream().map(Inventory.Entry::classInfo).filter(Objects::nonNull)
            .filter(info -> !info.name().startsWith("META-INF/")).toList();
        var referenceClasses = MethodRoundTrip.readJar(reference);
        var whole = new CompilerVerifier().verify(sources, output.resolve("whole"), originals, timeout, dependencies, reference);
        PartialRoundTrip.Result perFile = null;
        if (whole.status() != VerificationResult.Status.COMPILED)
            perFile = new PartialRoundTrip().measure(sources, output.resolve("per-file"), originals, timeout, dependencies, reference);

        // Readability: literals and foreign class names, read from the source text only.
        var texts = new TreeMap<String,String>();
        try (var paths = Files.walk(sources)) {
            for (Path path : paths.filter(p -> p.toString().endsWith(".java")).toList()) {
                if (Files.isSymbolicLink(path) || Files.size(path) > 16L * 1024 * 1024) throw new IOException("Unsafe source file");
                texts.put(sources.relativize(path).toString().replace('\\', '/'), Files.readString(path, StandardCharsets.UTF_8));
            }
        }
        String all = String.join("\n", texts.values());
        var literals = new TreeSet<String>();
        for (byte[] bytes : referenceClasses.values()) literals.addAll(literals(bytes));
        int found = 0;
        for (String literal : literals) if (all.contains(quote(literal))) found++;
        var inputClasses = MethodRoundTrip.readJar(decompileOutput.resolve("classes.jar")).keySet();
        var foreign = inputClasses.stream().filter(name -> !referenceClasses.containsKey(name) && !name.contains("$")).sorted().toList();
        int referenceFiles = 0, naming = 0;
        for (var entry : texts.entrySet()) {
            String top = entry.getKey().substring(0, entry.getKey().length() - ".java".length());
            if (!referenceClasses.containsKey(top)) continue;
            referenceFiles++;
            String text = entry.getValue();
            if (foreign.stream().anyMatch(name -> mentions(text, name))) naming++;
        }
        var result = new Result(whole, perFile, MethodRoundTrip.countBodies(referenceClasses), literals.size(), found,
            referenceFiles, naming, foreign);
        write(output, reference, decompileOutput, result, inventory.sha256());
        return result;
    }

    private static boolean mentions(String text, String internalName) {
        String dotted = internalName.replace('/', '.');
        int slash = internalName.lastIndexOf('/');
        String simple = internalName.substring(slash + 1);
        if (text.contains(dotted)) return true;
        String pkg = slash < 0 ? "" : internalName.substring(0, slash).replace('/', '.');
        // A simple name only counts when the file imports it or shares its package.
        return (text.contains("import " + dotted + ";") || (!pkg.isEmpty() && text.contains("package " + pkg + ";")))
            && java.util.regex.Pattern.compile("\\b" + java.util.regex.Pattern.quote(simple) + "\\b").matcher(text).find();
    }

    static Set<String> literals(byte[] bytes) {
        var node = new ClassNode();
        new ClassReader(bytes).accept(node, ClassReader.SKIP_FRAMES);
        var result = new TreeSet<String>();
        for (var method : node.methods)
            for (var insn : method.instructions)
                if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof String value && !value.isEmpty() && value.length() <= 200)
                    result.add(value);
        for (var field : node.fields) if (field.value instanceof String value && !value.isEmpty() && value.length() <= 200) result.add(value);
        // An enum's constant names are written as constants in source, not as literals.
        if ((node.access & org.objectweb.asm.Opcodes.ACC_ENUM) != 0)
            for (var field : node.fields) if ((field.access & org.objectweb.asm.Opcodes.ACC_ENUM) != 0) result.remove(field.name);
        return result;
    }

    /** Java source form of a literal; non-ASCII characters are kept as they are (decompilers that escape them miss). */
    static String quote(String value) {
        var out = new StringBuilder("\"");
        for (char c : value.toCharArray()) {
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\t' -> out.append("\\t");
                case '\r' -> out.append("\\r");
                default -> out.append(c);
            }
        }
        return out.append('"').toString();
    }

    private static void write(Path output, Path reference, Path decompileOutput, Result result, String sha) throws IOException {
        var whole = result.whole();
        var report = new TreeMap<String,Object>();
        report.put("schema_version", 1); report.put("kind", "reference_comparison"); report.put("behavior", "NOT_VERIFIED");
        report.put("reference_sha256", sha);
        report.put("whole_compilation", EvidenceReport.verification(whole));
        if (result.perFile() != null) report.put("per_file", EvidenceReport.perFile(result.perFile()));
        report.put("reference_bodies", result.referenceBodies()); report.put("bodies_recovered", result.bodiesRecovered());
        report.put("literals", result.literals()); report.put("literals_found", result.literalsFound());
        report.put("reference_source_files", result.referenceFiles());
        report.put("files_naming_foreign_classes", result.filesNamingForeignClasses());
        report.put("foreign_classes", result.foreignClasses().size());
        EvidenceReport.write(output.resolve("reference.json"), report);
        Files.writeString(output.resolve("reference.txt"), text(result));
    }

    public static String text(Result result) {
        var whole = result.whole();
        var out = new StringBuilder();
        out.append("Reference comparison (answer key); behavior not verified.\n");
        out.append("Compiles + structure:\n");
        out.append("  whole compilation against the reference: ").append(whole.status())
            .append(whole.status() == VerificationResult.Status.COMPILED ? "" : " (" + whole.errors() + " errors in " + whole.fileErrors().size() + " files)").append('\n');
        out.append("  reference signatures declared: ").append(whole.matchedSymbols()).append('/').append(whole.expectedSymbols())
            .append(" (").append(percent(whole.matchedSymbols(), whole.expectedSymbols())).append(")\n");
        if (result.perFile() != null)
            out.append("  files compiled one by one against the reference: ").append(result.perFile().compiledFiles()).append('/').append(result.perFile().files()).append('\n');
        out.append("  reference bodies reproduced (identical or equivalent): ").append(result.bodiesRecovered()).append('/').append(result.referenceBodies())
            .append(" (").append(percent(result.bodiesRecovered(), result.referenceBodies())).append(")")
            .append(whole.status() == VerificationResult.Status.COMPILED ? "" : ", per-file credit").append('\n');
        out.append("Readability:\n");
        out.append("  reference string literals present in the sources: ").append(result.literalsFound()).append('/').append(result.literals())
            .append(" (").append(percent(result.literalsFound(), result.literals())).append(")\n");
        out.append("  reference source files naming a class the reference lacks: ").append(result.filesNamingForeignClasses()).append('/')
            .append(result.referenceFiles()).append(" (").append(result.foreignClasses().size()).append(" foreign top-level classes in the input)\n");
        return out.toString();
    }

    static String percent(int part, int total) {
        return total == 0 ? "n/a" : String.format(Locale.ROOT, "%.1f%%", Math.floor(1000.0 * part / total) / 10);
    }
}
