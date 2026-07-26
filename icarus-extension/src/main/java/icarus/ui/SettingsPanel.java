package icarus.ui;

import burp.api.montoya.MontoyaApi;
import icarus.core.ModuleConfig;

import javax.swing.*;
import javax.swing.border.TitledBorder;
import java.awt.*;
import java.awt.event.ActionListener;
import java.util.ArrayList;
import java.util.List;

/**
 * Settings UI. Maps directly to ModuleConfig.
 */
public class SettingsPanel {

    private final MontoyaApi api;
    private final ModuleConfig config;
    private final JPanel mainPanel;

    private final List<Runnable> saveHooks = new ArrayList<>();

    public SettingsPanel(MontoyaApi api, ModuleConfig config) {
        this.api = api;
        this.config = config;
        this.mainPanel = new JPanel();
        this.mainPanel.setLayout(new BoxLayout(this.mainPanel, BoxLayout.Y_AXIS));
        this.mainPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        buildUI();
    }

    public Component getComponent() {
        JScrollPane scroll = new JScrollPane(mainPanel);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        return scroll;
    }

    private void buildUI() {
        // Global Toggles
        JPanel pnlGlobal = createSection("Enabled Modules");
        addCheckbox(pnlGlobal, "pv.enabled", "ParamValidator (JSON Mutation Fuzzer)");
        addCheckbox(pnlGlobal, "hv.enabled", "HTTP Verb Tester");
        addCheckbox(pnlGlobal, "jwt.enabled", "JWT / Bearer Token Checker");
        addCheckbox(pnlGlobal, "sh.enabled", "Sensitive Headers (Active)");
        addCheckbox(pnlGlobal, "sh.passive", "Sensitive Headers (Passive / Background)");
        addCheckbox(pnlGlobal, "export.enabled", "Postman Export");
        mainPanel.add(pnlGlobal);

        // Evidence
        JPanel pnlEvidence = createSection("Evidence & Reporting");
        addCheckbox(pnlEvidence, "evidence.enabled", "Enable Report Generation");
        addCheckbox(pnlEvidence, "evidence.auto_capture", "Auto-capture screenshots of requests/responses");
        addCheckbox(pnlEvidence, "evidence.html_report", "Generate HTML Report");
        addField(pnlEvidence, "evidence.output_dir", "Output Directory:");
        mainPanel.add(pnlEvidence);

        // ParamValidator
        JPanel pnlPv = createSection("ParamValidator");
        addCheckbox(pnlPv, "pv.structural", "Structural mutations (null, empty, remove)");
        addCheckbox(pnlPv, "pv.type_confusion", "Type confusion (string/number/boolean swaps)");
        addCheckbox(pnlPv, "pv.boundary", "Boundary (overflow, negative, long string)");
        addCheckbox(pnlPv, "pv.injection", "Injection (SQLi, XSS, Path Traversal)");
        addCheckbox(pnlPv, "pv.require_baseline", "Require successful baseline (HTTP 2xx)");
        addField(pnlPv, "pv.max_mutations", "Max mutations per request:");
        addField(pnlPv, "pv.max_repeater", "Max Repeater tabs:");
        mainPanel.add(pnlPv);

        // HTTP Verb
        JPanel pnlHv = createSection("HTTP Verb Tester");
        addCheckbox(pnlHv, "hv.test_get", "Test GET");
        addCheckbox(pnlHv, "hv.test_post", "Test POST");
        addCheckbox(pnlHv, "hv.test_put", "Test PUT");
        addCheckbox(pnlHv, "hv.test_delete", "Test DELETE");
        addCheckbox(pnlHv, "hv.test_options", "Test OPTIONS");
        addCheckbox(pnlHv, "hv.test_trace", "Test TRACE");
        addCheckbox(pnlHv, "hv.enable_state_changing", "Enable state-changing methods (POST/PUT/DELETE/PATCH)");
        addField(pnlHv, "hv.body_strategy", "Body Strategy (AUTO/KEEP/REMOVE):");
        mainPanel.add(pnlHv);

        // Save Button
        JPanel pnlSave = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JButton btnSave = new JButton("Save Settings");
        btnSave.addActionListener(e -> saveAll());
        pnlSave.add(btnSave);
        mainPanel.add(pnlSave);
    }

    private JPanel createSection(String title) {
        JPanel panel = new JPanel(new GridLayout(0, 1, 5, 5));
        panel.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createEtchedBorder(), title, TitledBorder.LEFT, TitledBorder.TOP));
        panel.setAlignmentX(Component.LEFT_ALIGNMENT);
        // Wrapper to stop it stretching vertically in BoxLayout
        JPanel wrapper = new JPanel(new BorderLayout());
        wrapper.add(panel, BorderLayout.NORTH);
        wrapper.setAlignmentX(Component.LEFT_ALIGNMENT);
        wrapper.setMaximumSize(new Dimension(800, 300));
        return panel; // return the inner panel so we can add to grid
    }

    private void addCheckbox(JPanel parent, String key, String label) {
        JCheckBox cb = new JCheckBox(label, config.getBool(key, true));
        parent.add(cb);
        saveHooks.add(() -> config.set(key, cb.isSelected()));
    }

    private void addField(JPanel parent, String key, String label) {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT));
        p.add(new JLabel(label));
        JTextField tf = new JTextField(config.getString(key, ""), 20);
        p.add(tf);
        parent.add(p);
        saveHooks.add(() -> config.set(key, tf.getText()));
    }

    private void saveAll() {
        for (Runnable hook : saveHooks) {
            hook.run();
        }

        // Persist
        StringBuilder sb = new StringBuilder();
        for (var entry : config.snapshot().entrySet()) {
            sb.append(entry.getKey()).append("=").append(entry.getValue()).append("\n");
        }
        api.persistence().extensionData().setString("config", sb.toString());
        api.logging().logToOutput("Settings saved.");
    }
}
