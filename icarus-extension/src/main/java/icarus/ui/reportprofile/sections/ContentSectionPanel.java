package icarus.ui.reportprofile.sections;

import icarus.ui.reportprofile.components.ToggleSwitch;
import icarus.ui.reportprofile.layout.Breakpoint;
import icarus.ui.reportprofile.layout.ResponsiveSection;
import icarus.report.model.FindingField;
import java.util.EnumMap;
import java.util.Map;

import javax.swing.*;
import java.awt.*;

public class ContentSectionPanel implements ResponsiveSection {
    private final JPanel component = new JPanel(new GridBagLayout());

    public final ToggleSwitch chkIncludeEvidence = new ToggleSwitch(true);
    public final ToggleSwitch chkToc = new ToggleSwitch(true);
    public final ToggleSwitch chkIncludeReq = new ToggleSwitch(true);
    public final ToggleSwitch chkIncludeRes = new ToggleSwitch(true);
    public final JSpinner spinMaxReqBytes = new JSpinner(new SpinnerNumberModel(4096, 512, 65536, 512));
    public final JSpinner spinMaxResBytes = new JSpinner(new SpinnerNumberModel(4096, 512, 65536, 512));

    public final Map<FindingField, ToggleSwitch> fieldCheckboxes = new EnumMap<>(FindingField.class);

    private final JPanel pnlToggles = new JPanel(new GridBagLayout());
    private final JPanel pnlFields = new JPanel(new GridBagLayout());

    public ContentSectionPanel() {
        for (FindingField f : FindingField.values()) {
            fieldCheckboxes.put(f, new ToggleSwitch(true));
        }
        setupToggles();
    }

    private void setupToggles() {
        // One shared 2-column grid: col 0 = the four toggle rows, col 1 = the
        // byte-limit control belonging to that row (only the two HTTP rows have
        // one). weightx sits on a trailing spacer so the two columns stay
        // adjacent instead of the right column floating off to the edge.
        GridBagConstraints g = new GridBagConstraints();
        g.fill = GridBagConstraints.HORIZONTAL;
        g.anchor = GridBagConstraints.WEST;
        g.weightx = 0; g.insets = new Insets(0, 0, 8, 24);

        g.gridx = 0;
        g.gridy = 0; pnlToggles.add(createTogglePanel("Attach evidence screenshots to findings", chkIncludeEvidence), g);
        g.gridy = 1; pnlToggles.add(createTogglePanel("Generate Table of Contents", chkToc), g);
        g.gridy = 2; pnlToggles.add(createTogglePanel("Include HTTP Request", chkIncludeReq), g);
        g.gridy = 3; pnlToggles.add(createTogglePanel("Include HTTP Response", chkIncludeRes), g);

        g.insets = new Insets(0, 0, 8, 0);
        g.gridx = 1; g.gridy = 2; pnlToggles.add(createSpinnerPanel("Request max bytes", spinMaxReqBytes), g);
        g.gridy = 3; pnlToggles.add(createSpinnerPanel("Response max bytes", spinMaxResBytes), g);

        g.gridx = 2; g.gridy = 0; g.weightx = 1.0;
        pnlToggles.add(Box.createHorizontalGlue(), g);
    }

    private JPanel createTogglePanel(String label, ToggleSwitch toggle) {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        p.add(toggle);
        p.add(new JLabel(label));
        return p;
    }

    private JPanel createSpinnerPanel(String label, JSpinner spinner) {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        p.add(new JLabel(label));
        p.add(spinner);
        return p;
    }

    @Override
    public Component component() {
        return component;
    }

    @Override
    public void onBreakpointChanged(Breakpoint bp) {
        component.removeAll();
        pnlFields.removeAll();

        GridBagConstraints g = new GridBagConstraints();
        g.fill = GridBagConstraints.HORIZONTAL;
        g.weightx = 1.0;
        g.insets = new Insets(0, 0, 8, 8);

        int cols = (bp == Breakpoint.COMPACT || bp == Breakpoint.NARROW) ? 1 : 2;
        int i = 0;
        for (Map.Entry<FindingField, ToggleSwitch> e : fieldCheckboxes.entrySet()) {
            g.gridx = i % cols;
            g.gridy = i / cols;
            // Title case the enum name for label
            String name = e.getKey().name().toLowerCase().replace("_", " ");
            name = Character.toUpperCase(name.charAt(0)) + name.substring(1);
            pnlFields.add(createTogglePanel(name, e.getValue()), g);
            i++;
        }

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;
        gbc.insets = new Insets(0, 0, 16, 16);

        JPanel fieldsWrapper = new JPanel(new BorderLayout());
        JLabel fieldsLabel = new JLabel("Finding fields to include");
        fieldsLabel.setFont(fieldsLabel.getFont().deriveFont(java.awt.Font.BOLD));
        fieldsWrapper.add(fieldsLabel, BorderLayout.NORTH);
        fieldsWrapper.add(pnlFields, BorderLayout.CENTER);

        if (bp == Breakpoint.COMPACT || bp == Breakpoint.NARROW) {
            gbc.gridx = 0; gbc.gridy = 0; component.add(pnlToggles, gbc);
            gbc.gridy = 1; component.add(fieldsWrapper, gbc);
        } else {
            gbc.gridx = 0; gbc.gridy = 0; component.add(pnlToggles, gbc);
            gbc.gridx = 1; component.add(fieldsWrapper, gbc);
        }
        component.revalidate();
        component.repaint();
    }
}
