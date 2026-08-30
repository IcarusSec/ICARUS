package icarus.ui;

import burp.api.montoya.MontoyaApi;
import icarus.core.I18n;
import icarus.core.ModuleConfig;
import icarus.core.Severity;
import icarus.evidence.EvidenceUiHelpers;
import icarus.report.DefaultReportProfileManager;
import icarus.report.PreviewService;
import icarus.report.ReportProfileManager;
import icarus.report.model.*;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.TitledBorder;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.io.File;
import java.nio.file.Files;
import java.util.*;
import java.util.List;

/**
 * Settings tab for configuring, cloning, styling, and managing Report Profiles.
 */
public class ReportingSettingsTab {

    private final MontoyaApi api;
    private final ModuleConfig config;
    private final ThemeHelper themeHelper;
    private final ReportProfileManager profileManager;
    private final JPanel containerPanel;

    private JComboBox<ReportProfile> comboProfiles;
    private JButton btnClone;
    private JButton btnDelete;
    private JButton btnExport;
    private JButton btnImport;
    private JButton btnSave;
    private JButton btnPreviewPdf;
    private JButton btnPreviewHtml;

    // Tabs / Form fields
    private JRadioButton rdoCoverGradient;
    private JRadioButton rdoCoverHeaderBand;
    private JRadioButton rdoCoverNone;

    private JRadioButton rdoFindingCard;
    private JRadioButton rdoFindingTabular;

    private DefaultTableModel sectionsTableModel;
    private JTable sectionsTable;

    private JPanel colorPrimaryPanel;
    private JPanel colorSecondaryPanel;
    private JComboBox<String> comboFontStack;
    private JSpinner spinFontSize;
    private Map<Severity, JPanel> severityColorPanels = new EnumMap<>(Severity.class);

    private JTextField txtCompanyLogo;
    private JTextField txtClientLogo;
    private JTextField txtDocTitle;
    private JTextField txtClassification;
    private JTextField txtAuthor;
    private JTextField txtReviewer;
    private JTextField txtApprover;
    private JTextField txtEnvironment;

    private JCheckBox chkIncludeEvidence;
    private JCheckBox chkIncludeReq;
    private JSpinner spinMaxReqBytes;
    private JCheckBox chkIncludeRes;
    private JSpinner spinMaxResBytes;
    private JCheckBox chkToc;
    private Map<FindingField, JCheckBox> fieldCheckboxes = new EnumMap<>(FindingField.class);

    private ReportProfile currentProfile;
    private boolean isUpdatingUi = false;

    public ReportingSettingsTab(MontoyaApi api, ModuleConfig config, ThemeHelper themeHelper) {
        this(api, config, themeHelper, new DefaultReportProfileManager(config));
    }

    public ReportingSettingsTab(MontoyaApi api, ModuleConfig config, ThemeHelper themeHelper, ReportProfileManager profileManager) {
        this.api = api;
        this.config = config;
        this.themeHelper = themeHelper;
        this.profileManager = profileManager;
        this.containerPanel = new JPanel(new BorderLayout());

        JPanel mainPanel = buildReportingTab();
        JScrollPane masterScroll = new JScrollPane(mainPanel);
        masterScroll.getVerticalScrollBar().setUnitIncrement(16);
        masterScroll.setBorder(null);
        themeHelper.applyTheme(masterScroll);

        this.containerPanel.add(masterScroll, BorderLayout.CENTER);
        refreshProfileList();
    }

    public Component getUiComponent() {
        return containerPanel;
    }

    public void save() {
        if (currentProfile != null && !currentProfile.builtIn()) {
            saveCurrentProfileChanges();
        }
    }

    private JPanel buildReportingTab() {
        JPanel mainPanel = new JPanel();
        mainPanel.setLayout(new BoxLayout(mainPanel, BoxLayout.Y_AXIS));
        mainPanel.setBorder(new EmptyBorder(16, 16, 16, 16));
        themeHelper.applyTheme(mainPanel);

        // 1. Profile Selector Strip
        mainPanel.add(buildProfileStrip());
        mainPanel.add(Box.createVerticalStrut(12));

        // 2. Editor Tabs
        JTabbedPane tabbedPane = new JTabbedPane();
        tabbedPane.addTab(I18n.t("ui.reporting.tab.layout", "Layout & Sections"), buildLayoutAndSectionsTab());
        tabbedPane.addTab(I18n.t("ui.reporting.tab.theme", "Colors & Theme"), buildThemeTab());
        tabbedPane.addTab(I18n.t("ui.reporting.tab.branding", "Branding & Metadata"), buildBrandingTab());
        tabbedPane.addTab(I18n.t("ui.reporting.tab.content", "Content & Policy"), buildContentPolicyTab());

        tabbedPane.setAlignmentX(Component.LEFT_ALIGNMENT);
        mainPanel.add(tabbedPane);
        mainPanel.add(Box.createVerticalStrut(12));

        // 3. Bottom Action Bar (Preview & Save)
        mainPanel.add(buildBottomActionBar());

        return mainPanel;
    }

    private JPanel buildProfileStrip() {
        JPanel strip = new JPanel(new BorderLayout(8, 0));
        strip.setBackground(UIManager.getColor("TextField.background"));
        strip.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createTitledBorder(I18n.t("ui.reporting.section.profile", "Active Report Model / Profile")),
            new EmptyBorder(8, 8, 8, 8)
        ));
        strip.setAlignmentX(Component.LEFT_ALIGNMENT);

        comboProfiles = new JComboBox<>();
        comboProfiles.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
                super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                if (value instanceof ReportProfile p) {
                    setText((p.builtIn() ? "🔒 " : "✏️ ") + p.name() + (p.builtIn() ? " [Built-in]" : " [Custom]"));
                }
                return this;
            }
        });
        comboProfiles.addActionListener(e -> {
            if (isUpdatingUi) return;
            ReportProfile selected = (ReportProfile) comboProfiles.getSelectedItem();
            if (selected != null) {
                profileManager.setActive(selected.id());
                loadProfileIntoForm(selected);
            }
        });

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        buttons.setOpaque(false);

        btnClone = new JButton(I18n.t("ui.reporting.btn.clone", "📋 Clone"), EvidenceUiHelpers.createIcon("copy"));
        btnClone.setToolTipText("Clone selected profile into a new customizable profile");
        btnClone.addActionListener(e -> onCloneProfile());

        btnExport = new JButton(I18n.t("ui.reporting.btn.export_json", "📤 Export JSON"), EvidenceUiHelpers.createIcon("download"));
        btnExport.addActionListener(e -> onExportProfile());

        btnImport = new JButton(I18n.t("ui.reporting.btn.import_json", "📥 Import JSON"), EvidenceUiHelpers.createIcon("upload"));
        btnImport.addActionListener(e -> onImportProfile());

        btnDelete = new JButton(I18n.t("ui.reporting.btn.delete", "🗑️ Delete"), EvidenceUiHelpers.createIcon("trash"));
        btnDelete.addActionListener(e -> onDeleteProfile());

        buttons.add(btnClone);
        buttons.add(btnExport);
        buttons.add(btnImport);
        buttons.add(btnDelete);

        strip.add(comboProfiles, BorderLayout.CENTER);
        strip.add(buttons, BorderLayout.EAST);

        return strip;
    }

    private JPanel buildLayoutAndSectionsTab() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(new EmptyBorder(12, 12, 12, 12));

        // Cover Style
        JPanel pnlCover = new JPanel(new FlowLayout(FlowLayout.LEFT, 16, 4));
        pnlCover.setBorder(BorderFactory.createTitledBorder(I18n.t("ui.reporting.lbl.cover_style", "Cover Page Layout (PDF)")));
        ButtonGroup grpCover = new ButtonGroup();
        rdoCoverGradient = new JRadioButton("Gradient Hero (Modern Executive)");
        rdoCoverHeaderBand = new JRadioButton("Header Band (Classic Technical)");
        rdoCoverNone = new JRadioButton("None (Direct Flow)");
        grpCover.add(rdoCoverGradient);
        grpCover.add(rdoCoverHeaderBand);
        grpCover.add(rdoCoverNone);
        pnlCover.add(rdoCoverGradient);
        pnlCover.add(rdoCoverHeaderBand);
        pnlCover.add(rdoCoverNone);
        pnlCover.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.add(pnlCover);
        panel.add(Box.createVerticalStrut(10));

        // Finding Style
        JPanel pnlFinding = new JPanel(new FlowLayout(FlowLayout.LEFT, 16, 4));
        pnlFinding.setBorder(BorderFactory.createTitledBorder(I18n.t("ui.reporting.lbl.finding_style", "Finding Card Layout")));
        ButtonGroup grpFinding = new ButtonGroup();
        rdoFindingCard = new JRadioButton("Elevated Card (Modern Rounded)");
        rdoFindingTabular = new JRadioButton("Tabular Grid (Classic Boxed)");
        grpFinding.add(rdoFindingCard);
        grpFinding.add(rdoFindingTabular);
        pnlFinding.add(rdoFindingCard);
        pnlFinding.add(rdoFindingTabular);
        pnlFinding.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.add(pnlFinding);
        panel.add(Box.createVerticalStrut(10));

        // Sections Reordering Table
        JPanel pnlSections = new JPanel(new BorderLayout(8, 8));
        pnlSections.setBorder(BorderFactory.createTitledBorder(I18n.t("ui.reporting.lbl.sections_order", "Report Sections Flow")));

        sectionsTableModel = new DefaultTableModel(new Object[]{"Enabled", "Order", "Section Identifier", "Required"}, 0) {
            @Override
            public Class<?> getColumnClass(int columnIndex) {
                if (columnIndex == 0 || columnIndex == 3) return Boolean.class;
                if (columnIndex == 1) return Integer.class;
                return String.class;
            }

            @Override
            public boolean isCellEditable(int row, int column) {
                if (currentProfile != null && currentProfile.builtIn()) return false;
                if (column == 0) {
                    Boolean req = (Boolean) getValueAt(row, 3);
                    return req == null || !req; // cannot disable required section
                }
                return false;
            }
        };

        sectionsTable = new JTable(sectionsTableModel);
        sectionsTable.setRowHeight(24);
        sectionsTable.getColumnModel().getColumn(0).setMaxWidth(70);
        sectionsTable.getColumnModel().getColumn(1).setMaxWidth(60);
        sectionsTable.getColumnModel().getColumn(3).setMaxWidth(80);

        JScrollPane scrollTable = new JScrollPane(sectionsTable);
        scrollTable.setPreferredSize(new Dimension(500, 180));
        pnlSections.add(scrollTable, BorderLayout.CENTER);

        JPanel pnlOrderButtons = new JPanel(new GridLayout(2, 1, 4, 4));
        JButton btnUp = new JButton("▲ Move Up");
        JButton btnDown = new JButton("▼ Move Down");
        btnUp.addActionListener(e -> moveSectionRow(-1));
        btnDown.addActionListener(e -> moveSectionRow(1));
        pnlOrderButtons.add(btnUp);
        pnlOrderButtons.add(btnDown);
        pnlSections.add(pnlOrderButtons, BorderLayout.EAST);

        pnlSections.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.add(pnlSections);

        return panel;
    }

    private JPanel buildThemeTab() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(new EmptyBorder(12, 12, 12, 12));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(6, 6, 6, 6);
        gbc.fill = GridBagConstraints.HORIZONTAL;

        // Primary Color
        gbc.gridx = 0; gbc.gridy = 0; gbc.weightx = 0.0;
        panel.add(new JLabel("Primary Accent Color:"), gbc);
        colorPrimaryPanel = createColorPickerComponent("Primary");
        gbc.gridx = 1; gbc.weightx = 1.0;
        panel.add(colorPrimaryPanel, gbc);

        // Secondary Color
        gbc.gridx = 0; gbc.gridy = 1; gbc.weightx = 0.0;
        panel.add(new JLabel("Secondary Accent Color:"), gbc);
        colorSecondaryPanel = createColorPickerComponent("Secondary");
        gbc.gridx = 1; gbc.weightx = 1.0;
        panel.add(colorSecondaryPanel, gbc);

        // Typography
        gbc.gridx = 0; gbc.gridy = 2; gbc.weightx = 0.0;
        panel.add(new JLabel("Base Font Family:"), gbc);
        comboFontStack = new JComboBox<>(new String[]{"Helvetica", "Times-Roman", "Courier"});
        gbc.gridx = 1; gbc.weightx = 1.0;
        panel.add(comboFontStack, gbc);

        gbc.gridx = 0; gbc.gridy = 3; gbc.weightx = 0.0;
        panel.add(new JLabel("Base Font Size (PDF):"), gbc);
        spinFontSize = new JSpinner(new SpinnerNumberModel(10, 8, 14, 1));
        gbc.gridx = 1; gbc.weightx = 1.0;
        panel.add(spinFontSize, gbc);

        // Severity Colors Box
        gbc.gridx = 0; gbc.gridy = 4; gbc.gridwidth = 2;
        JPanel pnlSev = new JPanel(new GridLayout(2, 4, 8, 8));
        pnlSev.setBorder(BorderFactory.createTitledBorder("Severity Badge Colors"));

        for (Severity s : List.of(Severity.CRITICAL, Severity.HIGH, Severity.MEDIUM, Severity.LOW, Severity.INFO, Severity.FIXED, Severity.NOT_FIXED)) {
            JPanel p = createColorPickerComponent(s.name());
            severityColorPanels.put(s, p);
            pnlSev.add(p);
        }
        panel.add(pnlSev, gbc);

        return panel;
    }

    private JPanel buildBrandingTab() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(new EmptyBorder(12, 12, 12, 12));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(4, 6, 4, 6);
        gbc.fill = GridBagConstraints.HORIZONTAL;

        txtCompanyLogo = new JTextField();
        txtClientLogo = new JTextField();
        txtDocTitle = new JTextField();
        txtClassification = new JTextField("Confidencial");
        txtAuthor = new JTextField();
        txtReviewer = new JTextField();
        txtApprover = new JTextField();
        txtEnvironment = new JTextField();

        int row = 0;
        addFormRow(panel, gbc, row++, "Document Title:", txtDocTitle);
        addFormRow(panel, gbc, row++, "Classification:", txtClassification);
        addFormRow(panel, gbc, row++, "Author:", txtAuthor);
        addFormRow(panel, gbc, row++, "Reviewer:", txtReviewer);
        addFormRow(panel, gbc, row++, "Approver:", txtApprover);
        addFormRow(panel, gbc, row++, "Environment:", txtEnvironment);
        addFilePickerRow(panel, gbc, row++, "Company Logo (PDF Header):", txtCompanyLogo);
        addFilePickerRow(panel, gbc, row++, "Client Logo (Cover Page):", txtClientLogo);

        return panel;
    }

    private JPanel buildContentPolicyTab() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(new EmptyBorder(12, 12, 12, 12));

        chkIncludeEvidence = new JCheckBox("Include Evidence Screenshots", true);
        chkIncludeReq = new JCheckBox("Include Raw HTTP Request", true);
        spinMaxReqBytes = new JSpinner(new SpinnerNumberModel(4096, 512, 65536, 512));
        chkIncludeRes = new JCheckBox("Include Raw HTTP Response", true);
        spinMaxResBytes = new JSpinner(new SpinnerNumberModel(4096, 512, 65536, 512));
        chkToc = new JCheckBox("Generate Table of Contents (TOC / PDF Bookmarks)", true);

        JPanel pnlGating = new JPanel(new GridLayout(3, 2, 8, 8));
        pnlGating.setBorder(BorderFactory.createTitledBorder("Evidence & Excerpt Caps"));
        pnlGating.add(chkIncludeEvidence);
        pnlGating.add(chkToc);
        pnlGating.add(chkIncludeReq);
        pnlGating.add(wrapWithLabel("Max Request Bytes:", spinMaxReqBytes));
        pnlGating.add(chkIncludeRes);
        pnlGating.add(wrapWithLabel("Max Response Bytes:", spinMaxResBytes));
        pnlGating.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.add(pnlGating);
        panel.add(Box.createVerticalStrut(10));

        JPanel pnlFields = new JPanel(new GridLayout(2, 4, 8, 8));
        pnlFields.setBorder(BorderFactory.createTitledBorder("Finding Fields Visibility"));
        for (FindingField f : FindingField.values()) {
            JCheckBox chk = new JCheckBox(f.name(), true);
            fieldCheckboxes.put(f, chk);
            pnlFields.add(chk);
        }
        pnlFields.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.add(pnlFields);

        return panel;
    }

    private JPanel buildBottomActionBar() {
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 4));
        bar.setAlignmentX(Component.LEFT_ALIGNMENT);

        btnPreviewPdf = new JButton("👁️ Preview PDF", EvidenceUiHelpers.createIcon("file-type-pdf"));
        btnPreviewPdf.addActionListener(e -> runPreview(PreviewService.Format.PDF));

        btnPreviewHtml = new JButton("👁️ Preview HTML", EvidenceUiHelpers.createIcon("file-type-html"));
        btnPreviewHtml.addActionListener(e -> runPreview(PreviewService.Format.HTML));

        btnSave = new JButton("💾 Save Profile Changes", EvidenceUiHelpers.createIcon("check"));
        btnSave.putClientProperty("FlatLaf.style", "background: #FF6633; foreground: #FFFFFF; font: bold;");
        btnSave.addActionListener(e -> saveCurrentProfileChanges());

        bar.add(btnPreviewPdf);
        bar.add(btnPreviewHtml);
        bar.add(btnSave);

        return bar;
    }

    private void refreshProfileList() {
        isUpdatingUi = true;
        try {
            comboProfiles.removeAllItems();
            for (ReportProfile p : profileManager.list()) {
                comboProfiles.addItem(p);
            }
            ReportProfile active = profileManager.active();
            comboProfiles.setSelectedItem(active);
            loadProfileIntoForm(active);
        } finally {
            isUpdatingUi = false;
        }
    }

    private void loadProfileIntoForm(ReportProfile p) {
        if (p == null) return;
        this.currentProfile = p;

        boolean editable = !p.builtIn();
        btnSave.setEnabled(editable);
        btnDelete.setEnabled(editable);

        // Cover
        if (p.coverRenderer() == CoverRendererId.GRADIENT_HERO) rdoCoverGradient.setSelected(true);
        else if (p.coverRenderer() == CoverRendererId.HEADER_BAND) rdoCoverHeaderBand.setSelected(true);
        else rdoCoverNone.setSelected(true);

        // Finding
        if (p.findingRenderer() == FindingRendererId.TABULAR) rdoFindingTabular.setSelected(true);
        else rdoFindingCard.setSelected(true);

        // Sections
        sectionsTableModel.setRowCount(0);
        for (SectionNode node : p.sections().nodes()) {
            sectionsTableModel.addRow(new Object[]{node.enabled(), node.order(), node.id(), node.required()});
        }

        // Colors
        setColorPickerValue(colorPrimaryPanel, p.pdfTheme().primaryHex());
        setColorPickerValue(colorSecondaryPanel, p.pdfTheme().secondaryHex());
        comboFontStack.setSelectedItem(p.pdfTheme().fontStack());
        spinFontSize.setValue(p.pdfTheme().baseFontSize());

        for (Map.Entry<Severity, String> entry : p.pdfTheme().severityHex().entrySet()) {
            JPanel cp = severityColorPanels.get(entry.getKey());
            if (cp != null) setColorPickerValue(cp, entry.getValue());
        }

        // Branding
        BrandingConfig b = p.branding();
        txtDocTitle.setText(b != null ? b.documentTitle() : "");
        txtClassification.setText(b != null ? b.classification() : "");
        txtAuthor.setText(b != null ? b.author() : "");
        txtReviewer.setText(b != null ? b.reviewer() : "");
        txtApprover.setText(b != null ? b.approver() : "");
        txtEnvironment.setText(b != null ? b.environment() : "");
        txtCompanyLogo.setText(b != null && b.companyLogoPath() != null ? b.companyLogoPath() : "");
        txtClientLogo.setText(b != null && b.clientLogoPath() != null ? b.clientLogoPath() : "");

        // Content
        ContentPolicy c = p.content();
        chkIncludeEvidence.setSelected(c.includeEvidence());
        chkIncludeReq.setSelected(c.includeHttpRequest());
        spinMaxReqBytes.setValue(c.maxRequestBytes());
        chkIncludeRes.setSelected(c.includeHttpResponse());
        spinMaxResBytes.setValue(c.maxResponseBytes());
        chkToc.setSelected(c.includeTocBookmarks());

        for (Map.Entry<FindingField, JCheckBox> entry : fieldCheckboxes.entrySet()) {
            entry.getValue().setSelected(c.findingFields().contains(entry.getKey()));
        }
    }

    private void saveCurrentProfileChanges() {
        if (currentProfile == null || currentProfile.builtIn()) return;

        CoverRendererId cover = rdoCoverGradient.isSelected() ? CoverRendererId.GRADIENT_HERO :
            (rdoCoverHeaderBand.isSelected() ? CoverRendererId.HEADER_BAND : CoverRendererId.NONE);

        FindingRendererId finding = rdoFindingTabular.isSelected() ? FindingRendererId.TABULAR : FindingRendererId.ELEVATED_CARD;

        // Sections
        List<SectionNode> nodes = new ArrayList<>();
        for (int i = 0; i < sectionsTableModel.getRowCount(); i++) {
            boolean en = (Boolean) sectionsTableModel.getValueAt(i, 0);
            int order = i + 1;
            String id = (String) sectionsTableModel.getValueAt(i, 2);
            boolean req = (Boolean) sectionsTableModel.getValueAt(i, 3);
            nodes.add(SectionNode.of(id, en, order, req));
        }

        String primary = (String) colorPrimaryPanel.getClientProperty("hexValue");
        String secondary = (String) colorSecondaryPanel.getClientProperty("hexValue");
        String font = (String) comboFontStack.getSelectedItem();
        int fontSize = (Integer) spinFontSize.getValue();

        Map<Severity, String> sevHex = new EnumMap<>(Severity.class);
        for (Map.Entry<Severity, JPanel> entry : severityColorPanels.entrySet()) {
            String val = (String) entry.getValue().getClientProperty("hexValue");
            if (val != null) sevHex.put(entry.getKey(), val);
        }

        PdfTheme pdfTheme = new PdfTheme(primary, secondary, "#202020", primary, "#F7F7F7", sevHex, font, fontSize, PageBox.a4Default());
        HtmlTheme htmlTheme = new HtmlTheme(primary, secondary, "#FFFFFF", "#F7F7F7", "#1A1A1A", "#DDDDDD", sevHex, "-apple-system, BlinkMacSystemFont, sans-serif", false);

        BrandingConfig branding = new BrandingConfig(
            blankToNull(txtCompanyLogo.getText()),
            blankToNull(txtClientLogo.getText()),
            txtAuthor.getText().trim(),
            txtReviewer.getText().trim(),
            txtApprover.getText().trim(),
            txtClassification.getText().trim(),
            txtEnvironment.getText().trim(),
            txtDocTitle.getText().trim(),
            "", "", "", "", "", ""
        );

        List<FindingField> fields = new ArrayList<>();
        for (Map.Entry<FindingField, JCheckBox> entry : fieldCheckboxes.entrySet()) {
            if (entry.getValue().isSelected()) fields.add(entry.getKey());
        }

        ContentPolicy content = new ContentPolicy(
            chkIncludeEvidence.isSelected(),
            chkIncludeReq.isSelected(),
            chkIncludeRes.isSelected(),
            (Integer) spinMaxReqBytes.getValue(),
            (Integer) spinMaxResBytes.getValue(),
            fields,
            CweMode.HARDCODED_CATALOG,
            Collections.emptyList(),
            chkToc.isSelected()
        );

        ReportProfile updated = new ReportProfile(
            ReportProfile.CURRENT_SCHEMA_VERSION,
            currentProfile.id(),
            currentProfile.name(),
            currentProfile.locale(),
            false,
            currentProfile.basedOnId(),
            cover,
            finding,
            new SectionGraph(nodes),
            branding,
            content,
            pdfTheme,
            htmlTheme
        );

        profileManager.saveUserProfile(updated);
        showToast("Profile '" + updated.name() + "' saved successfully!");
    }

    private void showToast(String message) {
        Frame parent = api != null ? api.userInterface().swingUtils().suiteFrame() : null;
        if (parent == null) {
            Window w = SwingUtilities.getWindowAncestor(containerPanel);
            if (w instanceof Frame f) parent = f;
        }
        if (parent != null) {
            ToastNotification.show(parent, message);
        }
    }

    private void onCloneProfile() {
        if (currentProfile == null) return;
        String name = JOptionPane.showInputDialog(containerPanel, "Enter name for cloned profile:", currentProfile.name() + " (Custom)");
        if (name != null && !name.isBlank()) {
            ReportProfile cloned = profileManager.clone(currentProfile.id(), name.trim());
            profileManager.setActive(cloned.id());
            refreshProfileList();
            showToast("Created profile '" + cloned.name() + "'");
        }
    }

    private void onExportProfile() {
        if (currentProfile == null) return;
        JFileChooser chooser = new JFileChooser();
        chooser.setSelectedFile(new File(currentProfile.name().toLowerCase().replace(" ", "-") + "-profile.json"));
        if (chooser.showSaveDialog(containerPanel) == JFileChooser.APPROVE_OPTION) {
            try {
                String json = profileManager.exportJson(currentProfile.id());
                Files.writeString(chooser.getSelectedFile().toPath(), json);
                showToast("Profile exported to JSON!");
            } catch (Exception e) {
                JOptionPane.showMessageDialog(containerPanel, "Export failed: " + e.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    private void onImportProfile() {
        JFileChooser chooser = new JFileChooser();
        if (chooser.showOpenDialog(containerPanel) == JFileChooser.APPROVE_OPTION) {
            try {
                String json = Files.readString(chooser.getSelectedFile().toPath());
                ReportProfile imported = profileManager.importJson(json);
                profileManager.setActive(imported.id());
                refreshProfileList();
                showToast("Imported profile '" + imported.name() + "'!");
            } catch (Exception e) {
                JOptionPane.showMessageDialog(containerPanel, "Import failed: " + e.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    private void onDeleteProfile() {
        if (currentProfile == null || currentProfile.builtIn()) return;
        int opt = JOptionPane.showConfirmDialog(containerPanel, "Delete profile '" + currentProfile.name() + "'?", "Confirm Delete", JOptionPane.YES_NO_OPTION);
        if (opt == JOptionPane.YES_OPTION) {
            profileManager.deleteUserProfile(currentProfile.id());
            refreshProfileList();
            showToast("Profile deleted.");
        }
    }

    private void runPreview(PreviewService.Format format) {
        if (currentProfile == null) return;
        new SwingWorker<File, Void>() {
            @Override
            protected File doInBackground() throws Exception {
                return PreviewService.generatePreviewFile(currentProfile, format);
            }

            @Override
            protected void done() {
                try {
                    File file = get();
                    if (file != null && Desktop.isDesktopSupported()) {
                        Desktop.getDesktop().open(file);
                    }
                } catch (Exception e) {
                    JOptionPane.showMessageDialog(containerPanel, "Preview failed: " + e.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
                }
            }
        }.execute();
    }

    private void moveSectionRow(int delta) {
        int idx = sectionsTable.getSelectedRow();
        if (idx < 0) return;
        int target = idx + delta;
        if (target < 0 || target >= sectionsTableModel.getRowCount()) return;
        sectionsTableModel.moveRow(idx, idx, target);
        sectionsTable.setRowSelectionInterval(target, target);
    }

    private JPanel createColorPickerComponent(String label) {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        panel.setOpaque(false);
        if (label != null && !label.isEmpty()) {
            panel.add(new JLabel(label));
        }
        JButton colorBtn = new JButton();
        colorBtn.setPreferredSize(new Dimension(22, 22));
        colorBtn.putClientProperty("FlatLaf.style", "arc: 6; borderWidth: 1; borderColor: $Component.borderColor");
        colorBtn.setCursor(new Cursor(Cursor.HAND_CURSOR));
        colorBtn.setOpaque(true);

        JLabel hexLabel = new JLabel("#------");
        colorBtn.addActionListener(e -> {
            if (currentProfile != null && currentProfile.builtIn()) return;
            Color chosen = JColorChooser.showDialog(panel, "Choose Color", colorBtn.getBackground());
            if (chosen != null) {
                colorBtn.setBackground(chosen);
                String hex = String.format("#%02x%02x%02x", chosen.getRed(), chosen.getGreen(), chosen.getBlue()).toUpperCase();
                hexLabel.setText(hex);
                panel.putClientProperty("hexValue", hex);
            }
        });
        panel.add(colorBtn);
        panel.add(hexLabel);
        return panel;
    }

    private void setColorPickerValue(JPanel panel, String hex) {
        if (panel == null) return;
        JButton btn = (JButton) panel.getComponent(panel.getComponentCount() - 2);
        JLabel lbl = (JLabel) panel.getComponent(panel.getComponentCount() - 1);
        if (hex != null && hex.matches("^#([A-Fa-f0-9]{6}|[A-Fa-f0-9]{3})$")) {
            try {
                btn.setBackground(Color.decode(hex));
                lbl.setText(hex.toUpperCase());
                panel.putClientProperty("hexValue", hex.toUpperCase());
            } catch (Exception ignored) {}
        }
    }

    private void addFormRow(JPanel panel, GridBagConstraints gbc, int row, String label, JTextField field) {
        gbc.gridx = 0; gbc.gridy = row; gbc.weightx = 0.0;
        panel.add(new JLabel(label), gbc);
        gbc.gridx = 1; gbc.weightx = 1.0;
        panel.add(field, gbc);
    }

    private void addFilePickerRow(JPanel panel, GridBagConstraints gbc, int row, String label, JTextField field) {
        gbc.gridx = 0; gbc.gridy = row; gbc.weightx = 0.0;
        panel.add(new JLabel(label), gbc);
        JPanel p = new JPanel(new BorderLayout(4, 0));
        p.setOpaque(false);
        p.add(field, BorderLayout.CENTER);
        JButton btnBrowse = new JButton("Browse...");
        btnBrowse.addActionListener(e -> {
            JFileChooser fc = new JFileChooser();
            if (fc.showOpenDialog(panel) == JFileChooser.APPROVE_OPTION) {
                field.setText(fc.getSelectedFile().getAbsolutePath());
            }
        });
        p.add(btnBrowse, BorderLayout.EAST);
        gbc.gridx = 1; gbc.weightx = 1.0;
        panel.add(p, gbc);
    }

    private JPanel wrapWithLabel(String label, JComponent comp) {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        p.setOpaque(false);
        p.add(new JLabel(label));
        p.add(comp);
        return p;
    }

    private static String blankToNull(String s) {
        return (s == null || s.trim().isEmpty()) ? null : s.trim();
    }
}
