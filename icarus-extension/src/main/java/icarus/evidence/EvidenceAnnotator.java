package icarus.evidence;

import java.awt.*;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.util.List;

public final class EvidenceAnnotator {

    /** One annotation op: BOX/REDACT/CROP are a rectangle at ({@code x},{@code y}) sized
     *  {@code width}x{@code height}; ARROW runs from ({@code x},{@code y}) to
     *  ({@code x+width},{@code y+height}). Kinds match the annotation editor's mouse modes
     *  ({@code mode[0]} in {@link #showPhase2}), so headless and interactive annotations render
     *  identically — CROP is the one addition, truncating the image to that rectangle. There is
     *  deliberately no fill/wash kind ("HIGHLIGHT") — {@link #paintAnnotation} has no branch for
     *  one, so passing that kind renders as a plain BOX outline instead: a translucent-yellow
     *  wash over anything but a tiny, precisely-placed target reliably read as a muddy smear
     *  rather than a pointer to something specific, and a guessed rectangle from an unattended
     *  caller (the MCP tool surface, or the reporting agent's own default) is never that precise. */
    public record Annotation(String kind, int x, int y, int width, int height) {}

    /**
     * Headless counterpart to the mouse-driven annotation canvas in {@link #showPhase2}, for
     * callers with no mouse to drive it (the MCP tool surface): draws the same BOX/ARROW/REDACT
     * shapes via {@link #createArrow} and the editor's own paint rules, plus CROP (applied last,
     * after every other annotation is drawn, so its coordinates stay in the original image's
     * space) to truncate what isn't relevant to the finding.
     */
    public static BufferedImage applyAnnotations(BufferedImage source, List<Annotation> annotations) {
        BufferedImage out = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2 = out.createGraphics();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.drawImage(source, 0, 0, null);
        g2.setStroke(new BasicStroke(3f));

        Rectangle crop = null;
        for (Annotation a : annotations) {
            if ("CROP".equals(a.kind())) {
                crop = new Rectangle(a.x(), a.y(), a.width(), a.height());
                continue;
            }
            Shape shape = "ARROW".equals(a.kind())
                    ? createArrow(new Point(a.x(), a.y()), new Point(a.x() + a.width(), a.y() + a.height()))
                    : new Rectangle2D.Double(a.x(), a.y(), a.width(), a.height());
            paintAnnotation(g2, shape, a.kind(), Color.RED);
        }
        g2.dispose();

        if (crop == null) return out;
        Rectangle bounds = crop.intersection(new Rectangle(0, 0, out.getWidth(), out.getHeight()));
        return out.getSubimage(bounds.x, bounds.y, bounds.width, bounds.height);
    }

    /** Shared paint rules with the annotation canvas's own {@code drawAnnotation} (a local class
     *  method there, so not directly reachable) — REDACT is an opaque black fill, ARROW a
     *  filled+stroked shape, everything else (BOX, and any unrecognized kind such as the retired
     *  "HIGHLIGHT") is stroke-only — there is no fill/wash option by construction. */
    public static void paintAnnotation(Graphics2D g2, Shape s, String kind, Color c) {
        if ("REDACT".equals(kind)) {
            g2.setColor(Color.BLACK);
            g2.fill(s);
        } else if ("ARROW".equals(kind)) {
            g2.setColor(c);
            g2.fill(s);
            g2.draw(s);
        } else {
            g2.setColor(c);
            g2.draw(s);
        }
    }

    public static Shape createArrow(Point from, Point to) {
        double angle = Math.atan2(to.y - from.y, to.x - from.x);
        double length = Math.hypot(to.x - from.x, to.y - from.y);
        double headLength = Math.min(15, length * 0.6);
        double headWidth = headLength * 0.65;

        double baseX = to.x - headLength * Math.cos(angle);
        double baseY = to.y - headLength * Math.sin(angle);
        double perpX = -Math.sin(angle);
        double perpY = Math.cos(angle);

        Path2D path = new Path2D.Double();
        path.moveTo(from.x, from.y);
        path.lineTo(baseX, baseY);

        path.moveTo(to.x, to.y);
        path.lineTo(baseX + headWidth * perpX, baseY + headWidth * perpY);
        path.lineTo(baseX - headWidth * perpX, baseY - headWidth * perpY);
        path.closePath();

        return path;
    }

    /** Flattens the base snapshot + committed annotations into one image. Shared by Save and Copy. */
    public static BufferedImage renderFinalImage(BufferedImage snap, List<Shape> shapes, List<String> kinds, List<Color> cols) {
        BufferedImage out = new BufferedImage(snap.getWidth(), snap.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2 = out.createGraphics();
        g2.drawImage(snap, 0, 0, null);
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setStroke(new BasicStroke(3f));
        for (int i = 0; i < shapes.size(); i++) {
            String kind = kinds.get(i);
            Shape s = shapes.get(i);
            if ("REDACT".equals(kind)) {
                g2.setColor(Color.BLACK);
                g2.fill(s);
            } else if ("ARROW".equals(kind)) {
                g2.setColor(cols.get(i));
                g2.fill(s);
                g2.draw(s);
            } else {
                g2.setColor(cols.get(i));
                g2.draw(s);
            }
        }
        g2.dispose();
        return out;
    }
}
