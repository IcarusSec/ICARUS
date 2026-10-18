package icarus.ui;

import javax.swing.JButton;
import javax.swing.UIManager;
import java.awt.Font;

/**
 * Headless checks for the shared spacing and type scale.
 * {@code java -cp build_manual/classes icarus.ui.UiMetricsTest}
 */
public final class UiMetricsTest {

    public static void main(String[] args) {
        pageInsetMatchesTheLargeStep();
        rolesStayOnThePlatformFont();
        commandButtonCarriesTextAndTooltip();
        System.out.println("UiMetricsTest: all checks passed.");
    }

    private static void pageInsetMatchesTheLargeStep() {
        var page = UiMetrics.page();
        check(page.getBorderInsets().top == UiMetrics.LG, "page top");
        check(page.getBorderInsets().left == UiMetrics.LG, "page left");
        var gap = UiMetrics.gap(UiMetrics.XS, UiMetrics.SM, UiMetrics.MD, UiMetrics.XL);
        check(gap.top == 4 && gap.left == 8 && gap.bottom == 12 && gap.right == 24, "gap steps");
    }

    private static void rolesStayOnThePlatformFont() {
        Font platform = UIManager.getFont("Label.font");
        if (platform == null) platform = new Font(Font.DIALOG, Font.PLAIN, 13);
        Font caption = UiMetrics.font(UiMetrics.Role.CAPTION);
        Font body = UiMetrics.font(UiMetrics.Role.BODY);
        Font emphasis = UiMetrics.font(UiMetrics.Role.EMPHASIS);
        Font title = UiMetrics.font(UiMetrics.Role.TITLE);
        check(body.getSize2D() == platform.getSize2D(), "body tracks the platform size");
        check(caption.getSize2D() <= body.getSize2D(), "caption is not larger than body");
        check(title.getSize2D() > body.getSize2D(), "title is a step above body");
        check(emphasis.isBold() && title.isBold(), "emphasis and title are bold");
        check(!body.isBold(), "body stays plain");
        check(UiMetrics.mono().getSize() == Math.round(body.getSize2D()), "mono uses the body size");
    }

    private static void commandButtonCarriesTextAndTooltip() {
        JButton button = UiMetrics.commandButton("Add to report", "Include every screenshot");
        check("Add to report".equals(button.getText()), "button text");
        check("Include every screenshot".equals(button.getToolTipText()), "button tooltip");
        check(button.getMargin().top == UiMetrics.XS, "button margin uses the scale");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
