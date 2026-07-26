package icarus;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.handler.*;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.ui.contextmenu.ContextMenuItemsProvider;
import burp.api.montoya.ui.contextmenu.ContextMenuEvent;

import icarus.core.*;
import icarus.evidence.EvidenceCapture;
import icarus.evidence.ReportGenerator;
import icarus.modules.SensitiveHeaderModule;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Central coordinator that runs modules, collects findings,
 * routes them to evidence capture, Repeater, Organizer, and audit issues.
 *
 * Also serves as the context menu provider and passive HTTP handler.
 */
public final class Orchestrator implements ContextMenuItemsProvider, HttpHandler {

    private final MontoyaApi api;
    private final List<IcarusModule> modules;
    private final ModuleConfig config;
    private final EvidenceCapture evidenceCapture;
    private final ReportGenerator reportGenerator;
    private final ExecutorService executor;

    // Listeners for the UI results table
    private final List<ScanListener> listeners = new ArrayList<>();

    public Orchestrator(MontoyaApi api,
                        List<IcarusModule> modules,
                        ModuleConfig config,
                        EvidenceCapture evidenceCapture,
                        ReportGenerator reportGenerator) {
        this.api = api;
        this.modules = modules;
        this.config = config;
        this.evidenceCapture = evidenceCapture;
        this.reportGenerator = reportGenerator;
        this.executor = Executors.newSingleThreadExecutor(r -> {
            var t = new Thread(r, "ICARUS-scan");
            t.setDaemon(true);
            return t;
        });
    }

    // ── Public API ──────────────────────────────────────────────

    /**
     * Run all enabled modules against the given request/response.
     * Executes off the EDT in a background thread.
     */
    public void runScan(HttpRequestResponse target) {
        executor.submit(() -> {
            try {
                doScan(target);
            } catch (Exception e) {
                api.logging().logToError("ICARUS scan failed: " + e.getMessage());
            }
        });
    }

    public void addListener(ScanListener listener) {
        listeners.add(listener);
    }

    // ── Context Menu ────────────────────────────────────────────

    @Override
    public List<Component> provideMenuItems(ContextMenuEvent event) {
        var items = new ArrayList<Component>();

        var requestResponses = event.messageEditorRequestResponse().isPresent()
                ? List.of(event.messageEditorRequestResponse().get().requestResponse())
                : event.selectedRequestResponses();

        if (requestResponses.isEmpty()) return items;

        var runAll = new JMenuItem("ICARUS → Run All Modules");
        runAll.addActionListener(e -> {
            for (var rr : requestResponses) {
                runScan(rr);
            }
        });
        items.add(runAll);

        // Individual module entries
        for (var module : modules) {
            var item = new JMenuItem("ICARUS → " + module.name());
            item.addActionListener(e -> {
                for (var rr : requestResponses) {
                    executor.submit(() -> {
                        try {
                            runSingleModule(module, rr);
                        } catch (Exception ex) {
                            api.logging().logToError("ICARUS " + module.name() + " failed: " + ex.getMessage());
                        }
                    });
                }
            });
            items.add(item);
        }

        return items;
    }

    // ── Passive HTTP Handler (SensitiveHeaders) ─────────────────

    @Override
    public RequestToBeSentAction handleHttpRequestToBeSent(HttpRequestToBeSent request) {
        return RequestToBeSentAction.continueWith(request);
    }

    @Override
    public ResponseReceivedAction handleHttpResponseReceived(HttpResponseReceived response) {
        if (!config.getBool("sh.enabled", true) || !config.getBool("sh.passive", true)) {
            return ResponseReceivedAction.continueWith(response);
        }

        // Run sensitive header checks passively in background
        executor.submit(() -> {
            try {
                for (var module : modules) {
                    if (module instanceof SensitiveHeaderModule shm) {
                        var findings = shm.analyzeResponse(response, config);
                        if (!findings.isEmpty()) {
                            routeFindings(findings);
                        }
                    }
                }
            } catch (Exception e) {
                // Don't spam logs for passive scan errors
            }
        });

        return ResponseReceivedAction.continueWith(response);
    }

    // ── Internal ────────────────────────────────────────────────

    private void doScan(HttpRequestResponse target) {
        var context = new ScanContext(api, target, config);

        context.log("════════════════════════════════════════════════");
        context.log("ICARUS scan started — " + target.request().method()
                + " " + target.request().path());

        for (var module : modules) {
            if (!isModuleEnabled(module)) continue;

            context.log("──── Running: " + module.name() + " ────");

            try {
                var findings = module.run(target, config);
                context.addFindings(findings);

                context.log(module.name() + " → " + findings.size() + " findings");
            } catch (Exception e) {
                context.error(module.name() + " failed: " + e.getMessage());
            }
        }

        routeFindings(context.findings());

        // Generate report
        if (config.getBool("evidence.enabled", true)) {
            try {
                reportGenerator.generate(context.findings(), config);
                context.log("Evidence report generated.");
            } catch (Exception e) {
                context.error("Report generation failed: " + e.getMessage());
            }
        }

        context.log("ICARUS scan complete — " + context.findings().size() + " total findings.");
        context.log("════════════════════════════════════════════════");

        notifyListeners(context.findings());
    }

    private void runSingleModule(IcarusModule module, HttpRequestResponse target) {
        api.logging().logToOutput("ICARUS → Running " + module.name());

        var findings = module.run(target, config);
        routeFindings(findings);

        api.logging().logToOutput("ICARUS → " + module.name() + " complete — " + findings.size() + " findings.");

        notifyListeners(findings);
    }

    private void routeFindings(List<Finding> findings) {
        int repeaterCount = 0;
        int maxRepeater = config.getInt("pv.max_repeater", 10);

        for (var finding : findings) {
            // Log
            api.logging().logToOutput(finding.toString());

            // Repeater
            if (finding.evidence() != null && repeaterCount < maxRepeater) {
                try {
                    String tabName = buildTabName(finding, repeaterCount + 1);
                    api.repeater().sendToRepeater(finding.evidence().request(), tabName);
                    repeaterCount++;
                } catch (Exception e) {
                    api.logging().logToError("Failed to send to Repeater: " + e.getMessage());
                }
            } else if (finding.evidence() != null && config.getBool("pv.send_excess_organizer", true)) {
                try {
                    api.organizer().sendToOrganizer(finding.evidence().request());
                } catch (Exception e) {
                    api.logging().logToError("Failed to send to Organizer: " + e.getMessage());
                }
            }

            // Audit issues
            if (config.getBool("pv.create_audit_issues", true) && finding.evidence() != null) {
                try {
                    createAuditIssue(finding);
                } catch (Exception e) {
                    api.logging().logToError("Failed to create audit issue: " + e.getMessage());
                }
            }

            // Evidence capture
            if (config.getBool("evidence.auto_capture", true) && finding.evidence() != null) {
                try {
                    evidenceCapture.capture(finding);
                } catch (Exception e) {
                    // Non-critical — don't stop the scan
                }
            }
        }
    }

    private String buildTabName(Finding finding, int index) {
        String prefix = "IC";
        String label = finding.shortLabel();
        String path = finding.path();

        // Take last 2 path components
        if (path != null && path.startsWith("$.")) {
            String[] parts = path.substring(2).split("\\.");
            int start = Math.max(0, parts.length - 2);
            path = String.join(".", java.util.Arrays.copyOfRange(parts, start, parts.length));
        }
        if (path == null || path.isBlank()) path = "root";
        if (path.length() > 10) path = path.substring(0, 10);

        String name = prefix + "-" + label + "-" + path + "-" + String.format("%02d", index);
        return name.length() <= 28 ? name : name.substring(0, 28);
    }

    private void createAuditIssue(Finding finding) {
        var issue = burp.api.montoya.scanner.audit.issues.AuditIssue.auditIssue(
            "ICARUS: " + finding.type(),
            finding.description() + "<br>Module: " + finding.module() + "<br>Path: " + finding.path(),
            "Review the finding and validate the vulnerability.",
            finding.evidence().request().url(),
            mapSeverity(finding.severity()),
            burp.api.montoya.scanner.audit.issues.AuditIssueConfidence.FIRM,
            null,
            null,
            mapSeverity(finding.severity()),
            finding.evidence()
        );
        api.siteMap().add(issue);
    }

    private burp.api.montoya.scanner.audit.issues.AuditIssueSeverity mapSeverity(Severity severity) {
        return switch (severity) {
            case CRITICAL, HIGH -> burp.api.montoya.scanner.audit.issues.AuditIssueSeverity.HIGH;
            case MEDIUM         -> burp.api.montoya.scanner.audit.issues.AuditIssueSeverity.MEDIUM;
            case LOW            -> burp.api.montoya.scanner.audit.issues.AuditIssueSeverity.LOW;
            case INFO           -> burp.api.montoya.scanner.audit.issues.AuditIssueSeverity.INFORMATION;
        };
    }

    private boolean isModuleEnabled(IcarusModule module) {
        return switch (module.name()) {
            case "ParamValidator"    -> config.getBool("pv.enabled", true);
            case "HTTP Verb Tester"  -> config.getBool("hv.enabled", true);
            case "JWT Checker"       -> config.getBool("jwt.enabled", true);
            case "Sensitive Headers" -> config.getBool("sh.enabled", true);
            case "Postman Export"    -> config.getBool("export.enabled", true);
            default -> true;
        };
    }

    private void notifyListeners(List<Finding> findings) {
        SwingUtilities.invokeLater(() -> {
            for (var listener : listeners) {
                listener.onScanComplete(findings);
            }
        });
    }

    /**
     * Callback for the UI to receive scan results.
     */
    public interface ScanListener {
        void onScanComplete(List<Finding> findings);
    }
}
