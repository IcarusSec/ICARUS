package icarus.evidence;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.message.HttpRequestResponse;

import icarus.core.Category;
import icarus.core.Finding;
import icarus.core.ModuleConfig;
import icarus.core.Severity;

import java.awt.Rectangle;
import java.util.Map;

/**
 * Renders an evidence image straight from a {@link Finding}'s own captured traffic — no
 * screenshot, no user interaction. Originally built for the MCP {@code capture_evidence} tool
 * (a headless client can't take a screenshot), and reused by {@link ReportGenerator}/
 * {@link PdfReportGenerator} to auto-fill an image for any reportable finding that has no
 * manually-captured evidence, rather than leaving a "no screenshot captured" placeholder.
 */
public final class EvidenceAutoRenderer {

    private EvidenceAutoRenderer() {}

    /** Renders with the finding's own title/description/severity and real captured traffic — no overrides. */
    public static java.awt.image.BufferedImage render(MontoyaApi api, ModuleConfig config, Finding finding, boolean force1080) {
        return render(api, config, finding, finding.type(), finding.description(), finding.severity().name(), null, null, force1080, null);
    }

    /**
     * @param title/description/severity override the rendered banner; null/blank falls back to the finding's own.
     * @param requestTextOverride/responseTextOverride override the traffic text rendered; null uses the finding's real captured request/response.
     * @param outAnchors optional — filled with the pixel {@link Rectangle} of anything dynamically positioned
     *                   (see {@link RateLimitTableRenderer}), so a caller can target an annotation by name
     *                   instead of guessing coordinates it has no way to predict.
     */
    public static java.awt.image.BufferedImage render(MontoyaApi api, ModuleConfig config, Finding finding,
                                                        String title, String description, String severity,
                                                        String requestTextOverride, String responseTextOverride,
                                                        boolean force1080, Map<String, Rectangle> outAnchors) {
        String finalTitle = title != null && !title.isBlank() ? title : finding.type();
        String finalDescription = description != null && !description.isBlank() ? description : finding.description();
        String finalSeverity = severity != null && !severity.isBlank() ? severity : finding.severity().name();
        int imgWidth = force1080 ? 1920 : 1200;

        if (finding.category() == Category.RATE_LIMIT && finding.metadata().containsKey("blast_log")) {
            Finding.Builder builder = Finding.builder(finding.module(), finalTitle)
                    .description(finalDescription)
                    .severity(Severity.valueOf(finalSeverity))
                    .category(finding.category())
                    .path(finding.path())
                    .evidence(finding.evidence());
            finding.metadata().forEach(builder::meta);
            finding.cweIds().forEach(builder::cwe);
            return RateLimitTableRenderer.renderRateLimitTable(api, config, builder.build(), force1080, outAnchors);
        }

        int imgHeight = force1080 ? 1080 : 800;
        // Computed unconditionally (not just when the caller wants outAnchors) — used below to
        // pick a default annotation baked into every auto-rendered image, not only ones a caller
        // explicitly annotates. ReportGenerator/PdfReportGenerator call this via the 4-arg
        // overload (outAnchors=null) to silently fill in evidence for any reportable finding
        // nobody ran capture_evidence on — that path used to render completely unannotated raw
        // traffic, which is what kept showing up as "still not annotating anything" in reports.
        Map<String, Rectangle> anchors = new java.util.LinkedHashMap<>();
        anchors.put("request_column", new Rectangle(0, 70, imgWidth / 2 - 5, imgHeight - 70));
        anchors.put("response_column", new Rectangle(imgWidth / 2 + 5, 70, imgWidth / 2 - 5, imgHeight - 70));

        int wrapWidth = EvidenceImageRenderer.maxCharsForColumnWidth(imgWidth);
        String reqText = EvidenceImageRenderer.wrapEvidenceText(
                requestTextOverride != null ? requestTextOverride : requestText(api, finding.evidence()), wrapWidth);
        String resText = EvidenceImageRenderer.wrapEvidenceText(
                responseTextOverride != null ? responseTextOverride : responseText(api, finding.evidence()), wrapWidth);

        int startY = 90 + 22; // matches colLabelY + 22 in EvidenceImageRenderer.renderTextToImage
        int colWidth = imgWidth / 2 - 40;
        String[] reqLines = reqText.split("\n");
        String[] resLines = resText.split("\n");
        anchors.putAll(lineAnchors(reqLines, 20, startY, colWidth, "request"));
        anchors.putAll(lineAnchors(resLines, imgWidth / 2 + 20, startY, colWidth, "response"));

        String payload = finding.metadata().get("payload");
        if (payload != null && !payload.isBlank()) {
            Rectangle reqRect = findTextRect(reqLines, payload, 20, startY);
            if (reqRect != null) anchors.put("request_payload", reqRect);
            Rectangle resRect = findTextRect(resLines, payload, imgWidth / 2 + 20, startY);
            if (resRect != null) anchors.put("response_payload", resRect);
        }

        if (outAnchors != null) outAnchors.putAll(anchors);

        java.awt.image.BufferedImage rendered = EvidenceImageRenderer.renderTextToImage(api, config, reqText, resText, finalTitle, finalDescription, finalSeverity, force1080);
        EvidenceAnnotator.Annotation defaultAnnotation = pickDefaultAnnotation(finding, anchors);
        return defaultAnnotation == null ? rendered : EvidenceAnnotator.applyAnnotations(rendered, java.util.List.of(defaultAnnotation));
    }

    /**
     * Chooses one sensible default annotation so every auto-rendered image points at something
     * specific, even when nobody called capture_evidence to annotate it by hand — the report's
     * own silent auto-fill (ReportGenerator/PdfReportGenerator, for any reportable finding with
     * no manually-captured evidence) goes through this same render() and used to come out with
     * zero annotation at all. Priority: the finding's own payload (almost every injection type)
     * &gt; the specific header it's about (VERSION_DISCLOSURE and similar single-header findings,
     * parsed from "&lt;Header&gt; header ..." in the description) &gt; the header block as a whole
     * (MISSING_* — there's no single line to point at since the header is absent) &gt; the response
     * status line (SERVER_ERROR, to point at the 500 itself). Returns null when none apply (e.g.
     * a finding with no captured traffic at all), leaving the image unannotated rather than
     * boxing something arbitrary.
     */
    private static EvidenceAnnotator.Annotation pickDefaultAnnotation(Finding finding, Map<String, Rectangle> anchors) {
        Rectangle payloadRect = anchors.getOrDefault("response_payload", anchors.get("request_payload"));
        if (payloadRect != null) return toAnnotation("HIGHLIGHT", payloadRect);

        String headerName = headerNameFromDescription(finding.description());
        if (headerName != null) {
            Rectangle headerRect = anchors.getOrDefault("response_header:" + headerName, anchors.get("request_header:" + headerName));
            if (headerRect != null) return toAnnotation("HIGHLIGHT", headerRect);
        }

        if (finding.type() != null && finding.type().startsWith("MISSING_")) {
            Rectangle block = anchors.get("response_headers");
            if (block != null) return toAnnotation("BOX", block);
        }

        if ("SERVER_ERROR".equals(finding.type())) {
            Rectangle statusLine = anchors.get("response_status_line");
            if (statusLine != null) return toAnnotation("BOX", statusLine);
        }

        return null;
    }

    private static EvidenceAnnotator.Annotation toAnnotation(String kind, Rectangle r) {
        return new EvidenceAnnotator.Annotation(kind, r.x, r.y, r.width, r.height);
    }

    /** Pulls the header name out of a description shaped like "Server header contains version: ..."
     *  or "X-Frame-Options header is missing" — the phrasing SensitiveHeaderModule's findings use. */
    private static String headerNameFromDescription(String description) {
        if (description == null) return null;
        var m = java.util.regex.Pattern.compile("^([A-Za-z0-9-]+)\\s+header", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(description.trim());
        return m.find() ? m.group(1).toLowerCase() : null;
    }

    /**
     * Tight, per-line annotation targets — a single header's line, or the request/status line —
     * instead of the only prior option (box the entire half-image column). Built from the exact
     * wrapped lines drawColumnLines is about to draw, so the returned rectangles land precisely
     * on that text rather than an outside guess. Named "{prefix}_status_line", "{prefix}_headers"
     * (the whole header block, still far tighter than a full column) and "{prefix}_header:name"
     * per header actually present — mirrors the header-line heuristic in EvidenceImageRenderer's
     * own drawLine (no leading whitespace, contains ':', not JSON/structural).
     */
    private static Map<String, Rectangle> lineAnchors(String[] lines, int x, int startY, int colWidth, String prefix) {
        Map<String, Rectangle> anchors = new java.util.LinkedHashMap<>();
        if (lines.length == 0) return anchors;

        int lineHeight = EvidenceImageRenderer.LINE_HEIGHT;
        int topPad = 14; // roughly the font's ascent, so the box sits around the glyphs, not below them
        anchors.put(prefix + "_status_line", new Rectangle(x, startY - topPad, colWidth, lineHeight));

        int firstHeaderLine = -1, lastHeaderLine = -1;
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];
            String trimmed = line.trim();
            boolean noLeadingWhitespace = !line.isEmpty() && line.charAt(0) != ' ' && line.charAt(0) != '\t';
            boolean looksLikeHeader = noLeadingWhitespace && trimmed.contains(":") && !trimmed.startsWith("{")
                    && !trimmed.startsWith("}") && !trimmed.startsWith("[") && !trimmed.startsWith("]") && !trimmed.startsWith("\"");
            if (!looksLikeHeader) continue;

            if (firstHeaderLine == -1) firstHeaderLine = i;
            lastHeaderLine = i;
            String headerName = line.substring(0, line.indexOf(':')).trim().toLowerCase();
            anchors.putIfAbsent(prefix + "_header:" + headerName, new Rectangle(x, startY + i * lineHeight - topPad, colWidth, lineHeight));
        }
        if (firstHeaderLine != -1) {
            int top = startY + firstHeaderLine * lineHeight - topPad;
            int bottom = startY + (lastHeaderLine + 1) * lineHeight - topPad;
            anchors.put(prefix + "_headers", new Rectangle(x, top, colWidth, bottom - top));
        }
        return anchors;
    }

    /**
     * Locates the exact pixel rectangle of {@code needle} (the finding's own injected/reflected
     * payload) inside {@code lines} — the precise fix for "circle only the payload, not the whole
     * box it lives in". Uses the real font-advance width of the text before/within the match
     * (MONO_FONT — what drawLine actually draws JSON values in) rather than an assumed fixed
     * char width, so the box lands exactly on the glyphs regardless of font quirks. Returns null
     * if the payload isn't found verbatim (e.g. it was JSON-escaped, or landed on a wrapped line
     * boundary) — callers should fall back to a coarser anchor in that case.
     */
    private static Rectangle findTextRect(String[] lines, String needle, int x, int startY) {
        java.awt.image.BufferedImage probe = new java.awt.image.BufferedImage(1, 1, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D g = probe.createGraphics();
        g.setFont(EvidenceImageRenderer.MONO_FONT);
        java.awt.FontMetrics fm = g.getFontMetrics();
        try {
            for (int i = 0; i < lines.length; i++) {
                int idx = lines[i].indexOf(needle);
                if (idx < 0) continue;
                int rx = x + fm.stringWidth(lines[i].substring(0, idx));
                int rw = fm.stringWidth(needle);
                int ry = startY + i * EvidenceImageRenderer.LINE_HEIGHT - 14;
                return new Rectangle(rx, ry, Math.max(rw, 4), EvidenceImageRenderer.LINE_HEIGHT);
            }
            return null;
        } finally {
            g.dispose();
        }
    }

    /** Mirrors {@link EvidencePhase1Dialog}'s reqText build so headless and interactive renders start from identical text. */
    private static String requestText(MontoyaApi api, HttpRequestResponse rr) {
        String reqContentType = rr.request().headerValue("Content-Type");
        String reqLine = rr.request().method() + " " + rr.request().path() + " " + rr.request().httpVersion() + "\n";
        return reqLine + rr.request().headers().stream()
                .map(h -> h.name() + ": " + h.value() + "\n")
                .reduce("", String::concat) + EvidenceImageRenderer.formatBody(api, rr.request().body().getBytes(), reqContentType);
    }

    /** Mirrors {@link EvidencePhase1Dialog}'s resText build. Empty if there's no response. */
    private static String responseText(MontoyaApi api, HttpRequestResponse rr) {
        if (rr.response() == null) return "";
        String resContentType = rr.response().headerValue("Content-Type");
        String statusLine = rr.response().httpVersion() + " " + rr.response().statusCode() + " " + rr.response().reasonPhrase() + "\n";
        return statusLine + rr.response().headers().stream()
                .map(h -> h.name() + ": " + h.value() + "\n")
                .reduce("", String::concat) + EvidenceImageRenderer.formatBody(api, rr.response().body().getBytes(), resContentType);
    }
}
