package com.icarus.ui.reportprofile.sections;

import com.icarus.ui.reportprofile.layout.Breakpoint;
import com.icarus.ui.reportprofile.layout.ResponsiveSection;
import com.icarus.ui.reportprofile.layout.WrapLayout;

import javax.swing.*;
import java.awt.*;

public class BrandingSectionPanel implements ResponsiveSection {
    private final JPanel component = new JPanel(new GridBagLayout());

    public final JTextField txtDocTitle = new JTextField(15);
    public final JTextField txtClassification = new JTextField("Confidential", 15);
    public final JTextField txtAuthor = new JTextField(15);
    public final JTextField txtReviewer = new JTextField(15);
    public final JTextField txtApprover = new JTextField(15);
    public final JTextField txtEnvironment = new JTextField(15);
    public final JTextField txtCompanyLogo = new JTextField(15);
    public final JTextField txtClientLogo = new JTextField(15);

    private JPanel createFieldPanel(String label, JTextField field) {
        JPanel panel = new JPanel(new BorderLayout(0, 4));
        panel.add(new JLabel(label), BorderLayout.NORTH);
        panel.add(field, BorderLayout.CENTER);
        return panel;
    }

    private JPanel createFilePanel(String label, JTextField field) {
        JPanel panel = new JPanel(new BorderLayout(0, 4));
        panel.add(new JLabel(label), BorderLayout.NORTH);
        
        JPanel centerPanel = new JPanel(new BorderLayout(8, 0));
        centerPanel.add(field, BorderLayout.CENTER);
        JButton browseBtn = new JButton("Browse…");
        browseBtn.addActionListener(e -> {
            JFileChooser chooser = new JFileChooser();
            chooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
            if (chooser.showOpenDialog(panel) == JFileChooser.APPROVE_OPTION) {
                field.setText(chooser.getSelectedFile().getAbsolutePath());
            }
        });
        centerPanel.add(browseBtn, BorderLayout.EAST);
        
        panel.add(centerPanel, BorderLayout.CENTER);
        return panel;
    }

    private final JPanel pnlDocTitle = createFieldPanel("Document Title", txtDocTitle);
    private final JPanel pnlClassification = createFieldPanel("Classification", txtClassification);
    private final JPanel pnlAuthor = createFieldPanel("Author", txtAuthor);
    private final JPanel pnlReviewer = createFieldPanel("Reviewer", txtReviewer);
    private final JPanel pnlApprover = createFieldPanel("Approver", txtApprover);
    private final JPanel pnlEnvironment = createFieldPanel("Environment", txtEnvironment);
    private final JPanel pnlCompanyLogo = createFilePanel("Company Logo", txtCompanyLogo);
    private final JPanel pnlClientLogo = createFilePanel("Client Logo", txtClientLogo);

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

        int cols = (bp == Breakpoint.COMPACT || bp == Breakpoint.NARROW) ? 1 : 2;
        JPanel[] panels = {pnlDocTitle, pnlClassification, pnlAuthor, pnlReviewer,
                pnlApprover, pnlEnvironment, pnlCompanyLogo, pnlClientLogo};

        for (int i = 0; i < panels.length; i++) {
            gbc.gridx = i % cols;
            gbc.gridy = i / cols;
            component.add(panels[i], gbc);
        }
        component.revalidate();
        component.repaint();
    }
}
