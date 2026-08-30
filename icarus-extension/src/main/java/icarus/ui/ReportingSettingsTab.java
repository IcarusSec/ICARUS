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
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.io.File;
import java.nio.file.Files;
import java.util.*;
import java.util.List;

/**
 * Settings tab for managing Report Profiles.
 * Uses the same CardPanel / vertical-stack pattern as {@link SettingsPanel}
 * so it feels native inside Burp Suite.
 */
public class ReportingSettingsTab {

    private final MontoyaApi api;
    private final ModuleConfig config;
    private final ThemeHelper themeHelper;
    private final ReportProfileManager profileManager;
    private final JPanel containerPanel;

    // Profile selector
    private JComboBox<ReportProfile> comboProfiles;
    private JButton btnClone, btnDelete, btnExport, btnImport;
    private JButton btnSave, btnPreviewPdf, btnPreviewHtml;

    // Layout card
    private JRadioButton rdoCoverGradient, rdoCoverHeaderBand, rdoCoverNone;
    private JRadioButton rdoFindingCard, rdoFindingTabular;
    private DefaultTableModel sectionsTableModel;
    private JTable sectionsTable;

    // Theme card
    private JPanel colorPrimaryPanel, colorSecondaryPanel;
    private JComboBox<String> comboFontStack;
    private JSpinner spinFontSize;
    private final Map<Severity, JPanel> severityColorPanels = new EnumMap<>(Severity.class);

    // Branding card
    private JTextField txtCompanyLogo, txtClientLogo, txtDocTitle, txtClassification;
    private JTextField txtAuthor, txtReviewer, txtApprover, txtEnvironment;

    // Content card
    private JCheckBox chkIncludeEvidence, chkIncludeReq, chkIncludeRes, chkToc;
    private JSpinner spinMaxReqBytes, spinMaxResBytes;
    private final Map<FindingField, JCheckBox> fieldCheckboxes = new EnumMap<>(FindingField.class);

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
        themeHelper.applyTheme(containerPanel);

        JPanel content = buildContent();
        JScrollPane scroll = new JScrollPane(content);
        scroll.setBorder(null);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        themeHelper.applyTheme(scroll);

        containerPanel.add(scroll, BorderLayout.CENTER);
        refreshProfileList();
    }

    public Component getUiComponent() { return containerPanel; }

    public void save() {
        if (currentProfile != null && !currentProfile.builtIn()) saveCurrentProfileChanges();
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  Content — vertical stack of cards, same pattern as SettingsPanel
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    private JPanel buildContent() {
        // Inner column: the actual cards, stacked vertically
        JPanel column = new JPanel();
        column.setLayout(new BoxLayout(column, BoxLayout.Y_AXIS));
        column.setOpaque(false);
        // Force the column to a strict maximum width
        column.setMaximumSize(new Dimension(800, Integer.MAX_VALUE));

        column.add(buildProfileCard());
        column.add(Box.createVerticalStrut(16));
        column.add(buildLayoutCard());
        column.add(Box.createVerticalStrut(16));
        column.add(buildThemeCard());
        column.add(Box.createVerticalStrut(16));
        column.add(buildBrandingCard());
        column.add(Box.createVerticalStrut(16));
        column.add(buildContentPolicyCard());
        column.add(Box.createVerticalStrut(16));
        column.add(buildActionsCard());

        // Outer wrapper: perfectly centers the column horizontally using glue
        JPanel wrapper = new JPanel();
        wrapper.setLayout(new BoxLayout(wrapper, BoxLayout.X_AXIS));
        wrapper.setBackground(themeHelper.getBackgroundColor());
        wrapper.setBorder(new EmptyBorder(16, 16, 16, 16));

        wrapper.add(Box.createHorizontalGlue());
        wrapper.add(column);
        wrapper.add(Box.createHorizontalGlue());

        return wrapper;
    }

    // ── Card: Profile Selector ──────────────────────────────────────

    private JPanel buildProfileCard() {
        CardPanel card = new CardPanel("Report Profile", "file-text");

        comboProfiles = new JComboBox<>();
        comboProfiles.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean sel, boolean focus) {
                super.getListCellRendererComponent(list, value, index, sel, focus);
                if (value instanceof ReportProfile p) {
                    String tag = p.builtIn() ? " [Built-in]" : " [Custom]";
                    setText(p.name() + tag);
                    setIcon(EvidenceUiHelpers.createIcon(p.builtIn() ? "lock" : "pencil"));
                }
                return this;
            }
        });
        comboProfiles.addActionListener(e -> {
            if (isUpdatingUi) return;
            ReportProfile sel = (ReportProfile) comboProfiles.getSelectedItem();
            if (sel != null) { profileManager.setActive(sel.id()); loadProfileIntoForm(sel); }
        });
        card.addFormRow(comboProfiles);

        JPanel btns = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        btns.setOpaque(false);
        btnClone  = btn("Clone",  "copy",     e -> onCloneProfile());
        btnExport = btn("Export", "download",  e -> onExportProfile());
        btnImport = btn("Import", "folder",    e -> onImportProfile());
        btnDelete = btn("Delete", "trash",     e -> onDeleteProfile());
        btns.add(btnClone); btns.add(btnExport); btns.add(btnImport); btns.add(btnDelete);
        card.addFormRow(btns);

        return card;
    }

    // ── Card: Layout & Sections ─────────────────────────────────────

    private JPanel buildLayoutCard() {
        CardPanel card = new CardPanel("Layout & Sections", "square");

        // Cover style
        card.addFormRow(label("Cover Page (PDF):"));
        ButtonGroup grpCover = new ButtonGroup();
        rdoCoverGradient   = radio("Gradient Hero (Modern)");
        rdoCoverHeaderBand = radio("Header Band (Classic)");
        rdoCoverNone       = radio("None (Direct Flow)");
        for (JRadioButton r : List.of(rdoCoverGradient, rdoCoverHeaderBand, rdoCoverNone)) {
            grpCover.add(r);
            card.addFormRow(indent(r));
        }

        card.addFormRow(Box.createVerticalStrut(6));

        // Finding style
        card.addFormRow(label("Finding Card Layout:"));
        ButtonGroup grpFinding = new ButtonGroup();
        rdoFindingCard   = radio("Elevated Card (Modern)");
        rdoFindingTabular = radio("Tabular Grid (Classic)");
        for (JRadioButton r : List.of(rdoFindingCard, rdoFindingTabular)) {
            grpFinding.add(r);
            card.addFormRow(indent(r));
        }

        card.addFormRow(Box.createVerticalStrut(6));

        // Sections table — fully editable: toggle, rename, add, remove, reorder
        card.addFormRow(label("Report Sections Flow:"));
        sectionsTableModel = new DefaultTableModel(new Object[]{"On", "#", "Section", "Req"}, 0) {
            @Override public Class<?> getColumnClass(int c) {
                return (c == 0 || c == 3) ? Boolean.class : (c == 1 ? Integer.class : String.class);
            }
            @Override public boolean isCellEditable(int r, int c) {
                if (currentProfile != null && currentProfile.builtIn()) return false;
                if (c == 0) { // "On" toggle — can't disable required sections
                    Boolean req = (Boolean) getValueAt(r, 3);
                    return req == null || !req;
                }
                if (c == 2) return true; // "Section" name is always editable
                return false;
            }
        };
        sectionsTable = new JTable(sectionsTableModel);
        sectionsTable.setRowHeight(24);
        sectionsTable.getColumnModel().getColumn(0).setMaxWidth(40);
        sectionsTable.getColumnModel().getColumn(1).setMaxWidth(30);
        sectionsTable.getColumnModel().getColumn(3).setMaxWidth(40);
        sectionsTable.setFillsViewportHeight(true);

        JScrollPane tableScroll = new JScrollPane(sectionsTable);
        tableScroll.setPreferredSize(new Dimension(0, 170));

        JPanel tableRow = new JPanel(new BorderLayout(6, 0));
        tableRow.setOpaque(false);
        tableRow.add(tableScroll, BorderLayout.CENTER);

        JPanel sideBtns = new JPanel();
        sideBtns.setLayout(new BoxLayout(sideBtns, BoxLayout.Y_AXIS));
        sideBtns.setOpaque(false);
        JButton btnUp     = btn("Up",     "chevron-up",   e -> moveSectionRow(-1));
        JButton btnDown   = btn("Down",   "chevron-down", e -> moveSectionRow(1));
        JButton btnAddSec = btn("Add",    "plus",         e -> addSection());
        JButton btnRmSec  = btn("Remove", "trash",        e -> removeSection());
        for (JButton b : List.of(btnUp, btnDown, btnAddSec, btnRmSec)) {
            b.setAlignmentX(Component.LEFT_ALIGNMENT);
            b.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
            sideBtns.add(b);
            sideBtns.add(Box.createVerticalStrut(3));
        }
        tableRow.add(sideBtns, BorderLayout.EAST);
        card.addFormRow(tableRow);

        return card;
    }

    // ── Card: Colors & Theme ────────────────────────────────────────

    private JPanel buildThemeCard() {
        CardPanel card = new CardPanel("Colors & Theme", "aperture");

        JPanel form = new JPanel(new GridBagLayout());
        form.setOpaque(false);
        GridBagConstraints g = gbc();
        int row = 0;

        colorPrimaryPanel = createColorSwatch();
        addLabeledRow(form, g, row++, "Primary Accent:", colorPrimaryPanel);

        colorSecondaryPanel = createColorSwatch();
        addLabeledRow(form, g, row++, "Secondary Accent:", colorSecondaryPanel);

        comboFontStack = new JComboBox<>(new String[]{"Helvetica", "Times-Roman", "Courier"});
        addLabeledRow(form, g, row++, "Font Family:", comboFontStack);

        spinFontSize = new JSpinner(new SpinnerNumberModel(10, 8, 14, 1));
        addLabeledRow(form, g, row++, "Font Size (PDF):", spinFontSize);

        card.addFormRow(form);

        // Severity badges — 2-column grid
        card.addFormRow(Box.createVerticalStrut(4));
        card.addFormRow(label("Severity Badge Colors:"));

        JPanel sevGrid = new JPanel(new GridBagLayout());
        sevGrid.setOpaque(false);
        Severity[] sevs = {Severity.CRITICAL, Severity.HIGH, Severity.MEDIUM, Severity.LOW,
                           Severity.INFO, Severity.FIXED, Severity.NOT_FIXED};
        for (int i = 0; i < sevs.length; i++) {
            int col = i % 2;   // 0 or 1
            int row2 = i / 2;

            GridBagConstraints gl = new GridBagConstraints();
            gl.gridx = col * 2; gl.gridy = row2;
            gl.weightx = 0; gl.anchor = GridBagConstraints.WEST;
            gl.insets = new Insets(3, col == 0 ? 4 : 16, 3, 4);
            gl.fill = GridBagConstraints.NONE;
            sevGrid.add(new JLabel(titleCase(sevs[i].name()) + ":"), gl);

            GridBagConstraints gs = new GridBagConstraints();
            gs.gridx = col * 2 + 1; gs.gridy = row2;
            gs.weightx = 0.5; gs.anchor = GridBagConstraints.WEST;
            gs.insets = new Insets(3, 0, 3, 4);
            gs.fill = GridBagConstraints.NONE;
            JPanel swatch = createColorSwatch();
            severityColorPanels.put(sevs[i], swatch);
            sevGrid.add(swatch, gs);
        }
        card.addFormRow(sevGrid);

        return card;
    }

    // ── Card: Branding & Metadata ───────────────────────────────────

    private JPanel buildBrandingCard() {
        CardPanel card = new CardPanel("Branding & Metadata", "shield");

        txtDocTitle       = new JTextField();
        txtClassification = new JTextField("Confidential");
        txtAuthor         = new JTextField();
        txtReviewer       = new JTextField();
        txtApprover       = new JTextField();
        txtEnvironment    = new JTextField();
        txtCompanyLogo    = new JTextField();
        txtClientLogo     = new JTextField();

        JPanel form = new JPanel(new GridBagLayout());
        form.setOpaque(false);
        GridBagConstraints g = gbc();
        int row = 0;
        addLabeledRow(form, g, row++, "Document Title:", txtDocTitle);
        addLabeledRow(form, g, row++, "Classification:", txtClassification);
        addLabeledRow(form, g, row++, "Author:",         txtAuthor);
        addLabeledRow(form, g, row++, "Reviewer:",       txtReviewer);
        addLabeledRow(form, g, row++, "Approver:",       txtApprover);
        addLabeledRow(form, g, row++, "Environment:",    txtEnvironment);
        addFileRow(form, g, row++, "Company Logo:", txtCompanyLogo);
        addFileRow(form, g, row++, "Client Logo:",  txtClientLogo);
        card.addFormRow(form);

        return card;
    }

    // ── Card: Content & Policy ──────────────────────────────────────

    private JPanel buildContentPolicyCard() {
        CardPanel card = new CardPanel("Content & Policy", "adjustments-horizontal");

        chkIncludeEvidence = new JCheckBox("Include Evidence Screenshots", true);
        chkToc             = new JCheckBox("Generate Table of Contents", true);
        chkIncludeReq      = new JCheckBox("Include HTTP Request", true);
        chkIncludeRes      = new JCheckBox("Include HTTP Response", true);
        spinMaxReqBytes    = new JSpinner(new SpinnerNumberModel(4096, 512, 65536, 512));
        spinMaxResBytes    = new JSpinner(new SpinnerNumberModel(4096, 512, 65536, 512));

        // Toggles in 2-col grid
        JPanel toggles = new JPanel(new GridBagLayout());
        toggles.setOpaque(false);
        GridBagConstraints tg = gbc();
        tg.gridx = 0; tg.gridy = 0; tg.gridwidth = 2;
        toggles.add(chkIncludeEvidence, tg);
        tg.gridy = 1;
        toggles.add(chkToc, tg);

        tg.gridwidth = 1; tg.gridy = 2; tg.gridx = 0; tg.weightx = 0;
        toggles.add(chkIncludeReq, tg);
        tg.gridx = 1; tg.weightx = 1.0;
        toggles.add(inlineLabel("Max bytes:", spinMaxReqBytes), tg);

        tg.gridy = 3; tg.gridx = 0; tg.weightx = 0;
        toggles.add(chkIncludeRes, tg);
        tg.gridx = 1; tg.weightx = 1.0;
        toggles.add(inlineLabel("Max bytes:", spinMaxResBytes), tg);
        card.addFormRow(toggles);

        // Finding fields — 2-col grid
        card.addFormRow(Box.createVerticalStrut(4));
        card.addFormRow(label("Finding Fields Visibility:"));

        JPanel fGrid = new JPanel(new GridBagLayout());
        fGrid.setOpaque(false);
        GridBagConstraints fg = gbc();
        FindingField[] fields = FindingField.values();
        for (int i = 0; i < fields.length; i++) {
            fg.gridx = i % 2;
            fg.gridy = i / 2;
            fg.weightx = 1.0;
            JCheckBox chk = new JCheckBox(titleCase(fields[i].name()), true);
            fieldCheckboxes.put(fields[i], chk);
            fGrid.add(chk, fg);
        }
        card.addFormRow(fGrid);

        return card;
    }

    // ── Card: Actions (Preview & Save) ──────────────────────────────

    private JPanel buildActionsCard() {
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        bar.setOpaque(false);
        bar.setAlignmentX(Component.LEFT_ALIGNMENT);
        bar.setMaximumSize(new Dimension(Integer.MAX_VALUE, 38));

        btnPreviewPdf  = btn("Preview PDF",  "file-text",     e -> runPreview(PreviewService.Format.PDF));
        btnPreviewHtml = btn("Preview HTML", "external-link", e -> runPreview(PreviewService.Format.HTML));

        btnSave = new JButton("Save Profile");
        btnSave.setIcon(EvidenceUiHelpers.createIcon("check"));
        btnSave.setFocusPainted(false);
        btnSave.putClientProperty("FlatLaf.style", "background: #FF6633; foreground: #FFFFFF; font: bold $defaultFont;");
        btnSave.addActionListener(e -> saveCurrentProfileChanges());

        bar.add(btnPreviewPdf);
        bar.add(btnPreviewHtml);
        bar.add(Box.createHorizontalStrut(8));
        bar.add(btnSave);
        return bar;
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  Data: load / save
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    private void refreshProfileList() {
        isUpdatingUi = true;
        try {
            comboProfiles.removeAllItems();
            for (ReportProfile p : profileManager.list()) comboProfiles.addItem(p);
            ReportProfile active = profileManager.active();
            comboProfiles.setSelectedItem(active);
            loadProfileIntoForm(active);
        } finally { isUpdatingUi = false; }
    }

    private void loadProfileIntoForm(ReportProfile p) {
        if (p == null) return;
        currentProfile = p;
        boolean editable = !p.builtIn();
        btnSave.setEnabled(editable);
        btnDelete.setEnabled(editable);

        // Cover
        switch (p.coverRenderer()) {
            case GRADIENT_HERO -> rdoCoverGradient.setSelected(true);
            case HEADER_BAND   -> rdoCoverHeaderBand.setSelected(true);
            default            -> rdoCoverNone.setSelected(true);
        }
        // Finding
        if (p.findingRenderer() == FindingRendererId.TABULAR) rdoFindingTabular.setSelected(true);
        else rdoFindingCard.setSelected(true);

        // Sections
        sectionsTableModel.setRowCount(0);
        for (SectionNode n : p.sections().nodes())
            sectionsTableModel.addRow(new Object[]{n.enabled(), n.order(), n.id(), n.required()});

        // Colors
        setSwatchHex(colorPrimaryPanel,   p.pdfTheme().primaryHex());
        setSwatchHex(colorSecondaryPanel,  p.pdfTheme().secondaryHex());
        comboFontStack.setSelectedItem(p.pdfTheme().fontStack());
        spinFontSize.setValue(p.pdfTheme().baseFontSize());
        for (var e : p.pdfTheme().severityHex().entrySet()) {
            JPanel sw = severityColorPanels.get(e.getKey());
            if (sw != null) setSwatchHex(sw, e.getValue());
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
        for (var e : fieldCheckboxes.entrySet())
            e.getValue().setSelected(c.findingFields().contains(e.getKey()));
    }

    private void autoCloneProfile() {
        if (currentProfile == null || !currentProfile.builtIn()) return;
        int suffix = 1;
        String base = "CUSTOM ";
        String newName;
        while (true) {
            newName = base + suffix;
            String finalName = newName;
            if (profileManager.list().stream().noneMatch(p -> p.name().equalsIgnoreCase(finalName))) {
                break;
            }
            suffix++;
        }
        ReportProfile cloned = profileManager.clone(currentProfile.id(), newName);
        profileManager.setActive(cloned.id());
        refreshProfileList(); // this switches the UI to the newly cloned profile, enabling everything
        showToast("Auto-cloned to '" + cloned.name() + "' for editing");
    }

    private void saveCurrentProfileChanges() {
        if (currentProfile == null || currentProfile.builtIn()) return;

        CoverRendererId cover = rdoCoverGradient.isSelected() ? CoverRendererId.GRADIENT_HERO
            : rdoCoverHeaderBand.isSelected() ? CoverRendererId.HEADER_BAND : CoverRendererId.NONE;
        FindingRendererId finding = rdoFindingTabular.isSelected() ? FindingRendererId.TABULAR : FindingRendererId.ELEVATED_CARD;

        List<SectionNode> nodes = new ArrayList<>();
        for (int i = 0; i < sectionsTableModel.getRowCount(); i++) {
            nodes.add(SectionNode.of(
                (String) sectionsTableModel.getValueAt(i, 2),
                (Boolean) sectionsTableModel.getValueAt(i, 0),
                i + 1,
                (Boolean) sectionsTableModel.getValueAt(i, 3)));
        }

        String primary   = (String) colorPrimaryPanel.getClientProperty("hexValue");
        String secondary = (String) colorSecondaryPanel.getClientProperty("hexValue");
        String font      = (String) comboFontStack.getSelectedItem();
        int fontSize     = (Integer) spinFontSize.getValue();

        Map<Severity, String> sevHex = new EnumMap<>(Severity.class);
        severityColorPanels.forEach((s, panel) -> {
            String v = (String) panel.getClientProperty("hexValue");
            if (v != null) sevHex.put(s, v);
        });

        PdfTheme pdfTheme = new PdfTheme(primary, secondary, "#202020", primary, "#F7F7F7", sevHex, font, fontSize, PageBox.a4Default());
        HtmlTheme htmlTheme = new HtmlTheme(primary, secondary, "#FFFFFF", "#F7F7F7", "#1A1A1A", "#DDDDDD", sevHex,
            "-apple-system, BlinkMacSystemFont, sans-serif", false);

        BrandingConfig branding = new BrandingConfig(
            blankToNull(txtCompanyLogo.getText()), blankToNull(txtClientLogo.getText()),
            txtAuthor.getText().trim(), txtReviewer.getText().trim(), txtApprover.getText().trim(),
            txtClassification.getText().trim(), txtEnvironment.getText().trim(), txtDocTitle.getText().trim(),
            "", "", "", "", "", "");

        List<FindingField> fields = new ArrayList<>();
        fieldCheckboxes.forEach((f, chk) -> { if (chk.isSelected()) fields.add(f); });

        ContentPolicy content = new ContentPolicy(
            chkIncludeEvidence.isSelected(), chkIncludeReq.isSelected(), chkIncludeRes.isSelected(),
            (Integer) spinMaxReqBytes.getValue(), (Integer) spinMaxResBytes.getValue(),
            fields, CweMode.HARDCODED_CATALOG, Collections.emptyList(), chkToc.isSelected());

        ReportProfile updated = new ReportProfile(
            ReportProfile.CURRENT_SCHEMA_VERSION, currentProfile.id(), currentProfile.name(),
            currentProfile.locale(), false, currentProfile.basedOnId(),
            cover, finding, new SectionGraph(nodes), branding, content, pdfTheme, htmlTheme);

        profileManager.saveUserProfile(updated);
        showToast("Profile '" + updated.name() + "' saved.");
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  Actions
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    private void showToast(String msg) {
        Frame f = api != null ? api.userInterface().swingUtils().suiteFrame() : null;
        if (f == null) { Window w = SwingUtilities.getWindowAncestor(containerPanel); if (w instanceof Frame fr) f = fr; }
        if (f != null) ToastNotification.show(f, msg);
    }

    private void onCloneProfile() {
        if (currentProfile == null) return;
        String name = JOptionPane.showInputDialog(containerPanel, "Name for new profile:", currentProfile.name() + " (Custom)");
        if (name != null && !name.isBlank()) {
            ReportProfile cloned = profileManager.clone(currentProfile.id(), name.trim());
            profileManager.setActive(cloned.id());
            refreshProfileList();
            showToast("Created '" + cloned.name() + "'");
        }
    }

    private void onExportProfile() {
        if (currentProfile == null) return;
        JFileChooser fc = new JFileChooser();
        fc.setSelectedFile(new File(currentProfile.name().toLowerCase().replace(" ", "-") + ".json"));
        if (fc.showSaveDialog(containerPanel) == JFileChooser.APPROVE_OPTION) {
            try {
                Files.writeString(fc.getSelectedFile().toPath(), profileManager.exportJson(currentProfile.id()));
                showToast("Exported.");
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(containerPanel, "Export failed: " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    private void onImportProfile() {
        JFileChooser fc = new JFileChooser();
        if (fc.showOpenDialog(containerPanel) == JFileChooser.APPROVE_OPTION) {
            try {
                ReportProfile p = profileManager.importJson(Files.readString(fc.getSelectedFile().toPath()));
                profileManager.setActive(p.id());
                refreshProfileList();
                showToast("Imported '" + p.name() + "'");
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(containerPanel, "Import failed: " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    private void onDeleteProfile() {
        if (currentProfile == null || currentProfile.builtIn()) return;
        if (JOptionPane.showConfirmDialog(containerPanel, "Delete '" + currentProfile.name() + "'?",
                "Confirm", JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION) {
            profileManager.deleteUserProfile(currentProfile.id());
            refreshProfileList();
            showToast("Deleted.");
        }
    }

    private void runPreview(PreviewService.Format fmt) {
        if (currentProfile == null) return;
        new SwingWorker<File, Void>() {
            @Override protected File doInBackground() throws Exception {
                return PreviewService.generatePreviewFile(currentProfile, fmt);
            }
            @Override protected void done() {
                try {
                    File f = get();
                    if (f != null && Desktop.isDesktopSupported()) Desktop.getDesktop().open(f);
                } catch (Exception ex) {
                    JOptionPane.showMessageDialog(containerPanel, "Preview failed: " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
                }
            }
        }.execute();
    }

    private void moveSectionRow(int delta) {
        int i = sectionsTable.getSelectedRow();
        if (i < 0) return;
        int t = i + delta;
        if (t < 0 || t >= sectionsTableModel.getRowCount()) return;
        
        if (currentProfile != null && currentProfile.builtIn()) {
            autoCloneProfile();
            // Restore selection after model reload
            sectionsTable.setRowSelectionInterval(i, i);
        }
        
        sectionsTableModel.moveRow(i, i, t);
        sectionsTable.setRowSelectionInterval(t, t);
    }

    private void addSection() {
        String name = JOptionPane.showInputDialog(containerPanel, "Section identifier (e.g. CUSTOM_NOTES):");
        if (name != null && !name.isBlank()) {
            if (currentProfile != null && currentProfile.builtIn()) {
                autoCloneProfile();
            }
            int nextOrder = sectionsTableModel.getRowCount() + 1;
            sectionsTableModel.addRow(new Object[]{true, nextOrder, name.trim().toUpperCase().replace(' ', '_'), false});
        }
    }

    private void removeSection() {
        int idx = sectionsTable.getSelectedRow();
        if (idx < 0) return;
        Boolean required = (Boolean) sectionsTableModel.getValueAt(idx, 3);
        if (required != null && required) {
            JOptionPane.showMessageDialog(containerPanel, "Cannot remove a required section.", "Info", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        
        if (currentProfile != null && currentProfile.builtIn()) {
            autoCloneProfile();
        }
        
        sectionsTableModel.removeRow(idx);
        // Renumber
        for (int i = 0; i < sectionsTableModel.getRowCount(); i++) {
            sectionsTableModel.setValueAt(i + 1, i, 1);
        }
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  UI Helpers
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /** Reusable card panel matching SettingsPanel's style. */
    private class CardPanel extends JPanel {
        CardPanel(String title, String iconName) {
            setLayout(new GridBagLayout());
            setBorder(new EmptyBorder(12, 14, 12, 14));
            setBackground(themeHelper.getContainerBackgroundColor());
            putClientProperty("FlatLaf.style", "arc: 12; borderWidth: 1; borderColor: $Component.borderColor");

            JPanel header = new JPanel(new BorderLayout(0, 8));
            header.setOpaque(false);
            JLabel lbl = new JLabel(title);
            lbl.setFont(lbl.getFont().deriveFont(Font.BOLD, 14f));
            Icon ico = EvidenceUiHelpers.createIcon(iconName);
            if (ico != null) { lbl.setIcon(ico); lbl.setIconTextGap(8); }
            header.add(lbl, BorderLayout.NORTH);
            header.add(new JSeparator(), BorderLayout.SOUTH);

            GridBagConstraints g = new GridBagConstraints();
            g.gridx = 0; g.gridy = 0;
            g.weightx = 1.0; g.fill = GridBagConstraints.HORIZONTAL;
            g.insets = new Insets(0, 0, 10, 0);
            add(header, g);
        }

        @Override
        public Dimension getMaximumSize() {
            return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
        }

        void addFormRow(Component comp) {
            GridBagConstraints g = new GridBagConstraints();
            g.gridx = 0; g.gridy = getComponentCount();
            g.weightx = 1.0; g.fill = GridBagConstraints.HORIZONTAL;
            g.insets = new Insets(0, 0, 6, 0);
            g.anchor = GridBagConstraints.NORTHWEST;
            add(comp, g);
        }
    }

    private JButton btn(String text, String icon, java.awt.event.ActionListener al) {
        JButton b = new JButton(text);
        Icon ic = EvidenceUiHelpers.createIcon(icon);
        if (ic != null) b.setIcon(ic);
        b.setFocusPainted(false);
        b.setMargin(new Insets(4, 10, 4, 10));
        b.addActionListener(al);
        return b;
    }

    private static JRadioButton radio(String text) {
        JRadioButton r = new JRadioButton(text);
        r.setOpaque(false);
        return r;
    }

    private static JLabel label(String text) {
        JLabel l = new JLabel(text);
        l.setFont(l.getFont().deriveFont(Font.BOLD));
        return l;
    }

    /** Indent a component by wrapping it in a panel with left padding. */
    private static JPanel indent(JComponent comp) {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        p.setOpaque(false);
        p.setBorder(new EmptyBorder(0, 20, 0, 0));
        p.add(comp);
        return p;
    }

    private static JPanel inlineLabel(String text, JComponent comp) {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        p.setOpaque(false);
        p.add(new JLabel(text));
        p.add(comp);
        return p;
    }

    private static GridBagConstraints gbc() {
        GridBagConstraints g = new GridBagConstraints();
        g.insets = new Insets(3, 4, 3, 4);
        g.fill = GridBagConstraints.HORIZONTAL;
        g.anchor = GridBagConstraints.WEST;
        return g;
    }

    private static void addLabeledRow(JPanel panel, GridBagConstraints g, int row, String label, JComponent field) {
        g.gridx = 0; g.gridy = row; g.weightx = 0; g.gridwidth = 1;
        panel.add(new JLabel(label), g);
        g.gridx = 1; g.weightx = 1.0;
        panel.add(field, g);
    }

    private void addFileRow(JPanel panel, GridBagConstraints g, int row, String label, JTextField field) {
        g.gridx = 0; g.gridy = row; g.weightx = 0; g.gridwidth = 1;
        panel.add(new JLabel(label), g);
        JPanel p = new JPanel(new BorderLayout(4, 0));
        p.setOpaque(false);
        p.add(field, BorderLayout.CENTER);
        JButton browse = btn("Browse", "folder", e -> {
            JFileChooser fc = new JFileChooser();
            if (fc.showOpenDialog(panel) == JFileChooser.APPROVE_OPTION) field.setText(fc.getSelectedFile().getAbsolutePath());
        });
        p.add(browse, BorderLayout.EAST);
        g.gridx = 1; g.weightx = 1.0;
        panel.add(p, g);
    }

    /** Color swatch: [■ button] [#HEX label]. Click button to pick. */
    private JPanel createColorSwatch() {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        panel.setOpaque(false);

        JButton btn = new JButton();
        btn.setPreferredSize(new Dimension(22, 22));
        btn.setMinimumSize(new Dimension(22, 22));
        btn.setMaximumSize(new Dimension(22, 22));
        btn.setOpaque(true);
        btn.setBorderPainted(true);
        btn.setFocusPainted(false);
        btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

        JLabel hex = new JLabel("#------");
        hex.setFont(hex.getFont().deriveFont(Font.PLAIN, 11f));

        btn.addActionListener(e -> {
            Color c = JColorChooser.showDialog(panel, "Pick Color", btn.getBackground());
            if (c != null) {
                if (currentProfile != null && currentProfile.builtIn()) {
                    autoCloneProfile();
                }
                btn.setBackground(c);
                String h = String.format("#%02X%02X%02X", c.getRed(), c.getGreen(), c.getBlue());
                hex.setText(h);
                panel.putClientProperty("hexValue", h);
            }
        });

        panel.add(btn);
        panel.add(hex);
        return panel;
    }

    private static void setSwatchHex(JPanel swatch, String hex) {
        if (swatch == null || hex == null || !hex.matches("^#[A-Fa-f0-9]{3,6}$")) return;
        try {
            ((JButton) swatch.getComponent(0)).setBackground(Color.decode(hex));
            ((JLabel) swatch.getComponent(1)).setText(hex.toUpperCase());
            swatch.putClientProperty("hexValue", hex.toUpperCase());
        } catch (Exception ignored) {}
    }

    private static String titleCase(String s) {
        return Character.toUpperCase(s.charAt(0)) + s.substring(1).toLowerCase().replace('_', ' ');
    }

    private static String blankToNull(String s) {
        return (s == null || s.trim().isEmpty()) ? null : s.trim();
    }
}
