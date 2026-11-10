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
import java.nio.file.Files;
import java.nio.file.Path;

public class ReportExportService {
    private final MontoyaApi api;
    private final icarus.core.ModuleConfig config;
    private final icarus.evidence.ReportGenerator reportGenerator;
    private final icarus.evidence.PdfReportGenerator pdfReportGenerator;
    private final icarus.evidence.EvidenceCapture evidenceCapture;
    private final icarus.core.FindingRegistry findings;

    public ReportExportService(MontoyaApi api, icarus.core.ModuleConfig config, icarus.evidence.ReportGenerator reportGenerator, icarus.evidence.PdfReportGenerator pdfReportGenerator, icarus.evidence.EvidenceCapture evidenceCapture, icarus.core.FindingRegistry findings) {
        this.api = api;
        this.config = config;
        this.reportGenerator = reportGenerator;
        this.pdfReportGenerator = pdfReportGenerator;
        this.evidenceCapture = evidenceCapture;
        this.findings = findings;
    }

    public List<Finding> getReportableFindings() {
        List<Finding> result = new ArrayList<>();
        // One entry per finding identity (hash), in first-capture order. Screenshots are grouped
        // by hash downstream, so two captures holding different Finding objects for the same
        // finding (e.g. one taken before and one after an edit) used to put that finding — with
        // its whole screenshot group — into the report twice, the older one showing stale text.
        Set<String> seenHashes = new java.util.HashSet<>();
        for (var ce : evidenceCapture.getCaptured()) {
            if (!evidenceCapture.isIncluded(ce)) continue;
            String hash = ce.finding().similarityHash();
            if (!seenHashes.add(hash)) continue;
            var record = findings.getRecordByHash(hash);
            if (record == null || record.isSuppressed()
                    || !record.getFinding().similarityHash().equals(hash)) continue;
            // The registry holds the latest edit of this finding.
            result.add(record.getFinding());
        }
        return result;
    }

    public void previewReport(Component parent, JButton triggerButton) {
        // The deliverable is the Evidence Manager's included set — not every finding the
        // registry has accumulated across past scans/sessions. Rendering the raw registry
        // made the preview balloon to hundreds of auto-rendered evidence images.
        List<Finding> reportFindings = getReportableFindings();
        if (reportFindings.isEmpty()) {
            JOptionPane.showMessageDialog(parent,
                    "No evidence to preview yet — use \"Send to Reporter Creation\" or the Evidence Manager first.");
            return;
        }

        Path tempFile;
        try {
            tempFile = Files.createTempFile("icarus-report-preview-", ".pdf");
            tempFile.toFile().deleteOnExit(); // previews otherwise pile up in the temp dir
        } catch (IOException e) {
            api.logging().logToError("Failed to create preview temp file: " + e);
            JOptionPane.showMessageDialog(parent, "Failed to create a temp file for the preview: " + e.getMessage());
            return;
        }

        if (triggerButton != null) triggerButton.setEnabled(false);
        new SwingWorker<Boolean, Void>() {
            @Override
            protected Boolean doInBackground() throws Exception {
                return writeReport(true, reportFindings, tempFile);
            }

            @Override
            protected void done() {
                if (triggerButton != null) triggerButton.setEnabled(true);
                Frame suiteFrame = api.userInterface().swingUtils().suiteFrame();
                try {
                    boolean written = get();
                    if (!written) {
                        ToastNotification.show(suiteFrame, "No preview generated.");
                        return;
                    }
                    openWithDefaultViewer(tempFile, parent);
                } catch (Exception ex) {
                    api.logging().logToError("Report preview failed: " + ex.getCause());
                    JOptionPane.showMessageDialog(parent, "Report preview failed: " + ex.getCause());
                }
            }
        }.execute();
    }

    private void openWithDefaultViewer(Path file, Component parent) {
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
            try {
                Desktop.getDesktop().open(file.toFile());
                return;
            } catch (IOException ex) {
                api.logging().logToError("Failed to open preview in default PDF viewer: " + ex);
                // fall through to the manual-path message below
            }
        }
        JOptionPane.showMessageDialog(parent,
                "Couldn't open a PDF viewer automatically. Preview saved at:\n" + file.toAbsolutePath());
    }

    public void generateHtmlReportInteractive(Component parent, JButton triggerButton, List<Finding> reportFindings) {
        exportReportInteractive(parent, triggerButton, reportFindings, "html", "report.html", "HTML Report",
                (findings, cfg, capture, out) -> writeReport(false, findings, out));
    }

    public void exportPdfReportInteractive(Component parent, JButton triggerButton, List<Finding> reportFindings) {
        exportReportInteractive(parent, triggerButton, reportFindings, "pdf", "report.pdf", "PDF Report",
                (findings, cfg, capture, out) -> writeReport(true, findings, out));
    }

    /**
     * PDF always renders through the profile context, the same path Preview uses, so the
     * selected cover and finding layout (and an imported template) are not replaced by the
     * legacy generator. HTML without canvas pages stays on that legacy generator, which also
     * honors the evidence.html_report switch. The working dir is the output's parent.
     */
    private boolean writeReport(boolean pdf, List<Finding> reportFindings, Path out) throws IOException {
        icarus.report.DefaultReportProfileManager profiles = new icarus.report.DefaultReportProfileManager(config);
        icarus.report.model.ReportProfile profile = profiles.active();
        boolean canvas = icarus.report.canvas.CanvasPages.hasCanvas(profile);
        if (!pdf && !canvas) {
            return reportGenerator.generate(reportFindings, config, evidenceCapture, out);
        }
        // Non-canvas PDF used to sort worst-first inside the legacy generator. Keep that order
        // when the profile's own finding renderer draws the cards.
        List<Finding> ordered = reportFindings;
        if (pdf && !canvas) {
            ordered = new ArrayList<>(reportFindings);
            ordered.sort(java.util.Comparator.comparingInt(f -> f.severity().ordinal()));
        }
        // Same title and project sources as the legacy generators: Report Details first, then Burp's project name.
        ReportTemplateConfig rtc = ReportTemplateConfig.fromConfig(config);
        rtc.injectFindingsVariables(ordered);
        String title = rtc.variables().get("report_title");
        String project = rtc.variables().get("project");
        if (project == null || project.isBlank()) {
            project = null;
            if (config.getBool("evidence.include_project_name", true)) {
                String name = api.project().name();
                if (name != null && !name.isBlank()) project = name;
            }
        }
        icarus.report.render.ReportData mapped = icarus.report.ReportDataMapper.buildReportData(
                ordered, evidenceCapture, config, profile.content(), title, project);
        // Report Details fields ({{author}}, {{client}}, ...) reach canvas pages through the variables.
        Map<String, String> vars = new LinkedHashMap<>(rtc.variables());
        vars.putAll(mapped.variables());
        icarus.report.render.ReportData data = new icarus.report.render.ReportData(mapped.reportTitle(), mapped.projectName(),
                mapped.findings(), mapped.severityCounts(), mapped.fixedCount(), mapped.notFixedCount(), vars, mapped.isRetest());
        icarus.report.render.ReportRenderContext ctx = profiles.bind(data, out.toAbsolutePath().getParent());
        return pdf ? pdfReportGenerator.generate(ctx, out) : reportGenerator.generate(ctx, out);
    }

    public Path generateReport(String format, Map<String, String> templateVariables) throws Exception {
        // Same set as previewReport / get_reportable_findings — the Evidence Manager's
        // included findings, not the whole cross-session registry.
        List<Finding> reportFindings = getReportableFindings();
        if (reportFindings.isEmpty()) return null;

        ReportTemplateConfig rtc = ReportTemplateConfig.fromConfig(config);
        rtc.variables().putAll(templateVariables);
        rtc.saveTo(config);
        api.persistence().extensionData().setString("config", config.serialize());

        boolean pdf = "pdf".equalsIgnoreCase(format);
        Path dir = Path.of(EvidencePaths.defaultOutputDir(api, config));
        Files.createDirectories(dir);
        Path outputFile = dir.resolve("icarus-report-" + System.currentTimeMillis() + (pdf ? ".pdf" : ".html"));

        boolean written = writeReport(pdf, reportFindings, outputFile);

        return written ? outputFile.toAbsolutePath() : null;
    }

    public void saveReportTemplateConfig(icarus.core.ReportTemplateConfig rtc) {
        rtc.saveTo(config);
        api.persistence().extensionData().setString("config", config.serialize());
    }

    private void exportReportInteractive(Component parent, JButton triggerButton, List<Finding> reportFindings,
                                          String extension, String defaultFileName, String formatLabel, ReportWriter writer) {
        JFileChooser fc = new JFileChooser(new java.io.File(EvidencePaths.defaultOutputDir(api, config)));
        fc.setSelectedFile(new java.io.File(defaultFileName));

        icarus.report.DefaultReportProfileManager profileManager = new icarus.report.DefaultReportProfileManager(config);
        JPanel accessory = new JPanel(new BorderLayout(4, 4));
        accessory.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createTitledBorder("Report Model / Profile"),
            new javax.swing.border.EmptyBorder(4, 4, 4, 4)
        ));
        JComboBox<icarus.report.model.ReportProfile> profileCombo = new JComboBox<>();
        for (var p : profileManager.list()) profileCombo.addItem(p);
        profileCombo.setSelectedItem(profileManager.active());
        profileCombo.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
                super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                if (value instanceof icarus.report.model.ReportProfile p) {
                    setText((p.builtIn() ? "🔒 " : "✏️ ") + p.name() + (p.builtIn() ? " [Built-in]" : " [Custom]"));
                }
                return this;
            }
        });
        accessory.add(profileCombo, BorderLayout.CENTER);
        fc.setAccessory(accessory);

        if (fc.showSaveDialog(parent) != JFileChooser.APPROVE_OPTION) {
            return;
        }

        var chosenProfile = (icarus.report.model.ReportProfile) profileCombo.getSelectedItem();
        if (chosenProfile != null) {
            profileManager.setActive(chosenProfile.id());
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
                                "No report was generated — HTML reports may be disabled in Settings, or there's no evidence to include yet.");
                    }
                } catch (Exception ex) {
                    api.logging().logToError(formatLabel + " generation failed: " + ex.getCause());
                    JOptionPane.showMessageDialog(parent, formatLabel + " generation failed: " + ex.getCause());
                }
            }
        }.execute();
    }

    public icarus.core.ReportTemplateConfig getReportTemplateConfig() {
        return icarus.core.ReportTemplateConfig.fromConfig(config);
    }

 

    @FunctionalInterface
    public interface ReportWriter {
        boolean write(java.util.List<icarus.core.Finding> findings, icarus.core.ModuleConfig config, icarus.evidence.EvidenceCapture capture, Path outputFile) throws Exception;
    }
}
