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
import icarus.report.PreviewService;
import icarus.report.model.ReportProfile;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * PDF export must use the active profile's cover and finding layout, the same renderers
 * Preview uses, including a profile brought in through Reporting → Import.
 * {@code java -cp build_manual/classes:build_manual/test-classes:libs/* icarus.ReportExportLayoutTest}
 */
public class ReportExportLayoutTest {

    public static void main(String[] args) throws Exception {
        Path out = Path.of("build_manual", "export-layout-test");
        Files.createDirectories(out);
        classicTechnicalUsesTabularLayout(out);
        importedProfileUsesItsLayout(out);
        previewAndExportShareTheTabularMarker(out);
        System.out.println("ReportExportLayoutTest: all checks passed.");
    }

    /** Built-in Classic Technical is HEADER_BAND + TABULAR, not the legacy "ICARUS Report" cover. */
    private static void classicTechnicalUsesTabularLayout(Path out) throws Exception {
        ModuleConfig config = configured();
        DefaultReportProfileManager profiles = new DefaultReportProfileManager(config);
        profiles.setActive("builtin:classic-technical");

        String text = exportPdf(config, out.resolve("classic.pdf"));
        assert text.contains("Vulnerability #") : "tabular finding layout missing:\n" + text;
        assert text.contains("LAYOUT-FINDING") : "finding text missing:\n" + text;
        assert !text.contains("Report ID:") : "legacy cover still used:\n" + text;
        assert !text.contains("ICARUS Report") : "legacy header still used:\n" + text;
        System.out.println("   [PASSED] Classic Technical PDF uses the profile finding layout.");
    }

    /** A profile imported the same way Reporting → Import does keeps its cover-less tabular layout. */
    private static void importedProfileUsesItsLayout(Path out) throws Exception {
        ModuleConfig config = configured();
        DefaultReportProfileManager profiles = new DefaultReportProfileManager(config);
        String json = """
            {"schemaVersion":"1","name":"Imported Layout","coverRenderer":"NONE","findingRenderer":"TABULAR",
             "sections":{"nodes":[
               {"id":"NOTES","enabled":true,"order":1,"required":false,"rendererKey":"NOTES",
                "params":{"title":"UNIQUE-SECTION-ALPHA","content":"Imported layout body"}},
               {"id":"FINDINGS","enabled":true,"order":2,"required":true,"rendererKey":"FINDINGS","params":{}}
             ]}}
            """;
        ReportProfile imported = profiles.importJson(json);
        profiles.setActive(imported.id());

        String text = exportPdf(config, out.resolve("imported.pdf"));
        assert text.contains("UNIQUE-SECTION-ALPHA") : "imported section missing:\n" + text;
        assert text.contains("Imported layout body") : "imported section body missing:\n" + text;
        assert text.contains("Vulnerability #") : "imported profile did not use its tabular layout:\n" + text;
        assert !text.contains("Report ID:") : "imported profile fell back to the legacy cover:\n" + text;
        System.out.println("   [PASSED] Imported profile PDF uses that profile's layout.");
    }

    /** Preview of the same built-in profile draws the tabular marker export now draws. */
    private static void previewAndExportShareTheTabularMarker(Path out) throws Exception {
        ModuleConfig config = configured();
        ReportProfile classic = new DefaultReportProfileManager(config).get("builtin:classic-technical");
        String preview = pdfText(PreviewService.generatePreviewFile(classic, PreviewService.Format.PDF).toPath());
        profilesActive(config, "builtin:classic-technical");
        String exported = exportPdf(config, out.resolve("match-preview.pdf"));
        assert preview.contains("Vulnerability #") : "preview is not the tabular layout";
        assert exported.contains("Vulnerability #") : "export is not the tabular layout";
        System.out.println("   [PASSED] Preview and export both use the tabular finding layout.");
    }

    private static ModuleConfig configured() {
        ModuleConfig config = new ModuleConfig();
        ReportTemplateConfig rtc = ReportTemplateConfig.fromConfig(config);
        rtc.variables().put("project", "REAL-PROJECT-QA");
        rtc.variables().put("report_title", "Real Title QA");
        rtc.saveTo(config);
        return config;
    }

    private static void profilesActive(ModuleConfig config, String id) {
        new DefaultReportProfileManager(config).setActive(id);
    }

    private static String exportPdf(ModuleConfig config, Path pdf) throws Exception {
        Finding low = Finding.builder("ICARUS", "INFO-NOTE").severity(Severity.INFO).description("low").path("/low").build();
        Finding high = Finding.builder("ICARUS", "LAYOUT-FINDING").severity(Severity.HIGH)
            .description("Real finding for layout test").path("/layout").build();
        ReportExportService service = new ReportExportService(null, config,
            new ReportGenerator(null), new PdfReportGenerator(null), new EvidenceCapture(null, config), null);
        Method writeReport = ReportExportService.class.getDeclaredMethod("writeReport", boolean.class, List.class, Path.class);
        writeReport.setAccessible(true);
        assert (Boolean) writeReport.invoke(service, true, List.of(low, high), pdf) : "pdf not written";
        String text = pdfText(pdf);
        int highAt = text.indexOf("LAYOUT-FINDING");
        int lowAt = text.indexOf("INFO-NOTE");
        assert highAt >= 0 && lowAt > highAt : "findings were not worst-first:\n" + text;
        return text;
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
