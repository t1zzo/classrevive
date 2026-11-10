package dev.classrevive.transforms;

import org.objectweb.asm.*;
import org.objectweb.asm.commons.SimpleRemapper;
import org.objectweb.asm.tree.*;
import javax.tools.*;
import java.io.IOException;
import java.net.URI;
import java.nio.file.*;
import java.util.*;

/** Phase 2DK test corpus: readable sources compiled with javac, then scrambled the way ProGuard does, with the original names kept as the answer key. */
final class InferenceFixtures {
    /** Readable fixture sources, package {@code app}. */
    static final Map<String,String> SOURCES = new TreeMap<>(Map.of(
        "Player", """
            package app;
            import java.util.*;
            public class Player {
                private final String name;
                private int score;
                private final List<Item> items = new ArrayList<>();
                private Team team;
                public Player(String name, int score) { this.name = Objects.requireNonNull(name, "name"); this.score = score; }
                private String getName() { return name; }
                private int getScore() { return score; }
                private void setScore(int score) { this.score = score; }
                private void addItem(Item item) { items.add(item); }
                private void setTeam(Team team) { this.team = team; }
                public String describe() { return getName() + getScore() + team; }
                public void grant(Item item, int points) { addItem(item); setScore(getScore() + points); }
                public void join(Team squad) { setTeam(squad); }
                @Override public String toString() { return "Player{name=" + name + ", score=" + score + ", items=" + items + "}"; }
            }
            """,
        "Item", """
            package app;
            import java.util.*;
            public class Item {
                private final String label;
                private double price;
                private int quantity;
                public Item(String label, double price, int quantity) { this.label = label; this.price = price; this.quantity = quantity; }
                private double getPrice() { return price; }
                private boolean isEmpty() { return quantity == 0; }
                public void export(Map<String,Object> json) { json.put("label", label); json.put("unit_price", price); json.put("quantity", quantity); }
                public String check() { return isEmpty() ? "empty" : "price " + getPrice(); }
                @Override public String toString() { return "Item(label=" + label + ")"; }
            }
            """,
        "Team", """
            package app;
            import java.util.*;
            public class Team {
                private final List<Player> members = new ArrayList<>();
                private final Map<String,Player> byName = new HashMap<>();
                private String title;
                private void addMember(Player player) { members.add(player); }
                private String getTitle() { return title; }
                public void enroll(Player player) { addMember(player); byName.put("x", player); }
                public String label() { return getTitle(); }
                @Override public String toString() { return "Team[title=" + title + "]"; }
            }
            """,
        "Config", """
            package app;
            import java.util.*;
            public class Config {
                private final int port;
                private final String host;
                private final boolean debug;
                public Config(Properties props) {
                    this.port = Integer.parseInt(props.getProperty("server_port"));
                    this.host = props.getProperty("host");
                    this.debug = Boolean.parseBoolean(props.getProperty("debug"));
                }
                private boolean isDebug() { return debug; }
                public String summary() { return host + ":" + port + isDebug(); }
            }
            """,
        "Job", """
            package app;
            public class Job implements Runnable {
                private int counter;
                private long started;
                private void tick() { counter++; }
                @Override public void run() { started = System.nanoTime(); tick(); }
            }
            """,
        "AppException", """
            package app;
            public class AppException extends Exception {
                private final int code;
                public AppException(String message, int code) { super(message); this.code = code; }
                private int getCode() { return code; }
                public int code() { return getCode(); }
            }
            """,
        "Main", """
            package app;
            public class Main {
                public static void main(String[] args) throws Exception {
                    Player player = new Player("x", 1);
                    Team team = new Team();
                    team.enroll(player);
                    System.out.println(player.describe() + team.label());
                }
            }
            """));

    /** An obfuscated class set plus the answer key (original names). */
    record Scrambled(Map<String,byte[]> classes, Map<String,String> classKey, Map<String,String> memberKey) {}

    /** Compiles {@link #SOURCES} with debug information. */
    static Map<String,byte[]> compile(Path directory) throws IOException {
        var compiler = ToolProvider.getSystemJavaCompiler();
        Path sources = Files.createDirectories(directory.resolve("src")), out = Files.createDirectories(directory.resolve("out"));
        var files = new ArrayList<Path>();
        for (var source : SOURCES.entrySet()) {
            Path file = Files.createDirectories(sources.resolve("app")).resolve(source.getKey() + ".java");
            Files.writeString(file, source.getValue()); files.add(file);
        }
        var options = new ArrayList<>(List.of("-g", "--release", "17", "-d", out.toString()));
        files.forEach(file -> options.add(file.toString()));
        int status = compiler.run(null, null, null, options.toArray(String[]::new));
        if (status != 0) throw new IllegalStateException("fixture sources do not compile");
        var classes = new TreeMap<String,byte[]>();
        try (var walk = Files.walk(out)) {
            for (Path path : walk.filter(p -> p.toString().endsWith(".class")).toList())
                classes.put(out.relativize(path).toString().replace('\\', '/').replaceAll("\\.class$", ""), Files.readAllBytes(path));
        }
        return classes;
    }

    /**
     * ProGuard-style scrambling: every class but {@code app/Main} becomes {@code app/a}, {@code app/b}..., every private field
     * then private method becomes {@code a}, {@code b}... in its class. {@code sourceFile}: keep the SourceFile attribute;
     * {@code localNames}: keep the LocalVariableTable (ProGuard drops it by default).
     */
    static Scrambled scramble(Map<String,byte[]> readable, boolean sourceFile, boolean localNames) {
        var nodes = new TreeMap<String,ClassNode>();
        readable.forEach((name, bytes) -> { var node = new ClassNode(); new ClassReader(bytes).accept(node, 0); nodes.put(name, node); });
        var classMap = new TreeMap<String,String>(); var classKey = new TreeMap<String,String>();
        int counter = 0;
        for (var name : nodes.keySet()) {
            if (name.equals("app/Main")) continue;
            String target = "app/" + (char) ('a' + counter++);
            classMap.put(name, target); classKey.put(target, name);
        }
        var remapper = new SimpleRemapper(classMap);
        var rules = new ArrayList<ClassRenamer.MemberRule>(); var memberKey = new TreeMap<String,String>();
        for (var node : nodes.values()) {
            if (!sourceFile) node.sourceFile = "SourceFile";
            for (var method : node.methods) if (!localNames) { method.localVariables = null; method.parameters = null; }
            if (!localNames) for (var method : node.methods) { method.localVariables = null; }
            if (node.name.equals("app/Main")) continue;
            int next = 0;
            var order = new ArrayList<String>();
            for (var field : node.fields) if ((field.access & Opcodes.ACC_PRIVATE) != 0) {
                String target = String.valueOf((char) ('a' + next++));
                rules.add(new ClassRenamer.MemberRule(node.name, field.name, field.desc, true, target));
                memberKey.put("field " + classMap.get(node.name) + "#" + target + ":" + remapper.mapDesc(field.desc), field.name);
            }
            for (var method : node.methods) if ((method.access & Opcodes.ACC_PRIVATE) != 0 && !method.name.startsWith("<")) {
                String target = String.valueOf((char) ('a' + next++));
                rules.add(new ClassRenamer.MemberRule(node.name, method.name, method.desc, false, target));
                memberKey.put("method " + classMap.get(node.name) + "#" + target + remapper.mapMethodDesc(method.desc), method.name);
            }
        }
        var input = new TreeMap<String,byte[]>();
        for (var node : nodes.values()) {
            var writer = new ClassWriter(0); node.accept(writer); input.put(node.name, writer.toByteArray());
        }
        var result = new ClassRenamer().rename(input, classMap, rules, false);
        return new Scrambled(result.classes(), classKey, memberKey);
    }

    /** Defines every class in an isolated loader and links it (getDeclaredMethods/Fields); no class is initialized, so no fixture code runs. */
    static void link(Map<String,byte[]> classes) throws Exception {
        var loader = new ClassLoader(ClassLoader.getPlatformClassLoader()) {
            @Override protected Class<?> findClass(String name) throws ClassNotFoundException {
                byte[] bytes = classes.get(name.replace('.', '/'));
                if (bytes == null) throw new ClassNotFoundException(name);
                return defineClass(name, bytes, 0, bytes.length);
            }
        };
        for (String name : classes.keySet()) {
            Class<?> type = Class.forName(name.replace('/', '.'), false, loader);
            type.getDeclaredMethods(); type.getDeclaredFields(); type.getDeclaredConstructors();
        }
    }
}
