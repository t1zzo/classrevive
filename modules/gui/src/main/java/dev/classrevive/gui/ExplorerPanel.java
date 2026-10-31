package dev.classrevive.gui;

import com.formdev.flatlaf.FlatClientProperties;
import java.awt.*;
import java.awt.event.*;
import java.util.*;
import java.util.List;
import java.util.function.Consumer;
import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.tree.*;

/** Phase G3: the input as a tree (packages, classes with their nested classes, resources) with an instant filter. */
final class ExplorerPanel extends JPanel {
    /** What a tree node stands for. */
    sealed interface Node permits Group, Package, ClassNode, Resource {}
    record Group(String label, Icons.Glyph glyph, int count) implements Node {}
    record Package(String name, int count) implements Node {}
    record ClassNode(Workspace.ClassItem item) implements Node {}
    record Resource(Workspace.ResourceItem item) implements Node {}

    private final JTree tree = new JTree(new DefaultTreeModel(new DefaultMutableTreeNode()));
    private final JTextField filter = new JTextField();
    private final JLabel title = Ui.heading("", 13f);
    private final javax.swing.Timer debounce;
    private Workspace workspace;

    ExplorerPanel(Consumer<Workspace.ClassItem> open, Consumer<Workspace.ResourceItem> openResource, Consumer<Node> select) {
        super(new BorderLayout());
        setBorder(Ui.line(0, 0, 0, 1));
        var header = new JPanel(new BorderLayout(0, 6));
        header.setBorder(Ui.padding(10, 10, 8, 10));
        title.setIcon(Icons.of(Icons.Glyph.PACKAGE, 14));
        header.add(title, BorderLayout.NORTH);
        filter.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, Text.t("explorer.filter"));
        filter.putClientProperty(FlatClientProperties.TEXT_FIELD_LEADING_ICON, Icons.of(Icons.Glyph.SEARCH, 14));
        filter.putClientProperty(FlatClientProperties.TEXT_FIELD_SHOW_CLEAR_BUTTON, true);
        header.add(filter, BorderLayout.CENTER);
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
        var scroll = new JScrollPane(tree);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        add(scroll, BorderLayout.CENTER);

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
        filter.setText("");
        rebuild();
    }

    /** Repaints after the evidence changed (a decompile finished). */
    void refresh() { tree.repaint(); }

    void focusFilter() { filter.requestFocusInWindow(); filter.selectAll(); }

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
        var root = build(workspace, filter.getText().strip());
        tree.setModel(new DefaultTreeModel(root));
        boolean filtering = !filter.getText().isBlank();
        // Expand the groups, and every package while filtering or when the input is small.
        for (int i = 0; i < tree.getRowCount(); i++) {
            var node = node(tree.getPathForRow(i));
            if (node instanceof Group || (node instanceof Package && (filtering || workspace.classes().size() <= 60))) tree.expandRow(i);
            if (filtering && i > 4000) break;
        }
    }

    /** The tree for a workspace and a filter (case-insensitive, on simple or full class names and resource paths). */
    static DefaultMutableTreeNode build(Workspace workspace, String filter) {
        String needle = filter.toLowerCase(Locale.ROOT);
        var names = workspace.classNames();
        var matched = new LinkedHashSet<String>();
        for (var item : workspace.classes())
            if (needle.isEmpty() || item.simpleName().toLowerCase(Locale.ROOT).contains(needle) || item.name().replace('/', '.').toLowerCase(Locale.ROOT).contains(needle))
                matched.add(item.name());
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
            node.setUserObject(new Package(name, node.getChildCount()));
            classes.add(node);
        });
        root.add(classes);
        var resources = workspace.resources().stream()
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
            switch (object) {
                case Group group -> { setText(group.label() + "  " + group.count()); setIcon(Icons.of(group.glyph())); setFont(tree.getFont().deriveFont(Font.BOLD)); }
                case Package pkg -> {
                    setText(pkg.name().isEmpty() ? Text.t("explorer.defaultPackage") : Ui.visible(pkg.name()));
                    setIcon(Icons.of(Icons.Glyph.PACKAGE, 14)); setFont(tree.getFont());
                }
                case ClassNode cls -> {
                    var evidence = workspace == null ? Optional.<dev.classrevive.workbench.ResultIndex.ClassEntry>empty() : workspace.evidence(cls.item().name());
                    char kind = kind(cls.item());
                    setIcon(Icons.kind(kind, kindColor(kind), evidence.map(entry -> Theme.level(entry.level())).orElse(null)));
                    setText(Ui.visible(cls.item().simpleName()));
                    setFont(tree.getFont());
                    setToolTipText(Ui.visible(cls.item().name().replace('/', '.')));
                }
                case Resource res -> { setText(Ui.visible(res.item().entry())); setIcon(Icons.of(Icons.Glyph.RESOURCE, 14)); setFont(tree.getFont()); }
                default -> { }
            }
            return this;
        }
    }
}
