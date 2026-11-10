package icarus.ui;

import burp.api.montoya.ui.Theme;
import burp.api.montoya.ui.UserInterface;
import icarus.ui.reportprofile.theme.ThemeColors;

import javax.swing.JTextPane;
import java.awt.Color;
import java.lang.reflect.Proxy;

/**
 * Light and dark text/fill pairs used by the Burp UI meet WCAG AA
 * (4.5:1 for normal text, 3:1 for a control fill against the page).
 * {@code java -cp build_manual/classes:build_manual/test-classes:libs/* icarus.ui.ContrastTest}
 */
public final class ContrastTest {

    public static void main(String[] args) {
        ThemeHelper light = helper(Theme.LIGHT);
        ThemeHelper dark = helper(Theme.DARK);

        text("light accent ink on white", light.accentInk(), Color.WHITE);
        text("light accent ink on container", light.accentInk(), light.getContainerBackgroundColor());
        text("light body on white", light.getForegroundColor(), Color.WHITE);
        text("light body on container", light.getForegroundColor(), light.getContainerBackgroundColor());
        text("light success on container", light.successColor(), light.getContainerBackgroundColor());
        text("light danger on container", light.dangerColor(), light.getContainerBackgroundColor());
        text("light muted on white", ThemeHelper.mutedOn(Color.WHITE), Color.WHITE);
        text("light muted on panel", ThemeHelper.mutedOn(new Color(0xF2F2F2)), new Color(0xF2F2F2));

        text("dark body on page", dark.getForegroundColor(), dark.getBackgroundColor());
        text("dark body on container", dark.getForegroundColor(), dark.getContainerBackgroundColor());
        text("dark success on container", dark.successColor(), dark.getContainerBackgroundColor());
        text("dark danger on container", dark.dangerColor(), dark.getContainerBackgroundColor());
        text("dark muted on page", ThemeHelper.mutedOn(dark.getBackgroundColor()), dark.getBackgroundColor());
        text("dark muted on burp charcoal", ThemeHelper.mutedOn(new Color(0x3C3F41)), new Color(0x3C3F41));

        fill("accent button on white", light.accentFill(), Color.WHITE);
        fill("accent button on dark page", light.accentFill(), dark.getBackgroundColor());
        text("white on accent button", Color.WHITE, light.accentFill());
        text("white on medium badge", Color.WHITE, new Color(0xC2410C));

        ThemeColors.light().textPrimary();
        palette("light", ThemeColors.light(), true);
        palette("dark", ThemeColors.dark(), false);

        EmptyState state = new EmptyState("Nothing here yet.");
        JTextPane pane = (JTextPane) state.getComponent(0);
        text("empty state", pane.getForeground(), state.getBackground());

        System.out.println("ContrastTest: all checks passed.");
    }

    private static void palette(String name, ThemeColors colors, boolean light) {
        Color[] surfaces = {colors.appBg(), colors.panelBg(), colors.wellBg()};
        for (Color bg : surfaces) {
            text(name + " primary", colors.textPrimary(), bg);
            text(name + " secondary", colors.textSecondary(), bg);
            text(name + " tertiary", colors.textTertiary(), bg);
            text(name + " accent", colors.accent(), bg);
        }
        Color chip = composite(colors.accent(), colors.panelBg(), light ? 22 : 36);
        text(name + " accent on chip", colors.accent(), chip);
        // Control edge against the page, 3:1.
        fill(name + " border on panel", colors.border(), colors.panelBg());
        text("ink on " + name + " track", ThemeHelper.ink(colors.borderStrong()), colors.borderStrong());
        text("ink on " + name + " accent", ThemeHelper.ink(colors.accent()), colors.accent());
    }

    private static Color composite(Color fg, Color bg, int alpha) {
        float a = alpha / 255f;
        return new Color(
            Math.round(fg.getRed() * a + bg.getRed() * (1 - a)),
            Math.round(fg.getGreen() * a + bg.getGreen() * (1 - a)),
            Math.round(fg.getBlue() * a + bg.getBlue() * (1 - a)));
    }

    private static void text(String name, Color fg, Color bg) {
        double ratio = ThemeHelper.contrast(fg, bg);
        if (ratio < 4.5) throw new AssertionError(name + " is " + ratio + " (" + hex(fg) + " on " + hex(bg) + ")");
    }

    private static void fill(String name, Color fg, Color bg) {
        double ratio = ThemeHelper.contrast(fg, bg);
        if (ratio < 3.0) throw new AssertionError(name + " is " + ratio + " (" + hex(fg) + " on " + hex(bg) + ")");
    }

    private static String hex(Color c) {
        return String.format("#%02X%02X%02X", c.getRed(), c.getGreen(), c.getBlue());
    }

    private static ThemeHelper helper(Theme theme) {
        UserInterface ui = (UserInterface) Proxy.newProxyInstance(UserInterface.class.getClassLoader(),
            new Class<?>[]{UserInterface.class}, (proxy, method, args) -> {
                if ("currentTheme".equals(method.getName())) return theme;
                if (method.getDeclaringClass() == Object.class) {
                    return switch (method.getName()) {
                        case "hashCode" -> 0;
                        case "equals" -> proxy == args[0];
                        default -> "ui";
                    };
                }
                Class<?> rt = method.getReturnType();
                if (rt == void.class) return null;
                if (rt == boolean.class) return false;
                if (rt == int.class) return 0;
                if (rt == String.class) return "";
                if (rt.isInterface()) return Proxy.newProxyInstance(rt.getClassLoader(), new Class<?>[]{rt}, (p, m, a) -> null);
                return null;
            });
        return new ThemeHelper(ui);
    }
}
