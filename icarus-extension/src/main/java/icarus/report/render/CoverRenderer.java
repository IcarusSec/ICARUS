package icarus.report.render;

import com.lowagie.text.Document;
import com.lowagie.text.DocumentException;
import com.lowagie.text.pdf.PdfWriter;
import icarus.report.model.CoverRendererId;

/**
 * Strategy interface for rendering report cover pages across PDF and HTML.
 */
public interface CoverRenderer {
    CoverRendererId id();

    void renderPdf(Document doc, PdfWriter writer, ReportRenderContext ctx) throws DocumentException;

    void renderHtml(StringBuilder html, ReportRenderContext ctx);
}
