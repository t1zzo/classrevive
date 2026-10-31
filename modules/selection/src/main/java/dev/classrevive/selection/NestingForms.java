package dev.classrevive.selection;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;
import dev.classrevive.verification.SourceText;

/**
 * Phase 2BX: source files grouped by outer class ("family"), so a hybrid never holds one binary class in two forms.
 * Without an {@code InnerClasses} attribute, engines disagree: CFR, Vineflower and Procyon write {@code a/B$C.java} as a
 * top-level file, while JADX nests {@code C} inside {@code a/B.java} by name. Mixing JADX's {@code B.java} with another
 * engine's {@code B$C.java} declares {@code a/B$C} twice ({@code compiler.err.same.binary.name}).
 *
 * <p>A family is the file stem up to the first {@code $} of its simple name. Two file sets hold a family in different
 * forms when exactly one of them has {@code $} files for it and the other's outer file nests them: it never names them
 * by binary name ({@code B$}) outside comments and strings, and it declares every named member ({@code class C}).
 * An outer file that refers to {@code B$C} or lacks a declaration of {@code C} is a top-level form whose member files
 * are missing (an engine that failed on them), never a nested form.
 */
final class NestingForms {
    private final Map<Path,String> code = new HashMap<>();

    /** {@code a/B$C$D.java} and {@code a/B.java} belong to {@code a/B}. */
    static String family(String file) {
        String stem = file.endsWith(".java") ? file.substring(0, file.length() - ".java".length()) : file;
        int slash = stem.lastIndexOf('/'), dollar = stem.indexOf('$', slash + 1);
        return dollar <= slash + 1 ? stem : stem.substring(0, dollar);
    }
    /** The {@code $} files of a family ({@code a/B$...java}); '$' sorts before '.', and '%' right after '$'. */
    static SortedMap<String,Path> members(NavigableMap<String,Path> files, String family) {
        return files.subMap(family + "$", true, family + "%", false);
    }
    /** Every file of a family: the outer file and its {@code $} files. */
    static SortedMap<String,Path> all(NavigableMap<String,Path> files, String family) {
        var result = new TreeMap<>(members(files, family));
        Path outer = files.get(family + ".java");
        if (outer != null) result.put(family + ".java", outer);
        return result;
    }
    /** True when {@code mix} and {@code pick} hold {@code family} in different nesting forms. */
    boolean otherForm(NavigableMap<String,Path> mix, NavigableMap<String,Path> pick, String family) throws IOException {
        var mixMembers = members(mix, family); var pickMembers = members(pick, family);
        if (mixMembers.isEmpty() == pickMembers.isEmpty()) return false;
        var nesting = mixMembers.isEmpty() ? mix : pick;
        Path outer = nesting.get(family + ".java");
        return outer != null && nests(outer, family, (mixMembers.isEmpty() ? pickMembers : mixMembers).keySet());
    }
    private boolean nests(Path outer, String family, Set<String> memberFiles) throws IOException {
        String text = code.get(outer);
        if (text == null) { text = SourceText.mask(Files.readString(outer)); code.put(outer, text); }
        String simple = family.substring(family.lastIndexOf('/') + 1);
        if (text.contains(simple + "$")) return false;
        for (var file : memberFiles) {
            String stem = file.substring(0, file.length() - ".java".length());
            String name = stem.substring(stem.lastIndexOf('$') + 1);
            if (name.isEmpty() || name.chars().allMatch(Character::isDigit)) continue; // anonymous: no declaration to find
            if (!Pattern.compile("\\b(class|interface|enum|record)\\s+" + Pattern.quote(name) + "\\b").matcher(text).find()) return false;
        }
        return true;
    }
}
