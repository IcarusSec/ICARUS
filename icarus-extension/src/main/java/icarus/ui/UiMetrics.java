package icarus.ui;

import javax.swing.JButton;
import javax.swing.UIManager;
import javax.swing.border.EmptyBorder;
import java.awt.Font;
import java.awt.Insets;

/**
 * One spacing and type scale for every ICARUS tab.
 * Steps are relative to the platform font from {@link UIManager}, so Burp's
 * light and dark themes keep their own face and size. This class does not
 * choose colors.
 */
public final class UiMetrics {

    public static final int XS = 4;
    public static final int SM = 8;
    public static final int MD = 12;
    public static final int LG = 16;
    public static final int XL = 24;

    public enum Role {
        /** Hints, badges, and secondary counts. One step under the platform body size. */
        CAPTION,
        /** Platform body. */
        BODY,
        /** Bold body: field labels and section names. */
        EMPHASIS,
        /** Panel titles. Two steps above the body, bold. */
        TITLE
    }

    private UiMetrics() {}

    public static EmptyBorder pad(int all) {
        return new EmptyBorder(all, all, all, all);
    }

    public static EmptyBorder pad(int vertical, int horizontal) {
        return new EmptyBorder(vertical, horizontal, vertical, horizontal);
    }

    public static EmptyBorder pad(int top, int left, int bottom, int right) {
        return new EmptyBorder(top, left, bottom, right);
    }

    /** Inset used around a tab's page content. */
    public static EmptyBorder page() {
        return pad(LG);
    }

    public static Insets gap(int all) {
        return new Insets(all, all, all, all);
    }

    public static Insets gap(int vertical, int horizontal) {
        return new Insets(vertical, horizontal, vertical, horizontal);
    }

    public static Insets gap(int top, int left, int bottom, int right) {
        return new Insets(top, left, bottom, right);
    }

    public static Font font(Role role) {
        Font base = platformFont();
        float size = base.getSize2D();
        return switch (role) {
            case CAPTION -> base.deriveFont(Font.PLAIN, Math.max(11f, size - 2f));
            case BODY -> base.deriveFont(Font.PLAIN, size);
            case EMPHASIS -> base.deriveFont(Font.BOLD, size);
            case TITLE -> base.deriveFont(Font.BOLD, size + 2f);
        };
    }

    /** Monospace at the body size, for logs and code-like fields. */
    public static Font mono() {
        Font body = font(Role.BODY);
        Font platform = UIManager.getFont("TextArea.font");
        String family = (platform != null && platform.getFamily() != null) ? platform.getFamily() : Font.MONOSPACED;
        return new Font(family, Font.PLAIN, Math.round(body.getSize2D()));
    }

    /** A labeled command button on the shared scale. Tooltips stay optional extras. */
    public static JButton commandButton(String text, String tooltip) {
        JButton button = new JButton(text);
        button.setFont(font(Role.BODY));
        button.setMargin(gap(XS, SM, XS, SM));
        if (tooltip != null && !tooltip.isBlank()) {
            button.setToolTipText(tooltip);
        }
        return button;
    }

    private static Font platformFont() {
        Font base = UIManager.getFont("Label.font");
        if (base == null) {
            base = new Font(Font.DIALOG, Font.PLAIN, 13);
        }
        return base;
    }
}
