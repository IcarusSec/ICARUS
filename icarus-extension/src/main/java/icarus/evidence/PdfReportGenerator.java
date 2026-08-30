package icarus.evidence;

import burp.api.montoya.MontoyaApi;

import icarus.core.Finding;
import icarus.core.I18n;
import icarus.core.ModuleConfig;
import icarus.core.ReportTemplateConfig;
import icarus.core.Severity;
import icarus.evidence.EvidenceCapture.CapturedEvidence;

import org.commonmark.parser.Parser;

import com.lowagie.text.*;
import com.lowagie.text.pdf.PdfAction;
import com.lowagie.text.pdf.PdfOutline;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * PDF counterpart to {@link ReportGenerator}. Built directly against OpenPDF's API rather
 * than converting the existing HTML/CSS — HTML-to-PDF renderers only support old CSS2.1 and
 * would mangle the HTML report's flex-based summary boxes. Light background by design (unlike
 * the HTML report's dark theme): PDFs are commonly printed or read outside a browser, and a
 * full-bleed dark background wastes ink and reads worse off-screen.
 */
public final class PdfReportGenerator {

    private static final Font TITLE_FONT     = new Font(Font.HELVETICA, 20, Font.BOLD, new Color(0x1a, 0x1a, 0x1a));
    private static final Font SUBTITLE_FONT  = new Font(Font.HELVETICA, 9, Font.NORMAL, new Color(0x66, 0x66, 0x66));
    private static final Font COVER_BYLINE_FONT = new Font(Font.HELVETICA, 11, Font.NORMAL, new Color(0x44, 0x44, 0x44));
    private static final Font COVER_CREDIT_FONT = new Font(Font.HELVETICA, 8, Font.NORMAL, new Color(0x99, 0x99, 0x99));
    private static final Font FOOTER_FONT    = new Font(Font.HELVETICA, 8, Font.NORMAL, new Color(0x99, 0x99, 0x99));
    private static final Font SECTION_FONT   = new Font(Font.HELVETICA, 14, Font.BOLD, new Color(0x1a, 0x1a, 0x1a));
    private static final Font BADGE_FONT     = new Font(Font.HELVETICA, 10, Font.BOLD, Color.WHITE);
    private static final Font FINDING_TITLE_FONT = new Font(Font.HELVETICA, 13, Font.BOLD, new Color(0x1a, 0x1a, 0x1a));
    private static final Font LABEL_FONT     = new Font(Font.HELVETICA, 9, Font.BOLD, new Color(0x66, 0x66, 0x66));
    private static final Font BODY_FONT      = new Font(Font.HELVETICA, 10, Font.NORMAL, new Color(0x20, 0x20, 0x20));
    private static final Font STAT_NUM_FONT  = new Font(Font.HELVETICA, 18, Font.BOLD, new Color(0x1a, 0x1a, 0x1a));
    private static final Font STAT_LABEL_FONT = new Font(Font.HELVETICA, 8, Font.NORMAL, new Color(0x66, 0x66, 0x66));
    private static final Font SUBHEADING_FONT = new Font(Font.HELVETICA, 11, Font.BOLD, new Color(0x1a, 0x1a, 0x1a));
    private static final Font RISK_LEVEL_FONT = new Font(Font.HELVETICA, 9, Font.BOLD, Color.WHITE);

    private static final Color CRITICAL = new Color(0xcc, 0x2e, 0x2e);
    private static final Color HIGH     = new Color(0xd9, 0x71, 0x1f);
    private static final Color MEDIUM   = new Color(0xb3, 0x8f, 0x00);
    private static final Color LOW      = new Color(0x2f, 0x7a, 0x77);
    private static final Color INFO     = new Color(0x6e, 0x6e, 0x6e);
    private static final Color FIXED     = new Color(0x2f, 0x9e, 0x44);
    private static final Color NOT_FIXED = new Color(0xcc, 0x2e, 0x2e);
    private static final Color ACCENT   = new Color(0x3e, 0x7b, 0xb8);
    private static final Color BORDER   = new Color(0xdd, 0xdd, 0xdd);
    private static final Color CARD_BG  = new Color(0xf7, 0xf7, 0xf7);
    private static final Color NAVY     = new Color(0, 47, 108);
    private static final Color SLATE    = new Color(74, 85, 104);

    /** Title match for the special-cased risk classification section — see {@link #appendSections}.
     *  Resolved via the same I18n key {@code ReportTemplateConfig.defaultSections()} uses, so the
     *  match still holds whatever the active UI language renders the title as. */
    private static final String RISK_MATRIX_SECTION_TITLE = I18n.t("report.template.title.matriz_classificacao_cvss4");
    private final Color[] riskLevelColors = {CRITICAL, HIGH, MEDIUM, LOW};

    private final MontoyaApi api;
    private final Parser markdownParser = Parser.builder().build();

    public PdfReportGenerator(MontoyaApi api) {
        this.api = api;
    }

    /** Parses a Settings-supplied "#rrggbb" hex string, falling back to {@code fallback} if unset/invalid. */
    private static Color themeColor(String hex, Color fallback) {
        if (hex == null || hex.isBlank()) return fallback;
        try {
            return Color.decode(hex);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static Color lerp(Color from, Color to, float t) {
        int r = Math.round(from.getRed()   + t * (to.getRed()   - from.getRed()));
        int g = Math.round(from.getGreen() + t * (to.getGreen() - from.getGreen()));
        int b = Math.round(from.getBlue()  + t * (to.getBlue()  - from.getBlue()));
        return new Color(r, g, b);
    }

    /** Fills a full-width band of the content stream with a left-to-right two-tone gradient,
     *  {@code from} → {@code to}, as a row of thin rectangles — used both for the cover's
     *  full-bleed hero band and the thinner recurring footer hairline on every other page. */
    private static void fillGradient(com.lowagie.text.pdf.PdfContentByte cb, float x, float y, float width, float height, Color from, Color to) {
        int steps = 80;
        float stepW = width / steps;
        for (int i = 0; i < steps; i++) {
            Color c = lerp(from, to, i / (float) (steps - 1));
            cb.setRGBColorFillF(c.getRed() / 255f, c.getGreen() / 255f, c.getBlue() / 255f);
            cb.rectangle(x + i * stepW, y, stepW + 0.5f, height);
            cb.fill();
        }
    }

    /** Settings → Reporting → Theme's logo path overrides, when set; otherwise falls back to
     *  {@code bundledResourceName} on the classpath, so reports carry branding out of the box
     *  with no configuration required. */
    private static Image loadLogo(String customPath, String bundledResourceName) throws Exception {
        if (customPath != null && !customPath.isBlank()) {
            return Image.getInstance(customPath);
        }
        try (var in = PdfReportGenerator.class.getResourceAsStream(bundledResourceName)) {
            if (in == null) return null;
            return Image.getInstance(in.readAllBytes());
        }
    }

    /** Mirrors {@link ReportGenerator#generate}'s contract: same gating, same return meaning. */
    public boolean generate(List<Finding> findings, ModuleConfig config, EvidenceCapture capture, Path outputPdfFile) throws IOException {
        if (findings.isEmpty()) {
            return false;
        }
        
        ReportTemplateConfig rtc = ReportTemplateConfig.fromConfig(config);
        
        List<Finding> processedFindings = new java.util.ArrayList<>();
        for (Finding f : findings) {
            ReportTemplateConfig.FindingTemplate tmpl = rtc.getFindingTemplate(f.type());
            if (tmpl == null) {
                processedFindings.add(f);
                continue;
            }
            
            Finding.Builder b = Finding.builder(f.module(), f.type())
                .category(f.category())
                .path(f.path())
                .evidence(f.evidence());
            f.metadata().forEach(b::meta);
            f.cweIds().forEach(b::cwe);
            
            b.description(tmpl.descricao() != null && !tmpl.descricao().isBlank() ? tmpl.descricao() : f.description());
            
            Severity sev = f.severity();
            if (tmpl.severidade() != null && !tmpl.severidade().isBlank()) {
                try { sev = Severity.valueOf(tmpl.severidade().toUpperCase()); } catch (Exception e) {}
            }
            b.severity(sev);
            
            processedFindings.add(b.build());
        }
        
        findings = processedFindings;
        // Worst-first, same rule as ReportGenerator — see its comment for why.
        findings.sort(java.util.Comparator.comparingInt(f -> f.severity().ordinal()));

        // Grouped by Finding#similarityHash(), not identity — see ReportGenerator for why.
        var evidenceByHash = capture.groupedBySimilarityHash();

        Path reportDir = outputPdfFile.toAbsolutePath().getParent();
        Files.createDirectories(reportDir);

        rtc.injectFindingsVariables(findings);
        // Evidence Manager's "Report Details" tab wins when filled in (it's what a tester
        // actually intends to appear on the deliverable); falls back to Burp's own project
        // name otherwise, same as before this field existed.
        String projectName = rtc.variables().get("project");
        if (projectName == null || projectName.isBlank()) {
            projectName = null;
            if (config.getBool("evidence.include_project_name", true)) {
                String name = api.project().name();
                if (name != null && !name.isBlank()) projectName = name;
            }
        }
        Color accent = themeColor(rtc.primaryColor(), ACCENT);
        Color accent2 = themeColor(rtc.secondaryColor(), accent);
        boolean addBookmarks = rtc.tocEnabled();
        // Same single persistent "Retest Mode" toggle as ReportGenerator — see its generate() for why.
        boolean retest = config.getBool("retest.enabled", false);

        // Not try-with-resources: doc.close() below already closes the underlying
        // FileOutputStream via PdfWriter/DocWriter, and closing it again first would leave
        // doc.close() flushing to an already-closed stream ("Stream Closed" IOException).
        Document doc = new Document(PageSize.A4, 40, 40, 50, 40);
        try {
            PdfWriter writer = PdfWriter.getInstance(doc, new FileOutputStream(outputPdfFile.toFile()));
            // Without this, OpenPDF defers image placement to better fill a page — which is
            // exactly what breaks finding-card order when a page break lands near a
            // description+image pair: several descriptions get flushed first, then their
            // images "catch up" out of sequence once the writer finally places them. Forces
            // images to render in add() order, matching the meta tables around them.
            writer.setStrictImageSequence(true);
            writer.setPageEvent(new FooterPageEvent(rtc, accent, accent2, this));
            doc.open();

            appendCoverPage(doc, rtc, reportDir.getFileName().toString(), projectName, accent, accent2, writer);
            appendHeader(doc, reportDir.getFileName().toString(), projectName, rtc);
            boolean findingsPlaced = appendSections(doc, rtc, accent, writer, addBookmarks, retest, findings, evidenceByHash, config, capture);
            if (!findingsPlaced) {
                appendSummary(doc, findings, accent);
                appendFindings(doc, findings, evidenceByHash, writer, addBookmarks, config, retest, capture, rtc);
            }
        } catch (DocumentException e) {
            throw new IOException("PDF generation failed", e);
        } finally {
            if (doc.isOpen()) doc.close();
        }

        if (api != null && api.logging() != null) {
            api.logging().logToOutput("PDF Report generated at: " + outputPdfFile.toAbsolutePath());
        }
        return true;
    }

    public boolean generate(icarus.report.render.ReportRenderContext ctx, Path outputPdfFile) throws IOException {
        if (ctx == null || ctx.data() == null) return false;
        Path reportDir = outputPdfFile.toAbsolutePath().getParent();
        if (reportDir != null) Files.createDirectories(reportDir);

        icarus.report.model.ReportProfile profile = ctx.profile();
        Color accent = themeColor(profile.pdfTheme().primaryHex(), ACCENT);
        Color accent2 = themeColor(profile.pdfTheme().secondaryHex(), accent);

        Document doc = new Document(PageSize.A4, 40, 40, 50, 40);
        try {
            PdfWriter writer = PdfWriter.getInstance(doc, new FileOutputStream(outputPdfFile.toFile()));
            writer.setStrictImageSequence(true);
            doc.open();

            // Cover
            icarus.report.render.ReportRendererRegistry registry = new icarus.report.render.ReportRendererRegistry();
            var cover = registry.getCover(profile.coverRenderer());
            if (cover != null) {
                cover.renderPdf(doc, writer, ctx);
            }

            // Header on page flow
            appendProfileHeader(doc, ctx);

            // Sections
            for (var node : profile.sections().enabledInOrder()) {
                String id = node.id().toUpperCase();
                if ("FINDINGS".equals(id)) {
                    appendProfileSummary(doc, ctx, accent);
                    var findingRenderer = registry.getFinding(profile.findingRenderer());
                    for (var fv : ctx.data().findings()) {
                        findingRenderer.renderPdf(doc, writer, fv, ctx);
                    }
                } else if ("VULNERABILITY_SUMMARY".equals(id)) {
                    appendProfileVulnerabilitySummary(doc, ctx, accent);
                }
            }
        } catch (DocumentException e) {
            throw new IOException("PDF generation failed", e);
        } finally {
            if (doc.isOpen()) doc.close();
        }

        if (api != null && api.logging() != null) {
            api.logging().logToOutput("PDF Report generated at: " + outputPdfFile.toAbsolutePath());
        }
        return true;
    }

    private void appendProfileHeader(Document doc, icarus.report.render.ReportRenderContext ctx) throws DocumentException {
        PdfPTable header = new PdfPTable(2);
        header.setWidthPercentage(100);
        header.setSpacingAfter(15f);

        PdfPCell titleCell = new PdfPCell();
        titleCell.setBorder(Rectangle.NO_BORDER);
        titleCell.addElement(new Paragraph(ctx.data().reportTitle(), new Font(Font.HELVETICA, 16, Font.BOLD, decodeColor(ctx.profile().pdfTheme().headingHex(), Color.DARK_GRAY))));
        if (ctx.data().projectName() != null && !ctx.data().projectName().isBlank()) {
            titleCell.addElement(new Paragraph("Project: " + ctx.data().projectName(), new Font(Font.HELVETICA, 10, Font.NORMAL, Color.GRAY)));
        }
        header.addCell(titleCell);

        PdfPCell metaCell = new PdfPCell();
        metaCell.setBorder(Rectangle.NO_BORDER);
        metaCell.setHorizontalAlignment(Element.ALIGN_RIGHT);
        String auth = ctx.profile().branding() != null ? ctx.profile().branding().author() : "";
        if (!auth.isBlank()) {
            metaCell.addElement(new Paragraph("Author: " + auth, new Font(Font.HELVETICA, 9, Font.NORMAL, Color.DARK_GRAY)));
        }
        header.addCell(metaCell);
        doc.add(header);
    }

    private void appendProfileSummary(Document doc, icarus.report.render.ReportRenderContext ctx, Color accent) throws DocumentException {
        PdfPTable summary = new PdfPTable(5);
        summary.setWidthPercentage(100);
        summary.setSpacingBefore(10f);
        summary.setSpacingAfter(15f);

        for (Severity sev : List.of(Severity.CRITICAL, Severity.HIGH, Severity.MEDIUM, Severity.LOW, Severity.INFO)) {
            PdfPCell cell = new PdfPCell();
            cell.setBackgroundColor(new Color(247, 247, 247));
            cell.setBorderColor(new Color(220, 220, 220));
            cell.setPadding(8f);
            cell.setHorizontalAlignment(Element.ALIGN_CENTER);

            long count = ctx.data().getCount(sev);
            Color col = decodeColor(ctx.profile().pdfTheme().severityHex().get(sev), Color.GRAY);

            Paragraph pCount = new Paragraph(String.valueOf(count), new Font(Font.HELVETICA, 16, Font.BOLD, col));
            pCount.setAlignment(Element.ALIGN_CENTER);
            Paragraph pLabel = new Paragraph(sev.name(), new Font(Font.HELVETICA, 8, Font.BOLD, Color.DARK_GRAY));
            pLabel.setAlignment(Element.ALIGN_CENTER);

            cell.addElement(pCount);
            cell.addElement(pLabel);
            summary.addCell(cell);
        }
        doc.add(summary);
    }

    private void appendProfileVulnerabilitySummary(Document doc, icarus.report.render.ReportRenderContext ctx, Color accent) throws DocumentException {
        PdfPTable table = new PdfPTable(new float[]{0.8f, 3.8f, 1.4f});
        table.setWidthPercentage(100);
        table.setSpacingBefore(10f);
        table.setSpacingAfter(15f);

        for (String h : new String[]{"#", "Vulnerability", "Severity"}) {
            PdfPCell hc = new PdfPCell(new Phrase(h, new Font(Font.HELVETICA, 9, Font.BOLD, Color.WHITE)));
            hc.setBackgroundColor(accent);
            hc.setPadding(5f);
            table.addCell(hc);
        }

        for (var f : ctx.data().findings()) {
            PdfPCell c1 = new PdfPCell(new Phrase(String.valueOf(f.displayIndex()), new Font(Font.HELVETICA, 9, Font.NORMAL, Color.BLACK)));
            c1.setPadding(4f);
            PdfPCell c2 = new PdfPCell(new Phrase(f.title(), new Font(Font.HELVETICA, 9, Font.NORMAL, Color.BLACK)));
            c2.setPadding(4f);
            PdfPCell c3 = new PdfPCell(new Phrase(f.severity().name(), new Font(Font.HELVETICA, 8, Font.BOLD, Color.WHITE)));
            c3.setBackgroundColor(decodeColor(ctx.profile().pdfTheme().severityHex().get(f.severity()), Color.GRAY));
            c3.setPadding(4f);
            c3.setHorizontalAlignment(Element.ALIGN_CENTER);

            table.addCell(c1);
            table.addCell(c2);
            table.addCell(c3);
        }
        doc.add(table);
    }

    private static Color decodeColor(String hex, Color fallback) {
        if (hex == null || hex.isBlank()) return fallback;
        try { return Color.decode(hex.trim()); } catch (Exception e) { return fallback; }
    }

    /** Standalone first page: a full-bleed gradient hero band (the report's configured accent
     *  colors, Settings → Reporting → Theme) fills the top ~57% of the page, carrying a white card
     *  with the client logo and a big reversed title. Classification badge and byline sit
     *  below, on white. Fully absolute-positioned — no Document flow/margins involved — so
     *  laying it out doesn't require juggling page margins for one page and restoring them for
     *  the next; page 2's normal flow (via {@link #appendHeader}) is completely unaffected by
     *  whatever this method draws. The ICARUS credit lives in {@link FooterPageEvent}, drawn at
     *  the same fixed footer position every other page uses. */
    private void appendCoverPage(Document doc, ReportTemplateConfig rtc, String reportName, String projectName, Color accent, Color accent2, PdfWriter writer) throws DocumentException {
        float pageW = doc.getPageSize().getWidth();
        float pageH = doc.getPageSize().getHeight();
        float bandHeight = pageH * 0.57f;
        float bandBottom = pageH - bandHeight;

        com.lowagie.text.pdf.PdfContentByte under = writer.getDirectContentUnder();
        com.lowagie.text.pdf.PdfContentByte over = writer.getDirectContent();

        fillGradient(under, 0f, bandBottom, pageW, bandHeight, accent, accent2);

        float cardW = 300f, cardH = 130f;
        float cardX = (pageW - cardW) / 2f;
        float cardY = pageH - 76f - cardH;
        over.setRGBColorFillF(1f, 1f, 1f);
        over.roundRectangle(cardX, cardY, cardW, cardH, 14f);
        over.fill();

        try {
            Image clientLogo = loadLogo(rtc.clientLogoPath(), "/client_logo.png");
            if (clientLogo != null) {
                clientLogo.scaleToFit(cardW - 56f, cardH - 44f);
                clientLogo.setAbsolutePosition(
                        cardX + (cardW - clientLogo.getScaledWidth()) / 2f,
                        cardY + (cardH - clientLogo.getScaledHeight()) / 2f);
                over.addImage(clientLogo);
            }
        } catch (Exception e) {
            api.logging().logToError("Failed to load client logo: " + e.getMessage());
        }

        String title = rtc.interpolate("{{report_title}}");
        if (title == null || title.isBlank()) title = I18n.t("evidence.pdf.default_title");
        Font coverTitleFont = new Font(Font.HELVETICA, 32, Font.BOLD, Color.WHITE);
        com.lowagie.text.pdf.ColumnText titleCt = new com.lowagie.text.pdf.ColumnText(over);
        titleCt.setSimpleColumn(new Phrase(title, coverTitleFont),
                56f, bandBottom + 30f, pageW - 56f, cardY - 26f, 36f, Element.ALIGN_CENTER);
        titleCt.go();
        float afterTitleY = titleCt.getYLine();

        String classification = rtc.interpolate("{{classification}}");
        float afterBadgeY = afterTitleY;
        if (classification != null && !classification.isBlank()) {
            Font badgeTextFont = new Font(Font.HELVETICA, 10, Font.BOLD, accent);
            float textWidth = com.lowagie.text.pdf.ColumnText.getWidth(new Phrase(classification, badgeTextFont));
            float padX = 16f, padY = 6f, pillH = 11f + padY * 2;
            float pillW = textWidth + padX * 2;
            float pillY = afterTitleY - 16f - pillH;
            float pillX = (pageW - pillW) / 2f;
            over.setRGBColorFillF(1f, 1f, 1f);
            over.roundRectangle(pillX, pillY, pillW, pillH, pillH / 2f);
            over.fill();
            com.lowagie.text.pdf.ColumnText.showTextAligned(over, Element.ALIGN_CENTER,
                    new Phrase(classification, badgeTextFont), pageW / 2f, pillY + padY + 1f, 0);
            afterBadgeY = pillY;
        }

        StringBuilder byline = new StringBuilder();
        String author = rtc.interpolate("{{author}}");
        if (author != null && !author.isBlank()) byline.append(I18n.t("evidence.pdf.byline.prepared_by")).append(author).append('\n');
        String reviewer = rtc.variables().get("reviewer");
        if (reviewer != null && !reviewer.isBlank()) byline.append(I18n.t("evidence.pdf.byline.reviewer")).append(reviewer).append('\n');
        // Report Details' pinned date wins over "today" -- a report generated days after the
        // engagement shouldn't show the export date as if it were the assessment date.
        String pinnedDate = rtc.variables().get("date");
        String dateText = (pinnedDate != null && !pinnedDate.isBlank())
                ? pinnedDate
                : LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd/MM/yyyy"));
        byline.append(I18n.t("evidence.pdf.byline.date")).append(dateText).append('\n');
        byline.append(I18n.t("evidence.pdf.byline.report_id")).append(reportName);
        if (projectName != null) byline.append(I18n.t("evidence.pdf.byline.project")).append(projectName);
        String environment = rtc.interpolate("{{environment}}");
        if (environment != null && !environment.isBlank()) byline.append(I18n.t("evidence.pdf.byline.environment")).append(environment);

        byline.append('\n');
        String period = rtc.interpolate("{{assessment_period}}");
        if (period != null && !period.isBlank()) byline.append(I18n.t("evidence.pdf.byline.period")).append(period).append(" | ");
        String method = rtc.interpolate("{{method}}");
        if (method != null && !method.isBlank()) byline.append(I18n.t("evidence.pdf.byline.method")).append(method);
        
        // Anchored to the band's bottom edge, not chained off the badge — the byline belongs in
        // the white zone below the color block, not trailing immediately under it. Clamped
        // against afterBadgeY too, so a pathologically long wrapped title can't push the badge
        // low enough to collide with it.
        float bylineTop = Math.min(bandBottom - 24f, afterBadgeY - 20f);
        com.lowagie.text.pdf.ColumnText bylineCt = new com.lowagie.text.pdf.ColumnText(over);
        bylineCt.setSimpleColumn(new Phrase(byline.toString(), COVER_BYLINE_FONT),
                56f, 90f, pageW - 56f, bylineTop, 16f, Element.ALIGN_CENTER);
        bylineCt.go();

        doc.newPage();
    }

    /** Slim per-page footer. Content pages (2+) get "Page N" left / classification + product
     *  name right, under a thin gradient hairline echoing the cover's bar. The cover page (1)
     *  gets a centered "Generated by ICARUS" credit instead — same footer band, same fixed
     *  y-position regardless of how tall the cover's content above happens to be, rather than
     *  a hand-tuned spacer guessing at leftover page height. */
    private static final class FooterPageEvent extends com.lowagie.text.pdf.PdfPageEventHelper {
        private final ReportTemplateConfig rtc;
        private final Color accent;
        private final Color accent2;
        private final PdfReportGenerator owner;
        private Image icarusIcon;
        private boolean icarusIconLoadAttempted;

        FooterPageEvent(ReportTemplateConfig rtc, Color accent, Color accent2, PdfReportGenerator owner) {
            this.rtc = rtc;
            this.accent = accent;
            this.accent2 = accent2;
            this.owner = owner;
        }

        /** Lazily loaded, cached — the cover page is the only caller, but onEndPage has no
         *  throws clause (it overrides a plain PdfPageEvent method), so loading happens here
         *  where a failure can be caught and logged instead of propagating. */
        private Image icarusIcon() {
            if (!icarusIconLoadAttempted) {
                icarusIconLoadAttempted = true;
                try {
                    icarusIcon = loadLogo(rtc.logoPath(), "/icarus_logo.png");
                    if (icarusIcon != null) icarusIcon.scaleToFit(20f, 20f);
                } catch (Exception e) {
                    owner.api.logging().logToError("Failed to load ICARUS footer icon: " + e.getMessage());
                }
            }
            return icarusIcon;
        }

        @Override
        public void onEndPage(PdfWriter writer, Document doc) {
            com.lowagie.text.pdf.PdfContentByte cb = writer.getDirectContent();
            float y = doc.bottom() - 20f;

            fillGradient(cb, doc.left(), y + 14f, doc.right() - doc.left(), 1.2f, accent, accent2);

            if (writer.getPageNumber() == 1) {
                // A bit lower than the shared footer baseline so the larger icon still clears
                // the hairline above it.
                float coverY = y - 6f;
                Phrase creditText = new Phrase(I18n.t("evidence.pdf.footer.generated_by"), COVER_CREDIT_FONT);
                float textWidth = com.lowagie.text.pdf.ColumnText.getWidth(creditText);
                Image icon = icarusIcon();
                float iconWidth = icon != null ? icon.getScaledWidth() : 0f;
                float gap = icon != null ? 6f : 0f;
                float centerX = (doc.left() + doc.right()) / 2f;
                float startX = centerX - (iconWidth + gap + textWidth) / 2f;

                if (icon != null) {
                    icon.setAbsolutePosition(startX, coverY - 6f);
                    try {
                        cb.addImage(icon);
                    } catch (Exception e) {
                        owner.api.logging().logToError("Failed to draw ICARUS footer icon: " + e.getMessage());
                    }
                }
                com.lowagie.text.pdf.ColumnText.showTextAligned(cb, Element.ALIGN_LEFT,
                        creditText, startX + iconWidth + gap, coverY, 0);
                return;
            }

            com.lowagie.text.pdf.ColumnText.showTextAligned(cb, Element.ALIGN_LEFT,
                    new Phrase(I18n.t("evidence.pdf.footer.page") + writer.getPageNumber(), FOOTER_FONT), doc.left(), y, 0);

            String classification = rtc.interpolate("{{classification}}");
            String right = (classification != null && !classification.isBlank() ? classification + " — " : "")
                    + I18n.t("evidence.pdf.footer.report_suffix");
            com.lowagie.text.pdf.ColumnText.showTextAligned(cb, Element.ALIGN_RIGHT,
                    new Phrase(right, FOOTER_FONT), doc.right(), y, 0);
        }
    }

    private void appendHeader(Document doc, String reportName, String projectName, ReportTemplateConfig rtc) throws DocumentException {
        doc.add(new Paragraph(I18n.t("evidence.pdf.header.title"), TITLE_FONT));

        String subtitle = I18n.t("evidence.pdf.header.generated_on") + LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss"))
                + I18n.t("evidence.pdf.header.report_id") + reportName
                + (projectName != null ? I18n.t("evidence.pdf.header.project") + projectName : "");
        Paragraph sub = new Paragraph(subtitle, SUBTITLE_FONT);
        sub.setSpacingAfter(14f);
        doc.add(sub);
    }

    /** Renders the configured report sections (Settings → Reporting) as Markdown, in order,
     *  with {{variable}} interpolation — one bordered card per section, matching the old
     *  single "Executive Summary" card's look. Bookmarks added when {@code addBookmarks}. */
    /** @return true if the Findings block (summary + finding cards) was rendered here, at a
     *  {@link ReportTemplateConfig#FINDINGS_MARKER} section — caller must not append it again. */
    private boolean appendSections(Document doc, ReportTemplateConfig rtc, Color accent, PdfWriter writer, boolean addBookmarks, boolean retest,
                                    List<Finding> findings, Map<String, List<CapturedEvidence>> evidenceByHash, ModuleConfig config, EvidenceCapture capture) throws DocumentException {
        int i = 0;
        boolean findingsPlaced = false;
        for (ReportTemplateConfig.Section section : rtc.sections()) {
            if (retest && !ReportTemplateConfig.isMandatory(section.title()) && rtc.retestSuppressedSections().contains(section.title())) continue;
            i++;
            String destName = "section-" + i;

            if (section.title().equalsIgnoreCase(ReportTemplateConfig.FINDINGS_MARKER)) {
                if (addBookmarks) {
                    Chunk anchor = new Chunk("");
                    anchor.setLocalDestination(destName);
                    doc.add(new Paragraph(anchor));
                    new PdfOutline(writer.getRootOutline(), PdfAction.gotoLocalPage(destName, false), section.title());
                }
                appendSummary(doc, findings, accent);
                appendFindings(doc, findings, evidenceByHash, writer, addBookmarks, config, retest, capture, rtc);
                findingsPlaced = true;
                continue;
            }

            PdfPTable box = new PdfPTable(1);
            box.setWidthPercentage(100);
            box.setSpacingAfter(10f);
            PdfPCell cell = new PdfPCell();
            cell.setBackgroundColor(CARD_BG);
            cell.setBorderColor(BORDER);
            cell.setPadding(10f);

            Chunk titleChunk = new Chunk(section.title(), SECTION_FONT);
            if (addBookmarks) titleChunk.setLocalDestination(destName);
            Paragraph heading = new Paragraph(titleChunk);
            heading.setSpacingAfter(4f);
            cell.addElement(heading);

            if (section.title().equals(RISK_MATRIX_SECTION_TITLE)) {
                appendRiskMatrixBody(cell);
            } else if (section.title().equalsIgnoreCase(I18n.t("report.template.title.controle_documento"))) {
                appendControleDocumento(cell, rtc);
            } else if (section.title().equalsIgnoreCase(I18n.t("report.template.title.historico_revisoes"))) {
                appendHistoricoRevisoes(cell, rtc, section.content());
            } else if (section.title().equalsIgnoreCase(I18n.t("report.template.title.relacao_vulnerabilidades")) || section.content().trim().equals(ReportTemplateConfig.VULNERABILITY_SUMMARY_MARKER)) {
                appendVulnerabilitySummary(cell, findings, accent, retest);
            } else {
                var body = MarkdownPdfRenderer.render(markdownParser.parse(rtc.interpolate(section.content())), BODY_FONT, accent);
                for (Element el : body) cell.addElement(el);
            }

            box.addCell(cell);
            doc.add(box);

            if (addBookmarks) {
                new PdfOutline(writer.getRootOutline(), PdfAction.gotoLocalPage(destName, false), section.title());
            }
        }
        return findingsPlaced;
    }

    private void appendSummary(Document doc, List<Finding> findings, Color accent) throws DocumentException {
        Paragraph heading = new Paragraph(I18n.t("evidence.pdf.summary.title"), SECTION_FONT);
        heading.setSpacingAfter(6f);
        doc.add(heading);

        long critical = ReportGenerator.countBySeverity(findings, "CRITICAL");
        long high = ReportGenerator.countBySeverity(findings, "HIGH");
        long medium = ReportGenerator.countBySeverity(findings, "MEDIUM");
        long low = ReportGenerator.countBySeverity(findings, "LOW");

        PdfPTable table = new PdfPTable(5);
        table.setWidthPercentage(100);
        table.setSpacingAfter(14f);
        table.addCell(statCell(critical, I18n.t("evidence.pdf.summary.critical"), CRITICAL));
        table.addCell(statCell(high, I18n.t("evidence.pdf.summary.high"), HIGH));
        table.addCell(statCell(medium, I18n.t("evidence.pdf.summary.medium"), MEDIUM));
        table.addCell(statCell(low, I18n.t("evidence.pdf.summary.low_info"), LOW));
        table.addCell(statCell(findings.size(), I18n.t("evidence.pdf.summary.total"), accent));
        doc.add(table);
    }

    private PdfPCell statCell(long count, String label, Color accent) {
        PdfPCell cell = new PdfPCell();
        cell.setBackgroundColor(CARD_BG);
        cell.setBorderColor(BORDER);
        cell.setBorderWidthBottom(3f);
        cell.setBorderColorBottom(accent);
        cell.setPadding(8f);
        cell.setHorizontalAlignment(Element.ALIGN_CENTER);

        Paragraph num = new Paragraph(String.valueOf(count), STAT_NUM_FONT);
        num.setAlignment(Element.ALIGN_CENTER);
        Paragraph lbl = new Paragraph(label, STAT_LABEL_FONT);
        lbl.setAlignment(Element.ALIGN_CENTER);

        cell.addElement(num);
        cell.addElement(lbl);
        return cell;
    }

    private void appendFindings(Document doc, List<Finding> findings, Map<String, List<CapturedEvidence>> evidenceByHash, PdfWriter writer, boolean addBookmarks, ModuleConfig config, boolean retest, EvidenceCapture capture, ReportTemplateConfig rtc) throws DocumentException {
        doc.add(new Paragraph(I18n.t("evidence.pdf.findings.title"), SECTION_FONT));
        doc.add(Chunk.NEWLINE);

        int index = 1;
        for (Finding f : findings) {
            List<CapturedEvidence> group = evidenceByHash.getOrDefault(f.similarityHash(), List.of());
            String retestStatus = retest ? config.getString("retest.status." + f.similarityHash(), "") : "";
            appendFindingCard(doc, index, f, group, writer, addBookmarks, retestStatus, capture, rtc);
            index++;
        }
    }

    private void appendFindingCard(Document doc, int index, Finding f, List<CapturedEvidence> evidenceGroup, PdfWriter writer, boolean addBookmarks, String retestStatus, EvidenceCapture capture, ReportTemplateConfig rtc) throws DocumentException {
        // Everything about this finding — title/badge, meta rows, and the screenshot — is
        // built as rows of ONE PdfPTable, not separate doc.add() calls. A PdfPTable's own
        // rows always render in the order added, even when the table splits across a page
        // break; a standalone Image added via doc.add() after the table, by contrast, goes
        // through OpenPDF's separate "float" placement path, which can defer it to make
        // better use of leftover page space — that's what let a page break decouple a
        // description from its own image and land it out of order relative to other
        // findings. One table eliminates that path entirely instead of just discouraging it.
        PdfPTable card = new PdfPTable(new float[]{1, 4});
        card.setWidthPercentage(100);
        card.setSpacingBefore(8f);
        // Keeps each evidence image + its caption row from splitting across a page break —
        // the whole row moves to the next page as a unit instead.
        card.setSplitRows(false);

        String destName = "finding-" + index;
        Chunk titleChunk = new Chunk("#" + index + ". " + f.type(), FINDING_TITLE_FONT);
        if (addBookmarks) titleChunk.setLocalDestination(destName);
        PdfPCell titleCell = new PdfPCell(new Phrase(titleChunk));
        titleCell.setColspan(2);
        titleCell.setBorder(Rectangle.BOTTOM);
        titleCell.setBorderColor(BORDER);
        titleCell.setPaddingBottom(6f);
        card.addCell(titleCell);

        PdfPCell badgeLabelCell = new PdfPCell(new Phrase(I18n.t("evidence.pdf.findings.severity_label"), LABEL_FONT));
        badgeLabelCell.setBorderColor(BORDER);
        badgeLabelCell.setPadding(5f);
        card.addCell(badgeLabelCell);

        PdfPCell badgeCell = new PdfPCell(new Phrase(severityLabelPt(f.severity().name()), BADGE_FONT));
        badgeCell.setBackgroundColor(severityColor(f.severity().name()));
        badgeCell.setBorderColor(BORDER);
        badgeCell.setPadding(5f);
        card.addCell(badgeCell);

        addMetaRow(card, I18n.t("evidence.pdf.findings.module"), f.module());
        addMetaRow(card, I18n.t("evidence.pdf.findings.category"), f.category().name());
        addMetaRow(card, I18n.t("evidence.pdf.findings.target_path"), f.path());
        
        String cwe = f.cweIds().isEmpty() ? "" : String.join(", ", f.cweIds());
        if (!cwe.isEmpty()) {
            addMetaRow(card, I18n.t("evidence.pdf.findings.attack_reference"), cwe);
        }
        
        if (retestStatus != null && !retestStatus.isBlank()) {
            addMetaRow(card, I18n.t("evidence.pdf.findings.status"), retestStatus);
        }

        for (var entry : f.metadata().entrySet()) {
            if (!entry.getKey().equalsIgnoreCase("grc_id")) {
                addMetaRow(card, entry.getKey(), entry.getValue());
            }
        }
        
        addMetaRow(card, I18n.t("evidence.pdf.findings.description"), f.description());
        
        ReportTemplateConfig.FindingTemplate tmpl = rtc.getFindingTemplate(f.type());
        String impacto = (tmpl != null && tmpl.impacto() != null) ? tmpl.impacto() : "";
        String recomendacao = (tmpl != null && tmpl.recomendacao() != null) ? tmpl.recomendacao() : "";
        
        if (!impacto.isBlank()) addMetaRow(card, I18n.t("evidence.pdf.findings.impact"), impacto);
        if (!recomendacao.isBlank()) addMetaRow(card, I18n.t("evidence.pdf.findings.recommendation"), recomendacao);

        if (evidenceGroup.isEmpty()) {
            PdfPCell contentCell = new PdfPCell();
            contentCell.setColspan(2);
            contentCell.setBorder(Rectangle.NO_BORDER);
            contentCell.setPaddingTop(6f);
            Image autoRendered = autoRenderImage(f, capture);
            if (autoRendered != null) {
                float maxWidth = doc.getPageSize().getWidth() - doc.leftMargin() - doc.rightMargin() - 20f;
                autoRendered.scaleToFit(maxWidth, 700f);
                contentCell.addElement(autoRendered);
                Paragraph caption = new Paragraph(I18n.t("evidence.pdf.findings.auto_evidence_caption"), LABEL_FONT);
                caption.setSpacingBefore(4f);
                contentCell.addElement(caption);
            } else {
                contentCell.addElement(new Paragraph(I18n.t("evidence.pdf.findings.no_evidence_caption"), BODY_FONT));
            }
            card.addCell(contentCell);
        } else {
            int evidenceIndex = 1;
            for (CapturedEvidence evidence : evidenceGroup) {
                // Image + its caption share one cell in a row of `card`, which has
                // setSplitRows(false) — the whole row (image + caption) moves to the next
                // page as a unit instead of a break landing between them.
                PdfPCell contentCell = new PdfPCell();
                contentCell.setColspan(2);
                contentCell.setBorder(Rectangle.NO_BORDER);
                contentCell.setPaddingTop(6f);
                try {
                    ByteArrayOutputStream png = new ByteArrayOutputStream();
                    javax.imageio.ImageIO.write(evidence.image(), "png", png);
                    Image img = Image.getInstance(png.toByteArray());
                    float maxWidth = doc.getPageSize().getWidth() - doc.leftMargin() - doc.rightMargin() - 20f;
                    img.scaleToFit(maxWidth, 700f);
                    contentCell.addElement(img);
                } catch (IOException e) {
                    api.logging().logToError("Failed to embed evidence image in PDF for finding '" + f.type() + "': " + e);
                    contentCell.addElement(new Paragraph(I18n.t("evidence.pdf.findings.evidence_error"), BODY_FONT));
                }
                String evidenceCaption = evidence.caption() == null ? "" : evidence.caption();
                Paragraph caption = new Paragraph(evidenceIndex + ". " + evidenceCaption, LABEL_FONT);
                caption.setSpacingBefore(4f);
                contentCell.addElement(caption);
                card.addCell(contentCell);
                evidenceIndex++;
            }
        }

        card.setSpacingAfter(12f);
        doc.add(card);

        if (addBookmarks) {
            new PdfOutline(writer.getRootOutline(), PdfAction.gotoLocalPage(destName, false), f.type());
        }
    }

    /** Renders the same evidence image capture_evidence would (see {@link EvidenceAutoRenderer}) for a
     *  finding nobody captured a screenshot for, so every reportable finding gets an image either way.
     *  {@code null} if the finding has no captured request to render from — that case still falls back
     *  to the placeholder text. */
    private Image autoRenderImage(Finding f, EvidenceCapture capture) {
        if (f.evidence() == null) return null;
        try {
            var buffered = EvidenceAutoRenderer.render(capture, f, true);
            var out = new ByteArrayOutputStream();
            javax.imageio.ImageIO.write(buffered, "png", out);
            return Image.getInstance(out.toByteArray());
        } catch (Exception e) {
            api.logging().logToError("Failed to auto-render evidence for '" + f.type() + "': " + e);
            return null;
        }
    }

    private void addMetaRow(PdfPTable table, String label, String value) {
        PdfPCell labelCell = new PdfPCell(new Phrase(label, LABEL_FONT));
        labelCell.setBorderColor(BORDER);
        labelCell.setPadding(5f);
        table.addCell(labelCell);

        PdfPCell valueCell = new PdfPCell(new Phrase(value == null ? "" : value, BODY_FONT));
        valueCell.setBorderColor(BORDER);
        valueCell.setPadding(5f);
        table.addCell(valueCell);
    }

    private Color severityColor(String severity) {
        return switch (severity) {
            case "CRITICAL" -> CRITICAL;
            case "HIGH" -> HIGH;
            case "MEDIUM" -> MEDIUM;
            case "LOW" -> LOW;
            case "FIXED" -> FIXED;
            case "NOT_FIXED" -> NOT_FIXED;
            default -> INFO;
        };
    }

    private static String severityLabelPt(String severity) {
        return switch (severity) {
            case "CRITICAL" -> I18n.t("evidence.pdf.severity.critical");
            case "HIGH" -> I18n.t("evidence.pdf.severity.high");
            case "MEDIUM" -> I18n.t("evidence.pdf.severity.medium");
            case "LOW" -> I18n.t("evidence.pdf.severity.low");
            case "FIXED" -> I18n.t("evidence.pdf.severity.fixed");
            case "NOT_FIXED" -> I18n.t("evidence.pdf.severity.not_fixed");
            default -> I18n.t("evidence.pdf.severity.info");
        };
    }

    /** Renders the fixed CVSS4-aligned risk classification content — probability, impact, and
     *  criticality/remediation-deadline tables — as real PdfPTables instead of parsed Markdown.
     *  This is the one section whose content can't flow through the generic Markdown-section
     *  mechanism: the actual required output is tabular, and MarkdownPdfRenderer deliberately
     *  doesn't support Markdown tables (see its own class doc) — building a general table
     *  renderer for content that's already fixed, known text would be pure speculative
     *  machinery. Matched by exact section title in {@link #appendSections}; renaming the
     *  section in Settings falls back to that section's own (plain-text) content instead. */
    private void appendRiskMatrixBody(PdfPCell cell) {
        Paragraph intro = new Paragraph(I18n.t("evidence.pdf.risk.intro"), BODY_FONT);
        intro.setSpacingAfter(10f);
        cell.addElement(intro);

        cell.addElement(subheading(I18n.t("evidence.pdf.risk.probability.title")));
        cell.addElement(riskTable(new String[] {
            I18n.t("evidence.pdf.risk.probability.desc_extremo"),
            I18n.t("evidence.pdf.risk.probability.desc_alto"),
            I18n.t("evidence.pdf.risk.probability.desc_medio"),
            I18n.t("evidence.pdf.risk.probability.desc_baixo")
        }));
        cell.addElement(caption(I18n.t("evidence.pdf.risk.probability.caption")));

        cell.addElement(subheading(I18n.t("evidence.pdf.risk.impact.title")));
        cell.addElement(riskTable(new String[] {
            I18n.t("evidence.pdf.risk.impact.desc_extremo"),
            I18n.t("evidence.pdf.risk.impact.desc_alto"),
            I18n.t("evidence.pdf.risk.impact.desc_medio"),
            I18n.t("evidence.pdf.risk.impact.desc_baixo")
        }));
        cell.addElement(caption(I18n.t("evidence.pdf.risk.impact.caption")));

        cell.addElement(subheading(I18n.t("evidence.pdf.risk.criticality.title")));
        cell.addElement(riskTable(new String[] {
            I18n.t("evidence.pdf.risk.criticality.desc_extremo"),
            I18n.t("evidence.pdf.risk.criticality.desc_alto"),
            I18n.t("evidence.pdf.risk.criticality.desc_medio"),
            I18n.t("evidence.pdf.risk.criticality.desc_baixo")
        }));
        cell.addElement(caption(I18n.t("evidence.pdf.risk.criticality.caption")));
    }

    private Paragraph subheading(String text) {
        Paragraph p = new Paragraph(text, SUBHEADING_FONT);
        p.setSpacingBefore(6f);
        p.setSpacingAfter(4f);
        return p;
    }

    private Paragraph caption(String text) {
        Paragraph p = new Paragraph(text, LABEL_FONT);
        p.setSpacingAfter(10f);
        return p;
    }

    private PdfPTable riskTable(String[] descriptions) {
        PdfPTable table = new PdfPTable(new float[]{1, 4});
        table.setWidthPercentage(100);
        String[] riskLevels = {
            I18n.t("evidence.pdf.risk_levels.extreme"),
            I18n.t("evidence.pdf.risk_levels.high"),
            I18n.t("evidence.pdf.risk_levels.medium"),
            I18n.t("evidence.pdf.risk_levels.low")
        };
        for (int i = 0; i < riskLevels.length; i++) {
            PdfPCell levelCell = new PdfPCell(new Phrase(riskLevels[i], RISK_LEVEL_FONT));
            levelCell.setBackgroundColor(riskLevelColors[i]);
            levelCell.setBorderColor(BORDER);
            levelCell.setPadding(6f);
            levelCell.setVerticalAlignment(Element.ALIGN_MIDDLE);
            table.addCell(levelCell);

            PdfPCell descCell = new PdfPCell(new Phrase(descriptions[i], BODY_FONT));
            descCell.setBorderColor(BORDER);
            descCell.setPadding(6f);
            table.addCell(descCell);
        }
        return table;
    }

    private void addTrackingHeader(PdfPTable table, String text) {
        PdfPCell cell = new PdfPCell(new Phrase(text, new Font(Font.HELVETICA, 9, Font.BOLD, Color.WHITE)));
        cell.setBackgroundColor(NAVY);
        cell.setBorderColor(BORDER);
        cell.setPadding(5f);
        cell.setHorizontalAlignment(Element.ALIGN_CENTER);
        table.addCell(cell);
    }

    private void addTrackingValue(PdfPTable table, String text) {
        PdfPCell cell = new PdfPCell(new Phrase(text, BODY_FONT));
        cell.setBorderColor(BORDER);
        cell.setPadding(5f);
        cell.setHorizontalAlignment(Element.ALIGN_CENTER);
        table.addCell(cell);
    }

    private void addTextBlock(PdfPCell container, String title, String content) {
        Paragraph pTitle = new Paragraph(title, LABEL_FONT);
        pTitle.setSpacingBefore(8f);
        container.addElement(pTitle);
        Paragraph pContent = new Paragraph(content != null && !content.isBlank() ? content : I18n.t("evidence.pdf.text_block.not_specified"), BODY_FONT);
        pContent.setSpacingBefore(4f);
        container.addElement(pContent);
    }

    private void appendControleDocumento(PdfPCell cell, ReportTemplateConfig rtc) {
        PdfPTable table = new PdfPTable(2);
        table.setWidthPercentage(100);
        try { table.setWidths(new float[]{30f, 70f}); } catch(Exception e) {}
        
        PdfPCell headerCell = new PdfPCell(new Phrase(I18n.t("evidence.pdf.doc_control.title"), new Font(Font.HELVETICA, 12, Font.BOLD, Color.WHITE)));
        headerCell.setBackgroundColor(NAVY);
        headerCell.setColspan(2);
        headerCell.setPadding(8f);
        table.addCell(headerCell);

        addMetaRow(table, I18n.t("evidence.pdf.doc_control.classification"), rtc.interpolate("{{classification}}"));
        addMetaRow(table, I18n.t("evidence.pdf.doc_control.project"), rtc.interpolate("{{project}}"));
        addMetaRow(table, I18n.t("evidence.pdf.doc_control.version"), rtc.interpolate("{{version}}"));
        addMetaRow(table, I18n.t("evidence.pdf.doc_control.date"), rtc.interpolate("{{date}}"));
        addMetaRow(table, I18n.t("evidence.pdf.doc_control.author"), rtc.interpolate("{{author}}"));
        addMetaRow(table, I18n.t("evidence.pdf.doc_control.reviewer"), rtc.interpolate("{{reviewer}}"));
        addMetaRow(table, I18n.t("evidence.pdf.doc_control.approver"), rtc.interpolate("{{approver}}"));
        addMetaRow(table, I18n.t("evidence.pdf.doc_control.report_title"), rtc.interpolate("{{report_title}}"));
        
        cell.addElement(table);
    }

    private void appendHistoricoRevisoes(PdfPCell cell, ReportTemplateConfig rtc, String content) {
        PdfPTable table = new PdfPTable(3);
        table.setWidthPercentage(100);
        try { table.setWidths(new float[]{20f, 50f, 30f}); } catch(Exception e) {}
        
        Font headerFont = new Font(Font.HELVETICA, 10, Font.BOLD, Color.WHITE);
        String[] headers = {
            I18n.t("evidence.pdf.rev_history.version_date"),
            I18n.t("evidence.pdf.rev_history.description"),
            I18n.t("evidence.pdf.rev_history.responsibles")
        };
        for (String h : headers) {
            PdfPCell hCell = new PdfPCell(new Phrase(h, headerFont));
            hCell.setBackgroundColor(NAVY);
            hCell.setPadding(6f);
            table.addCell(hCell);
        }
        
        String interpolated = rtc.interpolate(content);
        if (interpolated != null) {
            String[] lines = interpolated.split("\n");
            for (String line : lines) {
                line = line.trim();
                if (line.startsWith("*")) line = line.substring(1).trim();
                if (line.isEmpty()) continue;
                
                String[] parts = line.split("—", 2);
                String verDate = parts[0].replace("**", "").trim();
                String desc = "";
                String resp = "";
                if (parts.length > 1) {
                    String[] subParts = parts[1].split("\\.", 2);
                    desc = subParts[0].trim();
                    if (subParts.length > 1) resp = subParts[1].trim();
                }
                
                PdfPCell c1 = new PdfPCell(new Phrase(verDate, BODY_FONT)); c1.setPadding(5f); table.addCell(c1);
                PdfPCell c2 = new PdfPCell(new Phrase(desc, BODY_FONT)); c2.setPadding(5f); table.addCell(c2);
                PdfPCell c3 = new PdfPCell(new Phrase(resp, BODY_FONT)); c3.setPadding(5f); table.addCell(c3);
            }
        }
        
        cell.addElement(table);
    }

    private void appendVulnerabilitySummary(PdfPCell cell, List<Finding> findings, Color accent, boolean retest) {
        PdfPTable table;
        if (retest) {
            table = new PdfPTable(4);
            table.setWidthPercentage(100);
            try { table.setWidths(new float[]{10f, 50f, 20f, 20f}); } catch(Exception e) {}
        } else {
            table = new PdfPTable(3);
            table.setWidthPercentage(100);
            try { table.setWidths(new float[]{10f, 60f, 30f}); } catch(Exception e) {}
        }
        
        Font headerFont = new Font(Font.HELVETICA, 10, Font.BOLD, Color.WHITE);
        String[] headers = retest ? new String[]{
            I18n.t("evidence.pdf.vuln_summary.id"),
            I18n.t("evidence.pdf.vuln_summary.vulnerability"),
            I18n.t("evidence.pdf.vuln_summary.risk"),
            I18n.t("evidence.pdf.vuln_summary.status")
        } : new String[]{
            I18n.t("evidence.pdf.vuln_summary.id"),
            I18n.t("evidence.pdf.vuln_summary.vulnerability"),
            I18n.t("evidence.pdf.vuln_summary.risk")
        };
        for (String h : headers) {
            PdfPCell hCell = new PdfPCell(new Phrase(h, headerFont));
            hCell.setBackgroundColor(NAVY);
            hCell.setPadding(6f);
            table.addCell(hCell);
        }
        
        int id = 1;
        for (Finding f : findings) {
            PdfPCell c1 = new PdfPCell(new Phrase(String.valueOf(id++), BODY_FONT)); c1.setPadding(5f); table.addCell(c1);
            PdfPCell c2 = new PdfPCell(new Phrase(f.type(), BODY_FONT)); c2.setPadding(5f); table.addCell(c2);
            
            PdfPCell c3 = new PdfPCell(new Phrase(severityLabelPt(f.severity().name()), BADGE_FONT));
            c3.setBackgroundColor(severityColor(f.severity().name()));
            c3.setPadding(5f);
            c3.setHorizontalAlignment(Element.ALIGN_CENTER);
            table.addCell(c3);
            
            if (retest) {
                PdfPCell c4 = new PdfPCell(new Phrase(f.severity().name().equals("FIXED") ? I18n.t("evidence.pdf.vuln_summary.fixed") : I18n.t("evidence.pdf.vuln_summary.open"), BODY_FONT)); c4.setPadding(5f); table.addCell(c4);
            }
        }
        
        cell.addElement(table);
    }
}
