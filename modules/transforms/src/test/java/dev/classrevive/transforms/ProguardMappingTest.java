package dev.classrevive.transforms;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ProguardMappingTest {
    private static final int PRIVATE = 0x0002, PUBLIC = 0x0001;

    private static ProguardMapping.Parsed parse(String text) { return ProguardMapping.parse(text.getBytes(StandardCharsets.UTF_8)); }
    private static ProguardMapping.Member member(String name, String descriptor, int access) { return new ProguardMapping.Member(name, descriptor, access); }
    private static ProguardMapping.ClassShape shape(List<ProguardMapping.Member> fields, List<ProguardMapping.Member> methods) {
        return new ProguardMapping.ClassShape(PUBLIC, fields, methods);
    }

    @Test void parsesClassFieldMethodRangesCommentsAndInlineFrames() {
        var parsed = parse("""
            # compiler: R8
            # {"id":"com.android.tools.r8.mapping","version":"2.2"}
            com.example.Service -> com.example.a:
            # {"id":"sourceFile","fileName":"Service.java"}
                int retries -> a
                java.lang.String[] names -> b
                1:4:void connect(java.lang.String,int):10:13 -> a
                5:5:void com.example.Util.log(java.lang.String):40:40 -> b
                void <init>() -> <init>
            """);
        var entry = parsed.classes().getFirst();
        assertEquals("com/example/Service", entry.original()); assertEquals("com/example/a", entry.obfuscated());
        assertEquals(5, entry.members().size());
        assertEquals(new ProguardMapping.MemberLine("void", "connect", "java.lang.String,int", false, "a"), entry.members().get(2));
        assertTrue(entry.members().get(1).field());
    }

    @Test void rejectsMalformedAndDuplicateInput() {
        assertThrows(IllegalArgumentException.class, () -> parse("    int x -> a\n"));
        assertThrows(IllegalArgumentException.class, () -> parse("com.example.A -> a\n"));
        assertThrows(IllegalArgumentException.class, () -> parse("a.A -> a.a:\n    nonsense\n"));
        assertThrows(IllegalArgumentException.class, () -> parse("a.A -> a.a:\na.B -> a.a:\n"));
        assertThrows(IllegalArgumentException.class, () -> parse("# only comments\n"));
        assertThrows(IllegalArgumentException.class, () -> ProguardMapping.parse(new byte[ProguardMapping.MAX_BYTES + 1]));
    }

    @Test void convertsSamePackageClassesAndPrivateMembersUsingObfuscatedDescriptors() {
        var parsed = parse("""
            com.example.Service -> com.example.a:
                int retries -> a
                com.example.Config config -> b
                void connect(com.example.Config,int) -> a
                void open() -> b
            com.example.Config -> com.example.b:
            com.example.Moved -> z.c:
            """);
        var input = new TreeMap<String,ProguardMapping.ClassShape>();
        input.put("com/example/a", shape(
            List.of(member("a", "I", PRIVATE), member("b", "Lcom/example/b;", PRIVATE)),
            List.of(member("a", "(Lcom/example/b;I)V", PRIVATE), member("b", "()V", PUBLIC))));
        input.put("com/example/b", shape(List.of(), List.of()));
        input.put("z/c", shape(List.of(), List.of()));
        var conversion = ProguardMapping.convert(parsed, input);
        assertEquals(Map.of("com/example/a", "com/example/Service", "com/example/b", "com/example/Config"), conversion.plan().classes());
        var targets = new TreeMap<String,String>();
        for (var rule : conversion.plan().members()) targets.put(rule.owner() + "#" + rule.name() + rule.descriptor(), rule.target());
        assertEquals(Map.of("com/example/a#aI", "retries", "com/example/a#bLcom/example/b;", "config",
            "com/example/a#a(Lcom/example/b;I)V", "connect"), targets);
        assertEquals(1, conversion.skipped().get(ProguardMapping.Reason.CROSS_PACKAGE));
        assertEquals(1, conversion.skipped().get(ProguardMapping.Reason.NOT_PRIVATE));
    }

    @Test void formattedPlanRoundTripsThroughTheExplicitMappingParser() {
        var parsed = parse("p.Original -> p.a:\n    int count -> a\n    void run(java.lang.String[]) -> b\n");
        var input = Map.of("p/a", shape(List.of(member("a", "I", PRIVATE)), List.of(member("b", "([Ljava/lang/String;)V", PRIVATE))));
        var plan = ProguardMapping.convert(parsed, input).plan();
        var reparsed = MappingFile.parse(ProguardMapping.format(plan).getBytes(StandardCharsets.UTF_8));
        assertEquals(plan, reparsed);
    }

    @Test void skipsSerializationMembersMissingSignaturesAndRecordFields() {
        var parsed = parse("""
            p.Data -> p.a:
                long serialVersionUID -> a
                void readObject(java.io.ObjectInputStream) -> b
                void changed(int) -> c
            p.Point -> p.b:
                int x -> a
            """);
        var input = new TreeMap<String,ProguardMapping.ClassShape>();
        input.put("p/a", shape(List.of(member("a", "J", PRIVATE)),
            List.of(member("b", "(Ljava/io/ObjectInputStream;)V", PRIVATE), member("c", "(J)V", PRIVATE))));
        input.put("p/b", new ProguardMapping.ClassShape(0x10000 | PUBLIC, List.of(member("a", "I", PRIVATE)), List.of()));
        var conversion = ProguardMapping.convert(parsed, input);
        assertTrue(conversion.plan().members().isEmpty());
        assertEquals(2, conversion.skipped().get(ProguardMapping.Reason.SERIALIZATION_MEMBER));
        assertEquals(1, conversion.skipped().get(ProguardMapping.Reason.MEMBER_NOT_FOUND));
        assertEquals(1, conversion.skipped().get(ProguardMapping.Reason.RECORD_FIELD));
    }

    @Test void unqualifiedInlineFrameMakesTheKeyAmbiguousInsteadOfRenamingIt() {
        // An inlined same-class frame shares obfuscated name and descriptor with a method kept under its own name.
        var parsed = parse("""
            p.Worker -> p.a:
                1:1:void helper():5:5 -> a
                1:1:void a():9 -> a
                2:2:void first():3:3 -> b
                2:2:void second():7:7 -> b
            """);
        var input = Map.of("p/a", shape(List.of(), List.of(member("a", "()V", PRIVATE), member("b", "()V", PRIVATE))));
        var conversion = ProguardMapping.convert(parsed, input);
        assertTrue(conversion.plan().members().isEmpty());
        assertEquals(2, conversion.skipped().get(ProguardMapping.Reason.AMBIGUOUS));
    }

    @Test void dropsIncoherentNestedAndCollidingRules() {
        var parsed = parse("""
            p.Outer -> p.a:
            p.Outer$Inner -> q.b:
            p.Taken -> p.c:
            """);
        var input = new TreeMap<String,ProguardMapping.ClassShape>();
        for (String name : List.of("p/a", "p/a$b", "p/c", "p/Taken")) input.put(name, shape(List.of(), List.of()));
        var parsedNested = parse("p.Outer -> p.a:\np.Outer$Inner -> p.x:\n");
        var nested = ProguardMapping.convert(parsedNested, Map.of("p/a", shape(List.of(), List.of()), "p/a$b", shape(List.of(), List.of())));
        assertTrue(nested.plan().classes().isEmpty());
        assertEquals(1, nested.skipped().get(ProguardMapping.Reason.NESTED_INCOHERENT));
        var colliding = ProguardMapping.convert(parsed, input);
        assertFalse(colliding.plan().classes().containsKey("p/c"));
        assertEquals(1, colliding.skipped().get(ProguardMapping.Reason.OUTPUT_COLLISION));
    }

    @Test void convertsPrimitiveArrayAndUnmappedLibraryTypes() {
        var classes = Map.of("p/Original", "p/a");
        assertEquals("[[I", ProguardMapping.descriptor("int[][]", classes));
        assertEquals("Lp/a;", ProguardMapping.descriptor("p.Original", classes));
        assertEquals("Ljava/util/List;", ProguardMapping.descriptor("java.util.List", classes));
        assertEquals("(JLp/a;)Z", ProguardMapping.methodDescriptor("long,p.Original", "boolean", classes));
        assertEquals("()V", ProguardMapping.methodDescriptor("", "void", classes));
    }
}
