package dev.classrevive.gui;

import dev.classrevive.workbench.EngineRegistry;
import dev.classrevive.workbench.RequestLimits;
import java.awt.*;
import java.nio.file.Path;
import java.util.*;
import java.util.List;
import javax.swing.*;

/** Phase G3: the options of one decompile (engines, dependency JARs, stubs, time limit), optionally kept as the defaults. */
final class DecompileDialog extends JDialog {
    /** What the user chose; {@code engines} is the CLI syntax ({@code cfr,vineflower}). */
    record Choice(String engines, int timeoutSeconds, boolean stubs, List<Path> dependencies) {}

    private final JRadioButton fast = new JRadioButton(Text.t("profile.FAST"));
    private final JRadioButton complete = new JRadioButton(Text.t("profile.COMPLETE"));
    private final JRadioButton custom = new JRadioButton(Text.t("profile.CUSTOM"));
    private final Map<String,JCheckBox> engineBoxes = new LinkedHashMap<>();
    private final JSpinner timeout;
    private final JCheckBox stubs = new JCheckBox(Text.t("decompile.stubs"));
    private final DefaultListModel<Path> dependencies = new DefaultListModel<>();
    private final JCheckBox remember = new JCheckBox(Text.t("decompile.remember"), true);
    private Choice choice;

    DecompileDialog(Window owner, GuiSettings settings, List<Path> current) {
        super(owner, Text.t("decompile.title"), ModalityType.APPLICATION_MODAL);
        timeout = new JSpinner(new SpinnerNumberModel(settings.timeoutSeconds(), RequestLimits.MIN_TIMEOUT_SECONDS, RequestLimits.MAX_TIMEOUT_SECONDS, 30));
        var content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setBorder(Ui.padding(16, 18, 12, 18));

        content.add(section(Text.t("decompile.engines")));
        var group = new ButtonGroup();
        for (var button : List.of(fast, complete, custom)) { group.add(button); content.add(left(button)); }
        content.add(left(Ui.muted("   " + Text.t("profile.FAST.note"))));
        var boxes = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 2));
        boxes.setBorder(Ui.padding(0, 22, 0, 0));
        for (var engine : EngineRegistry.all()) {
            var box = new JCheckBox(engine.id() + " " + engine.version());
            engineBoxes.put(engine.id(), box);
            boxes.add(box);
        }
        content.add(left(boxes));
        var selected = Set.of(settings.customEngines().split(","));
        engineBoxes.forEach((id, box) -> box.setSelected(selected.contains(id)));
        switch (settings.profile()) { case FAST -> fast.setSelected(true); case COMPLETE -> complete.setSelected(true); case CUSTOM -> custom.setSelected(true); }
        for (var button : List.of(fast, complete, custom)) button.addActionListener(e -> syncBoxes());
        syncBoxes();

        content.add(Box.createVerticalStrut(12));
        content.add(section(Text.t("decompile.dependencies")));
        content.add(left(Ui.muted("<html><body style='width:420px'>" + Ui.escape(Text.t("decompile.dependencies.note")) + "</body></html>")));
        current.forEach(dependencies::addElement);
        var list = new JList<>(dependencies);
        list.setVisibleRowCount(4);
        var scroll = new JScrollPane(list);
        scroll.setPreferredSize(new Dimension(440, 86));
        content.add(left(scroll));
        var listButtons = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        var add = new JButton(Text.t("decompile.dependencies.add"));
        var remove = new JButton(Text.t("decompile.dependencies.remove"));
        listButtons.add(add); listButtons.add(remove);
        content.add(left(listButtons));
        add.addActionListener(e -> addJars(settings));
        remove.addActionListener(e -> list.getSelectedValuesList().forEach(dependencies::removeElement));

        content.add(Box.createVerticalStrut(12));
        content.add(section(Text.t("decompile.verification")));
        stubs.setSelected(settings.stubs());
        content.add(left(stubs));
        content.add(left(Ui.muted("<html><body style='width:420px'>" + Ui.escape(Text.t("decompile.stubs.note")) + "</body></html>")));
        var time = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 6));
        time.add(new JLabel(Text.t("decompile.timeout")));
        time.add(timeout);
        time.add(Ui.muted(Text.t("decompile.timeout.note")));
        content.add(left(time));

        content.add(Box.createVerticalStrut(8));
        content.add(left(remember));
        var buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        var cancel = new JButton(Text.t("dialog.cancel"));
        var run = Ui.primary(Ui.action(Text.t("action.decompile"), Icons.of(Icons.Glyph.PLAY, 14, Theme.ON_ACCENT), null, this::accept));
        buttons.add(cancel); buttons.add(run);
        cancel.addActionListener(e -> dispose());
        var root = new JPanel(new BorderLayout());
        root.add(content, BorderLayout.CENTER);
        buttons.setBorder(Ui.padding(0, 18, 14, 18));
        root.add(buttons, BorderLayout.SOUTH);
        setContentPane(root);
        getRootPane().setDefaultButton(run);
        getRootPane().registerKeyboardAction(e -> dispose(), KeyStroke.getKeyStroke("ESCAPE"), JComponent.WHEN_IN_FOCUSED_WINDOW);
        pack();
        setLocationRelativeTo(owner);
    }

    /** Shows the dialog; empty when cancelled. Applies the choice to {@code settings} when "remember" is checked. */
    static Optional<Choice> ask(Window owner, GuiSettings settings, List<Path> dependencies) {
        var dialog = new DecompileDialog(owner, settings, dependencies);
        dialog.setVisible(true);
        var choice = dialog.choice;
        if (choice != null && dialog.remember.isSelected()) {
            settings.profile(dialog.fast.isSelected() ? GuiSettings.Profile.FAST : dialog.complete.isSelected() ? GuiSettings.Profile.COMPLETE : GuiSettings.Profile.CUSTOM);
            if (dialog.custom.isSelected()) settings.customEngines(choice.engines());
            settings.timeoutSeconds(choice.timeoutSeconds());
            settings.stubs(choice.stubs());
        }
        return Optional.ofNullable(choice);
    }

    private void accept() {
        String engines = fast.isSelected() ? "cfr,vineflower" : complete.isSelected() ? "all"
            : String.join(",", engineBoxes.entrySet().stream().filter(e -> e.getValue().isSelected()).map(Map.Entry::getKey).toList());
        if (engines.isEmpty()) { Ui.error(this, Text.t("decompile.title"), Text.t("decompile.noEngine"), null); return; }
        if (dependencies.size() > RequestLimits.MAX_DEPENDENCIES) { Ui.error(this, Text.t("decompile.title"), Text.t("decompile.tooManyJars", RequestLimits.MAX_DEPENDENCIES), null); return; }
        var jars = new ArrayList<Path>();
        for (int i = 0; i < dependencies.size(); i++) jars.add(dependencies.get(i));
        choice = new Choice(engines.equals("all") ? "cfr,vineflower,procyon,jadx" : engines, (Integer) timeout.getValue(), stubs.isSelected(), List.copyOf(jars));
        dispose();
    }

    private void addJars(GuiSettings settings) {
        var chooser = new JFileChooser(settings.lastDirectory() == null ? null : settings.lastDirectory().toFile());
        chooser.setMultiSelectionEnabled(true);
        chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter(Text.t("files.jar"), "jar"));
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        for (var file : chooser.getSelectedFiles()) if (!dependencies.contains(file.toPath())) dependencies.addElement(file.toPath());
    }

    private void syncBoxes() {
        boolean editable = custom.isSelected();
        engineBoxes.forEach((id, box) -> {
            box.setEnabled(editable);
            if (fast.isSelected()) box.setSelected(id.equals("cfr") || id.equals("vineflower"));
            if (complete.isSelected()) box.setSelected(true);
        });
    }

    private static JComponent section(String title) {
        var label = Ui.heading(title, 13f);
        label.setBorder(Ui.padding(0, 0, 4, 0));
        return left(label);
    }

    private static JComponent left(JComponent component) { component.setAlignmentX(LEFT_ALIGNMENT); return component; }
}
