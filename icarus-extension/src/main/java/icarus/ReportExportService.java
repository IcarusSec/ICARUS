package icarus;

import burp.api.montoya.MontoyaApi;
import icarus.core.Finding;
import icarus.core.FindingRecord;
import icarus.core.FindingRegistry;
import icarus.core.ReportTemplateConfig;
import icarus.core.ModuleConfig;
import icarus.evidence.EvidenceCapture;
import icarus.evidence.PdfReportGenerator;
import icarus.evidence.ReportGenerator;
import icarus.ui.ToastNotification;

import javax.swing.*;
import java.awt.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class ReportExportService {
    private final MontoyaApi api;
    private final ModuleConfig config;
    private final ReportGenerator reportGenerator;
    private final PdfReportGenerator pdfReportGenerator;
    private final EvidenceCapture evidenceCapture;
    private final FindingRegistry findings;

    public ReportExportService(MontoyaApi api, ModuleConfig config, ReportGenerator reportGenerator,
                               PdfReportGenerator pdfReportGenerator, EvidenceCapture evidenceCapture,
                               FindingRegistry findings) {
        this.api = api;
        this.config = config;
        this.reportGenerator = reportGenerator;
        this.pdfReportGenerator = pdfReportGenerator;
        this.evidenceCapture = evidenceCapture;
        this.findings = findings;
    }

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
    public interface ReportWriter {
        boolean write(List<Finding> findings, ModuleConfig config, EvidenceCapture capture, Path outputFile) throws Exception;
    }

    public void generateHtmlReportInteractive(Component parent, JButton triggerButton, List<Finding> reportFindings) {
        exportReportInteractive(parent, triggerButton, reportFindings, "html", "report.html", "HTML Report",
                (findings, cfg, capture, out) -> reportGenerator.generate(findings, cfg, capture, out));
    }

    public void exportPdfReportInteractive(Component parent, JButton triggerButton, List<Finding> reportFindings) {
        exportReportInteractive(parent, triggerButton, reportFindings, "pdf", "report.pdf", "PDF Report",
                (findings, cfg, capture, out) -> pdfReportGenerator.generate(findings, cfg, capture, out));
    }

    public boolean generateReport(String format, Path outputFile) throws Exception {
        List<Finding> reportFindings = new ArrayList<>();
        for (FindingRecord record : findings.getAllFindingRecords()) {
            if (!record.isSuppressed()) reportFindings.add(record.getFinding());
        }
        return switch (format.toLowerCase()) {
            case "html" -> reportGenerator.generate(reportFindings, config, evidenceCapture, outputFile);
            case "pdf" -> pdfReportGenerator.generate(reportFindings, config, evidenceCapture, outputFile);
            default -> throw new IllegalArgumentException("Unknown report format: " + format + " (expected html or pdf)");
        };
    }

    public ReportTemplateConfig getReportTemplateConfig() {
        return ReportTemplateConfig.fromConfig(config);
    }

    public void saveReportTemplateConfig(ReportTemplateConfig rtc) {
        rtc.saveTo(config);
        api.persistence().extensionData().setString("config", config.serialize());
    }

    private void exportReportInteractive(Component parent, JButton triggerButton, List<Finding> reportFindings,
                                          String extension, String defaultFileName, String formatLabel, ReportWriter writer) {
        JFileChooser fc = new JFileChooser(new java.io.File(icarus.core.EvidencePaths.defaultOutputDir(api, config)));
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
}
