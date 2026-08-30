package com.icarus.ui.reportprofile.sections;

import com.icarus.ui.reportprofile.layout.Breakpoint;
import com.icarus.ui.reportprofile.layout.ResponsiveSection;
import com.icarus.ui.reportprofile.layout.WrapLayout;
import icarus.core.Severity;

import javax.swing.*;
import java.awt.*;
import java.util.Map;

public class ColorsThemeSectionPanel implements ResponsiveSection {
    private final JPanel component = new JPanel(new GridBagLayout());
    
    private final JPanel accentPanel;
    private final JPanel secondaryPanel;
    private final JPanel fontPanel;
    private final JPanel fontSizePanel;
    private final JPanel badgesPanel = new JPanel(new GridBagLayout());
    private final JPanel badgesRow = new JPanel(new WrapLayout(FlowLayout.LEFT, 8, 8));
    private final JPanel retestRow = new JPanel(new WrapLayout(FlowLayout.LEFT, 8, 8));

    public ColorsThemeSectionPanel(JPanel colorPrimaryPanel, JPanel colorSecondaryPanel, 
                                   JComboBox<String> comboFontStack, JSpinner spinFontSize,
                                   Map<Severity, JPanel> severityColorPanels) {
        this.accentPanel = createFieldPanel("Primary Accent", colorPrimaryPanel);
        this.secondaryPanel = createFieldPanel("Secondary Accent", colorSecondaryPanel);
        this.fontPanel = createFieldPanel("Font Family", comboFontStack);
        JPanel fontSizeHold = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        fontSizeHold.setOpaque(false);
        spinFontSize.setPreferredSize(new Dimension(64, spinFontSize.getPreferredSize().height));
        fontSizeHold.add(spinFontSize);
        this.fontSizePanel = createFieldPanel("Font Size", fontSizeHold);
        
        // Severity badges and retest-status (Fixed / Not Fixed) colours are two
        // different concepts -- keep them in separate labelled rows.
        for (Severity s : new Severity[]{Severity.CRITICAL, Severity.HIGH, Severity.MEDIUM,
                                         Severity.LOW, Severity.INFO}) {
            badgesRow.add(swatchCell(s, severityColorPanels));
        }
        for (Severity s : new Severity[]{Severity.FIXED, Severity.NOT_FIXED}) {
            retestRow.add(swatchCell(s, severityColorPanels));
        }

        GridBagConstraints bg = new GridBagConstraints();
        bg.gridx = 0; bg.weightx = 1.0; bg.fill = GridBagConstraints.HORIZONTAL;
        bg.anchor = GridBagConstraints.WEST;
        bg.gridy = 0; badgesPanel.add(new JLabel("Severity Badge Colors"), bg);
        bg.gridy = 1; badgesPanel.add(badgesRow, bg);
        bg.gridy = 2; bg.insets = new Insets(12, 0, 0, 0);
        badgesPanel.add(new JLabel("Retest Status Colors"), bg);
        bg.gridy = 3; bg.insets = new Insets(0, 0, 0, 0);
        badgesPanel.add(retestRow, bg);
    }

    /** label + swatch cell with fixed geometry so cells align into a grid on wrap. */
    private JPanel swatchCell(Severity s, Map<Severity, JPanel> swatches) {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        JLabel lbl = new JLabel(com.icarus.ui.reportprofile.model.SectionLabelFormatter.format(s.name()) + ":");
        lbl.setPreferredSize(new Dimension(64, lbl.getPreferredSize().height));
        p.add(lbl);
        p.add(swatches.get(s));
        p.setPreferredSize(new Dimension(180, 26));
        return p;
    }

    private JPanel createFieldPanel(String label, JComponent comp) {
        JPanel panel = new JPanel(new BorderLayout(0, 4));
        panel.add(new JLabel(label), BorderLayout.NORTH);
        panel.add(comp, BorderLayout.CENTER);
        return panel;
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
        gbc.insets = new Insets(0, 0, 16, 16);
        
        if (bp == Breakpoint.COMPACT) {
            gbc.gridy = 0; gbc.gridx = 0; component.add(accentPanel, gbc);
            gbc.gridy = 1; component.add(secondaryPanel, gbc);
            gbc.gridy = 2; component.add(fontPanel, gbc);
            gbc.gridy = 3; component.add(fontSizePanel, gbc);
            gbc.gridy = 4; component.add(badgesPanel, gbc);
        } else if (bp == Breakpoint.NARROW) {
            gbc.gridy = 0; gbc.gridx = 0; component.add(accentPanel, gbc);
            gbc.gridx = 1; component.add(secondaryPanel, gbc);
            gbc.gridy = 1; gbc.gridx = 0; component.add(fontPanel, gbc);
            gbc.gridx = 1; component.add(fontSizePanel, gbc);
            gbc.gridy = 2; gbc.gridx = 0; gbc.gridwidth = 2; component.add(badgesPanel, gbc);
        } else {
            gbc.gridy = 0;
            gbc.gridx = 0; component.add(accentPanel, gbc);
            gbc.gridx = 1; component.add(secondaryPanel, gbc);
            gbc.gridx = 2; component.add(fontPanel, gbc);
            gbc.gridx = 3; component.add(fontSizePanel, gbc);
            gbc.gridy = 1; gbc.gridx = 0; gbc.gridwidth = 4; component.add(badgesPanel, gbc);
        }
        component.revalidate(); component.repaint();
    }
}
