package icarus.ui;

import burp.api.montoya.MontoyaApi;
import icarus.core.ModuleConfig;
import icarus.evidence.EvidenceColorScheme;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

public class SettingsPanel {

    private final MontoyaApi api;
    private final ModuleConfig config;
    private final ThemeHelper themeHelper;
    private final JPanel mainPanel;

    private final List<Runnable> saveHooks = new ArrayList<>();
    private final List<JComponent> expandableLists = new ArrayList<>();

    public SettingsPanel(MontoyaApi api, ModuleConfig config, ThemeHelper themeHelper) {
        this.api = api;
        this.config = config;
        this.themeHelper = themeHelper;
        this.mainPanel = new JPanel();
        this.mainPanel.setLayout(new BoxLayout(this.mainPanel, BoxLayout.Y_AXIS));
        this.mainPanel.setBorder(BorderFactory.createEmptyBorder(15, 15, 15, 15));
        themeHelper.applyTheme(this.mainPanel);

        buildUI();
    }

    public Component getComponent() {
        JScrollPane scroll = new JScrollPane(mainPanel);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        scroll.setBorder(null);
        themeHelper.applyTheme(scroll);
        return scroll;
    }

    private void buildUI() {
        JPanel pnlTop = new JPanel(new FlowLayout(FlowLayout.LEFT));
        themeHelper.applyTheme(pnlTop);

        JCheckBox chkExpand = new JCheckBox("Expand payload lists");
        themeHelper.applyTheme(chkExpand);
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
        themeHelper.styleButton(btnSave);
        btnSave.addActionListener(e -> saveAll());
        pnlTop.add(btnSave);
        mainPanel.add(pnlTop);
        mainPanel.add(Box.createRigidArea(new Dimension(0, 10)));

        // Global Toggles
        JPanel pnlGlobal = createSection("General & Modules");
        addCheckbox(pnlGlobal, "ui.show_popups", "Show pop-up dialog with findings after an active scan completes");
        addCheckbox(pnlGlobal, "pv.enabled", "ParamValidator (JSON Mutation Fuzzer)");
        addCheckbox(pnlGlobal, "hv.enabled", "HTTP Verb Tester");
        addCheckbox(pnlGlobal, "jwt.enabled", "JWT / Bearer Token Checker");
        addCheckbox(pnlGlobal, "sh.enabled", "Sensitive Headers (Active)");
        addCheckbox(pnlGlobal, "sh.passive", "Sensitive Headers (Passive / Background)");
        addCheckbox(pnlGlobal, "export.enabled", "Postman Export");
        addCheckbox(pnlGlobal, "rl.enabled", "Rate Limit Tester");
        mainPanel.add(pnlGlobal);
        mainPanel.add(Box.createRigidArea(new Dimension(0, 10)));

        // WAF & Safe Lists
        JPanel pnlWaf = createSection("WAF Evasion & Safe Lists");
        addCheckbox(pnlWaf, "waf.detect_akamai", "Detect Akamai and prompt for Safe Mode");
        addTextArea(pnlWaf, "waf.safelist_payloads", "Safe List Payloads (one per line):", true);
        mainPanel.add(pnlWaf);
        mainPanel.add(Box.createRigidArea(new Dimension(0, 10)));

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
        mainPanel.add(Box.createRigidArea(new Dimension(0, 10)));

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
        mainPanel.add(Box.createRigidArea(new Dimension(0, 10)));

        // JWT Checker
        JPanel pnlJwt = createSection("JWT / Bearer Token Checker");
        addCheckbox(pnlJwt, "jwt.redact_sensitive_claims", "Redact sensitive claim values in findings/logs/reports (show key only)");
        mainPanel.add(pnlJwt);
        mainPanel.add(Box.createRigidArea(new Dimension(0, 10)));

        // Rate Limit Tester
        JPanel pnlRl = createSection("Rate Limit Tester");
        addField(pnlRl, "rl.request_count", "Requests per blast:");
        addField(pnlRl, "rl.concurrency", "Concurrent threads:");
        addField(pnlRl, "rl.cooldown_wait_ms", "Cooldown between bypasses (ms):");
        addCheckbox(pnlRl, "rl.bypass_headers", "Try IP header rotation bypass (X-Forwarded-For, etc.)");
        addCheckbox(pnlRl, "rl.bypass_path", "Try path normalization bypass (/api/./v1, //api, etc.)");
        addCheckbox(pnlRl, "rl.bypass_query", "Try cache-buster query param bypass (?_icarus=N)");
        mainPanel.add(pnlRl);
        mainPanel.add(Box.createRigidArea(new Dimension(0, 10)));

        // Evidence
        JPanel pnlEvidence = createSection("Evidence Capture");
        addComboBox(pnlEvidence, "evidence.colorscheme", "Screenshot Color Scheme:", EvidenceColorScheme.names());
        mainPanel.add(pnlEvidence);

        // Initially hide expandable lists
        for (JComponent c : expandableLists) {
            c.setVisible(false);
        }
    }

    private JPanel createSection(String title) {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(themeHelper.createSectionBorder(title));
        themeHelper.applyTheme(panel);

        JPanel wrapper = new JPanel(new BorderLayout());
        themeHelper.applyTheme(wrapper);
        wrapper.add(panel, BorderLayout.NORTH);
        wrapper.setAlignmentX(Component.LEFT_ALIGNMENT);
        wrapper.setMaximumSize(new Dimension(800, 1200));
        return wrapper;
    }

    private void addCheckbox(JPanel parent, String key, String label) {
        JCheckBox cb = new JCheckBox(label, config.getBool(key, true));
        themeHelper.applyTheme(cb);
        cb.setAlignmentX(Component.LEFT_ALIGNMENT);

        JPanel p = (JPanel) parent.getComponent(0); // The inner BoxLayout panel
        p.add(cb);
        p.add(Box.createRigidArea(new Dimension(0, 5)));

        saveHooks.add(() -> config.set(key, cb.isSelected()));
    }

    private void addField(JPanel parent, String key, String label) {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT));
        themeHelper.applyTheme(p);
        p.setAlignmentX(Component.LEFT_ALIGNMENT);

        JLabel lbl = new JLabel(label);
        themeHelper.applyTheme(lbl);
        p.add(lbl);

        JTextField tf = new JTextField(config.getString(key, ""), 20);
        themeHelper.applyTheme(tf);
        p.add(tf);

        JPanel inner = (JPanel) parent.getComponent(0);
        inner.add(p);

        saveHooks.add(() -> config.set(key, tf.getText()));
    }

    private void addComboBox(JPanel parent, String key, String label, String[] options) {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT));
        themeHelper.applyTheme(p);
        p.setAlignmentX(Component.LEFT_ALIGNMENT);

        JLabel lbl = new JLabel(label);
        themeHelper.applyTheme(lbl);
        p.add(lbl);

        JComboBox<String> combo = new JComboBox<>(options);
        combo.setSelectedItem(config.getString(key, options[0]));
        themeHelper.applyTheme(combo);
        p.add(combo);

        JPanel inner = (JPanel) parent.getComponent(0);
        inner.add(p);

        saveHooks.add(() -> config.set(key, (String) combo.getSelectedItem()));
    }

    private void addTextArea(JPanel parent, String key, String label, boolean expandable) {
        JPanel p = new JPanel(new BorderLayout(5, 5));
        themeHelper.applyTheme(p);
        p.setAlignmentX(Component.LEFT_ALIGNMENT);
        p.setBorder(BorderFactory.createEmptyBorder(5, 5, 10, 5));

        JLabel lbl = new JLabel(label);
        themeHelper.applyTheme(lbl);
        p.add(lbl, BorderLayout.NORTH);

        JTextArea ta = new JTextArea(config.getString(key, ""), 5, 40);
        themeHelper.styleTextArea(ta);

        JScrollPane scroll = new JScrollPane(ta);
        themeHelper.applyTheme(scroll);
        p.add(scroll, BorderLayout.CENTER);

        JPanel inner = (JPanel) parent.getComponent(0);
        inner.add(p);
        saveHooks.add(() -> config.set(key, ta.getText()));

        if (expandable) {
            expandableLists.add(p);
        }
    }

    private void saveAll() {
        for (Runnable hook : saveHooks) {
            hook.run();
        }
        api.persistence().extensionData().setString("config", config.serialize());
        api.logging().logToOutput("Settings saved.");
    }
}
