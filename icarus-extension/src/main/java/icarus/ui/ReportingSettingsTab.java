package icarus.ui;

import burp.api.montoya.MontoyaApi;
import icarus.core.ModuleConfig;
import icarus.core.ReportTemplateConfig;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ReportingSettingsTab {

    private final MontoyaApi api;
    private final ModuleConfig config;
    private final ThemeHelper themeHelper;
    private final JPanel mainPanel;

    private final List<Runnable> saveHooks = new ArrayList<>();

    public ReportingSettingsTab(MontoyaApi api, ModuleConfig config, ThemeHelper themeHelper) {
        this.api = api;
        this.config = config;
        this.themeHelper = themeHelper;
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

        JButton btnSave = new JButton("Save Reporting Settings");
        themeHelper.styleButton(btnSave);
        btnSave.addActionListener(e -> saveAll());
        pnlTop.add(btnSave);

        mainPanel.add(pnlTop, BorderLayout.NORTH);

        mainPanel.add(wrapInScroll(buildReportingTab()), BorderLayout.CENTER);
    }

    private void saveAll() {
        for (Runnable hook : saveHooks) hook.run();
        api.persistence().extensionData().setString("config", config.serialize());
        JOptionPane.showMessageDialog(mainPanel, "Reporting Settings saved successfully.", "Saved", JOptionPane.INFORMATION_MESSAGE);
    }

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
        sectionsSplit.setDividerSize(4); // Use subtle dividers
        sectionsSplit.setBorder(BorderFactory.createEmptyBorder());
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
}
