package icarus.report.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import icarus.core.Severity;
import java.util.Map;

/**
 * Visual styling and CSS configuration for HTML report generation.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record HtmlTheme(
    @JsonProperty("primaryHex") String primaryHex,
    @JsonProperty("secondaryHex") String secondaryHex,
    @JsonProperty("backgroundHex") String backgroundHex,
    @JsonProperty("cardBackgroundHex") String cardBackgroundHex,
    @JsonProperty("textHex") String textHex,
    @JsonProperty("borderHex") String borderHex,
    @JsonProperty("severityHex") Map<Severity, String> severityHex,
    @JsonProperty("fontStack") String fontStack,
    @JsonProperty("dark") boolean dark
) {
    public HtmlTheme {
        if (primaryHex == null || primaryHex.isBlank()) primaryHex = "#FF6633";
        if (secondaryHex == null || secondaryHex.isBlank()) secondaryHex = "#6E6E6E";
        if (backgroundHex == null || backgroundHex.isBlank()) backgroundHex = dark ? "#1A1A1A" : "#FFFFFF";
        if (cardBackgroundHex == null || cardBackgroundHex.isBlank()) cardBackgroundHex = dark ? "#262626" : "#F7F7F7";
        if (textHex == null || textHex.isBlank()) textHex = dark ? "#E8E8E8" : "#1A1A1A";
        if (borderHex == null || borderHex.isBlank()) borderHex = dark ? "#3A3A3A" : "#DDDDDD";
        if (fontStack == null || fontStack.isBlank()) {
            fontStack = "-apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Helvetica, Arial, sans-serif";
        }
        if (severityHex == null || severityHex.isEmpty()) {
            severityHex = PdfTheme.defaultSeverityHex();
        }
    }

    public static HtmlTheme executiveModern() {
        return new HtmlTheme(
            "#FF6633",
            "#6E6E6E",
            "#FFFFFF",
            "#F7F7F7",
            "#1A1A1A",
            "#DDDDDD",
            PdfTheme.defaultSeverityHex(),
            "-apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Helvetica, Arial, sans-serif",
            false
        );
    }

    public static HtmlTheme classicTechnical() {
        return new HtmlTheme(
            "#002F6C",
            "#4A5568",
            "#FFFFFF",
            "#F8FAFC",
            "#0F172A",
            "#CBD5E1",
            PdfTheme.defaultSeverityHex(),
            "-apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Helvetica, Arial, sans-serif",
            false
        );
    }
}
