package dev.classrevive.gui;

import com.formdev.flatlaf.FlatClientProperties;
import dev.classrevive.workbench.ResultIndex;
import java.awt.*;
import java.awt.event.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import javax.swing.*;
import org.fife.ui.rsyntaxtextarea.*;
import org.fife.ui.rsyntaxtextarea.parser.*;
import org.fife.ui.rtextarea.*;

/**
 * Phase G3: one editor tab. A class shows the selected source, any engine's version or its bytecode; a resource shows its
 * text. Content is read off the event thread; the editor is read-only. Phase G8: the views are a segmented switch, a strip
 * explains bytecode before a decompile, the evidence and javac errors are located off the event thread, and very large
 * texts open without folding so scrolling stays fast.
 */
final class CodeTab extends JPanel {
    /** One way to look at the tab's subject; {@code id} is a result candidate, {@link #BYTECODE} or {@link #RESOURCE}. */
    record View(String id, String label) { @Override public String toString() { return label; } }
    static final String BYTECODE = "bytecode", RESOURCE = "resource";
    static final long MAX_SOURCE_BYTES = 8L * 1024 * 1024;
    /** Phase G8: above either limit a text opens without code folding and occurrence marking. */
    static final int LARGE_CHARS = 1_500_000, LARGE_LINES = 30_000;

    final String key;
    private final Workspace workspace;
    private final Workspace.ClassItem item;
    private final Workspace.ResourceItem resource;
    private final GuiSettings settings;
    private final Consumer<String> status;
    private final RSyntaxTextArea area = new RSyntaxTextArea();
    private final RTextScrollPane scroll = new RTextScrollPane(area, true);
    private final Ui.Segmented<View> views = new Ui.Segmented<>(view -> { if (!this.adjusting) load(view); });
    private final Ui.Chip level = Ui.softChip("", Color.GRAY);
    private final FindBar find = new FindBar(area);
    private final JPanel strip = new JPanel(new BorderLayout(10, 0));
    private final JLabel stripText = new JLabel();
    private final JButton stripAction = new JButton();
    private boolean large;
    /**
     * Phase G8: true once the latest load's text is in the editor. {@code SwingWorker.isDone()} turns true when the
     * background part ends, before {@code done()} has put the text in: a line asked for in between was applied to the
     * "Loading" text and then lost.
     */
    private boolean ready;
    private SwingWorker<Loaded,Void> loading;
    private int pendingLine = -1;
    private final EvidenceParser parser = new EvidenceParser();
    private Navigator navigator = name -> { };
    private Map<String,String> imports = Map.of();
    private int pendingCaret = -1;
    private Locator pendingLocator;
    private int evidenceIcons, firstEvidenceLine;

    /** Phase G6: finds a 1-based line in the text of a view (-1 when not found). */
    interface Locator { int line(String viewId, String text); }

    /** Phase G4: what a Ctrl+click on a class name asks the window to do. */
    interface Navigator { void openClass(String className); }

    static CodeTab forClass(Workspace workspace, Workspace.ClassItem item, GuiSettings settings, Consumer<String> status) {
        return new CodeTab("class:" + item.name(), workspace, item, null, settings, status);
    }

    static CodeTab forResource(Workspace workspace, Workspace.ResourceItem resource, GuiSettings settings, Consumer<String> status) {
        return new CodeTab("resource:" + resource.entry(), workspace, null, resource, settings, status);
    }

    private CodeTab(String key, Workspace workspace, Workspace.ClassItem item, Workspace.ResourceItem resource, GuiSettings settings, Consumer<String> status) {
        super(new BorderLayout());
        this.key = key; this.workspace = workspace; this.item = item; this.resource = resource; this.settings = settings; this.status = status;
        area.setEditable(false);
        area.setCodeFoldingEnabled(true);
        area.setMarkOccurrences(true);
        area.setHighlightCurrentLine(true);
        area.setAntiAliasingEnabled(true);
        area.setAnimateBracketMatching(false);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.setFoldIndicatorEnabled(true);
        // Phase G4: evidence icons per method, javac errors as underlines with an overview strip, Ctrl+click on class names.
        scroll.setIconRowHeaderEnabled(true);
        area.addParser(parser);
        area.setHyperlinksEnabled(true);
        area.setLinkGenerator(this::link);
        area.addCaretListener(event -> status.accept(caretText()));
        // Phase G6: a right click puts the caret on the word under the mouse, so the context menu acts on it.
        area.addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent event) {
                if (!SwingUtilities.isRightMouseButton(event) && !event.isPopupTrigger()) return;
                int offset = area.viewToModel2D(event.getPoint());
                if (offset >= 0 && (area.getSelectionStart() == area.getSelectionEnd() || offset < area.getSelectionStart() || offset > area.getSelectionEnd()))
                    area.setCaretPosition(offset);
            }
        });

        var header = new JPanel(new BorderLayout(Ui.GAP, 0)) {
            @Override public void updateUI() { super.updateUI(); setBackground(Theme.panel()); }
        };
        header.setBorder(BorderFactory.createCompoundBorder(Ui.bottomLine(), Ui.padding(5, 12, 5, 8)));
        var name = new JLabel();
        if (item != null) {
            char kind = ExplorerPanel.kind(item);
            name.setIcon(Icons.kind(kind, ExplorerPanel.kindColor(kind), null));
            String pkg = item.packageName();
            name.setText("<html>" + (pkg.isEmpty() ? "" : "<span style='color:" + hex(Theme.secondary()) + "'>" + Ui.escape(pkg) + ".</span>")
                + "<b>" + Ui.escape(item.simpleName()) + "</b></html>");
            name.setToolTipText(Ui.visible(item.name().replace('/', '.')));
        } else {
            name.setIcon(Icons.of(Icons.Glyph.RESOURCE, 14));
            name.setText(Ui.visible(resource.entry()));
        }
        name.setIconTextGap(8);
        var left = new JPanel(new FlowLayout(FlowLayout.LEFT, Ui.GAP, 4));
        left.setOpaque(false);
        left.add(name);
        left.add(level);
        header.add(left, BorderLayout.CENTER);
        header.add(views, BorderLayout.EAST);
        // The strip under the header: what a bytecode-only view is, or why a large file has no folding.
        strip.setBorder(BorderFactory.createCompoundBorder(Ui.bottomLine(), Ui.padding(6, 14, 6, 10)));
        strip.setBackground(Theme.accentSoft());
        stripText.setIcon(Icons.of(Icons.Glyph.INFO, 14, Theme.ACCENT));
        stripText.setIconTextGap(8);
        strip.add(stripText, BorderLayout.CENTER);
        stripAction.putClientProperty(FlatClientProperties.STYLE, Ui.PRIMARY_STYLE + "; margin: 2,10,2,10");
        stripAction.setIcon(Icons.of(Icons.Glyph.PLAY, 12, Theme.ON_ACCENT));
        stripAction.setText(Text.t("action.decompile"));
        stripAction.setFocusable(false);
        var actionHolder = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        actionHolder.setOpaque(false);
        actionHolder.add(stripAction);
        strip.add(actionHolder, BorderLayout.EAST);
        strip.setVisible(false);
        var top = new JPanel(new BorderLayout());
        top.add(header, BorderLayout.NORTH);
        top.add(strip, BorderLayout.SOUTH);
        add(top, BorderLayout.NORTH);
        var editor = new JPanel(new BorderLayout());
        editor.add(scroll, BorderLayout.CENTER);
        editor.add(new ErrorStrip(area), BorderLayout.EAST);
        add(editor, BorderLayout.CENTER);
        add(find, BorderLayout.SOUTH);

        bind("ctrl F", "find", find::open);
        bind("F3", "next", () -> find.step(true));
        bind("shift F3", "previous", () -> find.step(false));
        bind("ctrl G", "line", this::askLine);
        bind("ESCAPE", "closeFind", find::close);
        applyLook();
        refreshViews();
    }

    private void bind(String key, String name, Runnable run) {
        getInputMap(WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(KeyStroke.getKeyStroke(key), name);
        getActionMap().put(name, new AbstractAction() { @Override public void actionPerformed(ActionEvent e) { run.run(); } });
    }

    String title() { return item != null ? Ui.visible(item.simpleName()) : Ui.visible(resource.entry().substring(resource.entry().lastIndexOf('/') + 1)); }
    String tooltip() { return item != null ? Ui.visible(item.name().replace('/', '.')) : Ui.visible(resource.entry()); }
    Workspace.ClassItem classItem() { return item; }
    void navigator(Navigator navigator) { this.navigator = navigator; }
    /** The caret offset, for the back/forward history. */
    int caret() { return area.getCaretPosition(); }
    /** Puts the caret at {@code offset} once the content is there. */
    void caret(int offset) {
        pendingCaret = offset;
        if (ready) applyPendingCaret();
    }
    /** For tests: how many methods got an evidence icon, and the link a Ctrl+click at {@code offset} would follow. */
    int evidenceIcons() { return evidenceIcons; }
    int firstEvidenceLine() { return firstEvidenceLine; }
    LinkGeneratorResult linkAt(int offset) { return link(area, offset); }

    String viewId() { return views.selected() instanceof View view ? view.id() : null; }

    /** Phase G8: the strip's Decompile button runs {@code action} and is enabled with it (off while a decompile runs). */
    void onDecompile(Action action) {
        decompileAction = action;
        stripAction.addActionListener(e -> { if (action.isEnabled()) action.actionPerformed(e); });
        stripAction.setEnabled(action.isEnabled());
        if (isDisplayable()) action.addPropertyChangeListener(enabledListener);
    }
    private Action decompileAction;
    private final java.beans.PropertyChangeListener enabledListener = e -> {
        if ("enabled".equals(e.getPropertyName())) stripAction.setEnabled(decompileAction.isEnabled());
    };

    /** The window's action outlives the tab: listen only while the tab is shown, so a closed tab is not kept alive. */
    @Override public void addNotify() {
        super.addNotify();
        if (decompileAction != null) { decompileAction.addPropertyChangeListener(enabledListener); stripAction.setEnabled(decompileAction.isEnabled()); }
    }
    @Override public void removeNotify() {
        if (decompileAction != null) decompileAction.removePropertyChangeListener(enabledListener);
        super.removeNotify();
    }

    /** Re-lists the views (a decompile just finished) keeping the current choice when it still exists. */
    void refreshViews() {
        var current = views.selected();
        var list = new ArrayList<View>();
        if (resource != null) list.add(new View(RESOURCE, Text.t("code.view.resource")));
        else {
            var result = workspace.result();
            if (result != null) for (String candidate : result.candidates())
                list.add(new View(candidate, candidate.equals(ResultIndex.SELECTION) ? Text.t("code.view.selectedShort") : engineName(candidate)));
            list.add(new View(BYTECODE, Text.t("code.view.bytecode")));
        }
        var evidence = item == null ? Optional.<ResultIndex.ClassEntry>empty() : workspace.evidence(item.name());
        level.setVisible(evidence.isPresent() && evidence.get().level() != null);
        evidence.ifPresent(entry -> {
            if (entry.level() == null) return;
            level.set(Text.t("level." + entry.level().name()), Theme.level(entry.level()));
            level.setToolTipText(Text.t("level." + entry.level().name() + ".meaning"));
        });
        View keep = current == null || views.values().size() <= 1 ? null : list.stream().filter(view -> view.id().equals(current.id())).findFirst().orElse(null);
        var result = workspace.result();
        adjusting = true;
        views.values(list, View::label, view -> viewTip(result, view));
        views.select(keep != null ? keep : list.getFirst(), true);
        adjusting = false;
        views.setVisible(list.size() > 1);
        // A new view list after a decompile switches a bytecode-only tab to the selected source.
        load(views.selected());
    }
    private boolean adjusting;

    /** "CFR", "Vineflower", "Procyon", "JADX": the engine ids as their projects write them. */
    static String engineName(String id) {
        return switch (id) {
            case "cfr" -> "CFR"; case "jadx" -> "JADX"; case "vineflower" -> "Vineflower"; case "procyon" -> "Procyon";
            default -> id.isEmpty() ? id : Character.toUpperCase(id.charAt(0)) + id.substring(1);
        };
    }

    private static String viewTip(ResultIndex result, View view) {
        return switch (view.id()) {
            case ResultIndex.SELECTION -> Text.t("code.view.selected.tip");
            case BYTECODE -> Text.t("code.view.bytecode.tip");
            case RESOURCE -> null;
            default -> Text.t("code.view.engine.tip", engineLabel(result, view.id()));
        };
    }

    private static String engineLabel(ResultIndex result, String id) {
        return result.engines().stream().filter(engine -> engine.id().equals(id)).findFirst()
            .map(engine -> Character.toUpperCase(id.charAt(0)) + id.substring(1) + " " + engine.version()).orElse(id);
    }

    void select(String viewId) {
        for (var view : views.values()) if (view.id().equals(viewId)) { views.select(view, false); return; }
    }

    /** Phase G6: moves to the line {@code locator} finds once the content is there; not found, the class's declaration. */
    void reveal(Locator locator) {
        pendingLocator = locator;
        if (ready) applyPendingLocator();
    }

    private void applyPendingLocator() {
        var locator = pendingLocator;
        pendingLocator = null;
        if (locator == null) return;
        int line = locator.line(viewId(), area.getText());
        if (line > 0) { pendingLine = line; applyPendingLine(); } else revealDeclaration();
    }

    /** Phase G6: the find usages and call hierarchy actions, first in the editor's context menu. */
    void contextActions(List<Action> actions) {
        var menu = area.getPopupMenu();
        if (menu == null) return;
        int at = 0;
        for (var action : actions) menu.insert(action, at++);
        menu.insert(new JPopupMenu.Separator(), at);
    }

    /** Phase G6: the input's class a simple name written in this tab means, by the Phase G4 rules, or null. */
    String resolveClass(String simple) { return item == null ? null : resolve(simple); }

    /** Phase G6: the text of the caret's line. */
    String caretLine() {
        try {
            int line = area.getCaretLineNumber();
            int start = area.getLineStartOffset(line), end = area.getLineEndOffset(line);
            return area.getText(start, end - start).stripTrailing();
        } catch (javax.swing.text.BadLocationException outside) {
            return "";
        }
    }

    /** Moves to a 1-based line once the content is there. */
    void goToLine(int line) {
        pendingLine = line;
        if (ready) applyPendingLine();
    }

    /** Phase G8: what a background load hands to the event thread: the text, its style and its decorations. */
    private record Loaded(String text, String style, Map<String,Map<String,Integer>> methods, List<ResultIndex.Diagnostic> diagnostics,
                          Map<String,String> imports) {}

    private void load(View view) {
        if (view == null) return;
        if (loading != null) loading.cancel(true);
        ready = false;
        area.setText(Text.t("code.loading"));
        area.setSyntaxEditingStyle(SyntaxConstants.SYNTAX_STYLE_NONE);
        var result = workspace.result();
        loading = new SwingWorker<>() {
            @Override protected Loaded doInBackground() throws Exception {
                if (view.id().equals(RESOURCE)) {
                    byte[] bytes = workspace.entryBytes(resource.entry());
                    if (binary(bytes)) return plain(Text.t("code.binary", bytes.length));
                    return new Loaded(new String(bytes, StandardCharsets.UTF_8), styleFor(resource.entry()), Map.of(), List.of(), Map.of());
                }
                if (view.id().equals(BYTECODE)) return plain(workspace.bytecode(item));
                Path source = workspace.source(item.name(), view.id()).orElse(null);
                if (source == null) return plain(Text.t("code.noSource", view.label()));
                if (Files.size(source) > MAX_SOURCE_BYTES) return plain(Text.t("code.tooLarge", source.getFileName()));
                String text = Files.readString(source, StandardCharsets.UTF_8);
                // The method declarations and javac errors are found here, off the event thread: both read the whole text.
                Map<String,Map<String,Integer>> methods = Map.of();
                List<ResultIndex.Diagnostic> diagnostics = List.of();
                if (result != null && view.id().equals(ResultIndex.SELECTION)) {
                    String file = result.output().resolve(ResultIndex.SELECTION).resolve("sources").relativize(source).toString().replace('\\', '/');
                    var declared = new LinkedHashMap<String,List<String>>();
                    for (var entry : result.classes().values())
                        if (entry.file().equals(file) && !entry.methods().isEmpty()) declared.put(entry.className(), List.copyOf(entry.methods().keySet()));
                    methods = MethodLocator.locate(text, declared);
                    diagnostics = result.diagnostics(file);
                }
                return new Loaded(text, SyntaxConstants.SYNTAX_STYLE_JAVA, methods, diagnostics, imports(text));
            }
            @Override protected void done() {
                // A newer load may have started after this one finished but before its result arrived: only the latest applies.
                if (isCancelled() || loading != this) return;
                Loaded loaded;
                try { loaded = get(); }
                catch (Exception failure) {
                    Throwable cause = failure.getCause() == null ? failure : failure.getCause();
                    loaded = plain(Text.t("code.failed", String.valueOf(cause.getMessage())));
                }
                large = loaded.text().length() > LARGE_CHARS || lines(loaded.text()) > LARGE_LINES;
                area.setCodeFoldingEnabled(!large);
                area.setMarkOccurrences(!large);
                area.setSyntaxEditingStyle(loaded.style());
                area.setText(loaded.text());
                area.discardAllEdits();
                decorate(view, loaded);
                refreshStrip(view);
                ready = true;
                if (pendingCaret >= 0) applyPendingCaret();
                else if (pendingLocator != null) applyPendingLocator();
                else if (pendingLine > 0) applyPendingLine();
                else revealDeclaration();
            }
        };
        loading.execute();
    }

    private static Loaded plain(String text) { return new Loaded(text, SyntaxConstants.SYNTAX_STYLE_NONE, Map.of(), List.of(), Map.of()); }

    /** Lines in {@code text}, counted up to just past {@link #LARGE_LINES}. */
    static int lines(String text) {
        int count = 1;
        for (int i = 0; i < text.length() && count <= LARGE_LINES; i++) if (text.charAt(i) == '\n') count++;
        return count;
    }

    /** Phase G8: the strip explains a bytecode-only view (with a Decompile button) or a large file opened without folding. */
    private void refreshStrip(View view) {
        boolean bytecodeOnly = item != null && workspace.result() == null && view != null && BYTECODE.equals(view.id());
        if (bytecodeOnly) {
            stripText.setText(Text.t("code.strip.bytecode"));
            stripAction.setVisible(true);
        } else if (large) {
            stripText.setText(Text.t("code.strip.large"));
            stripAction.setVisible(false);
        }
        strip.setVisible(bytecodeOnly || large);
        strip.setBackground(Theme.accentSoft());
        revalidate();
    }

    /** For tests: whether the text opened in large-file mode, and whether the strip is showing. */
    boolean large() { return large; }
    boolean stripShown() { return strip.isVisible(); }

    private void applyPendingCaret() {
        int offset = pendingCaret;
        pendingCaret = -1;
        if (offset < 0) return;
        area.setCaretPosition(Math.min(offset, area.getDocument().getLength()));
        centerCaret();
    }

    /**
     * Phase G4: in the selected source, a gutter icon on each measured method of every class this file declares (its
     * round trip outcome) and the file's javac errors as underlines. Other views get neither: the evidence is about the
     * selected source only.
     */
    private void decorate(View view, Loaded loaded) {
        scroll.getGutter().removeAllTrackingIcons();
        evidenceIcons = 0;
        firstEvidenceLine = 0;
        parser.diagnostics = loaded.diagnostics();
        imports = loaded.imports();
        var result = workspace.result();
        if (item != null && result != null && view.id().equals(ResultIndex.SELECTION)) {
            loaded.methods().forEach((className, found) -> found.forEach((method, line) -> {
                var entry = result.classes().get(className);
                var outcome = entry == null ? null : entry.methods().get(method);
                if (outcome == null) return;
                try {
                    scroll.getGutter().addLineTrackingIcon(line - 1, Icons.dot(outcomeColor(outcome), 10),
                        Text.t("evidence.method", Descriptors.pretty(className, method), Text.t("outcome." + outcome.name())));
                    evidenceIcons++;
                    if (firstEvidenceLine == 0 || line < firstEvidenceLine) firstEvidenceLine = line;
                } catch (javax.swing.text.BadLocationException ignored) { }
            }));
        }
        area.forceReparsing(parser);
    }

    static Color outcomeColor(ResultIndex.Outcome outcome) {
        return switch (outcome) {
            case IDENTICAL -> Theme.level(ResultIndex.Level.EXACT);
            case EQUIVALENT_AFTER_NORMALIZATION -> Theme.level(ResultIndex.Level.CLOSE);
            case DIFFERENT -> Theme.level(ResultIndex.Level.UNVERIFIED);
        };
    }

    /** {@code import a.b.C;} lines: simple name to internal name. */
    private static Map<String,String> imports(String text) {
        var found = new HashMap<String,String>();
        var matcher = Pattern.compile("(?m)^\\s*import\\s+([\\w.]+)\\.(\\w+)\\s*;").matcher(text);
        while (matcher.find()) found.put(matcher.group(2), matcher.group(1).replace('.', '/') + "/" + matcher.group(2));
        return found;
    }

    /** Phase G4: Ctrl+click on a class name of this input opens it; JDK and library names are not links. */
    private LinkGeneratorResult link(RSyntaxTextArea text, int offset) {
        if (item == null) return null;
        Token token = text.modelToToken(offset);
        if (token == null || !token.isIdentifier()) return null;
        String word = token.getLexeme();
        if (word.isEmpty() || !Character.isUpperCase(word.charAt(0))) return null;
        String target = resolve(word);
        if (target == null || target.equals(item.name())) return null;
        int start = token.getOffset();
        return new LinkGeneratorResult() {
            @Override public javax.swing.event.HyperlinkEvent execute() {
                navigator.openClass(target);
                return new javax.swing.event.HyperlinkEvent(area, javax.swing.event.HyperlinkEvent.EventType.ACTIVATED, null, target);
            }
            @Override public int getSourceOffset() { return start; }
        };
    }

    /** The input's class a simple name means here: an explicit import, then this package, then a unique name. */
    private String resolve(String simple) {
        var candidates = workspace.classesNamed(simple);
        if (candidates.isEmpty()) return null;
        String imported = imports.get(simple);
        // Imports name the classes as the engines saw them (portable names); the tree uses the input's own names.
        if (imported != null) { String original = workspace.originalOf(imported); return candidates.contains(original) ? original : null; }
        String pkg = item.name().contains("/") ? item.name().substring(0, item.name().lastIndexOf('/') + 1) : "";
        String outer = item.name().contains("$") ? item.name().substring(0, item.name().indexOf('$')) : item.name();
        for (String name : candidates) if (name.startsWith(outer + "$")) return name;
        for (String name : candidates) if (name.startsWith(pkg) && name.indexOf('/', pkg.length()) < 0 && !name.substring(pkg.length()).contains("$")) return name;
        return candidates.size() == 1 ? candidates.getFirst() : null;
    }

    private final class EvidenceParser extends AbstractParser {
        List<ResultIndex.Diagnostic> diagnostics = List.of();
        @Override public ParseResult parse(RSyntaxDocument document, String style) {
            var result = new DefaultParseResult(this);
            var root = document.getDefaultRootElement();
            for (var diagnostic : diagnostics) {
                int line = (int) diagnostic.line() - 1;
                if (line < 0 || line >= root.getElementCount()) continue;
                var element = root.getElement(line);
                int start = element.getStartOffset(), end = Math.max(start, element.getEndOffset() - 1);
                int offset = diagnostic.column() > 0 ? Math.min(end, start + (int) diagnostic.column() - 1) : start;
                int length = 0;
                try {
                    String rest = document.getText(offset, end - offset);
                    while (length < rest.length() && Character.isJavaIdentifierPart(rest.charAt(length))) length++;
                    if (length == 0) length = Math.max(1, rest.stripTrailing().length());
                } catch (javax.swing.text.BadLocationException ignored) { length = 1; }
                var notice = new DefaultParserNotice(this, Text.t("diagnostic.tooltip", diagnostic.message()), line, offset, length);
                notice.setLevel(ParserNotice.Level.ERROR);
                result.addNotice(notice);
            }
            return result;
        }
    }

    private void applyPendingLine() {
        int line = pendingLine;
        pendingLine = -1;
        if (line <= 0) return;
        try {
            int index = Math.min(line, area.getLineCount()) - 1;
            // Caret at the line's first character, no selection: a selection would hide a javac error's underline.
            int start = area.getLineStartOffset(index), end = area.getLineEndOffset(index);
            String text = area.getText(start, end - start);
            area.setCaretPosition(start + (text.length() - text.stripLeading().length()));
            centerCaret();
        } catch (javax.swing.text.BadLocationException ignored) { }
    }

    /** Nested classes share their outer class's file: put the caret on this one's declaration. */
    private void revealDeclaration() {
        area.setCaretPosition(0);
        if (item == null || !item.name().contains("$")) return;
        String simple = item.simpleName().substring(item.simpleName().lastIndexOf('$') + 1);
        if (simple.isEmpty() || Character.isDigit(simple.charAt(0))) return;
        var matcher = Pattern.compile("\\b(class|interface|enum|record)\\s+" + Pattern.quote(simple) + "\\b").matcher(area.getText());
        if (matcher.find()) { area.setCaretPosition(matcher.start()); centerCaret(); }
    }

    private void centerCaret() {
        SwingUtilities.invokeLater(() -> {
            try {
                var shape = area.modelToView2D(area.getCaretPosition());
                // Not laid out yet (the tab is not on screen): nothing to scroll.
                if (shape == null) return;
                var rect = shape.getBounds();
                var viewport = scroll.getViewport();
                int y = Math.max(0, rect.y - viewport.getExtentSize().height / 3);
                viewport.setViewPosition(new Point(0, Math.min(y, Math.max(0, area.getHeight() - viewport.getExtentSize().height))));
            } catch (javax.swing.text.BadLocationException ignored) { }
        });
    }

    private void askLine() {
        String answer = JOptionPane.showInputDialog(this, Text.t("code.goToLine", area.getLineCount()), Text.t("code.goToLine.title"), JOptionPane.PLAIN_MESSAGE);
        if (answer == null) return;
        try { goToLine(Integer.parseInt(answer.strip())); } catch (NumberFormatException ignored) { }
    }

    String caretText() {
        int line = area.getCaretLineNumber() + 1, column = area.getCaretOffsetFromLineStart() + 1;
        return Text.t("code.caret", line, column);
    }

    /** Colors and font for the current theme and font size. */
    void applyLook() {
        String file = Theme.dark() ? "dark.xml" : "idea.xml";
        try (var in = RSyntaxTextArea.class.getResourceAsStream("/org/fife/ui/rsyntaxtextarea/themes/" + file)) {
            org.fife.ui.rsyntaxtextarea.Theme.load(in).apply(area);
        } catch (java.io.IOException | RuntimeException ignored) { }
        area.setFont(Theme.codeFont(settings.fontSize()));
        scroll.getGutter().setLineNumberFont(Theme.codeFont(Math.max(GuiSettings.MIN_FONT, settings.fontSize() - 1)));
        if (Theme.dark()) darkScheme(area); else lightScheme(area);
        editorColors(area);
        var palette = Theme.palette();
        scroll.getGutter().setBackground(palette.editor());
        scroll.getGutter().setLineNumberColor(Theme.dark() ? new Color(0x4E4E56) : new Color(0xA8A8B2));
        scroll.getGutter().setCurrentLineNumberColor(Theme.dark() ? Theme.ACCENT : palette.accentText());
        scroll.getGutter().setBorderColor(palette.editor());
        scroll.getGutter().setFoldIndicatorForeground(Theme.lineStrong());
        scroll.getGutter().setFoldBackground(palette.editor());
        strip.setBackground(Theme.accentSoft());
        find.updateUI();
    }

    /** Phase G8: the editor surface of the current palette; shared with the comparison view. */
    static void editorColors(RSyntaxTextArea area) {
        var palette = Theme.palette();
        area.setBackground(palette.editor());
        area.setCurrentLineHighlightColor(palette.currentLine());
        area.setSelectionColor(palette.codeSelection());
        area.setCaretColor(Theme.dark() ? Theme.ACCENT : palette.accentText());
        area.setMarkOccurrencesColor(Theme.alpha(Theme.ACCENT, Theme.dark() ? 46 : 40));
        area.setMatchedBracketBGColor(Theme.alpha(Theme.ACCENT, Theme.dark() ? 46 : 40));
        area.setMatchedBracketBorderColor(Theme.ACCENT);
        area.setFadeCurrentLineHighlight(false);
    }

    /** Phase G8: the light editor keeps the logic of the dark one: keywords in a dark orange, quiet comments, green strings. */
    static void lightScheme(RSyntaxTextArea area) {
        var scheme = area.getSyntaxScheme();
        color(scheme, Token.RESERVED_WORD, 0xB8530A);
        color(scheme, Token.RESERVED_WORD_2, 0xB8530A);
        color(scheme, Token.DATA_TYPE, 0x8A4A12);
        color(scheme, Token.LITERAL_STRING_DOUBLE_QUOTE, 0x2E7D32);
        color(scheme, Token.LITERAL_CHAR, 0x2E7D32);
        color(scheme, Token.LITERAL_NUMBER_DECIMAL_INT, 0x1750A0);
        color(scheme, Token.LITERAL_NUMBER_FLOAT, 0x1750A0);
        color(scheme, Token.LITERAL_NUMBER_HEXADECIMAL, 0x1750A0);
        color(scheme, Token.LITERAL_BOOLEAN, 0xB8530A);
        color(scheme, Token.ANNOTATION, 0x7A4BB0);
        color(scheme, Token.COMMENT_EOL, 0x8A8A94);
        color(scheme, Token.COMMENT_MULTILINE, 0x8A8A94);
        color(scheme, Token.COMMENT_DOCUMENTATION, 0x7A7A86);
        color(scheme, Token.IDENTIFIER, 0x1F1F23);
        color(scheme, Token.FUNCTION, 0x1F1F23);
        color(scheme, Token.OPERATOR, 0x3F3F46);
        color(scheme, Token.SEPARATOR, 0x3F3F46);
        area.setSyntaxScheme(scheme);
    }

    private static String hex(Color color) { return String.format("#%06X", color.getRGB() & 0xFFFFFF); }

    /** Near-black editor colors with keywords in the orange accent; shared with the comparison view. */
    static void darkScheme(RSyntaxTextArea area) {
        var scheme = area.getSyntaxScheme();
        color(scheme, Token.RESERVED_WORD, 0xFF8A1F);
        color(scheme, Token.RESERVED_WORD_2, 0xFF8A1F);
        color(scheme, Token.DATA_TYPE, 0xFFB366);
        color(scheme, Token.LITERAL_STRING_DOUBLE_QUOTE, 0xA6D189);
        color(scheme, Token.LITERAL_CHAR, 0xA6D189);
        color(scheme, Token.LITERAL_NUMBER_DECIMAL_INT, 0xE5C07B);
        color(scheme, Token.LITERAL_NUMBER_FLOAT, 0xE5C07B);
        color(scheme, Token.LITERAL_NUMBER_HEXADECIMAL, 0xE5C07B);
        color(scheme, Token.LITERAL_BOOLEAN, 0xFF8A1F);
        color(scheme, Token.ANNOTATION, 0xC0A0F0);
        color(scheme, Token.COMMENT_EOL, 0x6B6B73);
        color(scheme, Token.COMMENT_MULTILINE, 0x6B6B73);
        color(scheme, Token.COMMENT_DOCUMENTATION, 0x7A7A82);
        color(scheme, Token.IDENTIFIER, 0xE4E4E7);
        color(scheme, Token.FUNCTION, 0xE4E4E7);
        color(scheme, Token.OPERATOR, 0xC8C8CE);
        color(scheme, Token.SEPARATOR, 0xC8C8CE);
        area.setSyntaxScheme(scheme);
    }

    private static void color(SyntaxScheme scheme, int type, int rgb) {
        var style = scheme.getStyle(type);
        if (style != null) style.foreground = new Color(rgb);
    }

    void focusEditor() { area.requestFocusInWindow(); }
    void openFind() { find.open(); }
    String content() { return area.getText(); }

    private static boolean binary(byte[] bytes) {
        int limit = Math.min(bytes.length, 8192);
        for (int i = 0; i < limit; i++) if (bytes[i] == 0) return true;
        return false;
    }

    private static String styleFor(String entry) {
        String name = entry.toLowerCase(Locale.ROOT);
        if (name.endsWith(".java")) return SyntaxConstants.SYNTAX_STYLE_JAVA;
        if (name.endsWith(".xml") || name.endsWith(".xsd") || name.endsWith(".pom")) return SyntaxConstants.SYNTAX_STYLE_XML;
        if (name.endsWith(".properties")) return SyntaxConstants.SYNTAX_STYLE_PROPERTIES_FILE;
        if (name.endsWith(".json")) return SyntaxConstants.SYNTAX_STYLE_JSON;
        if (name.endsWith(".html") || name.endsWith(".htm")) return SyntaxConstants.SYNTAX_STYLE_HTML;
        if (name.endsWith(".yml") || name.endsWith(".yaml")) return SyntaxConstants.SYNTAX_STYLE_YAML;
        if (name.endsWith(".js")) return SyntaxConstants.SYNTAX_STYLE_JAVASCRIPT;
        if (name.endsWith(".kt") || name.endsWith(".kts")) return SyntaxConstants.SYNTAX_STYLE_KOTLIN;
        return SyntaxConstants.SYNTAX_STYLE_NONE;
    }

    /** Find in this tab: Enter for next, Shift+Enter for previous, all matches marked. */
    static final class FindBar extends JPanel {
        private final RSyntaxTextArea area;
        private final JTextField field = new JTextField(24);
        private final JToggleButton matchCase = new JToggleButton("Aa");
        private final JLabel count = Ui.muted("");

        FindBar(RSyntaxTextArea area) {
            super(new FlowLayout(FlowLayout.LEFT, 6, 5));
            this.area = area;
            setBorder(BorderFactory.createCompoundBorder(Ui.topLine(), Ui.padding(0, 6, 0, 6)));
            setVisible(false);
            field.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, Text.t("find.placeholder"));
            field.putClientProperty(FlatClientProperties.TEXT_FIELD_LEADING_ICON, Icons.of(Icons.Glyph.SEARCH, 14));
            matchCase.setToolTipText(Text.t("find.matchCase"));
            matchCase.putClientProperty(FlatClientProperties.BUTTON_TYPE, FlatClientProperties.BUTTON_TYPE_TOOLBAR_BUTTON);
            var previous = new JButton(Icons.of(Icons.Glyph.ARROW_UP, 14)); var next = new JButton(Icons.of(Icons.Glyph.ARROW_DOWN, 14));
            var close = new JButton(Icons.of(Icons.Glyph.CLOSE, 13));
            for (var button : List.of(previous, next, close)) button.putClientProperty(FlatClientProperties.BUTTON_TYPE, FlatClientProperties.BUTTON_TYPE_TOOLBAR_BUTTON);
            previous.setToolTipText(Text.t("find.previous")); next.setToolTipText(Text.t("find.next")); close.setToolTipText(Text.t("find.close"));
            add(field); add(matchCase); add(previous); add(next); add(count); add(close);
            previous.addActionListener(e -> step(false));
            next.addActionListener(e -> step(true));
            close.addActionListener(e -> close());
            matchCase.addActionListener(e -> mark());
            field.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
                @Override public void insertUpdate(javax.swing.event.DocumentEvent e) { mark(); }
                @Override public void removeUpdate(javax.swing.event.DocumentEvent e) { mark(); }
                @Override public void changedUpdate(javax.swing.event.DocumentEvent e) { mark(); }
            });
            field.addKeyListener(new KeyAdapter() {
                @Override public void keyPressed(KeyEvent event) {
                    if (event.getKeyCode() == KeyEvent.VK_ENTER) step(!event.isShiftDown());
                    if (event.getKeyCode() == KeyEvent.VK_ESCAPE) close();
                }
            });
        }

        void open() {
            setVisible(true);
            String selected = area.getSelectedText();
            if (selected != null && !selected.contains("\n")) field.setText(selected);
            field.requestFocusInWindow();
            field.selectAll();
            revalidate();
        }

        void close() {
            if (!isVisible()) return;
            setVisible(false);
            SearchEngine.markAll(area, new SearchContext());
            area.requestFocusInWindow();
        }

        private SearchContext context(boolean forward) {
            var context = new SearchContext(field.getText(), matchCase.isSelected());
            context.setSearchForward(forward);
            context.setMarkAll(true);
            context.setSearchWrap(true);
            return context;
        }

        private void mark() {
            if (field.getText().isEmpty()) { SearchEngine.markAll(area, new SearchContext()); count.setText(""); field.putClientProperty(FlatClientProperties.OUTLINE, null); return; }
            var result = SearchEngine.markAll(area, context(true));
            show(result.getMarkedCount());
        }

        void step(boolean forward) {
            if (!isVisible()) { open(); return; }
            if (field.getText().isEmpty()) return;
            var result = SearchEngine.find(area, context(forward));
            show(result.getMarkedCount());
        }

        private void show(int matches) {
            count.setText(matches == 0 ? Text.t("find.none") : Text.t("find.count", matches));
            field.putClientProperty(FlatClientProperties.OUTLINE, matches == 0 ? FlatClientProperties.OUTLINE_ERROR : null);
        }
    }
}
