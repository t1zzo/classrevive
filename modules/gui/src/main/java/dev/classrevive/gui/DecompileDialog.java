package dev.classrevive.gui;

import com.formdev.flatlaf.FlatClientProperties;
import dev.classrevive.workbench.EngineRegistry;
import dev.classrevive.workbench.RequestLimits;
import java.awt.*;
import java.nio.file.Path;
import java.util.*;
import java.util.List;
import javax.swing.*;

/**
 * Phase G3: the options of one decompile (engines, dependency JARs, stubs, time limit), optionally kept as the defaults.
 * Phase G8: the three profiles are cards with what each one costs, the engine boxes show which engines a profile runs and
 * are editable for the custom one, and the library list says what it is for while empty.
 */
final class DecompileDialog extends JDialog {
    /** What the user chose; {@code engines} is the CLI syntax ({@code cfr,vineflower}). */
    record Choice(String engines, int timeoutSeconds, boolean stubs, List<Path> dependencies) {}

    private final JRadioButton fast = new JRadioButton(Text.t("profile.FAST.title"));
    private final JRadioButton complete = new JRadioButton(Text.t("profile.COMPLETE.title"));
    private final JRadioButton custom = new JRadioButton(Text.t("profile.CUSTOM.title"));
    private final Map<JRadioButton,Ui.Card> cards = new LinkedHashMap<>();
    private final Map<String,JCheckBox> engineBoxes = new LinkedHashMap<>();
    private final JPanel boxes = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 0));
    private final JSpinner timeout;
    private final JCheckBox stubs = new JCheckBox(Text.t("decompile.stubs"));
    private final DefaultListModel<Path> dependencies = new DefaultListModel<>();
    private final JCheckBox remember = new JCheckBox(Text.t("decompile.remember"), true);
    private Choice choice;

    DecompileDialog(Window owner, GuiSettings settings, List<Path> current) {
        super(owner, Text.t("decompile.title"), ModalityType.APPLICATION_MODAL);
        timeout = new JSpinner(new SpinnerNumberModel(settings.timeoutSeconds(), RequestLimits.MIN_TIMEOUT_SECONDS, RequestLimits.MAX_TIMEOUT_SECONDS, 30));
        var content = new Ui.Column();
        content.setBorder(Ui.padding(18, 22, 12, 22));

        content.add(Ui.styled(Text.t("decompile.heading"), "h3"));
        content.add(Box.createVerticalStrut(4));
        content.add(Ui.text(Text.t("decompile.intro"), true));
        content.add(Box.createVerticalStrut(16));

        content.add(Ui.caption(Text.t("decompile.engines")));
        content.add(Box.createVerticalStrut(8));
        var group = new ButtonGroup();
        var profiles = new JPanel(new GridLayout(1, 3, 10, 0));
        profiles.setOpaque(false);
        for (var button : List.of(fast, complete, custom)) {
            group.add(button);
            var card = profileCard(button, Text.t(button == fast ? "profile.FAST.description" : button == complete ? "profile.COMPLETE.description" : "profile.CUSTOM.description"));
            cards.put(button, card);
            profiles.add(card);
        }
        content.add(profiles);
        content.add(Box.createVerticalStrut(8));
        boxes.setOpaque(false);
        boxes.setBorder(Ui.padding(2, 2, 0, 0));
        for (var engine : EngineRegistry.all()) {
            var box = new JCheckBox(CodeTab.engineName(engine.id()));
            box.setToolTipText(engine.id() + " " + engine.version());
            engineBoxes.put(engine.id(), box);
            boxes.add(box);
        }
        content.add(boxes);
        var selected = Set.of(settings.customEngines().split(","));
        engineBoxes.forEach((id, box) -> box.setSelected(selected.contains(id)));
        switch (settings.profile()) { case FAST -> fast.setSelected(true); case COMPLETE -> complete.setSelected(true); case CUSTOM -> custom.setSelected(true); }
        for (var button : List.of(fast, complete, custom)) button.addActionListener(e -> syncBoxes());
        syncBoxes();

        content.add(Box.createVerticalStrut(18));
        content.add(Ui.caption(Text.t("decompile.dependencies")));
        content.add(Box.createVerticalStrut(4));
        content.add(Ui.text(Text.t("decompile.dependencies.note"), true));
        content.add(Box.createVerticalStrut(8));
        current.forEach(dependencies::addElement);
        var list = new JList<>(dependencies);
        list.setVisibleRowCount(4);
        list.setCellRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> l, Object value, int index, boolean selectedRow, boolean focus) {
                super.getListCellRendererComponent(l, value, index, selectedRow, focus);
                if (value instanceof Path path) {
                    setText(Ui.visible(String.valueOf(path.getFileName())));
                    setToolTipText(Ui.visible(path.toString()));
                    setIcon(Icons.of(Icons.Glyph.LIBRARY, 14));
                }
                return this;
            }
        });
        var listCards = new CardLayout();
        var listHolder = new JPanel(listCards);
        listHolder.setOpaque(false);
        var scroll = new JScrollPane(list);
        listHolder.add(scroll, "list");
        var empty = new JPanel(new GridBagLayout());
        empty.setBorder(BorderFactory.createLineBorder(Theme.line()));
        empty.add(Ui.faint(Text.t("decompile.dependencies.empty")));
        listHolder.add(empty, "empty");
        listHolder.setPreferredSize(new Dimension(560, 92));
        listHolder.setMaximumSize(new Dimension(Integer.MAX_VALUE, 92));
        content.add(listHolder);
        var listButtons = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 6));
        listButtons.setOpaque(false);
        var add = new JButton(Text.t("decompile.dependencies.add"), Icons.of(Icons.Glyph.LIBRARY, 14));
        var remove = new JButton(Text.t("decompile.dependencies.remove"));
        listButtons.add(add); listButtons.add(remove);
        content.add(listButtons);
        Runnable syncList = () -> {
            listCards.show(listHolder, dependencies.isEmpty() ? "empty" : "list");
            remove.setEnabled(!list.isSelectionEmpty());
        };
        add.addActionListener(e -> { addJars(settings); syncList.run(); });
        remove.addActionListener(e -> { list.getSelectedValuesList().forEach(dependencies::removeElement); syncList.run(); });
        list.addListSelectionListener(e -> syncList.run());
        syncList.run();

        content.add(Box.createVerticalStrut(14));
        content.add(Ui.caption(Text.t("decompile.verification")));
        content.add(Box.createVerticalStrut(6));
        stubs.setSelected(settings.stubs());
        content.add(stubs);
        var stubsNote = Ui.text(Text.t("decompile.stubs.note"), true);
        stubsNote.setBorder(Ui.padding(0, 26, 0, 0));
        content.add(stubsNote);
        content.add(Box.createVerticalStrut(8));
        var time = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        time.setOpaque(false);
        time.add(new JLabel(Text.t("decompile.timeout")));
        timeout.setPreferredSize(new Dimension(96, timeout.getPreferredSize().height));
        time.add(timeout);
        time.add(Ui.muted(Text.t("decompile.timeout.note")));
        content.add(time);

        var footer = new JPanel(new BorderLayout());
        footer.setBorder(BorderFactory.createCompoundBorder(Ui.topLine(), Ui.padding(12, 22, 14, 22)));
        footer.add(remember, BorderLayout.WEST);
        var buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        buttons.setOpaque(false);
        var cancel = new JButton(Text.t("dialog.cancel"));
        var run = Ui.primary(Ui.action(Text.t("action.decompile"), Icons.of(Icons.Glyph.PLAY, 14, Theme.ON_ACCENT), null, this::accept));
        buttons.add(cancel); buttons.add(run);
        footer.add(buttons, BorderLayout.EAST);
        cancel.addActionListener(e -> dispose());
        var root = new JPanel(new BorderLayout());
        root.add(content, BorderLayout.CENTER);
        root.add(footer, BorderLayout.SOUTH);
        setContentPane(root);
        getRootPane().setDefaultButton(run);
        getRootPane().registerKeyboardAction(e -> dispose(), KeyStroke.getKeyStroke("ESCAPE"), JComponent.WHEN_IN_FOCUSED_WINDOW);
        pack();
        setSize(Math.max(getWidth(), 640), getHeight());
        setLocationRelativeTo(owner);
    }

    /** One profile: its radio button, what it does, and the whole card selects it. */
    private Ui.Card profileCard(JRadioButton button, String description) {
        var card = new Ui.Card(null);
        card.clickable(() -> { button.setSelected(true); syncBoxes(); });
        card.setLayout(new BorderLayout(0, 4));
        card.setBorder(Ui.padding(10, 12, 12, 12));
        button.setOpaque(false);
        button.putClientProperty(FlatClientProperties.STYLE, "font: $semibold.font");
        card.add(button, BorderLayout.NORTH);
        var note = Ui.text(description, true);
        note.setBorder(Ui.padding(0, 24, 0, 0));
        note.setRows(2);
        card.add(note, BorderLayout.CENTER);
        return card;
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
        cards.forEach((button, card) -> card.selected(button.isSelected()));
    }
}
