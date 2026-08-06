package icarus;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.handler.HttpResponseReceived;
import burp.api.montoya.http.message.HttpRequestResponse;
import icarus.core.Finding;
import icarus.core.IcarusModule;
import icarus.core.ModuleConfig;
import icarus.modules.PassiveErrorModule;
import icarus.modules.SensitiveHeaderModule;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;
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
        Future<?> task = currentTask;
        if (task != null && !task.isDone()) {
            task.cancel(true);
        }
    }

    public void runScan(HttpRequestResponse target, boolean isManual) {
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

        if (config.getBool("waf.detect_akamai", true) && target.response() != null) {
            // ponytail: one-liner header check via stream matching. Ceiling: exact header string matching.
            boolean isAkamai = target.response().headers().stream()
                .anyMatch(h -> h.name().toLowerCase().startsWith("ak-") ||
                               h.name().toLowerCase().startsWith("x-akamai-") ||
                               (h.name().equalsIgnoreCase("server") && h.value().toLowerCase().contains("akamai")));

            if (isAkamai) {
                int[] choiceHolder = { -1 };
                runOnEdtAndWait(() -> choiceHolder[0] = JOptionPane.showOptionDialog(null,
                        "Akamai WAF detected in baseline response!\nAre you sure you want to run default payloads?",
                        "WAF Detected",
                        JOptionPane.YES_NO_OPTION,
                        JOptionPane.WARNING_MESSAGE,
                        null,
                        new String[]{"Run Default", "Run Safe Mode (Safelist)"},
                        "Run Safe Mode (Safelist)"));
                int choice = choiceHolder[0];

                if (choice == 1) {
                    log.accept("User chose SAFE MODE (WAF bypass)");
                    String safePayloads = config.getString("waf.safelist_payloads", "' OR 1=1--");
                    config.set("pv.payload_sqli", safePayloads);
                    config.set("pv.payload_xss", safePayloads);
                    config.set("pv.payload_path_traversal", safePayloads);
                    config.set("pv.payload_nosqli", safePayloads);
                    config.set("pv.payload_format_string", safePayloads);
                }
            }
        }

        for (var module : modules) {
            if (Thread.currentThread().isInterrupted()) {
                log.accept("ICARUS scan stopped by user.");
                break;
            }
            if (!isModuleEnabled(module) || !module.includeInBulkScan()) continue;

            log.accept("──── Running: " + module.name() + " ────");

            try {
                var moduleFindings = module.run(target, config, verboseLogger(log, config));
                findings.addAll(moduleFindings);
                log.accept(module.name() + " → " + moduleFindings.size() + " findings");
            } catch (Exception e) {
                error.accept(module.name() + " failed: " + e.getMessage());
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
        var findings = module.run(target, config, verboseLogger(logger, config));
        onFindings.accept(findings, isManual);
        String status = Thread.currentThread().isInterrupted() ? "stopped by user" : "complete";
        logger.accept("ICARUS → " + module.name() + " " + status + " — " + findings.size() + " findings.");
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

            JButton btnStop = new JButton("Stop");
            btnStop.addActionListener(e -> {
                textArea.append("Stopping — cancelling current test...\n");
                textArea.setCaretPosition(textArea.getDocument().getLength());
                btnStop.setEnabled(false);
                stopCurrent();
            });
            JPanel bottomBar = new JPanel(new FlowLayout(FlowLayout.RIGHT));
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
