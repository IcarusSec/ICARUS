package icarus.evidence;

import burp.api.montoya.MontoyaApi;

import icarus.core.Finding;
import icarus.core.ModuleConfig;
import icarus.core.ReportTemplateConfig;
import icarus.evidence.EvidenceCapture.CapturedEvidence;

import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * Generates a standalone HTML report linking all captured findings
 * with inline or relative PNG images.
 */
public final class ReportGenerator {

    private final MontoyaApi api;
    private final CweRepository cweRepository = new CweRepository();
    private final Parser markdownParser = Parser.builder().build();
    private final HtmlRenderer markdownRenderer = HtmlRenderer.builder().build();

    public ReportGenerator(MontoyaApi api) {
        this.api = api;
    }

    /**
     * @param outputHtmlFile where to write the report, chosen/confirmed by the caller
     *                       (e.g. via a save dialog). Every referenced evidence image is
     *                       copied alongside it so the relative &lt;img&gt; links resolve
     *                       regardless of where the images were originally saved.
     * @return true if a report file was actually written, false if generation was skipped
     *         (report disabled in settings, or no findings to report).
     */
    public boolean generate(List<Finding> findings, ModuleConfig config, EvidenceCapture capture, Path outputHtmlFile) throws IOException {
        if (!config.getBool("evidence.html_report", true) || findings.isEmpty()) {
            return false;
        }

        // Screenshots are grouped by Finding#similarityHash(), not identity — re-editing a
        // finding's evidence builds a new Finding instance with the same hash, so identity
        // matching used to silently drop every prior screenshot for that finding.
        var evidenceByHash = capture.groupedBySimilarityHash();

        Path reportDir = outputHtmlFile.toAbsolutePath().getParent();
        Files.createDirectories(reportDir);

        for (Finding f : findings) {
            for (var c : evidenceByHash.getOrDefault(f.similarityHash(), List.of())) {
                Path src = c.imagePath().toAbsolutePath().normalize();
                Path dest = reportDir.resolve(c.imagePath().getFileName()).normalize();
                if (!src.equals(dest)) {
                    try {
                        Files.copy(src, dest, StandardCopyOption.REPLACE_EXISTING);
                    } catch (IOException e) {
                        api.logging().logToError("Failed to copy evidence image " + src.getFileName() + " into report directory: " + e);
                    }
                }
            }
        }

        ReportTemplateConfig rtc = ReportTemplateConfig.fromConfig(config);
        String projectName = rtc.variables().get("projectName");
        if (projectName == null || projectName.isBlank()) {
            projectName = null;
            if (config.getBool("evidence.include_project_name", true)) {
                String name = api.project().name();
                if (name != null && !name.isBlank()) projectName = name;
            }
        }
        // One persistent "Retest Mode" toggle (Evidence Manager) drives both the per-finding
        // status dropdown there and this report profile — a separate one-shot "Generate Retest
        // Report" export-dialog checkbox would just be a second control for the same decision,
        // since there's no existing modal export options dialog to add it to.
        boolean retest = config.getBool("retest.enabled", false);

        String html;
        try {
            StringBuilder sb = new StringBuilder();
            appendHeader(sb, reportDir.getFileName().toString(), projectName, rtc);
            if (rtc.tocEnabled()) appendToc(sb, rtc, findings, retest);
            boolean findingsPlaced = appendSections(sb, rtc, retest, findings, evidenceByHash, config);
            if (!findingsPlaced) {
                appendSummary(sb, findings);
                appendFindings(sb, findings, evidenceByHash, config, retest);
            }
            appendFooter(sb);
            html = sb.toString();
        } catch (RuntimeException e) {
            api.logging().logToError("Styled report render failed, falling back to raw dump: " + e);
            html = rawFallback(findings);
        }

        Files.writeString(outputHtmlFile, html);
        api.logging().logToOutput("HTML Report generated at: " + outputHtmlFile.toAbsolutePath());
        return true;
    }

    /** Last-resort export so a render bug never costs the tester their findings data. */
    private String rawFallback(List<Finding> findings) {
        StringBuilder sb = new StringBuilder("<!DOCTYPE html><html><body><pre>\n");
        for (Finding f : findings) {
            sb.append(escapeHtml(f.toString())).append("\n\n");
        }
        sb.append("</pre></body></html>");
        return sb.toString();
    }

    private void appendHeader(StringBuilder html, String reportName, String projectName, ReportTemplateConfig rtc) {
        String accent = rtc.primaryColor() != null ? rtc.primaryColor() : "#3e7bb8";
        String accent2 = rtc.secondaryColor() != null ? rtc.secondaryColor() : "#6e6e6e";
        boolean dark = "dark".equals(rtc.themeName());
        String bg = dark ? "#1a1a1a" : "#ffffff";
        String cardBg = dark ? "#262626" : "#f7f7f7";
        String text = dark ? "#e8e8e8" : "#1a1a1a";
        String textMuted = dark ? "#a0a0a0" : "#666666";
        String border = dark ? "#3a3a3a" : "#dddddd";

        StringBuilder detailRows = new StringBuilder();
        appendMetaRow(detailRows, "Project", projectName);
        appendMetaRow(detailRows, "Author", rtc.variables().get("author"));
        appendMetaRow(detailRows, "Revisor", rtc.variables().get("revisor"));
        appendMetaRow(detailRows, "Ambient", rtc.variables().get("ambient"));
        appendMetaRow(detailRows, "Date", rtc.variables().get("reportDate"));
        String detailsTable = detailRows.isEmpty() ? "" : "<table class=\"meta-table\">" + detailRows + "</table>";

        html.append("""
            <!DOCTYPE html>
            <html lang="en">
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <title>ICARUS Security Report</title>
                <style>
                    :root {
                        --bg: %s;
                        --card-bg: %s;
                        --text: %s;
                        --text-muted: %s;
                        --border: %s;
                        --accent: %s;
                        --accent2: %s;
                        --critical: #cc2e2e;
                        --high: #d9711f;
                        --medium: #b38f00;
                        --low: #2f7a77;
                        --info: #6e6e6e;
                    }
                    body {
                        font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif;
                        background-color: var(--bg);
                        color: var(--text);
                        line-height: 1.6;
                        margin: 0;
                        padding: 2rem;
                    }
                    h1, h2, h3 { color: var(--text); }
                    .header {
                        border-bottom: 2px solid var(--border);
                        padding-bottom: 1rem;
                        margin-bottom: 2rem;
                    }
                    .exec-summary {
                        background: var(--card-bg);
                        border: 1px solid var(--border);
                        border-left: 4px solid var(--accent);
                        border-radius: 6px;
                        padding: 1rem 1.5rem;
                        margin-bottom: 2rem;
                    }
                    .exec-summary h2 { margin-top: 0; }
                    .exec-summary p { margin-bottom: 0; }
                    .summary {
                        display: flex;
                        gap: 1rem;
                        margin-bottom: 2rem;
                    }
                    .stat-box {
                        background: var(--card-bg);
                        padding: 1rem 2rem;
                        border-radius: 6px;
                        border: 1px solid var(--border);
                        text-align: center;
                    }
                    .stat-num { font-size: 2rem; font-weight: bold; }
                    .stat-label { font-size: 0.9rem; color: var(--text-muted); }
                    .finding-card {
                        background: var(--card-bg);
                        border-radius: 8px;
                        border: 1px solid var(--border);
                        margin-bottom: 2rem;
                        overflow: hidden;
                    }
                    .finding-header {
                        padding: 1rem 1.5rem;
                        border-bottom: 1px solid var(--border);
                        display: flex;
                        justify-content: space-between;
                        align-items: center;
                    }
                    .finding-title { font-size: 1.25rem; font-weight: bold; margin: 0; }
                    .badge {
                        padding: 0.25rem 0.75rem;
                        border-radius: 20px;
                        font-size: 0.85rem;
                        font-weight: 600;
                        color: #ffffff;
                    }
                    .badge.CRITICAL { background-color: var(--critical); }
                    .badge.HIGH { background-color: var(--high); }
                    .badge.MEDIUM { background-color: var(--medium); }
                    .badge.LOW { background-color: var(--low); }
                    .badge.INFO { background-color: var(--info); }

                    .finding-body { padding: 1.5rem; }
                    .meta-table {
                        width: 100%%;
                        border-collapse: collapse;
                        margin-bottom: 1.5rem;
                        font-size: 0.95rem;
                    }
                    .meta-table th, .meta-table td {
                        padding: 0.5rem;
                        border-bottom: 1px solid var(--border);
                        text-align: left;
                    }
                    .meta-table th { color: var(--text-muted); width: 120px; font-weight: normal; }
                    .evidence-block { margin-top: 2rem; }
                    .evidence-img {
                        max-width: 100%%;
                        border: 1px solid var(--border);
                        border-radius: 4px;
                    }
                    .evidence-caption {
                        margin-top: 0.75rem;
                        padding-left: 1rem;
                        border-left: 3px solid var(--accent);
                        color: var(--text-muted);
                    }
                    .toc { background: var(--card-bg); border: 1px solid var(--border); border-radius: 6px; padding: 1rem 1.5rem; margin-bottom: 2rem; }
                    .toc ul { margin: 0.5rem 0 0; padding-left: 1.25rem; }
                    .toc a { color: var(--accent); text-decoration: none; }
                    .toc a:hover { text-decoration: underline; }
                </style>
                %s
            </head>
            <body>
                <div class="header">
                    <h1>ICARUS Security Report</h1>
                    <p style="color: var(--text-muted)">Generated on: %s | Report ID: %s</p>
                    %s
                </div>
            """.formatted(
                bg, cardBg, text, textMuted, border,
                accent, accent2,
                customCssBlock(rtc.customCssPath()),
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")),
                reportName,
                detailsTable
            ));
    }

    /** Appends a {@code <tr>} for {@code label}/{@code value} unless {@code value} is blank. */
    private void appendMetaRow(StringBuilder html, String label, String value) {
        if (value == null || value.isBlank()) return;
        html.append("<tr><th>").append(escapeHtml(label)).append("</th><td>").append(escapeHtml(value)).append("</td></tr>");
    }

    /** Reads a user-supplied CSS file (Settings → Reporting → Theme) and wraps it in its own
     *  &lt;style&gt; block placed after the base one, so its rules override by cascade order.
     *  Missing/unreadable files are skipped silently — this is a nice-to-have override, not
     *  something that should ever break report generation. */
    private String customCssBlock(String customCssPath) {
        if (customCssPath == null || customCssPath.isBlank()) return "";
        try {
            String css = Files.readString(Path.of(customCssPath));
            return "<style>\n" + css + "\n</style>";
        } catch (IOException | RuntimeException e) {
            api.logging().logToError("Failed to read custom report CSS at " + customCssPath + ": " + e);
            return "";
        }
    }

    /** Renders the configured report sections (Settings → Reporting) as Markdown, in order,
     *  with {{variable}} interpolation. Empty if the user has no sections configured.
     *  A section titled {@link ReportTemplateConfig#FINDINGS_MARKER} renders the Findings
     *  block (summary + finding cards) in its place instead of Markdown content.
     *  @return true if the Findings block was rendered here (caller must not append it again). */
    private boolean appendSections(StringBuilder html, ReportTemplateConfig rtc, boolean retest,
                                    List<Finding> findings, Map<String, List<CapturedEvidence>> evidenceByHash, ModuleConfig config) {
        int i = 0;
        boolean findingsPlaced = false;
        for (ReportTemplateConfig.Section section : rtc.sections()) {
            if (retest && rtc.retestSuppressedSections().contains(section.title())) continue;
            i++;
            if (section.title().equalsIgnoreCase(ReportTemplateConfig.FINDINGS_MARKER)) {
                html.append("<div id=\"section-").append(i).append("\">\n");
                appendSummary(html, findings);
                appendFindings(html, findings, evidenceByHash, config, retest);
                html.append("</div>\n");
                findingsPlaced = true;
                continue;
            }
            String bodyHtml = markdownRenderer.render(markdownParser.parse(rtc.interpolate(section.content())));
            html.append("""
                    <div class="exec-summary" id="section-%d">
                        <h2>%s</h2>
                        %s
                    </div>
                """.formatted(i, escapeHtml(section.title()), bodyHtml));
        }
        return findingsPlaced;
    }

    private void appendToc(StringBuilder html, ReportTemplateConfig rtc, List<Finding> findings, boolean retest) {
        html.append("<div class=\"toc\"><strong>Contents</strong><ul>\n");
        int i = 0;
        for (ReportTemplateConfig.Section section : rtc.sections()) {
            if (retest && rtc.retestSuppressedSections().contains(section.title())) continue;
            i++;
            html.append("<li><a href=\"#section-").append(i).append("\">")
                .append(escapeHtml(section.title())).append("</a></li>\n");
        }
        int idx = 1;
        for (Finding f : findings) {
            html.append("<li><a href=\"#finding-").append(idx).append("\">#")
                .append(idx).append(". ").append(escapeHtml(f.type())).append("</a></li>\n");
            idx++;
        }
        html.append("</ul></div>\n");
    }

    private void appendSummary(StringBuilder html, List<Finding> findings) {
        long critical = countBySeverity(findings, "CRITICAL");
        long high = countBySeverity(findings, "HIGH");
        long medium = countBySeverity(findings, "MEDIUM");
        long low = countBySeverity(findings, "LOW");

        html.append("""
                <div class="summary">
                    <div class="stat-box" style="border-bottom: 3px solid var(--critical)">
                        <div class="stat-num">%d</div>
                        <div class="stat-label">CRITICAL</div>
                    </div>
                    <div class="stat-box" style="border-bottom: 3px solid var(--high)">
                        <div class="stat-num">%d</div>
                        <div class="stat-label">HIGH</div>
                    </div>
                    <div class="stat-box" style="border-bottom: 3px solid var(--medium)">
                        <div class="stat-num">%d</div>
                        <div class="stat-label">MEDIUM</div>
                    </div>
                    <div class="stat-box" style="border-bottom: 3px solid var(--low)">
                        <div class="stat-num">%d</div>
                        <div class="stat-label">LOW/INFO</div>
                    </div>
                    <div class="stat-box" style="border-bottom: 3px solid var(--accent)">
                        <div class="stat-num">%d</div>
                        <div class="stat-label">TOTAL FINDINGS</div>
                    </div>
                </div>
                <h2>Findings</h2>
            """.formatted(critical, high, medium, low, findings.size()));
    }

    /** Shared with {@link PdfReportGenerator} so both formats count severities identically. */
    static long countBySeverity(List<Finding> findings, String severity) {
        if (severity.equals("LOW")) {
            return findings.stream().filter(f ->
                f.severity().name().equals("LOW") ||
                f.severity().name().equals("INFO")).count();
        }
        return findings.stream().filter(f -> f.severity().name().equals(severity)).count();
    }

    private void appendFindings(StringBuilder html, List<Finding> findings, Map<String, List<CapturedEvidence>> evidenceByHash, ModuleConfig config, boolean retest) {
        int index = 1;
        for (Finding f : findings) {
            html.append("""
                <div class="finding-card" id="finding-%d">
                    <div class="finding-header">
                        <h3 class="finding-title">#%d. %s</h3>
                        <span class="badge %s">%s</span>
                    </div>
                    <div class="finding-body">
                        <table class="meta-table">
                            <tr><th>Module</th><td>%s</td></tr>
                            <tr><th>Category</th><td>%s</td></tr>
                            <tr><th>Target Path</th><td><code>%s</code></td></tr>
                            <tr><th>Description</th><td>%s</td></tr>
            """.formatted(
                index, index++, escapeHtml(f.type()),
                f.severity().name(), f.severity().name(),
                f.module(), f.category().name(),
                escapeHtml(f.path()), escapeHtml(f.description())
            ));

            if (!f.cweIds().isEmpty()) {
                String cweLabels = f.cweIds().stream()
                        .map(id -> {
                            var cwe = cweRepository.byId(id);
                            return cwe != null ? cwe.label() : id;
                        })
                        .collect(java.util.stream.Collectors.joining(", "));
                html.append("                        <tr><th>CWE</th><td>")
                    .append(escapeHtml(cweLabels))
                    .append("</td></tr>\n");
            }

            if (!f.metadata().isEmpty()) {
                for (var meta : f.metadata().entrySet()) {
                    html.append("""
                            <tr><th>%s</th><td><code>%s</code></td></tr>
                    """.formatted(escapeHtml(meta.getKey()), escapeHtml(meta.getValue())));
                }
            }

            if (retest) {
                String status = config.getString("retest.status." + f.similarityHash(), "");
                if (!status.isBlank()) {
                    html.append("                        <tr><th>Retest Status</th><td><strong>")
                        .append(escapeHtml(status))
                        .append("</strong></td></tr>\n");
                }
            }

            List<CapturedEvidence> group = evidenceByHash.getOrDefault(f.similarityHash(), List.of());
            if (!group.isEmpty()) {
                html.append("                        </table>\n                        <h4>Evidence</h4>\n");
                int evidenceIndex = 1;
                for (CapturedEvidence c : group) {
                    html.append("""
                            <div class="evidence-block">
                                <img class="evidence-img" src="%s" alt="Evidence for %s">
                    """.formatted(c.imagePath().getFileName().toString(), escapeHtml(f.type())));
                    String caption = c.caption() == null ? "" : c.caption();
                    html.append("                                <div class=\"evidence-caption\">")
                        .append(evidenceIndex).append(". ").append(escapeHtml(caption))
                        .append("</div>\n");
                    html.append("                            </div>\n");
                    evidenceIndex++;
                }
                html.append("                    </div>\n                </div>\n");
            } else {
                html.append("""
                        </table>
                        <p style="color: var(--text-muted)">No screenshot captured for this finding.</p>
                    </div>
                </div>
            """);
            }
        }
    }

    private void appendFooter(StringBuilder html) {
        html.append("""
            </body>
            </html>
            """);
    }

    private String escapeHtml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;")
                   .replace("<", "&lt;")
                   .replace(">", "&gt;")
                   .replace("\"", "&quot;")
                   .replace("'", "&#39;");
    }
}
