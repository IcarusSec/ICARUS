package icarus.evidence;

import icarus.core.Finding;
import icarus.core.Category;
import icarus.core.Severity;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

public class EvidencePhase1Dialog {
    private final EvidenceCapture owner;
    public EvidencePhase1Dialog(EvidenceCapture owner) {
        this.owner = owner;
    }

    public void showPhase1(Finding finding) {
        java.awt.Frame parent = owner.api.userInterface().swingUtils().suiteFrame();
        JFrame editor = new JFrame("ICARUS Evidence Editor - Phase 1: Text Cleanup");
        if (parent != null) editor.setIconImage(parent.getIconImage());
        java.awt.GraphicsConfiguration gc = parent != null ? parent.getGraphicsConfiguration() : null;
        java.awt.Rectangle screenBounds = gc != null ? gc.getBounds() : new java.awt.Rectangle(Toolkit.getDefaultToolkit().getScreenSize());
        
        int maxWidth = Math.min(1200, screenBounds.width - 50);
        int maxHeight = Math.min(800, screenBounds.height - 100);
        editor.setSize(new Dimension(maxWidth, maxHeight));
        editor.setLocationRelativeTo(parent);
        editor.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        editor.setLayout(new BorderLayout());

        // Top Metadata Bar
        JPanel pnlTop = new JPanel(new FlowLayout(FlowLayout.LEFT, 15, 10));
        pnlTop.setBorder(new EmptyBorder(10, 10, 10, 10));

        JLabel lblTitle = new JLabel("Evidence Title:");
        lblTitle.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 14));
        owner.api.userInterface().applyThemeToComponent(lblTitle);
        JTextField txtName = new JTextField(finding.type(), 20);
        txtName.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 14));
        owner.api.userInterface().applyThemeToComponent(txtName);

        JLabel lblDesc = new JLabel("Description:");
        lblDesc.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 14));
        owner.api.userInterface().applyThemeToComponent(lblDesc);
        JTextField txtDesc = new JTextField(finding.description() != null ? finding.description() : "", 40);
        txtDesc.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 14));
        owner.api.userInterface().applyThemeToComponent(txtDesc);

        JLabel lblSev = new JLabel("Status:");
        lblSev.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 14));
        owner.api.userInterface().applyThemeToComponent(lblSev);
        Severity[] normalSeverities = {Severity.CRITICAL, Severity.HIGH, Severity.MEDIUM, Severity.LOW, Severity.INFO};
        Severity[] retestSeverities = {Severity.FIXED, Severity.NOT_FIXED};
        boolean startsAsRetest = finding.severity() == Severity.FIXED || finding.severity() == Severity.NOT_FIXED;
        JComboBox<Severity> cbSev = new JComboBox<>(startsAsRetest ? retestSeverities : normalSeverities);
        cbSev.setSelectedItem(finding.severity());
        cbSev.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 14));

        JCheckBox chkRetest = new JCheckBox("Retest", startsAsRetest);
        chkRetest.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 14));
        owner.api.userInterface().applyThemeToComponent(chkRetest);
        // Remembers the severity picked before switching into Retest mode, so unchecking
        // the box restores it instead of always resetting to CRITICAL.
        Severity[] lastNormalSeverity = {startsAsRetest ? Severity.MEDIUM : finding.severity()};
        chkRetest.addActionListener(e -> {
            if (chkRetest.isSelected()) {
                Object current = cbSev.getSelectedItem();
                if (current instanceof Severity s && s != Severity.FIXED && s != Severity.NOT_FIXED) lastNormalSeverity[0] = s;
                cbSev.setModel(new DefaultComboBoxModel<>(retestSeverities));
                cbSev.setSelectedItem(Severity.FIXED);
            } else {
                cbSev.setModel(new DefaultComboBoxModel<>(normalSeverities));
                cbSev.setSelectedItem(lastNormalSeverity[0]);
            }
        });

        pnlTop.add(lblTitle);
        pnlTop.add(txtName);
        pnlTop.add(lblDesc);
        pnlTop.add(txtDesc);
        pnlTop.add(lblSev);
        pnlTop.add(cbSev);
        owner.api.userInterface().applyThemeToComponent(pnlTop);

        // CWE (and Retest) get their own row — cramming them onto the title/description/status
        // row let FlowLayout wrap them out of sight on anything less than a very wide dialog:
        // BoxLayout sizes pnlTop by its single-row preferred height, so a wrapped second line
        // renders underneath/behind the next row instead of pushing it down.
        JPanel pnlCweRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 15, 4));
        pnlCweRow.setBorder(new EmptyBorder(0, 10, 0, 10));
        JLabel lblCwe = new JLabel("CWE:");
        lblCwe.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 14));
        owner.api.userInterface().applyThemeToComponent(lblCwe);
        JTextField txtCwe = new JTextField(20);
        txtCwe.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 14));
        owner.api.userInterface().applyThemeToComponent(txtCwe);
        pnlCweRow.add(lblCwe);
        pnlCweRow.add(txtCwe);
        pnlCweRow.add(chkRetest);
        owner.api.userInterface().applyThemeToComponent(pnlCweRow);

        // CWE typeahead + tag chips — search-as-you-type against the bundled offline dataset,
        // free text on Enter falls back to a custom weakness label if nothing matches.
        JPanel pnlChips = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        pnlChips.setBorder(new EmptyBorder(0, 10, 5, 10));
        owner.api.userInterface().applyThemeToComponent(pnlChips);
        List<String> selectedCwe = new ArrayList<>();
        // Re-editing an already-tagged finding (e.g. from the Evidence Manager) should show
        // its existing CWE tags as chips, not lose them until the user retypes.
        for (String existingCwe : finding.cweIds()) {
            EvidenceUiHelpers.addCweChip(pnlChips, selectedCwe, existingCwe);
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
                new JLabel(" " + value.label()) {{ setOpaque(true); setBackground(isSelected ? new Color(80, 80, 80) : EvidenceImageRenderer.BG_COLOR); setForeground(EvidenceImageRenderer.TEXT_COLOR); }});
        suggestWindow.add(new JScrollPane(suggestList));
        suggestWindow.setSize(360, 160);

        Runnable hideSuggestions = () -> suggestWindow.setVisible(false);

        suggestList.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                int idx = suggestList.locationToIndex(e.getPoint());
                if (idx >= 0) {
                    EvidenceUiHelpers.addCweChip(pnlChips, selectedCwe, suggestModel.get(idx).id());
                    txtCwe.setText("");
                    hideSuggestions.run();
                }
            }
        });

        Runnable refreshSuggestions = () -> {
            List<CweRepository.Cwe> matches = owner.cweRepository.search(txtCwe.getText());
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
            List<CweRepository.Cwe> matches = owner.cweRepository.search(text);
            String id = matches.isEmpty() ? text : matches.get(0).id();
            EvidenceUiHelpers.addCweChip(pnlChips, selectedCwe, id);
            txtCwe.setText("");
            hideSuggestions.run();
        });
        txtCwe.addFocusListener(new FocusAdapter() {
            @Override
            public void focusLost(FocusEvent e) { hideSuggestions.run(); }
        });

        JPanel pnlTopWrap = new JPanel();
        pnlTopWrap.setLayout(new BoxLayout(pnlTopWrap, BoxLayout.Y_AXIS));
        pnlTopWrap.add(pnlTop);
        pnlTopWrap.add(pnlCweRow);
        pnlTopWrap.add(pnlChips);
        owner.api.userInterface().applyThemeToComponent(pnlTopWrap);

        // Text Areas — include the request line (method + path) and status line
        var rr = finding.evidence();
        String reqContentType = rr.request().headerValue("Content-Type");
        String reqLine = rr.request().method() + " " + rr.request().path() + " " + rr.request().httpVersion() + "\n";
        String reqText = reqLine + rr.request().headers().stream()
                .map(h -> h.name() + ": " + h.value() + "\n")
                .reduce("", String::concat) + EvidenceImageRenderer.formatBody(owner.api, rr.request().body().getBytes(), reqContentType);

        String resText = "";
        if (rr.response() != null) {
            String resContentType = rr.response().headerValue("Content-Type");
            String statusLine = rr.response().httpVersion() + " " + rr.response().statusCode() + " " + rr.response().reasonPhrase() + "\n";
            resText = statusLine + rr.response().headers().stream()
                    .map(h -> h.name() + ": " + h.value() + "\n")
                    .reduce("", String::concat) + EvidenceImageRenderer.formatBody(owner.api, rr.response().body().getBytes(), resContentType);
        }

        // Wrap for the default layout (chk1080 below defaults to checked, i.e. 1920x1080).
        // Wrapping narrower than what's actually rendered wastes space — a line wrapped
        // for the 1200px column only fills ~61% of the 1920px column's real width, needing
        // far more lines (and therefore a much taller image) than necessary. If the user
        // unchecks the box, the chk1080 listener below re-wraps down to the narrower width,
        // which is always a safe direction (splitting an already-short-enough line further).
        int wrapWidth = EvidenceImageRenderer.maxCharsForColumnWidth(1920);
        reqText = EvidenceImageRenderer.wrapEvidenceText(reqText, wrapWidth);
        resText = EvidenceImageRenderer.wrapEvidenceText(resText, wrapWidth);

        JTextArea reqArea = EvidenceUiHelpers.createStyledTextArea(reqText);
        JTextArea resArea = EvidenceUiHelpers.createStyledTextArea(resText);

        EvidenceUiHelpers.attachSmartContextMenu(reqArea);
        EvidenceUiHelpers.attachSmartContextMenu(resArea);

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT);
        split.setLeftComponent(EvidenceUiHelpers.createSmoothScrollPane(reqArea));
        split.setRightComponent(EvidenceUiHelpers.createSmoothScrollPane(resArea));
        split.setResizeWeight(0.5);
        split.setDividerSize(4);
        split.setBorder(null);

        // Bottom Bar
        JPanel pnlBottom = new JPanel(new FlowLayout(FlowLayout.RIGHT, 15, 10));
        pnlBottom.setBorder(new EmptyBorder(5, 10, 10, 10));

        JButton btnCleanNoise = EvidenceUiHelpers.createModernButton("Clean Standard Noise", new Color(70, 70, 70));
        JCheckBox chk1080 = new JCheckBox("Force 1920x1080", true);
        chk1080.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 14));
        // The text areas are wrapped for 1920 above (the checkbox's default). If the user
        // switches to the narrower 1200 layout, re-wrap down to that width — safe, since
        // splitting already-short-enough lines further never loses content. Going back to
        // 1920 needs no action: lines already wrapped for 1200 remain valid (just using less
        // of the wider column than optimal), which is a cosmetic tradeoff, not a bug.
        chk1080.addItemListener(e -> {
            if (e.getStateChange() == ItemEvent.DESELECTED) {
                int narrowWidth = EvidenceImageRenderer.maxCharsForColumnWidth(1200);
                // setText() resets the caret to 0, which would jump the view to the top of
                // a long payload — restore the caret's prior offset (clamped to the new,
                // slightly longer re-wrapped length) so the user doesn't lose their place.
                int reqCaret = reqArea.getCaretPosition();
                int resCaret = resArea.getCaretPosition();
                reqArea.setText(EvidenceImageRenderer.wrapEvidenceText(reqArea.getText(), narrowWidth));
                resArea.setText(EvidenceImageRenderer.wrapEvidenceText(resArea.getText(), narrowWidth));
                reqArea.setCaretPosition(Math.min(reqCaret, reqArea.getDocument().getLength()));
                resArea.setCaretPosition(Math.min(resCaret, resArea.getDocument().getLength()));
            }
        });

        JButton btnAnnotate = EvidenceUiHelpers.createModernButton("Annotate First…", new Color(70, 70, 70));
        JButton btnApply = EvidenceUiHelpers.createModernButton("Apply ➔ Add to Report", EvidenceImageRenderer.ACCENT_COLOR);

        btnCleanNoise.addActionListener(e -> {
            reqArea.setText(EvidenceUiHelpers.cleanNoise(reqArea.getText()));
            resArea.setText(EvidenceUiHelpers.cleanNoise(resArea.getText()));
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
                renderedText = RateLimitTableRenderer.renderRateLimitTable(owner.api, owner.config, updatedFinding, chk1080.isSelected());
            } else {
                renderedText = EvidenceImageRenderer.renderTextToImage(owner.api, owner.config, reqArea.getText(), resArea.getText(),
                        updatedFinding.type(), updatedFinding.description(), updatedFinding.severity().name(), chk1080.isSelected());
            }
            owner.saveAndRegisterEvidence(updatedFinding.withoutMeta("blast_log"), renderedText);
            editor.dispose();
        });

        btnAnnotate.addActionListener(e -> {
            Finding updatedFinding = buildUpdatedFinding.get();
            BufferedImage renderedText;
            if (updatedFinding.category() == Category.RATE_LIMIT && updatedFinding.metadata().containsKey("blast_log")) {
                renderedText = RateLimitTableRenderer.renderRateLimitTable(owner.api, owner.config, updatedFinding, chk1080.isSelected());
            } else {
                renderedText = EvidenceImageRenderer.renderTextToImage(owner.api, owner.config, reqArea.getText(), resArea.getText(),
                        updatedFinding.type(), updatedFinding.description(), updatedFinding.severity().name(), chk1080.isSelected());
            }
            Finding forEvidenceManager = updatedFinding.withoutMeta("blast_log");
            new EvidencePhase2Dialog(owner).showPhase2(editor, forEvidenceManager, renderedText, forEvidenceManager.type());
        });

        pnlBottom.add(btnCleanNoise);
        pnlBottom.add(new JSeparator(SwingConstants.VERTICAL));
        pnlBottom.add(chk1080);
        pnlBottom.add(btnAnnotate);
        pnlBottom.add(btnApply);

        editor.add(pnlTopWrap, BorderLayout.NORTH);
        editor.add(split, BorderLayout.CENTER);
        editor.add(pnlBottom, BorderLayout.SOUTH);
        editor.setVisible(true);
    }
}
