package icarus.ui;

import burp.api.montoya.MontoyaApi;
import icarus.Orchestrator;
import icarus.core.Finding;
import icarus.core.IcarusModule;
import icarus.core.ModuleConfig;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;

/**
 * Main Burp Suite tab for ICARUS.
 */
public class IcarusTab {

    private final MontoyaApi api;
    private final ModuleConfig config;
    private final List<IcarusModule> modules;
    private final Orchestrator orchestrator;

    private final JPanel mainPanel;
    private final DefaultTableModel tableModel;

    public IcarusTab(MontoyaApi api, ModuleConfig config, List<IcarusModule> modules,
                     Orchestrator orchestrator) {
        this.api = api;
        this.config = config;
        this.modules = modules;
        this.orchestrator = orchestrator;

        this.mainPanel = new JPanel(new BorderLayout());
        this.tableModel = new DefaultTableModel(new String[]{"Severity", "Module", "Type", "Path", "Description"}, 0) {
            @Override
            public boolean isCellEditable(int row, int column) { return false; }
        };

        buildUI();

        // Listen for new findings
        orchestrator.addListener(findings -> {
            for (var f : findings) {
                tableModel.addRow(new Object[]{
                    f.severity().name(),
                    f.module(),
                    f.type(),
                    f.path(),
                    f.description()
                });
            }
        });
    }

    public Component getComponent() {
        return mainPanel;
    }

    private void buildUI() {
        JTabbedPane tabs = new JTabbedPane();

        // ── Settings Tab ──
        tabs.addTab("Settings", new SettingsPanel(api, config).getComponent());

        // ── Results Tab ──
        JPanel resultsPanel = new JPanel(new BorderLayout());
        JTable table = new JTable(tableModel);
        table.setAutoCreateRowSorter(true);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);

        // Context menu for results table
        JPopupMenu popup = new JPopupMenu();
        JMenuItem clearItem = new JMenuItem("Clear results");
        clearItem.addActionListener(e -> tableModel.setRowCount(0));
        popup.add(clearItem);

        table.addMouseListener(new MouseAdapter() {
            public void mousePressed(MouseEvent e) { showPopup(e); }
            public void mouseReleased(MouseEvent e) { showPopup(e); }
            private void showPopup(MouseEvent e) {
                if (e.isPopupTrigger()) popup.show(e.getComponent(), e.getX(), e.getY());
            }
        });

        resultsPanel.add(new JScrollPane(table), BorderLayout.CENTER);

        JPanel bottomBar = new JPanel(new FlowLayout(FlowLayout.RIGHT));

        JButton btnPassiveLogs = new JButton("View Security Header Logs");
        btnPassiveLogs.addActionListener(e -> {
            var passive = orchestrator.getPassiveFindings();
            if (passive.isEmpty()) {
                JOptionPane.showMessageDialog(mainPanel, "No security header logs recorded yet.");
            } else {
                orchestrator.showFindingsDialog(passive);
            }
        });

        JButton btnClear = new JButton("Clear Results");
        btnClear.addActionListener(e -> {
            tableModel.setRowCount(0);
            orchestrator.clearPassiveFindings();
        });

        bottomBar.add(btnPassiveLogs);
        bottomBar.add(btnClear);
        resultsPanel.add(bottomBar, BorderLayout.SOUTH);

        tabs.addTab("Results", resultsPanel);

        mainPanel.add(tabs, BorderLayout.CENTER);
    }
}
