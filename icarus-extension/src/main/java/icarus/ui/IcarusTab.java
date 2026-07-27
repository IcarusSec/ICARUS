package icarus.ui;

import burp.api.montoya.MontoyaApi;
import icarus.Orchestrator;
import icarus.core.Finding;
import icarus.core.FindingRecord;
import icarus.core.IcarusModule;
import icarus.core.ModuleConfig;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;

public class IcarusTab {

    private final MontoyaApi api;
    private final ModuleConfig config;
    private final List<IcarusModule> modules;
    private final Orchestrator orchestrator;
    private final ThemeHelper themeHelper;

    private final JPanel mainPanel;
    private final DefaultTableModel tableModel;
    private final DefaultListModel<String> auditModel;

    public IcarusTab(MontoyaApi api, ModuleConfig config, List<IcarusModule> modules,
                     Orchestrator orchestrator) {
        this.api = api;
        this.config = config;
        this.modules = modules;
        this.orchestrator = orchestrator;
        this.themeHelper = new ThemeHelper(api.userInterface());

        this.mainPanel = new JPanel(new BorderLayout());
        themeHelper.applyTheme(this.mainPanel);

        this.tableModel = new DefaultTableModel(new String[]{"Hash", "Count", "Severity", "Module", "Type", "Path", "Description"}, 0) {
            @Override
            public boolean isCellEditable(int row, int column) { return false; }
        };
        this.auditModel = new DefaultListModel<>();

        buildUI();

        // Listen for new findings
        orchestrator.addListener(records -> {
            // Rebuild the whole table so counts update, but omit suppressed
            tableModel.setRowCount(0);
            for (var r : records) {
                if (!r.isSuppressed()) {
                    Finding f = r.getFinding();
                    tableModel.addRow(new Object[]{
                        f.similarityHash(),
                        r.getCount(),
                        f.severity().name(),
                        f.module(),
                        f.type(),
                        f.path(),
                        f.description()
                    });
                }
            }
            // Update audit log
            auditModel.clear();
            for (String log : orchestrator.getAuditLog()) {
                auditModel.addElement(log);
            }
        });
    }

    public Component getComponent() {
        return mainPanel;
    }

    private void buildUI() {
        JTabbedPane tabs = new JTabbedPane();
        themeHelper.applyTheme(tabs);

        // ── Settings Tab ──
        tabs.addTab("Settings", new SettingsPanel(api, config, themeHelper).getComponent());

        // ── Results Tab ──
        JPanel resultsPanel = new JPanel(new BorderLayout());
        themeHelper.applyTheme(resultsPanel);

        JTable table = new JTable(tableModel);
        themeHelper.styleTable(table);

        // Hide the Hash column
        table.removeColumn(table.getColumnModel().getColumn(0));

        table.setAutoCreateRowSorter(true);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);

        // Context menu for results table
        JPopupMenu popup = new JPopupMenu();
        themeHelper.applyTheme(popup);

        JMenuItem suppressItem = new JMenuItem("Suppress Finding");
        themeHelper.applyTheme(suppressItem);
        suppressItem.addActionListener(e -> {
            int viewRow = table.getSelectedRow();
            if (viewRow >= 0) {
                int modelRow = table.convertRowIndexToModel(viewRow);
                String hash = (String) tableModel.getValueAt(modelRow, 0);
                String reason = JOptionPane.showInputDialog(mainPanel, "Reason for suppression:", "Suppress", JOptionPane.PLAIN_MESSAGE);
                if (reason != null && !reason.trim().isEmpty()) {
                    orchestrator.suppressFinding(hash, reason);
                }
            }
        });

        JMenuItem clearItem = new JMenuItem("Clear display (doesn't suppress)");
        themeHelper.applyTheme(clearItem);
        clearItem.addActionListener(e -> tableModel.setRowCount(0));

        popup.add(suppressItem);
        popup.addSeparator();
        popup.add(clearItem);

        table.addMouseListener(new MouseAdapter() {
            public void mousePressed(MouseEvent e) { showPopup(e); }
            public void mouseReleased(MouseEvent e) { showPopup(e); }
            private void showPopup(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    int r = table.rowAtPoint(e.getPoint());
                    if (r >= 0 && r < table.getRowCount()) {
                        table.setRowSelectionInterval(r, r);
                    } else {
                        table.clearSelection();
                    }
                    popup.show(e.getComponent(), e.getX(), e.getY());
                }
            }
        });

        JScrollPane tableScroll = new JScrollPane(table);
        tableScroll.setBorder(BorderFactory.createEmptyBorder());
        themeHelper.applyTheme(tableScroll);
        resultsPanel.add(tableScroll, BorderLayout.CENTER);

        JPanel bottomBar = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        themeHelper.applyTheme(bottomBar);

        JButton btnPassiveLogs = new JButton("View Security Header Logs");
        themeHelper.styleButton(btnPassiveLogs);
        btnPassiveLogs.addActionListener(e -> {
            var passive = orchestrator.getPassiveFindings();
            if (passive.isEmpty()) {
                JOptionPane.showMessageDialog(mainPanel, "No security header logs recorded yet.");
            } else {
                orchestrator.showFindingsDialog(passive);
            }
        });

        JButton btnClearBtn = new JButton("Clear Results");
        themeHelper.styleButton(btnClearBtn);
        btnClearBtn.addActionListener(e -> {
            tableModel.setRowCount(0);
            orchestrator.clearPassiveFindings();
        });

        bottomBar.add(btnPassiveLogs);
        bottomBar.add(btnClearBtn);
        resultsPanel.add(bottomBar, BorderLayout.SOUTH);

        tabs.addTab("Results", resultsPanel);

        // ── Audit Log Tab ──
        JPanel auditPanel = new JPanel(new BorderLayout());
        themeHelper.applyTheme(auditPanel);

        JList<String> auditList = new JList<>(auditModel);
        auditList.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        auditList.setBackground(themeHelper.getBackgroundColor());
        auditList.setForeground(themeHelper.getForegroundColor());

        JScrollPane auditScroll = new JScrollPane(auditList);
        auditScroll.setBorder(BorderFactory.createEmptyBorder());
        themeHelper.applyTheme(auditScroll);

        auditPanel.add(auditScroll, BorderLayout.CENTER);
        tabs.addTab("Audit Log", auditPanel);

        mainPanel.add(tabs, BorderLayout.CENTER);
    }
}
