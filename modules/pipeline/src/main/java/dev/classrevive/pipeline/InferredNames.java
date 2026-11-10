package dev.classrevive.pipeline;

import dev.classrevive.transforms.NameInference;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * Phase 2DK: the evidence of {@code --infer-names}. Written by {@link DecompilationJob#run} under {@code inferred-names/}
 * ({@code names.json}: every name with rule, evidence and confidence; {@code mapping.txt}: the same plan in the rename
 * command's mapping syntax; {@code names.tsv}: one line per name) and read back when the report is built. Every name in it
 * is a guess marked {@code "inferred": true}; none is an original name.
 */
final class InferredNames {
    static final String DIRECTORY = "inferred-names";
    static final String POLICY = "Names are inferred from bytecode evidence (toString and message labels, the SourceFile attribute, parameter names, "
        + "put/get keys, declared types, getters and setters, well-known supertypes), never executed code. Each entry names its rule, its evidence and a "
        + "confidence (HIGH: the program states the name, MEDIUM: a convention or a key, LOW: only the type). Only classes of the input and private fields and "
        + "methods are renamed (a private member is never inherited or overridden, so every reference is rewritten); names that already read well, "
        + "Serializable classes, native methods, annotated fields, names the code also uses as strings, and classes named by a resource are left alone. "
        + "The round trip measures the renamed classes, not the original names.";

    private InferredNames() {}

    static void write(Path output, NameInference.Result result, NameInference.Confidence minimum) throws IOException {
        Path directory = Files.createDirectories(output.resolve(DIRECTORY));
        var data = new TreeMap<String,Object>();
        data.put("inferred", true); data.put("applied", true);
        data.put("minimum_confidence", minimum.name());
        data.put("policy", POLICY);
        data.put("classes", (int) result.count("class")); data.put("fields", (int) result.count("field")); data.put("methods", (int) result.count("method"));
        data.put("local_variables", result.localVariables());
        var byConfidence = new TreeMap<String,Integer>(); var byRule = new TreeMap<String,Integer>();
        for (var entry : result.entries()) { byConfidence.merge(entry.confidence().name(), 1, Integer::sum); byRule.merge(entry.rule(), 1, Integer::sum); }
        data.put("by_confidence", byConfidence); data.put("by_rule", byRule); data.put("skipped", new TreeMap<>(result.skipped()));
        data.put("mapping_file", DIRECTORY + "/mapping.txt");
        var rows = new ArrayList<Object>();
        var tsv = new StringBuilder("kind\towner\tname\tdescriptor\tinferred\tconfidence\trule\tevidence\n");
        for (var entry : result.entries()) {
            var row = new TreeMap<String,Object>();
            row.put("kind", entry.kind()); row.put("owner", entry.owner()); row.put("name", entry.name()); row.put("descriptor", entry.descriptor());
            row.put("inferred_name", entry.inferred()); row.put("rule", entry.rule()); row.put("evidence", entry.evidence());
            row.put("confidence", entry.confidence().name()); row.put("inferred", true);
            rows.add(row);
            tsv.append(String.join("\t", entry.kind(), entry.owner(), entry.name(), entry.descriptor(), entry.inferred(), entry.confidence().name(), entry.rule(),
                entry.evidence().replace('\t', ' ').replace('\n', ' ').replace('\r', ' '))).append('\n');
        }
        data.put("entries", rows);
        Files.writeString(directory.resolve("mapping.txt"), result.mapping(), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        Files.writeString(directory.resolve("names.tsv"), tsv, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        EvidenceReport.write(directory.resolve("names.json"), data);
    }

    /** The guesses were dropped because applying them failed; the job went on with the names the input has. */
    static void writeFailed(Path output, NameInference.Confidence minimum, String reason) throws IOException {
        Path directory = Files.createDirectories(output.resolve(DIRECTORY));
        var data = new TreeMap<String,Object>();
        data.put("inferred", true); data.put("applied", false); data.put("minimum_confidence", minimum.name()); data.put("policy", POLICY);
        data.put("error", reason); data.put("classes", 0); data.put("fields", 0); data.put("methods", 0); data.put("local_variables", 0);
        data.put("by_confidence", new TreeMap<String,Integer>()); data.put("by_rule", new TreeMap<String,Integer>()); data.put("skipped", new TreeMap<String,Integer>());
        data.put("entries", List.of());
        EvidenceReport.write(directory.resolve("names.json"), data);
    }

    /** The decoded {@code names.json}, or null when {@code --infer-names} was off. */
    @SuppressWarnings("unchecked")
    static Map<String,Object> read(Path output) throws IOException {
        Path file = output.resolve(DIRECTORY).resolve("names.json");
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) return null;
        return (Map<String,Object>) Json.decode(Files.readString(file, StandardCharsets.UTF_8));
    }

    /** Original class names renamed by inference. */
    static Set<String> classes(Map<String,Object> data) {
        var names = new TreeSet<String>();
        if (data != null) for (Object row : (List<?>) data.get("entries")) if (row instanceof Map<?,?> entry && "class".equals(entry.get("kind"))) names.add((String) entry.get("owner"));
        return names;
    }

    /** {@code owner#name(desc)} / {@code owner#name:desc} keys of the members renamed by inference. */
    static Set<String> members(Map<String,Object> data) {
        var names = new TreeSet<String>();
        if (data != null) for (Object row : (List<?>) data.get("entries")) if (row instanceof Map<?,?> entry && !"class".equals(entry.get("kind")))
            names.add(entry.get("owner") + "#" + entry.get("name") + ("field".equals(entry.get("kind")) ? ":" : "") + entry.get("descriptor"));
        return names;
    }

    static Map<String,String> without(Map<String,String> all, Set<String> inferred) {
        var result = new TreeMap<String,String>(all);
        result.keySet().removeAll(inferred);
        return result;
    }

    /** The {@code inferred_names} block of report.json: a copy of names.json (the file keeps the full evidence). */
    static Map<String,Object> json(Map<String,Object> data) { return data; }

    static String text(Map<String,Object> data) {
        if (data == null) return "";
        if (Boolean.FALSE.equals(data.get("applied"))) return "Inferred names (Phase 2DK): dropped, the rename failed (" + data.get("error") + "); the names are the input's own.\n";
        var text = new StringBuilder("Inferred names (Phase 2DK, minimum confidence ").append(data.get("minimum_confidence")).append("): ")
            .append(data.get("classes")).append(" class(es), ").append(data.get("fields")).append(" field(s), ").append(data.get("methods"))
            .append(" method(s), ").append(data.get("local_variables")).append(" local variable(s) renamed. These are guesses marked as inferred, not original names; by confidence ")
            .append(data.get("by_confidence")).append(", by rule ").append(data.get("by_rule")).append(". Evidence per name: ")
            .append(DIRECTORY).append("/names.tsv and report.json; the same plan as a mapping: ").append(DIRECTORY).append("/mapping.txt\n");
        int shown = 0;
        for (Object row : (List<?>) data.get("entries")) {
            if (!(row instanceof Map<?,?> entry)) continue;
            if (shown++ == 40) { text.append("  ... ").append(((List<?>) data.get("entries")).size() - 40).append(" more\n"); break; }
            text.append("  ").append(entry.get("kind")).append(' ').append(entry.get("owner"))
                .append("class".equals(entry.get("kind")) ? "" : "#" + entry.get("name")).append(" -> ").append(entry.get("inferred_name"))
                .append(" [").append(entry.get("confidence")).append(' ').append(entry.get("rule")).append("]\n");
        }
        return text.toString();
    }
}
