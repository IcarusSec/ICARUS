package icarus.evidence;

import java.awt.Color;
import java.util.Map;
import java.util.LinkedHashMap;

/**
 * Color schemes for evidence screenshot rendering.
 */
public record EvidenceColorScheme(
    String name,
    Color background,
    Color headerBg,
    Color text,
    Color dim,
    Color divider,
    Color titleText,
    Color headerKey,
    Color jsonKey,
    Color jsonString,
    Color jsonNumber,
    Color status2xx,
    Color status3xx,
    Color status4xx,
    Color status5xx
) {
    private static final Map<String, EvidenceColorScheme> SCHEMES = new LinkedHashMap<>();

    static {
        register(new EvidenceColorScheme("Catppuccin",
            new Color(0x1E, 0x1E, 0x2E),   // Background: #1E1E2E (Base)
            new Color(0x18, 0x18, 0x25),   // Header Background: #181825 (Mantle)
            new Color(0xCD, 0xD6, 0xF4),   // Foreground: #CDD6F4 (Text)
            new Color(0x6C, 0x70, 0x86),   // Dim/Comments: #6C7086
            new Color(0x31, 0x32, 0x44),   // Divider: #313244 (Surface0)
            new Color(0xCB, 0xA6, 0xF7),   // Title/Keywords (Mauve): #CBA6F7
            new Color(0x89, 0xB4, 0xFA),   // Header Keys (Blue): #89B4FA
            new Color(0xF9, 0xE2, 0xAF),   // JSON Keys (Yellow): #F9E2AF
            new Color(0xA6, 0xE3, 0xA1),   // JSON Strings (Green): #A6E3A1
            new Color(0xFA, 0xB3, 0x87),   // JSON Numbers (Peach): #FAB387
            new Color(0xA6, 0xE3, 0xA1),   // 2xx Success (Green)
            new Color(0x89, 0xDC, 0xEB),   // 3xx Info (Sky)
            new Color(0xF9, 0xE2, 0xAF),   // 4xx Warning (Yellow)
            new Color(0xF3, 0x8B, 0xA8)    // 5xx Error (Red)
        ));

        register(new EvidenceColorScheme("Dracula",
            new Color(0x28, 0x2A, 0x36),   // Background: #282A36
            new Color(0x21, 0x22, 0x2C),   // Header Background: #21222C
            new Color(0xF8, 0xF8, 0xF2),   // Foreground: #F8F8F2
            new Color(0x62, 0x72, 0xA4),   // Dim/Comments: #6272A4
            new Color(0x44, 0x47, 0x5A),   // Divider: #44475A (Current Line)
            new Color(0xFF, 0x79, 0xC6),   // Title/Keywords (Pink): #FF79C6
            new Color(0x8B, 0xE9, 0xFD),   // Header Keys (Cyan): #8BE9FD
            new Color(0xBD, 0x93, 0xF9),   // JSON Keys (Purple): #BD93F9
            new Color(0xF1, 0xFA, 0x8C),   // JSON Strings (Yellow): #F1FA8C
            new Color(0xBD, 0x93, 0xF9),   // JSON Numbers (Purple): #BD93F9
            new Color(0x50, 0xFA, 0x7B),   // 2xx Success (Green)
            new Color(0x8B, 0xE9, 0xFD),   // 3xx Info (Cyan)
            new Color(0xF1, 0xFA, 0x8C),   // 4xx Warning (Yellow)
            new Color(0xFF, 0x55, 0x55)    // 5xx Error (Red)
        ));

        register(new EvidenceColorScheme("Nord",
            new Color(0x2E, 0x34, 0x40),   // Background: #2E3440 (nord0)
            new Color(0x24, 0x29, 0x33),   // Header Background: darker than nord0
            new Color(0xD8, 0xDE, 0xE9),   // Foreground: #D8DEE9 (nord4)
            new Color(0x4C, 0x56, 0x6A),   // Dim/Comments: #4C566A (nord3)
            new Color(0x43, 0x4C, 0x5E),   // Divider: #434C5E (nord2)
            new Color(0x81, 0xA1, 0xC1),   // Title/Keywords (Frost): #81A1C1 (nord9)
            new Color(0x88, 0xC0, 0xD0),   // Header Keys (Frost Cyan): #88C0D0 (nord8)
            new Color(0xEB, 0xCB, 0x8B),   // JSON Keys (Yellow): #EBCB8B (nord13)
            new Color(0xA3, 0xBE, 0x8C),   // JSON Strings (Aurora Green): #A3BE8C (nord14)
            new Color(0xB4, 0x8E, 0xAD),   // JSON Numbers (Purple): #B48EAD (nord15)
            new Color(0xA3, 0xBE, 0x8C),   // 2xx Success (Green)
            new Color(0x88, 0xC0, 0xD0),   // 3xx Info (Cyan)
            new Color(0xEB, 0xCB, 0x8B),   // 4xx Warning (Yellow)
            new Color(0xBF, 0x61, 0x6A)    // 5xx Error (nord11 Red)
        ));

        register(new EvidenceColorScheme("Gruvbox",
            new Color(0x28, 0x28, 0x28),   // Background: #282828
            new Color(0x1D, 0x20, 0x21),   // Header Background: #1D2021 (dark0_hard)
            new Color(0xEB, 0xDB, 0xB2),   // Foreground: #EBDBB2
            new Color(0x92, 0x83, 0x74),   // Dim/Comments: #928374
            new Color(0x3C, 0x38, 0x36),   // Divider: #3C3836
            new Color(0xFB, 0x49, 0x34),   // Title/Keywords (Red): #FB4934
            new Color(0xFE, 0x80, 0x19),   // Header Keys (Orange): #FE8019
            new Color(0xFA, 0xBD, 0x2F),   // JSON Keys (Yellow): #FABD2F
            new Color(0xB8, 0xBB, 0x26),   // JSON Strings (Green): #B8BB26
            new Color(0xD3, 0x86, 0x9B),   // JSON Numbers (Purple): #D3869B
            new Color(0xB8, 0xBB, 0x26),   // 2xx Success (Green)
            new Color(0x83, 0xA5, 0x98),   // 3xx Info (Blue)
            new Color(0xFA, 0xBD, 0x2F),   // 4xx Warning (Yellow)
            new Color(0xFB, 0x49, 0x34)    // 5xx Error (Red)
        ));

        // Authentic Burp Suite FlatLaf / Darcula dark theme
        register(new EvidenceColorScheme("Burp Carbon",
            new Color(0x2B, 0x2B, 0x2B),   // Background: #2B2B2B (Authentic Burp charcoal)
            new Color(0x21, 0x21, 0x21),   // Header Background: Darker neutral slate
            new Color(0xA9, 0xB7, 0xC6),   // Text/Foreground: Soft off-white (Darcula base)
            new Color(0x70, 0x70, 0x70),   // Dim/Comments: Neutral gray
            new Color(0x3C, 0x3F, 0x41),   // Divider: Subtle FlatLaf border
            new Color(0xFF, 0x66, 0x33),   // Title/Keywords: Iconic PortSwigger Orange (#FF6633)
            new Color(0xCC, 0x78, 0x32),   // Header Keys: Darcula warm brown-orange
            new Color(0x98, 0x76, 0xAA),   // JSON Keys: Muted Lilac/Purple
            new Color(0x6A, 0x87, 0x59),   // JSON Strings: Olive/Sage Green
            new Color(0x68, 0x97, 0xBB),   // JSON Numbers: Soft Steel Blue
            new Color(0x6A, 0x87, 0x59),   // 2xx Success: Olive Green
            new Color(0x68, 0x97, 0xBB),   // 3xx Info: Soft Steel Blue
            new Color(0xFF, 0xC6, 0x6D),   // 4xx Warning: Soft Gold
            new Color(0xFF, 0x6B, 0x68)    // 5xx Error: Muted Coral Red
        ));

        // Modern Midnight Navy theme with PortSwigger Orange accents
        register(new EvidenceColorScheme("Burp Midnight",
            new Color(0x0F, 0x14, 0x23),   // Background: Desaturated Midnight Slate (no vibration)
            new Color(0x0A, 0x0D, 0x17),   // Header Background: Inset dark slate
            new Color(0xDE, 0xE4, 0xED),   // Text/Foreground: Softened icy white
            new Color(0x60, 0x6A, 0x86),   // Dim/Comments: Cool Slate Gray
            new Color(0x22, 0x29, 0x3D),   // Divider: Mid-slate boundary
            new Color(0xFF, 0x70, 0x43),   // Title/Keywords: Bright Coral/Burp Orange
            new Color(0x66, 0xC3, 0xCC),   // Header Keys: Muted Teal
            new Color(0xE5, 0xC0, 0x7B),   // JSON Keys: Warm Honey Gold
            new Color(0x98, 0xC3, 0x79),   // JSON Strings: Soft Sage Green
            new Color(0x66, 0xC3, 0xCC),   // JSON Numbers: Muted Teal
            new Color(0x98, 0xC3, 0x79),   // 2xx Success: Soft Sage Green
            new Color(0x56, 0xB6, 0xC2),   // 3xx Info: Soft Cyan
            new Color(0xE5, 0xC0, 0x7B),   // 4xx Warning: Warm Honey Gold
            new Color(0xE0, 0x6C, 0x75)    // 5xx Error: Rose/Crimson Alert
        ));
    }

    /** Registers a color scheme by name, overwriting any existing scheme with the same name.
     *  Public so other modules (e.g. a future custom-palette settings UI) can add their own. */
    public static void register(EvidenceColorScheme scheme) {
        SCHEMES.put(scheme.name(), scheme);
    }

    public static EvidenceColorScheme get(String name) {
        return SCHEMES.getOrDefault(name, SCHEMES.values().iterator().next());
    }

    public static String[] names() {
        return SCHEMES.keySet().toArray(new String[0]);
    }

    public Color statusColor(int code) {
        if (code >= 200 && code < 300) return status2xx;
        if (code >= 300 && code < 400) return status3xx;
        if (code >= 400 && code < 500) return status4xx;
        if (code >= 500)               return status5xx;
        return text;
    }
}
