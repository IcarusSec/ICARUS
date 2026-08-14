package icarus.ui;

import burp.api.montoya.MontoyaApi;
import icarus.Icarus;
import icarus.autoauth.AutoAuthModule;
import icarus.core.EvidencePaths;
import icarus.core.ModuleConfig;
import icarus.core.ReportTemplateConfig;
import icarus.core.Severity;
import icarus.evidence.EvidenceColorScheme;
import icarus.mcp.IcarusMcpServer;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class SettingsPanel {

    private final MontoyaApi api;
    private final ModuleConfig config;
    private final ThemeHelper themeHelper;
    private final AutoAuthModule autoAuth;
    private final IcarusMcpServer mcpServer;
    private final JPanel mainPanel;

    private final List<Runnable> saveHooks = new ArrayList<>();
    private final List<JComponent> expandableLists = new ArrayList<>();

    public SettingsPanel(MontoyaApi api, ModuleConfig config, ThemeHelper themeHelper, AutoAuthModule autoAuth, IcarusMcpServer mcpServer) {
        this.api = api;
        this.config = config;
        this.themeHelper = themeHelper;
        this.autoAuth = autoAuth;
        this.mcpServer = mcpServer;
        this.mainPanel = new JPanel(new BorderLayout(0, 10));
        this.mainPanel.setBorder(BorderFactory.createEmptyBorder(15, 15, 15, 15));
        themeHelper.applyTheme(this.mainPanel);

        buildUI();
    }

    public Component getComponent() {
        return mainPanel;
    }

    private Component wrapInScroll(Component content) {
        JScrollPane scroll = new JScrollPane(content);
        scroll.setBorder(null);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
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

        JButton btnReset = new JButton("Reset to Default");
        themeHelper.styleButton(btnReset);
        btnReset.addActionListener(e -> resetToDefault());
        pnlTop.add(btnReset);

        mainPanel.add(pnlTop, BorderLayout.NORTH);

        JTabbedPane settingsTabs = new JTabbedPane();
        themeHelper.applyTheme(settingsTabs);
        mainPanel.add(settingsTabs, BorderLayout.CENTER);

        // Global Toggles
        JPanel pnlGlobal = createSection("General & Modules");
        addCheckbox(pnlGlobal, "ui.show_popups", "Show pop-up dialog with findings after an active scan completes");
        addCheckbox(pnlGlobal, "verbose.enabled", "Verbose Mode (live step-by-step scan logging)");
        addCheckbox(pnlGlobal, "pv.enabled", "ParamValidator (JSON Mutation Fuzzer)");
        addCheckbox(pnlGlobal, "hv.enabled", "HTTP Verb Tester");
        addCheckbox(pnlGlobal, "jwt.enabled", "JWT / Bearer Token Checker");
        addCheckbox(pnlGlobal, "sh.enabled", "Sensitive Headers (Active)");
        addCheckbox(pnlGlobal, "sh.passive", "Sensitive Headers (Passive / Background)");
        addCheckbox(pnlGlobal, "export.enabled", "Postman Export");
        addCheckbox(pnlGlobal, "rl.enabled", "Rate Limit Tester");
        addCheckbox(pnlGlobal, "autoauth.enabled", "AutoAuth (background token refresh/injection)");
        addCheckbox(pnlGlobal, "pem.enabled", "Passive Error Detector (Active)");
        addCheckbox(pnlGlobal, "pem.passive", "Passive Error Detector (Passive / Background)");

        // WAF & Safe Lists
        JPanel pnlWaf = createSection("WAF Evasion & Safe Lists");
        addCheckbox(pnlWaf, "waf.detect_akamai", "Detect Akamai and prompt for Safe Mode");
        addTextArea(pnlWaf, "waf.safelist_payloads", "Safe List Payloads (one per line):", true);

        JPanel pnlGeneralTab = new JPanel();
        pnlGeneralTab.setLayout(new BoxLayout(pnlGeneralTab, BoxLayout.Y_AXIS));
        themeHelper.applyTheme(pnlGeneralTab);
        pnlGeneralTab.add(pnlGlobal);
        pnlGeneralTab.add(Box.createRigidArea(new Dimension(0, 10)));
        pnlGeneralTab.add(pnlWaf);
        settingsTabs.addTab("General", wrapInScroll(pnlGeneralTab));

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

        settingsTabs.addTab("ParamValidator", wrapInScroll(pnlPv));

        // HTTP Verb
        JPanel pnlHv = createSection("HTTP Verb Tester");
        addCheckbox(pnlHv, "hv.test_get", "Test GET");
        addCheckbox(pnlHv, "hv.test_post", "Test POST");
        addCheckbox(pnlHv, "hv.test_put", "Test PUT");
        addCheckbox(pnlHv, "hv.test_delete", "Test DELETE");
        addCheckbox(pnlHv, "hv.test_options", "Test OPTIONS");
        addCheckbox(pnlHv, "hv.test_trace", "Test TRACE");
        addCheckbox(pnlHv, "hv.enable_state_changing", "Enable state-changing methods (POST/PUT/DELETE/PATCH)");
        addComboBox(pnlHv, "hv.body_strategy", "Body Strategy:", new String[]{"AUTO", "KEEP", "REMOVE"});
        settingsTabs.addTab("HTTP Verb", wrapInScroll(pnlHv));

        // JWT Checker
        JPanel pnlJwt = createSection("JWT / Bearer Token Checker");
        addCheckbox(pnlJwt, "jwt.redact_sensitive_claims", "Redact sensitive claim values in findings/logs/reports (show key only)");
        settingsTabs.addTab("JWT", wrapInScroll(pnlJwt));

        // Sensitive Headers - CWE-200
        JPanel pnlSh = createSection("Sensitive Headers - CWE-200 Detection");
        addCheckbox(pnlSh, "sh.check_cwe200_pii", "PII / National ID leaks (SSN, NINO, SIN, CPF, NIR)");
        addCheckbox(pnlSh, "sh.check_cwe200_financial", "Financial data leaks (credit cards, IBAN)");
        addCheckbox(pnlSh, "sh.check_cwe200_backend", "Backend stack trace / framework error leaks");
        addCheckbox(pnlSh, "sh.check_cwe200_infra", "Internal IP / internal domain leaks");
        addCheckbox(pnlSh, "sh.redact_pii_values", "Redact PII values in findings (show header name only)");
        settingsTabs.addTab("Sensitive Headers", wrapInScroll(pnlSh));

        // Rate Limit Tester
        JPanel pnlRl = createSection("Rate Limit Tester");
        addField(pnlRl, "rl.request_count", "Requests per blast:");
        addField(pnlRl, "rl.concurrency", "Concurrent threads:");
        addField(pnlRl, "rl.cooldown_wait_ms", "Cooldown between bypasses (ms):");
        addField(pnlRl, "rl.max_rps", "Max RPS (0 = unlimited):");
        addCheckbox(pnlRl, "rl.bypass_headers", "Try IP header rotation bypass (X-Forwarded-For, etc.)");
        addCheckbox(pnlRl, "rl.bypass_path", "Try path normalization bypass (/api/./v1, //api, etc.)");
        addCheckbox(pnlRl, "rl.bypass_query", "Try cache-buster query param bypass (?_icarus=N)");
        settingsTabs.addTab("Rate Limit", wrapInScroll(pnlRl));

        // AutoAuth
        JPanel pnlAuto = createSection("AutoAuth");
        addField(pnlAuto, "autoauth.refresh_minutes", "Refresh token every X minutes:");
        JLabel autoAuthStatus = addStatusLabel(pnlAuto, autoAuth.statusSummary());
        addButton(pnlAuto, "Clear Active Config", () -> {
            autoAuth.clearSession();
            autoAuthStatus.setText(autoAuth.statusSummary());
        });
        settingsTabs.addTab("AutoAuth", wrapInScroll(pnlAuto));

        // MCP (AI agent access) — live toggle (starts/stops mcpServer immediately) rather than
        // addCheckbox's save-on-click-Save pattern, since flipping this opens/closes a real
        // localhost port and the status label needs to reflect that right away.
        JPanel pnlMcp = createSection("AI Integration (MCP Server)");
        JLabel mcpHint = new JLabel("Let AI agents query ICARUS findings over a local MCP connection.");
        themeHelper.applyTheme(mcpHint);
        ((JPanel) pnlMcp.getComponent(0)).add(mcpHint);
        JLabel mcpStatus = addStatusLabel(pnlMcp, mcpServer.statusSummary());

        JPanel pnlMcpPort = new JPanel(new FlowLayout(FlowLayout.LEFT));
        themeHelper.applyTheme(pnlMcpPort);
        pnlMcpPort.setAlignmentX(Component.LEFT_ALIGNMENT);
        JLabel mcpPortLbl = new JLabel("Port (0 = auto-assign):");
        themeHelper.applyTheme(mcpPortLbl);
        pnlMcpPort.add(mcpPortLbl);
        JTextField txtMcpPort = new JTextField(String.valueOf(config.getInt("mcp.port", 61337)), 6);
        themeHelper.applyTheme(txtMcpPort);
        pnlMcpPort.add(txtMcpPort);
        ((JPanel) pnlMcp.getComponent(0)).add(pnlMcpPort);

        JCheckBox chkMcp = new JCheckBox("Enable Local MCP Server", config.getBool("mcp.enabled", false));
        themeHelper.applyTheme(chkMcp);
        chkMcp.setAlignmentX(Component.LEFT_ALIGNMENT);

        Runnable applyMcpPort = () -> {
            int requestedPort;
            try {
                requestedPort = Integer.parseInt(txtMcpPort.getText().trim());
            } catch (NumberFormatException ex) {
                requestedPort = -1;
            }
            if (requestedPort < 0 || requestedPort > 65535) {
                JOptionPane.showMessageDialog(mainPanel, "Port must be between 0 and 65535 (0 = auto-assign).",
                        "Invalid MCP Port", JOptionPane.ERROR_MESSAGE);
                txtMcpPort.setText(String.valueOf(config.getInt("mcp.port", 61337)));
                return;
            }
            config.set("mcp.port", requestedPort);
            api.persistence().extensionData().setString("config", config.serialize());
            if (mcpServer.isRunning()) {
                mcpServer.stop();
                mcpServer.start(requestedPort);
            }
            mcpStatus.setText(mcpServer.statusSummary());
        };

        chkMcp.addActionListener(e -> {
            boolean enabled = chkMcp.isSelected();
            config.set("mcp.enabled", enabled);
            api.persistence().extensionData().setString("config", config.serialize());
            if (enabled) mcpServer.start(config.getInt("mcp.port", 61337)); else mcpServer.stop();
            mcpStatus.setText(mcpServer.statusSummary());
        });
        ((JPanel) pnlMcp.getComponent(0)).add(chkMcp);

        addButton(pnlMcp, "Apply Port / Restart Server", applyMcpPort);

        settingsTabs.addTab("MCP", wrapInScroll(pnlMcp));

        // Evidence
        JPanel pnlEvidence = createSection("Evidence Capture");
        addOutputDirField(pnlEvidence);
        addComboBox(pnlEvidence, "evidence.colorscheme", "Screenshot Color Scheme:", EvidenceColorScheme.names());
        addCheckbox(pnlEvidence, "evidence.include_project_name", "Include Project Name in Evidence/Report");
        addComboBox(pnlEvidence, "evidence.manual_severity", "Manual \"Create Evidence\" Severity:",
                java.util.Arrays.stream(Severity.values()).map(Enum::name).toArray(String[]::new));
        settingsTabs.addTab("Evidence", wrapInScroll(pnlEvidence));

        // Reporting
        if (icarus.Icarus.HTML_and_PDF_REPORT) {
            settingsTabs.addTab("Reporting", wrapInScroll(buildReportingTab()));
        }

        // Initially hide expandable lists
        for (JComponent c : expandableLists) {
            c.setVisible(false);
        }
    }

    /**
     * Builds the "Reporting" tab: custom report sections (add/remove/reorder + Markdown
     * content), {{variable}} definitions, theme colors/custom CSS, and retest settings.
     * Backed by {@link ReportTemplateConfig}, not the flat ModuleConfig keys the rest of
     * this panel uses — one saveHook serializes the whole thing back via saveTo().
     */
    private JPanel buildReportingTab() {
        ReportTemplateConfig rtc = ReportTemplateConfig.fromConfig(config);

        JPanel root = new JPanel();
        root.setLayout(new BoxLayout(root, BoxLayout.Y_AXIS));
        themeHelper.applyTheme(root);

        // ── Sections ──────────────────────────────────────────
        JPanel pnlSections = createSection("Report Sections");
        JPanel sectionsInner = (JPanel) pnlSections.getComponent(0);

        DefaultListModel<ReportTemplateConfig.Section> sectionModel = new DefaultListModel<>();
        rtc.sections().forEach(sectionModel::addElement);
        JList<ReportTemplateConfig.Section> sectionList = new JList<>(sectionModel);
        sectionList.setVisibleRowCount(6);
        sectionList.setCellRenderer((list, value, index, isSelected, hasFocus) -> {
            JLabel l = new JLabel((index + 1) + ". " + (value.title().isBlank() ? "(untitled)" : value.title()));
            l.setOpaque(true);
            l.setBackground(isSelected ? themeHelper.getSelectionBackgroundColor() : themeHelper.getBackgroundColor());
            l.setForeground(themeHelper.getForegroundColor());
            l.setBorder(BorderFactory.createEmptyBorder(3, 6, 3, 6));
            return l;
        });
        JScrollPane sectionScroll = new JScrollPane(sectionList);
        themeHelper.applyTheme(sectionScroll);
        sectionScroll.setPreferredSize(new Dimension(220, 150));

        JTextField txtSectionTitle = new JTextField();
        themeHelper.applyTheme(txtSectionTitle);
        JTextArea txtSectionContent = new JTextArea(8, 40);
        themeHelper.styleTextArea(txtSectionContent);
        JScrollPane contentScroll = new JScrollPane(txtSectionContent);
        themeHelper.applyTheme(contentScroll);
        txtSectionTitle.setEnabled(false);
        txtSectionContent.setEnabled(false);

        boolean[] syncingSelection = {false};
        Runnable syncSelectedIntoModel = () -> {
            int idx = sectionList.getSelectedIndex();
            if (idx < 0 || syncingSelection[0]) return;
            sectionModel.set(idx, new ReportTemplateConfig.Section(txtSectionTitle.getText(), txtSectionContent.getText()));
        };
        DocumentListener syncListener = new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { syncSelectedIntoModel.run(); }
            public void removeUpdate(DocumentEvent e) { syncSelectedIntoModel.run(); }
            public void changedUpdate(DocumentEvent e) { syncSelectedIntoModel.run(); }
        };
        txtSectionTitle.getDocument().addDocumentListener(syncListener);
        txtSectionContent.getDocument().addDocumentListener(syncListener);

        sectionList.addListSelectionListener(e -> {
            if (e.getValueIsAdjusting()) return;
            syncingSelection[0] = true;
            int idx = sectionList.getSelectedIndex();
            if (idx < 0) {
                txtSectionTitle.setText("");
                txtSectionContent.setText("");
                txtSectionTitle.setEnabled(false);
                txtSectionContent.setEnabled(false);
            } else {
                ReportTemplateConfig.Section s = sectionModel.get(idx);
                txtSectionTitle.setText(s.title());
                txtSectionContent.setText(s.content());
                txtSectionTitle.setEnabled(true);
                txtSectionContent.setEnabled(true);
            }
            syncingSelection[0] = false;
        });

        JPanel sectionListButtons = new JPanel(new GridLayout(1, 4, 4, 0));
        themeHelper.applyTheme(sectionListButtons);
        JButton btnAddSection = new JButton("Add");
        JButton btnRemoveSection = new JButton("Remove");
        JButton btnMoveUp = new JButton("Up");
        JButton btnMoveDown = new JButton("Down");
        for (JButton b : List.of(btnAddSection, btnRemoveSection, btnMoveUp, btnMoveDown)) themeHelper.styleButton(b);
        btnAddSection.addActionListener(e -> {
            sectionModel.addElement(new ReportTemplateConfig.Section("New Section", ""));
            sectionList.setSelectedIndex(sectionModel.size() - 1);
        });
        btnRemoveSection.addActionListener(e -> {
            int idx = sectionList.getSelectedIndex();
            if (idx >= 0) sectionModel.remove(idx);
        });
        btnMoveUp.addActionListener(e -> {
            int idx = sectionList.getSelectedIndex();
            if (idx > 0) {
                ReportTemplateConfig.Section s = sectionModel.remove(idx);
                sectionModel.add(idx - 1, s);
                sectionList.setSelectedIndex(idx - 1);
            }
        });
        btnMoveDown.addActionListener(e -> {
            int idx = sectionList.getSelectedIndex();
            if (idx >= 0 && idx < sectionModel.size() - 1) {
                ReportTemplateConfig.Section s = sectionModel.remove(idx);
                sectionModel.add(idx + 1, s);
                sectionList.setSelectedIndex(idx + 1);
            }
        });
        sectionListButtons.add(btnAddSection);
        sectionListButtons.add(btnRemoveSection);
        sectionListButtons.add(btnMoveUp);
        sectionListButtons.add(btnMoveDown);

        JPanel sectionListPanel = new JPanel(new BorderLayout(0, 4));
        themeHelper.applyTheme(sectionListPanel);
        sectionListPanel.add(sectionScroll, BorderLayout.CENTER);
        sectionListPanel.add(sectionListButtons, BorderLayout.SOUTH);

        JPanel sectionEditorPanel = new JPanel(new BorderLayout(0, 4));
        themeHelper.applyTheme(sectionEditorPanel);
        JLabel lblTitleField = new JLabel("Title:");
        themeHelper.applyTheme(lblTitleField);
        JPanel titleRow = new JPanel(new BorderLayout(4, 0));
        themeHelper.applyTheme(titleRow);
        titleRow.add(lblTitleField, BorderLayout.WEST);
        titleRow.add(txtSectionTitle, BorderLayout.CENTER);
        sectionEditorPanel.add(titleRow, BorderLayout.NORTH);
        sectionEditorPanel.add(contentScroll, BorderLayout.CENTER);
        JLabel lblMarkdownHint = new JLabel("Content is Markdown; use {{variable}} to interpolate values defined below.");
        themeHelper.applyTheme(lblMarkdownHint);
        sectionEditorPanel.add(lblMarkdownHint, BorderLayout.SOUTH);

        JSplitPane sectionsSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, sectionListPanel, sectionEditorPanel);
        sectionsSplit.setResizeWeight(0.3);
        sectionsSplit.setDividerSize(6);
        sectionsSplit.setPreferredSize(new Dimension(760, 260));
        sectionsInner.add(sectionsSplit);

        // ── Variables ─────────────────────────────────────────
        JPanel pnlVariables = createSection("Template Variables");
        JPanel variablesInner = (JPanel) pnlVariables.getComponent(0);
        JLabel lblVarHint = new JLabel("One per line, key=value. Reference as {{key}} in section content.");
        themeHelper.applyTheme(lblVarHint);
        variablesInner.add(lblVarHint);
        StringBuilder varLines = new StringBuilder();
        rtc.variables().forEach((k, v) -> varLines.append(k).append('=').append(v).append('\n'));
        JTextArea txtVariables = new JTextArea(varLines.toString(), 5, 40);
        themeHelper.styleTextArea(txtVariables);
        JScrollPane varScroll = new JScrollPane(txtVariables);
        themeHelper.applyTheme(varScroll);
        variablesInner.add(varScroll);

        // ── Theme ─────────────────────────────────────────────
        JPanel pnlTheme = createSection("Theme & Styling");

        JComboBox<String> comboBaseTheme = new JComboBox<>(new String[]{"Light", "Dark"});
        comboBaseTheme.setSelectedItem("dark".equals(rtc.themeName()) ? "Dark" : "Light");
        addLabeledCombo(pnlTheme, "HTML base theme:", comboBaseTheme);

        JTextField txtPrimary = new JTextField(rtc.primaryColor() != null ? rtc.primaryColor() : "", 10);
        JTextField txtSecondary = new JTextField(rtc.secondaryColor() != null ? rtc.secondaryColor() : "", 10);

        // Named presets are just a shortcut that fills the two hex fields below — they stay
        // freely editable afterward, so this doesn't need its own persisted state.
        Map<String, String[]> presets = new LinkedHashMap<>();
        presets.put("Custom", new String[]{"", ""});
        presets.put("ICARUS Blue (default)", new String[]{"#3e7bb8", "#6e6e6e"});
        presets.put("Crimson", new String[]{"#b3272c", "#6e6e6e"});
        presets.put("Forest", new String[]{"#2f7a4f", "#6e6e6e"});
        presets.put("Slate", new String[]{"#41505f", "#8a99a8"});
        JComboBox<String> comboPreset = new JComboBox<>(presets.keySet().toArray(new String[0]));
        comboPreset.addActionListener(e -> {
            String[] colors = presets.get((String) comboPreset.getSelectedItem());
            if (colors != null && !colors[0].isEmpty()) {
                txtPrimary.setText(colors[0]);
                txtSecondary.setText(colors[1]);
            }
        });
        addLabeledCombo(pnlTheme, "Color preset:", comboPreset);

        addLabeledField(pnlTheme, "Primary color (#hex):", txtPrimary);
        addLabeledField(pnlTheme, "Secondary color (#hex):", txtSecondary);

        JTextField txtCssPath = new JTextField(rtc.customCssPath() != null ? rtc.customCssPath() : "", 30);
        JButton btnBrowseCss = new JButton("Browse…");
        themeHelper.styleButton(btnBrowseCss);
        btnBrowseCss.addActionListener(e -> {
            JFileChooser fc = new JFileChooser();
            fc.setDialogTitle("Select custom CSS file");
            if (fc.showOpenDialog(mainPanel) == JFileChooser.APPROVE_OPTION) {
                txtCssPath.setText(fc.getSelectedFile().getAbsolutePath());
            }
        });
        JPanel cssRow = new JPanel(new BorderLayout(4, 0));
        themeHelper.applyTheme(cssRow);
        JLabel lblCss = new JLabel("Custom CSS file:");
        themeHelper.applyTheme(lblCss);
        cssRow.add(lblCss, BorderLayout.WEST);
        cssRow.add(txtCssPath, BorderLayout.CENTER);
        cssRow.add(btnBrowseCss, BorderLayout.EAST);
        cssRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        ((JPanel) pnlTheme.getComponent(0)).add(cssRow);

        JCheckBox chkToc = new JCheckBox("Enable Table of Contents / PDF bookmarks", rtc.tocEnabled());
        themeHelper.applyTheme(chkToc);
        chkToc.setAlignmentX(Component.LEFT_ALIGNMENT);
        ((JPanel) pnlTheme.getComponent(0)).add(chkToc);

        // ── Retest ────────────────────────────────────────────
        JPanel pnlRetest = createSection("Retest Mode");
        JLabel lblStatusHint = new JLabel("Resolution statuses shown per finding (one per line):");
        themeHelper.applyTheme(lblStatusHint);
        ((JPanel) pnlRetest.getComponent(0)).add(lblStatusHint);
        JTextArea txtRetestStatuses = new JTextArea(String.join("\n", rtc.retestStatuses()), 3, 40);
        themeHelper.styleTextArea(txtRetestStatuses);
        JScrollPane statusScroll = new JScrollPane(txtRetestStatuses);
        themeHelper.applyTheme(statusScroll);
        ((JPanel) pnlRetest.getComponent(0)).add(statusScroll);

        JLabel lblSuppressHint = new JLabel("Section titles to suppress in retest reports (one per line):");
        themeHelper.applyTheme(lblSuppressHint);
        ((JPanel) pnlRetest.getComponent(0)).add(lblSuppressHint);
        JTextArea txtSuppressed = new JTextArea(String.join("\n", rtc.retestSuppressedSections()), 3, 40);
        themeHelper.styleTextArea(txtSuppressed);
        JScrollPane suppressScroll = new JScrollPane(txtSuppressed);
        themeHelper.applyTheme(suppressScroll);
        ((JPanel) pnlRetest.getComponent(0)).add(suppressScroll);

        root.add(pnlSections);
        root.add(Box.createRigidArea(new Dimension(0, 10)));
        root.add(pnlVariables);
        root.add(Box.createRigidArea(new Dimension(0, 10)));
        root.add(pnlTheme);
        root.add(Box.createRigidArea(new Dimension(0, 10)));
        root.add(pnlRetest);

        saveHooks.add(() -> {
            syncSelectedIntoModel.run();
            List<ReportTemplateConfig.Section> sections = new ArrayList<>();
            for (int i = 0; i < sectionModel.size(); i++) sections.add(sectionModel.get(i));
            rtc.setSections(sections);

            Map<String, String> vars = new LinkedHashMap<>();
            for (String line : txtVariables.getText().split("\n")) {
                String trimmed = line.strip();
                if (trimmed.isEmpty()) continue;
                int eq = trimmed.indexOf('=');
                if (eq < 0) continue;
                vars.put(trimmed.substring(0, eq).strip(), trimmed.substring(eq + 1).strip());
            }
            rtc.setVariables(vars);

            rtc.setPrimaryColor(blankToNull(txtPrimary.getText()));
            rtc.setSecondaryColor(blankToNull(txtSecondary.getText()));
            rtc.setCustomCssPath(blankToNull(txtCssPath.getText()));
            rtc.setThemeName("Dark".equals(comboBaseTheme.getSelectedItem()) ? "dark" : "light");
            rtc.setTocEnabled(chkToc.isSelected());
            rtc.setRetestStatuses(nonBlankLines(txtRetestStatuses.getText()));
            rtc.setRetestSuppressedSections(nonBlankLines(txtSuppressed.getText()));

            rtc.saveTo(config);
        });

        return root;
    }

    private void addLabeledCombo(JPanel parent, String label, JComboBox<String> combo) {
        JPanel row = new JPanel(new BorderLayout(4, 0));
        themeHelper.applyTheme(row);
        JLabel lbl = new JLabel(label);
        themeHelper.applyTheme(lbl);
        row.add(lbl, BorderLayout.WEST);
        row.add(combo, BorderLayout.CENTER);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        ((JPanel) parent.getComponent(0)).add(row);
    }

    private void addLabeledField(JPanel parent, String label, JTextField field) {
        JPanel row = new JPanel(new BorderLayout(4, 0));
        themeHelper.applyTheme(row);
        JLabel lbl = new JLabel(label);
        themeHelper.applyTheme(lbl);
        themeHelper.applyTheme(field);
        row.add(lbl, BorderLayout.WEST);
        row.add(field, BorderLayout.CENTER);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        ((JPanel) parent.getComponent(0)).add(row);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }

    private static List<String> nonBlankLines(String text) {
        List<String> out = new ArrayList<>();
        for (String line : text.split("\n")) {
            String t = line.strip();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
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

    /**
     * "evidence.output_dir" gets its own field instead of {@link #addField}: it needs a
     * folder-picker button, and shows {@link EvidencePaths#defaultOutputDir} — the folder
     * actually in effect right now, whether the user pinned one explicitly or it's still
     * auto-resolved from the open project — not just whatever raw string (possibly blank)
     * happens to be in config.
     */
    private void addOutputDirField(JPanel parent) {
        JPanel row = new JPanel(new BorderLayout(4, 0));
        themeHelper.applyTheme(row);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);

        JLabel lbl = new JLabel("Evidence Output Folder:");
        themeHelper.applyTheme(lbl);
        row.add(lbl, BorderLayout.WEST);

        JTextField tf = new JTextField(EvidencePaths.defaultOutputDir(api, config));
        themeHelper.applyTheme(tf);
        row.add(tf, BorderLayout.CENTER);

        JButton btnBrowse = new JButton("Browse…");
        themeHelper.styleButton(btnBrowse);
        btnBrowse.addActionListener(e -> {
            JFileChooser fc = new JFileChooser(tf.getText());
            fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            if (fc.showOpenDialog(mainPanel) == JFileChooser.APPROVE_OPTION) {
                tf.setText(fc.getSelectedFile().getAbsolutePath());
            }
        });

        JButton btnAuto = new JButton("Auto");
        themeHelper.styleButton(btnAuto);
        btnAuto.setToolTipText("Clear to auto-detect from the open project folder each time");
        btnAuto.addActionListener(e -> tf.setText(""));

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        themeHelper.applyTheme(buttons);
        buttons.add(btnBrowse);
        buttons.add(btnAuto);
        row.add(buttons, BorderLayout.EAST);

        JPanel inner = (JPanel) parent.getComponent(0);
        inner.add(row);
        inner.add(Box.createRigidArea(new Dimension(0, 5)));

        // Blank persists as blank (not this call's resolved default) — EvidencePaths treats a
        // blank/unset value as "keep auto-resolving," which is exactly what "Auto" means here.
        saveHooks.add(() -> config.set("evidence.output_dir", tf.getText()));
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

    private JLabel addStatusLabel(JPanel parent, String initialText) {
        JLabel lbl = new JLabel(initialText);
        themeHelper.applyTheme(lbl);
        lbl.setAlignmentX(Component.LEFT_ALIGNMENT);
        lbl.setBorder(BorderFactory.createEmptyBorder(0, 0, 5, 0));

        JPanel inner = (JPanel) parent.getComponent(0);
        inner.add(lbl);
        return lbl;
    }

    private void addButton(JPanel parent, String label, Runnable action) {
        JButton btn = new JButton(label);
        themeHelper.styleButton(btn);
        btn.setAlignmentX(Component.LEFT_ALIGNMENT);
        btn.addActionListener(e -> action.run());

        JPanel inner = (JPanel) parent.getComponent(0);
        inner.add(btn);
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
        
        scroll.addMouseWheelListener(e -> {
            JScrollBar vbar = scroll.getVerticalScrollBar();
            if (!vbar.isVisible() || 
                (e.getWheelRotation() < 0 && vbar.getValue() == 0) || 
                (e.getWheelRotation() > 0 && vbar.getValue() >= vbar.getMaximum() - vbar.getVisibleAmount())) {
                
                Container ancestorScroll = SwingUtilities.getAncestorOfClass(JScrollPane.class, scroll);
                if (ancestorScroll != null) {
                    ancestorScroll.dispatchEvent(SwingUtilities.convertMouseEvent(scroll, e, ancestorScroll));
                }
            }
        });
        
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

    private void resetToDefault() {
        int confirm = JOptionPane.showConfirmDialog(mainPanel,
                "Reset all settings to their default values? This cannot be undone.",
                "Reset to Default", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (confirm != JOptionPane.YES_OPTION) return;

        config.clear();
        Icarus.applyDefaults(config);
        api.persistence().extensionData().setString("config", config.serialize());

        saveHooks.clear();
        expandableLists.clear();
        mainPanel.removeAll();
        buildUI();
        mainPanel.revalidate();
        mainPanel.repaint();

        api.logging().logToOutput("Settings reset to default.");
    }
}
