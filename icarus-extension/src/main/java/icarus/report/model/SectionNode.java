package icarus.report.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Collections;
import java.util.Map;

/**
 * Represents a single section in the report workflow.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SectionNode(
    @JsonProperty("id") String id,
    @JsonProperty("enabled") boolean enabled,
    @JsonProperty("order") int order,
    @JsonProperty("required") boolean required,
    @JsonProperty("rendererKey") String rendererKey,
    @JsonProperty("params") Map<String, String> params
) {
    public SectionNode {
        if (params == null) params = Collections.emptyMap();
    }

    public static SectionNode of(String id, boolean enabled, int order, boolean required) {
        return new SectionNode(id, enabled, order, required, id, Collections.emptyMap());
    }

    public static SectionNode of(String id, boolean enabled, int order, boolean required, String rendererKey, Map<String, String> params) {
        return new SectionNode(id, enabled, order, required, rendererKey != null ? rendererKey : id, params);
    }
}
