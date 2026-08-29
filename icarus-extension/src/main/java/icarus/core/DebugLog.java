package icarus.core;

import burp.api.montoya.MontoyaApi;

import javax.swing.JFileChooser;
import javax.swing.SwingUtilities;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Off-by-default performance/diagnostics logger, toggled by {@code debug.enabled} (Settings ->
 * General, or set directly in the persisted config). When off, every call here is a single
 * volatile-read boolean check -- cheap enough to leave sprinkled through hot paths. When on,
 * every line goes to both Burp's own Extensions -> Output tab (so it shows up immediately,
 * no file to go dig for) and a plain-text {@code icarus-debug.log} in a folder the user is
 * asked to pick the first time it's enabled (pinned as {@code debug.log_dir}; falls back to
 * {@link EvidencePaths#defaultOutputDir} if that prompt is cancelled), so a freeze/crash that
 * takes Burp down with it still leaves a trail on disk somewhere findable.
 *
 * <p>Not a general-purpose logging facade -- {@link #timed} exists specifically to answer "is
 * this running on the EDT, and how long did it take", since a Swing freeze is almost always one
 * of those two things.
 */
public final class DebugLog {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    private static volatile MontoyaApi api;
    private static volatile ModuleConfig config;
    private static volatile Path logFile;
    private static volatile boolean logDirPrompted;

    private DebugLog() {}

    public static void initialize(MontoyaApi api, ModuleConfig config) {
        DebugLog.api = api;
        DebugLog.config = config;
        DebugLog.logFile = null; // re-resolved lazily -- project dir may not exist yet at startup
        DebugLog.logDirPrompted = false;
    }

    public static boolean isEnabled() {
        return config != null && config.getBool("debug.enabled", false);
    }

    /** Plain message, e.g. counts/state that don't fit the timed-block shape. */
    public static void log(String message) {
        if (!isEnabled()) return;
        write(format(message));
    }

    /**
     * Runs {@code action}, and if debug mode is on, logs how long it took and whether it ran on
     * the EDT. A block routinely taking more than a few milliseconds *on the EDT* is exactly the
     * kind of thing that adds up into a freeze once it fires once per finding.
     */
    public static void timed(String label, Runnable action) {
        if (!isEnabled()) {
            action.run();
            return;
        }
        boolean edt = SwingUtilities.isEventDispatchThread();
        long start = System.nanoTime();
        try {
            action.run();
        } finally {
            long ms = (System.nanoTime() - start) / 1_000_000;
            write(format(label + " took " + ms + "ms" + (edt ? " [EDT]" : " [bg:" + Thread.currentThread().getName() + "]")));
        }
    }

    /**
     * Picks where {@code icarus-debug.log} goes. Once per debug session, if the user hasn't
     * pinned a folder yet ({@code debug.log_dir}), asks them to choose one — because the
     * automatic "next to the project file" location only works when Burp was launched from the
     * CLI with {@code --project-file=}, and otherwise silently lands in {@code ~/icarus-reports}
     * where nobody thinks to look. Cancelling falls back to that automatic location.
     */
    private static Path resolveLogFile() {
        String dir = config.getString("debug.log_dir", "");
        if (dir.isBlank() && !logDirPrompted) {
            logDirPrompted = true;
            dir = promptForLogDir();
            if (!dir.isBlank()) {
                config.set("debug.log_dir", dir);
                try {
                    api.persistence().extensionData().setString("config", config.serialize());
                } catch (Exception ignored) { }
            }
        }
        if (dir.isBlank()) dir = EvidencePaths.defaultOutputDir(api, config);
        return Path.of(dir).resolve("icarus-debug.log");
    }

    private static String promptForLogDir() {
        final String[] picked = {""};
        Runnable ask = () -> {
            JFileChooser fc = new JFileChooser(EvidencePaths.defaultOutputDir(api, config));
            fc.setDialogTitle("ICARUS — choose a folder for the debug log (icarus-debug.log)");
            fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            java.awt.Frame parent = api == null ? null : api.userInterface().swingUtils().suiteFrame();
            if (fc.showSaveDialog(parent) == JFileChooser.APPROVE_OPTION) {
                File d = fc.getSelectedFile();
                if (d != null) picked[0] = d.getAbsolutePath();
            }
        };
        try {
            if (SwingUtilities.isEventDispatchThread()) ask.run();
            else SwingUtilities.invokeAndWait(ask);
        } catch (Exception ignored) { }
        return picked[0];
    }

    private static String format(String message) {
        return "[" + LocalDateTime.now().format(TS) + "] " + message;
    }

    private static synchronized void write(String line) {
        if (api != null) {
            try {
                api.logging().logToOutput("[ICARUS-DEBUG] " + line);
            } catch (Exception ignored) {
                // Burp's own logger shouldn't be able to take us down mid-diagnosis.
            }
        }
        try {
            if (logFile == null) {
                logFile = resolveLogFile();
            }
            // SYNC: each line is force-flushed to physical disk before this call returns, so a
            // freeze or hard JVM crash mid-diagnosis still leaves every line up to the hang on
            // disk (the whole point of this logger). open/append/close per line, not a buffered
            // writer held open, for the same reason.
            Files.writeString(logFile, line + System.lineSeparator(),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.SYNC);
        } catch (IOException ignored) {
            // Best-effort -- a logging failure must never be the thing that breaks the feature
            // being diagnosed.
        }
    }
}
