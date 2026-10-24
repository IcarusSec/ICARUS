package icarus.report.blocks;

import icarus.evidence.PdfReportGenerator;
import icarus.evidence.ReportGenerator;
import icarus.report.PreviewService;
import icarus.report.model.ReportProfile;
import icarus.report.model.ReportProfileCodec;
import icarus.report.model.SectionGraph;
import icarus.report.model.SectionNode;
import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.parser.PdfTextExtractor;
import icarus.report.render.ReportRenderContext;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Renders custom blocks against the sample Evidence Manager fixture and
 * checks that a profile from before custom blocks still renders.
 */
public class CustomBlockRenderTest {
    public static void main(String[] args) throws Exception {
        Path dir = args.length > 0 ? Path.of(args[0]) : Path.of("build_manual", "block-samples");
        Files.createDirectories(dir);

        ReportProfile legacy = ReportProfileCodec.fromStream(
            CustomBlockRenderTest.class.getResourceAsStream("/reports/profiles/executive-modern.json"));
        Path beforeHtml = dir.resolve("before.html");
        Path beforePdf = dir.resolve("before.pdf");
        render(legacy, beforeHtml, beforePdf);
        String before = Files.readString(beforeHtml);
        assert before.contains("Executive Summary") : "legacy title missing";
        assert !before.contains("icarus-chart") : "legacy report grew a chart";
        assert Files.size(beforePdf) > 1000 : "legacy pdf empty";

        ReportProfile profile = profileWithBlocks();
        Path afterHtml = dir.resolve("after.html");
        Path afterPdf = dir.resolve("after.pdf");
        render(profile, afterHtml, afterPdf);
        String after = Files.readString(afterHtml);
        assert after.contains("Findings by severity") : "chart title";
        assert after.contains("data-count=\"1\"") : "bound critical/medium/info count";
        assert after.contains("SQL Injection in /api/v1/users") : "findings table uses fixture data";
        assert after.contains("Retest the closed items") : "callout";
        assert after.contains("<rect") : "diagram shape";
        assert after.contains("<path d=\"M200.0 20.0") : "freehand stroke";
        assert after.contains("Unsupported block (hologram) omitted.") : "unknown block degrades";
        assert !after.contains("<script") : "diagram svg kept scripts out";
        assert after.contains("This block could not be read.") : "broken spec degrades";

        String pdfText = pdfText(afterPdf);
        assert pdfText.contains("SQL Injection in /api/v1/users") : "pdf table";
        assert pdfText.contains("Findings by severity") : "pdf chart title";
        assert pdfText.contains("Unsupported block (hologram) omitted.") : "pdf unknown block";
        assert pdfText.contains("This block could not be read.") : "pdf broken spec";
        assert pdfText.contains("Retest the closed items") : "pdf callout";
        assert Files.size(afterPdf) > 1000 : "block pdf empty";
        String legacyPdf = pdfText(beforePdf);
        assert legacyPdf.contains("Executive Summary") : "legacy pdf text";
        assert !legacyPdf.contains("Unsupported block") : "legacy pdf grew a custom block";

        System.out.println("[TEST] CustomBlockRender passed");
        System.out.println(" samples: " + dir.toAbsolutePath());
    }

    private static String pdfText(Path pdf) throws Exception {
        try (PdfReader reader = new PdfReader(pdf.toString())) {
            PdfTextExtractor extractor = new PdfTextExtractor(reader);
            StringBuilder text = new StringBuilder();
            for (int page = 1; page <= reader.getNumberOfPages(); page++) {
                text.append(extractor.getTextFromPage(page)).append('\n');
            }
            return text.toString();
        }
    }

    private static void render(ReportProfile profile, Path html, Path pdf) throws Exception {
        var ctx = new ReportRenderContext(profile, PreviewService.createFixtureReportData(), null, html.getParent());
        if (!new ReportGenerator(null).generate(ctx, html)) throw new AssertionError("html not written");
        if (!new PdfReportGenerator(null).generate(ctx, pdf)) throw new AssertionError("pdf not written");
    }

    private static ReportProfile profileWithBlocks() {
        String chart = "{\"type\":\"chart\",\"chart\":\"bar\",\"binding\":\"findingsBySeverity\",\"title\":\"Findings by severity\"}";
        String table = "{\"type\":\"table\",\"mode\":\"findings\",\"title\":\"Finding index\",\"columns\":[{\"key\":\"title\",\"label\":\"Finding\"},{\"key\":\"severity\",\"label\":\"Severity\"},{\"key\":\"path\",\"label\":\"Path\"}]}";
        String callout = "{\"type\":\"callout\",\"tone\":\"warning\",\"title\":\"Retest\",\"text\":\"Retest the closed items\"}";
        String drawing = "{\"type\":\"drawing\",\"width\":320,\"height\":120,\"shapes\":["
            + "{\"type\":\"rect\",\"x\":8,\"y\":8,\"w\":80,\"h\":40,\"fill\":\"#E8EEF8\",\"stroke\":\"#2563EB\"},"
            + "{\"type\":\"arrow\",\"x1\":100,\"y1\":28,\"x2\":180,\"y2\":28,\"stroke\":\"#1A1A1A\"},"
            + "{\"type\":\"freehand\",\"points\":[[200,20],[220,40],[250,18]],\"stroke\":\"#CC2E2E\"}"
            + "]}";
        List<SectionNode> nodes = List.of(
            SectionNode.of("EXECUTIVE_SUMMARY", true, 1, true, "EXECUTIVE_SUMMARY",
                Map.of("title", "Executive summary", "content", "Scope stays in the profile.")),
            SectionNode.of("block:chart", true, 2, false, "CUSTOM_BLOCK",
                Map.of("title", "Findings by severity", "blockType", "chart", "spec", chart)),
            SectionNode.of("block:table", true, 3, false, "CUSTOM_BLOCK",
                Map.of("title", "Finding index", "blockType", "table", "spec", table)),
            SectionNode.of("block:note", true, 4, false, "CUSTOM_BLOCK",
                Map.of("title", "Retest", "blockType", "callout", "spec", callout)),
            SectionNode.of("block:diagram", true, 5, false, "CUSTOM_BLOCK",
                Map.of("title", "Diagram", "blockType", "drawing", "spec", drawing)),
            SectionNode.of("block:mystery", true, 6, false, "CUSTOM_BLOCK",
                Map.of("title", "Mystery", "blockType", "hologram", "spec", "{\"type\":\"hologram\"}")),
            SectionNode.of("block:broken", true, 7, false, "CUSTOM_BLOCK",
                Map.of("title", "Broken", "blockType", "chart", "spec", "{not json"))
        );
        assert CustomBlockRenderer.sanitizeSvg("<svg viewBox=\"0 0 10 10\"><script>alert(1)</script></svg>").isEmpty();
        return new ReportProfile("1", "user:blocks", "Blocks", "en", false, null,
            null, null, new SectionGraph(nodes), null, null, null, null);
    }
}
