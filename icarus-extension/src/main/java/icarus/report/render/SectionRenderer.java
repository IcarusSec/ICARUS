package icarus.report.render;

import com.lowagie.text.Document;
import com.lowagie.text.DocumentException;
import com.lowagie.text.pdf.PdfWriter;
import icarus.report.model.SectionNode;

/**
 * Strategy interface for rendering named sections (e.g. Executive Summary, Risk Matrix, Document Control).
 */
public interface SectionRenderer {
    String id();

    void renderPdf(Document doc, PdfWriter writer, SectionNode node, ReportRenderContext ctx) throws DocumentException;

    void renderHtml(StringBuilder html, SectionNode node, ReportRenderContext ctx);
}
