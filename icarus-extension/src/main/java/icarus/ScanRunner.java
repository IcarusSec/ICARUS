package icarus;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.handler.HttpResponseReceived;
import burp.api.montoya.http.message.HttpRequestResponse;
import icarus.core.Finding;
import icarus.core.IcarusModule;
import icarus.core.I18n;
import icarus.core.ModuleConfig;
import icarus.modules.PassiveErrorModule;
import icarus.modules.SensitiveHeaderModule;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Owns scan execution: the background executor, running modules against a target,
 * the WAF-detection prompt, and the live progress window. Extracted from Orchestrator
 * so Burp-menu wiring and finding bookkeeping don't need to know how a scan runs.
 *
 * Results are handed back via {@code onFindings} rather than acted on directly, so this
 * class has no opinion on how (or whether) findings get presented.
 */
public final class ScanRunner {

    private final MontoyaApi api;
    private final List<IcarusModule> modules;
    private final ModuleConfig config;
    private final ExecutorService executor;
    private final BiConsumer<List<Finding>, Boolean> onFindings;

    // The single-thread executor means only one of these ever runs at a time — this just
    // needs to track it so a Stop click has something to cancel.
    private volatile Future<?> currentTask;

    // Static because ScanRunner is a singleton (one instance per Orchestrator) and modules
    // (icarus.modules.*) need to poll this without each one being handed a ScanRunner reference.
    private static volatile boolean paused = false;
    private static volatile boolean stopRequested = false;
    private static volatile boolean skipRequested = false;
    private static final Object PAUSE_LOCK = new Object();

    public static boolean isPaused() {
        return paused;
    }

    public static boolean isStopRequested() {
        return stopRequested;
    }

    public static boolean isSkipRequested() {
        return skipRequested;
    }

    public static boolean isCancelled() {
        return Thread.currentThread().isInterrupted() || stopRequested || skipRequested;
    }

    /** Toggled by the live log window's Pause button. */
    public static void togglePause() {
        synchronized (PAUSE_LOCK) {
            paused = !paused;
            PAUSE_LOCK.notifyAll();
        }
    }

    /**
     * Blocks the calling thread while paused. Modules call this at the same per-request-loop
     * points that already check {@code Thread.currentThread().isInterrupted()} for Stop, so
     * pausing has the same "between requests, not mid-request" granularity Stop has.
     * A Stop click's {@code Future.cancel(true)} interrupts this wait immediately, same as it
     * would a plain {@code Thread.sleep}.
     */
    public static void waitIfPaused() {
        synchronized (PAUSE_LOCK) {
            while (paused) {
                try {
                    PAUSE_LOCK.wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    public ScanRunner(MontoyaApi api, List<IcarusModule> modules, ModuleConfig config,
                       BiConsumer<List<Finding>, Boolean> onFindings) {
        this.api = api;
        this.modules = modules;
        this.config = config;
        this.onFindings = onFindings;
        this.executor = Executors.newSingleThreadExecutor(r -> {
            var t = new Thread(r, "ICARUS-scan");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Interrupts whatever's currently running. Modules poll {@code Thread.currentThread()
     * .isInterrupted()} in their per-request loops rather than relying on the interrupt to
     * abort an in-flight {@code sendRequest()} call (Montoya's HTTP call isn't guaranteed to
     * be interrupt-aware) — so this stops promptly between requests, not necessarily
     * mid-request. {@link RateLimitModule} additionally shuts down its own internal thread
     * pool, since that runs on separate threads this interrupt wouldn't otherwise reach.
     */
    public void stopCurrent() {
        stopRequested = true;
        if (currentTask != null && !currentTask.isDone()) {
            currentTask.cancel(true); // This sets the interrupt flag
        }
        // Don't leave the next run starting out paused because this one was stopped mid-pause.
        if (paused) togglePause();
    }

    public void shutdown() {
        stopCurrent();
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    public void runScan(HttpRequestResponse target, boolean isManual) {
        stopRequested = false;
        skipRequested = false;
        currentTask = executor.submit(() -> {
            Thread.interrupted(); // clear any stale flag left by a previous cancellation
            try {
                doScan(target, isManual);
            } catch (Exception e) {
                api.logging().logToError("ICARUS scan failed: " + e);
            }
        });
    }

    public void runModule(IcarusModule module, HttpRequestResponse target, boolean isManual) {
        stopRequested = false;
        skipRequested = false;
        currentTask = executor.submit(() -> {
            Thread.interrupted();
            try {
                runSingleModule(module, target, isManual);
            } catch (Exception e) {
                api.logging().logToError("ICARUS " + module.name() + " failed: " + e);
            }
        });
    }

    /** Runs an arbitrary task on the same background executor used for scans. */
    public void runAsync(Runnable task) {
        currentTask = executor.submit(() -> {
            Thread.interrupted();
            try {
                task.run();
            } catch (Exception e) {
                api.logging().logToError("ICARUS background task failed: " + e);
            }
        });
    }

    /** Runs the passive (background) modules against a response, off the request-handling thread. */
    public void runPassiveScan(HttpResponseReceived response, Consumer<List<Finding>> onPassiveFindings) {
        runAsync(() -> {
            for (var module : modules) {
                List<Finding> passiveFindings = null;
                if (module instanceof SensitiveHeaderModule shm) {
                    passiveFindings = shm.analyzeResponse(response, config);
                } else if (module instanceof PassiveErrorModule pem) {
                    passiveFindings = pem.analyzeResponse(response, config);
                }
                if (passiveFindings != null && !passiveFindings.isEmpty()) {
                    onPassiveFindings.accept(passiveFindings);
                }
            }
        });
    }

    private void doScan(HttpRequestResponse target, boolean isManual) {
        Consumer<String> popupLogger = createLiveLogWindow("ICARUS — Scan Progress");
        Consumer<String> log = msg -> {
            api.logging().logToOutput(msg);
            popupLogger.accept(msg);
        };
        Consumer<String> error = msg -> {
            api.logging().logToError(msg);
            popupLogger.accept("[ERROR] " + msg);
        };
        List<Finding> findings = new ArrayList<>();

        log.accept("════════════════════════════════════════════════");
        log.accept("ICARUS scan started — " + target.request().method() + " " + target.request().path());

        WafDecision decision = maybePromptForWaf(target, log);
        ModuleConfig scanConfig = safeModeConfig(decision);

        for (var module : modules) {
            skipRequested = false;
            waitIfPaused();
            if (Thread.currentThread().isInterrupted() || stopRequested) {
                log.accept("ICARUS scan stopped by user.");
                break;
            }
            if (!isModuleEnabled(module) || !module.includeInBulkScan()) continue;

            log.accept("──── Running: " + module.name() + " ────");

            try {
                var moduleFindings = module.run(target, scanConfig, verboseLogger(log, config));
                findings.addAll(moduleFindings);
                log.accept(module.name() + " → " + moduleFindings.size() + " findings");
            } catch (Exception e) {
                error.accept(module.name() + " failed: " + e.getMessage());
                icarus.core.DebugLog.log(module.name() + " threw in bulk scan: " + stackTrace(e));
            }
        }

        onFindings.accept(findings, isManual);

        log.accept("ICARUS scan complete — " + findings.size() + " total findings.");
        log.accept("════════════════════════════════════════════════");
    }

    private void runSingleModule(IcarusModule module, HttpRequestResponse target, boolean isManual) {
        Consumer<String> popupLogger = createLiveLogWindow("ICARUS — " + module.name() + " Progress");
        Consumer<String> logger = msg -> {
            popupLogger.accept(msg);
            api.logging().logToOutput(msg);
        };
        logger.accept("ICARUS → Running " + module.name());
        icarus.core.DebugLog.log("ScanRunner.runSingleModule: " + module.name() + " starting, isManual=" + isManual);
        WafDecision decision = module.sendsActivePayloads()
                ? maybePromptForWaf(target, logger)
                : new WafDecision(false, Optional.empty(), false);
        ModuleConfig scanConfig = safeModeConfig(decision);
        List<Finding> findings;
        try {
            findings = module.run(target, scanConfig, verboseLogger(logger, config));
        } catch (Exception e) {
            icarus.core.DebugLog.log("ScanRunner.runSingleModule: " + module.name() + " threw: " + stackTrace(e));
            logger.accept("ICARUS → " + module.name() + " failed: " + e);
            api.logging().logToError(module.name() + " failed: " + e);
            return;
        }
        onFindings.accept(findings, isManual);
        String status = Thread.currentThread().isInterrupted() ? "stopped by user" : "complete";
        icarus.core.DebugLog.log("ScanRunner.runSingleModule: " + module.name() + " " + status + " — " + findings.size() + " findings");
        logger.accept("ICARUS → " + module.name() + " " + status + " — " + findings.size() + " findings.");
    }

    /** Outcome of the baseline WAF fingerprint + user prompt. */
    private record WafDecision(boolean wafDetected, Optional<icarus.modules.WafVendor> vendor, boolean safeMode) {}

    private static final String[] SAFE_MODE_DISABLED_TECHNIQUES =
            {"pv.sqli", "pv.sqli_time", "pv.xss", "pv.path_traversal", "pv.cmdi", "pv.ssti"};

    /**
     * Fingerprints the baseline response for a WAF/CDN and, if one is found, asks the user
     * whether to run the full payload set or a scoped Safe Mode. Prompts once per scan.
     * Returns a no-op decision when detection is disabled or there's no baseline response.
     */
    private WafDecision maybePromptForWaf(HttpRequestResponse target, Consumer<String> log) {
        boolean enabled = config.getBool("waf.detect", config.getBool("waf.detect_akamai", true));
        if (!enabled || target.response() == null) {
            return new WafDecision(false, Optional.empty(), false);
        }

        Optional<icarus.modules.WafVendor> vendor = icarus.modules.WafFingerprint.detect(target.response());
        if (vendor.isEmpty()) {
            return new WafDecision(false, Optional.empty(), false);
        }

        String friendlyName = friendlyVendorName(vendor.get());

        // A WAF/CDN in front of the target (Cloudflare especially) is extremely common and
        // usually isn't actively blocking. Only interrupt with the Safe-Mode prompt when the
        // baseline response itself looks like a block page; otherwise just note it and run
        // normally — the ParamValidator 403 backoff prompt is the safety net if it does start
        // blocking mid-scan.
        if (!icarus.modules.WafFingerprint.looksBlocked(target.response())) {
            log.accept(I18n.t("settings.waf.log.present", friendlyName));
            return new WafDecision(true, vendor, false);
        }

        int[] choiceHolder = { -1 };
        runOnEdtAndWait(() -> choiceHolder[0] = JOptionPane.showOptionDialog(
                api.userInterface().swingUtils().suiteFrame(),
                I18n.t("settings.waf.modal.msg", friendlyName),
                I18n.t("settings.waf.modal.title"),
                JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE,
                null,
                new String[]{ I18n.t("settings.waf.modal.run_default"), I18n.t("settings.waf.modal.safe_mode") },
                I18n.t("settings.waf.modal.safe_mode")));

        // Default to Safe Mode on anything that isn't an explicit "Run Default" (index 0).
        boolean safeMode = choiceHolder[0] != 0;
        log.accept(I18n.t("settings.waf.log.detected", friendlyName,
                I18n.t(safeMode ? "settings.waf.log.safe_mode" : "settings.waf.log.default_mode")));
        return new WafDecision(true, vendor, safeMode);
    }

    private static String friendlyVendorName(icarus.modules.WafVendor vendor) {
        return switch (vendor.name()) {
            case "CLOUDFLARE"        -> "Cloudflare";
            case "AKAMAI"            -> "Akamai";
            case "AWS_WAF"           -> "AWS WAF";
            case "IMPERVA_INCAPSULA" -> "Imperva Incapsula";
            case "F5_BIGIP_ASM"      -> "F5 BIG-IP";
            default                  -> "a WAF/CDN";
        };
    }

    /** Shared scan config for a decision: the real config unless Safe Mode, then a scoped copy. */
    private ModuleConfig safeModeConfig(WafDecision decision) {
        if (!decision.safeMode()) return config;
        ModuleConfig scanConfig = config.copy();
        scanConfig.set("pv.depth", "LIGHT");
        scanConfig.set("pv.max_mutations", 0);
        for (String t : SAFE_MODE_DISABLED_TECHNIQUES) scanConfig.set(t, false);
        return scanConfig;
    }

    private static String stackTrace(Throwable t) {
        java.io.StringWriter sw = new java.io.StringWriter();
        t.printStackTrace(new java.io.PrintWriter(sw));
        return sw.toString();
    }

    /**
     * Gates a module's step-by-step logging behind the "Verbose Mode" setting (default on),
     * without every module needing to check the config itself. Scan start/complete/phase-banner
     * lines logged directly via {@code context::log} elsewhere are unaffected.
     */
    private static Consumer<String> verboseLogger(Consumer<String> logger, ModuleConfig config) {
        return config.getBool("verbose.enabled", true) ? logger : msg -> {};
    }

    private Consumer<String> createLiveLogWindow(String title) {
        JTextArea[] textAreaHolder = new JTextArea[1];

        runOnEdtAndWait(() -> {
            JDialog frame = new JDialog(api.userInterface().swingUtils().suiteFrame(), title, false);
            frame.setSize(800, 400);
            frame.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
            frame.setLocationRelativeTo(null); // Center on screen

            JTextArea textArea = new JTextArea();
            textArea.setEditable(false);
            textArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
            api.userInterface().applyThemeToComponent(textArea);

            JScrollPane scrollPane = new JScrollPane(textArea);
            scrollPane.setBorder(BorderFactory.createEmptyBorder());
            frame.add(scrollPane, BorderLayout.CENTER);

            JButton btnPause = new JButton(isPaused() ? "Resume" : "Pause");
            btnPause.addActionListener(e -> {
                togglePause();
                btnPause.setText(isPaused() ? "Resume" : "Pause");
                textArea.append(isPaused() ? "Paused — will resume before the next request.\n" : "Resumed.\n");
                textArea.setCaretPosition(textArea.getDocument().getLength());
            });

            JButton btnSkip = new JButton("Skip Current");
            btnSkip.addActionListener(e -> {
                textArea.append("Skipping current module...\n");
                textArea.setCaretPosition(textArea.getDocument().getLength());
                skipRequested = true;
                // currentTask.cancel(true) would kill the whole scan, so we interrupt the thread manually
                // But since we don't have the thread ref, we can just let the flag do the work on next iteration,
                // or if it's blocking, we wait for it. Let's just rely on the flag.
            });

            JButton btnStop = new JButton("Stop");
            btnStop.addActionListener(e -> {
                textArea.append("Stopping — cancelling current test...\n");
                textArea.setCaretPosition(textArea.getDocument().getLength());
                btnStop.setEnabled(false);
                btnPause.setEnabled(false);
                btnSkip.setEnabled(false);
                stopCurrent();
            });
            
            JPanel bottomBar = new JPanel(new FlowLayout(FlowLayout.RIGHT));
            bottomBar.add(btnPause);
            bottomBar.add(btnSkip);
            bottomBar.add(btnStop);
            frame.add(bottomBar, BorderLayout.SOUTH);

            api.userInterface().applyThemeToComponent(frame);
            frame.setVisible(true);
            textAreaHolder[0] = textArea;
        });

        JTextArea textArea = textAreaHolder[0];
        return (msg) -> {
            if (textArea == null || !textArea.isDisplayable()) return;
            SwingUtilities.invokeLater(() -> {
                if (!textArea.isDisplayable()) return;
                textArea.append(msg + "\n");
                textArea.setCaretPosition(textArea.getDocument().getLength());
            });
        };
    }

    /**
     * Runs {@code r} on the EDT and blocks until it completes. Safe to call whether the
     * caller is already on the EDT (runs inline — invokeAndWait would throw an Error there)
     * or on a background thread (dispatches via invokeAndWait).
     */
    private void runOnEdtAndWait(Runnable r) {
        if (SwingUtilities.isEventDispatchThread()) {
            r.run();
            return;
        }
        try {
            SwingUtilities.invokeAndWait(r);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            api.logging().logToError("EDT task failed: " + e.getMessage());
        }
    }

    private boolean isModuleEnabled(IcarusModule module) {
        return switch (module.name()) {
            case "ParamValidator"    -> config.getBool("pv.enabled", true);
            case "HTTP Verb Tester"  -> config.getBool("hv.enabled", true);
            case "JWT Checker"       -> config.getBool("jwt.enabled", true);
            case "Sensitive Headers" -> config.getBool("sh.enabled", true);
            case "Postman Export"    -> config.getBool("export.enabled", true);
            case "Rate Limit Tester" -> config.getBool("rl.enabled", true);
            case "Passive Error Detector" -> config.getBool("pem.enabled", true);
            default -> true;
        };
    }
}
