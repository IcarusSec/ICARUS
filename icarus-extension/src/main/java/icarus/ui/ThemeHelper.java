package icarus.ui;

import burp.api.montoya.ui.Theme;
import burp.api.montoya.ui.UserInterface;

import javax.swing.*;
import javax.swing.border.Border;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.JTableHeader;
import java.awt.*;

/**
 * Helper class to apply Burp Suite native styling to standard Swing components.
 */
public class ThemeHelper {

    // Burp Suite standard colors (approximated for consistency)
    private static final Color BURP_ORANGE = new Color(255, 102, 51);
    
    // Light Theme Colors
    private static final Color LIGHT_BG = new Color(242, 242, 242);
    private static final Color LIGHT_FG = new Color(0, 0, 0);
    private static final Color LIGHT_BORDER = new Color(200, 200, 200);
    private static final Color LIGHT_SELECTION_BG = new Color(210, 210, 210);
    
    // Dark Theme Colors
    private static final Color DARK_BG = new Color(60, 63, 65);
    private static final Color DARK_FG = new Color(187, 187, 187);
    private static final Color DARK_BORDER = new Color(85, 85, 85);
    private static final Color DARK_SELECTION_BG = new Color(75, 110, 175);

    private final UserInterface ui;

    public ThemeHelper(UserInterface ui) {
        this.ui = ui;
    }

    public boolean isDarkTheme() {
        return ui.currentTheme() == Theme.DARK;
    }

    public Color getBackgroundColor() {
        return isDarkTheme() ? DARK_BG : LIGHT_BG;
    }

    public Color getForegroundColor() {
        return isDarkTheme() ? DARK_FG : LIGHT_FG;
    }

    public Color getBorderColor() {
        return isDarkTheme() ? DARK_BORDER : LIGHT_BORDER;
    }

    public Color getSelectionBackgroundColor() {
        return isDarkTheme() ? DARK_SELECTION_BG : LIGHT_SELECTION_BG;
    }

    /**
     * Applies standard Burp styling to a JTable.
     */
    public void styleTable(JTable table) {
        table.setBackground(getBackgroundColor());
        table.setForeground(getForegroundColor());
        table.setGridColor(getBorderColor());
        table.setSelectionBackground(getSelectionBackgroundColor());
        table.setSelectionForeground(isDarkTheme() ? Color.WHITE : Color.BLACK);
        table.setRowHeight(24);
        table.setShowVerticalLines(false);
        table.setIntercellSpacing(new Dimension(0, 0));

        JTableHeader header = table.getTableHeader();
        header.setBackground(getBackgroundColor());
        header.setForeground(getForegroundColor());
        header.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, getBorderColor()));
        ((DefaultTableCellRenderer) header.getDefaultRenderer()).setHorizontalAlignment(JLabel.LEFT);
    }

    /**
     * Creates a standard Burp-styled button.
     */
    public void styleButton(JButton button) {
        button.setBackground(getBackgroundColor());
        button.setForeground(getForegroundColor());
        button.setFocusPainted(false);
    }
    
    /**
     * Creates a styled title border for sections.
     */
    public Border createSectionBorder(String title) {
        Border line = new LineBorder(getBorderColor(), 1, true);
        Border margin = new EmptyBorder(10, 10, 10, 10);
        Border titled = BorderFactory.createTitledBorder(line, title, 
                javax.swing.border.TitledBorder.LEFT, 
                javax.swing.border.TitledBorder.TOP,
                ui.currentTheme() == Theme.DARK ? UIManager.getFont("TitledBorder.font") : null,
                getForegroundColor());
        return new CompoundBorder(titled, margin);
    }

    /**
     * Styles a text area to look modern.
     */
    public void styleTextArea(JTextArea textArea) {
        textArea.setBackground(getBackgroundColor());
        textArea.setForeground(getForegroundColor());
        textArea.setCaretColor(getForegroundColor());
        textArea.setBorder(BorderFactory.createCompoundBorder(
            new LineBorder(getBorderColor()),
            new EmptyBorder(5, 5, 5, 5)
        ));
        ui.applyThemeToComponent(textArea);
    }
    
    /**
     * Generic method to apply base Burp theme to a component and its children.
     * Note: MontoyaApi's ui.applyThemeToComponent handles most of the work,
     * but we sometimes need custom tweaks.
     */
    public void applyTheme(Component comp) {
        if (comp instanceof JComponent) {
            ui.applyThemeToComponent(comp);
        }
    }
}
