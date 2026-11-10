package icarus.report.canvas;

import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.parser.PdfTextExtractor;
import icarus.core.Severity;
import icarus.evidence.PdfReportGenerator;
import icarus.evidence.ReportGenerator;
import icarus.report.PreviewService;
import icarus.report.model.ReportProfile;
import icarus.report.model.ReportProfileCodec;
import icarus.report.model.SectionGraph;
import icarus.report.model.SectionNode;
import icarus.report.render.FindingView;
import icarus.report.render.ReportData;
import icarus.report.render.ReportRenderContext;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

        // Studio export with one real finding; client comes from the requester variable.
        ReportProfile rich = ReportProfileCodec.fromJson(Files.readString(fixtures.resolve("rich.json")));
        ReportData richData = new ReportData("Comprehensive Web Application Penetration Test", "Enterprise Vulnerability Assessment",
            List.of(new FindingView("rich-1", 1, "SERVER_ERROR", Severity.HIGH, "SERVER_ERROR", "ServerErrorScanner",
                "/debug", List.of(), "", Map.of(), List.of(), null, null, false)),
            Map.of(Severity.HIGH, 1L), 0, 1, Map.of("requester", "QA-CLIENT-7", "author", "QA-AUTHOR-3"), false);
        renderProfile(rich, richData, out.resolve("rich.html"), out.resolve("rich.pdf"));
        String richHtml = Files.readString(out.resolve("rich.html"));
        String richPdf = pdfText(out.resolve("rich.pdf"));
        assert !richHtml.contains("{{") && !richPdf.contains("{{") : "no literal tokens";
        assert richHtml.contains("QA-CLIENT-7") && richPdf.contains("QA-CLIENT-7") : "client from requester";
        assert richPdf.contains("QA-AUTHOR-3") : "author variable";
        assert richPdf.contains("Penetration Test") : "wrapped title keeps its words";
        assert richPdf.contains("SERVER_ERROR") && richPdf.contains("HIGH") && richPdf.contains("/debug") : "pdf finding row";
        assert richHtml.contains("SERVER_ERROR") && richHtml.contains("/debug") : "html finding row";
        assert richHtml.contains("background:#0F172A;color:#FFFFFF") : "dark header gets white ink";
        assert richHtml.contains(">High</span>") && richPdf.contains("High") : "chart labels in both outputs";

        // Studio tokens with no value print as empty text; unknown tokens stay visible.
        ReportData blank = new ReportData(null, null, List.of(), Map.of(), 0, 0, Map.of(), false);
        var blankVars = CanvasPages.variables(new ReportRenderContext(rich, blank, null, out), 1, 2);
        assert CanvasPages.fill("[{{project}}]", blankVars).equals("[]") : "blank project is empty";
        assert CanvasPages.fill("[{{projct}}]", blankVars).equals("[{{projct}}]") : "typo stays literal";
        for (String token : List.of("report_title", "project", "client", "tester", "author", "date", "version", "classification",
                "finding_count", "count_critical", "count_info", "page", "pages")) {
            assert !CanvasPages.fill("{{" + token + "}}", blankVars).contains("{{") : "studio token " + token;
        }

        // Static tables print their stored rows in both outputs.
        ReportProfile staticPage = new ReportProfile("1", "user:canvas-static", "Static", "en", false, null,
            null, null, new SectionGraph(List.of(
                SectionNode.of("page:static", true, 1, false, "CANVAS_PAGE", Map.of("title", "Static", "role", "interior",
                    "spec", "{\"background\":\"#FFFFFF\",\"elements\":[{\"type\":\"table\",\"x\":10,\"y\":10,\"w\":120,\"h\":30,\"mode\":\"static\","
                        + "\"columns\":[{\"key\":\"a\",\"label\":\"Name\"},{\"key\":\"b\",\"label\":\"Value\"}],\"rows\":[[\"alpha-row\",\"beta-value\"]]}]}"))
            )), null, null, null, null);
        renderProfile(staticPage, out.resolve("static.html"), out.resolve("static.pdf"));
        assert Files.readString(out.resolve("static.html")).contains("beta-value") : "static rows in html";
        assert pdfText(out.resolve("static.pdf")).contains("beta-value") : "static rows in pdf";

        // Arrowheads in HTML: 6.3 x 2.35 stroke widths, tip on the line end, at any angle.
        ReportProfile arrows = singlePage("arrows", "{\"background\":\"#FFFFFF\",\"elements\":["
            + "{\"type\":\"line\",\"x1\":20,\"y1\":20,\"x2\":120,\"y2\":20,\"stroke\":\"#111111\",\"strokeWidth\":1,\"arrow\":true},"
            + "{\"type\":\"line\",\"x1\":40,\"y1\":60,\"x2\":40,\"y2\":160,\"stroke\":\"#111111\",\"strokeWidth\":1,\"arrow\":true},"
            + "{\"type\":\"line\",\"x1\":20,\"y1\":200,\"x2\":120,\"y2\":250,\"stroke\":\"#111111\",\"strokeWidth\":1,\"arrow\":true}]}");
        renderProfile(arrows, out.resolve("arrows.html"), out.resolve("arrows.pdf"));
        Matcher heads = Pattern.compile("<polygon points=\"([^\"]+)\"").matcher(Files.readString(out.resolve("arrows.html")));
        int arrowCount = 0;
        while (heads.find()) {
            double[] v = Arrays.stream(heads.group(1).trim().split("[ ,]")).mapToDouble(Double::parseDouble).toArray();
            double baseX = (v[2] + v[4]) / 2;
            double baseY = (v[3] + v[5]) / 2;
            assert Math.abs(Math.hypot(v[0] - baseX, v[1] - baseY) - 6.3) < 0.01 : "arrow length is 6.3 stroke widths";
            assert Math.abs(Math.hypot(v[2] - baseX, v[3] - baseY) - 2.35) < 0.01 : "arrow half-width is 2.35 stroke widths";
            arrowCount++;
        }
        assert arrowCount == 3 : "one arrowhead per arrow line";

        // Chart kinds: line and pie are strokes and wedges with no bars and no labels; bars keep their labels.
        ReportProfile charts = singlePage("charts", "{\"background\":\"#FFFFFF\",\"elements\":["
            + "{\"type\":\"chart\",\"chart\":\"line\",\"binding\":\"static\",\"x\":10,\"y\":10,\"w\":80,\"h\":60,\"categories\":[{\"key\":\"A\",\"label\":\"Low\",\"value\":1,\"color\":\"#B42318\"},{\"key\":\"B\",\"label\":\"High\",\"value\":3,\"color\":\"#B42318\"}]},"
            + "{\"type\":\"chart\",\"chart\":\"pie\",\"binding\":\"static\",\"x\":100,\"y\":10,\"w\":60,\"h\":60,\"categories\":[{\"key\":\"A\",\"label\":\"Low\",\"value\":1,\"color\":\"#B42318\"},{\"key\":\"B\",\"label\":\"High\",\"value\":3,\"color\":\"#2563EB\"}]}]}");
        renderProfile(charts, out.resolve("charts.html"), out.resolve("charts.pdf"));
        String chartsHtml = Files.readString(out.resolve("charts.html"));
        assert chartsHtml.contains("stroke=\"#2563EB\"") && chartsHtml.contains("A36 36") : "line and pie drawn in html";
        assert !chartsHtml.contains("<rect") && !chartsHtml.contains("<span") : "no bars or labels for line and pie";

        // Empty table: one dash row spanning all columns, in both outputs.
        ReportProfile emptyTable = singlePage("empty", "{\"background\":\"#FFFFFF\",\"elements\":[{\"type\":\"table\",\"x\":10,\"y\":10,\"w\":120,\"h\":30,\"mode\":\"findings\","
            + "\"columns\":[{\"key\":\"title\",\"label\":\"Finding\"},{\"key\":\"path\",\"label\":\"Path\"}]}]}");
        renderProfile(emptyTable, new ReportData("T", "P", List.of(), Map.of(), 0, 0, Map.of(), false), out.resolve("empty.html"), out.resolve("empty.pdf"));
        assert Files.readString(out.resolve("empty.html")).contains("colspan=\"2\">\u2014</td>") : "empty table dash row in html";
        assert pdfText(out.resolve("empty.pdf")).contains("\u2014") : "empty table dash row in pdf";

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

    private static ReportProfile singlePage(String id, String spec) {
        return new ReportProfile("1", "user:canvas-" + id, id, "en", false, null, null, null,
            new SectionGraph(List.of(SectionNode.of("page:" + id, true, 1, false, "CANVAS_PAGE",
                Map.of("title", id, "role", "interior", "spec", spec)))), null, null, null, null);
    }

    private static void renderProfile(ReportProfile profile, Path html, Path pdf) throws Exception {
        renderProfile(profile, PreviewService.createFixtureReportData(), html, pdf);
    }

    private static void renderProfile(ReportProfile profile, ReportData data, Path html, Path pdf) throws Exception {
        var ctx = new ReportRenderContext(profile, data, null, html.getParent());
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
