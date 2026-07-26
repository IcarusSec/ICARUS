package icarus.evidence;

import burp.api.montoya.MontoyaApi;
import icarus.core.Finding;
import icarus.core.JsonParser;

import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.text.BadLocationException;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class EvidenceCapture {

    // Burp Dark Theme Colors
    private static final Color BG_COLOR = new Color(34, 34, 34);
    private static final Color TEXT_COLOR = new Color(190, 190, 190);
    private static final Color HEADER_BG = new Color(45, 45, 45);
    private static final Color ACCENT_COLOR = new Color(255, 102, 51);
    private static final Color SEPARATOR_COLOR = new Color(80, 80, 80);

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
        SwingUtilities.invokeLater(() -> showPhase1(finding));
    }

    // ===================================================================================
    // PHASE 1: TEXT CLEANUP
    // ===================================================================================

    private void showPhase1(Finding finding) {
        JDialog editor = new JDialog();
        editor.setTitle("ICARUS Evidence Editor - Phase 1: Text Cleanup");
        editor.setModal(false);
        editor.setSize(1200, 800);
        editor.setLocationRelativeTo(null);
        editor.setLayout(new BorderLayout());

        // Top Metadata Bar
        JPanel pnlTop = new JPanel(new FlowLayout(FlowLayout.LEFT, 15, 10));
        pnlTop.setBorder(new EmptyBorder(10, 10, 10, 10));
        
        JTextField txtName = new JTextField(finding.type(), 20);
        JTextField txtDesc = new JTextField(finding.description() != null ? finding.description() : "", 40);
        
        pnlTop.add(new JLabel("Evidence Title:"));
        pnlTop.add(txtName);
        pnlTop.add(new JLabel("Description:"));
        pnlTop.add(txtDesc);

        // Text Areas
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

        JTextArea reqArea = createStyledTextArea(reqText);
        JTextArea resArea = createStyledTextArea(resText);

        attachSmartContextMenu(reqArea);
        attachSmartContextMenu(resArea);

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, new JScrollPane(reqArea), new JScrollPane(resArea));
        split.setResizeWeight(0.5);

        // Bottom Bar
        JPanel pnlBottom = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton btnCleanNoise = new JButton("Clean Standard Noise");
        JCheckBox chk1080 = new JCheckBox("Force 1920x1080", true);
        JButton btnProceed = new JButton("Proceed to Annotation ➔");
        btnProceed.setFont(btnProceed.getFont().deriveFont(Font.BOLD));

        btnCleanNoise.addActionListener(e -> {
            reqArea.setText(cleanNoise(reqArea.getText()));
            resArea.setText(cleanNoise(resArea.getText()));
        });

        btnProceed.addActionListener(e -> {
            String finalTitle = txtName.getText();
            String finalDesc = txtDesc.getText();
            BufferedImage renderedText = renderTextToImage(reqArea.getText(), resArea.getText(), finalTitle, finalDesc, finding.severity().name(), chk1080.isSelected());
            showPhase2(editor, finding, renderedText, finalTitle);
        });

        pnlBottom.add(btnCleanNoise);
        pnlBottom.add(new JSeparator(SwingConstants.VERTICAL));
        pnlBottom.add(chk1080);
        pnlBottom.add(btnProceed);

        editor.add(pnlTop, BorderLayout.NORTH);
        editor.add(split, BorderLayout.CENTER);
        editor.add(pnlBottom, BorderLayout.SOUTH);
        editor.setVisible(true);
    }

    private JTextArea createStyledTextArea(String text) {
        JTextArea ta = new JTextArea(text);
        ta.setFont(MONO_FONT);
        ta.setBackground(BG_COLOR);
        ta.setForeground(TEXT_COLOR);
        ta.setCaretColor(Color.WHITE);
        return ta;
    }

    private void attachSmartContextMenu(JTextArea ta) {
        JPopupMenu menu = new JPopupMenu();

        JMenuItem itmTruncate = new JMenuItem("Truncate Selection");
        itmTruncate.addActionListener(e -> replaceSelection(ta, "... [Truncated for Evidence] ..."));

        JMenuItem itmRedact = new JMenuItem("Redact Selection");
        itmRedact.addActionListener(e -> replaceSelection(ta, "[REDACTED]"));

        JMenuItem itmRemoveLine = new JMenuItem("Remove Current Line");
        itmRemoveLine.addActionListener(e -> removeCurrentLine(ta));

        menu.add(itmTruncate);
        menu.add(itmRedact);
        menu.addSeparator();
        menu.add(itmRemoveLine);

        ta.setComponentPopupMenu(menu);
    }

    private void replaceSelection(JTextArea ta, String replacement) {
        if (ta.getSelectedText() != null) {
            ta.replaceSelection(replacement);
        }
    }

    private void removeCurrentLine(JTextArea ta) {
        try {
            int caret = ta.getCaretPosition();
            int line = ta.getLineOfOffset(caret);
            int start = ta.getLineStartOffset(line);
            int end = ta.getLineEndOffset(line);
            ta.getDocument().remove(start, end - start);
        } catch (BadLocationException ex) {
            // ignore
        }
    }

    private String cleanNoise(String text) {
        String[] noisyHeaders = {
            "Accept-Language:", "Accept-Encoding:", "Connection:", "Upgrade-Insecure-Requests:",
            "Sec-Fetch-", "Sec-Ch-Ua"
        };
        StringBuilder sb = new StringBuilder();
        for (String line : text.split("\n")) {
            boolean isNoise = false;
            for (String noise : noisyHeaders) {
                if (line.toLowerCase().startsWith(noise.toLowerCase())) {
                    isNoise = true;
                    break;
                }
            }
            if (!isNoise) {
                sb.append(line).append("\n");
            }
        }
        return sb.toString();
    }

    // ===================================================================================
    // IMAGE RENDERING
    // ===================================================================================

    private BufferedImage renderTextToImage(String req, String res, String title, String desc, String severity, boolean force1080) {
        int imgWidth = force1080 ? 1920 : 1200;
        int imgHeight = force1080 ? 1080 : 800;

        BufferedImage img = new BufferedImage(imgWidth, imgHeight, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB);

        // Fill background
        g.setColor(BG_COLOR);
        g.fillRect(0, 0, imgWidth, imgHeight);

        // Header Banner
        g.setColor(HEADER_BG);
        g.fillRect(0, 0, imgWidth, 80);
        
        g.setColor(ACCENT_COLOR);
        g.setFont(new Font(Font.MONOSPACED, Font.BOLD, 22));
        g.drawString("ICARUS EVIDENCE • " + title, 20, 35);
        
        g.setColor(Color.LIGHT_GRAY);
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 16));
        g.drawString("Severity: " + severity + " | " + desc, 20, 65);
        
        // Split Line
        g.setColor(SEPARATOR_COLOR);
        g.drawLine(imgWidth / 2, 80, imgWidth / 2, imgHeight);

        // Column Titles
        int y = 110;
        g.setFont(BOLD_FONT);
        g.setColor(Color.WHITE);
        g.drawString("REQUEST", 20, y);
        g.drawString("RESPONSE", imgWidth / 2 + 20, y);
        
        y += 30;
        g.setFont(MONO_FONT);
        g.setColor(TEXT_COLOR);

        // Draw Text
        int reqY = y;
        for (String line : req.split("\n")) {
            g.drawString(line, 20, reqY);
            reqY += 20;
            if (reqY > imgHeight - 20 && !force1080) break;
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

    // ===================================================================================
    // PHASE 2: VISUAL ANNOTATION
    // ===================================================================================

    private void showPhase2(JDialog parentEditor, Finding finding, BufferedImage snap, String finalTitle) {
        parentEditor.getContentPane().removeAll();
        parentEditor.setTitle("ICARUS Evidence Editor - Phase 2: Visual Annotation");

        JPanel root = new JPanel(new BorderLayout());

        JLabel imgLabel = new JLabel(new ImageIcon(snap));
        JLayeredPane stack = new JLayeredPane();
        imgLabel.setBounds(0, 0, snap.getWidth(), snap.getHeight());
        stack.add(imgLabel, Integer.valueOf(0));

        JPanel canvas = new JPanel(null);
        canvas.setBackground(Color.DARK_GRAY);
        canvas.setPreferredSize(new Dimension(snap.getWidth() + 24, snap.getHeight() + 24));
        stack.setBounds(12, 12, snap.getWidth(), snap.getHeight());
        canvas.add(stack);

        List<Shape> shapes = new ArrayList<>();
        List<Color> cols = new ArrayList<>();
        List<String> kinds = new ArrayList<>();
        
        Color[] curCol = { Color.RED };
        String[] mode = { "BOX" }; // BOX, ARROW, HIGHLIGHT, REDACT
        Point[] start = { null };
        Shape[] preview = { null };

        JComponent drawLayer = new JComponent() {
            { setOpaque(false); }
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                
                for (int i = 0; i < shapes.size(); i++) {
                    drawShape(g2, shapes.get(i), kinds.get(i), cols.get(i));
                }
                if (preview[0] != null) {
                    drawShape(g2, preview[0], mode[0], curCol[0]);
                }
                g2.dispose();
            }

            private void drawShape(Graphics2D g2, Shape s, String kind, Color c) {
                g2.setStroke(new BasicStroke(3f));
                if ("HIGHLIGHT".equals(kind)) {
                    g2.setColor(new Color(255, 255, 0, 80));
                    g2.fill(s);
                } else if ("REDACT".equals(kind)) {
                    g2.setColor(Color.BLACK);
                    g2.fill(s);
                } else if ("ARROW".equals(kind)) {
                    g2.setColor(c);
                    g2.draw(s);
                    // Draw arrowhead manually in the drag logic below
                } else {
                    g2.setColor(c);
                    g2.draw(s);
                }
            }
        };
        drawLayer.setBounds(0, 0, snap.getWidth(), snap.getHeight());
        stack.add(drawLayer, Integer.valueOf(100));

        MouseAdapter dm = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                start[0] = e.getPoint();
            }
            @Override
            public void mouseDragged(MouseEvent e) {
                if (start[0] == null) return;
                Point p = e.getPoint();
                
                if ("ARROW".equals(mode[0])) {
                    preview[0] = createArrow(start[0], p);
                } else {
                    int x = Math.min(start[0].x, p.x);
                    int y = Math.min(start[0].y, p.y);
                    int w = Math.abs(p.x - start[0].x);
                    int h = Math.abs(p.y - start[0].y);
                    preview[0] = new Rectangle2D.Double(x, y, w, h);
                }
                drawLayer.repaint();
            }
            @Override
            public void mouseReleased(MouseEvent e) {
                if (preview[0] != null) {
                    shapes.add(preview[0]);
                    cols.add(curCol[0]);
                    kinds.add(mode[0]);
                }
                start[0] = null;
                preview[0] = null;
                drawLayer.repaint();
            }
        };
        drawLayer.addMouseListener(dm);
        drawLayer.addMouseMotionListener(dm);

        root.add(new JScrollPane(canvas), BorderLayout.CENTER);

        // Toolbar
        JPanel bar = new JPanel(new GridLayout(0, 1, 0, 8));
        bar.setBorder(new EmptyBorder(8, 8, 8, 8));
        root.add(bar, BorderLayout.EAST);

        JButton colourBtn = new JButton("Colour"); colourBtn.setOpaque(true);
        colourBtn.setBackground(curCol[0]);
        JToggleButton boxBtn = new JToggleButton("Box", true);
        JToggleButton arrowBtn = new JToggleButton("Arrow");
        JToggleButton hiBtn = new JToggleButton("Highlight");
        JToggleButton redactBtn = new JToggleButton("Redact");
        JButton undoBtn = new JButton("Undo");
        JButton saveBtn = new JButton("Save Evidence");

        ButtonGroup grp = new ButtonGroup();
        grp.add(boxBtn); grp.add(arrowBtn); grp.add(hiBtn); grp.add(redactBtn);

        Arrays.asList(colourBtn, boxBtn, arrowBtn, hiBtn, redactBtn, undoBtn, saveBtn)
                .forEach(b -> b.setFont(b.getFont().deriveFont(Font.BOLD, 14f)));

        ActionListener modeSel = a -> {
            if (a.getSource() == boxBtn) mode[0] = "BOX";
            else if (a.getSource() == arrowBtn) mode[0] = "ARROW";
            else if (a.getSource() == hiBtn) mode[0] = "HIGHLIGHT";
            else if (a.getSource() == redactBtn) mode[0] = "REDACT";
            drawLayer.setCursor(Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR));
        };
        boxBtn.addActionListener(modeSel); arrowBtn.addActionListener(modeSel);
        hiBtn.addActionListener(modeSel); redactBtn.addActionListener(modeSel);

        colourBtn.addActionListener(a -> {
            Color chosen = JColorChooser.showDialog(parentEditor, "Choose colour", curCol[0]);
            if (chosen != null) { curCol[0] = chosen; colourBtn.setBackground(curCol[0]); }
        });

        undoBtn.addActionListener(a -> {
            if (!shapes.isEmpty()) {
                shapes.remove(shapes.size() - 1);
                cols.remove(cols.size() - 1);
                kinds.remove(kinds.size() - 1);
                drawLayer.repaint();
            }
        });

        saveBtn.addActionListener(a -> {
            try {
                BufferedImage out = new BufferedImage(snap.getWidth(), snap.getHeight(), BufferedImage.TYPE_INT_ARGB);
                Graphics2D g2 = out.createGraphics();
                g2.drawImage(snap, 0, 0, null);
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setStroke(new BasicStroke(3f));
                for (int i = 0; i < shapes.size(); i++) {
                    String kind = kinds.get(i);
                    Shape s = shapes.get(i);
                    if ("HIGHLIGHT".equals(kind)) {
                        g2.setColor(new Color(255, 255, 0, 80));
                        g2.fill(s);
                    } else if ("REDACT".equals(kind)) {
                        g2.setColor(Color.BLACK);
                        g2.fill(s);
                    } else {
                        g2.setColor(cols.get(i));
                        g2.draw(s);
                    }
                }
                g2.dispose();

                JFileChooser fc = new JFileChooser(new File(System.getProperty("user.home")));
                fc.setSelectedFile(new File("evidence-" + finalTitle.replaceAll("[^a-zA-Z0-9.-]", "_") + ".png"));
                if (fc.showSaveDialog(parentEditor) == JFileChooser.APPROVE_OPTION) {
                    File f = fc.getSelectedFile();
                    ImageIO.write(out, "png", f);
                    captured.add(new CapturedEvidence(finding, f.toPath(), out));
                    JOptionPane.showMessageDialog(parentEditor, "Saved: " + f.getAbsolutePath());
                    parentEditor.dispose();
                }
            } catch (Exception ex) {
                ex.printStackTrace();
            }
        });

        bar.add(colourBtn);
        bar.add(boxBtn);
        bar.add(arrowBtn);
        bar.add(hiBtn);
        bar.add(redactBtn);
        bar.add(undoBtn);
        bar.add(new JSeparator());
        bar.add(saveBtn);

        parentEditor.setContentPane(root);
        parentEditor.revalidate();
        parentEditor.repaint();
    }

    private Shape createArrow(Point from, Point to) {
        Path2D path = new Path2D.Double();
        path.moveTo(from.x, from.y);
        path.lineTo(to.x, to.y);
        
        // Arrowhead
        double angle = Math.atan2(to.y - from.y, to.x - from.x);
        int arrowSize = 15;
        path.lineTo(to.x - arrowSize * Math.cos(angle - Math.PI / 6),
                    to.y - arrowSize * Math.sin(angle - Math.PI / 6));
        path.moveTo(to.x, to.y);
        path.lineTo(to.x - arrowSize * Math.cos(angle + Math.PI / 6),
                    to.y - arrowSize * Math.sin(angle + Math.PI / 6));
        return path;
    }

    public record CapturedEvidence(Finding finding, Path imagePath, BufferedImage image) {}
}
