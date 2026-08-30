package icarus.report.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import icarus.core.Severity;
import java.util.Collections;
import java.util.Map;

/**
 * Visual styling and typography configuration for OpenPDF rendering.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PdfTheme(
    @JsonProperty("primaryHex") String primaryHex,
    @JsonProperty("secondaryHex") String secondaryHex,
    @JsonProperty("textHex") String textHex,
    @JsonProperty("headingHex") String headingHex,
    @JsonProperty("tableHeaderHex") String tableHeaderHex,
    @JsonProperty("severityHex") Map<Severity, String> severityHex,
    @JsonProperty("fontStack") String fontStack,
    @JsonProperty("baseFontSize") int baseFontSize,
    @JsonProperty("pageBox") PageBox pageBox
) {
    public PdfTheme {
        if (primaryHex == null || primaryHex.isBlank()) primaryHex = "#FF6633";
        if (secondaryHex == null || secondaryHex.isBlank()) secondaryHex = "#6E6E6E";
        if (textHex == null || textHex.isBlank()) textHex = "#202020";
        if (headingHex == null || headingHex.isBlank()) headingHex = "#1A1A1A";
        if (tableHeaderHex == null || tableHeaderHex.isBlank()) tableHeaderHex = "#F7F7F7";
        if (fontStack == null || fontStack.isBlank()) fontStack = "Helvetica";
        if (baseFontSize <= 0) baseFontSize = 10;
        if (pageBox == null) pageBox = PageBox.a4Default();
        if (severityHex == null || severityHex.isEmpty()) {
            severityHex = defaultSeverityHex();
        }
    }

    public static Map<Severity, String> defaultSeverityHex() {
        return Map.of(
            Severity.CRITICAL, "#CC2E2E",
            Severity.HIGH, "#D9711F",
            Severity.MEDIUM, "#B38F00",
            Severity.LOW, "#2F7A77",
            Severity.INFO, "#6E6E6E",
            Severity.FIXED, "#2F9E44",
            Severity.NOT_FIXED, "#CC2E2E"
        );
    }

    public static PdfTheme executiveModern() {
        return new PdfTheme(
            "#FF6633",
            "#6E6E6E",
            "#202020",
            "#1A1A1A",
            "#F7F7F7",
            defaultSeverityHex(),
            "Helvetica",
            10,
            PageBox.a4Default()
        );
    }

    public static PdfTheme classicTechnical() {
        return new PdfTheme(
            "#002F6C",
            "#4A5568",
            "#1A1A1A",
            "#002F6C",
            "#E2E8F0",
            defaultSeverityHex(),
            "Helvetica",
            9,
            PageBox.a4Default()
        );
    }
}
