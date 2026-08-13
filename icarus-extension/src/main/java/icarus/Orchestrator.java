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
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Burp integration facade: wires context-menu items and the passive HTTP handler to
 * scan execution ({@link ScanRunner}) and finding bookkeeping ({@link FindingRegistry}),
 * and owns how findings get presented (the results dialog).
 */
public final class Orchestrator implements ContextMenuItemsProvider, HttpHandler {

    private final MontoyaApi api;
    private final List<IcarusModule> modules;
    private final ModuleConfig config;
    private final EvidenceCapture evidenceCapture;
    private final ReportGenerator reportGenerator;
    private final PdfReportGenerator pdfReportGenerator;
    private final AutoAuthModule autoAuth;
    private final ScanRunner scanRunner;
    private final FindingRegistry findings;

    public Orchestrator(MontoyaApi api,
                        List<IcarusModule> modules,
                        ModuleConfig config,
                        EvidenceCapture evidenceCapture,
                        ReportGenerator reportGenerator,
                        AutoAuthModule autoAuth) {
        this.api = api;
        this.modules = modules;
        this.config = config;
        this.evidenceCapture = evidenceCapture;
        this.reportGenerator = reportGenerator;
        this.pdfReportGenerator = new PdfReportGenerator(api);
        this.autoAuth = autoAuth;
        this.findings = new FindingRegistry(api, config, SwingUtilities::invokeLater);
        this.scanRunner = new ScanRunner(api, modules, config, this::routeFindings);

        evidenceCapture.setOnApplied(this::registerManualFinding);
    }

    /**
     * Called by EvidenceCapture once a finding's evidence is applied — folds it into the
     * same registry manual/passive/scan findings share, so it shows up in the Results tab
     * and "Generate HTML Report" immediately, and re-editing + re-applying later updates
     * the same entry (matched by {@link Finding#similarityHash()}) instead of duplicating it.
     */
    private void registerManualFinding(Finding finding) {
        findings.processDeduplication(List.of(finding), false);
    }

    public AutoAuthModule autoAuth() {
        return autoAuth;
    }

    public void addListener(Consumer<List<FindingRecord>> listener) {
        findings.addListener(listener);
    }

    public List<String> getAuditLog() {
        return findings.getAuditLog();
    }

    public void suppressFinding(String hash, String reason) {
        findings.suppressFinding(hash, reason);
    }

    public void unsuppressFinding(String hash) {
        findings.unsuppressFinding(hash);
    }

    public Finding getFindingByHash(String hash) {
        return findings.getFindingByHash(hash);
    }

    public void showEvidenceInteractive(Finding finding) {
        evidenceCapture.captureInteractive(finding);
    }

    /**
     * Findings that actually belong in a report: ones the user explicitly sent through
     * Evidence Capture (Apply / Send annotation), not every passively-detected finding
     * (e.g. SensitiveHeaderModule's header checks, PassiveErrorModule) that only ever
     * landed in the Results tab for awareness. Order follows EvidenceCapture's captured
     * list, which the Evidence Manager's drag-and-drop reordering controls directly —
     * report order was previously undefined HashMap iteration order via getAllFindingRecords().
     * Grouped by {@link Finding#similarityHash()}, not Finding object identity — re-editing a
     * finding's evidence (Evidence Editor "Apply") builds a brand new Finding instance with the
     * same hash, so identity-based matching used to silently orphan every prior screenshot for
     * that finding instead of keeping them as additional evidence for the same reportable entry.
     * Returns the registry's current canonical Finding per hash (freshest title/severity/etc.),
     * one per hash regardless of how many CapturedEvidence entries share it — matching
     * {@link icarus.evidence.EvidenceCapture#groupedBySimilarityHash()}'s keys, which is what
     * the report generators actually iterate for each finding's evidence images.
     */
    public List<Finding> getReportableFindings() {
        List<Finding> result = new ArrayList<>();
        Set<String> seenHashes = new HashSet<>();
        for (var ce : evidenceCapture.getCaptured()) {
            if (!evidenceCapture.isIncluded(ce)) continue;
            String hash = ce.finding().similarityHash();
            if (!seenHashes.add(hash)) continue;
            var record = findings.getRecordByHash(hash);
            if (record == null || record.isSuppressed()) continue;
            result.add(record.getFinding());
        }
        return result;
    }

    /**
     * Window for managing the screenshots that will actually go into the HTML report —
     * separate from the Results tab, which lists every finding whether or not it has
     * evidence attached. Reachable from the "ICARUS → Manage Report Evidence" context-menu
     * item and the Results tab's own button.
     */
    public void showEvidenceManager() {
        JDialog dialog = new JDialog(api.userInterface().swingUtils().suiteFrame(), "ICARUS — Evidence Manager", false);
        dialog.setSize(1300, 800);
        dialog.setLocationRelativeTo(null);
        dialog.setLayout(new BorderLayout());

        // Master-detail view state: hashOrder controls which finding comes first in the
        // report, groups holds each finding's evidence in its own report order. Both are
        // fully rebuilt from evidenceCapture.getCaptured() by `reload` — EvidenceCapture
        // stays the single source of truth, this is just a convenient grouped view over it.
        // Grouped by similarityHash (not Finding identity) for the same reason
        // EvidenceCapture.groupedBySimilarityHash() is — re-editing a finding's evidence
        // builds a new Finding instance with the same hash.
        List<String> hashOrder = new ArrayList<>();
        Map<String, List<EvidenceCapture.CapturedEvidence>> groups = new LinkedHashMap<>();

        DefaultListModel<String> masterModel = new DefaultListModel<>();
        JList<String> masterList = new JList<>(masterModel);
        masterList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        masterList.setCellRenderer((list, hash, index, isSelected, hasFocus) -> {
            List<EvidenceCapture.CapturedEvidence> group = groups.get(hash);
            Finding display = group.get(group.size() - 1).finding(); // freshest edit
            JLabel l = new JLabel(display.severity().name() + "  ·  " + display.type()
                    + "  (" + group.size() + (group.size() == 1 ? " item)" : " items)"));
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

        // Forward-reference workaround (same single-element-array idiom EvidenceCapture's
        // annotation editor already uses for mutable lambda state): `reload` and
        // `refreshDetail` are mutually independent, but card/button callbacks need to
        // trigger both together, and neither can name the other before both exist.
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
                if (config.getBool("retest.enabled", false)) {
                    detailPanel.add(buildRetestStatusRow(hash));
                    detailPanel.add(Box.createRigidArea(new Dimension(0, 8)));
                }
                List<EvidenceCapture.CapturedEvidence> group = groups.get(hash);
                for (int i = 0; i < group.size(); i++) {
                    detailPanel.add(buildEvidenceCard(dialog, group, i, hashOrder, groups, () -> refreshAllRef[0].run()));
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
        btnGroupUp.addActionListener(e -> {
            int idx = masterList.getSelectedIndex();
            if (idx > 0) {
                Collections.swap(hashOrder, idx, idx - 1);
                syncGroupsToCapture(hashOrder, groups);
                refreshAllRef[0].run();
            }
        });
        JButton btnGroupDown = new JButton("▼ Move Finding Down");
        btnGroupDown.addActionListener(e -> {
            int idx = masterList.getSelectedIndex();
            if (idx >= 0 && idx < hashOrder.size() - 1) {
                Collections.swap(hashOrder, idx, idx + 1);
                syncGroupsToCapture(hashOrder, groups);
                refreshAllRef[0].run();
            }
        });
        JPanel masterButtons = new JPanel(new GridLayout(1, 2, 4, 0));
        masterButtons.add(btnGroupUp);
        masterButtons.add(btnGroupDown);

        JPanel masterPanel = new JPanel(new BorderLayout(0, 4));
        masterPanel.add(new JLabel("Findings (report order)"), BorderLayout.NORTH);
        masterPanel.add(masterScroll, BorderLayout.CENTER);
        masterPanel.add(masterButtons, BorderLayout.SOUTH);

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, masterPanel, detailScroll);
        split.setResizeWeight(0.28);
        split.setDividerSize(6);
        // Report-level free text (scope/methodology/takeaway), not tied to any one finding —
        // stored as the "Executive Summary" section of ReportTemplateConfig (Settings →
        // Reporting owns the rest of the sections; this is a quick-edit shortcut for the one
        // every report needs) so it survives across Evidence Manager sessions and is read by
        // both generators via ReportTemplateConfig.fromConfig() at generate time.
        var initialRtc = icarus.core.ReportTemplateConfig.fromConfig(config);
        String initialSummary = initialRtc.sections().stream()
                .filter(s -> "Executive Summary".equals(s.title()))
                .map(icarus.core.ReportTemplateConfig.Section::content)
                .findFirst().orElse("");
        JTextArea txtSummary = new JTextArea(initialSummary, 3, 0);
        txtSummary.setLineWrap(true);
        txtSummary.setWrapStyleWord(true);
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

        JLabel hint = new JLabel("  Select a finding on the left; manage its evidence cards on the right.");
        hint.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));

        // One toggle drives both: showing/editing each finding's resolution status here, and
        // switching HTML/PDF generation to the retest profile (suppressed sections + status
        // shown per finding) — see ReportGenerator.generate()'s comment for why this is one
        // flag instead of a separate one-shot export-dialog checkbox.
        JCheckBox chkRetest = new JCheckBox("Retest Mode (show resolution status; suppress configured sections in reports)",
                config.getBool("retest.enabled", false));
        chkRetest.addActionListener(e -> {
            config.set("retest.enabled", chkRetest.isSelected());
            api.persistence().extensionData().setString("config", config.serialize());
            refreshAllRef[0].run();
        });

        JPanel topPanel = new JPanel(new BorderLayout());
        topPanel.add(summaryScroll, BorderLayout.CENTER);
        JPanel bottomOfTop = new JPanel(new BorderLayout());
        bottomOfTop.add(chkRetest, BorderLayout.NORTH);
        bottomOfTop.add(hint, BorderLayout.SOUTH);
        topPanel.add(bottomOfTop, BorderLayout.SOUTH);

        dialog.add(topPanel, BorderLayout.NORTH);
        dialog.add(split, BorderLayout.CENTER);

        JButton btnPreview = new JButton("Preview");
        btnPreview.addActionListener(e -> previewReport(dialog, btnPreview));

        JButton btnGenerate = new JButton("Generate HTML Report");
        btnGenerate.addActionListener(e -> generateHtmlReportInteractive(dialog, btnGenerate, getReportableFindings()));

        JButton btnExportPdf = new JButton("Export PDF");
        btnExportPdf.addActionListener(e -> exportPdfReportInteractive(dialog, btnExportPdf, getReportableFindings()));

        JButton btnExportProject = new JButton("Export Project…");
        btnExportProject.addActionListener(e -> exportProjectStateInteractive(dialog, btnExportProject));

        JButton btnImportProject = new JButton("Import Project…");
        btnImportProject.addActionListener(e -> importProjectStateInteractive(dialog, btnImportProject, () -> refreshAllRef[0].run()));

        JButton btnClose = new JButton("Close");
        btnClose.addActionListener(e -> {
            // Report Notes are kept up to date in-memory on every keystroke already;
            // flush to persistent storage once here rather than on every keystroke.
            api.persistence().extensionData().setString("config", config.serialize());
            dialog.dispose();
        });

        JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        btnPanel.add(btnImportProject);
        btnPanel.add(btnExportProject);
        btnPanel.add(btnPreview);
        btnPanel.add(btnGenerate);
        btnPanel.add(btnExportPdf);
        btnPanel.add(btnClose);
        dialog.add(btnPanel, BorderLayout.SOUTH);

        dialog.setVisible(true);
    }

    /**
     * One row above the evidence card feed: the selected finding's retest resolution status,
     * persisted as a flat {@code retest.status.<hash>} config key (reuses ModuleConfig's
     * existing string-key persistence rather than threading status through the Finding/
     * FindingRegistry data model for what's meant to be a lightweight quick-edit dropdown).
     * Options come from {@code ReportTemplateConfig.retestStatuses()} (Settings → Reporting).
     */
    private JPanel buildRetestStatusRow(String hash) {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        row.setBorder(BorderFactory.createTitledBorder("Retest Status"));

        List<String> statuses = ReportTemplateConfig.fromConfig(config).retestStatuses();
        if (statuses.isEmpty()) {
            row.add(new JLabel("No retest statuses configured — add some in Settings → Reporting."));
            return row;
        }

        String key = "retest.status." + hash;
        JComboBox<String> combo = new JComboBox<>(statuses.toArray(new String[0]));
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

    /** Flattens {@code groups} back into EvidenceCapture's report order, following {@code hashOrder}. */
    private void syncGroupsToCapture(List<String> hashOrder, Map<String, List<EvidenceCapture.CapturedEvidence>> groups) {
        List<EvidenceCapture.CapturedEvidence> flat = new ArrayList<>();
        for (String hash : hashOrder) flat.addAll(groups.get(hash));
        evidenceCapture.reorderCaptured(flat);
    }

    /**
     * One evidence card in the Evidence Manager's detail feed: thumbnail, caption editor,
     * include toggle, reorder-within-finding buttons (drag-and-drop for a nested list turns
     * bug-prone fast in raw Swing — buttons are the documented fallback), re-annotate, and
     * remove. {@code onChange} is called after anything that changes membership/order
     * (remove, reorder) so the caller can rebuild both the master list and detail feed from
     * EvidenceCapture; caption edits don't call it — full rebuild on every keystroke would
     * steal focus mid-type, and captions don't affect ordering/membership.
     */
    private JPanel buildEvidenceCard(JDialog dialog, List<EvidenceCapture.CapturedEvidence> group, int indexInGroup,
                                      List<String> hashOrder, Map<String, List<EvidenceCapture.CapturedEvidence>> groups,
                                      Runnable onChange) {
        EvidenceCapture.CapturedEvidence ce = group.get(indexInGroup);

        JPanel card = new JPanel(new BorderLayout(8, 8));
        card.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(80, 80, 80)),
                BorderFactory.createEmptyBorder(8, 8, 8, 8)));

        Image scaled = ce.image().getScaledInstance(320, -1, Image.SCALE_SMOOTH);
        JLabel thumb = new JLabel(new ImageIcon(scaled));
        card.add(thumb, BorderLayout.WEST);

        JPanel right = new JPanel(new BorderLayout(4, 4));

        JTextArea txtCaption = new JTextArea(ce.caption(), 3, 30);
        txtCaption.setLineWrap(true);
        txtCaption.setWrapStyleWord(true);
        txtCaption.setBorder(BorderFactory.createTitledBorder("Caption"));
        txtCaption.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e) { save(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e) { save(); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { save(); }
            private void save() { evidenceCapture.setCaption(ce, txtCaption.getText()); }
        });
        right.add(new JScrollPane(txtCaption), BorderLayout.CENTER);

        JPanel controls = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));

        JCheckBox chkInclude = new JCheckBox("Include in report", evidenceCapture.isIncluded(ce));
        chkInclude.addActionListener(e -> evidenceCapture.setIncluded(ce, chkInclude.isSelected()));
        controls.add(chkInclude);

        JButton btnUp = new JButton("▲");
        btnUp.setEnabled(indexInGroup > 0);
        btnUp.addActionListener(e -> {
            Collections.swap(group, indexInGroup, indexInGroup - 1);
            syncGroupsToCapture(hashOrder, groups);
            onChange.run();
        });
        controls.add(btnUp);

        JButton btnDown = new JButton("▼");
        btnDown.setEnabled(indexInGroup < group.size() - 1);
        btnDown.addActionListener(e -> {
            Collections.swap(group, indexInGroup, indexInGroup + 1);
            syncGroupsToCapture(hashOrder, groups);
            onChange.run();
        });
        controls.add(btnDown);

        JButton btnEdit = new JButton("Edit / Re-annotate…");
        btnEdit.addActionListener(e -> evidenceCapture.captureInteractive(ce.finding()));
        controls.add(btnEdit);

        JButton btnRemove = new JButton("Remove");
        btnRemove.addActionListener(e -> {
            int confirm = JOptionPane.showConfirmDialog(dialog,
                    "Remove this screenshot from the report? The finding itself stays in the Results tab.",
                    "Confirm Remove", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
            if (confirm != JOptionPane.YES_OPTION) return;
            evidenceCapture.removeCaptured(ce);
            onChange.run();
        });
        controls.add(btnRemove);

        right.add(controls, BorderLayout.SOUTH);
        card.add(right, BorderLayout.CENTER);
        return card;
    }

    /**
     * Renders the report to a temp file and opens it in the system browser — a real look at
     * the actual CSS (flex summary boxes, badges) instead of a half-working JEditorPane, and
     * no new dependency since {@link Desktop} is stdlib. Writes nothing to the user's chosen
     * report location and never touches FindingRegistry — purely a look.
     */
    public void previewReport(Component parent, JButton triggerButton) {
        List<Finding> reportFindings = getReportableFindings();
        if (reportFindings.isEmpty()) {
            JOptionPane.showMessageDialog(parent,
                    "No evidence to preview yet — use \"Send to Reporter Creation\" or the Evidence Manager first.");
            return;
        }

        Path tempFile;
        try {
            tempFile = Files.createTempFile("icarus-report-preview-", ".html");
        } catch (IOException e) {
            api.logging().logToError("Failed to create preview temp file: " + e);
            JOptionPane.showMessageDialog(parent, "Failed to create a temp file for the preview: " + e.getMessage());
            return;
        }

        if (triggerButton != null) triggerButton.setEnabled(false);
        new SwingWorker<Boolean, Void>() {
            @Override
            protected Boolean doInBackground() throws Exception {
                return reportGenerator.generate(reportFindings, config, evidenceCapture, tempFile);
            }

            @Override
            protected void done() {
                if (triggerButton != null) triggerButton.setEnabled(true);
                Frame suiteFrame = api.userInterface().swingUtils().suiteFrame();
                try {
                    boolean written = get();
                    if (!written) {
                        ToastNotification.show(suiteFrame,
                                "No preview generated — HTML reports may be disabled in Settings.");
                        return;
                    }
                    openInBrowser(tempFile, parent);
                } catch (Exception ex) {
                    api.logging().logToError("Report preview failed: " + ex.getCause());
                    JOptionPane.showMessageDialog(parent, "Report preview failed: " + ex.getCause());
                }
            }
        }.execute();
    }

    private void openInBrowser(Path file, Component parent) {
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
            try {
                Desktop.getDesktop().browse(file.toUri());
                return;
            } catch (IOException ex) {
                api.logging().logToError("Failed to open preview in browser: " + ex);
                // fall through to the manual-path message below
            }
        }
        JOptionPane.showMessageDialog(parent,
                "Couldn't open a browser automatically. Preview saved at:\n" + file.toAbsolutePath());
    }

    @FunctionalInterface
    private interface ReportWriter {
        boolean write(List<Finding> findings, ModuleConfig config, EvidenceCapture capture, Path outputFile) throws Exception;
    }

    /**
     * Shared by the "ICARUS Scan Results" dialog and the Results tab's own report buttons —
     * both just gather whatever {@link Finding}s they're showing and hand them here.
     *
     * @param parent used to anchor the file chooser / confirm dialogs
     * @param triggerButton disabled while generating and re-enabled after, if not null
     */
    public void generateHtmlReportInteractive(Component parent, JButton triggerButton, List<Finding> reportFindings) {
        exportReportInteractive(parent, triggerButton, reportFindings, "html", "report.html", "HTML Report",
                (findings, cfg, capture, out) -> reportGenerator.generate(findings, cfg, capture, out));
    }

    /** Same shell as {@link #generateHtmlReportInteractive}, writing via {@link PdfReportGenerator} instead. */
    public void exportPdfReportInteractive(Component parent, JButton triggerButton, List<Finding> reportFindings) {
        exportReportInteractive(parent, triggerButton, reportFindings, "pdf", "report.pdf", "PDF Report",
                (findings, cfg, capture, out) -> pdfReportGenerator.generate(findings, cfg, capture, out));
    }

    private void exportReportInteractive(Component parent, JButton triggerButton, List<Finding> reportFindings,
                                          String extension, String defaultFileName, String formatLabel, ReportWriter writer) {
        JFileChooser fc = new JFileChooser(new java.io.File(EvidencePaths.defaultOutputDir(api, config)));
        fc.setSelectedFile(new java.io.File(defaultFileName));
        if (fc.showSaveDialog(parent) != JFileChooser.APPROVE_OPTION) {
            return;
        }

        java.io.File selectedFile = fc.getSelectedFile();
        if (!selectedFile.getName().toLowerCase().endsWith("." + extension)) {
            selectedFile = new java.io.File(selectedFile.getParentFile(), selectedFile.getName() + "." + extension);
        }
        if (selectedFile.exists()) {
            int overwrite = JOptionPane.showConfirmDialog(parent,
                    selectedFile.getName() + " already exists. Overwrite?",
                    "Confirm Overwrite", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
            if (overwrite != JOptionPane.YES_OPTION) {
                return;
            }
        }

        java.io.File finalSelectedFile = selectedFile;
        Path outputFile = selectedFile.toPath();

        if (triggerButton != null) triggerButton.setEnabled(false);
        new SwingWorker<Boolean, Void>() {
            @Override
            protected Boolean doInBackground() throws Exception {
                return writer.write(reportFindings, config, evidenceCapture, outputFile);
            }

            @Override
            protected void done() {
                if (triggerButton != null) triggerButton.setEnabled(true);
                Frame suiteFrame = api.userInterface().swingUtils().suiteFrame();
                try {
                    boolean written = get();
                    if (written) {
                        if (finalSelectedFile.getParentFile() != null) {
                            config.set("evidence.output_dir", finalSelectedFile.getParentFile().getAbsolutePath());
                            api.persistence().extensionData().setString("config", config.serialize());
                        }
                        ToastNotification.show(suiteFrame, formatLabel + " generated: " + outputFile.toAbsolutePath());
                    } else {
                        ToastNotification.show(suiteFrame,
                                "No report was generated — HTML reports may be disabled in Settings, or there are no findings to include.");
                    }
                } catch (Exception ex) {
                    api.logging().logToError(formatLabel + " generation failed: " + ex.getCause());
                    JOptionPane.showMessageDialog(parent, formatLabel + " generation failed: " + ex.getCause());
                }
            }
        }.execute();
    }

    /**
     * Exports the full Evidence Manager state (findings, screenshots, captions, inclusion,
     * and the active {@link ReportTemplateConfig}) to a portable {@code .icarus} project
     * file, via {@link ProjectStateCodec}. Base64-encoding every screenshot is the expensive
     * part, so it runs in {@code doInBackground} — a large evidence set shouldn't freeze the
     * dialog while exporting.
     */
    public void exportProjectStateInteractive(Component parent, JButton triggerButton) {
        List<EvidenceCapture.CapturedEvidence> evidence = evidenceCapture.getCaptured();
        if (evidence.isEmpty()) {
            JOptionPane.showMessageDialog(parent, "No captured evidence to export yet.");
            return;
        }

        JFileChooser fc = new JFileChooser(new java.io.File(EvidencePaths.defaultOutputDir(api, config)));
        fc.setSelectedFile(new java.io.File("project.icarus"));
        if (fc.showSaveDialog(parent) != JFileChooser.APPROVE_OPTION) {
            return;
        }

        java.io.File selectedFile = fc.getSelectedFile();
        if (!selectedFile.getName().toLowerCase().endsWith(".icarus")) {
            selectedFile = new java.io.File(selectedFile.getParentFile(), selectedFile.getName() + ".icarus");
        }
        if (selectedFile.exists()) {
            int overwrite = JOptionPane.showConfirmDialog(parent,
                    selectedFile.getName() + " already exists. Overwrite?",
                    "Confirm Overwrite", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
            if (overwrite != JOptionPane.YES_OPTION) {
                return;
            }
        }

        java.io.File finalSelectedFile = selectedFile;
        ReportTemplateConfig rtc = ReportTemplateConfig.fromConfig(config);

        if (triggerButton != null) triggerButton.setEnabled(false);
        new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() throws Exception {
                String json = ProjectStateCodec.export(evidence, evidenceCapture::isIncluded, rtc);
                Files.writeString(finalSelectedFile.toPath(), json);
                return null;
            }

            @Override
            protected void done() {
                if (triggerButton != null) triggerButton.setEnabled(true);
                Frame suiteFrame = api.userInterface().swingUtils().suiteFrame();
                try {
                    get();
                    ToastNotification.show(suiteFrame, "Project exported: " + finalSelectedFile.getAbsolutePath());
                } catch (Exception ex) {
                    api.logging().logToError("Project export failed: " + ex.getCause());
                    JOptionPane.showMessageDialog(parent, "Project export failed: " + ex.getCause());
                }
            }
        }.execute();
    }

    /**
     * Imports a {@code .icarus} project file, fully replacing current Evidence Manager state
     * (simpler than a merge, and matches the "baseline for a retest months later" use case) —
     * every imported finding is re-registered into {@link FindingRegistry} via the same
     * dedup path manual evidence capture uses, so it's immediately visible in the Results tab
     * and reportable, not just sitting in {@link EvidenceCapture} orphaned from the registry.
     */
    public void importProjectStateInteractive(Component parent, JButton triggerButton, Runnable onImported) {
        JFileChooser fc = new JFileChooser(new java.io.File(EvidencePaths.defaultOutputDir(api, config)));
        if (fc.showOpenDialog(parent) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        java.io.File selectedFile = fc.getSelectedFile();

        int confirm = JOptionPane.showConfirmDialog(parent,
                "Importing replaces all evidence currently in the Evidence Manager. Continue?",
                "Confirm Import", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (confirm != JOptionPane.YES_OPTION) {
            return;
        }

        if (triggerButton != null) triggerButton.setEnabled(false);
        new SwingWorker<ProjectStateCodec.ImportResult, Void>() {
            @Override
            protected ProjectStateCodec.ImportResult doInBackground() throws Exception {
                String json = Files.readString(selectedFile.toPath());
                return ProjectStateCodec.importFrom(json);
            }

            @Override
            protected void done() {
                if (triggerButton != null) triggerButton.setEnabled(true);
                Frame suiteFrame = api.userInterface().swingUtils().suiteFrame();
                try {
                    ProjectStateCodec.ImportResult result = get();
                    Path dir = Path.of(EvidencePaths.defaultOutputDir(api, config));
                    Files.createDirectories(dir);

                    evidenceCapture.clearAll();
                    for (var item : result.items()) {
                        String filename = "evidence-" + item.finding().type().replaceAll("[^a-zA-Z0-9.-]", "_")
                                + "-" + System.currentTimeMillis() + "-" + java.util.UUID.randomUUID() + ".png";
                        Path imagePath = dir.resolve(filename);
                        Files.write(imagePath, item.imageBytes());
                        java.awt.image.BufferedImage image = javax.imageio.ImageIO.read(imagePath.toFile());
                        var ce = new EvidenceCapture.CapturedEvidence(item.finding(), imagePath, image, item.caption());
                        evidenceCapture.restoreCaptured(ce, item.included());
                        findings.processDeduplication(List.of(item.finding()), false);
                    }
                    result.reportTemplateConfig().saveTo(config);
                    api.persistence().extensionData().setString("config", config.serialize());

                    onImported.run();
                    ToastNotification.show(suiteFrame, "Project imported: " + result.items().size() + " evidence item(s).");
                } catch (Exception ex) {
                    api.logging().logToError("Project import failed: " + ex.getCause());
                    JOptionPane.showMessageDialog(parent, "Project import failed: " + ex.getCause());
                }
            }
        }.execute();
    }

    /**
     * Shared by the "Create Evidence" context-menu item and the Ctrl+P hotkey handler —
     * both entry points get Smart Evidence detection for free by routing through here.
     */
    public void createManualEvidence(HttpRequestResponse rr) {
        // AutoAuth injects the token on the wire (handleHttpRequestToBeSent), but that never
        // touches the UI's copy of the request — without this, captured evidence shows the
        // stale pre-injection token instead of what was actually sent.
        HttpRequest injectedRequest = autoAuth.injectIfApplicable(rr.request());
        if (injectedRequest != rr.request()) {
            rr = HttpRequestResponse.httpRequestResponse(injectedRequest, rr.response());
        }
        Finding smart = detectSmartEvidence(rr);
        evidenceCapture.captureInteractive(smart != null ? smart : blankManualFinding(rr));
    }

    /**
     * Quietly checks the response for something worth flagging (verbose error / server
     * error, or an unencoded reflection of a request parameter) and, if the user confirms,
     * pre-fills the evidence with that finding instead of the blank manual template.
     */
    private Finding detectSmartEvidence(HttpRequestResponse rr) {
        if (rr.response() == null) return null;

        // Reuse PassiveErrorModule's detection instead of a second copy of the same
        // VerboseErrorDetector/status-code checks living here.
        List<Finding> errorFindings = new PassiveErrorModule().run(rr, config, msg -> {});
        if (!errorFindings.isEmpty()) {
            Finding candidate = errorFindings.get(0);
            return confirmSmartEvidence(candidate.type(), candidate.description()) ? candidate : null;
        }

        // XSS reflection — manual-evidence-only heuristic. Deliberately not part of the
        // always-on background passive scan: any endpoint that legitimately echoes a
        // search term back would make it noisy there, but it's a useful targeted nudge
        // when the user is already looking at this specific request/response.
        String bodyStr = rr.response().bodyToString();
        for (var param : rr.request().parameters()) {
            String val = param.value();
            if (val != null && !val.isBlank() && (val.contains("<") || val.contains(">")) && bodyStr.contains(val)) {
                String desc = "Unencoded reflection of HTML/script payload detected:\n`" + val + "`";
                if (!confirmSmartEvidence("XSS_REFLECTION", desc)) return null;
                return Finding.builder("Manual", "XSS_REFLECTION")
                        .description(desc)
                        .severity(Severity.HIGH)
                        .category(Category.INJECTION)
                        .path(param.name())
                        .evidence(rr)
                        .build();
            }
        }

        return null;
    }

    private boolean confirmSmartEvidence(String type, String description) {
        int choice = JOptionPane.showConfirmDialog(api.userInterface().swingUtils().suiteFrame(),
                "ICARUS detected a potential [" + type + "] in this response.\nAuto-populate the evidence title and description?",
                "Smart Evidence Detection", JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE);
        return choice == JOptionPane.YES_OPTION;
    }

    private Finding blankManualFinding(HttpRequestResponse rr) {
        Severity manualSeverity = parseSeverity(config.getString("evidence.manual_severity", "INFO"));
        return Finding.builder("Manual", "MANUAL_EVIDENCE")
                .description("Manual evidence capture triggered by user.")
                .severity(manualSeverity)
                .category(Category.MANUAL)
                .evidence(rr)
                .build();
    }

    public void runScan(HttpRequestResponse target, boolean isManual) {
        scanRunner.runScan(target, isManual);
    }

    public List<FindingRecord> getAllFindingRecords() {
        return findings.getAllFindingRecords();
    }

    public List<FindingRecord> getPassiveFindings() {
        return findings.getPassiveFindings();
    }

    public void clearPassiveFindings() {
        findings.clearPassiveFindings();
    }

    @Override
    public List<Component> provideMenuItems(ContextMenuEvent event) {
        var items = new ArrayList<Component>();

        var requestResponses = event.messageEditorRequestResponse().isPresent()
                ? List.of(event.messageEditorRequestResponse().get().requestResponse())
                : event.selectedRequestResponses();

        if (requestResponses.isEmpty()) return items;

        var runAll = new JMenuItem("ICARUS → Run All Modules");
        runAll.addActionListener(e -> {
            for (var rr : requestResponses) {
                scanRunner.runScan(rr, true);
            }
        });
        items.add(runAll);

        var createEvidence = new JMenuItem("ICARUS → Send to Reporter Creation");
        createEvidence.addActionListener(e -> {
            for (var rr : requestResponses) {
                createManualEvidence(rr);
            }
        });
        items.add(createEvidence);

        // AutoAuth: only shown when the user actually highlighted text in a message editor —
        // these need selection offsets that scan-style modules never receive.
        event.messageEditorRequestResponse().ifPresent(selection -> {
            // Quick toggle so users can disable AutoAuth for manual JWT manipulation
            // without digging into Settings.
            boolean enabled = autoAuth.isEnabled();
            var toggleAuth = new JMenuItem("ICARUS → " + (enabled ? "✓" : "✗") + " AutoAuth " + (enabled ? "ON" : "OFF"));
            toggleAuth.addActionListener(e -> autoAuth.toggleEnabled());
            items.add(toggleAuth);

            // Montoya's HttpHandler only controls what goes out on the wire — it has no hook
            // back into an already-open editor pane (e.g. a Repeater tab), so the injected
            // token never appears there on its own. setRequest() is the one API that can push
            // an update into the pane the user is looking at, so offer it as an explicit action.
            if (selection.selectionContext() == MessageEditorHttpRequestResponse.SelectionContext.REQUEST) {
                var syncToken = new JMenuItem("ICARUS → Sync AutoAuth Token");
                syncToken.addActionListener(e -> {
                    HttpRequest current = selection.requestResponse().request();
                    HttpRequest updated = autoAuth.injectIfApplicable(current);
                    if (updated != current) selection.setRequest(updated);
                });
                items.add(syncToken);
            }

            if (selection.selectionOffsets().isEmpty()) return;
            if (selection.selectionContext() == MessageEditorHttpRequestResponse.SelectionContext.RESPONSE) {
                var setSource = new JMenuItem("ICARUS → Set as Auth Token Source");
                setSource.addActionListener(e -> autoAuth.setSourceFromSelection(selection));
                items.add(setSource);
            } else {
                var addDestination = new JMenuItem("ICARUS → Add Auth Token Destination");
                addDestination.addActionListener(e -> autoAuth.addDestinationFromSelection(selection));
                items.add(addDestination);
            }
        });

        for (var module : modules) {
            var item = new JMenuItem("ICARUS → " + module.name());
            item.addActionListener(e -> {
                for (var rr : requestResponses) {
                    scanRunner.runModule(module, rr, true);
                }
            });
            items.add(item);
        }

        var evidenceManager = new JMenuItem("ICARUS → Manage Report Evidence");
        evidenceManager.addActionListener(e -> showEvidenceManager());
        items.add(evidenceManager);

        return items;
    }

    @Override
    public RequestToBeSentAction handleHttpRequestToBeSent(HttpRequestToBeSent request) {
        return RequestToBeSentAction.continueWith(autoAuth.processOutgoingRequest(request));
    }

    @Override
    public ResponseReceivedAction handleHttpResponseReceived(HttpResponseReceived response) {
        if (!config.getBool("sh.enabled", true) || !config.getBool("sh.passive", true)) {
            return ResponseReceivedAction.continueWith(response);
        }

        scanRunner.runPassiveScan(response, this::routeFindingsPassive);

        return ResponseReceivedAction.continueWith(response);
    }

    private void routeFindingsPassive(List<Finding> passiveFindings) {
        List<Finding> newFindings = findings.processDeduplication(passiveFindings, true);
        if (newFindings.isEmpty()) return;

        long errorCount = newFindings.stream()
                .filter(f -> f.category() == Category.SERVER_ERROR || f.category() == Category.INFORMATION_DISCLOSURE)
                .count();
        if (errorCount > 0) {
            ToastNotification.show(api.userInterface().swingUtils().suiteFrame(),
                    "ICARUS: Logged " + errorCount + " passive error(s).");
        }
    }

    private void routeFindings(List<Finding> newFindings, boolean isManual) {
        List<Finding> newOrUpdated = findings.processDeduplication(newFindings, false);

        if (isManual && !newFindings.isEmpty()) {
            // Manual scans show results even on a re-run that only produced duplicates —
            // look up every incoming finding by hash, not just the newly-created ones.
            List<FindingRecord> recordsToShow = new ArrayList<>();
            Set<String> seenHashes = new HashSet<>();
            for (Finding f : newFindings) {
                String hash = f.similarityHash();
                if (!seenHashes.add(hash)) continue; // already resolved this hash this batch
                FindingRecord r = findings.getRecordByHash(hash);
                if (r != null && !r.isSuppressed()) recordsToShow.add(r);
            }
            SwingUtilities.invokeLater(() -> showFindingsDialog(recordsToShow));
        } else if (!newOrUpdated.isEmpty() && config.getBool("ui.show_popups", true)) {
            List<FindingRecord> recordsToShow = new ArrayList<>();
            for (Finding f : newOrUpdated) {
                FindingRecord r = findings.getRecordByHash(f.similarityHash());
                if (r != null) recordsToShow.add(r);
            }
            SwingUtilities.invokeLater(() -> showFindingsDialog(recordsToShow));
        }
    }

    public void showFindingsDialog(List<FindingRecord> records) {
        JDialog dialog = new JDialog();
        dialog.setTitle("ICARUS Scan Results");
        dialog.setModal(false);
        dialog.setSize(1200, 800);
        dialog.setLocationRelativeTo(null);

        String[] cols = {"Count", "Severity", "Module", "Type", "Path", "Description"};
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

        JPanel topPanel = new JPanel(new BorderLayout());

        // Add filtering
        JPanel filterPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        filterPanel.add(new JLabel("Filter: "));
        JTextField txtFilter = new JTextField(20);
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
        topPanel.add(new JScrollPane(table), BorderLayout.CENTER);

        // Editors for Request/Response
        burp.api.montoya.ui.editor.HttpRequestEditor reqEditor = api.userInterface().createHttpRequestEditor(burp.api.montoya.ui.editor.EditorOptions.READ_ONLY);
        burp.api.montoya.ui.editor.HttpResponseEditor resEditor = api.userInterface().createHttpResponseEditor(burp.api.montoya.ui.editor.EditorOptions.READ_ONLY);

        JTabbedPane editorsTab = new JTabbedPane();
        editorsTab.addTab("Request", reqEditor.uiComponent());
        editorsTab.addTab("Response", resEditor.uiComponent());

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

        JSplitPane splitPane = new JSplitPane(JSplitPane.VERTICAL_SPLIT, topPanel, editorsTab);
        splitPane.setResizeWeight(0.5);
        dialog.add(splitPane, BorderLayout.CENTER);

        JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton btnRepeater = new JButton("Send to Repeater");
        JButton btnEvidence = new JButton("Save as Evidence");
        JButton btnReport = new JButton("Generate HTML Report");
        JButton btnClose = new JButton("Close");

        btnRepeater.addActionListener(e -> {
            int row = table.getSelectedRow();
            if (row >= 0) {
                int modelRow = table.convertRowIndexToModel(row);
                Finding f = records.get(modelRow).getFinding();
                if (f.evidence() != null) {
                    api.repeater().sendToRepeater(f.evidence().request(), buildTabName(f, modelRow + 1));
                    JOptionPane.showMessageDialog(dialog, "Sent to Repeater.");
                } else {
                    JOptionPane.showMessageDialog(dialog, "No HTTP request evidence attached to this finding.");
                }
            }
        });

        btnEvidence.addActionListener(e -> {
            int row = table.getSelectedRow();
            if (row >= 0) {
                int modelRow = table.convertRowIndexToModel(row);
                Finding f = records.get(modelRow).getFinding();
                if (f.evidence() != null) {
                    evidenceCapture.captureInteractive(f);
                } else {
                    JOptionPane.showMessageDialog(dialog, "No HTTP request evidence attached to this finding.");
                }
            }
        });

        btnReport.addActionListener(e -> generateHtmlReportInteractive(dialog, btnReport, getReportableFindings()));

        btnClose.addActionListener(e -> dialog.dispose());

        btnPanel.add(btnRepeater);
        btnPanel.add(btnEvidence);
        btnPanel.add(btnReport);
        btnPanel.add(btnClose);
        dialog.add(btnPanel, BorderLayout.SOUTH);

        dialog.setVisible(true);
    }

    private Severity parseSeverity(String value) {
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
