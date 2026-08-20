package icarus;

import burp.api.montoya.MontoyaApi;
import icarus.core.ModuleConfig;
import icarus.core.ReportTemplateConfig;
import icarus.core.FindingRegistry;
import icarus.evidence.EvidenceCapture;
import icarus.evidence.ProjectStateCodec;
import icarus.core.EvidencePaths;
import icarus.ui.ToastNotification;

import javax.swing.*;
import java.awt.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class ProjectStateService {
    private final MontoyaApi api;
    private final ModuleConfig config;
    private final EvidenceCapture evidenceCapture;
    private final FindingRegistry findings;

    public ProjectStateService(MontoyaApi api, ModuleConfig config, EvidenceCapture evidenceCapture, FindingRegistry findings) {
        this.api = api;
        this.config = config;
        this.evidenceCapture = evidenceCapture;
        this.findings = findings;
    }

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
}
