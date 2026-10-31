package dev.classrevive.pipeline;

import dev.classrevive.verification.ClassCoverage;
import dev.classrevive.verification.ClassRoundTrip;
import dev.classrevive.verification.MethodRoundTrip;
import dev.classrevive.verification.VerificationResult;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * Phase 2CF: a self-contained {@code report.html} beside {@code report.json}, a confidence map of the selected sources.
 * Each class gets a level from the evidence already measured: its structural signatures, and the per-method round
 * trip of the whole candidate or, when that did not compile, of the per-file measurement (Phase 2BP). No script, no
 * network, no external resource; every name from the input is HTML-escaped. Behavior is never claimed.
 */
final class HtmlReport {
    private HtmlReport() {}

    /** Confidence of one class, from strongest to weakest evidence. */
    enum Level {
        EXACT("exact", "every method body recompiles identical or equivalent, all signatures match"),
        CLOSE("close", "signatures match; some bodies differ after recompilation (may be legitimate compiler variation)"),
        PER_FILE("per-file", "the whole candidate does not compile; this class's file compiles against the original classes"),
        PARTIAL("partial", "signatures missing or bodies not recompiled"),
        UNVERIFIED("unverified", "not recompiled: its file has compiler errors or no source");
        final String css, meaning;
        Level(String css, String meaning) { this.css = css; this.meaning = meaning; }
    }

    /** {@code methods}: every measured method's round trip (Phase G4), empty when the class was not recompiled. */
    record Row(String className, String file, String origin, int matched, int expected, int identical, int equivalent, int different,
               List<String> differentMethods, Level level, List<MethodRoundTrip.MethodResult> methods) {}

    /** {@code originalBodies}: every original method body, so bodies never recompiled count against the rate. */
    static void write(Path output, String inputName, String inputSha256, DecompilationJob.VerifiedJob job, int originalBodies) throws IOException {
        write(output, inputName, inputSha256, job, originalBodies, null);
    }
    /** Phase 2CI: with {@code libraries}, bundled library packages are labeled and folded, and the application's own share is shown. */
    static void write(Path output, String inputName, String inputSha256, DecompilationJob.VerifiedJob job, int originalBodies,
                      EmbeddedLibraries.Result libraries) throws IOException {
        write(output, inputName, inputSha256, job, originalBodies, libraries, null);
    }
    /** Phase 2CJ: with {@code compiler}, a tile names the language and compiler, and the debug information present. */
    static void write(Path output, String inputName, String inputSha256, DecompilationJob.VerifiedJob job, int originalBodies,
                      EmbeddedLibraries.Result libraries, dev.classrevive.bytecode.CompilerFingerprint.Result compiler) throws IOException {
        write(output, inputName, inputSha256, job, originalBodies, libraries, compiler, 0);
    }
    /** Phase 2CT: {@code stubs} inferred stand-in classes were on the compiler's class path; the page says so. */
    static void write(Path output, String inputName, String inputSha256, DecompilationJob.VerifiedJob job, int originalBodies,
                      EmbeddedLibraries.Result libraries, dev.classrevive.bytecode.CompilerFingerprint.Result compiler, int stubs) throws IOException {
        String page = render(inputName, inputSha256, job, originalBodies, libraries, compiler);
        // Phase 2DD: what the automatic reversal did, and that the numbers below measure the reversed classes.
        var reversal = job.deobfuscation();
        if (reversal != null) page = page.replaceFirst("<section class=\"tiles\">", java.util.regex.Matcher.quoteReplacement(
            "<p class=\"" + (reversal.applied() ? "reversal" : "warning") + "\">" + escape(AutoDeobfuscation.headline(reversal)) + "."
            + (reversal.applied() ? " Signatures, round trip and confidence below are measured against the reversed classes (deobfuscation/), "
                + "not the original; the reversal kept " + reversal.comparison().keptMethods() + "/" + reversal.comparison().originalMethods()
                + " of the original's method signatures." : "") + "</p>\n<section class=\"tiles\">"));
        if (stubs > 0) page = page.replaceFirst("<section class=\"tiles\">", java.util.regex.Matcher.quoteReplacement(
            "<p class=\"warning\">" + (job.selection().stubBacked() ? "Stub-backed: c" : "C") + "ompiled against " + stubs + " inferred stub class"
            + (stubs == 1 ? "" : "es") + " for missing libraries (stubs/stubs.txt), not the real libraries."
            // Phase 2DF: a stub-backed result still counts as complete, so the page says what that completeness rests on.
            + (job.selection().stubBacked() ? " The result counts as complete only against these stand-ins; the bytecode fallback is kept (fallback/)." : "")
            + " Pass the real JARs with --classpath for a faithful check.</p>\n<section class=\"tiles\">"));
        Files.writeString(output.resolve("report.html"), page, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
    }

    static List<Row> rows(DecompilationJob.VerifiedJob job) {
        VerificationResult verification = job.selection().verification();
        boolean compiled = verification.status() == VerificationResult.Status.COMPILED;
        var whole = new HashMap<String,ClassRoundTrip>();
        for (var trip : verification.roundTrip()) whole.put(trip.className(), trip);
        var partial = new HashMap<String,ClassRoundTrip>();
        if (job.perFile() != null) for (var trip : job.perFile().classes()) partial.put(trip.className(), trip);
        var rows = new ArrayList<Row>();
        for (ClassCoverage coverage : verification.classCoverage()) {
            String name = coverage.className();
            String file = topLevel(name) + ".java";
            String origin = job.selection().provenance().getOrDefault(file, "none");
            ClassRoundTrip trip = compiled ? whole.get(name) : partial.get(name);
            int identical = trip == null ? 0 : trip.identical(), equivalent = trip == null ? 0 : trip.equivalent(), different = trip == null ? 0 : trip.different();
            var differing = new ArrayList<String>();
            if (trip != null) for (var method : trip.methods()) if (method.outcome() == MethodRoundTrip.Outcome.DIFFERENT) differing.add(method.name() + method.descriptor());
            boolean signatures = coverage.matchedSymbols() == coverage.expectedSymbols();
            Level level;
            if (trip == null) level = compiled && signatures && coverage.expectedSymbols() > 0 ? Level.PARTIAL : Level.UNVERIFIED;
            else if (!compiled) level = Level.PER_FILE;
            else if (!signatures) level = Level.PARTIAL;
            else level = different == 0 ? Level.EXACT : Level.CLOSE;
            rows.add(new Row(name, file, origin, coverage.matchedSymbols(), coverage.expectedSymbols(), identical, equivalent, different, differing, level,
                trip == null ? List.of() : trip.methods()));
        }
        rows.sort(Comparator.comparing(Row::className));
        return rows;
    }

    /**
     * Phase G2: the same rows as the page, for {@code class_confidence} in {@code report.json}, so a reader of the JSON (the
     * desktop GUI's result index) shows exactly the levels of {@code report.html}. Level names are the enum constants.
     */
    static List<Object> confidence(DecompilationJob.VerifiedJob job) {
        var entries = new ArrayList<Object>();
        for (var row : rows(job)) {
            var entry = new TreeMap<String,Object>();
            entry.put("class_name", row.className()); entry.put("file", row.file()); entry.put("origin", row.origin());
            entry.put("matched_symbols", row.matched()); entry.put("expected_symbols", row.expected());
            entry.put("identical", row.identical()); entry.put("equivalent_after_normalization", row.equivalent());
            entry.put("different", row.different()); entry.put("different_methods", row.differentMethods());
            entry.put("level", row.level().name());
            // Phase G4 (additive): each measured method's outcome, so the editor can mark it at its declaration.
            entry.put("methods", row.methods().stream().map(method -> (Object) Map.of("method", method.name() + method.descriptor(),
                "outcome", method.outcome().name())).toList());
            entries.add(entry);
        }
        return entries;
    }

    static String render(String inputName, String inputSha256, DecompilationJob.VerifiedJob job, int originalBodies) {
        return render(inputName, inputSha256, job, originalBodies, null);
    }
    static String render(String inputName, String inputSha256, DecompilationJob.VerifiedJob job, int originalBodies, EmbeddedLibraries.Result libraries) {
        return render(inputName, inputSha256, job, originalBodies, libraries, null);
    }
    static String render(String inputName, String inputSha256, DecompilationJob.VerifiedJob job, int originalBodies, EmbeddedLibraries.Result libraries,
                         dev.classrevive.bytecode.CompilerFingerprint.Result compiler) {
        VerificationResult verification = job.selection().verification();
        var rows = rows(job);
        var counts = new EnumMap<Level,Integer>(Level.class);
        for (var level : Level.values()) counts.put(level, 0);
        long recovered = 0;
        for (var row : rows) {
            counts.merge(row.level(), 1, Integer::sum);
            recovered += row.identical() + row.equivalent();
        }
        long bodies = originalBodies;
        var out = new StringBuilder(64 * 1024 + rows.size() * 256);
        out.append("<!doctype html>\n<html lang=\"en\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n")
            .append("<title>ClassRevive report</title>\n<style>").append(STYLE).append("</style></head><body>\n<main>\n")
            .append("<h1>ClassRevive confidence map</h1>\n<p class=\"meta\">").append(escape(inputName)).append(" &middot; SHA-256 <code>")
            .append(escape(inputSha256)).append("</code></p>\n");
        out.append("<section class=\"tiles\">")
            .append(tile("Compilation", escape(verification.status().name()), verification.status() == VerificationResult.Status.COMPILED
                ? "the selected sources recompile" : verification.errors() + " javac error(s)"))
            .append(tile("Signatures", verification.matchedSymbols() + "/" + verification.expectedSymbols(), "classes, fields and methods with the original shape"))
            .append(tile("Method bodies", bodies == 0 ? "n/a" : recovered + "/" + bodies, bodies == 0 ? "no original method body"
                : percent(recovered, bodies) + " of all original bodies identical or equivalent"
                + (verification.status() == VerificationResult.Status.COMPILED ? "" : " (per-file credit)")))
            .append(tile("Selected", escape(job.selection().candidate()), job.selection().repairs().size() + " source repair(s), "
                + job.selection().methods().size() + " composed method(s)"));
        if (libraries != null && !libraries.libraries().isEmpty()) {
            long ownExact = rows.stream().filter(row -> libraries.libraryOf(row.className()) == null && row.level() == Level.EXACT).count();
            long own = rows.stream().filter(row -> libraries.libraryOf(row.className()) == null).count();
            out.append(tile("Application code", own + " classes", ownExact + " exact; " + libraries.libraryClasses() + " classes belong to "
                + libraries.libraries().size() + " bundled librar" + (libraries.libraries().size() == 1 ? "y" : "ies")));
        }
        if (compiler != null && compiler.classes() > 0) {
            String producer = compiler.language().equals("Java")
                ? (compiler.javaCompiler().equals("javac") && compiler.javacAtLeast() > 0 ? "javac " + compiler.javacAtLeast() + "+" : compiler.javaCompiler())
                : compiler.language();
            out.append(tile("Compiler", escape(producer), "local variable names in " + compiler.withLocalVariables() + "/" + compiler.withCode()
                + " classes, line numbers in " + compiler.withLineNumbers() + "/" + compiler.withCode()));
        }
        out.append("</section>\n");
        if (libraries != null && !libraries.libraries().isEmpty()) {
            out.append("<p class=\"meta\">Bundled libraries (folded below): ");
            var names = new StringJoiner(", ");
            for (var library : libraries.libraries())
                names.add(escape(library.name() + (library.version() == null ? "" : " " + library.version())) + " (" + library.classes() + ")");
            out.append(names).append("</p>\n");
        }
        out.append("<section class=\"bar\" aria-label=\"classes by confidence\">");
        for (var level : Level.values()) {
            int count = counts.get(level);
            if (count > 0) out.append("<span class=\"").append(level.css).append("\" style=\"flex:").append(count).append("\" title=\"")
                .append(level.css).append(": ").append(count).append("\"></span>");
        }
        out.append("</section>\n<ul class=\"legend\">");
        for (var level : Level.values())
            out.append("<li><i class=\"").append(level.css).append("\"></i><b>").append(level.css).append("</b> ").append(counts.get(level))
                .append(" &middot; ").append(escape(level.meaning)).append("</li>");
        out.append("</ul>\n");
        String currentPackage = null;
        for (var row : rows) {
            String pkg = packageOf(row.className());
            if (!pkg.equals(currentPackage)) {
                if (currentPackage != null) out.append("</tbody></table></details>\n");
                currentPackage = pkg;
                var inPackage = rows.stream().filter(r -> packageOf(r.className()).equals(pkg)).toList();
                long exact = inPackage.stream().filter(r -> r.level() == Level.EXACT).count();
                String library = libraries == null ? null : libraries.libraryOf(inPackage.getFirst().className());
                out.append(library == null ? "<details open><summary><code>" : "<details class=\"library\"><summary><code>")
                    .append(escape(pkg.isEmpty() ? "(default package)" : pkg.replace('/', '.')))
                    .append("</code> <span class=\"meta\">").append(inPackage.size()).append(" classes, ").append(exact).append(" exact")
                    .append(library == null ? "" : " &middot; library: " + escape(library)).append("</span></summary>\n")
                    .append("<table><thead><tr><th>Class</th><th>Source</th><th>Signatures</th><th>Identical</th><th>Equivalent</th><th>Different</th><th>Level</th></tr></thead><tbody>\n");
            }
            out.append("<tr><td><code>").append(escape(simpleName(row.className()))).append("</code>");
            if (!row.differentMethods().isEmpty()) {
                out.append("<details class=\"methods\"><summary>").append(row.differentMethods().size()).append(" different</summary><ul>");
                row.differentMethods().stream().limit(50).forEach(method -> out.append("<li><code>").append(escape(method)).append("</code></li>"));
                out.append("</ul></details>");
            }
            out.append("</td><td>").append(escape(row.origin())).append("</td><td>").append(row.matched()).append('/').append(row.expected())
                .append("</td><td>").append(row.identical()).append("</td><td>").append(row.equivalent()).append("</td><td>").append(row.different())
                .append("</td><td><span class=\"pill ").append(row.level().css).append("\">").append(row.level().css).append("</span></td></tr>\n");
        }
        if (currentPackage != null) out.append("</tbody></table></details>\n");
        out.append("<p class=\"meta\">Levels come from recompilation evidence only: structural signatures and per-method round trip against ")
            .append("the decompiled bytes. They are not proof of behavior, which ClassRevive never executes. Details: <code>report.json</code>, ")
            .append("<code>selection/selection.txt</code>.</p>\n</main></body></html>\n");
        return out.toString();
    }

    private static String tile(String label, String value, String note) {
        return "<div class=\"tile\"><span class=\"label\">" + escape(label) + "</span><span class=\"value\">" + value + "</span><span class=\"note\">"
            + escape(note) + "</span></div>";
    }
    private static String percent(long part, long whole) { return String.format(Locale.ROOT, "%.1f%%", part * 100.0 / whole); }
    private static String topLevel(String internalName) {
        int slash = internalName.lastIndexOf('/'), dollar = internalName.indexOf('$', slash + 1);
        return dollar > slash + 1 ? internalName.substring(0, dollar) : internalName;
    }
    private static String packageOf(String internalName) { int slash = internalName.lastIndexOf('/'); return slash < 0 ? "" : internalName.substring(0, slash); }
    private static String simpleName(String internalName) { return internalName.substring(internalName.lastIndexOf('/') + 1); }

    /** HTML-escapes text taken from the input (class, method and file names can hold any character). */
    static String escape(String text) {
        var out = new StringBuilder(text.length() + 16);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '&' -> out.append("&amp;"); case '<' -> out.append("&lt;"); case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;"); case '\'' -> out.append("&#39;");
                default -> { if (c < 0x20 && c != '\t') out.append("&#").append((int) c).append(';'); else out.append(c); }
            }
        }
        return out.toString();
    }

    private static final String STYLE = """
        :root{--bg:#fbfbfa;--fg:#1d1d1b;--muted:#6b6b66;--line:#e3e2dd;--card:#ffffff;
          --exact:#2f9e60;--close:#8fbf4d;--per-file:#4f86c6;--partial:#e0a43a;--unverified:#d45a4a;color-scheme:light dark}
        @media (prefers-color-scheme:dark){:root{--bg:#161615;--fg:#ecebe7;--muted:#9c9b95;--line:#2c2c2a;--card:#1f1f1d}}
        *{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--fg);font:15px/1.5 system-ui,-apple-system,"Segoe UI",sans-serif}
        main{max-width:1100px;margin:0 auto;padding:24px 16px 48px}h1{font-size:24px;margin:0 0 4px}
        .meta{color:var(--muted);font-size:13px}code{font-family:ui-monospace,Consolas,monospace;font-size:13px;overflow-wrap:anywhere}
        .tiles{display:grid;grid-template-columns:repeat(auto-fit,minmax(200px,1fr));gap:12px;margin:20px 0}
        .tile{background:var(--card);border:1px solid var(--line);border-radius:10px;padding:12px 14px;display:flex;flex-direction:column;gap:2px}
        .label{color:var(--muted);font-size:12px;text-transform:uppercase;letter-spacing:.04em}.value{font-size:22px;font-weight:600;overflow-wrap:anywhere}
        .note{color:var(--muted);font-size:13px}.bar{display:flex;height:14px;border-radius:7px;overflow:hidden;margin:8px 0}
        .bar span{min-width:2px}.exact{background:var(--exact)}.close{background:var(--close)}.per-file{background:var(--per-file)}
        .partial{background:var(--partial)}.unverified{background:var(--unverified)}
        .legend{list-style:none;padding:0;margin:8px 0 20px;display:grid;gap:4px;font-size:13px}
        .legend i{display:inline-block;width:10px;height:10px;border-radius:2px;margin-right:6px}
        details{background:var(--card);border:1px solid var(--line);border-radius:10px;margin:10px 0;padding:6px 12px}
        details.library{opacity:.75}
        .warning{border:1px solid var(--partial);border-radius:10px;padding:8px 12px;margin:12px 0;font-size:14px}
        .reversal{border:1px solid var(--per-file);border-radius:10px;padding:8px 12px;margin:12px 0;font-size:14px}
        summary{cursor:pointer;padding:4px 0}table{width:100%;border-collapse:collapse;font-size:13px;margin:6px 0}
        th,td{text-align:left;padding:5px 6px;border-top:1px solid var(--line);vertical-align:top}th{color:var(--muted);font-weight:500}
        td:nth-child(n+3):not(:last-child),th:nth-child(n+3):not(:last-child){text-align:right;font-variant-numeric:tabular-nums}
        details.methods{border:0;padding:0;margin:2px 0;background:none}details.methods summary{color:var(--muted);font-size:12px}
        .pill{display:inline-block;padding:1px 8px;border-radius:999px;color:#fff;font-size:12px}
        @media (max-width:640px){table{display:block;overflow-x:auto}}
        """;
}
