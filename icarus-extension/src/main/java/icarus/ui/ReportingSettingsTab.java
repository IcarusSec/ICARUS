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
import com.icarus.ui.reportprofile.layout.*;
import com.icarus.ui.reportprofile.sections.*;
import com.icarus.ui.reportprofile.components.*;

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

    private LayoutSectionPanel layoutPanel;
    private ColorsThemeSectionPanel themePanel;
    private BrandingSectionPanel brandingPanel;
    private ContentSectionPanel contentPanel;
    private SectionListPanel sectionListPanel;
    private DetailPane detailPane;
    private SectionFlowPanel flowPanel;
    private ToolbarPanel toolbarPanel;
    
    // Theme card components (needed for ColorsThemeSectionPanel)
    private JPanel colorPrimaryPanel, colorSecondaryPanel;
    private JComboBox<String> comboFontStack;
    private JSpinner spinFontSize;
    private final Map<Severity, JPanel> severityColorPanels = new EnumMap<>(Severity.class);

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
        ResponsiveContainer responsiveContainer = new ResponsiveContainer();

        // Initialize Toolbar components
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

        btnClone  = btn("Clone",  "copy",     e -> onCloneProfile());
        btnExport = btn("Export", "download",  e -> onExportProfile());
        btnImport = btn("Import", "folder",    e -> onImportProfile());
        btnDelete = btn("Delete", "trash",     e -> onDeleteProfile());
        btnPreviewPdf  = btn("Preview PDF",  "file-text",     e -> runPreview(PreviewService.Format.PDF));
        btnPreviewHtml = btn("Preview HTML", "external-link", e -> runPreview(PreviewService.Format.HTML));
        btnSave = new JButton("Save Profile");
        btnSave.setIcon(EvidenceUiHelpers.createIcon("check"));
        btnSave.setFocusPainted(false);
        btnSave.putClientProperty("FlatLaf.style", "background: #FF6633; foreground: #FFFFFF; font: bold $defaultFont;");
        btnSave.addActionListener(e -> saveCurrentProfileChanges());

        toolbarPanel = new ToolbarPanel(comboProfiles, btnClone, btnExport, btnImport, btnDelete, btnPreviewPdf, btnPreviewHtml, btnSave);
        responsiveContainer.registerSection(wrapInCard("Report Profile & Actions", "file-text", toolbarPanel));

        // Layout
        layoutPanel = new LayoutSectionPanel();
        responsiveContainer.registerSection(wrapInCard("Layout", "menu-2", layoutPanel));

        // Sections
        sectionListPanel = new SectionListPanel();
        detailPane = new DetailPane();
        bindSectionsFlow();
        
        // Setup side buttons for sectionListPanel
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
        JPanel listWrapper = new JPanel(new BorderLayout());
        listWrapper.setOpaque(false);
        listWrapper.add(sectionListPanel.component(), BorderLayout.CENTER);
        listWrapper.add(sideBtns, BorderLayout.EAST);
        
        flowPanel = new SectionFlowPanel(listWrapper, (JComponent) detailPane.component());
        responsiveContainer.registerSection(wrapInCard("Sections Flow", "list", flowPanel));

        // Theme
        colorPrimaryPanel = createColorSwatch();
        colorSecondaryPanel = createColorSwatch();
        comboFontStack = new JComboBox<>(new String[]{"Helvetica", "Times-Roman", "Courier"});
        spinFontSize = new JSpinner(new SpinnerNumberModel(10, 8, 14, 1));
        Severity[] sevs = {Severity.CRITICAL, Severity.HIGH, Severity.MEDIUM, Severity.LOW,
                           Severity.INFO, Severity.FIXED, Severity.NOT_FIXED};
        for (Severity s : sevs) {
            severityColorPanels.put(s, createColorSwatch());
        }
        themePanel = new ColorsThemeSectionPanel(colorPrimaryPanel, colorSecondaryPanel, comboFontStack, spinFontSize, severityColorPanels);
        responsiveContainer.registerSection(wrapInCard("Colors & Theme", "aperture", themePanel));

        // Branding
        brandingPanel = new BrandingSectionPanel();
        responsiveContainer.registerSection(wrapInCard("Branding & Metadata", "shield", brandingPanel));

        // Content
        contentPanel = new ContentSectionPanel();
        responsiveContainer.registerSection(wrapInCard("Content & Policy", "adjustments-horizontal", contentPanel));

        // Add some padding around the container
        JPanel wrapper = new JPanel(new BorderLayout());
        wrapper.setBorder(new EmptyBorder(16, 16, 16, 16));
        wrapper.setOpaque(false);
        wrapper.add(responsiveContainer, BorderLayout.CENTER);
        return wrapper;
    }
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  Data: load / save
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    
    private void bindSectionsFlow() {
        sectionListPanel.list.addListSelectionListener(e -> {
            if (e.getValueIsAdjusting() || isUpdatingUi) return;
            icarus.report.model.SectionNode node = sectionListPanel.list.getSelectedValue();
            if (node != null) {
                isUpdatingUi = true;
                detailPane.titleField.setText(node.params().getOrDefault("title", titleCase(node.id())));
                detailPane.titleField.setCaretPosition(0);
                detailPane.bodyWell.setText(node.params().getOrDefault("content", ""));
                
                boolean disable = "EXECUTIVE_SUMMARY".equals(node.id()) && (currentProfile != null && currentProfile.builtIn());
                detailPane.titleField.setEnabled(!disable);
                detailPane.bodyWell.setEnabled(!disable);
                isUpdatingUi = false;
            } else {
                isUpdatingUi = true;
                detailPane.titleField.setText("");
                detailPane.bodyWell.setText("");
                detailPane.titleField.setEnabled(false);
                detailPane.bodyWell.setEnabled(false);
                isUpdatingUi = false;
            }
        });

        javax.swing.event.DocumentListener dl = new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e) { update(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e) { update(); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { update(); }
            private void update() {
                if (isUpdatingUi) return;
                int idx = sectionListPanel.list.getSelectedIndex();
                if (idx >= 0) {
                    if (currentProfile != null && currentProfile.builtIn()) {
                        autoCloneProfile();
                    } else {
                        icarus.report.model.SectionNode node = sectionListPanel.model.getElementAt(idx);
                        java.util.Map<String, String> newParams = new java.util.HashMap<>(node.params());
                        newParams.put("title", detailPane.titleField.getText());
                        newParams.put("content", detailPane.bodyWell.getText());
                        icarus.report.model.SectionNode updated = new icarus.report.model.SectionNode(node.id(), node.enabled(), node.order(), node.required(), node.rendererKey(), newParams);
                        sectionListPanel.model.setElementAt(updated, idx);
                    }
                }
            }
        };
        detailPane.titleField.getDocument().addDocumentListener(dl);
        detailPane.bodyWell.getDocument().addDocumentListener(dl);
    }

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

        // Cover & Finding
        layoutPanel.setCover(WireframeKind.valueOf(p.coverRenderer().name()));
        layoutPanel.setFinding(p.findingRenderer() == FindingRendererId.TABULAR ? WireframeKind.TABULAR_GRID : WireframeKind.ELEVATED_CARD);

        // Sections
        sectionListPanel.model.clear();
        for (SectionNode n : p.sections().nodes()) {
            sectionListPanel.model.addElement(n);
        }
        // Select the first section once isUpdatingUi has cleared, so the editor
        // opens populated instead of blank.
        SwingUtilities.invokeLater(() -> {
            if (!sectionListPanel.model.isEmpty() && sectionListPanel.list.getSelectedIndex() < 0) {
                sectionListPanel.list.setSelectedIndex(0);
            }
        });

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
        brandingPanel.txtDocTitle.setText(b != null ? b.documentTitle() : "");
        brandingPanel.txtClassification.setText(b != null ? b.classification() : "");
        brandingPanel.txtAuthor.setText(b != null ? b.author() : "");
        brandingPanel.txtReviewer.setText(b != null ? b.reviewer() : "");
        brandingPanel.txtApprover.setText(b != null ? b.approver() : "");
        brandingPanel.txtEnvironment.setText(b != null ? b.environment() : "");
        brandingPanel.txtCompanyLogo.setText(b != null && b.companyLogoPath() != null ? b.companyLogoPath() : "");
        brandingPanel.txtClientLogo.setText(b != null && b.clientLogoPath() != null ? b.clientLogoPath() : "");

        // Content
        ContentPolicy c = p.content();
        contentPanel.chkIncludeEvidence.setOn(c.includeEvidence());
        contentPanel.chkIncludeReq.setOn(c.includeHttpRequest());
        contentPanel.spinMaxReqBytes.setValue(c.maxRequestBytes());
        contentPanel.chkIncludeRes.setOn(c.includeHttpResponse());
        contentPanel.spinMaxResBytes.setValue(c.maxResponseBytes());
        contentPanel.chkToc.setOn(c.includeTocBookmarks());
        for (var e : contentPanel.fieldCheckboxes.entrySet())
            e.getValue().setOn(c.findingFields().contains(e.getKey()));
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

        CoverRendererId cover = CoverRendererId.valueOf(layoutPanel.getCover().name());
        FindingRendererId finding = layoutPanel.getFinding() == WireframeKind.TABULAR_GRID ? FindingRendererId.TABULAR : FindingRendererId.ELEVATED_CARD;

        List<SectionNode> nodes = new ArrayList<>();
        for (int i = 0; i < sectionListPanel.model.getSize(); i++) {
            SectionNode node = sectionListPanel.model.getElementAt(i);
            nodes.add(new SectionNode(node.id(), node.enabled(), i + 1, node.required(), node.rendererKey(), node.params()));
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
            blankToNull(brandingPanel.txtCompanyLogo.getText()), blankToNull(brandingPanel.txtClientLogo.getText()),
            brandingPanel.txtAuthor.getText().trim(), brandingPanel.txtReviewer.getText().trim(), brandingPanel.txtApprover.getText().trim(),
            brandingPanel.txtClassification.getText().trim(), brandingPanel.txtEnvironment.getText().trim(), brandingPanel.txtDocTitle.getText().trim(),
            "", "", "", "", "", "");

        List<FindingField> fields = new ArrayList<>();
        contentPanel.fieldCheckboxes.forEach((f, chk) -> { if (chk.isOn()) fields.add(f); });

        ContentPolicy contentPolicy = new ContentPolicy(
            contentPanel.chkIncludeEvidence.isOn(), contentPanel.chkIncludeReq.isOn(), contentPanel.chkIncludeRes.isOn(),
            (Integer) contentPanel.spinMaxReqBytes.getValue(), (Integer) contentPanel.spinMaxResBytes.getValue(),
            fields, CweMode.HARDCODED_CATALOG, Collections.emptyList(), contentPanel.chkToc.isOn());

        ReportProfile updated = new ReportProfile(
            ReportProfile.CURRENT_SCHEMA_VERSION, currentProfile.id(), currentProfile.name(),
            currentProfile.locale(), false, currentProfile.basedOnId(),
            cover, finding, new SectionGraph(nodes), branding, contentPolicy, pdfTheme, htmlTheme);

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
        int i = sectionListPanel.list.getSelectedIndex();
        if (i < 0) return;
        int t = i + delta;
        if (t < 0 || t >= sectionListPanel.model.getSize()) return;
        
        if (currentProfile != null && currentProfile.builtIn()) {
            autoCloneProfile();
            // Restore selection after model reload
            sectionListPanel.list.setSelectedIndex(i);
        }
        
        SectionNode n = sectionListPanel.model.remove(i);
        sectionListPanel.model.add(t, n);
        sectionListPanel.list.setSelectedIndex(t);
    }

    private void addSection() {
        String name = JOptionPane.showInputDialog(containerPanel, "Section identifier (e.g. CUSTOM_NOTES):");
        if (name != null && !name.isBlank()) {
            if (currentProfile != null && currentProfile.builtIn()) {
                autoCloneProfile();
            }
            int nextOrder = sectionListPanel.model.getSize() + 1;
            sectionListPanel.model.addElement(new SectionNode(name.trim().toUpperCase().replace(' ', '_'), true, nextOrder, false, name.trim().toUpperCase().replace(' ', '_'), new java.util.HashMap<>()));
        }
    }

    private void removeSection() {
        int idx = sectionListPanel.list.getSelectedIndex();
        if (idx < 0) return;
        Boolean required = sectionListPanel.model.getElementAt(idx).required();
        if (required != null && required) {
            JOptionPane.showMessageDialog(containerPanel, "Cannot remove a required section.", "Info", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        
        if (currentProfile != null && currentProfile.builtIn()) {
            autoCloneProfile();
        }
        
        sectionListPanel.model.remove(idx);
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


    private ResponsiveSection wrapInCard(String title, String iconName, ResponsiveSection inner) {
        CardPanel card = new CardPanel(title, iconName);
        card.addFormRow(inner.component());
        return new ResponsiveSection() {
            @Override
            public Component component() {
                return card;
            }
            @Override
            public void onBreakpointChanged(Breakpoint bp) {
                inner.onBreakpointChanged(bp);
            }
        };
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
