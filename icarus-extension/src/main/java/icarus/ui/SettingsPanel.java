package icarus.ui;

import burp.api.montoya.MontoyaApi;
import icarus.core.ModuleConfig;

import javax.swing.*;
import javax.swing.border.TitledBorder;
import java.awt.*;
import java.awt.event.ActionListener;
import java.util.ArrayList;
import java.util.List;

public class SettingsPanel {

    private final MontoyaApi api;
    private final ModuleConfig config;
    private final JPanel mainPanel;

    private final List<Runnable> saveHooks = new ArrayList<>();
    private final List<JComponent> expandableLists = new ArrayList<>();

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
        JPanel pnlTop = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JCheckBox chkExpand = new JCheckBox("Expand Lists");
        chkExpand.addActionListener(e -> {
            boolean visible = chkExpand.isSelected();
            for (JComponent c : expandableLists) {
                c.setVisible(visible);
            }
            mainPanel.revalidate();
            mainPanel.repaint();
        });
        pnlTop.add(chkExpand);
        
        JButton btnSave = new JButton("Save Settings");
        btnSave.addActionListener(e -> saveAll());
        pnlTop.add(btnSave);
        mainPanel.add(pnlTop);

        // Global Toggles
        JPanel pnlGlobal = createSection("Enabled Modules");
        addCheckbox(pnlGlobal, "pv.enabled", "ParamValidator (JSON Mutation Fuzzer)");
        addCheckbox(pnlGlobal, "hv.enabled", "HTTP Verb Tester");
        addCheckbox(pnlGlobal, "jwt.enabled", "JWT / Bearer Token Checker");
        addCheckbox(pnlGlobal, "sh.enabled", "Sensitive Headers (Active)");
        addCheckbox(pnlGlobal, "sh.passive", "Sensitive Headers (Passive / Background)");
        addCheckbox(pnlGlobal, "export.enabled", "Postman Export");
        mainPanel.add(pnlGlobal);

        // WAF & Safe Lists
        JPanel pnlWaf = createSection("WAF Evasion & Safe Lists");
        addCheckbox(pnlWaf, "waf.detect_akamai", "Detect Akamai and prompt for Safe Mode");
        addTextArea(pnlWaf, "waf.safelist_payloads", "Safe List Payloads (one per line):", true);
        mainPanel.add(pnlWaf);

        // ParamValidator
        JPanel pnlPv = createSection("ParamValidator");
        addCheckbox(pnlPv, "pv.structural", "Structural mutations");
        addCheckbox(pnlPv, "pv.type_confusion", "Type confusion");
        addCheckbox(pnlPv, "pv.boundary", "Boundary mutations");
        addCheckbox(pnlPv, "pv.injection", "Injection mutations");
        addCheckbox(pnlPv, "pv.behavioral_analysis", "Behavioral Analysis (Detect anomalies via diffing)");
        addField(pnlPv, "pv.max_mutations", "Max mutations per request:");
        addField(pnlPv, "pv.max_repeater", "Max Repeater tabs:");
        
        // Expandable payload lists
        addTextArea(pnlPv, "pv.payload_sqli", "SQLi Payloads:", true);
        addTextArea(pnlPv, "pv.payload_xss", "XSS Payloads:", true);
        addTextArea(pnlPv, "pv.payload_path_traversal", "Path Traversal Payloads:", true);
        addTextArea(pnlPv, "pv.payload_nosqli", "NoSQLi Payloads:", true);
        addTextArea(pnlPv, "pv.payload_format_string", "Format String Payloads:", true);
        
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
        
        // Initially hide expandable lists
        for (JComponent c : expandableLists) {
            c.setVisible(false);
        }
    }

    private JPanel createSection(String title) {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createEtchedBorder(), title, TitledBorder.LEFT, TitledBorder.TOP));
        panel.setAlignmentX(Component.LEFT_ALIGNMENT);
        
        JPanel wrapper = new JPanel(new BorderLayout());
        wrapper.add(panel, BorderLayout.NORTH);
        wrapper.setAlignmentX(Component.LEFT_ALIGNMENT);
        wrapper.setMaximumSize(new Dimension(800, 1000));
        return panel;
    }

    private void addCheckbox(JPanel parent, String key, String label) {
        JCheckBox cb = new JCheckBox(label, config.getBool(key, true));
        cb.setAlignmentX(Component.LEFT_ALIGNMENT);
        parent.add(cb);
        saveHooks.add(() -> config.set(key, cb.isSelected()));
    }

    private void addField(JPanel parent, String key, String label) {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT));
        p.setAlignmentX(Component.LEFT_ALIGNMENT);
        p.add(new JLabel(label));
        JTextField tf = new JTextField(config.getString(key, ""), 20);
        p.add(tf);
        parent.add(p);
        saveHooks.add(() -> config.set(key, tf.getText()));
    }
    
    private void addTextArea(JPanel parent, String key, String label, boolean expandable) {
        JPanel p = new JPanel(new BorderLayout(5, 5));
        p.setAlignmentX(Component.LEFT_ALIGNMENT);
        p.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));
        p.add(new JLabel(label), BorderLayout.NORTH);
        
        JTextArea ta = new JTextArea(config.getString(key, ""), 5, 40);
        JScrollPane scroll = new JScrollPane(ta);
        p.add(scroll, BorderLayout.CENTER);
        
        parent.add(p);
        saveHooks.add(() -> config.set(key, ta.getText()));
        
        if (expandable) {
            expandableLists.add(p);
        }
    }

    private void saveAll() {
        for (Runnable hook : saveHooks) {
            hook.run();
        }
        StringBuilder sb = new StringBuilder();
        for (var entry : config.snapshot().entrySet()) {
            sb.append(entry.getKey()).append("=").append(entry.getValue().replace("\n", "\\n")).append("\n");
        }
        api.persistence().extensionData().setString("config", sb.toString());
        api.logging().logToOutput("Settings saved.");
    }
}
