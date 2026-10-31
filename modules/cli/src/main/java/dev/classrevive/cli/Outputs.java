package dev.classrevive.cli;

import java.nio.file.*;
import java.util.*;

/** Phase 2CV: default result folders, so a command can run without --output. They are new folders: nothing is overwritten. */
final class Outputs {
    private Outputs() {}

    /** {@code <folder>/<input name without .jar/.class>-<suffix>}, with -2, -3... when taken; not created here. */
    static Path next(Path folder, Path input, String suffix) {
        String name = input.getFileName() == null ? "input" : input.getFileName().toString().replaceAll("(?i)\\.(jar|class)$", "");
        if (name.isBlank()) name = "input";
        Path candidate = folder.resolve(name + "-" + suffix);
        for (int n = 2; Files.exists(candidate, LinkOption.NOFOLLOW_LINKS); n++) candidate = folder.resolve(name + "-" + suffix + "-" + n);
        return candidate;
    }

    /** {@code args} (command, input, options...) with {@code --output} set to a new folder in the current folder. */
    static String[] withDefault(String[] args, String suffix) {
        var list = new ArrayList<>(Arrays.asList(args));
        list.add("--output");
        list.add(next(Path.of("").toAbsolutePath(), Path.of(args[1]), suffix).toString());
        return list.toArray(String[]::new);
    }
}
