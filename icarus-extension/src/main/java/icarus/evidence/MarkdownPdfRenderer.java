package icarus.evidence;

import org.commonmark.node.AbstractVisitor;
import org.commonmark.node.BulletList;
import org.commonmark.node.Code;
import org.commonmark.node.Emphasis;
import org.commonmark.node.HardLineBreak;
import org.commonmark.node.Heading;
import org.commonmark.node.ListItem;
import org.commonmark.node.Node;
import org.commonmark.node.OrderedList;
import org.commonmark.node.SoftLineBreak;
import org.commonmark.node.StrongEmphasis;
import org.commonmark.node.Text;

import com.lowagie.text.Chunk;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.List;
import com.lowagie.text.Paragraph;

import java.awt.Color;
import java.util.ArrayList;

/**
 * Minimal Markdown-to-OpenPDF visitor for {@code ReportTemplateConfig} sections in the PDF
 * report. Covers headings, paragraphs, bold/italic, inline code, and bullet/ordered lists —
 * the subset report sections actually use, not a general-purpose Markdown renderer.
 *
 * ponytail: nested lists (a list inside a list item) render as a second flat top-level list,
 * not true visual nesting — upgrade if reports actually nest list items.
 */
public final class MarkdownPdfRenderer extends AbstractVisitor {

    private final Font bodyFont;
    private final Font boldFont;
    private final Font italicFont;
    private final Font boldItalicFont;
    private final Font codeFont;
    private final Font[] headingFonts = new Font[7]; // index 1..6 by heading level

    private final java.util.List<Element> elements = new ArrayList<>();
    private Paragraph currentParagraph;
    private List currentList;
    private boolean bold;
    private boolean italic;

    private MarkdownPdfRenderer(Font bodyFont, Color headingColor) {
        this.bodyFont = bodyFont;
        int family = bodyFont.getFamily();
        float size = bodyFont.getSize();
        Color textColor = bodyFont.getColor();
        this.boldFont = new Font(family, size, Font.BOLD, textColor);
        this.italicFont = new Font(family, size, Font.ITALIC, textColor);
        this.boldItalicFont = new Font(family, size, Font.BOLDITALIC, textColor);
        this.codeFont = new Font(Font.COURIER, size, Font.NORMAL, textColor);
        for (int level = 1; level <= 6; level++) {
            float headingSize = Math.max(size + 1, 18 - (level * 2));
            headingFonts[level] = new Font(family, headingSize, Font.BOLD, headingColor);
        }
    }

    /** Renders {@code document}'s block content as a list of OpenPDF elements, ready to add to a PdfPCell/Document. */
    public static java.util.List<Element> render(Node document, Font bodyFont, Color headingColor) {
        MarkdownPdfRenderer renderer = new MarkdownPdfRenderer(bodyFont, headingColor);
        document.accept(renderer);
        return renderer.elements;
    }

    public static java.util.List<Element> render(String markdown, Font bodyFont, Color headingColor) {
        if (markdown == null || markdown.isBlank()) return java.util.Collections.emptyList();
        org.commonmark.parser.Parser parser = org.commonmark.parser.Parser.builder().build();
        Node doc = parser.parse(markdown);
        return render(doc, bodyFont, headingColor);
    }

    public static void renderToCell(String markdown, com.lowagie.text.pdf.PdfPCell cell, Font bodyFont) {
        if (markdown == null || markdown.isBlank() || cell == null) return;
        var elements = render(markdown, bodyFont, bodyFont.getColor());
        for (Element el : elements) {
            cell.addElement(el);
        }
    }

    private Font currentFont() {
        if (bold && italic) return boldItalicFont;
        if (bold) return boldFont;
        if (italic) return italicFont;
        return bodyFont;
    }

    @Override
    public void visit(Heading heading) {
        String text = collectText(heading);
        Font font = headingFonts[Math.min(6, Math.max(1, heading.getLevel()))];
        Paragraph p = new Paragraph(text, font);
        p.setSpacingBefore(8f);
        p.setSpacingAfter(4f);
        elements.add(p);
    }

    @Override
    public void visit(org.commonmark.node.Paragraph paragraph) {
        if (currentList != null) {
            // List items wrap their text in a Paragraph node — append directly onto the
            // ListItem (which IS a Paragraph) already set as currentParagraph, don't nest.
            visitChildren(paragraph);
            return;
        }
        Paragraph p = new Paragraph();
        p.setSpacingAfter(6f);
        Paragraph prev = currentParagraph;
        currentParagraph = p;
        visitChildren(paragraph);
        currentParagraph = prev;
        elements.add(p);
    }

    @Override
    public void visit(Text text) {
        if (currentParagraph != null) currentParagraph.add(new Chunk(text.getLiteral(), currentFont()));
    }

    @Override
    public void visit(Code code) {
        if (currentParagraph != null) currentParagraph.add(new Chunk(code.getLiteral(), codeFont));
    }

    @Override
    public void visit(Emphasis emphasis) {
        boolean prev = italic;
        italic = true;
        visitChildren(emphasis);
        italic = prev;
    }

    @Override
    public void visit(StrongEmphasis strongEmphasis) {
        boolean prev = bold;
        bold = true;
        visitChildren(strongEmphasis);
        bold = prev;
    }

    @Override
    public void visit(SoftLineBreak softLineBreak) {
        if (currentParagraph != null) currentParagraph.add(new Chunk(" ", currentFont()));
    }

    @Override
    public void visit(HardLineBreak hardLineBreak) {
        if (currentParagraph != null) currentParagraph.add(Chunk.NEWLINE);
    }

    @Override
    public void visit(BulletList bulletList) {
        renderList(bulletList, List.UNORDERED);
    }

    @Override
    public void visit(OrderedList orderedList) {
        renderList(orderedList, List.ORDERED);
    }

    private void renderList(Node listNode, boolean ordered) {
        List list = new List(ordered);
        list.setListSymbol(new Chunk(ordered ? "" : "•  ", bodyFont));
        List prevList = currentList;
        currentList = list;
        visitChildren(listNode);
        currentList = prevList;
        elements.add(list);
    }

    @Override
    public void visit(ListItem listItem) {
        com.lowagie.text.ListItem pdfItem = new com.lowagie.text.ListItem();
        Paragraph prev = currentParagraph;
        currentParagraph = pdfItem;
        visitChildren(listItem);
        currentParagraph = prev;
        currentList.add(pdfItem);
    }

    private String collectText(Node node) {
        StringBuilder sb = new StringBuilder();
        Node child = node.getFirstChild();
        while (child != null) {
            if (child instanceof Text t) sb.append(t.getLiteral());
            else if (child instanceof Code c) sb.append(c.getLiteral());
            else sb.append(collectText(child));
            child = child.getNext();
        }
        return sb.toString();
    }
}
