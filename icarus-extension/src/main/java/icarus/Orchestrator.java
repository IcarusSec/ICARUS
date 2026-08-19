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
import icarus.evidence.ProjectStateCodec;
import icarus.evidence.ReportGenerator;
import icarus.ui.ToastNotification;

import javax.swing.*;
import java.awt.*;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

    private Runnable showEvidenceAction;

    // Local, in-process drag-and-drop transfer of a CapturedEvidence reference from an
    // evidence card (Evidence Manager detail panel) onto a finding in the master list, to
    // move that screenshot to a different finding. No serialization involved — Swing DnD
    // between components in the same JVM just carries the object reference.

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
    
    public void updateFinding(Finding finding) {
        findings.processDeduplication(List.of(finding), false);
    }
    
    public EvidenceCapture getEvidenceCapture() {
        return evidenceCapture;
    }

    public AutoAuthModule autoAuth() {
        return autoAuth;
    }

    public void addListener(Consumer<List<FindingRecord>> listener) {
        findings.addListener(listener);
    }
    
    public void setShowEvidenceAction(Runnable action) {
        this.showEvidenceAction = action;
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
     * Grouped by {@link Finding#similarityHash()}, not Finding object identity — re-editing a
     * finding's evidence (Evidence Editor "Apply") builds a brand new Finding instance with the
     * same hash, so identity-based matching used to silently orphan every prior screenshot for
     * that finding instead of keeping them as additional evidence for the same reportable entry.
     * Returns the registry's current canonical Finding per hash (freshest title/severity/etc.),
     * one per hash regardless of how many CapturedEvidence entries share it — matching
     * {@link icarus.evidence.EvidenceCapture#groupedBySimilarityHash()}'s keys, which is what
     * the report generators actually iterate for each finding's evidence images.
     */
    public List<Finding> getReportableFindings() {
        List<Finding> result = new ArrayList<>();
        Set<String> seenHashes = new HashSet<>();
        for (var ce : evidenceCapture.getCaptured()) {
            if (!evidenceCapture.isIncluded(ce)) continue;
            String hash = ce.finding().similarityHash();
            if (!seenHashes.add(hash)) continue;
            var record = findings.getRecordByHash(hash);
            if (record == null || record.isSuppressed()) continue;
            result.add(record.getFinding());
        }
        return result;
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

    /**
     * Non-interactive counterpart to {@link #generateHtmlReportInteractive}/{@link #exportPdfReportInteractive}
     * for callers with no {@link Component} to anchor a file chooser (the MCP tool surface) —
     * writes straight to {@code outputFile}, overwriting it if it already exists. Defaults to
     * every non-suppressed finding rather than {@link #getReportableFindings()}: that method
     * only returns findings with manual evidence applied via the Evidence Manager UI, which an
     * MCP client has no way to do.
     *
     * @param format {@code "html"} or {@code "pdf"}
     * @return true if a report was written (false if there was nothing to report, e.g. HTML reports disabled)
     */
    public boolean generateReport(String format, Path outputFile) throws Exception {
        List<Finding> reportFindings = new ArrayList<>();
        for (FindingRecord record : getAllFindingRecords()) {
            if (!record.isSuppressed()) reportFindings.add(record.getFinding());
        }
        return switch (format.toLowerCase()) {
            case "html" -> reportGenerator.generate(reportFindings, config, evidenceCapture, outputFile);
            case "pdf" -> pdfReportGenerator.generate(reportFindings, config, evidenceCapture, outputFile);
            default -> throw new IllegalArgumentException("Unknown report format: " + format + " (expected html or pdf)");
        };
    }

    private void exportReportInteractive(Component parent, JButton triggerButton, List<Finding> reportFindings,
                                          String extension, String defaultFileName, String formatLabel, ReportWriter writer) {
        JFileChooser fc = new JFileChooser(new java.io.File(EvidencePaths.defaultOutputDir(api, config)));
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
     * Exports the full Evidence Manager state (findings, screenshots, captions, inclusion,
     * and the active {@link ReportTemplateConfig}) to a portable {@code .icarus} project
     * file, via {@link ProjectStateCodec}. Base64-encoding every screenshot is the expensive
     * part, so it runs in {@code doInBackground} — a large evidence set shouldn't freeze the
     * dialog while exporting.
     */
    public void exportProjectStateInteractive(Component parent, JButton triggerButton) {
        List<EvidenceCapture.CapturedEvidence> evidence = evidenceCapture.getCaptured();
        if (evidence.isEmpty()) {
            JOptionPane.showMessageDialog(parent, "No captured evidence to export yet.");
            return;
        }

        JFileChooser fc = new JFileChooser(new java.io.File(EvidencePaths.defaultOutputDir(api, config)));
        fc.setSelectedFile(new java.io.File("project.icarus"));
        if (fc.showSaveDialog(parent) != JFileChooser.APPROVE_OPTION) {
            return;
        }

        java.io.File selectedFile = fc.getSelectedFile();
        if (!selectedFile.getName().toLowerCase().endsWith(".icarus")) {
            selectedFile = new java.io.File(selectedFile.getParentFile(), selectedFile.getName() + ".icarus");
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
        ReportTemplateConfig rtc = ReportTemplateConfig.fromConfig(config);

        if (triggerButton != null) triggerButton.setEnabled(false);
        new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() throws Exception {
                String json = ProjectStateCodec.export(evidence, evidenceCapture::isIncluded, rtc);
                Files.writeString(finalSelectedFile.toPath(), json);
                return null;
            }

            @Override
            protected void done() {
                if (triggerButton != null) triggerButton.setEnabled(true);
                Frame suiteFrame = api.userInterface().swingUtils().suiteFrame();
                try {
                    get();
                    ToastNotification.show(suiteFrame, "Project exported: " + finalSelectedFile.getAbsolutePath());
                } catch (Exception ex) {
                    api.logging().logToError("Project export failed: " + ex.getCause());
                    JOptionPane.showMessageDialog(parent, "Project export failed: " + ex.getCause());
                }
            }
        }.execute();
    }

    /**
     * Imports a {@code .icarus} project file, fully replacing current Evidence Manager state
     * (simpler than a merge, and matches the "baseline for a retest months later" use case) —
     * every imported finding is re-registered into {@link FindingRegistry} via the same
     * dedup path manual evidence capture uses, so it's immediately visible in the Results tab
     * and reportable, not just sitting in {@link EvidenceCapture} orphaned from the registry.
     */
    public void importProjectStateInteractive(Component parent, JButton triggerButton, Runnable onImported) {
        JFileChooser fc = new JFileChooser(new java.io.File(EvidencePaths.defaultOutputDir(api, config)));
        if (fc.showOpenDialog(parent) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        java.io.File selectedFile = fc.getSelectedFile();

        int confirm = JOptionPane.showConfirmDialog(parent,
                "Importing replaces all evidence currently in the Evidence Manager. Continue?",
                "Confirm Import", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (confirm != JOptionPane.YES_OPTION) {
            return;
        }

        if (triggerButton != null) triggerButton.setEnabled(false);
        new SwingWorker<ProjectStateCodec.ImportResult, Void>() {
            @Override
            protected ProjectStateCodec.ImportResult doInBackground() throws Exception {
                String json = Files.readString(selectedFile.toPath());
                return ProjectStateCodec.importFrom(json);
            }

            @Override
            protected void done() {
                if (triggerButton != null) triggerButton.setEnabled(true);
                Frame suiteFrame = api.userInterface().swingUtils().suiteFrame();
                try {
                    ProjectStateCodec.ImportResult result = get();
                    Path dir = Path.of(EvidencePaths.defaultOutputDir(api, config));
                    Files.createDirectories(dir);

                    evidenceCapture.clearAll();
                    for (var item : result.items()) {
                        String filename = "evidence-" + item.finding().type().replaceAll("[^a-zA-Z0-9.-]", "_")
                                + "-" + System.currentTimeMillis() + "-" + java.util.UUID.randomUUID() + ".png";
                        Path imagePath = dir.resolve(filename);
                        Files.write(imagePath, item.imageBytes());
                        java.awt.image.BufferedImage image = javax.imageio.ImageIO.read(imagePath.toFile());
                        var ce = new EvidenceCapture.CapturedEvidence(item.finding(), imagePath, image, item.caption());
                        evidenceCapture.restoreCaptured(ce, item.included());
                        findings.processDeduplication(List.of(item.finding()), false);
                    }
                    result.reportTemplateConfig().saveTo(config);
                    api.persistence().extensionData().setString("config", config.serialize());

                    onImported.run();
                    ToastNotification.show(suiteFrame, "Project imported: " + result.items().size() + " evidence item(s).");
                } catch (Exception ex) {
                    api.logging().logToError("Project import failed: " + ex.getCause());
                    JOptionPane.showMessageDialog(parent, "Project import failed: " + ex.getCause());
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

        // Reuse PassiveErrorModule's detection if it exists instead of a second copy of the same
        // VerboseErrorDetector/status-code checks living here.
        IcarusModule pem = modules.stream().filter(m -> m.getClass().getSimpleName().equals("PassiveErrorModule")).findFirst().orElse(null);
        List<Finding> errorFindings = pem != null ? pem.run(rr, config, msg -> {}) : List.of();
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

    /**
     * Reads a screenshot off the system clipboard (e.g. an OS/browser screenshot of Burp's
     * embedded browser tab) and opens it directly in ICARUS's annotation editor as manual
     * evidence, tied to {@code rr} the same way {@link #blankManualFinding} is — keeps
     * {@link Finding#evidence()} non-null so this doesn't get miscategorized as a passive
     * finding by {@link FindingRegistry#getPassiveFindings()}.
     * <p>
     * This is the safe alternative to injecting a capture script into proxied HTML responses
     * (see {@code .claude/in_browser_evidence_capture_plan.md}): that would alter every page's
     * DOM for every client behind the proxy, not just Burp's embedded browser, and Montoya's
     * {@code ProxyRequestHandler} has no way to short-circuit a request with a synthetic
     * response anyway (only {@code drop()}/{@code continueWith()} — {@code spoof()} exists
     * only on the generic {@code HttpHandler}). Any OS screenshot tool already captures the
     * embedded Chromium browser's real rendered pixels; this just wires "paste" into the
     * existing annotation flow instead of reinventing screen capture.
     */
    public void pasteEvidenceFromClipboard(HttpRequestResponse rr) {
        Frame suiteFrame = api.userInterface().swingUtils().suiteFrame();
        Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
        try {
            if (!clipboard.isDataFlavorAvailable(DataFlavor.imageFlavor)) {
                JOptionPane.showMessageDialog(suiteFrame,
                        "No image found on the clipboard. Take a screenshot first, then try again.");
                return;
            }
            Image raw = (Image) clipboard.getData(DataFlavor.imageFlavor);
            java.awt.image.BufferedImage image = toBufferedImage(raw);
            evidenceCapture.captureInteractiveWithImage(blankManualFinding(rr), image);
        } catch (Exception e) {
            api.logging().logToError("Failed to read image from clipboard: " + e);
            JOptionPane.showMessageDialog(suiteFrame, "Could not read image from clipboard: " + e.getMessage());
        }
    }

    private java.awt.image.BufferedImage toBufferedImage(Image img) {
        if (img instanceof java.awt.image.BufferedImage bi) return bi;
        var bi = new java.awt.image.BufferedImage(img.getWidth(null), img.getHeight(null), java.awt.image.BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2 = bi.createGraphics();
        g2.drawImage(img, 0, 0, null);
        g2.dispose();
        return bi;
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

        // 1. AutoAuth: Context-sensitive actions at the top if applicable
        event.messageEditorRequestResponse().ifPresent(selection -> {
            JMenu authMenu = new JMenu("ICARUS → AutoAuth");
            
            boolean enabled = autoAuth.isEnabled();
            var toggleAuth = new JMenuItem((enabled ? "✓" : "✗") + " AutoAuth " + (enabled ? "ON" : "OFF"));
            toggleAuth.addActionListener(e -> autoAuth.toggleEnabled());
            authMenu.add(toggleAuth);

            if (selection.selectionContext() == MessageEditorHttpRequestResponse.SelectionContext.REQUEST) {
                var syncToken = new JMenuItem("Sync AutoAuth Token to Editor");
                syncToken.addActionListener(e -> {
                    HttpRequest current = selection.requestResponse().request();
                    HttpRequest updated = autoAuth.injectIfApplicable(current);
                    if (updated != current) selection.setRequest(updated);
                });
                authMenu.add(syncToken);
            }

            if (!selection.selectionOffsets().isEmpty()) {
                if (selection.selectionContext() == MessageEditorHttpRequestResponse.SelectionContext.RESPONSE) {
                    var setSource = new JMenuItem("Set Selection as Auth Token Source");
                    setSource.addActionListener(e -> autoAuth.setSourceFromSelection(selection));
                    authMenu.add(setSource);
                } else {
                    var addDestination = new JMenuItem("Add Selection as Auth Token Destination");
                    addDestination.addActionListener(e -> autoAuth.addDestinationFromSelection(selection));
                    authMenu.add(addDestination);
                }
            }
            items.add(authMenu);
        });

        // 2. Evidence & Reporting: High usage for manual testing
        if (icarus.Icarus.HTML_and_PDF_REPORT) {
            JMenu evidenceMenu = new JMenu("ICARUS → Evidence & Reporting");
            var createEvidence = new JMenuItem("Send to Reporter Creation");
            createEvidence.addActionListener(e -> {
                for (var rr : requestResponses) {
                    createManualEvidence(rr);
                }
            });
            evidenceMenu.add(createEvidence);

            var pasteEvidence = new JMenuItem("Paste Screenshot as Evidence");
            pasteEvidence.addActionListener(e -> pasteEvidenceFromClipboard(requestResponses.get(0)));
            evidenceMenu.add(pasteEvidence);

            var evidenceManager = new JMenuItem("Manage Report Evidence");
            evidenceManager.addActionListener(e -> {
                if (showEvidenceAction != null) showEvidenceAction.run();
            });
            evidenceMenu.add(evidenceManager);
            
            items.add(evidenceMenu);
        }

        // 3. Active Scanning Actions
        var runAll = new JMenuItem("ICARUS → Run All Modules");
        runAll.addActionListener(e -> {
            for (var rr : requestResponses) {
                scanRunner.runScan(rr, true);
            }
        });
        items.add(runAll);

        JMenu modulesMenu = new JMenu("ICARUS → Modules");
        for (var module : modules) {
            var item = new JMenuItem(module.name());
            item.addActionListener(e -> {
                for (var rr : requestResponses) {
                    scanRunner.runModule(module, rr, true);
                }
            });
            modulesMenu.add(item);
        }
        items.add(modulesMenu);

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
        java.awt.Frame parent = api.userInterface().swingUtils().suiteFrame();
        JFrame dialog = new JFrame("ICARUS Scan Results");
        if (parent != null) dialog.setIconImage(parent.getIconImage());
        dialog.setSize(1200, 800);
        dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
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
