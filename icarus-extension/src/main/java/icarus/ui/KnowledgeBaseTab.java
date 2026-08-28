package icarus.ui;

import burp.api.montoya.MontoyaApi;
import icarus.Orchestrator;
import icarus.core.Category;
import icarus.core.Finding;
import icarus.core.I18n;
import icarus.core.KnowledgeBaseEntry;
import icarus.core.Severity;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;

public class KnowledgeBaseTab {
    private final MontoyaApi api;
    private final Orchestrator orchestrator;
    private final JPanel mainPanel;
    private final DefaultTableModel tableModel;
    private final JTable table;
    private final ThemeHelper themeHelper;

    public KnowledgeBaseTab(MontoyaApi api, Orchestrator orchestrator) {
        this.api = api;
        this.orchestrator = orchestrator;
        this.themeHelper = new ThemeHelper(api.userInterface());

        mainPanel = new JPanel(new BorderLayout());
        themeHelper.applyTheme(mainPanel);

        // Define Columns
        String[] columns = {I18n.t("ui.kb.col.name"), I18n.t("ui.kb.col.severity"), I18n.t("ui.kb.col.description"), I18n.t("ui.kb.col.impact"), I18n.t("ui.kb.col.recommendation"), I18n.t("ui.kb.col.impact_level"), I18n.t("ui.kb.col.prob_level"), I18n.t("ui.kb.col.cwe")};
        tableModel = new DefaultTableModel(columns, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };

        table = new JTable(tableModel);
        themeHelper.styleTable(table);
        
        // Set proportional column widths
        table.getColumnModel().getColumn(0).setPreferredWidth(150); // Name
        table.getColumnModel().getColumn(1).setPreferredWidth(80);  // Severity
        table.getColumnModel().getColumn(2).setPreferredWidth(250); // Description
        table.getColumnModel().getColumn(3).setPreferredWidth(200); // Impact
        table.getColumnModel().getColumn(4).setPreferredWidth(250); // Recommendation
        table.getColumnModel().getColumn(5).setPreferredWidth(80);  // Impact Level
        table.getColumnModel().getColumn(6).setPreferredWidth(80);  // Prob Level
        table.getColumnModel().getColumn(7).setPreferredWidth(50);  // CWE

        JScrollPane scrollPane = new JScrollPane(table);
        scrollPane.setBorder(BorderFactory.createEmptyBorder(15, 15, 15, 15));
        themeHelper.applyTheme(scrollPane);

        mainPanel.add(scrollPane, BorderLayout.CENTER);

        // Bottom Actions Panel
        JPanel actionPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 10));
        actionPanel.setBorder(BorderFactory.createEmptyBorder(0, 15, 15, 15));
        themeHelper.applyTheme(actionPanel);

        JButton btnAdd = themeHelper.createPrimaryButton(I18n.t("ui.kb.btn.add"));
        
        JButton btnEdit = new JButton(I18n.t("ui.kb.btn.edit"));
        themeHelper.styleButton(btnEdit);
        
        JButton btnDelete = new JButton(I18n.t("ui.kb.btn.delete"));
        themeHelper.styleButton(btnDelete);

        // Styling with ICARUS standard tokens (using custom colors where ThemeHelper doesn't provide them directly)
        btnDelete.setForeground(themeHelper.isDarkTheme() ? new Color(0xFF1744) : new Color(0xD32F2F)); // COLOR_CRITICAL_RED

        actionPanel.add(btnAdd);
        actionPanel.add(btnEdit);
        actionPanel.add(btnDelete);

        mainPanel.add(actionPanel, BorderLayout.SOUTH);

        // Context Menu
        JPopupMenu contextMenu = new JPopupMenu();
        JMenuItem createFindingItem = new JMenuItem(I18n.t("ui.kb.menu.create_finding"));
        contextMenu.add(createFindingItem);

        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (SwingUtilities.isRightMouseButton(e)) {
                    int row = table.rowAtPoint(e.getPoint());
                    if (row >= 0 && row < table.getRowCount()) {
                        table.setRowSelectionInterval(row, row);
                        contextMenu.show(table, e.getX(), e.getY());
                    }
                }
            }
        });

        createFindingItem.addActionListener(e -> {
            int selectedRow = table.getSelectedRow();
            if (selectedRow >= 0) {
                String name = (String) tableModel.getValueAt(selectedRow, 0);
                KnowledgeBaseEntry entry = orchestrator.getKnowledgeBaseEntry(name);
                if (entry != null) {
                    Severity sev;
                    try {
                        sev = parseSeverity(entry.severity());
                    } catch (Exception ex) {
                        sev = Severity.INFO;
                    }
                    
                    Finding finding = Finding.builder("Manual", entry.name())
                            .description(entry.description())
                            .severity(sev)
                            .category(Category.MANUAL)
                            .build();
                    
                    SwingUtilities.invokeLater(() -> orchestrator.showEvidenceInteractive(finding));
                }
            }
        });
        
        btnDelete.addActionListener(e -> {
            int selectedRow = table.getSelectedRow();
            if (selectedRow >= 0) {
                String name = (String) tableModel.getValueAt(selectedRow, 0);
                orchestrator.deleteKnowledgeBaseEntry(name);
                refreshTable();
            }
        });

        btnAdd.addActionListener(e -> showEditDialog(null));

        btnEdit.addActionListener(e -> {
            int selectedRow = table.getSelectedRow();
            if (selectedRow >= 0) {
                String name = (String) tableModel.getValueAt(selectedRow, 0);
                KnowledgeBaseEntry entry = orchestrator.getKnowledgeBaseEntry(name);
                if (entry != null) {
                    showEditDialog(entry);
                }
            }
        });

        refreshTable();
    }

    private void showEditDialog(KnowledgeBaseEntry entry) {
        JDialog dialog = new JDialog(api.userInterface().swingUtils().suiteFrame(), 
                entry == null ? I18n.t("ui.kb.dialog.add.title") : I18n.t("ui.kb.dialog.edit.title"), true);
        dialog.setLayout(new BorderLayout());
        dialog.setSize(600, 500);
        dialog.setLocationRelativeTo(api.userInterface().swingUtils().suiteFrame());

        JPanel formPanel = new JPanel(new GridBagLayout());
        themeHelper.applyTheme(formPanel);
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(5, 5, 5, 5);
        gbc.fill = GridBagConstraints.HORIZONTAL;

        JTextField txtName = new JTextField(entry != null ? entry.name() : "");
        themeHelper.applyTheme(txtName);
        if (entry != null) txtName.setEditable(false);

        JComboBox<String> cbSeverity = new JComboBox<>(new String[]{"CRITICAL", "HIGH", "MEDIUM", "LOW", "INFO"});
        if (entry != null) cbSeverity.setSelectedItem(entry.severity());

        JTextArea txtDesc = new JTextArea(entry != null ? entry.description() : "", 4, 30);
        txtDesc.setLineWrap(true);
        txtDesc.setWrapStyleWord(true);
        themeHelper.applyTheme(txtDesc);

        JTextArea txtImpact = new JTextArea(entry != null ? entry.impact() : "", 4, 30);
        txtImpact.setLineWrap(true);
        txtImpact.setWrapStyleWord(true);
        themeHelper.applyTheme(txtImpact);

        JTextArea txtRec = new JTextArea(entry != null ? entry.recommendation() : "", 4, 30);
        txtRec.setLineWrap(true);
        txtRec.setWrapStyleWord(true);
        themeHelper.applyTheme(txtRec);

        JTextField txtImpactLevel = new JTextField(entry != null ? entry.impactLevel() : "");
        themeHelper.applyTheme(txtImpactLevel);

        JTextField txtProbLevel = new JTextField(entry != null ? entry.probLevel() : "");
        themeHelper.applyTheme(txtProbLevel);

        JTextField txtCWE = new JTextField(entry != null ? entry.cwe() : "");
        themeHelper.applyTheme(txtCWE);

        gbc.gridx = 0; gbc.gridy = 0; formPanel.add(new JLabel(I18n.t("ui.kb.lbl.name")), gbc);
        gbc.gridx = 1; formPanel.add(txtName, gbc);

        gbc.gridx = 0; gbc.gridy = 1; formPanel.add(new JLabel(I18n.t("ui.kb.lbl.severity")), gbc);
        gbc.gridx = 1; formPanel.add(cbSeverity, gbc);

        gbc.gridx = 0; gbc.gridy = 2; formPanel.add(new JLabel(I18n.t("ui.kb.lbl.description")), gbc);
        gbc.gridx = 1; formPanel.add(new JScrollPane(txtDesc), gbc);

        gbc.gridx = 0; gbc.gridy = 3; formPanel.add(new JLabel(I18n.t("ui.kb.lbl.impact")), gbc);
        gbc.gridx = 1; formPanel.add(new JScrollPane(txtImpact), gbc);

        gbc.gridx = 0; gbc.gridy = 4; formPanel.add(new JLabel(I18n.t("ui.kb.lbl.recommendation")), gbc);
        gbc.gridx = 1; formPanel.add(new JScrollPane(txtRec), gbc);

        gbc.gridx = 0; gbc.gridy = 5; formPanel.add(new JLabel(I18n.t("ui.kb.lbl.impact_level")), gbc);
        gbc.gridx = 1; formPanel.add(txtImpactLevel, gbc);

        gbc.gridx = 0; gbc.gridy = 6; formPanel.add(new JLabel(I18n.t("ui.kb.lbl.prob_level")), gbc);
        gbc.gridx = 1; formPanel.add(txtProbLevel, gbc);

        gbc.gridx = 0; gbc.gridy = 7; formPanel.add(new JLabel(I18n.t("ui.kb.lbl.cwe")), gbc);
        gbc.gridx = 1; formPanel.add(txtCWE, gbc);

        dialog.add(new JScrollPane(formPanel), BorderLayout.CENTER);

        JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        themeHelper.applyTheme(btnPanel);
        JButton btnSave = themeHelper.createPrimaryButton(I18n.t("ui.kb.btn.save"));
        JButton btnCancel = new JButton(I18n.t("ui.kb.btn.cancel"));
        themeHelper.styleButton(btnCancel);

        btnSave.addActionListener(ev -> {
            KnowledgeBaseEntry newEntry = new KnowledgeBaseEntry(
                    txtName.getText(),
                    (String) cbSeverity.getSelectedItem(),
                    txtDesc.getText(),
                    txtImpact.getText(),
                    txtRec.getText(),
                    txtImpactLevel.getText(),
                    txtProbLevel.getText(),
                    txtCWE.getText()
            );
            orchestrator.upsertKnowledgeBaseEntry(newEntry);
            refreshTable();
            dialog.dispose();
        });

        btnCancel.addActionListener(ev -> dialog.dispose());

        btnPanel.add(btnCancel);
        btnPanel.add(btnSave);
        dialog.add(btnPanel, BorderLayout.SOUTH);

        dialog.setVisible(true);
    }

    public static Severity parseSeverity(String sevStr) {
        if (sevStr == null) return Severity.INFO;
        String s = sevStr.toUpperCase().trim();
        if (s.equals("CRÍTICO") || s.equals("CRITICO")) return Severity.CRITICAL;
        if (s.equals("ALTO")) return Severity.HIGH;
        if (s.equals("MÉDIO") || s.equals("MEDIO")) return Severity.MEDIUM;
        if (s.equals("BAIXO")) return Severity.LOW;
        return Severity.valueOf(s);
    }

    public void refreshTable() {
        SwingUtilities.invokeLater(() -> {
            tableModel.setRowCount(0);
            List<KnowledgeBaseEntry> entries = orchestrator.getKnowledgeBaseEntries();
            for (KnowledgeBaseEntry entry : entries) {
                tableModel.addRow(new Object[]{
                        entry.name(),
                        entry.severity(),
                        entry.description(),
                        entry.impact(),
                        entry.recommendation(),
                        entry.impactLevel(),
                        entry.probLevel(),
                        entry.cwe()
                });
            }
        });
    }

    public Component getComponent() {
        return mainPanel;
    }
}
