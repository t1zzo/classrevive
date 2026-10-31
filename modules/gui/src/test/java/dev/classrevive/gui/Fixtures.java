package dev.classrevive.gui;

import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import javax.tools.ToolProvider;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** Compiles small authored sources into a jar for the GUI tests (nothing from the jar is ever run). */
final class Fixtures {
    private Fixtures() {}

    static final Map<String,String> SOURCES = Map.of(
        "demo/shapes/Shape.java", "package demo.shapes; public interface Shape { double area(); }",
        "demo/shapes/Circle.java", """
            package demo.shapes;
            public class Circle implements Shape {
                private final double radius;
                public Circle(double radius) { this.radius = radius; }
                public double area() { return Math.PI * radius * radius; }
                public static class Builder { private double r; public Builder radius(double r) { this.r = r; return this; } public Circle build() { return new Circle(r); } }
            }
            """,
        "demo/shapes/Color.java", "package demo.shapes; public enum Color { RED, GREEN; public Color next() { return values()[(ordinal() + 1) % 2]; } }",
        "demo/Main.java", """
            package demo;
            import demo.shapes.*;
            public class Main {
                public static String describe(Shape shape) { return "area=" + shape.area(); }
                public int sum(int[] values) { int total = 0; for (int value : values) total += value; return total; }
            }
            """);

    /** A jar with the classes above plus a text resource and a binary one. */
    static Path jar(Path directory) throws Exception {
        var sources = Files.createDirectories(directory.resolve("fixture-src"));
        var classes = Files.createDirectories(directory.resolve("fixture-classes"));
        var args = new ArrayList<>(List.of("--release", "8", "-proc:none", "-d", classes.toString()));
        for (var source : SOURCES.entrySet()) {
            Path file = sources.resolve(source.getKey());
            Files.createDirectories(file.getParent());
            Files.writeString(file, source.getValue());
            args.add(file.toString());
        }
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, args.toArray(String[]::new)));
        var archive = directory.resolve("demo-app.jar");
        try (var zip = new ZipOutputStream(Files.newOutputStream(archive)); var walk = Files.walk(classes)) {
            for (Path file : walk.filter(Files::isRegularFile).sorted().toList()) {
                zip.putNextEntry(new ZipEntry(classes.relativize(file).toString().replace('\\', '/')));
                zip.write(Files.readAllBytes(file));
                zip.closeEntry();
            }
            zip.putNextEntry(new ZipEntry("config/app.properties"));
            zip.write("name=demo\nversion=1\n".getBytes());
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("assets/logo.bin"));
            zip.write(new byte[] {0, 1, 2, 3, 0});
            zip.closeEntry();
        }
        return archive;
    }
}
