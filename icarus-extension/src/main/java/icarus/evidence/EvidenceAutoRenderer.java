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
        if (outAnchors != null) {
            outAnchors.put("request_column", new Rectangle(0, 70, imgWidth / 2 - 5, imgHeight - 70));
            outAnchors.put("response_column", new Rectangle(imgWidth / 2 + 5, 70, imgWidth / 2 - 5, imgHeight - 70));
        }

        int wrapWidth = EvidenceImageRenderer.maxCharsForColumnWidth(imgWidth);
        String reqText = EvidenceImageRenderer.wrapEvidenceText(
                requestTextOverride != null ? requestTextOverride : requestText(api, finding.evidence()), wrapWidth);
        String resText = EvidenceImageRenderer.wrapEvidenceText(
                responseTextOverride != null ? responseTextOverride : responseText(api, finding.evidence()), wrapWidth);

        return EvidenceImageRenderer.renderTextToImage(api, config, reqText, resText, finalTitle, finalDescription, finalSeverity, force1080);
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
