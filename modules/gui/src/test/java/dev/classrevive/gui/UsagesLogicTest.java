package dev.classrevive.gui;

import dev.classrevive.bytecode.ClassText;
import dev.classrevive.bytecode.ReferenceIndex;
import dev.classrevive.bytecode.ReferenceIndex.Ref;
import java.nio.file.*;
import java.util.*;
import java.util.function.UnaryOperator;
import java.util.zip.*;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase G6: resolving the name under the caret and finding a usage site in text, without a window. The authored source
 * stands in for decompiled text; the bytecode text is ASM's, as the editor shows it.
 */
class UsagesLogicTest {
    @TempDir static Path directory;
    static ReferenceIndex index;
    static Map<String,byte[]> classes = new HashMap<>();

    static final String SHOP = """
        package demo;
        import java.util.function.Supplier;
        public class Shop {
            private int stock;
            public Shop(int stock) { this.stock = stock; }
            public int stock() { return stock; }
            public void add(int n) { stock += n; }
            public void add(int n, int times) { for (int i = 0; i < times; i++) add(n); }
            public Supplier<Shop> copy() {
                return () -> new Shop(stock());
            }
            public static Shop open() {
                // add(9) in a comment is no call
                Shop shop = new Shop(1);
                shop.add(2);
                shop.add(3, 4);
                return shop;
            }
        }
        """;
    static final String OTHER = "package demo; public class Other { int add(int x) { return x; } }";

    @BeforeAll static void compile() throws Exception {
        var sources = Files.createDirectories(directory.resolve("src/demo"));
        Files.writeString(sources.resolve("Shop.java"), SHOP);
        Files.writeString(sources.resolve("Other.java"), OTHER);
        var out = Files.createDirectories(directory.resolve("classes"));
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "--release", "17", "-proc:none", "-d", out.toString(),
            sources.resolve("Shop.java").toString(), sources.resolve("Other.java").toString()));
        var jar = directory.resolve("shop.jar");
        try (var zip = new ZipOutputStream(Files.newOutputStream(jar)); var walk = Files.walk(out)) {
            for (Path file : walk.filter(Files::isRegularFile).toList()) {
                String name = out.relativize(file).toString().replace('\\', '/');
                classes.put(name, Files.readAllBytes(file));
                zip.putNextEntry(new ZipEntry(name));
                zip.write(classes.get(name));
                zip.closeEntry();
            }
        }
        index = ReferenceIndex.ofJar(jar, ReferenceIndex.Limits.defaults(), () -> false);
    }

    private static final UnaryOperator<String> CLASSES = simple -> switch (simple) { case "Shop" -> "demo/Shop"; case "Other" -> "demo/Other"; default -> null; };

    private static List<Ref> at(String marker, int delta) {
        int offset = SHOP.indexOf(marker);
        assertTrue(offset >= 0, marker);
        var query = MemberAtCaret.at(SHOP, offset + delta);
        return MemberAtCaret.candidates(query, index, "demo/Shop", List.of("demo/Shop"), CLASSES);
    }

    @Test void resolvesTheNameUnderTheCaretWithoutGuessing() {
        // shop.add(2): the receiver is a variable, so every add with one argument in the input is a candidate.
        assertEquals(Set.of(new Ref("demo/Shop", "add", "(I)V"), new Ref("demo/Other", "add", "(I)I")), Set.copyOf(at("shop.add(2)", 6)));
        // add(n) without a qualifier: this file's class, narrowed to the one-argument overload.
        assertEquals(List.of(new Ref("demo/Shop", "add", "(I)V")), at("add(n); }", 1));
        // The declaration of the two-argument overload.
        assertEquals(List.of(new Ref("demo/Shop", "add", "(II)V")), at("add(int n, int times)", 1));
        // new Shop(1): the constructor with one argument; Shop alone: the class.
        assertEquals(List.of(new Ref("demo/Shop", "<init>", "(I)V")), at("new Shop(1)", 5));
        assertEquals(List.of(Ref.ofClass("demo/Shop")), at("Shop shop", 1));
        // this.stock: the field; stock(): the method.
        assertEquals(List.of(new Ref("demo/Shop", "stock", "I")), at("this.stock", 6));
        assertEquals(List.of(new Ref("demo/Shop", "stock", "()I")), at("stock());", 1));
        // A JDK class, a keyword, a comment: nothing.
        assertEquals(List.of(), at("Supplier<Shop> copy", 2));
        assertNull(MemberAtCaret.at(SHOP, SHOP.indexOf("return shop") + 2));
        assertNull(MemberAtCaret.at(SHOP, SHOP.indexOf("add(9)") + 1));
    }

    @Test void bytecodeLinesNameTheirTargetExactly() {
        UnaryOperator<String> same = UnaryOperator.identity();
        assertEquals(new Ref("demo/Shop", "add", "(I)V"), MemberAtCaret.inBytecode("    INVOKEVIRTUAL demo/Shop.add (I)V", "demo/Shop", same));
        assertEquals(new Ref("demo/Shop", "stock", "I"), MemberAtCaret.inBytecode("    GETFIELD demo/Shop.stock : I", "demo/Shop", same));
        assertEquals(Ref.ofClass("demo/Shop"), MemberAtCaret.inBytecode("    NEW demo/Shop", "demo/Shop", same));
        assertEquals(new Ref("demo/Shop", "add", "(II)V"), MemberAtCaret.inBytecode("  public add(II)V", "demo/Shop", same));
        assertEquals(new Ref("demo/Shop", "stock", "I"), MemberAtCaret.inBytecode("  private I stock", "demo/Shop", same));
        assertEquals(new Ref("p/New", "m", "(Lp/New;)V"), MemberAtCaret.inBytecode("    INVOKESTATIC p/Old.m (Lp/Old;)V", "p/Old", name -> name.replace("Old", "New")));
        assertNull(MemberAtCaret.inBytecode("    ILOAD 1", "demo/Shop", same));
    }

    private static int lineOf(String text, String marker) {
        int offset = text.indexOf(marker);
        assertTrue(offset >= 0, marker);
        return (int) text.substring(0, offset).chars().filter(c -> c == '\n').count() + 1;
    }

    @Test void findsSitesInSourceAndBytecode() throws Exception {
        var calls = index.usages(new Ref("demo/Shop", "add", "(I)V"));
        assertEquals(2, calls.size());
        var inOpen = calls.stream().filter(usage -> usage.site().from().name().equals("open")).findFirst().orElseThrow().site();
        var inOverload = calls.stream().filter(usage -> usage.site().from().name().equals("add")).findFirst().orElseThrow().site();
        assertEquals(lineOf(SHOP, "shop.add(2)"), UsageLocator.source(SHOP, inOpen, index), "the comment's add(9) is skipped");
        assertEquals(lineOf(SHOP, "add(int n, int times)"), UsageLocator.source(SHOP, inOverload, index));
        assertEquals(inOpen.line(), lineOf(SHOP, "shop.add(2)"), "javac's LineNumberTable matches the authored source");

        // The constructor called inside a lambda: found inside the method that wrote the lambda.
        var created = index.usages(new Ref("demo/Shop", "<init>", "(I)V"));
        var inLambda = created.stream().filter(usage -> usage.site().from().name().startsWith("lambda$copy$")).findFirst().orElseThrow().site();
        assertEquals(ReferenceIndex.Kind.CALL, inLambda.kind());
        assertEquals(lineOf(SHOP, "() -> new Shop(stock())"), UsageLocator.source(SHOP, inLambda, index));
        Text.use(GuiSettings.Language.ENGLISH);
        assertEquals("lambda in copy", UsagesPanel.member(inLambda.from(), UnaryOperator.identity()));

        // ASM's text: the instruction inside the right method.
        String text = ClassText.disassemble(classes.get("demo/Shop.class"), 1_000_000);
        int line = UsageLocator.bytecode(text, inOpen, UnaryOperator.identity());
        assertTrue(text.split("\n")[line - 1].contains("INVOKEVIRTUAL demo/Shop.add (I)V"), text.split("\n")[line - 1]);
        int header = UsageLocator.bytecode(text, new ReferenceIndex.Site(new Ref("demo/Shop", "open", "()Ldemo/Shop;"), ReferenceIndex.Kind.CALL,
            new Ref("x/Missing", "m", "()V"), -1, 0), UnaryOperator.identity());
        assertTrue(text.split("\n")[header - 1].endsWith("open()Ldemo/Shop;"), "not found: the method's header");
    }

    @Test void labelsReadLikeJava() {
        Text.use(GuiSettings.Language.ENGLISH);
        UnaryOperator<String> same = UnaryOperator.identity();
        assertEquals("Shop.add(int, int)", UsagesPanel.describe(new Ref("demo/Shop", "add", "(II)V"), same));
        assertEquals("Shop.Shop(int)", UsagesPanel.describe(new Ref("demo/Shop", "<init>", "(I)V"), same));
        assertEquals("demo.Shop", UsagesPanel.describe(Ref.ofClass("demo/Shop"), same));
        var usage = new ReferenceIndex.Usage(new ReferenceIndex.Site(new Ref("demo/Shop", "open", "()Ldemo/Shop;"), ReferenceIndex.Kind.CALL,
            new Ref("demo/Shop", "add", "(I)V"), 14, 0), new Ref("demo/Base", "add", "(I)V"));
        assertEquals("call · Shop.add(int) (through Base.add(int)) · line 14", UsagesPanel.site(usage, same));
        for (var kind : ReferenceIndex.Kind.values()) {
            Text.use(GuiSettings.Language.PORTUGUESE);
            assertNotEquals("usages.kind." + kind.name(), Text.t("usages.kind." + kind.name()));
            Text.use(GuiSettings.Language.ENGLISH);
            assertNotEquals("usages.kind." + kind.name(), Text.t("usages.kind." + kind.name()));
        }
    }
}
