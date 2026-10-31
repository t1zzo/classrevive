package dev.classrevive.pipeline;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipFile;

/**
 * Phase 2CX: the Android framework classes of a local Android SDK, for javac only. An APK does not contain
 * {@code android.*}; with the SDK's {@code platforms/android-N/android.jar} on the class path the decompiled sources compile
 * against the real API instead of stubs inferred from their uses. The SDK is never downloaded and its jar never run or
 * redistributed: it is looked up in {@code -Dclassrevive.android.sdk}, {@code ANDROID_HOME}, {@code ANDROID_SDK_ROOT} and the
 * default install folders. The platform matching the app's {@code targetSdkVersion} (Phase 2CY, from the decoded manifest)
 * is preferred, then the oldest newer one, then the newest one.
 */
final class AndroidSdk {
    private AndroidSdk() {}

    static final String PROPERTY = "classrevive.android.sdk";
    private static final Pattern PLATFORM = Pattern.compile("android-(\\d+)(?:[.-].*)?");

    /** A platform jar of the SDK, its API level and where the SDK was found, e.g. {@code ANDROID_HOME}. */
    record Platform(Path jar, int apiLevel, String foundBy) {}

    static Optional<Platform> find() { return find(null); }

    /** {@code target}: the app's targetSdkVersion, or null. */
    static Optional<Platform> find(Integer target) {
        var roots = new LinkedHashMap<String, String>();
        put(roots, System.getProperty(PROPERTY), "-D" + PROPERTY);
        put(roots, System.getenv("ANDROID_HOME"), "ANDROID_HOME");
        put(roots, System.getenv("ANDROID_SDK_ROOT"), "ANDROID_SDK_ROOT");
        String local = System.getenv("LOCALAPPDATA"), home = System.getProperty("user.home");
        if (local != null) put(roots, Path.of(local, "Android", "Sdk").toString(), "the default SDK folder");
        if (home != null) {
            put(roots, Path.of(home, "Library", "Android", "sdk").toString(), "the default SDK folder");
            put(roots, Path.of(home, "Android", "Sdk").toString(), "the default SDK folder");
        }
        for (var root : roots.entrySet()) {
            var platform = choose(platforms(Path.of(root.getKey()), root.getValue()), target);
            if (platform.isPresent()) return platform;
        }
        return Optional.empty();
    }

    /** The target level if installed, else the oldest newer one, else the newest. */
    static Optional<Platform> choose(List<Platform> installed, Integer target) {
        if (installed.isEmpty()) return Optional.empty();
        if (target != null) {
            var atLeast = installed.stream().filter(p -> p.apiLevel() >= target).min(Comparator.comparingInt(Platform::apiLevel));
            if (atLeast.isPresent()) return atLeast;
        }
        return installed.stream().max(Comparator.comparingInt(Platform::apiLevel));
    }

    static Optional<Platform> newest(Path sdk, String foundBy) { return choose(platforms(sdk, foundBy), null); }

    private static void put(Map<String, String> roots, String path, String by) {
        if (path != null && !path.isBlank()) roots.putIfAbsent(path, by);
    }

    /** Every {@code platforms/android-N} folder of the SDK that holds an android.jar. */
    static List<Platform> platforms(Path sdk, String foundBy) {
        Path platforms = sdk.resolve("platforms");
        if (!Files.isDirectory(platforms)) return List.of();
        var found = new ArrayList<Platform>();
        try (Stream<Path> folders = Files.list(platforms)) {
            for (Path folder : folders.toList()) {
                var matcher = PLATFORM.matcher(folder.getFileName().toString());
                Path jar = folder.resolve("android.jar");
                if (matcher.matches() && Files.isRegularFile(jar, LinkOption.NOFOLLOW_LINKS))
                    found.add(new Platform(jar, Integer.parseInt(matcher.group(1)), foundBy));
            }
        } catch (IOException | NumberFormatException unreadable) {
            return List.of();
        }
        return found;
    }

    /** Whether one of the jars already holds the framework (the user passed android.jar with -cp). */
    static boolean supplied(List<Path> dependencies) {
        for (Path jar : dependencies) {
            try (var zip = new ZipFile(jar.toFile())) {
                if (zip.getEntry("android/app/Activity.class") != null) return true;
            } catch (IOException unreadable) {
                // Not a jar the framework could be in.
            }
        }
        return false;
    }
}
