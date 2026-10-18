package icarus.ui.evidence;

import burp.api.montoya.MontoyaApi;
import icarus.Orchestrator;
import icarus.core.Finding;
import icarus.core.ModuleConfig;
import icarus.core.ReportTemplateConfig;
import icarus.core.Severity;
import icarus.core.I18n;
import icarus.evidence.EvidenceCapture;
import icarus.ui.UiMetrics;

import javax.swing.*;
import java.awt.*;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class EvidenceManagerTab {
    private final JPanel mainPanel;
    private final Orchestrator orchestrator;
    private final ModuleConfig config;
    private final MontoyaApi api;
    private Runnable onReload;
    /** Rebuilds the open finding's detail. Inclusion changes use this; caption edits must not. */
    private Runnable refreshDetailView = () -> {};
    private JPanel evidenceListStack;
    /** A findings-registry refresh arrived while a detail text field had focus. Applied on focus loss. */
    private boolean registryReloadDeferred;
    private Runnable flushRegistryReload = () -> {};

    private static final DataFlavor EVIDENCE_DRAG_FLAVOR = new DataFlavor(EvidenceCapture.CapturedEvidence.class, "ICARUS Evidence Card");
    private static final DataFlavor FINDING_DRAG_FLAVOR = new DataFlavor(String.class, "ICARUS Finding Hash");

    public EvidenceManagerTab(Orchestrator orchestrator, ModuleConfig config, MontoyaApi api) {
        this.orchestrator = orchestrator;
        this.config = config;
        this.api = api;
        this.mainPanel = new JPanel(new BorderLayout());
        
        List<String> hashOrder = new ArrayList<>();
        Map<String, List<EvidenceCapture.CapturedEvidence>> groups = new LinkedHashMap<>();

        DefaultListModel<String> masterModel = new DefaultListModel<>();
        JList<String> masterList = new JList<>(masterModel);
        masterList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        masterList.putClientProperty("FlatLaf.style", "arc: 8;");
        
        masterList.setCellRenderer((list, hash, index, isSelected, hasFocus) -> {
            List<EvidenceCapture.CapturedEvidence> group = groups.get(hash);
            if (group == null || group.isEmpty()) return new JLabel("Error");
            Finding display = group.get(group.size() - 1).finding(); 

            JPanel panel = new JPanel(new BorderLayout(4, 4));
            panel.setBorder(UiMetrics.pad(UiMetrics.SM, UiMetrics.MD, UiMetrics.SM, UiMetrics.MD));
            panel.setOpaque(true);
            panel.setBackground(isSelected ? list.getSelectionBackground() : list.getBackground());
            panel.setForeground(isSelected ? list.getSelectionForeground() : list.getForeground());

            JLabel severityBadge = new JLabel(display.severity().name());
            severityBadge.setOpaque(true);
            severityBadge.setFont(UiMetrics.font(UiMetrics.Role.CAPTION).deriveFont(Font.BOLD));
            severityBadge.setBorder(UiMetrics.pad(UiMetrics.XS / 2, UiMetrics.SM, UiMetrics.XS / 2, UiMetrics.SM));
            severityBadge.putClientProperty("FlatLaf.style", "arc: 10;");
            
            Color badgeBg = switch(display.severity()) {
                case CRITICAL, HIGH -> new Color(220, 53, 69);
                case MEDIUM -> new Color(253, 126, 20);
                case LOW -> new Color(13, 110, 253);
                default -> new Color(108, 117, 125);
            };
            severityBadge.setBackground(badgeBg);
            severityBadge.setForeground(Color.WHITE);

            JLabel countLabel = new JLabel(group.size() + " img" + (group.size() == 1 ? "" : "s"));
            countLabel.setForeground(isSelected ? panel.getForeground() : new Color(130, 130, 130));
            countLabel.setFont(UiMetrics.font(UiMetrics.Role.CAPTION));

            icarus.ui.TextFit.Label titleLabel = new icarus.ui.TextFit.Label(display.type());
            titleLabel.setForeground(panel.getForeground());
            titleLabel.setFont(UiMetrics.font(UiMetrics.Role.EMPHASIS));
            // The row is a fixed height and the list assigns the width. Don't let an
            // unbroken title report a preferred width of thousands of pixels.
            titleLabel.setMinimumSize(new Dimension(0, 0));

            JPanel leftPanel = new JPanel(new BorderLayout(8, 0));
            leftPanel.setOpaque(false);
            leftPanel.add(severityBadge, BorderLayout.WEST);
            leftPanel.add(titleLabel, BorderLayout.CENTER);

            panel.add(leftPanel, BorderLayout.CENTER);
            panel.add(countLabel, BorderLayout.EAST);
            
            panel.setPreferredSize(new Dimension(0, 40));
            return panel;
        });
        
        JScrollPane masterScroll = new JScrollPane(masterList);
        masterScroll.setBorder(BorderFactory.createEmptyBorder());

        JPanel detailPanel = new JPanel();
        detailPanel.setLayout(new BoxLayout(detailPanel, BoxLayout.Y_AXIS));
        detailPanel.setBorder(UiMetrics.pad(UiMetrics.MD));
        
        JScrollPane detailScroll = new JScrollPane(detailPanel);
        detailScroll.getVerticalScrollBar().setUnitIncrement(16);
        detailScroll.setBorder(BorderFactory.createEmptyBorder());

        Runnable[] refreshAllRef = new Runnable[1];
        // Set while reload() is rewriting the list model, so the selection listener doesn't
        // rebuild the detail panel once per intermediate selection (clear, then reselect).
        boolean[] suppressSelectionRefresh = {false};
        // True when the latest reload changed which screenshots exist, or their order.
        // A caption edit swaps the record in place and must not set this.
        boolean[] evidenceStructureChanged = {true};

        Runnable reload = () -> icarus.core.DebugLog.timed(
                "EvidenceManagerTab.reload (" + orchestrator.getEvidenceCapture().getCaptured().size() + " captured)",
                () -> {
            List<EvidenceCapture.CapturedEvidence> captured = orchestrator.getEvidenceCapture().getCaptured();
            boolean sameStructure = evidenceStructureKeys(captured).equals(evidenceStructureKeys(hashOrder, groups));
            evidenceStructureChanged[0] = !sameStructure;
            if (sameStructure) {
                // Keep the list instances the open cards already hold, and only swap in the
                // new records (new caption text, same screenshot). Rebuilding the detail
                // panel here would remove the caption field mid-keystroke and drop focus.
                int cursor = 0;
                for (String hash : hashOrder) {
                    List<EvidenceCapture.CapturedEvidence> existing = groups.get(hash);
                    for (int i = 0; i < existing.size(); i++, cursor++) {
                        existing.set(i, captured.get(cursor));
                    }
                }
                masterList.repaint();
                showEvidenceList(hashOrder.isEmpty());
                return;
            }

            String selectedHash = masterList.getSelectedValue();
            hashOrder.clear();
            groups.clear();
            for (var ce : captured) {
                String hash = ce.finding().similarityHash();
                if (!groups.containsKey(hash)) hashOrder.add(hash);
                groups.computeIfAbsent(hash, h -> new ArrayList<>()).add(ce);
            }
            suppressSelectionRefresh[0] = true;
            try {
                masterModel.clear();
                hashOrder.forEach(masterModel::addElement);
                if (selectedHash != null && hashOrder.contains(selectedHash)) {
                    masterList.setSelectedValue(selectedHash, true);
                } else if (!hashOrder.isEmpty()) {
                    masterList.setSelectedIndex(0);
                }
            } finally {
                suppressSelectionRefresh[0] = false;
            }
            showEvidenceList(hashOrder.isEmpty());
        });

        Runnable refreshDetail = () -> icarus.core.DebugLog.timed("EvidenceManagerTab.refreshDetail", () -> {
            detailPanel.removeAll();
            String hash = masterList.getSelectedValue();
            if (hash == null) {
                String message = hashOrder.isEmpty()
                        ? I18n.t("ui.evidence.empty.captured")
                        : I18n.t("ui.evidence.empty.select");
                detailPanel.add(new icarus.ui.EmptyState(message));
            } else {
                List<EvidenceCapture.CapturedEvidence> group = groups.get(hash);
                detailPanel.add(buildFindingHeader(hash, group, groups, refreshAllRef));
                detailPanel.add(Box.createRigidArea(new Dimension(0, UiMetrics.MD)));
                
                if (config.getBool("retest.enabled", false)) {
                    detailPanel.add(buildRetestStatusRow(hash));
                    detailPanel.add(Box.createRigidArea(new Dimension(0, UiMetrics.MD)));
                }
                for (int i = 0; i < group.size(); i++) {
                    final int cardIndex = i;
                    icarus.core.DebugLog.timed("EvidenceManagerTab.buildEvidenceCard #" + cardIndex, () ->
                            detailPanel.add(buildEvidenceCard(mainPanel, group, cardIndex, hashOrder, groups, () -> refreshAllRef[0].run())));
                    detailPanel.add(Box.createRigidArea(new Dimension(0, UiMetrics.MD)));
                }
            }
            detailPanel.revalidate();
            detailPanel.repaint();
        });
        refreshDetailView = refreshDetail;

        masterList.addListSelectionListener(e -> {
            if (suppressSelectionRefresh[0] || e.getValueIsAdjusting()) return;
            refreshDetail.run();
        });

        refreshAllRef[0] = () -> { reload.run(); refreshDetail.run(); };
        refreshAllRef[0].run();
        this.onReload = refreshAllRef[0];

        // Caption (and include-toggle) edits notify this listener on every change. Rebuild the
        // detail panel only when the screenshot list actually changed shape; otherwise the
        // caption text area is removed and recreated and keyboard focus dies after one character.
        this.orchestrator.getEvidenceCapture().addChangeListener(() -> {
            reload.run();
            if (evidenceStructureChanged[0]) refreshDetail.run();
        });
        // Registry updates (passive scan, autosave restore, an explicit updateFinding) used to
        // call reload(), which always rebuilds the detail panel. Doing that while the title
        // field is focused removes it, and focusLost then commits whatever prefix was typed.
        // Unchanged evidence shape only needs a list repaint; a real shape change waits until
        // the user leaves the field.
        flushRegistryReload = () -> {
            if (!registryReloadDeferred) return;
            // Focus moved from the title to the caption (or the other way). Keep deferring
            // until the user actually leaves the text fields, so neither edit is wiped.
            if (detailTextFocused(detailPanel)) return;
            registryReloadDeferred = false;
            reload.run();
            if (evidenceStructureChanged[0]) refreshDetail.run();
        };
        this.orchestrator.addListener(records -> {
            if (detailTextFocused(detailPanel)) {
                registryReloadDeferred = true;
                return;
            }
            reload.run();
            if (evidenceStructureChanged[0]) refreshDetail.run();
        });

        JPanel masterToolbar = new JPanel(new GridLayout(0, 2, UiMetrics.XS, UiMetrics.XS));
        masterToolbar.setOpaque(false);

        JButton btnGroupUp = UiMetrics.commandButton(I18n.t("ui.evidence.btn.shot_up"), I18n.t("ui.evidence.btn.move_up"));
        btnGroupUp.addActionListener(e -> {
            int idx = masterList.getSelectedIndex();
            if (idx > 0) {
                Collections.swap(hashOrder, idx, idx - 1);
                syncGroupsToCapture(hashOrder, groups);
                refreshAllRef[0].run();
            }
        });
        
        JButton btnGroupDown = UiMetrics.commandButton(I18n.t("ui.evidence.btn.shot_down"), I18n.t("ui.evidence.btn.move_down"));
        btnGroupDown.addActionListener(e -> {
            int idx = masterList.getSelectedIndex();
            if (idx >= 0 && idx < hashOrder.size() - 1) {
                Collections.swap(hashOrder, idx, idx + 1);
                syncGroupsToCapture(hashOrder, groups);
                refreshAllRef[0].run();
            }
        });

        JButton btnPopOut = UiMetrics.commandButton(I18n.t("ui.evidence.btn.detach"), I18n.t("ui.evidence.btn.popout"));

        JButton btnExportProject = UiMetrics.commandButton(I18n.t("ui.evidence.btn.export_short"), I18n.t("ui.evidence.btn.export_project"));
        btnExportProject.addActionListener(e -> orchestrator.exportProjectStateInteractive(mainPanel, btnExportProject));

        masterToolbar.add(btnGroupUp);
        masterToolbar.add(btnGroupDown);
        masterToolbar.add(btnPopOut);
        masterToolbar.add(btnExportProject);
        
        masterList.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() != 2) return;
                int idx = masterList.locationToIndex(e.getPoint());
                if (idx < 0) return;
                masterList.setSelectedIndex(idx);
            }
        });

        masterList.setDragEnabled(true);
        masterList.setDropMode(DropMode.ON_OR_INSERT);
        masterList.setTransferHandler(new TransferHandler() {
            @Override
            public int getSourceActions(JComponent c) {
                return MOVE;
            }

            @Override
            protected Transferable createTransferable(JComponent c) {
                String selectedHash = masterList.getSelectedValue();
                if (selectedHash == null) return null;
                return new Transferable() {
                    @Override
                    public DataFlavor[] getTransferDataFlavors() {
                        return new DataFlavor[]{FINDING_DRAG_FLAVOR};
                    }
                    @Override
                    public boolean isDataFlavorSupported(DataFlavor flavor) {
                        return flavor.equals(FINDING_DRAG_FLAVOR);
                    }
                    @Override
                    public Object getTransferData(DataFlavor flavor) {
                        return selectedHash;
                    }
                };
            }

            @Override
            public boolean canImport(TransferSupport support) {
                return support.isDrop() && 
                       (support.isDataFlavorSupported(EVIDENCE_DRAG_FLAVOR) || support.isDataFlavorSupported(FINDING_DRAG_FLAVOR));
            }

            @Override
            public boolean importData(TransferSupport support) {
                if (!canImport(support)) return false;
                try {
                    if (support.isDataFlavorSupported(EVIDENCE_DRAG_FLAVOR)) {
                        var dragged = (EvidenceCapture.CapturedEvidence) support.getTransferable().getTransferData(EVIDENCE_DRAG_FLAVOR);
                        int dropIndex = ((JList.DropLocation) support.getDropLocation()).getIndex();
                        if (dropIndex < 0 || dropIndex >= hashOrder.size()) return false;
                        String targetHash = hashOrder.get(dropIndex);
                        if (targetHash.equals(dragged.finding().similarityHash())) return false; 
                        Finding targetFinding = orchestrator.getFindingByHash(targetHash);
                        if (targetFinding == null) return false;
                        orchestrator.getEvidenceCapture().moveToFinding(dragged, targetFinding);
                        refreshAllRef[0].run();
                        return true;
                    } else if (support.isDataFlavorSupported(FINDING_DRAG_FLAVOR)) {
                        String draggedHash = (String) support.getTransferable().getTransferData(FINDING_DRAG_FLAVOR);
                        int dropIndex = ((JList.DropLocation) support.getDropLocation()).getIndex();
                        int sourceIndex = hashOrder.indexOf(draggedHash);
                        if (sourceIndex == -1 || dropIndex < 0 || dropIndex > hashOrder.size()) return false;
                        if (sourceIndex < dropIndex) dropIndex--;
                        if (sourceIndex == dropIndex) return false;
                        hashOrder.remove(sourceIndex);
                        hashOrder.add(dropIndex, draggedHash);
                        syncGroupsToCapture(hashOrder, groups);
                        refreshAllRef[0].run();
                        masterList.setSelectedValue(draggedHash, true);
                        return true;
                    }
                    return false;
                } catch (Exception ex) {
                    return false;
                }
            }
        });

        JPanel masterHeader = new JPanel(new BorderLayout(0, UiMetrics.XS));
        masterHeader.setOpaque(false);
        JLabel findingsLbl = new JLabel(I18n.t("ui.evidence.lbl.findings"));
        findingsLbl.setFont(UiMetrics.font(UiMetrics.Role.TITLE));
        masterHeader.add(findingsLbl, BorderLayout.NORTH);
        masterHeader.add(masterToolbar, BorderLayout.CENTER);

        JPanel masterPanel = new JPanel(new BorderLayout(0, 8));
        masterPanel.setBorder(UiMetrics.pad(UiMetrics.MD));
        masterPanel.add(masterHeader, BorderLayout.NORTH);
        
        JPanel masterListWrapper = new JPanel(new BorderLayout());
        masterListWrapper.putClientProperty("FlatLaf.style", "arc: 8;");
        masterListWrapper.setBorder(BorderFactory.createLineBorder(new Color(130, 130, 130, 60), 1, true));
        evidenceListStack = new JPanel(new CardLayout());
        evidenceListStack.add(masterScroll, "list");
        evidenceListStack.add(new icarus.ui.EmptyState(I18n.t("ui.evidence.empty.captured")), "empty");
        masterListWrapper.add(evidenceListStack, BorderLayout.CENTER);
        showEvidenceList(hashOrder.isEmpty());
        
        masterPanel.add(masterListWrapper, BorderLayout.CENTER);

        masterPanel.setMinimumSize(new Dimension(260, 150));
        detailScroll.setMinimumSize(new Dimension(450, 150));

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, masterPanel, detailScroll);
        split.setResizeWeight(0.30);
        split.setDividerSize(6);
        split.putClientProperty("FlatLaf.style", "continuousLayout: true;");
        SwingUtilities.invokeLater(() -> {
            int initialWidth = split.getWidth();
            if (initialWidth > 0) {
                split.setDividerLocation((int)(initialWidth * 0.28));
            }
        });

        var initialRtc = icarus.core.ReportTemplateConfig.fromConfig(config);

        JLabel hint = new JLabel(I18n.t("ui.evidence.lbl.hint"));
        hint.setFont(UiMetrics.font(UiMetrics.Role.CAPTION));
        hint.setForeground(new Color(140, 140, 140));
        hint.setBorder(UiMetrics.pad(UiMetrics.XS, UiMetrics.MD, UiMetrics.SM, UiMetrics.MD));

        JCheckBox chkRetest = new JCheckBox(I18n.t("ui.evidence.chk.retest"),
                config.getBool("retest.enabled", false));
        chkRetest.addActionListener(e -> {
            config.set("retest.enabled", chkRetest.isSelected());
            api.persistence().extensionData().setString("config", config.serialize());
            refreshAllRef[0].run();
        });

        JPanel headerPanel = new JPanel(new BorderLayout());
        headerPanel.setBorder(UiMetrics.pad(UiMetrics.MD, UiMetrics.MD, 0, UiMetrics.MD));
        
        JPanel headerLeft = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        headerLeft.add(chkRetest);
        
        JPanel headerRight = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        
        headerPanel.add(headerLeft, BorderLayout.WEST);
        headerPanel.add(headerRight, BorderLayout.EAST);

        JPanel topPanel = new JPanel(new BorderLayout());
        topPanel.add(headerPanel, BorderLayout.NORTH);
        topPanel.add(hint, BorderLayout.SOUTH);

        JPanel evidenceTab = new JPanel(new BorderLayout());
        evidenceTab.add(topPanel, BorderLayout.NORTH);
        
        JTabbedPane compactTabs = new JTabbedPane();
        compactTabs.putClientProperty("FlatLaf.style", "tabType: hidden;"); 
        boolean[] isCompactMode = { false };

        Runnable updateLayoutMode = () -> {
            int width = evidenceTab.getWidth();
            if (width <= 0) return; 

            boolean shouldBeCompact = width < 1000;
            if (isCompactMode[0] == shouldBeCompact && evidenceTab.getComponentCount() > 1) return; 

            isCompactMode[0] = shouldBeCompact;
            evidenceTab.remove(split);
            evidenceTab.remove(compactTabs);
            if (shouldBeCompact) {
                compactTabs.addTab(I18n.t("ui.evidence.tab.findings"), masterPanel);
                compactTabs.addTab(I18n.t("ui.evidence.tab.details"), detailScroll);
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
        evidenceTab.add(split, BorderLayout.CENTER);

        masterList.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                if (e.getClickCount() == 2 && isCompactMode[0]) {
                    compactTabs.setSelectedIndex(1);
                }
            }
        });

        btnPopOut.addActionListener(e -> {
            java.awt.Window winParent = javax.swing.SwingUtilities.getWindowAncestor(mainPanel);
            JFrame popOutFrame = new JFrame(I18n.t("ui.evidence.title.detached"));
            if (winParent instanceof java.awt.Frame) popOutFrame.setIconImage(((java.awt.Frame)winParent).getIconImage());
            
            java.awt.GraphicsConfiguration gc = mainPanel.getGraphicsConfiguration();
            java.awt.Rectangle screenBounds = gc != null ? gc.getBounds() : new java.awt.Rectangle(java.awt.Toolkit.getDefaultToolkit().getScreenSize());
            
            popOutFrame.setSize(Math.min(1200, screenBounds.width - 50), Math.min(800, screenBounds.height - 100));
            popOutFrame.setLocationRelativeTo(winParent);
            popOutFrame.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);

            Container parent = mainPanel.getParent();
            if (parent instanceof JTabbedPane) {
                JTabbedPane parentTabs = (JTabbedPane) parent;
                int tabIdx = parentTabs.indexOfComponent(mainPanel);
                
                JPanel placeholder = new JPanel(new GridBagLayout());
                JButton btnRestore = new JButton(I18n.t("ui.evidence.btn.restore"));
                btnRestore.putClientProperty("FlatLaf.style", "arc: 8;");
                btnRestore.addActionListener(ev -> popOutFrame.dispose());
                placeholder.add(btnRestore);
                
                if (tabIdx >= 0) {
                    parentTabs.setComponentAt(tabIdx, placeholder);
                }
                
                popOutFrame.add(mainPanel);
                
                popOutFrame.addWindowListener(new java.awt.event.WindowAdapter() {
                    @Override
                    public void windowClosed(java.awt.event.WindowEvent ev) {
                        if (tabIdx >= 0) {
                            parentTabs.setComponentAt(tabIdx, mainPanel);
                        }
                    }
                });
                
                popOutFrame.setVisible(true);
            }
        });

        JTabbedPane tabs = new JTabbedPane();
        tabs.putClientProperty("FlatLaf.style", "showTabSeparators: true;");
        tabs.addTab(I18n.t("ui.tab.evidence"), evidenceTab);
        tabs.addTab(I18n.t("ui.evidence.tab.report_details"), buildReportSectionsQuickEditPanel(initialRtc));
        mainPanel.add(tabs, BorderLayout.CENTER);

        JButton btnPreview = new JButton(I18n.t("ui.tab.results.btn.preview"));
        btnPreview.putClientProperty("FlatLaf.style", "arc: 8;");
        btnPreview.addActionListener(e -> orchestrator.previewReport(mainPanel, btnPreview));

        JButton btnGenerate = new JButton(I18n.t("ui.tab.results.btn.generate_html"));
        btnGenerate.putClientProperty("FlatLaf.style", "arc: 8;");
        btnGenerate.addActionListener(e -> orchestrator.generateHtmlReportInteractive(mainPanel, btnGenerate, orchestrator.getReportableFindings()));

        JButton btnExportPdf = new JButton(I18n.t("ui.tab.results.btn.export_pdf"));
        btnExportPdf.putClientProperty("FlatLaf.style", "arc: 8;");
        btnExportPdf.addActionListener(e -> orchestrator.exportPdfReportInteractive(mainPanel, btnExportPdf, orchestrator.getReportableFindings()));

        JButton btnImportProject = new JButton(I18n.t("ui.evidence.btn.import_project"));
        btnImportProject.putClientProperty("FlatLaf.style", "arc: 8;");
        btnImportProject.addActionListener(e -> orchestrator.importProjectStateInteractive(mainPanel, btnImportProject, () -> refreshAllRef[0].run()));

        JButton btnClose = new JButton(I18n.t("ui.evidence.btn.close"));
        btnClose.putClientProperty("FlatLaf.style", "arc: 8;");
        btnClose.addActionListener(e -> {
            api.persistence().extensionData().setString("config", config.serialize());
        });

        JPanel btnPanel = new JPanel(new BorderLayout());
        btnPanel.setBorder(UiMetrics.pad(UiMetrics.MD));

        JPanel leftBtns = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        leftBtns.add(btnImportProject);

        JPanel rightBtns = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        rightBtns.add(btnPreview);
        rightBtns.add(btnGenerate);
        rightBtns.add(btnExportPdf);
        rightBtns.add(Box.createRigidArea(new Dimension(16, 0)));
        rightBtns.add(btnClose);

        btnPanel.add(leftBtns, BorderLayout.WEST);
        btnPanel.add(rightBtns, BorderLayout.EAST);
        mainPanel.add(btnPanel, BorderLayout.SOUTH);
    }


    private JTextField reportDetailField(String key, String initialValue) {
        JTextField field = new JTextField(initialValue != null ? initialValue : "");
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

    private String findingLabel(String hash, Map<String, List<EvidenceCapture.CapturedEvidence>> groups) {
        List<EvidenceCapture.CapturedEvidence> group = groups.get(hash);
        Finding display = group.get(group.size() - 1).finding(); 
        return display.severity().name() + "  ·  " + display.type()
                + "  (" + group.size() + " " + (group.size() == 1 ? I18n.t("ui.evidence.lbl.item") : I18n.t("ui.evidence.lbl.items")) + ")";
    }

    private JPanel buildRetestStatusRow(String hash) {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 8));
        row.putClientProperty("FlatLaf.style", "arc: 12;");
        row.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(new Color(130, 130, 130, 60), 1, true),
            UiMetrics.pad(UiMetrics.XS / 2, UiMetrics.SM, UiMetrics.XS / 2, UiMetrics.SM)
        ));

        JLabel titleLbl = new JLabel(I18n.t("ui.evidence.border.retest_status") + ":");
        titleLbl.setFont(UiMetrics.font(UiMetrics.Role.EMPHASIS));
        row.add(titleLbl);

        List<String> statuses = ReportTemplateConfig.fromConfig(config).retestStatuses();
        if (statuses.isEmpty()) {
            row.add(new JLabel(I18n.t("ui.evidence.lbl.no_retest_status")));
            row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height));
            return row;
        }

        String key = "retest.status." + hash;
        JComboBox<String> combo = new JComboBox<>(statuses.toArray(new String[0]));
        combo.putClientProperty("FlatLaf.style", "arc: 8;");
        String current = config.getString(key, "");
        if (!current.isBlank()) combo.setSelectedItem(current);
        else combo.setSelectedIndex(-1);
        combo.addActionListener(e -> {
            config.set(key, (String) combo.getSelectedItem());
            api.persistence().extensionData().setString("config", config.serialize());
        });
        row.add(combo);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height));
        return row;
    }

    /** True while the user is typing in the title, CWE, or caption field of the open detail. */
    private static boolean detailTextFocused(Component detailPanel) {
        Component focus = java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
        return focus instanceof javax.swing.text.JTextComponent
                && SwingUtilities.isDescendingFrom(focus, detailPanel);
    }

    /**
     * Identity of the evidence list for reload purposes: finding hash plus screenshot path,
     * in display order. Caption text is deliberately not part of it.
     */
    private static List<String> evidenceStructureKeys(List<EvidenceCapture.CapturedEvidence> captured) {
        List<String> keys = new ArrayList<>(captured.size());
        for (EvidenceCapture.CapturedEvidence ce : captured) {
            keys.add(ce.finding().similarityHash() + "\0" + ce.imagePath());
        }
        return keys;
    }

    private static List<String> evidenceStructureKeys(List<String> hashOrder,
                                                      Map<String, List<EvidenceCapture.CapturedEvidence>> groups) {
        List<String> keys = new ArrayList<>();
        for (String hash : hashOrder) {
            List<EvidenceCapture.CapturedEvidence> group = groups.get(hash);
            if (group == null) continue;
            for (EvidenceCapture.CapturedEvidence ce : group) {
                keys.add(ce.finding().similarityHash() + "\0" + ce.imagePath());
            }
        }
        return keys;
    }

    private void syncGroupsToCapture(List<String> hashOrder, Map<String, List<EvidenceCapture.CapturedEvidence>> groups) {
        List<EvidenceCapture.CapturedEvidence> flat = new ArrayList<>();
        for (String hash : hashOrder) flat.addAll(groups.get(hash));
        orchestrator.getEvidenceCapture().reorderCaptured(flat);
    }

    private JPanel buildEvidenceCard(Component mainPanel, List<EvidenceCapture.CapturedEvidence> group, int indexInGroup,
                                      List<String> hashOrder, Map<String, List<EvidenceCapture.CapturedEvidence>> groups,
                                      Runnable onChange) {
        EvidenceCapture.CapturedEvidence[] ceRef = { group.get(indexInGroup) };

        JPanel card = new JPanel(new BorderLayout(16, 12));
        card.putClientProperty("FlatLaf.style", "arc: 8; margin: 8,10,8,10;");
        card.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(130, 130, 130, 60), 1, true),
                UiMetrics.pad(UiMetrics.SM, UiMetrics.MD, UiMetrics.SM, UiMetrics.MD)));

        Image scaled = ceRef[0].image().getScaledInstance(320, -1, Image.SCALE_SMOOTH);
        JLabel thumb = new JLabel(new ImageIcon(scaled));
        thumb.setToolTipText(I18n.t("ui.evidence.tooltip.drag"));
        thumb.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        thumb.setBorder(BorderFactory.createLineBorder(new Color(130, 130, 130, 40), 1));
        
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
        final Point[] dragStartPoint = new Point[1];
        final boolean[] isDragStarted = new boolean[1];

        thumb.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                dragStartPoint[0] = e.getPoint();
                isDragStarted[0] = false;
            }

            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getButton() == MouseEvent.BUTTON1 && !isDragStarted[0]) {
                    EvidenceCapture.CapturedEvidence ce = ceRef[0];
                    if (ce != null && ce.image() != null) {
                        SwingUtilities.invokeLater(() -> showExpandedImageModal(ce.image(), ce.finding().type()));
                    }
                }
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                dragStartPoint[0] = null;
            }
        });

        thumb.addMouseMotionListener(new MouseMotionAdapter() {
            @Override
            public void mouseDragged(MouseEvent e) {
                if (dragStartPoint[0] != null && !isDragStarted[0]) {
                    double dist = e.getPoint().distance(dragStartPoint[0]);
                    if (dist > 5) {
                        isDragStarted[0] = true;
                        thumb.getTransferHandler().exportAsDrag(thumb, e, TransferHandler.MOVE);
                    }
                }
            }
        });
        
        JPanel leftPanel = new JPanel(new BorderLayout());
        leftPanel.add(thumb, BorderLayout.NORTH);
        card.add(leftPanel, BorderLayout.WEST);

        JPanel right = new JPanel(new BorderLayout(0, 10));

        JTextArea txtCaption = new JTextArea(ceRef[0].caption(), 4, 30);
        txtCaption.setLineWrap(true);
        txtCaption.setWrapStyleWord(true);
        txtCaption.putClientProperty("FlatLaf.style", "margin: 8,8,8,8; arc: 8;");
        
        JPanel captionPanel = new JPanel(new BorderLayout(0, 4));
        JLabel captionLbl = new JLabel(I18n.t("ui.evidence.border.caption") + " " + (indexInGroup + 1));
        captionLbl.setFont(UiMetrics.font(UiMetrics.Role.EMPHASIS));
        captionPanel.add(captionLbl, BorderLayout.NORTH);
        
        JScrollPane captionScroll = new JScrollPane(txtCaption);
        captionScroll.putClientProperty("FlatLaf.style", "arc: 8;");
        captionPanel.add(captionScroll, BorderLayout.CENTER);
        
        txtCaption.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e) { save(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e) { save(); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { save(); }
            private void save() {
                ceRef[0] = orchestrator.getEvidenceCapture().setCaption(ceRef[0], txtCaption.getText());
                group.set(indexInGroup, ceRef[0]);
            }
        });
        txtCaption.addFocusListener(new java.awt.event.FocusAdapter() {
            @Override public void focusLost(java.awt.event.FocusEvent e) {
                if (!e.isTemporary()) flushRegistryReload.run();
            }
        });
        right.add(captionPanel, BorderLayout.CENTER);

        JPanel controls = new JPanel(new BorderLayout(0, UiMetrics.XS));

        JCheckBox chkInclude = new JCheckBox(I18n.t("ui.evidence.chk.include"), orchestrator.getEvidenceCapture().isIncluded(ceRef[0]));
        chkInclude.addActionListener(e -> {
            orchestrator.getEvidenceCapture().setIncluded(ceRef[0], chkInclude.isSelected());
            refreshDetailView.run();
        });

        JPanel cardActions = new JPanel(new GridLayout(0, 3, UiMetrics.XS, UiMetrics.XS));
        cardActions.setOpaque(false);

        JButton btnUp = UiMetrics.commandButton(I18n.t("ui.evidence.btn.shot_up"), "Move Up");
        btnUp.setEnabled(indexInGroup > 0);
        btnUp.addActionListener(e -> {
            Collections.swap(group, indexInGroup, indexInGroup - 1);
            syncGroupsToCapture(hashOrder, groups);
            onChange.run();
        });

        JButton btnDown = UiMetrics.commandButton(I18n.t("ui.evidence.btn.shot_down"), "Move Down");
        btnDown.setEnabled(indexInGroup < group.size() - 1);
        btnDown.addActionListener(e -> {
            Collections.swap(group, indexInGroup, indexInGroup + 1);
            syncGroupsToCapture(hashOrder, groups);
            onChange.run();
        });
        
        JButton btnEdit = UiMetrics.commandButton(I18n.t("ui.evidence.btn.edit_short"), I18n.t("ui.evidence.btn.edit"));
        btnEdit.addActionListener(e -> orchestrator.getEvidenceCapture().captureInteractive(ceRef[0].finding()));

        JButton btnMove = UiMetrics.commandButton(I18n.t("ui.evidence.btn.move_short"), I18n.t("ui.evidence.btn.move_finding"));
        btnMove.addActionListener(e -> {
            String ownHash = ceRef[0].finding().similarityHash();
            List<String> targets = hashOrder.stream().filter(h -> !h.equals(ownHash)).toList();
            if (targets.isEmpty()) {
                JOptionPane.showMessageDialog(mainPanel, I18n.t("ui.evidence.dialog.move.no_finding"));
                return;
            }
            // Each option carries its own hash: two findings can share a display label (same
            // severity/type/count on different endpoints), so matching the picked label string
            // back to a hash with indexOf silently moved evidence onto the wrong finding.
            record MoveTarget(String hash, String label) {
                @Override public String toString() { return label; }
            }
            MoveTarget[] options = targets.stream()
                    .map(h -> new MoveTarget(h, findingLabel(h, groups))).toArray(MoveTarget[]::new);
            MoveTarget choice = (MoveTarget) JOptionPane.showInputDialog(mainPanel, I18n.t("ui.evidence.dialog.move.msg"),
                    I18n.t("ui.evidence.dialog.move.title"), JOptionPane.PLAIN_MESSAGE, null, options, options[0]);
            if (choice == null) return;
            Finding targetFinding = orchestrator.getFindingByHash(choice.hash());
            if (targetFinding == null) return; 
            orchestrator.getEvidenceCapture().moveToFinding(ceRef[0], targetFinding);
            onChange.run();
        });

        JButton btnRemove = UiMetrics.commandButton(I18n.t("ui.evidence.btn.remove"), I18n.t("ui.evidence.btn.remove"));
        btnRemove.addActionListener(e -> {
            int confirm = JOptionPane.showConfirmDialog(mainPanel,
                    I18n.t("ui.evidence.dialog.remove.msg"),
                    I18n.t("ui.evidence.dialog.remove.title"), JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
            if (confirm != JOptionPane.YES_OPTION) return;
            orchestrator.getEvidenceCapture().removeCaptured(ceRef[0]);
            onChange.run();
        });

        cardActions.add(btnUp);
        cardActions.add(btnDown);
        cardActions.add(btnEdit);
        cardActions.add(btnMove);
        cardActions.add(btnRemove);

        controls.add(chkInclude, BorderLayout.NORTH);
        controls.add(cardActions, BorderLayout.CENTER);
        
        right.add(controls, BorderLayout.SOUTH);
        card.add(right, BorderLayout.CENTER);
        return card;
    }

    private void showEvidenceList(boolean empty) {
        if (evidenceListStack == null) return;
        ((CardLayout) evidenceListStack.getLayout()).show(evidenceListStack, empty ? "empty" : "list");
    }

    public void reload() {
        if (this.onReload != null) {
            this.onReload.run();
        }
    }

    public Component getUiComponent() {
        return mainPanel;
    }

    private JComponent buildReportSectionsQuickEditPanel(ReportTemplateConfig initialRtc) {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(UiMetrics.pad(UiMetrics.XL));

        Map<String, String> vars = initialRtc.variables();

        String existingDate = vars.get("date");
        if (existingDate == null || existingDate.isBlank()) {
            vars.put("date", java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy")));
            initialRtc.saveTo(config);
        }

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = UiMetrics.gap(0, 0, UiMetrics.LG, 0);

        JLabel resumoNote = new JLabel(I18n.t("ui.evidence.lbl.resumo_note"));
        resumoNote.setFont(UiMetrics.font(UiMetrics.Role.BODY).deriveFont(Font.ITALIC));
        resumoNote.setForeground(new Color(130, 130, 130));
        panel.add(resumoNote, gbc);

        gbc.gridy++;
        panel.add(fieldGroup(I18n.t("ui.evidence.section.controle"), vars, new String[][]{
            {I18n.t("ui.evidence.lbl.projeto"), "project"},
            {I18n.t("ui.evidence.lbl.data"), "date"},
            {I18n.t("ui.evidence.lbl.versao"), "version"},
            {I18n.t("ui.evidence.lbl.autor"), "author"},
            {I18n.t("ui.evidence.lbl.revisor"), "reviewer"},
            {I18n.t("ui.evidence.lbl.aprovado_por"), "approver"},
        }), gbc);

        gbc.gridy++;
        JPanel escopoPanel = fieldGroup(I18n.t("ui.evidence.section.escopo"), vars, new String[][]{
            {I18n.t("ui.evidence.lbl.team"), "team"},
            {I18n.t("ui.evidence.lbl.componente"), "component"},
            {I18n.t("ui.evidence.lbl.solicitante"), "requester"},
            {I18n.t("ui.evidence.lbl.responsavel"), "owner"},
            {I18n.t("ui.evidence.lbl.ambiente"), "environment"},
        });

        GridBagConstraints eg = new GridBagConstraints();
        eg.insets = UiMetrics.gap(UiMetrics.SM);
        eg.fill = GridBagConstraints.HORIZONTAL;
        eg.gridx = 0; eg.gridy = 5; eg.weightx = 0;
        escopoPanel.add(new JLabel(I18n.t("ui.evidence.lbl.executor")), eg);

        eg.gridx = 1; eg.weightx = 1;
        JPanel executorWrapper = new JPanel(new BorderLayout(8, 0));

        boolean isSame = config.getBool("executor.sameAsAuthor", true);
        JCheckBox chkSame = new JCheckBox("Same as Author", isSame);
        JTextField txtExecutor = reportDetailField("executor", vars.get("executor"));
        txtExecutor.putClientProperty("FlatLaf.style", "margin: 6,8,6,8; arc: 8;");
        txtExecutor.setEnabled(!isSame);

        if (isSame) {
            vars.put("executor", vars.get("author"));
            txtExecutor.setText(vars.get("author"));
        }

        chkSame.addActionListener(e -> {
            boolean same = chkSame.isSelected();
            config.set("executor.sameAsAuthor", same);
            api.persistence().extensionData().setString("config", config.serialize());
            txtExecutor.setEnabled(!same);
            if (same) {
                txtExecutor.setText(vars.get("author"));
                vars.put("executor", vars.get("author"));
                ReportTemplateConfig.fromConfig(config).variables().put("executor", vars.get("author"));
            }
        });
        
        executorWrapper.add(txtExecutor, BorderLayout.CENTER);
        executorWrapper.add(chkSame, BorderLayout.EAST);
        escopoPanel.add(executorWrapper, eg);
        
        panel.add(escopoPanel, gbc);

        JPanel alignPanel = new JPanel(new BorderLayout());
        alignPanel.add(panel, BorderLayout.NORTH);
        
        JScrollPane scroll = new JScrollPane(alignPanel);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        return scroll;
    }


    private JPanel fieldGroup(String title, Map<String, String> vars, String[][] labelsAndKeys) {
        JPanel form = new JPanel(new GridBagLayout());
        form.putClientProperty("FlatLaf.style", "arc: 12;");
        form.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createTitledBorder(
                BorderFactory.createLineBorder(new Color(130, 130, 130, 50), 1, true),
                title, 
                javax.swing.border.TitledBorder.LEFT, 
                javax.swing.border.TitledBorder.TOP, 
                UiMetrics.font(UiMetrics.Role.EMPHASIS)
            ),
            UiMetrics.pad(UiMetrics.MD)
        ));
        
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = UiMetrics.gap(UiMetrics.SM);
        gbc.fill = GridBagConstraints.HORIZONTAL;
        for (int i = 0; i < labelsAndKeys.length; i++) {
            gbc.gridx = 0; gbc.gridy = i; gbc.weightx = 0;
            JLabel lbl = new JLabel(labelsAndKeys[i][0]);
            form.add(lbl, gbc);
            
            gbc.gridx = 1; gbc.weightx = 1;
            JTextField field = reportDetailField(labelsAndKeys[i][1], vars.get(labelsAndKeys[i][1]));
            field.putClientProperty("FlatLaf.style", "margin: 6,8,6,8; arc: 8;");
            form.add(field, gbc);
        }
        return form;
    }

    private JPanel buildFindingHeader(String hash, List<EvidenceCapture.CapturedEvidence> group,
                                      Map<String, List<EvidenceCapture.CapturedEvidence>> groups, Runnable[] refreshAllRef) {
        Finding current = group.get(group.size() - 1).finding(); 

        JPanel header = new JPanel(new GridBagLayout());
        header.putClientProperty("FlatLaf.style", "arc: 12;");
        header.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(new Color(130, 130, 130, 60), 1, true),
            UiMetrics.pad(UiMetrics.MD, UiMetrics.LG)
        ));
        
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = UiMetrics.gap(UiMetrics.XS, UiMetrics.XS, UiMetrics.XS, UiMetrics.MD);
        
        gbc.gridx = 0; gbc.gridy = 0; gbc.weightx = 0;
        JLabel titleLbl = new JLabel(I18n.t("ui.evidence.lbl.title"));
        titleLbl.setFont(UiMetrics.font(UiMetrics.Role.EMPHASIS));
        header.add(titleLbl, gbc);
        
        gbc.gridx = 1; gbc.weightx = 1.0; gbc.gridwidth = 3;
        // Column count, not the string, sets the preferred width. A title of several
        // hundred characters (or one long unbroken token) would otherwise make this
        // field, and the caption buttons pinned to the card's right edge, wider than
        // the window. The document still holds the full title; HTML/PDF reports read
        // Finding.type(), not the visible slice.
        JTextField txtTitle = new JTextField(current.type(), 24);
        txtTitle.setMinimumSize(new Dimension(80, txtTitle.getPreferredSize().height));
        txtTitle.putClientProperty("FlatLaf.style", "margin: 6,8,6,8; arc: 8;");
        header.add(txtTitle, gbc);

        gbc.gridwidth = 1;
        gbc.gridy = 1; 
        
        gbc.gridx = 0; gbc.weightx = 0;
        JLabel sevLbl = new JLabel(I18n.t("ui.evidence.lbl.severity"));
        sevLbl.setFont(UiMetrics.font(UiMetrics.Role.EMPHASIS));
        header.add(sevLbl, gbc);
        
        gbc.gridx = 1; gbc.weightx = 0.5;
        JComboBox<Severity> comboSeverity = new JComboBox<>(Severity.values());
        comboSeverity.putClientProperty("FlatLaf.style", "arc: 8;");
        comboSeverity.setSelectedItem(current.severity());
        header.add(comboSeverity, gbc);
        
        gbc.gridx = 2; gbc.weightx = 0;
        JLabel cweLbl = new JLabel(I18n.t("ui.evidence.lbl.cwes"));
        cweLbl.setFont(UiMetrics.font(UiMetrics.Role.EMPHASIS));
        header.add(cweLbl, gbc);
        
        gbc.gridx = 3; gbc.weightx = 0.5;
        JTextField txtCwe = new JTextField(String.join(", ", current.cweIds()));
        txtCwe.putClientProperty("FlatLaf.style", "margin: 6,8,6,8; arc: 8;");
        header.add(txtCwe, gbc);

        Runnable save = () -> {
            String newTitle = txtTitle.getText().strip();
            if (newTitle.isEmpty()) return;
            Severity newSeverity = (Severity) comboSeverity.getSelectedItem();
            List<String> newCweIds = java.util.Arrays.stream(txtCwe.getText().split(","))
                    .map(String::strip).filter(s -> !s.isEmpty()).toList();

            // Carry every other field over. This used to build the finding from module, title,
            // severity and CWEs alone, so any edit here wiped the description, path,
            // request/response evidence, category and metadata, and registered the stripped
            // copy as a second finding next to the original.
            Finding.Builder builder = Finding.builder(current.module(), newTitle)
                    .description(current.description())
                    .severity(newSeverity)
                    .category(current.category())
                    .path(current.path())
                    .evidence(current.evidence());
            current.metadata().forEach(builder::meta);
            builder.keepIdentityOf(current);
            newCweIds.forEach(builder::cwe);
            Finding updated = builder.build();

            if (updated.type().equals(current.type()) && updated.severity() == current.severity() && updated.cweIds().equals(current.cweIds())) {
                return;
            }

            for (var ce : group) {
                orchestrator.getEvidenceCapture().moveToFinding(ce, updated);
            }
            orchestrator.updateFinding(updated);
            refreshAllRef[0].run();
        };

        txtTitle.addFocusListener(new java.awt.event.FocusAdapter() {
            @Override public void focusLost(java.awt.event.FocusEvent e) {
                if (e.isTemporary()) return;
                save.run();
                flushRegistryReload.run();
            }
        });
        txtTitle.addActionListener(e -> save.run());
        comboSeverity.addActionListener(e -> save.run());
        txtCwe.addFocusListener(new java.awt.event.FocusAdapter() {
            @Override public void focusLost(java.awt.event.FocusEvent e) {
                if (e.isTemporary()) return;
                save.run();
                flushRegistryReload.run();
            }
        });
        txtCwe.addActionListener(e -> save.run());

        boolean inReport = group.stream().allMatch(ce -> orchestrator.getEvidenceCapture().isIncluded(ce));
        JButton btnAddToReport = UiMetrics.commandButton(
                inReport ? I18n.t("ui.evidence.btn.in_report") : I18n.t("ui.evidence.btn.add_to_report"),
                I18n.t("ui.evidence.btn.add_to_report"));
        btnAddToReport.setEnabled(!inReport);
        btnAddToReport.addActionListener(e -> {
            for (EvidenceCapture.CapturedEvidence ce : group) {
                orchestrator.getEvidenceCapture().setIncluded(ce, true);
            }
            refreshDetailView.run();
        });
        gbc.gridy = 2;
        gbc.gridx = 0;
        gbc.gridwidth = 4;
        gbc.weightx = 1;
        gbc.fill = GridBagConstraints.NONE;
        gbc.anchor = GridBagConstraints.WEST;
        header.add(btnAddToReport, gbc);

        header.setMaximumSize(new Dimension(Integer.MAX_VALUE, header.getPreferredSize().height));
        return header;
    }

    private long lastModalOpenTime = 0;

    private void showExpandedImageModal(BufferedImage img, String title) {
        if (img == null) return;
        long now = System.currentTimeMillis();
        if (now - lastModalOpenTime < 500) return;
        lastModalOpenTime = now;

        Window parentWindow = SwingUtilities.getWindowAncestor(mainPanel);
        JDialog dialog;
        if (parentWindow instanceof Frame) {
            dialog = new JDialog((Frame) parentWindow, title, false);
        } else if (parentWindow instanceof Dialog) {
            dialog = new JDialog((Dialog) parentWindow, title, false);
        } else {
            dialog = new JDialog((Frame) null, title, false);
        }

        dialog.setUndecorated(true);
        dialog.setLayout(new BorderLayout());

        // Close when user clicks anywhere outside Burp/modal window focus
        dialog.addWindowFocusListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowLostFocus(java.awt.event.WindowEvent e) {
                dialog.dispose();
            }
        });

        JPanel rootPanel = new JPanel(new BorderLayout());
        rootPanel.setBackground(new Color(18, 24, 36)); // ICARUS dark palette COLOR_BACKGROUND (#121824)
        rootPanel.setBorder(BorderFactory.createLineBorder(new Color(255, 102, 51), 2)); // ICARUS orange accent (#FF6633)

        // Close on clicking root panel background
        rootPanel.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                dialog.dispose();
            }
        });

        JPanel topBar = new JPanel(new BorderLayout());
        topBar.setOpaque(false);
        topBar.setBorder(UiMetrics.pad(UiMetrics.MD, UiMetrics.LG));

        JLabel lblTitle = new JLabel(title != null && !title.isBlank() ? title : I18n.t("ui.evidence.modal.title", "Evid\u00eancia Ampliada"));
        lblTitle.setFont(UiMetrics.font(UiMetrics.Role.TITLE));
        lblTitle.setForeground(Color.WHITE);
        topBar.add(lblTitle, BorderLayout.WEST);

        JButton btnClose = UiMetrics.commandButton(I18n.t("ui.evidence.btn.close"), "Fechar (ESC)");
        btnClose.addActionListener(e -> dialog.dispose());
        topBar.add(btnClose, BorderLayout.EAST);

        rootPanel.add(topBar, BorderLayout.NORTH);

        Dimension burpBounds = (parentWindow != null && parentWindow.getWidth() > 300)
                ? parentWindow.getSize()
                : Toolkit.getDefaultToolkit().getScreenSize();

        int maxWidth = Math.max(400, (int) (burpBounds.width * 0.85));
        int maxHeight = Math.max(300, (int) (burpBounds.height * 0.85));

        int imgW = img.getWidth();
        int imgH = img.getHeight();

        double scale = Math.min(1.0, Math.min((double) maxWidth / imgW, (double) maxHeight / imgH));
        int targetW = Math.max(100, (int) (imgW * scale));
        int targetH = Math.max(100, (int) (imgH * scale));

        Image scaledImg = img.getScaledInstance(targetW, targetH, Image.SCALE_SMOOTH);
        JLabel imgLabel = new JLabel(new ImageIcon(scaledImg));
        imgLabel.setHorizontalAlignment(SwingConstants.CENTER);

        JScrollPane scrollPane = new JScrollPane(imgLabel);
        scrollPane.setOpaque(false);
        scrollPane.getViewport().setOpaque(false);
        scrollPane.setBorder(UiMetrics.pad(0, UiMetrics.LG, UiMetrics.LG, UiMetrics.LG));

        // Close on clicking empty space inside scroll pane
        scrollPane.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                dialog.dispose();
            }
        });

        rootPanel.add(scrollPane, BorderLayout.CENTER);

        // Bind ESC key to close
        dialog.getRootPane().registerKeyboardAction(
                e -> dialog.dispose(),
                KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
                JComponent.WHEN_IN_FOCUSED_WINDOW
        );

        dialog.setContentPane(rootPanel);
        dialog.pack();
        dialog.setSize(Math.min(maxWidth, targetW + 60), Math.min(maxHeight, targetH + 100));
        dialog.setLocationRelativeTo(parentWindow);
        dialog.setVisible(true);
    }
}
