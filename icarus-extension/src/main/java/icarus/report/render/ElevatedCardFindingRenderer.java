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
import java.util.Map;

/**
 * Modern elevated card finding renderer with severity banners, clean typography, and embedded evidence.
 */
public class ElevatedCardFindingRenderer implements FindingRenderer {

    @Override
    public FindingRendererId id() {
        return FindingRendererId.ELEVATED_CARD;
    }

    @Override
    public void renderPdf(Document doc, PdfWriter writer, FindingView finding, ReportRenderContext ctx) throws DocumentException {
        PdfPTable card = new PdfPTable(1);
        card.setWidthPercentage(100);
        card.setKeepTogether(false);
        card.setSpacingBefore(14f);
        card.setSpacingAfter(14f);

        Color sevColor = getSeverityColor(finding.severity(), ctx);
        Color cardBg = new Color(247, 247, 247);
        Color borderCol = new Color(220, 220, 220);

        PdfPCell cell = new PdfPCell();
        cell.setBackgroundColor(cardBg);
        cell.setBorderColor(borderCol);
        cell.setBorderWidth(1f);
        cell.setPadding(12f);

        // Header: Badge + Title
        PdfPTable header = new PdfPTable(new float[]{1.2f, 4.8f});
        header.setWidthPercentage(100);

        PdfPCell badgeCell = new PdfPCell(new Phrase(finding.severity().name(), new Font(Font.HELVETICA, 10, Font.BOLD, Color.WHITE)));
        badgeCell.setBackgroundColor(sevColor);
        badgeCell.setHorizontalAlignment(Element.ALIGN_CENTER);
        badgeCell.setVerticalAlignment(Element.ALIGN_MIDDLE);
        badgeCell.setPadding(4f);
        badgeCell.setBorder(Rectangle.NO_BORDER);

        String titleStr = "#" + finding.displayIndex() + ". " + finding.title();
        PdfPCell titleCell = new PdfPCell(new Phrase(titleStr, new Font(Font.HELVETICA, 12, Font.BOLD, new Color(26, 26, 26))));
        titleCell.setBorder(Rectangle.NO_BORDER);
        titleCell.setPaddingLeft(8f);
        titleCell.setVerticalAlignment(Element.ALIGN_MIDDLE);

        header.addCell(badgeCell);
        header.addCell(titleCell);
        cell.addElement(header);

        // Metadata grid
        PdfPTable meta = new PdfPTable(2);
        meta.setWidthPercentage(100);
        meta.setSpacingBefore(8f);

        addMetaRow(meta, "Category", finding.category());
        addMetaRow(meta, "Module", finding.module());
        if (!finding.cweIds().isEmpty()) {
            addMetaRow(meta, "CWE", String.join(", ", finding.cweIds()) + (finding.cweName().isBlank() ? "" : " - " + finding.cweName()));
        }
        if (!finding.path().isBlank()) {
            addMetaRow(meta, "Path / URL", finding.path());
        }
        cell.addElement(meta);

        // Description
        String desc = finding.getField(FindingField.DESCRIPTION);
        if (desc != null && !desc.isBlank()) {
            Paragraph pLabel = new Paragraph("Description", new Font(Font.HELVETICA, 10, Font.BOLD, Color.DARK_GRAY));
            pLabel.setSpacingBefore(8f);
            cell.addElement(pLabel);
            MarkdownPdfRenderer.renderToCell(desc, cell, new Font(Font.HELVETICA, 9, Font.NORMAL, new Color(30, 30, 30)));
        }

        // Additional fields (Why, When, Where, How, Impact, Remediation)
        for (FindingField f : new FindingField[]{FindingField.WHY, FindingField.WHEN, FindingField.WHERE, FindingField.HOW, FindingField.IMPACT, FindingField.REMEDIATION}) {
            String val = finding.getField(f);
            if (val != null && !val.isBlank()) {
                Paragraph pF = new Paragraph(f.name(), new Font(Font.HELVETICA, 9, Font.BOLD, Color.DARK_GRAY));
                pF.setSpacingBefore(6f);
                cell.addElement(pF);
                MarkdownPdfRenderer.renderToCell(val, cell, new Font(Font.HELVETICA, 9, Font.NORMAL, new Color(40, 40, 40)));
            }
        }

        // Evidence images
        if (ctx.profile().content().includeEvidence() && !finding.evidence().isEmpty()) {
            Paragraph pEv = new Paragraph("Evidence Screenshots", new Font(Font.HELVETICA, 10, Font.BOLD, Color.DARK_GRAY));
            pEv.setSpacingBefore(10f);
            cell.addElement(pEv);

            for (EvidenceView ev : finding.evidence()) {
                if (ev.imageBytes() != null && ev.imageBytes().length > 0) {
                    try {
                        Image img = Image.getInstance(ev.imageBytes());
                        img.scaleToFit(doc.getPageSize().getWidth() - 100f, 400f);
                        img.setAlignment(Element.ALIGN_CENTER);
                        img.setSpacingBefore(6f);
                        cell.addElement(img);

                        if (ev.caption() != null && !ev.caption().isBlank()) {
                            Paragraph pCap = new Paragraph(ev.caption(), new Font(Font.HELVETICA, 8, Font.ITALIC, Color.GRAY));
                            pCap.setAlignment(Element.ALIGN_CENTER);
                            cell.addElement(pCap);
                        }
                    } catch (Exception ignored) {}
                }
            }
        }

        card.addCell(cell);
        doc.add(card);
    }

    @Override
    public void renderHtml(StringBuilder html, FindingView finding, ReportRenderContext ctx) {
        String sevHex = ctx.profile().htmlTheme().severityHex().getOrDefault(finding.severity(), "#CC2E2E");

        html.append("<div class=\"finding-card\" style=\"border-left: 4px solid ").append(sevHex)
            .append("; background: var(--card-bg); border-radius: 6px; padding: 1.5rem; margin-bottom: 2rem; border-top: 1px solid var(--border); border-right: 1px solid var(--border); border-bottom: 1px solid var(--border);\">\n");
        
        html.append("  <div style=\"display: flex; align-items: center; gap: 1rem; margin-bottom: 1rem;\">\n");
        html.append("    <span style=\"background: ").append(sevHex).append("; color: #FFFFFF; font-weight: bold; font-size: 0.8rem; padding: 4px 10px; border-radius: 12px;\">")
            .append(finding.severity().name()).append("</span>\n");
        html.append("    <h3 style=\"margin: 0; font-size: 1.2rem;\">#").append(finding.displayIndex()).append(". ").append(escape(finding.title())).append("</h3>\n");
        html.append("  </div>\n");

        html.append("  <table style=\"width: 100%; font-size: 0.9rem; margin-bottom: 1rem;\">\n");
        html.append("    <tr><td style=\"width: 120px; font-weight: bold; color: var(--text-muted);\">Category:</td><td>").append(escape(finding.category())).append("</td></tr>\n");
        html.append("    <tr><td style=\"font-weight: bold; color: var(--text-muted);\">Module:</td><td>").append(escape(finding.module())).append("</td></tr>\n");
        if (!finding.cweIds().isEmpty()) {
            html.append("    <tr><td style=\"font-weight: bold; color: var(--text-muted);\">CWE:</td><td>").append(String.join(", ", finding.cweIds())).append("</td></tr>\n");
        }
        if (!finding.path().isBlank()) {
            html.append("    <tr><td style=\"font-weight: bold; color: var(--text-muted);\">Path:</td><td><code>").append(escape(finding.path())).append("</code></td></tr>\n");
        }
        html.append("  </table>\n");

        String desc = finding.getField(FindingField.DESCRIPTION);
        if (desc != null && !desc.isBlank()) {
            html.append("  <div class=\"finding-description\" style=\"margin-top: 1rem; line-height: 1.6;\">")
                .append(escape(desc).replace("\n", "<br>")).append("</div>\n");
        }

        if (ctx.profile().content().includeEvidence() && !finding.evidence().isEmpty()) {
            html.append("  <div class=\"finding-evidence\" style=\"margin-top: 1.5rem;\">\n");
            html.append("    <h4>Evidence</h4>\n");
            for (EvidenceView ev : finding.evidence()) {
                if (ev.imagePath() != null) {
                    html.append("    <div style=\"margin-bottom: 1rem; text-align: center;\">\n");
                    html.append("      <img src=\"").append(ev.imagePath().getFileName().toString()).append("\" style=\"max-width: 100%; border-radius: 4px; border: 1px solid var(--border);\" />\n");
                    if (ev.caption() != null && !ev.caption().isBlank()) {
                        html.append("      <p style=\"font-size: 0.85rem; color: var(--text-muted);\">").append(escape(ev.caption())).append("</p>\n");
                    }
                    html.append("    </div>\n");
                }
            }
            html.append("  </div>\n");
        }

        html.append("</div>\n");
    }

    private static void addMetaRow(PdfPTable table, String key, String val) {
        PdfPCell kCell = new PdfPCell(new Phrase(key + ":", new Font(Font.HELVETICA, 8, Font.BOLD, Color.GRAY)));
        kCell.setBorder(Rectangle.NO_BORDER);
        kCell.setPadding(2f);
        PdfPCell vCell = new PdfPCell(new Phrase(val, new Font(Font.HELVETICA, 8, Font.NORMAL, new Color(40, 40, 40))));
        vCell.setBorder(Rectangle.NO_BORDER);
        vCell.setPadding(2f);
        table.addCell(kCell);
        table.addCell(vCell);
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
