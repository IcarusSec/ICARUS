package icarus.ui.reportprofile.sections;

import icarus.ui.reportprofile.layout.Breakpoint;
import icarus.ui.reportprofile.layout.ResponsiveSection;

import javax.swing.*;
import java.awt.*;

public class SectionFlowPanel implements ResponsiveSection {
    private final JPanel component = new JPanel(new GridBagLayout());
    private final JComponent listPanel;
    private final JComponent detailPane;

    public SectionFlowPanel(JComponent listPanel, JComponent detailPane) {
        this.listPanel = listPanel;
        this.detailPane = detailPane;
    }

    @Override
    public Component component() {
        return component;
    }

    @Override
    public void onBreakpointChanged(Breakpoint bp) {
        component.removeAll();
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.fill = GridBagConstraints.BOTH;
        gbc.insets = new Insets(0, 0, 16, 16);

        if (bp == Breakpoint.COMPACT || bp == Breakpoint.NARROW) {
            // Stacked
            gbc.gridx = 0;
            gbc.gridy = 0;
            gbc.weightx = 1.0;
            gbc.weighty = 0.0;
            
            // Fixed max-height 220px for list
            listPanel.setPreferredSize(new Dimension(-1, 280));
            listPanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 280));
            component.add(listPanel, gbc);
            
            gbc.gridy = 1;
            gbc.weighty = 1.0;
            component.add(detailPane, gbc);
        } else {
            // Side by side
            gbc.gridx = 0;
            gbc.gridy = 0;
            gbc.weightx = 0.0;
            gbc.weighty = 1.0;
            
            // List 280px fixed
            listPanel.setPreferredSize(new Dimension(300, -1));
            listPanel.setMinimumSize(new Dimension(300, 0));
            listPanel.setMaximumSize(new Dimension(300, Integer.MAX_VALUE));
            component.add(listPanel, gbc);
            
            gbc.gridx = 1;
            gbc.weightx = 1.0;
            component.add(detailPane, gbc);
        }
        component.revalidate();
        component.repaint();
    }
}
