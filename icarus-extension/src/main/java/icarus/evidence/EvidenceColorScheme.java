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
        register(new EvidenceColorScheme("Slate Dark",
            new Color(15, 23, 42),       // Canvas Background: #0f172a
            new Color(8, 12, 23),        // Header Background: #080C17
            new Color(255, 255, 255),    // Text/Values: #ffffff (High Contrast White)
            new Color(203, 213, 225),    // Dim/Muted: #cbd5e1
            new Color(29, 78, 216),      // Border/Divider: #1d4ed8 (Deep Royal Blue)
            new Color(34, 211, 238),     // Title/Labels: #22d3ee (Cyan Accent)
            new Color(34, 211, 238),     // Header Keys: #22d3ee (Cyan Accent)
            new Color(250, 204, 21),     // JSON Keys: Yellowish
            new Color(134, 239, 172),    // JSON Strings: Greenish
            new Color(250, 204, 21),     // JSON Numbers: Yellow
            new Color(74, 222, 128),     // 2xx Success: #4ade80 (Neon green)
            new Color(56, 189, 248),     // 3xx Info: #38bdf8 (Bright blue)
            new Color(250, 204, 21),     // 4xx Warning: #facc15 (Bright yellow)
            new Color(248, 113, 113)     // 5xx Error: #f87171 (Bright red)
        ));

        register(new EvidenceColorScheme("Slate Light",
            new Color(250, 251, 252), new Color(240, 243, 250),
            new Color(15, 23, 42), new Color(100, 116, 139), new Color(230, 232, 242),
            new Color(15, 23, 42), new Color(100, 116, 139),
            new Color(34, 58, 210), new Color(21, 128, 61), new Color(29, 78, 216),
            new Color(21, 128, 61), new Color(34, 58, 210), new Color(180, 130, 0), new Color(200, 40, 40)
        ));
    }

    private static void register(EvidenceColorScheme scheme) {
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
