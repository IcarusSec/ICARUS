package icarus.report.render;

import com.lowagie.text.*;
import com.lowagie.text.Font;
import com.lowagie.text.Image;
import com.lowagie.text.pdf.*;
import icarus.core.I18n;
import icarus.report.model.BrandingConfig;
import icarus.report.model.CoverRendererId;

import java.awt.Color;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Executive Modern cover renderer featuring a full-bleed gradient band and elevated client card.
 */
public class GradientHeroCoverRenderer implements CoverRenderer {

    @Override
    public CoverRendererId id() {
        return CoverRendererId.GRADIENT_HERO;
    }

    @Override
    public void renderPdf(Document doc, PdfWriter writer, ReportRenderContext ctx) throws DocumentException {
        float pageW = doc.getPageSize().getWidth();
        float pageH = doc.getPageSize().getHeight();
        float bandHeight = pageH * 0.57f;
        float bandBottom = pageH - bandHeight;

        PdfContentByte under = writer.getDirectContentUnder();
        PdfContentByte over = writer.getDirectContent();

        Color primary = decodeColor(ctx.profile().pdfTheme().primaryHex(), new Color(0xFF, 0x66, 0x33));
        Color secondary = decodeColor(ctx.profile().pdfTheme().secondaryHex(), new Color(0x6E, 0x6E, 0x6E));

        fillGradient(under, 0f, bandBottom, pageW, bandHeight, primary, secondary);

        float cardW = 300f, cardH = 130f;
        float cardX = (pageW - cardW) / 2f;
        float cardY = pageH - 76f - cardH;
        over.setRGBColorFillF(1f, 1f, 1f);
        over.roundRectangle(cardX, cardY, cardW, cardH, 14f);
        over.fill();

        BrandingConfig branding = ctx.profile().branding();
        if (branding != null && branding.clientLogoPath() != null && !branding.clientLogoPath().isBlank()) {
            try {
                Path logoPath = Path.of(branding.clientLogoPath());
                if (Files.exists(logoPath)) {
                    Image clientLogo = Image.getInstance(logoPath.toAbsolutePath().toString());
                    clientLogo.scaleToFit(cardW - 56f, cardH - 44f);
                    clientLogo.setAbsolutePosition(
                        cardX + (cardW - clientLogo.getScaledWidth()) / 2f,
                        cardY + (cardH - clientLogo.getScaledHeight()) / 2f
                    );
                    over.addImage(clientLogo);
                }
            } catch (Exception ignored) {}
        }

        String title = ctx.data().reportTitle();
        if (title == null || title.isBlank()) {
            title = branding != null && !branding.documentTitle().isBlank() ? branding.documentTitle() : "Security Assessment Report";
        }

        Font titleFont = new Font(Font.HELVETICA, 28, Font.BOLD, Color.WHITE);
        Paragraph pTitle = new Paragraph(title, titleFont);
        pTitle.setAlignment(Element.ALIGN_CENTER);

        ColumnText ct = new ColumnText(over);
        ct.setSimpleColumn(30f, bandBottom + 20f, pageW - 30f, cardY - 15f);
        ct.addElement(pTitle);

        String projectName = ctx.data().projectName();
        if (projectName != null && !projectName.isBlank()) {
            Font projFont = new Font(Font.HELVETICA, 13, Font.NORMAL, new Color(245, 245, 245));
            Paragraph pProj = new Paragraph(projectName, projFont);
            pProj.setAlignment(Element.ALIGN_CENTER);
            pProj.setSpacingBefore(8f);
            ct.addElement(pProj);
        }
        ct.go();

        // Bottom white half: Classification pill & metadata
        String classification = branding != null && !branding.classification().isBlank() ? branding.classification() : "Confidencial";
        float badgeW = 160f, badgeH = 24f;
        float badgeX = (pageW - badgeW) / 2f;
        float badgeY = bandBottom - 40f;

        over.setRGBColorFillF(0.94f, 0.94f, 0.94f);
        over.roundRectangle(badgeX, badgeY, badgeW, badgeH, 12f);
        over.fill();

        Font badgeFont = new Font(Font.HELVETICA, 10, Font.BOLD, decodeColor(ctx.profile().pdfTheme().headingHex(), Color.DARK_GRAY));
        ColumnText ctBadge = new ColumnText(over);
        ctBadge.setSimpleColumn(badgeX, badgeY, badgeX + badgeW, badgeY + badgeH);
        Paragraph pBadge = new Paragraph(classification.toUpperCase(), badgeFont);
        pBadge.setAlignment(Element.ALIGN_CENTER);
        ctBadge.addElement(pBadge);
        ctBadge.go();

        doc.newPage();
    }

    @Override
    public void renderHtml(StringBuilder html, ReportRenderContext ctx) {
        String primary = ctx.profile().htmlTheme().primaryHex();
        String secondary = ctx.profile().htmlTheme().secondaryHex();
        String title = ctx.data().reportTitle();
        String project = ctx.data().projectName();
        BrandingConfig branding = ctx.profile().branding();

        html.append("<div class=\"cover-hero\" style=\"background: linear-gradient(135deg, ")
            .append(primary).append(" 0%, ").append(secondary).append(" 100%); ")
            .append("padding: 3rem 2rem; border-radius: 8px; color: #FFFFFF; margin-bottom: 2rem; text-align: center;\">\n");
        html.append("  <h1 style=\"color: #FFFFFF; margin: 0 0 0.5rem 0; font-size: 2.2rem;\">").append(escape(title)).append("</h1>\n");
        if (project != null && !project.isBlank()) {
            html.append("  <div style=\"font-size: 1.2rem; opacity: 0.9; margin-bottom: 1rem;\">").append(escape(project)).append("</div>\n");
        }
        if (branding != null && !branding.classification().isBlank()) {
            html.append("  <span style=\"display: inline-block; background: rgba(255,255,255,0.2); padding: 4px 12px; border-radius: 12px; font-weight: bold; font-size: 0.85rem;\">")
                .append(escape(branding.classification())).append("</span>\n");
        }
        html.append("</div>\n");
    }

    private static Color decodeColor(String hex, Color fallback) {
        if (hex == null || hex.isBlank()) return fallback;
        try {
            return Color.decode(hex.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    private static void fillGradient(PdfContentByte cb, float x, float y, float width, float height, Color from, Color to) {
        int steps = 120;
        float sliceH = height / steps;
        for (int i = 0; i < steps; i++) {
            float t = i / (float) (steps - 1);
            int r = Math.round(from.getRed() + t * (to.getRed() - from.getRed()));
            int g = Math.round(from.getGreen() + t * (to.getGreen() - from.getGreen()));
            int b = Math.round(from.getBlue() + t * (to.getBlue() - from.getBlue()));
            cb.setRGBColorFillF(r / 255f, g / 255f, b / 255f);
            cb.rectangle(x, y + i * sliceH, width, sliceH + 0.5f);
            cb.fill();
        }
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
