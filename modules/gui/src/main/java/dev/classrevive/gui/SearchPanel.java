package dev.classrevive.gui;

import com.formdev.flatlaf.FlatClientProperties;
import dev.classrevive.workbench.ResultIndex;
import java.awt.*;
import java.awt.event.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import javax.swing.*;

/** Phase G3: search the selected sources of the current result; results open the class at the line. */
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

    /** {@code open} receives the input's class name and the 1-based line. */
    SearchPanel(BiConsumer<String,Integer> open) {
        super(new BorderLayout());
        this.open = open;
        var top = new JPanel(new FlowLayout(FlowLayout.LEFT, Ui.GAP, 6));
        top.setBorder(Ui.padding(2, 6, 0, 6));
        field.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, Text.t("search.placeholder"));
        field.putClientProperty(FlatClientProperties.TEXT_FIELD_LEADING_ICON, Icons.of(Icons.Glyph.SEARCH, 14));
        field.putClientProperty(FlatClientProperties.TEXT_FIELD_SHOW_CLEAR_BUTTON, true);
        top.add(field); top.add(matchCase); top.add(run); top.add(status);
        add(top, BorderLayout.NORTH);
        results.setCellRenderer(new Renderer());
        results.setFixedCellHeight(24);
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

    private void openSelected() {
        var hit = results.getSelectedValue();
        if (hit != null && workspace != null) open.accept(workspace.originalOf(hit.className()), hit.line());
    }

    private static final class Renderer extends DefaultListCellRenderer {
        @Override public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean selected, boolean focus) {
            super.getListCellRendererComponent(list, value, index, selected, focus);
            if (value instanceof SourceSearch.Hit hit) {
                String simple = hit.className().substring(hit.className().lastIndexOf('/') + 1);
                setText("<html><b>" + Ui.escape(simple) + "</b>:" + hit.line() + " &nbsp;<code>" + Ui.escape(hit.text()) + "</code></html>");
                setIcon(Icons.of(Icons.Glyph.RESOURCE, 14));
            }
            return this;
        }
    }
}
