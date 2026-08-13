package icarus.evidence;

import burp.api.montoya.MontoyaApi;

import icarus.core.Finding;
import icarus.core.ModuleConfig;
import icarus.evidence.EvidenceCapture.CapturedEvidence;

import org.openpdf.text.*;
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
    private static final Color ACCENT   = new Color(0x3e, 0x7b, 0xb8);
    private static final Color BORDER   = new Color(0xdd, 0xdd, 0xdd);
    private static final Color CARD_BG  = new Color(0xf7, 0xf7, 0xf7);

    private final MontoyaApi api;

    public PdfReportGenerator(MontoyaApi api) {
        this.api = api;
    }

    /** Mirrors {@link ReportGenerator#generate}'s contract: same gating, same return meaning. */
    public boolean generate(List<Finding> findings, ModuleConfig config, EvidenceCapture capture, Path outputPdfFile) throws IOException {
        if (findings.isEmpty()) {
            return false;
        }

        // Grouped by Finding#similarityHash(), not identity — see ReportGenerator for why.
        var evidenceByHash = capture.groupedBySimilarityHash();

        Path reportDir = outputPdfFile.toAbsolutePath().getParent();
        Files.createDirectories(reportDir);

        String projectName = null;
        if (config.getBool("evidence.include_project_name", true)) {
            String name = api.project().name();
            if (name != null && !name.isBlank()) projectName = name;
        }

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

            appendHeader(doc, reportDir.getFileName().toString(), projectName);
            appendExecutiveSummary(doc, config.getString("evidence.executive_summary", ""));
            appendSummary(doc, findings);
            appendFindings(doc, findings, evidenceByHash);
        } catch (DocumentException e) {
            throw new IOException("PDF generation failed", e);
        } finally {
            if (doc.isOpen()) doc.close();
        }

        api.logging().logToOutput("PDF Report generated at: " + outputPdfFile.toAbsolutePath());
        return true;
    }

    private void appendHeader(Document doc, String reportName, String projectName) throws DocumentException {
        doc.add(new Paragraph("ICARUS Security Report", TITLE_FONT));

        String subtitle = "Generated on: " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                + " | Report ID: " + reportName
                + (projectName != null ? " | Project: " + projectName : "");
        Paragraph sub = new Paragraph(subtitle, SUBTITLE_FONT);
        sub.setSpacingAfter(14f);
        doc.add(sub);
    }

    private void appendExecutiveSummary(Document doc, String executiveSummary) throws DocumentException {
        if (executiveSummary == null || executiveSummary.isBlank()) return;

        PdfPTable box = new PdfPTable(1);
        box.setWidthPercentage(100);
        PdfPCell cell = new PdfPCell();
        cell.setBackgroundColor(CARD_BG);
        cell.setBorderColor(BORDER);
        cell.setPadding(10f);
        Paragraph heading = new Paragraph("Executive Summary", SECTION_FONT);
        heading.setSpacingAfter(4f);
        cell.addElement(heading);
        cell.addElement(new Paragraph(executiveSummary, BODY_FONT));
        box.addCell(cell);

        doc.add(box);
        doc.add(Chunk.NEWLINE);
    }

    private void appendSummary(Document doc, List<Finding> findings) throws DocumentException {
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
        table.addCell(statCell(findings.size(), "TOTAL FINDINGS", ACCENT));
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

    private void appendFindings(Document doc, List<Finding> findings, Map<String, List<CapturedEvidence>> evidenceByHash) throws DocumentException {
        doc.add(new Paragraph("Findings", SECTION_FONT));
        doc.add(Chunk.NEWLINE);

        int index = 1;
        for (Finding f : findings) {
            // Renders the first captured item per finding for now — full multi-evidence
            // rendering (looping the group) lands in Step 06.
            List<CapturedEvidence> group = evidenceByHash.get(f.similarityHash());
            CapturedEvidence first = (group == null || group.isEmpty()) ? null : group.get(0);
            appendFindingCard(doc, index++, f, first);
        }
    }

    private void appendFindingCard(Document doc, int index, Finding f, CapturedEvidence evidence) throws DocumentException {
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

        PdfPCell titleCell = new PdfPCell(new Phrase("#" + index + ". " + f.type(), FINDING_TITLE_FONT));
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
        for (var entry : f.metadata().entrySet()) {
            addMetaRow(card, entry.getKey(), entry.getValue());
        }

        PdfPCell contentCell = new PdfPCell();
        contentCell.setColspan(2);
        contentCell.setBorder(Rectangle.NO_BORDER);
        contentCell.setPaddingTop(6f);
        if (evidence != null) {
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
        } else {
            contentCell.addElement(new Paragraph("No screenshot captured for this finding.", BODY_FONT));
        }
        card.addCell(contentCell);

        card.setSpacingAfter(12f);
        doc.add(card);
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
            default -> INFO;
        };
    }
}
