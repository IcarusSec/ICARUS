package icarus.evidence;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.*;
import java.util.List;
import javax.swing.*;
import burp.api.montoya.MontoyaApi;
import icarus.core.*;
import icarus.ui.*;
import java.awt.event.*;
import java.awt.datatransfer.*;
import java.io.*;
import java.nio.file.*;
import javax.imageio.*;
import javax.swing.border.*;
import javax.swing.event.*;
import javax.swing.text.*;

public class EvidenceImageRenderer {
    private final EvidenceCapture capture;
    private final MontoyaApi api;
    private final ModuleConfig config;

    public EvidenceImageRenderer(EvidenceCapture capture, MontoyaApi api, ModuleConfig config) {
        this.capture = capture;
        this.api = api;
        this.config = config;
    }

    /** Hard ceiling on a rendered evidence image's height, in pixels. A BufferedImage is
     *  {@code width * height * 4} bytes fully in heap — an unbounded req/res body (e.g. a
     *  minified JSON blob that {@code formatBody} explodes to hundreds of thousands of lines)
     *  otherwise asks for a multi-GB allocation and takes Burp's whole JVM down with an
     *  OutOfMemoryError. ~1200px of width * 30000px caps one image near ~140MB; the column /
     *  card drawing code already clips overflow and draws a "···" marker, so a clamped image
     *  just shows the first ~1200 lines instead of crashing. */
    public static final int MAX_IMAGE_HEIGHT = 30000;

    private static final Color FIXED_COLOR = new Color(0x2f, 0x9e, 0x44);
    private static final Color NOT_FIXED_COLOR = new Color(0xe0, 0x3e, 0x3e);

    /** FIXED/NOT_FIXED (Retest checkbox outcomes) get their own color in the evidence header's
     *  severity token; every other severity uses the color scheme's dim/muted text color. */
    public static Color severityTokenColor(String severity, EvidenceColorScheme cs) {
        return "FIXED".equals(severity) ? FIXED_COLOR
                : "NOT_FIXED".equals(severity) ? NOT_FIXED_COLOR
                : cs.dim();
    }

public BufferedImage renderTextToImage(String req, String res, String title, String desc, String severity, boolean force1080) {
        int imgWidth = 1920;

        // Split once and reuse for both the height calculation and drawing, so the two
        // never disagree on line count.
        String[] reqLines = req.split("\n");
        String[] resLines = res.split("\n");

        int colLabelY = 90;
        int y = colLabelY + 22;

        int contentLines = Math.max(reqLines.length, resLines.length);
        int calculatedHeight = (85 + 54) + contentLines * LINE_HEIGHT + 20;
        
        int imgHeight;
        if (force1080) {
            imgHeight = 1080;
        } else {
            imgHeight = Math.min(MAX_IMAGE_HEIGHT, Math.max(300, calculatedHeight));
        }

        EvidenceColorScheme cs = EvidenceColorScheme.get(config.getString("evidence.colorscheme", "Catppuccin"));

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

        int titleX = capture.imageRenderer.drawHeaderLogo(g, 70);
        g.setColor(cs.titleText());
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 18));
        g.drawString("ICARUS  ·  " + title + capture.imageRenderer.projectNameSuffix(), titleX, 30);

        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 13));
        g.setColor(severityTokenColor(severity, cs));
        g.drawString(severity, titleX, 55);
        int severityWidth = g.getFontMetrics().stringWidth(severity);

        g.setColor(cs.dim());
        g.drawString("  ·  " + desc, titleX + severityWidth, 55);

        // Card-based UI metrics
        int padding = 20;
        int cardY = 85;
        int cardHeight = imgHeight - cardY - padding;
        int cardWidth = (imgWidth / 2) - (padding * 3 / 2); // 3 paddings total: left, middle, right

        int reqCardX = padding;
        int resCardX = imgWidth / 2 + padding / 2;

        // Draw Card Backgrounds (slightly lighter than canvas)
        Color cardColor = new Color(
            Math.min(255, cs.background().getRed() + 8),
            Math.min(255, cs.background().getGreen() + 10),
            Math.min(255, cs.background().getBlue() + 12)
        );
        g.setColor(cardColor);
        g.fillRoundRect(reqCardX, cardY, cardWidth, cardHeight, 12, 12);
        g.fillRoundRect(resCardX, cardY, cardWidth, cardHeight, 12, 12);

        // Subtle Borders instead of harsh center divider
        g.setColor(cs.divider());
        g.drawRoundRect(reqCardX, cardY, cardWidth, cardHeight, 12, 12);
        g.drawRoundRect(resCardX, cardY, cardWidth, cardHeight, 12, 12);

        // Column labels (differentiated visual hierarchy)
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 14));
        g.setColor(cs.headerKey()); // Accent color for section titles
        g.drawString("REQUEST", reqCardX + 24, cardY + 28);
        g.drawString("RESPONSE", resCardX + 24, cardY + 28);

        g.setFont(EvidenceCapture.MONO_FONT);
        int textStartY = cardY + 54;

        // Clip columns
        Shape originalClip = g.getClip();

        // Request (left)
        g.setClip(reqCardX, cardY, cardWidth, cardHeight);
        capture.imageRenderer.drawColumnLines(g, reqLines, reqCardX + 24, textStartY, imgHeight, cs, true);

        // Response (right)
        g.setClip(resCardX, cardY, cardWidth, cardHeight);
        capture.imageRenderer.drawColumnLines(g, resLines, resCardX + 24, textStartY, imgHeight, cs, false);

        g.setClip(originalClip);
        g.dispose();
        return img;
    }

    /** Renders a block of free text (e.g. sqlmap / nuclei / curl output the agent ran to confirm a
     *  finding) into a single-column evidence image, styled like {@link #renderTextToImage} so it
     *  sits next to the auto-rendered traffic shots without looking foreign. Monospace, no syntax
     *  colouring, overflow clipped with the same "··· more lines truncated ···" marker. */
    public BufferedImage renderCodeToImage(String code, String title) {
        int imgWidth = 1600;
        String[] lines = (code == null ? "" : code).replace("\t", "    ").split("\n", -1);

        int cardY = 85;
        int padding = 20;
        int calculatedHeight = cardY + 54 + lines.length * LINE_HEIGHT + padding;
        int imgHeight = Math.min(MAX_IMAGE_HEIGHT, Math.max(300, calculatedHeight));

        EvidenceColorScheme cs = EvidenceColorScheme.get(config.getString("evidence.colorscheme", "Catppuccin"));

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

        int titleX = drawHeaderLogo(g, 70);
        g.setColor(cs.titleText());
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 18));
        g.drawString("ICARUS  ·  " + (title == null || title.isBlank() ? "External Tool Output" : title)
                + projectNameSuffix(), titleX, 42);

        int cardWidth = imgWidth - padding * 2;
        int cardHeight = imgHeight - cardY - padding;
        Color cardColor = new Color(
                Math.min(255, cs.background().getRed() + 8),
                Math.min(255, cs.background().getGreen() + 10),
                Math.min(255, cs.background().getBlue() + 12));
        g.setColor(cardColor);
        g.fillRoundRect(padding, cardY, cardWidth, cardHeight, 12, 12);
        g.setColor(cs.divider());
        g.drawRoundRect(padding, cardY, cardWidth, cardHeight, 12, 12);

        Shape originalClip = g.getClip();
        g.setClip(padding, cardY, cardWidth, cardHeight);
        g.setFont(EvidenceCapture.MONO_FONT);
        int maxLines = (cardHeight - 54) / LINE_HEIGHT;
        int toDraw = Math.min(lines.length, Math.max(0, maxLines));
        int curY = cardY + 54;
        for (int i = 0; i < toDraw; i++) {
            g.setColor(cs.text());
            g.drawString(lines[i], padding + 24, curY);
            curY += LINE_HEIGHT;
        }
        if (toDraw < lines.length) {
            g.setColor(cs.dim());
            g.drawString("··· (" + (lines.length - toDraw) + " more lines truncated) ···", padding + 24, curY);
        }
        g.setClip(originalClip);
        g.dispose();
        return img;
    }

    /** Pixel height of one drawn line — shared with {@link EvidenceAutoRenderer} so it can compute
     *  tight per-line annotation anchors (a specific header, the status line) that land exactly
     *  where drawColumnLines actually put the text, instead of a caller guessing. */
    public static final int LINE_HEIGHT = 24;

public void drawColumnLines(Graphics2D g, String[] lines, int x, int startY, int imgHeight, EvidenceColorScheme cs, boolean isRequest) {
        int lineHeight = LINE_HEIGHT; // Increased for better readability
        int maxLines = Math.max(0, (imgHeight - 10 - startY) / lineHeight);
        boolean truncated = lines.length > maxLines;
        int linesToDraw = truncated ? Math.max(0, maxLines - 1) : lines.length;

        int curY = startY;
        Color[] lastColor = new Color[1];
        for (int i = 0; i < linesToDraw; i++) {
            capture.imageRenderer.drawLine(g, lines[i], x, curY, cs, isRequest, lastColor);
            curY += lineHeight;
        }

        if (truncated) {
            g.setColor(cs.dim());
            g.setFont(new Font(Font.SANS_SERIF, Font.ITALIC, 12));
            g.drawString("··· (" + (lines.length - linesToDraw) + " more lines truncated) ···", x, curY);
            g.setFont(EvidenceCapture.MONO_FONT);
        }
    }

public void drawLine(Graphics2D g, String line, int x, int y, EvidenceColorScheme cs, boolean isRequest, Color[] lastValueColor) {
        String trimmed = line.trim();

        // Request line: GET /path HTTP/1.1
        // (Checking without trailing space handles edge case where the line wrapper 
        // aggressively wraps the URL onto the next line, leaving the method isolated)
        if (isRequest && (trimmed.startsWith("GET") || trimmed.startsWith("POST") || trimmed.startsWith("PUT") ||
            trimmed.startsWith("DELETE") || trimmed.startsWith("PATCH") || trimmed.startsWith("HEAD") ||
            trimmed.startsWith("OPTIONS") || trimmed.startsWith("TRACE") || trimmed.startsWith("CONNECT"))) {
            
            int firstSpace = line.indexOf(' ');
            String method = firstSpace > 0 ? line.substring(0, firstSpace) : line;
            String rest = firstSpace > 0 ? line.substring(firstSpace) : "";
            
            g.setFont(EvidenceCapture.BOLD_FONT);
            
            // Color-code HTTP methods dynamically
            g.setColor(capture.imageRenderer.colorForMethod(method, cs));
            g.drawString(method, x, y);
            
            if (!rest.isEmpty()) {
                int methodWidth = g.getFontMetrics().stringWidth(method);
                g.setColor(cs.text());
                g.drawString(rest, x + methodWidth, y);
            }
            
            g.setFont(EvidenceCapture.MONO_FONT);
            lastValueColor[0] = null;
            return;
        }

        // Status line: HTTP/1.1 200 OK
        if (!isRequest && trimmed.startsWith("HTTP/")) {
            g.setFont(EvidenceCapture.BOLD_FONT);
            int statusCode = capture.imageRenderer.extractStatusCode(trimmed);
            
            // Restore status code colors (2xx green, etc.) as requested
            String[] parts = line.split(" ", 2);
            if (parts.length >= 2) {
                g.setColor(cs.text()); // HTTP/1.1 remains White
                g.drawString(parts[0] + " ", x, y);
                int offset = g.getFontMetrics().stringWidth(parts[0] + " ");
                g.setColor(cs.statusColor(statusCode)); // Colorize the status code!
                g.drawString(parts[1], x + offset, y);
            } else {
                g.setColor(cs.text());
                g.drawString(line, x, y);
            }
            g.setFont(EvidenceCapture.MONO_FONT);
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
            
            g.setFont(EvidenceCapture.BOLD_FONT);
            g.setColor(cs.headerKey());
            g.drawString(key, x, y);
            int keyWidth = g.getFontMetrics().stringWidth(key);
            g.setFont(EvidenceCapture.MONO_FONT);
            
            if (key.trim().equalsIgnoreCase("Host:")) {
                g.setColor(new Color(216, 180, 254)); // Distinct Lilac/Purple color for Host
            } else {
                g.setColor(cs.text());
            }
            g.drawString(val, x + keyWidth, y);
            lastValueColor[0] = g.getColor();
            return;
        }

        // JSON-ish lines
        if (trimmed.startsWith("\"") && trimmed.contains(":")) {
            int colonIdx = trimmed.indexOf(':');
            String rawKey = trimmed.substring(0, colonIdx + 1);
            String rawVal = trimmed.substring(colonIdx + 1).trim();

            int indent = line.indexOf(trimmed.charAt(0));
            String prefix = indent > 0 ? line.substring(0, indent) : "";

            g.setFont(EvidenceCapture.BOLD_FONT);
            g.setColor(cs.jsonKey());
            g.drawString(prefix + rawKey, x, y);
            int keyWidth = g.getFontMetrics().stringWidth(prefix + rawKey + " ");
            g.setFont(EvidenceCapture.MONO_FONT);

            Color valueColor = capture.imageRenderer.colorForJsonValue(rawVal, cs.jsonString(), cs.jsonNumber(), cs.text());
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

public String projectNameSuffix() {
        if (!config.getBool("evidence.include_project_name", true)) return "";
        String projectName = api.project().name();
        if (projectName == null || projectName.isBlank()) return "";
        return "  ·  " + projectName;
    }

public int extractStatusCode(String statusLine) {
        String[] parts = statusLine.trim().split("\\s+");
        if (parts.length >= 2) {
            try { return Integer.parseInt(parts[1]); } catch (NumberFormatException ignored) {}
        }
        return 0;
    }

public Color colorForJsonValue(String val, Color strCol, Color numCol, Color defaultCol) {
        if (val.isEmpty()) return defaultCol;
        String clean = val.endsWith(",") ? val.substring(0, val.length() - 1).trim() : val.trim();
        if (clean.startsWith("\""))  return strCol;
        if ("true".equals(clean) || "false".equals(clean) || "null".equals(clean)) return numCol;
        try { Double.parseDouble(clean); return numCol; } catch (NumberFormatException ignored) {}
        return defaultCol;
    }

public Color colorForMethod(String method, EvidenceColorScheme cs) {
        return switch (method.toUpperCase()) {
            case "GET" -> cs.status3xx();                             // Info blue
            case "POST" -> cs.status2xx();                            // Success green
            case "PUT" -> new Color(251, 146, 60);                    // Orange
            case "PATCH" -> new Color(192, 132, 252);                 // Purple
            case "DELETE" -> cs.status5xx();                          // Error red
            case "OPTIONS", "HEAD" -> new Color(156, 163, 175);       // Gray
            default -> cs.status4xx();                                // Warning yellow
        };
    }

public String formatBody(byte[] body, String contentType) {
        if (body == null || body.length == 0) return "";
        // Render the body verbatim (decoded UTF-8, pretty-printed if it's JSON), capped by
        // line count. No binary sniffing / "Hex Dump vs Keep Original" prompt — that popped a
        // modal dialog mid-render (hanging headless/MCP report generation) and its only
        // sensible outcome was this path anyway. capLines still bounds a pathological body.
        String text = new String(body, java.nio.charset.StandardCharsets.UTF_8);
        return "\n" + capLines(JsonParser.formatJsonString(text));
    }

    /** formatJsonString pretty-prints one token per line, so a large minified JSON body
     *  becomes hundreds of thousands of lines. Every consumer (evidence image height, the
     *  Phase-1 review text areas, report rendering) scales with line count, so cap it here
     *  at the shared chokepoint with a visible marker rather than downstream. */
    private static final int MAX_BODY_LINES = 2000;
    private static String capLines(String s) {
        int nl = 0, idx = -1;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == '\n' && ++nl == MAX_BODY_LINES) { idx = i; break; }
        }
        if (idx < 0) return s;
        int remaining = 0;
        for (int i = idx; i < s.length(); i++) if (s.charAt(i) == '\n') remaining++;
        return s.substring(0, idx) + "\n... [truncated, " + remaining + " more lines]";
    }

public String toHexDump(byte[] data) {
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

public static int drawHeaderLogo(Graphics2D g, int bannerHeight) {
        int textX = EvidenceCapture.HEADER_LOGO_CENTER_X + EvidenceCapture.HEADER_LOGO_SIZE / 2 + 8;
        if (EvidenceCapture.LOGO == null) return 20;
        // LOGO is already prescaled to exactly HEADER_LOGO_SIZE, so this is a 1:1 blit.
        int x = EvidenceCapture.HEADER_LOGO_CENTER_X - EvidenceCapture.HEADER_LOGO_SIZE / 2;
        int y = (bannerHeight - EvidenceCapture.HEADER_LOGO_SIZE) / 2;
        g.drawImage(EvidenceCapture.LOGO, x, y, null);
        return textX;
    }

public static BufferedImage loadScaledLogo(int targetSize) {
        BufferedImage src;
        try (var in = EvidenceCapture.class.getResourceAsStream("/icarus_logo.png")) {
            if (in == null) return null;
            src = ImageIO.read(in);
        } catch (IOException e) {
            return null;
        }
        // Source is ~1254x1254 → ~38px is a >30x reduction. Repeated bilinear halving softens
        // more with every pass and still aliases the circle edge ("crunchy and blurry at once").
        // AWT's SCALE_AREA_AVERAGING is a proper box-filter downscale — one pass, every source
        // pixel contributes, which is the right tool for a reduction this large. Slow, but this
        // runs once at class load.
        Image scaled = src.getScaledInstance(targetSize, targetSize, Image.SCALE_AREA_AVERAGING);
        BufferedImage out = new BufferedImage(targetSize, targetSize, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.drawImage(scaled, 0, 0, null);
        g.dispose();
        return out;
    }
}
