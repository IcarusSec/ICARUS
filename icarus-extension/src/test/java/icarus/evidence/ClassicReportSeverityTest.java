package icarus.evidence;

import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.parser.PdfTextExtractor;
import icarus.ReportExportService;
import icarus.core.Finding;
import icarus.core.I18n;
import icarus.core.ModuleConfig;
import icarus.core.ReportTemplateConfig;
import icarus.core.Severity;
import icarus.evidence.EvidenceCapture.CapturedEvidence;

import java.awt.image.BufferedImage;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Classic PDF and HTML reports must print a finding's own severity (the one Results shows),
 * unless the user saved a template severity; and a screenshot with no caption must not print
 * a bare "1.". Generated headlessly, then read back with OpenPDF's text extractor.
 * {@code java -cp build_manual/classes:build_manual/test-classes:libs/* icarus.evidence.ClassicReportSeverityTest}
 */
public final class ClassicReportSeverityTest {

    private static final Path OUT = Path.of("build_manual", "classic-report-test");

    public static void main(String[] args) throws Exception {
        Files.createDirectories(OUT);
        I18n.initialize(new ModuleConfig());

        // SERVER_ERROR's knowledge-base severity is MEDIUM; the finding itself is HIGH.
        Finding high = Finding.builder("Passive Error Detector", "SERVER_ERROR")
            .severity(Severity.HIGH).category(icarus.core.Category.SERVER_ERROR)
            .description("Server returned HTTP 500").path("/debug").build();
        ModuleConfig cfg = config();
        EvidenceCapture capture = capture(cfg, high, "");

        Path pdf = report(cfg, capture, List.of(high), "pdf");
        String pdfText = pdfText(pdf);
        // Report ID comes from the output file name, not the folder it sits in.
        String id = pdf.getFileName().toString().replace(".pdf", "");
        assert pdfText.contains("Report ID: " + id) : "Report ID is not the file name";
        System.out.println("   [PDF text]\n" + pdfText);
        assert pdfText.contains("Severity HIGH") && pdfText.contains("SERVER_ERROR HIGH") : "PDF does not show the finding's HIGH";
        assert !pdfText.contains("Severity MEDIUM") && !pdfText.contains("SERVER_ERROR MEDIUM") : "PDF shows knowledge-base MEDIUM";

        Path htmlFile = report(cfg, capture, List.of(high), "html");
        String html = Files.readString(htmlFile);
        assert html.contains("Report ID: " + htmlFile.getFileName().toString().replace(".html", "")) : "HTML Report ID is not the file name";
        assert html.contains("class=\"badge HIGH\">HIGH</span>") : "HTML badge is not the finding's HIGH";
        assert !html.contains("class=\"badge MEDIUM\">") : "HTML badge shows knowledge-base MEDIUM";

        // An explicit user template severity still wins.
        ReportTemplateConfig rtc = ReportTemplateConfig.fromConfig(cfg);
        rtc.setFindingTemplates(java.util.Map.of("SERVER_ERROR",
            new ReportTemplateConfig.FindingTemplate(null, null, null, "LOW")));
        rtc.saveTo(cfg);
        String overridden = pdfText(report(cfg, capture, List.of(high), "pdf"));
        assert overridden.contains("Severity LOW") && overridden.contains("SERVER_ERROR LOW") : "user template severity not applied";

        // Blank caption: no stray "1." paragraph.
        ModuleConfig plain = config();
        EvidenceCapture captioned = capture(plain, high, "");
        String noCaption = pdfText(report(plain, captioned, List.of(high), "pdf"));
        for (String line : noCaption.split("\n")) {
            assert !line.trim().equals("1.") : "bare evidence number printed";
        }
        System.out.println("ClassicReportSeverityTest: all checks passed.");
    }

    private static ModuleConfig config() {
        ModuleConfig cfg = new ModuleConfig();
        cfg.set("evidence.include_project_name", false);
        ReportTemplateConfig rtc = ReportTemplateConfig.fromConfig(cfg);
        rtc.variables().put("project", "CLASSIC-TEST");
        rtc.saveTo(cfg);
        return cfg;
    }

    private static EvidenceCapture capture(ModuleConfig cfg, Finding f, String caption) throws Exception {
        Path png = OUT.resolve("evidence-" + System.nanoTime() + ".png");
        javax.imageio.ImageIO.write(new BufferedImage(120, 60, BufferedImage.TYPE_INT_RGB), "png", png.toFile());
        EvidenceCapture capture = new EvidenceCapture(null, cfg);
        capture.restoreCaptured(new CapturedEvidence(f, png, javax.imageio.ImageIO.read(png.toFile()), caption), true);
        return capture;
    }

    /** Writes through the same private helper the Export and Preview buttons use. */
    private static Path report(ModuleConfig cfg, EvidenceCapture capture, List<Finding> findings, String ext) throws Exception {
        ReportExportService service = new ReportExportService(null, cfg,
            new ReportGenerator(null), new PdfReportGenerator(null), capture, null);
        Method writeReport = ReportExportService.class.getDeclaredMethod("writeReport", boolean.class, List.class, Path.class);
        writeReport.setAccessible(true);
        Path out = OUT.resolve("classic-" + System.nanoTime() + "." + ext);
        assert (Boolean) writeReport.invoke(service, ext.equals("pdf"), findings, out) : "report not written";
        return out;
    }

    private static String pdfText(Path pdf) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (PdfReader reader = new PdfReader(pdf.toString())) {
            PdfTextExtractor extractor = new PdfTextExtractor(reader);
            for (int p = 1; p <= reader.getNumberOfPages(); p++) {
                sb.append(extractor.getTextFromPage(p)).append('\n');
            }
        }
        return sb.toString().replaceAll("[ \\t]+", " ");
    }
}
