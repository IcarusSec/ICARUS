package icarus.ui;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.proxy.ProxyHttpRequestResponse;
import icarus.Orchestrator;
import icarus.core.Finding;
import icarus.core.I18n;
import icarus.core.IcarusModule;
import icarus.core.ModuleConfig;
import icarus.mcp.IcarusMcpServer;

import burp.api.montoya.ui.editor.HttpRequestEditor;
import burp.api.montoya.ui.editor.HttpResponseEditor;

import javax.swing.*;
import javax.swing.event.ChangeListener;
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
    private final IcarusMcpServer mcpServer;
    private final ThemeHelper themeHelper;

    private final JPanel mainPanel;
    private final DefaultTableModel tableModel;
    private final DefaultListModel<String> auditModel;

    public IcarusTab(MontoyaApi api, ModuleConfig config, List<IcarusModule> modules,
                     Orchestrator orchestrator, IcarusMcpServer mcpServer) {
        this.api = api;
        this.config = config;
        this.modules = modules;
        this.orchestrator = orchestrator;
        this.mcpServer = mcpServer;
        this.themeHelper = new ThemeHelper(api.userInterface());

        this.mainPanel = new JPanel(new BorderLayout());
        themeHelper.applyTheme(this.mainPanel);

        this.tableModel = new DefaultTableModel(new String[]{I18n.t("ui.tab.results.col.hash"), I18n.t("ui.tab.results.col.count"), I18n.t("ui.tab.results.col.severity"), I18n.t("ui.tab.results.col.module"), I18n.t("ui.tab.results.col.type"), I18n.t("ui.tab.results.col.path"), I18n.t("ui.tab.results.col.description")}, 0) {
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

        // Build the tabs in the correct logical workflow order for a pentester:
        // 1. Resultados (Findings)
        // 2. Evidência (Evidence management)
        // 3. Relatórios (Report configuration)
        // 4. Base de Conhecimento (Reference)
        // 5. Configurações (Settings)
        // 6. Log de Auditoria (Debug logs)
        
        // Results Tab
        JPanel resultsPanel = new JPanel(new BorderLayout());
        themeHelper.applyTheme(resultsPanel);

        JTable table = new JTable(tableModel);
        themeHelper.styleTable(table);

        // Hide the Hash column
        table.removeColumn(table.getColumnModel().getColumn(0));

        table.setAutoCreateRowSorter(true);
        table.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);

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
                int[] viewRows = table.getSelectedRows();
                if (viewRows.length > 0) {
                    int modelRow = table.convertRowIndexToModel(viewRows[0]);
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
                } else {
                    reqEditor.setRequest(null);
                    resEditor.setResponse(null);
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

        JMenuItem suppressItem = new JMenuItem(I18n.t("ui.tab.results.menu.suppress"));
        themeHelper.applyTheme(suppressItem);
        suppressItem.addActionListener(e -> {
            SwingUtilities.invokeLater(() -> {
                int[] viewRows = table.getSelectedRows();
                if (viewRows.length > 0) {
                    String reason = JOptionPane.showInputDialog(mainPanel, I18n.t("ui.tab.results.dialog.suppress.reason"), I18n.t("ui.tab.results.dialog.suppress.title"), JOptionPane.PLAIN_MESSAGE);
                    if (reason != null && !reason.trim().isEmpty()) {
                        for (int viewRow : viewRows) {
                            int modelRow = table.convertRowIndexToModel(viewRow);
                            String hash = (String) tableModel.getValueAt(modelRow, 0);
                            orchestrator.suppressFinding(hash, reason);
                        }
                    }
                }
            });
        });

        JMenuItem combineItem = new JMenuItem(I18n.t("ui.tab.results.menu.combine"));
        themeHelper.applyTheme(combineItem);
        combineItem.addActionListener(e -> {
            SwingUtilities.invokeLater(() -> {
                int[] viewRows = table.getSelectedRows();
                if (viewRows.length > 1) {
                    JPanel panel = new JPanel(new GridLayout(2, 2, 5, 5));
                    JTextField titleField = new JTextField();
                    JTextField descField = new JTextField();
                    panel.add(new JLabel(I18n.t("ui.tab.results.dialog.combine.title_label")));
                    panel.add(titleField);
                    panel.add(new JLabel(I18n.t("ui.tab.results.dialog.combine.desc_label")));
                    panel.add(descField);

                    int result = JOptionPane.showConfirmDialog(mainPanel, panel, I18n.t("ui.tab.results.dialog.combine.title"), JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
                    if (result == JOptionPane.OK_OPTION) {
                        String title = titleField.getText().trim();
                        String desc = descField.getText().trim();
                        if (!title.isEmpty()) {
                            Finding firstFinding = null;
                            for (int viewRow : viewRows) {
                                int modelRow = table.convertRowIndexToModel(viewRow);
                                String hash = (String) tableModel.getValueAt(modelRow, 0);
                                Finding f = orchestrator.getFindingByHash(hash);
                                if (f != null && firstFinding == null) {
                                    firstFinding = f;
                                }
                                orchestrator.suppressFinding(hash, I18n.t("ui.tab.results.combine.default_reason"));
                            }
                            
                            if (firstFinding != null) {
                                Finding newFinding = Finding.builder("Manual", title)
                                    .description(desc)
                                    .severity(icarus.core.Severity.MEDIUM)
                                    .category(icarus.core.Category.MANUAL)
                                    .evidence(firstFinding.evidence())
                                    .build();
                                orchestrator.updateFinding(newFinding);
                            }
                        }
                    }
                } else {
                    JOptionPane.showMessageDialog(mainPanel, I18n.t("ui.tab.results.dialog.combine.error"));
                }
            });
        });

        JMenuItem clearItem = new JMenuItem(I18n.t("ui.tab.results.menu.clear"));
        themeHelper.applyTheme(clearItem);
        clearItem.addActionListener(e -> SwingUtilities.invokeLater(() -> tableModel.setRowCount(0)));

        popup.add(suppressItem);
        popup.add(combineItem);
        popup.addSeparator();
        popup.add(clearItem);
        
        // Keyboard Shortcuts
        table.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(KeyStroke.getKeyStroke("DELETE"), "suppressFinding");
        table.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(KeyStroke.getKeyStroke("BACK_SPACE"), "suppressFinding");
        table.getActionMap().put("suppressFinding", new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                suppressItem.doClick();
            }
        });
        
        table.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(KeyStroke.getKeyStroke("control shift E"), "sendToEvidence");
        table.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(KeyStroke.getKeyStroke("meta shift E"), "sendToEvidence");
        table.getActionMap().put("sendToEvidence", new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
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
        });

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
                        if (!table.isRowSelected(r)) {
                            table.setRowSelectionInterval(r, r);
                        }
                    } else {
                        table.clearSelection();
                    }
                    SwingUtilities.invokeLater(() -> popup.show(e.getComponent(), e.getX(), e.getY()));
                }
            }
        });

        JPanel bottomBar = new JPanel(new BorderLayout());
        themeHelper.applyTheme(bottomBar);

        JPanel dataActions = new JPanel(new FlowLayout(FlowLayout.LEFT));
        themeHelper.applyTheme(dataActions);

        JPanel reportActions = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        themeHelper.applyTheme(reportActions);

        JButton btnPassiveLogs = new JButton(I18n.t("ui.tab.results.btn.view_log"));
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
                JOptionPane.showMessageDialog(mainPanel, I18n.t("ui.tab.results.error.select_first"));
            }
        });

        JButton btnClearBtn = new JButton(I18n.t("ui.tab.results.btn.clear"));
        themeHelper.styleButton(btnClearBtn);
        btnClearBtn.addActionListener(e -> {
            tableModel.setRowCount(0);
            orchestrator.clearPassiveFindings();
        });

        JButton btnImportProxy = new JButton(I18n.t("ui.tab.results.btn.import_proxy"));
        themeHelper.styleButton(btnImportProxy);
        btnImportProxy.addActionListener(e -> showProxyHistoryImportDialog());

        JButton btnEvidenceManager = new JButton(I18n.t("ui.tab.results.btn.manage_evidence"));
        themeHelper.styleButton(btnEvidenceManager);
        // Note: With the new Evidence Manager Tab, this button could switch to that tab instead of opening a modal.
        // We'll update the ActionListener once we move it to a tab, but for now it calls orchestrator.showEvidenceManager() or we can switch tab.
        // Let's assume we will switch tabs later.
        btnEvidenceManager.addActionListener(e -> {
            int idx = tabs.indexOfTab(I18n.t("ui.tab.evidence"));
            if (idx >= 0) {
                tabs.setSelectedIndex(idx);
            }
        });

        JButton btnPreviewReport = new JButton(I18n.t("ui.tab.results.btn.preview"));
        themeHelper.styleButton(btnPreviewReport);
        btnPreviewReport.addActionListener(e -> orchestrator.previewReport(mainPanel, btnPreviewReport));

        JButton btnGenerateReport = new JButton(I18n.t("ui.tab.results.btn.generate_html"));
        btnGenerateReport.setBackground(new Color(255, 102, 51)); // Primary accent (ICARUS orange)
        btnGenerateReport.setForeground(Color.WHITE);
        btnGenerateReport.setFocusPainted(false);
        // We keep themeHelper from overriding background by styling it partially.
        // Wait, themeHelper.styleButton overrides background and foreground.
        // We do it after themeHelper.styleButton.
        themeHelper.styleButton(btnGenerateReport);
        btnGenerateReport.setBackground(new Color(255, 102, 51));
        btnGenerateReport.setForeground(Color.WHITE);
        
        btnGenerateReport.addActionListener(e -> {
            List<Finding> reportFindings = orchestrator.getReportableFindings();
            if (reportFindings.isEmpty()) {
                JOptionPane.showMessageDialog(mainPanel,
                        I18n.t("ui.tab.results.error.no_evidence"));
                return;
            }
            orchestrator.generateHtmlReportInteractive(mainPanel, btnGenerateReport, reportFindings);
        });

        JButton btnExportPdf = new JButton(I18n.t("ui.tab.results.btn.export_pdf"));
        themeHelper.styleButton(btnExportPdf);
        btnExportPdf.addActionListener(e -> {
            List<Finding> reportFindings = orchestrator.getReportableFindings();
            if (reportFindings.isEmpty()) {
                JOptionPane.showMessageDialog(mainPanel,
                        I18n.t("ui.tab.results.error.no_evidence"));
                return;
            }
            orchestrator.exportPdfReportInteractive(mainPanel, btnExportPdf, reportFindings);
        });

        dataActions.add(btnImportProxy);
        dataActions.add(btnPassiveLogs);
        dataActions.add(btnClearBtn);

        reportActions.add(btnEvidenceManager);
        reportActions.add(btnPreviewReport);
        reportActions.add(btnGenerateReport);
        reportActions.add(btnExportPdf);

        bottomBar.add(dataActions, BorderLayout.WEST);
        bottomBar.add(reportActions, BorderLayout.EAST);
        resultsPanel.add(bottomBar, BorderLayout.SOUTH);

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

        icarus.ui.evidence.EvidenceManagerTab evidenceManagerTab = new icarus.ui.evidence.EvidenceManagerTab(orchestrator, config, api);
        
        // Add tabs in logical workflow order
        tabs.addTab(I18n.t("ui.tab.results"), resultsPanel);
        tabs.addTab(I18n.t("ui.tab.evidence"), evidenceManagerTab.getUiComponent());
        tabs.addTab(I18n.t("ui.tab.reporting"), new ReportingSettingsTab(api, config, themeHelper).getUiComponent());
        tabs.addTab(I18n.t("ui.tab.kb"), new KnowledgeBaseTab(api, orchestrator).getComponent());
        tabs.addTab(I18n.t("ui.tab.settings"), new SettingsPanel(api, config, themeHelper, orchestrator.autoAuth(), mcpServer).getComponent());
        tabs.addTab(I18n.t("ui.tab.audit_log"), auditPanel);

        tabs.addChangeListener(e -> {
            if (tabs.getSelectedComponent() == evidenceManagerTab.getUiComponent()) {
                evidenceManagerTab.reload();
            }
        });

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

        DefaultTableModel model = new DefaultTableModel(new String[]{I18n.t("ui.import.col.host"), I18n.t("ui.import.col.method"), I18n.t("ui.import.col.path"), I18n.t("ui.import.col.status")}, 0) {
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
                I18n.t("ui.import.title") + " (" + entries.size() + " requests)", true);
        dialog.setSize(900, 600);
        dialog.setLocationRelativeTo(mainPanel);
        dialog.setLayout(new BorderLayout());

        JPanel topBar = new JPanel(new BorderLayout(8, 0));
        topBar.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        topBar.add(new JLabel(I18n.t("ui.import.filter")), BorderLayout.WEST);
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

        JButton btnImport = new JButton(I18n.t("ui.import.btn.import"));
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
