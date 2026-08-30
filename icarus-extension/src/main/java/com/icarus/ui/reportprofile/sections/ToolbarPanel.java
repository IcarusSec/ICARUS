package com.icarus.ui.reportprofile.sections;

import com.icarus.ui.reportprofile.layout.Breakpoint;
import com.icarus.ui.reportprofile.layout.ResponsiveSection;
import com.icarus.ui.reportprofile.layout.WrapLayout;
import icarus.report.model.ReportProfile;

import javax.swing.*;
import java.awt.*;

public class ToolbarPanel implements ResponsiveSection {
    // Two left-aligned rows, always stacked: profile management on top, the
    // preview/save actions below. Both start at the same left edge (no
    // right-hugging, no split strut that wraps oddly). Each row is a WrapLayout
    // so it still wraps within itself on a very narrow window.
    private final JPanel component = new JPanel(new GridBagLayout());
    private final JPanel profileRow = new JPanel(new WrapLayout(FlowLayout.LEFT, 8, 6));
    private final JPanel actionRow = new JPanel(new WrapLayout(FlowLayout.LEFT, 8, 6));

    public ToolbarPanel(JComboBox<ReportProfile> comboProfiles,
                        JButton btnClone, JButton btnExport, JButton btnImport, JButton btnDelete,
                        JButton btnPreviewPdf, JButton btnPreviewHtml, JButton btnSave) {
        profileRow.add(new JLabel("Profile:"));
        profileRow.add(comboProfiles);
        profileRow.add(btnClone);
        profileRow.add(btnExport);
        profileRow.add(btnImport);
        profileRow.add(btnDelete);

        actionRow.add(btnPreviewPdf);
        actionRow.add(btnPreviewHtml);
        actionRow.add(btnSave);

        GridBagConstraints g = new GridBagConstraints();
        g.gridx = 0; g.weightx = 1.0;
        g.fill = GridBagConstraints.HORIZONTAL;
        g.anchor = GridBagConstraints.WEST;
        g.gridy = 0; component.add(profileRow, g);
        g.gridy = 1; g.insets = new Insets(2, 0, 0, 0);
        component.add(actionRow, g);
    }

    @Override
    public Component component() {
        return component;
    }

    @Override
    public void onBreakpointChanged(Breakpoint bp) {
        // Each row's WrapLayout handles its own reflow.
    }
}
