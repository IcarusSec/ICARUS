package icarus.report;

import icarus.core.Severity;
import icarus.report.model.FindingField;
import icarus.report.model.ReportProfile;
import icarus.report.render.FindingView;
import icarus.report.render.HttpExcerpt;
import icarus.report.render.ReportData;
import icarus.report.render.ReportRenderContext;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Generates sample preview documents (PDF or HTML) for any ReportProfile without needing live scan findings.
 */
public final class PreviewService {

    public enum Format { PDF, HTML }

    private PreviewService() {}

    public static ReportData createFixtureReportData() {
        List<FindingView> findings = new ArrayList<>();

        Map<FindingField, String> f1Fields = new EnumMap<>(FindingField.class);
        f1Fields.put(FindingField.DESCRIPTION, "SQL Injection was identified in the `id` query parameter of `/api/v1/users`.\n\nAn unauthenticated attacker can manipulate SQL queries to extract sensitive database records.");
        f1Fields.put(FindingField.IMPACT, "Full database read access including password hashes and confidential user personal data.");
        f1Fields.put(FindingField.REMEDIATION, "Use parameterized queries (Prepared Statements) or an ORM with proper input binding.");

        findings.add(new FindingView(
            "hash-preview-1",
            1,
            "SQL Injection in /api/v1/users",
            Severity.CRITICAL,
            "INJECTION",
            "SqlInjectionScanner",
            "/api/v1/users?id=1' OR '1'='1",
            List.of("CWE-89"),
            "Improper Neutralization of Special Elements used in an SQL Command ('SQL Injection')",
            f1Fields,
            Collections.emptyList(),
            new HttpExcerpt("GET /api/v1/users?id=1'%20OR%20'1'='1 HTTP/1.1\nHost: example.com\nUser-Agent: Mozilla/5.0\n\n", false, 82),
            new HttpExcerpt("HTTP/1.1 200 OK\nContent-Type: application/json\n\n[{\"id\":1,\"admin\":true,\"username\":\"root\"}]", false, 88),
            false
        ));

        Map<FindingField, String> f2Fields = new EnumMap<>(FindingField.class);
        f2Fields.put(FindingField.DESCRIPTION, "Cross-Site Scripting (Reflected) was discovered in the `q` parameter of the search endpoint.");
        f2Fields.put(FindingField.IMPACT, "Execution of arbitrary JavaScript in the context of the victim's browser session.");
        f2Fields.put(FindingField.REMEDIATION, "Context-aware HTML entity encoding on all user-supplied output.");

        findings.add(new FindingView(
            "hash-preview-2",
            2,
            "Reflected XSS in Search Query",
            Severity.MEDIUM,
            "XSS",
            "XssScanner",
            "/search?q=<script>alert(1)</script>",
            List.of("CWE-79"),
            "Improper Neutralization of Input During Web Page Generation ('Cross-site Scripting')",
            f2Fields,
            Collections.emptyList(),
            new HttpExcerpt("GET /search?q=%3Cscript%3Ealert(1)%3C/script%3E HTTP/1.1\nHost: example.com\n\n", false, 72),
            new HttpExcerpt("HTTP/1.1 200 OK\nContent-Type: text/html\n\n<html><body>Results for <script>alert(1)</script></body></html>", false, 95),
            false
        ));

        Map<FindingField, String> f3Fields = new EnumMap<>(FindingField.class);
        f3Fields.put(FindingField.DESCRIPTION, "The server returns detailed software banner headers (`Server: Apache/2.4.51`).");
        f3Fields.put(FindingField.REMEDIATION, "Configure web server to suppress verbose version banners (`ServerTokens Prod`).");

        findings.add(new FindingView(
            "hash-preview-3",
            3,
            "Information Disclosure (Server Banner)",
            Severity.INFO,
            "INFO_DISCLOSURE",
            "HeaderScanner",
            "/",
            List.of("CWE-200"),
            "Exposure of Sensitive Information to an Unauthorized Actor",
            f3Fields,
            Collections.emptyList(),
            new HttpExcerpt("GET / HTTP/1.1\nHost: example.com\n\n", false, 32),
            new HttpExcerpt("HTTP/1.1 200 OK\nServer: Apache/2.4.51\n\n", false, 38),
            false
        ));

        Map<Severity, Long> sevCounts = new LinkedHashMap<>();
        sevCounts.put(Severity.CRITICAL, 1L);
        sevCounts.put(Severity.HIGH, 0L);
        sevCounts.put(Severity.MEDIUM, 1L);
        sevCounts.put(Severity.LOW, 0L);
        sevCounts.put(Severity.INFO, 1L);

        Map<String, String> vars = new LinkedHashMap<>();
        vars.put("finding_count", "3");
        vars.put("project", "Acme Production App");
        vars.put("report_title", "Security Assessment Report");
        vars.put("author", "Security Team");
        vars.put("reviewer", "Lead Assessor");
        vars.put("classification", "Confidencial");
        vars.put("environment", "Production");

        return new ReportData(
            "Security Assessment Report",
            "Acme Production App",
            findings,
            sevCounts,
            0,
            0,
            vars,
            false
        );
    }

    public static File generatePreviewFile(ReportProfile profile, Format format) throws IOException {
        Path tempDir = Files.createTempDirectory("icarus-preview");
        ReportData data = createFixtureReportData();
        String locTag = profile.locale() != null ? profile.locale() : "pt-BR";
        Locale locale = Locale.forLanguageTag(locTag.replace('_', '-'));
        ReportRenderContext ctx = new ReportRenderContext(profile, data, locale, tempDir);

        if (format == Format.PDF) {
            Path pdfFile = tempDir.resolve("preview-report.pdf");
            icarus.evidence.PdfReportGenerator pdfGen = new icarus.evidence.PdfReportGenerator(null);
            pdfGen.generate(ctx, pdfFile);
            return pdfFile.toFile();
        } else {
            Path htmlFile = tempDir.resolve("preview-report.html");
            icarus.evidence.ReportGenerator htmlGen = new icarus.evidence.ReportGenerator(null);
            htmlGen.generate(ctx, htmlFile);
            return htmlFile.toFile();
        }
    }
}
