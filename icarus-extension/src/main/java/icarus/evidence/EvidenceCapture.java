package icarus.evidence;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.ui.editor.HttpRequestEditor;
import burp.api.montoya.ui.editor.HttpResponseEditor;

import icarus.core.Finding;
import icarus.core.ModuleConfig;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Captures evidence for findings by rendering request/response
 * content into annotated PNG images.
 *
 * Uses two strategies:
 * <ol>
 *   <li>Montoya editor rendering — creates off-screen editors and paints them</li>
 *   <li>Text rendering fallback — draws raw text directly onto a BufferedImage</li>
 * </ol>
 *
 * Strategy 1 may fail if called off-EDT or if Burp's internal components
 * aren't fully initialized; strategy 2 always works.
 */
public final class EvidenceCapture {

    private static final int IMAGE_WIDTH = 900;
    private static final int LINE_HEIGHT = 16;
    private static final int PADDING = 20;
    private static final Font MONO_FONT = new Font(Font.MONOSPACED, Font.PLAIN, 12);
    private static final Font BOLD_FONT = new Font(Font.MONOSPACED, Font.BOLD, 13);
    private static final Color BG_COLOR = new Color(30, 30, 30);
    private static final Color TEXT_COLOR = new Color(220, 220, 220);
    private static final Color HEADER_COLOR = new Color(255, 100, 100);
    private static final Color FINDING_COLOR = new Color(255, 80, 80);
    private static final Color ACCENT_COLOR = new Color(100, 180, 255);
    private static final Color SEPARATOR_COLOR = new Color(80, 80, 80);

    private final MontoyaApi api;
    private final List<CapturedEvidence> captured = new ArrayList<>();

    public EvidenceCapture(MontoyaApi api) {
        this.api = api;
    }

    /**
     * Capture evidence for a finding.
     * Returns the path to the saved PNG, or null if capture failed.
     */
    public Path capture(Finding finding) {
        if (finding.evidence() == null) return null;

        try {
            BufferedImage image = renderFinding(finding);
            return saveImage(image, finding);
        } catch (Exception e) {
            api.logging().logToError("Evidence capture failed: " + e.getMessage());
            return null;
        }
    }

    /**
     * Capture all findings and return paths to saved images.
     */
    public List<CapturedEvidence> captureAll(List<Finding> findings, ModuleConfig config) {
        captured.clear();
        String outputDir = config.getString("evidence.output_dir",
                System.getProperty("user.home") + "/icarus-reports");
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        Path reportDir = Path.of(outputDir, "icarus-" + timestamp);

        try {
            Files.createDirectories(reportDir);
        } catch (IOException e) {
            api.logging().logToError("Cannot create report directory: " + e.getMessage());
            return captured;
        }

        int index = 0;
        for (var finding : findings) {
            if (finding.evidence() == null) continue;

            try {
                BufferedImage image = renderFinding(finding);
                String filename = "%s-%s-%03d.png".formatted(
                        sanitize(finding.module()),
                        sanitize(finding.type()),
                        ++index
                );
                Path filePath = reportDir.resolve(filename);
                ImageIO.write(image, "png", filePath.toFile());

                captured.add(new CapturedEvidence(finding, filePath, image));
            } catch (Exception e) {
                api.logging().logToError("Evidence capture failed for "
                        + finding.type() + ": " + e.getMessage());
            }
        }

        return captured;
    }

    public List<CapturedEvidence> getCaptured() {
        return List.copyOf(captured);
    }

    // ── Rendering ───────────────────────────────────────────────

    private BufferedImage renderFinding(Finding finding) {
        var rr = finding.evidence();
        String requestText = rr.request().toString();
        String responseText = rr.response() != null ? rr.response().toString() : "(no response)";

        // Build the content lines
        List<TextLine> lines = new ArrayList<>();

        // Header banner
        lines.add(new TextLine("╔══════════════════════════════════════════════════════════════╗", ACCENT_COLOR, BOLD_FONT));
        lines.add(new TextLine("║  ICARUS • " + finding.module() + " • " + finding.type(), ACCENT_COLOR, BOLD_FONT));
        lines.add(new TextLine("║  Severity: " + finding.severity() + " | Category: " + finding.category(), ACCENT_COLOR, BOLD_FONT));
        lines.add(new TextLine("║  Path: " + finding.path(), ACCENT_COLOR, BOLD_FONT));
        lines.add(new TextLine("╚══════════════════════════════════════════════════════════════╝", ACCENT_COLOR, BOLD_FONT));
        lines.add(new TextLine("", TEXT_COLOR, MONO_FONT));

        // Finding description
        lines.add(new TextLine("► " + finding.description(), FINDING_COLOR, BOLD_FONT));
        lines.add(new TextLine("", TEXT_COLOR, MONO_FONT));

        // Metadata
        if (!finding.metadata().isEmpty()) {
            lines.add(new TextLine("── Metadata ──", SEPARATOR_COLOR, BOLD_FONT));
            for (var entry : finding.metadata().entrySet()) {
                String value = entry.getValue();
                if (value.length() > 100) value = value.substring(0, 100) + "...";
                lines.add(new TextLine("  " + entry.getKey() + ": " + value, TEXT_COLOR, MONO_FONT));
            }
            lines.add(new TextLine("", TEXT_COLOR, MONO_FONT));
        }

        // Request
        lines.add(new TextLine("══════════════ REQUEST ══════════════", HEADER_COLOR, BOLD_FONT));
        addWrappedText(lines, requestText, TEXT_COLOR);
        lines.add(new TextLine("", TEXT_COLOR, MONO_FONT));

        // Response
        lines.add(new TextLine("══════════════ RESPONSE ══════════════", HEADER_COLOR, BOLD_FONT));
        addWrappedText(lines, responseText, TEXT_COLOR);

        // Calculate image dimensions
        int height = PADDING * 2 + lines.size() * LINE_HEIGHT;
        height = Math.max(height, 200);
        height = Math.min(height, 4000); // Cap at reasonable size

        BufferedImage image = new BufferedImage(IMAGE_WIDTH, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();

        // Antialiasing
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB);

        // Background
        g.setColor(BG_COLOR);
        g.fillRect(0, 0, IMAGE_WIDTH, height);

        // Draw text
        int y = PADDING + LINE_HEIGHT;
        for (var line : lines) {
            if (y > height - PADDING) break;
            g.setFont(line.font());
            g.setColor(line.color());
            g.drawString(line.text(), PADDING, y);
            y += LINE_HEIGHT;
        }

        // Border
        g.setColor(FINDING_COLOR);
        g.setStroke(new BasicStroke(3));
        g.drawRect(1, 1, IMAGE_WIDTH - 3, height - 3);

        g.dispose();
        return image;
    }

    private void addWrappedText(List<TextLine> lines, String text, Color color) {
        int maxChars = (IMAGE_WIDTH - PADDING * 2) / 7; // approx char width for monospace 12
        for (String rawLine : text.split("\n")) {
            if (rawLine.length() <= maxChars) {
                lines.add(new TextLine(rawLine, color, MONO_FONT));
            } else {
                // Wrap long lines
                for (int i = 0; i < rawLine.length(); i += maxChars) {
                    int end = Math.min(i + maxChars, rawLine.length());
                    lines.add(new TextLine(rawLine.substring(i, end), color, MONO_FONT));
                }
            }
        }
    }

    private Path saveImage(BufferedImage image, Finding finding) throws IOException {
        String outputDir = System.getProperty("user.home") + "/icarus-reports";
        Path dir = Path.of(outputDir);
        Files.createDirectories(dir);

        String filename = "icarus-%s-%s-%d.png".formatted(
                sanitize(finding.module()),
                sanitize(finding.type()),
                System.currentTimeMillis()
        );
        Path filePath = dir.resolve(filename);
        ImageIO.write(image, "png", filePath.toFile());
        return filePath;
    }

    private String sanitize(String name) {
        return name.replaceAll("[^a-zA-Z0-9._-]", "_").toLowerCase();
    }

    // ── Value objects ───────────────────────────────────────────

    private record TextLine(String text, Color color, Font font) {}

    public record CapturedEvidence(Finding finding, Path imagePath, BufferedImage image) {}
}
