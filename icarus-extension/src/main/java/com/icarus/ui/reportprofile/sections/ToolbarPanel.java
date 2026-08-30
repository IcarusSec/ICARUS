package com.icarus.ui.reportprofile.sections;

import com.icarus.ui.reportprofile.layout.Breakpoint;
import com.icarus.ui.reportprofile.layout.ResponsiveSection;
import com.icarus.ui.reportprofile.layout.WrapLayout;
import icarus.report.model.ReportProfile;

import javax.swing.*;
import java.awt.*;

public class ToolbarPanel implements ResponsiveSection {
    private final JPanel component;

    private final JPanel left = new JPanel(new WrapLayout(FlowLayout.LEFT, 8, 8));
    private final JPanel right = new JPanel(new WrapLayout(FlowLayout.RIGHT, 8, 8));

    public ToolbarPanel(JComboBox<ReportProfile> comboProfiles, 
                        JButton btnClone, JButton btnExport, JButton btnImport, JButton btnDelete,
                        JButton btnPreviewPdf, JButton btnPreviewHtml, JButton btnSave) {
        component = new JPanel(new GridBagLayout());
        
        left.add(new JLabel("Profile:"));
        left.add(comboProfiles);
        left.add(btnClone);
        left.add(btnExport);
        left.add(btnImport);
        left.add(btnDelete);
        
        right.add(btnPreviewPdf);
        right.add(btnPreviewHtml);
        right.add(btnSave);
    }

    @Override
    public Component component() {
        return component;
    }

    @Override
    public void onBreakpointChanged(Breakpoint bp) {
        component.removeAll();
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;
        
        if (bp == Breakpoint.COMPACT || bp == Breakpoint.NARROW) {
            gbc.gridy = 0; gbc.gridx = 0; component.add(left, gbc);
            gbc.gridy = 1; component.add(right, gbc);
        } else {
            gbc.gridy = 0;
            gbc.gridx = 0; component.add(left, gbc);
            gbc.gridx = 1; component.add(right, gbc);
        }
        component.revalidate();
        component.repaint();
    }
}
