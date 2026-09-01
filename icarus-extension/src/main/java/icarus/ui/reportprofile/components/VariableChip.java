package icarus.ui.reportprofile.components;

import icarus.ui.reportprofile.theme.FontLoader;
import icarus.ui.reportprofile.theme.ThemeColors;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.RoundRectangle2D;
import java.util.function.Consumer;

public final class VariableChip extends JComponent {
    private final String text;
    private boolean hovered = false;
    private final int W, H;

    public VariableChip(String text, Consumer<String> onClick) {
        this.text = text;
        setFont(FontLoader.mono(12f));
        FontMetrics fm = getFontMetrics(getFont());
        this.W = fm.stringWidth(text) + 16;
        this.H = fm.getHeight() + 8;
        
        setPreferredSize(new Dimension(W, H));
        setMinimumSize(getPreferredSize());
        setMaximumSize(getPreferredSize());
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        setFocusable(true);
        
        addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) { onClick.accept(text); }
            @Override public void mouseEntered(MouseEvent e) { hovered = true; repaint(); }
            @Override public void mouseExited(MouseEvent e) { hovered = false; repaint(); }
        });
        
        getInputMap().put(KeyStroke.getKeyStroke("SPACE"), "click");
        getActionMap().put("click", new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) { onClick.accept(text); }
        });
    }

    @Override
    protected void paintComponent(Graphics g0) {
        Graphics2D g = (Graphics2D) g0.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        var palette = ThemeColors.current();
        
        // Background
        g.setColor(hovered ? palette.accentSoftStrong() : palette.accentSoft());
        g.fill(new RoundRectangle2D.Float(0, 0, W - 1, H - 1, 4, 4));
        
        // Border
        g.setColor(palette.accentSoftStrong());
        g.draw(new RoundRectangle2D.Float(0, 0, W - 1, H - 1, 4, 4));
        
        // Focus ring
        if (hasFocus()) {
            g.setColor(palette.accent());
            g.setStroke(new BasicStroke(2));
            g.draw(new RoundRectangle2D.Float(1, 1, W - 3, H - 3, 4, 4));
        }
        
        // Text
        g.setColor(palette.accent());
        g.setFont(getFont());
        FontMetrics fm = g.getFontMetrics();
        int x = (W - fm.stringWidth(text)) / 2;
        int y = ((H - fm.getHeight()) / 2) + fm.getAscent();
        g.drawString(text, x, y);
        
        g.dispose();
    }
}
