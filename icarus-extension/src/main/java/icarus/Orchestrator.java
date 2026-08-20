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
import icarus.ui.ToastNotification;

import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

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

        evidenceCapture.setOnApplied(this::registerManualFinding);
    }

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

    public List<FindingRecord> getAllFindingRecords() {
        return findings.getAllFindingRecords();
    }

    public List<FindingRecord> getPassiveFindings() {
        return findings.getPassiveFindings();
    }

    public void clearPassiveFindings() {
        findings.clearPassiveFindings();
    }

    public void runScan(HttpRequestResponse target, boolean isManual) {
        scanRunner.runScan(target, isManual);
    }

    // Delegates for ReportExportService
    public List<Finding> getReportableFindings() {
        return reportExportService.getReportableFindings();
    }

    public void previewReport(Component parent, JButton triggerButton) {
        reportExportService.previewReport(parent, triggerButton);
    }

    public void generateHtmlReportInteractive(Component parent, JButton triggerButton, List<Finding> reportFindings) {
        reportExportService.generateHtmlReportInteractive(parent, triggerButton, reportFindings);
    }

    public void exportPdfReportInteractive(Component parent, JButton triggerButton, List<Finding> reportFindings) {
        reportExportService.exportPdfReportInteractive(parent, triggerButton, reportFindings);
    }

    public boolean generateReport(String format, Path outputFile) throws Exception {
        return reportExportService.generateReport(format, outputFile);
    }

    public ReportTemplateConfig getReportTemplateConfig() {
        return reportExportService.getReportTemplateConfig();
    }

    public void saveReportTemplateConfig(ReportTemplateConfig rtc) {
        reportExportService.saveReportTemplateConfig(rtc);
    }

    // Delegates for ProjectStateService
    public void exportProjectStateInteractive(Component parent, JButton triggerButton) {
        projectStateService.exportProjectStateInteractive(parent, triggerButton);
    }

    public void importProjectStateInteractive(Component parent, JButton triggerButton, Runnable onImported) {
        projectStateService.importProjectStateInteractive(parent, triggerButton, onImported);
    }

    // Delegates for EvidenceTriggerService
    public void captureEvidence(Finding finding, BufferedImage image, String caption) throws IOException {
        evidenceTriggerService.captureEvidence(finding, image, caption);
    }

    public void createManualEvidence(HttpRequestResponse rr) {
        evidenceTriggerService.createManualEvidence(rr);
    }

    public void pasteEvidenceFromClipboard(HttpRequestResponse rr) {
        evidenceTriggerService.pasteEvidenceFromClipboard(rr);
    }

    public void showEvidenceInteractive(Finding finding) {
        evidenceTriggerService.showEvidenceInteractive(finding);
    }

    // Delegates for FindingsReviewDialog
    public void showFindingsDialog(List<FindingRecord> records) {
        new FindingsReviewDialog(this, api).showFindingsDialog(records);
    }

    @Override
    public List<Component> provideMenuItems(ContextMenuEvent event) {
        var items = new ArrayList<Component>();

        var requestResponses = event.messageEditorRequestResponse().isPresent()
                ? List.of(event.messageEditorRequestResponse().get().requestResponse())
                : event.selectedRequestResponses();

        if (requestResponses.isEmpty()) return items;

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
            List<FindingRecord> recordsToShow = new ArrayList<>();
            Set<String> seenHashes = new HashSet<>();
            for (Finding f : newFindings) {
                String hash = f.similarityHash();
                if (!seenHashes.add(hash)) continue;
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
}
