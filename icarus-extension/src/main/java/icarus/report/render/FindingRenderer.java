package icarus.report.render;

import com.lowagie.text.Document;
import com.lowagie.text.DocumentException;
import com.lowagie.text.pdf.PdfWriter;
import icarus.report.model.FindingRendererId;

/**
 * Strategy interface for rendering individual finding cards/tables across PDF and HTML.
 */
public interface FindingRenderer {
    FindingRendererId id();

    void renderPdf(Document doc, PdfWriter writer, FindingView finding, ReportRenderContext ctx) throws DocumentException;

    void renderHtml(StringBuilder html, FindingView finding, ReportRenderContext ctx);
}
