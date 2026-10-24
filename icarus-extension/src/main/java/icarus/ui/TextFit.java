package icarus.ui;

import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JTable;
import javax.swing.ListCellRenderer;
import javax.swing.UIManager;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Insets;

/**
 * Cuts long UI text with an ellipsis and keeps the full value on the tooltip.
 * Stored and exported data is never passed through here.
 */
public final class TextFit {

    public static final String ELLIPSIS = "\u2026";

    private TextFit() {}

    public static String ellipsize(String text, FontMetrics fm, int maxWidth) {
        if (text == null || text.isEmpty()) return "";
        if (fm == null) return text;
        if (fm.stringWidth(text) <= maxWidth) return text;
        int ellipsisWidth = fm.stringWidth(ELLIPSIS);
        if (maxWidth <= ellipsisWidth) return ELLIPSIS;
        int low = 0;
        int high = text.length();
        while (low < high) {
            int mid = (low + high + 1) >>> 1;
            if (fm.stringWidth(text.substring(0, mid)) + ellipsisWidth <= maxWidth) low = mid;
            else high = mid - 1;
        }
        return (low == 0 ? "" : text.substring(0, low)) + ELLIPSIS;
    }

    /** A label that shows a clipped copy and tooltips the full string. */
    public static final class Label extends JLabel {
        private String full = "";
        private boolean clamping;

        public Label() {}

        public Label(String text) {
            setFullText(text);
        }

        public void setFullText(String text) {
            full = text == null ? "" : text;
            setToolTipText(full.isEmpty() ? null : full);
            reclamp();
        }

        public String fullText() {
            return full;
        }

        @Override
        public void setText(String text) {
            if (clamping) {
                super.setText(text);
                return;
            }
            setFullText(text);
        }

        @Override
        public void setBounds(int x, int y, int width, int height) {
            super.setBounds(x, y, width, height);
            reclamp();
        }

        @Override
        public Dimension getMinimumSize() {
            Dimension size = super.getMinimumSize();
            size.width = 0;
            return size;
        }

        private void reclamp() {
            if (clamping) return;
            clamping = true;
            try {
                int width = getWidth();
                if (width <= 0) {
                    super.setText(full);
                    return;
                }
                Insets insets = getInsets();
                int iconWidth = getIcon() == null ? 0 : getIcon().getIconWidth() + getIconTextGap();
                int available = width - insets.left - insets.right - iconWidth;
                super.setText(ellipsize(full, getFontMetrics(getFont()), Math.max(0, available)));
            } finally {
                clamping = false;
            }
        }
    }

    public static void install(JTable table) {
        Renderer renderer = new Renderer(false);
        table.setDefaultRenderer(Object.class, renderer);
        table.setDefaultRenderer(String.class, renderer);
        if (table.getTableHeader() != null) {
            table.getTableHeader().setDefaultRenderer(new Renderer(true));
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public static void install(JList list) {
        ListCellRenderer existing = list.getCellRenderer();
        list.setCellRenderer((lst, value, index, selected, focus) -> {
            Component rendered = existing.getListCellRendererComponent(lst, value, index, selected, focus);
            if (rendered instanceof JLabel label) {
                String full = label.getText() == null ? "" : label.getText();
                int available = Math.max(0, lst.getWidth() - 16);
                if (available > 0) {
                    label.setText(ellipsize(full, label.getFontMetrics(label.getFont()), available));
                }
                label.setToolTipText(full.isEmpty() ? null : full);
            }
            return rendered;
        });
    }

    private static final class Renderer extends DefaultTableCellRenderer {
        private final boolean header;

        Renderer(boolean header) {
            this.header = header;
        }

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected,
                                                        boolean hasFocus, int row, int column) {
            if (header && table != null && table.getTableHeader() != null) {
                setForeground(table.getTableHeader().getForeground());
                setBackground(table.getTableHeader().getBackground());
                setFont(table.getTableHeader().getFont());
                setBorder(UIManager.getBorder("TableHeader.cellBorder"));
                setHorizontalAlignment(LEFT);
            }
            Component component = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
            String full = value == null ? "" : value.toString();
            int available = 0;
            if (table != null && column >= 0 && column < table.getColumnModel().getColumnCount()) {
                Insets insets = getInsets();
                available = table.getColumnModel().getColumn(column).getWidth() - insets.left - insets.right - 4;
            }
            setToolTipText(full.isEmpty() ? null : full);
            setText(ellipsize(full, getFontMetrics(getFont()), Math.max(0, available)));
            if (header) setHorizontalAlignment(LEFT);
            return component;
        }
    }

    /** For custom painted rows: clip {@code text} to the component's current width. */
    public static String fit(JComponent component, String text, int reservedRight) {
        if (text == null) return "";
        int available = component.getWidth() - component.getInsets().left - component.getInsets().right - reservedRight;
        return ellipsize(text, component.getFontMetrics(component.getFont()), Math.max(0, available));
    }
}
