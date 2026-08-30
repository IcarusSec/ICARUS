package icarus.report.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Collections;
import java.util.List;

/**
 * Content inclusion, byte caps, and filtering policy for report rendering.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ContentPolicy(
    @JsonProperty("includeEvidence") boolean includeEvidence,
    @JsonProperty("includeHttpRequest") boolean includeHttpRequest,
    @JsonProperty("includeHttpResponse") boolean includeHttpResponse,
    @JsonProperty("maxRequestBytes") int maxRequestBytes,
    @JsonProperty("maxResponseBytes") int maxResponseBytes,
    @JsonProperty("findingFields") List<FindingField> findingFields,
    @JsonProperty("cweMode") CweMode cweMode,
    @JsonProperty("cweAllowlist") List<String> cweAllowlist,
    @JsonProperty("includeTocBookmarks") boolean includeTocBookmarks
) {
    public ContentPolicy {
        if (findingFields == null) findingFields = List.of(FindingField.values());
        if (cweMode == null) cweMode = CweMode.HARDCODED_CATALOG;
        if (cweAllowlist == null) cweAllowlist = Collections.emptyList();
        if (maxRequestBytes <= 0) maxRequestBytes = 4096;
        if (maxResponseBytes <= 0) maxResponseBytes = 4096;
    }

    public static ContentPolicy defaultPolicy() {
        return new ContentPolicy(
            true, true, true, 4096, 4096,
            List.of(FindingField.values()),
            CweMode.HARDCODED_CATALOG,
            Collections.emptyList(),
            true
        );
    }
}
