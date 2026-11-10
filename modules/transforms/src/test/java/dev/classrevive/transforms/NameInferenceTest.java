package dev.classrevive.transforms;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Phase 2DK: inferred names, measured against the original names the fixtures were scrambled from. */
class NameInferenceTest {
    @TempDir Path directory;

    private record Score(int total, int exact, int loose) {
        double exactRate() { return total == 0 ? 0 : (double) exact / total; }
    }

    private static Score score(NameInference.Result result, InferenceFixtures.Scrambled scrambled, NameInference.Confidence only) {
        int total = 0, exact = 0, loose = 0;
        for (var entry : result.entries()) {
            if (only != null && entry.confidence() != only) continue;
            String original = entry.kind().equals("class") ? scrambled.classKey().get(entry.owner())
                : scrambled.memberKey().get(entry.kind() + " " + entry.owner() + "#" + entry.name() + (entry.kind().equals("field") ? ":" : "") + entry.descriptor());
            assertNotNull(original, "answer key for " + entry);
            String expected = entry.kind().equals("class") ? original.substring(original.lastIndexOf('/') + 1) : original;
            String got = entry.kind().equals("class") ? entry.inferred().substring(entry.inferred().lastIndexOf('/') + 1) : entry.inferred();
            total++;
            if (got.equals(expected)) exact++;
            if (got.equalsIgnoreCase(expected) || got.toLowerCase(Locale.ROOT).contains(expected.toLowerCase(Locale.ROOT))
                || expected.toLowerCase(Locale.ROOT).contains(got.toLowerCase(Locale.ROOT))) loose++;
        }
        return new Score(total, exact, loose);
    }

    private NameInference.Result infer(Map<String,byte[]> classes) {
        return NameInference.infer(classes, Map.of(), List.of(), false, NameInference.Confidence.LOW);
    }

    @Test void recoversNamesFromSourceFileTextLabelsAndParameters() throws Exception {
        var scrambled = InferenceFixtures.scramble(InferenceFixtures.compile(directory), true, true);
        var result = infer(scrambled.classes());
        var byOriginal = new TreeMap<String,String>();
        for (var entry : result.entries()) {
            String original = entry.kind().equals("class") ? scrambled.classKey().get(entry.owner())
                : scrambled.memberKey().get(entry.kind() + " " + entry.owner() + "#" + entry.name() + (entry.kind().equals("field") ? ":" : "") + entry.descriptor());
            byOriginal.put(entry.kind() + " " + original, entry.inferred() + " [" + entry.confidence() + " " + entry.rule() + "]");
        }
        System.out.println("INFERRED (sourceFile+locals): " + byOriginal);
        assertTrue(result.classes().size() >= 6, byOriginal.toString());
        assertEquals("app/Player", result.classes().get(scrambled.classKey().entrySet().stream().filter(e -> e.getValue().equals("app/Player")).findFirst().orElseThrow().getKey()));
        var all = score(result, scrambled, null);
        var high = score(result, scrambled, NameInference.Confidence.HIGH);
        System.out.println("SCORE all=" + all + " high=" + high);
        assertTrue(high.exactRate() >= 0.9, "HIGH confidence names should be right: " + high);
        assertTrue(all.exactRate() >= 0.6, "exact rate " + all);
        InferenceFixtures.link(new ClassRenamer().rename(scrambled.classes(), result.classes(), result.members(), false, true).classes());
    }

    @Test void strippedProguardStyleStillGetsTypeAndTextNamesButNoClassNames() throws Exception {
        var scrambled = InferenceFixtures.scramble(InferenceFixtures.compile(directory), false, false);
        var result = infer(scrambled.classes());
        var all = score(result, scrambled, null);
        System.out.println("SCORE stripped all=" + all + " classes=" + result.count("class") + " fields=" + result.count("field") + " methods=" + result.count("method"));
        assertTrue(result.count("field") > 0);
        assertTrue(all.exactRate() >= 0.5, "exact rate " + all);
        InferenceFixtures.link(new ClassRenamer().rename(scrambled.classes(), result.classes(), result.members(), false).classes());
    }

    @Test void neverTouchesReadableNames() throws Exception {
        var readable = InferenceFixtures.compile(directory);
        var result = infer(readable);
        assertTrue(result.isEmpty(), result.entries().toString());
    }

    @Test void deterministicAndWritesAConsultableMapping() throws Exception {
        var scrambled = InferenceFixtures.scramble(InferenceFixtures.compile(directory), true, true);
        var first = infer(scrambled.classes()); var second = infer(scrambled.classes());
        assertEquals(first.mapping(), second.mapping());
        String mapping = first.mapping();
        assertTrue(mapping.contains("INFERRED"), mapping);
        var plan = MappingFile.parse(mapping.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertEquals(first.classes(), plan.classes());
        assertEquals(first.members().size(), plan.members().size());
        for (var entry : first.entries()) {
            assertFalse(entry.rule().isBlank()); assertFalse(entry.evidence().isBlank());
            assertNotNull(entry.confidence());
        }
    }

    @Test void obfuscatedNameTest() {
        for (String name : List.of("a", "b", "aa", "Zz", "lIl", "IIII", "OOO", "l1")) assertTrue(NameInference.obfuscated(name, true), name);
        for (String name : List.of("id", "name", "count", "ok", "x", "run", "Player")) assertFalse(NameInference.obfuscated(name, false), name);
        assertTrue(NameInference.obfuscated("x", true));
    }

    @Test void labelsAndKeys() {
        assertEquals("name", NameInference.labelName("Player{name="));
        assertEquals("age", NameInference.labelName(", age="));
        assertEquals("totalBalance", NameInference.labelName("Total balance: "));
        assertNull(NameInference.labelName("Failed to load the player from disk: "));
        assertNull(NameInference.labelName("plain text"));
        assertEquals("serverPort", NameInference.keyName("server.port"));
        assertEquals("userName", NameInference.keyName("user_name"));
        assertNull(NameInference.keyName("a b c d e"));
        assertEquals("players", NameInference.plural("player"));
        assertEquals("items", NameInference.plural("item"));
        assertEquals("entries", NameInference.plural("entry"));
        assertEquals("player", NameInference.singular("players"));
        assertEquals("entry", NameInference.singular("entries"));
    }

    @Test void localsAreNamedByTypeOnlyWhenTheTableIsObfuscated() throws Exception {
        var scrambled = InferenceFixtures.scramble(InferenceFixtures.compile(directory), true, true);
        int renamed = 0;
        for (var bytes : scrambled.classes().values()) {
            var node = new ClassNode(); new ClassReader(bytes).accept(node, 0);
            for (var method : node.methods) if (method.localVariables != null) for (var local : method.localVariables) {
                if (local.name.length() <= 2 && !local.name.equals("i")) fail("fixture locals keep their real names: " + local.name);
            }
        }
        // Real names stay: nothing is renamed.
        for (var bytes : scrambled.classes().values()) { var node = new ClassNode(); new ClassReader(bytes).accept(node, 0); renamed += LocalNames.rename(node); }
        assertEquals(0, renamed);
        var obfuscated = new ClassNode(); new ClassReader(scrambled.classes().values().iterator().next()).accept(obfuscated, 0);
        for (var method : obfuscated.methods) if (method.localVariables != null) { int n = 0; for (var local : method.localVariables) if (!local.name.equals("this")) local.name = String.valueOf((char) ('a' + n++)); }
        int count = LocalNames.rename(obfuscated);
        assertTrue(count > 0);
        for (var method : obfuscated.methods) if (method.localVariables != null) {
            var seen = new HashSet<String>();
            for (var local : method.localVariables) if (local.name.matches("[a-z]")) assertNotEquals("", local.name);
            for (var local : method.localVariables) seen.add(local.name + local.index);
        }
    }
}
