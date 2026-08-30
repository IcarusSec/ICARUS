package icarus.evidence;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.*;
import java.util.List;
import javax.swing.*;
import burp.api.montoya.MontoyaApi;
import icarus.core.*;
import icarus.ui.*;
import java.awt.event.*;
import java.awt.datatransfer.*;
import java.io.*;
import java.nio.file.*;
import javax.imageio.*;
import javax.swing.border.*;
import javax.swing.event.*;
import javax.swing.text.*;
import com.formdev.flatlaf.extras.FlatSVGIcon;

public class EvidenceUiHelpers {
    private final EvidenceCapture capture;
    private final MontoyaApi api;
    private final ModuleConfig config;

    public EvidenceUiHelpers(EvidenceCapture capture, MontoyaApi api, ModuleConfig config) {
        this.capture = capture;
        this.api = api;
        this.config = config;
    }

    public JButton createModernButton(String text, Color bg) {
        JButton btn = new JButton(text);
        btn.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 13));
        btn.setBackground(bg);
        
        // Calculate contrast (threshold at 0.65 ensures vibrant orange #FF6633 retains clean white text)
        double luminance = (0.299 * bg.getRed() + 0.587 * bg.getGreen() + 0.114 * bg.getBlue()) / 255;
        btn.setForeground(luminance > 0.65 ? new Color(30, 30, 30) : Color.WHITE);
        
        btn.setFocusPainted(false);
        btn.setHorizontalTextPosition(SwingConstants.RIGHT);
        btn.setVerticalTextPosition(SwingConstants.CENTER);
        btn.setVerticalAlignment(SwingConstants.CENTER);
        btn.setCursor(new Cursor(Cursor.HAND_CURSOR));
        btn.putClientProperty("FlatLaf.style", "arc: 8; margin: 8,16,8,16; iconTextGap: 8; minimumHeight: 38;");
        return btn;
    }

public JScrollPane createSmoothScrollPane(Component c) {
        JScrollPane scroll = new JScrollPane(c);
        scroll.setBorder(BorderFactory.createLineBorder(new Color(60, 60, 60), 1));
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        return scroll;
    }

public void addCweChip(JPanel pnlChips, List<String> selectedCwe, String cweId) {
        if (selectedCwe.contains(cweId)) return;
        selectedCwe.add(cweId);

        JPanel chip = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        chip.setBackground(new Color(60, 60, 60));
        chip.setBorder(BorderFactory.createLineBorder(EvidenceCapture.SEPARATOR_COLOR));

        JLabel lbl = new JLabel(cweId);
        lbl.setForeground(EvidenceCapture.TEXT_COLOR);

        JButton remove = new JButton("×");
        remove.setMargin(new Insets(0, 4, 0, 4));
        remove.setFocusable(false);
        remove.addActionListener(e -> {
            selectedCwe.remove(cweId);
            pnlChips.remove(chip);
            pnlChips.revalidate();
            pnlChips.repaint();
        });

        chip.add(lbl);
        chip.add(remove);
        pnlChips.add(chip);
        pnlChips.revalidate();
        pnlChips.repaint();
    }

    public static Icon createIcon(String type) {
        return createIcon(type, 16, null);
    }

    public static Icon createIcon(String type, int size, Color overrideColor) {
        try {
            FlatSVGIcon baseIcon = loadSvgIcon(type);
            if (baseIcon != null) {
                Color iconColor = overrideColor != null ? overrideColor : UIManager.getColor("Label.foreground");
                if (iconColor == null) iconColor = new Color(0xC8C8C8);
                final Color fc = iconColor;
                // Burp on Windows scales the UI by font size, not the Java2D
                // transform, so a FlatSVGIcon rasterised at the logical size gets
                // bitmap-upscaled by the OS and looks mushy. Render it once at 4x
                // into a buffer and downscale with bicubic at paint time -- crisp
                // whether or not the host is HiDPI-aware.
                int hi = Math.max(size * 4, 96);
                FlatSVGIcon svg = baseIcon.derive(hi, hi);
                svg.setColorFilter(new FlatSVGIcon.ColorFilter(color -> fc));
                BufferedImage buf = new BufferedImage(hi, hi, BufferedImage.TYPE_INT_ARGB);
                Graphics2D bg = buf.createGraphics();
                bg.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                bg.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
                bg.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
                bg.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                svg.paintIcon(null, bg, 0, 0);
                bg.dispose();
                return new Icon() {
                    @Override public void paintIcon(Component c, Graphics g, int x, int y) {
                        Graphics2D g2 = (Graphics2D) g.create();
                        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                                RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                        g2.setRenderingHint(RenderingHints.KEY_RENDERING,
                                RenderingHints.VALUE_RENDER_QUALITY);
                        g2.drawImage(buf, x, y, size, size, null);
                        g2.dispose();
                    }
                    @Override public int getIconWidth() { return size; }
                    @Override public int getIconHeight() { return size; }
                };
            }
        } catch (Exception ex) {
            // fallback
        }
        return new Icon() {
            @Override public void paintIcon(Component c, Graphics g, int x, int y) {}
            @Override public int getIconWidth() { return size; }
            @Override public int getIconHeight() { return size; }
        };
    }

    /** Icons are Tabler Icons (MIT), bundled on the classpath under
     *  {@code icarus/ui/resources/tabler/icons/} at build time -- see build.sh, which copies
     *  everything under {@code src/main/resources} onto the classpath. */
    public static FlatSVGIcon loadSvgIcon(String type) {
        String name = type.endsWith(".svg") ? type : type + ".svg";
        java.net.URL url = EvidenceUiHelpers.class.getResource("/icarus/ui/resources/tabler/icons/" + name);
        if (url == null) return null;
        try {
            return new FlatSVGIcon(url);
        } catch (Exception ignored) {
            return null;
        }
    }
}
