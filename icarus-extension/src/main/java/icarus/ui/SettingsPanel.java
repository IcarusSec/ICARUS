package icarus.ui;

import burp.api.montoya.MontoyaApi;
import icarus.Icarus;
import icarus.autoauth.AutoAuthModule;
import icarus.core.EvidencePaths;
import icarus.core.I18n;
import icarus.core.ModuleConfig;
import icarus.core.Severity;
import icarus.evidence.EvidenceColorScheme;
import icarus.evidence.EvidenceUiHelpers;
import icarus.mcp.IcarusMcpServer;
import icarus.modules.ParamValidatorModule;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

public class SettingsPanel {

    private final MontoyaApi api;
    private final ModuleConfig config;
    private final ThemeHelper themeHelper;
    private final AutoAuthModule autoAuth;
    private final IcarusMcpServer mcpServer;
    private final JPanel rootPanel;

    private final List<Runnable> saveHooks = new ArrayList<>();

    public SettingsPanel(MontoyaApi api, ModuleConfig config, ThemeHelper themeHelper, AutoAuthModule autoAuth, IcarusMcpServer mcpServer) {
        this.api = api;
        this.config = config;
        this.themeHelper = themeHelper;
        this.autoAuth = autoAuth;
        this.mcpServer = mcpServer;
        
        // Root Panel with BorderLayout. Center = Tabs, South = Footer
        this.rootPanel = new JPanel(new BorderLayout());
        themeHelper.applyTheme(this.rootPanel);
        // Apply the container background to root panel to act as Canvas
        this.rootPanel.setBackground(themeHelper.getBackgroundColor());
        
        // Initialize UI on EDT
        if (SwingUtilities.isEventDispatchThread()) {
            buildUI();
        } else {
            SwingUtilities.invokeLater(this::buildUI);
        }
    }

    public Component getComponent() {
        return rootPanel;
    }

    private Component wrapInScroll(Component content) {
        JScrollPane scroll = new JScrollPane(content);
        scroll.setBorder(null);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        themeHelper.applyTheme(scroll);
        return scroll;
    }

    private void buildUI() {
        JTabbedPane settingsTabs = new JTabbedPane();
        settingsTabs.putClientProperty("JTabbedPane.tabType", "underlined");
        themeHelper.applyTheme(settingsTabs);
        rootPanel.add(settingsTabs, BorderLayout.CENTER);

        // 1. General & Integrations
        JPanel pnlGeneralTab = new JPanel();
        pnlGeneralTab.setLayout(new BoxLayout(pnlGeneralTab, BoxLayout.Y_AXIS));
        pnlGeneralTab.setBorder(new EmptyBorder(16, 16, 16, 16));
        pnlGeneralTab.setBackground(themeHelper.getBackgroundColor());
        
        CardPanel cardGlobal = new CardPanel(I18n.t("settings.section.general_and_modules"), "shield");
        addComboBoxToForm(cardGlobal, ModuleConfig.UI_LANGUAGE_KEY, I18n.t("settings.combo.language"), new String[]{"en", "pt-BR"});
        
        JPanel pnlGlobalGrid = new JPanel(new GridBagLayout());
        pnlGlobalGrid.setOpaque(false);
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 0; gbc.insets = new Insets(0, 0, 8, 16);
        
        gbc.gridx = 0; gbc.gridy = 0; addCheckboxToGrid(pnlGlobalGrid, gbc, "ui.show_popups", I18n.t("settings.checkbox.show_popups"), false);
        gbc.gridx = 1; addCheckboxToGrid(pnlGlobalGrid, gbc, "verbose.enabled", I18n.t("settings.checkbox.verbose_mode"), true);
        gbc.gridx = 0; gbc.gridy = 1; addCheckboxToGrid(pnlGlobalGrid, gbc, "pv.enabled", I18n.t("settings.checkbox.pv_enabled"), true);
        gbc.gridx = 1; addCheckboxToGrid(pnlGlobalGrid, gbc, "hv.enabled", I18n.t("settings.checkbox.hv_enabled"), true);
        gbc.gridx = 0; gbc.gridy = 2; addCheckboxToGrid(pnlGlobalGrid, gbc, "jwt.enabled", I18n.t("settings.checkbox.jwt_enabled"), true);
        gbc.gridx = 1; addCheckboxToGrid(pnlGlobalGrid, gbc, "sh.enabled", I18n.t("settings.checkbox.sh_enabled"), true);
        gbc.gridx = 0; gbc.gridy = 3; addCheckboxToGrid(pnlGlobalGrid, gbc, "sh.passive", I18n.t("settings.checkbox.sh_passive"), true);
        gbc.gridx = 1; addCheckboxToGrid(pnlGlobalGrid, gbc, "export.enabled", I18n.t("settings.checkbox.export_enabled"), true);
        gbc.gridx = 0; gbc.gridy = 4; addCheckboxToGrid(pnlGlobalGrid, gbc, "rl.enabled", I18n.t("settings.checkbox.rl_enabled"), true);
        gbc.gridx = 1; addCheckboxToGrid(pnlGlobalGrid, gbc, "autoauth.enabled", I18n.t("settings.checkbox.autoauth_enabled"), true);
        gbc.gridx = 0; gbc.gridy = 5; addCheckboxToGrid(pnlGlobalGrid, gbc, "pem.enabled", I18n.t("settings.checkbox.pem_enabled"), true);
        gbc.gridx = 1; addCheckboxToGrid(pnlGlobalGrid, gbc, "pem.passive", I18n.t("settings.checkbox.pem_passive"), true);
        gbc.gridx = 0; gbc.gridy = 6; addCheckboxToGrid(pnlGlobalGrid, gbc, "debug.enabled", I18n.t("settings.checkbox.debug_enabled"), false);

        // Add horizontal glue to compact the grid to the left
        gbc.gridx = 2; gbc.gridy = 0; gbc.weightx = 1.0;
        pnlGlobalGrid.add(Box.createHorizontalGlue(), gbc);
        
        cardGlobal.addFormRow(pnlGlobalGrid);

        CardPanel cardWaf = new CardPanel(I18n.t("settings.section.waf_evasion"), "shield");
        addCheckboxToForm(cardWaf, "waf.detect", I18n.t("settings.checkbox.waf.detect"), true);
        cardWaf.addFormRow(mutedLabel(I18n.t("settings.help.waf.detect")));

        CardPanel cardAuto = new CardPanel(I18n.t("settings.section.autoauth"), "key");
        addSpinnerToForm(cardAuto, "autoauth.refresh_minutes", I18n.t("settings.field.autoauth.refresh_minutes"), 10, 1, 1440, 1);
        JLabel autoAuthStatus = new JLabel(autoAuth.statusSummary());
        autoAuthStatus.setForeground(UIManager.getColor("Label.disabledForeground"));
        cardAuto.addFormRow(autoAuthStatus);
        
        JButton btnClearAuth = new JButton(I18n.t("settings.button.clear_active_config"));
        btnClearAuth.putClientProperty("JButton.buttonType", "toolBarButton");
        btnClearAuth.addActionListener(e -> {
            autoAuth.clearSession();
            autoAuthStatus.setText(autoAuth.statusSummary());
        });
        cardAuto.addFormRow(btnClearAuth);

        CardPanel cardMcp = new CardPanel(I18n.t("settings.section.mcp"), "cpu");
        JLabel mcpStatusBadge = new JLabel(mcpServer.isRunning() ? I18n.t("settings.mcp.status.active", "● ACTIVE") : I18n.t("settings.mcp.status.stopped", "● STOPPED"));
        mcpStatusBadge.setFont(mcpStatusBadge.getFont().deriveFont(Font.BOLD));
        mcpStatusBadge.setForeground(mcpServer.isRunning() ? Color.decode("#00E676") : Color.decode("#FF1744"));
        
        JPanel headerPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        headerPanel.setOpaque(false);
        headerPanel.add(mcpStatusBadge);
        headerPanel.add(Box.createRigidArea(new Dimension(8, 0)));
        headerPanel.add(new JLabel(I18n.t("settings.label.mcp_hint")));
        cardMcp.addFormRow(headerPanel);

        JSpinner spinMcpPort = new JSpinner(new SpinnerNumberModel(config.getInt("mcp.port", 61337), 1024, 65535, 1));
        spinMcpPort.setEditor(new JSpinner.NumberEditor(spinMcpPort, "#"));
        JCheckBox chkMcp = new JCheckBox(I18n.t("settings.checkbox.mcp_enabled"), config.getBool("mcp.enabled", false));
        chkMcp.setOpaque(false);
        
        JPanel mcpPortPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        mcpPortPanel.setOpaque(false);
        mcpPortPanel.add(new JLabel(I18n.t("settings.label.mcp_port") + " "));
        mcpPortPanel.add(spinMcpPort);
        mcpPortPanel.add(Box.createRigidArea(new Dimension(16, 0)));
        mcpPortPanel.add(chkMcp);
        cardMcp.addFormRow(mcpPortPanel);

        JButton btnRestartMcp = new JButton(I18n.t("settings.button.restart_mcp", "Restart Server"), EvidenceUiHelpers.createIcon("refresh"));
        btnRestartMcp.addActionListener(e -> {
            btnRestartMcp.setEnabled(false);
            btnRestartMcp.setText(I18n.t("settings.button.restarting_mcp", "Restarting..."));
            
            boolean enabled = chkMcp.isSelected();
            int port = (int) spinMcpPort.getValue();
            config.set("mcp.enabled", enabled);
            config.set("mcp.port", port);
            api.persistence().extensionData().setString("config", config.serialize());
            
            SwingWorker<Void, Void> worker = new SwingWorker<>() {
                @Override
                protected Void doInBackground() {
                    mcpServer.stop();
                    if (enabled) {
                        mcpServer.start(port);
                    }
                    return null;
                }
                
                @Override
                protected void done() {
                    mcpStatusBadge.setText(mcpServer.isRunning() ? I18n.t("settings.mcp.status.active", "● ACTIVE") : I18n.t("settings.mcp.status.stopped", "● STOPPED"));
                    mcpStatusBadge.setForeground(mcpServer.isRunning() ? Color.decode("#00E676") : Color.decode("#FF1744"));
                    btnRestartMcp.setText(I18n.t("settings.button.restart_mcp", "Restart Server"));
                    btnRestartMcp.setEnabled(true);
                }
            };
            worker.execute();
        });
        cardMcp.addFormRow(btnRestartMcp);

        CardPanel cardEvidence = new CardPanel(I18n.t("settings.section.evidence"), "camera");
        addOutputDirField(cardEvidence);
        addComboBoxToForm(cardEvidence, "evidence.colorscheme", I18n.t("settings.combo.evidence.colorscheme"), EvidenceColorScheme.names());
        addCheckboxToForm(cardEvidence, "evidence.auto_capture", I18n.t("settings.checkbox.evidence.auto_capture"), true);
        addCheckboxToForm(cardEvidence, "evidence.include_project_name", I18n.t("settings.checkbox.evidence.include_project_name"), false);
        addComboBoxToForm(cardEvidence, "evidence.manual_severity", I18n.t("settings.combo.evidence.manual_severity"),
                java.util.Arrays.stream(Severity.values()).map(Enum::name).toArray(String[]::new));

        JPanel pnlReset = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        pnlReset.setOpaque(false);
        JButton btnReset = new JButton(I18n.t("settings.button.reset_to_default"));
        btnReset.putClientProperty("JButton.buttonType", "toolBarButton");
        btnReset.addActionListener(e -> resetToDefault());
        pnlReset.add(btnReset);

        pnlGeneralTab.add(cardGlobal);
        pnlGeneralTab.add(Box.createVerticalStrut(16));
        pnlGeneralTab.add(cardWaf);
        pnlGeneralTab.add(Box.createVerticalStrut(16));
        pnlGeneralTab.add(cardAuto);
        pnlGeneralTab.add(Box.createVerticalStrut(16));
        pnlGeneralTab.add(cardMcp);
        pnlGeneralTab.add(Box.createVerticalStrut(16));
        pnlGeneralTab.add(cardEvidence);
        pnlGeneralTab.add(Box.createVerticalStrut(16));
        pnlGeneralTab.add(pnlReset);
        pnlGeneralTab.add(Box.createVerticalGlue());
        settingsTabs.addTab(I18n.t("settings.subtab.general", "General & Integrations"), wrapInScroll(pnlGeneralTab));

        // 2. Active Scanners
        JPanel pnlActiveTab = new JPanel();
        pnlActiveTab.setLayout(new BoxLayout(pnlActiveTab, BoxLayout.Y_AXIS));
        pnlActiveTab.setBorder(new EmptyBorder(16, 16, 16, 16));
        pnlActiveTab.setBackground(themeHelper.getBackgroundColor());

        CardPanel cardPv = new CardPanel(I18n.t("settings.section.paramvalidator"), "adjustments-horizontal");
        
        // Scan depth
        addComboBoxToForm(cardPv, "pv.depth", I18n.t("settings.combo.pv.depth"), new String[]{"LIGHT", "MEDIUM", "DEEP"});
        cardPv.addFormRow(mutedLabel(I18n.t("settings.help.pv.depth")));

        addSpinnerToForm(cardPv, "pv.max_mutations", I18n.t("settings.field.pv.max_mutations"), 0, 0, 100000, 10);
        cardPv.addFormRow(mutedLabel(I18n.t("settings.help.pv.max_mutations")));

        // Mutation categories
        cardPv.addFormRow(subHeader(I18n.t("settings.header.pv.categories")));
        JPanel pnlPvCats = new JPanel(new GridBagLayout());
        pnlPvCats.setOpaque(false);
        gbc = new GridBagConstraints();
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 0; gbc.insets = new Insets(0, 0, 8, 16);
        gbc.gridx = 0; gbc.gridy = 0; addCheckboxToGrid(pnlPvCats, gbc, "pv.structural", I18n.t("settings.checkbox.pv.structural"), true);
        gbc.gridx = 1; addCheckboxToGrid(pnlPvCats, gbc, "pv.boundary", I18n.t("settings.checkbox.pv.boundary"), true);
        gbc.gridx = 0; gbc.gridy = 1; addCheckboxToGrid(pnlPvCats, gbc, "pv.type_confusion", I18n.t("settings.checkbox.pv.type_confusion"), true);
        gbc.gridx = 2; gbc.gridy = 0; gbc.weightx = 1.0;
        pnlPvCats.add(Box.createHorizontalGlue(), gbc);
        cardPv.addFormRow(pnlPvCats);

        // Injection techniques
        cardPv.addFormRow(subHeader(I18n.t("settings.header.pv.techniques")));
        JCheckBox chkPvInjection = new JCheckBox(I18n.t("settings.checkbox.pv.injection"), config.getBool("pv.injection", true));
        chkPvInjection.setOpaque(false);
        saveHooks.add(() -> config.set("pv.injection", chkPvInjection.isSelected()));
        cardPv.addFormRow(chkPvInjection);

        JPanel pnlPvTech = new JPanel(new GridBagLayout());
        pnlPvTech.setOpaque(false);
        String[][] techs = {
            {"pv.sqli", "settings.checkbox.pv.sqli"},
            {"pv.sqli_time", "settings.checkbox.pv.sqli_time"},
            {"pv.xss", "settings.checkbox.pv.xss"},
            {"pv.path_traversal", "settings.checkbox.pv.path_traversal"},
            {"pv.nosqli", "settings.checkbox.pv.nosqli"},
            {"pv.format_string", "settings.checkbox.pv.format_string"},
            {"pv.unicode", "settings.checkbox.pv.unicode"},
            {"pv.cmdi", "settings.checkbox.pv.cmdi"},
            {"pv.ssti", "settings.checkbox.pv.ssti"},
            {"pv.ssrf", "settings.checkbox.pv.ssrf"},
            {"pv.idor", "settings.checkbox.pv.idor"},
        };
        List<JCheckBox> techBoxes = new ArrayList<>();
        gbc = new GridBagConstraints();
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 0; gbc.insets = new Insets(0, 0, 8, 16);
        for (int i = 0; i < techs.length; i++) {
            gbc.gridx = i % 2; gbc.gridy = i / 2;
            techBoxes.add(addCheckboxToGrid(pnlPvTech, gbc, techs[i][0], I18n.t(techs[i][1]), true));
        }
        gbc.gridx = 2; gbc.gridy = 0; gbc.weightx = 1.0;
        pnlPvTech.add(Box.createHorizontalGlue(), gbc);
        cardPv.addFormRow(pnlPvTech);

        Runnable syncTech = () -> {
            boolean on = chkPvInjection.isSelected();
            for (JCheckBox b : techBoxes) b.setEnabled(on);
        };
        chkPvInjection.addItemListener(e -> { saveAll(); syncTech.run(); });
        syncTech.run();

        addCheckboxToForm(cardPv, "pv.behavioral_analysis", I18n.t("settings.checkbox.pv.behavioral_analysis"), false);

        // Payload editors
        ParamValidatorModule.ensurePayloadDefaults(config);
        JTabbedPane pvTabs = new JTabbedPane();
        pvTabs.putClientProperty("JTabbedPane.tabType", "underlined");
        pvTabs.addTab("SQLi", createTabbedTextArea("pv.payload_sqli"));
        pvTabs.addTab("SQLi time (string)", createTabbedTextArea("pv.payload_sqli_time"));
        pvTabs.addTab("SQLi time (number)", createTabbedTextArea("pv.payload_sqli_time_number"));
        pvTabs.addTab("XSS", createTabbedTextArea("pv.payload_xss"));
        pvTabs.addTab("Path Traversal", createTabbedTextArea("pv.payload_path_traversal"));
        pvTabs.addTab("NoSQLi", createTabbedTextArea("pv.payload_nosqli"));
        pvTabs.addTab("Format String", createTabbedTextArea("pv.payload_format_string"));
        pvTabs.addTab("Unicode / RTL", createTabbedTextField("pv.payload_unicode"));
        pvTabs.addTab("CMDi", createTabbedTextArea("pv.payload_cmdi"));
        pvTabs.addTab("SSTI", createTabbedTextArea("pv.payload_ssti"));
        pvTabs.addTab("SSRF targets", createTabbedTextArea("pv.payload_ssrf_heuristic"));
        cardPv.addFormRow(pvTabs);
        cardPv.addFormRow(mutedLabel(I18n.t("settings.help.pv.depth_extras")));

        CardPanel cardHv = new CardPanel(I18n.t("settings.section.http_verb"), "activity");
        addCheckboxToForm(cardHv, "hv.test_get", I18n.t("settings.checkbox.hv.test_get"), true);
        addCheckboxToForm(cardHv, "hv.test_post", I18n.t("settings.checkbox.hv.test_post"), true);
        addCheckboxToForm(cardHv, "hv.test_put", I18n.t("settings.checkbox.hv.test_put"), true);
        addCheckboxToForm(cardHv, "hv.test_delete", I18n.t("settings.checkbox.hv.test_delete"), true);
        addCheckboxToForm(cardHv, "hv.test_options", I18n.t("settings.checkbox.hv.test_options"), true);
        addCheckboxToForm(cardHv, "hv.test_trace", I18n.t("settings.checkbox.hv.test_trace"), true);
        addCheckboxToForm(cardHv, "hv.enable_state_changing", I18n.t("settings.checkbox.hv.enable_state_changing"), false);
        addComboBoxToForm(cardHv, "hv.body_strategy", I18n.t("settings.combo.hv.body_strategy"), new String[]{"AUTO", "KEEP", "REMOVE"});

        CardPanel cardRl = new CardPanel(I18n.t("settings.section.rate_limit"), "activity");
        addSpinnerToForm(cardRl, "rl.request_count", I18n.t("settings.field.rl.request_count"), 100, 10, 10000, 10);
        addSpinnerToForm(cardRl, "rl.concurrency", I18n.t("settings.field.rl.concurrency"), 10, 1, 100, 1);
        addSpinnerToForm(cardRl, "rl.cooldown_wait_ms", I18n.t("settings.field.rl.cooldown_wait_ms"), 0, 0, 60000, 100);
        addSpinnerToForm(cardRl, "rl.max_rps", I18n.t("settings.field.rl.max_rps"), 0, 0, 10000, 10);
        addCheckboxToForm(cardRl, "rl.bypass_headers", I18n.t("settings.checkbox.rl.bypass_headers"), true);
        addCheckboxToForm(cardRl, "rl.bypass_path", I18n.t("settings.checkbox.rl.bypass_path"), true);
        addCheckboxToForm(cardRl, "rl.bypass_query", I18n.t("settings.checkbox.rl.bypass_query"), true);

        pnlActiveTab.add(cardPv);
        pnlActiveTab.add(Box.createVerticalStrut(16));
        pnlActiveTab.add(cardHv);
        pnlActiveTab.add(Box.createVerticalStrut(16));
        pnlActiveTab.add(cardRl);
        pnlActiveTab.add(Box.createVerticalGlue());
        settingsTabs.addTab(I18n.t("settings.subtab.active", "Active Scanners"), wrapInScroll(pnlActiveTab));

        // 3. Passive Scanners & Checks
        JPanel pnlPassiveTab = new JPanel();
        pnlPassiveTab.setLayout(new BoxLayout(pnlPassiveTab, BoxLayout.Y_AXIS));
        pnlPassiveTab.setBorder(new EmptyBorder(16, 16, 16, 16));
        pnlPassiveTab.setBackground(themeHelper.getBackgroundColor());

        CardPanel cardJwt = new CardPanel(I18n.t("settings.section.jwt"), "lock");
        addCheckboxToForm(cardJwt, "jwt.redact_sensitive_claims", I18n.t("settings.checkbox.jwt.redact_sensitive_claims"), false);

        CardPanel cardSh = new CardPanel(I18n.t("settings.section.sensitive_headers"), "file-text");
        addCheckboxToForm(cardSh, "sh.check_cwe200_pii", I18n.t("settings.checkbox.sh.check_cwe200_pii"), true);
        addCheckboxToForm(cardSh, "sh.check_cwe200_financial", I18n.t("settings.checkbox.sh.check_cwe200_financial"), true);
        addCheckboxToForm(cardSh, "sh.check_cwe200_backend", I18n.t("settings.checkbox.sh.check_cwe200_backend"), true);
        addCheckboxToForm(cardSh, "sh.check_cwe200_infra", I18n.t("settings.checkbox.sh.check_cwe200_infra"), true);
        addCheckboxToForm(cardSh, "sh.redact_pii_values", I18n.t("settings.checkbox.sh.redact_pii_values"), false);

        pnlPassiveTab.add(cardJwt);
        pnlPassiveTab.add(Box.createVerticalStrut(16));
        pnlPassiveTab.add(cardSh);
        pnlPassiveTab.add(Box.createVerticalGlue());
        settingsTabs.addTab(I18n.t("settings.subtab.passive", "Passive Scanners"), wrapInScroll(pnlPassiveTab));

    }

    private JCheckBox addCheckboxToGrid(JPanel grid, GridBagConstraints gbc, String key, String label, boolean defaultValue) {
        JCheckBox cb = new JCheckBox(label, config.getBool(key, defaultValue));
        cb.setOpaque(false);
        grid.add(cb, gbc);
        saveHooks.add(() -> config.set(key, cb.isSelected()));
        cb.addItemListener(e -> saveAll());
        return cb;
    }

    private JLabel mutedLabel(String text) {
        // No <html> wrapper: Burp disables HTML rendering in Swing labels, so it shows as literal markup.
        JLabel l = new JLabel(text);
        l.setForeground(UIManager.getColor("Label.disabledForeground"));
        l.setFont(l.getFont().deriveFont(l.getFont().getSize2D() - 1f));
        return l;
    }

    private JLabel subHeader(String text) {
        JLabel l = new JLabel(text);
        l.setFont(l.getFont().deriveFont(Font.BOLD));
        return l;
    }

    private JComponent createTabbedTextField(String key) {
        JPanel p = new JPanel(new BorderLayout());
        p.setOpaque(false);
        p.setBorder(new EmptyBorder(8, 8, 8, 8));
        JTextField tf = new JTextField(config.getString(key, ""));
        themeHelper.applyTheme(tf);
        saveHooks.add(() -> config.set(key, tf.getText()));
        tf.addFocusListener(new java.awt.event.FocusAdapter() {
            public void focusLost(java.awt.event.FocusEvent evt) { saveAll(); }
        });
        p.add(tf, BorderLayout.NORTH);
        return p;
    }

    private void addCheckboxToForm(CardPanel card, String key, String label, boolean defaultValue) {
        JCheckBox cb = new JCheckBox(label, config.getBool(key, defaultValue));
        cb.setOpaque(false);
        card.addFormRow(cb);
        saveHooks.add(() -> config.set(key, cb.isSelected()));
        cb.addItemListener(e -> saveAll());
    }

    private void addSpinnerToForm(CardPanel card, String key, String label, int defaultVal, int min, int max, int step) {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        row.setOpaque(false);
        row.add(new JLabel(label + " "));
        
        JSpinner spinner = new JSpinner(new SpinnerNumberModel(config.getInt(key, defaultVal), min, max, step));
        spinner.setEditor(new JSpinner.NumberEditor(spinner, "#"));
        row.add(spinner);
        
        card.addFormRow(row);
        
        saveHooks.add(() -> config.set(key, (int) spinner.getValue()));
        spinner.addChangeListener(e -> saveAll());
    }

    private void addComboBoxToForm(CardPanel card, String key, String label, String[] options) {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        row.setOpaque(false);
        row.add(new JLabel(label + " "));
        
        JComboBox<String> combo = new JComboBox<>(options);
        combo.setSelectedItem(config.getString(key, options[0]));
        row.add(combo);
        
        card.addFormRow(row);
        
        saveHooks.add(() -> config.set(key, (String) combo.getSelectedItem()));
        combo.addItemListener(e -> saveAll());
    }

    private void addTextAreaToForm(CardPanel card, String key, String label) {
        card.addFormRow(new JLabel(label));
        JTextArea ta = new JTextArea(config.getString(key, ""), 4, 40);
        setupScrollableTextArea(ta);
        card.addFormRow(new JScrollPane(ta));
        
        saveHooks.add(() -> config.set(key, ta.getText()));
        ta.addFocusListener(new java.awt.event.FocusAdapter() {
            public void focusLost(java.awt.event.FocusEvent evt) { saveAll(); }
        });
    }

    private JScrollPane createTabbedTextArea(String key) {
        JTextArea ta = new JTextArea(config.getString(key, ""), 6, 40);
        setupScrollableTextArea(ta);
        saveHooks.add(() -> config.set(key, ta.getText()));
        ta.addFocusListener(new java.awt.event.FocusAdapter() {
            public void focusLost(java.awt.event.FocusEvent evt) { saveAll(); }
        });
        
        JScrollPane scroll = new JScrollPane(ta);
        scroll.setBorder(BorderFactory.createLineBorder(themeHelper.getBorderColor()));
        return scroll;
    }

    private void setupScrollableTextArea(JTextArea ta) {
        themeHelper.styleTextArea(ta);
        ta.setLineWrap(true);
        ta.setWrapStyleWord(true);
        ta.addMouseWheelListener(e -> {
            Component parent = SwingUtilities.getAncestorOfClass(JScrollPane.class, ta.getParent() != null ? ta.getParent().getParent() : ta);
            if (parent != null) {
                parent.dispatchEvent(SwingUtilities.convertMouseEvent(ta, e, parent));
            }
        });
    }

    private void addOutputDirField(CardPanel card) {
        JPanel row = new JPanel(new BorderLayout(4, 0));
        row.setOpaque(false);
        row.add(new JLabel(I18n.t("settings.label.evidence_output_folder")), BorderLayout.WEST);

        JTextField tf = new JTextField(EvidencePaths.defaultOutputDir(api, config));
        themeHelper.applyTheme(tf);
        row.add(tf, BorderLayout.CENTER);

        JButton btnBrowse = new JButton(I18n.t("settings.button.browse"));
        themeHelper.styleButton(btnBrowse);
        btnBrowse.addActionListener(e -> {
            JFileChooser fc = new JFileChooser(tf.getText());
            fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            if (fc.showOpenDialog(rootPanel) == JFileChooser.APPROVE_OPTION) {
                tf.setText(fc.getSelectedFile().getAbsolutePath());
                saveAll();
            }
        });

        JButton btnAuto = new JButton(I18n.t("settings.button.auto"));
        themeHelper.styleButton(btnAuto);
        btnAuto.addActionListener(e -> {
            tf.setText("");
            saveAll();
        });

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        buttons.setOpaque(false);
        buttons.add(btnBrowse);
        buttons.add(btnAuto);
        row.add(buttons, BorderLayout.EAST);

        card.addFormRow(row);

        saveHooks.add(() -> config.set("evidence.output_dir", tf.getText()));
        tf.addFocusListener(new java.awt.event.FocusAdapter() {
            public void focusLost(java.awt.event.FocusEvent evt) { saveAll(); }
        });
    }

    private void saveAll() {
        for (Runnable hook : saveHooks) {
            hook.run();
        }
        api.persistence().extensionData().setString("config", config.serialize());
        api.logging().logToOutput(I18n.t("settings.log.settings_saved"));
    }

    private void resetToDefault() {
        int confirm = JOptionPane.showConfirmDialog(rootPanel,
                I18n.t("settings.dialog.reset.message"),
                I18n.t("settings.dialog.reset.title"), JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (confirm != JOptionPane.YES_OPTION) return;

        config.clear();
        Icarus.applyDefaults(config);
        api.persistence().extensionData().setString("config", config.serialize());

        SwingUtilities.invokeLater(() -> {
            saveHooks.clear();
            rootPanel.removeAll();
            buildUI();
            rootPanel.revalidate();
            rootPanel.repaint();
        });
        api.logging().logToOutput(I18n.t("settings.log.settings_reset"));
    }

    // --- CardPanel Component ---
    private class CardPanel extends JPanel {
        public CardPanel(String title, String iconName) {
            setLayout(new GridBagLayout());
            setBorder(new EmptyBorder(12, 14, 12, 14));
            setBackground(themeHelper.getContainerBackgroundColor());
            putClientProperty("FlatLaf.style", "arc: 12; borderWidth: 1; borderColor: $Component.borderColor");
            
            JPanel header = new JPanel(new BorderLayout(0, 8));
            header.setOpaque(false);
            
            JLabel lblTitle = new JLabel(title);
            lblTitle.setFont(lblTitle.getFont().deriveFont(Font.BOLD, 14f));
            if (iconName != null && !iconName.isEmpty()) {
                lblTitle.setIcon(EvidenceUiHelpers.createIcon(iconName));
                lblTitle.setIconTextGap(8);
            }
            header.add(lblTitle, BorderLayout.NORTH);
            header.add(new JSeparator(), BorderLayout.SOUTH);
            
            GridBagConstraints gbc = new GridBagConstraints();
            gbc.gridx = 0; gbc.gridy = 0;
            gbc.weightx = 1.0; gbc.fill = GridBagConstraints.HORIZONTAL;
            gbc.insets = new Insets(0, 0, 12, 0); // space below header
            add(header, gbc);
        }

        @Override
        public Dimension getMaximumSize() {
            return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
        }

        public void addFormRow(Component comp) {
            GridBagConstraints gbc = new GridBagConstraints();
            gbc.gridx = 0; gbc.gridy = getComponentCount();
            gbc.weightx = 1.0; gbc.fill = GridBagConstraints.HORIZONTAL;
            gbc.insets = new Insets(0, 0, 8, 0);
            gbc.anchor = GridBagConstraints.NORTHWEST;
            add(comp, gbc);
        }
    }
}
