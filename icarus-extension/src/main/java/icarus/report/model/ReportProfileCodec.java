package icarus.report.model;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.InputStream;

/**
 * Jackson codec for JSON serialization and parsing of ReportProfile definitions.
 */
public final class ReportProfileCodec {
    private static final ObjectMapper MAPPER = new ObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        .enable(SerializationFeature.INDENT_OUTPUT);

    private ReportProfileCodec() {}

    public static String toJson(ReportProfile profile) {
        try {
            return MAPPER.writeValueAsString(profile);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize ReportProfile to JSON", e);
        }
    }

    /** Import entry point: rejects input that is not a profile, or is a profile of another schema version. */
    public static ReportProfile fromJson(String json) {
        JsonNode node;
        try {
            node = MAPPER.readTree(json);
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse ReportProfile from JSON: " + e.getMessage(), e);
        }
        return fromNode(node);
    }

    /** Validates an already-parsed profile (import, or one element of the stored profile list). */
    public static ReportProfile fromNode(JsonNode node) {
        if (node == null || !node.isObject()) {
            throw new IllegalArgumentException("Not a report profile: expected a JSON object");
        }
        // A missing or blank version is accepted (older exports predate the field).
        JsonNode version = node.get("schemaVersion");
        if (version != null && !version.isNull() && !version.asText().isBlank()
                && !ReportProfile.CURRENT_SCHEMA_VERSION.equals(version.asText())) {
            throw new IllegalArgumentException("Unsupported profile version \"" + version.asText()
                + "\" (this ICARUS reads version " + ReportProfile.CURRENT_SCHEMA_VERSION + ")");
        }
        if (!node.hasNonNull("name") && !node.hasNonNull("sections")) {
            throw new IllegalArgumentException("Not a report profile: no name or sections");
        }
        try {
            return MAPPER.treeToValue(node, ReportProfile.class);
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse ReportProfile from JSON: " + e.getMessage(), e);
        }
    }

    public static ReportProfile fromStream(InputStream in) {
        try {
            return MAPPER.readValue(in, ReportProfile.class);
        } catch (Exception e) {
            throw new RuntimeException("Failed to load ReportProfile from stream: " + e.getMessage(), e);
        }
    }
}
