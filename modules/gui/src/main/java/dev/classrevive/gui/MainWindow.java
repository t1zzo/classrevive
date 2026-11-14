package dev.classrevive.gui;

import com.formdev.flatlaf.FlatClientProperties;
import dev.classrevive.bytecode.CallHierarchy;
import dev.classrevive.bytecode.ReferenceIndex;
import dev.classrevive.engine.EngineResult;
import dev.classrevive.pipeline.InputIdentifier;
import dev.classrevive.pipeline.JobListener;
import dev.classrevive.workbench.*;
import java.awt.*;
import java.awt.datatransfer.*;
import java.awt.event.*;
import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;
import javax.swing.*;

/**
 * Phase G3: the main window. Explorer on the left, editor tabs in the middle, inspector on the right, activity and search
 * below. Every slow step (opening, decompiling, searching, exporting) runs off the event thread; a decompile can be
 * cancelled at any time and its worker processes stop with it.
 */
final class MainWindow extends JFrame {
    static final int MAX_TABS = 24;

    private final GuiSettings settings;
    private final CardLayout cards = new CardLayout();
    private final JPanel center = new JPanel(cards);
    private final WelcomePanel welcome;
    private final ExplorerPanel explorer;
    private final InspectorPanel inspector;
    private final JTabbedPane tabs = new JTabbedPane();
    private final JTabbedPane bottom = new JTabbedPane();
    private final ActivityPanel activity;
    private final SearchPanel search;
    private final UsagesPanel usages;
    private UsageIndex usageIndex;
    private UsageIndex.Built shown;
    /** Phase G6: asks which member is meant when several match; tests replace it. */
    MemberChooser.Ask chooser = MemberChooser::choose;
    private final JSplitPane vertical, inner, outer;
    private final JLabel message = new JLabel(" ");
    private final JLabel caret = Ui.muted("");
    /** Phase G8: the Java heap in use, so large inputs show what they cost; refreshed every 3 s while the window exists. */
    private final JLabel memory = Ui.faint("");
    private final javax.swing.Timer memoryClock = new javax.swing.Timer(3000, e -> refreshMemory());
    private final StatusDot statusDot = new StatusDot();
    private JButton cancelButton;
    private final ObfuscationBanner banner = new ObfuscationBanner(this);
    private final JProgressBar busy = new JProgressBar();
    private final List<Action> workspaceActions = new ArrayList<>(), resultActions = new ArrayList<>();
    private final JMenu recentMenu = new JMenu(Text.t("menu.recent"));
    private final JCheckBoxMenuItem inspectorItem = new JCheckBoxMenuItem(), bottomItem = new JCheckBoxMenuItem();
    private Workspace workspace;
    private List<Path> dependencies = new ArrayList<>();
    private Thread running;
    private boolean opening;

    final Action open, openResult, decompile, decompileOptions, cancel, goToClass, searchCode, exportProject, exportSources, openReport,
        openFolder, closeWorkspace, find, closeTab, copyName, settingsAction, overview, zoomIn, zoomOut, shortcuts, about, exit,
        back, forward, compare, findUsages, callHierarchy, toggleTheme, closeOthers, closeAll;
    /** Phase G4: where the user was (a class tab and its caret), for Alt+Left and Alt+Right. */
    record Place(String className, int caret) {}
    private final Deque<Place> backStack = new ArrayDeque<>(), forwardStack = new ArrayDeque<>();
    static final int MAX_HISTORY = 50;

    MainWindow(GuiSettings settings) {
        super("ClassRevive");
        this.settings = settings;
        setIconImages(List.of(ClassReviveApp.logo(16), ClassReviveApp.logo(32), ClassReviveApp.logo(48), ClassReviveApp.logo(256)));
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() { @Override public void windowClosing(WindowEvent event) { exit(); } });

        open = Ui.action(Text.t("action.open"), Icons.of(Icons.Glyph.OPEN), Ui.key("ctrl O"), this::chooseInput);
        openResult = Ui.action(Text.t("action.openResult"), Icons.of(Icons.Glyph.FOLDER_RESULT), Ui.key("ctrl shift O"), this::chooseResult);
        decompile = Ui.action(Text.t("action.decompile"), Icons.of(Icons.Glyph.PLAY, 16, Theme.ON_ACCENT), Ui.key("F5"), () -> decompile(false));
        decompileOptions = Ui.action(Text.t("action.decompileOptions"), null, Ui.key("shift F5"), () -> decompile(true));
        cancel = Ui.action(Text.t("action.cancel"), Icons.of(Icons.Glyph.STOP), Ui.key("ctrl shift F5"), this::cancel);
        goToClass = Ui.action(Text.t("action.goToClass"), Icons.of(Icons.Glyph.GOTO), Ui.key("ctrl N"), this::goToClass);
        searchCode = Ui.action(Text.t("action.search"), Icons.of(Icons.Glyph.SEARCH), Ui.key("ctrl shift F"), this::searchCode);
        exportProject = Ui.action(Text.t("action.exportProject"), Icons.of(Icons.Glyph.EXPORT), Ui.key("ctrl E"), this::exportProject);
        exportSources = Ui.action(Text.t("action.exportSources"), null, null, this::exportSources);
        openReport = Ui.action(Text.t("action.openReport"), Icons.of(Icons.Glyph.REPORT), null, () -> { if (result() != null) Ui.open(this, result().output().resolve("report.html")); });
        openFolder = Ui.action(Text.t("action.openFolder"), Icons.of(Icons.Glyph.FOLDER), null, () -> { if (result() != null) Ui.open(this, result().output()); });
        closeWorkspace = Ui.action(Text.t("action.close"), null, null, this::closeWorkspace);
        find = Ui.action(Text.t("action.find"), null, Ui.key("ctrl F"), () -> { if (tabs.getSelectedComponent() instanceof CodeTab tab) tab.openFind(); });
        closeTab = Ui.action(Text.t("action.closeTab"), null, Ui.key("ctrl W"), () -> { int i = tabs.getSelectedIndex(); if (i >= 0) tabs.removeTabAt(i); });
        copyName = Ui.action(Text.t("action.copyName"), null, Ui.key("ctrl shift C"), this::copyName);
        settingsAction = Ui.action(Text.t("action.settings"), Icons.of(Icons.Glyph.SETTINGS), Ui.key("ctrl COMMA"), this::editSettings);
        overview = Ui.action(Text.t("action.overview"), Icons.of(Icons.Glyph.REPORT), Ui.key("ctrl shift H"), this::showOverview);
        zoomIn = Ui.action(Text.t("action.zoomIn"), null, Ui.key("ctrl EQUALS"), () -> zoom(1));
        zoomOut = Ui.action(Text.t("action.zoomOut"), null, Ui.key("ctrl MINUS"), () -> zoom(-1));
        shortcuts = Ui.action(Text.t("action.shortcuts"), null, Ui.key("F1"), this::showShortcuts);
        about = Ui.action(Text.t("action.about"), null, null, this::showAbout);
        exit = Ui.action(Text.t("action.exit"), null, null, this::exit);
        back = Ui.action(Text.t("action.back"), Icons.of(Icons.Glyph.BACK), Ui.key("alt LEFT"), () -> travel(backStack, forwardStack));
        forward = Ui.action(Text.t("action.forward"), Icons.of(Icons.Glyph.FORWARD), Ui.key("alt RIGHT"), () -> travel(forwardStack, backStack));
        compare = Ui.action(Text.t("action.compare"), Icons.of(Icons.Glyph.COMPARE), Ui.key("ctrl D"), this::compareCurrent);
        findUsages = Ui.action(Text.t("action.findUsages"), null, Ui.key("alt F7"), () -> usagesOf(false));
        callHierarchy = Ui.action(Text.t("action.callHierarchy"), null, Ui.key("ctrl alt H"), () -> usagesOf(true));
        toggleTheme = Ui.action(Text.t("action.toggleTheme"), Icons.of(Theme.dark() ? Icons.Glyph.SUN : Icons.Glyph.MOON), null,
            () -> applyTheme(Theme.dark() ? GuiSettings.ThemeChoice.LIGHT : GuiSettings.ThemeChoice.DARK));
        closeOthers = Ui.action(Text.t("action.closeOthers"), null, null, () -> closeTabs(tabs.getSelectedIndex()));
        closeAll = Ui.action(Text.t("action.closeAll"), null, null, () -> closeTabs(-1));
        workspaceActions.addAll(List.of(decompile, decompileOptions, goToClass, closeWorkspace, findUsages, callHierarchy));
        resultActions.addAll(List.of(searchCode, exportProject, exportSources, openReport, openFolder, overview, compare));

        welcome = new WelcomePanel(settings, open, openResult, this::openPath, () -> { settings.clearRecent(); saveSettings(); refreshRecent(); });
        inspector = new InspectorPanel(() -> decompile(false));
        explorer = new ExplorerPanel(this::openClass, this::openResource, this::selected);
        activity = new ActivityPanel(cancel);
        search = new SearchPanel((name, line) -> workspace.classItem(name).ifPresent(item -> openClass(item, line)));
        usages = new UsagesPanel(this::openSite, () -> { if (usageIndex != null) usageIndex.cancel(); });
        explorer.popup(List.of(
            Ui.action(Text.t("action.findUsages"), null, Ui.key("alt F7"), () -> { if (explorer.selected() instanceof ExplorerPanel.ClassNode node) classUsages(node.item()); }),
            Ui.action(Text.t("action.memberUsages"), null, null, () -> { if (explorer.selected() instanceof ExplorerPanel.ClassNode node) memberOf(node.item(), false); }),
            Ui.action(Text.t("action.callHierarchy"), null, Ui.key("ctrl alt H"), () -> { if (explorer.selected() instanceof ExplorerPanel.ClassNode node) memberOf(node.item(), true); })));

        tabs.putClientProperty(FlatClientProperties.TABBED_PANE_TAB_CLOSABLE, true);
        tabs.putClientProperty(FlatClientProperties.TABBED_PANE_TAB_CLOSE_CALLBACK, (BiConsumer<JTabbedPane,Integer>) (pane, index) -> pane.removeTabAt(index));
        tabs.putClientProperty(FlatClientProperties.TABBED_PANE_TAB_TYPE, FlatClientProperties.TABBED_PANE_TAB_TYPE_UNDERLINED);
        tabs.putClientProperty(FlatClientProperties.TABBED_PANE_SCROLL_BUTTONS_POLICY, FlatClientProperties.TABBED_PANE_POLICY_AS_NEEDED);
        tabs.setTabLayoutPolicy(JTabbedPane.SCROLL_TAB_LAYOUT);
        tabs.addChangeListener(e -> caret.setText(tabs.getSelectedComponent() instanceof CodeTab tab ? tab.caretText() : ""));
        tabs.addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent event) { tabMenu(event); }
            @Override public void mouseReleased(MouseEvent event) { tabMenu(event); }
            @Override public void mouseClicked(MouseEvent event) {
                // Phase G8: a middle click closes a tab, as in browsers and IDEs.
                int index = tabs.indexAtLocation(event.getX(), event.getY());
                if (index >= 0 && SwingUtilities.isMiddleMouseButton(event)) tabs.removeTabAt(index);
            }
        });
        var emptyTabs = emptyEditor();
        var editorArea = new JPanel(new CardLayout());
        editorArea.add(emptyTabs, "empty");
        editorArea.add(tabs, "tabs");
        tabs.addContainerListener(new ContainerAdapter() {
            @Override public void componentAdded(ContainerEvent e) { ((CardLayout) editorArea.getLayout()).show(editorArea, "tabs"); }
            @Override public void componentRemoved(ContainerEvent e) { if (tabs.getTabCount() == 0) ((CardLayout) editorArea.getLayout()).show(editorArea, "empty"); }
        });

        bottom.addTab(Text.t("bottom.activity"), activity);
        bottom.addTab(Text.t("bottom.search"), search);
        bottom.addTab(Text.t("bottom.usages"), usages);
        bottom.putClientProperty(FlatClientProperties.TABBED_PANE_TAB_HEIGHT, 28);
        bottom.putClientProperty(FlatClientProperties.TABBED_PANE_TAB_TYPE, FlatClientProperties.TABBED_PANE_TAB_TYPE_UNDERLINED);
        bottom.setBorder(Ui.topLine());
        vertical = new JSplitPane(JSplitPane.VERTICAL_SPLIT, editorArea, bottom);
        vertical.setResizeWeight(1);
        vertical.setBorder(BorderFactory.createEmptyBorder());
        inner = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, vertical, inspector);
        inner.setResizeWeight(1);
        inner.setBorder(BorderFactory.createEmptyBorder());
        explorer.setPreferredSize(new Dimension(300, 400));
        outer = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, explorer, inner);
        outer.setBorder(BorderFactory.createEmptyBorder());
        center.add(welcome.scroller(), "welcome");
        center.add(outer, "work");

        var root = new JPanel(new BorderLayout());
        var north = new JPanel(new BorderLayout());
        north.add(toolbar(), BorderLayout.NORTH);
        north.add(banner, BorderLayout.SOUTH);
        root.add(north, BorderLayout.NORTH);
        root.add(center, BorderLayout.CENTER);
        root.add(statusBar(), BorderLayout.SOUTH);
        setContentPane(root);
        setJMenuBar(menus());
        setTransferHandler(new FileDrop());
        // Phase G8: the start screen's drop zone lights up while a file is dragged over the window.
        if (getDropTarget() != null) {
            try {
                getDropTarget().addDropTargetListener(new java.awt.dnd.DropTargetAdapter() {
                    @Override public void dragEnter(java.awt.dnd.DropTargetDragEvent event) { welcome.dragging(event.isDataFlavorSupported(DataFlavor.javaFileListFlavor)); }
                    @Override public void dragExit(java.awt.dnd.DropTargetEvent event) { welcome.dragging(false); }
                    @Override public void drop(java.awt.dnd.DropTargetDropEvent event) { welcome.dragging(false); }
                });
            } catch (java.util.TooManyListenersException single) { /* the highlight is optional */ }
        }
        memoryClock.start();
        refreshMemory();
        inspectorItem.setSelected(settings.showInspector());
        bottomItem.setSelected(settings.showActivity());
        refreshRecent();
        refreshActions();
        setSize(1380, 860);
        setMinimumSize(new Dimension(960, 600));
        setLocationRelativeTo(null);
        SwingUtilities.invokeLater(() -> { outer.setDividerLocation(300); inner.setDividerLocation(inner.getWidth() - 300); vertical.setDividerLocation(vertical.getHeight() - 236); });
        status(Text.t("status.ready"));
    }

    // ----- opening -----------------------------------------------------------------------------------------------

    private void chooseInput() {
        var chooser = chooser();
        chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter(Text.t("files.inputs"), "jar", "class", "exe", "zip", "war", "jmod"));
        chooser.setAcceptAllFileFilterUsed(true);
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) openPath(chooser.getSelectedFile().toPath());
    }

    private void chooseResult() {
        var chooser = chooser();
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        chooser.setDialogTitle(Text.t("action.openResult"));
        if (Files.isDirectory(settings.workspaceDirectory())) chooser.setCurrentDirectory(settings.workspaceDirectory().toFile());
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) openPath(chooser.getSelectedFile().toPath());
    }

    private JFileChooser chooser() {
        var chooser = new JFileChooser(settings.lastDirectory() == null ? null : settings.lastDirectory().toFile());
        chooser.addActionListener(e -> { if (chooser.getSelectedFile() != null) settings.lastDirectory(chooser.getSelectedFile().toPath().getParent()); });
        return chooser;
    }

    /** Opens an input file, or a result folder (one holding {@code report.json}). */
    void openPath(Path path) {
        if (running != null) { Ui.info(this, Text.t("app.name"), Text.t("busy.decompiling")); return; }
        if (opening) return;
        boolean folder = Files.isDirectory(path);
        if (!Files.exists(path)) {
            settings.forget(path);
            saveSettings();
            refreshRecent();
            Ui.error(this, Text.t("error.title"), Text.t("error.missing", path), null);
            return;
        }
        if (folder && !Files.isRegularFile(path.resolve("report.json"))) {
            Ui.error(this, Text.t("error.title"), Text.t("error.notResult", path.getFileName()), null);
            return;
        }
        opening = true;
        busy(true, Text.t("status.opening", Ui.visible(String.valueOf(path.getFileName()))));
        new SwingWorker<Workspace,Void>() {
            @Override protected Workspace doInBackground() throws Exception {
                return folder ? Workspace.openResult(path) : Workspace.openInput(path, settings.cacheDirectory());
            }
            @Override protected void done() {
                opening = false;
                busy(false, " ");
                try {
                    show(get());
                    settings.remember(path);
                    saveSettings();
                    refreshRecent();
                } catch (java.util.concurrent.ExecutionException failure) {
                    reportFailure(Text.t("error.openFailed", Ui.visible(String.valueOf(path.getFileName()))), failure.getCause());
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        }.execute();
    }

    private void show(Workspace opened) {
        workspace = opened;
        if (usageIndex != null) usageIndex.cancel();
        usageIndex = new UsageIndex(opened);
        shown = null;
        usages.message(Text.t("usages.empty"));
        usages.indexing("", false);
        backStack.clear();
        forwardStack.clear();
        dependencies = new ArrayList<>();
        tabs.removeAll();
        explorer.show(opened);
        inspector.show(opened, null);
        search.attach(opened);
        activity.reset(opened.decompiled() ? Ui.visible(opened.displayName()) : null);
        banner.show(opened);
        cards.show(center, "work");
        applyPanels();
        setTitle("ClassRevive — " + Ui.visible(opened.displayName()));
        statusDot.set(resultColor());
        refreshActions();
        if (opened.decompiled()) { showOverview(); status(summary(opened.result())); }
        else status(Text.t("status.opened", opened.classes().size(), Ui.visible(opened.displayName())));
        explorer.focusFilter();
    }

    private void closeWorkspace() {
        if (running != null) { Ui.info(this, Text.t("app.name"), Text.t("busy.decompiling")); return; }
        workspace = null;
        if (usageIndex != null) usageIndex.cancel();
        usageIndex = null;
        shown = null;
        usages.message(Text.t("usages.empty"));
        tabs.removeAll();
        search.attach(null);
        banner.show(null);
        welcome.refresh();
        cards.show(center, "welcome");
        setTitle("ClassRevive");
        statusDot.set(null);
        refreshActions();
        status(Text.t("status.ready"));
    }

    // ----- decompiling -------------------------------------------------------------------------------------------

    private void decompile(boolean ask) { decompile(ask, null); }

    /**
     * Phase G5: {@code reverse} true runs the Skidfuscator reversal first, false never does, null asks when the input
     * looks protected by Skidfuscator (and was not reversed already).
     */
    void decompile(boolean ask, Boolean reverse) {
        if (workspace == null || running != null) return;
        boolean declinedReversal = Boolean.FALSE.equals(reverse);
        if (reverse == null) {
            var detection = workspace.obfuscation();
            reverse = false;
            if (detection != null && detection.skidfuscator() && workspace.reversedFrom() == null) {
                var top = detection.top();
                Object[] options = {Text.t("skid.reverseAndDecompile"), Text.t("skid.decompileOriginal"), Text.t("dialog.cancel")};
                int answer = JOptionPane.showOptionDialog(this, "<html><body style='width:420px'>" + Ui.escape(Text.t("skid.question",
                    Text.t("confidence." + top.confidence()), top.score())) + "</body></html>", Text.t("skid.title"), JOptionPane.DEFAULT_OPTION,
                    JOptionPane.QUESTION_MESSAGE, null, options, options[0]);
                if (answer != 0 && answer != 1) return;
                reverse = answer == 0;
                declinedReversal = answer == 1;
            }
        }
        boolean reverseFirst = reverse, declined = declinedReversal;
        DecompileRequest request;
        try {
            if (ask) {
                var choice = DecompileDialog.ask(this, settings, dependencies);
                if (choice.isEmpty()) return;
                dependencies = new ArrayList<>(choice.get().dependencies());
                saveSettings();
                request = workspace.request(EngineRegistry.parse(choice.get().engines()), choice.get().timeoutSeconds(), choice.get().stubs(), dependencies, settings);
            } else request = workspace.request(settings, dependencies);
        } catch (IOException | IllegalArgumentException invalid) {
            reportFailure(Text.t("error.decompileFailed"), invalid);
            return;
        }
        var target = workspace;
        settingsAction.setEnabled(false);
        showBottom(activity);
        activity.start(target.displayName(), request.engines().stream().map(engine -> engine.id()).toList());
        busy(true, Text.t("status.decompiling", Ui.visible(target.displayName())));
        var listener = new JobListener() {
            @Override public void stage(Stage stage) { SwingUtilities.invokeLater(() -> activity.stage(stage)); }
            @Override public void engineStarted(String engine) { SwingUtilities.invokeLater(() -> activity.engineStarted(engine)); }
            @Override public void engineFinished(EngineResult result) { SwingUtilities.invokeLater(() -> activity.engineFinished(result)); }
        };
        running = Thread.ofPlatform().name("classrevive-decompile").start(() -> {
            try {
                if (reverseFirst) {
                    var reversed = reverseSkidfuscator(target, request);
                    if (reversed != null) {
                        // The result is the reversed program: reopen it, so the tree and bytecode show the reversed classes.
                        var output = new DecompileService().decompile(reversed.request(), listener).output();
                        var reopened = Workspace.openResult(output);
                        reopened.reversedFrom(target.displayName());
                        reopened.reversal(reversed.folder(), target.obfuscation());
                        SwingUtilities.invokeLater(() -> reversedAndDecompiled(target, reopened));
                        return;
                    }
                }
                // Phase 2DD: the job reverses a recognized obfuscator by itself, unless the user chose the original or the
                // reversal above already ran (and fell back to the original).
                boolean original = reverseFirst || declined;
                var index = target.decompile(original ? request.withDeobfuscation(false) : request, listener);
                SwingUtilities.invokeLater(() -> decompiled(target, index));
            } catch (InterruptedException cancelled) {
                SwingUtilities.invokeLater(() -> stopped(Text.t("activity.cancelled", request.output()), null));
            } catch (Throwable failure) {
                SwingUtilities.invokeLater(() -> stopped(Text.t("activity.failed"), failure));
            }
        });
        refreshActions();
    }

    /**
     * Phase G5: runs the Skidfuscator reversal into its own new folder beside the result and returns the request for the
     * reversed jar, or null (the original is then decompiled) when the reversal fails or the JVM verifier refuses a class.
     */
    private record Reversed(DecompileRequest request, Path folder) {}

    private Reversed reverseSkidfuscator(Workspace target, DecompileRequest request) throws InterruptedException {
        SwingUtilities.invokeLater(() -> activity.note(Text.t("skid.running")));
        try {
            String base = target.displayName().replaceAll("(?i)\\.(jar|class)$", "");
            Path folder = Workspace.newOutput(settings.workspaceDirectory(), base + "-skidfuscator-reversal");
            var result = SkidReversal.run(target.inspected(), folder, request.timeout());
            if (!result.usable()) {
                String reason = result.jar() == null ? Text.t("skid.noOutput") : Text.t("skid.notLinked", result.linkError());
                SwingUtilities.invokeLater(() -> activity.note(Text.t("skid.fallback", reason)));
                return null;
            }
            SwingUtilities.invokeLater(() -> activity.note(Text.t("skid.done", result.linkedClasses(), result.output())));
            return new Reversed(new DecompileRequest(result.jar(), request.output(), request.engines(), request.timeout(), request.dependencies(),
                request.engineCache(), request.stubs()), folder);
        } catch (IOException | RuntimeException failure) {
            String reason = String.valueOf(failure.getMessage());
            SwingUtilities.invokeLater(() -> activity.note(Text.t("skid.fallback", reason)));
            return null;
        }
    }

    private void reversedAndDecompiled(Workspace original, Workspace reversed) {
        running = null;
        busy(false, summary(reversed.result()));
        activity.finish(Text.t("activity.done", summary(reversed.result())), true);
        settingsAction.setEnabled(true);
        if (original != workspace) return;
        show(reversed);
        settings.remember(reversed.result().output());
        saveSettings();
        refreshRecent();
    }

    private void cancel() {
        if (running == null) return;
        running.interrupt();
        activity.cancelling();
        cancel.setEnabled(false);
    }

    private void decompiled(Workspace target, ResultIndex index) {
        running = null;
        busy(false, summary(index));
        activity.finish(Text.t("activity.done", summary(index)), true);
        settingsAction.setEnabled(true);
        if (target != workspace) return;
        explorer.refresh();
        inspector.show(target, explorer.selected());
        search.attach(target);
        for (int i = 0; i < tabs.getTabCount(); i++) if (tabs.getComponentAt(i) instanceof CodeTab tab) {
            tab.refreshViews();
            if (tab.classItem() != null) tabs.setIconAt(i, tabIcon(tab.classItem()));
        }
        refreshActions();
        showOverview();
        settings.remember(index.output());
        saveSettings();
        refreshRecent();
    }

    private void stopped(String outcome, Throwable failure) {
        running = null;
        busy(false, outcome);
        activity.finish(outcome, false);
        settingsAction.setEnabled(true);
        refreshActions();
        if (failure != null) reportFailure(Text.t("error.decompileFailed"), failure);
    }

    static String summary(ResultIndex index) {
        var summary = index.summary();
        var counts = index.levelCounts();
        String exact = counts.isEmpty() ? "" : " · " + Text.t("status.exact", counts.get(ResultIndex.Level.EXACT), index.classes().size());
        return Text.t("compilation." + summary.compilation()) + " · " + Text.t("status.signatures", summary.matchedSymbols(), summary.expectedSymbols()) + exact;
    }

    // ----- tabs ----------------------------------------------------------------------------------------------------

    void openClass(Workspace.ClassItem item) { openClass(item, -1); }

    void openClass(Workspace.ClassItem item, int line) {
        remember();
        show(item);
        var tab = (CodeTab) find("class:" + item.name());
        if (line > 0) { tab.select(ResultIndex.SELECTION); tab.goToLine(line); }
        tab.focusEditor();
        explorer.reveal(item.name());
    }

    /** Selects the class's tab, opening it when needed; no history entry. */
    private CodeTab show(Workspace.ClassItem item) {
        var tab = (CodeTab) find("class:" + item.name());
        if (tab == null) {
            tab = CodeTab.forClass(workspace, item, settings, caret::setText);
            tab.navigator(name -> workspace.classItem(name).ifPresent(this::openClass));
            tab.contextActions(List.of(findUsages, callHierarchy));
            tab.onDecompile(decompile);
            addTab(tab, tabIcon(item));
        }
        tabs.setSelectedComponent(tab);
        return tab;
    }

    /** Phase G4: the current class tab and caret go on the back stack; a new move clears the forward stack. */
    private void remember() {
        if (!(tabs.getSelectedComponent() instanceof CodeTab tab) || tab.classItem() == null) return;
        var place = new Place(tab.classItem().name(), tab.caret());
        if (place.equals(backStack.peek())) return;
        backStack.push(place);
        while (backStack.size() > MAX_HISTORY) backStack.removeLast();
        forwardStack.clear();
        refreshHistory();
    }

    private void travel(Deque<Place> from, Deque<Place> to) {
        if (workspace == null) return;
        while (!from.isEmpty()) {
            var place = from.pop();
            var item = workspace.classItem(place.className());
            if (item.isEmpty()) continue;
            if (tabs.getSelectedComponent() instanceof CodeTab current && current.classItem() != null)
                to.push(new Place(current.classItem().name(), current.caret()));
            var tab = show(item.get());
            tab.caret(place.caret());
            tab.focusEditor();
            explorer.reveal(place.className());
            break;
        }
        refreshHistory();
    }

    private void refreshHistory() {
        back.setEnabled(!backStack.isEmpty());
        forward.setEnabled(!forwardStack.isEmpty());
    }

    /** Phase G4: the class of the current tab (or the explorer's selection) in two candidates side by side. */
    private void compareCurrent() {
        if (result() == null) return;
        Workspace.ClassItem item = null;
        if (tabs.getSelectedComponent() instanceof CodeTab tab) item = tab.classItem();
        else if (tabs.getSelectedComponent() instanceof CompareTab) return;
        if (item == null && explorer.selected() instanceof ExplorerPanel.ClassNode node) item = node.item();
        if (item == null) { status(Text.t("compare.pick")); return; }
        if (result().candidates().size() < 2) { status(Text.t("compare.oneCandidate")); return; }
        for (int i = 0; i < tabs.getTabCount(); i++)
            if (tabs.getComponentAt(i) instanceof CompareTab open && open.key.equals("compare:" + item.name())) { tabs.setSelectedIndex(i); return; }
        var compareTab = new CompareTab(workspace, item, settings);
        tabs.addTab(compareTab.title(), Icons.of(Icons.Glyph.COMPARE, 14), compareTab, Ui.visible(item.name().replace('/', '.')));
        tabs.setSelectedComponent(compareTab);
    }

    private void openResource(Workspace.ResourceItem item) {
        var tab = (CodeTab) find("resource:" + item.entry());
        if (tab == null) { tab = CodeTab.forResource(workspace, item, settings, caret::setText); addTab(tab, Icons.of(Icons.Glyph.RESOURCE, 14)); }
        tabs.setSelectedComponent(tab);
    }

    private void addTab(CodeTab tab, Icon icon) {
        tabs.addTab(tab.title(), icon, tab, tab.tooltip());
        while (tabs.getTabCount() > MAX_TABS) {
            int oldest = tabs.getComponentAt(0) instanceof OverviewPanel ? 1 : 0;
            tabs.removeTabAt(oldest);
        }
    }

    private Component find(String key) {
        for (int i = 0; i < tabs.getTabCount(); i++) {
            var component = tabs.getComponentAt(i);
            if (component instanceof CodeTab tab && tab.key.equals(key)) return tab;
        }
        return null;
    }

    private void showOverview() {
        if (result() == null) return;
        for (int i = 0; i < tabs.getTabCount(); i++) if (tabs.getComponentAt(i) instanceof OverviewPanel) tabs.removeTabAt(i--);
        var panel = new OverviewPanel(workspace, List.of(exportProject, openReport, openFolder), level -> { explorer.showLevel(level); explorer.requestFocusInWindow(); });
        tabs.insertTab(Text.t("tab.overview"), Icons.of(Icons.Glyph.REPORT, 14), panel, null, 0);
        tabs.setSelectedIndex(0);
    }

    private void selected(ExplorerPanel.Node node) { if (workspace != null) inspector.show(workspace, node); }

    // ----- navigation and search --------------------------------------------------------------------------------

    private void goToClass() {
        if (workspace == null) return;
        new GoToClassDialog(this, workspace, this::openClass).setVisible(true);
    }

    private void searchCode() {
        if (result() == null) return;
        showBottom(search);
        String selected = null;
        if (tabs.getSelectedComponent() instanceof CodeTab tab) selected = selectedText(tab);
        search.focus(selected);
    }

    private static String selectedText(CodeTab tab) {
        var focus = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
        return focus instanceof javax.swing.text.JTextComponent text && SwingUtilities.isDescendingFrom(text, tab) ? text.getSelectedText() : null;
    }

    private void copyName() {
        if (tabs.getSelectedComponent() instanceof CodeTab tab && tab.classItem() != null) {
            var name = tab.classItem().name().replace('/', '.');
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(name), null);
            status(Text.t("status.copied", Ui.visible(name)));
        }
    }

    // ----- usages (Phase G6) ------------------------------------------------------------------------------------------

    /** Alt+F7 / Ctrl+Alt+H: the word under the editor's caret, or the class selected in the explorer when it has focus. */
    private void usagesOf(boolean hierarchy) {
        if (workspace == null) return;
        var focus = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
        boolean fromTree = focus != null && SwingUtilities.isDescendingFrom(focus, explorer);
        if (!fromTree && tabs.getSelectedComponent() instanceof CodeTab tab && tab.classItem() != null) { atCaret(tab, hierarchy); return; }
        if (explorer.selected() instanceof ExplorerPanel.ClassNode node) {
            if (hierarchy) memberOf(node.item(), true); else classUsages(node.item());
            return;
        }
        status(Text.t("usages.pick"));
    }

    /** Runs {@code then} with the index for the current input, building it first (in the background) when needed. */
    private void withIndex(Consumer<UsageIndex.Built> then) {
        var target = usageIndex;
        if (target == null) return;
        showBottom(usages);
        usages.indexing(Text.t("usages.indexing"), true);
        target.whenReady(built -> {
            if (target != usageIndex) return;
            usages.indexing(note(built), false);
            then.accept(built);
        }, failure -> {
            if (target != usageIndex) return;
            String text = failure instanceof InterruptedException ? Text.t("usages.cancelled")
                : Text.t("usages.failed", Ui.visible(String.valueOf(failure.getMessage())));
            usages.indexing(text, false);
            status(text);
        });
    }

    private static String note(UsageIndex.Built built) {
        var stats = built.index().stats();
        String text = Text.t(built.basedOn() == null ? "usages.indexedInput" : "usages.indexedResult", stats.classes(), stats.sites(), built.millis());
        if (stats.unreadable() > 0) text += " · " + Text.t("usages.unreadable", stats.unreadable());
        if (stats.truncated()) text += " · " + Text.t("usages.truncated");
        return text;
    }

    private UnaryOperator<String> treeNames(UsageIndex.Built built) {
        var index = usageIndex;
        return name -> index == null ? name : index.treeName(built, name);
    }

    /** The member (or class) under the caret: exact in bytecode, by context in source, a choice when ambiguous. */
    private void atCaret(CodeTab tab, boolean hierarchy) {
        withIndex(built -> {
            var index = built.index();
            String current = usageIndex.indexName(built, tab.classItem().name());
            List<ReferenceIndex.Ref> candidates;
            String word;
            if (CodeTab.BYTECODE.equals(tab.viewId())) {
                var ref = MemberAtCaret.inBytecode(tab.caretLine(), tab.classItem().name(), name -> usageIndex.indexName(built, name));
                candidates = ref == null ? List.of() : List.of(ref);
                word = ref == null ? "" : ref.isClass() ? ref.owner() : ref.name();
            } else {
                var query = MemberAtCaret.at(tab.content(), tab.caret());
                UnaryOperator<String> classes = simple -> {
                    String tree = tab.resolveClass(simple);
                    return tree == null ? null : usageIndex.indexName(built, tree);
                };
                candidates = MemberAtCaret.candidates(query, index, current, fileClasses(index, current), classes);
                word = query == null ? "" : query.word();
            }
            if (candidates.isEmpty()) {
                String text = word.isEmpty() ? Text.t("usages.nothing") : Text.t("usages.notDeclared", Ui.visible(word));
                usages.message(text);
                status(text);
                return;
            }
            choose(Text.t("usages.choose.message", Ui.visible(word)), candidates, built, ref -> run(ref, hierarchy, built));
        });
    }

    /** The index classes declared in the same source file as {@code current} (its top-level class and nested ones), current first. */
    private static List<String> fileClasses(ReferenceIndex index, String current) {
        int slash = current.lastIndexOf('/'), dollar = current.indexOf('$', slash + 1);
        String top = dollar < 0 ? current : current.substring(0, dollar);
        var found = new ArrayList<String>(List.of(current));
        for (String name : new TreeSet<>(index.classes().keySet()))
            if (!name.equals(current) && (name.equals(top) || name.startsWith(top + "$"))) found.add(name);
        return found;
    }

    private void choose(String message, List<ReferenceIndex.Ref> candidates, UsageIndex.Built built, Consumer<ReferenceIndex.Ref> then) {
        if (candidates.size() == 1) { then.accept(candidates.getFirst()); return; }
        chooser.choose(this, message, candidates, treeNames(built)).ifPresent(then);
    }

    private void classUsages(Workspace.ClassItem item) {
        withIndex(built -> run(ReferenceIndex.Ref.ofClass(usageIndex.indexName(built, item.name())), false, built));
    }

    /** Asks for a member of {@code item}: any member (and the class) for usages, a method for the call hierarchy. */
    private void memberOf(Workspace.ClassItem item, boolean hierarchy) {
        withIndex(built -> {
            String name = usageIndex.indexName(built, item.name());
            var decl = built.index().declaration(name).orElse(null);
            if (decl == null) { status(Text.t("usages.unreadableClass", Ui.visible(item.name().replace('/', '.')))); return; }
            var options = new ArrayList<ReferenceIndex.Ref>();
            if (!hierarchy) { options.add(ReferenceIndex.Ref.ofClass(name)); decl.fields().forEach(field -> options.add(field.ref())); }
            decl.methods().forEach(method -> options.add(method.ref()));
            if (options.isEmpty()) { status(Text.t("usages.noMethods")); return; }
            chooser.choose(this, Text.t(hierarchy ? "usages.choose.method" : "usages.choose.member", Ui.visible(item.simpleName())), options, treeNames(built))
                .ifPresent(ref -> run(ref, hierarchy, built));
        });
    }

    private void run(ReferenceIndex.Ref ref, boolean hierarchy, UsageIndex.Built built) {
        shown = built;
        var names = treeNames(built);
        showBottom(usages);
        if (hierarchy) {
            if (ref.isClass()) {
                workspace.classItem(names.apply(ref.owner())).ifPresentOrElse(item -> memberOf(item, true), () -> status(Text.t("usages.notAMethod")));
                return;
            }
            if (!ref.isMethod()) { status(Text.t("usages.notAMethod")); return; }
            hierarchy(ref, UsagesPanel.DEFAULT_DEPTH, built);
            return;
        }
        var found = built.index().usages(ref);
        usages.showUsages(ref, found, names, note(built));
        status(Text.t(found.isEmpty() ? "usages.titleNone" : "usages.title", found.size(), UsagesPanel.describe(ref, names)));
    }

    private void hierarchy(ReferenceIndex.Ref method, int depth, UsageIndex.Built built) {
        var root = CallHierarchy.callers(built.index(), method, depth, UsagesPanel.MAX_NODES);
        usages.showHierarchy(root, depth, treeNames(built), level -> hierarchy(method, level, built), note(built));
        status(Text.t("usages.hierarchyTitle", UsagesPanel.describe(method, treeNames(built))));
    }

    /** Opens a site's class: the selected source when there is one (else the bytecode), at the site's line. */
    void openSite(ReferenceIndex.Site site) {
        var built = shown;
        if (workspace == null || built == null) return;
        var names = treeNames(built);
        String tree = names.apply(site.className());
        var item = workspace.classItem(tree);
        if (item.isEmpty()) { status(Text.t("usages.notInTree", Ui.visible(tree.replace('/', '.')))); return; }
        remember();
        var tab = show(item.get());
        tab.select(workspace.source(tree, ResultIndex.SELECTION).isPresent() ? ResultIndex.SELECTION : CodeTab.BYTECODE);
        var index = built.index();
        tab.reveal((view, text) -> CodeTab.BYTECODE.equals(view) ? UsageLocator.bytecode(text, site, names) : UsageLocator.source(text, site, index));
        tab.focusEditor();
        explorer.reveal(tree);
    }

    // ----- export ------------------------------------------------------------------------------------------------

    private void exportProject() {
        var index = result();
        if (index == null) return;
        Path project = index.output().resolve("project");
        if (!Files.isDirectory(project)) { Ui.error(this, Text.t("error.title"), Text.t("export.noProject"), null); return; }
        var chooser = chooser();
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        chooser.setDialogTitle(Text.t("export.chooseFolder"));
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        Path parent = chooser.getSelectedFile().toPath();
        String base = workspace.displayName().replaceAll("\\.(jar|class)$", "") + "-project";
        Path target = parent.resolve(base);
        for (int n = 2; Files.exists(target); n++) target = parent.resolve(base + "-" + n);
        Path destination = target;
        background(Text.t("status.exporting"), () -> Exporter.copyTree(project, destination),
            count -> offerFolder(Text.t("export.done", count, destination), destination));
    }

    private void exportSources() {
        var index = result();
        if (index == null) return;
        Path sources = index.output().resolve(ResultIndex.SELECTION).resolve("sources");
        if (!Files.isDirectory(sources)) { Ui.error(this, Text.t("error.title"), Text.t("export.noSources"), null); return; }
        var chooser = chooser();
        chooser.setSelectedFile(new File(chooser.getCurrentDirectory(), workspace.displayName().replaceAll("\\.(jar|class)$", "") + "-sources.zip"));
        chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("ZIP", "zip"));
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        Path chosen = chooser.getSelectedFile().toPath();
        Path target = chosen.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".zip") ? chosen : chosen.resolveSibling(chosen.getFileName() + ".zip");
        if (Files.exists(target)) { Ui.error(this, Text.t("error.title"), Text.t("export.exists", target.getFileName()), null); return; }
        background(Text.t("status.exporting"), () -> Exporter.zip(sources, target),
            count -> offerFolder(Text.t("export.zipDone", count, target), target.getParent()));
    }

    private void offerFolder(String text, Path folder) {
        status(text);
        Object[] options = {Text.t("export.openFolder"), Text.t("dialog.close")};
        int answer = JOptionPane.showOptionDialog(this, text, Text.t("export.title"), JOptionPane.DEFAULT_OPTION, JOptionPane.INFORMATION_MESSAGE, null, options, options[0]);
        if (answer == 0) Ui.open(this, folder);
    }

    interface Work<T> { T run() throws Exception; }

    private <T> void background(String label, Work<T> work, java.util.function.Consumer<T> done) {
        busy(true, label);
        new SwingWorker<T,Void>() {
            @Override protected T doInBackground() throws Exception { return work.run(); }
            @Override protected void done() {
                busy(false, " ");
                try { done.accept(get()); }
                catch (java.util.concurrent.ExecutionException failure) { reportFailure(Text.t("error.exportFailed"), failure.getCause()); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            }
        }.execute();
    }

    // ----- settings, view, help ----------------------------------------------------------------------------------

    private void editSettings() {
        var themeBefore = settings.theme();
        var languageBefore = settings.language();
        int fontBefore = settings.fontSize();
        if (!SettingsDialog.edit(this, settings)) return;
        saveSettings();
        if (settings.theme() != themeBefore) applyTheme(settings.theme());
        if (settings.fontSize() != fontBefore) applyLook();
        if (settings.language() != languageBefore) Ui.info(this, Text.t("settings.title"), Text.t("settings.restart"));
        status(Text.t("status.settingsSaved"));
    }

    void applyTheme(GuiSettings.ThemeChoice choice) {
        settings.theme(choice);
        saveSettings();
        Theme.switchTo(choice);
        toggleTheme.putValue(Action.SMALL_ICON, Icons.of(Theme.dark() ? Icons.Glyph.SUN : Icons.Glyph.MOON));
        applyLook();
        themeItems.forEach((value, item) -> item.setSelected(value == choice));
    }
    private final Map<GuiSettings.ThemeChoice,JRadioButtonMenuItem> themeItems = new EnumMap<>(GuiSettings.ThemeChoice.class);

    private void applyLook() {
        for (int i = 0; i < tabs.getTabCount(); i++) {
            if (tabs.getComponentAt(i) instanceof CodeTab tab) tab.applyLook();
            if (tabs.getComponentAt(i) instanceof CompareTab compareTab) compareTab.applyLook();
        }
        explorer.refresh();
        activity.applyLook();
        welcome.refresh();
        if (workspace != null) inspector.show(workspace, explorer.selected());
        statusDot.set(running != null ? Theme.ACCENT : resultColor());
        repaint();
    }

    private void zoom(int delta) {
        settings.fontSize(settings.fontSize() + delta);
        saveSettings();
        applyLook();
        status(Text.t("status.fontSize", settings.fontSize()));
    }

    private void applyPanels() {
        inspector.setVisible(settings.showInspector());
        bottom.setVisible(settings.showActivity());
        if (settings.showInspector()) SwingUtilities.invokeLater(() -> inner.setDividerLocation(inner.getWidth() - 300));
        if (settings.showActivity()) SwingUtilities.invokeLater(() -> vertical.setDividerLocation(vertical.getHeight() - 236));
        inner.revalidate();
        vertical.revalidate();
    }

    private void showBottom(Component panel) {
        if (!settings.showActivity()) { settings.showActivity(true); bottomItem.setSelected(true); applyPanels(); }
        bottom.setSelectedComponent(panel);
    }

    /** Phase G8: the shortcuts in four groups, each written as key caps for this platform. */
    private void showShortcuts() {
        String click = Text.t("shortcuts.click");
        Object[][][] groups = {
            {{"menu.file"}, {"ctrl O", "action.open"}, {"ctrl shift O", "action.openResult"}, {"ctrl E", "action.exportProject"}, {"ctrl COMMA", "action.settings"}},
            {{"menu.decompile"}, {"F5", "action.decompile"}, {"shift F5", "action.decompileOptions"}, {"ctrl shift F5", "action.cancel"}},
            {{"menu.navigate"}, {"ctrl N", "action.goToClass"}, {"ctrl shift F", "action.search"}, {"alt F7", "action.findUsages"},
                {"ctrl alt H", "action.callHierarchy"}, {"ctrl+" + click, "shortcuts.openReferenced"}, {"alt LEFT", "action.back"},
                {"alt RIGHT", "action.forward"}, {"ctrl D", "action.compare"}, {"ctrl shift H", "action.overview"}},
            {{"shortcuts.editor"}, {"ctrl F", "action.find"}, {"F3", "find.next"}, {"shift F3", "find.previous"}, {"ctrl G", "code.goToLine.title"},
                {"ctrl W", "action.closeTab"}, {"ctrl EQUALS", "action.zoomIn"}, {"ctrl MINUS", "action.zoomOut"}}};
        var columns = new JPanel(new GridLayout(1, 2, 28, 0));
        var left = new JPanel(); left.setLayout(new BoxLayout(left, BoxLayout.Y_AXIS));
        var right = new JPanel(); right.setLayout(new BoxLayout(right, BoxLayout.Y_AXIS));
        for (int g = 0; g < groups.length; g++) {
            var column = g == 2 ? right : left;
            var group = groups[g];
            var title = Ui.caption(Text.t((String) group[0][0]));
            title.setAlignmentX(LEFT_ALIGNMENT);
            if (column.getComponentCount() > 0) column.add(Box.createVerticalStrut(16));
            column.add(title);
            column.add(Box.createVerticalStrut(6));
            var rows = new JPanel(new GridBagLayout());
            rows.setAlignmentX(LEFT_ALIGNMENT);
            var at = new GridBagConstraints();
            at.insets = new Insets(3, 0, 3, 0);
            for (int i = 1; i < group.length; i++) {
                String spec = (String) group[i][0];
                at.gridy = i; at.gridx = 0; at.weightx = 1; at.anchor = GridBagConstraints.WEST; at.fill = GridBagConstraints.HORIZONTAL;
                rows.add(new JLabel(Text.t((String) group[i][1]).replace("\u2026", "")), at);
                at.gridx = 1; at.weightx = 0; at.anchor = GridBagConstraints.EAST; at.fill = GridBagConstraints.NONE;
                rows.add(Ui.keycaps(spec.contains("+") ? Ui.shortcut(spec.substring(0, spec.indexOf('+'))) + "+" + spec.substring(spec.indexOf('+') + 1) : Ui.shortcut(spec)), at);
            }
            rows.setMaximumSize(new Dimension(Integer.MAX_VALUE, rows.getPreferredSize().height));
            column.add(rows);
        }
        left.add(Box.createVerticalGlue());
        right.add(Box.createVerticalGlue());
        columns.add(left);
        columns.add(right);
        columns.setBorder(Ui.padding(4, 4, 4, 4));
        JOptionPane.showMessageDialog(this, columns, Text.t("action.shortcuts"), JOptionPane.PLAIN_MESSAGE);
    }

    /** Phase G8: the logo, the version, what ClassRevive does and the safety promise, laid out like the rest of the app. */
    private void showAbout() {
        var panel = new JPanel(new BorderLayout(18, 0));
        panel.add(new JLabel(new ImageIcon(ClassReviveApp.logo(64))), BorderLayout.WEST);
        var text = new Ui.Column();
        text.add(Ui.styled("ClassRevive", "h2"));
        text.add(Ui.muted(Text.t("welcome.version", ClassReviveApp.VERSION)));
        text.add(Box.createVerticalStrut(10));
        var body = Ui.text(Text.t("about.text", ClassReviveApp.VERSION), false);
        body.setColumns(34);
        text.add(body);
        text.add(Box.createVerticalStrut(10));
        var safety = Ui.muted(Text.t("welcome.safety"));
        safety.setIcon(Icons.of(Icons.Glyph.SHIELD, 14, Theme.ACCENT));
        safety.setIconTextGap(8);
        text.add(safety);
        panel.add(text, BorderLayout.CENTER);
        panel.setPreferredSize(new Dimension(500, panel.getPreferredSize().height));
        JOptionPane.showMessageDialog(this, panel, Text.t("action.about"), JOptionPane.PLAIN_MESSAGE);
    }

    private void exit() {
        if (running != null) {
            int answer = JOptionPane.showConfirmDialog(this, Text.t("exit.running"), Text.t("app.name"), JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
            if (answer != JOptionPane.YES_OPTION) return;
            var job = running;
            job.interrupt();
            busy(true, Text.t("activity.cancelling"));
            Thread.ofVirtual().start(() -> {
                try { job.join(20_000); } catch (InterruptedException ignored) { }
                SwingUtilities.invokeLater(this::shutdown);
            });
            return;
        }
        shutdown();
    }

    private void shutdown() {
        saveSettings();
        dispose();
        System.exit(0);
    }

    // ----- chrome --------------------------------------------------------------------------------------------------

    /**
     * Phase G8: one command bar in four groups: open, history, decompile (the main action, with its options attached), and
     * find. Cancel only shows while a decompile runs; export, the theme switch and the settings sit on the right.
     */
    private JComponent toolbar() {
        var bar = new JToolBar();
        bar.setFloatable(false);
        bar.setBorder(BorderFactory.createCompoundBorder(Ui.bottomLine(), Ui.padding(5, 8, 5, 8)));
        var openButton = Ui.toolButton(open);
        bar.add(openButton);
        bar.add(iconOnly(openResult));
        bar.add(divider());
        bar.add(iconOnly(back));
        bar.add(iconOnly(forward));
        bar.add(divider());
        var options = new JPopupMenu();
        options.add(decompileOptions);
        options.add(decompile).setIcon(Icons.of(Icons.Glyph.PLAY, 14));
        var run = new Ui.SplitButton(decompile, options);
        bar.add(run);
        bar.add(Box.createHorizontalStrut(6));
        cancelButton = new JButton(cancel);
        cancelButton.putClientProperty(FlatClientProperties.BUTTON_TYPE, FlatClientProperties.BUTTON_TYPE_TOOLBAR_BUTTON);
        cancelButton.putClientProperty(FlatClientProperties.STYLE, "foreground: #F0605A; font: $semibold.font");
        cancelButton.setIcon(Icons.of(Icons.Glyph.STOP, 14, Theme.level(ResultIndex.Level.UNVERIFIED)));
        cancelButton.setText(Text.t("toolbar.cancel"));
        cancelButton.setFocusable(false);
        cancelButton.setVisible(false);
        bar.add(cancelButton);
        bar.add(divider());
        bar.add(iconOnly(goToClass));
        bar.add(iconOnly(searchCode));
        bar.add(iconOnly(compare));
        bar.add(Box.createHorizontalGlue());
        bar.add(Ui.toolButton(exportProject));
        bar.add(divider());
        bar.add(iconOnly(toggleTheme));
        bar.add(iconOnly(settingsAction));
        return bar;
    }

    /** A short vertical hairline between toolbar groups. */
    private static JComponent divider() {
        var line = new JComponent() {
            @Override protected void paintComponent(Graphics g) { g.setColor(Theme.line()); g.fillRect(getWidth() / 2, 6, 1, getHeight() - 12); }
        };
        var size = new Dimension(17, 28);
        line.setPreferredSize(size);
        line.setMaximumSize(size);
        return line;
    }

    private static JButton iconOnly(Action action) {
        var button = Ui.toolButton(action);
        button.setHideActionText(true);
        button.setMargin(new Insets(5, 6, 5, 6));
        return button;
    }

    private JMenuBar menus() {
        var bar = new JMenuBar();
        var file = new JMenu(Text.t("menu.file"));
        file.add(open); file.add(openResult); file.add(recentMenu); file.addSeparator();
        file.add(exportProject); file.add(exportSources); file.add(openReport); file.add(openFolder); file.addSeparator();
        file.add(closeWorkspace); file.add(exit);
        var edit = new JMenu(Text.t("menu.edit"));
        edit.add(find); edit.add(copyName);
        var navigate = new JMenu(Text.t("menu.navigate"));
        navigate.add(goToClass); navigate.add(searchCode); navigate.add(findUsages); navigate.add(callHierarchy); navigate.add(compare); navigate.add(overview); navigate.addSeparator();
        navigate.add(back); navigate.add(forward); navigate.addSeparator(); navigate.add(closeTab);
        var run = new JMenu(Text.t("menu.decompile"));
        run.add(decompile).setIcon(Icons.of(Icons.Glyph.PLAY)); run.add(decompileOptions); run.add(cancel);
        var view = new JMenu(Text.t("menu.view"));
        inspectorItem.setAction(Ui.action(Text.t("view.inspector"), null, Ui.key("alt 2"), () -> { settings.showInspector(inspectorItem.isSelected()); saveSettings(); applyPanels(); }));
        bottomItem.setAction(Ui.action(Text.t("view.bottom"), null, Ui.key("alt 3"), () -> { settings.showActivity(bottomItem.isSelected()); saveSettings(); applyPanels(); }));
        view.add(inspectorItem); view.add(bottomItem); view.addSeparator();
        var theme = new JMenu(Text.t("settings.theme"));
        var group = new ButtonGroup();
        for (var choice : GuiSettings.ThemeChoice.values()) {
            var item = new JRadioButtonMenuItem(Text.t("theme." + choice.name()), settings.theme() == choice);
            item.addActionListener(e -> applyTheme(choice));
            themeItems.put(choice, item);
            group.add(item);
            theme.add(item);
        }
        view.add(theme); view.add(zoomIn); view.add(zoomOut);
        var tools = new JMenu(Text.t("menu.tools"));
        tools.add(settingsAction);
        var help = new JMenu(Text.t("menu.help"));
        help.add(shortcuts);
        Path guide = ClassReviveApp.installedDoc("guia-iniciante.md");
        if (guide != null) help.add(Ui.action(Text.t("action.guide"), null, null, () -> Ui.open(this, guide)));
        help.addSeparator();
        help.add(about);
        for (var menu : List.of(file, edit, navigate, run, view, tools, help)) bar.add(menu);
        return bar;
    }

    /** Phase G8: a state dot and the message on the left; activity, memory and the caret position on the right. */
    private JComponent statusBar() {
        var bar = new JPanel(new BorderLayout(Ui.GAP, 0)) {
            @Override public void updateUI() { super.updateUI(); setBackground(Theme.palette().chrome()); }
        };
        bar.setBorder(BorderFactory.createCompoundBorder(Ui.topLine(), Ui.padding(4, 10, 4, 12)));
        var left = new JPanel(new BorderLayout(8, 0));
        left.setOpaque(false);
        left.add(statusDot, BorderLayout.WEST);
        left.add(message, BorderLayout.CENTER);
        bar.add(left, BorderLayout.CENTER);
        var right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 14, 0));
        right.setOpaque(false);
        busy.setIndeterminate(true);
        busy.setPreferredSize(new Dimension(110, 4));
        busy.setVisible(false);
        right.add(busy);
        right.add(memory);
        right.add(caret);
        bar.add(right, BorderLayout.EAST);
        return bar;
    }

    private void refreshMemory() {
        var runtime = Runtime.getRuntime();
        long used = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024);
        long max = runtime.maxMemory() / (1024 * 1024);
        memory.setText(Text.t("status.memory", used, max));
        memory.setToolTipText(Text.t("status.memory.tip", used, max));
    }

    /** The status dot: idle, busy (accent) or, after a decompile, the overall result. */
    static final class StatusDot extends JComponent {
        private Color color;
        StatusDot() { setPreferredSize(new Dimension(8, 8)); }
        void set(Color value) { color = value; repaint(); }
        @Override protected void paintComponent(Graphics g) {
            var g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(color == null ? Theme.muted() : color);
            g2.fillOval(0, getHeight() / 2 - 4, 8, 8);
            g2.dispose();
        }
    }

    /** Phase G8: the tab icon of a class: its kind, with its confidence dot once decompiled. */
    private Icon tabIcon(Workspace.ClassItem item) {
        char kind = ExplorerPanel.kind(item);
        var level = workspace == null ? null : workspace.evidence(item.name()).map(ResultIndex.ClassEntry::level).orElse(null);
        return Icons.kind(kind, ExplorerPanel.kindColor(kind), level == null ? null : Theme.level(level));
    }

    /** Phase G8: right click on a tab: close it, the others, or all. */
    private void tabMenu(MouseEvent event) {
        if (!event.isPopupTrigger()) return;
        int index = tabs.indexAtLocation(event.getX(), event.getY());
        if (index < 0) return;
        tabs.setSelectedIndex(index);
        var menu = new JPopupMenu();
        menu.add(closeTab);
        menu.add(closeOthers);
        menu.add(closeAll);
        if (tabs.getComponentAt(index) instanceof CodeTab tab && tab.classItem() != null) { menu.addSeparator(); menu.add(copyName); }
        menu.show(tabs, event.getX(), event.getY());
    }

    /** Closes every tab except the one at {@code keep} (all of them when -1). */
    private void closeTabs(int keep) {
        var kept = keep >= 0 && keep < tabs.getTabCount() ? tabs.getComponentAt(keep) : null;
        for (int i = tabs.getTabCount() - 1; i >= 0; i--) if (tabs.getComponentAt(i) != kept) tabs.removeTabAt(i);
    }

    /** Phase G8: what the editor area shows with no tab open: how to open a class, with the shortcuts as key caps. */
    private JComponent emptyEditor() {
        var panel = new JPanel(new GridBagLayout()) {
            @Override public void updateUI() { super.updateUI(); setBackground(Theme.editor()); }
        };
        var column = new JPanel();
        column.setOpaque(false);
        column.setLayout(new BoxLayout(column, BoxLayout.Y_AXIS));
        var icon = new JLabel(Icons.of(Icons.Glyph.CODE, 40, Theme.lineStrong()));
        icon.setAlignmentX(CENTER_ALIGNMENT);
        column.add(icon);
        column.add(Box.createVerticalStrut(14));
        var title = Ui.styled(Text.t("tabs.empty.title"), "h3");
        title.setAlignmentX(CENTER_ALIGNMENT);
        column.add(title);
        column.add(Box.createVerticalStrut(4));
        var hint = Ui.muted(Text.t("tabs.empty"));
        hint.setAlignmentX(CENTER_ALIGNMENT);
        column.add(hint);
        column.add(Box.createVerticalStrut(18));
        var keys = new JPanel(new GridBagLayout());
        keys.setOpaque(false);
        String[][] rows = {{"ctrl N", "action.goToClass"}, {"ctrl shift F", "action.search"}, {"F5", "action.decompile"}, {"F1", "action.shortcuts"}};
        var at = new GridBagConstraints();
        at.insets = new Insets(3, 6, 3, 6);
        for (int i = 0; i < rows.length; i++) {
            at.gridy = i; at.gridx = 0; at.anchor = GridBagConstraints.EAST;
            keys.add(Ui.muted(Text.t(rows[i][1]).replace("…", "")), at);
            at.gridx = 1; at.anchor = GridBagConstraints.WEST;
            keys.add(Ui.keycaps(Ui.shortcut(rows[i][0])), at);
        }
        keys.setAlignmentX(CENTER_ALIGNMENT);
        column.add(keys);
        panel.add(column);
        return panel;
    }

    private void refreshRecent() {
        recentMenu.removeAll();
        for (Path path : settings.recent()) {
            var item = new JMenuItem(Ui.visible(String.valueOf(path.getFileName())), Icons.of(Files.isDirectory(path) ? Icons.Glyph.FOLDER_RESULT : Icons.Glyph.RESOURCE, 14));
            item.setToolTipText(path.toString());
            item.addActionListener(e -> openPath(path));
            recentMenu.add(item);
        }
        recentMenu.setEnabled(recentMenu.getItemCount() > 0);
        welcome.refresh();
    }

    private void refreshActions() {
        boolean idle = running == null;
        // Navigation (go to class, usages, call hierarchy) also works while a decompile runs.
        for (var action : workspaceActions)
            action.setEnabled(workspace != null && (idle || action == goToClass || action == findUsages || action == callHierarchy));
        for (var action : resultActions) action.setEnabled(result() != null);
        cancel.setEnabled(!idle);
        if (cancelButton != null) cancelButton.setVisible(!idle);
        open.setEnabled(idle);
        openResult.setEnabled(idle);
        closeWorkspace.setEnabled(workspace != null && idle);
        refreshHistory();
    }

    private void busy(boolean on, String text) {
        busy.setVisible(on);
        statusDot.set(on ? Theme.ACCENT : resultColor());
        status(text);
        setCursor(on && opening ? Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR) : Cursor.getDefaultCursor());
    }

    void status(String text) { message.setText(text); }

    private ResultIndex result() { return workspace == null ? null : workspace.result(); }

    /** The status dot's color for the current result: green when it compiles, red when it does not, gray before. */
    private Color resultColor() {
        var index = result();
        if (index == null) return null;
        return index.summary().compilation().equals("COMPILED") ? Theme.level(ResultIndex.Level.EXACT) : Theme.level(ResultIndex.Level.UNVERIFIED);
    }

    private void saveSettings() {
        try { settings.save(); } catch (IOException failure) { status(Text.t("status.settingsNotSaved", failure.getMessage())); }
    }

    private void reportFailure(String headline, Throwable failure) {
        if (failure instanceof Workspace.Unreadable unreadable) { Ui.error(this, Text.t("error.title"), unreadable.getMessage(), unreadable.details()); return; }
        if (failure instanceof InputIdentifier.NoJavaException noJava) {
            Ui.error(this, Text.t("error.title"), Text.t("error.noJavaShort"), noJava.identification().text());
            return;
        }
        String reason = failure == null ? "" : failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
        if (failure instanceof FileAlreadyExistsException) reason = Text.t("error.exists", failure.getMessage());
        Ui.error(this, Text.t("error.title"), headline + "\n" + Ui.visible(reason), stack(failure));
    }

    private static String stack(Throwable failure) {
        if (failure == null) return null;
        var text = new java.io.StringWriter();
        failure.printStackTrace(new java.io.PrintWriter(text));
        return text.toString();
    }

    /** Accepts a file or folder dropped anywhere on the window. */
    private final class FileDrop extends TransferHandler {
        @Override public boolean canImport(TransferSupport support) { return support.isDataFlavorSupported(DataFlavor.javaFileListFlavor); }
        @Override public boolean importData(TransferSupport support) {
            try {
                @SuppressWarnings("unchecked")
                var files = (List<File>) support.getTransferable().getTransferData(DataFlavor.javaFileListFlavor);
                if (files.isEmpty()) return false;
                openPath(files.getFirst().toPath());
                return true;
            } catch (UnsupportedFlavorException | IOException unreadable) {
                return false;
            }
        }
    }

    /** The memory clock would keep a closed window reachable (tests open several); it stops with the window. */
    @Override public void dispose() {
        memoryClock.stop();
        super.dispose();
    }

    // For tests: the current state, read on the event thread.
    Workspace workspace() { return workspace; }
    JTabbedPane tabs() { return tabs; }
    ObfuscationBanner banner() { return banner; }
    UsagesPanel usages() { return usages; }
    SearchPanel searchPanel() { return search; }
    void showActivityPanel() { showBottom(activity); }
    ExplorerPanel explorer() { return explorer; }
    boolean running() { return running != null; }
}
