import sys
import re

with open('icarus-extension/src/main/java/icarus/ui/ReportingSettingsTab.java', 'r') as f:
    content = f.read()

# Fix imports
imports = """import icarus.report.model.*;
import com.icarus.ui.reportprofile.layout.*;
import com.icarus.ui.reportprofile.sections.*;
import com.icarus.ui.reportprofile.components.*;"""

content = re.sub(r'import icarus\.report\.model\.\*;', imports, content)

# Modify fields
fields_to_remove = r"""    // Layout card
    private JRadioButton rdoCoverGradient, rdoCoverHeaderBand, rdoCoverNone;
    private JRadioButton rdoFindingCard, rdoFindingTabular;
    private com.icarus.ui.reportprofile.sections.SectionListPanel sectionListPanel;
    private com.icarus.ui.reportprofile.sections.DetailPane detailPane;

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
    private final Map<FindingField, JCheckBox> fieldCheckboxes = new EnumMap<>(FindingField.class);"""

new_fields = """    private LayoutSectionPanel layoutPanel;
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
    private final Map<Severity, JPanel> severityColorPanels = new EnumMap<>(Severity.class);"""

content = content.replace(fields_to_remove, new_fields)

# Modify buildContent and its sub-methods
build_content_start = content.find("    private JPanel buildContent() {")
build_actions_card_end = content.find("    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n    //  Data: load / save")

build_content_code = """    private JPanel buildContent() {
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
        responsiveContainer.registerSection(toolbarPanel);

        // Layout
        layoutPanel = new LayoutSectionPanel();
        responsiveContainer.registerSection(layoutPanel);

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
        sectionListPanel.component().add(sideBtns, BorderLayout.EAST);
        
        flowPanel = new SectionFlowPanel(sectionListPanel.component(), detailPane.component());
        responsiveContainer.registerSection(flowPanel);

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
        responsiveContainer.registerSection(themePanel);

        // Branding
        brandingPanel = new BrandingSectionPanel();
        responsiveContainer.registerSection(brandingPanel);

        // Content
        contentPanel = new ContentSectionPanel();
        responsiveContainer.registerSection(contentPanel);

        // Add some padding around the container
        JPanel wrapper = new JPanel(new BorderLayout());
        wrapper.setBorder(new EmptyBorder(16, 16, 16, 16));
        wrapper.setOpaque(false);
        wrapper.add(responsiveContainer, BorderLayout.CENTER);
        return wrapper;
    }
"""
content = content[:build_content_start] + build_content_code + content[build_actions_card_end:]

# Update loadProfileIntoForm
load_start = content.find("    private void loadProfileIntoForm(ReportProfile p) {")
load_end = content.find("    private void autoCloneProfile() {")

load_code = """    private void loadProfileIntoForm(ReportProfile p) {
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
        contentPanel.chkIncludeEvidence.setSelected(c.includeEvidence());
        contentPanel.chkIncludeReq.setSelected(c.includeHttpRequest());
        contentPanel.spinMaxReqBytes.setValue(c.maxRequestBytes());
        contentPanel.chkIncludeRes.setSelected(c.includeHttpResponse());
        contentPanel.spinMaxResBytes.setValue(c.maxResponseBytes());
        contentPanel.chkToc.setSelected(c.includeTocBookmarks());
        for (var e : contentPanel.fieldCheckboxes.entrySet())
            e.getValue().setSelected(c.findingFields().contains(e.getKey()));
    }
"""

content = content[:load_start] + load_code + content[load_end:]

# Update saveCurrentProfileChanges
save_start = content.find("    private void saveCurrentProfileChanges() {")
save_end = content.find("    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n    //  Actions")

save_code = """    private void saveCurrentProfileChanges() {
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
        contentPanel.fieldCheckboxes.forEach((f, chk) -> { if (chk.isSelected()) fields.add(f); });

        ContentPolicy contentPolicy = new ContentPolicy(
            contentPanel.chkIncludeEvidence.isSelected(), contentPanel.chkIncludeReq.isSelected(), contentPanel.chkIncludeRes.isSelected(),
            (Integer) contentPanel.spinMaxReqBytes.getValue(), (Integer) contentPanel.spinMaxResBytes.getValue(),
            fields, CweMode.HARDCODED_CATALOG, Collections.emptyList(), contentPanel.chkToc.isSelected());

        ReportProfile updated = new ReportProfile(
            ReportProfile.CURRENT_SCHEMA_VERSION, currentProfile.id(), currentProfile.name(),
            currentProfile.locale(), false, currentProfile.basedOnId(),
            cover, finding, new SectionGraph(nodes), branding, contentPolicy, pdfTheme, htmlTheme);

        profileManager.saveUserProfile(updated);
        showToast("Profile '" + updated.name() + "' saved.");
    }
"""

content = content[:save_start] + save_code + content[save_end:]

# Update CardPanel usage in UI Helpers - we don't need CardPanel anymore, but it might be used? Let's just remove it.
cardpanel_start = content.find("    /** Reusable card panel matching SettingsPanel's style. */")
cardpanel_end = content.find("    private JButton btn(String text, String icon, java.awt.event.ActionListener al) {")
if cardpanel_start != -1 and cardpanel_end != -1:
    content = content[:cardpanel_start] + content[cardpanel_end:]

# Update radio, label, indent UI Helpers (we might not need them but they shouldn't hurt, wait, if we delete radio it's better)
helpers_start = content.find("    private static JRadioButton radio(String text) {")
if helpers_start != -1:
    helpers_end = content.find("    private JPanel createColorSwatch() {")
    if helpers_end != -1:
        content = content[:helpers_start] + content[helpers_end:]

with open('icarus-extension/src/main/java/icarus/ui/ReportingSettingsTab.java', 'w') as f:
    f.write(content)

