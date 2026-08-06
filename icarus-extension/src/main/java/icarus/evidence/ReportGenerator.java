package icarus.evidence;

import burp.api.montoya.MontoyaApi;

import icarus.core.Finding;
import icarus.core.ModuleConfig;
import icarus.evidence.EvidenceCapture.CapturedEvidence;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Generates a standalone HTML report linking all captured findings
 * with inline or relative PNG images.
 */
public final class ReportGenerator {

    private final MontoyaApi api;
    private final CweRepository cweRepository = new CweRepository();

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

        // Screenshots are optional per finding — captured by identity, since
        // captureInteractive() is always called with the same Finding reference
        // that's already in `findings` (never a copy).
        var evidenceByFinding = new IdentityHashMap<Finding, CapturedEvidence>();
        for (var c : capture.getCaptured()) {
            evidenceByFinding.put(c.finding(), c);
        }

        Path reportDir = outputHtmlFile.toAbsolutePath().getParent();
        Files.createDirectories(reportDir);

        for (Finding f : findings) {
            var c = evidenceByFinding.get(f);
            if (c == null) continue;
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

        String projectName = null;
        if (config.getBool("evidence.include_project_name", true)) {
            String name = api.project().name();
            if (name != null && !name.isBlank()) projectName = name;
        }

        String html;
        try {
            StringBuilder sb = new StringBuilder();
            appendHeader(sb, reportDir.getFileName().toString(), projectName);
            appendExecutiveSummary(sb, config.getString("evidence.executive_summary", ""));
            appendSummary(sb, findings);
            appendFindings(sb, findings, evidenceByFinding);
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

    private void appendHeader(StringBuilder html, String reportName, String projectName) {
        html.append("""
            <!DOCTYPE html>
            <html lang="en">
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <title>ICARUS Security Report</title>
                <style>
                    :root {
                        --bg: #1e1e1e;
                        --card-bg: #2d2d2d;
                        --text: #e0e0e0;
                        --text-muted: #aaaaaa;
                        --border: #444444;
                        --accent: #5e9dd9;
                        --critical: #ff4d4d;
                        --high: #ff8c42;
                        --medium: #f9c74f;
                        --low: #4d908e;
                        --info: #858585;
                    }
                    body {
                        font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif;
                        background-color: var(--bg);
                        color: var(--text);
                        line-height: 1.6;
                        margin: 0;
                        padding: 2rem;
                    }
                    h1, h2, h3 { color: #ffffff; }
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
                        color: #1a1a1a;
                    }
                    .badge.CRITICAL { background-color: var(--critical); }
                    .badge.HIGH { background-color: var(--high); }
                    .badge.MEDIUM { background-color: var(--medium); }
                    .badge.LOW { background-color: var(--low); }
                    .badge.INFO { background-color: var(--info); color: white; }

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
                    .evidence-img {
                        max-width: 100%%;
                        border: 1px solid var(--border);
                        border-radius: 4px;
                    }
                </style>
            </head>
            <body>
                <div class="header">
                    <h1>ICARUS Security Report</h1>
                    <p style="color: var(--text-muted)">Generated on: %s | Report ID: %s%s</p>
                </div>
            """.formatted(
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")),
                reportName,
                projectName != null ? " | Project: " + escapeHtml(projectName) : ""
            ));
    }

    /** Free-text scope/methodology/takeaway a client reads before the finding list. Omitted entirely if blank. */
    private void appendExecutiveSummary(StringBuilder html, String executiveSummary) {
        if (executiveSummary == null || executiveSummary.isBlank()) return;
        html.append("""
                <div class="exec-summary">
                    <h2>Executive Summary</h2>
                    <p>%s</p>
                </div>
            """.formatted(escapeHtml(executiveSummary).replace("\n", "<br>")));
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

    private void appendFindings(StringBuilder html, List<Finding> findings, Map<Finding, CapturedEvidence> evidenceByFinding) {
        int index = 1;
        for (Finding f : findings) {
            html.append("""
                <div class="finding-card">
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
                index++, escapeHtml(f.type()),
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

            var c = evidenceByFinding.get(f);
            if (c != null) {
                html.append("""
                        </table>
                        <h4>Evidence</h4>
                        <img class="evidence-img" src="%s" alt="Evidence for %s">
                    </div>
                </div>
            """.formatted(c.imagePath().getFileName().toString(), f.type()));
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
