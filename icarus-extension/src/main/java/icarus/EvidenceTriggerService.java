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

public class EvidenceTriggerService {
    private final MontoyaApi api;
    private final icarus.core.ModuleConfig config;
    private final icarus.evidence.EvidenceCapture evidenceCapture;
    private final java.util.List<icarus.core.IcarusModule> modules;
    private final icarus.autoauth.AutoAuthModule autoAuth;
    private final Orchestrator orchestrator;

    public EvidenceTriggerService(MontoyaApi api, icarus.core.ModuleConfig config, icarus.evidence.EvidenceCapture evidenceCapture, java.util.List<icarus.core.IcarusModule> modules, icarus.autoauth.AutoAuthModule autoAuth, Orchestrator orchestrator) {
        this.api = api;
        this.config = config;
        this.evidenceCapture = evidenceCapture;
        this.modules = modules;
        this.autoAuth = autoAuth;
        this.orchestrator = orchestrator;
    }

    public void captureEvidence(Finding finding, BufferedImage image, String caption) throws IOException {
        Path dir = icarus.core.EvidencePaths.evidenceImageDir(api, config);
        java.nio.file.Files.createDirectories(dir);
        String filename = "evidence-" + finding.type().replaceAll("[^a-zA-Z0-9.-]", "_")
                + "-" + System.currentTimeMillis() + "-" + UUID.randomUUID() + ".png";
        Path imagePath = dir.resolve(filename);
        ImageIO.write(image, "png", imagePath.toFile());
        var ce = new icarus.evidence.EvidenceCapture.CapturedEvidence(finding, imagePath, image, caption);
        evidenceCapture.restoreCaptured(ce, true);
        orchestrator.updateFinding(finding);
    }

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
        Severity manualSeverity = FindingsReviewDialog.parseSeverity(config.getString("evidence.manual_severity", "INFO"));
        return Finding.builder("Manual", "MANUAL_EVIDENCE")
                .description("Manual evidence capture triggered by user.")
                .severity(manualSeverity)
                .category(Category.MANUAL)
                .path(rr.request().path())
                .evidence(rr)
                .build();
    }

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

    public void showEvidenceInteractive(Finding finding) {
        evidenceCapture.captureInteractive(finding);
    }

    private java.awt.image.BufferedImage toBufferedImage(Image img) {
        if (img instanceof java.awt.image.BufferedImage bi) return bi;
        var bi = new java.awt.image.BufferedImage(img.getWidth(null), img.getHeight(null), java.awt.image.BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2 = bi.createGraphics();
        g2.drawImage(img, 0, 0, null);
        g2.dispose();
        return bi;
    }

}
