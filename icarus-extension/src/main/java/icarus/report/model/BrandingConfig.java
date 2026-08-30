package icarus.report.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Report branding and default document metadata.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record BrandingConfig(
    @JsonProperty("companyLogoPath") String companyLogoPath,
    @JsonProperty("clientLogoPath") String clientLogoPath,
    @JsonProperty("author") String author,
    @JsonProperty("reviewer") String reviewer,
    @JsonProperty("approver") String approver,
    @JsonProperty("classification") String classification,
    @JsonProperty("environment") String environment,
    @JsonProperty("documentTitle") String documentTitle,
    @JsonProperty("team") String team,
    @JsonProperty("component") String component,
    @JsonProperty("requester") String requester,
    @JsonProperty("owner") String owner,
    @JsonProperty("assessmentPeriod") String assessmentPeriod,
    @JsonProperty("method") String method
) {
    public static BrandingConfig empty() {
        return new BrandingConfig(null, null, "", "", "", "Confidencial", "", "", "", "", "", "", "", "");
    }
}
