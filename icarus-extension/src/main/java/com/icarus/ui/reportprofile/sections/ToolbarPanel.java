package com.icarus.ui.reportprofile.sections;

import com.icarus.ui.reportprofile.layout.Breakpoint;
import com.icarus.ui.reportprofile.layout.ResponsiveSection;
import com.icarus.ui.reportprofile.layout.WrapLayout;
import icarus.report.model.ReportProfile;

import javax.swing.*;
import java.awt.*;

public class ToolbarPanel implements ResponsiveSection {
    // One left-aligned flow row for every control; WrapLayout wraps to a second
    // row when it doesn't fit. A wide strut separates the profile group from the
    // preview/save actions. No left/right split -> both rows always share the
    // same left edge instead of one hugging the right.
    private final JPanel component = new JPanel(new WrapLayout(FlowLayout.LEFT, 8, 8));

    public ToolbarPanel(JComboBox<ReportProfile> comboProfiles,
                        JButton btnClone, JButton btnExport, JButton btnImport, JButton btnDelete,
                        JButton btnPreviewPdf, JButton btnPreviewHtml, JButton btnSave) {
        component.add(new JLabel("Profile:"));
        component.add(comboProfiles);
        component.add(btnClone);
        component.add(btnExport);
        component.add(btnImport);
        component.add(btnDelete);
        component.add(Box.createHorizontalStrut(24));
        component.add(btnPreviewPdf);
        component.add(btnPreviewHtml);
        component.add(btnSave);
    }

    @Override
    public Component component() {
        return component;
    }

    @Override
    public void onBreakpointChanged(Breakpoint bp) {
        // WrapLayout handles reflow on its own.
    }
}
