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
import icarus.modules.PassiveErrorModule;
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
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
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

    private final ReportExportService reportExportService;
    private final ProjectStateService projectStateService;
    private final EvidenceTriggerService evidenceTriggerService;

    private Runnable showEvidenceAction;


    // Local, in-process drag-and-drop transfer of a CapturedEvidence reference from an
    // evidence card (Evidence Manager detail panel) onto a finding in the master list, to
    // move that screenshot to a different finding. No serialization involved — Swing DnD
    // between components in the same JVM just carries the object reference.
    private static final DataFlavor EVIDENCE_DRAG_FLAVOR =
            new DataFlavor(EvidenceCapture.CapturedEvidence.class, "ICARUS Evidence Card");

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

        this.reportExportService = new ReportExportService(api, config, reportGenerator, this.pdfReportGenerator, evidenceCapture, this.findings);
        this.projectStateService = new ProjectStateService(api, config, evidenceCapture, this.findings);
        this.evidenceTriggerService = new EvidenceTriggerService(api, config, evidenceCapture, modules, autoAuth, this);

        // Initialize VulnerabilityKnowledgeBase
        String kbOutputDir = EvidencePaths.defaultOutputDir(api, config);
        VulnerabilityKnowledgeBase.getInstance().initialize(kbOutputDir);

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
    
    public burp.api.montoya.MontoyaApi api() { return api; }

    public EvidenceCapture getEvidenceCapture() {
        return evidenceCapture;
    }

    public void setShowEvidenceAction(Runnable action) {
        this.showEvidenceAction = action;
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

    

    

    

    

    

    

    

    

    

    

    /**
     * Exports the full Evidence Manager state (findings, screenshots, captions, inclusion,
     * and the active {@link ReportTemplateConfig}) to a portable {@code .icarus} project
     * file, via {@link ProjectStateCodec}. Base64-encoding every screenshot is the expensive
     * part, so it runs in {@code doInBackground} — a large evidence set shouldn't freeze the
     * dialog while exporting.
     */

    /**
     * Imports a {@code .icarus} project file, fully replacing current Evidence Manager state
     * (simpler than a merge, and matches the "baseline for a retest months later" use case) —
     * every imported finding is re-registered into {@link FindingRegistry} via the same
     * dedup path manual evidence capture uses, so it's immediately visible in the Results tab
     * and reportable, not just sitting in {@link EvidenceCapture} orphaned from the registry.
     */

    /**
     * Renders the actual PDF (cover page, gradient band, risk tables, everything
     * {@link #exportPdfReportInteractive} would produce) to a temp file and opens it in the
     * system's default PDF viewer via {@link Desktop#open}, stdlib, no new dependency. The
     * HTML report has drifted from the PDF's design — no cover page, no risk-matrix tables —
     * so it stopped being a meaningful preview of what actually gets delivered; the PDF export
     * path itself is what needs previewing. Writes nothing to the user's chosen report
     * location and never touches FindingRegistry — purely a look.
     */

    /**
     * Adds a manually-confirmed finding — the MCP server's {@code add_finding} tool, for an
     * LLM that verified a vulnerability itself (outside any ICARUS module, e.g. by sending
     * its own requests) and wants it folded into the same registry/report pipeline as
     * everything else. {@code cweIds} may be empty; {@code rawRequest}/{@code rawResponse}
     * become the evidence "screenshot" via {@link EvidenceCapture#captureManual}, rendered
     * verbatim into their own REQUEST/RESPONSE columns rather than merged into one field.
     */
    public void addFinding(String type, String description, Severity severity, List<String> cweIds,
                            String rawRequest, String rawResponse) {
        Finding.Builder builder = Finding.builder("MCP", type)
                .description(description)
                .severity(severity)
                .category(Category.MANUAL)
                .path(type);
        for (String cwe : cweIds) builder.cwe(cwe);
        evidenceCapture.captureManual(builder.build(), rawRequest, rawResponse);
    }

    /**
     * Non-interactive report generation for the MCP server ({@code generate_icarus_report}
     * tool) — no file chooser, no Swing thread. Hydrates the ICARUS template fields
     * ({@code classification}, {@code team}, {@code requester}, etc.) into the persisted {@link ReportTemplateConfig}
     * before rendering, same as a human filling in the Reporting tab, then writes to a
     * timestamped file under {@link EvidencePaths#defaultOutputDir}.
     *
     * @return the written file's absolute path, or null if there were no reportable findings.
     */



    /**
     * Shared by the "ICARUS Scan Results" dialog and the Results tab's own report buttons —
     * both just gather whatever {@link Finding}s they're showing and hand them here.
     *
     * @param parent used to anchor the file chooser / confirm dialogs
     * @param triggerButton disabled while generating and re-enabled after, if not null
     */

    /** Same shell as {@link #generateHtmlReportInteractive}, writing via {@link PdfReportGenerator} instead. */

    





    /**
     * Shared by the "Create Evidence" context-menu item and the Ctrl+P hotkey handler —
     * both entry points get Smart Evidence detection for free by routing through here.
     */

    /**
     * Quietly checks the response for something worth flagging (verbose error / server
     * error, or an unencoded reflection of a request parameter) and, if the user confirms,
     * pre-fills the evidence with that finding instead of the blank manual template.
     */



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


    public void shutdown() {
        scanRunner.shutdown();
        api.persistence().extensionData().setString("icarus_state", findings.serializeState());
    }

    public void restoreState(String stateJson) {
        findings.deserializeState(stateJson);
    }

    // Delegates for ReportExportService
    public java.util.List<icarus.core.Finding> getReportableFindings() { return reportExportService.getReportableFindings(); }
    public void previewReport(java.awt.Component parent, javax.swing.JButton triggerButton) { reportExportService.previewReport(parent, triggerButton); }
    public void generateHtmlReportInteractive(java.awt.Component parent, javax.swing.JButton triggerButton, java.util.List<icarus.core.Finding> reportFindings) { reportExportService.generateHtmlReportInteractive(parent, triggerButton, reportFindings); }
    public void exportPdfReportInteractive(java.awt.Component parent, javax.swing.JButton triggerButton, java.util.List<icarus.core.Finding> reportFindings) { reportExportService.exportPdfReportInteractive(parent, triggerButton, reportFindings); }
    public java.nio.file.Path generateReport(String format, java.util.Map<String, String> templateVariables) throws Exception { return reportExportService.generateReport(format, templateVariables); }
    public icarus.core.ReportTemplateConfig getReportTemplateConfig() { return reportExportService.getReportTemplateConfig(); }
    public void saveReportTemplateConfig(icarus.core.ReportTemplateConfig rtc) { reportExportService.saveReportTemplateConfig(rtc); }

    // Delegates for ProjectStateService
    public void exportProjectStateInteractive(java.awt.Component parent, javax.swing.JButton triggerButton) { projectStateService.exportProjectStateInteractive(parent, triggerButton); }
    public void importProjectStateInteractive(java.awt.Component parent, javax.swing.JButton triggerButton, Runnable onImported) { projectStateService.importProjectStateInteractive(parent, triggerButton, onImported); }

    public ModuleConfig getConfig() {
        return config;
    }

    // Delegates for VulnerabilityKnowledgeBase
    public java.util.List<icarus.core.KnowledgeBaseEntry> getKnowledgeBaseEntries() { return icarus.core.VulnerabilityKnowledgeBase.getInstance().getAllEntries(); }
    public icarus.core.KnowledgeBaseEntry getKnowledgeBaseEntry(String name) { return icarus.core.VulnerabilityKnowledgeBase.getInstance().getEntry(name); }
    public void upsertKnowledgeBaseEntry(icarus.core.KnowledgeBaseEntry entry) { icarus.core.VulnerabilityKnowledgeBase.getInstance().upsertEntry(entry); }
    public void deleteKnowledgeBaseEntry(String name) { icarus.core.VulnerabilityKnowledgeBase.getInstance().deleteEntry(name); }

    // Delegates for EvidenceTriggerService
    public void captureEvidence(icarus.core.Finding finding, java.awt.image.BufferedImage image, String caption) throws java.io.IOException { evidenceTriggerService.captureEvidence(finding, image, caption); }
    public void createManualEvidence(burp.api.montoya.http.message.HttpRequestResponse rr) { evidenceTriggerService.createManualEvidence(rr); }
    public void pasteEvidenceFromClipboard(burp.api.montoya.http.message.HttpRequestResponse rr) { evidenceTriggerService.pasteEvidenceFromClipboard(rr); }
    public void showEvidenceInteractive(icarus.core.Finding finding) { evidenceTriggerService.showEvidenceInteractive(finding); }

    // Delegates for FindingsReviewDialog
    public void showFindingsDialog(java.util.List<icarus.core.FindingRecord> records) { new FindingsReviewDialog(this, api).showFindingsDialog(records); }

    @Override
    public java.util.List<java.awt.Component> provideMenuItems(burp.api.montoya.ui.contextmenu.ContextMenuEvent event) {
        var items = new java.util.ArrayList<java.awt.Component>();

        var requestResponses = event.messageEditorRequestResponse().isPresent()
                ? List.of(event.messageEditorRequestResponse().get().requestResponse())
                : event.selectedRequestResponses();

        if (requestResponses.isEmpty()) return items;

        // 1. AutoAuth: Context-sensitive actions at the top if applicable
        event.messageEditorRequestResponse().ifPresent(selection -> {
            JMenu authMenu = new JMenu(I18n.t("contextmenu.autoauth"));
            boolean enabled = autoAuth.isEnabled();
            var toggleAuth = new JMenuItem(I18n.t(enabled ? "contextmenu.autoauth.toggle.on" : "contextmenu.autoauth.toggle.off"));
            toggleAuth.addActionListener(e -> autoAuth.toggleEnabled());
            authMenu.add(toggleAuth);

            if (selection.selectionContext() == MessageEditorHttpRequestResponse.SelectionContext.REQUEST) {
                var syncToken = new JMenuItem(I18n.t("contextmenu.autoauth.sync"));
                syncToken.addActionListener(e -> {
                    HttpRequest current = selection.requestResponse().request();
                    HttpRequest updated = autoAuth.injectIfApplicable(current);
                    if (updated != current) selection.setRequest(updated);
                });
                authMenu.add(syncToken);
            }

            if (!selection.selectionOffsets().isEmpty()) {
                if (selection.selectionContext() == MessageEditorHttpRequestResponse.SelectionContext.RESPONSE) {
                    var setSource = new JMenuItem(I18n.t("contextmenu.autoauth.set_source"));
                    setSource.addActionListener(e -> autoAuth.setSourceFromSelection(selection));
                    authMenu.add(setSource);
                } else {
                    var addDestination = new JMenuItem(I18n.t("contextmenu.autoauth.add_destination"));
                    addDestination.addActionListener(e -> autoAuth.addDestinationFromSelection(selection));
                    authMenu.add(addDestination);
                }
            }
            items.add(authMenu);
        });

        // 2. Evidence & Reporting
        JMenu evidenceMenu = new JMenu(I18n.t("contextmenu.evidence"));
        var createEvidence = new JMenuItem(I18n.t("contextmenu.evidence.create"));
        createEvidence.addActionListener(e -> {
            for (var rr : requestResponses) {
                createManualEvidence(rr);
            }
        });
        evidenceMenu.add(createEvidence);

        var pasteEvidence = new JMenuItem(I18n.t("contextmenu.evidence.paste"));
        pasteEvidence.addActionListener(e -> pasteEvidenceFromClipboard(requestResponses.get(0)));
        evidenceMenu.add(pasteEvidence);

        var evidenceManager = new JMenuItem(I18n.t("contextmenu.evidence.manage"));
        evidenceManager.addActionListener(e -> {
            if (showEvidenceAction != null) showEvidenceAction.run();
        });
        evidenceMenu.add(evidenceManager);
        items.add(evidenceMenu);

        // 3. Active Scanning Actions
        var runAll = new JMenuItem(I18n.t("contextmenu.modules.run_all"));
        runAll.addActionListener(e -> {
            for (var rr : requestResponses) {
                scanRunner.runScan(rr, true);
            }
        });
        items.add(runAll);

        JMenu modulesMenu = new JMenu(I18n.t("contextmenu.modules"));
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

    public RequestToBeSentAction handleHttpRequestToBeSent(HttpRequestToBeSent request) {
        return RequestToBeSentAction.continueWith(autoAuth.processOutgoingRequest(request));
    }

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
        } else if (!newOrUpdated.isEmpty() && config.getBool("ui.show_popups", false)) {
            List<FindingRecord> recordsToShow = new ArrayList<>();
            for (Finding f : newOrUpdated) {
                FindingRecord r = findings.getRecordByHash(f.similarityHash());
                if (r != null) recordsToShow.add(r);
            }
            SwingUtilities.invokeLater(() -> showFindingsDialog(recordsToShow));
        }
    }



}
