package com.icarus.ui.reportprofile.sections;

import com.icarus.ui.reportprofile.components.VariableChipRow;
import com.icarus.ui.reportprofile.layout.Breakpoint;
import com.icarus.ui.reportprofile.layout.ResponsiveSection;
import com.icarus.ui.reportprofile.theme.ThemeColors;
import com.icarus.ui.reportprofile.theme.FontLoader;

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
                bodyWell.getStyledDocument().insertString(bodyWell.getCaretPosition(), token, null);
            } catch (BadLocationException e) {
                e.printStackTrace();
            }
        });

        JPanel topPanel = new JPanel(new BorderLayout());
        topPanel.add(new JLabel("Title"), BorderLayout.NORTH);
        topPanel.add(titleField, BorderLayout.CENTER);
        topPanel.add(chipRow, BorderLayout.SOUTH);

        JScrollPane scrollPane = new JScrollPane(bodyWell, 
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, 
                JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
                
        component.add(topPanel, BorderLayout.NORTH);
        component.add(scrollPane, BorderLayout.CENTER);

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
