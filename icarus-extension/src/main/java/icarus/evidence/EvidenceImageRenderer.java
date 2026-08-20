package icarus.evidence;

import burp.api.montoya.MontoyaApi;
import icarus.core.JsonParser;
import icarus.core.ModuleConfig;

import javax.swing.JOptionPane;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.Arrays;

public final class EvidenceImageRenderer {

    public static final Color BG_COLOR = new Color(34, 34, 34);
    public static final Color TEXT_COLOR = new Color(190, 190, 190);
    public static final Color ACCENT_COLOR = new Color(255, 102, 51);
    public static final Color SEPARATOR_COLOR = new Color(80, 80, 80);

    public static final Font MONO_FONT = new Font(Font.MONOSPACED, Font.PLAIN, 14);
    public static final Font BOLD_FONT = new Font(Font.MONOSPACED, Font.BOLD, 14);
    public static final int BINARY_TRUNCATE_BYTES = 2048;

    private static final Color FIXED_COLOR = new Color(0x2f, 0x9e, 0x44);
    private static final Color NOT_FIXED_COLOR = new Color(0xe0, 0x3e, 0x3e);

    public static void drawHeaderBanner(Graphics2D g, int imgWidth, EvidenceColorScheme cs, String title, String severity, String desc) {
        g.setColor(cs.headerBg());
        g.fillRect(0, 0, imgWidth, 70);
        g.setColor(cs.divider());
        g.drawLine(0, 70, imgWidth, 70);

        g.setColor(cs.titleText());
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 18));
        g.drawString(title, 20, 30);

        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 13));
        Color severityColor = "FIXED".equals(severity) ? FIXED_COLOR
                : "NOT_FIXED".equals(severity) ? NOT_FIXED_COLOR
                : cs.dim();
        g.setColor(severityColor);
        g.drawString(severity, 20, 55);
        int severityWidth = g.getFontMetrics().stringWidth(severity);

        g.setColor(cs.dim());
        g.drawString("  ·  " + desc, 20 + severityWidth, 55);
    }

    public static BufferedImage renderTextToImage(MontoyaApi api, ModuleConfig config, String req, String res, String title, String desc, String severity, boolean force1080) {
        int imgWidth = force1080 ? 1920 : 1200;
        int defaultHeight = force1080 ? 1080 : 800;

        // Split once and reuse for both the height calculation and drawing, so the two
        // never disagree on line count.
        String[] reqLines = req.split("\n");
        String[] resLines = res.split("\n");

        int colLabelY = 90;
        int y = colLabelY + 22;

        // Shrunk to fit the shorter of the two columns' actual content rather than always
        // allocating the full defaultHeight — a MISSING_* finding's few header lines used to
        // render inside a mostly-empty 1080px-tall image. Still capped at defaultHeight for long
        // JSON bodies: whatever doesn't fit gets a truncation marker (see drawColumnLines)
        // instead of the image growing unbounded or the caller having to guess a height upfront.
        int contentLines = Math.max(reqLines.length, resLines.length);
        int imgHeight = Math.max(300, Math.min(defaultHeight, y + contentLines * LINE_HEIGHT + 20));

        EvidenceColorScheme cs = EvidenceColorScheme.get(config.getString("evidence.colorscheme", "Minimal Dark"));

        BufferedImage img = new BufferedImage(imgWidth, imgHeight, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB);

        // Fill background
        g.setColor(cs.background());
        g.fillRect(0, 0, imgWidth, imgHeight);

        // Header Banner
        drawHeaderBanner(g, imgWidth, cs, "ICARUS  ·  " + title + projectNameSuffix(api, config), severity, desc);

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
        drawColumnLines(g, reqLines, 20, y, imgHeight, cs, true);

        // Response (right)
        g.setClip(imgWidth / 2 + 5, 70, imgWidth / 2 - 5, imgHeight - 70);
        drawColumnLines(g, resLines, imgWidth / 2 + 20, y, imgHeight, cs, false);

        g.setClip(originalClip);
        g.dispose();
        return img;
    }

    /**
     * Draws as many pre-wrapped lines as fit in [startY, imgHeight), then a dim truncation
     * marker for whatever's left — instead of the caller growing the image to fit everything
     * (which is what let a long JSON body balloon into a multi-thousand-pixel-tall PNG). Line
     * wrapping/indentation and drawLine's JSON/header syntax coloring are untouched; this only
     * bounds how many of the already-wrapped lines get drawn.
     */
    /** Pixel height of one drawn line — shared with {@link EvidenceAutoRenderer} so it can compute
     *  tight per-line annotation anchors (a specific header, the status line) that land exactly
     *  where drawColumnLines actually put the text, instead of a caller guessing. */
    public static final int LINE_HEIGHT = 18;

    public static void drawColumnLines(Graphics2D g, String[] lines, int x, int startY, int imgHeight, EvidenceColorScheme cs, boolean isRequest) {
        int lineHeight = LINE_HEIGHT;
        int maxLines = Math.max(0, (imgHeight - 10 - startY) / lineHeight);
        boolean truncated = lines.length > maxLines;
        int linesToDraw = truncated ? Math.max(0, maxLines - 1) : lines.length;

        int curY = startY;
        Color[] lastColor = new Color[1];
        for (int i = 0; i < linesToDraw; i++) {
            drawLine(g, lines[i], x, curY, cs, isRequest, lastColor);
            curY += lineHeight;
        }

        if (truncated) {
            g.setColor(cs.dim());
            g.setFont(new Font(Font.SANS_SERIF, Font.ITALIC, 12));
            g.drawString("··· (" + (lines.length - linesToDraw) + " more lines truncated) ···", x, curY);
            g.setFont(MONO_FONT);
        }
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
    public static void drawLine(Graphics2D g, String line, int x, int y, EvidenceColorScheme cs, boolean isRequest, Color[] lastValueColor) {
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

    public static String projectNameSuffix(MontoyaApi api, ModuleConfig config) {
        if (!config.getBool("evidence.include_project_name", true)) return "";
        String projectName = api.project().name();
        if (projectName == null || projectName.isBlank()) return "";
        return "  ·  " + projectName;
    }

    public static int extractStatusCode(String statusLine) {
        String[] parts = statusLine.trim().split("\\s+");
        if (parts.length >= 2) {
            try { return Integer.parseInt(parts[1]); } catch (NumberFormatException ignored) {}
        }
        return 0;
    }

    public static Color colorForJsonValue(String val, Color strCol, Color numCol, Color defaultCol) {
        if (val.isEmpty()) return defaultCol;
        String clean = val.endsWith(",") ? val.substring(0, val.length() - 1).trim() : val.trim();
        if (clean.startsWith("\""))  return strCol;
        if ("true".equals(clean) || "false".equals(clean) || "null".equals(clean)) return numCol;
        try { Double.parseDouble(clean); return numCol; } catch (NumberFormatException ignored) {}
        return defaultCol;
    }

    public static String truncate(String text, Graphics2D g, int maxWidth) {
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
    public static int maxCharsForColumnWidth(int imgWidth) {
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
    public static String wrapEvidenceText(String text, int maxLineLength) {
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

    public static String formatBody(MontoyaApi api, byte[] body, String contentType) {
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
            Object[] options = {"Hex Dump", "Keep Original", "Truncate"};
            int choice = JOptionPane.showOptionDialog(api.userInterface().swingUtils().suiteFrame(),
                    "This payload appears to be binary. How should it be formatted?",
                    "Binary Payload Detected", JOptionPane.DEFAULT_OPTION,
                    JOptionPane.QUESTION_MESSAGE, null, options, options[0]);

            if (choice == 0) {
                return "\n--- BINARY PAYLOAD (HEX DUMP) ---\n" + toHexDump(body);
            } else if (choice == 1) {
                String text = new String(body, java.nio.charset.StandardCharsets.UTF_8);
                return "\n" + JsonParser.formatJsonString(text);
            } else if (choice == 2) {
                int limit = Math.min(body.length, BINARY_TRUNCATE_BYTES);
                byte[] truncated = Arrays.copyOf(body, limit);
                String suffix = limit < body.length
                        ? "\n... [truncated, showing " + limit + " of " + body.length + " bytes]"
                        : "";
                return "\n--- BINARY PAYLOAD (HEX DUMP, TRUNCATED) ---\n" + toHexDump(truncated) + suffix;
            }
            // Dialog dismissed without a choice — same safe default as before (omit).
            return "\n[Binary Payload Omitted]";
        } else {
            String text = new String(body, java.nio.charset.StandardCharsets.UTF_8);
            return "\n" + JsonParser.formatJsonString(text);
        }
    }

    public static String toHexDump(byte[] data) {
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
}
