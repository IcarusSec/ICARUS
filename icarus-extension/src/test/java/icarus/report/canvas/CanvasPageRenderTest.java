package icarus.report.canvas;

import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.parser.PdfTextExtractor;
import icarus.evidence.PdfReportGenerator;
import icarus.evidence.ReportGenerator;
import icarus.report.PreviewService;
import icarus.report.model.ReportProfile;
import icarus.report.model.ReportProfileCodec;
import icarus.report.model.SectionGraph;
import icarus.report.model.SectionNode;
import icarus.report.render.ReportRenderContext;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Renders the four starter covers through the same generators Burp calls,
 * and checks that a profile from before canvas pages still takes the old path.
 */
public class CanvasPageRenderTest {
    public static void main(String[] args) throws Exception {
        Path fixtures = args.length > 0 ? Path.of(args[0]) : Path.of("src/test/resources/reports/canvas");
        Path out = args.length > 1 ? Path.of(args[1]) : Path.of("build_manual", "canvas-samples");
        Files.createDirectories(out);

        String swissHtml = render(fixtures.resolve("swiss.json"), out, "swiss");
        assert swissHtml.contains("mix-blend-mode:difference") : "swiss difference blend";
        assert swissHtml.contains("Graphic") && swissHtml.contains("Dialogue") : "swiss title";
        assert swissHtml.contains("Security Team") : "tester comes from the report, not the template sample";
        assert swissHtml.contains("font-family:'ICARUS Sans'") : "embedded sans";
        assert swissHtml.contains("@font-face") : "font bytes in the html";
        String swissPdf = pdfText(out.resolve("swiss.pdf"));
        assert swissPdf.contains("Graphic") : "pdf title line 1";
        assert swissPdf.contains("Dialogue") : "pdf title line 2";
        assert swissPdf.contains("A series of visual conversations") : "pdf subtitle";
        assert swissPdf.contains("Security Team") : "pdf tester";
        assert swissPdf.contains("SQL Injection in /api/v1/users") : "interior findings use fixture data";

        String trianglesHtml = render(fixtures.resolve("triangles.json"), out, "triangles");
        assert trianglesHtml.contains("WORD") && trianglesHtml.contains("rotate(-28") : "triangle logo bars";
        String trianglesPdf = pdfText(out.resolve("triangles.pdf"));
        assert trianglesPdf.contains("WORD") && trianglesPdf.contains("COVER") && trianglesPdf.contains("DESIGN") : "pdf title";
        assert trianglesPdf.contains("24 Oct 2026") : "date from branding";

        String stripesHtml = render(fixtures.resolve("stripes.json"), out, "stripes");
        assert stripesHtml.contains("filter:grayscale(1)") : "grayscale strips";
        assert stripesHtml.contains("#A3205A") : "magenta tint";
        assert stripesHtml.contains("Northwind") : "client from branding";
        assert stripesHtml.contains("v1.0") : "version default";
        String stripesPdf = pdfText(out.resolve("stripes.pdf"));
        assert stripesPdf.contains("CORPORATE") && stripesPdf.contains("COVER") : "pdf stripes title";
        assert stripesPdf.contains("Northwind") : "pdf client";

        String viewbookHtml = render(fixtures.resolve("viewbook.json"), out, "viewbook");
        assert viewbookHtml.contains("clip-path:polygon") : "parallelogram photo frames";
        assert viewbookHtml.contains("ENGINEERING") : "viewbook headline";
        String viewbookPdf = pdfText(out.resolve("viewbook.pdf"));
        assert viewbookPdf.contains("ENGINEERING") && viewbookPdf.contains("VIEWBOOK") : "pdf viewbook";
        assert viewbookPdf.contains("DISCOVER") : "pdf discover";

        ReportProfile broken = new ReportProfile("1", "user:canvas-broken", "Broken", "en", false, null,
            null, null, new SectionGraph(List.of(
                SectionNode.of("page:bad", true, 1, false, "CANVAS_PAGE",
                    Map.of("title", "Bad", "role", "cover", "spec", "{not json")),
                SectionNode.of("page:odd", true, 2, false, "CANVAS_PAGE",
                    Map.of("title", "Odd", "role", "interior", "spec",
                        "{\"background\":\"#FFFFFF\",\"elements\":[{\"type\":\"sparkline\",\"x\":10,\"y\":10,\"w\":40,\"h\":10}]}"))
            )), null, null, null, null);
        renderProfile(broken, out.resolve("broken.html"), out.resolve("broken.pdf"));
        String brokenHtml = Files.readString(out.resolve("broken.html"));
        assert brokenHtml.contains("This page could not be read.") : "invalid spec";
        assert brokenHtml.contains("Unsupported element") : "unknown element";
        assert pdfText(out.resolve("broken.pdf")).contains("This page could not be read.") : "pdf invalid spec";

        ReportProfile legacy = ReportProfileCodec.fromStream(
            CanvasPageRenderTest.class.getResourceAsStream("/reports/profiles/executive-modern.json"));
        assert !CanvasPages.hasCanvas(legacy) : "old profile is not a canvas";
        renderProfile(legacy, out.resolve("legacy.html"), out.resolve("legacy.pdf"));
        String legacyHtml = Files.readString(out.resolve("legacy.html"));
        assert legacyHtml.contains("Executive Summary") : "legacy html";
        assert !legacyHtml.contains("mix-blend-mode") : "legacy stayed on the old renderer";
        assert pdfText(out.resolve("legacy.pdf")).contains("Executive Summary") : "legacy pdf";

        System.out.println("[TEST] CanvasPageRender passed");
        System.out.println(" samples: " + out.toAbsolutePath());
    }

    private static String render(Path json, Path out, String name) throws Exception {
        ReportProfile profile = ReportProfileCodec.fromJson(Files.readString(json));
        Path html = out.resolve(name + ".html");
        Path pdf = out.resolve(name + ".pdf");
        renderProfile(profile, html, pdf);
        assert Files.size(pdf) > 2000 : name + " pdf empty";
        return Files.readString(html);
    }

    private static void renderProfile(ReportProfile profile, Path html, Path pdf) throws Exception {
        var ctx = new ReportRenderContext(profile, PreviewService.createFixtureReportData(), null, html.getParent());
        if (!new ReportGenerator(null).generate(ctx, html)) throw new AssertionError("html not written");
        if (!new PdfReportGenerator(null).generate(ctx, pdf)) throw new AssertionError("pdf not written");
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
}
