package dev.classrevive.pipeline;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.*;
import java.util.zip.ZipFile;

/**
 * Phase 2CI: third-party libraries bundled inside an input JAR (fat JARs, shaded copies), so reports can tell the
 * application's own code from library code. Three kinds of evidence, strongest first:
 * <ol>
 * <li>Maven metadata the build left in the JAR: {@code META-INF/maven/<group>/<artifact>/pom.properties}, with the version;</li>
 * <li>a well-known library package at the root of a class name ({@code com/google/common/...} is Guava);</li>
 * <li>the same package relocated under another prefix ({@code app/shaded/com/google/gson/...}), as shading tools do.</li>
 * </ol>
 * Renamed or obfuscated copies of a library are not recognized. When no class is left outside the recognized
 * libraries, the largest one is the input itself (a library decompiled on its own) and is not reported as embedded.
 * Nothing is loaded or executed; Maven metadata is read as bounded text.
 */
final class EmbeddedLibraries {
    private EmbeddedLibraries() {}

    /** One recognized library: its packages (internal names, ending in '/'), classes, evidence and declared version. */
    record Library(String name, String coordinates, String version, SortedSet<String> packages, int classes,
                   SortedSet<String> evidence, SortedSet<String> relocatedUnder) {}
    /** {@code self}: the library the input itself is (all its classes belong to it), or null. */
    record Result(List<Library> libraries, int applicationClasses, int libraryClasses, String self, Map<String,String> classLibrary) {
        String libraryOf(String internalName) { return classLibrary.get(internalName); }
    }

    /** Curated: package prefix to library name and Maven coordinates (group:artifact), most specific first where they nest. */
    static final List<String[]> CATALOG = List.of(
        new String[]{"com/google/common/", "Guava", "com.google.guava:guava"},
        new String[]{"com/google/thirdparty/publicsuffix/", "Guava", "com.google.guava:guava"},
        new String[]{"com/google/gson/", "Gson", "com.google.code.gson:gson"},
        new String[]{"com/google/protobuf/", "Protocol Buffers", "com.google.protobuf:protobuf-java"},
        new String[]{"com/google/errorprone/annotations/", "Error Prone annotations", "com.google.errorprone:error_prone_annotations"},
        new String[]{"com/google/j2objc/annotations/", "J2ObjC annotations", "com.google.j2objc:j2objc-annotations"},
        new String[]{"javax/annotation/", "JSR 305 / javax.annotation", "com.google.code.findbugs:jsr305"},
        new String[]{"org/checkerframework/", "Checker Framework", "org.checkerframework:checker-qual"},
        new String[]{"org/jetbrains/annotations/", "JetBrains annotations", "org.jetbrains:annotations"},
        new String[]{"org/apache/commons/lang3/", "Apache Commons Lang", "org.apache.commons:commons-lang3"},
        new String[]{"org/apache/commons/lang/", "Apache Commons Lang 2", "commons-lang:commons-lang"},
        new String[]{"org/apache/commons/io/", "Apache Commons IO", "commons-io:commons-io"},
        new String[]{"org/apache/commons/codec/", "Apache Commons Codec", "commons-codec:commons-codec"},
        new String[]{"org/apache/commons/compress/", "Apache Commons Compress", "org.apache.commons:commons-compress"},
        new String[]{"org/apache/commons/text/", "Apache Commons Text", "org.apache.commons:commons-text"},
        new String[]{"org/apache/commons/collections4/", "Apache Commons Collections", "org.apache.commons:commons-collections4"},
        new String[]{"org/apache/commons/logging/", "Apache Commons Logging", "commons-logging:commons-logging"},
        new String[]{"org/apache/http/", "Apache HttpClient", "org.apache.httpcomponents:httpclient"},
        new String[]{"org/apache/logging/log4j/", "Log4j 2", "org.apache.logging.log4j:log4j-api"},
        new String[]{"org/apache/log4j/", "Log4j 1", "log4j:log4j"},
        new String[]{"org/slf4j/", "SLF4J", "org.slf4j:slf4j-api"},
        new String[]{"ch/qos/logback/", "Logback", "ch.qos.logback:logback-classic"},
        new String[]{"com/fasterxml/jackson/core/", "Jackson core", "com.fasterxml.jackson.core:jackson-core"},
        new String[]{"com/fasterxml/jackson/databind/", "Jackson databind", "com.fasterxml.jackson.core:jackson-databind"},
        new String[]{"com/fasterxml/jackson/annotation/", "Jackson annotations", "com.fasterxml.jackson.core:jackson-annotations"},
        new String[]{"org/json/", "JSON-java", "org.json:json"},
        new String[]{"org/yaml/snakeyaml/", "SnakeYAML", "org.yaml:snakeyaml"},
        new String[]{"io/netty/", "Netty", "io.netty:netty-all"},
        new String[]{"okhttp3/", "OkHttp", "com.squareup.okhttp3:okhttp"},
        new String[]{"okio/", "Okio", "com.squareup.okio:okio"},
        new String[]{"retrofit2/", "Retrofit", "com.squareup.retrofit2:retrofit"},
        new String[]{"kotlin/", "Kotlin standard library", "org.jetbrains.kotlin:kotlin-stdlib"},
        new String[]{"kotlinx/coroutines/", "kotlinx.coroutines", "org.jetbrains.kotlinx:kotlinx-coroutines-core"},
        new String[]{"scala/", "Scala library", "org.scala-lang:scala-library"},
        new String[]{"groovy/", "Groovy", "org.apache.groovy:groovy"},
        new String[]{"org/codehaus/groovy/", "Groovy", "org.apache.groovy:groovy"},
        new String[]{"org/objectweb/asm/", "ASM", "org.ow2.asm:asm"},
        new String[]{"net/bytebuddy/", "Byte Buddy", "net.bytebuddy:byte-buddy"},
        new String[]{"javassist/", "Javassist", "org.javassist:javassist"},
        new String[]{"org/antlr/v4/runtime/", "ANTLR 4 runtime", "org.antlr:antlr4-runtime"},
        new String[]{"org/jsoup/", "jsoup", "org.jsoup:jsoup"},
        new String[]{"picocli/", "picocli", "info.picocli:picocli"},
        new String[]{"joptsimple/", "jopt-simple", "net.sf.jopt-simple:jopt-simple"},
        new String[]{"org/joda/time/", "Joda-Time", "joda-time:joda-time"},
        new String[]{"com/github/benmanes/caffeine/", "Caffeine", "com.github.ben-manes.caffeine:caffeine"},
        new String[]{"it/unimi/dsi/fastutil/", "fastutil", "it.unimi.dsi:fastutil"},
        new String[]{"org/lwjgl/", "LWJGL", "org.lwjgl:lwjgl"},
        new String[]{"com/mojang/brigadier/", "Brigadier", "com.mojang:brigadier"},
        new String[]{"org/spongepowered/asm/", "Mixin", "org.spongepowered:mixin"},
        new String[]{"org/springframework/", "Spring Framework", "org.springframework:spring-core"},
        new String[]{"org/hibernate/", "Hibernate", "org.hibernate:hibernate-core"},
        new String[]{"org/junit/", "JUnit", "org.junit:junit-bom"},
        new String[]{"junit/", "JUnit 3/4", "junit:junit"},
        new String[]{"org/hamcrest/", "Hamcrest", "org.hamcrest:hamcrest"},
        new String[]{"org/mockito/", "Mockito", "org.mockito:mockito-core"},
        new String[]{"org/bouncycastle/", "Bouncy Castle", "org.bouncycastle:bcprov-jdk18on"},
        new String[]{"com/zaxxer/hikari/", "HikariCP", "com.zaxxer:HikariCP"},
        new String[]{"org/eclipse/jetty/", "Jetty", "org.eclipse.jetty:jetty-server"},
        new String[]{"org/xerial/snappy/", "snappy-java", "org.xerial.snappy:snappy-java"},
        new String[]{"org/tukaani/xz/", "XZ for Java", "org.tukaani:xz"},
        new String[]{"com/github/luben/zstd/", "zstd-jni", "com.github.luben:zstd-jni"});

    static final int MAX_POMS = 500, MAX_POM_BYTES = 64 * 1024;

    static Result detect(Path snapshot, Collection<String> classNames) throws IOException {
        var declared = new TreeMap<String,String>(); // coordinates -> version
        if (snapshot.getFileName().toString().endsWith(".jar")) {
            try (var zip = new ZipFile(snapshot.toFile())) {
                var entries = zip.entries(); int poms = 0;
                while (entries.hasMoreElements() && poms < MAX_POMS) {
                    var entry = entries.nextElement();
                    String name = entry.getName();
                    if (entry.isDirectory() || !name.startsWith("META-INF/maven/") || !name.endsWith("/pom.properties")) continue;
                    poms++;
                    var properties = new Properties();
                    try (InputStream in = zip.getInputStream(entry)) {
                        properties.load(new java.io.ByteArrayInputStream(in.readNBytes(MAX_POM_BYTES)));
                    } catch (IllegalArgumentException malformed) { continue; }
                    String group = clean(properties.getProperty("groupId")), artifact = clean(properties.getProperty("artifactId"));
                    if (group != null && artifact != null) declared.put(group + ":" + artifact, clean(properties.getProperty("version")));
                }
            }
        }
        var byName = new TreeMap<String,Builder>();
        var classLibrary = new TreeMap<String,String>();
        for (String className : new TreeSet<>(classNames)) {
            String[] match = null; int at = -1;
            for (var entry : CATALOG) {
                if (className.startsWith(entry[0])) { match = entry; at = 0; break; }
            }
            if (match == null) {
                // Relocated copies: the known package after one or more leading segments ("app/shaded/com/google/gson/").
                for (var entry : CATALOG) {
                    int index = className.indexOf("/" + entry[0]);
                    if (index > 0 && entry[0].indexOf('/') < entry[0].length() - 1) { match = entry; at = index + 1; break; }
                }
            }
            if (match == null) continue;
            var builder = byName.computeIfAbsent(match[1], key -> new Builder());
            builder.coordinates.add(match[2]);
            builder.packages.add(className.substring(0, at) + match[0]);
            builder.classes++;
            if (at == 0) builder.evidence.add("package " + match[0].replace('/', '.').replaceAll("\\.$", ""));
            else { builder.evidence.add("relocated package"); builder.relocated.add(className.substring(0, at).replace('/', '.').replaceAll("\\.$", "")); }
            classLibrary.put(className, match[1]);
        }
        // Maven metadata confirms a recognized library and gives its version; metadata for unknown artifacts is listed by itself.
        var libraries = new ArrayList<Library>();
        for (var entry : byName.entrySet()) {
            var builder = entry.getValue();
            String coordinates = String.join(", ", builder.coordinates), version = null;
            for (String coordinate : builder.coordinates)
                for (var pom : declared.entrySet())
                    if (pom.getKey().equals(coordinate) || pom.getKey().startsWith(coordinate.substring(0, coordinate.indexOf(':') + 1))
                        && sameFamily(pom.getKey(), coordinate)) {
                        version = pom.getValue(); builder.evidence.add("Maven metadata " + pom.getKey() + (pom.getValue() == null ? "" : ":" + pom.getValue()));
                    }
            libraries.add(new Library(entry.getKey(), coordinates, version, builder.packages, builder.classes, builder.evidence, builder.relocated));
        }
        int libraryClasses = classLibrary.size(), application = classNames.size() - libraryClasses;
        String self = null;
        if (application == 0 && !libraries.isEmpty()) {
            var largest = libraries.stream().max(Comparator.comparingInt(Library::classes)).orElseThrow();
            self = largest.name();
            libraries.remove(largest);
            classLibrary.values().removeIf(self::equals);
            libraryClasses = classLibrary.size(); application = classNames.size() - libraryClasses;
        }
        libraries.sort(Comparator.comparingInt(Library::classes).reversed().thenComparing(Library::name));
        return new Result(List.copyOf(libraries), application, libraryClasses, self, Collections.unmodifiableMap(classLibrary));
    }

    /** The same library family: equal group, or artifacts that only differ by a suffix (netty-all vs netty-buffer). */
    private static boolean sameFamily(String declared, String catalog) {
        String declaredArtifact = declared.substring(declared.indexOf(':') + 1), catalogArtifact = catalog.substring(catalog.indexOf(':') + 1);
        String stem = catalogArtifact.contains("-") ? catalogArtifact.substring(0, catalogArtifact.indexOf('-')) : catalogArtifact;
        return declaredArtifact.startsWith(stem);
    }
    private static String clean(String value) {
        if (value == null) return null;
        String trimmed = value.strip();
        return trimmed.isEmpty() || trimmed.length() > 200 || !trimmed.matches("[A-Za-z0-9._+\\-]+") ? null : trimmed;
    }

    static Map<String,Object> json(Result result) {
        var data = new TreeMap<String,Object>();
        data.put("application_classes", result.applicationClasses()); data.put("library_classes", result.libraryClasses());
        data.put("input_is_library", result.self());
        data.put("libraries", result.libraries().stream().map(library -> {
            var row = new TreeMap<String,Object>();
            row.put("name", library.name()); row.put("maven", library.coordinates()); row.put("version", library.version());
            row.put("classes", library.classes()); row.put("packages", List.copyOf(library.packages()));
            row.put("evidence", List.copyOf(library.evidence())); row.put("relocated_under", List.copyOf(library.relocatedUnder()));
            return (Object) row; }).toList());
        data.put("method", "Maven metadata in META-INF/maven, well-known package prefixes and their relocated (shaded) copies; "
            + "renamed or obfuscated copies are not recognized");
        return data;
    }

    static String text(Result result) {
        if (result.libraries().isEmpty()) return result.self() == null ? "" : "Embedded libraries: none (the input itself is " + result.self() + ")\n";
        var out = new StringBuilder("Embedded libraries: ").append(result.libraryClasses()).append(" of ")
            .append(result.libraryClasses() + result.applicationClasses()).append(" classes belong to ").append(result.libraries().size())
            .append(" recognized libraries; ").append(result.applicationClasses()).append(" are the application's own\n");
        for (var library : result.libraries())
            out.append("  ").append(library.name()).append(library.version() == null ? "" : " " + library.version()).append(": ")
                .append(library.classes()).append(" classes (").append(String.join("; ", library.evidence())).append(")\n");
        return out.toString();
    }

    private static final class Builder {
        final SortedSet<String> coordinates = new TreeSet<>(), packages = new TreeSet<>(), evidence = new TreeSet<>(), relocated = new TreeSet<>();
        int classes;
    }
}
