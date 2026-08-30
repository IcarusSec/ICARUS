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
            String tag = profile != null ? profile.locale() : "pt-BR";
            locale = Locale.forLanguageTag(tag.replace('_', '-'));
        }
    }
}
