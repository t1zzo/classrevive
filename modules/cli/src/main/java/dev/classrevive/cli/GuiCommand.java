package dev.classrevive.cli;

import java.io.PrintStream;
import java.nio.file.*;

/** Phase G3: {@code classrevive gui [file-or-result-folder]} opens the desktop application. */
final class GuiCommand {
    private GuiCommand() {}

    /** 0 when the window is starting (the process then lives on); 1 without a display, 2 for bad arguments. */
    static int start(String[] args, PrintStream err) {
        if (args.length > 2) { err.println("Usage: classrevive gui [file.jar|file.class|result-folder]"); return 2; }
        Path file = null;
        if (args.length == 2) {
            try { file = Path.of(args[1]); }
            catch (InvalidPathException invalid) { err.println("Invalid path."); return 2; }
            if (!Files.exists(file)) { err.println("No such file or folder: " + Main.safe(args[1])); return 2; }
        }
        if (java.awt.GraphicsEnvironment.isHeadless()) {
            err.println("The desktop application needs a graphical display. Use the other commands here (classrevive --help).");
            return 1;
        }
        dev.classrevive.gui.ClassReviveApp.launch(file);
        return 0;
    }
}
