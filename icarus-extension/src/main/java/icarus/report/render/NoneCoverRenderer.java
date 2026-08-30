package icarus.report.render;

import com.lowagie.text.Document;
import com.lowagie.text.DocumentException;
import com.lowagie.text.pdf.PdfWriter;
import icarus.report.model.CoverRendererId;

/**
 * No-op cover renderer; execution begins directly with report sections on page 1.
 */
public class NoneCoverRenderer implements CoverRenderer {

    @Override
    public CoverRendererId id() {
        return CoverRendererId.NONE;
    }

    @Override
    public void renderPdf(Document doc, PdfWriter writer, ReportRenderContext ctx) throws DocumentException {
        // No dedicated cover page generated
    }

    @Override
    public void renderHtml(StringBuilder html, ReportRenderContext ctx) {
        // No dedicated cover banner
    }
}
