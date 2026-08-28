package icarus.ui;

import burp.api.montoya.MontoyaApi;
import icarus.core.I18n;
import icarus.core.ModuleConfig;
import icarus.core.ReportTemplateConfig;
import icarus.evidence.EvidenceUiHelpers;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.TitledBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.Transferable;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ReportingSettingsTab {
    private final MontoyaApi api;
    private final ModuleConfig config;
    private final ThemeHelper themeHelper;
    private final JPanel containerPanel;
    private final List<Runnable> saveHooks = new ArrayList<>();

    public ReportingSettingsTab(MontoyaApi api, ModuleConfig config, ThemeHelper themeHelper) {
        this.api = api;
        this.config = config;
        this.themeHelper = themeHelper;
        this.containerPanel = new JPanel(new BorderLayout());
        
        JPanel mainPanel = buildReportingTab();
        
        JScrollPane masterScroll = new JScrollPane(mainPanel);
        masterScroll.getVerticalScrollBar().setUnitIncrement(16);
        masterScroll.setBorder(null);
        themeHelper.applyTheme(masterScroll);
        
        this.containerPanel.add(masterScroll, BorderLayout.CENTER);
    }
    
    public Component getUiComponent() {
        return containerPanel;
    }

    public void save() {
        saveHooks.forEach(Runnable::run);
    }

    private JPanel buildReportingTab() {
        ReportTemplateConfig rtc = ReportTemplateConfig.fromConfig(config);

        // 1. Princípio Arquitetural Único: Painel Principal com BoxLayout Vertical
        JPanel mainPanel = new JPanel();
        mainPanel.setLayout(new BoxLayout(mainPanel, BoxLayout.Y_AXIS));
        mainPanel.setBorder(new EmptyBorder(16, 16, 16, 16));
        themeHelper.applyTheme(mainPanel);

        // -- SEÇÕES DO RELATÓRIO --
        JPanel sectionsCard = createCardPanel(I18n.t("ui.reporting.section.report_sections", "Seções do Relatório"));
        sectionsCard.setLayout(new BorderLayout());
        
        DefaultListModel<ReportTemplateConfig.Section> sectionModel = new DefaultListModel<>();
        rtc.sections().forEach(sectionModel::addElement);
        JList<ReportTemplateConfig.Section> sectionList = new JList<>(sectionModel);
        sectionList.setVisibleRowCount(6);
        sectionList.setCellRenderer(new SectionListRenderer());
        
        sectionList.setDragEnabled(true);
        sectionList.setDropMode(DropMode.ON_OR_INSERT);
        sectionList.setTransferHandler(new TransferHandler() {
            @Override
            public int getSourceActions(JComponent c) { return MOVE; }
            @Override
            protected Transferable createTransferable(JComponent c) {
                return new StringSelection(String.valueOf(((JList<?>) c).getSelectedIndex()));
            }
            @Override
            public boolean canImport(TransferSupport support) {
                return support.isDataFlavorSupported(DataFlavor.stringFlavor) && support.isDrop();
            }
            @Override
            public boolean importData(TransferSupport support) {
                if (!canImport(support)) return false;
                JList.DropLocation dl = (JList.DropLocation) support.getDropLocation();
                int dropIndex = dl.getIndex();
                try {
                    int dragIndex = Integer.parseInt((String) support.getTransferable().getTransferData(DataFlavor.stringFlavor));
                    if (dragIndex == dropIndex || dragIndex == dropIndex - 1) return false;
                    ReportTemplateConfig.Section s = sectionModel.remove(dragIndex);
                    if (dropIndex > dragIndex) dropIndex--;
                    sectionModel.add(dropIndex, s);
                    sectionList.setSelectedIndex(dropIndex);
                    return true;
                } catch (Exception e) {
                    return false;
                }
            }
        });

        InputMap im = sectionList.getInputMap(JComponent.WHEN_FOCUSED);
        ActionMap am = sectionList.getActionMap();
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_UP, InputEvent.ALT_DOWN_MASK), "moveUp");
        am.put("moveUp", new AbstractAction() {
            public void actionPerformed(ActionEvent e) {
                int idx = sectionList.getSelectedIndex();
                if (idx > 0) {
                    ReportTemplateConfig.Section s = sectionModel.remove(idx);
                    sectionModel.add(idx - 1, s);
                    sectionList.setSelectedIndex(idx - 1);
                }
            }
        });
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, InputEvent.ALT_DOWN_MASK), "moveDown");
        am.put("moveDown", new AbstractAction() {
            public void actionPerformed(ActionEvent e) {
                int idx = sectionList.getSelectedIndex();
                if (idx >= 0 && idx < sectionModel.size() - 1) {
                    ReportTemplateConfig.Section s = sectionModel.remove(idx);
                    sectionModel.add(idx + 1, s);
                    sectionList.setSelectedIndex(idx + 1);
                }
            }
        });

        JScrollPane sectionScroll = new JScrollPane(sectionList);
        sectionScroll.setPreferredSize(new Dimension(250, 220));

        JPanel sectionListButtons = new JPanel(new GridLayout(1, 4, 4, 0));
        sectionListButtons.setBorder(new EmptyBorder(4, 0, 0, 0));
        sectionListButtons.setOpaque(false);
        JButton btnAddSection = createIconButton("plus", I18n.t("ui.reporting.btn.add", "Adicionar"));
        JButton btnRemoveSection = createIconButton("trash-2", I18n.t("ui.reporting.btn.remove", "Remover"));
        JButton btnMoveUpSection = createIconButton("chevron-up", I18n.t("ui.reporting.btn.up", "Subir"));
        JButton btnMoveDownSection = createIconButton("chevron-down", I18n.t("ui.reporting.btn.down", "Descer"));
        
        btnAddSection.addActionListener(e -> {
            sectionModel.addElement(new ReportTemplateConfig.Section(I18n.t("ui.reporting.new_section", "Nova Seção"), ""));
            sectionList.setSelectedIndex(sectionModel.size() - 1);
        });
        btnRemoveSection.addActionListener(e -> {
            int idx = sectionList.getSelectedIndex();
            if (idx >= 0 && !sectionModel.get(idx).title().equalsIgnoreCase(ReportTemplateConfig.FINDINGS_MARKER)) {
                sectionModel.remove(idx);
            }
        });
        btnMoveUpSection.addActionListener(e -> am.get("moveUp").actionPerformed(null));
        btnMoveDownSection.addActionListener(e -> am.get("moveDown").actionPerformed(null));

        sectionListButtons.add(btnAddSection);
        sectionListButtons.add(btnRemoveSection);
        sectionListButtons.add(btnMoveUpSection);
        sectionListButtons.add(btnMoveDownSection);

        JPanel sectionListPanel = new JPanel(new BorderLayout());
        sectionListPanel.setOpaque(false);
        sectionListPanel.add(sectionScroll, BorderLayout.CENTER);
        sectionListPanel.add(sectionListButtons, BorderLayout.SOUTH);

        JTextField txtSectionTitle = new JTextField();
        JTextArea txtSectionContent = new JTextArea(8, 40);
        themeHelper.styleTextArea(txtSectionContent);
        setupTextArea(txtSectionContent, 8);

        JLabel lblFindingsBanner = new JLabel("ℹ️ " + I18n.t("ui.reporting.marker.findings.content", "Esta seção é gerada dinamicamente a partir dos achados cadastrados no Gerenciador de Evidências."));
        lblFindingsBanner.setBorder(new EmptyBorder(0, 0, 8, 0));
        lblFindingsBanner.setForeground(UIManager.getColor("Label.disabledForeground"));
        lblFindingsBanner.setVisible(false);

        JPanel sectionEditorPanel = new JPanel(new BorderLayout(0, 8));
        sectionEditorPanel.setBorder(new EmptyBorder(0, 8, 0, 0));
        sectionEditorPanel.setOpaque(false);
        
        JPanel titleRow = new JPanel(new BorderLayout(8, 0));
        titleRow.setOpaque(false);
        JLabel lblTitleField = new JLabel(I18n.t("ui.reporting.lbl.title", "Título:"));
        titleRow.add(lblTitleField, BorderLayout.WEST);
        titleRow.add(txtSectionTitle, BorderLayout.CENTER);
        sectionEditorPanel.add(titleRow, BorderLayout.NORTH);
        
        JPanel centerContentPanel = new JPanel(new BorderLayout());
        centerContentPanel.setOpaque(false);
        centerContentPanel.add(lblFindingsBanner, BorderLayout.NORTH);
        centerContentPanel.add(new JScrollPane(txtSectionContent), BorderLayout.CENTER);
        sectionEditorPanel.add(centerContentPanel, BorderLayout.CENTER);
        
        JLabel lblMarkdownHint = new JLabel(I18n.t("ui.reporting.lbl.markdown_hint", "Suporta formatação Markdown"));
        lblMarkdownHint.setFont(lblMarkdownHint.getFont().deriveFont(11f));
        sectionEditorPanel.add(lblMarkdownHint, BorderLayout.SOUTH);

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
                txtSectionTitle.setEditable(false);
                txtSectionContent.setEditable(false);
                lblFindingsBanner.setVisible(false);
                txtSectionContent.setVisible(true);
            } else {
                ReportTemplateConfig.Section s = sectionModel.get(idx);
                boolean isFindings = s.title().equalsIgnoreCase(ReportTemplateConfig.FINDINGS_MARKER);
                txtSectionTitle.setText(s.title());
                txtSectionContent.setText(isFindings ? "" : s.content());
                txtSectionTitle.setEnabled(!isFindings);
                txtSectionContent.setEnabled(!isFindings);
                txtSectionTitle.setEditable(!isFindings);
                txtSectionContent.setEditable(!isFindings);
                
                lblFindingsBanner.setVisible(isFindings);
                txtSectionContent.setVisible(!isFindings);
            }
            syncingSelection[0] = false;
        });

        if (sectionModel.size() > 0) sectionList.setSelectedIndex(0);

        JSplitPane sectionsSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, sectionListPanel, sectionEditorPanel);
        sectionsSplit.setResizeWeight(0.3);
        sectionsSplit.setDividerSize(6);
        sectionsSplit.setBorder(null);
        sectionsSplit.setOpaque(false);
        sectionsCard.add(sectionsSplit, BorderLayout.CENTER);
        
        mainPanel.add(sectionsCard);
        mainPanel.add(Box.createVerticalStrut(15)); // Tolerância Zero

        // -- VARIÁVEIS --
        JPanel varsCard = createCardPanel(I18n.t("ui.reporting.section.template_variables", "Variáveis do Template"));
        varsCard.setLayout(new BorderLayout(0, 4));
        JLabel lblVarHint = new JLabel(I18n.t("ui.reporting.lbl.var_hint", "Defina variáveis globais (chave=valor)"));
        varsCard.add(lblVarHint, BorderLayout.NORTH);
        
        StringBuilder varLines = new StringBuilder();
        rtc.variables().forEach((k, v) -> varLines.append(k).append('=').append(v).append('\n'));
        JTextArea txtVariables = new JTextArea(varLines.toString(), 5, 40);
        themeHelper.styleTextArea(txtVariables);
        setupTextArea(txtVariables, 6);
        varsCard.add(new JScrollPane(txtVariables), BorderLayout.CENTER);
        
        mainPanel.add(varsCard);
        mainPanel.add(Box.createVerticalStrut(15));



        // -- RETESTE --
        JPanel retestCard = createCardPanel(I18n.t("ui.reporting.section.retest_mode", "Modo de Reteste"));
        retestCard.setLayout(new BoxLayout(retestCard, BoxLayout.Y_AXIS));
        retestCard.setAlignmentX(Component.LEFT_ALIGNMENT);
        
        JLabel lblRetestHint = new JLabel(I18n.t("ui.reporting.lbl.status_hint", "Status de achados a incluir no relatório de reteste (um por linha):"));
        lblRetestHint.setAlignmentX(Component.LEFT_ALIGNMENT);
        retestCard.add(lblRetestHint);
        retestCard.add(Box.createRigidArea(new Dimension(0, 4)));

        List<String> statuses = rtc.retestStatuses().isEmpty() ? ReportTemplateConfig.defaultRetestStatuses() : rtc.retestStatuses();
        JTextArea txtRetestStatuses = new JTextArea(String.join("\n", statuses), 3, 40);
        themeHelper.styleTextArea(txtRetestStatuses);
        setupTextArea(txtRetestStatuses, 4);
        JScrollPane retestScroll = new JScrollPane(txtRetestStatuses);
        retestScroll.setAlignmentX(Component.LEFT_ALIGNMENT);
        retestScroll.setMaximumSize(new Dimension(Integer.MAX_VALUE, retestScroll.getPreferredSize().height));
        retestCard.add(retestScroll);
        
        retestCard.add(Box.createRigidArea(new Dimension(0, 10)));
        JLabel lblSuppressHint = new JLabel(I18n.t("ui.reporting.lbl.suppress_hint", "Títulos de seção para ocultar em relatórios de reteste (um por linha):"));
        lblSuppressHint.setAlignmentX(Component.LEFT_ALIGNMENT);
        retestCard.add(lblSuppressHint);
        retestCard.add(Box.createRigidArea(new Dimension(0, 4)));

        List<String> suppressed = rtc.retestSuppressedSections().isEmpty() ? ReportTemplateConfig.defaultRetestSuppressedSections() : rtc.retestSuppressedSections();
        JTextArea txtSuppressed = new JTextArea(String.join("\n", suppressed), 4, 40);
        themeHelper.styleTextArea(txtSuppressed);
        setupTextArea(txtSuppressed, 5);
        JScrollPane suppressScroll = new JScrollPane(txtSuppressed);
        suppressScroll.setAlignmentX(Component.LEFT_ALIGNMENT);
        suppressScroll.setMaximumSize(new Dimension(Integer.MAX_VALUE, suppressScroll.getPreferredSize().height));
        retestCard.add(suppressScroll);

        mainPanel.add(retestCard);
        mainPanel.add(Box.createVerticalStrut(15));

        // -- TEMA E ESTILO --
        JPanel themeCardPanel = new JPanel(new GridBagLayout());
        themeCardPanel.setBackground(UIManager.getColor("TextField.background"));
        themeCardPanel.setBorder(BorderFactory.createTitledBorder("Tema e Estilo"));
        themeCardPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
        
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.fill = GridBagConstraints.HORIZONTAL;

        JPanel colorPickerPrimaria = createColorPickerComponent("");
        setColorPickerValue(colorPickerPrimaria, rtc.primaryColor());
        gbc.gridx = 0; gbc.gridy = 0; gbc.insets = new Insets(5, 5, 5, 10); gbc.anchor = GridBagConstraints.EAST;
        themeCardPanel.add(new JLabel(I18n.t("ui.reporting.lbl.primary_color", "Cor primária:")), gbc);
        gbc.gridx = 1; gbc.anchor = GridBagConstraints.WEST; gbc.weightx = 1.0;
        themeCardPanel.add(colorPickerPrimaria, gbc);

        JPanel colorPickerSecundaria = createColorPickerComponent("");
        setColorPickerValue(colorPickerSecundaria, rtc.secondaryColor());
        gbc.gridx = 0; gbc.gridy = 1; gbc.weightx = 0.0; gbc.anchor = GridBagConstraints.EAST;
        themeCardPanel.add(new JLabel(I18n.t("ui.reporting.lbl.secondary_color", "Cor secundária:")), gbc);
        gbc.gridx = 1; gbc.anchor = GridBagConstraints.WEST; gbc.weightx = 1.0;
        themeCardPanel.add(colorPickerSecundaria, gbc);

        JPanel filePickerCss = createFilePickerComponent("");
        setFilePickerValue(filePickerCss, rtc.customCssPath());
        gbc.gridx = 0; gbc.gridy = 2; gbc.weightx = 0.0; gbc.anchor = GridBagConstraints.EAST;
        themeCardPanel.add(new JLabel(I18n.t("ui.reporting.lbl.custom_css", "CSS Customizado:")), gbc);
        gbc.gridx = 1; gbc.anchor = GridBagConstraints.WEST; gbc.weightx = 1.0;
        themeCardPanel.add(filePickerCss, gbc);

        JPanel filePickerLogo = createFilePickerComponent("");
        setFilePickerValue(filePickerLogo, rtc.logoPath());
        gbc.gridx = 0; gbc.gridy = 3; gbc.weightx = 0.0; gbc.anchor = GridBagConstraints.EAST;
        themeCardPanel.add(new JLabel(I18n.t("ui.reporting.lbl.report_logo", "Logo do Relatório:")), gbc);
        gbc.gridx = 1; gbc.anchor = GridBagConstraints.WEST; gbc.weightx = 1.0;
        themeCardPanel.add(filePickerLogo, gbc);

        JPanel filePickerClientLogo = createFilePickerComponent("");
        setFilePickerValue(filePickerClientLogo, rtc.clientLogoPath());
        gbc.gridx = 0; gbc.gridy = 4; gbc.weightx = 0.0; gbc.anchor = GridBagConstraints.EAST;
        themeCardPanel.add(new JLabel(I18n.t("ui.reporting.lbl.client_logo", "Logo do Cliente:")), gbc);
        gbc.gridx = 1; gbc.anchor = GridBagConstraints.WEST; gbc.weightx = 1.0;
        themeCardPanel.add(filePickerClientLogo, gbc);

        gbc.gridx = 0; gbc.gridy = 5; gbc.weightx = 0.0; gbc.anchor = GridBagConstraints.EAST;
        themeCardPanel.add(new JLabel(I18n.t("ui.reporting.chk.toc", "Gerar Índice (TOC):")), gbc);
        JCheckBox chkToc = new JCheckBox();
        chkToc.setSelected(rtc.tocEnabled());
        chkToc.setOpaque(false);
        gbc.gridx = 1; gbc.anchor = GridBagConstraints.WEST; gbc.weightx = 1.0;
        themeCardPanel.add(chkToc, gbc);

        setPanelEnabled(themeCardPanel, false);
        mainPanel.add(themeCardPanel);

        // -- SAVE HOOKS --
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



            rtc.setPrimaryColor(blankToNull((String) colorPickerPrimaria.getClientProperty("hexValue")));
            rtc.setSecondaryColor(blankToNull((String) colorPickerSecundaria.getClientProperty("hexValue")));
            rtc.setCustomCssPath(blankToNull((String) filePickerCss.getClientProperty("filePath")));
            rtc.setLogoPath(blankToNull((String) filePickerLogo.getClientProperty("filePath")));
            rtc.setClientLogoPath(blankToNull((String) filePickerClientLogo.getClientProperty("filePath")));
            rtc.setTocEnabled(chkToc.isSelected());
            rtc.setRetestStatuses(nonBlankLines(txtRetestStatuses.getText()));
            rtc.setRetestSuppressedSections(nonBlankLines(txtSuppressed.getText()));

            rtc.saveTo(config);
        });

        mainPanel.add(Box.createVerticalGlue());

        return mainPanel;
    }

    private JPanel createCardPanel(String title) {
        JPanel pnl = new JPanel();
        pnl.setBackground(UIManager.getColor("TextField.background"));
        pnl.setBorder(BorderFactory.createTitledBorder(title));
        pnl.setAlignmentX(Component.LEFT_ALIGNMENT);
        return pnl;
    }

    public JPanel createColorPickerComponent(String label) {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        panel.setOpaque(false);
        
        if (label != null && !label.isEmpty()) {
            panel.add(new JLabel(label));
        }
        
        JButton colorBtn = new JButton();
        colorBtn.setPreferredSize(new Dimension(24, 24));
        colorBtn.putClientProperty("FlatLaf.style", "arc: 6; borderWidth: 1; borderColor: $Component.borderColor");
        colorBtn.setCursor(new Cursor(Cursor.HAND_CURSOR));
        colorBtn.setContentAreaFilled(false);
        colorBtn.setOpaque(true);
        colorBtn.setBackground(UIManager.getColor("Panel.background"));
        
        JLabel hexLabel = new JLabel("#------");
        
        colorBtn.addActionListener(e -> {
            Color chosen = JColorChooser.showDialog(panel, "Escolher Cor", colorBtn.getBackground());
            if (chosen != null) {
                colorBtn.setBackground(chosen);
                String hex = String.format("#%02x%02x%02x", chosen.getRed(), chosen.getGreen(), chosen.getBlue());
                hexLabel.setText(hex);
                panel.putClientProperty("hexValue", hex);
            }
        });
        
        panel.add(colorBtn);
        panel.add(hexLabel);
        
        return panel;
    }

    private void setColorPickerValue(JPanel panel, String hex) {
        JButton btn = (JButton) panel.getComponent(panel.getComponentCount() - 2);
        JLabel lbl = (JLabel) panel.getComponent(panel.getComponentCount() - 1);
        if (hex != null && hex.matches("^#([A-Fa-f0-9]{6}|[A-Fa-f0-9]{3})$")) {
            btn.setBackground(Color.decode(hex));
            lbl.setText(hex);
            panel.putClientProperty("hexValue", hex);
        } else {
            btn.setBackground(UIManager.getColor("Panel.background"));
            lbl.setText("#------");
            panel.putClientProperty("hexValue", "");
        }
    }

    public JPanel createFilePickerComponent(String label) {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        panel.setOpaque(false);
        
        if (label != null && !label.isEmpty()) {
            panel.add(new JLabel(label));
        }
        
        JLabel fileLabel = new JLabel(I18n.t("ui.reporting.lbl.no_file", "Nenhum arquivo"));
        fileLabel.setForeground(UIManager.getColor("Label.disabledForeground"));
        
        JButton btnBrowse = new JButton(I18n.t("ui.reporting.btn.browse", "Navegar..."));
        btnBrowse.setIcon(EvidenceUiHelpers.createIcon("folder"));
        btnBrowse.addActionListener(e -> {
            JFileChooser fc = new JFileChooser();
            if (fc.showOpenDialog(panel) == JFileChooser.APPROVE_OPTION) {
                File f = fc.getSelectedFile();
                fileLabel.setText(f.getName());
                fileLabel.setToolTipText(f.getAbsolutePath());
                fileLabel.setForeground(UIManager.getColor("Label.foreground"));
                panel.putClientProperty("filePath", f.getAbsolutePath());
            }
        });
        
        panel.add(fileLabel);
        panel.add(btnBrowse);
        return panel;
    }

    private void setFilePickerValue(JPanel panel, String path) {
        JLabel lbl = (JLabel) panel.getComponent(panel.getComponentCount() - 2);
        if (path != null && !path.isBlank()) {
            File f = new File(path);
            lbl.setText(f.getName());
            lbl.setToolTipText(path);
            lbl.setForeground(UIManager.getColor("Label.foreground"));
            panel.putClientProperty("filePath", path);
        } else {
            lbl.setText(I18n.t("ui.reporting.lbl.no_file", "Nenhum arquivo"));
            lbl.setToolTipText(null);
            lbl.setForeground(UIManager.getColor("Label.disabledForeground"));
            panel.putClientProperty("filePath", "");
        }
    }

    private void setupTextArea(JTextArea textArea, int rows) {
        textArea.setLineWrap(true);
        textArea.setWrapStyleWord(true);
        textArea.setRows(rows);
        textArea.addMouseWheelListener(e -> {
            Component parent = SwingUtilities.getAncestorOfClass(JScrollPane.class, textArea);
            if (parent != null) {
                parent.dispatchEvent(SwingUtilities.convertMouseEvent(textArea, e, parent));
            }
        });
    }

    private JButton createIconButton(String iconName, String tooltip) {
        Icon icon = EvidenceUiHelpers.createIcon(iconName);
        JButton btn = new JButton(icon);
        btn.setToolTipText(tooltip);
        btn.putClientProperty("JButton.buttonType", "toolBarButton");
        btn.putClientProperty("FlatLaf.style", "arc: 8");
        return btn;
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

    private static void setPanelEnabled(Component component, boolean enabled) {
        component.setEnabled(enabled);
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                setPanelEnabled(child, enabled);
            }
        }
    }

    class SectionListRenderer extends JPanel implements ListCellRenderer<ReportTemplateConfig.Section> {
        private final JLabel lblIcon;
        private final JLabel lblTitle;
        private final JLabel lblBadge;

        public SectionListRenderer() {
            setLayout(new BorderLayout(8, 0));
            setBorder(new EmptyBorder(6, 6, 6, 6));
            setOpaque(true);

            lblIcon = new JLabel(EvidenceUiHelpers.createIcon("menu"));
            lblTitle = new JLabel();
            lblTitle.setFont(lblTitle.getFont().deriveFont(Font.BOLD));
            
            lblBadge = new JLabel();
            lblBadge.setOpaque(true);
            lblBadge.setFont(lblBadge.getFont().deriveFont(10f));
            lblBadge.setBorder(new EmptyBorder(2, 4, 2, 4));
            lblBadge.putClientProperty("FlatLaf.style", "arc: 8"); 

            add(lblIcon, BorderLayout.WEST);
            add(lblTitle, BorderLayout.CENTER);
            add(lblBadge, BorderLayout.EAST);
        }

        @Override
        public Component getListCellRendererComponent(JList<? extends ReportTemplateConfig.Section> list, ReportTemplateConfig.Section value, int index, boolean isSelected, boolean cellHasFocus) {
            setBackground(isSelected ? themeHelper.getSelectionBackgroundColor() : themeHelper.getBackgroundColor());
            setForeground(isSelected ? list.getSelectionForeground() : themeHelper.getForegroundColor());

            boolean isFindings = value.title().equalsIgnoreCase(ReportTemplateConfig.FINDINGS_MARKER);
            String label = isFindings ? I18n.t("ui.reporting.marker.findings", "FINDINGS") : (value.title().isBlank() ? I18n.t("ui.reporting.marker.untitled", "Sem Título") : value.title());

            lblTitle.setText((index + 1) + ". " + label);
            lblTitle.setForeground(getForeground());

            if (isFindings) {
                lblBadge.setVisible(true);
                lblBadge.setText("MARKER");
                lblBadge.setBackground(Color.decode("#d32f2f"));
                lblBadge.setForeground(Color.WHITE);
            } else {
                lblBadge.setVisible(false);
            }

            return this;
        }
    }


}
