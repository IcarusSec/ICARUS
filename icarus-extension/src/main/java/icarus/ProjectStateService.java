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
import java.util.function.Consumer;
import java.nio.file.Files;
import java.nio.file.Path;

public class ProjectStateService {
    private final MontoyaApi api;
    private final icarus.core.ModuleConfig config;
    private final icarus.evidence.EvidenceCapture evidenceCapture;
    private final icarus.core.FindingRegistry findings;
    private final icarus.report.ReportProfileManager profileManager;

    public ProjectStateService(MontoyaApi api, icarus.core.ModuleConfig config, icarus.evidence.EvidenceCapture evidenceCapture,
                               icarus.core.FindingRegistry findings, icarus.report.ReportProfileManager profileManager) {
        this.api = api;
        this.config = config;
        this.evidenceCapture = evidenceCapture;
        this.findings = findings;
        this.profileManager = profileManager;
    }

    /** Everything beyond the Evidence Manager that a fresh install needs to continue the project. */
    private ProjectStateCodec.Workspace currentWorkspace() {
        return new ProjectStateCodec.Workspace(
                findings.serializeState(),
                config.getStringList("suppressed_hashes"),
                config.getString(icarus.report.DefaultReportProfileManager.KEY_USER_PROFILES, null),
                config.getString(icarus.report.DefaultReportProfileManager.KEY_ACTIVE_PROFILE_ID, null),
                VulnerabilityKnowledgeBase.getInstance().getCustomEntries());
    }

    public void exportProjectStateInteractive(Component parent, JButton triggerButton) {
        List<EvidenceCapture.CapturedEvidence> evidence = evidenceCapture.getCaptured();

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
        ProjectStateCodec.Workspace workspace = currentWorkspace();

        if (triggerButton != null) triggerButton.setEnabled(false);
        new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() throws Exception {
                String json = ProjectStateCodec.export(evidence, evidenceCapture::isIncluded, rtc, workspace);
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
                "Importing replaces this project's findings, evidence, audit log, suppression rules and report settings.\n"
                        + "Knowledge Base edits in the file are added. Continue?",
                "Confirm Import", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (confirm != JOptionPane.YES_OPTION) {
            return;
        }

        if (triggerButton != null) triggerButton.setEnabled(false);
        new SwingWorker<StagedImport, Void>() {
            @Override
            protected StagedImport doInBackground() throws Exception {
                String json = Files.readString(selectedFile.toPath());
                ProjectStateCodec.ImportResult result = ProjectStateCodec.importFrom(json);

                // Same dedicated screenshot folder every other capture path uses — the bare
                // output dir can point at wherever the user last saved a report (e.g. Desktop).
                Path dir = EvidencePaths.evidenceImageDir(api, config);

                // Stage + validate every image off the EDT and into a local list BEFORE we
                // touch the live Evidence Manager. A bad/undecodable image is skipped and
                // logged instead of aborting the whole import over one entry.
                List<Map.Entry<EvidenceCapture.CapturedEvidence, Boolean>> staged = new ArrayList<>();
                int skipped = 0;
                for (var item : result.items()) {
                    Path imagePath = null;
                    try {
                        imagePath = EvidencePaths.reserveEvidenceFile(dir, "evidence", item.finding().type());
                        Files.write(imagePath, item.imageBytes());
                        BufferedImage image = ImageIO.read(imagePath.toFile());
                        if (image == null) {
                            throw new IOException("undecodable image");
                        }
                        var ce = new EvidenceCapture.CapturedEvidence(item.finding(), imagePath, image, item.caption());
                        staged.add(Map.entry(ce, item.included()));
                    } catch (Exception e) {
                        skipped++;
                        api.logging().logToError("Project import: skipped evidence for " + item.finding().type() + ": " + e);
                        if (imagePath != null) {
                            try { Files.deleteIfExists(imagePath); } catch (IOException ignored) { }
                        }
                    }
                }
                if (staged.isEmpty() && skipped > 0) {
                    throw new IOException("none of the " + skipped + " evidence image(s) could be read");
                }

                // Logos/CSS the report settings point at: write them next to the evidence and
                // record where, so the settings can be pointed at this machine's copies.
                Map<String, String> assetPaths = new LinkedHashMap<>();
                if (!result.assets().isEmpty()) {
                    Path assetDir = dir.resolveSibling("assets");
                    Files.createDirectories(assetDir);
                    for (var asset : result.assets().entrySet()) {
                        try {
                            Path target = assetDir.resolve(asset.getKey()).normalize();
                            if (!target.startsWith(assetDir.normalize())) continue;
                            // Name is the content hash, so an existing file already holds these bytes.
                            if (!Files.exists(target)) Files.write(target, asset.getValue());
                            assetPaths.put(asset.getKey(), target.toAbsolutePath().toString());
                        } catch (Exception e) {
                            api.logging().logToError("Project import: could not write asset " + asset.getKey() + ": " + e);
                        }
                    }
                }
                return new StagedImport(staged, result, skipped, assetPaths);
            }

            @Override
            protected void done() {
                if (triggerButton != null) triggerButton.setEnabled(true);
                Frame suiteFrame = api.userInterface().swingUtils().suiteFrame();
                try {
                    StagedImport staged = get();

                    ProjectStateCodec.Workspace ws = staged.result().workspace();
                    Map<String, String> assetPaths = staged.assetPaths();

                    // Suppression rules first: clearAllFindings re-seeds suppressed hashes from config.
                    if (ws.suppressedHashes() != null) config.set("suppressed_hashes", String.join("\n", ws.suppressedHashes()));
                    if (ws.registryJson() != null) {
                        findings.clearAllFindings();
                        findings.deserializeState(ws.registryJson());
                    }

                    evidenceCapture.clearAll();
                    List<Finding> imported = new ArrayList<>();
                    for (var entry : staged.entries()) {
                        var ce = entry.getKey();
                        evidenceCapture.restoreCaptured(ce, entry.getValue());
                        // Already restored with its count/suppression from the registry: don't count it twice.
                        if (findings.getRecordByHash(ce.finding().similarityHash()) == null) imported.add(ce.finding());
                    }
                    // One registry batch (one UI refresh). passive=true only skips creating Burp
                    // issues: these were raised when first found, and re-importing the same
                    // project re-added every one of them to the site map each time.
                    if (!imported.isEmpty()) findings.processDeduplication(imported, true);

                    ReportTemplateConfig rtc = staged.result().reportTemplateConfig();
                    rtc.setLogoPath(ProjectStateCodec.resolveAsset(rtc.logoPath(), assetPaths));
                    rtc.setClientLogoPath(ProjectStateCodec.resolveAsset(rtc.clientLogoPath(), assetPaths));
                    rtc.setCustomCssPath(ProjectStateCodec.resolveAsset(rtc.customCssPath(), assetPaths));
                    rtc.saveTo(config);
                    if (ws.reportProfilesJson() != null) {
                        config.set(icarus.report.DefaultReportProfileManager.KEY_USER_PROFILES,
                                ProjectStateCodec.resolveAssets(ws.reportProfilesJson(), assetPaths));
                    }
                    if (ws.activeProfileId() != null) {
                        config.set(icarus.report.DefaultReportProfileManager.KEY_ACTIVE_PROFILE_ID, ws.activeProfileId());
                    }
                    if (ws.knowledgeBase() != null && !ws.knowledgeBase().isEmpty()) {
                        VulnerabilityKnowledgeBase.getInstance().importEntries(ws.knowledgeBase());
                    }
                    api.persistence().extensionData().setString("config", config.serialize());
                    if (ws.reportProfilesJson() != null || ws.activeProfileId() != null) profileManager.reload();

                    onImported.run();
                    ToastNotification.show(suiteFrame, "Project imported: " + findings.getAllFindingRecords().stream()
                            .filter(r -> !"DUMMY".equals(r.getFinding().type())).count() + " finding(s), "
                            + staged.entries().size() + " evidence item(s)."
                            + (staged.skipped() > 0 ? " " + staged.skipped() + " unreadable item(s) skipped — see the extension error log." : ""));
                } catch (Exception ex) {
                    api.logging().logToError("Project import failed: " + ex.getCause());
                    JOptionPane.showMessageDialog(parent, "Project import failed: " + ex.getCause());
                }
            }
        }.execute();
    }

    private record StagedImport(
            List<Map.Entry<EvidenceCapture.CapturedEvidence, Boolean>> entries,
            ProjectStateCodec.ImportResult result,
            int skipped,
            Map<String, String> assetPaths) {}

}
