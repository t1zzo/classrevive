package dev.classrevive.gui;

import dev.classrevive.workbench.ResultIndex;
import java.awt.Component;
import java.awt.Container;
import java.awt.GraphicsEnvironment;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import javax.swing.JLabel;
import javax.swing.text.JTextComponent;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Phase G7: the Deobfuscation section reads the report as written. Logic is headless; rendering needs a display. */
class DeobfuscationViewTest {
    @TempDir Path directory;

    private static final String REPORT = """
        {"kind":"decompilation","input_sha256":"ab","selected_candidate":"cfr","behavior":"NOT_VERIFIED",
         "verification":{"status":"%s","errors":%d,"matched_symbols":10,"expected_symbols":12,"structurally_complete":true},
         "engines":[{"id":"cfr","version":"1","status":"OUTPUT_PRODUCED","source_files":3},{"id":"jadx","version":"1","status":"TIMED_OUT","source_files":0}],
         "class_confidence":[{"class_name":"a/B","file":"a/B.java","origin":"cfr","matched_symbols":2,"expected_symbols":2,"identical":1,
            "equivalent_after_normalization":0,"different":0,"different_methods":[],"level":"EXACT"}],
         "obfuscation":{"classes":40,"verdict":"HIGH","suggested_obfuscator":"Skidfuscator","unparsable_classes":0,
            "techniques":{"STRING_ENCRYPTION":{"label":"string encryption","classes":5,"occurrences":120},"HIDDEN_CALLS":{"label":"hidden calls","classes":9,"occurrences":300}},
            "hypotheses":[{"obfuscator":"Skidfuscator","score":93,"confidence":"HIGH","reasons":["a","b"]},{"obfuscator":"Other","score":30,"confidence":"LOW","reasons":[]}]},
         "deobfuscation":%s}
        """;

    private static final String APPLIED = """
        {"obfuscator":"Skidfuscator","score":93,"confidence":"HIGH","pass":"skidfuscator","status":"APPLIED","summary":"x","methods_rewritten":12,
         "pass_counts":{"strings decrypted":6653,"opaque branches decided":40,"strings not decrypted":7,"methods refused":2,"class write failures":0,"switches pruned":0},
         "link_check":{"linked":false,"classes":30,"error_type":"NoClassDefFoundError","failed_class":"a/C"},
         "original_signatures":{"original_classes":40,"reversed_classes":38,"removed_classes":["rt/Helper","rt/Other"],
            "original_methods":100,"methods_kept":90,"original_fields":50,"fields_kept":45}}
        """;

    private ResultIndex open(String compilation, int errors, String deobfuscation) throws Exception {
        Path output = Files.createDirectories(directory.resolve("out" + System.nanoTime()));
        Files.writeString(output.resolve("report.json"), REPORT.formatted(compilation, errors, deobfuscation));
        return ResultIndex.open(output);
    }

    @Test void readsWhatTheReportSays() throws Exception {
        var index = open("COMPILED", 0, APPLIED);
        var found = index.obfuscation();
        assertEquals("Skidfuscator", found.top().obfuscator());
        assertEquals(93, found.top().score());
        assertEquals(2, found.techniques().size());
        var reversal = index.reversal();
        assertTrue(reversal.applied());
        assertEquals(6653, reversal.counts().get("strings decrypted"));
        assertEquals(List.of("rt/Helper", "rt/Other"), reversal.signatures().removedClasses());
        assertEquals("NoClassDefFoundError", reversal.link().errorType());
        assertTrue(index.editedClasses().isEmpty(), "counts only: no per-class edits recorded");
    }

    @Test void sortsCountsIntoRevertedAndNotRecovered() throws Exception {
        Text.use(GuiSettings.Language.ENGLISH);
        var insights = new ResultInsights(open("COMPILED", 0, APPLIED));
        assertTrue(insights.hasDeobfuscation());
        assertEquals(List.of("strings decrypted", "opaque branches decided"), insights.reverted().stream().map(ResultInsights.Count::label).toList(),
            "zero counts are left out, biggest first");
        assertEquals(List.of("strings not decrypted", "methods refused"), insights.notRecovered().stream().map(ResultInsights.Count::label).toList());
        assertEquals(0.9, insights.methodsKept(), 1e-9);
        assertTrue(ResultInsights.isNotRecovered("class salts in initialization order (left alone)"));
        assertTrue(ResultInsights.isNotRecovered("hashed switches ambiguous"));
        assertFalse(ResultInsights.isNotRecovered("strings decrypted"));
        assertFalse(ResultInsights.isNotRecovered("of which by the trap invariant (unevaluable hash)"));
    }

    @Test void noticesSayWhatToDo() throws Exception {
        Text.use(GuiSettings.Language.ENGLISH);
        var notices = new ResultInsights(open("COMPILE_FAILED", 3, APPLIED)).notices();
        var severities = notices.stream().map(ResultInsights.Notice::severity).toList();
        assertEquals(severities.stream().sorted().toList(), severities, "errors first");
        assertEquals(ResultInsights.Severity.ERROR, notices.getFirst().severity());
        assertTrue(notices.getFirst().text().contains("3"));
        var text = notices.stream().map(ResultInsights.Notice::text).toList().toString();
        assertTrue(text.contains("could not finish"), "a missing library is inconclusive, not a rejection: " + text);
        assertTrue(text.contains("jadx"), "the engine that did not finish: " + text);
        assertTrue(text.contains("9"), "unrecovered total (7 + 2): " + text);
        notices.forEach(notice -> assertFalse(notice.hint().isBlank(), notice.text()));
    }

    @Test void refusedReversalWarnsAndNothingIsClaimedReverted() throws Exception {
        Text.use(GuiSettings.Language.ENGLISH);
        var refused = """
            {"obfuscator":"Skidfuscator","score":80,"pass":"skidfuscator","status":"REFUSED","reason":"too many methods refused","summary":"s","pass_counts":{},"methods_rewritten":0}
            """;
        var insights = new ResultInsights(open("COMPILED", 0, refused));
        assertTrue(insights.reverted().isEmpty());
        assertTrue(insights.notices().stream().anyMatch(n -> n.severity() == ResultInsights.Severity.WARNING && n.text().contains("too many methods refused")));
        assertTrue(insights.notices().stream().anyMatch(n -> n.hint().contains("original")));
    }

    @Test void readsTheReversalTheWindowRanItself() throws Exception {
        Path folder = Files.createDirectories(directory.resolve("reversal"));
        Files.writeString(folder.resolve("transform.json"), """
            {"kind":"static_transform","status":"OUTPUT_VALIDATED","changes":[],"link_check":{"linked":true,"classes":45},
             "jar_passes":"Skidfuscator reversal:\\n  methods changed: 49\\n  strings decrypted: 12\\n  methods refused: 3\\n  refused: a/B.m()V\\n"}""");
        var reversal = ResultIndex.reversalOf(folder, "Skidfuscator", 100);
        assertTrue(reversal.applied());
        assertEquals(49, reversal.methodsRewritten());
        assertEquals(12, reversal.counts().get("strings decrypted"));
        assertTrue(reversal.link().linked());
        assertNull(ResultIndex.reversalOf(directory.resolve("missing"), "X", 1));
    }

    @Test void aMalformedSectionIsLeftOutNotFatal() throws Exception {
        var index = open("COMPILED", 0, "{\"status\":5}");
        assertNull(index.reversal());
        assertNotNull(index.obfuscation());
        assertEquals(1, index.classes().size());
    }

    @Test void plainResultHasNothingToShow() throws Exception {
        Path output = Files.createDirectories(directory.resolve("plain"));
        Files.writeString(output.resolve("report.json"), """
            {"kind":"decompilation","input_sha256":"ab","selected_candidate":"cfr","behavior":"NOT_VERIFIED",
             "verification":{"status":"COMPILED","errors":0,"matched_symbols":1,"expected_symbols":1,"structurally_complete":true},
             "engines":[],"class_confidence":[]}""");
        var insights = new ResultInsights(ResultIndex.open(output));
        assertFalse(insights.hasDeobfuscation());
        assertTrue(insights.notices().isEmpty());
        assertEquals(-1, insights.methodsKept());
    }

    @Test void stageTimesAreReadable() {
        assertEquals("", ActivityPanel.took(null));
        assertEquals("<0.1 s", ActivityPanel.took(Duration.ofMillis(20)));
        assertEquals("1.5 s", ActivityPanel.took(Duration.ofMillis(1500)));
        assertEquals("2 min 05 s", ActivityPanel.took(Duration.ofSeconds(125)));
    }

    @Test void panelShowsTheFourPartsInBothLanguages() throws Exception {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(), "needs a display");
        Theme.install(GuiSettings.ThemeChoice.DARK);
        var index = open("COMPILED", 0, APPLIED);
        try {
            Text.use(GuiSettings.Language.ENGLISH);
            var text = texts(new DeobfuscationPanel(new ResultInsights(index)));
            for (String expected : List.of("Deobfuscation", "IDENTIFIED", "REVERTED", "NOT RECOVERED", "string encryption", "strings decrypted", "6,653",
                    "strings not decrypted", "methods refused", "rt.Helper", "Kept 90 of 100"))
                assertTrue(text.contains(expected), expected + " in " + text);
            Text.use(GuiSettings.Language.PORTUGUESE);
            var portuguese = texts(new DeobfuscationPanel(new ResultInsights(index)));
            assertTrue(portuguese.contains("DESOFUSCAÇÃO") || portuguese.contains("Desofuscação"), portuguese);
            assertTrue(portuguese.contains("NÃO RECUPERADO"), portuguese);
        } finally {
            Text.use(GuiSettings.Language.ENGLISH);
        }
    }

    private static String texts(Component component) {
        var out = new StringBuilder();
        collect(component, out);
        return out.toString();
    }

    private static void collect(Component component, StringBuilder out) {
        if (component instanceof JLabel label && label.getText() != null) out.append(label.getText()).append('\n');
        if (component instanceof JTextComponent text) out.append(text.getText()).append('\n');
        if (component instanceof Container container) for (Component child : container.getComponents()) collect(child, out);
    }
}
