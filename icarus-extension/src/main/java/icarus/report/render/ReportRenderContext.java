package icarus.report.render;

import icarus.report.model.ReportProfile;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Immutable execution context passed directly into report renderers.
 */
public record ReportRenderContext(
    ReportProfile profile,
    ReportData data,
    Locale locale,
    Path workingDir
) {
    public ReportRenderContext {
        if (locale == null) {
            String tag = profile != null ? profile.locale() : "en";
            locale = Locale.forLanguageTag(tag.replace('_', '-'));
        }
    }

    /** Replaces {{var}} with values from data.variables() */
    public String interpolate(String text) {
        if (text == null || text.isBlank() || data == null || data.variables() == null) return text;
        String res = text;
        for (var e : data.variables().entrySet()) {
            res = res.replace("{{" + e.getKey() + "}}", e.getValue() != null ? e.getValue() : "");
        }
        return res;
    }
}
