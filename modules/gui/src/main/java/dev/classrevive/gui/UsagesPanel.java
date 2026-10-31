package dev.classrevive.gui;

import com.formdev.flatlaf.FlatClientProperties;
import dev.classrevive.bytecode.CallHierarchy;
import dev.classrevive.bytecode.ReferenceIndex;
import dev.classrevive.bytecode.ReferenceIndex.Ref;
import java.awt.*;
import java.awt.event.*;
import java.util.*;
import java.util.List;
import java.util.function.*;
import java.util.regex.Pattern;
import javax.swing.*;
import javax.swing.tree.*;

/**
 * Phase G6: the results of "find usages" (grouped by class, then by the member holding the site) and of the call
 * hierarchy (callers of callers, with cycles marked). Opening a site goes to its class at the site's line.
 */
final class UsagesPanel extends JPanel {
    static final int DEFAULT_DEPTH = 5, MAX_DEPTH = 12, MAX_NODES = 3000;

    /** Tree nodes: a class, a member holding sites, one site, or one method of the call hierarchy. */
    record ClassGroup(String treeName, int count) {}
    record MemberGroup(Ref from, int count) {}
    record SiteNode(ReferenceIndex.Usage usage) {}
    record CallerNode(CallHierarchy.Node node) {}

    private final JLabel title = new JLabel(Text.t("usages.empty"));
    private final JLabel status = Ui.muted("");
    private final JButton cancel = new JButton(Text.t("usages.cancel"));
    private final JLabel depthLabel = Ui.muted(Text.t("usages.depth"));
    private final JSpinner depth = new JSpinner(new SpinnerNumberModel(DEFAULT_DEPTH, 1, MAX_DEPTH, 1));
    private final JTree tree = new JTree(new DefaultTreeModel(new DefaultMutableTreeNode()));
    private final Consumer<ReferenceIndex.Site> open;
    private UnaryOperator<String> treeNames = UnaryOperator.identity();
    private IntConsumer redo = level -> { };
    private boolean adjusting;

    UsagesPanel(Consumer<ReferenceIndex.Site> open, Runnable cancelIndex) {
        super(new BorderLayout());
        this.open = open;
        var top = new JPanel(new FlowLayout(FlowLayout.LEFT, Ui.GAP, 6));
        top.setBorder(Ui.padding(2, 6, 0, 6));
        title.setFont(title.getFont().deriveFont(Font.BOLD));
        cancel.putClientProperty(FlatClientProperties.BUTTON_TYPE, FlatClientProperties.BUTTON_TYPE_TOOLBAR_BUTTON);
        cancel.addActionListener(e -> cancelIndex.run());
        cancel.setVisible(false);
        depth.addChangeListener(e -> { if (!adjusting) redo.accept((Integer) depth.getValue()); });
        top.add(title); top.add(depthLabel); top.add(depth); top.add(status); top.add(cancel);
        add(top, BorderLayout.NORTH);
        tree.setRootVisible(false);
        tree.setShowsRootHandles(true);
        tree.setCellRenderer(new Renderer());
        tree.getSelectionModel().setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION);
        tree.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent event) {
                if (event.getClickCount() != 2 || !SwingUtilities.isLeftMouseButton(event)) return;
                var path = tree.getPathForLocation(event.getX(), event.getY());
                if (path != null && openable(path)) openPath(path);
            }
        });
        tree.getInputMap().put(KeyStroke.getKeyStroke("ENTER"), "open");
        tree.getActionMap().put("open", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { if (tree.getSelectionPath() != null) openPath(tree.getSelectionPath()); }
        });
        var scroll = new JScrollPane(tree);
        scroll.setBorder(Ui.topLine());
        add(scroll, BorderLayout.CENTER);
        showDepth(false);
    }

    /** The index is being built (or failed: {@code text} says so, {@code busy} false). */
    void indexing(String text, boolean busy) {
        status.setText(text);
        cancel.setVisible(busy);
    }

    void message(String text) {
        title.setText(text);
        showDepth(false);
        tree.setModel(new DefaultTreeModel(new DefaultMutableTreeNode()));
    }

    /** Usages of {@code target}, grouped by class and member. */
    void showUsages(Ref target, List<ReferenceIndex.Usage> usages, UnaryOperator<String> treeNames, String note) {
        this.treeNames = treeNames;
        showDepth(false);
        title.setText(Text.t(usages.isEmpty() ? "usages.titleNone" : "usages.title", usages.size(), describe(target, treeNames)));
        status.setText(note);
        cancel.setVisible(false);
        var root = new DefaultMutableTreeNode();
        var byClass = new LinkedHashMap<String,LinkedHashMap<Ref,List<ReferenceIndex.Usage>>>();
        for (var usage : usages)
            byClass.computeIfAbsent(usage.site().className(), k -> new LinkedHashMap<>()).computeIfAbsent(usage.site().from(), k -> new ArrayList<>()).add(usage);
        byClass.forEach((className, members) -> {
            var classNode = new DefaultMutableTreeNode(new ClassGroup(treeNames.apply(className), members.values().stream().mapToInt(List::size).sum()));
            members.forEach((from, sites) -> {
                var memberNode = new DefaultMutableTreeNode(new MemberGroup(from, sites.size()));
                for (var usage : sites) memberNode.add(new DefaultMutableTreeNode(new SiteNode(usage)));
                classNode.add(memberNode);
            });
            root.add(classNode);
        });
        tree.setModel(new DefaultTreeModel(root));
        // Expand everything for small results, the classes only for large ones.
        int limit = usages.size() <= 400 ? Integer.MAX_VALUE : root.getChildCount();
        for (int i = 0; i < tree.getRowCount() && i < limit + 2000; i++) {
            var node = (DefaultMutableTreeNode) tree.getPathForRow(i).getLastPathComponent();
            if (usages.size() <= 400 || node.getUserObject() instanceof ClassGroup) tree.expandRow(i);
        }
        if (tree.getRowCount() > 0) tree.setSelectionRow(0);
    }

    /** The callers of a method; {@code redo} recomputes for another depth. */
    void showHierarchy(CallHierarchy.Node root, int level, UnaryOperator<String> treeNames, IntConsumer redo, String note) {
        this.treeNames = treeNames;
        this.redo = redo;
        adjusting = true;
        depth.setValue(level);
        adjusting = false;
        showDepth(true);
        title.setText(Text.t("usages.hierarchyTitle", describe(root.method(), treeNames)));
        status.setText(note);
        cancel.setVisible(false);
        var top = new DefaultMutableTreeNode(new CallerNode(root));
        add(top, root);
        var model = new DefaultMutableTreeNode();
        model.add(top);
        tree.setModel(new DefaultTreeModel(model));
        for (int i = 0; i < tree.getRowCount() && i < 500; i++) tree.expandRow(i);
        tree.setSelectionRow(0);
    }

    private static void add(DefaultMutableTreeNode parent, CallHierarchy.Node node) {
        for (var caller : node.callers()) {
            var child = new DefaultMutableTreeNode(new CallerNode(caller));
            add(child, caller);
            parent.add(child);
        }
    }

    private void showDepth(boolean visible) { depthLabel.setVisible(visible); depth.setVisible(visible); }

    private static boolean openable(TreePath path) {
        Object value = ((DefaultMutableTreeNode) path.getLastPathComponent()).getUserObject();
        return value instanceof SiteNode || value instanceof CallerNode caller && !caller.node().sites().isEmpty();
    }

    private void openPath(TreePath path) {
        var node = (DefaultMutableTreeNode) path.getLastPathComponent();
        switch (node.getUserObject()) {
            case SiteNode site -> open.accept(site.usage().site());
            case CallerNode caller when !caller.node().sites().isEmpty() -> open.accept(caller.node().sites().getFirst().site());
            case MemberGroup group when node.getChildCount() > 0 -> openPath(path.pathByAddingChild(node.getChildAt(0)));
            case null, default -> { if (tree.isExpanded(path)) tree.collapsePath(path); else tree.expandPath(path); }
        }
    }

    // ----- labels ----------------------------------------------------------------------------------------------------

    private static final Pattern LAMBDA = Pattern.compile("lambda\\$(.+)\\$\\d+");

    /** {@code Circle}, {@code Circle.area()}, {@code Circle.radius}, with the input's class names. */
    static String describe(Ref ref, UnaryOperator<String> treeNames) {
        String owner = simple(treeNames.apply(ref.owner()));
        if (ref.isClass()) return Ui.visible(treeNames.apply(ref.owner()).replace('/', '.'));
        return owner + "." + member(ref, treeNames);
    }

    /** A member as people read it; a lambda body is "lambda in m". */
    static String member(Ref ref, UnaryOperator<String> treeNames) {
        if (ref.isClass()) return Text.t("usages.classLevel");
        if (ref.isField()) return Ui.visible(ref.name());
        var lambda = LAMBDA.matcher(ref.name());
        if (lambda.matches()) {
            String written = lambda.group(1).equals("new") ? MethodLocator.simpleName(treeNames.apply(ref.owner()))
                : lambda.group(1).equals("static") ? "static { }" : lambda.group(1);
            return Text.t("usages.lambdaIn", Ui.visible(written));
        }
        return Descriptors.pretty(treeNames.apply(ref.owner()), ref.name() + ref.descriptor());
    }

    private static String simple(String internalName) {
        String simple = internalName.substring(internalName.lastIndexOf('/') + 1);
        return Ui.visible(simple);
    }

    /** One site: what it does, to what, where. */
    static String site(ReferenceIndex.Usage usage, UnaryOperator<String> treeNames) {
        var site = usage.site();
        String text = Text.t("usages.kind." + site.kind().name()) + " · " + describe(site.target(), treeNames);
        if (usage.via() != null) text += " " + Text.t("usages.via", describe(usage.via(), treeNames));
        if (site.line() > 0) text += " · " + Text.t("usages.line", site.line());
        return text;
    }

    private final class Renderer extends DefaultTreeCellRenderer {
        @Override public Component getTreeCellRendererComponent(JTree tree, Object value, boolean selected, boolean expanded, boolean leaf, int row, boolean focus) {
            super.getTreeCellRendererComponent(tree, value, selected, expanded, leaf, row, focus);
            setFont(tree.getFont());
            switch (((DefaultMutableTreeNode) value).getUserObject()) {
                case ClassGroup group -> {
                    setText("<html><b>" + Ui.escape(simple(group.treeName())) + "</b> <span style='color:gray'>" + Ui.escape(packageOf(group.treeName()))
                        + " · " + group.count() + "</span></html>");
                    setIcon(Icons.kind('C', ExplorerPanel.kindColor('C'), null));
                }
                case MemberGroup group -> { setText(member(group.from(), treeNames) + "  (" + group.count() + ")"); setIcon(Icons.of(Icons.Glyph.GOTO, 14)); }
                case SiteNode site -> { setText(site(site.usage(), treeNames)); setIcon(Icons.of(Icons.Glyph.FORWARD, 12)); }
                case CallerNode caller -> {
                    var node = caller.node();
                    String text = describe(node.method(), treeNames);
                    if (!node.sites().isEmpty()) text += "  · " + Text.t("usages.sites", node.sites().size());
                    if (node.cycle()) text += "  " + Text.t("usages.cycle");
                    else if (node.cut()) text += "  " + Text.t("usages.cut");
                    setText(text);
                    setIcon(Icons.of(node.sites().isEmpty() ? Icons.Glyph.GOTO : Icons.Glyph.BACK, 14));
                }
                case null, default -> { }
            }
            return this;
        }

        private String packageOf(String treeName) {
            int slash = treeName.lastIndexOf('/');
            return slash < 0 ? "" : Ui.visible(treeName.substring(0, slash).replace('/', '.'));
        }
    }

    // For tests.
    JTree tree() { return tree; }
    String titleText() { return title.getText(); }
}
