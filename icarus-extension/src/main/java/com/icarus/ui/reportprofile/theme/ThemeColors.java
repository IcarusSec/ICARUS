package com.icarus.ui.reportprofile.theme;

import java.awt.Color;
import burp.api.montoya.ui.Theme;

public record ThemeColors(
    Color appBg, Color panelBg, Color wellBg, Color wellBgHover,
    Color border, Color borderStrong,
    Color textPrimary, Color textSecondary, Color textTertiary,
    Color accent, Color accentSoft, Color accentSoftStrong,
    Color secondaryAccent,
    Color sevCritical, Color sevHigh, Color sevMedium, Color sevLow
) {
    private static volatile ThemeColors CURRENT = dark(); // safe default before first read

    public static ThemeColors current() { return CURRENT; }

    /** Call once at panel construction, and again from any lifecycle hook you have. */
    public static void refresh(Theme burpTheme) {
        CURRENT = burpTheme == Theme.LIGHT ? light() : dark();
    }

    public static ThemeColors dark() {
        return new ThemeColors(
            new Color(0x1c1d1f), new Color(0x333639), new Color(0x252729), new Color(0x2a2c2e),
            new Color(0x4a4d50), new Color(0x5c6063),
            new Color(0xe4e4e4), new Color(0xa3a6a8), new Color(0x75787b),
            new Color(0xff6633), withAlpha(0xff6633, 36), withAlpha(0xff6633, 77),
            new Color(0x6e6e6e),
            new Color(0xcc2f2f), new Color(0xd9711e), new Color(0xdba52c), new Color(0x4c8bf5)
        );
    }

    public static ThemeColors light() {
        return new ThemeColors(
            new Color(0xf3f3f3), new Color(0xffffff), new Color(0xececec), new Color(0xe4e4e4),
            new Color(0xd0d0d0), new Color(0xb8b8b8),
            new Color(0x202020), new Color(0x5a5d60), new Color(0x8a8d90),
            new Color(0xe85a2b), withAlpha(0xe85a2b, 22), withAlpha(0xe85a2b, 55),
            new Color(0x6e6e6e),
            new Color(0xc22929), new Color(0xc4630f), new Color(0xb9871f), new Color(0x2f6fd6)
        );
    }

    private static Color withAlpha(int rgb, int alpha) {
        Color c = new Color(rgb);
        return new Color(c.getRed(), c.getGreen(), c.getBlue(), alpha);
    }
}
