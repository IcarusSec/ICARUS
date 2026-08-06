package icarus.ui;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.proxy.ProxyHttpRequestResponse;
import icarus.Orchestrator;
import icarus.core.Finding;
import icarus.core.IcarusModule;
import icarus.core.ModuleConfig;

import burp.api.montoya.ui.editor.HttpRequestEditor;
import burp.api.montoya.ui.editor.HttpResponseEditor;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.event.ListSelectionEvent;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableRowSorter;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;
import java.util.regex.Pattern;

public class IcarusTab {

    // Proxy history can grow unbounded over a long engagement — cap what a single import
    // dialog loads into a Swing table to avoid OOM on large projects.
    private static final int PROXY_HISTORY_LIMIT = 2000;

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
        tabs.addTab("Settings", new SettingsPanel(api, config, themeHelper, orchestrator.autoAuth()).getComponent());

        // ── Results Tab ──
        JPanel resultsPanel = new JPanel(new BorderLayout());
        themeHelper.applyTheme(resultsPanel);

        JTable table = new JTable(tableModel);
        themeHelper.styleTable(table);

        // Hide the Hash column
        table.removeColumn(table.getColumnModel().getColumn(0));

        table.setAutoCreateRowSorter(true);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);

        JScrollPane tableScroll = new JScrollPane(table);
        tableScroll.setBorder(BorderFactory.createEmptyBorder());
        themeHelper.applyTheme(tableScroll);

        // Editors
        HttpRequestEditor reqEditor = api.userInterface().createHttpRequestEditor();
        HttpResponseEditor resEditor = api.userInterface().createHttpResponseEditor();

        JSplitPane reqResSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT);
        reqResSplit.setLeftComponent(reqEditor.uiComponent());
        reqResSplit.setRightComponent(resEditor.uiComponent());
        reqResSplit.setResizeWeight(0.5);
        reqResSplit.setBorder(null);

        // Update editors when selection changes
        table.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                int viewRow = table.getSelectedRow();
                if (viewRow >= 0) {
                    int modelRow = table.convertRowIndexToModel(viewRow);
                    String hash = (String) tableModel.getValueAt(modelRow, 0);
                    Finding finding = orchestrator.getFindingByHash(hash);
                    if (finding != null && finding.evidence() != null) {
                        reqEditor.setRequest(finding.evidence().request());
                        if (finding.evidence().response() != null) {
                            resEditor.setResponse(finding.evidence().response());
                        } else {
                            resEditor.setResponse(null);
                        }
                    } else {
                        reqEditor.setRequest(null);
                        resEditor.setResponse(null);
                    }
                }
            }
        });

        JSplitPane verticalSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT);
        verticalSplit.setTopComponent(tableScroll);
        verticalSplit.setBottomComponent(reqResSplit);
        verticalSplit.setResizeWeight(0.4);
        verticalSplit.setBorder(null);
        resultsPanel.add(verticalSplit, BorderLayout.CENTER);

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
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    int viewRow = table.getSelectedRow();
                    if (viewRow >= 0) {
                        int modelRow = table.convertRowIndexToModel(viewRow);
                        String hash = (String) tableModel.getValueAt(modelRow, 0);
                        Finding finding = orchestrator.getFindingByHash(hash);
                        if (finding != null) {
                            orchestrator.showEvidenceInteractive(finding);
                        }
                    }
                }
            }
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

        JPanel bottomBar = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        themeHelper.applyTheme(bottomBar);

        JButton btnPassiveLogs = new JButton("View Current Log");
        themeHelper.styleButton(btnPassiveLogs);
        btnPassiveLogs.addActionListener(e -> {
            int viewRow = table.getSelectedRow();
            if (viewRow >= 0) {
                int modelRow = table.convertRowIndexToModel(viewRow);
                String hash = (String) tableModel.getValueAt(modelRow, 0);
                Finding finding = orchestrator.getFindingByHash(hash);
                if (finding != null) {
                    orchestrator.showEvidenceInteractive(finding);
                }
            } else {
                JOptionPane.showMessageDialog(mainPanel, "Please select a finding first.");
            }
        });

        JButton btnClearBtn = new JButton("Clear Results");
        themeHelper.styleButton(btnClearBtn);
        btnClearBtn.addActionListener(e -> {
            tableModel.setRowCount(0);
            orchestrator.clearPassiveFindings();
        });

        JButton btnImportProxy = new JButton("Import from Proxy History");
        themeHelper.styleButton(btnImportProxy);
        btnImportProxy.addActionListener(e -> showProxyHistoryImportDialog());

        JButton btnEvidenceManager = new JButton("Manage Report Evidence");
        themeHelper.styleButton(btnEvidenceManager);
        btnEvidenceManager.addActionListener(e -> orchestrator.showEvidenceManager());

        JButton btnPreviewReport = new JButton("Preview");
        themeHelper.styleButton(btnPreviewReport);
        btnPreviewReport.addActionListener(e -> orchestrator.previewReport(mainPanel, btnPreviewReport));

        JButton btnGenerateReport = new JButton("Generate HTML Report");
        themeHelper.styleButton(btnGenerateReport);
        btnGenerateReport.addActionListener(e -> {
            List<Finding> reportFindings = orchestrator.getReportableFindings();
            if (reportFindings.isEmpty()) {
                JOptionPane.showMessageDialog(mainPanel,
                        "No evidence to include in a report yet — use \"Send to Reporter Creation\" or the Evidence Manager first.");
                return;
            }
            orchestrator.generateHtmlReportInteractive(mainPanel, btnGenerateReport, reportFindings);
        });

        JButton btnExportPdf = new JButton("Export PDF");
        themeHelper.styleButton(btnExportPdf);
        btnExportPdf.addActionListener(e -> {
            List<Finding> reportFindings = orchestrator.getReportableFindings();
            if (reportFindings.isEmpty()) {
                JOptionPane.showMessageDialog(mainPanel,
                        "No evidence to include in a report yet — use \"Send to Reporter Creation\" or the Evidence Manager first.");
                return;
            }
            orchestrator.exportPdfReportInteractive(mainPanel, btnExportPdf, reportFindings);
        });

        bottomBar.add(btnImportProxy);
        bottomBar.add(btnEvidenceManager);
        bottomBar.add(btnPreviewReport);
        bottomBar.add(btnGenerateReport);
        bottomBar.add(btnExportPdf);
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

    /**
     * Lets a tester pull any request/response the scanner missed into the evidence flow
     * without going through Repeater. Reuses {@link Orchestrator#createManualEvidence}, the
     * same entry point as the "Create Evidence" context-menu item, so Smart Evidence
     * detection and the Phase 1 dialog work identically either way.
     */
    private void showProxyHistoryImportDialog() {
        List<ProxyHttpRequestResponse> history = api.proxy().history();
        if (history.size() > PROXY_HISTORY_LIMIT) {
            history = history.subList(history.size() - PROXY_HISTORY_LIMIT, history.size());
        }
        List<ProxyHttpRequestResponse> entries = history;

        DefaultTableModel model = new DefaultTableModel(new String[]{"Host", "Method", "Path", "Status"}, 0) {
            @Override
            public boolean isCellEditable(int row, int column) { return false; }
        };
        for (ProxyHttpRequestResponse p : entries) {
            model.addRow(new Object[]{
                p.request().httpService().host(), p.request().method(), p.request().path(),
                p.hasResponse() ? p.response().statusCode() : "-"
            });
        }

        JTable table = new JTable(model);
        themeHelper.styleTable(table);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);

        TableRowSorter<DefaultTableModel> sorter = new TableRowSorter<>(model);
        table.setRowSorter(sorter);

        JTextField filterField = new JTextField();
        DocumentListener filterListener = new DocumentListener() {
            private void apply() {
                String text = filterField.getText();
                sorter.setRowFilter(text.isBlank() ? null : RowFilter.regexFilter("(?i)" + Pattern.quote(text)));
            }
            public void insertUpdate(DocumentEvent e) { apply(); }
            public void removeUpdate(DocumentEvent e) { apply(); }
            public void changedUpdate(DocumentEvent e) { apply(); }
        };
        filterField.getDocument().addDocumentListener(filterListener);

        JDialog dialog = new JDialog(api.userInterface().swingUtils().suiteFrame(),
                "Import from Proxy History (" + entries.size() + " requests)", true);
        dialog.setSize(900, 600);
        dialog.setLocationRelativeTo(mainPanel);
        dialog.setLayout(new BorderLayout());

        JPanel topBar = new JPanel(new BorderLayout(8, 0));
        topBar.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        topBar.add(new JLabel("Filter:"), BorderLayout.WEST);
        topBar.add(filterField, BorderLayout.CENTER);
        themeHelper.applyTheme(topBar);

        JScrollPane tableScroll = new JScrollPane(table);
        themeHelper.applyTheme(tableScroll);

        Runnable importSelected = () -> {
            int viewRow = table.getSelectedRow();
            if (viewRow < 0) return;
            int modelRow = table.convertRowIndexToModel(viewRow);
            ProxyHttpRequestResponse p = entries.get(modelRow);
            HttpRequestResponse rr = HttpRequestResponse.httpRequestResponse(p.finalRequest(), p.response());
            orchestrator.createManualEvidence(rr);
            dialog.dispose();
        };

        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) importSelected.run();
            }
        });

        JButton btnImport = new JButton("Import Selected");
        themeHelper.styleButton(btnImport);
        btnImport.addActionListener(e -> importSelected.run());

        JPanel bottomBar = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        themeHelper.applyTheme(bottomBar);
        bottomBar.add(btnImport);

        dialog.add(topBar, BorderLayout.NORTH);
        dialog.add(tableScroll, BorderLayout.CENTER);
        dialog.add(bottomBar, BorderLayout.SOUTH);
        dialog.setVisible(true);
    }
}
