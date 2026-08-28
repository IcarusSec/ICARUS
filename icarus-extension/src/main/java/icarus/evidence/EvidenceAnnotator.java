package icarus.evidence;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.*;
import java.util.List;
import javax.swing.*;
import burp.api.montoya.MontoyaApi;
import icarus.core.*;
import java.awt.geom.*;
import icarus.ui.*;
import java.awt.event.*;
import java.awt.datatransfer.*;
import java.io.*;
import java.nio.file.*;
import javax.imageio.*;
import javax.swing.border.*;
import javax.swing.event.*;
import javax.swing.text.*;

public class EvidenceAnnotator {
    private final EvidenceCapture capture;
    private final MontoyaApi api;
    private final ModuleConfig config;

    public EvidenceAnnotator(EvidenceCapture capture, MontoyaApi api, ModuleConfig config) {
        this.capture = capture;
        this.api = api;
        this.config = config;
    }

    /** One annotation op: BOX/REDACT/CROP are a rectangle at ({@code x},{@code y}) sized
     *  {@code width}x{@code height}; ARROW runs from ({@code x},{@code y}) to
     *  ({@code x+width},{@code y+height}). There is deliberately no fill/wash kind
     *  ("HIGHLIGHT") — {@link #paintAnnotation} has no branch for one, so passing that kind
     *  renders as a plain BOX outline instead: a translucent-yellow wash over anything but a
     *  tiny, precisely-placed target reliably read as a muddy smear rather than a pointer to
     *  something specific, and a guessed rectangle from an unattended caller is never that precise. */
public record Annotation(String kind, int x, int y, int width, int height) {}

public java.awt.image.BufferedImage applyAnnotations(java.awt.image.BufferedImage source, java.util.List<EvidenceAnnotator.Annotation> annotations) {
        java.awt.image.BufferedImage out = new java.awt.image.BufferedImage(source.getWidth(), source.getHeight(), java.awt.image.BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2 = out.createGraphics();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.drawImage(source, 0, 0, null);
        g2.setStroke(new BasicStroke(3f));

        Rectangle crop = null;
        for (EvidenceAnnotator.Annotation a : annotations) {
            if ("CROP".equals(a.kind())) {
                crop = new Rectangle(a.x(), a.y(), a.width(), a.height());
                continue;
            }
            Shape shape = "ARROW".equals(a.kind())
                    ? capture.annotator.createArrow(new Point(a.x(), a.y()), new Point(a.x() + a.width(), a.y() + a.height()))
                    : new java.awt.geom.Rectangle2D.Double(a.x(), a.y(), a.width(), a.height());
            capture.annotator.paintAnnotation(g2, shape, a.kind(), Color.RED);
        }
        g2.dispose();

        if (crop == null) return out;
        Rectangle bounds = crop.intersection(new Rectangle(0, 0, out.getWidth(), out.getHeight()));
        return out.getSubimage(bounds.x, bounds.y, bounds.width, bounds.height);
    }

public void paintAnnotation(Graphics2D g2, Shape s, String kind, Color c) {
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

public Shape createArrow(Point from, Point to) {
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
}
