package icarus.evidence;

import burp.api.montoya.MontoyaApi;
import icarus.core.Finding;
import icarus.core.ModuleConfig;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

public final class RateLimitTableRenderer {

    public static BufferedImage renderRateLimitTable(MontoyaApi api, ModuleConfig config, Finding finding, boolean force1080) {
        int imgWidth = force1080 ? 1920 : 1200;
        int imgHeight = force1080 ? 1080 : 1200; // Gave it a bit more default vertical space to comfortably fit headers/body

        EvidenceColorScheme cs = EvidenceColorScheme.get(config.getString("evidence.colorscheme", "Minimal Dark"));

        // Built once here (not inside drawRateLimitTable) because formatBody() can pop a
        // confirmation dialog for binary bodies — drawRateLimitTable retries itself with a
        // taller canvas when content doesn't fit, and recomputing there would re-prompt the
        // user for the same body on every retry.
        String[] fullReqRes = buildFullReqRes(api, finding, imgWidth);

        return drawRateLimitTable(api, config, finding, imgWidth, imgHeight, cs, !force1080, fullReqRes[0], fullReqRes[1]);
    }

    public static String[] buildFullReqRes(MontoyaApi api, Finding finding, int imgWidth) {
        var rr = finding.evidence();
        String reqContentType = rr.request().headerValue("Content-Type");
        String reqLine = rr.request().method() + " " + rr.request().path() + " HTTP/" + rr.request().httpVersion();

        String fullReq = reqLine + "\n" + rr.request().headers().stream()
                .map(h -> h.name() + ": " + h.value() + "\n")
                .reduce("", String::concat) + EvidenceImageRenderer.formatBody(api, rr.request().body().getBytes(), reqContentType);

        String fullRes = "";
        if (rr.response() != null) {
            String resContentType = rr.response().headerValue("Content-Type");
            String statusLine = rr.response().httpVersion() + " " + rr.response().statusCode() + " " + rr.response().reasonPhrase() + "\n";
            fullRes = statusLine + rr.response().headers().stream()
                    .map(h -> h.name() + ": " + h.value() + "\n")
                    .reduce("", String::concat) + EvidenceImageRenderer.formatBody(api, rr.response().body().getBytes(), resContentType);
        }

        // imgWidth is already fixed at this call site, so wrap tightly against the real
        // column budget instead of the conservative narrower-layout guess used above.
        int wrapWidth = EvidenceImageRenderer.maxCharsForColumnWidth(imgWidth);
        fullReq = EvidenceImageRenderer.wrapEvidenceText(fullReq, wrapWidth);
        fullRes = EvidenceImageRenderer.wrapEvidenceText(fullRes, wrapWidth);

        return new String[]{fullReq, fullRes};
    }

    public static BufferedImage drawRateLimitTable(MontoyaApi api, ModuleConfig config, Finding finding, int imgWidth, int imgHeight, EvidenceColorScheme cs,
                                              boolean allowGrow, String fullReq, String fullRes) {
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
        g.drawString("ICARUS EVIDENCE  ·  " + finding.type() + "  ·  " + finding.path() + EvidenceImageRenderer.projectNameSuffix(api, config), 20, 30);

        String startTime = finding.metadata().getOrDefault("start_time", "");
        String endTime = finding.metadata().getOrDefault("end_time", "");
        String timeStr = (!startTime.isEmpty() && !endTime.isEmpty()) ? "  |  [" + startTime + " to " + endTime + "]" : "";

        g.setColor(cs.dim());
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 13));
        String baseStr = finding.description() + timeStr;
        g.drawString(baseStr, 20, 55);

        String rps = finding.metadata().get("rps");
        if (rps != null && !rps.isBlank()) {
            int baseWidth = g.getFontMetrics().stringWidth(baseStr);
            g.setColor(cs.dim());
            g.drawString("  |  ", 20 + baseWidth, 55);
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
            g.drawString(rps, 20 + baseWidth + pipeWidth, 55);
        }

        String logStr = finding.metadata().get("blast_log");
        String[] entries = logStr.split(";");

        int y = 110;
        g.setFont(EvidenceImageRenderer.MONO_FONT);

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
            g.drawString(EvidenceImageRenderer.truncate(reqLine, g, maxReqWidth), 80, y);

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
        g.setFont(EvidenceImageRenderer.MONO_FONT);

        int reqLines = fullReq.split("\n").length;
        int resLines = fullRes.split("\n").length;
        int maxLines = Math.max(reqLines, resLines);

        int requiredHeight = y + (maxLines * 18) + 40;
        if (allowGrow && requiredHeight > imgHeight) {
            g.dispose();
            return drawRateLimitTable(api, config, finding, imgWidth, requiredHeight, cs, false, fullReq, fullRes);
        }

        Shape clipBackup = g.getClip();

        int clipY = y - 20;

        // Request
        g.setClip(0, clipY, imgWidth / 2 - 5, imgHeight - clipY);
        int rawReqY = y;
        Color[] rawReqLastColor = new Color[1];
        for (String line : fullReq.split("\n")) {
            EvidenceImageRenderer.drawLine(g, line, 20, rawReqY, cs, true, rawReqLastColor);
            rawReqY += 18;
        }

        // Response
        g.setClip(imgWidth / 2 + 5, clipY, imgWidth / 2 - 5, imgHeight - clipY);
        int rawResY = y;
        Color[] rawResLastColor = new Color[1];
        for (String line : fullRes.split("\n")) {
            EvidenceImageRenderer.drawLine(g, line, imgWidth / 2 + 20, rawResY, cs, false, rawResLastColor);
            rawResY += 18;
        }

        g.setClip(clipBackup);

        g.dispose();
        return img;
    }
}
