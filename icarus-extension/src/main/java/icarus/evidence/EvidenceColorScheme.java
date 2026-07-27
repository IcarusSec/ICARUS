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
        register(new EvidenceColorScheme("Minimal Dark",
            new Color(24, 24, 24), new Color(18, 18, 18),
            new Color(212, 212, 212), new Color(120, 120, 120), new Color(50, 50, 50),
            Color.WHITE, new Color(120, 120, 120),
            new Color(150, 150, 150), new Color(106, 171, 115), new Color(104, 151, 187),
            new Color(80, 180, 80), new Color(80, 140, 200), new Color(220, 140, 60), new Color(210, 70, 70)
        ));

        register(new EvidenceColorScheme("Catppuccin Mocha",
            new Color(30, 30, 46), new Color(24, 24, 37),
            new Color(205, 214, 244), new Color(147, 153, 178), new Color(69, 71, 90),
            new Color(205, 214, 244), new Color(147, 153, 178),
            new Color(137, 180, 250), new Color(166, 227, 161), new Color(250, 179, 135),
            new Color(166, 227, 161), new Color(137, 180, 250), new Color(249, 226, 175), new Color(243, 139, 168)
        ));

        register(new EvidenceColorScheme("Tokyo Night",
            new Color(26, 27, 38), new Color(22, 22, 30),
            new Color(169, 177, 214), new Color(86, 95, 137), new Color(52, 59, 88),
            new Color(192, 202, 245), new Color(86, 95, 137),
            new Color(125, 207, 255), new Color(158, 206, 106), new Color(255, 158, 100),
            new Color(158, 206, 106), new Color(125, 207, 255), new Color(224, 175, 104), new Color(247, 118, 142)
        ));

        register(new EvidenceColorScheme("One Dark",
            new Color(40, 44, 52), new Color(33, 37, 43),
            new Color(171, 178, 191), new Color(92, 99, 112), new Color(62, 68, 81),
            new Color(187, 194, 207), new Color(92, 99, 112),
            new Color(198, 120, 221), new Color(152, 195, 121), new Color(209, 154, 102),
            new Color(152, 195, 121), new Color(97, 175, 239), new Color(229, 192, 123), new Color(224, 108, 117)
        ));

        register(new EvidenceColorScheme("One Light",
            new Color(250, 250, 250), new Color(240, 240, 240),
            new Color(56, 58, 66), new Color(120, 120, 120), new Color(210, 210, 210),
            new Color(56, 58, 66), new Color(120, 120, 120),
            new Color(166, 38, 164), new Color(80, 161, 79), new Color(193, 132, 1),
            new Color(80, 161, 79), new Color(64, 120, 242), new Color(193, 132, 1), new Color(228, 86, 73)
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
