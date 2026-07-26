package icarus.evidence;

import burp.api.montoya.MontoyaApi;
import icarus.core.Finding;
import icarus.core.JsonParser;
import icarus.core.ModuleConfig;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class EvidenceCapture {

    // Burp Dark Theme Colors
    private static final Color BG_COLOR = new Color(34, 34, 34);
    private static final Color TEXT_COLOR = new Color(190, 190, 190);
    private static final Color HEADER_BG = new Color(45, 45, 45);
    private static final Color ACCENT_COLOR = new Color(255, 102, 51);
    
    private static final Font MONO_FONT = new Font(Font.MONOSPACED, Font.PLAIN, 14);
    private static final Font BOLD_FONT = new Font(Font.MONOSPACED, Font.BOLD, 14);

    private final MontoyaApi api;
    private final List<CapturedEvidence> captured = new ArrayList<>();

    public EvidenceCapture(MontoyaApi api) {
        this.api = api;
    }

    public List<CapturedEvidence> getCaptured() {
        return List.copyOf(captured);
    }

    public void captureInteractive(Finding finding) {
        SwingUtilities.invokeLater(() -> showTextEditor(finding));
    }

    private void showTextEditor(Finding finding) {
        JDialog editor = new JDialog();
        editor.setTitle("Text-Based Evidence Editor - " + finding.type());
        editor.setModal(false);
        editor.setSize(1200, 800);
        editor.setLocationRelativeTo(null);
        
        var rr = finding.evidence();
        String reqBody = rr.request().bodyToString();
        String reqText = rr.request().headers().stream()
                .map(h -> h.name() + ": " + h.value() + "\n")
                .reduce("", String::concat) + "\n" + JsonParser.formatJsonString(reqBody);
        
        String resText = "";
        if (rr.response() != null) {
            String resBody = rr.response().bodyToString();
            resText = rr.response().headers().stream()
                    .map(h -> h.name() + ": " + h.value() + "\n")
                    .reduce("", String::concat) + "\n" + JsonParser.formatJsonString(resBody);
        }

        JTextArea reqArea = new JTextArea(reqText);
        reqArea.setFont(MONO_FONT);
        reqArea.setBackground(BG_COLOR);
        reqArea.setForeground(TEXT_COLOR);
        reqArea.setCaretColor(Color.WHITE);

        JTextArea resArea = new JTextArea(resText);
        resArea.setFont(MONO_FONT);
        resArea.setBackground(BG_COLOR);
        resArea.setForeground(TEXT_COLOR);
        resArea.setCaretColor(Color.WHITE);

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, new JScrollPane(reqArea), new JScrollPane(resArea));
        split.setResizeWeight(0.5);
        editor.add(split, BorderLayout.CENTER);

        JPanel bottom = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JCheckBox chk1080 = new JCheckBox("Force 1920x1080 Resolution", true);
        JButton btnSave = new JButton("Save as Image");
        
        btnSave.addActionListener(e -> {
            BufferedImage img = generateImage(reqArea.getText(), resArea.getText(), finding, chk1080.isSelected());
            saveImage(img, finding, editor);
        });

        bottom.add(chk1080);
        bottom.add(btnSave);
        editor.add(bottom, BorderLayout.SOUTH);
        editor.setVisible(true);
    }

    private BufferedImage generateImage(String req, String res, Finding finding, boolean force1080) {
        int imgWidth = force1080 ? 1920 : 1200;
        int imgHeight = force1080 ? 1080 : 800;

        BufferedImage img = new BufferedImage(imgWidth, imgHeight, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB);

        // Fill background
        g.setColor(BG_COLOR);
        g.fillRect(0, 0, imgWidth, imgHeight);

        // Header
        g.setColor(HEADER_BG);
        g.fillRect(0, 0, imgWidth, 60);
        g.setColor(ACCENT_COLOR);
        g.setFont(new Font(Font.MONOSPACED, Font.BOLD, 18));
        g.drawString("ICARUS EVIDENCE • " + finding.module() + " • " + finding.type() + " • " + finding.severity(), 20, 35);
        
        g.setColor(new Color(60, 60, 60));
        g.drawLine(imgWidth / 2, 60, imgWidth / 2, imgHeight);

        // Text
        g.setFont(MONO_FONT);
        g.setColor(TEXT_COLOR);
        
        int y = 90;
        g.setFont(BOLD_FONT);
        g.setColor(Color.WHITE);
        g.drawString("REQUEST", 20, y);
        g.drawString("RESPONSE", imgWidth / 2 + 20, y);
        
        y += 30;
        g.setFont(MONO_FONT);
        g.setColor(TEXT_COLOR);

        int reqY = y;
        for (String line : req.split("\n")) {
            g.drawString(line, 20, reqY);
            reqY += 20;
            if (reqY > imgHeight - 20 && !force1080) break; // Don't overflow unless forcing
        }

        int resY = y;
        for (String line : res.split("\n")) {
            g.drawString(line, imgWidth / 2 + 20, resY);
            resY += 20;
            if (resY > imgHeight - 20 && !force1080) break;
        }

        g.dispose();
        return img;
    }

    private void saveImage(BufferedImage img, Finding finding, Window parent) {
        try {
            JFileChooser fc = new JFileChooser(new File(System.getProperty("user.home")));
            fc.setSelectedFile(new File("evidence-" + finding.type() + ".png"));
            if (fc.showSaveDialog(parent) == JFileChooser.APPROVE_OPTION) {
                File f = fc.getSelectedFile();
                ImageIO.write(img, "png", f);
                captured.add(new CapturedEvidence(finding, f.toPath(), img));
                JOptionPane.showMessageDialog(parent, "Saved: " + f.getAbsolutePath());
                parent.dispose();
            }
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(parent, "Save failed: " + ex.getMessage());
        }
    }

    public List<CapturedEvidence> captureAll(List<Finding> findings, ModuleConfig config) {
        // Obsoleted by user request. Auto-capture is disabled.
        return new ArrayList<>();
    }

    public record CapturedEvidence(Finding finding, Path imagePath, BufferedImage image) {}
}
