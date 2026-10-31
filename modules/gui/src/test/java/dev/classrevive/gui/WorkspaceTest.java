package dev.classrevive.gui;

import dev.classrevive.pipeline.JobListener;
import dev.classrevive.workbench.ResultIndex;
import java.nio.file.*;
import java.util.*;
import javax.swing.tree.DefaultMutableTreeNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class WorkspaceTest {
    @TempDir Path directory;

    @Test void opensAJarIntoClassesAndResourcesWithoutDecompiling() throws Exception {
        var workspace = Workspace.openInput(Fixtures.jar(directory), directory.resolve("cache"));
        assertFalse(workspace.decompiled());
        assertEquals(List.of("demo/Main", "demo/shapes/Circle", "demo/shapes/Circle$Builder", "demo/shapes/Color", "demo/shapes/Shape"),
            workspace.classes().stream().map(Workspace.ClassItem::name).toList());
        assertEquals(List.of("assets/logo.bin", "config/app.properties"), workspace.resources().stream().map(Workspace.ResourceItem::entry).toList());
        assertEquals('I', ExplorerPanel.kind(workspace.classItem("demo/shapes/Shape").orElseThrow()));
        assertEquals('E', ExplorerPanel.kind(workspace.classItem("demo/shapes/Color").orElseThrow()));
        assertEquals('C', ExplorerPanel.kind(workspace.classItem("demo/Main").orElseThrow()));
        // Bytecode is shown before any decompile, parsed in this process.
        String bytecode = workspace.bytecode(workspace.classItem("demo/Main").orElseThrow());
        assertTrue(bytecode.contains("describe"), bytecode);
        assertEquals("name=demo\nversion=1\n", new String(workspace.entryBytes("config/app.properties")));
    }

    @Test void buildsATreeWithNestedClassesUnderTheirOuterClassAndFilters() throws Exception {
        var workspace = Workspace.openInput(Fixtures.jar(directory), directory.resolve("cache"));
        var root = ExplorerPanel.build(workspace, "");
        assertEquals(2, root.getChildCount(), "classes and resources groups");
        var classes = (DefaultMutableTreeNode) root.getChildAt(0);
        assertEquals(List.of("demo", "demo.shapes"), children(classes).stream().map(n -> ((ExplorerPanel.Package) n.getUserObject()).name()).toList());
        var shapes = (DefaultMutableTreeNode) classes.getChildAt(1);
        var circle = children(shapes).stream().filter(n -> ((ExplorerPanel.ClassNode) n.getUserObject()).item().simpleName().equals("Circle")).findFirst().orElseThrow();
        assertEquals("demo/shapes/Circle$Builder", ((ExplorerPanel.ClassNode) ((DefaultMutableTreeNode) circle.getChildAt(0)).getUserObject()).item().name());
        assertEquals(3, shapes.getChildCount(), "Builder is under Circle, not beside it");

        var filtered = ExplorerPanel.build(workspace, "build");
        assertEquals(1, filtered.getChildCount(), "no resource matches");
        var only = (DefaultMutableTreeNode) ((DefaultMutableTreeNode) filtered.getChildAt(0)).getChildAt(0);
        assertEquals("demo/shapes/Circle$Builder", ((ExplorerPanel.ClassNode) ((DefaultMutableTreeNode) only.getChildAt(0)).getUserObject()).item().name());
        var resourceOnly = ExplorerPanel.build(workspace, "app.prop");
        assertEquals(0, resourceOnly.getChildAt(0).getChildCount(), "no class matches");
        assertEquals(1, resourceOnly.getChildAt(1).getChildCount(), "one resource matches");
    }

    @Test void ranksGoToClassMatchesByPrefixFirst() throws Exception {
        var workspace = Workspace.openInput(Fixtures.jar(directory), directory.resolve("cache"));
        assertEquals(List.of("demo/shapes/Circle", "demo/shapes/Color", "demo/shapes/Circle$Builder"),
            GoToClassDialog.rank(workspace.classes(), "c").stream().map(Workspace.ClassItem::name).toList());
        assertEquals("demo/shapes/Shape", GoToClassDialog.rank(workspace.classes(), "shapes.sh").getFirst().name());
        assertTrue(GoToClassDialog.rank(workspace.classes(), "zzz").isEmpty());
    }

    @Test void decompilesAndReopensTheResultWithEvidenceByTheInputsNames() throws Exception {
        Path home = directory.resolve("home");
        System.setProperty("classrevive.home", home.toString());
        try {
            var settings = GuiSettings.load();
            settings.profile(GuiSettings.Profile.CUSTOM);
            settings.customEngines("cfr");
            settings.engineCache(false);
            var workspace = Workspace.openInput(Fixtures.jar(directory), settings.cacheDirectory());
            var request = workspace.request(settings, List.of());
            assertTrue(request.output().startsWith(home.resolve("workspaces")));
            assertEquals(List.of("cfr"), request.engines().stream().map(e -> e.id()).toList());
            var stages = new ArrayList<JobListener.Stage>();
            var index = workspace.decompile(request, new JobListener() { @Override public void stage(Stage stage) { stages.add(stage); } });
            assertTrue(workspace.decompiled());
            assertEquals(JobListener.Stage.INTAKE, stages.getFirst());
            assertEquals("COMPILED", index.summary().compilation());
            var builder = workspace.evidence("demo/shapes/Circle$Builder").orElseThrow();
            assertEquals(ResultIndex.Level.EXACT, builder.level());
            assertTrue(Files.readString(workspace.source("demo/shapes/Circle$Builder", ResultIndex.SELECTION).orElseThrow()).contains("class Builder"));

            var reopened = Workspace.openResult(index.output());
            assertTrue(reopened.decompiled());
            assertEquals(workspace.classes().size(), reopened.classes().size());
            assertEquals(ResultIndex.Level.EXACT, reopened.evidence("demo/Main").orElseThrow().level());
            assertNotEquals(request.output(), workspace.request(settings, List.of()).output(), "every decompile gets a new folder");
        } finally {
            System.clearProperty("classrevive.home");
        }
    }

    @Test void explainsAFileWithoutJava() throws Exception {
        Path text = directory.resolve("notes.txt");
        Files.writeString(text, "just some text, no Java here");
        var refused = assertThrows(Workspace.Unreadable.class, () -> Workspace.openInput(text, directory.resolve("cache")));
        assertTrue(refused.getMessage().contains("notes.txt"), refused.getMessage());
        assertFalse(refused.details().isBlank());
        Path broken = directory.resolve("broken.jar");
        Files.write(broken, new byte[] {'P', 'K', 3, 4, 1, 2});
        assertThrows(Workspace.Unreadable.class, () -> Workspace.openInput(broken, directory.resolve("cache")));
        assertThrows(Workspace.Unreadable.class, () -> Workspace.openInput(directory, directory.resolve("cache")));
    }

    @Test void namesNewOutputFoldersWithoutCollisions() throws Exception {
        Path root = directory.resolve("workspaces");
        Path first = Workspace.newOutput(root, "my app.jar");
        assertTrue(first.getFileName().toString().startsWith("my_app-"), first.toString());
        Files.createDirectories(first);
        Path second = Workspace.newOutput(root, "my app.jar");
        assertNotEquals(first, second);
        assertTrue(Workspace.newOutput(root, ".jar").getFileName().toString().startsWith("input"));
    }

    private static List<DefaultMutableTreeNode> children(DefaultMutableTreeNode node) {
        var list = new ArrayList<DefaultMutableTreeNode>();
        for (int i = 0; i < node.getChildCount(); i++) list.add((DefaultMutableTreeNode) node.getChildAt(i));
        return list;
    }
}
