package icarus.report.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Page geometry and margin setup for PDF rendering.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PageBox(
    @JsonProperty("pageSize") String pageSize,
    @JsonProperty("marginTop") float marginTop,
    @JsonProperty("marginBottom") float marginBottom,
    @JsonProperty("marginLeft") float marginLeft,
    @JsonProperty("marginRight") float marginRight,
    @JsonProperty("showHeader") boolean showHeader,
    @JsonProperty("showFooter") boolean showFooter
) {
    public static PageBox a4Default() {
        return new PageBox("A4", 50f, 40f, 40f, 40f, true, true);
    }
}
