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

    // ICARUS Theme Colors
    private static final Color COLOR_PRIMARY_NAVY = Color.decode("#002F6C");
    private static final Color COLOR_NEUTRAL_SLATE_LIGHT = Color.decode("#4A5568");
    private static final Color COLOR_NEUTRAL_SLATE_DARK = Color.decode("#A0AEC0");
    private static final Color COLOR_BACKGROUND_LIGHT = Color.decode("#FFFFFF");
    private static final Color COLOR_BACKGROUND_DARK = Color.decode("#121824");
    private static final Color COLOR_CONTAINER_BG_LIGHT = Color.decode("#F4F6F9");
    private static final Color COLOR_CONTAINER_BG_DARK = Color.decode("#1E293B");
    private static final Color COLOR_ACCENT_GREEN_LIGHT = Color.decode("#00C853");
    private static final Color COLOR_ACCENT_GREEN_DARK = Color.decode("#00E676");
    private static final Color COLOR_CRITICAL_RED_LIGHT = Color.decode("#D32F2F");
    private static final Color COLOR_CRITICAL_RED_DARK = Color.decode("#FF1744");

    // Derived standard colors for selection/borders
    private static final Color LIGHT_BORDER = new Color(200, 200, 200);
    private static final Color DARK_BORDER = new Color(85, 85, 85);
    private static final Color LIGHT_SELECTION_BG = new Color(210, 210, 210);
    private static final Color DARK_SELECTION_BG = new Color(75, 110, 175);

    private final UserInterface ui;

    public ThemeHelper(UserInterface ui) {
        this.ui = ui;
    }

    public boolean isDarkTheme() {
        return ui.currentTheme() == Theme.DARK;
    }

    // Rely on Burp's native background colors or ICARUS tokens
    public Color getBackgroundColor() {
        return isDarkTheme() ? COLOR_BACKGROUND_DARK : COLOR_BACKGROUND_LIGHT;
    }
    
    public Color getContainerBackgroundColor() {
        return isDarkTheme() ? COLOR_CONTAINER_BG_DARK : COLOR_CONTAINER_BG_LIGHT;
    }

    public Color getForegroundColor() {
        return isDarkTheme() ? COLOR_NEUTRAL_SLATE_DARK : COLOR_NEUTRAL_SLATE_LIGHT;
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
        ui.applyThemeToComponent(table);
        table.setSelectionBackground(getSelectionBackgroundColor());
        table.setSelectionForeground(isDarkTheme() ? Color.WHITE : Color.BLACK);
        table.setRowHeight(24);
        table.setShowVerticalLines(false);
        table.setIntercellSpacing(new Dimension(0, 0));

        JTableHeader header = table.getTableHeader();
        ui.applyThemeToComponent(header);
        header.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, getBorderColor()));
        ((DefaultTableCellRenderer) header.getDefaultRenderer()).setHorizontalAlignment(JLabel.LEFT);
    }

    /**
     * Creates a standard Burp-styled button.
     */
    public void styleButton(JButton button) {
        ui.applyThemeToComponent(button);
        button.setFocusPainted(false);
    }
    
    /**
     * Creates a primary action button styled with ICARUS tokens.
     */
    public JButton createPrimaryButton(String text) {
        JButton button = new JButton(text);
        button.setBackground(COLOR_PRIMARY_NAVY);
        button.setForeground(Color.WHITE);
        button.setFocusPainted(false);
        button.setFont(button.getFont().deriveFont(Font.BOLD));
        return button;
    }
    
    /**
     * Creates a styled card panel for grouping fields.
     */
    public JPanel createCardPanel() {
        JPanel panel = new JPanel();
        ui.applyThemeToComponent(panel);
        panel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        return panel;
    }

    /**
     * Creates a styled header label using ICARUS tokens.
     */
    public JLabel createHeaderLabel(String text) {
        JLabel label = new JLabel(text);
        label.setForeground(isDarkTheme() ? COLOR_NEUTRAL_SLATE_DARK : COLOR_PRIMARY_NAVY);
        label.setFont(label.getFont().deriveFont(Font.BOLD, 14f));
        return label;
    }
    
    /**
     * Sets up a GridBagLayout container as a standard form.
     */
    public void createFormLayout(JPanel panel) {
        panel.setLayout(new GridBagLayout());
        ui.applyThemeToComponent(panel);
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
                isDarkTheme() ? COLOR_NEUTRAL_SLATE_DARK : COLOR_PRIMARY_NAVY);
        return new CompoundBorder(titled, margin);
    }

    /**
     * Styles a text area to look modern.
     */
    public void styleTextArea(JTextArea textArea) {
        ui.applyThemeToComponent(textArea);
        textArea.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));
    }
    
    /**
     * Generic method to apply base Burp theme to a component and its children.
     */
    public void applyTheme(Component comp) {
        if (comp instanceof JComponent) {
            ui.applyThemeToComponent(comp);
        }
    }
}
