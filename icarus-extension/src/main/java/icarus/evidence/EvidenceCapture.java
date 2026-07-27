package icarus.evidence;

import burp.api.montoya.MontoyaApi;
import icarus.core.Category;
import icarus.core.Finding;
import icarus.core.JsonParser;
import icarus.core.ModuleConfig;

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
    private final ModuleConfig config;
    private final List<CapturedEvidence> captured = new ArrayList<>();

    public EvidenceCapture(MontoyaApi api, ModuleConfig config) {
        this.api = api;
        this.config = config;
    }

    public List<CapturedEvidence> getCaptured() {
        return List.copyOf(captured);
    }

    public void captureInteractive(Finding finding) {
        SwingUtilities.invokeLater(() -> {
            if (finding.category() == Category.RATE_LIMIT && finding.metadata().containsKey("blast_log")) {
                BufferedImage tableImg = renderRateLimitTable(finding, true);
                showPhase2(new JDialog(), finding, tableImg, finding.type());
            } else {
                showPhase1(finding);
            }
        });
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

        // Wrap for the default layout (chk1080 below defaults to checked, i.e. 1920x1080).
        // Wrapping narrower than what's actually rendered wastes space — a line wrapped
        // for the 1200px column only fills ~61% of the 1920px column's real width, needing
        // far more lines (and therefore a much taller image) than necessary. If the user
        // unchecks the box, the chk1080 listener below re-wraps down to the narrower width,
        // which is always a safe direction (splitting an already-short-enough line further).
        int wrapWidth = maxCharsForColumnWidth(1920);
        reqText = wrapEvidenceText(reqText, wrapWidth);
        resText = wrapEvidenceText(resText, wrapWidth);

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
        // The text areas are wrapped for 1920 above (the checkbox's default). If the user
        // switches to the narrower 1200 layout, re-wrap down to that width — safe, since
        // splitting already-short-enough lines further never loses content. Going back to
        // 1920 needs no action: lines already wrapped for 1200 remain valid (just using less
        // of the wider column than optimal), which is a cosmetic tradeoff, not a bug.
        chk1080.addItemListener(e -> {
            if (e.getStateChange() == ItemEvent.DESELECTED) {
                int narrowWidth = maxCharsForColumnWidth(1200);
                // setText() resets the caret to 0, which would jump the view to the top of
                // a long payload — restore the caret's prior offset (clamped to the new,
                // slightly longer re-wrapped length) so the user doesn't lose their place.
                int reqCaret = reqArea.getCaretPosition();
                int resCaret = resArea.getCaretPosition();
                reqArea.setText(wrapEvidenceText(reqArea.getText(), narrowWidth));
                resArea.setText(wrapEvidenceText(resArea.getText(), narrowWidth));
                reqArea.setCaretPosition(Math.min(reqCaret, reqArea.getDocument().getLength()));
                resArea.setCaretPosition(Math.min(resCaret, resArea.getDocument().getLength()));
            }
        });

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

    private String truncate(String text, Graphics2D g, int maxWidth) {
        if (g.getFontMetrics().stringWidth(text) <= maxWidth) return text;
        String dot = "...";
        int dotWidth = g.getFontMetrics().stringWidth(dot);
        while (text.length() > 0 && g.getFontMetrics().stringWidth(text) + dotWidth > maxWidth) {
            text = text.substring(0, text.length() - 1);
        }
        return text + dot;
    }

    /**
     * Computes how many monospace characters actually fit in a request/response column's
     * real pixel width, so wrapping matches what will actually be drawn instead of a
     * hardcoded guess. Both MONO_FONT and BOLD_FONT are monospace, so per-char advance
     * width is uniform within each — checking both covers request/status lines (bold) and
     * everything else (plain).
     */
    private int maxCharsForColumnWidth(int imgWidth) {
        int columnBudget = imgWidth / 2 - 25; // matches the setClip/x-offset math in both renderers
        BufferedImage probe = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = probe.createGraphics();
        try {
            FontMetrics monoFm = g.getFontMetrics(MONO_FONT);
            FontMetrics boldFm = g.getFontMetrics(BOLD_FONT);
            int worstCharWidth = 1;
            for (int c = 32; c < 127; c++) {
                worstCharWidth = Math.max(worstCharWidth, monoFm.charWidth(c));
                worstCharWidth = Math.max(worstCharWidth, boldFm.charWidth(c));
            }
            return Math.max(1, columnBudget / worstCharWidth);
        } finally {
            g.dispose();
        }
    }

    /**
     * Wraps text to maxLineLength, breaking on the last whitespace before the limit when
     * one exists (so prose doesn't split mid-word) and falling back to a hard character
     * break when a single token (URL, base64 blob, etc.) has no whitespace to break on.
     * Continuation lines keep the original line's leading indentation, so a wrapped JSON
     * or header value stays visually aligned within its structure instead of collapsing
     * to the left margin.
     */
    private String wrapEvidenceText(String text, int maxLineLength) {
        StringBuilder sb = new StringBuilder();
        for (String line : text.split("\n")) {
            if (line.length() <= maxLineLength) {
                sb.append(line).append("\n");
                continue;
            }

            int indentLen = 0;
            while (indentLen < line.length() && (line.charAt(indentLen) == ' ' || line.charAt(indentLen) == '\t')) {
                indentLen++;
            }
            if (indentLen >= line.length()) {
                indentLen = 0; // whole line is whitespace; nothing to preserve
            }
            String indent = line.substring(0, indentLen);
            int contentBudget = Math.max(10, maxLineLength - indentLen);

            int start = indentLen;
            while (start < line.length()) {
                int end = Math.min(start + contentBudget, line.length());
                if (end < line.length()) {
                    int lastSpace = line.lastIndexOf(' ', end);
                    if (lastSpace > start) {
                        end = lastSpace;
                    }
                }
                sb.append(indent).append(line, start, end).append("\n");
                start = end;
                while (start < line.length() && line.charAt(start) == ' ') start++;
            }
        }
        return sb.toString();
    }

    // ===================================================================================
    // IMAGE RENDERING
    // ===================================================================================

    private BufferedImage renderTextToImage(String req, String res, String title, String desc, String severity, boolean force1080) {
        int imgWidth = force1080 ? 1920 : 1200;
        int defaultHeight = force1080 ? 1080 : 800;

        // Split once and reuse for both the height calculation and drawing, so the two
        // never disagree on line count.
        String[] reqLines = req.split("\n");
        String[] resLines = res.split("\n");

        int colLabelY = 90;
        int y = colLabelY + 22;

        // Grow the image to fit all content instead of silently clipping whatever doesn't
        // fit the default size — a fixed height previously cut off long request/response
        // bodies with no indication anything was missing.
        int maxLineCount = Math.max(reqLines.length, resLines.length);
        int imgHeight = Math.max(defaultHeight, y + maxLineCount * 18 + 20);

        EvidenceColorScheme cs = EvidenceColorScheme.get(config.getString("evidence.colorscheme", "Minimal Dark"));

        BufferedImage img = new BufferedImage(imgWidth, imgHeight, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB);

        // Fill background
        g.setColor(cs.background());
        g.fillRect(0, 0, imgWidth, imgHeight);

        // Header Banner
        g.setColor(cs.headerBg());
        g.fillRect(0, 0, imgWidth, 70);
        g.setColor(cs.divider());
        g.drawLine(0, 70, imgWidth, 70);

        g.setColor(cs.titleText());
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 18));
        g.drawString("ICARUS  ·  " + title, 20, 30);

        g.setColor(cs.dim());
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 13));
        g.drawString(severity + "  ·  " + desc, 20, 55);

        // Column labels + divider
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12));
        g.setColor(cs.dim());
        g.drawString("REQUEST", 20, colLabelY);
        g.drawString("RESPONSE", imgWidth / 2 + 20, colLabelY);

        g.setColor(cs.divider());
        g.drawLine(imgWidth / 2, 70, imgWidth / 2, imgHeight);

        g.setFont(MONO_FONT);

        // Clip columns
        Shape originalClip = g.getClip();

        // Request (left)
        g.setClip(0, 70, imgWidth / 2 - 5, imgHeight - 70);
        int reqY = y;
        Color[] reqLastColor = new Color[1];
        for (String line : reqLines) {
            drawLine(g, line, 20, reqY, cs, true, reqLastColor);
            reqY += 18;
        }

        // Response (right)
        g.setClip(imgWidth / 2 + 5, 70, imgWidth / 2 - 5, imgHeight - 70);
        int resY = y;
        Color[] resLastColor = new Color[1];
        for (String line : resLines) {
            drawLine(g, line, imgWidth / 2 + 20, resY, cs, false, resLastColor);
            resY += 18;
        }

        g.setClip(originalClip);
        g.dispose();
        return img;
    }

    /**
     * @param lastValueColor single-element carrier holding the color of the most recently
     *                       drawn key/value's value, so a wrapped continuation line (no
     *                       leading quote/colon of its own to classify by) can be drawn in
     *                       the same color as the value it's continuing instead of falling
     *                       back to the generic default. Reset to null on any line that
     *                       isn't itself indented, since that means it's fresh top-level
     *                       content, not a continuation. Re-derived from the current text's
     *                       own indentation every draw, so it stays correct even after the
     *                       user edits the text in showPhase1's JTextArea.
     */
    private void drawLine(Graphics2D g, String line, int x, int y, EvidenceColorScheme cs, boolean isRequest, Color[] lastValueColor) {
        String trimmed = line.trim();

        // Request line: GET /path HTTP/1.1
        if (isRequest && (trimmed.startsWith("GET ") || trimmed.startsWith("POST ") || trimmed.startsWith("PUT ") ||
            trimmed.startsWith("DELETE ") || trimmed.startsWith("PATCH ") || trimmed.startsWith("HEAD ") ||
            trimmed.startsWith("OPTIONS ") || trimmed.startsWith("TRACE ") || trimmed.startsWith("CONNECT "))) {
            g.setFont(BOLD_FONT);
            g.setColor(cs.titleText());
            g.drawString(line, x, y);
            g.setFont(MONO_FONT);
            lastValueColor[0] = null;
            return;
        }

        // Status line: HTTP/1.1 200 OK
        if (!isRequest && trimmed.startsWith("HTTP/")) {
            g.setFont(BOLD_FONT);
            int statusCode = extractStatusCode(trimmed);
            g.setColor(cs.statusColor(statusCode));
            g.drawString(line, x, y);
            g.setFont(MONO_FONT);
            lastValueColor[0] = null;
            return;
        }

        // Header line (Key: Value) — not inside JSON. Real top-level headers are never
        // indented (only JSON content is), so require no leading whitespace on the actual
        // line — otherwise a wrapped continuation fragment that happens to contain a colon
        // (a timestamp, a URL, etc.) would get misclassified as a fresh header.
        boolean hasLeadingWhitespace = !line.isEmpty() && (line.charAt(0) == ' ' || line.charAt(0) == '\t');
        if (trimmed.contains(":") && !trimmed.startsWith("{") && !trimmed.startsWith("}") &&
            !trimmed.startsWith("[") && !trimmed.startsWith("]") &&
            !trimmed.startsWith("\"") && !hasLeadingWhitespace) {
            int colonIdx = line.indexOf(':');
            String key = line.substring(0, colonIdx + 1);
            String val = line.substring(colonIdx + 1);
            g.setColor(cs.headerKey());
            g.drawString(key, x, y);
            int keyWidth = g.getFontMetrics().stringWidth(key);
            g.setColor(cs.text());
            g.drawString(val, x + keyWidth, y);
            lastValueColor[0] = cs.text();
            return;
        }

        // JSON-ish lines
        if (trimmed.startsWith("\"") && trimmed.contains(":")) {
            int colonIdx = trimmed.indexOf(':');
            String rawKey = trimmed.substring(0, colonIdx + 1);
            String rawVal = trimmed.substring(colonIdx + 1).trim();

            int indent = line.indexOf(trimmed.charAt(0));
            String prefix = indent > 0 ? line.substring(0, indent) : "";

            g.setColor(cs.jsonKey());
            g.drawString(prefix + rawKey, x, y);
            int keyWidth = g.getFontMetrics().stringWidth(prefix + rawKey + " ");

            Color valueColor = colorForJsonValue(rawVal, cs.jsonString(), cs.jsonNumber(), cs.text());
            g.setColor(valueColor);
            g.drawString(" " + rawVal, x + keyWidth - g.getFontMetrics().stringWidth(" "), y);
            lastValueColor[0] = valueColor;
            return;
        }

        // A bare structural line (closing brace/bracket, possibly with a trailing comma)
        // ends whatever object/array it closes — draw it plainly and stop carrying the
        // previous value's color forward, even though it's indented like a continuation
        // would be, so a wrapped value's color doesn't leak onto the token that closes it.
        if (!trimmed.isEmpty() && trimmed.matches("[{}\\[\\],]*")) {
            g.setColor(cs.text());
            g.drawString(line, x, y);
            lastValueColor[0] = null;
            return;
        }

        // Default — but if this line is indented (looks like a wrapped continuation of the
        // previous value) and we're carrying a color forward, keep using it instead of the
        // flat default so wrapped values stay visually consistent.
        boolean indented = !line.isEmpty() && (line.charAt(0) == ' ' || line.charAt(0) == '\t');
        if (indented && lastValueColor[0] != null) {
            g.setColor(lastValueColor[0]);
        } else {
            g.setColor(cs.text());
            lastValueColor[0] = null;
        }
        g.drawString(line, x, y);
    }

    private int extractStatusCode(String statusLine) {
        String[] parts = statusLine.trim().split("\\s+");
        if (parts.length >= 2) {
            try { return Integer.parseInt(parts[1]); } catch (NumberFormatException ignored) {}
        }
        return 0;
    }

    private Color colorForJsonValue(String val, Color strCol, Color numCol, Color defaultCol) {
        if (val.isEmpty()) return defaultCol;
        String clean = val.endsWith(",") ? val.substring(0, val.length() - 1).trim() : val.trim();
        if (clean.startsWith("\""))  return strCol;
        if ("true".equals(clean) || "false".equals(clean) || "null".equals(clean)) return numCol;
        try { Double.parseDouble(clean); return numCol; } catch (NumberFormatException ignored) {}
        return defaultCol;
    }

    private BufferedImage renderRateLimitTable(Finding finding, boolean force1080) {
        int imgWidth = force1080 ? 1920 : 1200;
        int imgHeight = force1080 ? 1080 : 1200; // Gave it a bit more default vertical space to comfortably fit headers/body

        EvidenceColorScheme cs = EvidenceColorScheme.get(config.getString("evidence.colorscheme", "Minimal Dark"));

        return drawRateLimitTable(finding, imgWidth, imgHeight, cs, !force1080);
    }

    private BufferedImage drawRateLimitTable(Finding finding, int imgWidth, int imgHeight, EvidenceColorScheme cs, boolean allowGrow) {
        BufferedImage img = new BufferedImage(imgWidth, imgHeight, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB);

        g.setColor(cs.background());
        g.fillRect(0, 0, imgWidth, imgHeight);

        g.setColor(cs.headerBg());
        g.fillRect(0, 0, imgWidth, 70);
        g.setColor(cs.divider());
        g.drawLine(0, 70, imgWidth, 70);

        g.setColor(cs.titleText());
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 18));
        g.drawString("ICARUS EVIDENCE  ·  " + finding.type() + "  ·  " + finding.path(), 20, 30);

        String startTime = finding.metadata().getOrDefault("start_time", "");
        String endTime = finding.metadata().getOrDefault("end_time", "");
        String timeStr = (!startTime.isEmpty() && !endTime.isEmpty()) ? "  |  [" + startTime + " to " + endTime + "]" : "";

        g.setColor(cs.dim());
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 13));
        g.drawString(finding.description() + timeStr, 20, 55);

        String logStr = finding.metadata().get("blast_log");
        String[] entries = logStr.split(";");

        int y = 110;
        g.setFont(MONO_FONT);

        g.setColor(cs.titleText());
        g.drawString(" #", 20, y);
        g.drawString("Request", 80, y);
        g.drawString("Response", imgWidth / 2, y);
        g.drawString("Latency", imgWidth - 150, y);

        y += 10;
        g.setColor(cs.divider());
        g.drawLine(20, y, imgWidth - 20, y);
        y += 20;

        var rr = finding.evidence();
        String reqContentType = rr.request().headerValue("Content-Type");
        String method = rr.request().method();
        String path = rr.request().path();
        String reqLine = method + " " + path + " HTTP/" + rr.request().httpVersion();

        int total = entries.length;
        if (total == 0) {
            g.dispose();
            return img;
        }

        boolean noLimit = "NO_RATE_LIMIT".equals(finding.type());
        int flipIdx = -1;

        if (!noLimit && finding.metadata().containsKey("threshold")) {
            try { flipIdx = Integer.parseInt(finding.metadata().get("threshold")); } catch (Exception ignored) {}
        }

        List<Integer> rowsToShow = new ArrayList<>();
        if (noLimit || flipIdx < 0) {
            for (int i = 0; i < Math.min(3, total); i++) rowsToShow.add(i);
            rowsToShow.add(-1);
            for (int i = Math.max(3, total - 3); i < total; i++) rowsToShow.add(i);
        } else {
            for (int i = 0; i < Math.min(3, flipIdx - 1); i++) rowsToShow.add(i);
            if (flipIdx > 4) rowsToShow.add(-1);
            for (int i = Math.max(0, flipIdx - 1); i <= Math.min(total - 1, flipIdx + 2); i++) rowsToShow.add(i);
        }

        for (int i : rowsToShow) {
            if (i == -1) {
                g.setColor(cs.dim());
                g.drawString("    ···  (omitted similar requests)  ···", 80, y);
                y += 20;
                continue;
            }

            if (i >= entries.length || entries[i].isBlank()) continue;

            String[] parts = entries[i].split(":");
            if (parts.length < 3) continue;

            String idxStr = String.format("%3d", Integer.parseInt(parts[0]) + 1);
            int status = Integer.parseInt(parts[1]);
            String ms = parts[2] + "ms";

            g.setColor(cs.dim());
            g.drawString(idxStr, 20, y);

            g.setColor(cs.text());
            int maxReqWidth = (imgWidth / 2) - 100;
            g.drawString(truncate(reqLine, g, maxReqWidth), 80, y);

            g.setColor(cs.statusColor(status));
            g.drawString("HTTP/1.1 " + status, imgWidth / 2, y);

            g.setColor(cs.dim());
            g.drawString(ms, imgWidth - 150, y);

            if (i == flipIdx) {
                g.setColor(cs.status5xx());
                g.drawString(" ← BLOCKED", imgWidth - 80, y);
            }

            y += 20;
        }

        if (finding.metadata().containsKey("bypass_log") && !finding.metadata().get("bypass_log").isBlank()) {
            y += 40;
            g.setColor(cs.divider());
            g.drawLine(20, y, imgWidth - 20, y);
            y += 30;

            g.setColor(cs.titleText());
            g.drawString("Bypass Tests:", 20, y);
            y += 25;

            String bypassLog = finding.metadata().get("bypass_log");
            for (String line : bypassLog.split("\n")) {
                if (line.isBlank()) continue;
                if (line.startsWith("✓")) {
                    g.setColor(cs.status2xx());
                } else {
                    g.setColor(cs.status5xx());
                }
                g.drawString(line.substring(0, 1), 20, y);

                g.setColor(cs.text());
                g.drawString(line.substring(1), 35, y);
                y += 20;
            }
        }

        y += 60;
        g.setColor(cs.divider());
        g.drawLine(0, y, imgWidth, y);
        y += 40;

        g.setColor(cs.dim());
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12));
        g.drawString("BASE REQUEST", 20, y);
        g.drawString(noLimit ? "SAMPLE RESPONSE" : "BLOCK RESPONSE", imgWidth / 2 + 20, y);

        y += 22;
        g.setFont(MONO_FONT);

        String fullReq = reqLine + "\n" + rr.request().headers().stream()
                .map(h -> h.name() + ": " + h.value() + "\n")
                .reduce("", String::concat) + formatBody(rr.request().body().getBytes(), reqContentType);

        String fullRes = "";
        if (rr.response() != null) {
            String resContentType = rr.response().headerValue("Content-Type");
            String statusLine = rr.response().httpVersion() + " " + rr.response().statusCode() + " " + rr.response().reasonPhrase() + "\n";
            fullRes = statusLine + rr.response().headers().stream()
                    .map(h -> h.name() + ": " + h.value() + "\n")
                    .reduce("", String::concat) + formatBody(rr.response().body().getBytes(), resContentType);
        }

        // imgWidth is already fixed at this call site, so wrap tightly against the real
        // column budget instead of the conservative narrower-layout guess used above.
        int wrapWidth = maxCharsForColumnWidth(imgWidth);
        fullReq = wrapEvidenceText(fullReq, wrapWidth);
        fullRes = wrapEvidenceText(fullRes, wrapWidth);

        int reqLines = fullReq.split("\n").length;
        int resLines = fullRes.split("\n").length;
        int maxLines = Math.max(reqLines, resLines);

        int requiredHeight = y + (maxLines * 18) + 40;
        if (allowGrow && requiredHeight > imgHeight) {
            g.dispose();
            return drawRateLimitTable(finding, imgWidth, requiredHeight, cs, false);
        }

        Shape clipBackup = g.getClip();

        int clipY = y - 20;

        // Request
        g.setClip(0, clipY, imgWidth / 2 - 5, imgHeight - clipY);
        int rawReqY = y;
        Color[] rawReqLastColor = new Color[1];
        for (String line : fullReq.split("\n")) {
            drawLine(g, line, 20, rawReqY, cs, true, rawReqLastColor);
            rawReqY += 18;
        }

        // Response
        g.setClip(imgWidth / 2 + 5, clipY, imgWidth / 2 - 5, imgHeight - clipY);
        int rawResY = y;
        Color[] rawResLastColor = new Color[1];
        for (String line : fullRes.split("\n")) {
            drawLine(g, line, imgWidth / 2 + 20, rawResY, cs, false, rawResLastColor);
            rawResY += 18;
        }

        g.setClip(clipBackup);

        g.dispose();
        return img;
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
