package icarus.report.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.*;

/**
 * Self-contained report configuration profile defining layout, renderers, theme, and content rules.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ReportProfile(
    @JsonProperty("schemaVersion") String schemaVersion,          // "1"
    @JsonProperty("id") String id,                                 // uuid for user profile; "builtin:slug" for built-in
    @JsonProperty("name") String name,
    @JsonProperty("locale") String locale,                         // e.g. "pt-BR", "en"
    @JsonProperty("builtIn") boolean builtIn,
    @JsonProperty("basedOnId") String basedOnId,                  // id of source profile if cloned
    @JsonProperty("coverRenderer") CoverRendererId coverRenderer,
    @JsonProperty("findingRenderer") FindingRendererId findingRenderer,
    @JsonProperty("sections") SectionGraph sections,
    @JsonProperty("branding") BrandingConfig branding,
    @JsonProperty("content") ContentPolicy content,
    @JsonProperty("pdfTheme") PdfTheme pdfTheme,
    @JsonProperty("htmlTheme") HtmlTheme htmlTheme
) {
    public static final String CURRENT_SCHEMA_VERSION = "1";

    public ReportProfile {
        if (schemaVersion == null || schemaVersion.isBlank()) schemaVersion = CURRENT_SCHEMA_VERSION;
        if (id == null || id.isBlank()) id = UUID.randomUUID().toString();
        if (name == null || name.isBlank()) name = "Custom Profile";
        if (locale == null || locale.isBlank()) locale = "en";
        if (coverRenderer == null) coverRenderer = CoverRendererId.GRADIENT_HERO;
        if (findingRenderer == null) findingRenderer = FindingRendererId.ELEVATED_CARD;
        if (sections == null) sections = new SectionGraph(Collections.emptyList());
        if (branding == null) branding = BrandingConfig.empty();
        if (content == null) content = ContentPolicy.defaultPolicy();
        if (pdfTheme == null) pdfTheme = PdfTheme.executiveModern();
        if (htmlTheme == null) htmlTheme = HtmlTheme.executiveModern();
    }

    /**
     * Creates a mutable user-owned clone based on this profile.
     */
    @Override
    public String toString() {
        return name + (builtIn ? " [Built-in]" : " [Custom]");
    }

    public ReportProfile createClone(String newId, String newName) {
        return new ReportProfile(
            CURRENT_SCHEMA_VERSION,
            newId != null ? newId : UUID.randomUUID().toString(),
            newName != null ? newName : (this.name() + " (Copy)"),
            this.locale(),
            false, // cloned profile is user-editable
            this.id(),
            this.coverRenderer(),
            this.findingRenderer(),
            this.sections(),
            this.branding(),
            this.content(),
            this.pdfTheme(),
            this.htmlTheme()
        );
    }
}
