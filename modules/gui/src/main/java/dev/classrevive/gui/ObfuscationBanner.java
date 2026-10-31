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
        setBorder(BorderFactory.createCompoundBorder(Ui.bottomLine(), Ui.padding(5, 12, 5, 10)));
        text.setIcon(Icons.of(Icons.Glyph.SHIELD, 16, Theme.ACCENT));
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
        g.setColor(Theme.dark() ? new Color(0x1E1408) : new Color(0xFFF1E3));
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

    /** The score and the number of techniques, or what an unknown verdict means. */
    static String note(ObfuscationCheck.Detection detection) {
        return switch (detection.verdict()) {
            case "NONE" -> Text.t("obfuscation.noneNote", detection.classes());
            case "UNKNOWN" -> Text.t("obfuscation.unknownNote", detection.techniques().size());
            default -> Text.t("obfuscation.likelyNote", detection.top().score(), detection.techniques().size());
        };
    }

    private void showDetails() {
        if (detection == null) return;
        var html = new StringBuilder("<html><body style='width:520px'>");
        html.append("<p>").append(Ui.escape(Text.t("obfuscation.disclaimer"))).append("</p>");
        if (!detection.hypotheses().isEmpty()) {
            html.append("<h3>").append(Ui.escape(Text.t("obfuscation.hypotheses"))).append("</h3>");
            for (var hypothesis : detection.hypotheses()) {
                html.append("<p><b>").append(Ui.escape(hypothesis.obfuscator())).append("</b> &nbsp;").append(hypothesis.score()).append("/100, ")
                    .append(Ui.escape(Text.t("confidence." + hypothesis.confidence()))).append("</p><ul>");
                hypothesis.reasons().stream().limit(12).forEach(reason -> html.append("<li>").append(Ui.escape(reason)).append("</li>"));
                html.append("</ul>");
            }
        }
        if (!detection.techniques().isEmpty()) {
            html.append("<h3>").append(Ui.escape(Text.t("obfuscation.techniques"))).append("</h3><ul>");
            detection.techniques().forEach(technique -> html.append("<li>").append(Ui.escape(technique.label())).append(" &nbsp;<span style='color:gray'>")
                .append(Ui.escape(Text.t("obfuscation.classes", technique.classes()))).append("</span></li>"));
            html.append("</ul>");
        }
        html.append("</body></html>");
        var label = new JLabel(html.toString());
        var scroll = new JScrollPane(label);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.setPreferredSize(new Dimension(580, Math.min(460, label.getPreferredSize().height + 12)));
        JOptionPane.showMessageDialog(window, scroll, Text.t("obfuscation.title"), JOptionPane.PLAIN_MESSAGE);
    }
}
