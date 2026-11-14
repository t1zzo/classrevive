package dev.classrevive.gui;

import com.formdev.flatlaf.FlatClientProperties;
import dev.classrevive.workbench.ResultIndex;
import java.awt.*;
import java.awt.event.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import javax.swing.*;

/**
 * Phase G3: search the selected sources of the current result; results open the class at the line. Phase G8: each hit
 * shows its class, its line as a badge and the code with the match highlighted.
 */
final class SearchPanel extends JPanel {
    static final int LIMIT = 2000;

    private final JTextField field = new JTextField(28);
    private final JCheckBox matchCase = new JCheckBox(Text.t("search.matchCase"));
    private final JButton run = new JButton(Text.t("search.run"));
    private final JLabel status = Ui.muted("");
    private final DefaultListModel<SourceSearch.Hit> model = new DefaultListModel<>();
    private final JList<SourceSearch.Hit> results = new JList<>(model);
    private final BiConsumer<String,Integer> open;
    private Workspace workspace;
    private AtomicBoolean cancelled = new AtomicBoolean();
    private String lastQuery = "";
    private boolean lastCase;

    /** {@code open} receives the input's class name and the 1-based line. */
    SearchPanel(BiConsumer<String,Integer> open) {
        super(new BorderLayout());
        this.open = open;
        var top = new JPanel(new FlowLayout(FlowLayout.LEFT, Ui.GAP, 6));
        top.setBorder(Ui.padding(4, 8, 2, 8));
        field.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, Text.t("search.placeholder"));
        field.putClientProperty(FlatClientProperties.TEXT_FIELD_LEADING_ICON, Icons.of(Icons.Glyph.SEARCH, 14));
        field.putClientProperty(FlatClientProperties.TEXT_FIELD_SHOW_CLEAR_BUTTON, true);
        run.setIcon(Icons.of(Icons.Glyph.SEARCH, 13));
        top.add(field); top.add(matchCase); top.add(run); top.add(status);
        add(top, BorderLayout.NORTH);
        results.setCellRenderer(new Renderer(() -> lastQuery, () -> lastCase));
        results.setFixedCellHeight(26);
        var scroll = new JScrollPane(results);
        scroll.setBorder(Ui.topLine());
        add(scroll, BorderLayout.CENTER);
        run.addActionListener(e -> search());
        field.addActionListener(e -> search());
        results.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent event) { if (event.getClickCount() == 2) openSelected(); }
        });
        results.getInputMap().put(KeyStroke.getKeyStroke("ENTER"), "open");
        results.getActionMap().put("open", new AbstractAction() { @Override public void actionPerformed(ActionEvent e) { openSelected(); } });
        attach(null);
    }

    void attach(Workspace workspace) {
        this.workspace = workspace;
        cancelled.set(true);
        model.clear();
        boolean ready = workspace != null && workspace.decompiled();
        field.setEnabled(ready); run.setEnabled(ready); matchCase.setEnabled(ready);
        status.setText(ready ? "" : Text.t("search.needsResult"));
    }

    void focus(String text) {
        if (text != null && !text.isBlank() && !text.contains("\n")) field.setText(text);
        field.requestFocusInWindow();
        field.selectAll();
    }

    private void search() {
        if (workspace == null || !workspace.decompiled() || field.getText().isEmpty()) return;
        cancelled.set(true);
        var flag = new AtomicBoolean();
        cancelled = flag;
        String query = field.getText();
        boolean caseSensitive = matchCase.isSelected();
        lastQuery = query;
        lastCase = caseSensitive;
        var root = workspace.result().output().resolve(ResultIndex.SELECTION).resolve("sources");
        model.clear();
        status.setText(Text.t("search.running"));
        new SwingWorker<List<SourceSearch.Hit>,Void>() {
            @Override protected List<SourceSearch.Hit> doInBackground() throws Exception {
                return SourceSearch.search(root, query, caseSensitive, LIMIT, flag::get);
            }
            @Override protected void done() {
                if (flag.get()) return;
                try {
                    var hits = get();
                    hits.forEach(model::addElement);
                    status.setText(hits.isEmpty() ? Text.t("search.none") : hits.size() >= LIMIT ? Text.t("search.limit", LIMIT) : Text.t("search.count", hits.size()));
                } catch (Exception failure) {
                    status.setText(Text.t("search.failed", String.valueOf(failure.getMessage())));
                }
            }
        }.execute();
    }

    /** For tests and the screenshot tour: searches {@code text} as if typed and run. */
    void searchFor(String text) { field.setText(text); search(); }

    private void openSelected() {
        var hit = results.getSelectedValue();
        if (hit != null && workspace != null) open.accept(workspace.originalOf(hit.className()), hit.line());
    }

    /** Phase G8: the line with each match in bold accent; at most 160 characters around the first match. */
    static String highlight(String line, String query, boolean caseSensitive, String color) {
        if (query == null || query.isEmpty()) return Ui.escape(line);
        String haystack = caseSensitive ? line : line.toLowerCase(java.util.Locale.ROOT), needle = caseSensitive ? query : query.toLowerCase(java.util.Locale.ROOT);
        // Lower-casing can change a string's length (some Unicode letters): then the offsets no longer match, so no highlight.
        if (haystack.length() != line.length()) return Ui.escape(line.length() > 160 ? line.substring(0, 160) + "…" : line);
        int first = haystack.indexOf(needle);
        int from = first > 60 ? first - 60 : 0;
        String shown = line.substring(from, Math.min(line.length(), from + 160));
        String lower = haystack.substring(from, Math.min(line.length(), from + 160));
        var out = new StringBuilder(from > 0 ? "…" : "");
        int at = 0;
        for (int hit = lower.indexOf(needle); hit >= 0 && !needle.isEmpty(); hit = lower.indexOf(needle, at)) {
            out.append(Ui.escape(shown.substring(at, hit))).append("<b><font color='").append(color).append("'>")
                .append(Ui.escape(shown.substring(hit, hit + needle.length()))).append("</font></b>");
            at = hit + needle.length();
        }
        out.append(Ui.escape(shown.substring(at)));
        return out.toString();
    }

    private static final class Renderer extends DefaultListCellRenderer {
        private final java.util.function.Supplier<String> query;
        private final java.util.function.BooleanSupplier caseSensitive;
        Renderer(java.util.function.Supplier<String> query, java.util.function.BooleanSupplier caseSensitive) { this.query = query; this.caseSensitive = caseSensitive; }

        @Override public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean selected, boolean focus) {
            super.getListCellRendererComponent(list, value, index, selected, false);
            if (value instanceof SourceSearch.Hit hit) {
                String simple = hit.className().substring(hit.className().lastIndexOf('/') + 1);
                String muted = hex(selected ? getForeground() : Theme.muted()), accent = hex(selected ? getForeground() : Theme.accentText());
                String code = highlight(hit.text().strip(), query.get(), caseSensitive.getAsBoolean(), accent);
                setText("<html><b>" + Ui.escape(simple) + "</b>&nbsp;<font color='" + muted + "'>:" + hit.line() + "</font>&nbsp;&nbsp;"
                    + "<font face='" + Theme.codeFont(12).getFamily() + "'>" + code + "</font></html>");
                setIcon(Icons.kind('C', ExplorerPanel.kindColor('C'), null));
                setIconTextGap(8);
                setToolTipText(Ui.visible(hit.className().replace('/', '.')) + ":" + hit.line());
            }
            return this;
        }

        private static String hex(Color color) { return String.format("#%06X", color.getRGB() & 0xFFFFFF); }
    }
}
