package icarus;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.handler.*;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.ui.contextmenu.ContextMenuItemsProvider;
import burp.api.montoya.ui.contextmenu.ContextMenuEvent;
import burp.api.montoya.ui.contextmenu.MessageEditorHttpRequestResponse;

import icarus.autoauth.AutoAuthModule;
import icarus.core.*;
import icarus.evidence.EvidenceCapture;
import icarus.evidence.PdfReportGenerator;
import icarus.evidence.ReportGenerator;
import icarus.modules.PassiveErrorModule;
import icarus.ui.ToastNotification;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
    private final PdfReportGenerator pdfReportGenerator;
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
        this.pdfReportGenerator = new PdfReportGenerator(api);
        this.autoAuth = autoAuth;
        this.findings = new FindingRegistry(api, config, SwingUtilities::invokeLater);
        this.scanRunner = new ScanRunner(api, modules, config, this::routeFindings);

        evidenceCapture.setOnApplied(this::registerManualFinding);
    }

    /**
     * Called by EvidenceCapture once a finding's evidence is applied — folds it into the
     * same registry manual/passive/scan findings share, so it shows up in the Results tab
     * and "Generate HTML Report" immediately, and re-editing + re-applying later updates
     * the same entry (matched by {@link Finding#similarityHash()}) instead of duplicating it.
     */
    private void registerManualFinding(Finding finding) {
        findings.processDeduplication(List.of(finding), false);
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
     * Findings that actually belong in a report: ones the user explicitly sent through
     * Evidence Capture (Apply / Send annotation), not every passively-detected finding
     * (e.g. SensitiveHeaderModule's header checks, PassiveErrorModule) that only ever
     * landed in the Results tab for awareness. Order follows EvidenceCapture's captured
     * list, which the Evidence Manager's drag-and-drop reordering controls directly —
     * report order was previously undefined HashMap iteration order via getAllFindingRecords().
     * Also drops orphaned entries left behind when a finding was re-edited (the old,
     * pre-edit CapturedEvidence stays in the list, but the registry only tracks the latest),
     * and entries the user unchecked in the Evidence Manager's Include column.
     */
    public List<Finding> getReportableFindings() {
        List<Finding> result = new ArrayList<>();
        Set<Finding> seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (var ce : evidenceCapture.getCaptured()) {
            if (!evidenceCapture.isIncluded(ce)) continue;
            Finding f = ce.finding();
            if (!seen.add(f)) continue;
            var record = findings.getRecordByHash(f.similarityHash());
            if (record == null || record.isSuppressed() || record.getFinding() != f) continue;
            result.add(f);
        }
        return result;
    }

    /**
     * Window for managing the screenshots that will actually go into the HTML report —
     * separate from the Results tab, which lists every finding whether or not it has
     * evidence attached. Reachable from the "ICARUS → Manage Report Evidence" context-menu
     * item and the Results tab's own button.
     */
    public void showEvidenceManager() {
        JDialog dialog = new JDialog(api.userInterface().swingUtils().suiteFrame(), "ICARUS — Evidence Manager", false);
        dialog.setSize(1100, 700);
        dialog.setLocationRelativeTo(null);
        dialog.setLayout(new BorderLayout());

        List<EvidenceCapture.CapturedEvidence> entries = new ArrayList<>(evidenceCapture.getCaptured());

        DefaultTableModel model = new DefaultTableModel(new String[]{"Include", "#", "Title", "Severity", "Path", "CWE", "Image File"}, 0) {
            @Override
            public boolean isCellEditable(int row, int column) { return column == 0; }

            @Override
            public Class<?> getColumnClass(int columnIndex) {
                // Without this, an empty table (0 rows) can't infer Boolean from row data and
                // renders column 0 as the text "true"/"false" instead of an actual checkbox.
                return columnIndex == 0 ? Boolean.class : Object.class;
            }
        };

        JTable table = new JTable(model);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setRowHeight(22);
        table.getColumnModel().getColumn(0).setMaxWidth(60);
        // No row sorter: this table's order IS the report's order, driven entirely by
        // drag-and-drop below — clicking a column header to sort would silently desync
        // what the user sees from what dragging actually reorders.
        JScrollPane tableScroll = new JScrollPane(table);

        // Rebuilds the table from `entries` in place, keeping the same row selected —
        // called after every reorder/remove so the "#" column always matches report order.
        Runnable refreshTable = () -> {
            int selected = table.getSelectedRow();
            model.setRowCount(0);
            for (int i = 0; i < entries.size(); i++) {
                var ce = entries.get(i);
                Finding f = ce.finding();
                model.addRow(new Object[]{
                    evidenceCapture.isIncluded(ce), i + 1, f.type(), f.severity().name(), f.path(),
                    String.join(", ", f.cweIds()), ce.imagePath().getFileName().toString()
                });
            }
            if (selected >= 0 && selected < table.getRowCount()) {
                table.setRowSelectionInterval(selected, selected);
            }
        };
        refreshTable.run();

        // Unchecking "Include" leaves the screenshot in place (unlike Remove) but drops it
        // from the next Preview/Generate/Export — reversible, unlike deleting it.
        model.addTableModelListener(e -> {
            if (e.getColumn() != 0 || e.getType() != javax.swing.event.TableModelEvent.UPDATE) return;
            int row = e.getFirstRow();
            if (row < 0 || row >= entries.size()) return;
            evidenceCapture.setIncluded(entries.get(row), (Boolean) model.getValueAt(row, 0));
        });

        // Drag-and-drop row reordering: drag a row to a new position to reorder the report.
        // JTable's built-in row-move TransferHandler support, not a hand-rolled mouse listener.
        table.setDragEnabled(true);
        table.setDropMode(DropMode.INSERT_ROWS);
        DataFlavor rowFlavor = new DataFlavor(Integer.class, "Evidence Manager row index");
        table.setTransferHandler(new TransferHandler() {
            @Override
            protected Transferable createTransferable(JComponent c) {
                int row = table.getSelectedRow();
                return new Transferable() {
                    public DataFlavor[] getTransferDataFlavors() { return new DataFlavor[]{rowFlavor}; }
                    public boolean isDataFlavorSupported(DataFlavor flavor) { return rowFlavor.equals(flavor); }
                    public Object getTransferData(DataFlavor flavor) { return row; }
                };
            }

            @Override
            public int getSourceActions(JComponent c) { return MOVE; }

            @Override
            public boolean canImport(TransferSupport support) {
                return support.isDrop() && support.isDataFlavorSupported(rowFlavor);
            }

            @Override
            public boolean importData(TransferSupport support) {
                if (!canImport(support)) return false;
                try {
                    int from = (int) support.getTransferable().getTransferData(rowFlavor);
                    int to = ((JTable.DropLocation) support.getDropLocation()).getRow();
                    if (to > from) to--; // dropping below the dragged row's old slot shifts by one
                    if (from < 0 || from >= entries.size() || to < 0 || to >= entries.size() || from == to) {
                        return false;
                    }

                    var moved = entries.remove(from);
                    entries.add(to, moved);
                    evidenceCapture.reorderCaptured(entries);

                    refreshTable.run();
                    table.setRowSelectionInterval(to, to);
                    return true;
                } catch (UnsupportedFlavorException | IOException ex) {
                    return false;
                }
            }
        });

        JLabel preview = new JLabel("Select a row to preview its screenshot", SwingConstants.CENTER);
        JScrollPane previewScroll = new JScrollPane(preview);
        previewScroll.setPreferredSize(new Dimension(420, 0));

        table.getSelectionModel().addListSelectionListener(e -> {
            if (e.getValueIsAdjusting()) return;
            int row = table.getSelectedRow();
            if (row < 0 || row >= entries.size()) {
                preview.setIcon(null);
                preview.setText("Select a row to preview its screenshot");
                return;
            }
            var ce = entries.get(row);
            Image scaled = ce.image().getScaledInstance(380, -1, Image.SCALE_SMOOTH);
            preview.setIcon(new ImageIcon(scaled));
            preview.setText(null);
        });

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, tableScroll, previewScroll);
        split.setResizeWeight(0.6);
        // Report-level free text (scope/methodology/takeaway), not tied to any one finding —
        // persisted in config so it survives across Evidence Manager sessions. Read directly
        // by ReportGenerator/PdfReportGenerator at generate time, no plumbing needed elsewhere.
        JTextArea txtSummary = new JTextArea(config.getString("evidence.executive_summary", ""), 3, 0);
        txtSummary.setLineWrap(true);
        txtSummary.setWrapStyleWord(true);
        txtSummary.setBorder(BorderFactory.createTitledBorder("Report Notes (optional executive summary, shown at the top of the report)"));
        txtSummary.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e) { save(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e) { save(); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { save(); }
            private void save() { config.set("evidence.executive_summary", txtSummary.getText()); }
        });
        JScrollPane summaryScroll = new JScrollPane(txtSummary);
        summaryScroll.setPreferredSize(new Dimension(0, 80));

        JLabel dragHint = new JLabel("  Drag rows to reorder the report.");
        dragHint.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));

        JPanel topPanel = new JPanel(new BorderLayout());
        topPanel.add(summaryScroll, BorderLayout.CENTER);
        topPanel.add(dragHint, BorderLayout.SOUTH);

        dialog.add(topPanel, BorderLayout.NORTH);
        dialog.add(split, BorderLayout.CENTER);

        JButton btnEdit = new JButton("Edit…");
        btnEdit.addActionListener(e -> {
            int row = table.getSelectedRow();
            if (row < 0) return;
            evidenceCapture.captureInteractive(entries.get(row).finding());
        });

        JButton btnRemove = new JButton("Remove Evidence");
        btnRemove.addActionListener(e -> {
            int row = table.getSelectedRow();
            if (row < 0) return;
            int confirm = JOptionPane.showConfirmDialog(dialog,
                    "Remove this screenshot from the report? The finding itself stays in the Results tab.",
                    "Confirm Remove", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
            if (confirm != JOptionPane.YES_OPTION) return;
            evidenceCapture.removeCaptured(entries.get(row));
            entries.remove(row);
            refreshTable.run();
        });

        JButton btnPreview = new JButton("Preview");
        btnPreview.addActionListener(e -> previewReport(dialog, btnPreview));

        JButton btnGenerate = new JButton("Generate HTML Report");
        btnGenerate.addActionListener(e -> generateHtmlReportInteractive(dialog, btnGenerate, getReportableFindings()));

        JButton btnExportPdf = new JButton("Export PDF");
        btnExportPdf.addActionListener(e -> exportPdfReportInteractive(dialog, btnExportPdf, getReportableFindings()));

        JButton btnClose = new JButton("Close");
        btnClose.addActionListener(e -> {
            // Report Notes are kept up to date in-memory on every keystroke already;
            // flush to persistent storage once here rather than on every keystroke.
            api.persistence().extensionData().setString("config", config.serialize());
            dialog.dispose();
        });

        JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        btnPanel.add(btnEdit);
        btnPanel.add(btnRemove);
        btnPanel.add(btnPreview);
        btnPanel.add(btnGenerate);
        btnPanel.add(btnExportPdf);
        btnPanel.add(btnClose);
        dialog.add(btnPanel, BorderLayout.SOUTH);

        dialog.setVisible(true);
    }

    /**
     * Renders the report to a temp file and opens it in the system browser — a real look at
     * the actual CSS (flex summary boxes, badges) instead of a half-working JEditorPane, and
     * no new dependency since {@link Desktop} is stdlib. Writes nothing to the user's chosen
     * report location and never touches FindingRegistry — purely a look.
     */
    public void previewReport(Component parent, JButton triggerButton) {
        List<Finding> reportFindings = getReportableFindings();
        if (reportFindings.isEmpty()) {
            JOptionPane.showMessageDialog(parent,
                    "No evidence to preview yet — use \"Send to Reporter Creation\" or the Evidence Manager first.");
            return;
        }

        Path tempFile;
        try {
            tempFile = Files.createTempFile("icarus-report-preview-", ".html");
        } catch (IOException e) {
            api.logging().logToError("Failed to create preview temp file: " + e);
            JOptionPane.showMessageDialog(parent, "Failed to create a temp file for the preview: " + e.getMessage());
            return;
        }

        if (triggerButton != null) triggerButton.setEnabled(false);
        new SwingWorker<Boolean, Void>() {
            @Override
            protected Boolean doInBackground() throws Exception {
                return reportGenerator.generate(reportFindings, config, evidenceCapture, tempFile);
            }

            @Override
            protected void done() {
                if (triggerButton != null) triggerButton.setEnabled(true);
                Frame suiteFrame = api.userInterface().swingUtils().suiteFrame();
                try {
                    boolean written = get();
                    if (!written) {
                        ToastNotification.show(suiteFrame,
                                "No preview generated — HTML reports may be disabled in Settings.");
                        return;
                    }
                    openInBrowser(tempFile, parent);
                } catch (Exception ex) {
                    api.logging().logToError("Report preview failed: " + ex.getCause());
                    JOptionPane.showMessageDialog(parent, "Report preview failed: " + ex.getCause());
                }
            }
        }.execute();
    }

    private void openInBrowser(Path file, Component parent) {
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
            try {
                Desktop.getDesktop().browse(file.toUri());
                return;
            } catch (IOException ex) {
                api.logging().logToError("Failed to open preview in browser: " + ex);
                // fall through to the manual-path message below
            }
        }
        JOptionPane.showMessageDialog(parent,
                "Couldn't open a browser automatically. Preview saved at:\n" + file.toAbsolutePath());
    }

    @FunctionalInterface
    private interface ReportWriter {
        boolean write(List<Finding> findings, ModuleConfig config, EvidenceCapture capture, Path outputFile) throws Exception;
    }

    /**
     * Shared by the "ICARUS Scan Results" dialog and the Results tab's own report buttons —
     * both just gather whatever {@link Finding}s they're showing and hand them here.
     *
     * @param parent used to anchor the file chooser / confirm dialogs
     * @param triggerButton disabled while generating and re-enabled after, if not null
     */
    public void generateHtmlReportInteractive(Component parent, JButton triggerButton, List<Finding> reportFindings) {
        exportReportInteractive(parent, triggerButton, reportFindings, "html", "report.html", "HTML Report",
                (findings, cfg, capture, out) -> reportGenerator.generate(findings, cfg, capture, out));
    }

    /** Same shell as {@link #generateHtmlReportInteractive}, writing via {@link PdfReportGenerator} instead. */
    public void exportPdfReportInteractive(Component parent, JButton triggerButton, List<Finding> reportFindings) {
        exportReportInteractive(parent, triggerButton, reportFindings, "pdf", "report.pdf", "PDF Report",
                (findings, cfg, capture, out) -> pdfReportGenerator.generate(findings, cfg, capture, out));
    }

    private void exportReportInteractive(Component parent, JButton triggerButton, List<Finding> reportFindings,
                                          String extension, String defaultFileName, String formatLabel, ReportWriter writer) {
        JFileChooser fc = new JFileChooser(new java.io.File(config.getString("evidence.output_dir", System.getProperty("user.home"))));
        fc.setSelectedFile(new java.io.File(defaultFileName));
        if (fc.showSaveDialog(parent) != JFileChooser.APPROVE_OPTION) {
            return;
        }

        java.io.File selectedFile = fc.getSelectedFile();
        if (!selectedFile.getName().toLowerCase().endsWith("." + extension)) {
            selectedFile = new java.io.File(selectedFile.getParentFile(), selectedFile.getName() + "." + extension);
        }
        if (selectedFile.exists()) {
            int overwrite = JOptionPane.showConfirmDialog(parent,
                    selectedFile.getName() + " already exists. Overwrite?",
                    "Confirm Overwrite", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
            if (overwrite != JOptionPane.YES_OPTION) {
                return;
            }
        }

        java.io.File finalSelectedFile = selectedFile;
        Path outputFile = selectedFile.toPath();

        if (triggerButton != null) triggerButton.setEnabled(false);
        new SwingWorker<Boolean, Void>() {
            @Override
            protected Boolean doInBackground() throws Exception {
                return writer.write(reportFindings, config, evidenceCapture, outputFile);
            }

            @Override
            protected void done() {
                if (triggerButton != null) triggerButton.setEnabled(true);
                Frame suiteFrame = api.userInterface().swingUtils().suiteFrame();
                try {
                    boolean written = get();
                    if (written) {
                        if (finalSelectedFile.getParentFile() != null) {
                            config.set("evidence.output_dir", finalSelectedFile.getParentFile().getAbsolutePath());
                            api.persistence().extensionData().setString("config", config.serialize());
                        }
                        ToastNotification.show(suiteFrame, formatLabel + " generated: " + outputFile.toAbsolutePath());
                    } else {
                        ToastNotification.show(suiteFrame,
                                "No report was generated — HTML reports may be disabled in Settings, or there are no findings to include.");
                    }
                } catch (Exception ex) {
                    api.logging().logToError(formatLabel + " generation failed: " + ex.getCause());
                    JOptionPane.showMessageDialog(parent, formatLabel + " generation failed: " + ex.getCause());
                }
            }
        }.execute();
    }

    /**
     * Shared by the "Create Evidence" context-menu item and the Ctrl+P hotkey handler —
     * both entry points get Smart Evidence detection for free by routing through here.
     */
    public void createManualEvidence(HttpRequestResponse rr) {
        // AutoAuth injects the token on the wire (handleHttpRequestToBeSent), but that never
        // touches the UI's copy of the request — without this, captured evidence shows the
        // stale pre-injection token instead of what was actually sent.
        HttpRequest injectedRequest = autoAuth.injectIfApplicable(rr.request());
        if (injectedRequest != rr.request()) {
            rr = HttpRequestResponse.httpRequestResponse(injectedRequest, rr.response());
        }
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

        var createEvidence = new JMenuItem("ICARUS → Send to Reporter Creation");
        createEvidence.addActionListener(e -> {
            for (var rr : requestResponses) {
                createManualEvidence(rr);
            }
        });
        items.add(createEvidence);

        // AutoAuth: only shown when the user actually highlighted text in a message editor —
        // these need selection offsets that scan-style modules never receive.
        event.messageEditorRequestResponse().ifPresent(selection -> {
            // Quick toggle so users can disable AutoAuth for manual JWT manipulation
            // without digging into Settings.
            boolean enabled = autoAuth.isEnabled();
            var toggleAuth = new JMenuItem("ICARUS → " + (enabled ? "✓" : "✗") + " AutoAuth " + (enabled ? "ON" : "OFF"));
            toggleAuth.addActionListener(e -> autoAuth.toggleEnabled());
            items.add(toggleAuth);

            // Montoya's HttpHandler only controls what goes out on the wire — it has no hook
            // back into an already-open editor pane (e.g. a Repeater tab), so the injected
            // token never appears there on its own. setRequest() is the one API that can push
            // an update into the pane the user is looking at, so offer it as an explicit action.
            if (selection.selectionContext() == MessageEditorHttpRequestResponse.SelectionContext.REQUEST) {
                var syncToken = new JMenuItem("ICARUS → Sync AutoAuth Token");
                syncToken.addActionListener(e -> {
                    HttpRequest current = selection.requestResponse().request();
                    HttpRequest updated = autoAuth.injectIfApplicable(current);
                    if (updated != current) selection.setRequest(updated);
                });
                items.add(syncToken);
            }

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

        var evidenceManager = new JMenuItem("ICARUS → Manage Report Evidence");
        evidenceManager.addActionListener(e -> showEvidenceManager());
        items.add(evidenceManager);

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

        btnReport.addActionListener(e -> generateHtmlReportInteractive(dialog, btnReport, getReportableFindings()));

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
