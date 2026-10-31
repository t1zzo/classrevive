package dev.classrevive.intake;

import dev.classrevive.model.Inventory;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Policy for class entries under {@code META-INF/versions/N/} (multi-release JARs, JAR File Specification).
 * A variant is accepted only when N is a decimal release of at least 9 without leading zeros and the rest of the
 * path is exactly the class's internal name plus {@code .class}. Every other path/identity mismatch stays an error.
 * Accepted variants are not decompiled or put on a compiler classpath; callers report them instead.
 */
public final class MultiRelease {
    private static final String PREFIX = "META-INF/versions/";
    private static final Pattern VERSIONED = Pattern.compile("META-INF/versions/([1-9][0-9]{0,3})/(.+)");
    private MultiRelease() {}

    /** A class entry that is a valid versioned variant; {@code hasBase} says whether the base JAR has the same class. */
    public record Variant(String entry, int release, String className, boolean hasBase) {}

    public static boolean versioned(String entryName) { return entryName.startsWith(PREFIX); }

    /** The variant for this class entry, or {@code null} when it is not a valid versioned variant. */
    public static Variant variant(Inventory.Entry entry, Set<String> baseClasses) {
        if (entry.classInfo() == null) return null;
        var matcher = VERSIONED.matcher(entry.name());
        if (!matcher.matches()) return null;
        int release = Integer.parseInt(matcher.group(1));
        String className = entry.classInfo().name();
        if (release < 9 || !matcher.group(2).equals(className + ".class")) return null;
        return new Variant(entry.name(), release, className, baseClasses.contains(className));
    }

    /** All valid variants among the inventory's class entries, in entry order. Invalid ones are not listed. */
    public static List<Variant> variants(Inventory inventory) {
        var base = new HashSet<String>();
        for (var entry : inventory.entries())
            if (entry.classInfo() != null && entry.name().equals(entry.classInfo().name() + ".class")) base.add(entry.classInfo().name());
        var result = new ArrayList<Variant>();
        for (var entry : inventory.entries()) {
            var variant = variant(entry, base);
            if (variant != null) result.add(variant);
        }
        return List.copyOf(result);
    }
}
