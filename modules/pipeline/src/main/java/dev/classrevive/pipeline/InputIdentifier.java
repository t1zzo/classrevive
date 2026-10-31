package dev.classrevive.pipeline;

import dev.classrevive.dex.DexConversion;
import dev.classrevive.model.Limits;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.time.Duration;
import java.util.*;

/**
 * Phase 2CE: says what an input that is not named {@code .class} or {@code .jar} is, from its bytes only, and finds Java in
 * it when Java is there to find. Executables (PE, ELF, Mach-O), archives (zip, jar, war, jmod) and common installers are
 * recognized; a jar inside an executable (Launch4j, exe4j, JSmooth, a jar concatenated to a stub or script) is found by
 * validating the zip's end record, central directory and local headers, never by a bare {@code PK} sequence. Nothing is
 * run, mapped, unpacked or decrypted: protectors and encrypted sections are reported with their evidence and left alone.
 * Phase 2CW adds Android: an APK (root {@code classes*.dex}) or a dex file is converted to class files by dex2jar in a
 * worker ({@link DexConversion}) and taken as a jar; an app bundle or a set of split APKs is named, not converted.
 * Phase 2DB: a zip of jars (a launcher's payload, a distribution) is a {@link Verdict#JAVA_BUNDLE}: its nested jars are read
 * in memory ({@link JavaBundle}), one is decompiled and the others go on the classpath.
 */
public final class InputIdentifier {
    private InputIdentifier() {}

    public enum Verdict {
        /** a class file */
        JAVA_CLASS,
        /** a zip whose classes sit at the jar layout (whatever its file name) */
        JAVA_ARCHIVE,
        /** a war, jmod or Spring Boot jar: classes under WEB-INF/classes, classes/ or BOOT-INF/classes */
        JAVA_ARCHIVE_OTHER_LAYOUT,
        /** a valid zip without class entries */
        ARCHIVE_WITHOUT_CLASSES,
        /** a valid jar inside another file (an executable's overlay or image, or after a script) */
        EMBEDDED_JAR,
        /** Phase 2DB: a valid zip, alone or inside another file, whose classes are all in nested jars */
        JAVA_BUNDLE,
        /** Phase 2CW: an Android package (APK): Dalvik bytecode in root classes*.dex, converted to class files */
        ANDROID_PACKAGE,
        /** Phase 2CW: a single Dalvik executable (.dex), converted to class files */
        DEX_FILE,
        /** Phase 2CW: an Android App Bundle (.aab) or a set of split APKs (.apks, .xapk): named, not converted */
        ANDROID_BUNDLE,
        /** a Java launcher whose jar is not inside it */
        EXTERNAL_JAR_LAUNCHER,
        /** GraalVM native-image: Java compiled ahead of time, no bytecode */
        NATIVE_IMAGE,
        /** a .NET assembly: managed code, not Java */
        DOTNET,
        /** an installer whose payload is compressed */
        INSTALLER,
        /** native, no visible Java, high-entropy data plus a protector or injection imports */
        NATIVE_PROTECTED_NO_JAVA,
        /** native, no visible Java */
        NATIVE_NO_JAVA,
        UNKNOWN
    }

    /** A zip that passed every structural check; {@code offset} is where its offsets count from (its first byte). */
    public record Archive(long offset, long length, int entries, int classEntries, int layoutClassEntries, int nestedJars,
                          boolean manifest, String layout, String location, AndroidEntries android) {}

    /**
     * Phase 2CW: what makes a zip an Android archive. {@code dex}: root classes.dex, classes2.dex, ... (what the runtime
     * loads); {@code otherDex}: .dex files elsewhere (assets, payloads: data, not converted); {@code bundleDex}: an app
     * bundle's base/dex and feature modules' dex; {@code nestedApks}: .apk entries of a split-APK set.
     */
    public record AndroidEntries(int dex, int otherDex, int bundleDex, int nestedApks, boolean manifest) {
        static final AndroidEntries NONE = new AndroidEntries(0, 0, 0, 0, false);
    }

    /** {@code facts} are header facts, {@code signals} the evidence behind the verdict; both are one-line, printable. */
    public record Identification(String fileName, long size, String sha256, String format, Verdict verdict, String explanation,
                                 List<String> facts, List<String> signals, List<Archive> archives, Archive selected, String wrapper,
                                 Map<String,Object> details) {
        public Identification {
            facts = List.copyOf(facts); signals = List.copyOf(signals); archives = List.copyOf(archives);
            details = Collections.unmodifiableMap(new TreeMap<>(details));
        }
        /**
         * Java the pipeline can take: a class file, a jar-layout zip, or such a zip inside another file, and (Phase 2CW)
         * Dalvik bytecode, which {@link #resolve} converts to a jar first.
         */
        public boolean javaAvailable() {
            return verdict == Verdict.JAVA_CLASS || verdict == Verdict.JAVA_ARCHIVE || verdict == Verdict.EMBEDDED_JAR
                || verdict == Verdict.JAVA_BUNDLE || dalvik();
        }
        /** Phase 2CW: an APK or a dex file, whose classes come from a dex2jar conversion. */
        public boolean dalvik() { return verdict == Verdict.ANDROID_PACKAGE || verdict == Verdict.DEX_FILE; }
        public String text() {
            var out = new StringBuilder("Input identification (Phase 2CE, static: nothing was run, unpacked or decrypted)\n");
            out.append("File: ").append(NativeBinary.clean(fileName)).append(" (").append(size).append(" bytes)\n");
            out.append("SHA-256: ").append(sha256).append('\n');
            out.append("Format: ").append(format).append('\n');
            for (String fact : facts) out.append("  ").append(fact).append('\n');
            if (!signals.isEmpty()) {
                out.append("Evidence:\n");
                for (String signal : signals) out.append("  - ").append(signal).append('\n');
            }
            out.append("Verdict: ").append(verdict).append('\n');
            out.append(explanation).append('\n');
            return out.toString();
        }
        public Map<String,Object> json() {
            var data = new TreeMap<String,Object>(details);
            data.remove(MANIFEST_TEXT); // written apart, as input-origin/AndroidManifest.xml
            data.put("schema_version", 1); data.put("kind", "input_identification");
            data.put("file_name", NativeBinary.clean(fileName)); data.put("size", size); data.put("sha256", sha256);
            data.put("format", format); data.put("verdict", verdict.name()); data.put("explanation", explanation);
            data.put("facts", facts); data.put("signals", signals); data.put("wrapper", wrapper);
            data.put("archives", archives.stream().map(InputIdentifier::archiveJson).toList());
            data.put("selected_archive", selected == null ? null : archiveJson(selected));
            data.put("execution", "NONE");
            return data;
        }
    }

    /**
     * What a job takes from an input that is not named .class or .jar: the class or jar bytes and where they came from;
     * {@code conversion} is the dex2jar run for Dalvik inputs (Phase 2CW), null otherwise; {@code dalvik} the dex files alone
     * (Phase 2CX), written to {@code input-origin/dalvik.zip} for engines that read Dalvik bytecode. Phase 2DB: for a
     * {@link Verdict#JAVA_BUNDLE}, {@code entry} is the nested jar taken and {@code classpath} the other nested jars with
     * classes (name to bytes, most classes first), for the verifier, and {@code leftOff} the nested jars with classes left off it
     * because they repeat a class of {@code entry} or of a jar already on it (the verifier refuses a class defined twice; a
     * variant of the main jar under other mappings is the usual case) or would pass the dependency byte budget; otherwise null and empty.
     */
    public record Resolved(Identification identification, byte[] bytes, boolean jar, DexConversion.Result conversion, byte[] dalvik,
                           String entry, Map<String,byte[]> classpath, List<String> leftOff) {
        public Resolved {
            classpath = Collections.unmodifiableMap(new LinkedHashMap<>(classpath));
            leftOff = List.copyOf(leftOff);
        }
        public Resolved(Identification identification, byte[] bytes, boolean jar, DexConversion.Result conversion, byte[] dalvik) {
            this(identification, bytes, jar, conversion, dalvik, null, Map.of(), List.of());
        }
        public Resolved(Identification identification, byte[] bytes, boolean jar) { this(identification, bytes, jar, null, null); }
        /** Phase 2DB: the same, with only the nested jars actually given to the verifier. */
        public Resolved withClasspath(Map<String,byte[]> used) { return new Resolved(identification, bytes, jar, conversion, dalvik, entry, used, leftOff); }
    }

    /** Thrown by {@link #resolve} when the input holds no Java the pipeline can take; the message is one line. */
    public static final class NoJavaException extends IOException {
        private final transient Identification identification;
        NoJavaException(Identification identification) {
            super("Input is " + identification.format() + " (" + identification.verdict() + "): " + firstSentence(identification.explanation()));
            this.identification = identification;
        }
        public Identification identification() { return identification; }
    }

    static final String DIRECTORY = "input-origin";
    /** Phase 2CX: the dex files of an Android input, for engines that read Dalvik bytecode. */
    public static final String DALVIK = "dalvik.zip";
    /** Phase 2CY: the decoded manifest's text in {@code details}, written to {@code input-origin/AndroidManifest.xml}. */
    static final String MANIFEST_TEXT = "android_manifest_xml";
    /** Sections at or above this entropy (bits per byte times 1000) and size look compressed or encrypted. */
    static final int HIGH_ENTROPY = 7200;
    static final int HIGH_ENTROPY_BYTES = 64 * 1024;
    static final int MAX_ARCHIVES = 16, MAX_CLASS_SIGNATURES = 100_000;
    /** Phase 2CW: the dex2jar run of {@link #resolve(Path)} when the caller gives no time limit. */
    static final Duration CONVERSION_TIMEOUT = Duration.ofMinutes(10);

    /** Reads the file (bounded by the intake byte limit) and identifies it. */
    public static Identification identify(Path path) throws IOException {
        Path name = path.getFileName();
        return identify(read(path), name == null ? "" : name.toString(), path.toAbsolutePath().normalize().getParent());
    }

    /** {@code directory}: the folder holding the input, for launchers whose jar sits next to them (names are listed, never run). */
    static Identification identify(byte[] b, String fileName, Path directory) {
        var facts = new ArrayList<String>();
        var signals = new ArrayList<String>();
        var details = new TreeMap<String,Object>();
        String lower = fileName.toLowerCase(Locale.ROOT);
        String sha = sha256(b);
        var strings = Needles.scan(b);

        if (b.length >= 10 && u32be(b, 0) == 0xcafebabeL && u16be(b, 6) >= 45 && u16be(b, 6) <= 72) {
            int major = u16be(b, 6);
            return new Identification(fileName, b.length, sha, "Java class file (major " + major + ", Java " + (major - 44) + ")",
                Verdict.JAVA_CLASS, "A class file: decompiled as it is.", facts, signals, List.of(), null, null, details);
        }
        // Phase 2CW: "dex\n" + a three-digit version + NUL, with the header's file_size equal to the file's.
        if (b.length >= 0x70 && b[0] == 'd' && b[1] == 'e' && b[2] == 'x' && b[3] == '\n' && b[7] == 0
                && Character.isDigit(b[4]) && Character.isDigit(b[5]) && Character.isDigit(b[6]) && u32(b, 32) == b.length) {
            String version = new String(b, 4, 3, StandardCharsets.US_ASCII);
            facts.add("dex version " + version + ", " + u32(b, 96) + " class definitions in the header");
            details.put("dex_class_definitions", u32(b, 96));
            return new Identification(fileName, b.length, sha, "Dalvik executable (dex " + version + ", Android)", Verdict.DEX_FILE,
                "Android Dalvik bytecode: converted to class files by " + DexConversion.TOOL + " " + DexConversion.VERSION
                    + ", then decompiled as a jar. Results are measured against that conversion, not against the dex itself.",
                facts, signals, List.of(), null, null, details);
        }

        var native_ = NativeBinary.parse(b);
        PeExtras pe = null;
        if (native_.format().startsWith("PE")) {
            try { pe = PeExtras.read(b); } catch (IndexOutOfBoundsException truncated) { facts.add("header note: PE optional header truncated"); }
        }
        long overlayStart = pe == null ? -1 : pe.overlayStart();
        var archives = archives(b, overlayStart);
        long classSignatures = classSignatures(b, archives);
        long pkOutside = localHeadersOutside(b, archives);
        details.put("class_signatures_outside_archives", classSignatures);
        details.put("zip_local_headers_outside_archives", pkOutside);

        String format;
        if (native_.known()) {
            format = nativeFormat(b, native_, pe);
            describeNative(b, native_, pe, facts, details);
        } else if (!archives.isEmpty() && archives.getFirst().offset() <= 4) {
            Archive first = archives.getFirst();
            format = first.offset() == 4 && b[0] == 'J' && b[1] == 'M' ? "Java module (jmod: 4-byte JM header plus zip)"
                : first.layout().equals("android") ? "Android package (APK: " + first.android().dex() + " dex file(s) at the root"
                    + (first.android().manifest() ? ", AndroidManifest.xml" : "") + ")"
                : first.layout().equals("android-bundle") ? "Android app bundle or split-APK set (" + first.android().bundleDex()
                    + " module dex file(s), " + first.android().nestedApks() + " nested .apk entries)"
                : "ZIP archive (" + first.layout() + ")";
        } else if (b.length >= 8 && u32be(b, 0) == 0xd0cf11e0L && u32be(b, 4) == 0xa1b11ae1L) {
            format = "OLE compound file" + (lower.endsWith(".msi") ? " (Windows Installer package)" : " (MSI installer or Office document)");
        } else if (b.length >= 2 && b[0] == '#' && b[1] == '!') {
            format = "script (" + NativeBinary.clean(firstLine(b)) + ")";
        } else {
            format = "unknown (first bytes " + HexFormat.of().formatHex(b, 0, Math.min(8, b.length)) + ", entropy "
                + NativeBinary.bits(Math.max(0, NativeBinary.entropy(b, 0, b.length))) + " bits/byte)";
        }

        for (var archive : archives) {
            facts.add("valid zip at " + hex(archive.offset()) + ", " + archive.length() + " bytes (" + archive.location() + "): " + archive.entries()
                + " entries, " + archive.classEntries() + " classes, layout " + archive.layout()
                + (archive.nestedJars() > 0 ? ", " + archive.nestedJars() + " nested .jar entries" : ""));
        }
        for (var archive : archives) {
            var android = archive.android();
            if (android.otherDex() > 0) signals.add(android.otherDex() + " .dex file(s) outside the archive root (assets or payloads):"
                + " the runtime does not load them as the app's code; they are not converted");
        }
        if (pkOutside > 0) signals.add(pkOutside + " 'PK\\x03\\x04' sequence(s) outside every valid archive (no matching central directory: not a zip)");
        if (classSignatures > 0) signals.add(classSignatures + " class-file signature(s) (CAFEBABE + Java version) outside every archive (not extracted)");

        String wrapper = wrapper(strings, b);
        Archive selected = archives.stream().filter(a -> a.layoutClassEntries() > 0)
            .max(Comparator.comparingInt(Archive::layoutClassEntries)).orElse(null);
        Archive otherLayout = archives.stream().filter(a -> a.classEntries() > 0 && a.layoutClassEntries() == 0).findFirst().orElse(null);
        // Phase 2DB: with no jar-layout classes anywhere, the zip whose nested jars hold the most classes is a bundle of jars.
        JavaBundle.Contents bundle = null;
        if (selected == null) {
            for (var archive : archives) {
                if (archive.nestedJars() == 0 || archive.classEntries() > 0) continue;
                var contents = JavaBundle.read(b, archive);
                if (contents.main() != null && (bundle == null || contents.classes() > bundle.classes())) bundle = contents;
            }
        }
        if (bundle != null) describeBundle(bundle, facts, details);

        Verdict verdict;
        String explanation;
        if (!native_.known() && selected != null && selected.offset() <= 4 && selected.offset() == archives.getFirst().offset()) {
            verdict = Verdict.JAVA_ARCHIVE;
            explanation = "A jar-layout zip archive (" + selected.classEntries() + " classes): decompiled as a jar.";
        } else if (!native_.known() && otherLayout != null && otherLayout.offset() <= 4) {
            verdict = Verdict.JAVA_ARCHIVE_OTHER_LAYOUT;
            explanation = "Java classes in a " + otherLayout.layout() + " layout: extract that folder (for example "
                + switch (otherLayout.layout()) { case "war" -> "WEB-INF/classes"; case "spring-boot" -> "BOOT-INF/classes"; default -> "classes/"; }
                + ") into a jar, or the nested jars, and pass that jar.";
        } else if (!native_.known() && !archives.isEmpty() && archives.getFirst().offset() <= 4 && archives.getFirst().layout().equals("android")) {
            verdict = Verdict.ANDROID_PACKAGE;
            explanation = "An Android package: its Dalvik bytecode (" + archives.getFirst().android().dex() + " dex file(s)) is converted to class "
                + "files by " + DexConversion.TOOL + " " + DexConversion.VERSION + ", then decompiled as a jar. Results are measured against "
                + "that conversion, not against the dex itself; android.* framework classes are not in the APK and get inferred stubs.";
        } else if (!native_.known() && !archives.isEmpty() && archives.getFirst().offset() <= 4 && archives.getFirst().layout().equals("android-bundle")) {
            verdict = Verdict.ANDROID_BUNDLE;
            explanation = "An Android App Bundle (.aab) or a set of split APKs (.apks, .xapk): ClassRevive takes one APK or dex file. "
                + "Extract the base APK (base.apk) from the set, or build an APK from the bundle with bundletool, and pass that.";
        } else if (pe != null && pe.clr()) {
            verdict = Verdict.DOTNET;
            explanation = "A .NET assembly (CLR header): managed .NET code, not Java bytecode. Use a .NET decompiler (ILSpy, dnSpy); ClassRevive does not apply.";
        } else if (selected != null) {
            verdict = Verdict.EMBEDDED_JAR;
            explanation = "A jar (" + selected.classEntries() + " classes) is embedded " + selected.location()
                + (wrapper == null ? "" : " by " + wrapper) + ": it is extracted as data and decompiled; the executable itself is never run.";
        } else if (bundle != null) {
            verdict = Verdict.JAVA_BUNDLE;
            selected = bundle.archive();
            long withClasses = bundle.jars().stream().filter(jar -> jar.classes() > 0).count();
            explanation = "A bundle of Java jars: the zip " + selected.location() + " holds " + bundle.jars().size() + " nested .jar entries, "
                + withClasses + " with classes (" + bundle.classes() + " classes in all), and no loose classes"
                + (wrapper == null ? "" : "; wrapper: " + wrapper) + ". decompile and diagnose take " + NativeBinary.clean(bundle.main().name())
                + " (" + bundle.main().classes() + " classes; rule: " + JavaBundle.RULE + ") and give the other jars with classes to "
                + "the verifier as --classpath, except jars that repeat classes already there (such as variants of it under other "
                + "mappings); choose another with --entry <name>. Everything is read as data; nothing is run.";
        } else if (strings.has("com.oracle.svm") || strings.has("com/oracle/svm") || strings.has("SubstrateVM") || strings.has("graal_create_isolate")) {
            verdict = Verdict.NATIVE_IMAGE;
            explanation = "A GraalVM native-image (SubstrateVM markers): the Java code was compiled ahead of time to machine code, so no "
                + "bytecode exists to recover. Only native analysis applies (for example Ghidra); ClassRevive cannot decompile it to Java.";
        } else if (installer(strings, b, native_) != null) {
            verdict = Verdict.INSTALLER;
            explanation = installer(strings, b, native_) + " installer: its payload is compressed and ClassRevive does not unpack "
                + "installers. Extract it with the vendor's tool or an archiver (7-Zip, innoextract, msiexec /a) without running "
                + "it, then pass the .jar you find.";
        } else if (otherLayout != null) {
            verdict = Verdict.JAVA_ARCHIVE_OTHER_LAYOUT;
            explanation = "A " + otherLayout.layout() + "-layout archive is embedded " + otherLayout.location()
                + ": extract its classes folder into a jar and pass that jar.";
        } else if (!archives.isEmpty() && !native_.known() && archives.getFirst().offset() <= 4) {
            verdict = Verdict.ARCHIVE_WITHOUT_CLASSES;
            Archive first = archives.getFirst();
            explanation = "A zip archive without class files" + (first.nestedJars() > 0
                ? "; it holds " + first.nestedJars() + " .jar entries: extract them and pass each jar." : ".");
        } else if (native_.known() && (wrapper != null || strings.has("JNI_CreateJavaVM"))) {
            verdict = Verdict.EXTERNAL_JAR_LAUNCHER;
            explanation = launcherExplanation(wrapper, strings, directory, facts);
        } else if (native_.known()) {
            var protection = protection(b, native_, pe, strings, signals, details, false);
            verdict = protection ? Verdict.NATIVE_PROTECTED_NO_JAVA : Verdict.NATIVE_NO_JAVA;
            explanation = protection
                ? "Native executable with no visible Java (no class file, no valid jar), a high-entropy section and protector or "
                    + "injection evidence: Java, if there is any, is possibly encrypted inside the binary or loaded at run time; "
                    + "not recoverable statically. ClassRevive does not unpack, decrypt or run protected binaries."
                : "Native code with no visible Java (no class file, no valid jar, no launcher or JNI markers): nothing to decompile to Java.";
        } else {
            verdict = Verdict.UNKNOWN;
            explanation = "Not a class file, jar, zip, executable or known installer: nothing to decompile.";
        }
        if (verdict != Verdict.NATIVE_PROTECTED_NO_JAVA && verdict != Verdict.NATIVE_NO_JAVA && native_.known())
            protection(b, native_, pe, strings, signals, details, verdict == Verdict.EMBEDDED_JAR || verdict == Verdict.JAVA_BUNDLE);
        if (wrapper != null) signals.add("Java wrapper marker: " + wrapper);
        if (verdict == Verdict.ANDROID_PACKAGE) describeAndroid(b, archives.getFirst(), facts, signals, details);
        return new Identification(fileName, b.length, sha, format, verdict, explanation, facts, signals, archives, selected, wrapper, details);
    }

    /** For a job: the class or jar bytes of an input that is not named .class or .jar, or {@link NoJavaException}. */
    public static Resolved resolve(Path input) throws IOException {
        return resolve(input, CONVERSION_TIMEOUT);
    }

    /** As {@link #resolve(Path)}; {@code timeout} bounds the dex2jar run of an APK or dex file (Phase 2CW). */
    public static Resolved resolve(Path input, Duration timeout) throws IOException {
        return resolve(input, timeout, null);
    }

    /**
     * As {@link #resolve(Path, Duration)}; Phase 2DB: {@code entry} names the nested jar of a {@link Verdict#JAVA_BUNDLE} to take
     * (its full name or a base name that is unique), or null for the default ({@link JavaBundle#RULE}). An entry for any other
     * input, or one that is not a nested jar with classes, is an {@link IllegalArgumentException}.
     */
    public static Resolved resolve(Path input, Duration timeout, String entry) throws IOException {
        byte[] bytes = read(input);
        Path name = input.getFileName();
        var identification = identify(bytes, name == null ? "" : name.toString(), input.toAbsolutePath().normalize().getParent());
        if (!identification.javaAvailable()) throw new NoJavaException(identification);
        if (entry != null && identification.verdict() != Verdict.JAVA_BUNDLE)
            throw new IllegalArgumentException("--entry applies only to a bundle of jars (JAVA_BUNDLE); this input is " + identification.verdict());
        if (identification.verdict() == Verdict.JAVA_BUNDLE) return bundle(bytes, identification, entry);
        if (identification.verdict() == Verdict.JAVA_CLASS) return new Resolved(identification, bytes, false);
        if (identification.dalvik()) return convert(input, identification, timeout);
        Archive archive = identification.selected();
        return new Resolved(identification, Arrays.copyOfRange(bytes, (int) archive.offset(), (int) (archive.offset() + archive.length())), true);
    }

    /** Phase 2DB: the chosen nested jar of a bundle, and the other nested jars with classes as its classpath. */
    private static Resolved bundle(byte[] bytes, Identification identification, String entry) {
        var contents = JavaBundle.read(bytes, identification.selected());
        JavaBundle.Jar main = entry == null ? contents.main() : contents.find(entry);
        if (main == null) throw new IllegalArgumentException("No nested jar named " + NativeBinary.clean(entry) + "; the bundle holds: "
            + NativeBinary.clean(String.join(", ", contents.jars().stream().map(JavaBundle.Jar::name).limit(30).toList())));
        if (!main.readable() || main.classes() == 0) throw new IllegalArgumentException("The nested jar " + NativeBinary.clean(main.name())
            + " has no classes to decompile" + (main.problem() == null ? "" : " (" + main.problem() + ")"));
        var classpath = new LinkedHashMap<String,byte[]>();
        var leftOff = new ArrayList<String>();
        // The verifier refuses a class defined twice across the input and its dependencies, and more than 128 MiB of them:
        // jars are taken greedily, most classes first, and one that repeats a class already taken is left off.
        var taken = new HashSet<>(main.classNames());
        long budget = JavaBundle.MAX_CLASSPATH_BYTES;
        for (var jar : contents.jars().stream().filter(jar -> jar != main && jar.readable() && jar.classes() > 0)
                .sorted(Comparator.comparingInt(JavaBundle.Jar::classes).reversed().thenComparing(JavaBundle.Jar::name)).toList()) {
            if (jar.classNames().stream().anyMatch(taken::contains) || jar.bytes().length > budget) { leftOff.add(jar.name()); continue; }
            taken.addAll(jar.classNames());
            budget -= jar.bytes().length;
            classpath.put(jar.name(), jar.bytes());
        }
        return new Resolved(identification, main.bytes(), true, null, null, main.name(), classpath, leftOff);
    }

    /**
     * Phase 2CW: dex2jar in its worker, in a temporary folder that is removed afterwards; the jar's bytes and the run's
     * findings are kept. Nothing converted means no Java to take: an {@link IOException} saying why.
     */
    private static Resolved convert(Path input, Identification identification, Duration timeout) throws IOException {
        Path work = Files.createTempDirectory("classrevive-dex");
        try {
            var conversion = DexConversion.convert(input.toAbsolutePath(), work.resolve("run"), timeout);
            if (conversion.jar() == null) {
                String log = "";
                Path engineLog = work.resolve("run").resolve("engine.log");
                if (Files.isRegularFile(engineLog)) log = Files.readAllLines(engineLog, StandardCharsets.UTF_8).stream()
                    .filter(line -> line.contains("Exception") || line.contains("dex2jar") || line.contains("classes.dex")).findFirst().orElse("");
                throw new IOException("The Dalvik bytecode could not be converted to class files (" + DexConversion.TOOL + " "
                    + DexConversion.VERSION + ": " + conversion.worker().status() + (log.isBlank() ? "" : "; " + NativeBinary.clean(log.strip())) + ")");
            }
            byte[] jar = Files.readAllBytes(conversion.jar());
            byte[] dalvik = conversion.dalvik() == null ? null : Files.readAllBytes(conversion.dalvik());
            return new Resolved(identification, jar, true, conversion, dalvik);
        } finally {
            try (var walk = Files.walk(work)) {
                for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            } catch (IOException | UncheckedIOException ignored) {
                // A leftover temporary folder holds only the conversion's own output.
            }
        }
    }

    /** Writes {@code output/input-origin/identification.txt} and {@code .json}: where the decompiled bytes came from. */
    public static void record(Path output, Resolved resolved) throws IOException {
        Path directory = output.resolve(DIRECTORY);
        Files.createDirectory(directory);
        var identification = resolved.identification();
        String origin = origin(resolved);
        Files.writeString(directory.resolve("identification.txt"), identification.text() + origin + "\n", StandardOpenOption.CREATE_NEW);
        var json = identification.json();
        var extracted = new TreeMap<String,Object>();
        extracted.put("file", resolved.jar() ? "input.jar" : "input.class"); extracted.put("bytes", (long) resolved.bytes().length);
        extracted.put("sha256", sha256(resolved.bytes()));
        if (resolved.entry() != null) {
            extracted.put("entry", resolved.entry()); extracted.put("classpath", List.copyOf(resolved.classpath().keySet()));
            extracted.put("left_off_classpath", resolved.leftOff());
            extracted.put("selection_rule", JavaBundle.RULE);
        }
        json.put("extracted", extracted);
        if (resolved.dalvik() != null) Files.write(directory.resolve(DALVIK), resolved.dalvik(), StandardOpenOption.CREATE_NEW);
        if (identification.details().get(MANIFEST_TEXT) instanceof String manifest)
            Files.writeString(directory.resolve("AndroidManifest.xml"), "<!-- Decoded from the APK's binary manifest by ClassRevive (Phase 2CY); "
                + "resource references stay as @0x ids. -->\n" + manifest, StandardOpenOption.CREATE_NEW);
        if (resolved.conversion() != null) {
            json.put("conversion", conversionJson(resolved.conversion()));
            Files.writeString(directory.resolve("conversion.txt"), conversionText(resolved.conversion()), StandardOpenOption.CREATE_NEW);
        }
        EvidenceReport.write(directory.resolve("identification.json"), json);
    }

    /** For {@code identify --output}: creates the new directory and writes identification.txt and .json in it. */
    public static Path write(Path output, Identification identification) throws IOException {
        output = dev.classrevive.engine.SafePaths.resolveParents(output);
        Files.createDirectory(output);
        Files.writeString(output.resolve("identification.txt"), identification.text(), StandardOpenOption.CREATE_NEW);
        EvidenceReport.write(output.resolve("identification.json"), identification.json());
        return output;
    }

    /**
     * Phase 2DB, for {@code identify --output}: writes each readable nested jar of a {@link Verdict#JAVA_BUNDLE} to
     * {@code directory/bundle/} as data (its path flattened to one portable file name) and returns the names written; empty
     * for any other verdict. Nothing is opened or run.
     */
    public static List<String> extractBundle(Path input, Identification identification, Path directory) throws IOException {
        if (identification.verdict() != Verdict.JAVA_BUNDLE) return List.of();
        var contents = JavaBundle.read(read(input), identification.selected());
        Path folder = Files.createDirectory(directory.resolve("bundle"));
        var written = new ArrayList<String>();
        for (var jar : contents.jars()) {
            if (jar.bytes() == null) continue;
            String file = jar.name().replaceAll("[^A-Za-z0-9._-]", "_");
            if (file.startsWith(".")) file = "_" + file;
            Files.write(folder.resolve(file), jar.bytes(), StandardOpenOption.CREATE_NEW);
            written.add("bundle/" + file);
        }
        return written;
    }

    /**
     * When the job recorded an origin, adds it to an evidence pair: a section at the end of {@code text} (report.txt,
     * obfuscation.txt) and {@code input_origin} in {@code json} (report.json, diagnostics.json). Returns the summary line or null.
     */
    public static String attach(Path output, Path text, Path json) throws IOException {
        Path data = output.resolve(DIRECTORY).resolve("identification.json");
        if (!Files.isRegularFile(data, LinkOption.NOFOLLOW_LINKS)) return null;
        if (!(Json.decode(Files.readString(data, StandardCharsets.UTF_8)) instanceof Map<?,?> origin)) return null;
        Object selected = origin.get("selected_archive");
        String line = "Input origin: " + origin.get("format") + " (" + origin.get("verdict") + ")"
            + (origin.get("extracted") instanceof Map<?,?> extracted && extracted.get("entry") != null && selected instanceof Map<?,?> archive
                ? "; nested jar " + extracted.get("entry") + " taken from the bundle at offset " + archive.get("offset") + " ("
                    + archive.get("location") + "), " + ((List<?>) extracted.get("classpath")).size() + " other nested jar(s) on the classpath"
                : selected instanceof Map<?,?> archive ? "; jar extracted from offset " + archive.get("offset") + ", " + archive.get("length")
                + " bytes, " + archive.get("location") : "")
            + (origin.get("wrapper") == null ? "" : "; wrapper: " + origin.get("wrapper"))
            + (origin.get("conversion") instanceof Map<?,?> conversion ? "; converted from Dalvik bytecode by " + conversion.get("tool") + " "
                + conversion.get("version") + " (" + conversion.get("classes") + " classes, " + conversion.get("untranslated_methods")
                + " untranslated methods)" : "")
            + "; original SHA-256 " + origin.get("sha256");
        Files.writeString(text, "\n" + line + " (details in " + DIRECTORY + "/identification.txt)\n",
            StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        if (Files.isRegularFile(json, LinkOption.NOFOLLOW_LINKS) && Json.decode(Files.readString(json, StandardCharsets.UTF_8)) instanceof Map<?,?> map) {
            var merged = new TreeMap<String,Object>();
            map.forEach((key, value) -> merged.put((String) key, value));
            merged.put("input_origin", origin);
            Path next = json.resolveSibling(json.getFileName() + ".next");
            EvidenceReport.write(next, merged);
            Files.move(next, json, StandardCopyOption.REPLACE_EXISTING);
        }
        return line;
    }

    private static Map<String,Object> conversionJson(DexConversion.Result conversion) {
        var data = new TreeMap<String,Object>();
        data.put("tool", DexConversion.TOOL); data.put("version", DexConversion.VERSION);
        data.put("dex_files", conversion.dexFiles().stream().map(dex -> Map.<String,Object>of("name", dex.name(), "bytes", dex.bytes(),
            "classes", (long) dex.classes())).toList());
        data.put("classes", (long) conversion.classes());
        data.put("untranslated_methods", conversion.failedMethodCount());
        data.put("duplicate_classes_dropped", conversion.duplicateCount());
        data.put("normalized", Map.of("methods_with_locals_split", (long) conversion.movedParameters(),
            "fields_marked_synthetic", (long) conversion.syntheticFields(), "classes_made_top_level", (long) conversion.topLevelClasses(),
            "synthetic_methods_shown", (long) conversion.shownSyntheticMethods()));
        data.put("errors", conversion.errors());
        data.put("debug_information", "line numbers kept; local variable names are not written by dex2jar");
        data.put("measured_against", "the converted class files, not the dex");
        return data;
    }

    private static String conversionText(DexConversion.Result conversion) {
        var out = new StringBuilder("Dalvik to class files (Phase 2CW): " + DexConversion.TOOL + " " + DexConversion.VERSION + ", in a worker; nothing was run.\n");
        for (var dex : conversion.dexFiles()) out.append("  ").append(NativeBinary.clean(dex.name())).append(": ").append(dex.bytes())
            .append(" bytes, ").append(dex.classes()).append(" classes kept\n");
        out.append("Classes: ").append(conversion.classes()).append('\n');
        out.append("Methods dex2jar could not translate (their bodies now throw): ").append(conversion.failedMethodCount()).append('\n');
        for (var method : conversion.failedMethods()) out.append("  ").append(NativeBinary.clean(method.className())).append('.')
            .append(NativeBinary.clean(method.method())).append(" (").append(NativeBinary.clean(method.error())).append(")\n");
        out.append("Classes defined again in a later dex (the first is kept, as at run time): ").append(conversion.duplicateCount()).append('\n');
        for (var duplicate : conversion.duplicates()) out.append("  ").append(NativeBinary.clean(duplicate.className())).append(": kept from ")
            .append(NativeBinary.clean(duplicate.keptFrom())).append(", dropped from ").append(NativeBinary.clean(duplicate.droppedFrom())).append('\n');
        out.append("Normalized for Java decompilers (semantics unchanged): ").append(conversion.movedParameters())
            .append(" method(s) whose reused registers were split into separate locals; ").append(conversion.syntheticFields())
            .append(" javac synthetic field(s) marked synthetic again; ").append(conversion.topLevelClasses())
            .append(" anonymous or local class(es) named outside their method made top-level; ").append(conversion.shownSyntheticMethods())
            .append(" synthetic method(s) called from other classes (R8 lambdas) no longer marked synthetic\n");
        for (String error : conversion.errors()) out.append("Error: ").append(NativeBinary.clean(error)).append('\n');
        out.append("Results are measured against these class files, not against the dex. Local variable names are not carried over.\n");
        return out.toString();
    }

    private static String origin(Resolved resolved) {
        if (resolved.conversion() != null) {
            var conversion = resolved.conversion();
            return "Converted: Dalvik bytecode (" + conversion.dexFiles().size() + " dex file(s)) to input.jar by " + DexConversion.TOOL + " "
                + DexConversion.VERSION + " (" + resolved.bytes().length + " bytes, SHA-256 " + sha256(resolved.bytes()) + "): "
                + conversion.classes() + " classes, " + conversion.failedMethodCount() + " untranslated method(s), "
                + conversion.duplicateCount() + " duplicate class(es) dropped; details in conversion.txt.";
        }
        var archive = resolved.identification().selected();
        if (resolved.entry() != null)
            return "Extracted: nested jar " + NativeBinary.clean(resolved.entry()) + " of the bundle at " + hex(archive.offset()) + " as input.jar ("
                + resolved.bytes().length + " bytes, SHA-256 " + sha256(resolved.bytes()) + "); rule: " + JavaBundle.RULE + ". Classpath for "
                + "verification: " + (resolved.classpath().isEmpty() ? "none" : NativeBinary.clean(String.join(", ", resolved.classpath().keySet()))) + "."
                + (resolved.leftOff().isEmpty() ? "" : " Left off the classpath (they repeat classes already on it, or pass the "
                    + "dependency byte budget): " + NativeBinary.clean(String.join(", ", resolved.leftOff())) + ".");
        return archive == null ? "Decompiled: the file itself, as input.class."
            : "Extracted: bytes " + hex(archive.offset()) + ".." + hex(archive.offset() + archive.length()) + " as input.jar ("
                + resolved.bytes().length + " bytes, SHA-256 " + sha256(resolved.bytes()) + "); zip offsets count from that first byte.";
    }

    /** Phase 2CY: the APK's decoded manifest and its native libraries, as facts, signals and JSON details. */
    private static void describeAndroid(byte[] b, Archive archive, List<String> facts, List<String> signals, Map<String,Object> details) {
        var contents = AndroidPackage.read(Arrays.copyOfRange(b, (int) archive.offset(), (int) (archive.offset() + archive.length())));
        var manifest = contents.manifest();
        if (manifest != null) {
            if (manifest.packageName() != null) facts.add("Android package " + NativeBinary.clean(manifest.packageName())
                + (manifest.versionName() == null ? "" : ", version " + NativeBinary.clean(manifest.versionName()))
                + (manifest.versionCode() == null ? "" : " (code " + NativeBinary.clean(manifest.versionCode()) + ")")
                + (manifest.minSdk() == null ? "" : ", minSdk " + manifest.minSdk())
                + (manifest.targetSdk() == null ? "" : ", targetSdk " + manifest.targetSdk()));
            if (!manifest.components().isEmpty()) {
                var counts = new StringJoiner(", ");
                manifest.components().forEach((kind, names) -> counts.add(names.size() + " " + (names.size() == 1 ? kind
                    : kind.endsWith("y") ? kind.substring(0, kind.length() - 1) + "ies" : kind.endsWith("s") ? kind + "es" : kind + "s")));
                facts.add("components: " + counts + (manifest.launcher() == null ? "" : "; launcher " + NativeBinary.clean(manifest.launcher())));
            }
            if (!manifest.permissions().isEmpty()) facts.add(manifest.permissions().size() + " permission(s) requested: "
                + NativeBinary.clean(String.join(", ", manifest.permissions().stream().limit(12).toList()))
                + (manifest.permissions().size() > 12 ? ", ..." : ""));
            if (manifest.debuggable()) signals.add("android:debuggable=\"true\" in the manifest (a debug build)");
            for (String problem : manifest.problems()) signals.add("manifest: " + NativeBinary.clean(problem));
            var data = new TreeMap<String,Object>();
            data.put("package", manifest.packageName()); data.put("version_code", manifest.versionCode()); data.put("version_name", manifest.versionName());
            data.put("min_sdk", manifest.minSdk() == null ? null : (long) manifest.minSdk());
            data.put("target_sdk", manifest.targetSdk() == null ? null : (long) manifest.targetSdk());
            data.put("permissions", manifest.permissions()); data.put("components", manifest.components());
            data.put("launcher", manifest.launcher()); data.put("debuggable", manifest.debuggable()); data.put("problems", manifest.problems());
            details.put("android_manifest", data);
            if (!manifest.xml().isEmpty()) details.put(MANIFEST_TEXT, manifest.xml());
        }
        if (!contents.libraries().isEmpty()) {
            var byAbi = new TreeMap<String, List<AndroidPackage.NativeLibrary>>();
            contents.libraries().forEach(library -> byAbi.computeIfAbsent(library.abi(), key -> new ArrayList<>()).add(library));
            byAbi.forEach((abi, libraries) -> facts.add("native libraries lib/" + NativeBinary.clean(abi) + ": " + libraries.size() + " .so ("
                + libraries.stream().map(l -> l.format() == null ? "not read" : l.format()).distinct().sorted().reduce((x, y) -> x + "; " + y).orElse("")
                + "), " + libraries.stream().mapToInt(AndroidPackage.NativeLibrary::jniExports).sum() + " Java_ JNI export(s)"
                + (libraries.stream().anyMatch(AndroidPackage.NativeLibrary::jniOnLoad) ? ", JNI_OnLoad" : "")));
            for (var library : contents.libraries())
                if (!library.packingHints().isEmpty()) signals.add(NativeBinary.clean(library.entry()) + " looks packed: "
                    + NativeBinary.clean(String.join("; ", library.packingHints())));
            signals.add("native code (lib/*/*.so) is not decompiled to Java: native methods (Java_ exports, JNI_OnLoad registrations) "
                + "call into it; extract those libraries and analyze them with a native tool such as Ghidra");
            details.put("native_libraries", contents.libraries().stream().map(library -> {
                var data = new TreeMap<String,Object>();
                data.put("entry", library.entry()); data.put("abi", library.abi()); data.put("size", library.size());
                data.put("format", library.format()); data.put("jni_exports", (long) library.jniExports());
                data.put("jni_onload", library.jniOnLoad()); data.put("packing_hints", library.packingHints());
                return (Map<String,Object>) data;
            }).toList());
        }
    }

    /** Phase 2DB: one fact per nested jar (the first 40) and the bundle's JSON. */
    private static void describeBundle(JavaBundle.Contents bundle, List<String> facts, Map<String,Object> details) {
        int shown = 0;
        for (var jar : bundle.jars()) {
            if (shown++ == 40) { facts.add("... " + (bundle.jars().size() - 40) + " more nested jars"); break; }
            facts.add("nested jar " + NativeBinary.clean(jar.name()) + ": " + (jar.readable()
                ? jar.classes() + " classes, " + jar.size() + " bytes" + (jar.mainClass() ? ", Main-Class" : "")
                    + (jar.library() == null ? "" : ", library " + jar.library()) + (jar.libraryFolder() ? ", library folder" : "")
                    + (jar.nestedJars() > 0 ? ", " + jar.nestedJars() + " jars inside (not opened)" : "")
                : "not read (" + jar.problem() + ")") + (jar == bundle.main() ? "  <- main"
                    : jar.readable() && jar.variantOf(bundle.main()) ? ", variant of the main jar (the same classes)" : ""));
        }
        if (bundle.otherEntries() > 0) facts.add(bundle.otherEntries() + " other entr" + (bundle.otherEntries() == 1 ? "y" : "ies")
            + " in the bundle (not Java, never run): " + NativeBinary.clean(String.join(", ", bundle.others())));
        var data = new TreeMap<String,Object>();
        data.put("archive_offset", bundle.archive().offset()); data.put("main", bundle.main().name()); data.put("selection_rule", JavaBundle.RULE);
        data.put("classes", (long) bundle.classes()); data.put("jars", bundle.jars().stream().map(JavaBundle.Jar::json).toList());
        data.put("other_entries", (long) bundle.otherEntries()); data.put("other_entry_names", bundle.others());
        details.put("bundle", data);
    }

    // ---- zip validation ----

    /**
     * Every zip whose end record, central directory and local headers agree, newest end record first; an archive inside an
     * archive already found (a stored nested jar) is not listed. Zip64 and multi-disk archives are not accepted.
     */
    static List<Archive> archives(byte[] b, long overlayStart) {
        var found = new ArrayList<Archive>();
        for (int e = b.length - 22; e >= 0 && found.size() < MAX_ARCHIVES; e--) {
            if (b[e] != 'P' || b[e + 1] != 'K' || b[e + 2] != 5 || b[e + 3] != 6) continue;
            final int end = e;
            if (found.stream().anyMatch(a -> end >= a.offset() && end < a.offset() + a.length())) continue;
            Archive archive = validate(b, e, overlayStart);
            if (archive != null) { found.add(archive); e = (int) archive.offset(); }
        }
        found.sort(Comparator.comparingLong(Archive::offset));
        return found;
    }

    private static Archive validate(byte[] b, int e, long overlayStart) {
        int disk = u16(b, e + 4), cdDisk = u16(b, e + 6), onDisk = u16(b, e + 8), count = u16(b, e + 10), comment = u16(b, e + 20);
        long cenSize = u32(b, e + 12), cenOffset = u32(b, e + 16);
        if (disk != 0 || cdDisk != 0 || onDisk != count || count == 0 || count == 0xffff || cenSize == 0xffffffffL || cenOffset == 0xffffffffL) return null;
        long end = (long) e + 22 + comment, cenStart = e - cenSize, base = cenStart - cenOffset;
        if (end > b.length || cenStart < 0 || base < 0) return null;
        int classes = 0, layoutClasses = 0, nested = 0, war = 0, boot = 0, jmod = 0, dex = 0, otherDex = 0, bundleDex = 0, apks = 0;
        boolean manifest = false, androidManifest = false, jmodHeader = base == 4 && b[0] == 'J' && b[1] == 'M';
        long p = cenStart;
        for (int i = 0; i < count; i++) {
            if (p + 46 > e || u32(b, (int) p) != 0x02014b50L) return null;
            int at = (int) p;
            int nameLength = u16(b, at + 28), extra = u16(b, at + 30), note = u16(b, at + 32);
            long compressed = u32(b, at + 20), local = base + u32(b, at + 42);
            if (nameLength == 0 || p + 46 + nameLength > e) return null;
            if (local + 30 > cenStart || u32(b, (int) local) != 0x04034b50L || u16(b, (int) local + 26) != nameLength) return null;
            if (!Arrays.equals(b, at + 46, at + 46 + nameLength, b, (int) local + 30, (int) local + 30 + nameLength)) return null;
            if (compressed != 0xffffffffL && local + 30 + nameLength + u16(b, (int) local + 28) + compressed > cenStart) return null;
            String name = new String(b, at + 46, nameLength, StandardCharsets.UTF_8);
            if (name.endsWith(".class")) {
                classes++;
                if (name.startsWith("WEB-INF/classes/")) war++;
                else if (name.startsWith("BOOT-INF/classes/")) boot++;
                else if (name.startsWith("classes/") && jmodHeader) jmod++;
                else layoutClasses++;
            }
            if (name.toLowerCase(Locale.ROOT).endsWith(".jar")) nested++;
            // Phase 2CW: Android entries.
            if (name.matches("classes\\d*\\.dex")) dex++;
            else if (name.matches("[^/]+/dex/classes\\d*\\.dex")) bundleDex++;
            else if (name.toLowerCase(Locale.ROOT).endsWith(".dex")) otherDex++;
            if (name.toLowerCase(Locale.ROOT).endsWith(".apk")) apks++;
            if (name.equals("AndroidManifest.xml") || name.endsWith("/manifest/AndroidManifest.xml")) androidManifest = true;
            if (name.equalsIgnoreCase("META-INF/MANIFEST.MF")) manifest = true;
            p += 46L + nameLength + extra + note;
        }
        if (p != e) return null;
        String layout = layoutClasses > 0 ? "jar" : war > 0 ? "war" : boot > 0 ? "spring-boot" : jmod > 0 ? "jmod"
            : dex > 0 ? "android" : bundleDex > 0 || apks > 0 && classes == 0 ? "android-bundle" : classes == 0 ? "no classes" : "jar";
        String location = overlayStart >= 0 && base >= overlayStart ? "in the executable's overlay (data appended after its sections)"
            : overlayStart >= 0 ? "inside the executable's image (a section or resource)"
            : base == 0 ? "at the start of the file" : "after a " + base + "-byte prefix";
        return new Archive(base, end - base, count, classes, layoutClasses, nested, manifest, layout, location,
            dex + otherDex + bundleDex + apks == 0 && !androidManifest ? AndroidEntries.NONE
                : new AndroidEntries(dex, otherDex, bundleDex, apks, androidManifest));
    }

    private static long classSignatures(byte[] b, List<Archive> archives) {
        long count = 0;
        for (int i = 0; i + 10 <= b.length && count < MAX_CLASS_SIGNATURES; i++) {
            if ((b[i] & 0xff) != 0xca || (b[i + 1] & 0xff) != 0xfe || (b[i + 2] & 0xff) != 0xba || (b[i + 3] & 0xff) != 0xbe) continue;
            int minor = u16be(b, i + 4), major = u16be(b, i + 6), pool = u16be(b, i + 8);
            if (major < 45 || major > 72 || (minor != 0 && minor != 3 && minor != 0xffff) || pool < 2 || inside(archives, i)) continue;
            count++;
        }
        return count;
    }

    private static long localHeadersOutside(byte[] b, List<Archive> archives) {
        long count = 0;
        for (int i = 0; i + 4 <= b.length; i++)
            if (b[i] == 'P' && b[i + 1] == 'K' && b[i + 2] == 3 && b[i + 3] == 4 && !inside(archives, i)) count++;
        return count;
    }

    private static boolean inside(List<Archive> archives, long at) {
        for (var archive : archives) if (at >= archive.offset() && at < archive.offset() + archive.length()) return true;
        return false;
    }

    // ---- native facts and signals ----

    /** PE facts NativeBinary does not keep: subsystem, DLL flag, CLR header, certificate table and where the overlay starts. */
    record PeExtras(int subsystem, boolean dll, boolean clr, long certificateOffset, long certificateSize, long overlayStart) {
        static PeExtras read(byte[] b) {
            int pe = (int) u32(b, 0x3c);
            int characteristics = u16(b, pe + 22), count = u16(b, pe + 6), optionalSize = u16(b, pe + 20), optional = pe + 24;
            boolean plus = u16(b, optional) == 0x20b;
            int subsystem = u16(b, optional + 68);
            int directories = optional + (plus ? 112 : 96);
            long directoryCount = u32(b, optional + (plus ? 108 : 92));
            long certificateOffset = directoryCount > 4 ? u32(b, directories + 32) : 0, certificateSize = directoryCount > 4 ? u32(b, directories + 36) : 0;
            boolean clr = directoryCount > 14 && u32(b, directories + 112) != 0 && u32(b, directories + 116) != 0;
            long overlay = optional + optionalSize + 40L * count;
            for (int i = 0; i < Math.min(count, NativeBinary.MAX_SECTIONS); i++) {
                int at = optional + optionalSize + 40 * i;
                if (at + 40 > b.length) break;
                long raw = u32(b, at + 16), pointer = u32(b, at + 20);
                if (raw > 0) overlay = Math.max(overlay, pointer + raw);
            }
            return new PeExtras(subsystem, (characteristics & 0x2000) != 0, clr, certificateOffset, certificateSize, Math.min(overlay, b.length));
        }
    }

    private static String nativeFormat(byte[] b, NativeBinary.Headers headers, PeExtras pe) {
        if (pe != null) {
            String kind = pe.dll() ? "DLL" : switch (pe.subsystem()) {
                case 2 -> "GUI executable"; case 3 -> "console executable"; case 1 -> "native (driver) image";
                case 10, 11, 12, 13 -> "EFI image"; default -> "executable (subsystem " + pe.subsystem() + ")";
            };
            return headers.format() + " " + headers.machine() + " " + kind + " (Windows" + (pe.clr() ? ", .NET CLR header" : "") + ")";
        }
        if (headers.format().startsWith("ELF")) {
            boolean little = b[5] != 2;
            int type = little ? u16(b, 16) : u16be(b, 16);
            return headers.format() + " " + headers.machine() + " " + switch (type) {
                case 2 -> "executable"; case 3 -> "shared object or position-independent executable"; case 1 -> "relocatable object";
                case 4 -> "core dump"; default -> "type " + type;
            } + " (Linux/Unix)";
        }
        if (headers.format().startsWith("Mach-O") && headers.format().contains("universal")) return "Mach-O universal binary (macOS)";
        boolean little = (b[0] & 0xff) == 0xce || (b[0] & 0xff) == 0xcf;
        long type = b.length >= 16 ? (little ? u32(b, 12) : u32be(b, 12)) : 0;
        return headers.format() + " " + headers.machine() + " " + (type == 2 ? "executable" : type == 6 ? "dynamic library" : type == 8 ? "bundle" : "file type " + type) + " (macOS)";
    }

    private static void describeNative(byte[] b, NativeBinary.Headers headers, PeExtras pe, List<String> facts, Map<String,Object> details) {
        var sections = new ArrayList<Map<String,Object>>();
        for (var section : headers.sections()) {
            facts.add(String.format(Locale.ROOT, "section %-10s %s raw %d bytes, virtual %d bytes, entropy %s",
                NativeBinary.label(section), section.permissions(), section.rawSize(), section.virtualSize(),
                section.entropyMillibits() < 0 ? "-" : NativeBinary.bits(section.entropyMillibits())));
            sections.add(Map.of("name", NativeBinary.label(section), "permissions", section.permissions(), "raw_size", section.rawSize(),
                "virtual_size", section.virtualSize(), "entropy_millibits", (long) section.entropyMillibits()));
        }
        details.put("sections", sections);
        details.put("imports", (long) headers.imports().size());
        details.put("imported_libraries", headers.neededLibraries());
        if (!headers.neededLibraries().isEmpty())
            facts.add("imports " + headers.imports().size() + " functions from " + headers.neededLibraries().size() + " libraries: "
                + String.join(", ", headers.neededLibraries().stream().limit(20).toList()) + (headers.neededLibraries().size() > 20 ? ", ..." : ""));
        for (String problem : headers.problems()) facts.add("header note: " + problem);
        if (pe == null) return;
        long overlay = b.length - pe.overlayStart();
        boolean certificate = pe.certificateSize() > 0 && pe.certificateOffset() >= pe.overlayStart()
            && pe.certificateOffset() + pe.certificateSize() <= b.length;
        long dataOverlay = overlay - (certificate ? pe.certificateSize() : 0);
        // The certificate table is 8-byte aligned: up to 7 zero bytes right before it are padding, not data.
        boolean padding = certificate && dataOverlay > 0 && dataOverlay < 8 && pe.certificateOffset() - pe.overlayStart() == dataOverlay
            && NativeBinary.entropy(b, pe.overlayStart(), dataOverlay) == 0 && b[(int) pe.overlayStart()] == 0;
        if (padding) dataOverlay = 0;
        var overlayJson = new TreeMap<String,Object>();
        overlayJson.put("offset", pe.overlayStart()); overlayJson.put("size", overlay); overlayJson.put("data_size", Math.max(0, dataOverlay));
        if (overlay > 0) facts.add("overlay (bytes after the last section): " + overlay + " bytes at " + hex(pe.overlayStart()));
        if (certificate) {
            int at = (int) pe.certificateOffset();
            int revision = pe.certificateSize() >= 8 ? u16(b, at + 4) : 0, type = pe.certificateSize() >= 8 ? u16(b, at + 6) : 0;
            var signers = signers(b, at, (int) pe.certificateSize());
            facts.add("Authenticode signature (WIN_CERTIFICATE revision " + (revision >> 8) + "." + (revision & 0xff) + ", type "
                + (type == 2 ? "PKCS#7 SignedData" : String.valueOf(type)) + "): " + pe.certificateSize() + " bytes at " + hex(at)
                + (signers.isEmpty() ? "" : "; certificate names: " + String.join("; ", signers)) + " (not verified)");
            overlayJson.put("certificate", Map.of("offset", (long) at, "size", pe.certificateSize(), "names", signers));
        } else if (pe.certificateSize() > 0) {
            facts.add("certificate table entry points outside the overlay (" + hex(pe.certificateOffset()) + ", " + pe.certificateSize() + " bytes)");
        }
        if (dataOverlay > 0) {
            int entropy = NativeBinary.entropy(b, pe.overlayStart(), dataOverlay);
            facts.add("data overlay (not the signature): " + dataOverlay + " bytes, entropy " + NativeBinary.bits(Math.max(0, entropy)));
            overlayJson.put("data_entropy_millibits", (long) entropy);
        } else if (overlay > 0 && certificate) {
            facts.add("the overlay is only the signature" + (padding ? " and its alignment padding" : "") + ": no appended data");
        }
        details.put("overlay", overlayJson);
    }

    /** Subject/issuer common names (OID 2.5.4.3) in the PKCS#7 blob, read as text only; the signature is not verified. */
    private static List<String> signers(byte[] b, int at, int size) {
        var names = new LinkedHashSet<String>();
        int end = Math.min(b.length, at + size);
        for (int i = at; i + 7 < end && names.size() < 8; i++) {
            if (b[i] != 6 || b[i + 1] != 3 || b[i + 2] != 0x55 || b[i + 3] != 4 || b[i + 4] != 3) continue;
            int tag = b[i + 5] & 0xff, length = b[i + 6] & 0xff;
            if ((tag != 0x0c && tag != 0x13 && tag != 0x14 && tag != 0x16) || length > 127 || i + 7 + length > end) continue;
            names.add(NativeBinary.clean(new String(b, i + 7, length, StandardCharsets.UTF_8)));
        }
        return List.copyOf(names);
    }

    private static final List<String> OPEN = List.of("OpenProcess", "NtOpenProcess");
    private static final List<String> ALLOCATE = List.of("VirtualAllocEx", "NtAllocateVirtualMemory", "VirtualAlloc2");
    private static final List<String> WRITE = List.of("WriteProcessMemory", "NtWriteVirtualMemory");
    private static final List<String> EXECUTE = List.of("CreateRemoteThread", "CreateRemoteThreadEx", "NtCreateThreadEx", "RtlCreateUserThread",
        "QueueUserAPC", "NtQueueApcThread", "SetThreadContext");
    private static final List<String> JNI = List.of("JNI_CreateJavaVM", "JNI_GetCreatedJavaVMs", "JNI_OnLoad", "Agent_OnLoad", "Agent_OnAttach",
        "JVM_", "jvmti", "JVMTI");

    /** Adds protector, entropy, injection and JNI evidence; true when it adds up to a protected binary with no visible Java. */
    private static boolean protection(byte[] b, NativeBinary.Headers headers, PeExtras pe, Needles strings, List<String> signals,
                                      Map<String,Object> details, boolean javaFound) {
        var packing = NativeBinary.packing(headers);
        boolean protector = !packing.suspectedPackers().isEmpty();
        if (protector) signals.add("protector or packer section names: " + String.join(", ", packing.suspectedPackers()) + " (a hypothesis from names)");
        for (String hint : packing.hints()) signals.add("packing hint: " + hint);
        boolean highEntropy = false;
        for (var section : headers.sections()) {
            if (section.rawSize() >= HIGH_ENTROPY_BYTES && section.entropyMillibits() >= HIGH_ENTROPY) {
                highEntropy = true;
                signals.add("high-entropy section " + NativeBinary.label(section) + ": " + section.rawSize() + " bytes at "
                    + NativeBinary.bits(section.entropyMillibits()) + " bits/byte (compressed, encrypted or virtualized)");
            }
        }
        var imported = new HashSet<String>();
        headers.imports().forEach(item -> imported.add(item.name()));
        var injection = new ArrayList<String>();
        int stages = 0;
        for (var stage : List.of(OPEN, ALLOCATE, WRITE, EXECUTE)) {
            boolean seen = false;
            for (String name : stage) {
                if (imported.contains(name)) { injection.add(name + " (import)"); seen = true; }
                else if (strings.has(name)) { injection.add(name + " (text only, resolved at run time)"); seen = true; }
            }
            if (seen) stages++;
        }
        boolean injects = stages >= 3;
        if (!injection.isEmpty())
            signals.add((injects ? "process-injection API set" : "process-memory APIs") + ": " + String.join(", ", injection)
                + " (" + stages + "/4 of open, allocate, write, start thread)");
        var jni = new ArrayList<String>();
        for (String name : JNI) if (imported.contains(name) || strings.has(name)) jni.add(name);
        long javaNames = strings.count("Java_") + strings.count("java/lang/") + strings.count("Ljava/");
        if (!jni.isEmpty()) signals.add("JNI/JVMTI names: " + String.join(", ", jni));
        // Phase 2DB: with the Java found as an archive, no JNI name only means the launcher does not embed a JVM itself.
        else if (javaFound) signals.add("no readable JNI or JVMTI name: the executable does not start a JVM through JNI itself "
            + "(launchers usually run java or javaw as a separate process); its Java is the archive above");
        else signals.add("no readable JNI or JVMTI name (JNI_CreateJavaVM, JNI_OnLoad, Agent_OnLoad, jvmti)");
        if (javaNames > 0) signals.add(javaNames + " readable Java names (Java_*, java/lang/, Ljava/)");
        for (String process : List.of("javaw.exe", "java.exe", "jvm.dll", "libjvm.so"))
            if (strings.has(process)) signals.add("names " + process + " as text");
        details.put("protection", Map.of("suspected_protectors", packing.suspectedPackers(), "high_entropy_section", highEntropy,
            "injection_stages", (long) stages, "injection_names", injection, "jni_names", jni, "readable_java_names", javaNames));
        return highEntropy && (injects || protector);
    }

    private static String wrapper(Needles strings, byte[] b) {
        if (strings.has("Launch4j") || strings.has("launch4j")) return "Launch4j";
        if (strings.has("exe4j") || strings.has("EXE4J")) return "exe4j";
        if (strings.has("JSmooth") || strings.has("jsmooth")) return "JSmooth";
        if (strings.has("WinRun4J")) return "WinRun4J";
        if (strings.has("jpackage") || strings.has("JPACKAGE") || strings.has("jpackageapplauncher")) return "jpackage launcher";
        if (b.length >= 2 && b[0] == '#' && b[1] == '!') return "shell-script stub (jar concatenated to a script)";
        return null;
    }

    private static String installer(Needles strings, byte[] b, NativeBinary.Headers headers) {
        if (strings.has("NullsoftInst") || strings.has("Nullsoft Install System")) return "NSIS (Nullsoft)";
        if (strings.has("Inno Setup Setup Data") || strings.has("Inno Setup")) return "Inno Setup";
        if (b.length >= 8 && u32be(b, 0) == 0xd0cf11e0L && u32be(b, 4) == 0xa1b11ae1L) return "Windows Installer (MSI/OLE)";
        if (headers.known() && strings.has("7z¼¯'\u001c")) return "7-Zip self-extracting";
        if (strings.has("InstallShield")) return "InstallShield";
        return null;
    }

    private static String launcherExplanation(String wrapper, Needles strings, Path directory, List<String> facts) {
        var jars = new TreeSet<String>(strings.jarNames());
        if (!jars.isEmpty()) facts.add(".jar names written in the file: " + String.join(", ", jars.stream().limit(10).toList()));
        var near = new ArrayList<String>();
        if (directory != null) {
            for (String folder : List.of("app", "lib/app", "../app", "../lib/app", "lib", ".")) {
                Path candidate = directory.resolve(folder).normalize();
                if (!Files.isDirectory(candidate, LinkOption.NOFOLLOW_LINKS)) continue;
                try (var list = Files.list(candidate)) {
                    list.filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar")
                            && Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                        .sorted().limit(20).forEach(path -> near.add(directory.relativize(path).toString().replace('\\', '/')));
                } catch (IOException | SecurityException ignored) { }
            }
            for (String runtime : List.of("runtime", "jre", "../runtime"))
                if (Files.isDirectory(directory.resolve(runtime).resolve("lib"), LinkOption.NOFOLLOW_LINKS))
                    facts.add("bundled Java runtime folder next to it: " + runtime + "/ (a JVM, not application code)");
        }
        if (!near.isEmpty()) facts.add("jars next to the launcher: " + String.join(", ", new LinkedHashSet<>(near)));
        return "A Java launcher" + (wrapper == null ? " (it starts a JVM through JNI_CreateJavaVM)" : " (" + wrapper + ")")
            + " whose jar is not inside it" + (near.isEmpty() ? "" : "; pass the jar next to it (" + near.getFirst() + ")")
            + (near.isEmpty() && !jars.isEmpty() ? "; the jar it names (" + jars.first() + ") is a separate file: pass that file" : "")
            + (near.isEmpty() && jars.isEmpty() ? "; the jar is a separate file (often in an app/ or lib/ folder next to it): pass that file" : "")
            + ".";
    }

    // ---- reading ----

    static byte[] read(Path path) throws IOException {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Expected an existing regular file (not a symbolic link)");
        long limit = Limits.defaults().maxInputBytes();
        if (Files.size(path) > limit) throw new IOException("Input exceeds the " + limit + "-byte limit");
        try (var in = Files.newInputStream(path)) {
            byte[] bytes = in.readNBytes((int) limit + 1);
            if (bytes.length > limit) throw new IOException("Input exceeds the " + limit + "-byte limit");
            return bytes;
        }
    }

    /**
     * One pass over the bytes for a fixed set of marker strings, as ASCII and as UTF-16LE (Windows resources), counted up to a
     * cap; also collects printable {@code ….jar} names. Markers are evidence, not proof: any file can contain any text.
     */
    static final class Needles {
        static final List<String> MARKERS = List.of("com.oracle.svm", "com/oracle/svm", "SubstrateVM", "graal_create_isolate",
            "NullsoftInst", "Nullsoft Install System", "Inno Setup Setup Data", "Inno Setup", "InstallShield", "7z¼¯'\u001c",
            "Launch4j", "launch4j", "exe4j", "EXE4J", "JSmooth", "jsmooth", "WinRun4J", "jpackage", "JPACKAGE", "jpackageapplauncher",
            "JNI_CreateJavaVM", "JNI_GetCreatedJavaVMs", "JNI_OnLoad", "Agent_OnLoad", "Agent_OnAttach", "JVM_", "jvmti", "JVMTI",
            "Java_", "java/lang/", "Ljava/", "javaw.exe", "java.exe", "jvm.dll", "libjvm.so",
            "OpenProcess", "NtOpenProcess", "VirtualAllocEx", "NtAllocateVirtualMemory", "VirtualAlloc2", "WriteProcessMemory",
            "NtWriteVirtualMemory", "CreateRemoteThread", "CreateRemoteThreadEx", "NtCreateThreadEx", "RtlCreateUserThread",
            "QueueUserAPC", "NtQueueApcThread", "SetThreadContext");
        static final int CAP = 1_000_000;
        private final Map<String,Long> counts = new HashMap<>();
        private final Set<String> jars = new TreeSet<>();

        static Needles scan(byte[] b) {
            var needles = new Needles();
            var patterns = new ArrayList<byte[]>();
            var owners = new ArrayList<String>();
            for (String marker : MARKERS) {
                byte[] ascii = marker.getBytes(StandardCharsets.ISO_8859_1);
                patterns.add(ascii); owners.add(marker);
                if (marker.length() >= 5) { patterns.add(marker.getBytes(StandardCharsets.UTF_16LE)); owners.add(marker); }
            }
            @SuppressWarnings("unchecked") List<Integer>[] first = new List[256];
            for (int i = 0; i < patterns.size(); i++) {
                int key = patterns.get(i)[0] & 0xff;
                if (first[key] == null) first[key] = new ArrayList<>();
                first[key].add(i);
            }
            for (int at = 0; at < b.length; at++) {
                var candidates = first[b[at] & 0xff];
                if (candidates != null) {
                    for (int index : candidates) {
                        byte[] pattern = patterns.get(index);
                        if (at + pattern.length > b.length || !Arrays.equals(b, at, at + pattern.length, pattern, 0, pattern.length)) continue;
                        // A marker that is part of a longer identifier (CreateRemoteThreadEx when looking for CreateRemoteThread) still counts.
                        needles.counts.merge(owners.get(index), 1L, (x, y) -> Math.min(CAP, x + y));
                    }
                }
                if (b[at] == '.' && needles.jars.size() < 50 && at + 4 <= b.length && (b[at + 1] | 0x20) == 'j' && (b[at + 2] | 0x20) == 'a'
                        && (b[at + 3] | 0x20) == 'r' && (at + 4 == b.length || !pathChar(b[at + 4]))) {
                    int start = at;
                    while (start > 0 && at - start < 200 && pathChar(b[start - 1])) start--;
                    if (at - start >= 1) needles.jars.add(NativeBinary.clean(new String(b, start, at + 4 - start, StandardCharsets.ISO_8859_1)));
                }
            }
            return needles;
        }
        private static boolean pathChar(byte c) {
            return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_' || c == '-' || c == '.' || c == '/' || c == '\\' || c == '$';
        }
        boolean has(String marker) { return counts.getOrDefault(marker, 0L) > 0; }
        long count(String marker) { return counts.getOrDefault(marker, 0L); }
        Set<String> jarNames() { return jars; }
    }

    // ---- helpers ----

    private static Map<String,Object> archiveJson(Archive archive) {
        var data = new TreeMap<String,Object>();
        data.put("offset", archive.offset()); data.put("length", archive.length()); data.put("entries", (long) archive.entries());
        data.put("class_entries", (long) archive.classEntries()); data.put("jar_layout_class_entries", (long) archive.layoutClassEntries());
        data.put("nested_jars", (long) archive.nestedJars()); data.put("manifest", archive.manifest()); data.put("layout", archive.layout());
        data.put("location", archive.location());
        if (!archive.android().equals(AndroidEntries.NONE)) {
            var android = archive.android();
            data.put("android", Map.of("root_dex", (long) android.dex(), "other_dex", (long) android.otherDex(),
                "bundle_dex", (long) android.bundleDex(), "nested_apks", (long) android.nestedApks(), "android_manifest", android.manifest()));
        }
        return data;
    }
    private static String firstSentence(String text) { int dot = text.indexOf(". "); return dot < 0 ? text : text.substring(0, dot + 1); }
    private static String firstLine(byte[] b) {
        int end = 0;
        while (end < b.length && end < 120 && b[end] != '\n' && b[end] != '\r') end++;
        return new String(b, 0, end, StandardCharsets.ISO_8859_1);
    }
    static String hex(long value) { return "0x" + Long.toHexString(value); }
    static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    private static int u16(byte[] b, int at) { return (b[at] & 0xff) | (b[at + 1] & 0xff) << 8; }
    private static long u32(byte[] b, int at) { return (b[at] & 0xffL) | (b[at + 1] & 0xffL) << 8 | (b[at + 2] & 0xffL) << 16 | (b[at + 3] & 0xffL) << 24; }
    private static int u16be(byte[] b, int at) { return (b[at] & 0xff) << 8 | (b[at + 1] & 0xff); }
    private static long u32be(byte[] b, int at) { return (b[at] & 0xffL) << 24 | (b[at + 1] & 0xffL) << 16 | (b[at + 2] & 0xffL) << 8 | (b[at + 3] & 0xffL); }
}
