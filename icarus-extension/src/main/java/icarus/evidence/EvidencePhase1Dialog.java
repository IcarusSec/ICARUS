package icarus.evidence;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.*;
import java.util.List;
import javax.swing.*;
import burp.api.montoya.MontoyaApi;
import icarus.core.*;
import icarus.ui.*;
import java.awt.event.*;
import java.awt.datatransfer.*;
import java.io.*;
import java.nio.file.*;
import javax.imageio.*;
import javax.swing.border.*;
import javax.swing.event.*;
import javax.swing.text.*;
public class EvidencePhase1Dialog {
    private final EvidenceCapture capture;
    private final MontoyaApi api;
    private final ModuleConfig config;

    public EvidencePhase1Dialog(EvidenceCapture capture, MontoyaApi api, ModuleConfig config) {
        this.capture = capture;
        this.api = api;
        this.config = config;
    }

public void showPhase1(Finding finding) {
        java.awt.Frame parent = api.userInterface().swingUtils().suiteFrame();
        JFrame editor = new JFrame(I18n.t("evidence.phase1.title"));
        if (parent != null) editor.setIconImage(parent.getIconImage());
        java.awt.GraphicsConfiguration gc = parent != null ? parent.getGraphicsConfiguration() : null;
        java.awt.Rectangle screenBounds = gc != null ? gc.getBounds() : new java.awt.Rectangle(java.awt.Toolkit.getDefaultToolkit().getScreenSize());
        
        int maxWidth = Math.min(1200, screenBounds.width - 50);
        int maxHeight = Math.min(800, screenBounds.height - 100);
        editor.setSize(new java.awt.Dimension(maxWidth, maxHeight));
        editor.setLocationRelativeTo(parent);
        editor.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        editor.setLayout(new BorderLayout());

        // Top Header
        JPanel pnlHeader = new JPanel(new BorderLayout(10, 10));
        pnlHeader.setBorder(new EmptyBorder(15, 20, 10, 20));

        JTextField txtName = new JTextField(finding.type());
        txtName.putClientProperty("FlatLaf.styleClass", "h1");
        txtName.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 0, 1, 0, new Color(130, 130, 130)),
            BorderFactory.createEmptyBorder(4, 4, 4, 4)
        ));
        api.userInterface().applyThemeToComponent(txtName);
        pnlHeader.add(txtName, BorderLayout.NORTH);

        JPanel pnlMeta = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = new Insets(5, 5, 5, 5);

        gbc.gridx = 0; gbc.gridy = 0; gbc.weightx = 0;
        JLabel lblDesc = new JLabel(I18n.t("evidence.phase1.label.description"));
        lblDesc.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 14));
        api.userInterface().applyThemeToComponent(lblDesc);
        pnlMeta.add(lblDesc, gbc);

        gbc.gridx = 1; gbc.gridwidth = 3; gbc.weightx = 1.0;
        JTextField txtDesc = new JTextField(finding.description() != null ? finding.description() : "");
        txtDesc.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 14));
        txtDesc.putClientProperty("FlatLaf.style", "arc: 8; margin: 4,8,4,8;");
        api.userInterface().applyThemeToComponent(txtDesc);
        pnlMeta.add(txtDesc, gbc);

        boolean startsAsRetest = finding.severity() == Severity.FIXED || finding.severity() == Severity.NOT_FIXED;
        
        gbc.gridwidth = 1;
        gbc.gridx = 0; gbc.gridy = 1; gbc.weightx = 0;
        JLabel lblSev = new JLabel(I18n.t(startsAsRetest ? "evidence.phase1.label.status" : "evidence.phase1.label.severity"));
        lblSev.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 14));
        api.userInterface().applyThemeToComponent(lblSev);
        pnlMeta.add(lblSev, gbc);

        gbc.gridx = 1; gbc.weightx = 0.2;
        Severity[] normalSeverities = {Severity.CRITICAL, Severity.HIGH, Severity.MEDIUM, Severity.LOW, Severity.INFO};
        Severity[] retestSeverities = {Severity.FIXED, Severity.NOT_FIXED};
        JComboBox<Severity> cbSev = new JComboBox<>(startsAsRetest ? retestSeverities : normalSeverities);
        cbSev.setSelectedItem(finding.severity());
        cbSev.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 14));
        cbSev.putClientProperty("FlatLaf.style", "arc: 8;");
        pnlMeta.add(cbSev, gbc);

        gbc.gridx = 2; gbc.weightx = 0;
        JCheckBox chkRetest = new JCheckBox(I18n.t("evidence.phase1.label.retest"), startsAsRetest);
        chkRetest.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 14));
        chkRetest.putClientProperty("JToggleButton.buttonType", "roundRect");
        api.userInterface().applyThemeToComponent(chkRetest);
        pnlMeta.add(chkRetest, gbc);

        Severity[] lastNormalSeverity = {startsAsRetest ? Severity.MEDIUM : finding.severity()};
        chkRetest.addActionListener(e -> {
            if (chkRetest.isSelected()) {
                lblSev.setText(I18n.t("evidence.phase1.label.status"));
                Object current = cbSev.getSelectedItem();
                if (current instanceof Severity s && s != Severity.FIXED && s != Severity.NOT_FIXED) lastNormalSeverity[0] = s;
                cbSev.setModel(new DefaultComboBoxModel<>(retestSeverities));
                cbSev.setSelectedItem(Severity.FIXED);
            } else {
                lblSev.setText(I18n.t("evidence.phase1.label.severity"));
                cbSev.setModel(new DefaultComboBoxModel<>(normalSeverities));
                cbSev.setSelectedItem(lastNormalSeverity[0]);
            }
        });

        gbc.gridx = 3; gbc.weightx = 0.8;
        JPanel pnlCweWrapper = new JPanel(new BorderLayout(10, 0));
        JLabel lblCwe = new JLabel(I18n.t("evidence.phase1.label.cwe"));
        lblCwe.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 14));
        api.userInterface().applyThemeToComponent(lblCwe);
        JTextField txtCwe = new JTextField();
        txtCwe.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 14));
        txtCwe.putClientProperty("FlatLaf.style", "arc: 8; margin: 4,8,4,8;");
        api.userInterface().applyThemeToComponent(txtCwe);
        pnlCweWrapper.add(lblCwe, BorderLayout.WEST);
        pnlCweWrapper.add(txtCwe, BorderLayout.CENTER);
        pnlMeta.add(pnlCweWrapper, gbc);

        pnlHeader.add(pnlMeta, BorderLayout.CENTER);

        // --- AUTOCOMPLETE LOGIC START ---
        JWindow suggestNameWindow = new JWindow(editor);
        suggestNameWindow.setFocusableWindowState(false);
        DefaultListModel<KnowledgeBaseEntry> suggestNameModel = new DefaultListModel<>();
        JList<KnowledgeBaseEntry> suggestNameList = new JList<>(suggestNameModel);
        suggestNameList.setCellRenderer((list, value, index, isSelected, cellHasFocus) ->
                new JLabel(" " + value.name()) {{ setOpaque(true); setBackground(isSelected ? new Color(80, 80, 80) : EvidenceCapture.BG_COLOR); setForeground(EvidenceCapture.TEXT_COLOR); }});
        suggestNameWindow.add(new JScrollPane(suggestNameList));
        suggestNameWindow.setSize(360, 160);

        Runnable hideNameSuggestions = () -> suggestNameWindow.setVisible(false);
        suggestNameList.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                int idx = suggestNameList.locationToIndex(e.getPoint());
                if (idx >= 0) {
                    KnowledgeBaseEntry entry = suggestNameModel.get(idx);
                    txtName.setText(entry.name());
                    txtDesc.setText(entry.description() != null ? entry.description() : "");
                    Severity parsedSev = icarus.ui.KnowledgeBaseTab.parseSeverity(entry.severity());
                    if (parsedSev != null) {
                        cbSev.setSelectedItem(parsedSev);
                        if (parsedSev != Severity.FIXED && parsedSev != Severity.NOT_FIXED) {
                            lastNormalSeverity[0] = parsedSev;
                        }
                    }
                    hideNameSuggestions.run();
                }
            }
        });

        Runnable refreshNameSuggestions = () -> {
            String text = txtName.getText().toLowerCase();
            List<KnowledgeBaseEntry> matches = icarus.core.VulnerabilityKnowledgeBase.getInstance().getAllEntries().stream()
                    .filter(entry -> entry.name().toLowerCase().contains(text)).toList();
            if (matches.isEmpty() || text.isEmpty()) { hideNameSuggestions.run(); return; }
            suggestNameModel.clear();
            matches.forEach(suggestNameModel::addElement);
            Point loc = txtName.getLocationOnScreen();
            suggestNameWindow.setLocation(loc.x, loc.y + txtName.getHeight());
            suggestNameWindow.setVisible(true);
        };
        txtName.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { refreshNameSuggestions.run(); }
            public void removeUpdate(DocumentEvent e) { refreshNameSuggestions.run(); }
            public void changedUpdate(DocumentEvent e) { refreshNameSuggestions.run(); }
        });
        txtName.addFocusListener(new FocusAdapter() { public void focusLost(FocusEvent e) { hideNameSuggestions.run(); } });
        // --- AUTOCOMPLETE LOGIC END ---

        // CWE typeahead + tag chips — search-as-you-type against the bundled offline dataset,
        // free text on Enter falls back to a custom weakness label if nothing matches.
        JPanel pnlChips = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        pnlChips.setBorder(new EmptyBorder(0, 10, 5, 10));
        api.userInterface().applyThemeToComponent(pnlChips);
        List<String> selectedCwe = new ArrayList<>();
        // Re-editing an already-tagged finding (e.g. from the Evidence Manager) should show
        // its existing CWE tags as chips, not lose them until the user retypes.
        for (String existingCwe : finding.cweIds()) {
            capture.uiHelpers.addCweChip(pnlChips, selectedCwe, existingCwe);
        }

        // A JPopupMenu grabs keyboard focus for its own arrow-key navigation the moment
        // show() is called, which yanks focus out of txtCwe after every keystroke — the
        // user has to click back into the field to keep typing. A non-focusable JWindow
        // (same trick ToastNotification uses) never takes focus at all, so txtCwe keeps it
        // continuously while still being clickable.
        JWindow suggestWindow = new JWindow(editor);
        suggestWindow.setFocusableWindowState(false);
        DefaultListModel<CweRepository.Cwe> suggestModel = new DefaultListModel<>();
        JList<CweRepository.Cwe> suggestList = new JList<>(suggestModel);
        suggestList.setCellRenderer((list, value, index, isSelected, cellHasFocus) ->
                new JLabel(" " + value.label()) {{ setOpaque(true); setBackground(isSelected ? new Color(80, 80, 80) : EvidenceCapture.BG_COLOR); setForeground(EvidenceCapture.TEXT_COLOR); }});
        suggestWindow.add(new JScrollPane(suggestList));
        suggestWindow.setSize(360, 160);

        Runnable hideSuggestions = () -> suggestWindow.setVisible(false);

        suggestList.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                int idx = suggestList.locationToIndex(e.getPoint());
                if (idx >= 0) {
                    capture.uiHelpers.addCweChip(pnlChips, selectedCwe, suggestModel.get(idx).id());
                    txtCwe.setText("");
                    hideSuggestions.run();
                }
            }
        });

        Runnable refreshSuggestions = () -> {
            List<CweRepository.Cwe> matches = capture.cweRepository.search(txtCwe.getText());
            if (matches.isEmpty()) {
                hideSuggestions.run();
                return;
            }
            suggestModel.clear();
            matches.forEach(suggestModel::addElement);
            Point loc = txtCwe.getLocationOnScreen();
            suggestWindow.setLocation(loc.x, loc.y + txtCwe.getHeight());
            suggestWindow.setVisible(true);
        };
        txtCwe.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { refreshSuggestions.run(); }
            public void removeUpdate(DocumentEvent e) { refreshSuggestions.run(); }
            public void changedUpdate(DocumentEvent e) { refreshSuggestions.run(); }
        });
        txtCwe.addActionListener(e -> {
            String text = txtCwe.getText().strip();
            if (text.isEmpty()) return;
            List<CweRepository.Cwe> matches = capture.cweRepository.search(text);
            String id = matches.isEmpty() ? text : matches.get(0).id();
            capture.uiHelpers.addCweChip(pnlChips, selectedCwe, id);
            txtCwe.setText("");
            hideSuggestions.run();
        });
        txtCwe.addFocusListener(new FocusAdapter() {
            @Override
            public void focusLost(FocusEvent e) { hideSuggestions.run(); }
        });

        pnlHeader.add(pnlChips, BorderLayout.SOUTH);

        // Text Areas — include the request line (method + path) and status line
        var rr = finding.evidence();
        String reqContentType = rr.request().headerValue("Content-Type");
        String reqLine = rr.request().method() + " " + rr.request().path() + " " + rr.request().httpVersion() + "\n";
        String reqText = reqLine + rr.request().headers().stream()
                .map(h -> h.name() + ": " + h.value() + "\n")
                .reduce("", String::concat) + capture.imageRenderer.formatBody(rr.request().body().getBytes(), reqContentType);

        String resText = "";
        if (rr.response() != null) {
            String resContentType = rr.response().headerValue("Content-Type");
            String statusLine = rr.response().httpVersion() + " " + rr.response().statusCode() + " " + rr.response().reasonPhrase() + "\n";
            resText = statusLine + rr.response().headers().stream()
                    .map(h -> h.name() + ": " + h.value() + "\n")
                    .reduce("", String::concat) + capture.imageRenderer.formatBody(rr.response().body().getBytes(), resContentType);
        }

        // Wrap for the default layout (chk1080 below defaults to checked, i.e. 1920x1080).
        // Wrapping narrower than what's actually rendered wastes space — a line wrapped
        // for the 1200px column only fills ~61% of the 1920px column's real width, needing
        // far more lines (and therefore a much taller image) than necessary. If the user
        // unchecks the box, the chk1080 listener below re-wraps down to the narrower width,
        // which is always a safe direction (splitting an already-short-enough line further).
        int wrapWidth = capture.phase1Dialog.maxCharsForColumnWidth(1920);
        reqText = capture.phase1Dialog.wrapEvidenceText(reqText, wrapWidth);
        resText = capture.phase1Dialog.wrapEvidenceText(resText, wrapWidth);

        JTextArea reqArea = capture.phase1Dialog.createStyledTextArea(reqText);
        JTextArea resArea = capture.phase1Dialog.createStyledTextArea(resText);

        capture.phase1Dialog.attachSmartContextMenu(reqArea);
        capture.phase1Dialog.attachSmartContextMenu(resArea);

        JScrollPane reqScroll = capture.uiHelpers.createSmoothScrollPane(reqArea);
        reqScroll.putClientProperty("FlatLaf.style", "arc: 8;");
        reqScroll.setBorder(BorderFactory.createLineBorder(new Color(130, 130, 130, 60), 1, true));

        JPanel pnlReq = new JPanel(new BorderLayout(0, 5));
        JLabel lblReq = new JLabel("HTTP REQUEST");
        lblReq.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12));
        lblReq.setForeground(new Color(150, 150, 150));
        pnlReq.add(lblReq, BorderLayout.NORTH);
        pnlReq.add(reqScroll, BorderLayout.CENTER);

        JScrollPane resScroll = capture.uiHelpers.createSmoothScrollPane(resArea);
        resScroll.putClientProperty("FlatLaf.style", "arc: 8;");
        resScroll.setBorder(BorderFactory.createLineBorder(new Color(130, 130, 130, 60), 1, true));

        JPanel pnlRes = new JPanel(new BorderLayout(0, 5));
        JLabel lblRes = new JLabel("HTTP RESPONSE");
        lblRes.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12));
        lblRes.setForeground(new Color(150, 150, 150));
        pnlRes.add(lblRes, BorderLayout.NORTH);
        pnlRes.add(resScroll, BorderLayout.CENTER);

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT);
        split.setLeftComponent(pnlReq);
        split.setRightComponent(pnlRes);
        split.setResizeWeight(0.5);
        split.setDividerSize(4);
        split.putClientProperty("FlatLaf.style", "continuousLayout: true;");
        split.setBorder(new EmptyBorder(10, 20, 10, 20));

        // Bottom Bar
        JPanel pnlBottom = new JPanel(new BorderLayout());
        pnlBottom.setBorder(new EmptyBorder(5, 20, 15, 20));

        JPanel pnlBottomLeft = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        JButton btnCleanNoise = capture.uiHelpers.createModernButton(I18n.t("evidence.phase1.btn.cleanNoise"), new Color(70, 70, 70));
        btnCleanNoise.setIcon(EvidenceUiHelpers.createIcon("trash"));
        
        String btnStyle = "arc: 8; margin: 10,20,10,20; iconTextGap: 10; minimumHeight: 42; font: bold 14 $Button.font.family;";
        btnCleanNoise.putClientProperty("FlatLaf.style", btnStyle);

        JCheckBox chk1080 = new JCheckBox(I18n.t("evidence.phase1.chk.force1080"), true);
        chk1080.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 14));
        
        pnlBottomLeft.add(btnCleanNoise);
        pnlBottomLeft.add(chk1080);

        JPanel pnlBottomRight = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        
        JButton btnAnnotate = capture.uiHelpers.createModernButton(I18n.t("evidence.phase1.btn.annotate"), new Color(70, 70, 70));
        btnAnnotate.setIcon(EvidenceUiHelpers.createIcon("pencil"));
        btnAnnotate.putClientProperty("FlatLaf.style", btnStyle);
        
        JButton btnApply = capture.uiHelpers.createModernButton(I18n.t("evidence.phase1.btn.apply"), EvidenceCapture.ACCENT_COLOR);
        btnApply.setIcon(EvidenceUiHelpers.createIcon("check"));
        btnApply.putClientProperty("FlatLaf.style", btnStyle);
        btnApply.putClientProperty("FlatLaf.styleClass", "default");

        // Force exact matching preferred height across all three action buttons
        int targetH = Math.max(42, Math.max(btnApply.getPreferredSize().height, Math.max(btnCleanNoise.getPreferredSize().height, btnAnnotate.getPreferredSize().height)));
        btnCleanNoise.setPreferredSize(new Dimension(btnCleanNoise.getPreferredSize().width + 12, targetH));
        btnAnnotate.setPreferredSize(new Dimension(btnAnnotate.getPreferredSize().width + 12, targetH));
        btnApply.setPreferredSize(new Dimension(btnApply.getPreferredSize().width + 12, targetH));

        pnlBottomRight.add(btnAnnotate);
        pnlBottomRight.add(btnApply);
        
        pnlBottom.add(pnlBottomLeft, BorderLayout.WEST);
        pnlBottom.add(pnlBottomRight, BorderLayout.EAST);

        btnCleanNoise.addActionListener(e -> {
            reqArea.setText(capture.phase1Dialog.cleanNoise(reqArea.getText()));
            resArea.setText(capture.phase1Dialog.cleanNoise(resArea.getText()));
        });

        // Shared by both buttons below — builds the edited Finding once, from whatever's
        // currently in the title/description/severity/CWE fields.
        java.util.function.Supplier<Finding> buildUpdatedFinding = () -> {
            Finding.Builder builder = Finding.builder(finding.module(), txtName.getText())
                    .description(txtDesc.getText())
                    .severity((Severity) cbSev.getSelectedItem())
                    .category(finding.category())
                    .path(finding.path())
                    .evidence(finding.evidence());
            finding.metadata().forEach(builder::meta);
            selectedCwe.forEach(builder::cwe);
            return builder.build();
        };

        btnApply.addActionListener(e -> {
            Finding updatedFinding = buildUpdatedFinding.get();
            BufferedImage renderedText;
            if (updatedFinding.category() == Category.RATE_LIMIT && updatedFinding.metadata().containsKey("blast_log")) {
                renderedText = capture.tableRenderer.renderRateLimitTable(updatedFinding, chk1080.isSelected());
            } else {
                renderedText = capture.imageRenderer.renderTextToImage(reqArea.getText(), resArea.getText(),
                        updatedFinding.type(), updatedFinding.description(), updatedFinding.severity().name(), chk1080.isSelected());
            }
            capture.saveAndRegisterEvidence(updatedFinding.withoutMeta("blast_log"), renderedText);
            editor.dispose();
        });

        btnAnnotate.addActionListener(e -> {
            Finding updatedFinding = buildUpdatedFinding.get();
            BufferedImage renderedText;
            if (updatedFinding.category() == Category.RATE_LIMIT && updatedFinding.metadata().containsKey("blast_log")) {
                renderedText = capture.tableRenderer.renderRateLimitTable(updatedFinding, chk1080.isSelected());
            } else {
                renderedText = capture.imageRenderer.renderTextToImage(reqArea.getText(), resArea.getText(),
                        updatedFinding.type(), updatedFinding.description(), updatedFinding.severity().name(), chk1080.isSelected());
            }
            Finding forEvidenceManager = updatedFinding.withoutMeta("blast_log");
            capture.phase2Dialog.showPhase2(editor, forEvidenceManager, renderedText, forEvidenceManager.type());
        });


        editor.add(pnlHeader, BorderLayout.NORTH);
        editor.add(split, BorderLayout.CENTER);
        editor.add(pnlBottom, BorderLayout.SOUTH);
        editor.setVisible(true);
    }

public JTextArea createStyledTextArea(String text) {
        JTextArea ta = new JTextArea(text);
        ta.setFont(EvidenceCapture.MONO_FONT);
        ta.setBackground(EvidenceCapture.BG_COLOR);
        ta.setForeground(EvidenceCapture.TEXT_COLOR);
        ta.setCaretColor(Color.WHITE);
        ta.setMargin(new Insets(10, 15, 10, 15));
        ta.setTabSize(4);
        ta.setLineWrap(true);
        ta.setWrapStyleWord(true);

        javax.swing.undo.UndoManager undoManager = new javax.swing.undo.UndoManager();
        ta.getDocument().addUndoableEditListener(e -> undoManager.addEdit(e.getEdit()));

        InputMap im = ta.getInputMap(JComponent.WHEN_FOCUSED);
        ActionMap am = ta.getActionMap();

        int ctrl = Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_Z, ctrl), "Undo");
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_Y, ctrl), "Redo");
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_Z, ctrl | InputEvent.SHIFT_DOWN_MASK), "Redo");

        am.put("Undo", new AbstractAction() {
            public void actionPerformed(ActionEvent e) {
                if (undoManager.canUndo()) undoManager.undo();
            }
        });
        am.put("Redo", new AbstractAction() {
            public void actionPerformed(ActionEvent e) {
                if (undoManager.canRedo()) undoManager.redo();
            }
        });

        return ta;
    }

public void attachSmartContextMenu(JTextArea ta) {
        JPopupMenu menu = new JPopupMenu();

        JMenuItem itmTruncate = new JMenuItem(I18n.t("evidence.phase1.menu.truncate"), EvidenceUiHelpers.createIcon("scissors"));
        itmTruncate.addActionListener(e -> capture.phase1Dialog.replaceSelection(ta, I18n.t("evidence.phase1.text.truncated")));

        JMenuItem itmRedact = new JMenuItem(I18n.t("evidence.phase1.menu.redact"), EvidenceUiHelpers.createIcon("eye-off"));
        itmRedact.addActionListener(e -> capture.phase1Dialog.replaceSelection(ta, I18n.t("evidence.phase1.text.redacted")));

        JMenuItem itmRemoveLine = new JMenuItem(I18n.t("evidence.phase1.menu.removeLine"), EvidenceUiHelpers.createIcon("circle-minus"));
        itmRemoveLine.addActionListener(e -> capture.phase1Dialog.removeCurrentLine(ta));

        menu.add(itmTruncate);
        menu.add(itmRedact);
        menu.addSeparator();
        menu.add(itmRemoveLine);

        ta.setComponentPopupMenu(menu);
    }

public void replaceSelection(JTextArea ta, String replacement) {
        if (ta.getSelectedText() != null) {
            ta.replaceSelection(replacement);
        }
    }

public void removeCurrentLine(JTextArea ta) {
        try {
            int caret = ta.getCaretPosition();
            int line = ta.getLineOfOffset(caret);
            int start = ta.getLineStartOffset(line);
            int end = ta.getLineEndOffset(line);
            ta.getDocument().remove(start, end - start);
        } catch (BadLocationException ex) {
            // ignore
        }
    }

public String cleanNoise(String text) {
        String[] noisyHeaders = {
            "Accept-Language:", "Accept-Encoding:", "Connection:", "Upgrade-Insecure-Requests:",
            "Sec-Fetch-", "Sec-Ch-Ua"
        };
        StringBuilder sb = new StringBuilder();
        for (String line : text.split("\n")) {
            boolean isNoise = false;
            for (String noise : noisyHeaders) {
                if (line.toLowerCase().startsWith(noise.toLowerCase())) {
                    isNoise = true;
                    break;
                }
            }
            if (!isNoise) {
                sb.append(line).append("\n");
            }
        }
        return sb.toString();
    }

public String truncate(String text, Graphics2D g, int maxWidth) {
        if (g.getFontMetrics().stringWidth(text) <= maxWidth) return text;
        String dot = "...";
        int dotWidth = g.getFontMetrics().stringWidth(dot);
        while (text.length() > 0 && g.getFontMetrics().stringWidth(text) + dotWidth > maxWidth) {
            text = text.substring(0, text.length() - 1);
        }
        return text + dot;
    }

public int maxCharsForColumnWidth(int imgWidth) {
        int columnBudget = imgWidth / 2 - 78; // adjusted for Card layout paddings (24px internal + 20px external)
        BufferedImage probe = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = probe.createGraphics();
        try {
            FontMetrics monoFm = g.getFontMetrics(EvidenceCapture.MONO_FONT);
            FontMetrics boldFm = g.getFontMetrics(EvidenceCapture.BOLD_FONT);
            int worstCharWidth = 1;
            for (int c = 32; c < 127; c++) {
                worstCharWidth = Math.max(worstCharWidth, monoFm.charWidth(c));
                worstCharWidth = Math.max(worstCharWidth, boldFm.charWidth(c));
            }
            return Math.max(1, columnBudget / worstCharWidth);
        } finally {
            g.dispose();
        }
    }

public String wrapEvidenceText(String text, int maxLineLength) {
        StringBuilder sb = new StringBuilder();
        for (String line : text.split("\n")) {
            if (line.length() <= maxLineLength) {
                sb.append(line).append("\n");
                continue;
            }

            int indentLen = 0;
            while (indentLen < line.length() && (line.charAt(indentLen) == ' ' || line.charAt(indentLen) == '\t')) {
                indentLen++;
            }
            if (indentLen >= line.length()) {
                indentLen = 0; // whole line is whitespace; nothing to preserve
            }
            String indent = line.substring(0, indentLen);
            int contentBudget = Math.max(10, maxLineLength - indentLen);

            int start = indentLen;
            while (start < line.length()) {
                int end = Math.min(start + contentBudget, line.length());
                if (end < line.length()) {
                    int lastSpace = line.lastIndexOf(' ', end);
                    // Only wrap at space if it doesn't leave the line mostly empty.
                    // This prevents isolating "POST " from a massive URL string.
                    if (lastSpace > start + contentBudget / 2) {
                        end = lastSpace;
                    }
                }
                sb.append(indent).append(line, start, end).append("\n");
                start = end;
                while (start < line.length() && line.charAt(start) == ' ') start++;
            }
        }
        return sb.toString();
    }
}
