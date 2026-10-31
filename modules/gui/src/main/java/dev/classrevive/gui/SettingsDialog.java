package dev.classrevive.gui;

import dev.classrevive.workbench.RequestLimits;
import java.awt.*;
import java.nio.file.Path;
import javax.swing.*;

/** Phase G3: appearance, decompile defaults and folders. Theme and font size apply at once; the language on restart. */
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
        form.setBorder(Ui.padding(16, 18, 8, 18));
        var at = new GridBagConstraints();
        at.anchor = GridBagConstraints.WEST;
        at.insets = new Insets(4, 0, 4, 12);
        int[] row = {0};
        Runnable gap = () -> row[0]++;
        java.util.function.BiConsumer<String,JComponent> line = (label, field) -> {
            at.gridy = row[0]++; at.gridx = 0; at.gridwidth = 1; at.fill = GridBagConstraints.NONE; at.weightx = 0;
            form.add(new JLabel(label), at);
            at.gridx = 1; at.fill = GridBagConstraints.HORIZONTAL; at.weightx = 1;
            form.add(field, at);
        };
        java.util.function.Consumer<String> heading = text -> {
            at.gridy = row[0]++; at.gridx = 0; at.gridwidth = 2; at.fill = GridBagConstraints.NONE;
            var label = Ui.heading(text, 13f);
            label.setBorder(Ui.padding(row[0] == 1 ? 0 : 10, 0, 2, 0));
            form.add(label, at);
        };
        heading.accept(Text.t("settings.appearance"));
        line.accept(Text.t("settings.theme"), theme);
        line.accept(Text.t("settings.language"), language);
        line.accept(Text.t("settings.fontSize"), fontSize);
        heading.accept(Text.t("settings.decompile"));
        line.accept(Text.t("settings.profile"), profile);
        line.accept(Text.t("decompile.timeout"), timeout);
        line.accept("", stubs);
        line.accept("", cache);
        heading.accept(Text.t("settings.folders"));
        var choose = new JButton(Text.t("settings.choose"));
        var folderRow = new JPanel(new BorderLayout(6, 0));
        folderRow.add(folder, BorderLayout.CENTER);
        folderRow.add(choose, BorderLayout.EAST);
        line.accept(Text.t("settings.workspace"), folderRow);
        gap.run();
        at.gridy = row[0]; at.gridx = 0; at.gridwidth = 2;
        form.add(Ui.muted("<html><body style='width:420px'>" + Ui.escape(Text.t("settings.workspace.note")) + "</body></html>"), at);
        choose.addActionListener(e -> {
            var chooser = new JFileChooser(folder.getText());
            chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) folder.setText(chooser.getSelectedFile().getPath());
        });

        var buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        buttons.setBorder(Ui.padding(8, 18, 14, 18));
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
