package icarus;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.handler.HttpResponseReceived;
import burp.api.montoya.http.message.HttpRequestResponse;
import icarus.core.Finding;
import icarus.core.IcarusModule;
import icarus.core.ModuleConfig;
import icarus.core.ScanContext;
import icarus.modules.SensitiveHeaderModule;

import javax.swing.*;
import java.awt.*;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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

    public void runScan(HttpRequestResponse target, boolean isManual) {
        executor.submit(() -> {
            try {
                doScan(target, isManual);
            } catch (Exception e) {
                api.logging().logToError("ICARUS scan failed: " + e);
            }
        });
    }

    public void runModule(IcarusModule module, HttpRequestResponse target, boolean isManual) {
        executor.submit(() -> {
            try {
                runSingleModule(module, target, isManual);
            } catch (Exception e) {
                api.logging().logToError("ICARUS " + module.name() + " failed: " + e);
            }
        });
    }

    /** Runs an arbitrary task on the same background executor used for scans. */
    public void runAsync(Runnable task) {
        executor.submit(() -> {
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
                if (module instanceof SensitiveHeaderModule shm) {
                    var passiveFindings = shm.analyzeResponse(response, config);
                    if (!passiveFindings.isEmpty()) {
                        onPassiveFindings.accept(passiveFindings);
                    }
                }
            }
        });
    }

    private void doScan(HttpRequestResponse target, boolean isManual) {
        var context = new ScanContext(api, target, config);
        Consumer<String> logger = createLiveLogWindow("ICARUS — Scan Progress");
        context.setLiveLogger(logger);

        context.log("════════════════════════════════════════════════");
        context.log("ICARUS scan started — " + target.request().method() + " " + target.request().path());

        if (config.getBool("waf.detect_akamai", true) && target.response() != null) {
            String server = target.response().headerValue("Server");
            if (server != null && server.toLowerCase().contains("akamai")) {
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
                    context.log("User chose SAFE MODE (WAF bypass)");
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
            if (!isModuleEnabled(module) || !module.includeInBulkScan()) continue;

            context.log("──── Running: " + module.name() + " ────");

            try {
                var findings = module.run(target, config, context::log);
                context.addFindings(findings);
                context.log(module.name() + " → " + findings.size() + " findings");
            } catch (Exception e) {
                context.error(module.name() + " failed: " + e.getMessage());
            }
        }

        onFindings.accept(context.findings(), isManual);

        context.log("ICARUS scan complete — " + context.findings().size() + " total findings.");
        context.log("════════════════════════════════════════════════");
    }

    private void runSingleModule(IcarusModule module, HttpRequestResponse target, boolean isManual) {
        Consumer<String> popupLogger = createLiveLogWindow("ICARUS — " + module.name() + " Progress");
        Consumer<String> logger = msg -> {
            popupLogger.accept(msg);
            api.logging().logToOutput(msg);
        };
        logger.accept("ICARUS → Running " + module.name());
        var findings = module.run(target, config, logger);
        onFindings.accept(findings, isManual);
        logger.accept("ICARUS → " + module.name() + " complete — " + findings.size() + " findings.");
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
            default -> true;
        };
    }
}
