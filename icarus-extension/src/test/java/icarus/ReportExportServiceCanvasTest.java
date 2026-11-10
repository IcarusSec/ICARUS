package icarus;

import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.parser.PdfTextExtractor;
import icarus.core.Finding;
import icarus.core.ModuleConfig;
import icarus.core.ReportTemplateConfig;
import icarus.core.Severity;
import icarus.evidence.EvidenceCapture;
import icarus.evidence.PdfReportGenerator;
import icarus.evidence.ReportGenerator;
import icarus.report.DefaultReportProfileManager;
import icarus.report.model.ReportProfile;
import icarus.report.model.ReportProfileCodec;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Export of a canvas profile goes through the context renderers with the real finding and the
 * Report Details values, not the PreviewService fixture.
 */
public class ReportExportServiceCanvasTest {
    public static void main(String[] args) throws Exception {
        Path out = Path.of("build_manual", "export-canvas-test");
        Files.createDirectories(out);

        ModuleConfig config = new ModuleConfig();
        ReportProfile canvas = ReportProfileCodec.fromJson(
            Files.readString(Path.of("src/test/resources/reports/canvas/stripes.json")));
        DefaultReportProfileManager profiles = new DefaultReportProfileManager(config);
        profiles.saveUserProfile(canvas);
        profiles.setActive(canvas.id());

        ReportTemplateConfig rtc = ReportTemplateConfig.fromConfig(config);
        rtc.variables().put("project", "REAL-PROJECT-QA");
        rtc.variables().put("report_title", "Real Title QA");
        rtc.saveTo(config);

        Finding finding = Finding.builder("ICARUS", "SERVER_ERROR")
            .severity(Severity.HIGH)
            .description("Real finding for export test")
            .path("/debug")
            .build();

        // Calls the private export helper directly; the public entry points need Burp's API.
        ReportExportService service = new ReportExportService(null, config,
            new ReportGenerator(null), new PdfReportGenerator(null), new EvidenceCapture(null, config), null);
        Method writeReport = ReportExportService.class.getDeclaredMethod("writeReport", boolean.class, List.class, Path.class);
        writeReport.setAccessible(true);

        Path html = out.resolve("canvas-export.html");
        assert (Boolean) writeReport.invoke(service, false, List.of(finding), html) : "html not written";
        String htmlText = Files.readString(html);
        assert htmlText.contains("filter:grayscale(1)") : "canvas sheet missing from export html";
        assert htmlText.contains("REAL-PROJECT-QA") : "project from Report Details not used";
        assert htmlText.contains("Real Title QA") : "report title from Report Details not used";
        assert !htmlText.contains("Acme Production App") : "PreviewService fixture leaked into export";
        assert !htmlText.contains("{{") : "unresolved template variable in export";

        Path pdf = out.resolve("canvas-export.pdf");
        assert (Boolean) writeReport.invoke(service, true, List.of(finding), pdf) : "pdf not written";
        String pdfText = pdfText(pdf);
        assert pdfText.contains("REAL-PROJECT-QA") : "pdf project from Report Details";
        assert !pdfText.contains("Acme Production App") : "pdf fixture leaked into export";

        System.out.println("[TEST] ReportExportServiceCanvas passed");
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
