package icarus.ui.reportprofile.model;

public final class SectionLabelFormatter {
    private SectionLabelFormatter() {}

    /** EXECUTIVE_SUMMARY -> "Executive Summary". Unknown/odd input degrades gracefully. */
    public static String format(String enumName) {
        if (enumName == null || enumName.isBlank()) return "";
        String[] parts = enumName.toLowerCase().split("_");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(p.charAt(0))).append(p.substring(1));
        }
        return sb.toString();
    }
}
