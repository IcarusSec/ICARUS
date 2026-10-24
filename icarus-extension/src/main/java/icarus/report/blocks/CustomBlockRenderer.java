package icarus.report.blocks;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.weisj.jsvg.SVGDocument;
import com.github.weisj.jsvg.parser.SVGLoader;
import com.lowagie.text.Document;
import com.lowagie.text.DocumentException;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.Image;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.Rectangle;
import com.lowagie.text.pdf.BaseFont;
import com.lowagie.text.pdf.PdfContentByte;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfTemplate;
import com.lowagie.text.pdf.PdfWriter;
import icarus.core.Severity;
import icarus.report.model.FindingField;
import icarus.report.model.SectionNode;
import icarus.report.render.FindingView;
import icarus.report.render.ReportRenderContext;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import javax.imageio.ImageIO;

/**
 * Draws custom blocks into the profile HTML and PDF reports.
 * Charts bound to {@code findingsBySeverity} use {@link icarus.report.render.ReportData}
 * counts, which come from the Evidence Manager when a real report is generated.
 * Anything this build cannot draw becomes a short note. It does not abort the report.
 */
public final class CustomBlockRenderer {
    private static final Font TITLE = new Font(Font.HELVETICA, 12, Font.BOLD, new Color(0x1a, 0x1a, 0x1a));
    private static final Font NOTE = new Font(Font.HELVETICA, 9, Font.ITALIC, new Color(0x66, 0x66, 0x66));
    private static final Font BODY = new Font(Font.HELVETICA, 10, Font.NORMAL, new Color(0x20, 0x20, 0x20));

    private CustomBlockRenderer() {}

    public static void renderHtml(StringBuilder html, SectionNode node, ReportRenderContext ctx) {
        CustomBlock block = CustomBlockParser.parse(node);
        html.append("<section class=\"icarus-block\">");
        if (!block.title().isBlank()) html.append("<h2>").append(esc(block.title())).append("</h2>\n");
        if (block.invalid()) {
            html.append("<p class=\"icarus-block-note\">").append(esc(note(ctx, "invalid", null))).append("</p>");
        } else if (block.unsupported()) {
            html.append("<p class=\"icarus-block-note\">").append(esc(note(ctx, "unsupported", block.type()))).append("</p>");
        } else {
            switch (block.type()) {
                case "chart" -> html.append(chartSvg(block, ctx));
                case "table" -> html.append(tableHtml(block, ctx));
                case "drawing" -> html.append(drawingSvg(block));
                case "image" -> html.append(imageHtml(block));
                case "callout" -> html.append(calloutHtml(block));
                default -> html.append("<p class=\"icarus-block-note\">").append(esc(note(ctx, "unsupported", block.type()))).append("</p>");
            }
        }
        html.append("</section>\n");
    }

    public static void renderPdf(Document doc, PdfWriter writer, SectionNode node, ReportRenderContext ctx) throws DocumentException {
        CustomBlock block = CustomBlockParser.parse(node);
        if (!block.title().isBlank()) doc.add(new Paragraph(block.title(), TITLE));
        try {
            if (block.invalid()) {
                doc.add(new Paragraph(note(ctx, "invalid", null), NOTE));
                return;
            }
            if (block.unsupported()) {
                doc.add(new Paragraph(note(ctx, "unsupported", block.type()), NOTE));
                return;
            }
            switch (block.type()) {
                case "chart" -> doc.add(chartImage(writer, block, ctx));
                case "table" -> doc.add(tablePdf(block, ctx));
                case "drawing" -> addDrawing(doc, writer, block);
                case "image" -> addImage(doc, block);
                case "callout" -> doc.add(calloutPdf(block));
                default -> doc.add(new Paragraph(note(ctx, "unsupported", block.type()), NOTE));
            }
        } catch (DocumentException ex) {
            throw ex;
        } catch (Exception ex) {
            doc.add(new Paragraph(note(ctx, "invalid", null), NOTE));
        }
    }

    private static String note(ReportRenderContext ctx, String kind, String type) {
        boolean br = ctx != null && ctx.locale() != null && "pt".equals(ctx.locale().getLanguage());
        if ("invalid".equals(kind)) return br ? "Este bloco não pôde ser lido." : "This block could not be read.";
        String label = type == null ? "unknown" : type.replaceAll("[^a-z0-9_-]", "");
        return br ? "Bloco não suportado (" + label + ") omitido." : "Unsupported block (" + label + ") omitted.";
    }

    private static List<Category> categories(CustomBlock block, ReportRenderContext ctx) {
        JsonNode spec = block.spec();
        String binding = spec != null && spec.hasNonNull("binding") ? spec.get("binding").asText("") : "findingsBySeverity";
        boolean bound = !"static".equals(binding);
        List<Category> rows = new ArrayList<>();
        JsonNode list = spec == null ? null : spec.get("categories");
        if (list != null && list.isArray() && !list.isEmpty()) {
            for (JsonNode item : list) {
                String key = item.path("key").asText("item");
                String label = item.path("label").asText(key);
                double value = bound ? boundValue(ctx, key) : item.path("value").asDouble(0);
                rows.add(new Category(label, value, color(item.path("color").asText(null), key)));
            }
            return rows;
        }
        for (Severity severity : List.of(Severity.CRITICAL, Severity.HIGH, Severity.MEDIUM, Severity.LOW, Severity.INFO)) {
            String hex = ctx != null && ctx.profile() != null && ctx.profile().pdfTheme() != null
                ? ctx.profile().pdfTheme().severityHex().get(severity) : null;
            rows.add(new Category(severity.name(), boundValue(ctx, severity.name()), color(hex, severity.name())));
        }
        return rows;
    }

    private static double boundValue(ReportRenderContext ctx, String key) {
        if (ctx == null || ctx.data() == null) return 0;
        try {
            return ctx.data().getCount(Severity.valueOf(key.toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException ex) {
            return 0;
        }
    }

    private static String chartSvg(CustomBlock block, ReportRenderContext ctx) {
        String kind = block.spec() != null ? block.spec().path("chart").asText("bar") : "bar";
        List<Category> rows = categories(block, ctx);
        if ("pie".equals(kind)) return pieSvg(rows);
        if ("line".equals(kind)) return lineSvg(rows);
        return barSvg(rows);
    }

    private static String barSvg(List<Category> rows) {
        int width = 640;
        int height = 220;
        int padL = 28;
        int padB = 36;
        double max = rows.stream().mapToDouble(Category::value).max().orElse(1);
        if (max < 1) max = 1;
        double innerW = width - padL - 12;
        double innerH = height - 16 - padB;
        double gap = 10;
        double barW = Math.max(8, (innerW - gap * (rows.size() - 1)) / Math.max(1, rows.size()));
        StringBuilder svg = new StringBuilder();
        svg.append("<svg class=\"icarus-chart\" viewBox=\"0 0 ").append(width).append(' ').append(height).append("\" role=\"img\">");
        for (int i = 0; i < rows.size(); i++) {
            Category row = rows.get(i);
            double h = Math.max(row.value() > 0 ? 2 : 0, (row.value() / max) * innerH);
            double x = padL + i * (barW + gap);
            double y = 16 + innerH - h;
            svg.append("<rect data-count=\"").append((int) row.value()).append("\" x=\"").append(r(x))
                .append("\" y=\"").append(r(y)).append("\" width=\"").append(r(barW)).append("\" height=\"").append(r(h))
                .append("\" fill=\"").append(esc(hex(row.color()))).append("\"/>");
            svg.append("<text x=\"").append(r(x + barW / 2)).append("\" y=\"").append(height - 12)
                .append("\" text-anchor=\"middle\" font-size=\"11\" font-family=\"Helvetica\">").append(esc(row.label())).append("</text>");
        }
        svg.append("</svg>");
        return svg.toString();
    }

    private static String lineSvg(List<Category> rows) {
        StringBuilder svg = new StringBuilder("<svg class=\"icarus-chart\" viewBox=\"0 0 640 220\" role=\"img\"><path d=\"");
        double max = Math.max(1, rows.stream().mapToDouble(Category::value).max().orElse(1));
        for (int i = 0; i < rows.size(); i++) {
            double x = rows.size() == 1 ? 320 : 40 + i * (560.0 / (rows.size() - 1));
            double y = 20 + (1 - rows.get(i).value() / max) * 150;
            svg.append(i == 0 ? "M" : "L").append(r(x)).append(' ').append(r(y)).append(' ');
        }
        svg.append("\" fill=\"none\" stroke=\"#2563EB\" stroke-width=\"2\"/></svg>");
        return svg.toString();
    }

    private static String pieSvg(List<Category> rows) {
        double total = rows.stream().mapToDouble(row -> Math.max(0, row.value())).sum();
        if (total <= 0) return "<svg class=\"icarus-chart\" viewBox=\"0 0 120 120\"><circle cx=\"60\" cy=\"60\" r=\"40\" fill=\"#F3F4F6\"/></svg>";
        StringBuilder svg = new StringBuilder("<svg class=\"icarus-chart\" viewBox=\"0 0 120 120\" role=\"img\">");
        double angle = -Math.PI / 2;
        for (Category row : rows) {
            double slice = Math.max(0, row.value()) / total * Math.PI * 2;
            if (slice <= 0) continue;
            double end = angle + slice;
            double x1 = 60 + 40 * Math.cos(angle);
            double y1 = 60 + 40 * Math.sin(angle);
            double x2 = 60 + 40 * Math.cos(end);
            double y2 = 60 + 40 * Math.sin(end);
            int large = slice > Math.PI ? 1 : 0;
            svg.append("<path data-count=\"").append((int) row.value()).append("\" d=\"M60 60 L").append(r(x1)).append(' ').append(r(y1))
                .append(" A40 40 0 ").append(large).append(" 1 ").append(r(x2)).append(' ').append(r(y2))
                .append(" Z\" fill=\"").append(esc(hex(row.color()))).append("\"/>");
            angle = end;
        }
        svg.append("</svg>");
        return svg.toString();
    }

    private static Image chartImage(PdfWriter writer, CustomBlock block, ReportRenderContext ctx) throws Exception {
        List<Category> rows = categories(block, ctx);
        String kind = block.spec() != null ? block.spec().path("chart").asText("bar") : "bar";
        PdfTemplate template = writer.getDirectContent().createTemplate(460, 180);
        if ("pie".equals(kind)) drawPie(template, rows);
        else if ("line".equals(kind)) drawLine(template, rows);
        else drawBars(template, rows);
        Image image = Image.getInstance(template);
        image.scaleToFit(460, 170);
        image.setSpacingAfter(8f);
        return image;
    }

    private static void drawBars(PdfTemplate template, List<Category> rows) throws Exception {
        double max = Math.max(1, rows.stream().mapToDouble(Category::value).max().orElse(1));
        float gap = 8f;
        float barW = Math.max(10, (420f - gap * (rows.size() - 1)) / Math.max(1, rows.size()));
        BaseFont font = BaseFont.createFont(BaseFont.HELVETICA, BaseFont.WINANSI, false);
        for (int i = 0; i < rows.size(); i++) {
            Category row = rows.get(i);
            float h = (float) ((row.value() / max) * 120);
            float x = 20 + i * (barW + gap);
            template.setColorFill(row.color());
            template.rectangle(x, 28, barW, Math.max(h, row.value() > 0 ? 2 : 0));
            template.fill();
            template.beginText();
            template.setFontAndSize(font, 8);
            template.setColorFill(Color.DARK_GRAY);
            template.showTextAligned(Element.ALIGN_CENTER, fit(row.label(), 12), x + barW / 2, 12, 0);
            template.endText();
        }
    }

    private static void drawLine(PdfTemplate template, List<Category> rows) {
        double max = Math.max(1, rows.stream().mapToDouble(Category::value).max().orElse(1));
        template.setColorStroke(new Color(0x25, 0x63, 0xeb));
        template.setLineWidth(1.6f);
        for (int i = 0; i < rows.size(); i++) {
            float x = rows.size() == 1 ? 230 : 30 + i * (400f / (rows.size() - 1));
            float y = 28 + (float) (rows.get(i).value() / max * 120);
            if (i == 0) template.moveTo(x, y);
            else template.lineTo(x, y);
        }
        template.stroke();
    }

    private static void drawPie(PdfTemplate template, List<Category> rows) {
        double total = rows.stream().mapToDouble(row -> Math.max(0, row.value())).sum();
        if (total <= 0) {
            template.setColorFill(new Color(0xf3, 0xf4, 0xf6));
            template.circle(230, 90, 50);
            template.fill();
            return;
        }
        float start = 90f;
        for (Category row : rows) {
            if (row.value() <= 0) continue;
            float sweep = (float) (row.value() / total * 360);
            template.setColorFill(row.color());
            template.moveTo(230, 90);
            template.arc(180, 40, 280, 140, start, -sweep);
            template.lineTo(230, 90);
            template.fill();
            start -= sweep;
        }
    }

    private static PdfPTable tablePdf(CustomBlock block, ReportRenderContext ctx) {
        List<Col> columns = columns(block);
        List<List<String>> rows = rows(block, columns, ctx);
        PdfPTable table = new PdfPTable(Math.max(1, columns.size()));
        table.setWidthPercentage(100);
        table.setSpacingBefore(4f);
        table.setSpacingAfter(8f);
        for (Col column : columns) {
            PdfPCell cell = new PdfPCell(new Phrase(column.label(), new Font(Font.HELVETICA, 9, Font.BOLD, Color.DARK_GRAY)));
            cell.setBackgroundColor(new Color(0xf7, 0xf7, 0xf7));
            cell.setPadding(4f);
            table.addCell(cell);
        }
        if (rows.isEmpty()) {
            PdfPCell empty = new PdfPCell(new Phrase("—", BODY));
            empty.setColspan(Math.max(1, columns.size()));
            table.addCell(empty);
            return table;
        }
        for (List<String> row : rows) {
            for (int i = 0; i < columns.size(); i++) {
                PdfPCell cell = new PdfPCell(new Phrase(i < row.size() ? row.get(i) : "", BODY));
                cell.setPadding(4f);
                table.addCell(cell);
            }
        }
        return table;
    }

    private static String tableHtml(CustomBlock block, ReportRenderContext ctx) {
        List<Col> columns = columns(block);
        List<List<String>> rows = rows(block, columns, ctx);
        StringBuilder html = new StringBuilder("<table class=\"icarus-block-table\"><thead><tr>");
        for (Col column : columns) html.append("<th>").append(esc(column.label())).append("</th>");
        html.append("</tr></thead><tbody>");
        if (rows.isEmpty()) html.append("<tr><td colspan=\"").append(columns.size()).append("\">—</td></tr>");
        for (List<String> row : rows) {
            html.append("<tr>");
            for (int i = 0; i < columns.size(); i++) html.append("<td>").append(esc(i < row.size() ? row.get(i) : "")).append("</td>");
            html.append("</tr>");
        }
        html.append("</tbody></table>");
        return html.toString();
    }

    private static List<Col> columns(CustomBlock block) {
        List<Col> columns = new ArrayList<>();
        JsonNode list = block.spec() == null ? null : block.spec().get("columns");
        if (list != null && list.isArray()) {
            for (JsonNode item : list) {
                String key = item.path("key").asText("");
                if (key.isBlank()) continue;
                columns.add(new Col(key, item.path("label").asText(key)));
            }
        }
        if (columns.isEmpty()) {
            columns.add(new Col("title", "Finding"));
            columns.add(new Col("severity", "Severity"));
            columns.add(new Col("path", "Path"));
        }
        return columns;
    }

    private static List<List<String>> rows(CustomBlock block, List<Col> columns, ReportRenderContext ctx) {
        String mode = block.spec() != null ? block.spec().path("mode").asText("findings") : "findings";
        List<List<String>> rows = new ArrayList<>();
        if (!"static".equals(mode) && ctx != null && ctx.data() != null) {
            for (FindingView finding : ctx.data().findings()) {
                List<String> row = new ArrayList<>();
                for (Col column : columns) row.add(findingCell(finding, column.key()));
                rows.add(row);
            }
            return rows;
        }
        JsonNode raw = block.spec() == null ? null : block.spec().get("rows");
        if (raw != null && raw.isArray()) {
            for (JsonNode item : raw) {
                List<String> row = new ArrayList<>();
                if (item.isArray()) item.forEach(cell -> row.add(cell.asText("")));
                rows.add(row);
            }
        }
        return rows;
    }

    private static String findingCell(FindingView finding, String key) {
        String name = key == null ? "" : key.toLowerCase(Locale.ROOT);
        return switch (name) {
            case "title", "finding" -> safe(finding.title());
            case "severity" -> finding.severity() == null ? "" : finding.severity().name();
            case "path", "where" -> safe(finding.path());
            case "cwe" -> finding.cweIds() == null ? "" : String.join(", ", finding.cweIds());
            case "category", "type" -> safe(finding.category());
            case "description" -> safe(finding.getField(FindingField.DESCRIPTION));
            case "impact" -> safe(finding.getField(FindingField.IMPACT));
            case "remediation" -> safe(finding.getField(FindingField.REMEDIATION));
            case "evidence" -> safe(finding.getField(FindingField.EVIDENCE));
            default -> "";
        };
    }

    private static String calloutHtml(CustomBlock block) {
        String tone = block.text("tone", "info");
        if (!"warning".equals(tone) && !"note".equals(tone)) tone = "info";
        return "<aside class=\"icarus-callout " + tone + "\">" + esc(block.text("text", "")).replace("\n", "<br>") + "</aside>";
    }

    private static PdfPTable calloutPdf(CustomBlock block) {
        String tone = block.text("tone", "info");
        Color bg = "warning".equals(tone) ? new Color(0xff, 0xf8, 0xee) : "note".equals(tone) ? new Color(0xfa, 0xfa, 0xfa) : new Color(0xf5, 0xf8, 0xfd);
        PdfPTable table = new PdfPTable(1);
        table.setWidthPercentage(100);
        table.setSpacingBefore(4f);
        table.setSpacingAfter(8f);
        PdfPCell cell = new PdfPCell(new Phrase(block.text("text", ""), BODY));
        cell.setBackgroundColor(bg);
        cell.setBorderColor(new Color(0xd7, 0xe2, 0xf5));
        cell.setPadding(8f);
        table.addCell(cell);
        return table;
    }

    private static String imageHtml(CustomBlock block) {
        String src = safeDataUrl(block.text("dataUrl", ""));
        if (src.isEmpty()) return "<p class=\"icarus-block-note\">PNG, JPEG, GIF, or WebP.</p>";
        int width = block.spec() != null ? block.spec().path("widthMm").asInt(120) : 120;
        if (width < 20 || width > 170) width = 120;
        return "<img alt=\"" + esc(block.text("alt", "")) + "\" src=\"" + src + "\" style=\"width:" + width + "mm;max-width:100%\">";
    }

    private static void addImage(Document doc, CustomBlock block) throws Exception {
        byte[] bytes = dataBytes(block.text("dataUrl", ""));
        if (bytes == null) {
            doc.add(new Paragraph("PNG, JPEG, GIF, or WebP.", NOTE));
            return;
        }
        Image image = Image.getInstance(bytes);
        float width = block.spec() != null ? block.spec().path("widthMm").asInt(120) : 120;
        image.scaleToFit(width * 72f / 25.4f, 400);
        image.setSpacingAfter(6f);
        doc.add(image);
    }

    private static String drawingSvg(CustomBlock block) {
        String raw = block.text("svg", "");
        String safe = sanitizeSvg(raw);
        if (!safe.isBlank()) return safe;
        int w = block.spec() != null ? block.spec().path("width").asInt(520) : 520;
        int h = block.spec() != null ? block.spec().path("height").asInt(220) : 220;
        StringBuilder svg = new StringBuilder("<svg class=\"icarus-diagram\" viewBox=\"0 0 ").append(w).append(' ').append(h).append("\">");
        JsonNode shapes = block.spec() == null ? null : block.spec().get("shapes");
        if (shapes != null && shapes.isArray()) shapes.forEach(shape -> svg.append(shapeSvg(shape)));
        svg.append("</svg>");
        return svg.toString();
    }

    private static String shapeSvg(JsonNode shape) {
        String type = shape.path("type").asText("");
        if ("rect".equals(type)) {
            return "<rect x=\"" + shape.path("x").asDouble() + "\" y=\"" + shape.path("y").asDouble()
                + "\" width=\"" + shape.path("w").asDouble() + "\" height=\"" + shape.path("h").asDouble()
                + "\" fill=\"" + esc(shape.path("fill").asText("none")) + "\" stroke=\"" + esc(shape.path("stroke").asText("#1A1A1A")) + "\"/>";
        }
        if ("ellipse".equals(type)) {
            return "<ellipse cx=\"" + (shape.path("x").asDouble() + shape.path("w").asDouble() / 2)
                + "\" cy=\"" + (shape.path("y").asDouble() + shape.path("h").asDouble() / 2)
                + "\" rx=\"" + (shape.path("w").asDouble() / 2) + "\" ry=\"" + (shape.path("h").asDouble() / 2)
                + "\" fill=\"" + esc(shape.path("fill").asText("none")) + "\" stroke=\"" + esc(shape.path("stroke").asText("#1A1A1A")) + "\"/>";
        }
        if ("line".equals(type) || "arrow".equals(type)) {
            return "<line x1=\"" + shape.path("x1").asDouble() + "\" y1=\"" + shape.path("y1").asDouble()
                + "\" x2=\"" + shape.path("x2").asDouble() + "\" y2=\"" + shape.path("y2").asDouble()
                + "\" stroke=\"" + esc(shape.path("stroke").asText("#1A1A1A")) + "\"/>";
        }
        if ("text".equals(type)) {
            return "<text x=\"" + shape.path("x").asDouble() + "\" y=\"" + shape.path("y").asDouble()
                + "\" font-size=\"" + shape.path("size").asInt(14) + "\">" + esc(shape.path("text").asText("")) + "</text>";
        }
        if ("freehand".equals(type)) {
            JsonNode points = shape.get("points");
            if (points == null || !points.isArray() || points.size() < 2) return "";
            StringBuilder path = new StringBuilder("<path d=\"");
            for (int i = 0; i < points.size(); i++) {
                JsonNode point = points.get(i);
                double x = point.isArray() && !point.isEmpty() ? point.get(0).asDouble() : 0;
                double y = point.isArray() && point.size() > 1 ? point.get(1).asDouble() : 0;
                path.append(i == 0 ? "M" : "L").append(r(x)).append(' ').append(r(y)).append(' ');
            }
            return path.append("\" fill=\"none\" stroke=\"").append(esc(shape.path("stroke").asText("#1A1A1A"))).append("\"/>").toString();
        }
        return "";
    }

    private static void addDrawing(Document doc, PdfWriter writer, CustomBlock block) throws Exception {
        String raw = block.text("svg", "");
        if (!raw.isBlank()) {
            Image raster = rasterSvg(raw);
            if (raster != null) {
                raster.scaleToFit(460, 240);
                raster.setSpacingAfter(8f);
                doc.add(raster);
                return;
            }
        }
        JsonNode shapes = block.spec() == null ? null : block.spec().get("shapes");
        if (shapes == null || !shapes.isArray() || shapes.isEmpty()) {
            doc.add(new Paragraph(note(null, "invalid", null), NOTE));
            return;
        }
        float height = block.spec() != null ? block.spec().path("height").asInt(160) : 160;
        PdfTemplate template = writer.getDirectContent().createTemplate(460, Math.min(240, height));
        float scale = 460f / Math.max(1, block.spec() != null ? block.spec().path("width").asInt(520) : 520);
        for (JsonNode shape : shapes) drawShape(template, shape, scale, template.getHeight());
        Image image = Image.getInstance(template);
        image.setSpacingAfter(8f);
        doc.add(image);
    }

    private static void drawShape(PdfContentByte canvas, JsonNode shape, float scale, float height) {
        String type = shape.path("type").asText("");
        Color stroke = color(shape.path("stroke").asText("#1A1A1A"), "INFO");
        Color fill = shape.hasNonNull("fill") ? color(shape.path("fill").asText(null), "INFO") : null;
        if ("rect".equals(type) || "ellipse".equals(type)) {
            float x = (float) shape.path("x").asDouble() * scale;
            float y = height - (float) (shape.path("y").asDouble() + shape.path("h").asDouble()) * scale;
            float w = (float) shape.path("w").asDouble() * scale;
            float h = (float) shape.path("h").asDouble() * scale;
            if (fill != null) {
                canvas.setColorFill(fill);
                if ("ellipse".equals(type)) canvas.ellipse(x, y, x + w, y + h);
                else canvas.rectangle(x, y, w, h);
                canvas.fill();
            }
            canvas.setColorStroke(stroke);
            if ("ellipse".equals(type)) canvas.ellipse(x, y, x + w, y + h);
            else canvas.rectangle(x, y, w, h);
            canvas.stroke();
            return;
        }
        if ("line".equals(type) || "arrow".equals(type)) {
            float x1 = (float) shape.path("x1").asDouble() * scale;
            float y1 = height - (float) shape.path("y1").asDouble() * scale;
            float x2 = (float) shape.path("x2").asDouble() * scale;
            float y2 = height - (float) shape.path("y2").asDouble() * scale;
            canvas.setColorStroke(stroke);
            canvas.moveTo(x1, y1);
            canvas.lineTo(x2, y2);
            canvas.stroke();
            if ("arrow".equals(type)) {
                float dx = x2 - x1;
                float dy = y2 - y1;
                float len = (float) Math.hypot(dx, dy);
                if (len > 0.5f) {
                    float ux = dx / len;
                    float uy = dy / len;
                    canvas.setColorFill(stroke);
                    canvas.moveTo(x2, y2);
                    canvas.lineTo(x2 - ux * 8 - uy * 3, y2 - uy * 8 + ux * 3);
                    canvas.lineTo(x2 - ux * 8 + uy * 3, y2 - uy * 8 - ux * 3);
                    canvas.fill();
                }
            }
            return;
        }
        if ("freehand".equals(type)) {
            JsonNode points = shape.get("points");
            if (points == null || !points.isArray() || points.size() < 2) return;
            canvas.setColorStroke(stroke);
            for (int i = 0; i < points.size(); i++) {
                JsonNode point = points.get(i);
                float x = (float) (point.isArray() && !point.isEmpty() ? point.get(0).asDouble() : 0) * scale;
                float y = height - (float) (point.isArray() && point.size() > 1 ? point.get(1).asDouble() : 0) * scale;
                if (i == 0) canvas.moveTo(x, y);
                else canvas.lineTo(x, y);
            }
            canvas.stroke();
        }
    }

    private static Image rasterSvg(String svg) {
        String safe = sanitizeSvg(svg);
        if (safe.isBlank()) return null;
        try {
            SVGDocument document = new SVGLoader().load(new ByteArrayInputStream(safe.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            if (document == null || document.size() == null) return null;
            int w = Math.max(1, Math.round((float) document.size().getWidth()));
            int h = Math.max(1, Math.round((float) document.size().getHeight()));
            BufferedImage buffered = new BufferedImage(Math.min(w, 1200), Math.min(h, 800), BufferedImage.TYPE_INT_ARGB);
            Graphics2D graphics = buffered.createGraphics();
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            document.render(null, graphics);
            graphics.dispose();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(buffered, "png", out);
            return Image.getInstance(out.toByteArray());
        } catch (Exception ex) {
            return null;
        }
    }

    static String sanitizeSvg(String svg) {
        if (svg == null) return "";
        String text = svg.trim();
        if (text.length() > 80000) text = text.substring(0, 80000);
        if (!text.startsWith("<svg") && !text.startsWith("<?xml")) return "";
        String lower = text.toLowerCase(Locale.ROOT);
        if (lower.contains("<script") || lower.contains("<foreignobject") || lower.contains("<iframe") || lower.contains("javascript:")) {
            return "";
        }
        return text.replaceAll("(?i)\\s+on[a-z]+\\s*=\\s*(\"[^\"]*\"|'[^']*')", "");
    }

    private static String safeDataUrl(String url) {
        return dataBytes(url) == null ? "" : url.replaceAll("\\s+", "");
    }

    private static byte[] dataBytes(String url) {
        if (url == null) return null;
        String trimmed = url.replaceAll("\\s+", "");
        if (!trimmed.matches("(?i)data:image/(png|jpeg|gif|webp);base64,[a-z0-9+/=]+")) return null;
        if (trimmed.length() > 700000) return null;
        try {
            return Base64.getDecoder().decode(trimmed.substring(trimmed.indexOf(',') + 1));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static Color color(String hex, String key) {
        if (hex != null && hex.matches("#[0-9a-fA-F]{6}")) return Color.decode(hex);
        return switch (key == null ? "" : key.toUpperCase(Locale.ROOT)) {
            case "CRITICAL" -> new Color(0xcc, 0x2e, 0x2e);
            case "HIGH" -> new Color(0xd9, 0x71, 0x1f);
            case "MEDIUM" -> new Color(0xb3, 0x8f, 0x00);
            case "LOW" -> new Color(0x2f, 0x7a, 0x77);
            default -> new Color(0x6e, 0x6e, 0x6e);
        };
    }

    private static String hex(Color color) {
        return String.format("#%02X%02X%02X", color.getRed(), color.getGreen(), color.getBlue());
    }

    private static String esc(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static String safe(String value) { return value == null ? "" : value; }

    private static String fit(String value, int max) {
        String text = safe(value);
        return text.length() <= max ? text : text.substring(0, max);
    }

    private static String r(double n) { return Double.toString(Math.round(n * 10) / 10.0); }

    private record Category(String label, double value, Color color) {}
    private record Col(String key, String label) {}
}
