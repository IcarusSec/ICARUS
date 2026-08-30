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
 * Layout uses vertical BoxLayout with GridBagLayout forms — no fixed widths,
 * no horizontal scroll.
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

    // Layout tab
    private JRadioButton rdoCoverGradient;
    private JRadioButton rdoCoverHeaderBand;
    private JRadioButton rdoCoverNone;
    private JRadioButton rdoFindingCard;
    private JRadioButton rdoFindingTabular;
    private DefaultTableModel sectionsTableModel;
    private JTable sectionsTable;

    // Theme tab
    private JPanel colorPrimaryPanel;
    private JPanel colorSecondaryPanel;
    private JComboBox<String> comboFontStack;
    private JSpinner spinFontSize;
    private Map<Severity, JPanel> severityColorPanels = new EnumMap<>(Severity.class);

    // Branding tab
    private JTextField txtCompanyLogo;
    private JTextField txtClientLogo;
    private JTextField txtDocTitle;
    private JTextField txtClassification;
    private JTextField txtAuthor;
    private JTextField txtReviewer;
    private JTextField txtApprover;
    private JTextField txtEnvironment;

    // Content tab
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
        masterScroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
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

    // ─── Main Layout ────────────────────────────────────────────────

    private JPanel buildReportingTab() {
        JPanel main = new JPanel();
        main.setLayout(new BoxLayout(main, BoxLayout.Y_AXIS));
        main.setBorder(new EmptyBorder(16, 16, 16, 16));
        themeHelper.applyTheme(main);

        main.add(buildProfileSelector());
        main.add(Box.createVerticalStrut(12));

        JTabbedPane tabs = new JTabbedPane();
        tabs.putClientProperty("JTabbedPane.tabType", "underlined");
        tabs.addTab(I18n.t("ui.reporting.tab.layout", "Layout & Sections"), buildLayoutTab());
        tabs.addTab(I18n.t("ui.reporting.tab.theme", "Colors & Theme"), buildThemeTab());
        tabs.addTab(I18n.t("ui.reporting.tab.branding", "Branding & Metadata"), buildBrandingTab());
        tabs.addTab(I18n.t("ui.reporting.tab.content", "Content & Policy"), buildContentPolicyTab());
        tabs.setAlignmentX(Component.LEFT_ALIGNMENT);
        main.add(tabs);
        main.add(Box.createVerticalStrut(12));

        main.add(buildBottomBar());
        return main;
    }

    // ─── Profile Selector (responsive, stacked) ────────────────────

    private JPanel buildProfileSelector() {
        JPanel card = new JPanel();
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
        card.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createTitledBorder(
                BorderFactory.createLineBorder(UIManager.getColor("Component.borderColor"), 1, true),
                " Report Profile ",
                TitledBorder.LEFT, TitledBorder.TOP,
                UIManager.getFont("TitledBorder.font")
            ),
            new EmptyBorder(10, 12, 10, 12)
        ));
        card.setAlignmentX(Component.LEFT_ALIGNMENT);

        // Row 1: Combo (full width)
        comboProfiles = new JComboBox<>();
        comboProfiles.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
        comboProfiles.setAlignmentX(Component.LEFT_ALIGNMENT);
        comboProfiles.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
                super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                if (value instanceof ReportProfile p) {
                    String icon = p.builtIn() ? "\uD83D\uDD12" : "\u270F\uFE0F";
                    String tag = p.builtIn() ? "Built-in" : "Custom";
                    setText(icon + "  " + p.name() + "  [" + tag + "]");
                }
                return this;
            }
        });
        comboProfiles.addActionListener(e -> {
            if (isUpdatingUi) return;
            ReportProfile sel = (ReportProfile) comboProfiles.getSelectedItem();
            if (sel != null) {
                profileManager.setActive(sel.id());
                loadProfileIntoForm(sel);
            }
        });

        card.add(comboProfiles);
        card.add(Box.createVerticalStrut(8));

        // Row 2: Action buttons (wrap-friendly)
        JPanel btns = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        btns.setOpaque(false);
        btns.setAlignmentX(Component.LEFT_ALIGNMENT);

        btnClone = makeSmallButton("Clone", "copy");
        btnClone.setToolTipText("Create an editable copy of the selected profile");
        btnClone.addActionListener(e -> onCloneProfile());

        btnExport = makeSmallButton("Export", "download");
        btnExport.setToolTipText("Export profile as a JSON file");
        btnExport.addActionListener(e -> onExportProfile());

        btnImport = makeSmallButton("Import", "folder");
        btnImport.setToolTipText("Import a profile from a JSON file");
        btnImport.addActionListener(e -> onImportProfile());

        btnDelete = makeSmallButton("Delete", "trash");
        btnDelete.setToolTipText("Delete selected custom profile");
        btnDelete.addActionListener(e -> onDeleteProfile());

        btns.add(btnClone);
        btns.add(btnExport);
        btns.add(btnImport);
        btns.add(btnDelete);

        card.add(btns);
        return card;
    }

    // ─── Tab 1: Layout & Sections ──────────────────────────────────

    private JPanel buildLayoutTab() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(new EmptyBorder(12, 12, 12, 12));

        // Cover Page Style — vertical radio group
        JPanel pnlCover = new JPanel();
        pnlCover.setLayout(new BoxLayout(pnlCover, BoxLayout.Y_AXIS));
        pnlCover.setBorder(makeSectionBorder("Cover Page Layout (PDF)"));
        ButtonGroup grpCover = new ButtonGroup();
        rdoCoverGradient = new JRadioButton("Gradient Hero (Modern Executive)");
        rdoCoverHeaderBand = new JRadioButton("Header Band (Classic Technical)");
        rdoCoverNone = new JRadioButton("None (Direct Flow)");
        for (JRadioButton r : List.of(rdoCoverGradient, rdoCoverHeaderBand, rdoCoverNone)) {
            grpCover.add(r);
            r.setAlignmentX(Component.LEFT_ALIGNMENT);
            pnlCover.add(r);
            pnlCover.add(Box.createVerticalStrut(2));
        }
        pnlCover.setAlignmentX(Component.LEFT_ALIGNMENT);
        setMaxWidth(pnlCover);
        panel.add(pnlCover);
        panel.add(Box.createVerticalStrut(10));

        // Finding Card Style — vertical radio group
        JPanel pnlFinding = new JPanel();
        pnlFinding.setLayout(new BoxLayout(pnlFinding, BoxLayout.Y_AXIS));
        pnlFinding.setBorder(makeSectionBorder("Finding Card Layout"));
        ButtonGroup grpFinding = new ButtonGroup();
        rdoFindingCard = new JRadioButton("Elevated Card (Modern Rounded)");
        rdoFindingTabular = new JRadioButton("Tabular Grid (Classic Boxed)");
        for (JRadioButton r : List.of(rdoFindingCard, rdoFindingTabular)) {
            grpFinding.add(r);
            r.setAlignmentX(Component.LEFT_ALIGNMENT);
            pnlFinding.add(r);
            pnlFinding.add(Box.createVerticalStrut(2));
        }
        pnlFinding.setAlignmentX(Component.LEFT_ALIGNMENT);
        setMaxWidth(pnlFinding);
        panel.add(pnlFinding);
        panel.add(Box.createVerticalStrut(10));

        // Sections table
        JPanel pnlSections = new JPanel(new BorderLayout(8, 8));
        pnlSections.setBorder(makeSectionBorder("Report Sections Flow"));
        pnlSections.setAlignmentX(Component.LEFT_ALIGNMENT);

        sectionsTableModel = new DefaultTableModel(new Object[]{"Enabled", "#", "Section", "Required"}, 0) {
            @Override
            public Class<?> getColumnClass(int col) {
                if (col == 0 || col == 3) return Boolean.class;
                if (col == 1) return Integer.class;
                return String.class;
            }

            @Override
            public boolean isCellEditable(int row, int col) {
                if (currentProfile != null && currentProfile.builtIn()) return false;
                if (col == 0) {
                    Boolean req = (Boolean) getValueAt(row, 3);
                    return req == null || !req;
                }
                return false;
            }
        };
        sectionsTable = new JTable(sectionsTableModel);
        sectionsTable.setRowHeight(24);
        sectionsTable.getColumnModel().getColumn(0).setMaxWidth(60);
        sectionsTable.getColumnModel().getColumn(0).setMinWidth(50);
        sectionsTable.getColumnModel().getColumn(1).setMaxWidth(35);
        sectionsTable.getColumnModel().getColumn(1).setMinWidth(30);
        sectionsTable.getColumnModel().getColumn(3).setMaxWidth(65);
        sectionsTable.getColumnModel().getColumn(3).setMinWidth(55);

        JScrollPane scrollTable = new JScrollPane(sectionsTable);
        scrollTable.setPreferredSize(new Dimension(100, 180));
        pnlSections.add(scrollTable, BorderLayout.CENTER);

        JPanel pnlOrderBtns = new JPanel(new GridLayout(2, 1, 4, 4));
        pnlOrderBtns.setOpaque(false);
        JButton btnUp = makeSmallButton("Up", "arrow-up");
        JButton btnDown = makeSmallButton("Down", "arrow-down");
        btnUp.addActionListener(e -> moveSectionRow(-1));
        btnDown.addActionListener(e -> moveSectionRow(1));
        pnlOrderBtns.add(btnUp);
        pnlOrderBtns.add(btnDown);
        pnlSections.add(pnlOrderBtns, BorderLayout.EAST);

        panel.add(pnlSections);
        return panel;
    }

    // ─── Tab 2: Colors & Theme ─────────────────────────────────────

    private JPanel buildThemeTab() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(new EmptyBorder(12, 12, 12, 12));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(6, 6, 6, 6);
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.anchor = GridBagConstraints.WEST;

        int row = 0;
        // Primary
        gbc.gridx = 0; gbc.gridy = row; gbc.weightx = 0;
        panel.add(new JLabel("Primary Accent Color:"), gbc);
        colorPrimaryPanel = createColorSwatch();
        gbc.gridx = 1; gbc.weightx = 1.0;
        panel.add(colorPrimaryPanel, gbc);

        // Secondary
        row++;
        gbc.gridx = 0; gbc.gridy = row; gbc.weightx = 0;
        panel.add(new JLabel("Secondary Accent Color:"), gbc);
        colorSecondaryPanel = createColorSwatch();
        gbc.gridx = 1; gbc.weightx = 1.0;
        panel.add(colorSecondaryPanel, gbc);

        // Font
        row++;
        gbc.gridx = 0; gbc.gridy = row; gbc.weightx = 0;
        panel.add(new JLabel("Font Family:"), gbc);
        comboFontStack = new JComboBox<>(new String[]{"Helvetica", "Times-Roman", "Courier"});
        gbc.gridx = 1; gbc.weightx = 1.0;
        panel.add(comboFontStack, gbc);

        // Font size
        row++;
        gbc.gridx = 0; gbc.gridy = row; gbc.weightx = 0;
        panel.add(new JLabel("Font Size (PDF):"), gbc);
        spinFontSize = new JSpinner(new SpinnerNumberModel(10, 8, 14, 1));
        gbc.gridx = 1; gbc.weightx = 1.0;
        panel.add(spinFontSize, gbc);

        // Severity Colors — use a WrapLayout (3 columns GridBag) inside a titled panel
        row++;
        gbc.gridx = 0; gbc.gridy = row; gbc.gridwidth = 2; gbc.weightx = 1.0;

        JPanel pnlSev = new JPanel(new GridBagLayout());
        pnlSev.setBorder(makeSectionBorder("Severity Badge Colors"));
        GridBagConstraints sg = new GridBagConstraints();
        sg.insets = new Insets(4, 4, 4, 4);
        sg.fill = GridBagConstraints.HORIZONTAL;
        sg.weightx = 1.0;

        Severity[] sevs = {Severity.CRITICAL, Severity.HIGH, Severity.MEDIUM, Severity.LOW, Severity.INFO, Severity.FIXED, Severity.NOT_FIXED};
        for (int i = 0; i < sevs.length; i++) {
            sg.gridx = i % 3;
            sg.gridy = i / 3;
            JPanel swatch = createColorSwatch();
            severityColorPanels.put(sevs[i], swatch);

            // Wrap with label
            JPanel labeled = new JPanel(new BorderLayout(4, 0));
            labeled.setOpaque(false);
            labeled.add(new JLabel(formatSeverityLabel(sevs[i])), BorderLayout.WEST);
            labeled.add(swatch, BorderLayout.CENTER);
            pnlSev.add(labeled, sg);
        }
        // filler for last row
        sg.gridx = sevs.length % 3;
        sg.gridy = sevs.length / 3;
        sg.gridwidth = 3 - (sevs.length % 3);
        if (sg.gridwidth > 0 && sg.gridwidth < 3) {
            pnlSev.add(Box.createGlue(), sg);
        }

        panel.add(pnlSev, gbc);
        gbc.gridwidth = 1; // reset

        return panel;
    }

    // ─── Tab 3: Branding & Metadata ────────────────────────────────

    private JPanel buildBrandingTab() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(new EmptyBorder(12, 12, 12, 12));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(4, 6, 4, 6);
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.anchor = GridBagConstraints.WEST;

        txtDocTitle = new JTextField();
        txtClassification = new JTextField("Confidencial");
        txtAuthor = new JTextField();
        txtReviewer = new JTextField();
        txtApprover = new JTextField();
        txtEnvironment = new JTextField();
        txtCompanyLogo = new JTextField();
        txtClientLogo = new JTextField();

        int row = 0;
        addFormRow(panel, gbc, row++, "Document Title:", txtDocTitle);
        addFormRow(panel, gbc, row++, "Classification:", txtClassification);
        addFormRow(panel, gbc, row++, "Author:", txtAuthor);
        addFormRow(panel, gbc, row++, "Reviewer:", txtReviewer);
        addFormRow(panel, gbc, row++, "Approver:", txtApprover);
        addFormRow(panel, gbc, row++, "Environment:", txtEnvironment);
        addFilePickerRow(panel, gbc, row++, "Company Logo:", txtCompanyLogo);
        addFilePickerRow(panel, gbc, row++, "Client Logo:", txtClientLogo);

        return panel;
    }

    // ─── Tab 4: Content & Policy ───────────────────────────────────

    private JPanel buildContentPolicyTab() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(new EmptyBorder(12, 12, 12, 12));

        chkIncludeEvidence = new JCheckBox("Include Evidence Screenshots", true);
        chkIncludeReq = new JCheckBox("Include Raw HTTP Request", true);
        spinMaxReqBytes = new JSpinner(new SpinnerNumberModel(4096, 512, 65536, 512));
        chkIncludeRes = new JCheckBox("Include Raw HTTP Response", true);
        spinMaxResBytes = new JSpinner(new SpinnerNumberModel(4096, 512, 65536, 512));
        chkToc = new JCheckBox("Generate Table of Contents", true);

        // Evidence & Excerpt section
        JPanel pnlGating = new JPanel(new GridBagLayout());
        pnlGating.setBorder(makeSectionBorder("Evidence & Excerpt Caps"));
        pnlGating.setAlignmentX(Component.LEFT_ALIGNMENT);
        GridBagConstraints gc = new GridBagConstraints();
        gc.insets = new Insets(3, 6, 3, 6);
        gc.fill = GridBagConstraints.HORIZONTAL;
        gc.anchor = GridBagConstraints.WEST;

        gc.gridx = 0; gc.gridy = 0; gc.weightx = 1.0; gc.gridwidth = 2;
        pnlGating.add(chkIncludeEvidence, gc);
        gc.gridy = 1;
        pnlGating.add(chkToc, gc);
        gc.gridy = 2; gc.gridwidth = 1; gc.weightx = 0;
        pnlGating.add(chkIncludeReq, gc);
        gc.gridx = 1; gc.weightx = 1.0;
        pnlGating.add(wrapWithLabel("Max bytes:", spinMaxReqBytes), gc);
        gc.gridx = 0; gc.gridy = 3; gc.weightx = 0;
        pnlGating.add(chkIncludeRes, gc);
        gc.gridx = 1; gc.weightx = 1.0;
        pnlGating.add(wrapWithLabel("Max bytes:", spinMaxResBytes), gc);

        setMaxWidth(pnlGating);
        panel.add(pnlGating);
        panel.add(Box.createVerticalStrut(10));

        // Finding Fields
        JPanel pnlFields = new JPanel(new GridBagLayout());
        pnlFields.setBorder(makeSectionBorder("Finding Fields Visibility"));
        pnlFields.setAlignmentX(Component.LEFT_ALIGNMENT);
        GridBagConstraints fg = new GridBagConstraints();
        fg.insets = new Insets(2, 6, 2, 6);
        fg.fill = GridBagConstraints.HORIZONTAL;
        fg.anchor = GridBagConstraints.WEST;
        fg.weightx = 1.0;

        FindingField[] fields = FindingField.values();
        for (int i = 0; i < fields.length; i++) {
            fg.gridx = i % 3;
            fg.gridy = i / 3;
            JCheckBox chk = new JCheckBox(formatFieldLabel(fields[i]), true);
            fieldCheckboxes.put(fields[i], chk);
            pnlFields.add(chk, fg);
        }

        setMaxWidth(pnlFields);
        panel.add(pnlFields);

        return panel;
    }

    // ─── Bottom Bar ────────────────────────────────────────────────

    private JPanel buildBottomBar() {
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 4));
        bar.setAlignmentX(Component.LEFT_ALIGNMENT);
        bar.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));

        btnPreviewPdf = makeSmallButton("Preview PDF", "file-text");
        btnPreviewPdf.addActionListener(e -> runPreview(PreviewService.Format.PDF));

        btnPreviewHtml = makeSmallButton("Preview HTML", "external-link");
        btnPreviewHtml.addActionListener(e -> runPreview(PreviewService.Format.HTML));

        btnSave = new JButton("Save Profile");
        btnSave.setIcon(EvidenceUiHelpers.createIcon("check"));
        btnSave.putClientProperty("FlatLaf.style", "background: #FF6633; foreground: #FFFFFF; font: bold $defaultFont;");
        btnSave.setFocusPainted(false);
        btnSave.addActionListener(e -> saveCurrentProfileChanges());

        bar.add(btnPreviewPdf);
        bar.add(btnPreviewHtml);
        bar.add(Box.createHorizontalStrut(12));
        bar.add(btnSave);

        return bar;
    }

    // ─── Data loading / saving ─────────────────────────────────────

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
        setSwatchColor(colorPrimaryPanel, p.pdfTheme().primaryHex());
        setSwatchColor(colorSecondaryPanel, p.pdfTheme().secondaryHex());
        comboFontStack.setSelectedItem(p.pdfTheme().fontStack());
        spinFontSize.setValue(p.pdfTheme().baseFontSize());

        for (Map.Entry<Severity, String> entry : p.pdfTheme().severityHex().entrySet()) {
            JPanel cp = severityColorPanels.get(entry.getKey());
            if (cp != null) setSwatchColor(cp, entry.getValue());
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

        List<SectionNode> nodes = new ArrayList<>();
        for (int i = 0; i < sectionsTableModel.getRowCount(); i++) {
            boolean en = (Boolean) sectionsTableModel.getValueAt(i, 0);
            String id = (String) sectionsTableModel.getValueAt(i, 2);
            boolean req = (Boolean) sectionsTableModel.getValueAt(i, 3);
            nodes.add(SectionNode.of(id, en, i + 1, req));
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
        showToast("Profile '" + updated.name() + "' saved.");
    }

    // ─── Profile Actions ───────────────────────────────────────────

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
        String name = JOptionPane.showInputDialog(containerPanel, "Enter name for the new profile:", currentProfile.name() + " (Custom)");
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
                showToast("Profile exported.");
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
                showToast("Imported profile '" + imported.name() + "'");
            } catch (Exception e) {
                JOptionPane.showMessageDialog(containerPanel, "Import failed: " + e.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    private void onDeleteProfile() {
        if (currentProfile == null || currentProfile.builtIn()) return;
        int opt = JOptionPane.showConfirmDialog(containerPanel, "Delete profile '" + currentProfile.name() + "'?", "Confirm", JOptionPane.YES_NO_OPTION);
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

    // ─── UI Helper Methods ─────────────────────────────────────────

    /** Creates a compact button with an SVG icon and short text label. */
    private JButton makeSmallButton(String text, String iconName) {
        JButton btn = new JButton(text);
        Icon icon = EvidenceUiHelpers.createIcon(iconName);
        if (icon != null) btn.setIcon(icon);
        btn.setFocusPainted(false);
        btn.setMargin(new Insets(3, 8, 3, 8));
        return btn;
    }

    /** Creates a color swatch: [color square] [#HEXHEX] — clickable to open color chooser. */
    private JPanel createColorSwatch() {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        panel.setOpaque(false);

        JButton colorBtn = new JButton();
        colorBtn.setPreferredSize(new Dimension(22, 22));
        colorBtn.setMinimumSize(new Dimension(22, 22));
        colorBtn.setMaximumSize(new Dimension(22, 22));
        colorBtn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        colorBtn.setOpaque(true);
        colorBtn.setBorderPainted(true);
        colorBtn.setFocusPainted(false);

        JLabel hexLabel = new JLabel("#------");
        hexLabel.setFont(hexLabel.getFont().deriveFont(Font.PLAIN, 11f));

        colorBtn.addActionListener(e -> {
            if (currentProfile != null && currentProfile.builtIn()) return;
            Color chosen = JColorChooser.showDialog(panel, "Choose Color", colorBtn.getBackground());
            if (chosen != null) {
                colorBtn.setBackground(chosen);
                String hex = String.format("#%02X%02X%02X", chosen.getRed(), chosen.getGreen(), chosen.getBlue());
                hexLabel.setText(hex);
                panel.putClientProperty("hexValue", hex);
            }
        });

        panel.add(colorBtn);
        panel.add(hexLabel);
        return panel;
    }

    /** Sets the color swatch background and hex label from a hex string. */
    private void setSwatchColor(JPanel swatch, String hex) {
        if (swatch == null || hex == null) return;
        if (!hex.matches("^#([A-Fa-f0-9]{6}|[A-Fa-f0-9]{3})$")) return;
        try {
            // swatch children: [0]=colorBtn, [1]=hexLabel
            JButton btn = (JButton) swatch.getComponent(0);
            JLabel lbl = (JLabel) swatch.getComponent(1);
            btn.setBackground(Color.decode(hex));
            lbl.setText(hex.toUpperCase());
            swatch.putClientProperty("hexValue", hex.toUpperCase());
        } catch (Exception ignored) {}
    }

    private void addFormRow(JPanel panel, GridBagConstraints gbc, int row, String label, JTextField field) {
        gbc.gridx = 0; gbc.gridy = row; gbc.weightx = 0; gbc.gridwidth = 1;
        panel.add(new JLabel(label), gbc);
        gbc.gridx = 1; gbc.weightx = 1.0;
        panel.add(field, gbc);
    }

    private void addFilePickerRow(JPanel panel, GridBagConstraints gbc, int row, String label, JTextField field) {
        gbc.gridx = 0; gbc.gridy = row; gbc.weightx = 0; gbc.gridwidth = 1;
        panel.add(new JLabel(label), gbc);
        JPanel p = new JPanel(new BorderLayout(4, 0));
        p.setOpaque(false);
        p.add(field, BorderLayout.CENTER);
        JButton btnBrowse = makeSmallButton("Browse", "folder");
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

    private javax.swing.border.Border makeSectionBorder(String title) {
        return BorderFactory.createCompoundBorder(
            BorderFactory.createTitledBorder(
                BorderFactory.createLineBorder(UIManager.getColor("Component.borderColor"), 1, true),
                " " + title + " ",
                TitledBorder.LEFT, TitledBorder.TOP
            ),
            new EmptyBorder(6, 8, 6, 8)
        );
    }

    /** Caps maxWidth so BoxLayout children don't stretch horizontally. */
    private static void setMaxWidth(JComponent comp) {
        comp.setMaximumSize(new Dimension(Integer.MAX_VALUE, comp.getPreferredSize().height));
    }

    /** Converts UPPER_SNAKE to Title Case for display. */
    private static String formatFieldLabel(FindingField f) {
        String raw = f.name().toLowerCase().replace('_', ' ');
        return Character.toUpperCase(raw.charAt(0)) + raw.substring(1);
    }

    private static String formatSeverityLabel(Severity s) {
        String raw = s.name().toLowerCase().replace('_', ' ');
        return Character.toUpperCase(raw.charAt(0)) + raw.substring(1);
    }

    private static String blankToNull(String s) {
        return (s == null || s.trim().isEmpty()) ? null : s.trim();
    }
}
