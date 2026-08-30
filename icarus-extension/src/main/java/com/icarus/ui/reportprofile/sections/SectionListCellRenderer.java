package com.icarus.ui.reportprofile.sections;

import icarus.report.model.SectionNode;
import com.icarus.ui.reportprofile.theme.ThemeColors;
import com.icarus.ui.reportprofile.model.SectionLabelFormatter;

import javax.swing.*;
import java.awt.*;
import java.awt.geom.RoundRectangle2D;

public class SectionListCellRenderer extends JPanel implements ListCellRenderer<SectionNode> {
    public static final int TOGGLE_X0 = 27;
    public static final int TOGGLE_X1 = 53;
    
    private SectionNode currentRow;
    private boolean isSelected;

    public SectionListCellRenderer() {
        setOpaque(true);
        setPreferredSize(new Dimension(0, 32));
    }

    @Override
    public Component getListCellRendererComponent(JList<? extends SectionNode> list, SectionNode value, int index, boolean isSelected, boolean cellHasFocus) {
        this.currentRow = value;
        this.isSelected = isSelected;
        
        if (isSelected) {
            setBackground(list.getSelectionBackground());
            setForeground(list.getSelectionForeground());
        } else {
            setBackground(list.getBackground());
            setForeground(list.getForeground());
        }
        
        return this;
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        if (currentRow == null) return;
        
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        
        var palette = ThemeColors.current();
        
        // Grip glyph
        g2.setColor(palette.textTertiary());
        g2.drawString("⋮⋮", 9, 18);
        
        // Toggle switch
        int toggleW = 26;
        int toggleH = 15;
        int toggleThumb = 11;
        int toggleY = (getHeight() - toggleH) / 2;
        
        boolean on = currentRow.enabled() || currentRow.required();
        boolean dim = currentRow.required();
        
        g2.setColor(on ? (dim ? palette.accentSoft() : palette.accent()) : palette.borderStrong());
        g2.fill(new RoundRectangle2D.Float(TOGGLE_X0, toggleY, toggleW, toggleH, toggleH, toggleH));
        
        int thumbX = on ? TOGGLE_X0 + toggleW - toggleThumb - 2 : TOGGLE_X0 + 2;
        g2.setColor(on ? Color.WHITE : palette.textTertiary());
        g2.fillOval(thumbX, toggleY + (toggleH - toggleThumb) / 2, toggleThumb, toggleThumb);
        
        // Text
        g2.setColor(isSelected ? getForeground() : palette.textPrimary());
        FontMetrics fm = g2.getFontMetrics();
        String text = currentRow.params().get("title");
        if (text == null || text.isBlank()) text = SectionLabelFormatter.format(currentRow.id());
        int textX = TOGGLE_X1 + 10;
        g2.drawString(text, textX, toggleY + toggleH - 2);
        
        // Required lock
        if (currentRow.required()) {
            int lockX = getWidth() - 20;
            g2.setColor(currentRow.enabled() ? palette.accent() : palette.textTertiary());
            g2.drawString("🔒", lockX, toggleY + toggleH - 2); // Lock icon
        }
        
        g2.dispose();
    }
}
