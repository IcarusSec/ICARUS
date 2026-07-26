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

        // WAF Detection
        if (config.getBool("waf.detect_akamai", true) && target.response() != null) {
            String server = target.response().headerValue("Server");
            if (server != null && server.toLowerCase().contains("akamai")) {
                int choice = JOptionPane.showOptionDialog(null,
                        "Akamai WAF detected in baseline response!\nAre you sure you want to run default payloads?",
                        "WAF Detected",
                        JOptionPane.YES_NO_OPTION,
                        JOptionPane.WARNING_MESSAGE,
                        null,
                        new String[]{"Run Default", "Run Safe Mode (Safelist)"},
                        "Run Safe Mode (Safelist)");

                if (choice == 1) {
                    context.log("User chose SAFE MODE (WAF bypass)");
                    // Override injection payloads temporarily for this scan run
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
        // Log all findings to extension output
        for (var finding : findings) {
            api.logging().logToOutput(finding.toString());

            // Audit issues
            if (config.getBool("pv.create_audit_issues", true) && finding.evidence() != null) {
                try {
                    createAuditIssue(finding);
                } catch (Exception e) {
                    api.logging().logToError("Failed to create audit issue: " + e.getMessage());
                }
            }
        }

        // Show interactive pop-up for findings if there are any
        if (!findings.isEmpty()) {
            SwingUtilities.invokeLater(() -> showFindingsDialog(findings));
        }
    }

    private void showFindingsDialog(List<Finding> findings) {
        JDialog dialog = new JDialog();
        dialog.setTitle("ICARUS Scan Results");
        dialog.setModal(false);
        dialog.setSize(900, 500);
        dialog.setLocationRelativeTo(null);

        String[] cols = {"Severity", "Module", "Type", "Path", "Description"};
        Object[][] data = new Object[findings.size()][5];
        for (int i = 0; i < findings.size(); i++) {
            Finding f = findings.get(i);
            data[i] = new Object[]{f.severity().name(), f.module(), f.type(), f.path(), f.description()};
        }

        JTable table = new JTable(data, cols) {
            @Override
            public boolean isCellEditable(int row, int column) { return false; }
        };
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        dialog.add(new JScrollPane(table), BorderLayout.CENTER);

        JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton btnRepeater = new JButton("Send to Repeater");
        JButton btnEvidence = new JButton("Save as Evidence");
        JButton btnReport = new JButton("Generate HTML Report");
        JButton btnClose = new JButton("Close");

        btnRepeater.addActionListener(e -> {
            int row = table.getSelectedRow();
            if (row >= 0) {
                Finding f = findings.get(row);
                if (f.evidence() != null) {
                    api.repeater().sendToRepeater(f.evidence().request(), buildTabName(f, row + 1));
                    JOptionPane.showMessageDialog(dialog, "Sent to Repeater.");
                } else {
                    JOptionPane.showMessageDialog(dialog, "No HTTP request evidence attached to this finding.");
                }
            }
        });

        btnEvidence.addActionListener(e -> {
            int row = table.getSelectedRow();
            if (row >= 0) {
                Finding f = findings.get(row);
                if (f.evidence() != null) {
                    evidenceCapture.captureInteractive(f);
                } else {
                    JOptionPane.showMessageDialog(dialog, "No HTTP request evidence attached to this finding.");
                }
            }
        });

        btnReport.addActionListener(e -> {
            try {
                reportGenerator.generate(findings, config, evidenceCapture);
                JOptionPane.showMessageDialog(dialog, "HTML Report generated from saved evidence.");
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(dialog, "Report generation failed: " + ex.getMessage());
            }
        });

        btnClose.addActionListener(e -> dialog.dispose());

        btnPanel.add(btnRepeater);
        btnPanel.add(btnEvidence);
        btnPanel.add(btnReport);
        btnPanel.add(btnClose);
        dialog.add(btnPanel, BorderLayout.SOUTH);

        dialog.setVisible(true);
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
