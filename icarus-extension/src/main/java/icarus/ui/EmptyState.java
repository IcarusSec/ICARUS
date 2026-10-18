package icarus.ui;

import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.UIManager;
import java.awt.Color;
import java.awt.GridBagLayout;

/**
 * Centered explanation shown when a tab or panel has nothing to display yet.
 * Colors come from the current look and feel, so light and dark Burp themes both apply.
 */
public final class EmptyState extends JPanel {

    private final JLabel message;

    public EmptyState(String text) {
        super(new GridBagLayout());
        setOpaque(true);
        Color background = UIManager.getColor("Panel.background");
        if (background != null) setBackground(background);
        message = new JLabel("<html><div style='text-align:center;width:420px'>" + escape(text) + "</div></html>");
        message.setName("empty-state");
        message.setFont(UiMetrics.font(UiMetrics.Role.BODY));
        message.setHorizontalAlignment(SwingConstants.CENTER);
        Color foreground = UIManager.getColor("Label.disabledForeground");
        if (foreground == null) foreground = UIManager.getColor("Label.foreground");
        if (foreground != null) message.setForeground(foreground);
        message.setBorder(UiMetrics.pad(UiMetrics.XL));
        add(message);
    }

    /** The HTML shown to the user, for tests. */
    public String shownHtml() {
        return message.getText();
    }

    private static String escape(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
