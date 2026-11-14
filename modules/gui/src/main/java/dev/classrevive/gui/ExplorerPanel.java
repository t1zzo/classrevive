package dev.classrevive.gui;

import com.formdev.flatlaf.FlatClientProperties;
import dev.classrevive.workbench.ResultIndex;
import java.awt.*;
import java.awt.event.*;
import java.util.*;
import java.util.List;
import java.util.function.Consumer;
import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.tree.*;

/**
 * Phase G3: the input as a tree (packages, classes with their nested classes, resources) with an instant filter. Phase G8:
 * a confidence filter (one level, or the classes that need attention), counts per package, resources shown by name with
 * their folder, and an empty state when nothing matches.
 */
final class ExplorerPanel extends JPanel {
    /** What a tree node stands for. */
    sealed interface Node permits Group, Package, ClassNode, Resource {}
    record Group(String label, Icons.Glyph glyph, int count) implements Node {}
    record Package(String name, int count) implements Node {}
    record ClassNode(Workspace.ClassItem item) implements Node {}
    record Resource(Workspace.ResourceItem item) implements Node {}

    /** Phase G8: the levels that need a closer look. */
    static final Set<ResultIndex.Level> ATTENTION = EnumSet.of(ResultIndex.Level.PER_FILE, ResultIndex.Level.PARTIAL, ResultIndex.Level.UNVERIFIED);

    private final JTree tree = new JTree(new DefaultTreeModel(new DefaultMutableTreeNode()));
    private final JTextField filter = new JTextField();
    private final JLabel title = Ui.styled("", "semibold");
    private final JLabel count = Ui.faint("");
    private final JButton levelButton = new JButton(Icons.of(Icons.Glyph.FILTER, 14));
    private final JPanel levelBar = new JPanel(new BorderLayout(6, 0));
    private final Ui.Chip levelChip = Ui.softChip("", Theme.ACCENT);
    private final CardLayout body = new CardLayout();
    private final JPanel bodyPanel = new JPanel(body);
    private final JLabel emptyText = Ui.muted("");
    private final javax.swing.Timer debounce;
    private Workspace workspace;
    /** Null shows every class; otherwise only classes at these levels (Phase G8). */
    private Set<ResultIndex.Level> levels;

    ExplorerPanel(Consumer<Workspace.ClassItem> open, Consumer<Workspace.ResourceItem> openResource, Consumer<Node> select) {
        super(new BorderLayout());
        setBorder(Ui.line(0, 0, 0, 1));
        var header = new JPanel(new BorderLayout(0, 8));
        header.setOpaque(false);
        header.setBorder(Ui.padding(12, 12, 8, 10));
        var titleRow = new JPanel(new BorderLayout(6, 0));
        titleRow.setOpaque(false);
        title.setIcon(Icons.of(Icons.Glyph.PACKAGE, 15, Theme.ACCENT));
        title.setIconTextGap(8);
        titleRow.add(title, BorderLayout.CENTER);
        titleRow.add(count, BorderLayout.EAST);
        header.add(titleRow, BorderLayout.NORTH);
        filter.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, Text.t("explorer.filter"));
        filter.putClientProperty(FlatClientProperties.TEXT_FIELD_LEADING_ICON, Icons.of(Icons.Glyph.SEARCH, 14));
        filter.putClientProperty(FlatClientProperties.TEXT_FIELD_SHOW_CLEAR_BUTTON, true);
        levelButton.putClientProperty(FlatClientProperties.BUTTON_TYPE, FlatClientProperties.BUTTON_TYPE_TOOLBAR_BUTTON);
        levelButton.setToolTipText(Text.t("explorer.levelFilter"));
        levelButton.setFocusable(false);
        levelButton.addActionListener(e -> levelMenu().show(levelButton, 0, levelButton.getHeight()));
        var filterRow = new JPanel(new BorderLayout(4, 0));
        filterRow.setOpaque(false);
        filterRow.add(filter, BorderLayout.CENTER);
        filterRow.add(levelButton, BorderLayout.EAST);
        header.add(filterRow, BorderLayout.CENTER);
        levelBar.setOpaque(false);
        levelBar.add(levelChip, BorderLayout.WEST);
        levelBar.add(Ui.link(Text.t("explorer.showAll"), () -> showLevels(null)), BorderLayout.EAST);
        levelBar.setVisible(false);
        header.add(levelBar, BorderLayout.SOUTH);
        add(header, BorderLayout.NORTH);

        tree.setRootVisible(false);
        tree.setShowsRootHandles(true);
        tree.setLargeModel(true);
        tree.setToggleClickCount(0);
        tree.setCellRenderer(new Renderer());
        ToolTipManager.sharedInstance().registerComponent(tree);
        tree.getSelectionModel().setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION);
        tree.addTreeSelectionListener(event -> { var node = selected(); if (node != null) select.accept(node); });
        tree.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent event) {
                if (event.getClickCount() != 2 || !SwingUtilities.isLeftMouseButton(event)) return;
                var path = tree.getPathForLocation(event.getX(), event.getY());
                if (path == null) return;
                if (node(path) instanceof ClassNode cls) open.accept(cls.item());
                else if (node(path) instanceof Resource res) openResource.accept(res.item());
                else if (tree.isExpanded(path)) tree.collapsePath(path); else tree.expandPath(path);
            }
        });
        tree.getInputMap().put(KeyStroke.getKeyStroke("ENTER"), "open");
        tree.getActionMap().put("open", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent event) {
                if (selected() instanceof ClassNode cls) open.accept(cls.item());
                else if (selected() instanceof Resource res) openResource.accept(res.item());
            }
        });
        // Long names are cut with their full text in the tooltip instead of a sideways scroll bar (Phase G8).
        var scroll = new JScrollPane(tree, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        bodyPanel.setOpaque(false);
        bodyPanel.add(scroll, "tree");
        var empty = new JPanel(new GridBagLayout());
        empty.setOpaque(false);
        var emptyColumn = new JPanel();
        emptyColumn.setOpaque(false);
        emptyColumn.setLayout(new BoxLayout(emptyColumn, BoxLayout.Y_AXIS));
        var emptyIcon = new JLabel(Icons.of(Icons.Glyph.SEARCH, 28, Theme.lineStrong()));
        emptyIcon.setAlignmentX(CENTER_ALIGNMENT);
        emptyText.setAlignmentX(CENTER_ALIGNMENT);
        var clear = Ui.link(Text.t("explorer.clearFilters"), this::clearFilters);
        clear.setAlignmentX(CENTER_ALIGNMENT);
        emptyColumn.add(emptyIcon);
        emptyColumn.add(Box.createVerticalStrut(10));
        emptyColumn.add(emptyText);
        emptyColumn.add(Box.createVerticalStrut(4));
        emptyColumn.add(clear);
        empty.add(emptyColumn);
        bodyPanel.add(empty, "empty");
        add(bodyPanel, BorderLayout.CENTER);

        debounce = new javax.swing.Timer(150, event -> rebuild());
        debounce.setRepeats(false);
        filter.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { debounce.restart(); }
            @Override public void removeUpdate(DocumentEvent e) { debounce.restart(); }
            @Override public void changedUpdate(DocumentEvent e) { debounce.restart(); }
        });
        filter.addKeyListener(new KeyAdapter() {
            @Override public void keyPressed(KeyEvent event) {
                if (event.getKeyCode() == KeyEvent.VK_DOWN) { tree.requestFocusInWindow(); if (tree.getSelectionCount() == 0) tree.setSelectionRow(0); }
                if (event.getKeyCode() == KeyEvent.VK_ESCAPE) filter.setText("");
            }
        });
    }

    @Override public void updateUI() { super.updateUI(); setBackground(Theme.panel()); }

    /** Phase G6: a context menu with {@code actions} on class nodes; a right click selects the node first. */
    void popup(List<Action> actions) {
        var menu = new JPopupMenu();
        actions.forEach(menu::add);
        tree.addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent event) { maybeShow(event); }
            @Override public void mouseReleased(MouseEvent event) { maybeShow(event); }
            private void maybeShow(MouseEvent event) {
                if (!event.isPopupTrigger()) return;
                var path = tree.getPathForLocation(event.getX(), event.getY());
                if (path == null || !(node(path) instanceof ClassNode)) return;
                tree.setSelectionPath(path);
                menu.show(tree, event.getX(), event.getY());
            }
        });
    }

    void show(Workspace workspace) {
        this.workspace = workspace;
        title.setText(Ui.visible(workspace.displayName()));
        title.setToolTipText(Ui.visible(workspace.input().toString()));
        count.setText(Text.t("explorer.count", workspace.classes().size()));
        levels = null;
        levelBar.setVisible(false);
        filter.setText("");
        rebuild();
    }

    /** Repaints after the evidence changed (a decompile finished). */
    void refresh() {
        levelButton.setEnabled(workspace != null && workspace.decompiled());
        if (levels != null) rebuild(); else tree.repaint();
    }

    void focusFilter() { filter.requestFocusInWindow(); filter.selectAll(); }

    /** Phase G8: shows only the classes at {@code level}; null shows the classes that need attention. */
    void showLevel(ResultIndex.Level level) { showLevels(level == null ? ATTENTION : EnumSet.of(level)); }

    private void showLevels(Set<ResultIndex.Level> chosen) {
        levels = chosen == null ? null : EnumSet.copyOf(chosen);
        if (levels != null) {
            boolean attention = levels.equals(ATTENTION);
            var only = levels.iterator().next();
            levelChip.set(attention ? Text.t("explorer.attention") : Text.t("level." + only.name()),
                attention ? Theme.level(ResultIndex.Level.UNVERIFIED) : Theme.level(only));
        }
        levelBar.setVisible(levels != null);
        revalidate();
        rebuild();
    }

    private void clearFilters() {
        levels = null;
        levelBar.setVisible(false);
        filter.setText("");
        rebuild();
    }

    private JPopupMenu levelMenu() {
        var menu = new JPopupMenu();
        var all = new JRadioButtonMenuItem(Text.t("explorer.allClasses"), levels == null);
        all.addActionListener(e -> showLevels(null));
        menu.add(all);
        var attention = new JRadioButtonMenuItem(Text.t("explorer.attention"), ATTENTION.equals(levels));
        attention.setIcon(Ui.severityIcon(ResultInsights.Severity.WARNING, 12));
        attention.addActionListener(e -> showLevels(ATTENTION));
        menu.add(attention);
        menu.addSeparator();
        var counts = workspace == null || workspace.result() == null ? Map.<ResultIndex.Level,Integer>of() : workspace.result().levelCounts();
        for (var level : ResultIndex.Level.values()) {
            var item = new JRadioButtonMenuItem(Text.t("explorer.levelItem", Text.t("level." + level.name()), counts.getOrDefault(level, 0)),
                levels != null && levels.size() == 1 && levels.contains(level));
            item.setIcon(Icons.dot(Theme.level(level), 12));
            item.addActionListener(e -> showLevels(EnumSet.of(level)));
            menu.add(item);
        }
        return menu;
    }

    /** Selects and reveals a class (used by "go to class" and search results). */
    void reveal(String className) {
        var root = (DefaultMutableTreeNode) tree.getModel().getRoot();
        for (var nodes = root.depthFirstEnumeration(); nodes.hasMoreElements(); ) {
            var node = (DefaultMutableTreeNode) nodes.nextElement();
            if (node.getUserObject() instanceof ClassNode cls && cls.item().name().equals(className)) {
                var path = new TreePath(node.getPath());
                tree.setSelectionPath(path);
                tree.scrollPathToVisible(path);
                return;
            }
        }
    }

    Node selected() {
        var path = tree.getSelectionPath();
        return path == null ? null : node(path);
    }

    private static Node node(TreePath path) {
        return ((DefaultMutableTreeNode) path.getLastPathComponent()).getUserObject() instanceof Node node ? node : null;
    }

    private void rebuild() {
        if (workspace == null) return;
        var root = build(workspace, filter.getText().strip(), levels);
        tree.setModel(new DefaultTreeModel(root));
        boolean filtering = !filter.getText().isBlank() || levels != null;
        // Expand the groups, and every package while filtering or when the input is small.
        for (int i = 0; i < tree.getRowCount(); i++) {
            var node = node(tree.getPathForRow(i));
            if (node instanceof Group || (node instanceof Package && (filtering || workspace.classes().size() <= 60))) tree.expandRow(i);
            if (filtering && i > 4000) break;
        }
        boolean nothing = root.getChildCount() == 0 || (root.getChildCount() == 1 && ((DefaultMutableTreeNode) root.getChildAt(0)).getChildCount() == 0);
        emptyText.setText(levels != null && filter.getText().isBlank() ? Text.t("explorer.noLevel") : Text.t("explorer.noMatch", Ui.visible(filter.getText().strip())));
        body.show(bodyPanel, nothing && filtering ? "empty" : "tree");
        levelButton.setEnabled(workspace.decompiled());
    }

    /** The tree for a workspace and a filter (case-insensitive, on simple or full class names and resource paths). */
    static DefaultMutableTreeNode build(Workspace workspace, String filter) { return build(workspace, filter, null); }

    /** Phase G8: also keeps only classes at {@code levels} (null keeps all; resources are hidden while a level is chosen). */
    static DefaultMutableTreeNode build(Workspace workspace, String filter, Set<ResultIndex.Level> levels) {
        String needle = filter.toLowerCase(Locale.ROOT);
        var names = workspace.classNames();
        var matched = new LinkedHashSet<String>();
        for (var item : workspace.classes()) {
            if (levels != null && !levels.contains(workspace.evidence(item.name()).map(ResultIndex.ClassEntry::level).orElse(null))) continue;
            if (needle.isEmpty() || item.simpleName().toLowerCase(Locale.ROOT).contains(needle) || item.name().replace('/', '.').toLowerCase(Locale.ROOT).contains(needle))
                matched.add(item.name());
        }
        var nodes = new HashMap<String,DefaultMutableTreeNode>();
        var packages = new TreeMap<String,DefaultMutableTreeNode>();
        for (var item : workspace.classes()) {
            if (!matched.contains(item.name())) continue;
            nodes.put(item.name(), new DefaultMutableTreeNode(new ClassNode(item)));
        }
        for (var item : workspace.classes()) {
            var node = nodes.get(item.name());
            if (node == null) continue;
            String outer = item.outer(names);
            if (outer != null && nodes.containsKey(outer)) nodes.get(outer).add(node);
            else packages.computeIfAbsent(item.packageName(), k -> new DefaultMutableTreeNode()).add(node);
        }
        var root = new DefaultMutableTreeNode();
        var classes = new DefaultMutableTreeNode(new Group(Text.t("explorer.classes"), Icons.Glyph.PACKAGE, matched.size()));
        packages.forEach((name, node) -> {
            // Every class of the package, nested ones included: the subtree without the package node itself.
            int classesInPackage = 0;
            for (var nodesIn = node.depthFirstEnumeration(); nodesIn.hasMoreElements(); nodesIn.nextElement()) classesInPackage++;
            node.setUserObject(new Package(name, classesInPackage - 1));
            classes.add(node);
        });
        root.add(classes);
        var resources = levels != null ? List.<Workspace.ResourceItem>of() : workspace.resources().stream()
            .filter(item -> needle.isEmpty() || item.entry().toLowerCase(Locale.ROOT).contains(needle)).toList();
        if (!resources.isEmpty()) {
            var group = new DefaultMutableTreeNode(new Group(Text.t("explorer.resources"), Icons.Glyph.FOLDER, resources.size()));
            for (var item : resources) group.add(new DefaultMutableTreeNode(new Resource(item)));
            root.add(group);
        }
        return root;
    }

    /** The kind badge of a class: C, I (interface), E (enum), R (record) or @ (annotation). */
    static char kind(Workspace.ClassItem item) {
        int access = item.info().access();
        if ((access & 0x2000) != 0) return '@';
        if ((access & 0x0200) != 0) return 'I';
        if ((access & 0x4000) != 0) return 'E';
        if ("java/lang/Record".equals(item.info().superName())) return 'R';
        return 'C';
    }

    static Color kindColor(char kind) {
        return switch (kind) {
            case 'I' -> new Color(0x7C6CB0); case 'E' -> new Color(0xB07C3C); case 'R' -> new Color(0x3C8CA0); case '@' -> new Color(0x9C5C8C);
            default -> new Color(0x5F7396);
        };
    }

    private final class Renderer extends DefaultTreeCellRenderer {
        @Override public Component getTreeCellRendererComponent(JTree tree, Object value, boolean selected, boolean expanded, boolean leaf, int row, boolean focus) {
            super.getTreeCellRendererComponent(tree, value, selected, expanded, leaf, row, focus);
            Object object = ((DefaultMutableTreeNode) value).getUserObject();
            setToolTipText(null);
            setFont(tree.getFont());
            setIconTextGap(6);
            String faint = hex(selected ? getForeground() : Theme.muted());
            switch (object) {
                case Group group -> {
                    setText("<html><b>" + Ui.escape(group.label()) + "</b>&nbsp; <font color='" + faint + "'>" + group.count() + "</font></html>");
                    setIcon(Icons.of(group.glyph(), 15));
                }
                case Package pkg -> {
                    String name = pkg.name().isEmpty() ? Text.t("explorer.defaultPackage") : Ui.visible(pkg.name());
                    setText("<html>" + Ui.escape(name) + "&nbsp; <font color='" + faint + "'>" + pkg.count() + "</font></html>");
                    setIcon(Icons.of(Icons.Glyph.PACKAGE, 14, selected ? null : Theme.secondary()));
                    setToolTipText(name);
                }
                case ClassNode cls -> {
                    var evidence = workspace == null ? Optional.<ResultIndex.ClassEntry>empty() : workspace.evidence(cls.item().name());
                    char kind = kind(cls.item());
                    var level = evidence.map(ResultIndex.ClassEntry::level).orElse(null);
                    setIcon(Icons.kind(kind, kindColor(kind), level == null ? null : Theme.level(level)));
                    setText(Ui.visible(cls.item().simpleName()));
                    String tip = Ui.visible(cls.item().name().replace('/', '.'));
                    setToolTipText(level == null ? tip : tip + "  ·  " + Text.t("level." + level.name()));
                }
                case Resource res -> {
                    String entry = Ui.visible(res.item().entry());
                    int slash = entry.lastIndexOf('/');
                    String folder = slash < 0 ? "" : entry.substring(0, slash);
                    setText(folder.isEmpty() ? entry : "<html>" + Ui.escape(entry.substring(slash + 1)) + "&nbsp; <font color='" + faint + "'>" + Ui.escape(folder) + "</font></html>");
                    setIcon(Icons.of(Icons.Glyph.RESOURCE, 14, selected ? null : Theme.secondary()));
                    setToolTipText(entry + "  ·  " + InspectorPanel.bytes(res.item().size()));
                }
                default -> { }
            }
            return this;
        }

        private static String hex(Color color) { return String.format("#%06X", color.getRGB() & 0xFFFFFF); }
    }
}
