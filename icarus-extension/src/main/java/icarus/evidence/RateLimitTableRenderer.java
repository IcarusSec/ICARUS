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

public class RateLimitTableRenderer {
    private final EvidenceCapture capture;
    private final MontoyaApi api;
    private final ModuleConfig config;

    public RateLimitTableRenderer(EvidenceCapture capture, MontoyaApi api, ModuleConfig config) {
        this.capture = capture;
        this.api = api;
        this.config = config;
    }

public BufferedImage renderRateLimitTable(Finding finding, boolean force1080) {
        return renderRateLimitTable(finding, force1080, null);
    }

    /**
     * @param outAnchors optional — if given, filled with the pixel {@link Rectangle} of each
     *                   dynamically-positioned element actually drawn ("rps", "blocked"), keyed
     *                   by name. Font-metric-dependent positions (e.g. the RPS badge's x offset,
     *                   which depends on the preceding description text's rendered width) can't
     *                   be predicted by a caller ahead of time; this lets an MCP client target an
     *                   annotation at "the RPS badge" by name instead of guessing pixel coordinates.
     */
    public BufferedImage renderRateLimitTable(Finding finding, boolean force1080, Map<String, Rectangle> outAnchors) {
        int imgWidth = force1080 ? 1920 : 1200;
        int imgHeight = force1080 ? 1080 : 1200; // Gave it a bit more default vertical space to comfortably fit headers/body

        EvidenceColorScheme cs = EvidenceColorScheme.get(config.getString("evidence.colorscheme", "Minimal Dark"));

        // Built once here (not inside drawRateLimitTable) because capture.imageRenderer.formatBody() can pop a
        // confirmation dialog for binary bodies — drawRateLimitTable retries itself with a
        // taller canvas when content doesn't fit, and recomputing there would re-prompt the
        // user for the same body on every retry.
        String[] fullReqRes = capture.tableRenderer.buildFullReqRes(finding, imgWidth);

        return drawRateLimitTable(finding, imgWidth, imgHeight, cs, !force1080, fullReqRes[0], fullReqRes[1], outAnchors);
    }

public String[] buildFullReqRes(Finding finding, int imgWidth) {
        var rr = finding.evidence();
        String reqContentType = rr.request().headerValue("Content-Type");
        String reqLine = rr.request().method() + " " + rr.request().path() + " HTTP/" + rr.request().httpVersion();

        String fullReq = reqLine + "\n" + rr.request().headers().stream()
                .map(h -> h.name() + ": " + h.value() + "\n")
                .reduce("", String::concat) + capture.imageRenderer.formatBody(rr.request().body().getBytes(), reqContentType);

        String fullRes = "";
        if (rr.response() != null) {
            String resContentType = rr.response().headerValue("Content-Type");
            String statusLine = rr.response().httpVersion() + " " + rr.response().statusCode() + " " + rr.response().reasonPhrase() + "\n";
            fullRes = statusLine + rr.response().headers().stream()
                    .map(h -> h.name() + ": " + h.value() + "\n")
                    .reduce("", String::concat) + capture.imageRenderer.formatBody(rr.response().body().getBytes(), resContentType);
        }

        // imgWidth is already fixed at this call site, so wrap tightly against the real
        // column budget instead of the conservative narrower-layout guess used above.
        int wrapWidth = capture.phase1Dialog.maxCharsForColumnWidth(imgWidth);
        fullReq = capture.phase1Dialog.wrapEvidenceText(fullReq, wrapWidth);
        fullRes = capture.phase1Dialog.wrapEvidenceText(fullRes, wrapWidth);

        return new String[]{fullReq, fullRes};
    }

public BufferedImage drawRateLimitTable(Finding finding, int imgWidth, int imgHeight, EvidenceColorScheme cs,
                                              boolean allowGrow, String fullReq, String fullRes) {
        return drawRateLimitTable(finding, imgWidth, imgHeight, cs, allowGrow, fullReq, fullRes, null);
    }

public BufferedImage drawRateLimitTable(Finding finding, int imgWidth, int imgHeight, EvidenceColorScheme cs,
                                              boolean allowGrow, String fullReq, String fullRes, Map<String, Rectangle> outAnchors) {
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

        int titleX = capture.imageRenderer.drawHeaderLogo(g, 70);
        g.setColor(cs.titleText());
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 18));
        g.drawString("ICARUS EVIDENCE  ·  " + finding.type() + "  ·  " + finding.path() + capture.imageRenderer.projectNameSuffix(), titleX, 30);

        String startTime = finding.metadata().getOrDefault("start_time", "");
        String endTime = finding.metadata().getOrDefault("end_time", "");
        String timeStr = (!startTime.isEmpty() && !endTime.isEmpty()) ? "  |  [" + startTime + " to " + endTime + "]" : "";

        // --- DRAW METADATA BANNER IN HEADER ---
        g.setColor(cs.dim());
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 13));
        String baseStr = finding.description() + timeStr;
        g.drawString(baseStr, titleX, 55);

        String rps = finding.metadata().get("rps");
        if (rps != null && !rps.isBlank()) {
            int baseWidth = g.getFontMetrics().stringWidth(baseStr);
            g.setColor(cs.dim());
            g.drawString("  |  ", titleX + baseWidth, 55);
            int pipeWidth = g.getFontMetrics().stringWidth("  |  ");
            
            Color rpsColor = cs.dim();
            try {
                String numericPart = rps.replaceAll("[^0-9.]", "");
                double rpsValue = Double.parseDouble(numericPart);
                if (rpsValue >= 50) rpsColor = cs.status5xx();
                else if (rpsValue >= 15) rpsColor = cs.status3xx();
                else rpsColor = cs.status2xx();
            } catch (Exception ignored) {}
            
            g.setColor(rpsColor);
            int rpsX = titleX + baseWidth + pipeWidth;
            g.drawString(rps, rpsX, 55);
            if (outAnchors != null) {
                FontMetrics fm = g.getFontMetrics();
                outAnchors.put("rps", new Rectangle(rpsX, 55 - fm.getAscent(), fm.stringWidth(rps), fm.getAscent() + fm.getDescent()));
            }
        }

        // Card Colors
        Color cardColor = new Color(
            Math.min(255, cs.background().getRed() + 8),
            Math.min(255, cs.background().getGreen() + 10),
            Math.min(255, cs.background().getBlue() + 12)
        );

        int padding = 20;
        int cardMargin = 24;
        
        String logStr = finding.metadata().getOrDefault("blast_log", "");
        String[] entries = logStr.isBlank() ? new String[0] : logStr.split(";");
        int total = entries.length;

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

        int tableRowsHeight = rowsToShow.size() * 20;
        
        int bypassHeight = 0;
        String bypassLog = finding.metadata().get("bypass_log");
        if (bypassLog != null && !bypassLog.isBlank()) {
            bypassHeight = 40 + (bypassLog.split("\n").length * 20);
        }

        int topCardY = 85;
        // Adjusted topCardHeight to accurately contain the table headers (40px)
        int topCardHeight = 40 + tableRowsHeight + bypassHeight + cardMargin * 2;
        
        int bottomCardsY = topCardY + topCardHeight + padding;
        
        int reqLinesCount = fullReq.split("\n").length;
        int resLinesCount = fullRes.split("\n").length;
        int maxLines = Math.max(reqLinesCount, resLinesCount);
        int reqResHeight = maxLines * 24 + 50; 
        int requiredBottomHeight = reqResHeight + cardMargin * 2;
        int requiredHeight = bottomCardsY + requiredBottomHeight + padding;
        
        if (allowGrow && requiredHeight > imgHeight) {
            g.dispose();
            return drawRateLimitTable(finding, imgWidth, requiredHeight, cs, false, fullReq, fullRes, outAnchors);
        }
        
        // After potential growth, exactly bound the cards to the image bounds
        int bottomCardsHeight = imgHeight - bottomCardsY - padding;

        // --- DRAW TOP CARD ---
        int topCardWidth = imgWidth - (padding * 2);
        g.setColor(cardColor);
        g.fillRoundRect(padding, topCardY, topCardWidth, topCardHeight, 12, 12);
        g.setColor(cs.divider());
        g.drawRoundRect(padding, topCardY, topCardWidth, topCardHeight, 12, 12);
        
        int y = topCardY + cardMargin + 10;
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12));
        g.setColor(cs.dim());
        g.drawString("#", padding + cardMargin + 8, y);
        g.drawString("REQUEST", padding + cardMargin + 60, y);
        g.drawString("RESPONSE", imgWidth / 2, y);
        g.drawString("LATENCY", imgWidth - padding - cardMargin - 150, y);
        
        y += 10;
        g.setColor(cs.divider());
        g.drawLine(padding + cardMargin, y, imgWidth - padding - cardMargin, y);
        y += 20;
        
        g.setFont(EvidenceCapture.MONO_FONT);
        var rr = finding.evidence();
        String reqLineStr = "";
        if (rr != null && rr.request() != null) {
            reqLineStr = rr.request().method() + " " + rr.request().path() + " HTTP/" + rr.request().httpVersion();
        }

        if (total > 0) {
            for (int i : rowsToShow) {
                if (i == -1) {
                    g.setColor(new Color(cs.dim().getRed(), cs.dim().getGreen(), cs.dim().getBlue(), 150));
                    g.setFont(new Font(Font.MONOSPACED, Font.ITALIC, 14));
                    g.drawString("    ···  (omitted similar requests)  ···", padding + cardMargin + 60, y);
                    g.setFont(EvidenceCapture.MONO_FONT);
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
                g.drawString(idxStr, padding + cardMargin, y);

                int maxReqWidth = (imgWidth / 2) - 100;
                String truncatedReq = capture.phase1Dialog.truncate(reqLineStr, g, maxReqWidth);
                
                int firstSpace = truncatedReq.indexOf(' ');
                if (firstSpace > 0) {
                    String method = truncatedReq.substring(0, firstSpace);
                    String rest = truncatedReq.substring(firstSpace);
                    
                    g.setFont(EvidenceCapture.BOLD_FONT);
                    g.setColor(capture.imageRenderer.colorForMethod(method, cs));
                    
                    int rx = padding + cardMargin + 60;
                    g.drawString(method, rx, y);
                    rx += g.getFontMetrics().stringWidth(method);
                    
                    g.setFont(EvidenceCapture.MONO_FONT);
                    g.setColor(cs.text());
                    g.drawString(rest, rx, y);
                } else {
                    g.setColor(cs.text());
                    g.drawString(truncatedReq, padding + cardMargin + 60, y);
                }

                int resX = imgWidth / 2;
                g.setColor(cs.text());
                g.drawString("HTTP/1.1 ", resX, y);
                resX += g.getFontMetrics().stringWidth("HTTP/1.1 ");
                g.setFont(EvidenceCapture.BOLD_FONT);
                g.setColor(cs.statusColor(status));
                g.drawString(String.valueOf(status), resX, y);
                g.setFont(EvidenceCapture.MONO_FONT);

                g.setColor(cs.dim());
                g.drawString(ms, imgWidth - padding - cardMargin - 150, y);

                if (i == flipIdx) {
                    g.setColor(cs.status5xx());
                    String blocked = " ← BLOCKED";
                    int blockedX = imgWidth - padding - cardMargin - 80;
                    g.drawString(blocked, blockedX, y);
                    if (outAnchors != null) {
                        FontMetrics fm = g.getFontMetrics();
                        outAnchors.put("blocked", new Rectangle(blockedX, y - fm.getAscent(), fm.stringWidth(blocked), fm.getAscent() + fm.getDescent()));
                    }
                }
                y += 20;
            }
        }
        
        if (bypassLog != null && !bypassLog.isBlank()) {
            y += 20;
            g.setColor(cs.divider());
            g.drawLine(padding + cardMargin, y, imgWidth - padding - cardMargin, y);
            y += 20;

            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12));
            g.setColor(cs.headerKey());
            g.drawString("BYPASS TESTS", padding + cardMargin, y);
            y += 20;
            
            g.setFont(EvidenceCapture.MONO_FONT);
            for (String line : bypassLog.split("\n")) {
                if (line.isBlank()) continue;
                if (line.startsWith("✓")) g.setColor(cs.status2xx());
                else g.setColor(cs.status5xx());
                g.drawString(line.substring(0, 1), padding + cardMargin, y);

                g.setColor(cs.text());
                g.drawString(line.substring(1), padding + cardMargin + 15, y);
                y += 20;
            }
        }
        
        // --- DRAW BOTTOM CARDS ---
        int cardWidth = (imgWidth / 2) - (padding * 3 / 2);
        int reqCardX = padding;
        int resCardX = imgWidth / 2 + padding / 2;
        
        g.setColor(cardColor);
        g.fillRoundRect(reqCardX, bottomCardsY, cardWidth, bottomCardsHeight, 12, 12);
        g.fillRoundRect(resCardX, bottomCardsY, cardWidth, bottomCardsHeight, 12, 12);
        
        g.setColor(cs.divider());
        g.drawRoundRect(reqCardX, bottomCardsY, cardWidth, bottomCardsHeight, 12, 12);
        g.drawRoundRect(resCardX, bottomCardsY, cardWidth, bottomCardsHeight, 12, 12);
        
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 14));
        g.setColor(cs.headerKey());
        g.drawString("BASE REQUEST", reqCardX + cardMargin, bottomCardsY + 28);
        g.drawString(noLimit ? "RESPONSE" : "BLOCK RESPONSE", resCardX + cardMargin, bottomCardsY + 28);
        
        g.setFont(EvidenceCapture.MONO_FONT);
        int textStartY = bottomCardsY + 54;
        
        Shape clipBackup = g.getClip();
        
        g.setClip(reqCardX, bottomCardsY, cardWidth, bottomCardsHeight);
        int rawReqY = textStartY;
        Color[] rawReqLastColor = new Color[1];
        for (String line : fullReq.split("\n")) {
            if (rawReqY + 24 > bottomCardsY + bottomCardsHeight - 10) {
                g.setColor(new Color(cs.dim().getRed(), cs.dim().getGreen(), cs.dim().getBlue(), 150));
                g.setFont(new Font(Font.MONOSPACED, Font.ITALIC, 14));
                g.drawString("    ···", reqCardX + cardMargin, rawReqY);
                break;
            }
            capture.imageRenderer.drawLine(g, line, reqCardX + cardMargin, rawReqY, cs, true, rawReqLastColor);
            rawReqY += 24;
        }

        g.setClip(resCardX, bottomCardsY, cardWidth, bottomCardsHeight);
        int rawResY = textStartY;
        Color[] rawResLastColor = new Color[1];
        for (String line : fullRes.split("\n")) {
            if (rawResY + 24 > bottomCardsY + bottomCardsHeight - 10) {
                g.setColor(new Color(cs.dim().getRed(), cs.dim().getGreen(), cs.dim().getBlue(), 150));
                g.setFont(new Font(Font.MONOSPACED, Font.ITALIC, 14));
                g.drawString("    ···", resCardX + cardMargin, rawResY);
                break;
            }
            capture.imageRenderer.drawLine(g, line, resCardX + cardMargin, rawResY, cs, false, rawResLastColor);
            rawResY += 24;
        }

        g.setClip(clipBackup);
        g.dispose();
        return img;
    }
}
