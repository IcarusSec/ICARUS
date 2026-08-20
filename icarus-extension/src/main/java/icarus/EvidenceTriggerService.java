package icarus;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.requests.HttpRequest;
import icarus.autoauth.AutoAuthModule;
import icarus.core.Category;
import icarus.core.Finding;
import icarus.core.IcarusModule;
import icarus.core.ModuleConfig;
import icarus.core.Severity;
import icarus.evidence.EvidenceCapture;
import icarus.core.EvidencePaths;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

public class EvidenceTriggerService {
    private final MontoyaApi api;
    private final ModuleConfig config;
    private final EvidenceCapture evidenceCapture;
    private final List<IcarusModule> modules;
    private final AutoAuthModule autoAuth;
    private final Orchestrator owner;

    public EvidenceTriggerService(MontoyaApi api, ModuleConfig config, EvidenceCapture evidenceCapture,
                                  List<IcarusModule> modules, AutoAuthModule autoAuth, Orchestrator owner) {
        this.api = api;
        this.config = config;
        this.evidenceCapture = evidenceCapture;
        this.modules = modules;
        this.autoAuth = autoAuth;
        this.owner = owner;
    }

    public void captureEvidence(Finding finding, BufferedImage image, String caption) throws IOException {
        Path dir = EvidencePaths.evidenceImageDir(api, config);
        Files.createDirectories(dir);
        String filename = "evidence-" + finding.type().replaceAll("[^a-zA-Z0-9.-]", "_")
                + "-" + System.currentTimeMillis() + "-" + UUID.randomUUID() + ".png";
        Path imagePath = dir.resolve(filename);
        ImageIO.write(image, "png", imagePath.toFile());
        var ce = new EvidenceCapture.CapturedEvidence(finding, imagePath, image, caption);
        evidenceCapture.restoreCaptured(ce, true);
        owner.updateFinding(finding);
    }

    public void createManualEvidence(HttpRequestResponse rr) {
        HttpRequest injectedRequest = autoAuth.injectIfApplicable(rr.request());
        if (injectedRequest != rr.request()) {
            rr = HttpRequestResponse.httpRequestResponse(injectedRequest, rr.response());
        }
        Finding smart = detectSmartEvidence(rr);
        evidenceCapture.captureInteractive(smart != null ? smart : blankManualFinding(rr));
    }

    private Finding detectSmartEvidence(HttpRequestResponse rr) {
        if (rr.response() == null) return null;

        IcarusModule pem = modules.stream().filter(m -> m.getClass().getSimpleName().equals("PassiveErrorModule")).findFirst().orElse(null);
        List<Finding> errorFindings = pem != null ? pem.run(rr, config, msg -> {}) : List.of();
        if (!errorFindings.isEmpty()) {
            Finding candidate = errorFindings.get(0);
            return confirmSmartEvidence(candidate.type(), candidate.description()) ? candidate : null;
        }

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
            BufferedImage image = toBufferedImage(raw);
            evidenceCapture.captureInteractiveWithImage(blankManualFinding(rr), image);
        } catch (Exception e) {
            api.logging().logToError("Failed to read image from clipboard: " + e);
            JOptionPane.showMessageDialog(suiteFrame, "Could not read image from clipboard: " + e.getMessage());
        }
    }

    private BufferedImage toBufferedImage(Image img) {
        if (img instanceof BufferedImage bi) return bi;
        var bi = new BufferedImage(img.getWidth(null), img.getHeight(null), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2 = bi.createGraphics();
        g2.drawImage(img, 0, 0, null);
        g2.dispose();
        return bi;
    }

    public void showEvidenceInteractive(Finding finding) {
        evidenceCapture.captureInteractive(finding);
    }
    
    private Severity parseSeverity(String value) {
        try {
            return Severity.valueOf(value);
        } catch (Exception e) {
            return Severity.INFO;
        }
    }
}
