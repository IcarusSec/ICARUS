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

    private JScrollPane createSmoothScrollPane(Component c) {
        JScrollPane scroll = new JScrollPane(c);
        scroll.setBorder(BorderFactory.createLineBorder(new Color(60, 60, 60), 1));
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        return scroll;
    }

    private JButton createModernButton(String text, Color bg) {
        JButton btn = new JButton(text);
        btn.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 14));
        btn.setBackground(bg);
        btn.setForeground(Color.WHITE);
        btn.setFocusPainted(false);
        btn.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(bg.darker(), 1),
            new EmptyBorder(8, 16, 8, 16)
        ));
        btn.setCursor(new Cursor(Cursor.HAND_CURSOR));
        return btn;
    }

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
        pnlTop.setBackground(HEADER_BG);

        JLabel lblTitle = new JLabel("Evidence Title:");
        lblTitle.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 14));
        lblTitle.setForeground(Color.WHITE);
        JTextField txtName = new JTextField(finding.type(), 20);
        txtName.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 14));

        JLabel lblDesc = new JLabel("Description:");
        lblDesc.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 14));
        lblDesc.setForeground(Color.WHITE);
        JTextField txtDesc = new JTextField(finding.description() != null ? finding.description() : "", 40);
        txtDesc.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 14));

        pnlTop.add(lblTitle);
        pnlTop.add(txtName);
        pnlTop.add(lblDesc);
        pnlTop.add(txtDesc);

        // Text Areas — include the request line (method + path) and status line
        var rr = finding.evidence();
        String reqContentType = rr.request().headerValue("Content-Type");
        String reqLine = rr.request().method() + " " + rr.request().path() + " " + rr.request().httpVersion() + "\n";
        String reqText = reqLine + rr.request().headers().stream()
                .map(h -> h.name() + ": " + h.value() + "\n")
                .reduce("", String::concat) + formatBody(rr.request().body().getBytes(), reqContentType);

        String resText = "";
        if (rr.response() != null) {
            String resContentType = rr.response().headerValue("Content-Type");
            String statusLine = rr.response().httpVersion() + " " + rr.response().statusCode() + " " + rr.response().reasonPhrase() + "\n";
            resText = statusLine + rr.response().headers().stream()
                    .map(h -> h.name() + ": " + h.value() + "\n")
                    .reduce("", String::concat) + formatBody(rr.response().body().getBytes(), resContentType);
        }

        reqText = wrapEvidenceText(reqText, 120);
        resText = wrapEvidenceText(resText, 120);

        JTextArea reqArea = createStyledTextArea(reqText);
        JTextArea resArea = createStyledTextArea(resText);

        attachSmartContextMenu(reqArea);
        attachSmartContextMenu(resArea);

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT);
        split.setLeftComponent(createSmoothScrollPane(reqArea));
        split.setRightComponent(createSmoothScrollPane(resArea));
        split.setResizeWeight(0.5);
        split.setDividerSize(4);
        split.setBorder(null);

        // Bottom Bar
        JPanel pnlBottom = new JPanel(new FlowLayout(FlowLayout.RIGHT, 15, 10));
        pnlBottom.setBorder(new EmptyBorder(5, 10, 10, 10));

        JButton btnCleanNoise = createModernButton("Clean Standard Noise", new Color(70, 70, 70));
        JCheckBox chk1080 = new JCheckBox("Force 1920x1080", true);
        chk1080.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 14));

        JButton btnProceed = createModernButton("Proceed to Annotation ➔", ACCENT_COLOR);
        btnProceed.setForeground(Color.WHITE);

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
        ta.setMargin(new Insets(10, 15, 10, 15));
        ta.setTabSize(4);

        javax.swing.undo.UndoManager undoManager = new javax.swing.undo.UndoManager();
        ta.getDocument().addUndoableEditListener(e -> undoManager.addEdit(e.getEdit()));

        InputMap im = ta.getInputMap(JComponent.WHEN_FOCUSED);
        ActionMap am = ta.getActionMap();

        int ctrl = Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_Z, ctrl), "Undo");
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_Y, ctrl), "Redo");
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_Z, ctrl | InputEvent.SHIFT_DOWN_MASK), "Redo");

        am.put("Undo", new AbstractAction() {
            public void actionPerformed(ActionEvent e) {
                if (undoManager.canUndo()) undoManager.undo();
            }
        });
        am.put("Redo", new AbstractAction() {
            public void actionPerformed(ActionEvent e) {
                if (undoManager.canRedo()) undoManager.redo();
            }
        });

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

    private String wrapEvidenceText(String text, int maxLineLength) {
        StringBuilder sb = new StringBuilder();
        for (String line : text.split("\n")) {
            if (line.length() <= maxLineLength) {
                sb.append(line).append("\n");
                continue;
            }

            int start = 0;
            while (start < line.length()) {
                int end = Math.min(start + maxLineLength, line.length());
                sb.append(line, start, end).append("\n");
                start = end;
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

        // Minimal dark theme — neutral grays, no saturated accents outside content
        Color imgBg       = new Color(24, 24, 24);
        Color imgHeaderBg = new Color(18, 18, 18);
        Color imgText     = new Color(212, 212, 212);
        Color imgDim      = new Color(120, 120, 120);
        Color imgDivider  = new Color(50, 50, 50);

        // Fill background
        g.setColor(imgBg);
        g.fillRect(0, 0, imgWidth, imgHeight);

        // Header Banner
        g.setColor(imgHeaderBg);
        g.fillRect(0, 0, imgWidth, 70);
        g.setColor(imgDivider);
        g.drawLine(0, 70, imgWidth, 70);

        g.setColor(Color.WHITE);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 18));
        g.drawString("ICARUS  ·  " + title, 20, 30);

        g.setColor(imgDim);
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 13));
        g.drawString(severity + "  ·  " + desc, 20, 55);

        // Column labels + divider
        int colLabelY = 90;
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12));
        g.setColor(imgDim);
        g.drawString("REQUEST", 20, colLabelY);
        g.drawString("RESPONSE", imgWidth / 2 + 20, colLabelY);

        g.setColor(imgDivider);
        g.drawLine(imgWidth / 2, 70, imgWidth / 2, imgHeight);

        int y = colLabelY + 22;
        g.setFont(MONO_FONT);

        // Clip columns
        Shape originalClip = g.getClip();

        // Request (left)
        g.setClip(0, 70, imgWidth / 2 - 5, imgHeight - 70);
        int reqY = y;
        for (String line : req.split("\n")) {
            drawLine(g, line, 20, reqY, imgText, true);
            reqY += 18;
            if (reqY > imgHeight - 10 && !force1080) break;
        }

        // Response (right)
        g.setClip(imgWidth / 2 + 5, 70, imgWidth / 2 - 5, imgHeight - 70);
        int resY = y;
        for (String line : res.split("\n")) {
            drawLine(g, line, imgWidth / 2 + 20, resY, imgText, false);
            resY += 18;
            if (resY > imgHeight - 10 && !force1080) break;
        }

        g.setClip(originalClip);
        g.dispose();
        return img;
    }

    /**
     * Draws a single line with minimal syntax coloring:
     *  - Request line (GET /path HTTP/1.1): white bold
     *  - Status line (HTTP/1.1 200 OK): status code colored by range
     *  - Header key: dim gray, value: normal text
     *  - JSON keys: dim, strings: soft green, numbers/booleans: soft blue
     *  - Everything else: default text color
     */
    private void drawLine(Graphics2D g, String line, int x, int y, Color textCol, boolean isRequest) {
        Color dimGray  = new Color(120, 120, 120);
        Color jsonKey  = new Color(150, 150, 150);
        Color jsonStr  = new Color(106, 171, 115); // Muted green
        Color jsonNum  = new Color(104, 151, 187); // Muted blue

        String trimmed = line.trim();

        // Request line: GET /path HTTP/1.1
        if (isRequest && (trimmed.startsWith("GET ") || trimmed.startsWith("POST ") || trimmed.startsWith("PUT ") ||
            trimmed.startsWith("DELETE ") || trimmed.startsWith("PATCH ") || trimmed.startsWith("HEAD ") ||
            trimmed.startsWith("OPTIONS ") || trimmed.startsWith("TRACE ") || trimmed.startsWith("CONNECT "))) {
            g.setFont(BOLD_FONT);
            g.setColor(Color.WHITE);
            g.drawString(line, x, y);
            g.setFont(MONO_FONT);
            return;
        }

        // Status line: HTTP/1.1 200 OK
        if (!isRequest && trimmed.startsWith("HTTP/")) {
            g.setFont(BOLD_FONT);
            // Extract status code for coloring
            int statusCode = extractStatusCode(trimmed);
            g.setColor(statusCodeColor(statusCode));
            g.drawString(line, x, y);
            g.setFont(MONO_FONT);
            return;
        }

        // Header line (Key: Value) — not inside JSON
        if (trimmed.contains(":") && !trimmed.startsWith("{") && !trimmed.startsWith("}") &&
            !trimmed.startsWith("[") && !trimmed.startsWith("]") &&
            !trimmed.startsWith("\"") && !trimmed.startsWith(" ") && !trimmed.startsWith("\t")) {
            int colonIdx = line.indexOf(':');
            String key = line.substring(0, colonIdx + 1);
            String val = line.substring(colonIdx + 1);
            g.setColor(dimGray);
            g.drawString(key, x, y);
            int keyWidth = g.getFontMetrics().stringWidth(key);
            g.setColor(textCol);
            g.drawString(val, x + keyWidth, y);
            return;
        }

        // JSON-ish lines
        if (trimmed.startsWith("\"") && trimmed.contains(":")) {
            // "key": value
            int colonIdx = trimmed.indexOf(':');
            String rawKey = trimmed.substring(0, colonIdx + 1);
            String rawVal = trimmed.substring(colonIdx + 1).trim();

            // Preserve leading whitespace from original line
            int indent = line.indexOf(trimmed.charAt(0));
            String prefix = indent > 0 ? line.substring(0, indent) : "";

            g.setColor(jsonKey);
            g.drawString(prefix + rawKey, x, y);
            int keyWidth = g.getFontMetrics().stringWidth(prefix + rawKey + " ");

            g.setColor(colorForJsonValue(rawVal, jsonStr, jsonNum, textCol));
            g.drawString(" " + rawVal, x + keyWidth - g.getFontMetrics().stringWidth(" "), y);
            return;
        }

        // Default
        g.setColor(textCol);
        g.drawString(line, x, y);
    }

    private int extractStatusCode(String statusLine) {
        // "HTTP/1.1 200 OK" -> 200
        String[] parts = statusLine.trim().split("\\s+");
        if (parts.length >= 2) {
            try { return Integer.parseInt(parts[1]); } catch (NumberFormatException ignored) {}
        }
        return 0;
    }

    private Color statusCodeColor(int code) {
        if (code >= 200 && code < 300) return new Color(80, 180, 80);   // Green — success
        if (code >= 300 && code < 400) return new Color(80, 140, 200);  // Blue — redirect
        if (code >= 400 && code < 500) return new Color(220, 140, 60);  // Orange — client error
        if (code >= 500)               return new Color(210, 70, 70);   // Red — server error
        return new Color(212, 212, 212); // Unknown — default text
    }

    private Color colorForJsonValue(String val, Color strCol, Color numCol, Color defaultCol) {
        if (val.isEmpty()) return defaultCol;
        // Remove trailing comma
        String clean = val.endsWith(",") ? val.substring(0, val.length() - 1).trim() : val.trim();
        if (clean.startsWith("\""))  return strCol;
        if ("true".equals(clean) || "false".equals(clean) || "null".equals(clean)) return numCol;
        try { Double.parseDouble(clean); return numCol; } catch (NumberFormatException ignored) {}
        return defaultCol;
    }

    // ===================================================================================
    // PHASE 2: VISUAL ANNOTATION
    // ===================================================================================

    private void showPhase2(JDialog parentEditor, Finding finding, BufferedImage snap, String finalTitle) {
        parentEditor.getContentPane().removeAll();
        parentEditor.setTitle("ICARUS Evidence — Annotation");
        parentEditor.setMinimumSize(new Dimension(640, 480));

        // Make the dialog behave like a proper window with minimize/maximize
        JFrame frame = new JFrame("ICARUS Evidence — Annotation");
        frame.setSize(1200, 800);
        frame.setLocationRelativeTo(null);
        frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);

        JPanel root = new JPanel(new BorderLayout());

        // Canvas panel that paints the image and annotations at an offset (for panning)
        List<Shape> shapes = new ArrayList<>();
        List<Color> cols = new ArrayList<>();
        List<String> kinds = new ArrayList<>();

        Color[] curCol = { Color.RED };
        String[] mode = { "PAN" }; // PAN, BOX, ARROW, HIGHLIGHT, REDACT
        Point[] dragStart = { null };
        Shape[] preview = { null };
        int[] panOffset = { 0, 0 }; // x, y offset for panning

        JPanel canvas = new JPanel() {
            @Override
            protected void paintComponent(Graphics gr) {
                super.paintComponent(gr);
                Graphics2D g2 = (Graphics2D) gr.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

                // Draw image at pan offset
                g2.drawImage(snap, panOffset[0], panOffset[1], null);

                // Draw committed shapes
                g2.translate(panOffset[0], panOffset[1]);
                g2.setStroke(new BasicStroke(3f));
                for (int i = 0; i < shapes.size(); i++) {
                    drawAnnotation(g2, shapes.get(i), kinds.get(i), cols.get(i));
                }
                // Draw live preview
                if (preview[0] != null) {
                    drawAnnotation(g2, preview[0], mode[0], curCol[0]);
                }
                g2.dispose();
            }

            private void drawAnnotation(Graphics2D g2, Shape s, String kind, Color c) {
                if ("HIGHLIGHT".equals(kind)) {
                    g2.setColor(new Color(255, 255, 0, 80));
                    g2.fill(s);
                } else if ("REDACT".equals(kind)) {
                    g2.setColor(Color.BLACK);
                    g2.fill(s);
                } else {
                    g2.setColor(c);
                    g2.draw(s);
                }
            }
        };
        canvas.setBackground(new Color(30, 30, 30));
        canvas.setPreferredSize(new Dimension(snap.getWidth() + 200, snap.getHeight() + 200));

        MouseAdapter mouseHandler = new MouseAdapter() {
            private Point panAnchor;

            @Override
            public void mousePressed(MouseEvent e) {
                if ("PAN".equals(mode[0])) {
                    panAnchor = e.getPoint();
                    canvas.setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
                } else {
                    // Convert screen point to image-space point
                    dragStart[0] = new Point(e.getX() - panOffset[0], e.getY() - panOffset[1]);
                }
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                if ("PAN".equals(mode[0]) && panAnchor != null) {
                    panOffset[0] += e.getX() - panAnchor.x;
                    panOffset[1] += e.getY() - panAnchor.y;
                    panAnchor = e.getPoint();
                    canvas.repaint();
                } else if (dragStart[0] != null) {
                    Point p = new Point(e.getX() - panOffset[0], e.getY() - panOffset[1]);
                    if ("ARROW".equals(mode[0])) {
                        preview[0] = createArrow(dragStart[0], p);
                    } else {
                        int x = Math.min(dragStart[0].x, p.x);
                        int y = Math.min(dragStart[0].y, p.y);
                        int w = Math.abs(p.x - dragStart[0].x);
                        int h = Math.abs(p.y - dragStart[0].y);
                        preview[0] = new Rectangle2D.Double(x, y, w, h);
                    }
                    canvas.repaint();
                }
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                if ("PAN".equals(mode[0])) {
                    panAnchor = null;
                    canvas.setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
                } else if (preview[0] != null) {
                    shapes.add(preview[0]);
                    cols.add(curCol[0]);
                    kinds.add(mode[0]);
                    preview[0] = null;
                    dragStart[0] = null;
                    canvas.repaint();
                }
            }
        };
        canvas.addMouseListener(mouseHandler);
        canvas.addMouseMotionListener(mouseHandler);

        JScrollPane scroll = new JScrollPane(canvas);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        scroll.getHorizontalScrollBar().setUnitIncrement(16);
        root.add(scroll, BorderLayout.CENTER);

        // Toolbar
        JPanel bar = new JPanel(new GridLayout(0, 1, 0, 6));
        bar.setBorder(new EmptyBorder(8, 8, 8, 8));

        JToggleButton panBtn = new JToggleButton("Pan", true);
        JToggleButton boxBtn = new JToggleButton("Box");
        JToggleButton arrowBtn = new JToggleButton("Arrow");
        JToggleButton hiBtn = new JToggleButton("Highlight");
        JToggleButton redactBtn = new JToggleButton("Redact");

        ButtonGroup grp = new ButtonGroup();
        grp.add(panBtn); grp.add(boxBtn); grp.add(arrowBtn); grp.add(hiBtn); grp.add(redactBtn);

        for (var b : List.of(panBtn, boxBtn, arrowBtn, hiBtn, redactBtn)) {
            b.setFont(b.getFont().deriveFont(Font.BOLD, 13f));
        }

        ActionListener modeSel = a -> {
            if (a.getSource() == panBtn) {
                mode[0] = "PAN";
                canvas.setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
            } else {
                if (a.getSource() == boxBtn) mode[0] = "BOX";
                else if (a.getSource() == arrowBtn) mode[0] = "ARROW";
                else if (a.getSource() == hiBtn) mode[0] = "HIGHLIGHT";
                else if (a.getSource() == redactBtn) mode[0] = "REDACT";
                canvas.setCursor(Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR));
            }
        };
        panBtn.addActionListener(modeSel); boxBtn.addActionListener(modeSel);
        arrowBtn.addActionListener(modeSel); hiBtn.addActionListener(modeSel);
        redactBtn.addActionListener(modeSel);

        JButton colourBtn = createModernButton("Colour", curCol[0]);
        colourBtn.addActionListener(a -> {
            Color chosen = JColorChooser.showDialog(frame, "Choose colour", curCol[0]);
            if (chosen != null) { curCol[0] = chosen; colourBtn.setBackground(curCol[0]); }
        });

        JButton undoBtn = createModernButton("Undo", new Color(70, 70, 70));
        undoBtn.addActionListener(a -> {
            if (!shapes.isEmpty()) {
                shapes.remove(shapes.size() - 1);
                cols.remove(cols.size() - 1);
                kinds.remove(kinds.size() - 1);
                canvas.repaint();
            }
        });

        JButton saveBtn = createModernButton("Save Evidence", ACCENT_COLOR);
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
                if (fc.showSaveDialog(frame) == JFileChooser.APPROVE_OPTION) {
                    File f = fc.getSelectedFile();
                    ImageIO.write(out, "png", f);
                    captured.add(new CapturedEvidence(finding, f.toPath(), out));
                    JOptionPane.showMessageDialog(frame, "Saved: " + f.getAbsolutePath());
                    frame.dispose();
                }
            } catch (Exception ex) {
                ex.printStackTrace();
            }
        });

        bar.add(panBtn);
        bar.add(new JSeparator());
        bar.add(colourBtn);
        bar.add(boxBtn);
        bar.add(arrowBtn);
        bar.add(hiBtn);
        bar.add(redactBtn);
        bar.add(undoBtn);
        bar.add(new JSeparator());
        bar.add(saveBtn);
        root.add(bar, BorderLayout.EAST);

        // Default cursor for pan mode
        canvas.setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));

        parentEditor.dispose(); // Close the Phase 1 dialog
        frame.setContentPane(root);
        frame.setVisible(true);
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

    private String formatBody(byte[] body, String contentType) {
        if (body == null || body.length == 0) return "";
        
        boolean isBinary = false;
        if (contentType != null) {
            String ct = contentType.toLowerCase();
            if (ct.contains("image/") || ct.contains("application/octet-stream") || 
                ct.contains("application/pdf") || ct.contains("application/zip") ||
                ct.contains("audio/") || ct.contains("video/")) {
                isBinary = true;
            }
        }
        
        if (!isBinary) {
            int unprintable = 0;
            for (int i = 0; i < Math.min(body.length, 512); i++) {
                byte b = body[i];
                if (b == 0 || (b < 32 && b != '\n' && b != '\r' && b != '\t')) {
                    unprintable++;
                }
            }
            if (unprintable > 2) isBinary = true;
        }

        if (isBinary) {
            return "\n--- BINARY PAYLOAD (HEX DUMP) ---\n" + toHexDump(body);
        } else {
            String text = new String(body, java.nio.charset.StandardCharsets.UTF_8);
            return "\n" + JsonParser.formatJsonString(text);
        }
    }

    private String toHexDump(byte[] data) {
        StringBuilder sb = new StringBuilder();
        int width = 16;
        for (int i = 0; i < data.length; i += width) {
            sb.append(String.format("%08x  ", i));
            for (int j = 0; j < width; j++) {
                if (i + j < data.length) {
                    sb.append(String.format("%02x ", data[i + j]));
                } else {
                    sb.append("   ");
                }
                if (j == 7) sb.append(" ");
            }
            sb.append(" |");
            for (int j = 0; j < width && i + j < data.length; j++) {
                char c = (char) data[i + j];
                if (c >= 32 && c <= 126) {
                    sb.append(c);
                } else {
                    sb.append('.');
                }
            }
            sb.append("|\n");
        }
        return sb.toString();
    }
    public record CapturedEvidence(Finding finding, Path imagePath, BufferedImage image) {}
}
