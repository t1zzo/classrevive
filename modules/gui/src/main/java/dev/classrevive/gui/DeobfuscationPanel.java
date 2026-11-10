package dev.classrevive.gui;

import dev.classrevive.workbench.ResultIndex;
import java.awt.*;
import javax.swing.*;

/**
 * Phase G7: the Deobfuscation section of the overview. Four parts, each read from the reports the engine already wrote:
 * what was identified (obfuscator, techniques, score), what was reverted (the pass's counts), what was not recovered, and
 * the warnings. It decides nothing: a count that is absent from the report is absent here.
 */
final class DeobfuscationPanel extends Ui.Column {
    private static final int WIDTH = 640;

    DeobfuscationPanel(ResultInsights insights) {
        setOpaque(false);
        add(Ui.section(Text.t("deob.title")));
        var reversal = insights.reversal();
        var found = insights.obfuscation();
        add(Ui.text(headline(reversal, found), false));
        add(Box.createVerticalStrut(10));

        add(card(Text.t("deob.identified"), identified(found, reversal)));
        if (reversal != null && !reversal.status().equals("OFF")) {
            add(Box.createVerticalStrut(10));
            add(card(Text.t("deob.reverted"), reverted(insights, reversal)));
            add(Box.createVerticalStrut(10));
            add(card(Text.t("deob.notRecovered"), notRecovered(insights, reversal)));
        }
        var notices = insights.notices();
        if (!notices.isEmpty()) {
            add(Box.createVerticalStrut(10));
            for (var notice : notices) { add(Ui.notice(notice)); add(Box.createVerticalStrut(6)); }
        }
        add(Box.createVerticalStrut(6));
        add(Ui.text(Text.t("deob.note"), true));
    }

    /** One line saying where the reversal stands: applied, refused, failed, off, or only a detection. */
    static String headline(ResultIndex.Reversal reversal, ResultIndex.ReportedObfuscation found) {
        if (reversal == null) return found == null ? "" : Text.t("deob.headline.detected", ObfuscationBanner.verdictName(found));
        return switch (reversal.status()) {
            case "APPLIED" -> Text.t("deob.headline.applied", reversal.obfuscator(), reversal.score(), reversal.methodsRewritten());
            case "REFUSED" -> Text.t("deob.headline.refused", reversal.obfuscator(), reversal.score());
            case "FAILED" -> Text.t("deob.headline.failed", reversal.obfuscator(), reversal.score());
            default -> Text.t("deob.headline.off", reversal.obfuscator(), reversal.score());
        };
    }

    private static Ui.Card card(String caption, JComponent content) {
        var card = new Ui.Card(null);
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
        var title = Ui.caption(caption);
        title.setAlignmentX(LEFT_ALIGNMENT);
        card.add(title);
        card.add(Box.createVerticalStrut(8));
        content.setAlignmentX(LEFT_ALIGNMENT);
        card.add(content);
        return card;
    }

    private static JComponent identified(ResultIndex.ReportedObfuscation found, ResultIndex.Reversal reversal) {
        var box = new Ui.Column();
        box.setOpaque(false);
        if (found == null || found.techniques().isEmpty()) {
            box.add(Ui.muted(Text.t("deob.identified.none")));
            return box;
        }
        var top = found.top();
        if (top != null && !found.verdict().equals("UNKNOWN")) {
            var line = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
            line.setOpaque(false);
            var name = Ui.heading(Ui.visible(top.obfuscator()), 15f);
            line.add(name);
            line.add(Box.createHorizontalStrut(10));
            line.add(Ui.chip(Text.t("confidence." + top.confidence()) + " · " + top.score() + "/100", Theme.ACCENT));
            box.add(line);
            box.add(Box.createVerticalStrut(2));
            box.add(Ui.muted(Text.t("deob.identified.hypothesis")));
        } else {
            box.add(Ui.heading(Text.t("obfuscation.unknown"), 15f));
        }
        box.add(Box.createVerticalStrut(8));
        var rows = Ui.rows(WIDTH);
        found.techniques().stream().sorted(java.util.Comparator.comparingInt(ResultIndex.Technique::occurrences).reversed())
            .forEach(technique -> Ui.row(rows, Ui.visible(technique.label()),
                technique.occurrences() > 0 ? Text.t("deob.technique", technique.classes(), technique.occurrences()) : Text.t("obfuscation.classes", technique.classes()), null));
        box.add(rows);
        if (found.hypotheses().size() > 1) {
            box.add(Box.createVerticalStrut(8));
            var others = Ui.rows(WIDTH);
            found.hypotheses().stream().skip(1).limit(4).forEach(hypothesis -> Ui.row(others, Text.t("deob.alternative", Ui.visible(hypothesis.obfuscator())),
                hypothesis.score() + "/100", null));
            box.add(others);
        }
        return box;
    }

    private static JComponent reverted(ResultInsights insights, ResultIndex.Reversal reversal) {
        var box = new Ui.Column();
        box.setOpaque(false);
        var rows = Ui.rows(WIDTH);
        if (reversal.methodsRewritten() > 0)
            Ui.row(rows, Text.t("deob.methodsRewritten"), String.format(Text.locale(), "%,d", reversal.methodsRewritten()), Icons.dot(Theme.level(ResultIndex.Level.EXACT), 10));
        for (var count : insights.reverted())
            Ui.row(rows, Ui.visible(count.label()), String.format(Text.locale(), "%,d", count.value()), Icons.dot(Theme.level(ResultIndex.Level.EXACT), 10));
        if (rows.getComponentCount() == 0) {
            box.add(Ui.muted(reversal.applied() ? Text.t("deob.reverted.none") : Text.t("deob.reverted.notApplied")));
            return box;
        }
        box.add(rows);
        var signatures = reversal.signatures();
        if (signatures != null) {
            box.add(Box.createVerticalStrut(8));
            double kept = insights.methodsKept();
            var bar = new Ui.Bar(new int[] {signatures.keptMethods(), Math.max(0, signatures.originalMethods() - signatures.keptMethods())},
                new Color[] {Theme.level(ResultIndex.Level.EXACT), Theme.line()}, null);
            bar.setAlignmentX(LEFT_ALIGNMENT);
            bar.setMaximumSize(new Dimension(WIDTH, 8));
            bar.setPreferredSize(new Dimension(WIDTH, 8));
            box.add(bar);
            box.add(Box.createVerticalStrut(4));
            box.add(Ui.muted(Text.t("deob.signatures", signatures.keptMethods(), signatures.originalMethods(), signatures.keptFields(),
                signatures.originalFields(), kept < 0 ? "—" : OverviewPanel.percent(signatures.keptMethods(), signatures.originalMethods()))));
            if (!signatures.removedClasses().isEmpty()) {
                var shown = signatures.removedClasses().stream().limit(6).map(name -> Ui.visible(name.replace('/', '.'))).toList();
                var text = Ui.text(Text.t("deob.removed", signatures.removedClasses().size(), String.join(", ", shown)
                    + (signatures.removedClasses().size() > shown.size() ? ", …" : "")), true);
                box.add(Box.createVerticalStrut(2));
                box.add(text);
            }
        }
        return box;
    }

    private static JComponent notRecovered(ResultInsights insights, ResultIndex.Reversal reversal) {
        var box = new Ui.Column();
        box.setOpaque(false);
        var counts = insights.notRecovered();
        if (reversal.status().equals("REFUSED") || reversal.status().equals("FAILED")) {
            box.add(Ui.text(Text.t("deob.notRecovered.refused", reversal.reason() == null ? "—" : Ui.visible(reversal.reason())), false));
            return box;
        }
        if (counts.isEmpty()) {
            box.add(Ui.muted(Text.t("deob.notRecovered.none")));
            return box;
        }
        var rows = Ui.rows(WIDTH);
        for (var count : counts)
            Ui.row(rows, Ui.visible(count.label()), String.format(Text.locale(), "%,d", count.value()), Ui.severityIcon(ResultInsights.Severity.WARNING, 12));
        box.add(rows);
        box.add(Box.createVerticalStrut(6));
        box.add(Ui.text(Text.t("deob.notRecovered.meaning"), true));
        return box;
    }
}
