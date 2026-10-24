package icarus.ui;

import javax.swing.JLabel;
import javax.swing.JTable;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.FontMetrics;

/**
 * Long UI text is cut with an ellipsis. The full value stays on the tooltip.
 * {@code java -cp build_manual/classes icarus.ui.TextFitTest}
 */
public final class TextFitTest {

    public static void main(String[] args) throws Exception {
        shortTextStaysWhole();
        longTextGainsAnEllipsis();
        tableCellKeepsTheFullValueOnTheTooltip();
        System.out.println("TextFitTest: all checks passed.");
    }

    private static void shortTextStaysWhole() {
        JLabel probe = new JLabel();
        FontMetrics fm = probe.getFontMetrics(probe.getFont());
        check("Short".equals(TextFit.ellipsize("Short", fm, 400)), "short text was modified");
    }

    private static void longTextGainsAnEllipsis() {
        JLabel probe = new JLabel();
        FontMetrics fm = probe.getFontMetrics(probe.getFont());
        String full = "VeryLongUnbrokenToken".repeat(12);
        String shown = TextFit.ellipsize(full, fm, 80);
        check(shown.endsWith(TextFit.ELLIPSIS), "long text was not ellipsized: " + shown);
        check(shown.length() < full.length(), "ellipsized text is not shorter");
        check(fm.stringWidth(shown) <= 80, "ellipsized text is wider than the budget: " + fm.stringWidth(shown));
        check(!shown.contains(full), "ellipsized text still contains the whole value");
    }

    private static void tableCellKeepsTheFullValueOnTheTooltip() throws Exception {
        String full = "Reflected value " + "VeryLongUnbrokenToken".repeat(8);
        SwingUtilities.invokeAndWait(() -> {
            JTable table = new JTable(new Object[][]{{full}}, new Object[]{"Type column heading that is also very long indeed"});
            TextFit.install(table);
            table.setSize(200, 48);
            table.getColumnModel().getColumn(0).setWidth(90);
            Component rendered = table.prepareRenderer(table.getCellRenderer(0, 0), 0, 0);
            check(rendered instanceof JLabel, "renderer was not a label");
            JLabel label = (JLabel) rendered;
            check(full.equals(label.getToolTipText()), "tooltip lost the full cell value");
            check(label.getText().endsWith(TextFit.ELLIPSIS), "cell text was not cut: " + label.getText());
            check(!full.equals(label.getText()), "cell text was not shortened");

            Object headerValue = table.getColumnModel().getColumn(0).getHeaderValue();
            Component header = table.getTableHeader().getDefaultRenderer()
                    .getTableCellRendererComponent(table, headerValue, false, false, -1, 0);
            check(header instanceof JLabel, "header renderer was not a label");
            JLabel headerLabel = (JLabel) header;
            check(headerValue.toString().equals(headerLabel.getToolTipText()), "header tooltip lost the full title");
            check(headerLabel.getText().endsWith(TextFit.ELLIPSIS), "header was not cut: " + headerLabel.getText());
        });
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
