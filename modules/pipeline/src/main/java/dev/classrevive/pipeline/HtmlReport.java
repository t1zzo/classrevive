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
 *
 * <p>Phase G9: the page follows the desktop application: the same level colors (they used to differ: "close" was green
 * and "per-file" blue here), the black and orange identity in both color schemes, a verdict in plain words, readable
 * status and candidate names, shares in the legend, and a level filter made of radio buttons and CSS {@code :has()}, so
 * the page still runs no script.
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
        boolean compiledAll = verification.status() == VerificationResult.Status.COMPILED;
        out.append("<!doctype html>\n<html lang=\"en\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n")
            .append("<meta name=\"color-scheme\" content=\"light dark\">\n<title>").append(escape(inputName)).append(" &middot; ClassRevive report</title>\n<style>")
            .append(STYLE).append("</style></head><body>\n<main>\n")
            .append("<header class=\"top\">").append(LOGO).append("<div><h1>ClassRevive confidence map</h1>\n<p class=\"meta\">").append(escape(inputName))
            .append(" &middot; SHA-256 <code>").append(escape(inputSha256)).append("</code></p></div></header>\n");
        out.append(verdict(verification, counts, rows.size()));
        out.append("<section class=\"tiles\">")
            .append(tile("Compilation", "<span title=\"" + escape(verification.status().name()) + "\">" + escape(statusName(verification.status())) + "</span>",
                compiledAll ? "the selected sources recompile" : verification.errors() + " javac error(s)", compiledAll ? "ok" : "bad"))
            .append(tile("Signatures", verification.matchedSymbols() + "/" + verification.expectedSymbols(), "classes, fields and methods with the original shape"))
            .append(tile("Method bodies", bodies == 0 ? "n/a" : recovered + "/" + bodies, bodies == 0 ? "no original method body"
                : percent(recovered, bodies) + " of all original bodies identical or equivalent"
                + (verification.status() == VerificationResult.Status.COMPILED ? "" : " (per-file credit)")))
            .append(tile("Selected", escape(candidateName(job.selection().candidate())), job.selection().candidate() + " · "
                + job.selection().repairs().size() + " source repair(s), " + job.selection().methods().size() + " composed method(s)"));
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
        out.append("<h2>Confidence by class</h2>\n<section class=\"bar\" aria-label=\"classes by confidence\">");
        for (var level : Level.values()) {
            int count = counts.get(level);
            if (count > 0) out.append("<span class=\"").append(level.css).append("\" style=\"flex:").append(count).append("\" title=\"")
                .append(level.css).append(": ").append(count).append("\"></span>");
        }
        out.append("</section>\n<ul class=\"legend\">");
        for (var level : Level.values())
            out.append("<li><i class=\"").append(level.css).append("\"></i><b>").append(level.css).append("</b> ").append(counts.get(level))
                .append(rows.isEmpty() || counts.get(level) == 0 ? "" : " <span class=\"share\">" + percent(counts.get(level), rows.size()) + "</span>")
                .append(" &middot; ").append(escape(level.meaning)).append("</li>");
        out.append("</ul>\n");
        // Phase G9: show one level, or the classes that need attention, without a script (radio buttons and CSS :has()).
        if (!rows.isEmpty()) {
            out.append("<fieldset class=\"filter\" aria-label=\"show classes\"><legend>Show</legend>")
                .append(radio("all", "all classes", true)).append(radio("attention", "need attention", false));
            for (var level : Level.values()) out.append(radio(level.css, level.css, false));
            out.append("</fieldset>\n");
        }
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
            .append("<code>selection/selection.txt</code>.</p>\n<footer>Written by ClassRevive. Static analysis only: nothing from the input was run.</footer>\n")
            .append("</main></body></html>\n");
        return out.toString();
    }

    private static String tile(String label, String value, String note) { return tile(label, value, note, null); }

    /** {@code tone} "ok" or "bad" gives the tile a colored stripe, as in the desktop's overview. */
    private static String tile(String label, String value, String note, String tone) {
        return "<div class=\"tile" + (tone == null ? "" : " " + tone) + "\"><span class=\"label\">" + escape(label) + "</span><span class=\"value\">"
            + value + "</span><span class=\"note\">" + escape(note) + "</span></div>";
    }

    private static String radio(String id, String label, boolean checked) {
        return "<input type=\"radio\" name=\"show\" id=\"f-" + id + "\"" + (checked ? " checked" : "") + "><label for=\"f-" + id + "\">"
            + escape(label) + "</label>";
    }

    /** Phase G9: the status as people read it; the report.json name stays in the tile's tooltip. */
    static String statusName(VerificationResult.Status status) {
        return switch (status) {
            case COMPILED -> "Compiles";
            case COMPILE_FAILED -> "Compile errors";
            case UNSUPPORTED_RELEASE -> "Unsupported Java release";
            case WORKER_FAILED -> "Verification failed";
        };
    }

    /** Phase G9: "hybrid-6" reads as "Mix of engines", "recompose-1" as "Methods combined", an engine id as its name. */
    static String candidateName(String candidate) {
        if (candidate == null || candidate.isBlank()) return "none";
        if (candidate.matches("hybrid-\\d+")) return "Mix of engines";
        if (candidate.matches("recompose-\\d+")) return "Methods combined";
        return switch (candidate) {
            case "cfr" -> "CFR"; case "jadx" -> "JADX"; case "vineflower" -> "Vineflower"; case "procyon" -> "Procyon";
            default -> candidate;
        };
    }

    /**
     * Phase G9: one paragraph saying what the result means, with the same reading as the desktop's overview. Never a
     * "recovery %", never a claim about behavior.
     */
    static String verdict(VerificationResult verification, Map<Level,Integer> counts, int total) {
        int exact = counts.getOrDefault(Level.EXACT, 0), close = counts.getOrDefault(Level.CLOSE, 0);
        int attention = counts.getOrDefault(Level.PER_FILE, 0) + counts.getOrDefault(Level.PARTIAL, 0) + counts.getOrDefault(Level.UNVERIFIED, 0);
        String tone, headline, detail;
        switch (verification.status()) {
            case COMPILED -> {
                if (total > 0 && attention == 0 && close == 0) {
                    tone = "ok"; headline = "Everything checks out: the recovered source recompiles, and all " + total + " classes match the original.";
                    detail = "Every signature and every measured method body is identical or equivalent to the original bytecode.";
                } else if (attention == 0) {
                    tone = "ok"; headline = "The recovered source recompiles.";
                    detail = exact + " of " + total + " classes are exact and " + close + " are close: their signatures match and some method bodies differ, often only by compiler variation.";
                } else {
                    tone = "warn"; headline = "The recovered source recompiles, but " + attention + " of " + total + " classes need a closer look.";
                    detail = exact + " classes are exact. Start with the ones marked partial or unverified.";
                }
            }
            case COMPILE_FAILED -> {
                tone = "bad"; headline = "The recovered source does not recompile yet (" + verification.errors() + " javac error(s)).";
                detail = (total - attention) + " of " + total + " classes could still be checked file by file; classes with errors are marked unverified.";
            }
            default -> {
                tone = "warn"; headline = "The result could not be checked: " + statusName(verification.status()) + ".";
                detail = "The sources are there to read, but no class was measured against the original.";
            }
        }
        return "<p class=\"verdict " + tone + "\"><b>" + escape(headline) + "</b> " + escape(detail) + "</p>\n";
    }
    static String percent(long part, long whole) { return String.format(Locale.ROOT, "%.1f%%", part * 100.0 / whole); }
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

    /** The application's logo (orange braces on graphite), inline: no external resource. */
    private static final String LOGO = "<svg class=\"logo\" viewBox=\"0 0 64 64\" aria-hidden=\"true\"><rect x=\"2\" y=\"2\" width=\"60\" height=\"60\" rx=\"9\" "
        + "fill=\"#18181b\" stroke=\"#34343a\" stroke-width=\"1.5\"/><g fill=\"none\" stroke=\"#ff8a1f\" stroke-width=\"5.2\" stroke-linecap=\"round\" "
        + "stroke-linejoin=\"round\"><path d=\"M24 15C17 15 18 22 18 26S16 32 13 32C16 32 18 34 18 38S17 49 24 49\"/>"
        + "<path d=\"M40 15C47 15 46 22 46 26S48 32 51 32C48 32 46 34 46 38S47 49 40 49\"/></g><circle cx=\"32\" cy=\"32\" r=\"3.5\" fill=\"#ff8a1f\"/></svg>";

    /** Phase G9: the desktop's palette: level colors, graphite surfaces in the dark scheme, warm white in the light one. */
    private static final String STYLE = """
        :root{--bg:#f7f7f8;--card:#fff;--fg:#18181b;--muted:#52525b;--faint:#7c7c86;--line:#e1e1e5;--accent:#ff8a1f;--accent-text:#b8530a;
          --exact:#3fb950;--close:#58a6ff;--per-file:#d7b53c;--partial:#b980f0;--unverified:#f0605a;--warn:#e8a317;
          --exact-ink:#1f7a35;--close-ink:#1d5fae;--per-file-ink:#806a12;--partial-ink:#6f3fa8;--unverified-ink:#b3322b;color-scheme:light dark}
        @media (prefers-color-scheme:dark){:root{--bg:#141416;--card:#1b1b1f;--fg:#e7e7ea;--muted:#a8a8b0;--faint:#77777f;--line:#26262b;--accent-text:#ffa552;
          --exact-ink:#5fd16f;--close-ink:#7cbcff;--per-file-ink:#e8c95a;--partial-ink:#cba0f5;--unverified-ink:#ff8a84}}
        *{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--fg);font:15px/1.55 system-ui,-apple-system,"Segoe UI",Roboto,sans-serif}
        main{max-width:1120px;margin:0 auto;padding:28px 16px 56px}
        .top{display:flex;gap:16px;align-items:center;margin-bottom:18px}.logo{width:48px;height:48px;flex:none}
        h1{font-size:24px;font-weight:600;margin:0}h2{font-size:17px;font-weight:600;margin:28px 0 10px}
        .meta{color:var(--muted);font-size:13px;margin:2px 0}code{font-family:ui-monospace,"Cascadia Mono",Consolas,monospace;font-size:13px;overflow-wrap:anywhere}
        .verdict{background:var(--card);border:1px solid var(--line);border-left:4px solid var(--exact);border-radius:12px;padding:14px 18px;margin:0 0 16px;color:var(--muted)}
        .verdict b{display:block;color:var(--fg);font-size:17px;font-weight:600;margin-bottom:2px}.verdict.warn{border-left-color:var(--warn)}.verdict.bad{border-left-color:var(--unverified)}
        .tiles{display:grid;grid-template-columns:repeat(auto-fit,minmax(190px,1fr));gap:12px;margin:16px 0}
        .tile{background:var(--card);border:1px solid var(--line);border-radius:12px;padding:14px 16px;display:flex;flex-direction:column;gap:3px}
        .tile.ok{box-shadow:inset 3px 0 0 var(--exact)}.tile.bad{box-shadow:inset 3px 0 0 var(--unverified)}
        .label{color:var(--faint);font-size:11px;font-weight:600;text-transform:uppercase;letter-spacing:.05em}
        .value{font-size:21px;font-weight:600;overflow-wrap:anywhere}.note{color:var(--muted);font-size:13px}
        .bar{display:flex;gap:2px;height:12px;border-radius:6px;overflow:hidden;margin:8px 0;background:var(--line)}
        .bar span{min-width:2px}.exact{background:var(--exact)}.close{background:var(--close)}.per-file{background:var(--per-file)}
        .partial{background:var(--partial)}.unverified{background:var(--unverified)}
        .legend{list-style:none;padding:0;margin:10px 0 16px;display:grid;gap:5px;font-size:13px;color:var(--muted)}.legend b{color:var(--fg);margin-right:4px}
        .legend i{display:inline-block;width:10px;height:10px;border-radius:50%;margin-right:8px}.share{color:var(--faint)}
        .warning,.reversal{background:var(--card);border:1px solid var(--line);border-left:4px solid var(--warn);border-radius:12px;padding:10px 14px;margin:12px 0;font-size:14px}
        .reversal{border-left-color:var(--accent)}
        .filter{border:0;padding:0;margin:4px 0 14px;display:flex;flex-wrap:wrap;gap:6px;align-items:center}
        .filter legend{float:left;color:var(--faint);font-size:12px;font-weight:600;text-transform:uppercase;letter-spacing:.05em;margin-right:6px}
        .filter input{position:absolute;opacity:0;pointer-events:none}
        .filter label{border:1px solid var(--line);background:var(--card);border-radius:999px;padding:3px 12px;font-size:13px;cursor:pointer;color:var(--muted)}
        .filter input:checked+label{border-color:var(--accent);color:var(--fg);box-shadow:inset 0 0 0 1px var(--accent)}
        .filter input:focus-visible+label{outline:2px solid var(--accent);outline-offset:2px}
        main>details{background:var(--card);border:1px solid var(--line);border-radius:12px;margin:10px 0;padding:6px 14px}
        details.library{opacity:.8}summary{cursor:pointer;padding:6px 0}summary code{font-size:14px;font-weight:600}
        table{width:100%;border-collapse:collapse;font-size:13px;margin:4px 0 8px}
        th,td{text-align:left;padding:6px 8px;border-top:1px solid var(--line);vertical-align:top}
        th{color:var(--faint);font-weight:600;font-size:11px;text-transform:uppercase;letter-spacing:.05em}tbody tr:hover{background:color-mix(in srgb,var(--line) 45%,transparent)}
        td:nth-child(n+3):not(:last-child),th:nth-child(n+3):not(:last-child){text-align:right;font-variant-numeric:tabular-nums}
        details.methods{border:0;padding:0;margin:2px 0;background:none}details.methods summary{color:var(--muted);font-size:12px;padding:0}
        .pill{display:inline-block;padding:1px 9px 1px 20px;border-radius:999px;font-size:12px;font-weight:600;position:relative;background:none;
          box-shadow:inset 0 0 0 1px var(--line)}.pill:before{content:"";position:absolute;left:8px;top:50%;width:7px;height:7px;margin-top:-3.5px;border-radius:50%}
        .pill.exact{color:var(--exact-ink)}.pill.exact:before{background:var(--exact)}.pill.close{color:var(--close-ink)}.pill.close:before{background:var(--close)}
        .pill.per-file{color:var(--per-file-ink)}.pill.per-file:before{background:var(--per-file)}.pill.partial{color:var(--partial-ink)}.pill.partial:before{background:var(--partial)}
        .pill.unverified{color:var(--unverified-ink)}.pill.unverified:before{background:var(--unverified)}
        body:has(#f-attention:checked) tbody tr:has(.pill.exact,.pill.close){display:none}
        body:has(#f-attention:checked) main>details:not(:has(.pill.per-file,.pill.partial,.pill.unverified)){display:none}
        body:has(#f-exact:checked) tbody tr:not(:has(.pill.exact)),body:has(#f-exact:checked) main>details:not(:has(.pill.exact)),
        body:has(#f-close:checked) tbody tr:not(:has(.pill.close)),body:has(#f-close:checked) main>details:not(:has(.pill.close)),
        body:has(#f-per-file:checked) tbody tr:not(:has(.pill.per-file)),body:has(#f-per-file:checked) main>details:not(:has(.pill.per-file)),
        body:has(#f-partial:checked) tbody tr:not(:has(.pill.partial)),body:has(#f-partial:checked) main>details:not(:has(.pill.partial)),
        body:has(#f-unverified:checked) tbody tr:not(:has(.pill.unverified)),body:has(#f-unverified:checked) main>details:not(:has(.pill.unverified)){display:none}
        footer{color:var(--faint);font-size:12px;margin-top:28px;border-top:1px solid var(--line);padding-top:12px}
        @media (max-width:640px){table{display:block;overflow-x:auto}.top{align-items:flex-start}}
        @media print{.filter{display:none}main>details{break-inside:avoid}body{background:#fff;color:#000}}
        """;
}
