package dev.classrevive.gui;

import dev.classrevive.workbench.RequestLimits;
import java.awt.*;
import java.nio.file.Path;
import javax.swing.*;

/**
 * Phase G3: appearance, decompile defaults and folders. Theme and font size apply at once; the language on restart. Phase
 * G8: sections with captions and a hint under the choices that are not obvious, compact number fields, and a button that
 * opens the results folder.
 */
final class SettingsDialog extends JDialog {
    private final JComboBox<GuiSettings.ThemeChoice> theme = new JComboBox<>(GuiSettings.ThemeChoice.values());
    private final JComboBox<GuiSettings.Language> language = new JComboBox<>(GuiSettings.Language.values());
    private final JSpinner fontSize;
    private final JComboBox<GuiSettings.Profile> profile = new JComboBox<>(GuiSettings.Profile.values());
    private final JSpinner timeout;
    private final JCheckBox stubs = new JCheckBox(Text.t("decompile.stubs"));
    private final JCheckBox cache = new JCheckBox(Text.t("settings.cache"));
    private final JTextField folder = new JTextField(30);
    private boolean saved;

    SettingsDialog(Window owner, GuiSettings settings) {
        super(owner, Text.t("settings.title"), ModalityType.APPLICATION_MODAL);
        fontSize = new JSpinner(new SpinnerNumberModel(settings.fontSize(), GuiSettings.MIN_FONT, GuiSettings.MAX_FONT, 1));
        timeout = new JSpinner(new SpinnerNumberModel(settings.timeoutSeconds(), RequestLimits.MIN_TIMEOUT_SECONDS, RequestLimits.MAX_TIMEOUT_SECONDS, 30));
        theme.setRenderer(renderer("theme."));
        language.setRenderer(renderer("language."));
        profile.setRenderer(renderer("profile."));
        theme.setSelectedItem(settings.theme());
        language.setSelectedItem(settings.language());
        profile.setSelectedItem(settings.profile());
        stubs.setSelected(settings.stubs());
        cache.setSelected(settings.engineCache());
        folder.setText(settings.workspaceDirectory().toString());

        var form = new JPanel(new GridBagLayout());
        form.setBorder(Ui.padding(18, 22, 10, 22));
        var at = new GridBagConstraints();
        at.anchor = GridBagConstraints.WEST;
        at.insets = new Insets(4, 0, 4, 16);
        int[] row = {0};
        java.util.function.BiConsumer<String,JComponent> line = (label, field) -> {
            at.gridy = row[0]++; at.gridx = 0; at.gridwidth = 1; at.fill = GridBagConstraints.NONE; at.weightx = 0; at.insets = new Insets(4, 0, 4, 16);
            form.add(label.isEmpty() ? new JLabel() : new JLabel(label), at);
            at.gridx = 1; at.weightx = 1;
            at.fill = field instanceof JSpinner ? GridBagConstraints.NONE : GridBagConstraints.HORIZONTAL;
            form.add(field, at);
        };
        java.util.function.Consumer<String> note = text -> {
            at.gridy = row[0]++; at.gridx = 1; at.gridwidth = 1; at.fill = GridBagConstraints.HORIZONTAL; at.weightx = 1; at.insets = new Insets(0, 0, 4, 0);
            var area = Ui.text(text, true);
            area.setColumns(36);
            form.add(area, at);
        };
        java.util.function.Consumer<String> heading = text -> {
            at.gridy = row[0]++; at.gridx = 0; at.gridwidth = 2; at.fill = GridBagConstraints.HORIZONTAL; at.weightx = 1;
            at.insets = new Insets(row[0] == 1 ? 0 : 16, 0, 6, 0);
            var label = Ui.caption(text);
            label.setBorder(BorderFactory.createCompoundBorder(Ui.bottomLine(), Ui.padding(0, 0, 6, 0)));
            form.add(label, at);
            at.gridwidth = 1;
        };
        for (var spinner : java.util.List.of(fontSize, timeout)) spinner.setPreferredSize(new Dimension(96, spinner.getPreferredSize().height));
        heading.accept(Text.t("settings.appearance"));
        line.accept(Text.t("settings.theme"), theme);
        line.accept(Text.t("settings.language"), language);
        note.accept(Text.t("settings.language.note"));
        line.accept(Text.t("settings.fontSize"), fontSize);
        heading.accept(Text.t("settings.decompile"));
        line.accept(Text.t("settings.profile"), profile);
        line.accept(Text.t("decompile.timeout"), timeout);
        line.accept("", stubs);
        line.accept("", cache);
        heading.accept(Text.t("settings.folders"));
        var choose = new JButton(Text.t("settings.choose"));
        var reveal = new JButton(Icons.of(Icons.Glyph.FOLDER, 14));
        reveal.setToolTipText(Text.t("settings.openFolder"));
        reveal.addActionListener(e -> {
            try {
                var path = Path.of(folder.getText().strip());
                java.nio.file.Files.createDirectories(path);
                Ui.open(this, path);
            } catch (Exception invalid) { Ui.error(this, Text.t("settings.title"), Text.t("settings.badFolder"), null); }
        });
        var folderRow = new JPanel(new BorderLayout(6, 0));
        folderRow.add(folder, BorderLayout.CENTER);
        var folderButtons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        folderButtons.add(choose);
        folderButtons.add(reveal);
        folderRow.add(folderButtons, BorderLayout.EAST);
        line.accept(Text.t("settings.workspace"), folderRow);
        note.accept(Text.t("settings.workspace.note"));
        choose.addActionListener(e -> {
            var chooser = new JFileChooser(folder.getText());
            chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) folder.setText(chooser.getSelectedFile().getPath());
        });

        var buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        buttons.setBorder(BorderFactory.createCompoundBorder(Ui.topLine(), Ui.padding(12, 22, 14, 22)));
        var cancel = new JButton(Text.t("dialog.cancel"));
        var ok = Ui.primary(Ui.action(Text.t("dialog.save"), null, null, () -> save(settings)));
        buttons.add(cancel); buttons.add(ok);
        cancel.addActionListener(e -> dispose());
        var root = new JPanel(new BorderLayout());
        root.add(form, BorderLayout.CENTER);
        root.add(buttons, BorderLayout.SOUTH);
        setContentPane(root);
        getRootPane().setDefaultButton(ok);
        getRootPane().registerKeyboardAction(e -> dispose(), KeyStroke.getKeyStroke("ESCAPE"), JComponent.WHEN_IN_FOCUSED_WINDOW);
        pack();
        setLocationRelativeTo(owner);
    }

    /** True when the user saved; the settings object then holds the new values. */
    static boolean edit(Window owner, GuiSettings settings) {
        var dialog = new SettingsDialog(owner, settings);
        dialog.setVisible(true);
        return dialog.saved;
    }

    private void save(GuiSettings settings) {
        Path workspace;
        try { workspace = Path.of(folder.getText().strip()); }
        catch (RuntimeException invalid) { Ui.error(this, Text.t("settings.title"), Text.t("settings.badFolder"), null); return; }
        if (folder.getText().isBlank() || !workspace.isAbsolute()) { Ui.error(this, Text.t("settings.title"), Text.t("settings.badFolder"), null); return; }
        settings.theme((GuiSettings.ThemeChoice) theme.getSelectedItem());
        settings.language((GuiSettings.Language) language.getSelectedItem());
        settings.fontSize((Integer) fontSize.getValue());
        settings.profile((GuiSettings.Profile) profile.getSelectedItem());
        settings.timeoutSeconds((Integer) timeout.getValue());
        settings.stubs(stubs.isSelected());
        settings.engineCache(cache.isSelected());
        settings.workspaceDirectory(workspace);
        saved = true;
        dispose();
    }

    private static ListCellRenderer<Object> renderer(String prefix) {
        return new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean selected, boolean focus) {
                super.getListCellRendererComponent(list, value, index, selected, focus);
                if (value instanceof Enum<?> e) setText(Text.t(prefix + e.name()));
                return this;
            }
        };
    }
}
