package com.icarus.ui.reportprofile.components;

import com.icarus.ui.reportprofile.theme.ThemeColors;
import javax.swing.*;
import java.awt.*;

public final class WireframePreview extends JComponent {
    private final WireframeKind kind;

    public WireframePreview(WireframeKind kind) {
        this.kind = kind;
        setPreferredSize(new Dimension(100, 60));
    }

    @Override
    protected void paintComponent(Graphics g0) {
        Graphics2D g = (Graphics2D) g0.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        var palette = ThemeColors.current();
        
        int w = getWidth();
        int h = getHeight();
        
        g.setColor(palette.wellBg());
        g.fillRect(0, 0, w, h);
        
        switch (kind) {
            case GRADIENT_HERO:
                GradientPaint gp = new GradientPaint(0, 0, palette.accentSoftStrong(), w, h, palette.appBg());
                g.setPaint(gp);
                g.fillRect(0, 0, w, h);
                break;
            case HEADER_BAND:
                g.setColor(palette.accentSoftStrong());
                g.fillRect(0, 0, w, h / 4);
                break;
            case ELEVATED_CARD:
                g.setColor(palette.borderStrong()); // shadow
                g.fillRoundRect(10, 10 + 2, w - 20, h - 20, 8, 8);
                g.setColor(palette.panelBg()); // card
                g.fillRoundRect(10, 10, w - 20, h - 20, 8, 8);
                g.setColor(palette.border());
                g.drawRoundRect(10, 10, w - 20, h - 20, 8, 8);
                break;
            case TABULAR_GRID:
                g.setColor(palette.border());
                int step = h / 4;
                for (int y = step; y < h; y += step) {
                    g.drawLine(10, y, w - 10, y);
                }
                break;
            case NONE:
            default:
                break;
        }
        
        g.dispose();
    }
}
