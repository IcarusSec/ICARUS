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
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

public final class Orchestrator implements ContextMenuItemsProvider, HttpHandler {

    private final MontoyaApi api;
    private final List<IcarusModule> modules;
    private final ModuleConfig config;
    private final EvidenceCapture evidenceCapture;
    private final ReportGenerator reportGenerator;
    private final ExecutorService executor;

    private final Map<String, FindingRecord> activeFindings = new java.util.concurrent.ConcurrentHashMap<>();
    private final List<String> auditLog = new java.util.ArrayList<>();

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

        for (String hash : config.getStringList("suppressed_hashes")) {
            Finding dummy = Finding.builder("System", "DUMMY").build();
            FindingRecord fr = new FindingRecord(dummy);
            fr.setSuppressed(true);
            activeFindings.put(hash, fr);
        }
        logAudit("System initialized. Loaded " + config.getStringList("suppressed_hashes").size() + " suppression rules.");
    }

    public void logAudit(String action) {
        String timestamp = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        String entry = "[" + timestamp + "] " + action;
        synchronized(auditLog) {
            auditLog.add(entry);
        }
        api.logging().logToOutput(entry);
    }

    public List<String> getAuditLog() {
        synchronized(auditLog) {
            return new ArrayList<>(auditLog);
        }
    }

    public void suppressFinding(String hash, String reason) {
        var record = activeFindings.get(hash);
        if (record != null) {
            record.setSuppressed(true);
            logAudit("User suppressed finding: " + hash + " (Reason: " + reason + ")");
            saveSuppressionConfig();
            notifyListenersOfUpdate();
        }
    }

    public void unsuppressFinding(String hash) {
        var record = activeFindings.get(hash);
        if (record != null) {
            record.setSuppressed(false);
            logAudit("User removed suppression for: " + hash);
            saveSuppressionConfig();
            notifyListenersOfUpdate();
        }
    }

    public Finding getFindingByHash(String hash) {
        FindingRecord record = activeFindings.get(hash);
        return record != null ? record.getFinding() : null;
    }

    public void showEvidenceInteractive(Finding finding) {
        evidenceCapture.captureInteractive(finding);
    }

    private void saveSuppressionConfig() {
        List<String> suppressed = new ArrayList<>();
        for (var entry : activeFindings.entrySet()) {
            if (entry.getValue().isSuppressed()) {
                suppressed.add(entry.getKey());
            }
        }
        config.set("suppressed_hashes", String.join("\n", suppressed));
        api.persistence().extensionData().setString("config", config.serialize());
    }

    public void runScan(HttpRequestResponse target, boolean isManual) {
        executor.submit(() -> {
            try {
                doScan(target, isManual);
            } catch (Exception e) {
                api.logging().logToError("ICARUS scan failed: " + e.getMessage());
            }
        });
    }

    public void addListener(ScanListener listener) {
        listeners.add(listener);
    }

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
                runScan(rr, true);
            }
        });
        items.add(runAll);

        var createEvidence = new JMenuItem("ICARUS → Create Evidence");
        createEvidence.addActionListener(e -> {
            for (var rr : requestResponses) {
                Finding manualFinding = Finding.builder("Manual", "MANUAL_EVIDENCE")
                        .description("Manual evidence capture triggered by user.")
                        .severity(Severity.INFO)
                        .category(Category.MANUAL)
                        .evidence(rr)
                        .build();
                evidenceCapture.captureInteractive(manualFinding);
            }
        });
        items.add(createEvidence);

        for (var module : modules) {
            var item = new JMenuItem("ICARUS → " + module.name());
            item.addActionListener(e -> {
                for (var rr : requestResponses) {
                    executor.submit(() -> {
                        try {
                            runSingleModule(module, rr, true);
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

    @Override
    public RequestToBeSentAction handleHttpRequestToBeSent(HttpRequestToBeSent request) {
        return RequestToBeSentAction.continueWith(request);
    }

    @Override
    public ResponseReceivedAction handleHttpResponseReceived(HttpResponseReceived response) {
        if (!config.getBool("sh.enabled", true) || !config.getBool("sh.passive", true)) {
            return ResponseReceivedAction.continueWith(response);
        }

        executor.submit(() -> {
            try {
                for (var module : modules) {
                    if (module instanceof SensitiveHeaderModule shm) {
                        var findings = shm.analyzeResponse(response, config);
                        if (!findings.isEmpty()) {
                            routeFindingsPassive(findings);
                        }
                    }
                }
            } catch (Exception e) {
                api.logging().logToError("ICARUS passive scan failed: " + e);
            }
        });

        return ResponseReceivedAction.continueWith(response);
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

        routeFindings(context.findings(), isManual);

        context.log("ICARUS scan complete — " + context.findings().size() + " total findings.");
        context.log("════════════════════════════════════════════════");
    }

    private void runSingleModule(IcarusModule module, HttpRequestResponse target, boolean isManual) {
        Consumer<String> logger = createLiveLogWindow("ICARUS — " + module.name() + " Progress");
        logger.accept("ICARUS → Running " + module.name());
        api.logging().logToOutput("ICARUS → Running " + module.name());
        var findings = module.run(target, config);
        routeFindings(findings, isManual);
        logger.accept("ICARUS → " + module.name() + " complete — " + findings.size() + " findings.");
        api.logging().logToOutput("ICARUS → " + module.name() + " complete — " + findings.size() + " findings.");
    }

    private Consumer<String> createLiveLogWindow(String title) {
        JTextArea[] textAreaHolder = new JTextArea[1];

        runOnEdtAndWait(() -> {
            JFrame frame = new JFrame(title);
            frame.setSize(800, 400);
            frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
            frame.setLocationRelativeTo(null); // Center on screen

            JTextArea textArea = new JTextArea();
            textArea.setEditable(false);
            textArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
            textArea.setBackground(new Color(34, 34, 34));
            textArea.setForeground(new Color(200, 200, 200));

            JScrollPane scrollPane = new JScrollPane(textArea);
            scrollPane.setBorder(BorderFactory.createEmptyBorder());
            frame.add(scrollPane, BorderLayout.CENTER);

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

    private void routeFindingsPassive(List<Finding> findings) {
        processDeduplication(findings, true);
    }

    private void routeFindings(List<Finding> findings, boolean isManual) {
        List<Finding> newOrUpdated = processDeduplication(findings, false);

        if (!newOrUpdated.isEmpty() && (isManual || config.getBool("ui.show_popups", true))) {
            List<FindingRecord> recordsToShow = new ArrayList<>();
            for (Finding f : newOrUpdated) {
                FindingRecord r = activeFindings.get(f.similarityHash());
                if (r != null) recordsToShow.add(r);
            }
            SwingUtilities.invokeLater(() -> showFindingsDialog(recordsToShow));
        }
    }

    private List<Finding> processDeduplication(List<Finding> findings, boolean passive) {
        List<Finding> actionable = new ArrayList<>();

        for (var finding : findings) {
            String hash = finding.similarityHash();
            var record = activeFindings.get(hash);

            if (record != null) {
                if (record.isSuppressed()) {
                    continue;
                }
                record.incrementCount();
                logAudit("Duplicate finding incremented to " + record.getCount() + "x: " + hash);
                notifyListenersOfUpdate();
            } else {
                var newRecord = new FindingRecord(finding);
                activeFindings.put(hash, newRecord);

                logAudit("New finding identified: " + hash);
                actionable.add(finding);

                if (!passive && config.getBool("pv.create_audit_issues", true) && finding.evidence() != null) {
                    try {
                        createAuditIssue(finding);
                    } catch (Exception e) {
                        api.logging().logToError("Failed to create audit issue: " + e.getMessage());
                    }
                }
                notifyListenersOfUpdate();
            }
        }
        return actionable;
    }

    public List<FindingRecord> getAllFindingRecords() {
        return new ArrayList<>(activeFindings.values());
    }

    public List<FindingRecord> getPassiveFindings() {
        List<FindingRecord> results = new ArrayList<>();
        for (FindingRecord r : activeFindings.values()) {
            if (!r.isSuppressed() && r.getFinding().evidence() == null) {
                results.add(r);
            }
        }
        return results;
    }

    public void clearPassiveFindings() {
        activeFindings.entrySet().removeIf(e -> e.getValue().getFinding().evidence() == null);
        notifyListenersOfUpdate();
        logAudit("User cleared passive findings.");
    }

    public void showFindingsDialog(List<FindingRecord> records) {
        JDialog dialog = new JDialog();
        dialog.setTitle("ICARUS Scan Results");
        dialog.setModal(false);
        dialog.setSize(1200, 800);
        dialog.setLocationRelativeTo(null);

        String[] cols = {"Count", "Severity", "Module", "Type", "Path", "Description"};
        Object[][] data = new Object[records.size()][6];
        for (int i = 0; i < records.size(); i++) {
            FindingRecord r = records.get(i);
            Finding f = r.getFinding();
            data[i] = new Object[]{r.getCount(), f.severity().name(), f.module(), f.type(), f.path(), f.description()};
        }

        JTable table = new JTable(data, cols) {
            @Override
            public boolean isCellEditable(int row, int column) { return false; }
        };
        table.setAutoCreateRowSorter(true);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);

        JPanel topPanel = new JPanel(new BorderLayout());

        // Add filtering
        JPanel filterPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        filterPanel.add(new JLabel("Filter: "));
        JTextField txtFilter = new JTextField(20);
        filterPanel.add(txtFilter);
        javax.swing.table.TableRowSorter<javax.swing.table.TableModel> sorter = new javax.swing.table.TableRowSorter<>(table.getModel());
        table.setRowSorter(sorter);
        txtFilter.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e) { applyFilter(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e) { applyFilter(); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { applyFilter(); }
            private void applyFilter() {
                String text = txtFilter.getText();
                if (text.trim().length() == 0) sorter.setRowFilter(null);
                else sorter.setRowFilter(RowFilter.regexFilter("(?i)" + text));
            }
        });

        topPanel.add(filterPanel, BorderLayout.NORTH);
        topPanel.add(new JScrollPane(table), BorderLayout.CENTER);

        // Editors for Request/Response
        burp.api.montoya.ui.editor.HttpRequestEditor reqEditor = api.userInterface().createHttpRequestEditor(burp.api.montoya.ui.editor.EditorOptions.READ_ONLY);
        burp.api.montoya.ui.editor.HttpResponseEditor resEditor = api.userInterface().createHttpResponseEditor(burp.api.montoya.ui.editor.EditorOptions.READ_ONLY);

        JTabbedPane editorsTab = new JTabbedPane();
        editorsTab.addTab("Request", reqEditor.uiComponent());
        editorsTab.addTab("Response", resEditor.uiComponent());

        table.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                int viewRow = table.getSelectedRow();
                if (viewRow >= 0) {
                    int modelRow = table.convertRowIndexToModel(viewRow);
                    Finding f = records.get(modelRow).getFinding();
                    if (f.evidence() != null) {
                        reqEditor.setRequest(f.evidence().request());
                        if (f.evidence().response() != null) {
                            resEditor.setResponse(f.evidence().response());
                        } else {
                            resEditor.setResponse(burp.api.montoya.http.message.responses.HttpResponse.httpResponse(""));
                        }
                    } else {
                        reqEditor.setRequest(burp.api.montoya.http.message.requests.HttpRequest.httpRequest(""));
                        resEditor.setResponse(burp.api.montoya.http.message.responses.HttpResponse.httpResponse(""));
                    }
                }
            }
        });

        JSplitPane splitPane = new JSplitPane(JSplitPane.VERTICAL_SPLIT, topPanel, editorsTab);
        splitPane.setResizeWeight(0.5);
        dialog.add(splitPane, BorderLayout.CENTER);

        JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton btnRepeater = new JButton("Send to Repeater");
        JButton btnEvidence = new JButton("Save as Evidence");
        JButton btnReport = new JButton("Generate HTML Report");
        JButton btnClose = new JButton("Close");

        btnRepeater.addActionListener(e -> {
            int row = table.getSelectedRow();
            if (row >= 0) {
                int modelRow = table.convertRowIndexToModel(row);
                Finding f = records.get(modelRow).getFinding();
                if (f.evidence() != null) {
                    api.repeater().sendToRepeater(f.evidence().request(), buildTabName(f, modelRow + 1));
                    JOptionPane.showMessageDialog(dialog, "Sent to Repeater.");
                } else {
                    JOptionPane.showMessageDialog(dialog, "No HTTP request evidence attached to this finding.");
                }
            }
        });

        btnEvidence.addActionListener(e -> {
            int row = table.getSelectedRow();
            if (row >= 0) {
                int modelRow = table.convertRowIndexToModel(row);
                Finding f = records.get(modelRow).getFinding();
                if (f.evidence() != null) {
                    evidenceCapture.captureInteractive(f);
                } else {
                    JOptionPane.showMessageDialog(dialog, "No HTTP request evidence attached to this finding.");
                }
            }
        });

        btnReport.addActionListener(e -> {
            try {
                List<Finding> reportFindings = new ArrayList<>();
                for (FindingRecord r : records) {
                    reportFindings.add(r.getFinding());
                }
                reportGenerator.generate(reportFindings, config, evidenceCapture);
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
            case "Rate Limit Tester" -> config.getBool("rl.enabled", true);
            default -> true;
        };
    }

    private void notifyListenersOfUpdate() {
        SwingUtilities.invokeLater(() -> {
            for (var listener : listeners) {
                listener.onScanComplete(new ArrayList<>(activeFindings.values()));
            }
        });
    }

    public interface ScanListener {
        void onScanComplete(List<FindingRecord> records);
    }
}
