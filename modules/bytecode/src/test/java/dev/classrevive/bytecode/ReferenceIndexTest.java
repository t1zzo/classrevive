package dev.classrevive.bytecode;

import dev.classrevive.bytecode.ReferenceIndex.Kind;
import dev.classrevive.bytecode.ReferenceIndex.Ref;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;
import java.util.zip.*;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Phase G6: the reference index over a compiled jar that holds each kind of reference (nothing from it is ever run). */
class ReferenceIndexTest {
    @TempDir Path dir;

    static final Map<String,String> SOURCES = Map.of(
        "app/Marker.java", "package app; import java.lang.annotation.*; @Retention(RetentionPolicy.RUNTIME) public @interface Marker {}",
        "app/Named.java", "package app; @Marker public interface Named { String name(); }",
        "app/AppError.java", "package app; public class AppError extends RuntimeException {}",
        "app/Base.java", """
            package app;
            public abstract class Base implements Named {
                protected int count;
                public abstract double area();
                public String name() { return "base"; }
                public static Base of(double r) { return new Circle(r); }
            }
            """,
        "app/Circle.java", """
            package app;
            public class Circle extends Base {
                final double radius;
                public Circle(double radius) { this.radius = radius; count++; }
                @Override public double area() { return Math.PI * radius * radius; }
            }
            """,
        "app/Use.java", """
            package app;
            import java.util.*;
            import java.util.function.*;
            public class Use {
                static List<Circle> circles = new ArrayList<>();
                @Marker Circle[] array() { return new Circle[2]; }
                double total(Base b) throws AppError {
                    if (b instanceof Circle) { Circle c = (Circle) b; return c.radius + c.area(); }
                    return b.area();
                }
                String label(Circle c) { return c.name(); }
                String named(Named n) { return n.name(); }
                Class<?> type() { return Circle.class; }
                void handle() { try { Base.of(1); } catch (AppError e) { } }
                Supplier<Circle> factory() { return () -> new Circle(3); }
                Function<Circle,Double> ref() { return Circle::area; }
                void write(Circle c) { c.count = 5; }
                int recurse(int n) { return n <= 0 ? 0 : again(n - 1); }
                int again(int n) { return recurse(n); }
            }
            """);

    private Path jar() throws Exception {
        var sources = Files.createDirectories(dir.resolve("src"));
        var classes = Files.createDirectories(dir.resolve("classes"));
        var args = new ArrayList<>(List.of("--release", "17", "-proc:none", "-d", classes.toString()));
        for (var source : SOURCES.entrySet()) {
            Path file = sources.resolve(source.getKey());
            Files.createDirectories(file.getParent());
            Files.writeString(file, source.getValue());
            args.add(file.toString());
        }
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, args.toArray(String[]::new)));
        var archive = dir.resolve("refs.jar");
        try (var zip = new ZipOutputStream(Files.newOutputStream(archive)); var walk = Files.walk(classes)) {
            for (Path file : walk.filter(Files::isRegularFile).sorted().toList()) {
                zip.putNextEntry(new ZipEntry(classes.relativize(file).toString().replace('\\', '/')));
                zip.write(Files.readAllBytes(file));
                zip.closeEntry();
            }
            zip.putNextEntry(new ZipEntry("META-INF/versions/11/app/Ignored.class"));
            zip.write(new byte[] {1, 2, 3});
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("app/Broken.class"));
            zip.write(new byte[] {(byte) 0xCA, (byte) 0xFE, 0, 0});
            zip.closeEntry();
        }
        return archive;
    }

    private static Set<Kind> kinds(List<ReferenceIndex.Usage> usages) {
        return usages.stream().map(usage -> usage.site().kind()).collect(Collectors.toCollection(() -> EnumSet.noneOf(Kind.class)));
    }

    private static Set<String> froms(List<ReferenceIndex.Usage> usages) {
        return usages.stream().map(usage -> usage.site().from().toString()).collect(Collectors.toCollection(TreeSet::new));
    }

    @Test void indexesEveryKindOfReference() throws Exception {
        var index = ReferenceIndex.ofJar(jar(), ReferenceIndex.Limits.defaults(), () -> false);
        assertEquals(new ReferenceIndex.Stats(6, 1, index.stats().sites(), false), index.stats(), "META-INF is skipped, the broken class counted");

        var circle = index.usages(Ref.ofClass("app/Circle"));
        assertEquals(EnumSet.of(Kind.SIGNATURE, Kind.NEW, Kind.NEW_ARRAY, Kind.CAST, Kind.INSTANCEOF, Kind.CLASS_LITERAL, Kind.CALL,
            Kind.FIELD_READ, Kind.FIELD_WRITE, Kind.METHOD_REFERENCE), kinds(circle));
        assertTrue(froms(circle).contains("app/Use.circles:Ljava/util/List;"), "a type argument of a generic signature");
        assertTrue(froms(circle).contains("app/Use.lambda$factory$0()Lapp/Circle;"), "new inside a lambda body");
        assertTrue(circle.stream().filter(usage -> usage.site().kind() == Kind.NEW).allMatch(usage -> usage.site().line() > 0), "lines from the LineNumberTable");

        assertEquals(EnumSet.of(Kind.EXTENDS), kinds(index.usages(Ref.ofClass("app/Base"))).stream().filter(kind -> kind != Kind.SIGNATURE && kind != Kind.CALL)
            .collect(Collectors.toCollection(() -> EnumSet.noneOf(Kind.class))));
        assertEquals(EnumSet.of(Kind.IMPLEMENTS, Kind.SIGNATURE, Kind.CALL), kinds(index.usages(Ref.ofClass("app/Named"))));
        assertEquals(Set.of("app/Named", "app/Use.array()[Lapp/Circle;"), froms(index.usages(Ref.ofClass("app/Marker"))));
        assertEquals(EnumSet.of(Kind.THROWS, Kind.CATCH), kinds(index.usages(Ref.ofClass("app/AppError"))));
    }

    @Test void membersResolveLikeTheJvmAndReportOverrides() throws Exception {
        var index = ReferenceIndex.ofJar(jar(), ReferenceIndex.Limits.defaults(), () -> false);
        // b.area() names Base.area; c.area() and Circle::area name Circle.area, which overrides it.
        var baseArea = index.usages(new Ref("app/Base", "area", "()D"));
        assertEquals(Set.of("app/Use.total(Lapp/Base;)D"), froms(baseArea));
        assertEquals(1, baseArea.size());
        var circleArea = index.usages(new Ref("app/Circle", "area", "()D"));
        assertEquals(EnumSet.of(Kind.CALL, Kind.METHOD_REFERENCE), kinds(circleArea));
        assertEquals(1, circleArea.stream().filter(usage -> usage.via() != null).count(), "the call through Base.area");
        assertEquals(new Ref("app/Base", "area", "()D"), circleArea.stream().filter(usage -> usage.via() != null).findFirst().orElseThrow().via());

        // c.name() names Circle, resolves up to Base.name; n.name() reaches Named.name, which Base.name implements.
        var name = index.usages(new Ref("app/Base", "name", "()Ljava/lang/String;"));
        assertEquals(Set.of("app/Use.label(Lapp/Circle;)Ljava/lang/String;", "app/Use.named(Lapp/Named;)Ljava/lang/String;"), froms(name));
        assertEquals(new Ref("app/Named", "name", "()Ljava/lang/String;"), name.stream().filter(usage -> usage.via() != null).findFirst().orElseThrow().via());
        assertEquals(Optional.of("app/Base"), index.resolve(new Ref("app/Circle", "name", "()Ljava/lang/String;")).map(ReferenceIndex.Member::owner));

        // c.count = 5 names Circle.count, declared in Base; count++ in the constructor reads and writes it.
        var count = index.usages(new Ref("app/Base", "count", "I"));
        assertEquals(EnumSet.of(Kind.FIELD_READ, Kind.FIELD_WRITE), kinds(count));
        assertEquals(Set.of("app/Circle.<init>(D)V", "app/Use.write(Lapp/Circle;)V"), froms(count));
        // radius is read twice in area(): ordinals 0 and 1.
        var radius = index.usages(new Ref("app/Circle", "radius", "D")).stream()
            .filter(usage -> usage.site().from().name().equals("area")).map(usage -> usage.site().ordinal()).toList();
        assertEquals(List.of(0, 1), radius);

        assertEquals(Set.of("app/Circle"), index.subtypes("app/Base"));
        assertEquals(List.of("app/Base", "java/lang/Object", "app/Named"), index.supertypes("app/Circle"));
        assertEquals(2, index.declared("app/Use", "recurse", true).size() + index.declared("app/Use", "again", true).size());
    }

    @Test void callHierarchyFollowsLambdasAndCutsCycles() throws Exception {
        var index = ReferenceIndex.ofJar(jar(), ReferenceIndex.Limits.defaults(), () -> false);
        var constructor = CallHierarchy.callers(index, new Ref("app/Circle", "<init>", "(D)V"), 3, 100);
        assertEquals(Set.of("app/Base.of(D)Lapp/Base;", "app/Use.lambda$factory$0()Lapp/Circle;"),
            constructor.callers().stream().map(node -> node.method().toString()).collect(Collectors.toSet()));
        var lambda = constructor.callers().stream().filter(node -> node.method().name().startsWith("lambda$")).findFirst().orElseThrow();
        assertEquals("app/Use.factory()Ljava/util/function/Supplier;", lambda.callers().getFirst().method().toString(), "the lambda's caller is its method");
        assertEquals(Kind.LAMBDA, lambda.callers().getFirst().sites().getFirst().site().kind());
        var of = constructor.callers().stream().filter(node -> node.method().name().equals("of")).findFirst().orElseThrow();
        assertEquals("app/Use.handle()V", of.callers().getFirst().method().toString());

        var recurse = CallHierarchy.callers(index, new Ref("app/Use", "recurse", "(I)I"), 10, 100);
        var again = recurse.callers().getFirst();
        assertEquals("again", again.method().name());
        var back = again.callers().getFirst();
        assertTrue(back.cycle(), "recurse is already on the path");
        assertTrue(back.callers().isEmpty());

        var shallow = CallHierarchy.callers(index, new Ref("app/Circle", "<init>", "(D)V"), 1, 100);
        assertTrue(shallow.callers().stream().allMatch(node -> node.cut() && node.callers().isEmpty()), "depth 1: callers exist but are not expanded");
        var budget = CallHierarchy.callers(index, new Ref("app/Circle", "<init>", "(D)V"), 3, 2);
        assertEquals(1, budget.callers().size());
        assertTrue(budget.cut());
    }

    @Test void limitsAndCancellationStopTheBuild() throws Exception {
        Path jar = jar();
        var fewSites = ReferenceIndex.ofJar(jar, new ReferenceIndex.Limits(100, 5, 1 << 20), () -> false);
        assertEquals(5, fewSites.stats().sites());
        assertTrue(fewSites.stats().truncated());
        var fewClasses = ReferenceIndex.ofJar(jar, new ReferenceIndex.Limits(2, 1000, 1 << 20), () -> false);
        assertEquals(2, fewClasses.stats().classes());
        assertTrue(fewClasses.stats().truncated());
        assertThrows(InterruptedException.class, () -> ReferenceIndex.ofJar(jar, ReferenceIndex.Limits.defaults(), () -> true));
    }
}
