package icarus.report.model;

import com.fasterxml.jackson.databind.DeserializationFeature;
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

    public static ReportProfile fromJson(String json) {
        try {
            return MAPPER.readValue(json, ReportProfile.class);
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
