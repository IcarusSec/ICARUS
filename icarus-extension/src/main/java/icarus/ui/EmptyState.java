package icarus.ui;

import javax.swing.JPanel;
import javax.swing.JTextPane;
import javax.swing.UIManager;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.GridBagLayout;

/**
 * Centered explanation shown when a tab or panel has nothing to display yet.
 * Colors come from the current look and feel, so light and dark Burp themes both apply.
 * Plain text on purpose: Burp turns off HTML rendering in extension labels, so an
 * {@code <html>} label would show its markup.
 */
public final class EmptyState extends JPanel {

    private static final int WIDTH = 420;
    private final JTextPane message;

    public EmptyState(String text) {
        super(new GridBagLayout());
        setOpaque(true);
        Color background = UIManager.getColor("Panel.background");
        if (background != null) setBackground(background);
        message = new JTextPane();
        message.setName("empty-state");
        message.setEditable(false);
        message.setFocusable(false);
        message.setOpaque(false);
        message.setBorder(UiMetrics.pad(UiMetrics.XL));
        message.setFont(UiMetrics.font(UiMetrics.Role.BODY));
        // Disabled-label grey is below 4.5:1 on a light Burp panel. Secondary text still
        // has to be readable; it is not a disabled control.
        message.setForeground(ThemeHelper.mutedOn(background != null ? background : UIManager.getColor("Panel.background")));
        message.setText(text == null ? "" : text);
        StyledDocument doc = message.getStyledDocument();
        SimpleAttributeSet center = new SimpleAttributeSet();
        StyleConstants.setAlignment(center, StyleConstants.ALIGN_CENTER);
        doc.setParagraphAttributes(0, doc.getLength(), center, false);
        message.setSize(new Dimension(WIDTH, Short.MAX_VALUE));
        message.setPreferredSize(new Dimension(WIDTH, message.getPreferredSize().height));
        add(message);
    }

    /** The text shown to the user, for tests. */
    public String shownText() {
        return message.getText();
    }
}
