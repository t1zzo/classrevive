package dev.classrevive.gui;

import dev.classrevive.workbench.ObfuscationCheck;
import java.awt.*;
import javax.swing.*;

/**
 * Phase G5: a thin bar under the toolbar when the input looks obfuscated: the likely obfuscator with its confidence, the
 * evidence on demand, and for Skidfuscator the reversal before decompiling. Hidden for inputs with no technique found.
 */
final class ObfuscationBanner extends JPanel {
    private final MainWindow window;
    private final JLabel text = new JLabel();
    private final JButton details = new JButton(Text.t("obfuscation.details"));
    private final JButton reverse;
    private ObfuscationCheck.Detection detection;

    ObfuscationBanner(MainWindow window) {
        super(new BorderLayout(Ui.GAP, 0));
        this.window = window;
        setBorder(BorderFactory.createCompoundBorder(Ui.bottomLine(), Ui.padding(6, 16, 6, 10)));
        text.setIcon(Icons.of(Icons.Glyph.SHIELD, 16, Theme.ACCENT));
        text.setIconTextGap(10);
        add(text, BorderLayout.CENTER);
        var buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        buttons.setOpaque(false);
        reverse = Ui.primary(Ui.action(Text.t("skid.reverseAndDecompile"), Icons.of(Icons.Glyph.PLAY, 14, Theme.ON_ACCENT), null,
            () -> window.decompile(false, true)));
        details.putClientProperty(com.formdev.flatlaf.FlatClientProperties.BUTTON_TYPE, com.formdev.flatlaf.FlatClientProperties.BUTTON_TYPE_TOOLBAR_BUTTON);
        buttons.add(details);
        buttons.add(reverse);
        add(buttons, BorderLayout.EAST);
        details.addActionListener(e -> showDetails());
        setVisible(false);
    }

    /** Shows what the workspace's classes point to; hidden when nothing was found or no workspace is open. */
    void show(Workspace workspace) {
        detection = workspace == null ? null : workspace.obfuscation();
        if (workspace != null && workspace.reversedFrom() != null) {
            text.setText("<html>" + Ui.escape(Text.t("obfuscation.bannerReversed", workspace.reversedFrom())) + "</html>");
            reverse.setVisible(false);
            details.setVisible(detection != null);
            setVisible(true);
            return;
        }
        if (detection == null || detection.verdict().equals("NONE")) { setVisible(false); return; }
        text.setText("<html><b>" + Ui.escape(headline(detection)) + "</b> &nbsp;" + Ui.escape(note(detection)) + "</html>");
        reverse.setVisible(detection.skidfuscator());
        details.setVisible(true);
        setVisible(true);
    }

    /** For tests: the bar is shown and offers the Skidfuscator reversal. */
    boolean offersReversal() { return isVisible() && reverse.isVisible(); }

    @Override protected void paintComponent(Graphics g) {
        g.setColor(Theme.accentSoft());
        g.fillRect(0, 0, getWidth(), getHeight());
        g.setColor(Theme.ACCENT);
        g.fillRect(0, 0, 3, getHeight());
    }

    /** "Skidfuscator (high confidence)", "Unknown obfuscator" or "No obfuscation found". */
    static String headline(ObfuscationCheck.Detection detection) {
        return switch (detection.verdict()) {
            case "NONE" -> Text.t("obfuscation.none");
            case "UNKNOWN" -> Text.t("obfuscation.unknown");
            default -> Text.t("obfuscation.likely", detection.top().obfuscator(), Text.t("confidence." + detection.top().confidence()));
        };
    }

    /** Phase G8: the verdict in a word or two, for the overview tile: "Skidfuscator", "Unknown", "None". */
    static String shortVerdict(ObfuscationCheck.Detection detection) {
        return switch (detection.verdict()) {
            case "NONE" -> Text.t("obfuscation.noneShort");
            case "UNKNOWN" -> Text.t("obfuscation.unknownShort");
            default -> Ui.visible(detection.top().obfuscator());
        };
    }

    /** Phase G8: the tile's note: the confidence and score, or what was checked. */
    static String shortNote(ObfuscationCheck.Detection detection) {
        return switch (detection.verdict()) {
            case "NONE" -> Text.t("obfuscation.noneNote", detection.classes());
            case "UNKNOWN" -> Text.t("obfuscation.unknownNote", detection.techniques().size());
            default -> Text.t("obfuscation.shortNote", Text.t("confidence." + detection.top().confidence()), detection.top().score());
        };
    }

    /** Phase G7: the same wording for the report's own section ({@code report.json} {@code obfuscation}). */
    static String verdictName(dev.classrevive.workbench.ResultIndex.ReportedObfuscation found) {
        if (found.verdict().equals("NONE")) return Text.t("obfuscation.none");
        var top = found.top();
        if (found.verdict().equals("UNKNOWN") || top == null) return Text.t("obfuscation.unknown");
        return Text.t("obfuscation.likely", top.obfuscator(), Text.t("confidence." + top.confidence()));
    }

    /** The score and the number of techniques, or what an unknown verdict means. */
    static String note(ObfuscationCheck.Detection detection) {
        return switch (detection.verdict()) {
            case "NONE" -> Text.t("obfuscation.noneNote", detection.classes());
            case "UNKNOWN" -> Text.t("obfuscation.unknownNote", detection.techniques().size());
            default -> Text.t("obfuscation.likelyNote", detection.top().score(), detection.techniques().size());
        };
    }

    /**
     * Phase G9: the evidence in a dialog of its own: the disclaimer first, then one card per hypothesis (score bar, confidence
     * and reasons), then the techniques with how many classes each touches.
     */
    private void showDetails() {
        var dialog = detailsDialog();
        if (dialog != null) dialog.setVisible(true);
    }

    /** The details dialog, built but not shown; null without a detection. */
    JDialog detailsDialog() {
        if (detection == null) return null;
        var body = new Ui.Column();
        body.setBorder(Ui.padding(16, 18, 16, 18));
        body.add(Ui.notice(new ResultInsights.Notice(ResultInsights.Severity.INFO, Text.t("obfuscation.disclaimerTitle"), Text.t("obfuscation.disclaimer"))));
        if (!detection.hypotheses().isEmpty()) {
            body.add(Box.createVerticalStrut(16));
            body.add(Ui.section(Text.t("obfuscation.hypotheses")));
            for (var hypothesis : detection.hypotheses()) {
                var card = new Ui.Card(hypothesis == detection.top() ? Theme.ACCENT : null);
                card.setLayout(new BorderLayout(0, 8));
                card.setBorder(Ui.padding(12, 16, 12, 14));
                var head = new JPanel(new BorderLayout(10, 0));
                head.setOpaque(false);
                head.add(Ui.styled(Ui.visible(hypothesis.obfuscator()), "semibold"), BorderLayout.WEST);
                head.add(Ui.softChip(Text.t("confidence." + hypothesis.confidence()) + " \u00b7 " + hypothesis.score() + "/100", Theme.ACCENT), BorderLayout.EAST);
                card.add(head, BorderLayout.NORTH);
                var details = new Ui.Column();
                details.setOpaque(false);
                var bar = new Ui.Bar(new int[] {hypothesis.score(), 100 - hypothesis.score()}, new Color[] {Theme.ACCENT, Theme.line()},
                    hypothesis.score() + "/100");
                bar.setPreferredSize(new Dimension(240, 6));
                details.add(bar);
                details.add(Box.createVerticalStrut(8));
                hypothesis.reasons().stream().limit(12).forEach(reason -> details.add(Ui.text("\u2022  " + Ui.visible(reason), true)));
                if (hypothesis.reasons().size() > 12) details.add(Ui.faint(Text.t("obfuscation.moreReasons", hypothesis.reasons().size() - 12)));
                card.add(details, BorderLayout.CENTER);
                body.add(card);
                body.add(Box.createVerticalStrut(8));
            }
        }
        if (!detection.techniques().isEmpty()) {
            body.add(Box.createVerticalStrut(8));
            body.add(Ui.section(Text.t("obfuscation.techniques")));
            var card = new Ui.Card(null);
            card.setLayout(new BorderLayout());
            card.setBorder(Ui.padding(8, 16, 8, 14));
            var rows = Ui.rows(Integer.MAX_VALUE);
            detection.techniques().forEach(technique -> Ui.row(rows, Ui.visible(technique.label()), Text.t("obfuscation.classes", technique.classes()), null));
            card.add(rows, BorderLayout.CENTER);
            body.add(card);
        }
        var scroll = new JScrollPane(body, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        scroll.setPreferredSize(new Dimension(600, Math.min(540, body.getPreferredSize().height + 4)));
        var dialog = new JDialog(window, Text.t("obfuscation.title"), Dialog.ModalityType.APPLICATION_MODAL);
        var close = new JButton(Text.t("dialog.close"));
        close.addActionListener(e -> dialog.dispose());
        var footer = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        footer.setBorder(BorderFactory.createCompoundBorder(Ui.topLine(), Ui.padding(10, 18, 12, 18)));
        footer.add(close);
        var root = new JPanel(new BorderLayout());
        root.add(scroll, BorderLayout.CENTER);
        root.add(footer, BorderLayout.SOUTH);
        dialog.setContentPane(root);
        dialog.getRootPane().setDefaultButton(close);
        dialog.getRootPane().registerKeyboardAction(e -> dialog.dispose(), KeyStroke.getKeyStroke("ESCAPE"), JComponent.WHEN_IN_FOCUSED_WINDOW);
        dialog.pack();
        dialog.setLocationRelativeTo(window);
        return dialog;
    }
}
