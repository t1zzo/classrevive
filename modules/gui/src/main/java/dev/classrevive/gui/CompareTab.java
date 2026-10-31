package dev.classrevive.gui;

import com.formdev.flatlaf.FlatClientProperties;
import dev.classrevive.workbench.ResultIndex;
import java.awt.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import javax.swing.*;
import org.fife.ui.rsyntaxtextarea.*;
import org.fife.ui.rtextarea.RTextScrollPane;

/**
 * Phase G4: one class's source in two candidates (the selected source and any engine), aligned line by line with the
 * differences colored, both sides scrolling together. Read-only; the diff runs off the event thread.
 */
final class CompareTab extends JPanel {
    final String key;
    private final Workspace workspace;
    private final Workspace.ClassItem item;
    private final JComboBox<CodeTab.View> left = new JComboBox<>(), right = new JComboBox<>();
    private final RSyntaxTextArea leftArea = area(), rightArea = area();
    private final RTextScrollPane leftScroll = new RTextScrollPane(leftArea), rightScroll = new RTextScrollPane(rightArea);
    private final JLabel summary = Ui.muted("");
    private final GuiSettings settings;
    private SwingWorker<LineDiff.Aligned,Void> running;

    CompareTab(Workspace workspace, Workspace.ClassItem item, GuiSettings settings) {
        super(new BorderLayout());
        this.key = "compare:" + item.name();
        this.workspace = workspace;
        this.item = item;
        this.settings = settings;
        var result = workspace.result();
        var views = new ArrayList<CodeTab.View>();
        for (String candidate : result.candidates())
            views.add(new CodeTab.View(candidate, candidate.equals(ResultIndex.SELECTION) ? Text.t("code.view.selected") : candidate));
        left.setModel(new DefaultComboBoxModel<>(views.toArray(CodeTab.View[]::new)));
        right.setModel(new DefaultComboBoxModel<>(views.toArray(CodeTab.View[]::new)));
        left.setSelectedIndex(0);
        right.setSelectedIndex(Math.min(1, views.size() - 1));

        var header = new JPanel(new BorderLayout(Ui.GAP, 0));
        header.setBorder(BorderFactory.createCompoundBorder(Ui.bottomLine(), Ui.padding(6, 12, 6, 12)));
        var title = new JLabel("<html>" + Ui.escape(Text.t("compare.title")) + " &nbsp;<b>" + Ui.escape(item.simpleName()) + "</b></html>");
        title.setIcon(Icons.of(Icons.Glyph.COMPARE, 14));
        header.add(title, BorderLayout.WEST);
        var pickers = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        pickers.setOpaque(false);
        pickers.add(summary);
        pickers.add(Box.createHorizontalStrut(12));
        pickers.add(left);
        pickers.add(Ui.muted("↔"));
        pickers.add(right);
        header.add(pickers, BorderLayout.EAST);
        add(header, BorderLayout.NORTH);

        // Rows are aligned with filler lines, so source line numbers would mislead: none are shown.
        for (var scroll : List.of(leftScroll, rightScroll)) { scroll.setBorder(BorderFactory.createEmptyBorder()); scroll.setLineNumbersEnabled(false); }
        // One vertical model for both sides: the aligned rows stay side by side.
        rightScroll.getVerticalScrollBar().setModel(leftScroll.getVerticalScrollBar().getModel());
        var split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, leftScroll, rightScroll);
        split.setResizeWeight(0.5);
        split.setBorder(BorderFactory.createEmptyBorder());
        add(split, BorderLayout.CENTER);
        left.addActionListener(e -> compare());
        right.addActionListener(e -> compare());
        applyLook();
        compare();
    }

    String title() { return Text.t("compare.tab", Ui.visible(item.simpleName())); }

    private static RSyntaxTextArea area() {
        var area = new RSyntaxTextArea();
        area.setEditable(false);
        area.setSyntaxEditingStyle(SyntaxConstants.SYNTAX_STYLE_JAVA);
        area.setCodeFoldingEnabled(false);
        area.setHighlightCurrentLine(false);
        area.setAntiAliasingEnabled(true);
        return area;
    }

    private void compare() {
        if (!(left.getSelectedItem() instanceof CodeTab.View a) || !(right.getSelectedItem() instanceof CodeTab.View b)) return;
        if (running != null) running.cancel(true);
        summary.setText(Text.t("compare.running"));
        running = new SwingWorker<>() {
            @Override protected LineDiff.Aligned doInBackground() throws Exception { return LineDiff.align(read(a), read(b)); }
            @Override protected void done() {
                if (isCancelled()) return;
                try { show(get()); }
                catch (Exception failure) { summary.setText(Text.t("compare.failed", String.valueOf(failure.getMessage()))); }
            }
        };
        running.execute();
    }

    private String read(CodeTab.View view) throws java.io.IOException {
        Path source = workspace.source(item.name(), view.id()).orElse(null);
        if (source == null) return Text.t("code.noSource", view.label()) + "\n";
        if (Files.size(source) > CodeTab.MAX_SOURCE_BYTES) return Text.t("code.tooLarge", source.getFileName()) + "\n";
        return Files.readString(source, StandardCharsets.UTF_8);
    }

    private void show(LineDiff.Aligned aligned) {
        leftArea.setText(aligned.leftText());
        rightArea.setText(aligned.rightText());
        leftArea.removeAllLineHighlights();
        rightArea.removeAllLineHighlights();
        Color changed = tint(Theme.level(ResultIndex.Level.PER_FILE)), removed = tint(Theme.level(ResultIndex.Level.UNVERIFIED)),
            added = tint(Theme.level(ResultIndex.Level.EXACT)), filler = tint(Theme.muted());
        for (int row = 0; row < aligned.kinds().size(); row++) {
            try {
                switch (aligned.kinds().get(row)) {
                    case CHANGED -> { leftArea.addLineHighlight(row, changed); rightArea.addLineHighlight(row, changed); }
                    case REMOVED -> { leftArea.addLineHighlight(row, removed); rightArea.addLineHighlight(row, filler); }
                    case ADDED -> { leftArea.addLineHighlight(row, filler); rightArea.addLineHighlight(row, added); }
                    case SAME -> { }
                }
            } catch (javax.swing.text.BadLocationException ignored) { }
        }
        leftArea.setCaretPosition(0);
        rightArea.setCaretPosition(0);
        long differences = aligned.count(LineDiff.Kind.CHANGED) + aligned.count(LineDiff.Kind.REMOVED) + aligned.count(LineDiff.Kind.ADDED);
        summary.setText(differences == 0 ? Text.t("compare.same") : Text.t("compare.summary", aligned.count(LineDiff.Kind.CHANGED),
            aligned.count(LineDiff.Kind.REMOVED), aligned.count(LineDiff.Kind.ADDED)));
    }

    private static Color tint(Color color) { return new Color(color.getRed(), color.getGreen(), color.getBlue(), Theme.dark() ? 46 : 52); }

    void applyLook() {
        for (var area : List.of(leftArea, rightArea)) {
            String file = Theme.dark() ? "dark.xml" : "idea.xml";
            try (var in = RSyntaxTextArea.class.getResourceAsStream("/org/fife/ui/rsyntaxtextarea/themes/" + file)) {
                org.fife.ui.rsyntaxtextarea.Theme.load(in).apply(area);
            } catch (java.io.IOException | RuntimeException ignored) { }
            area.setFont(Theme.codeFont(settings.fontSize()));
            if (Theme.dark()) { CodeTab.darkScheme(area); area.setBackground(Theme.editor()); }
        }
        for (var scroll : List.of(leftScroll, rightScroll)) {
            scroll.getGutter().setLineNumberFont(Theme.codeFont(Math.max(GuiSettings.MIN_FONT, settings.fontSize() - 1)));
            if (Theme.dark()) scroll.getGutter().setBackground(Theme.editor());
        }
        left.putClientProperty(FlatClientProperties.MINIMUM_WIDTH, 150);
        right.putClientProperty(FlatClientProperties.MINIMUM_WIDTH, 150);
    }
}
