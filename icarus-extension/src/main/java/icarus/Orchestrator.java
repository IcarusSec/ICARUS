package icarus;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.handler.*;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.ui.contextmenu.ContextMenuItemsProvider;
import burp.api.montoya.ui.contextmenu.ContextMenuEvent;
import burp.api.montoya.ui.contextmenu.MessageEditorHttpRequestResponse;

import icarus.autoauth.AutoAuthModule;
import icarus.core.*;
import icarus.evidence.EvidenceCapture;
import icarus.evidence.ReportGenerator;
import icarus.modules.PassiveErrorModule;
import icarus.ui.ToastNotification;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Burp integration facade: wires context-menu items and the passive HTTP handler to
 * scan execution ({@link ScanRunner}) and finding bookkeeping ({@link FindingRegistry}),
 * and owns how findings get presented (the results dialog).
 */
public final class Orchestrator implements ContextMenuItemsProvider, HttpHandler {

    private final MontoyaApi api;
    private final List<IcarusModule> modules;
    private final ModuleConfig config;
    private final EvidenceCapture evidenceCapture;
    private final ReportGenerator reportGenerator;
    private final AutoAuthModule autoAuth;
    private final ScanRunner scanRunner;
    private final FindingRegistry findings;

    public Orchestrator(MontoyaApi api,
                        List<IcarusModule> modules,
                        ModuleConfig config,
                        EvidenceCapture evidenceCapture,
                        ReportGenerator reportGenerator,
                        AutoAuthModule autoAuth) {
        this.api = api;
        this.modules = modules;
        this.config = config;
        this.evidenceCapture = evidenceCapture;
        this.reportGenerator = reportGenerator;
        this.autoAuth = autoAuth;
        this.findings = new FindingRegistry(api, config, SwingUtilities::invokeLater);
        this.scanRunner = new ScanRunner(api, modules, config, this::routeFindings);
    }

    public AutoAuthModule autoAuth() {
        return autoAuth;
    }

    public void addListener(Consumer<List<FindingRecord>> listener) {
        findings.addListener(listener);
    }

    public List<String> getAuditLog() {
        return findings.getAuditLog();
    }

    public void suppressFinding(String hash, String reason) {
        findings.suppressFinding(hash, reason);
    }

    public void unsuppressFinding(String hash) {
        findings.unsuppressFinding(hash);
    }

    public Finding getFindingByHash(String hash) {
        return findings.getFindingByHash(hash);
    }

    public void showEvidenceInteractive(Finding finding) {
        evidenceCapture.captureInteractive(finding);
    }

    /**
     * Shared by the "Create Evidence" context-menu item and the Ctrl+P hotkey handler —
     * both entry points get Smart Evidence detection for free by routing through here.
     */
    public void createManualEvidence(HttpRequestResponse rr) {
        Finding smart = detectSmartEvidence(rr);
        evidenceCapture.captureInteractive(smart != null ? smart : blankManualFinding(rr));
    }

    /**
     * Quietly checks the response for something worth flagging (verbose error / server
     * error, or an unencoded reflection of a request parameter) and, if the user confirms,
     * pre-fills the evidence with that finding instead of the blank manual template.
     */
    private Finding detectSmartEvidence(HttpRequestResponse rr) {
        if (rr.response() == null) return null;

        // Reuse PassiveErrorModule's detection instead of a second copy of the same
        // VerboseErrorDetector/status-code checks living here.
        List<Finding> errorFindings = new PassiveErrorModule().run(rr, config, msg -> {});
        if (!errorFindings.isEmpty()) {
            Finding candidate = errorFindings.get(0);
            return confirmSmartEvidence(candidate.type(), candidate.description()) ? candidate : null;
        }

        // XSS reflection — manual-evidence-only heuristic. Deliberately not part of the
        // always-on background passive scan: any endpoint that legitimately echoes a
        // search term back would make it noisy there, but it's a useful targeted nudge
        // when the user is already looking at this specific request/response.
        String bodyStr = rr.response().bodyToString();
        for (var param : rr.request().parameters()) {
            String val = param.value();
            if (val != null && !val.isBlank() && (val.contains("<") || val.contains(">")) && bodyStr.contains(val)) {
                String desc = "Unencoded reflection of HTML/script payload detected:\n`" + val + "`";
                if (!confirmSmartEvidence("XSS_REFLECTION", desc)) return null;
                return Finding.builder("Manual", "XSS_REFLECTION")
                        .description(desc)
                        .severity(Severity.HIGH)
                        .category(Category.INJECTION)
                        .path(param.name())
                        .evidence(rr)
                        .build();
            }
        }

        return null;
    }

    private boolean confirmSmartEvidence(String type, String description) {
        int choice = JOptionPane.showConfirmDialog(api.userInterface().swingUtils().suiteFrame(),
                "ICARUS detected a potential [" + type + "] in this response.\nAuto-populate the evidence title and description?",
                "Smart Evidence Detection", JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE);
        return choice == JOptionPane.YES_OPTION;
    }

    private Finding blankManualFinding(HttpRequestResponse rr) {
        Severity manualSeverity = parseSeverity(config.getString("evidence.manual_severity", "INFO"));
        return Finding.builder("Manual", "MANUAL_EVIDENCE")
                .description("Manual evidence capture triggered by user.")
                .severity(manualSeverity)
                .category(Category.MANUAL)
                .evidence(rr)
                .build();
    }

    public void runScan(HttpRequestResponse target, boolean isManual) {
        scanRunner.runScan(target, isManual);
    }

    public List<FindingRecord> getAllFindingRecords() {
        return findings.getAllFindingRecords();
    }

    public List<FindingRecord> getPassiveFindings() {
        return findings.getPassiveFindings();
    }

    public void clearPassiveFindings() {
        findings.clearPassiveFindings();
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
                scanRunner.runScan(rr, true);
            }
        });
        items.add(runAll);

        var createEvidence = new JMenuItem("ICARUS → Create Evidence");
        createEvidence.addActionListener(e -> {
            for (var rr : requestResponses) {
                createManualEvidence(rr);
            }
        });
        items.add(createEvidence);

        // AutoAuth: only shown when the user actually highlighted text in a message editor —
        // these need selection offsets that scan-style modules never receive.
        event.messageEditorRequestResponse().ifPresent(selection -> {
            if (selection.selectionOffsets().isEmpty()) return;
            if (selection.selectionContext() == MessageEditorHttpRequestResponse.SelectionContext.RESPONSE) {
                var setSource = new JMenuItem("ICARUS → Set as Auth Token Source");
                setSource.addActionListener(e -> autoAuth.setSourceFromSelection(selection));
                items.add(setSource);
            } else {
                var addDestination = new JMenuItem("ICARUS → Add Auth Token Destination");
                addDestination.addActionListener(e -> autoAuth.addDestinationFromSelection(selection));
                items.add(addDestination);
            }
        });

        for (var module : modules) {
            var item = new JMenuItem("ICARUS → " + module.name());
            item.addActionListener(e -> {
                for (var rr : requestResponses) {
                    scanRunner.runModule(module, rr, true);
                }
            });
            items.add(item);
        }

        return items;
    }

    @Override
    public RequestToBeSentAction handleHttpRequestToBeSent(HttpRequestToBeSent request) {
        return RequestToBeSentAction.continueWith(autoAuth.processOutgoingRequest(request));
    }

    @Override
    public ResponseReceivedAction handleHttpResponseReceived(HttpResponseReceived response) {
        if (!config.getBool("sh.enabled", true) || !config.getBool("sh.passive", true)) {
            return ResponseReceivedAction.continueWith(response);
        }

        scanRunner.runPassiveScan(response, this::routeFindingsPassive);

        return ResponseReceivedAction.continueWith(response);
    }

    private void routeFindingsPassive(List<Finding> passiveFindings) {
        List<Finding> newFindings = findings.processDeduplication(passiveFindings, true);
        if (newFindings.isEmpty()) return;

        long errorCount = newFindings.stream()
                .filter(f -> f.category() == Category.SERVER_ERROR || f.category() == Category.INFORMATION_DISCLOSURE)
                .count();
        if (errorCount > 0) {
            ToastNotification.show(api.userInterface().swingUtils().suiteFrame(),
                    "ICARUS: Logged " + errorCount + " passive error(s).");
        }
    }

    private void routeFindings(List<Finding> newFindings, boolean isManual) {
        List<Finding> newOrUpdated = findings.processDeduplication(newFindings, false);

        if (isManual && !newFindings.isEmpty()) {
            // Manual scans show results even on a re-run that only produced duplicates —
            // look up every incoming finding by hash, not just the newly-created ones.
            List<FindingRecord> recordsToShow = new ArrayList<>();
            Set<String> seenHashes = new HashSet<>();
            for (Finding f : newFindings) {
                String hash = f.similarityHash();
                if (!seenHashes.add(hash)) continue; // already resolved this hash this batch
                FindingRecord r = findings.getRecordByHash(hash);
                if (r != null && !r.isSuppressed()) recordsToShow.add(r);
            }
            SwingUtilities.invokeLater(() -> showFindingsDialog(recordsToShow));
        } else if (!newOrUpdated.isEmpty() && config.getBool("ui.show_popups", true)) {
            List<FindingRecord> recordsToShow = new ArrayList<>();
            for (Finding f : newOrUpdated) {
                FindingRecord r = findings.getRecordByHash(f.similarityHash());
                if (r != null) recordsToShow.add(r);
            }
            SwingUtilities.invokeLater(() -> showFindingsDialog(recordsToShow));
        }
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
            JFileChooser fc = new JFileChooser(new java.io.File(config.getString("evidence.output_dir", System.getProperty("user.home"))));
            fc.setSelectedFile(new java.io.File("report.html"));
            if (fc.showSaveDialog(dialog) != JFileChooser.APPROVE_OPTION) {
                return;
            }

            java.io.File selectedFile = fc.getSelectedFile();
            if (selectedFile.exists()) {
                int overwrite = JOptionPane.showConfirmDialog(dialog,
                        selectedFile.getName() + " already exists. Overwrite?",
                        "Confirm Overwrite", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
                if (overwrite != JOptionPane.YES_OPTION) {
                    return;
                }
            }

            try {
                List<Finding> reportFindings = new ArrayList<>();
                for (FindingRecord r : records) {
                    reportFindings.add(r.getFinding());
                }
                java.nio.file.Path outputFile = fc.getSelectedFile().toPath();
                reportGenerator.generate(reportFindings, config, evidenceCapture, outputFile);
                if (selectedFile.getParentFile() != null) {
                    config.set("evidence.output_dir", selectedFile.getParentFile().getAbsolutePath());
                    api.persistence().extensionData().setString("config", config.serialize());
                }
                JOptionPane.showMessageDialog(dialog, "HTML Report generated: " + outputFile.toAbsolutePath());
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

    private Severity parseSeverity(String value) {
        try {
            return Severity.valueOf(value);
        } catch (Exception e) {
            return Severity.INFO;
        }
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
}
