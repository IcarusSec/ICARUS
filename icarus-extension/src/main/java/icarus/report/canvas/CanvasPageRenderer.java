package icarus.report.canvas;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.weisj.jsvg.SVGDocument;
import com.github.weisj.jsvg.parser.SVGLoader;
import com.lowagie.text.Document;
import com.lowagie.text.Image;
import com.lowagie.text.PageSize;
import com.lowagie.text.pdf.BaseFont;
import com.lowagie.text.pdf.PdfContentByte;
import com.lowagie.text.pdf.PdfGState;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import icarus.core.Severity;
import icarus.report.model.SectionNode;
import icarus.report.render.FindingView;
import icarus.report.render.ReportRenderContext;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.color.ColorSpace;
import java.awt.image.BufferedImage;
import java.awt.image.ColorConvertOp;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.imageio.ImageIO;

/**
 * Draws {@code CANVAS_PAGE} sections in HTML and PDF using the same millimetre
 * coordinates as the template studio. Fonts are the files shipped with the studio.
 */
public final class CanvasPageRenderer {
    /** Studio arrowhead: length and half-width in stroke widths (canvas.js ARROW_LEN, ARROW_HALF). */
    private static final float ARROW_LEN = 6.3f;
    private static final float ARROW_HALF = 2.35f;
    private static final Map<String, BaseFont> FONTS = new HashMap<>();

    private CanvasPageRenderer() {}

    public static void writePdf(ReportRenderContext ctx, Path output) throws java.io.IOException {
        Document doc = new Document(PageSize.A4, 0, 0, 0, 0);
        try (var fos = Files.newOutputStream(output)) {
            PdfWriter writer = PdfWriter.getInstance(doc, fos);
            doc.open();
            List<SectionNode> pages = canvasNodes(ctx);
            for (int i = 0; i < pages.size(); i++) {
                if (i > 0) doc.newPage();
                paintPdf(writer.getDirectContent(), CanvasPages.parse(pages.get(i)), ctx, i + 1, pages.size());
            }
            if (pages.isEmpty()) doc.add(new com.lowagie.text.Paragraph(" "));
            doc.close();
        } catch (Exception ex) {
            throw new java.io.IOException("Canvas PDF failed", ex);
        }
    }

    public static void writeHtml(ReportRenderContext ctx, Path output) throws java.io.IOException {
        Files.writeString(output, htmlDocument(ctx));
    }

    public static String htmlDocument(ReportRenderContext ctx) {
        List<SectionNode> pages = canvasNodes(ctx);
        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html><html><head><meta charset=\"utf-8\"><title>")
            .append(esc(ctx.profile().name())).append("</title><style>");
        html.append(fontFaces());
        html.append("@page{size:A4;margin:0}body{margin:0}");
        html.append(".sheet{width:210mm;height:297mm;position:relative;overflow:hidden;page-break-after:always}");
        html.append(".el{position:absolute;box-sizing:border-box;transform-origin:center center;overflow:hidden;white-space:pre-wrap}");
        html.append(".el svg,.el img{width:100%;height:100%;display:block}.tint{position:absolute;inset:0;mix-blend-mode:multiply}");
        html.append(".slot{display:flex;align-items:center;justify-content:center;height:100%;box-sizing:border-box;border:1px dashed #c5cad3;color:#667085;background:#f8fafc;font-family:sans-serif;font-size:9pt}");
        html.append("table{width:100%;border-collapse:collapse}th,td{border:1px solid #e5e7eb;padding:1.2mm 1.6mm;text-align:left;vertical-align:top}");
        html.append("</style></head><body>");
        for (int i = 0; i < pages.size(); i++) {
            CanvasPages.Page page = CanvasPages.parse(pages.get(i));
            html.append("<section class=\"sheet\" style=\"background:").append(esc(page.background())).append("\">");
            if (page.invalid()) {
                html.append("<p class=\"el\" style=\"left:16mm;top:20mm;width:170mm\">This page could not be read.</p>");
            } else {
                Map<String, String> vars = CanvasPages.variables(ctx, i + 1, pages.size());
                for (JsonNode element : CanvasPages.elements(page)) {
                    if (element.path("hidden").asBoolean(false)) continue;
                    html.append(elementHtml(element, vars, ctx));
                }
            }
            html.append("</section>");
        }
        html.append("</body></html>");
        return html.toString();
    }

    private static List<SectionNode> canvasNodes(ReportRenderContext ctx) {
        return ctx.profile().sections().enabledInOrder().stream().filter(CanvasPages::isCanvas).toList();
    }

    private static void paintPdf(PdfContentByte cb, CanvasPages.Page page, ReportRenderContext ctx, int number, int total) {
        float pageH = PageSize.A4.getHeight();
        float pageW = PageSize.A4.getWidth();
        cb.saveState();
        cb.setColorFill(color(page.background(), Color.WHITE));
        cb.rectangle(0, 0, pageW, pageH);
        cb.fill();
        cb.restoreState();
        if (page.invalid() || page.spec() == null) {
            drawNote(cb, "This page could not be read.", 16, 20);
            return;
        }
        Map<String, String> vars = CanvasPages.variables(ctx, number, total);
        for (JsonNode element : CanvasPages.elements(page)) {
            if (element.path("hidden").asBoolean(false)) continue;
            try {
                drawElement(cb, element, vars, ctx, pageH);
            } catch (Exception ex) {
                drawNote(cb, "One element was skipped.", mm(element.path("x").asDouble(16)), mm(element.path("y").asDouble(20)));
            }
        }
    }

    private static void drawElement(PdfContentByte cb, JsonNode el, Map<String, String> vars, ReportRenderContext ctx, float pageH) throws Exception {
        String type = CanvasPages.text(el, "type", "text");
        if ("line".equals(type)) {
            drawLine(cb, el, pageH);
            return;
        }
        float x = mm(el.path("x").asDouble(0));
        float y = mm(el.path("y").asDouble(0));
        float w = mm(el.path("w").asDouble(10));
        float h = mm(el.path("h").asDouble(10));
        float rotation = (float) el.path("rotation").asDouble(0);
        cb.saveState();
        // Local origin is the element centre. CSS rotate() is clockwise on a y-down page,
        // which is the same matrix as a PDF rotate once y has been flipped.
        float cx = x + w / 2f;
        float cy = pageH - (y + h / 2f);
        double rad = Math.toRadians(rotation);
        float cos = (float) Math.cos(rad);
        float sin = (float) Math.sin(rad);
        cb.concatCTM(cos, sin, -sin, cos, cx, cy);
        PdfGState opacity = new PdfGState();
        opacity.setFillOpacity((float) el.path("opacity").asDouble(1));
        if ("difference".equals(CanvasPages.text(el, "blend", "normal"))) opacity.setBlendMode(PdfGState.BM_DIFFERENCE);
        cb.setGState(opacity);
        switch (type) {
            case "shape" -> drawShape(cb, el, w, h);
            case "text" -> drawText(cb, el, vars, w, h);
            case "image" -> drawImage(cb, el, ctx, w, h);
            case "chart" -> drawChart(cb, el, ctx, w, h);
            case "table" -> drawTable(cb, el, ctx, vars, w, h);
            default -> drawTextRaw(cb, "Unsupported element (" + type.replaceAll("[^a-z0-9_-]", "") + ")", w, h, font("sans", 400), 9, Color.GRAY, "left");
        }
        cb.restoreState();
    }

    private static void drawShape(PdfContentByte cb, JsonNode el, float w, float h) {
        String shape = CanvasPages.text(el, "shape", "rect");
        cb.setColorFill(color(CanvasPages.text(el, "fill", "#111111"), Color.BLACK));
        if ("ellipse".equals(shape)) cb.ellipse(-w / 2, -h / 2, w / 2, h / 2);
        else if ("rect".equals(shape)) cb.rectangle(-w / 2, -h / 2, w, h);
        else polygon(cb, el.path("points"), w, h);
        cb.fill();
        double stroke = el.path("strokeWidth").asDouble(0);
        String strokeColor = CanvasPages.text(el, "stroke", "");
        if (stroke > 0 && !strokeColor.isBlank()) {
            // Rect and ellipse strokes are inset by half the width so the outer edge is the element box (box-shadow inset).
            float sw = (float) stroke * CanvasPages.MM;
            float inset = sw / 2f;
            cb.setColorStroke(color(strokeColor, Color.BLACK));
            cb.setLineWidth(sw);
            if ("ellipse".equals(shape)) cb.ellipse(-w / 2 + inset, -h / 2 + inset, w / 2 - inset, h / 2 - inset);
            else if ("rect".equals(shape)) cb.rectangle(-w / 2 + inset, -h / 2 + inset, w - sw, h - sw);
            else polygon(cb, el.path("points"), w, h);
            cb.stroke();
        }
    }

    private static void polygon(PdfContentByte cb, JsonNode points, float w, float h) {
        if (points == null || !points.isArray() || points.size() < 3) {
            cb.rectangle(-w / 2, -h / 2, w, h);
            return;
        }
        for (int i = 0; i < points.size(); i++) {
            float px = (float) (points.get(i).get(0).asDouble() * w - w / 2);
            float py = (float) (h / 2 - points.get(i).get(1).asDouble() * h);
            if (i == 0) cb.moveTo(px, py);
            else cb.lineTo(px, py);
        }
        cb.closePath();
    }

    private static void drawText(PdfContentByte cb, JsonNode el, Map<String, String> vars, float w, float h) throws Exception {
        String fill = CanvasPages.text(el, "fill", "");
        if (!fill.isBlank()) {
            cb.setColorFill(color(fill, Color.WHITE));
            cb.rectangle(-w / 2, -h / 2, w, h);
            cb.fill();
        }
        String value = CanvasPages.fill(CanvasPages.text(el, "text", ""), vars);
        BaseFont font = font(CanvasPages.text(el, "font", "sans"), el.path("weight").asInt(400));
        float size = (float) el.path("size").asDouble(12);
        if (el.path("italic").asBoolean(false)) cb.concatCTM(1, 0, 0.18f, 1, 0, 0);
        cb.setColorFill(color(CanvasPages.text(el, "color", "#1A1A1A"), Color.BLACK));
        float spacing = (float) el.path("letterSpacing").asDouble(0) * 0.75f;
        cb.setCharacterSpacing(spacing);
        drawTextRaw(cb, value, w, h, font, size, color(CanvasPages.text(el, "color", "#1A1A1A"), Color.BLACK), CanvasPages.text(el, "align", "left"), (float) el.path("lineHeight").asDouble(1.15), (float) el.path("padding").asDouble(0) * CanvasPages.MM);
    }

    private static void drawTextRaw(PdfContentByte cb, String value, float w, float h, BaseFont font, float size, Color ink, String align) {
        drawTextRaw(cb, value, w, h, font, size, ink, align, 1.15f, 0);
    }

    private static void drawTextRaw(PdfContentByte cb, String value, float w, float h, BaseFont font, float size, Color ink, String align, float lineHeight, float pad) {
        cb.saveState();
        cb.rectangle(-w / 2f, -h / 2f, w, h);
        cb.clip();
        cb.newPath();
        cb.setColorFill(ink);
        List<String> lines = new java.util.ArrayList<>();
        for (String paragraph : value.split("\\n", -1)) lines.addAll(wrap(paragraph, font, size, w - 2f * pad));
        float leading = size * Math.max(lineHeight, 0.7f);
        // CSS centres the em box in the line box, so a line-height below 1 lifts the glyphs.
        float halfLeading = (lineHeight - 1f) * size / 2f;
        float baseline = h / 2f - pad - halfLeading - font.getAscentPoint("Ag", size);
        for (String line : lines) {
            float textWidth = font.getWidthPoint(line, size);
            float tx = switch (align) {
                case "center" -> -textWidth / 2f;
                case "right" -> w / 2f - textWidth - pad;
                default -> -w / 2f + pad;
            };
            cb.beginText();
            cb.setFontAndSize(font, size);
            cb.setTextMatrix(tx, baseline);
            safeShow(cb, line);
            cb.endText();
            baseline -= leading;
        }
        cb.restoreState();
    }

    /** Greedy wrap like CSS pre-wrap with overflow-wrap:break-word: break at spaces, else inside a word. */
    private static List<String> wrap(String paragraph, BaseFont font, float size, float maxWidth) {
        List<String> rows = new java.util.ArrayList<>();
        StringBuilder row = new StringBuilder();
        for (String word : paragraph.split("(?<= )")) {
            if (row.length() > 0 && font.getWidthPoint((row + word).stripTrailing(), size) > maxWidth) {
                rows.add(row.toString());
                row.setLength(0);
            }
            for (int i = 0; i < word.length(); i++) {
                String next = row.toString() + word.charAt(i);
                if (row.length() > 0 && font.getWidthPoint(next.stripTrailing(), size) > maxWidth) {
                    rows.add(row.toString());
                    row.setLength(0);
                }
                row.append(word.charAt(i));
            }
        }
        rows.add(row.toString());
        return rows;
    }

    private static void safeShow(PdfContentByte cb, String line) {
        try {
            cb.showText(line);
        } catch (Exception ex) {
            StringBuilder ascii = new StringBuilder();
            for (int i = 0; i < line.length(); i++) {
                char ch = line.charAt(i);
                ascii.append(ch >= 32 && ch < 127 ? ch : ' ');
            }
            try { cb.showText(ascii.toString()); } catch (Exception ignored) { /* keep the rest of the page */ }
        }
    }

    private static void drawImage(PdfContentByte cb, JsonNode el, ReportRenderContext ctx, float w, float h) throws Exception {
        byte[] bytes = imageBytes(el, ctx);
        if (bytes == null) {
            // Same empty slot as the studio: #f8fafc fill, 1px dashed #c5cad3, 9pt #667085, centred.
            cb.setColorFill(new Color(0xf8, 0xfa, 0xfc));
            cb.rectangle(-w / 2, -h / 2, w, h);
            cb.fill();
            cb.setLineWidth(0.75f);
            cb.setLineDash(2.25f, 1.5f, 0);
            cb.setColorStroke(new Color(0xc5, 0xca, 0xd3));
            cb.rectangle(-w / 2 + 0.375f, -h / 2 + 0.375f, w - 0.75f, h - 0.75f);
            cb.stroke();
            float pad = (h - 9f * 1.15f) / 2f;
            drawTextRaw(cb, "Client logo", w, h, font("sans", 400), 9, new Color(0x66, 0x70, 0x85), "center", 1.15f, pad);
            return;
        }
        BufferedImage buffered = ImageIO.read(new ByteArrayInputStream(bytes));
        if (buffered == null) return;
        BufferedImage rgb = new BufferedImage(buffered.getWidth(), buffered.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D flatten = rgb.createGraphics();
        flatten.setColor(Color.WHITE);
        flatten.fillRect(0, 0, rgb.getWidth(), rgb.getHeight());
        flatten.drawImage(buffered, 0, 0, null);
        flatten.dispose();
        buffered = rgb;
        if (el.path("grayscale").asBoolean(false)) {
            buffered = new ColorConvertOp(ColorSpace.getInstance(ColorSpace.CS_GRAY), null).filter(buffered, null);
        }
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(buffered, "png", png);
        Image image = Image.getInstance(png.toByteArray());
        cb.saveState();
        clipBox(cb, el, w, h);
        float scale = Math.max(w / image.getWidth(), h / image.getHeight());
        float dw = image.getWidth() * scale;
        float dh = image.getHeight() * scale;
        cb.addImage(image, dw, 0, 0, dh, -dw / 2f, -dh / 2f);
        String tint = CanvasPages.text(el, "tint", "");
        if (!tint.isBlank()) {
            PdfGState tintState = new PdfGState();
            tintState.setBlendMode(PdfGState.BM_MULTIPLY);
            tintState.setFillOpacity((float) el.path("tintOpacity").asDouble(0.45));
            cb.setGState(tintState);
            cb.setColorFill(color(tint, Color.MAGENTA));
            cb.rectangle(-w / 2, -h / 2, w, h);
            cb.fill();
        }
        cb.restoreState();
    }

    private static void clipBox(PdfContentByte cb, JsonNode el, float w, float h) {
        String clip = CanvasPages.text(el, "clip", "rect");
        if ("ellipse".equals(clip)) cb.ellipse(-w / 2, -h / 2, w / 2, h / 2);
        else if ("triangle".equals(clip) || "parallelogram".equals(clip) || "polygon".equals(clip)) polygon(cb, el.path("points"), w, h);
        else cb.rectangle(-w / 2, -h / 2, w, h);
        cb.clip();
        cb.newPath();
    }

    private static byte[] imageBytes(JsonNode el, ReportRenderContext ctx) {
        String slot = CanvasPages.text(el, "slot", "");
        if ("clientLogo".equals(slot) || "companyLogo".equals(slot)) {
            var branding = ctx.profile().branding();
            String path = branding == null ? null : ("clientLogo".equals(slot) ? branding.clientLogoPath() : branding.companyLogoPath());
            if ((path == null || path.isBlank()) && branding != null) path = branding.companyLogoPath();
            if (path != null && !path.isBlank()) {
                try { return Files.readAllBytes(Path.of(path)); } catch (Exception ignored) { /* placeholder */ }
            }
        }
        String src = CanvasPages.text(el, "src", "");
        if (!src.startsWith("data:image/")) return null;
        int comma = src.indexOf(',');
        if (comma < 0) return null;
        String meta = src.substring(0, comma).toLowerCase(Locale.ROOT);
        String payload = src.substring(comma + 1);
        if (meta.contains("svg")) {
            try {
                String svg = meta.contains("base64")
                    ? new String(Base64.getDecoder().decode(payload.replaceAll("\\s+", "")), StandardCharsets.UTF_8)
                    : URLDecoder.decode(payload, StandardCharsets.UTF_8);
                String trimmed = svg.trim();
                if (!trimmed.startsWith("<svg") && !trimmed.startsWith("<?xml")) return null;
                return rasterSvg(trimmed);
            } catch (Exception ex) {
                return null;
            }
        }
        try { return Base64.getDecoder().decode(payload.replaceAll("\\s+", "")); }
        catch (IllegalArgumentException ex) { return null; }
    }

    /** Studio placeholders are small SVG drawings. PDF needs a bitmap. */
    private static byte[] rasterSvg(String svg) {
        try {
            SVGDocument document = new SVGLoader().load(new ByteArrayInputStream(svg.getBytes(StandardCharsets.UTF_8)));
            if (document == null || document.size() == null) return null;
            float sw = (float) document.size().getWidth();
            float sh = (float) document.size().getHeight();
            if (sw < 1 || sh < 1) return null;
            float scale = Math.min(4f, 900f / Math.max(sw, sh));
            int w = Math.max(1, Math.round(sw * scale));
            int h = Math.max(1, Math.round(sh * scale));
            BufferedImage buffered = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = buffered.createGraphics();
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, w, h);
            graphics.scale(scale, scale);
            document.render(null, graphics);
            graphics.dispose();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(buffered, "png", out);
            return out.toByteArray();
        } catch (Exception ex) {
            return null;
        }
    }

    private static void drawChart(PdfContentByte cb, JsonNode el, ReportRenderContext ctx, float w, float h) throws Exception {
        JsonNode cats = el.path("categories");
        if (!cats.isArray() || cats.isEmpty()) return;
        double[] values = chartValues(el, ctx);
        double max = 1;
        for (double value : values) max = Math.max(max, value);
        // Studio geometry: a 100-unit square. Line and pie keep it uniform and centred; bars stretch to the box.
        // Local coordinates are centred: X = (u - 50) * scale, Y = (50 - v) * scale, with (u, v) in studio units.
        String kind = CanvasPages.text(el, "chart", "bar");
        float s = Math.min(w, h) / 100f;
        if ("line".equals(kind)) {
            cb.setLineWidth(1.6f * s);
            cb.setColorStroke(new Color(0x25, 0x63, 0xEB));
            for (int i = 0; i < values.length; i++) {
                float u = values.length == 1 ? 50 : 8 + i * 84f / (values.length - 1);
                float v = (float) (78 - values[i] / max * 62);
                if (i == 0) cb.moveTo((u - 50) * s, (50 - v) * s);
                else cb.lineTo((u - 50) * s, (50 - v) * s);
            }
            cb.stroke();
            return;
        }
        if ("pie".equals(kind)) {
            double total = 0;
            for (double value : values) total += Math.max(0, value);
            if (total <= 0) return;
            float radius = 36f * s;
            double phi = 90;   // 12 o'clock; slices run clockwise like the studio's SVG arcs
            for (int i = 0; i < values.length; i++) {
                if (values[i] <= 0) continue;
                double sweep = values[i] / total * 360;
                cb.setColorFill(color(cats.get(i).path("color").asText("#64748B"), Color.GRAY));
                cb.moveTo(0, 0);
                int steps = Math.max(1, (int) Math.ceil(sweep / 2));
                for (int k = 0; k <= steps; k++) {
                    double a = Math.toRadians(phi - sweep * k / steps);
                    cb.lineTo((float) (radius * Math.cos(a)), (float) (radius * Math.sin(a)));
                }
                cb.closePath();
                cb.fill();
                phi -= sweep;
            }
            return;
        }
        float gap = 0.03f * w;
        float barW = (0.88f * w - gap * (values.length - 1)) / values.length;
        float labelPt = (float) (chartLabelMm(el) * CanvasPages.MM);
        BaseFont label = font("sans", 400);
        float ascent = label.getAscentPoint("Ag", labelPt);
        float descent = -label.getDescentPoint("Ag", labelPt);
        for (int i = 0; i < values.length; i++) {
            float bh = values[i] > 0 ? Math.max(0.012f * h, (float) (values[i] / max * 0.62 * h)) : 0;
            float bx = -w / 2f + 0.06f * w + i * (barW + gap);
            float baseY = -h / 2f + 0.22f * h;
            if (bh > 0) {
                cb.setColorFill(color(cats.get(i).path("color").asText("#64748B"), Color.GRAY));
                cb.rectangle(bx, baseY, barW, bh);
                cb.fill();
            }
            // Label centre at 92% from the top; the CSS line box (line-height 1) is centred on it.
            float centre = -h / 2f + 0.08f * h;
            float baseline = centre + labelPt / 2f - ((labelPt - (ascent + descent)) / 2f + ascent);
            cb.beginText();
            cb.setFontAndSize(label, labelPt);
            cb.setColorFill(new Color(0x4B, 0x55, 0x63));
            cb.showTextAligned(com.lowagie.text.Element.ALIGN_CENTER, cats.get(i).path("label").asText(""), bx + barW / 2, baseline, 0);
            cb.endText();
        }
    }

    private static long count(ReportRenderContext ctx, String key) {
        try {
            return ctx.data().getCount(Severity.valueOf(key.toUpperCase(Locale.ROOT)));
        } catch (Exception ex) {
            return 0;
        }
    }

    private static void drawTable(PdfContentByte cb, JsonNode el, ReportRenderContext ctx, Map<String, String> vars, float w, float h) throws Exception {
        List<JsonNode> columns = new java.util.ArrayList<>();
        if (el.path("columns").isArray()) el.path("columns").forEach(columns::add);
        if (columns.isEmpty()) return;
        PdfPTable table = new PdfPTable(columns.size());
        table.setTotalWidth(w);
        // Same defaults as the studio's tableHtml: one ink colour and size for head and body, bold head.
        String family = CanvasPages.text(el, "font", "sans");
        Color ink = color(CanvasPages.text(el, "color", "#1A1A1A"), Color.BLACK);
        float size = (float) el.path("size").asDouble(9);
        com.lowagie.text.Font head = new com.lowagie.text.Font(font(family, 700), size, com.lowagie.text.Font.NORMAL, color(headerInk(el), Color.BLACK));
        com.lowagie.text.Font body = new com.lowagie.text.Font(font(family, 400), size, com.lowagie.text.Font.NORMAL, ink);
        for (JsonNode column : columns) {
            PdfPCell cell = gridCell(new com.lowagie.text.Phrase(CanvasPages.fill(column.path("label").asText(""), vars), head));
            cell.setBackgroundColor(color(CanvasPages.text(el, "headerFill", "#F4F4F5"), new Color(0xf4, 0xf4, 0xf5)));
            table.addCell(cell);
        }
        int bodyRows = 0;
        if (!"static".equals(CanvasPages.text(el, "mode", "findings")) && ctx.data() != null) {
            for (FindingView finding : ctx.data().findings()) {
                bodyRows++;
                for (JsonNode column : columns) {
                    table.addCell(gridCell(new com.lowagie.text.Phrase(cell(finding, column.path("key").asText("")), body)));
                }
            }
        } else if (el.path("rows").isArray()) {
            for (JsonNode row : el.path("rows")) {
                bodyRows++;
                for (int i = 0; i < columns.size(); i++) {
                    String value = row.isArray() && row.size() > i ? row.get(i).asText("") : "";
                    table.addCell(gridCell(new com.lowagie.text.Phrase(value, body)));
                }
            }
        }
        if (bodyRows == 0) {
            // Studio: with no rows, one dash row spans every column.
            PdfPCell dash = gridCell(new com.lowagie.text.Phrase("\u2014", body));
            dash.setColspan(columns.size());
            table.addCell(dash);
        }
        table.writeSelectedRows(0, -1, -w / 2f, h / 2f, cb);
    }

    /** Studio cells: 1px #e5e7eb border (CSS px = 0.75pt) and padding 1.2mm 1.6mm. */
    private static PdfPCell gridCell(com.lowagie.text.Phrase phrase) {
        PdfPCell cell = new PdfPCell(phrase);
        cell.setBorderColor(new Color(0xe5, 0xe7, 0xeb));
        cell.setBorderWidth(0.75f);
        cell.setPaddingTop(1.2f * CanvasPages.MM);
        cell.setPaddingBottom(1.2f * CanvasPages.MM);
        cell.setPaddingLeft(1.6f * CanvasPages.MM);
        cell.setPaddingRight(1.6f * CanvasPages.MM);
        return cell;
    }

    private static String cell(FindingView finding, String key) {
        return switch (key == null ? "" : key.toLowerCase(Locale.ROOT)) {
            case "title", "finding" -> finding.title() == null ? "" : finding.title();
            case "severity" -> finding.severity() == null ? "" : finding.severity().name();
            case "path", "where" -> finding.path() == null ? "" : finding.path();
            case "cwe" -> finding.cweIds() == null ? "" : String.join(", ", finding.cweIds());
            case "category", "type" -> finding.category() == null ? "" : finding.category();
            default -> "";
        };
    }

    private static void drawLine(PdfContentByte cb, JsonNode el, float pageH) {
        float x1 = mm(el.path("x1").asDouble(0));
        float y1 = pageH - mm(el.path("y1").asDouble(0));
        float x2 = mm(el.path("x2").asDouble(10));
        float y2 = pageH - mm(el.path("y2").asDouble(0));
        float sw = (float) el.path("strokeWidth").asDouble(0.4) * CanvasPages.MM;
        cb.saveState();
        Color ink = color(CanvasPages.text(el, "stroke", "#1A1A1A"), Color.BLACK);
        cb.setColorStroke(ink);
        cb.setLineWidth(sw);
        float dx = x2 - x1;
        float dy = y2 - y1;
        float len = (float) Math.hypot(dx, dy);
        boolean arrow = el.path("arrow").asBoolean(false) && len >= 0.5f * CanvasPages.MM;
        float ux = len == 0 ? 1 : dx / len;
        float uy = len == 0 ? 0 : dy / len;
        float headLen = ARROW_LEN * sw;
        float bx = x2 - ux * headLen;
        float by = y2 - uy * headLen;
        // The shaft stops at the head's base, so the square cap cannot stick out past the tip.
        if (!arrow || len > headLen) {
            cb.moveTo(x1, y1);
            cb.lineTo(arrow ? bx : x2, arrow ? by : y2);
            cb.stroke();
        }
        if (arrow) {
            float half = ARROW_HALF * sw;
            cb.setColorFill(ink);
            cb.moveTo(x2, y2);
            cb.lineTo(bx - uy * half, by + ux * half);
            cb.lineTo(bx + uy * half, by - ux * half);
            cb.fill();
        }
        cb.restoreState();
    }

    private static void drawNote(PdfContentByte cb, String text, float xMm, float yMm) {
        try {
            cb.beginText();
            cb.setFontAndSize(font("sans", 400), 10);
            cb.setColorFill(Color.GRAY);
            cb.setTextMatrix(xMm, PageSize.A4.getHeight() - yMm);
            cb.showText(text);
            cb.endText();
        } catch (Exception ignored) {
            /* a skipped note must not abort the report */
        }
    }

    private static String elementHtml(JsonNode el, Map<String, String> vars, ReportRenderContext ctx) {
        String type = CanvasPages.text(el, "type", "text");
        if ("line".equals(type)) return lineHtml(el);
        String style = "left:" + num(el, "x") + "mm;top:" + num(el, "y") + "mm;width:" + num(el, "w") + "mm;height:" + num(el, "h") +
            "mm;transform:rotate(" + el.path("rotation").asDouble(0) + "deg);z-index:" + el.path("z").asInt(1) +
            ";opacity:" + el.path("opacity").asDouble(1);
        String inner = "";
        if ("text".equals(type)) {
            style += ";font-family:" + fontCss(CanvasPages.text(el, "font", "sans")) + ";font-size:" + el.path("size").asDouble(12) +
                "pt;font-weight:" + (el.path("weight").asInt(400) >= 600 ? 700 : 400) + ";color:" + esc(CanvasPages.text(el, "color", "#1A1A1A")) +
                ";text-align:" + esc(CanvasPages.text(el, "align", "left")) + ";line-height:" + el.path("lineHeight").asDouble(1.15) +
                ";letter-spacing:" + el.path("letterSpacing").asDouble(0) + "px";
            if ("difference".equals(CanvasPages.text(el, "blend", "normal"))) style += ";mix-blend-mode:difference";
            if (!CanvasPages.text(el, "fill", "").isBlank()) style += ";background:" + esc(CanvasPages.text(el, "fill", ""));
            inner = esc(CanvasPages.fill(CanvasPages.text(el, "text", ""), vars)).replace("\n", "<br>");
        } else if ("shape".equals(type)) {
            String shape = CanvasPages.text(el, "shape", "rect");
            // Strokes are millimetres, as in the PDF and the studio inspector.
            String stroke = CanvasPages.text(el, "stroke", "");
            double strokeMm = el.path("strokeWidth").asDouble(0);
            boolean outlined = strokeMm > 0 && !stroke.isBlank();
            if ("rect".equals(shape) || "ellipse".equals(shape)) {
                style += ";background:" + esc(CanvasPages.text(el, "fill", "#111111"));
                if ("ellipse".equals(shape)) style += ";border-radius:50%";
                if (outlined) style += ";box-shadow:inset 0 0 0 " + strokeMm + "mm " + esc(stroke);
            } else {
                // Stroke is centred on the edge; visible overflow keeps the bottom of a triangle from being clipped.
                style += ";overflow:visible";
                inner = "<svg viewBox=\"0 0 100 100\" preserveAspectRatio=\"none\" overflow=\"visible\"><polygon points=\"" + pointsAttr(el.path("points")) + "\" fill=\"" + esc(CanvasPages.text(el, "fill", "#111111")) + "\"" +
                    (outlined ? " stroke=\"" + esc(stroke) + "\" stroke-width=\"" + strokeMm * 96 / 25.4 + "\" vector-effect=\"non-scaling-stroke\"" : "") + "/></svg>";
            }
        } else if ("image".equals(type)) {
            String clip = clipCss(el);
            if (!clip.isBlank()) style += ";clip-path:" + clip;
            String src = CanvasPages.text(el, "src", "");
            inner = src.isBlank()
                ? "<div class=\"slot\">Client logo</div>"
                : "<img alt=\"\" src=\"" + esc(src) + "\" style=\"object-fit:cover;" + (el.path("grayscale").asBoolean(false) ? "filter:grayscale(1)" : "") + "\">";
            if (!CanvasPages.text(el, "tint", "").isBlank()) {
                inner += "<div class=\"tint\" style=\"background:" + esc(CanvasPages.text(el, "tint", "")) + ";opacity:" + el.path("tintOpacity").asDouble(0.45) + "\"></div>";
            }
        } else if ("chart".equals(type)) inner = chartSvg(el, ctx);
        else if ("table".equals(type)) inner = tableHtml(el, ctx, vars);
        else inner = "Unsupported element";
        return "<div class=\"el\" style=\"" + style + "\">" + inner + "</div>";
    }

    private static String lineHtml(JsonNode el) {
        double x1 = el.path("x1").asDouble();
        double y1 = el.path("y1").asDouble();
        double x2 = el.path("x2").asDouble();
        double y2 = el.path("y2").asDouble();
        double sw = el.path("strokeWidth").asDouble(0.4);
        double dx = x2 - x1;
        double dy = y2 - y1;
        double len = Math.hypot(dx, dy);
        boolean arrow = el.path("arrow").asBoolean(false) && len >= 0.5;
        double ux = len == 0 ? 1 : dx / len;
        double uy = len == 0 ? 0 : dy / len;
        double headLen = ARROW_LEN * sw;
        double half = ARROW_HALF * sw;
        double bx = x2 - ux * headLen;
        double by = y2 - uy * headLen;
        // Same box as the studio: both ends, plus the head's base and wings when it is an arrow.
        double pad = Math.max(1.2, sw + 1);
        double minX = Math.min(x1, x2);
        double minY = Math.min(y1, y2);
        double maxX = Math.max(x1, x2);
        double maxY = Math.max(y1, y2);
        if (arrow) {
            minX = Math.min(minX, Math.min(bx - uy * half, bx + uy * half));
            maxX = Math.max(maxX, Math.max(bx - uy * half, bx + uy * half));
            minY = Math.min(minY, Math.min(by + ux * half, by - ux * half));
            maxY = Math.max(maxY, Math.max(by + ux * half, by - ux * half));
        }
        double x = minX - pad;
        double y = minY - pad;
        double w = Math.max(pad * 2, maxX - minX + pad * 2);
        double h = Math.max(pad * 2, maxY - minY + pad * 2);
        String color = esc(CanvasPages.text(el, "stroke", "#1A1A1A"));
        StringBuilder head = new StringBuilder();
        double ex = x2;
        double ey = y2;
        boolean drawShaft = true;
        if (arrow) {
            if (len > headLen) { ex = bx; ey = by; } else drawShaft = false;
            head.append("<polygon points=\"").append(fmt(x2 - x)).append(',').append(fmt(y2 - y)).append(' ')
                .append(fmt(bx - uy * half - x)).append(',').append(fmt(by + ux * half - y)).append(' ')
                .append(fmt(bx + uy * half - x)).append(',').append(fmt(by - ux * half - y))
                .append("\" fill=\"").append(color).append("\"/>");
        }
        String shaft = drawShaft
            ? "<line x1=\"" + fmt(x1 - x) + "\" y1=\"" + fmt(y1 - y) + "\" x2=\"" + fmt(ex - x) + "\" y2=\"" + fmt(ey - y) + "\" stroke=\"" + color + "\" stroke-width=\"" + fmt(sw) + "\"/>"
            : "";
        return "<div class=\"el\" style=\"left:" + fmt(x) + "mm;top:" + fmt(y) + "mm;width:" + fmt(w) + "mm;height:" + fmt(h) + "mm;overflow:visible\">" +
            "<svg viewBox=\"0 0 " + fmt(w) + " " + fmt(h) + "\" overflow=\"visible\" aria-hidden=\"true\">" + shaft + head + "</svg></div>";
    }

    private static String chartSvg(JsonNode el, ReportRenderContext ctx) {
        JsonNode cats = el.path("categories");
        if (!cats.isArray() || cats.isEmpty()) return "";
        double[] values = chartValues(el, ctx);
        double max = 1;
        for (double value : values) max = Math.max(max, value);
        String kind = CanvasPages.text(el, "chart", "bar");
        if ("pie".equals(kind)) {
            // Same path as the studio: radius 36 around (50, 50), clockwise from 12 o'clock.
            double total = 0;
            for (double value : values) total += Math.max(0, value);
            StringBuilder paths = new StringBuilder();
            double angle = -Math.PI / 2;
            for (int i = 0; total > 0 && i < values.length; i++) {
                double slice = Math.max(0, values[i]) / total * Math.PI * 2;
                if (slice <= 0) continue;
                double end = angle + slice;
                String d = "M50 50 L" + fmt(50 + 36 * Math.cos(angle)) + " " + fmt(50 + 36 * Math.sin(angle)) + " A36 36 0 " + (slice > Math.PI ? 1 : 0) + " 1 " +
                    fmt(50 + 36 * Math.cos(end)) + " " + fmt(50 + 36 * Math.sin(end)) + " Z";
                paths.append("<path d=\"").append(d).append("\" fill=\"").append(esc(cats.get(i).path("color").asText("#64748B"))).append("\"/>");
                angle = end;
            }
            return "<svg viewBox=\"0 0 100 100\" role=\"img\">" + paths + "</svg>";
        }
        if ("line".equals(kind)) {
            StringBuilder d = new StringBuilder();
            for (int i = 0; i < values.length; i++) {
                double x = values.length == 1 ? 50 : 8 + i * (84.0 / (values.length - 1));
                double y = 78 - values[i] / max * 62;
                d.append(i == 0 ? "M" : " L").append(fmt(x)).append(' ').append(fmt(y));
            }
            return "<svg viewBox=\"0 0 100 100\" role=\"img\"><path d=\"" + d + "\" fill=\"none\" stroke=\"#2563EB\" stroke-width=\"1.6\"/></svg>";
        }
        double gap = 3;
        double barW = (88 - gap * (values.length - 1)) / values.length;
        double labelMm = chartLabelMm(el);
        StringBuilder bars = new StringBuilder();
        StringBuilder labels = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            JsonNode cat = cats.get(i);
            double bh = values[i] > 0 ? Math.max(1.2, values[i] / max * 62) : 0;
            double x = 6 + i * (barW + gap);
            bars.append("<rect x=\"").append(fmt(x)).append("\" y=\"").append(fmt(78 - bh)).append("\" width=\"").append(fmt(barW))
                .append("\" height=\"").append(fmt(bh)).append("\" fill=\"").append(esc(cat.path("color").asText("#64748B"))).append("\"/>");
            // Labels are HTML so preserveAspectRatio="none" cannot stretch them.
            labels.append("<span style=\"position:absolute;top:92%;left:").append(fmt(x + barW / 2))
                .append("%;transform:translate(-50%,-50%);white-space:nowrap;text-align:center;line-height:1;font-family:").append(esc(fontCss("sans")))
                .append(";font-size:").append(fmt(labelMm)).append("mm;color:#4B5563\">").append(esc(cat.path("label").asText(""))).append("</span>");
        }
        return "<svg viewBox=\"0 0 100 100\" preserveAspectRatio=\"none\">" + bars + "</svg>" + labels;
    }

    private static String tableHtml(JsonNode el, ReportRenderContext ctx, Map<String, String> vars) {
        JsonNode columns = el.path("columns");
        if (!columns.isArray()) return "";
        StringBuilder html = new StringBuilder("<table style=\"font-family:" + esc(fontCss(CanvasPages.text(el, "font", "sans"))) +
            ";font-size:" + el.path("size").asDouble(9) + "pt;color:" + esc(CanvasPages.text(el, "color", "#1A1A1A")) +
            "\"><thead style=\"background:" + esc(CanvasPages.text(el, "headerFill", "#F4F4F5")) + ";color:" + headerInk(el) + "\"><tr>");
        for (JsonNode column : columns) html.append("<th>").append(esc(CanvasPages.fill(column.path("label").asText(""), vars))).append("</th>");
        html.append("</tr></thead><tbody>");
        // Same branches as drawTable: findings unless the table is static, then the stored rows.
        int bodyRows = 0;
        if (!"static".equals(CanvasPages.text(el, "mode", "findings")) && ctx.data() != null) {
            for (FindingView finding : ctx.data().findings()) {
                bodyRows++;
                html.append("<tr>");
                for (JsonNode column : columns) html.append("<td>").append(esc(cell(finding, column.path("key").asText("")))).append("</td>");
                html.append("</tr>");
            }
        } else if (el.path("rows").isArray()) {
            for (JsonNode row : el.path("rows")) {
                bodyRows++;
                html.append("<tr>");
                for (int i = 0; i < columns.size(); i++) html.append("<td>").append(esc(row.isArray() && row.size() > i ? row.get(i).asText("") : "")).append("</td>");
                html.append("</tr>");
            }
        }
        if (bodyRows == 0) html.append("<tr><td colspan=\"").append(columns.size()).append("\">\u2014</td></tr>");
        return html.append("</tbody></table>").toString();
    }

    private static String clipCss(JsonNode el) {
        String clip = CanvasPages.text(el, "clip", "rect");
        if ("ellipse".equals(clip)) return "ellipse(50% 50% at 50% 50%)";
        if ("rect".equals(clip) || "none".equals(clip)) return "";
        return "polygon(" + pointsPercent(el.path("points")) + ")";
    }

    private static String pointsAttr(JsonNode points) {
        if (points == null || !points.isArray()) return "0,0 100,0 100,100 0,100";
        StringBuilder out = new StringBuilder();
        for (JsonNode point : points) {
            if (out.length() > 0) out.append(' ');
            out.append(point.get(0).asDouble() * 100).append(',').append(point.get(1).asDouble() * 100);
        }
        return out.toString();
    }

    private static String pointsPercent(JsonNode points) {
        if (points == null || !points.isArray()) return "0% 0%, 100% 0%, 100% 100%, 0% 100%";
        StringBuilder out = new StringBuilder();
        for (JsonNode point : points) {
            if (out.length() > 0) out.append(", ");
            out.append(point.get(0).asDouble() * 100).append("% ").append(point.get(1).asDouble() * 100).append('%');
        }
        return out.toString();
    }

    private static String fontCss(String id) {
        return switch (id) {
            case "serif" -> "'ICARUS Serif', Georgia, serif";
            case "mono" -> "'ICARUS Mono', ui-monospace, monospace";
            default -> "'ICARUS Sans', Inter, Helvetica, Arial, sans-serif";
        };
    }

    private static String fontFaces() {
        StringBuilder css = new StringBuilder();
        face(css, "ICARUS Sans", 400, "Inter-Regular.ttf");
        face(css, "ICARUS Sans", 700, "Inter-Bold.ttf");
        face(css, "ICARUS Serif", 400, "SourceSerif4-Regular.ttf");
        face(css, "ICARUS Serif", 700, "SourceSerif4-Bold.ttf");
        face(css, "ICARUS Mono", 400, "JetBrainsMono-Regular.ttf");
        return css.toString();
    }

    private static void face(StringBuilder css, String family, int weight, String file) {
        byte[] bytes = readFont(file);
        if (bytes == null) return;
        css.append("@font-face{font-family:'").append(family).append("';font-weight:").append(weight)
            .append(";src:url(data:font/ttf;base64,").append(Base64.getEncoder().encodeToString(bytes)).append(") format('truetype');}");
    }

    private static BaseFont font(String family, int weight) throws Exception {
        String file = switch (family) {
            case "serif" -> weight >= 600 ? "SourceSerif4-Bold.ttf" : "SourceSerif4-Regular.ttf";
            case "mono" -> "JetBrainsMono-Regular.ttf";
            default -> weight >= 600 ? "Inter-Bold.ttf" : "Inter-Regular.ttf";
        };
        BaseFont cached = FONTS.get(file);
        if (cached != null) return cached;
        byte[] bytes = readFont(file);
        BaseFont created = bytes == null
            ? BaseFont.createFont(BaseFont.HELVETICA, BaseFont.WINANSI, false)
            : BaseFont.createFont(file, BaseFont.IDENTITY_H, BaseFont.EMBEDDED, false, bytes, null);
        FONTS.put(file, created);
        return created;
    }

    private static byte[] readFont(String file) {
        try (var in = CanvasPageRenderer.class.getResourceAsStream("/icarus/report/fonts/" + file)) {
            return in == null ? null : in.readAllBytes();
        } catch (Exception ex) {
            return null;
        }
    }

    private static float mm(double value) { return (float) (value * CanvasPages.MM); }
    private static String num(JsonNode el, String field) { return String.valueOf(el.path(field).asDouble(0)); }

    /** Studio chart labels: 6% of the box's smaller side, or the size that fits a bar slot at 0.6em per character. */
    private static double chartLabelMm(JsonNode el) {
        JsonNode cats = el.path("categories");
        double w = el.path("w").asDouble(10);
        double h = el.path("h").asDouble(10);
        int n = Math.max(1, cats.size());
        int maxLen = 1;
        for (JsonNode cat : cats) maxLen = Math.max(maxLen, cat.path("label").asText("").length());
        double barWidth = w * (88 - 3 * (n - 1)) / 100 / n;
        return Math.min(0.06 * Math.min(w, h), barWidth / (0.6 * maxLen));
    }

    /** Bar, line and pie values: live finding counts unless the chart is static (the studio's chartRows rule). */
    private static double[] chartValues(JsonNode el, ReportRenderContext ctx) {
        JsonNode cats = el.path("categories");
        boolean bound = !"static".equals(CanvasPages.text(el, "binding", "findingsBySeverity"));
        double[] values = new double[cats.isArray() ? cats.size() : 0];
        for (int i = 0; i < values.length; i++) {
            JsonNode cat = cats.get(i);
            values[i] = bound ? count(ctx, cat.path("key").asText("")) : cat.path("value").asDouble(0);
        }
        return values;
    }

    private static String fmt(double value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }

    /** Studio table header text: the element colour if it has WCAG contrast >= 3 on headerFill, else white or #1A1A1A by fill luminance. */
    private static String headerInk(JsonNode el) {
        Color ink = color(CanvasPages.text(el, "color", "#1A1A1A"), Color.BLACK);
        Color fill = color(CanvasPages.text(el, "headerFill", "#F4F4F5"), new Color(0xf4, 0xf4, 0xf5));
        java.util.function.ToDoubleFunction<Color> luminance = c -> {
            double[] v = {c.getRed() / 255.0, c.getGreen() / 255.0, c.getBlue() / 255.0};
            for (int i = 0; i < 3; i++) v[i] = v[i] <= 0.03928 ? v[i] / 12.92 : Math.pow((v[i] + 0.055) / 1.055, 2.4);
            return 0.2126 * v[0] + 0.7152 * v[1] + 0.0722 * v[2];
        };
        double a = luminance.applyAsDouble(ink);
        double b = luminance.applyAsDouble(fill);
        if ((Math.max(a, b) + 0.05) / (Math.min(a, b) + 0.05) >= 3) return String.format("#%06X", ink.getRGB() & 0xFFFFFF);
        return b < 0.5 ? "#FFFFFF" : "#1A1A1A";
    }

    private static Color color(String hex, Color fallback) {
        if (hex != null && hex.matches("#[0-9a-fA-F]{6}")) return Color.decode(hex);
        return fallback;
    }

    private static String esc(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
