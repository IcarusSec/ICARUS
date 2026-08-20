package icarus.evidence;

import burp.api.montoya.MontoyaApi;

import icarus.core.Finding;
import icarus.core.ModuleConfig;
import icarus.core.ReportTemplateConfig;
import icarus.evidence.EvidenceCapture.CapturedEvidence;

import org.commonmark.parser.Parser;

import org.openpdf.text.*;
import org.openpdf.text.pdf.PdfAction;
import org.openpdf.text.pdf.PdfOutline;
import org.openpdf.text.pdf.PdfPCell;
import org.openpdf.text.pdf.PdfPTable;
import org.openpdf.text.pdf.PdfWriter;

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
    private static final Font SECTION_FONT   = new Font(Font.HELVETICA, 14, Font.BOLD, new Color(0x1a, 0x1a, 0x1a));
    private static final Font BADGE_FONT     = new Font(Font.HELVETICA, 10, Font.BOLD, Color.WHITE);
    private static final Font FINDING_TITLE_FONT = new Font(Font.HELVETICA, 13, Font.BOLD, new Color(0x1a, 0x1a, 0x1a));
    private static final Font LABEL_FONT     = new Font(Font.HELVETICA, 9, Font.BOLD, new Color(0x66, 0x66, 0x66));
    private static final Font BODY_FONT      = new Font(Font.HELVETICA, 10, Font.NORMAL, new Color(0x20, 0x20, 0x20));
    private static final Font STAT_NUM_FONT  = new Font(Font.HELVETICA, 18, Font.BOLD, new Color(0x1a, 0x1a, 0x1a));
    private static final Font STAT_LABEL_FONT = new Font(Font.HELVETICA, 8, Font.NORMAL, new Color(0x66, 0x66, 0x66));

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

    /** Mirrors {@link ReportGenerator#generate}'s contract: same gating, same return meaning. */
    public boolean generate(List<Finding> findings, ModuleConfig config, EvidenceCapture capture, Path outputPdfFile) throws IOException {
        if (findings.isEmpty()) {
            return false;
        }
        // Worst-first, same rule as ReportGenerator — see its comment for why.
        findings = new java.util.ArrayList<>(findings);
        findings.sort(java.util.Comparator.comparingInt(f -> f.severity().ordinal()));

        // Grouped by Finding#similarityHash(), not identity — see ReportGenerator for why.
        var evidenceByHash = capture.groupedBySimilarityHash();

        Path reportDir = outputPdfFile.toAbsolutePath().getParent();
        Files.createDirectories(reportDir);

        ReportTemplateConfig rtc = ReportTemplateConfig.fromConfig(config);
        String projectName = rtc.variables().get("projectName");
        if (projectName == null || projectName.isBlank()) {
            projectName = null;
            if (config.getBool("evidence.include_project_name", true)) {
                String name = api.project().name();
                if (name != null && !name.isBlank()) projectName = name;
            }
        }
        Color accent = themeColor(rtc.primaryColor(), ACCENT);
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
            doc.open();

            appendHeader(doc, reportDir.getFileName().toString(), projectName, rtc);
            boolean findingsPlaced = appendSections(doc, rtc, accent, writer, addBookmarks, retest, findings, evidenceByHash, config);
            if (!findingsPlaced) {
                appendSummary(doc, findings, accent);
                appendFindings(doc, findings, evidenceByHash, writer, addBookmarks, config, retest);
            }
        } catch (DocumentException e) {
            throw new IOException("PDF generation failed", e);
        } finally {
            if (doc.isOpen()) doc.close();
        }

        api.logging().logToOutput("PDF Report generated at: " + outputPdfFile.toAbsolutePath());
        return true;
    }

    private void appendHeader(Document doc, String reportName, String projectName, ReportTemplateConfig rtc) throws DocumentException {
        doc.add(new Paragraph("ICARUS Security Report", TITLE_FONT));

        String subtitle = "Generated on: " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                + " | Report ID: " + reportName;
        Paragraph sub = new Paragraph(subtitle, SUBTITLE_FONT);
        sub.setSpacingAfter(8f);
        doc.add(sub);

        PdfPTable details = new PdfPTable(2);
        details.setWidthPercentage(60);
        details.setHorizontalAlignment(Element.ALIGN_LEFT);
        boolean any = false;
        any |= addMetaRowIfPresent(details, "Project", projectName);
        any |= addMetaRowIfPresent(details, "Author", rtc.variables().get("author"));
        any |= addMetaRowIfPresent(details, "Revisor", rtc.variables().get("revisor"));
        any |= addMetaRowIfPresent(details, "Ambient", rtc.variables().get("ambient"));
        any |= addMetaRowIfPresent(details, "Date", rtc.variables().get("reportDate"));
        if (any) {
            details.setSpacingAfter(14f);
            doc.add(details);
        }
    }

    /** Adds a label/value row via {@link #addMetaRow} unless {@code value} is blank; reports whether it added one. */
    private boolean addMetaRowIfPresent(PdfPTable table, String label, String value) {
        if (value == null || value.isBlank()) return false;
        addMetaRow(table, label, value);
        return true;
    }

    /** Renders the configured report sections (Settings → Reporting) as Markdown, in order,
     *  with {{variable}} interpolation — one bordered card per section, matching the old
     *  single "Executive Summary" card's look. Bookmarks added when {@code addBookmarks}.
     *  A section titled {@link ReportTemplateConfig#FINDINGS_MARKER} renders the Findings
     *  block (summary + finding cards) in its place instead of Markdown content.
     *  @return true if the Findings block was rendered here (caller must not append it again). */
    private boolean appendSections(Document doc, ReportTemplateConfig rtc, Color accent, PdfWriter writer, boolean addBookmarks, boolean retest,
                                    List<Finding> findings, Map<String, List<CapturedEvidence>> evidenceByHash, ModuleConfig config) throws DocumentException {
        int i = 0;
        boolean findingsPlaced = false;
        for (ReportTemplateConfig.Section section : rtc.sections()) {
            if (retest && rtc.retestSuppressedSections().contains(section.title())) continue;
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
                appendFindings(doc, findings, evidenceByHash, writer, addBookmarks, config, retest);
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

            var body = MarkdownPdfRenderer.render(markdownParser.parse(rtc.interpolate(section.content())), BODY_FONT, accent);
            for (Element el : body) cell.addElement(el);

            box.addCell(cell);
            doc.add(box);

            if (addBookmarks) {
                new PdfOutline(writer.getRootOutline(), PdfAction.gotoLocalPage(destName, false), section.title());
            }
        }
        return findingsPlaced;
    }

    private void appendSummary(Document doc, List<Finding> findings, Color accent) throws DocumentException {
        long critical = ReportGenerator.countBySeverity(findings, "CRITICAL");
        long high = ReportGenerator.countBySeverity(findings, "HIGH");
        long medium = ReportGenerator.countBySeverity(findings, "MEDIUM");
        long low = ReportGenerator.countBySeverity(findings, "LOW");

        PdfPTable table = new PdfPTable(5);
        table.setWidthPercentage(100);
        table.setSpacingAfter(14f);
        table.addCell(statCell(critical, "CRITICAL", CRITICAL));
        table.addCell(statCell(high, "HIGH", HIGH));
        table.addCell(statCell(medium, "MEDIUM", MEDIUM));
        table.addCell(statCell(low, "LOW/INFO", LOW));
        table.addCell(statCell(findings.size(), "TOTAL FINDINGS", accent));
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

    private void appendFindings(Document doc, List<Finding> findings, Map<String, List<CapturedEvidence>> evidenceByHash, PdfWriter writer, boolean addBookmarks, ModuleConfig config, boolean retest) throws DocumentException {
        doc.add(new Paragraph("Findings", SECTION_FONT));
        doc.add(Chunk.NEWLINE);

        int index = 1;
        for (Finding f : findings) {
            List<CapturedEvidence> group = evidenceByHash.getOrDefault(f.similarityHash(), List.of());
            String retestStatus = retest ? config.getString("retest.status." + f.similarityHash(), "") : "";
            appendFindingCard(doc, index, f, group, writer, addBookmarks, retestStatus, config);
            index++;
        }
    }

    private void appendFindingCard(Document doc, int index, Finding f, List<CapturedEvidence> evidenceGroup, PdfWriter writer, boolean addBookmarks, String retestStatus, ModuleConfig config) throws DocumentException {
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

        PdfPCell badgeLabelCell = new PdfPCell(new Phrase("Severity", LABEL_FONT));
        badgeLabelCell.setBorderColor(BORDER);
        badgeLabelCell.setPadding(5f);
        card.addCell(badgeLabelCell);

        PdfPCell badgeCell = new PdfPCell(new Phrase(f.severity().name(), BADGE_FONT));
        badgeCell.setBackgroundColor(severityColor(f.severity().name()));
        badgeCell.setBorderColor(BORDER);
        badgeCell.setPadding(5f);
        card.addCell(badgeCell);

        addMetaRow(card, "Module", f.module());
        addMetaRow(card, "Category", f.category().name());
        addMetaRow(card, "Target Path", f.path());
        addMetaRow(card, "Description", f.description());
        if (!f.cweIds().isEmpty()) {
            addMetaRow(card, "CWE", String.join(", ", f.cweIds()));
        }
        if (retestStatus != null && !retestStatus.isBlank()) {
            addMetaRow(card, "Retest Status", retestStatus);
        }
        for (var entry : f.metadata().entrySet()) {
            addMetaRow(card, entry.getKey(), entry.getValue());
        }

        if (evidenceGroup.isEmpty()) {
            PdfPCell contentCell = new PdfPCell();
            contentCell.setColspan(2);
            contentCell.setBorder(Rectangle.NO_BORDER);
            contentCell.setPaddingTop(6f);
            Image autoRendered = autoRenderImage(f, config);
            if (autoRendered != null) {
                float maxWidth = doc.getPageSize().getWidth() - doc.leftMargin() - doc.rightMargin() - 20f;
                autoRendered.scaleToFit(maxWidth, 700f);
                contentCell.addElement(autoRendered);
                Paragraph caption = new Paragraph("Auto-rendered from the finding's captured traffic — no manual evidence was captured for it.", LABEL_FONT);
                caption.setSpacingBefore(4f);
                contentCell.addElement(caption);
            } else {
                contentCell.addElement(new Paragraph("No screenshot captured, and no evidence image could be auto-rendered for this finding.", BODY_FONT));
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
                    contentCell.addElement(new Paragraph("(screenshot could not be embedded)", BODY_FONT));
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
    private Image autoRenderImage(Finding f, ModuleConfig config) {
        if (f.evidence() == null) return null;
        try {
            var buffered = EvidenceAutoRenderer.render(api, config, f, true);
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
}
