package dev.classrevive.verification;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.List;

/**
 * Phase G4: the javac errors of the selected candidate, kept beside its sources as {@code selection/diagnostics.tsv} in the
 * compiler worker's own format (file, line, column, code, message; tab separated, escaped), so the desktop application can
 * underline them. Messages can name target symbols, which is why they stay out of {@code report.json}.
 */
public final class SelectedDiagnostics {
    public static final String FILE = "diagnostics.tsv";

    private SelectedDiagnostics() {}

    public static void write(Path file, List<VerificationResult.CompilerDiagnostic> diagnostics) throws IOException {
        var text = new StringBuilder();
        for (var diagnostic : diagnostics)
            text.append(SourceRepairWorker.escape(diagnostic.file())).append('\t').append(diagnostic.line()).append('\t').append(diagnostic.column())
                .append('\t').append(SourceRepairWorker.escape(diagnostic.code())).append('\t').append(SourceRepairWorker.escape(diagnostic.message())).append('\n');
        Files.writeString(file, text, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
    }

    /** The diagnostics of {@code file}, validated like the worker's own output; empty when the file does not exist. */
    public static List<VerificationResult.CompilerDiagnostic> read(Path file) throws IOException {
        return CompilerVerifier.diagnostics(file);
    }
}
