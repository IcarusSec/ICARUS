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

    // ICARUS Theme Colors. Text and fills are chosen so normal text stays at or above
    // WCAG AA 4.5:1 on the surface it is drawn on, in both Burp themes.
    // Brand orange #FF6633 is 2.92:1 with white, so buttons use the deeper fill below.
    private static final Color COLOR_ACCENT_FILL = Color.decode("#C2410C"); // white label text is 5.18:1
    private static final Color COLOR_ACCENT_INK_LIGHT = Color.decode("#9A3412"); // 7.31:1 on white
    private static final Color COLOR_NEUTRAL_SLATE_LIGHT = Color.decode("#4A5568");
    private static final Color COLOR_NEUTRAL_SLATE_DARK = Color.decode("#A0AEC0");
    private static final Color COLOR_BACKGROUND_LIGHT = Color.decode("#FFFFFF");
    private static final Color COLOR_BACKGROUND_DARK = Color.decode("#121824");
    private static final Color COLOR_CONTAINER_BG_LIGHT = Color.decode("#F4F6F9");
    private static final Color COLOR_CONTAINER_BG_DARK = Color.decode("#1E293B");
    private static final Color COLOR_ACCENT_GREEN_LIGHT = Color.decode("#146C2E");
    private static final Color COLOR_ACCENT_GREEN_DARK = Color.decode("#00E676");
    private static final Color COLOR_CRITICAL_RED_LIGHT = Color.decode("#B71C1C");
    private static final Color COLOR_CRITICAL_RED_DARK = Color.decode("#FFB4AB");

    // Borders need 3:1 against the page so the edge of a control is visible.
    private static final Color LIGHT_BORDER = Color.decode("#6B7280");
    private static final Color DARK_BORDER = Color.decode("#9CA3AF");
    private static final Color LIGHT_SELECTION_BG = new Color(210, 210, 210);
    private static final Color DARK_SELECTION_BG = new Color(184, 82, 32); // Muted orange accent

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

    /** Orange button fill. White text meets 4.5:1; the fill meets 3:1 on white and on the dark page. */
    public Color accentFill() {
        return COLOR_ACCENT_FILL;
    }

    /** Body-sized accent text. Light and dark each have their own ink. */
    public Color accentInk() {
        return isDarkTheme() ? COLOR_NEUTRAL_SLATE_DARK : COLOR_ACCENT_INK_LIGHT;
    }

    public Color successColor() {
        return isDarkTheme() ? COLOR_ACCENT_GREEN_DARK : COLOR_ACCENT_GREEN_LIGHT;
    }

    public Color dangerColor() {
        return isDarkTheme() ? COLOR_CRITICAL_RED_DARK : COLOR_CRITICAL_RED_LIGHT;
    }

    /**
     * Secondary text that still meets 4.5:1. Light surfaces use the slate; dark surfaces use the
     * lighter slate. A null background is treated as white, which is the light-theme failure mode.
     */
    public static Color mutedOn(Color background) {
        Color bg = background == null ? COLOR_BACKGROUND_LIGHT : background;
        return relativeLuminance(bg) > 0.4 ? COLOR_NEUTRAL_SLATE_LIGHT : COLOR_NEUTRAL_SLATE_DARK;
    }

    /** Black or white, whichever contrasts more. The winner is at least about 4.5:1 on any opaque colour. */
    public static Color ink(Color background) {
        Color bg = background == null ? COLOR_BACKGROUND_LIGHT : background;
        Color dark = new Color(0x1A, 0x1A, 0x1A);
        return contrast(dark, bg) >= contrast(Color.WHITE, bg) ? dark : Color.WHITE;
    }

    /** WCAG contrast ratio of two opaque colours. */
    public static double contrast(Color a, Color b) {
        double l1 = relativeLuminance(a);
        double l2 = relativeLuminance(b);
        double hi = Math.max(l1, l2);
        double lo = Math.min(l1, l2);
        return (hi + 0.05) / (lo + 0.05);
    }

    private static double relativeLuminance(Color color) {
        double r = channel(color.getRed());
        double g = channel(color.getGreen());
        double b = channel(color.getBlue());
        return 0.2126 * r + 0.7152 * g + 0.0722 * b;
    }

    private static double channel(int value) {
        double c = value / 255.0;
        return c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
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
        button.setBackground(COLOR_ACCENT_FILL);
        button.setForeground(Color.WHITE);
        button.setFocusPainted(false);
        button.setFont(UiMetrics.font(UiMetrics.Role.EMPHASIS));
        return button;
    }
    
    /**
     * Creates a styled card panel for grouping fields.
     */
    public JPanel createCardPanel() {
        JPanel panel = new JPanel();
        ui.applyThemeToComponent(panel);
        panel.setBorder(UiMetrics.pad(UiMetrics.MD));
        return panel;
    }

    /**
     * Creates a styled header label using ICARUS tokens.
     */
    public JLabel createHeaderLabel(String text) {
        JLabel label = new JLabel(text);
        label.setForeground(accentInk());
        label.setFont(UiMetrics.font(UiMetrics.Role.TITLE));
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
        Border margin = UiMetrics.pad(UiMetrics.MD);
        Border titled = BorderFactory.createTitledBorder(line, title, 
                javax.swing.border.TitledBorder.LEFT, 
                javax.swing.border.TitledBorder.TOP,
                ui.currentTheme() == Theme.DARK ? UIManager.getFont("TitledBorder.font") : null,
                accentInk());
        return new CompoundBorder(titled, margin);
    }

    /**
     * Styles a text area to look modern.
     */
    public void styleTextArea(JTextArea textArea) {
        ui.applyThemeToComponent(textArea);
        textArea.setBorder(UiMetrics.pad(UiMetrics.SM));
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
