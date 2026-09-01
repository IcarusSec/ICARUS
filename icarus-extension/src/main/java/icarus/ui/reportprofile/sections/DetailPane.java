package icarus.ui.reportprofile.sections;

import icarus.ui.reportprofile.components.VariableChipRow;
import icarus.ui.reportprofile.layout.Breakpoint;
import icarus.ui.reportprofile.layout.ResponsiveSection;
import icarus.ui.reportprofile.theme.ThemeColors;
import icarus.ui.reportprofile.theme.FontLoader;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.*;
import java.awt.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DetailPane implements ResponsiveSection {
    private final JPanel component = new JPanel(new BorderLayout());
    public final JTextField titleField = new JTextField();
    public final JTextPane bodyWell = new JTextPane();
    private final VariableChipRow chipRow;
    
    private final Pattern tokenPattern = Pattern.compile("\\{\\{[a-z_]+\\}\\}");
    
    public DetailPane() {
        bodyWell.setFont(FontLoader.mono(13f));
        chipRow = new VariableChipRow(token -> {
            try {
                bodyWell.requestFocusInWindow();
                bodyWell.getStyledDocument().insertString(bodyWell.getCaretPosition(), token, null);
            } catch (BadLocationException e) {
                e.printStackTrace();
            }
        });

        JPanel topPanel = new JPanel(new BorderLayout());
        topPanel.add(new JLabel("Section title"), BorderLayout.NORTH);
        topPanel.add(titleField, BorderLayout.CENTER);
        
        // BorderLayout (not BoxLayout): it sizes chipRow to the real available
        // width and re-lays it out on resize, so the token WrapLayout actually
        // wraps instead of staying one clipped row.
        JPanel chipWrapper = new JPanel(new BorderLayout(0, 2));
        JLabel helperLabel = new JLabel("Body supports Markdown. Click a token to insert it at the cursor.");
        helperLabel.setFont(helperLabel.getFont().deriveFont(java.awt.Font.ITALIC, 11f));
        chipWrapper.add(helperLabel, BorderLayout.NORTH);
        chipWrapper.add(chipRow, BorderLayout.CENTER);
        topPanel.add(chipWrapper, BorderLayout.SOUTH);

        JScrollPane scrollPane = new JScrollPane(bodyWell,
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        // bodyWell is a JTextPane but the card that hosts DetailPane collapses to
        // preferred height, so without this the editor renders one line tall.
        scrollPane.setPreferredSize(new Dimension(0, 240));

        JPanel bodyWrapper = new JPanel(new BorderLayout());
        JLabel bodyLabel = new JLabel("Body");
        bodyLabel.setBorder(BorderFactory.createEmptyBorder(6, 0, 0, 0));
        bodyWrapper.add(bodyLabel, BorderLayout.NORTH);
        bodyWrapper.add(scrollPane, BorderLayout.CENTER);
                
        component.add(topPanel, BorderLayout.NORTH);
        component.add(bodyWrapper, BorderLayout.CENTER);

        // The token chip row uses WrapLayout, whose preferred width during the
        // first layout pass is "all 8 chips on one line" (~750px). Without a
        // minimum-size cap that width propagates out through SectionFlowPanel
        // and forces the whole Reporting tab into horizontal overflow. Cap it
        // low so GridBag can shrink us; WrapLayout then wraps the chips.
        component.setMinimumSize(new Dimension(320, 280));

        setupSyntaxHighlighting();
    }

    private void setupSyntaxHighlighting() {
        StyledDocument doc = bodyWell.getStyledDocument();
        Style defaultStyle = StyleContext.getDefaultStyleContext().getStyle(StyleContext.DEFAULT_STYLE);
        
        Style tokenStyle = doc.addStyle("token", defaultStyle);
        StyleConstants.setForeground(tokenStyle, ThemeColors.current().accent());
        StyleConstants.setFontFamily(tokenStyle, FontLoader.mono(12f).getFamily());
        
        doc.addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { highlightTokens(); }
            @Override public void removeUpdate(DocumentEvent e) { highlightTokens(); }
            @Override public void changedUpdate(DocumentEvent e) { }
            
            private void highlightTokens() {
                SwingUtilities.invokeLater(() -> {
                    try {
                        String text = doc.getText(0, doc.getLength());
                        doc.setCharacterAttributes(0, text.length(), defaultStyle, true);
                        
                        Matcher m = tokenPattern.matcher(text);
                        while (m.find()) {
                            doc.setCharacterAttributes(m.start(), m.end() - m.start(), tokenStyle, false);
                        }
                    } catch (BadLocationException ex) {
                        ex.printStackTrace();
                    }
                });
            }
        });
    }

    @Override
    public Component component() {
        return component;
    }

    @Override
    public void onBreakpointChanged(Breakpoint bp) {
        // Breakpoint handled by SectionFlowPanel
    }
}
