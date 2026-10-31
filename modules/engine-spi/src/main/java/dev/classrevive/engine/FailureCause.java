package dev.classrevive.engine;

import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Describes a host-side worker failure and its chained causes for engine.log: exception types, the first frames
 * that locate them, the OS reason of file-system errors and a redacted location. Absolute paths are replaced and
 * target-derived path segments (package and class names) are never written.
 */
final class FailureCause {
    private static final int MAX_DEPTH = 8, MAX_MESSAGE = 200;
    private static final Pattern ENGINE_SEGMENT = Pattern.compile("[a-z]+(-[a-z]+)*(-?[0-9]+)?");
    private FailureCause() {}

    static String describe(Throwable error, Path directory, Path input) {
        var lines = new ArrayList<String>();
        var seen = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
        for (Throwable current = error; current != null && seen.add(current) && lines.size() < MAX_DEPTH;
             current = current.getCause()) {
            var line = new StringBuilder(lines.isEmpty() ? "[classrevive] host-side worker failure: " : "[classrevive]   caused by: ");
            line.append(current.getClass().getName());
            if (current instanceof FileSystemException file) {
                line.append(" (reason=").append(file.getReason() == null ? "none" : clean(file.getReason(), directory, input))
                    .append(", location=").append(location(file.getFile(), directory));
                if (file.getOtherFile() != null) line.append(", other=").append(location(file.getOtherFile(), directory));
                line.append(')');
            } else {
                String message = current.getMessage();
                if (message != null && !(current.getCause() != null && message.equals(String.valueOf(current.getCause()))))
                    line.append(": ").append(clean(message, directory, input));
            }
            line.append(frames(current));
            lines.add(line.toString());
        }
        return String.join(System.lineSeparator(), lines) + System.lineSeparator();
    }

    /** Origin frame plus the first ClassRevive frame; frames name JDK/engine/ClassRevive code, never the target. */
    private static String frames(Throwable error) {
        var trace = error.getStackTrace();
        if (trace.length == 0) return "";
        var text = new StringBuilder(" at ").append(frame(trace[0]));
        for (var element : trace)
            if (element != trace[0] && element.getClassName().startsWith("dev.classrevive.")) {
                text.append(" via ").append(frame(element)); break;
            }
        return text.toString();
    }
    private static String frame(StackTraceElement element) {
        return element.getClassName() + "." + element.getMethodName() + (element.getLineNumber() > 0 ? ":" + element.getLineNumber() : "");
    }

    /**
     * Keeps the runner-owned folder and an engine-state subtree (a dot-prefixed directory such as {@code .jadx-state}
     * and up to two lowercase engine-named levels below it); every other segment is counted, not written.
     */
    static String location(String file, Path directory) {
        if (file == null) return "unknown";
        Path path;
        try { path = Path.of(file).toAbsolutePath().normalize(); }
        catch (InvalidPathException error) { return "unparsable"; }
        Path base = directory.toAbsolutePath().normalize();
        if (!path.startsWith(base)) return "<outside run directory>";
        Path relative = base.relativize(path);
        if (relative.getNameCount() == 0 || relative.toString().isEmpty()) return "<run>";
        var kept = new ArrayList<String>(List.of("<run>", relative.getName(0).toString()));
        int index = 1, engineLevels = -1;
        for (; index < relative.getNameCount(); index++) {
            String segment = relative.getName(index).toString();
            if (engineLevels < 0 && segment.startsWith(".") && segment.length() > 1 && ENGINE_SEGMENT.matcher(segment.substring(1)).matches())
                engineLevels = 0;
            else if (engineLevels >= 0 && engineLevels < 2 && ENGINE_SEGMENT.matcher(segment).matches()) engineLevels++;
            else break;
            kept.add(segment);
        }
        int redacted = relative.getNameCount() - index;
        return String.join("/", kept) + (redacted > 0 ? "/<" + redacted + " redacted segment" + (redacted == 1 ? "" : "s") + ">" : "");
    }

    private static String clean(String message, Path directory, Path input) {
        String text = message.replace('\r', ' ').replace('\n', ' ');
        text = redact(text, input, "<input>");
        text = redact(text, directory, "<run>");
        text = redact(text, Path.of(System.getProperty("java.io.tmpdir")), "<tmp>");
        text = redact(text, Path.of(System.getProperty("user.home")), "<home>");
        return text.length() > MAX_MESSAGE ? text.substring(0, MAX_MESSAGE) + "…" : text;
    }
    /** Replaces a known absolute path and everything that follows it up to whitespace (the target-derived tail). */
    private static String redact(String text, Path path, String placeholder) {
        if (path == null) return text;
        String absolute = path.toAbsolutePath().normalize().toString();
        if (absolute.length() < 3) return text;
        return Pattern.compile(Pattern.quote(absolute) + "\\S*", Pattern.CASE_INSENSITIVE).matcher(text)
            .replaceAll(java.util.regex.Matcher.quoteReplacement(placeholder + "/…"));
    }
}
