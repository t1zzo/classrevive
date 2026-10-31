package dev.classrevive.gui;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Phase G4: finding method declarations in decompiled text, and aligning two versions line by line. */
class EditorLogicTest {
    static final String SOURCE = """
        package demo;

        /* class Fake { void ghost() {} } in a comment */
        public class Outer {
            private final String text = "area(1) { not a method";
            static {
                System.out.println("init");
            }
            public Outer() { this(1); }
            public Outer(int x) { }
            public int area() { return area(2); }
            public int area(int scale) {
                return scale;
            }
            public <T> java.util.List<T> list(T a, java.util.Map<String, T> b) throws Exception { return null; }
            abstract static class Shape { abstract double area(); }
            public static class Inner {
                public int area() { return 3; }
            }
        }
        """;

    @Test void locatesDeclarationsByNameAndParameterCountInTheRightClass() {
        var methods = new LinkedHashMap<String,List<String>>();
        methods.put("demo/Outer$Inner", List.of("area()I"));
        methods.put("demo/Outer$Shape", List.of("area()D"));
        methods.put("demo/Outer", List.of("<clinit>()V", "<init>()V", "<init>(I)V", "area()I", "area(I)I", "list(Ljava/lang/Object;Ljava/util/Map;)Ljava/util/List;",
            "lambda$area$0()V", "ghost()V"));
        var lines = MethodLocator.locate(SOURCE, methods);
        var outer = lines.get("demo/Outer");
        assertEquals(6, outer.get("<clinit>()V"));
        assertEquals(9, outer.get("<init>()V"));
        assertEquals(10, outer.get("<init>(I)V"));
        assertEquals(11, outer.get("area()I"), "the call area(2) on the same line is not a declaration");
        assertEquals(12, outer.get("area(I)I"));
        assertEquals(15, outer.get("list(Ljava/lang/Object;Ljava/util/Map;)Ljava/util/List;"), "generic and nested commas");
        assertFalse(outer.containsKey("lambda$area$0()V"), "synthetic methods have no declaration");
        assertFalse(outer.containsKey("ghost()V"), "comments are ignored");
        assertEquals(16, lines.get("demo/Outer$Shape").get("area()D"), "abstract methods end with ;");
        assertEquals(18, lines.get("demo/Outer$Inner").get("area()I"), "a nested class's method is not the outer one");
        assertEquals(1, MethodLocator.parameters("(I)V"));
        assertEquals(3, MethodLocator.parameters("([[ILjava/lang/String;J)V"));
        assertEquals("Inner", MethodLocator.simpleName("demo/Outer$Inner"));
        assertEquals("", MethodLocator.simpleName("demo/Outer$1"));
        assertEquals("list(Object, Map)", Descriptors.pretty("demo/Outer", "list(Ljava/lang/Object;Ljava/util/Map;)Ljava/util/List;"));
        assertEquals("Outer(int[][])", Descriptors.pretty("demo/Outer", "<init>([[I)V"));
    }

    @Test void blankingKeepsOffsetsAndNewlines() {
        String text = "a /* x\ny */ \"s\\\"t\" 'c' // end\nb";
        String blank = MethodLocator.blank(text);
        assertEquals(text.length(), blank.length());
        assertEquals(text.indexOf('\n'), blank.indexOf('\n'));
        assertEquals('b', blank.charAt(blank.length() - 1));
        assertFalse(blank.contains("s") || blank.contains("end") || blank.contains("x"));
    }

    @Test void alignsTwoVersionsSideBySide() {
        var aligned = LineDiff.align("a\nb\nc\nd\n", "a\nB\nc\nd\ne\n");
        assertEquals(List.of(LineDiff.Kind.SAME, LineDiff.Kind.CHANGED, LineDiff.Kind.SAME, LineDiff.Kind.SAME, LineDiff.Kind.ADDED), aligned.kinds());
        assertEquals(Arrays.asList("a", "b", "c", "d", null), aligned.left());
        assertEquals(aligned.left().size(), aligned.right().size());
        var removed = LineDiff.align("x\ny\nz", "x\nz");
        assertEquals(List.of(LineDiff.Kind.SAME, LineDiff.Kind.REMOVED, LineDiff.Kind.SAME), removed.kinds());
        assertEquals(0, LineDiff.align("same  \n", "same\n").count(LineDiff.Kind.CHANGED), "trailing spaces do not count");
        assertEquals(List.of(), LineDiff.align("", "").kinds());
        assertEquals(3, LineDiff.align("", "a\nb\nc").count(LineDiff.Kind.ADDED));
    }

    @Test void theScriptIsMinimalOnAShiftedBlock() {
        var a = new ArrayList<String>();
        var b = new ArrayList<String>();
        for (int i = 0; i < 200; i++) a.add("line " + i);
        b.add("new first");
        b.addAll(a.subList(0, 150));
        b.addAll(a.subList(160, 200));
        char[] script = LineDiff.script(a, b);
        long edits = new String(script).chars().filter(c -> c != '=').count();
        assertEquals(11, edits, "one insertion and ten deletions");
    }
}
