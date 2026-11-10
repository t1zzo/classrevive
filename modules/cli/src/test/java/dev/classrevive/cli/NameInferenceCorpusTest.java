package dev.classrevive.cli;

import dev.classrevive.transforms.ClassRenamer;
import dev.classrevive.transforms.NameInference;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 2DK: name inference over the real ProGuard 7.10.0 jars (obf8, obf8-opt). Their SourceFile attribute is renamed to
 * "SourceFile", LocalVariableTable is dropped and the one Serializable class is off limits, so the honest expectation is
 * few names and none wrong: every name the inference does give must match the mapping file's original, and the renamed
 * classes must still link.
 */
class NameInferenceCorpusTest {
    private static final Path FIXTURES = Path.of(System.getProperty("classrevive.obfuscatedFixtures", "missing"));
    private static final Path NAMES = Path.of(System.getProperty("classrevive.nameFixtures", "missing"));

    /** {@code newClass -> original simple name} and {@code newClass#newMember -> original names} from a ProGuard mapping.txt. */
    private static Map<String,Set<String>> key(Path mapping) throws Exception {
        var key = new TreeMap<String,Set<String>>(); String current = null;
        for (String line : Files.readAllLines(mapping)) {
            if (line.startsWith("#") || line.isBlank()) continue;
            if (!line.startsWith(" ")) {
                String[] parts = line.substring(0, line.length() - 1).split(" -> ");
                current = parts[1].replace('.', '/');
                String original = parts[0]; original = original.substring(original.lastIndexOf('.') + 1);
                key.computeIfAbsent(current, k -> new TreeSet<>()).add(original.substring(original.lastIndexOf('$') + 1));
            } else {
                String body = line.trim(); String[] parts = body.split(" -> ");
                String left = parts[0]; left = left.replaceAll("^[0-9]+:[0-9]+:", "");
                String name = left.substring(left.lastIndexOf(' ') + 1); int paren = name.indexOf('('); if (paren >= 0) name = name.substring(0, paren);
                key.computeIfAbsent(current + (body.contains("(") ? "()#" : "#") + parts[1], k -> new TreeSet<>()).add(name);
            }
        }
        return key;
    }

    @Test void inferredNamesOnRealProguardJarsAreNeverWrongAndLinkAfterwards() throws Exception {
        for (String jar : List.of("obf8", "obf8-opt")) {
            var classes = ProguardFixtureTest.classes(FIXTURES.resolve(jar + ".jar"));
            var bytes = new TreeMap<String,byte[]>(); classes.forEach((name, value) -> bytes.put(name.replace('.', '/'), value));
            var key = key(FIXTURES.resolve(jar + ".mapping.txt"));
            var result = NameInference.infer(bytes, Map.of(), List.of(), false, NameInference.Confidence.LOW);
            int right = 0;
            for (var entry : result.entries()) {
                var originals = entry.kind().equals("class") ? key.get(entry.owner()) : key.get(entry.owner() + (entry.kind().equals("method") ? "()#" : "#") + entry.name());
                String got = entry.inferred().substring(entry.inferred().lastIndexOf('/') + 1);
                if (originals != null && originals.contains(got)) right++;
            }
            System.out.println("PROGUARD " + jar + ": inferred " + result.entries().size() + " names, " + right + " right: " + result.entries());
            assertEquals(result.entries().size(), right, jar + " " + result.entries());
            var renamed = new ClassRenamer().rename(bytes, result.classes(), result.members(), false);
            var linked = new TreeMap<String,byte[]>();
            renamed.classes().forEach((name, value) -> linked.put(name.replace('/', '.'), value));
            ProguardFixtureTest.link(linked);
            // The Serializable ledger is off limits: its fields and methods keep their names.
            for (var rule : result.members()) assertNotEquals("serialVersionUID", rule.name());
        }
    }

    /** The Phase 2DK ProGuard jars: every private member is in the answer key, so the rate of right names is measured. */
    @Test void inferredNamesOnProguardJarsMadeFromTheNameSourcesMatchTheMapping() throws Exception {
        for (var spec : List.of(Map.entry("names-debug", 0.85), Map.entry("names-default", 0.6))) {
            String jar = spec.getKey();
            var classes = ProguardFixtureTest.classes(NAMES.resolve(jar + ".jar"));
            var bytes = new TreeMap<String,byte[]>(); classes.forEach((name, value) -> bytes.put(name.replace('.', '/'), value));
            var key = key(NAMES.resolve(jar + ".mapping.txt"));
            var result = NameInference.infer(bytes, Map.of(), List.of(), false, NameInference.Confidence.LOW);
            int right = 0, highRight = 0, high = 0;
            var wrong = new ArrayList<String>();
            for (var entry : result.entries()) {
                var originals = entry.kind().equals("class") ? key.get(entry.owner()) : key.get(entry.owner() + (entry.kind().equals("method") ? "()#" : "#") + entry.name());
                String got = entry.inferred().substring(entry.inferred().lastIndexOf('/') + 1);
                boolean match = originals != null && originals.contains(got);
                if (match) right++; else wrong.add(entry.kind() + " " + originals + " -> " + got + " [" + entry.confidence() + " " + entry.rule() + "]");
                if (entry.confidence() == NameInference.Confidence.HIGH) { high++; if (match) highRight++; }
            }
            int total = result.entries().size();
            System.out.println("PROGUARD-NAMES " + jar + ": " + total + " inferred (" + result.count("class") + " classes, " + result.count("field") + " fields, "
                + result.count("method") + " methods), " + right + " exact (" + Math.round(100.0 * right / Math.max(1, total)) + "%), HIGH " + highRight + "/" + high + "; wrong: " + wrong);
            assertTrue(total >= 10, jar + " " + total);
            assertEquals(high, highRight, "every HIGH name is right in " + jar + ": " + wrong);
            assertTrue((double) right / total >= spec.getValue(), jar + ": " + right + "/" + total + " " + wrong);
            var renamed = new ClassRenamer().rename(bytes, result.classes(), result.members(), false, result.locals());
            var linked = new TreeMap<String,byte[]>();
            renamed.classes().forEach((name, value) -> linked.put(name.replace('/', '.'), value));
            ProguardFixtureTest.link(linked);
        }
    }
}
