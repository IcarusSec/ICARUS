package icarus.report.render;

import com.lowagie.text.*;
import com.lowagie.text.Font;
import com.lowagie.text.Image;
import com.lowagie.text.pdf.*;
import icarus.core.Severity;
import icarus.evidence.MarkdownPdfRenderer;
import icarus.report.model.FindingField;
import icarus.report.model.FindingRendererId;

import java.awt.Color;

/**
 * Classic boxed table finding renderer with sharp grid lines and structured rows.
 */
public class TabularFindingRenderer implements FindingRenderer {

    @Override
    public FindingRendererId id() {
        return FindingRendererId.TABULAR;
    }

    @Override
    public void renderPdf(Document doc, PdfWriter writer, FindingView finding, ReportRenderContext ctx) throws DocumentException {
        PdfPTable table = new PdfPTable(new float[]{1.5f, 4.5f});
        table.setWidthPercentage(100);
        table.setSpacingBefore(12f);
        table.setSpacingAfter(12f);

        Color tableHeaderBg = decodeColor(ctx.profile().pdfTheme().tableHeaderHex(), new Color(230, 235, 245));
        Color sevColor = getSeverityColor(finding.severity(), ctx);

        // Row 1: ID & Title
        PdfPCell c1 = new PdfPCell(new Phrase("Vulnerability #" + finding.displayIndex(), new Font(Font.HELVETICA, 9, Font.BOLD, Color.DARK_GRAY)));
        c1.setBackgroundColor(tableHeaderBg);
        c1.setPadding(5f);

        PdfPCell c2 = new PdfPCell(new Phrase(finding.title(), new Font(Font.HELVETICA, 10, Font.BOLD, new Color(20, 20, 20))));
        c2.setPadding(5f);
        table.addCell(c1);
        table.addCell(c2);

        // Row 2: Severity & Category
        PdfPCell cSevLabel = new PdfPCell(new Phrase("Severity / Category", new Font(Font.HELVETICA, 9, Font.BOLD, Color.DARK_GRAY)));
        cSevLabel.setBackgroundColor(tableHeaderBg);
        cSevLabel.setPadding(5f);

        Paragraph pSev = new Paragraph();
        pSev.add(new Chunk(finding.severity().name() + " ", new Font(Font.HELVETICA, 9, Font.BOLD, sevColor)));
        pSev.add(new Chunk(" | " + finding.category() + " (" + finding.module() + ")", new Font(Font.HELVETICA, 9, Font.NORMAL, Color.BLACK)));
        PdfPCell cSevVal = new PdfPCell(pSev);
        cSevVal.setPadding(5f);
        table.addCell(cSevLabel);
        table.addCell(cSevVal);

        // Row 3: Description
        String desc = finding.getField(FindingField.DESCRIPTION);
        if (desc != null && !desc.isBlank()) {
            PdfPCell cDescLabel = new PdfPCell(new Phrase("Description", new Font(Font.HELVETICA, 9, Font.BOLD, Color.DARK_GRAY)));
            cDescLabel.setBackgroundColor(tableHeaderBg);
            cDescLabel.setPadding(5f);

            PdfPCell cDescVal = new PdfPCell();
            cDescVal.setPadding(5f);
            MarkdownPdfRenderer.renderToCell(desc, cDescVal, new Font(Font.HELVETICA, 9, Font.NORMAL, new Color(30, 30, 30)));
            table.addCell(cDescLabel);
            table.addCell(cDescVal);
        }

        // Evidence
        if (ctx.profile().content().includeEvidence() && !finding.evidence().isEmpty()) {
            PdfPCell cEvLabel = new PdfPCell(new Phrase("Evidence", new Font(Font.HELVETICA, 9, Font.BOLD, Color.DARK_GRAY)));
            cEvLabel.setBackgroundColor(tableHeaderBg);
            cEvLabel.setPadding(5f);

            PdfPCell cEvVal = new PdfPCell();
            cEvVal.setPadding(5f);
            for (EvidenceView ev : finding.evidence()) {
                if (ev.imageBytes() != null && ev.imageBytes().length > 0) {
                    try {
                        Image img = Image.getInstance(ev.imageBytes());
                        img.scaleToFit(doc.getPageSize().getWidth() - 120f, 380f);
                        img.setAlignment(Element.ALIGN_CENTER);
                        img.setSpacingBefore(4f);
                        cEvVal.addElement(img);
                    } catch (Exception ignored) {}
                }
            }
            table.addCell(cEvLabel);
            table.addCell(cEvVal);
        }

        doc.add(table);
    }

    @Override
    public void renderHtml(StringBuilder html, FindingView finding, ReportRenderContext ctx) {
        String sevHex = ctx.profile().htmlTheme().severityHex().getOrDefault(finding.severity(), "#CC2E2E");

        html.append("<table class=\"finding-table\" style=\"width: 100%; border-collapse: collapse; margin-bottom: 2rem; border: 1px solid var(--border);\">\n");
        html.append("  <tr style=\"background: var(--card-bg);\">\n");
        html.append("    <th style=\"padding: 8px 12px; border: 1px solid var(--border); width: 140px; text-align: left;\">#").append(finding.displayIndex()).append(" Title</th>\n");
        html.append("    <td style=\"padding: 8px 12px; border: 1px solid var(--border); font-weight: bold;\">").append(escape(finding.title())).append("</td>\n");
        html.append("  </tr>\n");

        html.append("  <tr>\n");
        html.append("    <th style=\"padding: 8px 12px; border: 1px solid var(--border); text-align: left;\">Severity</th>\n");
        html.append("    <td style=\"padding: 8px 12px; border: 1px solid var(--border); font-weight: bold; color: ").append(sevHex).append(";\">")
            .append(finding.severity().name()).append(" (").append(escape(finding.category())).append(")</td>\n");
        html.append("  </tr>\n");

        String desc = finding.getField(FindingField.DESCRIPTION);
        if (desc != null && !desc.isBlank()) {
            html.append("  <tr>\n");
            html.append("    <th style=\"padding: 8px 12px; border: 1px solid var(--border); text-align: left; vertical-align: top;\">Description</th>\n");
            html.append("    <td style=\"padding: 8px 12px; border: 1px solid var(--border); line-height: 1.6;\">")
                .append(escape(desc).replace("\n", "<br>")).append("</td>\n");
            html.append("  </tr>\n");
        }

        html.append("</table>\n");
    }

    private static Color decodeColor(String hex, Color fallback) {
        if (hex == null || hex.isBlank()) return fallback;
        try { return Color.decode(hex.trim()); } catch (Exception e) { return fallback; }
    }

    private static Color getSeverityColor(Severity s, ReportRenderContext ctx) {
        String hex = ctx.profile().pdfTheme().severityHex().get(s);
        if (hex != null && !hex.isBlank()) {
            try { return Color.decode(hex); } catch (Exception ignored) {}
        }
        return Color.GRAY;
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
