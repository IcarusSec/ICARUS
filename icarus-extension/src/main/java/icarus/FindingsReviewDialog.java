package icarus;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.handler.*;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.ui.contextmenu.ContextMenuItemsProvider;
import burp.api.montoya.ui.contextmenu.ContextMenuEvent;
import burp.api.montoya.ui.contextmenu.MessageEditorHttpRequestResponse;
import icarus.autoauth.AutoAuthModule;
import icarus.core.*;
import icarus.evidence.EvidenceCapture;
import icarus.evidence.PdfReportGenerator;
import icarus.evidence.ProjectStateCodec;
import icarus.evidence.ReportGenerator;
import icarus.modules.PassiveErrorModule;
import icarus.ui.ToastNotification;
import javax.swing.*;
import java.awt.*;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.nio.file.Files;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

public class FindingsReviewDialog {
    private final Orchestrator owner;
    private final MontoyaApi api;

    public FindingsReviewDialog(Orchestrator owner, MontoyaApi api) {
        this.owner = owner;
        this.api = api;
    }

    public void showFindingsDialog(List<FindingRecord> records) {
        java.awt.Frame parent = api.userInterface().swingUtils().suiteFrame();
        javax.swing.JFrame dialog = new javax.swing.JFrame(I18n.t("findings.review.title"));
        if (parent != null) dialog.setIconImage(parent.getIconImage());
        dialog.setSize(1200, 800);
        dialog.setDefaultCloseOperation(javax.swing.WindowConstants.DISPOSE_ON_CLOSE);
        dialog.setLocationRelativeTo(null);

        String[] cols = {
            I18n.t("findings.review.col.count"), I18n.t("findings.review.col.severity"), 
            I18n.t("findings.review.col.module"), I18n.t("findings.review.col.type"), 
            I18n.t("findings.review.col.path"), I18n.t("findings.review.col.description")
        };
        Object[][] data = new Object[records.size()][6];
        for (int i = 0; i < records.size(); i++) {
            FindingRecord r = records.get(i);
            Finding f = r.getFinding();
            data[i] = new Object[]{r.getCount(), f.severity().name(), f.module(), f.type(), f.path(), f.description()};
        }

        JTable table = new JTable(data, cols) {
            @Override
            public boolean isCellEditable(int row, int column) { return false; }
        };
        table.setAutoCreateRowSorter(true);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);

        // Modernize table
        table.setRowHeight(24);
        table.putClientProperty("FlatLaf.style", "showHorizontalLines: true; showVerticalLines: false; alternateRowColor: $Table.alternateRowColor");
        table.getTableHeader().putClientProperty("FlatLaf.style", "hoverBackground: $Table.hoverBackground");
        
        JPanel topPanel = new JPanel(new BorderLayout(0, 10));
        topPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 5, 10));

        // Add filtering
        JPanel filterPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        JLabel lblFilter = new JLabel(I18n.t("findings.review.label.filter"));
        lblFilter.setFont(lblFilter.getFont().deriveFont(Font.BOLD));
        filterPanel.add(lblFilter);
        
        JTextField txtFilter = new JTextField(30);
        txtFilter.putClientProperty("FlatLaf.style", "arc: 8; margin: 4,8,4,8;");
        txtFilter.putClientProperty("JTextField.placeholderText", "Pesquisar (Regex)...");
        filterPanel.add(txtFilter);
        
        javax.swing.table.TableRowSorter<javax.swing.table.TableModel> sorter = new javax.swing.table.TableRowSorter<>(table.getModel());
        table.setRowSorter(sorter);
        txtFilter.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e) { applyFilter(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e) { applyFilter(); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { applyFilter(); }
            private void applyFilter() {
                String text = txtFilter.getText();
                if (text.trim().length() == 0) sorter.setRowFilter(null);
                else sorter.setRowFilter(RowFilter.regexFilter("(?i)" + text));
            }
        });

        topPanel.add(filterPanel, BorderLayout.NORTH);
        
        JScrollPane tableScroll = new JScrollPane(table);
        tableScroll.setBorder(BorderFactory.createLineBorder(new Color(130, 130, 130, 60), 1, true));
        tableScroll.putClientProperty("FlatLaf.style", "arc: 8;");
        topPanel.add(tableScroll, BorderLayout.CENTER);

        // Editors for Request/Response
        burp.api.montoya.ui.editor.HttpRequestEditor reqEditor = api.userInterface().createHttpRequestEditor(burp.api.montoya.ui.editor.EditorOptions.READ_ONLY);
        burp.api.montoya.ui.editor.HttpResponseEditor resEditor = api.userInterface().createHttpResponseEditor(burp.api.montoya.ui.editor.EditorOptions.READ_ONLY);

        JTabbedPane editorsTab = new JTabbedPane();
        editorsTab.putClientProperty("FlatLaf.style", "showTabSeparators: true;");
        editorsTab.addTab(I18n.t("findings.review.tab.request"), reqEditor.uiComponent());
        editorsTab.addTab(I18n.t("findings.review.tab.response"), resEditor.uiComponent());

        table.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                int viewRow = table.getSelectedRow();
                if (viewRow >= 0) {
                    int modelRow = table.convertRowIndexToModel(viewRow);
                    Finding f = records.get(modelRow).getFinding();
                    if (f.evidence() != null) {
                        reqEditor.setRequest(f.evidence().request());
                        if (f.evidence().response() != null) {
                            resEditor.setResponse(f.evidence().response());
                        } else {
                            resEditor.setResponse(burp.api.montoya.http.message.responses.HttpResponse.httpResponse(""));
                        }
                    } else {
                        reqEditor.setRequest(burp.api.montoya.http.message.requests.HttpRequest.httpRequest(""));
                        resEditor.setResponse(burp.api.montoya.http.message.responses.HttpResponse.httpResponse(""));
                    }
                }
            }
        });
        
        JPanel bottomPanelWrapper = new JPanel(new BorderLayout());
        bottomPanelWrapper.setBorder(BorderFactory.createEmptyBorder(5, 10, 10, 10));
        bottomPanelWrapper.add(editorsTab, BorderLayout.CENTER);

        JSplitPane splitPane = new JSplitPane(JSplitPane.VERTICAL_SPLIT, topPanel, bottomPanelWrapper);
        splitPane.setResizeWeight(0.5);
        splitPane.setDividerSize(6);
        splitPane.putClientProperty("FlatLaf.style", "continuousLayout: true;");
        dialog.add(splitPane, BorderLayout.CENTER);

        JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 10));
        btnPanel.setBorder(BorderFactory.createEmptyBorder(0, 10, 10, 10));

        JButton btnRepeater = new JButton(I18n.t("findings.review.btn.repeater"), icarus.evidence.EvidenceUiHelpers.createIcon("refresh-cw"));
        btnRepeater.putClientProperty("FlatLaf.style", "arc: 8; iconTextGap: 8;");
        JButton btnEvidence = new JButton(I18n.t("findings.review.btn.evidence"), icarus.evidence.EvidenceUiHelpers.createIcon("image"));
        btnEvidence.putClientProperty("FlatLaf.style", "arc: 8; iconTextGap: 8;");
        JButton btnReport = new JButton(I18n.t("findings.review.btn.report"), icarus.evidence.EvidenceUiHelpers.createIcon("file-text"));
        btnReport.putClientProperty("FlatLaf.style", "arc: 8; iconTextGap: 8;");
        JButton btnClose = new JButton(I18n.t("findings.review.btn.close"), icarus.evidence.EvidenceUiHelpers.createIcon("x"));
        btnClose.putClientProperty("FlatLaf.style", "arc: 8; iconTextGap: 8;");

        btnRepeater.addActionListener(e -> {
            int row = table.getSelectedRow();
            if (row >= 0) {
                int modelRow = table.convertRowIndexToModel(row);
                Finding f = records.get(modelRow).getFinding();
                if (f.evidence() != null) {
                    api.repeater().sendToRepeater(f.evidence().request(), buildTabName(f, modelRow + 1));
                    JOptionPane.showMessageDialog(dialog, I18n.t("findings.review.msg.sentToRepeater"));
                } else {
                    JOptionPane.showMessageDialog(dialog, I18n.t("findings.review.msg.noEvidence"));
                }
            }
        });

        btnEvidence.addActionListener(e -> {
            int row = table.getSelectedRow();
            if (row >= 0) {
                int modelRow = table.convertRowIndexToModel(row);
                Finding f = records.get(modelRow).getFinding();
                if (f.evidence() != null) {
                    owner.showEvidenceInteractive(f);
                } else {
                    JOptionPane.showMessageDialog(dialog, I18n.t("findings.review.msg.noEvidence"));
                }
            }
        });

        btnReport.addActionListener(e -> owner.generateHtmlReportInteractive(dialog, btnReport, owner.getReportableFindings()));

        btnClose.addActionListener(e -> dialog.dispose());

        btnPanel.add(btnRepeater);
        btnPanel.add(btnEvidence);
        btnPanel.add(btnReport);
        btnPanel.add(Box.createRigidArea(new Dimension(16, 0)));
        btnPanel.add(btnClose);
        dialog.add(btnPanel, BorderLayout.SOUTH);

        dialog.setVisible(true);
    }

    public static Severity parseSeverity(String value) {
        try {
            return Severity.valueOf(value);
        } catch (Exception e) {
            return Severity.INFO;
        }
    }

    private String buildTabName(Finding finding, int index) {
        String prefix = "IC";
        String label = finding.shortLabel();
        String path = finding.path();

        if (path != null && path.startsWith("$.")) {
            String[] parts = path.substring(2).split("\\.");
            int start = Math.max(0, parts.length - 2);
            path = String.join(".", java.util.Arrays.copyOfRange(parts, start, parts.length));
        }
        if (path == null || path.isBlank()) path = "root";
        if (path.length() > 10) path = path.substring(0, 10);

        String name = prefix + "-" + label + "-" + path + "-" + String.format("%02d", index);
        return name.length() <= 28 ? name : name.substring(0, 28);
    }

}
