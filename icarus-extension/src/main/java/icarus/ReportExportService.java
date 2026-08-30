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
        } catch (IOException e) {
            api.logging().logToError("Failed to create preview temp file: " + e);
            JOptionPane.showMessageDialog(parent, "Failed to create a temp file for the preview: " + e.getMessage());
            return;
        }

        if (triggerButton != null) triggerButton.setEnabled(false);
        new SwingWorker<Boolean, Void>() {
            @Override
            protected Boolean doInBackground() throws Exception {
                return pdfReportGenerator.generate(reportFindings, config, evidenceCapture, tempFile);
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
                (findings, cfg, capture, out) -> reportGenerator.generate(findings, cfg, capture, out));
    }

    public void exportPdfReportInteractive(Component parent, JButton triggerButton, List<Finding> reportFindings) {
        exportReportInteractive(parent, triggerButton, reportFindings, "pdf", "report.pdf", "PDF Report",
                (findings, cfg, capture, out) -> pdfReportGenerator.generate(findings, cfg, capture, out));
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

        boolean written = pdf
                ? pdfReportGenerator.generate(reportFindings, config, evidenceCapture, outputFile)
                : reportGenerator.generate(reportFindings, config, evidenceCapture, outputFile);

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
