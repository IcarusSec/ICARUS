package com.icarus.ui.reportprofile.components;

import com.icarus.ui.reportprofile.theme.FontLoader;
import com.icarus.ui.reportprofile.theme.ThemeColors;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.RoundRectangle2D;

public final class ThumbnailCard extends JPanel {
    private static final int W = 132, H = 92;
    private final boolean selected;
    private boolean hovered = false;

    public ThumbnailCard(String label, WireframeKind kind, boolean selected, Runnable onSelect) {
        this.selected = selected;
        setPreferredSize(new Dimension(W, H));
        setMinimumSize(new Dimension(W, H));
        setMaximumSize(new Dimension(W, H));
        setLayout(new BorderLayout());
        setOpaque(false);
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        
        WireframePreview preview = new WireframePreview(kind);
        preview.setPreferredSize(new Dimension(W - 4, H - 30));
        add(preview, BorderLayout.CENTER);
        
        JLabel title = new JLabel(label, SwingConstants.CENTER);
        title.setFont(FontLoader.sans(Font.PLAIN, 12f));
        title.setBorder(BorderFactory.createEmptyBorder(4, 0, 8, 0));
        add(title, BorderLayout.SOUTH);
        
        addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) { onSelect.run(); }
            @Override public void mouseEntered(MouseEvent e) { hovered = true; repaint(); }
            @Override public void mouseExited(MouseEvent e) { hovered = false; repaint(); }
        });
    }

    @Override
    protected void paintComponent(Graphics g0) {
        super.paintComponent(g0);
        Graphics2D g = (Graphics2D) g0.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        var palette = ThemeColors.current();
        
        g.setColor(palette.panelBg());
        g.fill(new RoundRectangle2D.Float(0, 0, W - 1, H - 1, 8, 8));
        
        if (selected) {
            g.setColor(palette.accent());
            g.setStroke(new BasicStroke(2f));
            // Inset well clear of the panel edge: a 2px stroke straddling the
            // outer rect bleeds past the top/right on fractional-DPI displays.
            g.draw(new RoundRectangle2D.Float(2, 2, W - 5, H - 5, 7, 7));
        } else if (hovered) {
            g.setColor(palette.borderStrong());
            g.setStroke(new BasicStroke(1f));
            g.draw(new RoundRectangle2D.Float(0, 0, W - 1, H - 1, 8, 8));
        } else {
            g.setColor(palette.border());
            g.setStroke(new BasicStroke(1f));
            g.draw(new RoundRectangle2D.Float(0, 0, W - 1, H - 1, 8, 8));
        }
        
        g.dispose();
    }
}
