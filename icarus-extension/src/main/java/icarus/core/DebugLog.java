package icarus.core;

import burp.api.montoya.MontoyaApi;

import javax.swing.SwingUtilities;
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
 * no file to go dig for) and a plain-text file next to the current project
 * ({@link EvidencePaths#defaultOutputDir}/icarus-debug.log), so a freeze/crash that takes Burp
 * down with it still leaves a trail on disk.
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

    private DebugLog() {}

    public static void initialize(MontoyaApi api, ModuleConfig config) {
        DebugLog.api = api;
        DebugLog.config = config;
        DebugLog.logFile = null; // re-resolved lazily -- project dir may not exist yet at startup
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
                logFile = Path.of(EvidencePaths.defaultOutputDir(api, config)).resolve("icarus-debug.log");
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
