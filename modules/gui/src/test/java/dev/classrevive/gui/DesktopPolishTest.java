package dev.classrevive.gui;

import dev.classrevive.engine.EngineResult;
import dev.classrevive.pipeline.JobListener;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Phase G8, headless: the logic behind the redesigned screens and the fixes found while redesigning them. */
class DesktopPolishTest {
    @BeforeAll static void english() { Text.use(GuiSettings.Language.ENGLISH); }

    /** The bug: after a decompile, opening another input kept the last run's stage times next to "No decompile yet". */
    @Test void openingAnotherInputClearsTheLastRunsStageTimes() {
        var activity = new ActivityPanel(Ui.action("Cancel", null, null, () -> { }));
        activity.start("a.jar", List.of("cfr"));
        activity.stage(JobListener.Stage.INTAKE);
        activity.stage(JobListener.Stage.ENGINES);
        activity.engineFinished(new EngineResult("cfr", "0.152", EngineResult.Status.OUTPUT_PRODUCED, 0, 3, 120, null));
        activity.finish("Done", true);
        assertFalse(activity.stageTime(JobListener.Stage.INTAKE).isEmpty(), "a finished stage shows its time");
        activity.reset(null);
        for (var stage : JobListener.Stage.values()) assertEquals("", activity.stageTime(stage), stage + " keeps no time after a reset");
        assertEquals(Text.t("activity.idle"), activity.headlineText());
        activity.reset("b-20261114");
        assertEquals(Text.t("activity.opened", "b-20261114"), activity.headlineText(), "a result opened from disk says so");
    }

    @Test void shortcutsUseTheCommandKeyOnMac() {
        assertEquals("meta O", Ui.platform("ctrl O", true));
        assertEquals("meta shift F", Ui.platform("ctrl shift F", true));
        assertEquals("ctrl alt H", Ui.platform("ctrl alt H", true), "Cmd+Option shortcuts clash with the system");
        assertEquals("ctrl O", Ui.platform("ctrl O", false));
        assertEquals("alt LEFT", Ui.platform("alt LEFT", true));
        assertEquals("F5", Ui.platform("F5", true));
    }

    @Test void searchHitsHighlightEveryMatchAndEscapeTheRest() {
        String html = SearchPanel.highlight("if (a < b) return a + A;", "a", false, "#FF8A1F");
        assertTrue(html.contains("&lt;"), "markup in the source is escaped");
        assertEquals(3, html.split("<b>", -1).length - 1, "three matches without case: a, a, A");
        assertEquals(2, SearchPanel.highlight("a A a", "A", true, "#000000").split("<b>", -1).length, "one match with case");
        String longLine = "x".repeat(300) + "needle" + "y".repeat(300);
        String cut = SearchPanel.highlight(longLine, "needle", true, "#000000");
        assertTrue(cut.startsWith("…") && cut.contains("needle"), "a long line is cut around its first match");
        assertTrue(cut.length() < 260);
        assertDoesNotThrow(() -> SearchPanel.highlight("\u0130stanbul", "i", false, "#000000"), "lower-casing that changes the length");
    }

    @Test void goToClassBoldsTheTypedPart() {
        assertEquals("<b>Json<font color='#C00'>Read</font>er</b>", GoToClassDialog.match("JsonReader", "read", "#C00"));
        assertEquals("<b>JsonReader</b>", GoToClassDialog.match("JsonReader", "", "#C00"));
        assertEquals("<b>A&lt;B</b>", GoToClassDialog.match("A<B", "z", "#C00"));
    }

    @Test void largeTextsAreCountedOnlyAsFarAsNeeded() {
        assertEquals(1, CodeTab.lines(""));
        assertEquals(3, CodeTab.lines("a\nb\nc"));
        assertEquals(CodeTab.LARGE_LINES + 1, CodeTab.lines("\n".repeat(CodeTab.LARGE_LINES * 3)), "stops just past the limit");
    }

    @Test void engineAndSelectionNamesReadAsPeopleWriteThem() {
        assertEquals("CFR", CodeTab.engineName("cfr"));
        assertEquals("JADX", CodeTab.engineName("jadx"));
        assertEquals("Vineflower", CodeTab.engineName("vineflower"));
        assertEquals("Procyon", CodeTab.engineName("procyon"));
        assertEquals("Mix (6)", OverviewPanel.selectedName("hybrid-6"));
        assertEquals("Methods combined (1)", OverviewPanel.selectedName("recompose-1"));
        assertEquals("CFR", OverviewPanel.selectedName("cfr"));
        assertEquals("—", OverviewPanel.selectedName(null));
    }

    @Test void recentPathsKeepBothEnds() {
        assertEquals("C:\\a\\b", WelcomePanel.shorten("C:\\a\\b", 20));
        String shortened = WelcomePanel.shorten("C:\\Users\\someone\\very\\long\\folder\\name\\here", 20);
        assertEquals(20, shortened.length());
        assertTrue(shortened.startsWith("C:\\Users") && shortened.endsWith("here") && shortened.contains("…"));
    }

    @Test void clearingTheRecentListKeepsTheOtherSettings(@org.junit.jupiter.api.io.TempDir Path directory) throws Exception {
        var settings = GuiSettings.load(directory.resolve("settings.properties"));
        settings.fontSize(17);
        settings.remember(directory.resolve("a.jar"));
        settings.remember(directory.resolve("b.jar"));
        settings.clearRecent();
        settings.save();
        var again = GuiSettings.load(directory.resolve("settings.properties"));
        assertTrue(again.recent().isEmpty());
        assertEquals(17, again.fontSize());
    }
}
