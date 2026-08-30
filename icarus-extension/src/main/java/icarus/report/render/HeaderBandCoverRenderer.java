package icarus.report.render;

import com.lowagie.text.*;
import com.lowagie.text.Font;
import com.lowagie.text.pdf.*;
import icarus.report.model.BrandingConfig;
import icarus.report.model.CoverRendererId;

import java.awt.Color;

/**
 * Classic Technical cover renderer featuring a structured header band with clear typography.
 */
public class HeaderBandCoverRenderer implements CoverRenderer {

    @Override
    public CoverRendererId id() {
        return CoverRendererId.HEADER_BAND;
    }

    @Override
    public void renderPdf(Document doc, PdfWriter writer, ReportRenderContext ctx) throws DocumentException {
        float pageW = doc.getPageSize().getWidth();
        float pageH = doc.getPageSize().getHeight();
        float bandH = 140f;
        float bandY = pageH - bandH;

        PdfContentByte cb = writer.getDirectContent();
        Color primary = decodeColor(ctx.profile().pdfTheme().primaryHex(), new Color(0x00, 0x2F, 0x6C));

        cb.setColorFill(primary);
        cb.rectangle(0f, bandY, pageW, bandH);
        cb.fill();

        String title = ctx.data().reportTitle();
        if (title == null || title.isBlank()) {
            title = ctx.profile().branding() != null ? ctx.profile().branding().documentTitle() : "Security Assessment Report";
        }

        Font titleFont = new Font(Font.HELVETICA, 22, Font.BOLD, Color.WHITE);
        Paragraph pTitle = new Paragraph(title, titleFont);
        ColumnText ct = new ColumnText(cb);
        ct.setSimpleColumn(40f, bandY + 20f, pageW - 40f, pageH - 30f);
        ct.addElement(pTitle);

        String project = ctx.data().projectName();
        if (project != null && !project.isBlank()) {
            Font projFont = new Font(Font.HELVETICA, 12, Font.NORMAL, new Color(220, 230, 245));
            Paragraph pProj = new Paragraph(project, projFont);
            pProj.setSpacingBefore(6f);
            ct.addElement(pProj);
        }
        ct.go();

        doc.newPage();
    }

    @Override
    public void renderHtml(StringBuilder html, ReportRenderContext ctx) {
        String primary = ctx.profile().htmlTheme().primaryHex();
        String title = ctx.data().reportTitle();
        String project = ctx.data().projectName();

        html.append("<div class=\"cover-header-band\" style=\"border-bottom: 4px solid ").append(primary)
            .append("; padding-bottom: 1.5rem; margin-bottom: 2rem;\">\n");
        html.append("  <h1 style=\"color: ").append(primary).append("; margin: 0 0 0.5rem 0;\">").append(escape(title)).append("</h1>\n");
        if (project != null && !project.isBlank()) {
            html.append("  <div style=\"font-size: 1.1rem; color: #64748B;\">").append(escape(project)).append("</div>\n");
        }
        html.append("</div>\n");
    }

    private static Color decodeColor(String hex, Color fallback) {
        if (hex == null || hex.isBlank()) return fallback;
        try { return Color.decode(hex.trim()); } catch (Exception e) { return fallback; }
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
