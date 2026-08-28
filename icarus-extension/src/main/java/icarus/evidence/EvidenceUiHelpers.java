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
        
        // Calculate contrast
        double luminance = (0.299 * bg.getRed() + 0.587 * bg.getGreen() + 0.114 * bg.getBlue()) / 255;
        btn.setForeground(luminance > 0.5 ? new Color(15, 24, 41) : Color.WHITE);
        
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
                baseIcon = baseIcon.derive(size, size);
                Color iconColor = overrideColor != null ? overrideColor : UIManager.getColor("Label.foreground");
                if (iconColor != null) {
                    baseIcon.setColorFilter(new FlatSVGIcon.ColorFilter(color -> iconColor));
                }
                return baseIcon;
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

    public static FlatSVGIcon loadSvgIcon(String type) {
        String name = type.endsWith(".svg") ? type : type + ".svg";

        // 1. Try ClassLoader resources
        String[] resourcePaths = {
            "/icarus/ui/resources/feather/" + name,
            "/icarus/ui/resources/feather/icons/" + name,
            "/feather/" + name,
            "/feather/icons/" + name,
            "/libs/feather/" + name,
            "/libs/feather/icons/" + name,
            "/" + name
        };
        for (String res : resourcePaths) {
            java.net.URL url = EvidenceUiHelpers.class.getResource(res);
            if (url != null) {
                try {
                    return new FlatSVGIcon(url);
                } catch (Exception ignored) {}
            }
        }

        // 2. Try relative file system paths
        String[] filePaths = {
            "libs/feather/" + name,
            "libs/feather/icons/" + name,
            "../libs/feather/" + name,
            "../libs/feather/icons/" + name,
            "../../libs/feather/" + name
        };
        for (String fp : filePaths) {
            java.io.File f = new java.io.File(fp);
            if (f.exists()) {
                try {
                    return new FlatSVGIcon(f);
                } catch (Exception ignored) {}
            }
        }

        // 3. Try user.dir relative paths
        String userDir = System.getProperty("user.dir", "");
        if (!userDir.isEmpty()) {
            java.io.File f1 = new java.io.File(userDir, "libs/feather/" + name);
            if (f1.exists()) try { return new FlatSVGIcon(f1); } catch (Exception ignored) {}

            java.io.File f2 = new java.io.File(userDir, "libs/feather/icons/" + name);
            if (f2.exists()) try { return new FlatSVGIcon(f2); } catch (Exception ignored) {}
        }

        // 4. Dynamic user home resolution (works across all OS user accounts without hardcoding)
        String userHome = System.getProperty("user.home", "");
        if (!userHome.isEmpty()) {
            String[] homeSubPaths = {
                "Downloads/icarus-extension/icarus-extension/libs/feather/" + name,
                "Downloads/icarus-extension/icarus-extension/libs/feather/icons/" + name,
                "Downloads/icarus-extension/libs/feather/" + name,
                "icarus-extension/libs/feather/" + name
            };
            for (String sub : homeSubPaths) {
                java.io.File f = new java.io.File(userHome, sub);
                if (f.exists()) {
                    try {
                        return new FlatSVGIcon(f);
                    } catch (Exception ignored) {}
                }
            }
        }

        return null;
    }
}
