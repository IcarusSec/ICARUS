package icarus.ui.evidence;

import burp.api.montoya.MontoyaApi;
import icarus.Orchestrator;
import icarus.core.Finding;
import icarus.core.ModuleConfig;
import icarus.core.ReportTemplateConfig;
import icarus.core.Severity;
import icarus.evidence.EvidenceCapture;
import icarus.ui.ThemeHelper;

import javax.swing.*;
import java.awt.*;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class EvidenceManagerTab {

    private static final DataFlavor EVIDENCE_DRAG_FLAVOR =
            new DataFlavor(EvidenceCapture.CapturedEvidence.class, "ICARUS Evidence Card");

    private final MontoyaApi api;
    private final ModuleConfig config;
    private final EvidenceCapture evidenceCapture;
    private final Orchestrator orchestrator;
    private final ThemeHelper themeHelper;
    private final JPanel mainPanel;

    public EvidenceManagerTab(MontoyaApi api, ModuleConfig config, EvidenceCapture evidenceCapture, Orchestrator orchestrator, ThemeHelper themeHelper) {
        this.api = api;
        this.config = config;
        this.evidenceCapture = evidenceCapture;
        this.orchestrator = orchestrator;
        this.themeHelper = themeHelper;
        this.mainPanel = new JPanel(new BorderLayout());
        buildUI();
    }

    public Component getComponent() {
        return mainPanel;
    }

    private void buildUI() {
        List<String> hashOrder = new ArrayList<>();
        Map<String, List<EvidenceCapture.CapturedEvidence>> groups = new LinkedHashMap<>();

        DefaultListModel<String> masterModel = new DefaultListModel<>();
        JList<String> masterList = new JList<>(masterModel);
        masterList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        masterList.setCellRenderer((list, hash, index, isSelected, hasFocus) -> {
            JLabel l = new JLabel(findingLabel(hash, groups));
            l.setOpaque(true);
            l.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
            l.setBackground(isSelected ? list.getSelectionBackground() : list.getBackground());
            l.setForeground(isSelected ? list.getSelectionForeground() : list.getForeground());
            return l;
        });
        JScrollPane masterScroll = new JScrollPane(masterList);

        JPanel detailPanel = new JPanel();
        detailPanel.setLayout(new BoxLayout(detailPanel, BoxLayout.Y_AXIS));
        JScrollPane detailScroll = new JScrollPane(detailPanel);
        detailScroll.getVerticalScrollBar().setUnitIncrement(16);

        Runnable[] refreshAllRef = new Runnable[1];

        Runnable reload = () -> {
            String selectedHash = masterList.getSelectedValue();
            hashOrder.clear();
            groups.clear();
            for (var ce : evidenceCapture.getCaptured()) {
                String hash = ce.finding().similarityHash();
                if (!groups.containsKey(hash)) hashOrder.add(hash);
                groups.computeIfAbsent(hash, h -> new ArrayList<>()).add(ce);
            }
            masterModel.clear();
            hashOrder.forEach(masterModel::addElement);
            if (selectedHash != null && hashOrder.contains(selectedHash)) {
                masterList.setSelectedValue(selectedHash, true);
            } else if (!hashOrder.isEmpty()) {
                masterList.setSelectedIndex(0);
            }
        };

        Runnable refreshDetail = () -> {
            detailPanel.removeAll();
            String hash = masterList.getSelectedValue();
            if (hash == null) {
                JLabel empty = new JLabel("Select a finding on the left to manage its evidence.");
                empty.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
                detailPanel.add(empty);
            } else {
                List<EvidenceCapture.CapturedEvidence> group = groups.get(hash);
                detailPanel.add(buildFindingHeader(hash, group, groups, refreshAllRef));
                detailPanel.add(Box.createRigidArea(new Dimension(0, 8)));
                
                if (config.getBool("retest.enabled", false)) {
                    detailPanel.add(buildRetestStatusRow(hash));
                    detailPanel.add(Box.createRigidArea(new Dimension(0, 8)));
                }
                for (int i = 0; i < group.size(); i++) {
                    detailPanel.add(buildEvidenceCard(group, i, hashOrder, groups, () -> refreshAllRef[0].run()));
                    detailPanel.add(Box.createRigidArea(new Dimension(0, 8)));
                }
            }
            detailPanel.revalidate();
            detailPanel.repaint();
        };

        masterList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) refreshDetail.run();
        });

        refreshAllRef[0] = () -> { reload.run(); refreshDetail.run(); };
        refreshAllRef[0].run();

        JButton btnGroupUp = new JButton("▲ Move Finding Up");
        themeHelper.styleButton(btnGroupUp);
        btnGroupUp.addActionListener(e -> {
            int idx = masterList.getSelectedIndex();
            if (idx > 0) {
                Collections.swap(hashOrder, idx, idx - 1);
                syncGroupsToCapture(hashOrder, groups);
                refreshAllRef[0].run();
            }
        });
        JButton btnGroupDown = new JButton("▼ Move Finding Down");
        themeHelper.styleButton(btnGroupDown);
        btnGroupDown.addActionListener(e -> {
            int idx = masterList.getSelectedIndex();
            if (idx >= 0 && idx < hashOrder.size() - 1) {
                Collections.swap(hashOrder, idx, idx + 1);
                syncGroupsToCapture(hashOrder, groups);
                refreshAllRef[0].run();
            }
        });

        masterList.setDropMode(DropMode.ON);
        masterList.setTransferHandler(new TransferHandler() {
            @Override
            public boolean canImport(TransferSupport support) {
                return support.isDrop() && support.isDataFlavorSupported(EVIDENCE_DRAG_FLAVOR);
            }

            @Override
            public boolean importData(TransferSupport support) {
                if (!canImport(support)) return false;
                try {
                    var dragged = (EvidenceCapture.CapturedEvidence) support.getTransferable().getTransferData(EVIDENCE_DRAG_FLAVOR);
                    int dropIndex = ((JList.DropLocation) support.getDropLocation()).getIndex();
                    if (dropIndex < 0 || dropIndex >= hashOrder.size()) return false;
                    String targetHash = hashOrder.get(dropIndex);
                    if (targetHash.equals(dragged.finding().similarityHash())) return false; // dropped on its own finding
                    Finding targetFinding = orchestrator.getFindingByHash(targetHash);
                    if (targetFinding == null) return false;
                    evidenceCapture.moveToFinding(dragged, targetFinding);
                    refreshAllRef[0].run();
                    return true;
                } catch (Exception ex) {
                    return false;
                }
            }
        });

        JPanel masterButtons = new JPanel(new GridLayout(2, 1, 0, 4));
        themeHelper.applyTheme(masterButtons);
        masterButtons.add(btnGroupUp);
        masterButtons.add(btnGroupDown);

        JPanel masterPanel = new JPanel(new BorderLayout(0, 4));
        themeHelper.applyTheme(masterPanel);
        masterPanel.add(new JLabel("Findings (report order)"), BorderLayout.NORTH);
        masterPanel.add(masterScroll, BorderLayout.CENTER);
        masterPanel.add(masterButtons, BorderLayout.SOUTH);

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, masterPanel, detailScroll);
        split.setResizeWeight(0.28);
        split.setDividerSize(10); // Better size for one-touch expandable
        split.setOneTouchExpandable(true);
        split.setBorder(BorderFactory.createEmptyBorder());

        var initialRtc = icarus.core.ReportTemplateConfig.fromConfig(config);
        String initialSummary = initialRtc.sections().stream()
                .filter(s -> "Executive Summary".equals(s.title()))
                .map(icarus.core.ReportTemplateConfig.Section::content)
                .findFirst().orElse("");
        JTextArea txtSummary = new JTextArea(initialSummary, 3, 0);
        txtSummary.setLineWrap(true);
        txtSummary.setWrapStyleWord(true);
        themeHelper.styleTextArea(txtSummary);
        txtSummary.setBorder(BorderFactory.createTitledBorder("Report Notes (optional executive summary, shown at the top of the report)"));
        txtSummary.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e) { save(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e) { save(); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { save(); }
            private void save() {
                var rtc = icarus.core.ReportTemplateConfig.fromConfig(config);
                List<icarus.core.ReportTemplateConfig.Section> sections = new ArrayList<>(rtc.sections());
                var updated = new icarus.core.ReportTemplateConfig.Section("Executive Summary", txtSummary.getText());
                int idx = -1;
                for (int i = 0; i < sections.size(); i++) {
                    if ("Executive Summary".equals(sections.get(i).title())) { idx = i; break; }
                }
                if (idx >= 0) sections.set(idx, updated); else sections.add(0, updated);
                rtc.setSections(sections);
                rtc.saveTo(config);
            }
        });
        JScrollPane summaryScroll = new JScrollPane(txtSummary);
        summaryScroll.setPreferredSize(new Dimension(0, 80));

        JPanel topPanel = new JPanel(new BorderLayout());
        themeHelper.applyTheme(topPanel);

        JButton btnToggleSummary = new JButton("▼ Executive Summary");
        themeHelper.styleButton(btnToggleSummary);
        
        JPanel summaryContainer = new JPanel(new BorderLayout());
        summaryContainer.add(summaryScroll, BorderLayout.CENTER);
        
        btnToggleSummary.addActionListener(e -> {
            boolean isVisible = summaryContainer.isVisible();
            summaryContainer.setVisible(!isVisible);
            btnToggleSummary.setText(isVisible ? "▶ Executive Summary" : "▼ Executive Summary");
            topPanel.revalidate();
        });

        JLabel hint = new JLabel("  Select a finding on the left; manage its evidence cards on the right.");
        hint.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));

        JCheckBox chkRetest = new JCheckBox("Retest Mode (show resolution status; suppress configured sections in reports)",
                config.getBool("retest.enabled", false));
        themeHelper.applyTheme(chkRetest);
        chkRetest.addActionListener(e -> {
            config.set("retest.enabled", chkRetest.isSelected());
            api.persistence().extensionData().setString("config", config.serialize());
            refreshAllRef[0].run();
        });

        JButton btnPopOut = new JButton("Pop Out Evidence Manager");
        themeHelper.styleButton(btnPopOut);

        JPanel summaryHeader = new JPanel(new FlowLayout(FlowLayout.LEFT));
        themeHelper.applyTheme(summaryHeader);
        summaryHeader.add(btnToggleSummary);
        summaryHeader.add(btnPopOut);

        topPanel.add(summaryHeader, BorderLayout.NORTH);
        topPanel.add(summaryContainer, BorderLayout.CENTER);
        
        JPanel bottomOfTop = new JPanel(new BorderLayout());
        themeHelper.applyTheme(bottomOfTop);
        JPanel chkPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        themeHelper.applyTheme(chkPanel);
        chkPanel.add(chkRetest);
        bottomOfTop.add(chkPanel, BorderLayout.NORTH);
        bottomOfTop.add(hint, BorderLayout.SOUTH);
        topPanel.add(bottomOfTop, BorderLayout.SOUTH);

        JPanel evidenceTab = new JPanel(new BorderLayout());
        themeHelper.applyTheme(evidenceTab);
        evidenceTab.add(topPanel, BorderLayout.NORTH);

        JTabbedPane compactTabs = new JTabbedPane();
        themeHelper.applyTheme(compactTabs);
        
        boolean[] isCompactMode = { false };

        Runnable updateLayoutMode = () -> {
            int width = evidenceTab.getWidth();
            if (width <= 0) return; // not laid out yet

            boolean shouldBeCompact = width < 1000;
            if (isCompactMode[0] == shouldBeCompact && evidenceTab.getComponentCount() > 1) return; // already in correct state

            isCompactMode[0] = shouldBeCompact;
            evidenceTab.remove(split);
            evidenceTab.remove(compactTabs);
            if (shouldBeCompact) {
                compactTabs.addTab("1. Findings List", masterPanel);
                compactTabs.addTab("2. Evidence Details", detailScroll);
                evidenceTab.add(compactTabs, BorderLayout.CENTER);
            } else {
                split.setLeftComponent(masterPanel);
                split.setRightComponent(detailScroll);
                evidenceTab.add(split, BorderLayout.CENTER);
            }
            evidenceTab.revalidate();
            evidenceTab.repaint();
        };

        evidenceTab.addComponentListener(new java.awt.event.ComponentAdapter() {
            @Override
            public void componentResized(java.awt.event.ComponentEvent e) {
                updateLayoutMode.run();
            }
        });

        // Apply initial layout fallback just in case
        evidenceTab.add(split, BorderLayout.CENTER);

        // When a finding is double clicked in compact mode, auto-switch to Evidence Details tab
        masterList.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && isCompactMode[0]) {
                    compactTabs.setSelectedIndex(1);
                }
            }
        });

        // Pop out logic
        btnPopOut.addActionListener(e -> {
            JDialog popOutFrame = new JDialog(api.userInterface().swingUtils().suiteFrame(), "ICARUS Evidence Manager (Detached)", false);
            Dimension screenSize = Toolkit.getDefaultToolkit().getScreenSize();
            popOutFrame.setSize(Math.min(1200, screenSize.width - 50), Math.min(800, screenSize.height - 100));
            popOutFrame.setLocationRelativeTo(null);
            popOutFrame.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);

            // Re-parent the evidenceTab to the detached window
            popOutFrame.add(evidenceTab);
            
            popOutFrame.addWindowListener(new java.awt.event.WindowAdapter() {
                @Override
                public void windowClosed(java.awt.event.WindowEvent e) {
                    // When closed, re-parent the evidenceTab back to the main GUI
                    JTabbedPane parentTabs = (JTabbedPane) mainPanel.getComponent(0);
                    parentTabs.setComponentAt(0, evidenceTab);
                }
            });

            // Replace the evidence tab content with a placeholder button to pop it back in
            JPanel placeholder = new JPanel(new GridBagLayout());
            JButton btnRestore = new JButton("Restore Evidence Manager to Tab");
            themeHelper.styleButton(btnRestore);
            btnRestore.addActionListener(ev -> popOutFrame.dispose()); // will trigger windowClosed
            placeholder.add(btnRestore);
            
            JTabbedPane parentTabs = (JTabbedPane) mainPanel.getComponent(0);
            parentTabs.setComponentAt(0, placeholder);

            popOutFrame.setVisible(true);
        });

        JTabbedPane tabs = new JTabbedPane();
        themeHelper.applyTheme(tabs);
        tabs.addTab("Evidence", evidenceTab);
        tabs.addTab("Report Details", buildReportDetailsPanel(initialRtc));
        mainPanel.add(tabs, BorderLayout.CENTER);

        JButton btnImportProject = new JButton("Import Project…");
        themeHelper.styleButton(btnImportProject);
        btnImportProject.addActionListener(e -> orchestrator.importProjectStateInteractive(mainPanel, btnImportProject, () -> refreshAllRef[0].run()));

        JButton btnExportProject = new JButton("Export Project…");
        themeHelper.styleButton(btnExportProject);
        btnExportProject.addActionListener(e -> orchestrator.exportProjectStateInteractive(mainPanel, btnExportProject));
        
        JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        themeHelper.applyTheme(btnPanel);
        btnPanel.add(btnImportProject);
        btnPanel.add(btnExportProject);

        JButton btnPreviewReport = new JButton("Preview");
        themeHelper.styleButton(btnPreviewReport);
        btnPreviewReport.addActionListener(e -> orchestrator.previewReport(mainPanel, btnPreviewReport));

        JButton btnGenerateReport = new JButton("Generate HTML Report");
        themeHelper.styleButton(btnGenerateReport);
        btnGenerateReport.setBackground(new Color(62, 123, 184));
        btnGenerateReport.setForeground(Color.WHITE);
        btnGenerateReport.addActionListener(e -> {
            List<Finding> reportFindings = orchestrator.getReportableFindings();
            if (reportFindings.isEmpty()) {
                JOptionPane.showMessageDialog(mainPanel, "No evidence to include in a report yet.");
                return;
            }
            orchestrator.generateHtmlReportInteractive(mainPanel, btnGenerateReport, reportFindings);
        });

        JButton btnExportPdf = new JButton("Export PDF");
        themeHelper.styleButton(btnExportPdf);
        btnExportPdf.addActionListener(e -> {
            List<Finding> reportFindings = orchestrator.getReportableFindings();
            if (reportFindings.isEmpty()) {
                JOptionPane.showMessageDialog(mainPanel, "No evidence to include in a report yet.");
                return;
            }
            orchestrator.exportPdfReportInteractive(mainPanel, btnExportPdf, reportFindings);
        });

        btnPanel.add(btnPreviewReport);
        btnPanel.add(btnGenerateReport);
        btnPanel.add(btnExportPdf);
        
        mainPanel.add(btnPanel, BorderLayout.SOUTH);

        // Auto-update when findings change
        orchestrator.addListener(records -> {
            SwingUtilities.invokeLater(() -> refreshAllRef[0].run());
        });
        
        // Auto-update when tab is shown
        mainPanel.addComponentListener(new java.awt.event.ComponentAdapter() {
            @Override
            public void componentShown(java.awt.event.ComponentEvent e) {
                refreshAllRef[0].run();
            }
        });
    }

    private JPanel buildReportDetailsPanel(ReportTemplateConfig initialRtc) {
        String[] labels = {"Project / Report Name:", "Author:", "Revisor:", "Ambient / Environment:", "Date:"};
        String[] keys = {"projectName", "author", "revisor", "ambient", "reportDate"};

        String existingDate = initialRtc.variables().get("reportDate");
        if (existingDate == null || existingDate.isBlank()) {
            initialRtc.variables().put("reportDate", java.time.LocalDate.now().toString());
            initialRtc.saveTo(config);
        }

        JPanel form = new JPanel(new GridBagLayout());
        form.setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));
        themeHelper.applyTheme(form);
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(6, 6, 6, 6);
        gbc.fill = GridBagConstraints.HORIZONTAL;
        for (int i = 0; i < labels.length; i++) {
            gbc.gridx = 0; gbc.gridy = i; gbc.weightx = 0;
            form.add(new JLabel(labels[i]), gbc);
            gbc.gridx = 1; gbc.weightx = 1;
            form.add(reportDetailField(keys[i], initialRtc.variables().get(keys[i])), gbc);
        }

        JPanel wrapper = new JPanel(new BorderLayout());
        themeHelper.applyTheme(wrapper);
        wrapper.add(form, BorderLayout.NORTH);
        return wrapper;
    }

    private JTextField reportDetailField(String key, String initialValue) {
        JTextField field = new JTextField(initialValue != null ? initialValue : "");
        themeHelper.applyTheme(field);
        field.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e) { save(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e) { save(); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { save(); }
            private void save() {
                var rtc = ReportTemplateConfig.fromConfig(config);
                rtc.variables().put(key, field.getText());
                rtc.saveTo(config);
            }
        });
        return field;
    }

    private JPanel buildFindingHeader(String hash, List<EvidenceCapture.CapturedEvidence> group,
                                      Map<String, List<EvidenceCapture.CapturedEvidence>> groups, Runnable[] refreshAllRef) {
        Finding current = group.get(group.size() - 1).finding(); // freshest edit

        JPanel header = new JPanel(new BorderLayout(8, 8));
        header.setBorder(BorderFactory.createTitledBorder("Finding Metadata (auto-saves)"));
        themeHelper.applyTheme(header);

        JPanel form = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 4));
        themeHelper.applyTheme(form);

        form.add(new JLabel("Title:"));
        JTextField txtTitle = new JTextField(current.type(), 25);
        themeHelper.applyTheme(txtTitle);
        form.add(txtTitle);

        form.add(new JLabel("Severity:"));
        JComboBox<Severity> comboSeverity = new JComboBox<>(Severity.values());
        comboSeverity.setSelectedItem(current.severity());
        themeHelper.applyTheme(comboSeverity);
        form.add(comboSeverity);

        form.add(new JLabel("CWEs:"));
        JTextField txtCwe = new JTextField(String.join(", ", current.cweIds()), 10);
        themeHelper.applyTheme(txtCwe);
        form.add(txtCwe);

        header.add(form, BorderLayout.CENTER);

        // Auto-save logic
        Runnable save = () -> {
            String newTitle = txtTitle.getText().strip();
            if (newTitle.isEmpty()) return;
            Severity newSeverity = (Severity) comboSeverity.getSelectedItem();
            List<String> newCweIds = java.util.Arrays.stream(txtCwe.getText().split(","))
                    .map(String::strip).filter(s -> !s.isEmpty()).toList();

            Finding.Builder builder = Finding.builder(current.module(), newTitle)
                    .description(current.description())
                    .severity(newSeverity)
                    .category(current.category())
                    .path(current.path())
                    .evidence(current.evidence());
            current.metadata().forEach(builder::meta);
            newCweIds.forEach(builder::cwe);
            Finding updated = builder.build();

            // Check if there was an actual change
            if (updated.type().equals(current.type()) && updated.severity() == current.severity() && updated.cweIds().equals(current.cweIds())) {
                return;
            }

            for (var ce : group) {
                evidenceCapture.moveToFinding(ce, updated);
            }
            orchestrator.updateFinding(updated);
            refreshAllRef[0].run();
        };

        txtTitle.addFocusListener(new java.awt.event.FocusAdapter() {
            @Override public void focusLost(java.awt.event.FocusEvent e) { save.run(); }
        });
        txtTitle.addActionListener(e -> save.run());
        comboSeverity.addActionListener(e -> save.run());
        txtCwe.addFocusListener(new java.awt.event.FocusAdapter() {
            @Override public void focusLost(java.awt.event.FocusEvent e) { save.run(); }
        });
        txtCwe.addActionListener(e -> save.run());

        return header;
    }

    private String findingLabel(String hash, Map<String, List<EvidenceCapture.CapturedEvidence>> groups) {
        List<EvidenceCapture.CapturedEvidence> group = groups.get(hash);
        Finding display = group.get(group.size() - 1).finding(); // freshest edit
        return display.severity().name() + "  ·  " + display.type()
                + "  (" + group.size() + (group.size() == 1 ? " item)" : " items)");
    }

    private JPanel buildRetestStatusRow(String hash) {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        row.setBorder(BorderFactory.createTitledBorder("Retest Status"));
        themeHelper.applyTheme(row);

        List<String> statuses = ReportTemplateConfig.fromConfig(config).retestStatuses();
        if (statuses.isEmpty()) {
            row.add(new JLabel("No retest statuses configured — add some in Settings → Reporting."));
            return row;
        }

        String key = "retest.status." + hash;
        JComboBox<String> combo = new JComboBox<>(statuses.toArray(new String[0]));
        themeHelper.applyTheme(combo);
        String current = config.getString(key, "");
        if (!current.isBlank()) combo.setSelectedItem(current);
        else combo.setSelectedIndex(-1);
        combo.addActionListener(e -> {
            config.set(key, (String) combo.getSelectedItem());
            api.persistence().extensionData().setString("config", config.serialize());
        });
        row.add(combo);
        return row;
    }

    private void syncGroupsToCapture(List<String> hashOrder, Map<String, List<EvidenceCapture.CapturedEvidence>> groups) {
        List<EvidenceCapture.CapturedEvidence> flat = new ArrayList<>();
        for (String hash : hashOrder) flat.addAll(groups.get(hash));
        evidenceCapture.reorderCaptured(flat);
    }

    private JPanel buildEvidenceCard(List<EvidenceCapture.CapturedEvidence> group, int indexInGroup,
                                      List<String> hashOrder, Map<String, List<EvidenceCapture.CapturedEvidence>> groups,
                                      Runnable onChange) {
        EvidenceCapture.CapturedEvidence[] ceRef = { group.get(indexInGroup) };

        JPanel card = new JPanel(new BorderLayout(8, 8));
        card.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(themeHelper.getBorderColor()),
                BorderFactory.createEmptyBorder(8, 8, 8, 8)));
        themeHelper.applyTheme(card);

        Image scaled = ceRef[0].image().getScaledInstance(320, -1, Image.SCALE_SMOOTH);
        JLabel thumb = new JLabel(new ImageIcon(scaled));
        thumb.setToolTipText("Drag onto a finding on the left to move this evidence there");
        thumb.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        thumb.setTransferHandler(new TransferHandler() {
            @Override
            protected Transferable createTransferable(JComponent c) {
                EvidenceCapture.CapturedEvidence dragged = ceRef[0];
                return new Transferable() {
                    public DataFlavor[] getTransferDataFlavors() { return new DataFlavor[]{EVIDENCE_DRAG_FLAVOR}; }
                    public boolean isDataFlavorSupported(DataFlavor flavor) { return EVIDENCE_DRAG_FLAVOR.equals(flavor); }
                    public Object getTransferData(DataFlavor flavor) { return dragged; }
                };
            }

            @Override
            public int getSourceActions(JComponent c) { return MOVE; }
        });
        thumb.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                thumb.getTransferHandler().exportAsDrag(thumb, e, TransferHandler.MOVE);
            }
        });
        card.add(thumb, BorderLayout.WEST);

        JPanel right = new JPanel(new BorderLayout(4, 4));
        themeHelper.applyTheme(right);

        JTextArea txtCaption = new JTextArea(ceRef[0].caption(), 3, 30);
        txtCaption.setLineWrap(true);
        txtCaption.setWrapStyleWord(true);
        themeHelper.styleTextArea(txtCaption);
        txtCaption.setBorder(BorderFactory.createTitledBorder("Caption #" + (indexInGroup + 1)));
        txtCaption.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e) { save(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e) { save(); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { save(); }
            private void save() {
                ceRef[0] = evidenceCapture.setCaption(ceRef[0], txtCaption.getText());
                group.set(indexInGroup, ceRef[0]);
            }
        });
        right.add(new JScrollPane(txtCaption), BorderLayout.CENTER);

        JPanel controls = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        themeHelper.applyTheme(controls);

        JCheckBox chkInclude = new JCheckBox("Include in report", evidenceCapture.isIncluded(ceRef[0]));
        themeHelper.applyTheme(chkInclude);
        chkInclude.addActionListener(e -> evidenceCapture.setIncluded(ceRef[0], chkInclude.isSelected()));
        controls.add(chkInclude);

        JButton btnUp = new JButton("▲");
        themeHelper.styleButton(btnUp);
        btnUp.setEnabled(indexInGroup > 0);
        btnUp.addActionListener(e -> {
            Collections.swap(group, indexInGroup, indexInGroup - 1);
            syncGroupsToCapture(hashOrder, groups);
            onChange.run();
        });
        controls.add(btnUp);

        JButton btnDown = new JButton("▼");
        themeHelper.styleButton(btnDown);
        btnDown.setEnabled(indexInGroup < group.size() - 1);
        btnDown.addActionListener(e -> {
            Collections.swap(group, indexInGroup, indexInGroup + 1);
            syncGroupsToCapture(hashOrder, groups);
            onChange.run();
        });
        controls.add(btnDown);

        JButton btnEdit = new JButton("Edit / Re-annotate…");
        themeHelper.styleButton(btnEdit);
        btnEdit.addActionListener(e -> evidenceCapture.captureInteractive(ceRef[0].finding()));
        controls.add(btnEdit);

        JButton btnMove = new JButton("Move to Finding…");
        themeHelper.styleButton(btnMove);
        btnMove.addActionListener(e -> {
            String ownHash = ceRef[0].finding().similarityHash();
            List<String> targets = hashOrder.stream().filter(h -> !h.equals(ownHash)).toList();
            if (targets.isEmpty()) {
                JOptionPane.showMessageDialog(mainPanel, "There's no other finding to move this evidence to.");
                return;
            }
            String[] labels = targets.stream().map(h -> findingLabel(h, groups)).toArray(String[]::new);
            String choice = (String) JOptionPane.showInputDialog(mainPanel, "Move this evidence to:",
                    "Move to Finding", JOptionPane.PLAIN_MESSAGE, null, labels, labels[0]);
            if (choice == null) return;
            String targetHash = targets.get(java.util.List.of(labels).indexOf(choice));
            Finding targetFinding = orchestrator.getFindingByHash(targetHash);
            if (targetFinding == null) return; 
            evidenceCapture.moveToFinding(ceRef[0], targetFinding);
            onChange.run();
        });
        controls.add(btnMove);

        JButton btnRemove = new JButton("Remove");
        themeHelper.styleButton(btnRemove);
        btnRemove.addActionListener(e -> {
            int confirm = JOptionPane.showConfirmDialog(mainPanel,
                    "Remove this screenshot from the report? The finding itself stays in the Results tab.",
                    "Confirm Remove", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
            if (confirm != JOptionPane.YES_OPTION) return;
            evidenceCapture.removeCaptured(ceRef[0]);
            onChange.run();
        });
        controls.add(btnRemove);

        right.add(controls, BorderLayout.SOUTH);
        card.add(right, BorderLayout.CENTER);
        return card;
    }
}
