package com.icarus.ui.reportprofile.components;

import com.icarus.ui.reportprofile.theme.ThemeColors;
import javax.swing.*;
import java.awt.*;
import java.awt.geom.RoundRectangle2D;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.function.Consumer;

public final class ToggleSwitch extends JComponent {
    private static final int W = 26, H = 15, THUMB = 11;
    private boolean on;
    private Consumer<Boolean> onChange = b -> {};

    public ToggleSwitch(boolean initial) {
        this.on = initial;
        setPreferredSize(new Dimension(W, H));
        setMinimumSize(getPreferredSize());
        setMaximumSize(getPreferredSize());
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        setFocusable(true);
        addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) { toggle(); }
        });
        getInputMap().put(KeyStroke.getKeyStroke("SPACE"), "toggle");
        getActionMap().put("toggle", new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) { toggle(); }
        });
    }

    public boolean isOn() { return on; }
    public void setOn(boolean v) { if (v != on) { on = v; repaint(); } }
    public void onChange(Consumer<Boolean> cb) { this.onChange = cb; }

    private void toggle() {
        on = !on;
        repaint();
        onChange.accept(on);
    }

    @Override protected void paintComponent(Graphics g0) {
        Graphics2D g = (Graphics2D) g0.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        var palette = ThemeColors.current();
        g.setColor(on ? palette.accent() : palette.borderStrong());
        g.fill(new RoundRectangle2D.Float(0, 0, W, H, H, H));
        int thumbX = on ? W - THUMB - 2 : 2;
        g.setColor(on ? Color.WHITE : palette.textTertiary());
        g.fillOval(thumbX, (H - THUMB) / 2, THUMB, THUMB);
        
        if (hasFocus()) {
            g.setColor(palette.accent());
            g.setStroke(new BasicStroke(2));
            g.draw(new RoundRectangle2D.Float(1, 1, W - 2, H - 2, H, H));
        }
        g.dispose();
    }
}
