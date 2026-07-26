package icarus.evidence;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.message.HttpRequestResponse;

import icarus.core.Finding;
import icarus.core.ModuleConfig;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.BiFunction;

public final class EvidenceCapture {

    private static final int PANEL_WIDTH = 800; // Left and Right panel width
    private static final int IMAGE_WIDTH = PANEL_WIDTH * 2 + 60; // Side-by-side + padding
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
    private static final Color HIGHLIGHT_COLOR = new Color(255, 255, 0, 80); // Semi-transparent yellow

    private final MontoyaApi api;
    private final List<CapturedEvidence> captured = new ArrayList<>();

    public EvidenceCapture(MontoyaApi api) {
        this.api = api;
    }

    public List<CapturedEvidence> getCaptured() {
        return List.copyOf(captured);
    }

    public void captureInteractive(Finding finding) {
        SwingUtilities.invokeLater(() -> {
            try {
                RenderResult rr = renderFindingSideBySide(finding);
                showEditor(rr.image, finding, rr.autoHighlights);
            } catch (Exception e) {
                api.logging().logToError("Evidence capture failed: " + e.getMessage());
            }
        });
    }

    private RenderResult renderFindingSideBySide(Finding finding) {
        var rr = finding.evidence();
        String requestText = rr.request().toString();
        String responseText = rr.response() != null ? rr.response().toString() : "(no response)";

        // Split texts into wrapped lines
        List<TextLine> reqLines = new ArrayList<>();
        List<TextLine> resLines = new ArrayList<>();

        addWrappedText(reqLines, requestText, TEXT_COLOR, PANEL_WIDTH);
        addWrappedText(resLines, responseText, TEXT_COLOR, PANEL_WIDTH);

        // Header info
        List<TextLine> headLines = new ArrayList<>();
        headLines.add(new TextLine("╔══════════════════════════════════════════════════════════════╗", ACCENT_COLOR, BOLD_FONT));
        headLines.add(new TextLine("║  ICARUS • " + finding.module() + " • " + finding.type(), ACCENT_COLOR, BOLD_FONT));
        headLines.add(new TextLine("║  Severity: " + finding.severity() + " | Category: " + finding.category(), ACCENT_COLOR, BOLD_FONT));
        headLines.add(new TextLine("║  Path/Target: " + finding.path(), ACCENT_COLOR, BOLD_FONT));
        headLines.add(new TextLine("╚══════════════════════════════════════════════════════════════╝", ACCENT_COLOR, BOLD_FONT));
        headLines.add(new TextLine("► " + finding.description(), FINDING_COLOR, BOLD_FONT));
        if (!finding.metadata().isEmpty()) {
            for (var entry : finding.metadata().entrySet()) {
                headLines.add(new TextLine("  " + entry.getKey() + ": " + entry.getValue(), TEXT_COLOR, MONO_FONT));
            }
        }
        headLines.add(new TextLine("", TEXT_COLOR, MONO_FONT));

        int maxContentLines = Math.max(reqLines.size(), resLines.size());
        int height = PADDING * 2 + (headLines.size() + 2 + maxContentLines) * LINE_HEIGHT;
        height = Math.max(height, 400);
        height = Math.min(height, 4000);

        BufferedImage image = new BufferedImage(IMAGE_WIDTH, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();

        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB);

        g.setColor(BG_COLOR);
        g.fillRect(0, 0, IMAGE_WIDTH, height);

        int y = PADDING + LINE_HEIGHT;
        for (var line : headLines) {
            g.setFont(line.font());
            g.setColor(line.color());
            g.drawString(line.text(), PADDING, y);
            y += LINE_HEIGHT;
        }

        int startY = y;
        
        // Draw Request Panel (Left)
        g.setFont(BOLD_FONT);
        g.setColor(HEADER_COLOR);
        g.drawString("══════════════ REQUEST ══════════════", PADDING, startY);
        
        // Draw Response Panel (Right)
        g.drawString("══════════════ RESPONSE ══════════════", PADDING + PANEL_WIDTH + 20, startY);
        
        y = startY + LINE_HEIGHT * 2;
        int reqY = y;
        int resY = y;
        
        List<Shape> autoHighlights = new ArrayList<>();
        FontMetrics fm = g.getFontMetrics(MONO_FONT);
        
        // Match string to auto-highlight based on path or description
        String highlightTarget = extractHighlightTarget(finding);

        for (var line : reqLines) {
            if (reqY > height - PADDING) break;
            g.setFont(line.font());
            g.setColor(line.color());
            g.drawString(line.text(), PADDING, reqY);
            
            if (highlightTarget != null && line.text().contains(highlightTarget)) {
                int idx = line.text().indexOf(highlightTarget);
                int xOffset = fm.stringWidth(line.text().substring(0, idx));
                int targetWidth = fm.stringWidth(highlightTarget);
                autoHighlights.add(new Rectangle2D.Double(PADDING + xOffset - 2, reqY - LINE_HEIGHT + 4, targetWidth + 4, LINE_HEIGHT + 2));
            }
            
            reqY += LINE_HEIGHT;
        }

        for (var line : resLines) {
            if (resY > height - PADDING) break;
            g.setFont(line.font());
            g.setColor(line.color());
            g.drawString(line.text(), PADDING + PANEL_WIDTH + 20, resY);
            
            if (highlightTarget != null && line.text().contains(highlightTarget)) {
                int idx = line.text().indexOf(highlightTarget);
                int xOffset = fm.stringWidth(line.text().substring(0, idx));
                int targetWidth = fm.stringWidth(highlightTarget);
                autoHighlights.add(new Rectangle2D.Double(PADDING + PANEL_WIDTH + 20 + xOffset - 2, resY - LINE_HEIGHT + 4, targetWidth + 4, LINE_HEIGHT + 2));
            }
            
            resY += LINE_HEIGHT;
        }

        // Separator line
        g.setColor(SEPARATOR_COLOR);
        g.drawLine(PADDING + PANEL_WIDTH + 10, startY, PADDING + PANEL_WIDTH + 10, height - PADDING);

        g.setColor(FINDING_COLOR);
        g.setStroke(new BasicStroke(3));
        g.drawRect(1, 1, IMAGE_WIDTH - 3, height - 3);

        g.dispose();
        return new RenderResult(image, autoHighlights);
    }
    
    private String extractHighlightTarget(Finding finding) {
        if (finding.path() != null && !finding.path().isEmpty() && !finding.path().equals("root") && !finding.path().equals("$")) {
            if (finding.path().startsWith("$.")) return finding.path().substring(2);
            return finding.path();
        }
        if (finding.metadata().containsKey("payload")) {
            return finding.metadata().get("payload");
        }
        return null;
    }

    private void addWrappedText(List<TextLine> lines, String text, Color color, int maxWidth) {
        int maxChars = maxWidth / 7;
        for (String rawLine : text.split("\n")) {
            String cleanLine = rawLine.replace("\r", "");
            if (cleanLine.length() <= maxChars) {
                lines.add(new TextLine(cleanLine, color, MONO_FONT));
            } else {
                for (int i = 0; i < cleanLine.length(); i += maxChars) {
                    int end = Math.min(i + maxChars, cleanLine.length());
                    lines.add(new TextLine(cleanLine.substring(i, end), color, MONO_FONT));
                }
            }
        }
    }

    private void showEditor(BufferedImage snap, Finding finding, List<Shape> autoHighlights) {
        JFrame editor = new JFrame("Evidence Editor - " + finding.type());
        editor.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        editor.getRootPane().setBorder(BorderFactory.createLineBorder(Color.DARK_GRAY, 2));

        JPanel root = new JPanel(new BorderLayout());
        editor.setContentPane(root);

        JLabel img = new JLabel(new ImageIcon(snap));
        JLayeredPane stack = new JLayeredPane();
        img.setBounds(0, 0, snap.getWidth(), snap.getHeight());
        stack.add(img, Integer.valueOf(0));

        JPanel canvas = new JPanel(null);
        canvas.setBackground(Color.DARK_GRAY);
        canvas.setBorder(BorderFactory.createMatteBorder(12, 12, 12, 12, Color.DARK_GRAY));
        canvas.setPreferredSize(new Dimension(snap.getWidth() + 24, snap.getHeight() + 24));
        stack.setBounds(0, 0, snap.getWidth(), snap.getHeight());
        canvas.add(stack);

        canvas.addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                int cw = canvas.getWidth() - 24;
                int ch = canvas.getHeight() - 24;
                int sx = (cw - snap.getWidth()) / 2;
                int sy = (ch - snap.getHeight()) / 2;
                stack.setBounds(12 + Math.max(0, sx), 12 + Math.max(0, sy), snap.getWidth(), snap.getHeight());
            }
        });

        root.add(new JScrollPane(canvas), BorderLayout.CENTER);

        List<Shape> shapes = new ArrayList<>(autoHighlights);
        List<Color> cols = new ArrayList<>();
        List<String> kinds = new ArrayList<>();
        for (int i=0; i<autoHighlights.size(); i++) {
            cols.add(HIGHLIGHT_COLOR);
            kinds.add("HIGHLIGHT");
        }
        
        Color[] curCol = { Color.RED };
        String[] mode = { "HIGHLIGHT" };
        Point[] start = { null };
        Shape[] preview = { null };

        JComponent draw = new JComponent() {
            { setOpaque(false); }
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setStroke(new BasicStroke(3f));
                for (int i = 0; i < shapes.size(); i++) {
                    g2.setColor(cols.get(i));
                    if ("HIGHLIGHT".equals(kinds.get(i)) || "REDACT".equals(kinds.get(i))) g2.fill(shapes.get(i));
                    else g2.draw(shapes.get(i));
                }
                if (preview[0] != null) {
                    g2.setColor("REDACT".equals(mode[0]) ? Color.BLACK : 
                               ("HIGHLIGHT".equals(mode[0]) ? HIGHLIGHT_COLOR : curCol[0]));
                    if ("HIGHLIGHT".equals(mode[0]) || "REDACT".equals(mode[0])) g2.fill(preview[0]);
                    else g2.draw(preview[0]);
                }
                g2.dispose();
            }
        };
        draw.setBounds(0, 0, snap.getWidth(), snap.getHeight());
        stack.add(draw, Integer.valueOf(100));

        MouseAdapter dm = new MouseAdapter() {
            Path2D path;
            @Override
            public void mousePressed(MouseEvent e) {
                start[0] = e.getPoint();
                if ("DRAW".equals(mode[0])) {
                    path = new Path2D.Double();
                    path.moveTo(e.getX(), e.getY());
                    preview[0] = path;
                }
            }
            @Override
            public void mouseDragged(MouseEvent e) {
                if (start[0] == null) return;
                Point p = e.getPoint();
                switch (mode[0]) {
                    case "LINE" -> preview[0] = new Line2D.Double(start[0], p);
                    case "DRAW" -> path.lineTo(p.getX(), p.getY());
                    default -> {
                        int x = Math.min(start[0].x, p.x);
                        int y = Math.min(start[0].y, p.y);
                        int w = Math.abs(p.x - start[0].x);
                        int h = Math.abs(p.y - start[0].y);
                        preview[0] = new Rectangle2D.Double(x, y, w, h);
                    }
                }
                draw.repaint();
            }
            @Override
            public void mouseReleased(MouseEvent e) {
                if (preview[0] != null) {
                    Color c = switch (mode[0]) {
                        case "HIGHLIGHT" -> HIGHLIGHT_COLOR;
                        case "REDACT" -> Color.BLACK;
                        default -> curCol[0];
                    };
                    shapes.add(preview[0]);
                    cols.add(c);
                    kinds.add(mode[0]);
                }
                start[0] = null;
                preview[0] = null;
                draw.repaint();
            }
        };
        draw.addMouseListener(dm);
        draw.addMouseMotionListener(dm);

        JPanel bar = new JPanel(new GridLayout(0, 1, 0, 8));
        bar.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        root.add(bar, BorderLayout.EAST);

        JButton colourBtn = new JButton("Colour"); colourBtn.setOpaque(true);
        JToggleButton redactBtn = new JToggleButton("Redact");
        JToggleButton lineBtn = new JToggleButton("Line");
        JToggleButton drawBtn = new JToggleButton("Draw");
        JToggleButton rectBtn = new JToggleButton("Box");
        JToggleButton hiBtn = new JToggleButton("Highlight", true);
        JButton undoBtn = new JButton("Undo");
        JButton saveBtn = new JButton("Save Evidence");

        ButtonGroup grp = new ButtonGroup();
        grp.add(rectBtn); grp.add(lineBtn); grp.add(drawBtn);
        grp.add(hiBtn); grp.add(redactBtn);

        Arrays.asList(colourBtn, redactBtn, lineBtn, drawBtn, rectBtn, hiBtn, undoBtn, saveBtn)
                .forEach(b -> b.setFont(b.getFont().deriveFont(Font.BOLD, 14f)));

        ActionListener modeSel = a -> {
            if (a.getSource() == rectBtn) mode[0] = "RECT";
            else if (a.getSource() == lineBtn) mode[0] = "LINE";
            else if (a.getSource() == drawBtn) mode[0] = "DRAW";
            else if (a.getSource() == hiBtn) mode[0] = "HIGHLIGHT";
            else if (a.getSource() == redactBtn) mode[0] = "REDACT";
            draw.setCursor(Cursor.getPredefinedCursor("LINE".equals(mode[0]) ? Cursor.DEFAULT_CURSOR : Cursor.CROSSHAIR_CURSOR));
        };
        rectBtn.addActionListener(modeSel); lineBtn.addActionListener(modeSel);
        drawBtn.addActionListener(modeSel); hiBtn.addActionListener(modeSel); redactBtn.addActionListener(modeSel);

        colourBtn.addActionListener(a -> {
            Color chosen = JColorChooser.showDialog(editor, "Choose colour", curCol[0]);
            if (chosen != null) { curCol[0] = chosen; colourBtn.setBackground(curCol[0]); }
        });
        colourBtn.setBackground(curCol[0]);

        undoBtn.addActionListener(a -> {
            if (!shapes.isEmpty()) {
                shapes.remove(shapes.size() - 1);
                cols.remove(cols.size() - 1);
                kinds.remove(kinds.size() - 1);
                draw.repaint();
            }
        });

        saveBtn.addActionListener(a -> {
            try {
                BufferedImage out = new BufferedImage(snap.getWidth(), snap.getHeight(), BufferedImage.TYPE_INT_ARGB);
                Graphics2D g2 = out.createGraphics();
                g2.drawImage(snap, 0, 0, null);
                g2.setStroke(new BasicStroke(3f));
                for (int i = 0; i < shapes.size(); i++) {
                    g2.setColor(cols.get(i));
                    if ("HIGHLIGHT".equals(kinds.get(i)) || "REDACT".equals(kinds.get(i))) g2.fill(shapes.get(i));
                    else g2.draw(shapes.get(i));
                }
                g2.dispose();

                JFileChooser fc = new JFileChooser(new File(System.getProperty("user.home")));
                fc.setSelectedFile(new File("evidence-" + finding.type() + ".png"));
                if (fc.showSaveDialog(editor) == JFileChooser.APPROVE_OPTION) {
                    File f = fc.getSelectedFile();
                    ImageIO.write(out, "png", f);
                    captured.add(new CapturedEvidence(finding, f.toPath(), out));
                    JOptionPane.showMessageDialog(editor, "Saved: " + f.getAbsolutePath());
                }
                editor.dispose();
            } catch (Exception ex) {
                ex.printStackTrace();
            }
        });

        bar.add(colourBtn);
        bar.add(hiBtn);
        bar.add(redactBtn);
        bar.add(rectBtn);
        bar.add(lineBtn);
        bar.add(drawBtn);
        bar.add(undoBtn);
        bar.add(new JSeparator());
        bar.add(saveBtn);

        editor.pack();
        int winW = Math.min(1200, snap.getWidth() + 200);
        int winH = Math.min(800, snap.getHeight() + 100);
        editor.setSize(winW, winH);
        editor.setLocationRelativeTo(null);
        draw.setCursor(Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR));
        editor.setVisible(true);
    }

    private record TextLine(String text, Color color, Font font) {}
    private record RenderResult(BufferedImage image, List<Shape> autoHighlights) {}
    public record CapturedEvidence(Finding finding, Path imagePath, BufferedImage image) {}
}
